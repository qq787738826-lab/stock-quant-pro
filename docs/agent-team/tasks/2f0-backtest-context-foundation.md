# 2F 大阶段内部工作包 2F-0：可靠 backtestContext 审计与接入基础

## 1. 文档状态与权威边界

状态：**已在 2F 任务分支实现；不是独立阶段；尚待完整 2F 的 ChatGPT Git
commit 验收；尚未合入**。

2F-0 是“2F 可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1”的内部技术
前置工作包，不单独开发、Review、commit、push 或验收。本文件同步已经实现的
knowledge-time/PIT、版本、回放和 canonical Hash 契约，不把任务分支事实提前
描述成集成分支能力。

- 完整大阶段任务书：[2f-reliable-strategy-backtest-v1.md](2f-reliable-strategy-backtest-v1.md)
- 实现和本地验证记录：[../stage-2f-strategy-backtest-v1.md](../stage-2f-strategy-backtest-v1.md)
- 当前事实唯一权威：[../CURRENT_STATE.md](../CURRENT_STATE.md)
- 阶段方向：[../ROADMAP.md](../ROADMAP.md)
- 跨阶段决定：[../DECISIONS.md](../DECISIONS.md)
- 开发治理：[../../../AGENTS.md](../../../AGENTS.md)

## 2. 冻结基线审计与实现结论

冻结基线 `1b6eb8c65a39bdae6b6e1fbd6d43743be881bed4` 的审计结论：

1. 旧 `AgentContextSnapshotService.create(symbol, tradeDate)` 不接收规则版本，
   `backtestContext` 固定以
   `BACKTEST_INPUT_CUTOFF_UNVERIFIABLE` 安全不可用。
2. 旧 Agent 日线查询最多读取 61 条当前态 QFQ 数据，只证明
   `trade_date<=requestTradeDate`，不能证明历史 knowledge-time。
3. `daily_bars` 是可覆盖的当前态投影，没有首次观察、known time、source
   revision 或不可变 dataset version。
4. `backtest_runs`、`scan_backtest_tasks`、`scan_backtest_results` 都没有
   完整冻结输入、策略/引擎版本、七项参数及可回放 lineage，不可冒充权威输入。
5. 原 `BacktestEngine` 已有 SMA20、下一交易日开盘入场、止损/止盈/移动止损/
   最大持有期、费用及 100 股整数交易语义，但缺少稳定版本和严格输入校验。
6. 原外层 contextSnapshot 和 `contextHash` 已有 JSONB 往返与原子持久化基础，
   但没有三个回测领域 Hash。
7. Python 的 `StrategyBacktestAgent` 和 Java 响应校验只冻结到 2E-1，尚不能解释
   或验证可靠回测事实。

2F 任务分支据此实现：

- V9 append-only 本地 PIT 日线观察模型；
- 规则版本感知的上下文入口，旧入口保留；
- `AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1`；
- 最多 500、至少 120 条的 Agent 专用 as-of 查询；
- 冻结策略、引擎、完整参数、三子区间、回放自校验；
- `BACKTEST_CANONICAL_V1` 和三个领域 Hash；
- Python 结构/Hash 验证与确定性规则；
- Java 双重响应校验、JSONB、原子失败及真实 PostgreSQL 测试。

实现没有修改外层 `CONTEXT_SCHEMA_VERSION`，没有修改 V1 至 V8。旧规则仍使用
旧入口与原 contextHash/cache key。

## 3. Knowledge-Time/PIT Input Contract Gate

门禁状态：**已由用户批准的 2F 架构冻结，并在任务分支实现；待完整 2F 提交
验收**。

### 3.1 三类时间

- 市场时区固定为 `Asia/Shanghai`。
- `requestTradeDate` 是被研究的业务日期。
- `decisionTime` 是该请求日期上海时区日终最后一微秒。
- `knowledgeCutoff=decisionTime`。
- 非交易日可选择请求日期以前最近有效输入交易日，cutoff 仍是请求日期日终。
- 未来请求日期或当日日终尚未到达时安全不可用。

每条可靠输入必须同时满足：

- `tradeDate` 是周一至周五；
- `earliestDailyBarKnownAt=tradeDate` 当日 `15:00:00 Asia/Shanghai`；
- `firstObservedAt>=earliestDailyBarKnownAt`；
- `knownAt>=earliestDailyBarKnownAt`；
- `firstObservedAt<=knownAt<=recordedAt`；
- `tradeDate<=requestTradeDate`；
- `knownAt<=knowledgeCutoff`。

业务日期上界不能证明数据值在历史决策时点已知；内容 Hash 也只能证明给定内容
稳定，不能替代 knowledge-time 证据。周末日线直接拒绝；本阶段不引入新的正式
交易日历来源，法定节假日仍由实际来源和后续正式日历能力治理。

### 3.2 必须可审计的事实

可靠输入逐条保留：

- `firstObservedAt`、`knownAt` 与 `recordedAt`；
- source code 和可空 source revision；
- batch version、dataset version 和 observation version；
- capture type 与来源元数据；
- immutable canonical content hash；
- 同一交易日期在不同 knowledge-time 下的版本链。

来源没有 revision 时保存 `null`，不得伪造；任务分支 V1 要求可靠输入的
revision 可验证，否则返回
`BACKTEST_SOURCE_REVISION_UNVERIFIABLE`。本地观察时间明确不等于供应商发布时间。

### 3.3 污染隔离

以下情形必须由观察版本与 as-of 查询隔离，或安全返回 `available=false`：

1. cutoff 后覆盖 cutoff 前交易日的数据；
2. 未来公司行动或复权因子改变历史 QFQ；
3. 来源修订历史记录；
4. 迟到数据在 cutoff 后才首次观察；
5. 同一交易日存在不同 knowledge-time 版本；
6. 内容 Hash 稳定但输入已经被未来信息污染。

迁移不回填现有 `daily_bars`，也不把迁移或导入时间冒充更早的历史 known time。
早于首次可信观察的历史请求保持不可用。

## 4. V9 append-only 观察模型

迁移：
`V9__backtest_pit_daily_bar_observations.sql`

### 4.1 `market_data_observation_batches`

不可变批次记录：

- 物理 batch ID；
- `batchVersion`；
- source code；
- 独立 `datasetVersion`；
- capture type；
- `observedAt`、`recordedAt`；
- 输入记录数；
- source metadata。

### 4.2 `daily_bar_observations`

不可变观察版本记录：

- 物理 ID 与逻辑 observation version；
- symbol、trade date、`adjustType=QFQ`；
- OHLC、volume、amount、turnover rate；
- source code、可空 source revision；
- batch/dataset version；
- `firstObservedAt`、`knownAt`、`recordedAt`；
- canonical content hash。

两表均由 PostgreSQL trigger 禁止 `UPDATE`、`DELETE`、`TRUNCATE`。数据库约束
同时拒绝周末 `tradeDate`，以及早于对应交易日上海时间 15:00 的
`firstObservedAt` 或 `knownAt`。同一来源、证券、交易日、QFQ 内容与 revision 的
连续重复捕获不追加观察版本；内容或 revision 变化追加新版本并保留旧值。as-of
同日选择顺序冻结为 known time、recorded time、物理 ID、逻辑 observation version
的确定性降序。

### 4.3 捕获原子边界

成功的本地行情持久化统一经过 `MarketDataPersistenceService`，在同一事务中：

1. 校验完整输入；
2. 拒绝周末日线，并只筛选捕获时刻不早于该工作日上海时间 15:00 的完整日线；
3. 仅在至少存在一条合格记录时创建不可变 observation batch，`record_count` 只计
   合格记录；
4. 对每条合格且内容变化的记录追加 observation version；
5. 保证证券兼容记录；
6. upsert 全部合法业务输入的 `daily_bars` 当前态投影。

任一步失败全部回滚。Agent 上下文读取只读本地观察表，不触发网络；Python 不访问
数据库。工作日收盘前当日日线可以继续更新 `daily_bars` 兼容投影，但不进入可靠 PIT
观察，也不产生空批次；`daily_bars` 不能替代 PIT 观察表。

## 5. Canonical Hash Contract Gate

门禁状态：**`BACKTEST_CANONICAL_V1` 已由用户批准的 2F 架构冻结并在任务分支
实现；待完整 2F 提交验收**。

### 5.1 Canonical 规则

- contract version：`BACKTEST_CANONICAL_V1`；
- Hash：SHA-256；
- digest：小写十六进制；
- 文本：UTF-8；
- Unicode：NFC；
- 对象：字段名词典序；
- 数组：保持契约规定的业务顺序；
- date：ISO-8601；
- instant：UTC、`Z` 结尾、微秒精度；
- Decimal：普通十进制字符串，删除无意义尾零，`-0` 归一为 `0`；
- 禁止科学计数法、NaN 与 Infinity；
- 缺失字段与显式 `null` 不等价；
- JSON/JSONB 均按上述规则生成同一 canonical 文本。

数据库 ID、创建时间、日志、进程信息和运行环境噪声不得进入领域 Hash。

### 5.2 字段白名单

1. `inputDataHash` 覆盖 profile/contract、symbol、三类时间、数据窗口、独立
   `dataVersion`、完整升序 bars 与所选 observation lineage。
2. `strategyDefinitionHash` 覆盖 strategy code/version、engine version、
   parameter Schema version 与七项完整参数。
3. `backtestResultHash` 覆盖前两个 Hash 引用、完整结果汇总、三个有序子区间、
   稳定性事实与有序交易明细。

`dataVersion` 是独立对象，包含 PIT 模型版本、batch/dataset versions、所选
observation versions 和 source revisions。不得把 `inputDataHash` 重命名后冒充
完整数据版本。

### 5.3 黄金向量与兼容

仓库测试夹具固定：

- 输入 JSON/JSONB；
- canonical 文本；
- 预期 SHA-256
  `153b2a3d2f0edf7aab1c6e56f5ae203eabb02bf36ce163fa6b5b087d6d8f67c1`。

预期值不由被测实现运行时生成。Java 是生产 Hash 权威方；Python 使用相同规则验证
同一固定向量及收到的结构和 Hash。未来 canonical 版本变化必须新建版本并明确旧
数据保留、重算或迁移策略，不能静默改写 V1。

## 6. 可靠 backtestContext 输出

只有精确规则版本 `1.4.0-stage-2f-strategy-backtest-v1` 选择
`AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1`。旧
`create(symbol, tradeDate)` 入口保留，2B、2D-1、2E-1 的 contextSnapshot、
contextHash、缓存键和结果保持兼容。

可靠输入：

- 只读本地 PostgreSQL PIT observation；
- `adjustType=QFQ`；
- 按 trade date 升序；
- 最多 500 条；
- 最少 120 条；
- 不调用无日期截止的 `localHistory(symbol, days)`；
- 不读取旧回测汇总作为事实。

可用结构包含：

- available、producer/version、profile/Schema/PIT/canonical 版本；
- symbol、request/effective trade date、decision time、knowledge cutoff、时区；
- 输入起止、bar 数量、完整 bars；
- 独立 dataVersion 和 batch/observation lineage；
- 完整策略、引擎、参数与版本；
- 完整结果和稳定有序交易；
- EARLY/MIDDLE/LATE 三个不重叠子区间及稳定性计数；
- 三个领域 Hash；
- PIT、future exclusion 与回放保证；
- 研究用途和本地观察时间限制。

可用上下文的 120 条最低窗口保证三个子区间各至少 40 条，超过
`BacktestEngine` 的 30 条最低输入要求。

## 7. 不可用 reasonCode

稳定白名单：

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
- `BACKTEST_FUTURE_REQUEST_DATE`
- `BACKTEST_DECISION_TIME_NOT_REACHED`

业务不可用必须 `available=false`，不得伪造零收益、空交易即正常或中性结论。
数据库连接和 SQL 错误必须使任务失败，不能伪装为业务 reasonCode。

## 8. 兼容、持久化与安全边界

- 外层 `CONTEXT_SCHEMA_VERSION` 不变。
- 六个 run 不变，总控不是第七个 run。
- DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS 既有规则语义不变。
- Java 在单事务持久化前完成 2F 响应、evidence、Hash、score、confidence 与
  正式 veto 校验。
- 非法 Python 响应不留下部分 evidence、veto 或 decision。
- Python 不访问数据库、不拉取行情、不重跑 BacktestEngine。
- 2F-0 不创建 `backtest_runs`、扫描、信号或交易计划等业务副作用。
- 不修改 V1 至 V8，不执行 Flyway repair/clean；本地验收只向随机临时 Schema
  应用 V9，不修改专用测试库 public。
- 不接入外部 source adapter，不开放 FORMAL/PIT_VERIFIED 来源。

## 9. 验收标准

完整 2F 提交验收必须确认：

- V1 至 V9 在随机临时 Schema 顺序迁移，append-only trigger 真实生效；
- 同内容幂等，内容/revision 变化与 A→B→A 保留版本；
- as-of cutoff 和第 3.3 节全部污染场景可重复验证；
- 数据库和 Java/Python 契约均拒绝周末及交易日上海时间 15:00 前完整日线；
- 周末日线不会写入 PIT 或 `daily_bars`；工作日收盘前当日日线不进入 PIT、不创建
  空批次，兼容 `daily_bars` 投影仍按原业务更新；
- 合法迟到回填可保存，但早于 `firstObservedAt` 的回放保持不可用；
- 500 上限、120 下限、非交易日、乱序、重复日期和非法 OHLCV 有边界测试；
- 策略、引擎、参数、三子区间与重放确定；
- 三个领域 Hash、黄金向量和 Java/Python 一致；
- JSONB 往返、外层 contextHash 与旧 profile 兼容；
- 六 run、空正式 veto、失败原子性、幂等和缓存不变；
- 真实 PostgreSQL 与真实 Java/Python 测试 `Skipped=0`；
- 临时 Schema 精确删除，public 数据与结构指纹前后不变；
- 未执行 repair/clean，未触碰 V1 至 V8；
- 当前无 revision 的普通来源仍安全不可用。

任务分支的实际本地结果见
[../stage-2f-strategy-backtest-v1.md](../stage-2f-strategy-backtest-v1.md)。测试证据
属于 Codex 本地执行，不得描述为 GitHub Actions CI。

## 10. Codex 自主与暂停边界

2F 已获得的明确授权包括 V9、append-only PIT 模型、行情持久化捕获、版本化
context profile、canonical V1、三个领域 Hash、必要 Java/Python 契约和测试。
普通编译、测试、局部重构和常规实现问题由 Codex 自主解决。

只有出现核心架构重大分歧、授权外数据库核心模型或不可逆迁移、Java/Python 公共
契约重大选择、无法证明可消除的前视偏差、外部或付费来源、真实交易/资金风险，
或必须由用户选择的高风险业务口径时暂停。暂停与恢复遵循
[../../../AGENTS.md](../../../AGENTS.md)。

## 11. ChatGPT 实际提交验收清单

Codex 为完整 2F 单次 commit 并 push 后，ChatGPT 检查：

- [ ] commit diff 仅含完整 2F 授权范围，2F-0 未拆成独立提交；
- [ ] V9、K1、G1 与 `DECISIONS.md` 一致；
- [ ] 业务日期、周一至周五、交易日 15:00 最早知识时间和 knowledge cutoff 限制
  真实生效；
- [ ] 污染场景被隔离或安全降级；
- [ ] 内容 Hash 没有被用作历史可得性的唯一证据；
- [ ] 无网络、Python 数据库访问或旧汇总权威化；
- [ ] 策略/引擎/参数与三个领域 Hash 可独立审计；
- [ ] 黄金向量不是实现运行时自证；
- [ ] 同一冻结输入可重复回放；
- [ ] Java/Python、JSONB、原子失败、旧 profile 和六 run 兼容；
- [ ] 真实 PostgreSQL `Skipped=0` 且 public 基线不变；
- [ ] STRATEGY_BACKTEST 不产生正式 veto、投资建议或交易能力；
- [ ] 文档没有把未验收任务分支写成已合入事实；
- [ ] Codex 未 merge，未开始 2G 或其他大阶段。

## 12. 完成边界

2F-0 已在同一 2F 任务分支与后续 STRATEGY_BACKTEST 工作包连续实现，不形成
单独里程碑。只有完整 2F commit 推送并通过 ChatGPT 实际提交验收、再由用户批准
merge，才可更新为已验收或已合入。Codex 完成推送后停止，不自行 merge，也不开始
2G、2H、2I 或其他大阶段。
