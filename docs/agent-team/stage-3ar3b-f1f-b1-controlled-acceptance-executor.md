# 3A-R3B-F1F-B1 阶段记录：受控验收可信执行机制

## 结论

F1F-A 已以双提交链合入集成分支 `f68d84403ebb82babe92a1cb0f78d845ed39547a`。F1F-B1 在任务分支完成了持久化唯一消费、可信构建证明、输出审计、数据库回读、内部资格重验和崩溃恢复机制；待 ChatGPT 基于实际 Git 提交复验，尚未合入。

本阶段没有执行 F1F-B2，没有真实 Provider 调用，也没有生成治理认可的真实 `PASSED`。当前仍为：

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

- 独立 `db/controlled-acceptance/V14` 新增 append-only 状态和转换历史；正常应用 Flyway location 不包含它。
- 数据库主键和原子 `INSERT ... ON CONFLICT` 使跨线程、跨 JVM、跨进程同 ID 只有一次预占成功。
- SQL trigger 固定转换白名单及不可变授权范围，禁止失败/终态回退和删除审计记录。
- `RUNNING` 使用独立事务在 Provider 之前持久化；恢复器将遗留的非终态记录终结为 `INTERRUPTED`。
- 受控构建脚本验证完整 SHA 和干净已跟踪工作区，计算实际 JAR SHA-256 后生成不含路径、用户或凭据的 sidecar。
- 输出审计捕获 stdout/stderr、Logback 事件和嵌套异常，识别原文、前后缀、编码/哈希、Authorization/Bearer、JDBC 认证参数和 Provider payload 形态。
- V13 三事实由数据库实际回读，批次、observation ID、类型数量、SYSTEM_KNOWLEDGE 时间和同连接 PID 均需匹配。
- `PASSED` 不从数据库字符串直接投影；加载后必须验证证据 SHA-256、完整转换链、构建来源、执行来源和全部安全不变量。
- `TEST` 来源即使完整成功也只形成候选，不能打开 operational ready。

## 验证

- Java 编译通过。
- 可信机制定向单元测试：`8 / 0 / 0 / 0`。
- F1F-A/F1E/Tushare/Provider/QFQ 联合离线回归：`163 / 0 / 0 / 1`；唯一 skipped 为需显式临时库环境的 B1 PostgreSQL 类。
- QFQ 权威引擎包含在联合回归中：`19 / 0 / 0 / 0`，18 个黄金向量未改变。
- 新建 PostgreSQL 16.13 临时实例，V1—V14 全部迁移成功；B1 PostgreSQL：`1 / 0 / 0 / 0`，验证唯一预占、并发争抢、恢复、非法回退及迁移集合。
- 临时实例端口已停止，随机目录已删除；未访问任何既有数据库。

## 未改变事项

完整 F1 十项技术阻断、正常业务库、生产运行、scheduler、Agent、回测、Shadow、Day 002、F2B、F3、3A-R3B-1、3B 与交易均未启动。F1F-B2 必须在本阶段审查合入后的冻结集成 SHA 上重新生成授权和构建证明。
