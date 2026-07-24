# 2F 可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1

## 1. 文档状态与阶段边界

状态：**任务分支实现与 Codex 本地验证完成 / 尚未经过 ChatGPT 基于实际 Git
commit 的验收 / 尚未合入**。

任务分支：`codex/1.4.0-stage-2f-strategy-backtest-v1`

冻结集成基线：`1b6eb8c65a39bdae6b6e1fbd6d43743be881bed4`

团队规则版本：`1.4.0-stage-2f-strategy-backtest-v1`

本任务书覆盖完整大阶段 2F。2F-0、knowledge-time/PIT、canonical Hash、
`backtestContext`、Python 规则、Java 响应校验、自动化测试和真实 PostgreSQL
验收是同一大阶段的内部工作包，不单独提交、推送或验收。当前真实状态以
[CURRENT_STATE.md](../CURRENT_STATE.md) 为唯一权威来源，路线方向以
[ROADMAP.md](../ROADMAP.md) 为准，跨阶段稳定决策以
[DECISIONS.md](../DECISIONS.md) 为准。实现和本地验证证据见
[stage-2f-strategy-backtest-v1.md](../stage-2f-strategy-backtest-v1.md)。

完成本阶段不表示公告风险、仓位风险、正式 veto、投资建议、交易执行、
2G、2H 或 2I 已经开始。

## 2. 冻结基线的仓库事实

1. `AgentContextSnapshotService` 外层 `schemaVersion=1.0`，固定生成九类上下文；
   旧入口的 `backtestContext.available=false`，reasonCode 为
   `BACKTEST_INPUT_CUTOFF_UNVERIFIABLE`。
2. `AgentContextReadRepository.findQfqDailyBars` 只读取截止请求日期的 61 条
   当前态 QFQ 日线，服务于技术指标，不是可靠回测窗口。
3. `daily_bars` 是当前态兼容投影；`MarketDataService.persistBars` 通过
   `ON CONFLICT ... DO UPDATE` 覆盖同证券、日期和复权类型的旧值。
4. 基线没有日线首次观察时间、knowledge-time、来源修订或不可变观察版本，
   不能排除后续同步、公司行动或 QFQ 重算对历史值的覆盖。
5. V1 `backtest_runs` 及 V3 `scan_backtest_tasks/results` 只保存旧任务或汇总，
   没有完整输入、截止时间、策略/引擎版本和可回放 lineage，不能成为权威输入。
6. 旧 `MarketDataService.localHistory(symbol, days)` 没有业务日期截止参数；
   Agent 读取不得调用它，也不得触发网络。
7. `BacktestModels.Request` 已有七项参数。基线 `BacktestEngine` 使用 SMA20、
   下一交易日开盘入场、止损、止盈、移动止损、最大持有期、佣金、印花税和
   100 股整数交易，但缺少可持久化版本和完整输入校验。
8. 基线 Python `StrategyBacktestAgent` 仍是安全不可用占位；Python
   `ContextSnapshot.backtestContext` 可承载结构化扩展。
9. 既有任务持久化已经具备六个固定 run、JSONB 上下文、contextHash、缓存键、
   Java 响应校验和结果事务原子性。
10. 2B、2D-1、2E-1 的既有规则、上下文 Hash 和缓存行为必须由旧入口保留。

## 3. 最终目标

本阶段一次性建立：

- append-only 日线观察批次和观察版本；
- 截止历史 knowledge-time 的稳定 as-of 选择；
- 可审计、可 Hash、可回放的 `BACKTEST_CONTEXT_V1`；
- `AGENT_CONTEXT_2F_V1` 与旧上下文入口并存；
- 策略、引擎、参数和数据版本的完整 lineage；
- `BACKTEST_CANONICAL_V1` 三个领域 Hash；
- Java 权威回测和 EARLY/MIDDLE/LATE 稳定性事实；
- Python 只解释 Java 事实的 `STRATEGY_BACKTEST` V1；
- Java/Python 双重验证、JSONB、原子失败、真实 PostgreSQL 和旧版本回归。

## 4. V9 PIT 日线观察模型

新增但不修改 V1 至 V8：

`V9__backtest_pit_daily_bar_observations.sql`

### 4.1 `market_data_observation_batches`

每个成功本地持久化批次记录不可变物理 ID、稳定批次版本、来源代码、本地数据集
版本、捕获类型、观察时间、记录时间、输入记录数和来源元数据。批次只追加，
禁止 `UPDATE`、`DELETE` 和 `TRUNCATE`。

### 4.2 `daily_bar_observations`

每个观察版本记录：

- 稳定物理 ID 与逻辑观察版本；
- `symbol`、`tradeDate`、`adjustType=QFQ`；
- OHLC、volume、amount、turnoverRate；
- source、可空 source revision；
- batch version、dataset version；
- first observed time、known time、recorded time；
- canonical content hash。

表只追加，禁止 `UPDATE`、`DELETE` 和 `TRUNCATE`。相同来源下连续重复的相同内容
保持幂等；内容变化以及 A→B→A 的再次变化必须形成新版本。as-of 查询按
`knownAt<=knowledgeCutoff` 选择每个交易日当时最新可见版本，并用冻结逻辑版本
稳定打破相同 `knownAt` 的顺序。

### 4.3 捕获事务

`MarketDataService` 的成功本地持久化委托给独立事务服务，在同一事务内：

1. 记录本地观察批次；
2. 对每条 bar 加事务级稳定并发锁；
3. 仅在相对最后观察内容变化时追加版本；
4. 更新 `daily_bars` 当前态兼容投影。

任一步失败时整个持久化事务回滚。Agent 上下文读取只访问本地 PostgreSQL，
不触发网络；Python 不访问数据库。

已有 `daily_bars` 不得由迁移时间伪装成历史首次观察。若未来执行
`BOOTSTRAP_CURRENT_STATE`，其 `knownAt` 只能是真实捕获时间，不能证明更早时点
可知。

## 5. Knowledge-time/PIT 契约

冻结市场时区 `Asia/Shanghai`，V1 只支持日线收盘后研究：

- `requestTradeDate`：用户请求研究的业务日期；
- `decisionTime`：请求日期在上海时区的日终；
- `knowledgeCutoff=decisionTime`；
- 非交易日可选择请求日期以前最近的有效输入交易日，cutoff 仍为请求日期日终；
- 未来请求日期安全不可用；
- 每条输入同时满足 `tradeDate<=requestTradeDate` 和
  `knownAt<=knowledgeCutoff`。

只满足业务日期不能声明无前视。内容 Hash 只能证明内容稳定，不能证明历史时点
可得。迟到数据、cutoff 后覆盖、来源修订、未来公司行动导致的 QFQ 变化和同一
交易日不同 knowledge-time 版本，都必须由观察版本隔离或返回稳定不可用状态。

## 6. 上下文 profile 与兼容

- 旧入口继续生成原 `backtestContext.available=false`，旧规则版本的
  contextSnapshot、contextHash 和缓存键不变。
- 仅规则版本 `1.4.0-stage-2f-strategy-backtest-v1` 选择
  `AGENT_CONTEXT_2F_V1`。
- 新嵌套 Schema 为 `BACKTEST_CONTEXT_V1`；外层 `schemaVersion=1.0`
  和九类上下文结构保持兼容。
- 六个固定专业 run 不变，总控不是第七个 run。
- DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS 既有规则语义继续复用。
- POSITION_RISK 仍是唯一允许形成正式 veto 的专业智能体；本阶段不实现它。

## 7. 可靠输入与回测语义

可靠输入只读取本地 PIT 观察版本：

- `adjustType=QFQ`；
- 截止请求日期和 knowledge cutoff；
- 按交易日期严格升序；
- 最多 500 条；
- 至少 120 条；
- 不使用技术指标的 61 条窗口；
- 不使用无截止参数的 `localHistory`；
- 不使用旧回测汇总表。

冻结策略与版本：

- strategy code/version：`SMA20_NEXT_OPEN_RISK_EXIT_V1`
- engine version：`BACKTEST_ENGINE_V1`
- parameter Schema：`BACKTEST_PARAMS_V1`

七项参数全部显式记录：

| 参数 | 冻结值 |
|---|---:|
| `initialCapital` | `100000` |
| `maxHoldingDays` | `10` |
| `stopLossPct` | `0.05` |
| `takeProfitPct` | `0.08` |
| `trailingStopPct` | `0.04` |
| `commissionRate` | `0.0003` |
| `stampDutyRate` | `0.0005` |

引擎保持基线策略含义：SMA20 收盘确认、下一交易日开盘进入、每次投入当时资本的
20%、100 股向下取整；同一 bar 退出优先级为止损、止盈、移动止损，未命中时按
最大持有期退出；买卖双边佣金、卖出印花税；所有计算使用确定性 BigDecimal
规则，所有日期不晚于请求截止。

## 8. 稳定性子区间

输入按交易日期升序稳定切分为不重叠的 `EARLY`、`MIDDLE`、`LATE`。基础长度为
`barCount/3`，余数从 EARLY 开始依次分配；120 条最低门槛保证每段均满足引擎
30 条最低输入。三段使用相同策略、引擎和参数，记录有效子区间数与正收益子区间
数。Python 不重新运行回测。

## 9. `backtestContext` 输出

可用上下文至少包含：

- available、reasonCode、producer/version、profile/Schema；
- symbol、request/effective date、decisionTime、knowledgeCutoff、timezone；
- 输入起止日期、bar 数量、QFQ 和完整 bars；
- PIT source、batch、dataset、revision、observed/known lineage；
- 独立数据版本对象；
- 策略、引擎、参数 Schema 和七项参数；
- 完整结果及有序交易明细；
- EARLY/MIDDLE/LATE 三个完整子结果；
- 有效/正收益子区间计数；
- canonical 版本和三个领域 Hash；
- PIT、未来数据排除、限制与安全声明。

业务不可用不得伪造零收益或中性回测。数据库或 SQL 失败使任务失败，不降级为
业务不可用。

## 10. `BACKTEST_CANONICAL_V1`

冻结：

- SHA-256，小写十六进制；
- UTF-8、Unicode NFC；
- 对象键按规范化字段名字典序；
- 数组保持契约规定的业务顺序；
- 日期为 ISO-8601；
- 时间为 UTC、`Z` 结尾、微秒精度；
- Decimal 使用普通十进制表示，禁止科学计数法，删除无意义尾零，`-0` 为 `0`；
- 拒绝 NaN 和 Infinity；
- 缺失与显式 `null` 不等价；
- 每个领域 Hash 只使用冻结白名单；
- 数据库物理 ID、创建时间、日志、进程和运行环境噪声不进入 Hash。

三个 Hash：

1. `inputDataHash`：profile/contract、证券、三类时间、窗口、独立数据版本、
   source/batch/revision/observed/known lineage 和完整有序 bars；
2. `strategyDefinitionHash`：strategy/engine/parameter Schema 和七项参数；
3. `backtestResultHash`：前两个 Hash 引用、完整汇总、三段结果、稳定性计数和
   全部有序交易。

数据版本对象独立表达 PIT 模型、batch/dataset versions、所选逻辑观察版本和
source revision，不以 `inputDataHash` 改名代替。

Java 是生产 Hash 权威方。Python 使用同一规则验证收到的结构和 Hash。固定黄金
夹具必须提交固定输入、固定 canonical 文本和固定预期 Hash；预期值不得由被测
实现运行时生成。

## 11. STRATEGY_BACKTEST 确定性规则 V1

Python 只解释 Java `backtestContext`，不拉取行情、不访问数据库、不重跑策略、
不寻优、不输出交易建议或收益承诺、不产生正式 veto。

### 11.1 安全降级

- DATA_QUALITY BLOCKED：继承阻断，不生成正常 finding；
- context 不可用：`INSUFFICIENT_DATA`，score/confidence 为 0；
- Schema、版本、Hash、数值或交易明细非法：
  `STRATEGY_BACKTEST_INPUT_INVALID`；
- `tradeCount<10` 或有效子区间少于 2：
  `STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT`。

### 11.2 五类 finding

有效输入固定生成：样本充分性、总收益表现、最大回撤风险、胜率与盈亏比质量、
跨时间子区间稳定性。finding 和 evidence 只直接引用 Java 已冻结事实。

### 11.3 Score

从 50 开始并截断到 `[0,100]`：

- totalReturn：`>=0.15 +15`；`>=0.05 +10`；`>0 +5`；
  `<=-0.15 -20`；`<0 -10`；否则 0；
- maxDrawdown：`<=0.10 +10`；`>0.20 && <=0.30 -10`；
  `>0.30 -20`；否则 0；
- winRate：`>=0.55 +10`；`<0.45 -10`；否则 0；
- profitLossRatio：`>=1.50 +10`；`>=1.00 +5`；`<0.80 -10`；否则 0；
- positiveSubperiodCount：`3 +10`；`2 +5`；`1 -10`；`0 -20`。

### 11.4 Confidence 与总控

- 10–19 笔：40；
- 20–39 笔：60；
- 40 笔及以上：80；
- DATA_QUALITY WARN 时最高 50；
- 永不为 100。

gate 继承 DATA_QUALITY PASS/WARN。总控仍因 ANNOUNCEMENT_RISK 和 POSITION_RISK
未实现而保持安全不足，不升级为完整投资结论。

## 12. 稳定 reasonCode

Java/Python 契约至少冻结并区分：

- `BACKTEST_NO_TRUSTED_PIT_DAILY_BARS`
- `BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE`
- `BACKTEST_SOURCE_REVISION_UNVERIFIABLE`
- `BACKTEST_CUTOFF_POLLUTION_UNRESOLVED`
- `BACKTEST_SAMPLE_INSUFFICIENT`
- `BACKTEST_DAILY_BAR_INVALID`
- `BACKTEST_STRATEGY_VERSION_UNVERIFIABLE`
- `BACKTEST_PARAMS_INVALID`
- `BACKTEST_HASH_MISMATCH`
- `BACKTEST_REPLAY_MISMATCH`
- `STRATEGY_BACKTEST_INPUT_INVALID`
- `STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT`

数据库连接和 SQL 错误不得使用上述业务 reasonCode 掩盖。

## 13. 测试与验收

### 13.1 Java

覆盖 V9 结构、append-only、连续相同内容幂等、内容修订与 A→B→A、as-of cutoff、
cutoff 后覆盖、未来公司行动 QFQ 变化、来源修订、迟到数据、同日不同
knowledge-time、非交易日、乱序/重复/非法 OHLCV、500/120 边界、七参数、
引擎和退出优先级、三个子区间、三个 Hash、黄金向量、JSONB、contextHash、
新旧 profile、旧规则 Hash、失败原子性、缓存幂等和无业务副作用。

### 13.2 Python

覆盖 Context 模型、黄金向量、三个 Hash、五类 finding、全部 score/confidence
边界、DQ PASS/WARN/BLOCKED、不可用、非法输入、样本不足、无 veto、无投资建议
和旧版本回归。

### 13.3 Java/Python 与真实 PostgreSQL

真实闭环必须验证六 run、Java 生成 context、Python 解析、Java 独立响应校验、
evidence 顺序、JSONB、Hash、空正式 veto、原子失败和安全总控。

真实 PostgreSQL 使用专用 `stock_quant_test` 随机临时 Schema 运行 V1 至 V9，
相关测试 `Skipped=0`；验证真实 append-only、PIT 污染隔离、JSONB、精确 Schema
清理及 public 数据、对象、Flyway 历史/checksum 和扩展基线前后不变。不执行
Flyway clean/repair。环境门禁跳过必须与真实通过分开报告。

### 13.4 完整回归

运行 quant-core、quant-server、全部 Agent Java 测试、Python compileall 和完整
unittest、真实 Java/Python、真实 PostgreSQL，以及 `git diff --check`。

## 14. 明确禁止

不接入外部或付费来源，不在 Agent 读取中联网，不让 Python 访问数据库，不修改
V1 至 V8，不执行 Flyway clean/repair，不改生产扫描语义，不参数寻优，不使用
LLM 形成权威事实，不输出投资建议，不执行自动交易，不产生正式 veto，不开始
2G、2H、2I 或其他阶段。

## 15. 完成和验收边界

Codex 在一个任务分支内完成全部实现、测试、文档、自查、单次 commit 和普通
push。提交后由 ChatGPT 基于实际 Git commit 验收；未经用户批准不得 merge。
任务分支提交和推送不等于 ChatGPT 验收、用户 merge 批准或集成分支已具备本能力。
