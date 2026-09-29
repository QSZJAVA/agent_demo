const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, '../../frontend/src/components/agent/AgentChat.vue'), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/import[\s\S]*?from\s+['"][^'"]+['"]/g, '').replace('export default', 'result =');
let submitted;
const sandbox = {
  result: null, PreviewCard: {}, PlanCard: {}, ResultCard: {}, ReportChoiceCard: {},
  getCurrentUserId: () => 'user1',
  window: { crypto: require('node:crypto').webcrypto },
  fetchMessages: async () => [{ id: 1, role: 'card', cardType: 'preview', status: 'ACTIVE', payload: { previewId: 'p1' } }],
  fetchDialogueSelection: async () => { throw new Error('selection endpoint temporarily unavailable'); },
  createPlan: async request => { submitted = request; return { planId: 'plan1', status: 'PENDING' }; },
};
vm.runInNewContext(source, sandbox);
const component = sandbox.result;
const state = { ...component.data(), $message: { warning() {} }, $emit() {} };
Object.defineProperty(state, 'busy', { get: () => component.computed.busy.call(state) });
for (const [key, method] of Object.entries(component.methods)) state[key] = method.bind(state);
state.scrollToBottom = () => {};
state.loadConversations = async () => {};
state.refreshStates = async () => {};
(async () => {
  state.applySelection({ previewId: 'p1', excludedRecords: [{ reportId: 'sales', recordId: '2' }] });
  await state.openConversation('conv1', true);
  assert.equal(state.busy, false);
  assert.equal(state.uiExcludes.length, 0);
  await state.dispatchSelected(state.messages[0]);
  assert.equal(state.selectionRestoreFailed, true);
  assert.equal(submitted, undefined);
  console.log('Verified: failed selection restore blocks createPlan until selection recovery succeeds.');
})().catch(error => { console.error(error); process.exitCode = 1; });
