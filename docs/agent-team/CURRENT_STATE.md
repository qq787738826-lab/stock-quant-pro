# Stock Quant Pro 智能体团队当前状态

> 本文件是智能体团队进度、能力边界和下一阶段入口的单一事实来源。阶段文档、聊天记录或旧注释与本文件冲突时，以本文件为准。

历史阶段提交、测试和验收证据索引见 [PROGRESS_LOG.md](PROGRESS_LOG.md)；该日志不替代本文件。

## 基线

- 当前稳定版本：`1.3.1`
- 当前目标版本：`1.4.0`
- 当前集成分支：`feature/1.4.0-agent-team`
- 当前集成分支 HEAD：`d3c0ada828c90c772f6bbbb7a787ba2d1ce8b7eb`
- 1D-4 验收来源分支：`codex/1.4.0-1d4-acceptance`
- 1D-4 验收基线：`5bc492a feat(agent): add safe local team runtime scripts`
- 阶段 2A 验收来源分支：`codex/1.4.0-2a-readonly-context`
- 阶段 2A 实现基线：`1707d3e4991434d7655b50d9af0b532e0b0e7a60`
- 阶段 2B 验收来源分支：`codex/1.4.0-2b-data-quality-gate`
- 阶段 2B 规则版本：`1.4.0-stage-2b-dq-v1`
- 阶段 2C 验收来源分支：`codex/1.4.0-2c-readonly-research-context`
- `marketBreadth` 生产版本：`MARKET_BREADTH_V1`
- 阶段 2D-1 验收来源分支：`codex/1.4.0-2d1-market-breadth-state`
- 阶段 2D-1 实现提交：`b956d02a65b5d5d27179013983ff4b501302fd47`
- 阶段 2D-1 规则版本：`1.4.0-stage-2d-market-regime-v1`
- 阶段 2D-1 已通过任务分支完成实现与验收，并已合并到集成分支；集成提交：`ff461d9f851e7ec37390dda6c15a67987f672dc7`。
- 阶段 2D-2A 来源分支：`codex/1.4.0-2d2a-temporal-market-foundation`
- 阶段 2D-2A 最终任务提交：`3a3eebd2ef580d31a6b02aab1a7204ea02fdba58`
- 阶段 2D-2A 已完成开发、真实 PostgreSQL 闭环、独立审查、审查问题修复和修复后复审，并已合并到集成分支。
- 阶段 2D-2B-1A 来源分支：`codex/1.4.0-2d2b1a-ingestion-foundation`
- 阶段 2D-2B-1A 最终任务提交：`7019ffdd75d364847404afb10edb0ec653c307bf`
- 阶段 2D-2B-1A 已完成 source-neutral ingestion foundation，并已通过合并提交 `505d18ca2e06c039163eada8f2f09f95cee97f30` 合入集成分支。
- 阶段 2D-2B-1B-0 文档任务分支：`codex/1.4.0-2d2b1b0-event-contract-freeze`
- 阶段 2D-2B-1B-0 首个契约提交：`c97d6a2c954f536eedd42796b1112aeaab421417`
- 阶段 2D-2B-1B-0 复审修复提交：`28c312dcbe26103c5f2b45c043ec6a8f81a08ae0`
- 阶段 2D-2B-1B-0 证券状态事件物化契约已冻结并通过独立 GitHub 审查；该阶段本身只有契约文档，后续 2D-2B-1B-1 实现现已完成。
- 阶段 2D-2B-1B-1 首次实现提交：`18151800d07fd7d2e6706b88869df5b7d0aa8ba0`
- 阶段 2D-2B-1B-1 复审修复提交：`b6cb263f863f91753f043e0fa19e85501873111f`
- 阶段 2D-2B-1B-1 已完成 TEST/DEMO security event materialization foundation，通过独立 GitHub 复审并获得 PASS，已通过合并提交 `9aebcbf7d5a315d1edd61d85bf2944a454f72ffe` 合入集成分支。
- 阶段 2E-1 任务分支：`codex/1.4.0-2e-technical-analysis-v1`
- 阶段 2E-1 冻结集成基线：`70b4bacb06dbaf799ec3b01599f07d38e8b96589`
- 阶段 2E-1 规则版本：`1.4.0-stage-2e-technical-analysis-v1`
- 阶段 2E-1 实现提交：`93ccf7c6da380be91ca342f6c5e8815f8e7dfe07`
- 阶段 2E-1 集成合并提交：`adb781c3ffb41ff13a14538067e838a60a65bea9`
- 阶段 2E-1 已完成确定性 TECHNICAL_ANALYSIS V1，实现通过 Codex 本地验证和独立 GitHub 最终复审 PASS（HIGH 0 / MEDIUM 0 / LOW 0），并已合入集成分支。
- 阶段 2F 冻结集成基线：`1b6eb8c65a39bdae6b6e1fbd6d43743be881bed4`
- 阶段 2F 任务分支：`codex/1.4.0-stage-2f-strategy-backtest-v1`
- 阶段 2F 规则版本：`1.4.0-stage-2f-strategy-backtest-v1`
- 阶段 2F context profile / Schema：`AGENT_CONTEXT_2F_V1` / `BACKTEST_CONTEXT_V1`
- 阶段 2F 实现提交：`4ae0ac4ebc12aef559b9f88e7e1dfacc2b00a573`
- 阶段 2F knowledge-time 修复及最终提交：`4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`
- 阶段 2F 当前状态：已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge；集成分支已 fast-forward 至 `4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`。精确批准时间无仓库证据，记为 `UNKNOWN`。
- 阶段 2H 冻结集成基线：`4b1ee01a86b027ec43deaab18e6a68a098e0e2f4`
- 阶段 2H 任务分支：`codex/1.4.0-stage-2h-position-risk-v1`
- 阶段 2H 规则版本：`1.4.0-stage-2h-position-risk-v1`
- 阶段 2H context profile / Schema：`AGENT_CONTEXT_2H_V1` / `PORTFOLIO_CONTEXT_V1`
- 阶段 2H 实现及最终提交：`a898e21df38594a6aca1429a3dfd5e28c2cf7f72`
- 阶段 2H 当前状态：已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge；集成分支已 fast-forward 至 `a898e21df38594a6aca1429a3dfd5e28c2cf7f72`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 阶段 2G 冻结集成基线：`a898e21df38594a6aca1429a3dfd5e28c2cf7f72`
- 阶段 2G 任务分支：`codex/1.4.0-stage-2g-announcement-risk-v1`
- 阶段 2G 规则版本：`1.4.0-stage-2g-announcement-risk-v1`
- 阶段 2G context profile / Schema：`AGENT_CONTEXT_2G_V1` / `SECURITY_EVENTS_CONTEXT_V1`
- 阶段 2G 研究来源 / Provider / canonical 契约：`AKSHARE_CNINFO_RESEARCH_V1` / `AKSHARE_CNINFO_PROVIDER_V1` / `ANNOUNCEMENT_CANONICAL_V1`
- 阶段 2G 实现提交：`9213507785323ab286d2cae147cf1d893dc102b6`
- 阶段 2G 混合标题与 CNINFO 域名门禁修复及最终提交：`681fee989f08c4c1e4edaa8cf787c97a95a27784`
- 阶段 2G 当前状态：已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge；集成分支已纯 fast-forward 至 `681fee989f08c4c1e4edaa8cf787c97a95a27784`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。该状态不表示来源获得 FORMAL/PIT 资格。
- 阶段 2I 冻结集成基线：`681fee989f08c4c1e4edaa8cf787c97a95a27784`
- 阶段 2I 任务分支：`codex/1.4.0-stage-2i-chief-decision-v1`
- 阶段 2I 规则 / 总控 / 权重契约：`1.4.0-stage-2i-chief-decision-v1` / `CHIEF_DECISION_V1` / `CHIEF_SCORE_WEIGHTS_V1`
- 阶段 2I context profile：复用 `AGENT_CONTEXT_2G_V1`
- 阶段 2I 实现提交：`8c391be46aa7c823577c0a15f866165473341708`
- 阶段 2I 终态修复及最终提交：`954959f5832d01ba1f7211d3e6ebbd8c93feab22`
- 阶段 2I 当前状态：已通过 ChatGPT 对实际 Git 最终提交的验收；用户已批准 merge；集成分支已纯 fast-forward 至 `954959f5832d01ba1f7211d3e6ebbd8c93feab22`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 阶段 3A-1 冻结集成基线：`954959f5832d01ba1f7211d3e6ebbd8c93feab22`
- 阶段 3A-1 任务分支：`codex/1.4.0-stage-3a1-shadow-readiness-v1`
- 阶段 3A-1 契约：`SHADOW_RUN_CONTROL_V1` / `SHADOW_SELECTION_V1` / `SHADOW_OUTCOME_SNAPSHOT_V1` / `SHADOW_REVIEW_V1` / `SHADOW_METRICS_V1`
- 阶段 3A-1 实现及最终提交：`99b369fcc652b8344453532a7ff9597751a6040b`
- 阶段 3A-1 当前状态：已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge；集成分支已纯 fast-forward 至 `99b369fcc652b8344453532a7ff9597751a6040b`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。scheduler 默认关闭，完整 3A 未完成，3B 未开始。
- 阶段 3A-R1 冻结集成基线：`99b369fcc652b8344453532a7ff9597751a6040b`
- 阶段 3A-R1 任务分支：`codex/1.4.0-stage-3ar1-flyway-v6-lineage-recovery`
- 阶段 3A-R1 实现及最终提交：`4fea1e210e683fea8490685879529f1d27e6448b`
- 阶段 3A-R1 当前状态：V6 血统恢复、V12 前向承接和测试隔离修复已通过 ChatGPT 对实际 Git 提交的验收；用户已批准 merge，集成分支已纯 fast-forward 至最终提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 阶段 3A-R3A 冻结集成基线：`4fea1e210e683fea8490685879529f1d27e6448b`
- 阶段 3A-R3A 任务分支：`codex/1.4.0-stage-3ar3a-pit-market-facts-design-v2`
- 阶段 3A-R3A 首次设计提交：`be12916ab0db07ceaa040883397424e10828b867`
- 阶段 3A-R3A QFQ factor 选择语义修复及最终提交：`94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`
- 阶段 3A-R3A 当前状态：可验证 PIT 市场原始事实 V2 的来源资格、事实模型、QFQ as-of 和实施门禁设计已经通过 ChatGPT 对实际 Git 提交的验收；用户已批准纯 fast-forward 合入，集成分支已前进至最终提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。该状态只表示设计完成，不表示 Provider 已接入或 V2 已实现。
- 阶段 3A-R3B iFinD 试用启动规划冻结基线：`94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`
- 阶段 3A-R3B iFinD 试用启动规划任务分支：`codex/1.4.0-stage-3ar3b-ifind-trial-activation-plan`
- 阶段 3A-R3B iFinD 试用启动规划实现及最终提交：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 阶段 3A-R3B iFinD 试用启动规划当前状态：已通过 ChatGPT 对实际 Git 提交的验收；用户已批准纯 fast-forward 合入，集成分支已前进至最终提交。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 阶段 3A-R3B-0 冻结集成基线：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 阶段 3A-R3B-0 任务分支：`codex/1.4.0-stage-3ar3b0-provider-neutral-pit-offline-v2`
- 阶段 3A-R3B-0 最终提交：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 阶段 3A-R3B-0 当前状态：Provider 中立 PIT 市场事实 V2、V13、TEST/DEMO Mock Provider、`DAILY_EXACT` QFQ、2F V2、六智能体和 EXPLICIT Mock Shadow 已通过 ChatGPT 对实际 Git 提交的最终复验；用户已批准纯 fast-forward 合入，远程集成分支与最终提交一致，ahead/behind 为 `0/0`。四类 as-of 先选定唯一语义版本再检查用途许可，选中版本不允许时固定返回 `PIT_USAGE_NOT_ALLOWED`，不得回退旧允许版本。V13 代码已经进入集成分支，但正常业务库尚未执行 V13；真实 Provider 仍未接入。
- 3A-R3B 免费优先规划冻结基线：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 3A-R3B 免费优先规划任务分支：`codex/1.4.0-stage-3ar3b-free-first-validation-plan`
- 3A-R3B 免费优先规划最终提交：`c47b88e586f6751563fe210f40137a3b7ce5e576`
- 3A-R3B 免费优先规划当前状态：已通过 ChatGPT 对实际 Git 提交的验收；用户已批准纯 fast-forward 合入，本地和远程集成分支均到达最终提交，ahead/behind 为 `0/0`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 3A-R3B-F0 冻结集成基线：`c47b88e586f6751563fe210f40137a3b7ce5e576`
- 3A-R3B-F0 任务分支：`codex/1.4.0-stage-3ar3b-f0-free-provider-qualification-audit`
- 3A-R3B-F0 最终提交：`059eacffaf7e4a9f383be205d453c5168279932a`
- 3A-R3B-F0 当前状态：免费 Provider 逐来源/逐事实资格审计、BaoStock 最小受控探针、独立审计工具、能力矩阵、证据登记册、调用矩阵和书面许可问题清单均已完成；ChatGPT 已基于实际 Git 最终提交复验通过，用户已批准并完成纯 fast-forward 合入，本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`。结论固定为 `F0_AUDIT_RESULT=PARTIAL`，不表示审计失败，也不批准 F1。
- 3A-R3B-F0.5 冻结集成基线：`059eacffaf7e4a9f383be205d453c5168279932a`
- 3A-R3B-F0.5 任务分支：`codex/1.4.0-stage-3ar3b-f05-free-implementation-scope`
- 3A-R3B-F0.5 最终提交：`08943b4f6af03c75aa4df2a4ecf2494bede4e57b`
- 3A-R3B-F0.5 当前状态：免费版实施顺序、研究预览边界、双轨路线和门禁已经通过 ChatGPT 对实际 Git 提交的验收；用户已批准纯 fast-forward 合入，本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`。精确验收和批准时间无仓库证据，记为 `UNKNOWN`。
- 3A-R3B-F2A 冻结集成基线：`08943b4f6af03c75aa4df2a4ecf2494bede4e57b`
- 3A-R3B-F2A 任务分支：`codex/1.4.0-stage-3ar3b-f2a-research-preview-product`
- 3A-R3B-F2A 最终提交：`f5137e2422a48e70d5c706cb146fb034a2b96f65`
- 3A-R3B-F2A 当前状态：`/research-preview` 免费研究预览产品的前端技术实现已通过 ChatGPT 对实际 Git 提交的验收，经用户批准纯 fast-forward 合入；本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`。页面只读复用既有 GET API，并严格隔离 `EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED` 与 `TEST_DEMO_EXPLICIT`；未新增 Java 接口或任何外部数据调用。
- 3A-R3B-F2A 首次视觉验收（历史状态）：用户于 `2026-07-29` 基于完整页面截图完成查看；功能闭环、Demo 隔离、六智能体、总控、证据、历史对比和报告均可见，但页面过长、默认技术字段过多、字体偏小、信息层级不足、Demo 标签重复和报告偏日志化，因此该轮视觉验收结论为 `BLOCKED`。
- 3A-R3B-F2A-R1 冻结基线：`f5137e2422a48e70d5c706cb146fb034a2b96f65`
- 3A-R3B-F2A-R1 任务分支：`codex/1.4.0-stage-3ar3b-f2a-r1-preview-ux-convergence`
- 3A-R3B-F2A-R1 最终提交：`e2b9457e3594676875167a703ae09ebc75aaaaf6`
- 3A-R3B-F2A-R1 当前状态：研究预览的五分区导航、首屏研究总览、默认折叠技术详情、结构化报告和响应式视觉收敛已通过 ChatGPT 对实际 Git 提交的技术复验；用户已批准纯 fast-forward 合入，本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`。
- 3A-R3B-F2A-R1 第二次视觉复验（历史状态）：用户于 `2026-07-29` 完成查看；五个 Tab、首屏层级、精简 Agent 卡片、默认折叠技术/证据/历史详情、结构化报告、字号和深色主题方向已通过，但 DEMO01 首屏文字重叠、数据门禁与证据不足语义歧义、INFO finding 严重红色样式以及 DEMO02 正式 veto 待最终查看，因此该轮总体结论仍为 `BLOCKED`。
- 3A-R3B-F2A-R1A 冻结基线：`e2b9457e3594676875167a703ae09ebc75aaaaf6`
- 3A-R3B-F2A-R1A 任务分支：`codex/1.4.0-stage-3ar3b-f2a-r1a-visual-semantics-fix`
- 3A-R3B-F2A-R1A 提交链：`99b22a5e3bd2ad945c2f2b10ae79618277f8ed01` → `11657c572d9561ae3b4a37be7a22f7456444844f`。两次提交均已通过 ChatGPT 对实际 Git 提交的最终技术复验，经用户批准纯 fast-forward 合入。
- 3A-R3B-F2A-R1A 视觉复验（历史状态）：数据质量门禁/研究证据完整性拆分、DATA_QUALITY 优先级和 finding 风险 tone 通过查看；首屏双列头部仍发生重叠，因此该轮产品门仍为 `BLOCKED`。
- 3A-R3B-F2A-R1B 任务分支：`codex/1.4.0-stage-3ar3b-f2a-r1b-overview-flow-fix`
- 3A-R3B-F2A-R1B 最终提交：`4917bbabc8262106abb47e6cb90cf7ab96e76d7d`
- 3A-R3B-F2A-R1B 当前状态：研究总览改为稳定单列正常文档流，已通过 ChatGPT 对实际 Git 提交的技术复验，经用户批准纯 fast-forward 合入；本地与远程集成分支均位于该提交，ahead/behind 为 `0/0`。
- 3A-R3B-F2A 最终用户视觉验收：用户基于 `4917bbabc8262106abb47e6cb90cf7ab96e76d7d` 完成 DEMO01 与 DEMO02 最终复验，并于 `2026-07-29 16:44 +08:00` 明确回复：“认可当前产品形态，批准创建独立治理提交，将FREE_PRODUCT_PREVIEW_GATE改为PASS。”
- 3A-R3B-F2A-GATE 治理分支：`codex/1.4.0-stage-3ar3b-f2a-product-preview-gate-pass`
- 3A-R3B-F2A-GATE 最终提交：`8b6a6bf39a40e44062a3f7aeb315e17e9b62e199`
- 3A-R3B-F2A-GATE 当前状态：治理提交已通过 ChatGPT 对实际 Git 提交的复验，经用户批准纯 fast-forward 合入；本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`。`FREE_PRODUCT_PREVIEW_GATE=PASS`，Track A 正式完成。
- 3A-R3B-TRACK-B0 冻结基线：`8b6a6bf39a40e44062a3f7aeb315e17e9b62e199`
- 3A-R3B-TRACK-B0 任务分支：`codex/1.4.0-stage-3ar3b-track-b0-provider-route-decision`
- 3A-R3B-TRACK-B0 最终提交：`284588242443af5ce03b468825f861b29ced5ad0`
- 3A-R3B-TRACK-B0 当前状态：有界官方资料调查、三候选统一资格矩阵、成本模型、书面许可请求包、F1 准入合同和未来试用探针合同已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入；本地和远程集成分支一致，ahead/behind 为 `0/0`。主要路线为 `TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST`（Tushare Pro），备用路线为 `TRACK_B_FALLBACK_ROUTE=IFIND`。
- 3A-R3B-TRACK-B1 冻结基线：`284588242443af5ce03b468825f861b29ced5ad0`
- 3A-R3B-TRACK-B1 任务分支：`codex/1.4.0-stage-3ar3b-track-b1-tushare-probe-review`
- 3A-R3B-TRACK-B1 最终提交链：`39ec0411a10e1ea6ada9d34da4a20aee04382c92` → `d223fdf9ff997ca256f2d0f651c99542e817dfee`。
- 3A-R3B-TRACK-B1 当前状态：`2026-07-30` 已完成的 Tushare 2000 积分十项受控权限探针与修正后的治理记录已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入；本地和远程集成分支均位于 `d223fdf9ff997ca256f2d0f651c99542e817dfee`，ahead/behind 为 `0/0`。精确执行时刻为 `PROBE_EXECUTION_TIME=UNKNOWN`；十项均为 `PASS`，`TUSHARE_2000_PERMISSION_PROBE=PASS`，当时累计真实业务请求为 10、重试/权限错误/网络错误均为 0。该技术权限检查执行前未取得完整探针合同要求的两项 Provider 书面答复，因此 `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`、`TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`，自动探针及响应留存书面许可均为 `UNVERIFIED`；这不判定本次调用合法或违法。
- 3A-R3B-F1A 冻结基线：`d223fdf9ff997ca256f2d0f651c99542e817dfee`
- 3A-R3B-F1A 任务分支：`codex/1.4.0-stage-3ar3b-f1a-tushare-adapter`
- 3A-R3B-F1A 最终提交链：`fc076821173407205e838b9512ee3a97e7afd3a6` → `e588e08533ddeefc161266eaf93a12e128748a47` → `1ad039038829f6e752ce6ecea6da0a3d88882df7`。
- 3A-R3B-F1A 合入时状态（历史）：三提交链已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入。Tushare 官方企业微信于 `2026-07-30` 书面回复“问：这个可以用来当量化数据来源吧；答：可以”，在 F1A 当时只支持 `WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED`；个人本地长期存储、回测和 Agent 三项当时为 `UNVERIFIED`。用户已明确 `USER_PERSONAL_USE_IMPLEMENTATION_AUTHORIZATION=CONFIRMED`，有限个人实现为 `APPROVED_BY_USER`。Adapter 默认 `DISABLED`，只有显式 `MANUAL_BOUNDED` 五 Endpoint 和类型化有限个人 FORMAL 捕获入口可用。初始 6 请求与修复剩余 4 请求均完成且零重试。F1A 合入时 `F1_ENTRY_READINESS=BLOCKED_MULTIPLE`，精确包含 `BLOCKED_WRITTEN_PERMISSION` 和 `BLOCKED_TECHNICAL_EVIDENCE`；F1D 的后续书面许可闭环不回写这一历史时点。
- 3A-R3B-F1B 冻结基线：`1ad039038829f6e752ce6ecea6da0a3d88882df7`
- 3A-R3B-F1B 任务分支：`codex/1.4.0-stage-3ar3b-f1b-tushare-technical-contract`
- 3A-R3B-F1B 最终提交链：`ce3360058b4ade6a2e86cdd9302387e7d338794b` → `0b2dbb665c8e45c4d0024d16094e3925d4dfe55e`。
- 3A-R3B-F1B 当前状态：双提交链已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入，本地与远程集成分支均位于最终提交，ahead/behind 为 `0/0`。只使用 Tushare 官方资料完成完整技术合同与缩减路线判定；类型化 `TechnicalClaim(status,evidenceIds)` 模型生成 `TUSHARE_TECHNICAL_ROUTE_DECISION=REDUCED_RESEARCH_ONLY`、`TUSHARE_REDUCED_RESEARCH_CONTRACT=READY`、`fullTechnicalContractReady=false`、`reducedResearchContractReady=true`。其中 `READY` 只表示合同定义完成；F1B 结束时 `QFQ_FORMULA_QUALIFICATION=VERIFIED`，但 `QFQ_OPERATIONAL_RUNTIME_QUALIFICATION=PARTIAL`、`REDUCED_RESEARCH_RUNTIME_READY=false`，现有 `QfqAsOfEngine` 保留公司行动 lineage 门禁，Endpoint 级限流和缩减运行入口当时尚未实现。raw/factor/calendar 满足缩减合同；公司行动、Provider revision/旧版本、永久证券身份和全历史 `DAILY_EXACT` 不满足完整 F1。本阶段 Provider 新增调用为 0，未检查 Token，未访问数据库。
- 3A-R3B-F1C 冻结基线：`0b2dbb665c8e45c4d0024d16094e3925d4dfe55e`
- 3A-R3B-F1C 任务分支：`codex/1.4.0-stage-3ar3b-f1c-tushare-reduced-runtime`
- 3A-R3B-F1C 最终提交链：`0d806e975985038e8d8c617ce1ce4c56e1dc80dd` → `6b34e0f730d8f70fb5894c78e692062ae5fb303d`。
- 3A-R3B-F1C 当前状态：双提交链已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准纯 fast-forward 合入，本地与远程集成分支均位于最终提交，ahead/behind 为 `0/0`。Endpoint 保守较小值类型化策略、进程内全局/Endpoint 分钟窗口与每 Endpoint 每日额度原子限流、实际 Gateway policy/limiter 合同、类型化隔离手工授权、专用数据库身份与事务绑定连接守卫、三 Endpoint/三请求/零重试缩减研究入口和公式级内存 QFQ 均已合入。`ENDPOINT_SPECIFIC_RATE_LIMIT_ENFORCED=true`、`CONSERVATIVE_ENDPOINT_MINIMUM_POLICY_ENFORCED=true`、`TUSHARE_REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME=READY`、`REDUCED_RESEARCH_ISOLATED_MANUAL_RUNTIME_READY=true`；但 `REDUCED_RESEARCH_RUNTIME_READY=false`、`REDUCED_RESEARCH_PRODUCTION_RUNTIME_READY=false`、`NORMAL_BUSINESS_DATABASE_RUNTIME_READY=false`、`SCHEDULER_RUNTIME_READY=false`、`FULL_TECHNICAL_CONTRACT_READY=false`。PostgreSQL 16.13 临时实例随机 Schema 闭环通过且 public 指纹不变；本阶段 Provider 新增调用为 0，未读取或检查 Token，未访问正常业务数据库。
- 3A-R3B-F1D 冻结基线：`6b34e0f730d8f70fb5894c78e692062ae5fb303d`
- 3A-R3B-F1D 任务分支：`codex/1.4.0-stage-3ar3b-f1d-tushare-written-permission-closure`
- 3A-R3B-F1D 最终提交链：`349856ea6e9e3dc423fc1ad9115886cfc8858159` → `049c750026fa00dad70c12667fad732af07d60ce` → `01024df465afcfa34dfd4efdbef7d56d32419aa1`。
- 3A-R3B-F1D 当前状态：三提交链已通过 ChatGPT 对实际 Git 的最终复验，经用户批准纯 fast-forward 合入；本地与远程集成分支均位于最终提交，ahead/behind 为 `0/0`。用户提供的 Tushare 官方七项逐条书面回复已按 `TS-WP-002` 精确脱敏转录登记，`TRANSCRIPTION_RECEIVED_AT=2026-07-31T11:07:00+08:00`、`OFFICIAL_REPLY_AT=UNKNOWN`；用户证明来源为官方回复，但仓库未保存原件，Codex 未查看截图也未独立认证来源。类型化书面许可、证据 provenance、Claim 主题绑定和 F1 聚合模型已经合入：八项个人研究许可为 `VERIFIED`，再分发、商业数据服务和 Token 共享保持 `NOT_GRANTED`；`WRITTEN_PERMISSION_GATE=PASS`，当前 `F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`，只剩 `BLOCKED_TECHNICAL_EVIDENCE`。生产、正常业务库、scheduler、Agent/回测、F2B/F3 和交易入口仍关闭。
- 3A-R3B-F1E 冻结基线：`01024df465afcfa34dfd4efdbef7d56d32419aa1`
- 3A-R3B-F1E 任务分支：`codex/1.4.0-stage-3ar3b-f1e-dedicated-research-runtime`
- 3A-R3B-F1E 当前状态：三提交链 `d5f28066bee97a5485917e193926594b9961767e` → `e95781687cd0af63507c42017ec8ca6d6f404f86` → `0e2b607bc068910319134790360d71a18a6a9e02` 已通过 ChatGPT 对完整实际 Git 差异的最终审查，经用户批准纯 fast-forward 合入。类型化缩减研究准入、专用数据库身份守卫、单日 1—3 证券共享会话、每证券 `daily/adj_factor/trade_cal` 精确三请求、全响应写前验证、开市日语义、全批次单事务合同及内存公式级 QFQ 均已合入。后续真实 Runner 验收发现非 Spring 手工装配没有触发注解事务；该缺陷不改变 F1E 合同，修复任务必须用专用 DataSource 的显式事务恢复合同。当前 `REDUCED_RESEARCH_ROUTE_DECISION=DEDICATED_LOCAL_RESEARCH_PATH`、`REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true`、`REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true`，但 `REDUCED_RESEARCH_OPERATIONAL_READY=false`；不改变完整 F1 技术阻断、生产/正常业务库、scheduler、Agent/回测、F2B/F3 或交易门禁。
- 3A-R3B-F1F-A 冻结基线：`0e2b607bc068910319134790360d71a18a6a9e02`
- 3A-R3B-F1F-A 任务分支：`codex/1.4.0-stage-3ar3b-f1f-controlled-acceptance-preparation`
- 3A-R3B-F1F-A 当前状态：双提交链已通过实际 Git 审查并纯 fast-forward 合入集成提交 `f68d84403ebb82babe92a1cb0f78d845ed39547a`。对象实例 CAS、类型化候选证据与默认拒绝投影已合入；其历史弱证明字段仍保留用于区分 F1F-B1 强证明。
- 3A-R3B-F1F-B1 当前状态：双提交链 `e0dfba061a1b2e335c2f0db9bc9efeac012d75c8` → `e3777602fadd65f3af0a2ba8ac6e886693d745d5` 已通过实际 Git 复验并经用户批准纯 fast-forward 合入。V14 使用独立 location 与独立治理历史，默认主历史仍为 V1—V13；TEST 成功只形成候选。F1F-B2 真实验收未运行，当前 `CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN`、`REDUCED_RESEARCH_OPERATIONAL_READY=false`。
- 3A-R3B-F1F-B2-PRE 当前结论：`NOT_READY`。审计确认 `e3777602...` 只能作为 F1F-B2-RUNNER 开发基线，不再是未来正式验收冻结 SHA；当时仍缺最小专用进程、审计先于秘密/数据源的全流程时序、显式治理 bootstrap、构建双模式和 Maven Wrapper 证明。
- 3A-R3B-F1F-B2-RUNNER 当前状态：四提交链 `4c136b32` → `0d8109cc` → `e18623f4` → `213264bc63a2584f0fbb30dca059abf272e62a64` 已通过实际 Git 复验并经用户批准纯 fast-forward 合入。专用非 Spring Runner、全流程输出审计、独立治理 V14、隔离构建和构建证明已经进入集成分支；真实 B2、真实三请求、正式构建证明和正式授权仍未执行。
- 3A-R3B-F1F-B2-FREEZE 首次结论：`2026-08-03` 基于 `213264bc...` 的冻结审计为历史 `NOT_READY`。两个精确阻断是清单误用不存在的松散 Runner 参数，以及缺少从全新专用实例创建数据库/角色/Schema并只执行主 V1—V13 的安全准备入口。旧草案 ID `F1FB2_20260803_140506_96C6DFB7` 已废弃且禁止复用。
- 3A-R3B-F1F-B2-DBPREP 当前状态：四提交链 `d178cde7` → `14b466f5` → `c8342830` → `d3c0ada828c90c772f6bbbb7a787ba2d1ce8b7eb` 已通过实际 Git 复验并经用户批准纯 fast-forward 合入。一次性数据库准备链及单一 `AuthorizationFile` 冻结合同校正已进入集成分支；专用 PostgreSQL 16.13 数据库已按 V1—V13 准备，后续真实受控验收已完成独立治理 V14。
- 3A-R3B-F1F-B2 首次真实受控验收：ID `F1FB2_20260803_REISSUE_C5A3D7B92A94` 已执行并永久封存；最终为 `FAILED_DATABASE_GUARD`，`failure_stage=DATABASE_GUARD`，`safe_failure_reason=TUSHARE_DEDICATED_RESEARCH_TRANSACTION_REQUIRED`。精确调用 `daily/adj_factor/trade_cal` 共 3 次、重试 0；`capture_batch_id=NULL`，没有持久化捕获批次。根因是非 Spring Runner 手工 `new` 捕获服务绕过 `@Transactional` 代理；当前修复分支 `codex/1.4.0-stage-3ar3b-f1f-b2-transaction-fix` 只为 F1E 捕获增加绑定专用 DataSource 的显式事务，待实际 Git 复验且尚未合入。本修复 Provider 新增调用为 0，Tushare 累计真实业务请求为 23。
- 3A-R3B-F2A 任务书 / 阶段记录：[tasks/3ar3b-f2a-research-preview-product.md](tasks/3ar3b-f2a-research-preview-product.md) / [stage-3ar3b-f2a-research-preview-product.md](stage-3ar3b-f2a-research-preview-product.md)。
- 3A-R3B-F2A-R1 任务书 / 阶段记录：[tasks/3ar3b-f2a-r1-preview-ux-convergence.md](tasks/3ar3b-f2a-r1-preview-ux-convergence.md) / [stage-3ar3b-f2a-r1-preview-ux-convergence.md](stage-3ar3b-f2a-r1-preview-ux-convergence.md)。
- 3A-R3B-F2A-R1A 任务书 / 阶段记录：[tasks/3ar3b-f2a-r1a-visual-semantics-fix.md](tasks/3ar3b-f2a-r1a-visual-semantics-fix.md) / [stage-3ar3b-f2a-r1a-visual-semantics-fix.md](stage-3ar3b-f2a-r1a-visual-semantics-fix.md)。
- 3A-R3B-F2A-GATE 任务书 / 阶段记录：[tasks/3ar3b-f2a-product-preview-gate-pass.md](tasks/3ar3b-f2a-product-preview-gate-pass.md) / [stage-3ar3b-f2a-product-preview-gate-pass.md](stage-3ar3b-f2a-product-preview-gate-pass.md)。
- 3A-R3B-TRACK-B0 任务书 / 阶段记录：[tasks/3ar3b-track-b0-provider-route-decision.md](tasks/3ar3b-track-b0-provider-route-decision.md) / [stage-3ar3b-track-b0-provider-route-decision.md](stage-3ar3b-track-b0-provider-route-decision.md)；配套证据见 [候选矩阵](track-b-provider-candidate-matrix.md)、[证据登记册](track-b-provider-evidence-register.md)、[成本模型](track-b-provider-cost-model.md)、[许可请求包](track-b-permission-request-pack.md)、[F1 准入合同](track-b-f1-entry-contract.md)和[试用探针合同](track-b-trial-probe-contract.md)。
- 3A-R3B-TRACK-B1 任务书 / 阶段记录：[tasks/3ar3b-track-b1-tushare-probe-review.md](tasks/3ar3b-track-b1-tushare-probe-review.md) / [stage-3ar3b-track-b1-tushare-probe-review.md](stage-3ar3b-track-b1-tushare-probe-review.md)。
- 3A-R3B-F1A 任务书 / 阶段记录：[tasks/3ar3b-f1a-tushare-limited-personal-adapter.md](tasks/3ar3b-f1a-tushare-limited-personal-adapter.md) / [stage-3ar3b-f1a-tushare-limited-personal-adapter.md](stage-3ar3b-f1a-tushare-limited-personal-adapter.md)。
- 3A-R3B-F1B 任务书 / 阶段记录：[tasks/3ar3b-f1b-tushare-technical-contract.md](tasks/3ar3b-f1b-tushare-technical-contract.md) / [stage-3ar3b-f1b-tushare-technical-contract.md](stage-3ar3b-f1b-tushare-technical-contract.md)。
- 3A-R3B-F1C 任务书 / 阶段记录：[tasks/3ar3b-f1c-tushare-reduced-runtime.md](tasks/3ar3b-f1c-tushare-reduced-runtime.md) / [stage-3ar3b-f1c-tushare-reduced-runtime.md](stage-3ar3b-f1c-tushare-reduced-runtime.md)。
- 3A-R3B-F1D 任务书 / 阶段记录：[tasks/3ar3b-f1d-tushare-written-permission-closure.md](tasks/3ar3b-f1d-tushare-written-permission-closure.md) / [stage-3ar3b-f1d-tushare-written-permission-closure.md](stage-3ar3b-f1d-tushare-written-permission-closure.md)。
- 3A-R3B-F1E 任务书 / 阶段记录：[tasks/3ar3b-f1e-dedicated-research-runtime.md](tasks/3ar3b-f1e-dedicated-research-runtime.md) / [stage-3ar3b-f1e-dedicated-research-runtime.md](stage-3ar3b-f1e-dedicated-research-runtime.md)。
- 3A-R3B-F1F-A 任务书 / 阶段记录：[tasks/3ar3b-f1f-controlled-acceptance-preparation.md](tasks/3ar3b-f1f-controlled-acceptance-preparation.md) / [stage-3ar3b-f1f-controlled-acceptance-preparation.md](stage-3ar3b-f1f-controlled-acceptance-preparation.md)。
- 3A-R3B-F1F-B1 任务书 / 阶段记录：[tasks/3ar3b-f1f-b1-controlled-acceptance-executor.md](tasks/3ar3b-f1f-b1-controlled-acceptance-executor.md) / [stage-3ar3b-f1f-b1-controlled-acceptance-executor.md](stage-3ar3b-f1f-b1-controlled-acceptance-executor.md)。
- 3A-R3B-F1F-B2-RUNNER 任务书 / 阶段记录：[tasks/3ar3b-f1f-b2-controlled-runner.md](tasks/3ar3b-f1f-b2-controlled-runner.md) / [stage-3ar3b-f1f-b2-controlled-runner.md](stage-3ar3b-f1f-b2-controlled-runner.md)。
- 3A-R3B-F1F-B2-DBPREP 任务书 / 阶段记录：[tasks/3ar3b-f1f-b2-database-preparation.md](tasks/3ar3b-f1f-b2-database-preparation.md) / [stage-3ar3b-f1f-b2-database-preparation.md](stage-3ar3b-f1f-b2-database-preparation.md)；首次冻结记录：[stage-3ar3b-f1f-b2-freeze.md](stage-3ar3b-f1f-b2-freeze.md)。
- 当前正式状态：`F0_AUDIT_RESULT=PARTIAL`、`FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST`、`FREE_PRODUCT_PREVIEW_GATE=PASS`、`FREE_PROVIDER_VALIDATION_GATE=BLOCKED`、`PAID_PROVIDER_UPGRADE_DECISION=PENDING`、`IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`。Track A 的免费研究预览产品形态验证已经完成；该 PASS 不改变 Provider、PIT、效果、Shadow、付费数据或交易资格。F1A—F1E、F1F-A、F1F-B1、F1F-B2-RUNNER 与 DBPREP 均已验收合入。F1D 已把当前个人研究书面许可闭环为 PASS，但完整技术合同仍有十项阻断，因此 `F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`、`fullF1EntryReady=false`。首次真实 F1F-B2 为 `CONTROLLED_ACCEPTANCE_STATUS=FAILED_DATABASE_GUARD`，对应 ID 已永久封存；由于捕获事务守卫安全拒绝，`REDUCED_RESEARCH_OPERATIONAL_READY=false`。Tushare 累计真实业务请求为 23（B1 为 10、F1A 为 10、本次真实受控验收为 3），本事务修复阶段新增调用为 0，iFinD 真实调用数为 0；正常业务库 V13 未执行，F2B/F3、3A-R3B-1 均未开始，Day 002 未创建，scheduler 关闭，3B 未开始。
- `master`：`27d9099 chore: checkpoint Stock Quant Pro 1.3.1 and remove tracked cache`
- 版本号仍保持 `1.3.1`；尚未发布 `1.4.0`。

## 权威文档职责

- 仓库根目录 [AGENTS.md](../../AGENTS.md)：双窗口开发、安全、ChatGPT 实际提交验收和用户 merge 批准规则。
- `CURRENT_STATE.md`：当前进度、真实能力、阻断和下一阶段入口的唯一事实来源。
- [DECISIONS.md](DECISIONS.md)：跨阶段稳定架构与治理决策。
- [ROADMAP.md](ROADMAP.md)：阶段方向、顺序、依赖和验收门槛，不构成连续开发授权。
- [PROGRESS_LOG.md](PROGRESS_LOG.md)：已完成阶段的提交、测试、验收和 merge 批准证据索引，不承担当前状态或完整路线图职责。
- `tasks/`：候选大阶段或内部工作包的范围和验收任务书；任务书存在不表示业务实现已开始。

## 已完成并验收

- 1D-1：六智能体跨语言契约与编排骨架。
- 1D-2：Java 权威任务、事务、异步执行、真实 Python 调用与 PostgreSQL 闭环。
- 1D-3：证据、评分、正式否决、总控一致性、合法持久化与非法响应原子失败。
- 1D-4：Vue 工作台、正式否决查询、本地安全启停和端到端验收。
- 阶段 2A 前置：Agent PostgreSQL 集成测试已完成任务级隔离治理，共享非空专用测试库不再要求五张 Agent 表全局为空。
- 阶段 2A：从现有 PostgreSQL 接入 `security`、`marketData`、`technicalMetrics` 和 `dataQualityContext` 四类只读上下文；完成只读一致性事务、Java 确定性指标、数学数值 Hash 规范化和 JSONB 往返验收。
- 阶段 2B：基于阶段 2A 四类冻结事实完成确定性 DATA_QUALITY 规则门禁、版本化跨语言契约、Java 双重校验和真实 PostgreSQL/Java/Python 持久化闭环验收。
- 阶段 2C：从现有 PostgreSQL 接入 `marketBreadth` 和 `scanResult` 两类只读研究上下文，并将 `backtestContext` 冻结为结构化安全不可用；完成统一时点规则、扫描任务稳定选择、字段白名单、Hash/JSONB 及真实 PostgreSQL 闭环验收。
- 阶段 2D-1：当前证券池市场宽度状态规则已完成实现、自动化回归、真实 PostgreSQL/Python/Java 闭环和独立验收。
- 阶段 2D-2A：历史事实版本与交易日历基础模型已完成开发、真实 PostgreSQL 闭环、独立审查、修复和复审；建立了 dataset 版本、不可变证券状态事件、双时间证券状态历史、SSE/SZSE 版本化交易日历和 Java as-of 查询基础。
- 阶段 2D-2B-1A：来源无关摄取基础已完成并合入；V7 建立通用 ingestion run、security/calendar immutable raw、run-record 关联、terminal processing attempt、retry、namespace、assurance、封存和 `INGESTION_MANIFEST_V1`。
- 阶段 2D-2B-1B-0：已冻结 TEST/DEMO security raw 到 V1 event 的稳定身份、active 语义、attempt/result/event 基数、normalization outcome、event root/lineage、Manifest V2、并发与原子失败契约；本阶段只有文档，没有生产实现。
- 阶段 2D-2B-1B-1：TEST/DEMO security event materialization foundation 已完成、通过独立 GitHub 复审并合入；V8 实现 `manifestContractVersion`、TEST/DEMO 稳定证券身份、显式 source identity mapping、`SECURITY_STATUS_RAW_TEST_V1`、V1 event 物化与复用、normalization result、唯一 event lineage、`INGESTION_MANIFEST_V2_SECURITY_EVENT`、Java/PostgreSQL 双重门禁，以及幂等、并发和原子失败保护。resolved event 在 2D-2B-2 前仍被禁止进入 history。
- 阶段 2E-1：确定性 TECHNICAL_ANALYSIS V1 已完成、通过独立 GitHub 最终复审并合入；只解释冻结的 `technicalMetrics` 与 `marketData`，形成五类固定 finding，通过 Java/Python 双重校验并受 DATA_QUALITY 门禁约束，不产生正式 veto，总控仍保持安全不足状态。
- 阶段 2F：可靠回测基础与 STRATEGY_BACKTEST 确定性规则 V1 已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准 fast-forward 合入；V9 建立 append-only PIT 日线观察，精确 profile 提供可靠 `backtestContext`、canonical 三领域 Hash、冻结回测策略和五类确定性 finding。STRATEGY_BACKTEST 不产生正式 veto。
- 阶段 2H：可靠模拟持仓上下文与 POSITION_RISK 正式否决 V1 已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准 fast-forward 合入；精确 profile 只读冻结默认模拟账户当前状态，POSITION_RISK 是唯一能够生成正式 veto 的专业智能体。该能力不接入真实账户，不修改模拟账户业务表，也不生成交易执行指令。
- 阶段 2G：研究级 AKShare/CNINFO 公告事实基础与 ANNOUNCEMENT_RISK 确定性规则 V1 已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准纯 fast-forward 合入；V10 建立 append-only 公告捕获批次与观察版本，精确 profile 提供 as-of `securityEvents`，冻结标题规则、短语级排除、研究来源 confidence 和 coverage/event evidence。ANNOUNCEMENT_RISK 不产生正式 veto，来源不具备 FORMAL/PIT 资格。
- 阶段 2I：确定性总控综合决策 V1 已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准纯 fast-forward 合入；精确规则版本复用 2G 组合上下文，以固定权重和优先级形成可由 Java 独立复算的五种确定结果或安全不足结果。总控不是第七个 run，不产生独立 finding/evidence，也不生成投资建议或交易指令。
- 阶段 3A-1：受控影子运行与就绪度观测基础 V1 已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准纯 fast-forward 合入；V11 建立 shadow batch、item、append-only review、结构化 reason、漂移、指标、取消和熔断控制面。功能和 scheduler 仍默认关闭；该技术基础不等于完整 3A 长期观察已经完成。
- 阶段 3A-R1：Flyway V6 迁移血统恢复已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准纯 fast-forward 合入；仓库 V6 已恢复为 checksum `-981595186` 的已应用历史内容，V12 只前向承接后来合法 delta，数据库集成测试已强制使用随机隔离 Schema。该修复不改变 Shadow 或 Agent 规则。
- 阶段 3A-R3A：可验证 PIT 市场原始事实 V2 设计冻结已完成、通过 ChatGPT 对实际 Git 提交的验收并经用户批准纯 fast-forward 合入；冻结四类 append-only 事实、Provider 资格分级、`DAILY_EXACT` QFQ as-of 和 18 个黄金场景。该阶段只有设计，不包含 Provider 接入、生产实现、迁移或数据库写入。
- 阶段 3A-R3B-0：Provider 中立 PIT 市场事实 V2 离线闭环已完成、通过 ChatGPT 对实际 Git 提交的最终复验并经用户批准纯 fast-forward 合入；V13、四类 append-only 事实、Provider 中立契约、TEST/DEMO Mock、`DAILY_EXACT` QFQ、2F V2、六智能体 V2 和 EXPLICIT Mock Shadow 已进入集成分支。正常业务库尚未执行 V13，Mock 不取得真实 Provider 资格。

阶段 2D-1、2D-2A、2D-2B-1A、文档阶段 2D-2B-1B-0 和 TEST/DEMO 实现阶段 2D-2B-1B-1 完成不等于完整阶段 2D、完整阶段 2D-2 或完整阶段 2D-2B 完成；这些上位阶段仍处于进行中。已完成内容是基础设施、只读事实上下文、数据质量门禁、受限的当前证券池宽度状态规则、时态事实基础、来源无关摄取基础、事件物化契约及 TEST/DEMO event 物化基础。当前 event 物化能力不包含正式来源、FORMAL/PIT、history/calendar projection、Universe、完整六智能体分析、完整市场环境模型、历史无前视市场宽度、真实股票分析或投资建议能力。

## 当前状态、门禁与规划入口

Day 001 已在正常业务库形成 1 个真实受控 Shadow 批次和 3 个 item，三项均以
`BLOCKED_BY_DATA_QUALITY` 终结；正式人工复核已追加。该安全结果没有被重跑或修改规则。
对 `000001`、`000002`、`000006` 的受控行情更新随后消除了
`MARKET_DATA_TOO_STALE`，形成 3 个本地观察批次和 780 条 V9 日线观察，但全部
`sourceRevision=NULL`。scheduler 仍关闭，Day 002 没有创建。

只读来源链审计确认，当前 Controller → Java 行情服务 → Python AKShare/Tencent →
`List<Bar>` → `MarketDataPersistenceService` → V9 的响应只传递行情值和
`dataSource`；普通持久化入口固定传入 `sourceRevision=null`，本地
`LOCAL_DATASET_V1-<UUID>` 不能代表供应商 revision。`000001` 两次受控响应的内容和
Tencent `version=18` 虽一致，但没有正式字段语义、修订关系或旧版本证据，当前资格结论
固定为 `PROVIDER_REVISION_UNVERIFIED`，不得接入 2F V1。

3A-R3A 设计、3A-R3B 里程碑规划和 3A-R3B-0 Provider 中立离线实现均已验收并合入。
V13 已进入代码库，建立四类独立 append-only 市场事实；Java 已具备类型化 Provider 契约、
canonical/as-of Repository 和 `QFQ_AS_OF_ENGINE_V1`，精确 V2 ruleVersion 提供
`BACKTEST_CONTEXT_V2`、六智能体和 TEST/DEMO EXPLICIT Mock Shadow 闭环。正常业务库尚未
执行 V13，因此不得把代码集成状态写成正常业务库已经具备 V13 结构或真实 V2 数据。

Mock Provider 只使用合成固定夹具，不取得真实 Provider、FORMAL 或
`PROVIDER_PIT_VERIFIED` 资格；iFinD Adapter 仍是默认禁用且网络前失败的骨架，没有安装 SDK、
没有凭据、没有真实调用。项目现在采用“免费数据完成产品与效果验证，证明价值且数据成为
主要瓶颈后再评估付费 Provider”的路线。iFinD 是后置专业化升级候选，不再是直接下一阶段。

当前正式状态为：

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

3A-R3B 免费优先治理规划与 F0 审计均已通过验收并合入。F0 结论为
`F0_AUDIT_RESULT=PARTIAL`：BaoStock 0.9.3 的两只证券 raw/QFQ
短日线各观察到 6 行，通用交易日历观察到 8 行，公司行动观察到 1 行，按证券因子在
固定短区间各观察到 0 行；修复前 collector 未在迭代后复核 Provider 终态，因此本次
Live response completeness 统一为 `UNVERIFIED`。全市场单日因子按边界未调用；独立
因子只能评为 `PARTIAL`，
`DAILY_EXACT=UNVERIFIED`。客户端 BSD License 不等于底层数据许可，BaoStock 当前角色
为 `PENDING_WRITTEN_PERMISSION`。AKShare 必须按 Tencent、Sina、Eastmoney、CNINFO
上游拆分并保持 `RESEARCH_AUXILIARY_ONLY`；CNINFO/SSE/SZSE/SZSI 只承担
`OFFICIAL_EVIDENCE_ONLY`。当前没有一个免费来源能单独承担完整 V13/QFQ 同源 lineage。

F0 最终提交 `059eacffaf7e4a9f383be205d453c5168279932a` 已通过 ChatGPT 实际 Git
复验，经用户批准纯 fast-forward 合入；该 `PARTIAL` 结论不是失败，也不批准 F1。F0
唯一实际 Provider Live 探针执行 8 个 BaoStock 数据逻辑调用和 2 个匿名登录/退出公开操作；
socket 级协议请求/frame 数未观测，固定为 `UNVERIFIED`，不得从函数调用数推断。
Provider HTTP 请求为 0，原始响应残留为 0。没有新增 AKShare Live 调用，没有
调用 iFinD，没有访问数据库或执行正常业务库 V13。

F0.5 已验收并合入，正式冻结 `FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST` 和
双轨路线。轨道 A 的完整产品代码链为 F2A
`f5137e2422a48e70d5c706cb146fb034a2b96f65`、R1
`e2b9457e3594676875167a703ae09ebc75aaaaf6`、R1A
`99b22a5e3bd2ad945c2f2b10ae79618277f8ed01` 与
`11657c572d9561ae3b4a37be7a22f7456444844f`、R1B
`4917bbabc8262106abb47e6cb90cf7ab96e76d7d`，均已通过 ChatGPT 实际 Git 验收并经用户
批准纯 fast-forward 合入。`2026-07-29` 首次视觉验收的历史结论为 `BLOCKED`；同日 R1
复验部分通过但当时仍为 `BLOCKED`；R1A 完成数据语义与风险颜色修复后，首屏重叠仍在该轮
阻断；R1B 最终解决垂直流布局。用户基于 R1B 最终基线完成 DEMO01 与 DEMO02 复验，并于
`2026-07-29 16:44 +08:00` 明确认可产品形态，因此当前
`FREE_PRODUCT_PREVIEW_GATE=PASS`，Track A 的产品形态验证完成。页面只读使用既有
扫描/Agent GET API，严格隔离
`EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED` 与
`TEST_DEMO_EXPLICIT`，不重算权威结果、不静默切换 Demo、不创建任务或写数据库。轨道 B
继续解决书面许可、独立因子、交易所日历身份、公司行动版本和 knowledge-time，再依次
进入 F1、F2B 与 F3。产品门 PASS 只表示用户认可信息架构、产品形态和日常只读研究流程，
不得被写成 Provider 资格、PIT/QFQ、准确率、收益、Shadow、投资建议或交易证据，也不
自动启动任何后续阶段。F1、F2B、F3 和 3A-R3B-1 均未开始，Day 002 未创建，
scheduler 关闭，3B 未开始。

Track B0 与 B1 已通过实际 Git 最终复验并纯 fast-forward 合入。三候选精确为 BaoStock、
Tushare Pro 和同花顺 iFinD；主要路线为
`TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST`（Tushare Pro），备用路线为
`TRACK_B_FALLBACK_ROUTE=IFIND`。Tushare Pro 的同平台 raw daily、独立 adjustment factor、
SSE/SZSE trade calendar 和 dividend 等公开 API 使其成为个人开发现实下最接近 V13/QFQ
闭环的候选；公司行动完整性、稳定身份生命周期和 revision/published/历史版本语义仍需
后续受控证据。

用户已开通 Tushare 2000 积分权限。`2026-07-30` 的固定两证券、两交易日、10 次无重试
探针全部 `PASS`，验证了 raw daily、adjustment factor、SSE/SZSE calendar、stock_basic
普通身份字段、dividend 公开字段和最小 `DAILY_EXACT`。该技术权限 PASS 本身不是用途许可：
精确执行时刻为 `UNKNOWN`，完整证据探针仍为 `PARTIAL_NOT_COMPLETE`；执行前没有取得
Provider 对最小自动 API 探针和响应留存/删除边界的两项书面答复，故完整合同法律前置为
`NOT_MET`，两项书面许可均为 `UNVERIFIED`。该状态不判定本次调用合法或违法，也不要求
重新执行 10 次请求。
此后 Tushare 官方企业微信于 `2026-07-30` 书面确认“可以用来当量化数据来源”；F1A
当时没有逐项确认个人本地长期存储、策略回测和智能体分析。`2026-07-31 11:07 +08:00`
收到用户提供的官方七项逐条精确转录，明确允许个人 2000 积分账号本地数据库保存、回测/
历史回放、本地 AI/Agent、程序自动调用/定时更新、字段结构/Hash/摘要/错误日志留存及
持续本地保存。因此当前 `personalResearchPermissionComplete=true`、
`providerWrittenPermissionComplete=true`、`WRITTEN_PERMISSION_GATE=PASS`。原始数据
再分发、商业数据服务和 Token/账号共享继续为 `NOT_GRANTED`。
Tushare 当前仍为 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`，稳定证券 ID 为 `PARTIAL`；
公司行动完整覆盖/稳定事件 ID/factor 解释关系、revision/snapshot/published/update/旧版本、
永久证券身份和全历史 `DAILY_EXACT` 继续缺证。`BLOCKED_COST_APPROVAL` 与
`BLOCKED_WRITTEN_PERMISSION` 均已解除；当前
`F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`，唯一粗粒度阻断为
`BLOCKED_TECHNICAL_EVIDENCE`。F1A/F1B/F1C 均已合入；F1D 只关闭许可门，不批准
生产、正常业务库、F2B 或 F3。

完整 3A 仍要求不少于 20 个有效观察日、200 个 shadow item、主要 reasonCode 人工复核、
持续业务表只读证明和正式观察报告；当前只有 1 个观察日和 3 个 item。

## 权威边界与真实可用能力

Java 是 `taskId`、六个 `runId`、状态、幂等与缓存、持久化和跨语言响应校验的唯一权威。Python 无状态，只处理 Java 传入的只读 `contextSnapshot`，不访问任务数据库。PostgreSQL 已包含 task、run、evidence、veto、decision 五类持久化结构。Vue 可创建、轮询、恢复并展示任务。本地脚本可安全启动、复用和精确停止 Python、Java、Vue。

当前集成分支真实可用的是 Java 权威任务和持久化、阶段 2A 第一批四类上下文、确定性技术指标、确定性 DATA_QUALITY 门禁、`marketBreadth` 只读事实、`scanResult` 历史扫描事实、阶段 2D-1 受限的当前证券池宽度状态规则、阶段 2E-1 确定性 TECHNICAL_ANALYSIS V1、阶段 2F 可靠回测基础与确定性 STRATEGY_BACKTEST V1、阶段 2H 可靠模拟持仓上下文与确定性 POSITION_RISK V1、阶段 2G 研究级公告事实与确定性 ANNOUNCEMENT_RISK V1、阶段 2I 确定性总控综合决策 V1、阶段 3A-1 默认关闭的受控 Shadow 控制与观测基础、V6 时态事实基础、V7 来源无关摄取基础、V8 TEST/DEMO security event 物化基础、V9 append-only PIT 日线观察、V10 append-only 公告观察、V11 Shadow 事实、V12 迁移血统前向修复，以及已合入代码库但尚未迁移正常业务库的 V13 Provider 中立 PIT 市场事实 V2 离线能力。V13 包含四类事实、Provider 中立契约、TEST/DEMO Mock、`DAILY_EXACT` QFQ、2F V2、六智能体 V2 和 EXPLICIT Mock Shadow；它不等于真实 Provider 已接入或正常业务库已部署。V8 已实现 run 创建时冻结的 `manifestContractVersion`、TEST/DEMO 稳定证券身份与显式 source identity mapping、`SECURITY_STATUS_RAW_TEST_V1`、`SECURITY_STATUS_EVENT_V1` 物化与严格复用、每 terminal attempt 唯一 normalization result、每逻辑 event 唯一 lineage、`INGESTION_MANIFEST_V2_SECURITY_EVENT`、Java/PostgreSQL 双重门禁，以及幂等、两个 backend 并发和原子失败保护。FORMAL/PIT 继续由数据库门禁拒绝，resolved event 在 2D-2B-2 前不得进入 `security_status_history`；当前尚未形成正式 history/calendar projection 或 Universe。ANNOUNCEMENT_RISK 的 AKShare/CNINFO 来源仍只具备 `RESEARCH` 资格，不声明正式授权、历史绝对完整或 PIT。

精确 2I 规则版本启用 `CHIEF_DECISION_V1/CHIEF_SCORE_WEIGHTS_V1`；总控只读取六个专业
run，不成为第七个 run，不产生独立 finding/evidence，并由 Java 在持久化前独立复算。
POSITION_RISK 正式 veto 仍具最高优先级和唯一正式否决权；其后依次为 DATA_QUALITY
阻断、必要 run 不足和完整输入综合分类。当前普通数据仍可能因 MARKET_REGIME、可靠回测
样本、公告覆盖或持仓上下文门禁而安全返回 `INSUFFICIENT_DATA`，不得把规则能力描述为
当前任意请求必然可以形成综合分类，也不具备投资建议或自动交易能力。

## 九类 contextSnapshot 实际状态

| 上下文 | 当前状态 |
|---|---|
| `security` | 已从现有 PostgreSQL `securities` 只读接入；该表不是历史版本表，不保证请求交易日时点的证券属性 |
| `marketData` | 已从现有 PostgreSQL `daily_bars` 接入截止请求日期的 QFQ 日线，最多读取最近61条 |
| `marketBreadth` | 已由 Java 在同一只读事务内基于当前 MAIN、active、非 ST 证券及 QFQ 日线确定性生成；统一有效日期和前一有效日期；阶段 2D-1 仅将其用于当前日期受限宽度状态。证券池不是历史版本，`pointInTimeGuaranteed=false`、`universePointInTimeGuaranteed=false`、`futureDataExcluded=false`，历史日期不能进行无前视分类 |
| `scanResult` | 已从已完成、正式、FULL 扫描任务中只读接入；按交易日、完成时间和 ID 稳定选择；只输出白名单事实，不输出推荐字段；生产输入截止日期和算法版本不可完全证明 |
| `technicalMetrics` | 已由 Java 基于同一事务冻结的本地 QFQ 日线，使用 `JAVA_INDICATORS_V1` 确定性计算 |
| `backtestContext` | 旧规则继续以 `BACKTEST_INPUT_CUTOFF_UNVERIFIABLE` 安全不可用；集成分支的精确 2F/2H/2G/2I 规则版本选择可靠 profile。只有本地 PIT 观察属于周一至周五、`firstObservedAt` 与 `knownAt` 均不早于该交易日上海时间 15:00，并同时满足日期/knowledge cutoff、source revision、lineage、Hash、版本、120 条最低窗口和输入校验时才可用。当前普通配置源 revision 为 `null`，因此实际仍以 `BACKTEST_SOURCE_REVISION_UNVERIFIABLE` 安全不可用 |
| `securityEvents` | V8 建立 TEST/DEMO 证券状态摄取侧 event 物化基础；集成分支的精确 2G/2I 规则版本通过 `AGENT_CONTEXT_2G_V1/SECURITY_EVENTS_CONTEXT_V1` 接入研究级公告观察。只有 180 日范围完整、`complete=true`、截至 knowledge cutoff 可见且不超过 24 小时的正确来源批次才可用，完整 0 公告批次可形成 `available=true, events=[]`；来源仍非 FORMAL/PIT |
| `portfolioContext` | 集成分支的精确 2H/2G/2I 规则版本选择 `AGENT_CONTEXT_2H_V1/PORTFOLIO_CONTEXT_V1`，通过 Agent 专用只读 Repository 冻结 `accountId=1` 当前模拟账户事实。只支持上海时区当前自然日，`historicalPointInTimeGuaranteed=false`；历史/未来日期、非法账户事实、缺失或超过 7 天的本地 QFQ 估值均安全不可用 |
| `dataQualityContext` | 已生成只读数据质量事实；不包含评分、规则门禁、决策或否决，数据库查询正常时即使证券和日线缺失仍可用 |

## 六智能体与总控实际状态

固定专业智能体为 `DATA_QUALITY`、`MARKET_REGIME`、`TECHNICAL_ANALYSIS`、`STRATEGY_BACKTEST`、`ANNOUNCEMENT_RISK`、`POSITION_RISK`。总控不是第七个 run。

阶段 2B 升级 DATA_QUALITY：无效上下文映射为 `INSUFFICIENT_DATA/BLOCKED/REJECT/0/0`，有效阻断为 `COMPLETED/BLOCKED/REJECT/0/100`，有效警告为 `COMPLETED/WARN/WARN/50/100`，有效通过为 `COMPLETED/PASS/PASS/100/100`，且 `veto` 始终为 `false`。阶段 2D-1 的团队规则版本为 `1.4.0-stage-2d-market-regime-v1`，DATA_QUALITY 完整复用阶段 2B 规则语义。

阶段 2D-1 的 MARKET_REGIME 只使用冻结的 `marketBreadth`，`marketData`、`technicalMetrics` 和 `scanResult` 均不参与分类、score、confidence、finding、evidence 或 summary 推断。当前日期由冻结 `requestedAt` 转换到 `Asia/Shanghai` 后的自然日确定；只有 `coverageRatio=1.00000000` 且 `comparableSymbolCount>=2` 等全部资格条件满足时才进行正向、混合或负向宽度状态分类。score 使用确定性宽度公式，仅描述当前证券池上涨和下跌数量平衡；confidence 固定为0。MARKET_REGIME 不得产生正式 veto。

阶段 2E-1 的 TECHNICAL_ANALYSIS 只解释 Java 已冻结的 `technicalMetrics` 与 `marketData`，不拉取行情、不连接业务数据库，也不在 Python 隐式重算 SMA、RSI、ATR 或其他指标。有效输入固定形成趋势、RSI、相对 MA20 偏离、相对波动、指标确认/冲突五类 finding；score 从 50 开始累加确定性影响后截断到 `[0,100]`。DATA_QUALITY 为 PASS 时 TECHNICAL_ANALYSIS gate/confidence 为 `PASS/100`，为 WARN 时为 `WARN/50`；DATA_QUALITY 阻断时不得形成技术 evidence、finding 或正常评分。输入非法时以 `TECHNICAL_ANALYSIS_INPUT_INVALID` 安全降级，不伪造中性结论。完整规则见 [stage-2e1-technical-analysis-v1.md](stage-2e1-technical-analysis-v1.md)。

阶段 2F 的 STRATEGY_BACKTEST 只解释 Java 生成的可靠回测事实。DATA_QUALITY
BLOCKED 时继承阻断；上下文不可用、输入非法或交易样本不足时不得形成正常性能
评分。有效输入固定生成样本充分性、总收益、最大回撤、胜率与盈亏比、跨时间
子区间稳定性五类 finding；score 从 50 按冻结阈值调整并截断至 `[0,100]`，
confidence 只可为 40、60、80，DATA_QUALITY WARN 时最高 50。完整规则见
[stage-2f-strategy-backtest-v1.md](stage-2f-strategy-backtest-v1.md)。

阶段 2H 的 POSITION_RISK 只解释 Java 冻结的 `portfolioContext`，即使
DATA_QUALITY 为 BLOCKED，只要组合上下文有效仍继续评估账户风险。它按账户回撤、
当日损失、持仓数量、当前/预计集中度、止损和移动止损的冻结顺序生成正式 veto；
无 veto 时固定生成五类 finding 并按阈值计算 safety score 和自身完整性 confidence。
目标位只产生警告，不产生 veto。完整规则见
[stage-2h-position-risk-v1.md](stage-2h-position-risk-v1.md)。

集成分支的 ANNOUNCEMENT_RISK 只解释 Java 冻结的研究级 `securityEvents`，按标题
确定性规则生成五类 finding、研究证据、`[0,100]` score 和固定 40 confidence，
不调用网络、数据库、PDF 或 LLM，也不产生正式 veto。旧 2G 规则版本的总控仍固定为：
正式 veto 优先于 DATA_QUALITY 阻断；两者均不存在时保持 `INSUFFICIENT_DATA/0/0`。
TECHNICAL_ANALYSIS、STRATEGY_BACKTEST 与
ANNOUNCEMENT_RISK 均不产生正式 veto，POSITION_RISK 是唯一正式否决权。

集成分支的精确 2I 总控严格按正式 POSITION_RISK veto、DATA_QUALITY 阻断、必要
run 不足、完整输入综合的顺序执行。正常综合只使用 TECHNICAL_ANALYSIS 25、
STRATEGY_BACKTEST 35、ANNOUNCEMENT_RISK 20、POSITION_RISK 20 的固定权重；
DATA_QUALITY 只作门禁和 confidence 上限，MARKET_REGIME V1 权重为 0 但必须为合法
终态。完整输入可形成 `RESEARCH_ONLY`、`WATCH` 或 `PASS_TO_MANUAL_REVIEW`；后者只表示
进入人工研究复核。总控不是第七个 run，不创建 finding/evidence，不改变专业阈值，
也不构成投资建议或交易信号。

## 数据库、前端与本地运行

- 集成分支代码迁移链当前为 Flyway V1 至 V13；正常业务库仍只执行至 V12。V6 新增 dataset 版本、证券状态事件、双时间证券状态历史和 SSE/SZSE 版本化交易日历；V7 新增来源无关 ingestion run、security/calendar raw、run-record 关联、terminal attempt、retry、namespace、assurance、封存与 Manifest V1；V8 新增 `manifestContractVersion`、TEST/DEMO 稳定证券身份及显式来源映射、normalization result、event lineage、Manifest V2 和相应数据库不可绕过门禁；V9 新增 append-only PIT 日线观察；V10 新增 append-only 公告捕获批次与观察版本；V11 新增 shadow batch、item、append-only review 和 `SHADOW` TriggerType；V12 前向承接原本被错误追加回 V6 的时态表不可变保护、knowledge-close 门禁与旧日历导航列删除；V13 在代码库中新增 Provider 中立 PIT 市场事实 V2，但尚未迁移正常业务库。V6/V7 均不回填现有 `securities` 或 `daily_bars`，V8 不接入正式来源，V9/V10/V13 不伪造历史 known time。
- 阶段 2A 使用 Agent 专用只读 Repository 查询 `securities` 和截止请求日的 QFQ `daily_bars`；四类上下文在 `REPEATABLE_READ` 只读事务中冻结，不执行市场数据同步或数据库写操作。
- 阶段 2C 未修改 Flyway 或外层 JSON Schema，`CONTEXT_SCHEMA_VERSION` 仍为 `1.0`。
- `marketBreadth`、`scanResult` 与阶段 2A 四类上下文在同一个 `REPEATABLE_READ` 只读事务内冻结；Python 始终不直连数据库。旧 profile 的 `backtestContext` 不运行 `BacktestEngine`；仅 2F 精确 profile 由 Java 使用 PIT 观察事实运行冻结引擎。
- 阶段 2D-1 未修改 Flyway、外层 JSON Schema、`CONTEXT_SCHEMA_VERSION`、contextHash算法或数据库写模型。
- 阶段 2E-1 同样未修改 Flyway、V1 至 V8、外层 JSON Schema、`CONTEXT_SCHEMA_VERSION`、contextHash 算法或数据库持久化结构；Java 继续在单事务持久化前完成独立响应校验。
- 阶段 2F 的 V9 新增 `market_data_observation_batches` 与 `daily_bar_observations`，两表均由数据库触发器禁止 `UPDATE`、`DELETE`、`TRUNCATE`；持久化入口和数据库均拒绝周末日线，V9 还在数据库层拒绝交易日上海时间 15:00 前的 `first_observed_at`/`known_at`。`daily_bars` 保留为当前态兼容投影；工作日收盘前当日日线不会进入可靠 PIT 观察，也不会创建空观察批次，但可继续按原业务更新该兼容投影。成功合格 PIT 捕获与当前态更新位于同一事务；迁移不回填历史 known time，也不修改 V1 至 V8。本地验收只在随机临时 Schema 应用 V9，没有向专用测试库 public 应用迁移。
- 阶段 2F 只对精确规则版本启用 `AGENT_CONTEXT_2F_V1/BACKTEST_CONTEXT_V1`；外层 `CONTEXT_SCHEMA_VERSION`、旧入口、旧规则 contextSnapshot/contextHash/cache key 与六 run 结构保持兼容。Java 使用 `BACKTEST_CANONICAL_V1` 生成三个领域 Hash，Python 不访问数据库或重跑回测。
- 阶段 2H 不新增 Flyway；只对精确规则版本启用 `AGENT_CONTEXT_2H_V1/PORTFOLIO_CONTEXT_V1`，并继续复用可靠 `backtestContext`。Agent 专用 Repository 在同一 `REPEATABLE_READ` 只读事务读取现有模拟账户、持仓、待确认委托、权益快照、设置与本地 QFQ 估值，不调用 `PortfolioService` 写路径，不修改任何模拟账户业务表。旧 profile/contextHash 和六 run 结构保持兼容。
- 阶段 2G 的 V10 新增 `announcement_capture_batches` 与 `announcement_observations`；两表由数据库触发器拒绝 `UPDATE`、`DELETE`、`TRUNCATE`。Java 在 Provider 外部调用结束后冻结 `observedAt`，以 `knownAt=firstObservedAt` 保存日期级研究公告事实；同内容幂等，内容变化及 A→B→A 追加新版本。完整空批次保留覆盖证据，部分批次不证明无事件。V10 不修改 V1 至 V9，不向历史回填 knowledge-time，也不改变 2D 证券状态事实模型。
- 阶段 2I 不新增迁移或持久化表，继续使用 V5 的 `agent_tasks`、`agent_runs`、`agent_evidence`、`agent_vetoes` 与 `agent_decisions`；精确规则版本复用 2G 组合上下文且不改变其 contextHash。精确 2I 规则以 finalDecision 显式映射总控终态：五种确定结果为 `COMPLETED`，只有最终 `INSUFFICIENT_DATA` 为 `task=PARTIAL/decision status=INSUFFICIENT_DATA`；专业 run 状态保持原样。非法 Python 响应在现有事务边界内原子失败，不影响已经合法存在的行情、公告或模拟账户事实。
- 阶段 3A-1 的 V11 新增 `agent_shadow_batches`、`agent_shadow_items`、`agent_shadow_reviews`，并把 `SHADOW` 加入既有任务触发类型硬约束。batch/item 只允许受控生命周期更新，终态身份、任务引用、结果快照和漂移由数据库触发器保护；review 禁止 `UPDATE/DELETE/TRUNCATE`，更正只能追加并引用被取代记录。影子 runner 只创建既有 2I Agent 任务，不直接写 run/decision，也不修改行情、公告或模拟账户事实。
- 阶段 3A-R1 恢复 V6 的已应用历史内容并冻结 checksum `-981595186`；V12 只前向承接两个 V6 版本的合法 Schema delta，删除旧日历导航列前必须证明其无非空值。所有执行 migrate 的数据库集成测试必须使用随机隔离 Schema，并显式配置 datasource `currentSchema`、Hikari schema、Flyway default-schema/schemas 与 `create-schemas=false`；准备向 public migrate 时立即失败。
- 阶段 2D-2A 冻结 `SECURITY_STATUS_EVENT_V1`；数据库层禁止 dataset/event 的 `UPDATE`、`DELETE`、`TRUNCATE`，history/calendar 只允许一次 `known_to: NULL -> 非NULL` 关闭。上一/下一开市日不持久化，统一按同 exchange、同 knowledge cutoff 的日历事实动态推导。
- 阶段 2D-2B-1B-1 仅在 TEST/DEMO 边界内把 `SECURITY_STATUS_RAW_TEST_V1` 物化或复用为 V1 event；V8 同时在 Java 和 PostgreSQL 阻止 FORMAL/PIT 提升，并在 2D-2B-2 独立实现前禁止任何 resolved event 写入 `security_status_history`。
- `contextHash` 按 JSON 数值的数学值规范化，对象字段稳定排序、数组保持业务顺序；API、PostgreSQL JSONB 与持久化快照重算结果一致。
- 阶段 2B 质量 evidence 固定来源为 `JAVA_ENGINE/AgentContextSnapshotService/contextSnapshot`，只投影四类冻结上下文；所有 DATA_QUALITY finding 引用该权威 evidence。
- Java 纳秒 `Instant` 与 Python `datetime` 往返时间按微秒传输精度规范化比较，双方截断到微秒后必须完全相等；相差 1 微秒即拒绝，不改写冻结上下文或 Hash。
- 逻辑 evidence ID、逻辑 veto ID 与数据库物理主键的映射规则已冻结。
- Agent 任务工作台路由为 `/agent-team`，通过 `taskId` query 恢复任务；3A-1 新增独立 `/agent-shadow` 只读观测与人工复核工作台；旧 `/ai` 页面保留。
- 工作台使用真实 Java API，不包含运行时 mock 或前端生成的分析结论。
- `start-agent-team-local.ps1` / `stop-agent-team-local.ps1` 使用可信状态、PID/启动时间、进程树、互斥锁和敏感环境隔离。

## 禁止范围

当前智能体团队阶段禁止接入或宣称已具备：实时外部行情、正式公告资格、全市场自动公告抓取、公告 PDF 语义分析、真实账户、`accountId=1` 当前只读状态之外的模拟持仓上下文、历史持仓 PIT、LLM/付费 API、超出已冻结 DATA_QUALITY、MARKET_REGIME V1、TECHNICAL_ANALYSIS V1、STRATEGY_BACKTEST V1、ANNOUNCEMENT_RISK V1、POSITION_RISK V1 和 2I 总控契约的评分策略、交易写操作、自动下单和券商控制。2G 只增加默认关闭、手动触发的 AKShare/CNINFO 研究 Provider；不得把它描述为 FORMAL/PIT、正式授权、历史完整或可用于自动交易。3A-1 的影子功能和 scheduler 默认关闭，不得将受控试运行描述为长期观察、收益证据、生产运行或完整 3A。不得编造价格、指标、证据或投资结论。专业 Agent score 和 2I 综合分类均不构成市场确定判断、收益预测、投资建议或交易信号；正式 veto 仅表示冻结账户风险规则拒绝，不是交易执行指令。

## 已知问题与最近测试

- PostgreSQL 16.13 高于当前 Flyway 9.22.3 声明的已测试上限，现阶段为非阻断警告。
- Python TestClient 存在 Starlette/httpx 弃用警告。
- 前端主包超过 Vite 默认 500 kB 提示阈值。
- 阶段 2A Java 验收：`quant-core` 运行1项、0失败；阶段 2A 定向测试运行21项、0失败；带专用 PostgreSQL 变量的完整 Agent 测试运行155项、0失败、1项跳过；独立无数据库变量 Agent 测试运行150项、0失败、9项安全跳过；独立无变量 `quant-server` 全量运行151项、0失败、9项跳过。
- Python `compileall` 通过，权威 `unittest discover` 为33/33通过；仓库未声明 pytest 依赖，本阶段未安装 pytest 或修改 Python 依赖。Vue生产构建通过，`git diff --check` 通过。
- 自动化生产流与真实本地受控任务均确认 API `contextHash`、数据库 `context_hash` 和生产 `AgentContextHashService` 对 JSONB 的重算结果一致。
- 正常用户 PowerShell 已通过 Python、Java、Vue 安全启动、空数据任务、受控数据任务和精确停止闭环。验收任务及阶段 2A 证券、日线夹具均已精确清理，最终 Agent 五表计数为 `2/12/0/0/2`；`state.json`、三个监听端口和六个记录 PID 均无残留。
- Codex 受控环境因无权读取 `Win32_Process` 而被脚本安全拒绝证明进程归属；正常用户 PowerShell 的 CIM 权限下闭环通过。本阶段未修改运行脚本。
- 阶段 2B Java 验收：阶段 2B 定向测试 `13/0/0/0`，响应校验相关定向测试 `45/0/0/0`，无集成变量完整 Agent 回归 `164/0/0/9`，`quant-server` 全量 `165/0/0/9`。
- 阶段 2B 真实闭环：专用 `stock_quant_test` 数据库、真实 Python 回环服务和 Java 持久化测试为 `1/0/0/0`、`BUILD SUCCESS`；DATA_QUALITY 为 `COMPLETED/WARN/WARN/50/100`，总控为 `INSUFFICIENT_DATA/WARN/0/0`，六 run、唯一 evidence、空正式 veto 和 Hash 一致性均通过。
- 阶段 2B 测试数据已精确清理；测试前后 Agent 五表基线为 `2/12/0/0/2`，证券与日线均为 `0/0`，Python 已停止且 8001 已释放。
- 阶段 2C 真实 PostgreSQL 验收：`AgentStage2CReadonlyContextPostgresIntegrationTest` 为 `1/0/0/0`、`BUILD SUCCESS`；上下文与 JSONB 完整语义往返、生产 Hash 重算、无业务副作用、测试数据精确清理及相关表恢复测试前基线均通过。
- 阶段 2C 最终回归：Java 定向 `38/0/0/0`，完整 Agent `176/0/0/10`，`quant-server` `177/0/0/10`，`quant-core` `1/0/0/0`；Python `compileall` 通过、unittest 全量 `50/0/0`、阶段 2C 定向 `1/0/0`；`git diff --check` 通过。
- 阶段 2D-1 普通回归：Python `compileall` 通过，阶段 2D 定向与契约33项通过，Python全量68项通过；Java阶段 2D Validator 8项通过，跨语言与一致性21项通过，阶段 2B 兼容13项通过；完整 Agent `189/0/0/11`，`quant-server` `190/0/0/11`，`quant-core` `1/0/0/0`，`git diff --check` 通过。
- 阶段 2D-1 真实成功路径：`AgentStage2DPostgresPythonIntegrationTest` 为 `1/0/0/0`、`BUILD SUCCESS`；专用 `stock_quant_test` 数据库和真实Python服务完成上下文冻结、六run持久化、DQ后MARKET_BREADTH证据顺序、安全总控、JSONB完整语义往返及生产Hash重算验证。
- 阶段 2D-1 非法响应原子失败：`AgentInvalidResponsePostgresIntegrationTest` 为 `7/0/0/0`、`BUILD SUCCESS`；测试预期的响应校验和JSON解析异常均被安全拒绝，evidence/veto/decision无部分持久化，FAILED任务重复执行不再次调用HTTP。
- 阶段 2D-1 测试任务、证券和日线均已精确清理；Agent五表、`securities` 和 `daily_bars` 恢复测试前基线。JSONB/Hash、原子失败、精确清理和基线恢复均已通过独立验收。
- 阶段 2D-2A 真实 PostgreSQL 及并发测试为 `2/0/0/0`、`BUILD SUCCESS`；随机临时 Schema 内完成 V1 至 V6 迁移、数据库不可变保护、事件到history事实链、as-of日历、并发幂等与并发更正验证。两个backend PID不同，并发更正只产生一个新逻辑版本，旧 `known_to` 正确关闭，仅一个开放knowledge版本且不存在valid/knowledge重叠；临时Schema最终删除，public基线不变。
- 阶段 2D-2B-1A 已完成并合入。其真实 PostgreSQL 测试在随机临时 Schema 内从 V1 顺序迁移至 V7，覆盖 raw/attempt 不可变、namespace、assurance、retry、封存、Manifest V1、两个 backend 并发幂等与冲突；测试结束删除临时 Schema且 public 基线未变化。
- 阶段 2D-2B-1B-1 的测试结果均为 Codex 本地执行证据，不是 GitHub Actions CI：V8 真实 PostgreSQL 为 `6/0/0/0`、`Skipped=0`；2D-2A 兼容 PostgreSQL 为 `2/0/0/0`；2D-2B-1A 兼容 PostgreSQL 为 `2/0/0/0`；`quant-server` 为 `255/0/0/21`；`quant-core` 为 `1/0/0/0`；Python unittest 68 项通过；Python `compileall` 通过；`git diff --check` 通过。
- `quant-server` 的 21 项跳过属于非数据库全量回归中的环境门禁跳过，不能冒充真实 PostgreSQL 测试；V8 真实 PostgreSQL 测试单独以 `Skipped=0` 完成。
- 本地专用测试库 public Schema 曾存在 V6 checksum 与仓库被改写版本不一致的历史环境问题。2D-2B-1B-1 当时未执行 Flyway repair 或 clean，未修改、删除或重建 public，并通过随机 Schema 隔离完成真实 PostgreSQL 验收；该问题不属于 V8 功能失败。3A-R1 已从 Git 历史确认 public 实际 V6 checksum `-981595186`，并把仓库 V6 恢复为该已应用内容。
- Tushare 个人研究的数据来源、本地保存、回测/回放和 Agent 书面许可已闭环；但稳定 source instrument ID、完整公司行动、revision、published/effective、历史版本及完整 QFQ lineage 仍未批准，因此完整 FORMAL/Provider PIT 与生产摄取继续阻断。
- 阶段 2E-1 的测试结果均为 Codex 本地执行证据，不是 GitHub Actions CI：Python `compileall` 通过、unittest `77/0/0`；真实 Java/Python 跨语言闭环 `4/0/0/0`、`Skipped=0`；专用 `stock_quant_test` 随机临时 Schema 的真实 PostgreSQL 闭环 `2/0/0/0`、`Skipped=0`；`quant-server` 全量 `261/0/0/27`；`quant-core` 全量 `1/0/0/0`。`quant-server` 的 27 项跳过是未提供外部集成环境变量时的门禁跳过，不能冒充真实 PostgreSQL 或真实 Python 闭环；两类真实闭环已分别单独以 `Skipped=0` 执行。
- 阶段 2E-1 的真实 PostgreSQL 测试从 V1 至 V8 迁移随机临时 Schema，覆盖六个 run、证据顺序、空正式 veto、Hash、非法响应原子失败与精确清理；临时 Schema 最终删除，public 数据计数、关系/约束/触发器/函数指纹、Flyway 历史和扩展前后不变。未对存在历史 V6 checksum 问题的 public 执行 repair、clean、删除或重建。
- 阶段 2F 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：原实现验收包括 `quant-core` 全量 `4/0/0/0`、2F Java contract/service 定向 `51/0/0/0`、Python 完整 unittest `82/0/0` 与 2F V1 至 V9 真实 PostgreSQL `5/0/0/0`。针对日线最早合法知识时间 BLOCKER 的最终增量复测为：`quant-core` 全量 `4/0/0/0`；Java contract/service/profile/contextHash 定向 `27/0/0/0`；Python `compileall` 通过、完整 unittest `83/0/0/0`；2F V1 至 V9 真实 PostgreSQL `7/0/0/0`、真实 Java/Python HTTP `4/0/0/0`、真实 PostgreSQL/Python/JSONB/原子失败 `2/0/0/0`，三组均 `Skipped=0`；2D V1 至 V9 PostgreSQL 兼容 `10/0/0/0`、2E 真实兼容 `6/0/0/0`，均 `Skipped=0`。
- 阶段 2F 安全非数据库 `quant-server` 回归为 `242/0/0/8`；8 项只属于 2E/2F 外部 Python/PostgreSQL 环境门禁，不能冒充真实闭环，对应真实跨语言测试已另行以 `8/0/0/0`、`Skipped=0` 运行。绑定专用数据库 public 的全量尝试运行 286 项，因既有 V6 checksum 不一致产生 15 个启动错误和 14 项跳过，因此不描述为通过。未 repair/clean 或改动 public；所有 2F 与兼容 PostgreSQL 验收在随机临时 Schema 运行 V1 至 V9、精确删除测试 Schema，并验证 public 数据与结构指纹前后不变。
- 阶段 2H 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：`quant-core` 全量 `4/0/0/0`；2H Java 定向 `26/0/0/0`；Python `compileall` 通过、完整 unittest `92/0/0/0`；真实 Java/Python HTTP `4/0/0/0`、真实 V1 至 V9 PostgreSQL/JSONB/持久化/业务表只读闭环 `2/0/0/0`，两组均 `Skipped=0`；安全非数据库 `quant-server` 全量 `301/0/0/46`；随机隔离 Schema 的 2D/2E/2F 真实兼容 `29/0/0/0`、`Skipped=0`。
- `quant-server` 的 46 项跳过属于未提供外部 Python/PostgreSQL 集成环境变量时的环境门禁，不能冒充真实闭环。另一次包含绑定专用数据库 public 的旧 2D 测试类的兼容尝试为 29 项通过、1 项 ApplicationContext 错误，原因仍是已知 V6 checksum 不一致，因此不描述为全量通过；未 repair/clean、删除、重建或修改 public。2H 真实验收在随机临时 Schema 运行 V1 至 V9并精确清理，验证 public 基线及 `portfolio_accounts`、`positions`、`manual_orders`、`simulated_trades`、`account_equity_snapshots`、`risk_events` 逐行指纹前后不变。
- 阶段 2G 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：真实 AKShare 安全门使用固定 `akshare==1.18.64`、`000001` 和受控历史范围成功返回 38 行，新增 CNINFO 域名门禁未拒绝真实链接；`quant-core` 全量 `4/0/0/0`；2G Java 定向合计 `42/0/0/0`、`Skipped=0`，其中真实 Java/Python HTTP `5/0/0/0`、V1 至 V10 PostgreSQL 公告事实 `3/0/0/0`、真实 PostgreSQL/Python/任务持久化 `1/0/0/0`、真实 AKShare Live Gate `1/0/0/0`；Python `compileall` 通过、完整 unittest `112/0/0/0`；安全非数据库 `quant-server` 全量 `334/0/0/56`；随机隔离 Schema 的 2D/2E/2F/2H 真实兼容 `35/0/0/0`、`Skipped=0`。
- `quant-server` 的 56 项跳过属于未提供外部 Python/PostgreSQL/AKShare 集成环境变量时的环境门禁，不能冒充真实闭环。2G 的 PostgreSQL、跨语言、任务持久化和 Live Gate 均已另行真实运行且 `Skipped=0`；随机测试 Schema 均被精确删除，public 数据与结构指纹前后不变，2H 六张模拟账户业务表前后逐行一致。未对 public 执行 Flyway repair/clean，也未把真实公告响应、Cookie 或访问凭据写入仓库。
- 阶段 2I 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：`quant-core` 全量 `4/0/0/0`；2I Java 纯规则、context 与终态映射定向 `23/0/0/0`；Python `compileall` 通过、完整 unittest `123/0/0/0`；真实 Java/Python HTTP `12/0/0/0`、2E/2F/2G/2H 真实 HTTP 兼容 `17/0/0/0`；V1 至 V10 真实 PostgreSQL/Python/任务持久化 `2/0/0/0`，覆盖五种确定总控结果的完成终态和缓存、最终不足非完成、专业 run 原状态与物理 veto 映射；随机隔离 Schema 的 2D-2A/2D-2B/2E/2F/2G/2H PostgreSQL 兼容 `26/0/0/0`；Java AKShare Live Gate `1/0/0/0`。所有真实组均 `Skipped=0`。安全非数据库 `quant-server` 全量为 `360/0/0/69`，69 项仅为未提供外部 Python/PostgreSQL/AKShare 环境时的门禁跳过，不能冒充真实闭环；Vue 生产 build 通过。
- 2I 真实数据库与 Live Gate 均在随机隔离 Schema 从 V1 迁移至 V10，测试内验证 public 数据和结构指纹前后不变；本轮结束后只读检查确认相关随机 Schema 残留数为 0。另一次把绑定专用库 public 的旧 `AgentStage2DPostgresPythonIntegrationTest` 加入兼容批次时得到 `27/0/1/0`，唯一 ApplicationContext 错误仍由已知 V6 checksum 不一致导致，因此不描述为通过；未执行 Flyway repair/clean，未修改、删除或重建 public。
- 阶段 3A-1 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：3A-1 Java 选择、调度、状态机、runner、结果、指标和 API 定向 `15/0/0/0`；Python `compileall` 通过、完整 unittest `123/0/0/0`；V1 至 V11 真实 PostgreSQL/Java/Python 受控试运行 `1/0/0/0`，EXPLICIT 三只证券首批逐项关联真实 SHADOW Agent 任务，重复批次三项全部命中 completed cache，并验证漂移、append-only 复核、六张模拟账户业务表、行情和公告观察表只读；V1 至 V11 的 2D/2E/2F/2G/2H/2I 真实兼容矩阵 `29/0/0/0`；Java AKShare Live Gate `1/0/0/0`。所有真实组均 `Skipped=0`。`quant-core` 全量 `4/0/0/0`，安全非数据库 `quant-server` 全量 `376/0/0/41`；41 项仅为外部 PostgreSQL/AKShare 环境门禁跳过，不能冒充真实闭环。Vue 类型检查与生产 build 通过。
- 3A-1 真实测试均在随机隔离 Schema 顺序应用 V1 至 V11，并在每组结束后精确删除；public 数据和结构指纹前后不变，测试随机 Schema 残留为 0。受控试运行 scheduler 始终关闭，不调用 AKShare 摄取、行情同步、全市场扫描或 Portfolio 写路径，结果不作为收益或策略有效性证明。
- 阶段 3A-R1 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：V6/V12 静态迁移与隔离安全门 `16/0/0/0`；原触发类随机 Schema 复测 `2/0/0/0`；双血统迁移、Schema 指纹收敛和 public 只读 validate `1/0/0/0`；旧 V6 血统克隆 Java/Python 单证券控制面 `1/0/0/0`；全部真实 PostgreSQL 兼容矩阵 `47/0/0/0`；Java AKShare Live Gate `1/0/0/0`；上述真实组均 `Skipped=0`。`quant-server` 全量 `388/0/0/0`、`quant-core` 全量 `4/0/0/0`、Python `compileall` 通过且完整 unittest `123/0/0/0`、Vue 类型检查与生产 build 通过。
- 3A-R1 首次全量回归中，既有 `AgentEvidenceVetoPostgresIntegrationTest` 的 Flyway 隔离缺陷意外把专用测试库 public 从 V6 通过合法迁移链前向迁移到 V12。没有 repair、clean、回滚、恢复备份、删除 V7 至 V12 对象、手工 checksum 或修改 `flyway_schema_history`。迁移事件前的完整业务表行数/逐行指纹没有快照，因此跨事件比较无法验证；V12 事务前置保护成功证明被删除的两个旧导航列当时没有非空值。接受 V12 后的最终回归前后，public 全表行、结构和 Flyway 历史指纹一致；只读检查确认 V1 至 V12 各一条成功记录、无失败或重复，V6 checksum `-981595186`、V12 checksum `-178798261`，随机 Schema 残留为 0，Shadow batch/item/review 均为 0。
- F1A 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：F1A Gateway/Provider/限流离线定向组 `23/0/0/0`；F1A + Provider V2 + 18 个 QFQ 黄金向量定向组 `51/0/0/0`；初始受控 Tushare 两证券三 Endpoint 联调 `1/0/0/0`、精确 6 请求和 0 重试；修复受控 stock_basic/dividend 联调 `1/0/0/0`、精确 4 请求和 0 重试，本增量提交没有新增 Provider 调用；PostgreSQL 16.13 随机 Schema V1→V13 `4/0/0/0`、`Skipped=0`，覆盖通用 FORMAL 拒绝、类型化有限授权正向捕获、伪 Provider/授权/资格绕过拒绝、TEST/DEMO 回归与 partial 原子不写；`quant-core` 全量 `4/0/0/0`；安全关闭 Live 和默认数据库连接后的 `quant-server` 全量 `457/0/0/94`，94 项是外部 PostgreSQL/Python/AKShare/Live 环境门禁，不能替代已经单独真实运行的 PostgreSQL 与此前两次受控 Provider 组。随机测试 Schema、55432 监听和临时集群目录残留均为 0；临时 public 只到 V12 且无 V13，正常业务数据库及其 public 未访问或迁移。
- F1B 的结果均为 Codex 本地离线证据，不是 GitHub Actions CI：Java 编译通过；类型化资格与 capability 定向 `21/0/0/0`；F1A/F1B、Provider V2 与 QFQ 联合回归 `62/0/0/0`，其中 Provider V2 `10/0/0/0`、18 个 QFQ 黄金向量 `18/0/0/0`；`quant-core` 全量 `4/0/0/0`；显式排除 Tushare Live、清空外部数据库和 Python 服务门变量后的 `quant-server` 安全全量 `466/0/0/93`。93 项是外部 PostgreSQL/Python/AKShare 环境门禁跳过，不能冒充真实集成测试；F1B 未修改持久化代码，按阶段边界未运行 PostgreSQL，本阶段没有检查 Token、没有新增 Provider 请求，也没有访问正常业务数据库。
- F1C 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：171 个生产源码完成干净 Java 编译；F1A/F1B/F1C 运行、实际 Gateway 合同、隔离守卫、Endpoint 限流、资格与 QFQ 定向组 `87/0/0/0`；加入 Provider V2 的扩展离线联合回归 `97/0/0/0`，其中 18 个既有 QFQ 黄金向量及显式完整 lineage 门禁均不回退；`quant-core` 全量 `4/0/0/0`；命令级排除全部 `*IntegrationTest`、`*Postgres*`、`*CrossLanguage*` 与 `*Live*` 的 `quant-server` 安全全量 `388/0/0/0`，没有读取 Token 或连接外部环境。PostgreSQL 16.13 临时实例中，F1A FORMAL 授权绕过回归 `4/0/0/0`、F1C 随机 Schema V1→V13 闭环 `3/0/0/0`，均 `Skipped=0`；F1C 覆盖正常公式运行、事务绑定同一 backend PID 以及数据库触发 `search_path` 变化后的全事务回滚。临时 public 只到 V12，且结构、数据、Flyway 指纹前后相同，随机 Schema、55432 监听和临时集群目录残留均为 0。所有 Provider 数据由经过冻结 Endpoint policy 与实际 limiter 的合成 Gateway 提供；F1C 新增 Provider 请求为 0，Tushare 累计仍为 20，iFinD 为 0，正常业务数据库未访问。
- F1E 的结果均为 Codex 本地执行证据，不是 GitHub Actions CI：8 个 core、181 个 server 生产源码完成干净 Java 编译；F1E 共享事实验证、捕获合同、全响应写前零写边界、准入、授权、命令、守卫与编排定向 `40/0/0/0`；F1A—F1E、Provider V2 与 QFQ 联合回归 `146/0/0/0`，其中 Provider V2 10 项、QFQ 权威引擎 19 项及 18 个黄金向量不变；`quant-core` 全量 `4/0/0/0`；命令级排除全部 `*IntegrationTest/*Postgres*/*CrossLanguage*/*Live*` 的 `quant-server` 安全全量 `437/0/0/0`。全新的 PostgreSQL 16.13 临时实例中，F1A `4/0/0/0`、F1C `3/0/0/0`、F1E `10/0/0/0`，均 `Skipped=0`；F1E 覆盖 1/2/3 证券精确请求、合同绕过和后续响应深层事实异常零写、直接休市日捕获零写、第二证券休市整批零写、Provider 或捕获失败全事务回滚、search path TOCTOU、幂等和 append-only。测试后 F1A/F1C 随机 Schema、F1E 专用 Schema、55432 监听和临时目录残留均为 0，F1E 临时 public 表数仍为 0，测试内 public 指纹前后相同。全部数据来自合成 Gateway，F1E 新增 Provider 请求为 0，Tushare 累计仍为 20，iFinD 为 0；未读取 Token，也未访问既有或正常业务数据库。

## 当前后续入口与阻断

**在阶段 2D-2B 数据来源工作线上，2D-2B-1B-2 approved source adapter 的前置来源与许可决策仍被阻断，正式 adapter 实现尚不能开始。**

来源询价、许可审查、样例验收与批准记录框架见 [stage-2d2b1b2-source-decision-package.md](stage-2d2b1b2-source-decision-package.md)。该决策包只准备证据清单和准入门槛，不代表任何来源已批准、FORMAL/PIT 已开放或 adapter 已开始。

完整阶段 2D、完整阶段 2D-2 和完整阶段 2D-2B 仍处于进行中。阶段 2D-2A、2D-2B-1A、文档阶段 2D-2B-1B-0 与 TEST/DEMO 实现阶段 2D-2B-1B-1 已完成；该工作线的唯一入口只是解决 2D-2B-1B-2 的外部前置决策，不是立即开始 adapter、2D-2B-2 或 Universe 实现。阶段 2E-1 已完成独立复审并合入，但没有自动批准或开始任何 2E 后续任务。

**在智能体规则能力工作线上，3A-R3B-0、免费优先治理规划、F0 审计、F0.5 双轨治理、F2A/R1/R1A/R1B、产品门治理、Track B0、Track B1、F1A、F1B、F1C、F1D、F1E、F1F-A、F1F-B1 与 F1F-B2-RUNNER 均已验收并合入；当前集成 HEAD 为 `213264bc63a2584f0fbb30dca059abf272e62a64`。F0 最终结论为 `F0_AUDIT_RESULT=PARTIAL`。Track A 已完成，当前 `FREE_PRODUCT_PREVIEW_GATE=PASS`。Track B 主要路线为 Tushare Pro，备用为 iFinD。B1 十项受控业务请求均 PASS；完整证据探针仍为 `PARTIAL_NOT_COMPLETE`、其执行时点法律前置为 `NOT_MET`。F1D 已把当前个人研究书面门闭环为 PASS，再分发、商业数据服务和 Token 共享仍为 `NOT_GRANTED`。F1E 已合入专用本地研究数据库守卫与 1—3 证券单日原子批次，但仅有 `REDUCED_RESEARCH_LOCAL_RUNTIME_IMPLEMENTATION_READY=true` 和 `REDUCED_RESEARCH_CONTROLLED_ACCEPTANCE_READY=true`，`REDUCED_RESEARCH_OPERATIONAL_READY=false`。首次 B2-FREEZE 为历史 `NOT_READY`；DBPREP 正在任务分支补齐一次性专用数据库准备链与真实单一授权文件清单，尚未合入或执行。当前 `F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE`，完整技术资格仍有十项阻断。** Day 001 仍只有 1 个有效观察日和 3 个 item。Tushare 保持 `V13_LINEAGE_PARTIAL/PIT_PARTIAL`、稳定证券 ID `PARTIAL`，不声明完整公司行动、Provider PIT 或永久身份资格。四项正式门禁仍为 `PASS/BLOCKED/PENDING/BLOCKED`。Tushare 累计真实业务请求为 20（B1 10、F1A 10，后续阶段均为 0），iFinD 为 0，scheduler 关闭，Day 002 未创建。Codex 不得自行升级 operational/生产运行、开始 F2B/F3、激活 iFinD、恢复长期观察、开始 3B 或其他阶段。

阻断项包括稳定 source instrument ID、完整公司行动、revision 语义以及 published/effective 时间语义。当前免费聚合源和 `securities` 当前态投影均不得被视为正式来源；2G 的研究级 AKShare/CNINFO 公告来源同样不得用于解除这些门禁。当前仍未实现 `PROVIDER_PIT_VERIFIED`、`SECURITY_STATUS_EVENT_V2`、`security_status_history` 正式投影、Universe snapshot、`MARKET_BREADTH_V2`、完整 MARKET_REGIME、公告 PDF 语义分析或生产扫描切换。F1A 只通过类型化专用入口增加 `PROVIDER_CAPTURE/RESEARCH_ONLY/formalEligible=false` 的 Tushare raw/factor/calendar 有限个人用途路径；F1B 固定 `REDUCED_RESEARCH_ONLY`；F1C 只增加随机隔离 Schema 的公式级手工入口；F1D 只关闭个人研究书面许可门。上述阶段都不等于完整 FORMAL、生产运行或正常业务库资格。正常业务库尚未执行 V13；完整 F1 当前只受 `BLOCKED_TECHNICAL_EVIDENCE` 阻断，生产摄取、F2B、F3、3A-R3B-1 至 R3B-3 均未开始。当前只有 1 个有效观察日和 3 个 shadow item，尚未达到 20 个有效观察日、200 个 item、主要原因人工复核和正式观察报告门槛，因此完整 3A 未完成，3B 未开始。阶段 2D-2B 禁止外部行情补数、LLM 权威决策、投资建议和交易写操作。
