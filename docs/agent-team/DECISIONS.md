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
12. **大阶段隔离**：每个大阶段使用独立任务分支、完整测试、ChatGPT 实际提交验收和用户 merge 批准，不跨大阶段偷渡功能；同一大阶段的内部工作包不分别提交或验收。
13. **单一事实来源**：`docs/agent-team/CURRENT_STATE.md` 是进度、能力边界和下一阶段入口的唯一权威状态文件。

## 证券状态事件物化

14. **稳定证券身份必须显式映射**：跨来源证券身份必须通过 `security_identity_registry` 与 `source_security_identity_mappings` 的显式关系解析；`sourceInstrumentId` 缺失时不得物化事件。mappingLogicalKey 只使用稳定业务字段，mapping append-only 且 TEST/DEMO 隔离，mappingAssuranceLevel 参与 event assurance 保守传播；同 key 不同 identity 必须 conflict。symbol、名称、代码前缀、board 或 exchange 均不得被用来自动合并或修复身份。
15. **active 表示上市资格而非当日可交易性**：`active` 表示证券仍具有目标市场上市与 Universe 资格。普通停牌、临时停牌、无成交或缺 bar 不改变 `active`；`active=true` 必须满足 `listed=true`，退市结果必须为 `listed=false, active=false`。
16. **V1 事件转换不可放宽**：`SECURITY_STATUS_EVENT_V1` 继续复用现有完整 `resultingState` 和七类事件转换语义。初始链只能使用 `FULL_STATUS_SNAPSHOT`；增量事件只能改变冻结目标字段；多字段、身份或局部 valid 更正不得拆成多个 V1 事件。
17. **raw、attempt、result 与 event 基数固定**：一条 raw revision 可以被多个 run 处理；每个 run/raw 可有连续的多个 terminal attempt，每个 attempt 恰好一个 result，每个 result 引用零或一个 event。相同 eventLogicalKey 全局最多一个 event 和一条 lineage；result 唯一边界是 attempt，不得错误地对 raw 建立全局唯一。AttemptStatus 与 normalization outcome 必须精确映射，`REJECTED` 不得替代 identity、contract 或 conflict 终态。
18. **V6 event 主表保持唯一权威**：`security_status_events` 是唯一证券状态 event 主表；eventContractVersion 必须持久化，来源 revision 唯一键必须包含 recordNamespace。同一 namespace、securityLogicalKey、contract 最多一个 FULL 根，非根必须引用同链 predecessor，且一个 predecessor 最多一个后继；direct SQL 不能构造双根或跨 namespace、identity、contract 链。业务 Hash 禁止使用数据库物理 ID。
19. **Event lineage 与 Manifest V2 独立版本化**：每个首次物化的逻辑 event 恰好一条 append-only raw→event lineage；复用 event 只新增本次 normalization result。`INGESTION_MANIFEST_V2_SECURITY_EVENT` 在 run 创建时冻结，覆盖该 run 全部 attempt/result；accepted/rejected 只取每条 raw 的最大连续 attemptNo，历史失败仍进入 manifest 但不重复计数。不能修改 `INGESTION_MANIFEST_V1` 既有编码或在封存时切换版本。
20. **事件时间与原子性**：event `knownAt` 必须等于 terminal attempt 的 `derivedKnownFrom`，effective、published、observed、recorded 与 known 时间不得混用，`completedAt` 不进入业务 manifest。event、首次 lineage、attempt 与 normalization result 必须在数据库事务中原子提交，并由唯一约束、不可变 trigger 和锁共同保护。

上述决策的完整字段、outcome、并发与验收契约见 [stage-2d2b1b-security-event-materialization-design.md](stage-2d2b1b-security-event-materialization-design.md)。它们只批准 TEST/DEMO 边界，不解除 FORMAL/PIT 门禁；2D-2B-1B-1 的实际完成状态继续以 [CURRENT_STATE.md](CURRENT_STATE.md) 为准。

## 阶段开发治理

21. **权威文档职责固定**：根目录 [AGENTS.md](../../AGENTS.md) 约束开发执行和安全；`CURRENT_STATE.md` 是当前进度、能力、阻断和入口的唯一事实来源；本文件冻结跨阶段决策；`ROADMAP.md` 只定义方向、顺序、依赖和验收门槛。`PROGRESS_LOG.md` 只能索引已经完成、已经验收或已经合入的历史里程碑及其提交、测试、ChatGPT 验收与用户 merge 批准证据，不得记录活动状态、当前阻断、当前入口、下一阶段或当前工作任务。`tasks/` 只保存大阶段或其内部工作包的任务边界。二者都不得成为平行状态权威。
22. **双窗口开发模式**：ChatGPT 窗口规划较大的完整开发阶段，定义架构、范围、边界和验收标准，并在 Codex 推送后基于实际 Git commit、完整 diff 和测试证据验收；Codex 开发窗口在一个任务分支连续完成该大阶段全部内部工作包，自主实现、测试、修复、commit 和普通 push，但不得 merge 或自行进入下一大阶段。用户只需对高风险业务选择和重大方案分歧作出最终选择，并最终批准 merge，不需要逐文件批准或批准普通 commit/push。详细执行规则以 [AGENTS.md](../../AGENTS.md) 为准。
23. **实际提交验收**：Codex 的聊天汇报、未提交工作区和自审不能代替最终验收。ChatGPT 必须检查已推送任务分支的实际 commit SHA、完整提交差异、测试证据、阶段边界和权威文档一致性，并向用户报告通过或不通过；不通过时由同一 Codex 开发窗口继续修复并提交推送。项目不固定设置第三个独立 Codex Review 窗口。
24. **大阶段连续交付**：路线图不构成自动开发授权。每次授权一个较大的完整开发阶段，大阶段可包含多个内部工作包；内部工作包不分别开发、Review、提交或验收，Codex 应连续完成，最终形成大阶段任务分支提交。ChatGPT 验收通过后由用户批准 merge；合并后 Codex 停止，ChatGPT 才规划下一大阶段。
25. **自主修复与高风险暂停**：普通编译错误、测试失败、局部重构、文件数量、常规实现选择和文档一致性问题由 Codex 在已授权大阶段内自主解决。仅当核心架构存在重大方案分歧、数据库核心模型或不可逆迁移、Java/Python 公共契约的重大选择、无法证明可消除的前视偏差、真实交易/下单/资金风险，或必须由用户选择的高风险业务规则时暂停。暂停报告必须说明原因、影响、方案、兼容性和迁移风险及所需选择；没有相应选择或新授权不得继续高风险实施。
26. **Canonical Hash 契约先于依赖实现**：任何大阶段新增会被持久化、进入公共契约或承担数据版本含义的领域 Hash 前，必须先在该大阶段架构中冻结版本化 canonical 契约。未冻结的算法、编码、Unicode、字段白名单、排序、空值、时间、精度、数据版本、跨语言和兼容策略不得由 Codex 猜测；如果形成重大公共契约、数据库模型或不可逆迁移选择，按第 25 条暂停。契约未冻结前不得实现依赖它的生产 Hash、迁移或公共契约。2F 内部工作包的具体门禁见 [tasks/2f0-backtest-context-foundation.md](tasks/2f0-backtest-context-foundation.md)。
27. **Knowledge-time 证据独立于业务日期与内容 Hash**：`trade_date<=requestTradeDate` 只证明业务日期上界，不证明该值在历史决策时点已经可知；内容 Hash 只证明给定内容稳定，也不证明历史可得性。可靠回测输入必须把决策时间、`knowledgeCutoff`、首次观察、来源/修订、快照或数据集版本、迟到与覆盖、公司行动和复权变化纳入可审计契约；无法证明 knowledge-time 合法性时 `backtestContext` 必须保持 `available=false`。2F 获准采用的不可变观察、PIT 模型、`known_at` 与 V9 迁移边界由下列决定冻结。

## 可靠回测与 STRATEGY_BACKTEST

28. **V9 本地 PIT 日线观察模型**：V9 新增 append-only `market_data_observation_batches` 与 `daily_bar_observations`，`daily_bars` 继续只是当前态兼容投影。观察批次和日线版本禁止 `UPDATE`、`DELETE`、`TRUNCATE`；同一来源、证券、交易日的 QFQ 内容和 revision 与当时最新观察相同时不重复追加，任一项相对最新观察变化时必须追加新版本，A→B→A 也保留后一次 A。成功的本地行情持久化在同一事务内先记录观察事实、再更新当前态投影；不得回填或伪造历史 `known_at`。
29. **2F 日终 knowledge-time 契约**：市场时区固定为 `Asia/Shanghai`；`decisionTime` 与 `knowledgeCutoff` 均为 `requestTradeDate` 当日最后一微秒。可靠输入同时满足 `tradeDate<=requestTradeDate` 与 `knownAt<=knowledgeCutoff`，非交易日可使用此前最近有效交易日，但 cutoff 不移动。请求日期在未来或日终决策时点尚未到达时安全不可用。内容 Hash 不替代 knowledge-time、来源 revision 或数据版本证据。
30. **版本化上下文兼容边界**：只有团队规则版本 `1.4.0-stage-2f-strategy-backtest-v1` 显式选择 `AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1`。旧 `create(symbol, tradeDate)` 入口及旧规则版本继续生成既有 `backtestContext.available=false`，不得改变 2B、2D-1、2E-1 的 contextSnapshot、contextHash、缓存键或结果。
31. **BACKTEST_CANONICAL_V1**：三个领域 Hash 固定使用 SHA-256、小写十六进制、UTF-8、Unicode NFC、对象字段名词典序、数组契约业务顺序、UTC `Z` 微秒时间及规范化十进制普通字符串；缺失字段与显式 `null` 不等价。`inputDataHash`、`strategyDefinitionHash`、`backtestResultHash` 各自只覆盖冻结白名单，数据库 ID、创建时间、日志与运行环境噪声不得进入。`dataVersion` 是独立的 PIT 模型、batch/dataset、所选观察版本和 source revision 对象，不得以 `inputDataHash` 冒充。
32. **冻结回测策略事实**：策略、引擎和参数版本分别为 `SMA20_NEXT_OPEN_RISK_EXIT_V1`、`BACKTEST_ENGINE_V1`、`BACKTEST_PARAMS_V1`。七项参数必须完整显式持久化；Java 是回测执行、稳定子区间和生产 Hash 的权威方。Python 不拉取行情、不访问数据库、不重跑策略或寻优，只按相同 canonical 契约校验并解释 Java 事实。
33. **STRATEGY_BACKTEST V1 安全边界**：可靠输入固定生成样本充分性、总收益、最大回撤、胜率与盈亏比、跨时间子区间稳定性五类 finding，并按冻结阈值计算 `[0,100]` score 与最高 80 的 confidence。DATA_QUALITY 阻断、上下文不可用、输入非法或交易样本不足均不得生成正常性能评分。STRATEGY_BACKTEST 永不产生正式 veto、投资建议、交易指令或收益承诺；POSITION_RISK 仍是唯一正式否决权，总控在公告风险与仓位风险未实现时仍保持安全不足结论。
