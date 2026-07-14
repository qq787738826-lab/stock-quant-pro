from __future__ import annotations

from abc import ABC
from datetime import datetime

from .models import (
    AgentCode,
    AgentDecision,
    AgentError,
    AgentOutput,
    AgentTeamRequest,
    GateStatus,
    RunStatus,
)


class InsufficientDataAgent(ABC):
    agent_code: AgentCode
    run_id_field: str
    summary: str
    error_code: str | None = None

    def analyze(self, request: AgentTeamRequest, generated_at: datetime) -> AgentOutput:
        errors = []
        if self.error_code:
            errors.append(AgentError(
                code=self.error_code,
                message="Java提供的只读分析上下文当前不可用，未执行推断。",
            ))
        return AgentOutput(
            taskId=request.taskId,
            runId=getattr(request.runIds, self.run_id_field),
            agentCode=self.agent_code,
            status=RunStatus.INSUFFICIENT_DATA,
            gateStatus=(GateStatus.BLOCKED
                        if self.agent_code is AgentCode.DATA_QUALITY
                        else GateStatus.NOT_APPLICABLE),
            decision=(AgentDecision.REJECT
                      if self.agent_code is AgentCode.DATA_QUALITY
                      else AgentDecision.NOT_APPLICABLE),
            veto=False,
            score=0,
            confidence=0,
            summary=self.summary,
            findings=[],
            evidence=[],
            errors=errors,
            contextHash=request.contextHash,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            generatedAt=generated_at,
        )


class DataQualityAgent(InsufficientDataAgent):
    agent_code = AgentCode.DATA_QUALITY
    run_id_field = "dataQuality"
    summary = "分析上下文数据不足，数据质量门禁已阻断本次团队分析。"
    error_code = "CONTEXT_DATA_UNAVAILABLE"


class MarketRegimeAgent(InsufficientDataAgent):
    agent_code = AgentCode.MARKET_REGIME
    run_id_field = "marketRegime"
    summary = "因数据质量门禁阻断，未执行市场环境分析。"


class TechnicalAnalysisAgent(InsufficientDataAgent):
    agent_code = AgentCode.TECHNICAL_ANALYSIS
    run_id_field = "technicalAnalysis"
    summary = "因数据质量门禁阻断，未执行技术分析。"


class StrategyBacktestAgent(InsufficientDataAgent):
    agent_code = AgentCode.STRATEGY_BACKTEST
    run_id_field = "strategyBacktest"
    summary = "因数据质量门禁阻断，未执行策略回测分析。"


class AnnouncementRiskAgent(InsufficientDataAgent):
    agent_code = AgentCode.ANNOUNCEMENT_RISK
    run_id_field = "announcementRisk"
    summary = "因数据质量门禁阻断，未执行公告事件风险分析。"


class PositionRiskAgent(InsufficientDataAgent):
    agent_code = AgentCode.POSITION_RISK
    run_id_field = "positionRisk"
    summary = "因数据质量门禁阻断且无真实仓位证据，未执行资金仓位风控分析。"
