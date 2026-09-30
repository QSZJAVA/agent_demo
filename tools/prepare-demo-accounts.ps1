param([string]$BaseUrl='http://127.0.0.1:8080')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskRuntime=Join-Path $taskRoot '.runtime'
$taskAdmin=Get-Content -LiteralPath (Join-Path $taskRuntime 'mcp-credentials.json') -Raw | ConvertFrom-Json
$taskPath=Join-Path $taskRuntime 'demo-accounts.json'
$taskDefinitions=@(
    @{userId='demo_admin';displayName='演示管理员';companies=@('A','B','C');permissions=@('*');admin=$true},
    @{userId='demo_a';displayName='A公司业务员';companies=@('A');permissions=@('report:sales','report:receivable','report:expense');admin=$false},
    @{userId='demo_b';displayName='B公司业务员';companies=@('B');permissions=@('report:sales','report:receivable','report:expense');admin=$false},
    @{userId='demo_sales';displayName='A公司销售业务员';companies=@('A');permissions=@('report:sales');admin=$false}
)
$taskStored=@{}
if(Test-Path -LiteralPath $taskPath) {
    (Get-Content -LiteralPath $taskPath -Raw | ConvertFrom-Json).accounts | ForEach-Object {$taskStored[$_.userId]=$_.password}
}
$taskAccounts=@()
foreach($taskDefinition in $taskDefinitions) {
    $taskPassword=$taskStored[$taskDefinition.userId]
    if(-not $taskPassword){$taskPassword='Demo@'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(8))}
    $taskAccounts+=@{userId=$taskDefinition.userId;displayName=$taskDefinition.displayName;companies=$taskDefinition.companies;
        permissions=$taskDefinition.permissions;admin=$taskDefinition.admin;enabled=$true;password=$taskPassword}
}
# Save credentials before mutation so an interrupted run can safely reuse the same accounts/passwords.
@{baseUrl=$BaseUrl;createdAt=(Get-Date).ToString('o');accounts=$taskAccounts} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $taskPath -Encoding utf8
function Invoke-DemoApi($Method,$Path,$Body,$Token) {
    $taskArguments=@{Method=$Method;Uri="$BaseUrl$Path";TimeoutSec=30;ContentType='application/json; charset=utf-8'}
    if($null -ne $Body){$taskArguments.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 6 -Compress))}
    if($Token){$taskArguments.Headers=@{Authorization="Bearer $Token"}}
    $taskResult=Invoke-RestMethod @taskArguments
    if($taskResult.code -ne 0){throw "API failed ($($taskResult.code)): $($taskResult.message)"}
    return $taskResult.data
}
$taskSession=Invoke-DemoApi 'POST' '/api/auth/login' @{userId=$taskAdmin.adminUser;password=$taskAdmin.adminPassword} $null
$taskChecks=@()
try {
    foreach($taskAccount in $taskAccounts) {
        Invoke-DemoApi 'PUT' '/api/auth/users' $taskAccount $taskSession.token | Out-Null
        $taskUserSession=Invoke-DemoApi 'POST' '/api/auth/login' @{userId=$taskAccount.userId;password=$taskAccount.password} $null
        try {
            $taskMe=Invoke-DemoApi 'GET' '/api/auth/me' $null $taskUserSession.token
            if($taskMe.userId -ne $taskAccount.userId -or $taskMe.admin -ne $taskAccount.admin){throw 'Identity verification failed'}
            $taskCatalog=@(Invoke-DemoApi 'GET' '/api/report-catalog' $null $taskUserSession.token)
            $taskExpected=if($taskAccount.userId -eq 'demo_sales'){@('sales')}else{@('sales','receivable','expense')}
            $taskCodes=@($taskCatalog | ForEach-Object {$_.reportCode})
            if(@(Compare-Object ($taskExpected|Sort-Object) ($taskCodes|Sort-Object)).Count -ne 0){throw "Catalog grants differ for $($taskAccount.userId)"}
            $taskPage=Invoke-DemoApi 'GET' '/api/report/sales/page?page=1&size=50' $null $taskUserSession.token
            if(@($taskPage.records | Where-Object {$_.companyCode -notin $taskAccount.companies}).Count -gt 0){throw 'Company isolation failed'}
            $taskChecks+=@{userId=$taskAccount.userId;login=$true;admin=$taskMe.admin;reportCodes=$taskCodes;salesRows=$taskPage.records.Count}
        } finally {Invoke-DemoApi 'POST' '/api/auth/logout' $null $taskUserSession.token | Out-Null}
    }
} finally {Invoke-DemoApi 'POST' '/api/auth/logout' $null $taskSession.token | Out-Null}
$taskChecks | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskRuntime 'demo-account-checks.json') -Encoding utf8
$taskLines=@('# 演示账号','','访问地址：http://127.0.0.1:5173','','| 账号 | 密码 | 公司范围 | 权限 |','| --- | --- | --- | --- |')
foreach($taskAccount in $taskAccounts) {
    $taskRole=if($taskAccount.admin){'管理员；全部报表、目录、规则、运营'}elseif($taskAccount.userId -eq 'demo_sales'){'仅销售报表'}else{'销售、应收、费用报表'}
    $taskLines+="| $($taskAccount.userId) | $($taskAccount.password) | $($taskAccount.companies -join '/') | $taskRole |"
}
$taskLines+=@('','以上为启用的真实登录账号。业务员可在自身权限范围内执行派单。','登录、报表权限及公司隔离已验证。测试会话已退出，账号密码可直接使用。')
$taskLines | Set-Content -LiteralPath (Join-Path $taskRuntime 'demo-accounts.md') -Encoding utf8
Write-Output "Prepared and verified $($taskAccounts.Count) demo accounts. Credentials: $taskRuntime\demo-accounts.md"
$taskChecks | Select-Object userId,login,admin,salesRows | Format-Table
