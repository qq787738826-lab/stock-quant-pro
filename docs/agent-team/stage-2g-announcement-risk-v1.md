# 阶段 2G：AKShare/CNINFO 公告事实基础与 ANNOUNCEMENT_RISK 确定性规则 V1

## 1. 状态

状态：**任务分支实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交验收，未合入集成分支。**

- 冻结集成基线：`a898e21df38594a6aca1429a3dfd5e28c2cf7f72`
- 任务分支：`codex/1.4.0-stage-2g-announcement-risk-v1`
- 团队规则版本：`1.4.0-stage-2g-announcement-risk-v1`
- context profile：`AGENT_CONTEXT_2G_V1`
- securityEvents Schema：`SECURITY_EVENTS_CONTEXT_V1`
- 来源 / Provider：`AKSHARE_CNINFO_RESEARCH_V1` / `AKSHARE_CNINFO_PROVIDER_V1`
- canonical 契约：`ANNOUNCEMENT_CANONICAL_V1`
- AKShare 版本：`1.18.64`
- 完整任务书：[2g-akshare-announcement-risk-v1.md](tasks/2g-akshare-announcement-risk-v1.md)

该状态不代表 ChatGPT 验收 PASS、用户 merge 批准、已合入或来源获得 FORMAL/PIT
资格。当前项目事实以 [CURRENT_STATE.md](CURRENT_STATE.md) 为唯一权威来源。

## 2. 真实来源门禁与资格

在修改生产代码前，项目隔离虚拟环境使用固定 `akshare==1.18.64` 调用公开函数
`stock_zh_a_disclosure_report_cninfo`，参数为 `symbol=000001`、`market=沪深京`、
空 keyword/category 和受控历史日期范围。真实响应包含 `代码`、`简称`、`公告标题`、
`公告时间`、`公告链接`，返回 38 行；未保存或提交真实响应、Cookie 或敏感网络信息。

来源资格固定为：

- `assuranceLevel=RESEARCH`
- `formalEligible=false`
- `pitVerified=false`
- `revisionRelationshipGuaranteed=false`
- `reportedPublishTimePrecision=DATE_ONLY`

因此本阶段只支持个人研究、公告风险提示、规则开发和从真实观察时点开始的可审计积累。
它不声明正式授权、历史绝对完整、精确首次公开时间、完整修订/撤回关系或自动交易资格，
也不解除 2D 正式证券状态来源、许可和 PIT 门禁。

## 3. Provider Bridge 与摄取边界

FastAPI 内部 Provider Bridge 与 `/agents/team/analyze` 严格分离：

- 只调用 AKShare 公开函数，不连接数据库，不生成业务结论，不决定 `knownAt`；
- 单个逻辑请求最长 366 天，按最多 30 个自然日切块；
- 单并发，相邻调用至少间隔 2 秒，单次超时 30 秒，临时网络异常最多重试 2 次；
- 403、429、验证码、访问限制或字段结构变化立即失败，不使用代理或隐藏接口；
- 响应包含 Provider/AKShare 版本、请求范围、完整性、块计数、稳定排序记录和安全错误。

Java 的 `AnnouncementIngestionService` 在数据库事务外调用 Provider，完整接收并校验响应后
立即冻结 `observedAt`，生成 Hash 和观察版本，再在单个事务中保存批次与新增事实。
研究摄取配置默认 `stockquant.announcement.akshare.enabled=false`，只提供显式手动入口和
`scripts/sync-announcements-local.ps1`；没有后台定时、全市场自动抓取或 PDF 下载。
Agent 任务创建和分析过程不会调用 Provider。

## 4. 来源身份与 canonical 契约

来源公告 ID 优先从 CNINFO URL 提取明确标识并生成 `CNINFO:<id>`。没有明确 ID 时，
只允许规范化 HTTP/HTTPS URL 后生成
`CNINFO_URL_SHA256:<64位小写SHA-256>`，并标记
`sourceIdentityStrength=URL_DERIVED`。无有效 URL 的记录拒绝；标题、公告日期、
symbol、数组序号和抓取顺序均不得单独生成来源身份。

`ANNOUNCEMENT_CANONICAL_V1` 冻结来源、身份、证券、名称、标题、报告日期、日期精度、
规范化 URL 和 assurance flags 的字段白名单，使用 UTF-8 与 SHA-256 小写十六进制。
Java 是生产 Hash 和 `observationVersion` 权威方。仓库黄金向量固定输入 JSON、
canonical 文本和预期 Hash
`6f3d2a871f44cccf8ff635214e2f156f11460dfc89bf38aba5b451f5d3bc63d4`，
Java 与 Python 独立验证，不由被测实现运行时生成预期值。

## 5. V10 append-only 公告事实

本阶段只新增 `V10__announcement_observation_foundation.sql`，未修改 V1 至 V9。

### 5.1 `announcement_capture_batches`

保存不可变批次 ID/version、来源与 Provider、symbol、请求范围、`observedAt`、完整性、
块计数、真实 `recordCount/appendedCount`、Provider 元数据和记录时间。
`complete=true, recordCount=0` 是有效覆盖证据；`complete=false` 只用于部分摄取审计，
不得证明范围内没有风险公告。

### 5.2 `announcement_observations`

保存 batch、来源公告身份、symbol/name/title、日期级报告时间、原始和规范化 URL、
URL Hash、`firstObservedAt/knownAt/recordedAt`、canonical Hash、观察版本、来源资格及
原始 JSON 对象。两个表均由数据库触发器拒绝 `UPDATE`、`DELETE`、`TRUNCATE`。

数据库约束强制六位 symbol、合法日期范围、HTTP/HTTPS URL、64 位小写 Hash、
`firstObservedAt<=knownAt<=recordedAt`、JSON 对象及研究来源 assurance flags。
同一逻辑公告与最新版本内容相同时不追加；标题、日期、URL 或其他 canonical 内容变化时
追加新版本；A→B→A 保留后一次 A。并发相同 capture 通过数据库锁和唯一门禁保持幂等。

## 6. 时间与 as-of 语义

AKShare 只提供日期级公告时间，因此不把报告日期伪造成 00:00 或 23:59 发布时间。
Java 完整接收并验证版本时冻结 `firstObservedAt`，并固定
`knownAt=firstObservedAt<=recordedAt`。历史公告今天才首次观察时只能从今天的
knowledge-time 起用于分析，不能回填为历史已知。

2G 固定回看 180 个自然日：

- 未来请求安全不可用；
- 历史请求的 knowledge cutoff 为请求日上海时区日终；
- 当前请求的 knowledge cutoff 为 `queriedAt`；
- 事件必须同时满足 `reportedPublishDate<=requestTradeDate` 与
  `knownAt<=knowledgeCutoff`；
- 同一逻辑公告只选 cutoff 时点最新可见观察版本。

数据库或 SQL 异常直接使任务失败，不伪装成业务不可用。

## 7. securityEvents

精确 2G profile 同时启用 2F 可靠 backtestContext、2H 可靠 portfolioContext 和
2G securityEvents；旧 2B、2D-1、2E-1、2F、2H profile/contextHash 保持兼容。

可用 context 必须由同 symbol、正确来源/Provider、完整覆盖 180 日窗口、
`complete=true`、`observedAt<=knowledgeCutoff` 且年龄不超过 24 小时的批次证明。
完整 0 公告批次产生 `available=true, events=[]`；无批次、过期、部分或范围不完整
分别安全返回稳定 reasonCode。事件稳定按报告日期降序、knownAt 降序、来源公告 ID
升序、observationVersion 升序。

context 固定包含来源资格、覆盖批次、观察年龄、事件版本及以下限制：研究来源、
日期级发布时间、无修订关系保证、无历史完整性保证、无 FORMAL/PIT 资格、无 PDF
语义解析、仅用于研究。

## 8. ANNOUNCEMENT_RISK V1

Python 只解释 Java 冻结的 securityEvents，不调用 AKShare、数据库、PDF、LLM，
也不重建公告事实。Java 与 Python 使用相同的 Unicode NFKC、首尾空白清理、
连续空白折叠、中文标点规范化和拉丁字母大写规则。

冻结规则覆盖：

1. 退市、风险警示和监管执法；
2. 财务、债务、审计、诉讼及经营中断；
3. 股东减持、质押、冻结、担保与资金占用；
4. 财务更正组合规则及一般更正、补充、澄清；
5. 撤销风险警示和解除质押/冻结等明确排除。

一条公告可以命中多个标签，但只按最高 `CRITICAL/HIGH/WARN/INFO` severity 扣分一次。
风险事件按 severity、报告日期降序、来源公告 ID、observationVersion 稳定排序。

- 有效上下文从 100 分开始；
- CRITICAL/HIGH/WARN/INFO 基础扣分为 40/25/10/0；
- 0–7、8–30、31–90、91–180 天系数为 1/0.75/0.50/0.25；
- 每条扣分使用 `HALF_UP` 到整数，最终 score 为 `max(0, 100-总扣分)`；
- research source confidence 固定为 40，不因风险命中提高。

有效上下文固定生成来源覆盖、监管/退市、财务/债务/诉讼、股东/担保/经营、
时效/修订/研究边界五类 finding。无命中时只能说明当前完整抓取范围内未匹配冻结规则。
DATA_QUALITY 阻断、context 不可用或输入非法时安全降级，不生成正常 finding/evidence/
评分。ANNOUNCEMENT_RISK 永不产生正式 veto、投资建议、收益预测或交易执行指令。

## 9. Evidence、Java复核与总控

Java 生成一个投影完整 securityEvents 的 `QUERY_RESULT` coverage evidence；每个风险公告
生成绑定 `observationVersion`、canonical Hash 和稳定来源引用的 `SECURITY_EVENT`
evidence。Finding 只引用存在的 coverage/event evidence。

Java 在持久化前独立复算并校验字段白名单、覆盖、时间、as-of、Hash、事件排序、
标题规范化、排除、标签、severity、score、固定 confidence、五 finding、evidence、
ANNOUNCEMENT_RISK 无 veto、POSITION_RISK 正式 veto 和总控优先级。非法响应原子失败，
不留下部分 Agent evidence/veto/decision；已合法持久化的公告事实不受 Agent 响应失败影响。

2G 保持六个专业 run，总控不是第七个 run。POSITION_RISK 正式 veto 最高优先，
其次为 DATA_QUALITY 阻断；两者均不存在时六个专业 run 已执行，但 2I 综合决策未实现，
因此总控仍为 `INSUFFICIENT_DATA/score=0/confidence=0`。

## 10. Codex本地测试证据

以下均为 Codex 本地执行证据，不是 GitHub Actions CI。

| 测试组 | 运行/失败/错误/跳过 | 说明 |
|---|---:|---|
| 真实 AKShare 前置安全门 | `1/0/0/0` | `akshare==1.18.64`，`000001` 受控历史范围，返回 38 行 |
| `quant-core` 全量 | `4/0/0/0` | 核心回归 |
| 2G Java 定向合计 | `39/0/0/0` | Provider/摄取/canonical/context/规则/校验/profile、跨语言、真实 PostgreSQL 与 Live Gate；`Skipped=0` |
| Python `compileall` | 通过 | `quant-ai/app` 与完整测试模块 |
| Python 完整 unittest | `111/0/0/0` | Provider、公告规则、所有旧规则和编排回归 |
| 真实 Java/Python HTTP | `4/0/0/0` | 完整 2G 请求、Hash、五 finding、evidence、六 run；`Skipped=0` |
| V1 至 V10 公告事实 PostgreSQL | `3/0/0/0` | append-only、0/部分批次、幂等、A→B→A、as-of、并发和 public 基线；`Skipped=0` |
| 真实 PostgreSQL/Python/任务持久化 | `1/0/0/0` | 0/多风险、veto/DQ 优先、JSONB、原子失败及六张 2H 业务表只读；`Skipped=0` |
| 真实 AKShare Live Gate | `1/0/0/0` | 真实 38 行、Java 摄取、ID/Hash/append-only、随机 Schema 清理；`Skipped=0` |
| 安全非数据库 `quant-server` 全量 | `331/0/0/55` | 55 项为未提供外部 Python/PostgreSQL/AKShare 环境时的门禁跳过 |
| 2D/2E/2F/2H 真实兼容 | `35/0/0/0` | 随机隔离 Schema，V1 至 V10，`Skipped=0` |
| `git diff --check` | 通过 | 最终增量无空白错误 |

真实 PostgreSQL 测试使用随机隔离 Schema，从 V1 迁移至 V10，结束后精确删除临时
Schema；public 数据与结构指纹前后不变。任务闭环还验证
`portfolio_accounts`、`positions`、`manual_orders`、`simulated_trades`、
`account_equity_snapshots`、`risk_events` 前后逐行一致。未执行 Flyway repair/clean，
未修改或重建 public。

## 11. 安全结论

- 只新增 V10，未修改 V1 至 V9；
- 未把 AKShare/CNINFO 描述为正式授权、FORMAL、PIT 或历史完整来源；
- 未提交真实公告响应、Cookie、缓存、代理或敏感网络信息；
- 未增加隐藏接口、验证码绕过、PDF 批量下载、定时或全市场抓取；
- Python Agent 不访问数据库或网络，Java 仍是事实、Hash、时间和持久化权威；
- 未修改真实账户或模拟交易业务事实；
- 未产生正式公告 veto、投资建议、收益承诺或自动交易能力；
- 任务分支未合入，未开始 2I 或其他阶段。
