param([switch]$NativeSchema=$true, [switch]$Thinking, [string]$Model = '', [string]$Corpus = '')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$settings = Join-Path $PSScriptRoot 'env.local.cmd'
$taskOriginal = @{}
foreach ($name in @('LLM_BASE_URL','LLM_API_KEY','LLM_MODEL','LLM_MODE','SEMANTIC_LIVE_EVAL','SEMANTIC_NATIVE_SCHEMA','SEMANTIC_THINKING_ENABLED','SEMANTIC_EVAL_PARSER','SEMANTIC_EVAL_CORPUS')) {
    $taskOriginal[$name] = [Environment]::GetEnvironmentVariable($name)
}
$taskExit = 0
try {
if (Test-Path -LiteralPath $settings) {
    Get-Content -LiteralPath $settings | ForEach-Object {
        if ($_ -match '^\s*@?set\s+"?(LLM_[A-Z_]+)=(.*?)"?\s*$' -and -not [Environment]::GetEnvironmentVariable($matches[1])) {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
        }
    }
}
$taskModelPath=Join-Path $taskRoot '.runtime/llm-credentials.json'
if(-not $env:LLM_API_KEY -and (Test-Path -LiteralPath $taskModelPath)) {
    $taskModelConfig=Get-Content -LiteralPath $taskModelPath -Raw|ConvertFrom-Json
    $env:LLM_API_KEY=$taskModelConfig.apiKey
    if(-not $env:LLM_BASE_URL){$env:LLM_BASE_URL=$taskModelConfig.baseUrl.TrimEnd('/') -replace '/v1$',''}
    if(-not $env:LLM_MODEL){$env:LLM_MODEL=$taskModelConfig.model}
}
if (-not $env:LLM_API_KEY) { throw 'LLM_API_KEY is required for live semantic evaluation.' }
$env:SEMANTIC_LIVE_EVAL='true'
$env:SEMANTIC_NATIVE_SCHEMA=if($NativeSchema){'true'}else{'false'}
$env:SEMANTIC_THINKING_ENABLED=if($Thinking){'true'}else{'false'}
# 当前协议只评估真实模型输出，未启用其他解析来源。
$env:SEMANTIC_EVAL_PARSER='model'
$env:SEMANTIC_EVAL_CORPUS=$Corpus
if ($Model) { $env:LLM_MODEL=$Model }
Push-Location (Join-Path $taskRoot 'backend')
try {
    & .\mvnw.cmd test -q '-Dtest=SemanticLiveEvaluationTest'
    $taskExit = $LASTEXITCODE
} finally { Pop-Location }
} finally {
    foreach ($name in $taskOriginal.Keys) { [Environment]::SetEnvironmentVariable($name,$taskOriginal[$name],'Process') }
}
if ($taskExit -ne 0) { exit $taskExit }
