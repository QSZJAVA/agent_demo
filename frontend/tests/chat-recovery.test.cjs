/** 统一聊天的真实组件与保存器回归；使用受控SSE/GET验证幂等、首响应丢失和竞态，真实模型与浏览器另行验收。 */
const { test } = require('node:test'), assert = require('node:assert/strict')
const { environment, chatHarness, receiptContext, request, receipt, task, plain, serverError, until, tick, C, R, PREVIEW } = require('./helpers/unified-chat-harness.cjs')

test('网络发送之前完整请求已保存且回读，正常分析事件只产生一张引用卡片', async () => {
  const env = environment()
  const { state, calls } = chatHarness(env, { streamChat: (body, event, key) => {
    const raw = [...env.values.values()].map(value => { try { return JSON.parse(value) } catch (_) { return null } }).find(value => value?.key === key)
    assert.ok(raw); assert.deepEqual(raw.request, plain(body))
    event('conversation', { requestId: R, conversationId: C, replayed: false }); event('analysis_task', { ...task(), answer: '不得写入卡片缓存' }); event('turn_receipt', receipt(key))
    return { promise: Promise.resolve(), abort() {} }
  } })
  await state.send(request().message)
  assert.equal(calls.posts.length, 1); assert.match(calls.posts[0].key, /^chat-/)
  const cards = state.messages.filter(item => item.cardType === 'analysis_task')
  assert.equal(cards.length, 1); assert.deepEqual(plain(cards[0].payload), task()); assert.equal(state.chatPending, null); assert.equal(env.values.size, 0)
})

test('首个SSE事件丢失仍按准确key找到原会话和原任务，不创建第二轮', async () => {
  const { state, calls } = chatHarness(undefined, { streamChat: () => ({ promise: Promise.reject(new Error('lost before first frame')), abort() {} }) })
  await state.send(request().message)
  assert.equal(state.activeId, C); assert.equal(calls.posts.length, 1); assert.equal(calls.gets.length, 1)
  assert.equal(calls.gets[0][0], calls.posts[0].key); assert.equal(calls.discoveries.length, 0)
  assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, 1); assert.equal(state.chatPending, null)
})

test('未知提交经过刷新只GET，显式核对才同key同完整载荷POST且不重复历史消息', async () => {
  const env = environment()
  const first = chatHarness(env, { streamChat: () => ({ promise: Promise.reject(new Error('lost response')) }), fetchChatTurn: async () => { throw new Error('offline') } })
  first.state.activeId = C; first.state.uiPreviewId = PREVIEW; first.state.uiExcludes = [{ reportId: 'rpt-sales-order', recordId: '001' }]
  const edit = { queryRef: R, conditions: [{ allOf: [{ field: 'amount', operator: 'GTE', values: ['90071992547409.93'] }] }, { allOf: [{ field: 'docNo', operator: 'IN', values: ['001', '002'] }] }] }
  await first.state.send('按已编辑的完整条件重新查询', { queryEdit: edit })
  const original = first.calls.posts[0]; assert.ok(first.state.chatPending)
  await first.state.send('新的问题'); assert.equal(first.calls.posts.length, 1)
  const reopened = chatHarness(env, { fetchChatTurn: async key => receipt(key, 'READY'), streamChat: (body, event, key) => {
    event('conversation', { requestId: R, conversationId: C, replayed: true }); event('turn_receipt', receipt(key)); event('analysis_task', task())
    return { promise: Promise.resolve(), abort() {} }
  } })
  await reopened.state.initializeChatRecovery()
  assert.equal(reopened.calls.posts.length, 0); assert.equal(reopened.state.chatCanReconcile, true)
  reopened.state.input = '无关新问题'; reopened.state.activeId = 'e'.repeat(32); reopened.state.uiPreviewId = null; reopened.state.uiExcludes = []
  await reopened.state.reconcileChatTurn()
  assert.equal(reopened.calls.posts.length, 1); assert.equal(reopened.calls.posts[0].key, original.key); assert.deepEqual(reopened.calls.posts[0].request, original.request)
  assert.equal(reopened.state.activeId, C); assert.equal(reopened.state.chatPending, null)
  assert.equal(reopened.state.messages.filter(item => item.role === 'user').length, 1); assert.equal(reopened.state.messages.filter(item => item.cardType === 'analysis_task').length, 1)
})

test('PLANNING只GET等待，转为READY后仍等待明确核对动作', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-planning-state')
  let status = 'PLANNING'
  const { state, calls } = chatHarness(env, { fetchChatTurn: async key => receipt(key, status) })
  await state.initializeChatRecovery(); assert.equal(state.chatCanReconcile, false); assert.equal(calls.timers.size, 1)
  await state.reconcileChatTurn(); assert.equal(calls.posts.length, 0)
  const poll = [...calls.timers.values()][0]; status = 'READY'; await poll()
  assert.equal(state.chatCanReconcile, true); assert.equal(state.chatPending.key, saved.key); assert.equal(calls.posts.length, 0); assert.equal(calls.timers.size, 0)
})

test('404恢复隐藏原展示且不自动POST，显式核对收到410继续保留原key并停止新请求', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-hidden-receipt')
  const { state, calls } = chatHarness(env, { fetchChatTurn: async () => { throw serverError(404) }, streamChat: () => ({ promise: Promise.reject(serverError(410)) }) })
  state.messages = [{ role: 'assistant', content: '旧的敏感答案' }]; await state.initializeChatRecovery()
  assert.equal(state.messages.length, 0); assert.equal(calls.posts.length, 0); assert.equal(state.chatCanReconcile, true)
  await state.reconcileChatTurn(); assert.equal(calls.posts.length, 1); assert.equal(calls.posts[0].key, saved.key)
  assert.equal(state.messages.length, 0); assert.equal(state.chatPending.key, saved.key); assert.equal(state.chatCanReconcile, false)
  await state.send('不能自动另起请求'); assert.equal(calls.posts.length, 1)
})

test('权限和期限读取失败清空已显示问题与卡片，不从缓存恢复答案', async () => {
  for (const code of [403, 410]) {
    const env = environment(); await env.store().create(request(), 'chat-authority-fail')
    const { state, calls } = chatHarness(env, { fetchChatTurn: async () => { throw serverError(code) } })
    state.messages = [{ role: 'user', content: '先前问题' }, { role: 'card', cardType: 'analysis_task', payload: task() }]
    state.input = '先前输入'; await state.initializeChatRecovery()
    assert.equal(state.messages.length, 0); assert.equal(state.input, ''); assert.equal(state.activeId, null); assert.equal(state.chatCanReconcile, false); assert.equal(calls.posts.length, 0)
  }
})

test('同一用户更换令牌后旧SSE被中止，迟到事件不能显示或恢复私有内容', async () => {
  const env = environment(); let event, finish, aborted = false
  const { state, calls } = chatHarness(env, { streamChat: (_, handler) => { event = handler; return { promise: new Promise(resolve => { finish = resolve }), abort: () => { aborted = true } } } })
  const sending = state.send(request().message); await until(() => event)
  event('conversation', { requestId: R, conversationId: C, replayed: false })
  env.identity.token = 'test-session-b'; event('text', { delta: '旧会话私有内容' }); event('analysis_task', task()); finish(); await sending
  assert.equal(aborted, true); assert.equal(state.messages.length, 0); assert.equal(state.activeId, null); assert.equal(calls.gets.length, 0)
  const reopened = chatHarness(env); await reopened.state.initializeChatRecovery(); assert.equal(reopened.state.chatPending, null); assert.equal(reopened.state.messages.length, 0)
})

test('同用户换token期间返回的GET不能恢复原会话，也不能清掉新登录保存记录', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-before-get')
  let finish
  const { state, calls } = chatHarness(env, { fetchChatTurn: () => new Promise(resolve => { finish = resolve }) })
  const reading = state.initializeChatRecovery(); await until(() => finish)
  env.identity.token = 'test-session-b'; const newer = await env.store().create(request(), 'chat-after-get')
  finish(receipt(saved.key)); await reading
  assert.equal(state.messages.length, 0); assert.equal(state.activeId, null); assert.equal(calls.posts.length, 0); assert.equal((await env.store().read()).pending.key, newer.key)
})

test('新会话代次撤销迟到轮次GET，不改变新页面或清理原保存记录', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-old-sequence'); let finish
  const { state } = chatHarness(env, { fetchChatTurn: () => new Promise(resolve => { finish = resolve }) })
  const reading = state.initializeChatRecovery(); await until(() => finish)
  state.turnVersion++; state.activeId = 'e'.repeat(32); state.messages = [{ role: 'user', content: '新页面' }]
  finish(receipt(saved.key)); await reading
  assert.equal(state.activeId, 'e'.repeat(32)); assert.equal(state.messages[0].content, '新页面'); assert.equal((await env.store().read()).pending.key, saved.key)
})

test('存储不可写时保留输入，不乐观显示已发送消息，也不发送网络请求', async () => {
  const env = environment(), { state, calls } = chatHarness(env)
  state._chatPendingStore = env.store({ getStorage: () => { throw new Error('disabled storage') } }); state.input = '未发送的问题'
  await state.send(); assert.equal(calls.posts.length, 0); assert.equal(state.input, '未发送的问题'); assert.equal(state.messages.length, 0); assert.match(state.chatPendingError, /存储/)
})

test('结构性拒绝只清原保存记录并允许明确修改，不自动创建另一轮', async () => {
  const { state, calls, env } = chatHarness(undefined, { streamChat: () => ({ promise: Promise.reject(serverError(422)) }) })
  await state.send(request().message); assert.equal(calls.posts.length, 1); assert.equal(state.chatPending, null); assert.equal(env.values.size, 0)
  assert.match(state.chatPendingNotice, /服务器已拒绝/); assert.equal(state.busy, false)
})

test('409响应按准确key读取已有轮次，不能把冲突当成未执行后创建新key', async () => {
  const { state, calls } = chatHarness(undefined, { streamChat: () => ({ promise: Promise.reject(serverError(409)) }), fetchChatTurn: async key => receipt(key, 'PLANNING') })
  await state.send(request().message); assert.equal(calls.gets.length, 1); assert.equal(state.chatReceipt.status, 'PLANNING'); assert.ok(state.chatPending)
  await state.send('重新描述'); assert.equal(calls.posts.length, 1); assert.equal(state.chatCanReconcile, false)
})

test('关闭抽屉后停止轮次轮询，重新打开仅GET并保留原key', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-drawer-reopen')
  const { state, component, calls } = chatHarness(env, { fetchChatTurn: async key => receipt(key, 'PLANNING') })
  await state.initializeChatRecovery(); assert.equal(calls.timers.size, 1)
  state.visible = false; component.watch.visible.call(state, false); assert.equal(calls.timers.size, 0)
  state.visible = true; await state.initializeChatRecovery()
  assert.equal(calls.posts.length, 0); assert.equal(state.chatPending.key, saved.key); assert.equal(calls.gets.length, 2)
})

test('已中断与失败回执释放未知提交，明确重新发送才生成新key', async () => {
  for (const status of ['INTERRUPTED', 'FAILED']) {
    const env = environment(), saved = await env.store().create(request(), 'chat-ended-request')
    const { state, calls } = chatHarness(env, { fetchChatTurn: async key => receipt(key, status, { message: '原轮次已结束' }) })
    await state.initializeChatRecovery(); assert.equal(state.chatPending, null); assert.equal(calls.posts.length, 0)
    await state.send('明确发起新的业务问题'); assert.equal(calls.posts.length, 1); assert.notEqual(calls.posts[0].key, saved.key)
  }
})

test('不属于原请求的回执不会清理保存记录或自动重放', async () => {
  const env = environment(), saved = await env.store().create({ ...request(), conversationId: C }, 'chat-wrong-receipt')
  const { state, calls } = chatHarness(env, { fetchChatTurn: async key => receipt(key, 'SUBMITTED', { conversationId: 'e'.repeat(32) }) })
  await state.initializeChatRecovery(); assert.equal(state.chatPending.key, saved.key); assert.equal(state.chatCanReconcile, false); assert.equal(calls.posts.length, 0); assert.equal(state.messages.length, 0)
})

test('提交记录在服务器确认后无法清理时仍保留原key，存储恢复后GET即可完成核对', async () => {
  const env = environment(), { state, calls } = chatHarness(env), realStore = env.store()
  let fail = true
  state._chatPendingStore = { ...realStore, async clear(value) { if (fail) throw Object.assign(new Error('存储暂不可写'), { pendingFailure: 'UNAVAILABLE' }); return realStore.clear(value) } }
  await state.send(request().message); assert.ok(state.chatPending); assert.equal(calls.posts.length, 1)
  fail = false; await state.initializeChatRecovery(); assert.equal(state.chatPending, null); assert.equal(calls.posts.length, 1); assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, 1)
})

test('轮次GET失权后流中暂存的业务卡片不能在finally重新出现', async () => {
  const { state } = chatHarness(undefined, { fetchChatTurn: async () => { throw serverError(403) }, streamChat: (_, event) => {
    event('conversation', { requestId: R, conversationId: C, replayed: false }); event('business_query', { rows: [{ amount: 'private' }] })
    return { promise: Promise.resolve(), abort() {} }
  } })
  await state.send(request().message); assert.equal(state.messages.length, 0); assert.equal(state.chatUnavailable, true); assert.ok(state.chatPending)
})

test('GET回执与已收到的conversation事件轮次不同则保留未知提交', async () => {
  const { state, calls } = chatHarness(undefined, { fetchChatTurn: async key => receipt(key, 'COMPLETED', { requestId: 'e'.repeat(32) }), streamChat: (_, event) => {
    event('conversation', { requestId: R, conversationId: C, replayed: false }); return { promise: Promise.resolve(), abort() {} }
  } })
  await state.send(request().message); assert.ok(state.chatPending); assert.equal(state.chatCanReconcile, false); assert.equal(calls.posts.length, 1); assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, 0)
})

test('只有用户明确结束不可访问的本页核对才释放输入，动作本身不创建或重发任务', async () => {
  const env = environment(); await env.store().create(request(), 'chat-explicit-finish')
  const { state, calls } = chatHarness(env, { fetchChatTurn: async () => { throw serverError(410) } })
  await state.initializeChatRecovery(); assert.equal(state.busy, true); assert.ok(state.chatPending)
  await state.finishUnavailableTurn(); assert.equal(state.chatPending, null); assert.equal(state.busy, false); assert.equal(state.activeId, null); assert.equal(state.input, ''); assert.equal(calls.posts.length, 0); assert.equal(env.values.size, 0)
  await state.send('用户明确提出的新问题'); assert.equal(calls.posts.length, 1); assert.notEqual(calls.posts[0].key, 'chat-explicit-finish')
})

test('首帧或尾帧丢失后PLANNING转完成会补读普通结果，历史按消息标识稳定去重', async () => {
  for (const loss of ['head', 'tail']) {
    let complete = false
    const history = [
      { id: 41, role: 'user', requestId: null, content: '查询销售报表' },
      { id: 42, role: 'assistant', requestId: null, content: '已取得销售结果。' },
      { id: 43, role: 'card', requestId: null, cardType: 'business_query', payload: { rows: [{ docNo: '001' }] } }
    ]
    const { state, calls } = chatHarness(undefined, {
      streamChat: (_, event) => {
        if (loss === 'tail') { event('conversation', { requestId: R, conversationId: C, replayed: false }); event('text', { delta: '已取得' }) }
        return { promise: Promise.reject(new Error('lost ' + loss + ' frame')), abort() {} }
      },
      fetchChatTurn: async key => receipt(key, complete ? 'COMPLETED' : 'PLANNING'),
      fetchMessages: async () => complete ? history : history.slice(0, 1)
    })
    await state.send('查询销售报表'); assert.equal(state.chatReceipt.status, 'PLANNING'); assert.equal(calls.histories.length, 1)
    const poll = [...calls.timers.values()][0]; complete = true; await poll()
    assert.equal(calls.posts.length, 1); assert.equal(calls.histories.length, 2); assert.equal(state.chatPending, null)
    assert.deepEqual(plain(state.messages.map(item => item.id)), [41, 42, 43]); assert.equal(state.messages[1].content, '已取得销售结果。')
    await poll(); assert.equal(calls.gets.length, 2); assert.equal(state.messages.filter(item => item.cardType === 'business_query').length, 1)
  }
})

test('首次接收但由并发执行者完成的无结果流也会恢复历史，不能只信replayed标志', async () => {
  const { state, calls } = chatHarness(undefined, {
    streamChat: (_, event, key) => { event('conversation', { requestId: R, conversationId: C, replayed: false }); event('turn_receipt', receipt(key, 'COMPLETED')); return { promise: Promise.resolve(), abort() {} } },
    fetchMessages: async () => [{ id: 11, role: 'user', content: '如何查询报表' }, { id: 12, role: 'assistant', content: '可以先选择可访问的报表。' }]
  })
  await state.send('如何查询报表'); assert.equal(calls.posts.length, 1); assert.equal(calls.histories.length, 1); assert.equal(calls.gets.length, 0)
  assert.deepEqual(plain(state.messages.map(item => item.id)), [11, 12]); assert.equal(state.chatPending, null)
})

test('GET404后用户显式核对首次被接收仍恢复完整历史，文字相同的不同历史消息不会误合并', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-first-after-404')
  const { state, calls } = chatHarness(env, {
    fetchChatTurn: async () => { throw serverError(404) },
    streamChat: (_, event, key) => {
      event('conversation', { requestId: R, conversationId: C, replayed: false }); event('text', { delta: '最新回答' }); event('analysis_task', task()); event('turn_receipt', receipt(key))
      return { promise: Promise.resolve(), abort() {} }
    },
    fetchMessages: async () => [
      { id: 1, role: 'user', content: request().message }, { id: 2, role: 'assistant', content: '历史回答' },
      { id: 3, role: 'user', content: request().message }, { id: 4, role: 'assistant', content: '最新回答' },
      { id: 5, role: 'card', requestId: R, cardType: 'analysis_task', payload: task() }
    ]
  })
  await state.initializeChatRecovery(); assert.equal(calls.posts.length, 0); assert.equal(state.chatCanReconcile, true)
  await state.reconcileChatTurn(); assert.equal(calls.posts.length, 1); assert.equal(calls.posts[0].key, saved.key); assert.equal(calls.histories.length, 1)
  assert.equal(state.messages.filter(item => item.role === 'user').length, 2); assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, 1)
  assert.deepEqual(plain(state.messages.map(item => item.id)), [1, 2, 3, 4, 5]); assert.equal(state.chatPending, null)
})

test('完整实时结果与完成回执不被尚未投影的历史覆盖', async () => {
  const { state, calls } = chatHarness(undefined, {
    streamChat: (_, event, key) => {
      event('conversation', { requestId: R, conversationId: C, replayed: false }); event('text', { delta: '销售查询完成。' }); event('business_query', { rows: [{ docNo: '001' }] }); event('turn_receipt', receipt(key, 'COMPLETED'))
      return { promise: Promise.resolve(), abort() {} }
    },
    fetchMessages: async () => []
  })
  await state.send('查询销售报表'); assert.equal(calls.histories.length, 0); assert.equal(state.messages.find(item => item.role === 'assistant').content, '销售查询完成。')
  assert.equal(state.messages.filter(item => item.cardType === 'business_query').length, 1); assert.equal(state.chatPending, null)
})

test('冻结依据失效的error后FAILED回执保留权威原因，历史读取不会恢复核对入口', async () => {
  for (const failHistory of [false, true]) {
    const env = environment(); await env.store().create(request(), 'chat-prepared-invalid')
    const reason = '原冻结依据已变化，需发送新的完整问题。'
    let fail = false
    const { state, calls } = chatHarness(env, {
      fetchChatTurn: async key => receipt(key, 'READY'),
      fetchMessages: async () => { if (fail && failHistory) throw new Error('history unavailable'); return [{ id: 1, role: 'user', content: request().message }] },
      streamChat: (_, event, key) => {
        event('conversation', { requestId: R, conversationId: C, replayed: true }); event('error', { message: reason }); event('turn_receipt', receipt(key, 'FAILED', { message: reason, canReconcile: false })); event('done', {})
        return { promise: Promise.resolve(), abort() {} }
      }
    })
    await state.initializeChatRecovery(); assert.equal(state.chatCanReconcile, true); fail = true; await state.reconcileChatTurn()
    assert.equal(state.chatPendingNotice, reason); assert.equal(state.chatCanReconcile, false); assert.equal(state.chatPending, null); assert.equal(calls.posts.length, 1)
  }
})

test('SUBMITTED回执不再允许读取任务时保留原因，并清掉同轮历史和流中暂存引用', async () => {
  const reason = '原分析任务已不可读取，请发送新的完整问题'
  const { state, calls } = chatHarness(undefined, { streamChat: (_, event, key) => {
    event('conversation', { requestId: R, conversationId: C, replayed: false }); event('analysis_task', task()); event('turn_receipt', receipt(key, 'SUBMITTED', { analysisTask: null, message: reason, canReconcile: false }))
    return { promise: Promise.resolve(), abort() {} }
  } })
  state.messages = [{ role: 'card', cardType: 'analysis_task', requestId: R, payload: task() }]
  await state.send(request().message)
  assert.equal(state.chatPendingNotice, reason); assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, 0); assert.equal(state.chatPending, null); assert.equal(calls.posts.length, 1)
})

test('SUBMITTED不可读任务的原因优先于历史读取失败提示，清理原key后仍可见', async () => {
  const env = environment(); await env.store().create(request(), 'chat-submitted-hidden')
  const reason = '原分析任务已不可读取，请发送新的完整问题'
  const { state, calls } = chatHarness(env, {
    fetchChatTurn: async key => receipt(key, 'SUBMITTED', { analysisTask: null, message: reason, canReconcile: false }),
    fetchMessages: async () => { throw new Error('history offline') }
  })
  state.messages = [{ role: 'card', cardType: 'analysis_task', requestId: R, payload: task() }]
  await state.initializeChatRecovery(); assert.equal(state.chatPendingNotice, reason); assert.equal(state.chatPending, null); assert.equal(state.messages.length, 0); assert.equal(calls.posts.length, 0)
})

test('历史投影完整性是必填布尔协议，不能把旧缺省或字符串解释为已完成', () => {
  const pending = { key: 'chat-history-contract', request: request() }
  for (const historyReady of [undefined, null, 'true', 1]) assert.throws(() => receiptContext.readChatReceipt(receipt(pending.key, 'COMPLETED', { historyReady }), pending), /回执/)
  assert.equal(receiptContext.readChatReceipt(receipt(pending.key, 'COMPLETED', { historyReady: false }), pending).historyReady, false)
})

test('终态历史仍在投影时保留原消息和key，只GET直到完整历史可替换且清理', async () => {
  for (const status of ['COMPLETED', 'FAILED', 'INTERRUPTED']) {
    const env = environment(), saved = await env.store().create(request(), 'chat-history-sync-' + status)
    let ready = false
    const { state, calls } = chatHarness(env, {
      fetchChatTurn: async key => receipt(key, status, { historyReady: ready, message: status === 'COMPLETED' ? null : '原轮次已结束' }),
      fetchMessages: async () => [{ id: 1, role: 'user', content: request().message }, { id: 2, role: 'assistant', content: '已投影的完整回复' }]
    })
    state.messages = [{ key: 'local-user', role: 'user', content: request().message }, { key: 'local-answer', role: 'assistant', content: '已收到的实时结果' }]
    await state.initializeChatRecovery(); assert.equal(state.chatPending.key, saved.key); assert.equal(calls.histories.length, 0)
    assert.equal(state.messages[1].content, '已收到的实时结果'); assert.match(state.chatPendingNotice, /同步/); assert.equal(calls.timers.size, 1)
    await state.send('不能覆盖正在同步的请求'); await state.reconcileChatTurn(); assert.equal(calls.posts.length, 0)
    await [...calls.timers.values()][0](); assert.equal(calls.histories.length, 0); assert.equal(state.chatPending.key, saved.key)
    ready = true; await [...calls.timers.values()][0](); assert.equal(calls.histories.length, 1); assert.equal(state.chatPending, null)
    assert.deepEqual(plain(state.messages.map(item => item.id)), [1, 2]); assert.equal(state.messages[1].content, '已投影的完整回复'); assert.equal(calls.posts.length, 0)
  }
})

test('尾帧丢失遇到历史投影延迟不会丢掉已收到的完整文字和卡片，完成后只有持久化消息', async () => {
  let ready = false
  const { state, calls } = chatHarness(undefined, {
    streamChat: (_, event) => {
      event('conversation', { requestId: R, conversationId: C, replayed: false }); event('text', { delta: '实时销售结果' }); event('business_query', { rows: [{ docNo: '001' }] })
      return { promise: Promise.reject(new Error('lost terminal frame')), abort() {} }
    },
    fetchChatTurn: async key => receipt(key, 'COMPLETED', { historyReady: ready }),
    fetchMessages: async () => [{ id: 1, role: 'user', content: '查询销售报表' }, { id: 2, role: 'assistant', content: '实时销售结果' }, { id: 3, role: 'card', cardType: 'business_query', payload: { rows: [{ docNo: '001' }] } }]
  })
  await state.send('查询销售报表'); assert.ok(state.chatPending); assert.equal(calls.histories.length, 0); assert.equal(state.messages.find(item => item.role === 'assistant').content, '实时销售结果'); assert.equal(state.messages.filter(item => item.cardType === 'business_query').length, 1)
  ready = true; await [...calls.timers.values()][0](); assert.equal(state.chatPending, null); assert.deepEqual(plain(state.messages.map(item => item.id)), [1, 2, 3]); assert.equal(calls.posts.length, 1)
})

test('SUBMITTED引用可先显示，投影未完成时不覆盖历史；READY仍只读等待', async () => {
  for (const status of ['SUBMITTED', 'READY']) {
    const env = environment(); await env.store().create(request(), 'chat-reference-sync-' + status)
    let ready = false
    const { state, calls } = chatHarness(env, { fetchChatTurn: async key => receipt(key, status, { historyReady: ready }) })
    await state.initializeChatRecovery(); assert.equal(calls.histories.length, 0); assert.ok(state.chatPending); assert.equal(state.chatCanReconcile, false)
    assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, status === 'SUBMITTED' ? 1 : 0)
    await state.reconcileChatTurn(); assert.equal(calls.posts.length, 0)
    ready = true; await [...calls.timers.values()][0](); assert.equal(calls.histories.length, 1); assert.equal(calls.posts.length, 0)
    if (status === 'SUBMITTED') { assert.equal(state.chatPending, null); assert.equal(state.messages.filter(item => item.cardType === 'analysis_task').length, 1) }
    else assert.equal(state.chatCanReconcile, true)
  }
})

test('恢复历史在新轮次代次开始后返回，不能覆盖新页面或清理原记录', async () => {
  const env = environment(), saved = await env.store().create(request(), 'chat-history-fence'); let complete
  const { state } = chatHarness(env, { fetchMessages: () => new Promise(resolve => { complete = resolve }) })
  const reading = state.initializeChatRecovery(); await until(() => complete)
  state.turnVersion++; state.activeId = 'e'.repeat(32); state.messages = [{ role: 'user', content: '新页面' }]
  complete([{ id: 1, role: 'assistant', content: '迟到的旧消息' }]); await reading
  assert.equal(state.messages[0].content, '新页面'); assert.equal(state.activeId, 'e'.repeat(32)); assert.equal((await env.store().read()).pending.key, saved.key)
})
