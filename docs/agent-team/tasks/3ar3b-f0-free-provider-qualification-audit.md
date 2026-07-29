# 3A-R3B-F0 免费 Provider 资格审计与最小受控探针任务书

## 1. 状态与范围

状态：**审计、最小受控探针和修复后离线验证已完成；ChatGPT 已基于实际 Git 最终提交
复验通过，用户已批准并完成纯 fast-forward 合入；F1 未开始。**

- 冻结集成基线：`c47b88e586f6751563fe210f40137a3b7ce5e576`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f0-free-provider-qualification-audit`
- 目标提交：`docs(agent): audit free provider qualification`
- 最终提交：`059eacffaf7e4a9f383be205d453c5168279932a`
- 阶段记录：
  [stage-3ar3b-f0-free-provider-qualification-audit.md](../stage-3ar3b-f0-free-provider-qualification-audit.md)
- 能力矩阵：
  [free-provider-capability-matrix.md](../free-provider-capability-matrix.md)
- 证据登记册：
  [free-provider-evidence-register.md](../free-provider-evidence-register.md)
- 探针矩阵：
  [free-provider-probe-matrix.md](../free-provider-probe-matrix.md)
- 书面许可问题：
  [free-provider-written-permission-questions.md](../free-provider-written-permission-questions.md)
- 后续治理：
  [3ar3b-f05-free-implementation-scope.md](3ar3b-f05-free-implementation-scope.md)

本阶段只审计来源、能力、许可、字段和稳定性。它没有开发生产 Adapter，没有接入或执行
V13，没有访问数据库，没有启动 FastAPI、Spring Boot、Vue 或 Shadow，也没有调用
iFinD。

## 2. 审计问题

F0 对每条候选路线逐项回答：

1. 是否提供未复权 A 股日线；
2. 是否提供独立复权因子；
3. 独立因子是否有证据支持 `DAILY_EXACT`；
4. 是否提供交易所级交易日历；
5. 是否提供结构化公司行动；
6. 四类事实是否分别具有稳定来源身份；
7. 字段单位、精度、空值与明确 0 是否可解释；
8. 最小调用是否稳定；
9. 是否允许本地保存、历史快照和备份；
10. 是否允许历史回放、回测、衍生计算和内部 Agent 使用；
11. 商业化限制是否明确；
12. 是否提供 revision、snapshot、published/update time 和旧版本；
13. 是否足以进入 F1，或只能承担研究辅助/官方证据角色。

不存在单一合格免费来源也是合法结果。F0 禁止为了得到 PASS 而从 QFQ 价格反推因子、
跨 Provider 拼接 lineage、扩大抓取或推测许可。

## 3. 状态与角色枚举

审计维度只使用：

```text
VERIFIED
PARTIAL
UNVERIFIED
UNAVAILABLE
NOT_EXPOSED
NOT_PROBED
CONFLICTING_EVIDENCE
```

最终角色只使用：

```text
FREE_PROVIDER_F1_CANDIDATE
RESEARCH_AUXILIARY_ONLY
OFFICIAL_EVIDENCE_ONLY
REJECTED
PENDING_WRITTEN_PERMISSION
```

F0 不产生 `APPROVED_ADAPTER`、`PROVIDER_PIT_VERIFIED`、`FORMAL` 或
`LICENSED_INTERNAL`。

## 4. 方法与调用边界

### 4.1 证据优先级

1. Provider 官方文档；
2. 交易所或法定披露平台；
3. PyPI 分发元数据与已验证包内容；
4. 项目官方 GitHub 文档；
5. 仓库既有真实 Live Gate 与审计记录。

博客、论坛和二手文章不用于许可或资格结论。开源客户端许可证只说明客户端代码，不说明
底层行情、公告或交易所数据的使用权。

### 4.2 BaoStock 包审计

PyPI 在审计日发布的正式版为 `0.9.3`：

- wheel：`baostock-0.9.3-py3-none-any.whl`
- wheel SHA-256：
  `acbd19403285bc4e254cee8297cf0e2646ae2276e5af7e549deed3988ab02293`
- sdist：`baostock-0.9.3.tar.gz`
- sdist SHA-256：
  `16699d82d05037a8c133577fcdeb9ac0d5a7f31edc2432c4e883004e0a95e3f7`
- 包元数据许可证：BSD License
- 包依赖：`pandas>=0.18.0`
- 上游端点：`public-api.baostock.com:10030`，TCP socket，而非 HTTP。

wheel 公开导出的相关函数包括：

- `login(...)` / `logout(...)`；
- `query_history_k_data_plus(...)`；
- `query_trade_dates(...)`；
- `query_dividend_data(...)`；
- `query_adjust_factor(...)`；
- `query_daily_adjust_factor(...)`。

安全摘要对每个公开函数只保存参数名、参数 `kind` 和是否存在默认值，不保存注解内容或
任何具体默认值；离线测试证明匿名登录的默认用户名和密码值不会进入摘要。

`query_daily_adjust_factor` 是某日全市场因子接口；F0 禁止全市场查询，因此只登记公开符号，
不执行该函数。包元数据的 BSD License 不授权底层数据本地保存、回放、回测、Agent 或
商业使用。

### 4.3 受控 Live 探针

探针通过独立工具
[`quant-ai/tools/free_provider_audit_f0.py`](../../../quant-ai/tools/free_provider_audit_f0.py)
执行。工具默认断网，只有显式 `--live` 才导入临时安装在仓库外的 BaoStock；固定范围为：

```text
symbols = sh.600000, sz.000001
startDate = 2025-06-03
endDate = 2025-06-10
maximumLogicalCalls = 10
```

实际只执行 8 个数据逻辑调用，另有匿名登录和退出 2 个公开操作；工具没有 socket/frame
级观测能力，因此 `providerProtocolRequestCount=null` 且
`providerProtocolRequestCountStatus=UNVERIFIED`，不得把 10 个公开函数操作写成实际
TCP request/frame 数。Provider HTTP 请求为 0。没有重试，没有全市场调用，没有
AKShare 新增 Live 调用。

原始响应只写入操作系统临时目录以计算 SHA-256，摘要只保留字段名、类型、空值数、
小数位范围、是否存在明确 0、行数、日期范围、重复键计数和 Hash，不保留具体价格、
成交量、成交额、因子或公司行动数值。每条原始文件即时删除，探针结束时内外两层临时目录
残留均为 0。

## 5. BaoStock 事实结果

| stableCallId | 能力 | 修复后的证据口径 | 观察行数 | 关键字段/边界 |
|---|---|---:|---:|---|
| `F0-BAO-002` | `sh.600000` 未复权日线 | `COMPLETENESS_UNVERIFIED` | 6 | OHLC、preclose、volume、amount、turn、isST；观察行无空值 |
| `F0-BAO-003` | `sz.000001` 未复权日线 | `COMPLETENESS_UNVERIFIED` | 6 | 同上；观察行无空值 |
| `F0-BAO-004` | `sh.600000` 前复权日线 | `COMPLETENESS_UNVERIFIED` | 6 | 与未复权使用相同返回字段；不作为独立因子 |
| `F0-BAO-005` | `sz.000001` 前复权日线 | `COMPLETENESS_UNVERIFIED` | 6 | 与未复权使用相同返回字段；不作为独立因子 |
| `F0-BAO-006` | 交易日历 | `COMPLETENESS_UNVERIFIED` | 8 | `calendar_date/is_trading_day`；没有 exchange identity |
| `F0-BAO-007` | 分红/公司行动 | `COMPLETENESS_UNVERIFIED` | 1 | 多个公告、登记、实施和派付日期；观察行部分字段为空 |
| `F0-BAO-008` | `sh.600000` 独立因子 | `COMPLETENESS_UNVERIFIED` | 0 | 函数与字段存在；固定短区间未观察到记录 |
| `F0-BAO-009` | `sz.000001` 独立因子 | `COMPLETENESS_UNVERIFIED` | 0 | 函数与字段存在；固定短区间未观察到记录 |
| `F0-BAO-010` | 单日全市场因子 | NOT_EXECUTED | — | `F0_FULL_MARKET_CALL_NOT_ALLOWED` |

本次摘要观察到两个日线结果各有 6 个工作日、交易日历有固定范围内 8 个自然日。修复前
collector 没有在数据迭代后重读 Provider 终态，原始响应又已按设计删除，因此不能重新
证明这些响应完整；原 V1 安全摘要 Hash 只证明已保存摘要内容。短区间未观察到因子不能
证明因子不可用，也不能证明逐交易日 `DAILY_EXACT`。`query_adjust_factor` 的包内说明以
“除权除息日期”为范围，公开的单日因子函数又会返回全市场，因此本阶段只能得出：

```text
ADJUSTMENT_FACTOR=PARTIAL
DAILY_EXACT=UNVERIFIED
```

禁止用未复权/前复权价格比、后复权价格或任何插值反推因子。

### 5.1 字段语义

- OHLC、volume、amount、turn 等字段技术上存在且本次无空值；
- 返回字符串可观察到稳定十进制精度，但官方页面没有提供足以冻结 V13 单位、舍入、空值
  和明确 0 的完整数据字典；
- `isST=0` 与日历休市标记的明确 0 可与空值区分；
- 公司行动本次出现真实空字段和明确 0，证明 Adapter 不能把空值补零；
- 上述事实只验证接口形态，不批准字段资格或生产映射。

字段单位、精度和空值契约最终为 `PARTIAL/UNVERIFIED`，须在 F1 前获得正式数据字典或
书面说明。

### 5.2 许可和版本

BaoStock 公开材料证明客户端免费使用和包代码许可证，但没有取得适用于底层数据的以下
明确授权：

- 本地持久化和长期备份；
- 历史版本保存与回放；
- 回测和衍生计算；
- 内部 AI Agent 分析；
- UI 展示和未来商业化；
- revision/snapshot/publishedAt/updatedAt；
- 静默修正、旧版本查询、SLA 与限流承诺。

因此 BaoStock 当前最终角色为：

```text
PENDING_WRITTEN_PERMISSION
```

它可以继续作为 raw/研究数据技术候选，但不能进入完整 V13/QFQ Adapter，也不能取得
`PROVIDER_PIT_VERIFIED`。

## 6. AKShare 按上游拆分结果

仓库继续固定 `akshare==1.18.64`，本阶段没有升级，也没有新增 AKShare Live 调用。

| 客户端函数 | 实际上游 | 当前事实 | 结论 |
|---|---|---|---|
| `stock_zh_a_hist_tx` | Tencent QQ Finance | 支持未复权/QFQ/HFQ；现有生产链使用 QFQ，返回 date/OHLC/amount | `RESEARCH_AUXILIARY_ONLY` |
| `stock_zh_a_daily` | Sina Finance | 支持未复权/QFQ/HFQ，并在代码中暴露 qfq/hfq factor 模式；F0 未联网验证 factor | `RESEARCH_AUXILIARY_ONLY` |
| `stock_zh_a_hist` | Eastmoney | 支持未复权/QFQ/HFQ；未暴露同函数独立因子 | `RESEARCH_AUXILIARY_ONLY` |
| `stock_zh_a_disclosure_report_cninfo` | CNINFO | 2G 已验证公告元数据和 CNINFO URL；日期精度、修订关系和完整性受限 | `RESEARCH_AUXILIARY_ONLY` |

AKShare 是客户端聚合库，不是这四个上游的共同 `sourceCode`。Tencent、Sina、Eastmoney
和 CNINFO 必须保持独立 source identity，不能组成同源 lineage。官方 AKShare 项目文档
明确提示数据来自公开来源、接口会随页面变化，适合研究且商业使用存在风险。

### 6.1 Tencent 冻结结论

现有受控两次请求已经证明同一范围的行数、内容和原始 body 一致，且 `version=18` 一致；
但没有 Provider 字段说明、旧版本、修订关系或发布时间证据。当前仍为：

```text
PROVIDER_REVISION_UNVERIFIED
```

`version=18`、HTTP Date、本地 UUID、dataset/observation version、内容 Hash 和抓取时间均
不能成为 `sourceRevision`。现有 Java/Python 链还把 Tencent 的 `amount` 临时映射到
`volume` 并把 amount 补零，只适用于旧 current projection，不能直接作为 V13 字段资格。

## 7. 官方证据来源

### 7.1 CNINFO

CNINFO 页面确认其为深圳证券交易所法定信息披露平台，由深交所全资子公司深圳证券信息
有限公司运营。公告页面提供代码、简称、标题、公告时间和正文入口；免责声明不保证准确性
和完整性，并把数据 API/商城列为独立服务。

因此 CNINFO 可以承担公告和公司行动披露的官方核验证据，但公开页面没有批准批量 PDF、
结构化公司行动落库、历史回放、回测、内部 Agent 或商业使用，也没有证明完整的修订/
撤回/替换关系。最终角色为 `OFFICIAL_EVIDENCE_ONLY`。

### 7.2 SSE、SZSE 与深圳证券信息有限公司

- SSE 官方 schedule 页面可以核验交易时段和公开休市安排；
- 它不自动授予 EOD 行情下载、本地保存、回测、Agent 或商业用途；
- SZSE trading overview、data services 和深圳证券信息有限公司海外服务页在受控读取中
  超时，没有绕过或重试；
- CNINFO 页面将行情授权和行情服务指向深圳证券信息有限公司，反而证明公开网页与正式
  市场数据授权是不同层级。

三者当前均为 `OFFICIAL_EVIDENCE_ONLY`；机器可读、版本化、交易所级日历和市场数据许可
仍需取得正式服务说明或书面确认。

## 8. F0 结论

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

`PARTIAL` 的理由：

1. BaoStock raw/QFQ 日线、通用日历、分红和因子接口均观察到可解析的最小技术形态，
   但本次 Live response completeness 为 `UNVERIFIED`；
2. 独立因子在固定区间未观察到记录，`DAILY_EXACT` 未验证；
3. BaoStock 日历没有交易所身份；
4. 字段单位、空值与许可缺少足够正式证据；
5. revision/snapshot/published/update 和旧版本不可验证；
6. AKShare 各上游只能保持研究辅助；
7. CNINFO/SSE/SZSE 只能作为官方证据，不是已批准批量数据 Adapter。

当前没有任何免费来源能单独承担完整 V13/QFQ 同源 lineage。F0 已完成实际 Git 验收和
合入，但 F1 仍只有在书面许可与核心技术证据补齐并获用户单独授权后才可规划；本阶段没有
自动开始 F1。

## 9. 验证

执行：

```text
quant-ai/.venv/Scripts/python.exe -m unittest \
  tests.agent_team.test_free_provider_audit_f0 -v
quant-ai/.venv/Scripts/python.exe -m compileall app tools tests
quant-ai/.venv/Scripts/python.exe tools/free_provider_audit_f0.py
```

离线测试覆盖默认断网、显式 live、调用预算、证券/日期白名单、禁止全市场、敏感字段递归
脱敏、不输出实际行情值、临时文件删除、canonical Hash、空数据、终态 `PARTIAL`、明确
`TIMEOUT`、错误、结构变化、独立运行、不访问数据库和不调用 iFinD。

本阶段不需要也没有运行 Java、Vue 或 PostgreSQL 测试；没有启动任何服务。

## 10. 完成边界

- 实际行情、成交量、成交额、因子和公告正文未提交；
- Live 原始响应残留为 0；
- iFinD 真实调用数为 0；
- 数据库访问和 V13 执行为 0；
- Day 002 未创建；
- scheduler 关闭；
- F1、3A-R3B-1 和 3B 均未开始；
- `.ai/` 未读取、修改、暂存或提交；
- 本任务分支已由用户批准纯 fast-forward 合入集成分支。
