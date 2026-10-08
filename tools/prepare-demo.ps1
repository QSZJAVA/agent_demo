#Requires -Version 7.2
param([int]$AgentPort=8080)
# 在最终专用库补齐九条预览所需演示数据，并通过认证接口准备账号；不清库、不确认派单。
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskBaseline=Get-Content (Join-Path $taskRoot 'demo-baseline.json') -Raw|ConvertFrom-Json
$taskSaved=@{}
try {
    $taskSettings=Join-Path $PSScriptRoot 'env.local.cmd'
    if(Test-Path -LiteralPath $taskSettings){foreach($taskLine in Get-Content -LiteralPath $taskSettings){
        if($taskLine -match '^\s*@?set\s+"?(DB_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])) {
            $taskSaved[$matches[1]]=[Environment]::GetEnvironmentVariable($matches[1]);[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')
        }
    }}
    if((Invoke-RestMethod "http://127.0.0.1:$AgentPort/api/health/readiness").status -ne 'UP'){throw 'Start the final Demo services first.'}
    # 从已构建运行包提取同版本 JDBC 驱动；初始化只依赖 java.sql 和该驱动，不额外下载 Maven 插件。
    $taskJar=Join-Path $taskRoot 'backend/target/report-demo-1.0.0.jar'
    if(-not (Test-Path -LiteralPath $taskJar)){throw 'Build the current Demo before preparing data.'}
    $taskArchive=[IO.Compression.ZipFile]::OpenRead($taskJar)
    try {
        $taskDrivers=@($taskArchive.Entries | Where-Object {$_.FullName -match '^BOOT-INF/lib/mysql-connector-j-[^/]+\.jar$'})
        if($taskDrivers.Count -ne 1){throw 'Expected exactly one packaged MySQL JDBC driver.'}
        $taskDriverDirectory=Join-Path $taskRoot 'backend/target/demo-jdbc'
        New-Item -ItemType Directory -Force -Path $taskDriverDirectory | Out-Null
        $taskClasspath=Join-Path $taskDriverDirectory $taskDrivers[0].Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($taskDrivers[0],$taskClasspath,$true)
    } finally {$taskArchive.Dispose()}
    & java.exe '-Dfile.encoding=UTF-8' -cp $taskClasspath (Join-Path $PSScriptRoot 'PrepareFinalDemo.java') $taskBaseline.database
    if($LASTEXITCODE -ne 0){throw 'Dedicated Demo data check failed; accounts were not changed.'}
    & (Join-Path $PSScriptRoot 'prepare-demo-accounts.ps1') -BaseUrl "http://127.0.0.1:$AgentPort"
    Write-Output "Final Demo data/account checks completed; existing dispatch state preserved. Primary user: $($taskBaseline.demoUser). Read credentials from .runtime/demo-accounts.md."
} finally {foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}}
