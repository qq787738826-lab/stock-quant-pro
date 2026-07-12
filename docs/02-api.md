# REST API

| 方法 | 地址 | 说明 |
|---|---|---|
| GET | `/api/health` | 核心服务与数据库健康检查 |
| GET | `/api/market/overview` | 市场概览 |
| GET | `/api/signals/daily?limit=10` | 多因子候选信号 |
| GET | `/api/trade-plans?limit=10` | 交易计划 |
| GET | `/api/portfolio` | 模拟账户汇总 |
| GET | `/api/portfolio/orders` | 人工委托列表 |
| POST | `/api/portfolio/orders` | 创建待确认委托 |
| POST | `/api/portfolio/orders/{id}/confirm` | 人工确认委托 |
| POST | `/api/backtests` | 运行单标的基础回测 |
| POST | `/api/ai/analyze` | AI/规则综合分析 |

FastAPI：`GET /market/history/{symbol}?days=120`、`POST /ai/analyze`。
