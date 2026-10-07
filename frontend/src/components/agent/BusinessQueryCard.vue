<template>
  <section class="business-query-card">
    <div class="query-heading"><strong>{{ title }}</strong><el-tag size="mini" type="info">只读查询</el-tag></div>
    <p class="query-source">{{ payload.source }} · 查询时间 {{ observedTime }}</p>
    <p class="query-scope">{{ scopeDescription }}</p>
    <p class="query-scope">筛选：{{ filterDescription }}</p>
    <div class="query-totals">
      <span>匹配 <b>{{ payload.total }}</b> 条</span>
      <span v-for="(amount, currency) in payload.summary.amountsByCurrency" :key="currency">{{ currency }} 合计 {{ amount }}</span>
      <span v-if="payload.summary.unclassifiedAmountCount">{{ payload.summary.unclassifiedAmountCount }} 条金额未声明币种，未计入合计</span>
    </div>
    <div v-if="payload.query.view === 'SUMMARY'" class="query-summary">
      <p>状态分布：{{ counts(payload.summary.statusCounts, 'status') }}</p>
      <p v-if="Object.keys(payload.summary.groups).length">分组统计：{{ counts(payload.summary.groups) }}</p>
      <p v-if="Object.keys(payload.summary.pendingApprovers).length">当前待审批：{{ counts(payload.summary.pendingApprovers) }}</p>
    </div>
    <el-table :data="payload.rows" border stripe size="mini" row-key="rowKey" max-height="350" empty-text="当前查询没有匹配记录">
      <el-table-column type="index" label="序号" width="65" :index="rowIndex" />
      <el-table-column v-for="column in displayColumns" :key="column.name" :label="label(column)" :min-width="column.name === 'title' ? 160 : 120" show-overflow-tooltip>
        <template slot-scope="scope"><business-label v-if="['status','planStatus'].includes(column.name)" :value="scope.row[column.name]" tag /><span v-else>{{ cell(scope.row[column.name]) }}</span></template>
      </el-table-column>
      <el-table-column v-if="payload.query.domain === 'WORK_ORDER'" label="操作" width="96" fixed="right">
        <template slot-scope="scope"><el-button type="text" size="mini" :disabled="busy" @click="$emit('detail', scope.row.orderId)">查看进度</el-button></template>
      </el-table-column>
    </el-table>
    <div class="query-pagination">
      <span>第 {{ payload.query.page }} 页 · 每页 {{ payload.query.size }} 条</span>
      <el-button size="mini" :disabled="busy || !latest || payload.query.page <= 1" @click="$emit('page', payload.query.page - 1)">上一页</el-button>
      <el-button size="mini" :disabled="busy || !latest || payload.query.page * payload.query.size >= payload.total" @click="$emit('page', payload.query.page + 1)">下一页</el-button>
      <span v-if="!latest">历史查询快照</span>
    </div>
    <div v-if="payload.query.domain === 'WORK_ORDER' && payload.query.view === 'DETAIL' && payload.rows.length === 1" class="workflow-detail">
      <p><strong>{{ payload.rows[0].orderId }}</strong> · {{ payload.rows[0].source }}</p>
      <p>{{ payload.rows[0].workflowSummary }}</p>
      <ol class="workflow-steps">
        <li v-for="(step, index) in payload.rows[0].steps" :key="index" :class="{ current: step.status === '处理中' || step.status === '待审批' }">
          <strong>{{ step.name }}</strong><span>{{ step.approver }}</span><el-tag size="mini" :type="step.status === '已驳回' ? 'danger' : step.status === '已完成' ? 'success' : 'info'">{{ step.status }}</el-tag><small>{{ step.date || '尚无完成日期' }}</small>
        </li>
      </ol>
    </div>
  </section>
</template>

<script>
/** 只读业务结果快照：数字与标识按服务端字符串展示；分页仅作用于最新查询，工单步骤全部来自事实载荷。 */
import { displayLabel } from '../../utils/presentation'
export default {
  name: 'BusinessQueryCard',
  props: { payload: { type: Object, required: true }, busy: Boolean, latest: Boolean },
  computed: {
    title() { return { REPORT: '报表数据', DISPATCH: '派单记录', WORK_ORDER: '工单查询' }[this.payload.query.domain] + (this.payload.query.view === 'SUMMARY' ? ' · 总结' : '') },
    observedTime() { return String(this.payload.observedAt).replace('T', ' ').slice(0, 19) },
    scopeDescription() {
      const query = this.payload.query
      const names = [...new Set(this.payload.rows.map(row => row.reportName).filter(Boolean))]
      const reports = names.length === query.reportIds.length ? names.join('、') : `已授权的 ${query.reportIds.length} 类报表`
      const sort = query.sortField ? this.label(this.payload.columns.find(c => c.name === query.sortField) || { name: query.sortField }) : '稳定业务标识'
      return `公司：${query.companyCode || '当前全部授权公司'}；报表范围：${reports}；排序：${sort}${query.sortField ? (query.descending ? ' 降序' : ' 升序') : ''}`
    },
    filterDescription() {
      const operators = { EQ: '等于', NE: '不等于', IN: '属于', NOT_IN: '不属于', IS_NULL: '为空', NOT_NULL: '非空', CONTAINS: '包含', STARTS_WITH: '开头为', GT: '大于', GTE: '大于等于', LT: '小于', LTE: '小于等于' }
      return this.payload.query.conditions.map(group => '(' + group.allOf.map(f => `${this.label(this.payload.columns.find(c => c.name === f.field) || { name: f.field })} ${operators[f.operator] || f.operator} ${f.values.join('、')}`).join(' 且 ') + ')').join(' 或 ') || '无附加字段条件'
    },
    displayColumns() {
      const names = this.payload.query.domain === 'WORK_ORDER'
        ? ['orderId', 'title', 'companyCode', 'reportName', 'status', 'stage', 'assignee', 'amount', 'currency', 'createdDate', 'updatedDate']
        : this.payload.query.domain === 'DISPATCH'
          ? ['docNo', 'reportName', 'companyCode', 'status', 'planStatus', 'operatorName', 'amount', 'createdDate', 'planId', 'requestId', 'message']
          : ['docNo', 'reportName', 'companyCode', 'amount', 'date', 'status', ...this.payload.columns.map(c => c.name).filter(n => !['rowKey', 'recordId', 'reportId', 'currency'].includes(n))]
      return [...new Set(names)].map(n => this.payload.columns.find(c => c.name === n)).filter(Boolean)
    }
  },
  methods: {
    cell(value) { return value == null ? '—' : String(value) },
    rowIndex(index) { return (this.payload.query.page - 1) * this.payload.query.size + index + 1 },
    counts(values, domain) { return Object.entries(values).map(([key, count]) => `${domain ? displayLabel(key, domain) : key} ${count} 条`).join('；') || '暂无记录' },
    label(column) {
      return { docNo: '单据号', reportName: '报表', companyCode: '公司', amount: '金额', currency: '币种', date: '业务日期', status: '状态', planStatus: '清单状态', operatorName: '创建人', createdByMe: '本人创建', createdDate: '创建日期', updatedDate: '更新日期', planId: '清单编号', requestId: '请求号', message: '结果说明', orderId: '工单编号', title: '主题', stage: '当前环节', assignee: '当前处理人' }[column.name] || column.description || column.name
    }
  }
}
</script>

<style scoped>
.business-query-card { padding: 16px; border: 1px solid #dce5ed; border-radius: 8px; background: #fff; min-width: 0; }
.query-heading, .query-totals, .query-pagination { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.query-source, .query-pagination { color: #687586; font-size: 12px; }
.query-scope { color: #526576; font-size: 12px; overflow-wrap: anywhere; }
.query-totals { margin: 12px 0; font-size: 13px; }
.query-summary { padding: 2px 12px; background: #f4f8fc; margin-bottom: 12px; font-size: 13px; }
.query-pagination { margin-top: 12px; }
.workflow-detail { border-top: 1px solid #e4e9ef; margin-top: 16px; padding-top: 8px; font-size: 13px; }
.workflow-steps { list-style: none; padding: 0; }
.workflow-steps li { display: flex; gap: 12px; flex-wrap: wrap; padding: 10px; border-left: 3px solid #dce5ed; margin-bottom: 4px; }
.workflow-steps li.current { border-left-color: #409eff; background: #f0f7ff; }
.workflow-steps small { color: #687586; }
</style>
