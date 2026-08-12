# M3_AGENT_RESEARCH_READY 阶段证据

## 当前结论

- 长期任务分支：`codex/1.4.0-m3-agent-research-ready`
- 冻结集成基线：`b041fbe807b817c4070781db33fbfca2d4f8bc4e`
- 最终代码与测试证据 HEAD：`195746398fe60c1cdfc29f400dd404a144eab929`
- `M3_AGENT_RESEARCH_READY=PASS`：真实 M1 数据、M2 策略/回测工具、7 Agent 协作、
  Evidence、Risk、Portfolio、Critic、结构化报告和真实百炼模型链路已经闭环。
- M3 新增 Tushare 调用 0，累计真实调用保持 55；永久研究数据库写入 0；未启动 M4、
  Shadow、业务 scheduler、真实订单、实盘或自动交易。

## 交付能力

- `AGENT_RUNTIME_V1`：共享的有界运行时，限制 `maxRounds`、`maxToolCalls`、
  `maxModelCalls` 和 timeout；Critic 最多触发一次返工，失败时保留已经产生的脱敏 usage。
- `AGENT_RESEARCH_TEAM_V1`：Research Coordinator、Data Analyst、Market Technical、
  Strategy Research、Risk、Portfolio、Critic Review 七个固定职责。
- `AGENT_TOOL_GATEWAY_V1`：白名单工具 `ResearchDataset`、`MarketTechnical`、
  `StrategyCompare`、`RiskMetrics`；行情、指标、回测和风险计算由 M1/M2 确定性代码负责。
- `RESEARCH_REPORT_V1`：结构化记录数据窗口、Evidence、策略实验、风险、组合、Agent finding、
  Critic、最终判断、未知项、运行指纹和成本；声明类型严格区分 FACT、INFERENCE、HYPOTHESIS、
  RECOMMENDATION 和 UNKNOWN。
- `AGENT_EVAL_V1`：固定对抗评测覆盖虚假数值、未来数据、缺失证据、过拟合、高回撤、
  Agent 冲突、prompt injection、Critic 修正、确定性重放和最终报告一致性。
- ModelAdapter 与厂商解耦；离线使用 `DETERMINISTIC_FAKE_MODEL_V1`，正式 smoke 使用阿里云百炼
  北京区域 OpenAI-compatible API、固定模型 `qwen3.7-plus`、redirects NEVER、retry 0。

## 真实百炼研究证据

- 成功 Broker request：`SQHB_20260811T235741Z_BCB57952F25D`；execution ID：
  `M3SMOKE_20260811T235741Z_BCB57952F25D`；终态 `SUCCEEDED`。
- 正式构建绑定 Git `c45ded2a7111fd2004dce3e7c8cab2cd61612e31`，JAR SHA-256：
  `385a5cbd271b01e63fe0122e4d40f4775bdf16d6b1ea44fea0e9e9723df4206a`。
- 13 次 Provider/model request 全部完成，retry 0；input 22,613、output 4,065、reasoning 0、
  total 26,678 tokens；单次研究保守成本 CNY 0.858760000000。百炼 API 不返回账单金额，
  `actualCostStatus=NOT_PROVIDED_BY_API`，因此不把估算值描述为实际账单。
- 单次真实研究耗时 71.786 秒；输出审计 clean，结果脱敏，数据库只读快照不变，
  Tushare 调用 0，永久数据库写入 0。
- M1 只读输入覆盖 `600000/SSE`、`000001/SZSE` 的 7 个开市日；typed fact、
  SYSTEM_KNOWLEDGE、formula-only QFQ、数据质量和无未来数据泄漏检查全部通过。
- 运行时实际调用四种工具，形成 BUY_AND_HOLD、MOVING_AVERAGE_MOMENTUM、
  MEAN_REVERSION、CROSS_SECTIONAL_MOMENTUM 四个 M2 策略实验；会计守恒和 look-ahead guard
  全部通过。
- Portfolio 的确定性排序首位为 `MEAN_REVERSION_V1`，风险等级 LOW，研究性建议总敞口上限
  0.75、置信度上限 0.40；这不是交易指令。
- Critic 识别 `OVERFITTING_RISK`、`METRIC_MISMATCH`、`DATA_QUALITY_GAP` 和
  `PIT_LINEAGE_LIMITATION`，触发一次有界修正。由于真实窗口仅 7 日、无法形成有效 OOS，
  Coordinator 最终正确输出 `INSUFFICIENT_EVIDENCE`、无策略偏好、confidence 0。
- 最终报告含 11 条 Evidence、26 条 finding，其中 11 条为 UNKNOWN；研究指纹：
  `36ca1bc07963f0b966bfaa4ac029ab51cf8681056938b051d5652d8b802c757d`。

## 独立 CNY 5.00 预算账本

- 新预算分 tranche `M3_BAILIAN_TRANCHE_2`，批准标记
  `USER_APPROVED_M3_BAILIAN_SMOKE_TRANCHE_2_CNY_5_00`；与旧预算完全隔离。
- 共 4 个真实研究 request、31 次 Provider request、29 次完整模型调用；已知 input 38,190、
  output 8,059、reasoning 0、total 46,249 tokens。
- 首次失败有 1 次已发请求未返回可用 usage；账本按最大输出和请求字节保守预留，不丢弃此前调用。
- tranche 保守累计占账 CNY 1.777600000000，余额 CNY 3.222400000000；真实调用次数门限已经用尽，
  不再为形式验证继续调用模型。

## 最终验证

- M3 核心 Java 定向回归 `51/0/0/0`；`AGENT_EVAL_V1` 对抗场景 `15/15`。
- 打包 JAR + Fake M1→M2→M3 + PostgreSQL 16 临时实例 E2E：PASS；V1 至 V13、M1 增量/幂等、
  M2 回测、7 Agent、正式 Start-Class/build proof、Broker 映射、输出脱敏均通过。
- Fake E2E 中 M1 Provider 调用 18，M2/M3 真实 Provider 调用 0，永久数据库写入 0，
  临时 PostgreSQL 和请求状态残留 0。
- 同输入、参数和固定时钟的 Fake Model 完整报告及 SHA-256 指纹可重复；PowerShell/Broker
  协议受影响定向检查和 `git diff --check` 通过。

## 阶段内自主解决的重要问题

- 将模型工具调用收口为“Agent 选择 → 白名单/权限校验 → 确定性工具执行”，避免模型编造行情、
  指标或回测结果。
- 修复失败路径 usage 丢失、历史结果兼容、响应 token 上限、精确 JSON/tool 选择解析、
  Critic 控制字段隔离和错误分类；所有真实调用均保留脱敏预算证据。
- 对模型提出但 M1/M2 Evidence 不支持的数值或因果声明确定性降级为 UNKNOWN；动态命令、
  越权工具、可执行交易语言和无效 Evidence 继续 fail-closed。
- 百炼 Key 只由 Resident Broker 从固定 `StockQuant/BailianApiKey` 读取；未写入参数、环境变量、
  文件、日志、request、result 或 Git。

## 已知限制与下一阶段边界

- 真实 M1 样本只有 7 个开市日，只证明工具、时序、证据和协作链路，不证明 alpha；较长窗口、
  train/test、walk-forward 和过拟合识别由 deterministic fixture 覆盖。
- 当前仍为 SYSTEM_KNOWLEDGE 与 formula-only QFQ，`PROVIDER_PIT_VERIFIED=false`；完整 F1
  十项技术证据缺口不变。
- M3 达到进入 `M4_SHADOW_READY` 的技术前置条件，但 M4 尚未授权或启动；M3 PASS 不授权
  Shadow、自动定时推荐、真实订单、实盘或自动交易。
