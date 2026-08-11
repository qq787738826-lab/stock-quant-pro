# M1_RESEARCH_DATA_READY 阶段证据

## 结论

- 阶段状态：`M1_RESEARCH_DATA_READY=PASS`
- 长期任务分支：`codex/1.4.0-m1-research-data-ready`
- 冻结集成基线：`c9303713cfe3e0aabd05d7e526b4223f844aedeb`
- 正式运行实现 HEAD：`6555c8a49ce986275f9a096fdc942eaef8fc2e2e`
- 正式运行 JAR SHA-256：`ea2dca47b7529a9fa6e8d1291d8fa0e195a36934b1e26173ab25ea0acdd8dbfc`
- 数据库：`127.0.0.1:38432/stock_quant_research`，用户/Schema 为
  `stock_quant_research/tushare_research`
- Provider 真实调用：阶段新增 21，历史累计从 34 增至 55；阶段上限 30、累计上限 64，
  剩余 9 次未使用

最终状态同步提交只更新证据与当前状态；正式 JAR、授权和脱敏结果仍按运行时实现 HEAD 与
JAR SHA-256 严格绑定，未改写或复用。

## 交付能力

- 固定 Resident Broker 继续作为唯一真实秘密与 Provider 边界；Codex 只写无秘密请求并读取
  heartbeat 和脱敏结果。
- M1 手工 Runner 支持有界多证券、多日期 `CAPTURE` 与 `IDEMPOTENCY_VERIFICATION`。
- 每只证券按 `daily → adj_factor → trade_cal` 固定顺序各调用一次；精确预算、零重试、
  `redirects=NEVER`。
- 三类事实按证券单事务持久化，随后执行 typed fact、SYSTEM_KNOWLEDGE、formula-only QFQ、
  基础数据质量、未来数据边界和 Research Dataset 只读校验。
- 未新增 Flyway 或治理表，未改变完整 F1 资格、F1F-B2 PASSED 记录或七项治理状态。

## 真实Provider证据

更新后的 Credential 先通过一次最小 `daily` 请求验证：HTTP 200、Tushare `body.code=0`、目标行存在、
重试 0、数据库连接和写入均为 0。随后执行三次正式 M1 运行：

| 运行 | 模式与窗口 | 调用/重试 | batch | 新增/链尾命中 | 结果 |
|---|---|---:|---|---:|---|
| `M1_20260811T041239Z_87622D79BA83` | CAPTURE，2025-01-02..2025-01-06 | 6/0 | 5, 6 | 19/3 | SUCCEEDED |
| `M1_20260811T041351Z_C97131BC57DD` | CAPTURE，2025-01-07..2025-01-10 | 6/0 | 7, 8 | 24/0 | SUCCEEDED |
| `M1_20260811T041501Z_43FBA67A8AEC` | IDEMPOTENCY_VERIFICATION，2025-01-02..2025-01-10 | 6/0 | 9, 10 | 0/46 | SUCCEEDED |

每次 6 个请求均为两只证券的 `daily=2 / adj_factor=2 / trade_cal=2`。三次成功运行的授权均已
`CONSUMED`，Broker request 均已原子领取并形成 terminal result；最终无 pending、processing 或
claimed 请求。

终态链为：无秘密 request 原子发布 → Resident Broker processing claim → 固定 Runner 执行 →
request processed marker → Broker `COMPLETED/SUCCEEDED` result；对应 USER_APPROVED 授权由可消费态
单向进入 `CONSUMED`，Runner 结果单向终结为 `SUCCEEDED`。

阶段前两次真实 `daily` 请求被 Tushare API 以 HTTP 2xx + JSON `body.code=40101` 拒绝，均为一次性
runId、重试 0、数据库写入 0。错误模型已把 HTTP 状态、Provider code、Gateway reason 和 Credential
读取状态分离；用户在本机安全更新 Token 后，最小验证和后续采集均成功。这两次请求计入阶段预算。

## 数据覆盖与质量

- 证券：`600000/SSE`、`000001/SZSE`
- 日期窗口：`2025-01-02` 至 `2025-01-10`
- 日线：14；复权因子：14；交易日历：18
- 开市证券日：14；休市证券日：4；QFQ bar：14
- 首次加增量共新增 observation：43
- 全窗口重复运行：新增 0，合法命中既有链尾 46
- typed fact readback：PASS
- SYSTEM_KNOWLEDGE readback：PASS
- formula-only QFQ：PASS
- 数据质量：PASS
- 无未来数据泄漏：PASS
- Research Dataset `M1_RESEARCH_DATASET_V1`：M2 read smoke PASS
- output audit：三次均 clean，命中 0

## 离线与构建验证

- 最终正式 JAR、manifest、Start-Class、sidecar 和 build proof：PASS；Start-Class 精确为
  `TushareM1ResearchDataManualRunner`
- 打包 Fake Provider + 临时 PostgreSQL：`TUSHARE_M1_PACKAGED_FAKE_E2E=PASS`
- Fake Provider 调用：24；真实 Provider 调用：0
- 覆盖首次写入、增量、全窗口幂等和失败回滚；临时 PostgreSQL：PASS；临时残留：0
- M1 授权、窗口、Provider/Gateway、输出审计、Broker M1 协议和 Token 最小验证定向测试：PASS
- QFQ 权威 19 项：`19/0/0/0`

## 终态与边界

- `CONTROLLED_ACCEPTANCE_STATUS=PASSED`
- `REDUCED_RESEARCH_OPERATIONAL_READY=true`
- `F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`
- `FREE_PRODUCT_PREVIEW_GATE=PASS`
- `FREE_PROVIDER_VALIDATION_GATE=BLOCKED`
- `PAID_PROVIDER_UPGRADE_DECISION=PENDING`
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`

M1 已满足 M2 策略引擎读取缩减研究数据的技术前置条件，但不自动批准或启动 M2。已知边界仍为：
只覆盖 `daily/adj_factor/trade_cal`，QFQ 仅为公式级，不声明完整公司行动 lineage、Provider PIT、
revision 历史或稳定永久证券身份。F2B、业务 scheduler、Agent、Shadow、回测、交易和真实订单均未启动。
