/** 语义质量证据的离线统计和版本比较；仅使用独立用例评分，不把模型自评、用户反馈或缺失计量当作正确率。 */
const fs = require('node:fs')
const quantile = (values, percentile) => values.length ? [...values].sort((a, b) => a - b)[Math.max(0, Math.ceil(values.length * percentile) - 1)] : null
const latency = values => ({ samples: values.length, p50: quantile(values, 0.5), p95: quantile(values, 0.95), p99: quantile(values, 0.99) })

/** 保留失败分类与计量覆盖率；部分token响应不补零，也不把预期澄清混入明确查询的一次成功率。 */
function summarize(cases) {
  const counts = {}, calls = cases.flatMap(row => row.diagnostic?.calls || [])
  for (const row of cases) counts[row.category] = (counts[row.category] || 0) + 1
  const queries = cases.filter(row => row.expected.kind === 'query')
  const clarifications = cases.filter(row => row.expected.kind === 'clarify')
  const complete = calls.length > 0 && calls.every(row => row.usageComplete === true && Number.isSafeInteger(row.inputTokens) && row.inputTokens >= 0 && Number.isSafeInteger(row.outputTokens) && row.outputTokens >= 0)
  const firstPass = queries.filter(row => row.passed && row.diagnostic?.firstPass === true).length
  const stages = {}
  for (const stage of ['PURPOSE', 'PLAN', 'REVIEW']) {
    const rows = calls.filter(row => row.stage === stage)
    stages[stage] = { calls: rows.length, failures: rows.filter(row => row.status === 'FAILED').length, latencyMs: latency(rows.map(row => row.durationMs).filter(Number.isFinite)) }
  }
  return { attempted: cases.length, passed: cases.filter(row => row.passed).length, failed: cases.filter(row => !row.passed).length, counts,
    explicitQuery: { total: queries.length, passed: queries.filter(row => row.passed).length, firstPass, firstPassRate: queries.length ? firstPass / queries.length : null,
      unnecessaryClarifications: queries.filter(row => row.category === 'UNNECESSARY_CLARIFICATION').length },
    clarification: { total: clarifications.length, passed: clarifications.filter(row => row.passed).length },
    latencyMs: latency(cases.map(row => row.durationMs).filter(Number.isFinite)), stages, modelCalls: calls.length,
    repairCalls: cases.reduce((total, row) => total + (row.diagnostic?.repairCalls || 0), 0),
    usage: { complete, measuredCalls: calls.filter(row => row.usageComplete === true).length, totalCalls: calls.length,
      inputTokens: complete ? calls.reduce((total, row) => total + row.inputTokens, 0) : null,
      outputTokens: complete ? calls.reduce((total, row) => total + row.outputTokens, 0) : null, cost: null, costComplete: false } }
}

/** 失败只按现有证据归类，无法归因时保留未完成；不把HTTP失败折算为语义误解。 */
function classify(expected, result, diagnostic, errors, transport) {
  if (transport) return 'INFRASTRUCTURE_ERROR'
  if (!errors.length) return expected.kind === 'query' ? 'CORRECT_QUERY' : expected.kind === 'clarify' ? 'EXPECTED_CLARIFICATION' : 'EXPECTED_INPUT_REJECTION'
  if (diagnostic?.calls?.some(row => row.status === 'FAILED')) return 'MODEL_CALL_FAILURE'
  if (expected.kind === 'query' && diagnostic?.outcome === 'CLARIFY') return 'UNNECESSARY_CLARIFICATION'
  if (diagnostic?.outcome === 'FAILED') {
    const failed = (diagnostic.validations || []).filter(row => !['PASSED', 'ACCEPTED'].includes(row.outcome))
    if (failed.at(-1)?.stage === 'PRECHECK') return 'BUSINESS_VALIDATION_FAILURE'
    return 'MODEL_VALIDATION_FAILURE'
  }
  return result ? 'EXPECTATION_MISMATCH' : 'INCOMPLETE_RESULT'
}

/** 同语料、评分器、来源事实和日期口径才能比较；不同代码、模型和词典版本是明确展示的实验变量。 */
function compare(before, after) {
  const incompatible = ['corpusSha256', 'sourceSha256', 'scorerSha256', 'authorizationSha256', 'referenceDate'].filter(key => typeof before[key] !== 'string' || !before[key] || before[key] !== after[key])
  const keys = report => report.cases.map(row => row.caseId).sort()
  if (JSON.stringify(keys(before)) !== JSON.stringify(keys(after))) incompatible.push('caseIds')
  if (new Set(keys(before)).size !== before.cases.length || new Set(keys(after)).size !== after.cases.length) incompatible.push('duplicateCaseIds')
  if (incompatible.length) return { comparable: false, reason: incompatible, before: summarize(before.cases), after: summarize(after.cases) }
  const prior = new Map(before.cases.map(row => [row.caseId, row]))
  return { comparable: true, evidence: '两次真实回放的描述性对比，不表示业务独立盲测或统计显著性',
    before: summarize(before.cases), after: summarize(after.cases),
    versions: { before: before.versions, after: after.versions },
    changes: after.cases.filter(row => row.passed !== prior.get(row.caseId).passed || row.category !== prior.get(row.caseId).category)
      .map(row => ({ caseId: row.caseId, before: prior.get(row.caseId).category, after: row.category, regressed: prior.get(row.caseId).passed && !row.passed })) }
}

if (require.main === module) {
  const [before, after, output] = process.argv.slice(2)
  if (!before || !after || !output) throw new Error('Usage: node tools/semantic-quality-evaluation.cjs before.json after.json comparison.json')
  const result = compare(JSON.parse(fs.readFileSync(before, 'utf8').replace(/^\uFEFF/, '')), JSON.parse(fs.readFileSync(after, 'utf8').replace(/^\uFEFF/, '')))
  fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n', { flag: 'wx' })
  console.log(JSON.stringify({ comparable: result.comparable, beforePassed: result.before.passed, afterPassed: result.after.passed }))
  if (!result.comparable || result.changes?.some(row => row.regressed)) process.exitCode = 1
}
module.exports = { summarize, classify, compare }
