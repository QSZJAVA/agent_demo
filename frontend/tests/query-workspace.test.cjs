/** 查询工作区真实组件逻辑和接口恢复边界；模拟传输只验证前端状态，真实HTTP与浏览器另行验收。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
const strip = source => source.replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
const payload = (id, page = 1) => ({ query: { domain: 'REPORT', page, size: 20 }, snapshot: { id }, columns: [{ name: 'amount', type: 'decimal' }, { name: 'date', type: 'date' }], total: 40, rows: [] })
test('self-managed query routes retain page state while identity and business refresh recreate it', () => {
  const sandbox = { result: null, AgentChat: {} }
  vm.runInNewContext(strip(read('App.vue').match(/<script>([\s\S]*?)<\/script>/)[1]), sandbox)
  const context = { $route: { path: '/query-workspace', fullPath: '/query-workspace?run=a&page=1', meta: { ownsQueryState: true } }, currentUserId: 'u1', refreshSeq: 0 }
  const key = () => sandbox.result.computed.routeKey.call(context), first = key()
  context.$route.fullPath = '/query-workspace?run=a&page=2'; assert.equal(key(), first)
  context.currentUserId = 'u2'; assert.notEqual(key(), first)
  context.currentUserId = 'u1'; context.refreshSeq++; assert.notEqual(key(), first)
  context.$route.meta = {}; assert.match(key(), /page=2/)
})
function harness() {
  const requests = [], identity = { user: 'u1', token: 's1' }
  const api = new Proxy({}, { get: (_, name) => (...args) => new Promise((resolve, reject) => requests.push({ name, args, resolve, reject })) })
  const sandbox = { result: null, api, BusinessQueryCard: {}, PlanCard: {}, getCurrentUserId: () => identity.user, getSessionToken: () => identity.token, http: {}, runDispatchJob() {} }
  vm.runInNewContext(strip(read('views/QueryWorkspace.vue').match(/<script>([\s\S]*?)<\/script>/)[1]), sandbox)
  const component = sandbox.result, state = component.data(), routes = []
  state.$route = { path: '/query-workspace', query: {} }
  state.$router = { replace: location => { routes.push(location); state.$route = location; return Promise.resolve() } }
  for (const [name, method] of Object.entries(component.methods)) state[name] = method.bind(state)
  for (const [name, getter] of Object.entries(component.computed)) Object.defineProperty(state, name, { get: () => getter.call(state) })
  return { state, component, requests, identity, routes }
}

test('opening and paging persist the requested position, including a failed page', async () => {
  const { state, requests, routes } = harness()
  const opened = state.openRun('latest'); requests[0].resolve(payload('latest')); await opened
  assert.equal(state.$route.query.run, 'latest'); assert.equal(state.$route.query.page, '1')
  const paging = state.loadPage(2); requests[1].reject(new Error('连接中断')); await paging
  assert.equal(state.$route.query.page, '2'); assert.equal(state.selectedPage, 2)
  const { state: restored, requests: restoredRequests } = harness()
  restored.$route = routes.at(-1); const restore = restored.restoreRoute(restored.$route.query)
  assert.equal(restoredRequests[0].name, 'queryPage'); assert.equal(restoredRequests[0].args[1], 2)
  restoredRequests[0].resolve(payload('latest', 2)); await restore
  assert.equal(restored.result.query.page, 2)
})

test('route changes within the same snapshot load the requested page once and clear selection without a run', async () => {
  const { state, component, requests } = harness()
  const opened = state.openRun('one'); requests[0].resolve(payload('one')); await opened
  const route = { run: 'one', page: '2' }; state.$route.query = route
  const paging = state.restoreRoute(route); requests[1].resolve(payload('one', 2)); await paging
  component.watch['$route.query'].call(state, route); assert.equal(requests.length, 2)
  component.watch['$route.query'].call(state, {}); assert.equal(state.selectedId, null); assert.equal(state.result, null)
})

test('running a saved query persists the new snapshot instead of restoring the previous one', async () => {
  const { state, requests } = harness(); state.selectedId = 'old'; state.$route.query = { run: 'old', page: '2' }
  const running = state.runSaved({ id: 'saved' }); requests[0].resolve(payload('new'))
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(state.$route.query.run, 'new'); assert.equal(state.$route.query.page, '1')
  requests[1].resolve(payload('new')); await new Promise(resolve => setImmediate(resolve))
  requests[2].resolve({ rows: [payload('new')], nextCursor: null }); requests[3].resolve([]); await running
  assert.equal(state.result.snapshot.id, 'new'); assert.equal(state.working, false)
})
test('switching snapshots discards both late facts and late errors', async () => {
  const { state, requests } = harness(); const first = state.openRun('first'); const second = state.openRun('second')
  requests[1].resolve(payload('second')); await second; requests[0].reject(new Error('old failure')); await first
  assert.equal(state.result.snapshot.id, 'second'); assert.equal(state.error, ''); assert.equal(state.loading, false)
})
test('same user with a new login session cannot receive a late snapshot', async () => {
  const { state, requests, identity } = harness(); const first = state.openRun('old'); identity.token = 's2'; requests[0].resolve(payload('old')); await first
  assert.equal(state.result, null)
})
test('changing snapshot during export submission never attaches the old task to new data', async () => {
  const { state, requests } = harness(); const ready = state.openRun('first'); requests[0].resolve(payload('first')); await ready
  const exporting = state.startExport(false); const next = state.openRun('next'); requests[2].resolve(payload('next')); await next
  requests[1].resolve({ id: 'old-export', runId: 'first' }); await exporting; assert.equal(state.exportJob, null); assert.equal(state.working, false)
})
test('failed paging clears old facts and can retry the same requested page', async () => {
  const { state, requests } = harness(); const ready = state.openRun('first'); requests[0].resolve(payload('first')); await ready
  const changing = state.loadPage(2); assert.equal(state.result, null); requests[1].reject(new Error('暂不可用')); await changing
  assert.equal(state.error, '暂不可用'); const retry = state.loadPage(2); requests[2].resolve(payload('first', 2)); await retry
  assert.equal(state.result.query.page, 2); assert.equal(state.error, '')
})
test('late ranking cannot authorize a changed count or direction', async () => {
  const { state, requests } = harness(); state.selectedId = 'one'; state.result = payload('one')
  const pending = state.rank(); state.rankForm.count = 4; state.clearRanking(); requests[0].resolve([{ rowKey: 'old' }]); await pending
  assert.equal(state.rankRows, null); assert.equal(state.acceptedRank, null); state.preparePlan(); assert.equal(requests.length, 1)
})
test('ranking does not prepare a plan until the explicit prepare action', async () => {
  const { state, requests } = harness(); state.selectedId = 'one'; state.result = payload('one')
  const ranking = state.rank(); requests[0].resolve([{ rowKey: 'r1' }]); await ranking; assert.equal(requests.length, 1)
  const prepare = state.preparePlan(); assert.equal(requests[1].name, 'prepareRankPlan'); requests[1].resolve({ planId: 'p1', status: 'PENDING' }); await prepare
  assert.equal(state.plan.status, 'PENDING')
})
test('closing the component discards late analysis output', async () => {
  const { state, component, requests } = harness(); state.selectedId = 'one'; state.result = payload('one')
  const analyzing = state.analyze(); component.beforeDestroy.call(state); requests[0].resolve({ groups: [{ sum: '90071992547409.93' }] }); await analyzing
  assert.equal(state.analysis, null); assert.equal(state.result, null)
})
function clientHarness() {
  const store = new Map(), calls = [], identity = { user: 'u1', token: 's1' }
  const sandbox = { getCurrentUserId: () => identity.user, getSessionToken: () => identity.token, authHeaders: () => ({}), window: { crypto: { getRandomValues: values => values.fill(7) } },
    sessionStorage: { getItem: key => store.get(key) || null, setItem: (key, value) => store.set(key, value) },
    http: { post: (...args) => new Promise((resolve, reject) => calls.push({ method: 'POST', args, resolve, reject })), get: (...args) => new Promise((resolve, reject) => calls.push({ method: 'GET', args, resolve, reject })) }, Uint32Array, URL: {}, document: {} }
  vm.runInNewContext(read('api/queryWorkspace.js').replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace(/export /g, ''), sandbox)
  return { sandbox, store, calls, identity }
}
test('lost export reply reuses the original persisted key', async () => {
  const { sandbox, calls } = clientHarness(); const first = sandbox.submitQueryExport('r1'); const key = calls[0].args[2].headers['Idempotency-Key']; calls[0].reject(new Error('lost reply')); await assert.rejects(first)
  const retry = sandbox.submitQueryExport('r1'); assert.equal(calls[1].args[2].headers['Idempotency-Key'], key); calls[1].resolve({ id: 'e1', status: 'QUEUED' }); await retry
  const resume = sandbox.submitQueryExport('r1'); assert.equal(calls[2].method, 'GET'); calls[2].resolve({ id: 'e1', status: 'SUCCEEDED' }); await resume
})
test('late export response after login change never writes a task ID', async () => {
  const { sandbox, calls, identity, store } = clientHarness(); const pending = sandbox.submitQueryExport('r1'); identity.token = 's2'; calls[0].resolve({ id: 'old' }); await assert.rejects(pending, /登录会话/)
  assert.equal(JSON.parse([...store.values()][0]).id, undefined)
})
test('download refuses a JSON error envelope even with HTTP 200', async () => {
  const { sandbox } = clientHarness(); sandbox.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, json: async () => ({ code: 410, message: '快照已过期' }) })
  await assert.rejects(sandbox.downloadQueryExport('e1'), /快照已过期/)
})
test('download does not publish bytes after a session change', async () => {
  const { sandbox, identity } = clientHarness(); sandbox.fetch = async () => ({ ok: true, headers: { get: () => 'text/csv;charset=UTF-8' }, blob: async () => { identity.token = 'new'; return {} } })
  await assert.rejects(sandbox.downloadQueryExport('e1'), /登录会话/)
})
test('task refresh ignores a stale read-marker response', async () => {
  const pending = [], sandbox = { result: null, getCurrentUserId: () => 'u', getSessionToken: () => 's', displayLabel: v => v,
    taskInbox: () => new Promise(resolve => pending.push(resolve)), readTaskNotice: () => new Promise(resolve => pending.push(resolve)) }
  vm.runInNewContext(strip(read('components/TaskInbox.vue').match(/<script>([\s\S]*?)<\/script>/)[1]), sandbox)
  const state = sandbox.result.data(); for (const [name, f] of Object.entries(sandbox.result.methods)) state[name] = f.bind(state)
  state.rows = [{ id: 'e', eventKey: 'old', unread: true }]; const mark = state.markRead(state.rows[0]); const refreshed = state.refresh()
  pending[1]({ rows: [{ id: 'e', eventKey: 'new', unread: true }], nextCursor: null }); await refreshed; pending[0]({}); await mark
  assert.equal(state.rows[0].eventKey, 'new'); assert.equal(state.rows[0].unread, true)
})
