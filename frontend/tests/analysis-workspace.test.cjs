/** 分析和知识页面的真实组件方法回归；受控传输检验竞态、幂等与恢复，真实浏览器和模型另行验收。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { webcrypto } = require('node:crypto')
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
const importContext = { TextDecoder }
vm.runInNewContext(read('utils/knowledgeImport.js').replace('export async function', 'async function'), importContext)
const pendingContext = { TextEncoder }
vm.runInNewContext(read('utils/pendingRequestStore.js').replace(/export /g, '') + read('utils/analysisPending.js').replace(/^import .*$/gm, '').replace(/export /g, ''), pendingContext)
function harness(view, options = {}) {
  const requests = [], prompts = []
  const names = ['analysisCatalog', 'analysisRuns', 'analysisRun', 'submitAnalysis', 'cancelAnalysis', 'analysisRows', 'queryPage', 'knowledgeDocuments', 'knowledgeHistory', 'saveKnowledge', 'publishKnowledge', 'disableKnowledge', 'searchKnowledge', 'searchKnowledgeSemantic']
  let id = 0
  const sandbox = { component: null, KnowledgeResults: {}, readKnowledgeFile: importContext.readKnowledgeFile, crypto: { randomUUID: () => `key-${++id}` }, setTimeout: () => 1, clearTimeout() {}, Intl, Date }
  for (const name of names) sandbox[name] = (...args) => new Promise((resolve, reject) => requests.push({ name, args, resolve, reject }))
  const script = read(`views/${view}.vue`).match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace('export default', 'component =')
  vm.runInNewContext(script, sandbox)
  const component = sandbox.component, state = component.data()
  state._pendingStore = options.pendingStore || {
    async read() { return { pending: null, notice: '' } }, async checkSession() {}, async verify() {}, async clear() { return true },
    async create(request, key) { return { request, key, signature: 'unit-signature', createdAt: Date.now(), ownerKey: 'unit-owner' } }
  }
  Object.assign(state, { _alive: true, _seq: 0, _detailSeq: 0, _historySeq: 0, _searchSeq: 0, _prepareSeq: 0, _importSeq: 0, $refs: {}, $nextTick: callback => callback(), $route: { path: '/analysis', query: {} }, $prompt: (...args) => new Promise((resolve, reject) => prompts.push({ args, resolve, reject })) })
  state.$router = { replace: location => { state.$route = location; return Promise.resolve() }, push() {} }
  for (const [key, fn] of Object.entries(component.methods)) state[key] = fn.bind(state)
  for (const [key, fn] of Object.entries(component.computed)) Object.defineProperty(state, key, { get: () => fn.call(state) })
  return { state, component, requests, prompts }
}
const run = (id, status = 'COMPLETED', version = 1) => ({ id, status, version, steps: [], usage: [], request: { companyCode: 'A', reportId: 'rpt-sales-order', question: id, mode: 'CURRENT_DATA' } })
const tick = () => new Promise(resolve => setImmediate(resolve))
const form = state => { state.ready = true; state.catalog = { companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }; state.form = { companyCode: 'A', reportId: 'rpt-sales-order', question: '销售口径是什么', mode: 'CURRENT_DATA' } }

test('new analysis forms default to current data while a snapshot link selects snapshot-only mode', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); assert.equal(state.form.mode, 'CURRENT_DATA')
  state.$route.query.source = 'a'.repeat(32); state.form.question = '按产品汇总这些结果'
  const initialization = state.initialize(); requests[0].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await tick()
  requests[1].resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await tick(); requests[2].resolve([]); await initialization
  assert.equal(state.form.mode, 'SNAPSHOT_ONLY'); assert.equal(Boolean(state.canSubmit), true)
})

test('explicit current-data comparison retains the selected snapshot and knowledge mode explicitly removes it', () => {
  const { state } = harness('AnalysisWorkspace'); form(state); state.sourceId = 'a'.repeat(32); state.form.mode = 'CURRENT_DATA'; state.modeChanged()
  assert.equal(state.sourceId, 'a'.repeat(32)); assert.equal(state.$route.query.source, 'a'.repeat(32)); assert.equal(state.$route.query.mode, 'CURRENT_DATA')
  state.form.mode = 'KNOWLEDGE_ONLY'; state.modeChanged(); assert.equal(state.sourceId, null); assert.equal(state.$route.query.source, undefined); assert.equal(state.modeValid, true)
  state.form.mode = 'SNAPSHOT_ONLY'; assert.equal(state.modeValid, false); assert.equal(Boolean(state.canSubmit), false)
})

test('unknown submissions retain the original mode and snapshot despite late mode changes', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.form.mode = 'SNAPSHOT_ONLY'; state.sourceId = 'a'.repeat(32)
  const first = state.submit(); await tick(); requests[0].reject(new Error('lost response')); await first
  state.form.mode = 'KNOWLEDGE_ONLY'; state.modeChanged(); assert.equal(state.form.mode, 'SNAPSHOT_ONLY'); assert.equal(state.sourceId, 'a'.repeat(32))
  const recheck = state.submit(); await tick(); assert.equal(requests[1].args[0].mode, 'SNAPSHOT_ONLY'); assert.equal(requests[1].args[1], requests[0].args[1]); requests[1].resolve(run('done')); await recheck
})

test('terminal knowledge task preparation preserves its mode and does not read business snapshots', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('knowledge-task'); state.run.request.mode = 'KNOWLEDGE_ONLY'; state.run.request.snapshotId = null
  await state.prepareRun(); assert.equal(state.form.mode, 'KNOWLEDGE_ONLY'); assert.equal(state.sourceId, null); assert.equal(requests.length, 0)
})

test('invalid knowledge-with-snapshot links never issue a business snapshot read', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); state.$route.query = { source: 'a'.repeat(32), mode: 'KNOWLEDGE_ONLY' }
  const initialization = state.initialize(); requests[0].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await initialization
  assert.equal(requests.length, 1); assert.equal(Boolean(state.canSubmit), false); assert.match(state.error, /执行范围/)
})

test('late task response cannot replace a newly selected task', async () => {
  const { state, requests } = harness('AnalysisWorkspace')
  const a = state.openRun('a'), b = state.openRun('b')
  requests[1].resolve(run('b')); await b; requests[0].resolve(run('a')); await a
  assert.equal(state.run.id, 'b'); assert.equal(state.$route.query.run, 'b')
})
test('uncertain submit retries reuse the original idempotency key', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const first = state.submit(); await tick(); requests[0].reject(new Error('连接中断')); await first
  const second = state.submit(); await tick(); assert.equal(requests[1].args[1], requests[0].args[1]); requests[1].resolve(run('saved')); await second
  assert.equal(state.run.id, 'saved'); assert.equal(state.pending, null)
})
test('changing a question after a confirmed submission creates a new explicit request key', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const first = state.submit(); await tick(); requests[0].resolve(run('confirmed')); await first
  state.form.question = '新的完整问题'; state.questionChanged(); const second = state.submit(); await tick()
  const submitted = requests.filter(item => item.name === 'submitAnalysis').at(-1)
  assert.notEqual(submitted.args[1], requests[0].args[1]); submitted.reject(new Error('连接中断')); await second
})
test('refresh recovery reads the existing task without submitting it again', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); state.$route.query.run = 'old'
  const restore = state.initialize(); requests[0].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await tick()
  requests[1].resolve([]); await tick(); assert.equal(requests[2].name, 'analysisRun'); requests[2].resolve(run('old')); await restore
  assert.equal(requests.filter(r => r.name === 'submitAnalysis').length, 0); assert.equal(state.run.id, 'old')
})
test('a late running poll cannot overwrite a confirmed cancellation', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); state.run = run('a', 'RUNNING', 2)
  const poll = state.poll(), cancel = state.cancel(); requests[1].resolve(run('a', 'CANCELLED', 4)); await cancel
  requests[0].resolve(run('a', 'RUNNING', 3)); await poll; assert.equal(state.run.status, 'CANCELLED')
})
test('an unfinished step does not appear active after the task was cancelled', () => {
  const { state } = harness('AnalysisWorkspace'); state.run = run('a', 'CANCELLED', 4)
  assert.equal(state.stepText('RUNNING'), '未完成'); assert.equal(state.stepText('COMPLETED'), '已完成')
  assert.equal(state.stageText('FINISHED'), '')
})
test('changing tasks closes and invalidates an old detail page', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); state.run = run('a'); state.showRows(0)
  const next = state.openRun('b'); requests[1].resolve(run('b')); await next; requests[0].resolve({ rows: [{ amount: 'stale' }] }); await tick()
  assert.equal(state.rowsVisible, false); assert.equal(state.rowPage, null)
})
test('destroying a page drops in-flight result data', async () => {
  const { state, component, requests } = harness('AnalysisWorkspace'); const opening = state.openRun('a'); component.beforeDestroy.call(state)
  requests[0].resolve(run('a')); await opening; assert.equal(state.run, null)
})
test('selected snapshot must finish validation before submission is enabled', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); state.$route.query.source = 'source'; state.form.question = '统计'
  const init = state.initialize(); requests[0].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await tick()
  assert.equal(Boolean(state.canSubmit), false); requests[1].reject(new Error('快照已过期')); await init
  assert.equal(Boolean(state.canSubmit), false); assert.equal(state.sourceId, 'source'); state.clearSource(); assert.equal(Boolean(state.canSubmit), true)
})
test('knowledge scope changes discard a late previous list', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); state.company = 'A'; state.report = 'rpt-sales-order'; const a = state.load(); state.company = 'B'; const b = state.load()
  requests[1].resolve([{ id: 'B' }]); await b; requests[0].resolve([{ id: 'A' }]); await a; assert.equal(state.documents[0].id, 'B')
})
test('publication confirmation cannot apply after scope changes', async () => {
  const { state, requests, prompts } = harness('KnowledgeAdmin'); state.company = 'A'; state.report = 'rpt-sales-order'
  const publish = state.transition({ id: 'doc', row_version: 3, content: { title: '资料' } }, true)
  state.company = 'B'; state.scopeChanged(); prompts[0].resolve({ value: '明确发布' }); await publish
  assert.equal(requests.filter(r => r.name === 'publishKnowledge').length, 0)
})
test('draft save preserves editor content after a version conflict', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); state.company = 'A'; state.report = 'rpt-sales-order'
  state.editor = { id: 'doc', version: 3, reason: '修改', content: { body: '未保存正文' } }
  const save = state.save(); assert.equal(requests[0].args[1].expectedVersion, 3); requests[0].reject(new Error('版本冲突')); await save
  assert.equal(state.editor.content.body, '未保存正文'); assert.match(state.error, /版本冲突/)
})
test('closing knowledge history rejects the late original response', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); const history = state.openHistory({ id: 'doc' }); state.closeHistory(); requests[0].resolve([{ version: 1 }]); await history
  assert.equal(state.revisions.length, 0); assert.equal(state.historyVisible, false)
})
test('source quotations and model text are rendered as escaped text', () => {
  for (const file of ['views/AnalysisWorkspace.vue', 'views/KnowledgeAdmin.vue', 'components/KnowledgeResults.vue']) assert.doesNotMatch(read(file), /v-html|innerHTML/)
  assert.match(read('views/QueryWorkspace.vue'), /分析此结果/)
})

function searchForm(state) { state.catalog.admin = true; state.company = 'A'; state.report = 'rpt-sales-order'; state.searchQuestion = 'booked value' }

test('knowledge search sends only the selected scope and current question', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const searching = state.search()
  assert.equal(requests[0].name, 'searchKnowledge')
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0].args[0])), { companyCode: 'A', reportId: 'rpt-sales-order', question: 'booked value' })
  requests[0].resolve({ question: 'booked value', result: { sources: [] } }); await searching
  assert.equal(state.searchResult.question, 'booked value'); assert.equal(state.searching, false)
})

test('changing the question discards old hits without clearing the newer loading state', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const first = state.search(); state.searchQuestion = 'tax'; state.invalidateSearch(); const second = state.search()
  requests[0].resolve({ question: 'booked value' }); await first
  assert.equal(state.searchResult, null); assert.equal(state.searching, true)
  requests[1].resolve({ question: 'tax' }); await second; assert.equal(state.searchResult.question, 'tax')
})

test('knowledge search rejects an old scope even if its response arrives last', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const searching = state.search(); state.company = 'B'; state.scopeChanged()
  requests[0].resolve({ companyCode: 'A' }); await searching; assert.equal(state.searchResult, null)
  requests[1].resolve([]); await tick(); assert.equal(state.company, 'B')
})

test('refresh clears displayed search evidence and invalidates a running search', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state); state.searchResult = { old: true }
  const searching = state.search(), refresh = state.load()
  requests[0].resolve({ old: true }); await searching; assert.equal(state.searchResult, null)
  requests[1].resolve([]); await refresh; assert.equal(state.searching, false)
})

test('search failures keep the question available for an explicit retry', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const failed = state.search(); requests[0].reject(new Error('知识依据已变化')); await failed
  assert.equal(state.searchResult, null); assert.match(state.searchError, /已变化/); assert.equal(state.searchQuestion, 'booked value')
  const retry = state.search(); assert.equal(state.searchError, ''); requests[1].resolve({ current: true }); await retry
  assert.equal(state.searchResult.current, true)
})

test('publication invalidates search evidence before the mutation returns', async () => {
  const { state, requests, prompts } = harness('KnowledgeAdmin'); searchForm(state); state.searchResult = { old: true }
  const publish = state.transition({ id: 'doc', row_version: 2, content: { title: '资料' } }, true)
  prompts[0].resolve({ value: '明确发布' }); await tick()
  assert.equal(state.searchResult, null); assert.equal(state.busy, true)
  requests[0].reject(new Error('版本冲突')); await publish; assert.equal(state.searchResult, null)
})

test('empty scope clears the old list and cancels its late response', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const first = state.load(); state.company = ''; await state.load(); requests[0].resolve([{ company: 'A' }]); await first
  assert.equal(state.documents.length, 0); assert.equal(state.canSearch, false); assert.equal(state.loading, false)
})

test('destroying the knowledge page drops outstanding search content', async () => {
  const { state, component, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const searching = state.search(); component.beforeDestroy.call(state); requests[0].resolve({ stale: true }); await searching
  assert.equal(state.searchResult, null)
})

test('unhandled scope or question edits still cannot adopt mismatched search results', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const searching = state.search(); state.searchQuestion = 'new'; requests[0].resolve({ question: 'booked value' }); await searching
  assert.equal(state.searchResult, null)
})

test('non-administrators cannot initiate a local knowledge search', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state); state.catalog.admin = false; await state.search()
  assert.equal(requests.length, 0)
})

test('semantic search uses its explicit endpoint and keeps actual model usage', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const searching = state.search('SEMANTIC')
  assert.equal(requests[0].name, 'searchKnowledgeSemantic')
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0].args[0])), { companyCode: 'A', reportId: 'rpt-sales-order', question: 'booked value' })
  requests[0].resolve({ question: 'booked value', result: { sources: [] }, usage: [{ stage: 'RAG_REWRITE' }, { stage: 'RAG_RERANK' }] }); await searching
  assert.equal(state.searchResultMode, 'SEMANTIC'); assert.equal(state.searchResult.usage.length, 2)
})

test('switching search mode discards the old semantic response and duplicate clicks do not issue extra calls', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state)
  const semantic = state.search('SEMANTIC'); await state.search('SEMANTIC'); assert.equal(requests.length, 1)
  const lexical = state.search('LEXICAL'); assert.equal(requests[1].name, 'searchKnowledge')
  requests[0].resolve({ stale: true }); await semantic; assert.equal(state.searchResult, null); assert.equal(state.searching, true)
  requests[1].resolve({ current: true }); await lexical; assert.equal(state.searchResult.current, true); assert.equal(state.searchResultMode, 'LEXICAL')
})

test('a failed semantic attempt clears the previous success and never silently calls lexical search', async () => {
  const { state, requests } = harness('KnowledgeAdmin'); searchForm(state); state.searchResult = { previous: true }; state.searchResultMode = 'LEXICAL'
  const searching = state.search('SEMANTIC'); assert.equal(state.searchResult, null)
  requests[0].reject(new Error('真实模型不可用')); await searching
  assert.equal(state.searchResult, null); assert.equal(state.searchResultMode, null); assert.match(state.searchError, /真实模型不可用/)
  assert.equal(state.searchQuestion, 'booked value'); assert.deepEqual(requests.map(item => item.name), ['searchKnowledgeSemantic'])
})

test('semantic responses lose display authority after an edit, scope change, refresh or page destruction', async () => {
  for (const change of ['question', 'scope', 'refresh', 'destroy']) {
    const { state, component, requests } = harness('KnowledgeAdmin'); searchForm(state)
    const searching = state.search('SEMANTIC')
    if (change === 'question') { state.searchQuestion = 'another'; state.invalidateSearch() }
    if (change === 'scope') { state.company = 'B'; state.scopeChanged() }
    if (change === 'refresh') state.load()
    if (change === 'destroy') component.beforeDestroy.call(state)
    requests[0].resolve({ stale: true }); await searching
    assert.equal(state.searchResult, null, change)
  }
})

test('terminal task preparation fills the original request without submitting or reusing an unrelated snapshot', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const original = run('old', 'CLARIFY'); state.run = original; state.sourceId = 'unrelated'; state.$route.query = { run: 'old', source: 'unrelated' }
  await state.prepareRun()
  assert.equal(state.run, original); assert.equal(state.form.question, 'old'); assert.equal(state.sourceId, null); assert.equal(state.$route.query.source, undefined)
  assert.equal(state.prepareLabel, '补充问题'); assert.equal(requests.length, 0); assert.equal(state.canSubmit, true)
  assert.match(state.preparationMessage, /点击.*开始分析/)
})

test('prepared rerun requires a later explicit submit with a new request key and leaves the original task intact', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const original = run('old'); original.request = { ...state.form, snapshotId: null }
  const first = state.submit(); await tick(); requests[0].resolve(original); await first
  const previousKey = requests[0].args[1], beforePreparation = requests.length
  await state.prepareRun(); assert.equal(requests.length, beforePreparation)
  state.form.question += '；请补充适用范围'; state.questionChanged()
  const next = state.submit(); await tick(); const submitted = requests.filter(item => item.name === 'submitAnalysis').at(-1)
  assert.notEqual(submitted.args[1], previousKey); assert.equal(submitted.args[0].snapshotId, null)
  assert.equal(original.request.question, '销售口径是什么'); assert.equal(original.id, 'old')
  submitted.reject(new Error('提交结果未知')); await next
  const retry = state.submit(); await tick(); const retried = requests.filter(item => item.name === 'submitAnalysis').at(-1)
  assert.equal(retried.args[1], submitted.args[1]); retried.resolve(run('new')); await retry
})

test('uncertain submission B prevents preparing terminal task A and keeps the original request for reconciliation', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  state.run = run('terminal-A'); state.run.request.snapshotId = 'snapshot-A'
  state.form.question = '问题B'; state.sourceId = 'snapshot-B'
  const unknown = state.submit(); await tick(); requests[0].reject(new Error('提交结果未知')); await unknown
  const pending = state.pending
  const preparing = state.prepareRun()
  requests.find(item => item.name === 'queryPage')?.resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } })
  await preparing
  assert.equal(state.pending, pending); assert.equal(state.form.question, '问题B'); assert.equal(state.sourceId, 'snapshot-B')
  assert.equal(state.formLocked, true); assert.equal(state.submitLabel, '核对本次提交')
  assert.equal(requests.length, 1); assert.equal(state.run.id, 'terminal-A')
  const checking = state.submit(); await tick()
  assert.equal(requests[1].args[1], pending.key); assert.equal(requests[1].args[0], pending.request)
  requests[1].resolve(run('confirmed-B')); await checking
  assert.equal(state.pending, null); assert.equal(state.formLocked, false)
})

test('uncertain submission cannot lose its key through question, scope or snapshot edits', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.sourceId = 'original-snapshot'
  const unknown = state.submit(); await tick(); requests[0].reject(new Error('提交结果未知')); await unknown
  const pending = state.pending
  state.form.question = '另一问题'; state.questionChanged()
  assert.equal(state.form.question, pending.request.question); assert.equal(state.pending, pending)
  state.form.companyCode = 'B'; state.form.reportId = 'rpt-expense'; state.scopeChanged()
  assert.equal(state.form.companyCode, pending.request.companyCode); assert.equal(state.form.reportId, pending.request.reportId)
  state.clearSource(); assert.equal(state.sourceId, 'original-snapshot'); assert.equal(state.pending, pending)
  assert.equal(requests.length, 1)
})

test('reconciliation sends the pending payload even when unrelated local form state no longer matches it', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.sourceId = 'original-snapshot'
  const unknown = state.submit(); await tick(); requests[0].reject(new Error('提交结果未知')); await unknown
  const pending = state.pending
  state.form = { companyCode: 'B', reportId: 'rpt-expense', question: '被迟到事件改变的表单', mode: 'CURRENT_DATA' }; state.sourceId = 'unrelated-snapshot'; state.ready = false; state.sourceReady = false
  const checking = state.submit(); await tick()
  assert.equal(requests.length, 2); assert.equal(requests[1].args[1], pending.key); assert.equal(requests[1].args[0], pending.request)
  assert.equal(state.form.question, pending.request.question); assert.equal(state.sourceId, 'original-snapshot')
  requests[1].resolve(run('confirmed')); await checking; assert.equal(state.pending, null)
})

test('explicit server 4xx rejections unlock editing without automatically resubmitting a new task', async () => {
  for (const code of [403, 404, 409, 410, 422, 429]) {
    const { state, requests } = harness('AnalysisWorkspace'); form(state)
    const rejected = state.submit(); await tick(); const originalKey = requests[0].args[1]
    requests[0].reject(Object.assign(new Error('服务器拒绝输入'), { code, serverResponse: true })); await rejected
    assert.equal(state.pending, null, String(code)); assert.equal(state.formLocked, false)
    assert.match(state.error, /服务器已拒绝本次提交，请核对后修改/); assert.equal(requests.length, 1)
    state.form.question = '修改后的问题'; state.questionChanged()
    const next = state.submit(); await tick(); assert.notEqual(requests[1].args[1], originalKey)
    requests[1].resolve(run('new')); await next
  }
})

test('503 and transport failures retain the exact pending request including an untrusted numeric transport code', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  let pending
  for (const error of [Object.assign(new Error('服务暂不可用'), { code: 503, serverResponse: true }), new Error('连接中断'), Object.assign(new Error('无结构化拒绝正文'), { code: 422 })]) {
    const checking = state.submit(); await tick(); const request = requests.filter(item => item.name === 'submitAnalysis').at(-1)
    pending ||= state.pending
    assert.equal(request.args[1], pending.key); assert.equal(request.args[0], pending.request)
    request.reject(error); await checking
    assert.equal(state.pending, pending); assert.equal(state.formLocked, true); assert.equal(state.submitLabel, '核对本次提交')
  }
})

test('cancelling a displayed prior task cannot invalidate an in-flight submission or its pending request', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('prior-A', 'RUNNING')
  const submitting = state.submit(), cancelling = state.cancel()
  await tick()
  requests.find(item => item.name === 'cancelAnalysis')?.resolve(run('prior-A', 'CANCELLED'))
  await cancelling
  requests[0].resolve(run('submitted-B')); await submitting
  assert.equal(requests.filter(item => item.name === 'cancelAnalysis').length, 0)
  assert.equal(state.submitting, false); assert.equal(state.pending, null); assert.equal(state.run.id, 'submitted-B')
})

test('preparing an explicit snapshot blocks submission until its scope has been revalidated', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('snapshot-task'); state.run.request.snapshotId = 'selected'
  const preparing = state.prepareRun(); assert.equal(state.canSubmit, false); assert.equal(requests[0].name, 'queryPage'); assert.deepEqual(requests[0].args, ['selected', 1])
  await state.submit(); assert.equal(requests.length, 1)
  requests[0].resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await preparing
  assert.equal(state.sourceId, 'selected'); assert.equal(state.sourceReady, true); assert.equal(state.canSubmit, true); assert.equal(state.$route.query.source, 'selected')
})

test('expired or mismatched original snapshots stay explicit and cannot silently switch the task to current data', async () => {
  for (const failure of ['expired', 'mismatch']) {
    const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('snapshot-task'); state.run.request.snapshotId = 'selected'
    const preparing = state.prepareRun()
    if (failure === 'expired') requests[0].reject(new Error('快照已过期'))
    else requests[0].resolve({ query: { companyCode: 'B', reportIds: ['rpt-sales-order'] } })
    await preparing
    assert.equal(state.form.question, 'snapshot-task'); assert.equal(state.sourceId, 'selected'); assert.equal(state.sourceReady, false); assert.equal(state.canSubmit, false)
    state.clearSource(); assert.equal(state.sourceId, null); assert.equal(state.canSubmit, true)
    assert.equal(requests.filter(item => item.name === 'submitAnalysis').length, 0)
  }
})

test('failed historical snapshot S1 replaces URL snapshot S2 so refresh cannot restore S2', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('historical'); state.run.request.snapshotId = 'S1'
  state.sourceId = 'S2'; state.$route.query = { run: 'historical', source: 'S2' }
  const preparing = state.prepareRun(); requests[0].reject(new Error('S1快照已过期')); await preparing
  assert.equal(state.sourceId, 'S1'); assert.equal(state.sourceReady, false); assert.equal(state.$route.query.source, 'S1'); assert.equal(state.canSubmit, false)
  const refreshed = harness('AnalysisWorkspace'); refreshed.state.$route.query = { ...state.$route.query }
  const restore = refreshed.state.initialize()
  refreshed.requests[0].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await tick()
  assert.equal(refreshed.requests[1].name, 'queryPage'); assert.deepEqual(refreshed.requests[1].args, ['S1', 1])
  refreshed.requests[1].reject(new Error('S1快照已过期')); await restore
  assert.equal(refreshed.state.sourceId, 'S1'); assert.equal(refreshed.state.sourceReady, false); assert.equal(refreshed.state.canSubmit, false)
})

test('a pre-cancellation poll failure cannot erase the confirmed terminal task or freeze its snapshot preparation', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('original', 'RUNNING', 2); state.run.request.snapshotId = 'selected'
  const poll = state.poll(), cancelling = state.cancel()
  const cancelled = run('original', 'CANCELLED', 4); cancelled.request.snapshotId = 'selected'
  requests[1].resolve(cancelled); await cancelling
  const preparing = state.prepareRun(), snapshot = requests.find(item => item.name === 'queryPage')
  requests[0].reject(new Error('取消前的旧轮询失败')); await poll
  snapshot.resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await preparing
  assert.equal(state.run?.status, 'CANCELLED'); assert.equal(state.run?.id, 'original'); assert.equal(state.preparing, false)
  assert.equal(state.sourceId, 'selected'); assert.equal(state.canSubmit, true); assert.equal(state.error, '')
})

test('snapshot preparation releases its loading state when its owned task disappears', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('original'); state.run.request.snapshotId = 'selected'
  const preparing = state.prepareRun(); state.run = null
  requests[0].resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await preparing
  assert.equal(state.preparing, false); assert.equal(state.form.question, '销售口径是什么'); assert.equal(state.sourceId, null)
})

test('changing tasks or destroying the page discards late preparation without replacing the current form', async () => {
  for (const change of ['task', 'destroy']) {
    const { state, component, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('old'); state.run.request.snapshotId = 'selected'
    const preparing = state.prepareRun()
    if (change === 'task') { const opening = state.openRun('new'); requests[1].resolve(run('new')); await opening }
    else component.beforeDestroy.call(state)
    requests[0].resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await preparing
    assert.equal(state.form.question, '销售口径是什么'); assert.equal(state.sourceId, null); assert.equal(state.preparationMessage, '')
  }
})

test('active tasks cannot prepare a second run and removed scopes require an explicit new selection', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state); state.run = run('active', 'RUNNING')
  await state.prepareRun(); assert.equal(requests.length, 0); assert.equal(state.form.question, '销售口径是什么')
  state.run = run('outside'); state.run.request.companyCode = 'B'; await state.prepareRun()
  assert.equal(state.canSubmit, false); assert.match(state.error, /可选目录/)
  state.form.companyCode = 'A'; state.scopeChanged(); assert.equal(state.canSubmit, true)
})

const importedFile = (content, name = '制度.md') => {
  const bytes = Buffer.from(content, 'utf8')
  return { name, size: bytes.length, arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) }
}
function importState() {
  const result = harness('KnowledgeAdmin'); searchForm(result.state); result.state.newDocument(); return result
}
const importEvent = file => ({ target: { files: [file], value: 'selected' } })

test('local text import only replaces the draft body and leaves title, source, saving and publishing to the administrator', async () => {
  const { state, requests } = importState(); const event = importEvent(importedFile('# 口径\n<script>test()</script>'))
  await state.importFile(event)
  assert.equal(state.editor.content.body, '# 口径\n<script>test()</script>'); assert.equal(state.editor.content.title, ''); assert.equal(state.editor.content.sourceName, '')
  assert.equal(requests.length, 0); assert.equal(event.target.value, ''); assert.match(state.importNotice, /核对实际来源/)
})

test('failed text import preserves the unsaved body and reports the budget without truncation', async () => {
  const { state, requests } = importState(); state.editor.content.body = '原有未保存正文'
  await state.importFile(importEvent(importedFile('文'.repeat(12001))))
  assert.equal(state.editor.content.body, '原有未保存正文'); assert.match(state.importError, /12,000/); assert.equal(state.importNotice, ''); assert.equal(requests.length, 0)
})

test('NUL 文件导入保留原正文并提示字符问题，不写入草稿或发布资料', async () => {
  const { state, requests } = importState(); state.editor.content.body = '原有未保存正文'
  await state.importFile(importEvent(importedFile('订单\u0000金额')))
  assert.equal(state.editor.content.body, '原有未保存正文'); assert.match(state.importError, /NUL/)
  assert.equal(state.importNotice, ''); assert.equal(state.importing, false); assert.equal(requests.length, 0)
})

test('text import does not start a file read after local administrator access is removed', async () => {
  const { state } = importState(); state.catalog.admin = false; let reads = 0
  await state.importFile(importEvent({ name: 'data.md', size: 1, arrayBuffer: async () => { reads++; return new ArrayBuffer(1) } }))
  assert.equal(reads, 0); assert.equal(state.editor.content.body, ''); assert.equal(state.importNotice, '')
})

test('file reads cannot overwrite a changed body, closed editor, new document, new scope or destroyed page', async () => {
  for (const change of ['body', 'close', 'new', 'scope', 'destroy']) {
    const { state, component, requests } = importState(); const original = state.editor; original.content.body = '原文'
    let finish; const file = importedFile('新文'); const originalRead = file.arrayBuffer; file.arrayBuffer = () => new Promise(resolve => { finish = resolve })
    const importing = state.importFile(importEvent(file)); await state.save(); assert.equal(requests.length, 0)
    if (change === 'body') { state.editor.content.body = '用户继续编辑'; state.invalidateImport() }
    if (change === 'close') state.closeEditor()
    if (change === 'new') state.newDocument()
    if (change === 'scope') { state.company = 'B'; state.scopeChanged() }
    if (change === 'destroy') component.beforeDestroy.call(state)
    finish(await originalRead()); await importing
    assert.equal(original.content.body, change === 'body' ? '用户继续编辑' : '原文', change); assert.equal(state.importNotice, '', change)
    assert.equal(requests.filter(item => ['saveKnowledge', 'publishKnowledge'].includes(item.name)).length, 0)
  }
})

test('a replaced file selection keeps its own loading state and ignores the first read failure', async () => {
  const { state } = importState(); let rejectFirst, finishSecond
  const first = importedFile('甲'); first.arrayBuffer = () => new Promise((_, reject) => { rejectFirst = reject })
  const second = importedFile('乙'); const readSecond = second.arrayBuffer; second.arrayBuffer = () => new Promise(resolve => { finishSecond = resolve })
  const a = state.importFile(importEvent(first)), b = state.importFile(importEvent(second))
  rejectFirst(new Error('旧文件错误')); await a; assert.equal(state.importing, true); assert.equal(state.importError, '')
  finishSecond(await readSecond()); await b; assert.equal(state.editor.content.body, '乙'); assert.equal(state.importing, false)
})

const savedSnapshot = '1'.repeat(32), differentSnapshot = '2'.repeat(32)
function pendingEnvironment() {
  const values = new Map(), env = { identity: { userId: 'reader', token: 'session-a' }, now: 1800000000000 }
  const storage = { get length() { return values.size }, key: index => [...values.keys()][index] ?? null, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) }
  return { env, values, store: () => pendingContext.createAnalysisPendingStore({ getStorage: () => storage, getIdentity: () => env.identity, cryptoApi: webcrypto, now: () => env.now }) }
}
async function waitRequest(requests, name) {
  const end = Date.now() + 2000
  while (Date.now() < end) { const request = requests.find(item => item.name === name); if (request) return request; await tick() }
  throw new Error('未发出预期的受控读取：' + name)
}
async function recoverPending(environment, options = {}) {
  const view = harness('AnalysisWorkspace', { pendingStore: environment.store() })
  view.state.$route.query = options.query || {}
  const initializing = view.state.initialize()
  view.requests[0].resolve({ companies: options.companies || ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false })
  if (options.snapshot) {
    const snapshot = await waitRequest(view.requests, 'queryPage')
    assert.equal(snapshot.args[0], options.snapshot)
    assert.equal(view.state.form.question, ''); assert.equal(view.state.canSubmit, false)
    if (options.snapshotError) snapshot.reject(new Error(options.snapshotError))
    else snapshot.resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } })
  }
  const history = await waitRequest(view.requests, 'analysisRuns'); history.resolve([])
  await initializing
  return view
}

test('refresh restores the saved request and key without a submission until an explicit reconciliation click', async () => {
  const environment = pendingEnvironment(), first = harness('AnalysisWorkspace', { pendingStore: environment.store() }); form(first.state)
  const submitting = first.state.submit(), wire = await waitRequest(first.requests, 'submitAnalysis')
  const saved = first.state.pending
  assert.equal(environment.values.size, 1)
  first.component.beforeDestroy.call(first.state); wire.reject(new Error('页面离开时响应未知')); await submitting
  const restored = await recoverPending(environment)
  assert.equal(restored.state.pending.key, saved.key); assert.equal(restored.state.form.question, saved.request.question)
  assert.match(restored.state.pendingNotice, /尚未重新提交/); assert.equal(restored.requests.filter(item => item.name === 'submitAnalysis').length, 0)
  const checking = restored.state.submit(), retry = await waitRequest(restored.requests, 'submitAnalysis')
  assert.equal(retry.args[1], saved.key); assert.deepEqual(JSON.parse(JSON.stringify(retry.args[0])), JSON.parse(JSON.stringify(saved.request)))
  retry.resolve(run('confirmed')); await checking
  assert.equal(restored.state.pending, null); assert.equal(environment.values.size, 0)
})

test('network and 503 failures keep the session record through leaving and returning to the workspace', async () => {
  for (const error of [new Error('网络中断'), Object.assign(new Error('暂时不可用'), { code: 503, serverResponse: true })]) {
    const environment = pendingEnvironment(), first = harness('AnalysisWorkspace', { pendingStore: environment.store() }); form(first.state)
    const submitting = first.state.submit(), wire = await waitRequest(first.requests, 'submitAnalysis')
    wire.reject(error); await submitting
    const key = first.state.pending.key; first.component.beforeDestroy.call(first.state)
    const restored = await recoverPending(environment)
    assert.equal(restored.state.pending.key, key); assert.equal(environment.values.size, 1)
    assert.equal(restored.requests.filter(item => item.name === 'submitAnalysis').length, 0)
  }
})

test('success and explicit rejection arriving after the page leaves still clear only their saved record', async () => {
  for (const rejected of [false, true]) {
    const environment = pendingEnvironment(), first = harness('AnalysisWorkspace', { pendingStore: environment.store() }); form(first.state)
    const submitting = first.state.submit(), wire = await waitRequest(first.requests, 'submitAnalysis')
    first.component.beforeDestroy.call(first.state)
    if (rejected) wire.reject(Object.assign(new Error('输入被拒绝'), { code: 422, serverResponse: true }))
    else wire.resolve(run('completed'))
    await submitting; assert.equal(environment.values.size, 0)
  }
})

test('saved snapshot replaces an unrelated URL source and is checked before the question is restored', async () => {
  for (const snapshotError of [null, '原快照已过期']) {
    const environment = pendingEnvironment()
    const saved = await environment.store().create({ companyCode: 'A', reportId: 'rpt-sales-order', question: '仅核对原快照', mode: 'CURRENT_DATA', snapshotId: savedSnapshot }, 'analysis-snapshot')
    const restored = await recoverPending(environment, { snapshot: savedSnapshot, snapshotError, query: { source: differentSnapshot } })
    assert.equal(restored.state.pending.key, saved.key); assert.equal(restored.state.form.question, '仅核对原快照')
    assert.equal(restored.state.sourceId, savedSnapshot); assert.equal(restored.state.$route.query.source, savedSnapshot)
    assert.equal(restored.state.sourceReady, !snapshotError); assert.equal(restored.requests.filter(item => item.name === 'submitAnalysis').length, 0)
  }
})

test('restoration checks the current catalog and does not read a saved snapshot outside that scope', async () => {
  const environment = pendingEnvironment()
  await environment.store().create({ companyCode: 'A', reportId: 'rpt-sales-order', question: '本人待核对问题', mode: 'CURRENT_DATA', snapshotId: savedSnapshot }, 'analysis-scope')
  const restored = await recoverPending(environment, { companies: ['B'] })
  assert.match(restored.state.error, /当前目录/); assert.equal(restored.state.sourceReady, false)
  assert.equal(restored.requests.filter(item => ['queryPage', 'submitAnalysis'].includes(item.name)).length, 0)
})

test('another user or login token never restores the previous owner question', async () => {
  for (const next of [{ userId: 'another', token: 'session-a' }, { userId: 'reader', token: 'session-b' }]) {
    const environment = pendingEnvironment()
    await environment.store().create({ companyCode: 'A', reportId: 'rpt-sales-order', question: '旧登录的私有问题', mode: 'CURRENT_DATA', snapshotId: null }, 'analysis-owner')
    environment.env.identity = next
    const restored = await recoverPending(environment)
    assert.equal(restored.state.pending, null); assert.equal(restored.state.form.question, ''); assert.match(restored.state.pendingNotice, /其他登录会话/)
    assert.equal(environment.values.size, 0); assert.equal(restored.requests.filter(item => item.name === 'submitAnalysis').length, 0)
  }
})

test('expired or corrupted records are explicitly discarded without carrying their question into a new task', async () => {
  for (const invalid of ['expired', 'corrupt']) {
    const environment = pendingEnvironment(), saved = await environment.store().create({ companyCode: 'A', reportId: 'rpt-sales-order', question: '失效问题', mode: 'CURRENT_DATA', snapshotId: null }, 'analysis-expired')
    if (invalid === 'expired') environment.env.now += 24 * 60 * 60 * 1000
    else environment.values.set(saved.ownerKey, '{broken')
    const restored = await recoverPending(environment)
    assert.equal(restored.state.pending, null); assert.equal(restored.state.form.question, '')
    assert.match(restored.state.pendingNotice, /已丢弃/); assert.equal(environment.values.size, 0)
    assert.equal(restored.requests.filter(item => item.name === 'submitAnalysis').length, 0)
  }
})

test('session changes before reconciliation or before a response is adopted cannot submit or display old-owner content', async () => {
  for (const stage of ['before-submit', 'after-submit']) {
    const environment = pendingEnvironment(), view = harness('AnalysisWorkspace', { pendingStore: environment.store() }); form(view.state)
    const first = view.state.submit(), wire = await waitRequest(view.requests, 'submitAnalysis')
    if (stage === 'before-submit') { wire.reject(new Error('未知')); await first; environment.env.identity.token = 'new-session'; await view.state.submit() }
    else { environment.env.identity.token = 'new-session'; wire.resolve(run('old-owner-result')); await first }
    assert.equal(view.requests.filter(item => item.name === 'submitAnalysis').length, 1)
    assert.equal(view.state.pending, null); assert.equal(view.state.form.question, ''); assert.equal(view.state.run, null)
    assert.match(view.state.error, /登录会话/)
  }
})

test('unavailable session storage stops before network submission and reports that saving was not confirmed', async () => {
  const store = pendingContext.createAnalysisPendingStore({ getStorage: () => { throw new Error('disabled') }, getIdentity: () => ({ userId: 'reader', token: 'session-a' }), cryptoApi: webcrypto })
  const view = harness('AnalysisWorkspace', { pendingStore: store }); form(view.state)
  await view.state.submit()
  assert.equal(view.requests.length, 0); assert.equal(view.state.pending, null); assert.match(view.state.pendingStorageError, /存储不可用/)
  assert.match(view.state.error, /尚未发送/)
})

test('rechecking recovery invalidates older task and history reads and releases their loading states', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const opening = state.openRun('old'), history = state.loadHistory()
  const restoring = state.initialize()
  assert.equal(state.loading, false); assert.equal(state.historyBusy, false); assert.equal(state.restoring, true)
  requests[0].resolve(run('old')); requests[1].resolve([run('old')]); await Promise.all([opening, history])
  assert.equal(state.run, null); assert.equal(state.history.length, 0)
  requests[2].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await tick()
  requests[3].resolve([]); await restoring
  assert.equal(state.loading, false); assert.equal(state.historyBusy, false); assert.equal(state.restoring, false); assert.equal(state.canSubmit, true)
  assert.equal(state.$route.query.run, undefined)
})

test('rechecking recovery cancels a pending history preparation before it can overwrite the recovered form', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  state.run = { ...run('old-question'), request: { ...run('old-question').request, snapshotId: 'old-snapshot' } }
  const preparing = state.prepareRun(), restoring = state.initialize()
  assert.equal(state.preparing, false)
  requests[0].resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await preparing
  requests[1].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: false }); await tick()
  requests[2].resolve([]); await restoring
  assert.equal(state.form.question, '销售口径是什么'); assert.equal(state.sourceId, null); assert.equal(state.preparationMessage, '')
  assert.equal(state.$route.query.run, undefined); assert.equal(state.preparing, false); assert.equal(state.restoring, false)
})

test('identity changes while a saved snapshot is being checked clear old content and invalidate outstanding history reads', async () => {
  const environment = pendingEnvironment()
  await environment.store().create({ companyCode: 'A', reportId: 'rpt-sales-order', question: '旧身份的待核对问题', mode: 'CURRENT_DATA', snapshotId: savedSnapshot }, 'analysis-old-identity')
  const { state, requests } = harness('AnalysisWorkspace', { pendingStore: environment.store() })
  const restoring = state.initialize()
  requests[0].resolve({ companies: ['A'], reports: [{ reportId: 'rpt-sales-order' }], admin: true })
  const snapshot = await waitRequest(requests, 'queryPage'), history = state.loadHistory()
  environment.env.identity.token = 'new-session'
  snapshot.resolve({ query: { companyCode: 'A', reportIds: ['rpt-sales-order'] } }); await restoring
  requests.find(item => item.name === 'analysisRuns').resolve([run('private-old-history')]); await history
  assert.equal(state.pending, null); assert.equal(state.form.question, ''); assert.equal(state.history.length, 0); assert.equal(state.run, null)
  assert.equal(state.catalog.companies.length, 0); assert.equal(state.catalog.reports.length, 0); assert.equal(state.catalog.admin, false)
  assert.equal(state.restoring, false); assert.equal(state.historyBusy, false); assert.equal(state.ready, false)
  assert.match(state.error, /登录会话/); assert.equal(requests.filter(item => item.name === 'submitAnalysis').length, 0)
})
