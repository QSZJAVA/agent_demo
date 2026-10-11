<template>
  <el-dialog title="修改查询条件" :visible="true" width="850px" append-to-body :close-on-click-modal="false" @close="$emit('close')">
    <p>保留当前公司、报表、展示方式和排序。提交后重新核验全部条件，并回到第一页。</p>
    <p class="edit-note">各组之间满足任意一组；一组内须同时满足全部条件。数值按来源字段基本单位填写。</p>
    <div v-for="(group, groupIndex) in groups" :key="groupIndex" class="condition-group">
      <div class="condition-title">条件组 {{ groupIndex + 1 }} <el-button type="text" @click="groups.splice(groupIndex, 1)">删除本组</el-button></div>
      <div v-for="(filter, filterIndex) in group.allOf" :key="filterIndex" class="condition-row">
        <el-select v-model="filter.field" placeholder="字段" @change="fieldChanged(filter)"><el-option v-for="column in fields" :key="column.name" :label="column.description || column.name" :value="column.name" /></el-select>
        <el-select v-model="filter.operator" placeholder="比较方式"><el-option v-for="operator in allowed(filter)" :key="operator" :label="operatorLabels[operator]" :value="operator" /></el-select>
        <el-input v-if="!['IS_NULL','NOT_NULL'].includes(filter.operator)" v-model="filter.text" type="textarea" :rows="1" placeholder="集合比较每行一个值" />
        <span v-else class="no-value">无需填写值</span>
        <el-button type="text" @click="group.allOf.splice(filterIndex, 1)">删除</el-button>
      </div>
      <el-button size="mini" :disabled="group.allOf.length >= 8" @click="addCondition(group)">增加组内条件</el-button>
    </div>
    <el-button size="small" :disabled="groups.length >= 8" @click="addGroup">增加另一组条件</el-button>
    <p v-if="!groups.length">提交将清除附加字段筛选，保留公司和报表范围。</p>
    <p v-if="error" role="alert" class="edit-error">{{ error }}</p>
    <span slot="footer"><el-button @click="$emit('close')">取消</el-button><el-button type="primary" :disabled="busy" @click="submit">应用并重新查询</el-button></span>
  </el-dialog>
</template>
<script>
/** 当前查询的完整条件编辑器；只发出绑定轮次的副本，服务端负责权限、类型、旧引用与模型复核。 */
import { operatorLabels, operatorsFor, editableGroups, buildQueryEdit } from '../../utils/queryConditions'
export default {
  name: 'QueryConditionEditor',
  props: { payload: { type: Object, required: true }, busy: Boolean },
  data() { return { groups: editableGroups(this.payload.query.conditions), error: '', operatorLabels } },
  computed: { fields() { return this.payload.columns.filter(column => column.name !== 'rowKey') } },
  methods: {
    allowed(filter) { const field = this.fields.find(column => column.name === filter.field); return field ? operatorsFor(field.type) : [] },
    fieldChanged(filter) { filter.operator = 'EQ'; filter.text = '' },
    addCondition(group) { if (group.allOf.length < 8) group.allOf.push({ field: this.fields[0] && this.fields[0].name, operator: 'EQ', text: '' }) },
    addGroup() { if (this.groups.length < 8) { const group = { allOf: [] }; this.addCondition(group); this.groups.push(group) } },
    submit() { try { const edit = buildQueryEdit(this.payload.interpretation.queryRef, this.groups, this.fields); this.error = ''; this.$emit('submit', edit) } catch (error) { this.error = error.message } }
  }
}
</script>
<style scoped>
.edit-note,.no-value { color: #687586; }.condition-group { border: 1px solid #dce5ed; border-radius: 6px; padding: 12px; margin: 12px 0; }.condition-title { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }.condition-row { display: grid; grid-template-columns: 210px 155px 1fr 40px; gap: 8px; align-items: center; margin: 8px 0; }.edit-error { color: #b43d35; }
</style>
