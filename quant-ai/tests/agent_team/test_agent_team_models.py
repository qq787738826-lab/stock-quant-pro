from __future__ import annotations

import unittest
from datetime import date, datetime, timezone

from pydantic import ValidationError

from app.agent_team.models import (
    AgentCode,
    AgentDecision,
    AgentOutput,
    FinalDecision,
    FinalDecisionCode,
    Finding,
    FormalVeto,
    GateStatus,
    RunStatus,
    Severity,
)


NOW = datetime(2026, 7, 14, 5, 0, tzinfo=timezone.utc)
HASH = "a" * 64


def output(**changes) -> AgentOutput:
    values = {
        "taskId": 77,
        "runId": 106,
        "agentCode": AgentCode.POSITION_RISK,
        "status": RunStatus.INSUFFICIENT_DATA,
        "gateStatus": GateStatus.NOT_APPLICABLE,
        "decision": AgentDecision.NOT_APPLICABLE,
        "veto": False,
        "score": 0,
        "confidence": 0,
        "summary": "数据不足",
        "findings": [],
        "evidence": [],
        "errors": [],
        "contextHash": HASH,
        "ruleVersion": "local-rules-1",
        "executionMode": "LOCAL_RULES",
        "generatedAt": NOW,
    }
    values.update(changes)
    return AgentOutput.model_validate(values)


def decision(**changes) -> FinalDecision:
    values = {
        "taskId": 77,
        "decision": FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
        "gateStatus": GateStatus.BLOCKED,
        "vetoed": False,
        "score": 0,
        "confidence": 0,
        "summary": "数据不足",
        "findings": [],
        "sourceRunIds": [101, 102, 103, 104, 105, 106],
        "vetoIds": [],
        "contextHash": HASH,
        "tradeDate": date(2026, 7, 14),
        "ruleVersion": "local-rules-1",
        "executionMode": "LOCAL_RULES",
        "generatedAt": NOW,
    }
    values.update(changes)
    return FinalDecision.model_validate(values)


class UniqueArrayContractTest(unittest.TestCase):
    def test_finding_rejects_duplicate_evidence_ids(self):
        with self.assertRaises(ValidationError):
            Finding(
                findingId="f-1", code="DATA_GAP", severity=Severity.WARN,
                title="缺少数据", detail="上下文缺少数据", evidenceIds=["e-1", "e-1"],
            )

    def test_formal_veto_rejects_duplicate_evidence_ids(self):
        with self.assertRaises(ValidationError):
            FormalVeto(
                vetoId="v-1", taskId=77, runId=106, agentCode=AgentCode.POSITION_RISK,
                vetoCode="POSITION_LIMIT", reason="超过限制",
                evidenceIds=["e-1", "e-1"], createdAt=NOW,
            )

    def test_final_decision_rejects_duplicate_source_run_ids(self):
        with self.assertRaises(ValidationError):
            decision(sourceRunIds=[101, 102, 103, 104, 105, 105])

    def test_final_decision_rejects_duplicate_veto_ids(self):
        with self.assertRaises(ValidationError):
            decision(vetoIds=["v-1", "v-1"])

    def test_unique_arrays_preserve_list_type_and_order(self):
        finding = Finding(
            findingId="f-1", code="DATA_GAP", severity=Severity.WARN,
            title="缺少数据", detail="上下文缺少数据", evidenceIds=["e-2", "e-1"],
        )
        veto = FormalVeto(
            vetoId="v-1", taskId=77, runId=106, agentCode=AgentCode.POSITION_RISK,
            vetoCode="POSITION_LIMIT", reason="超过限制",
            evidenceIds=["e-2", "e-1"], createdAt=NOW,
        )
        final = decision(sourceRunIds=[106, 105, 104, 103, 102, 101], vetoIds=["v-2", "v-1"])
        self.assertEqual(["e-2", "e-1"], finding.evidenceIds)
        self.assertEqual(["e-2", "e-1"], veto.evidenceIds)
        self.assertEqual([106, 105, 104, 103, 102, 101], final.sourceRunIds)
        self.assertEqual(["v-2", "v-1"], final.vetoIds)


class AgentOutputVetoContractTest(unittest.TestCase):
    def test_false_veto_is_allowed_for_every_professional_agent(self):
        for code in AgentCode:
            with self.subTest(agent_code=code):
                self.assertFalse(output(agentCode=code).veto)

    def test_non_position_agents_cannot_veto(self):
        for code in AgentCode:
            if code is AgentCode.POSITION_RISK:
                continue
            with self.subTest(agent_code=code):
                with self.assertRaises(ValidationError):
                    output(
                        agentCode=code, status=RunStatus.COMPLETED,
                        decision=AgentDecision.REJECT, veto=True,
                    )

    def test_position_risk_insufficient_data_cannot_veto(self):
        with self.assertRaises(ValidationError):
            output(
                status=RunStatus.INSUFFICIENT_DATA,
                decision=AgentDecision.REJECT,
                veto=True,
            )

    def test_position_risk_pass_cannot_veto(self):
        with self.assertRaises(ValidationError):
            output(
                status=RunStatus.COMPLETED,
                decision=AgentDecision.PASS,
                veto=True,
            )

    def test_position_risk_completed_or_partial_reject_can_veto(self):
        for status in (RunStatus.COMPLETED, RunStatus.PARTIAL):
            with self.subTest(status=status):
                self.assertTrue(output(
                    status=status,
                    decision=AgentDecision.REJECT,
                    veto=True,
                ).veto)


if __name__ == "__main__":
    unittest.main()
