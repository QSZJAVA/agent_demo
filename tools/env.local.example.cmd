@echo off
REM ============================================================
REM 本机私有配置模板：复制为 env.local.cmd 后按实际情况修改
REM env.local.cmd 已在 .gitignore 中，不会被提交
REM start-backend.cmd / start-frontend.cmd 会自动加载它
REM ============================================================

REM 后端需要 JDK 17+；本机默认 JAVA_HOME 指向低版本时在这里覆盖
set "JAVA_HOME=C:\Program Files\Java\jdk-21"

REM ---------- MySQL ----------
set DB_HOST=localhost
set DB_PORT=3306
set DB_USERNAME=root
set DB_PASSWORD=你的MySQL密码

REM ---------- Redis ----------
set REDIS_HOST=localhost
set REDIS_PORT=6379
REM 本机 Redis 未设密码时留空
set REDIS_PASSWORD=

REM ---------- 大模型（不配置则用 mock 模拟模型，无需 Key）----------
REM 阿里云百炼
REM set LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
REM set LLM_API_KEY=sk-你的Key
REM set LLM_MODEL=qwen3.7-plus
REM DeepSeek 官方
REM set LLM_BASE_URL=https://api.deepseek.com
REM set LLM_API_KEY=sk-你的Key
REM set LLM_MODEL=deepseek-chat
