# 派单 Agent 技术选型与当前设计

| 项目 | 内容 |
| --- | --- |
| 适用版本 | 当前仓库代码，说明更新于 2026-09-29 |
| 功能范围 | 报表识别、预览、排除、确认派单、结果追踪、目录/规则管理及运营治理 |
| 当前边界 | 演示身份、演示派单网关；真实认证和外部业务派单接入为下一阶段 |
| 启动与验证 | [README](../README.md)、[Docker 部署指南](Docker部署指南.md) |
| 下一阶段 | [Demo 到生产级待办](派单Agent_Demo到生产级待办.md) |

本文件说明当前实现与业务口径。按日期命名的评审保留对应历史版本的结论和测试结果，不作为当前接口、配置或验收通过的依据。

## 1. 目标与业务原则

用户可以用自然语言查询可派单记录、调整选择、生成清单并确认执行。默认 active 模式统一调用模型输出 V2 业务指令；模型不调用业务工具。公司、报表与排除项由服务端校验、归并和执行，结果文字来自实际业务结果。

当前默认 active 使用 V2，legacy 仅为显式回滚。历史 V1 语法/HTTP/模型成绩不适用于 V2。V2 包含 51 轮合成业务回放；真实模型验收目前因缺少 LLM_API_KEY 未执行，部署前必须针对实际端点运行。详见 [语义 V2 实施说明](语义V2实施与验收.md)。

1. 报表名称和别名从目录读取，新增标准报表通过配置与发布接入。
2. 查询同时受租户、报表权限和公司范围限制；身份不能从模型生成的参数取值。
3. 预览、待确认清单和执行结果保存在 MySQL，不能依赖模型回复或 Redis 中的工作记忆判断是否已派单。
4. 排除项必须属于对应预览；派单前再次检查归属、当前权限、目录/规则版本、状态及记录事实。
5. 语义入口始终生成待确认卡片，按钮通过 REST 执行，即使 legacy 的 `require-confirm` 被关闭也不会自动确认。明确失败允许重试，结果不明先核对，不将超时当作成功。
6. 历史卡片有效性以服务端当前状态为准，管理员操作也受租户及报表/公司权限范围约束。

## 2. 当前选型

| 选型 | 仓库版本/方式 | 用途与边界 |
| --- | --- | --- |
| Spring Boot | 3.5.16，Java 17+ | 承载现有报表和 Agent 业务；CI 使用 JDK 21 |
| Spring AI | 1.1.8，OpenAI 兼容 ChatClient | active 自由文本统一调用无工具的 V2 意图解析，原生 JSON Schema 可配置；legacy 保留工具和工作记忆以便回滚 |
| MyBatis-Plus 与 JDBC | 3.5.17；保留 JdbcTemplate/NamedParameterJdbcTemplate | 实体读写、配置式报表查询、状态事务及运营 SQL |
| Aviator | 5.4.4 | 管理员定义表达式，服务器编译校验和确定性求值 |
| MySQL | 8 | 业务状态、去重请求、并发租约、审计和版本历史的持久来源 |
| Redis | Docker 使用 7 | 短期工作记忆、缓存刷新广播、分钟请求频率限制 |
| Vue / Element UI / Vite | Vue 2.7、Element UI 2.15、Vite | 报表、聊天、历史会话和管理页面；组件按需引入、页面按路由加载 |
| SSE | POST + fetch 读取事件流 | 传递文本与卡片，事件有请求号/序号，断线通过持久状态恢复 |
| Flyway | `backend/src/main/resources/db/migration` 及 Java 迁移 | 当前 V1–V18，维护表结构演进 |

当前模型默认值不是供应商可用性保证：本地 `application.yml` 为 `deepseek-v4.1-flash`，Compose 为 `qwen3.7-plus`，默认端点为百炼兼容地址；请求扩展体为 `thinking: {type: disabled}`。active 意图解析温度为 `0`、最多输出 2400 tokens，legacy 温度为 `0.1`。切供应商需核对端点、模型、原生 Schema 和扩展参数，详见 README。

## 3. 页面与流程

| 页面 | 主要行为 |
| --- | --- |
| 销售/应收/费用报表 | 查询当前用户可见记录，发起手工派单；目录可扩展不等于自动新增一个独立报表菜单页 |
| 派单助手 | 输入自然语言，处理歧义选择，显示预览、待确认与结果卡片 |
| 历史会话 | 查看消息、恢复卡片状态、续聊、改名、软删除 |
| 派单规则 | 查看事实字段，新建草稿、表达式校验、试算、发布、停用、回滚 |
| 报表目录 | 维护定义、查询配置、别名、生效时间、状态和版本 |
| 运营治理 | 解析评估、指标、策略、审计、人工异常处理及删除请求 |

一次派单的步骤：

1. 用户表达查询范围。目录提供候选实体，模型输出 action、有序 scopeChanges 与本轮 restrictions。后端检查协议、原文证据、实体覆盖和权限；无法确定对象或存在冲突时澄清，不执行部分指令。
2. 按选择的报表和公司范围扫描待派单记录，通过生效规则求值。生成预览摘要、规则/目录/权限版本及明细；较大查询可使用异步任务。
3. 用户调整勾选或提出排除要求，基于来源预览创建待确认清单。相同幂等键重放返回已创建清单，不能因此扩大选择范围。
4. 用户点击确认。服务端认领清单、复核版本和事实，再按逐条请求号执行。成功、明确失败、未知结果分别保存。
5. 页面显示结果，刷新时重新读取当前状态。失败项重试、未知项核对、取消和运营关闭均走对应业务规则。

SSE 事件包括 `conversation`、`text`、`preview_job`、`choice`、`preview`、`plan`、`result`、`selection`、`error`、`done`。active 在本轮完成查询并流式发送进度与卡片，处理上限默认 180 秒，扫描不持有会话事务；大预览仍分批持久化。既有 REST/报表选择卡片的异步任务保留 120 秒上限。序号识别重复或缺失事件，断线通过消息和业务状态恢复，不提供原始文本流续传。

语义状态分别记录用户请求范围（desired）和最近成功查询范围（effective）。例如 `user1` 查 A 成功、查 B 被拒绝后，desired=B、effective=A；接着说“现在我只想派销售报表的”只改变报表，仍拒绝 B 请求，不能回退查询 A；明确说“A公司销售报表的”才查询 A。助手历史文字不参与权限或状态推断。

| 条件变更 | 业务含义 |
| --- | --- |
| 省略目标（内部 KEEP） | 本轮未修改该目标，继承最近请求；不会清除未解决的拒绝/歧义；V2 JSON 不输出 KEEP 操作 |
| REPLACE | 使用本轮明确对象替换；公司当前支持单家公司代码及其“公司”后缀 |
| ADD / REMOVE | 在既有报表集合追加/移除；在排除记录集合中表示不派/恢复 |
| CLEAR | 用户明确清除限制；公司恢复全部可见公司，报表恢复全部可派单报表，记录恢复全部勾选 |

多家公司、模糊报表、不能唯一定位的记录需要澄清。记录先按单据号精确匹配，再按摘要匹配，必须唯一；之后始终保存“报表 ID＋记录 ID”。只调整排除记录时复用同一有效预览，`selection` 事件同步勾选；已保存的语义选择可在刷新后通过 `GET /api/agent/conversations/{id}/selection` 恢复。仅在浏览器修改、尚未随消息提交的勾选仍是本地状态。

自由文本不再使用领域词法规则决定动作。V2 中每个修改绑定 COMPANY/REPORTS/RECORDS；报表操作按顺序归并，全部实体解析成功才提交请求范围；记录操作应用于最终预览，所有选择修改成功才提交排除集合。禁止派单表达通过本轮 PREPARE_DISPATCH restriction 表达，不修改报表范围。模型不可用时停止该轮业务动作，不能退回关键词猜测。mock 仅使用固定样本或澄清。

## 4. 目录、查询与规则口径

`report_id` 是稳定字符串标识；目录编码在租户内唯一。公开名称、权限码、别名、查询配置、生效范围及发布状态均由目录维护，旧三张报表代码保留兼容映射。

| 能力 | 当前规则 |
| --- | --- |
| 目录可见性 | 租户一致、已发布、处于生效时间、查询配置可用、拥有权限码，并满足目录灰度策略 |
| 标准查询 `STANDARD` | JSON 配置表和字段；标识符受格式校验并引用，数据值参数绑定；不允许用户或模型提交任意 SQL |
| 自定义查询 `ADAPTER` | 引用已注册适配器，复杂业务需编写实现并明确权限、分页与事实约定 |
| 租户隔离 | `tenantColumn` 必填，读取及回写带租户；待派单扫描另带公司范围及待派单状态 |
| 唯一记录 | 标准接入要求 ID 非空，且具有 ID 或租户+ID 唯一约束；其他复合主键使用专用适配器 |
| 发布检查 | 探测真实表列和唯一约束，检查权限码；新事实字段不能破坏既有已发布规则 |
| 规则范围 | 按租户、报表和公司选择生效规则；发布/回滚维护版本并在提交后刷新缓存 |
| SQL 下推 | 只下推可证明与 Aviator 语义一致的简单谓词；不安全或无法证明无损的映射回到 Java 求值 |
| 解析方式 | 名称/编码/ID、别名、编辑距离/二元组/包含匹配；当前未接搜索引擎或向量检索 |

模型不直接生成 SQL，也不决定最终可访问的报表或公司。当前身份源仍是 `X-User-Id` 模拟，四个演示账号都属于 `T001`；生产登录态、真实租户和组织权限对接尚待实施。

## 5. 持久化、状态与恢复

| 数据 | 当前存放与用途 |
| --- | --- |
| `report_definition`、`report_alias`、`report_code_mapping` | 目录、别名、旧代码映射 |
| `dispatch_rule`、`dispatch_rule_history` | 规则版本与变更历史 |
| `dispatch_preview`、`dispatch_preview_item`、`dispatch_preview_job` | 预览摘要、冻结明细、异步任务及进度 |
| `dispatch_plan`、`dispatch_plan_item` | 清单状态、逐条结果、尝试次数及外部请求号 |
| `dispatch_gateway_request` | 演示网关请求流水及结果查询 |
| `agent_conversation`、`agent_message`、`trace_event` | 会话、消息、持久追溯事件和投递状态 |
| `semantic_dialogue` | 请求/生效范围、未解决条件、预览/清单绑定、排除记录、版本与处理租约；V1 pendingIntent 读取时转为 V2；新 parserSource 为 MODEL/MOCK，DOMAIN 仅兼容历史 |
| `semantic_turn` | 每轮输入、V2 指令、状态结果、原因、模型及耗时；与会话删除流程一起清理，旧审计记录不重写 |
| `dispatch_audit`、`operations_audit`、`business_metric` | 派单审计、访问/管理审计及业务指标 |
| `catalog_revision`、`operations_policy`、`operations_policy_revision` | 目录/策略历史、灰度与回滚 |
| 数据库配额表 | 按请求持有和续期并发租约，Redis 丢键不能释放已持有容量 |
| Redis | 有 TTL 的模型上下文、缓存刷新广播、分钟频率限制 |

清单可处于 `PENDING`、`EXECUTING`、`EXECUTED`、`REVIEW_REQUIRED`、`EXPIRED`、`CANCELLED` 等状态；逐条结果另行记录。新预览/清单、超时、权限或版本变化可能使旧卡片失效，不能从历史消息内容反推可执行性。

同一语义会话同一时刻只允许一个处理租约，其他请求返回冲突。租约到期、删除或被其他实例接管后，旧请求不能保存状态、激活预览或建单；只有短暂的状态写入/业务提交持有事务。旧会话从有归属且有效的业务快照初始化；后续只采纳更新的显式 UI/API 选择，不把历史成功预览当作被拒绝请求的新结果。

标准演示回写在事务内锁定源记录并复核条件，持久化请求号用于重复提交与结果核对。真实外部网关并未实现；接入时必须建立同等的幂等与查询契约，不能假设外部调用具有本地数据库事务语义。

## 6. 运营治理与数据口径

| 项目 | 当前行为 |
| --- | --- |
| 解析回归 | 按租户维护样本，对目录/解析策略/样本集/模型配置指纹变化自动评估；评估对象是目录解析，不能代替真实模型端到端验收 |
| 多轮语义回放 | SemanticV2Test 验证统一模型路由、协议边界、原子归并和 V1 兼容；SemanticIntegrationTest 验证真实 MySQL 状态及业务服务。replay-corpus.json + business-corpus-v2.json 共 51 轮，通过同一业务 oracle 验证 mock 或真实模型；mock 不计模型准确率 |
| 指标 | 按租户、操作、报表、版本、结果分组统计次数、平均耗时和 P95；派单统计依据清单条目当前状态，重试不重复累计成功 |
| 灰度 | 目录与解析策略按用户稳定分桶，规则复用公司范围；策略修改校验期望版本，回滚生成新版本 |
| 人工处理 | 失败重试、结果核对、取消、关闭已知失败；在途或未知条目不能直接关闭 |
| 脱敏 | 文本、响应及 SSE 分片处理已有敏感信息规则；需结合真实业务补充样本，不代表所有敏感格式均覆盖 |
| 留存 | 默认会话/结果/审计 365 天、指标 90 天；租户策略可覆盖，按批清理 |
| 删除 | 会话软删除与内容清理分开；删除请求持久化，在途/未知/未处理失败等情况保留，完成后阻止迟到写入 |
| 永久去重 | 结果显示明细清除时保留请求 ID 和必要终态，避免历史请求再次执行 |

## 7. 管理接口

主要业务接口见 [README](../README.md#主要接口)。下表补充管理动作，均以当前控制器为准。

| 范围 | 实际入口 |
| --- | --- |
| 目录查询 | `GET /api/report-catalog`、`GET /api/report-catalog/search?q=...`、`GET /api/report-catalog/{reportId}` |
| 目录维护 | `POST /api/report-catalog`、`PUT /api/report-catalog/{reportId}`、`POST .../{reportId}/publish`、`POST .../{reportId}/disable` |
| 别名 | `POST /api/report-catalog/{reportId}/aliases`、`DELETE .../{reportId}/aliases/{aliasId}` |
| 规则查询 | `GET /api/rules`、`GET /api/rules/history`、`GET /api/rules/fields?reportId=...` |
| 规则修改 | `POST /api/rules/validate`、`POST /api/rules/dry-run`、`POST /api/rules/drafts`、`DELETE /api/rules/drafts/{id}`、`POST /api/rules/{id}/{publish,disable,rollback}` |
| 指标与审计 | `GET /api/operations/metrics`、`GET /api/operations/audit`、`GET /api/operations/evaluation` |
| 策略 | `GET/PUT /api/operations/policies/{key}`、`GET .../{key}/history`、`POST .../{key}/rollback` |
| 目录历史 | `GET /api/operations/catalog/{report}/history`、`POST .../{report}/rollback` |
| 人工工作台 | `GET /api/operations/workbench`、`GET .../{plan}/items`、`POST .../{plan}/{retry-failed,reconcile,cancel,close}` |
| 删除流程 | `POST /api/operations/erasures/{conversation}`、`GET /api/operations/erasures` |

业务权限按服务端校验，管理动作需要管理员；删除请求还检查会话归属。普通 REST 调用需检查 `Result.code`，不能仅依赖 HTTP 200；readiness 单独返回 HTTP 200/503。

## 8. 验证重点

| 场景 | 应验证结果 |
| --- | --- |
| 无报表权限/跨租户/跨用户访问 | 不返回越权数据，不泄露任务存在性 |
| 别名冲突或模糊结果接近 | active 要求明确报表名称，保持未解决状态；legacy/目录接口保留选择卡片 |
| A 成功→B 拒绝→只改报表→明确 A | 中间两轮不能查询 A；最后一轮才切回 A，实际查询和状态一致 |
| 排除同名单据、刷新、生成清单 | 歧义要求勾选；已确认排除项按复合记录键保存，实际清单不能包含排除项 |
| 同会话并发、超时接管、删除 | 旧请求失去写入与业务提交权，不能复活状态 |
| 0 条预览 | 显示明确空结果，不沿用旧卡片 |
| 规则/目录/权限变化 | 旧预览和清单不能继续执行 |
| 同幂等键重复建单、重复确认 | 不创建重复业务派单 |
| 执行中断或结果写入失败 | 按请求号保存/核对结果，未知状态不能盲目重试 |
| SSE 中断、页面刷新 | 从持久状态恢复任务与卡片，不重复追加结果 |
| 删除与在途任务并发 | 受保留条件约束，清理后不被迟到消息重新写回 |
| 默认重启已有库 | 保留数据和管理配置；显式重置仅用于可丢弃数据库 |
| MySQL/Redis 不可用 | readiness 返回 503，部署不能误报全部就绪 |

测试命令及 `DEMO_IT`、`TRACE_IT`、`P2_IT`、`P2_UI` 开关见 README。历史测试数和页面验收截图只说明对应版本，不作为本次变更的验证结论。

## 附录 A：只读业务核对 SQL

在正确的数据库中使用实际租户和任务号。以下查询不修改数据；结果可能包含业务信息，按授权范围使用。

```sql
SET @tenant_id = 'T001';
SET @plan_id = '替换为清单ID';

SELECT report_id, report_code, report_name, status, catalog_version
FROM report_definition
WHERE tenant_id = @tenant_id
ORDER BY report_id;

SELECT p.id, p.status, p.status_reason, i.report_id, i.record_id,
       i.status AS item_status, i.external_request_id, i.error_code
FROM dispatch_plan p
JOIN dispatch_plan_item i ON i.plan_id = p.id
WHERE p.tenant_id = @tenant_id AND p.id = @plan_id
ORDER BY i.id;

SELECT policy_key, version, payload
FROM operations_policy
WHERE tenant_id = @tenant_id;

SET @conversation_id = '替换为会话ID';
SELECT version, JSON_EXTRACT(state_json, '$.desired') AS desired,
       JSON_EXTRACT(state_json, '$.effective') AS effective,
       JSON_EXTRACT(state_json, '$.phase') AS phase
FROM semantic_dialogue
WHERE tenant_id = @tenant_id AND conversation_id = @conversation_id;

SELECT request_id, state_version, outcome, reason, model, latency_ms, intent_json
FROM semantic_turn
WHERE tenant_id = @tenant_id AND conversation_id = @conversation_id
ORDER BY created_at;
```

建表与升级以 Flyway 迁移文件为准，不在文档复制一套易过期的建表脚本。

## 附录 B：当前配置

| 配置项 | 默认值/行为 |
| --- | --- |
| `demo.reset-on-startup` / `DEMO_RESET_ON_STARTUP` | `false`；新空库首次初始化，已有库默认保留，`true` 才反复重置 |
| `agent.llm.mock` | `false`；`mock` profile 将其打开 |
| `agent.semantic.mode` / `SEMANTIC_MODE` | 默认 active，V2 统一模型语义入口；legacy 为显式回滚，旧工具兜底不参与 active 处理 |
| `agent.semantic.native-schema` / `SEMANTIC_NATIVE_SCHEMA` | 原生 Schema 开关；无论开启与否，服务端都会严格校验形状、字段、原文证据及权限 |
| `agent.semantic.model` / `SEMANTIC_MODEL` | 默认空，沿用 `LLM_MODEL`；可独立替换语义解析模型 |
| `agent.semantic.thinking-enabled` / `SEMANTIC_THINKING_ENABLED` | 默认 `false`；开启需支持 `thinking.type=enabled`，输出预算从 2400 调整为 4096 tokens |
| `agent.semantic.turn-timeout-seconds` | `180`；实际配置限制在 10–240 秒；租约过期后拒绝继续提交 |
| `agent.dispatch.require-confirm` | `true`；仅 legacy 工具路径读取该开关，active 始终待按钮确认 |
| `agent.preview.ttl-minutes` | `30` |
| `agent.preview.max-items` | `5000`，超过时预览改走分批持久化路径，建单仍受该上限约束；大预览需缩小报表或公司范围后再建单 |
| `agent.plan.ttl-minutes` | `10` |
| `agent.resolver.fuzzy-threshold` / `ambiguity-margin` | `0.6` / `0.15`，启用租户解析策略灰度时按策略生效 |
| `agent.memory.ttl-minutes` / `window-size` | `30` / `20`，过期可从对话日志恢复 |
| `agent.conversation.card-payload-max-rows` / `title-max-length` | `2000` / `30`；会话卡片实际最多持久化 `min(配置行数, 50)` 行，其余走分页 |
| 留存清理天数 | 实际使用运营治理的 `retention` 策略；不要仅修改旧 `agent.conversation.retention-days` 字段作为清理配置 |
| `agent.retention-sweep-ms` / `agent.evaluation-sweep-ms` | 默认 `60000` 毫秒 |
| `spring.jdbc.template.query-timeout` | `60s`，单条 JDBC 查询上限 |
| `spring.mvc.async.request-timeout` | `300000` 毫秒 |
| `CORS_ALLOWED_ORIGINS` | 默认空；开发代理与 Nginx 反代同源，按实际需求显式开放 |

数据库、Redis、模型和部署环境变量见 README 与 Docker 指南。默认确认关闭、配额调整、真实组织权限或新业务适配器的行为变化应单独验收。
