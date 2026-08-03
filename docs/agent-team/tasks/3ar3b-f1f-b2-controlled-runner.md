# 3A-R3B-F1F-B2-RUNNER：专用执行入口与冻结安全门修复

## 基线与目标

- 集成基线：`e3777602fadd65f3af0a2ba8ac6e886693d745d5`。
- 任务分支：`codex/1.4.0-stage-3ar3b-f1f-b2-controlled-runner`。
- B2-PRE 结论：`NOT_READY`。
- 本任务只补齐最小专用进程、输出审计时序、显式治理 bootstrap、构建双模式和 Maven Wrapper 证明；不执行真实受控验收。

## 冻结实现合同

1. `TushareControlledAcceptanceRunner` 是唯一专用一次性入口；它不启动普通 Spring Boot、Web、Controller、scheduler、Agent、回测、Shadow 或交易入口。
2. 组件通过 `TushareControlledAcceptanceComponents` 手工白名单装配；Jackson 只显式注册 `JavaTimeModule`，不进行组件扫描、`findAndRegisterModules` 或 ServiceLoader 自动发现。
3. stdout/stderr 与 Logback 拓扑先于构建证明、授权、秘密和 DataSource 安装；数据库密码和 Token 只从安全控制台通道读取为可清除字符数组，读取后立即登记审计变体。Provider 组件和 Token 持有者必须在最终 flush/审计前关闭，审计完成后才允许持久化 `PASSED`。
4. 治理 V14 固定 `baselineOnMigrate(false)`；数据库身份、search path、无失败记录的精确主历史 V1—V13、构建证明与授权先于治理操作验证。治理历史首次不存在时，只能显式 `BASELINE/13` 后迁移精确 `SQL/V14`；伪造、未知、部分或失败历史全部拒绝。
5. 构建模式只有 `PREPARATION_ONLY` 和 `CONTROLLED_BUILD_ARTIFACT`，默认前者。PREPARATION 证明不能取得治理资格或形成真实 `PASSED`。
6. 构建只使用仓库 Maven Wrapper `3.9.16`；从已核验 commit 的 `git archive` 在系统临时目录离线构建，不清理或重建共享工作区。专用 `quant-server-1.3.1-f1f-b2-runner.jar` 的 MANIFEST 与相邻 sidecar 同时绑定本地/远程 SHA、分支、实际 `java.version`、Wrapper、模块、executor、rule、模式、时间和 JAR SHA-256；Maven 构建 JVM 与启动 JVM 必须同版本。MANIFEST 直接冻结不可由 `loader.main` 覆盖的 `JarLauncher` 与 Runner `Start-Class`，运行时只接受单一真实 JAR classpath，启动脚本只允许 `java -jar`。
7. 本任务不签发证券、日期、数据库端口、SSL、owner、权限 DDL、保留策略、acceptance ID、到期时间或正式 JAR 摘要；这些仍是 `PROVISIONAL/DRAFT_NOT_AUTHORIZED`。

## 状态与禁止项

```text
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

本任务 Provider 调用、数据库连接、V13/V14 执行和真实秘密读取均为 0。不得执行 `CONTROLLED_BUILD_ARTIFACT`、F1F-B2、scheduler、Agent、回测、Shadow、Day 002、F2B、F3、3A-R3B-1、3B 或交易。

## 验收

- 专用 runner 与构建证明定向测试全部离线通过。
- 错误数据库、用户、Schema、search path，缺失/失败/未来主历史，以及部分、未知或伪造治理历史时，治理 DDL 与 Provider 调用均为 0。
- 敏感输出、缺构建证明、缺授权或缺安全秘密通道均 fail closed。
- PREPARATION 演练只产生专用非治理 JAR 与 sidecar；隔离源码、依赖构建目录和临时文件在验证后精确删除，直接运行 JAR 不会回退到普通 Spring Boot 入口。
- 合入后必须基于新的集成 SHA 重新执行完整 B2-PRE；本任务不得直接进入真实 B2。
