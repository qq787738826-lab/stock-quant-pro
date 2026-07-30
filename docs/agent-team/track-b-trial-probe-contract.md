# Track B 最小试用探针合同

## 1. 适用范围

本合同冻结 Track B 最小技术探针的一般边界。`2026-07-30` 已按用户专项授权执行 Tushare 2000 积分权限子集探针，精确执行时刻统一记录为 `PROBE_EXECUTION_TIME=UNKNOWN`。执行前没有取得本合同第 2、3 项 Provider 书面前置，因此该子集不是完整 Track B 证据探针，也没有完成 revision、稳定复取和全部用途许可验证。iFinD 探针仍未执行。

```text
MAX_BUSINESS_REQUESTS=10
MAX_SYMBOLS=2
MAX_HISTORICAL_TRADING_DAYS=2
RETRY_ALLOWED=false
NORMAL_BUSINESS_DATABASE_WRITE=false
SCHEDULER_ENABLED=false
DAY_002_CREATED=false
```

固定证券：

- `600000.SH`
- `000001.SZ`

一般合同固定日期：

- `T1`、`T2`：由候选 Provider 的官方交易日历在执行前确认的两个连续历史交易日；
- 在合同、字段和额度书面确认前不把具体日期或函数名猜成事实。

## 2. 法律前置条件

执行前必须全部满足：

1. 用户亲自取得和保管账号/Token，不写入 Git、文档、日志或夹具；
2. Provider 书面允许本次最小自动 API 探针；
3. Provider 书面说明临时原始响应、Hash、脱敏摘要和测试夹具的保存/删除范围；
4. 试用或积分价格和额度已由用户批准；
5. 函数、字段、证券、日期和 10 次预算已经冻结；
6. ChatGPT 基于实际治理提交验收，用户另行授权执行；
7. 误配置不能触发全市场、scheduler、无限重试或正常业务库写入。

## 3. 请求清单

真实函数名在 Provider 书面资料或试用工具中确认前统一标为 `UNVERIFIED`。

| callId | 类别 | 证券/范围 | 预期字段 | 成功条件 |
|---|---|---|---|---|
| TB-PROBE-001 | raw daily | `600000.SH`, T1—T2 | stable security ID、trade date、OHLC、volume、amount、turnover、unit/null/zero、published/update/revision | 精确两日或明确停牌语义，字段类型稳定 |
| TB-PROBE-002 | raw daily | `000001.SZ`, T1—T2 | 同上 | 同上 |
| TB-PROBE-003 | adjustment factor | `600000.SH`, T1—T2 | stable factor identity、factor date、factor、factorType、revision/published/update | 每个有效 raw 日存在同日精确正数因子 |
| TB-PROBE-004 | adjustment factor | `000001.SZ`, T1—T2 | 同上 | 同上 |
| TB-PROBE-005 | trading calendar | SSE, T1—T2 | exchange identity、calendar date、isOpen、session、revision/published/update | SSE 身份明确且日期精确 |
| TB-PROBE-006 | trading calendar | SZSE, T1—T2 | 同上 | SZSE 身份明确且不得与 SSE 混用 |
| TB-PROBE-007 | corporate action | `600000.SH`, 最小合法窗口 | event ID/type、announcement/publish/effective/revision、cash/stock/rights/split fields | 可解释因子变化或明确无事件，身份稳定 |
| TB-PROBE-008 | corporate action | `000001.SZ`, 最小合法窗口 | 同上 | 同上 |
| TB-PROBE-009 | security identity | 两证券一次批量 | Provider code、exchange、list/delist/status、identity lifecycle | raw/factor/action 可稳定映射且不跨证券 |
| TB-PROBE-010 | 稳定性复取 | 从 001—009 中选一条不变响应 | response metadata、request ID、revision/snapshot/published/update、body Hash | 相同内容下标识稳定；若无版本字段则明确记录 `NOT_SUPPORTED` |

不允许为了补失败而新增第 11 次请求；没有明确批量能力时必须缩小请求，不拆成无界调用。

## 4. 结果分类

### SUCCESS

- 10 次以内完成；
- 四类事实、各自稳定 identity、字段单位/null/zero 和错误语义可映射；
- raw 与 factor 满足同日 `DAILY_EXACT`；
- SSE/SZSE calendar 不混用；
- action 能解释 factor 变化或明确无事件；
- 响应证据与许可边界可写入 F1 合同。

### PARTIAL

- 技术值可取但某类事实、identity、单位或 metadata 不完整；
- 没有 Provider revision，但书面允许从首次捕获建立前向 `SYSTEM_KNOWLEDGE_PIT`；
- 仍不得把缺口由另一 Provider 补成同源 lineage；
- 必须返回具体 finding，不能自动扩大探针。

### BLOCKED

- 认证、验证码、反爬、企业资质或费用超出已批准边界；
- 本地保存、回测或 Agent 权利被拒绝；
- raw/factor/calendar/action 不能形成单 Provider 闭环；
- 因子不是 `DAILY_EXACT` 且没有另行冻结的覆盖契约；
- 需要全市场调用、超过预算、无界重试或正常业务库写入；
- 响应含无法安全脱敏的凭据/个人信息；
- Provider 文档与响应结构不一致且无法书面解释。

## 5. 响应隔离与清理

1. 原始响应只进入操作系统临时隔离目录，不进入仓库；
2. 文件名不含 Token、用户名、账号或完整认证 URL；
3. 先计算 SHA-256，再递归删除 Authorization、Cookie、token、session、password、username、account、本机路径和个人信息；
4. 只在书面许可允许时保存脱敏固定夹具；否则只保存不含业务值的字段/类型/统计/Hash 摘要；
5. finally 精确删除原始响应并记录残留数；
6. 试用结束按书面条款删除或保留，形成清理清单；
7. 不写正常业务库，不执行 V13 public migrate，不启动应用 scheduler；
8. 不创建 Day 002、Agent 任务、Shadow 或交易记录。

## 6. B1 已完成的 Tushare 权限子集

B1 使用相同两只证券，将日期固定为 `20250102`、`20250103`，日历范围固定为 `20250101`—`20250105`。十项请求为两次 `stock_basic`、两次 `trade_cal`、两次 `daily`、两次 `adj_factor` 和两次 `dividend`；全部 `PASS`，无重试、权限错误或网络错误。

本次是用户购买权限后专项授权的最小技术权限检查，但执行前没有取得：

1. Provider 书面允许本次最小自动 API 探针；
2. Provider 书面确认临时响应、Hash、摘要和夹具的保存/删除范围。

这只表示完整合同法律/许可前置未满足，不对本次调用作合法或违法判断。技术结果保留，不需要也不得重新执行 10 次请求；后续扩大调用前仍需独立授权和书面许可处理。

该子集已经验证：

- 2000 积分核心接口技术权限；
- raw/factor/calendar/普通证券身份/dividend 公开字段；
- 两证券、两交易日 `DAILY_EXACT` 最小样例。

该子集没有验证：

- 稳定复取；
- revision/snapshot/published/update/历史旧版本；
- 公司行动完整覆盖和稳定事件 ID；
- factor/action 解释关系；
- 永久证券身份；
- 本地保存、回测、Agent、备份或服务到期留存许可。

## 7. 当前状态

```text
PROBE_EXECUTION_DATE=2026-07-30
PROBE_EXECUTION_TIME=UNKNOWN
TUSHARE_2000_PERMISSION_PROBE=PASS
TUSHARE_PROVIDER_REAL_BUSINESS_CALL_COUNT=10
TUSHARE_RETRY_COUNT=0
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
IFIND_TRIAL_PROBE_STATUS=NOT_EXECUTED
IFIND_REAL_CALL_COUNT=0
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```
