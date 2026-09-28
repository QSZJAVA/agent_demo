const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue')
Vue.config.productionTip = false
function editor(api) {
  const source = fs.readFileSync(path.join(__dirname, '../src/views/RuleAdmin.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const sandbox = { result: null, dryRunRule: api, fetchRuleFields: async () => [] }
  vm.runInNewContext(source, sandbox)
  // Real Vue computed properties and watchers; only network/initial page loading is replaced.
  const component = sandbox.result
  delete component.created
  const state = new Vue(component)
  state.openEditor({ reportId: 'sales', companyCode: 'A', expression: 'amount > 100' }, false)
  return state
}
const result = n => ({ total: n, hitCount: n, samples: [] })
function deferred() {
  let resolve, reject
  const promise = new Promise((a, b) => { resolve = a; reject = b })
  return { promise, resolve, reject }
}
test('failed trial clears the old result and releases loading', async () => {
  let fail = false
  const state = editor(async () => { if (fail) throw new Error('422'); return result(2) })
  await state.runDryRun()
  assert.equal(state.dryRun.result.hitCount, 2)
  fail = true
  const pending = state.runDryRun()
  assert.equal(state.dryRun.result, null)
  await pending
  assert.equal(state.dryRun.result, null)
  assert.equal(state.dryRun.loading, false)
  state.$destroy()
})
test('editing report, company or expression invalidates displayed results', async () => {
  const state = editor(async () => result(2))
  for (const [key, value] of [['expression', 'amount > 200'], ['companyCode', 'B'], ['reportId', 'expense']]) {
    await state.runDryRun()
    state.editor[key] = value
    await Vue.nextTick()
    assert.equal(state.dryRun.result, null, key)
  }
  state.$destroy()
})
test('input changes during a request reject the late response', async () => {
  const d = deferred(), state = editor(() => d.promise)
  const pending = state.runDryRun()
  state.editor.expression = 'amount < 5'
  await Vue.nextTick()
  d.resolve(result(9))
  await pending
  assert.equal(state.dryRun.result, null)
  assert.equal(state.dryRun.loading, false)
  state.$destroy()
})
test('opening another editor invalidates old requests even with identical inputs', async () => {
  const d = deferred(), state = editor(() => d.promise)
  const pending = state.runDryRun()
  state.openEditor({ reportId: 'sales', companyCode: 'A', expression: 'amount > 100' }, false)
  d.resolve(result(8))
  await pending
  assert.equal(state.dryRun.result, null)
  state.$destroy()
})
test('an old failure cannot end loading or replace a newer result', async () => {
  const first = deferred(), second = deferred()
  let calls = 0
  const state = editor(() => ++calls === 1 ? first.promise : second.promise)
  const old = state.runDryRun(), latest = state.runDryRun()
  first.reject(new Error('late failure'))
  await old
  assert.equal(state.dryRun.loading, true)
  second.resolve(result(3))
  await latest
  assert.equal(state.dryRun.result.hitCount, 3)
  assert.equal(state.dryRun.loading, false)
  state.$destroy()
})
test('closing and destroying an editor prevent late results', async () => {
  for (const destroy of [false, true]) {
    const d = deferred(), state = editor(() => d.promise)
    const pending = state.runDryRun()
    if (destroy) state.$destroy()
    else { state.editor.visible = false; await Vue.nextTick() }
    d.resolve(result(7))
    await pending
    assert.equal(state.dryRun.result, null)
    assert.equal(state.dryRun.loading, false)
    if (!destroy) state.$destroy()
  }
})
