<template>
  <el-card shadow="never" v-loading="loading">
    <div slot="header" class="page-toolbar"><div><h1>报表目录</h1><p>维护报表定义、负责人和业务别名</p></div><div class="page-actions"><el-button size="small" type="primary" icon="el-icon-plus" :disabled="!admin" @click="edit()">新增报表</el-button><el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button></div></div>
    <el-alert v-if="!admin" title="仅管理员可维护目录" type="info" :closable="false" />
    <template v-else>
      <el-input v-model="search" placeholder="搜索名称、编码或负责人" prefix-icon="el-icon-search" clearable class="search" />
      <el-table :data="filtered.slice((page-1)*20,page*20)" border size="small">
        <el-table-column prop="reportName" label="报表" min-width="150" /><el-table-column prop="reportCode" label="编码" /><el-table-column label="负责人"><template slot-scope="s">{{ s.row.ownerUserId || '未指定' }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template slot-scope="s"><business-label :value="s.row.status" tag /></template></el-table-column><el-table-column prop="catalogVersion" label="版本" width="65" />
        <el-table-column label="操作" width="300"><template slot-scope="s">
          <el-button type="text" @click="edit(s.row)">编辑</el-button><el-button type="text" @click="aliases(s.row)">别名</el-button><el-button type="text" @click="history(s.row)">版本回滚</el-button>
          <el-button type="text" v-if="s.row.status !== 'PUBLISHED'" @click="changeStatus(s.row,true)">发布</el-button><el-button type="text" v-else @click="changeStatus(s.row,false)">停用</el-button>
        </template></el-table-column>
      </el-table>
      <el-pagination :current-page.sync="page" :page-size="20" :total="filtered.length" layout="total, prev, pager, next" />
    </template>
    <el-dialog :title="editing ? '编辑报表' : '新增报表'" :visible.sync="editorVisible" width="720px" :close-on-click-modal="false">
      <el-alert v-if="form.status === 'PUBLISHED'" title="已发布目录修改后立即生效，旧预览需要重新查询。新报表可先保存草稿，再到运营治理设置灰度比例后发布。" type="warning" :closable="false" />
      <el-form label-width="100px" size="small">
        <el-form-item label="稳定标识"><el-input v-model="form.reportId" :disabled="!!editing" placeholder="可留空自动生成" /></el-form-item>
        <el-form-item v-for="field in fields" :key="field.key" :label="field.label"><el-input v-model="form[field.key]" /></el-form-item>
        <el-form-item label="查询方式"><el-select v-model="form.queryMode"><el-option label="标准报表" value="STANDARD" /><el-option label="专用适配器" value="ADAPTER" /></el-select></el-form-item>
        <el-form-item label="查询配置"><el-input v-model="config" type="textarea" :rows="7" placeholder="填写字段映射配置（JSON）" /></el-form-item>
        <el-form-item label="允许派单"><el-switch v-model="form.dispatchEnabled" /></el-form-item>
        <el-form-item label="配置诊断" v-if="form.configError"><el-alert :title="form.configError" type="error" :closable="false" /></el-form-item>
      </el-form>
      <span slot="footer"><el-button @click="editorVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></span>
    </el-dialog>
    <el-dialog title="业务别名" :visible.sync="aliasVisible" width="680px">
      <el-form inline size="small"><el-form-item><el-input v-model="alias.alias" placeholder="简称、历史名称或常见错字" /></el-form-item><el-form-item><el-select v-model="alias.aliasType"><el-option v-for="t in aliasTypes" :key="t.value" :label="t.label" :value="t.value" /></el-select></el-form-item><el-button type="primary" :loading="saving" @click="saveAlias">添加</el-button></el-form>
      <el-table :data="selected ? selected.aliases : []" size="small"><el-table-column prop="alias" label="别名" /><el-table-column label="状态"><template slot-scope="s"><business-label :value="s.row.status" tag /></template></el-table-column><el-table-column label="操作"><template slot-scope="s"><el-button v-if="s.row.status === 'ACTIVE'" type="text" :disabled="saving" @click="removeAlias(s.row)">停用</el-button></template></el-table-column></el-table>
    </el-dialog>
    <el-dialog :title="historyTitle" :visible.sync="historyVisible" width="620px">
      <el-alert v-if="historyError" :title="historyError" type="error" :closable="false" show-icon />
      <el-button v-if="historyError" size="small" @click="history(historyTarget)">重新读取历史</el-button>
      <el-table v-loading="historyLoading" :data="revisions" :empty-text="historyError ? '历史暂不可用' : '暂无历史版本'">
        <el-table-column prop="catalog_version" label="版本" />
        <el-table-column prop="created_by" label="操作者" />
        <el-table-column label="时间" min-width="175"><template slot-scope="s"><business-label :value="s.row.created_at" domain="time" /></template></el-table-column>
        <el-table-column label="操作"><template slot-scope="s">
          <el-button type="text" :disabled="saving || rollbackBusy || historyLoading || !!historyError || !historyTarget || s.row.catalog_version === historyTarget.catalogVersion" @click="rollback(s.row)">回滚</el-button>
        </template></el-table-column>
      </el-table>
    </el-dialog>
  </el-card>
</template>
<script>
/**
 * 报表目录及别名维护页；保留稳定标识并提交当前版本，配置合法性及数据权限由服务端校验。
 * 历史弹窗使用独立报表快照和请求代次，回滚只能针对用户实际看过的该报表历史，不能跟随其他弹窗目标变化。
 */
import http from '../api/http'
import * as api from '../api/catalog'
import * as ops from '../api/operations'
export default {
  data: () => ({ disposed: false, admin: false, loading: false, saving: false, rows: [], search: '', page: 1, editing: null, form: {}, config: '{}', editorVisible: false, aliasVisible: false, historyVisible: false, selected: null,
    // 历史与别名各自绑定目标；历史中的目录版本为用户打开时的期望版本，不能在确认期间自动提升。
    historyTarget: null, historyRequest: 0, historyLoading: false, historyError: '', rollbackBusy: false, revisions: [], alias: { alias: '', aliasType: 'COLLOQUIAL' },
    fields: [{ key: 'reportCode', label: '报表编码' }, { key: 'reportName', label: '名称' }, { key: 'domainCode', label: '业务域' }, { key: 'ownerUserId', label: '负责人' }, { key: 'permissionCode', label: '权限码' }, { key: 'description', label: '说明' }],
    aliasTypes: [{ value: 'SHORT', label: '简称' }, { value: 'COLLOQUIAL', label: '口语' }, { value: 'ENGLISH', label: '英文名' }, { value: 'HISTORICAL', label: '历史名称' }, { value: 'DEPARTMENT', label: '部门俗称' }, { value: 'TYPO', label: '常见错字' }] }),
  computed: {
    filtered() { return this.rows.filter(r => [r.reportName,r.reportCode,r.ownerUserId].join(' ').toLowerCase().includes(this.search.toLowerCase())) },
    historyTitle() { return this.historyTarget ? `${this.historyTarget.reportName} · 目录历史版本` : '目录历史版本' }
  },
  watch: { search() { this.page=1 }, historyVisible(visible) { if (!visible) this.clearHistory() } },
  created() { this.load() },
  beforeDestroy() { this.disposed=true; this.clearHistory() },
  methods: {
    async load() { this.loading=true; try { this.admin=(await http.get('/auth/me')).admin; this.rows=this.admin ? await api.fetchCatalog(true) : []; if(this.selected) this.selected=this.rows.find(r=>r.reportId===this.selected.reportId)||null } finally { this.loading=false } },
    edit(row) { this.editing=row ? row.reportId : null; this.form=row ? JSON.parse(JSON.stringify(row)) : { reportId:'',reportCode:'',reportName:'',domainCode:'',ownerUserId:'',permissionCode:'',description:'',queryMode:'STANDARD',dispatchEnabled:false }; this.config=JSON.stringify(row ? row.queryConfig : {},null,2); this.editorVisible=true },
    async save() { let config; try { config=JSON.parse(this.config) } catch(e) { this.$message.error('查询配置不是有效 JSON'); return } this.saving=true; try { await api.saveCatalog(this.editing,{ ...this.form, expectedVersion:this.form.catalogVersion, queryConfig:config }); this.editorVisible=false; await this.load() } finally { this.saving=false } },
    async changeStatus(row,publish) { try { await this.$confirm(`${publish?'发布':'停用'}「${row.reportName}」后，旧预览需要重新查询。是否继续？`,'目录变更'); } catch(e) { return } if(this.disposed)return; this.loading=true; try { await (publish?api.publishCatalog:api.disableCatalog)(row.reportId); await this.load() } finally { this.loading=false } },
    aliases(row) { this.selected=row; this.alias={alias:'',aliasType:'COLLOQUIAL'}; this.aliasVisible=true },
    async saveAlias() { if(!this.selected) return; this.saving=true; try { await api.addAlias(this.selected.reportId,this.alias); this.alias.alias=''; await this.load() } finally { this.saving=false } },
    async removeAlias(row) { this.saving=true; try { await api.disableAlias(this.selected.reportId,row.id); await this.load() } finally { this.saving=false } },
    /** 关窗或销毁立即使旧历史失效；不得保留另一张报表可操作的历史条目。 */
    clearHistory() {
      this.historyRequest++
      this.historyTarget=null
      this.revisions=[]
      this.historyLoading=false
      this.historyError=''
    },
    currentHistory(request, target) {
      return !this.disposed && this.historyVisible && request===this.historyRequest && target===this.historyTarget
    },
    /** 打开即冻结报表及期望版本；成功、失败和 loading 都只由最新请求更新。 */
    async history(row) {
      if (this.disposed || !row) return
      const target={reportId:row.reportId,reportName:row.reportName,catalogVersion:row.catalogVersion}
      const request=++this.historyRequest
      this.historyTarget=target
      this.revisions=[]
      this.historyError=''
      this.historyVisible=true
      this.historyLoading=true
      try {
        const revisions=await ops.catalogHistory(target.reportId)
        if (this.currentHistory(request,target)) this.revisions=revisions
      } catch (e) {
        if (this.currentHistory(request,target)) this.historyError='目录历史读取失败，请重新读取后再选择版本。'
      } finally {
        if (this.currentHistory(request,target)) this.historyLoading=false
      }
    },
    /** 回滚提交使用展示历史时的目标和版本；提示框期间切换、关窗或销毁后不得发起变更。 */
    async rollback(row) {
      const target=this.historyTarget, request=this.historyRequest
      if (!this.currentHistory(request,target) || !target || this.saving || this.rollbackBusy || this.historyLoading || this.historyError
          || !this.revisions.includes(row) || row.catalog_version===target.catalogVersion) return
      const targetVersion=row.catalog_version
      this.rollbackBusy=true
      try {
        let answer
        try {
          answer=await this.$prompt(`将「${target.reportName}」回滚到版本 ${targetVersion}。请填写原因；回滚会生成新版本，并使旧预览失效。`, '回滚目录', {
            inputValidator:v=>!!(v&&v.trim())||'请填写原因'
          })
        } catch (e) { return }
        if (!this.currentHistory(request,target) || !this.revisions.includes(row)) return
        await ops.rollbackCatalog(target.reportId,{targetVersion,expectedVersion:target.catalogVersion,reason:answer.value})
        if (this.currentHistory(request,target)) this.historyVisible=false
        if (!this.disposed) await this.load()
      } finally { this.rollbackBusy=false }
    }
  }
}
</script>
<style scoped>.toolbar{display:flex;flex-wrap:wrap;gap:12px;align-items:center}.toolbar span{flex:1;color:#606266}.search{max-width:400px;margin-bottom:16px}.el-pagination{margin-top:16px}.el-card >>> .el-dialog{max-width:calc(100vw - 32px)}
</style>
