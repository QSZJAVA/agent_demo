# 通用业务助手 · V1 演示版

当前助手支持报表数据查询、派单记录查询、工单进度与总结，并保留待确认派单流程。业务查询通过真实模型和 HTTP MCP 执行；工单使用明确标注的固定演示流程与审批人，真实工单系统尚未接入。用法及边界见[通用业务助手](docs/通用业务助手.md)。

当前基线为 **V1 通用业务助手**，应用包版本 **1.0.0**、派单语义协议版本 **1**，统一定义在 [demo-baseline.json](demo-baseline.json)。派单能力沿用此前重新编号的字段筛选和状态保护协议，普通查询使用独立的严格业务查询协议。运行链路为 `real,mcp`、`agent.semantic.mode=active`、真实模型接口和带认证的 HTTP MCP 业务服务。模型名称仍为 `deepseek-v4.1-flash`，请求原生 JSON Schema，thinking 关闭。

## 启动与账号

需要 JDK 17+、PowerShell 7.2+、Node.js、MySQL 8 和 Redis。保持现有依赖版本，不升级前端框架或组件。

1. 参考 [本机配置模板](tools/env.local.example.cmd)，准备已忽略的 `tools/env.local.cmd`。真实模型也可放在已忽略且限制访问的 `.runtime/llm-credentials.json`，字段为 `apiKey`、`baseUrl`、`model`；密钥不写入文档或版本库。
2. 在仓库根目录启动，并在首次准备环境时初始化演示账号与补充数据：

```powershell
./tools/start-app.ps1
./tools/prepare-demo.ps1
```

Windows 也可双击 `tools/start-app.cmd`。启动脚本会先构建两个后端，再等待业务服务数据库初始化与健康检查，随后启动 Agent 和前端。

| 项目 | 最终默认值 |
| --- | --- |
| 页面 | [http://127.0.0.1:5173](http://127.0.0.1:5173) |
| Agent / HTTP MCP | `127.0.0.1:8080` / `127.0.0.1:8090/mcp` |
| 专用 Demo 库 | `report_demo` |
| 主要演示账号 | `semantic_fields_a`，A 公司销售、应收、费用权限 |
| 密码查看位置 | `.runtime/demo-accounts.md`；主要账号也保存在 `.runtime/semantic-fields-account.json` |
| 初始管理员 | `admin`；初始密码在 `.runtime/mcp-credentials.json` 的 `adminPassword` |

`prepare-demo.ps1` 仅向已核实的最终专用库幂等补齐两张演示发票，不清库、不复位派单状态；随后创建或更新五个演示账号。已有本机主要账号密码继续复用，账号权限恢复为脚本定义并撤销其旧登录会话。普通重启无需再次准备账号。若演示记录已实际派单，不会自动把它们改回待派单。

停止与重启：

```powershell
./tools/stop-app.ps1
./tools/start-app.ps1 -SkipBuild
```

需要重新编译时省略 `-SkipBuild`。较低层入口 `tools/start-mcp.ps1 -Build -Frontend` 使用相同数据库和真实模型配置；可显式指定端口；数据库名固定为 `report_demo`，启动脚本拒绝其他库名。停止脚本检查 PID 和程序路径，保留 MySQL、Redis 及数据。

当前快照只支持最新结构，不迁移旧版会话、预览或清单。演示库固定为 `report_demo`，不因版本变化另建数据库。结构不符时停止处理，只有取得用户明确授权后才重建该库；不自动清库或修复校验和。

## 工作台操作

报表右上角的“派单记录”打开手工记录抽屉，可展开详情、读取追溯及按状态核对或重试。运营治理使用中文业务环节和状态，提供明细搜索与分页；指标始终按服务端完整汇总计算。具体入口、状态含义和统计口径见[工作台操作与运营治理](docs/工作台操作与运营治理.md)。

## 演示操作

在九条记录尚未派单的新建基线上，使用主要演示账号依次输入。已确认派单的记录会退出预览，普通重启和 `prepare-demo.ps1` 不会复位它们，因此使用中的库可能少于九条：

1. `查一下我有哪些可以派单`：得到九条，销售三条、应收四条、费用两条。
2. `销售报表金额大于96000的不要`：剩八条，96000 元那条仍保留。
3. `销售报表SO2026002不要`：剩七条，其他报表保持。
4. 刷新页面或重启服务后重新打开会话，选择仍为七条。
5. `给剩下的记录生成待确认派单清单`：生成七条 PENDING 清单；只有点击确认才会执行。

字段名、类型及说明直接来自授权目录。派单选择支持数值、日期、文本、布尔、空值及有界 AND/OR 条件，并在完整字段快照中排除、恢复或只保留匹配记录；派单选择中的排序、数量上限及多步查询仍需澄清。普通业务查询另支持排序、分页和确定性统计。客户使用稳定标识、全称及已维护别名，不按相似名称自动合并。

失败请求不会把已确定的公司一律标成未知；后续明确记录操作可纠正，含糊的直接建单仍被阻止。权限、用户确认、幂等请求号、租约、规则与执行版本保护继续生效。

执行时在来源行锁内核对全部已确认标量字段，字段变化需重新查询确认。查询规则出错或扫描超限会明确失败，不激活部分结果；默认扫描上限为 100000 条、120 秒。后台任务处理绑定本部署租户，模型输入和调查消息采用统一出站脱敏。具体边界见字段设计说明；真实 ERP、正式认证和生产容量仍待验收。

## 当前文档与流程图

- [十二页完整流程图：HTML](docs/diagrams/demo-final/index.html) · [SVG/Mermaid 入口](docs/diagrams/README.md) · [可编辑 draw.io](docs/diagrams/demo-final/demo-final.drawio)
- [工作台操作、手工派单与运营治理](docs/工作台操作与运营治理.md)
- [通用业务查询与工单说明](docs/通用业务助手.md) · [已完成的实施计划](docs/通用业务助手实施计划_2026-10-06.md)
- [V1 配置字段与状态恢复设计](docs/语义V1配置字段筛选.md)
- [MCP 业务服务与接口契约](docs/MCP业务服务契约.md) · [外部派单接入契约](docs/MCP派单接入契约.md)
- [异常调查 Agent 当前实现](docs/异常调查Agent.md) · [上下文与证据工程](docs/上下文工程设计与验收_2026-10-06.md)
- [数据库字典：38 表、456 字段](docs/db/表结构字典.md) · [Docker 部署说明](docs/Docker部署指南.md)
- [演示范围与生产待办](docs/演示范围与生产待办.md) · [历史资料索引](docs/history/README.md)

流程图由 `node docs/diagrams/build-semantic-flows.cjs` 统一生成 HTML、SVG、Mermaid、draw.io 和数据文件；修改生成器后必须重新生成。

## 验证入口

最新结果见[统一任务编排最终接续验收（2026-10-10 至 11）](docs/review/统一任务编排最终接续验收_2026-10-10.md)：公司范围、当前原文、查询展示与复核恢复修复完成；Java 919 项、前端 100 项及构建通过，后续工具回归 16 项通过。完整真实回放原始评分 152/153，修复比较器误报后同批响应离线复算 153/153，另发起真实数值复验 30/30、独立浏览器 21 步通过。[上一阶段报告](docs/review/统一任务编排接续修复与验收_2026-10-09.md)补齐上次提交的第十三至十五轮实际结果；[上次接续清单](docs/review/统一任务编排接续清单_2026-10-09.md)保留原检查点、失败证据与异机接续命令。

此前界面优化、详情交互修复及当时文档／流程图核对见[界面优化与文档流程核对](docs/review/界面优化与文档流程核对_2026-10-07.md)。该记录区分前端回归、浏览器检查、文档与图表静态核对；没有把以往真实模型或数据库验收当成本次重跑结果。

本次固定库名及重建结果见 [report_demo 初始化记录](docs/review/report_demo重建与初始化_2026-10-06.md)。此前兼容逻辑清理见 [清理与验证记录](docs/review/旧兼容逻辑清理_2026-10-06.md)。当前使用单一 V1 初始化脚本和固定的 `report_demo` 库；旧协议、编码映射、旧字段补齐与自动接管非空库入口已移除。此前 [V1 重新编号记录](docs/review/V1重新编号与验收_2026-10-06.md) 保留当时的真实结果，不代替本轮验证。

此前安全、完整性、租户边界及语义恢复修复见[生产评审问题修复与整体回归](docs/review/生产评审问题修复与整体回归_2026-10-06.md)，其中保留各轮失败与后续复验，区分业务正确、安全拒绝和基础设施限流。

通用业务查询、工单与新旧流程联合回归见[通用业务助手实现与验收](docs/review/通用业务助手实现与验收_2026-10-07.md)，以该报告最终记录的状态和证据范围为准。

此前整体功能复测见[整体回归与多轮对话验收](docs/review/整体回归与多轮对话验收_2026-10-07.md)，记录岗位拟真多轮语料、独立事实与筛选条件核对、状态恢复、专项测试、跳过项及历史失败。本轮保留既有派单数据，不将初始化后的全未派状态当作当前运行数据。

```powershell
node tools/check-demo-baseline.cjs
node tools/check-no-legacy-compat.cjs
node tools/check-comments.cjs
node tools/check-docs-real-model.cjs
./tools/test-semantic-fields.ps1
./tools/test-semantic-fields.ps1 -CorpusPath tools/fixtures/semantic-production-holdout.json
./tools/test-semantic-fields.ps1 -CorpusPath tools/fixtures/semantic-production-reserve.json
```

`test-regression.ps1` 会创建独立测试库，默认拒绝执行；先获得用户明确授权，才可传入 `-AllowIsolatedDatabases` 运行程序/状态机/HTTP MCP 回归、前端逻辑与构建，不用测试替身宣称真实模型已通过。`test-semantic-fields.ps1` 对运行中的最终演示服务执行六个连续场景、十四个条件/选择步骤，不确认派单；依赖上述九条未派单基线及主要账号。

`test-semantic-live.ps1` 和 `test-semantic-generalization.ps1` 保留为 V1 模型诊断工具，使用合成事实并归档失败，不替代真实业务验收。调查的真实模型验证使用 `test-investigation-live.ps1`。各层测试与跳过项分别报告。

重新编号前的验收见 [V4 历史记录](docs/review/最终演示基线整理_2026-10-06.md)。历史 V3 泛化失败也保留；最终演示版不意味着任意自然语言、外部 ERP、正式外部认证、生产容量或灾备已经验收。
