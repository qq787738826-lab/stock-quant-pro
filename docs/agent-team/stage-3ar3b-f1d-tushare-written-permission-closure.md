# 3A-R3B-F1D：Tushare 书面许可闭环与 F1 单阻断收敛阶段记录

## 1. Git 与阶段边界

```text
INTEGRATION_BRANCH=feature/1.4.0-agent-team
INTEGRATION_BASE=6b34e0f730d8f70fb5894c78e692062ae5fb303d
TASK_BRANCH=codex/1.4.0-stage-3ar3b-f1d-tushare-written-permission-closure
TARGET_COMMIT_MESSAGE=feat(agent): close tushare written permission gate
```

开始前，本地与远程集成分支均精确位于冻结基线，ahead/behind 为 `0/0`；已跟踪
工作区干净、暂存区为空。`.ai/` 只通过 Git 状态确认仍未跟踪，没有读取或触碰。
本阶段没有读取或检查 `TUSHARE_TOKEN`，没有调用 Tushare/iFinD，没有访问数据库，
没有执行 V13，也没有启动任何服务。

F1C 双提交链：

```text
0d806e975985038e8d8c617ce1ce4c56e1dc80dd
6b34e0f730d8f70fb5894c78e692062ae5fb303d
```

已经通过 ChatGPT 对实际 Git 提交的最终复验，并经用户批准纯 fast-forward 合入。
F1D 基于最终 F1C 集成提交实施。

## 2. 书面证据登记

历史 `TS-WP-001` 保持：

```text
问：这个可以用来当量化数据来源吧
答：可以
```

新增 `TS-WP-002` 精确脱敏文字转录：

```text
1. 本地数据库保存：允许
2. 策略回测/历史回放：允许
3. 本地AI或智能体分析：允许
4. 程序自动调用/定时更新：允许
5. 字段结构、Hash、摘要和错误日志留存：允许
6. 可以一直保存到本地
7. 适用于个人Tushare Pro 2000积分账号
```

元数据：

```text
TRANSCRIPTION_RECEIVED_AT=2026-07-31T11:07:00+08:00
OFFICIAL_REPLY_AT=UNKNOWN
USER_ATTESTED_OFFICIAL_SOURCE=true
ORIGINAL_ARTIFACT_STORED=false
SCREENSHOT_REVIEWED=false
INDEPENDENT_SOURCE_AUTHENTICITY_REVIEWED=false
PROVENANCE=USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION
SOURCE=TUSHARE_OFFICIAL_REPLY
```

原始截图不入 Git；仓库没有联系人、企业微信 ID、头像、手机号、账号、Token 或其他
个人信息。阶段结论不声称 Codex 查看过截图或独立认证来源真实性。

## 3. 类型化许可资格

新增不可变 `TushareWrittenPermissionQualification`：

- `PermissionClaim(status,evidenceIds)` 要求每项 `VERIFIED` 必须有证据；
- `EvidenceMetadata` 明确来源、转录接收时间和真实性/留存边界；
- 精确转录 provenance 不允许冒充原件保存、截图审阅或独立认证；
- 再分发、商业数据服务和 Token 共享只能保持 `NOT_GRANTED`；
- `personalResearchPermissionComplete` 由八项个人研究许可的 blockers 计算。

当前许可投影：

```text
WRITTEN_QUANT_DATA_SOURCE_USE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_LOCAL_STORAGE_PERMISSION=VERIFIED
WRITTEN_PERSONAL_BACKTEST_PERMISSION=VERIFIED
WRITTEN_PERSONAL_AGENT_ANALYSIS_PERMISSION=VERIFIED
WRITTEN_AUTOMATED_API_UPDATE_PERMISSION=VERIFIED
WRITTEN_TECHNICAL_AUDIT_METADATA_RETENTION_PERMISSION=VERIFIED
POST_EXPIRY_DATA_RETENTION_PERMISSION=VERIFIED
PERSONAL_2000_POINT_ACCOUNT_SCOPE_PERMISSION=VERIFIED
RAW_DATA_REDISTRIBUTION_PERMISSION=NOT_GRANTED
COMMERCIAL_DATA_SERVICE_PERMISSION=NOT_GRANTED
TOKEN_SHARING_PERMISSION=NOT_GRANTED
personalResearchPermissionComplete=true
providerWrittenPermissionComplete=true
```

## 4. F1 准入聚合

新增不可变 `TushareF1EntryQualification`，由书面许可模型和 F1B/F1C 技术模型推导：

```text
WRITTEN_PERMISSION_GATE=PASS
TECHNICAL_EVIDENCE_GATE=BLOCKED
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
ACTIVE_F1_BLOCKERS=[BLOCKED_TECHNICAL_EVIDENCE]
fullF1EntryReady=false
```

`BLOCKED_WRITTEN_PERMISSION` 当前已解决，但完整 F1 仍不 READY。技术阻断包括完整
公司行动、稳定 action ID、factor/action 解释关系、Provider revision/snapshot/
published/update、旧版本、永久证券身份、全历史 `DAILY_EXACT` 和完整 QFQ lineage。

```text
V13_LINEAGE_PARTIAL
PIT_PARTIAL
STABLE_SECURITY_ID=PARTIAL
FULL_TECHNICAL_CONTRACT_READY=false
```

## 5. Capability 投影与运行隔离

`TushareMarketFactProvider` 不再复制许可常量；licensing 从
`TushareWrittenPermissionQualification` 和 `TushareF1EntryQualification`
类型化投影。`LimitedPersonalFormalCaptureAuthorization` 同步要求八项个人研究
许可均为 `VERIFIED`、Provider 书面许可完整，并继续要求三项禁止用途为
`NOT_GRANTED`。

书面门 PASS 不改变下列运行事实：

```text
formalEligible=false
fullF1EntryReady=false
reducedResearchProductionRuntimeReady=false
normalBusinessDatabaseRuntimeReady=false
schedulerRuntimeReady=false
agentDecisionRuntimeReady=false
backtestExecutionRuntimeReady=false
f2bRuntimeReady=false
f3RuntimeReady=false
```

F1C 的随机隔离手工入口保持 READY；通用 FORMAL、生产、正常业务库、scheduler、
Agent、回测、Shadow、F2B/F3 和交易入口均未开放。

## 6. 历史状态不回写

B1 执行时点继续保留：

```text
TRACK_B_FULL_EVIDENCE_PROBE_STATUS=PARTIAL_NOT_COMPLETE
TRACK_B_FULL_PROBE_LEGAL_PREREQUISITES=NOT_MET
WRITTEN_AUTOMATED_PROBE_PERMISSION=UNVERIFIED
WRITTEN_RESPONSE_RETENTION_PERMISSION=UNVERIFIED
```

这些是 B1 当时没有事前书面答复的历史事实。F1D 不追认 B1 为完整证据探针，不重新
执行请求，也不把收到转录后的当前许可状态倒写到探针执行前。

## 7. 实际 Git 复验增量修复

初始提交 `349856ea6e9e3dc423fc1ad9115886cfc8858159` 的实际 Git 复验未通过：
当时 `PermissionClaim` 只验证 VERIFIED claim 携带了已登记 Evidence ID，没有验证
对应 `EvidenceMetadata` 的 provenance 是否足以支撑 VERIFIED，因此不可信 Metadata
仍可能使 `permissionComplete=true`。

增量修复新增并冻结以下不变量：

1. `EvidenceMetadata.supportsVerifiedPermission()` 成为类型化证据资格边界；每份被
   VERIFIED claim 引用的证据都必须通过该资格。
2. `USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION` 必须来自 Tushare 官方回复、由用户
   证明为官方来源、精确转录非空，并同时保持
   `originalArtifactStored/screenshotReviewed/independentSourceAuthenticityReviewed=false`。
3. `UNVERIFIED` 永远不能支撑 VERIFIED；`OFFICIAL_ARTIFACT_REVIEWED` 必须有实际
   原件或截图审阅事实；`OFFICIAL_DOCUMENT` 当前没有合格的个人书面许可证据模型，
   因此不能升级 claim。
4. `assess()` 交叉核验 VERIFIED claim 的 Evidence ID 集合、Map key、
   `metadata.evidenceId`、证据可信资格、非 VERIFIED claim 的空 evidence，以及 Map
   中不存在未引用条目；任一矛盾立即拒绝。

本修复不改变 TS-WP-002 七项精确转录、用户声明或业务结论。当前冻结工厂继续只使用
`USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION`；书面许可门保持 PASS，当前 F1 仍只剩
`BLOCKED_TECHNICAL_EVIDENCE`。

`049c750026fa00dad70c12667fad732af07d60ce` 已修复 Evidence provenance 可信度，
但后续实际 Git 复验继续发现 Claim 与 Evidence 支持范围尚未绑定：如果多个 VERIFIED
claim 复用一份可信但不相关的 Evidence ID，旧模型仍可能错误升级许可。

本次增量继续固定类型化 `PermissionSubject`：

- `TS-WP-001` 只支持 `QUANT_DATA_SOURCE_USE`；
- `TS-WP-002` 精确支持本地保存、回测、Agent、自动更新、技术审计元数据留存、
  到期后继续保存和个人 2000 积分账号范围七项主题；
- 再分发、商业数据服务和 Token 共享拥有独立主题，但没有被 TS-WP-001/002 支持，
  并继续为 `NOT_GRANTED`。

`PermissionClaim` 现在携带明确主题，`AssessmentInput` 要求每个字段的主题精确匹配。
`assess()` 逐 Claim 验证 Evidence 的可信 provenance 和
`supportedPermissionSubjects`，并要求每份 Evidence 的主题集合与实际引用它的
VERIFIED Claim 主题集合完全一致。Capability 同步投影这组主题白名单，下游不能再把
“来源可信”解释成“支持任意许可”。该修复不分析自由文本、不改变七项转录，也不改变
当前书面门 PASS 和 F1 单一技术证据阻断。

## 8. 验证

本阶段只运行离线验证：

| 验证组 | 结果 |
|---|---|
| Java 干净编译 | `mvn -pl quant-server -am clean compile -DskipTests`；8 个 core、173 个 server 生产源码，`BUILD SUCCESS` |
| F1D provenance/subject、F1 聚合与 Capability 定向 | `29/0/0/0`；含 17 项书面许可证据可信度与主题绑定、2 项 F1 聚合、10 项 Capability/有限捕获授权投影 |
| F1A/F1B/F1C、Provider V2、QFQ 与 F1D 联合回归 | `116/0/0/0`；Provider V2 10 项、QFQ 权威黄金/lineage 19 项 |
| `quant-core` 全量 | `4/0/0/0` |
| `quant-server` 安全全量 | `407/0/0/0`；命令级排除全部 `*IntegrationTest/*Postgres*/*CrossLanguage*/*Live*` |

验证覆盖七项精确转录、可信 provenance、Claim/Metadata 交叉门、未引用证据、
UNVERIFIED/伪造 artifact/未支持 official document 反例、Claim 字段主题错位、
TS-WP-001/002 跨主题借用、空/无关/未引用支持主题、禁止用途、当前 F1 单技术阻断、
Capability 投影、`LimitedPersonalFormalCaptureAuthorization` 回归、运行门保持关闭和
Token 不泄露。安全全量没有运行 Live、数据库或跨语言测试；没有检查环境 Token 或
访问 PostgreSQL。

## 9. 当前阶段状态

```text
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED

F1D_PROVIDER_REAL_CALL_COUNT=0
TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=20
IFIND_REAL_CALL_COUNT=0
```

本阶段不访问数据库，不执行正常业务库 V13。F2B/F3、scheduler、Shadow、Day 002、
3A-R3B-1、3B 和交易均未开始。F1D 技术与治理实现已在任务分支完成，待 ChatGPT
基于实际 Git 提交验收；尚未合入，不自动开始下一阶段。
