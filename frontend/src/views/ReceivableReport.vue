<template>
  <report-table
    title="应收报表"
    table-name="report_receivable"
    report-type="receivable"
    doc-no-field="invoiceNo"
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
 * 应收报表页；共用分页状态与人工派单组件，数据范围和总数由服务端按当前权限计算。
 */
import ReportTable from '../components/ReportTable.vue'
import { fetchReceivableReport } from '../api/report'
import { pagedReport } from '../utils/pagedReport'

export default {
  name: 'ReceivableReport',
  components: { ReportTable },
  mixins: [pagedReport(fetchReceivableReport)],
  data() {
    return {
      columns: [
        { prop: 'companyCode', label: '公司代码', width: 100 },
        { prop: 'invoiceNo', label: '发票号', width: 160 },
        { prop: 'customerName', label: '客户名称', minWidth: 200 },
        { prop: 'amount', label: '金额(元)', width: 160, isAmount: true },
        { prop: 'dueDate', label: '到期日', width: 130 }
      ]
    }
  }
}
</script>
