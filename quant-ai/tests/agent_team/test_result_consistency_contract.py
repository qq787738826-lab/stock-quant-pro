from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from pydantic import ValidationError

from app.agent_team.models import AgentTeamResponse


FIXTURE_DIR = (Path(__file__).resolve().parents[3]
               / "quant-server" / "src" / "test" / "resources" / "agent-team-contract")


def fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


class ResultConsistencyContractTest(unittest.TestCase):
    def test_all_three_shared_legal_scenarios_pass_and_remain_camel_case(self):
        for name in (
            "valid-agent-team-response.json",
            "valid-agent-team-evidence-response.json",
            "valid-agent-team-veto-response.json",
        ):
            with self.subTest(name=name):
                response = AgentTeamResponse.model_validate(fixture(name))
                serialized = response.model_dump(mode="json")
                self.assertIn("agentRuns", serialized)
                self.assertIn("sourceRunIds", serialized["finalDecision"])
                self.assertNotIn("agent_runs", serialized)

    def test_evidence_references_and_uniqueness_are_enforced(self):
        cases = []

        unknown_finding = fixture("valid-agent-team-evidence-response.json")
        unknown_finding["agentRuns"][0]["findings"][0]["evidenceIds"] = ["missing-evidence"]
        cases.append(unknown_finding)

        unknown_veto_evidence = fixture("valid-agent-team-veto-response.json")
        unknown_veto_evidence["vetoes"][0]["evidenceIds"] = ["missing-evidence"]
        cases.append(unknown_veto_evidence)

        duplicate_evidence = fixture("valid-agent-team-evidence-response.json")
        duplicate_evidence["evidence"].append(copy.deepcopy(duplicate_evidence["evidence"][0]))
        cases.append(duplicate_evidence)

        duplicate_finding_reference = fixture("valid-agent-team-evidence-response.json")
        duplicate_finding_reference["agentRuns"][0]["findings"][0]["evidenceIds"] = ["e-market", "e-market"]
        cases.append(duplicate_finding_reference)

        for payload in cases:
            with self.subTest(case=len(payload.get("evidence", []))):
                self.assert_invalid(payload)

    def test_exact_agent_evidence_subset_passes_but_collected_at_conflict_is_rejected(self):
        valid = fixture("valid-agent-team-evidence-response.json")
        valid["agentRuns"][0]["evidence"] = [copy.deepcopy(valid["evidence"][0])]
        response = AgentTeamResponse.model_validate(valid)
        self.assertEqual(3, len(response.evidence))
        self.assertEqual("e-market", response.agentRuns[0].evidence[0].evidenceId)

        conflict = fixture("valid-agent-team-evidence-response.json")
        subset = copy.deepcopy(conflict["evidence"][0])
        subset["collectedAt"] = "2026-07-14T05:01:01Z"
        conflict["agentRuns"][0]["evidence"] = [subset]
        self.assert_invalid(conflict)

    def test_only_position_risk_can_own_a_consistent_formal_veto(self):
        cases = []

        non_position_output = fixture("valid-agent-team-evidence-response.json")
        non_position_output["agentRuns"][1].update(veto=True, decision="REJECT")
        cases.append(non_position_output)

        non_position_formal = fixture("valid-agent-team-veto-response.json")
        non_position_formal["vetoes"][0].update(agentCode="ANNOUNCEMENT_RISK", runId=105)
        cases.append(non_position_formal)

        output_without_formal = fixture("valid-agent-team-veto-response.json")
        output_without_formal["vetoes"] = []
        cases.append(output_without_formal)

        formal_without_output = fixture("valid-agent-team-veto-response.json")
        formal_without_output["agentRuns"][5]["veto"] = False
        cases.append(formal_without_output)

        wrong_run = fixture("valid-agent-team-veto-response.json")
        wrong_run["vetoes"][0]["runId"] = 105
        cases.append(wrong_run)

        data_quality_formal = fixture("valid-agent-team-veto-response.json")
        data_quality_formal["vetoes"][0].update(agentCode="DATA_QUALITY", runId=101)
        cases.append(data_quality_formal)

        for payload in cases:
            with self.subTest(agent_code=payload["vetoes"][0]["agentCode"] if payload["vetoes"] else "NONE"):
                self.assert_invalid(payload)

    def test_final_decision_must_exactly_represent_runs_and_vetoes(self):
        cases = []

        vetoed_without_veto = fixture("valid-agent-team-evidence-response.json")
        vetoed_without_veto["finalDecision"]["vetoed"] = True
        cases.append(vetoed_without_veto)

        veto_not_inherited = fixture("valid-agent-team-veto-response.json")
        veto_not_inherited["finalDecision"]["vetoed"] = False
        cases.append(veto_not_inherited)

        wrong_veto_decision = fixture("valid-agent-team-veto-response.json")
        wrong_veto_decision["finalDecision"]["decision"] = "WATCH"
        cases.append(wrong_veto_decision)

        rejected_without_veto = fixture("valid-agent-team-evidence-response.json")
        rejected_without_veto["finalDecision"]["decision"] = "REJECTED_BY_VETO"
        cases.append(rejected_without_veto)

        missing_run = fixture("valid-agent-team-evidence-response.json")
        missing_run["finalDecision"]["sourceRunIds"].pop()
        cases.append(missing_run)

        duplicate_run = fixture("valid-agent-team-evidence-response.json")
        duplicate_run["finalDecision"]["sourceRunIds"][-1] = 105
        cases.append(duplicate_run)

        unknown_run = fixture("valid-agent-team-evidence-response.json")
        unknown_run["finalDecision"]["sourceRunIds"][-1] = 999
        cases.append(unknown_run)

        unknown_veto = fixture("valid-agent-team-veto-response.json")
        unknown_veto["finalDecision"]["vetoIds"] = ["unknown-veto"]
        cases.append(unknown_veto)

        omitted_veto = fixture("valid-agent-team-veto-response.json")
        omitted_veto["finalDecision"]["vetoIds"] = []
        cases.append(omitted_veto)

        for payload in cases:
            with self.subTest(decision=payload["finalDecision"]["decision"]):
                self.assert_invalid(payload)

    def test_score_and_confidence_boundaries_match_the_frozen_contract(self):
        legal = AgentTeamResponse.model_validate(fixture("valid-agent-team-evidence-response.json"))
        self.assertEqual(100, legal.agentRuns[0].score)
        self.assertEqual(0, legal.agentRuns[5].score)

        for field in ("score", "confidence"):
            for value in (-1, 101):
                payload = fixture("valid-agent-team-evidence-response.json")
                payload["agentRuns"][0][field] = value
                with self.subTest(field=field, value=value):
                    self.assert_invalid(payload)

    def test_identity_and_non_blank_logical_ids_are_enforced(self):
        wrong_final_task = fixture("valid-agent-team-evidence-response.json")
        wrong_final_task["finalDecision"]["taskId"] = 78
        self.assert_invalid(wrong_final_task)

        blank_evidence = fixture("valid-agent-team-evidence-response.json")
        blank_evidence["evidence"][0]["evidenceId"] = "   "
        self.assert_invalid(blank_evidence)

        blank_veto = fixture("valid-agent-team-veto-response.json")
        blank_veto["vetoes"][0]["reason"] = "   "
        self.assert_invalid(blank_veto)

    def test_data_quality_block_remains_non_veto_default_behavior(self):
        response = AgentTeamResponse.model_validate(fixture("valid-agent-team-response.json"))
        data_quality = response.agentRuns[0]
        self.assertEqual("BLOCKED", data_quality.gateStatus.value)
        self.assertFalse(data_quality.veto)
        self.assertEqual([], response.vetoes)
        self.assertFalse(response.finalDecision.vetoed)
        self.assertEqual("BLOCKED_BY_DATA_QUALITY", response.finalDecision.decision.value)

    def test_data_quality_block_rejects_warn_or_pass_final_gate_status(self):
        for gate_status in ("WARN", "PASS"):
            payload = fixture("valid-agent-team-response.json")
            payload["finalDecision"]["gateStatus"] = gate_status
            with self.subTest(gate_status=gate_status):
                with self.assertRaises(ValidationError) as raised:
                    AgentTeamResponse.model_validate(payload)
                self.assertIn("数据质量阻断", str(raised.exception))

    def assert_invalid(self, payload: dict) -> None:
        with self.assertRaises(ValidationError):
            AgentTeamResponse.model_validate(payload)


if __name__ == "__main__":
    unittest.main()
