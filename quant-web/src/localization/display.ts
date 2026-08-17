const STATUS_LABELS: Readonly<Record<string, string>> = Object.freeze({
  CHECKING: '检查中',
  READY: '就绪',
  HEALTHY: '正常',
  DEGRADED: '部分可用',
  ACTION_REQUIRED: '需要处理',
  BLOCKED: '受阻',
  ACTIVE: '运行中',
  IDLE: '空闲',
  BUSY: '忙碌中',
  FROZEN: '已冻结',
  COMPLETED: '已完成',
  REVISED: '已修订',
  FAILED: '失败',
  FAILED_PRE_PROVIDER: '调用数据源前失败',
  FAILED_PROVIDER: '数据源调用失败',
  FAILED_PERSISTENCE: '保存失败',
  FAILED_OUTPUT_AUDIT: '输出检查失败',
  FAILED_VALIDATION: '验证失败',
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  INTERRUPTED: '已中断',
  PARTIAL: '部分完成',
  CANCELLED: '已取消',
  WATCH: '观察',
  RETAIN: '保留',
  DEMOTE: '降级',
  REPLACE: '替换',
  PASS: '通过',
  FAIL: '未通过',
  WARN: '警告',
  NOT_APPLICABLE: '不适用',
  ENABLED: '已启用',
  DISABLED: '已关闭',
  ALLOWED: '允许',
  REJECTED: '已拒绝',
  PENDING: '待处理',
  FILLED: '已成交',
  OPEN: '进行中',
  CLOSED: '已结束',
  PREPARING_DATA: '准备数据',
  QUANTITATIVE_SCAN: '量化扫描',
  STRATEGY_ANALYSIS: '策略分析',
  AI_RESEARCH: '智能体研究',
  CRITIC_REVIEW: '批判审查',
  SUBMITTED: '已提交',
  NO_SHADOW_RUN: '暂无影子研究',
})

const RISK_LABELS: Readonly<Record<string, string>> = Object.freeze({
  LOW: '低风险',
  MODERATE: '中等风险',
  MEDIUM: '中等风险',
  HIGH: '高风险',
  CRITICAL: '严重风险',
  UNKNOWN: '未知风险',
})

const DECISION_LABELS: Readonly<Record<string, string>> = Object.freeze({
  RESEARCH_PREFERENCE: '存在研究偏好',
  INSUFFICIENT_EVIDENCE: '证据不足',
  INSUFFICIENT_SAMPLE: '样本不足',
  UNKNOWN: '未知',
  WATCH: '观察',
  RESEARCH_ONLY: '仅限研究',
  PASS_TO_MANUAL_REVIEW: '进入人工研究复核',
  REJECTED_BY_VETO: '被风险否决',
  BLOCKED_BY_DATA_QUALITY: '被数据质量阻断',
  INSUFFICIENT_DATA: '数据不足',
  RETAIN_CHAMPION: '保留当前正式版本',
  WATCH_CHALLENGER: '继续观察候选版本',
  PROMOTE_CHALLENGER: '晋升候选版本',
  REJECT_CHALLENGER: '拒绝候选版本',
  CHAMPION: '正式版本',
  CHALLENGER: '候选版本',
  HISTORICAL: '历史版本',
})

const AGENT_ROLE_LABELS: Readonly<Record<string, string>> = Object.freeze({
  RESEARCH_COORDINATOR: '研究协调智能体',
  DATA_ANALYST: '数据分析智能体',
  MARKET_TECHNICAL: '市场技术智能体',
  STRATEGY_RESEARCH: '策略研究智能体',
  RISK: '风险智能体',
  PORTFOLIO: '组合智能体',
  CRITIC_REVIEW: '批判审查智能体',
})

const CLAIM_TYPE_LABELS: Readonly<Record<string, string>> = Object.freeze({
  FACT: '事实',
  INFERENCE: '推论',
  HYPOTHESIS: '假设',
  RECOMMENDATION: '研究建议',
  UNKNOWN: '未知 / 证据不足',
})

const TRIGGER_LABELS: Readonly<Record<string, string>> = Object.freeze({
  ON_DEMAND: '立即选股',
  SCHEDULED: '定时运行',
  SCHEDULED_SHADOW: '自动影子研究',
  MANUAL: '手动运行',
  HISTORICAL_REPLAY: '历史回放',
  CURRENT_AS_OF: '当前时点研究',
})

const STRATEGY_LABELS: Readonly<Record<string, string>> = Object.freeze({
  BUY_AND_HOLD_V1: '买入并持有',
  MOVING_AVERAGE_MOMENTUM_V1: '均线动量',
  MEAN_REVERSION_V1: '均值回归',
  CROSS_SECTIONAL_MOMENTUM_V1: '横截面动量',
})

const GENERAL_LABELS: Readonly<Record<string, string>> = Object.freeze({
  BUY: '买入',
  SELL: '卖出',
  UPTREND: '上升趋势',
  DOWNTREND: '下降趋势',
  NEUTRAL: '震荡整理',
  STOP_LOSS: '止损触发',
  TAKE_PROFIT: '止盈触发',
  TRAILING_STOP: '移动止损触发',
  MAX_HOLD: '达到最长持有期',
  END_OF_DATA: '数据窗口结束',
  SIGNAL_EXIT: '策略信号退出',
  VERIFIED: '已验证',
  NOT_VERIFIED: '未验证',
  NONE: '无',
  EMPTY: '无候选',
  YES: '是',
  NO: '否',
  APPLIED: '已应用',
  N_A: '不适用',
  PAPER_ONLY: '仅模拟研究',
  RESEARCH_OUTPUT: '研究输出',
  DATASET: '研究数据集',
  EVIDENCE: '证据',
  CRITIC: '批判审查',
  RESEARCH: '研究',
  STRATEGY: '策略回测',
  SHADOW: '影子研究',
  EVALUATION: '智能体评测',
  REAL_TRADING: '真实交易',
  PAPER_EQUITY: '模拟权益',
  PAPER_RETURN: '模拟收益',
  POSITIONS: '模拟持仓',
})

const PRODUCT_LABELS: Readonly<Record<string, string>> = Object.freeze({
  RESEARCH_PRODUCTION_V1: '研究生产版 V1',
  SYSTEM_HEALTH_V1: '系统健康 V1',
  RESEARCH_UNIVERSE_V1: '研究股票池 V1',
  AGENT_RESEARCH_TEAM_V1: '七智能体研究团队 V1',
  STRATEGY_ENGINE_V1: '策略引擎 V1',
  BACKTEST_ENGINE_V1: '回测引擎 V1',
  SHADOW_RESEARCH_RUNTIME_V1: '影子研究运行时 V1',
  AGENT_EVALUATION_SYSTEM_V1: '智能体评测系统 V1',
  LOCAL_PAPER_ONLY: '本地运行 / 仅模拟',
})

const RESEARCH_TEXT_LABELS: Readonly<Record<string, string>> = Object.freeze({
  DATA_QUALITY_GAP: '数据质量存在缺口',
  FUTURE_DATA_RISK: '存在未来数据风险',
  METRIC_MISMATCH: '指标与确定性证据不一致',
  UNSUPPORTED_CLAIM: '存在缺乏证据支持的结论',
  OVERFITTING_RISK: '存在过拟合风险',
  DRAWDOWN_UNDERSTATED: '回撤风险可能被低估',
  AGENT_CONFLICT: '智能体观点存在冲突',
  OVERCONFIDENCE: '研究置信度过高',
  PIT_LINEAGE_LIMITATION: '数据源时点血缘尚未完整验证',
  PROMPT_INJECTION_ATTEMPT: '检测到提示词注入尝试',
  CONCENTRATION_LIMIT: '组合集中度超出研究约束',
  FORMULA_ONLY_QFQ_LINEAGE: '当前前复权仅具备公式推导血缘',
  PROVIDER_PIT_NOT_VERIFIED: '数据源时点血缘尚未验证',
  OUT_OF_SAMPLE_WINDOW_INSUFFICIENT: '样本外评估窗口不足',
  OVERFITTING_RISK_DETECTED: '检测到过拟合风险',
  HIGH_RETURN_HIGH_DRAWDOWN: '高收益同时伴随高回撤',
  NO_SECURITY_PASSED_SELECTION_THRESHOLD: '没有证券通过选股阈值',
  'A model claim was rejected because its evidence reference was not present in deterministic tool output.':
    '模型结论引用了确定性工具输出中不存在的证据，已被系统拒绝。',
  'A model claim was rejected because deterministic supporting evidence was insufficient.':
    '模型结论缺乏足够的确定性支持证据，已被系统拒绝。',
  'A model uncertainty claim was rejected because its confidence exceeded the uncertainty limit.':
    '模型的不确定性结论超过置信度上限，已被系统拒绝。',
  'A model-supplied numeric statement was rejected because cited deterministic evidence did not directly support it.':
    '模型给出的数值结论缺乏确定性证据支持，已被系统拒绝。',
  'The accepted research dataset is eligible for bounded quantitative analysis.':
    '已验收的研究数据集具备开展受限量化分析的资格。',
  'The technical classifications are derived from the recorded adjusted-price observations.':
    '技术分类来自已记录的前复权价格观察。',
  'The strategy comparison uses deterministic backtests with accounting and look-ahead guards.':
    '策略比较使用带有会计守恒和防未来数据门禁的确定性回测。',
  'The risk classification reflects quantified drawdown, volatility, and concentration evidence.':
    '风险分类反映了量化回撤、波动率和集中度证据。',
  'The available window is insufficient for an out-of-sample research preference.':
    '当前数据窗口不足以形成样本外研究偏好。',
  'The evidence supports a bounded research preference, not an executable trading instruction.':
    '现有证据支持受限的研究偏好，但不构成可执行交易指令。',
  'The revised synthesis marks the available window as insufficient for out-of-sample preference.':
    '修订后的综合结论明确标记当前窗口不足以形成样本外偏好。',
  'The research preference is revised to disclose unresolved lineage limits and a reduced confidence cap.':
    '修订后的研究偏好披露了尚未解决的血缘限制，并降低置信度上限。',
  'The leading deterministic experiment is a research preference subject to the independent risk assessment.':
    '领先的确定性实验仅形成研究偏好，仍受独立风险评估约束。',
  'The portfolio conclusion must explicitly preserve the unverified provider lineage limitation.':
    '组合结论必须明确保留数据源时点血缘尚未验证这一限制。',
  'No unsupported quantitative conclusion survived the evidence checks.':
    '证据检查后没有保留缺乏支持的量化结论。',
})

const PHASE_LABELS: Readonly<Record<string, string>> = Object.freeze({
  PLAN: '研究计划',
  DATA_TOOL_SELECTION: '选择数据工具',
  DATA_QUALITY: '数据质量分析',
  TECHNICAL_TOOL_SELECTION: '选择技术分析工具',
  TECHNICAL_ANALYSIS: '市场技术分析',
  STRATEGY_TOOL_SELECTION: '选择策略工具',
  STRATEGY_EXPERIMENTS: '策略实验',
  RISK_TOOL_SELECTION: '选择风险工具',
  RISK_ASSESSMENT: '风险评估',
  PORTFOLIO_DECISION: '组合判断',
  CRITIC_CHALLENGE: '批判审查',
  FINAL_SYNTHESIS: '最终汇总',
})

const TOOL_LABELS: Readonly<Record<string, string>> = Object.freeze({
  RESEARCH_DATASET: '研究数据集',
  MARKET_TECHNICAL: '市场技术指标',
  STRATEGY_BACKTEST: '策略回测',
  STRATEGY_COMPARE: '策略比较',
  RISK_METRICS: '风险指标',
})

const COMPONENT_LABELS: Readonly<Record<string, string>> = Object.freeze({
  DATABASE: '研究数据库',
  BROKER: '本地研究代理',
  BACKEND: '后端服务',
  FRONTEND_API: '用户界面与接口',
  M1_DATASET: 'M1 研究数据',
  M2_BACKTEST: 'M2 策略回测',
  M3_AGENT_RUNTIME: 'M3 智能体运行时',
  M4_SHADOW: 'M4 影子研究',
  M5_EVALUATION: 'M5 智能体评测',
  TUSHARE_CREDENTIAL: 'Tushare 凭据',
  BAILIAN_CREDENTIAL: '百炼凭据',
})

const REASON_LABELS: Readonly<Record<string, string>> = Object.freeze({
  ALL_REQUIRED_COMPONENTS_HEALTHY: '所有日常运行组件均正常',
  NON_BLOCKING_COMPONENT_DEGRADED: '部分非关键组件暂不可用',
  REQUIRED_COMPONENT_BLOCKED: '关键组件需要处理',
  POSTGRESQL_V17_READY: '研究数据库 V17 已就绪',
  DATABASE_V17_REQUIRED: '研究数据库需要升级到 V17',
  RESIDENT_BROKER_IDLE: '本地研究代理空闲',
  RESIDENT_BROKER_BUSY: '本地研究代理正在处理任务',
  HOST_BROKER_HEARTBEAT_STALE: '本地研究代理心跳已过期',
  HOST_BROKER_NOT_RUNNING: '本地研究代理尚未运行',
  M1_RESEARCH_DATASET_V1: '研究数据集可用',
  DATASET_EMPTY: '研究数据集尚无可用数据',
  API_RESPONDING: '后端服务响应正常',
  PRODUCTION_UI_AVAILABLE: '用户界面可用',
  API_ONLY_UI_DEGRADED: '后端可用，但用户界面资源不完整',
  STRATEGY_RESEARCH_API_V1: '策略回测服务可用',
  AGENT_RESEARCH_TEAM_V1: '七智能体研究团队可用',
  SHADOW_RESEARCH_RUNTIME_V1: '影子研究服务可用',
  AGENT_EVALUATION_SYSTEM_V1: '智能体评测服务可用',
  INSUFFICIENT_EVALUATION_SAMPLE: '智能体评测样本不足',
  CREDENTIAL_PRESENT: '凭据已安全配置',
  CREDENTIAL_MISSING: '凭据尚未配置',
  CREDENTIAL_STATUS_UNAVAILABLE: '暂时无法检查凭据状态',
  M4_SCHEDULER_BROKER_SUBMIT_REJECTED: '自动影子研究提交失败',
  M4_SHADOW_REQUEST_INVALID: '研究时间边界校验未通过',
  RESEARCH_SELECTION_PAPER_EXECUTION_NOT_AFTER_AS_OF: '模拟执行时间早于研究时点，已安全拒绝',
  RESEARCH_SELECTION_ALREADY_RUNNING: '已有一次立即选股正在运行',
  NETWORK_ERROR: '本机网络连接暂不可用',
  EVIDENCE_CORRECTNESS: '证据引用正确性不足',
  TOOL_CALL_CORRECTNESS: '工具调用正确性不足',
  UNSUPPORTED_CLAIM_CONTROL: '无证据声明控制不足',
  UNKNOWN_DISCIPLINE: '证据不足时的未知结论使用不合理',
  FUTURE_DATA_RECOGNITION: '未来数据识别能力不足',
  RISK_RECOGNITION: '风险识别能力不足',
  CONFLICT_DISCOVERY: '观点冲突发现能力不足',
  CRITIC_CORRECTION_CONTRIBUTION: '批判审查纠错贡献不足',
  FINAL_REPORT_CONTRIBUTION: '最终报告贡献不足',
  STABILITY_CONSISTENCY: '稳定性或一致性不足',
  TOKEN_COST_EFFICIENCY: '模型成本效率不足',
  LATENCY_EFFICIENCY: '响应耗时效率不足',
  CHALLENGER_SUPERIOR_WITH_MINIMUM_EVIDENCE: '候选版本在满足最低证据要求后表现更好',
  SAFETY_OR_REGRESSION_GATE_FAILED: '候选版本未通过安全或回归门禁',
  INSUFFICIENT_SHADOW_SAMPLE: '影子研究样本不足',
  OFFLINE_AGENT_EVAL_INCOMPLETE: '离线智能体评测尚未全部通过',
  INSUFFICIENT_BOUND_REPLAY_EVIDENCE: '绑定版本的历史回放证据不足',
  NO_MATERIAL_SCORE_IMPROVEMENT: '候选版本评分没有实质提升',
  COST_OR_LATENCY_REGRESSION: '候选版本成本或延迟退化',
})

function keyOf(value: unknown): string {
  return String(value ?? '').trim().toUpperCase()
    .replace(/&/g, ' AND ')
    .replace(/[^A-Z0-9]+/g, '_')
    .replace(/^_+|_+$/g, '')
}

function lookup(map: Readonly<Record<string, string>>, value: unknown): string | undefined {
  const key = keyOf(value)
  return key ? map[key] : undefined
}

export function displayStatus(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(STATUS_LABELS, value)
    ?? lookup(DECISION_LABELS, value)
    ?? '未知状态'
}

export function displayRisk(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(RISK_LABELS, value) ?? '风险待确认'
}

export function displayDecision(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(DECISION_LABELS, value)
    ?? lookup(STATUS_LABELS, value)
    ?? '研究结论待确认'
}

export function displayAgentRole(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(AGENT_ROLE_LABELS, value) ?? '研究智能体'
}

export function displayClaimType(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(CLAIM_TYPE_LABELS, value) ?? '研究判断'
}

export function displayTrigger(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(TRIGGER_LABELS, value) ?? '受控运行'
}

export function displayStrategy(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(STRATEGY_LABELS, value) ?? String(value)
}

export function displayPhase(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(PHASE_LABELS, value) ?? '研究分析'
}

export function displayTool(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(TOOL_LABELS, value) ?? '研究工具'
}

export function displayComponent(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(COMPONENT_LABELS, value) ?? String(value)
}

export function displayValue(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(GENERAL_LABELS, value)
    ?? lookup(DECISION_LABELS, value)
    ?? lookup(STATUS_LABELS, value)
    ?? lookup(RISK_LABELS, value)
    ?? lookup(TRIGGER_LABELS, value)
    ?? lookup(STRATEGY_LABELS, value)
    ?? lookup(PRODUCT_LABELS, value)
    ?? String(value)
}

function localizeResearchEnums(value: string): string {
  return value
    .replace(/PROVIDER_PIT_VERIFIED=(true|false)/g, (_, flag) =>
      `数据源时点血缘已验证=${flag === 'true' ? '是' : '否'}`)
    .replace(/OUT_OF_SAMPLE_EVALUATED=(true|false)/g, (_, flag) =>
      `样本外评估=${flag === 'true' ? '是' : '否'}`)
    .replace(/\b(UPTREND|DOWNTREND|NEUTRAL)\b/g, item =>
      lookup(GENERAL_LABELS, item) ?? item)
    .replace(/\b(LOW|MODERATE|MEDIUM|HIGH|CRITICAL|UNKNOWN)\b/g, item =>
      lookup(RISK_LABELS, item) ?? item)
    .replace(/\b(true|false)\b/g, item => item === 'true' ? '是' : '否')
}

export function displayProduct(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  return lookup(PRODUCT_LABELS, value) ?? String(value)
}

export function displayResearchText(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  const raw = String(value).trim()
  const exact = RESEARCH_TEXT_LABELS[raw]
    ?? lookup(RESEARCH_TEXT_LABELS, raw)
  if (exact) return exact
  const dataset = raw.match(/^The accepted M1 dataset passed typed-fact, SYSTEM_KNOWLEDGE, data-quality, formula-only QFQ, and no-future-data checks for (\d+) securities and (\d+) open sessions; provider PIT lineage is (true|false)\.$/)
  if (dataset) {
    return `已验收的 M1 研究数据集通过类型化事实、SYSTEM_KNOWLEDGE、数据质量、公式化 QFQ 和防未来数据检查；覆盖证券数=${dataset[1]}，开市日数=${dataset[2]}；数据源时点血缘已验证=${dataset[3] === 'true' ? '是' : '否'}。`
  }
  const technical = raw.match(/^For ([^,]+), deterministic QFQ analysis used (\d+) observations; window return=([^,]+), short momentum=([^,]+), annualized volatility=([^,]+), trend=([^\.]+)\.$/)
  if (technical) {
    return `证券 ${technical[1]} 的确定性 QFQ 分析使用观察数=${technical[2]}；区间收益=${technical[3]}，短期动量=${technical[4]}，年化波动率=${technical[5]}，趋势=${displayValue(technical[6])}。`
  }
  if (raw.startsWith('Deterministic M2 backtest for ')) {
    return localizeResearchEnums(raw
      .replace(/^Deterministic M2 backtest for /, '策略 ')
      .replace(' produced total return=', ' 的确定性 M2 回测结果：总收益=')
      .replace(', Sharpe=', '，夏普比率=')
      .replace(', maximum drawdown=', '，最大回撤=')
      .replace(', turnover=', '，换手率=')
      .replace(', excess return=', '，超额收益=')
      .replace('; accounting and look-ahead guards passed=', '；会计与防未来数据门禁=')
      .replace(', out-of-sample evaluated=', '，样本外评估=')
      .replace(', train return=', '，训练期收益=')
      .replace(', test return=', '，测试期收益=')
      .replace(', walk-forward folds=', '，滚动窗口折数=')
      .replace(', overfitting flag=', '，过拟合标记='))
  }
  if (raw.startsWith('Risk policy classified ')) {
    return localizeResearchEnums(raw
      .replace(/^Risk policy classified /, '风险规则将策略 ')
      .replace(' as ', ' 分类为 ')
      .replace(' with maximum drawdown=', '；最大回撤=')
      .replace(', annualized volatility=', '，年化波动率=')
      .replace(', maximum position weight=', '，最大持仓权重=')
      .replace(', high-return/high-drawdown flag=', '，高收益高回撤标记='))
  }
  if (raw.startsWith('Current-as-of evidence-bound stock selection over ')) {
    return raw.replace(/^Current-as-of evidence-bound stock selection over /,
      '基于 ').replace('; paper research only, no real trading.',
      ' 开展当前时点、证据约束的股票研究；仅用于研究和模拟，不进行真实交易。')
  }
  if (raw === 'Perform evidence-bound seven-agent shadow research; freeze the conclusion and permit an empty paper portfolio.') {
    return '执行受证据约束的七智能体影子研究；冻结研究结论，并允许模拟组合保持空仓。'
  }
  return localizeResearchEnums(raw)
}

export function displayResearchList(values: readonly unknown[] | null | undefined): string {
  return (values ?? []).map(displayResearchText).filter(value => value !== '—').join('；')
}

export function isLegacyResearchText(value: unknown): boolean {
  const raw = String(value ?? '').trim()
  return /[A-Za-z]{4,}/.test(raw) && !/[\u3400-\u9fff]/.test(raw)
}

export function displayReason(value: unknown): string {
  if (value == null || String(value).trim() === '') return '暂无异常说明'
  const exact = lookup(REASON_LABELS, value)
  if (exact) return exact
  const key = keyOf(value)
  if (key.includes('BUDGET')) return 'API 预算不足'
  if (key.includes('BROKER')) return '本地研究代理暂不可用'
  if (key.includes('SCHEDULER')) return '自动影子研究调度异常'
  if (key.includes('TUSHARE') || key.includes('PROVIDER')) return '市场数据服务异常'
  if (key.includes('BAILIAN') || key.includes('MODEL')) return '智能体模型服务异常'
  if (key.includes('DATABASE') || key.includes('POSTGRES') || key.includes('FLYWAY')) return '研究数据库异常'
  if (key.includes('BUILD') || key.includes('JAR') || key.includes('ARTIFACT') || key.includes('GIT')) return '运行文件版本不一致'
  if (key.includes('DATA')) return '研究数据异常'
  return '系统运行异常，请查看高级诊断信息'
}

export function formatDateTime(value: unknown): string {
  if (value == null || String(value).trim() === '') return '—'
  const raw = String(value).trim()
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw
  const date = new Date(raw)
  if (Number.isNaN(date.getTime())) return '—'
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(date)
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find(valuePart => valuePart.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`
}

export function formatCurrency(value: unknown, maximumFractionDigits = 2): string {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '—'
  return `¥${amount.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits,
  })}`
}

const ALL_MAPS = [STATUS_LABELS, RISK_LABELS, DECISION_LABELS,
  AGENT_ROLE_LABELS, CLAIM_TYPE_LABELS, TRIGGER_LABELS, STRATEGY_LABELS,
  GENERAL_LABELS, PRODUCT_LABELS, RESEARCH_TEXT_LABELS, PHASE_LABELS,
  TOOL_LABELS, COMPONENT_LABELS, REASON_LABELS]

export const DISPLAY_MAPPING_COUNT = new Set(
  ALL_MAPS.flatMap(map => Object.keys(map)),
).size
