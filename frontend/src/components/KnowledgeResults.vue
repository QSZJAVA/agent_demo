<template>
  <div class="knowledge-results">
    <p class="coverage">已搜索 {{ result.coverage.searchedSources }} 份来源、{{ result.searched }} 个片段；命中 {{ result.coverage.matchedSources }} 份来源、{{ result.matched }} 个片段。</p>
    <p class="muted">本次返回 {{ result.coverage.returnedSources }} 份来源、{{ result.sources.length }} 个片段，优先保留不同来源。命中表示相关候选，具体结论仍需核对原文。</p>
    <el-alert v-if="result.truncated" type="warning" title="仍有匹配片段未返回，不能据此判断所有制度一致或没有其他规定。" :closable="false" show-icon />
    <el-alert v-if="result.expansionTruncated" type="warning" title="已达到字段别名扩展上限，请缩小问题后重新检索。" :closable="false" show-icon />
    <div v-if="semantic" class="semantic-summary">
      <p>多查询语义检索汇集 {{ retrieval.candidateFragments }} 个候选片段，{{ retrieval.rerankedFragments }} 个片段进入相关性重排。</p>
      <p class="muted">{{ retrieval.reranked ? '返回次序用于展示相关候选，不能作为结论可信度或制度一致性的证明。' : '本次未执行片段重排，请结合候选和覆盖范围核对依据。' }}</p>
      <el-alert v-if="retrieval.contextTruncated" type="warning" title="部分匹配片段未进入本次语义重排，当前依据覆盖有限。" :closable="false" show-icon />
      <details class="search-explanation">
        <summary>查看实际检索表达</summary>
        <p v-for="(query, index) in retrieval.queries" :key="index">{{ index + 1 }}. {{ queryKind(query.kind) }}：{{ query.text }}</p>
      </details>
    </div>
    <details class="search-explanation">
      <summary>查看检索词与别名依据</summary>
      <p>本步直接检索词：{{ result.queryTerms.join('、') || '没有可用词项' }}</p>
      <p class="muted">检索词已统一大小写和全半角。只扩展当前范围内明确配置的字段名称。</p>
      <p v-if="!result.expansions.length">本次未使用字段别名扩展。</p>
      <!-- 多条检索表达可分别触发同一字段；这些无内部状态的说明段落按本次结果顺序唯一编号。 -->
      <p v-for="(item, expansionIndex) in result.expansions" :key="expansionIndex">原文命中“{{ item.triggers.join('、') }}”，依据字段 {{ item.field }} 的配置，补充检索“{{ item.addedTerms.join('、') }}”。</p>
    </details>
    <p v-if="!result.sources.length" class="empty">本次未找到匹配依据。可补充具体术语，或检查资料是否已发布并生效；这不表示制度一定没有规定。</p>
    <details v-for="source in result.sources" :key="source.id" class="source">
      <summary>{{ source.title }} · 版本 {{ source.version }}</summary>
      <p class="muted">{{ source.scope }} · {{ source.observedAt }}</p>
      <p v-if="hits[source.id].directTerms.length">直接命中：{{ hits[source.id].directTerms.join('、') }}</p>
      <p v-if="hits[source.id].aliasTerms.length">别名命中：{{ hits[source.id].aliasTerms.join('、') }}</p>
      <template v-if="semantic && rankings[source.id]">
        <p>返回次序：{{ rankings[source.id].rank }}</p>
        <p v-for="route in sourceRoutes(source.id)" :key="route.number" class="muted">由表达 {{ route.number }}（{{ route.kind }}）召回：{{ route.text }}</p>
      </template>
      <pre>{{ source.text }}</pre>
    </details>
  </div>
</template>

<script>
/** 分析步骤与两类试查共用的依据展示；词项命中与语义召回路径分别呈现，排序不代表可信度，所有原文按文本转义。 */
export default {
  name: 'KnowledgeResults',
  props: { result: { type: Object, required: true } },
  computed: {
    hits() { return Object.fromEntries(this.result.hits.map(hit => [hit.sourceId, hit])) },
    retrieval() { return this.result.retrieval },
    semantic() { return this.retrieval?.mode === 'SEMANTIC_RAG' },
    rankings() { return this.semantic ? Object.fromEntries(this.retrieval.ranking.map(item => [item.sourceId, item])) : {} }
  },
  methods: {
    queryKind(kind) { return ({ ORIGINAL: '原问题', FOCUSED: '聚焦问题', REWRITE: '模型改写' })[kind] || kind },
    /** 仅将服务端零基路由映射到实际表达；不把改写词回填成原问题的直接命中。 */
    sourceRoutes(id) {
      const item = this.rankings[id]
      if (!item) return []
      return item.routes.filter(index => Number.isInteger(index) && index >= 0 && index < this.retrieval.queries.length)
        .map(index => ({ number: index + 1, kind: this.queryKind(this.retrieval.queries[index].kind), text: this.retrieval.queries[index].text }))
    }
  }
}
</script>

<style scoped>
.coverage { line-height:1.8; }.muted { color:#64758b; font-size:13px; line-height:1.7; }.empty,.semantic-summary { padding:14px; background:#f5f8fc; line-height:1.8; }.semantic-summary { margin-top:12px; }.search-explanation,.source { margin:16px 0; }summary { color:#286bb6; cursor:pointer; line-height:1.7; }pre { white-space:pre-wrap; overflow-wrap:anywhere; padding:14px; background:#f5f8fc; line-height:1.8; font-family:inherit; }.el-alert + .el-alert { margin-top:8px; }
</style>
