# Track B F1 准入合同

## 1. 当前判定

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

主要路线的具体 Provider 是 Tushare Pro。该选择只确定下一项外部取证顺序，不批准购买、账号、调用、Adapter、V13 业务库迁移或 F1 实施。

## 2. F1 READY 的全部条件

| 门禁 | READY 证据 | 当前状态 | 当前 finding |
|---|---|---|---|
| 个人用途 | 官方书面许可适用于个人非商业研究 | BLOCKED | 现行协议为个人非商业且仅个人查看，适用范围不足 |
| 本地持久化 | 明确允许 raw/factor/calendar/action、metadata、Hash、lineage 本地长期保存 | BLOCKED | 官方技术建议与合同语义冲突，需书面确认 |
| 历史回放与回测 | 明确允许 cutoff 回放、回测和保存结果 | BLOCKED | 官方回测示例不是合同授权 |
| 内部 Agent | 明确允许本地 AI/Agent 分析和内部派生指标 | BLOCKED | 官方 AI 示例不是合同授权 |
| 终止后数据 | 明确保留/删除范围和期限 | BLOCKED | 未公开 |
| 同 Provider raw | 正式字段、单位、精度、null/0 语义和最小响应样例 | PARTIAL | `daily` 文档充分，仍需响应样例 |
| 同 Provider factor | `ts_code + trade_date` 精确自然键、正数因子、日覆盖和单位 | PARTIAL | 文档支持逐日返回；需最小探针验证 `DAILY_EXACT` |
| 同 Provider calendar | SSE/SZSE 稳定身份与精确日期 | PARTIAL | 文档充分；需最小响应样例验证 |
| 同 Provider action | 事件自然键、类型、公告/生效/修订时间及覆盖清单 | BLOCKED | `dividend` 只形成部分公司行动闭环 |
| 稳定证券身份 | `ts_code` 生命周期、换码/退市语义 | PARTIAL | 公开字段存在；生命周期需确认 |
| 前向 PIT | 允许重复捕获并以真实首次接收建立 `SYSTEM_KNOWLEDGE_PIT` | BLOCKED | 技术可建，许可未闭合 |
| 成本 | 套餐、费用和所需接口范围明确，用户明确批准 | BLOCKED | 200 元/年核心积分价公开，但用户未批准购买 |
| 实现范围 | Adapter、DTO 映射、调用预算、错误/限流、数据清理、V13 迁移边界冻结 | PARTIAL | Provider 中立基础已完成；真实字段和许可尚未冻结 |

只有上述所有条件均通过，才能把：

```text
F1_ENTRY_READINESS=READY
```

写入后续独立治理提交。任何单项通过都不得覆盖其他阻断。

## 3. 当前 BLOCKED_MULTIPLE 的具体组成

1. `BLOCKED_WRITTEN_PERMISSION`
   - 本地长期保存；
   - 历史回放和回测；
   - 内部 Agent 与派生指标；
   - 本地 UI、备份、脱敏夹具；
   - 服务终止后的数据处理。
2. `BLOCKED_TECHNICAL_EVIDENCE`
   - 公司行动是否覆盖配股、拆并股和更正/撤回；
   - 稳定 action ID 与 factor 变化的解释关系；
   - `DAILY_EXACT` 的真实响应验证；
   - revision/snapshot/published/update/旧版本是否确实不可用；
   - 证券身份生命周期和字段 null/0 语义样例。
3. `BLOCKED_COST_APPROVAL`
   - 2000 积分 200 元/年的官方价格已知；
   - 用户尚未批准注册、购买或发生任何成本。

因此唯一合法判定是：

```text
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

## 4. 获得答复后的判定树

1. Tushare 明确允许全部用途，且最小样例满足四类事实：
   - 用户另行批准最小成本和 F1；
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

- 不注册、购买或调用 Tushare；
- 不申请、激活或调用 iFinD；
- 不开发任何真实 Adapter；
- 不迁移正常业务库 V13；
- 不写业务数据库；
- 不跨 Provider 拼接 QFQ；
- 不启动 F2B、F3、Shadow、Day 002 或 scheduler；
- 不开始 3A-R3B-1 或 3B；
- 不授权投资建议、券商账户、真实交易或自动交易。
