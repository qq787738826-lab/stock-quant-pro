from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from app.agent_team.data_quality import RULE_ORDER
from app.agent_team.models import AgentTeamRequest
from app.agent_team.orchestrator import AgentTeamOrchestrator


HASH = "b" * 64
FIXTURE_DIR = (Path(__file__).resolve().parents[3]
               / "quant-server" / "src" / "test" / "resources" / "agent-team-contract")


def stage_2b_payload() -> dict:
    return json.loads(
        (FIXTURE_DIR / "stage-2b-valid-request.json").read_text(encoding="utf-8")
    )


def analyze(payload: dict) -> dict:
    request = AgentTeamRequest.model_validate(payload)
    return AgentTeamOrchestrator().analyze(request).model_dump(mode="json")


def data_quality(body: dict) -> dict:
    return next(run for run in body["agentRuns"] if run["agentCode"] == "DATA_QUALITY")


class DataQualityAgentTest(unittest.TestCase):
    def assert_mapping(
        self,
        run: dict,
        status: str,
        gate: str,
        decision: str,
        score: int,
        confidence: int,
    ) -> None:
        self.assertEqual(
            (status, gate, decision, score, confidence, False),
            (run["status"], run["gateStatus"], run["decision"], run["score"],
             run["confidence"], run["veto"]),
        )

    def test_pass_mapping_and_incomplete_team_final_decision(self):
        body = analyze(stage_2b_payload())
        run = data_quality(body)
        self.assert_mapping(run, "COMPLETED", "PASS", "PASS", 100, 100)
        final = body["finalDecision"]
        self.assertEqual(
            ("INSUFFICIENT_DATA", "PASS", 0, 0, False),
            (final["decision"], final["gateStatus"], final["score"],
             final["confidence"], final["vetoed"]),
        )
        self.assertNotIn("数据质量门禁阻断", final["summary"])
        for other in body["agentRuns"][1:]:
            self.assertEqual("INSUFFICIENT_DATA", other["status"])
            self.assertEqual("NOT_APPLICABLE", other["gateStatus"])
            self.assertNotIn("数据质量门禁阻断", other["summary"])

    def test_warn_mapping(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["security"]["qualityFacts"]["pointInTimeGuaranteed"] = False
        payload["contextSnapshot"]["dataQualityContext"]["facts"][
            "securityPointInTimeGuaranteed"
        ] = False
        run = data_quality(analyze(payload))
        self.assert_mapping(run, "COMPLETED", "WARN", "WARN", 50, 100)
        self.assertIn("SECURITY_POINT_IN_TIME_UNVERIFIED", self.codes(run))

    def test_invalid_context_mapping_has_no_evidence_or_findings(self):
        payload = stage_2b_payload()
        del payload["contextSnapshot"]["dataQualityContext"]["facts"]
        body = analyze(payload)
        run = data_quality(body)
        self.assert_mapping(run, "INSUFFICIENT_DATA", "BLOCKED", "REJECT", 0, 0)
        self.assertEqual([], run["evidence"])
        self.assertEqual([], run["findings"])
        self.assertEqual(1, len(run["errors"]))
        self.assertEqual("BLOCKED_BY_DATA_QUALITY", body["finalDecision"]["decision"])

    def test_context_with_embedded_rule_conclusion_is_invalid_and_not_evidence(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["dataQualityContext"]["facts"]["score"] = 100
        run = data_quality(analyze(payload))
        self.assert_mapping(run, "INSUFFICIENT_DATA", "BLOCKED", "REJECT", 0, 0)
        self.assertEqual([], run["evidence"])
        self.assertEqual([], run["findings"])

    def test_missing_security_and_market_data_are_distinct_root_blocks(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["security"] = {
            "available": False,
            "reasonCode": "NO_LOCAL_SECURITY_DATA",
            "queriedAt": "2026-07-14T05:00:00Z",
            "queryScope": {"symbol": "600000", "tradeDate": "2026-07-14"},
        }
        market = payload["contextSnapshot"]["marketData"]
        market.update(available=False, effectiveTradeDate=None, exactTradeDateMatch=False, actualBars=0)
        technical = payload["contextSnapshot"]["technicalMetrics"]
        technical.update(available=False, effectiveTradeDate=None, actualBars=0)
        facts = payload["contextSnapshot"]["dataQualityContext"]["facts"]
        facts.update(
            securityRecordPresent=False,
            securityPlaceholderSuspected=False,
            securitySourceKnown=False,
            securityPointInTimeGuaranteed=False,
            loadedBarCount=0,
            exactTradeDatePresent=False,
            effectiveTradeDate=None,
            naturalDayLag=None,
            adjustTypesObserved=[],
            maximumObservedNaturalDayGap=None,
        )
        run = data_quality(analyze(payload))
        self.assert_mapping(run, "COMPLETED", "BLOCKED", "REJECT", 0, 100)
        codes = self.codes(run)
        self.assertEqual(1, codes.count("SECURITY_RECORD_MISSING"))
        self.assertEqual(1, codes.count("MARKET_DATA_MISSING"))
        self.assertNotIn("SECURITY_SOURCE_UNKNOWN", codes)
        self.assertNotIn("TECHNICAL_METRICS_UNAVAILABLE", codes)

    def test_sixty_bars_block_and_sixty_one_bars_pass(self):
        payload = stage_2b_payload()
        facts = payload["contextSnapshot"]["dataQualityContext"]["facts"]
        facts["loadedBarCount"] = 60
        payload["contextSnapshot"]["marketData"]["actualBars"] = 60
        payload["contextSnapshot"]["technicalMetrics"].update(available=False, actualBars=60)
        run = data_quality(analyze(payload))
        self.assert_mapping(run, "COMPLETED", "BLOCKED", "REJECT", 0, 100)
        self.assertIn("INSUFFICIENT_DAILY_BARS", self.codes(run))
        self.assert_mapping(
            data_quality(analyze(stage_2b_payload())),
            "COMPLETED", "PASS", "PASS", 100, 100,
        )

    def test_invalid_bar_is_one_blocking_root(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["technicalMetrics"]["available"] = False
        facts = payload["contextSnapshot"]["dataQualityContext"]["facts"]
        facts["invalidBarCount"] = 1
        facts["invalidBarDates"] = ["2026-06-01"]
        run = data_quality(analyze(payload))
        self.assertIn("INVALID_DAILY_BARS", self.codes(run))
        self.assertNotIn("TECHNICAL_METRICS_UNAVAILABLE", self.codes(run))

    def test_unavailable_technical_metrics_after_valid_bars_is_one_blocking_root(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["technicalMetrics"]["available"] = False
        run = data_quality(analyze(payload))
        self.assertEqual("BLOCKED", run["gateStatus"])
        self.assertIn("TECHNICAL_METRICS_UNAVAILABLE", self.codes(run))
        self.assertNotIn("TECHNICAL_AVAILABILITY_INCONSISTENT", self.codes(run))

    def test_lag_ten_warns_but_eleven_blocks(self):
        for lag, gate, code in ((10, "WARN", "REQUEST_DATE_NOT_EXACT"),
                                (11, "BLOCKED", "MARKET_DATA_TOO_STALE")):
            with self.subTest(lag=lag):
                payload = stage_2b_payload()
                effective = "2026-07-04" if lag == 10 else "2026-07-03"
                payload["contextSnapshot"]["marketData"].update(
                    effectiveTradeDate=effective, exactTradeDateMatch=False,
                )
                payload["contextSnapshot"]["technicalMetrics"]["effectiveTradeDate"] = effective
                facts = payload["contextSnapshot"]["dataQualityContext"]["facts"]
                facts.update(effectiveTradeDate=effective, exactTradeDatePresent=False, naturalDayLag=lag)
                run = data_quality(analyze(payload))
                self.assertEqual(gate, run["gateStatus"])
                self.assertIn(code, self.codes(run))

    def test_negative_lag_or_future_effective_date_is_fact_contradiction(self):
        payload = stage_2b_payload()
        for section in ("marketData", "technicalMetrics"):
            payload["contextSnapshot"][section]["effectiveTradeDate"] = "2026-07-15"
        payload["contextSnapshot"]["marketData"]["exactTradeDateMatch"] = False
        facts = payload["contextSnapshot"]["dataQualityContext"]["facts"]
        facts.update(effectiveTradeDate="2026-07-15", naturalDayLag=-1, exactTradeDatePresent=False)
        run = data_quality(analyze(payload))
        self.assertEqual("BLOCKED", run["gateStatus"])
        self.assertEqual(1, self.codes(run).count("EFFECTIVE_DATE_FACT_CONTRADICTION"))

    def test_board_st_and_inactive_are_info_only(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["security"].update(board="STAR", isSt=True, isActive=False)
        run = data_quality(analyze(payload))
        self.assert_mapping(run, "COMPLETED", "PASS", "PASS", 100, 100)
        finding = next(item for item in run["findings"] if item["code"] == "SECURITY_SCOPE_FACT")
        self.assertEqual("INFO", finding["severity"])

    def test_evidence_is_exact_direct_projection_and_input_is_unchanged(self):
        payload = stage_2b_payload()
        original = copy.deepcopy(payload)
        body = analyze(payload)
        self.assertEqual(original, payload)
        run = data_quality(body)
        self.assertEqual(1, len(run["evidence"]))
        evidence = run["evidence"][0]
        self.assertEqual("JAVA_ENGINE", evidence["sourceType"])
        self.assertEqual("AgentContextSnapshotService", evidence["sourceName"])
        self.assertEqual("contextSnapshot", evidence["sourceRef"])
        self.assertEqual(HASH, evidence["contentHash"])
        expected = {
            key: original["contextSnapshot"][key]
            for key in ("security", "marketData", "technicalMetrics", "dataQualityContext")
        }
        self.assertEqual(expected, evidence["fields"])
        self.assertEqual([evidence], body["evidence"])

    def test_findings_use_fixed_order_unique_codes_and_fixed_severities(self):
        payload = stage_2b_payload()
        payload["contextSnapshot"]["security"]["qualityFacts"]["pointInTimeGuaranteed"] = False
        facts = payload["contextSnapshot"]["dataQualityContext"]["facts"]
        facts.update(
            securityPointInTimeGuaranteed=False,
            missingAmountCount=1,
            missingTurnoverRateCount=1,
            tradingCalendarAvailable=False,
            sourceConsistencyAssessable=False,
        )
        run = data_quality(analyze(payload))
        codes = self.codes(run)
        self.assertEqual(len(codes), len(set(codes)))
        self.assertEqual(codes, sorted(codes, key=RULE_ORDER.index))
        severities = {item["code"]: item["severity"] for item in run["findings"]}
        self.assertEqual("WARN", severities["OPTIONAL_MARKET_FIELDS_MISSING"])
        self.assertEqual("INFO", severities["TRADING_CALENDAR_UNAVAILABLE"])

    @staticmethod
    def codes(run: dict) -> list[str]:
        return [finding["code"] for finding in run["findings"]]


if __name__ == "__main__":
    unittest.main()
