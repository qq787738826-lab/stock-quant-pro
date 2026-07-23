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
    AgentOutput,
    AgentTeamRequest,
    AgentTeamResponse,
    FinalDecision,
    FinalDecisionCode,
    GateStatus,
    STAGE_2B_DATA_QUALITY_RULE_VERSION,
    STAGE_2D_MARKET_REGIME_RULE_VERSION,
    STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION,
)


class ChiefDecisionService:
    def decide(
        self,
        request: AgentTeamRequest,
        data_quality: AgentOutput,
        market_regime: AgentOutput,
        technical_analysis: AgentOutput,
        generated_at: datetime,
    ) -> FinalDecision:
        if request.ruleVersion == STAGE_2B_DATA_QUALITY_RULE_VERSION:
            if data_quality.gateStatus is GateStatus.BLOCKED:
                decision = FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                gate_status = GateStatus.BLOCKED
                confidence = data_quality.confidence
                summary = (
                    "DATA_QUALITY上下文无效，总控已按安全门禁阻断。"
                    if data_quality.confidence == 0
                    else "DATA_QUALITY规则发现阻断事实，总控已停止后续分析。"
                )
            else:
                decision = FinalDecisionCode.INSUFFICIENT_DATA
                gate_status = data_quality.gateStatus
                confidence = 0
                summary = "DATA_QUALITY检查未阻断；其余五个专业规则尚未实现，无法形成团队结论。"
            return FinalDecision(
                taskId=request.taskId,
                decision=decision,
                gateStatus=gate_status,
                vetoed=False,
                score=0,
                confidence=confidence,
                summary=summary,
                findings=list(data_quality.findings),
                sourceRunIds=[run_id for _, run_id in request.runIds.ordered()],
                vetoIds=[],
                contextHash=request.contextHash,
                tradeDate=request.tradeDate,
                ruleVersion=request.ruleVersion,
                executionMode=request.executionMode,
                generatedAt=generated_at,
            )
        if request.ruleVersion == STAGE_2D_MARKET_REGIME_RULE_VERSION:
            if data_quality.gateStatus is GateStatus.BLOCKED:
                decision = FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                gate_status = GateStatus.BLOCKED
                confidence = data_quality.confidence
                findings = list(data_quality.findings)
                summary = (
                    "DATA_QUALITY上下文无效，总控已按安全门禁阻断。"
                    if data_quality.confidence == 0
                    else "DATA_QUALITY规则发现阻断事实，总控已停止后续分析。"
                )
            else:
                decision = FinalDecisionCode.INSUFFICIENT_DATA
                gate_status = data_quality.gateStatus
                confidence = 0
                findings = [*data_quality.findings, *market_regime.findings]
                summary = (
                    "DATA_QUALITY检查未阻断，当前证券池市场宽度状态规则已按受限边界执行；"
                    "其余四个专业规则尚未实现，无法形成团队结论。"
                )
            return FinalDecision(
                taskId=request.taskId,
                decision=decision,
                gateStatus=gate_status,
                vetoed=False,
                score=0,
                confidence=confidence,
                summary=summary,
                findings=findings,
                sourceRunIds=[run_id for _, run_id in request.runIds.ordered()],
                vetoIds=[],
                contextHash=request.contextHash,
                tradeDate=request.tradeDate,
                ruleVersion=request.ruleVersion,
                executionMode=request.executionMode,
                generatedAt=generated_at,
            )
        if request.ruleVersion == STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION:
            if data_quality.gateStatus is GateStatus.BLOCKED:
                decision = FinalDecisionCode.BLOCKED_BY_DATA_QUALITY
                gate_status = GateStatus.BLOCKED
                confidence = data_quality.confidence
                findings = list(data_quality.findings)
                summary = (
                    "DATA_QUALITY上下文无效，总控已按安全门禁阻断。"
                    if data_quality.confidence == 0
                    else "DATA_QUALITY规则发现阻断事实，总控已停止后续分析。"
                )
            else:
                decision = FinalDecisionCode.INSUFFICIENT_DATA
                gate_status = data_quality.gateStatus
                confidence = 0
                findings = [
                    *data_quality.findings,
                    *market_regime.findings,
                    *technical_analysis.findings,
                ]
                summary = (
                    "DATA_QUALITY检查未阻断，阶段2D-1市场宽度规则与阶段2E-1"
                    "技术指标确定性规则已按冻结边界执行；其余三个专业规则尚未实现，"
                    "无法形成团队结论。"
                )
            return FinalDecision(
                taskId=request.taskId,
                decision=decision,
                gateStatus=gate_status,
                vetoed=False,
                score=0,
                confidence=confidence,
                summary=summary,
                findings=findings,
                sourceRunIds=[run_id for _, run_id in request.runIds.ordered()],
                vetoIds=[],
                contextHash=request.contextHash,
                tradeDate=request.tradeDate,
                ruleVersion=request.ruleVersion,
                executionMode=request.executionMode,
                generatedAt=generated_at,
            )
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
        data_quality = self._agents[0].analyze(request, generated_at)
        runs = [data_quality]
        runs.extend(
            agent.analyze(request, generated_at, data_quality.gateStatus)
            for agent in self._agents[1:]
        )
        market_regime = runs[1]
        technical_analysis = runs[2]
        evidence = [
            *data_quality.evidence,
            *market_regime.evidence,
            *technical_analysis.evidence,
        ]
        return AgentTeamResponse(
            taskId=request.taskId,
            contextHash=request.contextHash,
            tradeDate=request.tradeDate,
            ruleVersion=request.ruleVersion,
            executionMode=request.executionMode,
            agentRuns=runs,
            evidence=evidence,
            vetoes=[],
            finalDecision=self._chief.decide(
                request,
                data_quality,
                market_regime,
                technical_analysis,
                generated_at,
            ),
            generatedAt=generated_at,
        )
