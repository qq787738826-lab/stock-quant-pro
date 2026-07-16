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


## 1.3.0 模拟交易接口

```text
GET  /api/portfolio
GET  /api/portfolio/plans
POST /api/portfolio/plans/from-scan
POST /api/portfolio/plans/{planId}/orders
POST /api/portfolio/plans/{planId}/cancel
GET  /api/portfolio/orders
POST /api/portfolio/orders
POST /api/portfolio/orders/{id}/confirm
POST /api/portfolio/orders/{id}/cancel
GET  /api/portfolio/trades
GET  /api/portfolio/equity
POST /api/portfolio/refresh-risk
GET  /api/portfolio/risk-events
POST /api/portfolio/risk-events/{id}/resolve
```

## 1.4.0 股票智能体团队接口

### Java对Vue公开的接口

```text
POST /api/agent-tasks
GET  /api/agent-tasks/{taskId}
GET  /api/agent-tasks/{taskId}/runs
GET  /api/agent-tasks/{taskId}/evidence
GET  /api/agent-tasks/{taskId}/decision
GET  /api/agent-tasks/{taskId}/vetoes
GET  /api/agent-tasks/history
```

Vue只能调用上述Java接口，不能直接调用Python智能体接口。

### Java调用Python的内部统一接口

```text
POST /agents/team/analyze
```

Java为每个团队任务只调用一次该接口。Python返回六个专业智能体结果、证据、正式否决和独立的总控最终决策。Python不读取或写入智能体任务数据库表，也不修改Java生成的`taskId`、六个专业智能体`runIds`或`contextHash`。

`agent-task-request.schema.json`是Vue调用Java创建团队任务的请求；`agent-team-request.schema.json`是Java调用Python执行只读分析的请求，两者不是同一个接口契约。Java到Python的请求携带六个专业智能体`runIds`和不可变上下文快照，Python不得修改`taskId`、`runIds`或`contextHash`。

请求与响应契约：

- [团队任务请求Schema](schemas/agent-task-request.schema.json)
- [Java到Python团队分析请求Schema](schemas/agent-team-request.schema.json)
- [单智能体输出Schema](schemas/agent-output.schema.json)
- [团队响应Schema](schemas/agent-team-response.schema.json)
- [总控最终决策Schema](schemas/agent-decision.schema.json)

上述接口已经进入生产代码。Java负责任务身份、状态、缓存、校验和持久化；Python接口仍是无状态规则骨架，当前不代表已具备真实股票分析能力。实际进度以 [CURRENT_STATE.md](agent-team/CURRENT_STATE.md) 为准。
