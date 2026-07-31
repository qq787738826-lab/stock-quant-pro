# 3A-R3B-F1D：Tushare 书面许可闭环与 F1 单阻断收敛任务书

## 1. 任务目标

本阶段只登记用户提供的 Tushare 官方七项逐条书面回复，建立类型化许可与 F1
准入判定模型，并把当前完整 F1 从“书面许可 + 技术证据”双阻断收敛为单一技术
证据阻断。本阶段不扩大 Tushare 技术能力，不调用 Provider，不检查 Token，不访问
数据库，不执行 V13，也不启动生产、scheduler、Shadow、F2B 或 F3。

```text
INTEGRATION_BRANCH=feature/1.4.0-agent-team
INTEGRATION_BASE=6b34e0f730d8f70fb5894c78e692062ae5fb303d
TASK_BRANCH=codex/1.4.0-stage-3ar3b-f1d-tushare-written-permission-closure
TARGET_COMMIT_MESSAGE=feat(agent): close tushare written permission gate
```

## 2. 输入证据

### 2.1 TS-WP-001

历史量化数据来源证据保持不变：

```text
问：这个可以用来当量化数据来源吧
答：可以
```

它只支持：

```text
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
```

### 2.2 TS-WP-002

用户提供的 Tushare 官方回复精确脱敏文字转录为：

```text
1. 本地数据库保存：允许
2. 策略回测/历史回放：允许
3. 本地AI或智能体分析：允许
4. 程序自动调用/定时更新：允许
5. 字段结构、Hash、摘要和错误日志留存：允许
6. 可以一直保存到本地
7. 适用于个人Tushare Pro 2000积分账号
```

证据元数据固定为：

```text
EVIDENCE_ID=TS-WP-002
SOURCE=TUSHARE_OFFICIAL_REPLY
PROVENANCE=USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION
TRANSCRIPTION_RECEIVED_AT=2026-07-31T11:07:00+08:00
OFFICIAL_REPLY_AT=UNKNOWN
USER_ATTESTED_OFFICIAL_SOURCE=true
ORIGINAL_ARTIFACT_STORED=false
SCREENSHOT_REVIEWED=false
INDEPENDENT_SOURCE_AUTHENTICITY_REVIEWED=false
```

仓库不保存截图、联系人、企业微信 ID、头像、手机号、账号、Token 或其他个人信息。
本阶段不声称 Codex 查看了原始截图，也不声称完成独立来源真实性复核。

## 3. 类型化许可合同

新增不可变 `TushareWrittenPermissionQualification`。每项 `VERIFIED` 许可必须引用
已登记证据 ID；没有证据的 claim 不得升级为 `VERIFIED`。当前个人研究许可为：

| 许可项 | 当前状态 | 证据 |
|---|---|---|
| 量化数据来源 | `VERIFIED` | `TS-WP-001` |
| 本地数据库保存 | `VERIFIED` | `TS-WP-002` |
| 历史回放与策略回测 | `VERIFIED` | `TS-WP-002` |
| 本地 AI/Agent 分析 | `VERIFIED` | `TS-WP-002` |
| 程序自动调用/定时更新 | `VERIFIED` | `TS-WP-002` |
| 字段结构、Hash、摘要和错误日志留存 | `VERIFIED` | `TS-WP-002` |
| 服务到期后继续本地保存 | `VERIFIED` | `TS-WP-002` |
| 个人 Tushare Pro 2000 积分账号范围 | `VERIFIED` | `TS-WP-002` |
| 原始数据再分发 | `NOT_GRANTED` | 用户未授权扩大用途 |
| 商业数据服务 | `NOT_GRANTED` | 用户未授权扩大用途 |
| Token/账号共享 | `NOT_GRANTED` | 用户未授权扩大用途 |

模型必须固定：

```text
personalResearchPermissionComplete=true
providerWrittenPermissionComplete=true
WRITTEN_PERMISSION_GATE=PASS
BLOCKED_WRITTEN_PERMISSION=RESOLVED
```

原始转录只能保存在类型化证据对象和治理文档中；Capability 只投影证据 ID、状态和
脱敏 provenance 元数据，不投影联系人、凭据或 Token。

## 4. 类型化 F1 准入合同

新增不可变 `TushareF1EntryQualification`，只从书面许可资格和现有
`TushareTechnicalQualification` 推导门禁，不接受调用方传入裸布尔结论。

当前判定：

```text
WRITTEN_PERMISSION_GATE=PASS
TECHNICAL_EVIDENCE_GATE=BLOCKED
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
ACTIVE_F1_BLOCKER=BLOCKED_TECHNICAL_EVIDENCE
fullF1EntryReady=false
```

剩余技术缺口保持：

- 完整公司行动覆盖；
- 稳定 action ID；
- factor/action 稳定解释关系；
- revision/snapshot/published/update 语义；
- 历史旧版本查询；
- 永久证券身份；
- 全历史 `DAILY_EXACT`；
- 完整 QFQ lineage 与生产运行链。

因此继续保持：

```text
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL
FULL_TECHNICAL_CONTRACT_READY=false
formalEligible=false
```

## 5. Capability 与运行边界

`TushareMarketFactProvider` 的许可字段必须从类型化许可/F1 模型投影。书面许可
PASS 只解除粗粒度许可阻断，不启用任何新运行入口。以下能力继续为 false：

```text
reducedResearchProductionRuntimeReady=false
normalBusinessDatabaseRuntimeReady=false
schedulerRuntimeReady=false
agentDecisionRuntimeReady=false
backtestExecutionRuntimeReady=false
f2bRuntimeReady=false
f3RuntimeReady=false
formalEligible=false
fullF1EntryReady=false
```

F1C 随机隔离手工运行状态保持 READY，但它仍不是生产、正常业务库、Agent、回测、
Shadow 或交易运行入口。

## 6. 历史证据边界

B1 在执行时的完整探针合同前置事实不得回写：

```text
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
```

这些是 `2026-07-30` B1 执行时点的历史状态。`TS-WP-002` 只改变收到转录后的当前
个人研究许可，不把 B1 追认为完整证据探针，不要求重跑，也不改变其历史前置。

## 7. 验证要求

必须离线验证：

1. 七项转录逐字一致；
2. provenance 元数据与六个真实性/留存边界一致；
3. 每个 `VERIFIED` claim 均有登记证据；
4. 再分发、商业数据服务和 Token 共享保持 `NOT_GRANTED`；
5. 许可不完整时书面门和 F1 准入不能越级；
6. 当前书面门 PASS、技术门 BLOCKED、F1 只剩技术阻断；
7. Capability 与两个类型化模型一致；
8. Capability 不包含 Token 或原始联系人信息；
9. F1A/F1B/F1C、Provider V2 与 QFQ 回归不退化；
10. `quant-core` 和 `quant-server` 安全测试通过；
11. Markdown、UTF-8、链接、表格、尾随空白和 Git 范围检查通过。

不得联网，不得读取环境 Token，不得连接 PostgreSQL。

## 8. 正式门禁和阶段边界

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

Provider 新增调用为 0，Tushare 累计真实业务请求保持 20，iFinD 为 0。正常业务库
未访问，V13 未执行；scheduler、Shadow、Day 002、F2B、F3、3A-R3B-1、3B 和交易
均未开始。本阶段完成后只提交并推送任务分支，不 merge，不自动开始下一阶段。
