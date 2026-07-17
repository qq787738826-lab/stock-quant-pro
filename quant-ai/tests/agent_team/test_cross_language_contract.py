from __future__ import annotations

import copy
import json
import socket
import sqlite3
import unittest
from datetime import timezone
from pathlib import Path
from unittest.mock import patch

from pydantic import ValidationError

from app.agent_team.models import AgentTeamRequest, AgentTeamResponse
from app.agent_team.orchestrator import AgentTeamOrchestrator


FIXTURE_DIR = (Path(__file__).resolve().parents[3]
               / "quant-server" / "src" / "test" / "resources" / "agent-team-contract")
STAGE_2B_RESPONSE_FIXTURES = {
    "stage-2b-invalid-context-response.json": (
        "INSUFFICIENT_DATA", "BLOCKED", "REJECT", 0, 0,
        "BLOCKED_BY_DATA_QUALITY", "BLOCKED",
    ),
    "stage-2b-blocked-response.json": (
        "COMPLETED", "BLOCKED", "REJECT", 0, 100,
        "BLOCKED_BY_DATA_QUALITY", "BLOCKED",
    ),
    "stage-2b-warn-response.json": (
        "COMPLETED", "WARN", "WARN", 50, 100,
        "INSUFFICIENT_DATA", "WARN",
    ),
    "stage-2b-pass-response.json": (
        "COMPLETED", "PASS", "PASS", 100, 100,
        "INSUFFICIENT_DATA", "PASS",
    ),
}


def fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


def without_generated_at(value):
    if isinstance(value, dict):
        return {key: without_generated_at(item) for key, item in value.items()
                if key != "generatedAt"}
    if isinstance(value, list):
        return [without_generated_at(item) for item in value]
    return value


class CrossLanguageContractTest(unittest.TestCase):
    def test_stage_2b_shared_fixtures_cover_all_frozen_mappings(self):
        request_payload = fixture("stage-2b-valid-request.json")
        request = AgentTeamRequest.model_validate(request_payload)
        self.assertEqual("1.4.0-stage-2b-dq-v1", request.ruleVersion)
        expected_fields = {
            key: request_payload["contextSnapshot"][key]
            for key in ("security", "marketData", "technicalMetrics", "dataQualityContext")
        }

        for name, expected in STAGE_2B_RESPONSE_FIXTURES.items():
            with self.subTest(name=name):
                response = AgentTeamResponse.model_validate(fixture(name))
                data_quality = response.agentRuns[0]
                actual = (
                    data_quality.status.value,
                    data_quality.gateStatus.value,
                    data_quality.decision.value,
                    data_quality.score,
                    data_quality.confidence,
                    response.finalDecision.decision.value,
                    response.finalDecision.gateStatus.value,
                )
                self.assertEqual(expected, actual)
                self.assertFalse(data_quality.veto)
                self.assertEqual([], response.vetoes)
                if data_quality.status.value == "COMPLETED":
                    self.assertEqual(1, len(response.evidence))
                    self.assertEqual(response.evidence, data_quality.evidence)
                    self.assertEqual(expected_fields, response.evidence[0].fields)
                    evidence_ids = {item.evidenceId for item in response.evidence}
                    for finding in data_quality.findings:
                        self.assertTrue(set(finding.evidenceIds) <= evidence_ids)
                else:
                    self.assertEqual([], response.evidence)
                    self.assertEqual([], data_quality.evidence)

    def test_stage_2b_pass_fixture_matches_deterministic_orchestrator(self):
        request = AgentTeamRequest.model_validate(fixture("stage-2b-valid-request.json"))
        expected = fixture("stage-2b-pass-response.json")
        with patch.object(socket.socket, "connect", side_effect=AssertionError("network called")), \
                patch.object(sqlite3, "connect", side_effect=AssertionError("database called")):
            actual = AgentTeamOrchestrator().analyze(request).model_dump(mode="json")
        self.assertEqual(without_generated_at(expected), without_generated_at(actual))

    def test_shared_request_and_response_are_accepted_by_pydantic(self):
        request = AgentTeamRequest.model_validate(fixture("valid-agent-team-request.json"))
        response = AgentTeamResponse.model_validate(fixture("valid-agent-team-response.json"))
        self.assertEqual(77, request.taskId)
        self.assertEqual("a" * 64, request.contextHash)
        self.assertEqual([101, 102, 103, 104, 105, 106],
                         [run_id for _, run_id in request.runIds.ordered()])
        self.assertEqual(6, len(response.agentRuns))
        self.assertIsNotNone(request.requestedAt.utcoffset())
        expected = response.generatedAt.astimezone(timezone.utc)
        self.assertEqual("2026-07-14T05:02:00+00:00", expected.isoformat())
        for run in response.agentRuns:
            self.assertEqual(expected, run.generatedAt.astimezone(timezone.utc))
        self.assertEqual(expected, response.finalDecision.generatedAt.astimezone(timezone.utc))

    def test_orchestrator_core_matches_shared_response_except_generated_times(self):
        request = AgentTeamRequest.model_validate(fixture("valid-agent-team-request.json"))
        expected = fixture("valid-agent-team-response.json")
        with patch.object(socket.socket, "connect", side_effect=AssertionError("network called")), \
                patch.object(sqlite3, "connect", side_effect=AssertionError("database called")):
            actual = AgentTeamOrchestrator().analyze(request).model_dump(mode="json")
        self.assertEqual(without_generated_at(expected), without_generated_at(actual))

        codes = [run["agentCode"] for run in actual["agentRuns"]]
        run_ids = [run["runId"] for run in actual["agentRuns"]]
        self.assertEqual([
            "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
            "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK",
        ], codes)
        self.assertEqual([101, 102, 103, 104, 105, 106], run_ids)
        self.assertNotIn("CHIEF_DECISION", codes)
        self.assertEqual(run_ids, actual["finalDecision"]["sourceRunIds"])
        self.assertNotIn(actual["finalDecision"]["decision"],
                         {"BUY", "SELL", "AUTO_BUY", "AUTO_SELL"})

    def test_invalid_enum_duplicate_run_id_no_timezone_and_unknown_field_are_rejected(self):
        base = fixture("valid-agent-team-request.json")
        invalid_values = []

        invalid_enum = copy.deepcopy(base)
        invalid_enum["executionMode"] = "PAID_MODEL"
        invalid_values.append(invalid_enum)

        duplicate = copy.deepcopy(base)
        duplicate["runIds"]["positionRisk"] = duplicate["runIds"]["dataQuality"]
        invalid_values.append(duplicate)

        no_timezone = copy.deepcopy(base)
        no_timezone["requestedAt"] = "2026-07-14T05:01:00"
        invalid_values.append(no_timezone)

        unknown = copy.deepcopy(base)
        unknown["unknownField"] = True
        invalid_values.append(unknown)

        for payload in invalid_values:
            with self.subTest(payload=payload):
                with self.assertRaises(ValidationError):
                    AgentTeamRequest.model_validate(payload)


if __name__ == "__main__":
    unittest.main()
