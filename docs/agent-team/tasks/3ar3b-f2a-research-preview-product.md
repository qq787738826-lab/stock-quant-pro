# 3A-R3B-F2A 免费研究预览产品任务书

## 1. 状态与身份

状态：**产品技术实现与 Codex 本地静态验证已在任务分支完成，待 ChatGPT 基于实际
Git 提交验收，尚未合入；用户视觉验收尚未进行。**

- 冻结集成基线：`08943b4f6af03c75aa4df2a4ecf2494bede4e57b`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f2a-research-preview-product`
- 目标提交：`feat(agent): add research preview product`
- 页面路由：`/research-preview`
- 页面菜单：`研究预览`
- 实施路径：`FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST`
- 产品预览门：`FREE_PRODUCT_PREVIEW_GATE=BLOCKED`
- 阶段记录：
  [stage-3ar3b-f2a-research-preview-product.md](../stage-3ar3b-f2a-research-preview-product.md)

F2A 是轨道 A 的产品形态实现，不是 Provider Adapter、PIT 数据接入、Shadow
效果验证或投资建议能力。技术实现完成后，产品预览门仍须等待用户实际看到页面并明确
认可，不能由 Codex 自行改为 `PASS`。

## 2. 前置事实

- 3A-R3B-F0 已验收并合入，结论为 `F0_AUDIT_RESULT=PARTIAL`；
- 3A-R3B-F0.5 最终提交
  `08943b4f6af03c75aa4df2a4ecf2494bede4e57b` 已通过 ChatGPT 实际 Git
  验收，经用户批准纯 fast-forward 合入；
- BaoStock 继续为 `PENDING_WRITTEN_PERMISSION`；
- `FREE_PROVIDER_VALIDATION_GATE=BLOCKED`；
- `PAID_PROVIDER_UPGRADE_DECISION=PENDING`；
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`；
- 正常业务库尚未执行 V13；
- F1、F2B、F3、3A-R3B-1 和 3B 均未开始；
- Day 002 未创建，scheduler 关闭，iFinD 真实调用数为 0。

## 3. 目标

使用现有前端、既有只读 GET API、已有本地研究快照、已持久化 Agent 结果与明确的
固定合成演示数据，形成可操作的专业研究预览工作台。页面必须让用户看见：

1. 候选股票池；
2. 单股分析元数据；
3. 固定六个专业智能体；
4. 六智能体 score/confidence 可视化；
5. Java 已持久化的总控综合结论；
6. 可靠回测可用或结构化不可用状态；
7. 公告风险、持仓风险和正式 veto；
8. evidence、lineage、contentHash 和 fields；
9. reasonCode 的明确结构来源；
10. Agent 历史查询与两个已有任务的只读对比；
11. `UI_PRESENTATION_ONLY` 的确定性研究报告；
12. 数据资格、合成状态、只读边界与非投资建议声明。

本阶段不计算新的扫描分、Agent 分、总控分或回测结果，不创建任务，也不持久化任何
页面状态。

## 4. 现有接口审计

代码审计确认既有 GET 接口足以支持最小产品，无需新增 Java 生产接口：

### 4.1 扫描候选池

- `GET /api/scans/history`
- `GET /api/scans/latest-official-task`
- `GET /api/scans/latest-task`
- `GET /api/scans/{taskId}`
- `GET /api/scans/{taskId}/results`

### 4.2 Agent 历史与结果

- `GET /api/agent-tasks/history`
- `GET /api/agent-tasks/{taskId}`
- `GET /api/agent-tasks/{taskId}/runs`
- `GET /api/agent-tasks/{taskId}/evidence`
- `GET /api/agent-tasks/{taskId}/decision`
- `GET /api/agent-tasks/{taskId}/vetoes`

研究预览专用 API 只能调用这些 GET 能力。禁止原生 `fetch`、POST、PUT、PATCH、
DELETE，以及任务创建、扫描启动、行情刷新、公告摄取、Shadow、Portfolio、Provider
或回测启动函数。

## 5. 双模式

### 5.1 本地研究快照

- 模式：`EXISTING_RESEARCH_SNAPSHOT`
- 资格：`RESEARCH_HISTORICAL_UNVERIFIED`
- 属性：`READ_ONLY`

本模式只读取已有扫描任务、候选、Agent 任务、run、evidence、decision 和 veto。字段
不存在时显示“暂无”，不补 0、不重新计算、不创建或重试任务。API 不可用时固定显示
`PREVIEW_LOCAL_API_UNAVAILABLE`，并提供“切换到显式演示”按钮；不得静默回退到
Demo。

### 5.2 显式固定演示

- 模式和资格：`TEST_DEMO_EXPLICIT`
- 合成标识：`synthetic=true`
- 展示标签：`SYNTHETIC`、`NOT_REAL_MARKET_RESULT`

固定 JSON fixture 使用 `DEMO01`、`DEMO02` 和“演示标的A/B”，每个任务恰好包含按
冻结顺序排列的六个 run。只有 POSITION_RISK 可以携带正式 veto；总控必须引用全部
六个 runId；evidenceId 必须唯一。fixture 不包含真实价格、成交量、成交额、收益、
Provider 名称或 Provider revision，不访问网络、不持久化，也不深链到真实 taskId。

本地模式与 Demo 候选、任务、图表、对比和报告不得混合。模式切换会清空当前模式状态，
Demo task 使用负数本地占位 ID，URL 只为本地正数 taskId 建立深链。

## 6. 展示契约

### 6.1 候选与任务

候选池只展示已有扫描字段和安全 metrics 白名单，支持前端代码/名称搜索、Agent 结果
筛选和扫描历史切换。稳定预览 reasonCode 为：

- `PREVIEW_SCAN_SNAPSHOT_UNAVAILABLE`
- `PREVIEW_SCAN_RESULTS_EMPTY`
- `PREVIEW_AGENT_RESULT_NOT_FOUND`
- `PREVIEW_LOCAL_API_UNAVAILABLE`

单股工作台展示 symbol、tradeDate、taskId、ruleVersion、contextSchemaVersion、
contextHash、triggerType、executionMode、状态和生命周期时间，不提供创建、刷新、
重试、保存、交易或计划按钮。

### 6.2 六智能体与图表

固定顺序为 DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS、
STRATEGY_BACKTEST、ANNOUNCEMENT_RISK、POSITION_RISK。缺失 run 仍保留固定卡位并
显示“暂无”；前端不生成第七个智能体或中性分。

ECharts 只绘制已有 score/confidence，缺失值使用无数据标记，不绘制为 0，也不计算
综合分。组件使用单实例、`ResizeObserver`、卸载断开监听与 `dispose`；Demo 图表持续
显示 `TEST_DEMO_EXPLICIT` 水印。

### 6.3 总控、回测和风险

总控只展示已有 finalDecision，并明确：

- `PASS_TO_MANUAL_REVIEW` 只表示进入人工研究复核；
- `BLOCKED_BY_DATA_QUALITY` 不是正式风险否决；
- `REJECTED_BY_VETO` 只能来自 POSITION_RISK；
- `INSUFFICIENT_DATA` 不是中性或安全结论。

回测区域只读 STRATEGY_BACKTEST 的 outputJson、findings 与 evidence。真实可靠结果
不可用时显示 `UNAVAILABLE_WITH_REASON` 及结构化 reasonCode；Demo 固定标记
`TEST_DEMO_EXPLICIT`，不展示为真实历史收益。

公告和持仓区域只展示现有结构化结果。POSITION_RISK 的 vetoCode、reason 和
evidenceIds 保持可见；页面不读取券商账户，也不产生仓位动作。

### 6.4 evidence、reasonCode、历史和对比

evidence 以 evidenceId 去重；重复 ID 显式警告但不隐藏事实。sourceType、sourceName、
sourceRef、symbol、tradeDate、observedAt、collectedAt、contentHash 和 fields 均只读
展示，并明确本地 Hash 不是 Provider revision。

reasonCode 只从以下结构提取：

- `finding.code`：`AGENT_FINDING`
- `outputJson.errors[].code` 或既有 reasonCode：`AGENT_ERROR`
- `veto.vetoCode`：`FORMAL_VETO`
- task errorMessage：`TASK_ERROR`
- run errorMessage：`RUN_ERROR`
- 页面固定错误：`PREVIEW_UI`

禁止从 summary 自然语言猜测 code。

历史查询固定使用 `GET /api/agent-tasks/history?page=0&size=100` 的通用
`PageResult<T>`，支持 symbol、taskId、tradeDate、ruleVersion、status 筛选和 URL
恢复 taskId。对比只展示两个既有任务的结构差异；symbol、tradeDate 或 ruleVersion
不一致时警告不可直接比较，不判断优劣。

### 6.5 UI 报告

报告固定标记 `UI_PRESENTATION_ONLY`，只拼接任务元数据、权威 summary、run summary、
finding、reasonCode、资格和不可用原因。它不调用 LLM、不打分、不预测、不重写权威
结论，也不生成买卖或仓位建议。复制操作只写系统剪贴板，不写数据库或自动导出文件。

## 7. 静态安全验证

新增 `npm run validate:research-preview`，无新增 npm 依赖。脚本必须验证：

1. 专用模块没有写请求或原生 fetch；
2. 没有导入任务、Shadow、扫描、回测、Portfolio 或 Provider 写入口；
3. Demo 模式、资格和 synthetic 标识固定；
4. symbol 为非真实 `DEMOxx`；
5. 每个 Demo 任务恰好六个固定 run；
6. 只有 POSITION_RISK 可以 veto；
7. sourceRunIds 与六个 run 一致；
8. evidenceId 唯一；
9. fixture 无真实市场数值字段或 Provider 名称；
10. 页面存在路由、菜单、免责声明和资格标签；
11. API 错误不会静默切换 Demo；
12. ECharts resize/dispose 生命周期存在；
13. `FREE_PRODUCT_PREVIEW_GATE=BLOCKED`。

## 8. 静态验收场景

| 场景 | 静态实现结果 |
|---|---|
| Demo 模式 | 无 Java、数据库或网络依赖；固定候选、六 run、总控、证据、reasonCode 和 Demo 标签完整 |
| 本地 API 不可用 | 显示 `PREVIEW_LOCAL_API_UNAVAILABLE`，保持本地模式并提供显式 Demo 按钮 |
| 候选池为空 | 显示 `PREVIEW_SCAN_RESULTS_EMPTY`，不伪造候选，Agent 历史仍可独立浏览 |
| 候选无 Agent 结果 | 显示 `PREVIEW_AGENT_RESULT_NOT_FOUND`，不创建 Agent 任务 |
| 完整已有任务 | 六 run 固定排序，总控/veto/evidence/reason 只读映射，不重算评分 |
| 部分已有任务 | 缺失项显示“暂无”或结构化不可用，不补 0、不伪造结论 |
| 对比维度不同 | symbol/date/ruleVersion 不一致时警告，不判断哪个任务更好 |

以上是离线构建和静态代码路径验收，不冒充正常业务库真实联调；真实本地模式联调仍须
后续单独授权。

## 9. 状态与完成边界

任务分支完成后必须保持：

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

并保持：

- 免费 Provider 调用：`0`
- iFinD 真实调用：`0`
- 数据库访问/写入：未发生
- 正常业务库 V13：未执行
- Agent 任务与 Shadow：未创建
- F1/F2B/F3：未开始
- Day 002：未创建
- scheduler：关闭
- 3A-R3B-1/3B：未开始
- merge：否

F2A 只有在 ChatGPT 基于实际 Git 提交验收、用户批准合入，并由用户实际看到页面、明确
认可产品形态后，才可通过独立治理提交讨论把
`FREE_PRODUCT_PREVIEW_GATE` 改为 `PASS`。
