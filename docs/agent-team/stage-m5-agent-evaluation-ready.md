# M5_AGENT_EVALUATION_READY 阶段记录

## 1. 状态与边界

- 冻结集成基线：`8e5a283969416e19f1e36e42251c7dcf6007edb3`
- 长期任务分支：`codex/1.4.0-m5-agent-evaluation-ready`
- 当前状态：`IMPLEMENTED_AWAITING_REVIEW`
- M5 开发期真实百炼调用：`0`；真实 Tushare 调用：`0`；永久数据库写入：`0`
- M5 不训练模型、不证明 alpha、不连接券商、不创建真实订单或真实资金操作。

## 2. 交付能力

### AGENT_SCORECARD_V1

统一保留 Evidence 正确率、工具正确率、无证据声明、合理 UNKNOWN、未来函数识别、风险识别、
冲突发现、Critic 纠错贡献、最终报告贡献、稳定性、一致性、Token/成本效率和延迟证据；七个职责
采用不同权重：Data 侧重数据/Evidence，Technical 侧重工具与一致性，Strategy 侧重工具与防未来
函数，Risk 侧重风险漏报，Portfolio 侧重组合约束和冲突，Critic 侧重纠错，Coordinator 侧重最终
报告和跨 Agent 一致性。收益不直接进入 Agent 质量分。

每个职责输出解释性 metric breakdown、样本数、失败模式和 `RETAIN/WATCH/DEMOTE/REPLACE`。
少于 3 份职责报告固定为 `WATCH`；永久替换需要至少 20 个可评价后验样本、重复失败模式以及离线
门禁共同支持。

### SHADOW_OUTCOME_EVAL_V1 与 confidence calibration

后验只允许从已冻结 Shadow run 读取，并要求 outcome 时间严格晚于 `researchAsOf`，支持 D1/D5/D20。
排名、方向、风险、Paper PnL/drawdown 和错误类型分开记录。只有可判定为 bullish/bearish 的方向型
判断进入 Brier 与三桶 ECE；`UNKNOWN`、`INSUFFICIENT_EVIDENCE`、空仓及非方向型 WATCH 不被当成
错误推荐。少于 20 个方向样本固定为 `INSUFFICIENT_SAMPLE`。

### AGENT_VERSION_REGISTRY_V1 与 CHAMPION_CHALLENGER_V1

版本绑定七 Agent prompt、model/provider、runtime、tool 和 strategy fingerprint。历史 Shadow 继续绑定
当时版本，V16 append-only 表与更新/删除拒绝触发器禁止覆盖。当前正式 M3/M4 lineage 是 Champion；
Critic V3 是固定、可复现的 Challenger，不直接覆盖 Champion。

晋升同时要求：固定 Agent Eval 全通过、至少 60 日 Replay、确定性 replay、防未来函数和风险门通过、
至少 20 个真实后验观察、质量分至少提高 2 分，且成本与延迟均不超过 Champion 的 1.25 倍。缺少任一
绑定证明即 `WATCH`，晋升仍需显式人工动作。

### RESEARCH_PERFORMANCE_REPORT_V1 与 UI

一次评测生成不可变版本快照、七 Agent scorecard、Shadow outcome、confidence calibration、
Champion/Challenger 比较、失败模式、成本/Token/延迟和样本充分性。最小 Vue 页面展示 Scorecard、
版本、后验、校准和晋升原因；后端只提供固定确定性 refresh，不允许传入动态版本或改写 M4 历史。

## 3. 持续 Shadow

用户已经批准 M4 Shadow 持续运行。M5 将 scheduler 固定为 Asia/Shanghai 17:20，仅工作日进入日历
判定，同一 `tradeDate + researchSlot + strategyVersion` 最多一次。已知开市日最多 6 个 Tushare
请求；日历未知时先用 SSE/SZSE 两次 `trade_cal` 判定，总计最多 8 次。休市日判定后不创建 Shadow、
不调用百炼。下一合法 Paper 执行日期只从 PIT 日历解析，不能由请求注入。

Broker 在读取凭据前执行月度 fail-closed 门禁：Shadow 百炼 CNY 30、Tushare 150 请求、项目所有外部
API CNY 200；每个 request/result 保存脱敏 usage/cost，M3/M4 已知历史成本采用保守 baseline，失败
也保留已消耗 usage。日级维护先幂等执行已到期 Paper order、追加可到期 outcome，再冻结当天研究；
重启不会重复成交。代码只有合并并部署 Java 服务后才会实际调度，本阶段没有触发真实运行。

## 4. 评测证据与已知限制

- 固定 Eval 覆盖未来数据、虚假 Sharpe/收益、缺失数据、过拟合、高收益高回撤、Agent 冲突等案例。
- deterministic fixture 为 2 只证券、180 个交易时点（360 bars），Replay 覆盖 5/20/60 日。
- 当前仓库可证明的真实 Shadow 冻结样本仅为 M4 smoke；没有足够的成熟 D1/D5/D20 方向样本，
  所以 confidence 和真实表现必须报告 `INSUFFICIENT_SAMPLE`，不得宣称长期胜率或 alpha。
- V16 只在随机临时 PostgreSQL 中迁移验证；本阶段不迁移永久数据库。
- M5 软件能力可供下一阶段调用，但长期淘汰/晋升结论必须等待持续 Shadow 的真实后验样本。

最终测试与任务提交 SHA 在任务分支收口后由实际 Git 和测试报告给出；本记录不得提前把未验收提交
描述成正式集成能力。
