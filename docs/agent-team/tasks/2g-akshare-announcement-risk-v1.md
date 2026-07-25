# 2G AKShare/CNINFO 公告事实基础与 ANNOUNCEMENT_RISK 确定性规则 V1

## 1. 状态与目标

状态：**任务分支实现与 Codex 本地验证完成；待 ChatGPT 基于实际 Git 提交验收；未合入集成分支。**

- 冻结集成基线：`a898e21df38594a6aca1429a3dfd5e28c2cf7f72`
- 任务分支：`codex/1.4.0-stage-2g-announcement-risk-v1`
- 团队规则版本：`1.4.0-stage-2g-announcement-risk-v1`
- context profile：`AGENT_CONTEXT_2G_V1`
- securityEvents Schema：`SECURITY_EVENTS_CONTEXT_V1`
- 来源：`AKSHARE_CNINFO_RESEARCH_V1`
- Provider 契约：`AKSHARE_CNINFO_PROVIDER_V1`
- canonical 契约：`ANNOUNCEMENT_CANONICAL_V1`

本大阶段连续完成研究级公告 Provider、Java 权威摄取、append-only 观察事实、
as-of `securityEvents`、确定性 ANNOUNCEMENT_RISK、Java/Python 双重校验、
真实 PostgreSQL/HTTP/AKShare 验收和权威文档同步。内部工作包不单独暂停、提交或验收。

## 2. 仓库事实审计

1. 外层 `contextSnapshot` 已包含结构化 `securityEvents` 槽位，无需改变外层 Schema。
2. V5 已包含六个固定专业 run、evidence、POSITION_RISK 正式 veto 和总控 decision；
   V5 的旧 `security_events` 不具备观察批次、knowledge-time、来源资格和版本链，
   不能作为本阶段权威公告事实。
3. V6 至 V8 的时态、摄取和证券状态事件基础不等于公告观察模型；V9 只服务可靠回测日线。
4. 2F 和 2H 已通过精确 ruleVersion 选择版本化 context profile，旧 contextHash 可保持不变。
5. Python Agent Rule Engine 当前不访问数据库或网络；现有 FastAPI 可增加一个与
   `/agents/team/analyze` 严格分离的内部 Provider Bridge。
6. 冻结基线仍缺少可审计公告观察版本、可靠覆盖批次和 ANNOUNCEMENT_RISK 规则。

## 3. 真实来源门禁

在修改生产代码前，使用项目隔离虚拟环境执行用户批准的 AKShare 公共函数：

```python
ak.stock_zh_a_disclosure_report_cninfo(
    symbol="000001",
    market="沪深京",
    keyword="",
    category="",
    start_date="20230619",
    end_date="20231220",
)
```

门禁只记录 AKShare 精确版本、字段结构、行数和成功/失败，不保存或提交真实响应。
403、429、验证码或访问控制不得绕过；临时网络错误最多重试两次。门禁未通过时不得
进入生产实现。

## 4. 来源资格与职责边界

`AKSHARE_CNINFO_RESEARCH_V1` 固定为 `RESEARCH`：

- `formalEligible=false`
- `pitVerified=false`
- `revisionRelationshipGuaranteed=false`
- `reportedPublishTimePrecision=DATE_ONLY`

它只支持个人研究、风险提示、规则开发和从真实观察时点起的可审计积累，不声明正式授权、
历史绝对完整、精确首次发布时间、完整修订关系或自动交易资格。

Java 是 capture、`observedAt/knownAt`、验证、canonical Hash、append-only 持久化、
as-of 读取、contextSnapshot 和 Agent 结果持久化的唯一权威。Python Provider Bridge
只调用 AKShare 并返回规范化记录；Python Agent Rule Engine 只解释 Java 冻结的
`securityEvents`。Agent 任务创建和分析不得触发 Provider、数据库外部调用或 PDF 下载。

## 5. Provider Bridge

- 输入：6 位 `symbol`、固定市场 `沪深京`、起止日期、固定空 keyword/category。
- 单次逻辑范围不超过 366 天；内部按不超过 30 个自然日切块。
- 单并发，相邻调用至少 2 秒，单次超时 30 秒，临时失败最多重试两次。
- 403、429、验证码和结构漂移立即失败；不得代理轮换或切换隐藏接口。
- 响应记录 provider/AKShare 版本、请求范围、完整性、块计数、记录和安全错误。
- 公告记录按报告日期、派生的稳定来源公告 ID、标题升序；空完整结果是有效响应。
- 有效 URL 必须是 HTTP/HTTPS。优先提取 CNINFO 明确公告 ID；否则规范化 URL 后
  使用 `CNINFO_URL_SHA256:<hash>`，不得由标题、日期、symbol 或抓取顺序生成身份。

## 6. V10 append-only 公告事实

只新增 `V10__announcement_observation_foundation.sql`，不修改 V1 至 V9。

### 6.1 批次

`announcement_capture_batches` 保存不可变批次 ID/version、来源/Provider、symbol、
请求范围、真实观察时间、完整性、块计数、记录/追加计数、Provider 元数据和记录时间。
`complete=true, record_count=0` 是有效覆盖；`complete=false` 只能作为部分摄取审计，
不能证明范围内无风险公告。

### 6.2 观察版本

`announcement_observations` 保存来源公告身份、symbol/name/title、日期级报告时间、
URL/规范化 URL/hash、首次观察/已知/记录时间、canonical Hash、观察版本、来源资格和
原始对象。两表通过触发器拒绝 UPDATE、DELETE 和 TRUNCATE。

同一来源公告与最新可见版本内容相同时不追加；内容变化追加新版本；A→B→A 保留后一次 A。
数据库硬门禁验证 symbol、日期、URL、Hash、时间顺序、JSON 对象和研究来源资格。

## 7. 时间、canonical 与 as-of

- 公告时间只保存 `reportedPublishDate`，不伪造 00:00 或 23:59。
- Java 完整收到并验证 Provider 响应后冻结 `firstObservedAt`；`knownAt=firstObservedAt`。
- 历史公告今天首次观察时只能从今天起可见，不能回填历史 knowledge-time。
- `ANNOUNCEMENT_CANONICAL_V1` 使用稳定字段白名单、UTF-8 和 SHA-256 小写十六进制。
- Java 生成 canonical Hash 和 observationVersion；固定输入、canonical 文本和预期
  Hash 作为仓库黄金向量，由 Java/Python 独立验证。
- as-of 只选择 `knownAt<=knowledgeCutoff` 的每个逻辑公告最新版本。

## 8. securityEvents

精确 2G profile 同时启用 2F backtestContext、2H portfolioContext 和 2G securityEvents。
旧 2B、2D-1、2E-1、2F、2H profile/contextHash 保持兼容。

- 未来请求不可用；历史请求 cutoff 为上海时区日终；当前请求 cutoff 为 `queriedAt`。
- lookback 固定 180 个自然日。
- 覆盖必须由同 symbol、正确来源/Provider、完整覆盖窗口、`complete=true`、
  `observedAt<=knowledgeCutoff` 且年龄不超过 24 小时的批次证明。
- 完整空批次生成 `available=true, events=[]`；无批次、过期、部分或范围不足安全不可用。
- 事件按报告日期降序、knownAt 降序、来源公告 ID 升序、observationVersion 升序。
- limitations 固定披露研究来源、日期级时间、无修订关系/历史完整性/PIT/正式资格和
  无 PDF 语义解析。

## 9. ANNOUNCEMENT_RISK V1

Python 只对 Java 冻结标题和元数据运行 NFKC、空白/标点/拉丁字母规范化后的确定性匹配。
冻结规则覆盖退市与风险警示、监管执法、财务债务、诉讼经营、股东减持质押冻结、
担保占用和更正澄清；撤销风险警示和解除质押/冻结按契约排除。

一条公告可命中多个标签，但只以最高 severity 扣分一次。风险事件按
`CRITICAL/HIGH/WARN/INFO`、报告日期降序、来源公告 ID、observationVersion 排序。

- 有效输入从 100 分开始；CRITICAL/HIGH/WARN 分别基础扣 40/25/10。
- 0–7、8–30、31–90、91–180 天系数分别为 1、0.75、0.50、0.25，HALF_UP 到整数。
- confidence 固定 40。
- 固定生成来源覆盖、监管退市、财务债务诉讼、股东担保经营、时效与研究边界五类 finding。
- coverage evidence 投影完整 securityEvents；每个命中公告生成绑定 observationVersion
  和 canonical Hash 的 event evidence。
- DATA_QUALITY 阻断、上下文不可用或非法输入安全降级，不生成正常评分；永不生成正式 veto。

## 10. 总控、校验和原子性

2G 保持六 run。POSITION_RISK 正式 veto 优先，其次 DATA_QUALITY 阻断；两者均不存在时，
六个专业 run 已执行但 2I 未实现，总控仍为 `INSUFFICIENT_DATA`、score/confidence 为 0。

Java 在持久化前独立复核上下文字段、覆盖、时间、as-of、Hash、标题规范化、排除与标签、
severity、score、confidence、finding/evidence、ANNOUNCEMENT_RISK 无 veto、
POSITION_RISK veto 和总控优先级。非法响应不留下部分 Agent evidence/veto/decision；
已经合法持久化的公告事实不因 Agent 响应失败而回滚或删除。

## 11. 验收

必须覆盖 Provider 字段/切块/排序/重试/访问控制、V10 append-only/幂等/A→B→A/as-of、
完整空批次和部分批次、securityEvents 覆盖与时间边界、全部关键词和排除、四档时效、
score/confidence、DQ 降级、五 finding、evidence、六 run、总控优先级、原子失败和旧
profile/hash 兼容。

真实验收包括：

- 随机隔离 Schema 的 V1 至 V10 PostgreSQL，`Skipped=0`，精确清理且 public 不变；
- 真实 Java/Python HTTP 闭环；
- 真实 AKShare `000001` Live Gate，至少一条并完成 Java 摄取；
- 2H 六张模拟账户业务表执行前后不变；
- quant-core、quant-server、Python compileall/unittest 和 2D/2E/2F/2H 回归；
- `git diff --check`、Markdown 链接/表格/结尾换行。

所有结果必须明确为 Codex 本地证据，不得冒充 GitHub Actions CI。

实际 Codex 本地执行证据见
[阶段 2G 实现与本地验证记录](../stage-2g-announcement-risk-v1.md)。真实 AKShare
安全门和 Live Gate 使用固定 `akshare==1.18.64`、`000001` 与受控历史范围成功返回
38 行；2G Java 定向、真实 V1 至 V10 PostgreSQL、真实 Java/Python、任务持久化、
兼容回归和 Python 全量均已按任务书完成。该证据不表示 ChatGPT 已验收或用户已批准 merge。

## 12. 完成边界

任务分支完成后只允许写为“实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交
验收，未合入”。单次 commit 和普通 push 后停止，不自行 merge，不开始 2I。
当前项目事实始终以 [CURRENT_STATE.md](../CURRENT_STATE.md) 为唯一权威来源。
