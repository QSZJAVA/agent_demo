const { test } = require('node:test')

test('semantic selection event applies to the exact preview and survives history reload', async () => {
  const excluded = [{ reportId: 'sales', recordId: '2' }]
  const { state } = harness({
    streamChat: (_, event) => {
      event('conversation', { conversationId: 'conv1' })
      event('preview', { previewId: 'p1', status: 'ACTIVE', records: [] })
      event('selection', { previewId: 'p1', excludedRecords: excluded })
      return { promise: Promise.resolve() }
    },
    fetchMessages: async () => [{ id: 1, role: 'card', cardType: 'preview', status: 'ACTIVE', payload: { previewId: 'p1' } }],
    fetchDialogueSelection: async () => ({ previewId: 'p1', excludedRecords: excluded })
  })
  await state.send('exclude')
  assert.equal(state.uiPreviewId, 'p1')
  assert.deepEqual(state.uiExcludes, excluded)
  state.clearSelection()
  await state.openConversation('conv1', true)
  assert.equal(state.uiPreviewId, 'p1')
  assert.deepEqual(state.uiExcludes, excluded)
})

test('late restored selection cannot affect a different conversation', async () => {
  let resolveOld
  const { state } = harness({
    fetchMessages: async id => [{ id: 1, role: 'card', cardType: 'preview', status: 'ACTIVE', payload: { previewId: id } }],
    fetchDialogueSelection: id => id === 'old' ? new Promise(resolve => { resolveOld = resolve })
      : Promise.resolve({ previewId: 'new', excludedRecords: [] })
  })
  const opening = state.openConversation('old', true)
  await new Promise(resolve => setImmediate(resolve))
  await state.openConversation('new', true)
  resolveOld({ previewId: 'old', excludedRecords: [{ reportId: 'private', recordId: '1' }] })
  await opening
  assert.equal(state.uiPreviewId, 'new')
  assert.equal(state.uiExcludes.length, 0)
})

test('server exclusion updates during a page fetch preserve the requested page and checkbox identity', async () => {
  const script = fs.readFileSync(path.join(__dirname, '../src/components/agent/PreviewCard.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  let resolvePage
  const sandbox = { result: null, fetchPreviewItems: () => new Promise(resolve => { resolvePage = resolve }) }
  vm.runInNewContext(script, sandbox)
  const component = sandbox.result
  const toggled = []
  const state = { payload: { previewId: 'p1', total: 100, records: [] }, readonly: false, serverExclusions: [] }
  Object.assign(state, component.data.call(state))
  state.$refs = { table: { clearSelection() {}, toggleRowSelection: row => toggled.push(row.recordId) } }
  state.$nextTick = fn => fn()
  for (const [key, method] of Object.entries(component.methods)) state[key] = method.bind(state)
  const loading = state.changePage(2)
  component.watch.serverExclusions.handler.call(state, [{ reportId: 'sales', recordId: '51' }])
  assert.equal(state.loadingPage, true)
  resolvePage([{ reportId: 'sales', recordId: '51' }, { reportId: 'sales', recordId: '52' }])
  await loading
  assert.equal(state.page, 2)
  assert.deepEqual(toggled, ['52'])
  assert.equal(state.selectedCount, 99)
})

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
    result: null, PreviewCard: {}, PlanCard: {}, ResultCard: {}, ReportChoiceCard: {}, BusinessQueryCard: {},
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
    fetchDialogueSelection: async () => ({ previewId: null, excludedRecords: [] }),
    saveDialogueSelection: async (id, request) => ({ previewId: request.previewId, excludedRecords: request.excludedRecords }),
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
  return { state, calls, storage, component }
}

test('manual selection is persisted before enabling actions and restored from server', async () => {
  let complete, request, saved = []
  const { state } = harness({
    saveDialogueSelection: (id, body) => { request = { id, body }; return new Promise(resolve => { complete = () => { saved = body.excludedRecords; resolve({ previewId: 'p1', excludedRecords: saved }) } }) },
    fetchDialogueSelection: async () => ({ previewId: 'p1', excludedRecords: saved })
  })
  state.activeId = 'conv1'; state.uiPreviewId = 'p1'
  const excluded = [{ reportId: 'sales', recordId: '2' }]
  const saving = state.onPreviewSelection({ status: 'ACTIVE', payload: { previewId: 'p1' } }, excluded)
  assert.equal(state.busy, true); assert.equal(request.id, 'conv1'); assert.equal(request.body.expectedExclusions.length, 0)
  complete(); await saving
  assert.deepEqual(state.uiExcludes, excluded); assert.equal(state.busy, false)
  state.clearSelection(); await state.restoreSelection('conv1', state.historyVersion)
  assert.deepEqual(state.uiExcludes, excluded)
})

test('manual selection failure blocks dispatch and late success cannot overwrite another conversation', async () => {
  const failed = harness({ saveDialogueSelection: async () => { throw new Error('conflict') } }).state
  failed.activeId = 'conv1'; failed.uiPreviewId = 'p1'
  await failed.onPreviewSelection({ status: 'ACTIVE', payload: { previewId: 'p1' } }, [{ reportId: 'sales', recordId: '2' }])
  assert.equal(failed.selectionRestoreFailed, true); assert.equal(failed.uiExcludes.length, 0)
  let finish
  const state = harness({ saveDialogueSelection: () => new Promise(resolve => { finish = resolve }) }).state
  state.activeId = 'conv1'; state.uiPreviewId = 'p1'
  const saving = state.onPreviewSelection({ status: 'ACTIVE', payload: { previewId: 'p1' } }, [])
  state.activeId = 'conv2'; state.historyVersion++; state.uiPreviewId = 'p2'
  finish({ previewId: 'p1', excludedRecords: [{ reportId: 'sales', recordId: '2' }] }); await saving
  assert.equal(state.uiPreviewId, 'p2'); assert.equal(state.uiExcludes.length, 0)
})

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

test('refresh replaces a saved older job with the latest conversation request', async () => {
  const storage = new Map([['agent-preview-job:user1', JSON.stringify({ conversationId: 'conv1', jobId: 'older-job' })]])
  const { state, calls } = harness({}, storage)
  await state.resumePendingJob()
  assert.deepEqual(calls.discovery, ['conv1'])
  assert.deepEqual(calls.polls, ['job1'])
  assert.equal(state.messages.filter(m => m.cardType === 'preview').length, 1)
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

test('switching accounts aborts SSE and ignores late events and recovery', async () => {
  let user = 'user1', event, finish, aborted = false
  const { state, component, calls, storage } = harness({
    getCurrentUserId: () => user,
    streamChat: (_, handler) => {
      event = handler
      return { promise: new Promise(resolve => { finish = resolve }), abort: () => { aborted = true } }
    }
  })
  const sending = state.send('query')
  event('conversation', { conversationId: 'private' })
  user = 'user2'
  component.beforeDestroy.call(state)
  event('text', { delta: 'private content' })
  event('conversation', { conversationId: 'late' })
  finish()
  await sending
  assert.equal(aborted, true)
  assert.equal(state.messages.length, 0)
  assert.equal(state.activeId, null)
  assert.equal(storage.has('agent-preview-job:user2'), false)
  assert.equal(calls.discovery.length, 0)
})

test('destroy cancels polling delay without issuing another request', async () => {
  let cleared = false, polls = 0
  let started
  const timerStarted = new Promise(resolve => { started = resolve })
  const { state, component } = harness({
    setTimeout: () => { started(); return 123 },
    clearTimeout: id => { assert.equal(id, 123); cleared = true },
    fetchPreviewJob: async () => { polls++; return { status: 'RUNNING' } }
  })
  const pending = state.pollPreviewJob('job1')
  await timerStarted
  component.beforeDestroy.call(state)
  await assert.rejects(pending, /会话已关闭/)
  assert.equal(cleared, true)
  assert.equal(polls, 1)
})

test('conversation pagination appends and deduplicates subsequent pages', async () => {
  const requests = []
  const { state, component } = harness({ fetchConversations: async (page, size) => {
    requests.push([page, size])
    return page === 1 ? Array.from({ length: 50 }, (_, i) => ({ id: i + 1 })) : [{ id: 50 }, { id: 51 }]
  } })
  await component.methods.loadConversations.call(state)
  assert.equal(state.hasMoreConversations, true)
  await component.methods.loadConversations.call(state, true)
  assert.equal(state.conversations.length, 51)
  assert.equal(state.hasMoreConversations, false)
  assert.deepEqual(requests, [[1, 50], [2, 50]])
})

test('older history uses cursor, deduplicates, and preserves scroll position', async () => {
  const requests = []
  const { state } = harness({ fetchMessages: async (id, before, size) => {
    requests.push([id, before, size])
    return before ? [{ id: 1, role: 'user' }, { id: 101, role: 'user' }]
      : Array.from({ length: 100 }, (_, i) => ({ id: 101 + i, role: 'user' }))
  } })
  const scroll = { scrollHeight: 1000, scrollTop: 40 }
  state.$refs = { scroll }
  state.$nextTick = fn => { scroll.scrollHeight = 1100; fn() }
  await state.openConversation('conv1', true)
  assert.equal(state.hasOlderMessages, true)
  await state.loadOlderMessages()
  assert.equal(state.messages.length, 101)
  assert.equal(state.messages[0].id, 1)
  assert.equal(state.hasOlderMessages, false)
  assert.equal(scroll.scrollTop, 140)
  assert.deepEqual(requests[1], ['conv1', 101, 100])
})

test('late history response cannot replace another conversation', async () => {
  let finish
  const { state } = harness({ fetchMessages: id => id === 'old'
    ? new Promise(resolve => { finish = resolve }) : Promise.resolve([{ id: 2, role: 'user' }]) })
  const first = state.openConversation('old', true)
  await state.openConversation('new', true)
  finish([{ id: 1, role: 'user' }])
  await first
  assert.equal(state.activeId, 'new')
  assert.equal(state.messages[0].id, 2)
})

test('selected record identity is sent with its preview', async () => {
  let request
  const { state } = harness({ streamChat: args => {
    request = args
    return { promise: Promise.resolve() }
  } })
  state.uiPreviewId = 'preview1'
  state.uiExcludes = [{ reportId: 'sales', recordId: '1' }]
  await state.send('dispatch')
  assert.equal(request.previewId, 'preview1')
  assert.equal(JSON.stringify(request.excludedRecords), JSON.stringify(state.uiExcludes))
})

test('duplicate document numbers and page changes preserve exact selection', () => {
  const script = fs.readFileSync(path.join(__dirname, '../src/components/agent/PreviewCard.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const sandbox = { result: null }
  vm.runInNewContext(script, sandbox)
  const sale = { reportId: 'sales', recordId: '1', docNo: 'DUP001' }
  const expense = { reportId: 'expense', recordId: '1', docNo: 'DUP001' }
  const state = { readonly: false, loadingPage: false, excludedRecords: [],
    pageRecords: [sale, expense], payload: { total: 3 }, $emit() {} }
  for (const [key, method] of Object.entries(sandbox.result.methods)) state[key] = method.bind(state)
  state.onSelectionChange([expense])
  assert.equal(JSON.stringify(state.excludedRecords), JSON.stringify([{ reportId: 'sales', recordId: '1' }]))
  state.pageRecords = [{ reportId: 'sales', recordId: '2', docNo: 'OTHER' }]
  state.onSelectionChange(state.pageRecords)
  assert.equal(state.selectedCount, 2)
  state.pageRecords = [sale, expense]
  state.onSelectionChange([sale, expense])
  assert.equal(state.excludedRecords.length, 0)
  assert.equal(state.selectedCount, 3)
})

test('sending is blocked until initial history finishes and remains usable afterwards', async () => {
  let finish, sent = 0
  const { state } = harness({
    fetchMessages: () => new Promise(resolve => { finish = resolve }),
    streamChat: (_, event) => { sent++; event('text', { delta: 'new answer' }); return { promise: Promise.resolve() } }
  })
  const opening = state.openConversation('conv1', true)
  assert.equal(state.busy, true)
  state.input = 'new message'
  await state.send()
  assert.equal(sent, 0)
  assert.equal(state.input, 'new message')
  finish([{ id: 1, role: 'user', content: 'old message' }])
  await opening
  await state.send()
  assert.equal(sent, 1)
  assert.equal(state.messages.length, 3)
  assert.equal(state.messages[2].content, 'new answer')
})

test('failed selection restore blocks both chat and plan creation until a successful retry', async () => {
  let fail = true, sent = 0, plans = 0
  const excluded = [{ reportId: 'sales', recordId: '2' }]
  const { state } = harness({
    fetchMessages: async () => [{ id: 1, role: 'card', cardType: 'preview', status: 'ACTIVE', payload: { previewId: 'p1' } }],
    fetchDialogueSelection: async () => { if (fail) throw new Error('offline'); return { previewId: 'p1', excludedRecords: excluded } },
    createPlan: async request => { plans++; assert.deepEqual(request.excludedRecords, excluded); return { planId: 'plan1' } },
    window: { crypto: require('node:crypto').webcrypto },
    streamChat: () => { sent++; return { promise: Promise.resolve() } }
  })
  await state.openConversation('conv1', true)
  assert.equal(state.selectionRestoreFailed, true)
  await state.send('dispatch'); await state.dispatchSelected(state.messages[0])
  assert.equal(sent, 0); assert.equal(plans, 0)
  fail = false
  await state.retrySelectionRestore()
  assert.equal(state.selectionRestoreFailed, false)
  await state.dispatchSelected(state.messages[0])
  assert.equal(plans, 1)
})

test('expired or older-page preview retains the server exclusions for safe refresh', async () => {
  const excluded = [{ reportId: 'sales', recordId: '2' }]
  const { state } = harness({ fetchDialogueSelection: async () => ({ previewId: 'old', excludedRecords: excluded }) })
  await state.openConversation('conv1', true)
  assert.equal(state.uiPreviewId, 'old'); assert.deepEqual(state.uiExcludes, excluded)
})

test('failed history request releases the sending guard', async () => {
  const { state } = harness({ fetchMessages: async () => { throw new Error('offline') } })
  await assert.rejects(state.openConversation('conv1', true), /offline/)
  assert.equal(state.busy, false)
})

test('invalid current selection cannot silently restore as all selected', async () => {
  const { state } = harness({ fetchDialogueSelection: async () => ({ previewId: 'p1', excludeDocNos: ['SO1'] }) })
  state.activeId = 'conv1'
  state.uiExcludes = [{ reportId: 'rpt-sales-order', recordId: '1' }]
  await state.restoreSelection('conv1', state.historyVersion)
  assert.equal(state.selectionRestoreFailed, true)
  assert.equal(state.uiExcludes.length, 1)
})
