# 阶段 2D-2B-1B-2：正式证券状态来源批准决策包

## 1. 文档状态、目标与范围

状态：**SOURCE APPROVAL BLOCKED / DECISION PACKAGE ONLY**。

本文只为阶段 2D-2B-1B-2 准备正式证券状态来源的询价、许可审查、样例验收和批准记录框架。本文不是来源批准书，不是合同，不是法律意见，不实现 source adapter，不接入任何外部数据，也不解除 FORMAL 或 PIT_VERIFIED 门禁。

- 冻结集成基线：`c2293cff03c142f8a14ffbfcbc8c808004cd3c5a`；
- 文档任务分支：`codex/1.4.0-2d2b1b2-source-decision-package`；
- 当前真实能力与唯一入口以 [CURRENT_STATE.md](CURRENT_STATE.md) 为准；
- 阶段顺序与准入条件以 [ROADMAP.md](ROADMAP.md) 为准；
- 跨阶段稳定边界以 [DECISIONS.md](DECISIONS.md) 为准；
- TEST/DEMO event 物化契约以 [stage-2d2b1b-security-event-materialization-design.md](stage-2d2b1b-security-event-materialization-design.md) 为准；
- 后续 history、calendar 与 Universe 总体边界以 [stage-2d2b-universe-snapshot-design.md](stage-2d2b-universe-snapshot-design.md) 为参考设计。

### 1.1 本决策覆盖的证券状态事件来源

- 上市；
- 退市；
- ST 状态与摘帽；
- 板块变化；
- `active` / `listed` 状态；
- 交易所变化；
- 证券简称变化的身份治理边界。

证券简称变化不自动等于证券身份变化，也不是 `SECURITY_STATUS_EVENT_V1` 的状态字段。候选来源必须提供稳定 `sourceInstrumentId` 或等价稳定主键，并证明简称或 symbol 变化不会触发自动合并、拆分或身份猜测。

### 1.2 本决策不覆盖

- 实时行情；
- 分钟行情；
- 财务数据；
- 公告分析；
- 公司行动；
- Universe；
- 交易执行。

本任务也不实现 `SECURITY_STATUS_EVENT_V2`、history/calendar projection、PIT 行情、`MARKET_BREADTH_V2`、完整 MARKET_REGIME 或生产扫描切换。

## 2. 证据规则与候选结论基线

以下均是**待合同、报价和样例数据进一步验证的候选结论**，不是最终批准：

1. 上交所、深交所正式数据服务或其授权供应商，是 FORMAL 来源的优先候选类别。
2. Tushare 当前只能作为个人研究或 TEST/DEMO 候选。未取得单独书面授权并证明完整时间和 revision 语义前，不得批准为 FORMAL/PIT。
3. 免费聚合接口、当前 `securities` 表投影和网页抓取，不得被默认为正式证券状态来源。

本文没有执行供应商网络查询，也没有取得合同、报价、授权书、字段说明或样例数据。无法由仓库现有证据确认的价格、SLA、历史覆盖范围、许可权利、接口字段、交付方式和供应商授权状态统一记为 `UNKNOWN` 或“待供应商书面确认”。不得以销售口头说明、网页宣传、接口可调用或技术上可抓取替代书面许可和可审计证据。

## 3. 候选来源类别

所有候选当前决策状态均为 `DISCOVERED`。本节的“可作为候选”只表示允许继续收集证据，不表示已经批准、已接入或可以开始 adapter。

### 3.1 A：上交所 / 上证信息正式数据服务

- 推荐用途：上交所证券状态 FORMAL 来源的优先询价与合规审查候选。
- 是否可作为 FORMAL 候选：是，仅为候选；必须通过全部 FORMAL 硬门槛。
- 是否可作为 TEST/DEMO：`UNKNOWN`；只有书面试用或样例许可明确允许后才可使用。
- 当前许可状态：`UNKNOWN`；未取得合同、订单或书面授权证据。
- 当前技术状态：`UNKNOWN`；未核验交付接口、字段、稳定主键、revision 或时间语义。
- 当前阻断项：主体与授权范围、持久化/历史库/回放/AI 使用权、稳定身份、修订链、时间语义、交付方式、费用和 SLA 均待书面确认。
- 需要取得的证据：数据提供主体说明、交易所授权或合法来源说明、合同/订单样本、许可条款、字段与版本说明、时间/revision 说明、脱敏样例、报价及支持政策。
- 当前决策状态：`DISCOVERED`。

### 3.2 B：深交所 / 深证信息正式数据服务

- 推荐用途：深交所证券状态 FORMAL 来源的优先询价与合规审查候选。
- 是否可作为 FORMAL 候选：是，仅为候选；必须通过全部 FORMAL 硬门槛。
- 是否可作为 TEST/DEMO：`UNKNOWN`；只有书面试用或样例许可明确允许后才可使用。
- 当前许可状态：`UNKNOWN`；未取得合同、订单或书面授权证据。
- 当前技术状态：`UNKNOWN`；未核验交付接口、字段、稳定主键、revision 或时间语义。
- 当前阻断项：主体与授权范围、持久化/历史库/回放/AI 使用权、稳定身份、修订链、时间语义、交付方式、费用和 SLA 均待书面确认。
- 需要取得的证据：数据提供主体说明、交易所授权或合法来源说明、合同/订单样本、许可条款、字段与版本说明、时间/revision 说明、脱敏样例、报价及支持政策。
- 当前决策状态：`DISCOVERED`。

### 3.3 C：获得交易所授权的商业数据供应商

- 推荐用途：覆盖 SSE/SZSE 的统一商业交付候选，供 FORMAL 来源合规与技术评估。
- 是否可作为 FORMAL 候选：是，但必须逐个供应商证明授权链和全部 FORMAL 门槛；不得依据“商业供应商”身份自动通过。
- 是否可作为 TEST/DEMO：`UNKNOWN`；必须有书面试用、样例或测试许可。
- 当前许可状态：`UNKNOWN`；尚未选择具体供应商，也无授权范围证据。
- 当前技术状态：`UNKNOWN`；尚无字段手册、样例、版本或交付方式证据。
- 当前阻断项：具体主体、交易所授权编号/范围、转授权边界、持久化/回放/AI 权利、身份和 revision/time 语义均未知。
- 需要取得的证据：供应商主体与授权证明、许可链、合同/订单、用途与再展示限制、字段/版本文档、样例、报价、SLA 和支持政策。
- 当前决策状态：`DISCOVERED`。

### 3.4 D：Tushare 个人研究数据

- 推荐用途：个人研究或 TEST/DEMO 候选的许可、字段与时间语义核验；当前不作为正式来源。
- 是否可作为 FORMAL 候选：否（当前状态）。未取得单独书面授权并证明全部 FORMAL/PIT 语义前不得批准。
- 是否可作为 TEST/DEMO：可作为候选，但书面许可和用途边界确认前不得新增接入；现有固定夹具继续优先。
- 当前许可状态：`UNKNOWN`；未取得针对本地长期持久化、历史回放和内部 AI/智能体使用的单独书面确认。
- 当前技术状态：`UNKNOWN`；本文未调用接口，也未核验字段、稳定主键、revision、published/effective/observed 时间。
- 当前阻断项：许可用途、持久化、历史回放、AI 使用、稳定身份、完整历史状态、revision 和时间语义均待书面确认。
- 需要取得的证据：书面许可答复、适用条款、字段与版本说明、稳定身份说明、revision/time 说明、脱敏样例和允许用途/禁止用途清单。
- 当前决策状态：`DISCOVERED`。

### 3.5 E：内部固定 TEST/DEMO 夹具

- 推荐用途：继续支持 V8 TEST/DEMO 的确定性契约、幂等、并发和失败原子性测试。
- 是否可作为 FORMAL 候选：否。
- 是否可作为 TEST/DEMO：是，继续沿用既有固定夹具边界；这不是本决策包授予的新来源批准。
- 当前许可状态：不适用外部供应商授权；夹具内容的内部来源、合成方式或可使用权仍须可审计，禁止混入未授权真实数据。
- 当前技术状态：V8 已支持固定 `SECURITY_STATUS_RAW_TEST_V1` TEST/DEMO 夹具；FORMAL/PIT 仍由门禁拒绝。
- 当前阻断项：不能用于 FORMAL、PIT_VERIFIED 或未来正式 lineage；不能替代真实来源证据。
- 需要取得的证据：夹具版本、来源/合成说明、预期 Hash、允许用途、禁止用途和维护责任记录。
- 当前决策状态：`DISCOVERED`；继续使用既有夹具不等于本包将其升级为来源批准状态。

### 3.6 不得默认采用的路径

- 免费聚合接口：许可、授权链、历史持久化、revision 与时间语义未证明时不得作为 FORMAL/PIT。
- 当前 `securities` 表投影：仅是当前态兼容投影，不能证明历史状态、发布时间或当时可知性。
- 网页抓取：可访问不等于获得复制、持久化、历史回放或内部 AI 使用许可；不得默认为正式来源。
- symbol、证券代码或简称匹配：不得替代稳定 `sourceInstrumentId` 和显式身份映射。

## 4. FORMAL 硬性准入门槛

以下门槛必须全部满足。任何一项缺失、为 `UNKNOWN`、仅有口头说明或证据不可审计时，候选来源**不得批准为 FORMAL**。

| # | 硬性门槛 | 必须取得的证据 | 当前状态 |
|---:|---|---|---|
| 1 | 明确的数据提供主体 | 主体全称、签约主体和责任边界的书面材料 | `UNKNOWN / 未满足` |
| 2 | 明确的交易所授权或合法来源说明 | 授权编号、授权范围或合法来源链的书面说明 | `UNKNOWN / 未满足` |
| 3 | 允许本地持久化 | 合同或授权条款明确允许本地数据库保存 | `UNKNOWN / 未满足` |
| 4 | 允许构建内部历史库 | 合同或授权条款明确允许长期版本化归档 | `UNKNOWN / 未满足` |
| 5 | 允许历史回放 | 合同或授权条款明确允许内部历史重放/回测 | `UNKNOWN / 未满足` |
| 6 | 允许内部模型和智能体分析 | 对内部 AI、模型和智能体处理的书面许可 | `UNKNOWN / 未满足` |
| 7 | 稳定 `sourceInstrumentId` | 主键定义、生命周期和样例 | `UNKNOWN / 未满足` |
| 8 | 身份不只依赖 symbol 或名称 | 代码复用、简称变化和身份连续性说明 | `UNKNOWN / 未满足` |
| 9 | 明确 revision 或更正语义 | revision ID、顺序、覆盖/撤销/重发规则 | `UNKNOWN / 未满足` |
| 10 | 明确 `publishedAt` 语义 | 来源发布时间定义、时区、精度和缺失规则 | `UNKNOWN / 未满足` |
| 11 | 明确 `effectiveDate` 语义 | 业务生效日期/时间定义和更正规则 | `UNKNOWN / 未满足` |
| 12 | 明确可获得时间或 `observedAt` 语义 | 首次可获得/送达/观察时间的定义与证据 | `UNKNOWN / 未满足` |
| 13 | 可获取历史状态变化 | 历史事件覆盖说明与可下载样例 | `UNKNOWN / 未满足` |
| 14 | 可识别删除、更正和重复发布 | 删除标记、撤销、revision 和重复键规则 | `UNKNOWN / 未满足` |
| 15 | 有机器可读交付方式 | API、文件、数据库或专线等书面交付说明 | `UNKNOWN / 未满足` |
| 16 | 有字段说明和版本管理 | 字段字典、Schema/API 版本和兼容策略 | `UNKNOWN / 未满足` |
| 17 | 有故障、延迟和补发规则 | 延迟、缺失、补发、重试和故障通知规则 | `UNKNOWN / 未满足` |
| 18 | 有书面合同、订单或授权证据 | 已签或可执行的合同、订单、授权书及附件 | `UNKNOWN / 未满足` |

FORMAL 批准还必须明确允许用途、禁止用途、地域/主体限制、第三方展示限制、数据保留期限、到期/终止后的处置方式和重新审查条件。技术可用性不能弥补许可缺口。

## 5. PIT_VERIFIED 额外门槛

只有全部 FORMAL 门槛已经通过，才允许评估 PIT_VERIFIED。以下额外门槛也必须全部有可复核证据：

| # | PIT_VERIFIED 门槛 | 必须证明的内容 | 当前状态 |
|---:|---|---|---|
| 1 | 历史时点真实可获得 | 记录在对应历史时点已经对订阅方可见 | `UNKNOWN / 未满足` |
| 2 | `publishedAt` 不是后补推测 | 时间来自权威交付事实，而非根据 effective time 倒推 | `UNKNOWN / 未满足` |
| 3 | revision 链完整 | 初始版本、全部更正、撤销和重发均可排序与复核 | `UNKNOWN / 未满足` |
| 4 | 历史更正不覆盖原始版本 | 原始版本保持可取回，不能只看到最终当前态 | `UNKNOWN / 未满足` |
| 5 | 可重建任意知识截止时间 | 给定 knowledge cutoff 可确定当时可见版本集合 | `UNKNOWN / 未满足` |
| 6 | 无未来信息污染 | 回放不会读取截止时间后才发布或更正的事实 | `UNKNOWN / 未满足` |
| 7 | 历史回放可重复 | 相同输入版本、规则和 cutoff 得到相同结果 | `UNKNOWN / 未满足` |

任一 PIT 门槛不满足时，最高 assurance 只能依据实际证据评为 `RECONSTRUCTED_VERIFIED` 或 `INFERRED_RESEARCH`，不得声明 `PIT_VERIFIED`。当前没有任何候选达到 FORMAL 或 PIT 批准条件。

## 6. 供应商书面询问模板

以下模板可直接发送给候选供应商。发送前只填写联系人、主体和项目用途，不写入仓库 Token、账号、密码或非公开报价。

> 主题：证券状态历史数据许可、时间语义与技术样例书面确认
>
> 您好！我们正在评估一项仅供内部研究系统使用的证券状态历史数据服务，范围包括上市、退市、ST/摘帽、板块、active/listed、交易所变化及证券身份治理。为完成合规和技术评审，请贵方对以下问题逐项书面回复；无法确认的项目请明确标注 UNKNOWN，并提供适用合同、授权、字段文档或样例附件。

1. 数据是否直接来自上交所/深交所授权渠道？分别对应哪些市场与数据类别？
2. 授权编号、授权主体、授权范围和有效期能否书面确认？如为转授权，请说明完整授权链。
3. 个人研究、企业内部系统和对外商业产品分别需要何种授权？
4. 是否允许在本地数据库长期保存原始数据及全部 revision？保存期限和终止后处置要求是什么？
5. 是否允许构建内部历史库、历史回放、回测和可重复研究？
6. 是否允许内部 AI 模型、机器学习系统和多智能体分析使用这些数据？是否需要附加授权？
7. 是否禁止向第三方展示、导出或再分发原始数据？允许展示的最小范围是什么？
8. 派生指标、Hash、审计 lineage、模型结论和聚合结果能否长期保存或展示？
9. 是否提供不依赖证券代码或简称的稳定证券主键？字段名称、生命周期和唯一性规则是什么？
10. 股票代码复用、证券简称变化、跨交易所变化、合并或拆分如何保持身份连续性？
11. 上市、退市、ST、摘帽、板块、交易所和 active/listed 状态变化是否完整覆盖？各字段业务定义是什么？
12. 是否提供历史修订版本，还是只提供最终当前态？原始版本是否会被覆盖？
13. 是否提供稳定 revision ID、revision 顺序、撤销、删除、更正和重复发布标识？
14. `publishedAt`、`effectiveDate/effectiveAt`、`observedAt/availableAt` 分别代表什么？时区、精度和缺失规则是什么？
15. 历史数据最早覆盖日期是什么？请按数据类别和市场分别书面说明。
16. 增量更新频率、正常延迟、晚到数据、补发窗口和重拉机制是什么？
17. 故障、延迟、缺失记录和更正发布如何通知？
18. 字段、Schema 或 API 版本升级的通知周期和兼容策略是什么？
19. 可选交付方式有哪些：API、批量文件、数据库、消息流或专线？
20. 各交付方式的频率限制、并发限制、单次数据量和重试规则是什么？
21. SLA、可用性承诺、技术支持时间和故障响应机制是什么？如无 SLA 请明确说明。
22. 费用构成、试用费用、合同周期、续费、升级和退出条款是什么？
23. 能否提供脱敏或试用样例、字段字典、版本说明和许可条款样本供评审？
24. 能否在合同或订单附件中明确本地持久化、内部历史库、历史回放、内部 AI 使用、原始数据展示限制和派生结果权利？

> 请同时标注每项答复对应的证据文件名、版本、发布日期和有效期。口头说明或销售演示不能替代书面确认。

## 7. 样例数据验收模板

### 7.1 供应商必须提供的样例材料

- 脱敏或试用数据文件；
- 字段字典与数据 Schema 版本；
- 每条记录的稳定 `sourceInstrumentId`；
- `sourceRecordId`、revision ID/序号及删除/撤销/更正标志；
- `publishedAt`、`effectiveDate/effectiveAt`、`observedAt/availableAt` 的原始字段；
- 时区、时间精度、NULL 与缺失语义；
- 上游数据版本、生成/交付批次和文件/API 版本；
- 允许用于样例验收、本地保存和内部分析的书面许可。

样例可以脱敏，但同一证券和同一 revision 链内的逻辑身份必须保持稳定，不能因脱敏破坏身份、顺序或时间关系。

### 7.2 必须覆盖的场景

| # | 样例场景 | 必须证明 |
|---:|---|---|
| 1 | 首次上市 `FULL_STATUS_SNAPSHOT` root | 初始完整状态、稳定身份、无 predecessor、published/effective 语义明确 |
| 2 | `ST_CHANGE` | 只改变 ST 状态，其他状态字段可核对 |
| 3 | 摘帽 `ST_CHANGE` | 从 ST 到非 ST 的独立变化及生效/发布时间 |
| 4 | `ACTIVE_CHANGE` | active 业务定义明确，不把普通停牌或无成交误映射为 inactive |
| 5 | `DELISTING` | `listed=false, active=false` 及其 effective/published/revision 证据 |
| 6 | 同一记录 revision 更正 | 原始版和更正版同时保留，revision 身份、顺序和更正原因可辨识 |
| 7 | 延迟发布 | effective time 早于真实发布/可获得时间，且 published/observed 不由系统猜测 |
| 8 | 同一证券多次状态变化 | 稳定身份、连续 revision/event 顺序和不覆盖历史版本 |
| 9 | symbol 或简称变化 | `sourceInstrumentId` 保持稳定，不因代码/简称匹配自动新建或合并身份 |
| 10 | `sourceInstrumentId` 稳定性 | 跨全部上述样例保持同一逻辑证券身份，代码复用场景能区分不同证券 |
| 11 | `BOARD_CHANGE` | 板块定义和变化语义明确，只改变允许的 board 状态 |
| 12 | `EXCHANGE_CHANGE` | 交易所变化前后稳定身份与时间语义明确，不按 symbol 自动合并 |

### 7.3 样例验收记录

每次样例验收必须记录：供应商、样例版本、收到时间、证据文件、许可范围、场景覆盖、缺失字段、冲突、可重复 Hash、审查人和结论。当前尚未收到任何供应商样例，所有样例验收项均为 `NOT_PROVIDED`，候选仍保持 `DISCOVERED`；只有法律前置允许继续后，具体候选才可进入 `TECHNICAL_SAMPLE_REQUIRED`。

## 8. 技术 PoC 验收门槛

**只有书面许可审查先通过，才允许创建独立 PoC 任务。** 本决策包不执行 PoC，也不实现 adapter。

PoC 必须在隔离 TEST/DEMO 或合同明确允许的试用边界内验证：

| # | PoC 硬门槛 | 当前状态 |
|---:|---|---|
| 1 | 无人工猜测证券身份 | `NOT_STARTED` |
| 2 | 无 symbol、简称、代码前缀自动合并 | `NOT_STARTED` |
| 3 | raw 记录和原始时间/revision 字段可完整保存 | `NOT_STARTED` |
| 4 | 同一 revision 可重复拉取并逐字段一致 | `NOT_STARTED` |
| 5 | 增量摄取幂等，冲突显式失败 | `NOT_STARTED` |
| 6 | published/effective/observed 时间不混用、不倒填 | `NOT_STARTED` |
| 7 | Java 与 PostgreSQL canonical Hash 稳定一致 | `NOT_STARTED` |
| 8 | 两个真实 backend 并发幂等且只有一个逻辑胜者 | `NOT_STARTED` |
| 9 | 任一步失败均无部分 raw/event/result/lineage 写入 | `NOT_STARTED` |
| 10 | 使用随机临时 Schema，不触碰 public Schema | `NOT_STARTED` |
| 11 | 测试数据、连接和临时 Schema 可精确清理 | `NOT_STARTED` |

PoC 还必须证明 FORMAL/TEST/DEMO namespace、assurance 上限、封存、重试和 V8 门禁不能被 direct SQL 绕过。PoC 通过也不自动等于 FORMAL/PIT 批准；法律许可和技术批准必须分别留证。

## 9. 决策状态模型

每个具体候选来源只能处于以下一个状态：

| 状态 | 含义 |
|---|---|
| `DISCOVERED` | 仅发现候选类别或供应商，尚未完成证据收集 |
| `LEGAL_REVIEW_REQUIRED` | 已取得部分材料，但许可、授权或用途仍需书面审查 |
| `TECHNICAL_SAMPLE_REQUIRED` | 法律前置允许继续，仍需样例和字段/time/revision 验收 |
| `REJECTED` | 许可、来源、语义或技术硬门槛不满足；原因必须留证 |
| `APPROVED_FOR_TEST_DEMO` | 仅批准明确范围的 TEST/DEMO 使用，不得进入 FORMAL lineage |
| `APPROVED_FOR_FORMAL_RECONSTRUCTED` | 全部 FORMAL 门槛通过，但 PIT 证据不足；最高为重建验证 |
| `APPROVED_FOR_FORMAL_PIT` | 全部 FORMAL 和 PIT_VERIFIED 门槛均有书面与技术证据 |

### 9.1 当前候选状态

| 类别 | 当前状态 | 说明 |
|---|---|---|
| A 上交所/上证信息正式数据服务 | `DISCOVERED` | 尚无具体合同、报价、许可或样例 |
| B 深交所/深证信息正式数据服务 | `DISCOVERED` | 尚无具体合同、报价、许可或样例 |
| C 获交易所授权的商业供应商 | `DISCOVERED` | 尚未选择具体主体或取得授权链证据 |
| D Tushare 个人研究数据 | `DISCOVERED` | 只允许继续许可和字段核验，不得视为 FORMAL/PIT |
| E 内部固定 TEST/DEMO 夹具 | `DISCOVERED` | 继续既有夹具边界，不由本包授予新批准状态 |

当前没有任何候选处于 `APPROVED_*` 状态。

### 9.2 状态升级证据

任何状态升级必须创建可审计记录，至少包含：

- 审批人；
- 审批时间；
- 证据文件及 Hash；
- 合同或订单编号；
- 数据版本；
- 允许用途；
- 禁止用途；
- 到期时间；
- 重新审查条件。

还应记录候选主体、授权范围、适用市场、决策前状态、决策后状态、审查意见和否决原因。合同、字段版本、授权范围、用途、主体或交付语义发生变化时必须重新审查，不得原地把旧批准解释为新范围。

`APPROVED_FOR_TEST_DEMO` 不能传递为 FORMAL；`APPROVED_FOR_FORMAL_RECONSTRUCTED` 不能传递为 PIT_VERIFIED；只有明确的 `APPROVED_FOR_FORMAL_PIT` 记录才能支持后续 PIT 候选任务。

## 10. 当前暂定建议

以下是**暂定建议，不是最终批准**：

1. TEST/DEMO 继续使用固定夹具。
2. Tushare 只作为个人研究候选进行许可和字段核验。
3. FORMAL 优先询价交易所正式数据服务或明确授权供应商。
4. 未取得书面许可和样例验证前，2D-2B-1B-2 adapter 保持阻断。
5. 不得为了推进进度而降低 FORMAL/PIT 门槛。

免费聚合接口、当前 `securities` 当前态投影和网页抓取均不进入暂定 FORMAL 方案。

## 11. 用户下一步行动清单

以下行动在仓库外由用户执行；仓库内不保存真实 Token、密码或未脱敏账号信息。

- [ ] 联系上证信息行情服务，发送第 6 节书面询问模板。
- [ ] 联系深证信息数据服务，发送第 6 节书面询问模板。
- [ ] 联系至少一家明确声称获得交易所授权的商业供应商，并要求提供授权链书面证据。
- [ ] 联系 Tushare，确认个人本地持久化、历史回放和内部 AI/智能体使用边界。
- [ ] 收集各候选报价、合同/订单样本、授权附件、字段说明和版本政策。
- [ ] 要求每个候选提供第 7 节覆盖场景的脱敏或试用样例数据。
- [ ] 将书面答复、合同附件、样例说明和文件 Hash 保存为来源审批证据，不提交敏感凭据。
- [ ] 按第 4、5、7 节逐项填写 `UNKNOWN` 项，不允许用口头说明代填。
- [ ] 完成法律/许可审查后，再决定是否允许独立技术 PoC。
- [ ] 只有具体来源达到 `APPROVED_FOR_FORMAL_RECONSTRUCTED` 或 `APPROVED_FOR_FORMAL_PIT`，且独立法律/许可与技术复审通过，才返回仓库建立正式 adapter 任务；`APPROVED_FOR_TEST_DEMO` 不满足该条件。

## 12. 当前决策结论与阻断状态

- 本决策包已准备审批框架，但没有批准任何来源。
- 所有候选当前均为 `DISCOVERED`。
- FORMAL 准入：`BLOCKED`。
- PIT_VERIFIED 准入：`BLOCKED`。
- 2D-2B-1B-2 source adapter：`NOT_STARTED / BLOCKED`。
- 2D-2B-1B-3 真实来源闭环：`NOT_STARTED`。
- 2D-2B-2 history/calendar projection：`NOT_STARTED`。
- Universe：`NOT_STARTED`。

下一阶段唯一入口仍是取得并审查正式来源、许可、持久化/历史回放权利、稳定 source instrument ID、revision 与 published/effective/observed 时间语义的书面证据。完成本决策包本身不解除阻断，也不授权 adapter 编码。
