/** 目录历史及回滚目标隔离回归；使用真实 Vue 生命周期，接口只控制响应先后，不执行业务修改。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue')
Vue.config.productionTip = false
const row = (id, version = 8) => ({ reportId: id, reportName: `报表 ${id}`, catalogVersion: version })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function setup(overrides = {}) {
  const requests = [], changes = []
  const source = fs.readFileSync(path.join(__dirname, '../src/views/CatalogAdmin.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const context = { result: null, ops: {
    catalogHistory: id => { const request = { id, ...deferred() }; requests.push(request); return request.promise },
    rollbackCatalog: async (id, body) => changes.push({ id, ...body }), ...overrides
  } }
  vm.runInNewContext(source, context)
  delete context.result.created
  const state = new Vue(context.result)
  state.load = async () => {}
  state.$prompt = async () => ({ value: '核对历史后回滚' })
  return { state, requests, changes }
}

test('目录 A/B 历史乱序不会把 A 版本展示或回滚到 B', async () => {
  const { state, requests, changes } = setup()
  const first = state.history(row('A')), latest = state.history(row('B'))
  const a = { catalog_version: 2, created_by: 'A-owner' }, b = { catalog_version: 3, created_by: 'B-owner' }
  requests[1].resolve([b]); await latest
  requests[0].resolve([a]); await first
  assert.equal(state.historyTarget.reportId, 'B')
  assert.equal(state.revisions[0].created_by, 'B-owner')
  await state.rollback(a)
  assert.equal(changes.length, 0)
  await state.rollback(b)
  assert.equal(changes.length, 1)
  assert.equal(changes[0].id, 'B')
  assert.equal(changes[0].targetVersion, 3)
  state.$destroy()
})

test('旧历史失败不结束最新加载，也不留下上一报表的版本', async () => {
  const { state, requests } = setup()
  const old = state.history(row('A')), latest = state.history(row('B'))
  requests[0].reject(new Error('old failure')); await old
  assert.equal(state.historyLoading, true)
  assert.equal(state.historyError, '')
  assert.equal(state.revisions.length, 0)
  requests[1].resolve([{ catalog_version: 7 }]); await latest
  assert.equal(state.historyLoading, false)
  state.$destroy()
})

test('关闭再打开同一报表时旧响应不能重新打开窗口或填入新历史', async () => {
  const { state, requests } = setup()
  const old = state.history(row('A'))
  state.historyVisible = false; await Vue.nextTick()
  const latest = state.history(row('A'))
  requests[0].resolve([{ catalog_version: 1 }]); await old
  assert.equal(state.revisions.length, 0)
  assert.equal(state.historyLoading, true)
  requests[1].resolve([{ catalog_version: 7 }]); await latest
  assert.equal(state.revisions[0].catalog_version, 7)
  state.$destroy()
})

test('失败历史必须成功重读才允许回滚，不能使用以前展示的行', async () => {
  const { state, requests, changes } = setup()
  const load = state.history(row('A'))
  requests[0].reject(new Error('offline')); await load
  assert.match(state.historyError, /读取失败/)
  await state.rollback({ catalog_version: 1 })
  assert.equal(changes.length, 0)
  const retry = state.history(state.historyTarget)
  const version = { catalog_version: 4 }
  requests[1].resolve([version]); await retry
  assert.equal(state.historyError, '')
  await state.rollback(version)
  assert.equal(changes[0].id, 'A')
  state.$destroy()
})

test('回滚确认期间换报表、关窗或销毁均不能发出修改', async () => {
  for (const action of ['switch', 'close', 'destroy']) {
    const { state, requests, changes } = setup()
    const load = state.history(row('A')); const version = { catalog_version: 2 }
    requests[0].resolve([version]); await load
    const answer = deferred(); state.$prompt = () => answer.promise
    const pending = state.rollback(version)
    if (action === 'switch') {
      const next = state.history(row('B')); requests[1].resolve([{ catalog_version: 2 }]); await next
    } else if (action === 'close') { state.historyVisible = false; await Vue.nextTick() }
    else state.$destroy()
    answer.resolve({ value: '原报表的确认' }); await pending
    assert.equal(changes.length, 0, action)
    assert.equal(state.rollbackBusy, false)
    if (action !== 'destroy') state.$destroy()
  }
})

test('别名选择和最新目录对象变化不能更改历史回滚的原目标与期望版本', async () => {
  const { state, requests, changes } = setup()
  const original = row('A', 8), version = { catalog_version: 2 }
  const load = state.history(original); requests[0].resolve([version]); await load
  const answer = deferred(); let prompt = ''
  state.$prompt = text => { prompt = text; return answer.promise }
  const pending = state.rollback(version)
  state.selected = row('B', 99)
  original.catalogVersion = 12
  answer.resolve({ value: '明确选择 A 的版本 2' }); await pending
  assert.match(prompt, /报表 A/)
  assert.equal(changes[0].id, 'A')
  assert.equal(changes[0].expectedVersion, 8)
  state.$destroy()
})

test('重复点击与取消回滚不会重复提交，销毁后忽略迟到历史', async () => {
  const { state, requests, changes } = setup()
  const load = state.history(row('A')), version = { catalog_version: 2 }
  requests[0].resolve([version]); await load
  const answer = deferred(); let prompts = 0
  state.$prompt = () => { prompts++; return answer.promise }
  const pending = state.rollback(version)
  await state.rollback(version)
  answer.reject(new Error('cancel')); await pending
  assert.equal(prompts, 1)
  assert.equal(changes.length, 0)
  const later = state.history(row('B')); state.$destroy()
  requests[1].resolve([{ catalog_version: 6 }]); await later
  assert.equal(state.revisions.length, 0)
})
