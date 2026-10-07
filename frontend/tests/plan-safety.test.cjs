const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
function component(name, extras = {}) {
  const source = fs.readFileSync(path.join(__dirname, '../src/components/agent', name + '.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const sandbox = { result: null, DispatchTrace: {}, PreviewCard: {}, PlanCard: {}, ResultCard: {}, ReportChoiceCard: {}, BusinessQueryCard: {},
    getCurrentUserId: () => 'user1', window: { crypto: require('node:crypto').webcrypto }, ...extras }
  vm.runInNewContext(source, sandbox)
  return sandbox.result
}
function stateFor(c, props = {}) {
  const state = { ...props }
  Object.assign(state, c.data.call(state))
  for (const [k, f] of Object.entries(c.methods)) state[k] = f.bind(state)
  return state
}
function chat(createPlan) {
  const c = component('AgentChat', { createPlan,
    streamChat() { throw new Error('card action must not invoke the model') } })
  const state = stateFor(c)
  Object.defineProperty(state, 'busy', { get: () => c.computed.busy.call(state) })
  state.activeId = 'conversation-1'
  state.scrollToBottom = state.refreshStates = state.loadConversations = () => {}
  return state
}
const card = () => ({ status: 'ACTIVE', payload: { previewId: 'preview-A' } })
test('all-selected button pins the clicked preview and rejects a superseded source without falling back', async () => {
  const calls = []
  const state = chat(async request => { calls.push(request); throw new Error('preview A superseded by B') })
  state.uiPreviewId = 'preview-B'
  state.uiExcludes = [{ reportId: 'B', recordId: '1' }]
  await state.dispatchSelected(card())
  assert.equal(calls.length, 1)
  assert.equal(calls[0].previewId, 'preview-A')
  assert.equal(calls[0].excludedRecords.length, 0)
  assert.equal(state.messages.length, 0)
  assert.equal(state.busy, false)
})
test('card creation preserves exact selection and retries a lost response with the same idempotency key', async () => {
  const calls = []
  const state = chat(async (request, key) => {
    calls.push({ request, key })
    if (calls.length === 1) throw new Error('response lost')
    return { planId: 'plan-1', previewId: request.previewId, status: 'PENDING' }
  })
  const m = card()
  state.uiPreviewId = m.payload.previewId
  state.uiExcludes = [{ reportId: 'sales', recordId: '1' }]
  await state.dispatchSelected(m)
  await state.dispatchSelected(m)
  assert.equal(calls[0].key, calls[1].key)
  assert.deepEqual(calls[1].request.excludedRecords, state.uiExcludes)
  assert.equal(state.messages.length, 1)
  assert.equal(state.messages[0].payload.planId, 'plan-1')
})
test('changed selection after a failed response gets a new key including when restored to all-selected', async () => {
  const keys = []
  const state = chat(async (_, key) => { keys.push(key); throw new Error('offline') })
  const m = card()
  state.uiPreviewId = m.payload.previewId
  state.uiExcludes = [{ reportId: 'sales', recordId: '1' }]
  await state.dispatchSelected(m)
  state.uiExcludes = []
  await state.dispatchSelected(m)
  assert.notEqual(keys[0], keys[1])
})
test('pending card creation blocks double clicks and ignores a result after account disposal', async () => {
  let complete, count = 0
  const state = chat(() => { count++; return new Promise(resolve => { complete = resolve }) })
  const m = card(), pending = state.dispatchSelected(m)
  await state.dispatchSelected(m)
  assert.equal(count, 1)
  assert.equal(state.busy, true)
  state.disposed = true
  complete({ planId: 'old-user-plan', status: 'PENDING' })
  await pending
  assert.equal(state.messages.length, 0)
})
function paging() {
  const replies = []
  const c = component('PlanCard', { fetchPlanItems: () => new Promise((resolve, reject) => replies.push({ resolve, reject })) })
  return { c, state: stateFor(c, { payload: { planId: 'plan-1' } }), replies }
}
test('late plan page cannot overwrite the last requested page', async () => {
  const { state, replies } = paging()
  const old = state.changePage(2), latest = state.changePage(3)
  replies[1].resolve([{ id: 101 }]); await latest
  replies[0].resolve([{ id: 51 }]); await old
  assert.equal(state.page, 3)
  assert.equal(state.pageRecords[0].id, 101)
})
test('late pre-reconciliation page cannot replace a refreshed success result', async () => {
  const { state, replies } = paging()
  const old = state.changePage(1), refreshed = state.changePage(1)
  replies[1].resolve([{ status: 'SUCCESS' }]); await refreshed
  replies[0].resolve([{ status: 'UNKNOWN' }]); await old
  assert.equal(state.pageRecords[0].status, 'SUCCESS')
})
test('plan change and destruction invalidate outstanding page responses', async () => {
  const { c, state, replies } = paging()
  const old = state.changePage(1)
  state.payload = { planId: 'plan-2' }
  c.watch['payload.planId'].call(state)
  replies[0].resolve([{ id: 'wrong-plan' }]); await old
  assert.equal(state.pageRecords.length, 0)
  c.beforeDestroy.call(state)
  replies[1].resolve([{ id: 'destroyed' }])
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(state.pageRecords.length, 0)
})
