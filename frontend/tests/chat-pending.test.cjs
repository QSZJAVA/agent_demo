/** 聊天保存边界使用真实WebCrypto；受控本地存储验证完整请求、领域隔离、损坏拒绝与身份变更，不代表真实模型验收。 */
const { test } = require('node:test'), assert = require('node:assert/strict')
const { environment, request, plain, PREVIEW, R } = require('./helpers/unified-chat-harness.cjs')

test('首次请求完整保存并回读，改动外部表单不能改变已保存的选择或精确数值', async () => {
  const env = environment(), body = { ...request(), conversationId: R, previewId: PREVIEW, excludedRecords: [{ reportId: 'rpt-sales-order', recordId: '001' }], queryEdit: { queryRef: R, conditions: [{ allOf: [{ field: 'amount', operator: 'GTE', values: ['90071992547409.93'] }] }] } }
  const original = plain(body), saved = await env.store().create(body, 'chat-original-submit')
  body.excludedRecords[0].recordId = 'wrong'; body.queryEdit.conditions[0].allOf[0].values[0] = '0'
  assert.deepEqual(plain(saved.request), original); assert.deepEqual(plain((await env.store().read()).pending.request), original)
  assert.equal([...env.values.keys()][0].includes(env.identity.userId), false); assert.equal([...env.values.values()][0].includes(env.identity.token), false)
})

test('完整查询条件与5000项排除记录超过分析页预算时仍不截断', async () => {
  const env = environment(), body = request()
  body.previewId = PREVIEW; body.excludedRecords = Array.from({ length: 5000 }, (_, index) => ({ reportId: 'rpt-sales-order', recordId: String(index) }))
  body.queryEdit = { queryRef: R, conditions: Array.from({ length: 8 }, () => ({ allOf: Array.from({ length: 8 }, () => ({ field: 'docNo', operator: 'IN', values: Array.from({ length: 20 }, (_, index) => `${index}`.padStart(40, '0')) })) })) }
  assert.ok(JSON.stringify(body).length > 16384)
  const saved = await env.store().create(body, 'chat-large-submit')
  assert.deepEqual(plain(saved.request), body); await env.store().verify(saved)
})

test('旧请求字段、数值自动转换、损坏引用和编辑澄清并用均拒绝发送', async () => {
  const variants = [
    { unexpected: 'field' }, { conversationId: R + '\n' }, { previewId: 'old-preview' }, { message: '' },
    { queryEdit: { queryRef: R, conditions: [{ allOf: [{ field: 'amount', operator: 'EQ', values: [1] }] }] } },
    { queryEdit: { queryRef: R, conditions: [], extra: true } },
    { queryEdit: { queryRef: R, conditions: [] }, clarificationReply: { clarificationRef: R, value: 'A' } },
    { clarificationReply: { clarificationRef: R, value: 1 } }
  ]
  for (const variant of variants) {
    const env = environment()
    await assert.rejects(env.store().create({ ...request(), ...variant }, 'chat-invalid-submit'), error => error.pendingFailure === 'CORRUPT')
    assert.equal(env.values.size, 0)
  }
})

test('聊天和分析保存记录互不删除，换token只丢弃该领域旧登录的问题', async () => {
  const env = environment()
  await env.analysisStore().create({ companyCode: 'A', reportId: 'rpt-sales-order', question: '业务知识', mode: 'KNOWLEDGE_ONLY', snapshotId: null }, 'analysis-current')
  const oldStore = env.store(), saved = await oldStore.create(request(), 'chat-before-login')
  env.values.set('report.sessionToken', 'unrelated-value'); env.identity.token = 'test-session-b'
  await assert.rejects(oldStore.verify(saved), error => error.pendingFailure === 'SESSION_CHANGED')
  const next = await env.store().read(); assert.equal(next.pending, null); assert.match(next.notice, /其他登录会话/)
  assert.equal([...env.values.keys()].filter(key => key.startsWith('report.analysis.pending.')).length, 1)
  assert.equal(env.values.get('report.sessionToken'), 'unrelated-value')
})

test('过期、未来时间和签名损坏不能用于原key重放', async () => {
  for (const change of [env => { env.clock.now += 86400000 }, env => { env.clock.now-- }, env => { const [key, raw] = [...env.values.entries()][0], record = JSON.parse(raw); record.request.message = '被改写'; env.values.set(key, JSON.stringify(record)) }]) {
    const env = environment(), store = env.store(), saved = await store.create(request(), 'chat-expiring-key')
    change(env); await assert.rejects(store.verify(saved), error => error.pendingFailure === 'INVALIDATED'); assert.equal(env.values.size, 0)
  }
})
