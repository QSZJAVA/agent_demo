const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
function component(file, extras) {
  const source = fs.readFileSync(path.join(__dirname, '../src/components', file), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const sandbox = { result: null, ...extras }
  vm.runInNewContext(source, sandbox)
  return sandbox.result
}
function paging() {
  const requests = new Map(), ticks = []
  const c = component('agent/PreviewCard.vue', {
    fetchPreviewItems: (_, page) => new Promise((resolve, reject) => requests.set(page, { resolve, reject }))
  })
  const state = { payload: { total: 150, previewId: 'p' }, readonly: false, busy: false, $emit() {} }
  Object.assign(state, c.data.call(state))
  for (const [k, f] of Object.entries(c.methods)) state[k] = f.bind(state)
  let records = [], selected = []
  state.$nextTick = fn => ticks.push(fn)
  const clear = () => { if (selected.length) { selected = []; state.onSelectionChange([]) } }
  // Mirrors Element UI setData: replacing an array clears non-reserved selection before nextTick callbacks.
  Object.defineProperty(state, 'pageRecords', { get: () => records, set: value => { records = value; ticks.push(clear) } })
  state.$refs = { table: { clearSelection: clear,
    toggleRowSelection(row) { selected.push(row); state.onSelectionChange(selected) } } }
  return { state, requests, drain: () => { while (ticks.length) ticks.shift()() } }
}
const row = id => [{ reportId: 'sales', recordId: String(id) }]
for (const order of [[2, 3], [3, 2]]) {
  test(`overlapping page responses ${order} preserve selection and latest page`, async () => {
    const { state, requests, drain } = paging()
    const first = state.changePage(1); requests.get(1).resolve(row(1)); await first; drain()
    const pending = { 2: state.changePage(2), 3: state.changePage(3) }
    for (const page of order) { requests.get(page).resolve(row(page)); await pending[page]; drain() }
    assert.equal(state.page, 3)
    assert.equal(state.pageRecords[0].recordId, '3')
    assert.equal(state.excludedRecords.length, 0)
    assert.equal(state.selectedCount, 150)
    assert.equal(state.loadingPage, false)
  })
}
test('stale page failure cannot unlock selection during the latest request', async () => {
  const { state, requests, drain } = paging()
  const old = state.changePage(2), latest = state.changePage(3)
  requests.get(2).reject(new Error('offline')); await old
  assert.equal(state.loadingPage, true)
  requests.get(3).resolve(row(3)); await latest; drain()
  assert.equal(state.loadingPage, false)
})
test('manual unknown is shown as pending reconciliation and recovered from server', async () => {
  const warnings = [], calls = []
  const plans = [{ planId: 'p1', docNo: 'SO1', status: 'REVIEW_REQUIRED', retryableCount: 0 }]
  const c = component('ReportTable.vue', {
    getCurrentUserId: () => 'user1',
    confirmPlan: async () => {}, retryFailedPlan: async () => {},
    dispatchDirect: async (_, ids) => { calls.push(['dispatch', ...ids]); return { reviewCount: 1 } },
    fetchManualPlans: async () => plans,
    reconcilePlan: async id => { calls.push(['reconcile', id]); plans[0].status = 'EXECUTED' }
  })
  const state = { ...c.data(), reportType: 'sales', docNoField: 'orderNo',
    $message: { warning: text => warnings.push(text), info() { throw new Error('must not report ordinary failure') } },
    $confirm: async () => {}, $emit() {} }
  for (const [k, f] of Object.entries(c.methods)) state[k] = f.bind(state)
  state.selectedRows = [{ id: '1', orderNo: 'SO1' }]
  await state.handleDispatch()
  await state.loadManualPlans()
  assert.match(warnings[0], /待核对/)
  assert.equal(state.manualPlans[0].planId, 'p1')
  await state.processManual(state.manualPlans[0], 'reconcile')
  assert.deepEqual(calls, [['dispatch', '1'], ['reconcile', 'p1']])
})
