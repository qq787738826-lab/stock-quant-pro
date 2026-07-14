# Stock Quant Pro 1.4.0 股票智能体团队架构

## 1. 阶段范围

本文冻结 1.4.0 阶段 1A 的架构契约。本阶段只定义文档与 JSON Schema，不创建数据库迁移，不实现 Java、Python、Vue 或 Electron 生产代码。

1.4.0 默认且当前唯一执行模式为 `LOCAL_RULES`，不接入任何付费模型。智能体结果只用于研究和模拟交易辅助，不能修改模拟账户现金、持仓、委托或成交数据，也不能产生自动买卖指令。

## 2. 权威边界

### 2.1 Java 是唯一任务权威

Java 是以下状态和行为的唯一权威：

- 创建团队级 `agent_tasks`。
- 只为六个专业智能体创建 `agent_runs`。
- 分配并维护 `taskId`、`runId`。
- 生成不可变的 `context_snapshot_json`。
- 计算和保存 `context_hash`。
- 判断幂等缓存是否命中。
- 维护任务与运行的排队、执行、完成、部分完成和失败状态。
- 调用 Python 统一分析接口。
- 在持久化前校验 JSON Schema、证据引用、正式否决和最终决策。
- 持久化任务、运行、证据、否决和最终决策。

Vue 只调用 Java API，不直接调用 Python 智能体接口。Java 对每个团队任务只调用一次 Python：

```text
POST /agents/team/analyze
```

### 2.2 Python 是无状态分析执行方

Python 负责在一次统一请求内编排以下六个专业智能体：

1. `DATA_QUALITY`
2. `MARKET_REGIME`
3. `TECHNICAL_ANALYSIS`
4. `STRATEGY_BACKTEST`
5. `ANNOUNCEMENT_RISK`
6. `POSITION_RISK`

六个专业结果完成后，Python生成独立的总控最终决策。总控不是普通`agent_run`，不使用`CHIEF_DECISION`运行类型；其最终状态、生成时间、错误和耗时以后由`agent_decisions`或相关决策字段记录。

Python 必须遵守：

- 不读取或写入智能体任务数据库表。
- 不创建、替换或修改 Java 生成的 `taskId`、六个专业智能体`runIds`和`contextHash`。
- 不更新 Java 的任务或运行状态。
- 只使用 Java 请求中提供的不可变上下文和已配置数据源允许的真实证据。
- 一次返回六个专业智能体结果、一个独立总控结果、证据和正式否决。
- 不访问模拟交易写接口，不修改账户现金、持仓、委托和成交。

## 3. 统一数据层级

### 3.1 agentTask

`agentTask` 表示一次完整股票智能体团队任务。它负责团队级状态、上下文快照、幂等缓存信息和最终完成状态，不保存某个具体智能体的分析输出。

### 3.2 agentRuns

`agentRuns` 表示团队内专业智能体的一次执行。Java只为`DATA_QUALITY`、`MARKET_REGIME`、`TECHNICAL_ANALYSIS`、`STRATEGY_BACKTEST`、`ANNOUNCEMENT_RISK`和`POSITION_RISK`创建六条运行记录。`CHIEF_DECISION`不得出现在`agentRuns`中。

### 3.3 agentDecision

`agentDecision` 是总控最终决策，未来只保存到独立`agent_decisions`，不能与专业智能体结果混存，也不能放入团队响应的`agentRuns`数组。前端可以展示“六个专业智能体 + 一个总控决策”，但总控不是普通`agent_run`。

### 3.4 evidence

`evidence` 是行情、市场宽度、指标、扫描、回测、公告、账户只读状态或查询结果等真实证据。单智能体输出中的`evidence`只是该智能体产生或使用的证据子集；团队响应顶层`evidence`是Java最终校验和持久化的权威集合。

### 3.5 vetoes

`vetoes` 只保存 `POSITION_RISK` 生成的正式风险否决。公告风险和数据质量阻断不是正式否决。

## 4. 上下文快照与哈希

未来 `agent_tasks` 需要保存：

- `context_schema_version`
- `context_snapshot_json`
- `context_generated_at`
- `context_hash`

`context_snapshot_json` 至少规划包含：

```json
{
  "security": {},
  "marketData": {},
  "marketBreadth": {},
  "scanResult": {},
  "technicalMetrics": {},
  "backtestContext": {},
  "securityEvents": {},
  "portfolioContext": {},
  "dataQualityContext": {}
}
```

Java 生成快照后不得在同一任务内修改。哈希计算流程：

1. 排除 `taskId`、`runId`、`generatedAt`、请求跟踪号等易变字段。
2. 对对象键进行稳定排序。
3. 对有业务顺序的数组保留顺序；对声明为集合的数组按稳定业务键排序。
4. 统一数字、布尔值、空值和时间字符串表示。
5. 使用 UTF-8 编码序列化规范化 JSON。
6. 对字节序列计算 SHA-256，输出64位小写十六进制 `context_hash`。

幂等缓存键为：

```text
symbol + tradeDate + contextHash + ruleVersion + executionMode
```

`forceRefresh=true` 只表示跳过已完成结果缓存，不能绕过相同缓存键的活动任务去重。

## 5. 执行时序

```text
Vue -> Java: POST /api/agent-tasks
Java: 构建只读上下文并计算 context_hash
Java: 检查幂等缓存
Java: 创建 agentTask 和六个专业 agentRuns
Java: 异步线程将任务置为 RUNNING
Java -> Python: 一次 POST /agents/team/analyze
Python: 执行六个专业智能体
Python: 生成独立总控最终决策（不创建agent_run）
Python -> Java: agentRuns + evidence + vetoes + finalDecision
Java: Schema 与跨对象规则校验
Java: 持久化运行、证据、否决和最终决策
Java: 更新团队任务最终状态
Vue -> Java: 轮询任务、运行、证据和最终决策
```

## 6. Gate、公告风险与正式否决

### 6.1 数据质量门禁

`DATA_QUALITY` 只能使用 `gateStatus=BLOCKED` 表示数据不满足分析条件，必须始终返回 `veto=false`，不能生成正式否决。数据被阻断时，总控最终决策应为 `BLOCKED_BY_DATA_QUALITY`。

### 6.2 公告事件风险

`ANNOUNCEMENT_RISK` 只能输出公告风险发现和 `PASS/WARN/REJECT` 结论，必须始终返回 `veto=false`。即使公告包含重大风险，也由 `POSITION_RISK` 读取相应证据后决定是否生成正式否决。

### 6.3 资金仓位风控否决

只有 `POSITION_RISK` 可以返回 `veto=true`。每个正式否决必须包含非空证据引用。总控不能解除、降低、忽略或用其他积极结论抵消正式否决；存在正式否决时，最终决策必须为 `REJECTED_BY_VETO`。

## 7. Java 持久化前强制校验

JSON Schema 负责单对象结构、类型、枚举、数值范围和部分条件约束。以下跨对象、跨数组或与任务状态有关的规则必须由后续 Java `AgentResponseValidator` 校验，不能只依赖 JSON Schema：

1. 所有 `taskId` 必须等于当前 Java 团队任务。
2. 所有 `runId` 必须属于当前 `taskId` 且匹配对应 `agentCode`。
3. `sourceRunIds` 必须属于当前任务。
4. `vetoIds` 必须属于当前任务。
5. 所有`findings[].evidenceIds`必须引用团队响应顶层权威`evidence`集合中真实存在的证据。
6. `contextHash` 必须等于 Java 保存的上下文哈希。
7. `tradeDate`、`ruleVersion`、`executionMode` 必须与任务一致。
8. 正式否决只能来自 `POSITION_RISK`。
9. `vetoed=true` 时最终决策必须为 `REJECTED_BY_VETO`。
10. 总控不能解除 `POSITION_RISK` 的正式否决。
11. 团队响应必须恰好包含六个不同的专业智能体结果，不能包含`CHIEF_DECISION`。
12. Java按`evidenceId`合并和去重证据；相同ID的关键字段或`fields`不一致时拒绝整个团队响应。

### 7.1 权威证据合并规则

Java合并六个专业智能体证据子集和团队顶层证据时，以团队响应顶层`evidence`作为最终权威集合，并按`evidenceId`去重。相同`evidenceId`重复出现时，以下内容必须一致：

- `category`
- `sourceType`
- `sourceName`
- `sourceRef`
- `symbol`
- `tradeDate`
- `observedAt`
- `contentHash`
- `fields`

任一字段冲突时，Java拒绝整个团队响应。JSON Schema只能验证基本结构，不能完成跨数组去重、深层内容一致性和引用完整性校验。

## 8. 本地规则与模型边界

1.4.0 默认 `executionMode=LOCAL_RULES`，本阶段不接入任何付费模型。本地规则是行情、指标、回测数值、数据门禁、公告事实、仓位约束、正式否决和最终决策枚举的唯一权威。

未来即使引入在线模型，也只能基于已验证证据生成解释草稿；不能新增事实、修改评分和置信度、解除否决、生成自动交易指令或修改模拟账户数据。

## 9. Schema 文件

- `schemas/agent-output.schema.json`：单智能体输出及 finding、evidence 公共结构。
- `schemas/agent-decision.schema.json`：独立总控最终决策。
- `schemas/agent-team-response.schema.json`：Python 统一团队响应。
- `schemas/agent-task-request.schema.json`：Vue 到 Java 的团队任务创建请求。
- `schemas/agent-team-request.schema.json`：Java 到 Python `/agents/team/analyze` 的只读分析请求。

两种请求不是同一个接口契约。Java到Python的请求包含六个专业智能体`runIds`、`contextHash`和不可变`contextSnapshot`；Python不得修改这些标识或上下文。上下文只能包含只读研究数据，不得包含账户写操作、委托确认或成交操作指令。此类语义安全限制最终由Java构建器和校验器保证，不能只依赖JSON Schema。

全部使用 JSON Schema Draft 2020-12，可从本地文件加载，不要求网络访问。
