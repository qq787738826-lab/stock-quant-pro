# 3A-R3B iFinD 试用里程碑启动规划

## 1. 文档状态与范围

状态：**原 iFinD 里程碑规划已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯
fast-forward 合入；3A-R3B-0 已通过最终复验并合入。免费优先路线正在独立治理任务分支
冻结，F0 尚未获得业务实施授权，3A-R3B-1 至 3A-R3B-3 未开始。**

- 冻结集成基线：`94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`
- 任务分支：`codex/1.4.0-stage-3ar3b-ifind-trial-activation-plan`
- 目标提交：`docs(agent): plan milestone-gated ifind trial activation`
- 实现及最终提交：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 当前集成 HEAD：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 免费优先规划：
  [3ar3b-free-first-provider-validation-plan.md](3ar3b-free-first-provider-validation-plan.md)
- 阶段记录：
  [stage-3ar3b-ifind-trial-activation-plan.md](../stage-3ar3b-ifind-trial-activation-plan.md)
- 当前事实唯一权威：[CURRENT_STATE.md](../CURRENT_STATE.md)
- 路线方向：[ROADMAP.md](../ROADMAP.md)
- 跨阶段决定：[DECISIONS.md](../DECISIONS.md)

本文件最初把 iFinD 15 天试用改为可审计里程碑门禁；当前更新进一步在 R3B-0 与 R3B-1
之间增加免费 Provider 验证路线。该治理更新不修改 Java、Python、Vue、SQL 或 Flyway，
不调用免费 Provider 或 iFinD，不写数据库，不创建 Day 002，不开启 scheduler，也不开始
3B。

## 2. 当前事实

- 3A-R3A 首次设计提交：
  `be12916ab0db07ceaa040883397424e10828b867`；
- 3A-R3A QFQ factor 选择语义修复及最终提交：
  `94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`；
- 3A-R3A 已通过 ChatGPT 对实际 Git 提交的最终验收，用户已批准纯 fast-forward，
  集成分支已到达最终提交；精确验收和批准时间无仓库证据，记为 `UNKNOWN`；
- 3A-R3B-0 最终提交：
  `f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`；
- 3A-R3B-0 已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准并纯 fast-forward
  合入；当前本地和远程集成分支均为该最终提交，ahead/behind 为 `0/0`；
- V13 代码已经进入集成分支，但正常业务库尚未执行 V13；
- 当前没有真实免费或付费 Provider Adapter 接入；
- iFinD 试用尚未启动或调用，真实调用数量为 `0`；
- 当前正式状态：

```text
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- Day 002 尚未创建，scheduler 仍关闭；
- 完整 3A 尚未达到长期观察门槛，3B 尚未开始。

## 3. 日期触发正式撤销

iFinD 试用不得绑定 `2026-08-31`、2026 年 8 月 31 日或任何其他固定日期。

- 日历日期只能作为非权威临时估算；
- 日期不得进入 `CURRENT_STATE.md` 的启动条件、`ROADMAP.md` 的依赖门或自动化配置；
- 试用启动完全由完成度门禁决定；
- 即使预计日期临近，也不得降低数据库、DTO、PIT、QFQ、Mock 闭环、证据采集或安全标准；
- 尚未满足门禁时只能保持
  `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`；
- 只有全部门禁通过时才可记录
  `IFIND_TRIAL_ACTIVATION_GATE=PASS`。

## 4. 3A-R3B-0：Provider 中立离线闭环与试用准备（已完成并合入）

### 4.1 目标

在不调用 iFinD 的前提下，使用 TEST/DEMO 固定夹具、Mock Provider 和当前允许的
AKShare 研究级能力，完成 Provider 中立的离线技术闭环，为有限试用保存最大化有效时间。

### 4.2 已交付

1. Provider 中立接口与类型化 DTO；
2. Provider capability 契约，明确 raw daily、factor、calendar、corporate action、
   revision、published/update time、许可和覆盖资格；
3. `PIT_MARKET_FACTS_V2` 四类事实；
4. append-only 版本链、幂等、A→B→A 和 cutoff 选择；
5. as-of Repository；
6. `DAILY_EXACT` 的 `QFQ_AS_OF_ENGINE_V1`；
7. 3A-R3A 冻结的 18 个黄金场景；
8. 独立 ruleVersion/profile 的 2F V2 离线链路，2F V1 保持不变；
9. 六智能体 Mock 闭环；
10. EXPLICIT Mock Shadow 闭环；
11. 默认禁用且无法发起真实请求的 iFinD Adapter 骨架；
12. 限流、超时、认证失败、结构变化、错误码、部分响应和空数据测试；
13. 原始响应采集、凭据剥离、字段脱敏、canonical Hash 和离线固定夹具工具；
14. 必要的随机隔离 PostgreSQL 与跨语言回归。

### 4.3 边界

- iFinD 真实调用数量必须保持 `0`；
- 不取得或宣称任何真实 Provider 资格；
- 不创建 Day 002；
- 不开启 scheduler、全市场遍历或自动交易；
- 3A-R3B-0 已通过 ChatGPT 实际提交最终复验和用户 merge 批准，成为集成分支能力；
- V13 尚未迁移正常业务库，不能把代码合入写成正常业务库已具备 V13 结构；
- 完成 3A-R3B-0 不自动批准真实 Provider、免费 Provider、iFinD、Day 002 或后续阶段。

## 5. 免费优先战略与门禁

系统尚未通过真实产品形态与效果验证，因此顺序调整为：

```text
免费数据完成产品与效果验证
→ 证明系统具有使用价值
→ 判断数据是否成为主要瓶颈
→ 决定是否开启 iFinD 或其他付费 Provider
→ 做同范围免费/付费 A/B
→ 只有增量覆盖成本才考虑长期购买
```

iFinD 是系统证明有效后的专业化升级候选，不再是 R3B-0 之后的直接开发依赖。免费路线
不得冒充专业 Provider 资格，也不得降低 PIT、许可、lineage、QFQ 或用途门禁。

正式状态为：

```text
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

免费验证门 PASS 只表示至少一条免费路线能在明确用途边界内稳定驱动产品闭环和
`SYSTEM_KNOWLEDGE_PIT` 前向 Shadow，不代表商业正式许可、Provider PIT、盈利、iFinD 或
Day 002 获批。

付费升级只有在产品获用户认可、免费闭环稳定、Shadow 显示不只是大盘或随机波动的初步
可重复价值、数据成为可量化主要瓶颈、付费改善指标和同范围 A/B 已明确、无重大重构且用户
愿意承担成本时才能 `PROCEED`。产品、规则或效果尚未证明，免费数据已足够，增量无法覆盖
成本或用户暂不投入时可以 `DEFER`；DEFER 不等于项目失败。

## 6. 3A-R3B-F0：免费 Provider 资格审计

F0 是只读调查、最小受控探针与证据规划阶段。BaoStock 只是免费主 Provider 技术候选；
AKShare/Tencent 只是研究级当前投影、辅助和交叉校验候选；巨潮资讯、上交所、深交所公开
信息只是公告、公司行动、交易日历和规则的官方证据候选。其他来源必须先形成独立证据。

F0 必须审计 raw daily、独立 factor、`DAILY_EXACT`、交易所日历、公司行动、四类事实的
稳定来源身份、字段单位/精度/空值/明确 0、更新时间和延迟、静默修正、限制与错误、本地
保存/历史回放/回测/Agent/商业化权利、revision/snapshot/published/update、旧版本、
跨来源差异和维护风险。

不得因客户端开源推断底层数据允许商业使用，不得从 QFQ 反推 factor，不得跨 Provider
拼成伪造同源 PIT lineage。F0 当前仅有规划，尚未获得实际审计授权。

## 7. 3A-R3B-F1：免费 Provider Adapter 与 V13 接入

F1 只有在 F0 证据通过单独验收，且至少一条免费路线的研究用途和本地保存授权边界明确后
才能开始。

- `RESEARCH_HISTORICAL_UNVERIFIED`：只用于产品、演示、探索性历史回测、覆盖研究和
  交叉校验，不宣称 Provider PIT、历史绝对可得、无前视、正式商业数据或历史修订版本；
- `SYSTEM_KNOWLEDGE_PIT`：只适用于系统首次真实捕获以后，满足 append-only、
  `firstObservedAt`、`knownAt`、cutoff 和许可门禁的事实；不证明 Provider 在首次捕获前
  何时发布或修订。

F1 不得把免费 Provider 升级为 `PROVIDER_PIT_VERIFIED`，不得绕过 V13 许可、跨 Provider
拼接 QFQ、自动全市场抓取、开启 scheduler、创建 Day 002 或自动交易。

## 8. 3A-R3B-F2：免费版真实产品闭环

F2 在不购买专业数据时形成用户可见的候选池、单股完整分析、固定六智能体、总控结论、
数据质量、技术、市场环境、回测、公告与持仓风险、evidence/lineage/reasonCode、历史查询、
结果对比和可理解报告，并清楚区分研究数据、`SYSTEM_KNOWLEDGE_PIT` 与不可用数据。

它用于判断产品形态、日常使用意愿、信息价值、可理解/可追溯性、数据缺口影响及各智能体
增量价值。页面完成度不得被描述为选股、策略或收益有效。

## 9. 3A-R3B-F3：免费 Shadow 与效果评估

F3 使用免费 Provider 与 `SYSTEM_KNOWLEDGE_PIT` 做前向验证，至少需要 20 个有效观察日、
200 个 Shadow item、主要 reasonCode 正式人工复核、持续业务表只读证明和正式观察报告。
观察周期不绑定固定日期。

Shadow 开始前必须冻结 `FREE_VALIDATION_METRICS_V1`，覆盖 5/10/20 个交易日、相对基准、
平均/中位超额、MFE/MAE、最大回撤、盈亏比、换手和成本、市场环境、confidence 区分度、
阻断率、Agent 边际贡献、重放/Hash 及随机/固定基准。不得观察后移动阈值、周期、基准或
样本；不得只用推荐上涨比例、只报告盈利样本，或删除失败、阻断和无信号样本。

## 10. 3A-R3B-1：iFinD 试用启动门

这是一个只读验收阶段，不开发功能、不调用 iFinD。ChatGPT 必须基于实际 Git 提交、
完整差异和真实离线运行证据逐项核验。

R3B-1 不再是 R3B-0 的直接下一阶段。进入 R3B-1 前必须完成 F0 至 F3 的相应验收，
`PAID_PROVIDER_UPGRADE_DECISION=PROCEED`，用户可安排连续 15 天，并亲自批准申请和激活。
在此前提下，只有下列 12 项全部满足才能 PASS：

1. Provider 中立接口和 DTO 已冻结；
2. 四类 PIT 事实实现及随机隔离 PostgreSQL 测试通过；
3. append-only、幂等、A→B→A 和 cutoff 测试通过；
4. `DAILY_EXACT` QFQ 的 18 个黄金场景全部通过；
5. 2F V2 使用 Mock Provider 完整运行；
6. 六智能体使用 Mock Provider 完整运行；
7. EXPLICIT Shadow 的 Mock 闭环通过；
8. iFinD Adapter 骨架和限流、超时、认证、错误、空数据处理准备完毕，但保持禁用；
9. iFinD 函数、字段、证券、日期范围和调用预算清单完成；
10. 响应证据采集、凭据剥离、脱敏、Hash 与离线夹具工具完成；
11. 没有待解决的重大数据库模型、迁移顺序、公共 DTO 或跨语言契约重构；
12. 用户能够安排连续 15 天集中联调，并明确承担试用申请和激活时点选择。

正式状态只有：

```text
IFIND_TRIAL_ACTIVATION_GATE=PASS
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

判定规则：

- 任一项缺失、失败、未经验证或仍有重大设计分歧时必须为 `BLOCKED`；
- 不得使用预计日期、倒计时或“接近完成”代替 PASS；
- ChatGPT 在每个相关阶段验收时同步检查 12 项，但只有全部满足才建议用户开通；
- Codex 不得申请、激活、续期或自动调用 iFinD；
- 用户只有看到正式 `PASS` 后，才决定是否亲自申请和开启试用。

## 11. 3A-R3B-2：15 天 iFinD 集中接入与取证

### 11.1 启动前提

必须同时满足：

1. `IFIND_TRIAL_ACTIVATION_GATE=PASS` 已由 ChatGPT 基于实际证据确认；
2. 用户已亲自批准申请和激活；
3. 凭据只通过安全进程环境注入，不进入 Git、文档、日志或测试夹具；
4. 15 天连续联调人员与时间已经安排。

### 11.2 目标

- 核验真实函数、指标、账户权限、频率和总调用额度；
- 完成真实 iFinD Adapter 字段映射；
- 核验 raw daily、factor、calendar、corporate action；
- 核验单位、精度、时区、空值、错误码和结构变化；
- 核验 revision、snapshot、provider update/publish time 和旧版本能力；
- 验证真实 PIT 入库、as-of、`DAILY_EXACT` QFQ、2F V2 和 EXPLICIT Shadow；
- 在合同和许可范围内保存脱敏固定夹具；
- 每日记录函数、调用量、成功/失败、权限、字段和未决问题；
- 形成完整 15 天证据报告。

### 11.3 额度与安全边界

- 禁止 scheduler 和全市场遍历；
- 禁止无界重试、自动重试消耗额度和后台轮询；
- 每次调用必须来自批准清单并计入调用预算；
- 禁止自动交易、真实账户和业务事实写入；
- 不得把试用成功调用自动解释为 Provider PIT 资格；
- 试用结束、额度耗尽或账号停用不得破坏 `SYSTEM_KNOWLEDGE_PIT`、Mock Provider、
  离线夹具和回归能力。

## 12. 3A-R3B-3：Provider 资格判定

该阶段只根据 15 天真实响应、书面许可、合同附件、字段语义、历史版本样例和运行证据，
对 iFinD 作唯一资格结论：

- `PROVIDER_PIT_VERIFIED`；
- `PROVIDER_REVISION_UNVERIFIED`；
- `PROVIDER_REVISION_UNAVAILABLE`；
- `SYSTEM_KNOWLEDGE_PIT`；
- 许可不足或不批准接入。

判定必须明确：

- 哪些数据类别分别获准；
- revision、snapshot、published/update time 和旧版本证据；
- 本地持久化、历史回放、回测、派生计算和内部 Agent 权利；
- 可用证券、日期覆盖、额度、错误和限制；
- 不允许用途与到期/重新审查条件。

只有资格结论通过 ChatGPT 对实际 Git 提交和运行证据的验收，并经用户批准后，才能决定
是否恢复 Day 002。试用完成本身不自动批准来源、Day 002、完整 3A 或 3B。

## 13. 凭据与证据治理

- iFinD 用户名、密码、token、session、Cookie、完整敏感 URL 和原始认证头不得进入
  Git、Markdown、日志、截图或测试夹具；
- 试用前真实 iFinD 调用数量必须为 `0`；
- 真实响应只在许可范围内采集；
- 固定夹具必须删除凭据、账户、机器和个人信息，并保留字段语义、canonical Hash 和
  来源证据；
- 原始证据与可提交夹具分离；
- Mock 回归不得依赖活跃试用账号；
- 试用窗口结束后，离线测试、`SYSTEM_KNOWLEDGE_PIT` 和既有研究能力继续可运行。

## 14. 完成边界

更新后的规划只表示：

- 固定日期触发已撤销；
- R3B-0 已完成并合入；
- F0 至 F3 以及 R3B-1 至 R3B-3 的依赖、准入和退出条件已明确；
- 免费验证门、付费升级决策和 iFinD 试用门均已冻结；
- 用户、ChatGPT 和 Codex 的启动权限边界已冻结。

它不表示 F0 已获实施授权、免费验证门已 PASS、付费升级已 `PROCEED`、iFinD 启动门已
PASS、iFinD 已启动或调用、Provider 已获资格、Day 002 已创建、完整 3A 已完成或 3B 已开始。
