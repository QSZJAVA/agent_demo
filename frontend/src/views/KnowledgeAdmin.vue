<template>
  <div class="knowledge-admin">
    <div class="intro"><h2>业务知识资料</h2><p>维护有来源、适用范围和生效期的制度。保存草稿后，需明确发布才会用于问答。</p></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="notice" />
    <el-alert v-if="ready && !catalog.admin" title="当前账号没有知识资料维护权限" type="warning" :closable="false" />
    <template v-if="catalog.admin">
      <el-card><div class="scope"><label>公司 <el-select v-model="company" size="small" :disabled="busy" @change="scopeChanged"><el-option v-for="value in catalog.companies" :key="value" :label="value + ' 公司'" :value="value" /></el-select></label><label>报表 <el-select v-model="report" size="small" :disabled="busy" @change="scopeChanged"><el-option v-for="value in catalog.reports" :key="value.reportId" :label="value.reportName" :value="value.reportId" /></el-select></label><el-button size="small" :loading="loading" :disabled="busy" @click="load">刷新</el-button><el-button size="small" type="primary" :disabled="busy" @click="newDocument">新增资料</el-button></div>
        <el-table :data="documents" v-loading="loading" class="document-list" size="small"><el-table-column label="资料" min-width="180"><template slot-scope="scope">{{ scope.row.content.title }}</template></el-table-column><el-table-column label="来源" min-width="140"><template slot-scope="scope">{{ scope.row.content.sourceName }}</template></el-table-column><el-table-column label="状态" min-width="140"><template slot-scope="scope">{{ stateText(scope.row.status) }}<span v-if="scope.row.published_version && scope.row.published_version !== scope.row.row_version"> · 有未发布修改</span></template></el-table-column><el-table-column prop="row_version" label="编辑版本" width="90" /><el-table-column prop="published_version" label="发布版本" width="90" /><el-table-column label="操作" width="220"><template slot-scope="scope"><el-button type="text" :disabled="busy" @click="edit(scope.row)">编辑</el-button><el-button type="text" :disabled="busy" @click="transition(scope.row, true)">发布</el-button><el-button type="text" :disabled="busy || !scope.row.published_version" @click="transition(scope.row, false)">停用</el-button><el-button type="text" :disabled="busy" @click="openHistory(scope.row)">历史</el-button></template></el-table-column></el-table>
      </el-card>
      <el-card v-if="editor" class="editor"><div slot="header"><b>{{ editor.id ? '编辑资料' : '新增资料草稿' }}</b><span class="muted"> · {{ company }} 公司 · {{ reportName }} · 当前版本 {{ editor.version }}</span></div><el-form label-width="100px" :disabled="busy">
        <el-form-item label="标题"><el-input v-model="editor.content.title" :maxlength="200" /></el-form-item><el-form-item label="来源名称"><el-input v-model="editor.content.sourceName" :maxlength="200" placeholder="填写实际制度、文件或手册名称" /></el-form-item><el-form-item label="来源链接"><el-input v-model="editor.content.sourceUri" :maxlength="512" placeholder="可选，填写不含凭据或查询参数的 HTTP(S) 来源地址" /></el-form-item>
        <el-form-item label="生效期间"><el-date-picker v-model="editor.content.effectiveFrom" type="date" value-format="yyyy-MM-dd" placeholder="生效日期（含当天）" /><span class="date-gap">至</span><el-date-picker v-model="editor.content.effectiveTo" type="date" value-format="yyyy-MM-dd" placeholder="失效日期（不含当天，可空）" /></el-form-item>
        <el-form-item label="导入正文">
          <input ref="documentFile" class="file-input" type="file" accept=".txt,.md" :disabled="busy" aria-label="选择 UTF-8 知识文本文件" @change="importFile" />
          <el-button size="small" :loading="importing" @click="$refs.documentFile.click()">导入文本替换正文</el-button>
          <p class="muted">支持 UTF-8 的 .txt / .md，文件最多 64 KiB、正文最多 12,000 字符。仅填入编辑器，请管理员核对来源、范围和生效期后保存草稿。</p>
          <el-alert v-if="importError" :title="importError" type="error" :closable="false" show-icon />
          <el-alert v-if="importNotice" :title="importNotice" type="info" :closable="false" show-icon />
        </el-form-item>
        <el-form-item label="正文"><el-input v-model="editor.content.body" type="textarea" :rows="9" :maxlength="12000" show-word-limit placeholder="录入实际业务定义或制度正文；不要填入密码、令牌等凭据。" @input="invalidateImport" /></el-form-item><el-form-item label="修改原因"><el-input v-model="editor.reason" :maxlength="500" /></el-form-item><el-form-item><el-button type="primary" :loading="busy" :disabled="importing" @click="save">保存草稿</el-button><el-button @click="closeEditor">关闭编辑</el-button></el-form-item>
      </el-form></el-card>
      <el-card class="search-panel">
        <div slot="header"><b>检索试查</b><p class="muted">查看当前范围内已生效的发布资料、目录、词典和规则。词法试查使用直接词项和配置别名；语义试查调用真实模型改写查询并核对候选相关性。两类试查都不读取业务明细。</p></div>
        <label for="knowledge-search-question" class="search-label">需要查证的问题</label>
        <el-input id="knowledge-search-question" v-model="searchQuestion" type="textarea" :rows="2" :maxlength="2000" show-word-limit placeholder="输入业务术语或制度问题，查看命中依据" @input="invalidateSearch" />
        <div class="search-actions">
          <el-button size="small" :disabled="!canSearch" :loading="searching && searchMode === 'LEXICAL'" @click="search('LEXICAL')">词法试查（不调用模型）</el-button>
          <el-button type="primary" size="small" :disabled="!canSearch" :loading="searching && searchMode === 'SEMANTIC'" @click="search('SEMANTIC')">语义试查（真实模型）</el-button>
          <span class="muted">{{ company }} 公司 · {{ reportName }}</span>
        </div>
        <el-alert v-if="searchError" :title="searchError" type="error" :closable="false" show-icon />
        <template v-if="searchResult"><p class="muted">{{ searchResultMode === 'SEMANTIC' ? '语义试查' : '词法试查' }}问题：{{ searchResult.question }}</p><knowledge-results :result="searchResult.result" /><details v-if="searchResultMode === 'SEMANTIC'"><summary>检索模型记录 · {{ searchResult.usage.length }} 次调用</summary><pre>{{ pretty(searchResult.usage) }}</pre></details></template>
      </el-card>
    </template>
    <el-dialog title="资料版本历史（最近 50 项）" :visible.sync="historyVisible" width="75%" @closed="closeHistory"><p v-if="historyError" role="alert">{{ historyError }}</p><div v-loading="historyBusy"><details v-for="item in revisions" :key="item.version"><summary>版本 {{ item.version }} · {{ stateText(item.status) }} · {{ item.reason }}</summary><p>{{ item.content.title }} · 来源：{{ item.content.sourceName }}</p><p>生效 {{ item.content.effectiveFrom }} / 失效 {{ item.content.effectiveTo || '未指定' }}</p><pre>{{ item.content.body }}</pre></details></div></el-dialog>
  </div>
</template>

<script>
/** 知识草稿、发布、文本导入及两类只读试查；异步结果绑定范围、问题、编辑器和页面代次，导入不会保存或发布。 */
import { analysisCatalog, knowledgeDocuments, knowledgeHistory, saveKnowledge, publishKnowledge, disableKnowledge, searchKnowledge, searchKnowledgeSemantic } from '../api/analysis'
import KnowledgeResults from '../components/KnowledgeResults.vue'
import { readKnowledgeFile } from '../utils/knowledgeImport'

export default {
  name: 'KnowledgeAdmin',
  components: { KnowledgeResults },
  data() { return { catalog: { companies: [], reports: [], admin: false }, ready: false, company: '', report: '', documents: [], editor: null, error: '', busy: false, loading: false, importing: false, importError: '', importNotice: '', historyVisible: false, historyBusy: false, historyError: '', revisions: [], searchQuestion: '', searchResult: null, searchResultMode: null, searchError: '', searching: false, searchMode: null } },
  computed: {
    reportName() { return this.catalog.reports.find(r => r.reportId === this.report)?.reportName || this.report },
    canSearch() { return this.catalog.admin && !this.busy && !!this.company && !!this.report && !!this.searchQuestion.trim() }
  },
  created() { this._alive = true; this._seq = 0; this._historySeq = 0; this._searchSeq = 0; this._importSeq = 0; this.initialize() },
  beforeDestroy() { this._alive = false; this._seq++; this._historySeq++; this._searchSeq++; this._importSeq++ },
  methods: {
    async initialize() { const seq = ++this._seq; try { const catalog = await analysisCatalog(); if (!this.valid(seq)) return; this.catalog = catalog; this.company = catalog.companies[0] || ''; this.report = catalog.reports[0]?.reportId || ''; this.ready = true; if (catalog.admin) await this.load() } catch (e) { if (this.valid(seq)) this.error = e.message } },
    valid(seq) { return this._alive && seq === this._seq },
    scopeChanged() { this.closeEditor(); this.closeHistory(); this.load() },
    async load() { const seq = ++this._seq; this.invalidateSearch(); this.invalidateImport(); this.documents = []; this.error = ''; this.loading = false; if (!this.company || !this.report) return; this.loading = true; try { const rows = await knowledgeDocuments(this.company, this.report); if (this.valid(seq)) this.documents = rows } catch (e) { if (this.valid(seq)) this.error = e.message } finally { if (this.valid(seq)) this.loading = false } },
    /** 范围、问题、刷新或资料变更后立即清除旧依据，网络请求仍可结束但不再拥有回写权。 */
    invalidateSearch() { this._searchSeq++; this.searchResult = null; this.searchResultMode = null; this.searchError = ''; this.searching = false; this.searchMode = null },
    searchValid(seq, request) { return this._alive && this.catalog.admin && seq === this._searchSeq && this.company === request.companyCode && this.report === request.reportId && this.searchQuestion === request.question },
    async search(mode = 'LEXICAL') {
      if (!['LEXICAL', 'SEMANTIC'].includes(mode) || !this.canSearch || (this.searching && this.searchMode === mode)) return
      const request = { companyCode: this.company, reportId: this.report, question: this.searchQuestion }, seq = ++this._searchSeq
      // 模式切换是用户显式的新试查，旧传输可以结束但不再拥有展示权；失败时保持空结果。
      this.searching = true; this.searchMode = mode; this.searchResult = null; this.searchResultMode = null; this.searchError = ''
      try { const result = await (mode === 'SEMANTIC' ? searchKnowledgeSemantic(request) : searchKnowledge(request)); if (this.searchValid(seq, request)) { this.searchResult = result; this.searchResultMode = mode } }
      catch (e) { if (this.searchValid(seq, request)) this.searchError = e.message }
      finally { if (this._alive && seq === this._searchSeq) this.searching = false }
    },
    invalidateImport() { this._importSeq++; this.importing = false; this.importError = ''; this.importNotice = '' },
    closeEditor() { this.invalidateImport(); this.editor = null },
    newDocument() { this.invalidateImport(); this.editor = { id: null, version: 0, reason: '', content: { companyCode: this.company, reportId: this.report, title: '', sourceName: '', sourceUri: '', body: '', effectiveFrom: new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()), effectiveTo: null } } },
    edit(row) { this.invalidateImport(); this.editor = { id: row.id, version: row.row_version, content: JSON.parse(JSON.stringify(row.content)), reason: '' } },
    /** 文件读取绑定原编辑器、正文与范围；用户继续编辑、切页或改选文件后，旧正文不可回写。 */
    async importFile(event) {
      const input = event.target, file = input.files?.[0]; input.value = ''
      if (!file || !this.catalog.admin || this.busy || !this.editor) return
      const editor = this.editor, body = editor.content.body, company = this.company, report = this.report, seq = ++this._importSeq, page = this._seq
      const current = () => this.valid(page) && this.catalog.admin && seq === this._importSeq && this.editor === editor && this.company === company && this.report === report && editor.content.body === body
      this.importing = true; this.importError = ''; this.importNotice = ''
      try { const result = await readKnowledgeFile(file); if (current()) { editor.content.body = result.text; this.importNotice = `已将“${result.name}”填入正文。请核对实际来源和适用信息，再保存草稿；发布仍需单独确认。` } }
      catch (e) { if (current()) this.importError = e.message || '读取文本失败，请重新选择文件。' }
      finally { if (this._alive && seq === this._importSeq) this.importing = false }
    },
    async save() { if (this.busy || this.importing || !this.editor) return; const editor = this.editor; const seq = this._seq; const request = { content: { ...editor.content, sourceUri: editor.content.sourceUri || null, effectiveTo: editor.content.effectiveTo || null }, expectedVersion: editor.version, reason: editor.reason }; this.invalidateSearch(); this.invalidateImport(); this.busy = true; this.error = '';
      try { const result = await saveKnowledge(editor.id, request); if (!this.valid(seq) || this.editor !== editor) return; this.edit(result); await this.load() } catch (e) { if (this.valid(seq)) this.error = e.message } finally { if (this._alive) this.busy = false } },
    async transition(row, publish) { if (this.busy) return; const seq = this._seq; const target = { id: row.id, version: row.row_version, title: row.content.title, company: this.company, report: this.report }; const action = publish ? '发布' : '停用';
      try { const { value } = await this.$prompt(`${action}“${target.title}”版本 ${target.version}。${publish ? '发布后，当前业务范围内的问答将使用此版本。' : '停用后，该资料不再用于问答，依赖它的旧分析会失效。'}请填写原因。`, `${action}知识资料`, { confirmButtonText: `确认${action}`, cancelButtonText: '取消', inputValidator: value => !!value?.trim() || '请填写原因' });
        if (!this.valid(seq) || this.company !== target.company || this.report !== target.report) return; this.invalidateSearch(); this.invalidateImport(); this.busy = true; this.error = ''; const request = { expectedVersion: target.version, reason: value.trim() };
        if (publish) await publishKnowledge(target.id, request); else await disableKnowledge(target.id, request); if (!this.valid(seq)) return; this.closeEditor(); await this.load()
      } catch (e) { if (this.valid(seq) && e !== 'cancel' && e !== 'close') this.error = e.message || '操作未完成' } finally { if (this._alive) this.busy = false } },
    async openHistory(row) { const seq = ++this._historySeq; this.historyVisible = true; this.historyBusy = true; this.historyError = ''; this.revisions = []; try { const values = await knowledgeHistory(row.id); if (this._alive && this.historyVisible && seq === this._historySeq) this.revisions = values } catch (e) { if (this._alive && this.historyVisible && seq === this._historySeq) this.historyError = e.message } finally { if (this._alive && seq === this._historySeq) this.historyBusy = false } },
    closeHistory() { this._historySeq++; this.historyVisible = false; this.revisions = []; this.historyError = ''; this.historyBusy = false },
    pretty(value) { return JSON.stringify(value, null, 2) },
    stateText(value) { return ({ DRAFT: '草稿', PUBLISHED: '已发布', DISABLED: '已停用' })[value] || value }
  }
}
</script>

<style scoped>
.file-input { display:none; }.search-panel { margin-top:18px; }.search-label { display:block; font-size:13px; margin-bottom:8px; }.search-actions { display:flex; align-items:center; flex-wrap:wrap; gap:14px; margin:14px 0; }
.intro h2 { margin-top:0; }.intro p,.muted { font-size:13px; color:#6b7c91; }.notice { margin-bottom:16px; }.scope { display:flex; flex-wrap:wrap; align-items:center; gap:16px; }.scope label { display:flex; align-items:center; gap:8px; font-size:13px; }.document-list { margin-top:18px; }.editor { margin-top:18px; }.date-gap { margin:0 12px; }.editor .el-input,.editor .el-textarea { max-width:850px; }details { margin-bottom:16px; }summary { color:#357bc8; cursor:pointer; }pre { white-space:pre-wrap; overflow-wrap:anywhere; padding:16px; background:#f5f8fc; line-height:1.8; }@media(max-width:900px){.editor >>> .el-form-item__label{width:80px!important;}.editor >>> .el-form-item__content{margin-left:80px!important;}.date-gap{display:block;margin:6px;}}
</style>
