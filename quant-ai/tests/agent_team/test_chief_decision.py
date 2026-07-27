from __future__ import annotations

import copy
import json
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

from app.agent_team.chief_decision import (
    CONTRACT_VERSION,
    SCORE_WEIGHTS,
    WEIGHT_CONTRACT_VERSION,
    ChiefDecisionRuleEngine,
    RiskSeverity,
    ordered_findings,
)
from app.agent_team.models import (
    AgentCode,
    AgentDecision,
    AgentError,
    AgentOutput,
    Evidence,
    EvidenceCategory,
    EvidenceSourceType,
    Finding,
    FormalVeto,
    GateStatus,
    RunStatus,
    Severity,
    STAGE_2I_CHIEF_DECISION_RULE_VERSION,
)


NOW = datetime(2026, 7, 25, 8, 0, tzinfo=timezone.utc)
TRADE_DATE = date(2026, 7, 25)
HASH = "9" * 64
VECTORS = (
    Path(__file__).resolve().parents[3]
    / "quant-server"
    / "src"
    / "test"
    / "resources"
    / "agent"
    / "chief-decision-v1-vectors.json"
)


def _finding_code(code: AgentCode) -> str:
    if code is AgentCode.ANNOUNCEMENT_RISK:
        return "ANNOUNCEMENT_REGULATORY_DELISTING_ASSESSED"
    if code is AgentCode.POSITION_RISK:
        return "POSITION_RISK_ACCOUNT_LOSS_ASSESSED"
    return f"{code.value}_TEST_FINDING"


def _run(raw: dict, index: int) -> AgentOutput:
    code = AgentCode(raw["agentCode"])
    status = RunStatus(raw["status"])
    evidence_id = f"chief-vector-evidence-{index}"
    evidence = []
    findings = []
    errors = []
    if status in (RunStatus.COMPLETED, RunStatus.PARTIAL):
        evidence = [Evidence(
            evidenceId=evidence_id,
            category=EvidenceCategory.QUERY_RESULT,
            sourceType=EvidenceSourceType.JAVA_ENGINE,
            sourceName="ChiefDecisionVectorFixture",
            sourceRef=f"agentRuns[{index}]",
            symbol="600000",
            tradeDate=TRADE_DATE,
            observedAt=NOW,
            collectedAt=NOW,
            fields={"fixture": code.value},
            contentHash=HASH,
        )]
        findings = [Finding(
            findingId=f"chief-vector-finding-{index}",
            code=_finding_code(code),
            severity=Severity(raw["riskSeverity"]),
            title=f"{code.value} vector finding",
            detail="fixed cross-language golden vector",
            evidenceIds=[evidence_id],
        )]
    elif status is RunStatus.INSUFFICIENT_DATA:
        errors = [AgentError(code=f"{code.value}_UNAVAILABLE", message="fixture")]
    return AgentOutput(
        taskId=77,
        runId=101 + index,
        agentCode=code,
        status=status,
        gateStatus=GateStatus(raw["gateStatus"]),
        decision=AgentDecision(raw["decision"]),
        veto=raw["veto"],
        score=raw["score"],
        confidence=raw["confidence"],
        summary=f"{code.value} fixed vector",
        findings=findings,
        evidence=evidence,
        errors=errors,
        contextHash=HASH,
        ruleVersion=STAGE_2I_CHIEF_DECISION_RULE_VERSION,
        executionMode="LOCAL_RULES",
        generatedAt=NOW,
    )


def _veto(value: str) -> FormalVeto:
    return FormalVeto(
        vetoId=value,
        taskId=77,
        runId=106,
        agentCode=AgentCode.POSITION_RISK,
        vetoCode="POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
        reason="fixed vector veto",
        evidenceIds=["chief-vector-evidence-5"],
        createdAt=NOW,
    )


def _golden() -> dict:
    return json.loads(VECTORS.read_text(encoding="utf-8"))


def _pass_runs() -> list[AgentOutput]:
    vector = _golden()["vectors"][0]
    return [_run(value, index) for index, value in enumerate(vector["runs"])]


def _replace(
    runs: list[AgentOutput],
    code: AgentCode,
    **changes,
) -> list[AgentOutput]:
    values = list(runs)
    index = list(AgentCode).index(code)
    values[index] = values[index].model_copy(update=changes)
    return values


class ChiefDecisionGoldenVectorTest(unittest.TestCase):

    def setUp(self) -> None:
        self.engine = ChiefDecisionRuleEngine()

    def test_shared_golden_vectors(self) -> None:
        fixture = _golden()
        self.assertEqual(CONTRACT_VERSION, fixture["contractVersion"])
        self.assertEqual(
            WEIGHT_CONTRACT_VERSION,
            fixture["weightContractVersion"],
        )
        self.assertEqual(
            {code.value: weight for code, weight in SCORE_WEIGHTS.items()},
            fixture["weights"],
        )
        self.assertEqual(100, sum(SCORE_WEIGHTS.values()))

        for vector in fixture["vectors"]:
            with self.subTest(vector=vector["name"]):
                runs = [
                    _run(value, index)
                    for index, value in enumerate(vector["runs"])
                ]
                vetoes = [_veto(value) for value in vector["vetoIds"]]
                actual = self.engine.evaluate(runs, vetoes)
                expected = vector["expected"]
                self.assertEqual(expected["decision"], actual.decision.value)
                self.assertEqual(expected["gateStatus"], actual.gate_status.value)
                self.assertEqual(expected["vetoed"], actual.vetoed)
                self.assertEqual(expected["score"], actual.score)
                self.assertEqual(expected["confidence"], actual.confidence)
                self.assertEqual(expected["summary"], actual.summary)
                self.assertEqual(
                    expected["weightedScoreSum"],
                    actual.weighted_score_sum,
                )
                self.assertEqual(
                    expected["weightedConfidenceSum"],
                    actual.weighted_confidence_sum,
                )
                self.assertEqual(
                    expected["highestRiskSeverity"],
                    None if actual.highest_risk_severity is None
                    else actual.highest_risk_severity.value,
                )


class ChiefDecisionBoundaryTest(unittest.TestCase):

    def setUp(self) -> None:
        self.engine = ChiefDecisionRuleEngine()

    def evaluate(self, runs: list[AgentOutput]):
        return self.engine.evaluate(runs, [])

    def test_market_regime_score_and_confidence_have_zero_weight(self) -> None:
        runs = _pass_runs()
        baseline = self.evaluate(runs)
        changed = self.evaluate(_replace(
            runs,
            AgentCode.MARKET_REGIME,
            score=100,
            confidence=0,
        ))
        self.assertEqual(baseline, changed)

    def test_score_boundaries_49_50_69_and_70(self) -> None:
        runs = _pass_runs()
        cases = (
            ((40, 40, 50, 75), 49, "RESEARCH_ONLY"),
            ((50, 50, 50, 50), 50, "WATCH"),
            ((60, 60, 100, 65), 69, "WATCH"),
            ((70, 70, 70, 70), 70, "PASS_TO_MANUAL_REVIEW"),
        )
        for scores, expected_score, decision in cases:
            with self.subTest(score=expected_score):
                changed = list(runs)
                for code, score in zip(
                    (
                        AgentCode.TECHNICAL_ANALYSIS,
                        AgentCode.STRATEGY_BACKTEST,
                        AgentCode.ANNOUNCEMENT_RISK,
                        AgentCode.POSITION_RISK,
                    ),
                    scores,
                    strict=True,
                ):
                    changed = _replace(changed, code, score=score)
                actual = self.evaluate(changed)
                self.assertEqual(expected_score, actual.score)
                self.assertEqual(decision, actual.decision.value)

        low_technical = _replace(
            runs,
            AgentCode.TECHNICAL_ANALYSIS,
            score=59,
        )
        self.assertEqual(
            "WATCH",
            self.evaluate(low_technical).decision.value,
        )

    def test_confidence_boundaries_39_40_59_and_60(self) -> None:
        cases = (
            ((40, 40, 40, 35), 39, "RESEARCH_ONLY"),
            ((40, 40, 40, 40), 40, "WATCH"),
            ((60, 60, 40, 75), 59, "WATCH"),
            ((60, 60, 40, 80), 60, "PASS_TO_MANUAL_REVIEW"),
        )
        for confidences, expected, decision in cases:
            with self.subTest(confidence=expected):
                runs = _pass_runs()
                for code, confidence in zip(
                    (
                        AgentCode.TECHNICAL_ANALYSIS,
                        AgentCode.STRATEGY_BACKTEST,
                        AgentCode.ANNOUNCEMENT_RISK,
                        AgentCode.POSITION_RISK,
                    ),
                    confidences,
                    strict=True,
                ):
                    runs = _replace(runs, code, confidence=confidence)
                actual = self.evaluate(runs)
                self.assertEqual(expected, actual.confidence)
                self.assertEqual(decision, actual.decision.value)

    def test_risk_info_warn_high_and_critical(self) -> None:
        expected = {
            Severity.INFO: "PASS_TO_MANUAL_REVIEW",
            Severity.WARN: "WATCH",
            Severity.HIGH: "RESEARCH_ONLY",
            Severity.CRITICAL: "RESEARCH_ONLY",
        }
        for severity, decision in expected.items():
            with self.subTest(severity=severity):
                runs = _pass_runs()
                announcement = runs[4]
                finding = announcement.findings[0].model_copy(
                    update={"severity": severity}
                )
                runs = _replace(
                    runs,
                    AgentCode.ANNOUNCEMENT_RISK,
                    findings=[finding],
                    gateStatus=(
                        GateStatus.PASS if severity is Severity.INFO
                        else GateStatus.WARN
                    ),
                    decision=(
                        AgentDecision.PASS if severity is Severity.INFO
                        else AgentDecision.WARN
                    ),
                )
                actual = self.evaluate(runs)
                self.assertEqual(severity.value, actual.highest_risk_severity.value)
                self.assertEqual(decision, actual.decision.value)

    def test_dq_warn_and_position_partial_cap_confidence_and_research(self) -> None:
        runs = _pass_runs()
        warned = _replace(
            runs,
            AgentCode.DATA_QUALITY,
            gateStatus=GateStatus.WARN,
            decision=AgentDecision.WARN,
        )
        actual = self.evaluate(warned)
        self.assertEqual(50, actual.confidence)
        self.assertEqual("RESEARCH_ONLY", actual.decision.value)

        partial = _replace(
            runs,
            AgentCode.POSITION_RISK,
            status=RunStatus.PARTIAL,
            gateStatus=GateStatus.WARN,
            decision=AgentDecision.WARN,
        )
        actual = self.evaluate(partial)
        self.assertEqual(50, actual.confidence)
        self.assertEqual("RESEARCH_ONLY", actual.decision.value)

    def test_each_required_run_can_force_insufficient_data(self) -> None:
        for code in (
            AgentCode.MARKET_REGIME,
            AgentCode.TECHNICAL_ANALYSIS,
            AgentCode.STRATEGY_BACKTEST,
            AgentCode.ANNOUNCEMENT_RISK,
            AgentCode.POSITION_RISK,
        ):
            with self.subTest(code=code):
                runs = _replace(
                    _pass_runs(),
                    code,
                    status=RunStatus.INSUFFICIENT_DATA,
                    gateStatus=GateStatus.NOT_APPLICABLE,
                    decision=AgentDecision.NOT_APPLICABLE,
                    score=0,
                    confidence=0,
                    findings=[],
                    evidence=[],
                    errors=[AgentError(code="UNAVAILABLE", message="fixture")],
                )
                actual = self.evaluate(runs)
                self.assertEqual("INSUFFICIENT_DATA", actual.decision.value)
                self.assertEqual((0, 0), (actual.score, actual.confidence))

    def test_veto_precedes_dq_block_and_missing_run(self) -> None:
        vector = _golden()["vectors"][5]
        runs = [
            _run(value, index)
            for index, value in enumerate(vector["runs"])
        ]
        actual = self.engine.evaluate(
            runs,
            [_veto("position-risk-veto-01")],
        )
        self.assertEqual("REJECTED_BY_VETO", actual.decision.value)
        self.assertEqual(("position-risk-veto-01",), actual.veto_ids)

    def test_findings_are_concatenated_in_fixed_run_order(self) -> None:
        runs = _pass_runs()
        self.assertEqual(
            [run.findings[0].findingId for run in runs],
            [finding.findingId for finding in ordered_findings(runs)],
        )

    def test_summary_uses_fixed_safe_template(self) -> None:
        summary = self.evaluate(_pass_runs()).summary
        self.assertIn("MARKET_REGIME V1", summary)
        self.assertIn("research or manual review only", summary)
        for forbidden in (
            "立即买入", "立即卖出", "自动下单", "清仓", "加仓", "减仓",
            "保证收益", "必涨", "必跌",
        ):
            self.assertNotIn(forbidden, summary)

    def test_fixed_order_is_required(self) -> None:
        runs = _pass_runs()
        with self.assertRaises(ValueError):
            self.engine.evaluate(list(reversed(runs)), [])


if __name__ == "__main__":
    unittest.main()
