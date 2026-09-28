<template>
  <el-card shadow="never" :class="['agent-card', { expired: readonly }]">
    <div slot="header" class="card-head">
      <span class="card-title">
        <i class="el-icon-view"></i> 可派单记录预览
        <el-tag size="mini" type="info">共 {{ payload.total }} 条</el-tag>
        <el-tag v-if="statusTag" size="mini" :type="statusTag.type">{{ statusTag.label }}</el-tag>
      </span>
      <span class="card-sub">
        <span v-for="r in payload.byReport" :key="r.reportId || r.reportType" class="count">
          {{ r.reportName }} {{ r.count }} 条
        </span>
      </span>
    </div>

    <div v-if="resolutionHint" class="resolution">
      <i class="el-icon-info"></i> {{ resolutionHint }}
    </div>

    <el-table
      ref="table"
      :data="pageRecords"
      size="mini"
      border
      max-height="320"
      :row-key="rowKey"
      @selection-change="onSelectionChange"
    >
      <el-table-column v-if="!readonly" type="selection" width="45" align="center" :reserve-selection="false" :selectable="canSelect" />
      <el-table-column prop="reportName" label="报表" width="90" />
      <el-table-column prop="docNo" label="单据号" width="130" />
      <el-table-column prop="label" label="摘要" min-width="140" show-overflow-tooltip />
      <el-table-column prop="companyCode" label="公司" width="60" align="center" />
      <el-table-column label="金额(元)" width="120" align="right">
        <template slot-scope="scope">{{ formatAmount(scope.row.amount) }}</template>
      </el-table-column>
      <el-table-column prop="date" label="日期" width="105" />
      <el-table-column prop="ruleName" label="命中规则" width="140" show-overflow-tooltip />
    </el-table>

    <el-pagination v-if="payload.total > 50" class="preview-pages" small layout="prev, pager, next"
      :current-page="page" :page-size="50" :total="payload.total" @current-change="changePage" />

    <div class="card-foot">
      <span class="rule-desc">
        <template v-for="(desc, key) in payload.ruleDescriptions">
          <span :key="key" class="rule">{{ desc }}</span>
        </template>
        <span v-if="payload.expiresAt && status === 'ACTIVE'" class="expiry">有效期至 {{ formatTime(payload.expiresAt) }}</span>
      </span>
      <span v-if="readonly" class="expired-hint">{{ readonlyHint }}</span>
      <span v-else class="foot-actions">
        <span class="sel-hint">已勾选 {{ selectedCount }} / {{ payload.total }} 条，取消勾选的记录派单时会排除</span>
        <el-button type="warning" size="mini" :disabled="selectedCount === 0 || busy || loadingPage" @click="$emit('dispatch-selected')">派单已勾选记录</el-button>
      </span>
    </div>
  </el-card>
</template>

<script>
import { fetchPreviewItems } from '../../api/agent'
// 预览状态只由服务端给出：ACTIVE 有效 / SUPERSEDED 已作废 / EXPIRED 已失效 / CONSUMED 已据此派单
const STATUS_TAGS = {
  SUPERSEDED: { label: '已作废', type: 'info' },
  EXPIRED: { label: '已失效', type: 'info' },
  CONSUMED: { label: '已派单', type: 'success' },
  LEGACY: { label: '历史快照', type: '' }
}

export default {
  name: 'PreviewCard',
  props: {
    payload: { type: Object, required: true },
    // 服务端状态；升级前的历史快照没有服务端状态，按只读处理
    status: { type: String, default: null },
    // 状态说明（失效原因等），由服务端给出
    statusMessage: { type: String, default: null },
    // 正在发送消息时禁用按钮
    busy: { type: Boolean, default: false }
  },
  data() {
    return {
      selectedCount: this.payload.total || 0,
      page: 1,
      pageRecords: this.payload.records || [],
      excludedRecords: [],
      loadingPage: false,
      pageRequest: 0
    }
  },
  computed: {
    effectiveStatus() {
      return this.status || 'LEGACY'
    },
    readonly() {
      return this.effectiveStatus !== 'ACTIVE'
    },
    statusTag() {
      return STATUS_TAGS[this.effectiveStatus] || null
    },
    readonlyHint() {
      if (this.effectiveStatus === 'LEGACY') return '历史快照仅供查看，如需派单请重新查询。'
      if (this.statusMessage) return this.statusMessage
      return '该预览已不可用，如需派单请重新查询。'
    },
    resolutionHint() {
      const r = this.payload.resolution
      if (!r) return ''
      const names = (this.payload.byReport || []).map((b) => b.reportName).join('、')
      const parts = []
      if (r.matchType === 'FUZZY') {
        parts.push(`“${r.query}”没有完全对应的报表，已按最接近的“${names}”查询，如不正确请重新说明报表名称。`)
      }
      if (r.unrecognized && r.unrecognized.length) {
        parts.push(`没有找到与“${r.unrecognized.join('、')}”对应的报表。`)
      }
      return parts.join(' ')
    }
  },
  watch: {
    readonly(v) {
      if (!v) this.selectAll()
    }
  },
  mounted() {
    if (!this.readonly) this.selectAll()
  },
  methods: {
    canSelect() {
      return !this.readonly && !this.busy && !this.loadingPage
    },
    rowKey(row) {
      return JSON.stringify([row.reportId, row.recordId])
    },
    selectAll(request = this.pageRequest) {
      this.$nextTick(() => {
        if (request !== this.pageRequest) return
        const table = this.$refs.table
        if (!table || this.readonly) return
        this.loadingPage = true
        table.clearSelection()
        this.pageRecords.forEach((row) => {
          if (!this.excludedRecords.some((item) => this.rowKey(item) === this.rowKey(row))) table.toggleRowSelection(row, true)
        })
        this.$nextTick(() => { if (request === this.pageRequest) this.loadingPage = false })
      })
    },
    onSelectionChange(rows) {
      if (this.readonly || this.loadingPage) return
      const selected = new Set(rows.map((r) => this.rowKey(r)))
      const excluded = new Map(this.excludedRecords.map((r) => [this.rowKey(r), r]))
      this.pageRecords.forEach((r) => selected.has(this.rowKey(r)) ? excluded.delete(this.rowKey(r))
        : excluded.set(this.rowKey(r), { reportId: r.reportId, recordId: r.recordId }))
      this.excludedRecords = [...excluded.values()]
      this.selectedCount = Math.max(0, this.payload.total - excluded.size)
      this.$emit('selection-change', this.excludedRecords)
    },
    async changePage(page) {
      const request = ++this.pageRequest
      this.loadingPage = true
      try {
        const records = await fetchPreviewItems(this.payload.previewId, page)
        if (request !== this.pageRequest) return
        this.loadingPage = true
        this.pageRecords = records
        this.page = page
        this.selectAll()
      } catch (e) {
        if (request === this.pageRequest) this.loadingPage = false
      }
    },
    formatAmount(value) {
      const num = Number(value)
      if (Number.isNaN(num)) return value
      return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    },
    formatTime(t) {
      return t ? String(t).replace('T', ' ').slice(11, 16) : ''
    }
  }
}
</script>

<style scoped>
.agent-card {
  width: 100%;
}

.agent-card.expired {
  background: #fafafa;
}

.expired-hint {
  color: #909399;
  font-size: 12px;
}

.agent-card >>> .el-card__header {
  padding: 8px 14px;
}

.agent-card >>> .el-card__body {
  padding: 10px 14px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
}

.card-title {
  font-weight: 600;
  font-size: 13px;
}

.card-title .el-tag {
  margin-left: 6px;
}

.card-sub .count {
  font-size: 12px;
  color: #606266;
  margin-left: 10px;
}

.resolution {
  margin-bottom: 8px;
  font-size: 12px;
  color: #e6a23c;
}

.card-foot {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
}

.rule-desc .rule,
.rule-desc .expiry {
  color: #909399;
  margin-right: 10px;
}

.foot-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sel-hint {
  color: #e6a23c;
}
</style>
