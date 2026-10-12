/** 聊天轮次及分析引用的当前读取协议；核对稳定标识与范围，拒绝把其他轮次或缓存答案解释成本次结果。 */
const ID = /^[a-f0-9]{32}$/
const STATUSES = ['RECEIVED', 'PLANNING', 'READY', 'SUBMITTED', 'COMPLETED', 'INTERRUPTED', 'FAILED']
function id(value) { return typeof value === 'string' && ID.exec(value)?.[0] === value }

/** 卡片只持有五项稳定引用；详情与权限由任务GET重新读取，消息载荷不能夹带已缓存答案。 */
export function analysisTaskReference(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || !id(value.requestId) || !id(value.runId) || !['KNOWLEDGE_ONLY', 'CURRENT_DATA', 'SNAPSHOT_ONLY'].includes(value.mode) || typeof value.companyCode !== 'string' || !value.companyCode.trim() || value.companyCode.length > 32 || typeof value.reportId !== 'string' || /^[a-z][a-z0-9-]{2,63}$/.exec(value.reportId)?.[0] !== value.reportId) throw new Error('分析任务引用无效')
  return { requestId: value.requestId, runId: value.runId, mode: value.mode, companyCode: value.companyCode, reportId: value.reportId }
}

/** 轮次GET或SSE回执必须属于原客户端键和原会话；historyReady明确区分执行终态与历史投影完成。 */
export function readChatReceipt(value, pending) {
  if (!value || typeof value !== 'object' || !id(value.requestId) || !id(value.conversationId) || value.clientKey !== pending.key || !STATUSES.includes(value.status) || typeof value.canReconcile !== 'boolean' || typeof value.historyReady !== 'boolean' || !(value.message === null || typeof value.message === 'string') || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt)) || (pending.request.conversationId !== null && pending.request.conversationId !== value.conversationId)) throw new Error('轮次回执与待核对请求不一致')
  const analysisTask = value.analysisTask === null ? null : analysisTaskReference(value.analysisTask)
  if (analysisTask && analysisTask.requestId !== value.requestId) throw new Error('分析任务不属于当前轮次')
  return { requestId: value.requestId, conversationId: value.conversationId, clientKey: value.clientKey, status: value.status, message: value.message, canReconcile: value.canReconcile, historyReady: value.historyReady, analysisTask, expiresAt: value.expiresAt }
}

/** 这些状态已经给出创建结果或明确终态；仍须等待historyReady才能清理本地待核对记录。 */
export function chatTurnSettled(status) { return ['SUBMITTED', 'COMPLETED', 'INTERRUPTED', 'FAILED'].includes(status) }
