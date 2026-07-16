# Stock Quant Pro 1.4.0 阶段 1D-3 验收记录

## 1. 阶段目标与基线

阶段 1D-3 用于收口股票智能体团队的跨语言结果契约，验证合法证据与正式否决的 PostgreSQL 持久化，以及非法响应失败时的事务原子性和安全错误闭环。本阶段不新增真实投资分析能力。

验收分支为 `feature/1.4.0-agent-team`，基线提交依次为：

- `256b387 feat(agent): enforce cross-language result consistency`
- `d9f5064 test(agent): verify evidence and veto persistence`
- `c8007f5 test(agent): verify invalid response failure atomicity`

## 2. 权威边界

- Java 是 `taskId`、六个 `runId`、任务状态、缓存判断和持久化的唯一权威。
- Python 保持无状态，只分析 Java 提供的只读 `contextSnapshot`，不访问智能体任务数据库。
- 固定六个专业智能体为 `DATA_QUALITY`、`MARKET_REGIME`、`TECHNICAL_ANALYSIS`、`STRATEGY_BACKTEST`、`ANNOUNCEMENT_RISK` 和 `POSITION_RISK`。
- 总控决策不是第七个 `agent_run`，只保存到 `agent_decisions`。
- `DATA_QUALITY` 使用 `gateStatus=BLOCKED` 表示数据门禁阻断，但 `veto` 必须为 `false`，不能产生正式否决。
- 只有 `POSITION_RISK` 可以产生正式否决；总控不能解除或忽略该否决。

## 3. 契约基线

### 3.1 评分和置信度

- `score` 和 `confidence` 均为 0 到 100 的整数，边界值 0 和 100 合法。
- `COMPLETED`、`PARTIAL` 和 `INSUFFICIENT_DATA` 结果必须包含评分、置信度和非空摘要。

### 3.2 证据完整性

- 顶层 `evidence` 是 Java 持久化的权威证据集合，`evidenceId` 非空且唯一。
- finding 和正式否决的 `evidenceIds` 必须非空、无重复，并引用顶层实际存在的证据。
- 单智能体 evidence 必须是顶层证据的完整一致子集，包括 `collectedAt` 在内的字段必须一致。
- evidence payload 必须是 JSON 对象，`contentHash`、观察时间和采集时间必须符合冻结契约。
- 同一共享证据只持久化一次，逻辑 `evidenceId` 保存为 `evidence_key`。

### 3.3 正式否决与最终决策

- 非 `POSITION_RISK` 智能体的 `veto` 必须为 `false`，也不能拥有顶层正式 veto。
- `POSITION_RISK` 正式否决要求 `decision=REJECT`、合法终态、`veto=true`，并至少引用一条真实证据。
- `finalDecision.sourceRunIds` 必须无重复且恰好覆盖六个 Java 物理 run ID。
- `finalDecision.vetoIds` 必须无重复且完整覆盖顶层逻辑 veto ID。
- 存在正式 veto 时，最终决策必须为 `REJECTED_BY_VETO` 且 `vetoed=true`。
- 不存在正式 veto 时，`vetoed=false`、`vetoIds=[]`，且不能返回 `REJECTED_BY_VETO`。
- DATA_QUALITY 阻断且无正式 veto 时，最终决策为 `BLOCKED_BY_DATA_QUALITY`，最终 `gateStatus=BLOCKED`。

## 4. 验收矩阵结果

| 验收域 | 场景 | 结果 |
| --- | --- | --- |
| 跨语言合法响应 | 原无数据响应 | Java、Python 均通过；DATA_QUALITY 阻断、无正式 veto、最终为 `BLOCKED_BY_DATA_QUALITY` |
| 跨语言合法响应 | 有证据、无否决 | Java、Python 均通过；证据唯一、引用完整、共享证据不重复、`vetoed=false` |
| 跨语言合法响应 | POSITION_RISK 正式否决 | Java、Python 均通过；正式 veto 引用真实证据，最终为 `REJECTED_BY_VETO` |
| 跨语言非法响应 | 证据、veto、sourceRunIds、最终决策及评分边界异常 | Java、Python 均按冻结规则拒绝 |
| PostgreSQL 合法持久化 | evidence 无否决 | 逻辑 evidence ID、时间、完整 payload、六个物理 run ID 和最终决策精确保存 |
| PostgreSQL 合法持久化 | POSITION_RISK 正式否决 | veto 使用数据库物理 BIGINT 主键；`evidence_ids` 保留逻辑 evidence ID；decision 的 `veto_ids` 保存物理主键；`decision_json` 保留逻辑 veto ID |
| PostgreSQL 非法响应 | 五种语义错误与 malformed JSON | task 与六条 run 全部 FAILED；evidence、veto、decision 均为 0；无部分成功结果 |
| 真实服务冒烟 | Java → FastAPI → PostgreSQL | 单次 POST、单次 HTTP 200；Python 环境隔离；进程树正常关闭；持久化和清理通过 |
| 无变量兼容性 | 普通开发/CI 环境 | 数据库及真实 Python 集成测试在 Spring 上下文创建前安全跳过，其他测试通过 |

非法契约覆盖包括：悬空或重复 evidence 引用、重复 evidence ID、非 POSITION_RISK veto、POSITION_RISK 输出与正式 veto 不一致、DATA_QUALITY 正式 veto、sourceRunIds 缺失/重复/未知、vetoIds 缺失/未知、最终 `vetoed` 不一致、score/confidence 越界、evidence 子集 `collectedAt` 冲突、DATA_QUALITY 阻断但最终 gateStatus 不一致，以及 malformed JSON。

## 5. PostgreSQL 持久化与失败原子性

合法结果验证结论：

- `evidence_key` 保存跨语言逻辑 evidence ID，共享证据只保存一次。
- evidence 的时间和完整 `payload_json` 精确往返。
- `agent_vetoes.id` 由 PostgreSQL 生成物理 BIGINT 主键。
- `agent_vetoes.evidence_ids` 保存逻辑 evidence ID。
- `agent_decisions.veto_ids` 保存正式 veto 的物理 BIGINT 主键。
- `decision_json` 保留 Python 返回的逻辑 veto ID。
- `source_run_ids` 恰好保存六个 Java 物理 run ID。
- 不存在 `CHIEF_DECISION` run，重复创建不会重复插入结果。

非法结果验证结论：

- 反序列化失败或 Java 校验失败后，任务和六条 run 均进入 `FAILED`。
- evidence、veto 和 decision 均不持久化，不存在部分成功 run。
- FAILED 任务重复执行不会发起第二次 HTTP 调用。
- 数据库错误信息不包含响应正文、`contextSnapshot`、堆栈或测试泄漏标记。

## 6. 真实 Python 服务冒烟

- 使用真实 FastAPI 入口、真实 AgentTeamOrchestrator、六个专业智能体骨架和独立总控服务。
- Java 使用生产 HTTP 客户端调用本地回环服务。
- `/agents/team/analyze` POST 总次数为 1，HTTP 200 次数为 1。
- Python 子进程看不到数据库、模型、行情或交易相关敏感环境变量。
- Uvicorn 进程树在测试结束后完成清理，无进程残留。
- 默认无数据响应保持不变，最终 task 状态为 `PARTIAL`。

## 7. 测试命令与结果

除真实 Python 冒烟脚本内部既有 Maven 调用外，本次单独执行的 Java 验收命令均使用 `-o` 离线模式。真实 Python 冒烟脚本内部使用项目 Maven Wrapper，当前脚本没有显式传入 `-o`；本阶段未修改该脚本，本次验收日志未观察到成功的外部依赖下载。

### 7.1 Python agent_team 契约测试

从 `quant-ai` 工作目录执行：

```powershell
Push-Location .\quant-ai
.\.venv\Scripts\python.exe -m unittest discover -s tests/agent_team -p "test_*.py" -v
Pop-Location
```

结果：33 通过，0 失败，0 错误。

### 7.2 Java 跨语言与一致性测试

```powershell
.\mvnw.cmd --% -o -pl quant-server -am -Dtest=AgentResponseValidatorTest,AgentCrossLanguageContractTest,AgentResultConsistencyContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：40 通过，0 失败，0 错误。

### 7.3 PostgreSQL 合法与非法闭环

```powershell
.\mvnw.cmd --% -o -pl quant-server -am -Dtest=AgentEvidenceVetoPostgresIntegrationTest,AgentInvalidResponsePostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：8 通过，0 失败，0 错误。

### 7.4 真实 Python 冒烟

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-agent-python-smoke.ps1
```

结果：Java 冒烟 1 通过；脚本内 Python agent_team 33 通过；POST=1，HTTP 200=1。脚本内部使用项目 Maven Wrapper，但没有显式传入 `-o`；验收未修改脚本，也未观察到成功的外部依赖下载。

### 7.5 Java 全部智能体测试

```powershell
.\mvnw.cmd --% -o -pl quant-server -am -Dtest=*Agent*Test -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：132 项，0 失败，0 错误，1 跳过。

### 7.6 quant-server 全量测试

```powershell
.\mvnw.cmd --% -o -pl quant-server -am test
```

结果：quant-server 133 项，0 失败，0 错误，1 跳过；quant-core 1 项通过。

### 7.7 Python 全量测试

从 `quant-ai` 工作目录执行：

```powershell
Push-Location .\quant-ai
.\.venv\Scripts\python.exe -m compileall -q app tests
.\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py" -v
Pop-Location
```

结果：编译检查通过；33 通过，0 失败，0 错误。

### 7.8 无变量全量测试

在独立子 PowerShell 中移除四个测试变量，再执行离线全量测试；该过程不改变父 PowerShell 环境：

```powershell
$childScript = @'
Remove-Item Env:STOCK_QUANT_TEST_DB_URL -ErrorAction SilentlyContinue
Remove-Item Env:STOCK_QUANT_TEST_DB_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:STOCK_QUANT_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:STOCK_QUANT_PYTHON_BASE_URL -ErrorAction SilentlyContinue

& .\mvnw.cmd -o -pl quant-server -am test
exit $LASTEXITCODE
'@

powershell -NoProfile -ExecutionPolicy Bypass -Command $childScript
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
```

结果：quant-server 128 项，0 失败，0 错误，8 跳过；BUILD SUCCESS；未启动 Hikari、Flyway 或 Python。

## 8. 数据库清理与已知警告

验收结束后专用测试库中五张智能体业务表记录数均为 0：

```text
agent_tasks=0
agent_runs=0
agent_evidence=0
agent_vetoes=0
agent_decisions=0
```

非阻断警告：

- 当前 Flyway Community Edition 9.22.3 提示其正式测试支持上限为 PostgreSQL 15，而专用测试库为 PostgreSQL 16.13。V1 至 V5 校验成功，当前 schema 已处于 V5；后续应评估升级 Flyway。
- FastAPI TestClient 输出 Starlette/httpx 兼容性弃用警告；当前测试通过，后续依赖升级时应统一处理。

## 9. 本阶段未接入范围

阶段 1D-3 未接入：

- 真实行情或 AKShare 实时查询
- 公告数据
- 真实持仓
- LLM 或付费模型 API
- 模拟或真实交易写操作

## 10. 验收结论与下一阶段

阶段 1D-3 验收通过。Java/Python 跨语言契约、合法结果持久化、正式否决映射、非法结果失败原子性、真实服务冒烟以及无变量安全兼容性均达到当前冻结基线，未发现生产代码或 Schema 阻断缺陷。

本记录完成后，项目继续推进至 1D-4 前端智能体团队工作台与本地运行闭环。后续验收状态、真实能力和下一阶段入口以 [CURRENT_STATE.md](CURRENT_STATE.md) 为准。
