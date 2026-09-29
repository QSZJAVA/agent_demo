param([int]$AgentPort=8080,[int]$BusinessPort=8090,[string]$Database='report_mcp',[switch]$Mock,[switch]$Build,[switch]$Frontend)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskRuntime=Join-Path $taskRoot '.runtime'
if ($Database -notmatch '^[a-zA-Z0-9_]+$') {throw 'Invalid database name.'}
foreach($taskPort in @($AgentPort,$BusinessPort)) {
    if(Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue){throw "Port $taskPort is already in use. Stop the previous verified process first."}
}
if(-not $Mock -and -not $env:LLM_API_KEY){throw 'Set LLM_API_KEY, LLM_BASE_URL and LLM_MODEL in the process environment.'}
if($Build) {
    & (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'pom.xml') package '-DskipTests' -q
    if($LASTEXITCODE -ne 0){throw 'Build failed.'}
}
New-Item -ItemType Directory -Force -Path $taskRuntime | Out-Null
# Runtime credentials are ignored by Git and readable only by the current Windows account.
& icacls.exe $taskRuntime /inheritance:r /grant:r "$($env:USERDOMAIN)\$($env:USERNAME):(OI)(CI)F" | Out-Null
if($LASTEXITCODE -ne 0){throw 'Cannot restrict runtime credential permissions.'}
$taskSecrets=Join-Path $taskRuntime 'mcp-credentials.json'
if(-not (Test-Path -LiteralPath $taskSecrets)) {
    $taskNew=@{serviceToken=[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(48));adminPassword=[Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24));adminUser='admin'}
    $taskNew | ConvertTo-Json | Set-Content -LiteralPath $taskSecrets -Encoding utf8
}
$taskCredentials=Get-Content -LiteralPath $taskSecrets -Raw | ConvertFrom-Json
$taskSaved=@{}
function Set-TaskEnvironment([string]$Name,[string]$Value) {
    if(-not $taskSaved.ContainsKey($Name)){$taskSaved[$Name]=[Environment]::GetEnvironmentVariable($Name)}
    [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
function Wait-TaskHealth([string]$Url,$Process) {
    $taskDeadline=(Get-Date).AddSeconds(60)
    while((Get-Date) -lt $taskDeadline) {
        if($Process.HasExited){throw 'Service exited; inspect .runtime logs.'}
        try {if((Invoke-RestMethod $Url -TimeoutSec 2).status -eq 'UP'){return}} catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "Service did not become ready: $Url"
}
$taskBusiness=$null; $taskAgent=$null
try {
    $taskLocal=Join-Path $PSScriptRoot 'env.local.cmd'
    if(Test-Path -LiteralPath $taskLocal) {
        Get-Content -LiteralPath $taskLocal | ForEach-Object {
            if($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])) {Set-TaskEnvironment $matches[1] $matches[2]}
        }
    }
    Set-TaskEnvironment 'DB_NAME' $Database
    Set-TaskEnvironment 'AUTH_TENANT_ID' 'T001'
    Set-TaskEnvironment 'BUSINESS_SERVICE_TOKEN' $taskCredentials.serviceToken
    Set-TaskEnvironment 'AUTH_BOOTSTRAP_PASSWORD' $taskCredentials.adminPassword
    Set-TaskEnvironment 'BUSINESS_MCP_URL' "http://127.0.0.1:$BusinessPort"
    Set-TaskEnvironment 'DEMO_RESET_ON_STARTUP' 'false'
    $taskModelKey=$env:LLM_API_KEY
    Set-TaskEnvironment 'LLM_API_KEY' ''
    $taskBusinessJar=Join-Path $taskRoot 'business-service/target/business-service-2.0.0.jar'
    $taskBusiness=Start-Process java.exe -WindowStyle Hidden -PassThru -WorkingDirectory $taskRoot `
        -ArgumentList @('-Dfile.encoding=UTF-8','-jar',"`"$taskBusinessJar`"","--server.port=$BusinessPort") `
        -RedirectStandardOutput (Join-Path $taskRuntime 'business.stdout.log') -RedirectStandardError (Join-Path $taskRuntime 'business.stderr.log')
    $taskBusiness.Id | Set-Content (Join-Path $taskRuntime 'business.pid')
    Wait-TaskHealth "http://127.0.0.1:$BusinessPort/health" $taskBusiness
    Set-TaskEnvironment 'LLM_API_KEY' $taskModelKey
    Set-TaskEnvironment 'SEMANTIC_NATIVE_SCHEMA' 'true'
    if($env:LLM_BASE_URL){Set-TaskEnvironment 'LLM_BASE_URL' ($env:LLM_BASE_URL.TrimEnd('/') -replace '/v1$','')}
    $taskProfiles=if($Mock){'mock,mcp'}else{'real,mcp'}
    $taskAgentJar=Join-Path $taskRoot 'backend/target/report-demo-2.0.0.jar'
    $taskAgent=Start-Process java.exe -WindowStyle Hidden -PassThru -WorkingDirectory $taskRoot `
        -ArgumentList @('-Dfile.encoding=UTF-8','-jar',"`"$taskAgentJar`"","--server.port=$AgentPort","--spring.profiles.active=$taskProfiles",'--spring.flyway.enabled=false') `
        -RedirectStandardOutput (Join-Path $taskRuntime 'agent.stdout.log') -RedirectStandardError (Join-Path $taskRuntime 'agent.stderr.log')
    $taskAgent.Id | Set-Content (Join-Path $taskRuntime 'agent.pid')
    Wait-TaskHealth "http://127.0.0.1:$AgentPort/api/health/readiness" $taskAgent
    if($Frontend -and -not (Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)) {
        foreach($taskPrivateName in @('LLM_API_KEY','BUSINESS_SERVICE_TOKEN','AUTH_BOOTSTRAP_PASSWORD','DB_PASSWORD','REDIS_PASSWORD')) {Set-TaskEnvironment $taskPrivateName ''}
        Set-TaskEnvironment 'AGENT_API_URL' "http://127.0.0.1:$AgentPort"
        $taskVite=Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'
        $taskUi=Start-Process node.exe -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $taskRoot 'frontend') `
            -ArgumentList @("`"$taskVite`"",'--host','127.0.0.1','--port','5173','--strictPort') `
            -RedirectStandardOutput (Join-Path $taskRuntime 'frontend.stdout.log') -RedirectStandardError (Join-Path $taskRuntime 'frontend.stderr.log')
        $taskUi.Id | Set-Content (Join-Path $taskRuntime 'frontend.pid')
    }
    Write-Output "Agent ready: http://127.0.0.1:$AgentPort; MCP ready: http://127.0.0.1:$BusinessPort/mcp; database: $Database"
    Write-Output "Initial login: admin. Password is in $taskSecrets (never committed)."
} catch {
    # Only stop processes created by this invocation, never arbitrary port owners.
    if($taskAgent -and -not $taskAgent.HasExited){Stop-Process -Id $taskAgent.Id}
    if($taskBusiness -and -not $taskBusiness.HasExited){Stop-Process -Id $taskBusiness.Id}
    throw
} finally {
    foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
}
