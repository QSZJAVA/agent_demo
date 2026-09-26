import http from './http'

/** 报表目录：普通用户只返回有权限的报表；管理员 manage=true 时返回全部定义（含草稿、停用） */
export function fetchCatalog(manage = false) {
  return http.get('/report-catalog', { params: { manage } })
}

/** 在当前用户可派单的报表范围内解析一个说法 */
export function searchCatalog(q) {
  return http.get('/report-catalog/search', { params: { q } })
}
