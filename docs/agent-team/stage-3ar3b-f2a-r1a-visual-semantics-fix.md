# 3A-R3B-F2A-R1A 首屏布局与风险语义修正阶段记录

## 1. 阶段状态

状态：**R1A 前端修复与 Codex 本地验证已在任务分支完成，待 ChatGPT 基于实际 Git
提交验收；尚未合入，R1A 用户最终视觉复验尚未进行。**

- 冻结基线：`e2b9457e3594676875167a703ae09ebc75aaaaf6`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-r1a-visual-semantics-fix`
- 目标提交：`fix(agent): refine preview layout and risk semantics`
- 任务书：
  [tasks/3ar3b-f2a-r1a-visual-semantics-fix.md](tasks/3ar3b-f2a-r1a-visual-semantics-fix.md)
- 产品预览门：`FREE_PRODUCT_PREVIEW_GATE=BLOCKED`

R1 最终提交 `e2b9457e3594676875167a703ae09ebc75aaaaf6` 已通过 ChatGPT
实际 Git 技术复验，经用户批准纯 fast-forward 合入。用户于 `2026-07-29` 完成第二次
视觉复验：五分区、信息层级、折叠详情、结构化报告、字号和深色主题方向已通过查看；首屏
重叠、数据语义歧义、INFO 风险颜色和 DEMO02 待复验继续阻断产品预览门。

## 2. 根因与修复

| 问题 | 根因 | R1A 修复 |
|---|---|---|
| 首屏文字重叠 | header 使用可相互挤压的自由宽度 flex，标的和资格区域缺少完整收缩边界 | 改为 `minmax(0, 1fr) minmax(260px, 360px)` Grid，双方 `min-width:0`，长文本自然换行，1400px 单列 |
| “通过”与“数据不足”并列歧义 | DATA_QUALITY 门禁和整个研究证据充分性被合并为“数据可靠性” | 拆为“数据质量门禁”和“研究证据完整性”两个纯展示状态 |
| INFO finding 显示为红色 | 风险列表所有条目共用严重风险背景 | 增加 level→tone 白名单，INFO/WARN/danger/formal-veto/neutral 分离 |

实现没有隐藏、裁切或删除业务内容，没有修改 Demo fixture、Agent/总控事实或正式 veto。

## 3. 首屏语义结果

DATA_QUALITY 非完成/失败/不足状态优先于 gate 展示，避免错误显示“通过”。完成 run 再按
PASS/WARN/BLOCKED/NOT_APPLICABLE 映射为通过/警告/阻断/不适用。

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
| `npm run validate:research-preview` | 通过；GET-only、五分区、折叠、Demo、六 run、双数据语义、稳定 Grid、风险 tone 与门禁均通过 |
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
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- F2A：已验收并合入；
- R1：已验收并合入；
- R1 第二次用户视觉复验：`2026-07-29` 已进行，部分通过但总体仍 `BLOCKED`；
- R1A：任务分支实现完成，待 ChatGPT 实际 Git 提交验收，尚未合入；
- R1A 用户最终视觉复验：未进行；
- Provider/iFinD 调用：0；
- 数据库访问与正常业务库 V13：未发生/未执行；
- Agent/Shadow/Day 002：未创建；
- scheduler：关闭；
- F1/F2B/F3、3A-R3B-1、3B：未开始；
- merge：否；
- `.ai/`：只通过 Git 状态确认未跟踪，未读取、修改、暂存或提交。
