<template>
  <div class="analysis-workspace">
    <div class="intro"><div><h2>分析与问答</h2><p>把统计、排序和明细展开连起来，用有来源的业务知识解释结果。</p></div><el-button v-if="catalog.admin" :disabled="!!pending" @click="$router.push('/knowledge')">管理知识资料</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="notice" />
    <el-alert v-if="pendingNotice" :title="pendingNotice" type="info" :closable="false" show-icon class="notice" />
    <div v-if="pendingStorageError" class="notice"><el-alert :title="pendingStorageError" type="warning" :closable="false" show-icon /><el-button type="text" :disabled="submitting || restoring" @click="initialize">重新检查恢复记录</el-button></div>
    <el-card ref="requestCard" class="request-card">
      <el-alert v-if="preparationMessage" :title="preparationMessage" type="info" :closable="false" show-icon class="notice" />
      <el-alert v-if="pending && !submitting" title="本次提交结果尚未确认。请点击“核对本次提交”，沿用原问题、范围和请求号核对；核对完成后可修改问题或开始新任务。" type="warning" :closable="false" show-icon class="notice" />
      <div class="scope">
        <label>执行范围 <el-select v-model="form.mode" size="small" :disabled="formLocked" @change="modeChanged"><el-option label="当前数据分析" value="CURRENT_DATA" /><el-option label="仅分析选定结果" value="SNAPSHOT_ONLY" :disabled="!sourceId" /><el-option label="业务知识问答" value="KNOWLEDGE_ONLY" /></el-select></label>
        <label>公司 <el-select v-model="form.companyCode" size="small" :disabled="formLocked || !!sourceId" @change="scopeChanged"><el-option v-for="company in catalog.companies" :key="company" :label="company + ' 公司'" :value="company" /></el-select></label>
        <label>报表 <el-select v-model="form.reportId" size="small" :disabled="formLocked || !!sourceId" @change="scopeChanged"><el-option v-for="report in catalog.reports" :key="report.reportId" :label="report.reportName" :value="report.reportId" /></el-select></label>
        <el-tag v-if="sourceId" type="info">{{ sourceReady ? '已选择查询快照' : '查询快照尚未通过核对' }}</el-tag><el-button v-if="sourceId" type="text" :disabled="formLocked" @click="clearSource">改用当前数据</el-button>
      </div>
      <p class="muted">{{ modeHint }}</p>
      <el-input ref="questionInput" v-model="form.question" type="textarea" :rows="3" :maxlength="2000" show-word-limit :disabled="formLocked" placeholder="例如：按产品汇总上月销售额，取金额最高的两个产品，展开它们的明细；再说明销售金额的口径。" @input="questionChanged" />
      <div class="actions"><el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">{{ submitLabel }}</el-button><span>一次任务最多 6 步，可查看依据和中间结果。</span></div>
    </el-card>
    <div class="layout">
      <el-card class="history"><div slot="header" class="between"><b>最近任务</b><el-button type="text" :loading="historyBusy" @click="loadHistory">刷新</el-button></div>
        <p v-if="!history.length && !historyBusy" class="muted">暂无可查看的任务</p>
        <button v-for="item in history" :key="item.id" class="history-item" :class="{ selected: run && run.id === item.id }" @click="openRun(item.id)"><span>{{ item.request.question }}</span><small>{{ statusText(item.status) }} · {{ time(item.created_at) }}</small></button>
      </el-card>
      <div class="results" v-loading="loading">
        <el-card v-if="!run"><p class="muted">选择公司和报表，输入问题。历史任务可以直接打开，刷新不会重新分析。</p></el-card>
        <template v-else>
          <el-card><div class="between"><div><el-tag :type="run.status === 'COMPLETED' ? 'success' : 'info'">{{ statusText(run.status) }}</el-tag><span class="stage">{{ stageText(run.stage) }}</span></div><el-button v-if="isActive" size="small" :disabled="formLocked" @click="cancel">取消任务</el-button><el-button v-if="isTerminal" size="small" :loading="preparing" :disabled="submitting || loading || !!pending" @click="prepareRun">{{ prepareLabel }}</el-button></div><h3>{{ run.request.question }}</h3><p class="muted">{{ modeText(run.request.mode) }} · {{ run.request.companyCode }} 公司 · {{ reportName(run.request.reportId) }} · {{ time(run.created_at) }}</p><p v-if="run.message" role="status">{{ run.message }}</p><p v-if="run.plan">目标：{{ run.plan.objective }}</p></el-card>
          <el-card v-for="step in run.steps" :key="step.index" class="step-card">
            <div slot="header" class="between"><b>{{ step.index + 1 }}. {{ actionText(step.action) }}</b><el-tag size="small" :type="step.status === 'COMPLETED' ? 'success' : step.status === 'FAILED' ? 'danger' : 'info'">{{ stepText(step.status) }}</el-tag></div>
            <p v-if="step.message" role="alert">{{ step.message }}</p>
            <template v-if="step.result">
              <template v-if="step.result.kind === 'ROWS'"><p>完整结果 {{ step.result.count }} 条 · 当前展示 {{ step.result.displayedCount }} 条</p><p v-if="step.result.summary">合计：{{ amounts(step.result.summary.amountsByCurrency) }}</p><el-table :data="step.result.rows" size="mini" border><el-table-column v-for="column in columns(step.result.columns)" :key="column.name" :prop="column.name" :label="column.description || column.name" min-width="110"><template slot-scope="scope">{{ cell(scope.row[column.name]) }}</template></el-table-column></el-table><el-button size="small" class="row-button" @click="showRows(step.index)">查看完整明细</el-button><el-button v-if="step.action === 'QUERY' || step.action === 'SNAPSHOT'" type="text" :disabled="!!pending" @click="openSnapshot(step.result.snapshotId)">打开查询快照</el-button></template>
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
/** 有限分析与引用工作区；刷新只恢复原运行，历史回填须显式再提交，未知提交沿用请求号，快照验证与迟到响应独立保护。 */
import KnowledgeResults from '../components/KnowledgeResults.vue'
import { analysisCatalog, analysisRuns, analysisRun, submitAnalysis, cancelAnalysis, analysisRows } from '../api/analysis'
import { queryPage } from '../api/queryWorkspace'
import { getCurrentUserId, getSessionToken } from '../auth'
import { createAnalysisPendingStore } from '../utils/analysisPending'

export default {
  name: 'AnalysisWorkspace',
  components: { KnowledgeResults },
  data() { return { ready: false, catalog: { companies: [], reports: [], admin: false }, form: { companyCode: '', reportId: '', question: '', mode: 'CURRENT_DATA' }, sourceId: null, sourceReady: true, history: [], run: null, error: '', loading: false, submitting: false, preparing: false, restoring: false, preparationMessage: '', pendingNotice: '', pendingStorageError: '', historyBusy: false, pending: null, rowsVisible: false, rowStep: null, rowPage: null, rowsBusy: false, rowsError: '' } },
  computed: {
    canSubmit() { return !this.restoring && !this.submitting && !this.preparing && !this.loading && (!!this.pending || (this.ready && this.modeValid && this.form.companyCode && this.form.reportId && this.form.question.trim() && (!this.sourceId || this.sourceReady))) },
    modeValid() { return ['KNOWLEDGE_ONLY', 'CURRENT_DATA', 'SNAPSHOT_ONLY'].includes(this.form.mode) && (this.form.mode !== 'KNOWLEDGE_ONLY' || !this.sourceId) && (this.form.mode !== 'SNAPSHOT_ONLY' || !!this.sourceId) },
    modeHint() { return ({ KNOWLEDGE_ONLY: '仅检索所选范围内已发布的业务资料，不查询业务明细。', SNAPSHOT_ONLY: '只使用选定查询结果，可结合知识资料解释；不会补查当前业务数据。', CURRENT_DATA: this.sourceId ? '可查询当前数据，并使用选定结果进行对比。' : '可在所选范围内查询、统计和比较当前业务数据。' })[this.form.mode] || '执行范围无效，请重新选择。' },
    formLocked() { return this.restoring || this.submitting || this.preparing || !!this.pending },
    submitLabel() { return this.pending ? '核对本次提交' : '开始分析' },
    isActive() { return this.run && ['QUEUED', 'RUNNING'].includes(this.run.status) },
    isTerminal() { return this.run && ['COMPLETED', 'CLARIFY', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(this.run.status) },
    prepareLabel() { return this.run?.status === 'CLARIFY' || this.run?.answer?.status === 'INSUFFICIENT' ? '补充问题' : '按此问题重新分析' },
    sources() { const result = {}; for (const step of this.run?.steps || []) for (const source of step.sources || []) result[source.id] = source; return result }
  },
  watch: { '$route.query.run'(id) { if (id && id !== this.run?.id) this.openRun(id, false) } },
  created() { this._alive = true; this._seq = 0; this._detailSeq = 0; this._historySeq = 0; this._prepareSeq = 0; this.initialize() },
  beforeDestroy() { this._alive = false; this._seq++; this._detailSeq++; this._historySeq++; this._prepareSeq++; clearTimeout(this._timer) },
  methods: {
    pendingStore() { if (!this._pendingStore) this._pendingStore = createAnalysisPendingStore({ getIdentity: () => ({ userId: getCurrentUserId(), token: getSessionToken() }) }); return this._pendingStore },
    /** 会话改变时撤销所有旧页面响应，清除显示内容；新页面或显式恢复操作重新建立 owner 绑定。 */
    identityChanged(message) { this._seq++; this._historySeq++; this.invalidatePreparation(); this.closeRows(); clearTimeout(this._timer); this.pending = null; this.form = { companyCode: '', reportId: '', question: '', mode: 'CURRENT_DATA' }; this.catalog = { companies: [], reports: [], admin: false }; this.sourceId = null; this.sourceReady = false; this.run = null; this.history = []; this.historyBusy = false; this.ready = false; this.restoring = false; this.submitting = false; this.loading = false; this._pendingStore = null; this.error = message; this.pendingStorageError = message },
    /** 先核对会话、当前目录与显式快照再显示保存的问题；恢复本身只读取，不提交任务。 */
    async initialize() {
      if (!this._alive || this.submitting) return
      // 恢复入口重新接管页面；旧读取、回填和明细统一撤销，并释放各自状态，避免迟到响应覆盖恢复表单或遗留加载遮罩。
      const seq = ++this._seq; this._historySeq++; this.invalidatePreparation(); this.closeRows(); clearTimeout(this._timer)
      this.loading = false; this.historyBusy = false; this.restoring = true; this.ready = false; this.error = ''; this.pendingNotice = ''; this.pendingStorageError = ''
      let loadRuns = false
      try {
        const store = this.pendingStore(), [catalog, saved] = await Promise.all([analysisCatalog(), store.read()])
        await store.checkSession(); if (!this.valid(seq)) return
        this.catalog = catalog
        const request = saved.pending?.request, source = request ? request.snapshotId : this.$route.query.source || null
        const nextForm = request ? { companyCode: request.companyCode, reportId: request.reportId, question: request.question, mode: request.mode } : { companyCode: catalog.companies[0] || '', reportId: catalog.reports[0]?.reportId || '', question: this.form.question, mode: this.$route.query.mode || (source ? 'SNAPSHOT_ONLY' : 'CURRENT_DATA') }
        let sourceReady = !source, validationError = ''
        if (!['KNOWLEDGE_ONLY', 'CURRENT_DATA', 'SNAPSHOT_ONLY'].includes(nextForm.mode) || (nextForm.mode === 'KNOWLEDGE_ONLY' && source) || (nextForm.mode === 'SNAPSHOT_ONLY' && !source)) validationError = '执行范围与所选数据不一致，请重新选择；原请求不会自动改成当前数据。'
        if (request && !this.hasScope(request.companyCode, request.reportId)) validationError = '待核对请求的原范围已不在当前目录中，请核对本次提交取得服务器结果。'
        else if (source && !validationError) {
          try {
            const snapshot = await queryPage(source, 1)
            if (!this.valid(seq)) return
            if (snapshot.query.reportIds.length !== 1 || !snapshot.query.companyCode || (request && (snapshot.query.companyCode !== request.companyCode || snapshot.query.reportIds[0] !== request.reportId))) throw new Error('原快照范围未通过核对，请核对本次提交或重新选择数据。')
            nextForm.companyCode = snapshot.query.companyCode; nextForm.reportId = snapshot.query.reportIds[0]; sourceReady = true
          } catch (e) { validationError = e.message || '查询快照未通过核对。' }
        }
        await store.checkSession(); if (!this.valid(seq)) return
        this.pending = saved.pending; this.form = nextForm; this.sourceId = source; this.sourceReady = sourceReady
        this.ready = !validationError; this.error = validationError
        this.pendingNotice = saved.notice || (saved.pending ? '已恢复当前登录会话的待核对请求。尚未重新提交，请点击“核对本次提交”后再继续。' : '')
        if (saved.pending) await this.setRoute(this.$route.query.run)
        loadRuns = !validationError || !!saved.pending
      } catch (e) {
        if (this.valid(seq)) {
          if (e.pendingFailure === 'SESSION_CHANGED') this.identityChanged(e.message)
          else { this.error = e.message || '无法加载分析范围'; if (e.pendingFailure) this.pendingStorageError = e.message }
        }
      } finally { if (this.valid(seq)) this.restoring = false }
      if (loadRuns && this.valid(seq)) { await this.loadHistory(); if (this.valid(seq) && this.$route.query.run) await this.openRun(this.$route.query.run, false) }
    },
    valid(seq) { return this._alive && seq === this._seq },
    async loadHistory() { const seq = ++this._historySeq; this.historyBusy = true; try { const rows = await analysisRuns(); if (this._alive && seq === this._historySeq) this.history = rows } catch (e) { if (this._alive && seq === this._historySeq) this.error = e.message } finally { if (this._alive && seq === this._historySeq) this.historyBusy = false } },
    hasScope(company, report) { return this.catalog.companies.includes(company) && this.catalog.reports.some(item => item.reportId === report) },
    /** 未核对提交始终以保存的请求为准；即使迟到的输入事件到达，也不能覆盖载荷或丢失幂等请求号。 */
    restorePendingRequest() { if (!this.pending) return false; const request = this.pending.request; this.form = { companyCode: request.companyCode, reportId: request.reportId, question: request.question, mode: request.mode }; this.sourceId = request.snapshotId; return true },
    scopeChanged() { if (this.restorePendingRequest()) return; this.ready = this.hasScope(this.form.companyCode, this.form.reportId) },
    questionChanged() { this.restorePendingRequest() },
    /** 只有明确选择执行范围时才解除快照；待核对请求保持原模式和原来源，不因其他输入事件变化。 */
    modeChanged() { if (this.restorePendingRequest()) return; if (this.form.mode === 'KNOWLEDGE_ONLY') { this.sourceId = null; this.sourceReady = true }; this.ready = this.hasScope(this.form.companyCode, this.form.reportId); this.setRoute(this.$route.query.run) },
    clearSource() { if (this.restorePendingRequest() || this.submitting || this.preparing) return; this.form.mode = 'CURRENT_DATA'; this.sourceId = null; this.sourceReady = true; this.ready = this.hasScope(this.form.companyCode, this.form.reportId); this.setRoute(this.$route.query.run) },
    invalidatePreparation() { this._prepareSeq++; this.preparing = false; this.preparationMessage = '' },
    /** 回填终态任务只准备新表单；显式快照先重新核对范围，失败时须由用户决定是否改用当前数据。 */
    async prepareRun() {
      if (this.restorePendingRequest()) return
      if (!this.isTerminal || this.restoring || this.submitting || this.loading || this.preparing) return
      const id = this.run.id, request = { ...this.run.request }, seq = ++this._prepareSeq
      const current = () => this._alive && seq === this._prepareSeq && this.run?.id === id
      const fill = sourceReady => { this.form = { companyCode: request.companyCode, reportId: request.reportId, question: request.question, mode: request.mode }; this.sourceId = request.snapshotId || null; this.sourceReady = sourceReady; this.ready = this.hasScope(request.companyCode, request.reportId) }
      this.preparing = true; this.preparationMessage = ''; this.error = ''; this.closeRows()
      try {
        if (!['KNOWLEDGE_ONLY', 'CURRENT_DATA', 'SNAPSHOT_ONLY'].includes(request.mode)) throw new Error('原任务执行范围无效，请重新填写问题。')
        if (!this.hasScope(request.companyCode, request.reportId)) throw new Error('原任务范围已不在当前可选目录中，请重新选择公司和报表。')
        if (request.snapshotId) {
          const snapshot = await queryPage(request.snapshotId, 1)
          if (!current()) return
          if (snapshot.query.companyCode !== request.companyCode || snapshot.query.reportIds.length !== 1 || snapshot.query.reportIds[0] !== request.reportId) throw new Error('原快照范围与任务不一致，请核对后重新选择数据。')
        }
        if (!current()) return
        fill(true); await this.setRoute(id)
        if (!current()) return
        this.preparationMessage = '已填入原问题与范围。请补充或核对后点击“开始分析”创建新任务，原任务会保留。'
        this.$nextTick(() => { if (current()) { this.$refs.requestCard?.$el?.scrollIntoView({ behavior: 'smooth', block: 'start' }); this.$refs.questionInput?.focus() } })
      } catch (e) { if (current()) { fill(!request.snapshotId); this.error = e.message || '原任务信息未通过核对，请重新选择分析范围。'; await this.setRoute(id) } }
      // 加载状态归本次准备操作所有；任务被清除也必须释放，任务内容的回写仍由 current() 严格约束。
      finally { if (this._alive && seq === this._prepareSeq) this.preparing = false }
    },
    async submit() {
      if (!this.canSubmit) return
      const seq = ++this._seq, store = this.pendingStore(); let pending = this.pending, sent = false
      this.submitting = true; this.error = ''; this.preparationMessage = ''; this.pendingStorageError = ''; clearTimeout(this._timer); this.closeRows()
      try {
        // 发出请求前完成会话级保存；页面销毁或刷新也能找回同一 key，不依赖网络响应抵达。
        if (!pending) pending = await store.create({ ...this.form, question: this.form.question.trim(), snapshotId: this.sourceId }, 'analysis-' + crypto.randomUUID())
        if (!this.valid(seq)) return
        this.pending = pending; this.restorePendingRequest()
        await store.verify(pending); if (!this.valid(seq)) return
        sent = true
        const run = await submitAnalysis(pending.request, pending.key)
        await store.checkSession(); await store.clear(pending)
        if (!this.valid(seq)) return
        this.pending = null; this.pendingNotice = ''; this.run = run; await this.setRoute(run.id)
        if (this.valid(seq)) { this.schedule(); this.loadHistory() }
      } catch (error) {
        let e = error
        if (sent && !e.pendingFailure) { try { await store.checkSession() } catch (changed) { e = changed } }
        // 清理在页面存活检查之前执行：离页后收到明确结论，也应清除属于原会话、原 key 的保存记录。
        if (sent && e.serverResponse === true && Number.isInteger(e.code) && e.code >= 400 && e.code < 500) {
          try {
            await store.clear(pending)
            if (this.valid(seq)) { this.pending = null; this.pendingNotice = ''; this.error = (e.message || '请求被拒绝') + '。服务器已拒绝本次提交，请核对后修改。' }
            return
          } catch (storageError) { e = storageError }
        }
        if (!this.valid(seq)) return
        if (e.pendingFailure === 'SESSION_CHANGED') { this.identityChanged(e.message); return }
        if (e.pendingFailure) {
          if (e.pendingFailure !== 'UNAVAILABLE') this.pending = null
          this.pendingStorageError = e.message
          this.error = sent ? '服务器结果或本地记录尚未完成核对，请恢复会话存储后继续核对原请求。' : '本次尚未发送请求，请先处理待核对记录。'
        } else this.error = (e.message || '提交结果尚未确认') + '。请点击“核对本次提交”，沿用原请求号核对。'
      } finally { if (this.valid(seq)) this.submitting = false }
    },
    async setRoute(id) { if (this.$route.query.run !== id || (this.$route.query.source || null) !== this.sourceId || this.$route.query.mode !== this.form.mode) await this.$router.replace({ path: '/analysis', query: { mode: this.form.mode, ...(this.sourceId ? { source: this.sourceId } : {}), ...(id ? { run: id } : {}) } }).catch(() => {}) },
    async openRun(id, route = true) { if (this.submitting || this.restoring) return; const seq = ++this._seq; this.invalidatePreparation(); this.loading = true; this.error = ''; this.run = null; this.closeRows(); clearTimeout(this._timer);
      try { const run = await analysisRun(id); if (!this.valid(seq)) return; this.run = run; if (route) await this.setRoute(id); if (this.valid(seq)) this.schedule() } catch (e) { if (this.valid(seq)) this.error = e.message || '任务无法恢复' } finally { if (this.valid(seq)) this.loading = false } },
    schedule() { clearTimeout(this._timer); if (this._alive && this.isActive) this._timer = setTimeout(() => this.poll(), 1200) },
    async poll() { if (!this._alive || !this.isActive) return; const id = this.run.id; const seq = this._seq;
      try { const run = await analysisRun(id); if (!this.valid(seq) || this.run?.id !== id || !this.isActive || run.version < this.run.version) return; this.run = run; this.error = ''; if (this.isActive) this.schedule(); else this.loadHistory() }
      catch (e) { if (this.valid(seq) && this.run?.id === id && this.isActive) { this.invalidatePreparation(); this.run = null; this.closeRows(); this.error = e.message || '无法读取任务，请从最近任务重新打开' } } },
    /** 取消确认推进页面代次，使此前轮询的成功或失败都不能覆盖已确认终态及随后开始的表单准备。 */
    async cancel() { if (!this.isActive || this.formLocked) return; const id = this.run.id; const seq = this._seq; try { const result = await cancelAnalysis(id); if (!this.valid(seq) || this.run?.id !== id) return; this._seq++; this.run = result; clearTimeout(this._timer); this.loadHistory() } catch (e) { if (this.valid(seq)) this.error = e.message } },
    showRows(step) { this.rowsVisible = true; this.rowStep = step; this.rowPage = null; this.loadRows(1) },
    closeRows() { this._detailSeq++; this.rowsVisible = false; this.rowStep = null; this.rowPage = null; this.rowsError = ''; this.rowsBusy = false },
    async loadRows(page) { if (!this.run || this.rowStep === null) return; const id = this.run.id, step = this.rowStep, seq = ++this._detailSeq; this.rowsBusy = true; this.rowsError = ''; this.rowPage = null;
      try { const result = await analysisRows(id, step, page); if (this._alive && seq === this._detailSeq && this.rowsVisible && this.run?.id === id && this.rowStep === step) this.rowPage = result }
      catch (e) { if (this._alive && seq === this._detailSeq && this.rowsVisible) this.rowsError = e.message } finally { if (this._alive && seq === this._detailSeq) this.rowsBusy = false } },
    openSnapshot(id) { if (this.restorePendingRequest()) return; this.$router.push({ path: '/query-workspace', query: { run: id, page: '1' } }) },
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
    modeText(value) { return ({ KNOWLEDGE_ONLY: '业务知识问答', CURRENT_DATA: '当前数据分析', SNAPSHOT_ONLY: '仅分析选定结果' })[value] || '执行范围无效' },
    stepText(value) { if (value === 'RUNNING' && !this.isActive) return '未完成'; return ({ RUNNING: '执行中', COMPLETED: '已完成', FAILED: '未完成' })[value] || value },
    statusText(value) { return ({ QUEUED: '等待执行', RUNNING: '分析中', COMPLETED: '已完成', CLARIFY: '需要补充说明', PARTIAL: '部分完成', FAILED: '未完成', CANCELLED: '已取消', INTERRUPTED: '已中断' })[value] || value },
    stageText(value) { return ({ QUEUED: '排队中', PLAN: '整理分析步骤', PLAN_REVIEW: '核对分析范围和要求', STEP: '读取与计算', RAG_REWRITE: '整理知识检索表达', RAG_RERANK: '核对知识片段相关性', ANSWER: '整理有依据的回答', ANSWER_REVIEW: '核对回答依据', FINISHED: '' })[value] ?? value },
    answerText(value) { return ({ ANSWERED: '有依据的回答', INSUFFICIENT: '部分问题缺少充分依据', CONFLICTING: '资料存在冲突，需业务确认' })[value] || value }
  }
}
</script>

<style scoped>
.intro,.between,.scope,.actions { display:flex; align-items:center; gap:16px; }.intro,.between { justify-content:space-between; }.intro h2 { margin:0; }.intro p,.muted,.actions span { color:#6b7c91; font-size:13px; }.notice,.request-card { margin-bottom:18px; }.scope { flex-wrap:wrap; margin-bottom:16px; }.scope label { display:flex; align-items:center; gap:8px; font-size:13px; }.actions { margin-top:14px; }.layout { display:grid; grid-template-columns:250px minmax(0,1fr); gap:18px; }.history { align-self:start; }.history-item { background:white; border:0; border-bottom:1px solid #edf0f5; padding:13px 8px; width:100%; text-align:left; cursor:pointer; color:#34475f; }.history-item span { display:block; line-height:1.6; }.history-item small { display:block; color:#8290a3; margin-top:5px; }.history-item.selected { background:#edf5ff; }.results > .el-card { margin-bottom:16px; }.stage { font-size:13px; color:#6b7c91; margin-left:12px; }details { margin:12px 0; }summary { cursor:pointer; color:#3479c7; font-size:13px; }pre,blockquote { white-space:pre-wrap; overflow-wrap:anywhere; background:#f6f8fc; padding:14px; border-radius:5px; font-size:13px; line-height:1.7; }blockquote { margin:10px 0; border-left:3px solid #77a8de; }.claim { border-bottom:1px solid #edf0f5; padding-bottom:12px; }.claim p { line-height:1.8; }.row-button { margin-top:12px; }.el-pagination { margin-top:14px; }.usage { padding:0 12px; }@media(max-width:1100px){.layout { grid-template-columns:1fr; }.history { max-height:260px; overflow:auto; }.intro { align-items:flex-start; }}
</style>
