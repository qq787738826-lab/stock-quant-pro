@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 (
  echo [ERROR] Cannot enter project root directory.
  pause
  exit /b 1
)

if not exist ".env" (
  echo [ERROR] .env is missing. Copy .env.example to .env and set DB_PASSWORD.
  pause
  exit /b 1
)

for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
  if not "%%A"=="" set "%%A=%%B"
)

echo [1/2] Installing parent and quant-core into the local Maven repository...
call "%CD%\mvnw.cmd" -pl quant-core -am install -DskipTests
if errorlevel 1 (
  echo [ERROR] quant-core Maven build failed.
  pause
  exit /b 1
)

echo [2/2] Starting Quant Server...
call "%CD%\mvnw.cmd" -pl quant-server spring-boot:run
