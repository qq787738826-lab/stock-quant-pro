# Stock Quant Pro 智能体团队当前状态

> 本文件是智能体团队进度、能力边界和下一阶段入口的单一事实来源。阶段文档、聊天记录或旧注释与本文件冲突时，以本文件为准。

## 基线

- 当前稳定版本：`1.3.1`
- 当前目标版本：`1.4.0`
- 当前集成分支：`feature/1.4.0-agent-team`
- 1D-4 验收来源分支：`codex/1.4.0-1d4-acceptance`
- 验收基线：`5bc492a feat(agent): add safe local team runtime scripts`
- `master`：`27d9099 chore: checkpoint Stock Quant Pro 1.3.1 and remove tracked cache`
- 版本号仍保持 `1.3.1`；尚未发布 `1.4.0`。

## 已完成并验收

- 1D-1：六智能体跨语言契约与编排骨架。
- 1D-2：Java 权威任务、事务、异步执行、真实 Python 调用与 PostgreSQL 闭环。
- 1D-3：证据、评分、正式否决、总控一致性、合法持久化与非法响应原子失败。
- 1D-4：Vue 工作台、正式否决查询、本地安全启停和端到端验收。
- 阶段 2A 前置：Agent PostgreSQL 集成测试已完成任务级隔离治理，共享非空专用测试库不再要求五张 Agent 表全局为空。

这些阶段完成的是基础设施与契约闭环，不代表真实股票分析能力已经完成。

## 权威边界与真实可用能力

Java 是 `taskId`、六个 `runId`、状态、幂等与缓存、持久化和跨语言响应校验的唯一权威。Python 无状态，只处理 Java 传入的只读 `contextSnapshot`，不访问任务数据库。PostgreSQL 已包含 task、run、evidence、veto、decision 五类持久化结构。Vue 可创建、轮询、恢复并展示任务。本地脚本可安全启动、复用和精确停止 Python、Java、Vue。

当前真实可用的是任务基础设施、契约校验、空数据阻断、持久化、失败原子性和工作台观察能力。当前没有真实智能体股票分析能力。

## 九类 contextSnapshot 实际状态

| 上下文 | 当前状态 |
|---|---|
| `security` | 不可用；尚未接入现有业务数据源 |
| `marketData` | 不可用；尚未接入现有业务数据源 |
| `marketBreadth` | 不可用；尚未接入现有业务数据源 |
| `scanResult` | 不可用；尚未接入现有业务数据源 |
| `technicalMetrics` | 不可用；尚未接入现有业务数据源 |
| `backtestContext` | 不可用；尚未接入现有业务数据源 |
| `securityEvents` | 不可用；尚未接入现有业务数据源 |
| `portfolioContext` | 不可用；尚未接入现有业务数据源 |
| `dataQualityContext` | 不可用；尚未接入现有业务数据源 |

## 六智能体与总控实际状态

固定专业智能体为 `DATA_QUALITY`、`MARKET_REGIME`、`TECHNICAL_ANALYSIS`、`STRATEGY_BACKTEST`、`ANNOUNCEMENT_RISK`、`POSITION_RISK`。总控不是第七个 run。

由于九类上下文均不可用，六个智能体当前都返回 `INSUFFICIENT_DATA`。`DATA_QUALITY` 返回 `gateStatus=BLOCKED`、`decision=REJECT`、`veto=false`；其他五个返回 `NOT_APPLICABLE`。`ChiefDecisionService` 当前固定产生 `BLOCKED_BY_DATA_QUALITY`，不产生正式否决。只有 `POSITION_RISK` 在满足冻结契约时才有正式否决权。

## 数据库、前端与本地运行

- 数据库 Schema 当前已迁移至 Flyway V5；本阶段未新增或修改迁移脚本。
- 逻辑 evidence ID、逻辑 veto ID 与数据库物理主键的映射规则已冻结。
- 工作台路由为 `/agent-team`，通过 `taskId` query 恢复任务；旧 `/ai` 页面保留。
- 工作台使用真实 Java API，不包含运行时 mock 或前端生成的分析结论。
- `start-agent-team-local.ps1` / `stop-agent-team-local.ps1` 使用可信状态、PID/启动时间、进程树、互斥锁和敏感环境隔离。

## 禁止范围

当前智能体团队阶段禁止新增接入或宣称已具备：实时行情查询、新 AKShare 查询、公告上下文、真实或新增模拟持仓上下文、LLM/付费 API、真实评分策略、交易写操作、自动下单和券商控制。不得编造价格、指标、证据或投资结论。

## 已知问题与最近测试

- PostgreSQL 16.13 高于当前 Flyway 9.22.3 声明的已测试上限，现阶段为非阻断警告。
- Python TestClient 存在 Starlette/httpx 弃用警告。
- 前端主包超过 Vite 默认 500 kB 提示阈值。
- 最近验收：带既有数据的专用测试库中 Java 智能体运行 138 项（0 项失败、1 项跳过）；无数据库变量 Java 智能体运行 133 项（8 项跳过、0 项失败）；无变量 quant-server 运行 134 项（8 项跳过、0 项失败）。测试隔离治理前后五张 Agent 表既有数据计数保持不变。此前验收的 Python 33/33、真实 Python 冒烟、Vue 生产构建和本地端到端闭环结果继续有效。

## 下一阶段唯一入口

**阶段2A：仅从现有 PostgreSQL 接入第一批只读上下文。**

第一批范围仅为：

- `security`
- `marketData`
- `technicalMetrics`
- `dataQualityContext`

阶段2A 尚未开始，本次验收不得实施。
