# Track B F1 准入合同

## 1. 当前判定

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=UNVERIFIED
USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER
```

主要路线的具体 Provider 是 Tushare Pro。用户已开通 2000 积分且 `2026-07-30`
B1 技术权限探针通过，精确执行时刻为 `PROBE_EXECUTION_TIME=UNKNOWN`。同日 Tushare
官方企业微信书面只确认“可以用来当量化数据来源”，没有逐项确认本地长期存储、策略回测
和智能体分析。F1A 真实 Adapter 的有限个人实现授权来自用户明确批准，并绑定不分发、
不转售、不共享 Token/账号和不商业化原始数据的边界。

Tushare Pro 当前资格仍固定为 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`，稳定证券 ID
固定为 `PARTIAL`。B1 已把 raw、factor、SSE/SZSE calendar、普通证券身份、dividend
字段和两证券两日 `DAILY_EXACT` 最小样例验证为 `VERIFIED`；F1A 只允许 raw、factor 和
calendar 以 `RESEARCH_ONLY/SYSTEM_KNOWLEDGE_PIT/formalEligible=false` 进入随机隔离
Schema。stock_basic 只作为普通身份 DTO，dividend 只作为部分证据 DTO，不进入完整
公司行动。永久 instrument identity、完整 action、历史版本和 Provider PIT 均未确认。

完整 Track B 证据探针状态仍固定为 `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`。执行前没有取得 Provider 对最小自动 API 探针和响应留存/删除范围的书面答复，因此 `TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`、`WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED`、`WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED`。该历史状态不判定本次调用合法或违法，也不支持把 B1 追认为完整证据探针；F1A 由后续 `TS-WP-001` 的量化数据来源用途证据和用户有界个人实现授权共同支持，后续扩大调用仍需用户专项授权并遵守 180 次/分钟安全预算。

完整探针前置的历史状态与后续量化数据来源书面证据必须分开：TS-WP-001 没有解决
本地长期存储、回测和 Agent 的逐项 Provider 许可，也不回溯宣称 B1 完整探针在执行前
满足全部法律前置。用户授权只支持有界个人实现，不授权无界调用、原始数据再分发或服务
到期后永久留存。

## 2. F1 READY 的全部条件

| 门禁 | READY 证据 | 当前状态 | 当前 finding |
|---|---|---|---|
| 个人用途 | 官方书面许可适用于个人非商业研究 | PASS_RESTRICTED | 只限用户本人、2000 积分服务、非商业内部用途；不转售、不分发、不共享账号 |
| 本地持久化 | 明确允许 raw/factor/calendar/action、metadata、Hash、lineage 本地保存 | UNVERIFIED | TS-WP-001 未逐项确认；用户只批准个人自用有界实现，不对外分发 |
| 历史回放与回测 | 明确允许 cutoff 回放、回测和保存结果 | UNVERIFIED | TS-WP-001 未逐项确认；有限研究实现不等于 Provider 正式许可 |
| 内部 Agent | 明确允许本地 AI/Agent 分析 | UNVERIFIED | TS-WP-001 未逐项确认；不对外提供数据服务 |
| 终止后数据 | 明确保留/删除范围和期限 | UNVERIFIED_NON_EXPANSION | F1A 不据此承诺到期后永久留存 |
| 同 Provider raw | 正式字段、单位、精度、null/0 语义和最小响应样例 | PARTIAL | B1 最小样例已验证；完整单位/null/0 合同和长期稳定性仍需冻结 |
| 同 Provider factor | `ts_code + trade_date` 精确自然键、正数因子、日覆盖和单位 | PARTIAL | B1 两证券、两日 `DAILY_EXACT` 最小样例已验证；全历史覆盖和修订仍未证明 |
| 同 Provider calendar | SSE/SZSE 稳定身份与精确日期 | PARTIAL | B1 两交易所最小样例已验证；临时休市修订和旧版本仍未证明 |
| 同 Provider action | 事件自然键、类型、公告/生效/修订时间及覆盖清单 | BLOCKED | B1 证明 `dividend` 可调用；配股、拆并股、更正/撤回、稳定 ID 和 factor 解释关系未闭合 |
| 稳定证券身份 | `ts_code` 生命周期、换码/迁板/重新上市/退市及历史映射 | PARTIAL | B1 验证普通字段；永久 identity 与生命周期仍未获官方保证或样例 |
| 前向 PIT | 允许重复捕获并以真实首次接收建立 `SYSTEM_KNOWLEDGE_PIT` | PARTIAL | F1A 可在个人用途隔离范围建立 SYSTEM_KNOWLEDGE 链；无历史 revision、完整 action 和到期留存结论，禁止升级 Provider PIT |
| 成本 | 套餐、费用和所需接口范围明确，用户明确批准 | PASS | 用户已开通 2000 积分，B1 十项探针证明权限生效；不记录支付隐私 |
| 实现范围 | Adapter、DTO 映射、调用预算、错误/限流、数据清理、V13 迁移边界冻结 | F1A_IMPLEMENTED | 五 Endpoint、显式 MANUAL_BOUNDED、10 次共享预算、分钟/每日限额、随机 Schema 和受控 10 调用已在 F1A 任务分支完成；dividend 仍不升级完整 action |

只有上述所有条件均通过，才能把：

```text
F1_ENTRY_READINESS=READY
```

写入后续独立治理提交。任何单项通过都不得覆盖其他阻断。

## 3. 当前完整 F1 的两类阻断

量化数据来源用途已书面确认，且用户批准 F1A 有界个人实现；这允许 Adapter 基础和随机
隔离验证合入，但不把本地长期存储、策略回测和内部 Agent 三项 Provider 书面许可升级为
VERIFIED，也不表示原始数据再分发、商业化或服务到期后留存获批。

当前完整 F1 的阻断精确为：

1. `BLOCKED_WRITTEN_PERMISSION`：
   - 本地长期持久化；
   - 历史回放与回测；
   - 内部 Agent；
   - 服务到期后的数据留存/删除。
2. `BLOCKED_TECHNICAL_EVIDENCE`：
   - 公司行动是否覆盖配股、拆并股和更正/撤回；
   - 稳定 action ID 与 factor 变化的解释关系；
   - revision/snapshot/published/update/旧版本是否确实不可用；
   - 证券身份生命周期；
   - raw/factor/calendar/action 的完整字段资格、null/0、长期稳定和全历史覆盖。

`BLOCKED_COST_APPROVAL` 已解除：用户已开通 2000 积分，技术权限已验证生效。

因此当前合法判定是：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE
```

## 4. 获得答复后的判定树

1. F1A 已按量化数据来源证据和用户有界实现授权实现缩小能力：
   - V13 只包含 raw/factor/calendar；
   - stock_basic 只产生普通身份 DTO；
   - dividend 只产生不可写入完整公司行动的部分证据 DTO；
   - 只使用 `RESEARCH_ONLY/SYSTEM_KNOWLEDGE_PIT`；
   - 不迁移正常业务库，不启动 scheduler、F2B 或 F3。
2. 公司行动闭环不足：
   - 保持 `V13_LINEAGE_PARTIAL`；
   - 禁止用 CNINFO、BaoStock 或 AKShare 拼接成同源 lineage；
   - 完整 QFQ/四事实 F1 继续阻断。
3. 若后续取得 action/identity/version 证据：
   - 在独立治理阶段复核 `F1_ENTRY_READINESS`；
   - 不由 F1A 自动升级到 READY、F2B 或 F3。
4. 若技术合同长期无法满足：
   - 保持 iFinD 为备用路线；
   - 仍需用户单独批准报价或试用，不因 F1A 自动激活。

## 5. F1A 已冻结技术范围

1. Gateway 固定五 Endpoint；`TushareMarketFactProvider` 只把 raw/factor/calendar 映射为
   V13 事实，stock_basic/dividend 保持普通身份/部分证据；
2. HTTP 只使用官方 HTTPS Host；
3. 官方 200 次/分钟、每 API 100000 次/日，应用安全预算为 180 次/分钟、每 API
   90000 次/日；
4. 所有 Endpoint 和进程内调用入口共享单进程限流器，不声明跨进程 Token 协调；
5. 默认 `DISABLED`，联网必须显式 `MANUAL_BOUNDED`，五 Endpoint 共用 10 次会话预算；
   `daily/adj_factor/trade_cal` 的固定两日约束不扩展到无日期参数的
   `stock_basic/dividend`；
6. `stock_basic` 和 `dividend` 在 DTO 生成前分别执行 1 行和 1000 行硬上限，超限返回
   `TUSHARE_REFERENCE_ROW_LIMIT_EXCEEDED`，不得截断；
7. 正常限流重试可配置且默认最多 2 次，受控验收零重试；
8. Token 不进入日志、metadata、异常或 fixture；
9. 正常业务库不迁移，随机隔离 Schema 执行 V1→V13；
10. 通用捕获拒绝 FORMAL；只有类型化有限个人授权入口可捕获 Tushare
    raw/factor/calendar；
11. capability 明确 `fullF1EntryReady=false`、
    `authorizationBasis=USER_APPROVED_LIMITED_PERSONAL_USE`、
    `providerWrittenPermissionComplete=false`；
12. partial Provider 响应不写事实观察；
13. 公司行动、Provider revision 和永久证券身份不实现、不伪造。

## 6. 继续禁止

- F1A 验收完成后不追加无授权 Tushare 调用；
- 不申请、激活或调用 iFinD；
- 不迁移正常业务库 V13；
- 不写业务数据库；
- 不跨 Provider 拼接 QFQ；
- 不启动 F2B、F3、Shadow、Day 002 或 scheduler；
- 不开始 3A-R3B-1 或 3B；
- 不授权投资建议、券商账户、真实交易或自动交易。
