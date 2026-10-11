<template>
  <el-card class="semantic-quality">
    <div slot="header"><strong>语义质量</strong><p class="muted">诊断仅展示本人当前有权读取的会话。计划通过表示语义检查完成，实际业务结果请查看查询或派单任务。</p></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="诊断记录" name="runs">
        <div class="toolbar"><el-button size="small" :loading="loading" @click="loadRuns(null)">刷新</el-button><span class="muted">本页 {{ rows.length }} 个样本；不据此推断全量识别正确率。</span></div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-table :data="rows" stripe border :empty-text="loading ? '正在读取…' : error ? '读取失败，请刷新重试' : '当前没有可见诊断'">
          <el-table-column prop="createdAt" label="时间" width="180" />
          <el-table-column label="用户请求" min-width="220" show-overflow-tooltip><template slot-scope="s">{{ s.row.diagnostic.message }}</template></el-table-column>
          <el-table-column label="语义结果" width="110"><template slot-scope="s">{{ outcome(s.row.diagnostic.outcome) }}</template></el-table-column>
          <el-table-column label="模型调用" width="90"><template slot-scope="s">{{ s.row.diagnostic.modelCalls }}</template></el-table-column>
          <el-table-column label="修正调用" width="90"><template slot-scope="s">{{ s.row.diagnostic.repairCalls }}</template></el-table-column>
          <el-table-column label="耗时" width="105"><template slot-scope="s">{{ s.row.diagnostic.durationMs }} ms</template></el-table-column>
          <el-table-column label="操作" width="120"><template slot-scope="s"><el-button type="text" @click="openRun(s.row.eventId)">详情与反馈</el-button></template></el-table-column>
        </el-table>
        <el-button class="more" size="small" :disabled="loading || !!error || !nextCursor" @click="loadRuns(nextCursor)">更早记录</el-button>
      </el-tab-pane>
      <el-tab-pane label="业务词典" name="dictionary">
        <el-alert v-if="catalogError" :title="catalogError" type="error" :closable="false" /><el-button v-if="catalogError" type="text" @click="loadCatalog">重新读取授权目录</el-button>
        <div class="toolbar">
          <el-select v-model="scope.domain" size="small" @change="loadDictionary"><el-option label="报表数据" value="REPORT" /><el-option label="派单记录" value="DISPATCH" /><el-option label="工单" value="WORK_ORDER" /></el-select>
          <el-select v-model="scope.companyCode" size="small" placeholder="公司" @change="loadDictionary"><el-option v-for="company in catalog.companies" :key="company" :label="company" :value="company" /></el-select>
          <el-select v-model="scope.reportId" size="small" placeholder="报表" @change="loadDictionary"><el-option v-for="report in catalog.reports" :key="report.reportId" :label="report.reportName" :value="report.reportId" /></el-select>
          <el-button size="small" :loading="dictionaryLoading" @click="loadDictionary">重新读取</el-button>
          <el-button size="small" :disabled="!entry || dictionaryLoading" @click="openHistory">发布历史</el-button>
        </div>
        <el-alert v-if="dictionaryError" :title="dictionaryError" type="error" :closable="false" />
        <template v-if="entry && definition">
          <p class="muted">当前版本 {{ entry.version }}。词典只定义当前公司和报表的业务含义；{{ catalog.admin ? '发布后相关旧预览与清单需要按新版本重新核对。' : '当前账号可查看，发布需要管理员权限。' }}</p>
          <el-form :disabled="!catalog.admin || publishing" label-position="top" size="small">
            <div class="form-grid">
              <el-form-item label="公司名称"><el-input v-model="definition.companyName" :maxlength="128" /></el-form-item>
              <el-form-item label="公司别名"><el-select v-model="definition.companyAliases" multiple filterable allow-create default-first-option placeholder="输入后回车添加" /></el-form-item>
              <el-form-item label="时区"><el-input v-model="definition.timeZone" placeholder="例如 Asia/Shanghai" /></el-form-item>
              <el-form-item label="财务年度起始月"><el-select v-model="definition.fiscalStartMonth"><el-option v-for="month in 12" :key="month" :label="month + '月'" :value="month" /></el-select></el-form-item>
            </div>
            <el-form-item label="已明确维护的非工作日（不代表完整企业日历）"><el-select v-model="definition.holidays" multiple filterable allow-create placeholder="日期如 2026-10-01，输入后回车添加" /></el-form-item>
            <div class="toolbar"><el-select v-model="newField" filterable placeholder="选择已配置字段"><el-option v-for="field in availableFields" :key="field.name" :label="(field.description || field.name) + '（' + field.name + '）'" :value="field.name" /></el-select><el-button :disabled="!newField" @click="addField">添加字段定义</el-button></div>
            <el-collapse>
              <el-collapse-item v-for="(field, index) in definition.fields" :key="field.field" :title="fieldLabel(field.field)" :name="field.field">
                <div class="form-grid">
                  <el-form-item label="字段别名"><el-select v-model="field.aliases" multiple filterable allow-create placeholder="输入后回车添加" /></el-form-item>
                  <el-form-item label="业务定义"><el-input v-model="field.meaning" :maxlength="512" type="textarea" /></el-form-item>
                  <el-form-item label="来源基本单位（如元）"><el-input v-model="field.baseUnit" :disabled="!numericField(field.field)" :maxlength="32" /></el-form-item>
                  <el-form-item label="已核实币种（未知留空）"><el-input v-model="field.currency" :disabled="!numericField(field.field)" placeholder="例如 CNY" :maxlength="3" /></el-form-item>
                </div>
                <p>单位换算：1 个指定单位等于下列倍率的来源基本单位。</p>
                <div v-for="(unit, unitIndex) in field.units" :key="'unit-' + unitIndex" class="compact-row"><el-input v-model="unit.name" placeholder="单位名称，如万元" /><el-input v-model="unit.factor" placeholder="倍率，如10000" /><el-button type="text" @click="field.units.splice(unitIndex, 1)">删除</el-button></div>
                <el-button size="mini" :disabled="!numericField(field.field) || field.units.length >= 12" @click="field.units.push({ name: '', factor: '' })">增加单位</el-button>
                <p>枚举值及其业务别名</p>
                <div v-for="(value, valueIndex) in field.values" :key="'value-' + valueIndex" class="compact-row"><el-input v-model="value.value" placeholder="来源字段的实际值" /><el-select v-model="value.aliases" multiple filterable allow-create placeholder="业务别名" /><el-button type="text" @click="field.values.splice(valueIndex, 1)">删除</el-button></div>
                <el-button size="mini" :disabled="field.values.length >= 40" @click="field.values.push({ value: '', aliases: [] })">增加枚举值</el-button>
                <el-button type="text" @click="definition.fields.splice(index, 1)">移除字段定义</el-button>
              </el-collapse-item>
            </el-collapse>
            <el-form-item label="发布原因"><el-input v-model="reason" :maxlength="512" placeholder="说明业务口径的来源及变更原因" /></el-form-item>
            <el-button :loading="checking" @click="checkDefinition">检查草稿</el-button><el-button type="primary" :loading="publishing" @click="publish">发布本范围词典</el-button>
            <span v-if="checkMessage" class="check-result">{{ checkMessage }}</span>
          </el-form>
        </template>
        <p v-else-if="!dictionaryLoading && !dictionaryError" class="muted">请选择当前有权查看的公司与报表。</p>
      </el-tab-pane>
    </el-tabs>

    <el-dialog title="语义诊断与用户反馈" :visible.sync="detailVisible" width="900px" @close="closeRun">
      <p v-if="detailLoading">正在读取诊断…</p><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
      <template v-if="detail">
        <p>{{ detail.diagnostic.message }}</p><p>{{ outcome(detail.diagnostic.outcome) }} · {{ detail.diagnostic.durationMs }} ms · {{ detail.diagnostic.modelCalls }} 次模型调用</p>
        <p>输入用量：{{ metric(detail.diagnostic.inputTokens) }}；输出用量：{{ metric(detail.diagnostic.outputTokens) }}。费用：{{ detail.diagnostic.costComplete ? detail.diagnostic.cost : '未知，缺少可信价格或计量' }}。</p>
        <el-table :data="detail.diagnostic.calls" border size="mini"><el-table-column label="阶段"><template slot-scope="s">{{ stage(s.row.stage) }}</template></el-table-column><el-table-column prop="attempt" label="第几次" width="80" /><el-table-column prop="durationMs" label="毫秒" width="95" /><el-table-column prop="model" label="实际模型" min-width="140" /><el-table-column label="调用结果"><template slot-scope="s">{{ s.row.status === 'RETURNED' ? '收到响应，仍需校验' : '调用失败' }}</template></el-table-column></el-table>
        <el-collapse><el-collapse-item title="查看校验与修正记录"><p v-for="(validation, index) in detail.diagnostic.validations" :key="index">{{ stage(validation.stage) }} · {{ validation.outcome }}：{{ validation.detail || '校验完成' }}</p></el-collapse-item></el-collapse>
        <p class="muted">反馈先记录为待审核，不自动修改词典或变成训练标签。正常调整条件与系统理解错误分别记录。</p>
        <div class="toolbar"><el-select v-model="feedbackCategory" size="small"><el-option value="MISUNDERSTOOD" label="系统理解有误" /><el-option value="QUERY_ADJUSTMENT" label="正常调整查询" /></el-select></div>
        <el-input v-model="feedbackNote" type="textarea" :maxlength="1000" placeholder="说明原本的意思、哪里理解有误或希望如何调整" />
        <el-button class="more" size="small" :loading="feedbackLoading" @click="sendFeedback">保存反馈</el-button><span>{{ feedbackMessage }}</span>
        <p v-for="feedback in feedbackRows" :key="feedback.eventId">{{ feedback.createdAt }} · 待审核 · {{ feedback.diagnostic.note }}</p>
        <el-button v-if="feedbackCursor" size="small" :loading="feedbackPaging" :disabled="feedbackLoading" @click="moreFeedback">更早反馈</el-button>
      </template>
    </el-dialog>
    <el-dialog :title="historyTitle" :visible.sync="historyVisible" width="650px" @close="closeHistory"><p v-if="historyLoading">正在读取历史…</p><el-alert v-if="historyError" :title="historyError" type="error" :closable="false" /><el-table :data="historyRows" border><el-table-column prop="version" label="版本" /><el-table-column label="字段定义数"><template slot-scope="s">{{ s.row.definition.fields.length }}</template></el-table-column><el-table-column label="时区"><template slot-scope="s">{{ s.row.definition.timeZone }}</template></el-table-column><el-table-column label="财年开始月"><template slot-scope="s">{{ s.row.definition.fiscalStartMonth }}</template></el-table-column></el-table></el-dialog>
  </el-card>
</template>
<script>
/** 本人诊断和当前授权业务词典；所有异步响应绑定账号、请求代次及对象快照，发布确认期间切换范围不会提交旧对象。 */
import { getCurrentUserId, getSessionToken } from '../auth'
import { fetchSemanticRuns, fetchSemanticRun, fetchSemanticFeedback, submitSemanticFeedback, fetchSemanticCatalog, fetchDictionary, fetchDictionaryHistory, validateDictionary, publishDictionary } from '../api/semanticQuality'

export default {
  name: 'SemanticQuality',
  data() { return { owner: getCurrentUserId(), token: getSessionToken(), disposed: false, tab: 'runs', rows: [], nextCursor: null, loading: false, error: '', runSequence: 0,
    catalog: { companies: [], reports: [], admin: false }, catalogError: '', catalogSequence: 0, scope: { domain: 'REPORT', companyCode: '', reportId: '' }, dictionarySequence: 0,
    entry: null, definition: null, fields: [], newField: '', reason: '', dictionaryLoading: false, dictionaryError: '', checking: false, publishing: false, checkMessage: '', checkSequence: 0,
    detailVisible: false, detailId: null, detail: null, detailLoading: false, detailError: '', detailSequence: 0, feedbackRows: [], feedbackCursor: null, feedbackPaging: false, feedbackCategory: 'MISUNDERSTOOD', feedbackNote: '', feedbackLoading: false, feedbackMessage: '', feedbackRequest: null,
    historyVisible: false, historyRows: [], historyLoading: false, historyError: '', historyTitle: '词典发布历史', historySequence: 0 } },
  computed: { availableFields() { return this.fields.filter(field => !this.definition || !this.definition.fields.some(item => item.field === field.name)) } },
  watch: { definition: { deep: true, handler() { this.checkMessage = ''; this.checkSequence++; this.checking = false } }, reason() { this.checkMessage = ''; this.checkSequence++; this.checking = false } },
  created() { this.loadRuns(null); this.loadCatalog() },
  beforeDestroy() { this.disposed = true; this.runSequence++; this.dictionarySequence++; this.catalogSequence++; this.closeRun(); this.closeHistory(); this.rows = []; this.definition = null },
  methods: {
    current() { return !this.disposed && this.owner === getCurrentUserId() && this.token === getSessionToken() },
    outcome(value) { return { ACCEPTED: '计划通过', CLARIFY: '请求澄清', FAILED: '规划未完成' }[value] || value },
    stage(value) { return { PURPOSE: '目标判断', PLAN: '任务规划', REVIEW: '独立复核', PRECHECK: '业务预检' }[value] || value },
    metric(value) { return value == null ? '未提供' : String(value) },
    scopeKey() { return JSON.stringify(this.scope) },
    fieldLabel(name) { const field = this.fields.find(item => item.name === name); return field ? (field.description || name) + '（' + name + '）' : name },
    numericField(name) { const field = this.fields.find(item => item.name === name); return field && ['decimal', 'integer', 'long'].includes(field.type) },
    async loadRuns(before) {
      if (!this.current()) return
      const sequence = ++this.runSequence; this.loading = true; this.error = ''; this.rows = []; this.nextCursor = null
      try { const page = await fetchSemanticRuns(before); if (this.current() && sequence === this.runSequence) { this.rows = page.records; this.nextCursor = page.nextCursor } }
      catch (error) { if (this.current() && sequence === this.runSequence) this.error = error.message || '诊断读取失败' }
      finally { if (this.current() && sequence === this.runSequence) this.loading = false }
    },
    async loadCatalog() {
      const sequence = ++this.catalogSequence; this.catalogError = ''
      try { const catalog = await fetchSemanticCatalog(); if (!this.current() || sequence !== this.catalogSequence) return; this.catalog = catalog; this.scope.companyCode = catalog.companies[0] || ''; this.scope.reportId = (catalog.reports[0] || {}).reportId || ''; await this.loadDictionary() }
      catch (error) { if (this.current() && sequence === this.catalogSequence) this.catalogError = error.message || '授权目录读取失败' }
    },
    /** 读取前清空旧范围，失败不保留可被误发布的旧定义。 */
    async loadDictionary() {
      this.closeHistory(); const sequence = ++this.dictionarySequence, scope = { ...this.scope }, key = this.scopeKey()
      this.entry = null; this.definition = null; this.fields = []; this.dictionaryError = ''; this.reason = ''; this.checkMessage = ''; this.newField = ''; this.checkSequence++; this.dictionaryLoading = false
      if (!this.current() || !scope.companyCode || !scope.reportId) return
      this.dictionaryLoading = true
      try { const value = await fetchDictionary(scope); if (!this.current() || sequence !== this.dictionarySequence || key !== this.scopeKey()) return; this.entry = value.entry; this.definition = JSON.parse(JSON.stringify(value.entry.definition)); this.fields = value.fields }
      catch (error) { if (this.current() && sequence === this.dictionarySequence) this.dictionaryError = error.message || '词典读取失败' }
      finally { if (this.current() && sequence === this.dictionarySequence) this.dictionaryLoading = false }
    },
    addField() { if (!this.definition || !this.newField || !this.availableFields.some(field => field.name === this.newField)) return; this.definition.fields.push({ field: this.newField, aliases: [], meaning: '', baseUnit: '', currency: '', units: [], values: [] }); this.newField = '' },
    changeSnapshot() { if (!this.entry || !this.definition || !this.reason.trim()) throw new Error('请先读取词典并填写发布原因'); return { expectedVersion: this.entry.version, definition: JSON.parse(JSON.stringify(this.definition)), reason: this.reason.trim() } },
    async checkDefinition() {
      if (!this.current() || !this.catalog.admin || this.publishing) return
      let change; try { change = this.changeSnapshot() } catch (error) { this.checkMessage = error.message; return }
      const sequence = ++this.checkSequence, key = this.scopeKey(), draft = JSON.stringify(change); this.checking = true; this.checkMessage = ''
      try { await validateDictionary({ ...this.scope }, change); if (this.current() && sequence === this.checkSequence && key === this.scopeKey() && draft === JSON.stringify(this.changeSnapshot())) this.checkMessage = '草稿检查通过，尚未发布' }
      catch (error) { if (this.current() && sequence === this.checkSequence) this.checkMessage = error.message || '检查失败' }
      finally { if (this.current() && sequence === this.checkSequence) this.checking = false }
    },
    /** 确认前冻结范围、版本与完整输入，确认返回后逐项核对，不从可变表单重新构造写入目标。 */
    async publish() {
      if (!this.current() || !this.catalog.admin || this.publishing) return
      let change; try { change = this.changeSnapshot() } catch (error) { this.checkMessage = error.message; return }
      const scope = { ...this.scope }, key = this.scopeKey(), draft = JSON.stringify(change), sequence = this.dictionarySequence; this.publishing = true
      try {
        await this.$confirm('将发布公司 ' + scope.companyCode + ' 的 ' + this.fieldScopeLabel(scope) + ' 词典；相关旧预览和清单须按新版本重新核对。', '确认发布业务词典', { type: 'warning' })
        if (!this.current() || sequence !== this.dictionarySequence || key !== this.scopeKey() || draft !== JSON.stringify(this.changeSnapshot())) return
        await publishDictionary(scope, change)
        if (this.current() && sequence === this.dictionarySequence && key === this.scopeKey()) { await this.loadDictionary(); this.checkMessage = '词典已发布' }
      } catch (error) { if (error !== 'cancel' && error !== 'close' && this.current() && sequence === this.dictionarySequence) this.checkMessage = error.message || '发布未完成，请重新读取核对' }
      finally { if (this.current()) this.publishing = false }
    },
    fieldScopeLabel(scope) { const report = this.catalog.reports.find(row => row.reportId === scope.reportId); return (report ? report.reportName : scope.reportId) + ' / ' + { REPORT: '报表', DISPATCH: '派单记录', WORK_ORDER: '工单' }[scope.domain] },
    closeHistory() { this.historySequence++; this.historyVisible = false; this.historyRows = []; this.historyError = ''; this.historyLoading = false },
    async openHistory() {
      this.closeHistory(); if (!this.current() || !this.entry) return
      const sequence = this.historySequence, scope = { ...this.scope }, key = this.scopeKey(); this.historyVisible = true; this.historyLoading = true; this.historyTitle = scope.companyCode + ' / ' + this.fieldScopeLabel(scope) + ' 发布历史'
      try { const rows = await fetchDictionaryHistory(scope); if (this.current() && this.historyVisible && sequence === this.historySequence && key === this.scopeKey()) this.historyRows = rows }
      catch (error) { if (this.current() && sequence === this.historySequence) this.historyError = error.message || '历史读取失败' }
      finally { if (this.current() && sequence === this.historySequence) this.historyLoading = false }
    },
    closeRun() { this.detailSequence++; this.detailVisible = false; this.detailId = null; this.detail = null; this.detailError = ''; this.detailLoading = false; this.feedbackRows = []; this.feedbackCursor = null; this.feedbackPaging = false; this.feedbackNote = ''; this.feedbackMessage = ''; this.feedbackRequest = null; this.feedbackLoading = false },
    async openRun(id) {
      this.closeRun(); if (!this.current()) return
      this.detailId = id; this.detailVisible = true; this.detailLoading = true; const sequence = this.detailSequence
      try { const [detail, feedback] = await Promise.all([fetchSemanticRun(id), fetchSemanticFeedback(id)]); if (this.current() && sequence === this.detailSequence && this.detailId === id) { this.detail = detail; this.feedbackRows = feedback.records; this.feedbackCursor = feedback.nextCursor } }
      catch (error) { if (this.current() && sequence === this.detailSequence) this.detailError = error.message || '诊断详情读取失败' }
      finally { if (this.current() && sequence === this.detailSequence) this.detailLoading = false }
    },
    /** 重试沿用同一反馈请求号；编辑内容或更换目标后才生成新键。 */
    async sendFeedback() {
      if (!this.current() || !this.detail || this.feedbackLoading || this.feedbackPaging || !this.feedbackNote.trim()) return
      const id = this.detailId, sequence = this.detailSequence, content = { category: this.feedbackCategory, note: this.feedbackNote.trim() }, signature = JSON.stringify([id, content])
      if (!this.feedbackRequest || this.feedbackRequest.signature !== signature) this.feedbackRequest = { signature, feedbackId: crypto.randomUUID().replace(/-/g, '') }
      const request = { ...content, feedbackId: this.feedbackRequest.feedbackId }; this.feedbackLoading = true; this.feedbackMessage = ''
      try { await submitSemanticFeedback(id, request); const feedback = await fetchSemanticFeedback(id); if (this.current() && sequence === this.detailSequence && id === this.detailId) { this.feedbackRows = feedback.records; this.feedbackCursor = feedback.nextCursor; this.feedbackMessage = '反馈已保存，待审核' } }
      catch (error) { if (this.current() && sequence === this.detailSequence) this.feedbackMessage = error.message || '反馈未完成，可用原请求重试' }
      finally { if (this.current() && sequence === this.detailSequence) this.feedbackLoading = false }
    },
    /** 翻页绑定当前诊断与游标；关闭或换轮次后迟到页不得追加到另一份反馈。 */
    async moreFeedback() {
      if (!this.current() || !this.detail || !this.feedbackCursor || this.feedbackPaging || this.feedbackLoading) return
      const id = this.detailId, sequence = this.detailSequence, cursor = this.feedbackCursor; this.feedbackPaging = true
      try { const page = await fetchSemanticFeedback(id, cursor); if (this.current() && sequence === this.detailSequence && id === this.detailId && cursor === this.feedbackCursor) { this.feedbackRows.push(...page.records); this.feedbackCursor = page.nextCursor } }
      catch (error) { if (this.current() && sequence === this.detailSequence) this.feedbackMessage = error.message || '反馈翻页失败，可重试' }
      finally { if (this.current() && sequence === this.detailSequence) this.feedbackPaging = false }
    }
  }
}
</script>
<style scoped>
.muted { color: #687586; font-size: 13px; line-height: 1.7; }.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 12px 0; }.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; }.el-select { max-width: 100%; }.el-form-item .el-select { width: 100%; }.compact-row { display: grid; grid-template-columns: 1fr 1fr 50px; gap: 12px; margin: 8px 0; }.more { margin: 16px 8px 0 0; }.check-result { margin-left: 12px; }.semantic-quality { min-width: 0; }
</style>
