/** 规则编辑器字段、校验和保存的生命周期回归；服务响应可控，业务输入和 Vue 观察逻辑使用真实实现。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue')
Vue.config.productionTip = false
const draft = reportId => ({ reportId, companyCode: 'A', expression: 'amount > 100', name: '', description: '' })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function setup(apis = {}) {
  const source = fs.readFileSync(path.join(__dirname, '../src/views/RuleAdmin.vue'), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
  const context = { result: null, fetchRuleFields: async () => [], validateRule: async () => ({ variables: ['amount'] }), ...apis }
  vm.runInNewContext(source, context)
  delete context.result.created
  const state = new Vue(context.result), notices = []
  state.$message = { success: message => notices.push(message) }
  state.load = async () => {}
  state.openEditor(draft('sales'), false)
  return { state, notices }
}

test('字段乱序只展示当前报表，旧字段不能被插入当前表达式', async () => {
  const requests = []
  const { state } = setup({ fetchRuleFields: id => { const request = { id, ...deferred() }; requests.push(request); return request.promise } })
  await Vue.nextTick()
  state.editor.reportId = 'expense'; await Vue.nextTick()
  assert.equal(requests.length, 2)
  requests[1].resolve([{ name: 'expenseType' }]); await new Promise(setImmediate)
  requests[0].resolve([{ name: 'productName' }]); await new Promise(setImmediate)
  assert.equal(state.fields[0].name, 'expenseType')
  state.insertField('productName')
  assert.equal(state.editor.expression, 'amount > 100')
  state.insertField('expenseType')
  assert.equal(state.editor.expression, 'amount > 100 expenseType')
  state.$destroy()
})

test('旧字段请求失败不能解除新请求加载状态，当前失败可明确重读', async () => {
  const requests = []
  const { state } = setup({ fetchRuleFields: () => { const request = deferred(); requests.push(request); return request.promise } })
  await Vue.nextTick()
  state.editor.reportId = 'expense'; await Vue.nextTick()
  requests[0].reject(new Error('old failure')); await new Promise(setImmediate)
  assert.equal(state.fieldsState.loading, true)
  assert.equal(state.fieldsState.error, '')
  requests[1].reject(new Error('offline')); await new Promise(setImmediate)
  assert.equal(state.fieldsState.loading, false)
  assert.match(state.fieldsState.error, /读取失败/)
  const retry = state.loadFields()
  requests[2].resolve([{ name: 'expenseType' }]); await retry
  assert.equal(state.fieldsState.error, '')
  assert.equal(state.fields[0].name, 'expenseType')
  state.$destroy()
})

test('关闭重开同一规则与销毁都会使旧字段响应失效', async () => {
  const requests = []
  const { state } = setup({ fetchRuleFields: () => { const request = deferred(); requests.push(request); return request.promise } })
  await Vue.nextTick()
  state.editor.visible = false; await Vue.nextTick()
  state.openEditor(draft('sales'), false); await Vue.nextTick()
  requests[0].resolve([{ name: 'oldField' }]); await new Promise(setImmediate)
  assert.equal(state.fields.length, 0)
  assert.equal(state.fieldsState.loading, true)
  state.$destroy()
  requests[1].resolve([{ name: 'afterDestroy' }]); await new Promise(setImmediate)
  assert.equal(state.fields.length, 0)
})

test('语法校验结果绑定报表、公司和表达式，改完输入不再显示旧成功', async () => {
  for (const [key, value] of [['expression', 'amount >'], ['companyCode', 'B'], ['reportId', 'expense']]) {
    const response = deferred(), { state } = setup({ validateRule: () => response.promise })
    await Vue.nextTick()
    const pending = state.validate()
    state.editor[key] = value
    response.resolve({ variables: ['amount'] }); await pending; await Vue.nextTick()
    assert.equal(state.validation.message, '', key)
    assert.equal(state.validation.loading, false)
    state.$destroy()
  }
})

test('当前成功校验随输入变化立即失效，失败重试不会保留成功提示', async () => {
  let failure = false
  const { state } = setup({ validateRule: async () => { if (failure) throw new Error('invalid'); return { variables: ['amount'] } } })
  await state.validate()
  assert.match(state.validation.message, /语法正确/)
  state.editor.expression = 'amount >'; await Vue.nextTick()
  assert.equal(state.validation.message, '')
  failure = true; await state.validate()
  assert.equal(state.validation.error, true)
  assert.doesNotMatch(state.validation.message, /语法正确/)
  state.editor.expression = 'amount > 20'; await Vue.nextTick()
  failure = false; await state.validate()
  assert.equal(state.validation.error, false)
  assert.match(state.validation.message, /语法正确/)
  state.$destroy()
})

test('校验乱序时旧失败不解除新校验加载，同输入新窗口仍拒绝旧成功', async () => {
  const requests = []
  const { state } = setup({ validateRule: () => { const request = deferred(); requests.push(request); return request.promise } })
  await Vue.nextTick()
  const old = state.validate(), latest = state.validate()
  requests[0].reject(new Error('old failure')); await old
  assert.equal(state.validation.loading, true)
  assert.equal(state.validation.message, '')
  state.openEditor(draft('sales'), false)
  requests[1].resolve({ variables: ['amount'] }); await latest
  assert.equal(state.validation.message, '')
  const after = state.validate(); state.$destroy()
  requests[2].resolve({ variables: ['amount'] }); await after
  assert.equal(state.validation.message, '')
})

test('关闭编辑器期间校验返回不能显示通过，重开后可重新校验', async () => {
  const requests = []
  const { state } = setup({ validateRule: () => { const request = deferred(); requests.push(request); return request.promise } })
  await Vue.nextTick()
  const old = state.validate(); state.editor.visible = false
  requests[0].resolve({ variables: ['amount'] }); await old; await Vue.nextTick()
  assert.equal(state.validation.message, '')
  state.openEditor(draft('sales'), false)
  const retry = state.validate(); requests[1].resolve({ variables: ['amount'] }); await retry
  assert.match(state.validation.message, /语法正确/)
  state.$destroy()
})

test('保存旧草稿的迟到回包不能关闭或解除新编辑器的保存状态', async () => {
  const saved = deferred(), { state, notices } = setup({ saveDraft: () => saved.promise })
  await Vue.nextTick()
  const oldEditor = state.editor, pending = state.saveDraftForm()
  state.openEditor(draft('expense'), false)
  state.editor.saving = true
  saved.resolve({}); await pending
  assert.equal(state.editor.visible, true)
  assert.equal(state.editor.reportId, 'expense')
  assert.equal(state.editor.saving, true)
  assert.equal(oldEditor.saving, false)
  assert.equal(notices.length, 0)
  state.$destroy()
})
