param([switch]$BackendOnly)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskSettings=Join-Path $PSScriptRoot 'env.local.cmd'
if(Test-Path -LiteralPath $taskSettings) {
    Get-Content -LiteralPath $taskSettings | ForEach-Object {
        if($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])) {
            [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')
        }
    }
}
foreach($taskPair in @(@('TRACE_DB_HOST','DB_HOST'),@('TRACE_DB_PORT','DB_PORT'),@('TRACE_DB_USER','DB_USERNAME'),@('TRACE_DB_PASSWORD','DB_PASSWORD'),@('TRACE_REDIS_HOST','REDIS_HOST'),@('TRACE_REDIS_PORT','REDIS_PORT'),@('TRACE_REDIS_PASSWORD','REDIS_PASSWORD'))) {
    $taskValue=[Environment]::GetEnvironmentVariable($taskPair[1])
    if($null -ne $taskValue -and -not [Environment]::GetEnvironmentVariable($taskPair[0])) {
        [Environment]::SetEnvironmentVariable($taskPair[0],$taskValue,'Process')
    }
}
$env:TRACE_IT='true';$env:P2_IT='true';$env:MCP_IT='true';$env:DEMO_IT='false';$env:P2_UI='false'
$env:SEMANTIC_LIVE='false'
$env:INVESTIGATION_LIVE='false';$env:INVESTIGATION_JOINT='false';$env:INVESTIGATION_UI='false'
& node (Join-Path $taskRoot 'tools/check-comments.cjs')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& node --test (Join-Path $taskRoot 'tools/compare-investigation-evaluations.test.cjs')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'pom.xml') verify -q
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
if(-not $BackendOnly) {
    & npm.cmd --prefix (Join-Path $taskRoot 'frontend') test
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
    & npm.cmd --prefix (Join-Path $taskRoot 'frontend') run build
    if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
}
& node (Join-Path $taskRoot 'tools/check-docs-real-model.cjs')
exit $LASTEXITCODE
