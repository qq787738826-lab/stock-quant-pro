# M2_STRATEGY_ENGINE_READY 阶段证据

## 结论

- 阶段状态：`M2_STRATEGY_ENGINE_READY=PASS`
- 长期任务分支：`codex/1.4.0-m2-strategy-engine-ready`
- 冻结集成基线：`3c73df6e38aa4e7d934dbf7a805ab7ec78eb9cbc`
- 交付契约：`STRATEGY_ENGINE_V1`、`BACKTEST_ENGINE_V1`、
  `STRATEGY_RESEARCH_API_V1`
- M2 真实 Provider 调用：0；Tushare 累计真实调用保持 55
- 数据库迁移和永久数据库写入：0

## 引擎与策略

- 统一 `Strategy`、参数化 `StrategySpec`、固定白名单 `StrategyRegistry` 和标准化研究结果。
- 代表策略为 Buy & Hold、均线动量、均值回归和跨证券横截面动量；策略用于验证引擎，
  没有按收益调参或宣称策略有效性。
- long-only 组合引擎覆盖现金、持仓、百股交易单位、T+1、先卖后买、佣金、最低佣金、
  卖出印花税、双向滑点、停牌/无价格/休市拒单、总仓位、单票权重、持仓数量和最大回撤停机。
- signal 固定在交易日收盘后形成，execution 只能发生在后续开市日开盘；证券只允许沪深主板，
  周末不得声明开市。非法时间事实、未来窗口事实、错误证券和重叠时间切分均 fail-closed。
- 输出包括 benchmark、equity curve、trade ledger、PnL、CAGR、年化收益/波动率、Sharpe、
  win rate、turnover 和最大回撤；现金守恒与 realized/unrealized PnL 对账必须精确通过。
- train/test 使用独立内容指纹的截止日切片；walk-forward 只返回样本外 fold。相同输入和参数的
  完整结果及 SHA-256 指纹完全一致，测试窗之后的事实不会改变 train/test 指纹。

## M1真实数据只读smoke

- 输入：`M1_RESEARCH_DATASET_V1`，证券 `600000/SSE`、`000001/SZSE`，
  `2025-01-02` 至 `2025-01-10`。
- 覆盖：7 个开市日、14 条 daily、14 条 adj_factor、18 条 trade_cal、14 条 formula-only QFQ。
- Buy & Hold 组合成交 2 笔；最终权益 `994222.72916200`，总收益 `-0.005777270838`，
  最大回撤 `0.0152610252`，Sharpe `-2.278744288465`，turnover `0.898312867617`。
- deterministic replay、会计守恒、收盘信号/次日开盘执行、typed fact、SYSTEM_KNOWLEDGE、
  数据质量、M1 未来数据边界和输出审计全部通过。
- 固定 Resident Broker 只读取研究数据库密码；M2 Runner 不读取 Tushare Token、不创建
  Provider 客户端。运行前后表级快照一致，Provider 调用 0、永久数据库写入 0。

该 smoke 证明真实 M1 数据到研究引擎的读取、时序、会计和结果链路，不证明策略 alpha。
M1 仍是 `SYSTEM_KNOWLEDGE` 与 formula-only QFQ，不具备完整公司行动 lineage 或
`PROVIDER_PIT_VERIFIED` 资格，因此不把结果描述为完整 PIT 历史效果证据。

## 测试与规模

- 核心 strategy/backtest 定向测试：16/0/0/0。
- deterministic fixture：20 只沪深主板证券 × 1000 个交易日 × 4 个策略；完整比较在普通
  单机测试中约 3.6 秒完成。
- 打包 JAR + Fake Provider + PostgreSQL 16 临时实例完整 E2E：PASS；M1 fake 调用 18，
  M2 Provider 调用 0，临时资源残留 0。
- E2E 覆盖 V1 至 V13、两证券增量和全窗口幂等、M1 typed fact/SYSTEM_KNOWLEDGE/QFQ、
  M2 只读适配、正式 Start-Class/build proof、结果脱敏与数据库快照不变。
- Broker M2 零 Provider 协议 7/0/0/0；M1 协议兼容 8/0/0/0；PowerShell 5.1 语法通过。
- packaged application smoke、Java 编译和 `git diff --check` 均通过。

## 边界

- 未新增 Flyway、Controller、scheduler、Agent、Shadow、订单或交易入口。
- Credential Manager、Resident Broker、watchdog、授权框架、F1F、build proof 和 output audit
  只复用必要能力；Broker 仅增加固定、只读、零 Provider 的 M2 smoke operation。
- 七项治理状态、F1F-B2 PASSED 和 M1 数据均未修改。完整 F1 十项技术证据缺口继续作为
  known limitation。
- M2 已满足 M3 调用策略目录、参数化回测、标准指标和策略比较的技术前置条件；M3 仍须独立
  阶段授权，本阶段未启动 Agent 编排、Shadow、真实订单、实盘或自动交易。
