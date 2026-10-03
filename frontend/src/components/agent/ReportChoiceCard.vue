<template>
  <el-card shadow="never" class="agent-card choice-card">
    <div slot="header" class="card-head">
      <span class="card-title">
        <i class="el-icon-question"></i> 找到多个相关报表，请选择
        <el-tag v-if="payload.query" size="mini" type="info">{{ payload.query }}</el-tag>
      </span>
    </div>

    <el-checkbox-group v-model="selected" :disabled="busy" class="options">
      <div v-for="c in payload.candidates" :key="c.reportId" class="option">
        <el-checkbox :label="c.reportId">
          <span class="name">{{ c.reportName }}</span>
          <span v-if="c.description" class="desc">{{ c.description }}</span>
        </el-checkbox>
      </div>
    </el-checkbox-group>

    <div class="card-foot">
      <span class="hint">{{ chosen ? `已选择：${chosen}` : '系统不会替你猜测报表，勾选后再查询' }}</span>
      <el-button type="primary" size="mini" :disabled="!selected.length || busy" @click="$emit('choose', selected.slice())">
        查询所选报表
      </el-button>
    </div>
  </el-card>
</template>

<script>
/**
 * 报表歧义选择卡片；候选来自可见目录，用户选择后由服务端重新验证当前业务授权。
 */
export default {
  name: 'ReportChoiceCard',
  props: {
    payload: { type: Object, required: true },
    busy: { type: Boolean, default: false },
    // 本卡片上一次选择的报表名称（仅展示）
    chosen: { type: String, default: '' }
  },
  data() {
    return {
      // 已经唯一确定的报表默认勾选，歧义部分由用户选
      selected: (this.payload.preselected || []).slice()
    }
  }
}
</script>

<style scoped>
.agent-card {
  width: 100%;
  border-color: #b3d8ff;
}

.agent-card >>> .el-card__header {
  padding: 8px 14px;
}

.agent-card >>> .el-card__body {
  padding: 10px 14px;
}

.card-title {
  font-weight: 600;
  font-size: 13px;
}

.card-title .el-tag {
  margin-left: 6px;
}

.options .option {
  margin-bottom: 6px;
}

.name {
  font-weight: 600;
  margin-right: 8px;
}

.desc {
  color: #909399;
  font-size: 12px;
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
