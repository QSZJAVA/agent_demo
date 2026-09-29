$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskTargets=@{
    agent=(Join-Path $taskRoot 'backend/target/report-demo-2.0.0.jar')
    business=(Join-Path $taskRoot 'business-service/target/business-service-2.0.0.jar')
    frontend=(Join-Path $taskRoot 'frontend/node_modules/vite/bin/vite.js')
}
foreach($taskName in $taskTargets.Keys) {
    $taskPidPath=Join-Path $taskRoot ".runtime/$taskName.pid"
    if(-not (Test-Path -LiteralPath $taskPidPath)){continue}
    $taskProcessId=[int](Get-Content -LiteralPath $taskPidPath)
    $taskProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$taskProcessId"
    if(-not $taskProcess){continue}
    $taskExpected=[IO.Path]::GetFullPath($taskTargets[$taskName]).Replace('/','\')
    if($taskProcess.Name -in @('java.exe','node.exe') -and $taskProcess.CommandLine.Replace('/','\').Contains($taskExpected)) {
        Stop-Process -Id $taskProcessId
        Write-Output "Stopped $taskName (PID=$taskProcessId)."
    } else {throw "PID $taskProcessId no longer belongs to this workspace; left it untouched."}
}
