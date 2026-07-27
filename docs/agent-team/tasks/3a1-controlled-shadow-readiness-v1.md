# 3A-1 受控影子运行与就绪度观测基础 V1 任务书

## 1. 阶段身份

- 阶段：`3A-1`
- 名称：受控影子运行与就绪度观测基础 V1
- 影子控制契约：`SHADOW_RUN_CONTROL_V1`
- 选择契约：`SHADOW_SELECTION_V1`
- 结果快照契约：`SHADOW_OUTCOME_SNAPSHOT_V1`
- 人工复核契约：`SHADOW_REVIEW_V1`
- 指标契约：`SHADOW_METRICS_V1`
- 被执行团队规则版本：`1.4.0-stage-2i-chief-decision-v1`
- 冻结基线：`954959f5832d01ba1f7211d3e6ebbd8c93feab22`
- 任务分支：`codex/1.4.0-stage-3a1-shadow-readiness-v1`

本阶段建立能够安全暂停、审计和观测的影子运行控制面。它真实调用既有 Java Agent
任务系统和 Python 本地规则服务，保存真实决定、真实不足原因、失败、漂移及人工复核，
但不修改专业规则，也不产生交易动作。

## 2. 仓库事实审计

审计确认：

- 2I 使用六个专业 `agent_runs`，总控决策单独保存在 `agent_decisions`，不存在第七个 run。
- `AgentTaskService` 已提供 contextHash 缓存、活动任务复用及完成任务复用。
- 任务创建事务只负责 `agent_tasks` 与六个 run，提交后由既有异步执行链调用 Python。
- 2I 五种确定结果保存为完成终态；`INSUFFICIENT_DATA` 保存为
  `task=PARTIAL / decision=INSUFFICIENT_DATA`。
- 当前任务触发类型只有 `MANUAL / SCAN_CANDIDATE / SCHEDULED / RETRY`。
- 默认模拟账户为 `accountId=1`；`positions.market_value` 可用于只读排序。
- 扫描候选存在于最近的 `COMPLETED` `market_scan_tasks` 及其
  `eligible=true` 结果中。
- 全市场扫描及行情更新均在各自任务表中暴露 `QUEUED/RUNNING` 状态。
- 现有 Agent 工作台可按 taskId 展示六 run 和总控结果，适合作为影子 item 的只读跳转目标。

未发现必须改变六智能体、2I 契约或外层 contextSnapshot Schema 的前置条件。

## 3. 已知运行限制

### 3.1 时间边界

2F 只在请求交易日上海日终之后形成可靠回测输入；2H 只接受上海当前自然日的模拟账户
状态。普通时刻运行时，两者可能无法同时可用。3A-1 不修改 2F `decisionTime`，不修改
2H current-date 约束，不伪造 Clock，不回填历史持仓，也不把残缺输入重新归一化为
`WATCH` 或人工复核结论。

### 3.2 来源边界

普通行情来源没有可验证 `sourceRevision` 时，2F 可能返回
`BACKTEST_SOURCE_REVISION_UNVERIFIABLE`。AKShare 公告缺少完整、及时的 capture 时，
2G 可能不可用。这些是就绪度观测对象，不是 3A-1 可绕过的缺陷。

## 4. 范围

允许：

- 新增 V11 影子运行表、约束、索引和保护触发器；
- 新增 `SHADOW` Agent TriggerType；
- 新增 Java 影子选择、批次、runner、指标、漂移、复核、取消和调度控制面；
- 新增只读 REST API；
- 新增 `/agent-shadow` 工作台；
- 使用既有 2I 任务链开展受控本地试运行；
- 单元、契约、真实 PostgreSQL、真实 Java/Python 和前端测试；
- 同步权威状态文档。

禁止：

- 修改六个专业规则、2I 权重、分类或上下文；
- 创建第七个智能体 run；
- 自动调用 AKShare、同步行情或启动全市场扫描；
- 修改行情、公告、账户、持仓、委托、成交、权益快照或风险事件；
- 直接调用 Python 绕过 Java 任务系统；
- 直接写 `agent_runs` 或 `agent_decisions`；
- 输出交易指令、收益指标或策略有效性宣传；
- 默认开启影子功能或 scheduler；
- 把 3A-1 写成完整 3A 已完成；
- 开始 3B。

## 5. V11 数据模型

只新增 `V11__agent_shadow_run_foundation.sql`，不修改 V1 至 V10。

### 5.1 `agent_shadow_batches`

保存冻结配置、选择摘要、任务进度、结果计数、取消标志和生命周期。状态固定为：

- `QUEUED`
- `RUNNING`
- `COMPLETED`
- `PARTIAL`
- `FAILED`
- `CANCELLED`

同一时刻只允许一个 `QUEUED/RUNNING` 批次。终态批次不可重新进入运行态，终态事实不可改写。

### 5.2 `agent_shadow_items`

每批每 symbol 唯一，保存选择来源、Agent task 引用、缓存状态、最终结果快照、不足原因和
同 symbol/同 ruleVersion 漂移。结果类别固定为：

- `DETERMINED`
- `INSUFFICIENT`
- `FAILED`
- `CANCELLED`

终态 item 的身份、任务引用、结果、快照及漂移不可改写。

### 5.3 `agent_shadow_reviews`

人工复核只追加，不允许 UPDATE、DELETE 或 TRUNCATE。更正必须新增记录并引用被替代记录。
标签固定为：

- `EXPECTED`
- `UNEXPECTED`
- `DATA_ISSUE`
- `RULE_ISSUE`
- `FALSE_POSITIVE`
- `FALSE_NEGATIVE`
- `NEEDS_FOLLOW_UP`

复核不修改 Agent task 或 finalDecision。

## 6. 选择契约

### 6.1 EXPLICIT

- 接受 1 至 20 个六位 symbol；
- 去重后按 symbol 升序；
- 不追加其他股票；
- `selectionSource=EXPLICIT`。

### 6.2 AUTO

顺序固定为：

1. 默认模拟账户当前持仓，按 `market_value DESC, symbol ASC`；
2. 最近完成扫描的 `eligible=true` 候选，按 `rank_no ASC, symbol ASC`；
3. 去重后截断到 `maxSymbols`。

来源分别保存为 `CURRENT_POSITION` 与 `LATEST_SCAN_CANDIDATE`，同时冻结账户、扫描任务和
rank 引用。默认最多 10 只，硬上限 20；禁止全市场遍历、随机选股或从 Python/Agent
结论反向扩充候选。

选择 Hash 使用 `SHA-256` 小写十六进制，覆盖选择契约、模式、交易日、上限及按顺序排列的
symbol/source/sourceRef。

## 7. 安全运行窗口与默认开关

默认配置：

```yaml
stockquant:
  agent-team:
    shadow:
      enabled: false
      scheduler-enabled: false
      rule-version: 1.4.0-stage-2i-chief-decision-v1
      cron: "0 50 16 * * MON-FRI"
      zone: Asia/Shanghai
      safe-window-start: "16:40"
      safe-window-end: "18:30"
      max-symbols: 10
      max-concurrency: 2
      item-timeout: 5m
      poll-interval: 2s
```

只有 `enabled=true` 且 `scheduler-enabled=true` 才允许 scheduler 创建批次。自动模式只在
周一至周五及安全窗口执行；当前没有正式交易日历时只声明工作日检查，不冒充正式开闭市
判断。运行中的 shadow、全市场扫描或行情更新构成冲突，必须安全跳过并记录原因。

## 8. Runner 与任务关联

每个新任务固定使用：

```text
triggerType=SHADOW
requestedBy=shadow:<batchId>
forceRefresh=false
executionMode=LOCAL_RULES
ruleVersion=1.4.0-stage-2i-chief-decision-v1
```

Runner 按选择顺序、最多两个在途 Agent task：

1. 创建批次并冻结配置和选择；
2. 预写全部 item；
3. 通过 `AgentTaskService` 创建或复用任务；
4. 轮询任务终态；
5. 读取 finalDecision、六 run 和正式 veto；
6. 一次写入不可变 outcome snapshot；
7. 提取 reason code；
8. 与同 symbol、同 ruleVersion 最近上一条终态 item 比较；
9. 汇总批次终态与计数。

不足不自动重试，结果不好不重跑。Agent 任务超时只终止本批次等待并记录失败，不强制终止
既有任务。

## 9. Reason code 与结果快照

`INSUFFICIENT_DATA` 只从六 run 的结构化 `errors[].code` 提取，顺序固定为：

1. DATA_QUALITY
2. MARKET_REGIME
3. TECHNICAL_ANALYSIS
4. STRATEGY_BACKTEST
5. ANNOUNCEMENT_RISK
6. POSITION_RISK

去重后保存全部 code，第一个为 primary。没有结构化 code 时使用
`SHADOW_INSUFFICIENT_WITHOUT_REASON_CODE`。禁止解析自然语言 summary。

快照保存 task 身份、finalDecision、正式 veto 集合及六 run 的
status/gate/decision/score/confidence/veto/errors，顺序固定且可审计。

## 10. 漂移

只比较同 symbol、同 ruleVersion 的最近上一条终态 item：

- contextHash；
- finalDecision；
- score/confidence delta；
- 六 run 的 status/gate/decision/score/confidence/veto/errors；
- 正式 veto 集合；
- reason code 集合。

`changedAgentsJson` 按六智能体固定顺序记录发生变化的字段。不得跨 ruleVersion 比较，不以
summary 或收益变化作为核心漂移；没有前一条时所有漂移字段保持未知。

## 11. 指标

指标直接从数据库事实查询计算，不维护手工缓存。支持日期范围、ruleVersion、batch 和
symbol 过滤，至少输出：

- batch/item 总量与 outcome 分布；
- 六种 finalDecision 分布；
- DQ 阻断、veto、cache hit 数及命中率；
- primary reason code 分布；
- 各 Agent run 状态与结构化错误分布；
- p50/p95 duration；
- context/decision 变化率；
- score/confidence 平均绝对变化；
- review label 分布和未复核 item 数。

## 12. 取消与熔断

取消只设置 `cancellationRequested=true`，不再启动新 item；已启动任务不强杀，未启动 item
记为 `CANCELLED`，批次最终为 `CANCELLED` 或 `PARTIAL`，不删除结果。

连续 Python 不可达、连续 item 创建失败、超过半数启动失败、数据库写入失败或 runner
线程中断时熔断当前批次。熔断不修改全局配置，也不自动重试。

## 13. API 与工作台

API：

```text
POST /api/agent-team/shadow/batches
GET  /api/agent-team/shadow/batches
GET  /api/agent-team/shadow/batches/{id}
GET  /api/agent-team/shadow/batches/{id}/items
POST /api/agent-team/shadow/batches/{id}/cancel
GET  /api/agent-team/shadow/metrics
GET  /api/agent-team/shadow/drift
POST /api/agent-team/shadow/items/{id}/reviews
GET  /api/agent-team/shadow/items/{id}/reviews
```

独立 `/agent-shadow` 页面显示开关、批次、item、六 run 摘要、指标、漂移和复核历史，并
明确显示“影子运行不会触发交易或修改持仓”。不得出现交易、仓位建议或收益统计功能。

## 14. 验收

### 14.1 Java

- EXPLICIT/AUTO 选择、排序、去重、限制和 selection hash；
- 双开关、安全窗口、工作日、运行冲突；
- batch/item 状态机、终态保护、取消和熔断；
- reason code 顺序、结果快照、metrics 和 drift；
- review append-only；
- 2I completed cache 复用。

### 14.2 真实 PostgreSQL

随机隔离 Schema 从 V1 迁移至 V11，`Skipped=0`，验证表、索引、SHADOW TriggerType、
唯一性、并发、终态保护、review 不可变、漂移、指标、取消、精确清理和 public 基线。

### 14.3 真实 Java/Python/PostgreSQL

覆盖确定结果、veto、DQ 阻断、数据不足、失败、完成缓存、PARTIAL、取消、复核、漂移、
非法响应原子失败及六张模拟账户业务表和行情/公告观察表只读不变。

### 14.4 前端与回归

运行 quant-core、quant-server、3A-1 定向、Python compileall/unittest、真实 HTTP、
2I 持久化、2G Live Gate、2B 至 2I 兼容、Vue 类型检查/build、文档检查和
`git diff --check`。真实环境组必须 `Skipped=0`；环境跳过不得冒充通过。

## 15. 受控本地试运行

scheduler 保持关闭，以 EXPLICIT 模式最多 3 至 5 只股票，通过真实 Java 任务系统、
Python 和 PostgreSQL 执行。禁止 AKShare 摄取、行情刷新和全市场扫描。允许真实结果为
DETERMINED、INSUFFICIENT 或 FAILED；验收只关注任务关联、结构化原因、批次终态、缓存、
取消及业务表只读，不把结果用作收益或策略有效性证明。

## 16. 完成边界

3A-1 完成只表示受控影子运行与就绪度观测技术基础可用。完整 3A 至少仍需：

- 不少于 20 个有效观察日；
- 不少于 200 个 shadow item；
- 覆盖确定、不足、阻断、veto 和失败；
- 主要 reason code 的人工复核；
- 持续验证业务表无写入；
- 正式观察报告；
- ChatGPT 验收及用户决定是否进入 3B。

本阶段不伪造长期运行历史，不开启 scheduler，不开始长期观察或 3B。
