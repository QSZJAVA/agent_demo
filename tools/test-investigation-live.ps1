param([ValidateSet('development','holdout','all')][string]$Split='holdout', [ValidateRange(1,10)][int]$Repeats=3, [string]$CaseFilter='', [string]$ResumeFrom='', [ValidateRange(1,2)][int]$Parallelism=2, [switch]$NativeSchema, [switch]$Thinking, [switch]$Joint, [switch]$Browser)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskNames=@('LLM_BASE_URL','LLM_API_KEY','LLM_MODEL','INVESTIGATION_MODEL','INVESTIGATION_LIVE','INVESTIGATION_SPLIT','INVESTIGATION_REPEATS','INVESTIGATION_CASE_FILTER','INVESTIGATION_RESUME','INVESTIGATION_NATIVE_SCHEMA','INVESTIGATION_THINKING_ENABLED','INVESTIGATION_PARALLELISM','INVESTIGATION_JOINT','INVESTIGATION_UI','INVESTIGATION_UI_PASSWORD','MCP_IT','DEMO_IT','TRACE_IT','P2_IT','DB_HOST','DB_PORT','DB_USERNAME','DB_PASSWORD','REDIS_HOST','REDIS_PORT','REDIS_PASSWORD')
$taskOriginal=@{}
foreach($taskName in $taskNames) { $taskOriginal[$taskName]=[Environment]::GetEnvironmentVariable($taskName) }
$taskExit=0
try {
    $taskSettings=Join-Path $PSScriptRoot 'env.local.cmd'
    if(Test-Path -LiteralPath $taskSettings) {
        Get-Content -LiteralPath $taskSettings | ForEach-Object {
            if($_ -match '^\s*@?set\s+"?((?:LLM|DB|REDIS|INVESTIGATION)_[A-Z_]+)=(.*?)"?\s*$' -and $taskNames.Contains($matches[1]) -and -not [Environment]::GetEnvironmentVariable($matches[1])) { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') }
        }
    }
    $taskCredentials=Join-Path $taskRoot '.runtime/llm-credentials.json'
    if(Test-Path -LiteralPath $taskCredentials) {
        $taskConfig=Get-Content -LiteralPath $taskCredentials -Raw | ConvertFrom-Json
        foreach($taskPair in @(@('LLM_API_KEY','apiKey'),@('LLM_BASE_URL','baseUrl'),@('LLM_MODEL','model'))) {
            if(-not [Environment]::GetEnvironmentVariable($taskPair[0]) -and $taskConfig.($taskPair[1])) { [Environment]::SetEnvironmentVariable($taskPair[0],[string]$taskConfig.($taskPair[1]),'Process') }
        }
    }
    if(-not $env:LLM_API_KEY) { throw 'LLM_API_KEY is required for real investigation evaluation.' }
    if(-not $env:LLM_BASE_URL) { $env:LLM_BASE_URL='https://dashscope.aliyuncs.com/compatible-mode' }
    $env:LLM_BASE_URL=$env:LLM_BASE_URL.TrimEnd('/') -replace '/v1$',''
    $env:INVESTIGATION_LIVE='true';$env:INVESTIGATION_SPLIT=$Split;$env:INVESTIGATION_REPEATS=[string]$Repeats;$env:INVESTIGATION_CASE_FILTER=$CaseFilter
    $env:INVESTIGATION_PARALLELISM=[string]$Parallelism
    $env:INVESTIGATION_RESUME=if($ResumeFrom){(Resolve-Path -LiteralPath $ResumeFrom).Path}else{''}
    $env:INVESTIGATION_NATIVE_SCHEMA=if($NativeSchema){'true'}else{'false'};$env:INVESTIGATION_THINKING_ENABLED=if($Thinking){'true'}else{'false'}
    if($Joint -or $Browser) {
        if($Joint -and $Browser){throw 'Choose either -Joint or -Browser.'}
        $env:INVESTIGATION_LIVE='false';$env:INVESTIGATION_JOINT=if($Joint){'true'}else{'false'};$env:INVESTIGATION_UI=if($Browser){'true'}else{'false'};$env:MCP_IT='true';$env:DEMO_IT='false';$env:TRACE_IT='false';$env:P2_IT='false'
        if($Browser -and -not $env:INVESTIGATION_UI_PASSWORD){$env:INVESTIGATION_UI_PASSWORD=[guid]::NewGuid().ToString();Write-Output 'Set INVESTIGATION_UI_PASSWORD before -Browser to use your own temporary isolated-test password.'}
        $taskTest=if($Browser){'BusinessMcpIntegrationTest#investigationBrowserSession'}else{'BusinessMcpIntegrationTest#investigationRealModelAndHttpMcpJointAcceptance*'}
        if($Joint -and $CaseFilter){if($CaseFilter -notmatch '^[A-Za-z]+$'){throw 'Joint CaseFilter must be a method suffix.'};$taskTest="BusinessMcpIntegrationTest#investigationRealModelAndHttpMcpJointAcceptance${CaseFilter}*"}
        & (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'pom.xml') verify -q "-Dtest=$taskTest" '-Dsurefire.failIfNoSpecifiedTests=false'
    } else {
        & (Join-Path $taskRoot 'backend/mvnw.cmd') -f (Join-Path $taskRoot 'backend/pom.xml') test -q '-Dtest=InvestigationLiveEvaluationTest'
    }
    $taskExit=$LASTEXITCODE
} finally { foreach($taskName in $taskNames) { [Environment]::SetEnvironmentVariable($taskName,$taskOriginal[$taskName],'Process') } }
exit $taskExit
