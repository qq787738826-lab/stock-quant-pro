# 阶段 1D-4 验收记录

## 1. 阶段目标与基线

本阶段验收智能体团队 Vue 工作台、本地安全启停和 PostgreSQL → Java → Python → Vue 真实闭环，并建立后续开发的状态治理基线。验收分支为 `codex/1.4.0-1d4-acceptance`，基线为 `5bc492a feat(agent): add safe local team runtime scripts`。本阶段承接 1D-3 已冻结的证据、评分、正式否决和原子持久化契约，不改变契约，也不实现真实股票分析。

## 2. 前端工作台功能矩阵

| 能力 | 验收结果 |
|---|---|
| 创建参数 | symbol 六位数字、tradeDate 必填、ruleVersion 非空且最长 64；仅 LOCAL_RULES/MANUAL，支持 forceRefresh |
| 创建与 URL | 使用 Java 返回的 task ID，`router.replace` 写入 `?taskId=` |
| URL 恢复 | 只接受安全正整数；空值不请求，非法值安全报错 |
| 轮询 | 每 2 秒读取 task/runs；单 timer、tick 防重入、请求 generation 隔离 |
| 失败控制 | 连续三次轮询失败停止，保留手动重新加载入口 |
| 终态加载 | COMPLETED/PARTIAL/FAILED/CANCELLED 后并行读取 evidence/decision/vetoes |
| 手动重新加载 | 作废旧请求，保留 task ID，按当前状态恢复轮询或加载终态结果 |
| 生命周期 | 切换任务和卸载时停止旧轮询；旧响应不能覆盖新任务 |
| 六智能体 | 固定顺序展示；不创建或展示 CHIEF_DECISION run |
| evidence | 按 evidenceId 保留第一条，重复 ID 发出非阻断警告，fields 受控展开 |
| veto | 只展示 `/vetoes` 的真实记录；DATA_QUALITY BLOCKED 不进入正式否决区 |
| finalDecision | 独立展示 decision、gateStatus、vetoed、sourceRunIds、vetoIds 等真实字段 |
| 视觉语义 | DATA_QUALITY BLOCKED、POSITION_RISK 风险、FAILED 与正常状态区分 |
| 安全 | 不包含运行时 mock，不显示完整响应、contextSnapshot、堆栈或数据库信息 |

当前 quant-web 没有单元测试框架或测试脚本。本阶段没有新增依赖或搭建大规模测试体系；以上由代码审计、TypeScript 编译和生产构建覆盖。该边界不阻断 1D-4 验收，但后续前端测试基础设施应作为独立工程任务评估。

## 3. 流程验收

创建成功后页面立即读取 task/runs 并启动轮询。URL watcher 使用 immediate 恢复首次任务；切换、删除或非法 query 会作废旧请求并清空状态。终态只清除 timer，不作废本代请求，因此 evidence、decision、vetoes 可以正常落入页面。手动重新加载创建新 generation。六智能体固定排序为 DATA_QUALITY、MARKET_REGIME、TECHNICAL_ANALYSIS、STRATEGY_BACKTEST、ANNOUNCEMENT_RISK、POSITION_RISK。

DATA_QUALITY 的 `BLOCKED` 显示为“数据质量阻断（非正式否决）”。POSITION_RISK 的 run 在 `veto=true` 时表示该智能体声明正式否决；正式否决的证据、原因及持久化结果通过 vetoes API 查询，并且必须与 run 输出和 finalDecision 保持一致。总控独立显示，不作为第七个 run。

## 4. API 接口矩阵

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/api/agent-tasks` | 创建或命中任务 |
| GET | `/api/agent-tasks/{taskId}` | 查询任务 |
| GET | `/api/agent-tasks/{taskId}/runs` | 查询六个 run |
| GET | `/api/agent-tasks/{taskId}/evidence` | 查询证据 |
| GET | `/api/agent-tasks/{taskId}/decision` | 查询总控决策 |
| GET | `/api/agent-tasks/{taskId}/vetoes` | 查询正式否决 |
| GET | `/api/agent-tasks/history` | 分页历史 |

前端复用同一 Axios 实例和统一 `data` 解包；浏览器使用 `/api`，Electron file 模式使用 Java 回环地址，不调用 Python 端口。

## 5. Java → Python → PostgreSQL 真实闭环

本次通过 Vue 代理创建一条 symbol=600000、LOCAL_RULES、MANUAL、forceRefresh=true 的临时验收任务。任务进入 PARTIAL；六个 run 均为 INSUFFICIENT_DATA，无 CHIEF_DECISION。DATA_QUALITY 为 BLOCKED/REJECT/veto=false，其他五个为 NOT_APPLICABLE。evidence=0，vetoes=0，decision 为 BLOCKED_BY_DATA_QUALITY、vetoed=false、sourceRunIds=6、vetoIds=0。

数据库对该临时任务核对为 task/run/evidence/veto/decision = 1/6/0/0/1。五个查询接口二次读取与首次结果一致，证明 URL 刷新恢复所需数据完备。验收后仅按该临时 task ID 精确删除，级联结果为 0/0/0/0/0；本任务开始前已存在的验收数据及其五表计数保持不变。

## 6. 本地启动与停止脚本

首次启动在 8001、8080、5173 空闲且专用测试库身份通过后启动 Python、Java、Vue；Python、Java、Vue 首页和 Vue 代理四项健康检查均为 HTTP 200。第二次启动验证可信 state，六个根/监听 PID 均未变化且不重新构建。

脚本拒绝接管未知端口；state 绑定仓库、runId、固定日志路径、PID、启动时间、进程名、监听端口和祖先关系。停止先为三个服务完整生成并复核计划，再按 Vue、Java、Python 顺序按进程树深度停止。首次停止释放三个端口并严格删除 state；第二次停止返回“无托管服务”，不扫描或结束未知进程。

敏感环境变量通过大小写不敏感集合隔离。Python、Maven、Vue 不继承 DB/SPRING/AGENT_TEAM、模型、行情、交易、Token/Secret 等变量；Java 先清空隔离集合，再仅设置专用测试库和智能体服务所需变量。密码不进入命令行、state 或仓库日志。运行日志和 state 位于系统临时目录；成功停止保留日志目录，不在仓库产生临时文件。

## 7. 测试命令与结果

| 范围 | 实际命令 | 结果 |
|---|---|---|
| Java 智能体（带测试库变量） | `.\mvnw.cmd --% -o -pl quant-server -am -Dtest=*Agent*Test -Dsurefire.failIfNoSpecifiedTests=false test` | 138 项；129 通过、8 失败、1 跳过。失败均源于测试库预存 decision 与测试的全表零假设冲突 |
| Java 定向 | `.\mvnw.cmd --% -o -pl quant-server -am -Dtest=AgentContextHashServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` | 4/4 通过 |
| Java 智能体（独立无变量子进程） | 移除四个测试变量后运行同一 Agent 测试命令 | 运行 133 项；8 跳过、0 失败 |
| quant-server 全量（独立无变量子进程） | 移除四个测试变量后运行 `.\mvnw.cmd -o -pl quant-server -am test` | quant-core 运行 1 项、0 失败；quant-server 运行 134 项、8 跳过、0 失败 |
| Python 编译 | `quant-ai\.venv\Scripts\python.exe -m compileall -q app tests` | 通过 |
| Python 全量 | `quant-ai\.venv\Scripts\python.exe -m unittest discover -s tests -p "test_*.py" -v` | 33/33 通过 |
| 前端构建 | 在 quant-web 运行 `npm run build` | vue-tsc 与 Vite build 通过 |
| 真实 Python 冒烟 | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-agent-python-smoke.ps1` | Python 33 项及 Java 真实 PostgreSQL 测试通过；POST=1、HTTP 200=1、无 Python 残留 |
| 本地闭环 | `start-agent-team-local.ps1 -NonInteractive`、Vue 代理任务请求、`stop-agent-team-local.ps1` | 四项健康、任务、持久化、刷新恢复和精确停止均通过 |

无变量子进程未观察到 Hikari、Flyway 或 Python/Uvicorn 启动；数据库和真实 Python 集成测试在 Spring 上下文创建前安全跳过，父 PowerShell 环境未改变。

## 8. 未执行、失败与警告

没有运行浏览器自动化：仓库没有现成浏览器测试体系，本阶段不安装 Playwright/Selenium；真实 API、Vue 代理、生产构建和刷新所需五接口已验证，因此不阻断基础闭环验收，视觉交互仍建议人工浏览器复核。

带数据库变量的 Java 智能体整组没有全绿：专用测试库保留既有验收数据，而 1D-3 相关的若干数据库集成测试在清理自身 task 后断言五张业务表全局为零。本阶段遵守禁止删除既有业务数据，没有清空测试库，也没有弱化断言。真实冒烟和本轮精确任务闭环均通过，因此判断为测试隔离/环境前置假设问题；建议后续将最终清理断言限定到测试创建的 task，另行审核。

已知非阻断警告：Flyway 9.22.3 对 PostgreSQL 16.13 的版本提示；Starlette TestClient/httpx 弃用提示；Vite 大 chunk 提示和依赖内 PURE 注释提示。

## 9. 未接入能力与结论

九类 contextSnapshot 仍全部不可用，六个智能体仍返回 INSUFFICIENT_DATA，ChiefDecisionService 仍固定 BLOCKED_BY_DATA_QUALITY。本阶段没有为智能体团队新增接入实时行情查询、新 AKShare 查询、公告上下文、持仓上下文、LLM、付费 API、真实评分、策略扩张或交易写操作。

因此，1D-4 的工作台、本地运行和基础设施闭环验收通过。共享非空测试库下仍有 8 项数据库集成测试因“全表必须为空”的环境前置假设失败，已确认不属于本次生产链路回归，但测试隔离治理尚未完成，应作为后续独立工程任务处理。本结论不代表真实股票分析能力已经完成。

下一阶段唯一入口是：**阶段2A：仅从现有 PostgreSQL 接入第一批只读上下文**，范围仅为 security、marketData、technicalMetrics、dataQualityContext。阶段2A 尚未开始。
