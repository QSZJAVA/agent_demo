const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

// Exercise the component's real methods without introducing a browser-test dependency.
const source = fs.readFileSync(path.join(__dirname, '../src/components/agent/AgentChat.vue'), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '')
  .replace('export default', 'result =')

function harness(overrides = {}, storage = new Map()) {
  const calls = { discovery: [], polls: [] }
  const job = { id: 'job1', status: 'SUCCEEDED', previewId: 'preview1' }
  const sandbox = {
    result: null, PreviewCard: {}, PlanCard: {}, ResultCard: {}, ReportChoiceCard: {},
    getCurrentUserId: () => 'user1',
    sessionStorage: {
      getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value),
      removeItem: key => storage.delete(key)
    },
    setTimeout: resolve => resolve(),
    fetchLatestPreviewJob: async id => { calls.discovery.push(id); return job },
    fetchPreviewJob: async id => { calls.polls.push(id); return job },
    fetchPreview: async id => ({ previewId: id, status: 'ACTIVE' }),
    fetchMessages: async () => [],
    streamChat: (_, event) => {
      event('conversation', { conversationId: 'conv1' })
      return { promise: Promise.reject(new Error('disconnected before preview_job')) }
    },
    ...overrides
  }
  vm.runInNewContext(source, sandbox)
  const component = sandbox.result
  const state = { ...component.data(), $message: { warning() {} }, $emit() {} }
  Object.defineProperty(state, 'busy', { get: () => component.computed.busy.call(state) })
  for (const [key, method] of Object.entries(component.methods)) state[key] = method.bind(state)
  state.scrollToBottom = () => {}
  state.loadConversations = async () => {}
  state.refreshStates = async () => {}
  return { state, calls, storage }
}

test('SSE disconnect before job event discovers the task and renders its result', async () => {
  const { state, calls, storage } = harness()
  await state.send('query')
  assert.deepEqual(calls.discovery, ['conv1'])
  assert.deepEqual(calls.polls, ['job1'])
  assert.equal(state.messages.filter(m => m.cardType === 'preview').length, 1)
  assert.equal(storage.size, 0)
  assert.equal(state.busy, false)
})

test('refresh can recover with only a saved conversation after discovery failed', async () => {
  const failed = harness({ fetchLatestPreviewJob: async () => { throw new Error('offline') } })
  await failed.state.send('query')
  assert.equal(JSON.parse(failed.storage.get('agent-preview-job:user1')).conversationId, 'conv1')
  const reopened = harness({}, failed.storage)
  await reopened.state.resumePendingJob()
  assert.equal(reopened.state.activeId, 'conv1')
  assert.deepEqual(reopened.calls.polls, ['job1'])
  assert.equal(reopened.state.messages.filter(m => m.cardType === 'preview').length, 1)
  assert.equal(reopened.storage.size, 0)
})

test('opening conversation without local storage discovers a running task', async () => {
  let polls = 0
  const { state, calls } = harness({ fetchPreviewJob: async () => ++polls === 1
    ? { id: 'job1', status: 'RUNNING', scannedRows: 10 }
    : { id: 'job1', status: 'SUCCEEDED', previewId: 'preview1' } })
  await state.openConversation('conv1')
  assert.deepEqual(calls.discovery, ['conv1'])
  assert.equal(polls, 2)
  assert.equal(state.messages.filter(m => m.cardType === 'preview').length, 1)
})

test('completed task already in history does not add another preview card', async () => {
  const { state, storage } = harness({ fetchMessages: async () => [
    { id: 'message1', role: 'card', cardType: 'preview', payload: { previewId: 'preview1' } }
  ] })
  await state.openConversation('conv1')
  assert.equal(state.messages.filter(m => m.cardType === 'preview').length, 1)
  assert.equal(storage.size, 0)
})
