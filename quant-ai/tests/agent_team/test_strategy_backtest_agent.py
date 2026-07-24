from __future__ import annotations

import copy
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path
import unittest

from app.agent_team.backtest_canonical import canonical_hash, canonical_text
from app.agent_team.models import (
    AgentTeamRequest,
    GateStatus,
    STAGE_2F_STRATEGY_BACKTEST_RULE_VERSION,
)
from app.agent_team.orchestrator import AgentTeamOrchestrator
from app.agent_team.strategy_backtest import (
    BASE_CONTEXT_FIELDS,
    StrategyBacktestRuleEngine,
    UNAVAILABLE_REASON_CODES,
)

from .test_technical_analysis_agent import _trading_dates, stage_2e_payload


GOLDEN_DIR = (Path(__file__).resolve().parents[3]
              / "quant-server" / "src" / "test" / "resources" / "agent")
CANONICAL_VERSION = "BACKTEST_CANONICAL_V1"


def stage_2f_payload(
    *,
    total_return: Decimal = Decimal("0.15"),
    max_drawdown: Decimal = Decimal("0.10"),
    win_rate: Decimal = Decimal("0.55"),
    profit_loss_ratio: Decimal = Decimal("1.50"),
    trade_count: int = 40,
    positive_subperiod_count: int = 3,
    bar_count: int = 120,
) -> dict:
    payload = stage_2e_payload()
    payload["ruleVersion"] = STAGE_2F_STRATEGY_BACKTEST_RULE_VERSION
    request_date = date.fromisoformat(payload["tradeDate"])
    trade_dates = _trading_dates(request_date, bar_count)
    known_at = datetime.combine(
        request_date,
        time(15, 0),
        tzinfo=timezone(timedelta(hours=8)),
    ).astimezone(timezone.utc).isoformat(
        timespec="microseconds"
    ).replace("+00:00", "Z")
    decision_time = (
        datetime.combine(
            request_date + timedelta(days=1),
            time(0, 0),
            tzinfo=timezone(timedelta(hours=8)),
        ) - timedelta(microseconds=1)
    ).astimezone(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")
    queried_at = (
        datetime.fromisoformat(decision_time[:-1] + "+00:00")
        + timedelta(microseconds=1)
    ).isoformat(timespec="microseconds").replace("+00:00", "Z")
    bars: list[dict] = []
    observations: list[dict] = []
    versions: list[str] = []
    for index in range(bar_count):
        trade_date = trade_dates[index]
        bar = {
            "symbol": payload["symbol"],
            "tradeDate": trade_date.isoformat(),
            "open": 100.0,
            "high": 101.0,
            "low": 99.0,
            "close": 100.0,
            "volume": 1000 + index,
            "amount": 100000.0,
            "turnoverRate": 0.5,
            "sourceCode": "TEST_FIXTURE_STAGE_2F",
            "sourceRevision": "REVISION_1",
            "batchVersion": "TEST_BATCH_V1",
            "datasetVersion": "TEST_DATASET_V1",
            "firstObservedAt": known_at,
            "knownAt": known_at,
        }
        content_hash = canonical_hash({
            "canonicalContractVersion": CANONICAL_VERSION,
            "sourceCode": bar["sourceCode"],
            "symbol": bar["symbol"],
            "tradeDate": bar["tradeDate"],
            "adjustType": "QFQ",
            "open": bar["open"],
            "high": bar["high"],
            "low": bar["low"],
            "close": bar["close"],
            "volume": bar["volume"],
            "amount": bar["amount"],
            "turnoverRate": bar["turnoverRate"],
        })
        version = canonical_hash({
            "canonicalContractVersion": CANONICAL_VERSION,
            "batchVersion": bar["batchVersion"],
            "datasetVersion": bar["datasetVersion"],
            "sourceCode": bar["sourceCode"],
            "sourceRevision": bar["sourceRevision"],
            "firstObservedAt": bar["firstObservedAt"],
            "knownAt": bar["knownAt"],
            "canonicalContentHash": content_hash,
        })
        bar["observationVersion"] = version
        bar["canonicalContentHash"] = content_hash
        versions.append(version)
        bars.append(bar)
        observations.append({
            "observationVersion": version,
            "tradeDate": trade_date.isoformat(),
            "sourceCode": "TEST_FIXTURE_STAGE_2F",
            "sourceRevision": "REVISION_1",
            "batchVersion": "TEST_BATCH_V1",
            "datasetVersion": "TEST_DATASET_V1",
            "firstObservedAt": known_at,
            "knownAt": known_at,
            "recordedAt": known_at,
            "canonicalContentHash": content_hash,
        })
    data_version = {
        "pitModelVersion": "PIT_DAILY_BAR_OBSERVATION_V1",
        "datasetVersions": ["TEST_DATASET_V1"],
        "batchVersions": ["TEST_BATCH_V1"],
        "selectedObservationVersions": versions,
        "sourceRevisions": [{
            "sourceCode": "TEST_FIXTURE_STAGE_2F",
            "sourceRevision": "REVISION_1",
        }],
    }
    lineage = {
        "sources": ["TEST_FIXTURE_STAGE_2F"],
        "batches": [{
            "batchVersion": "TEST_BATCH_V1",
            "datasetVersion": "TEST_DATASET_V1",
            "sourceCode": "TEST_FIXTURE_STAGE_2F",
            "captureType": "TEST_FIXTURE",
            "observedAt": known_at,
            "recordedAt": known_at,
            "sourceMetadata": {"revisionSemantics": "TEST_FIXTURE"},
        }],
        "observations": observations,
        "maximumKnownAt": known_at,
    }
    strategy = {
        "canonicalContractVersion": CANONICAL_VERSION,
        "strategyCode": "SMA20_NEXT_OPEN_RISK_EXIT_V1",
        "strategyVersion": "SMA20_NEXT_OPEN_RISK_EXIT_V1",
        "engineVersion": "BACKTEST_ENGINE_V1",
        "parameterSchemaVersion": "BACKTEST_PARAMS_V1",
        "parameters": {
            "initialCapital": 100000,
            "maxHoldingDays": 10,
            "stopLossPct": 0.05,
            "takeProfitPct": 0.08,
            "trailingStopPct": 0.04,
            "commissionRate": 0.0003,
            "stampDutyRate": 0.0005,
        },
    }
    result = _result(
        request_date,
        total_return,
        max_drawdown,
        win_rate,
        profit_loss_ratio,
        trade_count,
    )
    split_base = bar_count // 3
    remainder = bar_count % 3
    subperiods = []
    cursor = 0
    for index, name in enumerate(("EARLY", "MIDDLE", "LATE")):
        count = split_base + (1 if index < remainder else 0)
        period_return = Decimal("0.01") if index < positive_subperiod_count else Decimal("-0.01")
        subperiods.append({
            "name": name,
            "inputStartDate": bars[cursor]["tradeDate"],
            "inputEndDate": bars[cursor + count - 1]["tradeDate"],
            "barCount": count,
            "result": _result(
                request_date,
                period_return,
                Decimal("0.05"),
                Decimal("0.50"),
                Decimal("1.00"),
                0,
            ),
        })
        cursor += count
    stability = {
        "splitAlgorithm": "CHRONOLOGICAL_THIRDS_REMAINDER_TO_EARLY_THEN_MIDDLE_V1",
        "validSubperiodCount": 3,
        "positiveSubperiodCount": positive_subperiod_count,
    }
    input_payload = {
        "canonicalContractVersion": CANONICAL_VERSION,
        "contextProfile": "AGENT_CONTEXT_2F_V1",
        "contextSchemaVersion": "BACKTEST_CONTEXT_V1",
        "symbol": payload["symbol"],
        "requestTradeDate": request_date.isoformat(),
        "effectiveTradeDate": request_date.isoformat(),
        "decisionTime": decision_time,
        "knowledgeCutoff": decision_time,
        "marketTimezone": "Asia/Shanghai",
        "adjustType": "QFQ",
        "inputStartDate": bars[0]["tradeDate"],
        "inputEndDate": bars[-1]["tradeDate"],
        "barCount": bar_count,
        "dataVersion": data_version,
        "observationLineage": [
            {key: value for key, value in item.items() if key != "recordedAt"}
            for item in observations
        ],
        "bars": bars,
    }
    input_hash = canonical_hash(input_payload)
    strategy_hash = canonical_hash(strategy)
    result_hash = canonical_hash({
        "canonicalContractVersion": CANONICAL_VERSION,
        "inputDataHash": input_hash,
        "strategyDefinitionHash": strategy_hash,
        "result": result,
        "subperiods": subperiods,
        "stability": stability,
    })
    payload["contextSnapshot"]["backtestContext"] = {
        "available": True,
        "queriedAt": queried_at,
        "queryScope": {
            "symbol": payload["symbol"],
            "tradeDate": request_date.isoformat(),
        },
        "producer": "AgentBacktestContextService",
        "producerVersion": "JAVA_BACKTEST_CONTEXT_V1",
        "contextProfile": "AGENT_CONTEXT_2F_V1",
        "schemaVersion": "BACKTEST_CONTEXT_V1",
        "canonicalContractVersion": CANONICAL_VERSION,
        "pitModelVersion": "PIT_DAILY_BAR_OBSERVATION_V1",
        "symbol": payload["symbol"],
        "requestTradeDate": request_date.isoformat(),
        "decisionTime": decision_time,
        "knowledgeCutoff": decision_time,
        "marketTimezone": "Asia/Shanghai",
        "adjustType": "QFQ",
        "sourceType": "DATABASE",
        "sourceTables": [
            "market_data_observation_batches",
            "daily_bar_observations",
        ],
        "sourceStatus": "LOCAL_PIT_OBSERVATIONS",
        "effectiveTradeDate": request_date.isoformat(),
        "exactTradeDateMatch": True,
        "inputStartDate": bars[0]["tradeDate"],
        "inputEndDate": bars[-1]["tradeDate"],
        "barCount": bar_count,
        "requiredBars": 120,
        "maximumBars": 500,
        "dataVersion": data_version,
        "lineage": lineage,
        "bars": bars,
        "strategy": strategy,
        "result": result,
        "subperiods": subperiods,
        "stability": stability,
        "inputDataHash": input_hash,
        "strategyDefinitionHash": strategy_hash,
        "backtestResultHash": result_hash,
        "pointInTimeGuaranteed": True,
        "readSelectionFutureExcluded": True,
        "producerInputCutoffGuaranteed": True,
        "futureDataExcluded": True,
        "limitations": [
            "RESEARCH_AND_SIMULATION_ONLY",
            "LOCAL_OBSERVATION_TIME_IS_NOT_PROVIDER_PUBLICATION_TIME",
            "CONTENT_HASH_DOES_NOT_REPLACE_KNOWLEDGE_TIME",
        ],
    }
    return payload


def _result(
    request_date: date,
    total_return: Decimal,
    max_drawdown: Decimal,
    win_rate: Decimal,
    profit_loss_ratio: Decimal,
    trade_count: int,
) -> dict:
    initial = Decimal("100000")
    final = initial * (Decimal("1") + total_return)
    trades = []
    trade_dates = _trading_dates(request_date, trade_count)
    for index in range(trade_count):
        trades.append({
            "sequence": index + 1,
            "entryDate": trade_dates[index].isoformat(),
            "exitDate": trade_dates[index].isoformat(),
            "entryPrice": 100.0,
            "exitPrice": 101.0,
            "quantity": 100,
            "pnl": 99.0,
            "returnPct": 0.0099,
            "exitReason": "MAX_HOLD",
        })
    return {
        "initialCapital": float(initial),
        "finalCapital": float(final),
        "totalReturn": float(total_return),
        "maxDrawdown": float(max_drawdown),
        "winRate": float(win_rate),
        "profitLossRatio": float(profit_loss_ratio),
        "tradeCount": trade_count,
        "trades": trades,
    }


def analyze(payload: dict) -> dict:
    request = AgentTeamRequest.model_validate(payload)
    return AgentTeamOrchestrator().analyze(request).model_dump(mode="json")


def strategy_run(response: dict) -> dict:
    return next(
        item for item in response["agentRuns"]
        if item["agentCode"] == "STRATEGY_BACKTEST"
    )


class StrategyBacktestAgentTest(unittest.TestCase):

    def test_matches_cross_language_canonical_golden_vector(self):
        input_value = json.loads(
            (GOLDEN_DIR / "backtest-canonical-v1-input.json").read_text(encoding="utf-8")
        )
        expected_text = (
            GOLDEN_DIR / "backtest-canonical-v1-canonical.txt"
        ).read_text(encoding="utf-8").rstrip("\r\n")
        expected_hash = (
            GOLDEN_DIR / "backtest-canonical-v1-sha256.txt"
        ).read_text(encoding="utf-8").strip()
        self.assertEqual(expected_text, canonical_text(input_value))
        self.assertEqual(expected_hash, canonical_hash(input_value))

    def test_valid_context_generates_fixed_five_findings_and_safe_team_result(self):
        response = analyze(stage_2f_payload())
        run = strategy_run(response)
        self.assertEqual(
            ("COMPLETED", "PASS", "WARN", 100, 80, False),
            (
                run["status"],
                run["gateStatus"],
                run["decision"],
                run["score"],
                run["confidence"],
                run["veto"],
            ),
        )
        self.assertEqual([
            "STRATEGY_BACKTEST_SAMPLE_SUFFICIENT",
            "STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED",
            "STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED",
            "STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED",
            "STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED",
        ], [item["code"] for item in run["findings"]])
        self.assertEqual(1, len(run["evidence"]))
        self.assertEqual([], run["errors"])
        self.assertEqual([], response["vetoes"])
        self.assertFalse(response["finalDecision"]["vetoed"])
        self.assertEqual("INSUFFICIENT_DATA", response["finalDecision"]["decision"])
        self.assertEqual(6, len(response["agentRuns"]))
        encoded = json.dumps(response, ensure_ascii=False)
        for forbidden in ("买入", "卖出", "加仓", "减仓", "目标价", "收益承诺"):
            self.assertNotIn(forbidden, encoded)

    def test_score_thresholds_and_bounds(self):
        cases = (
            (Decimal("0.15"), Decimal("0.10"), Decimal("0.55"), Decimal("1.50"), 3, 100),
            (Decimal("0.05"), Decimal("0.20"), Decimal("0.45"), Decimal("1.00"), 2, 70),
            (Decimal("0.01"), Decimal("0.21"), Decimal("0.44"), Decimal("0.79"), 1, 15),
            (Decimal("-0.15"), Decimal("0.31"), Decimal("0.44"), Decimal("0.79"), 0, 0),
            (Decimal("0"), Decimal("0.100001"), Decimal("0.549999"),
             Decimal("0.999999"), 2, 55),
        )
        for total, drawdown, win_rate, ratio, positive, expected in cases:
            with self.subTest(total=total, drawdown=drawdown):
                run = strategy_run(analyze(stage_2f_payload(
                    total_return=total,
                    max_drawdown=drawdown,
                    win_rate=win_rate,
                    profit_loss_ratio=ratio,
                    positive_subperiod_count=positive,
                )))
                self.assertEqual(expected, run["score"])

    def test_confidence_boundaries_and_warn_cap(self):
        for count, expected in ((10, 40), (19, 40), (20, 60), (39, 60), (40, 80)):
            with self.subTest(count=count):
                request = AgentTeamRequest.model_validate(
                    stage_2f_payload(trade_count=count)
                )
                evaluation = StrategyBacktestRuleEngine().evaluate(
                    request,
                    GateStatus.PASS,
                )
                self.assertEqual(expected, evaluation.confidence)
        request = AgentTeamRequest.model_validate(stage_2f_payload(trade_count=40))
        warned = StrategyBacktestRuleEngine().evaluate(request, GateStatus.WARN)
        self.assertEqual(50, warned.confidence)
        self.assertEqual(GateStatus.WARN, warned.gate_status)

    def test_blocked_unavailable_invalid_and_sample_insufficient_paths(self):
        request = AgentTeamRequest.model_validate(stage_2f_payload())
        blocked = StrategyBacktestRuleEngine().evaluate(request, GateStatus.BLOCKED)
        self.assertEqual(("INSUFFICIENT_DATA", "BLOCKED", 0, 0), (
            blocked.status.value,
            blocked.gate_status.value,
            blocked.score,
            blocked.confidence,
        ))
        self.assertEqual((), blocked.findings)
        self.assertEqual((), blocked.evidence)

        unavailable_payload = stage_2f_payload()
        available_context = unavailable_payload["contextSnapshot"]["backtestContext"]
        context = {
            key: value for key, value in available_context.items()
            if key in BASE_CONTEXT_FIELDS
        }
        context.update({
            "available": False,
            "pointInTimeGuaranteed": False,
            "readSelectionFutureExcluded": False,
            "producerInputCutoffGuaranteed": False,
            "futureDataExcluded": False,
            "reasonCode": "BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE",
            "reason": "no observation was visible before knowledgeCutoff",
        })
        unavailable_payload["contextSnapshot"]["backtestContext"] = context
        unavailable = StrategyBacktestRuleEngine().evaluate(
            AgentTeamRequest.model_validate(unavailable_payload),
            GateStatus.PASS,
        )
        self.assertEqual(
            "BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE",
            unavailable.errors[0].code,
        )
        for reason_code in sorted(UNAVAILABLE_REASON_CODES):
            with self.subTest(reason_code=reason_code):
                reason_payload = stage_2f_payload()
                available = reason_payload["contextSnapshot"]["backtestContext"]
                unavailable_context = {
                    key: value for key, value in available.items()
                    if key in BASE_CONTEXT_FIELDS
                }
                unavailable_context.update({
                    "available": False,
                    "pointInTimeGuaranteed": False,
                    "readSelectionFutureExcluded": False,
                    "producerInputCutoffGuaranteed": False,
                    "futureDataExcluded": False,
                    "reasonCode": reason_code,
                    "reason": "stable unavailable test fixture",
                })
                if reason_code == "BACKTEST_SAMPLE_INSUFFICIENT":
                    unavailable_context["actualBars"] = 119
                    unavailable_context["requiredBars"] = 120
                reason_payload["contextSnapshot"][
                    "backtestContext"
                ] = unavailable_context
                reason_evaluation = StrategyBacktestRuleEngine().evaluate(
                    AgentTeamRequest.model_validate(reason_payload),
                    GateStatus.PASS,
                )
                self.assertEqual(
                    reason_code,
                    reason_evaluation.errors[0].code,
                )

        tampered_payload = stage_2f_payload()
        tampered_payload["contextSnapshot"]["backtestContext"]["inputDataHash"] = "0" * 64
        invalid = StrategyBacktestRuleEngine().evaluate(
            AgentTeamRequest.model_validate(tampered_payload),
            GateStatus.PASS,
        )
        self.assertEqual("STRATEGY_BACKTEST_INPUT_INVALID", invalid.errors[0].code)
        self.assertEqual((), invalid.evidence)

        noncanonical_time_payload = stage_2f_payload()
        noncanonical_time_payload["contextSnapshot"]["backtestContext"][
            "queriedAt"
        ] = "2025-01-01T16:00:00Z"
        noncanonical_time = StrategyBacktestRuleEngine().evaluate(
            AgentTeamRequest.model_validate(noncanonical_time_payload),
            GateStatus.PASS,
        )
        self.assertEqual(
            "STRATEGY_BACKTEST_INPUT_INVALID",
            noncanonical_time.errors[0].code,
        )

        unexpected_field_payload = stage_2f_payload()
        unexpected_field_payload["contextSnapshot"]["backtestContext"][
            "unexpected"
        ] = "forbidden"
        unexpected_field = StrategyBacktestRuleEngine().evaluate(
            AgentTeamRequest.model_validate(unexpected_field_payload),
            GateStatus.PASS,
        )
        self.assertEqual(
            "STRATEGY_BACKTEST_INPUT_INVALID",
            unexpected_field.errors[0].code,
        )

        sample = StrategyBacktestRuleEngine().evaluate(
            AgentTeamRequest.model_validate(stage_2f_payload(trade_count=9)),
            GateStatus.PASS,
        )
        self.assertEqual("STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT", sample.errors[0].code)
        self.assertEqual(0, sample.score)
        self.assertEqual(0, sample.confidence)
        self.assertEqual(1, len(sample.evidence))
        self.assertEqual((), sample.findings)

    def test_predated_and_weekend_bars_are_input_invalid(self):
        pre_close = datetime.combine(
            date.fromisoformat(stage_2f_payload()["tradeDate"]),
            time(14, 59, 59),
            tzinfo=timezone(timedelta(hours=8)),
        ).astimezone(timezone.utc).isoformat(
            timespec="microseconds"
        ).replace("+00:00", "Z")

        known_at_payload = stage_2f_payload()
        known_at_payload["contextSnapshot"]["backtestContext"]["bars"][-1][
            "knownAt"
        ] = pre_close
        known_at_run = strategy_run(analyze(known_at_payload))
        self.assertEqual(
            "STRATEGY_BACKTEST_INPUT_INVALID",
            known_at_run["errors"][0]["code"],
        )
        self.assertEqual([], known_at_run["findings"])
        self.assertEqual([], known_at_run["evidence"])
        self.assertEqual((0, 0), (
            known_at_run["score"],
            known_at_run["confidence"],
        ))

        first_observed_payload = stage_2f_payload()
        first_observed_payload["contextSnapshot"]["backtestContext"]["bars"][-1][
            "firstObservedAt"
        ] = pre_close
        first_observed_run = strategy_run(analyze(first_observed_payload))
        self.assertEqual(
            "STRATEGY_BACKTEST_INPUT_INVALID",
            first_observed_run["errors"][0]["code"],
        )
        self.assertEqual([], first_observed_run["findings"])

        weekend_payload = stage_2f_payload()
        bars = weekend_payload["contextSnapshot"]["backtestContext"]["bars"]
        friday_index = next(
            index for index, item in enumerate(bars[:-1])
            if date.fromisoformat(item["tradeDate"]).weekday() == 4
        )
        friday = date.fromisoformat(bars[friday_index]["tradeDate"])
        bars[friday_index]["tradeDate"] = (
            friday + timedelta(days=1)
        ).isoformat()
        weekend_run = strategy_run(analyze(weekend_payload))
        self.assertEqual(
            "STRATEGY_BACKTEST_INPUT_INVALID",
            weekend_run["errors"][0]["code"],
        )
        self.assertEqual([], weekend_run["findings"])


if __name__ == "__main__":
    unittest.main()
