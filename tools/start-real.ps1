param([string]$BaseUrl = $env:LLM_BASE_URL, [string]$Model = $env:LLM_MODEL, [int]$Port = 8080, [switch]$Frontend, [switch]$NativeSchema)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
if (-not $env:LLM_API_KEY) { throw 'Set LLM_API_KEY in the process environment before starting.' }
if (-not $BaseUrl -or -not $Model) { throw 'BaseUrl and Model are required.' }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is already in use." }
$taskJar = Join-Path $taskRoot 'backend/target/report-demo-2.0.0.jar'
if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Build the backend jar with mvnw.cmd package first.' }
$taskOriginal = @{}
try {
    # Treat private local settings as data; never execute them or print credentials.
    $taskSettings = Join-Path $PSScriptRoot 'env.local.cmd'
    if (Test-Path -LiteralPath $taskSettings) {
        Get-Content -LiteralPath $taskSettings | ForEach-Object {
            if ($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])) {
                $taskOriginal[$matches[1]] = [Environment]::GetEnvironmentVariable($matches[1])
                [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
            }
        }
    }
    foreach ($name in @('LLM_BASE_URL','LLM_MODEL','SEMANTIC_NATIVE_SCHEMA','LLM_API_KEY')) {
        $taskOriginal[$name] = [Environment]::GetEnvironmentVariable($name)
    }
    $env:LLM_BASE_URL = $BaseUrl.TrimEnd('/') -replace '/v1$', ''
    $env:LLM_MODEL = $Model
    $env:SEMANTIC_NATIVE_SCHEMA = if ($NativeSchema) { 'true' } else { 'false' }
    $taskBackend = Start-Process -FilePath (Get-Command java.exe).Source -WindowStyle Hidden -PassThru -WorkingDirectory $taskRoot `
        -ArgumentList @('-Dfile.encoding=UTF-8','-jar', $taskJar, "--server.port=$Port",'--server.address=127.0.0.1','--spring.profiles.active=real','--agent.llm.mock=false','--agent.semantic.mode=active','--demo.reset-on-startup=false') `
        -RedirectStandardOutput (Join-Path $taskRoot 'backend/target/real-server.stdout.log') `
        -RedirectStandardError (Join-Path $taskRoot 'backend/target/real-server.stderr.log')
    $taskBackend.Id | Set-Content (Join-Path $taskRoot 'backend/target/real-server.pid')
    Write-Output "Backend started: PID=$($taskBackend.Id), http://127.0.0.1:$Port (check readiness before use)."
    # The frontend has no reason to inherit the model credential.
    $env:LLM_API_KEY = $null
    if ($Frontend -and -not (Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)) {
        $taskUi = Start-Process -FilePath (Get-Command node.exe).Source -WindowStyle Hidden -PassThru -WorkingDirectory (Join-Path $taskRoot 'frontend') `
            -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1') `
            -RedirectStandardOutput (Join-Path $taskRoot 'backend/target/frontend.stdout.log') `
            -RedirectStandardError (Join-Path $taskRoot 'backend/target/frontend.stderr.log')
        $taskUi.Id | Set-Content (Join-Path $taskRoot 'backend/target/frontend.pid')
        Write-Output "Frontend started: PID=$($taskUi.Id), http://127.0.0.1:5173."
    }
} finally {
    foreach ($name in $taskOriginal.Keys) { [Environment]::SetEnvironmentVariable($name,$taskOriginal[$name],'Process') }
}
