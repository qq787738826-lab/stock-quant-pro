# 阶段 2E-1：TECHNICAL_ANALYSIS 确定性规则 V1

## 1. 状态与范围

状态：**已实现；Codex 本地验证通过；独立 GitHub 最终复审 PASS；已合入集成分支**。

团队规则版本：`1.4.0-stage-2e-technical-analysis-v1`。

实现提交：`93ccf7c6da380be91ca342f6c5e8815f8e7dfe07`。

集成合并提交：`adb781c3ffb41ff13a14538067e838a60a65bea9`。

本阶段只为 `TECHNICAL_ANALYSIS` 专业智能体解释 Java 已冻结到 `contextSnapshot` 的 `technicalMetrics` 与 `marketData`。它不拉取行情、不访问业务数据库、不在 Python 重算 SMA/RSI/ATR 或其他指标、不使用 LLM，也不输出买入、卖出、加仓、减仓、目标价、收益预测或收益承诺。

阶段 2E-1 完整复用阶段 2B `DATA_QUALITY` 门禁，并在新团队规则版本中原样承接阶段 2D-1 已冻结的 `MARKET_REGIME` 规则；本阶段不修改其字段、阈值、score、confidence、finding 或 evidence 契约。其余三个专业智能体仍保持未实现安全结果，总控继续为 `INSUFFICIENT_DATA` 或 `BLOCKED_BY_DATA_QUALITY`。

本阶段不修改外层 `schemaVersion=1.0`、`CONTEXT_SCHEMA_VERSION=1.0`、contextHash 算法、数据库持久化结构、Flyway 或 V1 至 V8。

## 2. 真实输入审计

### 2.1 `technicalMetrics`

权威生产者为 Java `AgentTechnicalMetricsService`，公式版本为 `JAVA_INDICATORS_V1`。Java 在同一个 `REPEATABLE_READ` 只读上下文事务内使用截止请求日期、按日期升序排列的本地 QFQ 日线计算指标；所需样本与最大读取数均为 61 条。

冻结元数据：

- `available: boolean`
- `queriedAt: UTC Instant`
- `queryScope.symbol: 6 位证券代码`
- `queryScope.tradeDate: LocalDate`
- `formulaVersion=JAVA_INDICATORS_V1`
- `adjustType=QFQ`
- `requestedTradeDate: LocalDate`
- `effectiveTradeDate: LocalDate | null`
- `requiredBars=61`
- `actualBars: integer`

冻结窗口：

- `ma5=5`
- `ma20=20`
- `ma60=60`
- `rsi14=14`
- `atr14=14`
- `averageVolume20=20`
- `highestClose20=20`

冻结值：

| 字段 | Java 算法 | 单位与 NULL 语义 | V1 用途 |
|---|---|---|---|
| `ma5` | 最后 5 条 close 的算术平均，6 位小数 `HALF_UP` | QFQ 价格单位；可用时非 NULL、必须大于 0 | 趋势 |
| `ma20` | 最后 20 条 close 的算术平均，6 位小数 `HALF_UP` | QFQ 价格单位；可用时非 NULL、必须大于 0 | 趋势、价格偏离 |
| `ma60` | 最后 60 条 close 的算术平均，6 位小数 `HALF_UP` | QFQ 价格单位；可用时非 NULL、必须大于 0 | 趋势 |
| `rsi14` | 最后 14 个 close 变化的 gain/loss 比率；无 loss 时为 100 | 0 至 100 指标点；可用时非 NULL | 动量与超买/超卖风险 |
| `atr14` | 最后 14 条 true range 的算术平均，6 位小数 `HALF_UP` | QFQ 价格单位；可用时非 NULL、不得为负 | 相对波动 |
| `averageVolume20` | 最后 20 条 volume 的算术平均，6 位小数 `HALF_UP` | 与 `daily_bars.volume` 相同的未换算整数单位；可用时非 NULL、不得为负 | V1 只审计，不评分 |
| `highestClose20` | 最后 20 条 close 的最大值 | QFQ 价格单位；可用时非 NULL、必须大于 0 | V1 只审计，不评分 |

`averageVolume20` 没有冻结为“手”或“股”等业务单位，V1 不据此设置量能阈值。`highestClose20` 可验证 Java 指标完整性，但 V1 不把“接近新高”转换成方向分或交易含义。

### 2.2 `marketData`

Java `AgentContextReadRepository` 只读查询 `daily_bars`：`symbol` 精确匹配、`adjust_type='QFQ'`、`trade_date<=request.tradeDate`、按日期倒序限制 61 条后再升序冻结。每条 bar 的真实字段为：

- `symbol: string`
- `tradeDate: LocalDate`
- `open/high/low/close: decimal`，必须为正且满足 OHLC 关系
- `volume: non-negative long`
- `amount: decimal | null`
- `turnoverRate: decimal | null`

V1 只读取最后一条冻结 bar 的 `close`，用于相对 `ma20` 的偏离和 `atr14/close`；不根据 bars 在 Python 重算任何指标。`amount`、`turnoverRate`、open/high/low 和 volume 不参与 score。

### 2.3 输入一致性门槛

在 `DATA_QUALITY=PASS/WARN` 后，TECHNICAL_ANALYSIS 仍必须独立验证：

1. 两个上下文均 `available=true`，queryScope 与 Java 请求一致；
2. `formulaVersion=JAVA_INDICATORS_V1`、`adjustType=QFQ`、窗口字段精确匹配；
3. `requiredBars=actualBars=bars.length=61`；
4. bars 按日期严格递增、symbol 一致、没有请求日期之后的数据，最后日期等于两个上下文的 `effectiveTradeDate`；
5. `requestedTradeDate` 与请求一致，`exactTradeDateMatch` 与最后日期可互相验证；
6. 两个 `queriedAt` 按微秒精度一致；
7. 七个指标值均为有限 JSON 数值；价格类为正、RSI 在 0 至 100、ATR 与平均量非负；
8. NULL、NaN、Infinity、字符串冒充数值、非法 OHLC、重复/乱序日期或字段缺失均拒绝；
9. `highestClose20>=latest close`，不得接受与 Java 定义直接矛盾的事实。

无法满足时返回 `TECHNICAL_ANALYSIS_INPUT_INVALID`，不形成技术 evidence、finding、中性结论或正常 score。

## 3. 确定性分类与阈值

所有比较使用 Python `Decimal` 与 Java `BigDecimal`，不经过二进制浮点规则计算。展示值中价格与指标固定 6 位小数，百分比固定 4 位小数并使用 `HALF_UP`；阈值比较使用未量化的精确十进制值。

### 3.1 趋势

| reasonCode（`Finding.code`） | 条件 | severity | score impact |
|---|---|---|---:|
| `TECH_TREND_BULLISH_ALIGNED` | `ma5 > ma20 > ma60` | INFO | +20 |
| `TECH_TREND_BEARISH_ALIGNED` | `ma5 < ma20 < ma60` | WARN | -20 |
| `TECH_TREND_MIXED` | 其他情况，包括任意均线相等 | WARN | 0 |

### 3.2 RSI 动量与超买/超卖风险

| reasonCode | 条件 | severity | score impact |
|---|---|---|---:|
| `TECH_RSI_OVERBOUGHT_RISK` | `rsi14 >= 70` | WARN | -10 |
| `TECH_RSI_POSITIVE_MOMENTUM` | `50 < rsi14 < 70` | INFO | +15 |
| `TECH_RSI_NEUTRAL` | `rsi14 = 50` | INFO | 0 |
| `TECH_RSI_NEGATIVE_MOMENTUM` | `30 < rsi14 < 50` | WARN | -15 |
| `TECH_RSI_OVERSOLD_RISK` | `rsi14 <= 30` | WARN | -10 |

超买与超卖都只表示阈值风险，不推导反转，不生成买卖结论。

### 3.3 相对 MA20 偏离

定义 `deviation=(latestClose-ma20)/ma20`。

| reasonCode | 条件 | severity | score impact |
|---|---|---|---:|
| `TECH_PRICE_ABOVE_MA20_EXTENDED` | `deviation >= 10%` | WARN | -10 |
| `TECH_PRICE_BELOW_MA20_EXTENDED` | `deviation <= -10%` | WARN | -10 |
| `TECH_PRICE_NEAR_MA20` | `-10% < deviation < 10%` | INFO | 0 |

### 3.4 相对波动

定义 `atrRatio=atr14/latestClose`。

| reasonCode | 条件 | severity | score impact |
|---|---|---|---:|
| `TECH_VOLATILITY_ELEVATED` | `atrRatio >= 5%` | WARN | -10 |
| `TECH_VOLATILITY_NORMAL` | `atrRatio < 5%` | INFO | 0 |

“NORMAL”只表示低于本规则阈值，不表示证券安全、低风险或适合买入。

### 3.5 指标确认与冲突

| reasonCode | 条件 | severity | score impact |
|---|---|---|---:|
| `TECH_INDICATORS_BULLISH_CONFIRMED` | 多头均线、`50<RSI<70` 且 `latestClose>ma20` | INFO | +15 |
| `TECH_INDICATORS_BEARISH_CONFIRMED` | 空头均线、`30<RSI<50` 且 `latestClose<ma20` | WARN | -15 |
| `TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED` | 其他组合 | WARN | 0 |

## 4. score、门禁与总控语义

技术状态 score 从 50 开始，累加恰好五个分类 finding 的 impact，最后截断到 `[0,100]`：

`score = min(100, max(0, 50 + sum(scoreImpact)))`

score 只编码本规则下冻结指标的相对方向、一致性与风险扣分，不表示上涨概率、预期收益、评级、交易信号或人工审批结果。

- `DATA_QUALITY=BLOCKED`：TECHNICAL_ANALYSIS 不执行，返回 `INSUFFICIENT_DATA / NOT_APPLICABLE / NOT_APPLICABLE / 0 / 0`，无 findings、evidence 或 errors。
- `DATA_QUALITY=PASS` 且输入有效：`COMPLETED / PASS / WARN`，score 按公式，confidence 100。
- `DATA_QUALITY=WARN` 且输入有效：`COMPLETED / WARN / WARN`，score 不改写，confidence 50。
- `DATA_QUALITY=PASS/WARN` 但技术输入非法：`INSUFFICIENT_DATA`，继承 gate，`NOT_APPLICABLE / 0 / 0`，唯一错误为 `TECHNICAL_ANALYSIS_INPUT_INVALID`。

TECHNICAL_ANALYSIS 的 `veto` 永远为 false，不创建 `agent_vetoes`。正式 veto 仍只允许来自 `POSITION_RISK`。

新团队版本在 DQ 未阻断时继续原样运行阶段 2D-1 MARKET_REGIME，并运行本阶段 TECHNICAL_ANALYSIS；其余三个专业智能体仍未实现。final findings 和顶层 evidence 分别按 `DATA_QUALITY -> MARKET_REGIME -> TECHNICAL_ANALYSIS` 顺序拼接。总控保持 `finalDecision=INSUFFICIENT_DATA`、score 0、confidence 0、vetoed false；DQ 阻断时仍为 `BLOCKED_BY_DATA_QUALITY`。

## 5. finding 契约

`Finding.code` 是本阶段稳定 reasonCode。允许代码按以下固定全局顺序排列：

1. `TECH_TREND_BULLISH_ALIGNED`
2. `TECH_TREND_MIXED`
3. `TECH_TREND_BEARISH_ALIGNED`
4. `TECH_RSI_OVERBOUGHT_RISK`
5. `TECH_RSI_POSITIVE_MOMENTUM`
6. `TECH_RSI_NEUTRAL`
7. `TECH_RSI_NEGATIVE_MOMENTUM`
8. `TECH_RSI_OVERSOLD_RISK`
9. `TECH_PRICE_ABOVE_MA20_EXTENDED`
10. `TECH_PRICE_NEAR_MA20`
11. `TECH_PRICE_BELOW_MA20_EXTENDED`
12. `TECH_VOLATILITY_ELEVATED`
13. `TECH_VOLATILITY_NORMAL`
14. `TECH_INDICATORS_BULLISH_CONFIRMED`
15. `TECH_INDICATORS_BEARISH_CONFIRMED`
16. `TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED`

有效输入恰好输出五个 findings：趋势、RSI、偏离、波动、确认/冲突各一个。findingId 为 `ta-{两位全局顺序}-{小写reasonCode}-{contextHash}`。title 使用冻结模板；detail 必须包含实际 evidence 路径、规范化 observed value、阈值或判断条件和固定 score impact。

## 6. evidence 契约

有效输入恰好生成两条 evidence：

### 6.1 指标 evidence

- `evidenceId=ta-metrics-{contextHash}`
- `category=TECHNICAL_INDICATOR`
- `sourceType=JAVA_ENGINE`
- `sourceName=AgentTechnicalMetricsService`
- `sourceRef=contextSnapshot.technicalMetrics`
- `observedAt=technicalMetrics.queriedAt`
- `collectedAt=request.requestedAt`
- `contentHash=contextHash`
- `fields.technicalMetrics` 只包含 `available`、`formulaVersion`、`adjustType`、`requestedTradeDate`、`effectiveTradeDate`、`requiredBars`、`actualBars`、`windows` 和 `values`

### 6.2 最后 bar evidence

- `evidenceId=ta-market-{contextHash}`
- `category=MARKET_DATA`
- `sourceType=JAVA_ENGINE`
- `sourceName=AgentContextSnapshotService`
- `sourceRef=contextSnapshot.marketData`
- `observedAt=marketData.queriedAt`
- `collectedAt=request.requestedAt`
- `contentHash=contextHash`
- `fields.marketData` 只包含 `available`、`adjustType`、`requestedTradeDate`、`effectiveTradeDate`、`exactTradeDateMatch`、`actualBars` 和对 `bars[-1]` 的逐字段直接副本 `latestBar`

趋势与 RSI finding 只引用指标 evidence；偏离、波动和确认/冲突 finding 按指标、最后 bar 的顺序引用两条 evidence。evidence 不包含 gate、decision、score、confidence、finding、建议或 Python 派生比率。

## 7. Java/Python 双重校验与持久化

Python 负责确定性规则输出；Java 在持久化前独立验证：

- 团队规则版本、六 run 固定顺序与身份；
- 完整复用 DQ 与 MARKET_REGIME 既有契约；
- DATA_QUALITY 门禁对 TECHNICAL_ANALYSIS 的执行资格；
- 两个输入对象的类型、字段、日期、61 条样本、QFQ、公式版本、窗口和值域；
- 无请求日期之后的 bar，且不在 Java 或 Python 重新计算指标；
- 五类 finding 的唯一性、互斥性、固定顺序、severity、title、detail、deterministic ID 和 evidence 引用；
- Decimal/BigDecimal 阈值、score impact、截断和 confidence；
- 两条 evidence 的元数据、字段白名单、直接投影、contextHash 和微秒时间；
- 顶层 evidence/findings 顺序、安全总控、空正式 veto 和三个未实现 run。

`AgentResultPersistenceService.persist` 继续使用既有单事务写入六 run、evidence、veto、decision 和 task 终态。任何 Python JSON、跨语言契约或 Java 响应校验失败都发生在该事务之前，不得留下部分 evidence、veto 或 decision。

## 8. 测试矩阵

覆盖矩阵：

- 16 个 reasonCode 的正向分类及相邻阈值反向/边界；
- 趋势均线相等、RSI 30/50/70、偏离 ±10%、ATR 比例 5%；
- score 0、100 与截断边界；
- DQ PASS、WARN、BLOCKED；
- NULL、NaN、Infinity、字符串数值、缺字段、非法/乱序/未来 bar、样本不足和冲突；
- 同一 contextHash 的业务字段确定性；
- evidence 白名单、路径、observed value、reasonCode、规则版本和 contextHash；
- Java 请求、真实 Python 响应、Java 独立校验与六 run 结构；
- 非法 TECHNICAL_ANALYSIS 响应的 PostgreSQL 原子失败；
- 专用 `stock_quant_test` 随机临时 Schema 的真实 PostgreSQL 闭环，`Skipped=0`；
- 临时 Schema 精确删除，public 数据计数和结构指纹前后不变；
- Python compileall/unittest、quant-server、quant-core 与 `git diff --check`。

Codex 本地执行结果（不是 GitHub Actions CI）：

- Python `compileall` 通过，unittest `77/0/0`；TECHNICAL_ANALYSIS 定向测试覆盖全部 16 个 reasonCode、阈值、DQ 三路径、非法输入、evidence、确定性、score 上下界与无投资建议；
- 真实 Java 请求 → Python 响应 → Java 独立验证闭环 `4/0/0/0`、`Skipped=0`，覆盖 PASS、WARN、BLOCKED、非法输入、同一上下文确定性及篡改 score/finding/evidence/finalDecision 拒绝；
- 专用 `stock_quant_test` 中随机临时 Schema 的真实 PostgreSQL 闭环 `2/0/0/0`、`Skipped=0`，覆盖合法六 run 持久化和非法响应原子失败；
- `quant-server` 全量 `261/0/0/27`，其中 27 项是未设置外部集成环境变量时的门禁跳过，不是真实 PostgreSQL 或真实 Python 通过；上述两类真实闭环已分别单独以 `Skipped=0` 执行；
- `quant-core` 全量 `1/0/0/0`；
- 随机临时 Schema 已精确删除；public 表计数、关系/约束/触发器/函数指纹、Flyway 历史与 checksum、扩展集合前后不变；
- `git diff --check` 通过。

持久化成功路径保持六个 run 完整，TECHNICAL_ANALYSIS evidence 位于 DATA_QUALITY 与 MARKET_REGIME evidence 之后且不存在正式 veto。响应校验在持久化事务前完成；非法响应产生 FAILED 任务/运行状态，但不留下部分 evidence、veto 或 decision。

环境门禁跳过必须与真实 PostgreSQL/真实 Python 闭环分开报告，不得把 skip 冒充通过。

## 9. 明确未实现

本阶段不实现或不改变：外部行情/source adapter、FORMAL/PIT、V1 至 V8、public Schema、MARKET_REGIME V2、`MARKET_BREADTH_V1`、backtestContext、Universe、公告、持仓、POSITION_RISK 规则、正式 veto、总控升级、前端大改、LLM 权威逻辑、投资建议或自动交易。
