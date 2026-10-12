<template>
  <div class="knowledge-results">
    <p class="coverage">已搜索 {{ result.coverage.searchedSources }} 份来源、{{ result.searched }} 个片段；命中 {{ result.coverage.matchedSources }} 份来源、{{ result.matched }} 个片段。</p>
    <p class="muted">本次返回 {{ result.coverage.returnedSources }} 份来源、{{ result.sources.length }} 个片段，优先保留不同来源。命中表示相关候选，具体结论仍需核对原文。</p>
    <el-alert v-if="result.truncated" type="warning" title="仍有匹配片段未返回，不能据此判断所有制度一致或没有其他规定。" :closable="false" show-icon />
    <el-alert v-if="result.expansionTruncated" type="warning" title="已达到字段别名扩展上限，请缩小问题后重新检索。" :closable="false" show-icon />
    <details class="search-explanation">
      <summary>查看检索词与别名依据</summary>
      <p>直接检索词：{{ result.queryTerms.join('、') || '没有可用词项' }}</p>
      <p class="muted">检索词已统一大小写和全半角。只扩展当前范围内明确配置的字段名称。</p>
      <p v-if="!result.expansions.length">本次未使用字段别名扩展。</p>
      <p v-for="item in result.expansions" :key="item.field">原文命中“{{ item.triggers.join('、') }}”，依据字段 {{ item.field }} 的配置，补充检索“{{ item.addedTerms.join('、') }}”。</p>
    </details>
    <p v-if="!result.sources.length" class="empty">本次未找到匹配依据。可补充具体术语，或检查资料是否已发布并生效；这不表示制度一定没有规定。</p>
    <details v-for="source in result.sources" :key="source.id" class="source">
      <summary>{{ source.title }} · 版本 {{ source.version }}</summary>
      <p class="muted">{{ source.scope }} · {{ source.observedAt }}</p>
      <p v-if="hits[source.id].directTerms.length">直接命中：{{ hits[source.id].directTerms.join('、') }}</p>
      <p v-if="hits[source.id].aliasTerms.length">别名命中：{{ hits[source.id].aliasTerms.join('、') }}</p>
      <pre>{{ source.text }}</pre>
    </details>
  </div>
</template>

<script>
/** 分析步骤与管理员试查共用的知识结果展示；按服务端真实覆盖和命中解释显示，原文一律按文本转义。 */
export default {
  name: 'KnowledgeResults',
  props: { result: { type: Object, required: true } },
  computed: { hits() { return Object.fromEntries(this.result.hits.map(hit => [hit.sourceId, hit])) } }
}
</script>

<style scoped>
.coverage { line-height:1.8; }.muted { color:#64758b; font-size:13px; line-height:1.7; }.empty { padding:14px; background:#f5f8fc; line-height:1.8; }.search-explanation,.source { margin:16px 0; }summary { color:#286bb6; cursor:pointer; line-height:1.7; }pre { white-space:pre-wrap; overflow-wrap:anywhere; padding:14px; background:#f5f8fc; line-height:1.8; font-family:inherit; }.el-alert + .el-alert { margin-top:8px; }
</style>
