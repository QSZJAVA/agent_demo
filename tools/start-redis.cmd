@echo off
REM 本地演示用 Redis（Windows 版 5.0.14.1，来自 github.com/tporadowski/redis）。生产环境使用现有 Redis。
cd /d "%~dp0redis-win"
redis-server.exe --port 6379 --save "" --appendonly no
