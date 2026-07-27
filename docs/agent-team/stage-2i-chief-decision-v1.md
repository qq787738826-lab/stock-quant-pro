# 阶段 2I：确定性总控综合决策 V1

## 1. 状态

状态：**任务分支实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交验收，未合入集成分支。**

- 冻结集成基线：`681fee989f08c4c1e4edaa8cf787c97a95a27784`
- 任务分支：`codex/1.4.0-stage-2i-chief-decision-v1`
- 团队规则版本：`1.4.0-stage-2i-chief-decision-v1`
- 总控契约：`CHIEF_DECISION_V1`
- 权重契约：`CHIEF_SCORE_WEIGHTS_V1`
- 复用 context profile：`AGENT_CONTEXT_2G_V1`
- 完整任务书：[2i-deterministic-chief-decision-v1.md](tasks/2i-deterministic-chief-decision-v1.md)

该状态不代表 ChatGPT 验收 PASS、用户 merge 批准或已经合入。当前项目事实以
[CURRENT_STATE.md](CURRENT_STATE.md) 为唯一权威来源；3A 尚未开始。

## 2. 范围与兼容

2I 只在六个已冻结专业 run 之上形成确定性 finalDecision，不增加业务上下文。精确
2I 规则版本复用与 2G 完全相同的组合上下文选择，因此：

- 不增加外层 `contextSnapshot` 字段；
- 不修改 2F `backtestContext`、2G `securityEvents` 或 2H `portfolioContext` Schema；
- 不修改 2G contextHash；
- 不改变 2B、2D-1、2E-1、2F、2G、2H 的 profile、缓存键或响应；
- 不增加第七个 run、总控 finding 或总控 evidence；
- 不新增 Flyway，V1 至 V10 保持不变。

六个专业 run 仍按 DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS、
STRATEGY_BACKTEST、ANNOUNCEMENT_RISK、POSITION_RISK 的固定顺序执行。Java 继续是
task、run、evidence、veto、decision 与持久化的唯一权威；Python 无状态，不访问网络或
数据库。

## 3. 输入角色与可综合门禁

DATA_QUALITY 只负责安全门禁、数据质量警告与综合 confidence 上限，其 score 不进入
综合 score。正常综合要求其为 `COMPLETED`、gate/decision 为 `PASS` 或 `WARN`、
`veto=false`、`confidence=100`，并通过既有 2B 契约。

MARKET_REGIME V1 的 score 和 confidence 权重均为 0，但仍必须为合法 `COMPLETED`、
`confidence=0`、`veto=false`，保留冻结 finding/evidence 并进入 `sourceRunIds`。权重为
0 不允许绕过缺失、失败或非法 run。

四个综合贡献者必须分别通过既有 2E、2F、2G、2H 契约及 2I 可综合终态：

| 贡献者 | score 权重 | confidence 权重 | 关键门禁 |
|---|---:|---:|---|
| TECHNICAL_ANALYSIS | 25 | 25 | `COMPLETED`、`PASS/WARN`、`decision=WARN`、confidence>0 |
| STRATEGY_BACKTEST | 35 | 35 | `COMPLETED`、正常评分样本、`decision=WARN`、confidence>0 |
| ANNOUNCEMENT_RISK | 20 | 20 | `COMPLETED`、`PASS/WARN`、confidence=40 |
| POSITION_RISK | 20 | 20 | 无正式 veto 时 `COMPLETED/PARTIAL`、`PASS/WARN`、confidence>0 |

任一必要 run 不满足门禁时不得重分配或重新归一化权重，必须安全形成
`INSUFFICIENT_DATA/NOT_APPLICABLE/0/0`。

## 4. 决策优先级

总控严格按以下顺序执行：

1. 任一合法 POSITION_RISK 正式 veto：
   `REJECTED_BY_VETO/BLOCKED/vetoed=true/score=0`，confidence 取 POSITION_RISK；
2. 无 veto 且 DATA_QUALITY 阻断：
   `BLOCKED_BY_DATA_QUALITY/BLOCKED/vetoed=false/score=0`，confidence 取 DATA_QUALITY；
3. 无上述阻断但任一必要 run 不足：
   `INSUFFICIENT_DATA/NOT_APPLICABLE/vetoed=false/score=0/confidence=0`；
4. 完整输入计算固定 score、confidence 和最高风险 severity；
5. 依次判断 `RESEARCH_ONLY`、`PASS_TO_MANUAL_REVIEW`、`WATCH`。

正式 veto 即使与 DATA_QUALITY 阻断或其他 run 不足同时存在也保持最高优先级。任何
非 POSITION_RISK veto 都是非法响应。

## 5. Score与confidence

完整输入使用：

```text
weightedScore = technical*25 + backtest*35 + announcement*20 + position*20
compositeScore = HALF_UP(weightedScore / 100)
```

```text
weightedConfidence = technical*25 + backtest*35 + announcement*20 + position*20
rawCompositeConfidence = HALF_UP(weightedConfidence / 100)
```

Java/Python 均使用相同的非负整数 `HALF_UP` 算法。DATA_QUALITY 为 WARN 时 confidence
最高 50；POSITION_RISK 为 PARTIAL 时再次应用最高 50。MARKET_REGIME 不进入两条
公式；缺失贡献者时不重新归一化。

共享黄金向量固定了六 run 结构、四项权重、weighted sum、score、confidence、决策和
summary。任务指令中的人工复核样例经精确重算为 `weightedScore=8450`、
`score=85`、`weightedConfidence=8100`、`confidence=81`，不是错误的 86。

## 6. 风险severity与三种综合分类

最高风险 severity 只从 ANNOUNCEMENT_RISK 与 POSITION_RISK 的冻结风险 finding code
读取，顺序为 `INFO<WARN<HIGH<CRITICAL`，不解析 summary。公告来源覆盖和研究限制
finding 是来源资格披露，不是发行人风险事件，因此不参与最高风险 severity。

以下任一条件强制 `RESEARCH_ONLY/WARN`：

- DATA_QUALITY 为 WARN；
- POSITION_RISK 为 PARTIAL；
- 公告或持仓最高风险为 HIGH/CRITICAL；
- composite score<50；
- composite confidence<40。

只有 DQ PASS、MARKET_REGIME 合法、技术和回测各至少 60、公告 PASS、持仓
`COMPLETED/PASS`、score至少70、confidence至少60且公告/持仓最高风险为 INFO 时，
才形成 `PASS_TO_MANUAL_REVIEW/PASS`。其余完整输入形成 `WATCH/WARN`。
`PASS_TO_MANUAL_REVIEW` 只表示进入人工研究复核，不是买卖、下单、仓位或收益结论。

## 7. FinalDecision、finding与evidence

- `sourceRunIds` 按固定专业 run 顺序恰好包含六个唯一 runId；
- `vetoIds` 精确等于顶层合法 POSITION_RISK veto 集合并保持冻结顺序；
- final findings 是六个专业 run findings 按固定 run 顺序拼接；
- 顶层 evidence 是六个专业 run evidence 按固定 run 顺序拼接；
- 不生成总控 finding 或 evidence；
- summary 使用固定英文模板，包含契约、分类、score、confidence、MARKET_REGIME V1
  权重为0和研究/人工复核边界；
- summary 禁止交易执行、仓位建议和收益承诺语言。

## 8. Python与Java双重校验

Python 独立模块 `chief_decision.py` 冻结权重、可综合门禁、优先级、`HALF_UP`、风险
severity、三种综合分类和 summary。旧规则版本继续走原有总控分支。

Java `AgentChiefDecisionRules` 使用独立常量和算法复算；
`AgentStage2IChiefDecisionValidator` 在持久化前验证六 run 身份、旧专业契约、veto/DQ/
不足优先级、score、confidence、风险 severity、summary、findings/evidence、
sourceRunIds、vetoIds 与禁止语言。Java 不信任 Python 返回的综合数值或分类。

非法响应沿用现有事务边界原子失败，不持久化部分 evidence、veto、decision 或部分成功
run 终态；已合法存在的行情、公告和模拟账户事实不受影响。

## 9. 持久化与工作台

2I 继续使用 V5 的 `agent_tasks`、`agent_runs`、`agent_evidence`、`agent_vetoes` 和
`agent_decisions`，没有迁移。专业 run 状态与总控决策状态是两个独立层级；精确
2I ruleVersion 显式依据 `finalDecision.decision` 映射总控终态：

- `REJECTED_BY_VETO`、`BLOCKED_BY_DATA_QUALITY`、`RESEARCH_ONLY`、`WATCH`、
  `PASS_TO_MANUAL_REVIEW` 均保存为
  `agent_decisions.status=COMPLETED/agent_tasks.status=COMPLETED`；
- 只有最终 `INSUFFICIENT_DATA` 保存为
  `agent_decisions.status=INSUFFICIENT_DATA/agent_tasks.status=PARTIAL`；
- veto/DQ 优先形成确定总控结果时，下游专业 run 的 `PARTIAL/INSUFFICIENT_DATA`
  保持原样；POSITION_RISK `PARTIAL` 可综合形成 `RESEARCH_ONLY` 时同样不改写该 run；
- 五种确定结果进入既有 completed cache，最终 `INSUFFICIENT_DATA` 不冒充完成缓存；
- 旧 2B、2D-1、2E-1、2F、2G、2H 仍沿用既有终态映射。

所有决策继续完成 JSONB 往返；非法响应仍在持久化前被原子拒绝。

Vue 工作台只增加 finalDecision 中文标签：仓位风险否决、数据质量阻断、数据不足、
仅限研究、持续观察、进入人工研究复核；继续显示 score、confidence、vetoed、summary
和六 run。没有增加买入、卖出、下单、仓位调整或收益预测联动。

## 10. Codex本地测试证据

以下均为 Codex 本地执行证据，不是 GitHub Actions CI。

| 测试组 | 运行/失败/错误/跳过 | 说明 |
|---|---:|---|
| `quant-core` 全量 | `4/0/0/0` | 核心回归 |
| 2I Java纯规则、上下文与终态映射定向 | `23/0/0/0` | 权重、边界、优先级、共享向量、profile/contextHash、精确2I finalDecision终态与旧版本兼容 |
| Python `compileall` | 通过 | `quant-ai/app` 与测试模块 |
| Python完整unittest | `123/0/0/0` | 2I规则、共享向量与全部旧规则回归 |
| 2I真实Java/Python HTTP | `12/0/0/0` | 六种决策和六类篡改拒绝；`Skipped=0` |
| 2E/2F/2G/2H真实HTTP兼容 | `17/0/0/0` | 旧规则实际调用；`Skipped=0` |
| 安全非数据库 `quant-server` 全量 | `360/0/0/69` | 69项为外部Python/PostgreSQL/AKShare环境门禁跳过 |
| Vue生产build | 通过 | `vue-tsc -b` 与 `vite build` |
| V1至V10真实PostgreSQL/Python/任务持久化 | `2/0/0/0` | 五种确定总控结果的COMPLETED终态与缓存、最终不足非完成、专业run原状态、物理veto映射、JSONB和非法响应原子失败；`Skipped=0` |
| 随机隔离Schema PostgreSQL兼容 | `26/0/0/0` | 2D-2A、2D-2B、2E、2F、2G、2H 从V1迁移至V10；`Skipped=0` |
| 2G真实AKShare Live Gate | `1/0/0/0` | 真实Provider、Java摄取、ID/Hash/append-only、随机Schema清理；`Skipped=0` |

真实数据库与 Live Gate 测试均捕获 public 数据和结构基线，并在随机隔离 Schema
中执行；测试结束后 public 基线不变，只读残留检查为 0。一次额外包含绑定专用库
public 的旧 `AgentStage2DPostgresPythonIntegrationTest` 的兼容尝试为 `27/0/1/0`，
唯一 ApplicationContext 错误来自已知 V6 checksum 不一致，因此不描述为通过；
没有执行 Flyway repair/clean，也没有修改、删除或重建 public。

## 11. 安全结论

- 未修改 V1 至 V10，未新增 Flyway；
- 未改变 2D、2E、2F、2G、2H 专业规则阈值或既有响应；
- 未增加第七个 run、总控 finding 或总控 evidence；
- 未访问外部数据源进行 Agent 分析，未使用 LLM；
- 未修改行情、公告、持仓或模拟账户事实；
- POSITION_RISK 仍是唯一正式否决权；
- 未增加真实账户、券商接口、自动下单、投资建议、仓位建议或收益预测；
- 任务分支尚未合入，3A 尚未开始。
