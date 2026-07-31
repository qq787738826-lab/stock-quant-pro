# Track B Provider 候选统一资格矩阵

## 1. 范围与状态词

- 官方资料调查日期：`2026-07-29`
- Tushare 受控权限探针日期：`2026-07-30`
- Tushare 探针执行时刻：`PROBE_EXECUTION_TIME=UNKNOWN`
- B1 治理复核日期：`2026-07-30`
- Tushare 量化数据来源书面证据与 F1A 受控联调日期：`2026-07-30`
- Tushare F1B 官方技术合同复核日期：`2026-07-30`
- Tushare F1C 本地实现与随机隔离验证日期：`2026-07-30`
- Tushare `TS-WP-002` 七项书面回复转录接收时间：`2026-07-31T11:07:00+08:00`
- Tushare F1E 专用本地研究实现日期：`2026-07-31`
- 候选数量：精确为 3 个：BaoStock、Tushare Pro、同花顺 iFinD。
- 本矩阵只使用 [Track B 证据登记册](track-b-provider-evidence-register.md) 中的官方资料、已经验收的 F0 直接 Provider 探针事实、B1 固定范围 Tushare 受控权限探针事实、
  Tushare 官方企业微信脱敏书面转录、F1A 固定范围受控联调事实和 F1B 官方
  `stock_company/namechange/bak_basic/ChangeLog` 证据。
- 不以搜索摘要、博客、论坛、第三方 GitHub、代理报价或未确认身份的聊天记录支撑结论。
- “没有写禁止”不等于允许；许可不明确时使用 `PENDING_WRITTEN_CONFIRMATION` 或 `UNVERIFIED`。

法律与用途状态只允许：

`VERIFIED_ALLOWED`、`VERIFIED_RESTRICTED`、`VERIFIED_NOT_ALLOWED`、`PENDING_WRITTEN_CONFIRMATION`、`UNVERIFIED`。

技术能力状态只允许：

`VERIFIED`、`PARTIAL`、`UNVERIFIED`、`NOT_SUPPORTED`、`REQUIRES_TRIAL_PROBE`。

## 2. 法律与用途

| 维度 | BaoStock | Tushare Pro | 同花顺 iFinD |
|---|---|---|---|
| 个人研究调用 | `PENDING_WRITTEN_CONFIRMATION`：无需注册和免费可用不等于取得具体数据用途许可（BS-001/002） | `VERIFIED_RESTRICTED`：TS-WP-001/002 适用于个人 Tushare Pro 2000 积分账号；不得转让、共享账号、分发或商业化原始数据 | `PENDING_WRITTEN_CONFIRMATION`：免费/试用/正式额度公开，但个人研究授权范围未公开（IF-001/002） |
| 本地持久化 | `PENDING_WRITTEN_CONFIRMATION`（BS-002） | `VERIFIED_ALLOWED`：TS-WP-002 明确“本地数据库保存：允许” | `PENDING_WRITTEN_CONFIRMATION`：接口可导出数据不等于长期落库权利（IF-003/004） |
| 历史回放与回测 | `PENDING_WRITTEN_CONFIRMATION`（BS-002） | `VERIFIED_ALLOWED`：TS-WP-002 明确“策略回测/历史回放：允许” | `PENDING_WRITTEN_CONFIRMATION`：历史行情技术能力公开，回测授权未公开（IF-002/004） |
| 内部智能体分析 | `PENDING_WRITTEN_CONFIRMATION`（BS-002） | `VERIFIED_ALLOWED`：TS-WP-002 明确“本地AI或智能体分析：允许” | `PENDING_WRITTEN_CONFIRMATION`：产品支持编程分析，内部 Agent 用途未公开授权（IF-002/003） |
| 长期保存 | `PENDING_WRITTEN_CONFIRMATION` | `VERIFIED_ALLOWED`：TS-WP-002 明确“可以一直保存到本地” | `PENDING_WRITTEN_CONFIRMATION` |
| 内部衍生指标 | `PENDING_WRITTEN_CONFIRMATION` | `PENDING_WRITTEN_CONFIRMATION` | `PENDING_WRITTEN_CONFIRMATION` |
| 个人自建系统展示 | `PENDING_WRITTEN_CONFIRMATION` | `PENDING_WRITTEN_CONFIRMATION` | `PENDING_WRITTEN_CONFIRMATION` |
| 二次分发限制 | `VERIFIED_RESTRICTED`：官网内容未经书面许可不得复制、传播或商业使用；API 数据适用范围仍需确认（BS-002） | `VERIFIED_RESTRICTED`：许可不可转让且仅限非商业个人使用（TS-002） | `PENDING_WRITTEN_CONFIRMATION`：数据接口专项授权和再分发限制需合同确认 |
| 是否要求企业身份 | `UNVERIFIED`：匿名 API 可用不能证明正式授权无需身份 | `VERIFIED_ALLOWED`：官方公布个人价格，机构另按个人价 10 倍；技术接入不要求企业身份（TS-003） | `UNVERIFIED`：公开试用入口要求填写公司信息，个人购买资格未明确（IF-006） |
| 是否需单独协议 | `PENDING_WRITTEN_CONFIRMATION` | `VERIFIED_RESTRICTED`：付费即受数据服务协议约束，独立权限接口另行开通（TS-002/003） | `PENDING_WRITTEN_CONFIRMATION`：试用与正式合同内容未公开 |
| 服务终止后数据处理 | `UNVERIFIED` | `VERIFIED_RESTRICTED`：TS-WP-002 允许持续本地保存；不扩张为再分发、商业数据服务或账号共享 | `UNVERIFIED` |
| 免费/积分/试用/付费差异 | `UNVERIFIED` | `VERIFIED_RESTRICTED`：120/2000/5000 等积分与独立接口权限不同，个人与机构价格不同（TS-003） | `VERIFIED_RESTRICTED`：免费、试用、正式账号的额度和历史范围不同（IF-001/002） |

法律结论：

- BaoStock：`PENDING_WRITTEN_CONFIRMATION`。免费访问和客户端许可不能替代底层数据用途许可。
- Tushare Pro：当前个人研究用途为 `VERIFIED_RESTRICTED`。TS-WP-001 支持量化数据
  来源，TS-WP-002 逐项支持个人 2000 积分账号本地保存、回测/回放、Agent、自动更新、
  技术审计元数据留存和持续本地保存。再分发、商业数据服务及 Token/账号共享为
  `NOT_GRANTED`；书面许可 PASS 不等于完整技术合同或生产运行 READY。
- iFinD：`PENDING_WRITTEN_CONFIRMATION`。专项 API 合同、个人购买、本地保存、试用留存和内部 Agent 权利均需正式报价/合同确认。

## 3. 技术事实能力

| 技术维度 | BaoStock | Tushare Pro | 同花顺 iFinD |
|---|---|---|---|
| 1. 日线原始行情 | `VERIFIED`：F0 直接探针观察到未复权日线；终态完整性仍为 `UNVERIFIED`（BS-003/005） | `VERIFIED`：官方文档明确 `daily` 为未复权并给出单位；B1 两证券、两交易日最小样例各返回 2 行（TS-004、TS-PB-005/006） | `VERIFIED`：官方 HTTP 历史行情示例公开 OHLC（IF-004） |
| 2. 前复权行情 | `VERIFIED`：公开 API 与 F0 探针均存在 QFQ（BS-003/005） | `VERIFIED`：`pro_bar` 动态计算 QFQ（TS-008） | `PARTIAL`：官方 FAQ 描述复权算法，但实际指标需账号内工具确认（IF-002） |
| 3. 独立复权因子 | `PARTIAL`：公开因子语义存在，F0 两个证券短区间查询均为 0 行（BS-004/005） | `VERIFIED`：`adj_factor(ts_code,trade_date,adj_factor)` 按交易日返回；B1 两证券、两交易日均返回同日因子，最小 `DAILY_EXACT` 样例通过（TS-005、TS-PB-007/008） | `PARTIAL`：公开 FAQ 说明累计复权因子，字段名、精度和取数函数需试用确认（IF-002） |
| 4. 公司行动 | `PARTIAL`：分红/送转入口与一次 F0 观察存在，稳定事件 ID、修订关系不明（BS-003/005） | `PARTIAL`：B1 证明两证券 `dividend` 可调用并返回公开字段，但配股、拆并股、更正/撤回、稳定事件 ID、factor 解释关系和修订链仍未证明（TS-007、TS-PB-009/010） | `REQUIRES_TRIAL_PROBE`：基础数据可覆盖公司资料/重组，但 V13 所需事件函数与字段未公开列全（IF-003） |
| 5. 除权除息 | `PARTIAL`（BS-004/005） | `VERIFIED`：`record_date/ex_date/imp_ann_date` 等字段公开（TS-007） | `PARTIAL`：FAQ 说明除权日维护因子，具体事件字段需探针（IF-002） |
| 6. 停复牌 | `PARTIAL`：历史行情含交易状态，但独立事件能力未充分证明 | `VERIFIED`：`suspend_d`（TS-010） | `REQUIRES_TRIAL_PROBE` |
| 7. 上市/退市状态 | `PARTIAL` | `VERIFIED`：`stock_basic` 提供且 B1 实样返回 `list_status/list_date/delist_date`（TS-009、TS-PB-001/002） | `PARTIAL`：公开文档说明可取在市/退市代码列表，时态字段需探针（IF-002） |
| 8. ST 状态 | `VERIFIED`：历史行情含 `isST`（BS-003） | `VERIFIED`：`stock_st` 提供逐交易日历史，但需 3000 积分（TS-011） | `REQUIRES_TRIAL_PROBE` |
| 9. 精确交易日历 | `PARTIAL`：通用日历缺交易所身份（BS-005） | `VERIFIED`：`trade_cal` 显式区分 SSE/SZSE；B1 两个交易所最小样例各返回 5 个日历日（TS-006、TS-PB-003/004） | `VERIFIED`：官方 HTTP 示例按 marketCode 查询交易日（IF-004） |
| 10. 指数成分 | `PARTIAL`：只见有限指数集合 | `VERIFIED`：`index_weight`（TS-013） | `VERIFIED`：官方数据池/板块成分示例（IF-003/004） |
| 11. 指数行情 | `PARTIAL` | `VERIFIED`：`index_daily`（TS-012） | `VERIFIED`：历史行情/日期序列支持指数（IF-002/003） |
| 12. 公告元数据 | `NOT_SUPPORTED` | `VERIFIED`：独立公告权限包含标题和 PDF 链接（TS-003） | `VERIFIED`：`report_query` 返回日期、标题、ctime、PDF URL、seq（IF-003/004） |
| 13. 公告正文或 PDF | `NOT_SUPPORTED` | `VERIFIED`：独立公告权限，需另购（TS-003） | `VERIFIED`：公告查询与下载额度公开（IF-001/004） |
| 14. 稳定证券 ID | `PARTIAL`：`sh.600000` 等身份稳定性未有合同/版本保证 | `PARTIAL`：B1 验证 `ts_code`、交易所、上市/退市普通字段；F1B 进一步确认 `stock_company.com_id` 只能作发行主体证据，`namechange` 只能作名称历史，`bak_basic` 正式权限要求 5000 积分。永久 instrument identity、换码、迁板、重新上市及历史映射仍没有官方保证或样例（TS-009/018/019/020、TS-PB-001/002） | `PARTIAL`：`thscode` 跨接口公开，但生命周期/换码语义需探针（IF-003/004） |
| 15. Provider revision/version | `NOT_SUPPORTED` | `NOT_SUPPORTED`：公开核心字段没有 revision/snapshot | `REQUIRES_TRIAL_PROBE` |
| 16. published time | `NOT_SUPPORTED` | `PARTIAL`：有接口更新时点和公告日，不等于逐版本 publishedAt（TS-004/005/007） | `REQUIRES_TRIAL_PROBE`：FAQ 有总体入库时点，不等于逐事实 publishedAt（IF-002） |
| 17. effective time | `PARTIAL`：交易日/除权日存在，语义链不完整 | `PARTIAL`：trade/ex/record/implementation dates 存在，统一事件有效时点需映射验证（TS-005/007） | `REQUIRES_TRIAL_PROBE` |
| 18. 历史修订识别 | `NOT_SUPPORTED` | `NOT_SUPPORTED`：F1B 复核核心 API 字段与官方 ChangeLog；ChangeLog 只证明接口/字段演进，不是单条数据 revision，也没有旧版本查询（TS-021） | `REQUIRES_TRIAL_PROBE` |
| 19. 分页和增量 | `PARTIAL`：游标结果存在，稳定增量合同不明 | `VERIFIED`：日期/证券参数、行限和全日抓取模式明确（TS-004/005/006） | `PARTIAL`：函数和数据量限制公开，增量游标语义需确认（IF-001/003） |
| 20. 全市场批量 | `PARTIAL`：F0 禁止执行全市场探针 | `VERIFIED`：按交易日可获取全市场日线/因子，股票基础信息单次覆盖全市场（TS-004/005/009） | `VERIFIED`：板块成分与多代码接口公开（IF-003/004） |
| 21. 调用频率 | `UNVERIFIED` | `PARTIAL`：套餐总表为 200 次/分钟；接口页另列 `stock_basic=50`、`daily=500` 次/分钟。F1C 已按多个适用上限的保守较小值实施单进程 Endpoint 级限流；跨进程协调仍未实现（TS-003/004/009） | `VERIFIED`：单函数 QPS 10、账号总 QPS 20（IF-002） |
| 22. 每日/周期额度 | `UNVERIFIED` | `VERIFIED`：积分等级对应每日额度（TS-003） | `VERIFIED`：免费按月、试用/正式按周额度（IF-001） |
| 23. 错误码 | `VERIFIED`：结果对象暴露 `error_code/error_msg`（BS-003/005） | `VERIFIED`：HTTP `code/msg/data`，2002 为权限问题（TS-014） | `VERIFIED`：`errorcode/errmsg`，公开部分登录与网络错误码（IF-002/003） |
| 24. SLA/稳定性承诺 | `NOT_SUPPORTED`：免责声明明确不保证不中断（BS-002） | `NOT_SUPPORTED`：服务协议不保证准确、完整和及时（TS-002） | `UNVERIFIED` |
| 25. Python/Java 接入 | `PARTIAL`：公开 Python 客户端；Java 公共接入未证明 | `VERIFIED`：Python SDK 与通用 HTTP，可由 Java 调用（TS-014/015） | `VERIFIED`：Python、Java、HTTP 等多语言接口公开（IF-002/003/005） |

## 4. V13/QFQ 与 PIT

| 候选 | V13/QFQ 状态 | 依据 | PIT 状态 | 依据 |
|---|---|---|---|---|
| BaoStock | `V13_LINEAGE_BLOCKED` | raw 可用，但独立因子结果、`DAILY_EXACT`、交易所日历身份、公司行动版本和用途许可均未满足；禁止跨来源补齐 | `PIT_PARTIAL` | 技术上可在获准后从真实首次捕获建立系统知识链，但当前本地保存/回放/Agent 权利未确认，也无 Provider revision |
| Tushare Pro | `V13_LINEAGE_PARTIAL` | B1 已验证两证券、两日 raw/factor `DAILY_EXACT`、SSE/SZSE calendar、普通证券身份和 dividend 字段；F1B 确认 2000 积分路线只达到 `REDUCED_RESEARCH_ONLY`。公司行动缺稳定事件 ID、配股/拆并股/更正/撤回及 factor 解释关系，永久证券身份和全历史 `DAILY_EXACT` 未闭合；当前个人研究书面许可已闭环，但不替代技术证据 | `PIT_PARTIAL` | F1A/F1C 只验证首次捕获后的隔离 `SYSTEM_KNOWLEDGE_ONLY` 路径；核心字段无 Provider revision、snapshot 或旧版本查询，ChangeLog 不能替代单条数据版本；不得升级为 Provider PIT |
| 同花顺 iFinD | `V13_LINEAGE_UNVERIFIED` | 公共文档证明接口广度，但核心指标名、字段、四类事实是否同一授权、身份及事件关系只能在试用/书面材料中验证 | `PIT_UNVERIFIED` | 更新时点与复权语义有公开说明，但 revision/snapshot/published/effective/旧版本及留存权利均需试用和合同证据 |

任何候选都未达到 `V13_LINEAGE_READY` 或 `PROVIDER_PIT_READY`。

### 4.1 Tushare F1B 合同与 F1C 隔离运行路线

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
FULL_TECHNICAL_CONTRACT_READY=false
REDUCED_RESEARCH_CONTRACT_READY=true
QFQ_FORMULA_QUALIFICATION=VERIFIED
QFQ_OPERATIONAL_RUNTIME_QUALIFICATION=PARTIAL
QFQ_REDUCED_RESEARCH_RUNTIME_QUALIFICATION=VERIFIED
QFQ_FULL_LINEAGE_RUNTIME_QUALIFICATION=PARTIAL
REDUCED_RESEARCH_RUNTIME_READY=false
REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true
REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true
REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true
REDUCED_RESEARCH_OPERATIONAL_READY=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
QFQ_OPERATIONAL_BLOCKER=EXISTING_QFQ_ENGINE_REQUIRES_CORPORATE_ACTION_LINEAGE
OFFICIAL_ENDPOINT_RATE_LIMITS=PARTIAL_CONFLICT_IDENTIFIED
ENDPOINT_SPECIFIC_RATE_LIMIT_ENFORCED=true
CONSERVATIVE_ENDPOINT_MINIMUM_POLICY_ENFORCED=true
PROVIDER_REVISION_AVAILABLE=false
HISTORICAL_VERSIONS_QUERYABLE=false
```

F1B 缩减合同定义同 Provider raw/factor/calendar、请求结束日锚定的研究级 QFQ，以及
真实首次捕获后的 `SYSTEM_KNOWLEDGE_PIT`。F1C 只实现随机隔离的单证券、两自然日、
`daily/adj_factor/trade_cal` 三请求、零重试手工入口；公式级 QFQ 只在内存返回。
F1E 进一步实现本机专用 `stock_quant_research/tushare_research` 目标的单日 1—3
证券原子批次，但当前只把实现和受控验收合同设为 ready，operational 与 production
仍为 false。
现有 `QfqAsOfEngine` 在 factor 变化时继续要求公司行动 lineage。官方总表与 Endpoint
页面频次值冲突仍为 `PARTIAL_CONFLICT_IDENTIFIED`，但实现已按所有适用上限的较小值
执行单进程 Endpoint 级限制；跨进程协调仍不存在。`dividend` 只作
`PARTIAL_DIVIDEND_EVIDENCE`，不进入 F1C 入口或完整公司行动 lineage；不允许
Provider PIT、历史 revision 回放、永久证券身份、跨 Provider QFQ、正常业务库、
scheduler 或全市场自动采集。

FULL 判定使用每一种公司行动的独立 `TechnicalClaim(status,evidenceIds)`，并单独要求
stable action ID、factor/action、revision、历史版本、永久身份与 Provider PIT 证据。
一个泛化公司行动 evidenceId 或任何无证据裸布尔值均不得升级完整资格。

公司行动逐项状态固定为：

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

B1 的 `TUSHARE_2000_PERMISSION_PROBE=PASS` 只升级最小技术样例；完整 Track B
证据探针仍为 `PARTIAL_NOT_COMPLETE`。TS-WP-001/002 已在后续时点闭环当前个人研究
书面许可，但不回溯满足 B1 执行前的完整证据探针法律前置，也不解决公司行动、版本和
永久身份技术缺口。

## 5. 加权评分

评分范围为 0—5。总分只用于排序，硬性许可/lineage 门禁优先于总分。

| 候选 | 法律与用途 30% | V13/QFQ 25% | PIT/版本 15% | 覆盖/稳定 15% | 个人成本 10% | 接入复杂度 5% | 加权总分 |
|---|---:|---:|---:|---:|---:|---:|---:|
| BaoStock | 1.0 | 1.5 | 1.5 | 2.5 | 5.0 | 4.0 | **1.98** |
| Tushare Pro | 4.0 | 3.5 | 2.0 | 3.5 | 4.5 | 4.0 | **3.55** |
| 同花顺 iFinD | 1.0 | 2.5 | 2.5 | 4.0 | 1.0 | 2.0 | **2.10** |

评分解释：

- Tushare Pro 当前计算过程：`4.0×30% + 3.5×25% + 2.0×15% + 3.5×15% + 4.5×10% + 4.0×5% = 1.200 + 0.875 + 0.300 + 0.525 + 0.450 + 0.200 = 3.550`。TS-WP-001/002 已闭环个人研究用途，因仍禁止再分发、商业数据服务和 Token/账号共享，法律项不记满分。PIT/版本仍为 2.0，覆盖/稳定仍为 3.5。
- 调整后排名仍为 Tushare Pro、iFinD、BaoStock。Tushare Pro 不是因为需要维持主路线而反向调分，而是重新计算后仍以核心四事实、交易所日历、逐日因子、个人公开价格和 HTTP 接入形成最短的可验证闭环。
- Tushare Pro 的个人研究书面许可已闭环；完整 F1 仍被公司行动完整性、版本语义、
  永久身份、全历史 `DAILY_EXACT` 与完整 QFQ lineage 技术证据阻断。
- iFinD 作为备用是因为其专业数据和多语言接口上限高于 BaoStock，但必须先取得报价、合同和试用字段证据；当前不启动试用。
- BaoStock 免费但许可与核心 `DAILY_EXACT` 证据缺口会持续拖慢完整 V13/QFQ，保留为研究辅助，不作为正式备用路线。

## 6. 强制路线结论

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
BLOCKED_TECHNICAL_EVIDENCE
```

主要路线具体指 Tushare Pro。用户已开通 2000 积分、B1 技术权限探针通过，且
TS-WP-001 已书面确认可作为量化数据来源，TS-WP-002 已闭环当前个人研究书面许可；用户另行固定
`USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED`、
`F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER`，F1A 可实现缩小的真实
Adapter，但 capability 必须同时声明完整 F1 未就绪、书面许可门 PASS 且技术证据门
BLOCKED。生产、正常业务库、scheduler、Agent/回测、F2B/F3 仍不就绪。备用路线仍为
iFinD，仅在剩余技术合同不能满足后续完整
F1 时再进入专业付费路线决策；它不表示现在启动 15 天试用。
