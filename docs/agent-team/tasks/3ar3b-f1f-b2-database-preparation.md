# 3A-R3B-F1F-B2-DBPREP：专用研究数据库准备链与冻结清单校正

## 基线与目标

- 集成基线：`213264bc63a2584f0fbb30dca059abf272e62a64`。
- 任务分支：`codex/1.4.0-stage-3ar3b-f1f-b2-database-preparation`。
- 本任务只补齐一次性专用数据库准备入口，并把 F1F-B2 启动清单校正为单一 `AuthorizationFile` 合同。
- 本任务不执行正式数据库准备、治理 V14、F1F-B2、Provider 调用或任何后续阶段。

## 冻结数据库合同

数据库目标固定为本机 `127.0.0.1`、数据库/用户 `stock_quant_research`、Schema/search path `tushare_research`。端口必须显式提供，不允许默认 5432、自动选端口或非本机主机。正式目标必须位于不含其他业务数据库的全新专用 PostgreSQL 实例；同名数据库或角色已存在时直接拒绝，不自动删除、接管、repair、clean 或 baseline。

一次性 Java 入口 `TushareControlledAcceptanceDatabasePreparer` 不启动 Spring Boot。PowerShell 包装脚本 `prepare-f1f-b2-dedicated-database.ps1` 只允许：

- 默认 `PREPARATION_ONLY`：校验冻结提交、参数、入口和构建绑定，不连接数据库；
- 显式 `CONTROLLED_DATABASE_PREPARATION`：留给未来用户再次批准的正式准备轮，本任务不执行；
- 临时 PostgreSQL 测试：只对全新随机 PostgreSQL 16 实例执行，与正式模式隔离。

准备入口只运行主 Flyway `classpath:db/migration`，history 为 `flyway_schema_history`，目标精确 V13，`baselineOnMigrate=false`、`outOfOrder=false`、`cleanDisabled=true`。`classpath:db/controlled-acceptance`、治理 history 和 V14 均不可达。

## 秘密与失败边界

输出审计先于计划解析和秘密读取安装。管理员密码与最终专用用户密码只从安全 Console 读取；创建阶段使用进程内随机一次性 bootstrap secret，三类秘密均立即登记输出审计并以字符数组限制生命周期。管理员连接关闭且管理员秘密清零后，才读取最终专用用户密码并轮换 bootstrap password。密码不进入命令行、环境变量、JDBC URL、报告、MANIFEST、sidecar 或授权文件。

目标发生任何修改后的失败只报告 `INCOMPLETE_NOT_APPROVED`、失败阶段和脱敏 reason code；不执行危险自动回滚。该目标不能被 Runner 使用，必须经人工处置和新的正式批准。

## Runner 启动合同

正式 Runner 唯一启动形式为：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\quant-server\scripts\run-f1f-b2-controlled-acceptance.ps1 `
  -AuthorizationFile "<正式非敏感授权文件路径>"
```

构建证明路径、数据库 host/port/name/user/schema、证券、日期、Endpoint、请求预算和 JAR SHA-256 全部来自严格授权文件。重复、未知、缺失、空白或类型错误字段拒绝；Runner 不接受松散命令行覆盖。Token 和数据库密码不得进入授权文件。

旧草案 ID `F1FB2_20260803_140506_96C6DFB7` 已废弃，禁止写入数据库、授权文件、治理证据或后续复用。

## 状态与验收边界

```text
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

合入后只能基于新的集成 SHA 重新执行简化 B2-FREEZE。旧 SHA、旧 JAR、旧 sidecar 和旧授权草案均不得沿用；真实三次 Provider 预算仍未消耗。
