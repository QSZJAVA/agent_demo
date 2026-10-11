/**
 * 查询工作区受控接口；分页、分析和导出固定运行ID，变更自然语言条件仍由助手处理。
 * 导出先保存幂等键再提交，丢失响应沿原键恢复；下载检查响应类型与登录上下文，不把错误JSON保存为文件。
 */
import http from './http'
import { authHeaders, getCurrentUserId, getSessionToken } from '../auth'

const root = '/query-workspace'
const path = id => `${root}/runs/${encodeURIComponent(id)}`
export const listQueryRuns = before => http.get(`${root}/runs`, { params: { before: before || undefined } })
export const queryPage = (id, page = 1) => http.get(path(id), { params: { page } })
export const expireQuery = id => http.delete(path(id))
export const saveQuery = (id, request) => http.post(`${path(id)}/saved`, request)
export const listSavedQueries = () => http.get(`${root}/saved`)
export const runSavedQuery = id => http.post(`${root}/saved/${encodeURIComponent(id)}/run`, {}, { timeout: 150000 })
export const updateSavedQuery = (id, version, request) => http.put(`${root}/saved/${encodeURIComponent(id)}`, request, { params: { version } })
export const deleteSavedQuery = (id, version) => http.delete(`${root}/saved/${encodeURIComponent(id)}`, { params: { version } })
export const analyzeQuery = (id, request) => http.post(`${path(id)}/analysis`, request)
export const rankQuery = (id, request) => http.post(`${path(id)}/rank`, request)
export const prepareRankPlan = (id, request) => http.post(`${path(id)}/rank-plan`, request, { timeout: 180000 })
export const compareQueries = (before, after) => http.get(`${root}/compare`, { params: { before, after } })
export const getExport = id => http.get(`${root}/exports/${encodeURIComponent(id)}`)
export const cancelExport = id => http.post(`${root}/exports/${encodeURIComponent(id)}/cancel`)
export const taskInbox = ({ kind = 'ALL', unread = false, before = null } = {}) => http.get(`${root}/inbox`, { params: { kind, unread, before: before || undefined } })
export const readTaskNotice = item => http.post(`${root}/inbox/read`, { kind: item.kind, id: item.id, eventKey: item.eventKey })

/** 相同快照默认恢复原导出；只有用户显式重新生成才分配新键。 */
export async function submitQueryExport(runId, fresh = false) {
  const user = getCurrentUserId(), token = getSessionToken(), key = `query-export:${user}:${runId}`
  let saved
  try { saved = JSON.parse(sessionStorage.getItem(key) || 'null') } catch (_) { /* 当前损坏恢复记录不作提交依据。 */ }
  if (!saved || fresh) {
    saved = { key: Array.from(window.crypto.getRandomValues(new Uint32Array(4)), value => value.toString(16).padStart(8, '0')).join('') }
    sessionStorage.setItem(key, JSON.stringify(saved))
  }
  const job = saved.id ? await getExport(saved.id) : await http.post(`${path(runId)}/exports`, {}, { headers: { 'Idempotency-Key': saved.key } })
  if (user !== getCurrentUserId() || token !== getSessionToken()) throw new Error('登录会话已变化，请重新读取任务')
  // 并发窗口中只更新自己发出的那次恢复记录，旧成功不能覆盖显式重新生成的新键。
  const current = JSON.parse(sessionStorage.getItem(key) || 'null')
  if (current?.key === saved.key) sessionStorage.setItem(key, JSON.stringify({ ...saved, id: job.id }))
  return job
}

/** 当前用户显式下载文件；浏览器本地URL只用于已经重新授权取得的字节，完成后立即释放。 */
export async function downloadQueryExport(id) {
  const user = getCurrentUserId(), token = getSessionToken()
  const response = await fetch(`/api${root}/exports/${encodeURIComponent(id)}/file`, { headers: authHeaders(), cache: 'no-store' })
  if (!response.ok || !response.headers.get('content-type')?.startsWith('text/csv')) {
    let message = '导出文件不可用'
    try { message = (await response.json()).message || message } catch (_) { /* 非JSON错误只显示通用信息。 */ }
    throw new Error(message)
  }
  const bytes = await response.blob()
  if (user !== getCurrentUserId() || token !== getSessionToken()) throw new Error('登录会话已变化，下载已停止')
  const url = URL.createObjectURL(bytes), anchor = document.createElement('a')
  try { anchor.href = url; anchor.download = `query-${id}.csv`; document.body.appendChild(anchor); anchor.click() } finally { anchor.remove(); URL.revokeObjectURL(url) }
}
