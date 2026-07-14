from __future__ import annotations

from datetime import datetime, timezone

from .agents import (
    AnnouncementRiskAgent,
    DataQualityAgent,
    MarketRegimeAgent,
    PositionRiskAgent,
    StrategyBacktestAgent,
    TechnicalAnalysisAgent,
)
from .models import (
    AgentTeamRequest,
    AgentTeamResponse,
    FinalDecision,
    FinalDecisionCode,
    GateStatus,
)


class ChiefDecisionService:
    def decide(self, request: AgentTeamRequest, generated_at: datetime) -> FinalDecision:
        return FinalDecision(
            taskId=request.taskId,
            decision=FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
            gateStatus=GateStatus.BLOCKED,
            vetoed=False,
            score=0,
            confidence=0,
            summary="分析上下文数据不足，总控决策已按数据质量门禁阻断。",
            findings=[],
            sourceRunIds=[run_id for _, run_id in request.runIds.ordered()],
            vetoIds=[],
            contextHash=request.contextHash,
            tradeDate=request.tradeDate,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            generatedAt=generated_at,
        )


class AgentTeamOrchestrator:
    def __init__(self) -> None:
        self._agents = (
            DataQualityAgent(),
            MarketRegimeAgent(),
            TechnicalAnalysisAgent(),
            StrategyBacktestAgent(),
            AnnouncementRiskAgent(),
            PositionRiskAgent(),
        )
        self._chief = ChiefDecisionService()

    def analyze(self, request: AgentTeamRequest) -> AgentTeamResponse:
        generated_at = datetime.now(timezone.utc)
        runs = [agent.analyze(request, generated_at) for agent in self._agents]
        return AgentTeamResponse(
            taskId=request.taskId,
            contextHash=request.contextHash,
            tradeDate=request.tradeDate,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            agentRuns=runs,
            evidence=[],
            vetoes=[],
            finalDecision=self._chief.decide(request, generated_at),
            generatedAt=generated_at,
        )
