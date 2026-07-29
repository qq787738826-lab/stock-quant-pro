# 3A-R3B-F2A-R1 研究预览视觉与交互收敛阶段记录

## 1. 阶段状态

状态：**前端实现与 Codex 本地验证已在任务分支完成，待 ChatGPT 基于实际 Git 提交验收；
尚未合入，R1 用户视觉复验尚未进行。**

- 冻结基线：`f5137e2422a48e70d5c706cb146fb034a2b96f65`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-r1-preview-ux-convergence`
- 目标提交：`feat(agent): refine research preview experience`
- 任务书：
  [tasks/3ar3b-f2a-r1-preview-ux-convergence.md](tasks/3ar3b-f2a-r1-preview-ux-convergence.md)
- 路由：`/research-preview`
- 产品预览门：`FREE_PRODUCT_PREVIEW_GATE=BLOCKED`

F2A 最终提交 `f5137e2422a48e70d5c706cb146fb034a2b96f65` 已通过 ChatGPT
实际 Git 验收，经用户批准纯 fast-forward 合入。用户于 `2026-07-29` 通过完整页面截图
完成首次视觉查看；功能闭环可见，但视觉层级、信息密度和日常使用体验未通过，因此进入 R1。

## 2. 首次视觉问题与实现对应

| 首次问题 | R1 处理 |
|---|---|
| 页面纵向过长 | 五个主 Tabs、候选内部滚动、技术/证据/对比/原始报告折叠 |
| 进入页面不知道先看什么 | 默认研究总览和独立首屏研究摘要 |
| 总控结论不突出 | 当前标的、总控中文结论、code、研究动作和可靠性置于首屏 |
| 技术字段过多 | Agent 技术详情与集中审计详情默认关闭 |
| Agent 卡片密度不合理 | 默认只保留业务状态、score/confidence、summary 和主要原因 |
| 字体与对比度偏低 | 正文/次要/技术字号分层并增强灰蓝对比 |
| Demo 标签重复 | 完整标签收敛到顶部、首屏和水印，其余使用小型“演示数据” |
| 报告偏日志 | 默认八段结构化报告，原始文本仅按需展开 |
| 当前股票不醒目 | 当前候选高亮、当前分析标签和首屏标的同步 |
| 审计内容占据主视图 | 证据摘要优先，证据项与技术审计默认折叠 |

所有原有能力均保留，没有通过删除功能缩短页面。

## 3. 实现结果

### 3.1 五分区与状态

- 固定分区：研究总览、六智能体、证据与审计、历史对比、综合报告；
- 默认 `section=overview`，非法值安全回退；
- URL query 保存当前 section；
- 模式切换会清除旧模式的任务、候选与对比状态，不混用 Demo 和本地快照。

### 3.2 首屏与候选

- 新增 `ResearchOverviewPanel.vue`；
- 显示当前标的、日期、扫描任务、资格、总控、研究动作、数据可靠性、正式 veto、六 run 完成数
  和主要结构化原因；
- 研究动作只按六种现有总控 code 进行中文展示映射；
- 候选表支持当前行高亮、“当前分析”标签、键盘选择和内部滚动。

### 3.3 Agent、图表与风险

- 六 Agent 保持固定身份和顺序；
- 默认卡片只展示业务摘要，完整字段进入默认折叠的“技术详情”；
- DATA_QUALITY 阻断和 POSITION_RISK 正式 veto 独立突出；
- 正式 veto 仍读取 veto 列表，不由 `run.veto` 推断；
- ECharts 高度压缩，保持 null 不补 0、Demo 水印、ResizeObserver、resize 和 dispose；
- 总览中的回测、公告、持仓风险改为紧凑摘要，DEMO02 的正式 veto 详情可展开。

### 3.4 证据、历史和报告

- 证据默认显示数量和 category/sourceType 分布，单条 evidence 与 fields 均默认折叠；
- 新增 `TechnicalAuditDetails.vue`，集中折叠 task/rule/context/sourceRunIds/vetoIds 和时间；
- 历史筛选/列表默认可见，当前任务高亮，任务 A/B 对比默认折叠；
- 综合报告改为研究结论、资格限制、六 Agent 摘要、主要风险、结构化原因、证据索引、技术审计
  和免责声明八节；
- 原始报告文本仅用于复制或按需展开，保持 `UI_PRESENTATION_ONLY`。

### 3.5 资格与安全

- 顶部资格区压缩，运行和安全边界移入折叠区；
- 完整 Demo 标识只保留在顶部、首屏与图表/报告水印；
- 研究预览专用 API 继续 GET-only；
- 没有 Java/Python/SQL/Flyway、Provider、数据库、Agent、Shadow、扫描或交易写路径变更。

## 4. 验证结果

| 验证 | 结果 |
|---|---|
| `npm run validate:research-preview` | 通过；五分区、折叠、Demo、六 run、GET-only、禁用文案与门禁检查均通过 |
| `npm run build` | 通过；命令包含 `vue-tsc -b`，TypeScript 与 Vite production build 均通过 |
| DEMO01 静态向量 | 通过；`INSUFFICIENT_DATA`、无正式 veto、回测缺失不补 0 |
| DEMO02 静态向量 | 通过；`REJECTED_BY_VETO`、POSITION_RISK 正式 veto 保留 |
| 三档响应式静态门禁 | 通过；覆盖 1920/1440/1366 所需的页面溢出、布局断点和组件内部滚动标记 |
| Vue Demo 运行检查 | 通过；Vite 在 `127.0.0.1:5173` 启动，目标 URL 返回 HTTP 200，8001/8080 均未监听，检查后端口已释放 |
| Java/Python/PostgreSQL | 未启动、未访问，不属于本前端阶段 |
| Provider/iFinD | 调用数均为 0 |

构建只出现仓库既有 npm 环境配置、Rollup 注释和主包大小提示；没有新增依赖，不影响成功。
当前执行环境没有可控浏览器实例，因此没有伪称完成截图级 DEMO01/DEMO02 点击与三档视口
人工视觉复核；相关语义、折叠状态和响应式约束由专用静态脚本验证，Vue 运行可达性已确认，
R1 用户实际视觉复验仍明确为未进行。该限制不冒充后端或数据库联调。

## 5. 修改范围

前端范围：

- `quant-web/src/views/ResearchPreviewWorkbench.vue`
- `quant-web/src/research-preview/`
- `quant-web/src/components/research-preview/`
- `quant-web/scripts/validate-research-preview.mjs`

文档范围：

- 本任务书与阶段记录；
- `CURRENT_STATE.md`、`ROADMAP.md`、`DECISIONS.md`；
- F2A 原任务书与阶段记录。

未修改 Java、Python、SQL、Flyway、Provider/Shadow 后端、`PROGRESS_LOG.md` 或其他业务页面。

## 6. 权威状态

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- F2A：最终提交已验收并合入；
- F2A 首次视觉验收：`BLOCKED`；
- F2A-R1：任务分支实现完成，待 ChatGPT 实际 Git 提交验收，尚未合入；
- R1 用户视觉复验：未进行；
- BaoStock：`PENDING_WRITTEN_PERMISSION`；
- Provider/iFinD 调用：`0`；
- 数据库访问：未发生；
- 正常业务库 V13：未执行；
- Agent/Shadow/Day 002：未创建；
- scheduler：关闭；
- F1/F2B/F3、3A-R3B-1、3B：未开始；
- merge：否；
- `.ai/`：只通过 Git 状态确认未跟踪，未读取、修改、暂存或提交。

R1 合入后仍必须由用户实际复验并明确认可，后续独立治理提交才可讨论
`FREE_PRODUCT_PREVIEW_GATE=PASS`。
