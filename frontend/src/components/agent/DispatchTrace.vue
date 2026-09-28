<template>
  <el-dialog title="派单追溯" :visible="visible" width="88%" append-to-body @close="$emit('close')">
    <div v-loading="loading" class="dispatch-trace">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="trace">
        <div class="trace-summary">
          <span>清单：{{ trace.plan.id }}</span>
          <span>操作人：{{ trace.plan.confirmedBy || trace.plan.userId }}</span>
          <span>记录：{{ trace.plan.itemCount }} 条</span>
          <el-tag :type="integrityType">{{ integrityLabel }}</el-tag>
        </div>
        <el-alert v-for="warning in trace.integrity.warnings" :key="warning" :title="warning"
          type="warning" :closable="false" show-icon />
        <p class="trace-note">追溯完整性与派单结果分别判断。待核对的派单结果请使用“查询外部结果”；补写记录不会重新派单。</p>
        <div class="trace-actions">
          <el-button size="mini" :disabled="loading" @click="load">刷新记录</el-button>
          <el-button v-if="trace.integrity.pendingCount" size="mini" :loading="retrying" @click="retry">立即安排补写</el-button>
        </div>
        <el-collapse>
          <el-collapse-item title="查询范围、排除项与当时的规则" name="scope">
            <p>公司范围：{{ trace.preview.companyCodes.join('、') }}；预览共 {{ trace.preview.totalCount }} 条。</p>
            <p>已排除：{{ trace.plan.excluded.length ? trace.plan.excluded.join('、') : '无' }}</p>
            <div v-for="(rule, index) in trace.rules" :key="index" class="rule-evidence">
              <strong>{{ rule.name || '手工派单' }}</strong>
              <span v-if="rule.version">（版本 {{ rule.version }}）</span>
              <pre>{{ rule.expression || rule.description }}</pre>
            </div>
          </el-collapse-item>
        </el-collapse>
        <el-tabs v-model="section">
          <el-tab-pane label="可靠事件" name="events" />
          <el-tab-pane label="审计记录" name="audits" />
          <el-tab-pane label="相关会话" name="messages" />
          <el-tab-pane label="逐条结果" name="items" />
        </el-tabs>
        <p v-if="section === 'messages'" class="trace-note">显示本清单所属会话的完整上下文，包含后续重试和核对；其他报表的对话不代表本次派单范围。</p>
        <el-table :data="current.records" border size="mini" max-height="430" row-key="id">
          <el-table-column type="expand">
            <template slot-scope="scope"><pre class="trace-detail">{{ detail(scope.row) }}</pre></template>
          </el-table-column>
          <el-table-column label="时间" width="165"><template slot-scope="scope">{{ time(scope.row) }}</template></el-table-column>
          <el-table-column label="类型 / 阶段" width="140"><template slot-scope="scope">{{ kind(scope.row) }}</template></el-table-column>
          <el-table-column label="单据 / 操作人" min-width="130"><template slot-scope="scope">{{ subject(scope.row) }}</template></el-table-column>
          <el-table-column label="记录内容" min-width="250" show-overflow-tooltip><template slot-scope="scope">{{ description(scope.row) }}</template></el-table-column>
          <el-table-column v-if="section === 'events'" label="展示同步" width="90"><template slot-scope="scope">{{ scope.row.delivery_status === 'DELIVERED' ? '已同步' : '待补写' }}</template></el-table-column>
        </el-table>
        <div class="trace-actions">
          <span>已加载 {{ current.records.length }} / {{ current.total }} 条，展开行可查看完整内容。</span>
          <el-button v-if="current.nextCursor != null" size="mini" :loading="loadingMore" @click="more">加载更多</el-button>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script>
import { fetchDispatchTrace, fetchTracePage, retryTraceDelivery } from '../../api/agent'
import { getCurrentUserId } from '../../auth'

const emptyPage = () => ({ records: [], total: 0, nextCursor: null })
export default {
  name: 'DispatchTrace',
  props: { planId: { type: String, required: true }, visible: { type: Boolean, default: true } },
  data() {
    return { trace: null, section: 'events', loading: false, loadingMore: false, retrying: false,
      error: '', requestVersion: 0, sessionUserId: getCurrentUserId(), disposed: false }
  },
  computed: {
    current() { return this.trace ? this.trace[this.section] : emptyPage() },
    integrityLabel() { return { COMPLETE: '证据完整', SYNCING: '证据已保存，展示待同步', LEGACY: '历史资料待核实', INCOMPLETE: '证据存在缺口' }[this.trace.integrity.status] },
    integrityType() { return this.trace.integrity.status === 'COMPLETE' ? 'success' : 'warning' }
  },
  mounted() { this.load() },
  beforeDestroy() { this.disposed = true; this.requestVersion++; this.trace = null },
  watch: { planId() { this.load() } },
  methods: {
    valid(version) { return !this.disposed && this.visible && this.sessionUserId === getCurrentUserId() && version === this.requestVersion },
    async load() {
      const version = ++this.requestVersion
      this.loading = true
      this.loadingMore = false
      this.error = ''
      this.trace = null
      try {
        const trace = await fetchDispatchTrace(this.planId)
        if (this.valid(version)) this.trace = trace
      } catch (e) { if (this.valid(version)) this.error = e.message || '追溯加载失败，请重试' }
      finally { if (this.valid(version)) this.loading = false }
    },
    async more() {
      if (this.loadingMore || !this.trace || this.current.nextCursor == null) return
      const version = this.requestVersion, section = this.section, cursor = this.current.nextCursor
      this.loadingMore = true
      try {
        const next = await fetchTracePage(this.planId, section, cursor)
        if (!this.valid(version)) return
        const page = this.trace[section], seen = new Set(page.records.map(row => row.id))
        page.records.push(...next.records.filter(row => !seen.has(row.id)))
        page.nextCursor = next.nextCursor
        page.total = next.total
      } catch (e) { /* 接口拦截器提示，保留已加载内容以便重试。 */ }
      finally { if (this.valid(version)) this.loadingMore = false }
    },
    async retry() {
      if (this.retrying) return
      const version = this.requestVersion
      this.retrying = true
      try {
        const result = await retryTraceDelivery(this.planId)
        if (this.valid(version)) this.$message.success(`已安排 ${result.scheduledCount} 条记录补写，可稍后刷新查看`)
      } catch (e) { /* 接口拦截器提示。 */ }
      finally { if (this.valid(version)) this.retrying = false }
    },
    value(row) { return row.event_type === 'MESSAGE' ? row.payload.message : row.event_type ? row.payload : row },
    time(row) { return String(row.created_at || row.updated_at || '').replace('T', ' ').slice(0, 23) },
    kind(row) {
      const value = this.value(row)
      const key = value.phase || row.phase || value.role || row.event_type || value.status
      return { INTENT: '发送前留证', RESULT: '派单返回', RECONCILE: '结果核对', PLAN: '清单状态',
        user: '用户消息', assistant: '助手回复', tool_call: '工具调用', tool_result: '工具返回', card: '业务卡片' }[key] || key
    },
    subject(row) { const value = this.value(row); return value.docNo || value.doc_no || value.userId || value.user_id || row.user_id || '' },
    description(row) {
      const value = this.value(row)
      return value.content || value.message || value.error_message || value.error_code || value.action || value.status || value.cardType || value.card_type || ''
    },
    detail(row) { return JSON.stringify(this.value(row), null, 2) }
  }
}
</script>

<style scoped>
.dispatch-trace { min-height: 120px; }
.trace-summary, .trace-actions { display: flex; gap: 16px; flex-wrap: wrap; align-items: center; margin: 12px 0; }
.trace-note { color: #606266; font-size: 12px; line-height: 1.7; }
.el-alert { margin-bottom: 8px; }
.trace-detail, .rule-evidence pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 8px; line-height: 1.6; }
.rule-evidence { margin: 8px 0; }
</style>
