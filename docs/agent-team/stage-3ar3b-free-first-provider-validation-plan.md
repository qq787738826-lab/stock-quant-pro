# 3A-R3B Free-First Validation Plan 阶段记录

## 1. 阶段状态

状态：**治理规划已通过 ChatGPT 对实际 Git 提交的验收，经用户批准并纯 fast-forward
合入；后续 3A-R3B-F0 审计已在独立任务分支完成 Codex 本地验证，待提交验收。**

- 冻结集成基线：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-free-first-validation-plan`
- 目标提交：`docs(agent): adopt free-first provider validation strategy`
- 最终提交：`c47b88e586f6751563fe210f40137a3b7ce5e576`
- 完整任务书：
  [tasks/3ar3b-free-first-provider-validation-plan.md](tasks/3ar3b-free-first-provider-validation-plan.md)

## 2. 同步的真实状态

- 3A-R3B-0 最终提交为
  `f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`；
- ChatGPT 已基于实际 Git 提交最终复验通过，用户已批准并完成纯 fast-forward 合入；
- 3A-R3B-0 合入后，本规划又以最终提交
  `c47b88e586f6751563fe210f40137a3b7ce5e576` 纯 fast-forward 合入；当前集成
  HEAD 和远程 HEAD 均为该提交，ahead/behind 为 `0/0`；
- V13 代码已进入集成分支，但正常业务库尚未执行 V13；
- Provider 尚未接入；
- 免费 Provider 与 iFinD 在本治理任务中的真实调用数均为 `0`；
- Day 002 未创建，scheduler 关闭；
- 3A-R3B-1 和 3B 均未开始。

## 3. 免费优先策略

路线从“离线闭环后直接准备 iFinD 启动门”调整为：

```text
免费 Provider 资格审计
→ 免费 Adapter 与 V13 接入
→ 免费版真实产品闭环
→ 免费 Shadow 与效果评估
→ 判断付费数据是否成为必要且值得的升级
→ 再决定是否进入 iFinD 启动门
```

iFinD 继续保留为专业化升级候选，但不再是项目开发前置依赖。免费数据的使用不得降低
PIT、许可、lineage、QFQ 或用途资格门槛。

## 4. F0 至 F3

| 阶段 | 核心目标 | 当前状态 |
|---|---|---|
| 3A-R3B-F0 | 审计免费来源的四类事实、身份、字段、时效、修订、许可和维护风险 | 任务分支审计与Codex验证完成；`PARTIAL`，待ChatGPT验收 |
| 3A-R3B-F1 | 在 F0 证据通过后接入免费 Provider，严格区分研究历史数据与 `SYSTEM_KNOWLEDGE_PIT` | 未开始 |
| 3A-R3B-F2 | 建立用户可理解、可追溯的免费版真实产品闭环 | 未开始 |
| 3A-R3B-F3 | 按冻结指标进行不少于 20 日、200 item 的前向 Shadow 与效果评估 | 未开始 |

候选角色仅为 BaoStock 主技术候选、AKShare/Tencent 研究级辅助候选，以及巨潮资讯、
上交所、深交所公开信息的官方证据候选；均未被本规划批准为正式 Provider。

F0 后续实际审计确认：BaoStock raw/QFQ 日线、通用日历和公司行动最小探针可用，两个
按证券 factor 查询在固定短区间为空，`DAILY_EXACT=UNVERIFIED`；客户端 BSD License
不能替代底层数据许可，角色为 `PENDING_WRITTEN_PERMISSION`。AKShare 按
Tencent/Sina/Eastmoney/CNINFO 拆分并保持研究辅助，CNINFO/SSE/SZSE/SZSI 只作官方
证据。F0 结论为 `F0_AUDIT_RESULT=PARTIAL`，不改变三项正式门禁。

## 5. 三项正式状态

```text
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- 免费验证门 PASS 只证明免费路线能在明确用途边界内驱动产品闭环和前向
  `SYSTEM_KNOWLEDGE_PIT` Shadow；
- 付费升级只有在产品价值、免费闭环、初步可重复效果、数据瓶颈、A/B 设计和成本意愿均
  成立后才可 `PROCEED`；
- iFinD 启动门还必须等待 F0 至 F3 的相应验收、付费升级 `PROCEED`、既有 12 项准备条件、
  连续 15 天安排及用户亲自批准。

## 6. 冻结评估边界

F3 在 Shadow 开始前必须冻结 `FREE_VALIDATION_METRICS_V1`，覆盖 5/10/20 个交易日、
相对基准、收益分布、MFE/MAE、回撤、盈亏比、换手与成本、市场环境、confidence 区分度、
阻断率、各智能体边际贡献、重放/Hash 以及随机和固定基准对照。

不得只以推荐上涨比例作为准确率，不得只报告盈利样本，不得删除失败、阻断或无信号样本，
也不得观察后移动阈值、周期、基准或样本选择。

## 7. 本阶段检查

- Markdown 相对链接：通过；
- Markdown 表格：通过；
- 文件结尾换行：通过；
- `git diff --check`：通过；
- 变更范围：仅七份授权 Markdown；
- Java、Python、Vue、SQL、Flyway、配置、测试、fixture、脚本和
  `PROGRESS_LOG.md`：无变化；
- 本治理规划提交自身的免费 Provider 与 iFinD 调用：均为 `0`；后续 F0 的免费
  Provider 调用仅发生在冻结预算内，iFinD 调用仍为 `0`；
- 数据库写入与正常业务库 V13 迁移：均未执行；
- F0 实际审计与本地验证已在任务分支完成，待 ChatGPT 基于实际 Git 提交验收且尚未合入；Day 002、scheduler、R3B-1 和 3B 均未开始。

本记录只说明治理规划已经验收并合入；后续 F0 仍待实际 Git 提交验收，不表示任何
Provider 获得资格，也不表示 F1 已授权。
