param([int]$AgentPort=8080,[int]$BusinessPort=8090,[string]$Database='report_mcp',[switch]$Mock,[switch]$Build,[switch]$Frontend,[int]$FrontendPort=5173,[ValidateSet('true','false')][string]$NativeSchema='true')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskRuntime=Join-Path $taskRoot '.runtime'
if ($Database -notmatch '^[a-zA-Z0-9_]+$') {throw 'Invalid database name.'}
$taskPorts=@($AgentPort,$BusinessPort)
if($Frontend){$taskPorts+= $FrontendPort}
if(@($taskPorts | Select-Object -Unique).Count -ne $taskPorts.Count){throw 'Service ports must be distinct.'}
foreach($taskPort in $taskPorts) {
    if($taskPort -lt 1 -or $taskPort -gt 65535){throw 'Invalid service port.'}
    if(Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue){throw "Port $taskPort is already in use. Stop the previous verified process first."}
}
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
$taskBusiness=$null; $taskAgent=$null; $taskUi=$null
try {
    # Optional local runtime model configuration; never committed or echoed.
    $taskModelSecrets=Join-Path $taskRuntime 'llm-credentials.json'
    if(-not $Mock -and -not $env:LLM_API_KEY -and (Test-Path -LiteralPath $taskModelSecrets)) {
        $taskModelCredentials=Get-Content -LiteralPath $taskModelSecrets -Raw | ConvertFrom-Json
        Set-TaskEnvironment 'LLM_API_KEY' $taskModelCredentials.apiKey
        if(-not $env:LLM_BASE_URL){Set-TaskEnvironment 'LLM_BASE_URL' $taskModelCredentials.baseUrl}
        if(-not $env:LLM_MODEL){Set-TaskEnvironment 'LLM_MODEL' $taskModelCredentials.model}
    }
    if(-not $Mock -and -not $env:LLM_API_KEY){throw 'Set LLM_API_KEY, LLM_BASE_URL and LLM_MODEL, or configure .runtime/llm-credentials.json.'}
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
        -ArgumentList @('-Dfile.encoding=UTF-8','-jar',"`"$taskBusinessJar`"","--server.port=$BusinessPort",'--server.address=127.0.0.1','--security.enabled=true') `
        -RedirectStandardOutput (Join-Path $taskRuntime 'business.stdout.log') -RedirectStandardError (Join-Path $taskRuntime 'business.stderr.log')
    $taskBusiness.Id | Set-Content (Join-Path $taskRuntime 'business.pid')
    Wait-TaskHealth "http://127.0.0.1:$BusinessPort/health" $taskBusiness
    Set-TaskEnvironment 'LLM_API_KEY' $taskModelKey
    Set-TaskEnvironment 'SEMANTIC_NATIVE_SCHEMA' $NativeSchema
    if($env:LLM_BASE_URL){Set-TaskEnvironment 'LLM_BASE_URL' ($env:LLM_BASE_URL.TrimEnd('/') -replace '/v1$','')}
    $taskProfiles=if($Mock){'mock,mcp'}else{'real,mcp'}
    $taskModelArgs=if($Mock){@()}else{@('--agent.llm.mock=false')}
    $taskAgentJar=Join-Path $taskRoot 'backend/target/report-demo-2.0.0.jar'
    $taskAgent=Start-Process java.exe -WindowStyle Hidden -PassThru -WorkingDirectory $taskRoot `
        -ArgumentList (@('-Dfile.encoding=UTF-8','-jar',"`"$taskAgentJar`"","--server.port=$AgentPort",'--server.address=127.0.0.1',"--spring.profiles.active=$taskProfiles",'--security.enabled=true','--agent.semantic.mode=active','--spring.flyway.enabled=false') + $taskModelArgs) `
        -RedirectStandardOutput (Join-Path $taskRuntime 'agent.stdout.log') -RedirectStandardError (Join-Path $taskRuntime 'agent.stderr.log')
    $taskAgent.Id | Set-Content (Join-Path $taskRuntime 'agent.pid')
    Wait-TaskHealth "http://127.0.0.1:$AgentPort/api/health/readiness" $taskAgent
    if($Frontend) {
        foreach($taskPrivateName in @('LLM_API_KEY','BUSINESS_SERVICE_TOKEN','AUTH_BOOTSTRAP_PASSWORD','DB_PASSWORD','REDIS_PASSWORD')) {Set-TaskEnvironment $taskPrivateName ''}
        Set-TaskEnvironment 'AGENT_API_URL' "http://127.0.0.1:$AgentPort"
        $taskVite=Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'
        $taskUi=Start-Process node.exe -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $taskRoot 'frontend') `
            -ArgumentList @("`"$taskVite`"",'--host','127.0.0.1','--port',"$FrontendPort",'--strictPort') `
            -RedirectStandardOutput (Join-Path $taskRuntime 'frontend.stdout.log') -RedirectStandardError (Join-Path $taskRuntime 'frontend.stderr.log')
        $taskUi.Id | Set-Content (Join-Path $taskRuntime 'frontend.pid')
        $taskDeadline=(Get-Date).AddSeconds(30)
        $taskReady=$false
        while((Get-Date) -lt $taskDeadline) {
            if($taskUi.HasExited){throw 'Frontend exited; inspect .runtime/frontend logs.'}
            try {
                if((Invoke-WebRequest "http://127.0.0.1:$FrontendPort" -TimeoutSec 2).StatusCode -eq 200){$taskReady=$true;break}
            } catch {}
            Start-Sleep -Milliseconds 500
        }
        if(-not $taskReady){throw 'Frontend did not become ready.'}
        Write-Output "Frontend ready: http://127.0.0.1:$FrontendPort"
    }
    Write-Output "Agent ready: http://127.0.0.1:$AgentPort; MCP ready: http://127.0.0.1:$BusinessPort/mcp; database: $Database"
    Write-Output "Initial login: admin. Password is in $taskSecrets (never committed)."
} catch {
    # Only stop processes created by this invocation, never arbitrary port owners.
    if($taskUi -and -not $taskUi.HasExited){Stop-Process -Id $taskUi.Id}
    if($taskAgent -and -not $taskAgent.HasExited){Stop-Process -Id $taskAgent.Id}
    if($taskBusiness -and -not $taskBusiness.HasExited){Stop-Process -Id $taskBusiness.Id}
    foreach($taskName in @('agent','business','frontend')) {
        Remove-Item -LiteralPath (Join-Path $taskRuntime "$taskName.pid") -Force -ErrorAction SilentlyContinue
    }
    throw
} finally {
    foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
}
