<template>
  <el-card shadow="never" v-loading="loading" class="operations-card">
    <div slot="header" class="page-toolbar"><div><h1>运营治理</h1><p>掌握业务表现，跟进异常任务与运行策略</p></div><el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button></div>
    <el-alert v-if="!admin" title="仅管理员可访问运营工作台" type="info" :closable="false" />
    <el-tabs v-else v-model="tab" @tab-click="loadTab">
      <el-tab-pane label="业务指标" name="metrics">
        <div class="section-toolbar"><div><h3>业务概览</h3><span class="muted">所选时间内的解析与派单表现</span></div><el-select v-model="days" size="small" aria-label="统计时间范围" @change="loadTab"><el-option v-for="d in [1,7,30,90]" :key="d" :value="d" :label="`最近 ${d} 天`" /></el-select></div>
        <div class="cards">
          <div class="metric-card"><span><i class="el-icon-aim"></i> 解析唯一命中率</span><strong>{{ ratio(['EXACT','ALIAS','FUZZY']) }}</strong><small>精确、别名与近似匹配</small></div>
          <div class="metric-card"><span><i class="el-icon-help"></i> 歧义率</span><strong>{{ ratio(['AMBIGUOUS']) }}</strong><small>需要进一步明确报表范围</small></div>
          <div class="metric-card"><span><i class="el-icon-search"></i> 无匹配率</span><strong>{{ ratio(['NONE']) }}</strong><small>未找到符合表达的报表</small></div>
          <div class="metric-card"><span><i class="el-icon-circle-check"></i> 派单条目成功率</span><strong>{{ dispatchRate }}</strong><small>包含待处理条目的当前结果</small></div>
        </div>
        <el-collapse class="metric-notes"><el-collapse-item title="统计口径说明" name="definition"><p>解析率以全部报表解析请求为分母；“全部报表”结果单列展示。派单成功率以当前持久化条目为分母，包含待处理条目。空数据不代表零故障。比例按全部数据计算，明细最多展示 1000 个分组。耗时的第 95 百分位表示 95% 的样本耗时不超过该值，单位为毫秒。</p></el-collapse-item></el-collapse>
        <div class="section-toolbar"><h3>环节运行明细 <span class="muted">{{ metricRows.length }} 个分组</span></h3><el-input v-model="metricSearch" prefix-icon="el-icon-search" clearable placeholder="搜索环节、报表或结果" class="metric-search" /></div>
        <el-table :data="metricRows.slice((metricPage-1)*20,metricPage*20)" size="small" stripe max-height="460" empty-text="当前范围暂无运行数据">
          <el-table-column label="业务环节" min-width="150"><template slot-scope="s"><business-label :value="s.row.operation" domain="operation" /></template></el-table-column>
          <el-table-column label="报表" min-width="130"><template slot-scope="s"><span :title="s.row.report_id">{{ reportName(s.row.report_id) }}</span></template></el-table-column>
          <el-table-column label="版本" min-width="150"><template slot-scope="s"><span class="version-text" :title="s.row.version">{{ versionText(s.row.version) }}</span></template></el-table-column>
          <el-table-column label="结果" min-width="115"><template slot-scope="s"><business-label :value="s.row.outcome" tag /></template></el-table-column>
          <el-table-column prop="samples" label="样本数" min-width="85" align="right" />
          <el-table-column prop="p95_ms" label="耗时（95% · 毫秒）" min-width="160" align="right" />
        </el-table>
        <el-pagination :current-page.sync="metricPage" :page-size="20" :total="metricRows.length" layout="total, prev, pager, next" />
        <div class="section-toolbar dispatch-section"><div><h3>派单当前状态</h3><span class="muted">按报表、规则版本和处理状态汇总</span></div></div>
        <el-table :data="stats.dispatch" size="small" stripe max-height="360" empty-text="当前范围暂无派单记录">
          <el-table-column label="报表" min-width="140"><template slot-scope="s"><span :title="s.row.report_id">{{ reportName(s.row.report_id) }}</span></template></el-table-column>
          <el-table-column label="规则版本" min-width="180"><template slot-scope="s"><span class="version-text" :title="s.row.rule_version">{{ versionText(s.row.rule_version) }}</span></template></el-table-column>
          <el-table-column label="状态" min-width="130"><template slot-scope="s"><business-label :value="s.row.status" tag /></template></el-table-column>
          <el-table-column prop="items" label="条目数" align="right" min-width="100" />
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="人工工作台" name="workbench">
        <el-alert title="待核对记录先查询网关结果；仅明确失败项可重试。过期或冲突记录请由原用户重新预览。" type="info" :closable="false" />
        <el-table :data="tasks" size="small" max-height="560" stripe><el-table-column prop="id" label="清单编号" min-width="190" show-overflow-tooltip /><el-table-column prop="user_id" label="原用户" /><el-table-column label="状态" min-width="120"><template slot-scope="s"><business-label :value="s.row.status" tag /></template></el-table-column><el-table-column prop="status_reason" label="原因" min-width="180" show-overflow-tooltip /><el-table-column prop="failed_count" label="失败数" width="70" /><el-table-column label="操作" width="220"><template slot-scope="s"><el-button type="text" @click="showItems(s.row)">详情</el-button><el-button v-if="s.row.status==='REVIEW_REQUIRED'" type="text" :disabled="saving" @click="act(s.row,'reconcile')">核对</el-button><el-button v-if="s.row.status==='EXECUTED' && s.row.failed_count>0" type="text" :disabled="saving" @click="act(s.row,'retry-failed')">重试失败项</el-button><el-button v-if="['EXECUTED','EXPIRED','CANCELLED'].includes(s.row.status)" type="text" :disabled="saving" @click="act(s.row,'close')">关闭任务</el-button></template></el-table-column></el-table>
        <el-button size="small" @click="loadTasks()">首页</el-button><el-button size="small" :disabled="!nextCursor" @click="loadTasks(nextCursor)">下一页</el-button>
      </el-tab-pane>
      <el-tab-pane label="灰度与回滚" name="policy">
        <el-alert title="目录灰度用于新报表按用户稳定分流；规则灰度在派单规则页发布指定公司版本，其他公司继续使用通配规则。停用公司版本可退回通配规则，历史版本可回滚。" type="info" :closable="false" />
        <el-select v-model="policyKey" @change="loadPolicy"><el-option label="解析策略" value="resolver" /><el-option v-for="r in catalog" :key="r.reportId" :label="`目录：${r.reportName}`" :value="`catalog:${r.reportId}`" /></el-select>
        <el-form label-width="140px" class="form"><el-form-item label="灰度比例 %"><el-input-number v-model="policyForm.percent" :min="0" :max="100" :precision="0" /></el-form-item><template v-if="policyKey==='resolver'"><el-form-item label="模糊匹配阈值"><el-input-number v-model="policyForm.fuzzyThreshold" :min="0.01" :max="1" :step="0.05" /></el-form-item><el-form-item label="歧义分差"><el-input-number v-model="policyForm.ambiguityMargin" :min="0.01" :max="1" :step="0.05" /></el-form-item></template><el-form-item label="变更原因"><el-input v-model="reason" maxlength="512" /></el-form-item><el-form-item><el-button type="primary" :loading="saving" :disabled="!policyReady" @click="savePolicy">保存版本 {{ policyVersion+1 }}</el-button><el-button @click="$router.push('/rules')">规则公司灰度</el-button></el-form-item></el-form>
        <el-table :data="policyRevisions" size="small"><el-table-column prop="version" label="历史版本" /><el-table-column prop="created_by" label="操作者" /><el-table-column label="时间" min-width="175"><template slot-scope="s"><business-label :value="s.row.created_at" domain="time" /></template></el-table-column><el-table-column label="操作"><template slot-scope="s"><el-button type="text" :disabled="saving || s.row.version===policyVersion" @click="rollbackPolicy(s.row)">回滚</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="解析评估" name="evaluation">
        <p>回归集覆盖口语、简称、歧义、错字和权限缩小场景。示例样本来自演示业务，实际业务样本需经脱敏后补充。</p>
        <el-collapse><el-collapse-item title="维护本租户评估样本" name="samples"><el-input type="textarea" :rows="10" v-model="sampleJson" /><el-input placeholder="变更原因" v-model="sampleReason" maxlength="512" /><el-button type="primary" :loading="saving" @click="saveSamples">保存样本并回归</el-button></el-collapse-item></el-collapse>
        <el-button size="small" @click="loadTab">运行回归</el-button><strong v-if="evaluated"> {{ evaluated.passed }} / {{ evaluated.total }} 通过</strong>
        <el-table :data="evaluated ? evaluated.cases : []" size="small"><el-table-column prop="query" label="表达" /><el-table-column label="期望结果"><template slot-scope="s"><business-label :value="s.row.expected" /></template></el-table-column><el-table-column label="实际结果"><template slot-scope="s"><business-label :value="s.row.actual" /></template></el-table-column><el-table-column label="结果"><template slot-scope="s"><el-tag :type="s.row.passed?'success':'danger'">{{ s.row.passed?'通过':'失败' }}</el-tag></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="数据留存" name="retention">
        <el-alert title="手机号、证件号和邮箱在对话及展示出口脱敏。清理按批次执行；在途、待核对或失败任务保留恢复依据。派单幂等标识和最终状态作为防重记录继续保留。" type="info" :closable="false" />
        <el-form label-width="140px" class="form"><el-form-item v-for="f in retentionFields" :key="f.key" :label="f.label"><el-input-number v-model="retentionForm[f.key]" :min="1" :max="3650" :precision="0" /></el-form-item><el-form-item label="变更原因"><el-input v-model="reason" maxlength="512" /></el-form-item><el-form-item><el-button type="primary" :loading="saving" @click="saveRetention">保存留存策略</el-button></el-form-item></el-form>
        <el-input v-model="conversationId" placeholder="需要删除内容的会话编号" class="inline-input" /><el-button :disabled="saving || !conversationId.trim()" @click="requestErase">申请删除</el-button>
        <el-table :data="erasureRows" size="small"><el-table-column prop="conversation_id" label="会话编号" /><el-table-column label="清理状态"><template slot-scope="s"><business-label :value="s.row.status" tag /></template></el-table-column><el-table-column label="申请时间" min-width="175"><template slot-scope="s"><business-label :value="s.row.requested_at" domain="time" /></template></el-table-column><el-table-column label="完成时间" min-width="175"><template slot-scope="s"><business-label :value="s.row.completed_at" domain="time" /></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="访问审计" name="audit"><el-table :data="audits" size="small" max-height="560" stripe><el-table-column prop="actor_id" label="操作者" /><el-table-column label="操作" min-width="140"><template slot-scope="s"><business-label :value="s.row.action" domain="action" /></template></el-table-column><el-table-column label="对象" min-width="150" show-overflow-tooltip><template slot-scope="s"><business-label :value="s.row.resource_id" domain="resource" /></template></el-table-column><el-table-column label="结果" min-width="100"><template slot-scope="s"><business-label :value="s.row.outcome" tag /></template></el-table-column><el-table-column prop="reason" label="原因" min-width="140" show-overflow-tooltip /><el-table-column label="时间" min-width="175"><template slot-scope="s"><business-label :value="s.row.created_at" domain="time" /></template></el-table-column></el-table><el-button :disabled="audits.length<100" @click="olderAudit">更早记录</el-button></el-tab-pane>
    </el-tabs>
    <el-dialog title="清单条目" :visible.sync="itemsVisible" width="850px"><el-table :data="items" size="small"><el-table-column prop="docNo" label="单据号" /><el-table-column label="状态" min-width="120"><template slot-scope="s"><business-label :value="s.row.status" tag /></template></el-table-column><el-table-column prop="errorCode" label="错误码" /><el-table-column prop="errorMessage" label="原因" /><el-table-column prop="attemptCount" label="尝试次数" /></el-table><el-button :disabled="itemPage<=1" @click="itemPage--; reloadItems()">上一页</el-button><span>第 {{ itemPage }} 页</span><el-button :disabled="items.length<50" @click="itemPage++; reloadItems()">下一页</el-button></el-dialog>
  </el-card>
</template>
<script>
/**
 * 租户管理员运维页；策略修改使用期望版本，异常清单操作必须提供原因。
 * 异步操作无论成功或失败均重新读取实际任务状态，避免页面把未知结果误显示为尚未执行。
 */
import http from '../api/http'
import { fetchCatalog } from '../api/catalog'
import * as api from '../api/operations'
import { displayLabel, reportLabel, versionLabel } from '../utils/presentation'
export default {
  data:()=>({metricSearch:'',metricPage:1,disposed:false,policyReady:false,itemsSequence:0,tasksSequence:0,sampleJson:'[]',sampleVersion:0,sampleReason:'',admin:false,loading:false,saving:false,tab:'metrics',days:7,stats:{samples:[],dispatch:[]},tasks:[],nextCursor:null,catalog:[],policyKey:'resolver',policyForm:{},policyVersion:0,policyRevisions:[],reason:'',evaluated:null,retentionForm:{},retentionVersion:0,erasureRows:[],conversationId:'',audits:[],itemsVisible:false,items:[],itemPage:1,selected:null,retentionFields:[{key:'conversationDays',label:'对话留存天数'},{key:'resultDays',label:'结果留存天数'},{key:'metricDays',label:'指标留存天数'},{key:'auditDays',label:'运营审计天数'}]}),
  watch:{metricSearch(){this.metricPage=1},days(){this.metricPage=1}},
  computed:{
    // 明细筛选和分页只改变展示范围，顶部比例仍使用服务端完整汇总。
    metricRows(){const query=this.metricSearch.trim().toLowerCase();return (this.stats.samples||[]).filter(row=>[displayLabel(row.operation,'operation'),this.reportName(row.report_id),displayLabel(row.outcome),row.operation,row.report_id,row.outcome].join(' ').toLowerCase().includes(query))},
    dispatchRate(){const rows=this.stats.overview ? this.stats.overview.dispatch : [];return this.percent(rows.filter(r=>r.status==='SUCCESS').reduce((n,r)=>n+Number(r.items),0),rows.reduce((n,r)=>n+Number(r.items),0))}},
  created(){this.load()},
  beforeDestroy(){this.disposed=true;this.itemsSequence++;this.tasksSequence++},
  methods:{
    reportName(value){return reportLabel(value,this.catalog)},
    versionText(value){return versionLabel(value)},
    percent(n,d){return d?`${(100*n/d).toFixed(1)}%`:'暂无数据'},
    ratio(outcomes){const rows=(this.stats.overview ? this.stats.overview.requests : []).filter(r=>r.operation==='RESOLVE');return this.percent(rows.filter(r=>outcomes.includes(r.outcome)).reduce((n,r)=>n+Number(r.samples),0),rows.reduce((n,r)=>n+Number(r.samples),0))},
    async load(){this.loading=true;try{this.admin=(await http.get('/auth/me')).admin;if(this.admin){this.catalog=await fetchCatalog(true);await this.loadTab()}}finally{this.loading=false}},
    async loadTab(){if(!this.admin)return;this.loading=true;try{if(this.tab==='metrics'){this.stats=await api.metrics(this.days);this.metricPage=1;}if(this.tab==='workbench')await this.loadTasks();if(this.tab==='policy')await this.loadPolicy();if(this.tab==='evaluation'){this.evaluated=await api.evaluation();const corpus=await api.policy('evaluation');this.sampleJson=JSON.stringify(corpus.payload.samples,null,2);this.sampleVersion=corpus.version;}if(this.tab==='audit')this.audits=await api.auditLog();if(this.tab==='retention'){const p=await api.policy('retention');this.retentionForm={...p.payload};this.retentionVersion=p.version;this.erasureRows=await api.erasures()}}finally{this.loading=false}},
    /** 递增请求序号，只接收最后一次工作台分页结果；管理员权限过滤后的游标由服务端提供。 */
    async loadTasks(after){const seq=++this.tasksSequence;const p=await api.workbench(after);if(this.disposed||seq!==this.tasksSequence)return;this.tasks=p.rows;this.nextCursor=p.nextCursor},
    async showItems(row){this.selected=row;this.itemPage=1;await this.reloadItems();this.itemsVisible=true},async reloadItems(){const seq=++this.itemsSequence;const id=this.selected.id;this.items=[];const rows=await api.workItems(id,this.itemPage);if(this.disposed||seq!==this.itemsSequence||id!==this.selected.id)return;this.items=rows},
    async promptReason(title){try{const r=await this.$prompt('请填写操作原因，执行前将再次校验权限和任务状态。',title,{inputValidator:v=>!!(v&&v.trim())||'请填写原因'});return r.value}catch(e){return null}},
    /** 管理员填写原因后提交持久化任务；传输或任务失败也刷新实际状态，避免重复确认结果不明的操作。 */
    async act(row,action){const reason=await this.promptReason(action==='reconcile'?'核对网关结果':(action==='close'?'关闭异常任务（未派单项不再重试）':'重试明确失败项'));if(!reason||this.disposed)return;this.saving=true;try{await api.act(row.id,action,reason);if(!this.disposed)this.$message.success('处理已完成')}catch(e){/* 提示已由任务或请求层展示，刷新实际持久状态。 */}finally{this.saving=false;if(!this.disposed)await this.loadTasks()}},
    async loadPolicy(){this.policyReady=false;const key=this.policyKey;const p=await api.policy(key);const h=await api.policyHistory(key);if(this.disposed||key!==this.policyKey)return;this.policyReady=true;this.policyForm={...p.payload};this.policyVersion=p.version;this.policyRevisions=h;this.reason=''},
    async savePolicy(){if(!this.policyReady||this.saving||this.disposed)return;if(!this.reason.trim()){this.$message.error('请填写变更原因');return}this.saving=true;try{await api.savePolicy(this.policyKey,{expectedVersion:this.policyVersion,payload:this.policyForm,reason:this.reason});await this.loadPolicy()}finally{this.saving=false}},
    async rollbackPolicy(row){const reason=await this.promptReason(`回滚到版本 ${row.version}`);if(!reason||this.disposed)return;this.saving=true;try{await api.rollbackPolicy(this.policyKey,{targetVersion:row.version,expectedVersion:this.policyVersion,reason});await this.loadPolicy()}finally{this.saving=false}},
    async saveRetention(){if(!this.reason.trim()){this.$message.error('请填写变更原因');return}this.saving=true;try{await api.savePolicy('retention',{expectedVersion:this.retentionVersion,payload:this.retentionForm,reason:this.reason});await this.loadTab()}finally{this.saving=false}},
    async requestErase(){const reason=await this.promptReason('申请删除会话内容');if(!reason||this.disposed)return;this.saving=true;try{await api.erase(this.conversationId.trim(),reason);this.conversationId='';this.erasureRows=await api.erasures()}finally{this.saving=false}},
    async saveSamples(){if(!this.sampleReason.trim()){this.$message.error('请填写样本变更原因');return}let samples;try{samples=JSON.parse(this.sampleJson)}catch(e){this.$message.error('样本不是有效 JSON');return}this.saving=true;try{await api.savePolicy('evaluation',{expectedVersion:this.sampleVersion,payload:{samples},reason:this.sampleReason});await this.loadTab()}finally{this.saving=false}},
    async olderAudit(){this.audits=await api.auditLog(this.audits[this.audits.length-1].id)}
  }
}
</script>
<style scoped>
.section-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; margin: 16px 0; }
h3 { font-size: 15px; font-weight: 600; margin: 0 0 6px; color: #263b54; }
.muted { color: #758397; font-size: 12px; font-weight: 400; }
h3 .muted { margin-left: 8px; }
.cards { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; margin: 20px 0; }
.metric-card { padding: 20px; border: 1px solid #e3eaf3; border-radius: 10px; background: #f8faff; }
.metric-card span { display: flex; align-items: center; gap: 8px; color: #526984; font-size: 13px; }
.metric-card i { color: #5684b9; font-size: 18px; }
.metric-card strong { display: block; font-size: 30px; font-weight: 600; letter-spacing: -.5px; margin: 13px 0 8px; color: #253e60; font-variant-numeric: tabular-nums; }
.metric-card small { font-size: 12px; color: #7b899a; }
.metric-notes { margin: 0 0 26px; border: 0; }
.metric-notes >>> .el-collapse-item__header { height: 36px; color: #6b7e94; border: 0; font-size: 12px; }
.metric-notes >>> .el-collapse-item__wrap { border: 0; }
.metric-notes p { margin: 0; line-height: 1.8; }
.metric-search { max-width: 280px; }
.version-text { color: #758397; font-size: 12px; font-family: Consolas, monospace; }
.dispatch-section { margin-top: 32px; padding-top: 24px; border-top: 1px solid #edf0f5; }
.form { max-width: 650px; margin-top: 24px; }
.inline-input { max-width: 400px; margin: 16px 12px 16px 0; }
.el-alert { margin: 12px 0 20px; }
p { color: #66778b; font-size: 13px; }
.el-table { margin: 16px 0; }
.el-pagination { text-align: right; }
@media (max-width: 1100px) { .cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .cards { gap: 10px; } .metric-card { padding: 14px; } .metric-card strong { font-size: 25px; } }
</style>
