# 报表派单 Agent · MCP 双服务版

当前分支：`codex/mcp-business-service`。

本文及仓库文档以真实模型 `real,mcp` 链路为基线，编写规则见 [AGENTS.md](AGENTS.md)。文档与图表变更后运行 `node tools/check-docs-real-model.cjs`。

系统通过自然语言查询可派单记录、调整选择并生成待确认清单。用户确认后，Agent 经 MCP 调用独立业务服务执行派单，保留逐条结果、幂等请求号、审计与追溯记录。

本分支已实现用户登录、服务间认证、MCP 报表查询与派单。默认使用新演示库 `report_mcp`；业务服务管理本库的报表派单状态，尚未接入外部 ERP。

## 架构与完整流程

![系统架构](docs/diagrams/mcp-architecture.png)

- [可编辑 draw.io 文档（架构、完整请求流程两页）](docs/diagrams/mcp-system.drawio)
- [完整请求流程长图](docs/diagrams/mcp-task-flow.png) · [两页 PDF](docs/diagrams/mcp-system.pdf)
- [图表说明与源码依据](docs/diagrams/README.md) · [MCP 实施与验收说明](docs/MCP业务服务实施与验收.md)

| 部分 | 职责 |
| --- | --- |
| 前端 `frontend` | 登录、报表分页、SSE 对话、预览勾选、派单确认、历史与管理页面 |
| Agent 后端 `backend` | 模型意图解析、对话状态、规则/目录管理、预览与清单编排、确认、重试和核对 |
| 业务后端 `business-service` | MCP 工具、当前账号权限复核、报表数据查询、业务派单事务与持久化幂等结果 |
| MySQL 8 | 账号/会话、目录/规则、对话、预览/清单、配额租约、审计、报表源数据及派单结果 |
| Redis | 工作记忆、频率限制、规则/目录刷新广播等辅助能力 |

**模型只输出结构化意图，Agent 服务负责调用 MCP。** 生成清单和实际派单是不同操作；真实写入由用户点击确认后发起的 REST 请求触发。MCP 调用失败会记录错误或待核对状态。

两个后端是独立进程，但当前共用一个 MySQL schema。业务服务读取共享账号、确认清单、目录和规则完成再次复核；图中的控制表与业务表是逻辑分组，尚未拆成独立数据库。

## 快速启动

推荐使用 Windows PowerShell 7.2+，并安装 JDK 17+、Node.js 22、MySQL 8 和 Redis。确保 `java`、`node`、`npm` 可从 PATH 调用；Maven 使用仓库内 Wrapper。

### 1. 准备依赖与连接配置

在仓库根目录执行：

```powershell
npm.cmd --prefix ./frontend ci

# 已有本机配置时保留原文件
if (-not (Test-Path ./tools/env.local.cmd)) {
    Copy-Item ./tools/env.local.example.cmd ./tools/env.local.cmd
}
```

在 `tools/env.local.cmd` 填写 `DB_HOST`、`DB_PORT`、`DB_USERNAME`、`DB_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`，也可以直接设置这些环境变量。MySQL 账号需能创建目标库并执行迁移，或由部署方预先准备所需权限。

MCP 启动脚本只把该文件中的 `DB_*`、`REDIS_*` 当作数据读取，不执行 CMD 文件，也不读取其中的 `JAVA_HOME`、`LLM_MODE` 或 `LLM_*`。启动目标库由 `-Database` 参数决定，默认 `report_mcp`，会覆盖继承的 `DB_NAME`。

### 2. 配置真实模型并启动

真实模型模式：

```powershell
$env:LLM_BASE_URL = Read-Host 'OpenAI 兼容端点的 base URL'
$env:LLM_MODEL = 'deepseek-v4.1-flash'
$env:LLM_API_KEY = Read-Host '模型 API Key' -MaskInput

./tools/start-mcp.ps1 -Build -Frontend
```

端点必须支持配置的模型与请求选项。脚本会去掉 base URL 末尾的 `/v1`，并开启原生 JSON Schema。

模型密钥缺失或端点不可用时，应先修复配置与连接，再验证真实模型调用。本文所有操作均基于 `real,mcp` 场景。

### 3. 登录与访问

| 默认地址 | 用途 |
| --- | --- |
| [http://127.0.0.1:5173](http://127.0.0.1:5173) | 登录、报表与派单助手 |
| `http://127.0.0.1:8080` | 面向前端的 Agent REST / SSE 服务 |
| `http://127.0.0.1:8090/mcp` | 业务 MCP 入口，需要独立服务凭据 |
| `http://127.0.0.1:8090/health` | 业务服务的数据库健康检查 |
| `http://127.0.0.1:8080/api/health/readiness` | Agent 的 MySQL、Redis、业务服务连通性检查 |

首次启动使用账号 `admin`。随机初始密码保存在本机 `.runtime/mcp-credentials.json` 的 `adminPassword` 字段；同文件的 `serviceToken` 用于两个后端之间的调用。文件记录的是初始凭据，后续修改账号密码不会由重启恢复。

启动脚本先启动业务服务并等待 Flyway 迁移与健康检查，再启动 Agent；Agent 侧关闭 Flyway，不装配演示数据初始化器。两个后端默认仅监听回环地址。

### 停止、重启与可选参数

```powershell
./tools/stop-mcp.ps1
./tools/start-mcp.ps1 -Build -Frontend

# 自定义端口或目标演示库
./tools/stop-mcp.ps1
./tools/start-mcp.ps1 -AgentPort 8082 -BusinessPort 8092 -Database report_mcp_test -Frontend
```

重启前保留或重新设置真实模型环境变量，也可使用本机 `.runtime/llm-credentials.json`。打包前先停止旧进程，避免 Windows 锁定运行中的 JAR。

| 参数 | 默认值 / 行为 |
| --- | --- |
| `-Build` | 从根 `pom.xml` 构建两个模块；省略时使用已有 JAR |
| `-Frontend` | 5173 未被占用时启动 Vite；新启动的前端代理指向本次 Agent 端口 |
| Agent profiles | 上述命令启动 `real,mcp` |
| `-AgentPort` / `-BusinessPort` | 8080 / 8090 |
| `-Database` | `report_mcp` |
| 日志与 PID | `.runtime/agent.*`、`.runtime/business.*`、`.runtime/frontend.*` |

已有前端进程不会被启动脚本重配；手动启动前端或切换 Agent 端口时，设置 `AGENT_API_URL` 后重启 Vite。停止脚本会核验 PID 和本工作区程序路径，避免误停其他进程。

`.runtime/` 已被 Git 和 Docker 忽略，Windows 脚本会限制其目录访问权限。模型密钥只注入 Agent 进程，不写入该凭据文件，也不传给前端和业务服务。

## 配置、存储与技术栈

| 配置 | 当前双服务模式 |
| --- | --- |
| 数据库 / Redis | 使用 `DB_*`、`REDIS_*`；本机脚本默认新库 `report_mcp` |
| `BUSINESS_MCP_URL` | 业务服务 base URL；本机脚本按 `-BusinessPort` 设置，路径由客户端追加 `/mcp` |
| `BUSINESS_SERVICE_TOKEN` | 至少 32 字符，两个后端一致；本机脚本使用随机生成的服务凭据 |
| `AUTH_TENANT_ID` | 应用默认 `T001`；本机脚本固定使用 `T001` |
| `AUTH_BOOTSTRAP_PASSWORD` | 首次创建管理员使用，至少 12 位；本机脚本使用随机初始密码 |
| `security.enabled` | `mcp` profile 启用；关闭认证却启用远端 MCP 时拒绝启动 |
| `LLM_BASE_URL` / `LLM_MODEL` / `LLM_API_KEY` | 真实模型连接；本分支已联调 `deepseek-v4.1-flash` |
| `SEMANTIC_MODE` | 应用默认 `active`，使用语义 V2；`legacy` 为显式兼容模式 |
| `SEMANTIC_NATIVE_SCHEMA` | 应用默认 false，`start-mcp.ps1` 会设为 true |
| `SEMANTIC_MODEL` | 留空沿用 `LLM_MODEL`，可为语义解析单独指定模型 |
| `SEMANTIC_THINKING_ENABLED` | 默认 false；需与端点支持的 thinking 参数匹配 |

真实语义解析温度为 0，默认最多输出 2400 tokens；格式、原文证据或报表覆盖校验失败时最多追加一次解析修复。当前脚本没有关闭原生 Schema 的参数；若端点不支持，应手动启动 Agent，并传入 `--agent.semantic.native-schema=false`。

手动启动需先设置相同的 `DB_NAME`、数据库/Redis 连接、`AUTH_TENANT_ID` 和服务凭据。业务服务先完成迁移；Agent 使用 `real,mcp` profiles，加 `--spring.flyway.enabled=false --server.port=8080`。直接使用 `mcp` profile 而不指定端口时，Agent 默认监听 **8081**，与一键脚本的 **8080** 不同。

| 部分 | 版本 / 存储说明 |
| --- | --- |
| Agent | Java 17+、Spring Boot 3.5.16、Spring AI 1.1.8 |
| 业务服务 | Spring Boot 3.5.16、MCP Java SDK 0.18.3；独立实现业务查询和派单事务 |
| 数据访问 / 规则 | MyBatis-Plus 3.5.17、JdbcTemplate、Aviator 5.4.4 |
| 前端 | Vue 2.7、Element UI、Vite 6.4.3 |
| 数据结构 | Flyway V1–V19，包含 Java 迁移 V7；V19 增加账号、会话及业务派单幂等结果表 |

默认启动不会重置已有数据；新空库由业务服务初始化示例报表、目录与规则，后续启动只迁移结构。使用默认库名不会迁移或修改原 `report_demo` 数据。应用保留演示重置开关，但一键 MCP 启动明确将其设为 false。

## 用户认证与账号管理

用户密码采用随机盐和 600000 轮 PBKDF2-HMAC-SHA256。登录返回 256 bit 随机 Bearer token，数据库只存 token 的 SHA-256 摘要，8 小时过期；前端使用 `sessionStorage`，退出登录立即撤销服务端会话。登录按账号和来源地址限流。

MCP 模式不接受 `X-User-Id` 作为身份凭据，过滤器用真实会话身份覆盖该头。除登录、认证模式查询和健康探针外，业务 API 都要求登录，包括用户信息及模型名称查询。旧的 `user1/user2/user3` 身份不会作为本分支的真实登录账号自动创建。

管理员通过 `PUT /api/auth/users` 创建或更新账号，分配公司、报表权限以及管理员标记。更新账号会撤销其已有会话；`enabled=false` 禁用账号，更新时不提供新密码可保留原密码。请求格式见 [账号接口说明](docs/MCP业务服务实施与验收.md#账号接口)。

服务间使用独立 Bearer 密钥，当前绑定配置租户。工具中的 `tenantId/operatorId` 由可信 Agent 代码填入；业务服务重新读取数据库中的账号状态、公司及报表权限。该凭据不下发浏览器、不放入模型提示，仅供可信服务调用。跨主机 MCP 连接要求 HTTPS；客户端只允许回环地址使用 HTTP。

前后端分域时，将 `CORS_ALLOWED_ORIGINS` 设置为准确的前端来源，例如 `https://reports.example.com`。合法预检在认证前处理，真实业务请求仍要求会话，401 响应也会附带允许来源的 CORS 头。

反向代理部署须为 Agent 设置 `TRUSTED_PROXY_CIDRS`，仅列出实际代理的 IP/CIDR；例如同机代理可使用 `127.0.0.1/32,::1/128`，容器代理则使用实际代理地址或专用网段。默认留空忽略转发头。登录限流从可信代理的 `X-Forwarded-For` 链右侧识别客户端，不接受直接访问者伪造地址；代理应追加或重写该头。不要把所有公网地址配置为可信代理。

运营管理员可在自身公司/报表权限范围内核对、读取和关闭禁用账号的遗留清单，审计记录实际管理员；不会重新启用原账号或重新发送派单。失败重试仍要求原账号有效并重新校验其当前权限。MCP `dispatch_lookup` 新增可选 `requestOperatorId`，仅管理员可用于指定原请求操作者，业务服务重新检查清单租户及公司/报表范围。

三张报表分页响应的业务 `id` 统一为 JSON 字符串，避免大整数在浏览器中失真；调用手工派单接口时也应保持字符串。

启动后可执行 `pwsh -File tools/prepare-demo-accounts.ps1` 创建并验证 `demo_admin`、`demo_a`、`demo_b`、`demo_sales` 四个演示账号。账号密码保存在 Git 忽略的 `.runtime/demo-accounts.md` 和 `.runtime/demo-accounts.json`；重复执行会复用密码并刷新账号权限、撤销旧会话。真实模型启动优先使用进程环境变量；未提供 `LLM_API_KEY` 时，`tools/start-mcp.ps1` 可读取本机 `.runtime/llm-credentials.json` 中的 `apiKey/baseUrl/model`，该文件不提交仓库。

## 业务操作与执行保障

1. 登录后查询，例如“查询 A 公司销售报表中可以派单的记录”。Agent 解析意图、验证范围，并经 MCP 查询业务数据。
2. 查看预览，在表格勾选记录或表达排除要求。服务端把选择绑定到对应预览，防止刷新或切换会话后误用旧选择。
3. 要求“把当前勾选的记录生成派单清单”，生成持久化的 `PENDING` 清单。
4. 点击“确认派单”。REST 服务复核归属、有效期、权限和版本，认领执行权，再逐条调用 `dispatch_submit`。
5. 查看成功、明确失败和未知结果。明确失败可显式重试；未知结果先通过 `dispatch_lookup` 核对，不盲目重发。

一次完整任务可以包含多轮 SSE 请求和一次单独的确认 REST 请求。报表页手工派单由用户操作触发，内部同样建立持久化清单并走确认与 MCP 执行路径。

派单时先记录稳定请求号和意图证据。业务服务在事务内检查幂等负载、已确认清单、执行版本、当前规则及源记录状态，再原子提交报表状态与请求结果。同一请求号且负载一致时，已成功的调用直接返回原结果；不同负载被拒绝。结果查询使用锁定读，避免把尚未提交的执行误报为“未受理”。

语义 V2 支持公司、报表与记录排除；不支持的日期、金额、排序等条件会要求澄清。当前“仅选一条”可通过表格勾选完成，不能将所有自然语言表达视为已支持。详细边界见 [语义 V2 说明](docs/语义V2实施与验收.md)。

## MCP 工具

业务入口为 `/mcp`，使用无状态 Streamable HTTP，支持标准 `initialize`、`tools/list`、`tools/call`。无状态指 MCP 连接不承载业务状态；清单、请求号和结果仍持久化。

| 工具 | 能力与限制 |
| --- | --- |
| `report_catalog` | 列出操作者有权访问的报表及字段 |
| `report_page` | 销售、应收、费用报表分页，包含派单状态；每页最多 200 条 |
| `report_records` | 游标/分页查询、按 ID 读取或复核、管理员试算；每批最多 500 条 |
| `report_probe` | 发布报表配置前检查数据源表和字段，供可信服务内部调用 |
| `dispatch_submit` | 提交已确认条目，校验请求号、负载、权限、确认状态及执行版本 |
| `dispatch_lookup` | 按原请求号返回 SUCCESS / FAILED / NOT_FOUND / UNKNOWN |

工具的 text 与 structuredContent 返回 `{data: ...}`；工具错误使用 `isError=true` 及 `{code,message}`。协议调用成功不等于业务派单成功。参数 Schema 通过 `tools/list` 获取，完整约定见 [MCP 工具契约](docs/MCP业务服务实施与验收.md#mcp-工具契约)。

## 主要 REST 接口

下表位于 Agent 后端，需要用户会话的请求使用 `Authorization: Bearer <token>`。

| 接口 | 行为 |
| --- | --- |
| `GET /api/auth/mode`、`POST /api/auth/login` | 查询是否要求登录、执行登录；免登录访问 |
| `GET /api/auth/me`、`POST /api/auth/logout` | 当前身份、退出会话 |
| `GET /api/auth/users` | MCP 模式仅返回当前用户，供前端兼容展示 |
| `PUT /api/auth/users` | 管理员创建/更新账号及权限 |
| `GET /api/health/readiness` | MySQL、Redis、业务服务连通性；HTTP 200/503 |
| `GET /api/report/{sales,receivable,expense}/page?page=1&size=50` | 通过 MCP 查询分页报表，返回 records/total/page/size |
| `POST /api/agent/chat` | 自然语言对话，SSE 返回文字、预览、清单、选择状态及结束事件 |
| `GET /api/agent/model` | 当前模型名称 |
| `GET /api/agent/conversations`、`GET .../{id}/messages`、`GET .../{id}/card-states`、`GET .../{id}/selection` | 会话、历史、卡片状态及选择恢复 |
| `POST /api/dispatch/previews`、`POST /api/dispatch/previews/jobs` | 同步预览与可恢复异步任务 |
| `GET /api/dispatch/previews/{id}`、`GET .../{id}/items` | 预览状态与分页明细 |
| `POST /api/dispatch/plans` | 从预览建单，支持 `Idempotency-Key` |
| `GET /api/dispatch/plans/{id}`、`GET .../{id}/items` | 清单状态与逐条结果 |
| `POST /api/dispatch/plans/{id}/{confirm,retry-failed,reconcile,cancel}` | 确认、失败重试、核对与取消 |
| `GET /api/dispatch/plans/{id}/trace`、`POST /api/dispatch/direct` | 追溯查询、报表页手工派单 |
| `/api/report-catalog`、`/api/rules`、`/api/operations` | 目录、规则和运营管理 |

普通 REST 返回 `{code,message,data}`，`code=0` 表示成功；部分业务错误仍以 HTTP 200 携带业务码，认证过滤失败与健康探针使用实际 HTTP 状态。SSE 建流前可能返回 JSON 错误，建流后以事件报告；通过历史、卡片状态及任务接口恢复，不保证原始文本流续传。未分页的旧报表接口保留，但最多返回前 200 条。

## 测试与验收

在仓库根目录依次执行：

```powershell
pwsh -File ./tools/test-p2.ps1 -BackendOnly
pwsh -File ./tools/test-mcp.ps1
npm.cmd --prefix ./frontend test
npm.cmd --prefix ./frontend run build
```

| 入口 | 范围 |
| --- | --- |
| `tools/test-p2.ps1` | 启用 TRACE/P2 隔离库回归，关闭 DEMO_IT/P2_UI；支持 `-BackendOnly`、`-Tests` |
| `tools/test-mcp.ps1` | 启用 MCP_IT，测试真实 HTTP MCP、事务、权限、并发幂等和服务重启；创建并清理 `mcp_it_<UUID>` 数据库 |
| `tools/test-mcp-http.py` | 当前 MCP 双服务的登录、真实模型与业务链路验收，需要运行中的服务和本机管理员凭据 |
| `tools/test-semantic-live.ps1 -NativeSchema -Corpus all` | 纯真实模型语义回放；使用合成数据，不查业务库、不执行派单 |

集成测试需要能创建/删除临时数据库的账号及 Redis。配置来自进程环境或 `tools/env.local.cmd`；业务测试不可将跳过项视为已通过。

使用 Python 3 验收运行中的 MCP 服务：

```powershell
# 默认会创建测试账号、会话和清单，取消清单并禁用测试账号；不确认派单
python ./tools/test-mcp-http.py

# 仅用于专门的 report_mcp 演示/测试库：实际确认其中一条候选记录
python ./tools/test-mcp-http.py --confirm-dispatch
```

HTTP 验收脚本默认连接 Agent 8080、MCP 8090，读取 `.runtime/mcp-credentials.json`，输出 `.runtime/mcp-http-acceptance.json`；可用 `--base-url`、`--mcp-url`、`--credentials` 覆盖。它依赖样例销售报表与管理员账号，应在专用演示环境运行。

2026-09-29 的本分支验收记录：

| 验证项 | 已记录结果 |
| --- | --- |
| 后端回归与认证测试 | 624 项：609 通过、15 跳过、0 失败 |
| 独立业务服务 MCP 集成测试 | 16/16 通过 |
| 前端测试与构建 | 53/53 通过，构建成功 |
| 真实模型 MCP 完整链路 | 16/16 通过，新演示库实际派单 1 条；重复确认与请求号核对通过 |

结果与范围见 [本分支验收说明](docs/MCP业务服务实施与验收.md#验证) 和 [HTTP 证据](docs/review/mcp-service-http-2026-09-29.json)。这些是已记录的回归与链路验证，不是任意自然语言输入的准确率承诺；之前固定语料 75/75 的结果见 [历史模型联调报告](docs/真实模型联调与缺陷修复_2026-09-29.md)。

当前 [CI](.github/workflows/regression.yml) 运行 Agent 后端和前端回归，尚未执行独立业务服务的 MCP 集成测试；本分支应额外运行 `tools/test-mcp.ps1`。

## 工程结构与兼容入口

下表中的 `backend/...` 指 `backend/src/main/java/com/example/report`。

| 目录 | 用途 |
| --- | --- |
| 根 `pom.xml` | Maven 聚合构建 `backend` 与 `business-service` |
| `backend/.../semantic`、`backend/.../agent` | 语义解析、对话状态、SSE 与结构化卡片 |
| `backend/.../security`、`backend/.../mcp` | 用户认证、MCP 客户端和适配器 |
| `backend/.../catalog`、`rule`、`dispatch`、`operations` | 目录、规则、任务编排、审计与运营治理 |
| `business-service/src/main/java/com/example/business` | 独立业务应用、MCP 工具注册、服务认证、查询与派单事务 |
| `backend/src/main/resources/db/migration`、`backend/src/main/java/db/migration` | 两个进程使用的 SQL / Java 迁移，合计 V1–V19 |
| `frontend` | 登录、页面、请求封装与前端测试 |
| `tools` | 双服务启动/停止、隔离回归、模型和 HTTP 验收脚本 |
| `docs/diagrams` | draw.io 源文件、PNG/SVG 和 PDF |

业务服务复用 `backend` 的 `lib` 分类 JAR 中的公共类型与数据访问代码，但应用只装配业务所需组件。两个可执行产物分别是 `backend/target/report-demo-2.0.0.jar` 和 `business-service/target/business-service-2.0.0.jar`。

现有 `deploy.sh`、`docker-compose.yml`、`tools/start-backend.cmd` 和 `tools/start-real.ps1` 尚未提供当前 MCP 双服务及真实登录的完整部署。当前启停使用 `tools/start-mcp.ps1` 和 `tools/stop-mcp.ps1`；运行服务验收使用支持真实会话认证的 `tools/test-mcp-http.py`。

本分支提供业务服务 Dockerfile，可在仓库根目录构建：

```bash
docker build -f business-service/Dockerfile -t report-business-service:1.0.0 .
```

该镜像构建文件已提供，但尚无双服务 Compose 编排，本次验收未执行 Docker 构建。容器部署需配置数据库、Redis、服务凭据、监听地址和 HTTPS 入口；现有 [Docker 部署指南](docs/Docker部署指南.md) 对应原单体部署。

后续生产接入包括外部 ERP、企业统一登录、按 MCP 客户端授权、独立数据库边界，以及数据库最小权限、密钥轮换、备份和监控。外部业务系统应继续遵守 [MCP 派单接入约定](docs/MCP派单接入契约.md)。
