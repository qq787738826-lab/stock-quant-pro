# 阶段 2D-2B 设计：证券状态摄取、双时间投影与每日 Universe 快照

## 1. 状态、定位与阶段边界

状态：**设计候选，READY WITH REQUIRED DECISIONS。尚未开始 2D-2B 实现。**

本文冻结阶段 2D-2B 的候选设计：建立“证券状态与交易日历事实摄取 → 双时间事实投影 → 每日不可变 Universe 快照”的治理闭环，为后续 PIT 行情、公司行动治理、MARKET_BREADTH_V2 和完整 MARKET_REGIME 提供证券池基础。

本阶段负责：

- 权威证券状态与交易日历来源的摄取契约；
- 上市、退市、暂停或恢复上市、ST/非 ST、active、board、exchange 状态变化；
- raw source record 到版本化事件和双时间投影的链路；
- 每个交易日、指定 knowledgeCutoff 下的不可变 Universe 快照；
- dataset、run、raw record、attempt、event、history、calendar、snapshot 的版本、assurance、lineage、幂等、并发、更正、重放与审计；
- 真实 PostgreSQL 门禁和生产扫描影子验证。

本阶段不负责：

- PIT 日行情、行情 revision 或发布时间治理；
- 复权因子与公司行动；
- 公告治理；
- MARKET_BREADTH_V2 指标生产；
- Python MARKET_REGIME 升级；
- 替换生产扫描证券池；
- 修改 MARKET_BREADTH_V1；
- 自动交易、券商连接、收益承诺或投资建议。

阶段 2D-2C 完成前不得宣称历史行情无前视；后续规则和评测完成前不得宣称完整 MARKET_REGIME 已实现。

## 2. 当前数据来源审计

当前 securities 是当前态兼容投影，可服务既有扫描与 MARKET_BREADTH_V1，但不能证明任意历史日期当时可知的上市、退市、ST、active、board 或 exchange 状态。现有同步链没有形成完整的不可变原始记录、来源 revision、发布时间、业务生效时间与系统知晓时间。

当前 daily_bars 是 legacy/current QFQ 投影，不是证券状态或交易日历权威，也不能用某日是否存在日线来唯一推断市场开市。

| 来源 | 当前字段 | 历史/revision能力 | 建议trustLevel | assuranceLevel上限 | PIT适用性 | 主要缺口 |
|---|---|---|---|---|---|---|
| securities | 当前证券状态 | 不能重建完整历史 | BACKFILLED_INFERRED | INFERRED_RESEARCH | 不支持 | 缺少历史状态、revision、published time与known time |
| daily_bars用于Universe资格 | 当前QFQ日线 | 缺少完整版本与发布时间链 | 不适用 | 不适用 | 不适合作为证券状态或日历来源 | 不是证券状态或交易日历权威 |
| 扫描结果 | 派生选择事实 | 不是证券状态revision | 不适用 | 不适用 | 不支持 | 属于派生结果而非来源事实 |
| 正式证券状态来源 | 尚未批准 | 待许可与契约审计 | 待来源验收 | 待来源验收 | 未定 | 来源身份、许可、revision及双时间语义未冻结 |
| 正式交易日历来源 | 尚未批准 | 待许可与契约审计 | 待来源验收 | 待来源验收 | 未定 | SSE/SZSE来源、许可、revision及发布时间未冻结 |

从正式摄取日起可积累可追溯事实，但不等于自动获得过去历史的 point-in-time 证明。使用当前状态回填旧日期只能标记为推断研究数据。

## 3. 总体数据流与权威边界

冻结链路为：

    raw source revision
      → market_data_dataset_versions
      → market_data_ingestion_runs
      → 类型专属 append-only raw record
      → terminal append-only processing attempt
      → SECURITY_STATUS_EVENT_V1 或独立评审后的 V2 修订事件
      → security_status_history / trading_calendar_revisions
      → 规范化 snapshot input rows
      → immutable snapshot manifest + members
      → 影子查询与差异验证

Java继续是摄取任务、事务、规范化、校验、投影、Hash、快照发布、持久化和审计的唯一权威。Python保持无状态，不访问数据库，也不参与本阶段 Universe 生成。

## 4. 稳定逻辑身份与业务 Hash

至少冻结：

- datasetLogicalKey：datasetType、source、sourceVersion、connectorVersion、业务范围、payloadHash；
- ingestionRunLogicalKey：runNamespace、operationType、datasetType、来源身份、业务范围、上游 manifest 身份与明确重试身份；
- rawRecordLogicalKey：recordNamespace、datasetType、source、sourceVersion、sourceRecordId、sourceRevision；
- eventLogicalKey：rawRecordLogicalKey、eventContractVersion、eventType；
- historyLogicalKey：eventLogicalKey、projectionRuleVersion、projectionRole、valid 区间、knownFrom、stateHash、derived-from 逻辑身份；
- snapshotMemberLogicalKey：snapshot 逻辑身份、symbol、exchange、historyLogicalKey。

以下物理数据库 ID 只能作为主键、外键和查询标识，禁止进入任何 canonical 业务 Hash：

- run ID；
- datasetVersionId；
- raw record ID；
- attempt ID；
- event ID；
- history ID；
- snapshot ID。

manifestHash、previousFullTimelineHash、correctionTargetSetHash、projectionSetHash、inputSetHash、lineageClosureHash、memberHash、membershipHash、provenanceHash 和 artifactHash 只能使用稳定逻辑身份与冻结业务字段。

所有集合必须去重并按冻结业务键排序；编码固定 UTF-8，算法固定 SHA-256。JSON canonicalization 和 PostgreSQL实现必须在编码前形成跨实现测试向量。

## 5. assuranceLevel 持久化、全序与传播

### 5.1 V6之后的迁移方式

分别为 security_status_history 和 trading_calendar_revisions 使用：

    ADD COLUMN assurance_level VARCHAR(32)
    NOT NULL DEFAULT 'INFERRED_RESEARCH';

随后立即执行：

    ALTER COLUMN assurance_level DROP DEFAULT;

之后增加白名单 CHECK：

- PIT_VERIFIED；
- RECONSTRUCTED_VERIFIED；
- INFERRED_RESEARCH。

迁移不得禁用不可变触发器，不得通过普通业务 UPDATE 回填。ADD COLUMN 的常量默认值只为既有记录保守赋值 INFERRED_RESEARCH；DROP DEFAULT 后，新记录必须显式写 assurance。

### 5.2 存储边界

Raw record不保存计算后的 assurance，只保存：

- recordNamespace；
- 来源身份；
- 原始时间字段；
- raw payload；
- payloadHash；
- source trustLevel。

计算后的 assurance 保存在：

- terminal processing attempt；
- event/history 或 calendar revision；
- sealed ingestion run；
- snapshot input；
- snapshot artifact。

### 5.3 全序与保守传播

强到弱的全序为：

    PIT_VERIFIED
    > RECONSTRUCTED_VERIFIED
    > INFERRED_RESEARCH

多输入和递归lineage取保守最低等级。trustLevel描述来源可信类别，assuranceLevel描述具体事实版本是否满足无前视条件；trust不能单独决定正式PIT资格。BACKFILLED_INFERRED永远不能提升为正式PIT。

assurance不得原地提升或修改。变化必须通过新knowledge版本；history/calendar继续只允许旧行执行一次 known_to: NULL → 非NULL，其他字段不可变。Java和PostgreSQL Hash都读取持久化assurance。

## 6. 通用摄取运行与类型专属事实

### 6.1 market_data_ingestion_runs

支持：

- datasetType：SECURITY_STATUS、TRADING_CALENDAR；
- runNamespace：FORMAL、TEST、DEMO；
- operationType：INGEST、BACKFILL、REBUILD、RETRY；
- status：运行态以及COMPLETED、PARTIAL、FAILED终态。

operationType不直接决定PIT资格。正式snapshot输入必须同时满足：

- runNamespace=FORMAL；
- status=COMPLETED；
- sealedAt非空；
- assuranceLevel=PIT_VERIFIED；
- manifestHash和最终统计验证通过。

封存字段至少包括：

- sealedAt；
- manifestHash；
- finalExpectedCount；
- finalReceivedCount；
- finalAcceptedCount；
- finalRejectedCount。

封存后数据库必须禁止新增raw record、增加processing attempt、修改dataset、最终统计、assurance或manifestHash。重试创建新run，不重新打开旧run。

### 6.2 类型专属Raw Record

保留：

- security_status_raw_records；
- trading_calendar_raw_records。

Raw record是append-only原始事实，不保存计算后的validationStatus、errors或assurance。

每条raw record有独立 recordNamespace：

- FORMAL；
- TEST；
- DEMO。

recordNamespace进入rawRecordLogicalKey。数据库必须拒绝FORMAL run引用TEST/DEMO记录，并拒绝正式snapshot经任何间接lineage引用TEST/DEMO记录。不得通过伪造source名称实现隔离。

### 6.3 Terminal Processing Attempt

保留：

- security_status_processing_attempts；
- trading_calendar_processing_attempts。

Attempt为插入即终态的append-only事实，不得先插入RUNNING再UPDATE，也不得修改旧attempt。终态至少包括：

- COMPLETED；
- REJECTED；
- CONFLICT；
- UNSUPPORTED_CONTRACT；
- PROJECTION_FAILED。

运行中状态由ingestion run或独立任务记录承担。

## 7. V1事件与V2完整修订边界

SECURITY_STATUS_EVENT_V1继续冻结完整resultingState：

- exchange；
- board；
- listed；
- active；
- isSt。

V1规则：

- 首次完整状态使用FULL_STATUS_SNAPSHOT；
- ST_CHANGE只改变isSt；
- BOARD_CHANGE只改变board；
- ACTIVE_CHANGE只改变active；
- EXCHANGE_CHANGE只改变exchange；
- LISTING与DELISTING按冻结规则改变listed/active；
- 增量事件基于上一有效状态确定性产生新状态；
- payloadHash由规范化payload确定性计算；
- history逐字段必须等于事件计算结果。

一条上游raw source revision原则上只产生一条规范化event。禁止通过eventOrdinal、伪造sourceRecordId或微秒knownAt顺序拆成多个事件。

已有状态发生多字段或局部valid区间修订时，必须使用独立评审的新完整状态修订契约，候选名称为 SECURITY_STATUS_EVENT_V2、FULL_STATUS_REVISION 或 STATUS_CORRECTION。正式名称与Schema待人工冻结。V1和V2采用独立验证与投影分支，不放宽V1，不修改V6历史语义。

映射基数冻结为：

    一条 raw source revision
    → 一条规范化 event
    → 一条或多条 history 投影行

## 8. V2局部Valid区间更正

### 8.1 Correction Targets

候选新增 append-only security_status_event_correction_targets。一条V2 event可引用多条target history/event。物理ID仅作FK；target canonical集合使用稳定逻辑身份。

V2必须保存：

- previousFullTimelineHash：更正前symbol全部开放knowledge时间轴；
- correctionTargetSetHash：本次实际锁定并关闭的target集合；
- projectionSetHash：最终完整新knowledge投影集合。

三者均禁止使用物理ID。

### 8.2 原子切分算法

所有valid区间统一采用半开区间 `[validFrom, validTo)`；`validTo=NULL`表示正无穷。BEFORE、CORRECTED和AFTER只有在区间非空时才能生成；每条具有有限上界的投影都必须满足`validTo > validFrom`，不得生成零长度投影。

同一事务内：

1. 按固定顺序锁定symbol全部受影响的开放knowledge行；
2. 重算previousFullTimelineHash与correctionTargetSetHash；
3. 验证旧时间轴连续、无重叠、无空洞；
4. 一次性关闭全部受影响旧knowledge行；
5. 按valid区间生成BEFORE、CORRECTED、AFTER；
6. CORRECTED状态必须等于V2 resultingState；
7. BEFORE/AFTER状态必须等于derivedFromHistory旧状态；
8. 未受影响区间必须完整进入新knowledge版本；
9. 同一event的多条history可使用相同knownFrom和不同validFrom；
10. 重算projectionSetHash并验证新时间轴无重叠、无空洞、无丢失；
11. event、targets、旧history关闭、projection lineage和新投影集合一并提交。

修订后完整可见时间轴的valid覆盖并集必须严格等于修订前完整可见时间轴的valid覆盖并集。普通V2 correction不得扩张或缩小历史覆盖范围，不得填补原有未知空洞，也不得丢失未受影响区间。扩大历史覆盖范围必须使用独立冻结的回填契约及相应assurance，不能复用普通V2 correction。

并发重试若同一更正已完成，应返回同一完整投影集合；不得再次插入，也不得把GiST排斥异常直接视为成功。

### 8.3 Projection Lineage

CORRECTED连接V2 event及全部correction targets；BEFORE/AFTER明确连接derivedFromHistory。trust与assurance采用修订来源和所有原始来源中的保守最低等级。

## 9. Trading Calendar摄取治理

交易日历走独立raw/attempt链并投影到trading_calendar_revisions。SSE与SZSE分别维护，不能由一个交易所推断另一个。

每条revision至少冻结：

- exchange；
- tradeDate；
- isOpen；
- sessionType；
- sessionOpenAt、sessionCloseAt；
- knownFrom、knownTo；
- source identity；
- trustLevel；
- assuranceLevel；
- dataset/run/raw lineage。

previous_open_date和next_open_date不持久化，按同exchange、同knowledgeCutoff的日历事实动态推导。

正式CN_A_MAIN snapshot要求SSE与SZSE在相同knowledgeCutoff下对tradeDate都有明确、PIT_VERIFIED的开市事实。任一缺失、冲突、降级或未封存都会阻止正式发布。两所临时休市的组合规则仍待人工冻结。

## 10. Universe Snapshot正式定义

交易日D、knowledgeCutoff K下的Universe，只能由K时刻已知并满足有效时间的交易日历、证券状态事实、市场范围规则、封存输入版本和完整lineage共同推导。

正式快照按交易日生成。非交易日不得生成同等语义的正式CN_A_MAIN快照。同一tradeDate允许不同knowledgeCutoff或规则版本产生不同、不可覆盖、可解释的artifact。正式Universe不得为空；输入不完整必须发布失败。

### 10.1 Snapshot Manifest

至少包含：

- snapshot业务身份；
- marketScope；
- tradeDate；
- knowledgeCutoff；
- generationRuleVersion；
- memberCount；
- snapshotAssuranceLevel；
- membershipHash；
- provenanceHash；
- artifactHash；
- generatedAt；
- status。

### 10.2 规范化逐行Inputs

每一行只对应一个明确run和dataset：

- inputRole；
- ingestionRunLogicalKey；
- runManifestHash；
- datasetLogicalKey；
- inputAssuranceLevel；
- lineageClosureHash。

不得用模糊dataset集合或datasetSetHash替代逐项输入。

每条market_universe_snapshot_inputs必须对应同一条冻结摄取链中的确定inputRole、sealed ingestion run、dataset version、run manifest、assurance和lineage closure。以下字段不得被调用方任意拼接：ingestionRunLogicalKey、runManifestHash、datasetLogicalKey、inputRole、inputAssuranceLevel和lineageClosureHash。

正式CN_A_MAIN至少必须同时具备：

- SECURITY_STATUS；
- TRADING_CALENDAR。

数据库deferred trigger必须验证：

- 两种inputRole都存在；
- 每行run均FORMAL、COMPLETED、sealed、PIT_VERIFIED；
- run.dataset_version_id与input.dataset_version_id一致，或input dataset存在于该sealed run冻结的逐项dataset清单中；
- inputRole与run.datasetType精确匹配：SECURITY_STATUS只能引用SECURITY_STATUS run/dataset，TRADING_CALENDAR只能引用TRADING_CALENDAR run/dataset；
- runManifestHash匹配封存事实；
- runManifestHash覆盖并证明该dataset属于run的封存manifest；
- inputAssuranceLevel必须等于run持久化assurance，且不得高于dataset及完整lineage保守计算出的最低assurance；
- ingestionRunLogicalKey、runManifestHash、datasetLogicalKey、inputRole、inputAssuranceLevel和lineageClosureHash必须属于同一冻结摄取链；
- lineage闭包中全部run和dataset都有对应input行；
- 正式snapshot全部直接和传递run/dataset都必须具有逐行input记录，不得仅依赖Hash声明；
- inputSetHash由全部input行按冻结顺序稳定重算；
- SSE/SZSE日历覆盖与knowledgeCutoff一致。

上述规则由Java预检查以提供快速、清晰的失败信息，但Java不是唯一保护。PostgreSQL DEFERRABLE CONSTRAINT TRIGGER必须在提交前完成最终权威核验，任一字段或摄取链不一致都拒绝整个snapshot发布事务。

### 10.3 Members

每个实际成员至少保存：

- symbol；
- exchange；
- board；
- listed；
- active；
- isSt；
- historyLogicalKey；
- lineageClosureHash；
- memberHash。

eligibility与exclusionReason不属于member canonical字段，也不进入membershipHash。排除原因未来若需审计，应单独设计eligibility decision事实，不作为本阶段硬门槛。

## 11. Membership、Provenance与Artifact Hash

### 11.1 membershipHash

只描述：

- tradeDate；
- marketScope；
- generationRuleVersion；
- 按exchange、symbol稳定排序的实际成员业务状态：symbol、exchange、board、listed、active、isSt、memberBusinessStateHash。

不包含run、dataset、lineage、generatedAt、物理ID、eligibility或exclusionReason。纯技术重试及runAttemptNumber不得改变membershipHash。

### 11.2 provenanceHash

描述：

- knowledgeCutoff；
- inputSetHash；
- 全部逐行snapshot inputs；
- runManifestHash；
- datasetLogicalKey；
- raw/event/history逻辑身份；
- lineageClosureHash；
- normalizer/projector契约版本；
- generationRuleVersion。

新run、新revision或lineage变化可以改变provenanceHash，即使membershipHash相同。

### 11.3 artifactHash

固定为：

    SHA-256(canonical({
      membershipHash,
      provenanceHash,
      snapshotAssuranceLevel
    }))

正式无前视消费者未来只能使用snapshotAssuranceLevel=PIT_VERIFIED的artifact。

## 12. lineageClosureHash

lineageClosureHash覆盖：

- 当前history/event/dataset/raw record/run；
- derivedFromHistory及其event/dataset/raw/run；
- correction targets；
- projection lineage；
- 必要的递归来源链；
- 各节点trust、assurance和契约版本。

闭包使用稳定逻辑节点和有向边，分别排序后计算Hash，不包含物理ID。

正式snapshot门禁必须验证：

- member闭包可由数据库事实重算；
- 全部run和dataset都有对应input行；
- 不存在TEST/DEMO来源；
- 不存在低于PIT_VERIFIED的来源；
- V2 targets、previousFullTimelineHash、correctionTargetSetHash和projectionSetHash一致；
- 不存在循环、孤立或未声明来源。

## 13. 幂等、并发、更正与重放

自然幂等键与数据库唯一保护至少覆盖：

- datasetLogicalKey；
- ingestionRunLogicalKey；
- rawRecordLogicalKey；
- terminal attempt逻辑身份；
- eventLogicalKey；
- historyLogicalKey及双时间排斥约束；
- snapshot input的snapshot、role、run、dataset组合；
- snapshot member的snapshot、exchange、symbol组合；
- artifact逻辑身份和artifactHash。

不得将“先查再插”作为唯一保护。

并发规则：

- dataset/raw/event依赖唯一约束与确定性冲突读取；
- V2更正按稳定symbol顺序加锁，锁后重算前置Hash；
- snapshot生成使用稳定advisory lock或等价数据库互斥；
- 第二调用若输入和Hash完全一致，返回并发胜出的逻辑结果；
- 输入不同则明确冲突；
- 失败不得留下部分history、input或member。

当两个真实连接同时请求相同snapshot逻辑身份时，第一个事务完成原子发布；第二个调用获得锁后必须重新读取数据库，并返回胜出事务已经提交的同一完整不可变snapshot，包括manifest、全部inputs、全部members、membershipHash、provenanceHash、artifactHash和assuranceLevel。第二个调用不得只返回artifact ID、snapshotKey或部分对象，不得在内存中重新组装一份未经过同一数据库门禁的结果。返回前必须验证完整集合及全部Hash与原请求一致；同一逻辑身份但内容不一致时必须报告冲突，不能将唯一约束或GiST异常直接视为成功。

更正与重放规则：

- 新revision创建新raw、attempt、event；
- 旧event不删除；
- 旧history只关闭knownTo；
- 旧snapshot永不覆盖；
- 晚到事实或规则升级生成新artifact；
- 历史查询必须同时指定tradeDate与knowledgeCutoff；
- 相同逻辑输入、版本和cutoff重放得到相同Hash；
- 重试创建新run，不重开旧run。

## 14. Snapshot原子发布与失败审计

Java在单事务内写入manifest、逐行inputs和members。数据库使用DEFERRABLE CONSTRAINT TRIGGER在提交前验证：

- required input roles；
- run封存和正式资格；
- 每条input的run.dataset、inputRole、runManifest、inputAssuranceLevel和lineageClosure属于同一冻结摄取链；
- 全部直接和传递run/dataset均有对应逐行input，且不存在仅以Hash替代的未声明来源；
- runManifestHash；
- inputSetHash；
- SSE/SZSE日历覆盖；
- lineage闭包；
- assurance；
- memberCount；
- 非空Universe；
- membershipHash、provenanceHash、artifactHash。

任一部分不合法，整个事务回滚。

提交失败时：

1. 发布事务整体回滚；
2. Java在真实commit边界外捕获异常；
3. 使用独立REQUIRES_NEW事务记录generation run为FAILED；
4. 只保存脱敏errorCode、failedPhase、finishedAt；
5. FAILED记录不得引用不存在的snapshot；
6. 重试创建新generation run，不修改失败run。

## 15. 失败与运维语义

- COMPLETED：预期输入全部接收、规范化、投影并封存；
- PARTIAL：只完成部分，不得成为正式snapshot输入；
- FAILED：运行失败，不得成为正式输入；
- 部分证券失败时不得生成正式Universe；
- TEST/DEMO不得进入FORMAL lineage；
- 日志至少保存runLogicalKey、datasetLogicalKey、source identity、contractVersion、manifestHash、knowledgeCutoff和脱敏错误码；
- 监控检测缺失snapshot、重复artifact、成员数异常、manifest漂移与lineage失败；
- 不得通过删除旧事实实现重跑。

## 16. 数据库候选结构与Java组件

V6之后候选新增或扩展：

- security_status_history.assurance_level；
- trading_calendar_revisions.assurance_level；
- market_data_ingestion_runs；
- security_status_raw_records；
- security_status_processing_attempts；
- trading_calendar_raw_records；
- trading_calendar_processing_attempts；
- security_status_event_correction_targets；
- security_status_projection_lineage；
- market_universe_generation_runs；
- market_universe_snapshots；
- market_universe_snapshot_inputs；
- market_universe_snapshot_members。

不得重复创建V6已有dataset、event、history或calendar权威表。

Java候选职责：

- 经批准来源的source adapter；
- security/calendar normalizer；
- ingestion与封存服务；
- 独立V1 projector；
- 独立V2 correction projector；
- calendar revision与as-of查询；
- universe snapshot原子发布；
- 独立canonical hash服务；
- lineage validation；
- audit/query；
- shadow comparison。

不新增Python生产逻辑，不修改AgentContextHashService。

## 17. 真实PostgreSQL测试矩阵

### 17.1 迁移与不可变性

- 从V1顺序迁移；
- assurance使用ADD COLUMN NOT NULL DEFAULT保守赋值并DROP DEFAULT；
- 不禁用触发器、不通过UPDATE回填；
- assurance不可原地修改或提升；
- append-only事实及sealed run的UPDATE、DELETE、TRUNCATE均被数据库拒绝。

### 17.2 摄取与封存

- security/calendar dataset、raw、event幂等；
- FORMAL/TEST/DEMO严格隔离；
- attempt插入即终态；
- 封存后不能新增raw/attempt或修改manifest、统计、assurance；
- manifestHash稳定重算；
- PARTIAL、FAILED、TEST、DEMO和非PIT输入被数据库门禁拒绝；
- 两个真实backend并发摄取只形成一个逻辑事实。

### 17.3 V1/V2与Calendar

- V1全部合法和非法转换；
- V2多字段、局部valid更正；
- BEFORE/CORRECTED/AFTER状态与lineage；
- 三个V2 Hash精确验证；
- 半开区间边界相邻且不重叠；
- validTo=NULL按正无穷处理；
- 零长度投影被Java和数据库拒绝；
- 修订前后valid覆盖并集严格相等；
- 普通V2对历史覆盖范围的扩张、缩小和填补空洞全部被拒绝；
- 新时间轴无重叠、无空洞、无丢失；
- assurance保守传播；
- 两个backend并发更正只形成一个完整新版本；
- SSE/SZSE隔离；
- 交易日、周末、节假日、临时休市及修订前后as-of；
- previous/next开市日动态推导。

### 17.4 Snapshot

- 上市前不进入、生效后进入、退市后退出；
- ST、board、active、exchange修订；
- 同一tradeDate不同knowledgeCutoff产生不同且可解释的Universe；
- 当前知识不污染旧cutoff；
- inputs逐项覆盖全部lineage run和dataset；
- 缺少任一role、TEST/DEMO间接来源或低assurance均拒绝发布；
- membershipHash不受技术重试影响；
- provenanceHash反映输入差异；
- artifactHash稳定；
- membershipHash不含eligibility/exclusionReason；
- 新revision不覆盖旧snapshot；
- 并发只发布一个逻辑artifact；
- 两个真实连接对相同snapshot逻辑身份的调用返回完全相同的manifest、全部inputs、全部members、三类Hash和assuranceLevel；
- deferred trigger失败整体回滚；
- REQUIRES_NEW仅保留脱敏FAILED generation run；
- 随机临时Schema最终删除，public基线不变。

PIT行情、公司行动、MARKET_BREADTH_V2与Python MARKET_REGIME历史回放属于2D-2C及后续验收。

## 18. 子任务拆分

### 18.1 2D-2B-0：契约与数据库门禁冻结

**目标**：冻结正式证券状态与交易日历来源、V2契约、knowledgeCutoff、canonical Hash、assurance以及数据库门禁设计。

**输入依赖**：已验收的2D-2A时态基础、本文设计和六项待人工冻结决策。

**数据库范围**：不创建生产迁移，只输出冻结设计、候选表结构、约束、触发器、SQLSTATE和迁移方案。

**Java范围**：不修改生产代码；只冻结未来repository、validator、projector、hasher和事务边界契约。

**测试硬门槛**：形成V1/V2、逻辑身份、canonical Hash、assurance、namespace、lineage和snapshot门禁的固定测试向量与验收矩阵，明确Java/PostgreSQL跨实现一致性。

**明确非目标**：不接入来源、不写迁移、不生成dataset/raw/event/history/calendar事实、不生成Universe。

**完成后仍不能声明**：不能声明已开始正式摄取、已建立PIT事实或具备PIT Universe。

### 18.2 2D-2B-1：通用摄取运行与双来源原始事实

**目标**：实现通用market_data_ingestion_runs、security/calendar raw records、terminal attempts、封存和manifest。

**输入依赖**：2D-2B-0全部必需决策、来源许可、逻辑身份、canonical Hash和数据库门禁已经冻结。

**数据库范围**：通过V6之后的新迁移增加摄取治理结构、唯一约束、namespace隔离、append-only和sealed run数据库保护。

**Java范围**：实现run/raw/attempt repository、service、hasher、validator和封存服务；不实现状态投影或snapshot发布。

**测试硬门槛**：单元测试覆盖逻辑身份、manifest与状态转换；真实PG覆盖双来源幂等、两个backend并发摄取、FORMAL/TEST/DEMO隔离、terminal attempt不可变、封存后禁止新增或修改、失败原子回滚和临时Schema/public基线保护。

**明确非目标**：不发布security event/history，不发布calendar知识版本，不生成Universe，不修改扫描或MARKET_BREADTH_V1。

**完成后仍不能声明**：不能声明已建立证券状态历史、权威交易日历、每日Universe或PIT Universe。

### 18.3 2D-2B-2：V1/V2状态投影与日历知识版本

**目标**：实现V1/V2状态投影、局部valid更正、calendar知识版本、projection lineage和lineage closure。

**输入依赖**：2D-2B-1完成；正式来源和V2契约获批；knowledgeCutoff、assurance传播及canonical Hash已经冻结。

**数据库范围**：增加history/calendar assurance字段、correction targets、projection lineage及V2兼容扩展，保持V1和V6既有语义不变。

**Java范围**：实现独立V1/V2 validator/projector、bulk as-of查询、lineage构造与验证以及并发更正服务。

**测试硬门槛**：单元和真实PG覆盖V1兼容、V2多target与before/corrected/after切分、半开区间和覆盖并集不变量、三个V2 Hash、assurance保守传播、两个backend并发更正、SSE/SZSE知识版本、动态previous/next开市日、namespace传递隔离及失败原子性。

**明确非目标**：不生成Universe、不读取PIT行情、不治理公司行动、不修改生产扫描或MARKET_BREADTH_V1。

**完成后仍不能声明**：不能声明已有每日Universe、PIT行情、MARKET_BREADTH_V2或完整MARKET_REGIME。

### 18.4 2D-2B-3：Universe原子发布与影子验证

**目标**：实现不可变Universe snapshot、原子发布、按knowledgeCutoff回放和生产扫描影子验证。

**输入依赖**：2D-2B-2完成；knowledgeCutoff业务规则、SSE/SZSE临时休市组合规则、逐行input门禁和snapshot canonical Hash已经冻结。

**数据库范围**：增加generation runs、snapshot manifests、规范化逐行inputs、members、唯一约束和DEFERRABLE CONSTRAINT TRIGGER。

**Java范围**：实现snapshot service、membership/provenance/artifact hashers、query/audit、REQUIRES_NEW失败审计和shadow comparison。

**测试硬门槛**：单元和真实PG覆盖required roles、run/dataset/input摄取链一致性、非空Universe、lineage闭包、三类Hash、相同逻辑身份幂等、两个backend返回同一完整snapshot、旧artifact不可覆盖、deferred trigger原子失败、FAILED generation run审计、历史回放、影子差异和临时Schema/public基线保护。

**明确非目标**：不切换生产扫描，不修改MARKET_BREADTH_V1，不实现PIT行情或公司行动，不开始2D-2C。

**完成后仍不能声明**：不能声明PIT行情、公司行动治理、MARKET_BREADTH_V2、完整MARKET_REGIME或自动交易能力。

生产扫描在整个2D-2B期间保持旧路径，只进行影子验证；MARKET_BREADTH_V1保持不变。

## 19. 风险与待人工冻结事项

### 19.1 主要风险

HIGH：

- 正式证券状态和交易日历来源未获批准，阻断FORMAL/PIT_VERIFIED摄取；
- V2契约未独立评审，阻断多字段和局部时间轴更正；
- knowledgeCutoff未冻结，阻断正式无前视声明；
- canonical Hash跨Java/PostgreSQL实现未冻结，阻断artifact权威发布。

MEDIUM：

- deferred trigger在大Universe上的性能和锁竞争；
- V2多symbol并发锁顺序与死锁；
- lineage closure递归规模；
- SSE/SZSE临时休市组合规则；
- 晚到修订造成artifact增长。

LOW：

- 长期数据增长后的分区需求；
- eligibility decision审计尚未设计，但不影响成员事实。

### 19.2 必须人工冻结

开始实现前必须明确：

1. 正式证券状态来源和许可；
2. 正式交易日历来源和许可；
3. SECURITY_STATUS_EVENT_V2正式名称及Schema；
4. knowledgeCutoff业务规则；
5. PostgreSQL canonical Hash实现及跨实现测试向量；
6. SSE/SZSE临时休市组合规则。

## 20. 最终结论

阶段2D-2B设计结论：**READY WITH REQUIRED DECISIONS**。

本文已整合时态摄取、V1/V2边界、局部valid更正、correction targets、projection lineage、稳定逻辑身份、assurance持久化、双来源封存运行、namespace隔离、逐行snapshot inputs、lineage闭包、三类Hash、原子发布、失败审计、并发重放、真实PG矩阵和2D-2B-0至2D-2B-3拆分。

当前仍是设计候选，尚未开始2D-2B实现；未创建迁移，未接入外部来源，未生成Universe，未修改生产扫描或MARKET_BREADTH_V1，也未开始2D-2C。
