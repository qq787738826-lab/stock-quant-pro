# 3A-R3B-F1F-B2-RUNNER 阶段记录：专用执行入口与冻结安全门修复

## 阶段结论

F1F-B1 双提交链已经验收并合入 `e3777602fadd65f3af0a2ba8ac6e886693d745d5`。随后 B2-PRE 审计结论为 `NOT_READY`：该提交缺少可正式启动的一次性最小进程，输出审计没有覆盖秘密与数据库初始化前的最早边界，治理 V14 仍依赖自动 baseline 语义，构建脚本没有默认非正式模式且使用环境 Maven。

四提交链已通过实际 Git 复验并经用户批准纯 fast-forward 合入 `213264bc63a2584f0fbb30dca059abf272e62a64`。`e3777602...` 只保留为开发父提交，不再作为未来真实验收冻结 SHA。

合入后的首次 B2-FREEZE 仍判 `NOT_READY`：清单错误使用了 Runner 不支持的松散数据库/构建参数，且尚无正式 V1—V13 专用数据库准备入口。后续 DBPREP 任务只修复这两个缺口；该历史结论不否定 Runner 的代码验收。

## 实现结果

- 新增普通 Java `main` 一次性 runner，使用不可由 `loader.main` 覆盖的 Spring Boot `JarLauncher` 仅作可执行 JAR 类加载，不创建 Spring ApplicationContext；组件以显式白名单手工构造，Jackson 只显式注册 `JavaTimeModule`，不触发模块自动发现。
- 输出捕获与 Logback 隔离在所有元数据、秘密、DataSource、Flyway 和 Provider 客户端之前安装。秘密仅由 `System.console().readPassword` 通道读取；不可用时直接非零退出。
- 专用非池化 DataSource 不把密码放入参数、系统属性或 JDBC URL；关闭时清零内部字符数组。
- 执行器拆为“Provider/回读后 pending”与“输出审计通过后 PASSED”两步；Provider 组件及 Token 持有者在最终 flush/审计前关闭，捕获拓扑在审计计算后才恢复；脏输出、关闭期泄漏或捕获不完整不能持久化 `PASSED`。
- V14 使用 `baselineOnMigrate(false)`。构建证明、授权、数据库身份、search path、精确 V1—V13 成功链和主历史失败数 0 先验证；只有治理 history 不存在且无未跟踪治理对象时才显式 `BASELINE/13`，再迁移精确 `SQL/V14`。部分、未知、失败或元数据伪造的历史均拒绝。
- 构建脚本默认 `PREPARATION_ONLY`，正式模式必须显式指定；两种模式均使用 Maven Wrapper `3.9.16`。PREPARATION 的 MANIFEST/sidecar 即使改名也不能取得治理资格。
- 构建从已核验 commit 的 `git archive` 在系统临时目录离线执行，不对共享工作区运行 Maven `clean`。只发布专用 `quant-server-1.3.1-f1f-b2-runner.jar` 与相邻 sidecar；MANIFEST 直接冻结 `JarLauncher`/Runner `Start-Class`，启动只用 `java -jar`，不接受 `loader.main` 覆盖。实际 `java.version` 同时绑定 MANIFEST、sidecar、Maven 构建 JVM 与运行时；隔离源码和构建目录按经校验的系统临时目录边界清理，不读取或打包 `.ai/`。

## 验证结果

- 合并前最终安全审查发现并修复：自动 Jackson 模块发现、Provider 组件晚于最终审计关闭、Logback 恢复早于最终扫描、治理 history 只核对版本、主 history 未单独拒绝失败项、共享工作区构建、可由 `loader.main` 覆盖的启动器以及 JAR/Java 运行时绑定不足。修复未扩大 22 文件累计范围。
- 专用 runner 与冻结构建证明定向测试：`32 / 0 / 0 / 0`（Tests / Failures / Errors / Skipped）。
- F1A—F1F-B2、Provider V2 与 QFQ 联合离线回归：`201 / 0 / 0 / 0`。
- QFQ 权威引擎：`19 / 0 / 0 / 0`，其中既有 18 个黄金向量保持不变。
- `quant-core` 全量：`4 / 0 / 0 / 0`。
- `quant-server` 安全离线全量：`630 / 0 / 0 / 117`。相对审查前 `621 / 0 / 0 / 112` 增加 9 项，其中新增的 5 个 Skipped 全部来自扩展后的 PostgreSQL 条件测试；核心 PostgreSQL 套件另在临时实例执行为 `10 / 0 / 0 / 0`，没有以跳过替代数据库断言。
- PostgreSQL `16.13` 全新临时实例验证：数据库 `stock_quant_research`、Schema `tushare_research`；主 history 在治理时精确 V1—V13，治理 history 精确 BASELINE/13 与 SQL/V14。错误数据库、错误用户、public、含 public 的 search path、主 history 缺失/失败/未来版本及治理 history 部分/未知/伪造全部拒绝；测试后端口、进程与目录残留为 0，未访问既有或正常业务数据库。
- Java `clean compile`、PowerShell 语法解析与 `git diff --check` 通过。
- 默认 `PREPARATION_ONLY` 构建预演从最终审查提交的隔离 `git archive` 完成：Maven Wrapper 为 `3.9.16`，专用 JAR、MANIFEST 与 sidecar 均绑定 PREPARATION 模式、实际 Java 运行时和最终 commit；生产来源解析为 `PREPARATION_ONLY` 且 `governanceEligible=false`。打包态无授权启动稳定非零退出，不启动 Spring、Web 或 scheduler。
- 预演 JAR、原始 JAR、sidecar、临时 MANIFEST 和依赖模块 JAR 已精确删除，相关构建产物残留为 0。
- 未运行 Live 测试或任何真实受控验收；临时 PostgreSQL 只用于本轮全新隔离治理验证。

## 安全边界

本阶段没有调用 Tushare/iFinD，没有读取 Token 或数据库密码，没有访问正式 PostgreSQL，没有执行正式 V13/V14，没有创建或消费 acceptance ID，也没有执行 `CONTROLLED_BUILD_ARTIFACT`。证券、日期、数据库运行参数和正式授权仍未签发。正式 Runner 唯一启动参数是 `AuthorizationFile`；构建证明和所有非敏感运行参数必须在严格授权文件中冻结，不能使用松散命令行覆盖。

七项状态保持：

```text
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

## 2026-08-03 真实验收与事务边界修复

首次实际进入 Provider 与捕获链的授权 ID
`F1FB2_20260803_REISSUE_C5A3D7B92A94` 已永久封存。该次执行按
`daily → adj_factor → trade_cal` 精确完成 3 次真实调用，重试为 0；治理 V14
已完成，但在第一次 Repository 写入前由数据库守卫拒绝：

```text
CONTROLLED_ACCEPTANCE_STATUS=FAILED_DATABASE_GUARD
failure_stage=DATABASE_GUARD
safe_failure_reason=TUSHARE_DEDICATED_RESEARCH_TRANSACTION_REQUIRED
capture_batch_id=NULL
F1F_B2_PROVIDER_REAL_CALL_COUNT=3
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=23
```

实际调用图确认 Runner 继续使用非 Spring、手工白名单装配；因此 F1E 捕获入口上的
`@Transactional` 没有经过代理，`TransactionSynchronizationManager` 在守卫执行时没有
专用 DataSource 的活动资源。增量修复不改变 Runner 架构，也不删除或放宽守卫；捕获服务
改用构造时已有、绑定专用 DataSource 的 `PlatformTransactionManager` 和显式
`TransactionTemplate`，把全部 Temporal、batch、observation 与 typed fact 写入包在同一
事务中。错误事务管理器仍在写前被原 reason code 拒绝，第三类事实写入失败必须整批回滚。

本修复不读取 Token，不调用 Tushare/iFinD，不访问永久专用数据库，不创建新 acceptance
ID，不重新执行 F1F-B2。当前仍为：

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

完整 F1 十项技术阻断不变。先完成 DBPREP、再按新集成 SHA 冻结和授权的顺序属于已执行的历史准入要求；当前必须先完成本事务修复的实际 Git 复验与合入，再由用户另行批准新的冻结和验收，失败 ID 不得重试。

## 2026-08-03 typed fact 回读失败与增量修复

事务修复合入后签发的 ID `F1FB2_20260803_POSTFIX_EB61FFB9663C`
已执行并永久封存。该次执行精确完成 `daily → adj_factor → trade_cal`
三次真实调用、重试 0，治理 V14 已完成；最终状态为：

```text
CONTROLLED_ACCEPTANCE_STATUS=FAILED_VALIDATION
failure_stage=VALIDATION
safe_failure_reason=TUSHARE_CONTROLLED_ACCEPTANCE_TYPED_FACT_READBACK_INVALID
capture_batch_id=NULL
F1F_B2_PROVIDER_REAL_CALL_COUNT=3
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=26
```

调用链确认 F1E 捕获事务先返回 `CaptureResult.batchId`，随后才执行 committed
typed fact 回读。V13 的真实关联是 `pit_market_fact_observations.batch_id` 指向批次，
`raw_daily_bar_facts_v2`、`adjustment_factor_facts_v1` 与
`trading_calendar_facts_v1` 仅通过 `observation_id` 指向 observation。
SQL 关联没有假设 typed 表存在 `batch_id`；精确缺陷是回读把所有 observation
的 `source_instrument_id` 都与批次证券 identity 比较，但生产捕获实际保存 raw、
factor、calendar 各自的类型化 identity。尤其 factor 与 calendar 必然不同于批次
证券 identity，因此合法 1/1/1 事实也会被安全拒绝。

`capture_batch_id=NULL` 的原因是 executor 只有在回读和输出审计均成功后才调用
`markCandidate` 投影批次 ID；本次在候选证据构造前抛出验证异常，失败 transition
只记录状态、阶段、reason 和调用次数。该空值不等于捕获事务未提交。当前环境没有
永久数据库凭据，使用 `psql -w` 的只读连接在认证前失败，未绕过秘密通道，也没有
对永久数据库执行 SELECT、DDL 或 DML；永久库事实存在性只能以用户提供的持久化
状态和后续正式只读审计为准。

增量修复保持 batch 的证券级 identity 检查；typed observation 改为分别验证
`rawSourceIdentity`、`factorSourceIdentity` 和 `calendarSourceIdentity`。测试数据也
改用与生产捕获相同的三类 identity，并以 V1—V14 临时 PostgreSQL 证明：三类事实
提交后精确回读 1/1/1、旧批次不会混入当前批次、错误批次/identity/数量均拒绝、
候选证据中的 batch ID 非空，第三类事实写入失败仍整批回滚。事务守卫没有修改或
放宽；本修复不调用 Provider、不读取 Token、不创建新 acceptance ID，也不执行新
的 F1F-B2。

验证结果：typed fact readback 与事务定向 PostgreSQL `7/0/0/0`、`Skipped=0`；
Runner/F1E 边界与 QFQ 联合定向 `52/0/0/0`，其中 QFQ 权威引擎
`19/0/0/0` 并保持 18 个黄金向量；Java `clean compile` 通过。临时 PostgreSQL
使用全新随机端口和目录，结束后端口、进程和目录残留均为 0。

当前继续保持：

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

## 2026-08-04 SYSTEM_KNOWLEDGE 回读失败与增量修复

typed fact identity 修复合入后签发的 ID
`F1FB2_20260804_READBACK_POSTFIX_D6D0F13C478D` 已执行并永久封存。该次执行
精确完成 `daily → adj_factor → trade_cal` 三次真实调用、重试 0；事务守卫和三类
typed fact identity 回读均已通过，最终状态为：

```text
CONTROLLED_ACCEPTANCE_STATUS=FAILED_VALIDATION
failure_stage=VALIDATION
safe_failure_reason=TUSHARE_CONTROLLED_ACCEPTANCE_SYSTEM_KNOWLEDGE_READBACK_INVALID
F1F_B2_PROVIDER_REAL_CALL_COUNT=3
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=29
```

精确根因是 V13 的幂等语义与旧回读的批次关联假设冲突。相同证券、日期及内容的
三类事实已存在于各自 append-only 链尾时，捕获会创建当前 batch，但不会追加重复
observation；旧回读只按当前 `observation.batch_id` 查三条事实，因而把合法的 0 条
新 observation 误判为 SYSTEM_KNOWLEDGE 缺失。`capture_batch_id` 仍只在回读和
输出审计成功后由 `markCandidate` 投影，所以失败记录中的空值不否定捕获事务已经
返回内部 batch ID。

增量修复在 batch 的现有 `provider_metadata_json` 中冻结三类事实引用：fact type、
类型化 source identity、natural key 和 canonical content hash。提交后回读逐项解析
当前 batch 引用并核对对应链尾及 typed fact；新 observation 仍要求属于当前 batch，
幂等复用则保留既有 observation 原始 `firstObservedAt/knownAt`，不伪造 successor，
也不重写 SYSTEM_KNOWLEDGE 时间。执行开始、当前 batch observedAt、既有观察时间和
回读时间全部以 UTC `Instant` 处理，并显式截断到 PostgreSQL 微秒精度；没有增加
宽泛容差。写事务前后 backend PID 仍必须相同；提交后的只读回读可以使用不同连接
和不同 PID，因为它不再属于写事务，但数据库、用户和 Schema 身份必须重新通过守卫。

永久 `38432` 数据库仅尝试使用无密码、read-only 的 `psql -w` 连接；认证在建立连接
前因没有密码失败，因此没有执行 SELECT、DDL 或 DML，也没有绕过秘密通道。根因由
V13 幂等规则、实际失败顺序和全新 V1—V14 临时 PostgreSQL 的同输入重复捕获复现
闭环；永久库具体行级时间未在本修复中直接读取。

验证结果：SYSTEM_KNOWLEDGE、Runner 与 F1E 离线边界 `44/0/0/0`；全新
PostgreSQL 16.13 的 V1—V14 定向组 `9/0/0/0`、`Skipped=0`，覆盖 UTC/微秒规范化、
缺失/未来/早于执行开始的当前批次时间、错误批次与 identity、幂等三引用、候选 batch
投影以及第三类事实失败整批回滚；QFQ 权威引擎 `19/0/0/0`，18 个黄金场景不变；
Java `clean compile` 与 `git diff --check` 通过。临时 PostgreSQL 端口、进程和目录
残留均为 0。

本阶段不调用 Provider、不读取 Token、不生成新 acceptance ID、不执行新的 F1F-B2，
也不修改永久数据库。当前 ID 保持永久封存，累计真实请求保持 29，继续保持：

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

## 2026-08-04 悬挂恢复与打包 E2E 收口

SYSTEM_KNOWLEDGE 修复合入后的真实授权 ID
`F1FB2_20260804_SK_POSTFIX_70E8249A333E` 在第一次 Provider 调用前进程退出，留下
`RUNNING`、Provider 调用 0、重试 0、无 capture batch、无 `finalized_at` 的悬挂记录。
本阶段没有复用该 ID 执行 Runner，而是新增正式单 ID 恢复入口：只接受上述精确状态，
以专用事务管理器、`REQUIRES_NEW` 和行锁写入
`INTERRUPTED / RECOVERY / STRANDED_RUNNING_PROCESS_EXITED`，并发只有一个状态转换获胜。

Runner/Executor 的终结边界同步覆盖 Provider 组件初始化、第一次调用前失败、执行期
`Throwable`、组件关闭、线程守卫、输出审计基础设施和首次终态写入失败；所有路径都必须
尝试终结或留下可恢复的安全证据。没有放宽一次性 ID、三请求、零重试、事务、typed fact、
SYSTEM_KNOWLEDGE 或输出审计门禁。

最终打包进程级 E2E 使用全新临时 PostgreSQL 16.13、主 V1—V13、独立治理 V14、
`E2E_DRY_RUN` JAR 与 Fake Provider。成功链固定为
`AUTHORIZED → RESERVED → RUNNING → SUCCEEDED_CANDIDATE`；三 Endpoint 调用 3、重试 0，
typed fact 为 1/1/1，SYSTEM_KNOWLEDGE、formula-only QFQ、输出审计、非空 batch/证据摘要/
digest/终结时间均通过。治理 history 已 COMPLETE 时跳过 Flyway 重放，避免把 JDBC 标识写入
审计窗口。临时端口、进程、目录、授权与构建产物残留均为 0。

该证据来源为 TEST，不写真实 `PASSED`，不提升 operational。真实 Provider 新增调用为 0，
Tushare 累计真实请求保持 29。Codex 执行环境无法提供恢复入口要求的原生可见
`System.console()`，因此永久库恢复尚未执行，悬挂 ID 保持最后已知 `RUNNING`；继续保持完整
F1 技术阻断及四项正式门禁。

## 2026-08-04 最终真实 PASSED 与退出码收口

上述末段是 E2E-CLOSEOUT 提交时的历史状态。随后用户在原生交互 Console 中完成正式恢复，
`F1FB2_20260804_SK_POSTFIX_70E8249A333E` 已单向终结为
`INTERRUPTED / STRANDED_RUNNING_PROCESS_EXITED`，恢复应用成功，Java/脚本退出码均为 0，
Provider 调用 0，原 ID 永久不可复用。

最终真实 ID `F1FB2_20260804_FINAL_69B5B6AF9814` 的权威持久化回读为：

```text
AUTHORIZED → RESERVED → RUNNING → SUCCEEDED_CANDIDATE → PASSED
capture_batch_id=4
provider_call_count=3
retry_count=0
outputAudit.clean=true
captureComplete=true
evidence_summary_json=NON_EMPTY
evidence_digest=NON_EMPTY
finalized_at=NON_NULL
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=32
```

本收尾阶段遵守无密码、无 Token 约束；尝试使用 `psql -w` 进行只读复核时在认证前因
`no password supplied` 退出，未连接永久数据库，也未执行 SELECT、DDL 或 DML。因此本记录
登记用户提供的权威真实回读结果，不虚构 Codex 独立数据库回读。

持久化状态已经是 PASSED，但原 Runner 进程仍返回 20。精确根因是 Java try-with-resources
先计算并暂存成功返回值，随后 `ExecutionHandle.close()` 的资源关闭异常覆盖该返回值并进入
外层拒绝分支。最小修复仅在 `captureComplete=true`、输出审计 clean、`successfulExit=true`，
且决策精确为 `PASSED / REAL_CONTROLLED_ACCEPTANCE_PASSED / operational=true / blockers=[]`
时，把持久化 PASSED 后的关闭异常安全记录为脱敏 warning 并返回 0。FAILED、INTERRUPTED、
审计不完整或不干净仍返回非 0；既有 PASSED 数据不重写，也不重新运行真实验收。

当前治理投影为：

```text
CONTROLLED_ACCEPTANCE_STATUS=PASSED
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=true
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

完整 F1 十项技术阻断继续保持；生产/正常业务库、scheduler、Agent、回测、Shadow、Day 002、
F2B、F3、3A-R3B-1、3B 和交易均未授权启动。本收尾新增 Provider 调用为 0。

退出码与治理投影定向测试为 `28/0/0/0`，其中 Runner 16 项、受控验收资格与完整 F1
阻断回归 12 项；Java `clean compile` 与 `git diff --check` 通过。测试没有连接数据库、读取
Token 或调用 Provider。
