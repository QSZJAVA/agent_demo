/** 本人语义诊断与业务词典接口；诊断读取不触发模型，词典发布与反馈仅在明确操作时提交。 */
import http from './http'

export function fetchSemanticRuns(before) { return http.get('/semantic-quality/runs', { params: { before } }) }
export function fetchSemanticRun(id) { return http.get(`/semantic-quality/runs/${id}`) }
export function fetchSemanticFeedback(id, before) { return http.get(`/semantic-quality/runs/${id}/feedback`, { params: { before } }) }
export function submitSemanticFeedback(id, payload) { return http.post(`/semantic-quality/runs/${id}/feedback`, payload) }
export function fetchSemanticCatalog() { return http.get('/semantic-quality/catalog') }
export function fetchDictionary(scope) { return http.get('/semantic-quality/dictionary', { params: scope }) }
export function fetchDictionaryHistory(scope) { return http.get('/semantic-quality/dictionary/history', { params: scope }) }
export function validateDictionary(scope, change) { return http.post('/semantic-quality/dictionary/validate', change, { params: scope }) }
export function publishDictionary(scope, change) { return http.put('/semantic-quality/dictionary', change, { params: scope }) }
