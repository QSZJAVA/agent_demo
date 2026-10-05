/**
 * 调查任务提交与只读恢复；首次POST前固定键和负载，丢失响应沿原键恢复，已获ID后只GET。
 * 恢复线索按账号和清单隔离，过期或404不会自动重新创建；晚到响应不能覆盖新会话。
 */
import http from './http'
import { getCurrentUserId, getSessionToken } from '../auth'

const lifetime = 7 * 86400000
const storageKey = planId => `investigation:${getCurrentUserId()}:${planId}`
const key = () => Array.from(window.crypto.getRandomValues(new Uint32Array(4)), value => value.toString(16).padStart(8, '0')).join('')
const context = () => { const user = getCurrentUserId(), token = getSessionToken(); return () => user === getCurrentUserId() && token === getSessionToken() }
const assertCurrent = current => { if (!current()) throw new Error('登录会话已变化，请重新打开调查') }
const load = planId => {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey(planId)) || 'null')
    if (!saved || !saved.createdAt || Date.now() - saved.createdAt > lifetime) { sessionStorage.removeItem(storageKey(planId)); return null }
    return saved
  } catch (e) { return null }
}
const save = (planId, value) => sessionStorage.setItem(storageKey(planId), JSON.stringify(value))
// 同一账号也可能有旧抽屉的在途请求；凭据校验之外还须比较恢复记录的键和已接收ID。
const removeUnchanged = (planId, pending) => {
  const saved = load(planId)
  if (saved?.key === pending.key && saved.id === pending.id) sessionStorage.removeItem(storageKey(planId))
}
const normalize = request => ({ planId: request.planId, question: request.question.trim(), itemIds: request.itemIds ? [...new Set(request.itemIds)].sort() : null })

/** 用户显式提交；相同未完成负载沿用旧键，已完成或明确重新分析产生新运行。 */
export async function submitInvestigation(request, fresh = false) {
  const current = context(), normalized = normalize(request)
  let pending = load(request.planId)
  if (pending && !fresh && !pending.terminal && JSON.stringify(pending.request) !== JSON.stringify(normalized)) throw new Error('上一调查提交尚未恢复，请先读取已有任务')
  if (!pending || fresh || pending.terminal) pending = { key: key(), createdAt: Date.now(), request: normalized }
  save(request.planId, pending)
  let result
  try {
    result = pending.id ? { run: await http.get(`/investigations/${encodeURIComponent(pending.id)}`) }
      : await http.post('/investigations', pending.request, { headers: { 'Idempotency-Key': pending.key } })
  } catch (error) {
    assertCurrent(current)
    // 明确拒绝意味着没有接受新任务，可以修改范围；断网和5xx仍保存原键避免重复创建。
    if (!pending.id && [400, 403, 409, 413, 429].includes(error.response?.status)) {
      removeUnchanged(request.planId, pending)
      error.investigationRejected = true
    }
    throw error
  }
  assertCurrent(current)
  if (result.empty) { removeUnchanged(request.planId, pending); return null }
  const latest = load(request.planId)
  if (latest?.key === pending.key) save(request.planId, { ...latest, id: result.run.id })
  return result.run
}

/** 刷新恢复已有任务；没有ID的丢失提交响应使用保存的原键和原负载，不新建键。 */
export async function recoverInvestigation(planId) {
  const current = context(), pending = load(planId)
  if (pending) {
    try {
      const run = pending.id ? await http.get(`/investigations/${encodeURIComponent(pending.id)}`) : await submitInvestigation(pending.request)
      assertCurrent(current); return run
    } catch (error) {
      assertCurrent(current)
      if (error.response?.status === 404) { removeUnchanged(planId, pending); return null }
      throw error
    }
  }
  const page = await http.get('/investigations', { params: { planId, size: 1 } })
  assertCurrent(current)
  if (!page.records.length) return null
  const run = await http.get(`/investigations/${encodeURIComponent(page.records[0].id)}`)
  assertCurrent(current); return run
}

export async function fetchInvestigation(id) { return http.get(`/investigations/${encodeURIComponent(id)}`) }
export async function fetchInvestigationSteps(id, afterSeq = 0) { return http.get(`/investigations/${encodeURIComponent(id)}/steps`, { params: { afterSeq, size: 50 } }) }
export async function fetchInvestigationEvidence(id, ref) { return http.get(`/investigations/${encodeURIComponent(id)}/evidence/${encodeURIComponent(ref)}`) }
export async function cancelInvestigation(id) { return http.post(`/investigations/${encodeURIComponent(id)}/cancel`) }
export async function fetchInvestigationCandidates(planId, afterId = '0') { return http.get('/investigations/candidates', { params: { planId, afterId, size: 20 } }) }

/** 判断是否存在尚未取得ID的提交，供页面冻结负载并通过原请求恢复。 */
export function hasPendingInvestigation(planId) { const saved = load(planId); return Boolean(saved && !saved.id) }

/** 收到终态后保留ID供只读恢复，下一次用户显式提交会创建新键。 */
export function markInvestigationTerminal(planId, id) {
  const pending = load(planId)
  if (pending?.id === id) { pending.terminal = true; save(planId, pending) }
}
