# 3A-R3B-F1A 阶段记录：Tushare 有限个人用途 Adapter

## 1. 结论

本阶段在任务分支
`codex/1.4.0-stage-3ar3b-f1a-tushare-adapter` 完成真实 Tushare Pro
有限个人用途 Adapter、全局限流、V13 随机隔离持久化和一次受控真实联调。

当前状态：

```text
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=VERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=VERIFIED
BLOCKED_WRITTEN_PERMISSION=RESOLVED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=READY
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

本阶段没有把 Tushare 升级为完整 V13 lineage、Provider PIT 或永久证券身份来源。

## 2. 书面证据

证据日期为 `2026-07-30`，来源为 Tushare 官方企业微信书面文字回复。脱敏转录为：

> 问：个人2000积分数据服务用户的本地存储、策略回测和智能体分析都是允许的嘛
>
> 答：可以的

原始截图未提交 Git；联系人、微信 ID、头像、手机号、账号、Token 和其他个人信息均未
记录。脱敏转录登记为 `TS-WP-001`。

许可仅支持个人非商业本地存储、策略回测和内部智能体分析。继续保持：

```text
POST_EXPIRY_DATA_RETENTION_PERMISSION=UNVERIFIED
RAW_DATA_REDISTRIBUTION_PERMISSION=NOT_GRANTED
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
```

## 3. 实现结果

新增：

- `TushareMarketFactProperties`：默认禁用、官方 HTTPS 主机锁定、200/180 限流和最多 2 次
  重试配置；
- `TushareTokenRateLimiter`：跨 Endpoint/调用方共享的并发安全滑动窗口；
- `TushareApiGateway` 与 `TushareHttpApiGateway`：类型化请求、错误、结构校验、Token 脱敏
  和受控零重试；
- `TushareMarketFactProvider`：raw daily、adjustment factor、SSE/SZSE calendar 的
  Provider 中立 DTO 映射；
- F1A 单元、真实 Provider 和 PostgreSQL 随机 Schema 测试。

扩展既有 V13 捕获服务，使 FORMAL Provider 响应可使用 `PROVIDER_CAPTURE`，同时强制从
类型化 licensing 读取 `usageQualification/formalEligible`。TEST/DEMO 旧路径仍固定为
`TEST_DEMO_ONLY/false`。

## 4. 受控真实联调

执行日期：`2026-07-30`。

范围：

- `600000.SH` / SSE；
- `000001.SZ` / SZSE；
- `2025-01-06`—`2025-01-07`；
- 每只证券调用 `daily`、`adj_factor`、`trade_cal`；
- 精确 6 次 Provider 业务请求；
- 重试 0；
- 完整响应、CSV、Token 和市场值 fixture 均未保存。

结果：

```text
TUSHARE_F1A_CONTROLLED_INTEGRATION=PASS
providerCallCount=6
rateLimitRetryCount=0
Failures=0
Errors=0
Skipped=0
```

两只证券的 raw、factor 和对应交易所 calendar 均返回非空且落在固定日期范围。该结果不
补齐公司行动、revision、历史旧版本、永久证券身份或全历史 `DAILY_EXACT`。

Tushare 累计真实业务请求：

```text
B1=10
F1A=6
TOTAL=16
```

## 5. PostgreSQL 随机隔离验证

由于当前进程未继承专用 5432 测试库凭据，本阶段使用操作系统临时目录中的独立
PostgreSQL `16.13` 集群和固定本地测试端口 `55432`。临时集群的 public 仅建立 V1—V12
测试基线；F1A 随机 Schema 从 V1 迁移到 V13。正常业务数据库及其 public 从未迁移。

结果：

```text
AgentStage3AR3BF1ATusharePostgresIntegrationTest
Tests=2
Failures=0
Errors=0
Skipped=0
```

验证：

- FORMAL/PROVIDER_CAPTURE；
- RESEARCH_ONLY；
- SYSTEM_KNOWLEDGE_PIT；
- 三类独立 source identity；
- `knownAt=firstObservedAt`；
- Provider revision/version 字段为空；
- 许可字段写入；
- 连续相同语义幂等；
- partial Provider 响应不写观察；
- 随机 Schema 精确清理；
- 临时 public 指纹前后不变。

测试结束后 55432 监听和 `stock-quant-f1a-pg-*` 临时目录残留均为 0。

## 6. 已执行验证

| 命令/组 | 结果 |
|---|---|
| `mvn -pl quant-server -am -DskipTests test-compile` | SUCCESS |
| F1A + Provider V2 + QFQ 定向组 | 43/0/0/0 |
| Spring 构造器与测试环境回归组 | 26/0/0/0 |
| 受控真实联调 | 1/0/0/0，Skipped=0 |
| PostgreSQL 16.13 随机 Schema | 2/0/0/0，Skipped=0 |
| `mvn -pl quant-core test` | 4/0/0/0 |
| `mvn -pl quant-server -am test` | quant-core 4/0/0/0；quant-server 447/0/0/92；BUILD SUCCESS |

`quant-server` 全量的 92 项跳过均是未提供外部 PostgreSQL、Python、AKShare 或显式
Tushare Live 门时的既有环境门禁；它们不替代本阶段已经单独真实运行且 `Skipped=0` 的
F1A PostgreSQL 与受控 Tushare 联调。全量回归命令显式关闭 Tushare Live、scheduler 和
默认 Flyway 数据源连接，没有新增 Provider 调用，也没有访问正常业务数据库。

## 7. 边界与门禁

继续保持：

```text
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

生产配置默认关闭；没有 Controller、scheduler 或自动入口。正常业务库未访问，V13 未执行；
F2B/F3、Shadow、Day 002、scheduler、3A-R3B-1、3B、真实交易和自动交易均未开始。
iFinD 真实调用数为 0。

## 8. Git 状态

- 父提交：`d223fdf9ff997ca256f2d0f651c99542e817dfee`；
- F1A 实现和 Codex 本地验证已完成；
- 待 ChatGPT 基于实际 Git 提交验收；
- 尚未合入集成分支；
- 不自动开始任何后续阶段。
