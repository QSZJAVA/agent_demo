<template>
  <section class="analysis-task-card" :aria-busy="loading">
    <div class="task-title"><b>{{ run ? modeText(run.request.mode) : '分析与问答任务' }}</b><el-tag v-if="run" size="small" :type="run.status === 'COMPLETED' ? 'success' : 'info'">{{ statusText(run.status) }}</el-tag></div>
    <p v-if="error" class="unavailable" role="status">{{ error }}</p>
    <p v-else-if="loading && !run" class="muted">正在核对任务及访问范围…</p>
    <template v-if="run">
      <p>{{ run.request.question }}</p>
      <p class="muted">{{ run.request.companyCode }} 公司 · {{ run.request.reportId }}<span v-if="stageText(run.stage)"> · {{ stageText(run.stage) }}</span></p>
      <p v-if="run.message" role="status">{{ run.message }}</p>
      <div v-if="run.answer" class="answer">
        <b>{{ answerText(run.answer.status) }}</b>
        <div v-for="(claim, index) in run.answer.claims" :key="index" class="claim"><p>{{ claim.text }}</p><details v-for="(citation, j) in claim.citations" :key="j"><summary>依据：{{ sourceTitle(citation.sourceId) }}</summary><p v-if="sources[citation.sourceId]" class="muted">{{ sources[citation.sourceId].scope }} · 版本 {{ sources[citation.sourceId].version }} · {{ sources[citation.sourceId].observedAt }}</p><blockquote>{{ citation.quote }}</blockquote></details></div>
        <ul v-if="run.answer.limitations.length"><li v-for="(item, index) in run.answer.limitations" :key="index">{{ item }}</li></ul>
      </div>
      <el-button size="small" @click="open">查看步骤和完整依据</el-button>
      <el-button v-if="isActive" size="small" :loading="cancelling" @click="cancel">取消任务</el-button>
      <el-button v-else type="text" size="small" :disabled="loading" @click="reload">重新核对状态</el-button>
    </template>
    <el-button v-else-if="active && cardState !== 'UNAVAILABLE'" type="text" size="small" :disabled="loading" @click="reload">重新读取任务</el-button>
  </section>
</template>

<script>
/** 聊天中的分析引用卡片；答案只取当前授权GET，展示最多有效30秒，隐藏、身份变化或读失败即撤销旧内容。 */
import { analysisRun, cancelAnalysis } from '../../api/analysis'
import { getCurrentUserId, getSessionToken } from '../../auth'
import { analysisTaskReference } from '../../utils/chatReceipt'

const DISPLAY_VALID_MS = 30000
/** 浏览器使用单调时钟计算核验期限，系统时间校准不能延长展示窗口；无此API的执行环境使用本地时钟。 */
function displayNow() { return typeof performance === 'undefined' ? Date.now() : performance.now() }

export default {
  name: 'AnalysisTaskCard',
  props: { payload: { type: Object, required: true }, cardState: String, active: { type: Boolean, default: true } },
  data() { return { run: null, error: '', loading: false, cancelling: false } },
  computed: {
    isActive() { return this.run && ['QUEUED', 'RUNNING'].includes(this.run.status) },
    sources() { const values = {}; for (const step of this.run?.steps || []) for (const source of step.sources || []) values[source.id] = source; return values }
  },
  watch: {
    active() { this.reload() },
    payload: { deep: true, handler() { this.reload() } },
    cardState() { this.reload() }
  },
  created() {
    this._alive = true; this._seq = 0; this._readSeq = 0; this._identity = { userId: getCurrentUserId(), token: getSessionToken() }
    // 浏览器隐藏时主动撤下内容，避免后台计时器节流延长答案展示；重新可见只重新GET原任务。
    if (typeof document !== 'undefined') { this._visibilityListener = () => this.reload(); document.addEventListener('visibilitychange', this._visibilityListener) }
    this.reload()
  },
  beforeDestroy() {
    this._alive = false; this._seq++; this.clearTimers(); this.run = null; this.loading = false; this.cancelling = false
    if (typeof document !== 'undefined' && this._visibilityListener) document.removeEventListener('visibilitychange', this._visibilityListener)
  },
  methods: {
    /** 校验显示代次和登录身份；身份一旦变化即废弃本实例，旧响应不能因后续令牌切换而恢复。 */
    current(seq) {
      const sameIdentity = !this._identityInvalid && this._identity.userId === getCurrentUserId() && this._identity.token === getSessionToken()
      if (this._alive && !sameIdentity) { this._identityInvalid = true; this.run = null; this.loading = false; this.cancelling = false; this.error = '登录会话已变化，原任务内容已隐藏。'; this.clearTimers() }
      return this._alive && this.active && (typeof document === 'undefined' || !document.hidden) && seq === this._seq && sameIdentity
    },
    /** 同一显示代次只接纳最后发起的GET，旧成功和旧失败都不能覆盖较新的权限核验。 */
    currentRead(seq, readSeq) { return this.current(seq) && readSeq === this._readSeq },
    clearTimers() { clearTimeout(this._timer); clearTimeout(this._expiryTimer); this._timer = null; this._expiryTimer = null },
    /** 卡片只接收稳定引用，GET结果还须与mode和范围一致；损坏或中性不可用卡片不请求其他任务。 */
    validReference() { if (this.cardState === 'UNAVAILABLE') return false; try { analysisTaskReference(this.payload); return true } catch (_) { return false } },
    /** 撤销旧展示与在途读取后核对同一引用；保留版本高水位，使过期、隐藏或重试都不能复活较旧任务。 */
    reload() {
      this._seq++; this.clearTimers(); this.run = null; this.error = ''; this.loading = false; this.cancelling = false; this._visibleUntil = 0
      if (!this.current(this._seq)) return
      if (!this.validReference()) { this.error = '该任务当前不可查看，请从分析与问答页面核对。'; return }
      const scopeKey = JSON.stringify(analysisTaskReference(this.payload))
      if (scopeKey !== this._scopeKey) { this._scopeKey = scopeKey; this._versionFloor = -1 }
      return this.read(this._seq)
    },
    /** 运行态按读取完成后1200毫秒再轮询；独立的展示期限负责处理GET挂起，终态也必须到期重新核权。 */
    schedulePoll(seq) {
      clearTimeout(this._timer); this._timer = null
      if (this.current(seq) && this.isActive) this._timer = setTimeout(() => { this._timer = null; return this.read(seq) }, 1200)
    },
    /** 每次结果都重新授权；展示期限从请求发出时起算，网络迟到不能延长旧授权或知识版本的可见窗口。 */
    async read(seq) {
      if (!this.current(seq) || !this.validReference()) return
      clearTimeout(this._timer); this._timer = null
      const reference = analysisTaskReference(this.payload), scopeKey = this._scopeKey, startedAt = displayNow()
      const readSeq = this._readSeq = (this._readSeq || 0) + 1
      const id = reference.runId; this.loading = true
      try {
        const value = await analysisRun(id)
        if (!this.currentRead(seq, readSeq) || JSON.stringify(analysisTaskReference(this.payload)) !== scopeKey) return
        if (value.id !== id || value.request?.mode !== reference.mode || value.request?.companyCode !== reference.companyCode || value.request?.reportId !== reference.reportId || !Number.isSafeInteger(value.version) || value.version < 0) throw new Error('任务引用与当前结果范围或版本不一致')
        if (displayNow() - startedAt >= DISPLAY_VALID_MS) throw new Error('任务读取超过本次核验有效期')
        if (value.version < this._versionFloor) {
          if (!this.run) this.error = '任务读取版本已过期，原答案已隐藏。请重新核对状态。'
          // 较旧响应不刷新展示期限；已有有效运行态仍可继续轮询，但过期后只能接受当前版本。
          this.schedulePoll(seq); return
        }
        this._versionFloor = value.version; this.run = value; this.error = ''; this._visibleUntil = startedAt + DISPLAY_VALID_MS
        const visibleUntil = this._visibleUntil
        clearTimeout(this._expiryTimer)
        this._expiryTimer = setTimeout(() => {
          if (!this.current(seq) || this._visibleUntil !== visibleUntil) return
          // 到期先撤下run，再推进显示代次并GET；此前挂起的轮询无权重新填回答案，也不创建或取消任务。
          return this.reload()
        }, Math.max(0, visibleUntil - displayNow()))
        this.schedulePoll(seq)
      } catch (_) {
        if (this.currentRead(seq, readSeq)) { this.run = null; this.error = '该任务当前无法读取，原答案已隐藏。请重新核对访问范围和资料状态。'; this.clearTimers(); this._visibleUntil = 0 }
      } finally { if (this.currentRead(seq, readSeq)) this.loading = false }
    },
    /** 取消仅针对已读取的当前任务；确认后推进读取代次，之前的轮询不能覆盖终态。 */
    async cancel() {
      if (!this.isActive || this.cancelling || !this.current(this._seq)) return
      const seq = this._seq, id = this.run.id; this.cancelling = true
      try {
        await cancelAnalysis(id)
        if (this.current(seq) && this.run?.id === id) await this.reload()
      } catch (_) { if (this.current(seq)) { this._seq++; this.run = null; this.cancelling = false; this.loading = false; this.error = '取消结果尚未核对，请重新读取任务。'; this.clearTimers() } }
      finally { if (this.current(seq)) this.cancelling = false }
    },
    open() { if (!this.current(this._seq) || !this.run) return; this.$emit('open', this.run.id) },
    sourceTitle(id) { return this.sources[id]?.title || '来源不可用' },
    modeText(value) { return ({ KNOWLEDGE_ONLY: '业务知识问答', CURRENT_DATA: '当前数据分析', SNAPSHOT_ONLY: '仅分析选定结果' })[value] || '分析与问答任务' },
    statusText(value) { return ({ QUEUED: '等待执行', RUNNING: '分析中', COMPLETED: '已完成', CLARIFY: '需要补充说明', PARTIAL: '部分完成', FAILED: '未完成', CANCELLED: '已取消', INTERRUPTED: '已中断' })[value] || value },
    stageText(value) { return ({ QUEUED: '排队中', PLAN: '整理分析步骤', PLAN_REVIEW: '核对分析范围', STEP: '读取与计算', RAG_REWRITE: '整理检索表达', RAG_RERANK: '核对相关性', ANSWER: '整理回答', ANSWER_REVIEW: '核对回答依据', FINISHED: '' })[value] || '' },
    answerText(value) { return ({ ANSWERED: '有依据的回答', INSUFFICIENT: '部分问题缺少充分依据', CONFLICTING: '资料存在冲突，需业务确认' })[value] || value }
  }
}
</script>

<style scoped>
.analysis-task-card { padding:18px; border:1px solid #d9e4ef; border-radius:8px; background:#fff; }.task-title { display:flex; align-items:center; gap:12px; }.muted,.unavailable { color:#6b7c91; font-size:13px; }.analysis-task-card p,.analysis-task-card li { line-height:1.8; overflow-wrap:anywhere; }.answer { margin:16px 0; }.claim { border-bottom:1px solid #edf0f5; padding-bottom:8px; }.claim details { margin:8px 0; }summary { cursor:pointer; color:#3479c7; }blockquote { white-space:pre-wrap; overflow-wrap:anywhere; margin:10px 0; padding:12px; border-left:3px solid #77a8de; background:#f6f8fc; }
</style>
