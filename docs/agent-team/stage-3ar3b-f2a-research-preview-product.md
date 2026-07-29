# 3A-R3B-F2A 免费研究预览产品阶段记录

## 1. 阶段状态

状态：**前端技术实现已验收并合入；首次视觉验收的历史结论为 `BLOCKED`，经
R1、R1A 与 R1B 连续收敛后，用户已明确通过最终产品形态验收。**

- 冻结集成基线：`08943b4f6af03c75aa4df2a4ecf2494bede4e57b`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-research-preview-product`
- 目标提交：`feat(agent): add research preview product`
- 最终提交：`f5137e2422a48e70d5c706cb146fb034a2b96f65`
- 完整任务书：
  [tasks/3ar3b-f2a-research-preview-product.md](tasks/3ar3b-f2a-research-preview-product.md)
- 路由：`/research-preview`
- 菜单：`研究预览`
- R1 任务书：
  [tasks/3ar3b-f2a-r1-preview-ux-convergence.md](tasks/3ar3b-f2a-r1-preview-ux-convergence.md)

F0.5 最终提交 `08943b4f6af03c75aa4df2a4ecf2494bede4e57b` 已通过 ChatGPT
实际 Git 验收、获得用户批准并纯 fast-forward 合入。精确验收与批准时间没有仓库证据，
记为 `UNKNOWN`。

F2A 最终提交已完成相同验收与纯 fast-forward 合入；用户于 `2026-07-29` 通过完整页面
截图完成首次视觉查看。功能闭环、Demo 隔离、六智能体、总控、证据、历史对比和报告均
可见，但信息密度和层级尚不适合日常使用，因此该轮历史视觉验收为 `BLOCKED`，进入独立
R1 视觉与交互收敛。R1、R1A 和 R1B 随后依次完成五分区、语义/风险颜色与最终垂直流
布局修复；用户基于 R1B 最终提交 `4917bbabc8262106abb47e6cb90cf7ab96e76d7d`
完成最终复验，并于 `2026-07-29 16:44 +08:00` 明确认可产品形态。

## 2. 实施结果

### 2.1 产品入口与双模式

- 新增独立 `/research-preview` 工作台，菜单顺序为
  “AI分析 → 研究预览 → 智能体团队 → 影子观测”；
- 本地模式固定为
  `EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED/READ_ONLY`；
- Demo 固定为
  `TEST_DEMO_EXPLICIT/SYNTHETIC/NOT_REAL_MARKET_RESULT`；
- 模式切换清空当前模式状态，本地错误不会静默切换 Demo；
- 页面顶部和主要区域持续显示模式、资格、合成状态、只读边界、外部调用数为 0、
  Shadow/V13 状态和非投资建议声明。

### 2.2 只读数据链

实现只复用既有扫描和 Agent GET API。候选池读取已有扫描任务/结果；Agent 历史使用
`PageResult<AgentTask>` 分页读取；任务详情并行读取 task、六 run、evidence、decision
和 veto。专用模块没有 POST、PUT、PATCH、DELETE、原生 fetch 或任务/Shadow/扫描/
回测/Portfolio/Provider 写入口。

没有新增 Java、Python、SQL、Flyway、Provider、Shadow 后端或数据库能力。

### 2.3 页面区域

- 候选池：已有扫描任务切换、代码/名称搜索、Agent 结果筛选、安全 metrics 和空状态；
- 单股任务：元数据、ruleVersion、contextSchemaVersion、contextHash、执行模式和时间；
- 六智能体：固定顺序卡片、finding、reasonCode、evidenceIds、错误和生命周期；
- ECharts：已有 score/confidence 柱状图、门禁辅助标识、Demo 水印、resize/dispose；
- 总控：原始 code、中文名、gate、vetoed、score/confidence、summary、run/veto IDs；
- 回测：仅显示已有 STRATEGY_BACKTEST，可靠结果缺失时展示结构化原因；
- 公告/持仓：已有 finding/evidence 与 POSITION_RISK 正式 veto；
- evidence/lineage：去重告警、来源、日期、时间、contentHash 和折叠 fields；
- reasonCode：只从结构化 finding/error/veto/task/run/UI code 提取，不解析 summary；
- 历史：100 条分页、筛选、taskId URL 恢复和详情加载；
- 对比：两个同模式已有任务的元数据、总控、六 run、证据、veto 和 reason 集合；
- 报告：确定性 `UI_PRESENTATION_ONLY` 文本，可复制，不调用 LLM 或写数据库。

### 2.4 Demo 隔离

固定 fixture 只含 `DEMO01/DEMO02`、演示标的名称、负数本地 taskId、非 SHA 占位
contextHash、精确六 run 和唯一 evidenceId。它不含真实 OHLC、volume、amount、收益、
Provider 名称或 revision，不进入任何网络/扫描/Agent/Shadow/交易接口，也不能与本地
任务进入同一对比。

## 3. 七个静态验收场景

| 场景 | 结果 |
|---|---|
| Demo 模式离线展示 | 通过：无需 Java/数据库/网络，候选、六 run、总控、证据、reason 和标签完整 |
| 本地 API 不可用 | 通过：`PREVIEW_LOCAL_API_UNAVAILABLE`，不自动 Demo，显式按钮可切换 |
| 本地候选池为空 | 通过：`PREVIEW_SCAN_RESULTS_EMPTY`，不伪造候选，历史独立可用 |
| 候选无 Agent 结果 | 通过：`PREVIEW_AGENT_RESULT_NOT_FOUND`，不创建任务 |
| 已有完整任务 | 通过：固定 run 顺序、总控/veto/evidence/reason 映射，不重算 |
| 已有部分任务 | 通过：缺失值为“暂无”或不可用原因，不补 0 |
| 对比关键维度不同 | 通过：显示不可直接比较警告，不判断优劣 |

这些结果来自静态代码路径、安全校验和生产构建，不是正常业务库联调证据。

## 4. 自动验证

| 验证 | 结果 |
|---|---|
| `npm run validate:research-preview` | 通过；GET-only、Demo 六 run、唯一 evidence、路由/菜单/标签、无静默回退、ECharts 生命周期和 gate 均通过 |
| `npm run build` | 通过；`vue-tsc -b` 完整类型检查和 Vite 生产构建通过 |
| `git diff --check` | 通过 |
| Markdown 相对链接、表格、UTF-8、结尾换行和尾随空白 | 通过 |
| 变更范围和禁止文件 | 通过；未修改 Java/Python/SQL/Flyway/Provider/Shadow 后端或 `PROGRESS_LOG.md` |

构建仍有仓库既有的 npm unknown env 配置、Rollup PURE 注释和主包大小提示；不影响构建
成功，本阶段没有新增依赖。

## 5. 权威状态

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- F2A 技术实现：最终提交 `f5137e2422a48e70d5c706cb146fb034a2b96f65`
  已验收并纯 fast-forward 合入；
- 首次用户视觉验收（历史状态）：`2026-07-29` 已进行，该轮结论为 `BLOCKED`；
- F2A-R1：最终提交 `e2b9457e3594676875167a703ae09ebc75aaaaf6` 已验收并合入；
- F2A-R1A：提交 `99b22a5e3bd2ad945c2f2b10ae79618277f8ed01` 与
  `11657c572d9561ae3b4a37be7a22f7456444844f` 已验收并合入；
- F2A-R1B：最终提交 `4917bbabc8262106abb47e6cb90cf7ab96e76d7d` 已验收并合入；
- 最终用户视觉验收：用户已查看 DEMO01/DEMO02，并于 `2026-07-29 16:44 +08:00`
  明确认可当前产品形态；
- 产品门治理：
  [任务书](tasks/3ar3b-f2a-product-preview-gate-pass.md) /
  [阶段记录](stage-3ar3b-f2a-product-preview-gate-pass.md) 已在独立任务分支落地，待
  ChatGPT 实际 Git 验收和合入；
- BaoStock：`PENDING_WRITTEN_PERMISSION`；
- 免费 Provider 与 iFinD 调用：`0`；
- 数据库访问与写入：未发生；
- 正常业务库 V13：未执行；
- Agent/Shadow/Day 002：未创建；
- scheduler：关闭；
- F1/F2B/F3、3A-R3B-1、3B：未开始；
- F2A/R1/R1A/R1B merge：是；产品门治理提交 merge：否；
- `.ai/`：只通过 Git 状态确认未跟踪，未读取、修改、暂存或提交。

`FREE_PRODUCT_PREVIEW_GATE=PASS` 只表示用户认可产品形态和日常只读研究流程，不改变
免费 Provider 验证门，不证明 PIT/QFQ、策略收益或 Shadow，也不形成 F3 效果样本。
