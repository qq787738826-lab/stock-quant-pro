# 3A-R3B-F2A-R1 研究预览视觉与交互收敛任务书

## 1. 状态与身份

状态：**最终提交已通过 ChatGPT 实际 Git 技术复验，经用户批准纯 fast-forward 合入；
用户已于 2026-07-29 完成第二次视觉复验，信息架构主体通过，但产品预览门仍因三个定向
展示问题和 DEMO02 待最终复验而 BLOCKED。**

- 冻结集成基线：`f5137e2422a48e70d5c706cb146fb034a2b96f65`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-r1-preview-ux-convergence`
- 目标提交：`feat(agent): refine research preview experience`
- 最终提交：`e2b9457e3594676875167a703ae09ebc75aaaaf6`
- 页面路由：`/research-preview`
- 页面菜单：`研究预览`
- 实施路径：`FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST`
- 产品预览门：`FREE_PRODUCT_PREVIEW_GATE=BLOCKED`
- 阶段记录：
  [stage-3ar3b-f2a-r1-preview-ux-convergence.md](../stage-3ar3b-f2a-r1-preview-ux-convergence.md)
- 原 F2A 任务书：
  [3ar3b-f2a-research-preview-product.md](3ar3b-f2a-research-preview-product.md)
- R1A 定向修复：
  [3ar3b-f2a-r1a-visual-semantics-fix.md](3ar3b-f2a-r1a-visual-semantics-fix.md)

R1 只改进 F2A 展示层。它不修改 Agent、总控、数据资格、Demo 事实、GET-only 边界或
POSITION_RISK 唯一正式否决权，也不授予 Provider、PIT、Shadow、收益或交易资格。

## 2. 前置事实与首次视觉结论

- F2A 最终提交 `f5137e2422a48e70d5c706cb146fb034a2b96f65` 已通过 ChatGPT
  实际 Git 验收，经用户批准纯 fast-forward 合入；
- R1 最终提交 `e2b9457e3594676875167a703ae09ebc75aaaaf6` 已通过 ChatGPT
  实际 Git 技术复验，经用户批准纯 fast-forward 合入；
- 用户于 `2026-07-29` 通过完整页面截图完成首次视觉查看；
- 双模式隔离、候选池、固定六智能体、总控、证据、历史对比和报告均可见；
- 首次视觉验收因页面过长、入口不明确、总控不突出、技术字段过多、字体偏小、层级不足、
  Demo 标签重复和报告偏日志化而 `BLOCKED`；
- `FREE_PRODUCT_PREVIEW_GATE=BLOCKED`；
- 用户于同日完成 R1 第二次视觉复验：五个 Tab、首屏层级、精简 Agent 卡片、默认折叠
  详情、结构化报告、字号和深色主题方向已通过查看；DEMO01 首屏重叠、“数据可靠性”语义
  歧义、INFO finding 红色样式和 DEMO02 正式 veto 待最终查看继续阻断 PASS；
- 正常业务库 V13 未执行，Provider 与 iFinD 调用数为 0；
- F1、F2B、F3、3A-R3B-1 和 3B 均未开始；
- Day 002 未创建，scheduler 关闭。

## 3. 冻结边界

R1 必须保持：

1. 本地模式为
   `EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED/READ_ONLY`；
2. Demo 为
   `TEST_DEMO_EXPLICIT/SYNTHETIC/NOT_REAL_MARKET_RESULT`；
3. 研究预览专用 API 只使用 GET；
4. 六 run 数量、身份和顺序不变；
5. POSITION_RISK 是唯一能形成正式 veto 的专业智能体；
6. score、confidence、总控、finding、evidence 和 veto 只展示既有值；
7. 缺失值显示“暂无”，不补 0；
8. reasonCode 只来自显式结构字段或冻结 UI code；
9. 综合报告保持 `UI_PRESENTATION_ONLY`；
10. Demo 与本地任务、对比、图表和报告状态相互隔离。

R1 禁止新增 Java/Python/SQL/Flyway、数据库访问、Provider 调用、Agent/Shadow/扫描任务、
行情或公告更新、交易写路径、LLM、评分、预测或投资动作。

## 4. 页面信息架构

页面固定为五个主分区：

| section | 中文分区 | 默认内容 |
|---|---|---|
| `overview` | 研究总览 | 首屏结论、候选池、总控、三类风险摘要和主要原因 |
| `agents` | 六智能体 | 精简 Agent 卡片、技术详情折叠和 score/confidence 图表 |
| `evidence` | 证据与审计 | 证据分布、折叠证据项和集中技术审计详情 |
| `history` | 历史对比 | 历史筛选与列表，任务 A/B 对比默认折叠 |
| `report` | 综合报告 | 八段结构化业务报告，原始文本默认折叠 |

默认分区为 `overview`。URL query 可持久化 `section`；非法值必须安全回退
`overview`。模式切换必须清空旧模式选中任务和对比状态。

## 5. 首屏研究总览

首屏展示：

- 当前股票代码、名称和交易日期；
- 当前扫描任务与选中标的；
- 数据资格与 Demo 身份；
- 总控中文结论和原始 code；
- 确定性研究动作；
- 数据质量门禁与研究证据完整性（原 R1 的含混“数据可靠性”字段由 R1A 拆分）；
- 正式 veto 状态；
- 六 run 完成数和固定状态摘要；
- 三至五条主要结构化原因。

研究动作只允许以下展示映射：

| finalDecision | 研究动作 |
|---|---|
| `REJECTED_BY_VETO` | 因正式风险否决停止研究 |
| `BLOCKED_BY_DATA_QUALITY` | 等待数据质量修复 |
| `INSUFFICIENT_DATA` | 暂不形成研究结论 |
| `RESEARCH_ONLY` | 仅作研究记录 |
| `WATCH` | 继续观察 |
| `PASS_TO_MANUAL_REVIEW` | 进入人工研究复核 |

R1A 后，数据质量门禁只读取 DATA_QUALITY 既有状态和 gate，研究证据完整性只按总控和
STRATEGY_BACKTEST 结构状态确定性展示；两者都不生成新评分或可靠性认证。

## 6. 各分区收敛

### 6.1 全局资格与候选

- 顶部默认只保留模式、资格、只读、合成状态和免责声明；
- Provider、iFinD、Shadow、scheduler 与 V13 状态进入“运行与安全边界”折叠区；
- 完整 Demo 三标签只保留在顶部、首屏标的附近和图表/报告水印；
- 候选表固定适中高度，内部滚动；
- 当前候选高亮并显示“当前分析”，支持键盘 Enter/Space；
- 切换候选同步更新首屏、六智能体、图表、证据、报告与历史当前任务。

### 6.2 六智能体

默认卡片只显示中文名称、status、gateStatus、score、confidence、一句话 summary、阻断或
正式 veto 状态、finding 数量和主要 reasonCode。runId、attempt、时间、duration、
executionMode、完整 finding、evidenceIds、errors 与 outputJson 全部进入默认关闭的
“技术详情”。

DATA_QUALITY 阻断和 POSITION_RISK 正式 veto 必须醒目。正式 veto 以独立 veto 列表为
权威，不能由普通 `run.veto` 推断。

### 6.3 证据与审计

证据首页只显示数量、category/sourceType 分布、重复 ID 告警、资格和 contentHash 说明。
每条 evidence 及 fields 默认折叠；长 JSON 在组件内部滚动。taskId、ruleVersion、
contextSchemaVersion、contextHash、triggerType、executionMode、sourceRunIds、vetoIds 和
任务时间集中到默认关闭的“技术审计详情”。

固定说明：

> 证据展示不改变其原始来源资格，本地contentHash不得解释为Provider revision。

### 6.4 历史与报告

历史任务列表和筛选默认可见，当前任务高亮；“展开任务对比”默认关闭。对比仍不判断优劣，
关键维度不一致时告警，Demo 与本地模式禁止混比。

综合报告默认拆为研究结论、数据资格与限制、六智能体摘要、主要风险、结构化原因、证据索引、
技术审计摘要和免责声明八节。原始纯文本只用于复制和按需查看，不作为默认主视图。

## 7. Demo 冻结场景

### DEMO01

- finalDecision：`INSUFFICIENT_DATA`；
- 研究动作：暂不形成研究结论；
- 无正式 veto；
- STRATEGY_BACKTEST 不可用；
- 缺失 score/confidence 显示“暂无”。

### DEMO02

- finalDecision：`REJECTED_BY_VETO`；
- 研究动作：因正式风险否决停止研究；
- 正式 veto 只来自 POSITION_RISK；
- vetoCode、reason 与 evidenceIds 可展开；
- 页面不生成投资或交易动作。

R1 不修改两组 Demo 的冻结事实数值、六 run 顺序或 evidenceId。

## 8. 视觉与响应式

- 中文业务正文不低于 13px，次要说明不低于 12px；
- 技术 code 可为 11px，但不能承担核心业务信息；
- 深色专业终端方向保持不变；
- 红色只用于错误、正式 veto 或严重不可用，橙色用于警告/不足，蓝色用于信息，绿色用于通过/
  只读安全状态；
- 1920×1080、1440×900、1366×768 均不得出现页面级横向滚动；
- Agent 卡片按宽度形成三列、两列或单列；
- 表格和 JSON 仅在组件内部滚动，长 Hash 自动换行；
- ECharts 保持已有依赖、空值语义、Demo 水印、resize 和 dispose。

## 9. 自动门禁

`npm run validate:research-preview` 必须继续验证原 F2A 全部门禁，并增加：

1. 五分区、默认 overview 和非法 section 回退；
2. 首屏总览与六种研究动作映射；
3. 技术审计、Agent、证据和历史对比默认折叠；
4. 八段结构化报告和原始文本默认折叠；
5. 候选选中状态；
6. DEMO01 数据不足与 DEMO02 正式 POSITION_RISK veto；
7. 精确六 run 顺序和唯一 evidenceId；
8. 专用 API GET-only；
9. 不出现交易动作或收益预测文案；
10. `FREE_PRODUCT_PREVIEW_GATE=BLOCKED`。

同时必须通过生产构建、完整 TypeScript 检查、`git diff --check`、Markdown/UTF-8/换行/
尾随空白、变更范围和禁止文件检查。

## 10. 完成边界

任务分支完成后仍必须保持：

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

并保持：

- Provider 与 iFinD 调用：`0`
- 数据库访问与正常业务库 V13：未发生/未执行
- Agent、Shadow 与 Day 002：未创建
- scheduler：关闭
- F1/F2B/F3、3A-R3B-1、3B：未开始
- R1 merge：已纯 fast-forward 合入
- R1 用户视觉复验：`2026-07-29` 已进行，主体通过但总体仍 `BLOCKED`
- R1A：任务分支实现完成，待 ChatGPT 实际 Git 提交验收，尚未合入
- R1A 用户最终视觉复验：未进行

只有 R1A 实际 Git 提交通过 ChatGPT 验收、用户批准合入，且用户最终实际查看 DEMO01
和 DEMO02 并明确认可，后续独立治理提交才可讨论
`FREE_PRODUCT_PREVIEW_GATE=PASS`。
