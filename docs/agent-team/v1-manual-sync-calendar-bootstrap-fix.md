# V1 手工行情同步日历 bootstrap 修复

## 1. 状态与基线

- 冻结集成基线：`5ee98382474e0d828975771aadd3ec48bc8e6180`
- 任务分支：`codex/1.4.0-v1-manual-sync-calendar-bootstrap-fix`
- 状态：已实现并通过 Codex 本地离线验证，尚待 ChatGPT 基于最终 Git 提交验收及用户批准 merge
- 权威项目状态：[CURRENT_STATE.md](CURRENT_STATE.md)

本修复没有执行正式 Tushare 请求，没有访问正式 PostgreSQL、Windows Credential、
Broker 计划任务或真实交易能力。

## 2. 根因

上一轮手工同步目标解析要求候选日期已经同时存在 SSE 和 SZSE 本地交易日历事实，
但固定维护链的第一步本来就是 `MAINBOARD_TRADE_CAL_FORWARD_INCREMENT`。因此，只要
两所本地日历以相同日期正常停止、但落后于当前可维护边界，手工入口就会先返回
`MANUAL_MARKET_DATA_TARGET_TRADE_DATE_UNAVAILABLE`，Broker 链没有机会补齐日历，
形成自锁。

同时，直接把未知日期猜成交易日会产生第二个问题：日历补齐后周末或节假日会解析为
更早的实际交易日，GET 状态若随之改查另一个确定性 requestId，原 bootstrap 请求就会
在页面上“消失”。

## 3. 修复契约

`ManualMarketDataTargetResolver` 现在返回显式 `Resolution`：

- `coverageEndDate`：稳定自然日覆盖边界；上海时间 `16:25` 前为前一自然日，否则为
  当日；
- `dispatchTargetDate`：实际交给既有 Broker operation 的确定性请求日期；
- `effectiveTargetTradeDate`：已有本地证据可以证明的实际共同开市日；bootstrap 前为
  `null`；
- `calendarBootstrapRequired`：是否需要先通过既有前向日历阶段补齐尾部。

若两所日历已经覆盖 `coverageEndDate`，继续按原行为把最近共同开市日作为
`dispatchTargetDate`。若两所最大日期一致、尾部存在可证明的共同开市日但共同落后，
则把稳定的 `coverageEndDate` 作为请求日期，让 Broker 链先补日历。以下情况仍然在任何
提交前失败关闭：

- 本地日历为空，无法满足既有 forward increment 的种子前提；
- SSE/SZSE 最大日期不一致；
- 从尾部无法连续回溯到共同开市日；
- Repository 返回异常、越界、重复或交易所错配数据。

手工服务每次先查询稳定 `coverageEndDate` 的 Broker 身份，再查询已经确定的实际交易日
身份。这样，日历在任务执行期间从“陈旧”变为“已覆盖”后，进程内轮询和应用重启后的
轮询仍能恢复原 bootstrap 请求，不会重复 POST 或丢失终态。

## 4. API 与页面

现有 `/api/data/maintenance/sync-latest` 和 `/api/data/maintenance/status` 路径不变，
没有新增客户端可控日期或提交模式。既有 `effectiveTargetTradeDate` 字段在 bootstrap
尚未完成时明确为 `null`；最终 Broker 审计给出共同完整交易日后再返回真实日期。

数据中心页面不再把自然日覆盖边界冒充为交易日。bootstrap 期间显示“先补齐本地交易
日历”，得到证据后再显示实际同步目标交易日。自动 scheduler、Broker operation、预算、
重试、正式 Provider 隔离和 DATA_READY 门禁均未改变。

## 5. 本地验证

以下均为 Codex 本地离线证据，不是 GitHub Actions，也不是真实 Provider 闭环：

| 验证 | 结果 |
|---|---:|
| Java 目标解析、服务、Controller、Broker 状态、生产装配与自动维护定向测试 | `49/0/0/0` |
| `quant-core` 离线全量 | `23/0/0/0` |
| `quant-server` 离线回归，命令级排除 `*IntegrationTest/*Postgres*/*CrossLanguage*/*Live*` | `846/0/0/1` |
| 数据中心维护页面测试 | `4/0/0/0` |
| Vue TypeScript 检查与生产构建 | 通过 |

唯一 skip 是既有环境门禁；本轮离线输出确认百炼网络调用为 `0`。没有新增或修改
Flyway，没有数据库结构或数据变化，没有调用正式 Tushare、百炼、券商或交易客户端。
