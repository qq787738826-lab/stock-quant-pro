# 阶段 2H：可靠模拟持仓上下文与 POSITION_RISK 正式否决 V1

## 1. 状态

状态：**实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交验收，未合入集成分支。**

- 冻结集成基线：`4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`
- 任务分支：`codex/1.4.0-stage-2h-position-risk-v1`
- 规则版本：`1.4.0-stage-2h-position-risk-v1`
- context profile：`AGENT_CONTEXT_2H_V1`
- portfolioContext Schema：`PORTFOLIO_CONTEXT_V1`
- 固定模拟账户：`accountId=1`
- 完整范围与验收任务书：[2h-reliable-position-risk-v1.md](tasks/2h-reliable-position-risk-v1.md)

本状态不代表 ChatGPT 验收 PASS、用户 merge 批准或已合入。当前事实以
[CURRENT_STATE.md](CURRENT_STATE.md) 为唯一权威来源。

## 2. 仓库事实与实现边界

基线审计确认，V1/V4 已包含模拟账户、持仓、待确认委托、成交、权益快照、风险事件和
组合限制所需结构，V5 已承载六 run、evidence、正式 veto 与 final decision。因此 2H
没有新增 Flyway，也没有修改 V1 至 V9。

现有 `PortfolioService` 的汇总、快照和风险刷新路径可能执行 T+1 结算、行情刷新、
快照保存或风险事件写入，2H 不调用这些路径。新 Agent 专用 Repository 只读查询现有
PostgreSQL，Python 不访问数据库，Agent 分析期间不触发网络。

## 3. portfolioContext

### 3.1 时间与只读契约

`AgentPortfolioContextService` 在同一 `REPEATABLE_READ`、`readOnly=true` 事务中冻结：

- `analysisDate` 为上下文生成时的 `Asia/Shanghai` 自然日；
- `request.tradeDate` 必须等于 `analysisDate`，历史或未来日期安全不可用；
- 周末允许分析当前账户；
- 每个持仓使用 `trade_date <= requestTradeDate` 的最近本地 QFQ 日线收盘价；
- 价格缺失或距请求日期超过 7 个自然日时上下文安全不可用；
- `currentStateOnly=true`；
- `snapshotFrozenForTask=true`；
- `historicalPointInTimeGuaranteed=false`；
- `businessTablesReadOnly=true`。

2H 不伪造历史持仓版本，也不把当前模拟账户快照描述为历史 PIT。

### 3.2 账户、持仓、委托与权益

Java 从冻结明细重新计算并交叉验证：

- 初始资金、现金、冻结现金、可用现金、持仓市值、总资产、未实现损益、现金比例；
- 当前持仓数量、待确认 BUY/SELL 数量、最大持仓数和最大仓位权重；
- 按 symbol 升序的持仓数量、可用数量、成本、数据库价格、本地 QFQ 标记价、
  标记日期、价格年龄、市值、未实现损益、仓位权重、止损、目标位、移动止损、
  历史最高价、来源计划和最后买入日期；
- 按 ID 升序的 `PENDING_CONFIRM` 委托及其金额、冻结资金/数量和来源计划；
- 每个 symbol 的 pending BUY 暴露、预计持仓价值/权重，以及当前持仓与 pending BUY
  合并后的预计持仓数量；
- 请求日期以前权益快照对应的账户历史峰值、account drawdown、最近前一快照和
  daily loss。

缺少回撤或当日损失历史不使上下文不可用，但相应 `available=false`，POSITION_RISK
状态为 `PARTIAL` 并降低 confidence。

### 3.3 不可用语义

稳定 reasonCode 为：

- `PORTFOLIO_CONTEXT_NOT_CURRENT_DATE`
- `PORTFOLIO_ACCOUNT_INVALID`
- `PORTFOLIO_SETTINGS_INVALID`
- `PORTFOLIO_POSITION_INVALID`
- `PORTFOLIO_PRICE_MISSING`
- `PORTFOLIO_PRICE_STALE`
- `PORTFOLIO_ORDER_INVALID`

数据库连接或 SQL 异常直接使任务失败，不伪装成业务不可用。

## 4. POSITION_RISK V1

Python 只校验和解释 Java 冻结的 portfolioContext。即使 DATA_QUALITY 为 `BLOCKED`，
只要 portfolioContext 有效，账户级风险仍继续评估。

### 4.1 正式 veto

正式 veto 按以下组序和组内 symbol 升序稳定生成：

1. `POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT`
2. `POSITION_RISK_DAILY_LOSS_LIMIT`
3. `POSITION_RISK_MAX_POSITIONS_EXCEEDED`
4. `POSITION_RISK_POSITION_WEIGHT_LIMIT_<symbol>`
5. `POSITION_RISK_PROJECTED_WEIGHT_LIMIT_<symbol>`
6. `POSITION_RISK_STOP_LOSS_TRIGGERED_<symbol>`
7. `POSITION_RISK_TRAILING_STOP_TRIGGERED_<symbol>`

symbol 后缀使每个 vetoCode 在 V5 `(task_id, run_id, veto_code)` 约束下仍唯一。每个
逻辑 vetoId 稳定绑定顺序、code、scope 与 contextHash，属于 POSITION_RISK run，只引用
唯一 `PORTFOLIO_STATE` evidence，reason 包含实际值、阈值和账户或 symbol 范围。
目标位达到只形成风险提示 finding，不产生正式 veto。

### 4.2 Finding、score 与 confidence

有效上下文固定按以下顺序生成五类 finding：

1. 账户回撤和当日损失；
2. 持仓数量与集中度；
3. 现金和待确认委托暴露；
4. 止损、移动止损和目标位；
5. 估值价格新鲜度与上下文完整性。

无正式 veto 时 safety score 从 100 按任务书冻结阈值扣分并截断到 `[0,100]`；存在任一
正式 veto 时 score 固定为 0。confidence 从 100 开始，account drawdown 不可计算、
daily loss 不可计算、存在 4 至 7 天估值价时各扣 20，最低为 40，不受 DATA_QUALITY
WARN/BLOCKED 截断。

上下文不可用或响应契约非法时输出
`INSUFFICIENT_DATA/NOT_APPLICABLE/NOT_APPLICABLE`，score/confidence 为 0，
不生成 evidence 或正式 veto；非法输入使用 `POSITION_RISK_INPUT_INVALID`。

## 5. Evidence、Java复核与总控

有效 portfolioContext 恰好形成一份完整 evidence：

- category：`PORTFOLIO_STATE`
- sourceType：`JAVA_ENGINE`
- sourceName：`AgentPortfolioContextService`
- sourceRef：`contextSnapshot.portfolioContext`
- fields：完整冻结 portfolioContext

Java 在持久化前独立复核字段白名单、所有资金/仓位重算、position/order 排序、
五类 finding、score/confidence、veto 顺序/ID/code/evidence、唯一 veto 权限及总控结论，
并拒绝交易执行指令。非法响应在持久化事务前失败，不留下部分 evidence、veto 或 decision。

2H 总控优先级固定为：

1. 存在正式 veto：`REJECTED_BY_VETO/BLOCKED/vetoed=true/score=0`，完整引用 vetoIds；
2. 无 veto 但 DATA_QUALITY 阻断：`BLOCKED_BY_DATA_QUALITY`；
3. 两者均不存在：因 ANNOUNCEMENT_RISK 尚未实现而保持 `INSUFFICIENT_DATA`。

六个专业 run 不变，总控不是第七个 run；只有 POSITION_RISK 可以产生正式 veto。

## 6. 兼容性

只有精确 2H 规则版本选择 `AGENT_CONTEXT_2H_V1/PORTFOLIO_CONTEXT_V1`，并继续复用
2F 的可靠 backtestContext。旧 2B、2D-1、2E-1、2F 入口、contextSnapshot、
contextHash、缓存键和规则语义保持兼容。ANNOUNCEMENT_RISK 继续返回安全未实现结果。

## 7. Codex本地测试证据

以下均为 Codex 本地执行证据，不是 GitHub Actions CI。

| 测试组 | 运行/失败/错误/跳过 | 说明 |
|---|---:|---|
| `quant-core` 全量 | `4/0/0/0` | 回测核心回归 |
| 2H Java 定向合计 | `26/0/0/0` | context、profile、跨语言和真实 PostgreSQL；其中真实 HTTP 4 项、真实 PostgreSQL 2 项 |
| Python `compileall` | 通过 | `quant-ai/app` 与测试模块 |
| Python 完整 unittest | `92/0/0/0` | 包含 2H veto、边界、score/confidence、DQ 和旧规则回归 |
| 真实 Java/Python HTTP | `4/0/0/0` | `Skipped=0` |
| V1 至 V9 真实 PostgreSQL | `2/0/0/0` | 随机隔离 Schema，`Skipped=0` |
| 安全非数据库 `quant-server` 全量 | `301/0/0/46` | 46 项为外部 Python/PostgreSQL 环境门禁跳过 |
| 2D/2E/2F 真实兼容 | `29/0/0/0` | 随机隔离 Schema，`Skipped=0` |

真实 PostgreSQL 验收覆盖无风险、单 veto、多 veto、正式 veto 持久化和逻辑/物理 ID
映射、总控优先级、JSONB、Hash、非法响应原子失败、六 run，以及 Agent 执行前后
`portfolio_accounts`、`positions`、`manual_orders`、`simulated_trades`、
`account_equity_snapshots`、`risk_events` 逐行指纹不变。测试精确删除随机临时 Schema，
public 数据与结构基线前后不变。

另一次包含绑定专用数据库 public 的旧 2D 测试类的兼容尝试为 29 项通过、1 项
ApplicationContext 错误，原因是已知 V6 checksum 不一致，因此不描述为通过。本阶段
未执行 Flyway repair/clean，未删除、重建或修改 public。

## 8. 安全结论

- 没有新增迁移或修改 V1 至 V9；
- 没有访问外部数据源或真实账户；
- 没有调用模拟账户结算、行情刷新、快照或风险事件写路径；
- 没有修改现金、持仓、委托、成交、权益快照或风险事件；
- 没有输出买卖建议、自动交易指令或收益承诺；
- 正式 veto 是账户风险拒绝事实，不是交易执行指令；
- 2G 仍受公告来源、许可和 revision 语义阻断，未开始；
- 2I 和其他阶段未开始；
- 本任务分支未合入，等待 ChatGPT 基于实际 Git 提交验收。
