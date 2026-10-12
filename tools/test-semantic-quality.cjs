#!/usr/bin/env node
/**
 * P0真实模型及认证HTTP MCP回放：来源报表页独立给出事实，版本化语料给出期望语义，逐轮保留成功与失败。
 * 只使用已启动且明确授权的本机隔离夹具；发送查询、条件编辑与澄清选择，不确认派单或修改业务来源。
 */
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto')
const { compareQueryFilters, compareDecimal, filterField } = require('./query-filter-semantics.cjs')
const { summarize, classify } = require('./semantic-quality-evaluation.cjs')
const root = path.resolve(__dirname, '..'), args = process.argv.slice(2)
const option = name => { const index = args.indexOf(name); if (index < 0 || !args[index + 1]) throw new Error('Missing ' + name); return args[index + 1] }
const read = file => JSON.parse(fs.readFileSync(path.resolve(root, file), 'utf8').replace(/^\uFEFF/, ''))
const hash = value => crypto.createHash('sha256').update(value).digest('hex')
const last = (events, type) => events.filter(row => row.type === type).at(-1)?.data
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const fixed = { sales: 'rpt-sales-order', receivable: 'rpt-ar-invoice', expense: 'rpt-expense-claim' }
const key = row => row.reportId + ':' + row.recordId
function local(value) { const url = new URL(value); if (url.protocol !== 'http:' || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) || url.username || url.password) throw new Error('Expected a local fixture URL'); return url }
function compareValue(a, b, type) { if (['decimal', 'integer', 'long'].includes(type)) { const c = compareDecimal(String(a), String(b)); if (c == null) throw new Error('Invalid independent decimal'); return c } return String(a) < String(b) ? -1 : String(a) > String(b) ? 1 : 0 }
function satisfies(row, [field, operator, input]) {
  const name = ['saleDate', 'dueDate', 'expenseDate'].includes(field) ? 'date' : field, value = row[name]
  if (operator === 'IS_NULL') return value == null
  if (operator === 'NOT_NULL') return value != null
  if (value == null) return false
  const values = (Array.isArray(input) ? input : [input]).map(String), text = String(value), type = name === 'amount' ? 'decimal' : 'string'
  const c = compareValue(value, values[0], type)
  switch (operator) {
    case 'EQ': return c === 0
    case 'NE': return c !== 0
    case 'GT': return c > 0
    case 'GTE': return c >= 0
    case 'LT': return c < 0
    case 'LTE': return c <= 0
    case 'IN': return values.some(v => compareValue(value, v, type) === 0)
    case 'NOT_IN': return values.every(v => compareValue(value, v, type) !== 0)
    case 'CONTAINS': return text.includes(values[0])
    case 'STARTS_WITH': return text.startsWith(values[0])
    default: throw new Error('Unknown expected operator')
  }
}
function cents(value) { const m = String(value).match(/^(-?)(\d+)(?:\.(\d{1,2}))?$/); if (!m) throw new Error('Source amount must have exact cent precision'); return (BigInt(m[2]) * 100n + BigInt(((m[3] || '') + '00').slice(0, 2))) * (m[1] ? -1n : 1n) }
function dateValues(referenceTime, definition) {
  const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: definition.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(referenceTime)).filter(x => x.type !== 'literal').map(x => [x.type, x.value]))
  const year = Number(p.year), month = Number(p.month), iso = (y, m) => new Date(Date.UTC(y, m - 1, 1)).toISOString().slice(0, 10)
  return { previousMonthStart: iso(year, month - 1), monthStart: iso(year, month), fiscalYearStart: iso(month < definition.fiscalStartMonth ? year - 1 : year, definition.fiscalStartMonth), today: `${p.year}-${p.month}-${p.day}` }
}
function replaceDates(value, dates) { if (typeof value === 'string' && value.startsWith('@')) { if (!(value.slice(1) in dates)) throw new Error('Unknown date placeholder'); return dates[value.slice(1)] } if (Array.isArray(value)) return value.map(v => replaceDates(v, dates)); if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, replaceDates(v, dates)])); return value }
/** 字段候选与条件共享来源字段等价关系；公司、报表及枚举身份仍逐字比较，重复等价选项不能充作两个候选。 */
function clarificationChoicesMatch(kind, expected, actual, domain = 'REPORT') {
  const canonical = values => values.map(value => kind === 'FIELD' ? filterField(value, domain) : value).sort()
  const left = canonical(expected), right = canonical(actual)
  return new Set(left).size === left.length && new Set(right).size === right.length && JSON.stringify(left) === JSON.stringify(right)
}
function scoreQuery(expected, result, source, errors) {
  const q = result.query
  for (const name of ['domain', 'view', 'companyCode', 'sortField', 'descending', 'page', 'size', 'groupBy']) {
    const normalize = value => ['sortField', 'groupBy'].includes(name) ? filterField(value, q.domain) : value
    if (Object.hasOwn(expected, name) && normalize(expected[name]) !== normalize(q[name])) errors.push('QUERY_' + name)
  }
  if (JSON.stringify([...expected.reportIds].sort()) !== JSON.stringify([...q.reportIds].sort())) errors.push('QUERY_REPORT_SCOPE')
  if (!compareQueryFilters(expected, q, expected.companyCode, result.columns).equal) errors.push('QUERY_CONDITION_SEMANTICS')
  const truth = source.filter(row => expected.reportIds.includes(row.reportId) && (!expected.companyCode || row.companyCode === expected.companyCode) && (expected.where || []).every(c => satisfies(row, c)) && (!expected.anyOf || expected.anyOf.some(group => group.every(c => satisfies(row, c)))))
  if (result.total !== truth.length || result.summary?.count !== truth.length) errors.push('FULL_RESULT_COUNT')
  const columns = new Map(result.columns.map(f => [f.name, f.type]))
  truth.sort((a, b) => { if (q.sortField) { const x = a[q.sortField], y = b[q.sortField]; if (x == null && y != null) return 1; if (x != null && y == null) return -1; const c = x == null ? 0 : compareValue(x, y, columns.get(q.sortField)); if (c) return q.descending ? -c : c } return key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0 })
  const wanted = truth.slice((q.page - 1) * q.size, q.page * q.size).map(key), actual = result.rows.map(key)
  if (JSON.stringify(wanted) !== JSON.stringify(actual)) errors.push('DISPLAYED_SOURCE_IDENTITIES')
  const byId = new Map(truth.map(row => [key(row), row]))
  for (const row of result.rows) { const original = byId.get(key(row)); if (!original) continue; for (const name of ['amount', 'companyCode', 'docNo', 'date', 'status']) if (name === 'amount' ? compareDecimal(String(row[name]), String(original[name])) !== 0 : row[name] !== original[name]) errors.push('SOURCE_VALUE_' + name) }
  if (truth.length && (result.summary.amountsByCurrency.CNY == null || truth.reduce((sum, row) => sum + cents(row.amount), 0n) !== cents(result.summary.amountsByCurrency.CNY))) errors.push('EXACT_AMOUNT_TOTAL')
  return { count: truth.length, displayedKeys: wanted }
}

async function main() {
  const corpusFile = option('--corpus'), runtime = read(option('--runtime-info')), credentialFile = option('--credentials'), output = path.resolve(root, option('--output'))
  const corpus = read(corpusFile), account = read(credentialFile), base = local(runtime.agentUrl)
  if (!runtime.profiles?.includes('real') || !runtime.profiles?.includes('mcp') || runtime.semanticMode !== 'active' || !/^mcp_it_[a-f0-9]{32}$/.test(runtime.schema)) throw new Error('A real,mcp isolated fixture is required')
  if (!['regression', 'reserve'].includes(corpus.partition) || !corpus.version || corpus.businessReviewed !== false || !corpus.scenarios?.length) throw new Error('Corpus needs explicit version, partition and review provenance')
  if (fs.existsSync(output)) throw new Error('Evidence exists; use a new output for every attempt')
  let token, lastStart = 0
  async function api(route, body) { const response = await fetch(new URL('/api/' + route, base), { method: body ? 'POST' : 'GET', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(180000) }); const json = await response.json(); if (!response.ok || json.code !== 0) throw new Error('API_' + response.status + '_' + json.code); return json.data }
  token = (await api('auth/login', { userId: account.userId, password: account.password })).token
  const identity = await api('auth/me'), authorizedCatalog = await api('semantic-quality/catalog'), source = [], dictionaries = []
  for (const [type, reportId] of Object.entries(fixed)) {
    for (let page = 1; ; page++) {
      const rows = await api(`report/${type}/page?page=${page}&size=200`)
      for (const row of rows.records) source.push({ ...row, reportId, recordId: String(row.id), docNo: row.orderNo || row.invoiceNo || row.expenseNo,
        date: row.saleDate ?? row.dueDate ?? row.expenseDate ?? null, status: row.dispatchStatus === 1 ? '已派单' : '未派单', amount: String(row.amount) })
      if (source.filter(row => row.reportId === reportId).length >= rows.total) break
      if (page >= 25 || !rows.records.length) throw new Error('Independent source exceeds fixture budget')
    }
  }
  for (const scope of corpus.dictionaryScopes) dictionaries.push((await api('semantic-quality/dictionary?' + new URLSearchParams(scope))).entry)
  const paths = []
  function walk(dir) { for (const file of fs.readdirSync(path.join(root, dir), { withFileTypes: true })) { const p = dir + '/' + file.name; if (file.isDirectory()) walk(p); else paths.push(p) } }
  for (const dir of ['backend/src/main', 'business-service/src/main', 'frontend/src']) walk(dir)
  const referenceDate = dateValues(new Date().toISOString(), { timeZone: 'Asia/Shanghai', fiscalStartMonth: 4 }).today
  const evidence = { recordedAt: new Date().toISOString(), scope: '真实模型、登录认证和HTTP MCP；独立报表页事实评分；本文件不是浏览器验收',
    corpus: corpusFile, partition: corpus.partition, provenance: corpus.provenance, businessReviewed: false,
    corpusSha256: hash(fs.readFileSync(path.resolve(root, corpusFile))), sourceSha256: hash(JSON.stringify(source)),
    authorizationSha256: hash(JSON.stringify(authorizedCatalog)), authorizedCatalog,
    scorerSha256: hash(fs.readFileSync(__filename) + fs.readFileSync(path.join(__dirname, 'query-filter-semantics.cjs')) + fs.readFileSync(path.join(__dirname, 'semantic-quality-evaluation.cjs'))), referenceDate,
    versions: { model: (await api('agent/model')).model, schema: runtime.schema, corpusVersion: corpus.version, dictionaries, sources: paths.sort().map(file => ({ path: file, sha256: hash(fs.readFileSync(path.join(root, file))) })) },
    source, cases: [] }
  fs.mkdirSync(path.dirname(output), { recursive: true }); fs.writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n', { flag: 'wx' })
  function save() { evidence.summary = summarize(evidence.cases); evidence.updatedAt = new Date().toISOString(); fs.writeFileSync(output, JSON.stringify(evidence, null, 2) + '\n') }
  async function diagnostic(requestId) { if (!requestId) return null; let before; for (let page = 0; page < 30; page++) { const records = await api('semantic-quality/runs' + (before ? '?before=' + before : '')); const found = records.records.find(row => row.diagnostic.requestId === requestId); if (found) return found; if (!records.nextCursor) return null; before = records.nextCursor } throw new Error('Diagnostic scan exceeded budget') }
  for (const scenario of corpus.scenarios) {
    let conversationId = null; const cards = [], clarifications = []
    for (const [index, turn] of scenario.turns.entries()) {
      // 请求号属于本轮完整输入；任何显式核对必须复用它，不能由传输层自动换键重试。
      const started = Date.now(), errors = [], clientKey = 'chat-' + crypto.randomUUID(); let events = [], observed, actualDiagnostic, truth, transport = false, input
      try {
        await wait(Math.max(0, 3000 - (Date.now() - lastStart))); lastStart = Date.now()
        input = { conversationId, message: turn.message }
        if (turn.edit) { const card = cards[turn.edit.from ?? cards.length - 1]; if (!card?.interpretation?.queryRef) throw new Error('Missing prior query reference'); input.queryEdit = { queryRef: card.interpretation.queryRef, conditions: turn.edit.conditions } }
        if (turn.choose) { const card = clarifications[turn.choose.from ?? clarifications.length - 1]; if (!card) throw new Error('Missing prior clarification'); input.clarificationReply = { clarificationRef: card.clarificationRef, value: turn.choose.value } }
        let response
        try { response = await fetch(new URL('/api/agent/chat', base), { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token, 'Idempotency-Key': clientKey }, body: JSON.stringify(input), signal: AbortSignal.timeout(240000) }) }
        catch { transport = true; throw new Error('CHAT_TRANSPORT_FAILURE') }
        if (!response.ok) { transport = true; throw new Error('CHAT_HTTP_' + response.status) }
        const wire = await response.text()
        for (const block of wire.replaceAll('\r\n', '\n').split('\n\n')) { const lines = block.split('\n'), type = lines.find(line => line.startsWith('event:'))?.slice(6).trim(), data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n'); if (type && data) events.push({ type, data: JSON.parse(data) }) }
        conversationId = last(events, 'conversation')?.conversationId || conversationId
        if (!events.some(row => row.type === 'done')) errors.push('STREAM_INCOMPLETE')
        if (events.some(row => ['preview', 'plan', 'result'].includes(row.type))) errors.push('UNREQUESTED_DISPATCH_PATH')
        const run = await diagnostic(last(events, 'conversation')?.requestId); actualDiagnostic = run?.diagnostic
        observed = last(events, 'business_query'); if (observed) cards.push(observed)
        const clarify = last(events, 'semantic_clarification'); if (clarify) clarifications.push(clarify)
        const dictionary = dictionaries.find(d => d.scope.companyCode === (turn.expect.companyCode || 'A') && d.scope.reportId === fixed.sales && d.scope.domain === 'REPORT')
        const dates = dateValues(actualDiagnostic?.referenceTime || new Date().toISOString(), dictionary?.definition || { timeZone: 'Asia/Shanghai', fiscalStartMonth: 1 })
        const expected = replaceDates(turn.expect, dates)
        if (expected.kind !== 'reject' && (!actualDiagnostic || actualDiagnostic.modelCalls < 3)) errors.push('MISSING_REAL_MODEL_STAGE_EVIDENCE')
        if (expected.kind === 'query') { if (!observed) errors.push('MISSING_QUERY'); else truth = scoreQuery(expected, observed, source, errors) }
        else if (expected.kind === 'clarify') {
          if (observed || actualDiagnostic?.outcome !== 'CLARIFY') errors.push('EXPECTED_CLARIFICATION')
          const expectedValues = expected.valuesFrom === 'authorizedCompanies' ? authorizedCatalog.companies : expected.values
          if (expected.optionKind && (!clarify || clarify.kind !== expected.optionKind || !clarificationChoicesMatch(expected.optionKind, expectedValues, clarify.choices.map(row => row.value), expected.domain || 'REPORT'))) errors.push('CLARIFICATION_OPTIONS')
        } else if (expected.kind === 'reject') { if (observed || actualDiagnostic || !last(events, 'error')) errors.push('STALE_INTERACTION_WAS_NOT_REJECTED_BEFORE_MODEL') }
        else throw new Error('Unknown corpus expectation')
      } catch (error) { errors.push(error.message?.match(/^[A-Z0-9_]+$/)?.[0] || error.constructor.name) }
      const category = classify(turn.expect, observed, actualDiagnostic, errors, transport)
      evidence.cases.push({ caseId: `${scenario.id}/${index + 1}`, clientKey, message: turn.message, interaction: turn.edit ? 'EDIT' : turn.choose ? 'CHOICE' : 'TEXT', expected: turn.expect,
        conversationId, passed: errors.length === 0, category, errors, durationMs: Date.now() - started, diagnostic: actualDiagnostic || null, truth: truth || null, events })
      save(); console.log(`${scenario.id}/${index + 1}: ${errors.length ? 'FAIL ' + errors.join(',') : 'PASS'} (${category})`)
    }
  }
  evidence.completedAt = new Date().toISOString(); save(); console.log(JSON.stringify(evidence.summary)); if (evidence.summary.failed) process.exitCode = 1
}
if (require.main === module) main().catch(error => { console.error(error.message?.match(/^[A-Za-z0-9_ .,-]+$/)?.[0] || error.constructor.name); process.exitCode = 1 })
module.exports = { scoreQuery, dateValues, replaceDates, satisfies, clarificationChoicesMatch }
