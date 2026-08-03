# 3A-R3B-F1F-B2-RUNNER 阶段记录：专用执行入口与冻结安全门修复

## 阶段结论

F1F-B1 双提交链已经验收并合入 `e3777602fadd65f3af0a2ba8ac6e886693d745d5`。随后 B2-PRE 审计结论为 `NOT_READY`：该提交缺少可正式启动的一次性最小进程，输出审计没有覆盖秘密与数据库初始化前的最早边界，治理 V14 仍依赖自动 baseline 语义，构建脚本没有默认非正式模式且使用环境 Maven。

本任务在同一冻结基线上完成最小修复。代码与文档在任务分支完成后仍待 ChatGPT 基于实际 Git 提交复验，尚未合入。`e3777602...` 只保留为开发父提交，不再作为未来真实验收冻结 SHA。

## 实现结果

- 新增普通 Java `main` 一次性 runner，使用不可由 `loader.main` 覆盖的 Spring Boot `JarLauncher` 仅作可执行 JAR 类加载，不创建 Spring ApplicationContext；组件以显式白名单手工构造，Jackson 只显式注册 `JavaTimeModule`，不触发模块自动发现。
- 输出捕获与 Logback 隔离在所有元数据、秘密、DataSource、Flyway 和 Provider 客户端之前安装。秘密仅由 `System.console().readPassword` 通道读取；不可用时直接非零退出。
- 专用非池化 DataSource 不把密码放入参数、系统属性或 JDBC URL；关闭时清零内部字符数组。
- 执行器拆为“Provider/回读后 pending”与“输出审计通过后 PASSED”两步；Provider 组件及 Token 持有者在最终 flush/审计前关闭，捕获拓扑在审计计算后才恢复；脏输出、关闭期泄漏或捕获不完整不能持久化 `PASSED`。
- V14 使用 `baselineOnMigrate(false)`。构建证明、授权、数据库身份、search path、精确 V1—V13 成功链和主历史失败数 0 先验证；只有治理 history 不存在且无未跟踪治理对象时才显式 `BASELINE/13`，再迁移精确 `SQL/V14`。部分、未知、失败或元数据伪造的历史均拒绝。
- 构建脚本默认 `PREPARATION_ONLY`，正式模式必须显式指定；两种模式均使用 Maven Wrapper `3.9.16`。PREPARATION 的 MANIFEST/sidecar 即使改名也不能取得治理资格。
- 构建从已核验 commit 的 `git archive` 在系统临时目录离线执行，不对共享工作区运行 Maven `clean`。只发布专用 `quant-server-1.3.1-f1f-b2-runner.jar` 与相邻 sidecar；MANIFEST 直接冻结 `JarLauncher`/Runner `Start-Class`，启动只用 `java -jar`，不接受 `loader.main` 覆盖。实际 `java.version` 同时绑定 MANIFEST、sidecar、Maven 构建 JVM 与运行时；隔离源码和构建目录按经校验的系统临时目录边界清理，不读取或打包 `.ai/`。

## 验证结果

- 合并前最终安全审查发现并修复：自动 Jackson 模块发现、Provider 组件晚于最终审计关闭、Logback 恢复早于最终扫描、治理 history 只核对版本、主 history 未单独拒绝失败项、共享工作区构建、可由 `loader.main` 覆盖的启动器以及 JAR/Java 运行时绑定不足。修复未扩大 22 文件累计范围。
- 专用 runner 与冻结构建证明定向测试：`32 / 0 / 0 / 0`（Tests / Failures / Errors / Skipped）。
- F1A—F1F-B2、Provider V2 与 QFQ 联合离线回归：`201 / 0 / 0 / 0`。
- QFQ 权威引擎：`19 / 0 / 0 / 0`，其中既有 18 个黄金向量保持不变。
- `quant-core` 全量：`4 / 0 / 0 / 0`。
- `quant-server` 安全离线全量：`630 / 0 / 0 / 117`。相对审查前 `621 / 0 / 0 / 112` 增加 9 项，其中新增的 5 个 Skipped 全部来自扩展后的 PostgreSQL 条件测试；核心 PostgreSQL 套件另在临时实例执行为 `10 / 0 / 0 / 0`，没有以跳过替代数据库断言。
- PostgreSQL `16.13` 全新临时实例验证：数据库 `stock_quant_research`、Schema `tushare_research`；主 history 在治理时精确 V1—V13，治理 history 精确 BASELINE/13 与 SQL/V14。错误数据库、错误用户、public、含 public 的 search path、主 history 缺失/失败/未来版本及治理 history 部分/未知/伪造全部拒绝；测试后端口、进程与目录残留为 0，未访问既有或正常业务数据库。
- Java `clean compile`、PowerShell 语法解析与 `git diff --check` 通过。
- 默认 `PREPARATION_ONLY` 构建预演从最终审查提交的隔离 `git archive` 完成：Maven Wrapper 为 `3.9.16`，专用 JAR、MANIFEST 与 sidecar 均绑定 PREPARATION 模式、实际 Java 运行时和最终 commit；生产来源解析为 `PREPARATION_ONLY` 且 `governanceEligible=false`。打包态无授权启动稳定非零退出，不启动 Spring、Web 或 scheduler。
- 预演 JAR、原始 JAR、sidecar、临时 MANIFEST 和依赖模块 JAR 已精确删除，相关构建产物残留为 0。
- 未运行 Live 测试或任何真实受控验收；临时 PostgreSQL 只用于本轮全新隔离治理验证。

## 安全边界

本阶段没有调用 Tushare/iFinD，没有读取 Token 或数据库密码，没有访问 PostgreSQL，没有执行 V13/V14，没有创建或消费 acceptance ID，也没有执行 `CONTROLLED_BUILD_ARTIFACT`。证券、日期、数据库运行参数和正式授权仍未签发。

七项状态保持：

```text
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

完整 F1 十项技术阻断不变。合入后唯一允许的下一动作是基于新的冻结集成 SHA 重新进行完整 B2-PRE；不得直接执行真实受控验收。
