from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from zoneinfo import ZoneInfo

from pydantic import AwareDatetime, BaseModel, ConfigDict, StrictBool, StrictInt, StrictStr, ValidationError

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
    STAGE_2D_MARKET_REGIME_RULE_VERSION,
)


MARKET_BREADTH_PRODUCER_VERSION = "MARKET_BREADTH_V1"
MIN_COVERAGE_RATIO = Decimal("1.00000000")
MIN_COMPARABLE_SYMBOL_COUNT = 2
RATIO_QUANTUM = Decimal("0.00000001")
SHANGHAI_ZONE = ZoneInfo("Asia/Shanghai")

EXPECTED_SOURCE_TABLES = ("daily_bars", "securities")
EXPECTED_LIMITATIONS = ("CURRENT_SECURITIES_ATTRIBUTES_ARE_NOT_HISTORICALLY_VERSIONED",)
EXPECTED_TIMESTAMP_SEMANTICS = "TRADE_DATES_ARE_LOCAL_DATE_QUERIED_AT_IS_UTC_INSTANT"
EXPECTED_SELECTION_RULE = "CURRENT_MAIN_ACTIVE_NON_ST_UNIVERSE_UNIFIED_EFFECTIVE_DATE"

MARKET_BREADTH_EVIDENCE_FIELDS = (
    "available",
    "reasonCode",
    "sourceType",
    "sourceTables",
    "sourceStatus",
    "producer",
    "producerVersion",
    "versionAvailable",
    "requestedTradeDate",
    "effectiveTradeDate",
    "previousEffectiveTradeDate",
    "exactTradeDateMatch",
    "pointInTimeGuaranteed",
    "barFutureDataExcluded",
    "universePointInTimeGuaranteed",
    "futureDataExcluded",
    "timestampTimezoneSemantics",
    "adjustType",
    "selectionRule",
    "universeCount",
    "coveredSymbolCount",
    "comparableSymbolCount",
    "advancingCount",
    "decliningCount",
    "unchangedCount",
    "missingCurrentBarCount",
    "missingPreviousBarCount",
    "coverageRatio",
    "limitations",
)

FINDING_ORDER = (
    "MARKET_BREADTH_FACT_INCONSISTENT",
    "MARKET_BREADTH_UNAVAILABLE",
    "MARKET_BREADTH_LOW_COVERAGE",
    "MARKET_BREADTH_DATE_NOT_EXACT",
    "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED",
    "MARKET_BREADTH_POSITIVE",
    "MARKET_BREADTH_MIXED",
    "MARKET_BREADTH_NEGATIVE",
)
_FINDING_RANK = {code: index + 1 for index, code in enumerate(FINDING_ORDER)}
_FINDING_DEFINITIONS = {
    "MARKET_BREADTH_FACT_INCONSISTENT": (
        Severity.HIGH,
        "当前证券池宽度事实不一致",
        "市场宽度来源、版本、日期、计数或比例事实无法互相验证。",
    ),
    "MARKET_BREADTH_UNAVAILABLE": (
        Severity.WARN,
        "当前证券池宽度事实不可用",
        "本地只读市场宽度上下文未提供可比较事实，未形成宽度方向。",
    ),
    "MARKET_BREADTH_LOW_COVERAGE": (
        Severity.WARN,
        "当前证券池宽度覆盖不足",
        "当前证券池宽度覆盖率未达到1.00000000或可比较证券少于2只，未形成宽度方向。",
    ),
    "MARKET_BREADTH_DATE_NOT_EXACT": (
        Severity.WARN,
        "当前证券池宽度日期未精确命中",
        "有效交易日未精确匹配请求交易日，未形成宽度方向。",
    ),
    "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED": (
        Severity.WARN,
        "当前证券池宽度时点属性不可验证",
        "当前证券池不是历史版本，结果仅描述冻结请求时的当前证券池宽度状态。",
    ),
    "MARKET_BREADTH_POSITIVE": (
        Severity.INFO,
        "当前证券池宽度偏正向",
        "可比较证券中上涨数量多于下跌数量，仅描述冻结的当前证券池宽度事实。",
    ),
    "MARKET_BREADTH_MIXED": (
        Severity.INFO,
        "当前证券池宽度混合",
        "可比较证券中上涨数量等于下跌数量，仅描述冻结的当前证券池宽度事实。",
    ),
    "MARKET_BREADTH_NEGATIVE": (
        Severity.INFO,
        "当前证券池宽度偏负向",
        "可比较证券中下跌数量多于上涨数量，仅描述冻结的当前证券池宽度事实。",
    ),
}


class _FrozenInput(BaseModel):
    model_config = ConfigDict(extra="allow", frozen=True)


class _QueryScope(_FrozenInput):
    symbol: StrictStr
    tradeDate: date


class _MarketBreadthInput(_FrozenInput):
    available: StrictBool
    queriedAt: AwareDatetime
    queryScope: _QueryScope
    reasonCode: StrictStr | None
    sourceType: StrictStr
    sourceTables: list[StrictStr]
    sourceStatus: StrictStr
    producer: StrictStr
    producerVersion: StrictStr
    versionAvailable: StrictBool
    requestedTradeDate: date
    effectiveTradeDate: date | None
    previousEffectiveTradeDate: date | None
    exactTradeDateMatch: StrictBool
    pointInTimeGuaranteed: StrictBool
    barFutureDataExcluded: StrictBool
    universePointInTimeGuaranteed: StrictBool
    futureDataExcluded: StrictBool
    timestampTimezoneSemantics: StrictStr
    adjustType: StrictStr
    selectionRule: StrictStr
    universeCount: StrictInt
    coveredSymbolCount: StrictInt
    comparableSymbolCount: StrictInt
    advancingCount: StrictInt
    decliningCount: StrictInt
    unchangedCount: StrictInt
    missingCurrentBarCount: StrictInt
    missingPreviousBarCount: StrictInt
    coverageRatio: Decimal | None
    limitations: list[StrictStr]


@dataclass(frozen=True)
class MarketRegimeEvaluation:
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
class _BreadthRatios:
    advance_ratio: Decimal
    decline_ratio: Decimal
    unchanged_ratio: Decimal
    net_breadth_ratio: Decimal


class MarketRegimeRuleEngine:
    def evaluate(
        self,
        request: AgentTeamRequest,
        data_quality_gate: GateStatus,
    ) -> MarketRegimeEvaluation:
        if data_quality_gate is GateStatus.BLOCKED:
            return self.blocked_by_data_quality()

        try:
            value = self._parse(request)
            evidence = self._evidence(request, value)
        except (ValidationError, ValueError, TypeError, InvalidOperation):
            return MarketRegimeEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=data_quality_gate,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="当前证券池市场宽度输入无法安全解析，未形成宽度方向。",
                findings=(),
                evidence=(),
                errors=(AgentError(
                    code="MARKET_BREADTH_INPUT_INVALID",
                    message="marketBreadth上下文无法按阶段2D-1冻结契约安全解析。",
                ),),
            )

        if not self._facts_consistent(request, value):
            return self._insufficient(
                data_quality_gate,
                evidence,
                request.contextHash,
                "MARKET_BREADTH_FACT_INCONSISTENT",
                "当前证券池市场宽度事实不一致，未形成宽度方向。",
            )
        if not value.available or value.universeCount == 0 or value.comparableSymbolCount == 0:
            return self._insufficient(
                data_quality_gate,
                evidence,
                request.contextHash,
                "MARKET_BREADTH_UNAVAILABLE",
                "当前证券池市场宽度事实不可用，未形成宽度方向。",
            )
        if (value.coverageRatio is None
                or value.coverageRatio.compare(MIN_COVERAGE_RATIO) != Decimal("0")
                or value.comparableSymbolCount < MIN_COMPARABLE_SYMBOL_COUNT):
            return self._insufficient(
                data_quality_gate,
                evidence,
                request.contextHash,
                "MARKET_BREADTH_LOW_COVERAGE",
                "当前证券池市场宽度覆盖不足，未形成宽度方向。",
            )
        if not value.exactTradeDateMatch or value.effectiveTradeDate != request.tradeDate:
            return self._insufficient(
                data_quality_gate,
                evidence,
                request.contextHash,
                "MARKET_BREADTH_DATE_NOT_EXACT",
                "当前证券池市场宽度日期未精确命中，未形成宽度方向。",
            )

        frozen_current_date = request.requestedAt.astimezone(SHANGHAI_ZONE).date()
        if request.tradeDate != frozen_current_date:
            return self._insufficient(
                data_quality_gate,
                evidence,
                request.contextHash,
                "MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED",
                "请求日期不是冻结请求时的上海自然日，历史或未来日期未形成宽度方向。",
            )

        ratios = _breadth_ratios(value)
        net_breadth_ratio = ratios.net_breadth_ratio
        score = int(((net_breadth_ratio + Decimal("1")) * Decimal("50"))
                    .quantize(Decimal("1"), rounding=ROUND_HALF_UP))
        if net_breadth_ratio > 0:
            direction_code = "MARKET_BREADTH_POSITIVE"
            state_text = "偏正向"
        elif net_breadth_ratio < 0:
            direction_code = "MARKET_BREADTH_NEGATIVE"
            state_text = "偏负向"
        else:
            direction_code = "MARKET_BREADTH_MIXED"
            state_text = "混合"

        findings = (
            _finding("MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED", evidence.evidenceId,
                     request.contextHash),
            _finding(direction_code, evidence.evidenceId, request.contextHash),
        )
        return MarketRegimeEvaluation(
            status=RunStatus.COMPLETED,
            gate_status=data_quality_gate,
            decision=AgentDecision.WARN,
            score=score,
            confidence=0,
            summary=(f"当前证券池市场宽度状态{state_text}；"
                     "证券池时点属性不可验证，confidence固定为0。"),
            findings=findings,
            evidence=(evidence,),
            errors=(),
        )

    @staticmethod
    def blocked_by_data_quality() -> MarketRegimeEvaluation:
        return MarketRegimeEvaluation(
            status=RunStatus.INSUFFICIENT_DATA,
            gate_status=GateStatus.NOT_APPLICABLE,
            decision=AgentDecision.NOT_APPLICABLE,
            score=0,
            confidence=0,
            summary="DATA_QUALITY门禁已阻断，未执行当前证券池市场宽度状态规则。",
            findings=(),
            evidence=(),
            errors=(),
        )

    @staticmethod
    def _parse(request: AgentTeamRequest) -> _MarketBreadthInput:
        raw = request.contextSnapshot.marketBreadth
        if not isinstance(raw, dict):
            raise TypeError("marketBreadth必须为JSON对象")
        missing = set(MARKET_BREADTH_EVIDENCE_FIELDS).difference(raw)
        missing.update({"queriedAt", "queryScope"}.difference(raw))
        if missing:
            raise ValueError("marketBreadth缺少冻结字段")
        coverage = raw["coverageRatio"]
        if coverage is not None and (
            isinstance(coverage, bool)
            or not isinstance(coverage, (int, float, Decimal))
        ):
            raise TypeError("coverageRatio必须为JSON数值或null")
        return _MarketBreadthInput.model_validate(raw)

    @staticmethod
    def _facts_consistent(
        request: AgentTeamRequest,
        value: _MarketBreadthInput,
    ) -> bool:
        counts = (
            value.universeCount,
            value.coveredSymbolCount,
            value.comparableSymbolCount,
            value.advancingCount,
            value.decliningCount,
            value.unchangedCount,
            value.missingCurrentBarCount,
            value.missingPreviousBarCount,
        )
        if any(candidate < 0 for candidate in counts):
            return False
        if not (value.comparableSymbolCount <= value.coveredSymbolCount <= value.universeCount):
            return False
        if (value.advancingCount + value.decliningCount + value.unchangedCount
                != value.comparableSymbolCount):
            return False
        if value.coveredSymbolCount + value.missingCurrentBarCount != value.universeCount:
            return False
        if value.comparableSymbolCount + value.missingPreviousBarCount != value.coveredSymbolCount:
            return False

        expected_coverage = (
            None if value.universeCount == 0
            else _ratio(value.comparableSymbolCount, value.universeCount)
        )
        if expected_coverage is None:
            if value.coverageRatio is not None:
                return False
        elif value.coverageRatio is None or value.coverageRatio != expected_coverage:
            return False

        if value.queryScope.symbol != request.symbol \
                or value.queryScope.tradeDate != request.tradeDate \
                or value.requestedTradeDate != request.tradeDate:
            return False
        if value.effectiveTradeDate is not None and value.effectiveTradeDate > request.tradeDate:
            return False
        if (value.previousEffectiveTradeDate is not None
                and (value.effectiveTradeDate is None
                     or value.previousEffectiveTradeDate >= value.effectiveTradeDate)):
            return False
        if value.available and value.previousEffectiveTradeDate is None:
            return False

        expected_reason_code = _reason_code(value)
        fixed_semantics = (
            value.sourceType == "DATABASE"
            and tuple(value.sourceTables) == EXPECTED_SOURCE_TABLES
            and value.available is (expected_reason_code is None)
            and value.reasonCode == expected_reason_code
            and value.sourceStatus == ("AVAILABLE" if expected_reason_code is None else "UNAVAILABLE")
            and value.producer == "AgentMarketBreadthContextService"
            and value.producerVersion == MARKET_BREADTH_PRODUCER_VERSION
            and value.versionAvailable is True
            and value.pointInTimeGuaranteed is False
            and value.barFutureDataExcluded is True
            and value.universePointInTimeGuaranteed is False
            and value.futureDataExcluded is False
            and value.timestampTimezoneSemantics == EXPECTED_TIMESTAMP_SEMANTICS
            and value.adjustType == "QFQ"
            and value.selectionRule == EXPECTED_SELECTION_RULE
            and tuple(value.limitations) == EXPECTED_LIMITATIONS
        )
        return fixed_semantics

    @staticmethod
    def _evidence(request: AgentTeamRequest, value: _MarketBreadthInput) -> Evidence:
        raw = request.contextSnapshot.marketBreadth
        fields = {
            "marketBreadth": {
                key: deepcopy(raw[key])
                for key in MARKET_BREADTH_EVIDENCE_FIELDS
            }
        }
        return Evidence(
            evidenceId=f"mr-breadth-{request.contextHash}",
            category=EvidenceCategory.MARKET_BREADTH,
            sourceType=EvidenceSourceType.JAVA_ENGINE,
            sourceName="AgentMarketBreadthContextService",
            sourceRef="contextSnapshot.marketBreadth",
            symbol=request.symbol,
            tradeDate=request.tradeDate,
            observedAt=value.queriedAt,
            collectedAt=request.requestedAt,
            fields=fields,
            contentHash=request.contextHash,
        )

    @staticmethod
    def _insufficient(
        gate_status: GateStatus,
        evidence: Evidence,
        context_hash: str,
        finding_code: str,
        summary: str,
    ) -> MarketRegimeEvaluation:
        return MarketRegimeEvaluation(
            status=RunStatus.INSUFFICIENT_DATA,
            gate_status=gate_status,
            decision=AgentDecision.NOT_APPLICABLE,
            score=0,
            confidence=0,
            summary=summary,
            findings=(_finding(finding_code, evidence.evidenceId, context_hash),),
            evidence=(evidence,),
            errors=(),
        )


def _ratio(numerator: int, denominator: int) -> Decimal:
    if denominator <= 0:
        raise InvalidOperation("ratio denominator must be positive")
    return (Decimal(numerator) / Decimal(denominator)).quantize(
        RATIO_QUANTUM,
        rounding=ROUND_HALF_UP,
    )


def _breadth_ratios(value: _MarketBreadthInput) -> _BreadthRatios:
    comparable = value.comparableSymbolCount
    return _BreadthRatios(
        advance_ratio=_ratio(value.advancingCount, comparable),
        decline_ratio=_ratio(value.decliningCount, comparable),
        unchanged_ratio=_ratio(value.unchangedCount, comparable),
        net_breadth_ratio=_ratio(value.advancingCount - value.decliningCount, comparable),
    )


def _reason_code(value: _MarketBreadthInput) -> str | None:
    if value.universeCount == 0:
        return "NO_ELIGIBLE_UNIVERSE"
    if value.effectiveTradeDate is None:
        return "NO_EFFECTIVE_TRADE_DATE"
    if value.previousEffectiveTradeDate is None:
        return "NO_PREVIOUS_EFFECTIVE_TRADE_DATE"
    if value.comparableSymbolCount == 0:
        return "ZERO_COMPARABLE_SYMBOLS"
    return None


def _finding(code: str, evidence_id: str, context_hash: str) -> Finding:
    severity, title, detail = _FINDING_DEFINITIONS[code]
    rank = _FINDING_RANK[code]
    return Finding(
        findingId=f"mr-{rank:02d}-{code.lower().replace('_', '-')}-{context_hash}",
        code=code,
        severity=severity,
        title=title,
        detail=detail,
        evidenceIds=[evidence_id],
    )
