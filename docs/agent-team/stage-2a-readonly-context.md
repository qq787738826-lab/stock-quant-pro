# 阶段 2A 验收记录：现有 PostgreSQL 第一批只读上下文

## 1. 阶段目标与范围

阶段 2A 仅由 Java 从现有 PostgreSQL 读取并冻结以下四类只读 `contextSnapshot`：

- `security`
- `marketData`
- `technicalMetrics`
- `dataQualityContext`

以下五类上下文仍未接入，继续返回 `available=false`：

- `marketBreadth`
- `scanResult`
- `backtestContext`
- `securityEvents`
- `portfolioContext`

Java 继续负责查询、冻结、Hash 和持久化，Python 保持无状态。本阶段没有实现智能体评分、规则门禁、投资结论或交易能力，也没有接入外部数据源、修改 Flyway 或修改外层 JSON Schema。

- 验收来源分支：`codex/1.4.0-2a-readonly-context`
- 实现基线：`1707d3e4991434d7655b50d9af0b532e0b0e7a60`

## 2. 数据来源与只读边界

| 上下文 | 数据来源 | 生成方式 |
|---|---|---|
| `security` | PostgreSQL `securities` | 按 `symbol` 读取单条本地证券事实 |
| `marketData` | PostgreSQL `daily_bars` | 读取截止请求日的 QFQ 日线，最多61条 |
| `technicalMetrics` | 冻结后的 QFQ 日线 | Java 复用 `quant-core` 的 `Indicators` 确定性计算，公式版本为 `JAVA_INDICATORS_V1` |
| `dataQualityContext` | 同一事务内的证券和日线查询结果 | Java 汇总数据质量事实，不产生评分或门禁结论 |

Agent 专用 `AgentContextReadRepository` 只负责参数化 `SELECT`。它不读取 `securities.latest_price`，不读取 `market_scan_results`，不调用 `MarketDataService.history()`，不触发市场数据同步、Python 行情加载或数据库写操作。数据库访问异常直接向上抛出，不会伪装成正常缺数。

## 3. 实际 SQL

证券查询：

```sql
SELECT symbol, name, exchange, board, industry, list_date,
       is_st, is_active, data_source, updated_at
FROM securities
WHERE symbol = ?
```

QFQ 日线查询：

```sql
SELECT symbol, trade_date, open, high, low, close,
       volume, amount, turnover_rate, adjust_type
FROM daily_bars
WHERE symbol = ?
  AND adjust_type = 'QFQ'
  AND trade_date <= ?
ORDER BY trade_date DESC
LIMIT 61
```

截止请求日的复权类型事实查询：

```sql
SELECT DISTINCT adjust_type
FROM daily_bars
WHERE symbol = ? AND trade_date <= ?
ORDER BY adjust_type
```

Repository 先按 `trade_date DESC LIMIT 61` 取最近记录，再由 Java 按 `tradeDate ASC` 稳定排序后写入快照。金额、换手率和所有价格字段保持数据库原始数值及 `null` 语义。

## 4. 事务一致性

公开的上下文创建入口使用：

```java
@Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ
)
```

证券、QFQ 日线、复权类型、技术指标和质量事实在同一个只读一致性事务内冻结。事务内只执行 `SELECT`；连接或 SQL 异常会在 Agent 任务持久化前终止流程，不创建半成品任务。

## 5. `security` 边界

证券存在时，快照原样输出 `securities` 中的：

- `symbol`
- `name`
- `exchange`
- `board`
- `industry`
- `listDate`
- `isSt`
- `isActive`
- `dataSource`
- `updatedAt`

同时输出以下质量事实：

- `placeholderSuspected`
- `sourceKnown`
- `pointInTimeGuaranteed=false`
- `updatedAtTimezoneSemantics=UNSPECIFIED_DATABASE_LOCAL_TIME`

`securities` 不是历史版本表，因此不能保证恢复请求交易日时点的证券属性。实现不会根据代码前缀补推交易所，不会自动补证券名称，也不会把 `isActive` 解释为具体停牌或退市原因。证券不存在时返回 `available=false` 和 `reasonCode=NO_LOCAL_SECURITY_DATA`。

## 6. `marketData` 边界

- 仅使用 `adjustType=QFQ`。
- 查询范围严格为 `trade_date <= requestedTradeDate`。
- 最多读取最近61条，输出时严格按日期升序。
- 非交易日使用截止请求日之前最近的本地历史日，并通过 `effectiveTradeDate` 和 `exactTradeDateMatch` 明确表达。
- `amount` 和 `turnoverRate` 为 `null` 时继续保持 `null`，不替换为0。
- 非法 OHLC 或负 `volume` 仍作为数据库原始事实保留在 `marketData.bars` 中，不被修正或静默删除。
- 不使用实时快照或 `latest_price` 代替指定日期日线。

没有任何可用日线时，返回 `available=false`、`reasonCode=NO_LOCAL_DAILY_BARS_ON_OR_BEFORE_TRADE_DATE`、`bars=[]` 和 `actualBars=0`。

## 7. `technicalMetrics` 边界

技术指标全部由 Java 基于本次冻结的 QFQ 日线计算，复用 `quant-core` 的 `Indicators`，公式版本固定为 `JAVA_INDICATORS_V1`。

阶段 2A 只输出：

- `ma5`
- `ma20`
- `ma60`
- `rsi14`
- `atr14`
- `averageVolume20`
- `highestClose20`

窗口分别为5、20、60、14、14、20、20，`requiredBars=61`。只有61条必要日线全部满足基本 OHLCV 合法性且所有指标计算成功时，`technicalMetrics.available=true`。

少于61条时返回 `available=false` 和 `reasonCode=INSUFFICIENT_LOCAL_DAILY_BARS`；不会补0、缩短窗口后复用原指标名或动态补数。必要日线非法时返回 `available=false` 和 `reasonCode=INVALID_LOCAL_DAILY_BARS`，不输出可信指标值。`amount` 或 `turnoverRate` 为 `null` 不阻断指标计算。

计算保持 `BigDecimal` 语义，不经过 `double` 或 `float`。本阶段不提供 EMA、MACD、BOLL，也不调用 Python 计算指标。

## 8. `dataQualityContext` 事实边界

只要数据库查询正常完成，`dataQualityContext.available=true`；即使证券和日线都不存在，“缺少数据”本身仍是质量事实。

当前事实包括：

- `securityRecordPresent`
- `securityPlaceholderSuspected`
- `securitySourceKnown`
- `securityPointInTimeGuaranteed`
- `loadedBarCount`
- `requiredBarsForTechnicalMetrics`
- `exactTradeDatePresent`
- `requestedTradeDate`
- `effectiveTradeDate`
- `naturalDayLag`
- `tradingCalendarAvailable`
- `missingAmountCount`
- `missingTurnoverRateCount`
- `invalidBarCount`
- `invalidBarDates`
- `maximumObservedNaturalDayGap`
- `duplicateProtection`
- `sourceConsistencyAssessable`
- `adjustTypesObserved`
- `missingSecurityFields`

`invalidBarCount` 统计非法记录总数；`invalidBarDates` 对非法记录日期显式去重后按 `LocalDate` 升序输出。质量日期去重不会删除 `marketData.bars` 中的原始记录。`adjustTypesObserved` 和其他数组均稳定排序。

当前没有交易日历，`tradingCalendarAvailable=false`，因此不会把周末或节假日直接判断为缺失交易日。`maximumObservedNaturalDayGap` 只描述已加载记录之间观察到的自然日间隔。`sourceConsistencyAssessable=false`，不会伪造跨源一致性检查。

`dataQualityContext` 不包含 `score`、`gateStatus`、`decision`、`veto` 或投资结论；这些属于阶段 2B。

## 9. Hash 契约与兼容性

`AgentContextHashService` 对所有 JSON 数值按数学值统一规范化：

- 数值统一转换为 `BigDecimal` 并移除无意义尾零。
- `10`、`10.0`、`10.0000` 和等值科学计数法产生相同 Hash。
- `10` 与 `10.0001` 产生不同 Hash。
- 零、负数和超过 `Long` 范围的大整数保持数学精度。
- 不经过 `double`、`float` 或 `asDouble`。
- 对象字段按名称排序；数组保持业务顺序。
- `queriedAt` 等既有易变字段不参与 Hash。

规范化只影响 Hash 计算，不修改实际 `contextSnapshot` JSON。阶段 2A 尚未发布时冻结了这一数值契约；未修改 `CONTEXT_SCHEMA_VERSION`、JSON Schema、数据库结构，也未重写历史任务。

PostgreSQL JSONB 往返可能不保留小数的原始 scale，但语义相同的数值、快照和 Hash 保持稳定。

## 10. 自动化测试结果

| 验证 | 结果 |
|---|---|
| `quant-core` 完整测试 | 1项，0失败 |
| 阶段 2A 定向组合 | 21项，0失败：Hash 13、Snapshot 7、PostgreSQL生产流 1 |
| 带专用数据库变量的完整 Agent 测试 | 155项，0失败，1项跳过 |
| 独立无数据库变量 Agent 测试 | 150项，0失败，9项安全跳过 |
| 独立无变量 `quant-server` 全量测试 | 151项，0失败，9项跳过 |
| Python `compileall` | 通过 |
| Python `unittest discover` | 33/33通过 |
| Vue生产构建 | 通过 |
| `git diff --check` | 通过 |

核心 Java 命令包括：

```powershell
.\mvnw.cmd -o -pl quant-core test

.\mvnw.cmd --% -o -pl quant-server -am -Dtest=AgentContextHashServiceTest,AgentContextSnapshotServiceTest,AgentReadonlyContextPostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test

.\mvnw.cmd --% -o -pl quant-server -am -Dtest=*Agent*Test -Dsurefire.failIfNoSpecifiedTests=false test

.\mvnw.cmd -o -pl quant-server -am test
```

Python 权威回归命令为现有虚拟环境中的 `compileall` 和 `unittest discover`。仓库没有声明 pytest 依赖，本阶段未安装 pytest、未修改 Python 依赖，也未将 pytest 缺失视为阶段失败。

Vue 从 `quant-web` 执行 `npm run build`，生产构建通过；现有大包提示为非阻断警告。

## 11. PostgreSQL JSONB Hash 验收

自动化 PostgreSQL 生产流的三方 Hash 为：

```text
538b4c64384c7fa4a7f768e3be99017616238022fde7a18cc209416c3e9704cf
```

真实本地受控任务的三方 Hash 为：

```text
45e88001f26da85e0298ba3fb2782ca8af6e1c535b988ab486bce2615cde7b8a
```

两次验收均确认以下三者完全一致：

1. API `contextHash`；
2. PostgreSQL `context_hash`；
3. 使用生产 `AgentContextHashService` 对持久化 JSONB 重新计算的 Hash。

API `contextSnapshot` 与数据库 JSONB 的业务语义也一致。

## 12. 本地真实闭环

正常用户 PowerShell 使用现有安全脚本启动成功：

- Python 健康检查 HTTP 200；
- Java 健康检查 HTTP 200，`database=UP`；
- Vue 首页 HTTP 200；
- Vue 代理健康检查 HTTP 200；
- `state.json` 成功创建；
- Python、Java、Vue 的根 PID 和实际 listener PID 均成功登记。

### 空数据任务

- `symbol=999999`
- `taskId=221`，验收后已精确删除
- `status=PARTIAL`
- `security.available=false`
- `marketData.available=false`
- `technicalMetrics.available=false`
- `dataQualityContext.available=true`
- `runCount=6`
- `chiefRunCount=0`
- evidence数量为0
- veto数量为0
- `decision=BLOCKED_BY_DATA_QUALITY`

空数据没有产生虚构证券名称、价格、K线或指标。

### 受控数据任务

- `symbol=990001`
- `taskId=222`，验收后已精确删除
- 证券夹具1条
- QFQ日线夹具61条
- `security`、`marketData`、`technicalMetrics`、`dataQualityContext` 均为 `available=true`
- 其他五类上下文均为 `available=false`
- `runCount=6`
- `chiefRunCount=0`

实际技术指标：

| 指标 | 值 |
|---|---:|
| `ma5` | 15.9 |
| `ma20` | 15.15 |
| `ma60` | 13.15 |
| `rsi14` | 100 |
| `atr14` | 0.5 |
| `averageVolume20` | 150500.0 |
| `highestClose20` | 16.1 |

阶段 2A 没有升级 Python 六智能体规则，因此六个 run 仍按现有骨架返回 `INSUFFICIENT_DATA`；这不代表已经产生真实投资结论。

## 13. 精确清理与数据库保护

- taskId 221和222均只通过 `DELETE FROM agent_tasks WHERE id = ?` 按ID删除。
- 关联 run、evidence、veto 和 decision 依赖 V5 外键级联精确清理。
- 受控任务的61条 QFQ 日线按 `symbol` 和 `adjust_type` 精确删除。
- 受控证券按 `symbol` 和 `data_source` 精确删除，共1条。
- 未使用 `TRUNCATE` 或无 `WHERE` 的 `DELETE`。
- 未删除或修改任务开始前已有的 Agent 数据。
- 最终五表计数恢复为 `agent_tasks/agent_runs/agent_evidence/agent_vetoes/agent_decisions = 2/12/0/0/2`。
- 阶段 2A 证券、日线和任务夹具最终均为0。

## 14. 安全停止结果

正常用户 PowerShell 最终停止结果：

```text
VUE_STOP_RESULT=stopped
JAVA_STOP_RESULT=stopped
PYTHON_STOP_RESULT=stopped
STATE_REMOVED=True
CLEANUP_SUCCEEDED=True
state.json=False
```

停止后 `state.json` 不存在，8001、8080、5173均无监听，状态中记录的六个 PID 均不存在。停止脚本没有接管或终止无关进程。

## 15. Codex 受控环境说明

Codex 受控执行环境读取 `Win32_Process` 时返回 `Access denied`，因此运行脚本在该环境中安全拒绝声称已证明 listener 与启动根进程的归属关系。正常用户 PowerShell 具备 CIM 读取权限，并完整通过启动、健康检查、任务闭环和安全停止。

当前运行脚本与 1D-4 验收基线完全一致，本阶段没有修改运行脚本。后续可以把 CIM 权限预检和错误分类作为独立工程改进，但它不是阶段 2A 生产上下文实现的一部分，也不构成本次产品阻断。

## 16. 已知限制

- `securities` 不是历史版本表，`pointInTimeGuaranteed=false`。
- `daily_bars` 没有逐条数据来源和更新时间，无法执行逐条跨源一致性判断。
- 当前没有交易日历，不能把周末或节假日直接判断为缺失交易日。
- `sourceConsistencyAssessable=false`。
- `marketBreadth`、`scanResult`、`backtestContext`、`securityEvents`、`portfolioContext` 仍未接入。
- 六智能体分析和评分规则没有在阶段 2A 升级。
- PostgreSQL 16.13 高于 Flyway 9.22.3 声明的已测试版本上限，当前为非阻断提示。
- 当前系统仍不具备真实投资建议、自动交易或自动下单能力。

## 17. 验收结论与下一阶段

阶段 2A 验收通过。完成的是现有 PostgreSQL 只读事实接入、Java 确定性指标、数据质量事实、Hash 与 JSONB 持久化稳定性；不代表六智能体真实分析能力或投资建议能力已经完成。

下一阶段唯一入口为：

**阶段 2B：DATA_QUALITY 规则门禁。**

阶段 2B 尚未开始。
