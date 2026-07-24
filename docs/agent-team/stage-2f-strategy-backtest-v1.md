# 阶段 2F：可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1

## 1. 状态与边界

状态：**实现与 Codex 本地验证完成；ChatGPT 已基于实际 Git 提交验收 PASS；
用户已批准 merge；集成分支已 fast-forward 至最终修复提交**。

- 冻结集成基线：`1b6eb8c65a39bdae6b6e1fbd6d43743be881bed4`
- 任务分支：`codex/1.4.0-stage-2f-strategy-backtest-v1`
- 首次实现提交：`4ae0ac4ebc12aef559b9f88e7e1dfacc2b00a573`
- knowledge-time BLOCKER 修复及最终提交：
  `4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`
- 集成方式：`feature/1.4.0-agent-team` fast-forward 至
  `4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`，没有额外 merge commit
- ChatGPT 实际提交验收：`PASS`
- 用户 merge 批准时间：`UNKNOWN`（仓库没有精确批准时间证据）
- 团队规则版本：`1.4.0-stage-2f-strategy-backtest-v1`
- context profile：`AGENT_CONTEXT_2F_V1`
- context Schema：`BACKTEST_CONTEXT_V1`
- PIT 模型：`PIT_DAILY_BAR_OBSERVATION_V1`
- canonical 契约：`BACKTEST_CANONICAL_V1`
- 策略：`SMA20_NEXT_OPEN_RISK_EXIT_V1`
- 引擎：`BACKTEST_ENGINE_V1`
- 参数 Schema：`BACKTEST_PARAMS_V1`

本阶段把 2F-0、knowledge-time/PIT、领域 Hash、可靠
`backtestContext`、确定性 `STRATEGY_BACKTEST`、测试和验收证据作为同一大阶段
连续完成。本文记录已经验收并合入的阶段事实；当前项目状态仍以
[CURRENT_STATE.md](CURRENT_STATE.md) 为唯一权威来源。

## 2. 基线审计

冻结基线存在以下缺口：

1. 旧 `backtestContext` 固定安全不可用，仅按业务 `trade_date` 读取当前态
   `daily_bars` 不能证明历史可得性。
2. `daily_bars` 会被后续同步覆盖，没有首次观察、known-time、revision 或不可变
   数据集版本。
3. `backtest_runs`、`scan_backtest_tasks` 和 `scan_backtest_results` 都缺少完整
   冻结输入、策略/引擎版本、七项参数与可回放 lineage，不能作为权威输入。
4. 原回测引擎已有 SMA20、下一交易日开盘入场、风险退出、费用及 100 股整数交易
   语义，但缺少稳定版本标识和完整输入校验。
5. 原上下文入口不接收规则版本；若无条件注入新结构，会改变 2B、2D-1、2E-1
   的 contextHash、缓存键和结果。

因此，仅为精确规则版本增加版本化 profile；旧入口和旧规则保持原语义。

## 3. V9 PIT 日线观察模型

新增迁移
`V9__backtest_pit_daily_bar_observations.sql`，不修改 V1 至 V8，也不回填或伪造
历史观察时间。

### 3.1 观察批次

`market_data_observation_batches` 记录不可变批次 ID、`batchVersion`、source、
`datasetVersion`、capture type、`observedAt`、`recordedAt`、输入记录数和来源
元数据。数据库触发器禁止 `UPDATE`、`DELETE` 和 `TRUNCATE`。

### 3.2 日线观察版本

`daily_bar_observations` 保存稳定物理 ID 与逻辑 observation version、symbol、
trade date、QFQ OHLCV、amount、turnover rate、source、可空的 source revision、
batch/dataset version、`firstObservedAt`、`knownAt`、`recordedAt` 和 canonical
content hash。

- 同一 source、symbol、trade date、QFQ 内容和 revision 的连续重复捕获不追加
  观察版本。
- 内容或 revision 变化追加新版本；旧版本保留。
- as-of 查询同时限定 `tradeDate<=requestTradeDate` 与
  `knownAt<=knowledgeCutoff`，按 known time、recorded time、物理 ID 和逻辑版本
  稳定选择当时可见版本。
- 数据库拒绝周末日线，以及早于对应交易日上海时间 15:00 的
  `firstObservedAt` 或 `knownAt`。
- 合理索引覆盖 source、symbol、adjust type、trade date、known time 与 batch。
- 迁移不把执行时间冒充历史首次观察时间；早于首次可信观察的请求保持不可用。

### 3.3 捕获事务

成功的本地行情持久化统一经过 `MarketDataPersistenceService`：持久化入口拒绝
周末日线，只为周一至周五且
捕获时刻不早于该交易日上海时间 15:00 的完整日线创建观察批次、按内容变化追加
PIT 版本，再更新兼容的 `daily_bars` 当前态投影。工作日收盘前当日日线不进入 PIT，
不创建空批次，但可以继续更新兼容投影；混合输入的 `record_count` 只计算合格 PIT
记录。任一步失败均回滚整个持久化事务。Agent 读取不触发网络，Python 不访问
数据库。

当前普通配置源不提供可验证的 source revision，捕获时如实保存 `null`；
`AgentBacktestContextService` 因而返回
`BACKTEST_SOURCE_REVISION_UNVERIFIABLE`，不会把本地观察时间误写成供应商发布时间
或把当前数据误判为可靠历史输入。

## 4. Knowledge-time 契约

- 市场时区：`Asia/Shanghai`。
- `requestTradeDate`：用户要求研究的业务日期。
- `decisionTime`：请求日期上海时区日终最后一微秒。
- `knowledgeCutoff=decisionTime`。
- 非交易日可选择请求日期以前最近的有效输入交易日，但 cutoff 不移动。
- 未来请求日期使用 `BACKTEST_FUTURE_REQUEST_DATE`；请求日日终尚未到达使用
  `BACKTEST_DECISION_TIME_NOT_REACHED`。
- 每条可靠输入必须同时满足业务日期与 knowledge-time 上界。
- 完整日线的最早合法知识时间是其交易日上海时间 15:00；可靠输入必须是周一至
  周五，并满足 `firstObservedAt`、`knownAt` 均不早于该时刻、
  `firstObservedAt<=knownAt<=recordedAt`。

内容 Hash 只证明给定内容的稳定性，不证明其在历史决策时点已经可知。cutoff 后
覆盖、未来公司行动导致 QFQ 改写、来源修订、迟到数据和同交易日多 knowledge-time
版本必须由不可变观察版本隔离；证据不足时保持 `available=false`。周末日线直接
拒绝；本阶段不引入新的正式交易日历来源，法定节假日仍由实际来源和后续正式
日历能力治理。

## 5. 可靠 backtestContext

只有精确规则版本 `1.4.0-stage-2f-strategy-backtest-v1` 选择
`AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1`。旧 `create(symbol, tradeDate)`
入口以及 2B、2D-1、2E-1 规则仍生成原安全不可用
`backtestContext`，其 contextSnapshot、contextHash、缓存键和结果不变。

可靠输入只读本地 PostgreSQL PIT 观察版本：

- `adjustType=QFQ`；
- 按 trade date 升序；
- 最多 500 条；
- 至少 120 条才形成可用回测基础；
- 只使用 revision、known time、batch/dataset 和观察版本均可验证的记录。

可用结构包含 producer/version、profile/Schema、symbol、请求与有效交易日期、
三类时间、时区、输入窗口、完整 bars、独立 `dataVersion`、batch 与观察 lineage、
策略/引擎/参数、完整汇总、稳定交易明细、三个子区间、三个领域 Hash、安全声明
及限制。不可用结构保留相同基础身份和时间事实，并给出互斥 reasonCode。

## 6. 策略、参数与稳定性

Java 冻结并执行 `SMA20_NEXT_OPEN_RISK_EXIT_V1/BACKTEST_ENGINE_V1`：

- SMA20 收盘信号；
- 下一交易日开盘入场；
- 止损、止盈、移动止损和最大持有期；
- 同一 bar 退出优先级为止损、止盈、移动止损、最大持有期；
- 佣金、印花税与 100 股整数交易；
- 所有日期不超过请求截止；
- BigDecimal 确定性计算和显式边界校验。

`BACKTEST_PARAMS_V1` 完整保存七项参数：

| 参数 | 冻结值 |
|---|---:|
| `initialCapital` | `100000` |
| `maxHoldingDays` | `10` |
| `stopLossPct` | `0.05` |
| `takeProfitPct` | `0.08` |
| `trailingStopPct` | `0.04` |
| `commissionRate` | `0.0003` |
| `stampDutyRate` | `0.0005` |

完整升序窗口按
`CHRONOLOGICAL_THIRDS_REMAINDER_TO_EARLY_THEN_MIDDLE_V1`
切成不重叠的 `EARLY`、`MIDDLE`、`LATE`：基础长度与余数分别分配给
EARLY、MIDDLE、LATE，第一条余数给 EARLY、第二条给 MIDDLE。每段至少 40 条，
超过引擎 30 条最低输入要求；三段使用完全相同策略与参数。Java 记录有效子区间数
和正收益子区间数，Python 不重跑回测。

## 7. Canonical Hash 与黄金向量

`BACKTEST_CANONICAL_V1` 冻结：

- SHA-256、小写十六进制、UTF-8、Unicode NFC；
- 对象字段名词典序；数组保持契约业务顺序；
- ISO-8601 date；UTC `Z` 微秒时间；
- Decimal 使用普通十进制字符串、删除无意义尾零、`-0` 归一为 `0`；
- 禁止 NaN、Infinity 和科学计数法；
- 缺失字段与显式 `null` 区分；
- 仅白名单字段进入 Hash，数据库 ID、创建时间、日志、进程与环境噪声排除。

三个领域 Hash 分别是：

1. `inputDataHash`：profile/contract、symbol、三类时间、窗口、独立数据版本、
   完整 bars 与 observation lineage。
2. `strategyDefinitionHash`：策略、策略版本、引擎、参数 Schema 和七项参数。
3. `backtestResultHash`：前两个 Hash 引用、完整汇总、三子区间、稳定性与有序交易
   明细。

`dataVersion` 独立表达 PIT 模型、batch/dataset versions、所选 observation
versions 和 source revisions，不以 `inputDataHash` 冒充数据版本。

固定测试夹具包含输入 JSON、canonical 文本和预期 SHA-256：

`153b2a3d2f0edf7aab1c6e56f5ae203eabb02bf36ce163fa6b5b087d6d8f67c1`

Java 是生产 Hash 权威方；Python 使用同一契约和固定预期值验证结构与 Hash，不由
被测实现运行时生成期望结果。

## 8. STRATEGY_BACKTEST 规则 V1

Python 只解释 Java 已冻结事实，不拉取行情、不连接数据库、不重跑策略、不选参或
寻优。

### 8.1 安全降级

- DATA_QUALITY BLOCKED：继承阻断，不生成正常回测 finding。
- context 不可用：`INSUFFICIENT_DATA`，score/confidence 为 0。
- Schema、版本、Hash、数值或交易明细非法：
  `STRATEGY_BACKTEST_INPUT_INVALID`。
- `tradeCount<10` 或有效子区间少于 2：
  `STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT`。

样本不足不输出正常性能评分。

### 8.2 五类 finding

固定且有序生成：

1. `STRATEGY_BACKTEST_SAMPLE_SUFFICIENT`
2. `STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED`
3. `STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED`
4. `STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED`
5. `STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED`

finding 和 evidence 只引用 Java context 中的事实。Java 对整个 2F 响应再次执行
字段白名单、类型、顺序、数值、Hash、evidence、门禁、score/confidence 和正式
veto 校验；非法响应在持久化前失败，不留下部分 evidence、veto 或 decision。

### 8.3 Score 与 confidence

有效样本从 50 开始，每个维度只应用一个档位：

| 维度 | 档位与影响 |
|---|---|
| total return | `>=0.15:+15`；`>=0.05:+10`；`>0:+5`；`<=-0.15:-20`；其余负值 `-10` |
| max drawdown | `<=0.10:+10`；`>0.20 && <=0.30:-10`；`>0.30:-20`；其余 `0` |
| win rate | `>=0.55:+10`；`<0.45:-10`；其余 `0` |
| profit/loss ratio | `>=1.50:+10`；`>=1.00:+5`；`<0.80:-10`；其余 `0` |
| positive subperiods | `3:+10`；`2:+5`；`1:-10`；`0:-20` |

最终 score 截断到 `[0,100]`。交易数 10–19、20–39、40+ 对应 confidence
40、60、80；DATA_QUALITY WARN 时最高 50。confidence 不达到 100，历史回测
不描述为确定预测。

STRATEGY_BACKTEST 不产生正式 veto、投资建议、交易指令或收益承诺。六个 run
保持不变，总控不是第七个 run；POSITION_RISK 仍是唯一正式否决权。由于
ANNOUNCEMENT_RISK 和 POSITION_RISK 尚未实现，总控仍保持安全不足结论。

## 9. 稳定不可用 reasonCode

Java/Python 白名单冻结：

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
- `STRATEGY_BACKTEST_INPUT_INVALID`
- `STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT`

数据库连接和 SQL 错误继续使任务失败，不伪装成业务不可用。

## 10. Codex 本地测试证据

以下均为 Codex 本地执行证据，**不是 GitHub Actions CI**：

| 范围 | 结果 |
|---|---|
| `quant-core` 全量 | `4/0/0/0` |
| 2F Java contract/service 定向 | `51/0/0/0` |
| Python `compileall` | 通过 |
| Python 完整 unittest | `82/0/0` |
| 2F V1–V9 真实 PostgreSQL | `5/0/0/0`，`Skipped=0` |
| 2F 真实 Java/Python HTTP | `4/0/0/0`，`Skipped=0` |
| 2F 真实 PostgreSQL/Python/JSONB/原子失败 | `2/0/0/0`，`Skipped=0` |
| 2D V1–V9 PostgreSQL 兼容 | `10/0/0/0`，`Skipped=0` |
| 2E 真实跨语言与 PostgreSQL 兼容 | `6/0/0/0`，`Skipped=0` |
| PostgreSQL 环境安全门单元测试 | `8/0/0/0` |
| `quant-server` 安全非数据库回归 | `242/0/0/8` |

`quant-server` 的 8 项跳过均是 2E/2F 外部 Python 或 PostgreSQL 环境门禁，
不能冒充真实闭环；对应 2E/2F 真实跨语言闭环已另外执行 `8/0/0/0`、
`Skipped=0`。

针对 ChatGPT 实际提交验收发现的日线最早知识时间 BLOCKER，增量修复后重新执行：

| 范围 | 运行/失败/错误/跳过 |
|---|---|
| `quant-core` 全量 | `4/0/0/0` |
| Java contract/service/profile/contextHash 定向 | `27/0/0/0` |
| Python `compileall` | 通过 |
| Python 完整 unittest | `83/0/0/0` |
| 2F V1–V9 真实 PostgreSQL | `7/0/0/0` |
| 2F 真实 Java/Python HTTP | `4/0/0/0` |
| 2F 真实 PostgreSQL/Python/JSONB/原子失败 | `2/0/0/0` |
| 2D V1–V9 PostgreSQL 兼容 | `10/0/0/0` |
| 2E 真实跨语言与 PostgreSQL 兼容 | `6/0/0/0` |

上述五组真实集成测试均为 `Skipped=0`；新场景覆盖数据库周末/15:00 前拒绝、
Java mocked 倒填安全不可用、Python 倒填输入非法、合法迟到回填及旧 cutoff、
Hash、重放和 profile/contextHash 兼容。所有结果仍是 Codex 本地执行证据，不是
GitHub Actions CI。

绑定专用数据库 public 的全量 `quant-server` 尝试运行 286 项，出现 15 个启动
错误和 14 项跳过，原因是专用测试库 public 已存在的历史 V6 checksum 与当前仓库
不一致；该次运行不得描述为通过。未执行 Flyway repair/clean，未修改、删除或
重建 public。2F 及兼容测试改用随机临时 Schema 从 V1 顺序迁移至 V9，测试后精确
删除临时 Schema，并验证 public 数据、关系、约束、触发器、函数、Flyway 历史和
扩展指纹前后不变。

真实 PostgreSQL 覆盖 append-only 触发器、同内容幂等、内容/revision 变化、A→B→A、
as-of cutoff、周末和 15:00 前观察拒绝、收盘前兼容投影、合法迟到回填、cutoff 后
污染、未来公司行动造成历史 QFQ 改写、来源修订、同日多 knowledge-time、120/500
窗口、JSONB、失败原子性、六 run、空正式 veto 与精确清理。最终
`git diff --check` 结果记录在本阶段提交前检查中。

## 11. 未实现与验收边界

- 当前普通来源 revision 为 `null`，不会形成可用生产回测输入。
- 本阶段不接入外部或付费行情，不开放 FORMAL/PIT_VERIFIED 数据来源。
- 不实现 ANNOUNCEMENT_RISK、POSITION_RISK、2G、2H、2I 或完整总控。
- 不实现参数寻优、投资建议、正式 veto、自动交易或真实资金能力。
- 不改变证券状态正式来源与许可工作线的阻断状态。
- 2F 已形成 ChatGPT 实际提交验收和用户 merge 批准的历史里程碑，索引见
  [PROGRESS_LOG.md](PROGRESS_LOG.md)。

完整架构决定见 [DECISIONS.md](DECISIONS.md)，大阶段任务书见
[tasks/2f-reliable-strategy-backtest-v1.md](tasks/2f-reliable-strategy-backtest-v1.md)，
2F-0 实现契约见
[tasks/2f0-backtest-context-foundation.md](tasks/2f0-backtest-context-foundation.md)。
