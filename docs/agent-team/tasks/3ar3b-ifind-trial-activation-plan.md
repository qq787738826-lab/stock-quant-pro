# 3A-R3B iFinD 试用里程碑启动规划

## 1. 文档状态与范围

状态：**整体规划已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯 fast-forward 合入；**
**3A-R3B-0 已在独立任务分支完成实现和 Codex 本地验证，待验收、未合入；3A-R3B-1 至
3A-R3B-3 未开始。**

- 冻结集成基线：`94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`
- 任务分支：`codex/1.4.0-stage-3ar3b-ifind-trial-activation-plan`
- 目标提交：`docs(agent): plan milestone-gated ifind trial activation`
- 实现及最终提交：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 阶段记录：
  [stage-3ar3b-ifind-trial-activation-plan.md](../stage-3ar3b-ifind-trial-activation-plan.md)
- 当前事实唯一权威：[CURRENT_STATE.md](../CURRENT_STATE.md)
- 路线方向：[ROADMAP.md](../ROADMAP.md)
- 跨阶段决定：[DECISIONS.md](../DECISIONS.md)

本阶段只把 iFinD 15 天试用的启动时机改为可审计里程碑门禁，并规划后续四个工作包。
它不修改 Java、Python、Vue、SQL 或 Flyway，不调用或激活 iFinD，不写数据库，不创建
Day 002，不开启 scheduler，也不开始 3B。

## 2. 当前事实

- 3A-R3A 首次设计提交：
  `be12916ab0db07ceaa040883397424e10828b867`；
- 3A-R3A QFQ factor 选择语义修复及最终提交：
  `94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`；
- 3A-R3A 已通过 ChatGPT 对实际 Git 提交的最终验收，用户已批准纯 fast-forward，
  集成分支已到达最终提交；精确验收和批准时间无仓库证据，记为 `UNKNOWN`；
- 当前没有 Provider Adapter 接入；
- iFinD 试用尚未启动或调用，真实调用数量为 `0`；
- 当前正式门禁状态：

```text
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- Day 002 尚未创建，scheduler 仍关闭；
- 完整 3A 尚未达到长期观察门槛，3B 尚未开始。
- 3A-R3B-0 任务分支已完成 V13、四类事实、TEST/DEMO Mock、QFQ、2F V2、六智能体、
  EXPLICIT Mock Shadow 和试用准备工具的 Codex 本地验证；见
  [3A-R3B-0 任务书](3ar3b0-provider-neutral-pit-offline-v2.md)。该实现尚待 ChatGPT
  实际 Git 提交验收且尚未 merge。

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

## 4. 3A-R3B-0：Provider 中立离线闭环与试用准备

### 4.1 目标

在不调用 iFinD 的前提下，使用 TEST/DEMO 固定夹具、Mock Provider 和当前允许的
AKShare 研究级能力，完成 Provider 中立的离线技术闭环，为有限试用保存最大化有效时间。

### 4.2 计划交付

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
- 本规划本身不授权生产修改；后续用户已独立授权 3A-R3B-0，并在独立任务分支实施。
  该实现通过 ChatGPT 实际提交验收和用户 merge 批准前仍不是集成分支能力。

## 5. 3A-R3B-1：iFinD 试用启动门

这是一个只读验收阶段，不开发功能、不调用 iFinD。ChatGPT 必须基于实际 Git 提交、
完整差异和真实离线运行证据逐项核验。

只有下列 12 项全部满足才能 PASS：

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

## 6. 3A-R3B-2：15 天 iFinD 集中接入与取证

### 6.1 启动前提

必须同时满足：

1. `IFIND_TRIAL_ACTIVATION_GATE=PASS` 已由 ChatGPT 基于实际证据确认；
2. 用户已亲自批准申请和激活；
3. 凭据只通过安全进程环境注入，不进入 Git、文档、日志或测试夹具；
4. 15 天连续联调人员与时间已经安排。

### 6.2 目标

- 核验真实函数、指标、账户权限、频率和总调用额度；
- 完成真实 iFinD Adapter 字段映射；
- 核验 raw daily、factor、calendar、corporate action；
- 核验单位、精度、时区、空值、错误码和结构变化；
- 核验 revision、snapshot、provider update/publish time 和旧版本能力；
- 验证真实 PIT 入库、as-of、`DAILY_EXACT` QFQ、2F V2 和 EXPLICIT Shadow；
- 在合同和许可范围内保存脱敏固定夹具；
- 每日记录函数、调用量、成功/失败、权限、字段和未决问题；
- 形成完整 15 天证据报告。

### 6.3 额度与安全边界

- 禁止 scheduler 和全市场遍历；
- 禁止无界重试、自动重试消耗额度和后台轮询；
- 每次调用必须来自批准清单并计入调用预算；
- 禁止自动交易、真实账户和业务事实写入；
- 不得把试用成功调用自动解释为 Provider PIT 资格；
- 试用结束、额度耗尽或账号停用不得破坏 `SYSTEM_KNOWLEDGE_PIT`、Mock Provider、
  离线夹具和回归能力。

## 7. 3A-R3B-3：Provider 资格判定

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

## 8. 凭据与证据治理

- iFinD 用户名、密码、token、session、Cookie、完整敏感 URL 和原始认证头不得进入
  Git、Markdown、日志、截图或测试夹具；
- 试用前真实 iFinD 调用数量必须为 `0`；
- 真实响应只在许可范围内采集；
- 固定夹具必须删除凭据、账户、机器和个人信息，并保留字段语义、canonical Hash 和
  来源证据；
- 原始证据与可提交夹具分离；
- Mock 回归不得依赖活跃试用账号；
- 试用窗口结束后，离线测试、`SYSTEM_KNOWLEDGE_PIT` 和既有研究能力继续可运行。

## 9. 完成边界

本规划完成只表示：

- 固定日期触发已撤销；
- R3B-0 至 R3B-3 的依赖、准入和退出条件已明确；
- `PASS/BLOCKED` 成为唯一正式试用启动标志；
- 用户、ChatGPT 和 Codex 的启动权限边界已冻结。

它不表示 R3B-0 已实现、启动门已 PASS、iFinD 试用已启动或调用、Provider 已获资格、
Day 002 已创建、完整 3A 已完成或 3B 已开始。
