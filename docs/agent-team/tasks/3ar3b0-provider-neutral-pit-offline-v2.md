# 3A-R3B-0 Provider 中立 PIT 市场事实 V2 离线闭环与 iFinD 试用准备

## 1. 阶段状态

- 冻结集成基线：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 任务分支：`codex/1.4.0-stage-3ar3b0-provider-neutral-pit-offline-v2`
- 状态：**第二次实际 Git 复验 findings 的增量修复和 Codex 本地验证完成，待 ChatGPT 基于新的实际 Git 提交复验，尚未 merge。**
- iFinD 真实调用数：`0`
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`
- Day 002 未创建，scheduler 未开启，3B 未开始。

本阶段只建立 Provider 中立的 TEST/DEMO 离线闭环和试用准备能力，不授予任何真实
Provider 资格，不把 Mock 结果描述为生产研究结论，也不修改 2F V1。

## 2. 冻结版本

| 能力 | 版本 |
| --- | --- |
| 团队规则 | `1.4.0-stage-3ar3b0-agent-team-pit-v2` |
| 上下文 profile | `AGENT_CONTEXT_3AR3B0_V2` |
| 回测上下文 | `BACKTEST_CONTEXT_V2` |
| 回测 canonical | `BACKTEST_CANONICAL_V2` |
| 市场事实 | `PIT_MARKET_FACTS_V2` |
| 市场事实 canonical | `PIT_MARKET_FACTS_CANONICAL_V2` |
| Provider 公共契约 | `MARKET_FACT_PROVIDER_CONTRACT_V1` |
| 原始日线 | `RAW_DAILY_BAR_OBSERVATION_V2` |
| 复权因子 | `ADJUSTMENT_FACTOR_OBSERVATION_V1` |
| 交易日历 | `TRADING_CALENDAR_OBSERVATION_V1` |
| 公司行动 | `CORPORATE_ACTION_OBSERVATION_V1` |
| QFQ 引擎 | `QFQ_AS_OF_ENGINE_V1` |
| 因子覆盖 | `DAILY_EXACT` |

## 3. 仓库审计与实施映射

| 既有能力 | 审计结论 | 本阶段处理 |
| --- | --- | --- |
| V6 交易日历与 dataset | 已具备 source namespace、dataset lineage 和时态基础 | 复用 dataset 外键和 lineage；V2 交易日历使用独立 observation 契约，不改 V6 |
| V7 摄取基础 | 已具备 run、manifest、assurance、source namespace 和终态保护 | 复用来源身份与资格思想；Provider DTO 与数据库 Entity 保持分层 |
| V9 PIT 日线 | 直接保存当前 QFQ，无法证明 raw/factor 分离或历史 factor as-of | 完全保留 V9；不回填、不转换，V2 使用独立 raw/factor 事实 |
| V10 公告观察 | 已验证 append-only、A→B→A、不可变触发器与并发链模式 | 复用模式，在 V13 为四类市场事实建立独立链 |
| 旧 Java/Python 行情桥 | 只传 Bar 值和 dataSource，revision 在上游不存在，普通持久化传 `null` | 新建 Provider 中立类型化契约；不提升 AKShare/Tencent 资格 |
| 2F V1 | profile、contextHash、缓存和回测引擎已冻结 | 复用回测引擎与七项参数；新建 V2 profile/context/canonical，V1 字节与语义不变 |
| 2I 六 run | ruleVersion 精确分派，总控不是第七 run | 为 V2 ruleVersion 显式注册六 run，复用已验收总控算法 |
| Shadow | 默认规则固定为 2I，并经 Java 任务系统执行 | 生产默认不变；仅允许显式 TEST/DEMO V2 ruleVersion |
| PostgreSQL 测试 | 已有随机 Schema 安全门，public 只读 | V13 所有迁移和闭环测试使用随机 Schema；public 只做 V12 validate/指纹保护 |

V13 是基线之后第一个未占用版本；V1 至 V12 均未修改。

## 4. V13 事实模型

迁移：`V13__provider_neutral_pit_market_facts_v2.sql`，Flyway checksum
`-408572418`。

### 4.1 表

1. `pit_market_fact_batches`
   - 冻结 Provider capability、source identity、dataset lineage、范围、完整性、
     revision/assurance/usage qualification 和 Provider 元数据；
   - 本地 `batchVersion`、datasetVersion 和 Hash 由 Java 生成。
2. `pit_market_fact_observations`
   - 四类事实的公共 append-only 观察头；
   - 保存自然键、predecessor、chainSequence、observationVersion、
     canonicalContentHash、provider metadata 和三类时间。
3. `raw_daily_bar_facts_v2`
   - 未复权 OHLC 必填；volume、amount、turnoverRate 可空；
   - 三个非价格字段分别保存 `PRESENT_VERIFIED/PRESENT_UNVERIFIED/MISSING`
     资格、冻结单位和语义代码，明确的 0 与缺失不等价。
4. `adjustment_factor_facts_v1`
   - `DAILY_EXACT` 因子及 `factorEffectiveTradeDate`。
5. `trading_calendar_facts_v1`
   - exchange、calendarDate、isOpen 和 session。
6. `corporate_action_facts_v1`
   - 稳定 action identity、类型、公告/生效日期和 terms。

V13 共建立 9 个查询/lineage 索引、5 个跨表校验触发器和 12 个
UPDATE/DELETE/TRUNCATE 不可变保护触发器，并只扩展 V11 Shadow 的允许 ruleVersion。

### 4.2 数据库硬门禁

- 禁止 UPDATE、DELETE 和 TRUNCATE；
- 按资格校验时间：`SYSTEM_KNOWLEDGE_ONLY` 必须
  `knownAt=firstObservedAt<=recordedAt`；`PROVIDER_VERIFIED` 必须同时具备
  providerRevision/providerPublishedAt，且
  `knownAt=providerPublishedAt<=firstObservedAt<=recordedAt`；
  providerUpdatedAt 如存在，必须位于 providerPublishedAt 与 firstObservedAt 之间；
- 拒绝周末 raw bar、上海时间 15:00 前的完整日线知识时间；
- 拒绝 NaN/Infinity 语义、非法 OHLC、负数非价格字段和非正因子；
- `SYSTEM_KNOWLEDGE_ONLY`、`PROVIDER_UNVERIFIED` 与
  `PROVIDER_UNAVAILABLE` 不得携带已限定的 provider dataset/revision/snapshot/
  publish/update 字段；
- batch 的 `providerDatasetVersion` 只有在
  `revisionQualification=PROVIDER_VERIFIED` 时才允许有值；其他资格必须为 null，
  Java 和 PostgreSQL 使用相同门禁；
- 非价格字段的 PRESENT 状态必须有值，MISSING 必须为 null；禁止把缺失补零；
- predecessor 必须属于同 source、同 instrument、同事实类型和同自然键；
- chainSequence、predecessor 和 observationVersion 唯一；
- observation 的来源身份不再被强制等于批次请求身份；raw、factor、calendar 和
  corporate action 各自使用稳定来源身份；
- 幂等只比较完整 semantic content hash；业务值、资格、许可或合格 Provider
  metadata 任一变化都追加版本；A→B→A 必须保留第三个物理版本；
- 并发相同捕获只能形成一个合法链尾。

迁移不回填 `daily_bars`、V9 或任何业务数据，也不把迁移时间伪装成历史 knownAt。

## 5. Provider 中立契约

Java `MarketFactProvider` 和类型化 DTO 覆盖：

- raw daily、adjustment factor、trading calendar、corporate action；
- source/provider instrument identity；
- revision、snapshot、published/update time 和历史版本能力；
- 本地持久化、历史回放、回测、Agent 内部使用许可；
- 覆盖范围、字段单位/精度、完整性；
- 错误、超时和限流信息。

Provider 只返回上游事实。`firstObservedAt`、`knownAt`、`recordedAt`、本地
datasetVersion、observationVersion 和 canonical Hash 只能由 Java 权威生成或验证。
公共契约不暴露数据库 Entity、旧 Bar 模型或 AKShare 专有结构。

每条 Provider 事实必须携带自己的稳定来源身份：raw 使用证券来源身份，factor 使用
证券/因子来源身份，corporate action 使用证券/事件来源身份，calendar 使用 Provider
交易所日历身份。批次仍保存请求范围身份，但不要求事实身份与批次证券身份相同。同一
Provider 的一个 SZSE 日历身份可以服务多只深市证券；SSE/SZSE、不同 Provider 或错误
证券身份禁止混用。

## 6. TEST/DEMO Mock Provider

`MockMarketFactProvider` 只读取仓库内合成固定夹具，不访问网络，并固定：

- `formalEligible=false`；
- TEST/DEMO source namespace；
- 仅在测试或显式 TEST/DEMO profile 下启用；
- 支持四类事实、完整和空响应、部分响应、错误、超时、限流、结构变化；
- 支持幂等、A→B→A、cutoff、可解释及不可解释 factor 变化；
- fixture 版本、Provider 契约和 canonical 向量稳定。

Mock 不取得真实 Provider 资格，不得写入正常业务库或用于 Day 002。

## 7. iFinD 禁用骨架

`IFindDisabledMarketFactProvider` 默认 disabled，不创建 HTTP/SDK 客户端，不安装
iFinD 依赖，也不读取凭据。即使误设 enabled，仍在任何网络动作前以
`IFIND_TRIAL_GATE_NOT_PASSED` 稳定失败。测试通过零网络 transport 证明该路径不能
发起请求。

真实函数、字段、权限、许可、revision 和 snapshot 继续记为 `UNVERIFIED`。
完整试用调用矩阵见 [iFinD 试用调用矩阵](../ifind-trial-call-matrix.md)。

## 8. Canonical、持久化与 as-of

`PIT_MARKET_FACTS_CANONICAL_V2` 固定：

- SHA-256、小写十六进制、UTF-8、Unicode NFC；
- 对象字段词典序，数组保持契约业务顺序；
- ISO-8601 日期和 UTC `Z` 微秒时间；
- Decimal 普通字符串、去无意义尾零、`-0` 归一为 `0`；
- 区分缺失与显式 null，拒绝 NaN/Infinity；
- 随机 UUID、数据库 ID、线程、主机、日志和本地捕获噪声不进入内容 Hash。

Java 是生产 canonical/Hash 唯一权威；Python 只验证仓库固定黄金向量。

每条 observation 的 semantic content payload 至少覆盖事实契约/类型、sourceCode、
该事实来源身份、自然键、业务字段及字段资格、revision/assurance/usage qualification、
四项许可标志，以及所有合格的 providerDatasetVersion/revision/snapshot/publishedAt/
updatedAt。未验证的候选 revision/snapshot 不得进入这些字段或可靠 Hash。完全相同
semantic hash 的连续捕获幂等；资格、许可或任一合格 Provider metadata 变化必须追加，
资格 A→B→A 保留三个可回放版本。

as-of Repository 显式接收 sourceCode、对应事实的 source identity 和 knowledgeCutoff，
只选择 `knownAt<=knowledgeCutoff` 的同 source 可见版本，并先按可靠资格、再按时间和
链序稳定选择，避免较晚捕获的低资格版本遮蔽可用的合格 Provider 版本。它返回完整
observationVersion/contentHash/predecessor lineage，不跨 Provider 拼接，不以当前最新
事实回填历史，也不返回半可靠窗口。

calendar、raw、factor 和 corporate action 四类查询必须先在全部 cutoff 可见版本中，
按 revision qualification 优先级、`knownAt`、`chainSequence`、数据库 `id` 的冻结顺序
选出唯一语义版本，随后才检查 usage qualification、local persistence、historical
replay、backtest 和 Agent use 许可。选中版本任一必要许可为 false 时固定返回
`PIT_USAGE_NOT_ALLOWED`；不得在选版前过滤该版本，不得回退到旧的允许版本，也不得把
许可撤销伪装成事实不存在。允许→禁止→重新允许必须保留三个 semantic 版本，并分别在
对应 cutoff 区间表现为可用、`PIT_USAGE_NOT_ALLOWED`、使用第三版恢复可用。

## 9. QFQ_AS_OF_ENGINE_V1

输入为 `(symbol, sourceCode, rawSourceIdentity, factorSourceIdentity,
calendarSourceIdentity, corporateActionSourceIdentity, requestTradeDate,
knowledgeCutoff)`。

1. 以 cutoff 前可见日历确定 `requestEffectiveTradeDate`；
2. raw window 只包含不晚于该日期的同 source/instrument 最新可见观察；
3. `anchorTradeDate` 必须等于窗口最后 raw bar 和 requestEffectiveTradeDate；
4. 每个日期 `t` 必须使用
   `factorEffectiveTradeDate == t` 的同 source/instrument/factorType 因子；
5. 锚点因子必须精确等于 anchor 日期；
6. 禁止 forward-fill、最近因子替代、当前因子补历史、从 QFQ 反推、跨 Provider
   拼接和删除缺失日期后重新归一化；
7. 缺少精确因子返回 `PIT_FACTOR_UNAVAILABLE`，不产生部分窗口；
8. calendar/raw/corporate-action lineage 不足时安全不可用；
9. factor 变化必须由 cutoff 前可见公司行动或合格 Provider revision 解释；
10. 非 Provider verified 的 factor 修订只能由同 symbol/source、合格 action identity、
    `action.effectiveTradeDate==factorEffectiveTradeDate`，且在当前 factor 版本可见前
    已可见的公司行动解释；时间相邻但日期或身份不匹配的 action 无效。

公式：

```text
qfqPrice(P,t,cutoff)
  = rawPrice(P,t,cutoff)
    * factor(t,cutoff)
    / factor(anchorTradeDate,cutoff)
```

输入由十进制字符串构造 BigDecimal；乘法保留完整精度，除法 scale=16、HALF_UP，
最终 OHLC scale=4、HALF_UP。volume、amount、turnoverRate 不复权，结果重新验证
OHLC。

共享黄金夹具固定 18 个可执行场景；每个场景包含 raw/factor/calendar/action 观察、
Provider/资格元数据、firstObservedAt、knownAt、请求日期、cutoff、预期可用性、
reasonCode、QFQ 输出、lineage 和固定 canonical/hash。Java 参数化测试逐项实际运行
`QFQ_AS_OF_ENGINE_V1` 并断言 18/18；Python 只验证已提交结果向量和 Hash，不建立第二套
QFQ 计算。场景覆盖首次捕获边界、D+1 不回填、公司行动重写、幂等、A→B→A、空 revision
的 SYSTEM_KNOWLEDGE_PIT、伪造 revision、calendar/factor 缺失、cutoff 排除、Java/Python
权威边界，以及“只有更早 factor”和“精确 factor 晚于 cutoff”均不得替代。

## 10. 2F V2 与六智能体

`BACKTEST_CONTEXT_V2` 记录 raw/factor/calendar/corporate-action/QFQ 的完整观察和
Hash lineage；`BACKTEST_CANONICAL_V2` 覆盖 cutoff、锚点、QFQ/舍入规则和回测结果。
它继续使用既有 SMA20、`BACKTEST_ENGINE_V1` 和七项完整参数，最多 500、最少
120 条，Java 权威运行回测，Python 只解释冻结结果。

QFQ 允许保留缺失的非价格字段；2F V2 的回测输入要求 volume 为
`PRESENT_VERIFIED`。volume 缺失或资格不合格时稳定返回
`PIT_REQUIRED_MARKET_FIELD_UNAVAILABLE`，不在转换为 Backtest Bar 时退化为泛化错误；
amount 和 turnoverRate 缺失仍按其字段资格和 null 原样进入 lineage。

精确 V2 ruleVersion 固定六个 run：

1. DATA_QUALITY
2. MARKET_REGIME
3. TECHNICAL_ANALYSIS
4. STRATEGY_BACKTEST
5. ANNOUNCEMENT_RISK
6. POSITION_RISK

除 STRATEGY_BACKTEST 切换到 V2 context 外，其余复用已验收算法；总控仍不是第七
run，POSITION_RISK 仍是唯一正式 veto 来源，2I 权重、优先级、终态和缓存语义不变。
新旧 ruleVersion 使用不同 contextHash/cache key。Python 不访问数据库、Provider 或
网络。

## 11. EXPLICIT Mock Shadow

生产默认 Shadow ruleVersion 仍为 2I，scheduler 仍默认关闭。V13 只允许测试环境以
EXPLICIT 模式选择 V2 ruleVersion；runner 仍通过 Java Agent 任务系统形成 batch、
item、六 run、总控、缓存和指标，不绕过持久化。

真实 PostgreSQL/Python 测试仅写随机隔离 Schema，结束后精确删除；不写正常业务库，
不修改 Day 001，也不创建 Day 002。

## 12. 脱敏与离线夹具

Java `OfflineFixtureSanitizer` 和 Python `offline_fixture.py`/命令行工具只处理本地 JSON：

- 递归处理对象和数组；
- 删除 Authorization、Cookie、token、session、password、username、account、
  机器路径和个人信息的大小写变体；
- 支持显式字段白名单；
- 生成 fixture schemaVersion、providerContractVersion、canonical 文本和 Hash；
- 拒绝输出中残留敏感字段、URL userinfo 或用户目录路径；
- 原始证据与可提交合成 fixture 分离。

仓库未包含真实 iFinD 响应、Cookie、账号或凭据。

## 13. 测试证据

以下均为 Codex 本地执行证据，不是 GitHub Actions：

| 测试组 | 命令摘要 | 结果 |
| --- | --- | --- |
| Java V2 定向 | Maven：Provider/QFQ 黄金向量/persistence/Shadow 定向 | 34/0/0/0，其中 QFQ 可执行黄金向量 18/18 |
| Python | `compileall`；`unittest discover -s tests -v` | compileall PASS；130/0/0/0 |
| quant-core | Maven `-pl quant-core test` | 4/0/0/0 |
| quant-server 安全全量 | Maven `-pl quant-server -am test` | quant-server 428/0/0/89；89 项为环境门禁，关键真实组另行 Skipped=0 |
| V13 PostgreSQL | `AgentStage3AR3B0PitV2PostgresIntegrationTest` | 16/0/0/0，Skipped=0 |
| V6→V13 双血统 | `AgentStage3AR1FlywayLineagePostgresIntegrationTest` | 1/0/0/0，Skipped=0，fresh/legacy 指纹收敛 |
| V2 Java/Python/Shadow | `AgentStage3AR3B0PostgresPythonShadowIntegrationTest` | 1/0/0/0，Skipped=0 |
| 旧阶段真实兼容矩阵 | 2D/2E/2F/2G/2H/2I PostgreSQL/Python/跨语言组 | 72/0/0/0，Skipped=0；旧测试的迁移集合断言同步验证 V1–V13 |
| AKShare 回归 | `AgentStage2GAkshareLiveGateTest` | 1/0/0/0，Skipped=0；仅验证既有研究级公告源 |

随机测试 Schema 最终残留为 0；public 仍停留 V12，只读 validate/结构与数据基线未
变化，未在 public 执行 V13。Vue 未修改。

## 14. 验收边界

- V1 至 V12 文件字节不变，V6 checksum 不变；
- 2F V1、2G、2H、2I profile、Hash、缓存和结果不变；
- V9 QFQ、`daily_bars`、Day 001 和正常业务表均未改写；
- AKShare/Tencent 仍不是正式 PIT Provider；
- iFinD 真实调用数为 0；
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`；
- 3A-R3B-1 尚未执行，Day 002 未创建，scheduler 未开启，3B 未开始。

本阶段完成不等于 iFinD 启动门 PASS、真实 Provider 已接入、完整 3A 完成或 3B 开始。
