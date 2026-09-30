# 真实模型 MCP 双服务的 Docker 部署说明

本文只描述真实模型场景：前端 → Agent（`real,mcp`、语义 V2）→ 独立 MCP 业务服务 → MySQL/Redis。模型配置、用户认证、服务认证和业务事务均须按此链路落实。

**当前仓库已有三个 Dockerfile，但尚未提供经过验收的双服务 Compose 编排。** 根目录现有 docker-compose.yml 与 deploy.sh 不覆盖本分支的完整部署，不能把其启动成功表述为真实模型双服务验收。已验证的本机启动方式见 [README](../README.md)，使用 tools/start-mcp.ps1 -Build -Frontend。

## 镜像构建

在仓库根目录执行：

```bash
docker build -f backend/Dockerfile -t report-agent:2.0.0 .
docker build -f business-service/Dockerfile -t report-business:2.0.0 .
docker build -f frontend/Dockerfile -t report-frontend:2.0.0 .
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
| SPRING_FLYWAY_ENABLED | 业务服务迁移后设为 false | 负责执行 V1–V19 迁移 |
| AUTH_TENANT_ID | 与业务服务相同 | 当前服务凭据对应租户 |
| AUTH_BOOTSTRAP_PASSWORD | 首次账号初始化需至少 12 位 | 相同初始身份数据配置，不写入镜像 |
| BUSINESS_SERVICE_TOKEN | 至少 32 位服务凭据 | 与 Agent 一致 |
| BUSINESS_MCP_URL | 实际可达的业务服务 base URL | 无需客户端配置 |
| LLM_BASE_URL/LLM_API_KEY/LLM_MODEL | 真实接口、密钥、验收过的模型 | 不注入模型密钥 |
| SEMANTIC_MODE | active | 不运行语义解析 |
| SEMANTIC_NATIVE_SCHEMA | 选定端点支持并验证后开启 | 不需要 |
| SEMANTIC_MODEL/SEMANTIC_THINKING_ENABLED | 按真实端点能力和回放结果设置 | 不需要 |
| DEMO_RESET_ON_STARTUP | false，保留已有数据 | false，保留已有数据 |
| CORS_ALLOWED_ORIGINS | 同源留空；分域填写准确前端 Origin | 服务 Origin 由独立配置控制 |
| TRUSTED_PROXY_CIDRS | 实际代理 IP/CIDR，供登录限流识别客户端 | 不代替服务认证 |

前端不继承模型、数据库、Redis 或 MCP 服务密钥。数据库迁移权限与日常业务最小权限应明确分配；应用配置中的 root 默认值不代表生产建议。

## 网络、TLS 与代理

BusinessMcpClient 只允许回环地址使用 HTTP，其余业务服务 URL 必须为 HTTPS。因此容器之间不能直接照抄 http://business:8090 作为有效生产连接。部署方需提供容器可达的 HTTPS 业务入口、代理和证书信任配置，再用实际调用验证。

本机 profile 默认绑定回环地址；容器网络需调整监听地址并限制访问。浏览器只访问前端/API 入口，不持有 BUSINESS_SERVICE_TOKEN。

现有前端 Nginx 模板包含独立入口认证。真实账号模式使用 Authorization Bearer，与同一入口直接要求另一种 Authorization 认证的方案会冲突；必须审查实际代理，不能宣称现有模板无需调整即可用于真实登录双服务。保留 SSE 的关闭缓冲和长连接配置，并正确转发 Authorization。

同源代理使用 /api 路径。分域访问配置准确 CORS 来源，真实请求仍要求会话。Agent 只信任 TRUSTED_PROXY_CIDRS 中的真实代理，不能把所有公网客户端配置为可信。

## 启动与健康验证

1. 准备 MySQL、Redis、数据库权限、服务凭据和目标 schema，检查备份。
2. 先启动业务服务，等待 Flyway 迁移并检查 GET /health。
3. 用真实模型配置启动 Agent，开启 real,mcp，关闭 Agent 的重复迁移。
4. 检查 GET /api/health/readiness。它验证数据库、Redis、业务服务连通性，不证明真实模型解析已成功。
5. 启动前端与实际代理，验证 /api/auth/mode 要求登录，无会话访问受保护 API 返回 401。
6. 验证真实登录、自然语言查询、SSE 预览、选择、生成 PENDING 清单的完整链路。
7. 正式执行验收仅在专用验收库进行，验证结果、重复确认、原请求号查询及异常核对。

部分 REST 业务错误使用 HTTP 200 携带非零业务码。监控和验收同时检查 HTTP、业务码和 SSE 事件，不能只看页面可访问或连接成功。

## 真实模型验收

在受保护环境配置 LLM_*，使用选定端点、模型、Schema 和 thinking 参数执行：

```powershell
./tools/test-semantic-live.ps1 -NativeSchema -Corpus all
```

端点不支持原生 Schema 时不加 -NativeSchema，并让应用配置一致。真实语义回放使用合成语料，不查询业务库、不执行派单；记录实际动作、最终范围、排除项、澄清结果、延迟及修复次数。

运行服务的真实模型双服务链路使用 tools/test-mcp-http.py，配置实际 URL 和受保护的管理员凭据。脚本默认创建验收账号、会话、待确认清单，随后取消清单并禁用验收账号；专用样例断言不能直接用于正式业务环境。

密钥缺失、请求选项不兼容、限流或端点不可用时停止相应验收并如实记录原因。当前 MCP 服务更新本仓库业务表，外部 ERP 接入是独立验收范围。

## 运维与上线门槛

落实镜像版本固定、健康检查、日志、TLS、密钥轮换、数据库备份恢复、告警、容量目标及多实例故障演练。升级前检查在途 EXECUTING/REVIEW_REQUIRED 清单，保留稳定请求号和审计，不能以重启代替核对。

默认不重置已有业务数据；新库首次初始化示例数据不等于已接入正式来源。不能回删 Flyway 迁移来实现业务回滚。数据库 useSSL=false 等现有默认值需按实际跨主机安全要求调整和验证。

双服务 Compose、真实容器构建、代理登录/TLS 链路以及容器升级/恢复仍需实际验收。本文不为未实现或未验证的能力编造一键部署结果。
