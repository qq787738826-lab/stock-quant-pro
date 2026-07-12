@echo off
setlocal
cd /d "%~dp0..\quant-ai"
if errorlevel 1 (
  echo [ERROR] Cannot enter quant-ai directory.
  pause
  exit /b 1
)

py -3.11 -m venv .venv
if errorlevel 1 (
  echo [ERROR] Failed to create Python 3.11 virtual environment.
  pause
  exit /b 1
)

call ".venv\Scripts\activate.bat"
python -m pip install --upgrade pip
if errorlevel 1 (
  echo [ERROR] Failed to upgrade pip.
  pause
  exit /b 1
)

python -m pip install -r requirements.txt
if errorlevel 1 (
  echo [ERROR] Failed to install Python dependencies.
  pause
  exit /b 1
)

echo [OK] Python environment initialized successfully.
pause
