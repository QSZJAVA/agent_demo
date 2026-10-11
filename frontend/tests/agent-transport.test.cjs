/** 实际会话发送模块的请求体契约回归；合成SSE仅用于检查传输边界，不代表真实模型或浏览器验收。 */
const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')

function transport() {
  const requests = [], events = []
  const context = { AbortController, TextDecoder, getSessionToken: () => 'test-session', authHeaders: () => ({}), sessionExpired: () => {},
    fetch: async (url, options) => {
      requests.push({ url, method: options.method, body: JSON.parse(options.body) })
      return new Response('id: request:0\nevent: done\ndata: {}\n\n', { headers: { 'Content-Type': 'text/event-stream' } })
    } }
  const source = fs.readFileSync(path.join(__dirname, '../src/api/agent.js'), 'utf8').replace(/^import .*$/gm, '').replace(/export /g, '')
  vm.runInNewContext(source, context)
  return { requests, events, send: input => context.streamChat(input, (type, data) => events.push({ type, data })).promise }
}

test('edited ranges, OR branches and clearing reach the chat request with the authoritative query reference', async () => {
  const { send, requests, events } = transport(), ref = 'a'.repeat(32)
  const variants = [
    [{ allOf: [{ field: 'amount', operator: 'GTE', values: ['30000'] }, { field: 'amount', operator: 'LTE', values: ['50000'] }] }],
    [{ allOf: [{ field: 'amount', operator: 'EQ', values: ['90071992547409.93'] }] }, { allOf: [{ field: 'docNo', operator: 'IN', values: ['001', '002'] }] }],
    []
  ]
  for (const conditions of variants) {
    const queryEdit = { queryRef: ref, conditions }
    await send({ conversationId: 'c'.repeat(32), message: '按已编辑的完整条件重新查询', queryEdit })
    assert.deepEqual(requests.at(-1).body.queryEdit, queryEdit)
    assert.equal(requests.at(-1).body.conversationId, 'c'.repeat(32))
  }
  assert.equal(events.length, variants.length); assert.ok(events.every(event => event.type === 'done'))
})

test('clarification selections keep the stable value and current reference instead of only sending the display label', async () => {
  const { send, requests } = transport()
  for (const value of ['A', 'amount', '001', '未派单']) {
    const clarificationReply = { clarificationRef: 'b'.repeat(32), value }
    await send({ conversationId: 'c'.repeat(32), message: '针对刚才的问题，我选择相应选项', clarificationReply })
    assert.deepEqual(requests.at(-1).body.clarificationReply, clarificationReply)
    assert.equal(Object.hasOwn(requests.at(-1).body, 'queryEdit'), false)
  }
})

test('ordinary chat sends only its declared request fields and preserves preview selection', async () => {
  const { send, requests } = transport()
  const input = { conversationId: 'c'.repeat(32), message: '查看候选', previewId: 'p'.repeat(32), excludedRecords: [{ reportId: 'report', recordId: '001' }] }
  await send({ ...input, unexpected: 'not a request field' })
  assert.deepEqual(requests[0].body, input); assert.equal(requests[0].url, '/api/agent/chat'); assert.equal(requests[0].method, 'POST')
})
