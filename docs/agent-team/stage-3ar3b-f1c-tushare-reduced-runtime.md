# 3A-R3B-F1C：Tushare Endpoint 级限流与随机隔离缩减研究运行入口阶段记录

## 1. Git 与阶段边界

```text
INTEGRATION_BRANCH=feature/1.4.0-agent-team
INTEGRATION_BASE=0b2dbb665c8e45c4d0024d16094e3925d4dfe55e
TASK_BRANCH=codex/1.4.0-stage-3ar3b-f1c-tushare-reduced-runtime
TARGET_COMMIT_MESSAGE=feat(agent): add isolated tushare reduced research runtime
```

开始前，本地和远程集成分支均精确位于冻结基线，ahead/behind 为 `0/0`；已跟踪
工作区干净、暂存区为空，任务分支从该提交创建。`.ai/` 只通过 Git 状态确认仍为
未跟踪目录。本阶段没有读取或检查 `TUSHARE_TOKEN`，没有调用 Tushare/iFinD，
没有连接正常业务数据库或在 public 执行 V13。

F1B 双提交链：

```text
ce3360058b4ade6a2e86cdd9302387e7d338794b
0b2dbb665c8e45c4d0024d16094e3925d4dfe55e
```

已经通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入。
F1C 基于最终 F1B 集成提交实施。

## 2. Endpoint 级限流实现

新增类型化 `TushareEndpointRateLimitPolicy`，只接受
`stock_basic/daily/adj_factor/trade_cal/dividend`。冻结值为：

| Endpoint | 有效官方上限 | 应用安全上限 |
|---|---:|---:|
| `stock_basic` | 50/分钟 | 45/分钟 |
| `daily` | 200/分钟 | 180/分钟 |
| `adj_factor` | 200/分钟 | 180/分钟 |
| `trade_cal` | 200/分钟 | 180/分钟 |
| `dividend` | 200/分钟 | 180/分钟 |

升级后的 `TushareTokenRateLimiter` 在单一同步边界内同时检查全局分钟、Endpoint
分钟和 Endpoint 每日窗口，三项都可用才一次性登记，不会先占全局额度再等待
Endpoint。全局安全上限为 180/分钟，每 Endpoint 每日安全上限为 90000；
分钟等待取两种窗口等待时间的最大值，每日耗尽立即失败。快照只包含计数、各
Endpoint 限额、窗口和 `distributedCoordination=false`，不包含 Token。

当前状态：

```text
OFFICIAL_ENDPOINT_RATE_LIMITS=PARTIAL_CONFLICT_IDENTIFIED
ENDPOINT_SPECIFIC_RATE_LIMIT_ENFORCED=true
CONSERVATIVE_ENDPOINT_MINIMUM_POLICY_ENFORCED=true
PROCESS_WIDE_RATE_LIMIT=true
DISTRIBUTED_RATE_LIMIT_COORDINATED=false
DISTRIBUTED_DAILY_QUOTA_COORDINATED=false
```

官方证据冲突状态没有被实现状态覆盖；多进程和多实例仍未协调。

## 3. 隔离手工运行入口

新增：

- `TushareReducedResearchRuntimeAuthorization`；
- `TushareReducedResearchPersistenceGuard`；
- `TushareReducedResearchRuntimeService`；
- `TushareReducedResearchModels`。

唯一冻结授权只允许 Tushare Pro V1 Adapter、`LIMITED_PERSONAL_RESEARCH_USE`、
`ISOLATED_MANUAL`、单证券、两自然日、三个请求、零重试和
raw/factor/calendar。正常业务库、scheduler、Shadow、Agent、回测、投资建议和
交易全部禁止。

运行入口没有 Controller 或自动触发器。它在 Provider 前校验授权、资格和随机
Schema；只调用 `daily/adj_factor/trade_cal`；完整校验响应与日期闭合后通过唯一
`QfqPriceMath` 在内存计算公式级 OHLC；持久化前再次校验同一隔离环境；最后复用
既有有限个人 FORMAL 专用入口只保存 raw/factor/calendar。

## 4. 随机 Schema 安全门

Schema 必须精确匹配：

```text
f1c_tushare_research_<32位十六进制随机后缀>
```

`current_schema()`、严格单项 `search_path` 和成功的 V1—V13 迁移必须同时满足。
`public`、其他随机前缀、`public` 回退、迁移不完整或 Provider 调用期间目标变化
均安全拒绝。安全门在 Provider 前和捕获前分别执行；失败时不创建 batch 或观察。

随机 PostgreSQL 16.13 实测使用临时 55432 实例：临时 public 只建立 V1—V12
冻结基线，F1C 随机 Schema 执行 V1—V13。两次相同合成输入形成 6 条
raw/factor/calendar 观察，第二次 6 条全部幂等；公司行动观察为 0，缩减 QFQ 只
在内存返回。测试结束后随机 Schema 为 0、55432 监听为 0、临时目录为 0，public
结构和数据指纹前后相同。

## 5. QFQ 运行资格

```text
QFQ_FORMULA_QUALIFICATION=VERIFIED
QFQ_REDUCED_RESEARCH_RUNTIME_QUALIFICATION=VERIFIED
QFQ_FULL_LINEAGE_RUNTIME_QUALIFICATION=PARTIAL
```

缩减入口只调用 `QfqPriceMath`，anchor 必须是请求窗口最后一个开市 raw 日期且有
同日 factor；每个 raw 日期必须有开市 calendar 和同日正 factor。结果类型与
`QfqAsOfResult` 分离，并显式声明不是 Provider PIT、完整公司行动 lineage、
完整 QFQ、生产、Agent、回测、投资建议或交易结果。

`QfqAsOfEngine` 的公司行动 lineage、factor predecessor、cutoff、用途、
Provider 一致性和 18 个黄金向量不变；因子变化而缺公司行动时继续返回
`PIT_CORPORATE_ACTION_LINEAGE_UNAVAILABLE`。

## 6. 类型化技术资格投影

F1B 的证据 claim 模型增加：

- `endpointConservativeMinimumPolicyClaim`；
- `endpointSpecificRateLimitEnforcementClaim`；
- `reducedResearchIsolatedManualRuntimeClaim`；
- `qfqReducedResearchRuntimeClaim`；
- `qfqFullLineageRuntimeClaim`；
- `isolatedSchemaGuardClaim`。

当前投影：

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
REDUCED_RESEARCH_CONTRACT_READY=true
TUSHARE_REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME=READY
REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true

REDUCED_RESEARCH_RUNTIME_READY=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
SCHEDULER_RUNTIME_READY=false
FULL_TECHNICAL_CONTRACT_READY=false
fullF1EntryReady=false
formalEligible=false
```

历史含混字段 `REDUCED_RESEARCH_RUNTIME_READY` 继续为 false，避免下游把隔离
手工入口误判为生产运行就绪。

## 7. 测试证据

阶段完成时执行并记录：

| 验证组 | 结果 |
|---|---|
| Java 编译 | `mvn -pl quant-server -am -DskipTests compile`，`BUILD SUCCESS` |
| F1C 运行、守卫、Endpoint 限流、资格、Provider 与 QFQ 定向组 | `70/0/0/0` |
| F1A/F1B、Provider V2 与 QFQ 扩展离线回归 | `89/0/0/0`，含 18 个黄金向量和显式完整 lineage 门禁 |
| `quant-core` 全量 | `4/0/0/0` |
| `quant-server` 安全全量 | `380/0/0/0`；命令级排除 `*IntegrationTest/*Postgres*/*CrossLanguage*/*Live*` |
| F1A FORMAL 授权绕过随机 PostgreSQL 回归 | `4/0/0/0`，`Skipped=0` |
| F1C PostgreSQL 16.13 随机 Schema V1—V13 | `1/0/0/0`，`Skipped=0` |
| public 冻结基线 | V1—V12，结构和数据指纹前后相同 |
| 随机 Schema / 55432 / 临时目录残留 | `0 / 0 / 0` |

所有 Gateway 数据均为确定性合成测试数据，没有真实 Provider 响应、Token、CSV 或
行情 fixture 进入仓库。外部集成类在安全全量中由命令级排除，因此没有读取环境 Token；
两组 PostgreSQL 测试只连接新建的本地临时实例，分别运行后停止并删除。

## 8. 阶段结论与未解除门禁

F1C 只证明随机隔离、人工调用的缩减公式运行链可用。它不表示：

- 正常业务数据库或生产运行可用；
- Provider 三项具体书面许可完整；
- Provider PIT、完整公司行动、永久证券身份或全历史 `DAILY_EXACT` 已验证；
- `QfqAsOfEngine` 完整 lineage 已通过；
- Agent、回测、scheduler、Shadow、Day 002、F2B、F3 或交易可启动。

完整 F1 继续保持：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE
```

四项正式门禁继续保持：

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

本阶段 Provider 新增调用为 0，Tushare 累计真实业务请求仍为 20，iFinD 为 0。
F1C 技术实现已在任务分支完成，待 ChatGPT 基于实际 Git 提交验收，尚未合入；
不得自动开始任何下一阶段。
