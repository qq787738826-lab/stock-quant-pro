# 3A-R3B-F1B：Tushare 技术合同收敛与缩减研究路线冻结任务书

## 1. 阶段目标

本阶段一次性回答 Tushare 2000 积分路线能否满足完整 F1 四类事实合同，以及在完整合同
不能满足时，是否存在可安全落地的缩减个人研究路线。本阶段不调用 Provider、不检查
Token、不访问数据库、不执行 V13，也不启动全市场采集、scheduler、Shadow 或后续阶段。

冻结基线与任务分支：

```text
INTEGRATION_BASE=1ad039038829f6e752ce6ecea6da0a3d88882df7
TASK_BRANCH=codex/1.4.0-stage-3ar3b-f1b-tushare-technical-contract
TARGET_COMMIT_MESSAGE=feat(agent): freeze tushare technical qualification
```

## 2. 输入状态

```text
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=UNVERIFIED
USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER

F1_ENTRY_READINESS=BLOCKED_MULTIPLE
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE

V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL

FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

真实业务请求累计数固定为 `20`：B1 为 10，F1A 为 10。本阶段新增 Provider 调用必须
为 0。

## 3. 官方资料审计范围

访问日期统一为 `2026-07-30`。只使用 Tushare 官方页面；搜索摘要、博客、问答社区、
第三方 SDK 说明或字段猜测不得成为结论依据。

| evidenceId | 页面/API | 官方标识 | 积分要求 | 输入摘要 | 输出摘要 | 更新/单次上限 | 支持的结论 | 不支持的结论 |
|---|---|---|---|---|---|---|---|---|
| TS-004 | A 股日线行情 / `daily` | `doc_id=27` | 120 积分可取未复权日线；当前 2000 档覆盖 | `ts_code/trade_date/start_date/end_date` | `ts_code/trade_date/OHLC/pre_close/change/pct_chg/vol/amount` | 15—16 点入库；6000 行 | 未复权日线、成交量为手、成交额为千元、停牌日不返回 | 全历史 null/0 稳定、逐条 published/revision、历史旧版本 |
| TS-009 | 股票基础信息 / `stock_basic` | `doc_id=25` | 2000 积分 | `ts_code/name/market/list_status/exchange` | `ts_code/symbol/name/market/exchange/list_status/list_date/delist_date` 等 | 6000 行；更新说明未公开 | 当前普通证券身份、上市/退市字段 | 永久 instrument ID、换码/迁板/重上市连续性 |
| TS-018 | 上市公司基本信息 / `stock_company` | `doc_id=112` | 120 积分 | `ts_code/exchange` | `ts_code/com_name/com_id/exchange` 等 | 4500 行；更新说明未公开 | `com_id` 可作为发行主体辅助证据 | `com_id` 不是永久证券工具身份 |
| TS-019 | 股票曾用名 / `namechange` | `doc_id=100` | 页面未公开 | `ts_code/start_date/end_date` | `ts_code/name/start_date/end_date/ann_date/change_reason` | `OFFICIAL_EVIDENCE_UNAVAILABLE` | 证券名称历史证据 | 代码换码、迁板或重新上市连续性 |
| TS-020 | 股票历史列表 / `bak_basic` | `doc_id=262` | 正式权限 5000 积分 | `trade_date/ts_code` | `trade_date/ts_code/name/list_date` 等 | 2016 年起；7000 行 | 历史每日股票列表能力存在 | 当前 2000 积分不能取得完整正式权限，也不是永久身份映射 |
| TS-005 | 复权因子 / `adj_factor` | `doc_id=28` | 2000 积分 | `ts_code/trade_date/start_date/end_date` | `ts_code/trade_date/adj_factor` | 盘前 9:15—9:20；行上限未公开 | Tushare 自产独立因子、单证券全历史查询表面 | revision、旧版本、action ID 或 factor/action 解释关系 |
| TS-008 | A 股复权行情 / `pro_bar` | `doc_id=146` | 页面未单列积分 | `ts_code/start_date/end_date/adj/freq` | 动态计算结果 | SDK `>=1.2.26`；HTTP 不支持；行上限未公开 | QFQ 公式与请求结束日锚点语义 | 历史 QFQ 不可变、Provider revision 或完整 action lineage |
| TS-007 | 分红送股 / `dividend` | `doc_id=103` | 2000 积分 | `ts_code/ann_date/record_date/ex_date/imp_ann_date`，至少一项 | 现金、送股、转增及公告/登记/除权/支付/实施日期 | 更新说明和行上限未公开 | 现金分红、送股、转增的解释性部分证据 | 配股、拆并股、更正/撤回、稳定 action ID、revision 链 |
| TS-006 | 交易日历 / `trade_cal` | `doc_id=26` | 2000 积分 | `exchange/start_date/end_date/is_open` | `exchange/cal_date/is_open/pretrade_date` | 更新说明和行上限未公开 | SSE/SZSE 交易所级日历 | 历史修订、旧版本、逐条发布时间 |
| TS-003 | 积分与频次权限对应表 | `doc_id=290` | 2000 档 200 元/年 | 非 API | 权限/频次/总量 | 200 次/分钟、每 API 100000 次/日 | 当前档位技术调用额度 | 完整用途许可、SLA、历史版本 |
| TS-021 | 数据上新与变更动态（ChangeLog） | `doc_id=9` | 非 API | 非 API | 平台接口/字段/SDK 变更记录 | 按平台发布事件更新 | 平台接口和字段会演进 | 单条事实 revision、snapshot、旧版本查询 |

页面未公开的积分、更新时点或单次上限必须保留为
`OFFICIAL_EVIDENCE_UNAVAILABLE`，不得猜测。

## 4. 类型化判定模型

新增 `TushareTechnicalQualification`。权威路线决策只允许：

```text
FULL_F1_BUILDABLE
REDUCED_RESEARCH_ONLY
PROVIDER_ROUTE_REJECTED
```

最低资格状态只允许：

```text
VERIFIED
PARTIAL
UNAVAILABLE
UNVERIFIED
NOT_SUPPORTED
```

模型必须逐项持有 raw、factor、calendar、corporate action、revision、历史版本、证券
身份、QFQ、全历史 `DAILY_EXACT` 与 Provider PIT 资格。任何 `VERIFIED` 维度没有对应
证据 ID 时必须降为 `UNVERIFIED`，不得靠自由文本升级。

完整路线只有四类事实、完整 action 类型、稳定 action ID、factor/action 关系、
Provider revision、旧版本、永久证券身份、全历史 `DAILY_EXACT` 和 Provider PIT
全部 VERIFIED 才能成立。raw/factor/calendar 或安全边界不成立时必须拒绝 Provider
路线。

## 5. 冻结技术结论

证据驱动的当前结果为：

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
FULL_TECHNICAL_CONTRACT_READY=false
REDUCED_RESEARCH_CONTRACT_READY=true
```

核心状态：

| 维度 | 当前状态 | 解释 |
|---|---|---|
| raw daily | `VERIFIED` | 官方字段/单位加两证券两日真实最小样例；全历史稳定另行保留 |
| adjustment factor | `VERIFIED` | 官方独立因子加两证券两日同日因子样例 |
| SSE/SZSE calendar | `VERIFIED` | 官方交易所身份加两交易所最小样例 |
| corporate action | `PARTIAL` | `dividend` 仅现金/送股/转增解释证据 |
| revision | `NOT_SUPPORTED` | 核心字段没有逐记录 revision/snapshot |
| historical versions | `NOT_SUPPORTED` | 没有旧版本查询合同 |
| security identity | `PARTIAL` | 普通 `ts_code` 生命周期字段不等于永久工具身份 |
| research QFQ | `VERIFIED` | 官方公式、固定请求结束日锚点及本地确定性测试 |
| full-history DAILY_EXACT | `UNVERIFIED` | 仅两证券两日样例已验证 |
| Provider PIT | `NOT_SUPPORTED` | 只能从本系统首次真实捕获建立系统知识时间 |

当前 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL` 和
`STABLE_SECURITY_ID=PARTIAL` 不变。

## 6. QFQ 合同

研究级前复权只允许：

```text
qfqPrice = rawPrice
         × factorAtTradeDate
         ÷ factorAtRequestedEndDate
```

- 锚点是显式 `requestedEndDate` 的因子，不使用当前日期；
- 相同 raw、factor 和锚点必须产生相同四位小数结果；
- 锚点或任一交易日因子缺失时安全拒绝；
- 因子必须大于 0；
- raw 与 factor 必须同为 `TUSHARE_PRO`；
- `dividend` 不参与生成、修复或反推 factor；
- factor 继续是 `SYSTEM_KNOWLEDGE_ONLY`，不宣称历史版本稳定。

现有 `QfqAsOfEngine` 数学规则和 18 个黄金向量不得修改。

## 7. 身份与公司行动边界

```text
stock_basic = CURRENT_INSTRUMENT_IDENTITY_FIELDS
stock_company.com_id = ISSUER_IDENTITY_EVIDENCE
namechange = SECURITY_NAME_HISTORY_EVIDENCE
bak_basic@2000 = HISTORICAL_SECURITY_LIST_PERMISSION_INSUFFICIENT
```

公司行动逐项状态：

| 类型 | 状态 |
|---|---|
| `CASH_DIVIDEND` | `PARTIAL` |
| `STOCK_DIVIDEND` | `PARTIAL` |
| `CAPITALIZATION` | `PARTIAL` |
| `RIGHTS_ISSUE` | `NOT_SUPPORTED` |
| `SPLIT` | `NOT_SUPPORTED` |
| `REVERSE_SPLIT` | `NOT_SUPPORTED` |
| `CORRECTION` | `NOT_SUPPORTED` |
| `WITHDRAWAL` | `NOT_SUPPORTED` |

`stableActionId/revisionId/providerPublishedAt/providerUpdatedAt`、撤回标识、更正链和
factor/action 稳定解释关系均未获得官方字段证据。不得生成合成 ID 并冒充 Provider
事件 ID，也不得把 `dividend` 写入完整 V13 公司行动。

## 8. 缩减个人研究路线

允许：

- 显式 `MANUAL_BOUNDED` 手工有界调用；
- raw/factor/calendar；
- 请求结束日锚定的研究级 QFQ；
- 首次真实捕获后的 `SYSTEM_KNOWLEDGE_PIT`；
- `stock_basic` 普通身份；
- `dividend` 解释性部分证据；
- 随机隔离 Schema 与用户个人研究。

禁止：

- 完整公司行动 lineage；
- `PROVIDER_PIT_VERIFIED`；
- 历史 revision 回放；
- 永久证券身份；
- 跨 Provider QFQ；
- 正常业务库 V13；
- scheduler、全市场自动采集、Shadow、Day 002、F2B、F3；
- 投资建议、券商连接或交易。

Capability 必须继续明示：

```text
formalEligible=false
fullF1EntryReady=false
providerWrittenPermissionComplete=false
```

## 9. 验收

离线测试至少证明：

1. FULL 条件全部满足才可判 FULL；
2. 缺 action ID、revision 或永久身份时只能 REDUCED；
3. raw/factor/calendar 任一核心失败时 REJECTED；
4. capability 与类型化资格一致；
5. 缺证据 ID 不得维持 VERIFIED；
6. QFQ 结束日锚点、重复确定性、缺因子和非正因子安全拒绝；
7. dividend 不进入 QFQ，且不跨 Provider；
8. issuer identity 不等于 instrument identity；
9. ChangeLog 不等于单条数据 revision；
10. reduced route 不开放完整 FORMAL、scheduler 或业务库路径；
11. F1A、Provider V2 和 18 个 QFQ 黄金向量不回退。

## 10. 阶段后状态

完整 F1 继续保持：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE
```

缩减合同 READY 只表示下一阶段可以在用户独立授权后规划“受控本地研究摄取实现”；
本任务本身不授权该阶段，不授权正常业务库或任何自动采集。
