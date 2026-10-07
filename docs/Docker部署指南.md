# 真实模型 MCP 双服务的 Docker 部署说明

本文以 [最终演示基线](../demo-baseline.json) 为准：前端 → Agent（`real,mcp`、语义 V1、`agent.semantic.mode=active`）→ 独立 MCP 业务服务 → MySQL/Redis。模型配置、用户认证、服务认证和业务事务均须按此链路落实。Compose 默认库为 `report_demo`，原生 JSON Schema 开启，thinking 关闭。

历史实现记录：2026-10-03 已补齐三个 Dockerfile、五项服务 Compose 和 deploy.sh。业务服务先执行迁移，Agent 等待其健康后启动，前端按当前会话认证转发 Authorization。配置与部署逻辑回归、隔离 HTTP MCP 验收通过；该次验收环境没有 Docker Engine，未实际构建和启动容器，生产部署仍待目标环境验收。

Demo 自带账号问题与前端依赖升级按 [AGENTS.md](../AGENTS.md) 暂缓；正式认证系统尚未接入，服务凭据和业务权限校验继续生效。

## Compose 入口

在仓库根目录运行 `./deploy.sh up`。首次运行生成权限为 600 的 .env 和随机基础服务凭据；没有真实模型配置时会停止并提示缺失配置，填写实际 `LLM_API_KEY`、`LLM_MODEL`、`LLM_BASE_URL` 后再执行。脚本不会打印密码或模型密钥。

也可先复制 `.env.example` 为 `.env`，设置真实模型配置、MySQL/Redis 密码、至少 32 位 `BUSINESS_SERVICE_TOKEN` 和至少 12 位 `AUTH_BOOTSTRAP_PASSWORD`，限制文件访问后执行：

```bash
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build
```

默认页面入口为 `http://127.0.0.1:8080`，使用应用登录。`AUTH_BOOTSTRAP_PASSWORD` 只在首次空库创建 admin 时生效，不能通过改环境变量重置已有账号。`./deploy.sh update` 按业务服务、Agent、前端顺序更新并等待健康；数据库与 Redis 不在其重建列表中。同基线重启保留当前运行状态。破坏性结构变更须取得用户明确授权后重建固定的 `report_demo` 库，不能自行另建数据库，不提供旧库升级或校验和修复路径；当前版本故障恢复仍依赖原请求号核对。

## 镜像构建

在仓库根目录执行：

```bash
docker build -f backend/Dockerfile -t report-agent:1.0.0 .
docker build -f business-service/Dockerfile -t report-business:1.0.0 .
docker build -f frontend/Dockerfile -t report-frontend:1.0.0 .
```

业务服务 Dockerfile 从根 Maven 聚合构建两个模块，使用 backend 的共享类型分类 JAR。上述为实际构建文件入口；本次文档修订没有执行 Docker 构建或完整容器启动。

源码包和镜像不得包含 .env、tools/env.local.cmd、.runtime/、模型密钥或数据库/服务凭据。密钥在目标环境由受保护配置或密钥管理设施注入。

## 运行配置

| 配置 | Agent | 业务服务 |
| --- | --- | --- |
| SPRING_PROFILES_ACTIVE | real,mcp | 无需装配模型 profile |
| SERVER_ADDRESS | 容器内显式监听 0.0.0.0 | Dockerfile 已设置 0.0.0.0 |
| 服务端口 | 显式指定 AGENT_PORT=8080 或 SERVER_PORT | 默认 8090，可显式指定 |
| DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD | 访问编排和身份数据 | 当前与 Agent 共用 schema，并访问业务表 |
| REDIS_HOST/REDIS_PORT/REDIS_PASSWORD | 配额、限流及缓存刷新 | 账号限流等共享设施 |
| SPRING_FLYWAY_ENABLED | Compose 设为 false | 负责执行当前 单一 V1 空库初始化，包含字段快照和调查表 |
| AUTH_TENANT_ID | 与业务服务相同 | 当前服务凭据对应租户 |
| AUTH_BOOTSTRAP_PASSWORD | 首次账号初始化需至少 12 位 | 相同初始身份数据配置，不写入镜像 |
| BUSINESS_SERVICE_TOKEN | 至少 32 位服务凭据 | 与 Agent 一致 |
| BUSINESS_MCP_URL | 实际可达的业务服务 base URL | 无需客户端配置 |
| BUSINESS_REMOTE_ALLOW_INSECURE_HTTP | 默认 false；当前 Compose 专网显式设置 true | 不影响 Bearer 服务认证 |
| LLM_BASE_URL/LLM_API_KEY/LLM_MODEL | 真实接口、密钥、验收过的模型 | 不注入模型密钥 |
| SEMANTIC_MODE | active | 不运行语义解析 |
| SEMANTIC_NATIVE_SCHEMA | 最终默认 true；更换端点后须重新验证能力 | 不需要 |
| SEMANTIC_MODEL/SEMANTIC_THINKING_ENABLED | 按真实端点能力和回放结果设置 | 不需要 |
| CORS_ALLOWED_ORIGINS | 同源留空；分域填写准确前端 Origin | 服务 Origin 由独立配置控制 |
| TRUSTED_PROXY_CIDRS | 实际代理 IP/CIDR，供登录限流识别客户端 | 不代替服务认证 |

前端不继承模型、数据库、Redis 或 MCP 服务密钥。数据库迁移权限与日常业务最小权限应明确分配；应用配置中的 root 默认值不代表生产建议。

## 网络、TLS 与代理

BusinessMcpClient 默认只允许回环地址使用 HTTP，其余地址必须为 HTTPS。当前 Compose 将数据库、Redis、业务服务放在 `internal: true` 的 demo-private 网络，不映射其端口，并仅对该内部链路显式开启 `BUSINESS_REMOTE_ALLOW_INSECURE_HTTP=true`。跨主机业务服务保留默认 HTTPS 要求，需要实际证书信任和调用验收。此配置不是公网 HTTP 接入或 TLS 验收的证明。

本机 profile 默认绑定回环地址；容器网络需调整监听地址并限制访问。浏览器只访问前端/API 入口，不持有 BUSINESS_SERVICE_TOKEN。

前端 Nginx 模板已移除冲突的入口认证，原样转发 Authorization Bearer，保留 SSE 的关闭缓冲和长连接配置。页面默认只映射到本机 127.0.0.1；对外服务时由实际 HTTPS 入口转发，并配置实际绑定地址与可信代理范围。

同源代理使用 /api 路径。分域访问配置准确 CORS 来源，真实请求仍要求会话。Agent 只信任 TRUSTED_PROXY_CIDRS 中的真实代理，不能把所有公网客户端配置为可信。

## 启动与健康验证

1. 准备 MySQL、Redis、数据库权限、服务凭据和目标 schema，检查备份。
2. 先启动业务服务，等待 Flyway 迁移并检查 GET /health。
3. 用真实模型配置启动 Agent，开启 real,mcp，关闭 Agent 的重复迁移。
4. 检查 GET /api/health/readiness。它验证数据库、Redis、业务服务数据库、使用服务凭据的 MCP 初始化和工具发现；探测采用独立短超时连接，结果缓存 10 秒，不证明真实模型解析已成功。
5. 启动前端与实际代理，验证 /api/auth/mode 要求登录，无会话访问受保护 API 返回 401。
6. 验证真实登录、自然语言查询、SSE 预览、选择、生成 PENDING 清单的完整链路。
7. 涉及实际派单的执行验收须先明确目标和影响并获得数据操作授权；创建独立验收库还需单独授权。验收时：确认请求通过 POST /api/dispatch/jobs 返回持久任务号，前端读取任务结果；验证稳定任务键重放、页面刷新、原派单请求号、未知结果核对与显式失败重试。排队超过 10 分钟的任务不会启动，运行中断不会自动重新入队。

部分 REST 业务错误使用 HTTP 200 携带非零业务码。监控和验收同时检查 HTTP、业务码和 SSE 事件，不能只看页面可访问或连接成功。

## 真实模型验收

在受保护环境配置 LLM_*，使用选定端点、模型、Schema 和 thinking 参数执行：

```powershell
./tools/test-semantic-live.ps1 -NativeSchema -Corpus all
```

当前统一业务规划固定请求原生 JSON Schema 并关闭 thinking；目标端点必须支持该请求。此语义专项的开关只影响被测 V1 解析器，不能证明统一业务入口可以关闭 Schema 后运行。真实语义回放使用合成语料，不查询业务库、不执行派单；记录实际动作、最终范围、排除项、澄清结果、延迟及修复次数。

运行服务的真实模型双服务链路使用 tools/test-mcp-http.py，配置实际 URL 和受保护的管理员凭据。脚本默认创建验收账号、会话、待确认清单，随后取消清单并禁用验收账号；专用样例断言不能直接用于正式业务环境。

密钥缺失、请求选项不兼容、限流或端点不可用时停止相应验收并如实记录原因。当前 MCP 服务更新本仓库业务表，外部 ERP 接入是独立验收范围。

## 运维与上线门槛

落实镜像版本固定、健康检查、日志、TLS、密钥轮换、数据库备份恢复、告警、容量目标及多实例故障演练。升级前检查在途 EXECUTING/REVIEW_REQUIRED 清单，保留稳定请求号和审计，不能以重启代替核对。

默认不重置已有业务数据；新库首次初始化示例数据不等于已接入正式来源。不能回删 Flyway 迁移来实现业务回滚。数据库 useSSL=false 等现有默认值需按实际跨主机安全要求调整和验证。

双服务编排与配置已实现；真实容器构建、代理登录/TLS 链路、容量和容器升级/恢复仍需实际验收。本文不将源码/配置回归等同于生产环境部署结果。
