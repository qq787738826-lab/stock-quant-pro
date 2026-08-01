# 3A-R3B-F1E：Tushare 缩减研究准入与专用本地前向数据闭环阶段记录

## 1. 基线与阶段定位

- 集成基线：`01024df465afcfa34dfd4efdbef7d56d32419aa1`
- 任务分支：`codex/1.4.0-stage-3ar3b-f1e-dedicated-research-runtime`
- 任务提交：`feat(agent): add dedicated tushare research runtime`

F1D 三提交链已经通过 ChatGPT 对实际 Git 的最终复验、由用户批准，并纯 fast-forward
合入集成分支。F1E 基于该许可闭环，只实现专用本地缩减研究运行代码和隔离验收合同；
它不再次调查许可，不调用 Provider，也不升级完整技术合同。

## 2. 准入结论

类型化 `TushareReducedResearchAdmissionQualification` 把书面许可、缩减技术合同、
Endpoint 限流、专用数据库守卫、批次边界、系统知识 PIT、公式 QFQ 和完整 F1 隔离
分别建模为带证据 ID 的 Claim。当前结果为：

```text
REDUCED_RESEARCH_ROUTE_DECISION=DEDICATED_LOCAL_RESEARCH_PATH
REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true
REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true
REDUCED_RESEARCH_OPERATIONAL_READY=false
```

`READY` 只表示实现和后续受控验收合同已经具备；本阶段没有进行真实 Provider 或用户
操作验收，因此 operational 继续为 `NOT_ACCEPTED/false`。完整 F1 仍为：

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
BLOCKED_TECHNICAL_EVIDENCE
fullF1EntryReady=false
fullTechnicalContractReady=false
formalEligible=false
```

## 3. 专用数据库身份与事务守卫

新增守卫只接受：

| 项目 | 固定值 |
|---|---|
| 数据库用途 | `TUSHARE_DEDICATED_LOCAL_RESEARCH` |
| 数据库 | `stock_quant_research` |
| 用户 | `stock_quant_research` |
| Schema | `tushare_research` |
| search path | 只有 `tushare_research` |
| JDBC Host | 本机回环地址 |
| Flyway | 精确 V1—V13 |

Provider 前进行只读预检。真正捕获时，事务内前后再次校验数据库、用户、JDBC 目标、
Schema、search path、V1—V13 和 backend PID；若调用期间目标变化，整个批次回滚。
`public`、普通业务库、通用测试库、其他 Schema、public 回退、迁移不完整或非事务
调用均拒绝。

## 4. 批次编排与请求预算

唯一授权固定个人 2000 积分、`RESEARCH_ONLY/DEDICATED_LOCAL_MANUAL` 和所有生产能力
关闭。命令只允许 SSE/SZSE、精确一个自然日和 1—3 只不重复证券。

每只证券按 `daily → adj_factor → trade_cal` 精确调用三次；一个批次共用一份
`TushareManualBoundedSession`，总预算是 `3 × 证券数`，即 3/6/9 次，最大 9，重试为
0。Gateway 继续经过 F1C 已验收的全局、Endpoint 分钟和每 Endpoint 每日原子限流；
未知 Endpoint 不允许回落到通用额度。

## 5. 全有或全无捕获

服务先取得并验证全部证券响应，再进入一个 Spring 事务调用
`captureAuthorizedDedicatedResearchBatch(...)`。该入口不再接受裸
`List<MarketFactResponse>`，只能接受由原始批次命令、证券顺序、专用会话和全部响应
共同构造的 `TushareDedicatedResearchCaptureContract`。私有工厂会逐项绑定日期、
证券顺序、调用数、零重试、F1E 会话配置和 raw/factor/calendar 精确事实集，调用方
不能绕过合同单独拼装响应。

事务入口在任何 Repository 写入前完成全部响应、授权、资格、事实排序与重复键、
观察时间、证券/交易所/日期、Provider 元数据和会话计数的纯预验证；第二个或后续
响应存在问题时也保持零写入。验证完成后才逐响应复用既有私有捕获逻辑。捕获前后
均绑定同一事务连接与 backend PID。

任何 Provider、资格、闭合、计数、守卫或持久化失败都会产生全批次回滚。重复内容
产生幂等结果；内容变化继续形成 append-only 系统知识观察。未保存 stock_basic、
dividend、公司行动、Provider revision、缩减 QFQ、Agent 结论或投资结论。

## 6. 首次实际 Git 复验修复

初始提交 `d5f28066bee97a5485917e193926594b9961767e` 的实际 Git 复验发现：

- 专用捕获入口仍接收裸响应列表，没有把响应绑定到原始命令、顺序和会话；
- 深层事实校验仍位于逐响应写入循环，后续非法响应存在先写风险；
- 数据库结果身份没有完整绑定前后 JDBC 目标、search path 和 V1—V13；
- `BLOCKED` 准入来源状态与资格对象不变量冲突，不能安全构造阻断结果。

增量修复建立私有工厂捕获合同和全响应预验证；数据库执行身份只能从守卫前后两份
完整 `Verification` 生成，并要求数据库、用户、用途、Schema、search path、JDBC
目标、迁移集合、事务和 backend PID 全部相同；准入改为类型化
`assess(written, technical, implementationEvidence)`，书面或技术来源不足时返回
`BLOCKED`，不会以构造器异常替代业务判定。修复不改变当前 ready/operational 状态、
真实调用累计数或四项正式门禁。

## 7. 最终实际 Git 复验修复

增量提交 `e95781687cd0af63507c42017ec8ca6d6f404f86` 已解决捕获合同、全部响应
写前验证、数据库身份和 Admission `BLOCKED` 语义。其最终实际 Git 复验继续发现：
`calendar.open=true` 只由上层 `TushareDedicatedResearchBatchService` 检查，直接调用
专用捕获入口时仍可能绕过该开市日不变量。

本次增量修复新增共享纯验证器 `TushareDedicatedResearchFactValidator`，由正常 Service、
`TushareDedicatedResearchCaptureContract` 和 `PitMarketFactCaptureService` 写前预验证
共同调用。每只证券统一要求 raw/factor/calendar 各一条、日期和来源身份闭合、
calendar 为 `open=true/REGULAR`、factor 与 OHLC 合法、无公司行动、Provider 调用数为
3 且重试数为 0。即使调用方绕过 Service 或篡改已构造合同，任一证券为休市日也会在
任何 Repository 或 Temporal Foundation 写入前拒绝；两证券批次中第二只证券休市时
同样整批零写入。该修复不改变业务状态、Provider 调用数、QFQ 规则或四项正式门禁。

## 8. 缩减 QFQ

每只证券只有一个开市日 raw 与同日 factor。OHLC 只调用共享 `QfqPriceMath`，以同日
factor 作为显式 anchor，在内存返回 `REDUCED_RESEARCH_FORMULA_ONLY`。结果明确：

- `systemKnowledgeOnly=true`；
- Provider PIT、公司行动 lineage、永久证券身份、完整 QFQ、生产、Agent、回测、
  投资建议和交易资格全部为 false；
- 不使用 `QfqAsOfResult`，不写 QFQ 表；
- `QfqAsOfEngine` 的完整 lineage/cutoff 门禁和黄金向量不变。

## 9. 验证结果

全部结果是 Codex 本地实际执行证据，不是 GitHub Actions CI：

| 验证组 | 结果 |
|---|---|
| Java 干净编译 | `mvn -pl quant-server -am clean compile -DskipTests`；8 个 core、181 个 server 生产源码，`BUILD SUCCESS` |
| F1E 定向与边界单元测试 | `40/0/0/0`；共享事实验证、捕获合同、休市日写前零写、准入、授权、命令、守卫、1—3 证券编排和 failure-before-capture |
| F1A—F1E、Provider V2 与 QFQ 联合回归 | `146/0/0/0`；Provider V2 10 项、QFQ 权威引擎 19 项及 18 个黄金向量不变 |
| `quant-core` 全量 | `4/0/0/0` |
| `quant-server` 安全全量 | `437/0/0/0`；命令级排除全部 `*IntegrationTest/*Postgres*/*CrossLanguage*/*Live*` |
| F1A PostgreSQL | `4/0/0/0`，`Skipped=0` |
| F1C PostgreSQL | `3/0/0/0`，`Skipped=0` |
| F1E PostgreSQL | `10/0/0/0`，`Skipped=0`；专用数据库/用户/Schema、V1—V13、1/2/3 证券、合同绕过零写、后续响应深层事实异常零写、直接休市日零写、第二证券休市整批零写、全批次回滚、search path TOCTOU、幂等及 append-only |

三组数据库测试只连接同一个本轮新建的 PostgreSQL 16.13 临时实例中的
`stock_quant_test` 与 `stock_quant_research`，没有连接既有或正常业务数据库。F1A/F1C
随机 Schema、F1E 专用 Schema 测试后残留均为 0；F1E 临时 public 表数仍为 0，测试内
public 结构、数据和 Flyway 指纹前后相同。临时 55432 监听和集群目录残留均为 0。
全部 Provider 响应来自合成 Gateway，不计真实 Provider 调用。

## 10. 当前状态

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
REDUCED_RESEARCH_CONTRACT_READY=true
TUSHARE_REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME=READY
REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true

REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true
REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true
REDUCED_RESEARCH_OPERATIONAL_READY=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
SCHEDULER_RUNTIME_READY=false
AGENT_DECISION_RUNTIME_READY=false
BACKTEST_EXECUTION_RUNTIME_READY=false
F2B_RUNTIME_READY=false
F3_RUNTIME_READY=false
FULL_TECHNICAL_CONTRACT_READY=false
```

四项正式门禁保持：

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

`F1E_PROVIDER_REAL_CALL_COUNT=0`，Tushare 累计真实业务请求为 20，iFinD 为 0。未读取
Token，未访问既有或正常业务数据库，未对 public 执行 V13。scheduler、Shadow、
Day 002、F2B、F3、3A-R3B-1、3B 和交易均未开始。

## 11. 合入后状态

F1E 三提交链 `d5f28066bee97a5485917e193926594b9961767e` →
`e95781687cd0af63507c42017ec8ca6d6f404f86` →
`0e2b607bc068910319134790360d71a18a6a9e02` 已通过 ChatGPT 对完整实际 Git 差异的
最终审查，并经用户批准纯 fast-forward 合入 `feature/1.4.0-agent-team`。合入只确认
实现与隔离验证成立；真实受控验收仍未运行，`REDUCED_RESEARCH_OPERATIONAL_READY=false`。
