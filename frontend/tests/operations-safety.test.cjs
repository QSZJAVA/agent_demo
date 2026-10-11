const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const Vue = require('vue')
Vue.config.productionTip = false
function state(api) {
  const source = fs.readFileSync(path.join(__dirname,'../src/views/OperationsAdmin.vue'),'utf8')
    .match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import[^\n]*from[^\n]*/g,'').replace('export default','result =')
  const sandbox={result:null,api};vm.runInNewContext(source,sandbox)
  delete sandbox.result.created
  return new Vue(sandbox.result)
}
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
function series(rows,overrides={}){return {available:true,scope:'CURRENT_AUTHORIZATION',unavailableReason:null,complete:true,truncated:false,rows,...overrides}}
function unavailable(reason='公司归属不可核实'){return series(null,{available:false,scope:'UNVERIFIABLE',unavailableReason:reason,complete:false})}
test('late policy cannot replace the newly selected report policy',async()=>{
  const a=deferred(),b=deferred()
  const s=state({policy:k=>k==='resolver'?a.promise:b.promise,policyHistory:async()=>[]})
  s.policyKey='resolver';const first=s.loadPolicy()
  s.policyKey='catalog:rpt-sales-order';const second=s.loadPolicy()
  b.resolve({version:2,payload:{percent:20}});await second
  a.resolve({version:9,payload:{percent:90}});await first
  assert.equal(s.policyVersion,2);assert.equal(s.policyForm.percent,20);s.$destroy()
})
test('policy saving is blocked while the selected policy is loading',async()=>{
  let calls=0;const s=state({savePolicy:async()=>calls++});s.policyReady=false;s.reason='reason'
  await s.savePolicy();assert.equal(calls,0);s.$destroy()
})
test('out-of-order item pages cannot expose the previous task',async()=>{
  const a=deferred(),b=deferred();const s=state({workItems:id=>id==='a'?a.promise:b.promise})
  s.selected={id:'a'};const first=s.reloadItems();s.selected={id:'b'};const second=s.reloadItems()
  b.resolve([{id:'b'}]);await second;a.resolve([{id:'a'}]);await first
  assert.equal(s.items[0].id,'b');s.$destroy()
})
test('switching account while an approval dialog is open cannot send an action',async()=>{
  const gate=deferred();let calls=0;const s=state({act:async()=>calls++})
  s.promptReason=()=>gate.promise;const action=s.act({id:'a'},'retry-failed');s.$destroy()
  gate.resolve('approved');await action;assert.equal(calls,0)
})
test('workbench refresh ignores stale pages and results after disposal',async()=>{
  const a=deferred(),b=deferred();const s=state({workbench:after=>after?a.promise:b.promise})
  const first=s.loadTasks('next'),second=s.loadTasks();b.resolve({rows:[{id:'fresh'}],nextCursor:null});await second
  a.resolve({rows:[{id:'stale'}],nextCursor:'old'});await first
  assert.equal(s.tasks[0].id,'fresh');assert.equal(s.nextCursor,null);s.$destroy()
})
test('empty metrics display absence of samples rather than zero failure',()=>{
  const s=state({});s.stats={samples:series([]),dispatch:series([]),overview:{requests:series([]),dispatch:series([])}}
  assert.equal(s.ratio(['EXACT']),'暂无数据');assert.equal(s.dispatchRate,'暂无数据');s.$destroy()
})
test('headline rates use uncapped totals rather than truncated report detail',()=>{
  const s=state({});s.stats={samples:series([{operation:'RESOLVE',outcome:'EXACT',samples:1}]),dispatch:series([{status:'SUCCESS',items:2}],{complete:false,truncated:true}),overview:{requests:series([{operation:'RESOLVE',outcome:'EXACT',samples:75},{operation:'RESOLVE',outcome:'NONE',samples:25}]),dispatch:series([{status:'SUCCESS',items:2},{status:'FAILED',items:2}])}}
  assert.equal(s.ratio(['EXACT']),'75.0%');assert.equal(s.dispatchRate,'50.0%');s.$destroy()
})
test('unverifiable sampling never appears as empty valid data or zero rates',()=>{
  const s=state({});s.stats={samples:unavailable(),dispatch:series([]),overview:{requests:unavailable(),dispatch:series([])},scope:{companyCodes:['A'],reportIds:['rpt-sales-order']}}
  assert.equal(s.ratio(['EXACT']),'范围不可核实');assert.equal(s.samplingAvailable,false);assert.equal(s.metricRows.length,0)
  assert.equal(s.samplingUnavailableReason,'公司归属不可核实');assert.equal(s.dispatchRate,'暂无数据')
  assert.match(s.scopeSummary,/公司 A/);assert.match(s.scopeSummary,/1 张授权报表/);s.$destroy()
})
test('missing data permissions display denial without a fabricated zero count',()=>{
  const denied=unavailable('当前没有公司或报表权限'),s=state({})
  s.stats={samples:unavailable(),dispatch:denied,overview:{requests:unavailable(),dispatch:denied},scope:{companyCodes:[],reportIds:[]}}
  assert.equal(s.dispatchRate,'无统计权限');assert.equal(s.dispatchAvailable,false);assert.equal(s.dispatchRows.length,0)
  assert.match(s.dispatchUnavailableReason,/没有公司/);assert.match(s.scopeSummary,/无授权公司/);s.$destroy()
})
test('statistics loading is distinct from an authorized empty result',()=>{
  const s=state({});assert.equal(s.ratio(['EXACT']),'尚未加载');assert.equal(s.dispatchRate,'尚未加载');s.$destroy()
})
test('late metric windows cannot replace current counts or authorization scope',async()=>{
  const a=deferred(),b=deferred(),s=state({metrics:days=>days===7?a.promise:b.promise})
  s.days=7;const first=s.loadMetrics();s.days=30;const second=s.loadMetrics()
  b.resolve({scope:{companyCodes:['A'],reportIds:['current']},samples:unavailable(),dispatch:series([]),overview:{requests:unavailable(),dispatch:series([{status:'SUCCESS',items:3}])}});await second
  a.resolve({scope:{companyCodes:['B'],reportIds:['stale']},samples:unavailable(),dispatch:series([]),overview:{requests:unavailable(),dispatch:series([{status:'FAILED',items:1}])}});await first
  assert.equal(s.stats.scope.reportIds[0],'current');assert.equal(s.dispatchRate,'100.0%');s.$destroy()
})
test('metric response after account disposal cannot repopulate the old page',async()=>{
  const gate=deferred(),s=state({metrics:()=>gate.promise});const request=s.loadMetrics();s.$destroy()
  gate.resolve({scope:{companyCodes:['B'],reportIds:['hidden']}});await request
  assert.equal(s.stats.scope,null)
})
