# 3A-R3B Free-First Validation Plan 任务书

## 1. 文档状态与范围

状态：**免费数据验证优先、付费 Provider 后置升级的治理规划已通过 ChatGPT 对实际
Git 提交的验收，经用户批准并纯 fast-forward 合入；后续 F0 审计也已通过最终复验并
纯 fast-forward 合入。F0.5 双轨治理也已验收合入；F2A 当前在独立任务分支完成技术
实现，等待实际 Git 提交验收和用户后续视觉验收。**

- 冻结集成基线：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-free-first-validation-plan`
- 目标提交：`docs(agent): adopt free-first provider validation strategy`
- 最终提交：`c47b88e586f6751563fe210f40137a3b7ce5e576`
- 阶段记录：
  [stage-3ar3b-free-first-provider-validation-plan.md](../stage-3ar3b-free-first-provider-validation-plan.md)
- 当前事实唯一权威：[CURRENT_STATE.md](../CURRENT_STATE.md)
- 路线方向：[ROADMAP.md](../ROADMAP.md)
- 跨阶段决定：[DECISIONS.md](../DECISIONS.md)

本任务只冻结治理路线，不开发 Provider Adapter，不调用免费或付费数据源，不启动服务，
不写数据库，不执行正常业务库 V13 迁移，不创建 Day 002，也不开始 3B。

## 2. 已批准战略

系统目前尚未通过真实产品形态与效果验证，不提前把高额数据费用作为项目开发前置条件。
后续顺序固定为：

```text
免费数据完成产品与效果验证
→ 系统证明具有使用价值
→ 判断数据是否已经成为主要瓶颈
→ 再决定是否开启 iFinD 试用或接入其他付费 Provider
→ 对同范围免费/付费数据做 A/B 对照
→ 只有可量化提升足以覆盖成本时才考虑长期购买
```

iFinD 从项目开发前置依赖调整为“系统证明有效后的专业化升级候选”。免费方案不得冒充
专业 Provider 资格，也不得降低既有 PIT、许可、lineage、QFQ、用途和知识时间门禁。

## 3. 当前仓库事实

- 当前集成 HEAD 为 `08943b4f6af03c75aa4df2a4ecf2494bede4e57b`；
- 3A-R3B-0 已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准并纯
  fast-forward 合入，远程集成分支与最终提交一致，ahead/behind 为 `0/0`；
- 本免费优先规划已通过验收并纯 fast-forward 合入，远程集成分支与最终提交一致；
- 3A-R3B-F0 最终提交 `059eacffaf7e4a9f383be205d453c5168279932a`
  已通过 ChatGPT 实际 Git 最终复验，经用户批准并纯 fast-forward 合入，本地与远程集成
  分支一致，ahead/behind 为 `0/0`；
- 3A-R3B-F0.5 最终提交 `08943b4f6af03c75aa4df2a4ecf2494bede4e57b`
  已通过 ChatGPT 实际 Git 验收，经用户批准并纯 fast-forward 合入，本地与远程集成
  分支一致，ahead/behind 为 `0/0`；
- F2A 已由用户单独授权，技术实现当前在独立任务分支完成，待实际 Git 提交验收，尚未
  合入或进行用户视觉验收；
- `F0_AUDIT_RESULT=PARTIAL`，该结果不是失败，但不批准 F1；
- V13 代码已进入集成分支，但正常业务库尚未执行 V13；
- 真实 Provider 尚未接入；
- iFinD 真实调用数为 `0`；
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`；
- `FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST`；
- `FREE_PRODUCT_PREVIEW_GATE=BLOCKED`；
- `FREE_PROVIDER_VALIDATION_GATE=BLOCKED`；
- `PAID_PROVIDER_UPGRADE_DECISION=PENDING`；
- 3A-R3B-1 未开始；
- Day 002 未创建；
- scheduler 关闭；
- 完整 3A 未完成，3B 未开始。

## 4. 3A-R3B-F0：免费 Provider 资格审计

### 4.1 性质与目标

F0 是只读调查、最小受控探针与证据规划阶段。它只判断免费来源能否在明确许可和资格
边界内承担产品验证以及 `SYSTEM_KNOWLEDGE_PIT` 前向积累，不直接批准 Adapter。

候选角色先冻结为：

- BaoStock：免费主 Provider 技术候选；
- AKShare 及现有 Tencent 链：研究级当前投影、辅助数据和交叉校验候选；
- 巨潮资讯、上交所、深交所公开信息：公告、公司行动、交易日历和规则的官方证据候选；
- 其他免费来源：只有形成独立审计证据后才能加入候选矩阵。

这些角色全部只是待审候选，不代表 Provider 已批准、许可已确认或取得 PIT 资格。

F0 已按本规划执行并形成
[F0任务书](3ar3b-f0-free-provider-qualification-audit.md)、
[阶段记录](../stage-3ar3b-f0-free-provider-qualification-audit.md)和逐事实证据矩阵。
当前结论为 `F0_AUDIT_RESULT=PARTIAL`：BaoStock 已观察到部分结构化技术能力，但本次
Live response completeness 为 `UNVERIFIED`，底层数据许可、`DAILY_EXACT`、交易所级
日历身份和版本语义仍未确认；AKShare 各上游只作研究辅助，
CNINFO/SSE/SZSE/SZSI 只作官方证据。F0 已通过最终复验并合入；F1 仍因书面许可和核心
技术证据未满足而未授权。

### 4.2 必审维度

F0 必须逐来源、逐数据类别形成证据矩阵，至少覆盖：

1. 未复权 raw daily；
2. 独立复权因子；
3. `DAILY_EXACT` 是否真实成立；
4. 交易所级交易日历；
5. 公司行动；
6. 稳定 `sourceCode`；
7. 四类事实各自稳定 source identity；
8. 字段单位、精度、空值和明确 0 值语义；
9. 数据更新时间和延迟；
10. 数据变化与静默修正风险；
11. 限流、失败、超时和接口变化；
12. 本地持久化权利；
13. 历史回放权利；
14. 回测权利；
15. 内部 Agent 使用权利；
16. 商业化限制；
17. Provider revision、snapshot、published/update time；
18. 旧版本查询能力；
19. 与其他来源的差异；
20. 长期维护风险。

代码库或客户端采用开源协议，不能证明底层数据允许商业使用。F0 禁止从 QFQ 价格反推
复权因子，也禁止把多个 Provider 拼成一条伪造的同源 PIT lineage。

### 4.3 退出门

F0 已以实际证据、许可边界、单位语义、来源身份和最小探针结果形成独立提交并完成验收。
F1 至少还要求其中一条免费路线获得明确的研究用途与本地保存边界，F0 `PARTIAL` 不自动
满足该条件。

## 5. 3A-R3B-F0.5：免费版实施范围与双轨路线冻结

正式实施路径冻结为：

```text
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
```

该决定允许系统在不等待完整免费或付费 Provider 的情况下，先规划使用现有合法边界内的
本地研究数据和 TEST/DEMO 能力展示产品形态；它不表示 BaoStock 获批、免费 Provider 门
通过、V13 可写、正式 Shadow 可启动、研究数据可升级为 PIT，或系统已经准确、盈利。

双轨路线为：

```text
轨道 A：F0.5 → F2A 免费研究预览产品 → 用户产品形态验收
轨道 B：F0 → 书面许可或替代 Provider 证据 → F1 → F2B → F3
```

两轨可以等待或并行推进，但资格严格隔离。F2A 的产品可见性不能被用来宣称 F1、F2B、
Provider 许可、`SYSTEM_KNOWLEDGE_PIT` 或 F3 已完成。

F0.5 本身是纯治理文档阶段，不开发页面、生产代码或 Adapter，不调用 Provider、数据库或
iFinD。该提交已通过验收、获得用户合入批准；用户随后已另行授权 F2A。F2A 的技术实现
不改变 F0.5 的来源资格或效果门禁。

## 6. 3A-R3B-F1：免费 Provider Adapter 与 V13 接入

### 6.1 启动条件

F0 已完成验收，但 F1 仍要求至少一条免费路线明确个人研究调用、本地持久化、历史回放、
回测、内部 Agent 使用、字段和单位数据字典及稳定来源身份。完整 V13/QFQ 还需要独立因子、
`DAILY_EXACT`、交易所级日历身份、公司行动稳定身份和单 Provider lineage。F1 必须复用
`MARKET_FACT_PROVIDER_CONTRACT_V1` 和 V13，不降低既有资格门禁。

BaoStock 在这些条件满足前继续是 `PENDING_WRITTEN_PERMISSION`，不得提升为
`FREE_PROVIDER_F1_CANDIDATE`、`APPROVED_ADAPTER`、`PROVIDER_PIT_VERIFIED` 或 `FORMAL`。

### 6.2 两类用途

#### A. RESEARCH_HISTORICAL_UNVERIFIED

允许用于：

- 产品页面和功能演示；
- 探索性历史回测；
- 数据覆盖研究；
- 与其他来源交叉校验。

不得声明：

- `PROVIDER_PIT_VERIFIED`；
- 历史时点绝对可得；
- 已消除前视；
- 商业正式数据资格；
- 真实历史修订版本。

#### B. SYSTEM_KNOWLEDGE_PIT

只有在系统首次真实捕获后，且满足 append-only、`firstObservedAt`、`knownAt`、cutoff 与
许可门禁时，才可证明“系统从首次捕获以后在某个决策时点实际知道什么”。它不证明
Provider 在首次捕获以前的发布时间、修订时间或历史完整性。

### 6.3 禁止范围

F1 不得把免费 Provider 升级为 `PROVIDER_PIT_VERIFIED`，不得绕过 V13 用途许可，不得
跨 Provider 拼接 QFQ，不得自动全市场抓取、开启 scheduler、创建 Day 002 或启动自动交易。

## 7. 3A-R3B-F2A：免费研究预览产品

F2A 已由用户在 F0.5 验收合入后单独授权；任务分支技术实现与 Codex 本地离线验证已完成，
待 ChatGPT 基于实际 Git 提交验收，尚未合入或进行用户视觉验收。它不要求 F1、BaoStock
书面许可或正常业务库 V13。

实现使用独立 `/research-preview` 前端入口，只读取既有扫描和 Agent GET API，严格隔离
`EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED` 与
`TEST_DEMO_EXPLICIT`；不新增 Java 接口、数据库写入或 Provider 调用。详细任务和记录见
[F2A任务书](3ar3b-f2a-research-preview-product.md)和
[F2A阶段记录](../stage-3ar3b-f2a-research-preview-product.md)。

允许输入只包括：

1. 已经存在于本地系统中的只读研究数据，必须标记
   `EXISTING_RESEARCH_SNAPSHOT/RESEARCH_HISTORICAL_UNVERIFIED`；
2. 3A-R3B-0 已验收的固定 Mock，必须标记 `TEST_DEMO_EXPLICIT`；
3. 已冻结的 Agent、总控和历史结果，只能按现有规则读取、解释和展示。

真实研究数据、不可用数据和 TEST/DEMO 结果不得混写。F2A 至少规划展示：

- 股票候选池；
- 单股票完整分析；
- 六个固定智能体；
- 总控综合结论；
- DATA_QUALITY 阻断；
- 技术分析和市场环境；
- 策略回测；
- 公告风险和持仓风险；
- evidence、lineage 和 reasonCode；
- 数据来源和资格标签；
- 历史查询与结果对比；
- 用户能够理解的报告；
- 明确的研究用途与非投资建议声明。

F2A 主要回答：

1. 最终产品大概是什么样；
2. 用户是否愿意每天使用；
3. 分析过程是否真正有信息价值；
4. 输出是否可理解和可追溯；
5. 数据缺失是否严重影响使用；
6. 哪些智能体真正提供增量价值。

真实可靠回测不可用时，必须展示结构化不可用原因。允许用显式 TEST/DEMO 展示页面结构，
但不得偷偷改用不合格数据计算，也不得把 Mock 收益显示为真实历史收益。

F2A 禁止新增 BaoStock、AKShare/Tencent/Sina/Eastmoney、CNINFO 或其他 Provider 调用；
禁止接入新 Provider、写 V13、执行正常业务库 V13、开启 scheduler、创建 Day 002、运行
正式 Shadow、宣称准确率/推荐有效/盈利或自动交易。

产品形态验收状态只允许：

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PRODUCT_PREVIEW_GATE=BLOCKED
```

当前为 `FREE_PRODUCT_PREVIEW_GATE=BLOCKED`。PASS 必须证明用户能看到候选池和单股完整
流程，理解六智能体、总控、evidence、lineage、reasonCode 与数据资格，真实研究数据、
不可用数据和 TEST/DEMO 严格区分，并明确认可产品总体形态值得继续。PASS 只表示产品形态
与日常流程获认可，不表示 Provider、PIT、策略、Shadow、iFinD、交易或盈利获批。

F2A 不计入 `FREE_PROVIDER_VALIDATION_GATE`，也不得进入 F3 正式效果样本。

## 8. 3A-R3B-F2B：免费 Provider 支持的真实产品闭环

F2B 只有在 F1 完成验收、至少一套免费 Provider 路线拥有明确用途边界，并且合法
`SYSTEM_KNOWLEDGE_PIT` 能够前向积累后才可开始。

F2B 才允许接入 Provider-backed 事实、展示真实首次捕获后的系统知识事实，以合格数据
驱动真实回测和六智能体，并为 F3 提供输入。F2A 完成不等于 F2B 或原完整 F2 完成。

## 9. 3A-R3B-F3：免费 Shadow 与效果评估

### 9.1 长期观察门槛

F3 只使用 F1/F2B 形成的免费 Provider 事实与合法 `SYSTEM_KNOWLEDGE_PIT` 做前向验证。
F2A 研究预览不得作为正式输入。最低门槛继续沿用完整 3A：

- 不少于 20 个有效观察日；
- 不少于 200 个 Shadow item；
- 主要 reasonCode 完成正式人工复核；
- 持续证明业务表只读；
- 形成正式观察报告。

观察周期由实际开发完成度与市场日历决定，不绑定固定完成日期。

### 9.2 FREE_VALIDATION_METRICS_V1

Shadow 开始前必须冻结 `FREE_VALIDATION_METRICS_V1`。冻结后不得因结果好坏临时调整阈值、
观察周期、比较基准或样本选择。指标至少包括：

1. 推荐后 5 个交易日上涨命中率；
2. 推荐后 10 个交易日上涨命中率；
3. 推荐后 20 个交易日上涨命中率；
4. 相对基准的超额收益命中率；
5. 平均和中位超额收益；
6. 最大有利变动；
7. 最大不利变动；
8. 最大回撤；
9. 盈亏比；
10. 换手率；
11. 加入交易成本后的表现；
12. 不同市场环境下表现；
13. 高、中、低置信度的实际区分度；
14. DATA_QUALITY 及其他数据原因阻断率；
15. 各智能体对最终结论的边际贡献；
16. 结果重放与 Hash 一致性；
17. 相对随机选择和固定基准策略的表现。

不得把“推荐股票上涨比例”作为唯一准确率，不得只报告盈利样本，也不得删除失败、阻断
或无信号样本。

## 10. 免费 Provider 验证门

正式状态只允许：

```text
FREE_PROVIDER_VALIDATION_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
```

当前状态：

```text
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
```

PASS 只表示至少一套免费 Provider 路线能在明确用途边界内，稳定驱动免费产品闭环和
`SYSTEM_KNOWLEDGE_PIT` 前向 Shadow。PASS 不表示商业正式许可、`PROVIDER_PIT_VERIFIED`、
系统已证明盈利、iFinD 可以开启或 Day 002 自动批准。F2A 研究预览和
`FREE_PRODUCT_PREVIEW_GATE` 不计入本门禁。

## 11. 付费 Provider 升级决策

正式状态只允许：

```text
PAID_PROVIDER_UPGRADE_DECISION=PENDING
PAID_PROVIDER_UPGRADE_DECISION=DEFER
PAID_PROVIDER_UPGRADE_DECISION=PROCEED
```

当前状态：

```text
PAID_PROVIDER_UPGRADE_DECISION=PENDING
```

### 11.1 PROCEED 条件

只有以下方向同时成立，才允许进入 `PROCEED`：

1. 免费版产品形态达到用户认可；
2. 免费数据能够稳定驱动完整闭环；
3. Shadow 显示初步、可重复的使用价值；
4. 结果不只是跟随大盘或随机波动；
5. 数据质量已成为可量化的主要瓶颈；
6. 已明确付费数据需要改善的指标；
7. 已设计同股票、同日期、同策略、同参数的免费/付费 A/B 测试；
8. 没有待解决的重大数据库、DTO、QFQ 或跨语言重构；
9. 用户愿意承担试用或采购成本。

### 11.2 DEFER 条件

出现以下任一情况可以选择 `DEFER`：

- 产品形态仍不满意；
- 免费 Shadow 未显示初步价值；
- 系统自身规则或产品问题大于数据问题；
- 免费数据已经足够当前用途；
- 付费数据预期提升无法覆盖成本；
- 用户暂不准备投入。

`DEFER` 不表示项目失败，可以继续迭代和观察免费版本。

## 12. 原 iFinD 阶段的新依赖

原 3A-R3B-1、R3B-2、R3B-3 的编号和目标保留，但 R3B-1 不再是 R3B-0 的直接下一阶段。
进入 R3B-1 必须同时满足：

1. F0、F0.5、F1、F2A、F2B 和 F3 已完成相应验收；
2. `PAID_PROVIDER_UPGRADE_DECISION=PROCEED`；
3. 既有 12 项 iFinD 准备条件全部满足；
4. 用户能够安排连续 15 天集中联调；
5. 用户亲自批准申请和激活。

只有全部满足，才允许：

```text
IFIND_TRIAL_ACTIVATION_GATE=PASS
```

当前仍为：

```text
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

不得因为 3A-R3B-0 已完成、预计日期临近或技术准备充分就自动改为 PASS。

## 13. 免费与付费 Provider 的角色

免费 Provider 用于产品验证、研究级历史回测、`SYSTEM_KNOWLEDGE_PIT` 前向 Shadow、
流程/规则/UI/智能体价值验证以及初步效果评估。

付费 Provider 用于补足免费数据缺失，验证 revision/snapshot/published time，提高字段
完整性和稳定性，减少数据质量阻断，对照回测和 Shadow 差异，并量化专业数据的真实增量。

付费数据不能修复没有价值的策略、代替产品设计、掩盖智能体规则缺陷、自动证明系统有效，
也不能自动批准生产、商业化或交易。

## 14. 完成边界

本治理任务完成只表示免费优先路线、阶段依赖和门禁状态已冻结。该规划与后续 F0 均已
验收并合入；F0 的 `PARTIAL` 不构成 F1 自动授权。F0.5 已验收合入并冻结
`RESEARCH_PREVIEW_FIRST`、双轨路线和产品预览门；F2A 技术实现只在独立任务分支完成，
仍待实际 Git 验收、merge 和用户视觉验收，`FREE_PRODUCT_PREVIEW_GATE` 保持 `BLOCKED`。

本规划与 F0.5 提交自身未调用任何免费 Provider 或 iFinD。F0 历史上仅在固定预算内执行
BaoStock 最小受控探针；F0.5 没有新增调用，也未修改生产 Adapter、写数据库、执行正常
业务库 V13、创建 Day 002、开启 scheduler 或开始 F1/F2B/F3、R3B-1、3B。F2A 本阶段
也没有新增外部调用、数据库访问或 Shadow；iFinD 真实调用数仍为 0。
