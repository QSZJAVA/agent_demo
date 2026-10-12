/** 分析待核对请求的会话级保存；只保留当前协议，按登录身份与会话摘要隔离，读取不会提交任务或自动重试。 */
const PREFIX = 'report.analysis.pending.'
const TTL_MS = 24 * 60 * 60 * 1000
const RECORD_FIELDS = ['createdAt', 'key', 'request', 'signature']
const REQUEST_FIELDS = ['companyCode', 'question', 'reportId', 'snapshotId']

function failure(kind, message) { const error = new Error(message); error.pendingFailure = kind; return error }
function exactFields(value, fields) { return value && typeof value === 'object' && !Array.isArray(value) && JSON.stringify(Object.keys(value).sort()) === JSON.stringify(fields) }
function text(value, limit) { return typeof value === 'string' && value.trim().length > 0 && value.length <= limit && !value.includes('\u0000') }
// JavaScript 的 $ 可停在末尾换行之前；要求命中完整原值，保持与后端 matches 的标识校验一致。
function identifier(value, pattern) { return typeof value === 'string' && pattern.exec(value)?.[0] === value }
function requestValue(request) {
  // 保存与恢复使用当前 AnalysisContract.Request 的范围、标识与 UTF-16 字符预算，不放宽或转换旧格式。
  if (!exactFields(request, REQUEST_FIELDS) || !text(request.companyCode, 32) || !identifier(request.reportId, /^[a-z][a-z0-9-]{2,63}$/) || !text(request.question, 2000) || !(request.snapshotId === null || identifier(request.snapshotId, /^[a-f0-9]{32}$/))) throw failure('CORRUPT', '待核对请求格式已损坏，已停止恢复。')
  return { companyCode: request.companyCode, reportId: request.reportId, question: request.question, snapshotId: request.snapshotId }
}

/**
 * 创建仅属于本次页面登录会话的保存器；身份变化后须由新页面建立新保存器，旧异步响应不能清理新登录的记录。
 * @param {Object} options 存储访问器、身份读取器、WebCrypto 和时钟；身份只在摘要计算期间使用，不写入额外令牌。
 * @returns {Object} read/create/verify/clear 方法；所有存储失败均明确抛出，不假装记录已经保存。
 */
export function createAnalysisPendingStore({ getStorage = () => sessionStorage, getIdentity, cryptoApi = globalThis.crypto, now = () => Date.now() }) {
  let sessionKey = null
  function storage() { try { return getStorage() } catch (_) { throw failure('UNAVAILABLE', '浏览器会话存储不可用，无法可靠保存或恢复待核对请求。') } }
  function identity() {
    let value
    try { value = getIdentity() } catch (_) { throw failure('UNAVAILABLE', '无法读取当前浏览器登录会话，请恢复会话存储后重试。') }
    if (!value || !text(value.userId, 200) || !text(value.token, 16384)) throw failure('SESSION_CHANGED', '当前登录会话已变化，请重新进入分析页面；旧登录的请求不会继续提交。')
    return { userId: value.userId, token: value.token }
  }
  async function digest(value) {
    try {
      const bytes = await cryptoApi.subtle.digest('SHA-256', new TextEncoder().encode(value))
      return Array.from(new Uint8Array(bytes), item => item.toString(16).padStart(2, '0')).join('')
    } catch (_) { throw failure('UNAVAILABLE', '当前浏览器无法安全绑定待核对请求，请确认会话存储与 WebCrypto 可用。') }
  }
  function guard(owner) {
    const current = identity()
    if (current.userId !== owner.identity.userId || current.token !== owner.identity.token) throw failure('SESSION_CHANGED', '登录会话在操作期间发生变化，旧请求不会继续提交。')
  }
  async function owner() {
    const value = identity(), key = PREFIX + await digest(JSON.stringify(['analysis-pending', value.userId, value.token]))
    const bound = { identity: value, key }; guard(bound)
    if (sessionKey && sessionKey !== key) throw failure('SESSION_CHANGED', '当前登录会话已变化，请重新进入分析页面；旧登录的请求不会继续提交。')
    sessionKey = key
    return bound
  }
  function access(action) { try { return action() } catch (error) { if (error.pendingFailure) throw error; throw failure('UNAVAILABLE', '浏览器会话存储读写失败，无法确认待核对记录已保存。') } }
  function rawRecord(pending) { return { request: requestValue(pending.request), key: pending.key, signature: pending.signature, createdAt: pending.createdAt } }
  async function decode(raw) {
    let record
    try { if (typeof raw !== 'string' || raw.length > 16384) throw new Error(); record = JSON.parse(raw) } catch (_) { throw failure('CORRUPT', '待核对记录格式已损坏，已丢弃；请从最近任务核对结果。') }
    if (!exactFields(record, RECORD_FIELDS) || !identifier(record.key, /^analysis-[a-zA-Z0-9_-]{1,100}$/) || !identifier(record.signature, /^[a-f0-9]{64}$/) || !Number.isSafeInteger(record.createdAt) || record.createdAt <= 0) throw failure('CORRUPT', '待核对记录格式已损坏，已丢弃；请从最近任务核对结果。')
    const request = requestValue(record.request), time = now()
    if (record.createdAt > time || time - record.createdAt >= TTL_MS) throw failure('EXPIRED', '待核对记录已超过 24 小时或时间无效，已丢弃；请从最近任务核对结果。')
    if (await digest(JSON.stringify(request)) !== record.signature) throw failure('CORRUPT', '待核对请求签名不一致，已丢弃；请从最近任务核对结果。')
    return { request, key: record.key, signature: record.signature, createdAt: record.createdAt }
  }
  async function readBound(bound) {
    const target = storage(); guard(bound)
    const removed = access(() => {
      const old = []
      for (let index = 0; index < target.length; index++) { const key = target.key(index); if (key?.startsWith(PREFIX) && key !== bound.key) old.push(key) }
      for (const key of old) target.removeItem(key)
      return old.length > 0
    })
    const raw = access(() => target.getItem(bound.key))
    if (raw === null) return { pending: null, notice: removed ? '已丢弃其他登录会话的待核对记录，未恢复其问题。' : '' }
    try {
      const record = await decode(raw); guard(bound)
      return { pending: { ...record, ownerKey: bound.key }, notice: '' }
    } catch (error) {
      if (!['CORRUPT', 'EXPIRED'].includes(error.pendingFailure)) throw error
      guard(bound)
      access(() => { if (target.getItem(bound.key) === raw) target.removeItem(bound.key) })
      return { pending: null, notice: error.message.includes('已丢弃') ? error.message : error.message + ' 该恢复记录已丢弃。' }
    }
  }
  return {
    /** 核对页面绑定的登录仍有效，不读取业务数据或返回原始会话凭据。 */
    async checkSession() { await owner() },
    /** 仅恢复当前 owner 的有效记录；旧登录、损坏和过期数据不迁移，不触发任何网络调用。 */
    async read() { return readBound(await owner()) },
    /** 必须先完成保存和回读校验再发送请求；已有未知请求时拒绝覆盖。 */
    async create(request, key) {
      const bound = await owner(), existing = await readBound(bound)
      if (existing.pending) { const error = failure('EXISTS', '已有待核对请求，请先核对该次提交。'); error.pending = existing.pending; throw error }
      const value = requestValue(request), record = { request: value, key, signature: await digest(JSON.stringify(value)), createdAt: now() }
      await decode(JSON.stringify(record)); guard(bound)
      const target = storage(), serialized = JSON.stringify(record)
      access(() => {
        if (target.getItem(bound.key) !== null) throw failure('EXISTS', '另一次提交已保存待核对记录，请重新进入分析页面恢复。')
        target.setItem(bound.key, serialized)
        if (target.getItem(bound.key) !== serialized) throw new Error('storage verification failed')
      })
      return { ...record, ownerKey: bound.key }
    },
    /** 每次显式核对前重验会话、24小时期限及实际保存内容，避免旧 key 在新登录或过期后重新执行。 */
    async verify(pending) {
      const bound = await owner()
      if (pending.ownerKey !== bound.key) throw failure('SESSION_CHANGED', '待核对请求属于另一个登录会话，已停止提交。')
      const existing = await readBound(bound)
      if (!existing.pending) throw failure('INVALIDATED', existing.notice || '待核对记录已不存在，请重新进入页面核对最近任务。')
      if (JSON.stringify(rawRecord(existing.pending)) !== JSON.stringify(rawRecord(pending))) throw failure('INVALIDATED', '当前待核对记录已变化，请重新进入分析页面。')
      guard(bound)
    },
    /** 确认成功或明确拒绝后只删除匹配 owner、key、签名的原记录；旧响应不能删掉后续提交。 */
    async clear(pending) {
      const bound = await owner()
      if (pending.ownerKey !== bound.key) return false
      const target = storage(), raw = access(() => target.getItem(bound.key))
      if (raw === null) return true
      let record
      try { record = JSON.parse(raw) } catch (_) { return false }
      guard(bound)
      if (record.key !== pending.key || record.signature !== pending.signature || record.createdAt !== pending.createdAt) return false
      access(() => target.removeItem(bound.key))
      return true
    }
  }
}
