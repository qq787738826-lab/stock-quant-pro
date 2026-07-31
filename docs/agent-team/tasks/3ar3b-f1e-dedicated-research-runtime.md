# 3A-R3B-F1E：Tushare 缩减研究准入与专用本地前向数据闭环任务书

## 1. 阶段目标

本阶段把 F1C 的随机 Schema、单证券、两日验证入口收敛为一个仍然受限的专用本地
研究运行实现。它只解决以下问题：

1. 根据 F1D 已闭环的个人研究书面许可和 F1B/F1C 技术资格，形成类型化缩减研究准入；
2. 只允许独立本地数据库 `stock_quant_research`、用户 `stock_quant_research` 和
   Schema `tushare_research`；
3. 支持手工单日、1—3 只证券、每只精确三次 Endpoint 调用的批次；
4. 在全批次响应完整后，以一个事务捕获 raw、factor 和 calendar；
5. 只在内存返回公式级 QFQ，不写完整 QFQ 或公司行动事实。

本阶段不执行真实 Provider 调用，不读取 Token，不连接正常业务数据库，也不使专用
运行成为生产、scheduler、Agent、回测、Shadow、F2B、F3 或交易入口。

## 2. Git 基线

- 集成分支：`feature/1.4.0-agent-team`
- 冻结基线：`01024df465afcfa34dfd4efdbef7d56d32419aa1`
- 任务分支：`codex/1.4.0-stage-3ar3b-f1e-dedicated-research-runtime`
- 目标提交：`feat(agent): add dedicated tushare research runtime`

F1D 三提交链
`349856ea6e9e3dc423fc1ad9115886cfc8858159` →
`049c750026fa00dad70c12667fad732af07d60ce` →
`01024df465afcfa34dfd4efdbef7d56d32419aa1`
已经通过实际 Git 最终复验并由用户批准纯 fast-forward 合入。

## 3. 类型化准入

`TushareReducedResearchAdmissionQualification` 必须综合：

- F1D 个人研究书面许可完整；
- F1B 缩减技术合同；
- F1C Endpoint 限流与随机隔离公式入口；
- F1E 专用数据库守卫、批次边界与完整 F1 隔离。

成功判定固定为：

```text
REDUCED_RESEARCH_ROUTE_DECISION=DEDICATED_LOCAL_RESEARCH_PATH
REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true
REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true
REDUCED_RESEARCH_OPERATIONAL_READY=false
```

每项准入 Claim 必须有类型化状态和证据 ID。实现就绪只表示代码与隔离验收合同可以
进入后续人工受控验收，不表示运行已获接受。以下状态必须继续为 false：

```text
fullF1EntryReady=false
formalEligible=false
fullTechnicalContractReady=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
SCHEDULER_RUNTIME_READY=false
AGENT_DECISION_RUNTIME_READY=false
BACKTEST_EXECUTION_RUNTIME_READY=false
F2B_RUNTIME_READY=false
F3_RUNTIME_READY=false
```

## 4. 专用数据库守卫

`TushareDedicatedResearchPersistenceGuard` 在 Provider 前和事务捕获前后都必须验证：

- 数据库用途：`TUSHARE_DEDICATED_LOCAL_RESEARCH`；
- 数据库：`stock_quant_research`；
- 用户：`stock_quant_research`；
- JDBC Host：本机回环地址；
- Schema 与唯一 search path：`tushare_research`；
- Flyway：精确 V1—V13；
- 捕获事务前后数据库身份、Schema、search path 和 backend PID 一致。

`public`、`stock_quant`、`stock_quant_test`、其他 Schema、含 public 回退的 search path、
迁移不完整、用途未显式设置、非事务捕获或目标变化都必须在写入前拒绝。不得修改
V1—V13 或 Repository SQL。

## 5. 手工批次合同

唯一授权工厂固定 `PERSONAL_2000_POINT/RESEARCH_ONLY/DEDICATED_LOCAL_MANUAL`，
并要求：

- Provider/Adapter：`TUSHARE_PRO/TUSHARE_MARKET_FACT_PROVIDER_V1`；
- `runNamespace=FORMAL`，但 `formalEligible=false`；
- 仅 SSE/SZSE；
- 精确一个自然日；
- 1—3 只不重复证券；
- 每只证券只调用 `daily/adj_factor/trade_cal`；
- 每只精确 3 次请求，总预算精确 `3 × 证券数`，最大 9；
- 自动重试关闭；
- 生产、正常业务库、scheduler、Shadow、Agent、回测、投资建议和交易全部禁止。

一个批次共用同一 `TushareManualBoundedSession` 和 Endpoint 限流器。请求顺序固定，
未知 Endpoint、第四只证券、第二个自然日、重试或超预算必须在 HTTP 前拒绝。

## 6. 原子捕获与结果资格

运行顺序固定为：

1. 校验类型化授权和准入；
2. 校验 Endpoint policy 与 limiter；
3. Provider 前验证专用数据库；
4. 创建共享批次会话；
5. 顺序取得全部证券的 raw/factor/calendar；
6. 验证每只证券精确一条同日 raw、factor 和开市 calendar；
7. 验证批次调用数、重试数和 limiter 增量；
8. 只复用 `QfqPriceMath` 生成内存公式级 OHLC；
9. 一个 Spring 事务捕获全部三类事实；
10. 事务内前后重验数据库与 backend PID；
11. 返回类型化批次结果。

任一证券 Provider 失败、响应不完整、日期不闭合、因子/价格非法、调用计数不一致、
事务守卫失败或任何写入失败时，整个批次不得保留部分观察。重复同一内容必须幂等；
同一自然键内容变化继续形成 append-only 系统知识链。

结果必须标记：

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

QFQ 结果不写数据库；`QfqAsOfEngine` 的公司行动 lineage、cutoff、factor predecessor、
用途和 Provider 一致性门禁保持不变。

## 7. 验证范围

离线验证必须覆盖：

- 准入不变量、专用授权和批次命令；
- 1/2/3 证券精确 3/6/9 请求与共享预算；
- Endpoint 限流、零重试和 Token 不泄露；
- Provider 失败时零捕获；
- 第二/第三证券验证或捕获失败时全事务回滚；
- search path 运行中变化时全事务回滚；
- 重复捕获幂等、内容变化 append-only；
- 缩减 QFQ 不落库、公司行动不落库；
- F1A/F1B/F1C/F1D、Provider V2、QFQ 权威引擎和 18 个黄金向量回归；
- 全新的临时 PostgreSQL 实例中 V1—V13 专用 Schema 闭环；
- 临时 public 指纹前后不变，Schema、端口和目录残留为 0。

普通测试不得联网、读取 Token 或访问既有数据库。随机 PostgreSQL 测试必须使用新建
临时实例并在完成后停止和删除。

## 8. 阶段状态边界

F1E 完成后仍保持：

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
BLOCKED_TECHNICAL_EVIDENCE
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL

FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

Provider 新增调用为 0，Tushare 累计真实业务请求继续为 20，iFinD 为 0。正常业务库
不执行 V13；scheduler、Shadow、Day 002、F2B、F3、3A-R3B-1、3B 和交易均不开始。
