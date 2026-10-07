<template>
  <el-tag v-if="tag" :type="tone" size="mini" :title="rawValue" class="business-label">{{ label }}</el-tag>
  <span v-else :title="rawValue">{{ label }}</span>
</template>

<script>
/** 中文代码展示组件；业务枚举保留在提示文本中，翻译不会参与状态流转或提交数据。 */
import { displayLabel, statusTone } from '../utils/presentation'

export default {
  name: 'BusinessLabel',
  props: {
    // 接口返回的原始枚举或空值，仅用于展示。
    value: { type: [String, Number], default: null },
    // 展示词典分类：状态、结果、操作环节、审计动作等。
    domain: { type: String, default: 'status' },
    // 状态类信息可用颜色标签强调；颜色不替代文字含义。
    tag: { type: Boolean, default: false }
  },
  computed: {
    label() { return displayLabel(this.value, this.domain) },
    tone() { return statusTone(this.value) },
    rawValue() { return this.value == null ? '' : String(this.value) }
  }
}
</script>

<style scoped>
.business-label { max-width: 100%; white-space: normal; height: auto; line-height: 20px; }
</style>
