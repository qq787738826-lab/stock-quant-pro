# 阶段 2A 前置任务：智能体数据库集成测试隔离治理

## 1. 任务目标

本任务修复共享非空专用测试库下 Agent 数据库集成测试错误依赖“五张 Agent 表全局必须为空”的问题。修复仅涉及测试隔离和治理文档，不修改生产代码、Flyway、跨语言契约或数据库结构，也没有进入阶段 2A 的生产上下文实现。

## 2. 原问题

以下八个场景在业务断言之外，还会在测试开始或清理结束后断言 `agent_tasks`、`agent_runs`、`agent_evidence`、`agent_vetoes`、`agent_decisions` 全表计数均为零：

- `AgentEvidenceVetoPostgresIntegrationTest`
  - `persistsSharedEvidenceWithoutFormalVetoThroughTheProductionFlow`
  - `persistsPositionRiskFormalVetoAndMapsItsLogicalIdToPhysicalIds`
- `AgentInvalidResponsePostgresIntegrationTest#rejectsInvalidResponseWithoutPartialPersistence`
  - 悬空 finding 证据引用
  - 非 POSITION_RISK 正式否决
  - finalDecision 缺少 sourceRunId
  - finalDecision.vetoed 与正式 veto 不一致
  - score 越界
  - malformed JSON

这些全表断言会把本任务开始前已经存在的其他验收任务误判为当前测试泄漏。失败不代表当前测试创建的数据未被清理，也不代表生产链路回归。

## 3. 修复后的任务范围不变量

两类测试继续保留原有生产链路和契约断言，并改为只验证本测试创建的 `taskId`：

1. 创建后，按该 `taskId`验证 task、六条 run、evidence、veto 和 decision 的准确数量与字段。
2. 非法响应仍必须证明该 `taskId`下 evidence、veto 和 decision 均为零，六条 run 全部安全失败。
3. 每个创建成功的任务立即登记到测试清理列表。
4. `@AfterEach`只执行带 `WHERE id = ?` 的 `DELETE FROM agent_tasks`，依赖 V5 已冻结的外键级联清理该任务的关联数据。
5. 删除后逐表按该 `taskId`断言五张表计数均为零。
6. 不再读取或断言五张表的全局总数。

## 4. 数据保护措施

- 专用测试数据库安全门保持不变，三个数据库环境变量缺一时在 Spring 上下文创建前跳过。
- 数据库身份和 Flyway V5 断言保持不变。
- 未使用 `TRUNCATE`、无 `WHERE` 的 `DELETE`或整表清理。
- 未修改本任务开始前的其他 task。
- 未降低 evidence、veto、decision、错误安全、幂等或原子失败断言。
- 测试前后五张 Agent 表全局计数均为：task/run/evidence/veto/decision = `2/12/0/0/2`。

## 5. 测试命令与结果

### 当前八项失败场景定向测试

```powershell
.\mvnw.cmd --% -o -pl quant-server -am -Dtest=AgentEvidenceVetoPostgresIntegrationTest,AgentInvalidResponsePostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：8 项运行，0 失败，0 错误，0 跳过，BUILD SUCCESS。

### 带专用测试数据库变量的完整 Agent 测试

```powershell
.\mvnw.cmd --% -o -pl quant-server -am -Dtest=*Agent*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：138 项运行，0 失败，0 错误，1 项合理跳过，BUILD SUCCESS。测试前后既有五表计数保持 `2/12/0/0/2`。

### 独立无变量子 PowerShell 的完整 Agent 测试

在独立子 PowerShell 中移除：

- `STOCK_QUANT_TEST_DB_URL`
- `STOCK_QUANT_TEST_DB_USERNAME`
- `STOCK_QUANT_TEST_DB_PASSWORD`
- `STOCK_QUANT_PYTHON_BASE_URL`

然后运行同一离线 Agent 测试命令。结果：133 项运行，0 失败，0 错误，8 项跳过，BUILD SUCCESS。数据库和真实 Python 集成测试在 Spring 上下文创建前跳过，日志未出现 Hikari、Flyway、Python 或 Uvicorn 启动。

首次受限执行因进程无法读取用户目录中的现有 Maven 离线缓存而在项目加载前退出，未进入测试；允许读取同一既有离线缓存后使用相同无变量子进程命令重跑并通过，未访问外部仓库。

### 独立无变量子 PowerShell 的 quant-server 全量测试

```powershell
.\mvnw.cmd -o -pl quant-server -am test
```

结果：quant-core 运行 1 项，0 失败；quant-server 运行 134 项，0 失败，8 项跳过，BUILD SUCCESS。未启动 Hikari、Flyway、Python 或 Uvicorn。

## 6. 数据保持结果

| 阶段 | agent_tasks | agent_runs | agent_evidence | agent_vetoes | agent_decisions |
|---|---:|---:|---:|---:|---:|
| 修复测试前 | 2 | 12 | 0 | 0 | 2 |
| 定向及完整带库测试后 | 2 | 12 | 0 | 0 | 2 |

测试运行期间会创建临时 Agent 任务并由真实生产外键关系产生关联记录；每个测试只按自身 `taskId`精确删除，清理后逐表验证该任务无残留。既有验收数据未被删除或修改。

## 7. 结论与阶段边界

共享非空专用测试库下的八项全表零假设失败已经消除。Agent 数据库集成测试现在验证任务级提交、失败原子性和清理结果；无数据库变量时仍保持上下文创建前安全跳过。

本任务没有实现 `security`、`marketData`、`technicalMetrics` 或 `dataQualityContext`，阶段 2A 生产实现尚未开始。
