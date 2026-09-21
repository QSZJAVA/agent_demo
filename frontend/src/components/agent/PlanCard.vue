<template>
  <el-card shadow="never" :class="['agent-card', 'plan-card', state]">
    <div slot="header" class="card-head">
      <span class="card-title">
        <i class="el-icon-warning-outline"></i> 待确认派单清单
        <el-tag size="mini" type="warning">{{ payload.count }} 条</el-tag>
        <el-tag v-if="state === 'executed'" size="mini" type="success">已执行</el-tag>
        <el-tag v-else-if="state === 'cancelled'" size="mini" type="info">已取消</el-tag>
        <el-tag v-else-if="state === 'expired'" size="mini" type="info">已过期</el-tag>
      </span>
      <span v-if="payload.excluded && payload.excluded.length" class="excluded">
        已排除：{{ payload.excluded.join('、') }}
      </span>
    </div>

    <el-table :data="payload.records" size="mini" border max-height="240">
      <el-table-column prop="reportName" label="报表" width="90" />
      <el-table-column prop="docNo" label="单据号" width="130" />
      <el-table-column prop="label" label="摘要" min-width="140" show-overflow-tooltip />
      <el-table-column prop="companyCode" label="公司" width="60" align="center" />
      <el-table-column label="金额(元)" width="120" align="right">
        <template slot-scope="scope">{{ formatAmount(scope.row.amount) }}</template>
      </el-table-column>
    </el-table>

    <div class="card-foot">
      <template v-if="state === 'pending'">
        <span class="hint">派单不可撤销，请核对后确认。清单 10 分钟内有效。</span>
        <span>
          <el-button size="mini" :disabled="busy" @click="$emit('cancel')">取消</el-button>
          <el-button type="danger" size="mini" :loading="busy" @click="$emit('confirm')">确认派单</el-button>
        </span>
      </template>
      <span v-else-if="state === 'expired'" class="hint">该清单已过期，如需派单请重新预览。</span>
    </div>
  </el-card>
</template>

<script>
export default {
  name: 'PlanCard',
  props: {
    payload: { type: Object, required: true },
    // pending / executed / cancelled / expired
    state: { type: String, default: 'pending' },
    busy: { type: Boolean, default: false }
  },
  methods: {
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

.plan-card.pending {
  border-color: #e6a23c;
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

.excluded {
  font-size: 12px;
  color: #909399;
}

.card-foot {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
}

.hint {
  color: #909399;
}
</style>
