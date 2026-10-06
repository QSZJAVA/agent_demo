<template>
  <report-table
    title="销售报表"
    table-name="report_sales"
    report-id="rpt-sales-order"
    doc-no-field="orderNo"
    :columns="columns"
    :data="list"
    :loading="loading"
    :page="page"
    :page-size="pageSize"
    :total="total"
    @page-change="changePage"
    @size-change="changePageSize"
    @refresh="loadData"
  />
</template>

<script>
/**
 * 销售报表页；共用分页状态与人工派单组件，数据范围和总数由服务端按当前权限计算。
 */
import ReportTable from '../components/ReportTable.vue'
import { fetchSalesReport } from '../api/report'
import { pagedReport } from '../utils/pagedReport'

export default {
  name: 'SalesReport',
  components: { ReportTable },
  mixins: [pagedReport(fetchSalesReport)],
  data() {
    return {
      columns: [
        { prop: 'companyCode', label: '公司代码', width: 100 },
        { prop: 'orderNo', label: '订单号', width: 140 },
        { prop: 'productName', label: '产品名称', minWidth: 140 },
        { prop: 'amount', label: '金额(元)', width: 160, isAmount: true },
        { prop: 'saleDate', label: '销售日期', width: 130 }
      ]
    }
  }
}
</script>
