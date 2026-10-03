/**
 * 报表目录与别名维护接口；定义以稳定 reportId 关联，发布前的来源配置探测由服务端执行。
 */
import http from './http'

/** 报表目录：普通用户只返回有权限的报表；管理员 manage=true 时返回全部定义（含草稿、停用） */
export function fetchCatalog(manage = false) {
  return http.get('/report-catalog', { params: { manage } })
}

/** 在当前用户可派单的报表范围内解析一个说法 */
export function searchCatalog(q) {
  return http.get('/report-catalog/search', { params: { q } })
}

export const saveCatalog = (id, form) => id
  ? http.put(`/report-catalog/${encodeURIComponent(id)}`, form)
  : http.post('/report-catalog', form)
export const publishCatalog = id => http.post(`/report-catalog/${encodeURIComponent(id)}/publish`)
export const disableCatalog = id => http.post(`/report-catalog/${encodeURIComponent(id)}/disable`)
export const addAlias = (id, form) => http.post(`/report-catalog/${encodeURIComponent(id)}/aliases`, form)
export const disableAlias = (id, alias) => http.delete(`/report-catalog/${encodeURIComponent(id)}/aliases/${alias}`)
