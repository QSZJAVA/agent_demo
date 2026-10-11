/** 规则历史的范围与窗口生命周期回归；使用真实 Vue 状态，受控只读响应验证乱序、失败和恢复。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue')
Vue.config.productionTip = false

function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function setup() {
  const requests = []
  const source = fs.readFileSync(path.join(__dirname, '../src/views/RuleAdmin.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const context = { result: null, fetchRuleHistory: (reportId, companyCode) => {
    const request = { reportId, companyCode, ...deferred() }; requests.push(request); return request.promise
  } }
  vm.runInNewContext(source, context)
  delete context.result.created
  const state = new Vue(context.result)
  state.catalog = [{ reportId: 'sales', reportName: '销售' }, { reportId: 'expense', reportName: '费用' }]
  return { state, requests }
}
const scope = (reportId, companyCode) => ({ reportId, companyCode })

test('规则历史 A/B 乱序不会把 A 的内容显示在 B 的标题下', async () => {
  const { state, requests } = setup()
  const first = state.showHistory(scope('sales', 'A')), latest = state.showHistory(scope('expense', 'B'))
  requests[1].resolve([{ expression: 'expenseAmount > 200' }]); await latest
  requests[0].resolve([{ expression: 'salesAmount > 100' }]); await first
  assert.equal(state.history.title, '费用 / B 变更历史')
  assert.equal(state.history.list[0].expression, 'expenseAmount > 200')
  state.$destroy()
})

test('切换规则历史清空旧事实，旧请求失败不结束新范围读取', async () => {
  const { state, requests } = setup()
  const initial = state.showHistory(scope('sales', 'A'))
  requests[0].resolve([{ expression: 'old' }]); await initial
  const old = state.showHistory(scope('sales', 'A')), latest = state.showHistory(scope('expense', 'B'))
  assert.equal(state.history.list.length, 0)
  requests[1].reject(new Error('old failure')); await old
  assert.equal(state.history.loading, true)
  assert.equal(state.history.error, '')
  assert.equal(state.history.list.length, 0)
  requests[2].resolve([{ expression: 'current' }]); await latest
  assert.equal(state.history.list[0].expression, 'current')
  assert.equal(state.history.loading, false)
  state.$destroy()
})

test('关闭后重开相同规则范围，旧历史不能填入新窗口', async () => {
  const { state, requests } = setup()
  const old = state.showHistory(scope('sales', 'A'))
  // 用户只能关闭已经渲染的抽屉，先让 Vue 完成本轮显隐观察，再模拟关闭操作。
  await Vue.nextTick()
  state.history.visible = false; await Vue.nextTick()
  const latest = state.showHistory(scope('sales', 'A'))
  requests[0].resolve([{ expression: 'stale' }]); await old
  assert.equal(state.history.visible, true)
  assert.equal(state.history.list.length, 0)
  assert.equal(state.history.loading, true)
  requests[1].resolve([{ expression: 'current' }]); await latest
  assert.equal(state.history.list[0].expression, 'current')
  state.$destroy()
})

test('当前规则历史失败保留原范围，重试成功后清除错误', async () => {
  const { state, requests } = setup()
  const original = scope('sales', '*'), initial = state.showHistory(original)
  original.reportId = 'expense'; original.companyCode = 'B'
  requests[0].reject(new Error('offline')); await initial
  assert.equal(state.history.title, '销售 / 通配 变更历史')
  assert.match(state.history.error, /读取失败/)
  assert.equal(state.history.list.length, 0)
  assert.equal(state.history.loading, false)
  const retry = state.showHistory(state.history.target)
  assert.equal(requests[1].reportId, 'sales')
  assert.equal(requests[1].companyCode, '*')
  requests[1].resolve([{ expression: 'recovered' }]); await retry
  assert.equal(state.history.error, '')
  assert.equal(state.history.list[0].expression, 'recovered')
  state.$destroy()
})

test('关闭历史后迟到成功或失败都不能重新打开窗口', async () => {
  for (const failure of [false, true]) {
    const { state, requests } = setup(), pending = state.showHistory(scope('sales', 'A'))
    await Vue.nextTick()
    state.history.visible = false; await Vue.nextTick()
    if (failure) requests[0].reject(new Error('after close'))
    else requests[0].resolve([{ expression: 'after close' }])
    await pending
    assert.equal(state.history.visible, false)
    assert.equal(state.history.list.length, 0)
    assert.equal(state.history.error, '')
    assert.equal(state.history.target, null)
    state.$destroy()
  }
})

test('离开规则页面后忽略历史回包，也不再发出新的历史请求', async () => {
  const { state, requests } = setup(), pending = state.showHistory(scope('sales', 'A'))
  state.$destroy()
  requests[0].resolve([{ expression: 'after destroy' }]); await pending
  await state.showHistory(scope('expense', 'B'))
  assert.equal(requests.length, 1)
  assert.equal(state.history.list.length, 0)
  assert.equal(state.history.visible, false)
  assert.equal(state.history.loading, false)
})
