/** 聊天待核对请求协议；完整保存原文、当前会话与结构化用户选择，首个SSE事件丢失也保留同一请求号。 */
import { createPendingRequestStore, pendingFailure, exactFields, validText, identifier } from './pendingRequestStore'

const REQUEST_FIELDS = ['clarificationReply', 'conversationId', 'excludedRecords', 'message', 'previewId', 'queryEdit']
const ID = /^[a-f0-9]{32}$/
const OPERATORS = ['EQ', 'NE', 'IN', 'NOT_IN', 'IS_NULL', 'NOT_NULL', 'CONTAINS', 'STARTS_WITH', 'GT', 'GTE', 'LT', 'LTE']
function invalid() { throw pendingFailure('CORRUPT', '聊天待核对请求格式已损坏，已停止恢复。') }

/** 校验并复制完整AND/OR条件；数值仍为字符串，空数组明确清除条件，不能丢失原始选值或比较方向。 */
function queryEdit(value) {
  if (value === null) return null
  if (!exactFields(value, ['conditions', 'queryRef']) || !identifier(value.queryRef, ID) || !Array.isArray(value.conditions) || value.conditions.length > 8 || JSON.stringify(value).length > 96000) invalid()
  return { queryRef: value.queryRef, conditions: value.conditions.map(group => {
    if (!exactFields(group, ['allOf']) || !Array.isArray(group.allOf) || !group.allOf.length || group.allOf.length > 8) invalid()
    return { allOf: group.allOf.map(filter => {
      if (!exactFields(filter, ['field', 'operator', 'values']) || !identifier(filter.field, /^[A-Za-z_][A-Za-z0-9_]{0,63}$/) || !OPERATORS.includes(filter.operator) || !Array.isArray(filter.values) || filter.values.length > 30 || filter.values.some(value => typeof value !== 'string' || value.length > 512 || value.includes('\u0000'))) invalid()
      const size = filter.values.length
      if (['IS_NULL', 'NOT_NULL'].includes(filter.operator) ? size !== 0 : ['IN', 'NOT_IN'].includes(filter.operator) ? size < 1 : size !== 1) invalid()
      return { field: filter.field, operator: filter.operator, values: filter.values.slice() }
    }) }
  }) }
}

/** 当前聊天只接受声明字段；可空标识保持null，恢复时绝不借用页面现有会话、预览或澄清选择。 */
function requestValue(request) {
  if (!exactFields(request, REQUEST_FIELDS) || !validText(request.message, 2000) || !(request.conversationId === null || identifier(request.conversationId, ID)) || !(request.previewId === null || identifier(request.previewId, ID)) || !Array.isArray(request.excludedRecords) || (request.queryEdit !== null && request.clarificationReply !== null)) invalid()
  const excludedRecords = request.excludedRecords.map(record => {
    if (!exactFields(record, ['recordId', 'reportId']) || !validText(record.reportId, 128) || !validText(record.recordId, 512)) invalid()
    return { reportId: record.reportId, recordId: record.recordId }
  })
  let clarificationReply = null
  if (request.clarificationReply !== null) {
    const value = request.clarificationReply
    if (!exactFields(value, ['clarificationRef', 'value']) || !identifier(value.clarificationRef, ID) || !validText(value.value, 128)) invalid()
    clarificationReply = { clarificationRef: value.clarificationRef, value: value.value }
  }
  return { conversationId: request.conversationId, message: request.message, excludedRecords, previewId: request.previewId, queryEdit: queryEdit(request.queryEdit), clarificationReply }
}

/** 创建聊天保存器；容量覆盖完整条件与记录选择，超限或存储配额失败只拒绝发送，不截断请求。 */
export function createChatPendingStore(options) {
  return createPendingRequestStore({ ...options, namespace: 'chat', keyPattern: /^chat-[a-zA-Z0-9_-]{8,95}$/, requestValue, maxRecordChars: 2 * 1024 * 1024 })
}
