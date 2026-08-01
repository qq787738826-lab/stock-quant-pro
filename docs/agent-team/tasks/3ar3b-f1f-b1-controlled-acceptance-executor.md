# 3A-R3B-F1F-B1：Tushare受控验收可信执行机制

## 目标与基线

- 冻结集成基线：`f68d84403ebb82babe92a1cb0f78d845ed39547a`。
- 任务分支：`codex/1.4.0-stage-3ar3b-f1f-b1-controlled-acceptance-executor`。
- 本阶段只实现并离线验证未来 F1F-B2 所需的可信执行机制；不执行真实受控验收。
- `CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN`，`REDUCED_RESEARCH_OPERATIONAL_READY=false`。

## 交付合同

1. 独立 V14 治理迁移只允许受控验收数据库通过独立 location 和独立
   `flyway_controlled_acceptance_history` 显式加载；主历史仍精确为 V1—V13。
   V14 脚本自身还必须校验数据库身份、Schema、主历史和治理历史，误把两个 location
   合并扫描时必须在建表前失败并回滚。
2. acceptance ID 由数据库主键唯一约束，`AUTHORIZED → RESERVED → RUNNING` 在独立事务中
   单向持久化，`RUNNING` 必须先于 Provider 委托；状态和历史由 SQL 不变量共同约束。
3. 失败、终态和崩溃恢复后的 ID 均不可复用。恢复器只能封存已经预占但未完成的执行，
   不得抢占尚未预占的 `AUTHORIZED`，也不得自动重试或补跑 Endpoint。
4. 构建证明只接受当前执行器 JAR、其 MANIFEST 和相邻 sidecar 的交叉核对，绑定完整集成
   SHA、集成分支名、远程集成引用、干净已跟踪工作区和实际 JAR SHA-256。TEST 构建证明
   永远没有治理资格。
5. 显式执行器不是 Bean、Controller、Configuration、Runner 或 scheduler；边界证明还拒绝
   `@Service/@Component/@Repository/@Bean/@Scheduled` 和后续阶段依赖。
6. 在读取敏感材料前先安装输出隔离；执行边界内独占捕获 stdout、stderr、全部当前
   Logback logger/appender 和嵌套/抑制异常。持久化只保留命中类别与安全位置，不保留原文。
7. F1E 捕获事务提交后才能回读；回读必须在无活动事务时重新校验专用数据库身份，并从
   V13 envelope 和 raw/factor/calendar typed 表逐项核对批次、事实 ID、证券、交易所、日期、
   开市状态、`REGULAR` session 及微秒级 SYSTEM_KNOWLEDGE 时间。捕获连接 PID 与回读连接
   PID 分别记录，不伪称跨事务使用同一连接。
8. `TEST` 成功永远只生成 `SUCCEEDED_CANDIDATE`；只有内部评估器对
   `REAL_CONTROLLED_ACCEPTANCE` 的严格 JSON、完整历史、构建证明和全部证据重验通过，先
   持久化 `PASSED` 后重新加载再次验证，才能投影真实通过。
9. Provider 尝试数以所有 Endpoint 共享 limiter 的执行前后总计数差证明；失败也必须保存
   已消耗尝试数。唯一范围仍为一个证券、一个开市日、三个 Endpoint、精确三次和零重试。

## 写入与恢复顺序

```text
静态范围/构建证明
→ 数据库与 V14 前置守卫
→ AUTHORIZED 唯一预占为 RESERVED
→ 独立事务持久化 RUNNING
→ 安装输出隔离并登记敏感材料
→ 三次 Provider 委托
→ F1E 单事务捕获提交
→ 无活动事务的 typed fact 回读
→ SUCCEEDED_CANDIDATE
→ REAL 来源内部资格重验
→ PASSED 持久化
→ 重新加载并再次重验
```

任一步失败只能进入对应失败终态或 `INTERRUPTED`。不得在 `RUNNING` 持久化前调用 Provider，
不得以事务回滚掩盖已发生的网络尝试，也不得从失败或候选状态直接伪造 operational ready。

## 信任模型与已知边界

- JAR/sidecar SHA-256 和数据库证据摘要用于检测意外变更与普通应用路径篡改，不是由外部
  根密钥签名的远程证明。能够同时改写 JAR、sidecar、Git 引用或完整数据库状态/历史/证据
  的特权操作系统或数据库管理员不在本阶段防护模型内；F1F-B2 仍需专用账号、最小权限和
  用户一次性授权。
- 输出审计覆盖边界内 stdout/stderr、当前 Logback 拓扑及同步完成的异常/子线程输出；它不
  证明任意外部文件写入、其他未桥接日志框架或边界结束后仍运行的脱离线程无泄漏。因此
  F1F-B2 必须使用最小专用进程、禁止未等待后台任务并保持输出目标白名单。
- V14 是验收治理结构，不得进入正常应用默认 Flyway location；V1—V13 文件不修改。

## 验收边界

- Endpoint 精确为 `daily/adj_factor/trade_cal`，一个证券、一个已知开市日、三次调用、零重试。
- 临时 PostgreSQL 必须为新实例，主历史加载 V1—V13，治理历史单独 baseline 13 后加载
  V14；测试后停止并删除端口和目录。
- 不调用 Tushare/iFinD，不检查 Token，不访问既有或正常业务数据库。
- 不改变完整 F1 十项技术阻断、四项正式门禁、scheduler、Shadow、Day 002、F2B/F3、
  3A-R3B-1 或 3B。

## 状态输出

任务分支完成后仅表示 F1F-B1 机制待实际 Git 复验，尚未合入；F1F-B2 尚未授权或执行。
真实 Provider 请求新增数为 0，Tushare 累计仍为 20，iFinD 仍为 0。
