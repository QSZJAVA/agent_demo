<template>
  <report-table
    title="销售报表"
    table-name="report_sales"
    report-type="sales"
    doc-no-field="orderNo"
    :columns="columns"
    :data="list"
    :loading="loading"
    @refresh="loadData"
  />
</template>

<script>
import ReportTable from '../components/ReportTable.vue'
import { fetchSalesReport } from '../api/report'

export default {
  name: 'SalesReport',
  components: { ReportTable },
  data() {
    return {
      loading: false,
      list: [],
      columns: [
        { prop: 'companyCode', label: '公司代码', width: 100 },
        { prop: 'orderNo', label: '订单号', width: 140 },
        { prop: 'productName', label: '产品名称', minWidth: 140 },
        { prop: 'amount', label: '金额(元)', width: 160, isAmount: true },
        { prop: 'saleDate', label: '销售日期', width: 130 }
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
        const data = await fetchSalesReport()
        this.list = data || []
      } finally {
        this.loading = false
      }
    }
  }
}
</script>
