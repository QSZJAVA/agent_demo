@echo off
REM Copy to env.local.cmd. That private file is ignored by Git.
REM start-app.ps1 reads SET assignments as data; do not use CMD expansions.
REM Existing process environment variables take precedence.
REM JDK 17+; leave empty to use java.exe on PATH.
set "JAVA_HOME="

set "DB_HOST=localhost"
set "DB_PORT=3306"
set "DB_NAME=report_demo"
set "DB_USERNAME=root"
set "DB_PASSWORD="
set "REDIS_HOST=localhost"
set "REDIS_PORT=6379"
set "REDIS_PASSWORD="

REM Configure your actual OpenAI-compatible model endpoint and credential.
REM Alternatively use process environment variables or ignored
REM .runtime/llm-credentials.json with apiKey, baseUrl and model fields.
set "LLM_BASE_URL="
set "LLM_MODEL="
set "LLM_API_KEY="
REM Enable only after verifying that the endpoint supports JSON Schema.
set "SEMANTIC_NATIVE_SCHEMA=true"
set "SEMANTIC_MODEL="
set "SEMANTIC_THINKING_ENABLED=false"
REM Investigation has its own bounded read-only tool loop; empty model follows LLM_MODEL.
set "INVESTIGATION_MODEL="
set "INVESTIGATION_NATIVE_SCHEMA=false"
set "INVESTIGATION_THINKING_ENABLED=false"
set "CORS_ALLOWED_ORIGINS="
set "TRUSTED_PROXY_CIDRS="
