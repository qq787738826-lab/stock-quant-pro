# 3A-R3B-F1F-B2-E2E-CLOSEOUT 阶段记录

## 输入事实

集成基线为 `4b897b0768957175f1b03b440a9850fe2940c1b3`。真实授权 ID
`F1FB2_20260804_SK_POSTFIX_70E8249A333E` 在 Runner 退出后仍为 `RUNNING`，已知
`provider_call_count=0`、`retry_count=0`、`capture_batch_id=NULL`、
`finalized_at=NULL`，且不得再次执行。

## 根因与修复

实际调用图存在四个终态空窗：Provider 调用计数基线在 `try` 外读取；执行期仅捕获
`Exception`；执行 handle 创建后组件关闭/线程守卫异常只关闭资源；输出审计基础设施在 action
返回后失败时 Runner 丢失 handle。修复把上述路径纳入 `Throwable` 终结边界，保留 handle
恢复引用，并在首次终结失败时尝试单向 `INTERRUPTED`。没有删除或伪造事务守卫。

新增正式单 ID 恢复入口。恢复只接受零调用、零重试、无 batch 的悬挂 `RUNNING`，以行锁和
`REQUIRES_NEW` 保证并发唯一成功，写入 `RECOVERY / STRANDED_RUNNING_PROCESS_EXITED`，
原 ID 永久封存。

## E2E Dry Run

`E2E_DRY_RUN` 构建与授权是类型化 TEST 路径，不读取真实 Token 或数据库密码。它通过与正式
执行相同的专用 Runner、单一 AuthorizationFile、手工组件装配、事务、回读、Evaluator 与
输出审计运行；网络边界替换为固定 Fake Gateway。成功只能落到
`SUCCEEDED_CANDIDATE / TEST_ONLY_CANDIDATE`，不能写 `PASSED` 或改变 operational。

打包 JAR 进程级 Dry Run 已在全新 PostgreSQL 16.13 临时实例完成。主历史精确 V1—V13，
独立治理历史精确 V14；在代码与治理提交
`dcb4fc700acd8a8caacca93b6503e3edd7b2ef85` 上完成的最终 TEST acceptance ID 为
`F1FB2_E2E_20260804043903_3D7B3028A3`，状态链为
`AUTHORIZED → RESERVED → RUNNING → SUCCEEDED_CANDIDATE`。Fake Provider 精确三次、重试 0，
typed fact 回读 1/1/1，SYSTEM_KNOWLEDGE、formula-only QFQ、非空 capture batch、证据摘要、
digest、输出审计与 `finalized_at` 均通过；`PASSED` 记录为 0，悬挂 RUNNING 为 0。构建产物
SHA-256 为 `e10873ab0b9ebe98aa6aa3c51ad5e480780cf5c6c4dbbc565ea67f597c71c966`；临时端口
`59560`、进程、目录、授权文件及 E2E 构建产物残留均为 0。

调试过程中发现治理 history 已完整时仍重放 Flyway，INFO 日志中的 JDBC 标识被输出审计
正确拒绝。最终修复是在 COMPLETE 回读后跳过 Flyway 的构造与执行，而不是放宽审计。
正式恢复脚本还修复了 Windows PowerShell 5.1 对 Maven UTF-8 classpath 中中文用户目录的
错误解码；恢复结果无论成功或安全拒绝都只保存脱敏状态/reason。

## 永久库恢复执行状态

后续由用户在原生交互 Console 中执行正式恢复入口，永久库中的目标 ID 已按合同单向终结：

```text
CONTROLLED_ACCEPTANCE_STATUS=INTERRUPTED
provider_call_count=0
retry_count=0
capture_batch_id=NULL
reason=STRANDED_RUNNING_PROCESS_EXITED
recoveryApplied=true
JAVA_EXIT_CODE=0
SCRIPT_EXIT_CODE=0
```

恢复没有调用 Provider，原 ID 永久不可复用。恢复执行发生在本阶段代码合入后；上述结果替代此前
因 Codex 终端无安全 Console 而保留的历史 `RUNNING` 状态。

## 后续真实验收结果

最终真实 acceptance ID `F1FB2_20260804_FINAL_69B5B6AF9814` 的权威回读结果为：

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
```

该结果把缩减研究受控验收状态投影为 PASSED；Tushare 累计真实业务请求为 32。完整 F1 十项
技术阻断、生产/正常业务库、scheduler、Agent、回测、Shadow、交易及后续阶段边界不变。

## 状态保持

```text
CONTROLLED_ACCEPTANCE_STATUS=PASSED
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=true
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

本阶段不启动 scheduler、Agent、回测、Shadow、Day 002、F2B、F3、3A-R3B-1 或 3B。
