const fs = require('node:fs'), path = require('node:path');
const out = path.join(__dirname, 'semantic-v2-2026-10-03');
fs.mkdirSync(out, { recursive: true });
const n = (id,col,row,kind,title,...lines) => ({id,col,row,kind,title,lines});
const charts = [
{name:'01 输入到业务的总流程',note:'当前 active V2 主路径；自然语言生成清单与用户确认执行是两次独立交互。',nodes:[
n('input',1,0,'ui','用户输入自然语言','Vue AgentChat.send'),
n('buttons',0,1,'ui','表格和卡片按钮','结构化 REST；不经过模型'),
n('request',1,1,'ui','POST /api/agent/chat','message + conversationId','previewId + excludedRecords'),
n('auth',1,2,'guard','CORS → 会话认证','Bearer token → 数据库身份','覆盖客户端 X-User-Id'),
n('badAuth',2,2,'error','身份失效：拒绝请求','HTTP 401；不解析语义'),
n('inputCheck',1,3,'guard','检查非空与长度','trim；最多 2000 字符'),
n('badInput',2,3,'error','输入不合法','REST JSON 业务错误码 400','尚未进入语义解析'),
n('route',1,4,'guard','进入 active V2 语义入口','真实模型调用链'),
n('entry',1,5,'agent','SemanticConversationService','新建或验证会话归属','检查历史数据可读性'),
n('lease',1,6,'store','配额 + 对话租约','同会话串行；读取状态','本轮 requestId/previewVersion'),
n('leaseFail',0,6,'error','配额或会话租约失败','限流/依赖异常/同会话忙','外层 SSE error + done'),
n('semantic',1,7,'model','解析本轮结构化语义','保护实体；模型输出 V2 JSON','详见第 02 页'),
n('planner',1,8,'agent','校验并合并范围','公司 / 报表 / 排除项','详见第 03、04 页'),
n('reject',2,8,'error','澄清 / 拒绝 / 失败','记录 unresolved 和原因','不回落旧范围执行业务'),
n('action',1,9,'guard','是哪一种 action？','查询 / 建单 / 解释规则'),
n('other',2,10,'agent','HELP / CANCEL_PLAN / SHOW_RESULT','服务端帮助、取消或读结果','均不执行实际派单'),
n('query',1,10,'business','确定性查询流程','权限 + 当前目录/规则版本','MCP report_records → 业务库'),
n('preview',1,11,'store','复用或生成预览','本地规则求值；预览持久化','排除项与预览绑定'),
n('prepare',1,12,'guard','PREPARE_DISPATCH？','有效范围和排除项已确认'),
n('plan',1,13,'store','创建 PENDING 清单','语义幂等键 semantic:requestId','没有调用 dispatch_submit'),
n('sse',1,14,'ui','SSE / REST 返回前端','自然语言：事实文本和卡片事件','按钮：结构化 JSON 结果'),
n('wait',1,15,'ui','用户核对并点击确认','POST /api/dispatch/jobs'),
n('execute',1,16,'business','持久任务认领与复核','DispatchJobService → DispatchService','逐条 MCP 提交；详见第 06、08 页'),
n('result',1,17,'store','结果保存与展示','SUCCESS / FAILED / SKIPPED','UNKNOWN → REVIEW_REQUIRED'),
],edges:[['input','request'],['request','auth'],['auth','badAuth','失效'],['auth','inputCheck','有效'],['inputCheck','badInput','不合法'],['inputCheck','route','合法'],['route','entry','active'],['entry','lease'],['lease','leaseFail','失败'],['lease','semantic','取得租约'],['semantic','planner'],['semantic','reject','不可靠'],['planner','reject','不确定/无权限'],['planner','action','允许继续'],['action','other','辅助动作'],['action','query','查询类动作'],['query','preview'],['preview','prepare'],['prepare','plan','是'],['prepare','sse','否'],['plan','sse'],['other','sse'],['reject','sse'],['sse','wait','有清单'],['wait','execute'],['execute','result'],['buttons','query','报表选择/预览'],['buttons','plan','从固定预览建单']]},
{name:'02 模型语义解析与校验',note:'模型只负责 action / scopeChanges / restrictions；服务端校验原文证据。应用层最多追加一次解析修复。',nodes:[
n('message',1,0,'agent','本轮原始 message','原文进入受信任服务边界'),
n('context',0,1,'store','权威上下文','desired / effective / phase','未解状态、排除数、上次 action'),
n('terms',2,1,'agent','权限过滤后的报表候选','ReportRef + mentionedReportTerms','词典命中只提供实体线索'),
n('protect',1,1,'guard','SensitiveData.modelText','手机号 / 证件号 / 邮箱','替换为本轮随机 REF 占位符'),
n('payload',1,2,'agent','构造 JSON 输入','currentMessage + context','不带完整助手历史或业务明细'),
n('options',1,4,'model','构造真实模型调用','temperature 0；maxTokens 2400','禁用内部工具执行和回调'),
n('schema',1,5,'model','规则提示 + V2 JSON Schema','native-schema 默认关闭；按端点验证','模型名由实际模型配置提供'),
n('call',1,6,'model','同步 call().content()','只取完整 JSON 内容','模型不能实际派单或编写 SQL'),
n('decode',1,7,'guard','IntentCodec 严格解码','version=2；必填/类型/枚举','未知字段和尾随内容拒绝'),
n('ground',1,8,'guard','验证原文证据和协议不变量','evidence 归一化后属于原文','mentions 属于 evidence'),
n('invariants',1,9,'guard','检查动作、顺序和限制','公司仅一次 REPLACE/CLEAR','记录修改在后；动作禁止不冲突'),
n('coverage',1,10,'guard','报表实体覆盖检查','查询类输出必须覆盖','本轮所有已命中的报表候选'),
n('valid',1,11,'guard','所有检查通过？','JSON / 原文 / 报表覆盖'),
n('repair',2,11,'model','第一次失败：独立重解析','同一本轮输入 + 修复指令','不采用模型猜测或关键词兜底'),
n('failed',2,13,'error','第二次失败 / 调用异常','返回友好原因与未解状态','传输异常不走格式修复循环'),
n('restore',1,12,'agent','恢复 REF 到本地原实体','恢复 mentions / evidence','在原 message 上再次校验'),
n('intent',1,13,'agent','交付 SemanticIntent','Source=MODEL','交给 Planner，不调用业务工具'),
],edges:[['message','protect'],['context','payload'],['terms','payload'],['protect','payload'],['payload','options'],['options','schema'],['schema','call'],['call','decode'],['decode','ground'],['ground','invariants'],['invariants','coverage'],['coverage','valid'],['valid','restore','通过'],['valid','repair','首次失败'],['repair','call','最多一次'],['valid','failed','再次失败'],['call','failed','传输/模型异常'],['restore','intent','再校验通过'],['restore','failed','再校验失败']]},
{name:'03 多轮范围与动作规划',note:'desired 是请求范围，effective 是最近成功范围；拒绝的新输入不会被悄悄替换为旧的成功查询。',nodes:[
n('read',1,0,'store','读取 DialogueState','按会话、租户、操作者归属','hydrate 新的显式 UI 预览'),
n('coverage',1,1,'guard','requireCoverage / requireAction','CLARIFY 或禁止冲突立即阻断','对应字段置 unresolved'),
n('draft',1,2,'agent','在临时 draft 上合并','按 scopeChanges 原始顺序','全部公司/报表操作成功才提交'),
n('company',0,3,'agent','COMPANY','REPLACE：NFKC/去公司后缀/大写','CLEAR：全部可见公司'),
n('reports',2,3,'agent','REPORTS','REPLACE / ADD / REMOVE / CLEAR','原话逐项映射到 reportId'),
n('records',0,5,'agent','RECORDS 暂不应用','留到最终范围的预览完成','详见第 05 页'),
n('reportGate',2,4,'guard','报表链接必须可靠','EXACT/唯一 ALIAS 可继续','FUZZY/AMBIGUOUS/NONE 需澄清'),
n('merge',1,5,'store','提交 desired 范围','未提及字段保持不变','清除已解决字段的未解标记'),
n('noPartial',2,6,'error','任一合并失败','不提交部分 draft','相关 unresolved 标记保留'),
n('validate',1,6,'guard','执行前 validate desired','公司范围、报表可用与权限','移除后为空 / 未解字段拒绝'),
n('action',1,7,'guard','按 action 分支','语义层没有 EXECUTE 动作'),
n('preview',0,8,'business','PREVIEW / EXPLAIN_RULES','刷新或复用预览','规则说明来自实际预览'),
n('prepare',1,8,'business','PREPARE_DISPATCH','复用或刷新相同范围预览','恢复选择后生成 PENDING'),
n('aux',2,8,'agent','HELP / CANCEL / SHOW_RESULT','服务端文本或当前清单','不靠模型生成业务结论'),
n('effective',1,9,'store','仅查询成功后更新 effective','previewId 对应成功数据范围','建单成功更新 planId/PLAN_READY'),
n('failure',2,10,'store','失败记录 phase 和 lastReason','422 → CLARIFY；其他业务拒绝','基础设施异常 → FAILED'),
n('next',1,11,'ui','下一轮继续','这些/剩下的参考权威状态','未解条件不能沿用旧数据建单'),
],edges:[['read','coverage'],['coverage','draft','允许'],['coverage','failure','需澄清'],['draft','company'],['draft','reports'],['company','merge'],['reports','reportGate'],['reportGate','merge','全部成功'],['reportGate','noPartial','不可靠'],['draft','records','跳过记录操作'],['merge','validate'],['validate','action','有权且明确'],['validate','failure','拒绝'],['noPartial','failure'],['action','preview','查询/规则'],['action','prepare','建单'],['action','aux','辅助'],['preview','effective'],['prepare','effective'],['aux','next'],['effective','next'],['failure','next']]},
{name:'04 报表实体解析',note:'词典和模糊算法解决“这段原话对应哪张报表”，不负责整句的否定、动作或追加/排除语义。',nodes:[
n('mention',1,0,'agent','模型提取的报表原话','例如 销售报表 / 应收台账'),
n('visible',0,1,'guard','先限定当前可派单目录','同租户 + 已发布/生效/可用','报表权限 + 治理灰度可见'),
n('normalize',1,1,'agent','TextNormalizer.normalize','NFKC + 小写 + 去空白'),
n('scan',1,2,'agent','TermIndex 最长不重叠扫描','名称 / 编码 / ID / 别名','英文词还检查词边界'),
n('hit',1,3,'guard','命中词典说法？','按优先类型关联报表'),
n('identity',0,4,'agent','名称 > 编码 > ID > 别名','唯一 → EXACT 或 ALIAS','一词多报表 → AMBIGUOUS'),
n('fuzzy',2,4,'agent','无命中：模糊候选评分','编辑距离 / 二元组 Dice','包含相似度取最大值'),
n('threshold',2,5,'guard','候选评分阈值与领先差','默认阈值 0.6；歧义差 0.15','治理策略可提供当前参数'),
n('result',1,6,'agent','ResolveResult','EXACT / ALIAS / FUZZY','AMBIGUOUS / ALL / NONE'),
n('planner',1,7,'guard','V2 Planner 二次收紧','只接受可靠唯一链接','拒绝 FUZZY、ALL、未识别残片'),
n('accept',0,8,'agent','稳定 reportId','按目录顺序合并范围','不使用模型直接提供的内部 ID'),
n('clarify',2,8,'error','提示完整名称或候选报表','用户补充名称或显式 UI 选择','不自动选择最高分候选'),
],edges:[['mention','normalize'],['visible','scan'],['normalize','scan'],['scan','hit'],['hit','identity','是'],['hit','fuzzy','否'],['fuzzy','threshold'],['threshold','result'],['identity','result'],['result','planner'],['planner','accept','EXACT/唯一 ALIAS'],['planner','clarify','模糊/歧义/未知']]},
{name:'05 预览、规则与记录选择',note:'模型不读取全部业务明细或自行判断规则命中；记录描述只有在服务器真实预览中唯一定位后才转为 RecordKey。',nodes:[
n('scope',1,0,'guard','校验最终 desired','是否和 effective 相同？','是否有 ACTIVE、版本有效预览？'),
n('reuse',0,1,'store','符合条件：复用预览','仅记录修改/建单可复用','普通 PREVIEW 通常重新查'),
n('new',1,1,'agent','需要重新查询','PreviewCommand：明确范围','先保存 QUERYING 状态'),
n('stamp',1,2,'guard','查询前记录版本指纹','目录 / 当前规则 / 权限','配额与对话租约持续检查'),
n('mcp',1,3,'business','MCP report_records(cursor)','可信 tenantId/operatorId','每批 500；按 ID 递增'),
n('query',1,4,'business','业务侧权限和粗筛','账号当前权限 + 公司范围','租户 / 未派单 / 游标条件'),
n('rules',1,5,'agent','Agent 侧当前规则求值','具体公司规则优先于通配','Aviator Boolean；异常按未命中'),
n('persist',1,6,'store','生成持久化预览','超过 5000 → 分批持久化','确认最新 requestVersion 后激活'),
n('supersede',2,6,'store','激活新预览时','旧 ACTIVE → SUPERSEDED','旧 PENDING 清单 → EXPIRED'),
n('bind',1,7,'guard','绑定最终预览及 UI 选择','previewId 相同才接纳','同范围刷新可迁移并重新验证'),
n('stale',2,8,'error','旧选择不可恢复 / 范围变了','设置 unresolvedRecords','要求重新选择；不默认全部派'),
n('rows',1,8,'guard','需要记录修改时读取真实预览','最多 5000 条；每页 100','更多记录先缩小范围'),
n('match',1,9,'agent','SelectionResolver 依次匹配','单据号精确 → 去类别前缀','仍无命中 → label 包含匹配'),
n('unique',1,10,'guard','是否恰好匹配一条？','重复单据号或描述歧义拒绝'),
n('key',1,11,'store','转换 RecordKey','reportId + 字符串 recordId','ADD 排除；REMOVE 恢复'),
n('unclear',2,10,'error','0 条或多条命中','返回澄清提示','请用表格精确勾选'),
n('selection',1,12,'guard','校验排除项都属于当前预览','CLEAR 恢复全部','REPLACE 替换排除集合'),
n('action',1,13,'guard','PREPARE_DISPATCH？','否则返回预览或规则说明'),
n('zero',0,14,'ui','0 条候选或仅查询','不创建清单','返回事实文本与 selection'),
n('plan',1,14,'store','PlanService 创建清单','固定预览 + 排除项 + 幂等键','条数上限 5000；PENDING'),
n('card',1,15,'ui','持久化卡片并 SSE 返回','有效预览约 30 分钟','待确认清单约 10 分钟'),
],edges:[['scope','reuse','可复用'],['scope','new','需刷新'],['new','stamp'],['stamp','mcp'],['mcp','query'],['query','rules'],['rules','mcp','还有下一批'],['rules','persist','扫描完成'],['persist','supersede'],['persist','bind'],['reuse','bind'],['bind','stale','选择来源无效'],['bind','rows','修改或验证排除项'],['bind','action','无需变更选择'],['rows','match','自然语言记录变更'],['rows','selection','仅验证已有/UI键'],['match','unique'],['unique','unclear','非唯一'],['unique','key','唯一'],['key','selection'],['selection','stale','有缺失键'],['selection','action','全部有效'],['action','zero','查询/零候选'],['action','plan','生成清单'],['plan','card'],['zero','card']]},
{name:'06 确认、执行与结果核对',note:'本页从用户点击确认开始；与本轮模型解析分离。未知结果必须先查询原请求号，不能盲目重发。',nodes:[
n('confirm',1,0,'ui','用户点击确认卡片','POST /api/dispatch/jobs；不经过模型'),
n('gate',1,1,'guard','归属、权限、TTL、版本复核','已 EXECUTED → 返回原结果','不是有效 PENDING 则拒绝'),
n('invalid',0,1,'error','清单不可确认','返回当前状态或错误','无权限/失效/正在执行等'),
n('replay',2,1,'store','重放已有清单结果','原状态已 EXECUTED','直接返回，不再调用网关'),
n('claim',1,2,'store','CAS 认领执行权','确认 PENDING / 重试 EXECUTED','→ EXECUTING + executionVersion'),
n('check',1,3,'guard','按清单 ID 重读和规则复核','当前记录仍待派单且有权','复核失败且未发送可恢复 PENDING'),
n('item',1,4,'guard','逐条是否仍符合？','取消/过期/失权不能继续'),
n('skip',0,5,'store','SKIPPED','记录已变化；未调用网关'),
n('intent',1,5,'store','先持久化发送意图','requestId=planId-itemId','条目 UNKNOWN + 审计 INTENT'),
n('call',1,6,'business','MCP dispatch_submit','操作者 / 记录 / 规则模式','原认领执行版本冻结传递'),
n('business',1,7,'guard','业务服务重新读取有效身份','公司、报表权限','锁用户、幂等请求和清单'),
n('duplicate',1,8,'guard','稳定请求号和负载是否一致？','同号不同负载 → 拒绝','已 SUCCESS → 重放成功'),
n('fence',1,9,'guard','验证已确认清单和条目','EXECUTING / confirmedBy','executionVersion / UNKNOWN 意图'),
n('source',1,10,'business','原子业务事务复核','锁目录、规则和源记录','版本、规则、当前待派单状态'),
n('commit',1,11,'store','事务提交业务状态与结果','业务表状态 + 请求结果同事务','当前是本地业务库，非外部 ERP'),
n('persist',1,12,'store','Agent 保存逐条结果及审计','SUCCESS / FAILED / SKIPPED','超时/不确定 → UNKNOWN'),
n('uncertain',1,13,'guard','有未知/保存失败/执行中断？','所有条目遍历并聚合'),
n('finish',0,14,'store','EXECUTED','保存实际成功和失败数','返回结果卡片'),
n('review',2,14,'store','REVIEW_REQUIRED','不能认为全部失败','不允许盲目自动重发'),
n('lookup',2,15,'business','用户或管理员显式核对','MCP dispatch_lookup','锁定读等待在途结果'),
n('known',2,16,'guard','核对结果明确？','SUCCESS / FAILED / NOT_FOUND','UNKNOWN 则继续等待'),
n('resolved',1,17,'store','更新未知条目','NOT_FOUND → 未发送的明确失败','全部明确后解除待核对状态'),
n('retry',1,18,'guard','用户显式 retry-failed','仅 FAILED；原操作者必须有效','重新复核，沿用原请求号'),
n('close',0,18,'agent','管理员安全关闭明确失败','权限 + 原清单范围检查','未知/在途状态不能强行关闭'),
],edges:[['confirm','gate'],['gate','invalid','不可确认'],['gate','replay','已执行'],['gate','claim','允许确认'],['claim','check'],['check','item'],['item','skip','不符合'],['item','intent','符合'],['intent','call'],['call','business'],['business','duplicate'],['duplicate','fence','新请求/失败重试'],['duplicate','persist','已成功：重放'],['duplicate','persist','协议错误：保守核对'],['fence','source'],['source','commit'],['commit','persist'],['skip','persist'],['persist','item','还有下一条'],['persist','uncertain','全部处理完成'],['call','persist','调用异常：UNKNOWN'],['uncertain','finish','否'],['uncertain','review','是'],['review','lookup'],['lookup','known'],['known','review','UNKNOWN'],['known','resolved','明确'],['resolved','finish','全部明确'],['finish','retry','有明确失败且用户要求'],['retry','claim','重新认领并复核'],['finish','close','无需重试']]},
{name:'07 状态、SSE 与异常恢复',note:'V2 多轮依据持久化状态，不依赖模型助手历史。SSE 是业务事件流，不是当前主路径的模型 token 流。',nodes:[
n('conversation',1,0,'store','agent_conversation','会话归属、标题、请求版本','deleted/erasure 拒绝继续写'),
n('lease',1,1,'guard','semantic_dialogue 租约','默认单轮 180 秒','租约 + version CAS；不跨模型持事务'),
n('model',1,2,'model','模型调用 / 规则扫描','阶段之间 check 租约与配额','被取消或超时不能提交业务变更'),
n('save',1,3,'store','fenced() 小事务保存','校验 token / version / expiry','state_json 脱敏持久化'),
n('turn',0,4,'store','semantic_turn','脱敏原文、意图、模型','结果、原因、耗时、状态版本'),
n('business',2,4,'store','预览 / 清单 / 条目 / 追溯','业务数据和状态持久化','更新正文与卡片记录'),
n('text',1,5,'agent','服务端生成事实回复','数量、状态、规则来自服务','不再请求模型润色回答'),
n('events',1,6,'ui','SSE 事件','conversation / text / preview','plan / selection / error / done'),
n('seq',1,7,'guard','requestId:seq + 输出脱敏','前端按请求/序号校验','卡片、文字与选择分别处理'),
n('front',1,8,'ui','前端更新并落地历史恢复线索','忽略旧账号/旧会话响应','selection 绑定指定 previewId'),
n('disconnected',2,9,'error','SSE 断开 / 页面刷新','不保证原文本流续传','读取已经保存的业务状态'),
n('restore',1,10,'ui','历史 + card-states + selection','必要时查询持久化 preview job','选择恢复失败先阻止建单'),
n('phase',0,9,'store','错误阶段持久化','422 → CLARIFY；业务拒绝','REJECTED；其他异常 FAILED'),
n('next',1,11,'agent','新的一轮','重新取权威状态/目录','上一轮禁止仅 THIS_TURN'),
n('previewJobs',2,11,'agent','独立异步预览接口','UI preview_job 可恢复','active 语义查询直接调用 PreviewService'),
],edges:[['conversation','lease'],['lease','model'],['model','save'],['save','turn'],['save','business'],['turn','text'],['business','text'],['text','events'],['events','seq'],['seq','front'],['front','next','正常完成'],['front','disconnected','断线/刷新'],['disconnected','restore'],['restore','next'],['model','phase','异常/不可靠'],['phase','events'],['previewJobs','restore','有独立预览任务']]},
];

// Persistent dispatch commands augment the confirmation path; retain dated historical exports.
const execution = charts[5];
execution.nodes.forEach(node => { if (node.id !== 'confirm') node.row += 3; });
execution.nodes.push(
  n('acceptJob',1,1,'store','接受稳定幂等任务','按当前权限检查；冻结清单版本','重复请求返回同一任务'),
  n('queueJob',1,2,'store','dispatch_job QUEUED','最多等待 10 分钟','请求快速返回任务标识'),
  n('workerJob',1,3,'business','后台认领 RUNNING 任务','跨实例 CAS；重新读取操作者','以下仍复用原派单与核对服务')
);
execution.edges = execution.edges.filter(edge => !(edge[0] === 'confirm' && edge[1] === 'gate'));
execution.edges.push(['confirm','acceptJob'],['acceptJob','queueJob'],['queueJob','workerJob'],['workerJob','gate']);
execution.edges = execution.edges.map(edge => edge[0] === 'retry' && edge[1] === 'claim' ? ['retry','acceptJob','新任务，原请求号'] : edge);
charts.push({name:'08 异步派单任务与恢复',note:'确认、明确失败重试、结果核对和手工派单使用持久任务；真实业务服务仍逐项事务复核。',nodes:[
 n('button',1,0,'ui','用户明确提交操作','确认 / 重试 / 核对 / 手工派单'),
 n('key',1,1,'ui','先保存稳定任务幂等键','POST /api/dispatch/jobs','丢失响应时沿用原键'),
 n('admit',1,2,'guard','归属、权限、参数与队列预算','冻结清单版本；同清单仅一在途任务','不经过模型决定是否执行'),
 n('queued',1,3,'store','持久化 QUEUED','返回任务号；排队最多 10 分钟','租户最多 100、用户最多 8 在途任务'),
 n('claim',1,4,'store','跨实例认领 RUNNING','FOR UPDATE SKIP LOCKED','5 分钟租约，30 秒续租'),
 n('expired',0,4,'error','未启动即排队过期','FAILED；未发送业务请求','用户刷新后重新确认'),
 n('service',1,5,'business','重新读身份并复用业务服务','确认 / retryFailed / reconcile','手工派单持久化逐记录清单'),
 n('mcp',1,6,'business','真实 HTTP MCP 业务服务','冻结原认领 executionVersion','稳定请求号、规则和源记录复核'),
 n('result',1,7,'store','保存可靠业务结果和任务结果','SUCCEEDED 是任务完成','条目成功/失败以实际结果为准'),
 n('query',1,8,'ui','GET /api/dispatch/jobs/{id}','前端每秒读取持久任务','结果卡片与业务页刷新'),
 n('disconnect',2,8,'error','页面刷新 / 连接中断','本机保留任务号和原幂等键','后台继续，不自动创建新任务'),
 n('recover',2,6,'store','运行租约过期或执行崩溃','任务 FAILED，不重新入队','清单按持久状态恢复或核对'),
 n('review',2,7,'guard','读取清单与原请求号结果','未知结果必须先核对','明确失败才允许用户再次重试'),
],edges:[['button','key'],['key','admit'],['admit','queued'],['queued','claim'],['queued','expired','超时未启动'],['claim','service'],['service','mcp'],['mcp','result'],['result','query'],['query','disconnect','断线'],['disconnect','query','仅恢复读取'],['claim','recover','执行中断'],['recover','review'],['review','query','查看持久状态']]});

const colors={ui:['#eaf2ff','#386bc1'],agent:['#edf5ff','#3778a8'],model:['#f3edff','#8751bf'],business:['#e8f7ef','#268255'],guard:['#fff4dc','#b58022'],error:['#fff0ef','#ba534e'],store:['#edf1f5','#60768b']};
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
function wrap(s,max=33){let chunks=[],line='',count=0;for(const ch of s){const weight=ch.charCodeAt(0)>255?1.7:1;if(count+weight>max){chunks.push(line);line='';count=0;}line+=ch;count+=weight;}if(line)chunks.push(line);return chunks;}
function geom(node){return {x:60+node.col*410,y:135+node.row*165,w:340,h:110};}
function points(a,b){let A=geom(a),B=geom(b);if(a.col===b.col){const sx=A.x+A.w/2,sy=A.y+A.h,ex=B.x+B.w/2,ey=B.y;if(b.row===a.row+1)return [[sx,sy],[ex,ey]];const lane=A.x-28;return [[A.x,A.y+A.h/2],[lane,A.y+A.h/2],[lane,B.y+B.h/2],[B.x,B.y+B.h/2]];}const right=b.col>a.col;const sx=right?A.x+A.w:A.x,ex=right?B.x:B.x+B.w,sy=A.y+A.h/2,ey=B.y+B.h/2;const lane=right?sx+35:sx-35;return [[sx,sy],[lane,sy],[lane,ey],[ex,ey]];}
function render(chart,index){const W=1340,H=300+Math.max(...chart.nodes.map(x=>x.row))*165;const byId=Object.fromEntries(chart.nodes.map(x=>[x.id,x]));let parts=[`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(chart.name)}"><defs><marker id="arrow${index}" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto"><path d="M0,0 L10,4 L0,8 Z" fill="#657891"/></marker></defs><rect width="100%" height="100%" fill="#fff"/><g font-family="Microsoft YaHei,PingFang SC,Arial,sans-serif"><text x="60" y="44" font-size="26" font-weight="700" fill="#15334b">${esc(chart.name)}</text><text x="60" y="79" font-size="14" fill="#53697e">${esc(chart.note)}</text>`];
for(const [from,to,label=''] of chart.edges){if(!byId[from]||!byId[to])throw Error('Unknown edge');const p=points(byId[from],byId[to]);const d=p.map((v,i)=>(i?'L':'M')+v.join(',')).join(' ');parts.push(`<path d="${d}" fill="none" stroke="#657891" stroke-width="1.7" marker-end="url(#arrow${index})"/>`);if(label){const A=p[0],B=p[1],x=(A[0]+B[0])/2,y=(A[1]+B[1])/2-8;const width=label.length*13+10;parts.push(`<rect x="${x-width/2}" y="${y-14}" width="${width}" height="20" fill="white" rx="3"/><text x="${x}" y="${y}" font-size="12" text-anchor="middle" fill="#53697e">${esc(label)}</text>`);}}
for(const node of chart.nodes){const g=geom(node),[fill,stroke]=colors[node.kind];if(node.kind==='guard')parts.push(`<polygon points="${g.x+g.w/2},${g.y} ${g.x+g.w},${g.y+g.h/2} ${g.x+g.w/2},${g.y+g.h} ${g.x},${g.y+g.h/2}" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>`);else parts.push(`<rect x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" rx="12" fill="${fill}" stroke="${stroke}" stroke-width="1.5"/>`);const texts=[...wrap(node.title),...node.lines.flatMap(s=>wrap(s))];if(texts.length>5)throw Error('Node text too long: '+node.id);texts.forEach((line,i)=>parts.push(`<text x="${g.x+g.w/2}" y="${g.y+g.h/2-(texts.length-1)*10+i*20+5}" text-anchor="middle" font-size="${i===0?15:13}" font-weight="${i===0?700:400}" fill="#1e354c">${esc(line)}</text>`));}
parts.push(`<text x="60" y="${H-26}" font-size="12" fill="#64748b">2026-10-03 · 依据当前工作区源码 · 模型解析 / 确定性业务 / 实际派单边界分离</text></g></svg>`);return parts.join('');}
function mermaid(chart){return 'flowchart TD\n'+chart.nodes.map(node=>{const text=[node.title,...node.lines].join('<br/>').replaceAll('"','&quot;');return `  ${node.id}${node.kind==='guard'?'{"'+text+'"}':'["'+text+'"]'}:::${node.kind}`;}).join('\n')+'\n'+chart.edges.map(([a,b,label])=>`  ${a} -->${label?'|"'+label+'"|':''} ${b}`).join('\n')+'\n'+Object.entries(colors).map(([k,[f,s]])=>`  classDef ${k} fill:${f},stroke:${s},color:#1e354c`).join('\n');}
const svgs=charts.map(render);
charts.forEach((chart,i)=>{fs.writeFileSync(path.join(out,`0${i+1}.svg`),svgs[i]);fs.writeFileSync(path.join(out,`0${i+1}.mmd`),mermaid(chart));});
let xml='<mxfile host="app.diagrams.net" modified="2026-10-03T00:00:00.000Z" agent="Codex" version="26.0.0">';
charts.forEach((chart,index)=>{xml+=`<diagram id="semantic-${index+1}" name="${esc(chart.name)}"><mxGraphModel dx="1340" dy="2000" grid="1" gridSize="10" page="0"><root><mxCell id="0"/><mxCell id="1" parent="0"/>`;for(const node of chart.nodes){const g=geom(node),[fill,stroke]=colors[node.kind],value=[`<b>${node.title}</b>`,...node.lines].join('<br>');xml+=`<mxCell id="${node.id}" value="${esc(value)}" style="${node.kind==='guard'?'rhombus;':'rounded=1;'}whiteSpace=wrap;html=1;fillColor=${fill};strokeColor=${stroke};fontColor=#1e354c;fontFamily=Microsoft YaHei;fontSize=14;" vertex="1" parent="1"><mxGeometry x="${g.x}" y="${g.y}" width="${g.w}" height="${g.h}" as="geometry"/></mxCell>`;}chart.edges.forEach(([a,b,label=''],i)=>{xml+=`<mxCell id="e${i}" value="${esc(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;strokeColor=#657891;fontFamily=Microsoft YaHei;fontSize=12;" edge="1" parent="1" source="${a}" target="${b}"><mxGeometry relative="1" as="geometry"/></mxCell>`;});xml+='</root></mxGraphModel></diagram>';});xml+='</mxfile>';fs.writeFileSync(path.join(out,'semantic-v2.drawio'),xml);
fs.writeFileSync(path.join(out,'flows.json'),JSON.stringify(charts,null,2));
const html=`<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>用户输入与语义 V2 完整流程</title><style>*{box-sizing:border-box}body{margin:0;font-family:"Microsoft YaHei",sans-serif;color:#1d354a;background:#f1f5f9}header{padding:24px 32px;background:#102a43;color:white}header h1{font-size:25px;margin:0 0 10px}header p{margin:0;color:#c3d4e7;font-size:14px}.bar{position:sticky;top:0;z-index:3;display:flex;gap:8px;flex-wrap:wrap;background:white;padding:12px 20px;border-bottom:1px solid #d7e2ed}button,a{font:inherit}button{cursor:pointer;border:1px solid #c5d5e5;background:#fff;border-radius:7px;padding:8px 11px;color:#214b70}button.active{background:#214b70;color:white}.zoom{padding:10px 24px;display:flex;align-items:center;gap:10px;background:#e8eef5}main{padding:22px;overflow:auto}.chart{background:white;max-width:1800px;margin:auto;box-shadow:0 2px 12px #102a4310}.chart svg{display:block;width:100%;height:auto}.hidden{display:none}small{color:#52687a}a{color:#27649d}.legend{display:flex;gap:14px;flex-wrap:wrap;margin-left:auto;font-size:12px}.legend span{padding:4px 7px;border-radius:4px}.summary{padding:14px 24px;background:#fff;border-bottom:1px solid #d7e2ed;line-height:1.7;font-size:14px}</style></head><body><header><h1>用户输入 → 结构化语义 → 权威状态 → 业务执行</h1><p>当前运行：active 语义 V2 · deepseek-v4.1-flash · native JSON Schema · MCP 双服务 · 2026-10-03</p></header><div class="summary">模型只解析本轮动作和范围变化；原文证据、实体映射、权限、规则、版本和实际派单由服务端负责。<br>七页可切换、缩放；下方节点颜色区分责任边界。日期/金额/排序等新增筛选当前不可表达，应要求澄清。<a href="semantic-v2.drawio" download>下载可编辑 draw.io</a> · <a href="说明.md">详细说明与 JSON 示例</a></div><nav class="bar">${charts.map((c,i)=>`<button data-page="${i}" class="${i===0?'active':''}">${c.name}</button>`).join('')}</nav><div class="zoom"><button id="less">缩小</button><button id="fit">适配宽度</button><button id="more">放大</button><small id="percent">100%</small><div class="legend">${Object.entries({ui:'前端',agent:'Agent 服务',model:'模型',business:'MCP 业务',guard:'校验/判断',store:'持久状态',error:'拒绝/异常'}).map(([k,t])=>`<span style="background:${colors[k][0]};color:${colors[k][1]}">${t}</span>`).join('')}</div></div><main>${svgs.map((s,i)=>`<section class="chart ${i?'hidden':''}" data-chart="${i}">${s}</section>`).join('')}</main><script>let selected=0,zoom=1;const sections=[...document.querySelectorAll('[data-chart]')];function update(){sections.forEach((s,i)=>{s.classList.toggle('hidden',i!==selected);s.style.width=(100*zoom)+'%';s.style.maxWidth=zoom>1?'none':'1800px'});document.querySelectorAll('[data-page]').forEach(b=>b.classList.toggle('active',Number(b.dataset.page)===selected));document.querySelector('#percent').textContent=Math.round(zoom*100)+'%'}document.querySelectorAll('[data-page]').forEach(b=>b.onclick=()=>{selected=Number(b.dataset.page);zoom=1;update();window.scrollTo({top:0})});document.querySelector('#less').onclick=()=>{zoom=Math.max(.35,zoom-.15);update()};document.querySelector('#more').onclick=()=>{zoom=Math.min(2.5,zoom+.15);update()};document.querySelector('#fit').onclick=()=>{zoom=1;update()};</script></body></html>`;
fs.writeFileSync(path.join(out,'index.html'),html);
const fence=String.fromCharCode(96).repeat(3);
const sections=charts.map((chart,i)=>`## ${chart.name}\n\n${chart.note}\n\n${fence}mermaid\n${mermaid(chart)}\n${fence}\n`);
fs.writeFileSync(path.join(out,'流程图.md'),'# 当前用户输入和语义 V2 的完整流程图\n\n2026-10-03，按当前工作区代码绘制。配合 [详细说明](说明.md) 阅读。\n\n'+sections.join('\n'));
console.log(`Generated ${charts.length} SVG/Mermaid pages, editable draw.io and standalone HTML in ${out}`);
