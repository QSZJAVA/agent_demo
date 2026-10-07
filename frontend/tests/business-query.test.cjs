/** 业务查询卡片的金额精度、分组、页码与只读交互测试；执行真实组件逻辑，不代替浏览器验收。 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const text = fs.readFileSync(path.join(__dirname, '../src/components/agent/BusinessQueryCard.vue'), 'utf8')
const sandbox = { result: null }
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/utils/presentation.js'), 'utf8').replace(/export function/g, 'function'), sandbox)
vm.runInNewContext(text.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import[^\n]*from[^\n]*/g, '').replace('export default', 'result ='), sandbox)
const component = sandbox.result
function state(domain = 'REPORT') {
  const s = { payload: { query: { domain, view: 'LIST', page: 3, size: 20 }, observedAt: '2026-10-06T12:30:00+08:00', columns: ['docNo', 'amount', 'status', 'companyCode', 'recordId'].map(name => ({ name, type: 'string', description: name })) } }
  for (const [name, method] of Object.entries(component.methods)) s[name] = method.bind(s)
  return s
}
test('金额和大整数标识始终保留服务端字符串精度', () => {
  const s = state()
  assert.equal(s.cell('9007199254740993.01'), '9007199254740993.01')
  assert.equal(s.cell('9007199254740993'), '9007199254740993')
  assert.equal(s.cell(null), '—')
})
test('展示序号在分页后保持全局位置', () => {
  const s = state()
  assert.equal(s.rowIndex(0), 41)
  assert.equal(s.rowIndex(19), 60)
})
test('动态报表字段不重复展示且隐藏内部标识列', () => {
  const columns = component.computed.displayColumns.call(state())
  assert.equal(columns.filter(c => c.name === 'amount').length, 1)
  assert.equal(columns.some(c => c.name === 'recordId'), false)
})
test('总结使用服务器计数而不是当前页数组长度', () => {
  assert.equal(state().counts({ 已完成: 200, 待审批: 30 }), '已完成 200 条；待审批 30 条')
  assert.match(text, /payload\.summary\.amountsByCurrency/)
  assert.match(text, /payload\.total/)
})
test('状态翻译保留待核对语义，业务分组名称不作为状态翻译', () => {
  const s = state()
  assert.equal(s.counts({ UNKNOWN: 3, EXECUTED: 2, SUCCESS: 1 }, 'status'), '结果待核对 3 条；已执行 2 条；成功 1 条')
  assert.equal(s.counts({ SUCCESS: 4 }), 'SUCCESS 4 条')
  assert.equal(sandbox.statusTone('EXECUTED'), 'info')
  assert.equal(sandbox.statusTone('UNKNOWN'), 'warning')
})
test('流程文本通过模板插值渲染，没有执行按钮或不可信HTML入口', () => {
  assert.doesNotMatch(text, /v-html|confirmPlan|dispatch_submit|approveOrder/)
  assert.match(text, /!latest/)
  assert.match(text, /step\.status === '处理中'/)
})
