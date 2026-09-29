# 当前版本的架构与完整任务流程

依据 `codex/mcp-business-service` 分支提交 `52bbf6d` 的实际代码绘制，日期 2026-09-29。

## 打开图表

- [可编辑 draw.io 文档](mcp-system.drawio)：包含“01 系统架构”和“02 完整请求流程”两个页面。
- [系统架构 PNG](mcp-architecture.png) / [系统架构 SVG](mcp-architecture.svg)。
- [完整请求流程 PNG](mcp-task-flow.png) / [完整请求流程 SVG](mcp-task-flow.svg)。
- [两页 PDF](mcp-system.pdf)：可直接分享或打印，流程页为长图。

图形均为原生 draw.io 节点与连接线。使用 draw.io MCP 创建和整理，架构图采用 libavoid 连线路由，流程图采用 ELK 分层布局；PNG、SVG 和 PDF 由本机 draw.io Desktop 31.5.3 导出，并嵌入可编辑图表数据。

## 阅读要点

一次完整派单任务包含多个交互：自然语言查询、调整勾选或范围、生成待确认清单，以及用户点击确认后的 REST 请求。图中的整个过程不是单个 HTTP 请求；模型解析请求和实际执行派单具有独立的边界。

架构图区分前端、Agent、模型、业务服务、共享 MySQL 控制表与业务表，以及 Redis 辅助设施。当前两个服务进程共用 `report_mcp` schema；尚未接入外部 ERP。账号、确认清单和版本由业务服务再次校验。

流程图涵盖登录、对话状态、模型解析与一次格式修复、范围/权限校验、MCP 查询、预览与勾选、清单生成、人工确认、执行版本、逐条幂等提交、结果保存、未知结果核对与显式失败重试。虚线表示继续处理或重试；最末尾的失败重试框通过“转步骤 17”引用执行入口，避免长反馈线遮挡主流程。帮助、取消及已有结果读取作为辅助分支表示。

业务事务中，同号同负载已成功的请求返回原结果；同号不同负载被拒绝。源报表状态和业务请求结果同事务提交。超时或响应不明不能当作明确失败重发，必须先按原请求号核对。

## 代码依据

- [认证入口](../../backend/src/main/java/com/example/report/security/SessionFilter.java)、[账号与会话](../../backend/src/main/java/com/example/report/security/IdentityStore.java)。
- [语义编排](../../backend/src/main/java/com/example/report/semantic/SemanticConversationService.java)、[模型解析](../../backend/src/main/java/com/example/report/semantic/ModelIntentParser.java)。
- [确认、执行和核对](../../backend/src/main/java/com/example/report/dispatch/DispatchService.java)。
- [MCP 客户端](../../backend/src/main/java/com/example/report/mcp/BusinessMcpClient.java)、[MCP 工具注册](../../business-service/src/main/java/com/example/business/McpConfiguration.java)。
- [业务查询](../../business-service/src/main/java/com/example/business/BusinessQueries.java)、[业务派单事务](../../business-service/src/main/java/com/example/business/BusinessDispatch.java)。

更详细的服务运行方式和契约见 [MCP 业务服务实施与验收](../MCP业务服务实施与验收.md)。
