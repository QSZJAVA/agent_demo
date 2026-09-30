$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $taskRoot
try {
    $taskSettings = Join-Path $taskRoot 'tools/env.local.cmd'
    if (Test-Path -LiteralPath $taskSettings) {
        Get-Content -LiteralPath $taskSettings | ForEach-Object {
            if ($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])) {
                [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
            }
        }
    }
    [xml]$taskXml = Get-Content backend/target/surefire-reports/TEST-com.example.report.semantic.SemanticIntegrationTest.xml
    $taskCp = ($taskXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $taskOutput = 'backend/target/identity-review-v2'
    New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
    & javac -proc:none -encoding UTF-8 -cp $taskCp -d $taskOutput docs/review/IdentityReview20260930V2Probe.java
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
    & java '-Dfile.encoding=UTF-8' -cp ($taskOutput+[IO.Path]::PathSeparator+$taskCp) IdentityReview20260930V2Probe
    if ($LASTEXITCODE -ne 0) { throw 'Probe failed' }
} finally { Pop-Location }
