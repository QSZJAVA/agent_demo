<template>
  <div class="analysis-workspace">
    <div class="intro"><div><h2>分析与问答</h2><p>把统计、排序和明细展开连起来，用有来源的业务知识解释结果。</p></div><el-button v-if="catalog.admin" @click="$router.push('/knowledge')">管理知识资料</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="notice" />
    <el-card class="request-card">
      <div class="scope">
        <label>公司 <el-select v-model="form.companyCode" size="small" :disabled="submitting || !!sourceId" @change="scopeChanged"><el-option v-for="company in catalog.companies" :key="company" :label="company + ' 公司'" :value="company" /></el-select></label>
        <label>报表 <el-select v-model="form.reportId" size="small" :disabled="submitting || !!sourceId" @change="scopeChanged"><el-option v-for="report in catalog.reports" :key="report.reportId" :label="report.reportName" :value="report.reportId" /></el-select></label>
        <el-tag v-if="sourceId" type="info">已选择查询快照</el-tag><el-button v-if="sourceId" type="text" :disabled="submitting" @click="clearSource">改用当前数据</el-button>
      </div>
      <el-input v-model="form.question" type="textarea" :rows="3" :maxlength="2000" show-word-limit :disabled="submitting" placeholder="例如：按产品汇总上月销售额，取金额最高的两个产品，展开它们的明细；再说明销售金额的口径。" @input="questionChanged" />
      <div class="actions"><el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">开始分析</el-button><span>一次任务最多 6 步，可查看依据和中间结果。</span></div>
    </el-card>
    <div class="layout">
      <el-card class="history"><div slot="header" class="between"><b>最近任务</b><el-button type="text" :loading="historyBusy" @click="loadHistory">刷新</el-button></div>
        <p v-if="!history.length && !historyBusy" class="muted">暂无可查看的任务</p>
        <button v-for="item in history" :key="item.id" class="history-item" :class="{ selected: run && run.id === item.id }" @click="openRun(item.id)"><span>{{ item.request.question }}</span><small>{{ statusText(item.status) }} · {{ time(item.created_at) }}</small></button>
      </el-card>
      <div class="results" v-loading="loading">
        <el-card v-if="!run"><p class="muted">选择公司和报表，输入问题。历史任务可以直接打开，刷新不会重新分析。</p></el-card>
        <template v-else>
          <el-card><div class="between"><div><el-tag :type="run.status === 'COMPLETED' ? 'success' : 'info'">{{ statusText(run.status) }}</el-tag><span class="stage">{{ stageText(run.stage) }}</span></div><el-button v-if="isActive" size="small" @click="cancel">取消任务</el-button></div><h3>{{ run.request.question }}</h3><p class="muted">{{ run.request.companyCode }} 公司 · {{ reportName(run.request.reportId) }} · {{ time(run.created_at) }}</p><p v-if="run.message" role="status">{{ run.message }}</p><p v-if="run.plan">目标：{{ run.plan.objective }}</p></el-card>
          <el-card v-for="step in run.steps" :key="step.index" class="step-card">
            <div slot="header" class="between"><b>{{ step.index + 1 }}. {{ actionText(step.action) }}</b><el-tag size="small" :type="step.status === 'COMPLETED' ? 'success' : step.status === 'FAILED' ? 'danger' : 'info'">{{ stepText(step.status) }}</el-tag></div>
            <p v-if="step.message" role="alert">{{ step.message }}</p>
            <template v-if="step.result">
              <template v-if="step.result.kind === 'ROWS'"><p>完整结果 {{ step.result.count }} 条 · 当前展示 {{ step.result.displayedCount }} 条</p><p v-if="step.result.summary">合计：{{ amounts(step.result.summary.amountsByCurrency) }}</p><el-table :data="step.result.rows" size="mini" border><el-table-column v-for="column in columns(step.result.columns)" :key="column.name" :prop="column.name" :label="column.description || column.name" min-width="110"><template slot-scope="scope">{{ cell(scope.row[column.name]) }}</template></el-table-column></el-table><el-button size="small" class="row-button" @click="showRows(step.index)">查看完整明细</el-button><el-button v-if="step.action === 'QUERY' || step.action === 'SNAPSHOT'" type="text" @click="openSnapshot(step.result.snapshotId)">打开查询快照</el-button></template>
              <template v-else-if="step.result.kind === 'GROUPS'"><p>共 {{ step.result.groupsCount }} 组，展示 {{ step.result.displayedCount }} 组<span v-if="!step.result.allGroupsDisplayed">；后续排名使用完整集合</span></p><el-table :data="step.result.groups" size="mini" border><el-table-column label="分组" min-width="170"><template slot-scope="scope">{{ dimensions(scope.row.dimensions) }}</template></el-table-column><el-table-column prop="period" label="期间" min-width="90" /><el-table-column prop="currency" label="币种" width="70" /><el-table-column prop="count" label="条数" width="70" /><el-table-column prop="sum" label="金额合计" min-width="115" /><el-table-column prop="average" label="均值" min-width="110" /></el-table><details v-if="step.result.comparison"><summary>期间对比结果</summary><pre>{{ pretty(step.result.comparison) }}</pre></details></template>
              <knowledge-results v-else-if="step.result.kind === 'KNOWLEDGE'" :result="step.result" />
              <details v-else><summary>查看对比结果</summary><pre>{{ pretty(step.result) }}</pre></details>
            </template>
          </el-card>
          <el-card v-if="run.answer" class="answer"><div slot="header"><b>{{ answerText(run.answer.status) }}</b></div><div v-for="(claim, index) in run.answer.claims" :key="index" class="claim"><p>{{ claim.text }}</p><details v-for="(citation, j) in claim.citations" :key="j"><summary>依据：{{ sourceTitle(citation.sourceId) }}</summary><p class="muted">{{ sourceMeta(citation.sourceId) }}</p><blockquote>{{ citation.quote }}</blockquote></details></div><ul v-if="run.answer.limitations.length"><li v-for="(item, i) in run.answer.limitations" :key="i">{{ item }}</li></ul></el-card>
          <details v-if="run.usage && run.usage.length" class="usage"><summary>运行记录 · {{ run.usage.length }} 次模型调用</summary><pre>{{ pretty(run.usage) }}</pre></details>
        </template>
      </div>
    </div>
    <el-dialog title="分析步骤完整明细" :visible.sync="rowsVisible" width="85%" @closed="closeRows"><div v-loading="rowsBusy"><el-alert v-if="rowsError" type="error" :title="rowsError" :closable="false" /><template v-if="rowPage"><p>共 {{ rowPage.count }} 条，来自原查询快照</p><el-table :data="rowPage.rows" border size="mini"><el-table-column v-for="column in rowPage.columns" :key="column.name" :label="column.description || column.name" min-width="130"><template slot-scope="scope">{{ cell(scope.row[column.name]) }}</template></el-table-column></el-table><el-pagination layout="prev, pager, next" :page-size="20" :current-page="rowPage.page" :total="rowPage.count" @current-change="loadRows" /></template></div></el-dialog>
  </div>
</template>

<script>
/** 有限分析任务与引用工作区；异步响应绑定任务和页面代次，刷新只恢复原运行，提交失败重试沿用同一个请求号。 */
import KnowledgeResults from '../components/KnowledgeResults.vue'
import { analysisCatalog, analysisRuns, analysisRun, submitAnalysis, cancelAnalysis, analysisRows } from '../api/analysis'
import { queryPage } from '../api/queryWorkspace'

export default {
  name: 'AnalysisWorkspace',
  components: { KnowledgeResults },
  data() { return { ready: false, catalog: { companies: [], reports: [], admin: false }, form: { companyCode: '', reportId: '', question: '' }, sourceId: null, history: [], run: null, error: '', loading: false, submitting: false, historyBusy: false, pending: null, rowsVisible: false, rowStep: null, rowPage: null, rowsBusy: false, rowsError: '' } },
  computed: {
    canSubmit() { return this.ready && this.form.companyCode && this.form.reportId && this.form.question.trim() && !this.submitting },
    isActive() { return this.run && ['QUEUED', 'RUNNING'].includes(this.run.status) },
    sources() { const result = {}; for (const step of this.run?.steps || []) for (const source of step.sources || []) result[source.id] = source; return result }
  },
  watch: { '$route.query.run'(id) { if (id && id !== this.run?.id) this.openRun(id, false) } },
  created() { this._alive = true; this._seq = 0; this._detailSeq = 0; this._historySeq = 0; this.initialize() },
  beforeDestroy() { this._alive = false; this._seq++; this._detailSeq++; this._historySeq++; clearTimeout(this._timer) },
  methods: {
    async initialize() { const seq = ++this._seq; try { const catalog = await analysisCatalog(); if (!this.valid(seq)) return; this.catalog = catalog; this.form.companyCode = catalog.companies[0] || ''; this.form.reportId = catalog.reports[0]?.reportId || '';
      const source = this.$route.query.source; if (source) { this.sourceId = source; const snapshot = await queryPage(source, 1); if (!this.valid(seq)) return; if (snapshot.query.reportIds.length !== 1 || !snapshot.query.companyCode) throw new Error('请选择单一公司和报表的快照进行分析'); this.form.companyCode = snapshot.query.companyCode; this.form.reportId = snapshot.query.reportIds[0] } this.ready = true
      await this.loadHistory(); if (this.valid(seq) && this.$route.query.run) await this.openRun(this.$route.query.run, false)
    } catch (e) { if (this.valid(seq)) this.error = e.message || '无法加载分析范围' } },
    valid(seq) { return this._alive && seq === this._seq },
    async loadHistory() { const seq = ++this._historySeq; this.historyBusy = true; try { const rows = await analysisRuns(); if (this._alive && seq === this._historySeq) this.history = rows } catch (e) { if (this._alive && seq === this._historySeq) this.error = e.message } finally { if (this._alive && seq === this._historySeq) this.historyBusy = false } },
    scopeChanged() { this.pending = null },
    questionChanged() { this.pending = null },
    clearSource() { this.sourceId = null; this.pending = null; this.ready = !!this.catalog.reports.length; const query = { ...this.$route.query }; delete query.source; this.$router.replace({ path: '/analysis', query }).catch(() => {}) },
    async submit() { if (!this.canSubmit) return; const request = { ...this.form, question: this.form.question.trim(), snapshotId: this.sourceId }; const signature = JSON.stringify(request); if (!this.pending || this.pending.signature !== signature) this.pending = { request, signature, key: 'analysis-' + crypto.randomUUID() };
      const pending = this.pending; const seq = ++this._seq; this.submitting = true; this.error = ''; clearTimeout(this._timer); this.closeRows();
      try { const run = await submitAnalysis(pending.request, pending.key); if (!this.valid(seq)) return; this.pending = null; this.run = run; await this.setRoute(run.id); if (this.valid(seq)) { this.schedule(); this.loadHistory() } }
      catch (e) { if (this.valid(seq)) this.error = (e.message || '提交结果尚未确认') + '。重试会沿用本次请求号，也可刷新最近任务核对。' }
      finally { if (this.valid(seq)) this.submitting = false }
    },
    async setRoute(id) { if (this.$route.query.run !== id) await this.$router.replace({ path: '/analysis', query: { ...(this.sourceId ? { source: this.sourceId } : {}), run: id } }).catch(() => {}) },
    async openRun(id, route = true) { if (this.submitting) return; const seq = ++this._seq; this.loading = true; this.error = ''; this.run = null; this.closeRows(); clearTimeout(this._timer);
      try { const run = await analysisRun(id); if (!this.valid(seq)) return; this.run = run; if (route) await this.setRoute(id); if (this.valid(seq)) this.schedule() } catch (e) { if (this.valid(seq)) this.error = e.message || '任务无法恢复' } finally { if (this.valid(seq)) this.loading = false } },
    schedule() { clearTimeout(this._timer); if (this._alive && this.isActive) this._timer = setTimeout(() => this.poll(), 1200) },
    async poll() { if (!this._alive || !this.run) return; const id = this.run.id; const seq = this._seq;
      try { const run = await analysisRun(id); if (!this.valid(seq) || this.run?.id !== id || run.version < this.run.version) return; this.run = run; this.error = ''; if (this.isActive) this.schedule(); else this.loadHistory() }
      catch (e) { if (this.valid(seq) && this.run?.id === id) { this.run = null; this.closeRows(); this.error = e.message || '无法读取任务，请从最近任务重新打开' } } },
    async cancel() { if (!this.isActive) return; const id = this.run.id; const seq = this._seq; try { const result = await cancelAnalysis(id); if (!this.valid(seq) || this.run?.id !== id) return; this.run = result; clearTimeout(this._timer); this.loadHistory() } catch (e) { if (this.valid(seq)) this.error = e.message } },
    showRows(step) { this.rowsVisible = true; this.rowStep = step; this.rowPage = null; this.loadRows(1) },
    closeRows() { this._detailSeq++; this.rowsVisible = false; this.rowStep = null; this.rowPage = null; this.rowsError = ''; this.rowsBusy = false },
    async loadRows(page) { if (!this.run || this.rowStep === null) return; const id = this.run.id, step = this.rowStep, seq = ++this._detailSeq; this.rowsBusy = true; this.rowsError = ''; this.rowPage = null;
      try { const result = await analysisRows(id, step, page); if (this._alive && seq === this._detailSeq && this.rowsVisible && this.run?.id === id && this.rowStep === step) this.rowPage = result }
      catch (e) { if (this._alive && seq === this._detailSeq && this.rowsVisible) this.rowsError = e.message } finally { if (this._alive && seq === this._detailSeq) this.rowsBusy = false } },
    openSnapshot(id) { this.$router.push({ path: '/query-workspace', query: { run: id, page: '1' } }) },
    columns(values) { return (values || []).filter(c => !['rowKey', 'reportId'].includes(c.name)).slice(0, 6) },
    reportName(id) { return this.catalog.reports.find(r => r.reportId === id)?.reportName || id },
    sourceTitle(id) { return this.sources[id]?.title || '来源不可用' },
    sourceMeta(id) { const source = this.sources[id]; return source ? `${source.scope} · 版本 ${source.version} · ${source.observedAt}` : '' },
    dimensions(value) { return Object.entries(value || {}).map(([key, item]) => `${key}：${item === null ? '空值' : item}`).join(' / ') || '全部' },
    amounts(value) { return Object.entries(value || {}).map(([currency, amount]) => `${amount} ${currency}`).join('，') || '无可合计金额' },
    cell(value) { return value === null || value === undefined ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value) },
    pretty(value) { return JSON.stringify(value, null, 2) },
    time(value) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—' },
    actionText(value) { return ({ QUERY: '查询基础数据', SNAPSHOT: '读取选定快照', GROUP: '分组与指标', RANK: '完整集合排名', DRILLDOWN: '展开相关明细', COMPARE: '查询结果对比', KNOWLEDGE: '检索业务依据' })[value] || value },
    stepText(value) { if (value === 'RUNNING' && !this.isActive) return '未完成'; return ({ RUNNING: '执行中', COMPLETED: '已完成', FAILED: '未完成' })[value] || value },
    statusText(value) { return ({ QUEUED: '等待执行', RUNNING: '分析中', COMPLETED: '已完成', CLARIFY: '需要补充说明', PARTIAL: '部分完成', FAILED: '未完成', CANCELLED: '已取消', INTERRUPTED: '已中断' })[value] || value },
    stageText(value) { return ({ QUEUED: '排队中', PLAN: '整理分析步骤', PLAN_REVIEW: '核对分析范围和要求', STEP: '读取与计算', ANSWER: '整理有依据的回答', ANSWER_REVIEW: '核对回答依据', FINISHED: '' })[value] ?? value },
    answerText(value) { return ({ ANSWERED: '有依据的回答', INSUFFICIENT: '部分问题缺少充分依据', CONFLICTING: '资料存在冲突，需业务确认' })[value] || value }
  }
}
</script>

<style scoped>
.intro,.between,.scope,.actions { display:flex; align-items:center; gap:16px; }.intro,.between { justify-content:space-between; }.intro h2 { margin:0; }.intro p,.muted,.actions span { color:#6b7c91; font-size:13px; }.notice,.request-card { margin-bottom:18px; }.scope { flex-wrap:wrap; margin-bottom:16px; }.scope label { display:flex; align-items:center; gap:8px; font-size:13px; }.actions { margin-top:14px; }.layout { display:grid; grid-template-columns:250px minmax(0,1fr); gap:18px; }.history { align-self:start; }.history-item { background:white; border:0; border-bottom:1px solid #edf0f5; padding:13px 8px; width:100%; text-align:left; cursor:pointer; color:#34475f; }.history-item span { display:block; line-height:1.6; }.history-item small { display:block; color:#8290a3; margin-top:5px; }.history-item.selected { background:#edf5ff; }.results > .el-card { margin-bottom:16px; }.stage { font-size:13px; color:#6b7c91; margin-left:12px; }details { margin:12px 0; }summary { cursor:pointer; color:#3479c7; font-size:13px; }pre,blockquote { white-space:pre-wrap; overflow-wrap:anywhere; background:#f6f8fc; padding:14px; border-radius:5px; font-size:13px; line-height:1.7; }blockquote { margin:10px 0; border-left:3px solid #77a8de; }.claim { border-bottom:1px solid #edf0f5; padding-bottom:12px; }.claim p { line-height:1.8; }.row-button { margin-top:12px; }.el-pagination { margin-top:14px; }.usage { padding:0 12px; }@media(max-width:1100px){.layout { grid-template-columns:1fr; }.history { max-height:260px; overflow:auto; }.intro { align-items:flex-start; }}
</style>
