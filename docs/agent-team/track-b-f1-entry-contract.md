# Track B F1 准入合同

## 1. 当前判定

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
BLOCKED_TECHNICAL_EVIDENCE
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=VERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=VERIFIED
WRITTEN_AUTOMATED_API_UPDATE_PERMISSION=VERIFIED
WRITTEN_TECHNICAL_AUDIT_METADATA_RETENTION_PERMISSION=VERIFIED
POST_EXPIRY_DATA_RETENTION_PERMISSION=VERIFIED
PERSONAL_2000_POINT_ACCOUNT_SCOPE_PERMISSION=VERIFIED
WRITTEN_PERMISSION_GATE=PASS
TECHNICAL_EVIDENCE_GATE=BLOCKED
USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED
F1_LIMITED_PERSONAL_USE_IMPLEMENTATION=APPROVED_BY_USER
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
FULL_TECHNICAL_CONTRACT_READY=false
REDUCED_RESEARCH_CONTRACT_READY=true
QFQ_FORMULA_QUALIFICATION=VERIFIED
QFQ_OPERATIONAL_RUNTIME_QUALIFICATION=PARTIAL
QFQ_REDUCED_RESEARCH_RUNTIME_QUALIFICATION=VERIFIED
QFQ_FULL_LINEAGE_RUNTIME_QUALIFICATION=PARTIAL
REDUCED_RESEARCH_RUNTIME_READY=false
REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
OFFICIAL_ENDPOINT_RATE_LIMITS=PARTIAL_CONFLICT_IDENTIFIED
ENDPOINT_SPECIFIC_RATE_LIMIT_ENFORCED=true
CONSERVATIVE_ENDPOINT_MINIMUM_POLICY_ENFORCED=true
```

主要路线的具体 Provider 是 Tushare Pro。用户已开通 2000 积分且 `2026-07-30`
B1 技术权限探针通过，精确执行时刻为 `PROBE_EXECUTION_TIME=UNKNOWN`。同日 Tushare
官方企业微信先书面确认“可以用来当量化数据来源”。`2026-07-31T11:07:00+08:00`
又收到用户提供的 Tushare 官方七项逐条精确脱敏转录，明确允许个人 2000 积分账号本地
数据库保存、策略回测/历史回放、本地 AI/Agent、程序自动调用/定时更新、字段结构/Hash/
摘要/错误日志留存及持续本地保存。用户证明来源为官方回复；仓库不保存原件，Codex 未
查看截图，也未独立认证来源。再分发、商业数据服务和 Token/账号共享仍为 `NOT_GRANTED`。

Tushare Pro 当前资格仍固定为 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`，稳定证券 ID
固定为 `PARTIAL`。B1 已把 raw、factor、SSE/SZSE calendar、普通证券身份、dividend
字段和两证券两日 `DAILY_EXACT` 最小样例验证为 `VERIFIED`；F1A 只允许 raw、factor 和
calendar 以 `RESEARCH_ONLY/SYSTEM_KNOWLEDGE_PIT/formalEligible=false` 进入随机隔离
Schema。stock_basic 只作为普通身份 DTO，dividend 只作为部分证据 DTO，不进入完整
公司行动。F1B 官方技术合同复核把该路线明确判为 `REDUCED_RESEARCH_ONLY`：永久
instrument identity、完整 action、历史版本和 Provider PIT 均未确认。

完整 Track B 证据探针状态仍固定为 `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`。执行前没有取得 Provider 对最小自动 API 探针和响应留存/删除范围的书面答复，因此 `TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`、`WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED`、`WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED`。该历史状态不判定本次调用合法或违法，也不支持把 B1 追认为完整证据探针；F1A 由后续 `TS-WP-001` 的量化数据来源用途证据和用户有界个人实现授权共同支持。后续扩大调用仍需用户专项授权，并在总表、Endpoint 页面存在多个上限时采用最保守的较小值。

完整探针前置的历史状态与后续许可证据必须分开：`TS-WP-002` 解决当前个人研究用途的
书面许可门，但不回溯宣称 B1 完整探针在执行前满足全部法律前置，也不把 B1 追认为
完整证据探针。当前许可同样不授权原始数据再分发、商业数据服务或 Token/账号共享。

## 2. F1 READY 的全部条件

| 门禁 | READY 证据 | 当前状态 | 当前 finding |
|---|---|---|---|
| 个人用途 | 官方书面许可适用于个人非商业研究 | PASS_RESTRICTED | TS-WP-001/002；只限用户本人 2000 积分账号，不转售、不分发、不共享账号 |
| 本地持久化 | 明确允许个人账号数据本地保存及技术审计元数据留存 | PASS_RESTRICTED | TS-WP-002 明确允许本地数据库保存及字段结构、Hash、摘要和错误日志留存；不授权分发 |
| 历史回放与回测 | 明确允许策略回测/历史回放 | PASS_RESTRICTED | TS-WP-002 逐项明确允许 |
| 内部 Agent | 明确允许本地 AI/Agent 分析 | PASS_RESTRICTED | TS-WP-002 逐项明确允许 |
| 自动更新 | 明确允许程序自动调用/定时更新 | PASS_RESTRICTED | TS-WP-002 逐项明确允许；运行仍受代码门禁、限流和阶段授权 |
| 终止后数据 | 明确允许持续本地保存 | PASS_RESTRICTED | TS-WP-002 原文“可以一直保存到本地”；不扩张为再分发或商业服务 |
| 同 Provider raw | 正式字段、单位、精度、null/0 语义和最小响应样例 | PARTIAL | F1 READY 仍缺全历史 null/0 与长期稳定；F1B 缩减技术模型的 raw 维度为 `VERIFIED` |
| 同 Provider factor | `ts_code + trade_date` 精确自然键、正数因子、日覆盖和单位 | PARTIAL | F1 READY 仍缺全历史覆盖和修订；F1B 缩减技术模型的 factor 维度为 `VERIFIED` |
| 同 Provider calendar | SSE/SZSE 稳定身份与精确日期 | PARTIAL | F1 READY 仍缺临时休市修订和旧版本；F1B 缩减技术模型的 calendar 维度为 `VERIFIED` |
| 同 Provider action | 事件自然键、类型、公告/生效/修订时间及覆盖清单 | BLOCKED | B1 证明 `dividend` 可调用；配股、拆并股、更正/撤回、稳定 ID 和 factor 解释关系未闭合 |
| 稳定证券身份 | `ts_code` 生命周期、换码/迁板/重新上市/退市及历史映射 | PARTIAL | B1 验证普通字段；永久 identity 与生命周期仍未获官方保证或样例 |
| 前向 PIT | 允许重复捕获并以真实首次接收建立 `SYSTEM_KNOWLEDGE_PIT` | PARTIAL | F1A 可在个人用途隔离范围建立 SYSTEM_KNOWLEDGE 链；无历史 revision、完整 action 和到期留存结论，禁止升级 Provider PIT |
| 成本 | 套餐、费用和所需接口范围明确，用户明确批准 | PASS | 用户已开通 2000 积分，B1 十项探针证明权限生效；不记录支付隐私 |
| 实现范围 | Adapter、DTO 映射、调用预算、错误/限流、数据清理、V13 迁移边界冻结 | F1A_ACCEPTED_AND_MERGED | 五 Endpoint、显式 MANUAL_BOUNDED、10 次共享预算、分钟/每日限额、随机 Schema 和受控 10 调用已通过实际 Git 最终复验并纯 fast-forward 合入；dividend 仍不升级完整 action |
| QFQ 运行链 | 公式与权威引擎 lineage 门禁兼容，并有受控缩减运行入口 | PARTIAL | F1C 隔离手工公式入口 `VERIFIED`；现有 `QfqAsOfEngine` 在 factor 变化时仍要求公司行动 lineage，完整运行链继续阻断 |
| Endpoint 频次 | 每个 Endpoint 使用所有适用官方上限中的保守较小值 | PASS_LIMITED_PROCESS | F1C 已实现 `stock_basic=45`、其余四项 180 次/分钟的保守安全值、全局 180 与每 Endpoint 每日 90000 原子计数；跨进程协调仍为 false |

只有上述所有条件均通过，才能把：

```text
F1_ENTRY_READINESS=READY
```

写入后续独立治理提交。任何单项通过都不得覆盖其他阻断。

## 3. 当前完整 F1 的单一粗粒度阻断

个人 2000 积分账号的量化数据来源、本地保存、策略回测/历史回放、本地 AI/Agent、
程序自动调用/定时更新、技术审计元数据留存及持续本地保存均已有逐项书面回复，因此
当前 `BLOCKED_WRITTEN_PERMISSION` 已解决。原始数据再分发、商业数据服务和 Token/
账号共享仍未获授权。

当前完整 F1 的阻断精确为：

1. `BLOCKED_TECHNICAL_EVIDENCE`：
   - 公司行动是否覆盖配股、拆并股和更正/撤回；
   - 稳定 action ID 与 factor 变化的解释关系；
   - revision/snapshot/published/update/旧版本是否确实不可用；
   - 证券身份生命周期；
   - raw/factor/calendar/action 的完整字段资格、null/0、长期稳定和全历史覆盖。

`BLOCKED_COST_APPROVAL` 已解除：用户已开通 2000 积分，技术权限已验证生效。

F1B 已把技术阻断从“是否存在可用路线”收敛为“完整合同仍不成立”。
F1C 随后只在随机隔离 Schema 实现手工三请求运行入口和 Endpoint 级进程内限流：
`REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true`，但历史含混的
`REDUCED_RESEARCH_RUNTIME_READY=false`，且
`REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false`。现有权威 QFQ 引擎的公司行动
lineage 门禁仍在；完整 F1 仍缺公司行动 lineage、Provider revision/旧版本、永久证券
身份和全历史 `DAILY_EXACT`，所以 `BLOCKED_TECHNICAL_EVIDENCE` 不解除。

因此当前合法判定是：

```text
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
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

### 4.1 缩减研究合同与完整 F1 的隔离

```text
TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY
TUSHARE_REDUCED_RESEARCH_CONTRACT=READY
QFQ_FORMULA_QUALIFICATION=VERIFIED
QFQ_OPERATIONAL_RUNTIME_QUALIFICATION=PARTIAL
QFQ_REDUCED_RESEARCH_RUNTIME_QUALIFICATION=VERIFIED
QFQ_FULL_LINEAGE_RUNTIME_QUALIFICATION=PARTIAL
REDUCED_RESEARCH_RUNTIME_READY=false
REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

F1C 已实现一个严格更窄的随机隔离运行入口：只允许单证券、最多两自然日、
`daily/adj_factor/trade_cal` 精确三次、零重试，结束日开市 raw/factor 为锚点的研究级
QFQ 只在内存返回，raw/factor/calendar 才能进入随机 Schema。`stock_basic` 与
`dividend` 仍只是 F1A 参考 DTO，不在 F1C 运行入口中。该入口不允许完整公司行动、
Provider PIT、历史 revision、永久证券身份、跨 Provider QFQ、正常业务库、scheduler、
Shadow、Agent、回测、全市场采集或交易。

## 5. F1A 已冻结技术范围

1. Gateway 固定五 Endpoint；`TushareMarketFactProvider` 只把 raw/factor/calendar 映射为
   V13 事实，stock_basic/dividend 保持普通身份/部分证据；
2. HTTP 只使用官方 HTTPS Host；
3. 官方总表为 200 次/分钟、每 API 100000 次/日；接口页另列
   `stock_basic=50`、`daily=500` 次/分钟。存在多个适用限制时必须取最保守较小值；
4. F1C 在单进程中实现全局 180 次/分钟、`stock_basic=45`、其余四 Endpoint
   `180` 次/分钟及每 Endpoint 90000 次/日的原子安全预算；不声明跨进程 Token 协调；
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
    `providerWrittenPermissionComplete=true`、`writtenPermissionGate=PASS` 与
    `technicalEvidenceGate=BLOCKED`；书面许可完整不得被误读为生产运行就绪；
12. partial Provider 响应不写事实观察；
13. 公司行动、Provider revision 和永久证券身份不实现、不伪造。

### 5.1 F1C 随机隔离运行范围

1. 唯一类型化授权固定 `ISOLATED_MANUAL`、单证券、最多两自然日、三 Endpoint、
   三请求和零重试；
2. Provider 前与持久化前都要求精确
   `f1c_tushare_research_<32位十六进制随机后缀>`、无 public 回退和完整 V1—V13；
3. 公式级 QFQ 只复用 `QfqPriceMath`，结果固定为
   `REDUCED_RESEARCH_FORMULA_ONLY` 且不写数据库；
4. `QfqAsOfEngine` 的完整 lineage/cutoff 门禁不变；
5. `ENDPOINT_SPECIFIC_RATE_LIMIT_ENFORCED=true` 不表示官方频次证据冲突已消失，也不
   表示多进程协调完成；
6. F1C 只把随机隔离手工运行设为 READY，生产、正常业务库、scheduler、Agent、回测、
   Shadow、F2B/F3 和交易继续不就绪。

### 5.2 F1E 专用本地研究实现与完整 F1 的隔离

F1E 在 F1D 书面许可门 PASS 后建立类型化准入，但没有解除完整技术证据阻断：

```text
REDUCED_RESEARCH_ROUTE_DECISION=DEDICATED_LOCAL_RESEARCH_PATH
REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true
REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true
REDUCED_RESEARCH_OPERATIONAL_READY=false
REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false
NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
```

专用入口只接受本机 `stock_quant_research` 数据库与用户、唯一
`tushare_research` search path 和完整 V1—V13。手工命令只允许精确一个自然日、
1—3 只 SSE/SZSE 证券；每只证券调用 `daily/adj_factor/trade_cal` 三次，一个批次共用
同一会话并精确消耗 3/6/9 次，零重试。全部响应验证完成后才能在一个事务保存
raw/factor/calendar；任一失败整体回滚。

F1E 的公式级 QFQ 只在内存返回，不进入完整 QFQ 或公司行动表。它不证明完整公司
行动、稳定 action ID、factor/action 解释关系、Provider revision/旧版本、永久证券
身份、全历史 `DAILY_EXACT` 或完整 lineage，因此当前唯一粗粒度 blocker 继续是
`BLOCKED_TECHNICAL_EVIDENCE`。运行验收尚未接受，不得把 implementation/acceptance
ready 改写为 operational ready。

### 5.3 F1F-A 受控验收机制准备

F1F-A 把固定的 `CONTROLLED_ACCEPTANCE_NOT_RUN` 改为类型化、默认拒绝的证据模型，
可以表达 `NOT_RUN/CANDIDATE/PASSED/FAILED/STALE/INCOMPATIBLE_BASELINE`。当前默认仍为
`NOT_RUN`；离线 Fake/Stub 成功只能生成 `CANDIDATE`，没有公开 `PASSED` 构造入口。

未来 F1F-B 的一次性授权必须精确绑定验收ID、代码基线、Tushare、一个证券、一个已知
开市日、`daily/adj_factor/trade_cal`、每项一次、总计三次、零重试、专用数据库/用户、
`tushare_research`和V13。基线/范围/有效期必须在数据库前验证；数据库身份必须在Provider
前验证，F1E捕获事务内前后守卫和全部响应写前验证继续生效。证据不得保存Token、密码、
JDBC URL或完整市场响应。

F1F-A 当前只证明同一授权对象的单 JVM CAS 防重，不证明同 ID 重建、JVM 重启或跨进程唯一
消费；当前基线只来自配置声明，不是构建产物证明；敏感输出为 `NOT_ATTESTED`；`observedAt`
虽与捕获调用共用，但未做数据库回读。F1F-B 必须在任何真实请求前补持久化 acceptance ID
唯一预占，在最终审查且不再修改的提交上验证构建产物完整哈希，并补日志/输出审计和数据库
first-observed/known-at 回读。验收后若提交哈希变化，现有精确相等规则必须判
`INCOMPATIBLE_BASELINE`；不得通过配置声明兼容。

即使未来受控验收通过，也只能独立讨论 `REDUCED_RESEARCH_OPERATIONAL_READY=true`；
不得改变完整F1十项技术阻断、生产/正常业务库、scheduler、Agent、回测、Shadow、F2B/F3、
交易或四项正式门禁。本阶段真实Provider调用为0，F1F-B尚未授权。

### 5.4 F1F-B1 可信执行机制

F1F-B1 补齐持久化唯一 acceptance ID、不可逆状态机、可信构建产物证明、实际输出审计、提交后数据库 typed fact 回读及内部资格重验。独立 V14 使用独立 location 和独立治理历史，baseline 13 后由受控验收数据库守卫显式加载；默认主历史仍止于 V13，合并 location 的误扫描由 V14 脚本内不变量在 DDL 留存前拒绝。`RUNNING` 必须在 Provider 前独立提交，失败尝试数按共享 limiter 总计数差保存；恢复只封存已预占执行，不自动重试。

构建证明必须绑定当前 executor JAR、MANIFEST、相邻 sidecar、冻结集成分支和本地/远程集成完整 SHA；TEST proof 永远无治理资格。输出隔离先于敏感材料读取，并捕获边界内 stdout/stderr、当前 Logback 拓扑和异常链。F1E 捕获事务提交后，回读必须在无活动事务中重新验证专用数据库身份，并同时核对 V13 envelope 与 raw/factor/calendar typed facts；捕获 PID 与回读 PID 分别记录。

上述 SHA-256 与数据库 digest 是完整性核对而不是特权管理员不可伪造的外部签名；输出审计也不覆盖任意外部文件、未桥接日志框架或边界结束后的脱离线程。F1F-B2 必须使用最小专用进程、最小权限账号、输出白名单和无未等待后台任务。TEST 来源不能投影真实 PASSED；F1F-B2 未运行前继续保持 `CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN` 与 `REDUCED_RESEARCH_OPERATIONAL_READY=false`。

完整 F1 的公司行动、稳定事件身份、factor/action、Provider revision/历史版本、永久证券身份、全历史 DAILY_EXACT 与完整 QFQ lineage 等技术阻断不因该运行机制完成而解除；`F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`。

### 5.5 F1F-B2 专用启动与构建证明边界

B2-PRE 在 B1 合入后判定 `NOT_READY`，因为可信机制尚无可正式使用的一次性最小进程。后续
B2-RUNNER 必须采用非 Spring 专用 `main`、手工组件白名单、安全控制台秘密通道、非池化专用
DataSource 和全流程输出审计；审计安装必须先于任何构建/授权解析、秘密读取、数据库或 Provider
客户端初始化。进程完成一次执行后必须关闭资源并主动退出，不能遗留非 daemon 线程。

治理 V14 固定 `baselineOnMigrate(false)`。构建证明、一次性授权、专用数据库身份、严格 search
path 与完整 V1—V13 主历史先通过，随后才允许在治理 history 不存在时显式 baseline 13 并迁移
V14；错误 public、错误数据库/用户或不完整历史必须保持治理 DDL 调用为 0。

构建证明分为默认 `PREPARATION_ONLY` 与显式 `CONTROLLED_BUILD_ARTIFACT`。两种模式都只能使用
Maven Wrapper，并绑定本地/远程 Git、分支、Java、Maven、模块、executor、rule、时间、MANIFEST、
sidecar 和 JAR SHA-256。PREPARATION 永远不具治理资格。B2-RUNNER 合入后仍须基于新的冻结集成
SHA 重新执行完整 B2-PRE；不得沿用旧 JAR、旧 sidecar、旧授权草稿或 `e3777602...`。

## 6. 继续禁止

- F1A 验收完成后不追加无授权 Tushare 调用；
- 不申请、激活或调用 iFinD；
- 不迁移正常业务库 V13；
- 不写业务数据库；
- 不跨 Provider 拼接 QFQ；
- 不启动 F2B、F3、Shadow、Day 002 或 scheduler；
- 不开始 3A-R3B-1 或 3B；
- 不授权投资建议、券商账户、真实交易或自动交易。
