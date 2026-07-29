# 免费 Provider F0 探针调用矩阵

## 1. 冻结预算

```text
BaoStock provider logical calls <= 10
BaoStock login/logout sessions = 1
BaoStock symbols = sh.600000, sz.000001
BaoStock history range = 2025-06-03..2025-06-10
AKShare new logical calls <= 6
official page logical GETs <= 15
retry = 0 by default
```

BaoStock 数据服务使用 TCP socket，因此 Provider 数据探针的 HTTP 请求数为 0。官方页面
GET、PyPI 包检索和 Provider TCP 请求分别计数，不能混为一个“调用次数”。

## 2. BaoStock stableCallId

| stableCallId | 公开函数/能力 | 证券/范围 | 是否执行 | Provider逻辑调用 | TCP请求 | 重试 | 开始/结束 | 结果 | raw SHA-256 | safe摘要/清理 | 停止条件 |
|---|---|---|---:|---:|---:|---:|---|---|---|---|---|
| `F0-BAO-001` | 包元数据、公开导出和签名 | 0.9.3 wheel/sdist | 是 | 0 | 0 | 0 | 2026-07-28/29（date-level） | SUCCESS | wheel/sdist Hash 见证据登记册 | 未产生行情原始响应 | 否 |
| `F0-BAO-002` | `query_history_k_data_plus(adjustflag=3)` | `sh.600000`，固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | SUCCESS，6 行 | `657e26f0bf74a2b739c34a7a1a6fa2ce71d8b476d8d54876ab43090bb4d188d7` | 仅字段统计；临时文件已删 | 否 |
| `F0-BAO-003` | `query_history_k_data_plus(adjustflag=3)` | `sz.000001`，固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | SUCCESS，6 行 | `f3778c4fedcc10fa3d6a0e3153358e11ad2323729258a6a42237a1de876dd10c` | 仅字段统计；临时文件已删 | 否 |
| `F0-BAO-004` | `query_history_k_data_plus(adjustflag=2)` | `sh.600000`，固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | SUCCESS，6 行 | `5a132e906060764fcd007f2d8865c8fd8c065101a70f7e34c334e54a27e73c7d` | 仅字段统计；临时文件已删 | 否 |
| `F0-BAO-005` | `query_history_k_data_plus(adjustflag=2)` | `sz.000001`，固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | SUCCESS，6 行 | `6240cd9f44ab5a6aaa3ae626df626bf3c459b37da7ec587834fa77b84d4d532d` | 仅字段统计；临时文件已删 | 否 |
| `F0-BAO-006` | `query_trade_dates` | 固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | SUCCESS，8 行 | `e728e411131e2145c7d2875ede668e63d83ac3bd96e03c6d2de315ce6727ec7a` | 仅字段统计；临时文件已删 | 否 |
| `F0-BAO-007` | `query_dividend_data` | `sh.600000`，2025 operate year | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | SUCCESS，1 行 | `9771c1e0ea91f7aabb33684b9971e9e374bf34f37a390deed547cf559a00252b` | 仅字段统计；临时文件已删 | 否 |
| `F0-BAO-008` | `query_adjust_factor` | `sh.600000`，固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | EMPTY，完整 0 行 | `7b277e5214e7a4e18d7426ece7292cd66057505ed1c87b9dce95a5f84cb0820f` | 字段存在；临时文件已删 | 否 |
| `F0-BAO-009` | `query_adjust_factor` | `sz.000001`，固定短区间 | 是 | 1 | 1 | 0 | 2026-07-29（date-level） | EMPTY，完整 0 行 | `7504d7be74469f685c12fdef2cf04d1ede61a2633521eaebe7648fcc6472c79f` | 字段存在；临时文件已删 | 否 |
| `F0-BAO-010` | `query_daily_adjust_factor` | 单日全市场 | 否 | 0 | 0 | 0 | n/a | `F0_FULL_MARKET_CALL_NOT_ALLOWED` | n/a | 无原始响应 | 安全策略主动禁止 |

匿名 `login` 和 `logout` 各产生 1 个 TCP 协议请求，不计入 8 个数据逻辑调用。因此实际
统计为：

```text
providerLogicalCallCount=8
providerProtocolRequestCount=10
providerHttpRequestCount=0
retryCount=0
rawResponseResidueCount=0
stopConditionTriggered=false
safeSummarySha256=f97779bb9d6138faa3b049abb5f1f6da98105e359644ecc785002518086ffd0b
```

一次预检命令曾在 `login` 前以 `F0_BAOSTOCK_VERSION_MISMATCH` 本地失败，网络请求和
Provider 调用均为 0。原因是包内运行时常量没有同步 PyPI 分发版本；工具随后固定以已验证
wheel 的 distribution metadata 为权威。它不是第二次 Live 数据探针，也没有消耗调用预算。

## 3. AKShare stableCallId

本阶段固定 `akshare==1.18.64`，没有新增 AKShare 网络调用。

| stableCallId | 函数/证据 | 上游 | 是否执行新增Live | 网络请求 | 结果 | 未执行原因/复用证据 |
|---|---|---|---:|---:|---|---|
| `F0-AKS-001` | `stock_zh_a_hist_tx` 本地公开代码与现有链 | Tencent | 否 | 0 | PARTIAL | 复用 3A-R3A 两次受控响应；不重复联网 |
| `F0-AKS-002` | `stock_zh_a_daily` 本地公开代码 | Sina | 否 | 0 | NOT_PROBED | 只确认 raw/QFQ/HFQ/qfq-factor 公开模式 |
| `F0-AKS-003` | `stock_zh_a_hist` 本地公开代码 | Eastmoney | 否 | 0 | NOT_PROBED | 只确认 raw/QFQ/HFQ 公开模式 |
| `F0-AKS-004` | `stock_zh_a_disclosure_report_cninfo` | CNINFO | 否 | 0 | PARTIAL | 复用 2G Live Gate 和既有 Provider Bridge |
| `F0-AKS-005` | AKShare introduction | 多公开上游 | 否 | 0 | VERIFIED（项目风险说明） | 官方 GitHub 文档 |
| `F0-AKS-006` | 保留预算 | n/a | 否 | 0 | NOT_EXECUTED | 已有证据足够，不扩大调用范围 |

## 4. 官方页面 logical GET

官方页面逻辑 GET 总数精确为 15，没有超过预算。下表只记录 URL、状态和安全结论，不保留
Cookie、响应头或正文。

| stableCallId | 页面 | 次数 | 状态 | 结论 |
|---|---|---:|---|---|
| `F0-OFFICIAL-001` | BaoStock PyPI project | 1 | SUCCESS | 版本、包元数据与 artifact Hash |
| `F0-OFFICIAL-002` | BaoStock homepage | 1 | SUCCESS/PARTIAL | JavaScript shell |
| `F0-OFFICIAL-003` | BaoStock disclaimer | 1 | SUCCESS/PARTIAL | web renderer 未取得正文 |
| `F0-OFFICIAL-004` | BaoStock Python API | 1 | SUCCESS/PARTIAL | web renderer 未取得正文 |
| `F0-OFFICIAL-005` | AKShare GitHub introduction | 1 | SUCCESS | 研究用途、公开上游和变化风险 |
| `F0-OFFICIAL-006` | SSE Trading Schedule | 1 | SUCCESS | 交易时间与休市证据 |
| `F0-OFFICIAL-007` | SZSE Trading Overview | 1 | TIMEOUT | 未取得，不绕过 |
| `F0-OFFICIAL-008` | SZSE Data Services | 1 | TIMEOUT | 未取得，不绕过 |
| `F0-OFFICIAL-009` | CNINFO disclosure notices | 1 | SUCCESS | 法定披露平台与公告元数据 |
| `F0-OFFICIAL-010` | CNINFO public information | 1 | SUCCESS | 免责声明和独立数据服务入口 |
| `F0-OFFICIAL-011` | SZSI overseas services | 1 | TIMEOUT | 未取得，不绕过 |
| `F0-OFFICIAL-012` | SZSE Trading Overview 受控复核 | 1 | TIMEOUT | 第二次网络错误后停止 |
| `F0-OFFICIAL-013` | SZSE Data Services 受控复核 | 1 | TIMEOUT | 第二次网络错误后停止 |
| `F0-OFFICIAL-014` | BaoStock disclaimer 直接 GET | 1 | SUCCESS/PARTIAL | 与 API URL 相同 shell body |
| `F0-OFFICIAL-015` | BaoStock API 直接 GET | 1 | SUCCESS/PARTIAL | 与 disclaimer 相同 shell body |

PyPI artifact 取证使用独立临时目录。为排除解包命令和运行时版本常量问题，审计过程中共
执行 9 次受控 `pip download` 命令（wheel 8 次、sdist 1 次）；底层 HTTP transaction
未被 pip 暴露，因此不伪造一个精确低层 HTTP 数。每次 artifact Hash 相同，最终 wheel
安装在仓库外并在探针后删除。它们不属于 Provider 数据 API 调用，也不包含行情数据。

## 5. 停止条件执行

固定停止条件：

- 认证、账号、手机号、邮箱或验证码；
- HTTP 403/429；
- 连续 2 次网络错误；
- 公开结构变化；
- 超预算；
- 全市场/长时间调用；
- 需要隐藏接口、绕过或付费权限。

BaoStock Provider Live 未触发停止条件。SZSE 两个页面均在受控复核后仍超时，未继续
访问、换 IP、改 UA 或绕过。BaoStock 全市场单日因子由静态策略直接拒绝。

## 6. 数据留存

- 实际行情、成交量、成交额、因子值、公司行动数值和公告正文：未提交；
- Live 原始响应：每条在计算 Hash 后立即删除；
- 外层临时包、模块和安全摘要：探针后删除；
- `stock-quant-free-provider-f0-*` 原始临时目录残留：0；
- 仓库只保留统计摘要、Hash、字段名、结果状态和许可结论。
