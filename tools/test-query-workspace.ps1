param([switch]$AllowIsolatedDatabases,[switch]$Live,[switch]$Browser,[switch]$ChangedCandidate,[int]$AgentPort=0,
    [ValidatePattern('^[a-zA-Z0-9_-]+$')][string]$EvidenceName='p1-query-workspace-2026-10-11',
    [ValidatePattern('^[a-zA-Z0-9_#,*]+$')][string]$TestSelector='P1WorkspaceIntegrationTest')
$ErrorActionPreference='Stop'
if(-not $AllowIsolatedDatabases){throw 'P1 tests create, seed and clean isolated databases. Obtain explicit user authorization, then pass -AllowIsolatedDatabases.'}
$p1Root=Split-Path $PSScriptRoot -Parent
$p1Dir=Join-Path $p1Root ('.runtime/'+$EvidenceName)
[IO.Directory]::CreateDirectory($p1Dir) | Out-Null
$p1Acl=Get-Acl -LiteralPath $p1Dir
$p1Identity=[System.Security.Principal.WindowsIdentity]::GetCurrent().Name
if(-not $p1Acl.AreAccessRulesProtected -or @($p1Acl.Access | Where-Object { $_.IdentityReference.Value -ne $p1Identity }).Count -gt 0) {
    # 只设置本轮目录的DACL，不能把Get-Acl取得的审核描述符一并写回而要求SeSecurityPrivilege。
    $p1Access=[System.Security.AccessControl.DirectorySecurity]::new()
    $p1Access.SetAccessRuleProtection($true,$false)
    $p1Access.AddAccessRule([System.Security.AccessControl.FileSystemAccessRule]::new($p1Identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
    Set-Acl -LiteralPath $p1Dir -AclObject $p1Access
}
$p1Settings=Join-Path $PSScriptRoot 'env.local.cmd'
if(Test-Path -LiteralPath $p1Settings){Get-Content -LiteralPath $p1Settings | ForEach-Object {
    if($_ -match '^\s*@?set\s+"?((?:DB|REDIS)_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}
}}
$p1Credentials=Get-Content -LiteralPath (Join-Path $p1Root '.runtime/llm-credentials.json') -Raw | ConvertFrom-Json
$env:LLM_API_KEY=$p1Credentials.apiKey
$env:LLM_BASE_URL=$p1Credentials.baseUrl
$env:LLM_MODEL=$p1Credentials.model
$env:P1_WORKSPACE='true'
$env:P1_WORKSPACE_DIR=$p1Dir
$env:P1_LIVE=([bool]$Live).ToString().ToLowerInvariant()
$env:P1_BROWSER=([bool]$Browser).ToString().ToLowerInvariant()
$env:P1_BROWSER_CHANGED=([bool]$ChangedCandidate).ToString().ToLowerInvariant()
$env:P1_AGENT_PORT=$AgentPort.ToString()
$env:TRACE_IT='false';$env:P2_IT='false';$env:MCP_IT='false';$env:DEMO_IT='false'
$env:ASSISTANT_BROWSER='false';$env:ASSISTANT_LIVE_REVIEW='false';$env:ASSISTANT_JOINT='false'
$env:SEMANTIC_LIVE='false';$env:INVESTIGATION_LIVE='false';$env:INVESTIGATION_CONTEXT_LIVE='false';$env:INVESTIGATION_JOINT='false'
$p1Log=Join-Path $p1Dir ('integration-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log')
& (Join-Path $p1Root 'backend/mvnw.cmd') -f (Join-Path $p1Root 'pom.xml') ('-Dtest='+$TestSelector) '-Dsurefire.failIfNoSpecifiedTests=false' verify -q *> $p1Log
$p1Exit=$LASTEXITCODE
Write-Output ('P1 test log: '+$p1Log)
Get-Content -LiteralPath $p1Log -Tail 45
exit $p1Exit
