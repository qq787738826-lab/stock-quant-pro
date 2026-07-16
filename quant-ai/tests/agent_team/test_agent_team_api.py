from __future__ import annotations

import copy
import socket
import sqlite3
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.agent_team.models import AgentTeamRequest
from app.agent_team.orchestrator import AgentTeamOrchestrator


RUN_IDS = {
    "dataQuality": 101,
    "marketRegime": 102,
    "technicalAnalysis": 103,
    "strategyBacktest": 104,
    "announcementRisk": 105,
    "positionRisk": 106,
}
AGENT_CODES = [
    "DATA_QUALITY",
    "MARKET_REGIME",
    "TECHNICAL_ANALYSIS",
    "STRATEGY_BACKTEST",
    "ANNOUNCEMENT_RISK",
    "POSITION_RISK",
]


def valid_request() -> dict:
    unavailable = {
        "available": False,
        "reason": "该只读上下文尚未接入现有业务数据源",
        "queriedAt": "2026-07-14T05:00:00Z",
        "queryScope": {},
    }
    return {
        "schemaVersion": "1.0",
        "taskId": 77,
        "runIds": dict(RUN_IDS),
        "symbol": "600000",
        "tradeDate": "2026-07-14",
        "contextHash": "a" * 64,
        "contextSchemaVersion": "1.0",
        "ruleVersion": "local-rules-1",
        "executionMode": "LOCAL_RULES",
        "contextSnapshot": {
            "security": copy.deepcopy(unavailable),
            "marketData": copy.deepcopy(unavailable),
            "marketBreadth": copy.deepcopy(unavailable),
            "scanResult": copy.deepcopy(unavailable),
            "technicalMetrics": copy.deepcopy(unavailable),
            "backtestContext": copy.deepcopy(unavailable),
            "securityEvents": copy.deepcopy(unavailable),
            "portfolioContext": copy.deepcopy(unavailable),
            "dataQualityContext": copy.deepcopy(unavailable),
        },
        "requestedAt": "2026-07-14T05:01:00Z",
    }


def strip_generated_at(value):
    if isinstance(value, dict):
        return {key: strip_generated_at(item) for key, item in value.items()
                if key != "generatedAt"}
    if isinstance(value, list):
        return [strip_generated_at(item) for item in value]
    return value


class AgentTeamApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.client = TestClient(app)

    def post(self, payload: dict):
        return self.client.post("/agents/team/analyze", json=payload)

    def test_valid_request_returns_complete_deterministic_team_contract(self):
        payload = valid_request()
        original = copy.deepcopy(payload)
        response = self.post(payload)

        self.assertEqual(200, response.status_code)
        body = response.json()
        self.assertEqual(original, payload)
        self.assertEqual(77, body["taskId"])
        self.assertEqual("a" * 64, body["contextHash"])
        self.assertEqual(6, len(body["agentRuns"]))
        self.assertEqual(AGENT_CODES, [run["agentCode"] for run in body["agentRuns"]])
        self.assertEqual(6, len(set(run["agentCode"] for run in body["agentRuns"])))
        self.assertEqual(list(RUN_IDS.values()), [run["runId"] for run in body["agentRuns"]])
        self.assertNotIn("CHIEF_DECISION", [run["agentCode"] for run in body["agentRuns"]])
        self.assertEqual("BLOCKED", body["agentRuns"][0]["gateStatus"])
        for run in body["agentRuns"]:
            self.assertEqual("INSUFFICIENT_DATA", run["status"])
            self.assertFalse(run["veto"])
        self.assertEqual([], body["evidence"])
        self.assertEqual([], body["vetoes"])
        decision = body["finalDecision"]
        self.assertEqual("BLOCKED_BY_DATA_QUALITY", decision["decision"])
        self.assertEqual(list(RUN_IDS.values()), decision["sourceRunIds"])
        self.assertFalse(decision["vetoed"])
        self.assertNotIn(decision["decision"], {"BUY", "SELL", "AUTO_BUY", "AUTO_SELL"})

        second = self.post(original)
        self.assertEqual(200, second.status_code)
        self.assertEqual(strip_generated_at(body), strip_generated_at(second.json()))

    def test_invalid_symbol_is_rejected(self):
        payload = valid_request()
        payload["symbol"] = "60000"
        self.assertEqual(422, self.post(payload).status_code)

    def test_invalid_context_hash_is_rejected(self):
        payload = valid_request()
        payload["contextHash"] = "A" * 64
        self.assertEqual(422, self.post(payload).status_code)

    def test_missing_each_run_id_is_rejected(self):
        for key in RUN_IDS:
            with self.subTest(run_id=key):
                payload = valid_request()
                del payload["runIds"][key]
                self.assertEqual(422, self.post(payload).status_code)

    def test_non_positive_run_ids_are_rejected(self):
        for invalid in (0, -1):
            with self.subTest(run_id=invalid):
                payload = valid_request()
                payload["runIds"]["dataQuality"] = invalid
                self.assertEqual(422, self.post(payload).status_code)

    def test_duplicate_run_ids_are_rejected(self):
        payload = valid_request()
        payload["runIds"]["positionRisk"] = payload["runIds"]["dataQuality"]
        self.assertEqual(422, self.post(payload).status_code)

    def test_six_unique_run_ids_are_preserved_in_final_decision(self):
        response = self.post(valid_request())
        self.assertEqual(200, response.status_code)
        source_run_ids = response.json()["finalDecision"]["sourceRunIds"]
        self.assertEqual(list(RUN_IDS.values()), source_run_ids)
        self.assertEqual(6, len(set(source_run_ids)))

    def test_unsupported_execution_mode_is_rejected(self):
        payload = valid_request()
        payload["executionMode"] = "PAID_MODEL"
        self.assertEqual(422, self.post(payload).status_code)

    def test_missing_each_context_section_is_rejected(self):
        for key in list(valid_request()["contextSnapshot"]):
            with self.subTest(section=key):
                payload = valid_request()
                del payload["contextSnapshot"][key]
                self.assertEqual(422, self.post(payload).status_code)

    def test_unknown_request_field_is_rejected(self):
        payload = valid_request()
        payload["unexpected"] = "not allowed"
        self.assertEqual(422, self.post(payload).status_code)

    def test_orchestrator_does_not_open_database_or_network_connections(self):
        payload = valid_request()
        request = AgentTeamRequest.model_validate(payload)
        with patch.object(socket.socket, "connect", side_effect=AssertionError("network called")), \
                patch.object(sqlite3, "connect", side_effect=AssertionError("database called")):
            response = AgentTeamOrchestrator().analyze(request)
        self.assertEqual(77, response.taskId)


if __name__ == "__main__":
    unittest.main()
