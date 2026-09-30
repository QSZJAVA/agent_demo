<template>
  <el-card shadow="never" class="report-card">
    <div slot="header" class="card-header">
      <div class="left">
        <span class="title">{{ title }}</span>
        <el-tag size="mini" type="info">{{ tableName }}</el-tag>
        <span class="count">本页 {{ data.length }} 条</span>
      </div>
      <div class="right">
        <el-button
          type="warning"
          size="small"
          icon="el-icon-s-promotion"
          :disabled="selectedRows.length === 0 || dispatching || loading"
          :loading="dispatching"
          @click="handleDispatch"
        >
          派单{{ selectedRows.length ? `(${selectedRows.length})` : '' }}
        </el-button>
        <el-button type="primary" size="small" icon="el-icon-refresh" @click="$emit('refresh')">
          刷新
        </el-button>
      </div>
    </div>

    <div v-if="manualPlans.length" class="manual-plans">
      <p>手工派单记录：结果待核对时，请先核对；明确失败后才能重试。</p>
      <div v-for="plan in manualPlans" :key="plan.planId">
        {{ plan.docNo }} · {{ manualStatus(plan) }}
        <span v-if="plan.message">（{{ plan.message }}）</span>
        <el-button v-if="plan.status === 'REVIEW_REQUIRED'" size="mini" :disabled="dispatching" @click="processManual(plan, 'reconcile')">核对结果</el-button>
        <el-button v-if="plan.status === 'EXECUTED' && plan.retryableCount" size="mini" :disabled="dispatching" @click="processManual(plan, 'retry')">重试失败项</el-button>
        <el-button v-if="plan.status === 'PENDING'" size="mini" :disabled="dispatching" @click="processManual(plan, 'confirm')">继续派单</el-button>
        <el-button size="mini" @click="tracePlanId = plan.planId">查看完整追溯</el-button>
      </div>
      <el-button size="mini" :disabled="manualPage === 1 || dispatching" @click="loadManualPlans(manualPage - 1)">上一页</el-button>
      <el-button size="mini" :disabled="!moreManualPlans || dispatching" @click="loadManualPlans(manualPage + 1)">下一页</el-button>
    </div>
    <el-button size="mini" :disabled="dispatching" @click="loadManualPlans()">刷新派单记录</el-button>
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
      <el-table-column type="index" label="序号" width="70" align="center" />
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
import { dispatchDirect, fetchManualPlans } from '../api/report'
import { confirmPlan, reconcilePlan, retryFailedPlan } from '../api/agent'
import { getCurrentUserId } from '../auth'
import DispatchTrace from './agent/DispatchTrace.vue'

export default {
  name: 'ReportTable',
  components: { DispatchTrace },
  props: {
    title: { type: String, default: '' },
    tableName: { type: String, default: '' },
    // 后端报表类型：sales / receivable / expense
    reportType: { type: String, default: '' },
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
      manualPage: 1,
      moreManualPlans: false,
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
    manualStatus(plan) {
      if (plan.status === 'EXECUTED') return plan.outcome === 'SUCCESS' ? '派单成功' : plan.retryableCount ? '明确失败，可重试' : '未派单'
      return { REVIEW_REQUIRED: '结果待核对', EXECUTING: '执行中', PENDING: '尚未完成',
        EXPIRED: '已过期，可重新选择记录派单', CANCELLED: '已取消' }[plan.status] || plan.status
    },
    async loadManualPlans(page = 1) {
      if (!this.isCurrentSession()) return
      const request = ++this.manualRequest
      try {
        const plans = await fetchManualPlans(this.reportType, page)
        if (!this.isCurrentSession() || request !== this.manualRequest) return
        this.manualPlans = plans
        this.manualPage = page
        this.moreManualPlans = plans.length === 50
      } catch (e) { /* 请求拦截器提示，可手工刷新。 */ }
    },
    async processManual(plan, action) {
      if (!this.isCurrentSession() || this.dispatching) return
      this.dispatching = true
      try {
        const operation = { reconcile: reconcilePlan, retry: retryFailedPlan, confirm: confirmPlan }[action]
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
    // 手工派单：调用后端派单接口（模拟实现会把记录标记为已派单并写审计）
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
        const result = await dispatchDirect(this.reportType, ids)
        if (!this.isCurrentSession()) return
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
.report-card {
  min-height: 320px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-header .left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-header .right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-header .title {
  font-size: 16px;
  font-weight: 600;
}

.card-header .count {
  font-size: 12px;
  color: #909399;
}
</style>
