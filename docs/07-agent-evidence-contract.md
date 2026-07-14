# 股票智能体证据与发现契约

## 1. 目标

所有智能体结论必须来源于真实行情、本地数据库、Java计算引擎、本地缓存或已配置数据源。智能体不得编造价格、指标、回测、公告、账户状态或新闻。

证据契约同时服务于：

- 单智能体 `findings` 的事实引用。
- 正式风险否决的依据。
- 总控最终决策的审计。
- Java 持久化前的引用完整性校验。
- Vue 对来源、时间和数据内容的展示。

## 2. Finding 契约

每个 finding 必须包含：

- `findingId`：当前输出内非空且应唯一。
- `code`：稳定的机器可读规则代码。
- `severity`：`INFO`、`WARN`、`HIGH` 或 `CRITICAL`。
- `title`：简短标题。
- `detail`：不得超出证据支持范围的说明。
- `evidenceIds`：至少一个证据ID，不允许空数组。

JSON Schema 只能校验 `evidenceIds` 存在、非空和元素格式，无法验证引用目标是否真实存在，也无法检查同一 finding 的引用是否语义相关。后续 Java `AgentResponseValidator` 必须完成跨数组引用完整性校验。

`findings[].evidenceIds`只能引用团队响应顶层权威`evidence`集合中实际存在的证据，不能只引用某个单智能体私有且未进入顶层集合的证据。

## 3. Evidence 契约

每条证据必须包含：

- `evidenceId`
- `category`
- `sourceType`
- `sourceName`
- `sourceRef`
- `symbol`
- `tradeDate`
- `observedAt`
- `collectedAt`
- `fields`
- `contentHash`

`tradeDate` 使用 `YYYY-MM-DD`。`observedAt`、`collectedAt` 和其他 date-time 字段使用带时区的 ISO-8601，例如 `2026-07-13T16:10:00+08:00` 或以 `Z` 结尾。仅包含本地时间但没有时区偏移的值不合格。

`contentHash` 是规范化证据内容的 SHA-256 小写十六进制值，用于审计和去重，不替代原始来源引用。

## 4. Evidence category

| category | 用途 |
|---|---|
| `MARKET_DATA` | 个股日线、价格、成交量和成交额 |
| `MARKET_BREADTH` | 上涨下跌家数、市场成交额和市场宽度 |
| `TECHNICAL_INDICATOR` | MA、RSI、MACD、ATR、量比等计算结果 |
| `SCAN_RESULT` | 扫描任务、候选过滤和排名 |
| `BACKTEST_RESULT` | 回测参数、周期、收益、回撤和交易次数 |
| `SECURITY_EVENT` | 公告、处罚、减持、解禁、诉讼等事件 |
| `PORTFOLIO_STATE` | 只读账户、持仓、冻结资金和风险状态 |
| `DATA_QUALITY` | 完整性、时效性、一致性和异常检查 |
| `QUERY_RESULT` | 查询为空、失败或无匹配数据的可审计记录 |

## 5. sourceType

| sourceType | 说明 |
|---|---|
| `DATABASE` | Java 从 PostgreSQL 现有业务表读取 |
| `LOCAL_CACHE` | 已标记来源和缓存时间的本地缓存 |
| `CONFIGURED_PROVIDER` | 项目明确配置的数据源 |
| `JAVA_ENGINE` | `quant-core` 或 Java 服务计算结果 |
| `PYTHON_RULE_ENGINE` | Python 本地规则从已有证据计算的派生结果 |

`PYTHON_RULE_ENGINE` 只能作为派生证据来源。派生 evidence 的 `fields` 应包含输入证据ID，Java 后续需要校验其输入引用。

## 6. 数据不存在与 QUERY_RESULT

不能在没有查询证据时声称“没有公告”“没有风险”“没有持仓”或“没有历史数据”。数据不存在、查询失败或查询结果为空时，必须生成 `category=QUERY_RESULT` 的 evidence，其 `fields` 至少记录：

```json
{
  "queryScope": {},
  "queriedAt": "2026-07-13T16:10:00+08:00",
  "returnedCount": 0,
  "dataSource": "",
  "outcome": "EMPTY",
  "reason": ""
}
```

`outcome` 建议使用：

- `SUCCESS`
- `EMPTY`
- `FAILED`
- `TIMEOUT`
- `UNAVAILABLE`

查询失败只能证明无法获得数据，不能证明风险不存在。公告数据源失败时，公告智能体应返回 `INSUFFICIENT_DATA`，而不是 `PASS`。

## 7. 风险边界

- `DATA_QUALITY` 可以根据数据质量证据设置 `gateStatus=BLOCKED`，但不能生成正式 veto。
- `ANNOUNCEMENT_RISK` 可以输出 `HIGH/CRITICAL` finding 或 `decision=REJECT`，但不能生成正式 veto。
- `POSITION_RISK` 可以引用公告、行情、账户和数据质量证据生成正式 veto。
- 正式 veto 必须至少引用一条真实 evidence。
- 总控只能继承正式否决，不能解除或降低否决。

## 8. 隐私与不可变性

账户证据只允许包含研究决策需要的只读数值，不应包含数据库密码、API Key 或券商凭据。智能体分析不能修改账户、持仓、委托、成交或冻结状态。

同一团队任务内 evidence 在 Java 接收并完成校验后视为不可变。若数据发生变化，应创建新任务并生成新的 `context_hash`，不能覆盖旧任务证据。

## 9. 团队级权威证据集合

- `agent-team-response`顶层`evidence`是Java最终持久化的权威证据集合。
- 单智能体输出中的`evidence`只是该智能体产生或使用的证据子集。
- Java按`evidenceId`合并和去重证据。
- 相同`evidenceId`重复出现时，`category`、`sourceType`、`sourceName`、`sourceRef`、`symbol`、`tradeDate`、`observedAt`、`contentHash`和`fields`必须完全一致。
- 相同`evidenceId`存在内容冲突时，Java拒绝整个团队响应，不允许选择其中一个版本继续分析。
- 所有finding和正式veto只能引用顶层权威集合中的证据。

JSON Schema只能验证证据及引用字段的基本结构。证据去重、内容一致性、跨数组引用完整性和与当前任务上下文的一致性由后续Java `AgentResponseValidator`执行。
