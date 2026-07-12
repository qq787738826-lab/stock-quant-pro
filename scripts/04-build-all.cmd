@echo off
setlocal
cd /d "%~dp0.."
if errorlevel 1 (
  echo [ERROR] Cannot enter project root directory.
  pause
  exit /b 1
)

call "%CD%\mvnw.cmd" clean package
if errorlevel 1 (
  echo [ERROR] Maven build failed.
  pause
  exit /b 1
)

call npm --prefix quant-web install
if errorlevel 1 (
  echo [ERROR] quant-web npm install failed.
  pause
  exit /b 1
)

call npm --prefix quant-web run build
if errorlevel 1 (
  echo [ERROR] quant-web build failed.
  pause
  exit /b 1
)

call npm --prefix quant-desktop install
if errorlevel 1 (
  echo [ERROR] quant-desktop npm install failed.
  pause
  exit /b 1
)

call npm --prefix quant-desktop run build
if errorlevel 1 (
  echo [ERROR] quant-desktop build failed.
  pause
  exit /b 1
)

echo [OK] All modules built successfully.
pause
