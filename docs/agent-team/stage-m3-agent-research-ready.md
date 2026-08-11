# M3_AGENT_RESEARCH_READY 阶段证据

## 当前结论

- 长期任务分支：`codex/1.4.0-m3-agent-research-ready`
- 冻结集成基线：`b041fbe807b817c4070781db33fbfca2d4f8bc4e`
- 当前状态：`BAILIAN_REAL_LLM_SMOKE_BLOCKED_EXTERNAL_BINDING`
- 外部门：`BAILIAN_REAL_LLM_SMOKE=BLOCKED_EXTERNAL_CREDENTIAL_ENDPOINT_BINDING`
- 在真实 LLM smoke 通过前，不声明 `M3_AGENT_RESEARCH_READY=PASS`，不执行最终合并。
- M3 新增 Tushare 调用 0；累计真实调用保持 55；百炼真实 HTTP 请求 2、完成模型调用 0；
  永久研究库写入 0。

## 交付契约

- `AGENT_RUNTIME_V1`：有界的 7 Agent 协作运行时，限制 `maxRounds`、`maxToolCalls`、
  `maxModelCalls` 和 timeout；Critic 最多触发一次返工。
- `AGENT_RESEARCH_TEAM_V1`：Research Coordinator、Data Analyst、Market Technical、
  Strategy Research、Risk、Portfolio、Critic Review 七个固定职责，共享同一运行时。
- `AGENT_TOOL_GATEWAY_V1`：固定白名单 `ResearchDataset`、`MarketTechnical`、
  `StrategyCompare`、`RiskMetrics`；行情、指标、回测和风险计算均由 M1/M2 确定性代码产生。
- `RESEARCH_REPORT_V1`：结构化任务、数据、策略实验、风险、组合、Evidence、工具调用、
  Agent finding、Critic、最终判断、指纹和成本字段；结论类型区分 FACT、INFERENCE、
  HYPOTHESIS、RECOMMENDATION 和 UNKNOWN。
- `AGENT_EVAL_V1`：固定对抗评测；报告文件原子写入 `target`，后端和现有页面只读展示，
  不能从 API 启动 Agent、Shadow 或交易。

## 模型边界

- `ModelAdapter` 与业务逻辑解耦；默认测试使用 `DETERMINISTIC_FAKE_MODEL_V1`。
- 正式真实适配器使用固定阿里云百炼 OpenAI-compatible Chat Completions endpoint、
  `response_format=json_object`、客户端严格结构校验、redirects NEVER 和固定模型 `qwen3.7-plus`；
  已完成的厂商无关 `ModelAdapter` 与 Responses 兼容实现继续保留。
- 每次模型输出都经过工具白名单、Evidence ID、声明类型、置信度、数值引用和交易语言门禁；
  task、Evidence 和前序摘要始终作为不可信数据。
- 唯一固定 Credential Target 为 `StockQuant/BailianApiKey`。一次性脚本只从原生安全 Console
  写入 Windows Credential Manager；适配器不会从环境变量、参数或明文文件降级。Broker 的
  零网络可读性检查与真实 smoke 是两个固定 operation。
- 百炼调用每次输出上限 600 tokens、最多 13 次模型调用；每次网络调用前按 UTF-8 请求字节和
  最大输出作保守成本预留，任一未知结果终止适配器。真实 smoke 仅在用户批准 CNY 5.00 硬上限后
  执行一次；当前未签发真实请求，真实百炼调用与成本均为 0。

## Fake Model 与评测证据

- 百炼切换受影响定向回归 `48/0/0/0`：固定 endpoint/model、Chat Completions 请求、严格结构化
  输出、CNY usage、HTTP/API 错误映射、零重试、7 Agent Fake Transport、Credential、Runner 与
  Broker 合同均通过；Broker M3 协议 `11/0/0/0`，相关 PowerShell 5.1 语法错误 0。
- 当前代码的打包 JAR + Fake Provider + PostgreSQL 16 临时实例 M1→M2→M3 E2E 再次 PASS；
  Start-Class 为 `TushareM3AgentResearchManualRunner`，百炼/Tushare 真实调用 0、永久库写入 0、
  临时残留 0；Vue/TypeScript production build PASS。
- 4 证券 × 180 交易日 deterministic fixture 完成 4 个代表策略的 M1/M2 工具链研究。
- 同输入、参数和固定时钟的完整 `ResearchReport` 与 SHA-256 指纹完全一致。
- `AGENT_EVAL_V1` 15/15：数据引用、工具调用、回测引用、风险识别、未来数据拒绝、
  虚假 Sharpe/收益拒绝、缺失数据拒绝、过拟合、高收益高回撤、Agent 冲突、Critic 修正、
  prompt injection、deterministic replay 和最终报告一致性全部通过。
- 打包 JAR + Fake Provider + PostgreSQL 16 临时实例完整 E2E 为 PASS：V1 至 V13、
  M1 增量/幂等、M2 回测、M3 七 Agent、正式 Start-Class/build proof、Broker 兼容映射、
  输出脱敏和临时残留 0；M1 Fake Provider 调用 18，M2/M3 Provider 调用 0。
- 最终核心回归：`quant-core 16/0/0/0`、`quant-server 48/0/0/0`；后者包含 M3 Runtime、
  validator、OpenAI-compatible adapter、Eval、报告/API、M1/M2 适配、正式 Runner、build proof 与本地自动化
  合同。PowerShell 5.1 的 9 个相关脚本语法错误 0，Broker 协议 `7/0/0/0`，Vue/TypeScript
  production build PASS，`git diff --check` PASS。

## 真实 M1 数据只读 smoke

- Broker request：`SQHB_20260811T071702Z_C8FB51E3D1FB`，终态 `SUCCEEDED`。
- 输入：`M1_RESEARCH_DATASET_V1`，`600000/SSE`、`000001/SZSE`，
  `2025-01-02` 至 `2025-01-10`。
- 覆盖 7 个开市日、14 条 daily、14 条 adj_factor、18 条 trade_cal、14 条 formula-only QFQ；
  typed fact、SYSTEM_KNOWLEDGE、数据质量和无未来数据泄漏均通过。
- 4 个 M2 策略实验、4 次工具调用、9 次 Fake Model 调用、2 轮、7 个角色完整执行；
  Critic 识别 `PIT_LINEAGE_LIMITATION` 与短窗口缺少样本外验证的 `OVERFITTING_RISK`，
  并完成一次受限修正。
- Portfolio 的机械排序首位为 `MEAN_REVERSION_V1`，但 7 日窗口不足以完成 train/test 和
  walk-forward；最终判断正确降级为 `INSUFFICIENT_EVIDENCE`、confidence 0、无策略偏好。
- 研究运行约 404 ms，打包 Runner 约 2.123 s；Fake Model token/cost 均为 0。
- 输出审计 clean，Provider 调用 0；永久库前后五表计数快照相同，写入 0；请求无 pending/
  processing 残留，结果与报告敏感模式命中 0。

## 阶段内自主修复

- 将模型工具调用从“运行时预先执行、模型事后描述”收口为 Coordinator 先规划、四个专业 Agent
  分别选择白名单工具、运行时验证选择后才执行确定性工具；专业工作流在 Portfolio 综合前不接收
  其他 Agent 摘要。当前完整流程为 13 次模型调用，Prompt 正式升级到 V2。
- 新增固定百炼 Credential、输出审计注册、零网络探针、OpenAI-compatible API 成本/次数硬门禁和
  Host Broker 固定 M3 operation；没有动态 Target、动态 URL、命令文本、环境变量密钥或重试。

- 已运行 Resident Broker 进程只识别既有 M2 任务分支，首次 M3 请求在秘密读取前以
  `STOCK_QUANT_HOST_BROKER_GIT_BINDING_INVALID` 安全拒绝。修复只允许固定 M3 分支、固定 M3 JAR
  复用零 Provider、只读 M2 operation；没有增加 operation、动态命令、Credential Target 或网络权限。
- 在 IDLE 且无待处理请求时终止旧 Broker；既有 PT1M watchdog 约 18 秒后以新 HEAD 恢复单实例
  `IDLE`。随后真实 M1 只读 smoke 通过，证明该兼容边界有效。

## 已知限制与外部前置条件

- 真实 M1 窗口只有 7 个开市日，只证明工具、时序、证据和协作链路，不证明 alpha；较长窗口的
  train/test、walk-forward、过拟合识别由 deterministic fixture 覆盖。
- M1 仍是 SYSTEM_KNOWLEDGE 与 formula-only QFQ，`PROVIDER_PIT_VERIFIED=false`；完整 F1 十项
  技术证据缺口不变。
- 百炼 API Key 已通过 Broker 零网络可读性验证，但真实 smoke 尚未通过：当前固定北京通用端点
  收到 HTTP 401、合法 JSON、`INVALID_API_KEY`，属于外部 Key/地域/套餐专属 Base URL 绑定问题。
  密钥不得发送到聊天；必须先在百炼控制台确认 Key 与 Base URL/地域/业务空间的官方绑定。
- M3 不启动 M4、Shadow、业务 scheduler、真实订单、实盘或自动交易。

## 真实百炼 smoke 阻断证据（2026-08-11）

- 用户批准模型 `qwen3.7-plus` 与阶段总成本上限 CNY 5.00。Broker 零网络 Credential request
  `SQHB_20260811T110240Z_6718DC513DAC` 成功，网络调用 0、输出审计通过。
- 脱敏诊断 request `SQHB_20260811T110306Z_69BAAA58B4FC` 只发出 1 次 HTTP 请求，retry 0；
  HTTP status 401，响应 Content-Type 为 JSON，JSON 解析成功，body error code 规范化为
  `INVALID_API_KEY`，分类为 `AUTHENTICATION / INVALID_OR_UNBOUND_API_KEY`。模型完成调用 0，
  token usage 0，保守占账 CNY 0.125；输出审计 clean，数据库写入 0，Tushare 调用 0。
- 前一版缺少失败遥测的首个请求按 CNY 0.50 保守占账；一次 Runner 前账本兼容失败可证明模型调用 0。
  当前阶段保守累计占账 CNY 0.625，未超过 CNY 5.00，但在外部绑定确认前停止新增真实调用。
- 阿里云官方错误码文档明确：`InvalidApiKey` 包括 Key 填写/删除、`sk-sp-` 套餐专属 Key 与通用
  Base URL 混用、以及 Key 与 Base URL 地域不一致；官方模型卡同时为 `qwen3.7-plus` 列出带
  `WorkspaceId` 的区域化 MaaS Base URL。当前证据不能在不读取密钥内容的前提下替用户选择绑定。
- 代码修复提交 `0bd0f030e3b8a8b0d24273a63d54ee86fe213906` 增加 HTTP status、body code、
  Content-Type/JSON、调用数与成本的脱敏失败证据；`6214d739ccd6823b81a3b46bdb6e187512a54caf`
  修复旧结果在 PowerShell StrictMode 下的预算账本兼容。打包 Fake M1→M2→M3 + 临时
  PostgreSQL E2E 再次 PASS，临时残留 0。
- 当前结论：`BAILIAN_REAL_LLM_SMOKE=BLOCKED_EXTERNAL_CREDENTIAL_ENDPOINT_BINDING`，不得声明
  `M3_AGENT_RESEARCH_READY=PASS`，不得执行最终集成收口。
