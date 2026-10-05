const test = require('node:test'), assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
function api(http, values = new Map()) {
  let token = 'current'
  const context = vm.createContext({ http, getCurrentUserId: () => 'reader', getSessionToken: () => token, Date, Uint32Array,
    window: { crypto: require('node:crypto').webcrypto }, sessionStorage: { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) } })
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/api/investigation.js'), 'utf8').replace(/^import .*$/gm, '').replace(/export (async )?function/g, '$1function'), context)
  return { context, values, change: value => { token = value } }
}
const request = { planId: 'plan', itemIds: ['2', '1'], question: '分析' }
const created = { run: { id: 'run', status: 'QUEUED' } }
test('investigation lost submit response reuses original key after reload', async () => {
  const keys = [], values = new Map()
  const first = api({ post: async (_, body, config) => { keys.push(config.headers['Idempotency-Key']); throw new Error('lost') } }, values)
  await assert.rejects(first.context.submitInvestigation(request), /lost/)
  const reopened = api({ post: async (_, body, config) => { keys.push(config.headers['Idempotency-Key']); return created } }, values)
  assert.equal((await reopened.context.recoverInvestigation('plan')).id, 'run'); assert.equal(keys[0], keys[1])
})
test('investigation accepted task recovers only by GET', async () => {
  const values = new Map(); let posts = 0
  const first = api({ post: async () => { posts++; return created } }, values)
  await first.context.submitInvestigation(request)
  const reopened = api({ get: async () => ({ id: 'run', status: 'RUNNING' }), post: async () => { posts++; throw new Error('must not post') } }, values)
  assert.equal((await reopened.context.recoverInvestigation('plan')).id, 'run'); assert.equal(posts, 1)
})
test('changed unresolved payload is blocked and cannot replace original key', async () => {
  const h = api({ post: async () => { throw new Error('lost') } })
  await assert.rejects(h.context.submitInvestigation(request)); const original = [...h.values.values()][0]
  await assert.rejects(h.context.submitInvestigation({ ...request, question: '新问题' }), /尚未恢复/); assert.equal([...h.values.values()][0], original)
})
test('404 recovery forgets task and never POSTs', async () => {
  const h = api({ post: async () => created }); await h.context.submitInvestigation(request)
  let posts = 0; h.context.http = { get: async () => { throw Object.assign(new Error('gone'), { response: { status: 404 } }) }, post: async () => { posts++ } }
  assert.equal(await h.context.recoverInvestigation('plan'), null); assert.equal(posts, 0); assert.equal(h.values.size, 0)
})
test('late submit after account change cannot save run into new identity', async () => {
  let complete; const h = api({ post: () => new Promise(resolve => { complete = resolve }) })
  const pending = h.context.submitInvestigation(request); h.change('new-session'); complete(created)
  await assert.rejects(pending, /登录会话已变化/); assert.equal(JSON.parse([...h.values.values()][0]).id, undefined)
})
test('late 404 from an old recovery cannot erase a newly submitted task', async () => {
  const h = api({ post: async () => created }); await h.context.submitInvestigation(request)
  let rejectOld
  h.context.http = { get: () => new Promise((_, reject) => { rejectOld = reject }), post: async () => ({ run: { id: 'new-run' } }) }
  const old = h.context.recoverInvestigation('plan')
  await h.context.submitInvestigation(request, true)
  rejectOld(Object.assign(new Error('gone'), { response: { status: 404 } })); await old
  assert.equal(JSON.parse([...h.values.values()][0]).id, 'new-run')
})
test('late rejection of a concurrent same-key submit cannot erase an accepted id', async () => {
  let rejectFirst, calls = 0
  const h = api({ post: () => ++calls === 1 ? new Promise((_, reject) => { rejectFirst = reject }) : Promise.resolve(created) })
  const old = h.context.submitInvestigation(request)
  await h.context.submitInvestigation(request)
  rejectFirst(Object.assign(new Error('late rejection'), { response: { status: 409 } })); await assert.rejects(old)
  assert.equal(JSON.parse([...h.values.values()][0]).id, 'run')
})
test('late old successful read cannot overwrite a newer recovery key', async () => {
  const h = api({ post: async () => created }); await h.context.submitInvestigation(request)
  let complete
  h.context.http = { get: () => new Promise(resolve => { complete = resolve }), post: async () => ({ run: { id: 'new-run' } }) }
  const old = h.context.submitInvestigation(request)
  await h.context.submitInvestigation(request, true); const latest = [...h.values.values()][0]
  complete({ id: 'run' }); await old
  assert.equal([...h.values.values()][0], latest)
})
function panel(extras) {
  const code = fs.readFileSync(path.join(__dirname, '../src/components/agent/InvestigationPanel.vue'), 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace('export default', 'result =')
  const sandbox = { result: null, getCurrentUserId: () => 'reader', getSessionToken: () => 'current', clearTimeout() {}, setTimeout() {}, ...extras }
  vm.runInNewContext(code, sandbox); const c = sandbox.result, state = { planId: 'plan', $emit() {} }; Object.assign(state, c.data.call(state), { identity: 'reader', sessionToken: 'current' })
  Object.entries(c.methods).forEach(([key, fn]) => { state[key] = fn.bind(state) }); return state
}
test('closed investigation panel ignores late report and evidence', async () => {
  let complete; const state = panel({ recoverInvestigation: () => new Promise(resolve => { complete = resolve }) })
  const work = state.resume(); state.dispose(); complete({ id: 'run' }); await work; assert.equal(state.run, null)
})
test('candidate ids and report are isolated when account changes', async () => {
  let complete, token = 'current'; const state = panel({ getSessionToken: () => token, fetchInvestigationCandidates: () => new Promise(resolve => { complete = resolve }) })
  const work = state.loadCandidates(); token = 'other'; complete({ records: [{ id: '9007199254740993' }], nextCursor: null }); await work; assert.equal(state.candidates.length, 0)
})
test('explicit rejection releases draft so user can change oversized selection', async () => {
  let count = 0
  const h = api({ post: async () => { if (!count++) throw Object.assign(new Error('too many'), { response: { status: 413 } }); return created } })
  await assert.rejects(h.context.submitInvestigation(request)); assert.equal(h.values.size, 0)
  assert.equal((await h.context.submitInvestigation({ ...request, itemIds: ['1'] })).id, 'run')
})
test('server uncertainty retains original key instead of accepting edited payload', async () => {
  const h = api({ post: async () => { throw Object.assign(new Error('uncertain'), { response: { status: 503 } }) } })
  await assert.rejects(h.context.submitInvestigation(request)); assert.equal(h.context.hasPendingInvestigation('plan'), true)
  await assert.rejects(h.context.submitInvestigation({ ...request, question: 'different' }), /尚未恢复/)
})
test('pending submission button resumes stored request and keeps selected scope frozen', async () => {
  let restores = 0
  const state = panel({ recoverInvestigation: async () => { restores++; return null } })
  state.submissionPending = true; state.selected = ['9007199254740993']; state.selectItems([{ id: '2' }])
  assert.equal(state.selected[0], '9007199254740993'); assert.equal(state.selectable({ id: '2' }), false)
  await state.submit(); assert.equal(restores, 1)
})
test('candidate loading is not stranded when investigation request generation changes', async () => {
  let complete
  const state = panel({ fetchInvestigationCandidates: () => new Promise(resolve => { complete = resolve }) })
  const work = state.loadCandidates(); state.generation++; complete({ records: [{ id: '2' }], nextCursor: null }); await work
  assert.equal(state.candidates.length, 1); assert.equal(state.loadingCandidates, false)
})
test('explicit read recovery clears the superseded submit spinner', async () => {
  const state = panel({ recoverInvestigation: async () => null }); state.submitting = true
  await state.resume(); assert.equal(state.submitting, false); assert.equal(state.loading, false)
})
test('header select-all above ten restores explicit scope without silently truncating', () => {
  const state = panel({}); state.selected = ['2']; state.candidates = Array.from({ length: 20 }, (_, i) => ({ id: String(i + 1) }))
  const restored = []; let cleared = false
  state.$nextTick = callback => callback()
  state.$refs = { candidateTable: { clearSelection() { cleared = true; state.selectItems([]) }, toggleRowSelection(row) { restored.push(row.id); state.selectItems([row]) } } }
  state.selectItems(state.candidates)
  assert.equal(cleared, true); assert.deepEqual(restored, ['2']); assert.deepEqual(state.selected, ['2'])
  assert.match(state.error, /最多选择 10/); assert.equal(state.selectionRestoring, false)
})

for (const status of ['COMPLETED', 'FAILED', 'CANCELLED']) {
  test(`terminal ${status} drains steps read before terminal commit, including further pages`, async () => {
    const cursors = []; let marked = 0, timers = 0
    const state = panel({ fetchInvestigation: async () => ({ id: 'run', status }),
      fetchInvestigationSteps: async (_, cursor) => {
        cursors.push(cursor)
        return { records: cursor < 3 ? [{ seq: cursor + 1, status: cursor === 0 ? 'SUCCEEDED' : 'FAILED' }] : [], nextCursor: Math.min(cursor + 1, 3) }
      }, markInvestigationTerminal: () => { marked++ }, setTimeout: () => { timers++ } })
    state.run = { id: 'run', status: 'RUNNING' }
    await state.refresh(0)
    assert.deepEqual(cursors, [0, 1, 2, 3]); assert.equal(state.steps.length, 3); assert.equal(state.afterSeq, 3)
    assert.equal(marked, 1); assert.equal(timers, 0)
  })
}
test('terminal follow-up step read cannot update closed or superseded panel', async () => {
  let complete, calls = 0, marked = false
  const state = panel({ fetchInvestigation: async () => ({ id: 'run', status: 'CANCELLED' }),
    fetchInvestigationSteps: async () => ++calls === 1 ? { records: [], nextCursor: 0 } : new Promise(resolve => { complete = resolve }),
    markInvestigationTerminal: () => { marked = true } })
  state.run = { id: 'run', status: 'RUNNING' }
  const work = state.refresh(0)
  while (!complete) await Promise.resolve()
  state.dispose(); complete({ records: [{ seq: 1 }], nextCursor: 1 }); await work
  assert.equal(state.steps.length, 0); assert.equal(marked, false)
})
test('failed terminal step read retains cursor and can recover with an explicit refresh', async () => {
  let calls = 0, marked = 0
  const state = panel({ fetchInvestigation: async () => ({ id: 'run', status: 'COMPLETED' }),
    fetchInvestigationSteps: async (_, cursor) => {
      if (++calls === 2) throw new Error('步骤读取失败')
      return { records: cursor === 0 ? [{ seq: 1 }] : [], nextCursor: 1 }
    }, markInvestigationTerminal: () => { marked++ } })
  state.run = { id: 'run', status: 'RUNNING' }
  await state.refresh(0); assert.equal(marked, 0); assert.equal(state.afterSeq, 1); assert.match(state.error, /步骤读取失败/)
  await state.refresh(0); assert.equal(marked, 1); assert.equal(state.steps.length, 1)
})

test('compiled report template keeps finding and unresolved sibling keys unique', () => {
  const Vue = require('vue'), compiler = require('vue/compiler-sfc')
  const source = fs.readFileSync(path.join(__dirname, '../src/components/agent/InvestigationPanel.vue'), 'utf8')
  const descriptor = compiler.parse({ source, filename: 'InvestigationPanel.vue' })
  const compiled = compiler.compileTemplate({ source: descriptor.template.content, filename: 'InvestigationPanel.vue' })
  assert.deepEqual(compiled.errors, [])
  const renderers = new Function(`${compiled.code}; return { render, staticRenderFns }`)()
  const state = panel({})
  state.active = false; state.statusLabel = '调查完成'
  state.run = { id: 'run', status: 'COMPLETED', itemRefs: [{ itemRef: 'I1', itemId: '1' }], report: {
    summary: '调查只读', findings: [{ itemRef: 'I1', reasonCode: 'PRECHECK_SKIPPED', certainty: 'VERIFIED', explanation: '复核跳过', evidenceIds: ['E1'], nextStep: 'MANUAL_REVIEW' }],
    unresolved: [{ itemRef: 'I1', topics: ['RULE_CAUSALITY'], message: '仍需核查规则因果' }] } }
  const instance = new Vue({ data: () => state, ...renderers })
  let keyedNodes = 0
  function inspect(node) {
    const children = node.children || [], keys = children.filter(child => child.key != null).map(child => child.key)
    assert.equal(new Set(keys).size, keys.length, `duplicate sibling keys: ${keys}`); keyedNodes += keys.length
    children.forEach(inspect)
  }
  inspect(instance._render()); assert.ok(keyedNodes >= 3); instance.$destroy()
})
