/** 评估统计不能通过缺失用量、缺失失败样本或不同语料制造提升；纯离线测试不调用模型或数据库。 */
const test = require('node:test'), assert = require('node:assert/strict')
const { summarize, classify, compare } = require('./semantic-quality-evaluation.cjs')
const { satisfies, dateValues, scoreQuery, clarificationChoicesMatch } = require('./test-semantic-quality.cjs')
const row = (id, passed = true) => ({ caseId: id, passed, category: passed ? 'CORRECT_QUERY' : 'EXPECTATION_MISMATCH', expected: { kind: 'query' }, durationMs: 10,
  diagnostic: { firstPass: passed, repairCalls: 0, calls: [{ stage: 'PLAN', status: 'RETURNED', durationMs: 5, usageComplete: true, inputTokens: 0, outputTokens: 0 }] } })
const report = rows => ({ corpusSha256: 'a', sourceSha256: 'b', scorerSha256: 'c', authorizationSha256: 'd', referenceDate: '2026-10-11', versions: { source: 'v1' }, cases: rows })
test('partial use stays unknown, genuine zero stays measured and expected clarification is counted separately', () => {
  assert.equal(summarize([row('one')]).usage.inputTokens, 0)
  const partial = row('partial'); partial.diagnostic.calls[0].usageComplete = false; partial.diagnostic.calls[0].inputTokens = null
  const clarify = { ...row('clarify'), expected: { kind: 'clarify' }, category: 'EXPECTED_CLARIFICATION' }
  const stats = summarize([row('one'), partial, clarify]); assert.equal(stats.usage.complete, false); assert.equal(stats.usage.inputTokens, null)
  assert.equal(stats.explicitQuery.total, 2); assert.equal(stats.clarification.total, 1); assert.equal(stats.usage.measuredCalls, 2)
})
test('failure facts distinguish transport, model call, business precheck and unwanted clarification', () => {
  const query = { kind: 'query' }, errors = ['missing query']
  assert.equal(classify(query, null, null, errors, true), 'INFRASTRUCTURE_ERROR')
  assert.equal(classify(query, null, { calls: [{ status: 'FAILED' }] }, errors), 'MODEL_CALL_FAILURE')
  assert.equal(classify(query, null, { outcome: 'FAILED', validations: [{ stage: 'PRECHECK', outcome: 'REJECTED' }] }, errors), 'BUSINESS_VALIDATION_FAILURE')
  assert.equal(classify(query, null, { outcome: 'CLARIFY' }, errors), 'UNNECESSARY_CLARIFICATION')
})
test('comparison detects regression and rejects changed corpus, missing cases and duplicate IDs', () => {
  const before = report([row('one'), row('two')]), after = report([row('one', false), row('two')])
  assert.equal(compare(before, after).changes[0].regressed, true)
  assert.equal(compare(before, { ...after, corpusSha256: 'changed' }).comparable, false)
  assert.equal(compare(before, report([row('one')])).comparable, false)
  assert.equal(compare(report([row('one'), row('one')]), report([row('one'), row('one')])).comparable, false)
  assert.equal(compare(before, { ...before, versions: { source: 'v2' } }).comparable, true)
})
test('source oracle preserves cent boundaries, nulls and calendar timezone across the month boundary', () => {
  assert.equal(satisfies({ amount: '90071992547409.93' }, ['amount', 'EQ', '90071992547409.94']), false)
  assert.equal(satisfies({ amount: '0.01' }, ['amount', 'GTE', '0.01']), true)
  assert.equal(satisfies({ amount: null }, ['amount', 'NE', '0']), false)
  const dates = dateValues('2026-10-01T00:00:00Z', { timeZone: 'America/Los_Angeles', fiscalStartMonth: 10 })
  assert.equal(dates.today, '2026-09-30'); assert.equal(dates.previousMonthStart, '2026-08-01'); assert.equal(dates.fiscalYearStart, '2025-10-01')
})
test('clarification field scoring shares source aliases without merging identities, domains or duplicate candidates', () => {
  for (const date of ['saleDate', 'expenseDate', 'dueDate']) assert.equal(clarificationChoicesMatch('FIELD', ['amount', 'date'], [date, 'amount']), true)
  assert.equal(clarificationChoicesMatch('FIELD', ['amount', 'date'], ['amount', 'createdDate']), false)
  assert.equal(clarificationChoicesMatch('FIELD', ['amount', 'date'], ['amount', 'date', 'saleDate']), false)
  assert.equal(clarificationChoicesMatch('FIELD', ['date', 'saleDate'], ['date', 'saleDate']), false)
  assert.equal(clarificationChoicesMatch('FIELD', ['amount', 'date'], ['amount', 'saleDate'], 'WORK_ORDER'), false)
  for (const kind of ['VALUE', 'REPORT', 'COMPANY']) assert.equal(clarificationChoicesMatch(kind, ['amount', 'date'], ['amount', 'saleDate']), false)
})
