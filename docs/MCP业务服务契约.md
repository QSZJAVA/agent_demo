# 最终演示版 MCP 业务服务契约

当前实现以 `real,mcp`、`agent.semantic.mode=active`、V1 真实模型解析和认证 HTTP MCP 为基线。默认数据库及启动命令统一见 [根 README](../README.md) 和 [基线清单](../demo-baseline.json)。旧实施与验收报告已归档，日期与结果不改写。

Agent 负责会话、目录、规则、字段预览、选择、清单和任务编排；业务服务负责受权限约束的来源查询与派单事务。两者当前共用专用 MySQL schema 中的控制数据，模型只输出语义或只读调查工具提议，不直接提交派单。

## 身份与协议

浏览器通过 `POST /api/auth/login` 获得 Bearer 会话，后续业务请求须认证。管理员用 `PUT /api/auth/users` 维护用户及公司、报表权限；Demo 账号生命周期问题仍属暂缓范围，不能当成已接入外部认证系统。

MCP 使用 `/mcp` 上的 Streamable HTTP，支持 initialize、tools/list、tools/call。独立服务 Bearer 凭据只在两个后端之间使用；工具参数中的操作者由可信 Agent 填入，业务服务重读账号及租户、公司和报表权限。跨主机连接默认要求 HTTPS，Compose 专网的例外见部署配置。

| 工具 | 当前职责 |
| --- | --- |
| report_catalog | 授权目录、业务字段及类型；不提供任意 SQL |
| report_page | 报表分页，最多 200 条，记录 ID 为字符串 |
| report_records | 有界候选事实、ID 读取与复核，每批最多 500 条 |
| business_query | 受控查询报表全部状态、派单条目或演示工单；包含完整统计和当前页，不执行写入 |
| report_probe | 可信管理流程校验来源配置 |
| dispatch_submit | 已确认条目的幂等提交，携带冻结执行版本 |
| dispatch_lookup | 按原请求号核对 SUCCESS / FAILED / NOT_FOUND / UNKNOWN |

工具结果使用 `{data: ...}`，错误带 isError 及 code/message。候选记录包含 `counterparty` 客户实体以及 `fields` 标量快照数组，每项为 name/type/value，空值显式为 null。业务服务核对其与确认清单一致，不能由调用方篡改；实际完整 Schema 以 tools/list 为准。

`business_query` 接受 `query`：domain、view、reportIds、companyCode、conditions、sortField、descending、page、size、groupBy。domain 为 REPORT / DISPATCH / WORK_ORDER；view 为 LIST / DETAIL / SUMMARY。字段和操作依数据域校验，空 reportIds 表示当前全部可见报表；租户及操作者仍由服务器注入。返回 query、observedAt、source、columns、rows、total、summary。summary 与完整筛选范围一致，币种未声明的金额单独计数、不参与合计。默认扫描预算为 100000 条 / 120 秒，超限或读取不完整时失败，不返回部分汇总。工单提供者及边界见[通用业务助手](通用业务助手.md)。

## 会话、预览、清单和执行

`POST /api/agent/chat` 返回 SSE。历史、卡片状态和选择由 `/api/agent/conversations/...` 读取；断线恢复重读持久状态，不保证原文字流续传。预览和清单接口位于 `/api/dispatch/previews`、`/api/dispatch/plans`。

手动勾选使用 `PUT /api/agent/conversations/{id}/selection` 保存当前预览的完整排除集合，并携带修改前集合进行并发比较。服务端验证预览、记录键、权限与会话租约；冲突要求重读，保存选择不构成派单确认。

确认、明确失败重试及核对由 `/api/dispatch/jobs` 接收稳定 Idempotency-Key，持久任务绑定用户、清单和执行版本。执行前检查归属、权限、TTL、目录/规则版本、确认状态；业务事务再锁定来源、请求和清单并核对负载。相同请求号及相同负载重放成功结果，不同负载拒绝；未知结果先核对，不能自动更换请求号重发。

来源目前是本仓库维护的 Demo 表；外部 ERP 接入、独立控制库和正式认证仍待完成。实现细节见 [字段快照设计](语义V1配置字段筛选.md)、[派单接入契约](MCP派单接入契约.md) 与 [当前验收](review/V1重新编号与验收_2026-10-06.md)。

实际来源写入还必须在行锁内核对已确认的金额、日期、公司、客户身份及目录声明的全部标量字段，任一变化拒绝执行并要求重新查询确认。正常规则仍然命中不能覆盖字段变化保护；明确失败重试继续沿用相同请求号和已确认快照。后台派单、调查认领与过期恢复、预览孤儿回收只处理本实例配置租户，不能处理共享库中的其他租户任务；这不等于完成多租户 SaaS 的公平调度与生产验收。
