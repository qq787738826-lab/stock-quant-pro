import type { AgentRun } from './types'

export const FINAL_AGENT_ROLE_PHASES = Object.freeze([
  { agentRole: 'RESEARCH_COORDINATOR', phases: ['FINAL_SYNTHESIS'] },
  { agentRole: 'DATA_ANALYST', phases: ['DATA_QUALITY'] },
  { agentRole: 'MARKET_TECHNICAL', phases: ['TECHNICAL_ANALYSIS'] },
  { agentRole: 'STRATEGY_RESEARCH', phases: ['STRATEGY_EXPERIMENTS'] },
  { agentRole: 'RISK', phases: ['RISK_ASSESSMENT'] },
  { agentRole: 'PORTFOLIO', phases: ['PORTFOLIO_REVISION', 'PORTFOLIO_SYNTHESIS'] },
  { agentRole: 'CRITIC_REVIEW', phases: ['CRITIC_CHALLENGE'] },
] as const)

export const INTERNAL_ONLY_AGENT_PHASES = Object.freeze([
  'PLAN',
  'DATA_TOOL_SELECTION',
  'TECHNICAL_TOOL_SELECTION',
  'STRATEGY_TOOL_SELECTION',
  'RISK_TOOL_SELECTION',
] as const)

export interface AgentConclusionCard {
  agentRole: string
  phase: string
  run: AgentRun | null
}

export interface AgentExecutionTrace {
  runId: string
  agentRole: string
  phase: string
  status: string
  requestedTools: string[]
  tokenTotal: number
  revised: boolean
}

function latestMatchingRun(
  runs: readonly AgentRun[],
  agentRole: string,
  phase: string,
): AgentRun | null {
  for (let index = runs.length - 1; index >= 0; index -= 1) {
    const run = runs[index]
    if (run.agentRole === agentRole && run.phase === phase) return run
  }
  return null
}

export function buildAgentConclusionCards(
  runs: readonly AgentRun[] | null | undefined,
): AgentConclusionCard[] {
  const values = runs ?? []
  return FINAL_AGENT_ROLE_PHASES.map(spec => {
    for (const phase of spec.phases) {
      const run = latestMatchingRun(values, spec.agentRole, phase)
      if (run) return { agentRole: spec.agentRole, phase, run }
    }
    return { agentRole: spec.agentRole, phase: spec.phases[0], run: null }
  })
}

export function buildAgentExecutionTrace(
  runs: readonly AgentRun[] | null | undefined,
): AgentExecutionTrace[] {
  return (runs ?? []).map(run => ({
    runId: run.runId,
    agentRole: run.agentRole,
    phase: run.phase,
    status: run.status,
    requestedTools: [...(run.requestedTools ?? [])],
    tokenTotal: Number(run.usage?.inputTokens ?? 0)
      + Number(run.usage?.outputTokens ?? 0),
    revised: Boolean(run.revised || run.status === 'REVISED'),
  }))
}
