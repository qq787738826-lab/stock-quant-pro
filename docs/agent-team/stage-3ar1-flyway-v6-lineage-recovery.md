# 阶段 3A-R1：Flyway V6 迁移血统恢复

## 1. 状态

状态：**任务分支实现与 Codex 本地验证完成，待 ChatGPT 基于实际 Git 提交验收，未合入。**

- 冻结基线：`99b369fcc652b8344453532a7ff9597751a6040b`
- 任务分支：`codex/1.4.0-stage-3ar1-flyway-v6-lineage-recovery`
- 目标提交：`fix(db): restore flyway v6 migration lineage`
- ChatGPT 实际 Git 提交验收：尚未进行
- 是否合入：否
- 是否开启 Shadow scheduler：否
- 是否创建专用测试库 public 真实 Shadow 批次：否
- 完整 3A：未完成
- 3B：未开始
- 完整任务书：
  [3ar1-flyway-v6-lineage-recovery.md](tasks/3ar1-flyway-v6-lineage-recovery.md)

当前项目事实以 [CURRENT_STATE.md](CURRENT_STATE.md) 为唯一权威来源。本文件中的测试结果
均为 Codex 本地执行证据，不是 GitHub Actions CI。

## 2. V6 血统结论

V6 实际文件：

```text
quant-server/src/main/resources/db/migration/V6__temporal_market_foundation.sql
```

Git `--follow` 审计只发现两个内容版本：

| 事实 | 提交 / 值 |
|---|---|
| public 实际应用版本提交 | `39f929aadebf9e1df6c392d38b97d7058b17dfff` |
| 已应用 Git blob | `e2b7e31034089b74c6f9eaf5b8bf2eaed9b7510b` |
| public V6 checksum | `-981595186` |
| 首次修改 V6 的提交 | `3a3eebd2ef580d31a6b02aab1a7204ea02fdba58` |
| 被修改 Git blob | `4ed12075492eeeb14706d2f06c73919564abdfd2` |
| 被修改版本 checksum | `1099377059` |

仓库 V6 已恢复为首个提交中的已应用内容；Flyway 使用实际算法重新校验为
`-981595186`，并在专用测试库 public 上只读 validate 成功。没有猜测或手工写入
checksum。

## 3. 语义差异与 V12

被修改 V6 后来加入的合法变化是：

- `market_data_dataset_versions` 的 UPDATE/DELETE/TRUNCATE 拒绝；
- `security_status_events` 从只拒绝 UPDATE 加强为三类修改都拒绝；
- `security_status_history` 与 `trading_calendar_revisions` 只允许一次合法
  `known_to` 关闭；
- 删除 calendar 的 `previous_open_date`、`next_open_date` 及其检查约束。

V7 至 V11 的 SQL、Java Repository 和测试均不引用两个旧导航列，也不要求这些触发器
必须先于 V7 存在。因此新增
`V12__temporal_market_foundation_hardening.sql`，不使用 V6.1 或 out-of-order。
V12 只承接上述 delta；若旧导航列任一存在非空值，迁移在删除前抛错并整体回滚。

## 4. public V12 事件与当前基线

第一次运行本阶段全量 `quant-server` 时，既有
`AgentEvidenceVetoPostgresIntegrationTest` 没有隔离 Flyway Schema，意外把专用
`stock_quant_test` 数据库的 `public` 从 V6 按仓库合法迁移链前向迁移至 V12。

事实边界：

- 这是专用测试库事件，不是生产数据库迁移；
- 未执行 repair、clean、baseline、回滚或备份恢复；
- 未删除 V7 至 V12 对象，未修改 `flyway_schema_history` 或手工 checksum；
- V12 事务前置保护通过，因此迁移当时两个旧导航列不存在非空值；
- 迁移事件前没有保存完整业务表行数或逐行指纹，无法证明的迁移前比较明确记为
  `UNKNOWN`；
- 用户选择接受当前 public V12，从此只做只读 validate 与基线指纹检查。

最终只读检查：

| 检查 | 结果 |
|---|---|
| Flyway 成功版本 | V1 至 V12，各版本恰好一条 |
| 失败 / 重复版本 | `0 / 0` |
| V6 checksum | `-981595186` |
| V12 checksum | `-178798261` |
| Flyway validate | 通过，12 个迁移全部有效 |
| `portfolio_accounts` | 1 |
| `positions` / `manual_orders` / `simulated_trades` | `0 / 0 / 0` |
| `account_equity_snapshots` / `risk_events` | `1 / 0` |
| Shadow batch / item / review | `0 / 0 / 0` |
| 测试随机 Schema 残留 | 0 |

上述行数在接受 V12 后的最终回归前后保持一致；它们不能替代缺失的迁移前快照。

## 5. 测试隔离修复

新增共享 `AgentPostgresTestEnvironment` 安全门，统一：

- 生成 `agent_it_<scope>_<uuid>` 随机 Schema；
- datasource URL 显式设置 `currentSchema`；
- Hikari `schema`、Flyway `default-schema`、`schemas` 指向同一 Schema；
- 固定 `create-schemas=false`、`validate-on-migrate=true`、
  `baseline-on-migrate=false`；
- 迁移前拒绝 `public` 和任何不符合随机命名契约的目标；
- 测试前采集 public V12 全表行指纹、结构指纹和 Flyway 历史；
- 测试后精确删除随机 Schema，并逐项证明 public 基线未变化。

已迁移到共享安全门的直接 public Spring 测试包括：

- `AgentEvidenceVetoPostgresIntegrationTest`
- `AgentHttpClientContractIntegrationTest`
- `AgentInvalidResponsePostgresIntegrationTest`
- `AgentPythonServicePostgresSmokeTest`
- `AgentReadonlyContextPostgresIntegrationTest`
- `AgentStage2CReadonlyContextPostgresIntegrationTest`
- `AgentStage2DPostgresPythonIntegrationTest`
- `AgentTaskPostgresIntegrationTest`

静态安全测试同时扫描全部 Spring PostgreSQL 测试：每个类必须使用共享安全门，或显式
提供 `currentSchema` 与三项 Flyway Schema 配置；旧的直接 datasource 注册入口被禁止。

## 6. 双血统和克隆结果

真实 PostgreSQL 随机 Schema 已验证：

1. 空 Schema 从 V1 顺序迁移到 V12；
2. 隔离重建 V1 至历史 V6，validate 后迁移 V7 至 V12；
3. 当前 public V12 只读指纹；
4. 三者最终的 relation、column、type、nullability、default、PK、unique、FK、check、
   index、trigger、function、sequence 与 Flyway 版本集合收敛；
5. V12 在旧导航列存在非空数据时事务失败，Schema 和 Flyway 历史保持 V6；
6. 所有随机 Schema 在 `finally` 中精确删除。

旧 V6 血统克隆迁移到 V12 后，真实 Python Agent 服务与 Java Spring 上下文成功启动，
Shadow 临时开启但 scheduler 保持关闭；单证券隔离测试批次完成并只写随机 Schema。
该 Schema 随后删除，public 的 Shadow 三表仍均为 0。

## 7. Codex 本地测试证据

| 测试组 | 运行/失败/错误/跳过 | 说明 |
|---|---:|---|
| V6/V12 静态与隔离安全门定向 | `16/0/0/0` | checksum、delta、配置和全测试源扫描 |
| 原触发类隔离复测 | `2/0/0/0` | 随机 Schema，`Skipped=0` |
| 双血统、指纹和 public validate | `1/0/0/0` | V1→V12、V1→V6→V12、事务保护，`Skipped=0` |
| 旧血统克隆 Java/Python 单证券 | `1/0/0/0` | V11 控制面兼容，`Skipped=0` |
| 全部真实 PostgreSQL 兼容矩阵 | `47/0/0/0` | 2D 至 3A-1、Live Gate、随机 Schema，`Skipped=0` |
| `quant-server` 全量 | `388/0/0/0` | 数据库环境已提供，无环境门禁跳过 |
| `quant-core` 全量 | `4/0/0/0` | 核心回测兼容 |
| Python `compileall` | 通过 | `quant-ai/app` |
| Python完整 unittest | `123/0/0/0` | 既有规则完整回归 |
| Java AKShare Live Gate | `1/0/0/0` | 真实接口与随机 Schema，`Skipped=0` |
| Vue 类型检查与生产 build | 通过 | `vue-tsc -b` 与 `vite build` |
| `git diff --check` | 通过 | 最终增量无空白错误 |

所有真实数据库测试均使用随机隔离 Schema，最终残留为 0。public V12 全表行、结构和
Flyway 历史指纹在最终兼容矩阵及全量回归前后不变。

## 8. 边界

- 没有修改 V1 至 V5 或 V7 至 V11；
- 没有修改 3A-1 功能、六智能体或 2I 规则；
- 没有 repair、clean、回滚、恢复备份或手工修改历史；
- 没有在 public 创建真实 Shadow 批次；
- scheduler 保持关闭；
- 长期 3A 观察尚未开始；
- 3B 未开始；
- 本任务分支尚未合入。
