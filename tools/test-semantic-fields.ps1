param([string]$BaseUrl='http://127.0.0.1:8080/api/',[string]$EvidencePath='',[string]$CorpusPath='')
# 真实模型及认证HTTP MCP字段选择回放；仅创建测试会话/预览和修改选择，不确认派单。
# 使用已核实的专用Demo库及仅有A公司权限的测试账号；原始问法只保存在测试中，不作为提示词样例。
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskBaseline=Get-Content (Join-Path $taskRoot 'demo-baseline.json') -Raw|ConvertFrom-Json
if(-not $EvidencePath){$EvidencePath=Join-Path $taskRoot ('backend/target/semantic-fields-http-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'.json')}
$taskAccountPath=Join-Path $taskRoot '.runtime/semantic-fields-account.json'
$taskAccount=Get-Content -LiteralPath $taskAccountPath -Raw|ConvertFrom-Json
$taskLogin=Invoke-RestMethod ($BaseUrl+'auth/login') -Method Post -ContentType 'application/json' -Body (@{userId=$taskAccount.userId;password=$taskAccount.password}|ConvertTo-Json)
$taskHeaders=@{Authorization='Bearer '+$taskLogin.data.token}
$taskEvidence=@()
$taskInitialPreviews=@()
$taskLastRequest=[DateTime]::MinValue
function Save-FieldEvidence {
    @{date=[DateTime]::Now.ToString('o');scope='Real model and authenticated HTTP MCP; dedicated Demo records, no dispatch confirmation';corpus=$CorpusPath;corpusSha256=$(if($CorpusPath){(Get-FileHash -LiteralPath $CorpusPath -Algorithm SHA256).Hash}else{(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash});total=$taskEvidence.Count;passed=@($taskEvidence|Where-Object {$_.passed}).Count;businessCases=@($taskEvidence|Where-Object {$_.expectedPhase -eq 'READY'}).Count;businessPassed=@($taskEvidence|Where-Object {$_.expectedPhase -eq 'READY' -and $_.passed}).Count;expectedClarifications=@($taskEvidence|Where-Object {$_.expectedPhase -eq 'CLARIFY'}).Count;safeRefusals=@($taskEvidence|Where-Object {-not $_.passed -and $_.selection.phase -eq 'CLARIFY'}).Count;wrongReadyCount=@($taskEvidence|Where-Object {-not $_.passed -and $_.selection.phase -eq 'READY'}).Count;initialPreviews=$taskInitialPreviews;cases=$taskEvidence}|
      ConvertTo-Json -Depth 40|Set-Content -LiteralPath $EvidencePath -Encoding utf8
    Write-Output ('Evidence: '+$EvidencePath)
}
function Invoke-FieldTurn($Conversation,[string]$Message) {
    # 普通业务账号每分钟限流保持不变；回放串行且每次间隔至少3秒，避免测试自身形成突发流量。
    $taskWait=3000-([DateTime]::UtcNow-$script:taskLastRequest).TotalMilliseconds
    if($taskWait -gt 0){Start-Sleep -Milliseconds ([int]$taskWait)}
    $script:taskLastRequest=[DateTime]::UtcNow
    $response=Invoke-WebRequest ($BaseUrl+'agent/chat') -Method Post -ContentType 'application/json' -Headers $taskHeaders -TimeoutSec 120 -Body (@{conversationId=$Conversation;message=$Message}|ConvertTo-Json)
    $events=@()
    foreach($block in ($response.Content.Replace("`r`n","`n") -split "`n`n")) {
        $kind=$null;$data=$null
        foreach($line in ($block -split "`n")) {if($line.StartsWith('event:')){$kind=$line.Substring(6).Trim()};if($line.StartsWith('data:')){$data=$line.Substring(5).Trim()|ConvertFrom-Json}}
        if($kind){$events+=@{type=$kind;data=$data}}
    }
    if(-not ($events.type -contains 'done')){throw 'Incomplete SSE turn'}
    return ,$events
}
$taskScenarios=@(
    @{name='用户原始连续两句';turns=@(@{message='销售报表金额大于96000的不要';docs=@('SO2026001')},@{message='销售报表SO2026002不要';docs=@('SO2026001','SO2026002')})},
    @{name='含等号边界及恢复';turns=@(@{message='销售报表金额至少96000元的先不要';docs=@('SO2026001','SO2026007')},@{message='销售报表金额等于96000的恢复';docs=@('SO2026001')})},
    @{name='区间保留及限定恢复';turns=@(@{message='销售报表只保留金额在30000到100000之间的记录';docs=@('SO2026001')},@{message='销售报表的记录全部恢复';docs=@()})},
    @{name='日期和文本筛选';turns=@(@{message='销售报表销售日期早于2026-04-01的不要';docs=@('SO2026001','SO2026002')},@{message='销售报表产品名称包含云的也不要';docs=@('SO2026001','SO2026002','SO2026007')})},
    @{name='与或组合';turns=@(@{message='销售报表金额大于30000而且产品名称包含云的都不要';docs=@('SO2026007')},@{message='恢复销售报表全部记录';docs=@()},@{message='销售报表金额大于100000或者产品名称等于交换机的都不要';docs=@('SO2026001','SO2026002')})},
    @{name='布尔零匹配与失败恢复';turns=@(@{message='销售报表已派单标志为true的记录不要';docs=@()},@{message='按信用评分排序';docs=@();clarify=$true},@{message='销售报表SO2026002不要';docs=@('SO2026002')})}
)
# 独立语料在调用前固定；执行过程只读，不按模型输出改变预期集合。
if($CorpusPath){$taskScenarios=Get-Content -LiteralPath $CorpusPath -Raw|ConvertFrom-Json}
foreach($scenario in $taskScenarios) {
    $initial=Invoke-FieldTurn $null '查一下我有哪些可以派单'
    $conversation=($initial|Where-Object {$_.type -eq 'conversation'}).data.conversationId
    $preview=($initial|Where-Object {$_.type -eq 'preview'}).data
    if($preview.total -ne $taskBaseline.demoPreviewCount -or $preview.byReport.Count -ne 3){
        # 已派单数据或初始模型失败都要保留证据，不能为凑齐九条而复位来源业务状态。
        $taskEvidence+=@{scenario=$scenario.name;stage='INITIAL_PREVIEW';passed=$false;expectedTotal=$taskBaseline.demoPreviewCount;actualTotal=$preview.total;events=$initial}
        Save-FieldEvidence
        throw 'Nine undispatched Demo records required; inspect saved evidence. Existing dispatch state was not reset.'
    }
    $taskInitialPreviews+=@{scenario=$scenario.name;previewId=$preview.previewId;documents=@($preview.records|ForEach-Object {$_.docNo})}
    foreach($turn in $scenario.turns) {
        if(@($turn.docs|Where-Object {$_ -notin $preview.records.docNo}).Count){throw 'Corpus expects a document outside the actual eligible preview. Correct the fixture; do not reset source data.'}
        $events=Invoke-FieldTurn $conversation $turn.message
        $selection=(Invoke-RestMethod ($BaseUrl+'agent/conversations/'+$conversation+'/selection') -Headers $taskHeaders).data
        $keys=@($selection.excludedRecords|ForEach-Object {$_.reportId+':'+$_.recordId})
        $actual=@($preview.records|Where-Object {($_.reportId+':'+$_.recordId) -in $keys}|ForEach-Object {$_.docNo}|Sort-Object)
        $wanted=@($turn.docs|Sort-Object)
        $passed=(($actual -join '|') -eq ($wanted -join '|') -and $selection.previewId -eq $preview.previewId -and -not ($events.type -contains 'preview') -and -not ($events.type -contains 'plan') -and -not ($events.type -contains 'error'))
        $passed=$passed -and ($selection.phase -eq $(if($turn.clarify){'CLARIFY'}else{'READY'}))
        if($turn.clarify){$passed=$passed -and (($events|Where-Object {$_.type -eq 'text'}).data.delta -join '').Contains('未应用')}
        $taskEvidence+=@{scenario=$scenario.name;message=$turn.message;passed=$passed;expectedPhase=$(if($turn.clarify){'CLARIFY'}else{'READY'});expectedDocuments=$wanted;actualDocuments=$actual;conversationId=$conversation;previewId=$preview.previewId;events=$events;selection=$selection}
        Write-Output ($scenario.name+': '+$(if($passed){'PASS'}else{'FAIL'}))
    }
}
Save-FieldEvidence
if(@($taskEvidence|Where-Object {-not $_.passed}).Count){exit 1}
