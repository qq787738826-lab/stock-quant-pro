from __future__ import annotations

import logging

from fastapi import APIRouter

from .models import AgentTeamRequest, AgentTeamResponse
from .orchestrator import AgentTeamOrchestrator


logger = logging.getLogger(__name__)
router = APIRouter(prefix="/agents/team", tags=["agent-team"])
orchestrator = AgentTeamOrchestrator()


@router.post("/analyze", response_model=AgentTeamResponse)
def analyze_agent_team(request: AgentTeamRequest) -> AgentTeamResponse:
    logger.info(
        "agent team analysis started taskId=%s symbol=%s contextHash=%s",
        request.taskId,
        request.symbol,
        request.contextHash[:12],
    )
    response = orchestrator.analyze(request)
    logger.info(
        "agent team analysis finished taskId=%s symbol=%s contextHash=%s status=%s",
        request.taskId,
        request.symbol,
        request.contextHash[:12],
        response.finalDecision.decision.value,
    )
    return response
