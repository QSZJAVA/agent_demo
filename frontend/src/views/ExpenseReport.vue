<template>
  <report-table
    title="费用报表"
    table-name="report_expense"
    report-type="expense"
    doc-no-field="expenseNo"
    :columns="columns"
    :data="list"
    :loading="loading"
    @refresh="loadData"
  />
</template>

<script>
import ReportTable from '../components/ReportTable.vue'
import { fetchExpenseReport } from '../api/report'

export default {
  name: 'ExpenseReport',
  components: { ReportTable },
  data() {
    return {
      loading: false,
      list: [],
      columns: [
        { prop: 'companyCode', label: '公司代码', width: 100 },
        { prop: 'expenseNo', label: '报销单号', width: 160 },
        { prop: 'expenseType', label: '费用类型', minWidth: 140 },
        { prop: 'amount', label: '金额(元)', width: 160, isAmount: true },
        { prop: 'expenseDate', label: '发生日期', width: 130 }
      ]
    }
  },
  created() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const data = await fetchExpenseReport()
        this.list = data || []
      } finally {
        this.loading = false
      }
    }
  }
}
</script>
