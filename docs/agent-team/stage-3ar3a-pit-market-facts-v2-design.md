# 3A-R3A 可验证 PIT 市场原始事实 V2 设计冻结记录

## 1. 阶段状态

状态：**设计与 Codex 本地文档检查已在任务分支完成，待 ChatGPT 基于实际 Git 提交验收，
尚未合入；生产实现未开始。**

- 冻结集成基线：`4fea1e210e683fea8490685879529f1d27e6448b`
- 任务分支：`codex/1.4.0-stage-3ar3a-pit-market-facts-design-v2`
- 目标提交：`docs(agent): freeze pit market facts v2 design`
- 完整任务书：
  [tasks/3ar3a-pit-market-facts-v2-design.md](tasks/3ar3a-pit-market-facts-v2-design.md)

本阶段只冻结来源资格、事实模型、QFQ as-of 算法、黄金场景和后续实施门禁，没有修改
Java、Python、Vue、SQL 或 Flyway，没有写数据库、更新行情、摄取公告、创建 Day 002、
开启 scheduler、实现 2F V2 或开始 3B。

## 2. 触发背景

3A-R1 已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯 fast-forward 合入
`feature/1.4.0-agent-team`，最终提交为
`4fea1e210e683fea8490685879529f1d27e6448b`；精确验收和批准时间没有仓库证据，记为
`UNKNOWN`。

Day 001 已形成一个真实受控 Shadow 批次和三个 item，三项均因 DATA_QUALITY 阻断得到
`BLOCKED_BY_DATA_QUALITY`，该结果属于预期安全结论。正式人工复核已追加。后续通过现有
生产行情入口更新 `000001`、`000002`、`000006`，消除了
`MARKET_DATA_TOO_STALE`，并形成 3 个本地观察批次和 780 条 V9 日线观察；全部
`sourceRevision=NULL`，因此没有创建 Day 002。

受控只读来源审计确认当前结论为：

```text
PROVIDER_REVISION_UNVERIFIED
```

## 3. 当前调用链与缺口

当前链路冻结为：

```text
POST /api/data/history/sync
  -> MarketDataController
  -> MarketDataCenterService
  -> MarketDataService
  -> Python AKShare/Tencent Provider
  -> List<Bar>
  -> MarketDataPersistenceService.persistBars
  -> market_data_observation_batches
  -> daily_bar_observations
```

跨语言响应当前只保留：

```text
symbol
dataSource
tradeDate
open/high/low/close
volume
amount
turnoverRate
```

普通 Java 持久化入口固定传入 `sourceRevision=null`。Java 生成的
`LOCAL_DATASET_V1-<UUID>` 只表示本地捕获数据集，不能代表供应商 revision。

`000001` 两次受控公开 Provider 请求的行数、规范化内容、原始 body 和
`version=18` 均一致，但没有供应商文档或受控修订样例证明该值表示单条数据发布版本、
变化链、旧版本或可重放快照。因此 `version=18` 不得进入 `sourceRevision`、数据版本
对象或可靠 Hash 白名单。

## 4. 来源资格结论

| 来源类别 | 技术覆盖 | Provider revision 资格 | 当前允许角色 |
|---|---|---|---|
| AKShare-Tencent | 当前 QFQ 日线；未独立传递 factor/calendar/action | `PROVIDER_REVISION_UNVERIFIED` | 研究级 current projection 和交叉校验 |
| Tushare Pro | raw daily、独立 `adj_factor`、`trade_cal`，可补充公司行动接口 | 公开输出未证明 revision/snapshot；许可待书面确认 | `SYSTEM_KNOWLEDGE_PIT` 优先技术候选，尚未批准 |
| Wind | 官方资料显示数据库落地、API、历史行情和权益事件产品能力 | revision、旧版本、发布时间、许可、价格和 SLA 均待书面确认 | 企业级询价候选 |
| iFinD / Choice | 本阶段没有足够官方字段和权利证据 | `UNKNOWN` | 仅列入供应商书面确认清单 |

AKShare 官方文档还明确提示前复权历史值会随除权除息重新调整，不能把 Provider 当前
QFQ 序列直接冻结为历史时点事实：
[AKShare 股票数据文档](https://akshare.akfamily.xyz/data/stock/stock.html)。

Tushare 官方资料分别公开了 raw daily、独立复权因子和交易日历接口；`pro_bar` 的 QFQ
计算依赖当日因子和查询结束锚点，说明当前 QFQ 不是独立不可变原始事实：
[daily](https://tushare.pro/document/1?doc_id=27)、
[adj_factor](https://tushare.pro/document/2?doc_id=28)、
[trade_cal](https://tushare.pro/document/2?doc_id=26)、
[pro_bar](https://tushare.pro/document/2?doc_id=146)。
公开服务协议也没有为本项目明确授予长期本地历史库、历史回放和 Agent 使用权，因此
实施前必须取得书面确认：
[Tushare 数据服务协议](https://tushare.pro/document/1?doc_id=405)。

Wind 官方资料只足以把它列为企业级候选，不足以断言已满足本项目的 revision 和许可门槛：
[数据库传输服务](https://www.wind.com.cn/portal/zh/WDS/database.html)、
[Server API](https://www.wind.com.cn/mobile/WDS/sapi/en.html)。

## 5. 推荐方案

推荐采用分工明确的混合来源路线：

1. Tushare 作为 raw daily、`adj_factor`、`trade_cal` 的优先技术候选，但必须先通过
   书面许可和真实样例验收；
2. AKShare-Tencent 保留为 current projection 和非权威交叉校验来源，不接入
   `version=18`；
3. Wind 等企业来源并行取得 revision、历史版本、发布时间、许可和样例证据；
4. 每条事实只保留唯一 source lineage，不用跨来源多数表决覆盖原始事实；
5. 先建设仅覆盖真实首次捕获之后的 `SYSTEM_KNOWLEDGE_PIT`，以后有正式证据时通过
   新 Provider qualification 增加 `PROVIDER_PIT_VERIFIED`，不改写旧观察。

该建议不是 Provider 批准、采购批准或 Adapter 实施授权。

## 6. 冻结的 V2 设计

候选契约：

```text
PIT_MARKET_FACTS_V2
PIT_MARKET_FACTS_CANONICAL_V2
RAW_DAILY_BAR_OBSERVATION_V2
ADJUSTMENT_FACTOR_OBSERVATION_V1
TRADING_CALENDAR_OBSERVATION_V1
CORPORATE_ACTION_OBSERVATION_V1
QFQ_AS_OF_ENGINE_V1
```

### 6.1 四类独立事实

- 原始日线：未复权 OHLCV、amount、turnover、来源、Provider 元数据、真实首次观察、
  knowledge-time、内容 Hash、观察版本和本地批次/数据集 lineage；
- 复权因子：按 source instrument、source code、`factorType` 和
  `factorEffectiveTradeDate` 的精确自然键独立版本化，覆盖模式固定为
  `DAILY_EXACT`，内容变化追加，绝不嵌入 raw bar；
- 交易日历：按交易所和日期保存开放状态、session、known time 和观察版本，临时休市或
  修订必须追加；
- 公司行动：首版即覆盖分红、送转、配股、拆并股、除权除息、生效、公告和修订 lineage；
  因子变化没有可见 action 或合格 Provider 修订解释时，可靠上下文安全不可用。

`revisionQualification` 候选值冻结为：

```text
PROVIDER_VERIFIED
PROVIDER_UNVERIFIED
PROVIDER_UNAVAILABLE
SYSTEM_KNOWLEDGE_ONLY
```

### 6.2 两类可靠性

- `PROVIDER_PIT_VERIFIED`：供应商 revision、发布时间、旧版本、修订关系和许可均有
  正式证据；
- `SYSTEM_KNOWLEDGE_PIT`：允许 `providerRevision=null`，但必须以真实首次捕获为
  最早 `knownAt`，append-only 保存后续变化，仅支持首次捕获之后的决策。

`SYSTEM_KNOWLEDGE_PIT` 不是供应商 PIT，不证明供应商首次发布时间、历史完整性或首次
捕获以前的可得性。

### 6.3 兼容边界

- 2F V1、V9、`AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1` 完全不变；
- V2 后续实施必须使用独立 ruleVersion、contextProfile 和 Context Schema；
- 旧 contextHash、缓存键、任务、结果和观察不重算、不覆盖；
- 本设计不选择迁移版本、表名、索引、API 或生产部署方式。

## 7. QFQ as-of

对 cutoff 前可见的 raw bar 和 factor，锚点固定为窗口最后一个有效交易日：

```text
qfqPrice(P, t, cutoff)
  = rawPrice(P, t, cutoff)
    * factor(t, cutoff)
    / factor(anchorTradeDate, cutoff)
```

请求级日期统一命名为 `requestEffectiveTradeDate`，窗口最后有效交易日为
`anchorTradeDate`；二者不得与因子字段 `factorEffectiveTradeDate` 混用。对每根 raw
bar 日期 `t`，只允许选择同 source instrument、同 source code、同 `factorType`、
`factorEffectiveTradeDate==t` 且 `knownAt<=knowledgeCutoff` 的最新可见 append-only
观察版本；锚点因子同样必须精确满足
`factorEffectiveTradeDate==anchorTradeDate`。

V1 禁止 factor forward-fill、最近值替代、当前最新值补历史、QFQ 反推和跨 Provider
拼接。任一 bar 缺少精确 factor 时返回 `PIT_FACTOR_UNAVAILABLE`，不产生部分窗口或
重新归一化。只允许使用 cutoff 前可见的 raw、factor、calendar 和必要 action 版本。
Java 是未来 as-of 选择、QFQ、lineage 和 Hash 的唯一生产权威，Python 只验证黄金向量，
不重算第二套事实。未来稀疏 effective-from step 因子必须使用独立 Provider 覆盖契约和
独立 engine 版本，不能由 `QFQ_AS_OF_ENGINE_V1` 推断。

数值规则冻结为：

- 十进制字符串构造 `BigDecimal`，禁止二进制 float 作为权威输入；
- factor 必须大于 0；
- 除法 scale 16、`HALF_UP`；
- 最终 OHLC scale 4、`HALF_UP`；
- volume、amount、turnoverRate 不作价格复权；
- 输出 Hash 覆盖输入观察 ID/Hash、cutoff、锚点、舍入契约和结果。

后续因子或 action 变化只能形成新 knowledge-time 结果，不能改写旧重放。

## 8. 黄金场景

设计冻结 18 个场景：

1. D 日收盘后捕获 raw 和 factor；
2. D 日 cutoff 前未捕获时 D 日不可用；
3. D+1 捕获不能回填为 D 日已知；
4. D+10 公司行动使 Provider 当前 QFQ 重写历史；
5. D 日 as-of 仍重放旧 factor；
6. D+10 使用新 factor 形成新结果；
7. 同内容重复捕获幂等；
8. 内容变化追加；
9. revision 为空但真实前向捕获可形成 `SYSTEM_KNOWLEDGE_PIT`；
10. UUID、Hash 或 `version=18` 伪造 revision 被拒绝；
11. factor 缺失安全不足；
12. calendar 缺失安全不足；
13. cutoff 后观察排除；
14. Java 权威计算、Python 只验证；
15. factor 变化无 action/revision 解释时安全不足；
16. A→B→A 三个 knowledge-time 版本均可重放；
17. raw bar 日期 `t` 只有更早 factor 时不允许沿用，返回
    `PIT_FACTOR_UNAVAILABLE`，不产生部分窗口；
18. `t` 日精确 factor 的 `knownAt` 晚于 cutoff 时排除，返回
    `PIT_FACTOR_UNAVAILABLE`，不得用更旧 factor 替代。

固定夹具必须包含输入 JSON、canonical 文本、预期 Hash、QFQ 输出和 lineage，预期值不得由
被测实现运行时生成。

## 9. 后续实施入口

后续路线不是自动授权：

1. **3A-R3B 来源、许可与样例批准门**：取得 Tushare 和至少一家企业 Provider 的书面
   许可、字段语义、版本样例和历史回放证据；
2. **PIT_MARKET_FACTS_V2 实现大阶段**：在批准来源后一次完成新的前向迁移、Adapter、
   四类 append-only 事实、canonical、as-of Repository、QFQ engine 和真实 PostgreSQL；
3. **独立 2F V2 大阶段**：使用新 ruleVersion/profile 接入 V2 事实，保留 2F V1；
4. **恢复 3A 观察**：上述能力验收并合入后，由用户另行授权 Day 002。

Day 001 只是 1 个观察日和 3 个 item；完整 3A 仍未达到 20 个有效观察日、200 个 item、
主要 reasonCode 人工复核和正式观察报告门槛。3B 未开始。

## 10. 本阶段检查

- 生产代码测试：不适用，本阶段没有生产代码变化；
- Markdown 相对链接：通过；
- Markdown 表格：通过；
- 文件结尾换行：通过；
- `git diff --check`：通过；
- 变更范围：仅两份 3A-R3A 文档及 `CURRENT_STATE.md`、`DECISIONS.md`、
  `ROADMAP.md`，没有 Java、Python、Vue、SQL、迁移或配置变化。

以上是 Codex 本地文档检查，不是 GitHub Actions CI，也不代表 ChatGPT 验收或用户 merge
批准。
