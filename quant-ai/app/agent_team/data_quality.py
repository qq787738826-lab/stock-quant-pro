from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date, datetime
from enum import Enum
from typing import Any

from pydantic import AwareDatetime, BaseModel, ConfigDict, ValidationError

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
    STAGE_2B_DATA_QUALITY_RULE_VERSION,
)


MAX_NON_BLOCKING_NATURAL_DAY_LAG = 10
EXPECTED_REQUIRED_BARS = 61
EXPECTED_ADJUST_TYPE = "QFQ"
EXPECTED_FORMULA_VERSION = "JAVA_INDICATORS_V1"
EXPECTED_DUPLICATE_PROTECTION = "PRIMARY_KEY_SYMBOL_TRADE_DATE_ADJUST_TYPE"
FORBIDDEN_EVIDENCE_CONCLUSION_FIELDS = frozenset({
    "gate",
    "gatestatus",
    "decision",
    "score",
    "finding",
    "findings",
    "veto",
})


RULE_ORDER = (
    "QUERY_SCOPE_INCONSISTENT",
    "REQUEST_DATE_INCONSISTENT",
    "EFFECTIVE_DATE_FACT_CONTRADICTION",
    "EFFECTIVE_DATE_INCONSISTENT",
    "NATURAL_DAY_LAG_INCONSISTENT",
    "EXACT_TRADE_DATE_INCONSISTENT",
    "SECURITY_AVAILABILITY_INCONSISTENT",
    "SECURITY_SYMBOL_INCONSISTENT",
    "SECURITY_QUALITY_FACTS_INCONSISTENT",
    "SECURITY_FIELDS_INCONSISTENT",
    "BAR_COUNT_INCONSISTENT",
    "REQUIRED_BAR_COUNT_INCONSISTENT",
    "ADJUST_TYPE_INCONSISTENT",
    "FORMULA_VERSION_INCONSISTENT",
    "DUPLICATE_PROTECTION_INCONSISTENT",
    "INVALID_BAR_DATES_INCONSISTENT",
    "OPTIONAL_FIELD_COUNT_INCONSISTENT",
    "NATURAL_DAY_GAP_INCONSISTENT",
    "TECHNICAL_AVAILABILITY_INCONSISTENT",
    "SECURITY_RECORD_MISSING",
    "SECURITY_PLACEHOLDER_SUSPECTED",
    "SECURITY_SOURCE_UNKNOWN",
    "MARKET_DATA_MISSING",
    "INSUFFICIENT_DAILY_BARS",
    "INVALID_DAILY_BARS",
    "TECHNICAL_METRICS_UNAVAILABLE",
    "MARKET_DATA_TOO_STALE",
    "REQUEST_DATE_NOT_EXACT",
    "SECURITY_POINT_IN_TIME_UNVERIFIED",
    "MISSING_SECURITY_FIELDS",
    "OPTIONAL_MARKET_FIELDS_MISSING",
    "LARGE_NATURAL_DAY_GAP",
    "TRADING_CALENDAR_UNAVAILABLE",
    "SOURCE_CONSISTENCY_UNASSESSABLE",
    "SECURITY_SCOPE_FACT",
)
_RULE_RANK = {code: index for index, code in enumerate(RULE_ORDER)}


class _FrozenContextModel(BaseModel):
    model_config = ConfigDict(extra="allow", frozen=True)


class QueryScope(_FrozenContextModel):
    symbol: str
    tradeDate: date


class SecurityQualityFacts(_FrozenContextModel):
    placeholderSuspected: bool
    sourceKnown: bool
    pointInTimeGuaranteed: bool


class SecurityContext(_FrozenContextModel):
    available: bool
    queriedAt: AwareDatetime
    queryScope: QueryScope
    symbol: str | None = None
    name: str | None = None
    board: str | None = None
    industry: str | None = None
    listDate: date | None = None
    isSt: bool | None = None
    isActive: bool | None = None
    dataSource: str | None = None
    qualityFacts: SecurityQualityFacts | None = None


class MarketDataContext(_FrozenContextModel):
    available: bool
    queriedAt: AwareDatetime
    queryScope: QueryScope
    adjustType: str
    requestedTradeDate: date
    effectiveTradeDate: date | None
    exactTradeDateMatch: bool
    actualBars: int


class TechnicalMetricsContext(_FrozenContextModel):
    available: bool
    queriedAt: AwareDatetime
    queryScope: QueryScope
    formulaVersion: str
    adjustType: str
    requestedTradeDate: date
    effectiveTradeDate: date | None
    requiredBars: int
    actualBars: int


class DataQualityFacts(_FrozenContextModel):
    securityRecordPresent: bool
    securityPlaceholderSuspected: bool
    securitySourceKnown: bool
    securityPointInTimeGuaranteed: bool
    loadedBarCount: int
    requiredBarsForTechnicalMetrics: int
    exactTradeDatePresent: bool
    requestedTradeDate: date
    effectiveTradeDate: date | None
    naturalDayLag: int | None
    tradingCalendarAvailable: bool
    missingAmountCount: int
    missingTurnoverRateCount: int
    invalidBarCount: int
    invalidBarDates: list[date]
    maximumObservedNaturalDayGap: int | None
    duplicateProtection: str
    sourceConsistencyAssessable: bool
    adjustTypesObserved: list[str]
    missingSecurityFields: list[str]


class DataQualityContext(_FrozenContextModel):
    available: bool
    queriedAt: AwareDatetime
    queryScope: QueryScope
    facts: DataQualityFacts


class Stage2AQualityInput(_FrozenContextModel):
    security: SecurityContext
    marketData: MarketDataContext
    technicalMetrics: TechnicalMetricsContext
    dataQualityContext: DataQualityContext


class RuleImpact(str, Enum):
    BLOCK = "BLOCK"
    WARN = "WARN"
    INFO = "INFO"


@dataclass(frozen=True)
class DataQualityEvaluation:
    status: RunStatus
    gate_status: GateStatus
    decision: AgentDecision
    score: int
    confidence: int
    summary: str
    findings: tuple[Finding, ...]
    evidence: tuple[Evidence, ...]
    errors: tuple[AgentError, ...]


class _FindingCollector:
    def __init__(self, evidence_id: str) -> None:
        self._evidence_id = evidence_id
        self._values: dict[str, Finding] = {}

    def add(self, code: str, impact: RuleImpact, title: str, detail: str) -> None:
        if code not in _RULE_RANK:
            raise ValueError(f"未冻结的DATA_QUALITY规则代码：{code}")
        if code in self._values:
            return
        severity = {
            RuleImpact.BLOCK: Severity.HIGH,
            RuleImpact.WARN: Severity.WARN,
            RuleImpact.INFO: Severity.INFO,
        }[impact]
        self._values[code] = Finding(
            findingId=f"dq-{_RULE_RANK[code] + 1:02d}-{code.lower().replace('_', '-')}",
            code=code,
            severity=severity,
            title=title,
            detail=detail,
            evidenceIds=[self._evidence_id],
        )

    def ordered(self) -> tuple[Finding, ...]:
        return tuple(sorted(self._values.values(), key=lambda item: _RULE_RANK[item.code]))


class DataQualityRuleEngine:
    def evaluate(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
    ) -> DataQualityEvaluation:
        try:
            value = self._parse(request)
        except (ValidationError, ValueError, TypeError) as error:
            return DataQualityEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=GateStatus.BLOCKED,
                decision=AgentDecision.REJECT,
                score=0,
                confidence=0,
                summary="Java提供的数据质量上下文无效，门禁已安全阻断。",
                findings=(),
                evidence=(),
                errors=(AgentError(
                    code="DATA_QUALITY_CONTEXT_INVALID",
                    message=f"阶段2A冻结上下文无法可靠解析：{error.__class__.__name__}",
                ),),
            )

        evidence = self._evidence(request, value)
        collector = _FindingCollector(evidence.evidenceId)
        self._evaluate_consistency(request, value, collector)
        self._evaluate_security(value, collector)
        self._evaluate_market_data(request, value, collector)
        self._evaluate_capability_limits(value, collector)
        findings = collector.ordered()

        if any(item.severity is Severity.HIGH for item in findings):
            gate_status = GateStatus.BLOCKED
            decision = AgentDecision.REJECT
            score = 0
            summary = "数据质量规则发现阻断事实，本次团队分析已停止。"
        elif any(item.severity is Severity.WARN for item in findings):
            gate_status = GateStatus.WARN
            decision = AgentDecision.WARN
            score = 50
            summary = "数据质量规则执行完成，存在非阻断警告。"
        else:
            gate_status = GateStatus.PASS
            decision = AgentDecision.PASS
            score = 100
            summary = "数据质量规则检查通过。"

        return DataQualityEvaluation(
            status=RunStatus.COMPLETED,
            gate_status=gate_status,
            decision=decision,
            score=score,
            confidence=100,
            summary=summary,
            findings=findings,
            evidence=(evidence,),
            errors=(),
        )

    @staticmethod
    def _parse(request: AgentTeamRequest) -> Stage2AQualityInput:
        snapshot = request.contextSnapshot
        if snapshot.dataQualityContext.get("available") is not True:
            raise ValueError("dataQualityContext.available必须为true")
        raw_value = {
            "security": snapshot.security,
            "marketData": snapshot.marketData,
            "technicalMetrics": snapshot.technicalMetrics,
            "dataQualityContext": snapshot.dataQualityContext,
        }
        if _contains_forbidden_evidence_conclusion(raw_value):
            raise ValueError("阶段2A冻结上下文不得包含规则结论字段")
        return Stage2AQualityInput.model_validate(raw_value)

    @staticmethod
    def _evidence(request: AgentTeamRequest, value: Stage2AQualityInput) -> Evidence:
        return Evidence(
            evidenceId=f"dq-context-{request.contextHash}",
            category=EvidenceCategory.DATA_QUALITY,
            sourceType=EvidenceSourceType.JAVA_ENGINE,
            sourceName="AgentContextSnapshotService",
            sourceRef="contextSnapshot",
            symbol=request.symbol,
            tradeDate=request.tradeDate,
            observedAt=value.dataQualityContext.queriedAt,
            collectedAt=request.requestedAt,
            fields=direct_stage_2a_evidence_fields(request),
            contentHash=request.contextHash,
        )

    def _evaluate_consistency(
        self,
        request: AgentTeamRequest,
        value: Stage2AQualityInput,
        findings: _FindingCollector,
    ) -> None:
        security = value.security
        market = value.marketData
        technical = value.technicalMetrics
        quality = value.dataQualityContext
        facts = quality.facts

        contexts = (security, market, technical, quality)
        if any(context.queryScope.symbol != request.symbol
               or context.queryScope.tradeDate != request.tradeDate for context in contexts):
            findings.add(
                "QUERY_SCOPE_INCONSISTENT", RuleImpact.BLOCK,
                "查询范围事实不一致",
                "四类冻结上下文的queryScope必须与Java请求中的symbol和tradeDate一致。",
            )

        if any(candidate != request.tradeDate for candidate in (
            market.requestedTradeDate,
            technical.requestedTradeDate,
            facts.requestedTradeDate,
        )):
            findings.add(
                "REQUEST_DATE_INCONSISTENT", RuleImpact.BLOCK,
                "请求日期事实不一致",
                "行情、技术指标和质量事实中的requestedTradeDate与Java请求不一致。",
            )

        effective_dates = (
            market.effectiveTradeDate,
            technical.effectiveTradeDate,
            facts.effectiveTradeDate,
        )
        if ((facts.naturalDayLag is not None and facts.naturalDayLag < 0)
                or any(candidate is not None and candidate > request.tradeDate
                       for candidate in effective_dates)):
            findings.add(
                "EFFECTIVE_DATE_FACT_CONTRADICTION", RuleImpact.BLOCK,
                "有效日期事实矛盾",
                "有效日期晚于请求日期或naturalDayLag为负数。",
            )
        elif len(set(effective_dates)) != 1:
            findings.add(
                "EFFECTIVE_DATE_INCONSISTENT", RuleImpact.BLOCK,
                "有效日期事实不一致",
                "行情、技术指标和质量事实中的effectiveTradeDate不一致。",
            )
        else:
            effective = facts.effectiveTradeDate
            expected_lag = None if effective is None else (request.tradeDate - effective).days
            if facts.naturalDayLag != expected_lag:
                findings.add(
                    "NATURAL_DAY_LAG_INCONSISTENT", RuleImpact.BLOCK,
                    "自然日延迟事实不一致",
                    "naturalDayLag与请求日期及有效日期不能互相验证。",
                )
            expected_exact = effective == request.tradeDate
            if facts.exactTradeDatePresent != expected_exact or market.exactTradeDateMatch != expected_exact:
                findings.add(
                    "EXACT_TRADE_DATE_INCONSISTENT", RuleImpact.BLOCK,
                    "精确日期命中事实不一致",
                    "exactTradeDate标记与请求日期和有效日期不一致。",
                )

        security_consistent = security.available == facts.securityRecordPresent
        if not security_consistent:
            findings.add(
                "SECURITY_AVAILABILITY_INCONSISTENT", RuleImpact.BLOCK,
                "证券可用性事实不一致",
                "security.available与securityRecordPresent不一致。",
            )
        if security.available and security.symbol != request.symbol:
            findings.add(
                "SECURITY_SYMBOL_INCONSISTENT", RuleImpact.BLOCK,
                "证券代码事实不一致",
                "security中的symbol与Java请求不一致。",
            )
        if security.available:
            direct = security.qualityFacts
            if direct is None or (
                direct.placeholderSuspected != facts.securityPlaceholderSuspected
                or direct.sourceKnown != facts.securitySourceKnown
                or direct.pointInTimeGuaranteed != facts.securityPointInTimeGuaranteed
            ):
                findings.add(
                    "SECURITY_QUALITY_FACTS_INCONSISTENT", RuleImpact.BLOCK,
                    "证券质量事实不一致",
                    "security.qualityFacts与dataQualityContext中的对应事实不一致。",
                )
            expected_missing = sorted(
                field for field, item in (
                    ("dataSource", security.dataSource),
                    ("industry", security.industry),
                    ("listDate", security.listDate),
                    ("name", security.name),
                ) if item is None or isinstance(item, str) and not item.strip()
            )
            if facts.missingSecurityFields != sorted(set(facts.missingSecurityFields)) \
                    or facts.missingSecurityFields != expected_missing:
                findings.add(
                    "SECURITY_FIELDS_INCONSISTENT", RuleImpact.BLOCK,
                    "证券缺失字段事实不一致",
                    "missingSecurityFields与security中的直接字段不一致或排序不稳定。",
                )

        loaded = facts.loadedBarCount
        bar_counts_consistent = (
            loaded >= 0
            and market.actualBars == loaded
            and technical.actualBars == loaded
            and market.available == (loaded > 0)
        )
        if not bar_counts_consistent:
            findings.add(
                "BAR_COUNT_INCONSISTENT", RuleImpact.BLOCK,
                "日线条数事实不一致",
                "marketData、technicalMetrics和dataQualityContext中的日线条数或可用性不一致。",
            )

        if facts.requiredBarsForTechnicalMetrics != EXPECTED_REQUIRED_BARS \
                or technical.requiredBars != EXPECTED_REQUIRED_BARS:
            findings.add(
                "REQUIRED_BAR_COUNT_INCONSISTENT", RuleImpact.BLOCK,
                "指标所需日线条数不一致",
                "阶段2A冻结的技术指标必须使用61条日线。",
            )

        observed_adjust_types = facts.adjustTypesObserved
        if (
            market.adjustType != EXPECTED_ADJUST_TYPE
            or technical.adjustType != EXPECTED_ADJUST_TYPE
            or observed_adjust_types != sorted(set(observed_adjust_types))
            or loaded > 0 and EXPECTED_ADJUST_TYPE not in observed_adjust_types
        ):
            findings.add(
                "ADJUST_TYPE_INCONSISTENT", RuleImpact.BLOCK,
                "复权类型事实不一致",
                "阶段2A行情和技术指标必须基于QFQ，复权类型事实必须稳定且可验证。",
            )

        if technical.formulaVersion != EXPECTED_FORMULA_VERSION:
            findings.add(
                "FORMULA_VERSION_INCONSISTENT", RuleImpact.BLOCK,
                "技术指标公式版本不一致",
                "阶段2A技术指标公式版本必须为JAVA_INDICATORS_V1。",
            )

        if facts.duplicateProtection != EXPECTED_DUPLICATE_PROTECTION:
            findings.add(
                "DUPLICATE_PROTECTION_INCONSISTENT", RuleImpact.BLOCK,
                "重复保护事实不一致",
                "日线重复保护事实与阶段2A冻结契约不一致。",
            )

        if facts.invalidBarCount < 0 \
                or facts.invalidBarDates != sorted(set(facts.invalidBarDates)) \
                or facts.invalidBarCount < len(facts.invalidBarDates):
            findings.add(
                "INVALID_BAR_DATES_INCONSISTENT", RuleImpact.BLOCK,
                "非法日线日期事实不一致",
                "非法日线计数、去重日期或稳定排序不能互相验证。",
            )

        if any(count < 0 or count > loaded for count in (
            facts.missingAmountCount,
            facts.missingTurnoverRateCount,
        )):
            findings.add(
                "OPTIONAL_FIELD_COUNT_INCONSISTENT", RuleImpact.BLOCK,
                "可选字段缺失计数不一致",
                "金额或换手率缺失计数超出已加载日线范围。",
            )

        if facts.maximumObservedNaturalDayGap is not None \
                and facts.maximumObservedNaturalDayGap < 0:
            findings.add(
                "NATURAL_DAY_GAP_INCONSISTENT", RuleImpact.BLOCK,
                "自然日间隔事实不一致",
                "maximumObservedNaturalDayGap不能为负数。",
            )

        if bar_counts_consistent:
            technical_impossible = technical.available and (
                loaded < EXPECTED_REQUIRED_BARS or facts.invalidBarCount > 0
            )
            if technical_impossible:
                findings.add(
                    "TECHNICAL_AVAILABILITY_INCONSISTENT", RuleImpact.BLOCK,
                    "技术指标可用性事实不一致",
                    "日线条数不足或存在非法日线时，technicalMetrics.available不得为true。",
                )

    @staticmethod
    def _evaluate_security(
        value: Stage2AQualityInput,
        findings: _FindingCollector,
    ) -> None:
        security = value.security
        facts = value.dataQualityContext.facts
        if security.available != facts.securityRecordPresent:
            return
        if not facts.securityRecordPresent:
            findings.add(
                "SECURITY_RECORD_MISSING", RuleImpact.BLOCK,
                "证券基础事实缺失",
                "本地数据库中不存在请求证券的基础记录。",
            )
            return

        if not facts.securitySourceKnown:
            findings.add(
                "SECURITY_SOURCE_UNKNOWN", RuleImpact.BLOCK,
                "证券来源未知",
                "证券记录缺少可识别的数据来源。",
            )
        elif facts.securityPlaceholderSuspected:
            findings.add(
                "SECURITY_PLACEHOLDER_SUSPECTED", RuleImpact.BLOCK,
                "证券记录疑似占位",
                "证券名称或来源事实表明该记录可能是占位数据。",
            )

        if not facts.securityPointInTimeGuaranteed:
            findings.add(
                "SECURITY_POINT_IN_TIME_UNVERIFIED", RuleImpact.WARN,
                "证券属性不具备点时保证",
                "securities不是历史版本表，不能保证请求交易日时点的证券属性。",
            )

        optional_missing = sorted(set(facts.missingSecurityFields) & {"industry", "listDate"})
        if optional_missing:
            findings.add(
                "MISSING_SECURITY_FIELDS", RuleImpact.WARN,
                "证券可选字段缺失",
                "缺失的非关键证券字段：" + ", ".join(optional_missing),
            )

        if security.board != "MAIN" or security.isSt is True or security.isActive is False:
            findings.add(
                "SECURITY_SCOPE_FACT", RuleImpact.INFO,
                "证券范围事实",
                f"board={security.board}, isSt={security.isSt}, isActive={security.isActive}；"
                "该事实不属于阶段2B股票池准入规则，不影响门禁。",
            )

    @staticmethod
    def _evaluate_market_data(
        request: AgentTeamRequest,
        value: Stage2AQualityInput,
        findings: _FindingCollector,
    ) -> None:
        market = value.marketData
        technical = value.technicalMetrics
        facts = value.dataQualityContext.facts
        loaded = facts.loadedBarCount
        counts_consistent = (
            loaded >= 0
            and market.actualBars == loaded
            and technical.actualBars == loaded
            and market.available == (loaded > 0)
        )
        technical_contradiction = counts_consistent and technical.available and (
            loaded < EXPECTED_REQUIRED_BARS or facts.invalidBarCount > 0
        )

        if counts_consistent and not technical_contradiction:
            if loaded == 0:
                findings.add(
                    "MARKET_DATA_MISSING", RuleImpact.BLOCK,
                    "本地日线缺失",
                    "请求日期及以前没有可用的本地QFQ日线。",
                )
            elif loaded < facts.requiredBarsForTechnicalMetrics:
                findings.add(
                    "INSUFFICIENT_DAILY_BARS", RuleImpact.BLOCK,
                    "本地日线数量不足",
                    f"已加载{loaded}条，技术指标需要{facts.requiredBarsForTechnicalMetrics}条。",
                )
            elif facts.invalidBarCount > 0:
                findings.add(
                    "INVALID_DAILY_BARS", RuleImpact.BLOCK,
                    "本地日线存在非法记录",
                    f"检测到{facts.invalidBarCount}条非法OHLCV记录。",
                )
            elif not technical.available:
                findings.add(
                    "TECHNICAL_METRICS_UNAVAILABLE", RuleImpact.BLOCK,
                    "技术指标不可用",
                    "日线数量和合法性满足要求，但阶段2A技术指标仍不可用。",
                )

        dates_consistent = (
            market.effectiveTradeDate == technical.effectiveTradeDate == facts.effectiveTradeDate
            and market.requestedTradeDate == technical.requestedTradeDate
            == facts.requestedTradeDate == request.tradeDate
        )
        if dates_consistent and facts.effectiveTradeDate is not None:
            expected_lag = (request.tradeDate - facts.effectiveTradeDate).days
            if expected_lag == facts.naturalDayLag and expected_lag >= 0:
                if expected_lag > MAX_NON_BLOCKING_NATURAL_DAY_LAG:
                    findings.add(
                        "MARKET_DATA_TOO_STALE", RuleImpact.BLOCK,
                        "本地行情严重过旧",
                        f"有效日期距离请求日期{expected_lag}个自然日，超过冻结阈值"
                        f"{MAX_NON_BLOCKING_NATURAL_DAY_LAG}日。",
                    )
                elif expected_lag > 0:
                    findings.add(
                        "REQUEST_DATE_NOT_EXACT", RuleImpact.WARN,
                        "请求日期未精确命中",
                        f"使用请求日期之前{expected_lag}个自然日的最近本地记录；"
                        "当前没有交易日历，不能据此断言缺失交易日。",
                    )

        if facts.missingAmountCount > 0 or facts.missingTurnoverRateCount > 0:
            findings.add(
                "OPTIONAL_MARKET_FIELDS_MISSING", RuleImpact.WARN,
                "可选行情字段存在缺失",
                f"amount缺失{facts.missingAmountCount}条，"
                f"turnoverRate缺失{facts.missingTurnoverRateCount}条；"
                "这些字段不阻断阶段2A技术指标。",
            )

        if facts.maximumObservedNaturalDayGap is not None \
                and facts.maximumObservedNaturalDayGap > MAX_NON_BLOCKING_NATURAL_DAY_LAG:
            findings.add(
                "LARGE_NATURAL_DAY_GAP", RuleImpact.WARN,
                "观察到较大自然日间隔",
                f"已加载记录之间的最大自然日间隔为"
                f"{facts.maximumObservedNaturalDayGap}日；当前没有交易日历，"
                "不能据此断言缺失交易日。",
            )

    @staticmethod
    def _evaluate_capability_limits(
        value: Stage2AQualityInput,
        findings: _FindingCollector,
    ) -> None:
        facts = value.dataQualityContext.facts
        if not facts.tradingCalendarAvailable:
            findings.add(
                "TRADING_CALENDAR_UNAVAILABLE", RuleImpact.INFO,
                "交易日历不可用",
                "阶段2A没有交易日历，规则不会把周末或节假日推断为缺失交易日。",
            )
        if not facts.sourceConsistencyAssessable:
            findings.add(
                "SOURCE_CONSISTENCY_UNASSESSABLE", RuleImpact.INFO,
                "跨源一致性不可评估",
                "阶段2A没有逐条来源与更新时间，规则不会伪造跨源一致性结论。",
            )


def direct_stage_2a_evidence_fields(request: AgentTeamRequest) -> dict[str, Any]:
    """Return the exact four-section projection used by Stage 2B evidence tests."""
    return {
        "security": deepcopy(request.contextSnapshot.security),
        "marketData": deepcopy(request.contextSnapshot.marketData),
        "technicalMetrics": deepcopy(request.contextSnapshot.technicalMetrics),
        "dataQualityContext": deepcopy(request.contextSnapshot.dataQualityContext),
    }


def _contains_forbidden_evidence_conclusion(value: Any) -> bool:
    if isinstance(value, dict):
        for key, item in value.items():
            normalized = str(key).replace("_", "").lower()
            if normalized in FORBIDDEN_EVIDENCE_CONCLUSION_FIELDS:
                return True
            if _contains_forbidden_evidence_conclusion(item):
                return True
        return False
    if isinstance(value, list):
        return any(_contains_forbidden_evidence_conclusion(item) for item in value)
    return False
