/**
 * 业务界面的中文展示词典；仅转换显示文本，不修改接口枚举、权限判断或执行状态。
 * 未收录的代码原样保留以便排查；成功、已执行和结果待核对必须保持不同含义。
 */
const status = {
  SUCCESS: '成功', SUCCEEDED: '已完成', FAILED: '失败', ERROR: '发生异常', OK: '正常',
  PENDING: '待处理', QUEUED: '排队中', RUNNING: '执行中', EXECUTING: '执行中',
  EXECUTED: '已执行', REVIEW_REQUIRED: '结果待核对', UNKNOWN: '结果待核对',
  SKIPPED: '已跳过', CANCELLED: '已取消', EXPIRED: '已过期', INTERRUPTED: '已中断',
  COMPLETED: '已完成', PARTIAL: '部分完成', CLOSED: '已关闭', STARTED: '已开始',
  ACTIVE: '已启用', DISABLED: '已停用', DRAFT: '草稿', PUBLISHED: '已发布',
  EXACT: '精确匹配', ALIAS: '别名匹配', FUZZY: '近似匹配', ALL: '全部报表',
  AMBIGUOUS: '存在歧义', NONE: '未匹配', NOT_FOUND: '未找到记录',
  ATTEMPT: '已发起', DELIVERED: '已同步', COMPLETE: '证据完整', SYNCING: '待同步', INCOMPLETE: '证据有缺口'
}
const dictionaries = {
  status, outcome: status,
  operation: {
    RESOLVE: '报表解析', RESOLVE_REPORT: '单报表解析', PREVIEW: '派单预览', PREVIEW_REPORT: '单报表预览',
    DISPATCH_JOB_DIRECT: '手工派单', DISPATCH_JOB_CONFIRM: '确认派单',
    DISPATCH_JOB_RETRY_FAILED: '失败项重试', DISPATCH_JOB_RECONCILE: '派单结果核对',
    DISPATCH_JOB_OP_RETRY_FAILED: '管理员重试', DISPATCH_JOB_OP_RECONCILE: '管理员核对'
  },
  action: {
    CREATED: '创建清单', CLAIMED: '领取执行任务', FINISH: '执行结束', TRANSITION: '更新清单状态',
    TRANSITIONEXECUTION: '更新执行状态', FINISHREVIEW: '完成结果核对', MARKSTALEFORREVIEW: '转入结果核对',
    direct: '手工派单', confirm: '确认派单', reconcile: '核对派单结果', retry: '重试失败项',
    METRICS_READ: '查看业务指标', AUDIT_READ: '查看访问审计', CATALOG_ROLLBACK: '回滚报表目录',
    RESOLVER_EVALUATION: '运行解析评估', WORKBENCH_READ: '查看人工工作台', WORKBENCH_ITEMS: '查看清单条目',
    'WORKBENCH_reconcile': '核对派单结果', 'WORKBENCH_retry-failed': '重试失败项',
    WORKBENCH_close: '关闭任务', WORKBENCH_CLOSE_COMMIT: '完成任务关闭', POLICY_CHANGE: '变更策略',
    ERASURE_REQUEST: '申请删除会话', HTTP_GET: '读取数据', HTTP_POST: '提交请求',
    HTTP_PUT: '更新数据', HTTP_PATCH: '修改数据', HTTP_DELETE: '删除数据',
    publish: '发布', disable: '停用', rollback: '回滚', create: '创建', update: '更新', delete: '删除'
  },
  phase: {
    INTENT: '发送前留证', RESULT: '派单返回', RECONCILE: '结果核对', PLAN: '清单状态',
    MESSAGE: '会话消息', AUDIT: '审计记录', user: '用户消息', assistant: '助手回复',
    tool_call: '工具调用', tool_result: '工具返回', card: '业务卡片',
    preview: '派单预览', plan: '派单清单', result: '派单结果', choice: '报表选择'
  },
  tool: {
    MODEL: '模型分析', TOOL: '事实查询', CONTROL: '流程控制', CONTEXT_COMPACT: '整理调查上下文', REPORT_VALIDATION: '校验调查报告',
    investigation_plan_summary: '读取清单概要', investigation_plan_items: '读取清单条目',
    investigation_rule_snapshots: '读取规则快照', investigation_execution_events: '查询执行证据',
    investigation_dispatch_lookup: '核对业务结果', investigation_evidence_read: '读取调查证据'
  },
  resource: { metrics: '业务指标', audit: '访问审计', plans: '派单清单', resolver: '解析策略', retention: '留存策略', evaluation: '评估样本' },
  actor: { system: '系统' },
  fieldType: { string: '文本', number: '数值', decimal: '金额', boolean: '布尔值', date: '日期', datetime: '日期时间', long: '整数', integer: '整数' }
}

/** 返回代码对应的中文文本；空值统一显示破折号，未知值不推断业务含义。 */
export function displayLabel(value, domain = 'status') {
  if (value === null || value === undefined || value === '') return '—'
  const code = String(value)
  // 时间只整理显示格式，不推断或转换服务器时间的时区；原值由展示组件保留。
  if (domain === 'time') return code.replace('T', ' ').slice(0, 19)
  const dictionary = dictionaries[domain] || {}
  return dictionary[code] || dictionary[code.toUpperCase()] || dictionary[code.toLowerCase()] || code
}

/** 根据实际状态选择标签颜色；“已执行”不等于全部成功，未知状态保持中性色。 */
export function statusTone(value) {
  const code = String(value || '').toUpperCase()
  if (['SUCCESS', 'SUCCEEDED', 'OK', 'COMPLETED', 'ACTIVE', 'PUBLISHED', 'COMPLETE', 'DELIVERED'].includes(code)) return 'success'
  if (['FAILED', 'ERROR'].includes(code)) return 'danger'
  if (['UNKNOWN', 'REVIEW_REQUIRED', 'AMBIGUOUS', 'PARTIAL', 'INCOMPLETE', 'PENDING', 'DRAFT'].includes(code)) return 'warning'
  if (['RUNNING', 'EXECUTING', 'QUEUED', 'STARTED'].includes(code)) return ''
  return 'info'
}

/** 报表名称优先来自当前授权目录；汇总行不伪装为具体报表，未收录标识保留原值。 */
export function reportLabel(value, catalog = []) {
  if (value === '*') return '全部报表'
  return catalog.find(row => row.reportId === value)?.reportName || value || '—'
}

/** 只缩短摘要型版本的可见长度；调用处用 title 保留完整原值，数字版本不截断。 */
export function versionLabel(value) {
  if (value === null || value === undefined || value === '' || value === '-') return '—'
  if (value === '0:baseline') return '初始基线'
  const code = String(value)
  if (/^\d+:0:baseline$/.test(code)) return `目录 ${code.split(':')[0]} · 初始基线`
  return code.length > 22 ? `${code.slice(0, 10)}…${code.slice(-6)}` : code
}
