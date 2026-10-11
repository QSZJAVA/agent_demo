/** 真实Vue状态下的词典发布、迟到响应及反馈重放回归；另验证条件编辑保留精确值和逻辑结构。 */
const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('../node_modules/vue/dist/vue.common.js')
const source = fs.readFileSync(path.join(__dirname, '../src/views/SemanticQuality.vue'), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/import[^\n]*from[^\n]*/g, '').replace('export default', 'result =')
const definition = () => ({ companyName: '', companyAliases: [], timeZone: 'Asia/Shanghai', fiscalStartMonth: 1, holidays: [], fields: [] })
const scope = { domain: 'REPORT', companyCode: 'A', reportId: 'rpt-sales-order' }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const flush = () => new Promise(setImmediate)
function setup(apis = {}) {
  const identity = { user: 'u1', token: 'session1' }
  const context = { result: null, crypto: require('node:crypto').webcrypto, getCurrentUserId: () => identity.user, getSessionToken: () => identity.token,
    fetchSemanticRuns: async () => ({ records: [], nextCursor: null }), fetchSemanticCatalog: async () => ({ companies: ['A'], reports: [{ reportId: scope.reportId, reportName: '销售报表' }], admin: true }),
    fetchDictionary: async requested => ({ entry: { scope: requested, version: 2, definition: definition() }, fields: [{ name: 'amount', type: 'decimal', description: '金额' }] }),
    fetchDictionaryHistory: async () => [], fetchSemanticRun: async id => ({ eventId: id, diagnostic: {} }), fetchSemanticFeedback: async () => ({ records: [], nextCursor: null }), submitSemanticFeedback: async () => ({}),
    validateDictionary: async () => ({}), publishDictionary: async () => ({}), ...apis }
  vm.runInNewContext(source, context); delete context.result.created
  const state = new Vue(context.result); state.catalog = { companies: ['A', 'B'], reports: [{ reportId: scope.reportId, reportName: '销售报表' }], admin: true }; state.scope = { ...scope }
  state.$confirm = async () => {}; return { state, identity, component: context.result }
}
async function loaded(apis) { const result = setup(apis); await result.state.loadDictionary(); result.state.reason = '经业务核实'; await Vue.nextTick(); return result }

test('dictionary late replies never cross company scope or release another request loading state', async () => {
  const requests = []; const { state } = setup({ fetchDictionary: scope => { const d = deferred(); requests.push({ ...d, scope }); return d.promise } })
  const first = state.loadDictionary(); state.scope.companyCode = 'B'; const second = state.loadDictionary()
  requests[0].resolve({ entry: { version: 1, definition: definition(), scope: requests[0].scope }, fields: [{ name: 'private' }] }); await first
  assert.equal(state.entry, null); assert.equal(state.dictionaryLoading, true)
  requests[1].resolve({ entry: { version: 4, definition: definition(), scope: requests[1].scope }, fields: [{ name: 'amount' }] }); await second
  assert.equal(state.entry.scope.companyCode, 'B'); assert.equal(state.entry.version, 4); state.$destroy()
})
test('failed dictionary refresh clears the editable old definition', async () => {
  let failing = false; const { state } = await loaded({ fetchDictionary: async () => { if (failing) throw new Error('revoked'); return { entry: { version: 2, definition: definition() }, fields: [] } } })
  failing = true; await state.loadDictionary(); assert.equal(state.entry, null); assert.equal(state.definition, null); assert.match(state.dictionaryError, /revoked/); state.$destroy()
})
test('publish confirmation is pinned to scope and never submits after switching company', async () => {
  const confirm = deferred(), calls = []; const { state } = await loaded({ publishDictionary: async (...args) => calls.push(args) }); state.$confirm = () => confirm.promise
  const pending = state.publish(); state.scope.companyCode = 'B'; await state.loadDictionary(); confirm.resolve(); await pending
  assert.equal(calls.length, 0); assert.equal(state.publishing, false); state.$destroy()
})
test('publish confirmation cannot silently submit an edited definition or new version', async () => {
  const confirm = deferred(), calls = []; const { state } = await loaded({ publishDictionary: async (...args) => calls.push(args) }); state.$confirm = () => confirm.promise
  const pending = state.publish(); state.definition.companyName = 'new name'; await Vue.nextTick(); confirm.resolve(); await pending
  assert.equal(calls.length, 0); state.$destroy()
})
test('valid publish uses the captured version and reloads the published scope', async () => {
  const calls = []; const { state } = await loaded({ publishDictionary: async (...args) => calls.push(JSON.parse(JSON.stringify(args))) }); await state.publish()
  assert.equal(calls.length, 1); assert.equal(calls[0][0].companyCode, 'A'); assert.equal(calls[0][1].expectedVersion, 2); assert.equal(calls[0][1].reason, '经业务核实'); state.$destroy()
})
test('validation results expire on either definition or reason edits without leaving a spinner', async () => {
  for (const target of ['definition', 'reason']) {
    const request = deferred(); const { state } = await loaded({ validateDictionary: () => request.promise }); const pending = state.checkDefinition()
    if (target === 'definition') state.definition.companyName = 'changed'; else state.reason = 'new reason'
    await Vue.nextTick(); request.resolve(); await pending
    assert.equal(state.checkMessage, ''); assert.equal(state.checking, false); state.$destroy()
  }
})
test('history closing and reopening ignores the previous result and binds the title', async () => {
  const requests = []; const { state } = await loaded({ fetchDictionaryHistory: () => { const d = deferred(); requests.push(d); return d.promise } })
  const first = state.openHistory(); state.closeHistory(); state.scope.companyCode = 'B'; const second = state.openHistory()
  requests[0].resolve([{ version: 99 }]); await first; assert.equal(state.historyRows.length, 0); assert.match(state.historyTitle, /^B/)
  requests[1].resolve([{ version: 3 }]); await second; assert.equal(state.historyRows[0].version, 3); state.$destroy()
})
test('a different login or disposal prevents late diagnostic and dictionary publication', async () => {
  const request = deferred(); const { state, identity } = setup({ fetchSemanticRuns: () => request.promise }); const pending = state.loadRuns(null); identity.token = 'other'
  request.resolve({ records: [{ eventId: 'private' }], nextCursor: '1' }); await pending; assert.equal(state.rows.length, 0); state.$destroy()
})
test('feedback retries reuse the request key and changed text receives a new key', async () => {
  const calls = []; let fail = true; const { state } = setup({ submitSemanticFeedback: async (id, payload) => { calls.push({ id, ...payload }); if (fail) throw new Error('disconnected') } })
  await state.openRun('123'); state.feedbackNote = '原意是保留下界'; await state.sendFeedback(); fail = false; await state.sendFeedback()
  assert.equal(calls[0].feedbackId, calls[1].feedbackId); assert.equal(calls[0].id, '123')
  state.feedbackNote = '另一项说明'; await state.sendFeedback(); assert.notEqual(calls[1].feedbackId, calls[2].feedbackId); state.$destroy()
})
test('late feedback pages cannot append to a newly opened diagnostic', async () => {
  const next = deferred(); const { state } = setup({ fetchSemanticFeedback: (id, before) => before ? next.promise : Promise.resolve({ records: [{ eventId: id + '-feedback' }], nextCursor: '10' }) })
  await state.openRun('123'); const pending = state.moreFeedback(); await state.openRun('456'); next.resolve({ records: [{ eventId: 'private-old' }], nextCursor: null }); await pending
  assert.equal(state.feedbackRows.length, 1); assert.equal(state.feedbackRows[0].eventId, '456-feedback'); assert.equal(state.feedbackCursor, '10'); state.$destroy()
})
const helpers = { result: null }
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/utils/queryConditions.js'), 'utf8').replace(/export /g, '') + '\nresult = { buildQueryEdit, editableGroups, operatorsFor };', helpers)
test('condition edits preserve large decimals, textual IDs, both interval ends and OR groups', () => {
  const columns = [{ name: 'amount', type: 'decimal' }, { name: 'docNo', type: 'string' }]
  const groups = [{ allOf: [{ field: 'amount', operator: 'GTE', text: '9007199254740993.01' }, { field: 'amount', operator: 'LTE', text: '9999999999999999.99' }] }, { allOf: [{ field: 'docNo', operator: 'IN', text: '001\n002' }] }]
  const value = helpers.result.buildQueryEdit('a'.repeat(32), groups, columns)
  assert.equal(value.conditions.length, 2); assert.equal(value.conditions[0].allOf[0].values[0], '9007199254740993.01'); assert.equal(value.conditions[1].allOf[0].values[0], '001')
  value.conditions[0].allOf[0].values[0] = 'changed'; assert.equal(groups[0].allOf[0].text, '9007199254740993.01')
})
test('condition edits distinguish clearing all conditions, an invalid empty group and NULL', () => {
  const h = helpers.result, columns = [{ name: 'amount', type: 'decimal' }], ref = 'a'.repeat(32)
  assert.equal(h.buildQueryEdit(ref, [], columns).conditions.length, 0)
  assert.throws(() => h.buildQueryEdit(ref, [{ allOf: [] }], columns))
  const value = h.buildQueryEdit(ref, [{ allOf: [{ field: 'amount', operator: 'IS_NULL', text: 'old text' }] }], columns); assert.equal(value.conditions[0].allOf[0].values.length, 0)
  assert.throws(() => h.buildQueryEdit(ref, [{ allOf: [{ field: 'unknown', operator: 'EQ', text: '1' }] }], columns))
  assert.equal(h.operatorsFor('unsupported').length, 0)
})
