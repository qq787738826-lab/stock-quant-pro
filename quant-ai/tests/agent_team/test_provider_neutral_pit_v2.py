from __future__ import annotations

import copy
import json
from pathlib import Path
import unittest

from app.agent_team.backtest_canonical import canonical_hash, canonical_text
from app.agent_team.models import (
    AgentTeamRequest,
    GateStatus,
    STAGE_3AR3B0_PIT_V2_RULE_VERSION,
)
from app.agent_team.offline_fixture import (
    reject_sensitive,
    sanitize_fixture,
)
from app.agent_team.orchestrator import AgentTeamOrchestrator
from app.agent_team.strategy_backtest_v2 import (
    StrategyBacktestRuleEngineV2,
)

from .test_strategy_backtest_agent import stage_2f_payload


GOLDEN_DIR = (
    Path(__file__).resolve().parents[3]
    / "quant-server"
    / "src"
    / "test"
    / "resources"
    / "agent"
)


def stage_3ar3b0_payload() -> dict:
    payload = stage_2f_payload()
    payload["ruleVersion"] = STAGE_3AR3B0_PIT_V2_RULE_VERSION
    old = payload["contextSnapshot"]["backtestContext"]
    bars = []
    raw_versions = []
    factor_versions = []
    raw_lineage = []
    factor_lineage = []
    source_identities = {
        "rawSourceIdentity": f"SECURITY:{payload['symbol']}.SZSE",
        "factorSourceIdentity": f"FACTOR:QFQ:{payload['symbol']}.SZSE",
        "calendarSourceIdentity": "CALENDAR:SZSE",
        "corporateActionSourceIdentity": (
            f"CORPORATE_ACTION:{payload['symbol']}.SZSE"
        ),
    }

    def observation_lineage(
        version: str,
        content_hash: str,
        natural_key: str,
        source_identity: str,
    ) -> dict:
        return {
            "observationVersion": version,
            "canonicalContentHash": content_hash,
            "naturalKey": natural_key,
            "sourceIdentity": source_identity,
            "knownAt": old["knowledgeCutoff"],
            "revisionQualification": "SYSTEM_KNOWLEDGE_ONLY",
            "assuranceLevel": "SYSTEM_KNOWLEDGE_PIT",
            "usageQualification": "TEST_DEMO_ONLY",
            "formalEligible": False,
            "localPersistenceAllowed": True,
            "historicalReplayAllowed": True,
            "backtestAllowed": True,
            "agentUseAllowed": True,
        }

    for index, old_bar in enumerate(old["bars"]):
        raw_version = canonical_hash({
            "fixture": "raw", "sequence": index + 1,
        })
        factor_version = canonical_hash({
            "fixture": "factor", "sequence": index + 1,
        })
        raw_versions.append(raw_version)
        factor_versions.append(factor_version)
        raw_content_hash = canonical_hash({
            "fixture": "raw-content", "sequence": index + 1,
        })
        factor_content_hash = canonical_hash({
            "fixture": "factor-content", "sequence": index + 1,
        })
        bars.append({
            "symbol": old_bar["symbol"],
            "tradeDate": old_bar["tradeDate"],
            "open": old_bar["open"],
            "high": old_bar["high"],
            "low": old_bar["low"],
            "close": old_bar["close"],
            "volume": old_bar["volume"],
            "volumeQualification": "PRESENT_VERIFIED",
            "volumeUnitCode": "SHARES",
            "volumeSemanticCode": "TRADED_VOLUME",
            "amount": old_bar["amount"],
            "amountQualification": (
                "MISSING"
                if old_bar["amount"] is None
                else "PRESENT_VERIFIED"
            ),
            "amountUnitCode": "CNY",
            "amountSemanticCode": "TRADED_AMOUNT",
            "turnoverRate": old_bar["turnoverRate"],
            "turnoverRateQualification": (
                "MISSING"
                if old_bar["turnoverRate"] is None
                else "PRESENT_VERIFIED"
            ),
            "turnoverRateUnitCode": "RATIO",
            "turnoverRateSemanticCode": "TURNOVER_RATE",
            "rawObservationVersion": raw_version,
            "rawContentHash": raw_content_hash,
            "factorObservationVersion": factor_version,
            "factorContentHash": factor_content_hash,
        })
        raw_lineage.append(observation_lineage(
            raw_version,
            raw_content_hash,
            f"RAW_DAILY_BAR|{payload['symbol']}|{old_bar['tradeDate']}",
            source_identities["rawSourceIdentity"],
        ))
        factor_lineage.append(observation_lineage(
            factor_version,
            factor_content_hash,
            (
                f"ADJUSTMENT_FACTOR|{payload['symbol']}|QFQ|"
                f"{old_bar['tradeDate']}"
            ),
            source_identities["factorSourceIdentity"],
        ))
    qfq = {
        "engineVersion": "QFQ_AS_OF_ENGINE_V1",
        "factorType": "QFQ",
        "factorCoverageMode": "DAILY_EXACT",
        "formula": "rawPrice(t)*factor(t)/factor(anchorTradeDate)",
        "divisionScale": 16,
        "divisionRoundingMode": "HALF_UP",
        "outputPriceScale": 4,
        "outputRoundingMode": "HALF_UP",
        "forwardFillAllowed": False,
        "crossProviderAllowed": False,
    }
    calendar_version = canonical_hash({"fixture": "calendar"})
    calendar_content_hash = canonical_hash({"fixture": "calendar-content"})
    action_version = canonical_hash({"fixture": "action"})
    action_content_hash = canonical_hash({"fixture": "action-content"})
    dataset_hash = canonical_hash({
        "fixture": "local-dataset",
        "symbol": payload["symbol"],
        "knowledgeCutoff": old["knowledgeCutoff"],
    })
    batch_version = canonical_hash({
        "fixture": "batch-lineage",
        "datasetHash": dataset_hash,
    })
    data_version = {
        "pitModelVersion": "PIT_MARKET_FACTS_V2",
        "sourceCode": "MOCK_PIT_MARKET_FACTS_V2",
        "sourceIdentities": source_identities,
        "qualification": "SYSTEM_KNOWLEDGE_PIT",
        "testDemoOnly": True,
        "batchLineage": [{
            "batchVersion": batch_version,
            "datasetVersion": f"LOCAL_PIT_DATASET_V2-{dataset_hash}",
            "providerDatasetVersion": None,
            "runNamespace": "TEST",
            "sourceCode": "MOCK_PIT_MARKET_FACTS_V2",
            "requestSourceIdentity": f"{payload['symbol']}.SZSE",
            "revisionQualification": "SYSTEM_KNOWLEDGE_ONLY",
            "assuranceLevel": "SYSTEM_KNOWLEDGE_PIT",
            "usageQualification": "TEST_DEMO_ONLY",
            "observedAt": old["knowledgeCutoff"],
            "responseComplete": True,
        }],
        "rawObservationVersions": raw_versions,
        "factorObservationVersions": factor_versions,
        "rawLineage": raw_lineage,
        "factorLineage": factor_lineage,
        "calendarObservationVersions": [calendar_version],
        "calendarLineage": [observation_lineage(
            calendar_version,
            calendar_content_hash,
            f"TRADING_CALENDAR|SZSE|{old['effectiveTradeDate']}",
            source_identities["calendarSourceIdentity"],
        )],
        "corporateActionObservationVersions": [action_version],
        "corporateActionLineage": [observation_lineage(
            action_version,
            action_content_hash,
            (
                f"CORPORATE_ACTION|{payload['symbol']}|"
                "MOCK-ACTION-001"
            ),
            source_identities["corporateActionSourceIdentity"],
        )],
    }
    strategy = copy.deepcopy(old["strategy"])
    strategy["canonicalContractVersion"] = "BACKTEST_CANONICAL_V2"
    input_payload = {
        "canonicalContractVersion": "BACKTEST_CANONICAL_V2",
        "contextProfile": "AGENT_CONTEXT_3AR3B0_V2",
        "contextSchemaVersion": "BACKTEST_CONTEXT_V2",
        "symbol": payload["symbol"],
        "requestTradeDate": payload["tradeDate"],
        "requestEffectiveTradeDate": old["effectiveTradeDate"],
        "anchorTradeDate": old["inputEndDate"],
        "decisionTime": old["decisionTime"],
        "knowledgeCutoff": old["knowledgeCutoff"],
        "qfqContract": qfq,
        "dataVersion": data_version,
        "bars": bars,
    }
    input_hash = canonical_hash(input_payload)
    strategy_hash = canonical_hash(strategy)
    result_hash = canonical_hash({
        "canonicalContractVersion": "BACKTEST_CANONICAL_V2",
        "inputDataHash": input_hash,
        "strategyDefinitionHash": strategy_hash,
        "result": old["result"],
        "subperiods": old["subperiods"],
        "stability": old["stability"],
    })
    payload["contextSnapshot"]["backtestContext"] = {
        "available": True,
        "queriedAt": old["queriedAt"],
        "queryScope": copy.deepcopy(old["queryScope"]),
        "producer": "AgentBacktestContextV2Service",
        "producerVersion": "JAVA_BACKTEST_CONTEXT_V2",
        "contextProfile": "AGENT_CONTEXT_3AR3B0_V2",
        "schemaVersion": "BACKTEST_CONTEXT_V2",
        "canonicalContractVersion": "BACKTEST_CANONICAL_V2",
        "pitModelVersion": "PIT_MARKET_FACTS_V2",
        "symbol": payload["symbol"],
        "requestTradeDate": payload["tradeDate"],
        "decisionTime": old["decisionTime"],
        "knowledgeCutoff": old["knowledgeCutoff"],
        "marketTimezone": "Asia/Shanghai",
        "adjustType": "QFQ",
        "sourceType": "DATABASE",
        "sourceTables": [
            "pit_market_fact_batches",
            "pit_market_fact_observations",
            "raw_daily_bar_facts_v2",
            "adjustment_factor_facts_v1",
            "trading_calendar_facts_v1",
            "corporate_action_facts_v1",
        ],
        "sourceStatus": "TEST_DEMO_PIT_MARKET_FACTS_V2",
        "effectiveTradeDate": old["effectiveTradeDate"],
        "exactTradeDateMatch": True,
        "inputStartDate": bars[0]["tradeDate"],
        "inputEndDate": bars[-1]["tradeDate"],
        "barCount": len(bars),
        "requiredBars": 120,
        "maximumBars": 500,
        "qfqContract": qfq,
        "dataVersion": data_version,
        "bars": bars,
        "strategy": strategy,
        "result": copy.deepcopy(old["result"]),
        "subperiods": copy.deepcopy(old["subperiods"]),
        "stability": copy.deepcopy(old["stability"]),
        "inputDataHash": input_hash,
        "strategyDefinitionHash": strategy_hash,
        "backtestResultHash": result_hash,
        "pointInTimeGuaranteed": True,
        "readSelectionFutureExcluded": True,
        "producerInputCutoffGuaranteed": True,
        "futureDataExcluded": True,
        "testDemoOnly": True,
        "limitations": [
            "TEST_DEMO_SYNTHETIC_FACTS_ONLY",
            "SYSTEM_KNOWLEDGE_PIT_IS_NOT_PROVIDER_PIT",
            "NO_PRE_CAPTURE_HISTORICAL_CLAIM",
            "RESEARCH_AND_SIMULATION_ONLY",
        ],
    }
    return payload


def refresh_v2_context_hashes(payload: dict) -> None:
    context = payload["contextSnapshot"]["backtestContext"]
    input_hash = canonical_hash({
        "canonicalContractVersion": context["canonicalContractVersion"],
        "contextProfile": context["contextProfile"],
        "contextSchemaVersion": context["schemaVersion"],
        "symbol": context["symbol"],
        "requestTradeDate": context["requestTradeDate"],
        "requestEffectiveTradeDate": context["effectiveTradeDate"],
        "anchorTradeDate": context["inputEndDate"],
        "decisionTime": context["decisionTime"],
        "knowledgeCutoff": context["knowledgeCutoff"],
        "qfqContract": context["qfqContract"],
        "dataVersion": context["dataVersion"],
        "bars": context["bars"],
    })
    context["inputDataHash"] = input_hash
    context["backtestResultHash"] = canonical_hash({
        "canonicalContractVersion": context["canonicalContractVersion"],
        "inputDataHash": input_hash,
        "strategyDefinitionHash": context["strategyDefinitionHash"],
        "result": context["result"],
        "subperiods": context["subperiods"],
        "stability": context["stability"],
    })


class ProviderNeutralPitV2Test(unittest.TestCase):

    def test_cross_language_market_fact_golden_vector(self):
        value = json.loads(
            (GOLDEN_DIR / "pit-market-facts-canonical-v2-input.json")
            .read_text(encoding="utf-8")
        )
        expected = (
            GOLDEN_DIR / "pit-market-facts-canonical-v2-canonical.txt"
        ).read_text(encoding="utf-8").rstrip("\r\n")
        expected_hash = (
            GOLDEN_DIR / "pit-market-facts-canonical-v2-sha256.txt"
        ).read_text(encoding="utf-8").strip()
        self.assertEqual(expected, canonical_text(value))
        self.assertEqual(expected_hash, canonical_hash(value))

    def test_qfq_golden_manifest_is_shared_but_python_does_not_calculate(self):
        fixture = json.loads(
            (GOLDEN_DIR / "qfq-as-of-engine-v1-golden-scenarios.json")
            .read_text(encoding="utf-8")
        )
        self.assertEqual("QFQ_AS_OF_ENGINE_V1", fixture["engineVersion"])
        self.assertEqual("DAILY_EXACT", fixture["factorCoverageMode"])
        self.assertEqual(list(range(1, 19)),
                         [item["id"] for item in fixture["scenarios"]])
        self.assertEqual(18, len({
            item["name"] for item in fixture["scenarios"]
        }))
        for item in fixture["scenarios"]:
            expected = item["expectedCanonicalResult"]
            expected_hash = item["expectedCanonicalHash"]
            self.assertRegex(expected_hash, r"^[0-9a-f]{64}$")
            self.assertEqual(expected_hash, canonical_hash(expected))
            self.assertIn("rawObservations", item["input"])
            self.assertIn("factorObservations", item["input"])
            self.assertIn("calendarObservations", item["input"])
            self.assertIn("corporateActionObservations", item["input"])
            self.assertIn("factorPredecessors", item["input"])

    def test_v2_backtest_is_validated_without_recomputing_qfq_or_strategy(self):
        request = AgentTeamRequest.model_validate(stage_3ar3b0_payload())
        result = StrategyBacktestRuleEngineV2().evaluate(
            request,
            GateStatus.PASS,
        )
        self.assertEqual("COMPLETED", result.status.value)
        self.assertEqual(5, len(result.findings))
        self.assertEqual(1, len(result.evidence))
        self.assertFalse(result.evidence[0].fields[
            "backtestContext"]["qfqContract"]["forwardFillAllowed"])

    def test_factor_missing_and_tampered_hash_fail_safely(self):
        unavailable = stage_3ar3b0_payload()
        context = unavailable["contextSnapshot"]["backtestContext"]
        keys = set(context)
        for key in keys - {
            "available", "queriedAt", "queryScope", "producer",
            "producerVersion", "contextProfile", "schemaVersion",
            "canonicalContractVersion", "pitModelVersion", "symbol",
            "requestTradeDate", "decisionTime", "knowledgeCutoff",
            "marketTimezone", "adjustType", "sourceType", "sourceTables",
            "sourceStatus", "pointInTimeGuaranteed",
            "readSelectionFutureExcluded",
            "producerInputCutoffGuaranteed", "futureDataExcluded",
        }:
            context.pop(key)
        context["available"] = False
        context["pointInTimeGuaranteed"] = False
        context["readSelectionFutureExcluded"] = False
        context["producerInputCutoffGuaranteed"] = False
        context["futureDataExcluded"] = False
        context["reasonCode"] = "PIT_FACTOR_UNAVAILABLE"
        context["reason"] = "exact DAILY_EXACT factor is absent"
        result = StrategyBacktestRuleEngineV2().evaluate(
            AgentTeamRequest.model_validate(unavailable),
            GateStatus.PASS,
        )
        self.assertEqual("INSUFFICIENT_DATA", result.status.value)
        self.assertEqual("PIT_FACTOR_UNAVAILABLE", result.errors[0].code)

        tampered = stage_3ar3b0_payload()
        tampered["contextSnapshot"]["backtestContext"][
            "inputDataHash"] = "0" * 64
        invalid = StrategyBacktestRuleEngineV2().evaluate(
            AgentTeamRequest.model_validate(tampered),
            GateStatus.PASS,
        )
        self.assertEqual("STRATEGY_BACKTEST_INPUT_INVALID",
                         invalid.errors[0].code)

    def test_optional_field_qualification_distinguishes_missing_and_zero(self):
        for field, unit, semantic in (
            ("amount", "CNY", "TRADED_AMOUNT"),
            ("turnoverRate", "RATIO", "TURNOVER_RATE"),
        ):
            with self.subTest(field=field):
                payload = stage_3ar3b0_payload()
                bar = payload["contextSnapshot"]["backtestContext"]["bars"][0]
                bar[field] = None
                bar[f"{field}Qualification"] = "MISSING"
                bar[f"{field}UnitCode"] = unit
                bar[f"{field}SemanticCode"] = semantic
                refresh_v2_context_hashes(payload)
                result = StrategyBacktestRuleEngineV2().evaluate(
                    AgentTeamRequest.model_validate(payload),
                    GateStatus.PASS,
                )
                self.assertEqual("COMPLETED", result.status.value)

        explicit_zero = stage_3ar3b0_payload()
        bar = explicit_zero["contextSnapshot"]["backtestContext"]["bars"][0]
        bar["volume"] = "0"
        bar["volumeQualification"] = "PRESENT_VERIFIED"
        refresh_v2_context_hashes(explicit_zero)
        result = StrategyBacktestRuleEngineV2().evaluate(
            AgentTeamRequest.model_validate(explicit_zero),
            GateStatus.PASS,
        )
        self.assertEqual("COMPLETED", result.status.value)

        for value, qualification in (
            (None, "MISSING"),
            ("0", "MISSING"),
            (None, "PRESENT_VERIFIED"),
        ):
            with self.subTest(volume=value, qualification=qualification):
                invalid = stage_3ar3b0_payload()
                bar = invalid["contextSnapshot"]["backtestContext"]["bars"][0]
                bar["volume"] = value
                bar["volumeQualification"] = qualification
                refresh_v2_context_hashes(invalid)
                result = StrategyBacktestRuleEngineV2().evaluate(
                    AgentTeamRequest.model_validate(invalid),
                    GateStatus.PASS,
                )
                self.assertEqual(
                    "STRATEGY_BACKTEST_INPUT_INVALID",
                    result.errors[0].code,
                )

    def test_new_rule_keeps_exactly_six_runs_and_safe_chief_result(self):
        response = AgentTeamOrchestrator().analyze(
            AgentTeamRequest.model_validate(stage_3ar3b0_payload())
        )
        self.assertEqual(6, len(response.agentRuns))
        self.assertEqual(
            [
                "DATA_QUALITY",
                "MARKET_REGIME",
                "TECHNICAL_ANALYSIS",
                "STRATEGY_BACKTEST",
                "ANNOUNCEMENT_RISK",
                "POSITION_RISK",
            ],
            [run.agentCode.value for run in response.agentRuns],
        )
        self.assertEqual(
            STAGE_3AR3B0_PIT_V2_RULE_VERSION,
            response.ruleVersion,
        )
        self.assertEqual("INSUFFICIENT_DATA",
                         response.finalDecision.decision.value)

    def test_offline_fixture_redaction_is_recursive_and_hash_stable(self):
        raw = {
            "provider": "DEMO",
            "Authorization": "secret",
            "payload": {
                "symbol": "000001",
                "records": [{
                    "tradeDate": "2026-07-27",
                    "Cookie": "secret",
                    "session_token": "secret",
                }],
            },
        }
        result = sanitize_fixture(raw, {"provider", "payload"})
        reject_sensitive(result.value)
        text = result.canonical.lower()
        self.assertNotIn("authorization", text)
        self.assertNotIn("cookie", text)
        self.assertNotIn("token", text)
        self.assertEqual(result.sha256, canonical_hash(result.value))
        with self.assertRaises(ValueError):
            sanitize_fixture(
                {"sourceFile": r"C:\Users\local-user\private\response.json"},
                {"sourceFile"},
            )
        with self.assertRaises(ValueError):
            sanitize_fixture(
                {"sourceUrl": "https://local-user:secret@example.invalid/data"},
                {"sourceUrl"},
            )


if __name__ == "__main__":
    unittest.main()
