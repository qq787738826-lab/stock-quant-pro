# 3A-R3B-F1F-B1：Tushare受控验收可信执行机制

## 目标与基线

- 冻结集成基线：`f68d84403ebb82babe92a1cb0f78d845ed39547a`。
- 任务分支：`codex/1.4.0-stage-3ar3b-f1f-b1-controlled-acceptance-executor`。
- 本阶段只实现并离线验证未来 F1F-B2 所需的可信执行机制；不执行真实受控验收。
- `CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN`，`REDUCED_RESEARCH_OPERATIONAL_READY=false`。

## 交付合同

1. 独立 V14 治理迁移只由验收数据库显式加载；默认应用迁移仍止于 V13。
2. acceptance ID 由数据库主键唯一约束，`AUTHORIZED → RESERVED → RUNNING` 在独立事务中单向持久化，`RUNNING` 必须先于 Provider 委托。
3. 状态转换由 SQL trigger 白名单约束；失败、终态和恢复后的 ID 均不可复用。
4. 构建证明绑定完整 Git SHA、干净的已跟踪工作区、产物 SHA-256、构建时间、Java/模块/执行器/规则版本，不接受普通运行参数声明 SHA。
5. 显式执行器不是 Bean、Controller、Runner 或 scheduler；调用图不包含 Agent、回测、Shadow、交易或后续阶段。
6. 实际 stdout、stderr、日志和异常链在执行边界内捕获；持久化只保留命中类别和安全位置，不保留敏感原文。
7. SYSTEM_KNOWLEDGE 证明必须从 V13 数据库表回读三类 observation，并在同一事务连接上按微秒精确核对。
8. `TEST` 成功永远只生成 `SUCCEEDED_CANDIDATE`；只有内部评估器对 `REAL_CONTROLLED_ACCEPTANCE` 全证据重验后才能形成 `PASSED`。
9. 重启恢复只把不确定执行终结为 `INTERRUPTED`，不自动重试、不补跑 Endpoint；重跑必须新授权和新 ID。

## 验收边界

- Endpoint 精确为 `daily/adj_factor/trade_cal`，一个证券、一个已知开市日、三次调用、零重试。
- 临时 PostgreSQL 必须为新实例，加载 V1—V13 及独立 V14，测试后停止并删除端口和目录。
- 不调用 Tushare/iFinD，不检查 Token，不访问既有或正常业务数据库。
- 不改变完整 F1 十项技术阻断、四项正式门禁、scheduler、Shadow、Day 002、F2B/F3、3A-R3B-1 或 3B。

## 状态输出

任务分支完成后仅表示 F1F-B1 机制待实际 Git 复验，F1F-B2 尚未授权或执行。真实 Provider 请求新增数为 0，Tushare 累计仍为 20。
