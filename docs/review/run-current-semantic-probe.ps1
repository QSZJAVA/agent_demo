$ErrorActionPreference = 'Stop'
$reviewRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $reviewRoot
try {
    # Run tools/test-p2.ps1 -BackendOnly first to compile the existing isolated test harness.
    [xml]$reviewXml = Get-Content backend/target/surefire-reports/TEST-com.example.report.semantic.SemanticIntegrationTest.xml
    $reviewCp = ($reviewXml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
    $reviewEngine = $reviewCp.Split([IO.Path]::PathSeparator) | Where-Object { $_ -match 'junit-platform-engine-[^\\/]+\.jar$' } | Select-Object -First 1
    $reviewLauncher = $reviewEngine.Replace('junit-platform-engine', 'junit-platform-launcher')
    if (-not (Test-Path -LiteralPath $reviewLauncher)) { throw 'JUnit platform launcher from the Maven test runtime is required.' }
    $reviewCp += [IO.Path]::PathSeparator + $reviewLauncher
    New-Item -ItemType Directory -Path backend/target/current-review-probe -Force | Out-Null
    & javac -proc:none -encoding UTF-8 -cp $reviewCp -d backend/target/current-review-probe docs/review/CurrentSemanticReadinessProbe.java
    if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed.' }

    # Read known settings as data; never execute a private config or print credentials.
    if (Test-Path -LiteralPath tools/env.local.cmd) {
        Get-Content -LiteralPath tools/env.local.cmd | ForEach-Object {
            if ($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$') {
                if (-not [Environment]::GetEnvironmentVariable($matches[1])) {
                    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
                }
            }
        }
    }
    foreach ($reviewPair in @(@('TRACE_DB_HOST','DB_HOST'),@('TRACE_DB_PORT','DB_PORT'),@('TRACE_DB_USER','DB_USERNAME'),@('TRACE_DB_PASSWORD','DB_PASSWORD'),@('TRACE_REDIS_HOST','REDIS_HOST'),@('TRACE_REDIS_PORT','REDIS_PORT'),@('TRACE_REDIS_PASSWORD','REDIS_PASSWORD'))) {
        $reviewValue = [Environment]::GetEnvironmentVariable($reviewPair[1])
        if ($null -ne $reviewValue -and -not [Environment]::GetEnvironmentVariable($reviewPair[0])) {
            [Environment]::SetEnvironmentVariable($reviewPair[0], $reviewValue, 'Process')
        }
    }
    $env:P2_IT = 'true'
    $env:DEMO_IT = 'false'
    $env:P2_UI = 'false'
    $reviewRunCp = 'backend/target/current-review-probe' + [IO.Path]::PathSeparator + $reviewCp
    & java '-Dfile.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' -cp $reviewRunCp com.example.report.semantic.CurrentSemanticReadinessProbe
    if ($LASTEXITCODE -ne 0) { throw 'Defect reproductions did not complete; inspect the output.' }
} finally { Pop-Location }
