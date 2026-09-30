@echo off
setlocal
where pwsh.exe >nul 2>&1
if errorlevel 1 (
    echo PowerShell 7.2+ is required. Install it and add pwsh.exe to PATH.
    pause
    exit /b 1
)
pwsh.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-app.ps1"
set "TASK_EXIT=%ERRORLEVEL%"
pause
exit /b %TASK_EXIT%
