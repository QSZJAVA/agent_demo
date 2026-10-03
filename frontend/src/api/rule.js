/**
 * 规则草稿、校验、试算、发布和回滚接口；请求由服务端验证报表、公司范围及管理权限。
 */
import http from './http'

export function fetchRules() {
  return http.get('/rules')
}

export function fetchRuleHistory(reportId, companyCode) {
  return http.get('/rules/history', { params: { reportId, companyCode } })
}

export function fetchRuleFields(reportId) {
  return http.get('/rules/fields', { params: { reportId } })
}

export function validateRule(payload) {
  return http.post('/rules/validate', payload)
}

export function dryRunRule(payload) {
  return http.post('/rules/dry-run', payload)
}

export function saveDraft(form) {
  return http.post('/rules/drafts', form)
}

export function deleteDraft(id) {
  return http.delete(`/rules/drafts/${id}`)
}

export function publishRule(id) {
  return http.post(`/rules/${id}/publish`)
}

export function disableRule(id) {
  return http.post(`/rules/${id}/disable`)
}

export function rollbackRule(id) {
  return http.post(`/rules/${id}/rollback`)
}
