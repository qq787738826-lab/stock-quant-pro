from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from app.agent_team.market_regime import (
    FINDING_ORDER,
    MARKET_BREADTH_EVIDENCE_FIELDS,
    MarketRegimeRuleEngine,
)
from app.agent_team.models import (
    AgentCode,
    AgentTeamRequest,
    GateStatus,
    STAGE_2B_DATA_QUALITY_RULE_VERSION,
    STAGE_2D_MARKET_REGIME_RULE_VERSION,
)
from app.agent_team.orchestrator import AgentTeamOrchestrator


FIXTURE_DIR = (Path(__file__).resolve().parents[3]
               / "quant-server" / "src" / "test" / "resources" / "agent-team-contract")


def fixture(name: str) -> dict:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


def stage_2d_payload() -> dict:
    payload = fixture("stage-2c-valid-request.json")
    payload["ruleVersion"] = STAGE_2D_MARKET_REGIME_RULE_VERSION
    breadth = payload["contextSnapshot"]["marketBreadth"]
    breadth.update(
        available=True,
        reasonCode=None,
        reason=None,
        sourceStatus="AVAILABLE",
        universeCount=4,
        coveredSymbolCount=4,
        comparableSymbolCount=4,
        advancingCount=3,
        decliningCount=1,
        unchangedCount=0,
        missingCurrentBarCount=0,
        missingPreviousBarCount=0,
        coverageRatio=1.00000000,
    )
    return payload


def analyze(payload: dict) -> dict:
    request = AgentTeamRequest.model_validate(payload)
    return AgentTeamOrchestrator().analyze(request).model_dump(mode="json")


def run(body: dict, code: str = "MARKET_REGIME") -> dict:
    return next(item for item in body["agentRuns"] if item["agentCode"] == code)


def configure_counts(payload: dict, advancing: int, declining: int, unchanged: int) -> None:
    breadth = payload["contextSnapshot"]["marketBreadth"]
    comparable = advancing + declining + unchanged
    breadth.update(
        universeCount=comparable,
        coveredSymbolCount=comparable,
        comparableSymbolCount=comparable,
        advancingCount=advancing,
        decliningCount=declining,
        unchangedCount=unchanged,
        missingCurrentBarCount=0,
        missingPreviousBarCount=0,
        coverageRatio=1.00000000,
    )


class MarketRegimeAgentTest(unittest.TestCase):
    def assert_state(
        self,
        output: dict,
        status: str,
        gate: str,
        decision: str,
        score: int,
        confidence: int,
    ) -> None:
        self.assertEqual(
            (status, gate, decision, score, confidence, False),
            (output["status"], output["gateStatus"], output["decision"],
             output["score"], output["confidence"], output["veto"]),
        )

    def test_positive_mixed_negative_and_equal_boundary(self):
        cases = (
            ((4, 0, 0), "MARKET_BREADTH_POSITIVE", 100),
            ((1, 1, 2), "MARKET_BREADTH_MIXED", 50),
            ((0, 4, 0), "MARKET_BREADTH_NEGATIVE", 0),
        )
        for counts, expected_code, expected_score in cases:
            with self.subTest(counts=counts):
                payload = stage_2d_payload()
                configure_counts(payload, *counts)
                output = run(analyze(payload))
                self.assert_state(output, "COMPLETED", "PASS", "WARN", expected_score, 0)
                self.assertEqual(
                    ["MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED", expected_code],
                    [item["code"] for item in output["findings"]],
                )

    def test_score_uses_decimal_half_up(self):
        payload = stage_2d_payload()
        configure_counts(payload, 1, 0, 3)
        output = run(analyze(payload))
        self.assertEqual(63, output["score"])
        self.assertEqual("MARKET_BREADTH_POSITIVE", output["findings"][1]["code"])

    def test_data_quality_blocked_stops_market_regime(self):
        payload = stage_2d_payload()
        payload["contextSnapshot"]["technicalMetrics"]["available"] = False
        output = run(analyze(payload))
        self.assert_state(
            output, "INSUFFICIENT_DATA", "NOT_APPLICABLE", "NOT_APPLICABLE", 0, 0,
        )
        self.assertEqual([], output["findings"])
        self.assertEqual([], output["evidence"])
        self.assertEqual([], output["errors"])
        self.assertEqual("BLOCKED_BY_DATA_QUALITY", analyze(payload)["finalDecision"]["decision"])

    def test_data_quality_warn_and_pass_are_inherited(self):
        passed = run(analyze(stage_2d_payload()))
        self.assertEqual("PASS", passed["gateStatus"])

        payload = stage_2d_payload()
        payload["contextSnapshot"]["security"]["qualityFacts"]["pointInTimeGuaranteed"] = False
        payload["contextSnapshot"]["dataQualityContext"]["facts"][
            "securityPointInTimeGuaranteed"
        ] = False
        warned = run(analyze(payload))
        self.assert_state(warned, "COMPLETED", "WARN", "WARN", 75, 0)

    def test_unavailable_universe_zero_and_comparable_zero(self):
        for reason_code, universe, covered, missing_current, missing_previous in (
            ("NO_ELIGIBLE_UNIVERSE", 0, 0, 0, 0),
            ("ZERO_COMPARABLE_SYMBOLS", 2, 2, 0, 2),
        ):
            with self.subTest(reason_code=reason_code):
                payload = stage_2d_payload()
                breadth = payload["contextSnapshot"]["marketBreadth"]
                breadth.update(
                    available=False,
                    reasonCode=reason_code,
                    sourceStatus="UNAVAILABLE",
                    universeCount=universe,
                    coveredSymbolCount=covered,
                    comparableSymbolCount=0,
                    advancingCount=0,
                    decliningCount=0,
                    unchangedCount=0,
                    missingCurrentBarCount=missing_current,
                    missingPreviousBarCount=missing_previous,
                    coverageRatio=None if universe == 0 else 0,
                )
                output = run(analyze(payload))
                self.assert_state(
                    output, "INSUFFICIENT_DATA", "PASS", "NOT_APPLICABLE", 0, 0,
                )
                self.assertEqual("MARKET_BREADTH_UNAVAILABLE", output["findings"][0]["code"])

    def test_full_coverage_and_minimum_comparable_boundaries(self):
        full = stage_2d_payload()
        configure_counts(full, 1, 1, 0)
        self.assertEqual("COMPLETED", run(analyze(full))["status"])

        one = stage_2d_payload()
        configure_counts(one, 1, 0, 0)
        output = run(analyze(one))
        self.assertEqual("MARKET_BREADTH_LOW_COVERAGE", output["findings"][0]["code"])

        low = stage_2d_payload()
        breadth = low["contextSnapshot"]["marketBreadth"]
        breadth.update(
            universeCount=100_000_000,
            coveredSymbolCount=99_999_999,
            comparableSymbolCount=99_999_999,
            advancingCount=99_999_999,
            decliningCount=0,
            unchangedCount=0,
            missingCurrentBarCount=1,
            missingPreviousBarCount=0,
            coverageRatio=0.99999999,
        )
        output = run(analyze(low))
        self.assertEqual("MARKET_BREADTH_LOW_COVERAGE", output["findings"][0]["code"])

    def test_each_count_relationship_and_coverage_contradiction_is_rejected(self):
        mutations = (
            lambda b: b.update(advancingCount=-1, decliningCount=2),
            lambda b: b.update(comparableSymbolCount=5),
            lambda b: b.update(advancingCount=2, decliningCount=1, unchangedCount=0),
            lambda b: b.update(missingCurrentBarCount=1),
            lambda b: b.update(missingPreviousBarCount=1),
            lambda b: b.update(coverageRatio=0.99999999),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                payload = stage_2d_payload()
                mutate(payload["contextSnapshot"]["marketBreadth"])
                output = run(analyze(payload))
                self.assertEqual(
                    "MARKET_BREADTH_FACT_INCONSISTENT",
                    output["findings"][0]["code"],
                )
                self.assertEqual([], output["errors"])

    def test_date_exactness_current_historical_and_future(self):
        current = run(analyze(stage_2d_payload()))
        self.assertEqual("COMPLETED", current["status"])

        shanghai_date_differs_from_utc = stage_2d_payload()
        shanghai_date_differs_from_utc["requestedAt"] = "2026-07-13T16:01:00Z"
        self.assertEqual(
            "COMPLETED",
            run(analyze(shanghai_date_differs_from_utc))["status"],
        )

        not_exact = stage_2d_payload()
        breadth = not_exact["contextSnapshot"]["marketBreadth"]
        breadth.update(
            effectiveTradeDate="2026-07-13",
            previousEffectiveTradeDate="2026-07-12",
            exactTradeDateMatch=False,
        )
        output = run(analyze(not_exact))
        self.assertEqual("MARKET_BREADTH_DATE_NOT_EXACT", output["findings"][0]["code"])

        for requested_at in ("2026-07-15T05:01:00Z", "2026-07-13T05:01:00Z"):
            with self.subTest(requested_at=requested_at):
                payload = stage_2d_payload()
                payload["requestedAt"] = requested_at
                output = run(analyze(payload))
                self.assert_state(
                    output, "INSUFFICIENT_DATA", "PASS", "NOT_APPLICABLE", 0, 0,
                )
                self.assertEqual(
                    "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED",
                    output["findings"][0]["code"],
                )

    def test_fixed_source_version_and_future_semantics_are_enforced(self):
        mutations = (
            ("barFutureDataExcluded", False),
            ("producerVersion", "MARKET_BREADTH_V2"),
            ("sourceType", "LOCAL_CACHE"),
            ("adjustType", "NONE"),
            ("pointInTimeGuaranteed", True),
            ("universePointInTimeGuaranteed", True),
            ("futureDataExcluded", True),
            ("sourceTables", ["securities", "daily_bars"]),
            ("limitations", []),
            ("reasonCode", "UNEXPECTED"),
        )
        for field, value in mutations:
            with self.subTest(field=field):
                payload = stage_2d_payload()
                payload["contextSnapshot"]["marketBreadth"][field] = value
                output = run(analyze(payload))
                self.assertEqual(
                    "MARKET_BREADTH_FACT_INCONSISTENT",
                    output["findings"][0]["code"],
                )

    def test_unparseable_input_has_only_safe_error(self):
        payload = stage_2d_payload()
        del payload["contextSnapshot"]["marketBreadth"]["universeCount"]
        output = run(analyze(payload))
        self.assert_state(
            output, "INSUFFICIENT_DATA", "PASS", "NOT_APPLICABLE", 0, 0,
        )
        self.assertEqual([], output["findings"])
        self.assertEqual([], output["evidence"])
        self.assertEqual(
            ["MARKET_BREADTH_INPUT_INVALID"],
            [item["code"] for item in output["errors"]],
        )

    def test_only_market_breadth_changes_market_regime_output(self):
        baseline = stage_2d_payload()
        changed = copy.deepcopy(baseline)
        changed["contextSnapshot"]["scanResult"] = {"arbitrary": "changed"}
        changed["contextSnapshot"]["marketData"]["bars"] = [{"close": "999"}]
        changed["contextSnapshot"]["technicalMetrics"]["values"] = {"ma5": "999"}

        before = run(analyze(baseline))
        after = run(analyze(changed))
        before.pop("generatedAt")
        after.pop("generatedAt")
        self.assertEqual(before, after)

    def test_finding_order_ids_evidence_whitelist_and_top_level_order(self):
        payload = stage_2d_payload()
        body = analyze(payload)
        output = run(body)
        codes = [item["code"] for item in output["findings"]]
        self.assertEqual(codes, sorted(codes, key=FINDING_ORDER.index))
        for item in output["findings"]:
            rank = FINDING_ORDER.index(item["code"]) + 1
            expected_id = (
                f"mr-{rank:02d}-{item['code'].lower().replace('_', '-')}"
                f"-{payload['contextHash']}"
            )
            self.assertEqual(expected_id, item["findingId"])

        evidence = output["evidence"][0]
        self.assertEqual(f"mr-breadth-{payload['contextHash']}", evidence["evidenceId"])
        self.assertEqual(
            set(MARKET_BREADTH_EVIDENCE_FIELDS),
            set(evidence["fields"]["marketBreadth"]),
        )
        self.assertNotIn("queriedAt", evidence["fields"]["marketBreadth"])
        self.assertNotIn("queryScope", evidence["fields"]["marketBreadth"])
        self.assertEqual("DATA_QUALITY", body["evidence"][0]["category"])
        self.assertEqual("MARKET_BREADTH", body["evidence"][1]["category"])
        self.assertEqual(evidence, body["evidence"][1])
        self.assertEqual(
            [*run(body, "DATA_QUALITY")["findings"], *output["findings"]],
            body["finalDecision"]["findings"],
        )

    def test_fixed_input_is_deterministic_except_generated_at(self):
        request = AgentTeamRequest.model_validate(stage_2d_payload())
        engine = MarketRegimeRuleEngine()
        first = engine.evaluate(request, GateStatus.PASS)
        second = engine.evaluate(request, GateStatus.PASS)
        self.assertEqual(first, second)

    def test_old_stage_2b_behavior_and_unknown_version_remain_fail_closed(self):
        old_payload = fixture("stage-2b-valid-request.json")
        self.assertEqual(STAGE_2B_DATA_QUALITY_RULE_VERSION, old_payload["ruleVersion"])
        old = analyze(old_payload)
        old_market = run(old)
        self.assert_state(
            old_market, "INSUFFICIENT_DATA", "NOT_APPLICABLE", "NOT_APPLICABLE", 0, 0,
        )
        self.assertEqual([], old_market["findings"])
        self.assertEqual(1, len(old["evidence"]))

        unknown_payload = stage_2d_payload()
        unknown_payload["ruleVersion"] = "unknown-rule-version"
        unknown = analyze(unknown_payload)
        self.assertEqual("BLOCKED", run(unknown, "DATA_QUALITY")["gateStatus"])
        self.assert_state(
            run(unknown),
            "INSUFFICIENT_DATA", "NOT_APPLICABLE", "NOT_APPLICABLE", 0, 0,
        )
        self.assertEqual("BLOCKED_BY_DATA_QUALITY", unknown["finalDecision"]["decision"])


if __name__ == "__main__":
    unittest.main()
