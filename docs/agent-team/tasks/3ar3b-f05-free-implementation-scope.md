# 3A-R3B-F0.5 免费版实施范围与双轨路线冻结任务书

## 1. 状态与范围

状态：**治理规划已通过 ChatGPT 对实际 Git 提交的验收，经用户批准并纯
fast-forward 合入；用户随后已单独授权 F2A，F2A 技术实现当前在独立任务分支待验收，
F1、F2B 与 F3 仍未开始。**

- 冻结集成基线：`059eacffaf7e4a9f383be205d453c5168279932a`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f05-free-implementation-scope`
- 目标提交：`docs(agent): freeze free implementation scope`
- 最终提交：`08943b4f6af03c75aa4df2a4ecf2494bede4e57b`
- 阶段记录：
  [stage-3ar3b-f05-free-implementation-scope.md](../stage-3ar3b-f05-free-implementation-scope.md)
- 当前事实：
  [CURRENT_STATE.md](../CURRENT_STATE.md)
- 路线图：
  [ROADMAP.md](../ROADMAP.md)
- 跨阶段决定：
  [DECISIONS.md](../DECISIONS.md)

本阶段只冻结实施顺序、产品预览边界和门禁。没有开发页面或生产代码，没有调用任何免费
Provider 或 iFinD，没有访问数据库，没有执行正常业务库 V13，也没有创建 Day 002 或
启动 Shadow。该历史边界不否定用户随后对独立 F2A 开发阶段的明确授权。

## 2. F0 已验收事实

- F0 冻结基线：`c47b88e586f6751563fe210f40137a3b7ce5e576`；
- F0 最终提交：`059eacffaf7e4a9f383be205d453c5168279932a`；
- ChatGPT 已基于实际 Git 最终提交复验通过；
- 用户已批准并完成纯 fast-forward 合入；
- 本地和远程集成分支均位于最终提交，ahead/behind 为 `0/0`；
- F0 已完成逐来源/逐事实审计、BaoStock 最小受控探针、独立审计工具和离线测试；
- F0 结论固定为 `F0_AUDIT_RESULT=PARTIAL`。

`PARTIAL` 不是审计失败。它表示：

1. BaoStock 有部分技术能力，但仍为 `PENDING_WRITTEN_PERMISSION`；
2. 独立 factor 仅为 `PARTIAL`，`DAILY_EXACT=UNVERIFIED`；
3. AKShare 各实际上游只能承担 `RESEARCH_AUXILIARY_ONLY`；
4. CNINFO、SSE、SZSE、SZSI 只能承担 `OFFICIAL_EVIDENCE_ONLY`；
5. 当前没有免费来源能单独承担完整 V13/QFQ 同源 lineage。

这些事实不批准 F1，也不提升任何来源为正式 Provider 或 PIT。

## 3. 实施路径决定

正式状态冻结为：

```text
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
```

含义：系统不等待完整免费 Provider 或付费 Provider，先基于已有合法边界内的本地研究数据
和 TEST/DEMO 能力形成真实产品预览。

该决定不表示：

- BaoStock 已批准；
- `FREE_PROVIDER_VALIDATION_GATE` 已通过；
- 正式 Shadow 可以开始；
- 研究数据可以升级为 PIT；
- V13 用途许可可以绕过；
- 系统准确、策略有效或能够盈利；
- 可以形成投资建议或自动交易。

## 4. 双轨路线

### 4.1 轨道 A：产品形态验证

```text
F0.5
→ F2A 免费研究预览产品
→ 用户产品形态验收
→ 等待或并行推进 Provider 许可和技术证据
```

目标是在不新增外部调用、不购买数据、不伪造来源资格的前提下，让用户看见并判断产品
形态、日常流程、解释性和可追溯性。

### 4.2 轨道 B：免费 Provider 资格与前向 PIT

```text
F0
→ 书面许可或替代 Provider 证据补充
→ F1 免费 Provider Adapter 与 V13 接入
→ F2B 免费 Provider 支持的真实产品
→ F3 免费 Shadow 与效果评估
```

目标是解决书面许可、独立 factor、交易所日历身份、公司行动版本和真实 knowledge-time，
并在合法 `SYSTEM_KNOWLEDGE_PIT` 上形成 Provider-backed 产品与前向验证。

### 4.3 资格隔离

两条轨道可以等待或并行推进，但轨道 A 的页面和报告不得证明轨道 B 的许可、来源身份、
`DAILY_EXACT`、PIT、真实回测、Shadow 或效果已经完成。Mock 与真实研究数据不得混写。

## 5. F2A：免费研究预览产品

F2A 是 F0.5 验收并合入后唯一允许规划的下一实施阶段，但本任务不授权自动开始。启动仍
需要用户单独批准 F2A 任务。

F2A 不要求：

- F1 已完成；
- BaoStock 已取得书面许可；
- 正常业务库已执行 V13。

### 5.1 允许输入

#### A. 已存在的本地研究数据

只允许读取现有数据，不发起新的外部 Provider 调用，不扩大既有用途，必须标记：

```text
EXISTING_RESEARCH_SNAPSHOT
RESEARCH_HISTORICAL_UNVERIFIED
```

#### B. 已验收的固定 Mock

只允许使用 3A-R3B-0 已验收的固定能力，并必须标记：

```text
TEST_DEMO_EXPLICIT
```

Mock 结果不得与真实研究数据混写。

#### C. 已冻结结果

Agent、总控和历史结果只能按现有规则读取、解释和展示，不得改写权威结果。

### 5.2 最小产品范围

F2A 至少规划展示：

1. 股票候选池；
2. 单只股票分析工作台；
3. 固定六个智能体；
4. 总控综合结论；
5. DATA_QUALITY 状态；
6. 技术分析；
7. 市场环境；
8. 策略回测区域；
9. 公告风险；
10. 持仓风险；
11. evidence 和 lineage；
12. reasonCode；
13. 数据来源和资格标签；
14. 历史结果查询；
15. 结果对比；
16. 用户可理解的综合报告；
17. 研究用途与非投资建议声明。

真实可靠回测不可用时必须展示结构化不可用原因。可以使用显式 TEST/DEMO 场景展示页面
结构，但不得改用不合格数据计算，也不得把 Mock 收益显示为真实历史收益。

### 5.3 禁止范围

F2A 禁止：

- 新增 BaoStock、AKShare、Tencent、Sina、Eastmoney 或 CNINFO 调用；
- 接入其他新 Provider；
- 写入 V13 事实或执行正常业务库 V13；
- 创建 Day 002 或运行正式 Shadow；
- 开启 scheduler；
- 宣称准确率、推荐有效、策略有效或盈利；
- 自动交易。

## 6. 产品形态验收门

正式状态只允许：

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
```

当前为：

```text
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
```

PASS 必须同时满足：

1. 用户能够看到真实产品工作台；
2. 候选池和单股分析流程完整；
3. 六个智能体与总控角色清楚；
4. evidence、lineage、reasonCode 和数据资格可见；
5. 真实研究数据、不可用数据和 TEST/DEMO 严格区分；
6. 用户明确认可产品总体形态值得继续；
7. 没有把页面完成度冒充准确率或盈利证据。

PASS 只表示用户认可产品形态和日常使用流程，不表示免费 Provider 通过、系统准确、策略
有效、Shadow 完成、iFinD 可启动或可以交易。

F2A 与该门禁不计入 `FREE_PROVIDER_VALIDATION_GATE`，也不形成 F3 效果样本。

## 7. F1 与 F2B

F1 不因 F0.5 或 F2A 获批而自动开始。BaoStock 至少仍需明确：

1. 个人研究调用边界；
2. 本地持久化边界；
3. 历史回放和回测边界；
4. 内部 Agent 使用边界；
5. 字段和单位数据字典；
6. 稳定来源身份；
7. 独立 factor，或正式缩小 QFQ 能力的决定。

完整 V13/QFQ 还需要 `DAILY_EXACT`、交易所级日历身份、公司行动稳定身份和单 Provider
lineage。条件未满足前，BaoStock 继续为 `PENDING_WRITTEN_PERMISSION`，不得提升为：

```text
FREE_PROVIDER_F1_CANDIDATE
APPROVED_ADAPTER
PROVIDER_PIT_VERIFIED
FORMAL
```

F2B 只有在 F1 完成验收、至少一套免费 Provider 路线拥有明确用途边界，且
`SYSTEM_KNOWLEDGE_PIT` 能够合法前向积累后才能开始。F2B 才允许以 Provider-backed
事实驱动合格回测、六智能体和后续 F3。

F2A 完成不等于 F2B 或原完整 F2 完成。

## 8. 新候选来源边界

可以继续审计免费或低成本来源，但只能作为用户另行授权的独立阶段。新候选必须优先解决：

1. 独立调整因子；
2. `DAILY_EXACT`；
3. 同源 raw/factor/calendar/action；
4. 本地持久化；
5. 回测；
6. Agent 使用；
7. revision 和发布时间；
8. 调用稳定性。

不得为继续开发而无边界搜索免费接口。在 F2A 产品形态尚未得到用户认可前，不投入大量
时间寻找更多 Provider。

## 9. F3 保持原门禁

F3 仍必须依赖：

1. F1 合格免费 Provider 接入；
2. F2B 真实产品闭环；
3. 合法 `SYSTEM_KNOWLEDGE_PIT`；
4. `FREE_VALIDATION_METRICS_V1` 冻结；
5. 不少于 20 个有效观察日；
6. 不少于 200 个 Shadow item。

F2A 不能用于正式 F3 准确率或效果评估。Day 001 的 1 个观察日和 3 个 item 不因本阶段
变化。

## 10. 当前正式状态

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

同时：

- iFinD 真实调用数：`0`；
- 正常业务库 V13：未执行；
- F2A：独立任务分支技术实现已完成，待实际 Git 提交验收，尚未合入或进行用户视觉验收；
- F1/F2B/F3：未开始；
- Day 002：未创建；
- scheduler：关闭；
- 3A-R3B-1：未开始；
- 3B：未开始。

## 11. 下一阶段入口

F0.5 实际 Git 提交已经通过 ChatGPT 验收并由用户批准合入。用户已按本门禁另行授权：

```text
3A-R3B-F2A：免费研究预览产品
```

F2A 当前已在独立任务分支完成技术实现并等待实际 Git 提交验收；这不改变 F0.5
“不得自动开始后续阶段”的治理规则，也不授权 F1、F2B 或 F3。

## 12. 验证与完成边界

本阶段只执行 Markdown 相对链接、表格列数、UTF-8、结尾换行、尾随空白、
`git diff --check`、变更范围和 `.ai/` 未暂存检查。

本阶段没有：

- 修改生产代码、测试、配置、迁移或 `PROGRESS_LOG.md`；
- 调用任何免费 Provider 或 iFinD；
- 启动 Java、Python、Vue 或 Shadow；
- 访问或写数据库；
- 执行正常业务库 V13；
- 创建 Day 002；
- 开启 scheduler；
- 在 F0.5 任务自身中开始 F1、F2A、F2B、F3、3A-R3B-1 或 3B；
- merge 当前任务分支。
