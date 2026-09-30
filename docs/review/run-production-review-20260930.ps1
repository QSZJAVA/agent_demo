$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $taskRoot
try {
    [xml]$taskXml = Get-Content backend/target/surefire-reports/TEST-com.example.report.semantic.SemanticIntegrationTest.xml
    $taskCp = ($taskXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    New-Item -ItemType Directory -Path backend/target/review-20260930-probe -Force | Out-Null
    & javac -proc:none -encoding UTF-8 -cp $taskCp -d backend/target/review-20260930-probe docs/review/ProductionReview20260930Probe.java
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
    & java '-Dfile.encoding=UTF-8' -cp ('backend/target/review-20260930-probe'+[IO.Path]::PathSeparator+$taskCp) ProductionReview20260930Probe
    if ($LASTEXITCODE -ne 0) { throw 'Java reproduction failed' }
    & node docs/review/production-review-20260930.probe.cjs
    if ($LASTEXITCODE -ne 0) { throw 'Browser logic reproduction failed' }
} finally { Pop-Location }
