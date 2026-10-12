param(
    [switch]$AllowIsolatedDatabases,
    [switch]$Live,
    [switch]$Browser,
    [ValidateRange(0,65535)][int]$AgentPort=0,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$EvidenceName=('unified-analysis-'+(Get-Date -Format 'yyyyMMdd-HHmmss')),
    [ValidatePattern('^UnifiedAnalysisConversationIntegrationTest(?:#[a-zA-Z0-9_*+]+)?$')][string]$TestSelector='UnifiedAnalysisConversationIntegrationTest'
)
$ErrorActionPreference='Stop'

# 开关表达本轮已授权的专用隔离库创建、写入和最终清理，不扩大到既有库或report_demo。
if(-not $AllowIsolatedDatabases){throw '本轮隔离库运行须显式传入 -AllowIsolatedDatabases；保留执行前已有数据库。'}
$unifiedRoot=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$unifiedRuntime=[IO.Path]::GetFullPath((Join-Path $unifiedRoot '.runtime'))
$unifiedDirectory=[IO.Path]::GetFullPath((Join-Path $unifiedRuntime $EvidenceName))
if(-not $unifiedDirectory.StartsWith($unifiedRuntime+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '取证目录必须位于本仓库.runtime内。'}
if(Test-Path -LiteralPath $unifiedDirectory){throw '取证目录已存在；使用新的 EvidenceName 保留之前的失败和通过证据。'}
[IO.Directory]::CreateDirectory($unifiedDirectory) | Out-Null
$unifiedIdentity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$unifiedAcl=[System.Security.AccessControl.DirectorySecurity]::new()
$unifiedAcl.SetAccessRuleProtection($true,$false)
$unifiedAcl.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($unifiedIdentity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
Set-Acl -LiteralPath $unifiedDirectory -AclObject $unifiedAcl

# 浏览器模式只建立交互夹具；真实模型专项可在另一独立目录执行，避免复用或覆盖证据。
if($Browser -and -not $PSBoundParameters.ContainsKey('TestSelector')){$TestSelector='UnifiedAnalysisConversationIntegrationTest#realBrowserUnifiedChatFixture'}
$unifiedPrior=@{}
function Set-UnifiedEnvironment([string]$Name,[string]$Value){
    if(-not $unifiedPrior.ContainsKey($Name)){$unifiedPrior[$Name]=[Environment]::GetEnvironmentVariable($Name,'Process')}
    [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
# 同一次字节读取同时给出长度与SHA，清单只保存指纹，不复制源码正文或本机配置。
function Get-UnifiedFingerprint([string]$Path){
    $unifiedContent=[IO.File]::ReadAllBytes($Path)
    $unifiedHasher=[Security.Cryptography.SHA256]::Create()
    try{return [pscustomobject]@{bytes=$unifiedContent.LongLength;sha256=([BitConverter]::ToString($unifiedHasher.ComputeHash($unifiedContent))).Replace('-','').ToLowerInvariant()}}
    finally{$unifiedHasher.Dispose()}
}
function Get-UnifiedRelativePath([string]$Path){
    $unifiedFullPath=[IO.Path]::GetFullPath($Path)
    if(-not $unifiedFullPath.StartsWith($unifiedRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw '取证文件必须属于当前仓库。'}
    return $unifiedFullPath.Substring($unifiedRoot.Length+1).Replace('\','/')
}
function Save-UnifiedSourceManifest {
    $unifiedSources=@(foreach($unifiedModule in @('backend','business-service')){Get-ChildItem -LiteralPath (Join-Path $unifiedRoot ($unifiedModule+'/src')) -File -Recurse -Force})
    $unifiedSources+=Get-Item -LiteralPath $PSCommandPath
    $unifiedSourceEntries=@(foreach($unifiedSource in ($unifiedSources | Sort-Object FullName)){
        $unifiedFingerprint=Get-UnifiedFingerprint $unifiedSource.FullName
        [ordered]@{path=(Get-UnifiedRelativePath $unifiedSource.FullName);bytes=$unifiedFingerprint.bytes;sha256=$unifiedFingerprint.sha256}
    })
    [ordered]@{startedAt=$unifiedStarted;capturedAt=[DateTime]::UtcNow.ToString('o');testSelector=$TestSelector;files=$unifiedSourceEntries} |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $unifiedDirectory 'source-manifest.json') -Encoding utf8
}
# Surefire的test系统属性必须与本轮选择器逐字符一致；不根据文件名、数量或成功字样猜测运行归属。
function Read-UnifiedReportIdentity([string]$Path){
    $unifiedXmlSettings=[Xml.XmlReaderSettings]::new();$unifiedXmlSettings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$unifiedXmlSettings.XmlResolver=$null
    $unifiedReader=[Xml.XmlReader]::Create($Path,$unifiedXmlSettings)
    try{
        $unifiedXml=[Xml.XmlDocument]::new();$unifiedXml.XmlResolver=$null;$unifiedXml.Load($unifiedReader)
        $unifiedSuite=$unifiedXml.DocumentElement
        $unifiedSelectors=@($unifiedSuite.SelectNodes("properties/property[@name='test']"))
        if($unifiedSuite.LocalName -cne 'testsuite' -or $unifiedSelectors.Count -ne 1){throw 'REPORT_IDENTITY_MISSING'}
        return [pscustomobject]@{suite=$unifiedSuite.GetAttribute('name');selector=$unifiedSelectors[0].GetAttribute('value')}
    }finally{$unifiedReader.Dispose()}
}
# 复制前后同时核验来源与目标；禁止覆盖已有证据，异常文件保留但明确标为COPY_ERROR，不能计为已归档报告。
function Copy-UnifiedReport([IO.FileInfo]$File,[string]$Module,[string]$PairedXml,[DateTime]$StartedUtc,[DateTime]$FinishedUtc){
    $unifiedEntry=[ordered]@{module=$Module;source=(Get-UnifiedRelativePath $File.FullName);lastWriteTimeUtc=$File.LastWriteTimeUtc.ToString('o');pairedXml=$PairedXml;status='EXCLUDED_OUTSIDE_RUN_WINDOW'}
    if($File.LastWriteTimeUtc -lt $StartedUtc -or $File.LastWriteTimeUtc -gt $FinishedUtc){return [pscustomobject]$unifiedEntry}
    try{
        $unifiedBefore=Get-UnifiedFingerprint $File.FullName
        $unifiedDestinationDirectory=Join-Path $unifiedDirectory ('surefire/'+$Module)
        [IO.Directory]::CreateDirectory($unifiedDestinationDirectory) | Out-Null
        $unifiedDestination=Join-Path $unifiedDestinationDirectory $File.Name
        $unifiedEntry['destination']='surefire/'+$Module+'/'+$File.Name;$unifiedEntry['sourceSha256']=$unifiedBefore.sha256
        [IO.File]::Copy($File.FullName,$unifiedDestination,$false)
        $unifiedAfter=Get-UnifiedFingerprint $File.FullName;$unifiedCopied=Get-UnifiedFingerprint $unifiedDestination
        $unifiedEntry['bytes']=$unifiedCopied.bytes;$unifiedEntry['destinationSha256']=$unifiedCopied.sha256;$unifiedEntry['sourceSha256AfterCopy']=$unifiedAfter.sha256
        $unifiedCurrent=Get-Item -LiteralPath $File.FullName
        if($unifiedBefore.sha256 -cne $unifiedAfter.sha256 -or $unifiedBefore.sha256 -cne $unifiedCopied.sha256 -or $unifiedBefore.bytes -ne $unifiedCopied.bytes -or
                $unifiedCurrent.LastWriteTimeUtc -lt $StartedUtc -or $unifiedCurrent.LastWriteTimeUtc -gt $FinishedUtc){throw 'REPORT_CHANGED_DURING_COPY'}
        if($File.Extension -ieq '.xml'){
            $unifiedCopiedIdentity=Read-UnifiedReportIdentity $unifiedDestination
            if($unifiedCopiedIdentity.selector -cne $TestSelector -or ('TEST-'+$unifiedCopiedIdentity.suite+'.xml') -cne $File.Name){throw 'COPIED_REPORT_IDENTITY_MISMATCH'}
        }
        $unifiedEntry['status']='COPIED'
    }catch{$unifiedEntry['status']='COPY_ERROR';$unifiedEntry['errorType']=$_.Exception.GetType().FullName}
    return [pscustomobject]$unifiedEntry
}
function Save-UnifiedReports([DateTime]$StartedUtc,[DateTime]$FinishedUtc){
    $unifiedEntries=[Collections.Generic.List[object]]::new();$unifiedModules=[Collections.Generic.List[object]]::new()
    $unifiedClass=($TestSelector -split '#',2)[0]
    foreach($unifiedModule in @('backend','business-service')){
        $unifiedReports=Join-Path $unifiedRoot ($unifiedModule+'/target/surefire-reports')
        $unifiedModuleStatus='MAVEN_NOT_STARTED';$unifiedCopiedXml=0
        if($unifiedMavenStarted -and (Test-Path -LiteralPath $unifiedReports -PathType Container)){
            $unifiedModuleStatus='NO_CURRENT_MATCHING_REPORT';$unifiedPairedTexts=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
            foreach($unifiedReport in @(Get-ChildItem -LiteralPath $unifiedReports -File -Filter ('TEST-*'+$unifiedClass+'.xml'))){
                $unifiedReason='EXCLUDED_OUTSIDE_RUN_WINDOW'
                if($unifiedReport.LastWriteTimeUtc -ge $StartedUtc -and $unifiedReport.LastWriteTimeUtc -le $FinishedUtc){
                    try{
                        $unifiedIdentity=Read-UnifiedReportIdentity $unifiedReport.FullName
                        $unifiedSuitePattern='^(?:[a-zA-Z_$][a-zA-Z0-9_$]*\.)*'+[regex]::Escape($unifiedClass)+'$'
                        $unifiedReason='EXCLUDED_SELECTOR_OR_SUITE_MISMATCH'
                        if($unifiedIdentity.selector -ceq $TestSelector -and $unifiedIdentity.suite -cmatch $unifiedSuitePattern -and ('TEST-'+$unifiedIdentity.suite+'.xml') -ceq $unifiedReport.Name){
                            $unifiedCopy=Copy-UnifiedReport $unifiedReport $unifiedModule $null $StartedUtc $FinishedUtc;$unifiedEntries.Add($unifiedCopy)
                            if($unifiedCopy.status -ceq 'COPIED'){
                                $unifiedCopiedXml++
                                $unifiedText=Join-Path $unifiedReports ($unifiedIdentity.suite+'.txt');[void]$unifiedPairedTexts.Add($unifiedText)
                                if(Test-Path -LiteralPath $unifiedText -PathType Leaf){$unifiedEntries.Add((Copy-UnifiedReport (Get-Item -LiteralPath $unifiedText) $unifiedModule $unifiedCopy.source $StartedUtc $FinishedUtc))}
                                else{$unifiedEntries.Add([pscustomobject]@{module=$unifiedModule;source=(Get-UnifiedRelativePath $unifiedText);pairedXml=$unifiedCopy.source;status='TXT_MISSING'})}
                            }
                            continue
                        }
                    }catch{$unifiedReason='EXCLUDED_UNREADABLE_REPORT_IDENTITY'}
                }
                $unifiedEntries.Add([pscustomobject]@{module=$unifiedModule;source=(Get-UnifiedRelativePath $unifiedReport.FullName);lastWriteTimeUtc=$unifiedReport.LastWriteTimeUtc.ToString('o');status=$unifiedReason})
            }
            foreach($unifiedText in @(Get-ChildItem -LiteralPath $unifiedReports -File -Filter ('*'+$unifiedClass+'.txt'))){
                if(-not $unifiedPairedTexts.Contains($unifiedText.FullName)){$unifiedEntries.Add([pscustomobject]@{module=$unifiedModule;source=(Get-UnifiedRelativePath $unifiedText.FullName);lastWriteTimeUtc=$unifiedText.LastWriteTimeUtc.ToString('o');status='EXCLUDED_NO_MATCHING_XML'})}
            }
            if($unifiedCopiedXml -gt 0){$unifiedModuleStatus='COPIED'}
        }elseif($unifiedMavenStarted){$unifiedModuleStatus='REPORT_DIRECTORY_MISSING'}
        $unifiedModules.Add([pscustomobject]@{module=$unifiedModule;status=$unifiedModuleStatus;copiedXml=$unifiedCopiedXml})
    }
    # 只根据本轮日志中的Maven编译阶段错误标记分类；其他非零退出不冒充编译失败，更不能用旧XML填补缺失证据。
    $unifiedCompilationMarkers=@()
    if(Test-Path -LiteralPath $unifiedLog){$unifiedCompilationMarkers=@(Select-String -LiteralPath $unifiedLog -Pattern '\[ERROR\]\s+COMPILATION ERROR','Failed to execute goal .*maven-compiler-plugin' | ForEach-Object {$_.Line.Trim()})}
    $unifiedManifest=[ordered]@{startedAt=$StartedUtc.ToString('o');finishedAt=$FinishedUtc.ToString('o');testSelector=$TestSelector;mavenStarted=$unifiedMavenStarted;exitCode=$unifiedExit;launcherStage=$unifiedStage;launcherFailureType=$unifiedFailureType;compilationFailureDetected=($unifiedCompilationMarkers.Count -gt 0);compilationMarkers=$unifiedCompilationMarkers;modules=@($unifiedModules.ToArray());files=@($unifiedEntries.ToArray())}
    $unifiedManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $unifiedDirectory 'report-copy-manifest.json') -Encoding utf8
}
$unifiedLog=Join-Path $unifiedDirectory 'integration.log'
$unifiedExit=1
$unifiedStartedUtc=[DateTime]::UtcNow
$unifiedStarted=$unifiedStartedUtc.ToString('o')
$unifiedMavenStarted=$false;$unifiedStage='SOURCE_MANIFEST';$unifiedFailureType=$null
try {
    Save-UnifiedSourceManifest
    $unifiedStage='ENVIRONMENT_SETUP'
    # 只解析已有本机配置中的DB/Redis赋值，不执行cmd脚本，不打印认证值。
    $unifiedSettings=Join-Path $PSScriptRoot 'env.local.cmd'
    if(Test-Path -LiteralPath $unifiedSettings){Get-Content -LiteralPath $unifiedSettings | ForEach-Object {
        if($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])){
            Set-UnifiedEnvironment $matches[1] $matches[2]
        }
    }}
    $unifiedCredentialPath=Join-Path $unifiedRoot '.runtime/llm-credentials.json'
    $unifiedCredentials=Get-Content -LiteralPath $unifiedCredentialPath -Raw | ConvertFrom-Json
    if([string]::IsNullOrWhiteSpace($unifiedCredentials.apiKey) -or [string]::IsNullOrWhiteSpace($unifiedCredentials.baseUrl) -or [string]::IsNullOrWhiteSpace($unifiedCredentials.model)){throw '本机真实模型配置不完整。'}
    Set-UnifiedEnvironment 'LLM_API_KEY' $unifiedCredentials.apiKey
    Set-UnifiedEnvironment 'LLM_BASE_URL' $unifiedCredentials.baseUrl
    Set-UnifiedEnvironment 'LLM_MODEL' $unifiedCredentials.model
    foreach($unifiedFlag in @('TRACE_IT','P2_IT','P2_UI','MCP_IT','DEMO_IT','P1_WORKSPACE','P1_LIVE','P1_BROWSER','P2_ANALYSIS','P2_ANALYSIS_LIVE','P2_ANALYSIS_BROWSER','P2_SEARCH_BROWSER','P2_RAG_BROWSER','ASSISTANT_BROWSER','ASSISTANT_LIVE_REVIEW','ASSISTANT_JOINT','SEMANTIC_LIVE_EVAL','INVESTIGATION_ARCHIVE','INVESTIGATION_LIVE','INVESTIGATION_CONTEXT_LIVE','INVESTIGATION_JOINT','INVESTIGATION_UI')){
        Set-UnifiedEnvironment $unifiedFlag 'false'
    }
    Set-UnifiedEnvironment 'UNIFIED_ANALYSIS' 'true'
    Set-UnifiedEnvironment 'UNIFIED_ALLOW_ISOLATED_DATABASES' 'true'
    Set-UnifiedEnvironment 'UNIFIED_ANALYSIS_DIR' $unifiedDirectory
    Set-UnifiedEnvironment 'UNIFIED_ANALYSIS_LIVE' ([bool]$Live).ToString().ToLowerInvariant()
    Set-UnifiedEnvironment 'UNIFIED_ANALYSIS_BROWSER' ([bool]$Browser).ToString().ToLowerInvariant()
    Set-UnifiedEnvironment 'UNIFIED_AGENT_PORT' $AgentPort.ToString()
    @{startedAt=$unifiedStarted;testSelector=$TestSelector;live=[bool]$Live;browser=[bool]$Browser;agentPort=$AgentPort;schemaPrefix='unified_analysis_it_';preservePreexistingDatabases=$true} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $unifiedDirectory 'launcher.json') -Encoding utf8
    $unifiedMavenStarted=$true;$unifiedStage='MAVEN'
    & (Join-Path $unifiedRoot 'backend/mvnw.cmd') -f (Join-Path $unifiedRoot 'pom.xml') ('-Dtest='+$TestSelector) '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8' verify -q *> $unifiedLog
    $unifiedExit=$LASTEXITCODE
    $unifiedStage='MAVEN_RETURNED'
} catch {
    $unifiedFailureType=$_.Exception.GetType().FullName
    throw
} finally {
    $unifiedFinishedUtc=[DateTime]::UtcNow
    foreach($unifiedName in $unifiedPrior.Keys){[Environment]::SetEnvironmentVariable($unifiedName,$unifiedPrior[$unifiedName],'Process')}
    $unifiedArchiveError=$null
    try{Save-UnifiedReports $unifiedStartedUtc $unifiedFinishedUtc}catch{$unifiedArchiveError=$_.Exception.GetType().FullName;Write-Warning '本轮报告取证未完整写入，launcher-result.json记录实际归档错误；不改变原启动器退出码。'}
    @{startedAt=$unifiedStarted;finishedAt=$unifiedFinishedUtc.ToString('o');exitCode=$unifiedExit;testSelector=$TestSelector;live=[bool]$Live;browser=[bool]$Browser;sourceManifest='source-manifest.json';reportManifest='report-copy-manifest.json';reportArchiveError=$unifiedArchiveError;launcherStage=$unifiedStage;launcherFailureType=$unifiedFailureType} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $unifiedDirectory 'launcher-result.json') -Encoding utf8
    Write-Output ('Unified analysis evidence: '+$unifiedDirectory)
    Write-Output ('Unified analysis log: '+$unifiedLog)
}
exit $unifiedExit
