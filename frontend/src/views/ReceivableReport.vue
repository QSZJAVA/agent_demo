<template>
  <report-table
    title="应收报表"
    table-name="report_receivable"
    report-type="receivable"
    doc-no-field="invoiceNo"
    :columns="columns"
    :data="list"
    :loading="loading"
    @refresh="loadData"
  />
</template>

<script>
import ReportTable from '../components/ReportTable.vue'
import { fetchReceivableReport } from '../api/report'

export default {
  name: 'ReceivableReport',
  components: { ReportTable },
  data() {
    return {
      loading: false,
      list: [],
      columns: [
        { prop: 'companyCode', label: '公司代码', width: 100 },
        { prop: 'invoiceNo', label: '发票号', width: 160 },
        { prop: 'customerName', label: '客户名称', minWidth: 200 },
        { prop: 'amount', label: '金额(元)', width: 160, isAmount: true },
        { prop: 'dueDate', label: '到期日', width: 130 }
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
        const data = await fetchReceivableReport()
        this.list = data || []
      } finally {
        this.loading = false
      }
    }
  }
}
</script>
