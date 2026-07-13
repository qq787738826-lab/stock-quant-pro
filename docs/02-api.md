# REST API

## Java API

| 方法 | 地址 | 说明 |
|---|---|---|
| GET | `/api/health` | Java 服务与数据库健康检查 |
| GET | `/api/data/overview` | 股票数、K线数、缓存股票数和最新扫描任务 |
| POST | `/api/data/universe/sync` | 同步沪深主板股票列表 |
| POST | `/api/data/history/sync?symbol=600000&days=260` | 同步单只股票历史 K 线 |
| GET | `/api/market/securities?keyword=&page=1&size=50` | 查询本地股票库 |
| POST | `/api/scans` | 创建异步全市场扫描任务 |
| GET | `/api/scans/{taskId}` | 查询扫描任务进度 |
| GET | `/api/scans/latest-task` | 查询最新扫描任务 |
| GET | `/api/scans/latest?limit=50` | 查询最近一次完成的扫描排名 |
| POST | `/api/backtests` | 单标的回测 |
| POST | `/api/ai/analyze` | 动态规则综合分析 |
| GET | `/api/portfolio` | 模拟账户汇总 |
| POST | `/api/portfolio/orders` | 创建待确认委托 |

创建扫描任务示例：

```json
{
  "scanLimit": 200,
  "batchSize": 12,
  "resultLimit": 50
}
```

`scanLimit=0` 表示扫描全部已同步的沪深主板股票。

## Python FastAPI

| 方法 | 地址 | 说明 |
|---|---|---|
| GET | `/health` | Python 服务健康检查 |
| GET | `/market/universe?includeSt=false` | 沪深主板股票列表 |
| GET | `/market/history/{symbol}?days=120` | 多数据源历史行情 |
| POST | `/market/analyze-batch` | 最多 50 只股票的并行动态分析 |
| POST | `/ai/analyze` | 单只股票动态分析 |


## 1.2.2 扫描与验证接口

```text
GET  /api/scans/history
GET  /api/scans/{taskId}/results?eligibleOnly=true
GET  /api/scans/{taskId}/failures
POST /api/scans/{taskId}/retry
GET  /api/scans/latest-official-task

POST /api/data/updates
GET  /api/data/updates/latest
GET  /api/data/updates/{taskId}
GET  /api/data/updates/{taskId}/failures

POST /api/scans/{taskId}/backtests
GET  /api/scan-backtests/{taskId}
GET  /api/scan-backtests/{taskId}/results
GET  /api/scans/{scanTaskId}/backtests/latest
```
