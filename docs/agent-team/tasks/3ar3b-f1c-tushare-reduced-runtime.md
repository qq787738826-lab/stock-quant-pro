# 3A-R3B-F1C：Tushare Endpoint 级限流与随机隔离缩减研究运行入口任务书

## 1. 阶段目标

本阶段在 F1B 已冻结的 `REDUCED_RESEARCH_ONLY` 技术合同上完成两项生产 Java
能力：

1. 五个已知 Tushare Endpoint 的进程内全局、Endpoint 分钟及 Endpoint 每日
   原子限流；
2. 只面向随机隔离 Schema 的单证券、两自然日、三 Endpoint 手工缩减研究运行
   入口。

本阶段不调用 Tushare 或 iFinD，不读取环境 Token，不连接正常业务数据库，不修改
Flyway V1—V13、Repository、Controller、scheduler、Shadow、Agent、Python 或 Vue。

```text
INTEGRATION_BASE=0b2dbb665c8e45c4d0024d16094e3925d4dfe55e
TASK_BRANCH=codex/1.4.0-stage-3ar3b-f1c-tushare-reduced-runtime
TARGET_COMMIT_MESSAGE=feat(agent): add isolated tushare reduced research runtime
F1C_PROVIDER_REAL_CALL_COUNT=0
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=20
```

## 2. 输入资格与保持状态

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
REDUCED_RESEARCH_CONTRACT_READY=true
FULL_TECHNICAL_CONTRACT_READY=false

QFQ_FORMULA_QUALIFICATION=VERIFIED
QFQ_OPERATIONAL_RUNTIME_QUALIFICATION=PARTIAL
OFFICIAL_ENDPOINT_RATE_LIMITS=PARTIAL_CONFLICT_IDENTIFIED

F1_ENTRY_READINESS=BLOCKED_MULTIPLE
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE

FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

F1B 合同 `READY` 只表示缩减技术路线已经定义，不表示运行入口、生产数据库或完整
F1 已经就绪。

## 3. Endpoint 级限流合同

`TushareEndpointRateLimitPolicy` 是五个允许 Endpoint 的类型化权威策略。未知
Endpoint 不得继承通用默认值。

| Endpoint | 套餐总表 | 接口页 | 有效官方上限 | 应用安全上限 |
|---|---:|---:|---:|---:|
| `stock_basic` | 200/分钟 | 50/分钟 | 50/分钟 | 45/分钟 |
| `daily` | 200/分钟 | 500/分钟 | 200/分钟 | 180/分钟 |
| `adj_factor` | 200/分钟 | 200/分钟 | 200/分钟 | 180/分钟 |
| `trade_cal` | 200/分钟 | 200/分钟 | 200/分钟 | 180/分钟 |
| `dividend` | 200/分钟 | 200/分钟 | 200/分钟 | 180/分钟 |

所有 Endpoint 还共享 180 次/分钟的进程内全局窗口；每个 Endpoint 的
Asia/Shanghai 自然日安全额度为 90000。许可时必须在同一临界区原子检查并登记：

- 进程内全局分钟窗口；
- 当前 Endpoint 分钟窗口；
- 当前 Endpoint 每日额度。

分钟窗口等待时间取全局与 Endpoint 所需等待时间的较大值。每日额度耗尽立即返回
`TUSHARE_DAILY_API_BUDGET_EXHAUSTED`；等待被中断返回
`TUSHARE_RATE_LIMIT_WAIT_INTERRUPTED`；未知 Endpoint 返回
`TUSHARE_ENDPOINT_NOT_ALLOWED`。所有重试尝试也消耗同一限额。本阶段仍不提供跨
进程或分布式 Token 额度协调。

## 4. 类型化运行授权

唯一允许的授权由
`TushareReducedResearchRuntimeAuthorization.f1cIsolatedManual()` 创建：

```text
providerCode=TUSHARE_PRO
adapterVersion=TUSHARE_MARKET_FACT_PROVIDER_V1
implementationScope=LIMITED_PERSONAL_RESEARCH_USE
runtimeMode=ISOLATED_MANUAL
runNamespace=FORMAL
usageQualification=RESEARCH_ONLY
formalEligible=false
maximumSymbols=1
maximumNaturalDays=2
maximumProviderRequests=3
allowedFactTypes=RAW_DAILY_BAR,ADJUSTMENT_FACTOR,TRADING_CALENDAR
automaticRetryAllowed=false
isolatedSchemaRequired=true
```

正常业务库、scheduler、Shadow、Agent 决策、回测执行、投资建议和交易权限全部
固定为禁止。伪造 Provider/Adapter、放宽请求数、允许重试、允许 public 或允许
Agent 的授权对象必须在 Provider 调用前拒绝。

## 5. 随机隔离 Schema 门禁

`TushareReducedResearchPersistenceGuard` 在 Provider 调用前和持久化前各检查
一次：

- `current_schema()` 不是 `public`；
- Schema 精确匹配 `f1c_tushare_research_<32位十六进制随机后缀>`；
- `search_path` 只有该 Schema，不包含 `public` 或其他回退；
- `flyway_schema_history` 精确包含成功的 V1—V13；
- 前后两次检查的连接目标与迁移状态完全一致。

任一条件不满足时，不发 Provider 请求、不创建 batch、不写观察。正常业务数据库
入口永远不由该守卫授权。

## 6. 缩减运行流程

`TushareReducedResearchRuntimeService` 没有 Controller、scheduler、Agent、回测或
交易入口。固定流程为：

1. 校验冻结授权和类型化技术资格；
2. 校验随机隔离 Schema；
3. 创建精确三次、单证券、两自然日、零重试会话；
4. 依次读取 `daily`、`adj_factor`、`trade_cal`；
5. 校验响应完整、日期闭合、开市日、anchor 与逐日 factor；
6. 仅通过共享 `QfqPriceMath` 在内存计算 OHLC 公式结果；
7. 再次校验隔离 Schema；
8. 通过既有 `captureAuthorizedLimitedPersonalFormal(...)` 只保存
   raw/factor/calendar；
9. 返回独立的公式级缩减研究结果。

`stock_basic`、`dividend`、公司行动、其他 Provider、批量证券和第四次请求均被
排除。响应不完整或 QFQ 校验失败时不允许部分持久化。

## 7. 缩减 QFQ 资格

公式继续只有一份 Java 数学实现：

```text
qfqPrice = rawPrice
         × factorAtTradeDate
         ÷ factorAtAnchorTradeDate
```

anchor 必须是请求范围内、calendar 开市、raw 窗口最后交易日且同日 factor 存在。
每个 raw 日期必须有同日 factor 和开市 calendar；raw 不得晚于 anchor。缩减结果
只在内存返回并固定：

```text
runtimeQualification=REDUCED_RESEARCH_FORMULA_ONLY
systemKnowledgeOnly=true
providerPitVerified=false
corporateActionLineageComplete=false
permanentSecurityIdentityVerified=false
formalEligible=false
fullQfqEligible=false
productionEligible=false
agentDecisionEligible=false
backtestExecutionEligible=false
investmentAdviceEligible=false
tradingEligible=false
```

`QfqAsOfEngine` 继续是完整 lineage/cutoff 权威引擎。因子变化但缺少公司行动
lineage 时仍返回 `PIT_CORPORATE_ACTION_LINEAGE_UNAVAILABLE`，不得因 F1C
公式级结果而放宽。

## 8. 持久化边界

随机隔离 Schema 只保存：

- `RAW_DAILY_BAR`；
- `ADJUSTMENT_FACTOR`；
- `TRADING_CALENDAR`。

资格保持 `RESEARCH_ONLY`、`formalEligible=false`、
`SYSTEM_KNOWLEDGE_ONLY/SYSTEM_KNOWLEDGE_PIT`。不保存 `stock_basic`、
`dividend`、缩减 QFQ、公司行动、Provider revision、合成 action ID、Agent 结论
或投资结论。

## 9. 阶段后状态

F1C 技术实现通过后只允许新增：

```text
TUSHARE_REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME=READY
REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true
ENDPOINT_SPECIFIC_RATE_LIMIT_ENFORCED=true
CONSERVATIVE_ENDPOINT_MINIMUM_POLICY_ENFORCED=true
QFQ_REDUCED_RESEARCH_RUNTIME_QUALIFICATION=VERIFIED
QFQ_FULL_LINEAGE_RUNTIME_QUALIFICATION=PARTIAL
```

同时继续保持：

```text
REDUCED_RESEARCH_RUNTIME_READY=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
SCHEDULER_RUNTIME_READY=false
FULL_TECHNICAL_CONTRACT_READY=false
fullF1EntryReady=false
formalEligible=false
```

`REDUCED_RESEARCH_RUNTIME_READY` 是历史含混字段，继续为 false；调用方必须读取
明确拆分的 isolated-manual 与 production 状态。

## 10. 验收

验收必须覆盖：

- Endpoint 保守较小值、全局/Endpoint 原子窗口、并发、每日额度、次日重置、未知
  Endpoint、等待中断、重试计数和无 Token 快照；
- 冻结授权及伪造授权拒绝；
- public、错误前缀、V13 不完整和调用中目标变化拒绝；
- 三 Endpoint 精确三次、第四次拒绝、零重试；
- incomplete、缺 calendar/factor/anchor、raw 晚于 anchor 均不持久化；
- OHLC 公式确定性、重复捕获幂等、缩减 QFQ 不写库；
- `QfqAsOfEngine`、18 个 QFQ 黄金向量、F1A/F1B 与 FORMAL 捕获门禁不回退；
- PostgreSQL 16 临时实例中随机 Schema V1—V13，public 结构/数据指纹前后不变，
  测试后 Schema、端口和目录残留为 0。
