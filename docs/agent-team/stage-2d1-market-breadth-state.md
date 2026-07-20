# 阶段2D-1验收：当前证券池市场宽度状态规则

## 状态

阶段2D-1已完成实现、自动化回归、真实PostgreSQL/Python/Java闭环和独立验收。

来源分支：`codex/1.4.0-2d1-market-breadth-state`。

实现提交：`b956d02a65b5d5d27179013983ff4b501302fd47`。

本阶段只为 `MARKET_REGIME` 专业智能体实现“当前证券池市场宽度状态规则”。它不是完整市场环境模型，不是牛熊判断、市场涨跌预测、收益预测、交易信号或投资建议。阶段2D-1没有开始历史证券池治理，也没有开始阶段2E。

## 规则版本与兼容边界

- 新团队规则版本：`1.4.0-stage-2d-market-regime-v1`。
- `1.4.0-stage-2b-dq-v1` 行为保持不变：只执行阶段2B `DATA_QUALITY` 规则，`MARKET_REGIME` 和其余四个专业智能体继续返回未实现占位结果。
- 新版本完整复用阶段2B `DATA_QUALITY` 规则；只有门禁为 `PASS` 或 `WARN` 时才评估市场宽度。
- 未识别的规则版本继续安全关闭，不会自动回退到最新规则。
- 团队响应、六个 run 和 finalDecision 继续使用同一个团队级 `ruleVersion`。
- 未修改外层 `schemaVersion=1.0`、`CONTEXT_SCHEMA_VERSION=1.0`、contextHash 算法、数据库结构或外层 JSON Schema。

## 唯一业务输入

`MARKET_REGIME` 只消费冻结的 `contextSnapshot.marketBreadth`；`DATA_QUALITY` 结果只作为前置门禁。

以下上下文不参与该智能体的分类、score、confidence、finding、evidence 或 summary 推断：

- `marketData`
- `technicalMetrics`
- `scanResult`
- `backtestContext`
- `securityEvents`
- `portfolioContext`

自动化测试会分别改变 `marketData`、`technicalMetrics` 和 `scanResult`，并确认去除统一 `generatedAt` 后 `MARKET_REGIME` 输出完全不变。

## 当前日期资格

业务上的“当前日期”不读取运行时当前时间，而是由冻结请求确定：

- Python：`request.requestedAt.astimezone(ZoneInfo("Asia/Shanghai")).date()`。
- Java独立校验：`request.requestedAt().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()`。

只有 `request.tradeDate` 等于上述上海自然日时才允许分类。历史日期和未来日期均返回 `INSUFFICIENT_DATA / NOT_APPLICABLE`、score 0、confidence 0，并输出 `MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED`，不得伪装成无前视历史结果。

## 输入事实与固定来源语义

Python和Java均检查市场宽度的完整冻结字段，包括查询范围、来源、版本、日期、固定语义、计数、比例和限制说明。固定来源契约为：

- `sourceType=DATABASE`
- `sourceTables=["daily_bars", "securities"]`
- `producer=AgentMarketBreadthContextService`
- `producerVersion=MARKET_BREADTH_V1`
- `versionAvailable=true`
- `adjustType=QFQ`
- `selectionRule=CURRENT_MAIN_ACTIVE_NON_ST_UNIVERSE_UNIFIED_EFFECTIVE_DATE`
- `barFutureDataExcluded=true`
- `pointInTimeGuaranteed=false`
- `universePointInTimeGuaranteed=false`
- `futureDataExcluded=false`
- `timestampTimezoneSemantics=TRADE_DATES_ARE_LOCAL_DATE_QUERIED_AT_IS_UTC_INSTANT`
- `limitations=["CURRENT_SECURITIES_ATTRIBUTES_ARE_NOT_HISTORICALLY_VERSIONED"]`

所有计数必须为非负整数，并满足：

- `0 <= comparableSymbolCount <= coveredSymbolCount <= universeCount`
- `advancingCount + decliningCount + unchangedCount = comparableSymbolCount`
- `coveredSymbolCount + missingCurrentBarCount = universeCount`
- `comparableSymbolCount + missingPreviousBarCount = coveredSymbolCount`

`coverageRatio` 由 `comparableSymbolCount / universeCount` 独立重算，使用 Decimal/BigDecimal、8位小数和 `ROUND_HALF_UP`；所有规则运算不经过 double 或 float。来源、版本、日期、reasonCode、计数、比例或固定语义矛盾时，输出 `MARKET_BREADTH_FACT_INCONSISTENT`，不得产生方向 finding。

## 分类资格与公式

只有以下条件全部满足时才允许分类：

1. `DATA_QUALITY.gateStatus` 为 `PASS` 或 `WARN`。
2. `marketBreadth.available=true`。
3. 请求日期是冻结 `requestedAt` 的上海自然日。
4. `exactTradeDateMatch=true`。
5. `effectiveTradeDate=request.tradeDate`。
6. 来源、日期、计数、比例与固定语义全部一致。
7. `coverageRatio=1.00000000`。
8. `comparableSymbolCount>=2`。
9. `barFutureDataExcluded=true`。
10. `producerVersion=MARKET_BREADTH_V1`。

冻结门槛为：

- `MIN_COVERAGE_RATIO=1.00000000`
- `MIN_COMPARABLE_SYMBOL_COUNT=2`

令 `C=comparableSymbolCount`、`A=advancingCount`、`D=decliningCount`、`N=unchangedCount`。比例均以8位小数、`ROUND_HALF_UP` 计算：

- `advanceRatio=A/C`
- `declineRatio=D/C`
- `unchangedRatio=N/C`
- `netBreadthRatio=(A-D)/C`

分类只有三种受限宽度事实：

- `netBreadthRatio > 0`：`MARKET_BREADTH_POSITIVE`
- `netBreadthRatio = 0`：`MARKET_BREADTH_MIXED`
- `netBreadthRatio < 0`：`MARKET_BREADTH_NEGATIVE`

没有未经评测的 dead-band。score 为：

`ROUND_HALF_UP((netBreadthRatio + 1) * 50)`

score 是0到100的整数，只表示本次冻结的当前证券池上涨与下跌数量平衡，不表示预期收益、买入概率、风险等级或投资建议。由于 `MARKET_BREADTH_V1` 无法保证历史证券池时点属性，confidence 固定为0。

## 状态与安全降级

- `DATA_QUALITY=BLOCKED`：不执行宽度规则；`MARKET_REGIME` 返回 `INSUFFICIENT_DATA / NOT_APPLICABLE`，无 findings、evidence 或 errors。
- 上下文无法安全解析：返回 `INSUFFICIENT_DATA / NOT_APPLICABLE`，唯一错误为 `MARKET_BREADTH_INPUT_INVALID`，无 findings 或 evidence。
- 无可用宽度事实、证券池为0或可比较数为0：`MARKET_BREADTH_UNAVAILABLE`。
- 有可比较事实但覆盖不足或可比较证券少于2只：`MARKET_BREADTH_LOW_COVERAGE`。
- 交易日未精确命中：`MARKET_BREADTH_DATE_NOT_EXACT`。
- 请求不是冻结时的上海自然日：`MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED`。
- 有效分类：`status=COMPLETED`、`decision=WARN`、门禁继承 `DATA_QUALITY`、confidence 0，并且恰好包含时点限制 finding 和一个方向 finding。

缺数、低覆盖率和无法验证的时点不会被解释成中性、负向或低分市场。`MARKET_REGIME.veto` 始终为 false；正式 veto 仍只允许来自 `POSITION_RISK`。

## finding契约

允许的 code 和固定顺序为：

1. `MARKET_BREADTH_FACT_INCONSISTENT`（HIGH）
2. `MARKET_BREADTH_UNAVAILABLE`（WARN）
3. `MARKET_BREADTH_LOW_COVERAGE`（WARN）
4. `MARKET_BREADTH_DATE_NOT_EXACT`（WARN）
5. `MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED`（WARN）
6. `MARKET_BREADTH_POSITIVE`（INFO）
7. `MARKET_BREADTH_MIXED`（INFO）
8. `MARKET_BREADTH_NEGATIVE`（INFO）

code不得重复；阻断分类的 finding 与方向 finding 互斥。有效分类恰好包含第5项和第6至8项中的一项。findingId 为确定性格式 `mr-{两位顺序号}-{小写code}-{contextHash}`，不使用随机UUID。title与detail使用固定模板，只描述冻结事实和限制。

## evidence契约

`MARKET_REGIME` 最多生成一条证据：

- `evidenceId=mr-breadth-{contextHash}`
- `category=MARKET_BREADTH`
- `sourceType=JAVA_ENGINE`
- `sourceName=AgentMarketBreadthContextService`
- `sourceRef=contextSnapshot.marketBreadth`
- `symbol=request.symbol`
- `tradeDate=request.tradeDate`
- `observedAt=marketBreadth.queriedAt`
- `collectedAt=request.requestedAt`
- `contentHash=request.contextHash`

`fields` 只包含 `marketBreadth` 对象及以下白名单字段：

- `available`、`reasonCode`、`sourceType`、`sourceTables`、`sourceStatus`
- `producer`、`producerVersion`、`versionAvailable`
- `requestedTradeDate`、`effectiveTradeDate`、`previousEffectiveTradeDate`、`exactTradeDateMatch`
- `pointInTimeGuaranteed`、`barFutureDataExcluded`、`universePointInTimeGuaranteed`、`futureDataExcluded`
- `timestampTimezoneSemantics`、`adjustType`、`selectionRule`
- `universeCount`、`coveredSymbolCount`、`comparableSymbolCount`
- `advancingCount`、`decliningCount`、`unchangedCount`
- `missingCurrentBarCount`、`missingPreviousBarCount`、`coverageRatio`、`limitations`

证据不包含 `queriedAt`、`queryScope`、自然语言 reason、decision、gateStatus、score、confidence、finding结论、scanResult、个股行情或技术指标。顶层证据顺序固定为 `DATA_QUALITY` 后 `MARKET_BREADTH`；run内证据必须是顶层同一 evidenceId 的完全相同对象。

## 总控行为

阶段2D-1之后仍有四个专业智能体未实现，因此团队结论不得提前升级：

- `DATA_QUALITY=BLOCKED` 时保持 `BLOCKED_BY_DATA_QUALITY`。
- `DATA_QUALITY=PASS/WARN` 时保持 `finalDecision=INSUFFICIENT_DATA`，gateStatus精确继承质量门禁，score 0、confidence 0、vetoed false。
- final findings 按 `DATA_QUALITY` 后 `MARKET_REGIME` 精确拼接。
- sourceRunIds 继续恰好包含Java预分配的六个专业runId并保持固定顺序。
- 顶层 vetoIds 为空；总控仍不是第七个 run。

不会输出 `WATCH`、`RESEARCH_ONLY`、`PASS_TO_MANUAL_REVIEW`、`REJECTED_BY_VETO`、投资建议或交易指令。

## Java双重校验

`AgentResponseValidator` 对 `1.4.0-stage-2d-market-regime-v1` 使用独立严格分支，在持久化前重新验证：

- 阶段2B `DATA_QUALITY` 的完整输出契约和阻断先决条件。
- MARKET_REGIME身份、状态组合、门禁继承、veto=false、score和confidence。
- 上海冻结日期、市场宽度字段类型、来源、版本、日期、reasonCode和固定语义。
- 全部计数关系、8位HALF_UP覆盖率、最小可比较数、方向和精确score。
- finding白名单、数量、顺序、severity、固定文案、确定性ID和证据引用。
- evidence元数据、精确字段白名单、字段值、contentHash和顶层相同子集。
- 顶层evidence按 `DATA_QUALITY` 后 `MARKET_BREADTH` 排序、findings按DQ后MR排序、六run顺序和安全finalDecision。
- 其余四个专业智能体继续未实现，旧阶段2B响应继续兼容。

## 自动化测试结果

实现提交完成了以下非数据库自动化回归：

- Python compileall：通过。
- Python阶段2D规则、跨语言和一致性定向：33项，0失败。
- Python `unittest discover` 全量：68项，0失败。
- Java `AgentStage2DResponseValidatorTest`：8项，0失败、0错误、0跳过。
- Java跨语言与结果一致性：21项，0失败、0错误、0跳过。
- Java阶段2B兼容：13项，0失败、0错误、0跳过。
- 完整Agent回归：189项，0失败、0错误、11项按环境门禁跳过。
- quant-server全量：190项，0失败、0错误、11项按环境门禁跳过。
- quant-core全量：1项，0失败、0错误、0跳过。
- `git diff --check`：通过。

共享契约包含正向、混合、负向、DATA_QUALITY阻断、覆盖不足及非法响应样例。合法响应由实际Python编排输出冻结；Python与Java共同读取同一组fixture。非法响应将MARKET_REGIME confidence改为1，Python模型与Java校验均拒绝。

## 真实PostgreSQL/Python/Java闭环验收

真实闭环使用专用 PostgreSQL 数据库和用户 `stock_quant_test`，并调用监听于 `127.0.0.1:8001` 的真实 `quant-ai` 服务；成功响应不是 mock 或共享 fixture 替代响应。

真实成功路径 `AgentStage2DPostgresPythonIntegrationTest` 的结果为 `1/0/0/0`，`BUILD SUCCESS`。该测试确认：

- Java冻结真实 `contextSnapshot` 并调用真实Python。
- 任务终态为 `PARTIAL`，六个专业run完成持久化。
- `MARKET_REGIME` 为 `COMPLETED/WARN`、confidence为0，包含 `MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED` 和一个正向、混合或负向宽度finding。
- 顶层evidence严格按 `DATA_QUALITY` 后 `MARKET_BREADTH` 排序，总控仍为 `finalDecision=INSUFFICIENT_DATA`，正式veto为0。
- API快照、PostgreSQL JSONB完整语义和生产 `AgentContextHashService` 重算结果一致。
- 测试任务、证券和日线被精确清理，Agent五表、`securities` 和 `daily_bars` 恢复测试前基线。

非法响应原子失败 `AgentInvalidResponsePostgresIntegrationTest` 的结果为 `7/0/0/0`，`BUILD SUCCESS`。控制台中的 `AgentResponseValidationException` 和JSON解析异常是测试主动构造的非法响应，不是验收失败。阶段2D非法响应场景确认：

- Java拒绝非法 `MARKET_REGIME` 响应，任务进入 `FAILED`，六个run进入安全失败状态。
- `agent_evidence`、`agent_vetoes` 和 `agent_decisions` 均为0。
- 错误信息不泄漏响应体、`contextSnapshot` 或凭据；重复执行FAILED任务不会再次调用HTTP。
- 测试任务最终被精确清理，相关表恢复测试前基线。

闭环依赖测试自身的身份安全门、事务与 `finally` 精确清理；未使用TRUNCATE、无WHERE DELETE、Flyway变更或手工清库来获得通过。

## 已知限制与后续边界

- `securities` 是当前证券池，不是历史版本表；`pointInTimeGuaranteed` 与 `universePointInTimeGuaranteed` 均为 false。
- `futureDataExcluded=false`，因此历史请求不能进行无前视分类；本阶段只允许冻结请求时的上海自然日。
- 完整覆盖门槛会在本地数据缺失时安全降级，不会自动从外部补数。
- confidence固定为0；score只描述横截面上涨/下跌数量平衡。
- `marketData`、`technicalMetrics` 和 `scanResult` 均不参与本规则。
- 其余四个专业智能体仍未实现，团队仍不能形成完整分析或投资建议。
- 历史证券池版本化、历史样例无前视回放与评测集治理尚未开始，必须作为阶段2D-2的独立治理任务处理。

阶段2D-1完成不等于完整阶段2D完成。完整阶段2D仍处于进行中；阶段2D-2和阶段2E均未开始，本次验收没有开始相关代码开发。
