const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')

function storage() {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) }
}
function auth(localStorage = storage()) {
  const events = []
  const context = vm.createContext({ localStorage, sessionStorage: storage(), Event: class { constructor(type) { this.type = type } }, window: { dispatchEvent: event => events.push(event.type) } })
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/auth.js'), 'utf8').replaceAll('export function', 'function'), context)
  return { context, events }
}
test('authenticated requests use a session token without the simulated identity header', () => {
  const { context } = auth()
  context.saveSession('session-value', { userId: 'reader' })
  assert.equal(context.authHeaders().Authorization, 'Bearer session-value')
  assert.equal(context.authHeaders()['X-User-Id'], undefined)
  assert.equal(context.localStorage.getItem('report.sessionToken'), null)
})
test('another browser tab cannot replace the identity associated with this tab session', () => {
  const shared = storage()
  const a = auth(shared).context
  const b = auth(shared).context
  a.saveSession('session-a', { userId: 'reader-a' })
  b.saveSession('session-b', { userId: 'reader-b' })
  assert.equal(a.getCurrentUserId(), 'reader-a')
  assert.equal(b.getCurrentUserId(), 'reader-b')
  assert.equal(a.getSessionToken(), 'session-a')
})
test('session expiry clears credentials and notifies the login screen', () => {
  const { context, events } = auth()
  context.saveSession('session-value', { userId: 'reader' })
  context.sessionExpired()
  assert.equal(context.getSessionToken(), null)
  assert.equal(context.sessionStorage.getItem('report.sessionUser'), null)
  assert.deepEqual(events, ['session-expired'])
})

test('old and anonymous requests cannot expire a newer session', () => {
  const { context, events } = auth()
  context.saveSession('new-session', { userId: 'new-user' })
  assert.equal(context.sessionExpired('old-session'), false)
  assert.equal(context.sessionExpired(null), false)
  assert.equal(context.getSessionToken(), 'new-session')
  assert.deepEqual(events, [])
  assert.equal(context.sessionExpired('new-session'), true)
  assert.equal(context.getSessionToken(), null)
  assert.deepEqual(events, ['session-expired'])
})

function httpAuth() {
  const state = auth()
  let request, success, failure
  state.context.axios = { create: () => ({ interceptors: {
    request: { use: fn => { request = fn } },
    response: { use: (ok, err) => { success = ok; failure = err } }
  } }) }
  state.context.Message = { error() {} }
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/api/http.js'), 'utf8')
    .replace(/^import .*$/gm, '').replace('export default http', ''), state.context)
  return { ...state, request, success, failure }
}
for (const mode of ['http', 'business']) {
  test(`${mode} 401 only expires the session associated with that request`, async () => {
    const { context, events, request, success, failure } = httpAuth()
    context.saveSession('old-session', { userId: 'old-user' })
    const old = request({ headers: {} })
    context.saveSession('new-session', { userId: 'new-user' })
    const reject = config => mode === 'http'
      ? failure({ config, response: { status: 401 }, message: 'expired' })
      : success({ config, data: { code: 401, message: 'expired' } })
    await assert.rejects(reject(old))
    assert.equal(context.getSessionToken(), 'new-session'); assert.deepEqual(events, [])
    await assert.rejects(reject(request({ headers: {} })))
    assert.equal(context.getSessionToken(), null); assert.deepEqual(events, ['session-expired'])
  })
}

for (const code of [401, 200]) {
  test(`late SSE authentication error HTTP ${code} cannot clear a new login`, async () => {
    const { context, events } = auth()
    let finish
    context.AbortController = AbortController
    context.fetch = () => new Promise(resolve => { finish = resolve })
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/api/agent.js'), 'utf8')
      .replace(/^import .*$/gm, '').replaceAll('export function', 'function'), context)
    context.saveSession('old-session', { userId: 'old-user' })
    const stream = context.streamChat({ message: 'test' }, () => {})
    context.saveSession('new-session', { userId: 'new-user' })
    finish({ ok: code === 200, status: code, headers: { get: () => 'application/json' },
      json: async () => ({ code: 401, message: 'expired' }) })
    await assert.rejects(stream.promise)
    assert.equal(context.getSessionToken(), 'new-session'); assert.deepEqual(events, [])
  })
}
