import http from './http'

/** 报表一：销售报表 */
export function fetchSalesReport() {
  return http.get('/report/sales')
}

/** 报表二：应收报表 */
export function fetchReceivableReport() {
  return http.get('/report/receivable')
}

/** 报表三：费用报表 */
export function fetchExpenseReport() {
  return http.get('/report/expense')
}

/** 报表页手工派单 */
export function dispatchDirect(reportType, ids) {
  return http.post('/dispatch/direct', { reportType, ids })
}

export function fetchManualPlans(reportType, page = 1) {
  return http.get('/dispatch/direct/plans', { params: { reportType, page } })
}
