/** Reject stale pages and clear actionable rows immediately while another page is loading. */
export function pagedReport(fetchPage) {
  return {
    data() { return { loading: false, list: [], page: 1, pageSize: 50, total: 0, pageRequest: 0, pageDisposed: false } },
    created() { this.loadData() },
    beforeDestroy() { this.pageDisposed = true; this.pageRequest++ },
    methods: {
      async loadData() {
        const request = ++this.pageRequest
        this.loading = true
        this.list = []
        try {
          const result = await fetchPage(this.page, this.pageSize)
          if (this.pageDisposed || request !== this.pageRequest) return
          this.total = result.total
          const lastPage = Math.max(1, Math.ceil(result.total / this.pageSize))
          if (this.page > lastPage) { this.page = lastPage; return this.loadData() }
          this.list = result.records
        } catch (e) {
          if (!this.pageDisposed && request === this.pageRequest) this.total = 0
        } finally {
          if (!this.pageDisposed && request === this.pageRequest) this.loading = false
        }
      },
      changePage(page) { this.page = page; return this.loadData() },
      changePageSize(size) { this.pageSize = size; this.page = 1; return this.loadData() }
    }
  }
}
