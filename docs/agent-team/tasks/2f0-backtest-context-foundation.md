# 2F 大阶段内部工作包 2F-0：可靠 backtestContext 审计与接入基础

## 1. 文档状态与权威边界

状态：**候选内部工作包任务书 / `NOT_STARTED`**。

本文只冻结下一候选大阶段“**2F 可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1**”中内部工作包 2F-0 的目标、范围、安全门和验收条件，不表示业务实现已经开始。2F-0 不单独开发、Review、commit、push 或验收；未来整个 2F 获得实施授权后，Codex 应在同一任务分支连续完成 2F-0 和后续内部工作包，最终为完整 2F commit 并 push。当前真实状态以 [CURRENT_STATE.md](../CURRENT_STATE.md) 为准，阶段方向以 [ROADMAP.md](../ROADMAP.md) 为准，跨阶段决策以 [DECISIONS.md](../DECISIONS.md) 为准，开发治理以仓库根目录 [AGENTS.md](../../../AGENTS.md) 为准。

内部工作包名称：**2F-0 可靠 backtestContext 审计与接入基础**。

## 2. 仓库事实审计

冻结基线 `04d14fe9aee05ba0396e5d8f6454668da944f7b1` 的真实事实如下：

1. `AgentContextSnapshotService` 已生成固定九类 `contextSnapshot`，外层版本仍为 `1.0`；其中 `backtestContext` 固定 `available=false`，`reasonCode=BACKTEST_INPUT_CUTOFF_UNVERIFIABLE`。
2. `AgentContextSnapshotService.create(symbol, tradeDate)` 在 `REPEATABLE_READ` 只读事务内执行，但不接收 `ruleVersion`。若无条件改变 `backtestContext`，会改变所有现有规则版本的 `contextHash` 和缓存键，因此旧版本兼容策略必须在开发安全门中明确验证。
3. 现有 `AgentContextReadRepository.findQfqDailyBars` 能按 `symbol`、`adjust_type='QFQ'` 和 `trade_date<=request.tradeDate` 读取最多 61 条日线。2F-0 所需回测窗口是否可复用该上限尚未证明，允许新增 Agent 专用只读 Repository 查询，但不得把 61 条技术指标窗口默认为回测窗口。
4. V1 `backtest_runs` 只有 `strategy_code`、不受强类型约束的 `params`、汇总结果和运行时间，没有可验证的输入开始/截止日期、算法版本、策略版本或输入 lineage。
5. V3 `scan_backtest_tasks` 和 `scan_backtest_results` 保存批量任务参数及汇总结果，但没有冻结完整参数、输入 bars、输入截止、策略版本或结果重放所需 lineage。
6. `ScanValidationService` 当前调用 `MarketDataService.localHistory(symbol, 180)`；该方法只按最新日期倒序限制条数，没有请求交易日截止参数。该旧路径不能作为 2F-0 权威输入。
7. 当前 `BacktestModels.Request` 有七项参数：`initialCapital`、`maxHoldingDays`、`stopLossPct`、`takeProfitPct`、`trailingStopPct`、`commissionRate`、`stampDutyRate`。`BacktestEngine` 本身没有可持久化的算法版本标识。
8. Python `ContextSnapshot.backtestContext` 当前是可承载结构化字段的字典，但 `StrategyBacktestAgent` 仍只返回未实现的安全结果；Java 响应校验也只冻结到 2E-1。
9. 现有 `AgentContextHashService` 对对象键排序、保留数组业务顺序、按数学值规范化 JSON 数字，并排除既有易变时间/身份字段。该算法已经用于整个 `contextSnapshot`，但尚未冻结 2F-0 的三个领域 Hash。
10. 现有 Agent 任务已经把完整 `contextSnapshot` 和 `contextHash` 持久化为 JSONB，并有 PostgreSQL 语义往返、Hash 重算和失败原子性测试基础。
11. 当前已审计路径只按业务 `trade_date` 读取现有 QFQ 日线，没有为每个历史值冻结首次观察时间、来源修订版本或 knowledge-time 快照。现有证据因此不能排除后续同步覆盖历史值，也不能证明 QFQ 值未受后续公司行动、复权因子或来源修订影响。

以上事实只证明现有入口和缺口，**不预先证明无需数据库迁移、无需外层 Schema 调整或无需架构变化**。如果可靠 knowledge-time/PIT 证据需要数据库核心模型、不可逆迁移或重大公共契约选择，必须按大阶段暂停门报告并纳入 2F 架构，不能由本次纯文档任务实现。

## 3. 背景与目标

当前 `backtestContext` 因输入截止日期、knowledge-time、来源/修订版本、策略版本、完整参数和来源谱系不可验证而保持不可用。读取旧 `backtest_runs`、`scan_backtest_tasks` 或 `scan_backtest_results` 的最终汇总，不能证明结果只使用了历史决策时点已经可知的数据，也不能重建当时算法和参数。

2F-0 的目标是建立一个：

- 由 Java 生成；
- 不调用外部服务；
- 只读取本地 PostgreSQL；
- 输入严格截止 `request.tradeDate`；
- 输入在 `knowledgeCutoff` 前已经可知并有可审计证据；
- 完整冻结算法版本、策略版本和参数；
- 可确定性 Hash；
- 可从冻结事实重放；
- 不产生业务写入；

的只读 `backtestContext` 事实基础。

2F-0 内部工作包不实现 `STRATEGY_BACKTEST` finding、score、confidence、正式 veto 或总控升级；这些确定性规则属于同一 2F 大阶段的后续内部工作包，不需要为 2F-0 建立单独提交或验收停顿。

## 4. 输入边界

候选市场输入是本地 PostgreSQL `daily_bars` 中：

- 与请求 `symbol` 精确匹配；
- `adjust_type='QFQ'`；
- `trade_date<=request.tradeDate`；
- 按交易日稳定排序；
- 满足最终冻结窗口和数据完整性门槛；

的日线记录。只有同时通过第 5 节 knowledge-time/PIT 门禁时，这些记录才可成为可靠回测事实；仅满足上述业务日期条件时不得把它们视为 PIT 安全输入。

开发实现必须使用 Agent 专用参数化只读查询。不得调用 `MarketDataService.history`，不得触发外部行情同步，不得复用没有日期截止的 `localHistory(symbol, days)`，不得让 Python 访问数据库，也不得把旧回测结果表视为权威输入。

开发阶段必须先冻结：

- 回测窗口长度或开始日期规则；
- 请求交易日不是交易日时的有效截止语义；
- OHLCV、`amount`、`turnoverRate` 的 NULL/非法值规则；
- 最少样本门槛；
- 相同日期重复记录和排序规则；
- 当前 `BacktestEngine` 是否满足可版本化、可重放门槛。

上述规则中的普通技术选择由 Codex 在未来获授权的 2F 大阶段内自行完成；涉及核心架构重大分歧、数据库核心模型或不可逆迁移、Java/Python 公共契约重大选择或无法证明可消除的前视偏差时，才按 [AGENTS.md](../../../AGENTS.md) 暂停。

## 5. 2F-0-K1：Knowledge-Time/PIT Input Contract Gate

门禁状态：**阻断式前置安全门，尚未通过**。

`trade_date<=requestTradeDate` 只能限制业务日期，不能证明某个历史值在当时的决策时点已经可知。当前历史行情可能被后续同步覆盖；QFQ 历史值也可能因后续公司行动、复权因子或来源修订发生变化。可靠 `backtestContext` 必须先证明输入在 `knowledgeCutoff` 前已经可知，否则必须保持 `available=false`。

### 5.1 三类时间的关系

- `requestTradeDate` 是被研究或回放的业务交易日期，不是数据观察时间。
- `decisionTime` 是历史场景中允许形成判断的决策时刻，必须明确市场时区、盘中/收盘后语义和非交易日处理。
- `knowledgeCutoff` 是该次回放允许看到数据的最晚知识时点，必须不晚于 `decisionTime`；二者是否相等以及盘后数据何时可用，必须在 2F 架构中明确冻结。
- 任何记录即使满足 `trade_date<=requestTradeDate`，只要无法证明在 `knowledgeCutoff` 前已发布、已观察或已进入获准快照，就不能进入可靠输入。

### 5.2 必须设计或审计的证据

2F 架构必须明确并可验证：

- 每条行情或快照的首次观察时间；
- 来源版本、修订版本及更正关系；
- 行情快照版本或数据集版本；
- 迟到数据的识别和可见时间；
- 同一交易日历史值被后续覆盖时的版本保留；
- 公司行动在何时发布、何时生效以及对历史价格的影响；
- 复权因子、复权算法及其 knowledge-time；
- `knowledgeCutoff` 之后发生的历史修订如何隔离；
- 历史覆盖范围及缺失区间；
- 同一交易日期在不同 knowledge-time 下可见的不同版本。

内容 Hash 只能证明给定内容在 canonical 契约下稳定，不能证明该内容在历史决策时点已经可知。`inputDataHash`、快照 Hash 或外层 `contextHash` 均不得单独充当 PIT 合法性证据。

### 5.3 不可用语义

- 可靠性判断必须逐项引用 knowledge-time、来源/修订和快照证据。
- 任一必要证据缺失、冲突或无法回放时，`backtestContext.available` 必须为 `false`，不得以稳定 Hash、当前数据库值或零交易结果伪装为可用。
- 本任务不擅自冻结新的生产 reasonCode；在后续 2F 架构明确新契约前，当前 `BACKTEST_INPUT_CUTOFF_UNVERIFIABLE` 安全不可用语义保持不变。

### 5.4 后续验收测试

未来 2F 大阶段至少必须覆盖：

1. `knowledgeCutoff` 之后覆盖 `knowledgeCutoff` 之前交易日的数据；
2. 未来公司行动改变历史 QFQ；
3. 来源修订历史值；
4. 迟到数据在 cutoff 后才出现；
5. 同一交易日期在不同 knowledge-time 下存在不同可见版本；
6. Hash 稳定但输入已经被未来信息污染。

每个场景都必须证明污染版本被排除或安全降级为 `available=false`，不能只验证业务日期过滤。

### 5.5 架构边界

如果满足本门禁需要不可变行情快照、PIT 模型、`known_at`/首次观察字段、修订链或数据库迁移，应把它们记录为下一大阶段 2F 的架构设计事项。当前治理文档任务不编写生产代码、测试代码或迁移；未来 Codex 遇到数据库核心模型、不可逆迁移、重大公共契约选择或无法证明可消除的前视偏差时，必须暂停并提交方案。

## 6. 2F-0-G1：Canonical Hash Contract Gate

门禁状态：**阻断式前置安全门，尚未通过**。

在未来 2F 大阶段开始任何 Hash 持久化、重大公共契约调整、数据库迁移或依赖该契约的生产实现之前，必须先形成并冻结版本化 canonical 契约方案。本任务书只定义方案必须回答的问题，不批准最终算法、字段名、业务口径或数据版本语义。Codex 可在大阶段内自主完成普通契约细节；若出现核心架构重大分歧、数据库核心模型或不可逆迁移、Java/Python 公共契约重大选择，则按全局暂停门提交方案。

### 6.1 版本化 canonical 契约方案的必答项

方案必须逐项明确：

1. canonical 契约版本号及版本升级策略；
2. Hash 算法；
3. digest 输出编码；
4. 文本编码；
5. Unicode 规范化规则；
6. 三类领域 Hash 各自的字段白名单：输入数据、策略和参数、结果；
7. 明确不得进入 Hash 的字段，包括数据库自增 ID、创建时间、与业务结果无关的运行环境噪声和非确定性日志字段；
8. 对象字段排序规则；
9. 数组或时间序列排序规则；
10. `null`、缺失字段和空值的区别；
11. 日期和时间的表达规则；
12. 时区语义；
13. 小数精度；
14. 舍入模式；
15. 整数、浮点数和 Decimal 的规范化方式，以及 NaN/Infinity 的处理；
16. JSON/JSONB canonical 序列化方式；
17. 数据版本的明确定义；
18. Java 与 Python 是否都需要计算或验证 Hash，以及跨语言一致性要求；
19. 固定黄金测试向量；
20. 旧数据、旧回测记录和新 canonical 版本之间的兼容策略；
21. canonical 版本变化时，是重新计算、迁移还是保留旧版本。

第 17 项必须明确回答数据版本究竟是独立来源版本字段、行情数据快照版本、数据集版本，还是由内容 Hash 承担版本标识。不得在没有明确决策时默认 `inputDataHash` 自动等于完整的数据版本语义。

第 19 项必须包含固定输入 JSON/JSONB、固定 canonical 输出和固定预期 Hash。若 Java 与 Python 都参与计算或验证，二者必须对相同向量得到完全相同结果。预期值必须来自已批准契约和可独立复核的固定向量，不允许只使用实现代码自己生成的结果进行自证。

### 6.2 门禁通过条件与暂停要求

- 仓库现有事实已经冻结的选择，可以在明确其适用范围后引用并沿用；未冻结的选择不得由开发 Codex 猜测。
- ChatGPT 必须在 2F 大阶段架构和验收标准中冻结 canonical 契约；形成重大方案选择时，Codex 按 [AGENTS.md](../../../AGENTS.md) 暂停并提供选项，需要高风险业务选择时由用户决定。
- 冻结后的跨阶段决定必须记录到 [DECISIONS.md](../DECISIONS.md)，普通实现可在同一 2F 任务分支继续，不为 2F-0 单独 commit 或验收。
- 门禁通过前，不得实现依赖该契约的生产 Hash、Hash 持久化、数据库迁移或公共契约。
- 若方案涉及重大公共契约选择、数据库核心模型或不可逆迁移，同时触发根目录 [AGENTS.md](../../../AGENTS.md) 的全局暂停门。

## 7. K1 与 G1 通过后的输出目标

只有 knowledge-time/PIT 门禁 K1 与 canonical Hash 门禁 G1 都通过后，可用的 `backtestContext` 才可以按冻结契约包含以下事实：

- `available`、稳定 `reasonCode` 和限制说明；
- producer、producer version、策略标识和策略版本；
- 数据库来源、来源表和 QFQ 调整类型；
- `requestTradeDate`、`decisionTime`、`knowledgeCutoff`、市场时区及三者关系；
- 请求日期、有效输入开始/结束日期、bar 数量及未来数据排除声明；
- 首次观察时间、来源/修订版本、快照或数据集版本、迟到/覆盖、公司行动与复权 lineage；
- 按冻结规则排序的完整输入 bars，或能够被 ChatGPT 独立核验为等价可重放的完整冻结输入；
- 当前策略使用的全部七项参数及参数 Schema 版本；
- 确定性回测结果和完整交易明细，前提是现有引擎通过版本与语义审计；
- 三类领域 Hash、canonical 契约版本及获批准的数据版本标识；
- 回放所需的算法、参数、输入、knowledge-time 和来源 lineage。

`inputDataHash`、`strategyDefinitionHash`、`backtestResultHash` 仅作为本任务书描述三类 Hash 的候选名称，不是已经批准的公共字段名或最终字段白名单。三类 Hash 的最终字段集合、canonical 规则及相互引用关系全部以通过 2F-0-G1 的契约为准。

现有外层 `contextHash` 与三个领域 Hash 的关系也必须在 G1 中明确，不得默认现有算法可直接承担新的数据版本或回测重放语义。重放仍必须保证只使用冻结输入、获批版本和参数即可得到相同结果，不重新查询外部数据，也不使用请求日期之后的数据。

## 8. 2F-0 内部工作包允许范围

未来获授权的 2F 大阶段中，2F-0 内部工作包允许：

- Java Agent 专用只读 Repository；
- Java Agent 专用 `backtestContext` 服务及其装配；
- K1 所需的 knowledge-time/PIT 事实审计、契约设计和安全降级；
- G1 所需的版本化 canonical 契约及其确定性 Hash；
- 必要的 Java 模型、只读验证、可回放事实和兼容适配；
- 单元、契约、JSONB、真实 PostgreSQL 与 public 基线保护测试；
- 2F 内部工作包、验收和权威状态文档。

大阶段限制的是业务边界，不限制完成授权范围所需的合理文件数量。普通编译、类型、测试、局部重构、常规实现选择和文档一致性问题由 Codex 自主修复。2F-0 工作包完成后，在没有触发第 13 节暂停门时，Codex 应继续同一 2F 任务分支的后续规则和验收工作包。

## 9. 2F-0 内部工作包边界

- 本次治理文档任务只修改文档，不编写生产代码、测试或迁移。
- 2F-0 内部工作包不实现 Python `STRATEGY_BACKTEST` 评分、finding 或 evidence；它们属于同一 2F 大阶段的后续工作包，不需要另建任务分支。
- 不调用外部行情、网络、source adapter，也不开放 FORMAL 或正式 PIT 来源。
- 不使用旧 `backtest_runs`、`scan_backtest_results` 或 `scan_backtest_tasks` 汇总冒充权威事实。
- 不得静默新增 Flyway、修改 V1 至 V8、改变 public Schema 或清理现有业务数据；如果 K1 需要不可变快照、`known_at`、PIT 模型或数据库迁移，按第 13 节暂停并纳入 2F 大阶段架构。
- 不修改生产扫描、批量回测生产路径或 `MarketDataService` 外部同步行为。
- 不自动参数寻优、策略选择、收益排名或以结果反推参数。
- 不输出投资建议、买卖信号、收益承诺，不执行自动交易或真实账户操作。
- 不改变六智能体职责、POSITION_RISK 唯一正式否决权或总控安全结论。
- 不开始 2G、2H、2I 或其他大阶段。

## 10. 不可用与安全降级

实现阶段必须冻结稳定、互斥的不可用 reasonCode，至少区分：

- 请求日期及以前没有本地 QFQ 日线；
- knowledge-time、首次观察、来源/修订或快照版本不可验证；
- cutoff 后覆盖、迟到数据、公司行动或复权变化无法安全隔离；
- 样本不足；
- 日线字段、顺序或数值非法；
- 策略/引擎版本无法可靠冻结；
- 参数集合不完整或非法；
- 回放或 Hash 自校验失败。

数据库连接或 SQL 执行失败不得伪装成业务不可用，应按现有任务失败语义向上失败。业务不可用必须 `available=false`，不得伪造零收益、空交易即正常或“中性”回测。正式 reasonCode 名称在 2F 架构和跨语言夹具中冻结；在此之前沿用当前安全不可用结论。

## 11. 2F-0 验收标准

### 11.1 Knowledge-Time/PIT Input Contract Gate

- `requestTradeDate`、`decisionTime` 和 `knowledgeCutoff` 的关系、时区和盘中/盘后语义已经冻结并可测试。
- 每个可用输入都有首次观察、来源/修订、快照或数据集版本以及公司行动/复权 lineage，能够证明其在 `knowledgeCutoff` 前可知。
- 测试覆盖 cutoff 后覆盖历史交易日、未来公司行动改变历史 QFQ、来源修订、迟到数据、同一交易日的不同 knowledge-time 版本，以及 Hash 稳定但输入被未来信息污染。
- 上述场景必须排除污染版本或返回 `available=false`；不得只用 `trade_date` 上界或内容 Hash 自证 PIT 合法。

### 11.2 Canonical Hash Contract Gate

- `DECISIONS.md` 和 2F 架构中存在版本化 canonical 契约；重大公共契约、数据库模型或不可逆迁移选择已按暂停门完成处理。
- 必须能够独立执行固定黄金向量，核对固定输入 JSON/JSONB、固定 canonical 输出和固定预期 Hash。
- 相同输入、策略和结果必须产生稳定 Hash；对象顺序、时间序列顺序、时区、精度或序列化方式的变化不得产生未经契约解释的差异。
- Java 与 Python 若都参与计算或验证，必须对同一黄金向量得到完全相同结果。
- 不允许只使用实现代码自己生成的预期结果进行自证。
- 未通过 G1 时，不得把依赖该契约的生产 Hash、持久化、迁移或公共契约描述为已经实现或通过验收。

### 11.3 输入截止与无前视

- 所有输入 bars 均满足 `tradeDate<=request.tradeDate`，且同时满足 K1 的 knowledge-time 证据。
- 请求日期之后或 `knowledgeCutoff` 之后新增、覆盖或修订的 bars 不得污染同一历史场景的回放。
- 最后一条输入、所有信号、入场和出场日期都不得晚于请求日期。
- SQL 必须有显式日期上界，不能依赖“最新 N 条”间接保证。
- 非交易日、缺口、重复日和乱序场景有边界测试。

### 11.4 版本、参数、Hash 与重放

- 策略/引擎版本和参数 Schema 版本非空、稳定且受白名单验证。
- 七项现有参数全部显式冻结，不得依赖代码默认值补齐持久化事实。
- 三类领域 Hash 有固定向量、对象字段排序测试、数组/时间序列排序测试和单字段变更测试。
- 同一冻结事实多次运行得到相同结果、交易顺序、三个领域 Hash 和外层 `contextHash`。
- NULL、NaN、Infinity、非法 OHLC、负 volume 和非法参数安全拒绝或降级。

### 11.5 契约、持久化与兼容

- Java 生成的 `backtestContext` 可被当前 Python 请求模型无损解析；在 2F-0 内部工作包中 Python 不评分、不连接数据库。
- 完整 `contextSnapshot` 经过 PostgreSQL JSONB 往返后语义不变，knowledge-time 证据、三个领域 Hash 和外层 `contextHash` 可稳定重算。
- 2B、2D-1、2E-1 规则版本、六个 run、证据顺序、空正式 veto 和总控安全结论保持兼容。
- 必须专门验证 `AgentContextSnapshotService.create` 当前不接收 `ruleVersion` 带来的兼容风险；出现 Java/Python 公共契约重大选择或核心架构分歧时按第 13 节暂停。
- 不读取旧汇总冒充事实，不创建回测任务，不产生 `backtest_runs`、扫描、信号、trade plan 或 Agent 结果之外的业务副作用。

### 11.6 真实 PostgreSQL 与 public 基线

- 在专用 `stock_quant_test` 上完成真实 PostgreSQL 闭环，相关测试 `Skipped=0`。
- 测试覆盖 K1 全部污染场景、输入截止、三个 Hash、JSONB 往返、不可用 reasonCode、可重复回放和精确清理。
- public 的数据计数、关系、约束、触发器、函数、Flyway 历史/checksum 和扩展指纹前后不变。
- 测试数据按唯一标记或返回 ID 精确清理，不使用全表清理，不执行 Flyway repair/clean。
- 无数据库环境变量时的门禁跳过必须单独报告，不得冒充真实 PostgreSQL 通过。

## 12. Codex 自主处理项

未来 2F 大阶段获得明确授权后，Codex 应在同一任务分支自主并连续完成：

- 重新审计相关 Repository、服务、模型、行情写入路径、Hash、持久化和测试；
- 完整设计并验证 K1 knowledge-time/PIT 契约和 G1 canonical 契约；
- 在不触发第 13 节暂停门的前提下，实现可靠只读查询、上下文组装、领域 Hash、回放和兼容验证；
- 补齐单元、边界、契约、JSONB、跨语言和真实 PostgreSQL 测试；
- 完成 2F-0 后继续 `STRATEGY_BACKTEST` 确定性规则 V1 及其余 2F 工作包；
- 修复普通编译、类型、测试、局部重构和文档一致性问题；
- 为整个 2F 大阶段运行最终检查、同步权威文档、精确暂存、commit 并普通 push 当前任务分支，供 ChatGPT 基于实际提交验收。

`PROGRESS_LOG.md` 只能在形成已完成、已验收或已合入的历史里程碑后追加。Codex 不因内部工作包完成、文件数量或普通测试失败暂停，也不根据路线图自行开始 2G 或其他大阶段。

## 13. Codex 必须暂停项

仅在出现以下任一情况时暂停：

- 核心架构存在重大方案分歧；
- 需要改变数据库核心模型或执行不可逆迁移，包括必须新增不可变行情快照、PIT 核心模型或 `known_at` 持久化；
- 存在 Java/Python 公共契约的重大选择；
- 无法证明可消除的前视偏差，包括无法证明输入在 `knowledgeCutoff` 前已知；
- 涉及真实交易、真实下单或真实资金风险；
- 必须由用户选择窗口、费用、收益口径或其他高风险业务规则。

暂停报告和恢复条件统一遵循 [AGENTS.md](../../../AGENTS.md)：必须说明触发原因、影响范围、可选方案、兼容性和迁移风险、是否需要用户选择，以及是否需要调整 2F 大阶段架构、范围或验收标准。未获得所需选择或新授权前不得继续相关高风险实施。

## 14. ChatGPT 实际 Git 提交验收清单

Codex 为完整 2F 大阶段 commit 并 push 后，ChatGPT 必须基于实际任务分支和 commit 检查：

- [ ] 完整 commit diff 只包含 2F 大阶段授权范围，2F-0 没有被拆成单独提交或验收阶段；
- [ ] K1 已冻结三类时间关系、knowledge-time 证据和全部未来污染测试；
- [ ] G1 已冻结 canonical 契约，固定黄金向量可被独立执行，预期值不是只由被测实现生成；
- [ ] SQL 对 `requestTradeDate` 有显式上界，输入同时满足 `knowledgeCutoff`；
- [ ] cutoff 后覆盖、未来公司行动、来源修订、迟到数据和不同 knowledge-time 版本均被隔离或安全降级；
- [ ] 内容 Hash 没有被用作历史可得性的唯一证据；
- [ ] 没有网络、Python 数据库访问或旧汇总权威化；
- [ ] 策略/引擎/参数版本与全部七项参数完整冻结；
- [ ] 三类领域 Hash 的字段白名单、canonical 版本、编码、排序、空值、时间、精度、数据版本和兼容策略可审计；
- [ ] 同一冻结输入可重复回放，结果与 Hash 稳定；
- [ ] `STRATEGY_BACKTEST` 确定性规则 V1 只解释可靠事实，不产生正式 veto、投资建议或交易能力；
- [ ] 不可用路径不伪造零收益、中性或正常结论；
- [ ] 2B、2D-1、2E-1 兼容，六 run 与总控语义不变；
- [ ] JSONB 往返、失败原子性和精确清理通过；
- [ ] 真实 PostgreSQL `Skipped=0`，public 基线前后不变；
- [ ] 测试证据与 Codex 汇报一致，权威状态没有把未实现计划写成事实；
- [ ] Codex 没有 merge，也没有开始 2G 或其他大阶段。

本清单由 ChatGPT 窗口完成，不要求建立第三个独立 Codex Review 窗口。

## 15. 2F 大阶段完成统一汇报模板

1. 分支、冻结基线与最终 commit SHA
2. 仓库事实审计结论
3. `requestTradeDate`、`decisionTime` 与 `knowledgeCutoff`
4. Knowledge-time/PIT 证据、修订和快照语义
5. `backtestContext` 可用/不可用结构与 reasonCode
6. 策略、引擎、参数和数据版本
7. K1、G1 契约及 `DECISIONS.md` 记录位置
8. 三个领域 Hash 与外层 `contextHash`
9. 黄金向量和可重复回放证据
10. `STRATEGY_BACKTEST` 确定性规则与 reasonCode
11. 修改文件
12. Java/Python 契约与旧版本兼容
13. 单元、边界与未来污染测试
14. JSONB 与跨语言测试
15. 真实 PostgreSQL 结果及 `Skipped`
16. public Schema 前后状态
17. 完整回归
18. 是否修改迁移、外层 Schema 或核心架构
19. 是否产生正式 veto、投资建议或交易能力
20. commit 和 push 结果
21. `.ai/` 状态
22. 是否 merge
23. 是否开始 2G 或其他大阶段

## 16. 完成边界

2F-0 是 2F 大阶段的内部技术前置工作包，不单独 commit、push、Review 或验收。完成 2F-0 后，Codex 应在未触发暂停门时继续同一任务分支的 `STRATEGY_BACKTEST` 确定性规则 V1、测试和真实 PostgreSQL 验收；只有完整 2F 大阶段提交并推送后，才由 ChatGPT 基于实际 Git commit 验收。ChatGPT 验收通过后仍由用户批准 merge；Codex 不得自行开始 2G、2H、2I 或其他大阶段。
