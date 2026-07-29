# 3A-R3B-F2A-R1A 首屏布局与风险语义修正任务书

## 1. 状态与身份

状态：**R1A 前端修复已在任务分支完成并通过 Codex 本地验证，待 ChatGPT 基于实际
Git 提交验收；尚未合入，R1A 用户最终视觉复验尚未进行。**

- 冻结集成基线：`e2b9457e3594676875167a703ae09ebc75aaaaf6`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-r1a-visual-semantics-fix`
- 目标提交：`fix(agent): refine preview layout and risk semantics`
- 页面路由：`/research-preview`
- 产品预览门：`FREE_PRODUCT_PREVIEW_GATE=BLOCKED`
- 阶段记录：
  [stage-3ar3b-f2a-r1a-visual-semantics-fix.md](../stage-3ar3b-f2a-r1a-visual-semantics-fix.md)
- R1 任务书：
  [3ar3b-f2a-r1-preview-ux-convergence.md](3ar3b-f2a-r1-preview-ux-convergence.md)

R1A 只修复第二次用户视觉复验发现的首屏布局、数据语义和 finding 风险颜色问题。它不
重新设计产品，不改变 Demo 事实、Agent/总控结果、数据资格、只读边界、六 run 顺序或
POSITION_RISK 唯一正式否决权。

## 2. 前置事实

- F2A 最终提交 `f5137e2422a48e70d5c706cb146fb034a2b96f65` 已验收并合入；
- R1 最终提交 `e2b9457e3594676875167a703ae09ebc75aaaaf6` 已通过 ChatGPT
  实际 Git 技术复验，经用户批准纯 fast-forward 合入；
- 用户于 `2026-07-29` 完成 R1 第二次视觉复验；
- 五个 Tab、首屏层级、精简 Agent 卡片、默认折叠详情、结构化报告、字号和深色主题方向
  已通过查看；
- 产品门仍因首屏文字重叠、数据质量与证据充分性语义混淆、INFO finding 使用严重红色，
  以及 DEMO02 正式 veto 尚待最终查看而 `BLOCKED`。

## 3. 首屏布局契约

`ResearchOverviewPanel.vue` 的头部必须使用稳定布局：

1. 桌面默认两列：
   `minmax(0, 1fr) minmax(260px, 360px)`；
2. 标的信息与资格摘要均设置 `min-width: 0`；
3. 股票代码、名称、日期、扫描任务、资格和 Demo 身份允许自然换行；
4. 不晚于 `1400px` 降为单列；
5. header 由内容自然撑高；
6. 禁止绝对定位、负 margin、固定高度裁切或以 `overflow: hidden` 掩盖重叠。

1920×1080、1440×900、1366×768 和 125% 缩放等效宽度必须由同一响应式契约覆盖，
不得产生页面级横向溢出。

## 4. 数据语义拆分

原含混的“数据可靠性”展示被废止，改为两个纯展示字段。

### 4.1 数据质量门禁

只读取 DATA_QUALITY run：

| 结构状态 | 中文展示 |
|---|---|
| `gateStatus=PASS` | 通过 |
| `gateStatus=WARN` | 警告 |
| `gateStatus=BLOCKED` | 阻断 |
| `gateStatus=NOT_APPLICABLE` | 不适用 |
| `status=INSUFFICIENT_DATA` | 数据不足 |
| `status=FAILED` | 失败 |
| `status=SKIPPED` | 已跳过 |
| run 缺失 | 暂无 |

非完成状态不得因同时存在 PASS gate 而显示为“通过”。该字段不创建评分。

### 4.2 研究证据完整性

按以下优先级进行确定性展示：

1. `BLOCKED_BY_DATA_QUALITY` → 受数据质量阻断；
2. `INSUFFICIENT_DATA` → 不足；
3. STRATEGY_BACKTEST 缺失或为 `INSUFFICIENT_DATA/FAILED/SKIPPED` → 不足；
4. finalDecision 缺失 → 暂无；
5. 其他已有持久化结果 → 已有研究证据。

该状态不是新 Agent 结果、评分或可靠性认证，不得显示“充分可靠”“已验证有效”“可交易”
等没有权威依据的语义。

DEMO01 固定展示：

```text
总控结论=数据不足
研究动作=暂不形成研究结论
数据质量门禁=通过
研究证据完整性=不足
正式veto=无
```

DEMO02 的数据质量门禁和证据完整性仍按同一映射读取，不得因正式 veto 伪造其他状态。

## 5. 风险 tone 契约

综合报告的 finding/veto 使用白名单 tone：

| level | tone | 视觉含义 |
|---|---|---|
| `INFO` | `info` | 蓝色或中性蓝灰信息 |
| `WARN` | `warning` | 橙色/琥珀色警告 |
| `HIGH` | `danger` | 红色严重风险 |
| `CRITICAL` | `danger` | 红色严重风险 |
| `FORMAL_VETO` | `formal-veto` | 独立强化红色正式否决 |
| 未知 | `neutral` | 普通蓝灰 |

tone 只能由纯函数白名单生成。模板只消费规范化 tone，不能把任意输入直接拼成不受控 CSS
语义。DEMO01 的 INFO 公告/持仓 finding 不得显示为严重红色；DEMO02 的 POSITION_RISK
正式 veto 必须保留独立红色样式、vetoCode、reason 和 evidenceIds。

## 6. 回归与安全边界

必须继续保持：

- 五个 Tab、默认 overview 和 section URL 恢复；
- Demo 与本地研究模式隔离；
- 研究预览专用 API GET-only；
- 六智能体固定顺序且没有第七个 Agent；
- POSITION_RISK 唯一正式 veto；
- Agent、证据、技术审计、历史对比和原始报告默认折叠；
- ECharts null 不补 0、Demo 水印、resize/dispose；
- `UI_PRESENTATION_ONLY` 和非投资建议声明；
- 无 LLM、Provider、数据库、Agent/Shadow 创建或交易动作。

R1A 不修改 `demo.fixture.json`、后端、路由、菜单、API、依赖或其他业务页面。

## 7. 自动与视觉验证

`npm run validate:research-preview` 必须额外验证：

1. “数据可靠性”不再作为首屏/报告字段；
2. 两个独立数据字段和 DEMO01 映射存在；
3. 风险 level 到 tone 的白名单及 neutral fallback；
4. INFO 不得映射 danger/formal-veto；
5. DEMO02 仍为 `REJECTED_BY_VETO` 且包含 POSITION_RISK 正式 veto；
6. overview 使用稳定 Grid、自然高度和 1400px 单列降级；
7. 禁止绝对定位、负 margin、固定 header 高度和溢出隐藏；
8. `FREE_PRODUCT_PREVIEW_GATE=BLOCKED`。

同时必须通过 `npm run build`（包含 `vue-tsc -b`）、`git diff --check`、Markdown
链接/表格/UTF-8/换行/尾随空白及变更范围检查。

仅允许启动 Vue Demo 做前端自检。浏览器不可控时必须如实记录，不能冒充截图级用户验收；
自检后必须停止 Vue。

## 8. 完成边界

任务分支完成后继续保持：

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

并保持 Provider/iFinD 调用为 0、数据库未访问、V13 未执行、Agent/Shadow/Day 002 未创建、
scheduler 关闭，F1/F2B/F3、3A-R3B-1 和 3B 未开始。R1A 合入后仍必须由用户实际查看
DEMO01 和 DEMO02 并明确认可，后续独立治理提交才可讨论产品预览门。
