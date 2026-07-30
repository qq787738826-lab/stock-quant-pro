# Track B F1 准入合同

## 1. 当前判定

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

主要路线的具体 Provider 是 Tushare Pro。用户已开通 2000 积分且 `2026-07-30` B1 技术权限探针通过，精确执行时刻为 `PROBE_EXECUTION_TIME=UNKNOWN`；这不批准追加购买、再次调用、Adapter、V13 业务库迁移或 F1 实施。

Tushare Pro 当前资格固定为 `V13_LINEAGE_PARTIAL`、`PIT_PARTIAL`，稳定证券 ID 固定为 `PARTIAL`。B1 已把 raw、factor、SSE/SZSE calendar、普通证券身份、dividend 字段和两证券两日 `DAILY_EXACT` 最小样例验证为 `VERIFIED`，但这不表示当前可合法落库，也不确认永久 instrument identity、完整 action 或历史版本。

完整 Track B 证据探针状态仍固定为 `TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE`。执行前没有取得 Provider 对最小自动 API 探针和响应留存/删除范围的书面答复，因此 `TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET`、`WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED`、`WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED`。该状态不判定本次调用合法或违法，但继续阻断用途许可和后续扩大调用。

## 2. F1 READY 的全部条件

| 门禁 | READY 证据 | 当前状态 | 当前 finding |
|---|---|---|---|
| 个人用途 | 官方书面许可适用于个人非商业研究 | BLOCKED | 现行协议为个人非商业且仅个人查看，适用范围不足 |
| 本地持久化 | 明确允许 raw/factor/calendar/action、metadata、Hash、lineage 本地长期保存 | BLOCKED | 官方技术建议与合同语义冲突，需书面确认 |
| 历史回放与回测 | 明确允许 cutoff 回放、回测和保存结果 | BLOCKED | 官方回测示例不是合同授权 |
| 内部 Agent | 明确允许本地 AI/Agent 分析和内部派生指标 | BLOCKED | 官方 AI 示例不是合同授权 |
| 终止后数据 | 明确保留/删除范围和期限 | BLOCKED | 未公开 |
| 同 Provider raw | 正式字段、单位、精度、null/0 语义和最小响应样例 | PARTIAL | B1 最小样例已验证；完整单位/null/0 合同和长期稳定性仍需冻结 |
| 同 Provider factor | `ts_code + trade_date` 精确自然键、正数因子、日覆盖和单位 | PARTIAL | B1 两证券、两日 `DAILY_EXACT` 最小样例已验证；全历史覆盖和修订仍未证明 |
| 同 Provider calendar | SSE/SZSE 稳定身份与精确日期 | PARTIAL | B1 两交易所最小样例已验证；临时休市修订和旧版本仍未证明 |
| 同 Provider action | 事件自然键、类型、公告/生效/修订时间及覆盖清单 | BLOCKED | B1 证明 `dividend` 可调用；配股、拆并股、更正/撤回、稳定 ID 和 factor 解释关系未闭合 |
| 稳定证券身份 | `ts_code` 生命周期、换码/迁板/重新上市/退市及历史映射 | PARTIAL | B1 验证普通字段；永久 identity 与生命周期仍未获官方保证或样例 |
| 前向 PIT | 允许重复捕获并以真实首次接收建立 `SYSTEM_KNOWLEDGE_PIT` | BLOCKED | 当前 `PIT_PARTIAL`；技术基础存在，但保存、重复捕获、回放、Agent、备份和终止后留存许可未闭合 |
| 成本 | 套餐、费用和所需接口范围明确，用户明确批准 | PASS | 用户已开通 2000 积分，B1 十项探针证明权限生效；不记录支付隐私 |
| 实现范围 | Adapter、DTO 映射、调用预算、错误/限流、数据清理、V13 迁移边界冻结 | PARTIAL | Provider 中立基础已完成；真实字段和许可尚未冻结 |

只有上述所有条件均通过，才能把：

```text
F1_ENTRY_READINESS=READY
```

写入后续独立治理提交。任何单项通过都不得覆盖其他阻断。

## 3. 当前 BLOCKED_MULTIPLE 的具体组成

1. `BLOCKED_WRITTEN_PERMISSION`
   - 最小自动 API 探针及响应、Hash、摘要、夹具保存/删除边界；
   - 本地长期保存；
   - 历史回放和回测；
   - 内部 Agent 与派生指标；
   - 本地 UI、备份、脱敏夹具；
   - 服务终止后的数据处理。
2. `BLOCKED_TECHNICAL_EVIDENCE`
   - 公司行动是否覆盖配股、拆并股和更正/撤回；
   - 稳定 action ID 与 factor 变化的解释关系；
   - revision/snapshot/published/update/旧版本是否确实不可用；
   - 证券身份生命周期；
   - raw/factor/calendar/action 的完整字段资格、null/0、长期稳定和全历史覆盖。

`BLOCKED_COST_APPROVAL` 已解除：用户已开通 2000 积分，技术权限已验证生效。

因此唯一合法判定是：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

## 4. 获得答复后的判定树

1. Tushare 明确允许全部用途，且剩余技术合同满足四类事实：
   - 用户另行批准 F1；
   - Codex 可规划 `3A-R3B-F1-TUSHARE`，只实现受控 Adapter、V13 隔离接入和测试；
   - 不自动迁移正常业务库，不启动 scheduler、F2B 或 F3。
2. 许可允许，但公司行动闭环不足：
   - 只允许冻结缩小能力；
   - 若无法在单 Provider 内解释 factor，完整 QFQ F1 仍阻断；
   - 禁止用 CNINFO、BaoStock 或 AKShare 拼接成同源 lineage。
3. 许可不允许长期保存、回测或 Agent：
   - Tushare 主路线判定失败；
   - 切换备用 `IFIND` 的正式报价与试用准备，但仍需用户单独批准。
4. Tushare 无书面回复：
   - 保持 `TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST` 一次等待窗口；
   - 不无限追问或开发猜测字段；
   - 由用户决定是否进入 iFinD 备用路线询价。

## 5. F1 技术实施范围候选

在许可和样例通过后，预计需要 **6—10 个专注开发日**，包括：

1. Tushare Provider capability 与四类 DTO 精确映射；
2. 受控 HTTP 客户端、限流、超时、错误、空值和重试边界；
3. raw/factor/calendar/action 的字段资格、单位和稳定身份；
4. 临时隔离响应取证、脱敏、Hash 和固定合成回归；
5. V13 随机 Schema 持久化、append-only、cutoff、QFQ 与 2F V2；
6. Java/Python/Mock/旧规则兼容回归；
7. 治理文档与安全门。

这是基于已经合入的 Provider 中立 V13/Mock/QFQ 基础的工程估算，不是进度承诺。若公司行动或身份契约必须重构，必须重新评估并触发既有重大暂停门。

## 6. 继续禁止

- 不追加购买或再次调用 Tushare；
- 不申请、激活或调用 iFinD；
- 不开发任何真实 Adapter；
- 不迁移正常业务库 V13；
- 不写业务数据库；
- 不跨 Provider 拼接 QFQ；
- 不启动 F2B、F3、Shadow、Day 002 或 scheduler；
- 不开始 3A-R3B-1 或 3B；
- 不授权投资建议、券商账户、真实交易或自动交易。
