# Docker 部署指南

适用于当前仓库的 Linux 演示部署：MySQL 8、Redis 7、Spring Boot 后端和 Nginx 前端。浏览器访问前端端口，`/api` 由 Nginx 反向代理，SSE 已关闭代理缓冲。

**真实认证和真实业务派单网关尚未接入，属于下一阶段。** 整站 Basic Auth 保护演示入口；页面的用户切换仍是请求头模拟身份，接真实大模型也仍使用演示派单网关。

## 1. 环境准备

需要受支持的 Linux、Docker Engine 和 Docker Compose V2，能下载 Maven/npm 依赖及基础镜像。建议至少 4 GB 内存，并为数据库增长预留磁盘。小内存机器可以调整 `.env` 的 `JAVA_OPTS`，实际容量需验证。

按 [Docker 官方安装说明](https://docs.docker.com/engine/install/) 安装，确认 `docker info`、`docker compose version` 可用。仓库只配置 HTTP；跨不可信网络访问时，应在入口反向代理或负载均衡层配置 HTTPS。

## 2. 打包上传

`tar` 不会自动读取 `.gitignore` 或 `.dockerignore`。以下 Bash 命令在项目根目录按明确文件清单打包，将输出放到项目外，保留当前未提交的源码修改；不包含 `.env`、`tools/env.local.cmd`、Windows Redis、依赖目录及构建产物：

```bash
tar -czf ../demo-source.tar.gz \
  --exclude='*.exe' --exclude='*.dll' --exclude='*.jar' \
  --exclude='*.class' --exclude='*.log' --exclude='*.tmp' \
  .dockerignore .env.example docker-compose.yml deploy.sh \
  backend/Dockerfile backend/pom.xml backend/mvnw backend/mvnw.cmd \
  backend/.mvn backend/src \
  frontend/Dockerfile frontend/index.html frontend/nginx.conf.template \
  frontend/package.json frontend/package-lock.json frontend/vite.config.mjs \
  frontend/src frontend/tests

tar -tzf ../demo-source.tar.gz
scp ../demo-source.tar.gz <部署用户>@<服务器IP>:/opt/
```

服务器上准备有写权限的目录并解压：

```bash
mkdir -p /opt/demo
tar -xzf /opt/demo-source.tar.gz -C /opt/demo
cd /opt/demo
```

也可以从已提交对应改动的 Git 分支克隆。配置密钥在目标服务器创建，不随源码包传输。覆盖已有部署前先备份数据库及 `.env`。

## 3. 配置与启动

```bash
chmod +x deploy.sh
./deploy.sh up
```

首次运行自动从 `.env.example` 生成随机 MySQL/Redis 密码及网页访问口令；已有 `.env` 会沿用。生成的口令会打印到终端，请妥善保存 `.env`，避免将部署日志公开。

| `.env` 配置 | 作用 |
| --- | --- |
| `HTTP_PORT`、`SERVER_NAME` | 前端对外端口与访问主机名，默认端口 80 |
| `BASIC_AUTH_USER`、`BASIC_AUTH_PASSWORD` | 整站演示访问口令；不提供业务用户身份隔离 |
| `MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD` | 容器依赖服务密码 |
| `DB_NAME` | MySQL 初始化库名和后端连接库名，默认 `report_demo` |
| `DEMO_RESET_ON_STARTUP` | 默认 `false`；仅显式 `true` 时每次启动重置演示报表、目录与规则 |
| `SPRING_ARGS` | 默认 `--spring.profiles.active=mock`；真实模型模式留空 |
| `LLM_BASE_URL`、`LLM_API_KEY`、`LLM_MODEL` | 模型端点、密钥、模型名 |
| `SEMANTIC_MODE` | 当前默认 `legacy`；验证新语义链路设为 `active`，通过真实模型回放后再切换 |
| `SEMANTIC_NATIVE_SCHEMA` | 原生 JSON Schema 输出开关，需按供应商实际能力设置；服务端协议校验始终启用 |
| `SEMANTIC_MODEL`、`SEMANTIC_THINKING_ENABLED` | 语义模型独立覆盖（默认空）与推理开关（默认 false），需用回放验证供应商行为 |
| `JAVA_OPTS` | JVM 参数与内存上限 |

启动顺序和健康判定：

1. MySQL、Redis 健康后启动后端。
2. 后端 `GET /api/health/readiness` 实际查询 MySQL 并检查 Redis；全部正常返回 HTTP 200，失败返回 503，不要求 `X-User-Id`。
3. 前端在后端健康后启动，自身通过 `/healthz` 检查 Nginx。
4. 部署脚本等待全部服务健康再输出成功；依赖失败、容器异常或等待超时会以非零状态退出。默认等待上限 240 秒，可用 shell 环境变量 `DEPLOY_HEALTH_TIMEOUT_SECONDS` 覆盖（不是 `.env` 配置）。

访问 `http://<服务器IP>:<HTTP_PORT>/`，输入 Basic Auth 口令后选择演示用户。只发布前端端口；MySQL、Redis 和后端未映射宿主机端口。安全组按实际入口端口开放。

readiness 只证明当前 MySQL/Redis 依赖可用，不代表真实模型端点或未来外部派单服务已联通。普通业务接口有 HTTP 200 携带非零业务码的兼容行为，不能用它们代替 readiness。

## 4. 数据初始化与保留

表结构由 Flyway 的版本迁移维护，当前为 V1–V18。V18 新增语义会话状态和逐轮证据表。**默认 `DEMO_RESET_ON_STARTUP=false`：新空库第一次初始化示例数据，已有数据库启动保留数据与管理配置。** 不使用启动时反复执行的 `schema.sql`/`data.sql` 机制。

显式改为 `true` 会在每次启动重置演示报表、目录、派单规则及规则历史，对相关表不按租户限制；既有预览和待确认清单按演示重置逻辑失效。仅在可清空的演示库使用，用完恢复 `false` 并重建后端容器使配置生效。真实业务环境不得开启。

| 数据 | 默认位置 | 删除容器后 |
| --- | --- | --- |
| MySQL：报表、目录、规则、预览/计划/任务/去重请求、租约、会话、追溯与运营数据 | Docker 卷 `report-demo_mysql-data` | 数据卷保留时仍在 |
| Redis：模型工作记忆、缓存刷新广播、分钟频率限制；配置 AOF | Docker 卷 `report-demo_redis-data` | 数据卷保留时仍在，带 TTL 的内容仍会过期 |

`down` 保留数据卷；`destroy` 会删除数据卷。MySQL 镜像的 `MYSQL_DATABASE`、初始密码仅在空数据目录初始化时使用；修改 `DB_NAME` 不是迁移旧库，修改 `.env` 密码也不会自动修改既有 MySQL 账号密码。

运营治理另有按租户配置的留存策略：默认会话/结果/审计 365 天、指标 90 天。清理分批执行，在途、未知结果或未处理失败有保留条件；永久去重标识和必要终态不会随显示明细一起清除。

## 5. 模型切换

默认 mock 不请求外部模型；active 使用固定语义样本，未收录说法返回澄清。接真实模型前填写供应商实际支持的组合，例如编辑 `.env` 中的端点、API Key 和模型名，然后执行：

```bash
./deploy.sh start-real
```

模型切换会重建后端容器，并重启前端使 Nginx 重新解析后端地址，然后等待健康。返回 mock：

```bash
./deploy.sh start-mock
```

当前 Compose 默认模型 `qwen3.7-plus`，本地 `application.yml` 默认 `deepseek-v4.1-flash`，默认端点均为 `https://dashscope.aliyuncs.com/compatible-mode`。URL 不附加 `/v1`，配置的请求路径已包含它。

当前请求体 `extra-body` 是 `thinking: {type: disabled}`；active 解析温度为 `0`，legacy 温度为 `0.1`。扩展参数位于 `backend/src/main/resources/application.yml`，不是 `.env` 的独立字段。切换供应商需核对模型名、Schema 和关闭思考参数，必要时改配置、执行 `./deploy.sh update` 重建后端；不能只凭“OpenAI 兼容”认定行为相同。

语义路径切换使用 `SEMANTIC_MODE=active|legacy`，修改后重建后端容器。回滚业务入口时保留 V18 表结构与已有状态，不回删数据库迁移。原生 Schema 开关是模型能力配置，不替代权限校验或多轮语料验收；可在本地用 `tools/test-semantic-live.ps1 -NativeSchema` 单独测试解析，不会执行派单。

## 6. 运维命令与备份

| 命令 | 用途 |
| --- | --- |
| `./deploy.sh up` | 构建、启动并等待健康 |
| `./deploy.sh ps` | 容器与健康状态 |
| `./deploy.sh logs` | 跟踪全部日志；Ctrl+C 停止跟踪 |
| `docker logs -f report-demo-backend` | 后端日志 |
| `./deploy.sh restart` | 重启现有容器并等待健康；修改环境变量后应重建容器 |
| `./deploy.sh update` | 重新构建并强制重建前后端容器，等待健康 |
| `./deploy.sh rebuild` | 禁用构建缓存后重新构建 |
| `./deploy.sh down` | 停止并删除容器，保留数据卷 |
| `./deploy.sh destroy` | 输入确认后删除容器和数据卷，数据不可依赖容器恢复 |

备份当前配置的 MySQL 数据库：

```bash
docker exec report-demo-mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --triggers "$MYSQL_DATABASE"' \
  > "backup-$(date +%F-%H%M%S).sql"
```

确认命令成功且备份可恢复后再执行升级或清理。恢复演练使用隔离数据库，勿直接覆盖运行中的演示库。生产接入阶段还需最小权限业务账号、备份保留/恢复目标及密钥轮换方案。

## 7. 故障定位

| 现象 | 检查方式 |
| --- | --- |
| 依赖下载失败 | 检查镜像源、Maven/npm 网络和代理；使用组织认可的源 |
| 后端 `unhealthy` | 查看后端日志和 MySQL/Redis 健康状态，检查连接参数、密码、库名、迁移失败或内存不足 |
| 页面可用但对话失败 | 区分 mock/真实模型，检查服务端业务码、密钥、端点、模型名与扩展参数 |
| SSE 停顿或断开 | 检查入口 SLB/CDN 的缓冲和超时；通过会话/任务查询恢复结果 |
| 改 `.env` 后未生效 | `restart` 不会重新注入环境变量；执行 `up` 或对应的模型切换命令重建容器 |
| 升级后数据“消失” | 核对 `DB_NAME`、数据卷及 `DEMO_RESET_ON_STARTUP`；先保留现场和备份，勿直接 `destroy` |

当前功能与接口以 [README](../README.md) 为入口；真实认证、外部派单和生产验收工作见 [下一阶段待办](派单Agent_Demo到生产级待办.md)。
