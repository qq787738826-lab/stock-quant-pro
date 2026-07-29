# 3A-R3B-F2A-R1A 首屏布局与风险语义修正阶段记录

## 1. 阶段状态

状态：**R1A 两次提交均已通过 ChatGPT 实际 Git 技术复验并合入；该轮语义和风险颜色
通过用户查看，但首屏仍发生重叠。R1B 已完成最终布局修复，用户产品形态验收已经通过。**

- 冻结基线：`e2b9457e3594676875167a703ae09ebc75aaaaf6`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-r1a-visual-semantics-fix`
- 目标提交：`fix(agent): refine preview layout and risk semantics`
- R1A 提交：`99b22a5e3bd2ad945c2f2b10ae79618277f8ed01`
- DATA_QUALITY 优先级修复：`11657c572d9561ae3b4a37be7a22f7456444844f`
- 任务书：
  [tasks/3ar3b-f2a-r1a-visual-semantics-fix.md](tasks/3ar3b-f2a-r1a-visual-semantics-fix.md)
- 当前产品预览门：`FREE_PRODUCT_PREVIEW_GATE=PASS`

R1 最终提交 `e2b9457e3594676875167a703ae09ebc75aaaaf6` 已通过 ChatGPT
实际 Git 技术复验，经用户批准纯 fast-forward 合入。用户于 `2026-07-29` 完成第二次
视觉复验：五分区、信息层级、折叠详情、结构化报告、字号和深色主题方向已通过查看；首屏
重叠、数据语义歧义、INFO 风险颜色和 DEMO02 待复验使该轮历史产品门继续为
`BLOCKED`。

R1A 合入后的用户复验确认数据质量门禁/研究证据完整性语义、INFO 风险颜色和 DEMO02
正式 veto 展示通过，但双列 header 仍在首屏产生重叠，因此当时没有打开产品门。R1B
最终提交 `4917bbabc8262106abb47e6cb90cf7ab96e76d7d` 随后以单列正常文档流替代该双列
布局；用户最终复验通过，并于 `2026-07-29 16:44 +08:00` 明确认可产品形态。

## 2. 根因与修复

| 问题 | 根因 | R1A 修复 |
|---|---|---|
| 首屏文字重叠 | header 使用可相互挤压的自由宽度 flex，标的和资格区域缺少完整收缩边界 | R1A 当时改为双列 Grid；用户复验仍发现重叠，R1B 最终改为单列正常文档流 |
| “通过”与“数据不足”并列歧义 | DATA_QUALITY 门禁和整个研究证据充分性被合并为“数据可靠性” | 拆为“数据质量门禁”和“研究证据完整性”两个纯展示状态 |
| INFO finding 显示为红色 | 风险列表所有条目共用严重风险背景 | 增加 level→tone 白名单，INFO/WARN/danger/formal-veto/neutral 分离 |

实现没有隐藏、裁切或删除业务内容，没有修改 Demo fixture、Agent/总控事实或正式 veto。

## 3. 首屏语义结果

DATA_QUALITY 展示顺序固定为：`INSUFFICIENT_DATA/FAILED/SKIPPED` 安全异常终态优先；
其余 run 先按 `BLOCKED/WARN/PASS/NOT_APPLICABLE` gate 映射为阻断/警告/通过/不适用；
只有 gate 没有明确结果时，才按 `PARTIAL/QUEUED/RUNNING` 展示普通运行进度。该顺序确保
`PARTIAL/RUNNING + BLOCKED/WARN` 不会回退为“部分完成/运行中”而遮蔽门禁。

研究证据完整性按总控阻断、总控不足、STRATEGY_BACKTEST 可用性、finalDecision 存在性和
其他已有结果的固定优先级映射，不解析 summary、不计算新评分。

DEMO01 固定结果：

| 字段 | 展示 |
|---|---|
| 总控结论 | 数据不足 |
| 研究动作 | 暂不形成研究结论 |
| 数据质量门禁 | 通过 |
| 研究证据完整性 | 不足 |
| 正式 veto | 无 |

这明确表达“数据结构门禁通过不等于研究证据已经足够”。

## 4. 风险颜色结果

| level | tone | 样式 |
|---|---|---|
| INFO | info | 蓝色/中性蓝灰 |
| WARN | warning | 橙色/琥珀色 |
| HIGH / CRITICAL | danger | 红色 |
| FORMAL_VETO | formal-veto | 独立强化红色并显示“正式否决” |
| 未知 | neutral | 普通蓝灰 |

DEMO01 的公告和持仓 INFO finding 不再使用红色严重风险样式。DEMO02 的
`REJECTED_BY_VETO`、POSITION_RISK 正式 veto、vetoCode、reason 和 evidenceIds 均未
修改；综合报告中的 FORMAL_VETO 保持独立严重样式。

## 5. 验证结果

| 验证 | 结果 |
|---|---|
| `npm run validate:research-preview` | 通过；GET-only、五分区、折叠、Demo、六 run、双数据语义、稳定 Grid、风险 tone 与门禁均通过；DATA_QUALITY 九组合优先级矩阵 `9/9` |
| `npm run build` | 通过；命令实际包含 `vue-tsc -b`，TypeScript 和 Vite production build 均通过 |
| DEMO01 静态向量 | 通过；DATA_QUALITY `COMPLETED/PASS`、回测不足、无正式 veto |
| DEMO02 静态向量 | 通过；`REJECTED_BY_VETO` 与 POSITION_RISK 正式 veto 保留 |
| 1920/1440/1366/125% 布局门禁 | 静态响应式契约通过；Grid、自然高度、1400px 单列、换行和页面溢出约束均满足 |
| Vue Demo 可达性 | 通过；目标 URL 返回 HTTP 200，8001/8080 未监听，自检后 5173 已释放 |
| 浏览器截图级自检 | 当前执行环境无可控浏览器实例，未冒充完成；等待用户在合入后最终视觉复验 |

构建只出现仓库既有 npm 环境配置、Rollup 注释和主包大小提示，没有新增依赖或失败。

## 6. 修改范围

前端只修改：

- `quant-web/src/components/research-preview/ResearchOverviewPanel.vue`
- `quant-web/src/components/research-preview/ResearchReportPanel.vue`
- `quant-web/src/research-preview/presentation.ts`
- `quant-web/src/research-preview/types.ts`
- `quant-web/scripts/validate-research-preview.mjs`

文档只修改本任务书/阶段记录、`CURRENT_STATE.md`、`ROADMAP.md`、`DECISIONS.md` 和 R1
任务书/阶段记录。未修改 Demo fixture、API、Java、Python、SQL、Flyway、Provider/Shadow
后端、依赖、`PROGRESS_LOG.md` 或其他业务页面。

## 7. 权威状态

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- F2A：已验收并合入；
- R1：已验收并合入；
- R1 第二次用户视觉复验（历史状态）：`2026-07-29` 已进行，部分通过但该轮总体仍
  `BLOCKED`；
- R1A：`99b22a5e3bd2ad945c2f2b10ae79618277f8ed01` 与
  `11657c572d9561ae3b4a37be7a22f7456444844f` 已验收并合入；
- R1A 用户复验（历史状态）：语义和风险颜色通过，首屏重叠仍阻断该轮 PASS；
- R1B：最终提交 `4917bbabc8262106abb47e6cb90cf7ab96e76d7d` 已验收并合入；
- 最终用户视觉验收：DEMO01/DEMO02 均通过，用户于 `2026-07-29 16:44 +08:00`
  明确认可当前产品形态；
- 产品门治理：
  [任务书](tasks/3ar3b-f2a-product-preview-gate-pass.md) /
  [阶段记录](stage-3ar3b-f2a-product-preview-gate-pass.md) 已在独立任务分支落地，待
  ChatGPT 实际 Git 验收和合入；
- Provider/iFinD 调用：0；
- 数据库访问与正常业务库 V13：未发生/未执行；
- Agent/Shadow/Day 002：未创建；
- scheduler：关闭；
- F1/F2B/F3、3A-R3B-1、3B：未开始；
- R1A/R1B merge：是；产品门治理提交 merge：否；
- `.ai/`：只通过 Git 状态确认未跟踪，未读取、修改、暂存或提交。
