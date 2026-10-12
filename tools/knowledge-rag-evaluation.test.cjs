/** 用独立小样本验证召回统计与证据边界；评分器本身不访问模型、数据库或应用会话。 */
const test = require('node:test')
const assert = require('node:assert/strict')
const { validateCorpus, scoreRetrieval, summarizeCalls, evaluate } = require('./knowledge-rag-evaluation.cjs')
const corpus = () => ({ version: 'unit-v1', documents: [{ id: 'positive' }, { id: 'contrary' }, { id: 'noise' }], cases: [
  { id: 'conflict', category: 'CONFLICT', relevantSourceIds: ['positive', 'contrary'], opposingSourceIds: ['contrary'], requiredCitationSourceIds: ['positive', 'contrary'], expectedAnswerStatus: 'CONFLICTING' },
  { id: 'missing', category: 'NO_MATCH', relevantSourceIds: [], opposingSourceIds: [], requiredCitationSourceIds: [], expectedAnswerStatus: 'INSUFFICIENT' }
] })
const retrieval = (...sourceIds) => ({ status: 'SUCCEEDED', sources: sourceIds.map((sourceId, index) => ({ fragmentId: `fragment-${index}`, sourceId })), searches: [{ matchedFragments: sourceIds.length, searchedFragments: Math.max(20, sourceIds.length) }] })
const call = (stage, source = 'MODEL') => ({ source, stage, status: 'RESPONDED', elapsedMs: 3, usageComplete: true, usage: { inputTokens: 0, outputTokens: 0 } })
const observation = (caseId = 'conflict', attempt = 1) => ({ caseId, attempt, source: 'MODEL', durationMs: 12, errors: [],
  retrieval: { lexical: caseId === 'conflict' ? retrieval('positive', 'contrary') : retrieval(), semantic: caseId === 'conflict' ? retrieval('positive', 'contrary') : retrieval() },
  run: { status: 'COMPLETED', answerStatus: caseId === 'conflict' ? 'CONFLICTING' : 'INSUFFICIENT', citationSourceIds: caseId === 'conflict' ? ['positive', 'contrary'] : [] },
  calls: ['PLAN', 'PLAN_REVIEW', 'RAG_REWRITE', ...(caseId === 'conflict' ? ['RAG_RERANK'] : []), 'ANSWER', 'ANSWER_REVIEW'].map(stage => call(stage)) })
const report = (...cases) => ({ corpusSha256: 'verified-fixture', cases })
const score = (...cases) => evaluate(corpus(), report(...cases), 'verified-fixture')

test('recall uses independent sources in the first twelve fragments, so a long document cannot hide a missing opponent', () => {
  const first = corpus().cases[0]
  const result = scoreRetrieval(first, retrieval(...Array(12).fill('positive'), 'contrary'))
  assert.equal(result.recallAt12, 0.5)
  assert.equal(result.retrievedSources, 1)
  assert.equal(result.opposingRecallAt12, 0)
  assert.equal(result.firstRelevantRank, 1)
  assert.equal(result.reciprocalRank, 1)
})

test('rank counts irrelevant fragments and preserves the first relevant one rather than sorting with gold labels', () => {
  const result = scoreRetrieval(corpus().cases[0], retrieval('noise', 'noise', 'contrary', 'positive'))
  assert.equal(result.firstRelevantRank, 3)
  assert.equal(result.reciprocalRank, 1 / 3)
  assert.equal(result.opposingRecallAt12, 1)
})

test('a failed retrieval is not a correct no-match, and absent latency or token counts stay unknown', () => {
  const missing = observation('missing'); missing.retrieval.semantic = { status: 'FAILED' }; missing.durationMs = null
  missing.calls = [{ ...call('RAG_REWRITE'), status: 'FAILED', elapsedMs: null, usageComplete: false, usage: { inputTokens: null, outputTokens: null } }]
  const result = score(observation(), missing)
  assert.equal(result.cases[1].final.semantic.noMatchCorrect, false)
  assert.equal(result.summary.semantic.noMatchAccuracy, 0)
  assert.equal(result.summary.usage.complete, false)
  assert.equal(result.summary.usage.inputTokens, null)
  assert.equal(result.summary.latencyMs.samples, 1)
  assert.equal(result.summary.stages.RAG_REWRITE.failures, 1)
})

test('unexpected irrelevant evidence makes a no-match case fail even when the final answer says insufficient', () => {
  const missing = observation('missing'); missing.retrieval.semantic = retrieval('noise'); missing.calls.push(call('RAG_RERANK'))
  const result = score(observation(), missing)
  assert.equal(result.summary.failed, 1)
  assert.ok(result.cases[1].final.errors.includes('NO_MATCH_EXPECTATION_FAILED'))
})

test('partial measured token usage is not summed as a complete total and zero is valid when natively measured', () => {
  assert.equal(summarizeCalls([call('RAG_REWRITE')]).usage.inputTokens, 0)
  const partial = { ...call('RAG_RERANK'), usageComplete: false, usage: { inputTokens: 6, outputTokens: null } }
  const result = summarizeCalls([call('RAG_REWRITE'), partial, call('PLAN', 'TEST_DOUBLE')])
  assert.equal(result.usage.complete, false)
  assert.equal(result.usage.measuredCalls, 1)
  assert.equal(result.usage.inputTokens, null)
  assert.equal(result.modelCalls, 2)
  assert.equal(result.testDoubleCalls, 1)
})

test('all omitted cases remain in denominators and cannot produce a green report', () => {
  const result = score(observation())
  assert.equal(result.summary.expectedCases, 2)
  assert.equal(result.summary.observedCases, 1)
  assert.equal(result.summary.failed, 1)
  assert.ok(result.cases[1].final.errors.includes('MISSING_OBSERVATION'))
})

test('successful retry retains the original failure and cannot become first-pass success', () => {
  const first = observation(); first.retrieval.semantic = retrieval('positive')
  const result = score(first, observation('conflict', 2), observation('missing'))
  assert.equal(result.summary.failed, 0)
  assert.equal(result.summary.firstPass, 1)
  assert.equal(result.cases[0].attempts.length, 2)
  assert.equal(result.cases[0].attempts[0].passed, false)
  assert.ok(result.cases[0].attempts[0].errors.includes('OPPOSING_SOURCE_MISSING'))
})

test('internal model retries count separately from a whole-case retry', () => {
  const retry = observation(); retry.calls.push(call('ANSWER'))
  const result = score(retry, observation('missing'))
  assert.equal(result.summary.passed, 2)
  assert.equal(result.summary.firstPass, 1)
  assert.equal(result.cases[0].final.repaired, true)
})

test('independent corpus identity, full attempt history and unique case IDs are required', () => {
  assert.throws(() => evaluate(corpus(), { ...report(observation()), corpusSha256: 'different' }, 'verified-fixture'), /fingerprint/)
  assert.throws(() => score(observation(), observation()), /Duplicate case attempt/)
  assert.throws(() => score(observation('conflict', 2)), /Missing attempt history/)
  assert.throws(() => score(observation('invented')), /Unknown observation/)
})

test('duplicate actual fragments and malformed gold cannot inflate or redefine quality', () => {
  assert.throws(() => scoreRetrieval(corpus().cases[0], { status: 'SUCCEEDED', sources: [{ fragmentId: 'same', sourceId: 'positive' }, { fragmentId: 'same', sourceId: 'contrary' }] }), /Duplicate returned fragment/)
  const invalid = corpus(); invalid.cases[0].opposingSourceIds = ['noise']
  assert.throws(() => validateCorpus(invalid), /must be relevant/)
  const repeated = corpus(); repeated.cases[0].relevantSourceIds.push('positive')
  assert.throws(() => validateCorpus(repeated), /Invalid relevantSourceIds/)
})

test('retrieval success alone is insufficient when the final conflict answer omits contrary citations', () => {
  const omitted = observation(); omitted.run.citationSourceIds = ['positive']
  const result = score(omitted, observation('missing'))
  assert.equal(result.cases[0].final.semantic.recallAt12, 1)
  assert.equal(result.cases[0].final.passed, false)
  assert.ok(result.cases[0].final.errors.includes('EXPECTED_CITATION_MISSING'))
})

test('test-double evidence stays visible and cannot be called a real-model quality acceptance', () => {
  const synthetic = observation(); synthetic.source = 'TEST_DOUBLE'; synthetic.calls = synthetic.calls.map(row => ({ ...row, source: 'TEST_DOUBLE' }))
  const result = score(synthetic, observation('missing'))
  assert.equal(result.summary.realModelCases, 1)
  assert.equal(result.summary.failed, 1)
  assert.equal(result.summary.testDoubleCalls, 6)
  assert.ok(result.cases[0].final.errors.includes('REAL_MODEL_EVIDENCE_INCOMPLETE'))
})

test('a cross-language claim of improvement requires an actual lexical miss in the same observation', () => {
  const expected = corpus(); expected.cases[0].expectLexicalMiss = true
  const result = evaluate(expected, report(observation(), observation('missing')), 'verified-fixture')
  assert.ok(result.cases[0].final.errors.includes('LEXICAL_MISS_PRECONDITION_FAILED'))
})

test('the maintained RAG fixture has valid independent source labels and a no-alias cross-language case', () => {
  const actual = validateCorpus(require('./fixtures/knowledge-rag-regression-v1.json'))
  assert.equal(actual.cases.length, 5)
  assert.equal(actual.cases.filter(row => row.expectLexicalMiss).length, 1)
  assert.ok(actual.cases.some(row => row.opposingSourceIds.length > 0))
  assert.ok(actual.cases.some(row => row.relevantSourceIds.length === 0))
})

test('empty returned context with known matches is truncated coverage, not a successful no-match', () => {
  const truncated = retrieval(); truncated.searches[0].matchedFragments = 2
  assert.equal(scoreRetrieval(corpus().cases[1], truncated).noMatchCorrect, false)
  const missingCounts = retrieval(); delete missingCounts.searches
  assert.throws(() => scoreRetrieval(corpus().cases[1], missingCounts), /actual per-topic/)
})

test('real RAG calls cannot stand in for an absent independent answer review', () => {
  const missingReview = observation(); missingReview.calls = missingReview.calls.filter(row => row.stage !== 'ANSWER_REVIEW')
  const result = score(missingReview, observation('missing'))
  assert.equal(result.cases[0].final.realModelVerified, false)
  assert.ok(result.cases[0].final.errors.includes('REAL_MODEL_EVIDENCE_INCOMPLETE'))
})
