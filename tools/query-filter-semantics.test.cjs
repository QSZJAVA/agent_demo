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
test('同端点只消除被严格比较蕴含的包含限制，不能放宽等号或跨OR分支化简',()=>{
  for(const [strict,inclusive] of [['GT','GTE'],['LT','LTE']]) {
    const expected={where:[['amount',strict,'96000']]};
    assert.equal(compareQueryFilters(expected,query([[['amount',inclusive,'96000.00'],['amount',strict,'96000']]])).equal,true);
    assert.equal(compareQueryFilters(expected,query([[['amount',inclusive,'96000']]])).equal,false);
    assert.equal(compareQueryFilters(expected,query([[['amount',inclusive,'96000']],[['amount',strict,'96000']]])).equal,false);
  }
});
test('更强同向端点按精确数值归一，上下限、负数与超出安全整数的端点不能混淆',()=>{
  const expected={where:[['amount','GTE','9007199254740993'],['amount','LTE','9007199254740994']]};
  assert.equal(compareQueryFilters(expected,query([[['amount','GT','9007199254740992'],['amount','GTE','9007199254740993'],['amount','LTE','9007199254740994'],['amount','LT','9007199254740995']]])).equal,true);
  assert.equal(compareQueryFilters(expected,query([[['amount','GT','9007199254740992'],['amount','LTE','9007199254740994']]])).equal,false);
  assert.equal(compareQueryFilters({where:[['amount','LT','-0.0000000000000000002']]},query([[['amount','LTE','-0.0000000000000000001'],['amount','LT','-0.0000000000000000002']]])).equal,true);
  assert.equal(compareQueryFilters({where:[['amount','GTE','10'],['amount','LTE','20']]},query([[['amount','GTE','10']]])).equal,false);
});
test('使用声明的日期和目录数值类型，数字形字符串标识不按数值顺序推断',()=>{
  const expected={where:[['quantity','GTE','100']]},actual=query([[['quantity','GT','20'],['quantity','GTE','100']]]);
  assert.equal(compareQueryFilters(expected,actual,'A',[{name:'quantity',type:'long'}]).equal,true);
  assert.equal(compareQueryFilters(expected,actual,'A',[{name:'quantity',type:'string'}]).equal,false);
  assert.equal(compareQueryFilters(expected,actual).equal,false);
  assert.equal(compareQueryFilters({where:[['date','GTE','2026-03-02']]},query([[['expenseDate','GTE','2026-03-01'],['date','GT','2026-03-01']]])).equal,true);
  assert.equal(compareQueryFilters({where:[['recordId','EQ','001']]},query([[['recordId','EQ','1']]])).equal,false);
});
test('端点化简与独立区间真值核对一致，覆盖所有方向、等号、交集和空值',()=>{
  // 小整数阈值之间枚举端点、内侧、外侧及NULL；直接按数学关系求值，不复用比较器的化简算法。
  const atoms=['GT','GTE','LT','LTE'].flatMap(op=>[-2,0,2].map(n=>['amount',op,String(n)]));
  const witnesses=[null,-3,-2.25,-2,-1.75,-0.25,0,0.25,1.75,2,2.25,3];
  const matches=(x,[,op,value])=>x!==null&&({GT:()=>x>Number(value),GTE:()=>x>=Number(value),LT:()=>x<Number(value),LTE:()=>x<=Number(value)})[op]();
  for(const single of atoms)for(const first of atoms)for(const second of atoms) {
    const truth=witnesses.every(x=>matches(x,single)===(matches(x,first)&&matches(x,second)));
    assert.equal(compareQueryFilters({where:[single]},query([[first,second]])).equal,truth,JSON.stringify({single,first,second}));
  }
});
