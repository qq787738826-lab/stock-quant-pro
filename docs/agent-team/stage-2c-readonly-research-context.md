# 阶段2C验收：第二批只读研究上下文

> 状态：阶段2C已完成正式技术验收和真实PostgreSQL闭环验收。

- 验收来源分支：`codex/1.4.0-2c-readonly-research-context`

## 范围与权威边界

Java继续作为上下文读取、解释、冻结、Hash和持久化的唯一权威。Python无状态且不访问业务数据库。本阶段只接入`marketBreadth`与`scanResult`，并把`backtestContext`固化为结构化安全不可用；不实现智能体评分、市场环境判断、回测解释或交易写入。

全部九类上下文由`AgentContextSnapshotService.create`在同一`REPEATABLE_READ`只读事务、同一个`queriedAt`下生成。Repository只执行参数化SELECT。数据库异常向上抛出，不映射为业务不可用。

## marketBreadth

来源固定为`securities`和`daily_bars`，`producer=AgentMarketBreadthContextService`、`producerVersion=MARKET_BREADTH_V1`、`versionAvailable=true`。universe使用查询时当前`board='MAIN' AND is_active=true AND is_st=false`证券；`securities`不是历史表，因此`universePointInTimeGuaranteed=false`、整体`futureDataExcluded=false`。日线读取严格排除请求日期之后的数据，因此`barFutureDataExcluded=true`。

日线只读取`QFQ`且`trade_date<=requestedTradeDate`。先选择整个当前universe出现过的最大统一`effectiveTradeDate`，再选择严格早于它的最大统一`previousEffectiveTradeDate`。每只证券都使用这两个统一日期，禁止各自回退。

输出universe总数、当前日覆盖数、可比较数、上涨/下跌/平盘、当前日缺失、前日缺失及BigDecimal八位HALF_UP覆盖率；`coverageRatio=comparableSymbolCount/universeCount`。universe为零时覆盖率为null。不可用优先级为`NO_ELIGIBLE_UNIVERSE`、`NO_EFFECTIVE_TRADE_DATE`、`NO_PREVIOUS_EFFECTIVE_TRADE_DATE`、`ZERO_COMPARABLE_SYMBOLS`。

不输出牛熊、环境、涨跌停、新高新低、score、gate、decision、finding、veto或投资建议。`marketBreadth`只提供可追溯的市场宽度事实，不构成市场环境判断。

## scanResult

来源固定为`market_scan_tasks`、`market_scan_results`和`market_scan_failures`。任务选择条件为：`COMPLETED/FULL/official=true`、任务交易日不晚于请求日、完成时间早于请求日下一日零点；TEST、RETRY和未来任务均被排除；按`trade_date DESC, finished_at DESC, id DESC`稳定选择唯一任务。选定后不为寻找symbol而回退旧任务。

结果、失败都按`taskId+symbol`独立读取。结果存在表示参与已知；eligible只映射为`symbolSelected`。仅失败存在表示参与已知且扫描失败，不复制错误原文。二者均不存在表示参与未知。二者同时存在时保留结果为主要事实并增加限制。结果和任务交易日不一致时返回`SCAN_TASK_RESULT_DATE_MISMATCH`并保留两端日期。

只投影强类型列：rank、eligible、数据库扫描分数`sourceScanScore`、filterReasons、latestClose、dataSource及七个强类型指标列。filterReasons仅保留字符串并确定性去空、去重、排序；非字符串只记录限制。禁止复制原始metrics、bullish、bearish、summary、买入区间、止损、目标价、建议仓位、signalLevel和riskLevel。

读取层排除未来任务，但生产过程没有持久化完整输入截止日，因此固定：`pointInTimeGuaranteed=false`、`readSelectionFutureExcluded=true`、`producerInputCutoffGuaranteed=false`、`futureDataExcluded=false`。生产者版本不可用，`producerVersion=null`且`versionAvailable=false`。`scanResult`只表示历史扫描事实，不构成 Agent 评分或投资建议。

## backtestContext

固定`available=false`和`reasonCode=BACKTEST_INPUT_CUTOFF_UNVERIFIABLE`。来源表仅记录为`backtest_runs`、`scan_backtest_results`和`scan_backtest_tasks`；`producer=null`、`producerVersion=null`、`versionAvailable=false`、`readSelectionFutureExcluded=false`、`producerInputCutoffGuaranteed=false`、`futureDataExcluded=false`。现有记录没有保存可验证的输入截止日期、策略版本和完整参数；`ScanValidationService.localHistory(symbol,180)`也没有传入截止日。本阶段不读取旧收益记录冒充可靠证据，不运行新回测或`BacktestEngine`，不创建回测任务。

## Hash与阶段2B兼容

外层九类结构和`CONTEXT_SCHEMA_VERSION=1.0`不变。新事实自然参与现有Hash；`queriedAt`继续排除。阶段2B规则版本、状态映射和DATA_QUALITY实现不变，其权威evidence仍只投影`security`、`marketData`、`technicalMetrics`、`dataQualityContext`。其余五个专业run及总控行为不变。

## 正式验收证据

验收覆盖Java上下文服务、Hash、跨语言契约、Python兼容及专用PostgreSQL真实聚合/JSONB往返。真实数据库验收使用PostgreSQL 16.13服务，专用数据库和专用用户均为`stock_quant_test`；数据库测试使用唯一fixture标记和精确清理，不假设共享数据库为空。

`AgentStage2CReadonlyContextPostgresIntegrationTest#freezesStage2CContextsWithoutBusinessSideEffectsAndRoundTripsJsonb`真实结果为`1/0/0/0`（run/failure/error/skipped）、`BUILD SUCCESS`。上下文快照与PostgreSQL JSONB完整语义一致，持久化JSONB经生产`AgentContextHashService`重算后与正式`contextHash`一致；`marketBreadth`真实聚合、`scanResult`正式任务选择及强类型字段白名单通过，`backtestContext`保持结构化安全不可用。

测试未产生扫描、回测、信号或trade plan副作用；测试夹具已按返回ID、专用symbol和data source精确清理，11张相关表恢复测试前基线。未使用全表清理。临时数据库认证环境变量已清除。

最终非数据库回归结果：阶段2C定向Java组合`38/0/0/0`；完整Agent回归`176/0/0/10`；`quant-server`全量`177/0/0/10`；`quant-core`全量`1/0/0/0`。无数据库变量回归中的数据库集成测试按环境门禁安全跳过，不改变上述阶段2C真实PostgreSQL测试已经通过的结论。

Python compileall通过；unittest discover全量`50/0/0`（run/failure/error）；阶段2C兼容测试`1/0/0`。全部Maven测试使用现有本机缓存离线执行，未下载依赖。`git diff --check`通过。

阶段2C完成的是`marketBreadth`与`scanResult`只读事实接入和`backtestContext`安全不可用边界，不代表`MARKET_REGIME`或`STRATEGY_BACKTEST`专业规则已经实现，也不具备投资建议、LLM决策、自动交易或新外部数据接入能力。

下一阶段唯一入口为阶段2D：MARKET_REGIME真实规则；阶段2D尚未开始。
