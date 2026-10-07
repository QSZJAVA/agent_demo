# 异常调查 Agent：当前实现与操作

核对日期：2026-10-07。当前实现依据 [InvestigationService](../backend/src/main/java/com/example/report/investigation/InvestigationService.java)、[InvestigationAgent](../backend/src/main/java/com/example/report/investigation/InvestigationAgent.java) 和 [InvestigationProperties](../backend/src/main/java/com/example/report/investigation/InvestigationProperties.java)。[2026-10-05 详细设计](异常调查Agent与评估体系详细设计_2026-10-05.md)保留当时目标；其中工具数量、报告草案和验收门槛不作为当前接口定义。

## 操作与边界

运行链路为 `real,mcp`、`agent.semantic.mode=active`、真实模型接口和认证 HTTP MCP。调查是独立的模型工具循环，语义开关不控制其内部轮次。清单或结果卡片中的“分析异常”打开调查抽屉；输入问题后显式开始分析。创建人仅能读取本人调查，同租户管理员创建时也必须具备完整清单业务范围权限。

清单必须为 `EXECUTED` 或 `REVIEW_REQUIRED`，候选条目为 `FAILED`、`SKIPPED`、`UNKNOWN`、`PENDING`。每次最多 10 条；未显式选择时由服务端检查全部异常，超过上限要求选择子集，不静默截取。无异常返回空结果，不调用模型。`PENDING` 不证明请求从未发送。

页面展示中文任务状态、已完成步骤、活动步骤、结构化报告及证据。刷新、关闭抽屉或断线只恢复已有任务；不会自动重新创建调查。取消会撤销执行权并尝试停止在途调用。来源变化、证据不足及部分完成明确标注，调查不会修改派单清单、来源记录、规则或业务派单结果。

## 模型、工具与报告

当前工具为六个：

| 工具 | 作用 |
| --- | --- |
| `investigation_plan_summary` | 清单及当前选择的概要 |
| `investigation_plan_items` | 调查条目分页 |
| `investigation_rule_snapshots` | 执行时规则快照 |
| `investigation_execution_events` | 执行事件分页 |
| `investigation_dispatch_lookup` | 通过原请求号读取真实业务结果，每批最多 5 条 |
| `investigation_evidence_read` | 按本轮 E 编号回读已取得的有界证据，不生成新证据编号 |

模型只能使用运行内 `I` 条目引用和已返回的 `E` 证据引用，不能提供身份、任意 URL 或 SQL。应用逐次检查白名单、权限、预算、来源状态和执行权。工具分页每页最多 20 条，独立于页面 REST 分页的 50 条上限；工具调用 ID 必须与返回消息配对。相同查询缓存与无进展限制不会放宽权限。

报告阶段不注册工具，使用已取得证据的事实投影，最多一次报告修正。模型输出原因、核实程度、证据和下一步枚举；`summary`、`explanation` 和待查 `message` 由程序根据校验后的结构生成，不把模型自由文本作为已核实结论。缺失、错条目或跨运行引用不接受。远端成功而本地未明必须同时有本地状态和本次远端成功证据；`UNKNOWN`、`NOT_FOUND` 均不授权重发。

默认跟随 `LLM_MODEL`，可由 `INVESTIGATION_MODEL` 独立配置；thinking 默认关闭。调查报告的 `INVESTIGATION_NATIVE_SCHEMA` 默认 false，与统一业务规划固定请求原生 Schema、V1 派单解析默认原生 Schema 是不同配置。工具能力和最终结构输出能力需分别验证，不能仅根据模型名称推断。

## 默认运行预算

| 配置（`agent.investigation.*`） | 当前默认值 |
| --- | --- |
| `max-items` | 10 条 |
| `max-model-calls` / `max-collection-calls` | 总计 8 次／收集最多 6 次；报告及一次修正计入总量 |
| `max-tool-calls` / `max-mcp-calls` | 16 次／20 次 |
| `max-output-tokens` / `max-report-output-tokens` | 收集 2400／报告 4096 |
| `run-timeout-seconds` / `queue-timeout-seconds` | 运行 180 秒／排队 120 秒 |
| `model-timeout-seconds` / `mcp-timeout-seconds` | 单次最多 40 秒／8 秒，同时受任务剩余时间约束 |
| `max-input-utf8-bytes` / `max-tool-result-utf8-bytes` | 模型逻辑输入及完整 HTTP 请求各检查 98304 字节上限／单次工具结果 8192 字节 |
| `context-target-utf8-bytes` | 24576 字节，触发确定性上下文整理；不是强制截断 |
| `worker-count` | 2 个调查工作线程 |
| `lease-seconds` / `heartbeat-seconds` | 租约 45 秒／续租间隔 10 秒 |
| `max-active-per-user` / `max-active-per-tenant` | 3 个／12 个 |
| `retention-days` | 7 天 |

实际 HTTP 请求体积另受传输层限制，包含工具和 Schema；不能以逻辑输入大小替代完整请求检查。未知 token 用量和缺少价格时不虚构费用。上下文笔记、事实投影及按需回读详见[上下文工程](上下文工程设计与验收_2026-10-06.md)。

## REST 接口与恢复

浏览器使用当前 Bearer 会话，身份由服务端确定。入口见 [InvestigationController](../backend/src/main/java/com/example/report/web/InvestigationController.java)。

| 接口 | 请求或行为 |
| --- | --- |
| `POST /api/investigations` | `planId`、`question`、可选 `itemIds`；要求稳定 `Idempotency-Key` |
| `GET /api/investigations?planId=…&cursor=…&size=…` | 本人该清单的已有调查列表，用于恢复 |
| `GET /api/investigations/candidates?planId=…&afterId=…&size=…` | 当前清单可调查条目 |
| `GET /api/investigations/{id}` | 任务、活动步骤、报告及来源变化状态 |
| `GET /api/investigations/{id}/steps?afterSeq=…&size=…` | 已完成步骤的增量读取 |
| `GET /api/investigations/{id}/evidence/{ref}` | 当前运行的已取得证据 |
| `POST /api/investigations/{id}/cancel` | 取消原任务，不新建任务 |

REST 列表默认每页 20 条、最多 50 条。提交新任务返回 202，幂等重放或无异常空结果返回 200；业务失败使用对应 HTTP 状态及 `{code,message,data}`。相同幂等键不同负载拒绝，丢失响应用原键恢复。明确传入空 `itemIds` 不代表全部；问题上限 1000 字符，幂等键长度 16～128 字符。

任务状态包括排队、运行、完成、部分完成、失败、取消、中断。进程退出或租约失效后不自动重放模型请求；需要重新分析时由用户显式发起。GET 恢复仍检查当前权限，历史上可见不等于当前可读。

## 验证范围

当前单元／协议、真实模型工具夹具、真实 HTTP MCP、浏览器及真实联合调查分别报告。`tools/test-investigation-live.ps1` 和 `-Context -Repeats 3` 为真实模型评估入口；后者使用合成长事件及明确用户流程指令，不代表无提示自主工具选择。创建独立测试数据库的整体回归须先取得针对该操作的授权。

历史事实见[调查真实模型复验](review/异常调查Agent真实模型复验_2026-10-06.md)、[终评](review/异常调查Agent整体复评问题修复与终评_2026-10-06.md)、[上下文专项验收](review/S1修复与上下文真实模型验收_2026-10-06.md)。本次文档核对没有重新执行上述模型／数据库验收。正式外部认证、ERP、真实工单系统、容量和灾备继续待实际接入与验证。
