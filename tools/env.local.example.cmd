@echo off
REM ============================================================
REM 本机私有配置模板：复制为 env.local.cmd 后按实际情况修改
REM env.local.cmd 已在 .gitignore 中，不会被提交
REM start-backend.cmd 会自动加载它（start-frontend.cmd 不会，前端也不需要）
REM ============================================================

REM 后端需要 JDK 17+；本机默认 JAVA_HOME 指向低版本时在这里覆盖
set "JAVA_HOME=C:\Program Files\Java\jdk-21"

REM ---------- MySQL ----------
set DB_HOST=localhost
set DB_PORT=3306
set DB_NAME=report_demo
set DB_USERNAME=root
set DB_PASSWORD=你的MySQL密码
REM 默认重启保留数据；首次空库自动初始化。仅需清空并恢复演示数据时临时设 true。
set DEMO_RESET_ON_STARTUP=false

REM ---------- Redis ----------
set REDIS_HOST=localhost
set REDIS_PORT=6379
REM 本机 Redis 未设密码时留空
set REDIS_PASSWORD=

REM ---------- 模型开关 ----------
REM mock = 领域语法及固定语义样本，无需 API Key；real = 领域语法未覆盖时调用下面配置的真实模型
REM 双击 start-backend.cmd 时生效，也可用参数临时覆盖：
REM   start-backend.cmd mock        仅这一次用 mock
REM   start-backend.cmd real        仅这一次用真实模型
set LLM_MODE=mock

REM ---------- 大模型（LLM_MODE=real 时使用）----------
REM 阿里云百炼
REM set LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
REM set LLM_API_KEY=sk-你的Key
REM set LLM_MODEL=qwen3.7-plus
REM DeepSeek 官方
REM set LLM_BASE_URL=https://api.deepseek.com
REM set LLM_API_KEY=sk-你的Key
REM set LLM_MODEL=deepseek-chat
