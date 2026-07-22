# 智能体团队路线图

具体当前状态以 [CURRENT_STATE.md](CURRENT_STATE.md) 为准；本文件只定义阶段顺序、依赖和验收门槛。

每个阶段都遵守 Java 权威、Python 无状态、真实证据、无自动交易的冻结边界。未达到验收条件不得宣称完成。

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
- 下一阶段唯一入口：阶段 2D-2B-1B-1——TEST/DEMO event materialization foundation；不得跳过 TEST/DEMO 物化基础直接接入 FORMAL、实现 history 或生成 Universe。

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
- 完成门槛：完成阶段 2D-2 实现和独立验收前，完整阶段 2D 不得标记完成。

#### 2D-2A：历史事实版本与交易日历基础模型（已完成）

- 输出：dataset 版本、不可变证券状态事件、双时间证券状态历史、SSE/SZSE 版本化交易日历和 Java as-of 查询。
- 冻结契约：`SECURITY_STATUS_EVENT_V1`、数据库不可变保护、上一/下一开市日动态推导。
- 验收结果：真实 PostgreSQL 及并发测试 `2/0/0/0`；开发、独立审查、修复和修复后复审全部完成。
- 能力边界：尚未生成历史 universe 快照，尚未治理 PIT 行情与公司行动，未实现 `marketBreadth V2`。

#### 2D-2B：证券状态/日历摄取与版本化每日 universe 快照（进行中）

- 阶段位置：2D-2B-1A 已完成；完整 2D-2B 仍进行中。
- 目标：在来源、身份、时间、assurance 和 lineage 可审计的前提下，逐步形成可追溯、版本化、可重复查询的每日 universe 快照。
- 禁止范围：PIT 行情与公司行动实现、`marketBreadth V2`、MARKET_REGIME 规则升级、投资建议和交易写操作。
- 完成门槛：1B 事件摄取、2D-2B-2 双时间投影和 2D-2B-3 Universe 均完成独立验收前，不得标记完整 2D-2B 完成。

##### 2D-2B-1A：source-neutral ingestion foundation（已完成）

- 输出：V7 通用 ingestion run、security/calendar immutable raw、run-record 关联、terminal attempt、retry、namespace、assurance、封存与 `INGESTION_MANIFEST_V1`。
- 合入：集成提交 `505d18ca2e06c039163eada8f2f09f95cee97f30`。
- 验收：单元、真实 PostgreSQL 随机 Schema、两个 backend 并发、不可变、幂等、冲突、封存和 public 基线保护均通过。
- 能力边界：没有 event 物化、history/calendar projection 或 Universe；FORMAL 继续关闭。

##### 2D-2B-1B-0：security event contract freeze（已完成）

- 目标：冻结 TEST/DEMO security raw 到 `SECURITY_STATUS_EVENT_V1` 的显式稳定身份、active 语义、物化基数、normalization result、event lineage、Manifest V2、并发与原子失败契约。
- 输出：[stage-2d2b1b-security-event-materialization-design.md](stage-2d2b1b-security-event-materialization-design.md) 及跨文档一致性决策。
- 禁止范围：不创建迁移，不修改生产代码或测试，不接来源，不写 event/history，不生成 Universe。
- 验收结果：契约已冻结并通过独立 GitHub 审查；首个契约提交为 `c97d6a2c954f536eedd42796b1112aeaab421417`。
- 能力边界：完成设计冻结仍不代表 event 物化实现开始或具备 PIT。

##### 2D-2B-1B-1：TEST/DEMO event materialization foundation（未开始）

- 目标：在 1B-0 冻结契约下实现显式 identity mapping、normalization result、V1 event 物化、唯一 lineage 与 `INGESTION_MANIFEST_V2_SECURITY_EVENT`。
- 输入依赖：1B-0 独立审查并合入。
- 阶段位置：下一阶段唯一入口，尚未开始。
- 禁止范围：FORMAL、真实来源、V2 correction、history 写入、Universe 和扫描切换。
- 验收条件：单元、migration、真实 PostgreSQL、两个 backend 并发、direct SQL 门禁与 Java/SQL 黄金 Hash 全部通过。
- 能力边界：完成后仍无正式来源、PIT、history projection 或 Universe。

##### 2D-2B-1B-2：approved source adapter（外部决策阻断）

- 目标：仅为经批准的证券状态来源实现 adapter，并冻结来源 instrument ID、revision、published/effective 时间、许可与持久化边界。
- 输入依赖：来源和许可书面批准、稳定 instrument ID 可验证、1B-1 完成。
- 阻断条件：来源、许可、本地持久化/历史回放权利或时间语义任一未验证即不得开始。
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
- 能力边界：完成后仍无不可变每日 Universe、PIT 行情或 `marketBreadth V2`。

##### 2D-2B-3：Universe snapshot（未开始）

- 目标：实现不可变 snapshot manifest、逐行 inputs、members、三类 Hash、原子发布、回放和扫描影子验证。
- 输入依赖：2D-2B-2 完成，knowledgeCutoff 与 SSE/SZSE 组合日历规则冻结。
- 禁止范围：不切换生产扫描，不修改 `MARKET_BREADTH_V1`，不开始 2D-2C。
- 验收条件：无前视成员资格、输入 lineage、并发唯一发布、修订不覆盖、真实 PostgreSQL 和影子差异均可审计。
- 能力边界：完成后仍无 PIT 行情、公司行动、`marketBreadth V2` 或完整 MARKET_REGIME。

## 2E：TECHNICAL_ANALYSIS 真实规则（未开始）

- 目标：解释已有技术指标，不在 Python 内伪造行情。
- 输入：technicalMetrics、marketData。
- 输出：有证据的技术 findings 与规则评分。
- 依赖：2A、2B。
- 禁止范围：前视数据、隐式指标重算、LLM 评分。
- 验收条件：指标来源可追溯、边界测试完整、结果确定性。

## 2F：STRATEGY_BACKTEST 解释与稳定性评估（未开始）

- 目标：解释现有回测结果及其稳定性，不创建交易策略捷径。
- 输入：现有 Java 回测上下文及证据。
- 输出：适用性、样本、风险和稳定性 findings。
- 依赖：先独立完成可靠的 `backtestContext` 接入；阶段 2C 未满足该条件。
- 禁止范围：实盘承诺、自动参数寻优后直接交易、虚构回测。
- 验收条件：无前视、版本可追溯、空样本安全降级。

## 2G：公告上下文和 ANNOUNCEMENT_RISK（未开始）

- 目标：接入可验证公告上下文并建立公告风险规则。
- 输入：经批准的公告数据源与 securityEvents。
- 输出：事件证据、严重度和非正式风险提示。
- 依赖：数据源合规与缓存设计单独验收。
- 禁止范围：新闻编造、非 POSITION_RISK 正式 veto、LLM 事实生成。
- 验收条件：原文引用可追溯、时间准确、重复事件去重。

## 2H：模拟持仓上下文和 POSITION_RISK（未开始）

- 目标：在明确的模拟持仓边界内实现持仓风险和正式否决规则。
- 输入：经批准的 portfolioContext、市场与证据。
- 输出：可审计风险 findings 和契约允许的正式 veto。
- 依赖：模拟持仓数据源、权限和隔离设计。
- 禁止范围：真实账户写入、券商控制、自动下单。
- 验收条件：POSITION_RISK 唯一否决权、逻辑/物理 ID 映射与持久化闭环。

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
