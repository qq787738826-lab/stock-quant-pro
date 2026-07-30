# 3A-R3B-TRACK-B1：Tushare 2000 积分受控权限探针与 F1 准入复核任务书

## 1. 任务身份

- 性质：只记录已经完成的真实受控探针，并重新评估 F1 准入状态。
- 冻结集成基线：`284588242443af5ce03b468825f861b29ced5ad0`
- 任务分支：`codex/1.4.0-stage-3ar3b-track-b1-tushare-probe-review`
- 目标提交：`docs(agent): record tushare permission probe`
- 探针执行日期：`2026-07-30`
- 探针执行时刻：`PROBE_EXECUTION_TIME=UNKNOWN`
- 治理记录日期：`2026-07-30`
- 本治理阶段新增 Provider 调用数：0。
- Tushare 累计真实业务请求数：10。
- iFinD 真实调用数：0。

本阶段不开发 Adapter、不修改生产代码、不访问数据库、不执行 V13、不再次调用 Provider。

## 2. 前置事实

1. Track B0 最终提交 `284588242443af5ce03b468825f861b29ced5ad0` 已通过实际 Git 复验，经用户批准纯 fast-forward 合入。
2. Track B0 已固定：
   - `TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST`，具体为 Tushare Pro；
   - `TRACK_B_FALLBACK_ROUTE=IFIND`；
   - `F1_ENTRY_READINESS=BLOCKED_MULTIPLE`。
3. 用户随后实际开通 Tushare 2000 积分权限，并授权一次只读、临时、受控的技术权限探针。
4. 环境变量存在，但其内容从未输出、记录、复制或写入文件。
5. 探针完成后临时 venv、缓存和输出已删除；仓库零变化。
6. 本次是用户购买权限后专项授权的最小技术权限检查；执行前没有取得 Provider 对最小自动 API 探针及响应留存/删除边界的两项书面答复，因此不是完整 Track B 证据探针，也不判定其合法或违法。

## 3. 探针边界

- Python：`3.11.9`
- tushare：`1.4.29`
- pandas：`3.0.5`
- 证券：`600000.SH`、`000001.SZ`
- 交易日：`20250102`、`20250103`
- 日历范围：`20250101`—`20250105`
- 业务请求：精确 10 次
- 重试：0
- 权限错误：0
- 网络错误：0
- 全市场抓取：否
- 数据库写入：否
- 正常业务库 V13：未执行
- 完整原始响应或 CSV 留存：否
- 临时文件残留：0

本任务书只保存字段、行数、日期范围和资格结论，不保存实际行情值、完整响应、Token、订单号、手机号或支付信息。

## 4. 十项固定结果

| # | Endpoint | 目标 | 状态 | 行数 | 字段 | 日期范围 |
|---:|---|---|---|---:|---|---|
| 1 | `stock_basic` | `600000.SH` | `PASS` | 1 | `ts_code,symbol,name,area,industry,market,exchange,list_status,list_date,delist_date` | 不适用 |
| 2 | `stock_basic` | `000001.SZ` | `PASS` | 1 | 同上 | 不适用 |
| 3 | `trade_cal` | SSE | `PASS` | 5 | `exchange,cal_date,is_open,pretrade_date` | `20250101`—`20250105` |
| 4 | `trade_cal` | SZSE | `PASS` | 5 | 同上 | `20250101`—`20250105` |
| 5 | `daily` | `600000.SH` | `PASS` | 2 | `ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount` | `20250102`—`20250103` |
| 6 | `daily` | `000001.SZ` | `PASS` | 2 | 同上 | `20250102`—`20250103` |
| 7 | `adj_factor` | `600000.SH` | `PASS` | 2 | `ts_code,trade_date,adj_factor` | `20250102`—`20250103` |
| 8 | `adj_factor` | `000001.SZ` | `PASS` | 2 | 同上 | `20250102`—`20250103` |
| 9 | `dividend` | `600000.SH` | `PASS` | 51 | `ts_code,end_date,ann_date,div_proc,stk_div,stk_bo_rate,stk_co_rate,cash_div,cash_div_tax,record_date,ex_date,pay_date,div_listdate,imp_ann_date` | 无 `trade_date` |
| 10 | `dividend` | `000001.SZ` | `PASS` | 53 | 同上 | 无 `trade_date` |

综合结论：

```text
TUSHARE_2000_PERMISSION_PROBE=PASS
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
```

该 PASS 只证明当前账号在上述固定范围内具备技术调用权限，不证明数据用途许可、长期稳定性、历史修订能力或 F1 准入。完整探针合同要求的 Provider 书面自动探针许可和临时响应、Hash、摘要、夹具保存/删除边界均未在执行前取得，因此不能宣称完整探针 `SUCCESS`，也不需要且不得为修正文档而重新执行这 10 次请求。

## 5. 允许升级的技术结论

| 能力 | B1 结论 | 精确边界 |
|---|---|---|
| 2000 积分核心接口权限 | `VERIFIED` | 十项请求均为 `PASS`，无权限或网络错误 |
| raw daily 最小技术样例 | `VERIFIED` | 两证券、两交易日返回请求字段 |
| adjustment factor 最小技术样例 | `VERIFIED` | 两证券、两交易日均有同日因子 |
| SSE/SZSE calendar 最小技术样例 | `VERIFIED` | 两交易所身份和五个日历日均返回 |
| stock_basic 普通身份字段 | `VERIFIED` | `ts_code/exchange/list_status/list_date/delist_date` 可取 |
| dividend 接口与公开字段 | `VERIFIED` | 两证券均返回记录和公开字段 |
| `DAILY_EXACT` 最小样例 | `VERIFIED` | 仅限两证券、两个已验证交易日 |

以下资格保持不变：

```text
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL
```

不得把最小样例 PASS 扩写为全市场覆盖、长期稳定、完整公司行动、Provider PIT 或永久证券身份。

## 6. 仍未解除的技术阻断

1. `dividend` 未证明配股、拆股、并股、更正和撤回的完整覆盖。
2. 没有验证稳定公司行动事件 ID。
3. 没有验证 factor 与 action 之间的稳定解释关系。
4. 没有验证：
   - `revisionId`；
   - `snapshotId`；
   - `providerPublishedAt`；
   - `providerUpdatedAt`；
   - 历史旧版本查询。
5. 没有验证永久证券身份的换码、迁板、重新上市和历史映射。
6. 两证券、两日样例不能证明全历史 `DAILY_EXACT` 或静默修正行为。

## 7. 成本与书面许可

成本事实更新为：

- 用户已经实际开通 Tushare 2000 积分权限；
- 受控探针证明该权限已生效；
- 不记录订单号、手机号、支付渠道或其他隐私；
- `BLOCKED_COST_APPROVAL` 已解除；
- `PAID_PROVIDER_UPGRADE_DECISION=PENDING` 保持不变，因为该门控制后续专业付费升级和 iFinD，而不是本次低成本权限。

以下书面许可仍为 `BLOCKED`：

- 最小自动 API 探针；
- 临时响应、Hash、摘要和夹具的保存/删除范围；
- 本地长期持久化；
- 历史回放和回测；
- 内部 Agent；
- 派生指标；
- 本地 UI；
- 备份与脱敏 fixture；
- 服务到期后的原始数据和衍生结果处理。

技术接口 PASS 不能替代书面许可。

后续任何扩大 Provider 调用的阶段仍必须取得独立用户授权，并先处理完整探针合同要求的书面许可。

## 8. F1 重新判定

当前仍为：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

阻断组成从 Track B0 时点的“书面许可 + 技术证据 + 成本批准”缩小为：

1. `BLOCKED_WRITTEN_PERMISSION`
2. `BLOCKED_TECHNICAL_EVIDENCE`

`BLOCKED_COST_APPROVAL` 已解除，不再列为当前阻断。

只有以下全部完成后才能由独立治理阶段讨论 `READY`：

1. Tushare 书面允许本地保存、回放、回测和内部 Agent；
2. 明确服务到期后的数据处理；
3. 公司行动覆盖范围与事件身份满足合同；
4. 修订/版本语义得到明确结论；
5. 永久证券身份边界冻结；
6. 用户单独批准 F1 实施。

即使前五项完成，也不得由本任务自动开始 F1。

## 9. 正式门禁和阶段边界

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

并保持：

- F1/F2B/F3 未开始；
- 数据库未访问；
- 正常业务库 V13 未执行；
- Day 002 未创建；
- scheduler 关闭；
- 3A-R3B-1 未开始；
- 3B 未开始；
- iFinD 调用数为 0；
- 真实交易和自动交易未授权。

## 10. 文档与验收

本阶段只允许新增两份 B1 Markdown，并同步八份 Track B 权威治理 Markdown。禁止修改 `PROGRESS_LOG.md`、Java、Python、Vue、SQL、Flyway、配置、依赖、Provider 运行代码、原始响应、CSV、凭据或 `.ai/`。

验收必须证明：

1. B1 真实探针日期为 `2026-07-30`，执行时刻只能为 `UNKNOWN`；
2. 十项状态、行数、字段和日期范围一致；
3. `TUSHARE_2000_PERMISSION_PROBE=PASS` 只有技术权限含义；
4. `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`；
5. `TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`，两项书面许可均为 `UNVERIFIED`；
6. `DAILY_EXACT` 只升级为最小样例 `VERIFIED`；
7. `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL` 和稳定证券 ID `PARTIAL` 保持不变；
8. F1 当前仅由书面许可和剩余技术证据阻断；
9. 成本批准阻断已解除；
10. 四项正式门禁保持不变；
11. 本治理阶段没有新增 Provider 调用、代码或数据库变化。

## 11. 后续 F1A 状态说明

本节记录 B1 合入后的后续事实，不改写第 8 节所保留的 B1 时点历史状态。

`2026-07-30`，Tushare 官方企业微信书面回复确认个人 2000 积分用户可以本地存储、
策略回测和智能体分析。F1A 因而将有限个人用途状态更新为：

```text
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=VERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=VERIFIED
BLOCKED_WRITTEN_PERMISSION=RESOLVED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=READY
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

该后续书面回复不改变 B1 完整证据探针的历史状态：
`TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`、自动探针与响应留存书面许可仍为
`UNVERIFIED`；服务到期留存仍未验证，原始数据再分发未获授权。F1A 只实现有限个人用途
raw/factor/calendar Adapter，完整公司行动、版本、永久身份和全历史 `DAILY_EXACT` 继续受
技术证据阻断。
