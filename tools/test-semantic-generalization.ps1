param([ValidateRange(1,5)][int]$Repetitions=3,[ValidateSet('generalization-holdout.json','generalization-reserve.json')][string]$Corpus='generalization-holdout.json')
# 独立真实模型泛化回放：客户与问法不进入提示词；保存每轮成功和失败、输入哈希与实际调用统计，不执行派单。
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskRun=Join-Path $taskRoot ('backend/target/semantic-generalization/'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $taskRun | Out-Null
$taskSaved=@{}
foreach($taskName in @('LLM_API_KEY','LLM_BASE_URL','LLM_MODEL')) {$taskSaved[$taskName]=[Environment]::GetEnvironmentVariable($taskName)}
$taskResults=@()
$taskImplementation=@()
foreach($taskFolder in @('backend/src/main/java/com/example/report/semantic','backend/src/main/java/com/example/report/catalog','backend/src/main/java/com/example/report/rule')) {
    Get-ChildItem -LiteralPath (Join-Path $taskRoot $taskFolder) -Filter '*.java' | Sort-Object Name | ForEach-Object {
        $taskImplementation+=@{path=$taskFolder+'/'+$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    }
}
try {
    $taskLocal=Join-Path $taskRoot '.runtime/llm-credentials.json'
    if(-not $env:LLM_API_KEY -and (Test-Path -LiteralPath $taskLocal)) {
        $taskModel=Get-Content -LiteralPath $taskLocal -Raw | ConvertFrom-Json
        $env:LLM_API_KEY=$taskModel.apiKey
        $env:LLM_BASE_URL=$taskModel.baseUrl.TrimEnd('/') -replace '/v1$',''
        $env:LLM_MODEL=$taskModel.model
    }
    for($taskIndex=1;$taskIndex -le $Repetitions;$taskIndex++) {
        $taskOutput=Join-Path $taskRoot 'backend/target/semantic-live-evaluation.json'
        if(Test-Path -LiteralPath $taskOutput){Remove-Item -LiteralPath $taskOutput}
        & (Join-Path $PSScriptRoot 'test-semantic-live.ps1') -NativeSchema -Corpus $Corpus *> (Join-Path $taskRun "round-$taskIndex.log")
        $taskCode=$LASTEXITCODE
        if(Test-Path -LiteralPath $taskOutput) {
            Copy-Item -LiteralPath $taskOutput -Destination (Join-Path $taskRun "round-$taskIndex.json")
            $taskEval=Get-Content -LiteralPath $taskOutput -Raw | ConvertFrom-Json
            $taskResults+=@{round=$taskIndex;exitCode=$taskCode;passed=$taskEval.passed;total=$taskEval.total;modelCalls=$taskEval.modelCalls;formatRepairs=$taskEval.formatRepairs;wrongReadyCount=$taskEval.wrongReadyCount;promptSha256=$taskEval.promptSha256;model=$taskEval.model}
            Write-Output "Generalization round $taskIndex : $($taskEval.passed)/$($taskEval.total), wrong-ready=$($taskEval.wrongReadyCount)"
        } else {$taskResults+=@{round=$taskIndex;exitCode=$taskCode;completed=$false};break}
    }
} finally {
    @{scope='Real model with isolated synthetic facts; no HTTP MCP or dispatch execution';repetitionsRequested=$Repetitions;corpus=$Corpus;rounds=$taskResults;
      corpusSha256=(Get-FileHash (Join-Path $taskRoot ('backend/src/test/resources/semantic/'+$Corpus)) -Algorithm SHA256).Hash;
      schemaSha256=(Get-FileHash (Join-Path $taskRoot 'backend/src/main/resources/semantic/intent-v1.schema.json') -Algorithm SHA256).Hash;
      implementationSources=$taskImplementation} |
        ConvertTo-Json -Depth 10 | Set-Content (Join-Path $taskRun 'manifest.json') -Encoding utf8
    foreach($taskName in $taskSaved.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskSaved[$taskName],'Process')}
    Write-Output "Evidence: $taskRun"
}
if($taskResults.Count -ne $Repetitions -or @($taskResults|Where-Object {$_.exitCode -ne 0}).Count -gt 0){exit 1}
