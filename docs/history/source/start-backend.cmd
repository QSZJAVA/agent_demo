@echo off
setlocal
REM ============================================================
REM Start the backend. Model switch, highest priority first:
REM   1) command line argument: start-backend.cmd mock | start-backend.cmd real
REM   2) environment variable LLM_MODE (set it in tools\env.local.cmd;
REM      "real" also requires LLM_API_KEY)
REM   3) neither given: defaults to mock - keyword model, no API Key needed
REM Local MySQL / Redis / model / JDK settings live in tools\env.local.cmd
REM (gitignored), template at tools\env.local.example.cmd
REM
REM KEEP THIS FILE PURE ASCII - comments included.
REM cmd parses a batch file with the console code page. A UTF-8 Chinese
REM comment byte sequence can be misread under the default GBK (936)
REM console, which splits the line and executes the remainder as a
REM command. chcp 65001 does NOT fix this: cmd pre-reads the file in
REM blocks, so switching code page mid-file only causes byte misalignment.
REM ============================================================

if exist "%~dp0env.local.cmd" call "%~dp0env.local.cmd"

REM Command line argument wins over the setting in env.local.cmd
if "%~1"=="" goto :resolve
if /i "%~1"=="mock" goto :arg_mock
if /i "%~1"=="real" goto :arg_real
echo [start-backend] ERROR: unknown argument "%~1"
echo [start-backend] Usage: start-backend.cmd [mock^|real]
exit /b 1

:arg_mock
set "LLM_MODE=mock"
goto :resolve

:arg_real
set "LLM_MODE=real"
goto :resolve

:resolve
if not defined LLM_MODE set "LLM_MODE=mock"
set "PROFILE_ARGS="

if /i "%LLM_MODE%"=="mock" goto :mode_mock
if /i "%LLM_MODE%"=="real" goto :mode_real

echo [start-backend] ERROR: LLM_MODE must be "mock" or "real", got "%LLM_MODE%"
echo [start-backend] Set LLM_MODE in tools\env.local.cmd, or pass: start-backend.cmd [mock^|real]
exit /b 1

:mode_mock
echo [start-backend] model mode = mock - keyword model, no API Key needed
set "PROFILE_ARGS=-Dspring-boot.run.profiles=mock"
goto :run

:mode_real
if not defined LLM_API_KEY (
    echo [start-backend] ERROR: LLM_MODE=real but LLM_API_KEY is not set.
    echo [start-backend] Configure LLM_API_KEY in tools\env.local.cmd, or run: start-backend.cmd mock
    exit /b 1
)
echo [start-backend] model mode = real - %LLM_MODEL% @ %LLM_BASE_URL%
goto :run

:run
cd /d "%~dp0..\backend"
call mvnw.cmd spring-boot:run %PROFILE_ARGS%
