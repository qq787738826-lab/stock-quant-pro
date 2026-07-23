from __future__ import annotations

from abc import ABC
from datetime import datetime

from .data_quality import DataQualityRuleEngine
from .market_regime import MarketRegimeRuleEngine
from .technical_analysis import TechnicalAnalysisRuleEngine
from .models import (
    AgentCode,
    AgentDecision,
    AgentError,
    AgentOutput,
    AgentTeamRequest,
    GateStatus,
    RunStatus,
    STAGE_2B_DATA_QUALITY_RULE_VERSION,
    STAGE_2D_MARKET_REGIME_RULE_VERSION,
    STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION,
)


_DATA_QUALITY_RULE_VERSIONS = frozenset({
    STAGE_2B_DATA_QUALITY_RULE_VERSION,
    STAGE_2D_MARKET_REGIME_RULE_VERSION,
    STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION,
})
_MARKET_REGIME_RULE_VERSIONS = frozenset({
    STAGE_2D_MARKET_REGIME_RULE_VERSION,
    STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION,
})


class InsufficientDataAgent(ABC):
    agent_code: AgentCode
    run_id_field: str
    summary: str
    pending_summary: str
    error_code: str | None = None

    def analyze(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
        data_quality_gate: GateStatus | None = None,
    ) -> AgentOutput:
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
            summary=(self.pending_summary
                     if request.ruleVersion in _DATA_QUALITY_RULE_VERSIONS
                     and data_quality_gate in (GateStatus.PASS, GateStatus.WARN)
                     else self.summary),
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
    pending_summary = summary
    error_code = "CONTEXT_DATA_UNAVAILABLE"

    def __init__(self) -> None:
        self._engine = DataQualityRuleEngine()

    def analyze(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
        data_quality_gate: GateStatus | None = None,
    ) -> AgentOutput:
        if request.ruleVersion not in _DATA_QUALITY_RULE_VERSIONS:
            return super().analyze(request, generated_at, data_quality_gate)
        evaluation = self._engine.evaluate(request, generated_at)
        return AgentOutput(
            taskId=request.taskId,
            runId=request.runIds.dataQuality,
            agentCode=AgentCode.DATA_QUALITY,
            status=evaluation.status,
            gateStatus=evaluation.gate_status,
            decision=evaluation.decision,
            veto=False,
            score=evaluation.score,
            confidence=evaluation.confidence,
            summary=evaluation.summary,
            findings=list(evaluation.findings),
            evidence=list(evaluation.evidence),
            errors=list(evaluation.errors),
            contextHash=request.contextHash,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            generatedAt=generated_at,
        )


class MarketRegimeAgent(InsufficientDataAgent):
    agent_code = AgentCode.MARKET_REGIME
    run_id_field = "marketRegime"
    summary = "因数据质量门禁阻断，未执行市场环境分析。"
    pending_summary = "市场环境规则尚未实现，未执行市场环境分析。"

    def __init__(self) -> None:
        self._engine = MarketRegimeRuleEngine()

    def analyze(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
        data_quality_gate: GateStatus | None = None,
    ) -> AgentOutput:
        if request.ruleVersion not in _MARKET_REGIME_RULE_VERSIONS:
            return super().analyze(request, generated_at, data_quality_gate)
        evaluation = self._engine.evaluate(
            request,
            data_quality_gate if data_quality_gate is not None else GateStatus.BLOCKED,
        )
        return AgentOutput(
            taskId=request.taskId,
            runId=request.runIds.marketRegime,
            agentCode=AgentCode.MARKET_REGIME,
            status=evaluation.status,
            gateStatus=evaluation.gate_status,
            decision=evaluation.decision,
            veto=False,
            score=evaluation.score,
            confidence=evaluation.confidence,
            summary=evaluation.summary,
            findings=list(evaluation.findings),
            evidence=list(evaluation.evidence),
            errors=list(evaluation.errors),
            contextHash=request.contextHash,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            generatedAt=generated_at,
        )


class TechnicalAnalysisAgent(InsufficientDataAgent):
    agent_code = AgentCode.TECHNICAL_ANALYSIS
    run_id_field = "technicalAnalysis"
    summary = "因数据质量门禁阻断，未执行技术分析。"
    pending_summary = "技术分析规则尚未实现，未执行技术分析。"

    def __init__(self) -> None:
        self._engine = TechnicalAnalysisRuleEngine()

    def analyze(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
        data_quality_gate: GateStatus | None = None,
    ) -> AgentOutput:
        if request.ruleVersion != STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION:
            return super().analyze(request, generated_at, data_quality_gate)
        evaluation = self._engine.evaluate(
            request,
            data_quality_gate if data_quality_gate is not None else GateStatus.BLOCKED,
        )
        return AgentOutput(
            taskId=request.taskId,
            runId=request.runIds.technicalAnalysis,
            agentCode=AgentCode.TECHNICAL_ANALYSIS,
            status=evaluation.status,
            gateStatus=evaluation.gate_status,
            decision=evaluation.decision,
            veto=False,
            score=evaluation.score,
            confidence=evaluation.confidence,
            summary=evaluation.summary,
            findings=list(evaluation.findings),
            evidence=list(evaluation.evidence),
            errors=list(evaluation.errors),
            contextHash=request.contextHash,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            generatedAt=generated_at,
        )


class StrategyBacktestAgent(InsufficientDataAgent):
    agent_code = AgentCode.STRATEGY_BACKTEST
    run_id_field = "strategyBacktest"
    summary = "因数据质量门禁阻断，未执行策略回测分析。"
    pending_summary = "策略回测规则尚未实现，未执行策略回测分析。"


class AnnouncementRiskAgent(InsufficientDataAgent):
    agent_code = AgentCode.ANNOUNCEMENT_RISK
    run_id_field = "announcementRisk"
    summary = "因数据质量门禁阻断，未执行公告事件风险分析。"
    pending_summary = "公告事件风险规则尚未实现，未执行公告事件风险分析。"


class PositionRiskAgent(InsufficientDataAgent):
    agent_code = AgentCode.POSITION_RISK
    run_id_field = "positionRisk"
    summary = "因数据质量门禁阻断且无真实仓位证据，未执行资金仓位风控分析。"
    pending_summary = "资金仓位风控规则尚未实现且无真实仓位证据，未执行资金仓位风控分析。"
