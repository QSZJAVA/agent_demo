#Requires -Version 7.2
param(
    [ValidateRange(1,65535)][int]$AgentPort=8080,
    [ValidateRange(1,65535)][int]$BusinessPort=8090,
    [ValidateRange(1,65535)][int]$FrontendPort=5173,
    [ValidatePattern('^[a-zA-Z0-9_]+$')][string]$Database='',
    [switch]$SkipBuild,
    [switch]$CheckOnly
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskBaseline=Get-Content (Join-Path $taskRoot 'demo-baseline.json') -Raw|ConvertFrom-Json
if(-not $Database){$Database=$taskBaseline.database}
# 演示库名称固定；禁止启动时另建带版本号或日期的新库。
if($Database -ne 'report_demo'){throw 'Demo database is fixed to report_demo. Do not create another database without explicit user authorization.'}
$taskSaved=@{}
function Set-AppEnvironment([string]$Name,[string]$Value) {
    if(-not $taskSaved.ContainsKey($Name)){$taskSaved[$Name]=[Environment]::GetEnvironmentVariable($Name,'Process')}
    [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
function Test-AppConnection([string]$HostName,[int]$Port,[string]$Label) {
    $taskClient=[Net.Sockets.TcpClient]::new()
    try {
        $taskConnect=$taskClient.ConnectAsync($HostName,$Port)
        if(-not $taskConnect.Wait(3000) -or -not $taskClient.Connected){throw 'Connection timeout.'}
    } catch {throw "$Label is unavailable at ${HostName}:$Port. Start the dependency or correct the local configuration."}
    finally {$taskClient.Dispose()}
}
try {
    # Read allowlisted SET assignments as data, never execute private CMD content.
    $taskSettings=Join-Path $PSScriptRoot 'env.local.cmd'
    if(Test-Path -LiteralPath $taskSettings) {
        foreach($taskLine in Get-Content -LiteralPath $taskSettings) {
            if($taskLine -match '^\s*@?set\s+"?((?:DB|REDIS|LLM|SEMANTIC|INVESTIGATION)_[A-Z_]+|JAVA_HOME|CORS_ALLOWED_ORIGINS|TRUSTED_PROXY_CIDRS)=(.*?)"?\s*$') {
                $taskName=$matches[1]; $taskValue=$matches[2]
                if(-not [Environment]::GetEnvironmentVariable($taskName,'Process')){Set-AppEnvironment $taskName $taskValue}
            }
        }
    }
    $taskModelPath=Join-Path $taskRoot '.runtime/llm-credentials.json'
    if(Test-Path -LiteralPath $taskModelPath) {
        $taskModel=Get-Content -LiteralPath $taskModelPath -Raw | ConvertFrom-Json
        foreach($taskPair in @(@('LLM_API_KEY','apiKey'),@('LLM_BASE_URL','baseUrl'),@('LLM_MODEL','model'))) {
            if(-not [Environment]::GetEnvironmentVariable($taskPair[0],'Process')){Set-AppEnvironment $taskPair[0] $taskModel.($taskPair[1])}
        }
    }
    foreach($taskName in @('LLM_API_KEY','LLM_BASE_URL','LLM_MODEL')) {
        if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($taskName,'Process'))){throw "Missing $taskName. Configure environment variables, tools/env.local.cmd or .runtime/llm-credentials.json."}
    }
    $taskUri=$null
    if(-not [Uri]::TryCreate($env:LLM_BASE_URL,[UriKind]::Absolute,[ref]$taskUri) -or $taskUri.Scheme -notin @('http','https')){throw 'LLM_BASE_URL must be an HTTP(S) base URL.'}
    if($env:JAVA_HOME){Set-AppEnvironment 'PATH' ((Join-Path $env:JAVA_HOME 'bin')+';'+$env:PATH)}
    foreach($taskCommand in @('java.exe','javac.exe','node.exe','npm.cmd')) {Get-Command $taskCommand -ErrorAction Stop | Out-Null}
    $taskJavaVersion=(& java.exe -version 2>&1 | Out-String)
    if($taskJavaVersion -notmatch 'version "(\d+)' -or [int]$matches[1] -lt 17){throw 'JDK 17 or newer is required.'}
    $taskPorts=@($AgentPort,$BusinessPort,$FrontendPort)
    if(@($taskPorts | Select-Object -Unique).Count -ne 3){throw 'Service ports must be distinct.'}
    foreach($taskPort in $taskPorts) {
        if(Get-NetTCPConnection -State Listen -LocalPort $taskPort -ErrorAction SilentlyContinue){throw "Port $taskPort is occupied. Run tools/stop-app.cmd for this workspace before restarting."}
    }
    Test-AppConnection $(if($env:DB_HOST){$env:DB_HOST}else{'localhost'}) $(if($env:DB_PORT){[int]$env:DB_PORT}else{3306}) 'MySQL'
    Test-AppConnection $(if($env:REDIS_HOST){$env:REDIS_HOST}else{'localhost'}) $(if($env:REDIS_PORT){[int]$env:REDIS_PORT}else{6379}) 'Redis'
    $taskSchema=if($env:SEMANTIC_NATIVE_SCHEMA){$env:SEMANTIC_NATIVE_SCHEMA}else{'true'}
    if($taskSchema -notin @('true','false')){throw 'SEMANTIC_NATIVE_SCHEMA must be true or false.'}
    if($CheckOnly){Write-Output 'Configuration and dependency checks passed. Model authentication and output have not been verified.';return}
    if(-not $SkipBuild) {
        Write-Output 'Building Agent and HTTP MCP business service...'
        & (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'pom.xml') package '-DskipTests' -q
        if($LASTEXITCODE -ne 0){throw 'Backend build failed.'}
    }
    foreach($taskJar in @('backend/target/report-demo-1.0.0.jar','business-service/target/business-service-1.0.0.jar')) {
        if(-not (Test-Path -LiteralPath (Join-Path $taskRoot $taskJar))){throw "Missing $taskJar. Start without -SkipBuild."}
    }
    if(-not (Test-Path -LiteralPath (Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js'))) {
        & npm.cmd --prefix (Join-Path $taskRoot 'frontend') ci
        if($LASTEXITCODE -ne 0){throw 'Frontend dependency installation failed.'}
    }
    & (Join-Path $PSScriptRoot 'start-mcp.ps1') -AgentPort $AgentPort -BusinessPort $BusinessPort -FrontendPort $FrontendPort -Database $Database -Frontend -NativeSchema $taskSchema
    Write-Output 'Services ready: real,mcp; semantic mode=active. Send a message in the UI to verify the actual model call.'
} finally {
    foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
}
