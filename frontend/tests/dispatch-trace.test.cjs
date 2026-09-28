const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const source = fs.readFileSync(path.join(__dirname, '../src/components/agent/DispatchTrace.vue'), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
function harness(overrides = {}) {
  const sandbox = { result: null, getCurrentUserId: () => 'user1', ...overrides }
  vm.runInNewContext(source, sandbox)
  const component = sandbox.result
  const state = { planId: 'plan1', visible: true, ...component.data(), $message: { success() {} } }
  for (const [name, method] of Object.entries(component.methods)) state[name] = method.bind(state)
  Object.defineProperty(state, 'current', { get: () => component.computed.current.call(state) })
  return { state, component, sandbox }
}
const trace = id => ({ plan: { id }, events: { records: [{ id: 1 }], total: 3, nextCursor: 1 },
  messages: { records: [], total: 0, nextCursor: null } })

test('late trace response cannot expose a previous plan or account', async () => {
  const requests = []
  const { state, sandbox } = harness({ fetchDispatchTrace: () => new Promise(resolve => requests.push(resolve)) })
  const first = state.load()
  state.planId = 'plan2'
  const second = state.load()
  requests[1](trace('plan2')); await second
  requests[0](trace('plan1')); await first
  assert.equal(state.trace.plan.id, 'plan2')
  const third = state.load()
  sandbox.getCurrentUserId = () => 'user2'
  requests[2](trace('plan2')); await third
  assert.equal(state.trace, null)
})

test('revoked trace access clears old data and keeps a retryable error', async () => {
  const { state } = harness({ fetchDispatchTrace: async () => { throw new Error('权限范围已变化') } })
  state.trace = trace('plan1')
  await state.load()
  assert.equal(state.trace, null)
  assert.equal(state.loading, false)
  assert.equal(state.error, '权限范围已变化')
})

test('trace pagination preserves the target tab, deduplicates and keeps the cursor', async () => {
  let resolve
  const { state } = harness({ fetchTracePage: () => new Promise(r => { resolve = r }) })
  state.trace = trace('plan1')
  const pending = state.more()
  state.section = 'messages'
  resolve({ records: [{ id: 1 }, { id: 2 }, { id: 3 }], total: 3, nextCursor: null })
  await pending
  assert.deepEqual(state.trace.events.records.map(row => row.id), [1, 2, 3])
  assert.equal(state.trace.events.nextCursor, null)
  assert.equal(state.trace.messages.records.length, 0)
})

test('repair only queues durable evidence delivery and cannot repeat dispatch', async () => {
  const calls = []
  const { state } = harness({ retryTraceDelivery: async id => { calls.push(id); return { scheduledCount: 2 } } })
  await state.retry()
  assert.deepEqual(calls, ['plan1'])
  assert.equal(state.retrying, false)
})
