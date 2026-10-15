@echo off
REM ============================================================
REM  Agent Nexus - one-click launcher
REM  Starts the Spring Boot backend (:8888) in its own window,
REM  waits until the port actually accepts connections, then
REM  starts the Tauri desktop app (Vite dev server on :1420).
REM
REM  NOTE: this file is deliberately ASCII-only. .bat files are
REM  read using the console OEM codepage (GBK on zh-CN Windows),
REM  so UTF-8 Chinese here would show up as mojibake.
REM ============================================================
setlocal
set ROOT=%~dp0
set PORT=8888

echo [1/3] Starting backend (Spring Boot, port %PORT%) ...
start "Agent Nexus backend" cmd /k "cd /d "%ROOT%" && mvnw.cmd spring-boot:run"

echo [2/3] Waiting for the backend to accept connections ...
set /a tries=0
:waitloop
set /a tries+=1
powershell -NoProfile -Command "try{$c=New-Object Net.Sockets.TcpClient;$c.Connect('127.0.0.1',%PORT%);$c.Close();exit 0}catch{exit 1}" >nul 2>&1
if %errorlevel%==0 goto ready
if %tries% GEQ 60 (
  echo    WARNING: backend still not listening after ~120s.
  echo    Starting the desktop app anyway - check the backend window for errors.
  goto ready
)
timeout /t 2 /nobreak >nul
goto waitloop

:ready
echo    Backend is up.
echo [3/3] Starting desktop app (Tauri, Vite dev server on 1420) ...
cd /d "%ROOT%desktop-app"
call npm run tauri dev

endlocal
