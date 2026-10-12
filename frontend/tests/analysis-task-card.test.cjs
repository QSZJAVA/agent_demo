/** 分析引用卡片的当前授权读取与竞态回归；执行真实组件方法，受控GET不代表真实模型或浏览器证据。 */
const { test } = require('node:test'), assert = require('node:assert/strict')
const { cardHarness, task, tick, until, RUN } = require('./helpers/unified-chat-harness.cjs')

/** 用显式时间推进真实组件计时器；挂起的GET不会阻塞时钟，也不以真实睡眠掩盖过期边界。 */
function timedCard() {
  let now = 0, wallOffset = 0, nextTimer = 0
  const timers = new Map(), listeners = new Set()
  const clock = {
    timers,
    Date: class extends Date { static now() { return now + wallOffset } },
    performance: { now: () => now },
    shiftWall(duration) { wallOffset += duration },
    setTimeout(callback, delay = 0) { const id = ++nextTimer; timers.set(id, { at: now + delay, callback }); return id },
    clearTimeout(id) { timers.delete(id) },
    async advance(duration) {
      const target = now + duration
      while (true) {
        const next = [...timers.entries()].filter(([, timer]) => timer.at <= target).sort((left, right) => left[1].at - right[1].at || left[0] - right[0])[0]
        if (!next) break
        now = next[1].at; timers.delete(next[0]); next[1].callback(); await tick()
      }
      now = target; await tick()
    }
  }
  const document = {
    hidden: false,
    addEventListener(type, listener) { assert.equal(type, 'visibilitychange'); listeners.add(listener) },
    removeEventListener(type, listener) { assert.equal(type, 'visibilitychange'); listeners.delete(listener) },
    changeVisibility(hidden) { this.hidden = hidden; for (const listener of [...listeners]) listener() }
  }
  return { ...cardHarness({ Date: clock.Date, performance: clock.performance, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout, document }), clock, document, listeners }
}

test('卡片答案只来自GET，引用载荷内的答案不显示，终态读取不创建新任务', async () => {
  const { state, calls, run } = cardHarness(); state.payload = { ...task(), answer: { claims: [{ text: '不能信任的缓存' }] } }
  const reading = state.reload(); assert.equal(state.run, null); assert.equal(calls.reads[0].id, RUN)
  calls.reads[0].resolve(run()); await reading
  assert.equal(state.run.answer.claims[0].text, '受控依据'); assert.equal(calls.cancellations.length, 0); assert.equal(calls.timers.size, 1)
  state.open(); assert.deepEqual(calls.opened, [{ type: 'open', id: RUN }])
})

test('中性不可用卡片和缺失引用不会读取其他任务', () => {
  for (const modify of [state => { state.cardState = 'UNAVAILABLE' }, state => { delete state.payload.runId }, state => { state.payload.requestId += '\n' }, state => { state.payload.mode = 'UNRESTRICTED' }]) {
    const { state, calls } = cardHarness(); modify(state); state.reload()
    assert.equal(calls.reads.length, 0); assert.equal(state.run, null); assert.match(state.error, /不可查看/)
  }
})

test('任务范围和引用不一致时不显示任何返回答案', async () => {
  for (const modify of [value => { value.id = 'e'.repeat(32) }, value => { value.request.companyCode = 'B' }, value => { value.request.reportId = 'rpt-other' }, value => { value.request.mode = 'CURRENT_DATA' }]) {
    const { state, calls, run } = cardHarness(), value = run(); modify(value)
    const reading = state.reload(); calls.reads[0].resolve(value); await reading
    assert.equal(state.run, null); assert.match(state.error, /原答案已隐藏/)
  }
})

test('权限或资料版本读取失败清空上次成功答案，不继续显示最后成功值', async () => {
  const { state, calls, run } = cardHarness(), loading = state.reload(); calls.reads[0].resolve(run('RUNNING')); await loading
  assert.ok(state.run.answer); const polling = state.read(state._seq); calls.reads[1].reject(new Error('403 changed permission')); await polling
  assert.equal(state.run, null); assert.match(state.error, /原答案已隐藏/); assert.equal(calls.timers.size, 0)
})

test('隐藏和销毁撤销读取并停止轮询，迟到的成功或失败不恢复旧答案', async () => {
  for (const stop of [(state, component) => { state.active = false; component.watch.active.call(state) }, (state, component) => component.beforeDestroy.call(state)]) {
    const { state, component, calls, run } = cardHarness(), reading = state.reload()
    stop(state, component); calls.reads[0].resolve(run()); await reading
    assert.equal(state.run, null); assert.equal(calls.timers.size, 0); assert.equal(calls.reads.length, 1)
  }
})

test('重新显示只GET核对同一任务，旧页面响应不能覆盖新的读取', async () => {
  const { state, component, calls, run } = cardHarness(), first = state.reload()
  state.active = false; component.watch.active.call(state); state.active = true
  const second = state.reload(); calls.reads[1].resolve(run('COMPLETED', 3)); await second
  calls.reads[0].resolve(run('RUNNING', 1)); await first
  assert.equal(state.run.version, 3); assert.equal(state.run.status, 'COMPLETED'); assert.equal(calls.cancellations.length, 0)
})

test('同用户的新令牌撤销旧任务内容，迟到GET不能写入新登录', async () => {
  const { state, calls, identity, run } = cardHarness(), first = state.reload(); calls.reads[0].resolve(run('RUNNING')); await first
  const polling = state.read(state._seq); identity.token = 'test-session-b'; calls.reads[1].resolve(run('COMPLETED', 2)); await polling
  assert.equal(state.run, null); assert.equal(calls.timers.size, 0); assert.match(state.error, /登录会话已变化/)
})

test('取消确认后的新读取不会被旧RUNNING轮询覆盖', async () => {
  const { state, calls, run } = cardHarness(), first = state.reload(); calls.reads[0].resolve(run('RUNNING', 1)); await first
  const oldPoll = state.read(state._seq), cancellation = state.cancel(); assert.equal(calls.cancellations.length, 1)
  calls.cancellations[0].resolve(run('CANCELLED', 3)); await until(() => calls.reads.length === 3)
  calls.reads[2].resolve(run('CANCELLED', 3)); await cancellation
  calls.reads[1].resolve(run('RUNNING', 2)); await oldPoll
  assert.equal(state.run.status, 'CANCELLED'); assert.equal(state.run.version, 3); assert.equal(calls.timers.size, 1)
})

test('已确认终态的旧版本响应不能倒退任务版本', async () => {
  const { state, calls, run } = cardHarness(), first = state.reload(); calls.reads[0].resolve(run('COMPLETED', 5)); await first
  const old = state.read(state._seq); calls.reads[1].resolve(run('RUNNING', 4)); await old
  assert.equal(state.run.status, 'COMPLETED'); assert.equal(state.run.version, 5); assert.equal(calls.timers.size, 1)
})

test('所有终态在29999毫秒仍可见，30000毫秒先撤下再GET同一任务', async () => {
  for (const status of ['COMPLETED', 'CLARIFY', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED']) {
    const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run(status, 5)); await initial
    await clock.advance(29999)
    assert.equal(state.run.status, status); assert.equal(calls.reads.length, 1)
    await clock.advance(1)
    assert.equal(state.run, null); assert.equal(state.loading, true); assert.equal(calls.reads.length, 2); assert.equal(calls.reads[1].id, RUN)
    calls.reads[1].resolve(run(status, 5)); await tick()
    assert.equal(state.run.status, status); assert.equal(calls.cancellations.length, 0); assert.equal(clock.timers.size, 1)
  }
})

test('同令牌下撤权或知识版本变化最多30秒撤下答案，拒绝后不自动重试', async () => {
  for (const reason of ['403 permission revoked', '409 knowledge published', '410 evidence expired']) {
    const { state, calls, run, clock, identity } = timedCard(), token = identity.token, initial = state.reload(); calls.reads[0].resolve(run()); await initial
    await clock.advance(30000)
    assert.equal(state.run, null); assert.equal(calls.reads.length, 2); assert.equal(identity.token, token)
    calls.reads[1].reject(new Error(reason)); await tick()
    assert.equal(state.run, null); assert.match(state.error, /原答案已隐藏/); assert.equal(clock.timers.size, 0)
    await clock.advance(60000)
    assert.equal(calls.reads.length, 2); assert.equal(calls.cancellations.length, 0)
  }
})

test('运行态轮询挂起也会到期撤下答案，过期前发出的成功响应不能复活内容', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('RUNNING', 5)); await initial
  await clock.advance(1200)
  assert.equal(calls.reads.length, 2); assert.equal(state.run.version, 5)
  await clock.advance(28799)
  assert.equal(state.run.version, 5); assert.equal(calls.reads.length, 2)
  await clock.advance(1)
  assert.equal(state.run, null); assert.equal(calls.reads.length, 3); assert.equal(calls.reads[2].id, RUN)
  calls.reads[1].resolve(run('COMPLETED', 6)); await tick()
  assert.equal(state.run, null); assert.equal(state.loading, true)
  calls.reads[2].reject(new Error('network unavailable')); await tick()
  assert.equal(state.run, null); assert.equal(state.loading, false); assert.equal(clock.timers.size, 0); assert.equal(calls.cancellations.length, 0)
})

test('过期后的GET持续挂起或断网时不保留旧答案，也不产生重叠轮询', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run()); await initial
  await clock.advance(30000); assert.equal(state.run, null); assert.equal(calls.reads.length, 2)
  await clock.advance(60000)
  assert.equal(state.run, null); assert.equal(calls.reads.length, 2); assert.equal(clock.timers.size, 0)
  calls.reads[1].reject(new Error('network unavailable')); await tick()
  assert.equal(state.run, null); assert.match(state.error, /原答案已隐藏/); assert.equal(calls.cancellations.length, 0)
})

test('超过30秒才返回的GET不重新展示已经超过本次核验有效期的内容', async () => {
  const { state, calls, run, clock } = timedCard(), reading = state.reload()
  await clock.advance(30001); calls.reads[0].resolve(run('COMPLETED', 5)); await reading
  assert.equal(state.run, null); assert.equal(state.loading, false); assert.match(state.error, /原答案已隐藏/); assert.equal(clock.timers.size, 0)
})

test('展示有效期从GET开始核验计时，网络延迟不会延长原授权窗口', async () => {
  const { state, calls, run, clock } = timedCard(), reading = state.reload()
  await clock.advance(20000); calls.reads[0].resolve(run()); await reading
  await clock.advance(9999); assert.ok(state.run); assert.equal(calls.reads.length, 1)
  await clock.advance(1); assert.equal(state.run, null); assert.equal(calls.reads.length, 2)
})

test('运行态成功轮询更新展示有效期，后续挂起不沿用早先的计时器', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('RUNNING', 1)); await initial
  await clock.advance(1200); calls.reads[1].resolve(run('RUNNING', 2)); await tick()
  await clock.advance(28800)
  assert.equal(state.run.version, 2); assert.equal(calls.reads.length, 3)
  await clock.advance(1199); assert.equal(state.run.version, 2)
  await clock.advance(1); assert.equal(state.run, null); assert.equal(calls.reads.length, 4); assert.equal(calls.cancellations.length, 0)
})

test('过期和显式重读仍保留同一引用的最高版本，低版本不能恢复旧答案', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('COMPLETED', 5)); await initial
  await clock.advance(30000); calls.reads[1].resolve(run('RUNNING', 4)); await tick()
  assert.equal(state.run, null); assert.equal(clock.timers.size, 0)
  const reading = state.reload(); calls.reads[2].resolve(run('RUNNING', 3)); await reading
  assert.equal(state.run, null)
  const refreshed = state.reload(); calls.reads[3].resolve(run('COMPLETED', 5)); await refreshed
  assert.equal(state.run.version, 5); assert.equal(state.run.status, 'COMPLETED'); assert.equal(calls.cancellations.length, 0)
})

test('旧版本返回不会延长当前已确认答案的展示有效期', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('COMPLETED', 5)); await initial
  await clock.advance(20000); const reading = state.read(state._seq); calls.reads[1].resolve(run('RUNNING', 4)); await reading
  assert.equal(state.run.version, 5)
  await clock.advance(9999); assert.equal(state.run.version, 5)
  await clock.advance(1); assert.equal(state.run, null); assert.equal(calls.reads.length, 3)
})

test('同一显示代次内较早GET的迟到成功或失败不能覆盖最新读取结果', async () => {
  for (const rejectOld of [false, true]) {
    const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('RUNNING', 1)); await initial
    const older = state.read(state._seq), newer = state.read(state._seq)
    calls.reads[2].resolve(run('COMPLETED', 5)); await newer
    if (rejectOld) calls.reads[1].reject(new Error('old read failed')); else calls.reads[1].resolve(run('RUNNING', 6))
    await older
    assert.equal(state.run.status, 'COMPLETED'); assert.equal(state.run.version, 5); assert.equal(state.error, ''); assert.equal(clock.timers.size, 1)
  }
})

test('最新GET拒绝后旧并发成功响应不能复活原答案', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('RUNNING', 1)); await initial
  const older = state.read(state._seq), newer = state.read(state._seq)
  calls.reads[2].reject(new Error('403 permission revoked')); await newer
  calls.reads[1].resolve(run('COMPLETED', 5)); await older
  assert.equal(state.run, null); assert.match(state.error, /原答案已隐藏/); assert.equal(clock.timers.size, 0)
})

test('隐藏卡片立即清空答案并停止所有计时，重新显示只GET不执行任务', async () => {
  const { state, calls, component, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run()); await initial
  await clock.advance(29999); state.active = false; component.watch.active.call(state)
  assert.equal(state.run, null); assert.equal(clock.timers.size, 0)
  await clock.advance(60000); assert.equal(calls.reads.length, 1)
  state.active = true; component.watch.active.call(state); assert.equal(calls.reads.length, 2); assert.equal(state.run, null)
  calls.reads[1].resolve(run('COMPLETED', 2)); await tick()
  assert.equal(state.run.version, 2); assert.equal(calls.cancellations.length, 0)
})

test('浏览器页面隐藏也立即撤下答案，恢复和销毁正确管理可见性监听', async () => {
  const { state, calls, component, run, clock, document, listeners } = timedCard(); component.created.call(state)
  assert.equal(listeners.size, 1); calls.reads[0].resolve(run()); await tick()
  document.changeVisibility(true); assert.equal(state.run, null); assert.equal(clock.timers.size, 0)
  await clock.advance(60000); assert.equal(calls.reads.length, 1)
  document.changeVisibility(false); assert.equal(calls.reads.length, 2); assert.equal(state.run, null)
  calls.reads[1].resolve(run('COMPLETED', 2)); await tick(); assert.equal(state.run.version, 2)
  component.beforeDestroy.call(state)
  assert.equal(state.run, null); assert.equal(clock.timers.size, 0); assert.equal(listeners.size, 0)
  document.changeVisibility(true); document.changeVisibility(false); await clock.advance(60000)
  assert.equal(calls.reads.length, 2); assert.equal(calls.cancellations.length, 0)
})

test('终态答案到期时发现登录已变化，仅清理展示而不沿用新身份读取旧任务', async () => {
  const { state, calls, run, clock, identity } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run()); await initial
  identity.token = 'test-session-b'; await clock.advance(30000)
  assert.equal(state.run, null); assert.equal(calls.reads.length, 1); assert.equal(clock.timers.size, 0); assert.match(state.error, /登录会话已变化/)
})

test('系统时间校准不延长或误判GET的30秒展示窗口', async () => {
  for (const adjustment of [-3600000, 3600000]) {
    const { state, calls, run, clock } = timedCard(), reading = state.reload()
    await clock.advance(20000); clock.shiftWall(adjustment); calls.reads[0].resolve(run()); await reading
    assert.ok(state.run)
    await clock.advance(9999); assert.ok(state.run)
    await clock.advance(1); assert.equal(state.run, null); assert.equal(calls.reads.length, 2)
  }
})

test('更换稳定引用后可以显示新任务自身的版本，旧任务的计时器和响应不再有效', async () => {
  const { state, component, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('RUNNING', 10)); await initial
  const old = state.read(state._seq), newRunId = 'e'.repeat(32)
  state.payload = { ...task(), runId: newRunId }; component.watch.payload.handler.call(state)
  assert.equal(state.run, null); assert.equal(calls.reads[2].id, newRunId)
  calls.reads[2].resolve({ ...run('COMPLETED', 1), id: newRunId }); await tick()
  calls.reads[1].resolve(run('COMPLETED', 11)); await old
  assert.equal(state.run.id, newRunId); assert.equal(state.run.version, 1); assert.equal(clock.timers.size, 1)
})

test('取消结果未知时废弃在途读取，旧成功响应不能掩盖待人工核对状态', async () => {
  const { state, calls, run, clock } = timedCard(), initial = state.reload(); calls.reads[0].resolve(run('RUNNING', 1)); await initial
  const polling = state.read(state._seq), cancellation = state.cancel()
  calls.cancellations[0].reject(new Error('cancel response lost')); await cancellation
  assert.equal(state.run, null); assert.match(state.error, /取消结果尚未核对/)
  calls.reads[1].resolve(run('RUNNING', 2)); await polling
  assert.equal(state.run, null); assert.match(state.error, /取消结果尚未核对/); assert.equal(clock.timers.size, 0); assert.equal(state.cancelling, false)
})
