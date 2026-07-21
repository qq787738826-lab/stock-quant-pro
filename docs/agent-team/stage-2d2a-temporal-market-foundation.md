# 阶段2D-2A候选：历史事实版本与交易日历基础模型

## 状态

阶段2D-2A实现候选，已完成本阶段代码和测试，等待独立验收及合并。

- 来源集成基线：`3b88bfcc174f67b4abcffb8f54c4c9bfed9d62bf`
- 任务分支：`codex/1.4.0-2d2a-temporal-market-foundation`
- 迁移：`V6__temporal_market_foundation.sql`

本阶段只建立时态事实的数据库和Java基础。它没有生成历史证券池，没有接入历史数据源，没有修改 `MARKET_BREADTH_V1`，也没有实现无前视 `marketBreadth V2`、完整 `MARKET_REGIME` 或任何投资、回测和交易能力。

## 表结构

### market_data_dataset_versions

该表给一次来源发布、采集批次或回填批次分配不可变身份。它记录数据集类型、来源及来源版本、连接器版本、覆盖日期、采集与记录时刻、SHA-256载荷哈希、可信等级和JSONB元数据。

幂等键为：

`dataset_type + source + source_version + connector_version + range_start + range_end + payload_hash`

日期范围为闭区间元数据，`range_end` 不得早于 `range_start`。`fetched_at` 和 `recorded_at` 使用 `TIMESTAMPTZ`；metadata必须为非空JSON对象。生产Repository不提供原地更新或删除方法。

### security_status_events

该表保存原始证券状态事件或状态快照事实，支持：

- `LISTING`
- `DELISTING`
- `ST_CHANGE`
- `ACTIVE_CHANGE`
- `BOARD_CHANGE`
- `EXCHANGE_CHANGE`
- `FULL_STATUS_SNAPSHOT`

幂等键为：

`source + source_version + source_record_id + source_revision`

事件payload冻结为 `SECURITY_STATUS_EVENT_V1`，必须包含规范化 `resultingState`；增量事件只能改变其事件类型允许的字段，history逐字段必须与事件确定性计算结果一致。payload哈希由规范化JSON确定性计算并校验。更正必须新增事件，并通过 `supersedes_event_id` 指向旧事件。数据库触发器拒绝 `UPDATE`、`DELETE` 和 `TRUNCATE`，保证生产事实不会原地覆盖或移除。该表故意不引用当前态 `securities`，因此已经退市或不在当前投影中的历史symbol仍能作为权威事实保存。

### security_status_history

该表是从事件规范化得到的双时间投影，保存 exchange、board、listed、active、is_st、来源、可信等级和独立的 `SECURITY_STATUS_STATE_V1` 状态哈希。

- valid time：`[valid_from, valid_to)`，描述业务事实何时有效。
- knowledge time：`[known_from, known_to)`，描述系统何时认可该版本。
- NULL上界表示正无穷。
- 相邻区间允许。
- 更正先关闭旧 `known_to`，再插入新版本；旧版本继续保留。
- `recorded_at` 只是数据库记录时刻，不能代替 `known_from`。

数据库触发器只允许一次合法的 `known_to: NULL -> 非NULL` 关闭；修改其他字段、重新开放或二次关闭knowledge区间，以及 `DELETE`、`TRUNCATE` 均被拒绝。

数据库启用 `btree_gist`，通过 `symbol + daterange(valid) + tstzrange(knowledge)` exclusion constraint拒绝valid与knowledge同时重叠的矩形。重叠保护不是Java预检查。

投影幂等键为：

`source_event_id + valid_from + known_from`

### trading_calendar_revisions

该表分别保存SSE和SZSE每个交易日的知识版本，支持：

- `REGULAR`
- `HALF_DAY`
- `HOLIDAY`
- `TEMPORARY_CLOSURE`

开市日必须有合法的 `session_open_at` 和 `session_close_at`，且收盘晚于开盘；闭市日不得保存session时刻。表中不持久化 `previous_open_date` 或 `next_open_date`。

同一 `exchange + trade_date` 的knowledge区间使用GiST exclusion constraint防重叠。临时休市或日历更正关闭旧knowledge版本并新增版本。Java查询上一/下一开市日时根据同exchange、同knowledge cutoff的as-of日历事实动态推导。日历修订同样只允许一次合法关闭knowledge区间，并禁止其他原地修改、删除和清空。

幂等键为：

`source + source_version + source_record_id + source_revision + exchange + trade_date`

## 可信等级

V6仅允许：

- `OBSERVED`
- `BACKFILLED_VERIFIED`
- `BACKFILLED_INFERRED`

Java的保守判断规则为：

- OBSERVED只有具备明确known时间、来源版本和effective语义时才是PIT候选。
- BACKFILLED_VERIFIED只有具备同样完整语义时才是PIT候选。
- BACKFILLED_INFERRED永远不是PIT候选。
- dataset、原始事件和投影之间的可信等级只能保持或降低，不能由投影自行抬升；as-of结果采用整条来源链中最保守的等级。

“PIT候选”不等于已经保证无前视。本阶段不会把任何context字段升级为 `pointInTimeGuaranteed=true`。

## Java边界

独立包 `com.stockquant.server.agent.temporal` 提供：

- 数据集、事件、状态版本、日历修订和as-of结果的强类型record。
- trust、event、exchange和session枚举。
- 与数据库唯一约束完全一致的确定性幂等键。
- 独立的证券状态哈希；未修改 `AgentContextHashService`。
- 四个JdbcTemplate Repository。
- 一个事务服务 `TemporalMarketFoundationService`。
- 注入Clock，所有知识时刻使用Instant并按PostgreSQL微秒精度归一化。

事务服务支持：

- `registerDatasetVersion`
- `appendSecurityStatusEvent`
- `publishSecurityStatusVersion`
- `correctSecurityStatusVersion`
- `appendTradingCalendarRevision`
- `correctTradingCalendarRevision`
- `findSecurityStatusAsOf`
- `findTradingCalendarAsOf`
- `findPreviousOpenDateAsOf`
- `findNextOpenDateAsOf`

更正操作在单个Spring事务中先锁定旧版本，再关闭旧knowledge区间并插入新版本；插入失败时整个事务回滚。顺序或并发的同一更正重试都返回同一逻辑记录；相同幂等身份若解析到不同不可变内容则安全失败。服务同时校验dataset、事件和投影的symbol、source、sourceVersion、时间区间、supersedes关系及可信等级链，禁止逻辑错链和可信等级抬升。JSONB元数据及事件payload按对象字段无序、数组有序和数值数学值比较，避免PostgreSQL JSONB数值表示变化破坏幂等。as-of查询只使用valid/knowledge时间，最多接受一条结果，发现歧义时安全失败。

本阶段没有新增Controller或对外API。

## 索引、保留与隔离

V6提供来源幂等索引、dataset/event外键索引、symbol+valid/knowledge as-of索引、exchange+date+knowledge索引，以及当前knowledge部分索引。历史事实外键均使用 `ON DELETE RESTRICT`。

本阶段不分区。未来数据量和查询计划证明需要时，可按交易日期或记录时刻评估分区，但不能在没有真实规模证据时提前增加迁移复杂度。

V6不会：

- 修改V1至V5。
- 自动把当前 `securities` 或 `daily_bars` 回填到新表。
- 修改现有Agent五表。
- 修改当前态证券和QFQ日线语义。
- 调用Python、同步任务或外部网络。
- 接入现有 `AgentResearchContextReadRepository` 或 `AgentMarketBreadthContextService`。

`securities` 仍是当前态兼容投影，`daily_bars` 仍是legacy/current QFQ投影。新时态表在后续阶段验收前不参与生产 `marketBreadth`。

## 自动化测试

当前已执行：

- 时态模型、服务与迁移合同定向：17项，0失败，0错误，0跳过，BUILD SUCCESS。
- 完整Agent回归：191项，0失败，0错误，13项环境门禁跳过，BUILD SUCCESS。
- quant-server全量：209项，0失败，0错误，13项环境门禁跳过，BUILD SUCCESS。
- quant-core全量：1项，0失败，0错误，0跳过，BUILD SUCCESS。
- Python `compileall app tests`：通过。
- Python `unittest discover -s tests`：68项全部通过。
- `git diff --check`：通过。

真实PostgreSQL 16闭环已通过专用测试 `AgentStage2D2ATemporalMarketFoundationPostgresIntegrationTest`：

- Tests run: 2
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

真实测试在每次执行时创建唯一 `stage_2d2a_it_<uuid>` Schema，并将DataSource、Flyway default schema及schema history全部隔离到该Schema，从V1至V6顺序迁移。测试验证四表/约束/索引、dataset与event并发幂等、事件及dataset不可变、history与calendar仅允许一次knowledge关闭、相邻valid和knowledge区间、security与calendar数据库重叠拒绝、更正前后as-of回放、BACKFILLED_INFERRED、SSE/SZSE隔离、节假日、临时休市、上一/下一开市日动态推导、非法区间和更正原子回滚。并发测试使用两个不同PostgreSQL backend PID请求同一更正，断言只产生一个新逻辑版本、旧版本正确关闭、仅一个开放knowledge版本且不存在valid/knowledge重叠。测试结束仅删除本轮随机Schema，并确认Schema已不存在且public业务表和Flyway基线未变化。

## 当前限制与后续入口

- 尚未接入真实证券状态或交易日历来源。
- 尚未拥有任何历史证券池或每日universe快照。
- 尚未治理PIT行情版本、公司行动或QFQ前视问题。
- 尚未支持完整历史无前视回放。
- 尚未实现 `marketBreadth V2`。
- 尚未实现完整 `MARKET_REGIME`。
- 阶段2D-1的当前证券池受限规则和所有旧fixture保持不变。
- 未修改 `CURRENT_STATE.md` 或 `ROADMAP.md`。

真实PostgreSQL硬门槛、独立验收和合并完成后，后续唯一入口才是阶段2D-2B：基于已验收时态事实建立版本化每日universe快照。本阶段没有开始2D-2B、2D-2C、2D-2D、2D-2E或顶层阶段2E。
