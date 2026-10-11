/**
 * 本人派单任务历史的只读接口；服务端按当前租户、身份和业务权限过滤，浏览器不恢复或重放提交负载。
 * 游标只用于原筛选，清单详情每次重新读取当前状态及当前页条目。
 */
import http from './http'

export function fetchTaskHistory({ before = null, status = '', size = 20 } = {}) {
  return http.get('/dispatch/jobs/history', { params: { before: before || undefined, status: status || undefined, size } })
}

/** 同时读取清单的最新权威状态和当前页结果；任一路失败都不能显示另一份旧清单。 */
export async function fetchTaskPlan(planId, page = 1, operator = false) {
  // 管理员代处理任务的清单仍归原用户所有，必须经过运维范围复核入口，不能放宽普通清单归属。
  const root = `${operator ? '/operations/workbench' : '/dispatch/plans'}/${encodeURIComponent(planId)}`
  const [plan, items] = await Promise.all([http.get(root), http.get(`${root}/items`, { params: { page, size: 50 } })])
  return { plan, items }
}
