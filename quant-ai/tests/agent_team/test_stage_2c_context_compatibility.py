from __future__ import annotations

import json
import socket
import sqlite3
import unittest
from pathlib import Path
from unittest.mock import patch

from app.agent_team.models import AgentTeamRequest
from app.agent_team.orchestrator import AgentTeamOrchestrator


FIXTURE_DIR = (Path(__file__).resolve().parents[3] / "quant-server" / "src" / "test"
               / "resources" / "agent-team-contract")


class Stage2CContextCompatibilityTest(unittest.TestCase):
    def test_new_research_sections_do_not_change_stage_2b_rules_or_evidence(self):
        original = json.loads((FIXTURE_DIR / "stage-2b-valid-request.json").read_text(encoding="utf-8"))
        stage_2c = json.loads((FIXTURE_DIR / "stage-2c-valid-request.json").read_text(encoding="utf-8"))
        request_2c = AgentTeamRequest.model_validate(stage_2c)
        self.assertEqual(stage_2c["contextHash"], request_2c.contextHash)
        with patch.object(socket.socket, "connect", side_effect=AssertionError("network called")), \
                patch.object(sqlite3, "connect", side_effect=AssertionError("database called")):
            before = AgentTeamOrchestrator().analyze(AgentTeamRequest.model_validate(original))
            after = AgentTeamOrchestrator().analyze(AgentTeamRequest.model_validate(stage_2c))
        def run_semantics(run):
            return (run.agentCode, run.status, run.gateStatus, run.decision, run.veto,
                    run.score, run.confidence, run.summary,
                    [(item.code, item.severity, item.evidenceIds) for item in run.findings])
        self.assertEqual([run_semantics(item) for item in before.agentRuns],
                         [run_semantics(item) for item in after.agentRuns])
        self.assertEqual(before.evidence[0].fields, after.evidence[0].fields)
        self.assertEqual(before.finalDecision.decision, after.finalDecision.decision)
        self.assertEqual(before.finalDecision.gateStatus, after.finalDecision.gateStatus)
        self.assertEqual(before.finalDecision.score, after.finalDecision.score)
        self.assertEqual(before.finalDecision.confidence, after.finalDecision.confidence)
        self.assertEqual(1, len(after.evidence))
        fields = after.evidence[0].fields
        self.assertEqual({"security", "marketData", "technicalMetrics", "dataQualityContext"}, set(fields))


if __name__ == "__main__":
    unittest.main()
