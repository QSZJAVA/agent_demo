<template>
  <el-card shadow="never" class="report-card">
    <div slot="header" class="card-header">
      <div class="left">
        <div class="report-heading"><span class="title">{{ title }}</span><span class="count">共 {{ total }} 条</span></div>
        <p class="report-description">查看业务明细，勾选未派单记录后发起派单</p>
      </div>
      <div class="right">
        <el-button
          type="primary"
          size="small"
          icon="el-icon-s-promotion"
          :disabled="selectedRows.length === 0 || dispatching || loading"
          :loading="dispatching"
          @click="handleDispatch"
        >
          发起派单{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
        </el-button>
        <el-button size="small" icon="el-icon-time" @click="openManualPlans">派单记录</el-button>
        <el-button size="small" icon="el-icon-refresh" :disabled="loading || dispatching" @click="$emit('refresh')">
          刷新
        </el-button>
      </div>
    </div>

    <div class="report-table-caption">
      <span><i class="el-icon-document"></i> 本页 {{ data.length }} 条记录</span>
      <span v-if="selectedRows.length" class="selection-count">已选择 {{ selectedRows.length }} 条</span>
      <span v-else>每次最多派单 50 条</span>
    </div>
    <el-drawer :title="`${title} · 手工派单记录`" :visible.sync="manualVisible" size="min(760px, 94vw)" append-to-body custom-class="manual-plan-drawer">
      <div class="manual-plans">
        <el-alert title="结果待核对时，请先核对；明确失败后才能重试。" type="info" :closable="false" show-icon />
        <div class="manual-toolbar"><span>当前报表的手工派单记录</span><el-button size="small" icon="el-icon-refresh" :loading="manualLoading" :disabled="dispatching" @click="loadManualPlans(manualPage)">刷新记录</el-button></div>
        <el-alert v-if="manualError" :title="manualError" type="error" :closable="false" show-icon />
        <el-table ref="manualPlansTable" v-loading="manualLoading" :data="manualPlans" row-key="planId"
          :expand-row-keys="expandedManualPlanIds" size="small"
          :empty-text="manualError ? '记录暂不可用，请刷新重试' : '暂无手工派单记录'"
          @expand-change="onManualExpandChange" @cell-click="onManualCellClick">
          <el-table-column prop="docNo" label="单据号" min-width="140" show-overflow-tooltip />
          <el-table-column label="派单结果" min-width="145"><template slot-scope="scope"><el-tag :type="manualTone(scope.row)" size="mini">{{ manualStatus(scope.row) }}</el-tag></template></el-table-column>
          <el-table-column label="操作" min-width="205"><template slot-scope="scope">
            <el-button v-if="scope.row.status === 'REVIEW_REQUIRED'" type="text" :disabled="dispatching || manualLoading" @click="processManual(scope.row, 'reconcile')">核对结果</el-button>
            <el-button v-if="scope.row.status === 'EXECUTED' && scope.row.retryableCount" type="text" :disabled="dispatching || manualLoading" @click="processManual(scope.row, 'retry')">重试失败项</el-button>
            <el-button v-if="scope.row.status === 'PENDING'" type="text" :disabled="dispatching || manualLoading" @click="processManual(scope.row, 'confirm')">继续派单</el-button>
            <el-button v-if="scope.row.status === 'EXECUTING'" type="text" :disabled="dispatching || manualLoading" @click="processManual(scope.row, 'resume')">刷新结果</el-button>
            <el-button type="text" :aria-expanded="String(expandedManualPlanIds.includes(scope.row.planId))"
              :aria-label="`${expandedManualPlanIds.includes(scope.row.planId) ? '收起' : '展开'} ${scope.row.docNo} 的详情`"
              @click="toggleManualDetails(scope.row)">{{ expandedManualPlanIds.includes(scope.row.planId) ? '收起详情' : '展开详情' }}</el-button>
            <el-button type="text" @click="tracePlanId = scope.row.planId">完整追溯</el-button>
          </template></el-table-column>
          <el-table-column type="expand" width="52" class-name="manual-expand-cell"><template slot-scope="scope"><div class="manual-detail"><p>清单编号：{{ scope.row.planId }}</p><p>结果说明：{{ scope.row.message || '暂无补充说明' }}</p></div></template></el-table-column>
        </el-table>
        <div class="manual-pagination"><span>第 {{ manualPage }} 页 · 每页最多 50 条</span><div>
          <el-button size="small" :disabled="manualPage === 1 || dispatching || manualLoading" @click="loadManualPlans(manualPage - 1)">上一页</el-button>
          <el-button size="small" :disabled="!moreManualPlans || dispatching || manualLoading" @click="loadManualPlans(manualPage + 1)">下一页</el-button>
        </div></div>
      </div>
    </el-drawer>
    <dispatch-trace v-if="tracePlanId" :plan-id="tracePlanId" @close="tracePlanId = null" />

    <el-table
      ref="reportTable"
      v-loading="loading"
      :data="data"
      border
      stripe
      show-summary
      :summary-method="summaryMethod"
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" align="center" :selectable="(row) => !loading && !dispatching && row.dispatchStatus !== 1" />
      <el-table-column type="index" label="序号" width="85" align="center" />
      <el-table-column
        v-for="col in columns"
        :key="col.prop"
        :prop="col.prop"
        :label="col.label"
        :width="col.width"
        :min-width="col.minWidth"
        :align="col.isAmount ? 'right' : 'left'"
        show-overflow-tooltip
      >
        <template slot-scope="scope">
          <span v-if="col.isAmount">{{ formatAmount(scope.row[col.prop]) }}</span>
          <span v-else>{{ scope.row[col.prop] }}</span>
        </template>
      </el-table-column>
      <el-table-column label="派单状态" width="110" align="center">
        <template slot-scope="scope">
          <el-tag v-if="scope.row.dispatchStatus === 1" size="mini" type="success">已派单</el-tag>
          <el-tag v-else size="mini" type="info">未派单</el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      :current-page="page" :page-size="pageSize" :total="total" :page-sizes="[20, 50, 100, 200]"
      :disabled="loading || dispatching" layout="total, sizes, prev, pager, next"
      @current-change="$emit('page-change', $event)" @size-change="$emit('size-change', $event)"
    />
  </el-card>
</template>

<script>
/**
 * 业务报表表格与人工派单清单入口；选择只针对当前页事实，执行后以服务器持久化结果刷新。
 * 记录ID与报表共同确定范围；在途任务通过读取恢复，不能因弹窗或页面刷新再次发起派单。
 */
import { dispatchDirect, fetchManualPlans } from '../api/report'
import { confirmPlan, reconcilePlan, retryFailedPlan } from '../api/agent'
import { resumePlanAction } from '../api/dispatchJob'
import { getCurrentUserId } from '../auth'
import DispatchTrace from './agent/DispatchTrace.vue'

export default {
  name: 'ReportTable',
  components: { DispatchTrace },
  props: {
    title: { type: String, default: '' },
    // 报表目录稳定标识，用于服务端核验当前报表范围。
    reportId: { type: String, default: '' },
    columns: { type: Array, default: () => [] },
    data: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    page: { type: Number, default: 1 },
    pageSize: { type: Number, default: 50 },
    total: { type: Number, default: 0 },
    // 派单提示中展示的单据号字段
    docNoField: { type: String, default: '' }
  },
  data() {
    return {
      selectedRows: [],
      tracePlanId: null,
      dispatching: false,
      manualPlans: [],
      // 使用服务端清单标识保存展开行，刷新返回新对象时仍绑定同一条记录。
      expandedManualPlanIds: [],
      manualPage: 1,
      moreManualPlans: false,
      manualVisible: false,
      manualLoading: false,
      manualError: '',
      manualRequest: 0,
      sessionUserId: getCurrentUserId(),
      disposed: false
    }
  },
  watch: {
    data() {
      this.selectedRows = []
      this.$nextTick(() => {
        if (this.$refs.reportTable) {
          this.$refs.reportTable.clearSelection()
        }
      })
    }
  },
  mounted() { this.loadManualPlans() },
  beforeDestroy() { this.disposed = true },
  methods: {
    isCurrentSession() { return !this.disposed && this.sessionUserId === getCurrentUserId() },
    /** 打开抽屉时读取服务器记录；关闭抽屉不取消后台执行，也不重新提交派单。 */
    openManualPlans() { this.manualVisible = true; this.loadManualPlans() },
    /** 详情仅切换本地显示；文本按钮、箭头周围的单元格共用表格的展开入口，不发起业务操作。 */
    toggleManualDetails(plan) { this.$refs.manualPlansTable.toggleRowExpansion(plan) },
    onManualExpandChange(plan, expandedRows) { this.expandedManualPlanIds = expandedRows.map(row => row.planId) },
    /** 原生箭头自行切换并阻止冒泡；这里仅接住展开列空白处的点击，避免一次点击切换两次。 */
    onManualCellClick(plan, column) {
      if (column.type === 'expand') this.toggleManualDetails(plan)
    },
    manualTone(plan) {
      if (plan.status === 'REVIEW_REQUIRED') return 'warning'
      if (plan.status === 'EXECUTED' && plan.outcome === 'SUCCESS') return 'success'
      if (plan.status === 'EXECUTED' && plan.retryableCount) return 'danger'
      return 'info'
    },
    manualStatus(plan) {
      if (plan.status === 'EXECUTED') return plan.outcome === 'SUCCESS' ? '派单成功' : plan.retryableCount ? '明确失败，可重试' : '未派单'
      return { REVIEW_REQUIRED: '结果待核对', EXECUTING: '执行中', PENDING: '尚未完成',
        EXPIRED: '已过期', CANCELLED: '已取消' }[plan.status] || plan.status
    },
    async loadManualPlans(page = 1) {
      if (!this.isCurrentSession()) return
      const request = ++this.manualRequest
      this.manualLoading = true
      this.manualError = ''
      try {
        const plans = await fetchManualPlans(this.reportId, page)
        if (!this.isCurrentSession() || request !== this.manualRequest) return
        this.manualPlans = plans
        this.expandedManualPlanIds = this.expandedManualPlanIds.filter(id => plans.some(plan => plan.planId === id))
        this.manualPage = page
        this.moreManualPlans = plans.length === 50
      } catch (e) {
        // 加载失败不能伪装为没有派单记录；仅最后一次请求可以更新抽屉提示和加载状态。
        if (this.isCurrentSession() && request === this.manualRequest) this.manualError = '派单记录加载失败，请刷新重试。'
      } finally {
        if (this.isCurrentSession() && request === this.manualRequest) this.manualLoading = false
      }
    },
    /** 人工清单核对、重试或恢复时检查当前会话仍有效；操作完成后重新读取清单列表，不能根据传输异常猜测未执行。 */
    async processManual(plan, action) {
      if (!this.isCurrentSession() || this.dispatching) return
      this.dispatching = true
      try {
        const operation = { reconcile: reconcilePlan, retry: retryFailedPlan, confirm: confirmPlan, resume: resumePlanAction }[action]
        await operation(plan.planId)
        if (this.isCurrentSession()) this.$emit('refresh')
      } catch (e) {
        /* 拦截器提示原因；重新读取状态，未知结果不会当作明确失败。 */
      } finally {
        this.dispatching = false
        this.loadManualPlans(this.manualPage)
      }
    },
    formatAmount(value) {
      const num = Number(value)
      if (Number.isNaN(num)) {
        return value === null || value === undefined ? '' : value
      }
      return num.toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
      })
    },
    handleSelectionChange(rows) {
      this.selectedRows = rows
    },
    /** 以当前页人工选择创建任务；处理结束清除选择并刷新报表，最多50条，服务端再次验证公司权限与派单状态。 */
    async handleDispatch() {
      if (!this.isCurrentSession() || this.dispatching || this.loading) return
      if (!this.selectedRows.length) {
        this.$message.warning('请先勾选需要派单的记录')
        return
      }
      if (this.selectedRows.length > 50) {
        this.$message.warning('每次最多派单 50 条，请分批选择')
        return
      }
      const ids = this.selectedRows.map((row) => String(row.id))
      const nos = this.selectedRows.map((row) => row[this.docNoField] || row.id).join('、')
      this.dispatching = true
      try {
        await this.$confirm(`确认对以下 ${this.selectedRows.length} 条记录派单？${nos}`, '手工派单', {
          type: 'warning',
          confirmButtonText: '确认派单',
          cancelButtonText: '取消'
        })
      } catch (e) {
        this.dispatching = false
        return
      }
      if (!this.isCurrentSession()) return
      try {
        const result = await dispatchDirect(this.reportId, ids)
        if (!this.isCurrentSession()) return
        this.manualVisible = true
        if (result.reviewCount) this.$message.warning(`有 ${result.reviewCount} 条结果待核对，请在派单记录中核对，勿重复提交`)
        else this.$message.info(`本次处理：成功 ${result.successCount} 条，未成功 ${result.failedCount} 条；详情见派单记录`)
        this.$emit('refresh')
      } finally {
        this.dispatching = false
        this.loadManualPlans()
      }
    },
    summaryMethod({ columns }) {
      return columns.map((column, index) => {
        // 第 0 列为勾选列，第 1 列为序号列
        if (index === 0) {
          return ''
        }
        if (index === 1) {
          return '本页合计'
        }
        const target = this.columns[index - 2]
        if (target && target.isAmount) {
          const total = this.data.reduce((acc, row) => acc + Number(row[target.prop] || 0), 0)
          return this.formatAmount(total)
        }
        return ''
      })
    }
  }
}
</script>

<style scoped>
.report-card { min-height: 320px; }
.card-header, .report-heading, .card-header .right, .report-table-caption, .manual-toolbar, .manual-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.card-header { flex-wrap: wrap; }
.report-heading { justify-content: flex-start; }
.card-header .right { flex-wrap: wrap; gap: 8px; }
.card-header .right .el-button + .el-button { margin-left: 0; }
.card-header .title { font-size: 20px; font-weight: 600; color: #21354e; }
.card-header .count { font-size: 12px; color: #596e86; background: #f0f4f9; border-radius: 12px; padding: 3px 9px; }
.report-description { margin: 8px 0 0; color: #738196; font-size: 13px; }
.report-table-caption { margin-bottom: 14px; color: #738196; font-size: 12px; }
.report-table-caption i { margin-right: 5px; }
.selection-count { color: #2873cc; font-weight: 600; }
.manual-plans { padding: 0 24px 24px; }
.manual-toolbar { margin: 20px 0 10px; font-size: 13px; color: #66778b; }
.manual-pagination { flex-wrap: wrap; margin-top: 20px; color: #738196; font-size: 12px; }
.manual-detail { padding: 0 16px; color: #66778b; font-size: 12px; overflow-wrap: anywhere; }
.manual-plans >>> .manual-expand-cell { cursor: pointer; }
.manual-plans >>> .manual-expand-cell .cell { padding: 0; }
.manual-plans >>> .manual-expand-cell .el-table__expand-icon { height: 40px; line-height: 40px; }
.el-pagination { margin-top: 20px; text-align: right; }
@media (max-width: 700px) { .manual-plans { padding: 0 14px 18px; } .el-pagination { text-align: left; overflow-x: auto; } }
</style>
