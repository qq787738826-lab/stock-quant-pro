# 3A-R3B-0 Provider 中立 PIT 市场事实 V2 离线闭环阶段记录

## 状态

- 冻结基线：`23baf11ed3a236800b5f3feba8681d261a71d9f9`
- 任务分支：`codex/1.4.0-stage-3ar3b0-provider-neutral-pit-offline-v2`
- 状态：**第二次实际 Git 复验 findings 的增量修复和 Codex 本地验证完成，待 ChatGPT 基于新的实际 Git 提交复验，尚未 merge。**
- iFinD 真实调用数：`0`
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`
- Day 002 未创建，scheduler 仍关闭，3B 未开始。

## 审计映射

| 仓库事实 | 实施结果 |
| --- | --- |
| V6/V7 已有 dataset、source namespace、ingestion、assurance 和日历基础 | 复用 lineage 与资格结构，不建立同义摄取体系 |
| V9 保存当前 QFQ 观察 | 保持 V9 不变；V2 不把它转换或回填成 raw/factor |
| V10 已证明 append-only、A→B→A、不可变和并发模式 | V13 复用该模式建立四类市场事实链 |
| 旧 Java/Python Bar 桥不携带已验证 revision | 新 Provider 公共 DTO 独立分层，不提升 AKShare/Tencent 资格 |
| 2F/2I 按精确 ruleVersion/profile 分派 | 新 V2 版本独立接入，旧 contextHash/cache/result 保持兼容 |
| Shadow 默认固定 2I | 默认不变；V2 仅允许显式 TEST/DEMO EXPLICIT 测试 |
| PostgreSQL 已有随机 Schema 安全门 | V13、双血统和闭环全部使用随机 Schema，public 只读 |

## 实现摘要

### V13

新增：

- `pit_market_fact_batches`
- `pit_market_fact_observations`
- `raw_daily_bar_facts_v2`
- `adjustment_factor_facts_v1`
- `trading_calendar_facts_v1`
- `corporate_action_facts_v1`

迁移包含 9 个索引、5 个跨表校验触发器、12 个不可变/禁止 truncate 触发器，以及
V2 TEST/DEMO Shadow ruleVersion 的约束扩展；Flyway checksum 为 `-408572418`。V1 至
V12 未修改，迁移未回填任何业务数据。

batch 级数据库门禁规定：只有 `PROVIDER_VERIFIED` 可以携带
`providerDatasetVersion`；`SYSTEM_KNOWLEDGE_ONLY`、`PROVIDER_UNVERIFIED` 和
`PROVIDER_UNAVAILABLE` 必须为 null，且 Java DTO 门禁保持一致。

### Provider 与资格

`MARKET_FACT_PROVIDER_CONTRACT_V1` 类型化覆盖四类事实、capability、各事实独立
source identity、revision/snapshot/time、许可、单位、字段资格、完整性、错误和限流。
raw、factor、calendar、corporate action 分别使用证券、证券/因子、交易所日历和
证券/事件来源身份；同 Provider 内可复用 SZSE 日历，但禁止交易所、证券或 Provider
身份错配。Provider 不生成本地时间、datasetVersion、observationVersion 或 Hash。

Mock Provider 仅使用合成 fixture，固定 TEST/DEMO、`formalEligible=false`、无网络。
iFinD Adapter 只保留默认禁用骨架，误开启也在网络前以
`IFIND_TRIAL_GATE_NOT_PASSED` 失败；没有 SDK、客户端或凭据。

### Canonical、as-of 与 QFQ

Java 以 `PIT_MARKET_FACTS_CANONICAL_V2` 生成生产 Hash，Python 只交叉验证固定黄金
向量。semantic content hash 覆盖业务值、字段资格、revision/assurance/usage、
许可标志及全部合格 Provider dataset/revision/snapshot/publish/update 元数据；本地
时间和随机身份不进入该 Hash。完全相同语义幂等，资格、许可或 Provider metadata 变化
追加，资格 A→B→A 保留三版。Repository 只选择同 source/事实身份且
`knownAt<=knowledgeCutoff` 的资格优先、时间稳定版本。

第二次复验修复把四类 as-of 路径统一为“先选语义版本、后验许可”：calendar、raw、
factor、corporate action 均先按 revision qualification、knownAt、chainSequence、id
选出唯一版本，再校验 usage qualification 与 local persistence/historical replay/
backtest/Agent use。选中版本的任一必要许可为 false 时固定返回
`PIT_USAGE_NOT_ALLOWED`，禁止回退旧允许版本。真实 PostgreSQL 已覆盖 raw 的
backtest、Agent use、historical replay 三类撤销和 allow→deny→allow，并以相同路径证明
calendar、factor、corporate action 均不回退。

knowledge-time 按资格分流：SYSTEM_KNOWLEDGE 必须
`knownAt=firstObservedAt<=recordedAt`；PROVIDER_PIT_VERIFIED 必须具备 revision 和
providerPublishedAt，且 `knownAt=providerPublishedAt<=firstObservedAt<=recordedAt`，
providerUpdatedAt 如存在必须位于 published 与首次接收之间。因此合格 Provider 版本
可在 providerPublishedAt 之后、首次本地接收之前的历史 cutoff 被选择，系统首次捕获
版本在同一 cutoff 不可见。

`QFQ_AS_OF_ENGINE_V1` 使用 `DAILY_EXACT`：输入显式冻结 raw、factor、calendar 和
corporate-action 四类来源身份；每根 raw bar 和锚点必须取得同日、同 Provider、
正确事实身份/factorType、cutoff 前可见的精确 factor。禁止 forward-fill、最近 factor
替代、当前 factor 补历史、跨 Provider 拼接及缺失日期后重新归一化。非 Provider
verified 的 factor 修订仅能由同 symbol/source/identity、相同 effectiveTradeDate 且在
当前 factor 可见前已可见的 action 解释。缺任一精确 factor 返回
`PIT_FACTOR_UNAVAILABLE`，不产生部分窗口。

共享夹具的 18 个场景均包含固定事实输入、时间、资格、预期输出、lineage 和 Hash；
Java 参数化测试实际逐一运行引擎 18/18，Python 只验证固定结果向量和 Hash。

### 2F V2、六智能体与 Shadow

新 ruleVersion `1.4.0-stage-3ar3b0-agent-team-pit-v2` 使用
`AGENT_CONTEXT_3AR3B0_V2/BACKTEST_CONTEXT_V2/BACKTEST_CANONICAL_V2`。2F V2
沿用既有回测引擎、策略和七项参数；Java 运行 QFQ/回测并生成 Hash，Python 只解释
冻结结果。

raw OHLC 必填；volume、amount、turnoverRate 分别携带
`PRESENT_VERIFIED/PRESENT_UNVERIFIED/MISSING`、冻结单位和语义代码，明确 0 与缺失
不等价。QFQ 可保留缺失非价格字段；2F V2 要求 volume 为 PRESENT_VERIFIED，否则返回
`PIT_REQUIRED_MARKET_FIELD_UNAVAILABLE`，amount/turnoverRate 的缺失按资格保留。

六 run 顺序、POSITION_RISK 唯一正式 veto、2I 总控优先级和缓存终态均不变。真实
PostgreSQL/Python EXPLICIT Mock Shadow 通过既有 Java 任务系统形成确定结果、缓存
复用和非法响应原子回滚。

### 夹具安全

Java/Python 离线工具支持递归脱敏、字段白名单、canonical Hash 和 fixture 版本，并
拒绝残留凭据字段、URL userinfo、个人路径或机器路径。仓库只提交合成 fixture。

## 本地验证

以下为 Codex 本地执行证据，不是 GitHub Actions：

| 测试组 | 结果 |
| --- | --- |
| Java Provider/QFQ 黄金向量/persistence/Shadow 定向 | 34/0/0/0，其中 QFQ 可执行黄金向量 18/18 |
| Python compileall / 完整 unittest | PASS；130/0/0/0 |
| quant-core | 4/0/0/0 |
| quant-server 安全全量 | 428/0/0/89；真实环境组单独 Skipped=0 |
| V1→V13 随机 Schema PostgreSQL | 16/0/0/0，Skipped=0 |
| V6 旧血统→V13 与 fresh V1→V13 收敛 | 1/0/0/0，Skipped=0 |
| 真实 Java/Python/PostgreSQL Mock Shadow | 1/0/0/0，Skipped=0 |
| 2D/2E/2F/2G/2H/2I 真实兼容矩阵 | 72/0/0/0，Skipped=0 |
| 2G AKShare Live Gate 回归 | 1/0/0/0，Skipped=0 |

随机 Schema 残留为 0；public 保持 V12，结构和数据指纹不变；未迁移正常业务库。
Vue 未修改。

## 当前边界

- 该实现仅在任务分支可用，尚未通过 ChatGPT 实际提交验收，尚未 merge；
- Mock/TEST/DEMO 不授予真实 Provider、FORMAL 或 PROVIDER_PIT_VERIFIED 资格；
- iFinD 函数、字段、权限、额度、许可和 revision 仍为 `UNVERIFIED`；
- 3A-R3B-1 尚未执行，因此启动门必须保持 BLOCKED；
- 未创建 Day 002，未开启 scheduler，未开始 3B。

完整任务与证据见
[3A-R3B-0 任务书](tasks/3ar3b0-provider-neutral-pit-offline-v2.md)，试用前最小调用预算见
[iFinD 试用调用矩阵](ifind-trial-call-matrix.md)。
