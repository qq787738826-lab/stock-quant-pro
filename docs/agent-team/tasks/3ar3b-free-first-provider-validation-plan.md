# 3A-R3B Free-First Validation Plan 任务书

## 1. 文档状态与范围

状态：**免费数据验证优先、付费 Provider 后置升级的治理规划已在任务分支完成，
待 ChatGPT 基于实际 Git 提交验收，尚未合入；3A-R3B-F0 尚未获得业务实施授权。**

- 冻结集成基线：`f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-free-first-validation-plan`
- 目标提交：`docs(agent): adopt free-first provider validation strategy`
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

- 当前集成 HEAD 为 `f0b87e1ecf51d2e94d5eff43d18f5fc3b6abe819`；
- 3A-R3B-0 已通过 ChatGPT 对实际 Git 提交的最终复验，经用户批准并纯
  fast-forward 合入，远程集成分支与最终提交一致，ahead/behind 为 `0/0`；
- V13 代码已进入集成分支，但正常业务库尚未执行 V13；
- 真实 Provider 尚未接入；
- iFinD 真实调用数为 `0`；
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`；
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

F0 只有在实际证据、许可边界、单位语义、来源身份和最小探针结果形成独立提交并通过
验收后才完成。F1 至少还要求其中一条免费路线获得明确的研究用途与本地保存边界。

## 5. 3A-R3B-F1：免费 Provider Adapter 与 V13 接入

### 5.1 启动条件

F0 已形成实际证据并通过单独验收，且至少一条免费路线的研究用途、本地持久化及相关
使用边界明确。F1 必须复用 `MARKET_FACT_PROVIDER_CONTRACT_V1` 和 V13，不降低既有资格
门禁。

### 5.2 两类用途

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

### 5.3 禁止范围

F1 不得把免费 Provider 升级为 `PROVIDER_PIT_VERIFIED`，不得绕过 V13 用途许可，不得
跨 Provider 拼接 QFQ，不得自动全市场抓取、开启 scheduler、创建 Day 002 或启动自动交易。

## 6. 3A-R3B-F2：免费版真实产品闭环

F2 的目标是在不购买专业数据的前提下，让用户看到并使用真实成品形态。至少覆盖：

- 股票候选池；
- 单股票完整分析；
- 六个固定智能体；
- 总控综合结论；
- DATA_QUALITY 阻断；
- 技术分析和市场环境；
- 策略回测；
- 公告风险和持仓风险；
- evidence、lineage 和 reasonCode；
- 历史查询与结果对比；
- 用户能够理解的报告；
- 对研究数据、`SYSTEM_KNOWLEDGE_PIT` 和不可用数据的清楚区分。

F2 主要回答：

1. 最终产品大概是什么样；
2. 用户是否愿意每天使用；
3. 分析过程是否真正有信息价值；
4. 输出是否可理解和可追溯；
5. 数据缺失是否严重影响使用；
6. 哪些智能体真正提供增量价值。

页面完成度、交互顺畅或报告美观均不得被写成选股有效、策略有效或盈利证据。

## 7. 3A-R3B-F3：免费 Shadow 与效果评估

### 7.1 长期观察门槛

F3 使用免费 Provider 与 `SYSTEM_KNOWLEDGE_PIT` 做前向验证。最低门槛继续沿用完整 3A：

- 不少于 20 个有效观察日；
- 不少于 200 个 Shadow item；
- 主要 reasonCode 完成正式人工复核；
- 持续证明业务表只读；
- 形成正式观察报告。

观察周期由实际开发完成度与市场日历决定，不绑定固定完成日期。

### 7.2 FREE_VALIDATION_METRICS_V1

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

## 8. 免费 Provider 验证门

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
系统已证明盈利、iFinD 可以开启或 Day 002 自动批准。

## 9. 付费 Provider 升级决策

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

### 9.1 PROCEED 条件

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

### 9.2 DEFER 条件

出现以下任一情况可以选择 `DEFER`：

- 产品形态仍不满意；
- 免费 Shadow 未显示初步价值；
- 系统自身规则或产品问题大于数据问题；
- 免费数据已经足够当前用途；
- 付费数据预期提升无法覆盖成本；
- 用户暂不准备投入。

`DEFER` 不表示项目失败，可以继续迭代和观察免费版本。

## 10. 原 iFinD 阶段的新依赖

原 3A-R3B-1、R3B-2、R3B-3 的编号和目标保留，但 R3B-1 不再是 R3B-0 的直接下一阶段。
进入 R3B-1 必须同时满足：

1. F0 至 F3 已完成相应验收；
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

## 11. 免费与付费 Provider 的角色

免费 Provider 用于产品验证、研究级历史回测、`SYSTEM_KNOWLEDGE_PIT` 前向 Shadow、
流程/规则/UI/智能体价值验证以及初步效果评估。

付费 Provider 用于补足免费数据缺失，验证 revision/snapshot/published time，提高字段
完整性和稳定性，减少数据质量阻断，对照回测和 Shadow 差异，并量化专业数据的真实增量。

付费数据不能修复没有价值的策略、代替产品设计、掩盖智能体规则缺陷、自动证明系统有效，
也不能自动批准生产、商业化或交易。

## 12. 完成边界

本治理任务完成只表示免费优先路线、阶段依赖和门禁状态已冻结。当前唯一规划中的下一阶段
是 3A-R3B-F0，但 F0 尚未获得业务实施授权。

本任务未调用任何免费 Provider 或 iFinD，未修改生产代码，未写数据库，未执行正常业务库
V13 迁移，未创建 Day 002，未开启 scheduler，未开始 R3B-1 或 3B。
