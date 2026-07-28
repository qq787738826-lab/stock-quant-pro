# iFinD 15 天试用调用矩阵

## 1. 状态与用途

- 当前状态：**试用前离线计划，不构成调用授权。**
- iFinD 真实调用数：`0`
- `IFIND_TRIAL_ACTIVATION_GATE=BLOCKED`
- 真实函数名、指标名、字段名、权限、单位、额度和许可：`UNVERIFIED`

只有 3A-R3B-1 形成正式 PASS，且用户亲自开通试用后，3A-R3B-2 才能按本矩阵执行。
Codex 不得申请、激活或自动调用 iFinD。

## 2. 调用原则

- 只使用用户授权的隔离试用环境和最小证券/日期范围；
- 默认不重试；超时、权限拒绝、结构变化、限流、验证码或许可不明立即停止；
- 不做 scheduler、后台轮询、全市场遍历或额度消耗型自动重试；
- 每个 stableCallId 的真实函数、字段和额度必须先以官方文档或试用控制台验证；
- 原始响应保存在仓库外，提交前仅允许经过白名单和脱敏工具生成的固定 fixture；
- 不记录账号、密码、token、Cookie、session、完整认证 URL 或个人路径；
- 一项真实调用不得同时被解释成多个未经证明的 Provider 能力。

## 3. 最小调用矩阵

| stableCallId | 数据类别 | 目标能力 | 最小证券集合 | 最小日期范围 | 所需字段 | 预期类型与单位 | 最大调用数 | 重试 | 停止条件 | 证据 | 许可待确认 | revision/snapshot/time 待确认 |
| --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- | --- |
| `IFIND-CAP-001` | capability | 账号可用函数、权限和额度清单 | 无证券或最小合法样例 | 单日 | 函数、字段、频率、额度 | `UNVERIFIED` | 1 | 否 | 需真实数据扫描、返回敏感信息或权限不明 | 官方页面/脱敏响应结构/错误码 | 本地持久化、回测、派生、内部 Agent | 是否提供正式 capability/version |
| `IFIND-RAW-001` | raw daily | 未复权 OHLCV、amount、turnover | 1 只沪深主板证券 | 连续 3 个有效交易日 | 函数和字段均 `UNVERIFIED` | 日期、Decimal、股/金额/比率单位 `UNVERIFIED` | 2 | 否 | 返回复权价、单位不明、时间边界不明 | 两次响应结构、值、头/元数据、Hash | 历史落库、回测、fixture | revisionId、snapshotId、publish/update time |
| `IFIND-FACTOR-001` | adjustment factor | DAILY_EXACT 因子及 factorType | 与 RAW 相同 | 同 3 日加一个公司行动窗口 | 函数和字段均 `UNVERIFIED` | 正 Decimal；口径/锚点 `UNVERIFIED` | 2 | 否 | 仅提供当前 QFQ、缺日期覆盖或因子口径不明 | 同日覆盖、重复响应、公司行动前后差异 | 因子历史版本落库与派生 | revision、旧版本回查、published/update time |
| `IFIND-CAL-001` | trading calendar | 开闭市、session、临时休市 | SSE、SZSE | 含周末的 10 个自然日 | 函数和字段均 `UNVERIFIED` | 日期/布尔/session `UNVERIFIED` | 2 | 否 | 交易所身份或临时修订语义不明 | 两交易所样例、修订/错误结构 | 日历持久化与回测 | calendar revision、发布时间、旧版本 |
| `IFIND-ACTION-001` | corporate action | 分红、送转、配股、拆并股及生效关系 | 1 只存在公开公司行动的样例证券 | 最小事件窗口 | 函数和字段均 `UNVERIFIED` | 日期、Decimal、actionType `UNVERIFIED` | 2 | 否 | 无稳定 action identity、公告/生效时间不明 | 响应结构、action identity、factor 对应关系 | 公司行动落库、派生与 fixture | revision、撤回/更正、publish/update time |
| `IFIND-ASOF-001` | historical version | 同一自然键旧版本回查和 cutoff 复现 | 与前述证券相同 | 一个可验证修订窗口 | revision/snapshot/time 字段 `UNVERIFIED` | `UNVERIFIED` | 2 | 否 | 只能返回当前态或需扩大调用范围 | 旧/新版本对照、官方语义、稳定标识 | 历史版本保存和重放 | revision 关系、snapshot、可查询保留期 |
| `IFIND-ERR-001` | error/quota | 权限、空数据、限流、超时和结构错误 | 1 只合法证券；一个受控空范围 | 最小范围 | 错误码、requestId、quota 字段 `UNVERIFIED` | `UNVERIFIED` | 2 | 否 | 任何会消耗异常额度或触发访问控制的动作 | 脱敏错误结构、是否计费、停止语义 | 错误响应能否留存 | requestId 是否稳定版本标识：默认否 |

矩阵上限是 13 次有目的的最小调用，不是预授权额度；3A-R3B-1 可在证据不足时继续
BLOCKED，用户也可进一步降低预算。任何新增 stableCallId 都必须先更新任务书并由用户
确认，不得临时扩张。

## 4. 证据采集包

每个实际 stableCallId 在未来 3A-R3B-2 至少记录：

1. 官方函数/字段文档引用和访问日期；
2. 账号权限与许可说明的脱敏证据；
3. 请求的 stableCallId、证券、日期范围和调用序号；
4. 响应字段、类型、单位、null、错误码和完整性；
5. Provider revision/snapshot/publish/update 字段的原始语义证据；
6. 相同请求的稳定性，以及内容变化时版本标识是否变化；
7. 可否查询旧版本、修订关系和保留期限；
8. 原始文件的仓库外 Hash 与脱敏 fixture Hash；
9. 离线 fixture schemaVersion 和 providerContractVersion；
10. 停止条件是否触发及剩余额度。

## 5. 资格边界

- `inputDataHash`、contentHash、本地 UUID、batchVersion、datasetVersion、
  observationVersion、抓取时间、HTTP Date 和 requestId 均不能自行成为
  providerRevision；
- 没有正式 revision、发布时间和修订关系证据时，不得授予
  `PROVIDER_PIT_VERIFIED`；
- 若 Provider 无 revision，但真实首次捕获和 append-only 回放成立，只能评估
  `SYSTEM_KNOWLEDGE_PIT`，且不能支持首次捕获前的历史决策；
- 试用结束不影响 Mock、合成 fixture 和离线回归；
- 调用矩阵完成仍不等于 `IFIND_TRIAL_ACTIVATION_GATE=PASS`。
