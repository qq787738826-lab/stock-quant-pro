from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Iterable, Mapping, Sequence

from .models import (
    AgentCode,
    AgentDecision,
    AgentOutput,
    FinalDecisionCode,
    FormalVeto,
    GateStatus,
    RunStatus,
    Severity,
)


RULE_VERSION = "1.4.0-stage-2i-chief-decision-v1"
CONTRACT_VERSION = "CHIEF_DECISION_V1"
WEIGHT_CONTRACT_VERSION = "CHIEF_SCORE_WEIGHTS_V1"
CONTEXT_PROFILE = "AGENT_CONTEXT_2G_V1"

CONTRIBUTOR_ORDER = (
    AgentCode.TECHNICAL_ANALYSIS,
    AgentCode.STRATEGY_BACKTEST,
    AgentCode.ANNOUNCEMENT_RISK,
    AgentCode.POSITION_RISK,
)
SCORE_WEIGHTS: Mapping[AgentCode, int] = {
    AgentCode.TECHNICAL_ANALYSIS: 25,
    AgentCode.STRATEGY_BACKTEST: 35,
    AgentCode.ANNOUNCEMENT_RISK: 20,
    AgentCode.POSITION_RISK: 20,
}
CONFIDENCE_WEIGHTS = SCORE_WEIGHTS

_ANNOUNCEMENT_RISK_FINDINGS = frozenset({
    "ANNOUNCEMENT_REGULATORY_DELISTING_ASSESSED",
    "ANNOUNCEMENT_FINANCIAL_LITIGATION_ASSESSED",
    "ANNOUNCEMENT_OWNERSHIP_OPERATION_ASSESSED",
})
_POSITION_RISK_FINDINGS = frozenset({
    "POSITION_RISK_ACCOUNT_LOSS_ASSESSED",
    "POSITION_RISK_CONCENTRATION_ASSESSED",
    "POSITION_RISK_PENDING_EXPOSURE_ASSESSED",
    "POSITION_RISK_EXIT_THRESHOLDS_ASSESSED",
    "POSITION_RISK_CONTEXT_COMPLETENESS_ASSESSED",
})


class RiskSeverity(str, Enum):
    INFO = "INFO"
    WARN = "WARN"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


@dataclass(frozen=True)
class ChiefDecisionEvaluation:
    decision: FinalDecisionCode
    gate_status: GateStatus
    vetoed: bool
    score: int
    confidence: int
    summary: str
    veto_ids: tuple[str, ...]
    weighted_score_sum: int | None
    weighted_confidence_sum: int | None
    highest_risk_severity: RiskSeverity | None


class ChiefDecisionRuleEngine:

    def evaluate(
        self,
        runs: Sequence[AgentOutput],
        vetoes: Sequence[FormalVeto],
    ) -> ChiefDecisionEvaluation:
        by_code = self._ordered_runs(runs)
        data_quality = by_code[AgentCode.DATA_QUALITY]
        position_risk = by_code[AgentCode.POSITION_RISK]

        if vetoes:
            return self._result(
                FinalDecisionCode.REJECTED_BY_VETO,
                GateStatus.BLOCKED,
                True,
                0,
                position_risk.confidence,
                tuple(veto.vetoId for veto in vetoes),
            )
        if data_quality.gateStatus is GateStatus.BLOCKED:
            return self._result(
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                GateStatus.BLOCKED,
                False,
                0,
                data_quality.confidence,
            )
        if not self._composable(by_code):
            return self._result(
                FinalDecisionCode.INSUFFICIENT_DATA,
                GateStatus.NOT_APPLICABLE,
                False,
                0,
                0,
            )

        weighted_score_sum = sum(
            by_code[code].score * SCORE_WEIGHTS[code]
            for code in CONTRIBUTOR_ORDER
        )
        weighted_confidence_sum = sum(
            by_code[code].confidence * CONFIDENCE_WEIGHTS[code]
            for code in CONTRIBUTOR_ORDER
        )
        score = _half_up_non_negative(weighted_score_sum, 100)
        confidence = _half_up_non_negative(weighted_confidence_sum, 100)
        if data_quality.gateStatus is GateStatus.WARN:
            confidence = min(confidence, 50)
        if position_risk.status is RunStatus.PARTIAL:
            confidence = min(confidence, 50)

        risk = self.highest_risk_severity(
            by_code[AgentCode.ANNOUNCEMENT_RISK],
            position_risk,
        )
        forced_research = (
            data_quality.gateStatus is GateStatus.WARN
            or position_risk.status is RunStatus.PARTIAL
            or risk in (RiskSeverity.HIGH, RiskSeverity.CRITICAL)
            or score < 50
            or confidence < 40
        )
        if forced_research:
            decision = FinalDecisionCode.RESEARCH_ONLY
            gate = GateStatus.WARN
        elif self._manual_review_eligible(by_code, score, confidence, risk):
            decision = FinalDecisionCode.PASS_TO_MANUAL_REVIEW
            gate = GateStatus.PASS
        else:
            decision = FinalDecisionCode.WATCH
            gate = GateStatus.WARN
        return self._result(
            decision,
            gate,
            False,
            score,
            confidence,
            weighted_score_sum=weighted_score_sum,
            weighted_confidence_sum=weighted_confidence_sum,
            highest_risk_severity=risk,
        )

    @staticmethod
    def highest_risk_severity(
        announcement_risk: AgentOutput,
        position_risk: AgentOutput,
    ) -> RiskSeverity:
        values: list[Severity] = []
        values.extend(
            finding.severity
            for finding in announcement_risk.findings
            if finding.code in _ANNOUNCEMENT_RISK_FINDINGS
        )
        values.extend(
            finding.severity
            for finding in position_risk.findings
            if finding.code in _POSITION_RISK_FINDINGS
        )
        if not values:
            return RiskSeverity.INFO
        rank = {
            Severity.INFO: 0,
            Severity.WARN: 1,
            Severity.HIGH: 2,
            Severity.CRITICAL: 3,
        }
        return RiskSeverity(max(values, key=rank.__getitem__).value)

    @staticmethod
    def _ordered_runs(
        runs: Sequence[AgentOutput],
    ) -> dict[AgentCode, AgentOutput]:
        if len(runs) != len(AgentCode):
            raise ValueError("2I requires exactly six professional runs")
        codes = [run.agentCode for run in runs]
        if codes != list(AgentCode) or len(set(codes)) != len(codes):
            raise ValueError("2I professional runs must use the fixed order")
        return {run.agentCode: run for run in runs}

    @staticmethod
    def _composable(by_code: Mapping[AgentCode, AgentOutput]) -> bool:
        dq = by_code[AgentCode.DATA_QUALITY]
        market = by_code[AgentCode.MARKET_REGIME]
        technical = by_code[AgentCode.TECHNICAL_ANALYSIS]
        backtest = by_code[AgentCode.STRATEGY_BACKTEST]
        announcement = by_code[AgentCode.ANNOUNCEMENT_RISK]
        position = by_code[AgentCode.POSITION_RISK]

        if not (
            dq.status is RunStatus.COMPLETED
            and dq.gateStatus in (GateStatus.PASS, GateStatus.WARN)
            and dq.decision in (AgentDecision.PASS, AgentDecision.WARN)
            and not dq.veto
            and dq.confidence == 100
            and not dq.errors
            and dq.evidence
        ):
            return False
        if not (
            market.status is RunStatus.COMPLETED
            and market.confidence == 0
            and not market.veto
            and not market.errors
            and market.findings
            and market.evidence
        ):
            return False
        if not (
            technical.status is RunStatus.COMPLETED
            and technical.gateStatus in (GateStatus.PASS, GateStatus.WARN)
            and technical.decision is AgentDecision.WARN
            and not technical.veto
            and technical.confidence > 0
            and not technical.errors
            and technical.findings
            and technical.evidence
        ):
            return False
        if not (
            backtest.status is RunStatus.COMPLETED
            and backtest.gateStatus in (GateStatus.PASS, GateStatus.WARN)
            and backtest.decision is AgentDecision.WARN
            and not backtest.veto
            and backtest.confidence > 0
            and not backtest.errors
            and backtest.findings
            and backtest.evidence
        ):
            return False
        if not (
            announcement.status is RunStatus.COMPLETED
            and announcement.gateStatus in (GateStatus.PASS, GateStatus.WARN)
            and announcement.decision in (AgentDecision.PASS, AgentDecision.WARN)
            and not announcement.veto
            and announcement.confidence == 40
            and not announcement.errors
            and announcement.findings
            and announcement.evidence
        ):
            return False
        return (
            position.status in (RunStatus.COMPLETED, RunStatus.PARTIAL)
            and position.gateStatus in (GateStatus.PASS, GateStatus.WARN)
            and position.decision in (AgentDecision.PASS, AgentDecision.WARN)
            and not position.veto
            and position.confidence > 0
            and not position.errors
            and bool(position.findings)
            and bool(position.evidence)
        )

    @staticmethod
    def _manual_review_eligible(
        by_code: Mapping[AgentCode, AgentOutput],
        score: int,
        confidence: int,
        risk: RiskSeverity,
    ) -> bool:
        dq = by_code[AgentCode.DATA_QUALITY]
        technical = by_code[AgentCode.TECHNICAL_ANALYSIS]
        backtest = by_code[AgentCode.STRATEGY_BACKTEST]
        announcement = by_code[AgentCode.ANNOUNCEMENT_RISK]
        position = by_code[AgentCode.POSITION_RISK]
        return (
            dq.gateStatus is GateStatus.PASS
            and technical.score >= 60
            and backtest.score >= 60
            and announcement.gateStatus is GateStatus.PASS
            and position.status is RunStatus.COMPLETED
            and position.gateStatus is GateStatus.PASS
            and score >= 70
            and confidence >= 60
            and risk is RiskSeverity.INFO
        )

    @staticmethod
    def _result(
        decision: FinalDecisionCode,
        gate_status: GateStatus,
        vetoed: bool,
        score: int,
        confidence: int,
        veto_ids: tuple[str, ...] = (),
        *,
        weighted_score_sum: int | None = None,
        weighted_confidence_sum: int | None = None,
        highest_risk_severity: RiskSeverity | None = None,
    ) -> ChiefDecisionEvaluation:
        return ChiefDecisionEvaluation(
            decision=decision,
            gate_status=gate_status,
            vetoed=vetoed,
            score=score,
            confidence=confidence,
            summary=summary(decision, score, confidence),
            veto_ids=veto_ids,
            weighted_score_sum=weighted_score_sum,
            weighted_confidence_sum=weighted_confidence_sum,
            highest_risk_severity=highest_risk_severity,
        )


def summary(
    decision: FinalDecisionCode,
    score: int,
    confidence: int,
) -> str:
    return (
        f"{CONTRACT_VERSION} decision={decision.value}; "
        f"compositeScore={score}; compositeConfidence={confidence}; "
        "MARKET_REGIME V1 is informational with score/confidence weight 0; "
        "research or manual review only; no executable action."
    )


def ordered_findings(runs: Iterable[AgentOutput]):
    return [
        finding
        for run in runs
        for finding in run.findings
    ]


def _half_up_non_negative(numerator: int, denominator: int) -> int:
    if numerator < 0 or denominator <= 0:
        raise ValueError("HALF_UP input must be non-negative")
    return (numerator * 2 + denominator) // (denominator * 2)
