# 3A-R3B-F1F-B1 阶段记录：受控验收可信执行机制

## 结论

F1F-A 已以双提交链合入集成分支 `f68d84403ebb82babe92a1cb0f78d845ed39547a`。
F1F-B1 初始任务提交 `e0dfba061a1b2e335c2f0db9bc9efeac012d75c8` 在合并前完整审查中
发现 V14 隔离、状态机、构建证明、输出隔离、提交后回读、PASSED 重验和失败调用计数仍需
加固；修复提交 `e3777602fadd65f3af0a2ba8ac6e886693d745d5` 已通过实际 Git 最终复验，
经用户批准纯 fast-forward 合入集成分支。

本阶段没有执行 F1F-B2，没有真实 Provider 调用，也没有生成治理认可的真实 `PASSED`。
当前仍为：

```text
CONTROLLED_ACCEPTANCE_STATUS=NOT_RUN
F1_ENTRY_READINESS=BLOCKED_TECHNICAL_EVIDENCE
REDUCED_RESEARCH_OPERATIONAL_READY=false
FREE_PRODUCT_PREVIEW_GATE=PASS
FREE_PROVIDER_VALIDATION_GATE=BLOCKED
PAID_PROVIDER_UPGRADE_DECISION=PENDING
IFIND_TRIAL_ACTIVATION_GATE=BLOCKED
```

## 实现证据

- 默认 Flyway 主历史保持精确 V1—V13。独立 `db/controlled-acceptance/V14` 由专用守卫通过
  `flyway_controlled_acceptance_history` baseline 13 后显式加载；脚本内身份与历史校验使
  合并 location 的误扫描在任何治理 DDL 留存前失败并回滚，production loader 禁用 clean。
- V14 主键、CHECK、不可变触发器与转换历史把 `AUTHORIZED/RESERVED/RUNNING/
  SUCCEEDED_CANDIDATE/PASSED` 和失败终态冻结为单向状态机；Repository 转换使用独立事务
  和条件更新。恢复器只封存已预占的非终态执行，不消费 `AUTHORIZED`，并发恢复不会重试。
- `RUNNING` 在 Provider 前提交；共享 limiter 的全 Endpoint 总计数在边界前后取差，三次
  尝试和失败尝试均可审计，不能靠业务返回计数掩盖实际委托。
- 输出捕获包装后的异常按可信原因码重新分类为 Provider、QFQ、Persistence、Database Guard、
  Validation 或 Output Audit 终态；敏感材料未成功登记时不能误记为 Provider 失败。
- B1 当时的构建脚本只允许在冻结集成分支上生成证明，要求本地/远程集成 SHA 与期望提交一致，计算
  当前 executor JAR SHA-256，并把分支、SHA、产物摘要、Java/模块/执行器/规则版本同时写入
  MANIFEST 与相邻 sidecar。加载时再次计算实际 JAR 摘要并交叉核对；TEST proof 无治理资格。
- B1 执行器无 Spring/Controller/Runner/Scheduled 入口；静态边界证明同时拒绝组件注解、
  `@Bean` 和 Agent/回测/Shadow/交易等依赖。后续 B2-PRE 已确认仍需另行实现显式最小进程；
  该缺口由独立 F1F-B2-RUNNER 任务处理，不回写 B1 的历史实现结论。
- 输出审计在敏感材料 supplier 执行前安装，独占替换并最终恢复全部当前 Logback logger/
  appender，包括 non-additive 与 AsyncAppender；同时捕获 stdout/stderr、嵌套和 suppressed
  exception。空登记、原文/前后缀、编码/Hash、Authorization/Bearer、JDBC 认证参数或
  Provider payload 形态都 fail closed，只保存命中类别和位置。
- F1E 捕获先完整提交，再由无活动事务的回读服务重新验证数据库身份；回读同时核对 V13
  envelope 与 raw/factor/calendar typed 行，要求三个不同 observation ID、精确 1/1/1、
  证券/交易所/日期、开市 `REGULAR` 以及 firstObserved/known/observed 时间完全相等。写入
  PID 与回读 PID 分别留证，不把跨事务回读伪装成同连接证明。
- 候选证据 JSON 拒绝未知字段，状态历史要求精确顺序和时间一致；`PASSED` 必须先由内部
  evaluator 授权、数据库转换，再重新加载当前行、历史、证据摘要和构建证明重验。TEST
  始终只形成候选，重复或冲突 readback ID、错误事实数和证据篡改都会拒绝。

## 状态机与零写入证据

- 前置范围、构建、数据库和 durable authorization 任一失败都发生在 Provider 前。
- `RESERVED → RUNNING` 先独立提交；随后异常按实际尝试数写入对应失败终态。
- F1E 仍在一个捕获事务内执行全响应写前验证与 raw/factor/calendar 整批写入；失败不产生
  部分 batch/observation。B1 回读只在该事务已提交后运行，不参与或掩盖写入回滚。
- PostgreSQL 反例覆盖：默认 V1—V13 不出现治理表、合并 location 失败且主历史仍为 V1—V13、
  错误 public 目标在 DDL 前拒绝、并发唯一预占、恢复竞争、非法转换/篡改拒绝、活动事务
  回读拒绝、只有 envelope 而缺 typed facts 的回读拒绝。

## 验证

- Java clean compile：通过。
- B1 可信机制、执行顺序与失败终态分类定向：`21 / 0 / 0 / 0`。
- F1A—F1F-B1、Provider V2 与 QFQ 联合离线回归：`188 / 0 / 0 / 0`。
- QFQ 权威引擎：`19 / 0 / 0 / 0`，18 个黄金向量未改变。
- `quant-core` 全量：`4 / 0 / 0 / 0`。
- 安全关闭数据库、Python、Live 与 Token 环境门禁后的 `quant-server` 全量：
  `612 / 0 / 0 / 112`。112 项全部属于显式外部 PostgreSQL、Python、AKShare/Live 或 B1
  PostgreSQL 环境门禁，不能冒充真实闭环；B1 数据库类已另行真实运行。
- 新建 PostgreSQL 16.13 临时实例：B1 `5 / 0 / 0 / 0`、`Skipped=0`。主历史迁移 V1—V13，
  治理历史 baseline 13 后迁移 V14；覆盖迁移隔离、唯一预占/恢复、状态机/篡改和提交后
  typed fact 回读。临时实例、端口与目录均已清理，未访问任何既有数据库。
- `git diff --check`、Markdown 相对链接/表格、UTF-8、文件结尾、尾随空白、敏感信息与
  禁止范围检查均通过。

## 信任边界

SHA-256 sidecar 和证据 digest 是完整性核对，不是外部签名。能够同时控制 Git/JAR/sidecar
或完整改写数据库行、转换历史和证据的特权管理员超出本阶段威胁模型。输出审计也不覆盖
任意外部文件写入、未桥接日志框架或边界结束后继续运行的脱离线程。F1F-B2 因而必须使用
最小专用进程、专用最小权限数据库账号、输出白名单、无未等待后台任务和用户一次性授权；
这些限制不降低当前默认拒绝状态。

## 未改变事项

完整 F1 十项技术阻断、正常业务库、生产运行、scheduler、Agent、回测、Shadow、Day 002、
F2B、F3、3A-R3B-1、3B 与交易均未启动。F1F-B2 必须在本阶段审查合入后的冻结集成 SHA
上重新生成授权和构建证明，不能复用任务分支或 TEST 证明。

## 后续 B2-PRE 结论

F1F-B1 合入后，B2-PRE 对真实启动条件的进一步审计结论为 `NOT_READY`。它确认 B1 的持久化、
状态机、回读与证明基础继续有效，但 `e3777602...` 尚缺专用 launcher、覆盖秘密/数据源初始化
之前的审计时序、显式治理 bootstrap、默认 PREPARATION 构建模式与 Maven Wrapper 冻结。
这些是后续 F1F-B2-RUNNER 的新增安全门，不表示 B1 已验收证据失效，也不授权真实 B2。
