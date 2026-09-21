# 项目长期记忆

## 项目性质
报表派单 Agent Demo。前端 Vue 2.7 + Element UI + Vite；后端 Spring Boot 3.5 + MyBatis-Plus + Spring AI（OpenAI 兼容）+ Aviator 规则引擎；存储 MySQL 8 + Redis 7。

## 约定
- 所有接口走请求头 `X-User-Id` 模拟登录态（`user1` / `user2` / `admin`），真实系统替换 `PermissionService`。
- 后端配置项全部支持环境变量覆盖：`DB_HOST` / `DB_PORT` / `DB_USERNAME` / `DB_PASSWORD` /
  `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`。
- **`application.yml` 中所有敏感项一律留空默认值（`${VAR:}`），严禁写入真实凭据。**
  本机真实值放 `tools/env.local.cmd`（已 gitignore），服务器放 `.env`。
- 报表表、规则表（`report_sales` / `report_receivable` / `report_expense` / `dispatch_rule` /
  `dispatch_rule_history`）每次后端启动 **DROP 重建**并写示例数据；
  `agent_conversation` / `agent_message` / `dispatch_audit` 用 `IF NOT EXISTS`，历史跨重启保留。
- 前端 API 全部使用相对路径 `/api`，无硬编码后端地址 —— 部署时用反向代理即可，不需要运行时配置注入。
- 默认 mock 模型（`--spring.profiles.active=mock`，无需 API Key）；真实模型推荐走 OpenAI 兼容端点。

## 仓库信息
- 远程：`origin` = https://github.com/QSZJAVA/agent_demo.git，主分支 `main`。
- 已装提交前密钥扫描钩子：`tools/pre-commit-hook.sh` → `.git/hooks/pre-commit`。
  改了它记得重新 `cp` 到 `.git/hooks/` 并保持 LF 换行。

## 部署方式
- Docker Compose 一键部署（见 `docs/Docker部署指南.md`）：`./deploy.sh up`。
- 仅 frontend 容器对外暴露端口，backend / MySQL / Redis 均在内网。
- 改代码后更新用 `./deploy.sh update`（只重建前后端，数据库不动）。

## Windows 环境注意事项
- 本机含中文的 shell 脚本切勿做编码转换，文件工具写入的即 UTF-8，转码会造成不可逆乱码。
- PowerShell 工具输出有时不回显，可将命令结果重定向到文件后用 Read 读取。
- bash 工具在本机受限（`ls` / `cp` / `mkdir` / `dirname` / `rm` 等命令不可用，`cd` 到盘符路径失败），
  文件操作优先用 Read/Write/Glob/Grep；调 git 建议用 Python subprocess，不要依赖 shell 脚本输出。
- `wsl.exe` 被安全策略拦截，会拉起 wsl 的命令一律失败。
