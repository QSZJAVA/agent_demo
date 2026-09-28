# 报表派单 Agent Demo

在原"三张报表查询 Demo"基础上，按 `docs/派单Agent技术选型.md` 实现的自然语言派单助手：
用户在对话框里说"查一下我有哪些可以派单"，助手按每张报表当前生效的规则找出该用户可见公司范围内应派单的记录并以表格预览；
再说"我不想派 SO2026002，剩下的帮我派单吧"，助手生成待确认清单，用户点击确认后调用派单接口逐条派单。

- 前端：Vue 2.7 + Element UI + Vite（聊天抽屉、历史会话、规则管理页）
- 后端：Spring Boot 3.5 + MyBatis-Plus 3.5 + Spring AI 1.1（OpenAI 兼容接入千问 / DeepSeek）+ Aviator 规则引擎
- 存储：MySQL 8（报表、规则、审计、对话日志）+ Redis（预览快照、待确认清单、幂等锁、工作记忆、规则刷新广播）

## 目录结构

```
demo
├── backend                 Spring Boot 后端（mvnw 已内置，无需单独安装 Maven）
│   └── src/main/java/com/example/report
│       ├── agent           DispatchTools（两个工具）、ChatClient 装配、SSE 对话服务、对话日志 Advisor
│       ├── rule            Aviator 规则引擎、规则缓存、候选记录查找、规则管理（草稿 / 试算 / 发布 / 回滚）
│       ├── rule/fact       三张报表的事实模型装配器（规则里可用的字段在这里定义）
│       ├── dispatch        预览快照、待确认清单、派单执行、审计、模拟派单接口
│       ├── conversation    对话日志与历史会话
│       ├── memory          Redis 工作记忆仓库（过期后从对话日志回灌）
│       ├── permission      模拟权限接口（user1 看 A，user2 看 B，admin 看 A/B/C 且可改规则）
│       ├── mock            关键词模拟模型（mock profile，无需 API Key 即可演示）
│       ├── entity / mapper MyBatis-Plus 实体与 Mapper
│       └── web             REST / SSE 控制器
├── frontend                Vue + Element UI 前端
├── docs                    技术选型文档、Docker 部署指南
├── tools                   本地启动脚本与 Windows 版 Redis
├── docker-compose.yml      Docker 一键编排（MySQL + Redis + 后端 + 前端）
├── deploy.sh               Linux 一键部署脚本
└── .env.example            部署配置模板（复制为 .env 后修改）
```

## Docker 部署（Linux 服务器）

不想手工装 MySQL / Redis / JDK / Node，用 Docker 一条命令拉起全套：

```bash
chmod +x deploy.sh && ./deploy.sh up
```

脚本自动生成随机数据库密码与网页访问口令（整站 Basic Auth，演示身份只靠请求头，不能裸露在公网）、构建镜像、等 MySQL/Redis 健康后启动后端，最后打印访问地址。
默认使用 mock 模拟模型（无需 API Key），接真实大模型改 `.env` 后执行 `./deploy.sh start-real`。
完整说明见 [`docs/Docker部署指南.md`](docs/Docker部署指南.md)。

## 本地启动步骤

### 1. MySQL

保证 MySQL 8 已启动。数据库 `report_demo` 会自动创建；报表表与规则表每次启动重建并写入示例数据，
会话、消息、审计表用 `IF NOT EXISTS` 创建，历史记录跨重启保留。

账号密码默认 `root` / `123456`，不同时通过环境变量覆盖：`DB_PASSWORD`、`DB_USERNAME`、`DB_HOST`、`DB_PORT`。
Windows 下可把私有配置写在 `tools/env.local.cmd`（不入库，示例见 `tools/env.local.example.cmd`），`tools/start-backend.cmd` 会自动加载。

### 2. Redis

已有 Redis 直接用（默认 `localhost:6379`，可用 `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` 覆盖）。
本机没有 Redis 时，Windows 下双击 `tools/start-redis.cmd` 启动一个演示用实例。

### 3. 后端

```bash
cd backend
# 方式一：本地演示，不需要大模型 API Key，用关键词模拟模型驱动同一套工具与流程
mvnw spring-boot:run -Dspring-boot.run.profiles=mock
# 方式二：接真实模型（阿里云百炼，千问 / DeepSeek 共用；或 DeepSeek 官方 API）
set LLM_API_KEY=sk-xxx
set LLM_MODEL=qwen3.7-plus        # 或 deepseek-flash / deepseek-v4-pro
mvnw spring-boot:run
```

Windows 下也可以直接运行 `tools/start-backend.cmd`（mock 模式）。服务地址 http://localhost:8080 。

模型接入配置在 `backend/src/main/resources/application.yml` 的 `spring.ai.openai` 段：
`LLM_BASE_URL` 默认指向百炼兼容端点（新版业务空间地址以控制台为准），直连 DeepSeek 官方时改为 `https://api.deepseek.com`，
并把 `extra-body` 里的 `enable_thinking: false` 换成 DeepSeek 文档要求的关闭思考模式参数。Agent 路径关闭思考模式，温度 0.1。

### 4. 前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://127.0.0.1:5173 。右上角可切换模拟用户（用户1 / 用户2 / 管理员），点击"派单助手"打开对话抽屉，左侧菜单"派单规则"进入规则管理页。

## 使用流程

1. 以"用户1"身份打开派单助手，输入"查一下我有哪些可以派单"。
   助手调用 `previewDispatchable`，只在 A 公司范围内按规则求值，表格展示 7 条候选（销售 3、应收 2、费用 2）。
2. 输入"我不想派 SO2026002，剩下的帮我派单吧"，或直接在表格里取消勾选再说"剩下的帮我派单"。
   助手调用 `dispatch`，服务端校验排除项在快照内、规则版本未变，生成待确认清单卡片。
3. 点击卡片上的"确认派单"。执行走 REST 接口，不经过模型：幂等锁 → 逐条调用派单接口 → 写审计 → 结果卡片，报表页状态变为"已派单"。
4. 左侧"历史会话"可回看每一轮对话与卡片，并在历史会话里继续对话（Redis 工作记忆过期后自动从对话日志回灌）。
5. 切换到"管理员"，在"派单规则"页新建草稿、校验表达式、试算命中、发布或回滚。发布后规则缓存立即刷新，之前的预览快照不能再执行。

## 主要接口

| 接口 | 说明 |
| --- | --- |
| `GET /api/report/{sales,receivable,expense}` | 报表查询，按当前用户可见公司过滤 |
| `POST /api/agent/chat` | 对话（SSE）：事件 `conversation` / `text` / `preview` / `plan` / `result` / `error` / `done` |
| `GET /api/agent/previews/{previewId}` | 预览快照全量记录 |
| `GET/POST/PUT/DELETE /api/agent/conversations` | 会话列表、历史消息、改名、软删除 |
| `POST /api/dispatch/plans/{planId}/execute` | 确认执行待确认清单 |
| `POST /api/dispatch/direct` | 报表页手工派单 |
| `GET /api/rules`、`POST /api/rules/drafts`、`POST /api/rules/dry-run`、`POST /api/rules/{id}/publish` … | 规则管理（修改类接口仅管理员） |

所有接口通过请求头 `X-User-Id` 模拟登录态（`user1` / `user2` / `admin`），真实系统替换 `PermissionService` 即可。

## 测试

P2 管理能力入口：管理员菜单“报表目录”和“运营治理”。包含目录维护、版本回滚、解析回归、业务指标、人工异常处理、留存策略和访问审计。实现与验证边界见 [P2 实施与自测评审](docs/派单Agent_P2实施与自测评审_2026-09-28.md)。

Windows 隔离数据库完整回归：`./tools/test-p2.ps1`（需要 MySQL、Redis，读取本地私有连接配置；自动创建和删除 UUID 测试库，不重置现有演示库）。

```bash
cd backend
mvnw test                               # 规则引擎单元测试
set DEMO_IT=true && mvnw test           # 加上端到端集成测试（需要本地 MySQL 与 Redis，使用 mock 模型）
```

集成测试覆盖：预览只含可见公司、排除不存在的单据号被拒绝、待确认清单归属校验、执行后状态变更、规则发布后旧快照被拒绝、SSE 事件序列、历史记录与跨用户 404。

## 接口返回格式

```json
{
  "code": 0,
  "message": "success",
  "data": [
    { "id": 1, "companyCode": "A", "orderNo": "SO2026001", "productName": "服务器", "amount": 128000.00, "saleDate": "2026-01-15", "dispatchStatus": 0 }
  ]
}
```
