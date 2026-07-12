@echo off
setlocal
set "PGUSER="
set /p "PGUSER=PostgreSQL user [postgres]: "
if not defined PGUSER set "PGUSER=postgres"

echo You will now be asked for the PostgreSQL password.
psql -U "%PGUSER%" -h localhost -p 5432 -f "%~dp0..\database\00_create_database.sql"
if errorlevel 1 (
  echo [ERROR] Database initialization failed.
  echo If stock_quant already exists, that message can be ignored.
  pause
  exit /b 1
)

echo [OK] Database initialization completed.
pause
