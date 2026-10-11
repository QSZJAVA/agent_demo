/** 分析和知识页面的真实组件方法回归；受控传输检验竞态、幂等与恢复，真实浏览器和模型另行验收。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
function harness(view) {
  const requests = [], prompts = []
  const names = ['analysisCatalog', 'analysisRuns', 'analysisRun', 'submitAnalysis', 'cancelAnalysis', 'analysisRows', 'queryPage', 'knowledgeDocuments', 'knowledgeHistory', 'saveKnowledge', 'publishKnowledge', 'disableKnowledge']
  let id = 0
  const sandbox = { component: null, crypto: { randomUUID: () => `key-${++id}` }, setTimeout: () => 1, clearTimeout() {}, Intl, Date }
  for (const name of names) sandbox[name] = (...args) => new Promise((resolve, reject) => requests.push({ name, args, resolve, reject }))
  const script = read(`views/${view}.vue`).match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace('export default', 'component =')
  vm.runInNewContext(script, sandbox)
  const component = sandbox.component, state = component.data()
  Object.assign(state, { _alive: true, _seq: 0, _detailSeq: 0, _historySeq: 0, $route: { path: '/analysis', query: {} }, $prompt: (...args) => new Promise((resolve, reject) => prompts.push({ args, resolve, reject })) })
  state.$router = { replace: location => { state.$route = location; return Promise.resolve() }, push() {} }
  for (const [key, fn] of Object.entries(component.methods)) state[key] = fn.bind(state)
  for (const [key, fn] of Object.entries(component.computed)) Object.defineProperty(state, key, { get: () => fn.call(state) })
  return { state, component, requests, prompts }
}
const run = (id, status = 'COMPLETED', version = 1) => ({ id, status, version, steps: [], usage: [], request: { companyCode: 'A', reportId: 'rpt-sales-order', question: id } })
const tick = () => new Promise(resolve => setImmediate(resolve))
const form = state => { state.ready = true; state.form = { companyCode: 'A', reportId: 'rpt-sales-order', question: '销售口径是什么' } }

test('late task response cannot replace a newly selected task', async () => {
  const { state, requests } = harness('AnalysisWorkspace')
  const a = state.openRun('a'), b = state.openRun('b')
  requests[1].resolve(run('b')); await b; requests[0].resolve(run('a')); await a
  assert.equal(state.run.id, 'b'); assert.equal(state.$route.query.run, 'b')
})
test('uncertain submit retries reuse the original idempotency key', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const first = state.submit(); requests[0].reject(new Error('连接中断')); await first
  const second = state.submit(); assert.equal(requests[1].args[1], requests[0].args[1]); requests[1].resolve(run('saved')); await second
  assert.equal(state.run.id, 'saved'); assert.equal(state.pending, null)
})
test('changing a question creates a new explicit request key', async () => {
  const { state, requests } = harness('AnalysisWorkspace'); form(state)
  const first = state.submit(); requests[0].reject(new Error('连接中断')); await first
  state.form.question = '新的完整问题'; state.questionChanged(); const second = state.submit()
  assert.notEqual(requests[1].args[1], requests[0].args[1]); requests[1].reject(new Error('连接中断')); await second
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
  for (const file of ['views/AnalysisWorkspace.vue', 'views/KnowledgeAdmin.vue']) assert.doesNotMatch(read(file), /v-html|innerHTML/)
  assert.match(read('views/QueryWorkspace.vue'), /分析此结果/)
})
