# 2I 确定性总控综合决策 V1 任务书

## 1. 阶段身份

- 大阶段：`2I 确定性总控综合决策 V1`
- 团队规则版本：`1.4.0-stage-2i-chief-decision-v1`
- 总控契约：`CHIEF_DECISION_V1`
- 权重契约：`CHIEF_SCORE_WEIGHTS_V1`
- 冻结集成基线：`681fee989f08c4c1e4edaa8cf787c97a95a27784`
- 任务分支：`codex/1.4.0-stage-2i-chief-decision-v1`

本阶段在已经合入的六个专业智能体之上形成可复现、可解释、可由 Java
独立复算的总控结果。总控继续写入 `agent_decisions`，不是第七个 run。

## 2. 仓库事实与范围

2G 已提供由 `AGENT_CONTEXT_2G_V1` 组合的完整只读上下文，包括
DATA_QUALITY、受限 MARKET_REGIME、TECHNICAL_ANALYSIS、可靠
backtestContext、securityEvents 和当前模拟账户 portfolioContext。2I 不新增外层
`contextSnapshot` 字段，不修改任何既有子 Context Schema，也不新增 Flyway。

允许范围：

- 为精确 2I 规则版本复用与 2G 完全相同的上下文选择；
- 新增 Python 确定性总控规则模块；
- 新增 Java 独立复算规则与 2I 响应校验；
- 复用 V5 的 task、run、evidence、veto 和 decision 持久化；
- 增加共享黄金向量、纯规则、跨语言、真实 PostgreSQL 和兼容测试；
- 对 Vue 工作台增加最小的总控决策中文标签；
- 同步权威状态与阶段证据文档。

禁止范围：

- 第七个总控 run、总控 evidence 或总控 finding；
- LLM、网络、Python 数据库访问或新事实生成；
- 修改六个专业智能体的阈值、score、confidence、finding、evidence 或 veto 语义；
- 修改 V1 至 V10、增加迁移或改变既有 Context Schema；
- 真实账户、券商接口、自动下单、投资或仓位建议、收益预测；
- 启动 3A 或任何其他阶段。

## 3. 输入角色与门禁

DATA_QUALITY 只负责门禁、质量警告和综合 confidence 上限，其 score 不进入
综合 score。合法正常输入必须为 `COMPLETED`、`PASS/WARN`、`PASS/WARN`、
`veto=false`、`confidence=100`。

MARKET_REGIME V1 的 score/confidence 权重均为 0，但必须保持
`COMPLETED`、`confidence=0`、`veto=false` 且 evidence 非空。它继续进入
`sourceRunIds` 和顶层 finding/evidence 拼接；缺失或非法仍导致
`INSUFFICIENT_DATA`。

四个综合贡献者及固定权重：

| 专业 run | score 权重 | confidence 权重 |
| --- | ---: | ---: |
| TECHNICAL_ANALYSIS | 25 | 25 |
| STRATEGY_BACKTEST | 35 | 35 |
| ANNOUNCEMENT_RISK | 20 | 20 |
| POSITION_RISK | 20 | 20 |

权重总和必须精确为 100。四个贡献者均须满足各自冻结契约和本阶段可综合终态；
任何贡献者不可用、输入非法或样本不足时，不对剩余权重重新归一化。

## 4. 决策优先级

严格顺序如下：

1. 任一合法 POSITION_RISK 正式 veto：
   `REJECTED_BY_VETO/BLOCKED/vetoed=true/score=0`，confidence 取
   POSITION_RISK confidence；
2. 无正式 veto 且 DATA_QUALITY 为 `BLOCKED`：
   `BLOCKED_BY_DATA_QUALITY/BLOCKED/vetoed=false/score=0`，confidence
   取 DATA_QUALITY confidence；
3. 无上述阻断但任一必要 run 不满足正常综合门禁：
   `INSUFFICIENT_DATA/NOT_APPLICABLE/vetoed=false/score=0/confidence=0`；
4. 输入完整时计算综合 score、confidence 和最高风险 severity；
5. 按 `RESEARCH_ONLY`、`PASS_TO_MANUAL_REVIEW`、`WATCH` 的冻结顺序分类。

正式 veto 即使与 DATA_QUALITY 阻断或其他 run 不足同时存在也保持最高优先。
任何非 POSITION_RISK veto 都是非法响应。

## 5. 综合公式

```text
weightedScore =
    technicalScore * 25
  + backtestScore * 35
  + announcementRiskScore * 20
  + positionRiskScore * 20

compositeScore = HALF_UP(weightedScore / 100)
```

```text
weightedConfidence =
    technicalConfidence * 25
  + backtestConfidence * 35
  + announcementRiskConfidence * 20
  + positionRiskConfidence * 20

rawCompositeConfidence = HALF_UP(weightedConfidence / 100)
```

DATA_QUALITY 为 `WARN` 时 confidence 上限为 50；POSITION_RISK 为 `PARTIAL`
时再应用 50 上限。两者均使最终分类最多为 `RESEARCH_ONLY`。

公告与持仓风险只从 ANNOUNCEMENT_RISK 和 POSITION_RISK 的冻结 finding
severity 中按 `INFO < WARN < HIGH < CRITICAL` 取最高值，不解析 summary。

## 6. 最终分类

以下任一条件强制 `RESEARCH_ONLY/WARN`：

- DATA_QUALITY 为 `WARN`；
- POSITION_RISK 为 `PARTIAL`；
- 公告或持仓最高风险为 `HIGH/CRITICAL`；
- composite score 小于 50；
- composite confidence 小于 40。

`PASS_TO_MANUAL_REVIEW/PASS` 只有同时满足以下全部条件才成立：

- DATA_QUALITY 为 `PASS`；
- MARKET_REGIME 为合法 `COMPLETED`；
- TECHNICAL_ANALYSIS score 至少 60；
- STRATEGY_BACKTEST score 至少 60；
- ANNOUNCEMENT_RISK 为 `PASS`；
- POSITION_RISK 为 `COMPLETED/PASS`；
- composite score 至少 70；
- composite confidence 至少 60；
- 公告和持仓最高风险均为 `INFO`。

其余完整输入形成 `WATCH/WARN`。`PASS_TO_MANUAL_REVIEW` 只表示进入人工研究
复核，不表示任何可执行操作。

## 7. 输出契约

- `sourceRunIds` 按 DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS、
  STRATEGY_BACKTEST、ANNOUNCEMENT_RISK、POSITION_RISK 固定顺序，恰好六个；
- `vetoIds` 精确等于顶层 POSITION_RISK 正式 veto 集合并保持稳定顺序；
- final findings 是六个专业 run findings 按固定 run 顺序拼接；
- 顶层 evidence 是六个专业 run evidence 按固定 run 顺序拼接；
- 不生成总控 finding 或 evidence；
- summary 使用由契约、分类、score 和 confidence 决定的固定模板，明确
  MARKET_REGIME V1 权重为 0，并声明结果仅供研究或人工复核。

## 8. 双重校验与原子性

Python 只依据六个专业输出执行确定性分类。Java 在持久化前独立复算全部门禁、
权重、HALF_UP、confidence 上限、风险 severity、最终代码、gate、vetoed、
score、confidence、summary、finding/evidence 顺序、sourceRunIds 和 vetoIds。

任何不一致使响应校验失败，并沿用现有事务边界：

- 不持久化部分 evidence、veto 或 final decision；
- 不留下部分成功 run 终态；
- 已合法存在的行情、公告和模拟账户事实不受影响。

## 9. 测试与验收

必须覆盖：

- Python 与 Java 的完整分类、优先级、49/50/69/70 score、
  39/40/59/60 confidence、四档风险 severity 和两个 confidence 上限矩阵；
- 共享固定黄金向量，包含六 run 输入、weighted sum、score、confidence、
  decision 和 summary，预期不能由被测实现运行时生成；
- 跨语言真实 HTTP 的 PASS、WATCH、RESEARCH、INSUFFICIENT、veto、DQ
  阻断和全部篡改拒绝路径，`Skipped=0`；
- 随机隔离 Schema 的 V1 至 V10 真实 PostgreSQL task/run/evidence/veto/
  decision、JSONB、幂等、缓存和非法响应原子失败，`Skipped=0`；
- 2B、2D-1、2E-1、2F、2G、2H 旧规则版本兼容；
- `quant-core`、`quant-server` 安全回归、Python compileall/unittest、
  2G AKShare Live Gate、Vue build、文档链接及 `git diff --check`；
- public Schema 基线前后不变，临时 Schema 精确清理。

## 10. 完成边界

任务分支完成标准是实现和 Codex 本地验证通过、单次 commit 并普通 push。
这不等于 ChatGPT 已验收或用户已批准 merge；不得在任务分支文档中提前声明
已合入，也不得开始 3A。
