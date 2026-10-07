const test=require('node:test'),assert=require('node:assert/strict');
const {compareQueryFilters,canonicalField,reportScopeMatches}=require('./query-filter-semantics.cjs');
const query=groups=>({domain:'REPORT',companyCode:'A',conditions:groups.map(g=>({allOf:g.map(([field,operator,values])=>({field,operator,values:Array.isArray(values)?values:[values]}))}))});
test('唯一工单详情可按实际报表收窄，不能借详情扩大授权或改变列表与显式报表范围',()=>{
  const actual={domain:'WORK_ORDER',view:'DETAIL',reportIds:['sales']},expected={view:'DETAIL'},rows=[{reportId:'sales'}],allowed=['sales','expense'];
  assert.equal(reportScopeMatches(expected,actual,allowed,rows),true);
  assert.equal(reportScopeMatches(expected,{...actual,reportIds:['expense']},allowed,rows),false);
  assert.equal(reportScopeMatches(expected,{...actual,reportIds:['sales','forbidden']},allowed,rows),false);
  assert.equal(reportScopeMatches(expected,actual,allowed,[...rows,{reportId:'expense'}]),false);
  for(const view of ['LIST','SUMMARY'])assert.equal(reportScopeMatches({view},{...actual,view},allowed,rows),false);
  assert.equal(reportScopeMatches(expected,{...actual,domain:'REPORT'},allowed,rows),false);
});
test('报表客户分组字段使用同一契约别名，但不将客户标识合并成名称',()=>{
  assert.equal(canonicalField('counterpartyName','REPORT'),canonicalField('customerName','REPORT'));
  assert.notEqual(canonicalField('counterpartyId','REPORT'),canonicalField('customerName','REPORT'));
  assert.equal(canonicalField('counterpartyName','WORK_ORDER'),'counterpartyName');
});
test('已冻结公司范围可以在范围字段或条件中表达，其他公司不能归一掉',()=>{
  const inFilter=query([[['companyCode','EQ','A']]]);inFilter.companyCode=null;
  assert.equal(compareQueryFilters({},inFilter,'A').equal,true);
  assert.equal(compareQueryFilters({},inFilter,'B').equal,false);
});
test('全名包含匹配不能冒充精确相等，即使当前结果集合相同',()=>{
  assert.equal(compareQueryFilters({where:[['customerName','EQ','示例公司']]},query([[['counterpartyName','CONTAINS','示例公司']]])).equal,false);
  assert.equal(compareQueryFilters({where:[['customerName','EQ','示例公司']]},query([[['counterpartyName','EQ','示例公司']]])).equal,true);
});
test('日期开闭区间、金额文本及报表派单布尔状态允许契约等价',()=>{
  assert.equal(compareQueryFilters({where:[['date','LTE','2026-03-31'],['amount','GT','1000'],['status','EQ','未派单']]},
    query([[['dueDate','LT','2026-04-01'],['amount','GT','01000.00'],['dispatched','EQ','false']]])).equal,true);
});
test('IN与OR、NOT_IN与多个NE等价，不能把AND与OR混淆',()=>{
  const expected={anyOf:[[['expenseType','EQ','差旅费']],[['expenseType','EQ','办公用品']]]};
  assert.equal(compareQueryFilters(expected,query([[['expenseType','IN',['差旅费','办公用品']]]])).equal,true);
  assert.equal(compareQueryFilters(expected,query([[['expenseType','EQ','差旅费'],['expenseType','EQ','办公用品']]])).equal,false);
  assert.equal(compareQueryFilters({where:[['expenseType','NOT_IN',['差旅费','办公用品']]]},query([[['expenseType','NE','办公用品'],['expenseType','NE','差旅费']]])).equal,true);
});
test('空条件和重复条件可归一，但不能默默丢弃日期或扩大金额边界',()=>{
  assert.equal(compareQueryFilters({},query([])).equal,true);
  assert.equal(compareQueryFilters({where:[['amount','GT','1000']]},query([[['amount','GTE','1000']]])).equal,false);
  assert.equal(compareQueryFilters({where:[['amount','GT','1000'],['date','GTE','2026-01-01']]},query([[['amount','GT','1000']]])).equal,false);
});
test('组合展开超出预算时失败而不是截断后判为相等',()=>{
  const groups=[[['a','IN',Array.from({length:17},(_,i)=>String(i))],['b','IN',Array.from({length:17},(_,i)=>String(i))]]];
  assert.throws(()=>compareQueryFilters({},query(groups)),/预算/);
});
