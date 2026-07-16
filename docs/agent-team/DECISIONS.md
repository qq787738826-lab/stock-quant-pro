# 智能体团队架构决策

本文件冻结跨阶段决策。更改这些决策必须单独评审，不能作为普通实现细节顺带修改。

## 权威与边界

1. **Java 权威**：Java 唯一生成 taskId 和六个 runId，负责状态、幂等、缓存、持久化和跨语言结果校验。
2. **Python 无状态**：Python 只分析 Java 提供的只读 contextSnapshot，不访问任务数据库，不保存状态，不改写 Java 身份。
3. **六智能体固定名单**：只包含 DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS、STRATEGY_BACKTEST、ANNOUNCEMENT_RISK、POSITION_RISK。
4. **总控不是 agentRun**：不得创建 `CHIEF_DECISION` run；总控结果只进入 `agent_decisions`。

## 风险、证据与决策

5. **POSITION_RISK 唯一正式否决权**：其他智能体不得声明或创建正式 veto。
6. **DATA_QUALITY 只负责门禁阻断**：`BLOCKED` 不是正式否决，DATA_QUALITY 的 `veto` 必须为 false。
7. **证据优先于自然语言结论**：finding 和 veto 必须引用真实、完整、唯一的 evidence；不得编造证据。
8. **规则评分与 LLM 解释分离**：可审计规则产生结构化评分和决策；未来 LLM 最多作为解释层，不得成为权威计算或证据来源。

## 演进与治理

9. **先本地 PostgreSQL，后外部数据源**：优先复用已有数据表建立只读上下文，再评估外部数据源。
10. **先规则能力，后 LLM**：先完成可复现、可测试的本地规则能力，再单独评估模型解释层。
11. **不自动交易**：智能体输出仅用于研究和模拟，不直接修改现金、持仓、成交或控制券商客户端。
12. **阶段隔离**：每个阶段使用独立任务分支、独立测试和独立人工验收，不跨阶段偷渡功能。
13. **单一事实来源**：`docs/agent-team/CURRENT_STATE.md` 是进度、能力边界和下一阶段入口的唯一权威状态文件。
