param([switch]$Apply)

# 本轮临时文件清理：仅处理 plan.json 已列明的普通文件；先核实原件、归档和运行状态。
# 无 -Apply 时仅查看范围；人工执行 -Apply 后删除，保存逐文件结果，允许从部分完成状态继续。
# 不输出凭据内容；长期配置仅计算哈希以核对保留状态，不操作数据库、源码或前端预览产物。
$ErrorActionPreference='Stop'
$cleanupWorkspace=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
if($cleanupWorkspace -ne 'D:\demo-v1\demo'){throw 'Workspace does not match the reviewed cleanup plan'}
$cleanupPlan=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'plan.json') -Raw|ConvertFrom-Json
$cleanupRoots=@(
    (Resolve-Path -LiteralPath (Join-Path $cleanupWorkspace '.runtime/overall-review-2026-10-11')).Path,
    (Resolve-Path -LiteralPath (Join-Path $cleanupWorkspace '.runtime/p0-semantic-quality-2026-10-11')).Path
)
if($cleanupPlan.workspace -ne $cleanupWorkspace -or $cleanupPlan.targets.Count -ne 473 -or @($cleanupPlan.targets.path|Sort-Object -Unique).Count -ne 473 -or $cleanupPlan.retainedEvidence.Count -ne 161){throw 'Cleanup plan differs from the reviewed inventory'}
if(@($cleanupPlan.retainedEvidence|Where-Object{$_.path -in $cleanupPlan.targets.path}).Count){throw 'A retained evidence file appears in the deletion list'}

# 先完成全部检查再删除第一项；文件修改、符号链接或归档变化均要求重新核对清单。
$cleanupPending=@()
$cleanupAlreadyAbsent=@()
foreach($cleanupTarget in $cleanupPlan.targets){
    $cleanupFull=[IO.Path]::GetFullPath([string]$cleanupTarget.path)
    if($cleanupFull -ne $cleanupTarget.path -or -not @($cleanupRoots|Where-Object{$cleanupFull.StartsWith($_+'\',[StringComparison]::OrdinalIgnoreCase)}).Count){throw 'Target escapes the two reviewed task directories'}
    if(-not (Test-Path -LiteralPath $cleanupFull)){$cleanupAlreadyAbsent+=$cleanupFull;continue}
    $cleanupFile=Get-Item -LiteralPath $cleanupFull -Force
    if($cleanupFile.PSIsContainer -or ($cleanupFile.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Expected a regular temporary file'}
    $cleanupParent=$cleanupFile.Directory
    while($cleanupParent -and $cleanupParent.FullName -ne $cleanupWorkspace){
        if($cleanupParent.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse point in target path'}
        $cleanupParent=$cleanupParent.Parent
    }
    if($cleanupFile.Length -ne [long]$cleanupTarget.bytes -or $cleanupFile.LastWriteTimeUtc.Ticks -ne ([DateTime]$cleanupTarget.lastWriteTimeUtc).ToUniversalTime().Ticks){throw ('Temporary file changed: '+$cleanupFull)}
    if($cleanupTarget.sha256 -and (Get-FileHash -LiteralPath $cleanupFull -Algorithm SHA256).Hash -ne $cleanupTarget.sha256){throw ('Temporary file hash changed: '+$cleanupFull)}
    if($cleanupTarget.reason -eq 'verified_archive_duplicate' -and (Get-FileHash -LiteralPath $cleanupTarget.archive.path -Algorithm SHA256).Hash -ne $cleanupTarget.archive.sha256){throw 'Archive changed; keep its original evidence'}
    $cleanupPending+=$cleanupTarget
}
Write-Output ('Pending temporary files: '+$cleanupPending.Count+'; already absent: '+$cleanupAlreadyAbsent.Count)
Write-Output ('Bytes to remove: '+($cleanupPending|Measure-Object -Property bytes -Sum).Sum)

# 保留证据和长期配置也必须在删除前完整存在，避免执行后才发现核对基线已缺失。
if(@($cleanupPlan.retainedEvidence|Where-Object{-not (Test-Path -LiteralPath $_.path -PathType Leaf)}).Count){throw 'Original evidence is missing before cleanup; review the inventory first'}
$cleanupProtected=@('.runtime/llm-credentials.json','tools/env.local.cmd')|ForEach-Object{
    @{path=(Join-Path $cleanupWorkspace $_);sha256=(Get-FileHash -LiteralPath (Join-Path $cleanupWorkspace $_) -Algorithm SHA256).Hash}
}
if(-not $Apply){Write-Output 'Preview only. Run this same script with -Apply to delete the reviewed files.';return}

$cleanupActive=@(Get-CimInstance Win32_Process|Where-Object{$_.Name -in @('java.exe','node.exe','python.exe') -and $_.CommandLine -match '(overall-review-2026-10-11|p0-semantic-quality-2026-10-11)'})
if($cleanupActive.Count){throw 'A task-specific helper is still running; stop it before cleanup'}
$cleanupDeleted=@()
$cleanupFailure=$null
try{
    foreach($cleanupTarget in $cleanupPending){
        Remove-Item -LiteralPath ([string]$cleanupTarget.path) -Force -ErrorAction Stop
        $cleanupDeleted+=@{path=$cleanupTarget.path;reason=$cleanupTarget.reason;bytes=$cleanupTarget.bytes}
    }
}catch{$cleanupFailure=$_.Exception.GetType().FullName}

# 结果只记录路径、数量与核对结论，不包含密码、密钥或会话凭据。
$cleanupRemaining=@($cleanupPlan.targets|Where-Object{Test-Path -LiteralPath $_.path}|Select-Object -ExpandProperty path)
$cleanupLostEvidence=@($cleanupPlan.retainedEvidence|Where-Object{-not (Test-Path -LiteralPath $_.path -PathType Leaf)}|Select-Object -ExpandProperty path)
$cleanupProtectedUnchanged=$true
foreach($cleanupConfig in $cleanupProtected){
    if(-not (Test-Path -LiteralPath $cleanupConfig.path) -or (Get-FileHash -LiteralPath $cleanupConfig.path -Algorithm SHA256).Hash -ne $cleanupConfig.sha256){$cleanupProtectedUnchanged=$false}
}
$cleanupPassed=($null -eq $cleanupFailure -and $cleanupRemaining.Count -eq 0 -and $cleanupLostEvidence.Count -eq 0 -and $cleanupProtectedUnchanged)
$cleanupResult=@{
    completedAt=[DateTimeOffset]::Now.ToString('o');execution='USER_RUN_POWERSHELL';passed=$cleanupPassed
    deletedCount=$cleanupDeleted.Count;deletedBytes=($cleanupDeleted|Measure-Object -Property bytes -Sum).Sum
    deleted=$cleanupDeleted;alreadyAbsent=$cleanupAlreadyAbsent;remainingTargets=$cleanupRemaining
    preservedOriginalEvidence=$cleanupPlan.retainedEvidence.Count;missingOriginalEvidence=$cleanupLostEvidence
    protectedConfigurationUnchanged=$cleanupProtectedUnchanged;failureType=$cleanupFailure
}
$cleanupResultPath=Join-Path $PSScriptRoot ('result-'+[DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmssfff')+'.json')
$cleanupResult|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $cleanupResultPath -Encoding utf8NoBOM
Write-Output ('Deleted: '+$cleanupDeleted.Count+'; remaining: '+$cleanupRemaining.Count+'; checks passed: '+$cleanupPassed)
Write-Output ('Result: '+$cleanupResultPath)
if(-not $cleanupPassed){throw 'Cleanup did not finish completely; inspect the saved result before continuing'}
