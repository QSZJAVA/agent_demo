<template>
  <el-drawer title="异常调查" :visible="true" size="min(860px, 95vw)" :before-close="close" append-to-body>
    <div class="investigation-panel">
      <el-alert title="调查只查询事实并提供建议；报告覆盖本次选择的条目。" type="info" :closable="false" />
      <div v-if="error" class="error">{{ error }} <el-button size="mini" @click="resume">重新读取</el-button></div>
      <div class="scope">清单：{{ planId }} · 每次最多 10 条异常记录</div>
      <el-table ref="candidateTable" :data="candidates" size="mini" border max-height="220" row-key="id" @selection-change="selectItems">
        <el-table-column type="selection" width="45" :reserve-selection="true" :selectable="selectable" />
        <el-table-column prop="doc_no" label="单据" min-width="140" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="error_message" label="异常摘要" min-width="200" show-overflow-tooltip />
      </el-table>
      <el-button v-if="candidateCursor" size="mini" :loading="loadingCandidates" @click="loadCandidates">更多异常条目</el-button>
      <p>已选择 {{ selected.length }} 条；未选择时由服务端检查全部异常，超过 10 条须明确选择。</p>
      <el-input v-model="question" type="textarea" :rows="2" maxlength="1000" show-word-limit :disabled="active || submissionPending" />
      <div class="actions">
        <el-button type="primary" size="small" :loading="submitting" :disabled="active || !question.trim() || loading" @click="submit">{{ submissionPending ? '恢复提交' : run ? '重新分析' : '开始分析' }}</el-button>
        <el-button v-if="active" size="small" type="warning" @click="cancel">取消调查</el-button>
        <el-button v-if="run" size="small" @click="resume">刷新结果</el-button>
      </div>
      <template v-if="run">
        <el-tag>{{ statusLabel }}</el-tag> <span>{{ run.message }}</span>
        <p>本次范围：{{ (run.itemRefs || []).map(item => item.docNo || item.itemId).join('、') }}</p>
        <el-alert v-if="run.status === 'PARTIAL' || run.sourceChangedSinceRun" :title="run.sourceChangedSinceRun ? '来源已变化，以下报告描述调查时点；可重新分析当前状态。' : '调查部分完成，请关注证据不足和结束原因。'" type="warning" :closable="false" />
        <div v-if="run.activeStep" class="progress">正在处理：{{ run.activeStep.tool_name || '模型分析' }}</div>
        <el-collapse>
          <el-collapse-item title="查看查询步骤" name="steps">
            <div v-for="step in steps" :key="step.seq" class="step">{{ step.seq }} · {{ step.tool_name || step.kind }} · {{ step.status }} · {{ step.duration_ms }} ms <span v-if="step.error_code">{{ step.error_code }}</span></div>
          </el-collapse-item>
        </el-collapse>
        <template v-if="run.report">
          <p>{{ run.report.summary }}</p>
          <!-- 两组循环展开为同级节点，必须使用不同键前缀，避免报告更新破坏证据弹窗。 -->
          <div v-for="finding in run.report.findings" :key="'finding-' + finding.itemRef" class="finding">
            <strong>{{ itemLabel(finding.itemRef) }}</strong> · {{ certaintyLabel(finding.certainty) }} · {{ reasonLabel(finding.reasonCode) }}
            <p>{{ finding.explanation }}</p>
            <div><el-button v-for="ref in finding.evidenceIds" :key="ref" size="mini" type="text" @click="showEvidence(ref)">证据 {{ ref }}</el-button></div>
            <p>建议：{{ nextLabel(finding.nextStep) }}</p>
          </div>
          <div v-for="item in run.report.unresolved" :key="'unresolved-' + item.itemRef" class="error">待查：{{ itemLabel(item.itemRef) }} · {{ item.message }}</div>
        </template>
        <p v-if="run.usage">模型 {{ run.usage.modelCalls }} 次 · 工具 {{ run.usage.toolCalls }} 次 · MCP {{ run.usage.mcpCalls }} 次 · 用量{{ run.usage.usageComplete ? '已记录' : '未完整提供' }}</p>
      </template>
      <el-dialog title="调查证据" :visible.sync="evidenceVisible" append-to-body width="min(760px, 90vw)">
        <pre class="evidence">{{ evidenceText }}</pre>
      </el-dialog>
    </div>
  </el-drawer>
</template>

<script>
/**
 * 指定清单的异常调查抽屉；提交、步骤、报告与证据绑定账号、清单和请求轮次，关闭后迟到响应不能更新页面。
 * 页面只恢复已有任务，重新分析由用户点击；报告说明按文本转义显示，不生成执行按钮或任意链接。
 */
import { submitInvestigation, recoverInvestigation, fetchInvestigation, fetchInvestigationSteps, fetchInvestigationEvidence, cancelInvestigation, fetchInvestigationCandidates, markInvestigationTerminal, hasPendingInvestigation } from '../../api/investigation'
import { getCurrentUserId, getSessionToken } from '../../auth'

export default {
  name: 'InvestigationPanel',
  props: { planId: { type: String, required: true } },
  data() {
    return { question: '请分析这些条目的异常原因，列出依据和下一步建议。', selected: [], candidates: [], candidateCursor: '0', loadingCandidates: false,
      run: null, steps: [], afterSeq: 0, loading: false, submitting: false, submissionPending: false, error: '', disposed: false, generation: 0, timer: null,
      evidenceVisible: false, evidenceText: '', evidenceRequest: 0, identity: '', sessionToken: '', pollingStartedAt: 0, selectionRestoring: false }
  },
  computed: {
    active() { return this.run && ['QUEUED', 'RUNNING'].includes(this.run.status) },
    statusLabel() { return { QUEUED: '排队', RUNNING: '调查中', COMPLETED: '调查完成', PARTIAL: '部分完成', FAILED: '调查失败', CANCELLED: '已取消', INTERRUPTED: '已中断' }[this.run?.status] || '' }
  },
  mounted() { this.identity = getCurrentUserId(); this.sessionToken = getSessionToken(); this.resume(); this.loadCandidates() },
  beforeDestroy() { this.dispose() },
  watch: { planId() { this.dispose(); this.$emit('close') } },
  methods: {
    currentScope() { return !this.disposed && this.identity === getCurrentUserId() && this.sessionToken === getSessionToken() },
    current(generation) { return this.currentScope() && generation === this.generation },
    dispose() { this.disposed = true; this.generation++; this.evidenceRequest++; clearTimeout(this.timer) },
    close(done) { this.dispose(); this.$emit('close'); if (done) done() },
    selectable(row) { return !this.active && !this.loading && !this.submitting && !this.submissionPending && (this.selected.includes(row.id) || this.selected.length < 10) },
    selectItems(rows) {
      if (this.active || this.submitting || this.submissionPending || this.selectionRestoring) return
      if (rows.length <= 10) { this.selected = rows.map(row => row.id); return }
      // 表头全选可能越过逐行 selectable 限制；拒绝整批并恢复原选择，不能静默截取前十条。
      this.error = '每次最多选择 10 条，请明确选择调查范围。'
      this.selectionRestoring = true
      this.$nextTick(() => {
        try {
          if (!this.currentScope()) return
          const table = this.$refs.candidateTable
          table.clearSelection()
          this.candidates.filter(row => this.selected.includes(row.id)).forEach(row => table.toggleRowSelection(row, true))
        } finally { this.selectionRestoring = false }
      })
    },
    async loadCandidates() {
      if (this.loadingCandidates || !this.candidateCursor) return
      this.loadingCandidates = true
      try {
        const page = await fetchInvestigationCandidates(this.planId, this.candidateCursor)
        // 候选读取只绑定账号和清单，不因同范围内提交/刷新调查而丢弃并留下永久加载状态。
        if (!this.currentScope()) return
        const known = new Set(this.candidates.map(row => row.id)); this.candidates.push(...page.records.filter(row => !known.has(row.id))); this.candidateCursor = page.nextCursor
      } catch (e) { if (this.currentScope()) this.error = e.response?.data?.message || e.message }
      finally { if (this.currentScope()) this.loadingCandidates = false }
    },
    async submit() {
      if (this.submitting || this.active) return
      if (this.submissionPending) return this.resume()
      const generation = ++this.generation; clearTimeout(this.timer); this.submitting = true; this.error = ''
      try {
        const fresh = Boolean(this.run) && !this.submissionPending
        this.submissionPending = true
        const run = await submitInvestigation({ planId: this.planId, itemIds: this.selected.length ? this.selected : null, question: this.question }, fresh)
        if (!this.current(generation)) return
        this.submissionPending = false; this.run = run; this.steps = []; this.afterSeq = 0; this.pollingStartedAt = Date.now()
        if (!run) this.error = '当前范围没有可调查异常'
        else await this.refresh(generation)
      } catch (e) { if (this.current(generation)) { this.submissionPending = hasPendingInvestigation(this.planId); this.error = e.response?.data?.message || e.message } }
      finally { if (this.current(generation)) this.submitting = false }
    },
    async resume() {
      const generation = ++this.generation; clearTimeout(this.timer); this.submitting = false; this.loading = true; this.error = ''
      try {
        const run = await recoverInvestigation(this.planId)
        if (!this.current(generation)) return
        if (this.run?.id !== run?.id) { this.steps = []; this.afterSeq = 0 }
        this.submissionPending = false
        this.run = run; this.pollingStartedAt = Date.now()
        if (run) await this.refresh(generation)
      } catch (e) { if (this.current(generation)) { this.submissionPending = hasPendingInvestigation(this.planId); this.error = e.response?.data?.message || e.message } }
      finally { if (this.current(generation)) this.loading = false }
    },
    async refresh(generation) {
      if (!this.current(generation) || !this.run) return
      try {
        const runId = this.run.id
        const [run, page] = await Promise.all([fetchInvestigation(runId), fetchInvestigationSteps(runId, this.afterSeq)])
        if (!this.current(generation) || this.run.id !== runId) return
        this.run = run
        this.mergeSteps(page)
        if (['QUEUED', 'RUNNING'].includes(run.status)) {
          // 页面等待有上限，停止轮询不取消服务端任务；用户刷新结果继续只读恢复。
          if (Date.now() - this.pollingStartedAt >= 300000) this.error = '页面等待已达5分钟，请刷新结果读取已有任务'
          else this.timer = setTimeout(() => this.refresh(generation), this.steps.length ? 3000 : 1000)
        }
        else {
          // 并行步骤页可能早于终态事务；观察终态后重新读取，并补齐全部分页再停止。
          // 终态和STARTED步骤的收尾在服务端同一事务提交，此后的读取不会再漏最后一步。
          let finalPage
          do {
            finalPage = await fetchInvestigationSteps(runId, this.afterSeq)
            if (!this.current(generation) || this.run.id !== runId) return
            this.mergeSteps(finalPage)
          } while (finalPage.records.length)
          markInvestigationTerminal(this.planId, run.id)
        }
      } catch (e) { if (this.current(generation)) this.error = e.response?.data?.message || e.message }
    },
    /** 合并连续的已完成步骤，重读不能重复展示或把游标退回旧页。 */
    mergeSteps(page) {
      const known = new Set(this.steps.map(step => step.seq))
      this.steps.push(...page.records.filter(step => !known.has(step.seq)))
      this.afterSeq = Math.max(this.afterSeq, Number(page.nextCursor))
    },
    async cancel() {
      if (!this.run) return
      const generation = ++this.generation; clearTimeout(this.timer)
      try { const run = await cancelInvestigation(this.run.id); if (this.current(generation)) { this.run = run; await this.refresh(generation) } }
      catch (e) { if (this.current(generation)) this.error = e.response?.data?.message || e.message }
    },
    async showEvidence(ref) {
      const generation = this.generation, request = ++this.evidenceRequest, id = this.run.id
      try {
        const value = await fetchInvestigationEvidence(id, ref)
        if (!this.current(generation) || request !== this.evidenceRequest || id !== this.run.id) return
        this.evidenceText = JSON.stringify(value, null, 2); this.evidenceVisible = true
      } catch (e) { if (this.current(generation)) this.error = e.response?.data?.message || e.message }
    },
    itemLabel(ref) { const item = (this.run.itemRefs || []).find(value => value.itemRef === ref); return item?.docNo || item?.itemId || ref },
    certaintyLabel(value) { return { VERIFIED: '事实已核实', HYPOTHESIS: '待验证推测', INSUFFICIENT: '证据不足' }[value] || value },
    reasonLabel(value) { return { BUSINESS_REJECTED: '业务明确失败', PRECHECK_SKIPPED: '复核未通过', RESULT_UNKNOWN: '结果未知', REMOTE_SUCCESS_LOCAL_UNRESOLVED: '远端成功、本地待核对', REQUEST_NOT_FOUND: '本次未查到请求', EVIDENCE_MISSING: '缺少证据', UNDETERMINED: '尚无法判断' }[value] || value },
    nextLabel(value) { return { VIEW_EVIDENCE: '查看执行证据', USE_EXISTING_RECONCILE: '在原清单入口核对结果', RECHECK_CURRENT_RULES: '核查当前规则', MANUAL_REVIEW: '人工核查', NONE: '无进一步建议' }[value] || value }
  }
}
</script>

<style scoped>
.investigation-panel { padding: 0 20px 24px; overflow: auto; height: calc(100vh - 90px); }
.scope, .actions, .progress { margin: 14px 0; }
.error { color: #b04435; margin: 12px 0; }
.finding { border: 1px solid #dcdfe6; border-radius: 4px; padding: 12px; margin: 12px 0; }
.step { padding: 6px 0; border-bottom: 1px solid #ebeef5; }
.evidence { white-space: pre-wrap; word-break: break-all; max-height: 60vh; overflow: auto; }
</style>
