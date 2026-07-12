@echo off
setlocal
cd /d "%~dp0"

echo [1/6] Setting official npm registry...
call npm config set registry "https://registry.npmjs.org/" --location=user
call npm config delete proxy --location=user >nul 2>&1
call npm config delete https-proxy --location=user >nul 2>&1
call npm config get registry

echo [2/6] Stopping leftover Node/Electron processes...
taskkill /F /IM node.exe /T >nul 2>&1
taskkill /F /IM electron.exe /T >nul 2>&1

echo [3/6] Cleaning quant-web node_modules...
if exist "quant-web\node_modules" rmdir /s /q "quant-web\node_modules"

echo [4/6] Installing quant-web dependencies...
call npm --prefix quant-web ci --no-audit --no-fund --registry=https://registry.npmjs.org/
if errorlevel 1 (
  echo [ERROR] quant-web install failed.
  pause
  exit /b 1
)

echo [5/6] Cleaning quant-desktop node_modules...
if exist "quant-desktop\node_modules" rmdir /s /q "quant-desktop\node_modules"

echo [6/6] Installing quant-desktop dependencies...
call npm --prefix quant-desktop ci --no-audit --no-fund --registry=https://registry.npmjs.org/
if errorlevel 1 (
  echo [ERROR] quant-desktop install failed.
  pause
  exit /b 1
)

echo [OK] Both npm dependency sets installed successfully.
pause
