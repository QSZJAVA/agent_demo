<template>
  <report-table
    title="费用报表"
    table-name="report_expense"
    report-type="expense"
    doc-no-field="expenseNo"
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
 * 费用报表页；共用分页状态与人工派单组件，数据范围和总数由服务端按当前权限计算。
 */
import ReportTable from '../components/ReportTable.vue'
import { fetchExpenseReport } from '../api/report'
import { pagedReport } from '../utils/pagedReport'

export default {
  name: 'ExpenseReport',
  components: { ReportTable },
  mixins: [pagedReport(fetchExpenseReport)],
  data() {
    return {
      columns: [
        { prop: 'companyCode', label: '公司代码', width: 100 },
        { prop: 'expenseNo', label: '报销单号', width: 160 },
        { prop: 'expenseType', label: '费用类型', minWidth: 140 },
        { prop: 'amount', label: '金额(元)', width: 160, isAmount: true },
        { prop: 'expenseDate', label: '发生日期', width: 130 }
      ]
    }
  }
}
</script>
