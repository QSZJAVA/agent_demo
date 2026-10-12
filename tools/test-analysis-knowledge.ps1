param([switch]$AllowIsolatedDatabases,[switch]$Live,[switch]$Browser,[switch]$SearchBrowser,[int]$AgentPort=0,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$EvidenceName='p2-analysis-knowledge-2026-10-11',
    [ValidatePattern('^[a-zA-Z0-9_#,*]+$')][string]$TestSelector='AnalysisKnowledgeIntegrationTest')
$ErrorActionPreference='Stop'
if(-not $AllowIsolatedDatabases){throw 'Analysis tests create, seed and clean isolated databases. Obtain explicit authorization, then pass -AllowIsolatedDatabases.'}
$analysisRoot=Split-Path $PSScriptRoot -Parent
$analysisDir=Join-Path $analysisRoot ('.runtime/'+$EvidenceName)
[IO.Directory]::CreateDirectory($analysisDir) | Out-Null
$analysisIdentity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$analysisAccess=[System.Security.AccessControl.DirectorySecurity]::new()
$analysisAccess.SetAccessRuleProtection($true,$false)
$analysisAccess.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($analysisIdentity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
Set-Acl -LiteralPath $analysisDir -AclObject $analysisAccess
$analysisSettings=Join-Path $PSScriptRoot 'env.local.cmd'
if(Test-Path -LiteralPath $analysisSettings){Get-Content -LiteralPath $analysisSettings | ForEach-Object {
    if($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}
}}
$analysisCredentials=Get-Content -LiteralPath (Join-Path $analysisRoot '.runtime/llm-credentials.json') -Raw | ConvertFrom-Json
$env:LLM_API_KEY=$analysisCredentials.apiKey
$env:LLM_BASE_URL=$analysisCredentials.baseUrl
$env:LLM_MODEL=$analysisCredentials.model
$env:P2_ANALYSIS='true'
$env:P2_ANALYSIS_DIR=$analysisDir
$env:P2_ANALYSIS_LIVE=([bool]$Live).ToString().ToLowerInvariant()
$env:P2_ANALYSIS_BROWSER=([bool]$Browser).ToString().ToLowerInvariant()
$env:P2_SEARCH_BROWSER=([bool]$SearchBrowser).ToString().ToLowerInvariant()
$env:P2_AGENT_PORT=$AgentPort.ToString()
$env:TRACE_IT='false';$env:P2_IT='false';$env:MCP_IT='false';$env:DEMO_IT='false'
$env:P1_WORKSPACE='false';$env:P1_LIVE='false';$env:P1_BROWSER='false'
$env:ASSISTANT_BROWSER='false';$env:ASSISTANT_LIVE_REVIEW='false';$env:ASSISTANT_JOINT='false'
$env:SEMANTIC_LIVE='false';$env:INVESTIGATION_LIVE='false';$env:INVESTIGATION_CONTEXT_LIVE='false';$env:INVESTIGATION_JOINT='false'
$analysisLog=Join-Path $analysisDir ('integration-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log')
& (Join-Path $analysisRoot 'backend/mvnw.cmd') -f (Join-Path $analysisRoot 'pom.xml') ('-Dtest='+$TestSelector) '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8' verify -q *> $analysisLog
$analysisExit=$LASTEXITCODE
Write-Output ('Analysis test log: '+$analysisLog)
Get-Content -LiteralPath $analysisLog -Tail 45
exit $analysisExit
