/** 分析待核对请求的当前协议；模式与快照共同保存，身份、签名、期限及迟到确认由共享保存器约束。 */
import { createPendingRequestStore, pendingFailure, exactFields, validText, identifier } from './pendingRequestStore'

const REQUEST_FIELDS = ['companyCode', 'mode', 'question', 'reportId', 'snapshotId']
const MODES = ['KNOWLEDGE_ONLY', 'CURRENT_DATA', 'SNAPSHOT_ONLY']

function requestValue(request) {
  // 不转换旧模式或借用当前表单补充缺失字段；明确快照不能因恢复失败静默切换成实时查询。
  if (!exactFields(request, REQUEST_FIELDS) || !validText(request.companyCode, 32) || !identifier(request.reportId, /^[a-z][a-z0-9-]{2,63}$/) || !validText(request.question, 2000) || !MODES.includes(request.mode) || !(request.snapshotId === null || identifier(request.snapshotId, /^[a-f0-9]{32}$/)) || (request.mode === 'KNOWLEDGE_ONLY' && request.snapshotId !== null) || (request.mode === 'SNAPSHOT_ONLY' && request.snapshotId === null)) throw pendingFailure('CORRUPT', '待核对请求格式已损坏，已停止恢复。')
  return { companyCode: request.companyCode, reportId: request.reportId, question: request.question, mode: request.mode, snapshotId: request.snapshotId }
}

/** 创建当前分析协议的会话保存器；读取只准备核对，不创建任务、不迁移旧请求。 */
export function createAnalysisPendingStore(options) {
  return createPendingRequestStore({ ...options, namespace: 'analysis', keyPattern: /^analysis-[a-zA-Z0-9_-]{1,100}$/, requestValue, maxRecordChars: 16384 })
}
