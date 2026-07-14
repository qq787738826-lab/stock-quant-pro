# 架构设计

## 分层

- Electron：Windows 桌面窗口与安装包，不接触券商密码。
- Vue 3：同花顺式终端界面。
- Spring Boot：业务 API、策略编排、账户、风控、回测、任务与审计。
- quant-core：纯 Java 指标、策略、风控、回测，可脱离 Web 测试。
- FastAPI：AKShare 免费数据适配、新闻/AI扩展。
- PostgreSQL：行情、信号、计划、账户、委托、回测、风险与AI报告。

## 核心流程

1. 数据服务获取前复权日线并落库。
2. 过滤沪深主板、ST、停牌、新股和不可交易状态。
3. 多因子引擎计算趋势、动量、量价、突破与波动率评分。
4. 风控生成仓位、止损、止盈和信号有效期。
5. 用户在桌面端创建待确认委托。
6. 用户确认后，仍需进入券商客户端最终核对和提交。

## 默认风险参数

- 总资金：100,000 元
- 最大持仓：5
- 单股权重：20%
- 止损：5%
- 第一止盈：8%
- 移动止盈：从最高价回撤4%
- 单日亏损3%暂停开仓
- 总回撤12%暂停策略

## 1.3.0 模拟交易子系统

```text
智能选股结果
  → trade_plans 交易计划
  → manual_orders 待确认委托与冻结
  → simulated_trades 模拟成交
  → portfolio_accounts / positions 资金和持仓
  → risk_events 风险触发
  → account_equity_snapshots 权益曲线
```

所有成交由用户在客户端人工确认，不连接券商账户。

## 1.4.0 股票智能体团队契约（阶段1A）

1.4.0 采用“Java权威任务与持久化、Python无状态统一分析、Vue只调用Java”的边界：

```text
Vue → Java agentTask/agentRuns → Python /agents/team/analyze
    ← Java校验并持久化 evidence/vetoes/agentDecision ←
```

- Java创建团队级任务和六个专业智能体运行记录，生成不可变上下文快照并计算SHA-256哈希。
- Java每个团队任务只调用一次Python统一分析接口。
- Python不访问智能体任务表，不修改Java分配的任务ID和运行ID。
- `agentRuns`只包含六个专业智能体；总控不是普通运行记录，其最终结果只保存到独立`agent_decisions`。
- 数据质量使用`gateStatus=BLOCKED`，不产生正式否决。
- 公告智能体只输出公告风险；只有资金仓位风控智能体可以产生正式否决。
- 总控不能解除正式否决，智能体不能修改模拟账户资金、持仓、委托或成交。
- 默认且当前唯一执行模式为`LOCAL_RULES`，不接入付费模型。

详细契约见[股票智能体团队架构](06-agent-team-architecture.md)和[证据契约](07-agent-evidence-contract.md)。
