// Defect reproductions for the 2026-09-28 review, not passing business regressions.
// Run from the repository root: node docs/review/p0-p1-2026-09-28.probe.cjs
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '../..')
function component(file, dependencies) {
  const script = fs.readFileSync(path.join(root, file), 'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '')
    .replace('export default', 'result =')
  const sandbox = { result: null, ...dependencies }
  vm.runInNewContext(script, sandbox)
  return sandbox.result
}
function bind(definition, state) {
  for (const [name, method] of Object.entries(definition.methods)) state[name] = method.bind(state)
  return state
}
async function main() {
  let submitted
  const chat = component('frontend/src/components/agent/AgentChat.vue', {
    PreviewCard: {}, PlanCard: {}, ResultCard: {}, ReportChoiceCard: {},
    getCurrentUserId: () => 'user1',
    streamChat: (request, event) => {
      submitted = request
      event('text', { delta: '正在生成清单' })
      return { promise: Promise.resolve(), abort() {} }
    }
  })
  const state = bind(chat, { ...chat.data(), $emit() {} })
  Object.defineProperty(state, 'busy', { get: () => chat.computed.busy.call(state) })
  state.activeId = 'conversation-1'
  state.scrollToBottom = () => {}
  state.forgetPendingJob = () => {}
  state.loadConversations = () => {}
  state.refreshStates = () => {}
  const originalSend = state.send
  let completed
  state.send = (...args) => (completed = originalSend(...args))
  state.dispatchSelected({ status: 'ACTIVE', payload: { previewId: 'clicked-preview-A' } })
  await completed
  assert.equal(submitted.previewId, null)
  assert.equal(submitted.excludedRecords.length, 0)
  console.log('J1 reproduced: clicked preview A with all rows selected; sent previewId=null, excludedRecords=[]')

  const replies = []
  const card = component('frontend/src/components/agent/PlanCard.vue', {
    DispatchTrace: {}, fetchPlanItems: () => new Promise(resolve => replies.push(resolve))
  })
  const plan = bind(card, { payload: { planId: 'plan-1' }, page: 1, pageRecords: [] })
  const oldRead = plan.changePage(1)
  const afterReconcile = plan.changePage(1)
  replies[1]([{ id: 1, status: 'SUCCESS' }])
  await afterReconcile
  assert.equal(plan.pageRecords[0].status, 'SUCCESS')
  replies[0]([{ id: 1, status: 'UNKNOWN', errorMessage: '结果待核对' }])
  await oldRead
  assert.equal(plan.pageRecords[0].status, 'UNKNOWN')
  console.log('J3 reproduced: late pre-reconciliation page overwrote SUCCESS with UNKNOWN')

  const page2 = plan.changePage(2)
  const page3 = plan.changePage(3)
  replies[3]([{ id: 101 }])
  await page3
  replies[2]([{ id: 51 }])
  await page2
  assert.equal(plan.page, 2)
  console.log('J3 reproduced: last requested page=3; late page=2 response reset the displayed page to 2')
}
main().catch(error => { console.error(error); process.exitCode = 1 })
