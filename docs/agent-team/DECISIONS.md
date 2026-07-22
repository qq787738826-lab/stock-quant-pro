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

## 证券状态事件物化

14. **稳定证券身份必须显式映射**：跨来源证券身份必须通过 `security_identity_registry` 与 `source_security_identity_mappings` 的显式关系解析；`sourceInstrumentId` 缺失时不得物化事件。symbol、名称、代码前缀、board 或 exchange 均不得被用来自动合并身份。
15. **active 表示上市资格而非当日可交易性**：`active` 表示证券仍具有目标市场上市与 Universe 资格。普通停牌、临时停牌、无成交或缺 bar 不改变 `active`；`active=true` 必须满足 `listed=true`，退市结果必须为 `listed=false, active=false`。
16. **V1 事件转换不可放宽**：`SECURITY_STATUS_EVENT_V1` 继续复用现有完整 `resultingState` 和七类事件转换语义。初始链只能使用 `FULL_STATUS_SNAPSHOT`；增量事件只能改变冻结目标字段；多字段、身份或局部 valid 更正不得拆成多个 V1 事件。
17. **raw revision 到事件的基数固定**：一条 raw revision 恰好产生一个 terminal normalization result，并产生零或一个 V1 event。`NO_STATE_CHANGE` 不创建 event，但必须保留 attempt、result 和 Manifest V2 审计；身份未解析或不支持的变化必须安全终止。
18. **V6 event 主表保持唯一权威**：`security_status_events` 是唯一证券状态 event 主表；后续只允许增加稳定逻辑身份、namespace、assurance 以及独立 normalization/lineage 关系，不得创建第二套事件权威表。业务 Hash 禁止使用数据库物理 ID。
19. **Event lineage 与 Manifest V2 独立版本化**：每个首次物化的逻辑 event 恰好一条 append-only raw→event lineage；复用 event 只新增本次 normalization result。`INGESTION_MANIFEST_V2_SECURITY_EVENT` 在 run 创建时冻结，不能修改 `INGESTION_MANIFEST_V1` 既有编码或在封存时切换版本。
20. **事件时间与原子性**：event `knownAt` 必须等于 terminal attempt 的 `derivedKnownFrom`，effective、published、observed、recorded 与 known 时间不得混用，`completedAt` 不进入业务 manifest。event、首次 lineage、attempt 与 normalization result 必须在数据库事务中原子提交，并由唯一约束、不可变 trigger 和锁共同保护。

上述决策的完整字段、outcome、并发与验收契约见 [stage-2d2b1b-security-event-materialization-design.md](stage-2d2b1b-security-event-materialization-design.md)。它们只批准 TEST/DEMO 设计边界，不解除 FORMAL/PIT 门禁，也不代表 2D-2B-1B-1 已开始实现。
