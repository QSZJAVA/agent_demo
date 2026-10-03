<template>
  <el-card shadow="never" class="agent-card result-card">
    <div slot="header" class="card-head">
      <span class="card-title">
        <i class="el-icon-s-promotion"></i> 派单结果
        <el-tag size="mini" type="success">成功 {{ payload.successCount }}</el-tag>
        <el-tag v-if="payload.failedCount" size="mini" type="danger">失败 {{ payload.failedCount }}</el-tag>
      </span>
      <span v-if="payload.replayed" class="replayed">该清单已处理，以下为第一次执行的结果</span>
    </div>

    <div v-if="payload.success && payload.success.length" class="section">
      <div class="section-title">已派单</div>
      <el-tag v-for="c in payload.success" :key="c.docNo" size="mini" type="success" class="doc">{{ c.docNo }}</el-tag>
    </div>
    <div v-if="payload.failed && payload.failed.length" class="section">
      <div class="section-title">失败</div>
      <div v-for="f in payload.failed" :key="f.docNo" class="failed">
        <el-tag size="mini" :type="f.outcome === 'SKIPPED' ? 'info' : 'danger'" class="doc">{{ f.docNo }}</el-tag>
        <span v-if="f.outcome === 'SKIPPED'" class="skipped">未派单</span>
        <span class="reason">{{ f.message }}</span>
      </div>
    </div>
    <div v-if="payload.successCount > (payload.success || []).length || payload.failedCount > (payload.failed || []).length"
      class="replayed">这里只展示前 50 条成功和失败记录；完整结果可在清单卡片中分页查看。</div>
    <el-button v-if="canRetry" type="warning" size="mini" :disabled="busy" @click="$emit('retry')">重试明确失败项</el-button>
  </el-card>
</template>

<script>
/**
 * 派单结果展示卡片；数量使用服务端汇总，记录列表为有界样本，完整执行事实从清单和追溯接口读取。
 */
export default {
  name: 'ResultCard',
  props: {
    payload: { type: Object, required: true },
    busy: { type: Boolean, default: false }
  },
  computed: {
    canRetry() {
      return this.payload.planId && (this.payload.retryableCount > 0 ||
        (this.payload.retryableCount == null && (this.payload.failed || []).some((item) => item.outcome === 'FAILED')))
    }
  }
}
</script>

<style scoped>
.agent-card {
  width: 100%;
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

.replayed {
  font-size: 12px;
  color: #909399;
}

.section {
  margin-bottom: 6px;
  font-size: 12px;
}

.section-title {
  color: #909399;
  margin-bottom: 4px;
}

.doc {
  margin: 0 6px 4px 0;
}

.failed {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.skipped {
  color: #909399;
}

.reason {
  color: #f56c6c;
}
</style>
