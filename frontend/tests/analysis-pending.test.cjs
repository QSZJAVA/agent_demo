/** 会话级分析待核对记录回归；使用真实 WebCrypto，核对身份隔离、24小时预算、损坏丢弃及存储失败。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { webcrypto } = require('node:crypto')
const context = { TextEncoder }
vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/utils/analysisPending.js'), 'utf8').replace('export function', 'function'), context)
const { createAnalysisPendingStore } = context
const request = { companyCode: 'A', reportId: 'rpt-sales-order', question: '订单金额是否等于已收款', snapshotId: null }
const HOUR = 60 * 60 * 1000

function fixture() {
  const values = new Map()
  const state = { time: 1800000000000, identity: { userId: 'reader', token: 'session-a' } }
  const storage = { get length() { return values.size }, key: index => [...values.keys()][index] ?? null, getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) }
  const store = options => createAnalysisPendingStore({ getStorage: () => storage, getIdentity: () => state.identity, cryptoApi: webcrypto, now: () => state.time, ...options })
  return { values, state, storage, store }
}

test('保存只包含请求、key、签名和时间，owner 使用摘要且同一会话可恢复原 key', async () => {
  const env = fixture(), writer = env.store(), pending = await writer.create(request, 'analysis-original')
  const [key, raw] = [...env.values.entries()][0], record = JSON.parse(raw)
  assert.match(key, /^report\.analysis\.pending\.[a-f0-9]{64}$/)
  assert.deepEqual(Object.keys(record).sort(), ['createdAt', 'key', 'request', 'signature'])
  assert.equal(raw.includes('session-a'), false); assert.equal(key.includes('reader'), false)
  assert.match(record.signature, /^[a-f0-9]{64}$/)
  const restored = await env.store().read()
  assert.equal(restored.pending.key, pending.key); assert.deepEqual(JSON.parse(JSON.stringify(restored.pending.request)), request)
  await writer.verify(pending)
})

test('用户或令牌变化后旧保存器拒绝继续，新保存器不读取旧问题并丢弃旧记录', async () => {
  for (const change of [{ userId: 'other', token: 'session-a' }, { userId: 'reader', token: 'session-b' }]) {
    const env = fixture(), old = env.store(), pending = await old.create(request, 'analysis-original')
    env.state.identity = change
    await assert.rejects(old.verify(pending), error => error.pendingFailure === 'SESSION_CHANGED')
    const result = await env.store().read()
    assert.equal(result.pending, null); assert.match(result.notice, /其他登录会话/); assert.equal(env.values.size, 0)
  }
})

test('记录满24小时即丢弃，未来时间与损坏签名也不能恢复或迁移', async () => {
  for (const change of [
    (record, env) => { env.state.time += 24 * HOUR },
    record => { record.createdAt++ },
    record => { record.request.question = '被改变的问题' },
    record => { record.unexpected = true },
    record => { record.request.extra = '未知字段' }
  ]) {
    const env = fixture(); await env.store().create(request, 'analysis-original')
    const [key, raw] = [...env.values.entries()][0], record = JSON.parse(raw)
    change(record, env); env.values.set(key, JSON.stringify(record))
    const result = await env.store().read()
    assert.equal(result.pending, null); assert.match(result.notice, /丢弃|损坏/); assert.equal(env.values.size, 0)
  }
})

test('当前请求边界接受最长公司报表问题以及合法的小写快照标识', async () => {
  const env = fixture(), value = { companyCode: 'A'.repeat(32), reportId: 'a'.repeat(64), question: '问'.repeat(2000), snapshotId: 'abcdefabcdefab01'.repeat(2) }
  const pending = await env.store().create(value, 'analysis-boundary')
  await env.store().verify(pending)
  assert.deepEqual(JSON.parse(JSON.stringify((await env.store().read()).pending.request)), value)
})

test('损坏存储的范围和标识即使签名重算一致也按当前后端协议拒绝恢复', async () => {
  for (const invalid of [
    { companyCode: 'A'.repeat(33) }, { companyCode: 'A\u0000' },
    { reportId: 'ab' }, { reportId: 'a'.repeat(65) }, { reportId: 'rpt_sales' }, { reportId: 'RPT-sales' }, { reportId: 'rpt-sales\n' },
    { snapshotId: 'old-snapshot' }, { snapshotId: 'a'.repeat(31) }, { snapshotId: 'A'.repeat(32) }, { snapshotId: 'a'.repeat(32) + '\n' }
  ]) {
    const env = fixture(); await env.store().create(request, 'analysis-original')
    const [key, raw] = [...env.values.entries()][0], record = JSON.parse(raw)
    Object.assign(record.request, invalid)
    record.signature = Buffer.from(await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify(record.request)))).toString('hex')
    env.values.set(key, JSON.stringify(record))
    const result = await env.store().read()
    assert.equal(result.pending, null); assert.match(result.notice, /损坏.*丢弃/); assert.equal(env.values.size, 0)
  }
})

test('损坏JSON只清除分析自己的记录，不触碰会话认证或其他页面数据', async () => {
  const env = fixture(), pending = await env.store().create(request, 'analysis-original')
  env.values.set('report.sessionToken', 'unrelated-session-value'); env.values.set(pending.ownerKey, '{invalid')
  const result = await env.store().read()
  assert.equal(result.pending, null); assert.equal(env.values.get('report.sessionToken'), 'unrelated-session-value'); assert.equal(env.values.has(pending.ownerKey), false)
})

test('核对前再次检查期限，过期记录不能继续使用旧key发起执行', async () => {
  const env = fixture(), store = env.store(), pending = await store.create(request, 'analysis-original')
  env.state.time += 24 * HOUR
  await assert.rejects(store.verify(pending), error => error.pendingFailure === 'INVALIDATED' && /24 小时/.test(error.message))
  assert.equal(env.values.size, 0)
})

test('旧确认只清理匹配记录，不能删掉同会话的后续提交', async () => {
  const env = fixture(), store = env.store(), first = await store.create(request, 'analysis-first')
  assert.equal(await store.clear(first), true); assert.equal(env.values.size, 0)
  const second = await store.create(request, 'analysis-second')
  assert.equal(await store.clear(first), false); assert.equal((await store.read()).pending.key, second.key)
})

test('旧登录迟到的确认不能清理新登录刚保存的请求', async () => {
  const env = fixture(), oldStore = env.store(), oldPending = await oldStore.create(request, 'analysis-old-login')
  env.state.identity = { userId: 'new-reader', token: 'new-session' }
  const newStore = env.store(), current = await newStore.create(request, 'analysis-current-login')
  await assert.rejects(oldStore.clear(oldPending), error => error.pendingFailure === 'SESSION_CHANGED')
  assert.equal((await newStore.read()).pending.key, current.key); assert.equal(env.values.size, 1)
})

test('已有未知提交或并发保存不能被新的请求号覆盖', async () => {
  const env = fixture(), store = env.store(), first = await store.create(request, 'analysis-first')
  await assert.rejects(store.create(request, 'analysis-second'), error => error.pendingFailure === 'EXISTS')
  assert.equal((await store.read()).pending.key, first.key)
  const parallel = fixture(), results = await Promise.allSettled([parallel.store().create(request, 'analysis-a'), parallel.store().create(request, 'analysis-b')])
  assert.equal(results.filter(item => item.status === 'fulfilled').length, 1)
})

test('存储和WebCrypto不可用时明确失败，不返回已保存记录', async () => {
  for (const options of [
    { getStorage: () => { throw new Error('disabled') } },
    { getStorage: () => ({ length: 0, getItem: () => null, setItem: () => { throw new Error('quota') } }) },
    { getStorage: () => ({ length: 0, getItem: () => null, setItem() {} }) },
    { cryptoApi: {} }
  ]) {
    await assert.rejects(fixture().store(options).create(request, 'analysis-new'), error => error.pendingFailure === 'UNAVAILABLE')
  }
})

test('身份在摘要计算期间改变时，不写入旧登录的请求正文', async () => {
  const env = fixture(); let release
  const deferredCrypto = { subtle: { digest: async (...args) => { await new Promise(resolve => { release = resolve }); return webcrypto.subtle.digest(...args) } } }
  const saving = env.store({ cryptoApi: deferredCrypto }).create(request, 'analysis-original')
  env.state.identity = { userId: 'reader', token: 'new-session' }; release()
  await assert.rejects(saving, error => error.pendingFailure === 'SESSION_CHANGED')
  assert.equal(env.values.size, 0)
})
