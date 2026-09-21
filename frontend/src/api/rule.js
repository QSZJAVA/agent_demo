import http from './http'

export function fetchRules() {
  return http.get('/rules')
}

export function fetchRuleHistory(reportType, companyCode) {
  return http.get('/rules/history', { params: { reportType, companyCode } })
}

export function fetchRuleFields(reportType) {
  return http.get('/rules/fields', { params: { reportType } })
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
