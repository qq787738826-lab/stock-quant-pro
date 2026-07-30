# 3A-R3B-TRACK-B1 阶段记录：Tushare 2000 积分受控权限探针与 F1 准入复核

## 1. 阶段结果

本阶段只记录 `2026-07-30` 已完成的 Tushare 受控权限探针，不再次调用 Provider。精确执行时刻没有可靠证据，统一记录为 `PROBE_EXECUTION_TIME=UNKNOWN`。十项请求均为 `PASS`：

```text
PROBE_EXECUTION_DATE=2026-07-30
PROBE_EXECUTION_TIME=UNKNOWN
TUSHARE_2000_PERMISSION_PROBE=PASS
TUSHARE_REAL_BUSINESS_CALL_COUNT=10
TUSHARE_RETRY_COUNT=0
TUSHARE_PERMISSION_ERROR_COUNT=0
TUSHARE_NETWORK_ERROR_COUNT=0
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
```

本次是用户购买权限后专项授权的最小技术权限检查。执行前未取得 Provider 对最小自动 API 探针和临时响应、Hash、摘要、夹具保存/删除边界的两项书面答复，因此不是完整 Track B 证据探针，不能宣称完整探针 `SUCCESS`；该记录不判定本次调用合法或违法。

当前 F1 结论仍为：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

成本批准阻断已经解除；当前只剩书面许可和剩余技术证据两类阻断。

## 2. Git 与范围

- 冻结基线：`284588242443af5ce03b468825f861b29ced5ad0`
- 任务分支：`codex/1.4.0-stage-3ar3b-track-b1-tushare-probe-review`
- 提交信息：`docs(agent): record tushare permission probe`
- 只新增 2 份 B1 Markdown，并更新 8 份授权治理 Markdown。
- 没有修改 `PROGRESS_LOG.md`、Java、Python、Vue、SQL、Flyway、配置、依赖或 Provider 运行代码。
- 没有访问数据库、执行 V13、启动服务、创建 Agent/Shadow 或开始后续阶段。
- 本治理阶段新增 Provider 调用数为 0；记录的是此前已经完成的 10 次 Tushare 真实业务请求。

## 3. 已完成探针

### 3.1 运行与安全边界

- Python：`3.11.9`
- tushare：`1.4.29`
- pandas：`3.0.5`
- 环境变量只确认存在，内容未输出、记录或写入文件。
- 固定证券：`600000.SH`、`000001.SZ`
- 固定交易日：`20250102`、`20250103`
- 日历范围：`20250101`—`20250105`
- 探针日期：`2026-07-30`
- 探针时刻：`UNKNOWN`
- 业务请求：10
- 重试：0
- 全市场调用：0
- 数据库写入：0
- 完整响应或 CSV 留存：0
- 临时 venv、缓存和输出残留：0

### 3.2 十项结果

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

没有保存实际市场值、完整响应、Token、认证头、订单或支付信息。

### 3.3 完整合同前置复核

完整 Track B 探针合同要求的两项 Provider 书面前置在执行前未满足：

1. Provider 书面允许本次最小自动 API 探针；
2. Provider 书面确认临时响应、Hash、摘要和夹具的保存/删除范围。

因此固定为：

```text
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
```

技术权限 PASS 不解除本地保存、回测、Agent、备份或服务到期留存阻断。不需要且不得重新执行这 10 次请求；后续扩大 Provider 调用前仍需独立授权并先处理书面许可。

## 4. 技术资格变化

以下最小技术样例升级为 `VERIFIED`：

1. Tushare 2000 积分核心接口权限；
2. raw daily；
3. adjustment factor；
4. SSE/SZSE calendar；
5. stock_basic 普通身份字段；
6. dividend 接口和公开字段；
7. 两证券、两交易日 `DAILY_EXACT`。

以下正式资格不变：

```text
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL
```

原因是最小样例没有证明公司行动完整覆盖、稳定事件 ID、factor/action 解释关系、Provider 修订和旧版本、永久证券身份或数据用途许可。

## 5. 未解除阻断

### 5.1 书面许可

- 本地长期保存；
- 历史回放和回测；
- 内部 Agent 与派生指标；
- 本地 UI；
- 备份和脱敏 fixture；
- 服务到期后的原始数据和衍生结果处理。

### 5.2 技术证据

- 配股、拆股、并股、更正和撤回的公司行动覆盖；
- 稳定 action ID；
- factor 与 action 的稳定解释关系；
- revision/snapshot/published/update/历史旧版本；
- 换码、迁板、重新上市和历史证券身份映射。

## 6. 成本复核

- 用户已经实际开通 Tushare 2000 积分权限；
- 技术探针证明权限已经生效；
- 不记录订单号、手机号、支付渠道或其他隐私；
- `BLOCKED_COST_APPROVAL` 已解除；
- `PAID_PROVIDER_UPGRADE_DECISION=PENDING` 不变，该门用于后续专业付费升级和 iFinD。

## 7. F1 准入复核

Track B0 时点的三类阻断为：

```text
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE
BLOCKED_COST_APPROVAL
```

B1 当前阻断缩小为：

```text
BLOCKED_WRITTEN_PERMISSION
BLOCKED_TECHNICAL_EVIDENCE
```

因此状态仍是 `F1_ENTRY_READINESS=BLOCKED_MULTIPLE`，但成本不再是当前阻断。取得书面许可、补齐剩余技术合同并冻结永久身份边界后，还必须由用户单独批准 F1，不能由 B1 自动开始。

## 8. 正式状态

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- F1/F2B/F3：未开始
- 数据库：未访问
- 正常业务库 V13：未执行
- Day 002：未创建
- scheduler：关闭
- 3A-R3B-1：未开始
- 3B：未开始
- iFinD 真实调用数：0
- 真实交易和自动交易：未授权

## 9. 当前任务分支状态

- Track B0 已通过实际 Git 最终复验并纯 fast-forward 合入。
- B1 探针事实与 F1 复核已在本任务分支完成文档落地。
- 待 ChatGPT 基于实际 Git 提交验收。
- 尚未合入集成分支。
- 本治理阶段没有新增 Provider 调用、代码、数据库或服务变化。

## 10. 合入与后续状态

B1 提交链
`39ec0411a10e1ea6ada9d34da4a20aee04382c92` →
`d223fdf9ff997ca256f2d0f651c99542e817dfee`
后来已通过 ChatGPT 实际 Git 最终复验，经用户批准纯 fast-forward 合入。上述第 7—9 节
是 B1 完成时的历史状态。

`2026-07-30`，Tushare 官方企业微信后续精确书面回复为“问：这个可以用来当量化数据
来源吧；答：可以”。F1A 据此固定：

```text
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=UNVERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=UNVERIFIED
USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

该回复没有逐项确认本地长期存储、回测或 Agent；有限个人实现由用户单独授权且不得分发、
转售或商业化原始数据。该后续状态不把 B1 完整证据探针升级为完整成功，也不改变服务到期留存
`UNVERIFIED`、原始数据再分发 `NOT_GRANTED`、`V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`
或稳定证券 ID `PARTIAL`。完整公司行动、版本、永久身份和全历史 `DAILY_EXACT` 仍是
技术阻断。
