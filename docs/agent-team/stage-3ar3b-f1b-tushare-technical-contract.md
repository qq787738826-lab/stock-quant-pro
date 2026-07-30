# 3A-R3B-F1B：Tushare 技术合同收敛与缩减研究路线冻结阶段记录

## 1. Git 与阶段边界

```text
INTEGRATION_BRANCH=feature/1.4.0-agent-team
INTEGRATION_BASE=1ad039038829f6e752ce6ecea6da0a3d88882df7
TASK_BRANCH=codex/1.4.0-stage-3ar3b-f1b-tushare-technical-contract
TARGET_COMMIT_MESSAGE=feat(agent): freeze tushare technical qualification
```

开始时本地/远程集成 HEAD 精确等于冻结基线，ahead/behind 为 `0/0`，已跟踪工作区
干净、暂存区为空；`.ai/` 只通过 Git 状态确认未跟踪。本阶段没有读取或检查
`TUSHARE_TOKEN`，没有调用 Provider，没有访问数据库或执行 V13。

F1A 三提交链：

```text
fc076821173407205e838b9512ee3a97e7afd3a6
e588e08533ddeefc161266eaf93a12e128748a47
1ad039038829f6e752ce6ecea6da0a3d88882df7
```

已经通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入；F1B
基于最终 F1A 集成提交实施。

## 2. 官方证据复核

访问日期为 `2026-07-30`。实际审计了 Tushare 官方 `stock_basic`、`stock_company`、
`namechange`、`bak_basic`、`adj_factor`、`pro_bar`、`dividend`、`trade_cal`、积分与
频次权限页和官方 ChangeLog，并复用既有官方 `daily` 数据字典。详细输入/输出、积分、
更新/行限和不支持结论见
[F1B 任务书](tasks/3ar3b-f1b-tushare-technical-contract.md)与
[Track B 官方证据登记册](track-b-provider-evidence-register.md)。

官方证据新增：

| evidenceId | 官方页面 | 关键结论 |
|---|---|---|
| `TS-018` | `stock_company`, `doc_id=112` | `com_id` 是发行主体辅助证据，不是永久证券工具身份 |
| `TS-019` | `namechange`, `doc_id=100` | 只能证明名称历史，不能证明代码/上市生命周期连续 |
| `TS-020` | `bak_basic`, `doc_id=262` | 正式权限要求 5000 积分；2000 积分不足以取得完整历史列表 |
| `TS-021` | ChangeLog, `doc_id=9` | 证明平台接口/字段演进，不证明单条事实 revision 或旧版本 |

未使用博客、论坛、问答社区、搜索摘要正文或第三方 SDK 说明。页面没有公开的积分、
更新时间或单次行限均保留为 `OFFICIAL_EVIDENCE_UNAVAILABLE`。

## 3. 类型化技术资格

新增 `TushareTechnicalQualification`，路线只允许：

```text
FULL_F1_BUILDABLE
REDUCED_RESEARCH_ONLY
PROVIDER_ROUTE_REJECTED
```

模型逐项持有 raw、factor、calendar、corporate action、revision、历史版本、身份、
QFQ、全历史 `DAILY_EXACT` 与 Provider PIT 资格，并把证据 ID 和技术 blocker 绑定到
判定。任何声明为 `VERIFIED` 的维度缺少该维度证据 ID 时自动降为 `UNVERIFIED`。

当前证据生成的正式判定为：

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
FULL_TECHNICAL_CONTRACT_READY=false
REDUCED_RESEARCH_CONTRACT_READY=true
```

不是预先写死路线：测试分别证明全条件满足才能进入 FULL，缺 action ID/revision/永久
身份时只能进入 REDUCED，raw/factor/calendar 任一核心不可用时进入 REJECTED。

## 4. Capability 投影

`TushareMarketFactProvider.capability()` 继续保留既有有限个人许可、进程内限流和五
Endpoint 事实，同时从类型化模型投影：

```text
fullTechnicalContractReady=false
reducedResearchContractReady=true
tushareReducedResearchContract=READY
technicalRouteDecision=REDUCED_RESEARCH_ONLY
qfqCalculationMode=RAW_FACTOR_END_DATE_ANCHORED
qfqAnchorSemantics=REQUESTED_END_DATE_FACTOR
corporateActionLineageComplete=false
permanentSecurityIdentityVerified=false
providerRevisionAvailable=false
historicalVersionsQueryable=false
fullHistoryDailyExactQualification=UNVERIFIED
providerPitQualification=NOT_SUPPORTED
forwardSystemKnowledgePitBuildable=true
```

还明确：

```text
stockCompanyIdentityUse=ISSUER_IDENTITY_EVIDENCE
namechangeUse=SECURITY_NAME_HISTORY_EVIDENCE
historicalSecurityList=HISTORICAL_SECURITY_LIST_PERMISSION_INSUFFICIENT
```

以下既有安全字段没有改变：

```text
formalEligible=false
fullF1EntryReady=false
providerWrittenPermissionComplete=false
```

## 5. QFQ 冻结结果

缩减路线使用和现有 Java QFQ 数学规则相同的确定公式：

```text
qfqPrice =
    rawPrice
    × factorAtTradeDate
    ÷ factorAtRequestedEndDate
```

结束日锚点由请求显式提供；相同 raw/factor/anchor 重复结果一致，换锚点只按公式改变。
锚点缺失、任一交易日因子缺失、因子非正数或 raw/factor 跨 Provider 时均安全拒绝。
计算接口不接受 `DividendEvidence`，因此 `dividend` 不可能被用于生成或修复因子。现有
`QfqAsOfEngine` 数学规则没有修改，18 个黄金向量继续回归。

## 6. 公司行动、版本与身份

公司行动：

```text
CASH_DIVIDEND=PARTIAL
STOCK_DIVIDEND=PARTIAL
CAPITALIZATION=PARTIAL
RIGHTS_ISSUE=NOT_SUPPORTED
SPLIT=NOT_SUPPORTED
REVERSE_SPLIT=NOT_SUPPORTED
CORRECTION=NOT_SUPPORTED
WITHDRAWAL=NOT_SUPPORTED
```

`stableActionId`、`revisionId`、逐条 `providerPublishedAt/providerUpdatedAt`、撤回标识、
更正链和 factor/action 稳定解释关系没有官方字段证据，故
`corporateActionLineageComplete=false`。不生成合成 Provider action ID，
`dividend` 继续是 `PARTIAL_DIVIDEND_EVIDENCE`，不进入完整 V13 公司行动。

版本与 PIT：

```text
PROVIDER_REVISION_AVAILABLE=false
HISTORICAL_VERSIONS_QUERYABLE=false
PIT_PARTIAL
```

官方 ChangeLog 只表示接口/字段发生变更，不能充当单条历史数据 revision。本地首次
观察只支持前向 `SYSTEM_KNOWLEDGE_PIT`，不支持 `PROVIDER_PIT_VERIFIED`。

身份：

- `stock_basic` 只提供当前普通证券字段；
- `stock_company.com_id` 只作为 `ISSUER_IDENTITY_EVIDENCE`；
- `namechange` 只作为 `SECURITY_NAME_HISTORY_EVIDENCE`；
- `bak_basic` 正式权限要求 5000 积分，当前 2000 积分记录为
  `HISTORICAL_SECURITY_LIST_PERMISSION_INSUFFICIENT`。

因此 `STABLE_SECURITY_ID=PARTIAL` 和
`permanentSecurityIdentityVerified=false` 保持不变。

## 7. 缩减路线边界

允许：

- 显式 `MANUAL_BOUNDED` 手工有界调用；
- raw/factor/calendar；
- 结束日锚定的研究级 QFQ；
- 首次真实捕获后的 `SYSTEM_KNOWLEDGE_PIT`；
- `stock_basic` 普通身份和 `dividend` 解释性部分证据；
- 随机隔离 Schema 与用户个人研究。

禁止：

- 完整公司行动 lineage、Provider PIT、历史 revision 回放和永久证券身份；
- 跨 Provider QFQ、正常业务库 V13、scheduler 和全市场自动采集；
- Shadow、Day 002、F2B、F3、3A-R3B-1、3B；
- 投资建议、券商连接、真实或自动交易。

下一阶段“受控本地研究摄取实现”只有在 F1B 实际 Git 提交验收、用户批准合入及用户
单独授权后才可开始；F1B 本身不授权。

## 8. 状态保持

```text
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

书面许可状态没有升级：本地长期存储、回测和内部 Agent 三项仍为 `UNVERIFIED`。
完整技术阻断继续包括公司行动 lineage、Provider revision/历史版本、永久证券身份和
全历史 `DAILY_EXACT`。

## 9. 验证结果

所有测试均离线执行；不运行 PostgreSQL 测试，因为本阶段未修改持久化代码。

| 验证项 | 结果 |
|---|---|
| Java 编译 | `mvn -pl quant-server -am -DskipTests test-compile`，`BUILD SUCCESS` |
| F1B 定向测试 | `21/0/0/0` |
| F1A、F1B、Provider V2 与 QFQ 联合回归 | `62/0/0/0` |
| Provider V2 与 18 个 QFQ 黄金向量 | `10/0/0/0` 与 `18/0/0/0` |
| quant-core 全量 | `4/0/0/0` |
| quant-server 安全全量 | `466/0/0/93`；93 项为外部 PostgreSQL/Python/AKShare 环境门禁跳过，Tushare Live 类被显式排除 |
| Markdown / UTF-8 / 表格 / 链接 / `git diff --check` | 全部通过 |

本阶段 Provider 新增调用数为 `0`，Tushare 累计真实业务请求继续为 `20`，iFinD
调用数为 `0`。Token 未读取、检查、记录或输出；数据库未访问，正常业务库 V13 未执行。
