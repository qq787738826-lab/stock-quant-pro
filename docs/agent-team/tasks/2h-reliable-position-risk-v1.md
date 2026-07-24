# 2H 可靠模拟持仓上下文与 POSITION_RISK 正式否决 V1

## 1. 状态与目标

状态：**任务分支实现与 Codex 本地验证完成；尚未经过 ChatGPT 实际 Git 提交验收；尚未合入集成分支。**

- 冻结集成基线：`4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`
- 任务分支：`codex/1.4.0-stage-2h-position-risk-v1`
- 团队规则版本：`1.4.0-stage-2h-position-risk-v1`
- 上下文 profile：`AGENT_CONTEXT_2H_V1`
- portfolioContext Schema：`PORTFOLIO_CONTEXT_V1`
- 固定模拟账户：`accountId=1`

本大阶段连续完成只读模拟持仓事实冻结、`portfolioContext`、确定性
`POSITION_RISK`、正式 veto、总控优先级、跨语言验证、真实 PostgreSQL 验收和权威文档同步。
内部工作包不单独暂停、提交或验收。

## 2. 仓库事实审计

冻结基线的事实如下：

1. V1 已有 `portfolio_accounts`、`positions`、`manual_orders`、`risk_events`，
   V4 已补充冻结资金、持仓风控字段、`simulated_trades`、
   `account_equity_snapshots` 和组合限制设置。
2. `PortfolioService.summary()`、`snapshot()` 和 `refreshAndCheckRisk()` 会执行
   T+1 结算、行情刷新、快照写入或风险事件写入，不能用于 Agent 只读分析。
3. `daily_bars` 可提供请求日期及以前最近的本地 QFQ 收盘价；当前账户没有历史持仓版本，
   因此 V1 只能表达冻结时刻的当前模拟账户状态，不能声明历史 PIT。
4. V5 已支持六个固定专业 run、`PORTFOLIO_STATE` evidence、只有
   `POSITION_RISK` 可持有的正式 veto 及逻辑 vetoId 到数据库物理 ID 的映射。
5. 外层 `contextSnapshot` 已保留 `portfolioContext` 槽位，V1 至 V9 无需修改；
   通过精确规则版本选择新 profile 可以保持旧 contextHash 和缓存键兼容。
6. Python 当前将 `POSITION_RISK` 安全降级为未实现；总控已有正式 veto 的通用结构约束，
   但尚无 2H 确定性规则和 veto 优先级实现。

## 3. 阶段范围

允许：

- 通过 Agent 专用 Repository 只读查询现有模拟账户表；
- 在同一 `REPEATABLE_READ`、`readOnly=true` 事务中冻结账户事实；
- 生成确定性 `portfolioContext`；
- 扩展 Java/Python 2H 契约、验证、规则和总控；
- 持久化已有 V5 Schema 能承载的 evidence、veto 和 final decision；
- 单元、跨语言、HTTP、真实 PostgreSQL 和兼容回归测试；
- 同步 2F 已合入事实及 2H 任务分支状态。

禁止：

- 真实证券账户、券商接口、自动下单或真实资金操作；
- 修改现金、持仓、委托、成交、权益快照、风险事件或任何业务表；
- 调用 `PortfolioService.summary()`、`snapshot()`、`refreshAndCheckRisk()`、
  T+1 结算、行情刷新或风险事件写入路径；
- Agent 分析期间访问网络，或由 Python 访问数据库；
- 新增 Flyway、修改 V1 至 V9、伪造历史持仓或历史 PIT；
- 开始 2G、2I 或其他阶段；
- 输出买卖建议、交易执行指令或收益承诺。

## 4. portfolioContext 契约

### 4.1 时间和只读边界

- 市场时区：`Asia/Shanghai`。
- `analysisDate` 是上下文冻结时刻在上海时区的自然日。
- `request.tradeDate` 必须等于 `analysisDate`；历史或未来请求均安全不可用。
- 周末允许分析当前账户；估值价取每个持仓在请求日期及以前最近的本地 QFQ 日线。
- V1 固定声明：
  - `currentStateOnly=true`
  - `snapshotFrozenForTask=true`
  - `historicalPointInTimeGuaranteed=false`
  - `businessTablesReadOnly=true`

### 4.2 账户与持仓

账户事实至少包括账户身份、初始资金、现金、冻结资金、可用资金、重算市值、重算总资产、
已实现与重算未实现损益、费用、持仓/待确认委托数量、现金比例、组合限制和权益历史指标。

所有持仓按 `symbol` 升序；Java 使用本地 QFQ 最近收盘价重新计算市值、未实现损益、当前
仓位权重、移动止损价、预计仓位价值和预计仓位权重。价格缺失或距请求日期超过 7 个自然日
时整个上下文不可用。数据库保存的 `last_price` 只作为交叉验证事实，不作为 Agent 估值价。

### 4.3 待确认委托与权益历史

只冻结 `PENDING_CONFIRM` 委托并按 ID 升序。BUY 委托按 `grossAmount` 聚合预计暴露；
预计持仓数量是当前持仓 symbol 与 pending BUY symbol 的并集。

权益历史只读取请求日期以前的快照：

- `accountDrawdown=max(0,(历史峰值-当前总资产)/历史峰值)`；
- `dailyLossPct=max(0,(最近前一快照总资产-当前总资产)/最近前一快照总资产)`。

缺少任一历史指标不使上下文不可用，但该指标的 `available` 为 false，POSITION_RISK
终态为 `PARTIAL/WARN`（存在正式 veto 时仍为 `PARTIAL/BLOCKED`），并降低 confidence。

### 4.4 不可用 reasonCode

稳定区分：

- `PORTFOLIO_CONTEXT_NOT_CURRENT_DATE`
- `PORTFOLIO_ACCOUNT_INVALID`
- `PORTFOLIO_SETTINGS_INVALID`
- `PORTFOLIO_POSITION_INVALID`
- `PORTFOLIO_PRICE_MISSING`
- `PORTFOLIO_PRICE_STALE`
- `PORTFOLIO_ORDER_INVALID`

数据库连接或 SQL 异常直接使任务失败，不伪装为业务不可用。

## 5. POSITION_RISK V1

Python 只解释 Java 冻结事实，不访问数据库、不刷新行情、不重算来源数据、不修改账户。
即使 DATA_QUALITY 为 `BLOCKED`，只要 portfolioContext 有效，账户风险仍必须执行。

### 5.1 正式 veto 顺序

1. `POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT`
2. `POSITION_RISK_DAILY_LOSS_LIMIT`
3. `POSITION_RISK_MAX_POSITIONS_EXCEEDED`
4. `POSITION_RISK_POSITION_WEIGHT_LIMIT_<symbol>`
5. `POSITION_RISK_PROJECTED_WEIGHT_LIMIT_<symbol>`
6. `POSITION_RISK_STOP_LOSS_TRIGGERED_<symbol>`
7. `POSITION_RISK_TRAILING_STOP_TRIGGERED_<symbol>`

带 symbol 的 vetoCode 使用固定后缀，以满足 V5
`(task_id, run_id, veto_code)` 唯一约束并保留逐 symbol 的独立 veto。各组按 symbol 升序。
逻辑 vetoId 由固定顺序、vetoCode、scope 和 contextHash 确定；每个 veto 只引用唯一
`PORTFOLIO_STATE` evidence，reason 包含实际值、阈值和账户或 symbol 范围。

### 5.2 五类 finding、score 与 confidence

固定顺序：

1. 账户回撤和当日损失；
2. 持仓数量与集中度；
3. 现金和待确认委托暴露；
4. 止损、移动止损和目标位；
5. 估值价格新鲜度与上下文完整性。

无 veto 时 safety score 从 100 按冻结阈值扣分并截断到 `[0,100]`；存在任一正式 veto 时
score 固定为 0。confidence 从 100 开始，两个缺失历史指标和 4 至 7 天估值价各扣 20，
最低为 40，不受 DATA_QUALITY 门禁截断。目标位达到只进入第四类 finding，不产生正式 veto。

上下文不可用或契约非法时输出 `INSUFFICIENT_DATA/NOT_APPLICABLE`、score/confidence 为 0、
无 evidence、无正式 veto；契约非法使用 `POSITION_RISK_INPUT_INVALID`。

## 6. 总控规则

2H 下的优先级固定为：

1. 存在正式 veto：`REJECTED_BY_VETO/BLOCKED/vetoed=true/score=0`，
   完整引用全部逻辑 vetoId；该结论优先于 DATA_QUALITY 阻断。
2. 无正式 veto且 DATA_QUALITY 阻断：`BLOCKED_BY_DATA_QUALITY`。
3. 无正式 veto且 DATA_QUALITY 未阻断：由于 ANNOUNCEMENT_RISK 未实现，
   保持 `INSUFFICIENT_DATA`，不升级为投资结论或交易信号。

六个专业 run 顺序和数量不变，总控不是第七个 run，只有 POSITION_RISK 可以产生正式 veto。

## 7. Evidence 与 Java 二次校验

有效上下文恰好生成一份：

- `category=PORTFOLIO_STATE`
- `sourceType=JAVA_ENGINE`
- `sourceName=AgentPortfolioContextService`
- `sourceRef=contextSnapshot.portfolioContext`
- `fields={"portfolioContext": <完整冻结上下文>}`
- evidenceId 和 contentHash 稳定绑定 contextHash。

Java 在持久化前复核上下文字段白名单、资金与仓位重算、position/order 排序、
五类 finding、score/confidence、veto 顺序/ID/code/evidence、唯一 veto 权限、总控优先级，
并拒绝交易执行指令。非法响应在同一持久化事务前失败，不留下部分 evidence、veto 或 decision。

## 8. 测试与验收

实现与本地验收已覆盖：

- 当前日、历史日、未来日、周末、空仓、多持仓、稳定排序；
- 资金、持仓、估值、pending BUY/SELL、预计数量和集中度重算；
- 缺失/过期价格、权益指标完整或缺失；
- 每个 veto、多个 veto 的稳定顺序与唯一 ID；
- score/confidence 全部边界、目标位仅警告；
- DATA_QUALITY PASS/WARN/BLOCKED；
- context 不可用、非法字段、无交易指令；
- 六 run、正式 veto 持久化、逻辑 ID 到物理 ID、总控优先级；
- JSONB 往返、contextHash 重算、非法响应原子失败；
- Agent 执行前后所有模拟账户业务表逐行不变；
- 旧 2B、2D-1、2E-1、2F profile/contextHash 和规则兼容；
- `quant-core`、`quant-server`、Python compileall/unittest、真实 Java/Python HTTP；
- V1 至 V9 随机隔离 Schema 的真实 PostgreSQL，`Skipped=0`，精确清理且 public 基线不变；
- `git diff --check`。

所有测试证据必须标记为 Codex 本地执行结果，不得描述为 GitHub Actions CI。

本阶段当前 Codex 本地执行证据如下：

| 验收组 | 结果 |
|---|---|
| `quant-core` 全量 | `4/0/0/0` |
| 2H Java 定向 | `26/0/0/0` |
| Python `compileall` / 完整 unittest | 通过 / `92/0/0/0` |
| 真实 Java/Python HTTP | `4/0/0/0`，`Skipped=0` |
| 随机隔离 Schema 的 V1 至 V9 真实 PostgreSQL | `2/0/0/0`，`Skipped=0` |
| 安全非数据库 `quant-server` 全量 | `301/0/0/46` |
| 随机隔离 Schema 的 2D/2E/2F 真实兼容 | `29/0/0/0`，`Skipped=0` |

`quant-server` 的 46 项跳过是外部 Python/PostgreSQL 环境门禁跳过，不能冒充
真实闭环。包含绑定专用数据库 public 的旧 2D 测试类的另一次尝试受到已知 V6 checksum
不一致影响，不能描述为通过；本阶段没有 repair/clean 或改动 public。2H 真实测试精确
删除随机临时 Schema，并验证 public 基线和六张模拟账户业务表的逐行指纹前后不变。

## 9. 完成边界

当前只允许写为“实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交验收，
未合入”。Codex 单次 commit 并普通 push 后停止，不自行 merge，不开始 2G、2I 或其他阶段。
当前项目事实始终以 [CURRENT_STATE.md](../CURRENT_STATE.md) 为唯一权威来源。
