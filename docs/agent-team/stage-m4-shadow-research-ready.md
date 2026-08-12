# M4_SHADOW_RESEARCH_READY 阶段证据

## 阶段定位与边界

- 长期任务分支：`codex/1.4.0-m4-shadow-research-ready`。
- 冻结集成基线：`a8f82834878549051ce2300b08dfdb4ea188c202`。
- 真实 smoke 代码、构建和运行资产 HEAD：`56f8bea91495381ca96646698fe1dcb105d592eb`。
- M4 只交付研究型 Shadow/Paper 闭环；不连接券商，不读取交易密码，不产生真实订单，
  不接触真实资金，也不启动实盘或自动交易。
- 真实开发预算固定为 Tushare 最多 20 次、阿里云百炼最多 CNY 10.00；请求重试为 0，
  redirects 为 NEVER，预算账本在 Provider 前 fail-closed。

## 交付能力

- `SHADOW_RESEARCH_RUNTIME_V1`：以 `researchAsOf` 冻结 M1 as-of 数据集，依次调用 M2
  策略研究和 M3 七 Agent；保存数据、策略、Prompt、模型和研究指纹。
- `SHADOW_SCHEDULER_V1`：默认关闭的日级收盘后调度，固定 Asia/Shanghai 17:20，
  只在已知开市日、安全窗口内向 Resident Broker 原子提交固定无秘密请求。
- `SHADOW_SNAPSHOT_V1`：每个 `tradeDate + researchSlot + strategyVersion` 仅允许一个
  FROZEN 结果；报告、Evidence、Critic 和 Recommendation 冻结后数据库触发器禁止改写、
  删除或 truncate。
- `PAPER_PORTFOLIO_V1`：复用 M2 long-only 交易规则，确定性处理下一合法开盘、A 股整手、
  T+1、仓位约束、手续费、印花税和方向性滑点；Agent 只能给研究建议，不能修改账本。
- `SHADOW_REPLAY_V1`：固定历史时钟的 5/20/60 日 Replay；研究信号使用历史 as-of，
  Paper 成交只能发生在下一交易日开盘。
- `SHADOW_OUTCOME_V1`：D1/D5/D20 未来观察只追加，不修改冻结结论；事实 `knownAt` 晚于
  评价时点时拒绝。
- `SHADOW_UI_V1`：只读展示运行历史、时间边界、七 Agent 结论、Evidence、Critic、
  推荐、Paper 订单/成交/组合和后续 Outcome；不提供真实下单或可变账户入口。

## 时间、幂等与恢复不变量

- `researchAsOf` 不早于目标交易日收盘；数据交易日不得晚于目标日，所有 M1 事实的
  `knownAt` 不得晚于 `researchAsOf`。
- `signalTime` 不早于 `researchAsOf`；`paperExecutionTime` 必须严格晚于 signal，且只接受
  M2 定义的下一合法开盘时点。同日收盘数据不会以同日价格成交。
- 同一 Shadow slot 重复执行回读相同冻结事实，不产生第二份 Recommendation、Paper order
  或 fill；scheduler claim 和 paper order 都有独立唯一键。
- 进程中断留下的过期 RUNNING 可单向封存为 INTERRUPTED，再创建新 attempt；FROZEN、FAILED、
  INTERRUPTED 历史均不可回写。模型失败不会产生快照、订单或成交。
- Evidence/OOS 不足时冻结 `INSUFFICIENT_EVIDENCE`、空证券推荐和零敞口；空仓是合法结果。

## 离线验收证据

- Java 最终定向回归 `31/0/0/0`；真实 PostgreSQL 16 隔离组为 server `13/0/0/0`
  与 core `3/0/0/0`，覆盖 Paper 会计、Shadow 状态、不可变快照、空仓、调度幂等、恢复、
  模型失败、Outcome 和 5/20/60 日 Replay。后续新增的第 4 项时序回归也单独通过；临时活动
  状态、端口和目录残留均为 0。
- Broker M4 严格协议覆盖固定证券/Endpoint/预算/模型/URL、未知字段、命令注入、路径和预算
  错配拒绝，结果为 `15/0/0/0`；Fake 协议不会读取真实凭据或调用真实 Provider。
- 打包 Fake M1→M2→M3→M4 E2E 使用正式 M4 Start-Class、隔离构建证明和临时 PostgreSQL
  V1→V15，结果为 PASS；固定 Fake Tushare 6 次、Fake Model 13 次、真实 Provider 0，
  RUNNING 状态和临时资源残留均为 0。
- Vue 类型检查和生产构建通过（2297 modules）；PowerShell 5.1 语法、build proof、正式 JAR
  Start-Class 和 `git diff --check` 均通过。

## 真实 Shadow smoke

- 正式 JAR：`quant-server/target/quant-server-1.3.1-m4-shadow-research-runner.jar`；SHA-256
  `7bc641a03cb2d104560536095b71ab9ff2e8d5f99004611c28c6e9be3e4f1980`，Start-Class 为
  `TushareM4ShadowResearchManualRunner`，sidecar/build proof 与 HEAD 绑定通过。
- 成功 request 为 `SQHB_20260812T061537Z_38B78FF54D39`，executionId 为
  `M4SHADOW_20260812T061537Z_38B78FF54D39`，Broker 状态链终结于 `SUCCEEDED/COMPLETED`；
  真实运行约 87.5 秒，trade date 为 `2026-08-11`，as-of 数据窗口为
  `2026-07-12` 至 `2026-08-11`。
- Tushare 对两证券执行 `daily/adj_factor/trade_cal` 各一次，共 6 次、retry 0；M4 阶段使用
  `6/20`，项目累计真实 Tushare 请求由 55 增至 61。
- 百炼 `qwen3.7-plus` 完成 13 次真实模型调用：input/output/reasoning/total tokens 为
  `22447/3806/0/26253`，保守记账 CNY `0.829540000000`，低于 CNY 10.00 阶段门限；API
  未返回账单实际金额，13 次调用的 usage 均已脱敏持久化。
- 七个角色全部执行，工具调用 4 次，Evidence 11 条；typed fact、SYSTEM_KNOWLEDGE、
  formula-only QFQ、无未来数据泄漏和输出审计均通过。冻结 snapshot fingerprint 为
  `c4a1c23b31e9bcb23240615a28724116ed5ce67dd77548c65620ad2b83fd4fe0`。
- Critic 识别 `FUTURE_DATA_RISK`、`OVERFITTING_RISK`、`UNSUPPORTED_CLAIM` 和
  `PIT_LINEAGE_LIMITATION`；最终按门禁冻结为 `INSUFFICIENT_EVIDENCE`、confidence 0、
  MODERATE risk、空推荐。Paper 初始现金和权益均为 1,000,000，订单/成交均为 0、收益为 0；
  这证明证据不足时系统选择合法空仓，而不是制造交易。
- 首个已终结 request `SQHB_20260812T060740Z_5CF57460A909` 在 Provider 前因 Windows
  PowerShell 5.1 不支持 `[decimal]::Min` 而失败，真实调用为 0；修复为显式 decimal 比较并增加
  回归后才创建唯一成功 request。两个 request 均已终结，无 pending/claimed/RUNNING 残留。

该 smoke 全程为 RESEARCH/PAPER，不连接券商、不产生真实订单或资金变动，也不证明 alpha。

## 已知限制与后续边界

- Tushare 仍为个人缩减研究、SYSTEM_KNOWLEDGE 和 formula-only QFQ；完整公司行动、
  Provider PIT、稳定证券身份与完整 F1 十项技术证据缺口不因 M4 改变。
- V1 日级 scheduler 默认关闭。持续运行建议在 M4 用户验收后显式开启，并由既有 Resident
  Broker、预算门禁和唯一 slot 约束运行；本阶段不会自动开启持续真实调用。
- M4 验证软件在真实时间下冻结研究和跟踪 Paper 结果，不验证策略盈利能力。长期效果评估、
  漂移和 Shadow 统计属于后续 M5；真实订单、资金和券商连接继续禁止。
