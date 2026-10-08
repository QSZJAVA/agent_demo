param([string]$BaseUrl='http://127.0.0.1:8080/api/',[string]$CorpusPath='',[string]$EvidencePath='')
# 真实模型与认证HTTP MCP的通用助手回放：只查询并创建会话，禁止出现派单预览/清单/执行结果事件。
# 固定语料与独立报表页事实共同评分；失败逐项保存，不通过改变预期或删除问法提高通过率。
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
if(-not $CorpusPath){$CorpusPath=Join-Path $PSScriptRoot 'fixtures/business-assistant-corpus.json'}
if(-not $EvidencePath){$EvidencePath=Join-Path $taskRoot ('.runtime/generic-assistant/live-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'.json')}
$taskAccount=Get-Content -LiteralPath (Join-Path $taskRoot '.runtime/semantic-fields-account.json') -Raw|ConvertFrom-Json
$taskLogin=Invoke-RestMethod ($BaseUrl+'auth/login') -Method Post -ContentType 'application/json' -Body (@{userId=$taskAccount.userId;password=$taskAccount.password}|ConvertTo-Json)
$taskHeaders=@{Authorization='Bearer '+$taskLogin.data.token}
$taskSales=(Invoke-RestMethod ($BaseUrl+'report/sales/page?page=1&size=200') -Headers $taskHeaders).data
$taskExpenses=(Invoke-RestMethod ($BaseUrl+'report/expense/page?page=1&size=200') -Headers $taskHeaders).data
if($taskSales.total -gt 200 -or $taskExpenses.total -gt 200){throw 'Acceptance fixture must fit the independently fetched source page.'}
$taskScenarios=Get-Content -LiteralPath $CorpusPath -Raw|ConvertFrom-Json
$taskCases=[System.Collections.Generic.List[object]]::new()
$taskLastRequest=[DateTime]::MinValue
function Save-BusinessEvidence {
    @{recordedAt=[DateTime]::Now.ToString('o');scope='Real model plus authenticated HTTP MCP; no dispatch or approval; programmatic checks, not browser acceptance';corpusSha256=(Get-FileHash -LiteralPath $CorpusPath -Algorithm SHA256).Hash;total=$taskCases.Count;passed=@($taskCases|Where-Object passed).Count;sourceSales=$taskSales;sourceExpenses=$taskExpenses;cases=$taskCases.ToArray()}|ConvertTo-Json -Depth 55|Set-Content -LiteralPath $EvidencePath -Encoding utf8
}
function Invoke-BusinessTurn($Conversation,[string]$Message) {
    $taskWait=3000-([DateTime]::UtcNow-$script:taskLastRequest).TotalMilliseconds
    if($taskWait -gt 0){Start-Sleep -Milliseconds ([int]$taskWait)}
    $script:taskLastRequest=[DateTime]::UtcNow
    $response=Invoke-WebRequest ($BaseUrl+'agent/chat') -Method Post -ContentType 'application/json' -Headers $taskHeaders -TimeoutSec 180 -Body (@{conversationId=$Conversation;message=$Message}|ConvertTo-Json)
    $events=@()
    foreach($block in ($response.Content.Replace("`r`n","`n") -split "`n`n")) {
        $kind=$null;$data=$null
        foreach($line in ($block -split "`n")){if($line.StartsWith('event:')){$kind=$line.Substring(6).Trim()};if($line.StartsWith('data:')){$data=$line.Substring(5).Trim()|ConvertFrom-Json}}
        if($kind){$events+=@{type=$kind;data=$data}}
    }
    return ,$events
}
foreach($scenario in $taskScenarios) {
    $taskConversation=$null
    foreach($turn in $scenario.turns) {
        $errors=[System.Collections.Generic.List[string]]::new();$events=@();$result=$null
        try {
            $events=Invoke-BusinessTurn $taskConversation $turn.message
            if(-not ($events.type -contains 'done')){$errors.Add('Incomplete stream')}
            $taskConversation=($events|Where-Object {$_.type -eq 'conversation'}).data.conversationId
            if(@($events|Where-Object {$_.type -in @('preview','plan','result')}).Count){$errors.Add('Read request entered dispatch path')}
            $result=($events|Where-Object {$_.type -eq 'business_query'}).data
            $reply=($events|Where-Object {$_.type -in @('text','error')}|ForEach-Object {$_.data.delta;$_.data.message}) -join ''
            if($turn.noQuery){if($result){$errors.Add('Expected clarification or rejection')}}
            elseif(-not $result){$errors.Add('Missing business query result')}
            else {
                foreach($key in @('domain','view','sortField','descending','page','size','groupBy')) {
                    if($null -ne $turn.$key -and $turn.$key -cne $result.query.$key){$errors.Add('Unexpected '+$key)}
                }
                if($null -ne $turn.total -and $turn.total -ne $result.total){$errors.Add('Wrong full total')}
                if($turn.status -and @($result.query.conditions.allOf|Where-Object {$_.field -eq 'status' -and $_.values -contains $turn.status}).Count -eq 0){$errors.Add('Missing status condition')}
                if($turn.requiredFilter -and @($result.query.conditions.allOf|Where-Object {$_.field -ceq $turn.requiredFilter.field -and $_.values -ccontains $turn.requiredFilter.value}).Count -eq 0){$errors.Add('Stable query identifier was not preserved')}
                if($null -ne $turn.eligible){
                    $taskEligibility=$result.rows[0].eligibility
                    if($result.total -ne 1 -or $null -eq $taskEligibility -or $null -eq $taskEligibility.eligible){$errors.Add('Missing single-record qualification evidence')}
                    elseif($taskEligibility.eligible -cne $turn.eligible){$errors.Add('Wrong dispatch qualification')}
                    if($null -ne $turn.ruleVersion -and $taskEligibility.ruleVersion -ne $turn.ruleVersion){$errors.Add('Wrong effective rule version')}
                }
                if($turn.orderIds){
                    $actual=@($result.rows.orderId|Sort-Object);$wanted=@($turn.orderIds|Sort-Object)
                    if(($actual -join '|') -cne ($wanted -join '|') -or $result.total -ne $wanted.Count){$errors.Add('Wrong work order set')}
                }
                foreach($key in @('stage','assignee')){if($turn.$key -and $result.rows[0].$key -cne $turn.$key){$errors.Add('Wrong '+$key)}}
                if($turn.pendingApprovers){foreach($property in $turn.pendingApprovers.PSObject.Properties){if($result.summary.pendingApprovers.($property.Name) -ne $property.Value){$errors.Add('Wrong current approver count')}}}
                if($turn.recordSet){
                    $wantedRows=@(switch($turn.recordSet){
                        'sales_all' {$taskSales.records}
                        'sales_gt_50000' {$taskSales.records|Where-Object {[decimal]$_.amount -gt 50000}}
                        'sales_gte_96000' {$taskSales.records|Where-Object {[decimal]$_.amount -ge 96000}}
                        'sales_cloud' {$taskSales.records|Where-Object {$_.productName -like '*云*'}}
                        'sales_before_0401' {$taskSales.records|Where-Object {$_.saleDate -and [DateTime]$_.saleDate -lt [DateTime]'2026-04-01'}}
                        'expense_all' {$taskExpenses.records}
                        default {throw 'Unknown source fixture'}
                    })
                    # 预期集合来自独立报表页，再按事先固定的业务值筛选，不从模型返回集合反推预期。
                    if($turn.sourceEquals){$wantedRows=@($wantedRows|Where-Object {[string]$_.($turn.sourceEquals.field) -ceq $turn.sourceEquals.value})}
                    if($wantedRows.Count -ne $result.total){$errors.Add('Total differs from independent report page')}
                    if($turn.sortField -eq 'amount'){$wantedRows=@($wantedRows|Sort-Object {[decimal]$_.amount} -Descending:([bool]$turn.descending))}
                    $expectedPage=@($wantedRows|Select-Object -Skip (($result.query.page-1)*$result.query.size) -First $result.query.size)
                    $actual=@($result.rows.recordId|Sort-Object);$wanted=@($expectedPage.id|ForEach-Object {"$_"}|Sort-Object)
                    if(($actual -join '|') -cne ($wanted -join '|')){$errors.Add('Rows differ from independent report page')}
                    [decimal]$sum=0;foreach($row in $wantedRows){$sum+=[decimal]$row.amount}
                    if($wantedRows.Count -gt 0 -and [decimal]$result.summary.amountsByCurrency.CNY -ne $sum){$errors.Add('Amount total differs from independent source')}
                }
                if($result.summary.count -ne $result.total){$errors.Add('Inconsistent summary count')}
            }
            if($turn.textContains -and -not $reply.Contains($turn.textContains)){$errors.Add('Expected explanation missing')}
        } catch {$errors.Add($_.Exception.GetType().Name)}
        $taskCases.Add(@{scenario=$scenario.name;message=$turn.message;expected=$turn;conversationId=$taskConversation;passed=$errors.Count -eq 0;errors=$errors.ToArray();events=$events})
        Save-BusinessEvidence
        Write-Output ($scenario.name+': '+$(if($errors.Count -eq 0){'PASS'}else{'FAIL '+($errors -join '; ')}))
    }
}
Write-Output ('Evidence: '+$EvidencePath)
if(@($taskCases|Where-Object {-not $_.passed}).Count){exit 1}
