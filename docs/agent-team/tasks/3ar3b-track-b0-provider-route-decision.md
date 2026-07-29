# 3A-R3B-TRACK-B0：真实数据 Provider 路线决策与 F1 准入合同任务书

## 1. 任务身份

- 性质：Track B 唯一入口的治理与官方资料研究。
- 冻结集成基线：`8b6a6bf39a40e44062a3f7aeb315e17e9b62e199`
- 任务分支：`codex/1.4.0-stage-3ar3b-track-b0-provider-route-decision`
- 目标提交：`docs(agent): select track b provider route`
- 调查日期：`2026-07-29`
- 候选数量：精确为 3。
- Provider/iFinD 真实调用数：0。

## 2. 前置事实

1. Track A 已完成，`FREE_PRODUCT_PREVIEW_GATE=PASS`。
2. PASS 只证明产品形态和日常只读研究流程，不证明数据资格、PIT、QFQ、策略、Shadow、iFinD 或交易。
3. F0 结论为 `F0_AUDIT_RESULT=PARTIAL`；BaoStock 仍是 `PENDING_WRITTEN_PERMISSION`，`DAILY_EXACT=UNVERIFIED`。
4. V13 Provider 中立实现已进入代码库，但正常业务库没有执行 V13，真实 Provider 未接入。
5. Track B 的 F1、F2B、F3 均未开始。

## 3. 目标

本阶段必须：

1. 只用官方资料和已验收的直接 Provider 探针事实，比较 BaoStock、Tushare Pro、iFinD；
2. 逐项冻结法律/用途、25 项技术能力、V13/QFQ、PIT、成本和个人开发可行性；
3. 选择且只选择一条主要路线和一条备用路线；
4. 给出唯一 F1 准入状态；
5. 形成可发送的书面许可模板；
6. 为未来低成本 Provider 或 iFinD 冻结 10 次以内最小探针合同；
7. 明确用户下一外部动作及获得答复后 Codex 可以做什么。

不得用“各有优缺点，后续再看”代替决定。

## 4. 调查边界

### 4.1 精确三候选

- 路线 A：BaoStock，当前免费路线；
- 路线 B：Tushare Pro，唯一低成本正式 API 候选；
- 路线 C：同花顺 iFinD，专业付费备用路线。

Wind 不进入主矩阵；没有用非官方报价扩展候选。

### 4.2 官方资料

只允许 Provider 官方网站、API 文档、服务协议、许可/权限、价格、试用与联系方式。搜索摘要只用于定位官方页面，不成为证据。证据见 [官方证据登记册](../track-b-provider-evidence-register.md)。

### 4.3 禁止

- 不注册、登录、购买、申请试用或调用 Provider；
- 不启动 Java/Python/Vue；
- 不访问数据库或执行 V13；
- 不开发 Adapter；
- 不修改研究预览、Agent、Shadow、交易或生产代码；
- 不启动 F1/F2B/F3、Day 002、scheduler、3A-R3B-1 或 3B；
- 不读取 `.ai/`、`.env` 或凭据。

## 5. 资格与评分

统一状态、逐项矩阵、V13/PIT 分类和评分见 [候选矩阵](../track-b-provider-candidate-matrix.md)。

评分权重：

| 维度 | 权重 |
|---|---:|
| 法律与用途明确性 | 30% |
| V13/QFQ 闭环 | 25% |
| PIT 与版本能力 | 15% |
| 数据覆盖与稳定性 | 15% |
| 个人开发成本 | 10% |
| 接入复杂度 | 5% |

评分不能覆盖硬门禁。以下任一项缺失均不能进入 F1：

- 本地持久化；
- 历史回放/回测；
- 内部 Agent；
- 单 Provider raw/factor/calendar/action；
- 稳定证券身份；
- 合法前向 PIT；
- 明确且由用户批准的成本；
- 个人身份可购买/使用。

状态一致性验收固定为：

1. 同一候选的当前 PIT 状态不得在 Track B0 文档间矛盾；
2. `PIT_PARTIAL` 不得描述为当前已可合法落库；
3. 稳定证券 ID 的矩阵状态必须与 F1 合同一致；
4. `FORWARD_PIT_BUILDABLE` 只有在书面许可通过并完成最小样例复核后，才可由独立治理阶段讨论；
5. `F1_ENTRY_READINESS=BLOCKED_MULTIPLE`；
6. 四项正式门禁保持不变。

## 6. 冻结决定

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

### 6.1 主要路线

`LOW_COST_PROVIDER_FIRST` 精确指 Tushare Pro。

Tushare Pro 当前 PIT 状态固定为 `PIT_PARTIAL`，稳定证券 ID 状态固定为 `PARTIAL`。这两项当前状态均不得被主要路线选择覆盖。

选择原因：

1. `daily`、逐交易日 `adj_factor`、SSE/SZSE `trade_cal`、`dividend`、`stock_basic` 同属一个官方 API 平台；
2. 核心接口公开的最低技术门槛是 2000 积分，个人价格 200 元/年；
3. Python SDK 和 HTTP 能复用既有 Java Provider 中立桥接；
4. 在书面许可通过后，最适合个人开发者以较小成本建立首次捕获后的 `SYSTEM_KNOWLEDGE_PIT`。

它没有获批进入 F1，因为本地保存、回测、Agent 和留存的合同边界仍需书面确认，公司行动闭环及实际 `DAILY_EXACT` 仍需最小样例。

### 6.2 备用路线

`IFIND` 是唯一备用。

选择原因：

1. 官方资料证明多语言 SDK/HTTP、历史行情、基础数据、交易日、公告和较高额度；
2. 专业能力上限能覆盖 Tushare 无法证明的字段或版本需求；
3. 但价格、个人资格、专项合同、四类字段和 revision 只能通过报价/试用核验，因此不能作为当前主路线。

BaoStock 保留研究辅助地位，不作为正式备用：它的免费优势不足以覆盖许可、因子、交易所日历和公司行动版本四个核心阻断。

### 6.3 F1 判定

`BLOCKED_MULTIPLE` 同时包含：

- `BLOCKED_WRITTEN_PERMISSION`；
- `BLOCKED_TECHNICAL_EVIDENCE`；
- `BLOCKED_COST_APPROVAL`。

完整合同见 [F1 准入合同](../track-b-f1-entry-contract.md)。

## 7. 用户下一外部动作

唯一优先动作：

> 用户使用 [Tushare Pro 书面许可模板](../track-b-permission-request-pack.md#3-tushare-pro-模板主要路线)，向官方渠道取得可归档的逐项书面答复，并请求购买前两个证券、两个交易日的脱敏字段样例；在答复通过前不注册、不购买、不提供 Token。

可选备用准备：

> 可以向 iFinD 官方热线/销售索取书面报价、专项 API 合同和脱敏样例，但不得申请或激活 15 天试用。

获得 Tushare 书面答复后，Codex 可在独立治理阶段：

1. 对答复做许可与字段准入复核；
2. 将可执行限制映射到 V13 qualification；
3. 判定 F1 是否从 `BLOCKED_MULTIPLE` 变为其他阻断或 `READY`；
4. 只有用户再批准成本和 F1 后，才规划真实 Adapter 实施。

## 8. 工作量与个人开发现实

- 免费 BaoStock 路线继续等待会明显拖慢完整 V13/QFQ，因为缺口不是普通编码问题。
- Tushare 能以当前公开最低 200 元/年的技术档缩短闭环，但书面许可优先于购买。
- iFinD 试用尚不到启动时点：Track A 只证明产品形态，F2B/F3 尚未证明数据是主要效果瓶颈。
- Tushare 答复和样例通过后，F1 技术接入预计 6—10 个专注开发日；不包含等待 Provider、用户验收或后续 F2B/F3 观察时间。

## 9. 交付物

- [阶段记录](../stage-3ar3b-track-b0-provider-route-decision.md)
- [候选统一资格矩阵](../track-b-provider-candidate-matrix.md)
- [官方证据登记册](../track-b-provider-evidence-register.md)
- [成本模型](../track-b-provider-cost-model.md)
- [书面许可请求包](../track-b-permission-request-pack.md)
- [F1 准入合同](../track-b-f1-entry-contract.md)
- [最小试用探针合同](../track-b-trial-probe-contract.md)

## 10. 任务分支状态

- Track A 正式完成；
- Track B0 调查、主要/备用路线和 F1 判定已完成；
- 待 ChatGPT 基于实际 Git 提交验收；
- 尚未合入；
- F1 尚未获得实施授权；
- Provider/iFinD 真实调用数为 0；
- 正常业务库 V13 未执行；
- F2B/F3、Day 002、3A-R3B-1、3B 未开始；
- scheduler 关闭。

保持：

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```
