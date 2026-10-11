<template>
  <el-card shadow="never" class="tasks-page">
    <div slot="header" class="page-toolbar">
      <div><h1>我的任务</h1><p>找回已提交的派单、重试与核对任务，查看当前进度和清单结果</p></div>
      <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="refresh">刷新任务</el-button>
    </div>
    <div class="tasks-toolbar">
      <el-select v-model="status" size="small" aria-label="任务状态" @change="changeStatus">
        <el-option label="全部状态" value="" />
        <el-option label="排队中" value="QUEUED" /><el-option label="执行中" value="RUNNING" />
        <el-option label="处理完成" value="SUCCEEDED" /><el-option label="任务失败" value="FAILED" />
      </el-select>
      <span class="muted">按创建时间倒序 · 仅显示当前权限可见的本人任务</span>
    </div>
    <el-alert title="任务处理完成后，请查看关联清单的逐条结果。任务失败或结果待核对时，请先核实已有结果。"
      type="info" show-icon :closable="false" />
    <div v-if="error" class="request-error" role="alert">
      <span>{{ error }}</span><el-button size="small" @click="retry">重试加载</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id" stripe :empty-text="emptyText" class="task-table">
      <el-table-column label="任务" min-width="180">
        <template slot-scope="scope"><strong>{{ actionLabel(scope.row.action) }}</strong><small class="task-id" :title="scope.row.id">{{ scope.row.id }}</small></template>
      </el-table-column>
      <el-table-column label="状态" width="112"><template slot-scope="scope"><el-tag :type="statusTone(scope.row.status)" size="small">{{ taskStatus(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="当前说明" min-width="245"><template slot-scope="scope"><span>{{ scope.row.message || taskHint(scope.row) }}</span></template></el-table-column>
      <el-table-column label="创建时间" width="164"><template slot-scope="scope">{{ time(scope.row.createdAt) }}</template></el-table-column>
      <el-table-column label="更新时间" width="164"><template slot-scope="scope">{{ time(scope.row.updatedAt) }}</template></el-table-column>
      <el-table-column label="关联清单" min-width="140">
        <template slot-scope="scope">
          <div v-for="(plan, index) in scope.row.plans" :key="plan.planId"><el-button type="text" size="small" @click="openPlan(plan.planId, scope.row.action)">查看清单<span v-if="scope.row.plans.length > 1"> {{ index + 1 }}</span></el-button></div>
          <span v-if="!scope.row.plans.length" class="muted">{{ scope.row.status === 'QUEUED' || scope.row.status === 'RUNNING' ? '等待任务结果' : '关联信息不可用' }}</span>
        </template>
      </el-table-column>
    </el-table>
    <div class="tasks-pagination">
      <span class="muted">{{ pageLabel }}</span>
      <el-button size="small" :disabled="loading || !!error || !previous.length" @click="previousPage">上一页</el-button>
      <el-button size="small" :disabled="loading || !!error || !nextCursor" @click="nextPage">更早任务</el-button>
    </div>

    <el-dialog title="清单结果" :visible.sync="detailVisible" width="90%" @close="closePlan">
      <div v-loading="detailLoading">
        <div v-if="detailError" class="request-error" role="alert"><span>{{ detailError }}</span><el-button size="small" @click="loadPlan(detailTargetPage)">重试加载清单</el-button></div>
        <template v-if="detail">
          <div class="plan-summary"><span>清单：{{ detail.plan.planId }}</span><business-label :value="detail.plan.status" tag /><span>共 {{ detail.plan.count }} 条</span><el-button size="small" @click="loadPlan(detailPage)">刷新清单</el-button><el-button v-if="!operatorPlan" type="text" @click="traceId = detail.plan.planId">查看完整追溯</el-button><el-button v-else type="text" @click="$router.push('/operations')">进入运营治理</el-button></div>
          <el-alert v-if="detail.plan.status === 'EXECUTING' || detail.plan.status === 'REVIEW_REQUIRED'" :title="detail.plan.status === 'EXECUTING' ? '清单仍在执行，可稍后刷新当前结果。' : (operatorPlan ? '仍有结果需要核对，请进入运营治理继续处理。' : '仍有结果需要核对，请从业务助手或报表派单记录继续处理。')" type="warning" :closable="false" />
          <el-table :data="detail.items" size="small" border empty-text="当前页没有可展示的清单条目">
            <el-table-column prop="reportName" label="报表" min-width="100" />
            <el-table-column prop="docNo" label="单据号" min-width="130" />
            <el-table-column prop="companyCode" label="公司" width="70" />
            <el-table-column prop="label" label="摘要" min-width="140" show-overflow-tooltip />
            <el-table-column label="金额（元）" min-width="260" align="right" class-name="amount-column" show-overflow-tooltip><template slot-scope="scope">{{ formatAmount(scope.row.amount) }}</template></el-table-column>
            <el-table-column label="结果" width="120"><template slot-scope="scope"><business-label :value="scope.row.status" tag /></template></el-table-column>
            <el-table-column label="说明" min-width="160"><template slot-scope="scope">{{ scope.row.errorMessage || scope.row.errorCode || '—' }}</template></el-table-column>
          </el-table>
          <el-pagination v-if="detail.plan.count > 50" :current-page="detailPage" :page-size="50" :total="detail.plan.count" :disabled="detailLoading" layout="prev, pager, next" @current-change="loadPlan" />
        </template>
      </div>
    </el-dialog>
    <dispatch-trace v-if="traceId" :key="traceId" :plan-id="traceId" @close="traceId = null" />
  </el-card>
</template>

<script>
/**
 * 当前用户任务入口；读取持久化历史并沿关联标识查看最新清单和追溯，不产生新的派单命令。
 * 列表、详情各自绑定请求代次和登录上下文；切换筛选、关闭窗口或离开页面后，迟到数据不得回写。
 */
import { fetchTaskHistory, fetchTaskPlan } from '../api/taskHistory'
import { getCurrentUserId, getSessionToken } from '../auth'
import { displayLabel, statusTone } from '../utils/presentation'
import { formatAmount } from '../utils/amount'
import DispatchTrace from '../components/agent/DispatchTrace.vue'

const ACTIONS = { DIRECT: '手工派单', CONFIRM: '确认派单', RETRY_FAILED: '失败项重试', RECONCILE: '派单结果核对', OP_RETRY_FAILED: '管理员重试', OP_RECONCILE: '管理员核对' }

export default {
  name: 'MyTasks',
  components: { DispatchTrace },
  data() {
    return { owner: getCurrentUserId(), sessionToken: getSessionToken(), disposed: false, sequence: 0,
      rows: [], status: '', loading: false, error: '', cursor: null, nextCursor: null, previous: [], retryRequest: null,
      detailVisible: false, detailLoading: false, detailError: '', detail: null, selectedPlan: null,
      detailPage: 1, detailTargetPage: 1, detailSequence: 0, operatorPlan: false, traceId: null }
  },
  computed: {
    emptyText() {
      if (this.loading) return '正在读取任务…'
      if (this.error) return '任务加载失败，请重试'
      return this.nextCursor ? '本段暂无当前权限可见的任务，可继续查看更早任务' : '当前筛选下暂无可见任务'
    },
    pageLabel() { return this.loading ? '正在读取当前页…' : (this.error ? '尚未取得当前页结果' : `第 ${this.previous.length + 1} 页 · 本页 ${this.rows.length} 项`) }
  },
  created() { this.refresh() },
  beforeDestroy() { this.disposed = true; this.sequence++; this.closePlan(); this.rows = [] },
  methods: {
    formatAmount, statusTone,
    actionLabel(value) { return ACTIONS[value] || value },
    taskStatus(value) { return value === 'SUCCEEDED' ? '处理完成' : displayLabel(value) },
    time(value) { return displayLabel(value, 'time') },
    taskHint(row) {
      if (row.status === 'SUCCEEDED') return row.resultAvailable ? '处理完成，请查看清单逐条结果' : '结果保留期已到期，可查看仍可读取的关联清单'
      return row.status === 'FAILED' ? '任务未完成，请核对既有清单' : '可稍后刷新任务进度'
    },
    current() { return !this.disposed && this.owner === getCurrentUserId() && this.sessionToken === getSessionToken() },
    refresh() { this.closePlan(); return this.load(null, []) },
    changeStatus() { return this.refresh() },
    retry() { const request = this.retryRequest; return request ? this.load(request.cursor, request.previous) : this.refresh() },
    nextPage() { if (!this.loading && !this.error && this.nextCursor) return this.load(this.nextCursor, [...this.previous, this.cursor]) },
    previousPage() { if (!this.loading && !this.error && this.previous.length) return this.load(this.previous[this.previous.length - 1], this.previous.slice(0, -1)) },
    /** 只在成功后提交分页位置；失败保留目标页供原位重试，换筛选和刷新始终回到第一页。 */
    async load(cursor, previous) {
      if (!this.current()) return
      const sequence = ++this.sequence, status = this.status
      this.loading = true; this.error = ''; this.rows = []; this.nextCursor = null
      this.retryRequest = { cursor, previous: [...previous] }
      try {
        const page = await fetchTaskHistory({ before: cursor, status, size: 20 })
        if (!this.current() || sequence !== this.sequence || status !== this.status) return
        this.rows = page.rows; this.nextCursor = page.nextCursor; this.cursor = cursor; this.previous = [...previous]; this.retryRequest = null
      } catch (error) {
        if (this.current() && sequence === this.sequence && status === this.status) this.error = error.message || '任务加载失败，请重试'
      } finally {
        if (this.current() && sequence === this.sequence) this.loading = false
      }
    },
    openPlan(planId, action = '') { if (!this.current()) return; this.closePlan(); this.selectedPlan = planId; this.operatorPlan = ['OP_RETRY_FAILED', 'OP_RECONCILE'].includes(action); this.detailVisible = true; return this.loadPlan(1) },
    closePlan() { this.detailSequence++; this.detailVisible = false; this.detail = null; this.detailError = ''; this.detailLoading = false; this.selectedPlan = null; this.operatorPlan = false; this.traceId = null },
    /** 每次详情读取同时绑定清单与页码，清空上一份事实；失败、权限变化或关窗不保留可误用的旧结果。 */
    async loadPlan(page = 1) {
      if (!this.current() || !this.detailVisible || !this.selectedPlan) return
      const sequence = ++this.detailSequence, planId = this.selectedPlan
      this.detailTargetPage = page; this.detailLoading = true; this.detailError = ''; this.detail = null
      try {
        const detail = await fetchTaskPlan(planId, page, this.operatorPlan)
        if (!this.current() || !this.detailVisible || sequence !== this.detailSequence || planId !== this.selectedPlan) return
        this.detail = detail; this.detailPage = page
      } catch (error) {
        if (this.current() && this.detailVisible && sequence === this.detailSequence) this.detailError = error.message || '清单加载失败，请重试'
      } finally {
        if (this.current() && sequence === this.detailSequence) this.detailLoading = false
      }
    }
  }
}
</script>

<style scoped>
.tasks-toolbar, .tasks-pagination, .plan-summary, .request-error { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.tasks-toolbar { margin-bottom: 18px; }
.muted { color: #748399; font-size: 12px; }
.task-table { margin: 18px 0; }
.task-table strong { color: #294360; font-size: 13px; font-weight: 600; }
.task-id { display: block; max-width: 180px; overflow: hidden; text-overflow: ellipsis; color: #8794a5; font: 11px/1.8 Consolas, monospace; }
.tasks-pagination { justify-content: flex-end; margin-top: 16px; }
.request-error { justify-content: space-between; color: #b73838; padding: 14px; background: #fff5f4; border: 1px solid #f2cfcc; border-radius: 6px; margin: 16px 0; }
.plan-summary { color: #526984; font-size: 13px; margin-bottom: 18px; }
.el-pagination { margin-top: 18px; text-align: right; }
.plan-summary + .el-alert { margin-bottom: 16px; }
</style>
