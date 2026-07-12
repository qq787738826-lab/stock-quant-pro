@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 (
  echo [ERROR] Cannot enter project root directory.
  pause
  exit /b 1
)

if not exist ".env" copy ".env.example" ".env" >nul

for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
  if not "%%A"=="" set "%%A=%%B"
)

if not exist "quant-web\node_modules" (
  call npm --prefix quant-web ci --no-audit --no-fund --registry=https://registry.npmjs.org/
  if errorlevel 1 (
    echo [ERROR] Failed to install quant-web dependencies.
    pause
    exit /b 1
  )
)

if not exist "quant-desktop\node_modules" (
  call npm --prefix quant-desktop ci --no-audit --no-fund --registry=https://registry.npmjs.org/
  if errorlevel 1 (
    echo [ERROR] Failed to install quant-desktop dependencies.
    pause
    exit /b 1
  )
)

start "Quant AI" cmd /k "cd /d ""%CD%\quant-ai"" && call run.cmd"
start "Quant Server" cmd /k "cd /d ""%CD%"" && call ""%CD%\scripts\08-start-server-only.cmd"""
start "Quant Desktop" cmd /k "cd /d ""%CD%\quant-desktop"" && npm run dev"

echo [OK] Startup commands launched. Keep the three new windows open.
pause
