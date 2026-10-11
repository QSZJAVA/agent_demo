<template>
  <section class="query-workspace">
    <el-card shadow="never">
      <div slot="header" class="page-toolbar"><div><h1>查询工作区</h1><p>在完整查询快照上翻页、分析、导出和比较，保存日常查询条件</p></div><el-button :loading="listing" size="small" @click="refresh">刷新目录</el-button></div>
      <div v-if="listError" class="workspace-error" role="alert">{{ listError }} <el-button size="mini" @click="refresh">重试</el-button></div>
      <el-tabs value="runs">
        <el-tab-pane label="最近查询" name="runs">
          <el-table :data="runs" size="small" row-key="snapshot.id" empty-text="暂无查询快照，请先在业务助手中完成查询">
            <el-table-column label="查询范围" min-width="180"><template slot-scope="s">{{ domain(s.row.query.domain) }} · {{ s.row.query.companyCode || '授权公司' }} · {{ s.row.query.reportIds.length }} 张报表</template></el-table-column>
            <el-table-column prop="total" label="完整匹配数" width="110" />
            <el-table-column label="观测时间" min-width="160"><template slot-scope="s">{{ time(s.row.observedAt) }}</template></el-table-column>
            <el-table-column label="有效期至" min-width="160"><template slot-scope="s">{{ time(s.row.snapshot.expiresAt) }}</template></el-table-column>
            <el-table-column label="操作" width="150"><template slot-scope="s"><el-button type="text" @click="openRun(s.row.snapshot.id)">打开</el-button><el-button type="text" @click="removeRun(s.row.snapshot.id)">删除快照</el-button></template></el-table-column>
          </el-table>
          <el-button size="mini" :disabled="!nextCursor || listing" @click="loadMore">加载更早查询</el-button>
        </el-tab-pane>
        <el-tab-pane label="常用查询" name="saved">
          <el-table :data="saved" size="small" empty-text="打开一份查询快照后即可保存常用条件">
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="日期方式" width="190"><template slot-scope="s">{{ s.row.clock_mode === 'LAST_MONTH' ? '每次重新计算上月' : '固定条件和日期' }}</template></el-table-column>
            <el-table-column label="操作" width="185"><template slot-scope="s"><el-button type="text" :disabled="working" @click="runSaved(s.row)">重新运行</el-button><el-button type="text" @click="renameSaved(s.row)">重命名</el-button><el-button type="text" @click="removeSaved(s.row)">删除</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <el-card v-if="selectedId" shadow="never" class="workspace-detail" v-loading="loading">
      <div slot="header" class="workspace-toolbar"><strong>当前查询快照</strong><el-button size="mini" :disabled="loading" @click="openRun(selectedId, selectedPage)">重新读取</el-button><span class="muted">{{ selectedId }}</span></div>
      <div v-if="error" role="alert" class="workspace-error">{{ error }}</div>
      <template v-if="result">
        <business-query-card :payload="result" :busy="loading || working" :latest="true" snapshot-details @page="loadPage" />
        <p class="muted">完整性：已完整读取 · 进入筛选 {{ result.snapshot.scannedRows }} 条 · {{ result.snapshot.consistency === 'LOCAL_REPEATABLE_READ' ? '本地来源使用同一次读取事务' : '按来源观测时间保存，不代表跨系统同时快照' }} · {{ time(result.snapshot.expiresAt) }} 到期</p>
        <div class="workspace-toolbar"><el-button size="small" :disabled="working" @click="saveVisible = true">保存为常用查询</el-button><el-button size="small" :disabled="working" @click="startExport(false)">导出完整结果</el-button><el-button v-if="result.query.domain === 'REPORT' && result.query.companyCode && result.query.reportIds.length === 1" size="small" :disabled="working" @click="$router.push({ path: '/analysis', query: { source: result.snapshot.id } })">分析此结果</el-button><el-button type="text" @click="$router.push('/tasks')">查看任务与提醒</el-button></div>
        <div v-if="exportJob" class="export-state" role="status">
          <strong>导出：{{ status(exportJob.status) }}</strong><span>{{ exportJob.message || '可以稍后刷新，也可从我的任务找回' }}</span><span>已处理 {{ exportJob.progressRows }} 条</span>
          <el-button size="mini" :disabled="working" @click="refreshExport">刷新导出状态</el-button>
          <el-button v-if="exportJob.status === 'SUCCEEDED'" type="primary" size="mini" @click="download">下载 CSV</el-button>
          <el-button v-if="['QUEUED','RUNNING'].includes(exportJob.status)" size="mini" @click="cancelCurrentExport">取消导出</el-button>
          <el-button v-if="['FAILED','CANCELLED'].includes(exportJob.status)" size="mini" @click="startExport(true)">重新生成</el-button>
        </div>
        <el-tabs value="analysis" class="workspace-tools">
          <el-tab-pane label="指标分析" name="analysis">
            <div class="workspace-toolbar">
              <el-select v-model="analysisForm.dimensions" multiple :multiple-limit="3" placeholder="分组维度，最多3项" aria-label="分组维度" @change="clearAnalysis"><el-option v-for="f in result.columns" :key="f.name" :value="f.name" :label="f.description" /></el-select>
              <el-select v-model="analysisForm.bucket" aria-label="趋势粒度" @change="changeBucket"><el-option label="不按日期分组" value="NONE" /><el-option label="按日趋势" value="DAY" /><el-option label="按月趋势" value="MONTH" /></el-select>
              <el-select v-if="analysisForm.bucket !== 'NONE'" v-model="analysisForm.dateField" placeholder="日期口径" aria-label="日期口径" @change="clearAnalysis"><el-option v-for="f in dateFields" :key="f.name" :value="f.name" :label="f.description" /></el-select>
              <el-date-picker v-if="analysisForm.bucket === 'MONTH'" v-model="analysisForm.compareMonth" type="month" value-format="yyyy-MM" placeholder="可选同比/环比月份" @change="clearAnalysis" />
              <el-button type="primary" size="small" :loading="working" @click="analyze">计算完整范围</el-button>
            </div>
            <p class="muted">金额按币种分别汇总；均值只包含非空金额，保留6位小数。同比/环比需要完整覆盖已结束的三个期间。</p>
            <el-table v-if="analysis" :data="analysis.groups" size="small" empty-text="没有可统计记录">
              <el-table-column label="维度" min-width="180"><template slot-scope="s">{{ dimensions(s.row.dimensions) }}</template></el-table-column><el-table-column prop="period" label="日期期间" width="120" /><el-table-column prop="currency" label="币种" width="90" /><el-table-column prop="count" label="记录数" width="90" /><el-table-column label="金额合计" min-width="200"><template slot-scope="s">{{ s.row.sum == null ? '未提供' : s.row.sum }}</template></el-table-column><el-table-column label="平均金额" min-width="170"><template slot-scope="s">{{ s.row.average == null ? '未提供' : s.row.average }}</template></el-table-column><el-table-column prop="missingAmountCount" label="缺少可汇总金额" width="135" />
            </el-table>
            <template v-if="analysis && analysis.comparison"><el-alert v-if="!analysis.comparison.available" :title="analysis.comparison.reason" type="warning" :closable="false" /><div v-else><p>{{ analysis.comparison.scope }}</p><el-table :data="analysis.comparison.rows" size="small"><el-table-column prop="currency" label="币种" /><el-table-column prop="current" label="本期金额" /><el-table-column prop="previousMonth" label="上月金额" /><el-table-column prop="previousYear" label="去年同月金额" /><el-table-column label="环比%"><template slot-scope="s">{{ s.row.monthOverMonthPercent == null ? '不可计算' : s.row.monthOverMonthPercent }}</template></el-table-column><el-table-column label="同比%"><template slot-scope="s">{{ s.row.yearOverYearPercent == null ? '不可计算' : s.row.yearOverYearPercent }}</template></el-table-column></el-table></div></template>
          </el-tab-pane>
          <el-tab-pane label="查询对比" name="compare">
            <div class="workspace-toolbar"><el-select v-model="compareId" placeholder="选择较早的查询快照" aria-label="比较快照" @change="comparison = null"><el-option v-for="r in runs.filter(r => r.snapshot.id !== selectedId)" :key="r.snapshot.id" :value="r.snapshot.id" :label="`${time(r.observedAt)} · ${r.total} 条`" /></el-select><el-button :disabled="!compareId" :loading="working" size="small" @click="compare">与当前查询比较</el-button></div>
            <template v-if="comparison"><el-alert :title="comparison.sameDefinition ? '两次查询条件和业务口径一致' : '查询条件或口径不同：下面仅表示结果集合差异，不能解释为业务增长'" :type="comparison.sameDefinition ? 'info' : 'warning'" :closable="false" /><p>{{ time(comparison.before.observedAt) }} → {{ time(comparison.after.observedAt) }}；新增 {{ comparison.added }}、移除 {{ comparison.removed }}、字段变化 {{ comparison.changed }} 条。</p><p v-for="(value, currency) in comparison.amountDeltaByCurrency" :key="currency">{{ currency }} 金额差额：{{ value }}</p><el-table :data="comparison.details" size="small"><el-table-column prop="rowKey" label="记录标识" min-width="180" /><el-table-column label="差异" width="100"><template slot-scope="s">{{ { ADDED: '新增', REMOVED: '移除', CHANGED: '字段变更' }[s.row.kind] }}</template></el-table-column><el-table-column label="变化字段"><template slot-scope="s">{{ s.row.fields.join('、') || '整条记录' }}</template></el-table-column></el-table><p v-if="comparison.detailsTruncated">明细仅展示前100项，以上计数覆盖全部变化。</p></template>
          </el-tab-pane>
          <el-tab-pane label="Top N 候选" name="rank">
            <div class="workspace-toolbar"><el-select v-model="rankForm.field" aria-label="排名字段" @change="clearRanking"><el-option v-for="f in rankFields" :key="f.name" :value="f.name" :label="f.description" /></el-select><el-select v-model="rankForm.descending" aria-label="排名方向" @change="clearRanking"><el-option label="从大到小" :value="true" /><el-option label="从小到大" :value="false" /></el-select><el-input-number v-model="rankForm.count" :min="1" :max="50" :precision="0" aria-label="候选数量" @change="clearRanking" /><el-button size="small" :loading="working" @click="rank">查看完整范围排名</el-button></div>
            <p class="muted">并列按稳定业务标识排序，空值在末尾。金额排名要求唯一币种；准备清单时重新核验当前规则和事实。</p>
            <el-table v-if="rankRows" :data="rankRows" size="small" row-key="rowKey"><el-table-column type="index" label="排名" width="70" /><el-table-column prop="docNo" label="单据" /><el-table-column prop="companyCode" label="公司" /><el-table-column label="排名值"><template slot-scope="s">{{ s.row[acceptedRank.field] }}</template></el-table-column></el-table>
            <el-button v-if="rankRows && result.query.domain === 'REPORT'" type="warning" size="small" :loading="working" @click="preparePlan">为这 {{ rankRows.length }} 条生成待确认清单</el-button>
            <plan-card v-if="plan" :key="plan.planId" :payload="plan" :status="plan.status" :busy="working" :refresh-version="planRefresh" @confirm="planAction('CONFIRM')" @cancel="cancelPlan" @reconcile="planAction('RECONCILE')" @resume="refreshPlan" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </el-card>
    <el-dialog title="保存常用查询" :visible.sync="saveVisible" width="500px">
      <el-form label-width="100px"><el-form-item label="查询名称"><el-input v-model="saveForm.name" maxlength="100" /></el-form-item><el-form-item label="日期方式"><el-select v-model="saveForm.clockMode" @change="changeClock"><el-option label="保留固定条件和日期" value="FIXED" /><el-option label="每次重算上个自然月" value="LAST_MONTH" /></el-select></el-form-item><el-form-item v-if="saveForm.clockMode === 'LAST_MONTH'" label="日期字段"><el-select v-model="saveForm.dateField"><el-option v-for="f in dateFields" :key="f.name" :label="f.description" :value="f.name" /></el-select></el-form-item><p>保存的是查询定义。下次运行仍检查当前权限与业务口径；“上月”按上海时区重新计算。</p></el-form>
      <span slot="footer"><el-button @click="saveVisible = false">取消</el-button><el-button type="primary" :loading="working" @click="saveCurrent">保存</el-button></span>
    </el-dialog>
  </section>
</template>

<script>
/**
 * 查询快照工作区：各操作绑定运行ID、请求代次和登录上下文，切换或销毁后迟到响应不得回写。
 * 只在用户点击生成清单后调用准备接口，确认与核对复用已有持久派单任务；页面刷新不重放业务动作。
 */
import * as api from '../api/queryWorkspace'
import http from '../api/http'
import { getCurrentUserId, getSessionToken } from '../auth'
import { runDispatchJob } from '../api/dispatchJob'
import BusinessQueryCard from '../components/agent/BusinessQueryCard.vue'
import PlanCard from '../components/agent/PlanCard.vue'
export default {
  name: 'QueryWorkspace', components: { BusinessQueryCard, PlanCard },
  data() { return { owner: getCurrentUserId(), token: getSessionToken(), disposed: false, sequence: 0, listSequence: 0, actionSequence: 0,
    runs: [], saved: [], nextCursor: null, listing: false, listError: '', selectedId: null, selectedPage: 1, result: null, loading: false, working: false, error: '',
    saveVisible: false, saveForm: { name: '', clockMode: 'FIXED', dateField: null }, exportJob: null,
    analysisForm: { dimensions: [], bucket: 'NONE', dateField: null, compareMonth: null }, analysis: null, compareId: null, comparison: null,
    rankForm: { field: 'amount', descending: true, count: 3 }, rankRows: null, acceptedRank: null, plan: null, planRefresh: 0 } },
  computed: { dateFields() { return (this.result?.columns || []).filter(f => f.type === 'date') }, rankFields() { return (this.result?.columns || []).filter(f => ['decimal','integer','long','date','string'].includes(f.type)) } },
  created() { this.refresh(); this.restoreRoute(this.$route.query) },
  beforeDestroy() { this.disposed = true; this.sequence++; this.listSequence++; this.actionSequence++; this.result = null },
  watch: { '$route.query'(query) { this.restoreRoute(query) } },
  methods: {
    current() { return !this.disposed && this.owner === getCurrentUserId() && this.token === getSessionToken() },
    /** 运行和页码共同构成恢复位置；路由变化只读取快照，不重放保存、导出或派单动作。 */
    restoreRoute(query) { const id = typeof query.run === 'string' ? query.run : null, page = this.pageNumber(query.page)
      if (!id) { if (this.selectedId) this.clearSelection(); return }
      if (id !== this.selectedId || page !== this.selectedPage) return this.openRun(id, page) },
    pageNumber(value) { const page = Number(value || 1); return Number.isInteger(page) && page >= 1 && page <= 100000 ? page : 1 },
    /** 在发起读取时记录目标页，失败刷新仍可重试；仅替换恢复位置，不制造额外历史导航。 */
    syncRoute(id, page = 1) { if (!this.current()) return
      const query = { ...this.$route.query }; if (id) { query.run = id; query.page = String(page) } else { delete query.run; delete query.page }
      if (query.run === this.$route.query.run && query.page === this.$route.query.page) return
      const seq = this.sequence
      return this.$router.replace({ path: '/query-workspace', query }).catch(e => { if (this.current() && seq === this.sequence && e?.name !== 'NavigationDuplicated') this.error = e.message || '查询恢复地址更新失败' }) },
    clearSelection() { this.sequence++; this.actionSequence++; this.selectedId = null; this.selectedPage = 1; this.result = null; this.loading = false; this.working = false; this.error = ''; this.analysis = null; this.comparison = null; this.compareId = null; this.exportJob = null; this.clearRanking(); this.saveVisible = false },
    time(value) { if (!value) return '—'; return new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) },
    domain(value) { return { REPORT: '报表数据', DISPATCH: '派单记录', WORK_ORDER: '工单' }[value] || value },
    status(value) { return { QUEUED: '排队中', RUNNING: '生成中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消', EXPIRED: '已过期' }[value] || value },
    dimensions(value) { return Object.entries(value).map(([key, v]) => `${this.result.columns.find(f => f.name === key)?.description || key}：${v == null ? '未提供' : v}`).join('；') || '全部记录' },
    async refresh() { return this.loadDirectory(null, false) },
    async loadMore() { if (this.nextCursor && !this.listing) return this.loadDirectory(this.nextCursor, true) },
    async loadDirectory(cursor, append) { const seq = ++this.listSequence; this.listing = true; this.listError = ''
      try { const [runs, saved] = await Promise.all([api.listQueryRuns(cursor), api.listSavedQueries()]); if (!this.current() || seq !== this.listSequence) return; this.runs = append ? [...this.runs, ...runs.rows] : runs.rows; this.nextCursor = runs.nextCursor; this.saved = saved }
      catch (e) { if (this.current() && seq === this.listSequence) this.listError = e.message }
      finally { if (this.current() && seq === this.listSequence) this.listing = false } },
    async openRun(id, page = 1) { if (!this.current()) return; page = this.pageNumber(page); const seq = ++this.sequence; this.actionSequence++; this.working = false; this.loading = true; this.error = ''; this.selectedId = id; this.selectedPage = page; this.result = null; this.analysis = null; this.comparison = null; this.compareId = null; this.exportJob = null; this.clearRanking(); this.plan = null; this.saveVisible = false; this.syncRoute(id, page)
      try { const result = await api.queryPage(id, page); if (!this.current() || seq !== this.sequence || id !== this.selectedId) return; this.result = result; this.analysisForm = { dimensions: [], bucket: 'NONE', dateField: null, compareMonth: null }; if (!this.rankFields.some(f => f.name === this.rankForm.field)) this.rankForm.field = this.rankFields[0]?.name || '' }
      catch (e) { if (this.current() && seq === this.sequence) this.error = e.message }
      finally { if (this.current() && seq === this.sequence) this.loading = false } },
    async loadPage(page) { if (!this.current() || !this.selectedId) return; page = this.pageNumber(page); const id = this.selectedId, seq = ++this.sequence; this.selectedPage = page; this.loading = true; this.error = ''; this.result = null; this.syncRoute(id, page)
      try { const result = await api.queryPage(id, page); if (this.current() && seq === this.sequence && id === this.selectedId) this.result = result }
      catch (e) { if (this.current() && seq === this.sequence) this.error = e.message }
      finally { if (this.current() && seq === this.sequence) this.loading = false } },
    /** 动作响应必须属于发起时的快照，晚到失败也不能覆盖另一个查询。 */
    async perform(work, apply = () => {}) { if (!this.current() || this.working) return; const seq = ++this.actionSequence, id = this.selectedId; this.working = true; this.error = ''
      try { const value = await work(); if (this.current() && seq === this.actionSequence && id === this.selectedId) return await apply(value) }
      catch (e) { if (this.current() && seq === this.actionSequence) this.error = e.message }
      finally { if (this.current() && seq === this.actionSequence) this.working = false } },
    changeClock() { this.saveForm.dateField = this.saveForm.clockMode === 'FIXED' ? null : (this.dateFields[0]?.name || null) },
    /** 参数变化即撤下旧指标，迟到响应仍须通过完整参数比对才能显示。 */
    clearAnalysis() { this.analysis = null },
    changeBucket() { this.analysis = null; this.analysisForm.dateField = this.analysisForm.bucket === 'NONE' ? null : (this.dateFields[0]?.name || null); if (this.analysisForm.bucket !== 'MONTH') this.analysisForm.compareMonth = null },
    saveCurrent() { const id = this.selectedId, request = { ...this.saveForm }; return this.perform(() => api.saveQuery(id, request), async () => { this.saveVisible = false; await this.refresh() }) },
    runSaved(row) { return this.perform(() => api.runSavedQuery(row.id), async result => { await this.openRun(result.snapshot.id); await this.refresh() }) },
    async renameSaved(row) { try { const { value } = await this.$prompt('输入新的查询名称', '重命名常用查询', { inputValue: row.name, inputValidator: v => !!v?.trim() && v.length <= 100 }); if (!this.current()) return; await api.updateSavedQuery(row.id, row.version, { name: value, clockMode: row.clock_mode, dateField: row.date_field }); if (this.current()) this.refresh() } catch (e) { if (e?.message && this.current()) this.listError = e.message } },
    async removeSaved(row) { try { await this.$confirm(`删除常用查询“${row.name}”？`, '删除查询定义'); if (!this.current()) return; await api.deleteSavedQuery(row.id, row.version); if (this.current()) this.refresh() } catch (e) { if (e?.message && this.current()) this.listError = e.message } },
    async removeRun(id) { try { await this.$confirm('删除这份结果快照后，关联导出将无法下载；业务数据不受影响。', '删除查询快照'); if (!this.current()) return; await api.expireQuery(id); if (!this.current()) return; if (this.selectedId === id) { this.clearSelection(); this.syncRoute(null) } this.refresh() } catch (e) { if (e?.message && this.current()) this.listError = e.message } },
    analyze() { const id = this.selectedId, request = JSON.parse(JSON.stringify(this.analysisForm)); this.analysis = null; return this.perform(() => api.analyzeQuery(id, request), result => { if (JSON.stringify(request) === JSON.stringify(this.analysisForm)) this.analysis = result }) },
    compare() { const id = this.selectedId, before = this.compareId; this.comparison = null; return this.perform(() => api.compareQueries(before, id), result => { if (this.compareId === before) this.comparison = result }) },
    clearRanking() { this.rankRows = null; this.acceptedRank = null; this.plan = null },
    rank() { const id = this.selectedId, request = { ...this.rankForm }; this.clearRanking(); return this.perform(() => api.rankQuery(id, request), rows => { if (JSON.stringify(request) === JSON.stringify(this.rankForm)) { this.rankRows = rows; this.acceptedRank = request } }) },
    preparePlan() { if (!this.acceptedRank) return; const id = this.selectedId, request = { ...this.acceptedRank }; return this.perform(() => api.prepareRankPlan(id, request), plan => { this.plan = plan }) },
    startExport(fresh) { const id = this.selectedId; return this.perform(() => api.submitQueryExport(id, fresh), job => { this.exportJob = job }) },
    refreshExport() { if (this.exportJob) return this.perform(() => api.getExport(this.exportJob.id), job => { this.exportJob = job }) },
    cancelCurrentExport() { if (this.exportJob) return this.perform(() => api.cancelExport(this.exportJob.id), job => { this.exportJob = job }) },
    download() { if (this.exportJob) return this.perform(() => api.downloadQueryExport(this.exportJob.id)) },
    planAction(action) { if (!this.plan) return; const id = this.plan.planId; return this.perform(() => runDispatchJob({ action, planId: id, reportId: null, recordIds: null, reason: null }).then(() => http.get(`/dispatch/plans/${encodeURIComponent(id)}`)), plan => { if (this.plan?.planId === id) { this.plan = plan; this.planRefresh++ } }) },
    cancelPlan() { if (!this.plan) return; const id = this.plan.planId; return this.perform(() => http.post(`/dispatch/plans/${encodeURIComponent(id)}/cancel`).then(() => http.get(`/dispatch/plans/${encodeURIComponent(id)}`)), plan => { if (this.plan?.planId === id) this.plan = plan }) },
    refreshPlan() { if (!this.plan) return; const id = this.plan.planId; return this.perform(() => http.get(`/dispatch/plans/${encodeURIComponent(id)}`), plan => { if (this.plan?.planId === id) { this.plan = plan; this.planRefresh++ } }) }
  }
}
</script>

<style scoped>
.workspace-detail { margin-top: 20px; }.workspace-toolbar, .export-state { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin: 14px 0; }.workspace-error { padding: 14px; background: #fff2f0; color: #ab332c; margin: 10px 0; }.muted { font-size: 12px; color: #687c91; overflow-wrap: anywhere; }.workspace-tools { margin-top: 22px; }.export-state { padding: 16px; background: #f0f7fc; font-size: 13px; }.el-select { max-width: 260px; }.el-table { margin: 12px 0; }
</style>
