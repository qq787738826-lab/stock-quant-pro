from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date, datetime, time, timezone
from decimal import Decimal, ROUND_HALF_UP
import re
from typing import Any
from zoneinfo import ZoneInfo

from .backtest_canonical import canonical_hash, decimal_value
from .models import (
    AgentDecision,
    AgentError,
    AgentTeamRequest,
    Evidence,
    EvidenceCategory,
    EvidenceSourceType,
    Finding,
    GateStatus,
    RunStatus,
    Severity,
)


RULE_VERSION = "1.4.0-stage-2f-strategy-backtest-v1"
CONTEXT_PROFILE = "AGENT_CONTEXT_2F_V1"
CONTEXT_SCHEMA_VERSION = "BACKTEST_CONTEXT_V1"
CANONICAL_CONTRACT_VERSION = "BACKTEST_CANONICAL_V1"
PIT_MODEL_VERSION = "PIT_DAILY_BAR_OBSERVATION_V1"
PRODUCER = "AgentBacktestContextService"
PRODUCER_VERSION = "JAVA_BACKTEST_CONTEXT_V1"
STRATEGY_CODE = "SMA20_NEXT_OPEN_RISK_EXIT_V1"
ENGINE_VERSION = "BACKTEST_ENGINE_V1"
PARAMETER_SCHEMA_VERSION = "BACKTEST_PARAMS_V1"
SPLIT_ALGORITHM = "CHRONOLOGICAL_THIRDS_REMAINDER_TO_EARLY_THEN_MIDDLE_V1"
MARKET_TIMEZONE = "Asia/Shanghai"

UNAVAILABLE_REASON_CODES = frozenset({
    "BACKTEST_NO_TRUSTED_PIT_DAILY_BARS",
    "BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE",
    "BACKTEST_SOURCE_REVISION_UNVERIFIABLE",
    "BACKTEST_CUTOFF_POLLUTION_UNRESOLVED",
    "BACKTEST_SAMPLE_INSUFFICIENT",
    "BACKTEST_DAILY_BAR_INVALID",
    "BACKTEST_STRATEGY_VERSION_UNVERIFIABLE",
    "BACKTEST_PARAMS_INVALID",
    "BACKTEST_HASH_MISMATCH",
    "BACKTEST_REPLAY_MISMATCH",
    "BACKTEST_FUTURE_REQUEST_DATE",
    "BACKTEST_DECISION_TIME_NOT_REACHED",
})
INPUT_INVALID = "STRATEGY_BACKTEST_INPUT_INVALID"
SAMPLE_INSUFFICIENT = "STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT"
FINDING_CODES = (
    "STRATEGY_BACKTEST_SAMPLE_SUFFICIENT",
    "STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED",
    "STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED",
    "STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED",
    "STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED",
)
FINDING_TITLES = (
    "回测交易样本达到规则门槛",
    "回测总收益表现",
    "回测最大回撤风险",
    "回测胜率与盈亏比质量",
    "回测跨时间子区间稳定性",
)
FROZEN_PARAMETERS = {
    "initialCapital": Decimal("100000"),
    "maxHoldingDays": 10,
    "stopLossPct": Decimal("0.05"),
    "takeProfitPct": Decimal("0.08"),
    "trailingStopPct": Decimal("0.04"),
    "commissionRate": Decimal("0.0003"),
    "stampDutyRate": Decimal("0.0005"),
}
BASE_CONTEXT_FIELDS = frozenset({
    "available",
    "queriedAt",
    "queryScope",
    "producer",
    "producerVersion",
    "contextProfile",
    "schemaVersion",
    "canonicalContractVersion",
    "pitModelVersion",
    "symbol",
    "requestTradeDate",
    "decisionTime",
    "knowledgeCutoff",
    "marketTimezone",
    "adjustType",
    "sourceType",
    "sourceTables",
    "sourceStatus",
    "pointInTimeGuaranteed",
    "readSelectionFutureExcluded",
    "producerInputCutoffGuaranteed",
    "futureDataExcluded",
})
AVAILABLE_CONTEXT_FIELDS = BASE_CONTEXT_FIELDS | frozenset({
    "effectiveTradeDate",
    "exactTradeDateMatch",
    "inputStartDate",
    "inputEndDate",
    "barCount",
    "requiredBars",
    "maximumBars",
    "dataVersion",
    "lineage",
    "bars",
    "strategy",
    "result",
    "subperiods",
    "stability",
    "inputDataHash",
    "strategyDefinitionHash",
    "backtestResultHash",
    "limitations",
})
UNAVAILABLE_CONTEXT_FIELDS = BASE_CONTEXT_FIELDS | frozenset({
    "reasonCode",
    "reason",
})
SAMPLE_UNAVAILABLE_CONTEXT_FIELDS = UNAVAILABLE_CONTEXT_FIELDS | frozenset({
    "actualBars",
    "requiredBars",
})
AVAILABLE_LIMITATIONS = [
    "RESEARCH_AND_SIMULATION_ONLY",
    "LOCAL_OBSERVATION_TIME_IS_NOT_PROVIDER_PUBLICATION_TIME",
    "CONTENT_HASH_DOES_NOT_REPLACE_KNOWLEDGE_TIME",
]


@dataclass(frozen=True)
class StrategyBacktestEvaluation:
    status: RunStatus
    gate_status: GateStatus
    decision: AgentDecision
    score: int
    confidence: int
    summary: str
    findings: tuple[Finding, ...]
    evidence: tuple[Evidence, ...]
    errors: tuple[AgentError, ...]


@dataclass(frozen=True)
class ParsedBacktestContext:
    raw: dict[str, Any]
    result: dict[str, Any]
    subperiods: list[dict[str, Any]]
    trade_count: int
    valid_subperiod_count: int
    positive_subperiod_count: int
    total_return: Decimal
    max_drawdown: Decimal
    win_rate: Decimal
    profit_loss_ratio: Decimal
    maximum_known_at: datetime
    backtest_result_hash: str


class StrategyBacktestRuleEngine:

    def evaluate(
        self,
        request: AgentTeamRequest,
        data_quality_gate: GateStatus,
    ) -> StrategyBacktestEvaluation:
        if data_quality_gate is GateStatus.BLOCKED:
            return StrategyBacktestEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=GateStatus.BLOCKED,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="因DATA_QUALITY门禁阻断，未解释策略回测事实。",
                findings=(),
                evidence=(),
                errors=(),
            )
        if data_quality_gate not in (GateStatus.PASS, GateStatus.WARN):
            return self._invalid(data_quality_gate)

        raw = deepcopy(request.contextSnapshot.backtestContext)
        try:
            self._validate_base(raw, request)
        except (KeyError, TypeError, ValueError):
            return self._invalid(data_quality_gate)
        if raw.get("available") is not True:
            reason_code = raw.get("reasonCode")
            if reason_code not in UNAVAILABLE_REASON_CODES:
                return self._invalid(data_quality_gate)
            return StrategyBacktestEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=data_quality_gate,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="可靠backtestContext不可用，未形成策略回测评分。",
                findings=(),
                evidence=(),
                errors=(AgentError(
                    code=reason_code,
                    message="Java提供的可靠回测事实上下文不可用。",
                ),),
            )

        try:
            parsed = self._validate_available(raw, request)
        except (KeyError, TypeError, ValueError):
            return self._invalid(data_quality_gate)
        evidence = self._evidence(request, parsed)
        if parsed.trade_count < 10 or parsed.valid_subperiod_count < 2:
            return StrategyBacktestEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=data_quality_gate,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="可靠回测事实的交易或子区间样本不足，未形成正常性能评分。",
                findings=(),
                evidence=(evidence,),
                errors=(AgentError(
                    code=SAMPLE_INSUFFICIENT,
                    message="tradeCount小于10或有效子区间少于2。",
                ),),
            )

        impacts = (
            0,
            _total_return_impact(parsed.total_return),
            _drawdown_impact(parsed.max_drawdown),
            _win_rate_impact(parsed.win_rate)
            + _profit_loss_ratio_impact(parsed.profit_loss_ratio),
            _subperiod_impact(parsed.positive_subperiod_count),
        )
        score = max(0, min(100, 50 + sum(impacts)))
        confidence = _confidence(parsed.trade_count)
        if data_quality_gate is GateStatus.WARN:
            confidence = min(confidence, 50)
        findings = self._findings(request, parsed, evidence, impacts)
        return StrategyBacktestEvaluation(
            status=RunStatus.COMPLETED,
            gate_status=data_quality_gate,
            decision=AgentDecision.WARN,
            score=score,
            confidence=confidence,
            summary="已按冻结PIT、版本、参数和Hash解释可靠回测事实；结果仅用于研究。",
            findings=findings,
            evidence=(evidence,),
            errors=(),
        )

    @staticmethod
    def _invalid(gate: GateStatus) -> StrategyBacktestEvaluation:
        return StrategyBacktestEvaluation(
            status=RunStatus.INSUFFICIENT_DATA,
            gate_status=gate,
            decision=AgentDecision.NOT_APPLICABLE,
            score=0,
            confidence=0,
            summary="STRATEGY_BACKTEST输入契约非法，未形成回测评分。",
            findings=(),
            evidence=(),
            errors=(AgentError(
                code=INPUT_INVALID,
                message="backtestContext的Schema、版本、Hash、数值或交易明细非法。",
            ),),
        )

    @staticmethod
    def _validate_base(raw: dict[str, Any], request: AgentTeamRequest) -> None:
        if not isinstance(raw, dict):
            raise ValueError("backtestContext必须是对象")
        available = raw.get("available")
        if available is True:
            expected_fields = AVAILABLE_CONTEXT_FIELDS
        elif available is False:
            expected_fields = (
                SAMPLE_UNAVAILABLE_CONTEXT_FIELDS
                if raw.get("reasonCode") == "BACKTEST_SAMPLE_INSUFFICIENT"
                else UNAVAILABLE_CONTEXT_FIELDS
            )
        else:
            raise ValueError("backtestContext.available必须是布尔值")
        if set(raw) != expected_fields:
            raise ValueError("backtestContext字段白名单无效")
        if available is False:
            if (not isinstance(raw.get("reasonCode"), str)
                    or not raw["reasonCode"].strip()
                    or not isinstance(raw.get("reason"), str)
                    or not raw["reason"].strip()):
                raise ValueError("不可用backtestContext必须给出reasonCode和reason")
            if raw["reasonCode"] == "BACKTEST_SAMPLE_INSUFFICIENT":
                actual_bars = raw.get("actualBars")
                if (isinstance(actual_bars, bool)
                        or not isinstance(actual_bars, int)
                        or not 0 <= actual_bars < 120
                        or raw.get("requiredBars") != 120):
                    raise ValueError("样本不足数量契约无效")
        expected = {
            "producer": PRODUCER,
            "producerVersion": PRODUCER_VERSION,
            "contextProfile": CONTEXT_PROFILE,
            "schemaVersion": CONTEXT_SCHEMA_VERSION,
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "pitModelVersion": PIT_MODEL_VERSION,
            "symbol": request.symbol,
            "requestTradeDate": request.tradeDate.isoformat(),
            "marketTimezone": MARKET_TIMEZONE,
            "adjustType": "QFQ",
            "sourceType": "DATABASE",
        }
        for field, value in expected.items():
            if raw.get(field) != value:
                raise ValueError(f"backtestContext.{field}不一致")
        scope = raw.get("queryScope")
        if scope != {
            "symbol": request.symbol,
            "tradeDate": request.tradeDate.isoformat(),
        }:
            raise ValueError("backtestContext.queryScope不一致")
        if raw.get("sourceTables") != [
            "market_data_observation_batches",
            "daily_bar_observations",
        ] or raw.get("sourceStatus") != "LOCAL_PIT_OBSERVATIONS":
            raise ValueError("backtestContext来源表或来源状态无效")
        queried_at = _instant(raw.get("queriedAt"))
        decision_time = _instant(raw.get("decisionTime"))
        cutoff = _instant(raw.get("knowledgeCutoff"))
        if decision_time != cutoff or queried_at < cutoff:
            raise ValueError("decisionTime必须等于knowledgeCutoff且不得晚于queriedAt")
        local = decision_time.astimezone(ZoneInfo(MARKET_TIMEZONE))
        if (local.date() != request.tradeDate
                or (local.hour, local.minute, local.second, local.microsecond)
                != (23, 59, 59, 999999)):
            raise ValueError("decisionTime不是请求日期上海时区日终")

    def _validate_available(
        self,
        raw: dict[str, Any],
        request: AgentTeamRequest,
    ) -> ParsedBacktestContext:
        for field in (
            "pointInTimeGuaranteed",
            "readSelectionFutureExcluded",
            "producerInputCutoffGuaranteed",
            "futureDataExcluded",
        ):
            if raw.get(field) is not True:
                raise ValueError(f"{field}必须为true")
        if raw.get("available") is not True:
            raise ValueError("available必须为true")
        if raw.get("requiredBars") != 120 or raw.get("maximumBars") != 500:
            raise ValueError("可靠回测窗口门槛无效")
        if raw.get("limitations") != AVAILABLE_LIMITATIONS:
            raise ValueError("可靠回测限制说明无效")
        bars = raw.get("bars")
        bar_count = raw.get("barCount")
        if (not isinstance(bars, list) or isinstance(bar_count, bool)
                or not isinstance(bar_count, int)
                or bar_count != len(bars) or not 120 <= bar_count <= 500):
            raise ValueError("barCount无效")
        request_date = request.tradeDate
        cutoff = _instant(raw["knowledgeCutoff"])
        previous: date | None = None
        observation_versions: list[str] = []
        dataset_versions: set[str] = set()
        batch_versions: set[str] = set()
        source_revisions: set[tuple[str, str]] = set()
        known_times: list[datetime] = []
        for item in bars:
            if not isinstance(item, dict) or item.get("symbol") != request.symbol:
                raise ValueError("bar证券代码无效")
            trade_date = date.fromisoformat(_text(item, "tradeDate"))
            if (trade_date.weekday() >= 5
                    or trade_date > request_date
                    or previous is not None and trade_date <= previous):
                raise ValueError("bar日期无效")
            previous = trade_date
            _validate_bar(item)
            known_at = _instant(item.get("knownAt"))
            first_observed = _instant(item.get("firstObservedAt"))
            earliest_known_at = datetime.combine(
                trade_date,
                time(15, 0),
                tzinfo=ZoneInfo(MARKET_TIMEZONE),
            ).astimezone(timezone.utc)
            if (first_observed < earliest_known_at
                    or known_at < earliest_known_at
                    or known_at > cutoff
                    or first_observed > known_at):
                raise ValueError("bar knowledge-time无效")
            for field in (
                "sourceCode",
                "batchVersion",
                "datasetVersion",
                "observationVersion",
                "canonicalContentHash",
            ):
                if not _text(item, field):
                    raise ValueError("bar lineage字段为空")
            _sha(item["observationVersion"])
            _sha(item["canonicalContentHash"])
            source_revision = item.get("sourceRevision")
            if not isinstance(source_revision, str) or not source_revision.strip():
                raise ValueError("可靠回测bar必须有可验证sourceRevision")
            content_payload = {
                "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
                "sourceCode": item["sourceCode"],
                "symbol": item["symbol"],
                "tradeDate": item["tradeDate"],
                "adjustType": "QFQ",
                "open": item["open"],
                "high": item["high"],
                "low": item["low"],
                "close": item["close"],
                "volume": item["volume"],
                "amount": item["amount"],
                "turnoverRate": item["turnoverRate"],
            }
            if canonical_hash(content_payload) != item["canonicalContentHash"]:
                raise ValueError("bar canonicalContentHash不一致")
            observation_payload = {
                "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
                "batchVersion": item["batchVersion"],
                "datasetVersion": item["datasetVersion"],
                "sourceCode": item["sourceCode"],
                "sourceRevision": source_revision,
                "firstObservedAt": item["firstObservedAt"],
                "knownAt": item["knownAt"],
                "canonicalContentHash": item["canonicalContentHash"],
            }
            if canonical_hash(observation_payload) != item["observationVersion"]:
                raise ValueError("bar observationVersion不一致")
            observation_versions.append(item["observationVersion"])
            dataset_versions.add(item["datasetVersion"])
            batch_versions.add(item["batchVersion"])
            source_revisions.add((item["sourceCode"], source_revision))
            known_times.append(known_at)
        if previous is None:
            raise ValueError("bars不能为空")
        if (raw.get("effectiveTradeDate") != previous.isoformat()
                or raw.get("inputStartDate") != bars[0]["tradeDate"]
                or raw.get("inputEndDate") != bars[-1]["tradeDate"]
                or raw.get("exactTradeDateMatch") is not (previous == request_date)):
            raise ValueError("输入日期边界不一致")

        data_version = raw.get("dataVersion")
        expected_source_revisions = [
            {"sourceCode": source, "sourceRevision": revision}
            for source, revision in sorted(source_revisions)
        ]
        if (not isinstance(data_version, dict)
                or set(data_version) != {
                    "pitModelVersion",
                    "datasetVersions",
                    "batchVersions",
                    "selectedObservationVersions",
                    "sourceRevisions",
                }
                or data_version.get("pitModelVersion") != PIT_MODEL_VERSION
                or data_version.get("datasetVersions") != sorted(dataset_versions)
                or data_version.get("batchVersions") != sorted(batch_versions)
                or data_version.get("selectedObservationVersions")
                != observation_versions
                or data_version.get("sourceRevisions")
                != expected_source_revisions):
            raise ValueError("dataVersion无效")

        lineage = raw.get("lineage")
        if (not isinstance(lineage, dict)
                or set(lineage) != {
                    "sources", "batches", "observations", "maximumKnownAt"
                }
                or lineage.get("sources")
                != sorted({item["sourceCode"] for item in bars})
                or not isinstance(lineage.get("batches"), list)
                or not isinstance(lineage.get("observations"), list)):
            raise ValueError("lineage无效")
        batches = lineage["batches"]
        if [item.get("batchVersion") for item in batches] != sorted(batch_versions):
            raise ValueError("batch lineage顺序或集合无效")
        batch_by_version: dict[str, dict[str, Any]] = {}
        for batch in batches:
            if (not isinstance(batch, dict)
                    or set(batch) != {
                        "batchVersion",
                        "datasetVersion",
                        "sourceCode",
                        "captureType",
                        "observedAt",
                        "recordedAt",
                        "sourceMetadata",
                    }
                    or batch["captureType"] not in {
                        "MARKET_DATA_PERSISTENCE",
                        "BOOTSTRAP_CURRENT_STATE",
                        "TEST_FIXTURE",
                    }
                    or not isinstance(batch["sourceMetadata"], dict)
                    or _instant(batch["observedAt"])
                    > _instant(batch["recordedAt"])):
                raise ValueError("batch lineage无效")
            batch_by_version[_text(batch, "batchVersion")] = batch
        observations = lineage["observations"]
        if len(observations) != bar_count:
            raise ValueError("observation lineage数量无效")
        maximum_known_at = _instant(lineage.get("maximumKnownAt"))
        if maximum_known_at > cutoff or maximum_known_at != max(known_times):
            raise ValueError("maximumKnownAt晚于cutoff")
        for bar, observation in zip(bars, observations, strict=True):
            expected_observation = {
                "observationVersion": bar["observationVersion"],
                "tradeDate": bar["tradeDate"],
                "sourceCode": bar["sourceCode"],
                "sourceRevision": bar["sourceRevision"],
                "batchVersion": bar["batchVersion"],
                "datasetVersion": bar["datasetVersion"],
                "firstObservedAt": bar["firstObservedAt"],
                "knownAt": bar["knownAt"],
                "canonicalContentHash": bar["canonicalContentHash"],
            }
            if (not isinstance(observation, dict)
                    or set(observation) != set(expected_observation) | {"recordedAt"}
                    or any(
                        observation.get(field) != value
                        for field, value in expected_observation.items()
                    )
                    or _instant(observation.get("recordedAt"))
                    < _instant(observation.get("knownAt"))):
                raise ValueError("bar与lineage不一致")
            batch = batch_by_version.get(bar["batchVersion"])
            if (batch is None
                    or batch["sourceCode"] != bar["sourceCode"]
                    or batch["datasetVersion"] != bar["datasetVersion"]):
                raise ValueError("bar与batch lineage不一致")

        strategy = raw.get("strategy")
        self._validate_strategy(strategy)
        result = raw.get("result")
        trade_count = _validate_result(result, request_date)
        subperiods = raw.get("subperiods")
        self._validate_subperiods(subperiods, bars, request_date)
        stability = raw.get("stability")
        if not isinstance(stability, dict) \
                or stability.get("splitAlgorithm") != SPLIT_ALGORITHM \
                or stability.get("validSubperiodCount") != 3:
            raise ValueError("stability无效")
        positive = stability.get("positiveSubperiodCount")
        if isinstance(positive, bool) or not isinstance(positive, int) or not 0 <= positive <= 3:
            raise ValueError("positiveSubperiodCount无效")
        actual_positive = sum(
            1 for item in subperiods
            if decimal_value(item["result"]["totalReturn"]) > 0
        )
        if positive != actual_positive:
            raise ValueError("positiveSubperiodCount不一致")

        input_hash = _sha(raw.get("inputDataHash"))
        strategy_hash = _sha(raw.get("strategyDefinitionHash"))
        result_hash = _sha(raw.get("backtestResultHash"))
        observation_lineage = [
            {key: deepcopy(value) for key, value in item.items() if key != "recordedAt"}
            for item in observations
        ]
        input_payload = {
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "contextProfile": CONTEXT_PROFILE,
            "contextSchemaVersion": CONTEXT_SCHEMA_VERSION,
            "symbol": request.symbol,
            "requestTradeDate": request.tradeDate.isoformat(),
            "effectiveTradeDate": raw["effectiveTradeDate"],
            "decisionTime": raw["decisionTime"],
            "knowledgeCutoff": raw["knowledgeCutoff"],
            "marketTimezone": MARKET_TIMEZONE,
            "adjustType": "QFQ",
            "inputStartDate": raw["inputStartDate"],
            "inputEndDate": raw["inputEndDate"],
            "barCount": bar_count,
            "dataVersion": deepcopy(data_version),
            "observationLineage": observation_lineage,
            "bars": deepcopy(bars),
        }
        if canonical_hash(input_payload) != input_hash:
            raise ValueError("inputDataHash不一致")
        if canonical_hash(strategy) != strategy_hash:
            raise ValueError("strategyDefinitionHash不一致")
        result_payload = {
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "inputDataHash": input_hash,
            "strategyDefinitionHash": strategy_hash,
            "result": deepcopy(result),
            "subperiods": deepcopy(subperiods),
            "stability": deepcopy(stability),
        }
        if canonical_hash(result_payload) != result_hash:
            raise ValueError("backtestResultHash不一致")
        return ParsedBacktestContext(
            raw=raw,
            result=result,
            subperiods=subperiods,
            trade_count=trade_count,
            valid_subperiod_count=stability["validSubperiodCount"],
            positive_subperiod_count=positive,
            total_return=decimal_value(result["totalReturn"]),
            max_drawdown=decimal_value(result["maxDrawdown"]),
            win_rate=decimal_value(result["winRate"]),
            profit_loss_ratio=decimal_value(result["profitLossRatio"]),
            maximum_known_at=maximum_known_at,
            backtest_result_hash=result_hash,
        )

    @staticmethod
    def _validate_strategy(strategy: Any) -> None:
        if not isinstance(strategy, dict):
            raise ValueError("strategy必须是对象")
        expected = {
            "canonicalContractVersion": CANONICAL_CONTRACT_VERSION,
            "strategyCode": STRATEGY_CODE,
            "strategyVersion": STRATEGY_CODE,
            "engineVersion": ENGINE_VERSION,
            "parameterSchemaVersion": PARAMETER_SCHEMA_VERSION,
        }
        for field, value in expected.items():
            if strategy.get(field) != value:
                raise ValueError(f"strategy.{field}无效")
        parameters = strategy.get("parameters")
        if not isinstance(parameters, dict) or set(parameters) != set(FROZEN_PARAMETERS):
            raise ValueError("七项参数不完整")
        for field, expected_value in FROZEN_PARAMETERS.items():
            actual = parameters[field]
            if field == "maxHoldingDays":
                if isinstance(actual, bool) or actual != expected_value:
                    raise ValueError("maxHoldingDays无效")
            elif decimal_value(actual) != expected_value:
                raise ValueError(f"{field}无效")

    @staticmethod
    def _validate_subperiods(
        subperiods: Any,
        bars: list[dict[str, Any]],
        request_date: date,
    ) -> None:
        if not isinstance(subperiods, list) or len(subperiods) != 3:
            raise ValueError("subperiods必须恰好三段")
        expected_names = ("EARLY", "MIDDLE", "LATE")
        base = len(bars) // 3
        remainder = len(bars) % 3
        cursor = 0
        for index, (name, item) in enumerate(zip(expected_names, subperiods, strict=True)):
            expected_count = base + (1 if index < remainder else 0)
            if (not isinstance(item, dict) or item.get("name") != name
                    or item.get("barCount") != expected_count
                    or item.get("inputStartDate") != bars[cursor]["tradeDate"]
                    or item.get("inputEndDate")
                    != bars[cursor + expected_count - 1]["tradeDate"]):
                raise ValueError("subperiod切分不一致")
            _validate_result(item.get("result"), request_date)
            cursor += expected_count
        if cursor != len(bars):
            raise ValueError("subperiod未完整覆盖输入")

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

    @staticmethod
    def _findings(
        request: AgentTeamRequest,
        parsed: ParsedBacktestContext,
        evidence: Evidence,
        impacts: tuple[int, ...],
    ) -> tuple[Finding, ...]:
        conditions = (
            "tradeCount>=10 and validSubperiodCount>=2",
            _total_return_condition(parsed.total_return),
            _drawdown_condition(parsed.max_drawdown),
            (
                f"{_win_rate_condition(parsed.win_rate)} and "
                f"{_profit_loss_condition(parsed.profit_loss_ratio)}"
            ),
            _subperiod_condition(parsed.positive_subperiod_count),
        )
        observed = (
            f"tradeCount:{parsed.trade_count},"
            f"validSubperiodCount:{parsed.valid_subperiod_count}",
            f"totalReturn:{_plain(parsed.total_return)}",
            f"maxDrawdown:{_plain(parsed.max_drawdown)}",
            (
                f"winRate:{_plain(parsed.win_rate)},"
                f"profitLossRatio:{_plain(parsed.profit_loss_ratio)}"
            ),
            f"positiveSubperiodCount:{parsed.positive_subperiod_count}",
        )
        paths = (
            "result.tradeCount,stability.validSubperiodCount",
            "result.totalReturn",
            "result.maxDrawdown",
            "result.winRate,result.profitLossRatio",
            "stability.positiveSubperiodCount",
        )
        severities = (
            Severity.INFO,
            Severity.INFO if parsed.total_return > 0 else Severity.WARN,
            Severity.INFO if parsed.max_drawdown <= Decimal("0.10") else Severity.WARN,
            (
                Severity.INFO
                if parsed.win_rate >= Decimal("0.55")
                and parsed.profit_loss_ratio >= Decimal("1.00")
                else Severity.WARN
            ),
            Severity.INFO if parsed.positive_subperiod_count >= 2 else Severity.WARN,
        )
        findings: list[Finding] = []
        for index, code in enumerate(FINDING_CODES):
            impact = impacts[index]
            findings.append(Finding(
                findingId=(
                    f"sb-{index + 1:02d}-{code.lower().replace('_', '-')}"
                    f"-{request.contextHash}"
                ),
                code=code,
                severity=severities[index],
                title=FINDING_TITLES[index],
                detail=(
                    f"evidencePaths=backtestContext.{paths[index]}；"
                    f"observed={observed[index]}；condition={conditions[index]}；"
                    f"scoreImpact={impact:+d}。"
                ),
                evidenceIds=[evidence.evidenceId],
            ))
        return tuple(findings)


def _validate_bar(item: dict[str, Any]) -> None:
    required = {
        "symbol", "tradeDate", "open", "high", "low", "close", "volume",
        "amount", "turnoverRate", "sourceCode", "sourceRevision", "batchVersion",
        "datasetVersion", "observationVersion", "firstObservedAt", "knownAt",
        "canonicalContentHash",
    }
    if set(item) != required:
        raise ValueError("bar字段白名单无效")
    open_value = decimal_value(item["open"])
    high = decimal_value(item["high"])
    low = decimal_value(item["low"])
    close = decimal_value(item["close"])
    if min(open_value, high, low, close) <= 0 \
            or high < max(open_value, low, close) \
            or low > min(open_value, high, close):
        raise ValueError("OHLC无效")
    volume = item["volume"]
    if isinstance(volume, bool) or not isinstance(volume, int) or volume < 0:
        raise ValueError("volume无效")
    for field in ("amount", "turnoverRate"):
        if item[field] is not None and decimal_value(item[field]) < 0:
            raise ValueError(f"{field}无效")
    if item["sourceRevision"] is not None \
            and (not isinstance(item["sourceRevision"], str)
                 or not item["sourceRevision"].strip()):
        raise ValueError("sourceRevision无效")


def _validate_result(value: Any, request_date: date) -> int:
    if not isinstance(value, dict) or set(value) != {
        "initialCapital",
        "finalCapital",
        "totalReturn",
        "maxDrawdown",
        "winRate",
        "profitLossRatio",
        "tradeCount",
        "trades",
    }:
        raise ValueError("回测结果字段白名单无效")
    initial = decimal_value(value["initialCapital"])
    final = decimal_value(value["finalCapital"])
    total_return = decimal_value(value["totalReturn"])
    max_drawdown = decimal_value(value["maxDrawdown"])
    win_rate = decimal_value(value["winRate"])
    profit_loss = decimal_value(value["profitLossRatio"])
    if (initial <= 0 or final < 0 or max_drawdown < 0 or max_drawdown > 1
            or win_rate < 0 or win_rate > 1 or profit_loss < 0):
        raise ValueError("回测汇总数值无效")
    if ((final - initial) / initial).quantize(
            Decimal("0.000001"), rounding=ROUND_HALF_UP) != total_return:
        raise ValueError("totalReturn与资金不一致")
    trades = value["trades"]
    trade_count = value["tradeCount"]
    if (isinstance(trade_count, bool) or not isinstance(trade_count, int)
            or trade_count < 0 or not isinstance(trades, list)
            or trade_count != len(trades)):
        raise ValueError("tradeCount无效")
    previous_entry: date | None = None
    for index, trade in enumerate(trades):
        if not isinstance(trade, dict) or set(trade) != {
            "sequence",
            "entryDate",
            "exitDate",
            "entryPrice",
            "exitPrice",
            "quantity",
            "pnl",
            "returnPct",
            "exitReason",
        }:
            raise ValueError("trade字段白名单无效")
        entry = date.fromisoformat(_text(trade, "entryDate"))
        exit_date = date.fromisoformat(_text(trade, "exitDate"))
        if (trade["sequence"] != index + 1 or exit_date < entry
                or exit_date > request_date
                or previous_entry is not None and entry < previous_entry):
            raise ValueError("trade顺序或日期无效")
        previous_entry = entry
        quantity = trade["quantity"]
        if (isinstance(quantity, bool) or not isinstance(quantity, int)
                or quantity < 100 or quantity % 100 != 0):
            raise ValueError("trade quantity无效")
        if decimal_value(trade["entryPrice"]) <= 0 \
                or decimal_value(trade["exitPrice"]) <= 0:
            raise ValueError("trade价格无效")
        decimal_value(trade["pnl"])
        decimal_value(trade["returnPct"])
        if trade["exitReason"] not in {
            "STOP_LOSS", "TAKE_PROFIT", "TRAILING_STOP", "MAX_HOLD"
        }:
            raise ValueError("exitReason无效")
    return trade_count


def _instant(value: Any) -> datetime:
    if (not isinstance(value, str)
            or re.fullmatch(
                r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z",
                value,
            ) is None):
        raise ValueError("时间必须是UTC Z结尾的微秒精度格式")
    parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    if parsed.tzinfo is None:
        raise ValueError("时间必须有时区")
    return parsed


def _text(value: dict[str, Any], field: str) -> str:
    result = value.get(field)
    if not isinstance(result, str) or not result:
        raise ValueError(f"{field}必须是非空字符串")
    return result


def _sha(value: Any) -> str:
    if (not isinstance(value, str) or len(value) != 64
            or any(character not in "0123456789abcdef" for character in value)):
        raise ValueError("Hash必须是小写SHA-256")
    return value


def _plain(value: Decimal) -> str:
    if value == 0:
        return "0"
    text = format(value.normalize(), "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def _total_return_impact(value: Decimal) -> int:
    if value >= Decimal("0.15"):
        return 15
    if value >= Decimal("0.05"):
        return 10
    if value > 0:
        return 5
    if value <= Decimal("-0.15"):
        return -20
    if value < 0:
        return -10
    return 0


def _drawdown_impact(value: Decimal) -> int:
    if value <= Decimal("0.10"):
        return 10
    if value > Decimal("0.30"):
        return -20
    if value > Decimal("0.20"):
        return -10
    return 0


def _win_rate_impact(value: Decimal) -> int:
    if value >= Decimal("0.55"):
        return 10
    if value < Decimal("0.45"):
        return -10
    return 0


def _profit_loss_ratio_impact(value: Decimal) -> int:
    if value >= Decimal("1.50"):
        return 10
    if value >= Decimal("1.00"):
        return 5
    if value < Decimal("0.80"):
        return -10
    return 0


def _subperiod_impact(value: int) -> int:
    return {3: 10, 2: 5, 1: -10, 0: -20}[value]


def _confidence(trade_count: int) -> int:
    if trade_count >= 40:
        return 80
    if trade_count >= 20:
        return 60
    return 40


def _total_return_condition(value: Decimal) -> str:
    if value >= Decimal("0.15"):
        return "totalReturn>=0.15"
    if value >= Decimal("0.05"):
        return "0.05<=totalReturn<0.15"
    if value > 0:
        return "0<totalReturn<0.05"
    if value <= Decimal("-0.15"):
        return "totalReturn<=-0.15"
    if value < 0:
        return "-0.15<totalReturn<0"
    return "totalReturn=0"


def _drawdown_condition(value: Decimal) -> str:
    if value <= Decimal("0.10"):
        return "maxDrawdown<=0.10"
    if value > Decimal("0.30"):
        return "maxDrawdown>0.30"
    if value > Decimal("0.20"):
        return "0.20<maxDrawdown<=0.30"
    return "0.10<maxDrawdown<=0.20"


def _win_rate_condition(value: Decimal) -> str:
    if value >= Decimal("0.55"):
        return "winRate>=0.55"
    if value < Decimal("0.45"):
        return "winRate<0.45"
    return "0.45<=winRate<0.55"


def _profit_loss_condition(value: Decimal) -> str:
    if value >= Decimal("1.50"):
        return "profitLossRatio>=1.50"
    if value >= Decimal("1.00"):
        return "1.00<=profitLossRatio<1.50"
    if value < Decimal("0.80"):
        return "profitLossRatio<0.80"
    return "0.80<=profitLossRatio<1.00"


def _subperiod_condition(value: int) -> str:
    return f"positiveSubperiodCount={value}"
