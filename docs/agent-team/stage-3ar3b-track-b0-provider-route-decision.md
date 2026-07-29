# 3A-R3B-TRACK-B0 阶段记录：真实数据 Provider 路线决策与 F1 准入合同

## 1. 阶段结果

本阶段以 28 条官方页面证据和 1 条已验收的 F0 直接 Provider 探针记录，对精确三个候选完成有界调查。没有调用 Provider、注册账号、申请试用、访问数据库或修改代码。

冻结结论：

```text
TRACK_B_PRIMARY_ROUTE=LOW_COST_PROVIDER_FIRST
TRACK_B_FALLBACK_ROUTE=IFIND
F1_ENTRY_READINESS=BLOCKED_MULTIPLE
```

主要路线精确为 Tushare Pro；备用路线精确为同花顺 iFinD。BaoStock 保留研究辅助，不是正式备用。

## 2. Git 与范围

- 冻结基线：`8b6a6bf39a40e44062a3f7aeb315e17e9b62e199`
- 任务分支：`codex/1.4.0-stage-3ar3b-track-b0-provider-route-decision`
- 本阶段仅新增 8 份 Markdown、更新 3 份治理 Markdown。
- 没有修改 `PROGRESS_LOG.md`、研究预览、Java、Python、Vue、SQL、Flyway、配置、依赖、fixture 或 Provider 运行代码。

## 3. 调查结果

### 3.1 BaoStock

- 法律与用途：`PENDING_WRITTEN_CONFIRMATION`。
- V13/QFQ：`V13_LINEAGE_BLOCKED`。
- PIT：`PIT_PARTIAL`。
- 成本：访问费 0，`OFFICIAL_CONFIRMED`；不等于许可通过。
- 关键事实：F0 已观察 raw/QFQ、通用日历和一次公司行动；独立因子短区间为 0 行，`DAILY_EXACT` 和交易所日历身份未验证。
- 结论：免费不会产生直接费用，但许可和技术缺口足以持续阻断完整 QFQ；不选为主路线或备用路线。

### 3.2 Tushare Pro

- 法律与用途：`PENDING_WRITTEN_CONFIRMATION`。
- V13/QFQ：`V13_LINEAGE_PARTIAL`。
- PIT：`FORWARD_PIT_BUILDABLE`，条件是书面许可通过；不具备 Provider 历史 PIT。
- 成本：2000 积分 200 元/年，`OFFICIAL_CONFIRMED`；用户尚未批准。
- 技术优势：同平台公开 `daily`、逐交易日 `adj_factor`、SSE/SZSE `trade_cal`、`dividend` 和稳定 `ts_code`；QFQ 动态锚点语义明确。
- 许可冲突：现行数据服务协议限定个人、非商业和仅个人查看；官方 AI/技术文档又给出本地缓存、回测与 Agent 工作流。合同优先，必须书面消歧。
- 技术缺口：公司行动完整范围/稳定事件 ID、修订链、逐版本发布时间、真实 `DAILY_EXACT` 样例和证券身份生命周期。
- 结论：技术闭环和个人成本最接近 F1，是唯一主要路线；但现在不能购买或开发 Adapter。

### 3.3 同花顺 iFinD

- 法律与用途：`PENDING_WRITTEN_CONFIRMATION`。
- V13/QFQ：`V13_LINEAGE_UNVERIFIED`。
- PIT：`PIT_UNVERIFIED`。
- 成本：`OFFICIAL_CONTACT_REQUIRED`，需要官方报价，不估算具体金额。
- 技术上限：官方公开多语言 SDK/HTTP、历史行情、基础数据、交易日、公告、额度、错误码和总体复权语义。
- 缺口：公开文档没有完整数据字典，具体指标需 SuperCommand/试用；个人购买、专项授权、留存、revision/snapshot/逐版本时间和旧版本均未公开。
- 结论：适合作为 Tushare 不能满足合同或字段需求时的专业备用；现在不值得消耗有限试用窗口。

## 4. 评分

| 候选 | 加权总分（0—5） | 排名 | 硬门禁 |
|---|---:|---:|---|
| Tushare Pro | 3.10 | 1 | 书面用途、action/版本样例、用户成本批准 |
| 同花顺 iFinD | 2.10 | 2 | 价格、个人资格、合同、四类字段与 PIT 试用证据 |
| BaoStock | 1.98 | 3 | 许可、factor/DAILY_EXACT、calendar identity、action revision |

评分没有覆盖硬门禁；三者均未达到 F1 READY。

## 5. F1 准入结论

`F1_ENTRY_READINESS=BLOCKED_MULTIPLE`。

具体阻断：

1. Tushare 本地长期保存、回放/回测、Agent、备份/fixture、服务终止后留存需要书面确认；
2. 公司行动不完整、真实逐日因子和 stable identity/revision/published semantics 需要最小样例；
3. 用户尚未批准 200 元/年或任何其他费用。

即使将来判为 READY，也必须先通过 ChatGPT 实际 Git 验收、合入和用户单独 F1 授权。

## 6. 最小探针

[试用探针合同](track-b-trial-probe-contract.md) 固定：

- 两只证券：`600000.SH`、`000001.SZ`；
- 两个由 Provider calendar 确认的历史交易日；
- raw/factor/SSE+SZSE calendar/action/identity/稳定性复取；
- 最多 10 次业务请求，禁止重试；
- 临时隔离响应、Hash、脱敏、按合同删除或保留；
- 不写正常业务库，不执行 V13 public migrate，不开 scheduler，不创建 Day 002。

当前 `TRIAL_PROBE_STATUS=NOT_EXECUTED`。

## 7. 用户外部动作与后续

优先外部动作只有一个：按 [Tushare 询问模板](track-b-permission-request-pack.md#3-tushare-pro-模板主要路线) 取得可归档的书面许可和购买前最小字段样例。

答复通过后，Codex 可先执行独立的“答复与样例准入复核”治理阶段；只有该阶段把所有 F1 条件判为 READY、用户批准成本并另行授权，才可开始 Tushare Adapter。

如 Tushare 明确拒绝或无法满足，切换 `TRACK_B_FALLBACK_ROUTE=IFIND`，先取得报价和专项合同，不直接激活试用。

## 8. 当前正式状态

```text
F0_AUDIT_RESULT=PARTIAL
FREE_IMPLEMENTATION_PATH=RESEARCH_PREVIEW_FIRST
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

- Track A：完成。
- Track B0：任务分支调查与推荐完成，待实际 Git 验收，尚未合入。
- F1/F2B/F3：未开始。
- Provider/iFinD 真实调用：0。
- 数据库访问/V13 正常业务库迁移：无。
- Agent/Shadow：未创建。
- Day 002：未创建。
- scheduler：关闭。
- 3A-R3B-1/3B：未开始。
- 真实交易/自动交易：未授权。
