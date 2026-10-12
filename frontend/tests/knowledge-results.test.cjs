/** 知识检索的当前接口与展示边界；分别核对传输等待窗口、词项依据和真实语义查询路由。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue'), { compileTemplate } = require('vue/compiler-sfc')
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')

test('semantic preview has an independent 100-second timeout without changing lexical transport or resubmitting', async () => {
  const calls = [], context = { http: { post: (...args) => { calls.push(args); return Promise.resolve({}) } } }
  vm.runInNewContext(read('api/analysis.js').replace(/import[^\n]*from\s+['"][^'"]+['"]/g, '').replace(/export const /g, 'var '), context)
  const request = { companyCode: 'A', reportId: 'rpt-sales-order', question: '销售款已到账了吗' }
  await context.searchKnowledgeSemantic(request); await context.searchKnowledge(request)
  assert.equal(calls.length, 2); assert.equal(calls[0][0], '/analysis/knowledge/search/semantic'); assert.equal(calls[0][1], request); assert.equal(calls[0][2].timeout, 100000)
  assert.equal(calls[1][0], '/analysis/knowledge/search'); assert.equal(calls[1].length, 2)
})

function resultState(result) {
  const context = { component: null }
  vm.runInNewContext(read('components/KnowledgeResults.vue').match(/<script>([\s\S]*?)<\/script>/)[1].replace('export default', 'component ='), context)
  const state = { result }
  for (const [key, fn] of Object.entries(context.component.methods)) state[key] = fn.bind(state)
  for (const [key, fn] of Object.entries(context.component.computed)) Object.defineProperty(state, key, { get: () => fn.call(state) })
  return state
}

test('semantic-only candidates show actual query routes without manufacturing direct or alias hits', () => {
  const result = { hits: [{ sourceId: 'doc', directTerms: [], aliasTerms: [] }], retrieval: {
    mode: 'SEMANTIC_RAG', queries: [{ kind: 'ORIGINAL', text: '款项到账了吗' }, { kind: 'FOCUSED', text: '到账含义' }, { kind: 'REWRITE', text: '订单金额与已收款区别' }],
    candidateFragments: 4, rerankedFragments: 3, contextTruncated: true, reranked: true, version: 'test', ranking: [{ sourceId: 'doc', routes: [1, 2], rank: 1 }]
  } }
  const state = resultState(result)
  assert.equal(state.semantic, true); assert.equal(state.rankings.doc.rank, 1); assert.equal(state.retrieval.contextTruncated, true)
  assert.deepEqual(JSON.parse(JSON.stringify(state.sourceRoutes('doc'))), [{ number: 2, kind: '聚焦问题', text: '到账含义' }, { number: 3, kind: '模型改写', text: '订单金额与已收款区别' }])
  assert.equal(state.hits.doc.directTerms.length, 0); assert.equal(state.hits.doc.aliasTerms.length, 0)
})

test('lexical preview remains lexical and absent routes do not become invented query expressions', () => {
  const lexical = resultState({ hits: [], retrieval: null })
  assert.equal(lexical.semantic, false); assert.equal(lexical.sourceRoutes('missing').length, 0)
  const semantic = resultState({ hits: [], retrieval: { mode: 'SEMANTIC_RAG', queries: [{ kind: 'ORIGINAL', text: '原问题' }], ranking: [{ sourceId: 'doc', routes: [-1, 1, 0], rank: 1 }] } })
  assert.equal(semantic.sourceRoutes('doc').length, 1); assert.equal(semantic.sourceRoutes('doc')[0].text, '原问题')
})

test('同一字段因不同查询触发的展开项保留独立渲染键', () => {
  const source = read('components/KnowledgeResults.vue'), context = { component: null }
  vm.runInNewContext(source.match(/<script>([\s\S]*?)<\/script>/)[1].replace('export default', 'component ='), context)
  const compiled = compileTemplate({ source: source.match(/<template>([\s\S]*)<\/template>\s*<script>/)[1], filename: 'KnowledgeResults.vue' })
  assert.deepEqual(compiled.errors, [])
  vm.runInNewContext(compiled.code, context)
  const result = {
    coverage: { searchedSources: 0, matchedSources: 0, returnedSources: 0 }, searched: 0, matched: 0, sources: [], hits: [], queryTerms: [],
    expansions: [{ field: 'amount', triggers: ['booked value'], addedTerms: ['amount'] }, { field: 'amount', triggers: ['销单值'], addedTerms: ['amount'] }],
    truncated: false, expansionTruncated: false, retrieval: null
  }
  const component = new Vue({ ...context.component, propsData: { result }, render: context.render, staticRenderFns: context.staticRenderFns })
  const paragraphs = []
  const visit = node => {
    if (node.tag === 'p' && node.children?.some(child => child.text?.startsWith('原文命中'))) paragraphs.push(node)
    for (const child of node.children || []) visit(child)
  }
  visit(component._render())
  assert.equal(paragraphs.length, 2)
  assert.equal(new Set(paragraphs.map(node => node.key)).size, 2)
  assert.match(paragraphs[0].children.map(node => node.text || '').join(''), /booked value/)
  assert.match(paragraphs[1].children.map(node => node.text || '').join(''), /销单值/)
})
