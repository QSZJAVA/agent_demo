param([string]$Tests = '', [switch]$BackendOnly)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
# Read only known local settings as data. Never execute private configuration or print credentials.
$localSettings = Join-Path $PSScriptRoot 'env.local.cmd'
if (Test-Path -LiteralPath $localSettings) {
    Get-Content -LiteralPath $localSettings | ForEach-Object {
        if ($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$') {
            if (-not [Environment]::GetEnvironmentVariable($matches[1])) {
                [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
            }
        }
    }
}
foreach ($pair in @(@('TRACE_DB_HOST','DB_HOST'),@('TRACE_DB_PORT','DB_PORT'),@('TRACE_DB_USER','DB_USERNAME'),@('TRACE_DB_PASSWORD','DB_PASSWORD'),@('TRACE_REDIS_HOST','REDIS_HOST'),@('TRACE_REDIS_PORT','REDIS_PORT'),@('TRACE_REDIS_PASSWORD','REDIS_PASSWORD'))) {
    $value = [Environment]::GetEnvironmentVariable($pair[1])
    if ($null -ne $value -and -not [Environment]::GetEnvironmentVariable($pair[0])) {
        [Environment]::SetEnvironmentVariable($pair[0],$value,'Process')
    }
}
$env:TRACE_IT='true'
$env:P2_IT='true'
# This entry point only runs isolated databases, regardless of inherited shell settings.
$env:DEMO_IT='false'
$env:P2_UI='false'
Push-Location (Join-Path $taskRoot 'backend')
try {
    $argsForMaven = @('test','-q')
    if ($Tests) { $argsForMaven += "-Dtest=$Tests" }
    & .\mvnw.cmd @argsForMaven
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
if (-not $BackendOnly) {
    Push-Location (Join-Path $taskRoot 'frontend')
    try {
        & npm.cmd test
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
}
