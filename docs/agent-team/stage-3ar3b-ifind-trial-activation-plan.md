# 3A-R3B iFinD 试用里程碑启动规划记录

## 1. 阶段状态

状态：**治理规划已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯 fast-forward
合入。**
**3A-R3B-0 已通过最终复验并纯 fast-forward 合入；免费优先治理更新正在独立任务分支
冻结，F0 尚未获得实施授权，iFinD 试用和真实接入均未开始。**

- 冻结集成基线：`94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`
- 任务分支：`codex/1.4.0-stage-3ar3b-ifind-trial-activation-plan`
- 目标提交：`docs(agent): plan milestone-gated ifind trial activation`
- 实现及最终提交：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 当前集成 HEAD：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 完整任务书：
  [tasks/3ar3b-ifind-trial-activation-plan.md](tasks/3ar3b-ifind-trial-activation-plan.md)
- 免费优先规划：
  [tasks/3ar3b-free-first-provider-validation-plan.md](tasks/3ar3b-free-first-provider-validation-plan.md)

## 2. 同步的真实状态

- 3A-R3B-0 最终提交：
  `f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`；
- ChatGPT 已基于实际 Git 提交最终复验 PASS，用户已批准纯 fast-forward 合入；
- 当前本地和远程集成 HEAD 均为该最终提交，ahead/behind 为 `0/0`；
- V13 代码已经进入集成分支，但正常业务库尚未执行 V13；
- 真实免费或付费 Provider 尚未接入；
- iFinD 试用尚未启动或调用，真实调用数量为 `0`；
- Day 002 未创建，scheduler 关闭；
- 完整 3A 未完成，3B 未开始。

## 3. 里程碑门禁

`2026-08-31`、2026 年 8 月 31 日及任何其他固定日期均不是权威启动条件。日历日期只能
作为临时估算，不能进入路线图依赖、门禁或自动配置，也不能促使团队降低验收标准。

当前正式状态：

```text
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

免费验证门 PASS 只表示至少一条免费路线能在明确用途边界内稳定驱动产品闭环和前向
`SYSTEM_KNOWLEDGE_PIT` Shadow。付费升级只有在产品、免费闭环、初步可重复价值、数据
瓶颈、同范围 A/B、重构状态和成本意愿均满足时才能 `PROCEED`。

只有 F0 至 F3 相应验收完成、付费升级为 `PROCEED`、Provider 中立 DTO、四类 PIT 事实、
append-only/cutoff、18 个 QFQ 黄金场景、2F V2 Mock、六智能体 Mock、EXPLICIT Shadow、
禁用 Adapter 骨架、调用预算、证据工具、无重大重构和连续 15 天安排等原 12 项全部通过，
且用户亲自批准申请和激活，才可以改为：

```text
IFIND_TRIAL_ACTIVATION_GATE=PASS
```

ChatGPT 在相关阶段验收时同步检查门禁；只有 PASS 后才建议用户亲自申请开通。Codex
不得申请、激活、续期或自动调用 iFinD。

## 4. 免费优先后的阶段规划

| 阶段 | 目标 | iFinD 调用 | 当前状态 |
|---|---|---:|---|
| 3A-R3B-0 | Provider 中立离线闭环、四类事实、QFQ、2F V2/六智能体/Shadow Mock 和试用工具 | 0 | 已最终复验并合入；正常业务库未执行 V13 |
| 3A-R3B-F0 | 只读审计免费来源的事实、身份、字段、时效、修订、许可和维护风险 | 0 | 仅完成规划，未获实施授权 |
| 3A-R3B-F1 | 接入通过审计的免费 Provider，区分研究历史事实与系统前向知识事实 | 0 | 未开始 |
| 3A-R3B-F2 | 建立免费版真实产品闭环并验证可用性与可解释性 | 0 | 未开始 |
| 3A-R3B-F3 | 按冻结指标完成不少于 20 日、200 item 的免费 Shadow 效果评估 | 0 | 未开始 |
| 3A-R3B-1 | F0–F3 通过且付费升级 `PROCEED` 后，只读核验 12 项试用启动门 | 0 | 非直接下一阶段；门禁 BLOCKED |
| 3A-R3B-2 | 用户激活后的连续 15 天真实接入与取证 | 仅批准预算 | 未开始 |
| 3A-R3B-3 | 基于真实证据和许可判定 Provider 资格 | 不新增无关调用 | 未开始 |

## 5. 安全与延续性

- 免费 Provider 只是候选，开源客户端不证明底层数据商业许可；
- 免费历史数据不得冒充 `PROVIDER_PIT_VERIFIED`；系统首次捕获后的合格数据只能声明
  `SYSTEM_KNOWLEDGE_PIT`；
- 不得从 QFQ 反推 factor，也不得跨 Provider 拼成伪造同源 lineage；
- F3 必须预先冻结 `FREE_VALIDATION_METRICS_V1`，不得挑选盈利样本或观察后改阈值；
- 试用前真实 iFinD 调用必须为 `0`；
- 凭据不得写入 Git、文档、日志或固定夹具；
- R3B-2 禁止 scheduler、全市场遍历、无界重试和自动交易；
- 试用结束不得破坏 Mock 离线回归或 `SYSTEM_KNOWLEDGE_PIT`；
- Provider 资格验收和用户批准之前不得恢复 Day 002；
- 任何工作包完成都不自动完成 3A 或开始 3B。

## 6. 本阶段检查

- 生产代码测试：不适用，本阶段没有生产代码变化；
- Markdown 相对链接：通过；
- Markdown 表格：通过；
- 文件结尾换行：通过；
- `git diff --check`：通过；
- 变更范围：仅本次授权的七份治理 Markdown；
  没有 Java、Python、Vue、SQL、Flyway、配置或 `PROGRESS_LOG.md` 变化。

原规划提交已通过 ChatGPT 实际 Git 验收并经用户批准合入，3A-R3B-0 也已最终复验并
合入。本次免费优先更新仍待 ChatGPT 基于新的实际 Git 提交验收；这不代表 F0 获得实施
授权、任何门禁 PASS、付费升级 `PROCEED` 或 iFinD 试用已经开始。
