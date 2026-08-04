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

最终运行证据在本阶段验证完成后登记；在证据登记前不得宣称收口 READY。

## 状态保持

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

本阶段不启动 scheduler、Agent、回测、Shadow、Day 002、F2B、F3、3A-R3B-1 或 3B。
