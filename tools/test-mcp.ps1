param([string]$Tests='BusinessMcpIntegrationTest,SessionFilterTest')
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
$env:MCP_IT='true';$env:DEMO_IT='false';$env:TRACE_IT='false';$env:P2_IT='false'
& (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'pom.xml') test "-Dtest=$Tests" '-Dsurefire.failIfNoSpecifiedTests=false' -q
exit $LASTEXITCODE
