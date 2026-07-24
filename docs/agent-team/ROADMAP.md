# 智能体团队路线图

具体当前状态以 [CURRENT_STATE.md](CURRENT_STATE.md) 为准；本文件只定义阶段顺序、依赖和验收门槛。

每个大阶段都遵守 Java 权威、Python 无状态、真实证据、无自动交易的冻结边界。未达到验收条件不得宣称完成。

路线图只控制方向，不授权自动实施。ChatGPT 每次规划一个较大的完整开发阶段；Codex 在一个任务分支连续完成其中全部内部工作包，自主测试、修复、commit 和普通 push；ChatGPT 基于实际 Git commit 验收，用户最终批准 merge。内部工作包不分别开发、Review、提交或验收，Codex 也不得自行进入下一大阶段。详细流程见仓库根目录 [AGENTS.md](../../AGENTS.md)。

既有细分编号继续作为历史、依赖和能力边界索引，不要求未来逐项建立任务分支或验收停顿；未完成工作恢复前，应由 ChatGPT 按完整能力重新组合为大阶段。

## 1D-4：工作台与本地运行闭环验收（已完成）

- 目标：验收 Vue 工作台和本地 Python/Java/Vue/PostgreSQL 闭环。
- 输入：1D-3 冻结契约、现有 API、V5、启停脚本。
- 输出：工作台、本地安全运行、验收记录与权威状态。
- 依赖：1D-1 至 1D-3。
- 禁止范围：真实行情、持仓、公告、LLM、评分策略、交易。
- 验收条件：分层测试、真实闭环、安全启停、状态治理均可审计。

## 2A：现有 PostgreSQL 第一批只读上下文（已完成）

- 目标：仅接入 security、marketData、technicalMetrics、dataQualityContext。
- 输入：现有 PostgreSQL 已有业务表。
- 输出：由 Java 基于冻结的本地 PostgreSQL 事实生成确定性只读 `contextSnapshot`；`dataQualityContext` 只包含数据质量事实。
- 依赖：1D-4 验收完成、表语义审计。
- 禁止范围：新外部数据源、公告、持仓、LLM、交易、真实评分扩张。
- 验收条件：四类上下文可复现、哈希稳定、无写操作、缺失数据不伪造。
- 阶段边界：2A 只完成上下文事实层和确定性技术指标，不实现 DATA_QUALITY 规则门禁，不升级六智能体分析规则，也不产生投资建议。

## 2B：DATA_QUALITY 规则门禁（已完成）

- 阶段位置：已完成并通过真实 PostgreSQL、Java、Python 闭环验收。
- 目标：基于阶段 2A 的只读事实实现 DATA_QUALITY 数据质量规则门禁，不回写或改写 `contextSnapshot`。
- 输入：2A 已冻结的 `security`、`marketData`、`technicalMetrics` 和 `dataQualityContext` 只读事实。
- 输出：可解释的缺失、时效、一致性 findings 与 gateStatus。
- 依赖：2A 已完成并验收。
- 禁止范围：正式 veto、投资推荐、LLM。
- 验收条件：规则边界、证据引用、阻断与非阻断样例跨语言一致。
- 验收结果：规则版本 `1.4.0-stage-2b-dq-v1`；四种状态映射、`veto=false`、唯一权威 evidence、微秒精度跨语言时间规范化、六 run 和总控持久化均已通过。

## 2C：第二批只读研究上下文（已完成）

- 阶段位置：已完成正式技术验收和真实 PostgreSQL 闭环验收。
- 目标：仅从现有 PostgreSQL 接入 `marketBreadth`、`scanResult`，并审计 `backtestContext` 的安全可用边界。
- 输入：现有市场宽度、扫描结果和回测业务表及其已审计语义。
- 输出：确定性、可追溯、可复现的 `marketBreadth` 与 `scanResult` 只读事实；`backtestContext` 因输入截止证据不可验证而保持结构化安全不可用。
- 依赖：2A、2B 已完成，以及相关业务表结构和字段语义审计。
- 禁止范围：外部数据补数、Python 直连业务数据库、智能体评分、LLM 和交易写操作。
- 验收条件：三类上下文来源明确、哈希稳定、缺失数据安全降级且不产生数据库写操作。
- 验收结果：`marketBreadth` 与 `scanResult` 已安全接入；`backtestContext` 使用 `BACKTEST_INPUT_CUTOFF_UNVERIFIABLE` 保持不可用；无 Flyway 和外层 Schema 变化，JSONB、Hash、无副作用、精确清理及测试前基线恢复均通过。

## 2D：MARKET_REGIME 真实规则（进行中）

- 阶段位置：阶段 2D-1 已完成实现和真实闭环验收；完整阶段 2D 尚未完成。
- 依赖：2A、2B、2C 全部完成。
- 禁止范围：外部行情补数、LLM 权威分类、收益承诺、投资推荐和交易写操作。
- 下一阶段唯一入口：阶段 2D-2B-1B-2 的正式来源与许可前置决策仍被阻断，approved source adapter 实现尚不能开始；不得把 2D-2B-2 或 Universe 视为可立即实施的下一阶段。

### 2D-1：当前证券池宽度状态规则（已完成）

- 输入：仅使用冻结的 `marketBreadth`；DATA_QUALITY 只作为前置门禁，`marketData`、`technicalMetrics` 和 `scanResult` 不参与该规则。
- 输出：仅对冻结请求当前日期形成正向、混合或负向宽度finding及确定性score；confidence固定为0。
- 安全边界：MARKET_REGIME不产生正式veto，总控不升级，仍保持 `finalDecision=INSUFFICIENT_DATA`。
- 验收结果：已通过自动化回归和真实 PostgreSQL/Python/Java 闭环；JSONB/Hash、非法响应原子失败、精确清理与测试前基线恢复均通过。
- 能力边界：当前证券池不是历史版本，不支持历史无前视分类；本规则不构成完整 MARKET_REGIME、牛熊判断、收益预测、投资建议或交易信号。

### 2D-2：历史证券池治理与完整 MARKET_REGIME 前置能力（进行中）

- 阶段位置：2D-2A 已完成，完整 2D-2 仍进行中。
- 目标：形成历史无前视的市场宽度上下文。
- 目标：建立可重复的历史样例。
- 目标：建立评测集和规则阈值治理。
- 完成门槛：阶段 2D-2 的全部依赖能力完成并形成实际 Git 提交、通过 ChatGPT 验收且由用户批准合入前，完整阶段 2D 不得标记完成。

#### 2D-2A：历史事实版本与交易日历基础模型（已完成）

- 输出：dataset 版本、不可变证券状态事件、双时间证券状态历史、SSE/SZSE 版本化交易日历和 Java as-of 查询。
- 冻结契约：`SECURITY_STATUS_EVENT_V1`、数据库不可变保护、上一/下一开市日动态推导。
- 验收结果：真实 PostgreSQL 及并发测试 `2/0/0/0`；开发、独立审查、修复和修复后复审全部完成。
- 能力边界：尚未生成历史 universe 快照，尚未治理 PIT 行情与公司行动，未实现 `MARKET_BREADTH_V2`。

#### 2D-2B：证券状态/日历摄取与版本化每日 universe 快照（进行中）

- 阶段位置：2D-2B-1A 与 2D-2B-1B-1 已完成；完整 2D-2B 仍进行中。
- 目标：在来源、身份、时间、assurance 和 lineage 可审计的前提下，逐步形成可追溯、版本化、可重复查询的每日 universe 快照。
- 禁止范围：PIT 行情与公司行动实现、`MARKET_BREADTH_V2`、MARKET_REGIME 规则升级、投资建议和交易写操作。
- 完成门槛：1B 事件摄取、双时间投影和 Universe 能力全部完成并作为完整大阶段提交通过 ChatGPT 验收、由用户批准合入前，不得标记完整 2D-2B 完成。

##### 2D-2B-1A：source-neutral ingestion foundation（已完成）

- 输出：V7 通用 ingestion run、security/calendar immutable raw、run-record 关联、terminal attempt、retry、namespace、assurance、封存与 `INGESTION_MANIFEST_V1`。
- 合入：集成提交 `505d18ca2e06c039163eada8f2f09f95cee97f30`。
- 验收：单元、真实 PostgreSQL 随机 Schema、两个 backend 并发、不可变、幂等、冲突、封存和 public 基线保护均通过。
- 能力边界：没有 event 物化、history/calendar projection 或 Universe；FORMAL 继续关闭。

##### 2D-2B-1B-0：security event contract freeze（已完成）

- 目标：冻结 TEST/DEMO security raw 到 `SECURITY_STATUS_EVENT_V1` 的显式稳定身份、active 语义、物化基数、normalization result、event lineage、Manifest V2、并发与原子失败契约。
- 输出：[stage-2d2b1b-security-event-materialization-design.md](stage-2d2b1b-security-event-materialization-design.md) 及跨文档一致性决策。
- 禁止范围：不创建迁移，不修改生产代码或测试，不接来源，不写 event/history，不生成 Universe。
- 验收结果：契约已冻结并通过独立 GitHub 审查；首个契约提交为 `c97d6a2c954f536eedd42796b1112aeaab421417`，复审修复提交为 `28c312dcbe26103c5f2b45c043ec6a8f81a08ae0`。
- 能力边界：完成设计冻结仍不代表 event 物化实现开始或具备 PIT。

##### 2D-2B-1B-1：TEST/DEMO event materialization foundation（已完成）

- 目标：在 1B-0 冻结契约下实现显式 identity mapping、normalization result、V1 event 物化、唯一 lineage 与 `INGESTION_MANIFEST_V2_SECURITY_EVENT`。
- 实现结果：V8 已实现 `manifestContractVersion`、TEST/DEMO 稳定证券身份、显式 source identity mapping、`SECURITY_STATUS_RAW_TEST_V1`、V1 event 物化与复用、normalization result、唯一 event lineage、Manifest V2、Java/PostgreSQL 双重门禁，以及幂等、两个 backend 并发和原子失败保护。
- 提交与合入：首次实现提交 `18151800d07fd7d2e6706b88869df5b7d0aa8ba0`；复审修复提交 `b6cb263f863f91753f043e0fa19e85501873111f`；独立 GitHub 复审 PASS；集成合并提交 `9aebcbf7d5a315d1edd61d85bf2944a454f72ffe`。
- 本地验收证据：以下均为 Codex 本地执行结果，不是 GitHub Actions CI——V8 真实 PostgreSQL `6/0/0/0`、`Skipped=0`；2D-2A 兼容 PostgreSQL `2/0/0/0`；2D-2B-1A 兼容 PostgreSQL `2/0/0/0`；`quant-server` `255/0/0/21`；`quant-core` `1/0/0/0`；Python unittest 68 项通过；Python `compileall` 与 `git diff --check` 通过。`quant-server` 的 21 项跳过是非数据库全量回归中的环境门禁跳过，不是真实 PostgreSQL 测试。
- 安全边界：FORMAL、真实来源、PIT_VERIFIED、V2 correction、history 写入、Universe 和扫描切换仍禁止；resolved event 在 2D-2B-2 前不得进入 history。
- 能力边界：仍无正式证券状态来源、真实 source adapter、正式 history/calendar projection 或 Universe。

##### 2D-2B-1B-2：approved source adapter（外部决策阻断）

- 目标：仅为经批准的证券状态来源实现 adapter，并冻结来源 instrument ID、revision、published/effective 时间、许可与持久化边界。
- 决策准备：[stage-2d2b1b2-source-decision-package.md](stage-2d2b1b2-source-decision-package.md) 只提供候选来源、书面询问、许可/PIT 门槛和样例验收框架；不批准任何来源，也不表示 adapter 已开始。
- 输入依赖：1B-1 已完成；仍须取得正式证券状态来源、数据许可、本地持久化权利、历史回放权利、稳定 source instrument ID、revision 语义和 published/effective 时间语义的明确批准。
- 阶段位置：上述外部前置决策仍被阻断，正式 adapter 实现尚不能开始；2D-2B 数据来源工作线的唯一入口是解决前置来源与许可决策，而不是编码 adapter。
- 阻断条件：来源、数据许可、本地持久化权利、历史回放权利、稳定 source instrument ID、revision 语义或 published/effective 时间语义任一未验证即不得开始。
- 禁止范围：不得以当前免费聚合源或 `securities` 当前态投影冒充正式 PIT 来源。
- 能力边界：adapter 完成不等于真实来源闭环通过。

##### 2D-2B-1B-3：真实来源闭环验收（未开始）

- 目标：对 approved adapter 执行真实来源、FORMAL namespace、许可边界、PIT assurance、幂等、修订、失败恢复与精确清理验收。
- 输入依赖：1B-2 完成并获独立许可批准。
- 禁止范围：不实现 history、Universe、PIT 行情或公司行动。
- 验收条件：真实来源记录身份、revision、发布时间、有效时间、known time 和 lineage 均可审计，真实 PostgreSQL 闭环 Skipped=0。
- 能力边界：通过后仍不代表双时间 history 或每日 Universe 已完成。

##### 2D-2B-2：history/calendar bitemporal projection（未开始）

- 目标：实现 V1/V2 证券状态双时间投影、局部 valid 更正、calendar raw 到 knowledge revision、lineage 闭包与 as-of 查询。
- 输入依赖：1B 事件摄取链完成；V2、更正、knowledgeCutoff 和日历来源决策独立冻结。
- 禁止范围：不生成 Universe，不读取 PIT 行情，不修改生产扫描。
- 验收条件：双时间区间、无重叠、无空洞、更正保留、assurance、并发和真实 PostgreSQL 回放可审计。
- 能力边界：完成后仍无不可变每日 Universe、PIT 行情或 `MARKET_BREADTH_V2`。

##### 2D-2B-3：Universe snapshot（未开始）

- 目标：实现不可变 snapshot manifest、逐行 inputs、members、三类 Hash、原子发布、回放和扫描影子验证。
- 输入依赖：2D-2B-2 完成，knowledgeCutoff 与 SSE/SZSE 组合日历规则冻结。
- 禁止范围：不切换生产扫描，不修改 `MARKET_BREADTH_V1`，不开始 2D-2C。
- 验收条件：无前视成员资格、输入 lineage、并发唯一发布、修订不覆盖、真实 PostgreSQL 和影子差异均可审计。
- 能力边界：完成后仍无 PIT 行情、公司行动、`MARKET_BREADTH_V2` 或完整 MARKET_REGIME。

## 2E：TECHNICAL_ANALYSIS 真实规则

### 2E-1：确定性规则 V1（已完成并合入）

- 目标：只解释 Java 已冻结的 `technicalMetrics` 与 `marketData`，不在 Python 拉取行情、访问业务数据库或隐式重算技术指标。
- 设计与规则：[stage-2e1-technical-analysis-v1.md](stage-2e1-technical-analysis-v1.md)。
- 规则版本：`1.4.0-stage-2e-technical-analysis-v1`。
- 实现与合入：实现提交 `93ccf7c6da380be91ca342f6c5e8815f8e7dfe07`；独立 GitHub 最终复审 PASS（HIGH 0 / MEDIUM 0 / LOW 0）；集成合并提交 `adb781c3ffb41ff13a14538067e838a60a65bea9`。
- 输出：趋势、RSI 动量/超买超卖风险、相对 MA20 偏离、相对波动和指标确认/冲突五类确定性 findings，两条直接投影 evidence，以及截断到 `[0,100]` 的确定性 score。
- 依赖：已完成的 2A 冻结输入和 2B DATA_QUALITY 门禁；阶段 2D-1 MARKET_REGIME 在新团队版本中保持原契约。
- 门禁：DATA_QUALITY BLOCKED 时不形成技术 evidence、finding 或正常评分；PASS/WARN 分别形成 `PASS/100` 与 `WARN/50` 的技术门禁和 confidence。非法技术输入以 `TECHNICAL_ANALYSIS_INPUT_INVALID` 安全降级，不伪造中性结论。
- 权限边界：TECHNICAL_ANALYSIS 永不产生正式 veto；POSITION_RISK 仍是唯一可能拥有正式否决权的专业智能体。总控继续保持安全的 `INSUFFICIENT_DATA` 或 `BLOCKED_BY_DATA_QUALITY`。
- 本地验收：Python `compileall` 与 unittest `77/0/0`；真实 Java/Python 跨语言 `4/0/0/0`、`Skipped=0`；随机临时 Schema 的真实 PostgreSQL `2/0/0/0`、`Skipped=0`；`quant-server` 全量 `261/0/0/27`；`quant-core` 全量 `1/0/0/0`。这些均为 Codex 本地执行证据，不是 GitHub Actions CI；27 项为无外部集成环境变量时的门禁跳过，不能冒充真实闭环。
- 数据库验收：真实 PostgreSQL 覆盖六个 run、证据顺序、空正式 veto、非法响应原子失败与精确清理；测试临时 Schema 删除，public 数据和结构指纹前后不变。没有修改 Flyway、V1 至 V8、public Schema 或外层 `contextSnapshot` Schema。
- 禁止范围：前视数据、外部数据源、source adapter、FORMAL/PIT、隐式指标重算、MARKET_REGIME 升级、`MARKET_BREADTH_V1` 修改、`backtestContext` 接入、LLM 事实/评分/结论、投资建议和交易写操作。
- 阶段边界：阶段 2E-1 完成并合入本身不自动批准或开始任何后续 2E 扩展、2F 或其他阶段；2F 的后续授权、实现和验收状态必须单独记录。

## 2F：可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1（已完成并合入）

- 当前状态：实现提交 `4ae0ac4ebc12aef559b9f88e7e1dfacc2b00a573`、knowledge-time 修复及最终提交 `4b1ee01a86b027ec43deaab18e6a68a098e0e2f4` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge，集成分支已 fast-forward 至最终提交。批准时间无仓库证据，记为 `UNKNOWN`。
- 交付文档：[完整 2F 任务书](tasks/2f-reliable-strategy-backtest-v1.md)、[2F-0 实现契约](tasks/2f0-backtest-context-foundation.md)和[阶段实现与本地验证记录](stage-2f-strategy-backtest-v1.md)。
- 连续交付：2F-0、knowledge-time/PIT、参数/版本/Hash、可回放事实、`STRATEGY_BACKTEST` V1、Java/Python 契约、自动化测试与真实 PostgreSQL 已作为同一大阶段连续实现，不拆成独立提交或验收阶段。
- PIT 模型：V9 新增 append-only `market_data_observation_batches` 与 `daily_bar_observations`；`daily_bars` 继续作为当前态兼容投影。持久化入口和数据库均拒绝周末日线；可靠观察只接受周一至周五且 `firstObservedAt`、`knownAt` 均不早于该交易日上海时间 15:00 的完整日线。工作日收盘前当日日线不进入 PIT、不产生空批次，但可继续更新兼容投影。合格观察版本与当前态在同一事务内持久化，as-of 输入同时受 `tradeDate` 与 `knowledgeCutoff` 约束。V1 至 V8 不变，不回填或伪造历史 known time。
- 兼容 profile：只有规则版本 `1.4.0-stage-2f-strategy-backtest-v1` 选择 `AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1`；旧入口和 2B、2D-1、2E-1 的 contextSnapshot、contextHash、缓存键与结果保持兼容。
- Canonical 契约：`BACKTEST_CANONICAL_V1` 冻结 SHA-256、编码、Unicode、对象/数组顺序、UTC 微秒时间、Decimal、null/缺失、字段白名单与独立 `dataVersion`；Java 生成 `inputDataHash`、`strategyDefinitionHash`、`backtestResultHash`，Java/Python 使用固定输入、canonical 文本和预期 Hash 的黄金向量交叉验证。
- 策略事实：冻结 `SMA20_NEXT_OPEN_RISK_EXIT_V1/BACKTEST_ENGINE_V1/BACKTEST_PARAMS_V1` 和七项完整参数；Java 执行完整窗口及 EARLY/MIDDLE/LATE 三个稳定子区间，Python 不重跑回测。
- 规则输出：有效输入固定产生样本充分性、总收益、最大回撤、胜率与盈亏比、跨时间子区间稳定性五类 finding，按冻结阈值计算 `[0,100]` score 与最高 80 confidence。DATA_QUALITY 阻断、上下文不可用、输入非法或交易样本不足均安全降级。
- 当前安全限制：普通配置来源没有可验证 revision，因此真实普通捕获仍返回 `BACKTEST_SOURCE_REVISION_UNVERIFIABLE`，不会被误写为可靠历史输入。内容 Hash 不替代 knowledge-time 证据。
- 本地验收：针对最终 knowledge-time 修复的真实 2F V1 至 V9 PostgreSQL `7/0/0/0`、真实 Java/Python `4/0/0/0`、真实 PostgreSQL/Python/JSONB/原子失败 `2/0/0/0`，均 `Skipped=0`；其他回归与已知 public V6 checksum 环境问题详见阶段文档。这些是 Codex 本地证据，不是 GitHub Actions CI。
- 禁止范围：外部行情、旧结果权威化、参数寻优、投资建议、收益承诺、自动交易、正式 veto 或总控升级。POSITION_RISK 仍是唯一正式否决权。
- 阶段边界：2F 已完成并合入不自动批准或开始 2G、2H、2I 或其他阶段。

## 2G：公告上下文和 ANNOUNCEMENT_RISK（未开始）

- 当前状态：正式公告来源、数据许可和 revision/时间语义尚未解决，2G 继续阻断且未开始；暂缓不等于放弃 2G。
- 目标：接入可验证公告上下文并建立公告风险规则。
- 输入：经批准的公告数据源与 securityEvents。
- 输出：事件证据、严重度和非正式风险提示。
- 依赖：数据源合规与缓存设计必须在对应大阶段架构中先行冻结。
- 禁止范围：新闻编造、非 POSITION_RISK 正式 veto、LLM 事实生成。
- 验收条件：原文引用可追溯、时间准确、重复事件去重。

## 2H：可靠模拟持仓上下文与 POSITION_RISK 正式否决 V1（任务分支待验收）

- 当前状态：任务分支 `codex/1.4.0-stage-2h-position-risk-v1` 已完成实现与 Codex 本地验证；尚待 ChatGPT 基于实际 Git commit 验收，尚未合入集成分支。本状态不等于验收 PASS 或用户 merge 批准。
- 优先实施原因：2H 完全依赖本地模拟账户和本地 PostgreSQL，不依赖 2G 尚未解决的外部公告来源；该顺序不代表放弃 2G，也不自动批准 2I。
- 交付文档：[完整 2H 任务书](tasks/2h-reliable-position-risk-v1.md)和[阶段实现与本地验证记录](stage-2h-position-risk-v1.md)。
- 版本：规则 `1.4.0-stage-2h-position-risk-v1`、profile `AGENT_CONTEXT_2H_V1`、Schema `PORTFOLIO_CONTEXT_V1`，只对精确 2H 规则版本启用。
- 输入边界：Agent 专用只读 Repository 在同一 `REPEATABLE_READ` 只读事务内冻结默认模拟账户 `accountId=1`、持仓、待确认委托、本地 QFQ 估值价及权益历史；只支持上海时区当前自然日，不声明历史持仓 PIT。
- 输出：五类稳定 finding、确定性 safety score/confidence、按冻结顺序生成的正式 veto，以及正式 veto 优先于 DATA_QUALITY 阻断的总控结论。只有 POSITION_RISK 可以产生正式 veto，六个 run 不变。
- 只读与兼容：不调用会结算、刷新行情、保存快照或写风险事件的业务方法，不修改任何模拟账户业务表；旧 2B、2D-1、2E-1、2F profile/contextHash 和规则保持兼容，没有新增 Flyway。
- 本地验收：`quant-core` `4/0/0/0`；2H Java 定向 `26/0/0/0`；Python `compileall` 通过、完整 unittest `92/0/0/0`；真实 Java/Python `4/0/0/0`、真实 V1 至 V9 PostgreSQL `2/0/0/0`，均 `Skipped=0`；`quant-server` 安全全量 `301/0/0/46`；2D/2E/2F 真实兼容 `29/0/0/0`、`Skipped=0`。这些是 Codex 本地证据，不是 GitHub Actions CI；46 项是环境门禁跳过。
- 禁止范围：真实账户、券商控制、自动下单、交易执行指令、业务表写入、外部数据源、2G、2I 或其他阶段。
- 阶段边界：Codex 完成单次 commit 和普通 push 后停止，由 ChatGPT 检查实际提交；验收通过后仍须用户批准 merge，不得自行合并或开始下一阶段。

## 2I：总控综合决策（未开始）

- 目标：基于六个权威 run 和证据形成确定性综合决策。
- 输入：六智能体结构化结果、正式 veto、数据质量门禁。
- 输出：一致的 finalDecision，不创建第七个 run。
- 依赖：2B 至 2H 达到验收条件。
- 禁止范围：脱离证据的自然语言裁决、LLM 权威决策。
- 验收条件：sourceRunIds/vetoIds 完整，门禁、否决和评分规则可复现。

## 3A：影子运行（未开始）

- 目标：在不影响交易的情况下长期观察规则稳定性。
- 输入：真实只读上下文和完整团队结果。
- 输出：运行指标、失败分布、漂移和人工复核记录。
- 依赖：2I。
- 禁止范围：自动交易、账户写入、收益宣传。
- 验收条件：安全运行窗口、可回滚版本、人工审阅流程完备。

## 3B：评测集、版本管理和长期复盘（未开始）

- 目标：建立固定评测集、规则版本和长期复盘机制。
- 输入：影子运行数据与人工标注。
- 输出：回归基线、版本报告、偏差与失败案例库。
- 依赖：3A。
- 禁止范围：用单一收益指标替代风险评估、删除失败样本。
- 验收条件：可重复评测、版本可追溯、升级门槛明确。

## 最后：评估 LLM 解释层（未开始）

- 目标：评估 LLM 是否能在不改变权威结果的前提下改善解释。
- 输入：已冻结的结构化结果、脱敏证据和评测集。
- 输出：可选解释文本及独立质量评估。
- 依赖：3B，另需成本、安全、隐私评审。
- 禁止范围：让 LLM 生成事实、评分、证据、veto 或交易指令。
- 验收条件：关闭 LLM 不影响业务结果，解释可审计且不泄密。
