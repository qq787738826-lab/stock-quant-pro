# 3A-R3B-F1F-B2-RUNNER 阶段记录：专用执行入口与冻结安全门修复

## 阶段结论

F1F-B1 双提交链已经验收并合入 `e3777602fadd65f3af0a2ba8ac6e886693d745d5`。随后 B2-PRE 审计结论为 `NOT_READY`：该提交缺少可正式启动的一次性最小进程，输出审计没有覆盖秘密与数据库初始化前的最早边界，治理 V14 仍依赖自动 baseline 语义，构建脚本没有默认非正式模式且使用环境 Maven。

本任务在同一冻结基线上完成最小修复。代码与文档在任务分支完成后仍待 ChatGPT 基于实际 Git 提交复验，尚未合入。`e3777602...` 只保留为开发父提交，不再作为未来真实验收冻结 SHA。

## 实现结果

- 新增普通 Java `main` 一次性 runner，使用 Spring Boot `PropertiesLauncher` 仅作可执行 JAR 类加载，不创建 Spring ApplicationContext；组件以显式白名单手工构造。
- 输出捕获与 Logback 隔离在所有元数据、秘密、DataSource、Flyway 和 Provider 客户端之前安装。秘密仅由 `System.console().readPassword` 通道读取；不可用时直接非零退出。
- 专用非池化 DataSource 不把密码放入参数、系统属性或 JDBC URL；关闭时清零内部字符数组。
- 执行器拆为“Provider/回读后 pending”与“输出审计通过后 PASSED”两步；脏输出或捕获不完整不能持久化 `PASSED`。
- V14 使用 `baselineOnMigrate(false)`。构建证明、授权、数据库身份、search path 和精确 V1—V13 主历史先验证；只有治理 history 不存在且无未跟踪治理对象时才显式 baseline 13，再迁移 V14。
- 构建脚本默认 `PREPARATION_ONLY`，正式模式必须显式指定；两种模式均使用 Maven Wrapper `3.9.16`。PREPARATION 的 MANIFEST/sidecar 即使改名也不能取得治理资格。
- 构建前后只精确处理目标 JAR、相邻 sidecar 和唯一临时文件；不使用递归清理，也不读取或打包 `.ai/`。

## 验证结果

- 专用 runner 与冻结构建证明定向测试：`28 / 0 / 0 / 0`（Tests / Failures / Errors / Skipped）。
- F1A—F1F-B2、Provider V2 与 QFQ 联合离线回归：`196 / 0 / 0 / 0`。
- QFQ 权威引擎：`19 / 0 / 0 / 0`，其中既有 18 个黄金向量保持不变。
- `quant-core` 全量：`4 / 0 / 0 / 0`。
- `quant-server` 安全离线全量：`621 / 0 / 0 / 112`；112 项均为需数据库、Python 或真实 Provider 的显式条件跳过，本阶段没有以跳过替代 B2-RUNNER 定向断言。
- Java `clean compile`、PowerShell 语法解析与 `git diff --check` 通过。
- 默认 `PREPARATION_ONLY` 构建预演通过：Maven Wrapper 为 `3.9.16`，JAR、MANIFEST 与 sidecar 均绑定 PREPARATION 模式，生产来源解析为 `PREPARATION_ONLY` 且 `governanceEligible=false`。首次预演发现并修复 Windows PowerShell 将 `java -version` 正常 stderr 误判为失败的问题；修复后定向回归仍为 `28 / 0 / 0 / 0`。
- 预演 JAR、原始 JAR、sidecar、临时 MANIFEST 和依赖模块 JAR 已精确删除，相关构建产物残留为 0。
- 按阶段禁令未运行 PostgreSQL 测试、Live 测试或任何真实受控验收。

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
