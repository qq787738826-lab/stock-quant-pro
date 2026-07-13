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

## 升级到 1.2.2

保留旧项目的 `.env`，用 1.2.2 完整目录替换代码后重新启动 Java。Flyway 会依次确认 `V2__market_data_center.sql`，
并自动执行 `V3__selection_validation.sql`，无需手工建表。

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


## 1.2.2 日常操作

1. 每个交易日收盘后，在“数据管理”执行“更新最新行情”。
2. 行情更新完成后执行全市场扫描，扫描数量 `0` 表示全部股票。
3. “智能选股”默认展示最近一次正式全市场扫描。
4. 测试扫描可从任务下拉框查看，但不会替换正式结果。
5. 对失败股票使用“一键重试”。
6. 对候选前20名执行批量历史回测，检查规则的历史稳定性。
