<template>
  <div>
    <el-card shadow="never">
      <div slot="header" class="head page-toolbar">
        <div class="left">
          <h1>派单规则</h1>
          <p>维护各公司派单条件，发布后立即生效</p>
        </div>
        <div class="right page-actions">
          <el-alert v-if="!isAdmin" type="info" :closable="false" show-icon title="当前用户不是管理员，只能查看" class="admin-alert" />
          <el-button type="primary" size="small" icon="el-icon-plus" :disabled="!isAdmin" @click="openEditor()">新建草稿</el-button>
          <el-button size="small" icon="el-icon-refresh" @click="load">刷新</el-button>
        </div>
      </div>

      <el-alert title="规则变更后，原预览需要重新生成再执行。" type="info" show-icon :closable="false" class="rule-note" />
      <el-table v-loading="loading" :data="rules" border stripe size="small">
        <el-table-column label="报表" width="120">
          <template slot-scope="scope">{{ reportLabel(scope.row.reportId) }}</template>
        </el-table-column>
        <el-table-column label="公司" width="80" align="center">
          <template slot-scope="scope">{{ scope.row.companyCode === '*' ? '通配' : scope.row.companyCode }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="60" align="center" />
        <el-table-column label="状态" width="90" align="center">
          <template slot-scope="scope">
            <el-tag size="mini" :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="expression" label="表达式" min-width="200">
          <template slot-scope="scope"><code>{{ scope.row.expression }}</code></template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="更新" width="150">
          <template slot-scope="scope"><business-label :value="scope.row.updatedBy" domain="actor" /><br /><span class="time">{{ formatTime(scope.row.updatedAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template slot-scope="scope">
            <template v-if="scope.row.status === 'draft'">
              <el-button type="text" size="mini" :disabled="!isAdmin" @click="openEditor(scope.row)">编辑</el-button>
              <el-button type="text" size="mini" :disabled="!isAdmin" @click="publish(scope.row)">发布</el-button>
              <el-button type="text" size="mini" class="danger" :disabled="!isAdmin" @click="removeDraft(scope.row)">删除</el-button>
            </template>
            <template v-else-if="scope.row.status === 'published'">
              <el-button type="text" size="mini" :disabled="!isAdmin" @click="openEditor(scope.row, true)">新建版本</el-button>
              <el-button type="text" size="mini" class="danger" :disabled="!isAdmin" @click="disable(scope.row)">停用</el-button>
            </template>
            <template v-else>
              <el-button type="text" size="mini" :disabled="!isAdmin" @click="rollback(scope.row)">回滚到此版本</el-button>
            </template>
            <el-button type="text" size="mini" @click="showHistory(scope.row)">历史</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑器 -->
    <el-dialog :title="editor.id ? '编辑草稿' : '新建规则草稿'" :visible.sync="editor.visible" width="760px" :close-on-click-modal="false">
      <el-form :model="editor" :disabled="editor.saving" label-width="90px" size="small">
        <el-form-item label="报表">
          <el-select v-model="editor.reportId" :disabled="!!editor.id" filterable>
            <el-option v-for="r in catalog" :key="r.reportId" :label="catalogOptionLabel(r)" :value="r.reportId" />
          </el-select>
        </el-form-item>
        <el-form-item label="公司">
          <el-input v-model="editor.companyCode" :disabled="!!editor.id" placeholder="* 表示通配（默认）；填 A / B / C 表示只对该公司生效" style="width: 360px" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="editor.name" placeholder="留空则自动生成" style="width: 360px" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="editor.description" placeholder="给业务人员和助手看的规则说明，例如：报销单金额大于 1000 元需要派单" />
        </el-form-item>
        <el-form-item label="表达式">
          <el-input v-model="editor.expression" type="textarea" :rows="3" placeholder="例如：amount > 1000 && include(seq.list('差旅费','市场推广费'), expenseType)" />
          <div class="fields">
            <span class="fields-title">可用字段（点击插入）：</span>
            <span v-if="fieldsState.loading">正在读取字段…</span>
            <span v-if="fieldsState.error">{{ fieldsState.error }} <el-button type="text" size="mini" @click="loadFields">重新读取</el-button></span>
            <el-tag v-for="f in fields" :key="f.name" size="mini" class="field" :title="f.name" @click="insertField(f.name)">
              {{ f.description || f.name }} <span class="ftype"><business-label :value="f.type" domain="fieldType" /></span>
            </el-tag>
          </div>
          <div class="fields-help">语法：Aviator 表达式。比较 &gt; &lt; == !=，逻辑 &amp;&amp; || !，函数 include(seq.list(...), x)、string.startsWith(x, '前缀')、字符串用单引号。</div>
        </el-form-item>
        <el-form-item label="试算">
          <el-button size="mini" :loading="validation.loading" @click="validate">校验语法</el-button>
          <el-button size="mini" type="warning" :loading="dryRun.loading" @click="runDryRun">试算命中</el-button>
          <span v-if="dryRun.result" class="dry-result">
            范围内 {{ dryRun.result.total }} 条未派单记录，命中 {{ dryRun.result.hitCount }} 条
          </span>
          <div v-if="dryRun.result && dryRun.result.errorCount" class="dry-error">
            {{ dryRun.result.errorCount }} 条记录求值出错，已按不命中处理。例如 {{ dryRun.result.errorSample }}
          </div>
          <el-alert v-if="validation.message" :title="validation.message" :type="validation.error ? 'error' : 'success'" :closable="false" show-icon />
        </el-form-item>
        <el-table v-if="dryRun.result && dryRun.result.samples.length" :data="dryRun.result.samples" size="mini" border max-height="200">
          <el-table-column prop="docNo" label="单据号" width="130" />
          <el-table-column prop="label" label="摘要" min-width="140" />
          <el-table-column prop="companyCode" label="公司" width="60" align="center" />
          <el-table-column prop="amount" label="金额" min-width="260" align="right" class-name="amount-column" show-overflow-tooltip />
          <el-table-column prop="date" label="日期" width="105" />
        </el-table>
      </el-form>
      <div slot="footer">
        <el-button size="small" @click="editor.visible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="editor.saving" @click="saveDraftForm">保存草稿</el-button>
      </div>
    </el-dialog>

    <!-- 历史 -->
    <el-drawer :visible.sync="history.visible" :title="history.title" size="45%">
      <div v-if="history.error" class="history-error" role="alert">
        <el-alert :title="history.error" type="error" :closable="false" show-icon />
        <el-button size="small" @click="showHistory(history.target)">重新读取历史</el-button>
      </div>
      <el-table v-loading="history.loading" :data="history.list" size="mini" border
        :empty-text="history.error ? '历史暂不可用，请重新读取' : history.loading ? '正在读取规则历史…' : '当前范围暂无规则变更历史'">
        <el-table-column prop="version" label="版本" width="60" align="center" />
        <el-table-column label="动作" width="80">
          <template slot-scope="scope">{{ actionLabel(scope.row.action) }}</template>
        </el-table-column>
        <el-table-column prop="expression" label="表达式" min-width="160">
          <template slot-scope="scope"><code>{{ scope.row.expression }}</code></template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
        <el-table-column label="操作人" width="100"><template slot-scope="scope"><business-label :value="scope.row.operatedBy" domain="actor" /></template></el-table-column>
        <el-table-column label="时间" width="140">
          <template slot-scope="scope">{{ formatTime(scope.row.operatedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script>
/**
 * 规则维护页：编辑、校验、试算后发布，规则变更与历史记录由服务端事务管理。
 * 字段、语法校验和试算共用编辑会话版本；修改输入、换规则、关窗或销毁后，旧响应不得成为当前规则的依据。
 * 历史窗口单独冻结报表、公司与请求代次，标题和记录始终来自同一范围，读取不执行规则变更。
 */
import {
  deleteDraft,
  disableRule,
  dryRunRule,
  fetchRuleFields,
  fetchRuleHistory,
  fetchRules,
  publishRule,
  rollbackRule,
  saveDraft,
  validateRule
} from '../api/rule'
import { fetchCatalog } from '../api/catalog'
import http from '../api/http'

const STATUS_LABELS = { DRAFT: '草稿', DISABLED: '已停用' }

export default {
  name: 'RuleAdmin',
  data() {
    return {
      loading: false,
      rules: [],
      isAdmin: false,
      // 报表目录：报表名称、可选报表都来自目录，新增报表后这里自动出现，不需要改页面
      catalog: [],
      fields: [],
      fieldsState: { loading: false, request: 0, inputKey: null, error: '' },
      editorVersion: 0,
      editor: { visible: false, saving: false, id: null, reportId: '', companyCode: '*', name: '', description: '', expression: '' },
      dryRun: { loading: false, result: null, request: 0, inputKey: null },
      validation: { loading: false, request: 0, inputKey: null, message: '', error: false },
      disposed: false,
      // 历史标题和列表共用范围快照；request 区分相同范围的多次打开，失败保留 target 供原范围重试。
      history: { visible: false, title: '', list: [], target: null, request: 0, loading: false, error: '' }
    }
  },
  created() {
    this.load()
    http.get('/auth/me')
      .then((me) => {
        this.isAdmin = !!me.admin
        return this.loadCatalog()
      })
      .catch(() => (this.isAdmin = false))
  },
  computed: {
    dryRunInputKey() {
      return JSON.stringify(this.payload())
    },
    fieldsInputKey() {
      return JSON.stringify([this.editorVersion, this.editor.reportId])
    }
  },
  watch: {
    dryRunInputKey(value) {
      if (value !== this.dryRun.inputKey) this.invalidateDryRun()
      if (value !== this.validation.inputKey) this.invalidateValidation()
    },
    fieldsInputKey(value) {
      if (!this.disposed && this.editor.visible && value !== this.fieldsState.inputKey) this.loadFields()
    },
    'editor.visible'(visible) {
      if (!visible) this.invalidateEditor()
    },
    'history.visible'(visible) {
      if (!visible) this.clearHistory()
    }
  },
  beforeDestroy() {
    this.disposed = true
    this.invalidateEditor()
    this.clearHistory()
  },
  methods: {
    /** 编辑器重开或关闭是一段新生命周期；即使报表和表达式相同，旧请求仍不得进入新窗口。 */
    invalidateEditor() {
      this.editorVersion++
      this.invalidateDryRun()
      this.invalidateValidation()
      this.fieldsState.request++
      this.fieldsState.loading=false
      this.fieldsState.inputKey=null
      this.fieldsState.error=''
      this.fields=[]
    },
    editorSnapshot() {
      return { version:this.editorVersion, reportId:this.editor.reportId, inputKey:this.dryRunInputKey }
    },
    /** 字段只依赖报表，语法和试算依赖完整业务输入；两者共享相同的编辑生命周期边界。 */
    currentEditor(snapshot, fieldsOnly=false) {
      return !this.disposed && this.editor.visible && snapshot.version===this.editorVersion
        && snapshot.reportId===this.editor.reportId && (fieldsOnly || snapshot.inputKey===this.dryRunInputKey)
    },
    invalidateValidation() {
      this.validation.request++
      this.validation.loading=false
      this.validation.inputKey=null
      this.validation.message=''
      this.validation.error=false
    },
    invalidateDryRun() {
      this.dryRun.request++
      this.dryRun.result = null
      this.dryRun.loading = false
      this.dryRun.inputKey = null
    },
    async load() {
      this.loading = true
      try {
        this.rules = await fetchRules()
      } finally {
        this.loading = false
      }
    },
    async loadCatalog() {
      try {
        this.catalog = await fetchCatalog(this.isAdmin)
      } catch (e) {
        this.catalog = []
      }
    },
    reportLabel(reportId) {
      const r = this.catalog.find((c) => c.reportId === reportId)
      return r ? r.reportName : reportId
    },
    catalogOptionLabel(r) {
      const status = STATUS_LABELS[r.status]
      return status ? `${r.reportName}（${status}）` : r.reportName
    },
    statusType(s) {
      return { published: 'success', draft: 'warning', disabled: 'info' }[s] || 'info'
    },
    statusLabel(s) {
      return { published: '生效中', draft: '草稿', disabled: '已停用' }[s] || s
    },
    actionLabel(a) {
      return { publish: '发布', disable: '停用', rollback: '回滚' }[a] || a
    },
    formatTime(t) {
      return t ? String(t).replace('T', ' ').slice(0, 16) : ''
    },
    async loadFields() {
      if (this.disposed || !this.editor.visible) return
      const snapshot=this.editorSnapshot(), request=++this.fieldsState.request
      const current=()=>this.currentEditor(snapshot,true) && request===this.fieldsState.request
      this.fields=[]
      this.fieldsState.loading=true
      this.fieldsState.inputKey=this.fieldsInputKey
      this.fieldsState.error=''
      try {
        const fields=snapshot.reportId ? await fetchRuleFields(snapshot.reportId) : []
        if (current()) this.fields=fields
      } catch (e) {
        if (current()) this.fieldsState.error='字段读取失败。'
      } finally { if (current()) this.fieldsState.loading=false }
    },
    insertField(name) {
      if (this.disposed || !this.editor.visible || this.editor.saving || this.fieldsState.loading
          || this.fieldsState.inputKey!==this.fieldsInputKey || !this.fields.some(field=>field.name===name)) return
      this.editor.expression = (this.editor.expression || '') + (this.editor.expression ? ' ' : '') + name
    },
    openEditor(row, asNewVersion) {
      if (this.disposed) return
      this.invalidateEditor()
      if (row) {
        this.editor = {
          visible: true,
          saving: false,
          id: asNewVersion ? null : row.id,
          reportId: row.reportId,
          companyCode: row.companyCode,
          name: asNewVersion ? '' : row.name,
          description: row.description,
          expression: row.expression
        }
      } else {
        const first = this.catalog.find((c) => c.status !== 'DISABLED') || this.catalog[0]
        this.editor = { visible: true, saving: false, id: null, reportId: first ? first.reportId : '', companyCode: '*', name: '', description: '', expression: '' }
      }
      this.loadFields()
    },
    payload() {
      return { reportId: this.editor.reportId, companyCode: this.editor.companyCode, expression: this.editor.expression }
    },
    async validate() {
      if (this.disposed || !this.editor.visible || this.editor.saving) return
      const snapshot=this.editorSnapshot(), request=++this.validation.request
      const current=()=>this.currentEditor(snapshot) && request===this.validation.request
      this.validation.loading=true
      this.validation.inputKey=snapshot.inputKey
      this.validation.message=''
      this.validation.error=false
      try {
        const result=await validateRule(this.payload())
        if (!current()) return
        this.validation.message=`语法正确，引用字段：${(result.variables || []).join(', ') || '无'}`
      } catch (e) {
        if (current()) {
          this.validation.error=true
          this.validation.message='语法校验未完成，请检查表达式或服务连接后重试。'
        }
      } finally { if (current()) this.validation.loading=false }
    },
    async runDryRun() {
      if (this.disposed || !this.editor.visible) return
      const request = ++this.dryRun.request
      const snapshot = this.editorSnapshot()
      const payload = this.payload()
      const inputKey = JSON.stringify(payload)
      const current = () => this.currentEditor(snapshot) && request === this.dryRun.request
      this.dryRun.inputKey = inputKey
      this.dryRun.result = null
      this.dryRun.loading = true
      try {
        const result = await dryRunRule(payload)
        if (current()) this.dryRun.result = result
      } catch (e) {
        // 请求错误由公共 HTTP 层显示；旧试算结果不能成为当前输入的校验依据。
        if (current()) this.dryRun.result = null
      } finally {
        if (request === this.dryRun.request) this.dryRun.loading = false
      }
    },
    async saveDraftForm() {
      if (this.disposed || !this.editor.visible || this.editor.saving) return
      const target=this.editor, version=this.editorVersion
      target.saving = true
      try {
        await saveDraft({
          id: this.editor.id,
          reportId: this.editor.reportId,
          companyCode: this.editor.companyCode,
          name: this.editor.name,
          description: this.editor.description,
          expression: this.editor.expression
        })
        if (!this.disposed && version===this.editorVersion && target===this.editor && target.visible) {
          this.$message.success('草稿已保存，发布后生效')
          target.visible = false
        }
        if (!this.disposed) this.load()
      } finally {
        target.saving = false
      }
    },
    async publish(row) {
      try {
        await this.$confirm(`发布后立即生效，同范围当前生效的版本会自动停用。确认发布 v${row.version}？`, '发布规则', { type: 'warning' })
      } catch (e) {
        return
      }
      await publishRule(row.id)
      this.$message.success('已发布并刷新规则缓存')
      this.load()
    },
    async disable(row) {
      try {
        await this.$confirm('停用后该范围没有生效规则，将不会产生派单候选。确认停用？', '停用规则', { type: 'warning' })
      } catch (e) {
        return
      }
      await disableRule(row.id)
      this.load()
    },
    async rollback(row) {
      try {
        await this.$confirm(`将以 v${row.version} 的表达式创建新版本并立即发布。确认回滚？`, '回滚规则', { type: 'warning' })
      } catch (e) {
        return
      }
      await rollbackRule(row.id)
      this.$message.success('已回滚')
      this.load()
    },
    async removeDraft(row) {
      try {
        await this.$confirm('确认删除该草稿？', '提示', { type: 'warning' })
      } catch (e) {
        return
      }
      await deleteDraft(row.id)
      this.load()
    },
    /** 关闭或离开页面立即作废所有历史请求；迟到结果不得重新打开抽屉或保留旧范围的记录。 */
    clearHistory() {
      this.history.request++
      this.history.visible = false
      this.history.title = ''
      this.history.target = null
      this.history.list = []
      this.history.loading = false
      this.history.error = ''
    },
    /** 只允许当前窗口最新请求回写；目标快照不跟随原表格行、目录名称或其他编辑窗口变化。 */
    currentHistory(request, target) {
      return !this.disposed && this.history.visible && request === this.history.request && target === this.history.target
    },
    /**
     * 读取所选规则范围的变更历史；服务端复核当前报表与公司权限，失败后可对同一快照重试。
     * @param {Object} row 含 reportId 和 companyCode 的规则行或上次读取的范围快照。
     * @returns {Promise<void>} 读取完成后仅更新仍有效的窗口，不发布、回滚或修改规则。
     */
    async showHistory(row) {
      if (this.disposed || !row) return
      const target = { reportId: row.reportId, companyCode: row.companyCode }
      const request = ++this.history.request
      this.history.target = target
      this.history.title = `${this.reportLabel(target.reportId)} / ${target.companyCode === '*' ? '通配' : target.companyCode} 变更历史`
      this.history.list = []
      this.history.error = ''
      this.history.loading = true
      this.history.visible = true
      try {
        const list = await fetchRuleHistory(target.reportId, target.companyCode)
        if (this.currentHistory(request, target)) this.history.list = list
      } catch (e) {
        if (this.currentHistory(request, target)) this.history.error = '规则历史读取失败，请重新读取。'
      } finally {
        if (this.currentHistory(request, target)) this.history.loading = false
      }
    }
  }
}
</script>

<style scoped>
.head { flex-wrap: wrap; gap: 16px; }
.rule-note { margin-bottom: 18px; }
.head .left { flex: 1; min-width: 230px; }
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head .title {
  font-size: 16px;
  font-weight: 600;
  margin-right: 12px;
}

.head .desc {
  font-size: 12px;
  color: #909399;
}

.head .right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-alert {
  width: 260px;
  padding: 4px 8px;
}

.time {
  font-size: 11px;
  color: #909399;
}

.danger {
  color: #f56c6c;
}

code {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  background: #f5f7fa;
  padding: 1px 4px;
  border-radius: 3px;
}

.fields {
  margin-top: 6px;
  font-size: 12px;
}

.fields-title {
  color: #909399;
}

.field {
  margin: 0 4px 4px 0;
  cursor: pointer;
}

.ftype {
  color: #909399;
}

.fields-help {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.dry-result {
  margin-left: 12px;
  font-size: 12px;
  color: #e6a23c;
}

.dry-error {
  font-size: 12px;
  line-height: 1.6;
  color: #f56c6c;
}

.history-error {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 16px 16px;
}
</style>
