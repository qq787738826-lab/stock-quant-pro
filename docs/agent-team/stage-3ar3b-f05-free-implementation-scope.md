# 3A-R3B-F0.5 免费版实施范围与双轨路线冻结阶段记录

## 1. 阶段状态

状态：**治理规划与 Codex 本地文档检查已在任务分支完成，待 ChatGPT 基于实际 Git
提交验收，尚未合入；F2A 与 F1 均未开始。**

- 冻结集成基线：`059eacffaf7e4a9f383be205d453c5168279932a`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f05-free-implementation-scope`
- 目标提交：`docs(agent): freeze free implementation scope`
- 完整任务书：
  [tasks/3ar3b-f05-free-implementation-scope.md](tasks/3ar3b-f05-free-implementation-scope.md)

本阶段仅修改九份授权 Markdown，没有开发页面、生产代码、测试、配置或迁移。

## 2. 同步的 F0 真实状态

- F0 最终提交：`059eacffaf7e4a9f383be205d453c5168279932a`；
- ChatGPT 已基于实际 Git 最终提交复验通过；
- 用户已批准并完成纯 fast-forward 合入；
- 当前集成分支本地与远程 HEAD 均为最终提交，ahead/behind 为 `0/0`；
- `F0_AUDIT_RESULT=PARTIAL`；
- BaoStock 继续为 `PENDING_WRITTEN_PERMISSION`；
- `DAILY_EXACT=UNVERIFIED`；
- AKShare 各实际上游为 `RESEARCH_AUXILIARY_ONLY`；
- CNINFO/SSE/SZSE/SZSI 为 `OFFICIAL_EVIDENCE_ONLY`；
- 当前没有免费来源能单独承担完整 V13/QFQ 同源 lineage。

`PARTIAL` 不是失败，但不批准 F1、Provider 或 PIT。

## 3. 冻结决定

```text
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
```

系统先以已有合法边界内的本地研究快照和显式 TEST/DEMO 能力形成产品预览，不等待完整
免费或付费 Provider。该决定不改变来源许可、PIT、V13、QFQ、Shadow 或效果门禁。

## 4. 双轨路线

| 轨道 | 路线 | 目标 |
|---|---|---|
| A：产品形态 | F0.5 → F2A → 用户产品形态验收 | 先验证页面、流程、解释和可追溯性 |
| B：Provider/PIT | F0 → 许可或替代证据 → F1 → F2B → F3 | 形成合格 Provider-backed 产品和前向验证 |

两条路线最终在付费升级决策前汇合，但资格保持隔离。轨道 A 的产品可见性不能证明轨道 B
已完成。

## 5. F2A 与 F2B

F2A 只允许：

- `EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED` 的既有只读数据；
- `TEST_DEMO_EXPLICIT` 的 3A-R3B-0 固定 Mock；
- 只读展示已冻结 Agent、总控和历史结果。

真实研究数据、不可用数据和 TEST/DEMO 不得混写。可靠回测不可用时显示结构化原因，不用
不合格数据替代，不把 Mock 收益写成真实历史收益。F2A 不新增 Provider 调用、不写或迁移
V13、不创建 Day 002、不运行正式 Shadow、不宣称准确率或盈利。

F2B 必须等待 F1 完成验收、用途边界明确且合法 `SYSTEM_KNOWLEDGE_PIT` 能前向积累。F2B
才允许使用 Provider-backed 事实驱动真实回测、六智能体和 F3。

F2A 完成不等于 F2B 或原完整 F2 完成。

## 6. 门禁

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

`FREE_PRODUCT_PREVIEW_GATE=PASS` 只表示用户认可产品形态和日常流程。它不表示 Provider、
PIT、策略、Shadow、iFinD、Day 002、交易或盈利获批，也不计入
`FREE_PROVIDER_VALIDATION_GATE`。

## 7. F1 与 F3 边界

BaoStock 在个人研究调用、本地持久化、历史回放/回测、内部 Agent、数据字典、单位、稳定
身份和独立 factor 边界明确前，继续为 `PENDING_WRITTEN_PERMISSION`。完整 QFQ 还必须
解决 `DAILY_EXACT`、交易所日历身份、公司行动身份和单 Provider lineage。

F3 继续依赖 F1、F2B、合法 `SYSTEM_KNOWLEDGE_PIT`、`FREE_VALIDATION_METRICS_V1`、不少于
20 个有效观察日和 200 个 Shadow item。F2A 不形成 F3 效果样本；Day 001 仍只有 1 个
观察日和 3 个 item。

## 8. 检查与安全边界

- Markdown 相对链接：通过；
- Markdown 表格列数：通过；
- UTF-8 与结尾换行：通过；
- 尾随空白和 `git diff --check`：通过；
- 变更范围：仅九份授权 Markdown；
- `PROGRESS_LOG.md`、能力/证据/探针/许可清单、Java、Python、Vue、SQL/Flyway、配置、
  requirements、lock、测试、fixture 与脚本：无变化；
- 免费 Provider 与 iFinD 调用：`0`；
- 数据库访问和正常业务库 V13：未执行；
- F1/F2A/F2B/F3：未开始；
- Day 002：未创建；
- scheduler：关闭；
- 3A-R3B-1 与 3B：未开始；
- merge：否；
- `.ai/`：未读取、修改、暂存或提交。

F0.5 验收并合入后，唯一允许另行规划的下一实施阶段是 F2A。本阶段不自动授权开始。
