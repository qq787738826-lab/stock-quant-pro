# 3A-R3B iFinD 试用里程碑启动规划记录

## 1. 阶段状态

状态：**治理规划已通过 ChatGPT 对实际 Git 提交的验收，并经用户批准纯 fast-forward
合入。**
**3A-R3B-0 已在独立任务分支完成实现和 Codex 本地验证，待验收、未合入；iFinD 试用和
真实接入均未开始。**

- 冻结集成基线：`94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`
- 任务分支：`codex/1.4.0-stage-3ar3b-ifind-trial-activation-plan`
- 目标提交：`docs(agent): plan milestone-gated ifind trial activation`
- 实现及最终提交：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 完整任务书：
  [tasks/3ar3b-ifind-trial-activation-plan.md](tasks/3ar3b-ifind-trial-activation-plan.md)

## 2. 同步的真实状态

- 3A-R3A 最终提交：
  `94d442fa5fcad874462c54ca83b4ba21dcf7d3b4`；
- ChatGPT 已基于实际 Git 提交验收 PASS，用户已批准纯 fast-forward 合入；
- 当前集成 HEAD 为该最终提交；
- Provider 尚未接入；
- iFinD 试用尚未启动或调用，真实调用数量为 `0`；
- Day 002 未创建，scheduler 关闭；
- 完整 3A 未完成，3B 未开始。

## 3. 里程碑门禁

`2026-08-31`、2026 年 8 月 31 日及任何其他固定日期均不是权威启动条件。日历日期只能
作为临时估算，不能进入路线图依赖、门禁或自动配置，也不能促使团队降低验收标准。

当前正式状态：

```text
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

只有 Provider 中立 DTO、四类 PIT 事实、append-only/cutoff、18 个 QFQ 黄金场景、
2F V2 Mock、六智能体 Mock、EXPLICIT Shadow、禁用 Adapter 骨架、调用预算、证据工具、
无重大重构和连续 15 天安排等 12 项全部通过，才可以改为：

```text
IFIND_TRIAL_ACTIVATION_GATE=PASS
```

ChatGPT 在相关阶段验收时同步检查门禁；只有 PASS 后才建议用户亲自申请开通。Codex
不得申请、激活、续期或自动调用 iFinD。

## 4. 四阶段规划

| 阶段 | 目标 | iFinD 调用 | 当前状态 |
|---|---|---:|---|
| 3A-R3B-0 | Provider 中立离线闭环、四类事实、QFQ、2F V2/六智能体/Shadow Mock 和试用工具 | 0 | 任务分支实现和 Codex 本地验证完成；待验收、未合入 |
| 3A-R3B-1 | 只读核验 12 项试用启动门 | 0 | 未开始；门禁 BLOCKED |
| 3A-R3B-2 | 用户激活后的连续 15 天真实接入与取证 | 仅批准预算 | 未开始 |
| 3A-R3B-3 | 基于真实证据和许可判定 Provider 资格 | 不新增无关调用 | 未开始 |

## 5. 安全与延续性

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
- 变更范围：仅 `CURRENT_STATE.md`、`ROADMAP.md`、`DECISIONS.md` 及本阶段两份文档；
  没有 Java、Python、Vue、SQL、Flyway、配置或 `PROGRESS_LOG.md` 变化。

上述规划提交已通过 ChatGPT 实际 Git 验收并经用户批准合入；这不代表 3A-R3B-0
已验收或合入，也不代表启动门 PASS 或 iFinD 试用已经开始。
