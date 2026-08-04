# 3A-R3B-F1F-B2-E2E-CLOSEOUT：悬挂恢复与全链路 Dry Run 收口

## 基线与范围

- 集成基线：`4b897b0768957175f1b03b440a9850fe2940c1b3`
- 任务分支：`codex/1.4.0-stage-3ar3b-f1f-b2-e2e-closeout`
- 永久库只处理悬挂 ID `F1FB2_20260804_SK_POSTFIX_70E8249A333E`；不得修改市场事实。
- 本阶段真实 Provider 新增调用为 0，不读取 Tushare Token，不签发真实授权，不执行新一轮真实 F1F-B2。

## 悬挂恢复合同

正式恢复入口必须先验证 Runner 进程不存在，再用专用数据库安全 Console 读取密码。Repository
在 `REQUIRES_NEW` 事务中对单一 acceptance 行执行 `FOR UPDATE`；只有同时满足
`RUNNING`、调用数 0、重试 0、无 capture batch 的记录才可单向转换为
`INTERRUPTED`。转换写入 `finalized_at`、`failure_stage=RECOVERY`、
`safe_failure_reason=STRANDED_RUNNING_PROCESS_EXITED` 和转换历史。并发恢复只允许一个赢家；
终态记录返回未应用，不能恢复成 `AUTHORIZED` 或 `RESERVED`。

## RUNNING 终态合同

`RUNNING` 独立提交后的 Provider 计数器初始化、Provider/F1E、readback、组件关闭、线程守卫、
输出审计和最终状态写入均位于 `Throwable` 终结边界内。首次终结写入失败必须再尝试
`INTERRUPTED`；审计基础设施在返回 handle 后失败时，Runner 仍持有恢复引用。守卫
`TUSHARE_DEDICATED_RESEARCH_TRANSACTION_REQUIRED`、一次性 ID、三请求和零重试合同均不放宽。

## E2E Dry Run 合同

- 构建模式：`E2E_DRY_RUN`；产物仍以专用 Runner 为 `Start-Class`。
- 授权状态/用途/来源：`E2E_DRY_RUN` / `F1F_B2_E2E_DRY_RUN` / `TEST`。
- 数据库：全新临时 PostgreSQL 16.x，专用角色与数据库，主 V1—V13、独立治理 V14。
- Provider：进程内 Fake Gateway，仅允许 `daily → adj_factor → trade_cal`，精确 3 次，重试 0。
- 成功链：`AUTHORIZED → RESERVED → RUNNING → SUCCEEDED_CANDIDATE`。
- 必须经过正式事务、typed fact 1/1/1、SYSTEM_KNOWLEDGE 回读、公式级 QFQ、输出审计和终态写入。
- TEST 来源禁止写 `PASSED`，禁止投影 `REDUCED_RESEARCH_OPERATIONAL_READY=true`。
- 临时端口、进程、目录、授权草案和 E2E 构建产物在验证后精确清理。

## 验收

1. 单 ID 并发恢复测试及永久悬挂记录安全终结；
2. Runner/Executor/Fake Gateway 定向测试；
3. 临时 PostgreSQL V1—V14 的事务、readback、幂等链尾、回滚和恢复测试，`Skipped=0`；
4. 从隔离源码构建的最终 JAR 作为新进程完成 E2E Dry Run；
5. QFQ 权威 19 项及 18 个黄金场景不变；
6. Java clean compile、PowerShell 语法和 `git diff --check` 通过；
7. 真实 Provider 新增调用 0，四项正式门禁及 operational 状态不变。
