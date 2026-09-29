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
