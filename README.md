# 报表派单 Agent Demo

自然语言报表查询与派单演示项目。用户先查询可派单记录、调整选择、生成待确认清单，再通过按钮确认执行；服务端校验权限、规则、版本和记录当前状态，并保留逐条结果与追溯记录。

当前已实现配置化报表目录、规则管理、异步预览、持久化清单、失败重试和结果核对，以及运营治理页面。**身份仍由 `X-User-Id` 模拟，派单仍由演示网关回写数据库；真实认证与真实业务派单网关属于下一阶段。** 切换真实大模型不会改变这两个边界。

## 技术与存储

| 部分 | 当前实现 |
| --- | --- |
| 前端 | Vue 2.7、Element UI、Vite；报表、聊天与历史会话、规则、目录、运营治理页面 |
| 后端 | Java 17+、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 3.5.17 与 JdbcTemplate、Aviator 5.4.4 |
| MySQL 8 | 报表、目录、规则及版本，预览/清单及明细，任务、请求去重、并发配额租约、对话、追溯、审计和运营策略 |
| Redis | 模型短期工作记忆、规则/目录刷新广播、分钟频率限制；不是派单业务状态的唯一来源 |
| 数据结构 | Flyway 版本迁移，当前迁移范围 V1–V17；示例数据由初始化回调管理 |

| 目录 | 用途 |
| --- | --- |
| `backend/src/main/java/com/example/report/catalog` | 报表目录、权限过滤、名称/别名/模糊解析；`query` 提供配置式查询及事实映射 |
| `backend/src/main/java/com/example/report/rule` | 规则校验、试算、发布/回滚及候选扫描 |
| `backend/src/main/java/com/example/report/dispatch` | 预览、清单、执行、恢复和演示网关 |
| `backend/src/main/java/com/example/report/operations` | 评估、指标、留存、脱敏、访问审计和人工处理 |
| `backend/src/main/java/com/example/report/agent` | 模型工具、SSE 与结构化卡片 |
| `frontend` | 页面、组件、请求封装和前端测试 |
| `tools` | 本地启动脚本、隔离数据库回归入口和私有配置模板 |
| `docs` | 当前设计/部署说明及按日期保留的历史评审 |

## Docker 启动

Linux 项目根目录执行：

```bash
chmod +x deploy.sh
./deploy.sh up
```

脚本生成随机 MySQL/Redis 密码和整站 Basic Auth 口令，构建镜像，并等待服务健康；失败或等待超时以非零状态退出。后端通过 `GET /api/health/readiness` 检查 MySQL 和 Redis，依赖正常返回 HTTP 200，否则返回 503；前端等待后端健康后启动。

默认使用 `mock` 模型，不需要 API Key。访问口令只用于保护演示入口，持有口令的人仍可切换演示管理员。详细配置、打包、备份与更新见 [Docker 部署指南](docs/Docker部署指南.md)。

## 本地启动

需要 MySQL 8、Redis、JDK 17+ 和支持当前 Vite 的 Node.js（CI 使用 Node.js 22）；仓库自带 Maven Wrapper。

1. 配置 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`。默认连接 `localhost:3306/report_demo`、用户名 `root`，密码没有内置固定值，按本机实例提供。账号需具备建库/迁移所需权限，或事先准备数据库。
2. 配置 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`，默认 `localhost:6379`，密码留空表示无密码实例。
3. Windows 可复制 `tools/env.local.example.cmd` 为 `tools/env.local.cmd` 并填写本机参数，运行 `tools/start-backend.cmd` 以 mock 模式启动后端。此私有文件不入库。其他平台在 `backend` 执行 `./mvnw spring-boot:run -Dspring-boot.run.profiles=mock`。
4. 在 `frontend` 执行 `npm ci`、`npm run dev`，访问 [本地前端](http://127.0.0.1:5173)。后端默认端口为 8080。

**数据初始化：** `DEMO_RESET_ON_STARTUP` 默认 `false`。新空库首次初始化示例报表、目录和规则；已有数据库正常启动保留业务数据与管理配置，结构变更由 Flyway 迁移。只有显式设为 `true` 才会在每次启动清空并重建演示报表数据、目录和规则，旧预览/清单按重置逻辑失效。该开关会影响相关表的全部租户数据，仅在可丢弃的演示/测试库使用，重置后恢复 `false`。

## 模型配置

| 配置 | 当前默认/说明 |
| --- | --- |
| 模式 | 本地启动脚本与 Docker 默认 mock；直接不指定 profile 启动使用真实模型配置 |
| `LLM_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode`；不附加 `/v1`，请求路径已含 `/v1/chat/completions` |
| `LLM_MODEL` | 本地 `application.yml` 默认 `deepseek-v4.1-flash`；Compose 默认 `qwen3.7-plus` |
| `LLM_API_KEY` | 真实模型模式必须提供有效密钥；不要写入源码 |
| 请求选项 | 温度 `0.1`；当前 `extra-body` 为 `thinking: {type: disabled}` |

模型名、兼容端点和关闭思考参数必须与所选供应商实际支持范围匹配；上述值描述仓库默认配置，不代表所有组合都已联调。`extra-body` 在 `backend/src/main/resources/application.yml` 中，`.env` 没有单独的同名配置项；必要时修改配置并重建后端。直连 DeepSeek 时可将端点改为 `https://api.deepseek.com`，同时核对模型名和参数。

Docker 编辑 `.env` 的 `LLM_*` 后执行 `./deploy.sh start-real`；返回 mock 用 `./deploy.sh start-mock`。本地真实模型模式设置 `LLM_*` 后执行 Maven 启动命令，不带 `mock` profile。

## 业务操作

演示用户均属于租户 `T001`：`user1` 只能看 A 公司；`user2` 只能看 B 公司；`user3` 看 B 公司但无应收报表权限；`admin` 看 A/B/C 公司并可管理规则、目录和运营策略。

明确公司且能完整识别的查询（如“A公司销售报表的”“我在B公司有吗”）由服务端直接校验权限并执行预览，回复与历史记录使用实际工具结果。无权限的公司在异步任务入队前即被拒绝；上一轮查询过哪家公司不能作为权限依据。其余复杂意图继续交给模型处理，派单仍需确认卡片。

1. 输入“查一下我有哪些可以派单”。服务端先按目录、租户、报表权限和公司范围筛选，再按生效规则查询；候选数以当前数据为准。
2. 名称存在歧义时先选报表。较大预览以异步任务执行，卡片与明细分页读取，刷新后可恢复任务状态。
3. 在预览卡片取消勾选记录或表达排除要求，生成待确认清单。排除项必须来自对应预览，新查询/新清单会使旧卡片失效。
4. 点击确认，调用确定性 REST 接口。服务端复核后逐条派单，成功、明确失败和结果不明分别记录；明确失败可重试，结果不明先核对。
5. 在历史会话查看消息、当前卡片状态和派单追溯；管理员可维护规则/目录、查看指标、处理异常任务及配置留存。

## 主要接口

业务接口通常通过请求头 `X-User-Id` 传入演示身份。`/api/auth/users`、`/api/agent/model` 和 readiness 等信息/健康接口不要求该头；它不是生产认证协议。

| 接口 | 行为 |
| --- | --- |
| `GET /api/health/readiness` | MySQL 与 Redis 就绪检查，HTTP 200/503 |
| `GET /api/auth/users`、`GET /api/auth/me` | 演示用户列表、当前身份 |
| `GET /api/report/{sales,receivable,expense}` | 三张报表查询，服务端权限过滤 |
| `POST /api/agent/chat` | SSE：`conversation`、`text`、`preview_job`、`choice`、`preview`、`plan`、`result`、`error`、`done` |
| `POST /api/dispatch/previews`、`POST /api/dispatch/previews/jobs` | 同步/异步创建预览 |
| `GET /api/dispatch/previews/jobs/{jobId}`、`GET /api/dispatch/previews/jobs?conversationId=...` | 查询任务、发现会话最新任务；`POST .../{jobId}/cancel` 取消任务 |
| `GET /api/dispatch/previews/{id}`、`GET .../{id}/items?page=1&size=50` | 预览摘要/状态及分页明细 |
| `POST /api/dispatch/plans` | 从预览建单，支持 `Idempotency-Key` 请求头 |
| `GET /api/dispatch/plans/{id}`、`GET .../{id}/items` | 清单摘要/状态及分页明细 |
| `POST /api/dispatch/plans/{id}/{confirm,retry-failed,reconcile,cancel}` | 确认、失败重试、核对、取消；`execute` 为兼容确认入口 |
| `GET /api/dispatch/plans/{id}/trace` | 派单追溯；分段分页和补投接口见控制器 |
| `POST /api/dispatch/direct` | 报表页手工派单 |
| `GET /api/agent/conversations`、`GET .../{id}/messages`、`GET .../{id}/card-states` | 会话列表、历史消息和当前卡片状态；首次对话创建会话 |
| `PUT /api/agent/conversations/{id}/title`、`DELETE .../{id}` | 改名、软删除；内容清理另走留存/删除流程 |
| `/api/report-catalog`、`/api/rules`、`/api/operations` | 目录、规则、运营管理；具体动作见 [技术选型与当前设计](docs/派单Agent技术选型.md) |

普通 REST 业务响应使用 `{code,message,data}`，`code=0` 表示成功；当前异常处理通常仍返回 HTTP 200，调用方需要检查业务码。readiness 使用实际 HTTP 状态；SSE 进入流前可能返回 JSON 错误，进入流后以事件报告结果。事件带请求号和序号，断线通过历史消息、卡片状态及任务查询恢复，不提供原始文本流的续传保证。

## 测试与验证

```bash
cd backend
./mvnw test
cd ../frontend
npm ci
npm test
npm run build
```

Windows 使用 `mvnw.cmd`。普通后端测试包含规则、目录、状态机及回归测试；真实 MySQL/Redis 集成测试按环境开关启用，不应把跳过当作已验证。

| 开关/入口 | 范围 |
| --- | --- |
| `TRACE_IT=true` | UUID 隔离数据库上的事务、持久化追溯等数据库集成测试 |
| `P2_IT=true` | UUID 隔离数据库上的运营治理集成测试 |
| `P2_UI=true` | 可选浏览器驻留测试，需配合 P2 测试，默认关闭 |
| `DEMO_IT=true` | 原有共用演示环境集成测试，会操作演示数据；仅在专用测试环境启用 |
| `./tools/test-p2.ps1` | Windows 设置 TRACE/P2 开关并显式关闭 DEMO_IT/P2_UI，运行后端、前端测试及构建；`-BackendOnly` 仅后端，`-Tests` 指定类 |

隔离测试读取 `TRACE_DB_HOST/PORT/USER/PASSWORD`、`TRACE_REDIS_HOST/PORT/PASSWORD` 等连接设置；脚本可从本地 `DB_*`/`REDIS_*` 映射，读取私有配置而不打印凭据。测试需要可创建和删除临时数据库的账号及专用 Redis 测试环境。CI 入口为 `.github/workflows/regression.yml`。`bash tools/test-deploy.sh` 使用模拟 Docker 命令验证部署等待与失败退出，不需要 Docker 服务；不替代真实容器启动验收。

按日期命名的评审文档仅记录对应历史版本的结果，不能代表当前检出的代码已完成相同验证。当前能力和下一阶段验收清单见 [Demo 到生产级待办](docs/派单Agent_Demo到生产级待办.md)。
