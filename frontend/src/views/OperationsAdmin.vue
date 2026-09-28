<template>
  <el-card shadow="never" v-loading="loading">
    <div slot="header" class="toolbar"><strong>运营治理</strong><span>指标、异常处理、发布策略与数据留存</span><el-button size="small" @click="load">刷新</el-button></div>
    <el-alert v-if="!admin" title="仅管理员可访问运营工作台" type="info" :closable="false" />
    <el-tabs v-else v-model="tab" @tab-click="loadTab">
      <el-tab-pane label="业务指标" name="metrics">
        <el-select v-model="days" size="small" @change="loadTab"><el-option v-for="d in [1,7,30,90]" :key="d" :value="d" :label="`最近 ${d} 天`" /></el-select>
        <div class="cards"><div>解析唯一命中率<strong>{{ ratio(['EXACT','ALIAS','FUZZY']) }}</strong></div><div>歧义率<strong>{{ ratio(['AMBIGUOUS']) }}</strong></div><div>无匹配率<strong>{{ ratio(['NONE']) }}</strong></div><div>派单条目成功率<strong>{{ dispatchRate }}</strong></div></div>
        <p>解析率以 RESOLVE 请求为分母（ALL 单列）；派单成功率以当前持久化条目为分母，包含待处理条目。空数据不按零故障解释。比例按全部数据计算，明细最多展示 1000 个分组。</p>
        <el-table :data="stats.samples" size="small" border><el-table-column prop="operation" label="环节" /><el-table-column prop="report_id" label="报表" /><el-table-column prop="version" label="版本" show-overflow-tooltip /><el-table-column prop="outcome" label="结果" /><el-table-column prop="samples" label="样本数" /><el-table-column prop="p95_ms" label="P95 毫秒" /></el-table>
        <h4>派单当前状态</h4><el-table :data="stats.dispatch" size="small" border><el-table-column prop="report_id" label="报表" /><el-table-column prop="rule_version" label="规则版本" show-overflow-tooltip /><el-table-column prop="status" label="状态" /><el-table-column prop="items" label="条目数" /></el-table>
      </el-tab-pane>
      <el-tab-pane label="人工工作台" name="workbench">
        <el-alert title="待核对记录先查询网关结果；仅明确失败项可重试。过期或冲突记录请由原用户重新预览。" type="info" :closable="false" />
        <el-table :data="tasks" size="small" border><el-table-column prop="id" label="清单编号" min-width="190" /><el-table-column prop="user_id" label="原用户" /><el-table-column prop="status" label="状态" /><el-table-column prop="status_reason" label="原因" /><el-table-column prop="failed_count" label="失败数" width="70" /><el-table-column label="操作" width="220"><template slot-scope="s"><el-button type="text" @click="showItems(s.row)">详情</el-button><el-button v-if="s.row.status==='REVIEW_REQUIRED'" type="text" :disabled="saving" @click="act(s.row,'reconcile')">核对</el-button><el-button v-if="s.row.status==='EXECUTED' && s.row.failed_count>0" type="text" :disabled="saving" @click="act(s.row,'retry-failed')">重试失败项</el-button><el-button v-if="['EXECUTED','EXPIRED','CANCELLED'].includes(s.row.status)" type="text" :disabled="saving" @click="act(s.row,'close')">关闭任务</el-button></template></el-table-column></el-table>
        <el-button size="small" @click="loadTasks()">首页</el-button><el-button size="small" :disabled="!nextCursor" @click="loadTasks(nextCursor)">下一页</el-button>
      </el-tab-pane>
      <el-tab-pane label="灰度与回滚" name="policy">
        <el-alert title="目录灰度用于新报表按用户稳定分流；规则灰度在派单规则页发布指定公司版本，其他公司继续使用通配规则。停用公司版本可退回通配规则，历史版本可回滚。" type="info" :closable="false" />
        <el-select v-model="policyKey" @change="loadPolicy"><el-option label="解析策略" value="resolver" /><el-option v-for="r in catalog" :key="r.reportId" :label="`目录：${r.reportName}`" :value="`catalog:${r.reportId}`" /></el-select>
        <el-form label-width="140px" class="form"><el-form-item label="灰度比例 %"><el-input-number v-model="policyForm.percent" :min="0" :max="100" :precision="0" /></el-form-item><template v-if="policyKey==='resolver'"><el-form-item label="模糊匹配阈值"><el-input-number v-model="policyForm.fuzzyThreshold" :min="0.01" :max="1" :step="0.05" /></el-form-item><el-form-item label="歧义分差"><el-input-number v-model="policyForm.ambiguityMargin" :min="0.01" :max="1" :step="0.05" /></el-form-item></template><el-form-item label="变更原因"><el-input v-model="reason" maxlength="512" /></el-form-item><el-form-item><el-button type="primary" :loading="saving" :disabled="!policyReady" @click="savePolicy">保存版本 {{ policyVersion+1 }}</el-button><el-button @click="$router.push('/rules')">规则公司灰度</el-button></el-form-item></el-form>
        <el-table :data="policyRevisions" size="small"><el-table-column prop="version" label="历史版本" /><el-table-column prop="created_by" label="操作者" /><el-table-column prop="created_at" label="时间" /><el-table-column label="操作"><template slot-scope="s"><el-button type="text" :disabled="saving || s.row.version===policyVersion" @click="rollbackPolicy(s.row)">回滚</el-button></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="解析评估" name="evaluation">
        <p>回归集覆盖口语、简称、歧义、错字和权限缩小场景。示例样本来自演示业务，实际业务样本需经脱敏后补充。</p>
        <el-collapse><el-collapse-item title="维护本租户评估样本" name="samples"><el-input type="textarea" :rows="10" v-model="sampleJson" /><el-input placeholder="变更原因" v-model="sampleReason" maxlength="512" /><el-button type="primary" :loading="saving" @click="saveSamples">保存样本并回归</el-button></el-collapse-item></el-collapse>
        <el-button size="small" @click="loadTab">运行回归</el-button><strong v-if="evaluated"> {{ evaluated.passed }} / {{ evaluated.total }} 通过</strong>
        <el-table :data="evaluated ? evaluated.cases : []" size="small"><el-table-column prop="query" label="表达" /><el-table-column prop="expected" label="期望" /><el-table-column prop="actual" label="实际" /><el-table-column label="结果"><template slot-scope="s"><el-tag :type="s.row.passed?'success':'danger'">{{ s.row.passed?'通过':'失败' }}</el-tag></template></el-table-column></el-table>
      </el-tab-pane>
      <el-tab-pane label="数据留存" name="retention">
        <el-alert title="手机号、证件号和邮箱在对话及展示出口脱敏。清理按批次执行；在途、待核对或失败任务保留恢复依据。派单幂等标识和最终状态作为防重记录继续保留。" type="info" :closable="false" />
        <el-form label-width="140px" class="form"><el-form-item v-for="f in retentionFields" :key="f.key" :label="f.label"><el-input-number v-model="retentionForm[f.key]" :min="1" :max="3650" :precision="0" /></el-form-item><el-form-item label="变更原因"><el-input v-model="reason" maxlength="512" /></el-form-item><el-form-item><el-button type="primary" :loading="saving" @click="saveRetention">保存留存策略</el-button></el-form-item></el-form>
        <el-input v-model="conversationId" placeholder="需要删除内容的会话编号" class="inline-input" /><el-button :disabled="saving || !conversationId.trim()" @click="requestErase">申请删除</el-button>
        <el-table :data="erasureRows" size="small"><el-table-column prop="conversation_id" label="会话编号" /><el-table-column prop="status" label="清理状态" /><el-table-column prop="requested_at" label="申请时间" /><el-table-column prop="completed_at" label="完成时间" /></el-table>
      </el-tab-pane>
      <el-tab-pane label="访问审计" name="audit"><el-table :data="audits" size="small"><el-table-column prop="actor_id" label="操作者" /><el-table-column prop="action" label="操作" /><el-table-column prop="resource_id" label="对象" show-overflow-tooltip /><el-table-column prop="outcome" label="结果" /><el-table-column prop="reason" label="原因" /><el-table-column prop="created_at" label="时间" /></el-table><el-button :disabled="audits.length<100" @click="olderAudit">更早记录</el-button></el-tab-pane>
    </el-tabs>
    <el-dialog title="清单条目" :visible.sync="itemsVisible" width="850px"><el-table :data="items" size="small"><el-table-column prop="docNo" label="单据号" /><el-table-column prop="status" label="状态" /><el-table-column prop="errorCode" label="错误码" /><el-table-column prop="errorMessage" label="原因" /><el-table-column prop="attemptCount" label="尝试次数" /></el-table><el-button :disabled="itemPage<=1" @click="itemPage--; reloadItems()">上一页</el-button><span>第 {{ itemPage }} 页</span><el-button :disabled="items.length<50" @click="itemPage++; reloadItems()">下一页</el-button></el-dialog>
  </el-card>
</template>
<script>
import http from '../api/http'
import { fetchCatalog } from '../api/catalog'
import * as api from '../api/operations'
export default {
  data:()=>({disposed:false,policyReady:false,itemsSequence:0,tasksSequence:0,sampleJson:'[]',sampleVersion:0,sampleReason:'',admin:false,loading:false,saving:false,tab:'metrics',days:7,stats:{samples:[],dispatch:[]},tasks:[],nextCursor:null,catalog:[],policyKey:'resolver',policyForm:{},policyVersion:0,policyRevisions:[],reason:'',evaluated:null,retentionForm:{},retentionVersion:0,erasureRows:[],conversationId:'',audits:[],itemsVisible:false,items:[],itemPage:1,selected:null,retentionFields:[{key:'conversationDays',label:'对话留存天数'},{key:'resultDays',label:'结果留存天数'},{key:'metricDays',label:'指标留存天数'},{key:'auditDays',label:'运营审计天数'}]}),
  computed:{dispatchRate(){const rows=this.stats.overview ? this.stats.overview.dispatch : [];return this.percent(rows.filter(r=>r.status==='SUCCESS').reduce((n,r)=>n+Number(r.items),0),rows.reduce((n,r)=>n+Number(r.items),0))}},
  created(){this.load()},
  beforeDestroy(){this.disposed=true;this.itemsSequence++;this.tasksSequence++},
  methods:{
    percent(n,d){return d?`${(100*n/d).toFixed(1)}%`:'暂无数据'},
    ratio(outcomes){const rows=(this.stats.overview ? this.stats.overview.requests : []).filter(r=>r.operation==='RESOLVE');return this.percent(rows.filter(r=>outcomes.includes(r.outcome)).reduce((n,r)=>n+Number(r.samples),0),rows.reduce((n,r)=>n+Number(r.samples),0))},
    async load(){this.loading=true;try{this.admin=(await http.get('/auth/me')).admin;if(this.admin){this.catalog=await fetchCatalog(true);await this.loadTab()}}finally{this.loading=false}},
    async loadTab(){if(!this.admin)return;this.loading=true;try{if(this.tab==='metrics')this.stats=await api.metrics(this.days);if(this.tab==='workbench')await this.loadTasks();if(this.tab==='policy')await this.loadPolicy();if(this.tab==='evaluation'){this.evaluated=await api.evaluation();const corpus=await api.policy('evaluation');this.sampleJson=JSON.stringify(corpus.payload.samples,null,2);this.sampleVersion=corpus.version;}if(this.tab==='audit')this.audits=await api.auditLog();if(this.tab==='retention'){const p=await api.policy('retention');this.retentionForm={...p.payload};this.retentionVersion=p.version;this.erasureRows=await api.erasures()}}finally{this.loading=false}},
    async loadTasks(after){const seq=++this.tasksSequence;const p=await api.workbench(after);if(this.disposed||seq!==this.tasksSequence)return;this.tasks=p.rows;this.nextCursor=p.nextCursor},
    async showItems(row){this.selected=row;this.itemPage=1;await this.reloadItems();this.itemsVisible=true},async reloadItems(){const seq=++this.itemsSequence;const id=this.selected.id;this.items=[];const rows=await api.workItems(id,this.itemPage);if(this.disposed||seq!==this.itemsSequence||id!==this.selected.id)return;this.items=rows},
    async promptReason(title){try{const r=await this.$prompt('请填写操作原因，执行前将再次校验权限和任务状态。',title,{inputValidator:v=>!!(v&&v.trim())||'请填写原因'});return r.value}catch(e){return null}},
    async act(row,action){const reason=await this.promptReason(action==='reconcile'?'核对网关结果':(action==='close'?'关闭异常任务（未派单项不再重试）':'重试明确失败项'));if(!reason||this.disposed)return;this.saving=true;try{await api.act(row.id,action,reason);await this.loadTasks();this.$message.success('处理结果已刷新')}finally{this.saving=false}},
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
<style scoped>.toolbar{display:flex;flex-wrap:wrap;gap:12px;align-items:center}.toolbar span{flex:1;color:#606266}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:12px;margin:20px 0}.cards>div{flex:1;background:#f5f7fa;padding:18px}.cards strong{display:block;font-size:26px;margin-top:8px;color:#303133}.form{max-width:650px;margin-top:24px}.inline-input{max-width:400px;margin:16px}.el-alert{margin-bottom:16px}p{color:#606266;font-size:13px}.el-table{margin:16px 0}.el-card >>> .el-dialog{max-width:calc(100vw - 32px)}
</style>
