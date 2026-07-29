# 3A-R3B-F0 免费 Provider 资格审计阶段记录

## 1. 阶段状态

状态：**F0 审计、最小受控探针和 Codex 本地验证已完成，待 ChatGPT 基于实际 Git 提交
验收，尚未合入；F1 未开始。**

- 冻结集成基线：`c47b88e586f6751563fe210f40137a3b7ce5e576`
- 任务分支：
  `codex/1.4.0-stage-3ar3b-f0-free-provider-qualification-audit`
- 提交目标：`docs(agent): audit free provider qualification`
- 任务书：
  [tasks/3ar3b-f0-free-provider-qualification-audit.md](tasks/3ar3b-f0-free-provider-qualification-audit.md)

## 2. 结果

```text
F0_AUDIT_RESULT=PARTIAL
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

BaoStock 0.9.3 的最小受控探针观察到 raw/QFQ 日线、通用交易日历、分红和按证券因子函数
具有实际技术入口；固定短区间内两个因子查询观察到 0 行，全市场单日因子函数按边界未
调用。修复前 collector 未在迭代后复核 Provider 终态，且原始响应已按设计删除，因此
本次 Live response completeness 统一为 `UNVERIFIED`。独立因子仍只能评为 `PARTIAL`，
`DAILY_EXACT=UNVERIFIED`。

客户端 BSD License 不能授予底层数据的本地保存、回放、回测、Agent 或商业使用权；
revision/snapshot/published/update 和旧版本也没有正式证据。BaoStock 的角色为
`PENDING_WRITTEN_PERMISSION`，不是已批准 F1 Adapter。

AKShare 继续固定 1.18.64，并按 Tencent、Sina、Eastmoney、CNINFO 上游拆分为
`RESEARCH_AUXILIARY_ONLY`。CNINFO、SSE、SZSE 和深圳证券信息有限公司只承担
`OFFICIAL_EVIDENCE_ONLY` 角色。当前没有一个免费来源能单独组成完整 V13/QFQ 同源
lineage。

## 3. Live 探针摘要

| 指标 | 结果 |
|---|---:|
| BaoStock 数据逻辑调用 | 8 |
| 匿名登录/退出公开操作 | 2 |
| socket/frame 级协议请求数 | `UNVERIFIED` |
| Provider HTTP 请求 | 0 |
| AKShare 新增 Provider 调用 | 0 |
| 重试 | 0 |
| 停止条件 | 未触发 |
| raw 日线 | 两只证券各观察到 6 行 |
| QFQ 日线 | 两只证券各观察到 6 行 |
| 通用交易日历 | 观察到 8 行 |
| 公司行动 | 观察到 1 行 |
| 按证券因子 | 两次各观察到 0 行 |
| 本次 Live response completeness | `UNVERIFIED` |
| 全市场单日因子 | 未执行 |
| 原始响应临时残留 | 0 |
| 安全摘要 SHA-256 | `f97779bb9d6138faa3b049abb5f1f6da98105e359644ecc785002518086ffd0b` |

安全摘要 Hash 是修复前 collector 生成的原 V1 审计产物，只证明该摘要内容，不证明迭代
终态或响应完整性。8 个数据逻辑调用加 2 个登录/退出操作只是公开函数操作计数，不是
socket request/frame 观测值。

第一次命令启动曾在任何 Provider 网络动作前被包内运行时版本常量差异拦截，Provider 调用
数为 0；工具随后改为以已验证 wheel 的分发元数据为版本权威。表中只统计唯一一次实际
Provider Live 探针。

## 4. 证据边界

- BaoStock wheel/sdist Hash 与 PyPI 元数据已验证；
- BaoStock disclaimer/API URL 在直接 GET 中只返回同一 JavaScript shell，未暴露足以批准
  底层数据使用权的正文；
- AKShare 官方项目文档明确研究用途与接口变化风险；
- Tencent revision 审计复用 3A-R3A 的受控两次请求，不重复联网；
- CNINFO 官方页面确认法定披露平台身份，但没有批量数据许可；
- SSE schedule 可核验交易时间与休市安排；
- SZSE/SZSI 三个受控页面读取超时，记录为 `UNVERIFIED`，没有绕过。

页面 Hash、包 Hash、仓库文件 Hash、Live 安全摘要 Hash 与具体支持/不支持结论见
[证据登记册](free-provider-evidence-register.md)。

## 5. 工具和测试

新增独立工具：

```text
quant-ai/tools/free_provider_audit_f0.py
```

它默认断网，只有 `--live` 可启用固定 BaoStock 探针；内置 10 次预算、两只证券和一个短
区间白名单，不访问 `.env`、数据库、生产应用或 iFinD，不允许全市场函数，递归脱敏并在
`finally` 清理原始响应。修复后 collector 在迭代结束后重新读取 Provider
`error_code/error_msg`：有行且终态错误为 `PARTIAL`，无行且终态错误为 `ERROR`，
终态持续成功才允许 `SUCCESS/EMPTY`；`TIMEOUT` 和 `STRUCTURE_CHANGED` 独立稳定表达。
公开函数摘要只保存参数名、参数 kind 和是否存在默认值，不保存任何默认值。

离线测试和固定 Hash 向量：

```text
quant-ai/tests/agent_team/test_free_provider_audit_f0.py
quant-ai/tests/fixtures/free_provider_audit_f0_expected.json
```

Java、Vue、SQL/Flyway、配置、现有 Provider Adapter、V13 和
`PROGRESS_LOG.md` 均未修改。

## 6. 未解决问题

1. BaoStock 底层数据本地持久化、历史回放、回测、Agent、UI、备份和商业使用需书面许可；
2. 因子是否逐交易日覆盖、单位/精度/空值语义和静默修正策略未验证；
3. 通用日历缺少 SSE/SZSE 交易所身份；
4. 公司行动稳定事件 ID、修订、发布时间和旧版本能力未验证；
5. Sina factor 只完成公开代码审计，未做 Live 探针；
6. SZSE/SZSI 官方服务页需在后续获准阶段重新取得证据；
7. 任何 F1 方案仍需保持单 Provider lineage，不得用官方证据页拼成行情 Provider。

## 7. 安全状态

- 免费 Provider 调用只发生在 F0 固定预算内；
- iFinD 真实调用数：`0`；
- 数据库访问：否；
- 正常业务库 V13：未执行；
- Day 002：未创建；
- scheduler：关闭；
- F1：未开始；
- 3A-R3B-1：未开始；
- 3B：未开始；
- merge：否。
