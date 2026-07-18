# Stock Quant Pro 智能体团队当前状态

> 本文件是智能体团队进度、能力边界和下一阶段入口的单一事实来源。阶段文档、聊天记录或旧注释与本文件冲突时，以本文件为准。

## 基线

- 当前稳定版本：`1.3.1`
- 当前目标版本：`1.4.0`
- 当前集成分支：`feature/1.4.0-agent-team`
- 1D-4 验收来源分支：`codex/1.4.0-1d4-acceptance`
- 1D-4 验收基线：`5bc492a feat(agent): add safe local team runtime scripts`
- 阶段 2A 验收来源分支：`codex/1.4.0-2a-readonly-context`
- 阶段 2A 实现基线：`1707d3e4991434d7655b50d9af0b532e0b0e7a60`
- 阶段 2B 验收来源分支：`codex/1.4.0-2b-data-quality-gate`
- 阶段 2B 规则版本：`1.4.0-stage-2b-dq-v1`
- 阶段 2C 验收来源分支：`codex/1.4.0-2c-readonly-research-context`
- `marketBreadth` 生产版本：`MARKET_BREADTH_V1`
- `master`：`27d9099 chore: checkpoint Stock Quant Pro 1.3.1 and remove tracked cache`
- 版本号仍保持 `1.3.1`；尚未发布 `1.4.0`。

## 已完成并验收

- 1D-1：六智能体跨语言契约与编排骨架。
- 1D-2：Java 权威任务、事务、异步执行、真实 Python 调用与 PostgreSQL 闭环。
- 1D-3：证据、评分、正式否决、总控一致性、合法持久化与非法响应原子失败。
- 1D-4：Vue 工作台、正式否决查询、本地安全启停和端到端验收。
- 阶段 2A 前置：Agent PostgreSQL 集成测试已完成任务级隔离治理，共享非空专用测试库不再要求五张 Agent 表全局为空。
- 阶段 2A：从现有 PostgreSQL 接入 `security`、`marketData`、`technicalMetrics` 和 `dataQualityContext` 四类只读上下文；完成只读一致性事务、Java 确定性指标、数学数值 Hash 规范化和 JSONB 往返验收。
- 阶段 2B：基于阶段 2A 四类冻结事实完成确定性 DATA_QUALITY 规则门禁、版本化跨语言契约、Java 双重校验和真实 PostgreSQL/Java/Python 持久化闭环验收。
- 阶段 2C：从现有 PostgreSQL 接入 `marketBreadth` 和 `scanResult` 两类只读研究上下文，并将 `backtestContext` 冻结为结构化安全不可用；完成统一时点规则、扫描任务稳定选择、字段白名单、Hash/JSONB 及真实 PostgreSQL 闭环验收。

这些阶段完成的是基础设施、只读事实上下文与契约闭环，不代表六智能体真实评分、真实股票分析或投资建议能力已经完成。

## 权威边界与真实可用能力

Java 是 `taskId`、六个 `runId`、状态、幂等与缓存、持久化和跨语言响应校验的唯一权威。Python 无状态，只处理 Java 传入的只读 `contextSnapshot`，不访问任务数据库。PostgreSQL 已包含 task、run、evidence、veto、decision 五类持久化结构。Vue 可创建、轮询、恢复并展示任务。本地脚本可安全启动、复用和精确停止 Python、Java、Vue。

当前真实可用的是 Java 权威任务和持久化、阶段 2A 第一批四类上下文、确定性技术指标、确定性 DATA_QUALITY 门禁、`marketBreadth` 只读事实、`scanResult` 历史扫描事实、Hash 与 JSONB 稳定往返、缺数安全降级、失败原子性和 Vue 工作台观察能力。除 DATA_QUALITY 外其余五个专业规则仍未实现，当前仍不具备完整六智能体分析、MARKET_REGIME 真实分类、TECHNICAL_ANALYSIS 真实规则、STRATEGY_BACKTEST 解释、公告风险、持仓风险、投资建议或自动交易能力。

## 九类 contextSnapshot 实际状态

| 上下文 | 当前状态 |
|---|---|
| `security` | 已从现有 PostgreSQL `securities` 只读接入；该表不是历史版本表，不保证请求交易日时点的证券属性 |
| `marketData` | 已从现有 PostgreSQL `daily_bars` 接入截止请求日期的 QFQ 日线，最多读取最近61条 |
| `marketBreadth` | 已由 Java 在同一只读事务内基于当前 MAIN、active、非 ST 证券及 QFQ 日线确定性生成；统一有效日期和前一有效日期；证券池不是历史版本，因此 `pointInTimeGuaranteed=false` |
| `scanResult` | 已从已完成、正式、FULL 扫描任务中只读接入；按交易日、完成时间和 ID 稳定选择；只输出白名单事实，不输出推荐字段；生产输入截止日期和算法版本不可完全证明 |
| `technicalMetrics` | 已由 Java 基于同一事务冻结的本地 QFQ 日线，使用 `JAVA_INDICATORS_V1` 确定性计算 |
| `backtestContext` | 仍不可用；现有回测记录没有可验证的输入截止日期、策略版本和完整参数，`reasonCode=BACKTEST_INPUT_CUTOFF_UNVERIFIABLE` |
| `securityEvents` | 不可用；尚未接入现有业务数据源 |
| `portfolioContext` | 不可用；尚未接入现有业务数据源 |
| `dataQualityContext` | 已生成只读数据质量事实；不包含评分、规则门禁、决策或否决，数据库查询正常时即使证券和日线缺失仍可用 |

## 六智能体与总控实际状态

固定专业智能体为 `DATA_QUALITY`、`MARKET_REGIME`、`TECHNICAL_ANALYSIS`、`STRATEGY_BACKTEST`、`ANNOUNCEMENT_RISK`、`POSITION_RISK`。总控不是第七个 run。

阶段 2B 仅升级 DATA_QUALITY：无效上下文映射为 `INSUFFICIENT_DATA/BLOCKED/REJECT/0/0`，有效阻断为 `COMPLETED/BLOCKED/REJECT/0/100`，有效警告为 `COMPLETED/WARN/WARN/50/100`，有效通过为 `COMPLETED/PASS/PASS/100/100`，且 `veto` 始终为 `false`。其余五个专业规则仍返回 `INSUFFICIENT_DATA/NOT_APPLICABLE`。DATA_QUALITY 为 PASS 或 WARN 时，总控为 `INSUFFICIENT_DATA`、继承 gate、`score=0`、`confidence=0`；BLOCKED 时为 `BLOCKED_BY_DATA_QUALITY`，但不形成正式否决。只有 `POSITION_RISK` 在满足冻结契约时才有正式否决权。总控现有行为不变，且总控不是第七个 run。阶段 2C 接入 `marketBreadth` 和 `scanResult` 不等于 MARKET_REGIME 已实现。这些结果不构成完整股票分析或投资建议。

## 数据库、前端与本地运行

- 数据库 Schema 当前已迁移至 Flyway V5；本阶段未新增或修改迁移脚本。
- 阶段 2A 使用 Agent 专用只读 Repository 查询 `securities` 和截止请求日的 QFQ `daily_bars`；四类上下文在 `REPEATABLE_READ` 只读事务中冻结，不执行市场数据同步或数据库写操作。
- 阶段 2C 未修改 Flyway 或外层 JSON Schema，`CONTEXT_SCHEMA_VERSION` 仍为 `1.0`。
- `marketBreadth`、`scanResult` 与阶段 2A 四类上下文在同一个 `REPEATABLE_READ` 只读事务内冻结；Python 仍不直连数据库，`backtestContext` 不运行 `BacktestEngine`。
- `contextHash` 按 JSON 数值的数学值规范化，对象字段稳定排序、数组保持业务顺序；API、PostgreSQL JSONB 与持久化快照重算结果一致。
- 阶段 2B 质量 evidence 固定来源为 `JAVA_ENGINE/AgentContextSnapshotService/contextSnapshot`，只投影四类冻结上下文；所有 DATA_QUALITY finding 引用该权威 evidence。
- Java 纳秒 `Instant` 与 Python `datetime` 往返时间按微秒传输精度规范化比较，双方截断到微秒后必须完全相等；相差 1 微秒即拒绝，不改写冻结上下文或 Hash。
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
- 阶段 2A Java 验收：`quant-core` 运行1项、0失败；阶段 2A 定向测试运行21项、0失败；带专用 PostgreSQL 变量的完整 Agent 测试运行155项、0失败、1项跳过；独立无数据库变量 Agent 测试运行150项、0失败、9项安全跳过；独立无变量 `quant-server` 全量运行151项、0失败、9项跳过。
- Python `compileall` 通过，权威 `unittest discover` 为33/33通过；仓库未声明 pytest 依赖，本阶段未安装 pytest 或修改 Python 依赖。Vue生产构建通过，`git diff --check` 通过。
- 自动化生产流与真实本地受控任务均确认 API `contextHash`、数据库 `context_hash` 和生产 `AgentContextHashService` 对 JSONB 的重算结果一致。
- 正常用户 PowerShell 已通过 Python、Java、Vue 安全启动、空数据任务、受控数据任务和精确停止闭环。验收任务及阶段 2A 证券、日线夹具均已精确清理，最终 Agent 五表计数为 `2/12/0/0/2`；`state.json`、三个监听端口和六个记录 PID 均无残留。
- Codex 受控环境因无权读取 `Win32_Process` 而被脚本安全拒绝证明进程归属；正常用户 PowerShell 的 CIM 权限下闭环通过。本阶段未修改运行脚本。
- 阶段 2B Java 验收：阶段 2B 定向测试 `13/0/0/0`，响应校验相关定向测试 `45/0/0/0`，无集成变量完整 Agent 回归 `164/0/0/9`，`quant-server` 全量 `165/0/0/9`。
- 阶段 2B 真实闭环：专用 `stock_quant_test` 数据库、真实 Python 回环服务和 Java 持久化测试为 `1/0/0/0`、`BUILD SUCCESS`；DATA_QUALITY 为 `COMPLETED/WARN/WARN/50/100`，总控为 `INSUFFICIENT_DATA/WARN/0/0`，六 run、唯一 evidence、空正式 veto 和 Hash 一致性均通过。
- 阶段 2B 测试数据已精确清理；测试前后 Agent 五表基线为 `2/12/0/0/2`，证券与日线均为 `0/0`，Python 已停止且 8001 已释放。
- 阶段 2C 真实 PostgreSQL 验收：`AgentStage2CReadonlyContextPostgresIntegrationTest` 为 `1/0/0/0`、`BUILD SUCCESS`；上下文与 JSONB 完整语义往返、生产 Hash 重算、无业务副作用、测试数据精确清理及相关表恢复测试前基线均通过。
- 阶段 2C 最终回归：Java 定向 `38/0/0/0`，完整 Agent `176/0/0/10`，`quant-server` `177/0/0/10`，`quant-core` `1/0/0/0`；Python `compileall` 通过、unittest 全量 `50/0/0`、阶段 2C 定向 `1/0/0`；`git diff --check` 通过。

## 下一阶段唯一入口

**阶段2D：MARKET_REGIME真实规则。**

阶段 2D 尚未开始。其唯一允许范围是消费已冻结的 `marketData`、`marketBreadth`、`scanResult`、`technicalMetrics` 及 DATA_QUALITY 结果，实现确定性市场环境分类和可追溯证据。

阶段 2D 禁止外部行情补数、LLM 权威决策、投资建议和交易写操作；本次文档修改没有开始阶段 2D。
