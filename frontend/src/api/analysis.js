/** 有限分析及有版本知识资料接口；身份沿用认证HTTP层，请求号由页面保存以安全重试同一次提交。 */
import http from './http'

export const analysisCatalog = () => http.get('/analysis/catalog')
export const analysisRuns = () => http.get('/analysis/runs')
export const analysisRun = id => http.get(`/analysis/runs/${encodeURIComponent(id)}`)
export const submitAnalysis = (request, key) => http.post('/analysis/runs', request, { headers: { 'Idempotency-Key': key } })
export const cancelAnalysis = id => http.post(`/analysis/runs/${encodeURIComponent(id)}/cancel`)
export const analysisRows = (id, step, page) => http.get(`/analysis/runs/${encodeURIComponent(id)}/steps/${step}/rows`, { params: { page } })
export const knowledgeDocuments = (companyCode, reportId) => http.get('/analysis/knowledge', { params: { companyCode, reportId } })
export const knowledgeHistory = id => http.get(`/analysis/knowledge/${encodeURIComponent(id)}/history`)
export const saveKnowledge = (id, data) => id ? http.put(`/analysis/knowledge/${encodeURIComponent(id)}`, data) : http.post('/analysis/knowledge', data)
export const publishKnowledge = (id, data) => http.post(`/analysis/knowledge/${encodeURIComponent(id)}/publish`, data)
export const disableKnowledge = (id, data) => http.post(`/analysis/knowledge/${encodeURIComponent(id)}/disable`, data)
