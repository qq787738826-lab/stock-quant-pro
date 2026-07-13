# Stock Quant Pro 1.2.2（A股短线量化终端）

面向 Windows 个人投资者的本地量化研究软件。当前版本覆盖沪深主板 A 股，默认排除创业板、科创板、北交所、ST 和退市股票，核心周期为 2–10 个交易日。

## 1.2.2 已完成

- 沪深主板股票列表同步并保存到 PostgreSQL
- 全市场异步扫描任务、进度查询、历史任务、失败明细和一键重试
- 扫描结果按任务持久化；测试扫描不覆盖正式全市场结果
- 严格候选过滤、自动排名和交易计划生成
- 扫描时自动保存近 120 个交易日 K 线到本地数据库
- 腾讯、 新浪、东财和本地缓存的行情降级链路
- 全市场行情库、数据管理中心、智能选股排行榜
- 候选股票一键进入动态分析和单股回测
- 候选前N名批量历史回测与汇总
- 最近90日行情增量更新任务
- MA5/10/20/60、RSI14、MACD、量比、ATR、收益率、回撤和波动率评分
- 每个交易日 16:10 自动创建全市场扫描任务

## 技术栈

- Java 17、Spring Boot 3.2.8、Flyway
- PostgreSQL 16
- Python 3.11、FastAPI、AKShare、Pandas、NumPy
- Vue 3、TypeScript、Electron、ECharts

## 首次启动

1. 将 `.env.example` 复制为 `.env`，填写 PostgreSQL 密码。
2. 运行 `scripts\01-init-python.cmd`。
3. 运行 `scripts\02-init-database.cmd`。
4. 运行 `scripts\03-start-dev.cmd`。

使用 IDEA 时：

1. 启动 `QuantServerApplication`。
2. 在 `quant-ai` 目录启动：
   `\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8001`
3. 在 `quant-desktop` 目录执行：`npm run dev`。

默认地址：

- Java API：`http://127.0.0.1:8080`
- Python 行情服务：`http://127.0.0.1:8001`
- Web：`http://127.0.0.1:5173`

## 第一次使用全市场扫描

1. 打开“数据管理”。
2. 点击“同步股票列表”。
3. 扫描数量填写 `200` 可先做小范围验证；填写 `0` 表示扫描全部沪深主板。
4. 点击“启动全市场扫描”。
5. 在“智能选股”查看最新排名。

首次扫描需要从外部数据源获取历史行情，耗时会较长。成功获取后会写入 PostgreSQL 和 Python 本地缓存，后续速度会明显提高。

## 风险与边界

本软件仅用于投研、回测和人工交易辅助，不保证收益。当前不会连接券商自动下单，也不会保存券商密码。免费行情可能延迟、缺失或临时不可用，真实交易前必须在券商终端再次核对。
