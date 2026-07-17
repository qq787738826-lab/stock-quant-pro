# 阶段 2B 验收：确定性 DATA_QUALITY 规则门禁

## 1. 状态与边界

本文记录阶段 2B 的冻结实现、验收口径和真实闭环结果。阶段 2B 已完成正式验收；
下一阶段唯一入口为阶段 2C，但阶段 2C 尚未开始。

阶段 2B 仅消费 Java 冻结并传入的四类上下文：

- `security`
- `marketData`
- `technicalMetrics`
- `dataQualityContext`

Python 保持无状态，不查询数据库、不连接新数据源、不修改 `contextSnapshot` 或
`contextHash`。本阶段没有接入 LLM、公告、仓位或交易能力，没有修改外层 JSON
Schema、Flyway 或数据库结构。

阶段 2B 新语义只对规则版本
`1.4.0-stage-2b-dq-v1` 生效；其他规则版本继续使用原有兼容行为。

## 2. 权威边界

- Java 继续是 `taskId`、`runId`、任务状态、缓存、上下文、Hash、响应校验和持久化的唯一权威。
- Python 仅根据请求中的冻结快照执行确定性规则。
- 团队仍恰好包含六个专业智能体，总控不产生第七个 `agentRun`。
- `DATA_QUALITY.veto` 始终为 `false`；只有 `POSITION_RISK` 可以形成正式否决。
- 其余五个专业规则仍未实现，继续返回 `INSUFFICIENT_DATA` 和 `NOT_APPLICABLE`。
- 非主板、ST、`isActive=false` 只生成 `INFO` 事实，不参与阶段 2B 门禁。

## 3. 状态映射

| 场景 | status | gateStatus | decision | score | confidence | veto |
|---|---|---|---|---:|---:|---|
| 上下文无效 | `INSUFFICIENT_DATA` | `BLOCKED` | `REJECT` | 0 | 0 | false |
| 有效且存在阻断 | `COMPLETED` | `BLOCKED` | `REJECT` | 0 | 100 | false |
| 有效且只有警告 | `COMPLETED` | `WARN` | `WARN` | 50 | 100 | false |
| 有效且通过 | `COMPLETED` | `PASS` | `PASS` | 100 | 100 | false |

当 DATA_QUALITY 为 `PASS` 或 `WARN` 时，由于其余五个规则尚未实现：

- `finalDecision.decision=INSUFFICIENT_DATA`
- `finalDecision.gateStatus` 继承 DATA_QUALITY gate
- `finalDecision.score=0`
- `finalDecision.confidence=0`
- 摘要不得声称被数据质量阻断

DATA_QUALITY 为 `BLOCKED` 时，`finalDecision.decision` 为
`BLOCKED_BY_DATA_QUALITY`，但仍不是正式 veto。

## 4. 统一质量证据

有效上下文固定生成一条质量证据：

- `category=DATA_QUALITY`
- `sourceType=JAVA_ENGINE`
- `sourceName=AgentContextSnapshotService`
- `sourceRef=contextSnapshot`
- `contentHash=request.contextHash`
- `fields` 只直接投影 `security`、`marketData`、`technicalMetrics`、
  `dataQualityContext`
- `fields` 及其嵌套对象不得包含 `gate`、`gateStatus`、`decision`、`score`、
  `finding`、`findings` 或 `veto` 等规则结论字段

所有阶段 2B finding 只引用这条证据。上下文无效时不生成证据和 finding，并返回
明确错误。

## 5. 规则分类

阻断 finding 使用 `HIGH`，包括：

- 四类上下文的查询范围、请求日期、有效日期、延迟、精确日期、证券事实、条数、
  复权类型、公式版本、重复保护和可用性矛盾；
- 有效日期晚于请求日期或自然日延迟为负数；
- 证券记录缺失、来源未知或疑似占位；
- 本地 QFQ 日线缺失、少于 61 条、存在非法 OHLCV、技术指标不可用；
- 有效行情距离请求日期超过 10 个自然日。

警告 finding 使用 `WARN`，包括：

- 请求日期未精确命中但延迟不超过 10 个自然日；
- 证券属性不具备点时保证；
- 非关键证券字段、`amount` 或 `turnoverRate` 缺失；
- 已加载记录之间观察到超过 10 个自然日的间隔。

能力限制和股票范围事实使用 `INFO`：

- 交易日历不可用；
- 跨源一致性不可评估；
- 非主板、ST 或非活动状态事实。

finding 按冻结的规则代码顺序输出，同一代码最多一条。规则对同一根因采用互斥分支，
不重复生成解释相同缺失或矛盾的阻断 finding。

## 6. Java 双重校验

Java 响应校验器仅在阶段 2B 规则版本下额外校验：

- DATA_QUALITY 四种状态映射；
- `veto=false` 和空正式 veto 集合；
- 唯一质量证据的来源、Hash、日期和四字段直接投影；
- finding 代码唯一、固定顺序、固定严重性和统一证据引用；
- 其余五个规则仍为未实现状态；
- DATA_QUALITY gate 与总控决策一致。
- `requestedAt/collectedAt` 与 `dataQualityContext.queriedAt/observedAt` 按跨语言可传输的
  微秒精度比较：双方非空并统一截断到 `ChronoUnit.MICROS` 后必须完全相等；相差
  1 微秒即拒绝。

时间比较是 Java 纳秒 `Instant` 经 Python/Pydantic `datetime` 往返时的传输精度
规范化，不是毫秒容差或任意时间窗口。`queriedAt` 必须仍可解析为带时区的合法时刻，
解析失败直接拒绝；阶段 2A 冻结事实和 `contextHash` 不做预先截断或改写。

旧规则版本和既有共享夹具不启用这些新增约束。

阶段 2B 增加以下版本化跨语言共享夹具，Python 和 Java 测试读取同一组 JSON：

- `stage-2b-valid-request.json`
- `stage-2b-invalid-context-response.json`
- `stage-2b-blocked-response.json`
- `stage-2b-warn-response.json`
- `stage-2b-pass-response.json`

这些夹具不修改旧共享夹具语义，固定覆盖四种 DATA_QUALITY 映射、总控映射、
`veto=false`、权威 evidence 子集一致性和 finding 引用存在性。

## 7. 验收矩阵

自动化测试覆盖：

- 旧规则版本共享契约兼容；
- 无效上下文与有效 `BLOCKED`、`WARN`、`PASS` 四种映射；
- 60/61 条日线边界、非法日线、技术指标不可用；
- 0、1—10、超过 10 个自然日延迟；
- 负延迟和未来有效日期事实矛盾；
- 非主板、ST、非活动证券只产生 `INFO`；
- finding 去重、固定排序和严重性；
- 唯一证据元数据、四字段直接投影和输入不可变性；
- Java 对错误映射、证据来源、额外字段、finding 重复/乱序的拒绝；
- Java、PostgreSQL、真实 Python 的任务、六 run、证据和总控持久化闭环；
- Vue 默认规则版本和生产构建。

具体执行结果以本任务最终测试报告和后续独立验收报告为准。

本开发窗口的执行结果：

| 验证 | 结果 |
|---|---|
| Python `compileall` | 通过 |
| Python `unittest discover` | 49 项，0 失败，0 跳过 |
| Python 阶段 2B 定向测试 | 13 项，0 失败，0 跳过 |
| Java 阶段 2B 定向测试 | 13 项，0 失败，0 错误，0 跳过 |
| Java 阶段 2B 与既有响应校验定向测试 | 45 项，0 失败，0 错误，0 跳过 |
| Java 完整 Agent 回归 | 164 项，0 失败，0 错误，9 项因缺少显式集成环境变量安全跳过 |
| `quant-server` 全量回归 | 165 项，0 失败，0 错误，9 项因缺少显式集成环境变量安全跳过 |
| `quant-core` 独立回归 | 1 项，0 失败，0 跳过 |
| PostgreSQL/Java/真实 Python 阶段 2B 烟测 | 1 项，0 失败，0 错误，0 跳过，`BUILD SUCCESS` |
| Vue 类型检查与生产构建 | 通过；生产构建转换 2247 个模块，仅有依赖注释和大包非阻断提示 |
| `git diff --check` | 通过 |

Java 全量回归中的 9 个跳过项均属于现有显式环境门禁：

- `AgentEvidenceVetoPostgresIntegrationTest`：2 项，缺少测试数据库 URL、用户名或密码；
- `AgentHttpClientContractIntegrationTest`：1 项，缺少测试数据库环境变量；
- `AgentInvalidResponsePostgresIntegrationTest`：1 项，缺少测试数据库环境变量；
- `AgentPythonServicePostgresSmokeTest`：1 项，缺少测试数据库环境变量和 Python 服务 URL；
- `AgentReadonlyContextPostgresIntegrationTest`：1 项，缺少测试数据库环境变量；
- `AgentTaskPostgresIntegrationTest`：3 项，缺少测试数据库环境变量。

真实闭环使用专用本地数据库 `stock_quant_test`、受限角色 `stock_quant_test` 和
`127.0.0.1:8001` Python 回环服务，实际验证：

- 任务进入 `PARTIAL`，恰好持久化六个专业智能体 run，不存在第七个总控 run；
- DATA_QUALITY 为 `COMPLETED/WARN/WARN/50/100`，`veto=false`；
- 正式 veto 数量为 0；
- finalDecision 为 `INSUFFICIENT_DATA/WARN/0/0`，`vetoed=false`；
- 唯一 DATA_QUALITY evidence 通过 Java 校验，finding 引用真实 evidence；
- `contextSnapshot` 和 `contextHash` 未被 Python 修改；
- 其他五个未实现智能体安全降级，摘要不声称被数据质量阻断；
- 测试任务、六 run、evidence、decision、测试证券和 61 条 QFQ 日线按标识精确清理。

测试前后数据库基线一致：`agent_tasks/agent_runs/agent_evidence/agent_vetoes/agent_decisions`
为 `2/12/0/0/2`，`securities/daily_bars` 为 `0/0`；测试数据源记录为 0。Python
测试服务已停止，8001 端口已释放，临时环境变量、日志和盘符映射均已清理。

## 8. 当前结论

阶段 2B 的确定性 DATA_QUALITY 规则门禁、版本化共享契约、Java 双重校验和真实
PostgreSQL/Java/Python 闭环均已通过验收。阶段 2B 标记为完成；下一阶段唯一入口为
阶段 2C，但阶段 2C 仍为未开始。
