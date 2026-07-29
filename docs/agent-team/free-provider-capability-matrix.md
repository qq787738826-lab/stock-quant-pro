# 免费 Provider 能力与资格矩阵

## 1. 读法

本矩阵是 3A-R3B-F0 的逐来源、逐事实审计结果，不是 Adapter 批准清单。所有
`sourceCode` 和 source identity 均只是候选命名；F1 重新冻结之前不得写入 V13。

状态只使用：

```text
VERIFIED / PARTIAL / UNVERIFIED / UNAVAILABLE
NOT_EXPOSED / NOT_PROBED / CONFLICTING_EVIDENCE
```

角色只使用：

```text
FREE_PROVIDER_F1_CANDIDATE
RESEARCH_AUXILIARY_ONLY
OFFICIAL_EVIDENCE_ONLY
REJECTED
PENDING_WRITTEN_PERMISSION
```

为避免一个 29 列的不可读表格，下面五张表使用同一 `recordId` 联结；合起来完整覆盖
providerCandidate、client/library、upstreamProvider、sourceCode、sourceIdentity、
factClass、publicFunction/page、raw/adjusted、coverage、unit、precision、null/zero、
revision/snapshot/publishedAt/updatedAt/历史版本、五类用途许可、rate limit、stability、
probe、evidence、status 和 F1 role。

## 2. 身份、接口与覆盖

| recordId | providerCandidate | client/library | upstreamProvider | sourceCode 候选 | sourceIdentity 候选 | factClass | publicFunction/页面 | raw/adjusted | coverage | probe |
|---|---|---|---|---|---|---|---|---|---|---|
| BAO-RAW | BaoStock | baostock 0.9.3 | BaoStock data service | `BAOSTOCK_RESEARCH_CANDIDATE_V1` | 证券身份 `exchange.code` 可观察，稳定承诺未取得 | RAW_DAILY_BAR | `query_history_k_data_plus(adjustflag=3)` | raw | A 股、固定两证券短区间 | 两证券各 6 行 SUCCESS |
| BAO-FAC | BaoStock | baostock 0.9.3 | BaoStock data service | 同上 | `code + factor type` 候选；版本身份未取得 | ADJUSTMENT_FACTOR | `query_adjust_factor`；`query_daily_adjust_factor` | independent factor surface | 按证券区间；单日函数为全市场 | 两证券区间均 EMPTY；全市场函数未执行 |
| BAO-CAL | BaoStock | baostock 0.9.3 | BaoStock data service | 同上 | 只有通用 `calendar_date`，无 exchange identity | TRADING_CALENDAR | `query_trade_dates` | n/a | 固定自然日范围 | 8 行 SUCCESS |
| BAO-ACT | BaoStock | baostock 0.9.3 | BaoStock data service | 同上 | 证券可识别；稳定 action ID 未暴露 | CORPORATE_ACTION | `query_dividend_data(yearType=operate)` | n/a | 分红送转年度数据 | 1 行 SUCCESS |
| TX-RAW | AKShare 1.18.64 / Tencent | AKShare | Tencent QQ Finance | `TENCENT_QQ_FINANCE_RESEARCH_CANDIDATE_V1` | 腾讯证券代码候选 | RAW_DAILY_BAR | `stock_zh_a_hist_tx` | raw/QFQ/HFQ；生产当前用 QFQ | A 股按年分段 | 复用既有受控两次响应 |
| TX-FAC | AKShare 1.18.64 / Tencent | AKShare | Tencent QQ Finance | 同上 | 未取得 | ADJUSTMENT_FACTOR | 当前函数未返回独立 factor | n/a | n/a | NOT_EXPOSED |
| TX-CAL | AKShare 1.18.64 / Tencent | AKShare | Tencent QQ Finance | 同上 | 未取得 | TRADING_CALENDAR | 当前函数未返回 calendar | n/a | n/a | NOT_EXPOSED |
| TX-ACT | AKShare 1.18.64 / Tencent | AKShare | Tencent QQ Finance | 同上 | 未取得 | CORPORATE_ACTION | 当前函数未返回 action | n/a | n/a | NOT_EXPOSED |
| SINA-RAW | AKShare 1.18.64 / Sina | AKShare | Sina Finance | `SINA_FINANCE_RESEARCH_CANDIDATE_V1` | 新浪证券代码候选 | RAW_DAILY_BAR | `stock_zh_a_daily` | raw/QFQ/HFQ | A 股按证券 | 只做本地公开代码审计 |
| SINA-FAC | AKShare 1.18.64 / Sina | AKShare | Sina Finance | 同上 | `code + qfq/hfq factor` 候选 | ADJUSTMENT_FACTOR | `stock_zh_a_daily(adjust=qfq-factor/hfq-factor)` | independent factor surface | 按证券 | NOT_PROBED |
| SINA-CAL | AKShare 1.18.64 / Sina | AKShare | Sina Finance | 同上 | 未取得 | TRADING_CALENDAR | 当前日线函数未提供 | n/a | n/a | NOT_EXPOSED |
| SINA-ACT | AKShare 1.18.64 / Sina | AKShare | Sina Finance | 同上 | 未取得 | CORPORATE_ACTION | 不在已审计函数范围 | n/a | n/a | NOT_PROBED |
| EM-RAW | AKShare 1.18.64 / Eastmoney | AKShare | Eastmoney | `EASTMONEY_RESEARCH_CANDIDATE_V1` | 东财 market/security code 候选 | RAW_DAILY_BAR | `stock_zh_a_hist` | raw/QFQ/HFQ | A 股按证券 | 只做本地公开代码审计 |
| EM-FAC | AKShare 1.18.64 / Eastmoney | AKShare | Eastmoney | 同上 | 未取得 | ADJUSTMENT_FACTOR | 当前函数未返回独立 factor | n/a | n/a | NOT_EXPOSED |
| EM-CAL | AKShare 1.18.64 / Eastmoney | AKShare | Eastmoney | 同上 | 未取得 | TRADING_CALENDAR | 不在已审计函数范围 | n/a | n/a | NOT_PROBED |
| EM-ACT | AKShare 1.18.64 / Eastmoney | AKShare | Eastmoney | 同上 | 未取得 | CORPORATE_ACTION | 不在已审计函数范围 | n/a | n/a | NOT_PROBED |
| AKCN-RAW | AKShare 1.18.64 / CNINFO | AKShare | CNINFO | `AKSHARE_CNINFO_RESEARCH_V1` | n/a | RAW_DAILY_BAR | `stock_zh_a_disclosure_report_cninfo` 不提供行情 | n/a | n/a | UNAVAILABLE |
| AKCN-FAC | AKShare 1.18.64 / CNINFO | AKShare | CNINFO | 同上 | n/a | ADJUSTMENT_FACTOR | 公告函数不提供因子 | n/a | n/a | UNAVAILABLE |
| AKCN-CAL | AKShare 1.18.64 / CNINFO | AKShare | CNINFO | 同上 | 未取得 | TRADING_CALENDAR | 公告函数不提供日历 | n/a | n/a | NOT_EXPOSED |
| AKCN-ACT | AKShare 1.18.64 / CNINFO | AKShare | CNINFO | 同上 | `CNINFO:<announcementId>` 或 URL Hash | CORPORATE_ACTION | 公告元数据；不解析 PDF 语义 | disclosure evidence | A 股公告、日期级时间 | 复用 2G Live Gate |
| CNI-RAW | CNINFO 官方平台 | 官方网页/数据服务入口 | CNINFO | `CNINFO_OFFICIAL_EVIDENCE_V1` | n/a | RAW_DAILY_BAR | 公告/公开信息页不是 EOD Adapter | n/a | n/a | UNAVAILABLE |
| CNI-FAC | CNINFO 官方平台 | 官方网页/数据服务入口 | CNINFO | 同上 | n/a | ADJUSTMENT_FACTOR | 未发现公开独立因子证据 | n/a | n/a | NOT_EXPOSED |
| CNI-CAL | CNINFO 官方平台 | 官方网页/数据服务入口 | CNINFO | 同上 | 深市日历入口存在；版本身份未审计 | TRADING_CALENDAR | 深市日历导航 | n/a | 深市公开信息 | NOT_PROBED |
| CNI-ACT | CNINFO 官方平台 | 官方网页/数据服务入口 | CNINFO | 同上 | 公告 ID/URL；结构化 action ID 未取得 | CORPORATE_ACTION | 法定披露公告元数据/PDF | disclosure evidence | 沪深京公告 | 官方页面读取成功 |
| SSE-RAW | SSE | 官方网站/授权服务待确认 | Shanghai Stock Exchange | `SSE_OFFICIAL_EVIDENCE_V1` | SSE instrument candidate | RAW_DAILY_BAR | 公开 schedule 不提供 EOD 数据 | n/a | n/a | NOT_PROBED |
| SSE-FAC | SSE | 官方网站/授权服务待确认 | Shanghai Stock Exchange | 同上 | 未取得 | ADJUSTMENT_FACTOR | 公开 schedule 未暴露 | n/a | n/a | NOT_EXPOSED |
| SSE-CAL | SSE | 官方网站 | Shanghai Stock Exchange | 同上 | `CALENDAR:SSE` 证据候选 | TRADING_CALENDAR | official trading schedule | n/a | 交易时段和年度休市 | 页面读取成功 |
| SSE-ACT | SSE | 官方网站/披露入口 | Shanghai Stock Exchange | 同上 | 未取得 | CORPORATE_ACTION | 未在 F0 扩大页面抓取 | disclosure evidence candidate | 沪市 | NOT_PROBED |
| SZSE-RAW | SZSE | 官方网站/授权服务待确认 | Shenzhen Stock Exchange | `SZSE_OFFICIAL_EVIDENCE_V1` | SZSE instrument candidate | RAW_DAILY_BAR | data services 页面 | n/a | 待确认 | 页面读取超时 |
| SZSE-FAC | SZSE | 官方网站/授权服务待确认 | Shenzhen Stock Exchange | 同上 | 未取得 | ADJUSTMENT_FACTOR | 未取得公开函数证据 | n/a | 待确认 | NOT_PROBED |
| SZSE-CAL | SZSE | 官方网站 | Shenzhen Stock Exchange | 同上 | `CALENDAR:SZSE` 证据候选 | TRADING_CALENDAR | trading overview 页面 | n/a | 待确认 | 页面读取超时 |
| SZSE-ACT | SZSE | 官方网站/CNINFO | Shenzhen Stock Exchange | 同上 | 直接事件身份未取得 | CORPORATE_ACTION | 法定披露主要由 CNINFO 承担 | disclosure evidence candidate | 深市 | NOT_PROBED |
| SZSI-RAW | 深圳证券信息有限公司 | 官方数据服务 | Shenzhen Securities Information Co. | `SZSI_MARKET_DATA_SERVICE_CANDIDATE_V1` | 待正式数据字典 | RAW_DAILY_BAR | 行情服务/海外服务 | 待确认 | 待确认 | 页面读取超时 |
| SZSI-FAC | 深圳证券信息有限公司 | 官方数据服务 | Shenzhen Securities Information Co. | 同上 | 待正式数据字典 | ADJUSTMENT_FACTOR | 待确认 | 待确认 | 待确认 | NOT_PROBED |
| SZSI-CAL | 深圳证券信息有限公司 | 官方数据服务 | Shenzhen Securities Information Co. | 同上 | `CALENDAR:SZSE` 待确认 | TRADING_CALENDAR | 待确认 | n/a | 待确认 | NOT_PROBED |
| SZSI-ACT | 深圳证券信息有限公司 | 官方数据服务 | Shenzhen Securities Information Co. | 同上 | 待正式数据字典 | CORPORATE_ACTION | 待确认 | n/a | 待确认 | NOT_PROBED |

## 3. 字段、单位、精度、空值和明确 0

| recordId | unit | precision | null semantics | zero semantics |
|---|---|---|---|---|
| BAO-RAW | OHLC/amount/volume/turn 官方冻结单位未取得：UNVERIFIED | 返回十进制字符串可统计；正式精度 UNVERIFIED | 本次日线无空值；长期规则 UNVERIFIED | `isST=0` 可区分；价格/量 0 规则 UNVERIFIED |
| BAO-FAC | factor 单位/归一化语义 UNVERIFIED | 字段存在；固定区间无值 | 空结果与缺失字段可区分 | 无样本，UNVERIFIED |
| BAO-CAL | `is_trading_day` 布尔编码 PARTIAL | `0/1` | 本次无空值 | 休市 `0` 明确存在 |
| BAO-ACT | 分红送转单位 UNVERIFIED | 十进制字段 PARTIAL | 本次多个字段为空 | 送股字段明确 `0` 与空值可区分 |
| TX-RAW | 当前 payload 的 `amount` 语义与旧链 volume 映射冲突：CONFLICTING_EVIDENCE | 公开响应字符串/数字，冻结精度 UNVERIFIED | 旧 normalizer 会补 0，不能用于 V13 | amount 补 0 不代表 Provider 明确 0 |
| TX-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| TX-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| TX-ACT | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SINA-RAW | OHLC/volume/outstanding share 语义需数据字典 | 本地代码显示 factor 与价格列，精度未冻结 | NOT_PROBED | NOT_PROBED |
| SINA-FAC | qfq/hfq factor 语义 PARTIAL | NOT_PROBED | NOT_PROBED | NOT_PROBED |
| SINA-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SINA-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| EM-RAW | 中文行情字段单位需上游字典 | 精度 UNVERIFIED | NOT_PROBED | NOT_PROBED |
| EM-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| EM-CAL | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| EM-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| AKCN-RAW | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-ACT | 公告元数据文本/日期；PDF 单位不解析 | 发布时间精度固定 DATE_ONLY | 2G 有严格必填/拒绝规则 | n/a |
| CNI-RAW | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| CNI-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| CNI-CAL | 页面语义待审 | 待审 | 待审 | 待审 |
| CNI-ACT | 公告文本和日期；结构化 action 单位未取得 | 公告页面时间展示，AKShare 路线仅 DATE_ONLY | 公告元数据 PARTIAL | n/a |
| SSE-RAW | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SSE-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SSE-CAL | 交易日/时段语义 VERIFIED | 页面级日期和时间 | n/a | 开/闭市语义可核验 |
| SSE-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-RAW | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-FAC | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-CAL | 页面超时，UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSI-RAW | 正式数据字典待取得 | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSI-FAC | 正式数据字典待取得 | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSI-CAL | 正式数据字典待取得 | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSI-ACT | 正式数据字典待取得 | UNVERIFIED | UNVERIFIED | UNVERIFIED |

## 4. 修订、快照和知识时间

| recordId | revision | snapshot | publishedAt | updatedAt | historical versions |
|---|---|---|---|---|---|
| BAO-RAW | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | UNVERIFIED |
| BAO-FAC | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | UNVERIFIED |
| BAO-CAL | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | UNVERIFIED |
| BAO-ACT | NOT_EXPOSED | NOT_EXPOSED | 多个业务日期不等于 Provider 发布时间 | NOT_EXPOSED | UNVERIFIED |
| TX-RAW | `version=18` 为 UNVERIFIED，不得接入 | NOT_EXPOSED | HTTP Date 不合格 | NOT_EXPOSED | NOT_EXPOSED |
| TX-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| TX-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| TX-ACT | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SINA-RAW | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | UNVERIFIED |
| SINA-FAC | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | NOT_EXPOSED | UNVERIFIED |
| SINA-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SINA-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| EM-RAW | NOT_EXPOSED | NOT_EXPOSED | HTTP Date 不合格 | NOT_EXPOSED | NOT_EXPOSED |
| EM-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| EM-CAL | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| EM-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| AKCN-RAW | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-ACT | revisionRelationshipGuaranteed=false | NOT_EXPOSED | DATE_ONLY reported date，不是精确发布时间 | NOT_EXPOSED | 旧版本关系 UNVERIFIED |
| CNI-RAW | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| CNI-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| CNI-CAL | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| CNI-ACT | 更正/撤回关系未证明 | 页面查询不是 snapshot | 页面显示公告时间，精度/首次发布时间未冻结 | UNVERIFIED | 旧版本关系 UNVERIFIED |
| SSE-RAW | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SSE-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SSE-CAL | 修订版本未提供 | 页面不是 snapshot | 年度安排公开时间未进入机器契约 | 页面变更时间未取得 | 旧页面版本 UNVERIFIED |
| SSE-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-RAW | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-FAC | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-CAL | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSI-RAW | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 |
| SZSI-FAC | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 |
| SZSI-CAL | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 |
| SZSI-ACT | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 | 需书面确认 |

## 5. 用途与许可

| recordId | local persistence | replay | backtest | Agent use | commercial use |
|---|---|---|---|---|---|
| BAO-RAW | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED；BSD 仅覆盖客户端代码 |
| BAO-FAC | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| BAO-CAL | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| BAO-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| TX-RAW | UNVERIFIED | UNVERIFIED | 仅研究辅助 | 仅研究辅助 | UNVERIFIED |
| TX-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| TX-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| TX-ACT | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SINA-RAW | UNVERIFIED | UNVERIFIED | 仅研究辅助 | 仅研究辅助 | UNVERIFIED |
| SINA-FAC | UNVERIFIED | UNVERIFIED | 仅研究辅助 | 仅研究辅助 | UNVERIFIED |
| SINA-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SINA-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| EM-RAW | UNVERIFIED | UNVERIFIED | 仅研究辅助 | 仅研究辅助 | UNVERIFIED |
| EM-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| EM-CAL | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| EM-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| AKCN-RAW | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-CAL | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| AKCN-ACT | 2G 仅研究捕获；正式许可未取得 | 首次捕获后研究回放 | 标题规则研究 | 内部研究 Agent | 商业使用 UNVERIFIED |
| CNI-RAW | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| CNI-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| CNI-CAL | UNVERIFIED | UNVERIFIED | 官方核验证据 | 官方核验证据 | UNVERIFIED |
| CNI-ACT | 批量保存 UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED；数据 API/商城为独立服务 |
| SSE-RAW | 需授权服务确认 | 需确认 | 需确认 | 需确认 | 需确认 |
| SSE-FAC | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE | UNAVAILABLE |
| SSE-CAL | 页面证据可人工核验；批量落库未批准 | UNVERIFIED | 核验用途 | 核验用途 | 行情商业权利不能推断 |
| SSE-ACT | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSE-RAW | 需授权服务确认 | 需确认 | 需确认 | 需确认 | 需确认 |
| SZSE-FAC | 需确认 | 需确认 | 需确认 | 需确认 | 需确认 |
| SZSE-CAL | 页面证据候选；批量落库未批准 | UNVERIFIED | 核验用途候选 | 核验用途候选 | 行情商业权利不能推断 |
| SZSE-ACT | 通过 CNINFO 核验证据；批量权利未取得 | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| SZSI-RAW | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 |
| SZSI-FAC | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 |
| SZSI-CAL | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 |
| SZSI-ACT | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 | 需正式许可 |

## 6. 运行稳定性、证据和最终角色

| recordId | rate limit | stability | probe result | evidenceId | status | F1 role |
|---|---|---|---|---|---|---|
| BAO-RAW | 官方限流值 UNVERIFIED | 单会话短探针稳定 | 两证券 SUCCESS | `F0-EVID-BAO-LIVE-001` | PARTIAL | PENDING_WRITTEN_PERMISSION |
| BAO-FAC | 官方限流值 UNVERIFIED | 函数存在，短区间为空 | 两次 EMPTY | `F0-EVID-BAO-LIVE-001` | PARTIAL | PENDING_WRITTEN_PERMISSION |
| BAO-CAL | 官方限流值 UNVERIFIED | 短探针稳定；缺 exchange identity | SUCCESS | `F0-EVID-BAO-LIVE-001` | PARTIAL | PENDING_WRITTEN_PERMISSION |
| BAO-ACT | 官方限流值 UNVERIFIED | 单证券年度调用稳定 | SUCCESS | `F0-EVID-BAO-LIVE-001` | PARTIAL | PENDING_WRITTEN_PERMISSION |
| TX-RAW | 无本项目 SLA | AKShare 页面适配存在变化风险 | 既有两次一致；revision 未验证 | `F0-EVID-TENCENT-001` | PARTIAL | RESEARCH_AUXILIARY_ONLY |
| TX-FAC | n/a | n/a | NOT_EXPOSED | `F0-EVID-AKS-CODE-001` | NOT_EXPOSED | RESEARCH_AUXILIARY_ONLY |
| TX-CAL | n/a | n/a | NOT_EXPOSED | `F0-EVID-AKS-CODE-001` | NOT_EXPOSED | RESEARCH_AUXILIARY_ONLY |
| TX-ACT | n/a | n/a | NOT_EXPOSED | `F0-EVID-AKS-CODE-001` | NOT_EXPOSED | RESEARCH_AUXILIARY_ONLY |
| SINA-RAW | 无本项目 SLA | 页面/API变化风险 | NOT_PROBED | `F0-EVID-AKS-CODE-001` | PARTIAL | RESEARCH_AUXILIARY_ONLY |
| SINA-FAC | 无本项目 SLA | 页面/API变化风险 | NOT_PROBED | `F0-EVID-AKS-CODE-001` | NOT_PROBED | RESEARCH_AUXILIARY_ONLY |
| SINA-CAL | n/a | n/a | NOT_EXPOSED | `F0-EVID-AKS-CODE-001` | NOT_EXPOSED | RESEARCH_AUXILIARY_ONLY |
| SINA-ACT | 未审计 | 未审计 | NOT_PROBED | `F0-EVID-AKS-DOC-001` | NOT_PROBED | RESEARCH_AUXILIARY_ONLY |
| EM-RAW | 无本项目 SLA | 页面/API变化风险 | NOT_PROBED | `F0-EVID-AKS-CODE-001` | PARTIAL | RESEARCH_AUXILIARY_ONLY |
| EM-FAC | n/a | n/a | NOT_EXPOSED | `F0-EVID-AKS-CODE-001` | NOT_EXPOSED | RESEARCH_AUXILIARY_ONLY |
| EM-CAL | 未审计 | 未审计 | NOT_PROBED | `F0-EVID-AKS-DOC-001` | NOT_PROBED | RESEARCH_AUXILIARY_ONLY |
| EM-ACT | 未审计 | 未审计 | NOT_PROBED | `F0-EVID-AKS-DOC-001` | NOT_PROBED | RESEARCH_AUXILIARY_ONLY |
| AKCN-RAW | n/a | n/a | UNAVAILABLE | `F0-EVID-AKS-CNINFO-001` | UNAVAILABLE | RESEARCH_AUXILIARY_ONLY |
| AKCN-FAC | n/a | n/a | UNAVAILABLE | `F0-EVID-AKS-CNINFO-001` | UNAVAILABLE | RESEARCH_AUXILIARY_ONLY |
| AKCN-CAL | n/a | n/a | NOT_EXPOSED | `F0-EVID-AKS-CNINFO-001` | NOT_EXPOSED | RESEARCH_AUXILIARY_ONLY |
| AKCN-ACT | 2G 有受控节流 | AKShare结构变化门禁已实现；无 SLA | 复用 2G Live Gate | `F0-EVID-AKS-CNINFO-001` | PARTIAL | RESEARCH_AUXILIARY_ONLY |
| CNI-RAW | n/a | n/a | UNAVAILABLE | `F0-EVID-CNINFO-001` | UNAVAILABLE | OFFICIAL_EVIDENCE_ONLY |
| CNI-FAC | n/a | n/a | NOT_EXPOSED | `F0-EVID-CNINFO-001` | NOT_EXPOSED | OFFICIAL_EVIDENCE_ONLY |
| CNI-CAL | 未审计 | 未审计 | NOT_PROBED | `F0-EVID-CNINFO-001` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |
| CNI-ACT | 批量边界未批准 | 法定平台稳定；完整性不担保 | 页面 SUCCESS | `F0-EVID-CNINFO-001` | PARTIAL | OFFICIAL_EVIDENCE_ONLY |
| SSE-RAW | 授权服务待确认 | 官方 schedule 不代表数据 SLA | NOT_PROBED | `F0-EVID-SSE-001` | UNVERIFIED | OFFICIAL_EVIDENCE_ONLY |
| SSE-FAC | n/a | n/a | NOT_EXPOSED | `F0-EVID-SSE-001` | NOT_EXPOSED | OFFICIAL_EVIDENCE_ONLY |
| SSE-CAL | 页面访问正常 | 官方页面稳定性仍非 API SLA | SUCCESS | `F0-EVID-SSE-001` | VERIFIED | OFFICIAL_EVIDENCE_ONLY |
| SSE-ACT | 未审计 | 未审计 | NOT_PROBED | `F0-EVID-SSE-001` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |
| SZSE-RAW | 未取得 | 页面本轮超时 | TIMEOUT | `F0-EVID-SZSE-002` | UNVERIFIED | OFFICIAL_EVIDENCE_ONLY |
| SZSE-FAC | 未取得 | 未审计 | NOT_PROBED | `F0-EVID-SZSE-002` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |
| SZSE-CAL | 未取得 | 页面本轮超时 | TIMEOUT | `F0-EVID-SZSE-001` | UNVERIFIED | OFFICIAL_EVIDENCE_ONLY |
| SZSE-ACT | 未取得 | CNINFO 为主要官方证据 | NOT_PROBED | `F0-EVID-CNINFO-001` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |
| SZSI-RAW | 待书面确认 | 页面本轮超时 | TIMEOUT | `F0-EVID-SZSI-001` | UNVERIFIED | OFFICIAL_EVIDENCE_ONLY |
| SZSI-FAC | 待书面确认 | 未审计 | NOT_PROBED | `F0-EVID-SZSI-001` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |
| SZSI-CAL | 待书面确认 | 未审计 | NOT_PROBED | `F0-EVID-SZSI-001` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |
| SZSI-ACT | 待书面确认 | 未审计 | NOT_PROBED | `F0-EVID-SZSI-001` | NOT_PROBED | OFFICIAL_EVIDENCE_ONLY |

## 7. 冻结结论

- `F0_AUDIT_RESULT=PARTIAL`；
- BaoStock 是技术上最接近免费主源的候选，但角色仍为
  `PENDING_WRITTEN_PERMISSION`；
- BaoStock 独立因子接口存在，`DAILY_EXACT` 没有得到证明；
- AKShare 必须按 Tencent/Sina/Eastmoney/CNINFO 分源，全部仅为研究辅助；
- CNINFO/SSE/SZSE/SZSI 只承担官方证据角色；
- 当前没有一个免费来源能单独满足 raw/factor/calendar/action 的完整同源 V13/QFQ
  lineage；
- 不从价格反推因子，不跨 Provider 拼接，不把页面或包许可证升级为数据许可；
- `FREE_PROVIDER_VALIDATION_GATE=BLOCKED`。
