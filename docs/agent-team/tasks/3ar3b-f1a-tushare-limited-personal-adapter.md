# 3A-R3B-F1A：Tushare 有限个人用途 Adapter

## 1. 阶段定位

本阶段在 Track B1 已验收合入的基础上，实现一条严格缩小的真实 Tushare Pro
Provider 路线。它只服务用户本人、非商业研究、本地保存、策略回测和内部 Agent
分析，不启动 F2B、F3、Shadow、scheduler、Day 002 或交易。

任务分支：

```text
codex/1.4.0-stage-3ar3b-f1a-tushare-adapter
```

冻结父提交：

```text
d223fdf9ff997ca256f2d0f651c99542e817dfee
```

## 2. 书面许可与用途边界

`2026-07-30`，Tushare 官方企业微信对以下问题作出书面文字回复：

> 问：个人2000积分数据服务用户的本地存储、策略回测和智能体分析都是允许的嘛
>
> 答：可以的

仓库不保存原始截图，只保存上述脱敏转录。没有记录联系人、微信 ID、头像、手机号、
Token、账号或其他个人信息。规范化脱敏转录 SHA-256 为：

```text
f0a778d80b9f01cc100040ce3cb7648553eb9a60015da37fba26967128fece71
```

据此冻结：

```text
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=VERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=VERIFIED
BLOCKED_WRITTEN_PERMISSION=RESOLVED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=READY
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

`BLOCKED_WRITTEN_PERMISSION=RESOLVED` 只适用于上述有限个人用途，不扩大为原始数据
再分发、商业数据服务或服务到期后的永久留存。继续保持：

```text
POST_EXPIRY_DATA_RETENTION_PERMISSION=UNVERIFIED
RAW_DATA_REDISTRIBUTION_PERMISSION=NOT_GRANTED
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
```

用户明确不会转售或向第三方分发原始数据，不共享 Token/账号，也不把数据服务商业化。
本阶段依据的是官方书面许可，不采用“书面许可未通过但用户接受风险”的替代机制。

## 3. 实现范围

真实 Adapter 固定为：

```text
providerCode=TUSHARE_PRO
adapterVersion=TUSHARE_MARKET_FACT_PROVIDER_V1
implementationScope=LIMITED_PERSONAL_RESEARCH_USE
usageQualification=RESEARCH_ONLY
formalEligible=false
revisionQualification=SYSTEM_KNOWLEDGE_ONLY
assuranceLevel=SYSTEM_KNOWLEDGE_PIT
```

仅实现：

1. `daily` → `RAW_DAILY_BAR_OBSERVATION_V2`；
2. `adj_factor` → `ADJUSTMENT_FACTOR_OBSERVATION_V1`；
3. `trade_cal` → `TRADING_CALENDAR_OBSERVATION_V1`。

明确不实现 `CORPORATE_ACTION_OBSERVATION_V1`。`dividend` 的 B1 技术探针不能证明
配股、拆并股、更正、撤回、稳定 action ID 或 factor/action 解释关系，禁止为了凑齐
四类事实而写入不完整公司行动。

来源身份分别固定为：

```text
raw:      TUSHARE:SECURITY:<ts_code>
factor:   TUSHARE:ADJ_FACTOR:<ts_code>
calendar: TUSHARE:TRADE_CAL:<exchange>
```

同一请求只允许一只沪深主板证券、SSE 或 SZSE、最多 366 个自然日。Adapter 不提供
Controller、scheduler 或自动调用入口；F1A 只建立受控 Provider Bean、传输层和既有 V13
捕获服务的合法 FORMAL/RESEARCH_ONLY 接入能力。

## 4. 字段映射

- OHLC：CNY/股，必填，不重算；
- `vol`：Provider 单位“手”，精确乘 100 转为股；
- `amount`：Provider 单位“千元”，精确乘 1000 转为 CNY；
- `volume/amount` 的 null 保持 `MISSING`，明确 0 保持
  `PRESENT_VERIFIED` 的 0，不补零；
- `turnoverRate` 未暴露，固定为 `MISSING`；
- `adj_factor` 为无量纲正数，覆盖模式 `DAILY_EXACT`；
- calendar 使用 Provider 返回的 `exchange/cal_date/is_open`；
- Provider 未公开的 revision、snapshot、publishedAt、updatedAt 和旧版本字段全部为 null，
  不伪造 `PROVIDER_PIT_VERIFIED`。

## 5. Token、HTTPS 和全局限流

Token 只从运行时配置进入内存中的 HTTPS 请求体，不进入 capability、响应 metadata、
日志、异常、数据库元数据或测试 fixture。生产默认：

```text
enabled=false
baseUrl=https://api.tushare.pro
```

限流合同：

```text
TUSHARE_OFFICIAL_RATE_LIMIT_PER_MINUTE=200
TUSHARE_APPLICATION_SAFE_LIMIT_PER_MINUTE=180
NORMAL_MAXIMUM_RATE_LIMIT_RETRIES=2
CONTROLLED_PROBE_MAXIMUM_RETRIES=0
```

`TushareTokenRateLimiter` 是单进程 Spring 单例，所有 Endpoint 和所有调用方必须先经过同一
滑动窗口；达到 180 次预算后等待下一窗口。同步临界区禁止并发绕过，内存快照只记录总调用
次数与逐 Endpoint 次数，不记录 Token。正常运行只对限流错误执行有限重试，默认最多 2 次；
受控验收固定零重试。

## 6. 数据库边界

- 不新增或修改 Flyway；
- 不修改 V13；
- 不迁移正常业务库 public；
- 不回填历史业务数据；
- 随机隔离 Schema 从 V1 完整迁移至 V13；
- FORMAL 捕获使用 `PROVIDER_CAPTURE`，但用途仍为 `RESEARCH_ONLY` 且
  `formalEligible=false`；
- `SYSTEM_KNOWLEDGE_ONLY` 的 `knownAt=firstObservedAt`；
- partial Provider 响应保留审计 batch，但原子地不写事实观察；
- 随机 Schema 精确删除，测试 public 指纹前后不变。

## 7. 仍未解除的技术阻断

当前继续保持：

```text
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

剩余缺口为：

1. 公司行动完整覆盖；
2. 稳定 action ID；
3. factor/action 稳定解释关系；
4. revision/snapshot/published/update 语义；
5. 历史旧版本；
6. 永久证券身份；
7. 全历史 `DAILY_EXACT`。

这些缺口不阻止本阶段真实 Adapter 和有限个人用途隔离联调，但禁止声明
`V13_LINEAGE_VERIFIED`、`PROVIDER_PIT_VERIFIED`、`STABLE_SECURITY_ID_VERIFIED`
或公司行动闭环完整。

## 8. 验收

至少覆盖：

- capability、许可与禁用默认值；
- HTTPS 请求 envelope、超时、结构校验和 Token 脱敏；
- 200/180 限流、跨 Endpoint 共享和并发不可绕过；
- 正常最多 2 次限流重试与受控零重试；
- raw/factor/calendar 映射、单位、null 与明确 0；
- source identity、范围、沪深主板和 unsupported fact 安全门；
- partial/异常响应；
- 一次两证券、三 Endpoint、精确 6 请求的受控真实联调；
- PostgreSQL 16 随机 Schema V1→V13、幂等和 partial 原子失败；
- Provider 中立 V2 与 QFQ 18 个黄金向量兼容；
- quant-core 与 quant-server 回归。

## 9. 任务分支完成状态

- F1A 技术实现与 Codex 本地验证在任务分支完成；
- 待 ChatGPT 基于实际 Git 提交验收；
- 尚未合入；
- Tushare 累计真实业务请求为 16，其中本阶段新增 6；
- iFinD 真实调用数为 0；
- 正常业务库未访问，V13 未执行；
- F2B/F3 未开始；
- Shadow、Day 002、scheduler、3A-R3B-1、3B 和交易均未启动。
