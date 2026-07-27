# 阶段 3A-1：受控影子运行与就绪度观测基础 V1

## 1. 状态

状态：**任务分支实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交验收，未合入。**

- 冻结基线：`954959f5832d01ba1f7211d3e6ebbd8c93feab22`
- 任务分支：`codex/1.4.0-stage-3a1-shadow-readiness-v1`
- 目标提交：`feat(agent): complete controlled shadow run v1`
- ChatGPT 实际 Git 提交验收：尚未进行
- 是否合入：否
- `enabled`：默认 `false`
- `scheduler-enabled`：默认 `false`
- 完整 3A：未完成
- 3B：未开始
- 完整任务书：[3a1-controlled-shadow-readiness-v1.md](tasks/3a1-controlled-shadow-readiness-v1.md)

当前项目事实以 [CURRENT_STATE.md](CURRENT_STATE.md) 为唯一权威来源。本文件不得在
ChatGPT 基于实际 Git 提交验收前写为 PASS，也不得将 3A-1 写成完整 3A 已完成。

## 2. 目标与冻结边界

本阶段为已经合入的 2I 确定性总控建立受控、可暂停、可审计的真实影子运行控制面，
记录决策分布、数据不足原因、失败、缓存、漂移与人工复核。影子运行的成功标准是可重复
运行并解释真实结果和真实不足，不要求产生 `PASS_TO_MANUAL_REVIEW` 或收益表现。

冻结契约：

- `SHADOW_RUN_CONTROL_V1`
- `SHADOW_SELECTION_V1`
- `SHADOW_OUTCOME_SNAPSHOT_V1`
- `SHADOW_REVIEW_V1`
- `SHADOW_METRICS_V1`
- 团队规则：`1.4.0-stage-2i-chief-decision-v1`

影子 item 只通过既有 Java `AgentTaskService` 创建真实 2I 任务。Python 只执行已经冻结的
本地规则，不接收选择、持久化或外部访问职责。阶段没有修改六个专业规则、2I 权重或
分类，没有第七个 run，也没有自动交易、业务事实写入、行情同步、公告摄取或全市场扫描。

## 3. 已知就绪度限制

- 2F 要求查询时刻达到请求交易日上海日终，2H 要求请求日期等于上海当前自然日；普通
  时刻可能无法同时形成两类完整输入；
- 普通行情来源缺少可靠 `sourceRevision` 时，2F 可以返回
  `BACKTEST_SOURCE_REVISION_UNVERIFIABLE`；
- 公告 capture 不完整或过期时，2G securityEvents 可以不可用；
- 上述结果必须按真实结构化 error 保存为不足，不得绕过、伪造 Clock、重算权重或转换为
  `WATCH`、人工复核通过或收益判断。

## 4. V11 数据库事实

只新增 `V11__agent_shadow_run_foundation.sql`，不修改 V1 至 V10：

- `agent_shadow_batches`：冻结契约、触发方式、日期、规则、选择 Hash、配置、进度、
  分布计数、取消状态和时间；
- `agent_shadow_items`：冻结批次内 symbol、来源、Agent task 关联、真实结果快照、
  结构化 reason、contextHash、耗时和漂移；
- `agent_shadow_reviews`：append-only 人工复核和 supersede 链；
- `agent_tasks.trigger_type` 数据库约束增加 `SHADOW`，旧四类触发保持兼容。

批次状态为 `QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED/CANCELLED`，item outcome 为
`DETERMINED/INSUFFICIENT/FAILED/CANCELLED`。运行期只允许生命周期字段合法推进；批次
或 item 进入终态后，核心身份、symbol、Agent task 引用、final outcome、快照和漂移由
数据库触发器保护。终态批次不能恢复运行或追加 item。review 禁止
`UPDATE/DELETE/TRUNCATE`，更正只能追加并引用被取代记录，且只能复核终态 item。

## 5. 选择、运行窗口和任务关联

EXPLICIT 模式对 1 至 20 个六位 symbol 去重并按 symbol 升序，不隐式增加候选。AUTO
模式先取当前模拟持仓，按 market value 降序、symbol 升序；再取最新已完成扫描中的
`eligible=true` 候选，按 rank、symbol 升序；去重后按 maxSymbols 截断。默认 10，
硬上限 20，来源引用和稳定 selection Hash 随批次冻结。

配置默认完全关闭；scheduler 必须同时满足 `enabled=true`、`scheduler-enabled=true`、
工作日、`Asia/Shanghai` 安全窗口和无运行冲突才允许创建 AUTO 批次。交易日历不能可靠
证明时不会宣称正式交易日判断。最大并发固定受配置约束且默认 2；测试使用固定 Clock。

每个影子 Agent task 固定：

```text
triggerType=SHADOW
requestedBy=shadow:<batchId>
forceRefresh=false
executionMode=LOCAL_RULES
ruleVersion=1.4.0-stage-2i-chief-decision-v1
```

runner 不直接调用 Python 分析接口，不直接写 `agent_runs` 或 `agent_decisions`。同一完成
CacheKey 由既有 Agent completed cache 复用，item 明确记录是否新建 task 和是否命中缓存。

## 6. Outcome、reason、漂移和指标

runner 轮询既有 Agent task 终态，读取 finalDecision 和固定六 run，生成不可变
`SHADOW_OUTCOME_SNAPSHOT_V1`。`INSUFFICIENT_DATA` 的 reasonCode 只从六 run 的结构化
errors 按 DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS、STRATEGY_BACKTEST、
ANNOUNCEMENT_RISK、POSITION_RISK 顺序去重提取；没有结构化原因时使用
`SHADOW_INSUFFICIENT_WITHOUT_REASON_CODE`，不解析 summary。

漂移只比较同 symbol、同 ruleVersion 最近上一条终态 item，覆盖：

- contextHash；
- finalDecision、score 和 confidence；
- 六 run 的 status、gate、decision、score 和 confidence；
- veto 集合；
- reasonCodes。

`changedAgents` 保持固定六 run 顺序；找不到上一条时不伪造零漂移，不跨 ruleVersion
比较，不把收益或 summary 文本作为核心漂移。

只读指标从数据库事实即时计算 batch/item/outcome/finalDecision、DQ 阻断、veto、cache
hit、primary reason、run 状态/error、p50/p95 duration、context/decision 变化率、
score/confidence 平均绝对变化、review label 和未复核数量，不维护可漂移的手工缓存。

## 7. 取消、熔断、API 和工作台

取消只设置 `cancellationRequested`，停止启动新 item，等待已启动 Agent task 终结，并将
未启动 item 标记为 `CANCELLED`；不强制终止 Agent task，不删除结果。Python 连续不可达、
item 连续创建失败、超过一半启动失败、数据库写入失败或 runner 中断只熔断当前批次，
不改变全局配置且不自动重试。

API 提供批次创建/列表/详情/item/取消、metrics、drift 以及 review 新增/列表；不提供
review 删除或改写接口。独立 `/agent-shadow` 工作台显示开关、手动 EXPLICIT/AUTO、
批次进度、结果与 reason 分布、cache、延迟、item/task 跳转、六 run 摘要、drift 和
append-only 复核，并固定显示“影子运行不会触发交易或修改持仓”。页面不包含订单、仓位
建议、收益或 scheduler 自动开启功能。

## 8. 受控真实试运行

Codex 本地测试在 scheduler 关闭状态下，以 EXPLICIT 模式对去重排序后的三只证券执行：

- 随机隔离 Schema 从 V1 顺序迁移至 V11；
- 使用真实 Java Agent task 系统、真实 Python 服务和真实 PostgreSQL；
- 每个 symbol 只有一个 item，全部关联 `TriggerType=SHADOW` 的真实六 run Agent task；
- 首批 3 项全部终结且无失败，真实 outcome 仅允许 `DETERMINED/INSUFFICIENT`；
- 相同输入第二批 3 项全部命中 completed cache，复用 taskId，不重复调用 Python；
- 记录上一 item、零变化漂移及 append-only review/supersede；
- 六张模拟账户业务表、`daily_bars`、PIT 行情观察表和公告观察表逐行指纹不变；
- public 数据/结构基线不变，随机 Schema 精确删除。

本次试运行没有 AKShare 摄取、行情刷新、全市场扫描或 Portfolio 写路径。结果只证明影子
控制面和真实不足观测可运行，不证明策略有效性或收益。

## 9. Codex 本地测试证据

以下均为 Codex 本地执行证据，不是 GitHub Actions CI。

| 测试组 | 运行/失败/错误/跳过 | 说明 |
|---|---:|---|
| `quant-core` 全量 | `4/0/0/0` | 核心策略与回测兼容 |
| 3A-1 Java 定向 | `15/0/0/0` | 选择、AUTO 工作日与可靠日历门禁、窗口、状态机、runner、outcome、metrics、API 与迁移契约 |
| Python `compileall` | 通过 | `quant-ai/app` 与 tests |
| Python完整 unittest | `123/0/0/0` | `SHADOW` 输入兼容及 2B 至 2I 规则回归 |
| 安全非数据库 `quant-server` 全量 | `376/0/0/41` | 真实 Python HTTP 已运行；41 项为外部 PostgreSQL/AKShare 环境门禁跳过 |
| V1 至 V11 真实 PostgreSQL/Java/Python 受控试运行 | `1/0/0/0` | 三 symbol、真实六 run、缓存、漂移、复核、只读和清理；`Skipped=0` |
| V1 至 V11 真实兼容矩阵 | `29/0/0/0` | 2D、2E、2F、2G、2H、2I 真实 PostgreSQL/Python；`Skipped=0` |
| 2G 真实 AKShare Live Gate | `1/0/0/0` | V1 至 V11、真实 Provider、摄取、Hash、append-only 与清理；`Skipped=0` |
| Vue 类型检查与生产 build | 通过 | `vue-tsc -b` 与 `vite build` |
| `git diff --check` | 通过 | 最终增量无空白错误 |

真实组均使用随机隔离 Schema，测试内验证 public 数据和结构指纹前后不变并精确删除
Schema；未执行 Flyway repair/clean。安全全量中的 41 项跳过不能冒充真实闭环。

## 10. 完成边界

3A-1 完成只表示受控影子运行与就绪度观测技术基础在任务分支实现并完成本地验证。
完整 3A 至少仍需：

- 不少于 20 个有效观察日；
- 不少于 200 个 shadow item；
- 覆盖确定、不足、阻断、veto 和失败；
- 主要 reasonCode 的人工复核；
- 持续验证业务表无写入；
- 正式观察报告；
- ChatGPT 验收及用户决定是否进入 3B。

本阶段不伪造长期运行历史，不开启 scheduler，不开始长期观察或 3B；任务分支未合入。
