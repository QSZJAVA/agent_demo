/** RAG 独立来源金标评分：片段去重、来源召回、反对依据与无命中分别计量，不把模型自评当成准确率。 */
const fs = require('node:fs')
const crypto = require('node:crypto')
const TOP_K = 12
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex')
const validCount = value => Number.isSafeInteger(value) && value >= 0
const average = values => values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null
const quantile = (values, q) => values.length ? [...values].sort((a, b) => a - b)[Math.max(0, Math.ceil(values.length * q) - 1)] : null
const latency = values => ({ samples: values.length, p50: quantile(values, 0.5), p95: quantile(values, 0.95), p99: quantile(values, 0.99) })

/** 金标只能使用语料实际来源编号；重复、未知编号和不属于相关集合的反对依据均拒绝，避免分母被悄悄改写。 */
function validateCorpus(corpus) {
  if (!corpus || !Array.isArray(corpus.documents) || !Array.isArray(corpus.cases) || !corpus.cases.length) throw new Error('RAG corpus requires documents and nonempty cases')
  const ids = (rows, label) => {
    const values = rows.map(row => row?.id)
    if (values.some(id => typeof id !== 'string' || !id) || new Set(values).size !== values.length) throw new Error(`Invalid or duplicate ${label} IDs`)
    return new Set(values)
  }
  const documents = ids(corpus.documents, 'document')
  ids(corpus.cases, 'case')
  for (const item of corpus.cases) {
    for (const field of ['relevantSourceIds', 'opposingSourceIds', 'requiredCitationSourceIds']) {
      const values = item[field]
      if (!Array.isArray(values) || new Set(values).size !== values.length || values.some(id => !documents.has(id))) throw new Error(`Invalid ${field}: ${item.id}`)
    }
    if ([...item.opposingSourceIds, ...item.requiredCitationSourceIds].some(id => !item.relevantSourceIds.includes(id))) throw new Error(`Gold citations and opposing sources must be relevant: ${item.id}`)
    if (!['ANSWERED', 'CONFLICTING', 'INSUFFICIENT'].includes(item.expectedAnswerStatus)) throw new Error(`Invalid expected answer status: ${item.id}`)
  }
  return corpus
}

/** 先取实际返回的前十二个片段，再按原始来源取并集；同一长文档占多个片段不会获得多次召回奖励。 */
function scoreRetrieval(expected, result) {
  if (!result || result.status !== 'SUCCEEDED') return { available: false, retrievedFragments: 0, retrievedSources: 0, recallAt12: expected.relevantSourceIds.length ? 0 : null,
    firstRelevantRank: null, reciprocalRank: expected.relevantSourceIds.length ? 0 : null, opposingRecallAt12: expected.opposingSourceIds.length ? 0 : null,
    noMatchCorrect: expected.relevantSourceIds.length ? null : false, relevantRetrieved: [], opposingRetrieved: [] }
  if (!Array.isArray(result.sources) || result.sources.some(row => !row || typeof row.fragmentId !== 'string' || !row.fragmentId || typeof row.sourceId !== 'string' || !row.sourceId)) throw new Error('Retrieval requires actual fragment/source IDs')
  if (new Set(result.sources.map(row => row.fragmentId)).size !== result.sources.length) throw new Error('Duplicate returned fragment ID')
  if (!Array.isArray(result.searches) || !result.searches.length || result.searches.some(row => !validCount(row.matchedFragments) || !validCount(row.searchedFragments) || row.matchedFragments > row.searchedFragments)) throw new Error('Retrieval requires actual per-topic matched/searched counts')
  const top = result.sources.slice(0, TOP_K), unique = new Set(top.map(row => row.sourceId))
  const found = expected.relevantSourceIds.filter(id => unique.has(id)), opposing = expected.opposingSourceIds.filter(id => unique.has(id))
  const first = top.findIndex(row => expected.relevantSourceIds.includes(row.sourceId))
  return { available: true, retrievedFragments: top.length, retrievedSources: unique.size, recallAt12: expected.relevantSourceIds.length ? found.length / expected.relevantSourceIds.length : null,
    firstRelevantRank: first < 0 ? null : first + 1, reciprocalRank: expected.relevantSourceIds.length ? first < 0 ? 0 : 1 / (first + 1) : null,
    opposingRecallAt12: expected.opposingSourceIds.length ? opposing.length / expected.opposingSourceIds.length : null,
    noMatchCorrect: expected.relevantSourceIds.length ? null : result.sources.length === 0 && result.searches.every(row => row.matchedFragments === 0), relevantRetrieved: found, opposingRetrieved: opposing }
}

/** 缺失原生用量保留未知；真实零与未知不同，替身调用从真实模型统计中单列。 */
function summarizeCalls(calls) {
  const actual = calls.filter(row => row.source === 'MODEL')
  const measured = actual.filter(row => row.usageComplete === true && validCount(row.usage?.inputTokens) && validCount(row.usage?.outputTokens))
  const complete = actual.length > 0 && actual.length === measured.length
  const stages = {}
  for (const stage of [...new Set(actual.map(row => row.stage))].sort()) {
    const values = actual.filter(row => row.stage === stage)
    stages[stage] = { calls: values.length, failures: values.filter(row => row.status === 'FAILED').length,
      latencyMs: latency(values.map(row => row.elapsedMs).filter(value => Number.isFinite(value) && value >= 0)) }
  }
  return { modelCalls: actual.length, testDoubleCalls: calls.length - actual.length, stages,
    usage: { complete, measuredCalls: measured.length, totalCalls: actual.length,
      inputTokens: complete ? measured.reduce((sum, row) => sum + row.usage.inputTokens, 0) : null,
      outputTokens: complete ? measured.reduce((sum, row) => sum + row.usage.outputTokens, 0) : null, cost: null, costComplete: false } }
}

/** 评分每次执行并保留全部失败。回答项仅核对状态和实际引用覆盖，不声称已替代人工对业务结论的审阅。 */
function scoreAttempt(expected, observation) {
  const lexical = scoreRetrieval(expected, observation?.retrieval?.lexical)
  const semantic = scoreRetrieval(expected, observation?.retrieval?.semantic)
  const errors = [...(observation?.errors || [])]
  if (!observation) errors.push('MISSING_OBSERVATION')
  if (!lexical.available) errors.push('LEXICAL_RESULT_MISSING_OR_FAILED')
  if (!semantic.available) errors.push('SEMANTIC_RESULT_MISSING_OR_FAILED')
  if (semantic.recallAt12 !== null && semantic.recallAt12 < 1) errors.push('RELEVANT_SOURCE_MISSING')
  if (semantic.opposingRecallAt12 !== null && semantic.opposingRecallAt12 < 1) errors.push('OPPOSING_SOURCE_MISSING')
  if (semantic.noMatchCorrect === false) errors.push('NO_MATCH_EXPECTATION_FAILED')
  if (expected.expectLexicalMiss && lexical.recallAt12 !== 0) errors.push('LEXICAL_MISS_PRECONDITION_FAILED')
  const citations = observation?.run?.citationSourceIds
  const answerStateCorrect = observation?.run?.status === 'COMPLETED' && observation.run.answerStatus === expected.expectedAnswerStatus
  const citationCoverageCorrect = Array.isArray(citations) && expected.requiredCitationSourceIds.every(id => citations.includes(id)) &&
    (!expected.relevantSourceIds.length || citations.some(id => expected.relevantSourceIds.includes(id)))
  if (!answerStateCorrect) errors.push('ANSWER_STATUS_MISMATCH')
  if (!citationCoverageCorrect) errors.push('EXPECTED_CITATION_MISSING')
  const calls = observation?.calls || []
  const ragCalls = calls.filter(row => ['RAG_REWRITE', 'RAG_RERANK'].includes(row.stage))
  const requiredStages = ['PLAN', 'PLAN_REVIEW', 'RAG_REWRITE', 'ANSWER', 'ANSWER_REVIEW']
  const realModelVerified = observation?.source === 'MODEL' && requiredStages.every(stage => calls.some(row => row.stage === stage && row.status === 'RESPONDED')) &&
    calls.length > 0 && calls.every(row => row.source === 'MODEL') && ragCalls.every(row => row.status === 'RESPONDED') &&
    (semantic.retrievedFragments === 0 || ragCalls.some(row => row.stage === 'RAG_RERANK'))
  if (!realModelVerified) errors.push('REAL_MODEL_EVIDENCE_INCOMPLETE')
  const counts = new Map()
  for (const call of calls) counts.set(call.stage, (counts.get(call.stage) || 0) + 1)
  const repaired = [...counts.values()].some(count => count > 1)
  return { caseId: expected.id, attempt: observation?.attempt || null, category: expected.category, source: observation?.source || null,
    passed: errors.length === 0, errors: [...new Set(errors)], lexical, semantic, realModelVerified, repaired,
    answer: { statusCorrect: answerStateCorrect, citationCoverageCorrect, scope: '仅核对最终状态与实际引用覆盖；业务结论仍须审阅' },
    durationMs: Number.isFinite(observation?.durationMs) && observation.durationMs >= 0 ? observation.durationMs : null,
    calls: summarizeCalls(calls) }
}

/** 不遗漏未执行用例，不把重试后的通过改写为一次通过；不同来源的执行可并列保留但不会冒充真实验收。 */
function evaluate(corpus, observations, expectedCorpusSha256) {
  validateCorpus(corpus)
  if (!observations || !Array.isArray(observations.cases)) throw new Error('RAG observations require cases')
  if (typeof expectedCorpusSha256 !== 'string' || !expectedCorpusSha256 || observations.corpusSha256 !== expectedCorpusSha256) throw new Error('Corpus fingerprint does not match the independently scored fixture')
  const known = new Map(corpus.cases.map(item => [item.id, item])), groups = new Map()
  for (const row of observations.cases) {
    if (!known.has(row?.caseId)) throw new Error(`Unknown observation case: ${row?.caseId}`)
    if (!Number.isSafeInteger(row.attempt) || row.attempt < 1) throw new Error(`Invalid attempt number: ${row.caseId}`)
    const attempts = groups.get(row.caseId) || []
    if (attempts.some(item => item.attempt === row.attempt)) throw new Error(`Duplicate case attempt: ${row.caseId}/${row.attempt}`)
    attempts.push(row); groups.set(row.caseId, attempts)
  }
  const cases = corpus.cases.map(expected => {
    const rows = (groups.get(expected.id) || []).sort((a, b) => a.attempt - b.attempt)
    if (rows.some((row, index) => row.attempt !== index + 1)) throw new Error(`Missing attempt history: ${expected.id}`)
    const attempts = rows.length ? rows.map(row => scoreAttempt(expected, row)) : [scoreAttempt(expected, null)]
    return { caseId: expected.id, firstPass: attempts[0].passed && !attempts[0].repaired, attempts, final: attempts.at(-1) }
  })
  const finals = cases.map(row => row.final), allCalls = observations.cases.flatMap(row => row.calls || [])
  const metrics = mode => ({ recallAt12: average(finals.map(row => row[mode].recallAt12).filter(value => value !== null)),
    meanReciprocalRankAt12: average(finals.map(row => row[mode].reciprocalRank).filter(value => value !== null)),
    opposingRecallAt12: average(finals.map(row => row[mode].opposingRecallAt12).filter(value => value !== null)),
    noMatchAccuracy: average(finals.map(row => row[mode].noMatchCorrect).filter(value => value !== null).map(Number)) })
  return { corpusVersion: corpus.version, corpusSha256: expectedCorpusSha256, scorerSha256: sha256(fs.readFileSync(__filename)), topK: TOP_K,
    evidenceScope: '仅统计输入文件中完整记录的执行；工程编写的独立来源金标，非业务盲测，不推断未提供的历史尝试',
    summary: { expectedCases: cases.length, observedCases: groups.size, attempts: observations.cases.length,
      passed: finals.filter(row => row.passed).length, failed: finals.filter(row => !row.passed).length,
      firstPass: cases.filter(row => row.firstPass).length, firstPassRate: cases.filter(row => row.firstPass).length / cases.length,
      realModelCases: finals.filter(row => row.realModelVerified).length, lexical: metrics('lexical'), semantic: metrics('semantic'),
      latencyMs: latency(finals.map(row => row.durationMs).filter(value => value !== null)), ...summarizeCalls(allCalls) }, cases }
}

if (require.main === module) {
  const [corpusPath, observationsPath, outputPath] = process.argv.slice(2)
  if (!corpusPath || !observationsPath || !outputPath) throw new Error('Usage: node tools/knowledge-rag-evaluation.cjs corpus.json observations.json evaluation.json')
  const rawCorpus = fs.readFileSync(corpusPath)
  const parse = value => JSON.parse(value.toString('utf8').replace(/^\uFEFF/, ''))
  const result = evaluate(parse(rawCorpus), parse(fs.readFileSync(observationsPath)), sha256(rawCorpus))
  fs.writeFileSync(outputPath, JSON.stringify(result, null, 2) + '\n', { flag: 'wx' })
  console.log(JSON.stringify(result.summary))
  if (result.summary.failed) process.exitCode = 1
}
module.exports = { validateCorpus, scoreRetrieval, scoreAttempt, summarizeCalls, evaluate }
