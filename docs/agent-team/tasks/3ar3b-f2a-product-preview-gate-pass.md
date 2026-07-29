# 3A-R3B-F2A-GATE 免费研究预览产品门正式通过任务书

## 1. 任务性质

本阶段是独立治理阶段，只记录用户对免费研究预览产品形态的最终人工认可，并将当前产品
预览门设置为：

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
```

本阶段不修改前端、后端、Agent、数据、Provider、Shadow 或交易逻辑，不启动服务，不访问
数据库，也不授权任何后续实施阶段。

## 2. Git 基线与分支

- 集成分支：`feature/1.4.0-agent-team`
- 冻结集成基线：`4917bbabc8262106abb47e6cb90cf7ab96e76d7d`
- 治理任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-product-preview-gate-pass`
- 目标提交：`docs(agent): pass research preview product gate`
- 集成状态：治理提交合入前，本地和远程集成分支仍位于冻结基线。

## 3. 用户批准证据

- 批准时间：`2026-07-29 16:44 +08:00`
- 用户原文：“认可当前产品形态，批准创建独立治理提交，将FREE_PRODUCT_PREVIEW_GATE改为PASS。”

该批准仅针对用户已经实际查看的研究预览产品形态与只读日常流程，不得扩展解释为对数据、
策略、效果、投资或交易的批准。

## 4. 最终产品代码链

| 阶段 | 提交 | 结果 |
|---|---|---|
| F2A 初始产品 | `f5137e2422a48e70d5c706cb146fb034a2b96f65` | 候选池、单股分析、六智能体、总控、证据、历史和报告闭环 |
| R1 五分区与交互收敛 | `e2b9457e3594676875167a703ae09ebc75aaaaf6` | 五个主 Tab、默认折叠、结构化报告与信息层级 |
| R1A 首屏与风险语义 | `99b22a5e3bd2ad945c2f2b10ae79618277f8ed01` | 数据门禁/证据完整性分离与风险 tone |
| R1A 门禁优先级 | `11657c572d9561ae3b4a37be7a22f7456444844f` | DATA_QUALITY 安全终态与 gate 优先级 |
| R1B 垂直流修复 | `4917bbabc8262106abb47e6cb90cf7ab96e76d7d` | 单列正常文档流，最终消除首屏重叠 |

最终用户视觉验收基于
`4917bbabc8262106abb47e6cb90cf7ab96e76d7d`。

## 5. 视觉验收过程

| 轮次 | 当时结果 | 事实 |
|---|---|---|
| F2A 首次视觉验收 | 历史状态 `BLOCKED` | 功能闭环可见，但页面过长、技术字段过多、字号与层级不足 |
| R1 第二次视觉复验 | 历史状态 `BLOCKED` | 五分区和主体信息架构通过，首屏重叠、语义、颜色和 DEMO02 仍待收敛 |
| R1A 复验 | 历史状态 `BLOCKED` | 数据语义、风险颜色和 DEMO02 正式 veto 语义通过，首屏双列布局仍重叠 |
| R1B 最终复验 | `PASS` | DEMO01、DEMO02、首屏、风险语义、历史与报告均获用户认可 |

最终复验确认：

- DEMO01 无首屏重叠，显示“数据不足”“暂不形成研究结论”“数据质量门禁：通过”
  “研究证据完整性：不足”和“正式 veto：无”；
- 五个主 Tab、精确六智能体、按需技术详情和无买卖/仓位建议边界清楚；
- DEMO02 显示 `REJECTED_BY_VETO`、正式 veto 存在、POSITION_RISK 风险区域和
  `DEMO_POSITION_LIMIT` 入口；
- 历史任务中的 DEMO01/DEMO02、当前任务高亮和默认折叠对比可用；
- 信息架构、视觉层级、日常操作路径和风险颜色语义均通过用户查看。

## 6. PASS 精确定义

`FREE_PRODUCT_PREVIEW_GATE=PASS` 只表示：

1. 用户已经实际查看免费研究预览产品；
2. 用户认可当前信息架构和产品形态；
3. 用户认可日常只读研究流程；
4. 用户认可六智能体、总控、证据、历史与报告的展示方式；
5. Track A 的免费研究预览产品形态验证完成。

PASS 不表示：

- 免费 Provider 通过资格验证或真实数据已接入；
- Provider PIT、QFQ 或正常业务库 V13 获批；
- 策略有效、回测收益可信或 Shadow 达标；
- 可以推荐股票、提供投资建议或连接真实账户；
- 可以真实交易或自动交易；
- 可以购买、申请、激活或调用 iFinD；
- F1、F2B、F3 或其他后续阶段自动启动。

产品预览门与免费 Provider 验证门保持相互独立。

## 7. 保持不变的状态

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- F1、F2B、F3、3A-R3B-1 和 3B 未开始；
- Provider 真实接入未开始，iFinD 真实调用数为 0；
- 正常业务库 V13 未执行；
- Agent、Shadow 与 Day 002 未创建；
- scheduler 关闭；
- 真实交易与自动交易均未授权。

## 8. 修改和验证边界

只允许新增本任务书与阶段记录，并更新授权的六份权威阶段 Markdown。禁止修改代码、
`PROGRESS_LOG.md`、Provider 证据、Demo fixture、配置、迁移或 `.ai/`。

必须完成 Markdown 相对链接、表格列数、UTF-8、结尾换行、尾随空白、`git diff --check`、
精确范围、禁止文件、`.ai/` 未暂存和当前状态一致性检查。

## 9. 完成状态

任务分支完成后：

- 用户产品形态验收已经明确通过；
- `FREE_PRODUCT_PREVIEW_GATE=PASS` 已在治理任务分支落地；
- 治理提交待 ChatGPT 基于实际 Git 验收，尚未合入；
- 远程集成分支在治理提交合入前仍位于
  `4917bbabc8262106abb47e6cb90cf7ab96e76d7d`；
- 没有代码、服务、数据库或后续阶段变更。
