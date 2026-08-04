# 3A-R3B-RR-DAY001：缩减研究人工单次捕获

## 阶段定位

本阶段提供 `TushareReducedResearchManualRunner` 与
`run-reduced-research-day001.ps1`，用于用户另行签发一次性非敏感授权后，人工执行一个
单证券、单已结束交易日的缩减研究捕获。它不是 F1F-B2 再验收，不调用或写入 F1F-B2
状态机，不是 F2B、F3、scheduler、Agent、Shadow、回测或交易入口。

## 冻结运行合同

- Provider 授权名固定为 `TUSHARE`；运行时继续复用已验收的 Tushare Gateway。
- 证券固定优先 `600000 / SSE`；Endpoint 顺序及预算固定为
  `daily=1`、`adj_factor=1`、`trade_cal=1`，总请求 3，重试 0，redirects 为 `NEVER`。
- 真实运行只允许
  `127.0.0.1:38432/stock_quant_research`、用户 `stock_quant_research`、Schema/search path
  `tushare_research`；禁止正常业务库和 `public`。
- 授权绑定 runId、完整 Git SHA、JAR SHA-256、相邻构建证明、证券、日期、Day 001
  模式、全部预算、数据库身份、签发与不超过 30 分钟的过期时间，以及
  `executionSource=REDUCED_RESEARCH_MANUAL_DAY001`。授权文件不得包含 Token、密码、JDBC
  URL或其他 secret-like 字段。
- 构建、授权、有效期和数据库身份在 Provider 前核验；授权通过非敏感 `.consumed`
  标记原子消费。既有标记、既有结果文件或任一字段错配均安全拒绝，不自动重试或补跑。
- 捕获直接复用 `TushareDedicatedResearchBatchService`、显式专用事务、三事实写前验证、
  typed fact/SYSTEM_KNOWLEDGE 提交后回读、formula-only QFQ 和原输出审计；不新增 Flyway、
  表、Controller、Spring Bean、ApplicationRunner、CommandLineRunner 或 scheduler。

## 候选与模式

永久专用数据库的无交互只读连接因没有可用凭据而在认证前安全失败；没有读取密码、没有
写库，也没有 Provider 探测。仓库已验收证据明确覆盖 `600000 / SSE / 2025-01-03`，且最终
F1F-B2 对同一语义内容证明了合法链尾复用。因此首个候选冻结为：

```text
DAY001_SYMBOL=600000
DAY001_EXCHANGE=SSE
DAY001_TRADE_DATE=2025-01-03
DAY001_MODE=IDEMPOTENCY_VERIFICATION
```

若用户批准真实运行，结果必须是三次调用、零重试、0 条新增 observation、3 条既有链尾、
typed fact/SYSTEM_KNOWLEDGE 回读通过、1 条仅内存 formula-only QFQ 摘要及 clean 输出审计；
否则终态非 `SUCCEEDED`，且不得自动重跑。

## 入口与结果

正式构建仍使用已验收的隔离 Git archive、MANIFEST、sidecar 与 JAR SHA-256 证明机制。
授权由用户另行生成和批准；本阶段不生成真实授权。人工入口为：

```powershell
.\quant-server\scripts\run-reduced-research-day001.ps1 `
  -AuthorizationFile <user-approved-authorization.properties> `
  -ResultFile <new-redacted-result.json> `
  -ArtifactPath <verified-runner.jar>
```

`STOCK-QUANT-LOCAL-AUTOMATION` 合入后，正式本地模式默认从 Windows Credential Manager
的两个固定 Target 读取秘密，无需重复输入；Console 只允许显式应急选择且不自动降级。
统一入口、生命周期和仓库权限见
[Windows 秘密托管与 Codex 全自动执行](stock-quant-local-automation.md)。

脱敏结果只允许
`SUCCEEDED / FAILED_PRE_PROVIDER / FAILED_PROVIDER / FAILED_VALIDATION /
FAILED_PERSISTENCE / FAILED_OUTPUT_AUDIT / INTERRUPTED`，并记录 run/scope、三 Endpoint
计数、重试、batch ID、新增/幂等数量、两类回读、QFQ 摘要、审计及开始/结束时间。
结果固定声明没有生成 F1F `PASSED`、没有修改 operational 投影且所有禁止阶段均未启动。

## 测试边界

- Day 001 授权、Runner、结果与共享信任机制只跑定向测试。
- 打包 JAR E2E 只使用 Fake Provider 与全新临时 PostgreSQL；先验证新日期写入，再以新
  测试授权验证同日幂等链尾，并验证第三次 Provider 失败零写、前置拒绝和无 V14 治理表。
- F1E 临时 PostgreSQL 定向回归继续证明三事实单事务和第三事实失败整批回滚。
- QFQ 权威 19 项、Java clean compile、PowerShell AST 及 `git diff --check` 必须通过。
- 全部测试新增真实 Provider 调用为 0，不读取真实 Token，不访问或修改 38432 永久库。

## 治理边界

```text
CONTROLLED_ACCEPTANCE_STATUS=PASSED
REDUCED_RESEARCH_OPERATIONAL_READY=true
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

Day 001 成功只增加一个合法缩减研究观察批次；不会自动开放 F2B、Day 002、scheduler、
Agent、Shadow、回测、F3、3A-R3B-1、3B 或交易。路线图中的下一正式阶段名称仍为
`3A-R3B-F2B：选定 Provider 支持的真实产品闭环（未开始）`，必须另行授权。
