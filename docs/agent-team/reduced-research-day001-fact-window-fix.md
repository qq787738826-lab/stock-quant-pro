# REDUCED-RESEARCH-DAY001 FACT_WINDOW_INCOMPLETE 诊断与修复

## 冻结失败证据

已消费运行 `RRDAY001_20260810T053245Z_405497967D16` 固定在 Git
`ceda6d06f3c10748de93b512e0d5c8c2e1e27ffc`。脱敏结果只证明：

- `daily=1 / adj_factor=0 / trade_cal=0`，重试为 `0`；
- 未生成 capture batch、observation、typed fact、SYSTEM_KNOWLEDGE 或 QFQ；
- `outputAudit.clean=true`；
- `safeFailureCode=TUSHARE_DEDICATED_RESEARCH_FACT_WINDOW_INCOMPLETE`。

旧结果模型没有保存 HTTP 状态、Provider table 的 fields/rows、返回证券集合或返回日期集合，
因此这些历史值均为 `NOT_RECORDED`，不得根据 reason 名称补造。源码不变量能够进一步确定：
`TushareDedicatedResearchFactValidator.validate` 的第一个短路条件
`!response.complete()` 为真，同时 `response.errors()` 非空；随后
`TushareDedicatedResearchBatchService.validateResponse` 把该部分 Provider 响应和其他所有语义失败
统一折叠成了 FACT_WINDOW reason。

## 固定请求链

调用链为：Day001 USER_APPROVED 授权 → `SecuritySelection(600000,SSE)` →
`MarketFactRequest` → `baseSecurityParameters` → `TushareHttpApiGateway` → table DTO 映射 →
目标证券/日期窗口 → `TushareDedicatedResearchFactValidator`。

HTTP 前的 `TushareManualBoundedSession` 对参数逐项 fail-closed，因此本次已进入一次 Provider 尝试
证明请求参数通过了以下冻结检查：

```text
daily.ts_code=600000.SH
daily.start_date=20250103
daily.end_date=20250103
authorization.symbol=600000
authorization.exchange=SSE
authorization.tradeDate=2025-01-03
```

证券后缀、exchange 映射、LocalDate 到 BASIC_ISO_DATE 的转换和单日边界均无偏差。

## 精确代码缺陷与最小修复

旧 DTO 映射对 Provider 返回的每一行先执行目标证券和请求日期断言，再形成目标窗口。
因此合法超集中的任一其他证券或其他日期会先抛映射异常，目标行即使存在也无法进入后续校验；
映射异常又被通用 reason 折叠，丢失了 daily 首因。

修复保持目标约束不变：

- daily、adj_factor、trade_cal 先按完整 Provider identity、exchange 和请求日期范围过滤；
- 只映射过滤后的目标行；错误证券或错误日期本身永远不能成为目标事实；
- 精确目标重复、目标日期格式非法、目标行缺失仍 fail-closed；
- 部分 Provider 响应保留 Gateway safe code 或 Endpoint 专用映射 reason，不再伪装为完整响应的
  FACT_WINDOW 失败；
- FACT_WINDOW 只处理 `complete=true` 后精确三事实窗口仍不成立的情况；
- 请求预算仍为三次、零重试，数据库、事务、readback、SYSTEM_KNOWLEDGE、QFQ 和治理边界不变。

Fake Provider 覆盖目标单行、零行、多日、多证券、裸 `600000` 与 `600000.SH`、日期格式差异、
目标行与额外行共存、目标缺失、顺序变化、三 Endpoint 超集和目标重复。打包 E2E Fake Gateway
也固定返回合法超集，使未修复代码无法通过完整 Day001 E2E。

本修复不创建新 runId 或授权，不读取真实秘密，不调用真实 Provider，不访问永久数据库；
Tushare 累计真实请求保持 `33`，七项治理状态不变。
