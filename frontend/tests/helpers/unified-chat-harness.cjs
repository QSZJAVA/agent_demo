/** 当前聊天与卡片的受控传输夹具；执行真实组件、请求保存器和回执校验，模型与HTTP行为由专项端到端另验。 */
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { webcrypto } = require('node:crypto')
const root = path.join(__dirname, '../../src')
const read = name => fs.readFileSync(path.join(root, name), 'utf8')
const moduleSource = value => value.replace(/^import .*$/gm, '').replace(/export /g, '')
const pendingContext = { TextEncoder }
vm.runInNewContext(moduleSource(read('utils/pendingRequestStore.js')) + moduleSource(read('utils/chatPending.js')), pendingContext)
const analysisPendingContext = { TextEncoder }
vm.runInNewContext(moduleSource(read('utils/pendingRequestStore.js')) + moduleSource(read('utils/analysisPending.js')), analysisPendingContext)
const receiptContext = {}
vm.runInNewContext(moduleSource(read('utils/chatReceipt.js')), receiptContext)
const C = 'c'.repeat(32), R = 'a'.repeat(32), RUN = 'd'.repeat(32), PREVIEW = 'b'.repeat(32)
const task = () => ({ requestId: R, runId: RUN, mode: 'KNOWLEDGE_ONLY', companyCode: 'A', reportId: 'rpt-sales-order' })
const receipt = (key, status = 'SUBMITTED', overrides = {}) => ({ requestId: R, conversationId: C, clientKey: key, status, message: null, canReconcile: ['RECEIVED', 'READY'].includes(status), historyReady: true, analysisTask: status === 'SUBMITTED' ? task() : null, expiresAt: '2099-01-01T00:00:00Z', ...overrides })
const request = () => ({ conversationId: null, message: 'A公司销售金额的业务口径是什么', excludedRecords: [], previewId: null, queryEdit: null, clarificationReply: null })
const plain = value => JSON.parse(JSON.stringify(value))
const serverError = code => Object.assign(new Error('受控接口拒绝'), { code, serverResponse: true })
const tick = () => new Promise(resolve => setImmediate(resolve))
async function until(check) { for (let index = 0; index < 200; index++) { if (check()) return; await tick() }; throw new Error('受控异步操作没有到达预期阶段') }

function environment() {
  const values = new Map(), identity = { userId: 'reader', token: 'test-session-a' }, clock = { now: Date.now() }
  const storage = { get length() { return values.size }, key: index => [...values.keys()][index] ?? null, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) }
  const options = () => ({ getStorage: () => storage, getIdentity: () => identity, cryptoApi: webcrypto, now: () => clock.now })
  return { values, identity, clock, storage, store: extra => pendingContext.createChatPendingStore({ ...options(), ...extra }), analysisStore: () => analysisPendingContext.createAnalysisPendingStore(options()) }
}

function chatHarness(env = environment(), overrides = {}) {
  const calls = { posts: [], gets: [], histories: [], discoveries: [], timers: new Map(), routes: [], warnings: [] }
  let timer = 0
  const context = {
    component: null, PreviewCard: {}, PlanCard: {}, ResultCard: {}, ReportChoiceCard: {}, BusinessQueryCard: {}, QueryConditionEditor: {}, AnalysisTaskCard: {},
    getCurrentUserId: () => env.identity.userId, getSessionToken: () => env.identity.token, crypto: webcrypto, sessionStorage: env.storage,
    createChatPendingStore: () => env.store(), analysisTaskReference: receiptContext.analysisTaskReference, readChatReceipt: receiptContext.readChatReceipt, chatTurnSettled: receiptContext.chatTurnSettled,
    setTimeout: callback => { const id = ++timer; calls.timers.set(id, callback); return id }, clearTimeout: id => calls.timers.delete(id),
    fetchChatTurn: async key => receipt(key),
    fetchMessages: async () => [{ id: 1, role: 'user', requestId: R, content: request().message }, { id: 2, role: 'card', requestId: R, cardType: 'analysis_task', payload: task() }],
    fetchDialogueSelection: async () => ({ previewId: null, excludedRecords: [] }), fetchCardStates: async () => ({ previews: {}, plans: {} }), fetchConversations: async () => [],
    fetchLatestPreviewJob: async () => null,
    streamChat: (body, event, key) => { event('conversation', { requestId: R, conversationId: C, replayed: false }); event('analysis_task', task()); event('turn_receipt', receipt(key)); return { promise: Promise.resolve(), abort() {} } },
    ...overrides
  }
  for (const [name, group] of [['streamChat', 'posts'], ['fetchChatTurn', 'gets'], ['fetchMessages', 'histories'], ['fetchLatestPreviewJob', 'discoveries']]) {
    const original = context[name]
    context[name] = (...args) => { calls[group].push(name === 'streamChat' ? { request: plain(args[0]), event: args[1], key: args[2] } : args); return original(...args) }
  }
  const script = read('components/agent/AgentChat.vue').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'component =')
  vm.runInNewContext(script, context)
  const component = context.component, state = { ...component.data(), visible: true, chatReady: true, $refs: {}, $nextTick: callback => callback(), $emit() {}, $set: (target, key, value) => { target[key] = value }, $message: { warning: text => calls.warnings.push(text) }, $router: { push: route => calls.routes.push(route) } }
  for (const [key, method] of Object.entries(component.methods)) state[key] = method.bind(state)
  for (const [key, getter] of Object.entries(component.computed)) Object.defineProperty(state, key, { get: () => getter.call(state) })
  state._chatPendingStore = env.store(); state.scrollToBottom = () => {}; state.loadConversations = async () => {}; state.refreshStates = async () => {}
  return { env, state, component, calls }
}

function cardHarness(overrides = {}) {
  const identity = { userId: 'reader', token: 'test-session-a' }, calls = { reads: [], cancellations: [], timers: new Map(), opened: [] }
  let timer = 0
  const context = { component: null, getCurrentUserId: () => identity.userId, getSessionToken: () => identity.token, analysisTaskReference: receiptContext.analysisTaskReference,
    setTimeout: callback => { const id = ++timer; calls.timers.set(id, callback); return id }, clearTimeout: id => calls.timers.delete(id),
    analysisRun: id => new Promise((resolve, reject) => calls.reads.push({ id, resolve, reject })),
    cancelAnalysis: id => new Promise((resolve, reject) => calls.cancellations.push({ id, resolve, reject })), ...overrides }
  const script = read('components/agent/AnalysisTaskCard.vue').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'component =')
  vm.runInNewContext(script, context)
  const component = context.component, state = { ...component.data(), payload: task(), cardState: null, active: true, _alive: true, _seq: 0, _identity: { ...identity }, $emit: (type, id) => calls.opened.push({ type, id }) }
  for (const [key, method] of Object.entries(component.methods)) state[key] = method.bind(state)
  for (const [key, getter] of Object.entries(component.computed)) Object.defineProperty(state, key, { get: () => getter.call(state) })
  const run = (status = 'COMPLETED', version = 1) => ({ id: RUN, status, version, stage: 'FINISHED', request: { companyCode: 'A', reportId: 'rpt-sales-order', mode: 'KNOWLEDGE_ONLY', question: '销售口径' }, steps: [], answer: { status: 'ANSWERED', claims: [{ text: '受控依据', citations: [] }], limitations: [] } })
  return { state, component, calls, identity, run }
}

module.exports = { environment, chatHarness, cardHarness, receiptContext, request, receipt, task, plain, serverError, tick, until, C, R, RUN, PREVIEW }
