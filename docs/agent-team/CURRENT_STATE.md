# Stock Quant Pro 智能体团队当前状态

> 本文件是智能体团队进度、能力边界和下一阶段入口的单一事实来源。阶段文档、聊天记录或旧注释与本文件冲突时，以本文件为准。

历史阶段提交、测试和验收证据索引见 [PROGRESS_LOG.md](PROGRESS_LOG.md)；该日志不替代本文件。

## 基线

- 当前稳定版本：`1.3.1`
- 当前目标版本：`1.4.0`
- 当前集成分支：`feature/1.4.0-agent-team`
- 1D-4 验收来源分支：`codex/1.4.0-1d4-acceptance`
- 1D-4 验收基线：`5bc492a feat(agent): add safe local team runtime scripts`
- 阶段 2A 验收来源分支：`codex/1.4.0-2a-readonly-context`
- 阶段 2A 实现基线：`1707d3e4991434d7655b50d9af0b532e0b0e7a60`
- 阶段 2B 验收来源分支：`codex/1.4.0-2b-data-quality-gate`
- 阶段 2B 规则版本：`1.4.0-stage-2b-dq-v1`
- 阶段 2C 验收来源分支：`codex/1.4.0-2c-readonly-research-context`
- `marketBreadth` 生产版本：`MARKET_BREADTH_V1`
- 阶段 2D-1 验收来源分支：`codex/1.4.0-2d1-market-breadth-state`
- 阶段 2D-1 实现提交：`b956d02a65b5d5d27179013983ff4b501302fd47`
- 阶段 2D-1 规则版本：`1.4.0-stage-2d-market-regime-v1`
- 阶段 2D-1 已通过任务分支完成实现与验收，并已合并到集成分支；集成提交：`ff461d9f851e7ec37390dda6c15a67987f672dc7`。
- 阶段 2D-2A 来源分支：`codex/1.4.0-2d2a-temporal-market-foundation`
- 阶段 2D-2A 最终任务提交：`3a3eebd2ef580d31a6b02aab1a7204ea02fdba58`
- 阶段 2D-2A 已完成开发、真实 PostgreSQL 闭环、独立审查、审查问题修复和修复后复审，并已合并到集成分支。
- 阶段 2D-2B-1A 来源分支：`codex/1.4.0-2d2b1a-ingestion-foundation`
- 阶段 2D-2B-1A 最终任务提交：`7019ffdd75d364847404afb10edb0ec653c307bf`
- 阶段 2D-2B-1A 已完成 source-neutral ingestion foundation，并已通过合并提交 `505d18ca2e06c039163eada8f2f09f95cee97f30` 合入集成分支。
- 阶段 2D-2B-1B-0 文档任务分支：`codex/1.4.0-2d2b1b0-event-contract-freeze`
- 阶段 2D-2B-1B-0 首个契约提交：`c97d6a2c954f536eedd42796b1112aeaab421417`
- 阶段 2D-2B-1B-0 复审修复提交：`28c312dcbe26103c5f2b45c043ec6a8f81a08ae0`
- 阶段 2D-2B-1B-0 证券状态事件物化契约已冻结并通过独立 GitHub 审查；该阶段本身只有契约文档，后续 2D-2B-1B-1 实现现已完成。
- 阶段 2D-2B-1B-1 首次实现提交：`18151800d07fd7d2e6706b88869df5b7d0aa8ba0`
- 阶段 2D-2B-1B-1 复审修复提交：`b6cb263f863f91753f043e0fa19e85501873111f`
- 阶段 2D-2B-1B-1 已完成 TEST/DEMO security event materialization foundation，通过独立 GitHub 复审并获得 PASS，已通过合并提交 `9aebcbf7d5a315d1edd61d85bf2944a454f72ffe` 合入集成分支。
- 阶段 2E-1 任务分支：`codex/1.4.0-2e-technical-analysis-v1`
- 阶段 2E-1 冻结集成基线：`70b4bacb06dbaf799ec3b01599f07d38e8b96589`
- 阶段 2E-1 规则版本：`1.4.0-stage-2e-technical-analysis-v1`
- 阶段 2E-1 实现提交：`93ccf7c6da380be91ca342f6c5e8815f8e7dfe07`
- 阶段 2E-1 集成合并提交：`adb781c3ffb41ff13a14538067e838a60a65bea9`
- 阶段 2E-1 已完成确定性 TECHNICAL_ANALYSIS V1，实现通过 Codex 本地验证和独立 GitHub 最终复审 PASS（HIGH 0 / MEDIUM 0 / LOW 0），并已合入集成分支。
- `master`：`27d9099 chore: checkpoint Stock Quant Pro 1.3.1 and remove tracked cache`
- 版本号仍保持 `1.3.1`；尚未发布 `1.4.0`。

## 权威文档职责

- 仓库根目录 [AGENTS.md](../../AGENTS.md)：双窗口开发、安全、ChatGPT 实际提交验收和用户 merge 批准规则。
- `CURRENT_STATE.md`：当前进度、真实能力、阻断和下一阶段入口的唯一事实来源。
- [DECISIONS.md](DECISIONS.md)：跨阶段稳定架构与治理决策。
- [ROADMAP.md](ROADMAP.md)：阶段方向、顺序、依赖和验收门槛，不构成连续开发授权。
- [PROGRESS_LOG.md](PROGRESS_LOG.md)：已完成阶段的提交、测试、验收和 merge 批准证据索引，不承担当前状态或完整路线图职责。
- `tasks/`：候选大阶段或内部工作包的范围和验收任务书；任务书存在不表示业务实现已开始。

## 已完成并验收

- 1D-1：六智能体跨语言契约与编排骨架。
- 1D-2：Java 权威任务、事务、异步执行、真实 Python 调用与 PostgreSQL 闭环。
- 1D-3：证据、评分、正式否决、总控一致性、合法持久化与非法响应原子失败。
- 1D-4：Vue 工作台、正式否决查询、本地安全启停和端到端验收。
- 阶段 2A 前置：Agent PostgreSQL 集成测试已完成任务级隔离治理，共享非空专用测试库不再要求五张 Agent 表全局为空。
- 阶段 2A：从现有 PostgreSQL 接入 `security`、`marketData`、`technicalMetrics` 和 `dataQualityContext` 四类只读上下文；完成只读一致性事务、Java 确定性指标、数学数值 Hash 规范化和 JSONB 往返验收。
- 阶段 2B：基于阶段 2A 四类冻结事实完成确定性 DATA_QUALITY 规则门禁、版本化跨语言契约、Java 双重校验和真实 PostgreSQL/Java/Python 持久化闭环验收。
- 阶段 2C：从现有 PostgreSQL 接入 `marketBreadth` 和 `scanResult` 两类只读研究上下文，并将 `backtestContext` 冻结为结构化安全不可用；完成统一时点规则、扫描任务稳定选择、字段白名单、Hash/JSONB 及真实 PostgreSQL 闭环验收。
- 阶段 2D-1：当前证券池市场宽度状态规则已完成实现、自动化回归、真实 PostgreSQL/Python/Java 闭环和独立验收。
- 阶段 2D-2A：历史事实版本与交易日历基础模型已完成开发、真实 PostgreSQL 闭环、独立审查、修复和复审；建立了 dataset 版本、不可变证券状态事件、双时间证券状态历史、SSE/SZSE 版本化交易日历和 Java as-of 查询基础。
- 阶段 2D-2B-1A：来源无关摄取基础已完成并合入；V7 建立通用 ingestion run、security/calendar immutable raw、run-record 关联、terminal processing attempt、retry、namespace、assurance、封存和 `INGESTION_MANIFEST_V1`。
- 阶段 2D-2B-1B-0：已冻结 TEST/DEMO security raw 到 V1 event 的稳定身份、active 语义、attempt/result/event 基数、normalization outcome、event root/lineage、Manifest V2、并发与原子失败契约；本阶段只有文档，没有生产实现。
- 阶段 2D-2B-1B-1：TEST/DEMO security event materialization foundation 已完成、通过独立 GitHub 复审并合入；V8 实现 `manifestContractVersion`、TEST/DEMO 稳定证券身份、显式 source identity mapping、`SECURITY_STATUS_RAW_TEST_V1`、V1 event 物化与复用、normalization result、唯一 event lineage、`INGESTION_MANIFEST_V2_SECURITY_EVENT`、Java/PostgreSQL 双重门禁，以及幂等、并发和原子失败保护。resolved event 在 2D-2B-2 前仍被禁止进入 history。
- 阶段 2E-1：确定性 TECHNICAL_ANALYSIS V1 已完成、通过独立 GitHub 最终复审并合入；只解释冻结的 `technicalMetrics` 与 `marketData`，形成五类固定 finding，通过 Java/Python 双重校验并受 DATA_QUALITY 门禁约束，不产生正式 veto，总控仍保持安全不足状态。

阶段 2D-1、2D-2A、2D-2B-1A、文档阶段 2D-2B-1B-0 和 TEST/DEMO 实现阶段 2D-2B-1B-1 完成不等于完整阶段 2D、完整阶段 2D-2 或完整阶段 2D-2B 完成；这些上位阶段仍处于进行中。已完成内容是基础设施、只读事实上下文、数据质量门禁、受限的当前证券池宽度状态规则、时态事实基础、来源无关摄取基础、事件物化契约及 TEST/DEMO event 物化基础。当前 event 物化能力不包含正式来源、FORMAL/PIT、history/calendar projection、Universe、完整六智能体分析、完整市场环境模型、历史无前视市场宽度、真实股票分析或投资建议能力。

## 权威边界与真实可用能力

Java 是 `taskId`、六个 `runId`、状态、幂等与缓存、持久化和跨语言响应校验的唯一权威。Python 无状态，只处理 Java 传入的只读 `contextSnapshot`，不访问任务数据库。PostgreSQL 已包含 task、run、evidence、veto、decision 五类持久化结构。Vue 可创建、轮询、恢复并展示任务。本地脚本可安全启动、复用和精确停止 Python、Java、Vue。

当前集成分支真实可用的是 Java 权威任务和持久化、阶段 2A 第一批四类上下文、确定性技术指标、确定性 DATA_QUALITY 门禁、`marketBreadth` 只读事实、`scanResult` 历史扫描事实、阶段 2D-1 受限的当前证券池宽度状态规则、阶段 2E-1 确定性 TECHNICAL_ANALYSIS V1、V6 时态事实基础、V7 来源无关摄取基础、V8 TEST/DEMO security event 物化基础、Hash 与 JSONB 稳定往返、缺数安全降级、失败原子性和 Vue 工作台观察能力。V8 已实现 run 创建时冻结的 `manifestContractVersion`、TEST/DEMO 稳定证券身份与显式 source identity mapping、`SECURITY_STATUS_RAW_TEST_V1`、`SECURITY_STATUS_EVENT_V1` 物化与严格复用、每 terminal attempt 唯一 normalization result、每逻辑 event 唯一 lineage、`INGESTION_MANIFEST_V2_SECURITY_EVENT`、Java/PostgreSQL 双重门禁，以及幂等、两个 backend 并发和原子失败保护。FORMAL/PIT 继续由数据库门禁拒绝，resolved event 在 2D-2B-2 前不得进入 `security_status_history`；当前尚未形成正式 history/calendar projection 或 Universe。除 DATA_QUALITY、该受限宽度状态规则和 TECHNICAL_ANALYSIS V1 外，其余三个专业智能体规则仍未实现；当前仍不具备完整六智能体分析、完整 MARKET_REGIME、STRATEGY_BACKTEST 解释、公告风险、持仓风险、投资建议或自动交易能力。

## 九类 contextSnapshot 实际状态

| 上下文 | 当前状态 |
|---|---|
| `security` | 已从现有 PostgreSQL `securities` 只读接入；该表不是历史版本表，不保证请求交易日时点的证券属性 |
| `marketData` | 已从现有 PostgreSQL `daily_bars` 接入截止请求日期的 QFQ 日线，最多读取最近61条 |
| `marketBreadth` | 已由 Java 在同一只读事务内基于当前 MAIN、active、非 ST 证券及 QFQ 日线确定性生成；统一有效日期和前一有效日期；阶段 2D-1 仅将其用于当前日期受限宽度状态。证券池不是历史版本，`pointInTimeGuaranteed=false`、`universePointInTimeGuaranteed=false`、`futureDataExcluded=false`，历史日期不能进行无前视分类 |
| `scanResult` | 已从已完成、正式、FULL 扫描任务中只读接入；按交易日、完成时间和 ID 稳定选择；只输出白名单事实，不输出推荐字段；生产输入截止日期和算法版本不可完全证明 |
| `technicalMetrics` | 已由 Java 基于同一事务冻结的本地 QFQ 日线，使用 `JAVA_INDICATORS_V1` 确定性计算 |
| `backtestContext` | 仍不可用；现有回测记录没有可验证的输入截止日期、knowledge-time、来源/修订版本、策略版本和完整参数，现有 QFQ 历史值也不能证明在历史决策时点已经可知；`reasonCode=BACKTEST_INPUT_CUTOFF_UNVERIFIABLE` |
| `securityEvents` | 对智能体 contextSnapshot 仍不可用；V8 只建立 TEST/DEMO 摄取侧 event 物化基础，尚无正式来源，也未接入该上下文 |
| `portfolioContext` | 不可用；尚未接入现有业务数据源 |
| `dataQualityContext` | 已生成只读数据质量事实；不包含评分、规则门禁、决策或否决，数据库查询正常时即使证券和日线缺失仍可用 |

## 六智能体与总控实际状态

固定专业智能体为 `DATA_QUALITY`、`MARKET_REGIME`、`TECHNICAL_ANALYSIS`、`STRATEGY_BACKTEST`、`ANNOUNCEMENT_RISK`、`POSITION_RISK`。总控不是第七个 run。

阶段 2B 升级 DATA_QUALITY：无效上下文映射为 `INSUFFICIENT_DATA/BLOCKED/REJECT/0/0`，有效阻断为 `COMPLETED/BLOCKED/REJECT/0/100`，有效警告为 `COMPLETED/WARN/WARN/50/100`，有效通过为 `COMPLETED/PASS/PASS/100/100`，且 `veto` 始终为 `false`。阶段 2D-1 的团队规则版本为 `1.4.0-stage-2d-market-regime-v1`，DATA_QUALITY 完整复用阶段 2B 规则语义。

阶段 2D-1 的 MARKET_REGIME 只使用冻结的 `marketBreadth`，`marketData`、`technicalMetrics` 和 `scanResult` 均不参与分类、score、confidence、finding、evidence 或 summary 推断。当前日期由冻结 `requestedAt` 转换到 `Asia/Shanghai` 后的自然日确定；只有 `coverageRatio=1.00000000` 且 `comparableSymbolCount>=2` 等全部资格条件满足时才进行正向、混合或负向宽度状态分类。score 使用确定性宽度公式，仅描述当前证券池上涨和下跌数量平衡；confidence 固定为0。MARKET_REGIME 不得产生正式 veto。

阶段 2E-1 的 TECHNICAL_ANALYSIS 只解释 Java 已冻结的 `technicalMetrics` 与 `marketData`，不拉取行情、不连接业务数据库，也不在 Python 隐式重算 SMA、RSI、ATR 或其他指标。有效输入固定形成趋势、RSI、相对 MA20 偏离、相对波动、指标确认/冲突五类 finding；score 从 50 开始累加确定性影响后截断到 `[0,100]`。DATA_QUALITY 为 PASS 时 TECHNICAL_ANALYSIS gate/confidence 为 `PASS/100`，为 WARN 时为 `WARN/50`；DATA_QUALITY 阻断时不得形成技术 evidence、finding 或正常评分。输入非法时以 `TECHNICAL_ANALYSIS_INPUT_INVALID` 安全降级，不伪造中性结论。完整规则见 [stage-2e1-technical-analysis-v1.md](stage-2e1-technical-analysis-v1.md)。

DATA_QUALITY 为 PASS 或 WARN 且相应输入可执行时，MARKET_REGIME 与 TECHNICAL_ANALYSIS 可为 `COMPLETED`，但总控仍为 `finalDecision=INSUFFICIENT_DATA`、继承 gate、`score=0`、`confidence=0`；BLOCKED 时仍为 `BLOCKED_BY_DATA_QUALITY`。TECHNICAL_ANALYSIS 不产生正式 veto，只有 `POSITION_RISK` 在满足冻结契约时才有正式否决权。其余三个专业智能体仍返回未实现的 `INSUFFICIENT_DATA/NOT_APPLICABLE` 安全结果；总控不是第七个 run。阶段 2E-1 不构成完整六智能体分析、投资建议或交易信号。

## 数据库、前端与本地运行

- 数据库 Schema 当前已升级至 Flyway V8。V6 新增 dataset 版本、不可变证券状态事件、双时间证券状态历史和 SSE/SZSE 版本化交易日历；V7 新增来源无关 ingestion run、security/calendar raw、run-record 关联、terminal attempt、retry、namespace、assurance、封存与 Manifest V1；V8 新增 `manifestContractVersion`、TEST/DEMO 稳定证券身份及显式来源映射、normalization result、event lineage、Manifest V2 和相应数据库不可绕过门禁。V6/V7 均不回填现有 `securities` 或 `daily_bars`，V8 不接入正式来源。
- 阶段 2A 使用 Agent 专用只读 Repository 查询 `securities` 和截止请求日的 QFQ `daily_bars`；四类上下文在 `REPEATABLE_READ` 只读事务中冻结，不执行市场数据同步或数据库写操作。
- 阶段 2C 未修改 Flyway 或外层 JSON Schema，`CONTEXT_SCHEMA_VERSION` 仍为 `1.0`。
- `marketBreadth`、`scanResult` 与阶段 2A 四类上下文在同一个 `REPEATABLE_READ` 只读事务内冻结；Python 仍不直连数据库，`backtestContext` 不运行 `BacktestEngine`。
- 阶段 2D-1 未修改 Flyway、外层 JSON Schema、`CONTEXT_SCHEMA_VERSION`、contextHash算法或数据库写模型。
- 阶段 2E-1 同样未修改 Flyway、V1 至 V8、外层 JSON Schema、`CONTEXT_SCHEMA_VERSION`、contextHash 算法或数据库持久化结构；Java 继续在单事务持久化前完成独立响应校验。
- 阶段 2D-2A 冻结 `SECURITY_STATUS_EVENT_V1`；数据库层禁止 dataset/event 的 `UPDATE`、`DELETE`、`TRUNCATE`，history/calendar 只允许一次 `known_to: NULL -> 非NULL` 关闭。上一/下一开市日不持久化，统一按同 exchange、同 knowledge cutoff 的日历事实动态推导。
- 阶段 2D-2B-1B-1 仅在 TEST/DEMO 边界内把 `SECURITY_STATUS_RAW_TEST_V1` 物化或复用为 V1 event；V8 同时在 Java 和 PostgreSQL 阻止 FORMAL/PIT 提升，并在 2D-2B-2 独立实现前禁止任何 resolved event 写入 `security_status_history`。
- `contextHash` 按 JSON 数值的数学值规范化，对象字段稳定排序、数组保持业务顺序；API、PostgreSQL JSONB 与持久化快照重算结果一致。
- 阶段 2B 质量 evidence 固定来源为 `JAVA_ENGINE/AgentContextSnapshotService/contextSnapshot`，只投影四类冻结上下文；所有 DATA_QUALITY finding 引用该权威 evidence。
- Java 纳秒 `Instant` 与 Python `datetime` 往返时间按微秒传输精度规范化比较，双方截断到微秒后必须完全相等；相差 1 微秒即拒绝，不改写冻结上下文或 Hash。
- 逻辑 evidence ID、逻辑 veto ID 与数据库物理主键的映射规则已冻结。
- 工作台路由为 `/agent-team`，通过 `taskId` query 恢复任务；旧 `/ai` 页面保留。
- 工作台使用真实 Java API，不包含运行时 mock 或前端生成的分析结论。
- `start-agent-team-local.ps1` / `stop-agent-team-local.ps1` 使用可信状态、PID/启动时间、进程树、互斥锁和敏感环境隔离。

## 禁止范围

当前智能体团队阶段禁止新增接入或宣称已具备：实时行情查询、新 AKShare 查询、公告上下文、真实或新增模拟持仓上下文、LLM/付费 API、超出已冻结 DATA_QUALITY、MARKET_REGIME V1 与 TECHNICAL_ANALYSIS V1 的评分策略、交易写操作、自动下单和券商控制。不得编造价格、指标、证据或投资结论。阶段 2D-1 和 2E-1 的 score 均不构成完整市场判断、收益预测、投资建议或交易信号。

## 已知问题与最近测试

- PostgreSQL 16.13 高于当前 Flyway 9.22.3 声明的已测试上限，现阶段为非阻断警告。
- Python TestClient 存在 Starlette/httpx 弃用警告。
- 前端主包超过 Vite 默认 500 kB 提示阈值。
- 阶段 2A Java 验收：`quant-core` 运行1项、0失败；阶段 2A 定向测试运行21项、0失败；带专用 PostgreSQL 变量的完整 Agent 测试运行155项、0失败、1项跳过；独立无数据库变量 Agent 测试运行150项、0失败、9项安全跳过；独立无变量 `quant-server` 全量运行151项、0失败、9项跳过。
- Python `compileall` 通过，权威 `unittest discover` 为33/33通过；仓库未声明 pytest 依赖，本阶段未安装 pytest 或修改 Python 依赖。Vue生产构建通过，`git diff --check` 通过。
- 自动化生产流与真实本地受控任务均确认 API `contextHash`、数据库 `context_hash` 和生产 `AgentContextHashService` 对 JSONB 的重算结果一致。
- 正常用户 PowerShell 已通过 Python、Java、Vue 安全启动、空数据任务、受控数据任务和精确停止闭环。验收任务及阶段 2A 证券、日线夹具均已精确清理，最终 Agent 五表计数为 `2/12/0/0/2`；`state.json`、三个监听端口和六个记录 PID 均无残留。
- Codex 受控环境因无权读取 `Win32_Process` 而被脚本安全拒绝证明进程归属；正常用户 PowerShell 的 CIM 权限下闭环通过。本阶段未修改运行脚本。
- 阶段 2B Java 验收：阶段 2B 定向测试 `13/0/0/0`，响应校验相关定向测试 `45/0/0/0`，无集成变量完整 Agent 回归 `164/0/0/9`，`quant-server` 全量 `165/0/0/9`。
- 阶段 2B 真实闭环：专用 `stock_quant_test` 数据库、真实 Python 回环服务和 Java 持久化测试为 `1/0/0/0`、`BUILD SUCCESS`；DATA_QUALITY 为 `COMPLETED/WARN/WARN/50/100`，总控为 `INSUFFICIENT_DATA/WARN/0/0`，六 run、唯一 evidence、空正式 veto 和 Hash 一致性均通过。
- 阶段 2B 测试数据已精确清理；测试前后 Agent 五表基线为 `2/12/0/0/2`，证券与日线均为 `0/0`，Python 已停止且 8001 已释放。
- 阶段 2C 真实 PostgreSQL 验收：`AgentStage2CReadonlyContextPostgresIntegrationTest` 为 `1/0/0/0`、`BUILD SUCCESS`；上下文与 JSONB 完整语义往返、生产 Hash 重算、无业务副作用、测试数据精确清理及相关表恢复测试前基线均通过。
- 阶段 2C 最终回归：Java 定向 `38/0/0/0`，完整 Agent `176/0/0/10`，`quant-server` `177/0/0/10`，`quant-core` `1/0/0/0`；Python `compileall` 通过、unittest 全量 `50/0/0`、阶段 2C 定向 `1/0/0`；`git diff --check` 通过。
- 阶段 2D-1 普通回归：Python `compileall` 通过，阶段 2D 定向与契约33项通过，Python全量68项通过；Java阶段 2D Validator 8项通过，跨语言与一致性21项通过，阶段 2B 兼容13项通过；完整 Agent `189/0/0/11`，`quant-server` `190/0/0/11`，`quant-core` `1/0/0/0`，`git diff --check` 通过。
- 阶段 2D-1 真实成功路径：`AgentStage2DPostgresPythonIntegrationTest` 为 `1/0/0/0`、`BUILD SUCCESS`；专用 `stock_quant_test` 数据库和真实Python服务完成上下文冻结、六run持久化、DQ后MARKET_BREADTH证据顺序、安全总控、JSONB完整语义往返及生产Hash重算验证。
- 阶段 2D-1 非法响应原子失败：`AgentInvalidResponsePostgresIntegrationTest` 为 `7/0/0/0`、`BUILD SUCCESS`；测试预期的响应校验和JSON解析异常均被安全拒绝，evidence/veto/decision无部分持久化，FAILED任务重复执行不再次调用HTTP。
- 阶段 2D-1 测试任务、证券和日线均已精确清理；Agent五表、`securities` 和 `daily_bars` 恢复测试前基线。JSONB/Hash、原子失败、精确清理和基线恢复均已通过独立验收。
- 阶段 2D-2A 真实 PostgreSQL 及并发测试为 `2/0/0/0`、`BUILD SUCCESS`；随机临时 Schema 内完成 V1 至 V6 迁移、数据库不可变保护、事件到history事实链、as-of日历、并发幂等与并发更正验证。两个backend PID不同，并发更正只产生一个新逻辑版本，旧 `known_to` 正确关闭，仅一个开放knowledge版本且不存在valid/knowledge重叠；临时Schema最终删除，public基线不变。
- 阶段 2D-2B-1A 已完成并合入。其真实 PostgreSQL 测试在随机临时 Schema 内从 V1 顺序迁移至 V7，覆盖 raw/attempt 不可变、namespace、assurance、retry、封存、Manifest V1、两个 backend 并发幂等与冲突；测试结束删除临时 Schema且 public 基线未变化。
- 阶段 2D-2B-1B-1 的测试结果均为 Codex 本地执行证据，不是 GitHub Actions CI：V8 真实 PostgreSQL 为 `6/0/0/0`、`Skipped=0`；2D-2A 兼容 PostgreSQL 为 `2/0/0/0`；2D-2B-1A 兼容 PostgreSQL 为 `2/0/0/0`；`quant-server` 为 `255/0/0/21`；`quant-core` 为 `1/0/0/0`；Python unittest 68 项通过；Python `compileall` 通过；`git diff --check` 通过。
- `quant-server` 的 21 项跳过属于非数据库全量回归中的环境门禁跳过，不能冒充真实 PostgreSQL 测试；V8 真实 PostgreSQL 测试单独以 `Skipped=0` 完成。
- 本地专用测试库 public Schema 存在 V6 checksum 与当前仓库不一致的历史环境问题。2D-2B-1B-1 未执行 Flyway repair 或 clean，未修改、删除或重建 public，并通过随机 Schema 隔离完成真实 PostgreSQL 验收；该问题属于独立环境治理事项，不能被描述为 V8 功能失败，也不能被静默修复或掩盖。
- 正式证券状态来源、数据许可、本地持久化权利、历史回放权利、稳定 source instrument ID、revision 语义以及 published/effective 时间语义尚未批准，因此 FORMAL/PIT 摄取继续阻断。
- 阶段 2E-1 的测试结果均为 Codex 本地执行证据，不是 GitHub Actions CI：Python `compileall` 通过、unittest `77/0/0`；真实 Java/Python 跨语言闭环 `4/0/0/0`、`Skipped=0`；专用 `stock_quant_test` 随机临时 Schema 的真实 PostgreSQL 闭环 `2/0/0/0`、`Skipped=0`；`quant-server` 全量 `261/0/0/27`；`quant-core` 全量 `1/0/0/0`。`quant-server` 的 27 项跳过是未提供外部集成环境变量时的门禁跳过，不能冒充真实 PostgreSQL 或真实 Python 闭环；两类真实闭环已分别单独以 `Skipped=0` 执行。
- 阶段 2E-1 的真实 PostgreSQL 测试从 V1 至 V8 迁移随机临时 Schema，覆盖六个 run、证据顺序、空正式 veto、Hash、非法响应原子失败与精确清理；临时 Schema 最终删除，public 数据计数、关系/约束/触发器/函数指纹、Flyway 历史和扩展前后不变。未对存在历史 V6 checksum 问题的 public 执行 repair、clean、删除或重建。

## 当前后续入口与阻断

**在阶段 2D-2B 数据来源工作线上，2D-2B-1B-2 approved source adapter 的前置来源与许可决策仍被阻断，正式 adapter 实现尚不能开始。**

来源询价、许可审查、样例验收与批准记录框架见 [stage-2d2b1b2-source-decision-package.md](stage-2d2b1b2-source-decision-package.md)。该决策包只准备证据清单和准入门槛，不代表任何来源已批准、FORMAL/PIT 已开放或 adapter 已开始。

完整阶段 2D、完整阶段 2D-2 和完整阶段 2D-2B 仍处于进行中。阶段 2D-2A、2D-2B-1A、文档阶段 2D-2B-1B-0 与 TEST/DEMO 实现阶段 2D-2B-1B-1 已完成；该工作线的唯一入口只是解决 2D-2B-1B-2 的外部前置决策，不是立即开始 adapter、2D-2B-2 或 Universe 实现。阶段 2E-1 已完成独立复审并合入，但没有自动批准或开始任何 2E 后续任务。

**在智能体规则能力工作线上，下一候选大阶段统一为“2F 可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1”，当前状态为 `NOT_STARTED`。** [2F-0 可靠 backtestContext 审计与接入基础](tasks/2f0-backtest-context-foundation.md) 只是该大阶段的内部技术前置工作包，不单独开发、提交或验收；整个 2F 仍未获得业务实现授权。未来开发必须先解决 knowledge-time/PIT、canonical Hash、架构、迁移和旧版本兼容门禁，不能由本次治理文档任务预判或实现。

阻断项包括正式证券状态来源、数据许可、本地持久化权利、历史回放权利、稳定 source instrument ID、revision 语义以及 published/effective 时间语义。当前免费聚合源和 `securities` 当前态投影均不得被视为正式来源。当前仍未实现正式 source adapter、FORMAL 摄取、PIT_VERIFIED、`SECURITY_STATUS_EVENT_V2`、`security_status_history` 正式投影、trading calendar projection、Universe snapshot、PIT 行情和公司行动、`MARKET_BREADTH_V2`、完整 MARKET_REGIME 或生产扫描切换；无前视历史回放和评测集也尚未建立。阶段 2E-1 之外的 TECHNICAL_ANALYSIS 扩展及后续阶段均未开始；阶段 2F、2G、2H、2I 均未开始。阶段 2D-2B 禁止外部行情补数、LLM 权威决策、投资建议和交易写操作。
