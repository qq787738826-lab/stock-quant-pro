from __future__ import annotations

import copy
import json
import math
import unittest
from datetime import date, timedelta
from pathlib import Path

from app.agent_team.models import (
    AgentTeamRequest,
    STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION,
)
from app.agent_team.orchestrator import AgentTeamOrchestrator
from app.agent_team.technical_analysis import FINDING_ORDER, SCORE_IMPACTS


FIXTURE_DIR = (Path(__file__).resolve().parents[3]
               / "quant-server" / "src" / "test" / "resources" / "agent-team-contract")


def _trading_dates(end: date, count: int) -> list[date]:
    values: list[date] = []
    candidate = end
    while len(values) < count:
        if candidate.weekday() < 5:
            values.append(candidate)
        candidate -= timedelta(days=1)
    return list(reversed(values))


def stage_2e_payload() -> dict:
    payload = json.loads(
        (FIXTURE_DIR / "stage-2c-valid-request.json").read_text(encoding="utf-8")
    )
    payload["ruleVersion"] = STAGE_2E_TECHNICAL_ANALYSIS_RULE_VERSION
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
    dates = _trading_dates(date.fromisoformat(payload["tradeDate"]), 61)
    payload["contextSnapshot"]["marketData"]["bars"] = [
        {
            "symbol": payload["symbol"],
            "tradeDate": item.isoformat(),
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.0,
            "volume": 1000 + index,
            "amount": 100000.0 + index,
            "turnoverRate": 0.5,
        }
        for index, item in enumerate(dates)
    ]
    metrics = payload["contextSnapshot"]["technicalMetrics"]
    metrics["windows"] = {
        "ma5": 5,
        "ma20": 20,
        "ma60": 60,
        "rsi14": 14,
        "atr14": 14,
        "averageVolume20": 20,
        "highestClose20": 20,
    }
    metrics["values"] = {
        "ma5": 105.0,
        "ma20": 100.0,
        "ma60": 95.0,
        "rsi14": 60.0,
        "atr14": 4.0,
        "averageVolume20": 1020.0,
        "highestClose20": 105.0,
    }
    configure(payload, latest_close=105.0)
    return payload


def configure(
    payload: dict,
    *,
    ma5: float = 105.0,
    ma20: float = 100.0,
    ma60: float = 95.0,
    rsi14: float = 60.0,
    atr14: float = 4.0,
    latest_close: float = 105.0,
) -> None:
    values = payload["contextSnapshot"]["technicalMetrics"]["values"]
    values.update(
        ma5=ma5,
        ma20=ma20,
        ma60=ma60,
        rsi14=rsi14,
        atr14=atr14,
        averageVolume20=1020.0,
        highestClose20=max(latest_close, 105.0),
    )
    latest = payload["contextSnapshot"]["marketData"]["bars"][-1]
    latest.update(
        open=latest_close,
        high=latest_close + 1.0,
        low=latest_close - 1.0,
        close=latest_close,
    )


def analyze(payload: dict) -> dict:
    request = AgentTeamRequest.model_validate(payload)
    return AgentTeamOrchestrator().analyze(request).model_dump(mode="json")


def agent_run(body: dict, code: str = "TECHNICAL_ANALYSIS") -> dict:
    return next(item for item in body["agentRuns"] if item["agentCode"] == code)


def codes(run: dict) -> list[str]:
    return [finding["code"] for finding in run["findings"]]


class TechnicalAnalysisAgentTest(unittest.TestCase):
    def assert_completed(
        self,
        output: dict,
        expected_codes: list[str],
        expected_score: int,
        gate: str = "PASS",
        confidence: int = 100,
    ) -> None:
        self.assertEqual(
            ("COMPLETED", gate, "WARN", expected_score, confidence, False),
            (
                output["status"], output["gateStatus"], output["decision"],
                output["score"], output["confidence"], output["veto"],
            ),
        )
        self.assertEqual(expected_codes, codes(output))
        self.assertEqual([], output["errors"])
        self.assertEqual(2, len(output["evidence"]))

    def test_bullish_and_bearish_score_bounds(self):
        bullish = agent_run(analyze(stage_2e_payload()))
        self.assert_completed(
            bullish,
            [
                "TECH_TREND_BULLISH_ALIGNED",
                "TECH_RSI_POSITIVE_MOMENTUM",
                "TECH_PRICE_NEAR_MA20",
                "TECH_VOLATILITY_NORMAL",
                "TECH_INDICATORS_BULLISH_CONFIRMED",
            ],
            100,
        )

        payload = stage_2e_payload()
        configure(payload, ma5=95.0, ma20=100.0, ma60=105.0,
                  rsi14=40.0, atr14=4.0, latest_close=95.0)
        bearish = agent_run(analyze(payload))
        self.assert_completed(
            bearish,
            [
                "TECH_TREND_BEARISH_ALIGNED",
                "TECH_RSI_NEGATIVE_MOMENTUM",
                "TECH_PRICE_NEAR_MA20",
                "TECH_VOLATILITY_NORMAL",
                "TECH_INDICATORS_BEARISH_CONFIRMED",
            ],
            0,
        )

    def test_every_reason_code_and_threshold_boundary(self):
        cases = (
            ({"ma5": 105.0, "ma20": 100.0, "ma60": 95.0},
             "TECH_TREND_BULLISH_ALIGNED"),
            ({"ma5": 100.0, "ma20": 100.0, "ma60": 95.0},
             "TECH_TREND_MIXED"),
            ({"ma5": 95.0, "ma20": 100.0, "ma60": 105.0},
             "TECH_TREND_BEARISH_ALIGNED"),
            ({"rsi14": 70.0}, "TECH_RSI_OVERBOUGHT_RISK"),
            ({"rsi14": 69.999999}, "TECH_RSI_POSITIVE_MOMENTUM"),
            ({"rsi14": 50.0}, "TECH_RSI_NEUTRAL"),
            ({"rsi14": 49.999999}, "TECH_RSI_NEGATIVE_MOMENTUM"),
            ({"rsi14": 30.0}, "TECH_RSI_OVERSOLD_RISK"),
            ({"latest_close": 110.0}, "TECH_PRICE_ABOVE_MA20_EXTENDED"),
            ({"latest_close": 109.999999}, "TECH_PRICE_NEAR_MA20"),
            ({"latest_close": 90.0}, "TECH_PRICE_BELOW_MA20_EXTENDED"),
            ({"latest_close": 100.0, "atr14": 5.0}, "TECH_VOLATILITY_ELEVATED"),
            ({"latest_close": 100.0, "atr14": 4.999999}, "TECH_VOLATILITY_NORMAL"),
            ({}, "TECH_INDICATORS_BULLISH_CONFIRMED"),
            ({"ma5": 95.0, "ma20": 100.0, "ma60": 105.0,
              "rsi14": 40.0, "latest_close": 95.0},
             "TECH_INDICATORS_BEARISH_CONFIRMED"),
            ({"ma5": 100.0, "ma20": 100.0, "ma60": 95.0,
              "rsi14": 60.0, "latest_close": 100.0},
             "TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED"),
        )
        observed: set[str] = set()
        for overrides, expected in cases:
            with self.subTest(reason_code=expected):
                payload = stage_2e_payload()
                configure(payload, **overrides)
                output = agent_run(analyze(payload))
                self.assertIn(expected, codes(output))
                self.assertGreaterEqual(output["score"], 0)
                self.assertLessEqual(output["score"], 100)
                observed.update(codes(output))
        self.assertEqual(set(FINDING_ORDER), observed)

    def test_five_categories_are_mutually_exclusive_and_score_is_reason_derived(self):
        payload = stage_2e_payload()
        configure(payload, ma5=100.0, ma20=100.0, ma60=95.0,
                  rsi14=70.0, atr14=5.0, latest_close=110.0)
        output = agent_run(analyze(payload))
        actual_codes = codes(output)
        self.assertEqual(5, len(actual_codes))
        self.assertEqual(len(actual_codes), len(set(actual_codes)))
        self.assertEqual(actual_codes, sorted(actual_codes, key=FINDING_ORDER.index))
        expected_score = min(
            100,
            max(0, 50 + sum(SCORE_IMPACTS[code] for code in actual_codes)),
        )
        self.assertEqual(expected_score, output["score"])

    def test_data_quality_pass_warn_and_blocked_paths(self):
        passed = agent_run(analyze(stage_2e_payload()))
        self.assertEqual(("PASS", 100), (passed["gateStatus"], passed["confidence"]))

        warned_payload = stage_2e_payload()
        warned_payload["contextSnapshot"]["security"]["qualityFacts"][
            "pointInTimeGuaranteed"
        ] = False
        warned_payload["contextSnapshot"]["dataQualityContext"]["facts"][
            "securityPointInTimeGuaranteed"
        ] = False
        warned = agent_run(analyze(warned_payload))
        self.assertEqual(
            ("COMPLETED", "WARN", "WARN", 50),
            (warned["status"], warned["gateStatus"], warned["decision"],
             warned["confidence"]),
        )

        blocked_payload = stage_2e_payload()
        blocked_payload["contextSnapshot"]["technicalMetrics"][
            "formulaVersion"
        ] = "UNAPPROVED_FORMULA"
        body = analyze(blocked_payload)
        blocked = agent_run(body)
        self.assertEqual(
            ("INSUFFICIENT_DATA", "NOT_APPLICABLE", "NOT_APPLICABLE", 0, 0),
            (blocked["status"], blocked["gateStatus"], blocked["decision"],
             blocked["score"], blocked["confidence"]),
        )
        self.assertEqual([], blocked["evidence"])
        self.assertEqual([], blocked["findings"])
        self.assertEqual([], blocked["errors"])
        self.assertEqual("BLOCKED_BY_DATA_QUALITY", body["finalDecision"]["decision"])

    def test_null_non_numeric_nan_infinity_and_sample_shortage_degrade_safely(self):
        mutations = (
            lambda payload: payload["contextSnapshot"]["technicalMetrics"]["values"].update(ma5=None),
            lambda payload: payload["contextSnapshot"]["technicalMetrics"]["values"].update(rsi14="60"),
            lambda payload: payload["contextSnapshot"]["technicalMetrics"]["values"].update(atr14=math.nan),
            lambda payload: payload["contextSnapshot"]["technicalMetrics"]["values"].update(ma20=math.inf),
            lambda payload: payload["contextSnapshot"]["marketData"]["bars"].pop(),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                payload = stage_2e_payload()
                mutate(payload)
                output = agent_run(analyze(payload))
                self.assertEqual(
                    ("INSUFFICIENT_DATA", "PASS", "NOT_APPLICABLE", 0, 0, False),
                    (
                        output["status"], output["gateStatus"], output["decision"],
                        output["score"], output["confidence"], output["veto"],
                    ),
                )
                self.assertEqual([], output["evidence"])
                self.assertEqual([], output["findings"])
                self.assertEqual(
                    ["TECHNICAL_ANALYSIS_INPUT_INVALID"],
                    [error["code"] for error in output["errors"]],
                )

    def test_future_out_of_order_and_invalid_ohlc_are_rejected(self):
        mutations = (
            lambda payload: payload["contextSnapshot"]["marketData"]["bars"][-1].update(
                tradeDate="2026-07-15"
            ),
            lambda payload: payload["contextSnapshot"]["marketData"]["bars"][-1].update(
                tradeDate=payload["contextSnapshot"]["marketData"]["bars"][-2]["tradeDate"]
            ),
            lambda payload: payload["contextSnapshot"]["marketData"]["bars"][-1].update(
                high=90.0
            ),
            lambda payload: payload["contextSnapshot"]["marketData"]["bars"][-1].update(
                unapprovedField="must-not-be-ignored"
            ),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                payload = stage_2e_payload()
                mutate(payload)
                output = agent_run(analyze(payload))
                self.assertEqual("INSUFFICIENT_DATA", output["status"])
                self.assertEqual("TECHNICAL_ANALYSIS_INPUT_INVALID", output["errors"][0]["code"])

    def test_evidence_is_direct_whitelisted_projection_with_stable_paths(self):
        payload = stage_2e_payload()
        original = copy.deepcopy(payload)
        body = analyze(payload)
        output = agent_run(body)
        self.assertEqual(original, payload)
        metrics_evidence, market_evidence = output["evidence"]
        expected_metrics = {
            key: original["contextSnapshot"]["technicalMetrics"][key]
            for key in (
                "available", "formulaVersion", "adjustType", "requestedTradeDate",
                "effectiveTradeDate", "requiredBars", "actualBars", "windows", "values",
            )
        }
        self.assertEqual({"technicalMetrics": expected_metrics}, metrics_evidence["fields"])
        market = original["contextSnapshot"]["marketData"]
        self.assertEqual(
            {
                "marketData": {
                    "available": market["available"],
                    "adjustType": market["adjustType"],
                    "requestedTradeDate": market["requestedTradeDate"],
                    "effectiveTradeDate": market["effectiveTradeDate"],
                    "exactTradeDateMatch": market["exactTradeDateMatch"],
                    "actualBars": market["actualBars"],
                    "latestBar": market["bars"][-1],
                }
            },
            market_evidence["fields"],
        )
        for finding in output["findings"]:
            self.assertIn("observed=", finding["detail"])
            self.assertIn("condition=", finding["detail"])
            self.assertIn("scoreImpact=", finding["detail"])
            self.assertTrue(finding["evidenceIds"])

    def test_same_context_hash_is_deterministic_and_six_run_shape_is_preserved(self):
        payload = stage_2e_payload()
        first = analyze(copy.deepcopy(payload))
        second = analyze(copy.deepcopy(payload))
        first_run = agent_run(first)
        second_run = agent_run(second)
        for item in (first_run, second_run):
            item.pop("generatedAt")
        self.assertEqual(first_run, second_run)
        self.assertEqual(6, len(first["agentRuns"]))
        self.assertEqual([], first["vetoes"])
        self.assertFalse(first["finalDecision"]["vetoed"])
        self.assertEqual("INSUFFICIENT_DATA", first["finalDecision"]["decision"])
        self.assertEqual(
            [
                "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
                "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK",
            ],
            [item["agentCode"] for item in first["agentRuns"]],
        )

    def test_outputs_contain_no_investment_instruction_or_return_promise(self):
        rendered = json.dumps(analyze(stage_2e_payload()), ensure_ascii=False)
        for forbidden in ("买入", "卖出", "加仓", "减仓", "目标价", "承诺收益"):
            self.assertNotIn(forbidden, rendered)


if __name__ == "__main__":
    unittest.main()
