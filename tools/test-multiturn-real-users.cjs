#!/usr/bin/env node
/**
 * 真实用户多轮回放：先固定自然语言及任务预期，再独立核对事实集合、统计和持久选择。
 * 只调用认证的本机 Agent/HTTP MCP；可建立预览和待确认清单，绝不调用确认、审批或重置接口。
 * 凭据仅从已忽略的本机文件读取，不进入日志；每轮即时落盘，失败及其后续轮次全部保留。
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {compareQueryFilters,canonicalField,reportScopeMatches} = require('./query-filter-semantics.cjs');
const root = path.resolve(__dirname, '..');
const args = process.argv.slice(2);
const option = (name, fallback) => args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const readJson = file => JSON.parse(fs.readFileSync(path.resolve(root, file), 'utf8').replace(/^\uFEFF/, ''));
const corpusPath = option('--corpus', 'tools/fixtures/multiturn-real-users.json');
const outputPath = path.resolve(root, option('--output', '.runtime/whole-project-2026-10-07/multiturn-initial.json'));
const wanted = option('--scenarios', '').split(',').filter(Boolean);
const delay = Number(option('--delay', '3000'));
const baseline = readJson('demo-baseline.json');
// 显式测试环境可指向已授权的隔离夹具；默认仍使用本机当前基线，不改库名、不复制或重置演示数据。
const localUrl = value => {
  const url = new URL(value);
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password)
    throw new Error('Regression endpoints must be credential-free loopback HTTP URLs');
  return url.toString().replace(/\/$/, '');
};
const apiBase = localUrl(option('--agent-url', `http://127.0.0.1:${baseline.agentPort}`)) + '/api/';
const mcpBase = localUrl(option('--business-url', `http://127.0.0.1:${baseline.businessPort}`)) + '/mcp';
const account = readJson(option('--account', '.runtime/semantic-fields-account.json'));
const credentials = readJson(option('--mcp-credentials', '.runtime/mcp-credentials.json'));
const reportIds = {sales:'rpt-sales-order', receivable:'rpt-ar-invoice', expense:'rpt-expense-claim'};
const domain = source => source === 'dispatch' ? 'DISPATCH' : source === 'work_order' ? 'WORK_ORDER' : 'REPORT';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
let token, operator, protocol = '2025-03-26', rpcId = 0, lastTurn = 0;

/** GET 或非执行型 POST；业务错误必须显式失败，不能将空响应当作空集合。 */
async function api(route, body) {
  const response = await fetch(apiBase + route, {method:body === undefined ? 'GET' : 'POST',
    headers:{'Content-Type':'application/json', ...(token ? {Authorization:'Bearer '+token} : {})},
    body:body === undefined ? undefined : JSON.stringify(body), signal:AbortSignal.timeout(190000)});
  const envelope = await response.json();
  if (!response.ok || envelope.code !== 0) throw new Error(`API ${route}: ${response.status}/${envelope.code} ${envelope.message}`);
  return envelope.data;
}

/** 独立于模型的只读 MCP 基准；使用同一认证用户，测试脚本不能扩大业务身份。 */
async function rpc(method, params) {
  const response = await fetch(mcpBase, {method:'POST', headers:{'Content-Type':'application/json',
    Accept:'application/json, text/event-stream', Authorization:'Bearer '+credentials.serviceToken,
    'MCP-Protocol-Version':protocol}, body:JSON.stringify({jsonrpc:'2.0', id:++rpcId, method, params}), signal:AbortSignal.timeout(70000)});
  const wire = await response.text();
  if (!response.ok) throw new Error(`Read-only MCP HTTP ${response.status}`);
  const json = wire.trim().startsWith('{') ? JSON.parse(wire) : JSON.parse(wire.split(/\r?\n/).filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trim()).join('\n'));
  if (json.error) throw new Error('MCP protocol failure: '+json.error.message);
  return json.result;
}
async function readBusiness(source) {
  const rows = []; let first;
  for (let page=1; page<=200; page++) {
    const query = {domain:domain(source), view:'LIST', reportIds:Object.values(reportIds), companyCode:'A', conditions:[], sortField:null, descending:false, page, size:50, groupBy:null};
    const result = await rpc('tools/call', {name:'business_query', arguments:{operatorId:operator.userId, tenantId:operator.tenantId, query}});
    const envelope = JSON.parse(result.content.find(c=>c.type==='text').text);
    if (result.isError || !envelope.data) throw new Error('Read-only MCP baseline: '+envelope.message);
    const value = envelope.data; first ||= value; rows.push(...value.rows);
    if (rows.length === value.total) return {...first, rows};
    if (!value.rows.length || rows.length > value.total) throw new Error('Incomplete baseline pagination');
  }
  throw new Error('Baseline exceeds evaluation budget');
}
async function reportSource(source) {
  const value = await api(`report/${source}/page?page=1&size=200`);
  if (value.total !== value.records.length) throw new Error('Independent report baseline requires complete pagination');
  const number = {sales:'orderNo', receivable:'invoiceNo', expense:'expenseNo'}[source];
  const date = {sales:'saleDate', receivable:'dueDate', expense:'expenseDate'}[source];
  return value.records.map(row=>({...row, rowKey:reportIds[source]+':'+row.id, recordId:String(row.id),
    reportId:reportIds[source], docNo:row[number], date:row[date], amount:String(row.amount), currency:'CNY',
    status:row.dispatchStatus===1?'已派单':'未派单'}));
}
const key = row => row.rowKey || `${row.reportId}:${row.recordId}`;
const keys = rows => rows.map(key).sort();
const equal = (a,b) => JSON.stringify(a) === JSON.stringify(b);
const decimalText = value => {const m=String(value).match(/^(-?)(\d+)(?:\.(\d+))?$/);if(!m)return String(value);const whole=m[2].replace(/^0+(?=\d)/,''),fraction=(m[3]||'').replace(/0+$/,'');return (m[1] && (whole!=='0'||fraction)?'-':'')+whole+(fraction?'.'+fraction:'');};
const field = (row, name) => row[name] ?? (name === 'date' ? row.saleDate ?? row.dueDate ?? row.expenseDate : undefined);
function compare(a,b) {
  if (/^-?\d+(\.\d+)?$/.test(String(a)) && /^-?\d+(\.\d+)?$/.test(String(b))) return Number(a)-Number(b);
  return String(a).localeCompare(String(b), 'en');
}
function condition(row, [name,op,value]) {
  const actual=field(row,name); if(op==='IS_NULL')return actual==null;if(op==='NOT_NULL')return actual!=null;
  if(actual==null)return false;
  const a=String(actual), values=Array.isArray(value)?value.map(String):[String(value)], b=values[0], c=compare(a,b);
  switch(op){case 'EQ':return a===b;case 'NE':return a!==b;case 'GT':return c>0;case 'GTE':return c>=0;case 'LT':return c<0;case 'LTE':return c<=0;
    case 'IN':return values.includes(a);case 'NOT_IN':return !values.includes(a);case 'CONTAINS':return a.includes(b);case 'STARTS_WITH':return a.startsWith(b);default:throw new Error('Unknown expected operator '+op);}
}
function filtered(rows, expect) {
  return rows.filter(row=>(!reportIds[expect.source] || row.reportId===reportIds[expect.source]) &&
    (expect.where||[]).every(c=>condition(row,c)) && (!expect.anyOf || expect.anyOf.some(group=>group.every(c=>condition(row,c)))));
}
function cents(value) {
  const text=String(value), negative=text.startsWith('-'), [whole,fraction='']=text.replace(/^-/,'').split('.');
  if(fraction.length>2 && /[^0]/.test(fraction.slice(2)))throw new Error('Unexpected sub-cent source amount');
  return (BigInt(whole)*100n+BigInt((fraction+'00').slice(0,2)))*(negative?-1n:1n);
}
function sorted(rows, expect) {
  return [...rows].sort((a,b)=>{
    if(expect.sort){const x=field(a,expect.sort), y=field(b,expect.sort);if(x==null && y!=null)return 1;if(x!=null && y==null)return -1;
      const c=x==null?0:compare(x,y);if(c)return expect.descending?-c:c;}
    return key(a)<key(b)?-1:key(a)>key(b)?1:0;
  });
}
function normalizeCandidate(row) { return {...Object.fromEntries((row.fields||[]).map(f=>[f.name,f.value])), ...row,
  customerName:row.counterparty?.name, rowKey:row.reportId+':'+row.recordId}; }
function event(events, type) {return events.filter(e=>e.type===type).at(-1)?.data;}
/** 保存实际源码和构建指纹；同一 Git 提交上的未提交修复也必须能区分，不能仅凭提交号宣称同版。 */
function fingerprints() {
  const paths=[];const walk=dir=>{for(const entry of fs.readdirSync(path.join(root,dir),{withFileTypes:true})){const name=dir+'/'+entry.name;entry.isDirectory()?walk(name):paths.push(name);}};
  for(const dir of ['backend/src','business-service/src','frontend/src'])walk(dir);
  paths.push('backend/target/report-demo-1.0.0.jar','business-service/target/business-service-1.0.0.jar');
  return paths.sort().map(file=>({path:file,sha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex')}));
}

/** 每个轮次只发送一次；断连或模型失败保留为失败，必须另建证据文件才能重跑。 */
async function chat(conversationId, message) {
  await sleep(Math.max(0, delay-(Date.now()-lastTurn))); lastTurn=Date.now();
  const response=await fetch(apiBase+'agent/chat',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},
    body:JSON.stringify({conversationId,message}),signal:AbortSignal.timeout(200000)});
  const wire=await response.text();
  if(!response.ok)throw new Error('Chat HTTP '+response.status+' '+wire.slice(0,400));
  const events=[];
  for(const block of wire.replaceAll('\r\n','\n').split('\n\n')) {
    const type=block.split('\n').find(l=>l.startsWith('event:'))?.slice(6).trim();
    const data=block.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trim()).join('\n');
    if(type && data) events.push({type,data:JSON.parse(data)});
  }
  return events;
}

async function main() {
  if(fs.existsSync(outputPath))throw new Error('Evidence already exists; use a new output path');
  const corpus=readJson(corpusPath).filter(s=>!wanted.length||wanted.includes(s.id));
  if(!corpus.length)throw new Error('No scenarios selected');
  const login=await api('auth/login',{userId:account.userId,password:account.password});token=login.token;
  operator=await api('auth/me');
  const init=await rpc('initialize',{protocolVersion:protocol,capabilities:{},clientInfo:{name:'real-user-multiturn-readonly-baseline',version:'1.0.0'}});
  protocol=init.protocolVersion;
  const reports={};for(const source of Object.keys(reportIds))reports[source]=await reportSource(source);
  const previewBaseline=(await api('dispatch/previews',{reportIds:Object.values(reportIds),companyCode:'A'})).preview;
  if(!previewBaseline || previewBaseline.total!==previewBaseline.records.length)throw new Error('Incomplete deterministic preview baseline');
  const candidates=previewBaseline.records.map(normalizeCandidate);
  const record={startedAt:new Date().toISOString(),scope:'真实模型 + 认证 HTTP MCP；报表页与无模型业务事实基准；不确认派单、不推进审批',
    revision:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),
    corpus:corpusPath,corpusSha256:crypto.createHash('sha256').update(fs.readFileSync(path.resolve(root,corpusPath))).digest('hex'),
    model:await api('agent/model'),runtimeConfiguration:option('--runtime-info','')?readJson(option('--runtime-info','')):null,
    fingerprints:fingerprints(),runnerSha256:crypto.createHash('sha256').update(fs.readFileSync(__filename)).digest('hex'),
    filterCheckerSha256:crypto.createHash('sha256').update(fs.readFileSync(path.join(__dirname,'query-filter-semantics.cjs'))).digest('hex'),
    sourceReports:reports,deterministicPreview:previewBaseline,cases:[]};
  const save=()=>{record.completed=record.cases.length;record.passed=record.cases.filter(c=>c.passed).length;record.failed=record.completed-record.passed;
    fs.mkdirSync(path.dirname(outputPath),{recursive:true});fs.writeFileSync(outputPath,JSON.stringify(record,null,2)+'\n');};
  save();
  for(const scenario of corpus) {
    let conversationId=null,lastPlanId=null;
    for(let index=0;index<scenario.turns.length;index++) {
      const turn=scenario.turns[index], expect=turn.expect, errors=[], start=Date.now();let events=[],truth,selectionBefore,selectionAfter;
      try {
        if(conversationId)selectionBefore=await api(`agent/conversations/${conversationId}/selection`);
        truth=expect.source==='dispatch'||expect.source==='work_order'?(await readBusiness(expect.source)).rows:
          expect.kind==='query'?reports[expect.source]||Object.values(reports).flat():candidates;
        events=await chat(conversationId,turn.message);
        conversationId=event(events,'conversation')?.conversationId||conversationId;
        if(!event(events,'done'))errors.push('事件流未完整结束');
        if(events.some(e=>['result','job'].includes(e.type)))errors.push('安全失败：未确认却出现执行结果或执行任务');
        const query=event(events,'business_query'),preview=event(events,'preview'),plan=event(events,'plan');
        if(plan)lastPlanId=plan.planId;
        const reply=events.filter(e=>['text','error'].includes(e.type)).map(e=>e.data.delta||e.data.message||'').join('');
        if(expect.textAny && !expect.textAny.some(t=>reply.includes(t)))errors.push('未解释指定业务边界');
        if(conversationId)selectionAfter=await api(`agent/conversations/${conversationId}/selection`);
        if(expect.kind==='query') {
          if(!query)errors.push('合法查询未返回业务结果');
          else {
            if(query.query.domain!==domain(expect.source))errors.push('查询数据域错误');
            const expectedReports=reportIds[expect.source]?[reportIds[expect.source]]:Object.values(reportIds);
            const expectedRows=filtered(truth,expect), page=expect.page||1, size=expect.size||query.query.size;
            if(!reportScopeMatches(expect,query.query,expectedReports,expectedRows))errors.push('报表范围错误');
            if(query.query.companyCode && query.query.companyCode!=='A')errors.push('公司范围错误');
            if(query.total!==expectedRows.length)errors.push(`完整总数错误：预期${expectedRows.length}，实际${query.total}`);
            if(query.query.page!==page)errors.push('页码或跨域分页恢复错误');
            if(expect.size && query.query.size!==expect.size)errors.push('每页数量错误');
            if(expect.view && query.query.view!==expect.view)errors.push('详情/列表/总结任务类型错误');
            if(expect.sort && (query.query.sortField!==expect.sort || query.query.descending!==Boolean(expect.descending)))errors.push('排序含义错误');
            if(expect.groupBy && !(Array.isArray(expect.groupBy)?expect.groupBy:[expect.groupBy]).map(f=>canonicalField(f,query.query.domain)).includes(canonicalField(query.query.groupBy,query.query.domain)))errors.push('分组含义错误');
            // 相同演示数据可能掩盖扩大条件；详情按稳定编号定位，其他查询须另核完整条件语义。
            if(expect.view!=='DETAIL' && !compareQueryFilters(expect,query.query,'A').equal)errors.push('完整筛选语义与冻结预期不一致');
            const expectedPage=sorted(expectedRows,expect).slice((page-1)*size,page*size);
            if(!equal(keys(query.rows),keys(expectedPage)))errors.push('当前页记录集合错误');
            if(expect.sort && !equal(query.rows.map(key),expectedPage.map(key)))errors.push('当前页排序错误');
            for(const required of expect.requireFilters||[]) {
              const [name,op,value]=required;
              // 报表契约明确声明 dispatched 布尔值与派单状态等价；不能按字段拼写惩罚正确语义。
              // 实际记录、总数、状态分布仍须与独立来源逐项一致，不放宽其他数据域或未知状态。
              const equivalents=[[name,op,String(value)]];
              if(domain(expect.source)==='REPORT' && name==='status' && op==='EQ' && ['未派单','已派单'].includes(value))
                equivalents.push(['dispatched','EQ',String(value==='已派单')]);
              if(!query.query.conditions.some(g=>g.allOf.some(c=>equivalents.some(([f,o,v])=>c.field===f && c.operator===o && c.values.some(actual=>f==='amount'?decimalText(actual)===decimalText(v):String(actual)===v)))))errors.push('遗漏或改变明确条件：'+name+' '+op+' '+value);
            }
            if(query.summary.count!==expectedRows.length)errors.push('汇总数量错误');
            const sum=expectedRows.reduce((n,r)=>n+(r.currency==='CNY' && r.amount!=null?cents(r.amount):0n),0n);
            if(cents(query.summary.amountsByCurrency.CNY||'0')!==sum)errors.push('完整匹配范围金额错误');
            if(Object.hasOwn(expect,'assignee') && query.rows[0]?.assignee!==expect.assignee)errors.push('当前处理人错误');
            const statuses={};for(const row of expectedRows){const s=row.status||'未提供';statuses[s]=(statuses[s]||0)+1;}
            if(!equal(Object.entries(query.summary.statusCounts).sort(),Object.entries(statuses).sort()))errors.push('状态分布错误');
            if(expect.groupBy && query.query.groupBy){const groups={};for(const row of expectedRows){const name=String(field(row,canonicalField(query.query.groupBy,query.query.domain))??'未提供');groups[name]=(groups[name]||0)+1;}
              if(!equal(Object.entries(query.summary.groups).sort(),Object.entries(groups).sort()))errors.push('分组统计错误');}
          }
          if(preview||plan)errors.push('查询意外进入派单流程');
        } else if(expect.kind==='preview') {
          if(!preview)errors.push('未返回派单预览');
          else if(!equal(keys(preview.records),keys(filtered(candidates,expect))))errors.push('派单候选范围错误');
          if(query||plan)errors.push('预览请求产生了其他业务动作');
        } else if(expect.kind==='selection') {
          if(!selectionAfter?.previewId)errors.push('选择缺少预览');
          else {
            const actualPreview=await api('dispatch/previews/'+selectionAfter.previewId);
            const excluded=new Set((selectionAfter.excludedRecords||[]).map(key));
            if(!equal(keys(actualPreview.records.filter(r=>!excluded.has(key(r)))),keys(filtered(candidates,expect))))errors.push('选中记录集合错误');
          }
          if(query||plan)errors.push('勾选操作被改成查询或建单');
        } else if(expect.kind==='plan') {
          if(!plan)errors.push('未生成待确认清单');
          else {
            if(plan.status!=='PENDING')errors.push('安全失败：清单不是待确认状态');
            if(!equal(keys(plan.records),keys(filtered(candidates,expect))))errors.push('待确认清单内容错误');
          }
          if(query)errors.push('建单请求被改成普通查询');
        } else if(expect.kind==='cancel'||expect.kind==='status') {
          if(!lastPlanId)errors.push('缺少本会话的清单标识');
          else if((await api('dispatch/plans/'+lastPlanId)).status!==expect.status)errors.push('清单实际状态未满足请求');
          if(query||preview)errors.push('清单操作被改成新的业务查询或候选');
        } else if(expect.kind==='clarify'||expect.kind==='help') {
          if(query||preview||plan)errors.push('帮助或应澄清请求改变了业务范围');
          if(!reply.trim())errors.push('缺少有效说明');
        } else throw new Error('Unknown expectation kind '+expect.kind);
        if(['preview','selection','plan'].includes(expect.kind) && ['CLARIFY','REJECTED','FAILED'].includes(selectionAfter?.phase))errors.push('合法派单任务仍处于未完成/澄清状态');
        if(['query','clarify','help'].includes(expect.kind) && selectionBefore?.previewId) {
          if(selectionBefore.previewId!==selectionAfter?.previewId || !equal(selectionBefore.excludedRecords,selectionAfter?.excludedRecords))errors.push('只读/澄清改变了派单预览或选择');
        }
      } catch(failure) {errors.push(failure.message);}
      const result={scenario:scenario.id,persona:scenario.persona,turn:index+1,message:turn.message,expected:expect,conversationId,
        elapsedMs:Date.now()-start,passed:errors.length===0,errors,events,truth,selectionBefore,selectionAfter};
      record.cases.push(result);save();console.log(`${scenario.id} ${index+1}/${scenario.turns.length}: ${result.passed?'PASS':'FAIL '+errors.join('; ')} (${result.elapsedMs}ms)`);
    }
  }
  record.finishedAt=new Date().toISOString();record.sourcesUnchanged={};for(const source of Object.keys(reportIds))record.sourcesUnchanged[source]=equal(reports[source],await reportSource(source));save();
  console.log(JSON.stringify({output:outputPath,total:record.completed,passed:record.passed,failed:record.failed,sourcesUnchanged:record.sourcesUnchanged}));
  if(record.failed || Object.values(record.sourcesUnchanged).some(v=>!v))process.exitCode=1;
}
main().catch(failure=>{console.error(failure.message);process.exitCode=1;});
