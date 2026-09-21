<template>
  <el-card shadow="never" :class="['agent-card', { expired: readonly }]">
    <div slot="header" class="card-head">
      <span class="card-title">
        <i class="el-icon-view"></i> 可派单记录预览
        <el-tag size="mini" type="info">共 {{ payload.total }} 条</el-tag>
        <el-tag v-if="history" size="mini">历史快照</el-tag>
        <el-tag v-else-if="readonly" size="mini" type="info">已作废</el-tag>
      </span>
      <span class="card-sub">
        <span v-for="r in payload.byReport" :key="r.reportType" class="count">
          {{ r.reportName }} {{ r.count }} 条
        </span>
      </span>
    </div>

    <el-table
      ref="table"
      :data="payload.records"
      size="mini"
      border
      max-height="320"
      :row-key="rowKey"
      @selection-change="onSelectionChange"
    >
      <el-table-column v-if="!readonly" type="selection" width="45" align="center" :reserve-selection="false" :selectable="canSelect" />
      <el-table-column prop="reportName" label="报表" width="90" />
      <el-table-column prop="docNo" label="单据号" width="130" />
      <el-table-column prop="label" label="摘要" min-width="140" show-overflow-tooltip />
      <el-table-column prop="companyCode" label="公司" width="60" align="center" />
      <el-table-column label="金额(元)" width="120" align="right">
        <template slot-scope="scope">{{ formatAmount(scope.row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="date" label="日期" width="105" />
      <el-table-column prop="ruleName" label="命中规则" width="140" show-overflow-tooltip />
    </el-table>

    <div class="card-foot">
      <span class="rule-desc">
        <template v-for="(desc, type) in payload.ruleDescriptions">
          <span :key="type" class="rule">{{ desc }}</span>
        </template>
      </span>
      <span v-if="readonly" class="expired-hint">{{ history ? '历史快照仅供查看，如需派单请重新查询。' : '该预览已作废，如需派单请使用最新预览。' }}</span>
      <span v-if="!readonly" class="foot-actions">
        <span class="sel-hint">已勾选 {{ selectedCount }} / {{ payload.records.length }} 条，取消勾选的记录派单时会排除</span>
        <el-button type="warning" size="mini" :disabled="selectedCount === 0 || busy" @click="$emit('dispatch-selected')">派单已勾选记录</el-button>
      </span>
    </div>
  </el-card>
</template>

<script>
export default {
  name: 'PreviewCard',
  props: {
    payload: { type: Object, required: true },
    // 非最新预览或历史快照时只读
    readonly: { type: Boolean, default: false },
    // 正在发送消息时禁用按钮
    busy: { type: Boolean, default: false },
    history: { type: Boolean, default: false }
  },
  data() {
    return {
      selectedCount: 0
    }
  },
  watch: {
    readonly(v) {
      if (!v) this.selectAll()
    }
  },
  mounted() {
    if (!this.readonly) this.selectAll()
  },
  methods: {
    canSelect() {
      return !this.readonly && !this.busy
    },
    rowKey(row) {
      return `${row.reportType}-${row.docNo}`
    },
    selectAll() {
      this.$nextTick(() => {
        const table = this.$refs.table
        if (!table || this.readonly) return
        table.clearSelection()
        this.payload.records.forEach((row) => table.toggleRowSelection(row, true))
      })
    },
    onSelectionChange(rows) {
      if (this.readonly) return
      this.selectedCount = rows.length
      const selected = new Set(rows.map((r) => r.docNo))
      const unselected = this.payload.records.filter((r) => !selected.has(r.docNo)).map((r) => r.docNo)
      this.$emit('selection-change', unselected)
    },
    formatAmount(value) {
      const num = Number(value)
      if (Number.isNaN(num)) return value
      return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    }
  }
}
</script>

<style scoped>
.agent-card {
  width: 100%;
}

.agent-card.expired {
  background: #fafafa;
}

.expired-hint {
  color: #909399;
  font-size: 12px;
}

.agent-card >>> .el-card__header {
  padding: 8px 14px;
}

.agent-card >>> .el-card__body {
  padding: 10px 14px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
}

.card-title {
  font-weight: 600;
  font-size: 13px;
}

.card-title .el-tag {
  margin-left: 6px;
}

.card-sub .count {
  font-size: 12px;
  color: #606266;
  margin-left: 10px;
}

.card-foot {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
}

.rule-desc .rule {
  color: #909399;
  margin-right: 10px;
}

.foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sel-hint {
  color: #e6a23c;
}
</style>
