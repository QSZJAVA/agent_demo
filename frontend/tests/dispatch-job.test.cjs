const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')

function harness(http, values = new Map()) {
  const warnings = []
  let token = 'current-session'
  const context = vm.createContext({
    http, getCurrentUserId: () => 'reader', getSessionToken: () => token,
    sessionStorage: { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value),
      removeItem: key => values.delete(key), get length() { return values.size }, key: index => [...values.keys()][index] },
    window: { crypto: require('node:crypto').webcrypto },
    Message: { warning: value => warnings.push(value) },
    setTimeout: fn => fn(), Uint32Array, Date
  })
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/api/dispatchJob.js'), 'utf8')
    .replace(/^import .*$/gm, '').replaceAll('export async function', 'async function'), context)
  return { context, values, warnings, changeSession: value => { token = value } }
}
const request = { planId: 'plan1', action: 'RETRY_FAILED' }
const done = key => ({ id: 'job1', status: 'SUCCEEDED', idempotencyKey: key, result: { planId: 'plan1', successCount: 1 } })

test('lost POST response keeps the same retry key through reload', async () => {
  const keys = [], values = new Map()
  const first = harness({ post: async (_, body, config) => { keys.push(config.headers['Idempotency-Key']); throw new Error('response lost') } }, values)
  await assert.rejects(first.context.runDispatchJob(request), /response lost/)
  const reopened = harness({ post: async (_, body, config) => { keys.push(config.headers['Idempotency-Key']); return done(keys[1]) } }, values)
  assert.equal((await reopened.context.runDispatchJob(request)).successCount, 1)
  assert.equal(keys[0], keys[1]); assert.equal(values.size, 0)
})

test('lost polling connection resumes by GET without another mutation POST', async () => {
  const values = new Map(); let posts = 0
  const first = harness({ post: async () => { posts++; return { id: 'job1', status: 'RUNNING' } }, get: async () => { throw new Error('offline') } }, values)
  await assert.rejects(first.context.runDispatchJob(request), /offline/)
  const reopened = harness({ post: async () => { posts++; throw new Error('must not POST') }, get: async () => done() }, values)
  assert.equal((await reopened.context.runDispatchJob(request)).successCount, 1)
  assert.equal(posts, 1); assert.equal(values.size, 0)
})

test('read-only card recovery clears its old key so a later explicit retry is a new action', async () => {
  const values = new Map(); let oldKey, newKey
  const first = harness({ post: async (_, body, config) => { oldKey = config.headers['Idempotency-Key']; return { id: 'job1', status: 'RUNNING' } }, get: async () => { throw new Error('offline') } }, values)
  await assert.rejects(first.context.runDispatchJob(request))
  const reopened = harness({ get: async () => done(oldKey), post: async (_, body, config) => { newKey = config.headers['Idempotency-Key']; return done(newKey) } }, values)
  await reopened.context.resumePlanAction('plan1'); assert.equal(values.size, 0)
  await reopened.context.runDispatchJob(request); assert.notEqual(oldKey, newKey)
})

test('a terminal failed task is shown and is never automatically resubmitted', async () => {
  let posts = 0
  const state = harness({ post: async () => { posts++; return { id: 'job1', status: 'FAILED', message: '结果待核对' } } })
  await assert.rejects(state.context.runDispatchJob(request), /结果待核对/)
  assert.equal(posts, 1); assert.deepEqual(state.warnings, ['结果待核对'])
})

test('late submission response cannot continue polling after the session changes', async () => {
  let release, gets = 0
  const state = harness({ post: () => new Promise(resolve => { release = resolve }), get: async () => { gets++; return done() } })
  const pending = state.context.runDispatchJob(request)
  state.changeSession('new-session'); release({ id: 'job1', status: 'RUNNING' })
  await assert.rejects(pending, /会话已变化/); assert.equal(gets, 0)
})

test('recovering a missing task performs no POST and explains the recovery boundary', async () => {
  let posts = 0
  const state = harness({ get: async () => null, post: async () => { posts++ } })
  await assert.rejects(state.context.resumePlanAction('plan1'), /未找到异步任务/)
  assert.equal(posts, 0); assert.equal(state.warnings.length, 1)
})
test('a session change during the terminal callback cannot return old results', async () => {
  const state = harness({ post: async () => done() })
  await assert.rejects(state.context.runDispatchJob(request, () => state.changeSession('new-session')), /会话已变化/)
})
