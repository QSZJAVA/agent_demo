> 历史归档：V1 重新编号前的 V4 基线，保留当时版本和证据范围。当前操作见根 README。

# 报表派单 Agent · 最终演示版

最终演示基线为 **2026-10-06 的 V4 配置字段筛选版**，统一定义在 [demo-baseline.json](../../demo-baseline.json)。运行链路为 `real,mcp`、`agent.semantic.mode=active`、真实模型接口和带认证的 HTTP MCP 业务服务。当前已验证模型为 `deepseek-v4.1-flash`，请求原生 JSON Schema，thinking 关闭。

## 启动与账号

需要 JDK 17+、PowerShell 7.2+、Node.js、MySQL 8 和 Redis。保持现有依赖版本，不升级前端框架或组件。

1. 参考 [本机配置模板](../../tools/env.local.example.cmd)，准备已忽略的 `tools/env.local.cmd`。真实模型也可放在已忽略且限制访问的 `.runtime/llm-credentials.json`，字段为 `apiKey`、`baseUrl`、`model`；密钥不写入文档或版本库。
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
| 专用 Demo 库 | `report_semantic_fields_20261006` |
| 主要演示账号 | `semantic_fields_a`，A 公司销售、应收、费用权限 |
| 密码查看位置 | `.runtime/demo-accounts.md`；主要账号也保存在 `.runtime/semantic-fields-account.json` |
| 初始管理员 | `admin`；初始密码在 `.runtime/mcp-credentials.json` 的 `adminPassword` |

`prepare-demo.ps1` 仅向已核实的最终专用库幂等补齐两张演示发票，不清库、不复位派单状态；随后创建或更新五个演示账号。已有本机主要账号密码继续复用，账号权限恢复为脚本定义并撤销其旧登录会话。普通重启无需再次准备账号。若演示记录已实际派单，不会自动把它们改回待派单。

停止与重启：

```powershell
./tools/stop-app.ps1
./tools/start-app.ps1 -SkipBuild
```

需要重新编译时省略 `-SkipBuild`。较低层入口 `tools/start-mcp.ps1 -Build -Frontend` 使用相同数据库和真实模型配置；可显式指定端口及新专用库，但 `prepare-demo.ps1` 只维护最终命名的 Demo 库。停止脚本检查 PID 和程序路径，保留 MySQL、Redis 及数据。

当前快照只支持最新结构，不迁移旧版会话、预览或清单。旧 `report_mcp`、`report_demo` 和早期语义专用库不会被启动脚本自动清理或修复校验和。结构不符时停止处理，按明确的新建/重建专用库方案处理。

## 演示操作

在九条记录尚未派单的新建基线上，使用主要演示账号依次输入。已确认派单的记录会退出预览，普通重启和 `prepare-demo.ps1` 不会复位它们，因此使用中的库可能少于九条：

1. `查一下我有哪些可以派单`：得到九条，销售三条、应收四条、费用两条。
2. `销售报表金额大于96000的不要`：剩八条，96000 元那条仍保留。
3. `销售报表SO2026002不要`：剩七条，其他报表保持。
4. 刷新页面或重启服务后重新打开会话，选择仍为七条。
5. `给剩下的记录生成待确认派单清单`：生成七条 PENDING 清单；只有点击确认才会执行。

字段名、类型及说明直接来自授权目录。支持数值、日期、文本、布尔、空值及有界 AND/OR 条件，并在完整字段快照中排除、恢复或只保留匹配记录。未知字段、排序、数量上限及多步查询仍需澄清。客户使用稳定标识、全称及已维护别名，不按相似名称自动合并。

失败请求不会把已确定的公司一律标成未知；后续明确记录操作可纠正，含糊的直接建单仍被阻止。权限、用户确认、幂等请求号、租约、规则与执行版本保护继续生效。

## 当前文档与流程图

- [八页完整流程图：HTML](../diagrams/demo-final/index.html) · [SVG/Mermaid 入口](../diagrams/README.md) · [可编辑 draw.io](../diagrams/demo-final/demo-final.drawio)
- [V4 配置字段与状态恢复设计](语义V4配置字段筛选_2026-10-06.md)
- [MCP 业务服务与接口契约](../MCP业务服务契约.md) · [外部派单接入契约](../MCP派单接入契约.md)
- [异常调查 Agent 设计](../异常调查Agent与评估体系详细设计_2026-10-05.md) · [上下文与证据工程](../上下文工程设计与验收_2026-10-06.md)
- [数据库字典：39 表、464 字段](../db/表结构字典.md) · [Docker 部署说明](../Docker部署指南.md)
- [演示范围与生产待办](../演示范围与生产待办.md) · [历史资料索引](README.md)

流程图由 `node docs/diagrams/build-semantic-flows.cjs` 统一生成 HTML、SVG、Mermaid、draw.io 和数据文件；修改生成器后必须重新生成。

## 验证入口

最新结果见 [最终演示基线整理与验证](../review/最终演示基线整理_2026-10-06.md)：完整程序回归无失败，最终十四步真实模型加 HTTP MCP 通过，首轮失败和跳过项独立保留。当前演示库已有八条派单结果，完整九条路径在独立新建验收库验证。

```powershell
node tools/check-demo-baseline.cjs
node tools/check-comments.cjs
node tools/check-docs-real-model.cjs
./tools/test-regression.ps1
./tools/test-semantic-fields.ps1
```

`test-regression.ps1` 运行隔离库中的程序/状态机/HTTP MCP 回归、前端逻辑与构建，不用测试替身宣称真实模型已通过。`test-semantic-fields.ps1` 对运行中的最终演示服务执行六个连续场景、十四个条件/选择步骤，不确认派单；依赖上述九条未派单基线及主要账号。

`test-semantic-live.ps1` 和 `test-semantic-generalization.ps1` 保留为 V4 模型诊断工具，使用合成事实并归档失败，不替代真实业务验收。调查的真实模型验证使用 `test-investigation-live.ps1`。各层测试与跳过项分别报告。

已完成的字段版本验收见 [2026-10-06 记录](../review/配置字段筛选与连续对话验收_2026-10-06.md)：十四步真实模型加 HTTP MCP 通过，浏览器连续两句、重启恢复和待确认清单通过。历史 V3 泛化失败仍保留；最终演示版不意味着任意自然语言、外部 ERP、正式外部认证、生产容量或灾备已经验收。
