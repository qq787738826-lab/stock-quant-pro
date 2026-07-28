# 3A-R3A 可验证 PIT 市场原始事实 V2 设计冻结

## 1. 文档状态与边界

状态：**设计冻结已在任务分支完成，待 ChatGPT 基于实际 Git 提交验收，尚未合入；
生产实现未开始。**

- 冻结集成基线：`4fea1e210e683fea8490685879529f1d27e6448b`
- 任务分支：`codex/1.4.0-stage-3ar3a-pit-market-facts-design-v2`
- 目标提交：`docs(agent): freeze pit market facts v2 design`
- 阶段记录：
  [stage-3ar3a-pit-market-facts-v2-design.md](../stage-3ar3a-pit-market-facts-v2-design.md)
- 当前事实唯一权威：[CURRENT_STATE.md](../CURRENT_STATE.md)
- 路线方向：[ROADMAP.md](../ROADMAP.md)
- 跨阶段决定：[DECISIONS.md](../DECISIONS.md)

本阶段只完成来源资格研究、PIT 事实模型、QFQ as-of 算法和后续实施门禁。它不：

- 修改 Java、Python、Vue 或 SQL；
- 新增 Flyway 迁移；
- 修改 `1.4.0-stage-2f-strategy-backtest-v1`；
- 批准或接入任何新 Provider；
- 写数据库、更新行情或摄取公告；
- 创建 Day 002 Shadow 批次；
- 开启 scheduler、完成长期 3A 或开始 3B。

## 2. 设计动因与当前运行事实

Day 001 已证明 Shadow 控制面会如实保存安全不足结果。随后对 `000001`、`000002`、
`000006` 的受控行情更新修复了 `MARKET_DATA_TOO_STALE`，并通过现有生产入口形成
3 个观察批次和 780 条 V9 日线观察，但全部 `sourceRevision=NULL`。因此当前阻断从
运营补数问题收敛为来源版本和 knowledge-time 的结构性问题。

当前正式审计结论是：

```text
PROVIDER_REVISION_UNVERIFIED
```

该结论不表示行情内容一定错误；它表示当前公开链路没有足够证据证明供应商版本身份、
发布时间和修订关系，不能满足 2F V1 的可靠来源门禁。

## 3. 当前行情链路与字段损失点

### 3.1 端到端链路

```text
POST /api/data/history/sync
  -> MarketDataController.syncHistory
  -> MarketDataCenterService.syncHistory
  -> MarketDataService.history
  -> Python GET /market/history/{symbol}
  -> _load_history
  -> AKShare stock_zh_a_hist_tx / 其他 fallback
  -> _normalize_history
  -> _bars_to_records
  -> Java List<Bar>
  -> MarketDataPersistenceService.persistBars
  -> market_data_observation_batches
  -> daily_bar_observations
```

| 层 | 当前实际输入/输出 | 版本与时间事实 |
|---|---|---|
| Controller | `symbol`、`days` | 不接收来源版本 |
| `MarketDataCenterService` | 调用 `MarketDataService.history`，返回数量和日期范围 | 不增加 Provider 元数据 |
| `MarketDataService` HTTP 请求 | `/market/history/{symbol}?days=...` | Java 未保存响应头 |
| Python Provider | `symbol`、start/end、`adjust="qfq"` | 当前首选 `AKShare-Tencent` |
| Python 规范化 | `tradeDate/open/high/low/close/volume/amount/turnoverRate` | 缺失值会按现有兼容逻辑补零；没有 revision DTO |
| Python 返回 DTO | `symbol/dataSource/tradeDate/OHLC/volume/amount/turnoverRate` | 不含 revision、snapshot、provider update time |
| Java `mapBar` | 只反序列化 `Bar` 数值字段 | HTTP 头和未知字段均未保留 |
| `persistBars` 普通入口 | `symbol/bars/sourceCode` | 固定调用五参数入口时 `sourceRevision=null` |
| V9 batch | 本地 `OBS_BATCH_V1-<UUID>`、`LOCAL_DATASET_V1-<UUID>` | 都是本地捕获身份 |
| V9 observation | QFQ 内容、首次观察/known/recorded、Hash、观察版本 | 没有供应商 revision |

当前 `sourceCode` 来自 Python `_load_history` 选择的本地字符串，例如
`AKShare-Tencent`。`datasetVersion=LOCAL_DATASET_V1-<UUID>` 由 Java 每次合格捕获
生成，只代表本地捕获批次；它不是供应商 revision、snapshot 或数据集版本。

### 3.2 `000001` 两次受控响应审计

此前只读 Provider 审计使用仓库固定的 `akshare==1.18.64`，对同一证券、同一日期范围
执行两次公开调用，结论如下：

- 行数一致；
- 规范化数据内容一致；
- 原始响应 body 一致；
- 原始 Tencent payload 中 `version=18` 一致；
- DataFrame 实际只有 `date/open/close/high/low/amount`，`attrs` 为空；
- 当前代码把该响应的 `amount` 作为成交量兼容输入，货币成交额和换手率没有得到可靠
  Provider 字段；
- 响应头未提供或当前链路未保留 `ETag`、`Last-Modified`、稳定 request ID；
- 没有 revision、snapshot ID、供应商发布时间、修订关系或历史版本查询能力。

当前 AKShare 官方文档已展示 `stock_zh_a_hist_tx` 的八字段形态，并明确指出前复权历史值
会随除权除息重新调整；这与固定版本实测的六字段形态也说明接口形态需要版本化样例测试，
不能仅凭文档字段推断当前运行语义。参考：
[AKShare 股票数据文档](https://akshare.akfamily.xyz/data/stock/stock.html)。

### 3.3 `version=18` 的冻结结论

`version=18` 不能进入 `sourceRevision`。现有证据只证明两次相同响应中该值相同，不能
证明：

1. 它由某一条数据发布版本产生；
2. 同一发布版本重复请求时稳定；
3. 内容修订时一定变化；
4. 能表达 A→B→A 或替换/撤回关系；
5. 能查询历史版本；
6. 有腾讯或 AKShare 的正式字段说明。

它最多作为“不合格候选元数据”被审计，不得进入可靠 Hash 白名单、数据版本对象或
2F 输入资格。

## 4. Provider revision 资格门

一个字段只有同时满足下列八项，才能标记为 `PROVIDER_VERIFIED`：

1. 由供应商或其明确上游来源产生；
2. 对同一发布版本稳定；
3. 内容修订后会变化；
4. 不由本地抓取时间生成；
5. 不由本地内容 Hash 生成；
6. 能解释历史版本关系；
7. 能在重复请求和受控更正样例中验证；
8. 有正式字段语义、合同附件或可重复实验的证据。

下列值永远不能单独充当 `sourceRevision`：

- 腾讯 `version=18`；
- HTTP `Date`；
- UUID；
- `batchVersion`；
- 本地 `datasetVersion`；
- `observationVersion`；
- `canonicalContentHash`；
- 抓取/缓存时间；
- 数据库 ID；
- 请求序号；
- `symbol+tradeDate` 拼接值。

Hash 只能证明给定内容相同；首次捕获时间只能证明本系统何时看见内容。两者都不能冒充
供应商发布版本。

## 5. 来源资格研究

研究日期：`2026-07-28`。本阶段只使用公开官方资料和已完成的受控响应审计，没有购买、
调用或试用新的付费来源。费用、SLA、合同权利、历史覆盖和 revision 语义没有正式证据
时一律记为 `UNKNOWN`。

### 5.1 A：当前 AKShare-Tencent

| 维度 | 当前证据 | 资格结论 |
|---|---|---|
| 未复权日线 | AKShare 公开函数支持 `adjust=""`；当前生产链实际请求 QFQ | 可作后续技术候选，当前链未使用 |
| QFQ 日线 | 当前生产链直接请求 `adjust="qfq"` | 只有当前投影价值；没有因子 lineage |
| amount/turnover | 固定版本实测没有可靠货币 amount/turnover；现有兼容逻辑补值 | 不能作为 V2 权威字段 |
| 交易日历 | 当前链路没有 | `UNKNOWN`，不能由周一至周五替代正式日历 |
| 复权因子 | 当前 Tencent 响应没有独立因子事实 | 不可用于 as-of QFQ |
| 公司行动 | 当前链路没有 | 不可解释因子变化 |
| provider revision | 只有未验证 `version=18` | `PROVIDER_REVISION_UNVERIFIED` |
| 发布时间/更新时间 | HTTP Date 不是数据发布时间；无逐记录字段 | 不合格 |
| 更正语义 | 无旧版本查询和关系证明 | 不合格 |
| API 稳定性 | AKShare 公开函数可用，但固定版本实测与当前文档字段形态不同；无本项目 SLA | 研究级 |
| 授权边界 | AKShare 包许可不能自动授予腾讯底层数据的本地历史库/回放权利 | `UNKNOWN` |
| 适用角色 | 当前行情投影、人工研究、交叉校验 | 不得作为 2F V1 可靠来源 |

冻结结论：继续保留为研究级 current projection 和交叉校验来源；不把其 QFQ 序列、
`version=18` 或本地捕获元数据提升为 PIT 来源。

### 5.2 B：Tushare Pro 候选

官方资料证明的技术覆盖：

- `daily` 提供未复权 OHLC、成交量和成交额，并声明交易日 15:00 至 16:00 入库：
  [A 股日线](https://tushare.pro/document/1?doc_id=27)；
- `adj_factor` 独立提供 `ts_code/trade_date/adj_factor`，公开输出没有 revision 或
  snapshot 字段：[复权因子](https://tushare.pro/document/2?doc_id=28)；
- `trade_cal` 提供 exchange、calendar date、open flag 和前一交易日：
  [交易日历](https://tushare.pro/document/2?doc_id=26)；
- `pro_bar` 的 QFQ 是动态计算，公式为“当日价格 × 当日因子 ÷ 锚点因子”，且锚点取决于
  查询 `end_date`：[A 股复权行情](https://tushare.pro/document/2?doc_id=146)；
- `dividend` 提供公告日、登记日、除权除息日、实施公告日、送转和现金分红字段：
  [分红送股](https://tushare.pro/document/2?doc_id=103)。

| 维度 | 当前证据 | 资格结论 |
|---|---|---|
| raw daily | 有正式接口和明确字段 | V2 优先技术候选 |
| amount/turnover | daily 有 amount；turnover 不在该接口，需另行接口和口径审计 | 不能隐式拼接 |
| adj factor | 有独立因子接口和更新时段 | 必须作为独立观察版本捕获 |
| trade calendar | 有独立日历接口 | 必须独立版本化，不能只存最终状态 |
| QFQ | `pro_bar` 依赖 end date 和动态因子 | 不能直接保存为历史不可变事实 |
| corporate action | dividend 覆盖部分分红送转日期/比例 | 配股、拆并股、修订关系和完整性仍需样例 |
| revision/snapshot | 公开输出没有明确字段 | 不得编造；当前未达到 Provider PIT |
| update/publish time | 文档给出运营更新时段，不是逐记录 `providerPublishedAt` | 不能直接用作 known time |
| 权限/调用限制 | 由积分和账户权限控制，具体限制可能变化 | 实施前现场核验 |
| 个人开发可行性 | 官方 Python/HTTP 接口可供个人账户使用 | 技术上较高 |
| 本地持久化/回放权利 | 当前数据服务协议只明确个人、不可转让、非商业且个人查看用途 | 必须取得书面确认 |

Tushare 的公开数据服务协议没有为本项目明确授予长期本地历史库、历史回放、模型/Agent
使用和派生数据保留权。参考：
[Tushare 数据服务协议](https://tushare.pro/document/1?doc_id=405) 和
[Tushare 用户协议](https://tushare.pro/document/1?doc_id=409)。

冻结结论：Tushare 是 `raw daily + adj_factor + trade_cal` 的优先候选，但不是已批准
来源。只有书面许可和样例门禁通过后，才可以作为 `SYSTEM_KNOWLEDGE_PIT` 的候选 Adapter；
公开字段不足以升级为 `PROVIDER_PIT_VERIFIED`。

### 5.3 C：企业级来源候选

Wind 官方资料证明其提供数据库落地、FileSync、Server API、Client API、历史行情回溯、
权益事件和量化研究场景；这说明它适合作为企业级询价类别，但不证明具体产品已经提供
本项目要求的 revision ID、旧版本重放或许可条款。参考：

- [Wind 数据库传输服务](https://www.wind.com.cn/portal/zh/WDS/database.html)
- [Wind Server API](https://www.wind.com.cn/mobile/WDS/sapi/en.html)
- [Wind Client API](https://www.wind.com.cn/mobile/ClientApi/zh.html)

| 候选 | 已有官方证据 | 仍为 `UNKNOWN` 的关键事项 | 当前资格 |
|---|---|---|---|
| Wind | 落地数据库/API、历史行情回溯、权益事件、量化研究场景 | revision/snapshot 字段、旧版本查询、逐记录发布时间、因子历史版本、合同许可、报价、SLA | 待书面确认的企业候选 |
| iFinD | 本阶段没有取得足以冻结字段/权利的官方证据 | 全部 PIT、revision、字段、许可、报价和 SLA | `UNKNOWN` |
| Choice | 本阶段没有取得足以冻结字段/权利的官方证据 | 全部 PIT、revision、字段、许可、报价和 SLA | `UNKNOWN` |

未取得书面回复、合同附件和样例响应前，不批准采购或接入。

### 5.4 企业供应商书面问题清单

1. 日线是否提供 `revisionId`、`snapshotId` 或数据版本？
2. 修订后旧版本能否按版本或 knowledge cutoff 重新查询？
3. 是否提供逐记录 provider update time 或 publication time，语义和时区是什么？
4. 复权因子是否有历史版本、修订关系和首次发布时间？
5. 公司行动是否同时提供公告时间、生效时间和修订时间？
6. 是否提供正式交易日历及临时休市的追加/修订版本？
7. 合同是否允许本地长期落库、备份和灾备恢复？
8. 合同是否允许历史回放、回测、派生计算和内部 Agent/模型使用？
9. 是否提供测试账号，以及含原始日线、因子、日历、公司行动和更正链的样例响应？
10. 上述字段、版本、时间、历史保留和许可语义能否写入合同或技术附件？

回复必须由供应商可归责主体书面确认。销售口头描述、截图或字段名本身不能通过门禁。

## 6. 来源方案比较与推荐

评分为设计期判断，`1` 最弱、`5` 最强；“成本”高分表示更可负担，“维护成本”高分
表示维护负担更低，“授权风险”高分表示风险更低。分数不是价格、SLA 或采购承诺。

| 方案 | 正确性 | PIT 可证明性 | 成本 | 个人可行性 | API 稳定性 | 维护成本 | 授权风险 | 历史回放 | 3A 解阻速度 | 合计 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A 等待企业 Provider | 5 | 4 | 1 | 1 | 4 | 3 | 5 | 5 | 1 | 29 |
| B Tushare raw/factor/calendar | 4 | 3 | 4 | 5 | 4 | 4 | 2 | 2 | 4 | 32 |
| C 当前 AKShare-Tencent | 2 | 2 | 5 | 5 | 2 | 2 | 1 | 1 | 5 | 25 |
| D 分工明确的混合来源 | 5 | 3 | 3 | 4 | 4 | 2 | 2 | 3 | 4 | 33 |

推荐 **D：分工明确的混合来源**，但不是把不同来源的值无条件混合：

1. Tushare 作为 raw daily、adj_factor、trade_cal 的优先候选权威，前提是书面许可和
   样例验收通过；
2. AKShare-Tencent 只保留 current projection 和交叉校验角色，不进入可靠 as-of
   输入；
3. 企业 Provider 的 revision/旧版本/许可证据并行询价；证据成熟后新增独立 Adapter
   和 qualification，不覆盖已有系统观察；
4. 不用跨来源“多数表决”修改原始事实；每条事实必须保留唯一 source lineage；
5. 任一来源没有资格时只降级该来源，不降低 `PROVIDER_PIT_VERIFIED` 门槛。

该推荐同时考虑正确性、可证明性、维护和授权风险，不是因为免费或低成本。

## 7. PIT_MARKET_FACTS_V2 总体契约

冻结候选契约：

```text
PIT_MARKET_FACTS_V2
PIT_MARKET_FACTS_CANONICAL_V2
RAW_DAILY_BAR_OBSERVATION_V2
ADJUSTMENT_FACTOR_OBSERVATION_V1
TRADING_CALENDAR_OBSERVATION_V1
CORPORATE_ACTION_OBSERVATION_V1
QFQ_AS_OF_ENGINE_V1
```

设计原则：

- 2F V1、V9、`AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1` 完全不变；
- V2 使用未来独立 ruleVersion、contextProfile 和 context Schema，具体名称在实施阶段
  冻结；
- 旧 contextHash、cache key、任务结果和观察事实不重算、不覆盖；
- 原始日线、复权因子、交易日历和公司行动是四类独立事实；
- 当前 QFQ 投影不能反推原始日线或历史因子；
- 所有 V2 事实 append-only；同内容连续重复幂等，内容变化追加，A→B→A 保留后一次 A；
- 不为历史记录回填伪造的首次观察、发布时间、revision 或 knowledge-time；
- 本阶段不选择迁移版本、表名、索引或部署方式。

## 8. 时间、版本与资格语义

### 8.1 公共时间字段

- `providerPublishedAt`：供应商明确声明该精确版本可获得的时间，可空；不得由更新时段、
  HTTP Date 或抓取时间推导。
- `firstObservedAt`：本系统第一次完整接收并验证该内容版本的真实时间。
- `knownAt`：该观察可进入指定资格 as-of 查询的最早时间。
- `recordedAt`：数据库提交记录时间，必须不早于本地接收时间。
- `knowledgeCutoff`：请求决策允许使用信息的上界。

`knownAt` 的来源：

| 资格 | `knownAt` |
|---|---|
| `PROVIDER_PIT_VERIFIED` | 经书面语义和旧版本样例验证的 `providerPublishedAt` |
| `SYSTEM_KNOWLEDGE_PIT` | `firstObservedAt`；不得更早 |

Provider 资格允许 `providerPublishedAt < firstObservedAt`，但只有完整历史版本和发布证据
都通过时才能使用；否则一律退回 `SYSTEM_KNOWLEDGE_PIT`，从本地首次捕获起可见。

### 8.2 revisionQualification

| 值 | 语义 |
|---|---|
| `PROVIDER_VERIFIED` | revision 字段通过第 4 节全部门禁 |
| `PROVIDER_UNVERIFIED` | 存在疑似字段，但缺少正式语义或更正实验 |
| `PROVIDER_UNAVAILABLE` | 公开响应/书面说明没有 revision 字段 |
| `SYSTEM_KNOWLEDGE_ONLY` | provider revision 为空或不合格，但真实首次捕获和 append-only 链可证明 |

当值为 `SYSTEM_KNOWLEDGE_ONLY` 时，`providerRevision` 必须为 `null`。未验证候选值不能
偷偷写入该字段。

### 8.3 本地与供应商版本分离

- `batchId`：本地捕获批次引用；
- `datasetVersion`：本地不可变捕获数据集身份；
- `providerDatasetVersion`：供应商明确提供时才保存，可空；
- `providerRevision`：只有供应商字段，资格不通过时为空；
- `canonicalContentHash`：语义内容 Hash；
- `observationVersion`：本地 append-only 观察链版本。

它们互不等价。`datasetVersion`、Hash 或 observationVersion 不得改名后冒充供应商版本。

## 9. 四类事实模型

### 9.1 RAW_DAILY_BAR_OBSERVATION_V2

至少保存：

- source instrument identity、`symbol`、`tradeDate`；
- 未复权 `open/high/low/close`；
- `volume`、`amount`、`turnoverRate`，缺失必须为 `null` 并携带字段资格，不能补零冒充；
- `sourceCode`、可空 `providerRevision`、可空 `providerPublishedAt`；
- `firstObservedAt`、`knownAt`、`recordedAt`；
- `canonicalContentHash`、`observationVersion`；
- `batchId`、本地 `datasetVersion`、可空 `providerDatasetVersion`；
- `revisionQualification`、许可/用途 qualification。

自然键是来源身份、证券身份和交易日；as-of 查询对同一自然键选择 cutoff 前最新可见
观察版本。OHLC 必须为正，high/low 关系、volume/amount/turnover 单位必须由 Provider
契约冻结。

### 9.2 ADJUSTMENT_FACTOR_OBSERVATION_V1

复权因子不得嵌入 raw bar。至少保存：

- source instrument identity、`symbol`；
- `factorEffectiveTradeDate`；
- 正且有限的十进制 `factor`；
- `factorType` 和明确基准语义；
- `factorCoverageMode=DAILY_EXACT`；
- Provider、可空 revision/published time；
- `firstObservedAt`、`knownAt`、`recordedAt`；
- canonical Hash、observation version、batch/dataset lineage；
- revision qualification。

V1 的因子覆盖模式固定为 `DAILY_EXACT`。因子精确自然键至少由 source code、稳定
source instrument identity、`factorType` 和 `factorEffectiveTradeDate` 组成；as-of
查询只能在该精确自然键上选择 `knownAt<=knowledgeCutoff` 的最新可见 append-only
观察版本。同一精确自然键的因子变化必须追加版本。

V1 不允许把较早因子向后填充，不允许用最近因子或当前最新因子补齐缺失交易日，不允许
从 QFQ 价格反推因子，也不允许跨 Provider 拼接因子。未来 Provider 如果只提供稀疏的
effective-from step 因子，必须建立独立 Provider 因子覆盖契约、证明生效区间语义，并
使用未来独立 engine 版本或资格；`QFQ_AS_OF_ENGINE_V1` 不得隐式推断。

### 9.3 TRADING_CALENDAR_OBSERVATION_V1

至少保存：

- `exchange`、`calendarDate`、`isOpen`、`session`；
- Provider 和来源日历身份；
- 可空 revision/published time；
- `firstObservedAt`、`knownAt`、`recordedAt`；
- canonical Hash、observation version、batch/dataset lineage；
- revision qualification。

临时休市、恢复交易或日历更正必须追加版本。周末规则只能是安全下限，不能替代正式
SSE/SZSE 日历及其 knowledge-time。

### 9.4 CORPORATE_ACTION_OBSERVATION_V1

V2 首版必须纳入公司行动事实。原因是单独的 `adj_factor` 数值可以重放计算，却不能解释
因子为何改变、何时公告、何时生效，以及变化属于真实公司行动还是来源更正。

至少覆盖：

- 现金分红；
- 送股；
- 转增；
- 配股；
- 拆股/并股；
- 登记日、除权除息日和生效日；
- 公告时间及精度；
- Provider event identity；
- revision、替换/撤回关系；
- `firstObservedAt`、`knownAt` 和完整 lineage。

当相邻有效交易日的可见因子发生变化，却没有 cutoff 前可见的匹配公司行动或经验证的
Provider 修订原因时，允许生成诊断结果，但可靠 V2 backtestContext 必须
`available=false`，不能静默解释。

## 10. Canonical 与 append-only 规则

`PIT_MARKET_FACTS_CANONICAL_V2` 沿用仓库已冻结的安全基础：

- SHA-256、小写十六进制、UTF-8、Unicode NFC；
- 对象字段名词典序，数组保持契约业务顺序；
- 日期 ISO-8601，时间 UTC `Z`、微秒精度；
- Decimal 使用普通十进制字符串、无科学计数法、去无意义尾零、`-0` 归一为 `0`；
- 缺失与显式 `null` 不等价；
- 禁止 NaN、Infinity；
- 数据库 ID、创建时间、线程/主机、日志和本地随机 UUID 不进入内容 Hash。

每类事实使用独立字段白名单。`canonicalContentHash` 包含来源身份、自然键、业务值、
合格的 Provider revision/published metadata 和资格标志，不包含本地记录时间。

append 规则：

1. 与当前链尾语义内容相同：不追加 observation；
2. 与链尾不同：追加 observation，并引用 predecessor；
3. A→B→A：后一次 A 因 predecessor 和 knowledge-time 不同形成新 observationVersion；
4. 不更新旧 observation；
5. as-of 只选择 `knownAt<=knowledgeCutoff` 的链上版本；
6. 并发相同捕获只能形成一个链尾；
7. 内容 Hash 相同不证明其在 cutoff 前已经可知。

## 11. QFQ_AS_OF_ENGINE_V1

### 11.1 输入选择

对请求 `(symbol, requestTradeDate, knowledgeCutoff)`：

1. 通过 cutoff 前可见的交易日历确定请求级 `requestEffectiveTradeDate`；
2. 只读取 `tradeDate<=requestEffectiveTradeDate` 且 `knownAt<=knowledgeCutoff` 的
   raw bars；
3. 每个 raw trade date 选择当时最新可见观察版本；
4. `anchorTradeDate` 固定为窗口内最后一根有效 raw bar 的交易日，并且必须等于
   `requestEffectiveTradeDate`；请求级日期不得与因子字段
   `factorEffectiveTradeDate` 混用；
5. 对每根 raw bar 日期 `t`，只在同 source instrument、同 source code、同
   `factorType` 且 `factorEffectiveTradeDate==t` 的精确自然键上，选择
   `knownAt<=knowledgeCutoff` 的最新可见 append-only 因子观察版本；
6. 锚点因子必须精确满足
   `factorEffectiveTradeDate==anchorTradeDate` 且在 cutoff 前可见；
7. 任一 bar 缺少精确日期因子时稳定返回 `PIT_FACTOR_UNAVAILABLE`，不产生部分 QFQ
   窗口，也不对剩余输入重新归一化；
8. 禁止较早因子向后填充、最近因子替代、当前最新因子补历史、从 QFQ 反推因子或跨
   Provider 拼接因子；
9. 任一 raw bar、calendar 或必要 corporate action lineage 缺失时安全不可用；
10. 完整记录所有输入 observationVersion、contentHash、source、qualification、因子
    覆盖模式和 cutoff。

### 11.2 冻结公式

对价格字段 `P in {open, high, low, close}`：

```text
qfqPrice(P, t, cutoff)
  = rawPrice(P, t, cutoff)
    * factor(t, cutoff)
    / factor(anchorTradeDate, cutoff)
```

该公式与 Tushare 文档描述一致，但计算只使用本系统 as-of 选择的 raw 和 factor，不调用
Provider 当前 QFQ 序列。

冻结数值规则：

- 所有输入先按十进制字符串构造 `BigDecimal`，禁止二进制 float 作为权威输入；
- factor 必须大于 0；
- 乘法保留完整精度；
- 除法使用 scale 16、`RoundingMode.HALF_UP`；
- 最终 OHLC 使用 scale 4、`RoundingMode.HALF_UP`，与现有 Bar 价格精度兼容；
- volume、amount、turnoverRate 不做价格复权；
- 最终再次验证 OHLC 关系；
- canonical Hash 同时覆盖未舍入输入 lineage、锚点、舍入契约和最终 scale 4 输出。

### 11.3 重放与权威

- Java 是未来生产 as-of 选择、QFQ 计算和 Hash 的唯一权威；
- Python 只验证固定黄金向量和解释 Java 冻结事实，不重新计算另一套行情；
- 同一输入 observation 集合和 cutoff 必须得到完全相同输出；
- 新因子或公司行动只生成新 knowledge-time 结果，不改写旧结果；
- 当前最新因子不得应用到早于其 `knownAt` 的历史决策；
- Provider 当前 QFQ 仅可用于非权威交叉校验，差异必须记录而不能覆盖结果。

## 12. V2 可靠性分类

### 12.1 PROVIDER_PIT_VERIFIED

必须同时具备：

- Provider revision/snapshot 正式语义；
- 每个版本的 provider publication/update time；
- 修订、撤回、替换关系；
- 旧版本可查询或由正式历史快照交付；
- raw、factor、calendar 和 corporate action 的同等时间/版本证据；
- 合同允许本地落库、历史回放、回测和内部 Agent 使用；
- 书面技术附件和样例黄金链验证通过。

任何一项缺失都不能使用该分类。

### 12.2 SYSTEM_KNOWLEDGE_PIT

允许 `providerRevision=null`，但必须：

- 使用真实首次捕获时间；
- `knownAt=firstObservedAt`，或更晚但有明确延迟原因；
- 只服务首次捕获之后的决策；
- append-only，内容变化追加；
- cutoff 前版本可重复查询和重放；
- 明确不保证供应商实际发布时间和首次公开时间；
- 明确不支持首次捕获前的历史决策；
- 原始/factor/calendar/action 的缺失不能用当前值补齐。

`SYSTEM_KNOWLEDGE_PIT` 是本系统的前向知识记录，不是供应商 PIT、FORMAL、历史全量或
旧 2F V1 的 source revision 替代品。

## 13. 黄金场景与预期

| # | 场景 | 冻结预期 |
|---:|---|---|
| 1 | D 日收盘后捕获 raw bar 和 factor | 从真实捕获时刻起可用于 system knowledge |
| 2 | D 日 cutoff 前没有捕获 | D 日决策不可用 |
| 3 | D+1 才捕获 D 日事实 | 不得回填成 D 日已知 |
| 4 | D+10 公司行动使 Provider 当前 QFQ 重写历史 | 保存新 factor/action 观察，不覆盖旧版本 |
| 5 | 重放 D 日 cutoff | 仍使用 D 日当时可见 factor 和旧 QFQ |
| 6 | D+10 cutoff | 使用新 factor 形成新 as-of 结果和 lineage |
| 7 | 同内容连续重复捕获 | 幂等，不追加 observation |
| 8 | 内容变化 | 追加新 observation，旧值保留 |
| 9 | providerRevision 为空且真实前向捕获 | 可为 `SYSTEM_KNOWLEDGE_PIT` |
| 10 | 把 UUID、Hash、version=18 伪装成 revision | 拒绝 |
| 11 | raw 有、factor 缺失 | 安全不足 |
| 12 | raw/factor 有、交易日历缺失 | 安全不足 |
| 13 | observation knownAt 晚于 cutoff | 排除；不得重新归一化剩余输入 |
| 14 | Java/Python 同时收到固定夹具 | Java 计算权威；Python 只验证，不形成第二套事实 |
| 15 | 因子变化但没有 action/revision 解释 | 诊断可见，可靠 context 不可用 |
| 16 | A→B→A 内容链 | 三个时点均可重放，后一次 A 不与第一次合并 |
| 17 | raw bar 日期 `t` 存在，但只有 `t` 之前的 factor | 不允许沿用旧 factor；返回 `PIT_FACTOR_UNAVAILABLE`；不产生部分 QFQ 窗口 |
| 18 | `t` 日精确 factor 存在，但 `knownAt` 晚于 cutoff | 排除该 factor；返回 `PIT_FACTOR_UNAVAILABLE`；不得使用更旧 factor 替代 |

固定实现测试必须把输入 JSON、canonical 文本、预期 Hash、QFQ 输出和 lineage 写成仓库
夹具；预期值不能由被测实现运行时生成。

## 14. 候选 reasonCode

后续实现需冻结 Java/Python 一致的稳定代码，至少区分：

- `PIT_RAW_DAILY_BAR_UNAVAILABLE`
- `PIT_FACTOR_UNAVAILABLE`
- `PIT_TRADING_CALENDAR_UNAVAILABLE`
- `PIT_CORPORATE_ACTION_LINEAGE_UNVERIFIED`
- `PIT_KNOWLEDGE_CUTOFF_UNSATISFIED`
- `PIT_PROVIDER_REVISION_UNVERIFIED`
- `PIT_PROVIDER_LICENSE_UNVERIFIED`
- `PIT_DATASET_COVERAGE_INCOMPLETE`
- `PIT_CANONICAL_HASH_MISMATCH`
- `PIT_QFQ_REPLAY_MISMATCH`

名称是 V2 设计的一部分，尚未加入生产枚举；实施阶段可以在不改变语义的前提下按仓库
命名规范统一。

## 15. 后续实施路线

路线图不构成自动授权。建议按以下大阶段推进：

### 15.1 3A-R3B：来源、许可与样例批准门

- 向 Tushare 和至少一家企业 Provider 发送书面问题清单；
- 取得本地落库、历史回放、回测和内部 Agent 使用权的书面答复；
- 取得 raw/factor/calendar/corporate action 及修订链样例；
- 对费用、调用限制、历史覆盖、字段单位和 update/publish 时间做现场核验；
- 只批准满足证据的用途，未批准项保持 `UNKNOWN`。

### 15.2 PIT_MARKET_FACTS_V2 实现大阶段

在获得明确授权后，一次完成：

- 新前向迁移版本选择与 append-only 数据库模型；
- Provider Adapter、捕获批次、四类观察事实和 canonical Hash；
- as-of Repository、QFQ engine、黄金向量、真实 PostgreSQL 和故障恢复；
- current projection 与可靠事实隔离；
- 来源切换/并存和旧数据兼容。

迁移编号、表名和生产 API 只有在该阶段安全门通过后冻结，本设计不预先声明。

### 15.3 独立 2F V2

- 新 ruleVersion/contextProfile/backtestContext Schema；
- 只读取 `PIT_MARKET_FACTS_V2` 输出；
- 保留 2F V1 全部行为、Hash 和缓存；
- 独立验证 SYSTEM_KNOWLEDGE 与 PROVIDER_PIT 资格；
- 完成 Java/Python、回测重放和 Shadow 兼容。

### 15.4 恢复 3A 观察

只有来源许可、V2 事实、2F V2 和运行门禁全部验收并合入后，才由用户另行授权 Day 002。
不得为追求正常决策结果而补造历史、放宽门禁或重跑 Day 001。

## 16. 设计验收门禁

后续实现开始前必须同时确认：

- 来源与用途获得书面许可；
- Provider 字段和单位有样例验证；
- revision 资格明确为 verified、unverified 或 unavailable；
- SYSTEM_KNOWLEDGE 的首次捕获时间可真实冻结；
- raw/factor/calendar/action 四类模型和 as-of 选择无重大分歧；
- QFQ 公式、锚点、精度和黄金向量获架构验收；
- 新迁移和公共契约获得用户明确授权；
- 2F V1 兼容测试方案完整；
- 不需要修改历史 V1 至 V12；
- 不需要伪造首次捕获前的历史 knowledge-time。

任一门禁不满足，生产实现不得开始。

## 17. 完成边界

3A-R3A 完成只表示：

- 当前 Provider revision 资格已经形成有证据的结论；
- 三类来源候选和书面询价问题已经明确；
- V2 四类事实、两类 PIT 资格和 QFQ as-of 算法已经冻结；
- 后续实施与验收门禁已经可执行。

它不表示来源获批、Provider 已接入、V2 数据库已存在、2F V2 已实现、Day 002 已运行、
长期 3A 已完成或 3B 已开始。
