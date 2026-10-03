/**
 * 持久化派单任务客户端：先保存幂等键再提交，拿到任务标识后仅轮询状态。
 * 等待上限5分钟，超时或丢失响应保留本地恢复依据；后台执行不因页面等待结束而重新派单。
 */
import http from './http'
import { getCurrentUserId, getSessionToken } from '../auth'
import Message from 'element-ui/lib/message'

const pendingKey = request => `dispatch-job:${getCurrentUserId()}:${JSON.stringify(request)}`
const delay = () => new Promise(resolve => setTimeout(resolve, 1000))
const jobError = message => Object.assign(new Error(message), { dispatchJobError: true })
const notifyError = error => { if (error.dispatchJobError) Message.warning(error.message); throw error }

/** 清理当前用户与该终态任务匹配的恢复键；同任务可从多个卡片入口恢复，不能只删除当前入口的键。 */
function forgetJob(job) {
  const prefix = `dispatch-job:${getCurrentUserId()}:`
  const keys = []
  for (let i = 0; i < sessionStorage.length; i++) { const key = sessionStorage.key(i); if (key.startsWith(prefix)) keys.push(key) }
  keys.forEach(key => {
    try { const saved = JSON.parse(sessionStorage.getItem(key)); if (saved.id === job.id || saved.key === job.idempotencyKey) sessionStorage.removeItem(key) } catch (e) { /* unrelated state */ }
  })
}

/** 每秒只读任务状态；onJob仅在状态变化时通知界面。登录上下文变化立即停止，5分钟等待结束保留后续恢复所需标识。 */
async function waitForJob(job, current, onJob) {
  const deadline = Date.now() + 300000
  let lastStatus = null
  while (true) {
    if (!current()) throw new Error('登录会话已变化，请重新打开清单查看结果')
    if (onJob && job.status !== lastStatus) onJob(job)
    lastStatus = job.status
    if (job.status === 'SUCCEEDED' || job.status === 'FAILED') return job
    if (!['QUEUED', 'RUNNING'].includes(job.status)) throw jobError('任务状态无效，请刷新清单')
    if (Date.now() >= deadline) throw jobError('任务仍在处理，请刷新执行结果；后台不会因页面等待结束而重新派单')
    await delay()
    if (!current()) throw new Error('登录会话已变化，请重新打开清单查看结果')
    job = await http.get(`/dispatch/jobs/${encodeURIComponent(job.id)}`)
  }
}

/**
 * 提交任务并等待终态；首次POST前保存幂等键，响应丢失后沿用该键恢复同一任务。
 * @param {Object} request 清单动作，或人工报表及记录范围；必须符合服务端互斥参数契约。
 * @param {Function} onJob 可选的任务状态变化回调，用于显示排队或运行状态。
 * @returns {Promise<Object>} 已完成的业务结果；失败、会话变化或等待超时抛错。
 */
export async function runDispatchJob(request, onJob) {
  const user = getCurrentUserId(), token = getSessionToken()
  const current = () => user === getCurrentUserId() && token === getSessionToken()
  const storageKey = pendingKey(request)
  try {
    let pending
    try { pending = JSON.parse(sessionStorage.getItem(storageKey) || 'null') } catch (e) { /* replace invalid local state */ }
    if (!pending || !pending.key) {
      pending = { key: Array.from(window.crypto.getRandomValues(new Uint32Array(4)), value => value.toString(16).padStart(8, '0')).join('') }
      sessionStorage.setItem(storageKey, JSON.stringify(pending))
    }
    let job = pending.id ? await http.get(`/dispatch/jobs/${encodeURIComponent(pending.id)}`)
      : await http.post('/dispatch/jobs', request, { headers: { 'Idempotency-Key': pending.key } })
    if (!current()) throw new Error('登录会话已变化，请重新打开清单查看结果')
    pending.id = job.id
    sessionStorage.setItem(storageKey, JSON.stringify(pending))
    job = await waitForJob(job, current, onJob)
    if (!current()) throw new Error('登录会话已变化，请重新打开清单查看结果')
    sessionStorage.removeItem(storageKey)
    if (job.status === 'FAILED') throw jobError(job.message || '任务未完成，请刷新并核对结果')
    if (!job.result) throw jobError('任务结果已超过保留期，请查看清单状态')
    return job.result
  } catch (error) { if (current()) notifyError(error); throw error }
}

/**
 * 读取该清单最近的既有任务并恢复等待；刷新执行中卡片不会创建新的派单命令。
 * @param {string} planId 当前卡片绑定的持久化清单标识。
 * @param {Function} onJob 可选任务状态变化回调。
 * @returns {Promise<Object>} 既有任务的完成结果；无任务、已失败或结果超过保留期时抛错。
 */
export async function resumePlanAction(planId, onJob) {
  const user = getCurrentUserId(), token = getSessionToken()
  const current = () => user === getCurrentUserId() && token === getSessionToken()
  try {
    let job = await http.get('/dispatch/jobs', { params: { planId } })
    if (!job) throw jobError('未找到异步任务，请刷新清单状态；结果不明时先核对')
    job = await waitForJob(job, current, onJob)
    if (!current()) throw new Error('登录会话已变化，请重新打开清单查看结果')
    forgetJob(job)
    if (job.status === 'FAILED') throw jobError(job.message || '任务中断，请先核对清单')
    if (!job.result) throw jobError('任务结果已超过保留期，请查看清单状态')
    return job.result
  } catch (error) { if (current()) notifyError(error); throw error }
}
