# 阶段 2D-2B-1B-0：证券状态事件物化契约冻结

## 1. 文档状态与权威关系

状态：**阶段 2D-2B-1B-0 契约已冻结并通过独立 GitHub 审查；生产实现尚未开始。**

- 来源集成基线：`505d18ca2e06c039163eada8f2f09f95cee97f30`；
- 文档任务分支：`codex/1.4.0-2d2b1b0-event-contract-freeze`；
- 首个契约提交：`c97d6a2c954f536eedd42796b1112aeaab421417`。

本文冻结阶段 2D-2B-1B 后续实现必须遵守的边界、业务语义、逻辑身份、物化基数、审计链、Manifest V2、并发事务和验收门槛。本文是设计契约，不是实现完成声明。

权威关系：

- 当前真实能力与下一阶段入口以 [CURRENT_STATE.md](CURRENT_STATE.md) 为准；
- 阶段顺序以 [ROADMAP.md](ROADMAP.md) 为准；
- 跨阶段稳定边界以 [DECISIONS.md](DECISIONS.md) 为准；
- Universe 总体设计以 [stage-2d2b-universe-snapshot-design.md](stage-2d2b-universe-snapshot-design.md) 为上位设计；
- V1 时态事实基础以 [stage-2d2a-temporal-market-foundation.md](stage-2d2a-temporal-market-foundation.md) 为准；
- 已冻结的 `SECURITY_STATUS_EVENT_V1` payload 与转换语义，以现有 `SecurityStatusEventPayloadContract` 为实现事实。

本文不修改 V1 至 V7，不创建迁移，不修改 Java、Python、测试、扫描或 `MARKET_BREADTH_V1`。

## 2. 阶段边界

### 2.1 2D-2B-1B 负责

- 建立 TEST/DEMO `security_status_raw_records` 到 `SECURITY_STATUS_EVENT_V1` 的可审计物化基础；
- 使用显式稳定证券身份及来源身份映射；
- 为每次最终处理 attempt 保存唯一 normalization result；
- 复用 V6 `security_status_events` 作为唯一事件权威；
- 保存权威 raw → event lineage；
- 引入与 event 绑定的 `INGESTION_MANIFEST_V2_SECURITY_EVENT`；
- 冻结幂等、并发、冲突、失败原子性和数据库不可绕过门禁；
- 为后续 history projector 提供经过校验的事件与 lineage 输入，但不执行 history 写入。

### 2.2 2D-2B-1B 不负责

- 不选择、采购、接入或验收真实证券状态来源；
- 不解除 V7 对 FORMAL 摄取的门禁，不宣称 PIT；
- 不实现真实 source adapter；
- 不实现 `SECURITY_STATUS_EVENT_V2`、多目标更正或局部 valid 区间更正；
- 不写入或重建 `security_status_history`；
- 不实现 trading calendar projection；
- 不生成 Universe snapshot，不切换生产扫描证券池；
- 不实现 PIT 行情、公司行动、复权或 `marketBreadth V2`；
- 不修改 Python `MARKET_REGIME`，不开始 2D-2C；
- 不产生投资建议、交易指令或券商连接。

### 2.3 Namespace 与 assurance 上限

2D-2B-1B-1 只允许 TEST/DEMO。FORMAL 必须继续由数据库拒绝，直到 2D-2B-1B-2 完成来源、许可、稳定 source instrument ID 和真实闭环的独立批准。

TEST/DEMO 事件不得被解释为正式 PIT 事实，也不得通过直接或传递 lineage 进入未来正式 Universe。事件 assurance 必须取 dataset、raw、processing attempt、发布时间验证、namespace 与阶段能力上限的保守最低等级。

## 3. 复用的现有权威资产

### 3.1 V6 时态事实

继续复用：

- `market_data_dataset_versions`：dataset 权威身份；
- `security_status_events`：唯一证券状态事件主表；
- `SECURITY_STATUS_EVENT_V1`：唯一允许在 1B-1 物化的事件 payload 契约；
- V6 事件 append-only 保护、来源 revision 唯一约束和 `supersedes_event_id` 关系。

不得创建第二套 event 主表。未来迁移只能为现有 `security_status_events` 增加冻结的身份、namespace 与 assurance 字段，以及增加独立 normalization/lineage 关系。

### 3.2 V7 摄取事实

继续复用：

- `market_data_ingestion_runs`；
- `security_status_raw_records`；
- security raw 与 run 的逐项关联；
- `security_status_processing_attempts`；
- dataset、raw、attempt、run 的 append-only、namespace、assurance、重试和封存语义；
- `INGESTION_MANIFEST_V1`，其既有 canonical 顺序与 Hash 语义永久保持不变。

1B-1 只能在新的 manifest contract 下增加 event-bound 审计材料，不能修改 V1 Hash 或把旧 run 静默解释为 V2。

## 4. 稳定证券身份

### 4.1 候选结构

后续迁移候选包含：

#### `security_identity_registry`

职责：保存不依赖 symbol、名称、board 或 exchange 的稳定 `securityLogicalKey`。

关键语义：

- `securityLogicalKey` 是业务逻辑身份，不使用数据库物理 ID；
- 身份一经建立即 append-only；合并、拆分或废止必须用独立审计事实表达；
- symbol、name、board、exchange 是可版本化属性，不是永久跨来源身份；
- 本阶段不实现正式 identity registry，只冻结接口与数据库门禁要求。

#### `source_security_identity_mappings`

职责：显式绑定一个 namespace 下的来源 instrument 身份与 `securityLogicalKey`。

最小可实现字段：

- `mappingLogicalKey`；
- `recordNamespace`；
- `source`；
- `sourceVersion` 或稳定的 `sourceContractLogicalKey`；
- `sourceInstrumentId`；
- `securityLogicalKey`；
- `mappingContractVersion`；
- `mappingAssuranceLevel`；
- `createdAt`、`recordedAt`，仅用于审计且不得进入业务 Hash。

`mappingLogicalKey` 必须由 recordNamespace、来源契约身份、sourceInstrumentId 和 mappingContractVersion 等稳定业务字段按冻结 canonical 协议生成，禁止使用数据库物理 ID。

1B-1 只支持 append-only mapping。一个 mapping key 只能指向一个 `securityLogicalKey`；TEST 与 DEMO 必须严格隔离；`mappingAssuranceLevel` 必须进入 event assurance 的保守最低值。同一 mapping key 指向不同身份时结果为 `CONFLICT`。

1B-1 不支持 mapping 原地修改、身份合并、身份拆分或 mapping correction，也不得按 symbol、name 或代码前缀自动修复。需要这些能力时必须安全返回 `UNSUPPORTED_CONTRACT` 或 `CONFLICT`，留给独立身份治理契约。

### 4.2 身份解析规则

- raw 的 `sourceInstrumentId` 非空且存在显式 mapping，才允许物化 event；
- 禁止按相同 symbol、名称、代码前缀、board 或 exchange 自动合并身份；
- 禁止把 symbol 直接作为跨来源稳定身份；
- symbol 或 exchange 属性修订不自动创建新的 `securityLogicalKey`；
- symbol 被另一个证券复用时必须建立新的 `securityLogicalKey`，不得复用旧身份；
- 找不到 mapping 时，attempt/result 终态为 `IDENTITY_UNRESOLVED`，不创建 event 或 lineage；
- TEST 与 DEMO mapping 不能被 FORMAL run 或未来正式 lineage 复用。

## 5. `active` 业务语义

`active` 表示证券仍具有目标市场上市资格和 Universe 成员资格，不表示当日是否可交易。

冻结规则：

- 普通停牌、盘中临时停牌、无成交或缺少 daily bar 不改变 `active`；
- 明确上市资格暂停可以使 `active=false`；
- 明确恢复上市资格可以使 `active=true`；
- `active=true` 必须同时满足 `listed=true`；
- `listed=false` 必须同时满足 `active=false`；
- `DELISTING` 必须产生 `listed=false, active=false`；
- `LISTING` 必须从 `listed=false, active=false` 进入 `listed=true, active=true`；
- 普通交易可用性、停牌分类、缺 bar 与当日可比价格属于 2D-2C，不得塞入 `active`。

任何来源字段只有在其语义能证明“目标市场上市资格”时才能映射到 `active`。名称、行情是否存在或当日成交量不得决定 `active`。

## 6. `SECURITY_STATUS_EVENT_V1` 契约

### 6.1 Payload

V1 payload 继续严格复用现有契约：

```json
{
  "schemaVersion": "SECURITY_STATUS_EVENT_V1",
  "resultingState": {
    "exchange": "SSE",
    "board": "MAIN",
    "listed": true,
    "active": true,
    "isSt": false
  }
}
```

字段集合必须精确匹配；未知字段、缺字段、类型错误、未知版本或非法枚举必须拒绝。payloadHash 继续由现有 V1 canonical payload 的 UTF-8 SHA-256 确定性生成。

### 6.2 事件类型与转换

| eventType | predecessor | 唯一允许的变化 |
|---|---|---|
| `FULL_STATUS_SNAPSHOT` | 必须为空 | 建立初始完整五字段状态 |
| `LISTING` | 必须存在 | `listed=false, active=false` → `listed=true, active=true` |
| `DELISTING` | 必须存在且 `listed=true` | 结果必须为 `listed=false, active=false` |
| `ST_CHANGE` | 必须存在 | 只改变 `isSt` |
| `BOARD_CHANGE` | 必须存在 | 只改变 `board` |
| `ACTIVE_CHANGE` | 必须存在 | 只改变 `active`，且始终满足 active/listed 不变量 |
| `EXCHANGE_CHANGE` | 必须存在 | 只改变 `exchange` |

额外冻结：

- 初始事件只能是 `FULL_STATUS_SNAPSHOT`；
- V1 增量事件必须以同一 `securityLogicalKey` 的确定 predecessor resultingState 为输入；
- 非目标字段变化必须拒绝；
- resultingState 与 predecessor 完全相同必须走 `NO_STATE_CHANGE`，不得伪造 change event；
- 多字段变化、symbol 身份变化、局部 valid 区间更正不得拆成多个 V1 event；
- 不使用 `eventOrdinal`，不伪造 `sourceRecordId`，不通过微秒偏移制造事件顺序；
- 需要多字段或局部 valid 更正时必须安全返回 `UNSUPPORTED_CONTRACT`，等待独立批准的 V2 契约。

## 7. 物化基数与 normalization outcome

正式基数：

```text
一条 raw source revision
→ 可以被一个或多个 ingestion run 处理
→ 每个 run/raw 可以产生连续的一个或多个 terminal processing attempt
→ 每个 terminal attempt 恰好一条 normalization result
→ 每条 normalization result 引用 0 或 1 个 SECURITY_STATUS_EVENT_V1
→ 相同 eventLogicalKey 全局最多一个权威 event 和一条权威 lineage
```

“每个 attempt 恰好一条 result”只适用于创建时已冻结为 `INGESTION_MANIFEST_V2_SECURITY_EVENT` 的 security-status run。既有 `INGESTION_MANIFEST_V1` run 不被追溯改写。

补充不变量：

- retry run 必须新增自己的 attempt/result 审计，不得复用父 run 的 attempt/result；
- 两个 run 处理同一 raw 时，各自拥有独立且连续编号的 attempt/result；
- 不得对 `raw_record_id` 建立“全局只有一个 attempt/result”的错误唯一约束；
- result 唯一键以 attempt 物理 FK 或 `attemptLogicalKey` 为边界；
- 同一 run/raw 的 `attemptNo` 从 1 开始连续递增，不得跳号或重复；
- Manifest V2 覆盖该 run 的全部 attempt/result，包括历史失败 attempt；
- run 的 accepted/rejected 统计只使用每条 received raw 的最大连续 `attemptNo` 对应最终 attempt；
- 历史失败 attempt 进入 Manifest V2 审计，但不重复参与最终 accepted/rejected 计数。

### 7.1 Outcome

| AttemptStatus | normalization outcome | event 数量 | 语义 |
|---|---|---:|---|
| `COMPLETED` | `EVENT_MATERIALIZED` | 1 | 本次事务首次创建逻辑 event 与唯一 lineage |
| `COMPLETED` | `EVENT_REUSED` | 1（既有） | 已存在完全相同 event；本次 result 引用并逐字段验证后复用 |
| `COMPLETED` | `NO_STATE_CHANGE` | 0 | 身份已解析，但 resultingState 与 predecessor 相同 |
| `IDENTITY_UNRESOLVED` | `IDENTITY_UNRESOLVED` | 0 | 缺少 sourceInstrumentId 或显式 mapping |
| `UNSUPPORTED_CONTRACT` | `UNSUPPORTED_CONTRACT` | 0 | 需要 V2、多字段、局部 valid 或身份治理契约，V1 不支持 |
| `CONFLICT` | `CONFLICT` | 0 | 同一逻辑 revision、mapping、身份或 predecessor 下出现不一致内容或竞争结果 |
| `PROJECTION_FAILED` | `PROJECTION_FAILED` | 0 | 事件投影输入准备或冻结校验失败；本阶段不写 history |
| `REJECTED` | `REJECTED` | 0 | raw schema、字段或业务校验失败 |

映射必须精确匹配。`REJECTED` 不得替代 `IDENTITY_UNRESOLVED` 或 `UNSUPPORTED_CONTRACT`，也不得用来隐藏 conflict。

`NO_STATE_CHANGE` 不是业务拒绝：

- 不创建 event 或 event lineage；
- 必须保存 terminal attempt 和 normalization result；
- 必须进入 Manifest V2；
- 属于最终 accepted 结果。

失败 outcome 必须保存脱敏、稳定的 errorCode；不得保存 raw payload、凭据或不确定堆栈文本到业务 Hash。

### 7.2 封存统计

- `received` 等于该 run 通过 run-record 关联逐项接收的 raw 数量；
- 对每条 received raw，只取该 run/raw 最大连续 `attemptNo` 对应的最终 attempt 计算统计；
- 最终 AttemptStatus 为 `COMPLETED` 时计入 `accepted`；
- `EVENT_MATERIALIZED`、`EVENT_REUSED` 和 `NO_STATE_CHANGE` 均属于 `COMPLETED`，因此均计入 accepted；
- 最终 AttemptStatus 为其他终态时计入 `rejected`；
- 必须满足 `accepted + rejected = received`；
- 缺少最终 attempt、attemptNo 不连续、一个 attempt 缺 result 或状态/outcome 不匹配时禁止封存；
- 所有历史及最终 attempt/result 均进入 Manifest V2，历史失败只提供审计材料，不重复增加 rejected。

## 8. Event 权威边界与逻辑身份

### 8.1 唯一 Event 主表

`security_status_events` 继续是唯一 event 权威表。候选扩展字段：

- `event_logical_key`：非空、唯一、64 位小写十六进制；
- `event_contract_version VARCHAR(...) NOT NULL`：当前只允许 `SECURITY_STATUS_EVENT_V1`；
- `record_namespace`：`FORMAL | TEST | DEMO`；
- `assurance_level`：`PIT_VERIFIED | RECONSTRUCTED_VERIFIED | INFERRED_RESEARCH`；
- `security_logical_key`：稳定业务身份。

不得创建 `security_status_event_records` 等第二套事件主表。数据库物理 `id` 仅可用于 FK 和查询定位，禁止进入任何 canonical 业务 Hash。

### 8.2 `eventLogicalKey`

逻辑材料固定为：

```text
TEMPORAL_CANONICAL_V1(
  rawRecordLogicalKey,
  eventContractVersion,
  eventType
)
```

结果使用 SHA-256。相同 raw、contract 与 eventType 必须得到相同 key；任一业务身份变化必须改变 key。

### 8.3 Event 唯一键与根/后继链

未来迁移必须删除或替换 V6 旧来源唯一约束：

```text
(source, source_version, source_record_id, source_revision)
```

新的来源 revision 唯一约束必须具有 namespace 作用域：

```text
(record_namespace, source, source_version, source_record_id, source_revision)
```

同时由数据库冻结：

- `eventLogicalKey` 全局唯一；
- 同一 `recordNamespace + securityLogicalKey + eventContractVersion` 最多一个 `FULL_STATUS_SNAPSHOT` 根事件；
- FULL 根事件的 predecessor 必须为空；
- 非根 event 必须引用 predecessor；
- predecessor 与新 event 的 recordNamespace、securityLogicalKey、eventContractVersion 必须完全一致；
- 一个 predecessor 最多一个合法后继；
- direct SQL 不得创建双根、跨 namespace 链、跨身份链或跨 contract 链；
- application 预检查不能替代唯一约束、FK、CHECK 或 trigger。

### 8.4 既有 V6 Event 的迁移候选

未来迁移对既有 V6 event 采用保守值：

- `record_namespace=DEMO`；
- `assurance_level=INFERRED_RESEARCH`。

冻结的迁移方式是为 namespace/assurance 列使用常量 `NOT NULL DEFAULT` 完成既有行保守赋值，随后 `DROP DEFAULT`，再添加约束。不得通过普通业务 `UPDATE` 回填，不得禁用 V6 不可变 trigger。

不能依据 symbol 猜测 `securityLogicalKey`。`securityLogicalKey`、`eventLogicalKey` 或 `eventContractVersion` 若无法仅凭既有权威事实可靠迁移，未来迁移任务必须显式阻断并单独处理；不得用空映射、伪造 identity 或降低约束掩盖。

本文不创建该迁移，也不预定迁移版本号。

## 9. Normalization Result 与 Event Lineage

### 9.1 `security_status_normalization_results`

职责：保存 V2 security run 中每个 terminal attempt 的唯一规范化终态。

候选核心字段：

- 物理主键及 attempt FK；
- `attemptLogicalKey`；
- `outcome`；
- 可空 `eventLogicalKey`；
- `securityLogicalKey`（身份未解析时为空）；
- `predecessorEventLogicalKey`（初始或身份未解析时为空）；
- `normalizerVersion`；
- `transitionRuleVersion`；
- `resultHash`；
- 脱敏 `errorCode`；
- 数据库权威 `recordedAt`。

约束：

- 一个 terminal attempt 恰好一个 result，唯一边界为 attempt FK 或 `attemptLogicalKey`；
- 同一 raw 可因不同 run 或连续 attempt 拥有多个 result，禁止对 raw FK 建立全局唯一约束；
- result 插入即终态，禁止 UPDATE、DELETE、TRUNCATE；
- outcome 与 eventLogicalKey 的空值组合必须由 CHECK/trigger 验证；
- `EVENT_MATERIALIZED`/`EVENT_REUSED` 必须引用存在且完全一致的 event；
- `NO_STATE_CHANGE`、`IDENTITY_UNRESOLVED`、`UNSUPPORTED_CONTRACT`、`CONFLICT`、`PROJECTION_FAILED` 与 `REJECTED` 不得引用 event；
- AttemptStatus 与 normalization outcome 必须严格符合 7.1 的映射；
- `resultHash` 只使用稳定逻辑身份、outcome、版本和脱敏业务字段，不使用物理 ID 或 recordedAt。

### 9.2 `security_status_event_lineage`

职责：保存每个逻辑 event 唯一的权威 raw → event 来源链。

候选核心字段：

- event FK 与 `eventLogicalKey`；
- dataset FK 与 `datasetLogicalKey`；
- raw FK 与 `rawRecordLogicalKey`；
- `securityLogicalKey`；
- 可空 predecessor event FK 与 `predecessorEventLogicalKey`；
- `recordNamespace`；
- `eventContractVersion`；
- `normalizerVersion`；
- `transitionRuleVersion`；
- `lineageHash`；
- 数据库权威 `recordedAt`。

约束：

- 每个逻辑 event 恰好一条 lineage；
- lineage 插入即终态，禁止 UPDATE、DELETE、TRUNCATE；
- dataset、raw、namespace、identity、event source revision、payloadHash 与 predecessor 必须属于同一冻结链；
- lineageHash 使用逻辑身份，不使用 event/raw/dataset 的物理 ID；
- `FULL_STATUS_SNAPSHOT` predecessor 必须为空；增量事件必须有 predecessor；
- lineage 的 event 必须和 raw revision、eventLogicalKey、payloadHash 逐字段一致。

### 9.3 Event 复用

其他 run 或 retry 复用既有 event 时：

- 不新增 event；
- 不新增第二条 event lineage；
- 为本次 attempt 新增 normalization result，outcome 为 `EVENT_REUSED`；
- 回读 event、lineage、payload、source revision、namespace、assurance、identity、predecessor 与所有版本字段并逐字段核验；
- 任何不一致均为 `CONFLICT`，不得把唯一约束异常直接视为成功。

## 10. `INGESTION_MANIFEST_V2_SECURITY_EVENT`

### 10.1 版本冻结

新 manifest contract 正式名称：

```text
INGESTION_MANIFEST_V2_SECURITY_EVENT
```

`INGESTION_MANIFEST_V1` 的字段顺序、NULL 编码、集合排序和 Hash 语义不得修改。

未来候选迁移必须给 `market_data_ingestion_runs` 增加 `manifest_contract_version`：

- run 创建时由受信服务按 datasetType 与阶段策略确定；
- 一经插入不可修改；
- 封存只读取持久化版本，不接受调用方再次传入或切换；
- 既有 V7 run 保守标记为 `INGESTION_MANIFEST_V1`；
- V2 仅适用于本契约定义的 security event materialization run；
- trading calendar 在本阶段继续使用 V1。

### 10.2 V2 覆盖材料

V2 在冻结的 run/raw/attempt V1 事实基础上，逐项覆盖：

- normalization outcome；
- eventLogicalKey（可空）；
- eventType（可空）；
- eventPayloadHash（可空）；
- securityLogicalKey（可空）；
- predecessorEventLogicalKey（可空）；
- recordNamespace；
- requested assurance；
- effective assurance；
- normalizerVersion；
- transitionRuleVersion；
- resultHash；
- lineageHash（无 event 时为空）；
- `NO_STATE_CHANGE` 的完整 result 材料；
- terminal errorCode 的冻结脱敏值。

V2 必须覆盖该 run 的全部 attempt/result，而不只是每条 raw 的最终 attempt。每条 canonical attempt entry 必须包含 run/raw 逻辑身份、连续 attemptNo、AttemptStatus、normalization outcome 及上述 event/result/lineage 材料。最终 accepted/rejected 统计只由每条 received raw 的最大连续 attemptNo 计算，但历史失败 attempt/result 仍留在 Manifest V2 中供审计。

`completedAt`、数据库物理 ID、recordedAt、日志文本和插入顺序不得进入业务 manifest。

### 10.3 Canonical 规则

- 使用冻结的 UTF-8、长度前缀、NULL/空字符串区分和 SHA-256；
- ordered array 保持契约顺序；
- semantic set 先验证逻辑 key 唯一，重复立即拒绝，再按 canonical bytes 排序；
- canonicalizer 不得静默去重、修复或重排非法业务输入；
- Java 与 PostgreSQL 必须共享固定黄金向量；
- V2 Hash 必须由数据库根据实际 run-record、attempt、result、event 和 lineage 集合独立重算或进行等价的强引用校验，不能只信任调用方提供的 Hash。

## 11. 时间语义

冻结规则：

- event `knownAt` 必须等于对应 terminal attempt 的 `derivedKnownFrom`；
- `derivedKnownFrom` 继续由 `KNOWLEDGE_TIME_POLICY_V1` 计算，来源不得直接声明最终 known time；
- event `effectiveFrom` 来源于已验证的业务有效日期；
- 来源仅有日期时保留 `sourceEffectiveDate`，不得伪造午夜 Instant；
- 有精确时间时先保存 `sourceEffectiveAt`，再按冻结规则转换为 `Asia/Shanghai` 业务日期；
- `publishedAt`、`systemFirstObservedAt`、`recordedAt`、`effectiveFrom` 和 `knownAt` 职责不得混用；
- 缺少可靠 effective time 时不得猜测，结果为 `UNSUPPORTED_CONTRACT`；
- 晚到 revision 使用可验证发布时间或首次持久观察时间推导 knownAt，不得用旧 effective time 倒填；
- 同一 raw revision 的 retry 必须复用首次冻结的知识时间；
- Instant 按 PostgreSQL 微秒精度规范化；
- `completedAt` 只作为 append-only attempt 审计时间，不进入 manifest 或其他业务 Hash。

## 12. Assurance 与 Namespace 传播

事件 effective assurance 必须等于下列上限的保守最低等级：

- requested assurance；
- dataset trust ceiling；
- raw trust ceiling；
- publication verification ceiling；
- processing attempt 持久化 assurance；
- identity mapping assurance；
- predecessor event/lineage assurance；
- record/run namespace ceiling；
- 2D-2B-1B 阶段能力上限。

冻结全序：

```text
INFERRED_RESEARCH < RECONSTRUCTED_VERIFIED < PIT_VERIFIED
```

规则：

- TEST/DEMO 不得提升为正式 PIT；
- `BACKFILLED_INFERRED` 永远只能得到 `INFERRED_RESEARCH`；
- assurance 不得由调用方任意声明或原地提升；
- event、result、lineage 与 manifest 中的 assurance 必须一致并由数据库校验；
- 任何 namespace 或 assurance 链不一致必须原子拒绝。

## 13. 幂等、并发与事务

### 13.1 幂等结果

- 同一 raw revision、相同 payload：复用同一 raw；
- 同一 raw、相同 event contract/type/result：复用同一逻辑 event；
- 同一 source revision、不同 payload：明确 `CONFLICT`；
- 同一 attemptLogicalKey、不同 normalization/result 内容：明确 `CONFLICT`；
- retry 必须产生自身 run/raw attempt/result 审计；同一 run/raw 的后续重试必须使用下一个连续 attemptNo；
- 两个 run 处理同一 raw 时，必须分别保存各自 attempt/result，不得共享 result；
- 任何幂等返回都必须回读并逐字段核验，不能只返回物理 ID。

### 13.2 锁与唯一胜者

- materialization 事务先解析显式 identity；
- 以 `securityLogicalKey` 获取数据库行锁或冻结的 transaction-scoped advisory lock；
- 锁内读取当前 predecessor 与 event/lineage；
- 数据库唯一约束是 eventLogicalKey、raw revision、attempt result 和 event lineage 的最终唯一性保护；
- 来源 revision 唯一约束必须包含 recordNamespace；
- 根/后继约束必须阻止双根和跨 namespace、security identity、event contract 的链；
- 禁止把“先查再插”作为唯一并发保护；
- 两个 revision 竞争同一 predecessor 时只有一个可成为合法后继；
- 第二事务获得锁后必须重新读取：完全一致则 `EVENT_REUSED`，不一致或 predecessor 已改变则 `CONFLICT`；
- 不得吞掉 GiST、唯一约束或 trigger 异常，也不得直接把异常当成功。

### 13.3 原子事务

成功物化必须在同一事务中完成：

1. 锁定 run、raw、identity mapping 与 predecessor；
2. 插入 terminal processing attempt；
3. 插入或严格复用 event；
4. 首次 event 插入唯一 lineage；
5. 插入 normalization result；
6. 验证 event/result/lineage/assurance/namespace 全链；
7. 提交。

无事件 outcome 在同一事务中插入 terminal attempt 与 normalization result。任何一步失败必须整体回滚，不留下孤立 attempt、event、result 或 lineage。

若预期外唯一约束竞争导致事务失败，服务必须在新事务、重新加锁和回读后确定 `EVENT_REUSED` 或 `CONFLICT`；不得在 rollback-only 事务中继续写审计事实。

## 14. 数据库不可绕过门禁

未来迁移必须在数据库层保证：

- identity registry、mapping、normalization result 和 event lineage 的 append-only；
- UPDATE、DELETE、TRUNCATE 均不能绕过；
- V2 run 每个 terminal security attempt 恰好一条 normalization result；
- result 唯一键以 attempt 为边界，不能以 raw 为全局唯一边界；
- 同一 run/raw 的 attemptNo 必须从 1 连续递增；
- outcome 与 event 引用组合合法；
- AttemptStatus 与 normalization outcome 映射精确一致；
- event 与 lineage 一对一；
- eventContractVersion 持久化且参与 eventLogicalKey 和链一致性校验；
- 来源 revision 唯一约束具有 recordNamespace 作用域；
- 同一 namespace、securityLogicalKey、eventContractVersion 最多一个 FULL 根，非根必须有同链 predecessor 且一个 predecessor 最多一个后继；
- event/source revision/dataset/raw/identity/predecessor/namespace/assurance 属于同一链；
- `active=true` 蕴含 `listed=true`；
- V1 转换只能改变允许字段；
- sealed run 不能新增 attempt/result/event 关联；
- FORMAL run、FORMAL mapping 或 PIT 提升继续被拒绝；
- manifestContractVersion 创建后不可变；
- V2 封存必须根据该 run 的全部 attempt/result/event/lineage 实际集合重算并验证 Manifest V2；
- received、accepted、rejected 必须按 7.2 规则计算并满足守恒等式。

Java 预检查不能替代数据库最终门禁。direct SQL、JdbcTemplate、批量 SQL 和并发连接都必须受到同样保护。

## 15. 后续 Java 组件候选

仅冻结职责，不在本任务实现：

- `SecurityIdentityRepository`：显式身份与 mapping 查询；
- `SecurityStatusNormalizer`：raw 到完整 resultingState 候选，纯确定性；
- `SecurityStatusTransitionValidator`：严格复用 V1 转换规则；
- `SecurityStatusEventMaterializationService`：事务、锁、幂等与冲突协调；
- `SecurityStatusNormalizationResultRepository`：append-only result；
- `SecurityStatusEventLineageRepository`：唯一 lineage；
- `SecurityEventManifestV2Hasher`：Java canonical/Hash；
- `SecurityEventManifestV2Validator`：封存前全链校验；
- PostgreSQL 对应 canonical/manifest 函数与不可变 trigger。

Normalizer 不访问外部网络，不查询 Python，不写 history。Repository 不自行推断 identity、状态转换或 assurance。

## 16. History Projector 输入边界

1B-1 完成后最多提供：

- 已验证的 V1 event；
- 稳定 `securityLogicalKey`；
- predecessor 关系；
- dataset/raw/event lineage；
- namespace、trust、assurance 和时间语义；
- normalization/result/manifest 审计。

它不写 `security_status_history`，也不证明双时间投影闭环完成。2D-2B-2 必须独立实现并验收 event → history 一致性、knowledge 版本、valid 区间、calendar projection 与 as-of 查询。

## 17. 验收矩阵

### 17.1 单元测试

- identity mapping 必须显式存在，禁止 symbol/name/prefix 自动匹配；
- mappingLogicalKey 确定性、TEST/DEMO 隔离、mapping assurance 保守传播及同 key 不同 identity 冲突；
- mapping 修改、合并、拆分和 correction 返回 `UNSUPPORTED_CONTRACT` 或 `CONFLICT`；
- `active/listed` 不变量及普通停牌不改变 active；
- V1 七类事件的合法/非法转换；
- 初始仅 FULL、增量必须有 predecessor；
- 多字段、无状态变化、未知版本和缺 effective time 的安全结果；
- raw → 多 run → 多连续 attempt → 每 attempt 一个 result → 每 result 0/1 event 的正式基数；
- retry 新增 attempt/result、两个 run 各自审计、result 不对 raw 建全局唯一；
- AttemptStatus 与八种 normalization outcome 的精确映射及 nullable event 字段组合；
- `NO_STATE_CHANGE` accepted 统计、最大连续 attemptNo 最终统计、历史失败只进入 manifest；
- received/accepted/rejected 守恒和缺 attempt/result/跳号时封存拒绝；
- eventLogicalKey、resultHash、lineageHash、Manifest V2 固定向量；
- 物理 ID、插入顺序和 completedAt 不影响业务 Hash；
- semantic set 重复项拒绝、合法输入顺序无关；
- assurance 与 namespace 保守传播；
- V1 Manifest 黄金向量保持完全不变。

### 17.2 Migration 与数据库元数据测试

- 从 V1 顺序迁移到未来实现迁移；
- V1 至 V7 checksum 和文件保持不变；
- `security_status_events` 是唯一 event 主表；
- 候选字段、唯一键、FK、CHECK、索引和 trigger 存在；
- eventContractVersion 非空，V6 来源唯一键被 namespace 作用域唯一键替换；
- 双 FULL 根、无 predecessor 非根、跨 namespace/identity/contract 链及同 predecessor 双后继均由数据库拒绝；
- 既有 event 的 namespace/assurance 使用常量默认保守赋值后 DROP DEFAULT；
- 无法可靠生成既有 event identity 时迁移显式阻断，不按 symbol 猜测；
- V1 run 保持 V1，V2 run 创建时冻结 manifest version；
- result/lineage/identity mapping 的 UPDATE、DELETE、TRUNCATE 由 direct SQL 拒绝；
- sealed run、namespace、assurance、V1 转换与链一致性不能被 direct SQL 绕过。

### 17.3 真实 PostgreSQL

- 专用 `stock_quant_test` 数据库和用户身份门禁；
- 每次创建严格前缀的随机临时 Schema，从 V1 顺序迁移；
- public Schema 与既有 Flyway 基线前后不变；
- 测试结束删除随机 Schema；
- TEST 与 DEMO raw 成功形成或复用 event；FORMAL 仍被拒绝；
- `IDENTITY_UNRESOLVED` 不产生 event；
- `NO_STATE_CHANGE` 不产生 event，但 result 和 Manifest V2 完整；
- raw schema/字段/业务校验失败使用 `REJECTED`，且不得替代 identity/contract/conflict outcome；
- 同一 raw 在多个 run 中各有 attempt/result；同一 run/raw 多次 attemptNo 连续且每次均有 result；
- Manifest V2 包含全部历史和最终 attempt/result，统计只使用最终 attempt；
- received/accepted/rejected 守恒，`NO_STATE_CHANGE` 计入 accepted；
- 同 revision 同内容幂等；同 revision 不同 payload 为 conflict；
- event reuse 不新增 lineage；
- predecessor 竞争只有一个胜者；
- direct SQL 创建双根、跨 namespace/identity/contract 链或同 predecessor 双后继均失败；
- 同 mapping key 指向不同 identity 为 conflict，mapping assurance 不得被提升；
- assurance 提升和 namespace 混用被数据库拒绝；
- event/attempt/result/lineage 任一步失败后无部分副作用；
- Java 与 PostgreSQL Manifest V2 黄金向量完全一致；
- direct SQL 不能绕过 append-only、转换、namespace、assurance 或封存门禁。

### 17.4 两 backend 并发

- 两个真实连接的 PostgreSQL backend PID 必须不同；
- 同 raw、同内容只产生一个 raw 事实；
- 两个 run 处理同一 raw：各自有 attempt/result，只有一个 event 和一条 lineage；
- retry run 拥有自己的 attempt/result，同一 run/raw 重试使用连续 attemptNo；
- 并发 event reuse 返回完全相同 event 内容；
- 两个 revision 竞争同一 predecessor 只产生一个合法后继；
- 第二调用加锁后回读，严格区分 reuse 与 conflict；
- 不把唯一约束异常直接视为成功；
- 所有并发连接关闭，事务回滚或提交边界明确。

### 17.5 回归与范围

- V6 时态测试、V7 摄取测试和 V1 Manifest 黄金向量继续通过；
- Agent、quant-server、quant-core 与 Python 既有回归不受影响；
- 不新增外部网络调用；
- 不写 history、calendar revision 或 Universe；
- 不修改扫描和 `MARKET_BREADTH_V1`；
- 随机 Schema 删除且 public 基线不变；
- `git diff --check` 通过。

## 18. 2D-2B 后续任务与准入门槛

### 18.1 2D-2B-1A：source-neutral ingestion foundation（已完成）

V7 已建立通用 ingestion run、security/calendar raw、terminal attempt、run-record 关联、重试、封存、namespace、assurance 与 Manifest V1。该能力不包含 event、history、calendar projection 或 Universe。

### 18.2 2D-2B-1B-0：security event contract freeze（已完成）

契约已冻结并通过独立 GitHub 审查；首个契约提交为 `c97d6a2c954f536eedd42796b1112aeaab421417`。本阶段未创建迁移或生产实现，不能宣称 event 物化已实现。

### 18.3 2D-2B-1B-1：TEST/DEMO event materialization foundation（未开始）

下一阶段唯一入口。实现 identity mapping 基础、normalization result、V1 event 物化、event lineage 和 Manifest V2；FORMAL 仍关闭。

### 18.4 2D-2B-1B-2：approved source adapter（外部决策阻断）

必须先批准正式来源、许可、本地持久化/回放权利、稳定 source instrument ID、published/effective/revision 语义。不得用当前免费聚合源默认替代。

### 18.5 2D-2B-1B-3：真实来源闭环验收（未开始）

只在 1B-2 获批并实现后，对真实来源、FORMAL namespace、许可边界、PIT assurance 与精确清理进行独立验收。

### 18.6 2D-2B-2：history/calendar bitemporal projection（未开始）

实现 event → history、calendar raw → revision、双时间投影、V2 correction 与 lineage 闭包；不得提前塞入 1B。

### 18.7 2D-2B-3：Universe snapshot（未开始）

实现不可变每日 Universe、逐行 inputs、members、三类 Hash、原子发布和扫描影子对比；生产扫描仍不切换。

## 19. 保持不变的能力边界

- `CURRENT_STATE.md` 仍是当前真实能力的唯一权威；
- 完整 2D-2B、2D-2 和 2D 均未完成；
- 当前没有 event 物化实现、稳定 identity registry、history/calendar projection 或 Universe；
- 正式证券状态来源和许可仍未决；
- PIT 行情、公司行动、复权和 `marketBreadth V2` 尚未开始；
- 生产扫描继续读取旧证券池路径；
- `MARKET_BREADTH_V1` 保持不变；
- Python 仍无状态；
- 不构成完整 MARKET_REGIME、投资建议或交易信号。

## 20. 冻结结论

阶段 2D-2B-1B-0 只完成证券状态事件物化契约冻结。后续 1B-1 必须在 TEST/DEMO 边界内，严格复用 V1 event 主表与转换语义，以显式稳定身份、每 terminal attempt 唯一 normalization result、每逻辑 event 唯一 lineage 和 Manifest V2 建立可审计闭环。

在正式来源、许可和稳定 instrument ID 获批前，FORMAL/PIT 继续阻断；在 2D-2B-2 与 2D-2B-3 分别完成前，不得宣称已具备双时间历史投影或版本化每日 Universe。
