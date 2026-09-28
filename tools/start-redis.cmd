@echo off
REM 本地演示用 Redis（Windows 版 5.0.14.1，来自 github.com/tporadowski/redis）。生产环境使用现有 Redis。
REM 本机私有配置（含 REDIS_PASSWORD）放在同目录 env.local.cmd，会自动加载：
REM 设了 REDIS_PASSWORD 就带密码启动，与本机后端配置保持一致；没设则和不带密码的旧行为完全一致。
if exist "%~dp0env.local.cmd" call "%~dp0env.local.cmd"
cd /d "%~dp0redis-win"
if "%REDIS_PASSWORD%"=="" (
  redis-server.exe --port 6379 --save "" --appendonly no
) else (
  redis-server.exe --port 6379 --requirepass %REDIS_PASSWORD% --save "" --appendonly no
)
