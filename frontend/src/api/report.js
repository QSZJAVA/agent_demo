import http from './http'

/** 报表一：销售报表 */
export function fetchSalesReport(page = 1, size = 50) {
  return http.get('/report/sales/page', { params: { page, size } })
}

/** 报表二：应收报表 */
export function fetchReceivableReport(page = 1, size = 50) {
  return http.get('/report/receivable/page', { params: { page, size } })
}

/** 报表三：费用报表 */
export function fetchExpenseReport(page = 1, size = 50) {
  return http.get('/report/expense/page', { params: { page, size } })
}

/** 报表页手工派单 */
export function dispatchDirect(reportType, ids) {
  return http.post('/dispatch/direct', { reportType, ids })
}

export function fetchManualPlans(reportType, page = 1) {
  return http.get('/dispatch/direct/plans', { params: { reportType, page } })
}
