# 运行与排错

## 启动顺序

1. PostgreSQL 服务。
2. Python：
   `cd D:\stock-quant-pro\quant-ai`
   `.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001`
3. IDEA 运行 `QuantServerApplication`。
4. 前端：
   `cd D:\stock-quant-pro\quant-desktop`
   `npm run dev`

## 健康检查

- `http://127.0.0.1:8001/health`
- `http://127.0.0.1:8080/api/health`
- `http://127.0.0.1:5173`

## 升级到 1.2.0

保留旧项目的 `.env`，用 1.2.0 完整目录替换代码后重新启动 Java。Flyway 会自动执行 `V2__market_data_center.sql`，无需手工建表。

## 常见问题

- `已有全市场扫描任务正在运行`：等待当前任务完成。
- 扫描失败较多：检查 Python 服务和外部网络，先用 20–200 只做测试。
- 第一次扫描较慢：正常，后续会读取 PostgreSQL 和本地缓存。
- 数据管理显示 0 只股票：先点击“同步股票列表”。


## 数据管理中心出现 Network Error

依次检查：

- `http://127.0.0.1:8080/api/health`
- `http://127.0.0.1:8080/api/data/overview`
- `http://127.0.0.1:8001/health`

如果第一个地址打不开，说明 Java 后端尚未启动。
如果第一个正常、第二个返回 404，说明 IDEA 运行的仍是旧版本，需要重新导入 Maven 并重启。
