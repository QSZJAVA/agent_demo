/**
 * 报表分页状态复用逻辑；请求序号保护迟到响应，加载新页立即清空旧记录，避免对过时行执行人工派单。
 */
/**
 * 创建Vue 2分页状态混入；新请求清空旧页记录，只有最新序号且组件仍有效的响应能更新页面。
 * @param {Function} fetchPage 接收从1开始的页码及页大小，返回records和授权范围内total。
 * @returns {Object} 分页数据、生命周期和加载方法，销毁时使全部旧请求失效。
 */
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
