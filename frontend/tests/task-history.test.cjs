const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const source = fs.readFileSync(path.join(__dirname, '../src/views/MyTasks.vue'), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')

function harness() {
  const lists = [], plans = [], actions = [], confirmations = []
  const sandbox = { result: null, DispatchTrace: {}, formatAmount() {}, statusTone() {}, displayLabel: value => value,
    getCurrentUserId: () => 'user1', getSessionToken: () => 'session1',
    fetchTaskHistory: args => new Promise((resolve, reject) => lists.push({ args, resolve, reject })),
    fetchTaskPlan: (id, page, operator) => new Promise((resolve, reject) => plans.push({ id, page, operator, resolve, reject })),
    runDispatchJob: request => new Promise((resolve, reject) => actions.push({ request, resolve, reject })) }
  vm.runInNewContext(source, sandbox)
  const component = sandbox.result, state = component.data()
  for (const [name, method] of Object.entries(component.methods)) state[name] = method.bind(state)
  state.$confirm = () => new Promise((resolve, reject) => confirmations.push({ resolve, reject }))
  return { component, state, sandbox, lists, plans, actions, confirmations }
}
const page = (id, nextCursor = null) => ({ rows: [{ id, plans: [] }], nextCursor })

test('task status changes reject late replies and reset to first page', async () => {
  const { state, lists } = harness()
  const first = state.refresh()
  state.status = 'FAILED'
  const latest = state.changeStatus()
  lists[1].resolve(page('failure')); await latest
  lists[0].resolve(page('old', 'old-cursor')); await first
  assert.equal(state.rows[0].id, 'failure')
  assert.equal(state.nextCursor, null)
  assert.equal(state.previous.length, 0)
  assert.equal(lists[1].args.status, 'FAILED')
  assert.equal(lists[1].args.before, null)
})

test('failed paging clears facts and retries the same cursor without skipping a page', async () => {
  const { state, lists } = harness()
  const first = state.refresh(); lists[0].resolve(page('first', 'next')); await first
  const next = state.nextPage(); lists[1].reject(new Error('服务连接中断')); await next
  assert.equal(state.rows.length, 0)
  assert.equal(state.previous.length, 0)
  assert.equal(state.error, '服务连接中断')
  const retry = state.retry(); lists[2].resolve(page('second')); await retry
  assert.equal(lists[2].args.before, 'next')
  assert.equal(state.previous.length, 1)
  const back = state.previousPage(); lists[3].resolve(page('first', 'next')); await back
  assert.equal(lists[3].args.before, null)
  assert.equal(state.previous.length, 0)
})

test('an empty permission filtered scan can still load older visible tasks', async () => {
  const { state, component, lists } = harness()
  const first = state.refresh(); lists[0].resolve({ rows: [], nextCursor: 'scan-end' }); await first
  assert.match(component.computed.emptyText.call(state), /更早任务/)
  const next = state.nextPage(); lists[1].resolve(page('visible')); await next
  assert.equal(state.rows[0].id, 'visible')
})

test('old failures cannot clear the loading state or overwrite a newer request', async () => {
  const { state, lists } = harness()
  const first = state.refresh(), latest = state.refresh()
  lists[0].reject(new Error('old')); await first
  assert.equal(state.loading, true)
  assert.equal(state.error, '')
  lists[1].resolve(page('current')); await latest
  assert.equal(state.loading, false)
})

test('late task replies after logout, another session or destruction are discarded', async () => {
  for (const change of ['user', 'session', 'destroy']) {
    const { state, component, sandbox, lists } = harness()
    const loading = state.refresh()
    if (change === 'user') sandbox.getCurrentUserId = () => 'other'
    if (change === 'session') sandbox.getSessionToken = () => 'new-session'
    if (change === 'destroy') component.beforeDestroy.call(state)
    lists[0].resolve(page('private')); await loading
    assert.equal(state.rows.length, 0)
  }
})

test('plan details are bound to both target and open dialog lifetime', async () => {
  const { state, plans } = harness()
  const first = state.openPlan('A'), second = state.openPlan('B')
  plans[1].resolve({ plan: { planId: 'B' }, items: [{ id: 'B1' }] }); await second
  plans[0].resolve({ plan: { planId: 'A' }, items: [{ id: 'A1' }] }); await first
  assert.equal(state.detail.plan.planId, 'B')
  const late = state.loadPlan(2); state.closePlan()
  plans[2].resolve({ plan: { planId: 'B' }, items: [{ id: 'B2' }] }); await late
  assert.equal(state.detail, null)
  assert.equal(state.detailVisible, false)
})

test('revoked detail reads clear earlier facts and keep a retryable error', async () => {
  const { state, plans } = harness()
  const first = state.openPlan('A'); plans[0].resolve({ plan: { planId: 'A' }, items: [] }); await first
  const revoked = state.loadPlan(1); plans[1].reject(new Error('当前权限不可读取')); await revoked
  assert.equal(state.detail, null)
  assert.equal(state.detailError, '当前权限不可读取')
  assert.equal(state.detailLoading, false)
})

test('operator job detail uses the delegated read path and resets for ordinary jobs', async () => {
  const { state, plans } = harness()
  const delegated = state.openPlan('owner-plan', 'OP_RECONCILE')
  assert.equal(plans[0].operator, true)
  plans[0].resolve({ plan: { planId: 'owner-plan' }, items: [] }); await delegated
  const own = state.openPlan('own-plan', 'CONFIRM')
  assert.equal(plans[1].operator, false)
  plans[1].resolve({ plan: { planId: 'own-plan' }, items: [] }); await own
})

test('closing, switching, refreshing or losing a session invalidates a pending business confirmation', async () => {
  for (const action of ['CONFIRM', 'RETRY_FAILED']) for (const change of ['close', 'switch', 'refresh', 'session']) {
    const { state, plans, actions, confirmations, sandbox } = harness()
    const opened = state.openPlan('A'); plans[0].resolve({ plan: { planId: 'A', count: 3 }, items: [] }); await opened
    const confirmation = state.handleException(action)
    if (change === 'close') state.closePlan()
    if (change === 'switch') state.openPlan('B')
    if (change === 'refresh') state.loadPlan(1)
    if (change === 'session') sandbox.getSessionToken = () => 'new-session'
    confirmations[0].resolve(); await confirmation
    assert.equal(actions.length, 0, `${action}/${change} must not submit`)
  }
})

test('explicit confirmation submits once and refreshes the matching plan without replaying on read', async () => {
  const { state, plans, actions, confirmations } = harness()
  const opened = state.openPlan('A'); plans[0].resolve({ plan: { planId: 'A', count: 3 }, items: [] }); await opened
  const pending = state.handleException('CONFIRM'); await state.handleException('CONFIRM')
  assert.equal(confirmations.length, 1); assert.equal(actions.length, 0)
  confirmations[0].resolve(); await new Promise(resolve => setImmediate(resolve))
  assert.equal(actions.length, 1); assert.equal(actions[0].request.planId, 'A')
  actions[0].resolve({}); await new Promise(resolve => setImmediate(resolve))
  plans[1].resolve({ plan: { planId: 'A', status: 'EXECUTED', count: 3 }, items: [] }); await pending
  assert.equal(state.detail.plan.status, 'EXECUTED'); assert.equal(actions.length, 1); assert.equal(state.actionLoading, false)
})

test('history and plan clients only make GET calls and encode plan identity', async () => {
  const calls = [], sandbox = { http: { get: async (...args) => { calls.push(args); return {} } } }
  const api = fs.readFileSync(path.join(__dirname, '../src/api/taskHistory.js'), 'utf8')
    .replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace(/export /g, '')
  vm.runInNewContext(api, sandbox)
  await sandbox.fetchTaskHistory({ status: 'RUNNING' })
  await sandbox.fetchTaskPlan('plan/a', 2)
  assert.equal(calls[0][0], '/dispatch/jobs/history')
  assert.equal(calls[0][1].params.status, 'RUNNING')
  assert.deepEqual(calls.slice(1).map(call => call[0]), ['/dispatch/plans/plan%2Fa', '/dispatch/plans/plan%2Fa/items'])
  assert.equal(calls[2][1].params.page, 2)
  await sandbox.fetchTaskPlan('other-owner-plan', 1, true)
  assert.deepEqual(calls.slice(3).map(call => call[0]), ['/operations/workbench/other-owner-plan', '/operations/workbench/other-owner-plan/items'])
})
