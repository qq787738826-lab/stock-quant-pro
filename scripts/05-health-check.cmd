@echo off
echo === Quant Server ===
curl http://127.0.0.1:8080/api/health
echo.
echo.
echo === Quant AI ===
curl http://127.0.0.1:8001/health
echo.
pause
