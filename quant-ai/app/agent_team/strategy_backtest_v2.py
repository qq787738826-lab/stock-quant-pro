from __future__ import annotations

from copy import deepcopy
from datetime import date
from decimal import Decimal
import re
from typing import Any

from .backtest_canonical import canonical_hash, decimal_value
from .models import (
    AgentTeamRequest,
    Evidence,
    EvidenceCategory,
    EvidenceSourceType,
)
from .strategy_backtest import (
    ENGINE_VERSION,
    FROZEN_PARAMETERS,
    PARAMETER_SCHEMA_VERSION,
    ParsedBacktestContext,
    SPLIT_ALGORITHM,
    STRATEGY_CODE,
    StrategyBacktestRuleEngine,
    _instant,
    _sha,
    _text,
    _validate_result,
)


RULE_VERSION = "1.4.0-stage-3ar3b0-agent-team-pit-v2"
CONTEXT_PROFILE = "AGENT_CONTEXT_3AR3B0_V2"
CONTEXT_SCHEMA_VERSION = "BACKTEST_CONTEXT_V2"
CANONICAL_CONTRACT_VERSION = "BACKTEST_CANONICAL_V2"
PIT_MODEL_VERSION = "PIT_MARKET_FACTS_V2"
PRODUCER = "AgentBacktestContextV2Service"
PRODUCER_VERSION = "JAVA_BACKTEST_CONTEXT_V2"
SOURCE_TABLES = [
    "pit_market_fact_batches",
    "pit_market_fact_observations",
    "raw_daily_bar_facts_v2",
    "adjustment_factor_facts_v1",
    "trading_calendar_facts_v1",
    "corporate_action_facts_v1",
]
LIMITATIONS = [
    "TEST_DEMO_SYNTHETIC_FACTS_ONLY",
    "SYSTEM_KNOWLEDGE_PIT_IS_NOT_PROVIDER_PIT",
    "NO_PRE_CAPTURE_HISTORICAL_CLAIM",
    "RESEARCH_AND_SIMULATION_ONLY",
]
UNAVAILABLE = {
    "PIT_CALENDAR_UNAVAILABLE",
    "PIT_RAW_BAR_UNAVAILABLE",
    "PIT_FACTOR_UNAVAILABLE",
    "PIT_CORPORATE_ACTION_LINEAGE_UNAVAILABLE",
    "PIT_CROSS_PROVIDER_FORBIDDEN",
    "PIT_MARKET_FACT_INVALID",
    "PIT_FUTURE_REQUEST_DATE",
    "PIT_DECISION_TIME_NOT_REACHED",
    "PIT_TEST_DEMO_PROFILE_DISABLED",
    "BACKTEST_SAMPLE_INSUFFICIENT",
    "BACKTEST_REPLAY_MISMATCH",
}
BASE_FIELDS = {
    "available", "queriedAt", "queryScope", "producer", "producerVersion",
    "contextProfile", "schemaVersion", "canonicalContractVersion",
    "pitModelVersion", "symbol", "requestTradeDate", "decisionTime",
    "knowledgeCutoff", "marketTimezone", "adjustType", "sourceType",
    "sourceTables", "sourceStatus", "pointInTimeGuaranteed",
    "readSelectionFutureExcluded", "producerInputCutoffGuaranteed",
    "futureDataExcluded",
}
AVAILABLE_FIELDS = BASE_FIELDS | {
    "effectiveTradeDate", "exactTradeDateMatch", "inputStartDate",
    "inputEndDate", "barCount", "requiredBars", "maximumBars",
    "qfqContract", "dataVersion", "bars", "strategy", "result",
    "subperiods", "stability", "inputDataHash",
    "strategyDefinitionHash", "backtestResultHash", "testDemoOnly",
    "limitations",
}
DATA_VERSION_FIELDS = {
    "pitModelVersion", "sourceCode", "sourceInstrumentId", "qualification",
    "testDemoOnly", "batchLineage", "rawObservationVersions",
    "factorObservationVersions", "calendarObservationVersions",
    "calendarLineage", "corporateActionObservationVersions",
    "corporateActionLineage",
}
BATCH_LINEAGE_FIELDS = {
    "batchVersion", "datasetVersion", "providerDatasetVersion",
    "runNamespace", "sourceCode", "sourceInstrumentId",
    "revisionQualification", "assuranceLevel", "usageQualification",
    "observedAt", "responseComplete",
}
OBSERVATION_LINEAGE_FIELDS = {
    "observationVersion", "canonicalContentHash", "naturalKey", "knownAt",
    "revisionQualification",
}


class StrategyBacktestRuleEngineV2(StrategyBacktestRuleEngine):
    """Validates Java's V2 lineage/hashes, then reuses frozen 2F scoring."""

    unavailable_reason_codes = UNAVAILABLE

    @staticmethod
    def _validate_base(raw: dict[str, Any], request: AgentTeamRequest) -> None:
        if not isinstance(raw, dict):
            raise ValueError("backtestContext must be an object")
        available = raw.get("available")
        expected_fields = AVAILABLE_FIELDS if available is True else (
            BASE_FIELDS | {"reasonCode", "reason"}
            | ({"actualBars", "requiredBars"}
               if raw.get("reasonCode") == "BACKTEST_SAMPLE_INSUFFICIENT"
               else set())
        )
        if available not in (True, False) or set(raw) != expected_fields:
            raise ValueError("V2 backtestContext field whitelist mismatch")
        expected = {
            "producer": PRODUCER,
            "producerVersion": PRODUCER_VERSION,
            "contextProfile": CONTEXT_PROFILE,
            "schemaVersion": CONTEXT_SCHEMA_VERSION,
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "pitModelVersion": PIT_MODEL_VERSION,
            "symbol": request.symbol,
            "requestTradeDate": request.tradeDate.isoformat(),
            "marketTimezone": "Asia/Shanghai",
            "adjustType": "QFQ",
            "sourceType": "DATABASE",
            "sourceTables": SOURCE_TABLES,
            "sourceStatus": "TEST_DEMO_PIT_MARKET_FACTS_V2",
        }
        if any(raw.get(field) != value for field, value in expected.items()):
            raise ValueError("V2 backtestContext identity mismatch")
        if raw.get("queryScope") != {
            "symbol": request.symbol,
            "tradeDate": request.tradeDate.isoformat(),
        }:
            raise ValueError("V2 queryScope mismatch")
        queried_at = _instant(raw.get("queriedAt"))
        decision = _instant(raw.get("decisionTime"))
        cutoff = _instant(raw.get("knowledgeCutoff"))
        if decision != cutoff or queried_at < cutoff:
            raise ValueError("V2 cutoff ordering mismatch")
        if available is False:
            if raw.get("reasonCode") not in UNAVAILABLE:
                raise ValueError("V2 unavailable reasonCode is not frozen")
            if not isinstance(raw.get("reason"), str) or not raw["reason"].strip():
                raise ValueError("V2 unavailable reason is blank")
            if raw["reasonCode"] == "BACKTEST_SAMPLE_INSUFFICIENT":
                actual = raw.get("actualBars")
                if (isinstance(actual, bool) or not isinstance(actual, int)
                        or not 0 <= actual < 120
                        or raw.get("requiredBars") != 120):
                    raise ValueError("V2 sample boundary mismatch")

    def _validate_available(
        self,
        raw: dict[str, Any],
        request: AgentTeamRequest,
    ) -> ParsedBacktestContext:
        if any(raw.get(field) is not True for field in (
            "pointInTimeGuaranteed", "readSelectionFutureExcluded",
            "producerInputCutoffGuaranteed", "futureDataExcluded",
            "testDemoOnly",
        )):
            raise ValueError("V2 reliable/test flags mismatch")
        if raw.get("requiredBars") != 120 or raw.get("maximumBars") != 500:
            raise ValueError("V2 window limits mismatch")
        if raw.get("limitations") != LIMITATIONS:
            raise ValueError("V2 limitation ordering mismatch")
        qfq = raw.get("qfqContract")
        if qfq != {
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
        }:
            raise ValueError("V2 QFQ contract mismatch")

        bars = raw.get("bars")
        count = raw.get("barCount")
        if (not isinstance(bars, list) or isinstance(count, bool)
                or not isinstance(count, int) or count != len(bars)
                or not 120 <= count <= 500):
            raise ValueError("V2 barCount mismatch")
        previous: date | None = None
        raw_versions: list[str] = []
        factor_versions: list[str] = []
        for item in bars:
            if not isinstance(item, dict) or set(item) != {
                "symbol", "tradeDate", "open", "high", "low", "close",
                "volume", "amount", "turnoverRate",
                "rawObservationVersion", "rawContentHash",
                "factorObservationVersion", "factorContentHash",
            } or item.get("symbol") != request.symbol:
                raise ValueError("V2 bar field whitelist mismatch")
            trade_date = date.fromisoformat(_text(item, "tradeDate"))
            if (trade_date > request.tradeDate
                    or previous is not None and trade_date <= previous):
                raise ValueError("V2 bar order mismatch")
            previous = trade_date
            values = [
                decimal_value(item[field])
                for field in ("open", "high", "low", "close")
            ]
            open_value, high, low, close = values
            if (min(values) <= 0 or high < max(open_value, low, close)
                    or low > min(open_value, high, close)):
                raise ValueError("V2 OHLC invalid")
            volume = decimal_value(item["volume"])
            if volume < 0 or volume != volume.to_integral_value():
                raise ValueError("V2 volume invalid")
            for field in ("amount", "turnoverRate"):
                if item[field] is not None and decimal_value(item[field]) < 0:
                    raise ValueError("V2 optional market value invalid")
            raw_versions.append(_sha(item["rawObservationVersion"]))
            factor_versions.append(_sha(item["factorObservationVersion"]))
            _sha(item["rawContentHash"])
            _sha(item["factorContentHash"])
        if previous is None:
            raise ValueError("V2 bars empty")
        if (raw.get("effectiveTradeDate") != previous.isoformat()
                or raw.get("inputStartDate") != bars[0]["tradeDate"]
                or raw.get("inputEndDate") != bars[-1]["tradeDate"]
                or raw.get("exactTradeDateMatch") is not (
                    previous == request.tradeDate)):
            raise ValueError("V2 date bounds mismatch")

        data_version = raw.get("dataVersion")
        if (not isinstance(data_version, dict)
                or set(data_version) != DATA_VERSION_FIELDS
                or data_version.get("pitModelVersion") != PIT_MODEL_VERSION
                or data_version.get("qualification") != "SYSTEM_KNOWLEDGE_PIT"
                or data_version.get("testDemoOnly") is not True
                or data_version.get("sourceCode") != "MOCK_PIT_MARKET_FACTS_V2"
                or not isinstance(data_version.get("sourceInstrumentId"), str)
                or not data_version.get("sourceInstrumentId")
                or data_version.get("rawObservationVersions") != raw_versions
                or data_version.get("factorObservationVersions") != factor_versions):
            raise ValueError("V2 dataVersion mismatch")
        self._validate_batch_lineage(
            data_version["batchLineage"],
            data_version["sourceInstrumentId"],
            _instant(raw["knowledgeCutoff"]),
        )
        self._validate_observation_lineage(
            data_version["calendarObservationVersions"],
            data_version["calendarLineage"],
            _instant(raw["knowledgeCutoff"]),
            "calendar",
        )
        self._validate_observation_lineage(
            data_version["corporateActionObservationVersions"],
            data_version["corporateActionLineage"],
            _instant(raw["knowledgeCutoff"]),
            "corporate action",
        )

        strategy = raw.get("strategy")
        self._validate_strategy_v2(strategy)
        result = raw.get("result")
        trade_count = _validate_result(result, request.tradeDate)
        subperiods = raw.get("subperiods")
        self._validate_subperiods(subperiods, bars, request.tradeDate)
        stability = raw.get("stability")
        if (not isinstance(stability, dict)
                or stability.get("splitAlgorithm") != SPLIT_ALGORITHM
                or stability.get("validSubperiodCount") != 3):
            raise ValueError("V2 stability invalid")
        positive = stability.get("positiveSubperiodCount")
        if (isinstance(positive, bool) or not isinstance(positive, int)
                or not 0 <= positive <= 3
                or positive != sum(
                    decimal_value(item["result"]["totalReturn"]) > 0
                    for item in subperiods)):
            raise ValueError("V2 positive subperiod count invalid")

        input_hash = _sha(raw.get("inputDataHash"))
        strategy_hash = _sha(raw.get("strategyDefinitionHash"))
        result_hash = _sha(raw.get("backtestResultHash"))
        input_payload = {
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "contextProfile": CONTEXT_PROFILE,
            "contextSchemaVersion": CONTEXT_SCHEMA_VERSION,
            "symbol": request.symbol,
            "requestTradeDate": request.tradeDate.isoformat(),
            "requestEffectiveTradeDate": raw["effectiveTradeDate"],
            "anchorTradeDate": raw["inputEndDate"],
            "decisionTime": raw["decisionTime"],
            "knowledgeCutoff": raw["knowledgeCutoff"],
            "qfqContract": deepcopy(qfq),
            "dataVersion": deepcopy(data_version),
            "bars": deepcopy(bars),
        }
        if canonical_hash(input_payload) != input_hash:
            raise ValueError("V2 inputDataHash mismatch")
        if canonical_hash(strategy) != strategy_hash:
            raise ValueError("V2 strategyDefinitionHash mismatch")
        result_payload = {
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "inputDataHash": input_hash,
            "strategyDefinitionHash": strategy_hash,
            "result": deepcopy(result),
            "subperiods": deepcopy(subperiods),
            "stability": deepcopy(stability),
        }
        if canonical_hash(result_payload) != result_hash:
            raise ValueError("V2 backtestResultHash mismatch")
        return ParsedBacktestContext(
            raw=raw,
            result=result,
            subperiods=subperiods,
            trade_count=trade_count,
            valid_subperiod_count=3,
            positive_subperiod_count=positive,
            total_return=decimal_value(result["totalReturn"]),
            max_drawdown=decimal_value(result["maxDrawdown"]),
            win_rate=decimal_value(result["winRate"]),
            profit_loss_ratio=decimal_value(result["profitLossRatio"]),
            maximum_known_at=_instant(raw["knowledgeCutoff"]),
            backtest_result_hash=result_hash,
        )

    @staticmethod
    def _validate_batch_lineage(
        batches: Any,
        source_instrument_id: str,
        knowledge_cutoff: Any,
    ) -> None:
        if not isinstance(batches, list) or not batches:
            raise ValueError("V2 batch lineage missing")
        previous_version: str | None = None
        for batch in batches:
            if (not isinstance(batch, dict)
                    or set(batch) != BATCH_LINEAGE_FIELDS):
                raise ValueError("V2 batch lineage fields invalid")
            batch_version = _sha(batch.get("batchVersion"))
            dataset_version = batch.get("datasetVersion")
            provider_dataset_version = batch.get("providerDatasetVersion")
            if (previous_version is not None
                    and batch_version <= previous_version):
                raise ValueError("V2 batch lineage order invalid")
            previous_version = batch_version
            if (not isinstance(dataset_version, str)
                    or re.fullmatch(
                        r"LOCAL_PIT_DATASET_V2-[0-9a-f]{64}",
                        dataset_version,
                    ) is None
                    or provider_dataset_version is not None
                    and (not isinstance(provider_dataset_version, str)
                         or not provider_dataset_version)
                    or batch.get("runNamespace") not in {"TEST", "DEMO"}
                    or batch.get("sourceCode") != "MOCK_PIT_MARKET_FACTS_V2"
                    or batch.get("sourceInstrumentId") != source_instrument_id
                    or batch.get("revisionQualification")
                    != "SYSTEM_KNOWLEDGE_ONLY"
                    or batch.get("assuranceLevel") != "SYSTEM_KNOWLEDGE_PIT"
                    or batch.get("usageQualification") != "TEST_DEMO_ONLY"
                    or batch.get("responseComplete") is not True
                    or _instant(batch.get("observedAt")) > knowledge_cutoff):
                raise ValueError("V2 batch lineage qualification invalid")

    @staticmethod
    def _validate_observation_lineage(
        versions: Any,
        lineage: Any,
        knowledge_cutoff: Any,
        label: str,
    ) -> None:
        if (not isinstance(versions, list)
                or not isinstance(lineage, list)
                or len(versions) != len(lineage)):
            raise ValueError(f"V2 {label} lineage length invalid")
        previous_key: str | None = None
        for version, item in zip(versions, lineage):
            if (not isinstance(item, dict)
                    or set(item) != OBSERVATION_LINEAGE_FIELDS
                    or _sha(version) != _sha(item.get("observationVersion"))
                    or _sha(item.get("canonicalContentHash"))
                    != item.get("canonicalContentHash")
                    or not isinstance(item.get("naturalKey"), str)
                    or not item.get("naturalKey")
                    or item.get("revisionQualification")
                    != "SYSTEM_KNOWLEDGE_ONLY"
                    or _instant(item.get("knownAt")) > knowledge_cutoff):
                raise ValueError(f"V2 {label} lineage invalid")
            natural_key = item["naturalKey"]
            if previous_key is not None and natural_key <= previous_key:
                raise ValueError(f"V2 {label} lineage order invalid")
            previous_key = natural_key

    @staticmethod
    def _validate_strategy_v2(strategy: Any) -> None:
        if not isinstance(strategy, dict) or set(strategy) != {
            "canonicalContractVersion", "strategyCode", "strategyVersion",
            "engineVersion", "parameterSchemaVersion", "parameters",
        }:
            raise ValueError("V2 strategy fields invalid")
        expected = {
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "strategyCode": STRATEGY_CODE,
            "strategyVersion": STRATEGY_CODE,
            "engineVersion": ENGINE_VERSION,
            "parameterSchemaVersion": PARAMETER_SCHEMA_VERSION,
        }
        if any(strategy.get(field) != value for field, value in expected.items()):
            raise ValueError("V2 strategy identity invalid")
        parameters = strategy.get("parameters")
        if not isinstance(parameters, dict) or set(parameters) != set(FROZEN_PARAMETERS):
            raise ValueError("V2 frozen parameters incomplete")
        for field, expected_value in FROZEN_PARAMETERS.items():
            value = parameters[field]
            if field == "maxHoldingDays":
                if isinstance(value, bool) or value != expected_value:
                    raise ValueError("V2 maxHoldingDays invalid")
            elif decimal_value(value) != expected_value:
                raise ValueError(f"V2 {field} invalid")

    @staticmethod
    def _evidence(
        request: AgentTeamRequest,
        parsed: ParsedBacktestContext,
    ) -> Evidence:
        return Evidence(
            evidenceId=f"sb-context-{request.contextHash}",
            category=EvidenceCategory.BACKTEST_RESULT,
            sourceType=EvidenceSourceType.JAVA_ENGINE,
            sourceName=PRODUCER,
            sourceRef="contextSnapshot.backtestContext",
            symbol=request.symbol,
            tradeDate=request.tradeDate,
            observedAt=parsed.maximum_known_at,
            collectedAt=request.requestedAt,
            fields={"backtestContext": deepcopy(parsed.raw)},
            contentHash=parsed.backtest_result_hash,
        )
