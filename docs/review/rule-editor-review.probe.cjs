// Historical pre-L2 diagnostic: asserts the former bug. Fixed-code acceptance is in frontend/tests/rule-trial-safety.test.cjs.
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
const assert = require('node:assert/strict')
const source = fs.readFileSync(path.join(__dirname, '../../frontend/src/views/RuleAdmin.vue'), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =')
let reply
const sandbox = { result: null, dryRunRule: () => reply }
vm.runInNewContext(source, sandbox)
const component = sandbox.result
const state = component.data()
for (const [name, method] of Object.entries(component.methods)) state[name] = method.bind(state)
async function main() {
  state.editor = { reportId: 'sales', companyCode: 'A', expression: 'amount > 100' }
  reply = Promise.resolve({ total: 2, hitCount: 1, samples: [] })
  await state.runDryRun()
  state.editor.expression = 'amount > 200'
  reply = Promise.reject(new Error('422: scan limit exceeded'))
  await assert.rejects(state.runDryRun())
  assert.equal(state.dryRun.result.hitCount, 1)
  console.log('L2 reproduced: second trial fails with 422 but the prior rule hit count remains displayed.')
  let finishOld
  reply = new Promise(resolve => { finishOld = resolve })
  const pending = state.runDryRun()
  state.loadFields = () => {}
  state.openEditor({ reportId: 'expense', companyCode: 'B', expression: 'amount < 5' }, false)
  assert.equal(state.dryRun.result, null)
  finishOld({ total: 2, hitCount: 1, samples: [] })
  await pending
  assert.equal(state.editor.reportId, 'expense')
  assert.equal(state.dryRun.result.hitCount, 1)
  console.log('L2 reproduced: a late sales trial response populates the newly opened expense rule editor.')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
