/** 回放预期与实际查询的独立条件核验；只归一明确的字段/运算等价，不用当前数据碰巧相同代替语义一致。 */
function canonicalField(field,domain){return domain==='REPORT'&&field==='counterpartyName'?'customerName':field;}
const numericTypes=new Set(['decimal','integer','long']);
function filterField(field,domain){return ['saleDate','expenseDate','dueDate'].includes(field)?'date':canonicalField(field,domain);}
/** 以十进制整数和小数位数精确比较，避免大金额或细小小数被Number舍入成同一端点。 */
function compareDecimal(left,right) {
  const parse=value=>{const m=value.match(/^(-?)(\d+)(?:\.(\d+))?$/);return m?{coefficient:BigInt(m[1]+m[2]+(m[3]||'')),scale:(m[3]||'').length}:null;};
  const a=parse(left),b=parse(right);if(!a||!b)return null;
  const scale=Math.max(a.scale,b.scale),x=a.coefficient*10n**BigInt(scale-a.scale),y=b.coefficient*10n**BigInt(scale-b.scale);
  return x<y?-1:x>y?1:0;
}
function atom(field,operator,value,domain,types) {
  let values=(Array.isArray(value)?value:[value]).map(String);
  field=filterField(field,domain);
  if(domain==='REPORT' && field==='dispatched' && ['EQ','NE'].includes(operator) && ['true','false'].includes(values[0])) {
    field='status';values=[(values[0]==='true')===(operator==='EQ')?'已派单':'未派单'];operator='EQ';
  }
  if(['IN','NOT_IN'].includes(operator)&&values.length===1)operator=operator==='IN'?'EQ':'NE';
  // 业务日期为日粒度；次日之前与本日及之前等价，不对时间戳执行这个转换。
  if(types.get(field)==='date'&&['GT','LT'].includes(operator)&&/^\d{4}-\d{2}-\d{2}$/.test(values[0])) {
    const date=new Date(values[0]+'T00:00:00Z');date.setUTCDate(date.getUTCDate()+(operator==='GT'?1:-1));
    values=[date.toISOString().slice(0,10)];operator=operator==='GT'?'GTE':'LTE';
  }
  if(numericTypes.has(types.get(field)))values=values.map(v=>{
    const m=v.match(/^(-?)(\d+)(?:\.(\d+))?$/);if(!m)return v;
    const whole=m[2].replace(/^0+(?=\d)/,''),fraction=(m[3]||'').replace(/0+$/,'');
    return (m[1]&&(whole!=='0'||fraction)?'-':'')+whole+(fraction?'.'+fraction:'');
  });
  return JSON.stringify([field,operator,values.sort()]);
}

/**
 * 只消除同一AND组、同一字段、同方向中被更强端点蕴含的限制，不跨字段或OR分支推断。
 * 数值和日期类型来自业务契约及只读结果的字段元数据；未知类型仅比较完全相同的端点，不猜测数字形标识的顺序。
 * 上下限分别保留，严格比较优先于同值包含比较；NULL对这些普通比较均为false，不因化简变成匹配。
 */
function pruneBounds(encoded,types) {
  const unique=[...new Set(encoded)],atoms=unique.map(JSON.parse);
  return unique.filter((_,index)=>{
    const [field,operator,values]=atoms[index],lower=['GT','GTE'].includes(operator),upper=['LT','LTE'].includes(operator);
    if((!lower&&!upper)||values.length!==1)return true;
    return !atoms.some(([otherField,otherOperator,otherValues],otherIndex)=>{
      if(otherIndex===index||otherField!==field||otherValues.length!==1
        || !(lower?['GT','GTE']:['LT','LTE']).includes(otherOperator))return false;
      const left=otherValues[0],right=values[0],type=types.get(field);
      let comparison=left===right?0:null;
      if(numericTypes.has(type))comparison=compareDecimal(left,right);
      else if(type==='date'&&/^\d{4}-\d{2}-\d{2}$/.test(left)&&/^\d{4}-\d{2}-\d{2}$/.test(right))comparison=left<right?-1:left>right?1:0;
      if(comparison===null)return false;
      return (lower?comparison>0:comparison<0)
        || comparison===0&&['GTE','LTE'].includes(operator)&&['GT','LT'].includes(otherOperator);
    });
  });
}

/** 将受限的 OR-of-AND 条件规范化；IN 展开为 OR，NOT_IN 展开为 AND，超预算时明确失败。 */
function canonical(groups,domain,types) {
  const expanded=[];
  for(const group of groups) {
    let options=[[]];
    for(const filter of group) {
      const encoded=atom(...filter,domain,types),[field,operator,values]=JSON.parse(encoded);
      if(operator==='IN')options=options.flatMap(g=>values.map(v=>[...g,atom(field,'EQ',[v],domain,types)]));
      else if(operator==='NOT_IN')options=options.map(g=>[...g,...values.map(v=>atom(field,'NE',[v],domain,types))]);
      else options=options.map(g=>[...g,encoded]);
      if(options.length+expanded.length>256)throw new Error('查询条件等价核验超出预算');
    }
    expanded.push(...options);
  }
  return JSON.stringify([...new Set(expanded.map(g=>JSON.stringify(pruneBounds(g,types).sort())))].sort());
}

/**
 * 列表与汇总按完整条件逻辑比较，不读取当前记录集合来判断等价；详情的身份另由回放器核对唯一事实。
 * columns是业务服务返回的字段类型，用于有依据的端点化简；省略时仍保留公共金额与日期契约，未知字段不作类型猜测。
 */
function compareQueryFilters(expected,query,expectedCompanyCode=query.companyCode,columns=[]) {
  const types=new Map([['amount','decimal'],['date','date']]);
  for(const column of columns)types.set(filterField(column.name,query.domain),column.type);
  const before=(expected.anyOf||[[]]).map(group=>[...(expected.where||[]),...group]);
  const after=(query.conditions.length?query.conditions:[{allOf:[]}]).map(group=>group.allOf
    .filter(f=>!(f.field==='companyCode'&&f.operator==='EQ'&&f.values.length===1&&f.values[0]===expectedCompanyCode))
    .map(f=>[f.field,f.operator,f.values]));
  const expectedCanonical=canonical(before,query.domain,types),actualCanonical=canonical(after,query.domain,types);
  return {equal:expectedCanonical===actualCanonical,expectedCanonical,actualCanonical};
}
/** 独立事实唯一定位的工单/派单详情可收窄到其所属授权报表；列表、汇总和显式报表查询仍须范围完全一致。 */
function reportScopeMatches(expected,query,expectedReports,expectedRows) {
  const actual=[...new Set(query.reportIds)].sort(),allowed=[...new Set(expectedReports)].sort();
  if(JSON.stringify(actual)===JSON.stringify(allowed))return true;
  return expected.view==='DETAIL' && query.view==='DETAIL' && ['WORK_ORDER','DISPATCH'].includes(query.domain)
    && expectedRows.length===1 && actual.length>0 && actual.every(id=>allowed.includes(id))
    && actual.includes(expectedRows[0].reportId);
}
module.exports={compareQueryFilters,canonicalField,reportScopeMatches};
