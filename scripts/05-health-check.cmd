@echo off
echo === Quant Server ===
curl http://localhost:8080/api/health
echo.
echo.
echo === Quant AI ===
curl http://localhost:8001/health
echo.
pause
