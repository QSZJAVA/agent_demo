const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
function setup() {
  const source = fs.readFileSync(path.join(__dirname, '../src/utils/pagedReport.js'), 'utf8').replace('export function', 'function')
  const sandbox = {}; vm.runInNewContext(source, sandbox)
  const pending = []
  const mixin = sandbox.pagedReport((page, size) => new Promise((resolve, reject) => pending.push({ page, size, resolve, reject })))
  const state = mixin.data()
  for (const [key, fn] of Object.entries(mixin.methods)) state[key] = fn.bind(state)
  return { state, pending, mixin }
}
test('out-of-order report pages never replace the latest requested rows', async () => {
  const { state, pending } = setup()
  const first = state.changePage(2), second = state.changePage(3)
  assert.equal(state.list.length, 0)
  pending[1].resolve({ records: [{ id: 101 }], total: 160 }); await second
  pending[0].resolve({ records: [{ id: 51 }], total: 160 }); await first
  assert.equal(state.list[0].id, 101); assert.equal(state.page, 3); assert.equal(state.loading, false)
})
test('deleted last page is reloaded at the last valid page', async () => {
  const { state, pending } = setup()
  const load = state.changePage(3)
  pending[0].resolve({ records: [], total: 2 }); await new Promise(setImmediate)
  assert.equal(pending[1].page, 1)
  pending[1].resolve({ records: [{ id: 1 }, { id: 2 }], total: 2 }); await load
  assert.equal(state.page, 1); assert.equal(state.list.length, 2); assert.equal(state.loading, false)
})
test('failed or disposed report requests cannot leave actionable stale rows', async () => {
  const { state, pending, mixin } = setup()
  state.list = [{ id: 1 }]
  const load = state.loadData(); assert.equal(state.list.length, 0)
  pending[0].reject(new Error('offline')); await load
  assert.equal(state.loading, false); assert.equal(state.total, 0)
  const late = state.loadData(); mixin.beforeDestroy.call(state)
  pending[1].resolve({ records: [{ id: 'old-account' }], total: 1 }); await late
  assert.equal(state.list.length, 0)
})
