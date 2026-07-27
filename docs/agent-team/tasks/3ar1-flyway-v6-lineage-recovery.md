# 3A-R1 Flyway V6 迁移血统恢复任务书

## 1. 阶段身份

- 阶段：`3A-R1`
- 名称：Flyway V6 迁移血统恢复
- 冻结集成基线：`99b369fcc652b8344453532a7ff9597751a6040b`
- 任务分支：`codex/1.4.0-stage-3ar1-flyway-v6-lineage-recovery`
- 目标提交：`fix(db): restore flyway v6 migration lineage`

本阶段只恢复已经执行的 V6 迁移血统，把后来追加到 V6 的合法 Schema 变化移入前向
迁移，并永久修复 PostgreSQL 集成测试可能把 Flyway 指向专用测试库 `public` 的隔离
缺陷。本阶段不开发 Shadow 功能，不修改六智能体或 2I 规则，不开始长期 3A 观察或 3B。

## 2. 仓库与数据库事实

V6 实际路径为：

```text
quant-server/src/main/resources/db/migration/V6__temporal_market_foundation.sql
```

Git 历史只有两个不同内容版本：

| 版本 | Git 提交 | Git blob | Flyway checksum | 含义 |
|---|---|---|---:|---|
| 已应用 V6 | `39f929aadebf9e1df6c392d38b97d7058b17dfff` | `e2b7e31034089b74c6f9eaf5b8bf2eaed9b7510b` | `-981595186` | 专用测试库历史实际执行版本 |
| 被改写 V6 | `3a3eebd2ef580d31a6b02aab1a7204ea02fdba58` | `4ed12075492eeeb14706d2f06c73919564abdfd2` | `1099377059` | 首次把后续硬化直接写回 V6 |

首个修改 V6 的提交是
`3a3eebd2ef580d31a6b02aab1a7204ea02fdba58`。恢复前的语义增量包括：

- 为 dataset、event、history 和 calendar 增加不可变或 knowledge-close 触发器；
- 把 `security_status_events` 从只拒绝 UPDATE 加强为拒绝
  UPDATE、DELETE 和 TRUNCATE；
- 删除 `trading_calendar_revisions.previous_open_date` 与
  `next_open_date` 及对应检查约束。

V7 至 V11 不引用上述两个旧导航列，也不依赖硬化触发器在 V7 前存在；因此这些变化不需
在 V7 前执行，前向承接版本选择 V12，而不是启用 out-of-order 的 V6.1。

## 3. 恢复与前向迁移

### 3.1 V6 恢复

仓库 V6 必须逐字节恢复为已应用 Git 版本，使 Flyway checksum 重新等于
`-981595186`。恢复后的 V6 不再接受任何后续编辑。

### 3.2 V12

只新增：

```text
V12__temporal_market_foundation_hardening.sql
```

V12 只承接两个 V6 版本之间的必要 Schema delta。删除旧导航列前必须在同一迁移事务中
证明两个字段均无非空值；否则整条 V12 失败并回滚。不得删除数据、改写历史事实或修改
Agent 规则。

## 4. 专用测试库 public 事件

本阶段第一次运行全量 `quant-server` 时，既有
`AgentEvidenceVetoPostgresIntegrationTest` 直接把 Spring datasource 与 Flyway 绑定到
专用测试库 `public`。该测试使 `public` 使用仓库合法迁移链从 V6 前向迁移至 V12。

冻结处理如下：

- 接受专用测试库 `public` 当前 V12 状态；
- 不恢复备份，不回滚，不删除 V7 至 V12 对象；
- 不修改 `flyway_schema_history`，不手工改 checksum；
- 不执行 Flyway repair 或 clean；
- 该事件只发生于专用测试库，不得描述为生产数据库迁移；
- V12 成功说明其事务前置门禁已证明被删除的两个旧导航列没有非空历史值；
- 迁移事件前的完整业务表行数或逐行指纹没有可用快照，相关比较必须记为无法验证；
- 从被接受的 V12 基线开始，后续测试前后必须做完整 public 数据、结构和 Flyway 历史
  指纹比较。

## 5. PostgreSQL 测试隔离硬门

所有会执行 Flyway migrate 的 Spring PostgreSQL 集成测试必须：

1. 创建名称带随机 UUID 的隔离 Schema；
2. datasource URL 显式包含 `currentSchema`；
3. Hikari `schema` 指向同一随机 Schema；
4. 显式配置 Flyway `default-schema`、`schemas` 与
   `create-schemas=false`；
5. 保持 `validate-on-migrate=true` 与 `baseline-on-migrate=false`；
6. 在迁移前拒绝 `public` 或不符合随机 Schema 命名契约的目标；
7. 只读采集 public V12 的表行指纹、结构指纹与 Flyway 历史；
8. 测试后只删除精确随机 Schema，并验证 public 基线未变化。

测试执行器环境变量只能提供专用测试数据库连接，不能决定迁移 Schema。直接使用
`public` 准备 migrate 必须立即失败。

## 6. 双血统验收

在随机隔离 Schema 中验证：

- 空 Schema 从 V1 顺序迁移到 V12；
- 独立重建历史 V1 至 V6 后先 validate，再迁移 V7 至 V12；
- 两条路径的表、列、类型、nullability、default、主键、unique、外键、check、索引、
  trigger、function、sequence 与 Flyway 版本集合一致；
- 对象 OID、序列当前值和创建时间等非结构事实不参与比较；
- V12 删除前置门禁在旧导航列存在非空数据时事务失败且不留下 V12 历史；
- V11 Shadow 约束及 2D 至 3A-1 兼容能力保持有效。

专用测试库 public 只允许 Flyway validate 和只读指纹检查，不再执行 migrate。

## 7. 3A-1 克隆启动验收

在旧 V6 血统随机 Schema 迁移到 V12 后：

- 启动真实 Python Agent 服务和 Java Spring 测试上下文；
- 临时启用 Shadow、保持 scheduler 关闭；
- 只创建一个隔离 Schema 内的单证券测试批次；
- 验证 V11 三张 Shadow 表、真实 Agent task 关联和终态；
- 不调用 AKShare、不刷新行情、不调用 Portfolio 写路径；
- 测试完成后删除整个随机 Schema。

该批次是隔离测试事实，不是 public 真实 Shadow 观察批次，也不计入长期 3A。

## 8. 禁止范围

- Flyway repair、clean、baseline 或跳过 validate；
- 修改 V1 至 V5 或 V7 至 V11；
- 修改专用测试库 public 数据、历史表或 Schema；
- 恢复备份、回滚或删除 public 已有对象；
- Shadow 功能、六智能体、2I 或业务规则变化；
- 开启 scheduler、创建 public 真实 Shadow 批次或开始 3B；
- 输出或持久化数据库凭据；
- 读取、修改、删除、暂存或提交 `.ai/`。

## 9. 完成边界

完成 3A-R1 只表示：

- V6 与已应用历史 checksum 重新一致；
- 合法硬化由 V12 前向承接；
- 新旧血统在隔离环境收敛；
- 专用测试库 public V12 通过只读 validate；
- 数据库集成测试不再把 Flyway migrate 指向 public。

它不表示长期 3A 观察已经开始或完成，也不批准 3B。
