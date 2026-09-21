<template>
  <el-card shadow="never" class="report-card">
    <div slot="header" class="card-header">
      <div class="left">
        <span class="title">{{ title }}</span>
        <el-tag size="mini" type="info">{{ tableName }}</el-tag>
        <span class="count">共 {{ data.length }} 条</span>
      </div>
      <div class="right">
        <el-button
          type="warning"
          size="small"
          icon="el-icon-s-promotion"
          :disabled="selectedRows.length === 0"
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
      <el-table-column type="selection" width="55" align="center" :selectable="(row) => row.dispatchStatus !== 1" />
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
  </el-card>
</template>

<script>
import { dispatchDirect } from '../api/report'

export default {
  name: 'ReportTable',
  props: {
    title: { type: String, default: '' },
    tableName: { type: String, default: '' },
    // 后端报表类型：sales / receivable / expense
    reportType: { type: String, default: '' },
    columns: { type: Array, default: () => [] },
    data: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    // 派单提示中展示的单据号字段
    docNoField: { type: String, default: '' }
  },
  data() {
    return {
      selectedRows: [],
      dispatching: false
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
  methods: {
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
      if (!this.selectedRows.length) {
        this.$message.warning('请先勾选需要派单的记录')
        return
      }
      const nos = this.selectedRows.map((row) => row[this.docNoField] || row.id).join('、')
      try {
        await this.$confirm(`确认对以下 ${this.selectedRows.length} 条记录派单？${nos}`, '手工派单', {
          type: 'warning',
          confirmButtonText: '确认派单',
          cancelButtonText: '取消'
        })
      } catch (e) {
        return
      }
      this.dispatching = true
      try {
        const result = await dispatchDirect(this.reportType, this.selectedRows.map((r) => r.id))
        this.$message.success(`派单完成：成功 ${result.successCount} 条，失败 ${result.failedCount} 条`)
        this.$emit('refresh')
      } finally {
        this.dispatching = false
      }
    },
    summaryMethod({ columns }) {
      return columns.map((column, index) => {
        // 第 0 列为勾选列，第 1 列为序号列
        if (index === 0) {
          return ''
        }
        if (index === 1) {
          return '合计'
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
