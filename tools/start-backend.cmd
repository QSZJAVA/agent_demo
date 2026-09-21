@echo off
REM 启动后端（默认 mock 模型，无需 API Key）。使用真实模型时去掉 --spring.profiles.active=mock 并设置 LLM_API_KEY。
REM 本机数据库 / Redis / 模型 / JDK 的私有配置放在同目录 env.local.cmd（已在 .gitignore 中），示例见 env.local.example.cmd
if exist "%~dp0env.local.cmd" call "%~dp0env.local.cmd"
cd /d "%~dp0..\backend"
call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=mock
