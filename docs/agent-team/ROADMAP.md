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

## 2G：AKShare/CNINFO 公告事实基础与 ANNOUNCEMENT_RISK 确定性规则 V1（已完成并合入）

- 当前状态：实现提交 `9213507785323ab286d2cae147cf1d893dc102b6`、混合标题与 CNINFO 域名门禁修复及最终提交 `681fee989f08c4c1e4edaa8cf787c97a95a27784` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge，集成分支已纯 fast-forward 至最终提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。该状态不等于来源取得正式资格。
- 交付文档：[完整 2G 任务书](tasks/2g-akshare-announcement-risk-v1.md)和[阶段实现与本地验证记录](stage-2g-announcement-risk-v1.md)。
- 来源边界：固定 `akshare==1.18.64`，通过公开 `stock_zh_a_disclosure_report_cninfo` 函数提供 `AKSHARE_CNINFO_RESEARCH_V1/AKSHARE_CNINFO_PROVIDER_V1`。公告 URL 只允许 `cninfo.com.cn` 或其真实子域及默认端口；Provider、Java、Python 和 V10 共同拒绝相似域名、userinfo 与非默认端口。来源资格固定为 `RESEARCH`、`formalEligible=false`、`pitVerified=false`、`revisionRelationshipGuaranteed=false`、`DATE_ONLY`；不得宣称正式授权、历史绝对完整或自动交易资格。
- Provider 边界：FastAPI Provider Bridge 与 Agent Rule Engine 严格分离；只有手动、默认关闭的 Java 摄取入口能够调用 Provider。Agent 任务创建和分析不访问 AKShare、数据库外部网络或 PDF。
- 事实模型：V10 新增 append-only `announcement_capture_batches` 与 `announcement_observations`；完整空批次是有效覆盖，部分批次不能证明无风险公告。Java 冻结 `firstObservedAt` 和 `knownAt`，生成 `ANNOUNCEMENT_CANONICAL_V1` Hash 与观察版本；同内容幂等，变化和 A→B→A 保留新版本，不回填历史 knowledge-time。
- 兼容 profile：只有规则版本 `1.4.0-stage-2g-announcement-risk-v1` 选择 `AGENT_CONTEXT_2G_V1/SECURITY_EVENTS_CONTEXT_V1`，同时复用 2F backtestContext 与 2H portfolioContext；旧 2B、2D-1、2E-1、2F、2H profile/contextHash 保持兼容。
- 规则输出：Python 只解释 Java 冻结的公告标题和元数据，按退市/监管、财务债务诉讼、股东减持质押担保经营、更正澄清等冻结关键词、短语级排除和最高 severity 运行确定性规则。明确安全短语只从对应规则匹配副本中移除，混合标题中的继续或新增风险仍被保留。每公告只扣分一次，四档 recency 使用 `HALF_UP`，confidence 固定 40，生成五类 finding、coverage/event evidence，永不生成正式 veto。
- 总控边界：2G 本身保持 POSITION_RISK 正式 veto 最高优先，其次 DATA_QUALITY 阻断；两者均不存在时六个专业 run 已执行，但 2G 规则版本仍为 `INSUFFICIENT_DATA/0/0`。2G 不实现综合评分、投资结论或交易指令。
- 本地验收：真实 AKShare 安全门和 Live Gate 使用 `000001` 受控历史范围返回 38 行，新增 CNINFO 域名门禁未拒绝真实链接；2G Java 定向 `42/0/0/0`、Python unittest `112/0/0/0`、真实 V1 至 V10 PostgreSQL/跨语言/Live Gate 均 `Skipped=0`；`quant-core` `4/0/0/0`、安全非数据库 `quant-server` `334/0/0/56`、2D/2E/2F/2H 真实兼容 `35/0/0/0`。这些是 Codex 本地证据，不是 GitHub Actions CI；56 项为环境门禁跳过。
- 禁止范围：正式来源资格、FORMAL/PIT、隐藏接口或反爬绕过、PDF 批量下载、定时或全市场抓取、LLM 事实生成、非 POSITION_RISK 正式 veto、投资建议和自动交易。
- 阶段边界：2G 已完成并合入；该事实本身不曾自动批准 2I，2I 的当前授权、实现与验收状态单独记录。

## 2H：可靠模拟持仓上下文与 POSITION_RISK 正式否决 V1（已完成并合入）

- 当前状态：实现及最终提交 `a898e21df38594a6aca1429a3dfd5e28c2cf7f72` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge，集成分支已 fast-forward 至该提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 优先实施原因：2H 完全依赖本地模拟账户和本地 PostgreSQL，因此在当时 2G 外部公告来源方案尚未冻结时先行完成；该历史顺序不代表放弃 2G，也不自动批准 2I。
- 交付文档：[完整 2H 任务书](tasks/2h-reliable-position-risk-v1.md)和[阶段实现与本地验证记录](stage-2h-position-risk-v1.md)。
- 版本：规则 `1.4.0-stage-2h-position-risk-v1`、profile `AGENT_CONTEXT_2H_V1`、Schema `PORTFOLIO_CONTEXT_V1`，只对精确 2H 规则版本启用。
- 输入边界：Agent 专用只读 Repository 在同一 `REPEATABLE_READ` 只读事务内冻结默认模拟账户 `accountId=1`、持仓、待确认委托、本地 QFQ 估值价及权益历史；只支持上海时区当前自然日，不声明历史持仓 PIT。
- 输出：五类稳定 finding、确定性 safety score/confidence、按冻结顺序生成的正式 veto，以及正式 veto 优先于 DATA_QUALITY 阻断的总控结论。只有 POSITION_RISK 可以产生正式 veto，六个 run 不变。
- 只读与兼容：不调用会结算、刷新行情、保存快照或写风险事件的业务方法，不修改任何模拟账户业务表；旧 2B、2D-1、2E-1、2F profile/contextHash 和规则保持兼容，没有新增 Flyway。
- 本地验收：`quant-core` `4/0/0/0`；2H Java 定向 `26/0/0/0`；Python `compileall` 通过、完整 unittest `92/0/0/0`；真实 Java/Python `4/0/0/0`、真实 V1 至 V9 PostgreSQL `2/0/0/0`，均 `Skipped=0`；`quant-server` 安全全量 `301/0/0/46`；2D/2E/2F 真实兼容 `29/0/0/0`、`Skipped=0`。这些是 Codex 本地证据，不是 GitHub Actions CI；46 项是环境门禁跳过。
- 禁止范围：真实账户、券商控制、自动下单、交易执行指令、业务表写入、外部数据源或历史持仓 PIT。
- 阶段边界：2H 已完成并合入不自动批准或开始 2G、2I 或其他阶段。

## 2I：确定性总控综合决策 V1（已完成并合入）

- 当前状态：实现提交 `8c391be46aa7c823577c0a15f866165473341708`，终态修复及最终提交 `954959f5832d01ba1f7211d3e6ebbd8c93feab22`；ChatGPT 已基于实际最终 Git 提交验收 PASS，用户已批准 merge，集成分支已纯 fast-forward 至最终提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 交付文档：[完整 2I 任务书](tasks/2i-deterministic-chief-decision-v1.md)和[阶段实现与本地验证记录](stage-2i-chief-decision-v1.md)。
- 版本与兼容：规则 `1.4.0-stage-2i-chief-decision-v1`、总控契约 `CHIEF_DECISION_V1`、权重契约 `CHIEF_SCORE_WEIGHTS_V1`；精确 2I 规则版本复用与 2G 完全相同的 `AGENT_CONTEXT_2G_V1` 组合上下文，不增加外层字段、子 Context Schema、Flyway 或第七个 run，也不改变旧规则版本结果和 contextHash。
- 输入角色：DATA_QUALITY 只作门禁和 confidence 上限；MARKET_REGIME V1 的 score/confidence 权重均为 0，但必须处于合法终态；正常综合只使用 TECHNICAL_ANALYSIS 25、STRATEGY_BACKTEST 35、ANNOUNCEMENT_RISK 20、POSITION_RISK 20。
- 优先级：合法 POSITION_RISK 正式 veto 最高，其次 DATA_QUALITY 阻断，再次为必要 run 不足；只有完整输入才按固定 `HALF_UP` 公式形成 `RESEARCH_ONLY`、`WATCH` 或 `PASS_TO_MANUAL_REVIEW`。
- 输出：`sourceRunIds` 精确包含固定六 run，`vetoIds` 只引用 POSITION_RISK；顶层 findings/evidence 继续按六 run 顺序拼接，不创建总控 finding/evidence。Java 在持久化前独立复算决策、权重、边界、风险 severity、summary 和顺序，非法响应原子失败。
- 持久化终态：专业 run 状态与总控决策状态分层保存。精确 2I ruleVersion 的 `REJECTED_BY_VETO`、`BLOCKED_BY_DATA_QUALITY`、`RESEARCH_ONLY`、`WATCH`、`PASS_TO_MANUAL_REVIEW` 均为完成的确定结果并进入 completed cache；只有最终 `INSUFFICIENT_DATA` 保持 `task=PARTIAL/decision status=INSUFFICIENT_DATA`。旧阶段映射不变。
- 工作台：只增加六类 finalDecision 的中文展示标签；`PASS_TO_MANUAL_REVIEW` 仅表示进入人工研究复核，不增加买卖、下单、仓位调整或收益预测能力。
- 本地验收：2I Java 纯规则、context 与终态映射 `23/0/0/0`、Python unittest `123/0/0/0`、真实 Java/Python HTTP `12/0/0/0`、旧阶段真实 HTTP 兼容 `17/0/0/0`、V1 至 V10 真实 PostgreSQL/Python/任务持久化 `2/0/0/0`、随机隔离 Schema PostgreSQL 兼容 `26/0/0/0`、Java AKShare Live Gate `1/0/0/0`，真实组均 `Skipped=0`；`quant-core` `4/0/0/0`、安全非数据库 `quant-server` `360/0/0/69`、Vue build 通过。这些均为 Codex 本地证据，不是 GitHub Actions CI；69 项是环境门禁跳过，不能冒充真实闭环。真实持久化覆盖五种确定总控结果完成缓存、最终不足非完成、专业 run 原状态和物理 veto 映射；随机 Schema 已精确清理，public 基线不变。
- 禁止范围：LLM 权威裁决、专业规则阈值漂移、外部访问、事实写入、真实账户、自动交易、投资建议和 2I 专用迁移。
- 阶段边界：2I 已完成并合入不代表普通请求必然具备完整输入，也不自动完成 3A 或批准 3B。

## 3A：受控影子运行、就绪度观测与长期验证（进行中）

- 目标：在不影响交易和业务事实的情况下，以真实 2I Agent 任务长期观察规则稳定性、就绪度、失败、漂移和人工复核。
- 输入：真实只读上下文、完整团队结果、当前持仓和最新已完成扫描候选的受控选择。
- 输出：影子批次/item、决策与不足分布、漂移、数据库事实指标和 append-only 人工复核记录。
- 依赖：已完成并合入的 2I。
- 禁止范围：自动交易、账户或市场事实写入、自动外部摄取、全市场遍历、收益统计或宣传。
- 完整验收条件：不少于 20 个有效观察日、200 个 shadow item，覆盖确定、不足、阻断、veto 和失败；主要 reasonCode 完成人工复核；持续验证业务表只读；形成正式观察报告，再由 ChatGPT 和用户决定是否进入 3B。
- 当前真实观察：Day 001 已创建 1 个 EXPLICIT 受控批次和 3 个 item，均以 `BLOCKED_BY_DATA_QUALITY` 安全终结并完成正式 `DATA_ISSUE` 人工复核。对三个 symbol 的受控行情更新已消除 `MARKET_DATA_TOO_STALE`，但新增 780 条 V9 观察全部 `sourceRevision=NULL`；Day 002 未创建，scheduler 仍关闭。该 1 日/3 item 记录不满足完整 3A 门槛。

### 3A-1：受控影子运行与就绪度观测基础 V1（已完成并合入）

- 当前状态：实现及最终提交 `99b369fcc652b8344453532a7ff9597751a6040b` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge，集成分支已纯 fast-forward 至该提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。scheduler 默认关闭，未开始长期观察。
- 交付文档：[完整 3A-1 任务书](tasks/3a1-controlled-shadow-readiness-v1.md)和[阶段实现与本地验证记录](stage-3a1-shadow-readiness-v1.md)。
- V11：新增 `agent_shadow_batches`、`agent_shadow_items`、append-only `agent_shadow_reviews`，并增加 `SHADOW` TriggerType；批次/item 终态事实由数据库触发器保护，复核更正只能追加。
- 选择：EXPLICIT 只接受去重排序后的 1 至 20 个六位 symbol；AUTO 先当前模拟持仓、后最新已完成扫描 eligible 候选，默认 10、硬上限 20，选择和来源引用通过稳定 Hash 冻结。
- 运行：默认 `enabled=false/scheduler-enabled=false`；双开关、安全窗口、工作日、运行冲突、最大并发 2、取消与批次级熔断共同保护。runner 只通过现有 Java 任务系统执行精确 2I 规则，不直接调用 Python 或写 Agent 结果。
- 观测：结构化提取六 run reasonCode，比较同 symbol/同 ruleVersion 最近终态的 context、决策、分数、confidence、run、veto 与 reason 漂移；指标由数据库事实即时查询，不维护手工计数缓存。
- 本地验收：3A-1 Java 定向 `15/0/0/0`、Python unittest `123/0/0/0`、V1 至 V11 真实 PostgreSQL/Java/Python 受控试运行 `1/0/0/0`、V1 至 V11 旧阶段真实兼容 `29/0/0/0`、AKShare Live Gate `1/0/0/0`，真实组均 `Skipped=0`；`quant-core` `4/0/0/0`、安全 `quant-server` `376/0/0/41`、Vue build 通过。这些是 Codex 本地证据，不是 GitHub Actions CI；41 项为外部 PostgreSQL/AKShare 环境门禁跳过。
- 阶段边界：3A-1 只建立技术基础，不伪造长期观察历史，不完成 3A，不启动 3B。

### 3A-R1：Flyway V6 迁移血统恢复（已完成并合入）

- 当前状态：实现及最终提交 `4fea1e210e683fea8490685879529f1d27e6448b` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge，集成分支已纯 fast-forward 至该提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 交付文档：[完整 3A-R1 任务书](tasks/3ar1-flyway-v6-lineage-recovery.md)和[阶段实现与本地验证记录](stage-3ar1-flyway-v6-lineage-recovery.md)。
- 血统：V6 恢复为提交 `39f929aadebf9e1df6c392d38b97d7058b17dfff` 的已应用内容，checksum `-981595186`；首个改写提交为 `3a3eebd2ef580d31a6b02aab1a7204ea02fdba58`。V7 至 V11 不依赖后来 delta 在 V7 前存在，因此 V12 前向承接不可变、knowledge-close 和旧日历导航列删除。
- public 事件：第一次全量回归中，既有 `AgentEvidenceVetoPostgresIntegrationTest` 的隔离缺陷把专用测试库 public 通过合法链从 V6 前向迁移到 V12。没有 repair、clean、回滚、恢复备份、删除对象、修改历史或手工 checksum；这不是生产迁移。迁移前完整业务表指纹没有证据，无法验证的比较记为 `UNKNOWN`。
- 隔离：所有执行 Flyway migrate 的 PostgreSQL 测试必须创建随机 Schema，显式设置 datasource `currentSchema`、Hikari schema、Flyway default-schema/schemas 和 `create-schemas=false`；目标为 public 时立即失败。当前 public 只做只读 validate 和完整指纹保护。
- 本地验收：V6/V12 静态与隔离安全门 `16/0/0/0`、原触发类隔离复测 `2/0/0/0`、双血统迁移/指纹/public validate `1/0/0/0`、旧血统克隆 Java/Python 单证券 `1/0/0/0`、全部真实 PostgreSQL 兼容矩阵 `47/0/0/0`、AKShare Live Gate `1/0/0/0`，真实组均 `Skipped=0`；`quant-server` `388/0/0/0`、`quant-core` `4/0/0/0`、Python `123/0/0/0` 与 compileall、Vue build 均通过。随机 Schema 残留为 0，接受后的 public V12 基线前后不变。
- 阶段边界：3A-R1 只恢复迁移血统和测试隔离，不修改 Shadow 功能或 Agent 规则，不创建 public 真实 Shadow 批次，不开启 scheduler，不积累长期观察日，也不启动 3B。

### 3A-R2：可靠行情来源 revision 资格审计（只读审计已完成）

- 当前结论：现有 `POST /api/data/history/sync` 链路从 Java 到 Python AKShare/Tencent，再回到 Java `List<Bar>` 和 V9，只传递 symbol、来源名、交易日与行情数值；普通持久化入口固定使用 `sourceRevision=null`，本地 `LOCAL_DATASET_V1-<UUID>` 只表示本地捕获批次。
- 受控证据：`000001` 两次公开 Provider 请求的行数、内容、原始 body 和 Tencent `version=18` 均一致，但没有正式字段语义、修订关系、历史版本或发布时间证据。结论为 `PROVIDER_REVISION_UNVERIFIED`；`version=18`、HTTP Date、UUID、本地 dataset/observation version、内容 Hash 和抓取时间均不得冒充 source revision。
- 阶段边界：该审计没有修改代码或数据库，没有接入来源、改变 2F V1、创建 Day 002 或开始 3B。

### 3A-R3A：可验证 PIT 市场原始事实 V2 设计冻结（设计已完成并合入）

- 当前状态：首次设计提交 `be12916ab0db07ceaa040883397424e10828b867` 和 QFQ factor 选择语义修复及最终提交 `94d442fa5fcad874462c54ca83b4ba21dcf7d3b4` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准纯 fast-forward 合入，集成分支已到达最终提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 交付文档：[完整 3A-R3A 任务书](tasks/3ar3a-pit-market-facts-v2-design.md)和[阶段设计记录](stage-3ar3a-pit-market-facts-v2-design.md)。
- 来源路线：Tushare 是 raw daily、`adj_factor`、`trade_cal` 的优先技术候选，但书面许可、样例和版本语义未通过前不批准；AKShare-Tencent 仅保留 current projection 与交叉校验角色；Wind 等企业来源需取得 revision、旧版本、发布时间、许可和合同附件证据。
- 事实模型：候选 `PIT_MARKET_FACTS_V2` 把 raw daily、复权因子、交易日历和公司行动保存为四类独立 append-only 观察，严格区分 `PROVIDER_PIT_VERIFIED` 与首次捕获之后才可用的 `SYSTEM_KNOWLEDGE_PIT`。
- 兼容边界：2F V1、V9、旧 profile、contextHash 和缓存键完全不变；未来 V2 使用独立规则版本/profile。设计合入不表示 Provider 已批准、生产实现或迁移已完成，也没有批准 Day 002 或 3B。
- 后续入口：按 [3A-R3B 免费优先 Provider 验证规划](tasks/3ar3b-free-first-provider-validation-plan.md)，先用免费数据验证产品形态、系统价值和数据瓶颈，再决定是否把 iFinD 或其他付费 Provider 作为专业化升级；任务书和路线图本身不构成自动实施授权。

### 3A-R3B：免费优先验证、付费 Provider 后置升级与资格取证

- 规划文档：[免费优先完整任务书](tasks/3ar3b-free-first-provider-validation-plan.md)、[免费优先阶段记录](stage-3ar3b-free-first-provider-validation-plan.md)、[iFinD 里程碑任务书](tasks/3ar3b-ifind-trial-activation-plan.md)和[iFinD 阶段规划记录](stage-3ar3b-ifind-trial-activation-plan.md)。
- 既有规划状态：iFinD 里程碑规划提交 `23baf11ed3a236800b5f3feba8681d261a71d9f9` 已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯 fast-forward 合入。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 免费优先更新：最终提交 `c47b88e586f6751563fe210f40137a3b7ce5e576` 已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯 fast-forward 合入。系统先用免费数据验证产品形态和效果；只有系统显示可重复使用价值、数据成为可量化主要瓶颈，且同范围免费/付费 A/B 方案和成本意愿均明确后，才考虑 iFinD 或其他付费 Provider。
- 日期边界：iFinD 试用不得绑定 `2026-08-31`、2026 年 8 月 31 日或任何其他固定日期。日历日期只能作为非权威临时估算，不属于路线图依赖，不得因预计日期临近而降低验收标准。
- 当前集成 HEAD：`d223fdf9ff997ca256f2d0f651c99542e817dfee`。当前状态：`F0_AUDIT_RESULT=PARTIAL`、`FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST`、`FREE_PRODUCT_PREVIEW_GATE=PASS`、`FREE_PROVIDER_VALIDATION_GATE=BLOCKED`、`PAID_PROVIDER_UPGRADE_DECISION=PENDING`、`IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`。正常业务库 V13 未执行，iFinD 调用数为 0；Track A、Track B0 与 Track B1 均已验收并纯 fast-forward 合入。用户已开通 Tushare 2000 积分，十项 B1 受控权限探针均 PASS；官方书面只确认可作为量化数据来源，未逐项确认本地长期存储、回测和 Agent。用户已批准个人自用有限实现。F1A 有限 Adapter 修复已在任务分支完成，待实际 Git 验收且尚未合入；完整 F1 仍受技术证据阻断，F2B/F3 均未开始，Day 002 未创建，scheduler 关闭，3B 未开始。

#### 3A-R3B-0：Provider 中立离线闭环与试用准备（已完成并合入）

- 当前状态：最终提交 `f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819` 已通过 ChatGPT 对实际 Git 提交的最终复验；用户已批准纯 fast-forward 合入，当前本地和远程集成分支均到达该提交，ahead/behind 为 `0/0`。
- 任务证据：[3A-R3B-0 任务书](tasks/3ar3b0-provider-neutral-pit-offline-v2.md)、[阶段记录](stage-3ar3b0-provider-neutral-pit-offline-v2.md)和[iFinD 试用调用矩阵](ifind-trial-call-matrix.md)。
- 实现：V13 独立建立 raw daily/factor/calendar/corporate action 四类 PIT 事实和 append-only lineage；Java 建立 Provider 中立 DTO/capability、canonical、as-of Repository 和 `DAILY_EXACT` QFQ 引擎；精确 V2 ruleVersion 建立 2F V2、六智能体和 EXPLICIT Mock Shadow；同时建立默认禁用且网络前失败的 iFinD 骨架、脱敏和离线夹具工具。
- 增量修复：资格感知 knowledge-time 明确区分 Provider published time 与系统首次捕获；幂等改为完整 semantic content hash；四类事实使用独立来源身份；raw 非价格字段具有可空值、单位、语义和资格；公司行动必须精确匹配因子日期与身份；18 个黄金场景改为 Java 实际执行的固定输入/输出/lineage/hash 向量。
- 第二次增量修复：四类 as-of 查询先按资格、knownAt、chainSequence 和 id 选定唯一语义版本，再检查用途和许可；许可撤销固定返回 `PIT_USAGE_NOT_ALLOWED`，禁止回退旧允许版本。V13 batch 同步拒绝非 `PROVIDER_VERIFIED` 资格携带 `providerDatasetVersion`。
- 验证状态：18 个 QFQ 可执行黄金向量、随机 Schema V1→V13、真实 Java/Python/PostgreSQL Mock Shadow 及相关回归已通过最终提交验收。
- 部署边界：V13 代码已经进入集成分支，但正常业务库尚未执行 V13。Mock/TEST/DEMO 不取得真实 Provider 资格；真实 iFinD 调用数保持 0，不创建 Day 002。

#### 3A-R3B-F0：免费 Provider 资格审计（已完成并合入）

- 性质：只读调查、最小受控探针与证据规划，不实现 Adapter。
- 当前状态：最终提交 `059eacffaf7e4a9f383be205d453c5168279932a` 已通过 ChatGPT 对实际 Git 提交的最终复验；用户已批准并完成纯 fast-forward 合入，本地和远程集成分支均位于该提交，ahead/behind 为 `0/0`。
- 候选角色：BaoStock 作为免费主 Provider 技术候选；AKShare/Tencent 作为研究级当前投影、辅助与交叉校验候选；巨潮资讯、上交所、深交所公开信息作为公告、公司行动、交易日历和规则的官方证据候选。其他免费来源必须先形成独立审计证据。所有角色均为候选，不是批准。
- 审计范围：未复权 raw daily、独立复权因子、`DAILY_EXACT`、交易所日历、公司行动、四类稳定来源身份、单位/精度/空值/明确 0、时效与静默修正、限流与结构变化、本地持久化/历史回放/回测/Agent/商业化权利、revision/snapshot/published/update time、旧版本查询、来源差异和维护风险。
- 禁止推断：开源客户端不证明底层数据商业许可；不得从 QFQ 价格反推因子；不得跨 Provider 拼成伪造同源 PIT lineage。
- 审计交付：[F0任务书](tasks/3ar3b-f0-free-provider-qualification-audit.md)、[阶段记录](stage-3ar3b-f0-free-provider-qualification-audit.md)、[能力矩阵](free-provider-capability-matrix.md)、[证据登记册](free-provider-evidence-register.md)、[探针矩阵](free-provider-probe-matrix.md)和[书面许可问题](free-provider-written-permission-questions.md)。
- 实际结果：`F0_AUDIT_RESULT=PARTIAL`。BaoStock 0.9.3 raw/QFQ 日线各观察到 6 行、通用日历观察到 8 行、公司行动观察到 1 行，两个按证券因子查询在固定短区间各观察到 0 行；修复前 collector 未复核迭代终态，因此本次 Live response completeness 为 `UNVERIFIED`。独立因子为 `PARTIAL`、`DAILY_EXACT=UNVERIFIED`，客户端 BSD License 不能替代底层数据许可，因此角色为 `PENDING_WRITTEN_PERMISSION`。AKShare 必须按 Tencent/Sina/Eastmoney/CNINFO 上游拆分并保持研究辅助，CNINFO/SSE/SZSE/SZSI 只作官方证据。当前没有一个免费来源能单独承担完整 V13/QFQ 同源 lineage。
- 结论边界：`PARTIAL` 不是审计失败，但不批准 F1，也不改变 `FREE_PROVIDER_VALIDATION_GATE=BLOCKED`。

#### 3A-R3B-F0.5：免费版实施范围与双轨路线冻结（已完成并合入）

- 性质：纯治理决策，只冻结实施顺序、研究预览边界和门禁，不开发产品页面、Provider Adapter 或数据库结构。
- 规划文档：[F0.5任务书](tasks/3ar3b-f05-free-implementation-scope.md)和[阶段记录](stage-3ar3b-f05-free-implementation-scope.md)。
- 当前状态：最终提交 `08943b4f6af03c75aa4df2a4ecf2494bede4e57b` 已通过 ChatGPT 对实际 Git 提交的验收；用户已批准并完成纯 fast-forward 合入，本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 决策：`FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST`。系统不等待完整免费或付费 Provider，先规划使用已有合法边界内的本地研究快照和 TEST/DEMO 能力展示真实产品形态；该决定不授予 Provider、PIT、Shadow、准确率、盈利或交易资格。
- 双轨路线：
  - 轨道 A：F0.5 → F2A 免费研究预览产品 → 用户产品形态验收；
  - 轨道 B：F0 → 书面许可或替代 Provider 证据 → F1 → F2B → F3。
- 资格隔离：轨道 A 的产品可见性不能代替轨道 B 的许可、事实身份、`DAILY_EXACT`、knowledge-time 或 Provider 资格。
- 后续入口：用户已在 F0.5 合入后另行授权 F2A；F1 仍未开始。

#### 3A-R3B-TRACK-B0：真实数据 Provider 路线决策与 F1 准入合同（已完成并合入）

- 性质：Track B 的唯一入口决策阶段；只调查 BaoStock、Tushare Pro 和同花顺 iFinD 三条路线，只使用官方资料，不开发 Adapter、不调用 Provider。
- 交付：[任务书](tasks/3ar3b-track-b0-provider-route-decision.md)、[阶段记录](stage-3ar3b-track-b0-provider-route-decision.md)、[候选矩阵](track-b-provider-candidate-matrix.md)、[证据登记册](track-b-provider-evidence-register.md)、[成本模型](track-b-provider-cost-model.md)、[许可请求包](track-b-permission-request-pack.md)、[F1 准入合同](track-b-f1-entry-contract.md)和[试用探针合同](track-b-trial-probe-contract.md)。
- 决策：`TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST`，具体候选为 Tushare Pro；`TRACK_B_FALLBACK_ROUTE=IFIND`。BaoStock 保留免费研究辅助与许可候选角色，不作为当前备用路线。
- 当前状态：最终提交 `284588242443af5ce03b468825f861b29ced5ad0` 已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入；本地与远程集成分支一致，ahead/behind 为 `0/0`。
- 评分：按法律与用途 30%、V13/QFQ 25%、PIT 15%、覆盖与稳定性 15%、个人成本 10%、接入复杂度 5% 计算，BaoStock 为 `1.98/5`、Tushare Pro 为 `2.95/5`、iFinD 为 `2.10/5`。Tushare 六项分为 `2.0/3.5/2.0/3.5/4.5/4.0`，加权过程为 `0.600+0.875+0.300+0.525+0.450+0.200=2.950`；排名不变，硬性许可和事实门禁仍优先于总分。
- B0 时点技术结论：Tushare Pro 已公开 raw daily、独立 `adj_factor`、SSE/SZSE `trade_cal`、`dividend` 与跨核心接口使用的 `ts_code`，判为 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`，稳定证券 ID 为 `PARTIAL`；完整公司行动/修订关系、`DAILY_EXACT` 实样、永久 instrument identity 生命周期和 Provider revision/published/历史版本当时仍需后续证据。
- B0 时点准入结论：`F1_ENTRY_READINESS=BLOCKED_MULTIPLE`，由书面许可、技术证据和成本批准三类阻断；B1 已对该组成进行后续复核。
- iFinD 定位：官方资料显示其 SDK/HTTP、数据覆盖和额度能力适合作为专业备用，但个人资格、授权、价格、V13 字段与 revision 语义必须由官方报价、书面许可和未来受控试用验证；Track A 通过不表示现在应启动 15 天试用。
- 当前边界：B0 合入不自动授权 F1。四项正式门禁不变。

#### 3A-R3B-TRACK-B1：Tushare 2000 积分受控权限探针与 F1 准入复核（已完成并合入）

- 性质：只记录 `2026-07-30` 已完成的固定范围真实探针，并复核 F1；精确执行时刻固定为 `PROBE_EXECUTION_TIME=UNKNOWN`，不再次调用 Provider，不开发 Adapter，不访问数据库。
- 交付：[B1任务书](tasks/3ar3b-track-b1-tushare-probe-review.md)和[B1阶段记录](stage-3ar3b-track-b1-tushare-probe-review.md)，并同步候选矩阵、证据登记册、成本模型、F1 准入合同和试用探针合同。
- 探针边界：Python `3.11.9`、tushare `1.4.29`、pandas `3.0.5`；固定 `600000.SH/000001.SZ`、`20250102/20250103`，日历范围 `20250101`—`20250105`；精确 10 次业务请求，无重试、全市场、数据库或完整响应留存，临时环境残留为 0。
- 探针结果：两次 `stock_basic`、两次 SSE/SZSE `trade_cal`、两次 `daily`、两次 `adj_factor` 和两次 `dividend` 全部 `PASS`，权限错误和网络错误均为 0；`TUSHARE_2000_PERMISSION_PROBE=PASS`。
- 完整合同前置：本次是用户购买权限后专项授权的最小技术权限检查；执行前未取得 Provider 对最小自动 API 探针及响应留存/删除边界的书面答复。因此 `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`、`TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`、`WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED`、`WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED`。不判定本次调用合法或违法，不需要也不得重新执行 10 次请求。
- 技术升级：2000 积分核心接口权限、raw/factor/calendar/普通身份/dividend 字段和两证券两日 `DAILY_EXACT` 最小样例为 `VERIFIED`。
- 保持部分：`V13_LINEAGE_PARTIAL`、`PIT_PARTIAL` 和稳定证券 ID `PARTIAL` 不变；公司行动完整覆盖/身份/解释关系、revision/snapshot/published/update/旧版本和永久证券身份仍未验证。
- 成本复核：用户已开通 2000 积分，`BLOCKED_COST_APPROVAL` 解除；不记录支付隐私。`PAID_PROVIDER_UPGRADE_DECISION=PENDING` 继续控制后续专业付费升级和 iFinD。
- B1 时点 F1 复核：`F1_ENTRY_READINESS=BLOCKED_MULTIPLE`，当时阻断从“书面许可 + 技术证据 + 成本批准”缩小为“书面许可 + 剩余技术证据”。这是 B1 的历史状态，后续 F1A 书面证据已再次复核。
- 合入状态：提交链 `39ec0411a10e1ea6ada9d34da4a20aee04382c92` → `d223fdf9ff997ca256f2d0f651c99542e817dfee` 已通过 ChatGPT 实际 Git 最终复验，经用户批准纯 fast-forward 合入。本治理阶段新增 Provider 调用为 0，B1 时点 Tushare 累计真实业务请求为 10，iFinD 为 0。

#### 3A-R3B-F1A：Tushare 有限个人用途 Adapter（任务分支已完成，待验收）

- 交付：[F1A 任务书](tasks/3ar3b-f1a-tushare-limited-personal-adapter.md)和[F1A 阶段记录](stage-3ar3b-f1a-tushare-limited-personal-adapter.md)。
- 书面证据：`2026-07-30`，Tushare 官方企业微信精确脱敏文字为“问：这个可以用来当量化数据来源吧；答：可以”。因此只固定 `WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED`；`WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION/WRITTEN_PERSONAL_BACKTEST_PERMISSION/WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION` 均为 `UNVERIFIED`。用户另行固定 `USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED`、`F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER`，且不分发、转售或商业化原始数据。服务到期留存仍为 `UNVERIFIED`，原始数据再分发为 `NOT_GRANTED`。
- 实现范围：只实现 Tushare `daily`、`adj_factor`、SSE/SZSE `trade_cal` 到 Provider 中立 raw/factor/calendar DTO 和既有 V13 捕获服务；不实现公司行动，不声明完整 QFQ lineage。用途固定为 `RESEARCH_ONLY`、`formalEligible=false`、`SYSTEM_KNOWLEDGE_ONLY/SYSTEM_KNOWLEDGE_PIT`。
- 运行边界：生产默认 `TUSHARE_MODE=DISABLED`，联网只允许显式 `MANUAL_BOUNDED` 会话；五 Endpoint 共用 10 次会话预算，第 11 次在 HTTP 前拒绝。官方限额为 200 次/分钟、每 API 100000 次/日，应用单进程安全值为 180 次/分钟、每 API 90000 次/日。所有 Endpoint 和进程内调用入口共享限流器，但不声称跨进程 Token 全局协调；普通限流重试默认最多 2 次，受控验收重试为 0。
- 验证：初始固定两证券的 daily/factor/calendar 联调新增 6 次；复验修复仅使用剩余 4 次验证两证券 stock_basic/dividend，均零重试，F1A 总预算精确为 10。随机隔离 PostgreSQL 16 Schema 从 V1 迁移到 V13，正常业务数据库和 public 未迁移。Tushare 累计真实业务请求为 20，iFinD 为 0。
- 当前准入：`F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`。剩余公司行动完整覆盖/稳定 action ID/factor-action 关系、revision/snapshot/published/update/旧版本、永久证券身份和全历史 `DAILY_EXACT`；继续保持 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`、稳定证券 ID `PARTIAL`。这些缺口不阻止本阶段有限 Adapter 与个人用途隔离联调，但禁止升级为完整 lineage、Provider PIT 或永久身份资格。
- 当前状态：F1A 技术实现与 Codex 本地验证已在 `codex/1.4.0-stage-3ar3b-f1a-tushare-adapter` 完成，待 ChatGPT 基于实际 Git 提交验收，尚未合入；不自动启动 F2B/F3、Shadow、scheduler、Day 002、3A-R3B-1、3B 或交易。

#### 3A-R3B-F1：完整 Provider Adapter 与 V13 闭环（技术证据仍阻断）

- 启动条件：Provider 已书面确认可作为量化数据来源，用户已批准个人自用有限实现；本地长期存储、回测和内部 Agent 三项 Provider 书面许可仍未逐项验证。完整 QFQ 仍必须解决公司行动覆盖与身份、factor/action 关系、全历史 `DAILY_EXACT`、永久证券身份和单 Provider lineage。F1A 有限实现不自动满足完整 F1 条件。
- 历史用途：`RESEARCH_HISTORICAL_UNVERIFIED` 仅用于产品、演示、探索性历史回测、覆盖研究和交叉校验；不声明 Provider PIT、历史无前视、正式商业资格或历史修订版本。
- 前向用途：只有真实首次捕获之后，满足 append-only、`firstObservedAt`、`knownAt`、cutoff 和许可门禁的事实才能成为 `SYSTEM_KNOWLEDGE_PIT`；它不证明首次捕获前 Provider 的发布时间或修订。
- 边界：不得把任一候选升级为 `PROVIDER_PIT_VERIFIED`，不得绕过 V13 用途许可、跨 Provider 拼接 QFQ、自动全市场抓取、开启 scheduler、创建 Day 002 或自动交易。
- 当前阻断：`F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`。Tushare Pro 的量化数据来源用途书面证据、最低成本、用户个人实现授权和最小技术样例允许 F1A 继续，但本地长期存储、回测和 Agent 三项 Provider 书面许可仍未逐项验证；同时仍缺完整公司行动/稳定事件身份/factor 解释关系、修订和历史版本语义、永久证券身份边界及全历史 `DAILY_EXACT`。BaoStock 继续为 `PENDING_WRITTEN_PERMISSION`，iFinD 仍需报价、授权和未来试用证据。F1A 的 `PROVIDER_CAPTURE/RESEARCH_ONLY/formalEligible=false` 不得解释为 `PROVIDER_PIT_VERIFIED` 或完整 `FORMAL` 资格。

#### 3A-R3B-F2A：免费研究预览产品（技术与用户产品形态验收均完成）

- 当前状态：最终提交 `f5137e2422a48e70d5c706cb146fb034a2b96f65` 已通过 ChatGPT 对实际 Git 提交的验收，经用户批准纯 fast-forward 合入。用户于 `2026-07-29` 完成首次视觉验收；双模式隔离、候选池、六智能体、总控、证据、历史对比和报告均可见，但信息密度与视觉层级未达标，因此该轮历史结论为 `BLOCKED`。后续 R1/R1A/R1B 已完成收敛，用户最终明确认可产品形态。任务证据见 [F2A任务书](tasks/3ar3b-f2a-research-preview-product.md)和[阶段记录](stage-3ar3b-f2a-research-preview-product.md)。
- 启动条件：F0 已验收合入，F0.5 通过实际 Git 验收并由用户批准合入，且用户已另行授权 F2A；不要求 F1、BaoStock 书面许可或正常业务库 V13。
- 输入：只读既有本地研究数据，明确标记 `EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED`；以及 3A-R3B-0 已验收的显式 `TEST_DEMO_EXPLICIT` 固定 Mock。两类结果不得混写。既有 Agent、总控和历史结果只按原规则读取和展示。
- 目标：展示股票候选池、单股分析、固定六智能体、总控、DATA_QUALITY、技术、市场环境、回测、公告/持仓风险、evidence、lineage、reasonCode、来源资格、历史查询、结果对比和研究用途报告。
- 实现边界：新增独立 `/research-preview` 前端路由与“研究预览”菜单；只复用既有扫描和 Agent GET API，不新增 Java 接口，不创建 Agent/Shadow，不访问 Provider 或数据库。固定 Demo 使用非真实证券代码、合成标签和精确六 run；本地 API 不可用时只显示结构化错误并提供显式 Demo 切换，不允许静默替代。
- 不可用语义：真实可靠回测不可用时显示结构化原因；可以用显式 TEST/DEMO 展示页面结构，但不得改用不合格数据计算或把 Mock 收益写成真实历史收益。
- 禁止范围：不新增 BaoStock/AKShare/Tencent/Sina/Eastmoney/CNINFO 或其他 Provider 调用，不写 V13、不迁移正常业务库、不创建 Day 002、不运行正式 Shadow、不开启 scheduler、不宣称准确率、推荐有效或盈利，也不自动交易。
- 验收门：用户已实际查看最终产品并明确认可，当前 `FREE_PRODUCT_PREVIEW_GATE=PASS`。该 PASS 只表示用户认可信息架构、产品形态和日常只读研究流程，不表示 Provider、PIT/QFQ、策略效果、Shadow、iFinD、投资建议或交易获批。
- 阶段边界：F2A 不计入 `FREE_PROVIDER_VALIDATION_GATE`，也不能作为 F3 准确率或效果评估样本。

#### 3A-R3B-F2A-R1：研究预览视觉与交互收敛（已完成并合入）

- 当前状态：最终提交 `e2b9457e3594676875167a703ae09ebc75aaaaf6` 已通过 ChatGPT 对实际 Git 提交的技术复验，经用户批准纯 fast-forward 合入。用户于 `2026-07-29` 完成第二次视觉复验，五分区、信息层级、精简卡片、折叠详情、结构化报告、字号和深色主题方向通过查看；首屏重叠、数据语义歧义、INFO 风险颜色及 DEMO02 尚待完整复验，因此该轮历史结论仍为 `BLOCKED`。任务证据见 [R1任务书](tasks/3ar3b-f2a-r1-preview-ux-convergence.md)和[阶段记录](stage-3ar3b-f2a-r1-preview-ux-convergence.md)。
- 展示结构：固定为“研究总览、六智能体、证据与审计、历史对比、综合报告”五个主分区，默认进入研究总览；首屏显示当前标的、总控结论、确定性研究动作、数据质量门禁、研究证据完整性、正式 veto 和主要结构化原因。
- 收敛原则：默认隐藏 runId、contextHash、完整 finding、evidence fields 和原始报告等技术细节，通过折叠区域保持完整可追溯；候选池、智能体图表、证据、对比和报告均不得重算或改写权威结果。
- 安全边界：继续 GET-only，保持本地研究快照与显式 Demo 隔离，不新增 Java/Python/SQL/Flyway、Provider、数据库、Agent、Shadow 或交易写路径；六 run 顺序和 POSITION_RISK 唯一正式 veto 不变。
- 历史门禁：R1 技术完成、Git 验收或 merge 当时均未打开产品门；这一边界已被遵守，后续通过 R1A/R1B 修复并取得用户最终明确认可。

#### 3A-R3B-F2A-R1A：首屏布局与风险语义修正（已完成并合入）

- 当前状态：提交 `99b22a5e3bd2ad945c2f2b10ae79618277f8ed01` 与 DATA_QUALITY 优先级修复 `11657c572d9561ae3b4a37be7a22f7456444844f` 均已通过 ChatGPT 实际 Git 技术复验，经用户批准纯 fast-forward 合入。用户复验确认数据语义和风险颜色通过，但双列 header 仍存在重叠，因此该轮历史结论仍为 `BLOCKED`。任务证据见 [R1A任务书](tasks/3ar3b-f2a-r1a-visual-semantics-fix.md)和[阶段记录](stage-3ar3b-f2a-r1a-visual-semantics-fix.md)。
- 布局（历史实现）：R1A 曾使用两列 Grid、自然高度、长文本换行和响应式单列降级；R1B 随后以单列正常文档流替代该布局。
- 语义：废止含混“数据可靠性”，独立展示 DATA_QUALITY 权威门禁和研究证据完整性；DEMO01 固定为“门禁通过、证据不足”。
- 风险 tone：INFO 为信息色，WARN 为警告色，HIGH/CRITICAL 为严重红色，FORMAL_VETO 为独立正式否决红色，未知为 neutral；DEMO02 正式 veto 事实与入口保持不变。
- 历史门禁：R1A 技术完成、Git 验收和 merge 当时均未自动打开产品门；这一边界已被遵守。

#### 3A-R3B-F2A-R1B：研究总览垂直流布局修复（已完成并合入）

- 当前状态：最终提交 `4917bbabc8262106abb47e6cb90cf7ab96e76d7d` 已通过 ChatGPT 对实际 Git 提交的技术复验，经用户批准纯 fast-forward 合入；本地和远程集成分支均位于该提交，ahead/behind 为 `0/0`。
- 修复：研究总览头部放弃双列布局，按标签、证券、日期/扫描任务、资格信息的单列正常文档流自然排列；总控、原因和 Agent 状态区域依次位于头部之后，不使用叠层或裁切补丁。
- 最终视觉结果：DEMO01 的标的、日期、资格与总控不再重叠，并清楚显示“数据质量门禁：通过、研究证据完整性：不足”；DEMO02 清楚显示 `REJECTED_BY_VETO`、POSITION_RISK 正式 veto 与 `DEMO_POSITION_LIMIT` 入口。五个主 Tab、历史高亮、默认折叠和无投资建议边界均通过用户查看。

#### 3A-R3B-F2A-GATE：免费研究预览产品门（已完成并合入）

- 用户批准：`2026-07-29 16:44 +08:00`，用户明确回复：“认可当前产品形态，批准创建独立治理提交，将FREE_PRODUCT_PREVIEW_GATE改为PASS。”
- 当前状态：最终提交 `8b6a6bf39a40e44062a3f7aeb315e17e9b62e199` 已通过 ChatGPT 对实际 Git 提交的复验，经用户批准纯 fast-forward 合入；本地和远程集成分支一致，ahead/behind 为 `0/0`。`FREE_PRODUCT_PREVIEW_GATE=PASS`。
- 任务证据：[产品门任务书](tasks/3ar3b-f2a-product-preview-gate-pass.md)和[产品门阶段记录](stage-3ar3b-f2a-product-preview-gate-pass.md)。
- 精确定义：PASS 只表示用户已查看并认可免费研究预览的信息架构、产品形态、日常只读研究流程，以及六智能体、总控、证据、历史和报告的展示方式；Track A 正式完成。
- 不包含范围：不证明 Provider、PIT/QFQ、策略效果、回测收益或 Shadow，不授权投资建议、真实账户、自动交易、iFinD、F1、F2B 或 F3。

#### 3A-R3B-F2B：选定 Provider 支持的真实产品闭环（未开始）

- 启动条件：F1 已完成并通过验收，选定 Provider 路线具有明确用途边界，且合法 `SYSTEM_KNOWLEDGE_PIT` 已能前向积累。
- 目标：以真实 Provider-backed 事实驱动合格回测和六智能体，同时执行真实来源资格标签与用途门禁，为 F3 提供输入。
- 边界：F2A 完成不等于 F2B 或原完整 F2 完成；F2B 也不自动批准 F3、Day 002、scheduler 或 iFinD。

#### 3A-R3B-F3：Provider-backed Shadow 与效果评估（未开始）

- 输入：通过 F1/F2B 形成的选定 Provider 数据和合法 `SYSTEM_KNOWLEDGE_PIT`；F2A 研究预览不得作为正式 F3 输入。
- 最低门槛：不少于 20 个有效观察日、200 个 Shadow item，主要 reasonCode 正式人工复核，持续业务表只读证明和正式观察报告；观察时间由实际开发和市场日历决定，不绑定固定日期。
- 指标冻结：Shadow 开始前必须冻结 `FREE_VALIDATION_METRICS_V1`，覆盖 5/10/20 日命中、相对基准命中和平均/中位超额、MFE/MAE、最大回撤、盈亏比、换手、交易成本、市场环境、confidence 区分度、阻断率、各 Agent 边际贡献、重放/Hash、随机选择和固定基准。
- 反选择偏差：冻结后不得移动阈值、周期、基准或样本选择；不得只以推荐上涨比例作为准确率、只报告盈利样本，或删除失败、阻断和无信号样本。

#### 免费验证与付费升级门禁

`FREE_PRODUCT_PREVIEW_GATE` 只允许 `PASS/BLOCKED`，当前为
`FREE_PRODUCT_PREVIEW_GATE=PASS`。用户已基于最终产品基线
`4917bbabc8262106abb47e6cb90cf7ab96e76d7d` 查看候选池、单股完整流程、六智能体、总控、
evidence、lineage、reasonCode、数据资格、历史和报告，并明确认可产品形态和日常只读
研究流程。该 PASS 不表示 Provider、PIT/QFQ、策略效果、回测收益、Shadow、iFinD、
投资建议、生产交易或自动交易获批。

`FREE_PROVIDER_VALIDATION_GATE` 只允许 `PASS/BLOCKED`，当前为
`FREE_PROVIDER_VALIDATION_GATE=BLOCKED`。PASS 只表示至少一套免费路线能在明确用途边界内
稳定驱动产品闭环和 `SYSTEM_KNOWLEDGE_PIT` 前向 Shadow，不表示商业正式许可、
`PROVIDER_PIT_VERIFIED`、盈利证明、iFinD 可开启或 Day 002 自动批准。F2A 及其
`FREE_PRODUCT_PREVIEW_GATE` 不计入该门禁。

`PAID_PROVIDER_UPGRADE_DECISION` 只允许 `PENDING/DEFER/PROCEED`，当前为
`PAID_PROVIDER_UPGRADE_DECISION=PENDING`。只有产品形态获用户认可、免费数据稳定驱动闭环、
Shadow 显示不只是大盘或随机波动的初步可重复价值、数据成为量化主要瓶颈、付费改善指标
和同股票/日期/策略/参数 A/B 已设计、无重大重构且用户愿意承担成本时才能 `PROCEED`。
产品、规则或免费效果尚未证明，免费数据已足够，预期增量不能覆盖成本或用户暂不投入时可
`DEFER`；DEFER 不等于项目失败。

#### 3A-R3B-1：iFinD 试用启动门（未开始，非直接下一阶段）

这是只读验收阶段，不开发功能、不调用 iFinD。进入本阶段前必须先满足：

1. F0、F0.5、F1、F2A、F2B 与 F3 已完成各自相应验收；
2. `PAID_PROVIDER_UPGRADE_DECISION` 已经另行判定为 `PROCEED`；
3. 用户能够安排连续 15 天集中联调；
4. 用户亲自批准申请和激活。

在此前提下，原有 12 项准备条件仍须全部满足才能 PASS：

1. Provider 中立接口和 DTO 冻结；
2. 四类 PIT 事实实现及随机隔离 PostgreSQL 测试通过；
3. append-only、幂等、A→B→A 和 cutoff 测试通过；
4. `DAILY_EXACT` QFQ 的 18 个黄金场景通过；
5. 2F V2 使用 Mock Provider 完整运行；
6. 六智能体使用 Mock Provider 完整运行；
7. EXPLICIT Shadow 的 Mock 闭环通过；
8. iFinD Adapter 骨架及限流、超时、认证、错误和空数据处理准备完毕但保持禁用；
9. iFinD 函数、字段、证券、日期范围和调用预算清单完成；
10. 响应证据采集、凭据剥离、脱敏、Hash 和离线夹具工具完成；
11. 没有待解决的重大数据库模型、迁移顺序、公共 DTO 或跨语言契约重构；
12. 用户能够安排连续 15 天集中联调，并决定试用申请和激活时点。

正式状态只能是 iFinD 试用启动门的 `PASS` 或
`IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`。任一条件缺失时必须为 BLOCKED，不得以预计日期
代替门禁。ChatGPT 在每个相关阶段验收时同步检查；只有全部满足后才建议用户亲自开通，
Codex 不得申请、激活或自动调用 iFinD。

#### 3A-R3B-2：15 天 iFinD 集中接入与取证（未开始）

- 启动条件：ChatGPT 基于实际证据确认 iFinD 试用启动门为 `PASS`，且用户亲自申请并开启试用。
- 目标：核验真实函数、指标、权限、额度、raw daily、factor、calendar、corporate action、单位、时间、空值、错误码、revision/snapshot/update/publish time；完成真实 Adapter 字段映射，验证 PIT 入库、QFQ、2F V2 与 EXPLICIT Shadow，在许可范围内保存脱敏夹具并形成 15 天证据报告。
- 边界：禁止 scheduler、全市场遍历、无界自动重试消耗额度和自动交易；凭据只通过安全环境注入，任何调用都必须进入批准预算。

#### 3A-R3B-3：Provider 资格判定（未开始）

- 根据 15 天真实证据、书面许可和合同附件，唯一判定为 `PROVIDER_PIT_VERIFIED`、`PROVIDER_REVISION_UNVERIFIED`、`PROVIDER_REVISION_UNAVAILABLE`、`SYSTEM_KNOWLEDGE_PIT`，或许可不足/不批准接入。
- 资格结论必须通过 ChatGPT 对实际 Git 提交和运行证据的验收并经用户批准，之后才能决定是否恢复 Day 002。试用完成本身不自动批准 Provider、完整 3A 或 3B。

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
