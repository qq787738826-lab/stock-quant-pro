# 免费 Provider 书面许可与数据语义问题清单

## 1. 使用方式

本清单供用户后续自行向 BaoStock、AKShare 实际上游、CNINFO、交易所或授权数据机构询问。
Codex 不发送邮件、不提交表单、不注册账号，也不代表用户接受条款。

有效答复至少应：

- 来自 Provider、数据权利人或正式授权机构；
- 明确指向具体数据类别和用途；
- 能保存为书面证据；
- 区分客户端代码许可证与底层数据许可；
- 区分个人非公开研究、内部使用、展示和商业化；
- 说明生效时间、版本、期限、撤销与变更通知。

## 2. 通用 17 问

1. 是否允许个人研究项目调用该具体接口和数据类别？
2. 是否允许把原始响应和规范化事实长期保存在本地 PostgreSQL？
3. 是否允许保存每日/每次捕获的历史快照和 append-only 版本？
4. 是否允许按历史 knowledge cutoff 重放数据？
5. 是否允许用于历史回测和策略验证？
6. 是否允许内部 AI Agent 读取事实并生成研究分析？
7. 是否允许计算 QFQ、指标、风险标签和其他衍生数据？
8. 是否允许在仅本机可见的 UI/报告中展示原始字段和衍生结果？
9. 是否允许非公开个人使用，是否需要署名或显示来源？
10. 若未来商业化，哪些功能需要另行授权或付费？
11. 是否允许缓存、备份、灾备恢复和跨设备迁移？
12. 是否允许保存脱敏、最小化的固定响应夹具用于自动化测试？
13. 正式调用频率、并发、日配额、超时、重试和封禁规则是什么？
14. 是否提供带单位、精度、空值、明确 0、复权和枚举定义的正式数据字典？
15. 是否提供 revisionId、snapshotId、providerPublishedAt 或 providerUpdatedAt？
16. 数据修订后能否查询旧版本，是否提供版本间 predecessor/replacement 关系？
17. 上述用途是否需要签署许可、购买升级或在合同/技术附件中确认？

## 3. BaoStock 专项

请明确回答以下对象，而不是只回答 Python 包：

```text
query_history_k_data_plus raw daily
query_history_k_data_plus QFQ/HFQ
query_adjust_factor
query_daily_adjust_factor
query_trade_dates
query_dividend_data
```

专项问题：

1. PyPI 的 BSD License 是否只覆盖客户端代码？底层数据的权利人和许可文本是什么？
2. `volume`、`amount`、`turn`、OHLC 和 factor 的单位、精度、空字符串和 0 语义是什么？
3. `query_adjust_factor` 是只在除权除息日返回，还是可以提供每个交易日的精确 factor？
4. `query_daily_adjust_factor(date)` 返回的 factor 是否与逐证券查询同一数据集、同一版本？
5. factor 的 `foreAdjustFactor/backAdjustFactor/adjustFactor` 各自公式和锚点是什么？
6. 通用 `query_trade_dates` 是否同时适用于 SSE/SZSE？是否存在交易所级身份和差异？
7. 分红数据是否有稳定事件 ID、公告时间、实施时间、修订时间和撤回/替换关系？
8. 历史 K 线、因子、日历和公司行动是否会静默修正？如何通知？
9. 免费服务是否有稳定性承诺、维护公告、版本公告或正式 SLA？
10. 是否允许 Stock Quant Pro 以 `SYSTEM_KNOWLEDGE_PIT` 方式从首次真实捕获起保存前向版本？

未取得书面回复前：

```text
role=PENDING_WRITTEN_PERMISSION
DAILY_EXACT=UNVERIFIED
PROVIDER_PIT_VERIFIED=false
```

## 4. AKShare 与实际上游专项

AKShare 只是客户端聚合库。询问必须分别面向 Tencent QQ Finance、Sina Finance、
Eastmoney 和 CNINFO，不接受“AKShare 开源所以全部可用”的答复。

### 4.1 Tencent QQ Finance

1. `stock_zh_a_hist_tx` 底层日线接口的正式权利人和用途许可是什么？
2. payload 的 `amount` 字段究竟代表成交量、成交额还是其他量？
3. `version=18` 的正式语义是什么；是否是 schema、dataset、snapshot 或 revision？
4. 是否提供未复权日线、独立 factor、旧版本和修订时间？
5. 是否允许长期落库、回测、内部 Agent、UI 展示和商业化？

没有书面语义前，`version=18` 永远不得作为 `sourceRevision`。

### 4.2 Sina Finance

1. `stock_zh_a_daily` 以及 qfq/hfq factor 上游接口的许可和数据字典是什么？
2. factor 是否逐交易日覆盖，能否证明 `DAILY_EXACT`？
3. factor 与 raw daily 是否属于同一 Provider 数据集和稳定证券身份？
4. 是否提供 revision/snapshot/published/update time 和旧版本？
5. 是否允许本地持久化、回测、Agent 和商业使用？

### 4.3 Eastmoney

1. `stock_zh_a_hist` 底层接口的许可和单位是什么？
2. 是否有同源独立 factor、交易日历和公司行动接口？
3. 是否提供 revision/snapshot/发布时间和旧版本？
4. 是否允许本地持久化、回放、回测、Agent 和商业使用？

## 5. CNINFO 专项

1. 法定公告页面的公开浏览权与批量 API/数据商城许可如何区分？
2. 是否允许保存公告元数据、稳定公告 ID、URL、标题和日期？
3. 是否允许下载和长期保存 PDF；若允许，调用与总量限制是什么？
4. 是否允许将公告解析为结构化公司行动并用于历史回测？
5. 是否提供精确首次发布时间、修订时间、撤回、替换和 predecessor 关系？
6. 是否允许保存旧公告版本和生成脱敏固定夹具？
7. 是否允许内部 Agent 标题规则和本地 UI 展示？
8. 个人非公开研究与未来商业化分别需要什么授权？

在答复前，CNINFO 只承担 `OFFICIAL_EVIDENCE_ONLY`；现有 AKShare/CNINFO 2G 来源继续固定
为研究级，不升级 FORMAL/PIT。

## 6. SSE、SZSE 与授权数据机构专项

1. 官方交易日历是否有机器可读、版本化、可本地保存的正式服务？
2. SSE 与 SZSE 临时休市、半日市或规则修订如何发布和版本化？
3. EOD raw daily、复权因子和公司行动分别由谁授权？
4. 公开网页、投资者数据页面和正式授权数据服务的许可差异是什么？
5. 是否允许个人非公开本地保存、历史回放、回测和内部 Agent 使用？
6. 是否允许保存修订前版本，是否提供 revision/snapshot/published/update time？
7. 是否提供稳定 instrument/calendar/action identity 与正式数据字典？
8. 深圳证券信息有限公司对深市行情的许可范围、频率、费用和商业边界是什么？
9. 是否提供测试账号或最小样例响应，且不要求在 F0 注册或付费？
10. 若仅允许人工网页查阅，是否可以把页面作为校验证据但不做批量 Adapter？

## 7. 回复验收模板

每份回复至少登记：

```text
respondingOrganization
respondingPersonOrOfficialChannel
responseDate
dataProduct
coveredFunctions
coveredFactClasses
allowedUses
forbiddenUses
retentionAndBackup
replayAndBacktest
agentAndDerivedUse
commercialBoundary
rateLimitAndSLA
revisionAndOldVersions
effectiveDate
expiryOrRevocation
evidenceFileSha256
reviewStatus
```

任何口头回复、销售概述或未指向具体数据产品的笼统答复只能记为 `UNVERIFIED`，不能启动
F1。
