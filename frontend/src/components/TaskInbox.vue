<template>
  <section class="task-inbox">
    <div class="inbox-toolbar"><h2>任务与站内提醒</h2><el-select v-model="kind" size="small" aria-label="任务类别" @change="refresh"><el-option label="全部类别" value="ALL" /><el-option label="待确认清单" value="PLAN" /><el-option label="需处理异常" value="ATTENTION" /><el-option label="派单任务" value="DISPATCH" /><el-option label="异常调查" value="INVESTIGATION" /><el-option label="查询导出" value="EXPORT" /></el-select><el-checkbox v-model="unread" @change="refresh">只看未读</el-checkbox><el-button size="small" :loading="loading" @click="refresh">刷新提醒</el-button></div>
    <div v-if="error" class="inbox-error" role="alert">{{ error }}</div>
    <el-table :data="rows" size="small" row-key="eventKey" empty-text="当前筛选没有可见提醒">
      <el-table-column label="任务类别" width="155"><template slot-scope="s"><el-badge :is-dot="s.row.unread && s.row.actionable">{{ label(s.row.kind) }}</el-badge><small class="inbox-id" :title="s.row.id">{{ s.row.id }}</small></template></el-table-column>
      <el-table-column label="状态" width="100"><template slot-scope="s">{{ status(s.row.status) }}</template></el-table-column><el-table-column prop="message" label="下一步" min-width="260" />
      <el-table-column label="创建时间" width="165"><template slot-scope="s">{{ time(s.row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="190"><template slot-scope="s"><el-button type="text" size="small" @click="open(s.row)">查看任务</el-button><el-button v-if="s.row.unread" type="text" size="small" :disabled="reading === s.row.eventKey" @click="markRead(s.row)">标记已读</el-button><span v-else class="muted">已读</span></template></el-table-column>
    </el-table>
    <el-button v-if="nextCursor" size="mini" :disabled="loading" @click="load(nextCursor, true)">更早提醒</el-button>
    <el-dialog title="查询导出任务" :visible.sync="exportVisible" width="600px" @close="closeExport">
      <div v-if="exportError" role="alert" class="inbox-error">{{ exportError }}</div><template v-if="exportJob"><p>{{ status(exportJob.status) }} · {{ exportJob.message }}</p><p>已处理 {{ exportJob.progressRows }} 条，{{ time(exportJob.expiresAt) }} 到期</p><el-button size="small" :disabled="exportLoading" @click="loadExport(exportJob.id)">刷新状态</el-button><el-button v-if="exportJob.status === 'SUCCEEDED'" size="small" type="primary" :disabled="exportLoading" @click="download">下载 CSV</el-button><el-button v-if="['QUEUED','RUNNING'].includes(exportJob.status)" size="small" :disabled="exportLoading" @click="cancel">取消导出</el-button><el-button type="text" @click="$router.push({ path: '/query-workspace', query: { run: exportJob.runId } })">查看来源查询</el-button></template>
    </el-dialog>
    <investigation-panel v-if="investigation" :key="investigation.id" :plan-id="investigation.target" :initial-run-id="investigation.id" @close="investigation = null" />
  </section>
</template>

<script>
/** 各类任务的只读投影与已读标记；列表和弹窗各自绑定身份和请求代次，提醒不会触发确认、重试或模型调用。 */
import { taskInbox, readTaskNotice, getExport, cancelExport, downloadQueryExport } from '../api/queryWorkspace'
import { getCurrentUserId, getSessionToken } from '../auth'
import { displayLabel } from '../utils/presentation'
export default {
  name: 'TaskInbox', components: { InvestigationPanel: () => import('./agent/InvestigationPanel.vue') },
  data() { return { owner: getCurrentUserId(), token: getSessionToken(), disposed: false, sequence: 0, rows: [], kind: 'ALL', unread: false, nextCursor: null, loading: false, error: '', reading: null,
    exportVisible: false, exportJob: null, exportId: null, exportSequence: 0, exportLoading: false, exportError: '', investigation: null } },
  created() { this.refresh() }, beforeDestroy() { this.disposed = true; this.sequence++; this.closeExport() },
  methods: {
    current() { return !this.disposed && this.owner === getCurrentUserId() && this.token === getSessionToken() },
    time(value) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—' },
    label(value) { return { DISPATCH: '派单任务', PLAN: '待确认清单', ATTENTION: '待处理异常', INVESTIGATION: '异常调查', EXPORT: '查询导出' }[value] || value },
    status(value) { return { COMPLETED: '调查完成', PARTIAL: '部分完成', INTERRUPTED: '已中断' }[value] || displayLabel(value) },
    refresh() { this.rows = []; return this.load(null, false) },
    async load(before, append) { if (!this.current()) return; const seq = ++this.sequence, kind = this.kind, unread = this.unread; this.loading = true; this.error = ''; this.nextCursor = null
      try { const result = await taskInbox({ kind, unread, before }); if (!this.current() || seq !== this.sequence) return; this.rows = append ? [...this.rows, ...result.rows] : result.rows; this.nextCursor = result.nextCursor }
      catch (e) { if (this.current() && seq === this.sequence) this.error = e.message }
      finally { if (this.current() && seq === this.sequence) this.loading = false } },
    async markRead(item) { if (!this.current()) return; const seq = this.sequence; this.reading = item.eventKey
      try { await readTaskNotice(item); if (!this.current() || seq !== this.sequence) return; this.rows = this.rows.map(row => row.eventKey === item.eventKey ? { ...row, unread: false } : row); if (this.unread) this.rows = this.rows.filter(row => row.unread) }
      catch (e) { if (this.current() && seq === this.sequence) this.error = e.message }
      finally { if (this.current() && this.reading === item.eventKey) this.reading = null } },
    open(item) { if (!this.current()) return; if (item.kind === 'EXPORT') { this.closeExport(); this.exportVisible = true; this.exportId = item.id; return this.loadExport(item.id) } if (item.kind === 'INVESTIGATION') this.investigation = item; else if (item.target) this.$emit('plan', item.target); else this.$emit('refresh-dispatch') },
    closeExport() { this.exportSequence++; this.exportVisible = false; this.exportJob = null; this.exportId = null; this.exportLoading = false; this.exportError = '' },
    async loadExport(id) { const seq = ++this.exportSequence; this.exportLoading = true; this.exportError = ''; this.exportJob = null
      try { const job = await getExport(id); if (this.current() && this.exportVisible && this.exportId === id && seq === this.exportSequence) this.exportJob = job }
      catch (e) { if (this.current() && seq === this.exportSequence) this.exportError = e.message }
      finally { if (this.current() && seq === this.exportSequence) this.exportLoading = false } },
    async download() { const id = this.exportId, seq = this.exportSequence; this.exportLoading = true; try { await downloadQueryExport(id) } catch (e) { if (this.current() && seq === this.exportSequence) this.exportError = e.message } finally { if (this.current() && seq === this.exportSequence) this.exportLoading = false } },
    async cancel() { const id = this.exportId, seq = this.exportSequence; this.exportLoading = true; try { const job = await cancelExport(id); if (this.current() && seq === this.exportSequence) this.exportJob = job } catch (e) { if (this.current() && seq === this.exportSequence) this.exportError = e.message } finally { if (this.current() && seq === this.exportSequence) this.exportLoading = false } }
  }
}
</script>

<style scoped>
.task-inbox { margin: 20px 0 28px; padding-bottom: 20px; border-bottom: 1px solid #e4eaf1; }.inbox-toolbar { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }.inbox-toolbar h2 { font-size: 16px; }.inbox-error { color: #b33939; padding: 14px; background: #fff4f2; }.muted { color: #8492a5; }.el-table { margin-bottom: 12px; }.inbox-id { display: block; overflow: hidden; text-overflow: ellipsis; color: #8794a5; font: 11px/1.8 Consolas, monospace; }
</style>
