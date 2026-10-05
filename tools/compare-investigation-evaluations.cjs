/** 调查评估基线对比；读取脱敏产物并核对控制变量，只比较共同场景，不把配置阻断批次当作质量基线。 */
const fs = require('node:fs'), path = require('node:path')
const [baselinePath, candidatePath, outputPath = path.join(__dirname, '../.runtime/investigation-comparison.json')] = process.argv.slice(2)
if (!baselinePath || !candidatePath) throw new Error('Usage: node tools/compare-investigation-evaluations.cjs BASELINE_DIR CANDIDATE_DIR [OUTPUT_JSON]')
const read = directory => ({
  manifest: JSON.parse(fs.readFileSync(path.join(directory, 'manifest.json'), 'utf8')),
  summary: JSON.parse(fs.readFileSync(path.join(directory, 'summary.json'), 'utf8')),
  runs: fs.readFileSync(path.join(directory, 'runs.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line))
})
const before = read(baselinePath), after = read(candidatePath)
const stable = value => value && typeof value === 'object' ? Array.isArray(value) ? value.map(stable) : Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value
const same = (a, b) => JSON.stringify(stable(a)) === JSON.stringify(stable(b))
// 与Java续跑校验共享名单；双方都缺字段也不代表控制变量相同，不能据此宣布可比。
const contract = require('../backend/src/test/resources/investigation/evaluation-controls.json')
const controls = contract.manifestControls.map(key => [key, before.manifest[key], after.manifest[key]])
for (const key of contract.configurationControls) controls.push([key, before.manifest.configuration?.[key], after.manifest.configuration?.[key]])
const changedControls = controls.filter(([, a, b]) => a == null || b == null || !same(a, b)).map(([field, baseline, candidate]) => ({ field, baseline: baseline ?? null, candidate: candidate ?? null }))
const index = new Map(before.runs.map(run => [`${run.caseId}:${run.repeat}`, run]))
const paired = after.runs.filter(run => index.has(`${run.caseId}:${run.repeat}`)).map(run => {
  const prior = index.get(`${run.caseId}:${run.repeat}`)
  return { caseId: run.caseId, repeat: run.repeat, factsMatched: prior.factsHash === run.factsHash && Boolean(run.factsHash), baselinePassed: prior.passed, candidatePassed: run.passed, durationDeltaMs: run.durationMs - prior.durationMs }
})
// 重复题次会制造虚假的一对一配对；空批次也没有可供比较的质量证据。
const uniqueRuns = runs => runs.length > 0 && new Set(runs.map(run => `${run.caseId}:${run.repeat}`)).size === runs.length
const metrics = Object.fromEntries(['completionRate', 'p50Ms', 'p95Ms', 'maxMs', 'modelCalls', 'toolCalls', 'mcpCalls', 'cost'].map(key => {
  const a = before.summary[key], b = after.summary[key]
  return [key, { baseline: a ?? null, candidate: b ?? null, delta: typeof a === 'number' && typeof b === 'number' ? b - a : null }]
}))
const result = { createdAt: new Date().toISOString(), evidenceScope: after.manifest.evidenceScope,
  comparable: before.summary.status === 'COMPLETED' && after.summary.status === 'COMPLETED' && uniqueRuns(before.runs) && uniqueRuns(after.runs) && !changedControls.length && paired.every(run => run.factsMatched) && paired.length === before.runs.length && paired.length === after.runs.length,
  changedControls, pairedRuns: paired.length, baselineRuns: before.runs.length, candidateRuns: after.runs.length,
  variables: Object.fromEntries(contract.comparisonVariables.map(field => [field, { baseline: before.manifest.configuration?.[field], candidate: after.manifest.configuration?.[field] }])),
  metrics, paired }
fs.mkdirSync(path.dirname(path.resolve(outputPath)), { recursive: true })
fs.writeFileSync(outputPath, JSON.stringify(result, null, 2) + '\n')
console.log(`Compared ${paired.length} paired runs; controls ${changedControls.length ? 'differ' : 'match'}; result: ${path.resolve(outputPath)}`)
