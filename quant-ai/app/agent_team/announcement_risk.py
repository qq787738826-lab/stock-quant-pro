from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from copy import deepcopy
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from zoneinfo import ZoneInfo

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


RULE_VERSION = "1.4.0-stage-2g-announcement-risk-v1"
CONTEXT_PROFILE = "AGENT_CONTEXT_2G_V1"
CONTEXT_SCHEMA_VERSION = "SECURITY_EVENTS_CONTEXT_V1"
PRODUCER = "AgentSecurityEventsContextService"
PRODUCER_VERSION = "JAVA_SECURITY_EVENTS_CONTEXT_V1"
SOURCE_CODE = "AKSHARE_CNINFO_RESEARCH_V1"
PROVIDER_CONTRACT_VERSION = "AKSHARE_CNINFO_PROVIDER_V1"
ASSURANCE_LEVEL = "RESEARCH"
PUBLISH_TIME_PRECISION = "DATE_ONLY"
CANONICAL_CONTRACT_VERSION = "ANNOUNCEMENT_CANONICAL_V1"
MARKET_TIMEZONE = "Asia/Shanghai"
LOOKBACK_DAYS = 180
INPUT_INVALID = "ANNOUNCEMENT_RISK_INPUT_INVALID"
UNAVAILABLE_REASON_CODES = frozenset({
    "ANNOUNCEMENT_NO_COMPLETE_CAPTURE",
    "ANNOUNCEMENT_CAPTURE_STALE",
    "ANNOUNCEMENT_CAPTURE_RANGE_INCOMPLETE",
    "ANNOUNCEMENT_SOURCE_UNVERIFIABLE",
    "ANNOUNCEMENT_CONTEXT_INVALID",
    "ANNOUNCEMENT_FUTURE_REQUEST_DATE",
})
LIMITATIONS = [
    "RESEARCH_SOURCE_ONLY",
    "DATE_ONLY_PUBLICATION_PRECISION",
    "NO_REVISION_RELATIONSHIP_GUARANTEE",
    "NO_HISTORICAL_COMPLETENESS_GUARANTEE",
    "NO_FORMAL_OR_PIT_QUALIFICATION",
    "NO_PDF_SEMANTIC_PARSING",
    "RESEARCH_ONLY",
]
FINDING_CODES = (
    "ANNOUNCEMENT_SOURCE_COVERAGE_ASSESSED",
    "ANNOUNCEMENT_REGULATORY_DELISTING_ASSESSED",
    "ANNOUNCEMENT_FINANCIAL_LITIGATION_ASSESSED",
    "ANNOUNCEMENT_OWNERSHIP_OPERATION_ASSESSED",
    "ANNOUNCEMENT_RESEARCH_LIMITATIONS_ASSESSED",
)
FINDING_TITLES = (
    "来源覆盖与资格",
    "监管、退市和风险警示",
    "财务、债务和诉讼",
    "股东、减持、质押、担保及经营风险",
    "时效性、修订限制和研究边界",
)
BASE_FIELDS = frozenset({
    "available", "queriedAt", "queryScope", "producer", "producerVersion",
    "contextProfile", "schemaVersion", "symbol", "requestTradeDate",
    "marketTimezone", "knowledgeCutoff", "lookbackStartDate", "lookbackDays",
    "sourceCode", "providerContractVersion", "assuranceLevel", "formalEligible",
    "pitVerified", "revisionRelationshipGuaranteed",
    "reportedPublishTimePrecision", "completeCapture", "captureBatchVersion",
    "captureObservedAt", "captureAgeHours", "eventCount", "events", "limitations",
})
EVENT_FIELDS = frozenset({
    "sourceAnnouncementId", "sourceIdentityStrength", "symbol", "securityName",
    "title", "reportedPublishDate", "reportedPublishTimePrecision", "sourceUrl",
    "normalizedSourceUrl", "sourceUrlHash", "firstObservedAt", "knownAt",
    "canonicalContentHash", "observationVersion", "sourceCode",
    "providerContractVersion", "assuranceLevel", "formalEligible", "pitVerified",
    "revisionRelationshipGuaranteed",
})
DELISTING_EXCLUSIONS = (
    "撤销退市风险警示", "撤销其他风险警示", "申请撤销风险警示",
)
OWNERSHIP_EXCLUSIONS = (
    "解除股份质押", "股份解除冻结", "解除轮候冻结",
)
CORRECTION_CONTEXT = ("年报", "半年报", "季报", "业绩", "财务")
TRACKING_QUERY_KEYS = frozenset({"from", "source", "spm", "track", "tracking"})
EXPLICIT_ID_KEYS = (
    "announcement_id", "announcement_no", "announcementid", "announcementno",
    "bulletin_id", "bulletinid",
)


class Group(str, Enum):
    REGULATORY_DELISTING = "REGULATORY_DELISTING"
    FINANCIAL_LITIGATION = "FINANCIAL_LITIGATION"
    OWNERSHIP_OPERATION = "OWNERSHIP_OPERATION"
    RESEARCH_LIMITATIONS = "RESEARCH_LIMITATIONS"


@dataclass(frozen=True)
class Rule:
    tag: str
    group: Group
    severity: Severity
    keyword: str
    exclusions: tuple[str, ...] = ()


@dataclass(frozen=True)
class Match:
    severity: Severity
    tags: tuple[str, ...]
    groups: frozenset[Group]
    normalized_title: str


@dataclass(frozen=True)
class EventFact:
    raw: dict[str, Any]
    source_announcement_id: str
    title: str
    reported_publish_date: date
    known_at: datetime
    canonical_hash: str
    observation_version: str


@dataclass(frozen=True)
class RiskEvent:
    event: EventFact
    match: Match
    age_days: int
    deduction: int


@dataclass(frozen=True)
class ParsedContext:
    raw: dict[str, Any]
    queried_at: datetime
    capture_observed_at: datetime
    events: tuple[EventFact, ...]


@dataclass(frozen=True)
class AnnouncementRiskEvaluation:
    status: RunStatus
    gate_status: GateStatus
    decision: AgentDecision
    score: int
    confidence: int
    summary: str
    findings: tuple[Finding, ...]
    evidence: tuple[Evidence, ...]
    errors: tuple[AgentError, ...]


def _rules() -> tuple[Rule, ...]:
    values: list[Rule] = []

    def add(
        group: Group,
        severity: Severity,
        tag: str,
        keywords: tuple[str, ...],
        exclusions: tuple[str, ...] = (),
    ) -> None:
        values.extend(
            Rule(tag, group, severity, keyword, exclusions)
            for keyword in keywords
        )

    add(Group.REGULATORY_DELISTING, Severity.CRITICAL, "DELISTING_TERMINATION",
        ("终止上市", "可能被终止上市", "退市风险警示", "暂停上市", "进入退市整理期"),
        DELISTING_EXCLUSIONS)
    add(Group.REGULATORY_DELISTING, Severity.HIGH, "OTHER_RISK_WARNING",
        ("实施其他风险警示", "被实施风险警示"), DELISTING_EXCLUSIONS)
    add(Group.REGULATORY_DELISTING, Severity.CRITICAL,
        "REGULATORY_ENFORCEMENT_CRITICAL",
        ("立案调查", "涉嫌违法违规", "行政处罚决定书", "公开谴责", "纪律处分"))
    add(Group.REGULATORY_DELISTING, Severity.HIGH,
        "REGULATORY_ENFORCEMENT_HIGH", ("监管措施", "警示函", "责令改正"))
    add(Group.REGULATORY_DELISTING, Severity.WARN,
        "REGULATORY_INQUIRY", ("问询函", "关注函", "监管工作函"))

    add(Group.FINANCIAL_LITIGATION, Severity.CRITICAL, "FINANCIAL_DEBT_CRITICAL",
        ("无法表示意见", "否定意见", "破产重整", "预重整", "债务违约",
         "债务逾期", "不能清偿到期债务"))
    add(Group.FINANCIAL_LITIGATION, Severity.HIGH, "FINANCIAL_PERFORMANCE_HIGH",
        ("业绩预亏", "首亏", "续亏", "业绩大幅下降", "业绩下修",
         "会计差错更正", "财务数据更正", "保留意见", "非标准审计意见"))
    add(Group.FINANCIAL_LITIGATION, Severity.HIGH, "LITIGATION_OPERATION_HIGH",
        ("重大诉讼", "重大仲裁", "被申请破产", "主要银行账户被冻结",
         "停产", "重大安全事故", "重大合同终止", "核心业务暂停"))
    add(Group.FINANCIAL_LITIGATION, Severity.WARN, "LITIGATION_PROGRESS",
        ("诉讼进展", "仲裁进展", "风险提示公告"))

    add(Group.OWNERSHIP_OPERATION, Severity.HIGH, "OWNERSHIP_ENFORCEMENT_HIGH",
        ("被动减持", "司法拍卖", "股份冻结", "轮候冻结", "质押违约", "平仓风险"),
        OWNERSHIP_EXCLUSIONS)
    add(Group.OWNERSHIP_OPERATION, Severity.WARN, "OWNERSHIP_EXPOSURE_WARN",
        ("减持计划", "减持进展", "股份质押"), OWNERSHIP_EXCLUSIONS)
    add(Group.OWNERSHIP_OPERATION, Severity.HIGH, "FUND_OCCUPATION_HIGH",
        ("违规担保", "资金占用", "关联方占用"))
    add(Group.OWNERSHIP_OPERATION, Severity.WARN, "EXTERNAL_GUARANTEE_WARN",
        ("对外担保",))
    add(Group.RESEARCH_LIMITATIONS, Severity.WARN,
        "CORRECTION_CLARIFICATION_WARN",
        ("更正公告", "补充公告", "澄清公告", "致歉公告"))
    return tuple(values)


RULES = _rules()


class AnnouncementRiskRuleEngine:

    def evaluate(
        self,
        request: AgentTeamRequest,
        generated_at: datetime,
        data_quality_gate: GateStatus,
    ) -> AnnouncementRiskEvaluation:
        if data_quality_gate is GateStatus.BLOCKED:
            return AnnouncementRiskEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=GateStatus.NOT_APPLICABLE,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="DATA_QUALITY门禁阻断，公告风险规则已安全降级。",
                findings=(),
                evidence=(),
                errors=(),
            )
        raw = deepcopy(request.contextSnapshot.securityEvents)
        try:
            _validate_base(raw, request)
        except (KeyError, TypeError, ValueError):
            return _invalid()
        if raw.get("available") is not True:
            reason_code = raw.get("reasonCode")
            if (
                reason_code not in UNAVAILABLE_REASON_CODES
                or not isinstance(raw.get("reason"), str)
                or not raw["reason"].strip()
                or raw["completeCapture"] is not False
                or raw["captureBatchVersion"] is not None
                or raw["captureObservedAt"] is not None
                or raw["captureAgeHours"] is not None
                or raw["eventCount"] != 0
                or raw["events"]
            ):
                return _invalid()
            return AnnouncementRiskEvaluation(
                status=RunStatus.INSUFFICIENT_DATA,
                gate_status=GateStatus.NOT_APPLICABLE,
                decision=AgentDecision.NOT_APPLICABLE,
                score=0,
                confidence=0,
                summary="可靠securityEvents不可用，未形成公告风险结论。",
                findings=(),
                evidence=(),
                errors=(AgentError(
                    code=reason_code,
                    message="Java提供的公告事实上下文不可用。",
                ),),
            )
        try:
            parsed = _validate_available(raw, request)
            risks, score = _evaluate_risks(parsed.events, request.tradeDate)
        except (KeyError, TypeError, ValueError):
            return _invalid()

        coverage = Evidence(
            evidenceId=f"announcement-risk-coverage-{request.contextHash}",
            category=EvidenceCategory.QUERY_RESULT,
            sourceType=EvidenceSourceType.JAVA_ENGINE,
            sourceName=PRODUCER,
            sourceRef="contextSnapshot.securityEvents",
            symbol=request.symbol,
            tradeDate=request.tradeDate,
            observedAt=parsed.capture_observed_at,
            collectedAt=generated_at,
            fields={"securityEvents": parsed.raw},
            contentHash=request.contextHash,
        )
        event_evidence = tuple(
            Evidence(
                evidenceId=(
                    "announcement-risk-event-"
                    f"{risk.event.observation_version}"
                ),
                category=EvidenceCategory.SECURITY_EVENT,
                sourceType=EvidenceSourceType.JAVA_ENGINE,
                sourceName=PRODUCER,
                sourceRef=(
                    "contextSnapshot.securityEvents.events."
                    f"{risk.event.observation_version}"
                ),
                symbol=request.symbol,
                tradeDate=request.tradeDate,
                observedAt=risk.event.known_at,
                collectedAt=generated_at,
                fields={"event": risk.event.raw},
                contentHash=risk.event.canonical_hash,
            )
            for risk in risks
        )
        findings = _findings(request, parsed, risks, coverage.evidenceId)
        has_risk = bool(risks)
        return AnnouncementRiskEvaluation(
            status=RunStatus.COMPLETED,
            gate_status=GateStatus.WARN if has_risk else GateStatus.PASS,
            decision=AgentDecision.WARN if has_risk else AgentDecision.PASS,
            score=score,
            confidence=40,
            summary=(
                f"当前完整抓取范围命中{len(risks)}条冻结标题风险事件；"
                "结果仅用于研究提示。"
                if has_risk
                else "在当前完整抓取范围内未匹配冻结标题规则；结果仅用于研究提示。"
            ),
            findings=findings,
            evidence=(coverage, *event_evidence),
            errors=(),
        )


def normalize_title(value: str) -> str:
    if not isinstance(value, str):
        raise ValueError("公告标题必须是字符串")
    normalized = unicodedata.normalize("NFKC", value)
    punctuation = str.maketrans({
        "，": ",", "、": ",", "。": ".", "；": ";", "：": ":",
        "！": "!", "？": "?", "【": "[", "】": "]", "（": "(",
        "）": ")", "“": '"', "”": '"', "‘": "'", "’": "'",
    })
    result = " ".join(normalized.translate(punctuation).strip().split()).upper()
    if not result:
        raise ValueError("公告标题不能为空")
    return result


def classify_title(value: str) -> Match:
    title = normalize_title(value)
    tags: list[str] = []
    groups: set[Group] = set()
    severity = Severity.INFO
    for rule in RULES:
        if rule.keyword in title and not any(
            exclusion in title for exclusion in rule.exclusions
        ):
            if rule.tag not in tags:
                tags.append(rule.tag)
            groups.add(rule.group)
            severity = _max_severity(severity, rule.severity)
    if (
        ("更正" in title or "补充更正" in title)
        and any(context in title for context in CORRECTION_CONTEXT)
    ):
        if "FINANCIAL_REPORT_CORRECTION" not in tags:
            tags.append("FINANCIAL_REPORT_CORRECTION")
        groups.add(Group.FINANCIAL_LITIGATION)
        severity = _max_severity(severity, Severity.HIGH)
    return Match(severity, tuple(tags), frozenset(groups), title)


def canonical_text(event: dict[str, Any]) -> str:
    value = {
        "assuranceLevel": ASSURANCE_LEVEL,
        "contractVersion": CANONICAL_CONTRACT_VERSION,
        "formalEligible": False,
        "normalizedSourceUrl": event["normalizedSourceUrl"],
        "pitVerified": False,
        "providerContractVersion": PROVIDER_CONTRACT_VERSION,
        "reportedPublishDate": event["reportedPublishDate"],
        "reportedPublishTimePrecision": PUBLISH_TIME_PRECISION,
        "revisionRelationshipGuaranteed": False,
        "securityName": event["securityName"],
        "sourceAnnouncementId": event["sourceAnnouncementId"],
        "sourceCode": SOURCE_CODE,
        "sourceIdentityStrength": event["sourceIdentityStrength"],
        "symbol": event["symbol"],
        "title": event["title"],
    }
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _validate_base(raw: dict[str, Any], request: AgentTeamRequest) -> None:
    expected = BASE_FIELDS | (
        frozenset() if raw.get("available") is True
        else frozenset({"reasonCode", "reason"})
    )
    _object(raw, expected)
    scope = _object(raw["queryScope"], frozenset({"symbol", "tradeDate"}))
    if (
        _boolean(raw["available"]) != (raw.get("available") is True)
        or raw["producer"] != PRODUCER
        or raw["producerVersion"] != PRODUCER_VERSION
        or raw["contextProfile"] != CONTEXT_PROFILE
        or raw["schemaVersion"] != CONTEXT_SCHEMA_VERSION
        or raw["symbol"] != request.symbol
        or raw["requestTradeDate"] != request.tradeDate.isoformat()
        or scope != {
            "symbol": request.symbol,
            "tradeDate": request.tradeDate.isoformat(),
        }
        or raw["marketTimezone"] != MARKET_TIMEZONE
        or _date(raw["lookbackStartDate"])
        != request.tradeDate - timedelta(days=LOOKBACK_DAYS - 1)
        or raw["lookbackDays"] != LOOKBACK_DAYS
        or raw["sourceCode"] != SOURCE_CODE
        or raw["providerContractVersion"] != PROVIDER_CONTRACT_VERSION
        or raw["assuranceLevel"] != ASSURANCE_LEVEL
        or _boolean(raw["formalEligible"])
        or _boolean(raw["pitVerified"])
        or _boolean(raw["revisionRelationshipGuaranteed"])
        or raw["reportedPublishTimePrecision"] != PUBLISH_TIME_PRECISION
        or raw["limitations"] != LIMITATIONS
        or not isinstance(raw["events"], list)
        or raw["eventCount"] != len(raw["events"])
    ):
        raise ValueError("securityEvents基础契约无效")
    queried_at = _instant(raw["queriedAt"])
    cutoff = _instant(raw["knowledgeCutoff"])
    current_date = queried_at.astimezone(ZoneInfo(MARKET_TIMEZONE)).date()
    expected_cutoff = (
        datetime.combine(
            request.tradeDate,
            time.max,
            tzinfo=ZoneInfo(MARKET_TIMEZONE),
        )
        if request.tradeDate < current_date
        else queried_at
    )
    if cutoff != expected_cutoff:
        raise ValueError("knowledgeCutoff与请求日期语义不一致")


def _validate_available(
    raw: dict[str, Any],
    request: AgentTeamRequest,
) -> ParsedContext:
    if (
        raw["completeCapture"] is not True
        or not isinstance(raw["captureBatchVersion"], str)
        or not raw["captureBatchVersion"].startswith("ANNOUNCEMENT_BATCH_V1:")
        or not isinstance(raw["captureAgeHours"], (int, float))
        or isinstance(raw["captureAgeHours"], bool)
        or not 0 <= Decimal(str(raw["captureAgeHours"])) <= Decimal(24)
    ):
        raise ValueError("公告完整覆盖元数据无效")
    queried_at = _instant(raw["queriedAt"])
    cutoff = _instant(raw["knowledgeCutoff"])
    capture_observed_at = _instant(raw["captureObservedAt"])
    capture_age = cutoff - capture_observed_at
    capture_age_micros = (
        (capture_age.days * 86_400 + capture_age.seconds) * 1_000_000
        + capture_age.microseconds
    )
    expected_age_hours = (
        Decimal(capture_age_micros // 1000)
        / Decimal("3600000")
    ).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)
    if (
        request.tradeDate
        > queried_at.astimezone(ZoneInfo(MARKET_TIMEZONE)).date()
        or capture_age.total_seconds() < 0
        or capture_age > timedelta(hours=24)
        or Decimal(str(raw["captureAgeHours"])) != expected_age_hours
    ):
        raise ValueError("captureObservedAt或captureAgeHours无效")
    values: list[EventFact] = []
    seen: set[str] = set()
    versions: set[str] = set()
    lookback_start = request.tradeDate - timedelta(days=LOOKBACK_DAYS - 1)
    for item in raw["events"]:
        event = _object(item, EVENT_FIELDS)
        reported_date = _date(event["reportedPublishDate"])
        first_observed_at = _instant(event["firstObservedAt"])
        known_at = _instant(event["knownAt"])
        if (
            event["symbol"] != request.symbol
            or not isinstance(event["securityName"], str)
            or not event["securityName"].strip()
            or len(event["securityName"]) > 128
            or not isinstance(event["title"], str)
            or not event["title"].strip()
            or len(event["title"]) > 1024
            or reported_date < lookback_start
            or reported_date > request.tradeDate
            or reported_date
            > first_observed_at.astimezone(
                ZoneInfo(MARKET_TIMEZONE)
            ).date()
            or first_observed_at != known_at
            or known_at > cutoff
            or event["sourceCode"] != SOURCE_CODE
            or event["providerContractVersion"] != PROVIDER_CONTRACT_VERSION
            or event["assuranceLevel"] != ASSURANCE_LEVEL
            or _boolean(event["formalEligible"])
            or _boolean(event["pitVerified"])
            or _boolean(event["revisionRelationshipGuaranteed"])
            or event["reportedPublishTimePrecision"] != PUBLISH_TIME_PRECISION
            or not re.fullmatch(r"[0-9a-f]{64}", event["sourceUrlHash"])
            or not re.fullmatch(r"[0-9a-f]{64}", event["canonicalContentHash"])
            or not re.fullmatch(r"[0-9a-f]{64}", event["observationVersion"])
        ):
            raise ValueError("公告事件字段无效")
        source_id, strength, normalized_url = _source_identity(event["sourceUrl"])
        if (
            source_id != event["sourceAnnouncementId"]
            or strength != event["sourceIdentityStrength"]
            or normalized_url != event["normalizedSourceUrl"]
            or _sha256(normalized_url) != event["sourceUrlHash"]
            or _sha256(canonical_text(event)) != event["canonicalContentHash"]
            or source_id in seen
            or event["observationVersion"] in versions
        ):
            raise ValueError("公告事件身份或Hash无效")
        seen.add(source_id)
        versions.add(event["observationVersion"])
        values.append(EventFact(
            raw=event,
            source_announcement_id=source_id,
            title=event["title"],
            reported_publish_date=reported_date,
            known_at=known_at,
            canonical_hash=event["canonicalContentHash"],
            observation_version=event["observationVersion"],
        ))
    expected = sorted(
        values,
        key=lambda event: (
            -event.reported_publish_date.toordinal(),
            -int(event.known_at.timestamp() * 1_000_000),
            event.source_announcement_id,
            event.observation_version,
        ),
    )
    if values != expected:
        raise ValueError("公告事件顺序无效")
    return ParsedContext(raw, queried_at, capture_observed_at, tuple(values))


def _evaluate_risks(
    events: tuple[EventFact, ...],
    request_trade_date: date,
) -> tuple[tuple[RiskEvent, ...], int]:
    risks: list[RiskEvent] = []
    deductions = 0
    for event in events:
        match = classify_title(event.title)
        if match.severity is Severity.INFO:
            continue
        age = (request_trade_date - event.reported_publish_date).days
        if not 0 <= age < LOOKBACK_DAYS:
            raise ValueError("公告时效超出180日")
        factor = (
            Decimal("1.00") if age <= 7
            else Decimal("0.75") if age <= 30
            else Decimal("0.50") if age <= 90
            else Decimal("0.25")
        )
        base = {
            Severity.CRITICAL: 40,
            Severity.HIGH: 25,
            Severity.WARN: 10,
        }[match.severity]
        deduction = int(
            (Decimal(base) * factor).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
        )
        deductions += deduction
        risks.append(RiskEvent(event, match, age, deduction))
    risks.sort(key=lambda risk: (
        -_severity_rank(risk.match.severity),
        -risk.event.reported_publish_date.toordinal(),
        risk.event.source_announcement_id,
        risk.event.observation_version,
    ))
    return tuple(risks), max(0, 100 - deductions)


def _findings(
    request: AgentTeamRequest,
    parsed: ParsedContext,
    risks: tuple[RiskEvent, ...],
    coverage_id: str,
) -> tuple[Finding, ...]:
    groups = (
        None,
        Group.REGULATORY_DELISTING,
        Group.FINANCIAL_LITIGATION,
        Group.OWNERSHIP_OPERATION,
        Group.RESEARCH_LIMITATIONS,
    )
    event_ids = {
        risk.event.observation_version:
        f"announcement-risk-event-{risk.event.observation_version}"
        for risk in risks
    }
    counts = [
        len(risks) if group is None
        else sum(group in risk.match.groups for risk in risks)
        for group in groups
    ]
    severities = [
        Severity.INFO,
        _group_severity(risks, Group.REGULATORY_DELISTING),
        _group_severity(risks, Group.FINANCIAL_LITIGATION),
        _group_severity(risks, Group.OWNERSHIP_OPERATION),
        Severity.WARN,
    ]
    details = (
        f"completeCapture=true,eventCount={len(parsed.events)},"
        f"sourceCode={SOURCE_CODE},assuranceLevel=RESEARCH",
        f"matchedEventCount={counts[1]},"
        f"highestSeverity={severities[1].value}",
        f"matchedEventCount={counts[2]},"
        f"highestSeverity={severities[2].value}",
        f"matchedEventCount={counts[3]},"
        f"highestSeverity={severities[3].value}",
        f"matchedEventCount={counts[4]},confidence=40,"
        "dateOnly=true,revisionRelationshipGuaranteed=false,"
        "historicalCompletenessGuaranteed=false,researchOnly=true",
    )
    findings: list[Finding] = []
    for index, (code, title, detail, severity, group) in enumerate(
        zip(
            FINDING_CODES,
            FINDING_TITLES,
            details,
            severities,
            groups,
            strict=True,
        ),
        start=1,
    ):
        references = [coverage_id]
        if group is not None:
            references.extend(
                event_ids[risk.event.observation_version]
                for risk in risks
                if group in risk.match.groups
            )
        findings.append(Finding(
            findingId=(
                f"announcement-risk-finding-{index:02d}-"
                f"{code.lower().replace('_', '-')}-{request.contextHash}"
            ),
            code=code,
            severity=severity,
            title=title,
            detail=detail,
            evidenceIds=list(dict.fromkeys(references)),
        ))
    return tuple(findings)


def _invalid() -> AnnouncementRiskEvaluation:
    return AnnouncementRiskEvaluation(
        status=RunStatus.INSUFFICIENT_DATA,
        gate_status=GateStatus.NOT_APPLICABLE,
        decision=AgentDecision.NOT_APPLICABLE,
        score=0,
        confidence=0,
        summary="securityEvents契约或Hash无效，未形成公告风险结论。",
        findings=(),
        evidence=(),
        errors=(AgentError(
            code=INPUT_INVALID,
            message="Java提供的公告事实上下文未通过Python契约校验。",
        ),),
    )


def _source_identity(source_url: Any) -> tuple[str, str, str]:
    normalized = _normalize_url(source_url)
    parsed = urlsplit(normalized)
    query = {key.lower(): value for key, value in parse_qsl(parsed.query)}
    for key in EXPLICIT_ID_KEYS:
        value = query.get(key)
        if value and re.fullmatch(r"[A-Za-z0-9._-]+", value):
            return f"CNINFO:{value}", "CNINFO_ID", normalized
    filename = parsed.path.rsplit("/", 1)[-1]
    basename = filename.rsplit(".", 1)[0]
    if re.fullmatch(r"[A-Za-z0-9_-]*[0-9][A-Za-z0-9._-]{5,}", basename):
        return f"CNINFO:{basename}", "CNINFO_ID", normalized
    return f"CNINFO_URL_SHA256:{_sha256(normalized)}", "URL_DERIVED", normalized


def _normalize_url(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("公告URL必须是字符串")
    parsed = urlsplit(value.strip())
    scheme = parsed.scheme.lower()
    host = (parsed.hostname or "").lower()
    if scheme not in {"http", "https"} or not host:
        raise ValueError("公告URL必须是HTTP或HTTPS")
    port = parsed.port
    netloc = host
    if port is not None and not (
        scheme == "http" and port == 80 or scheme == "https" and port == 443
    ):
        netloc = f"{host}:{port}"
    items = [
        (key, item)
        for key, item in parse_qsl(parsed.query, keep_blank_values=True)
        if not key.lower().startswith("utm_")
        and key.lower() not in TRACKING_QUERY_KEYS
    ]
    items.sort(key=lambda item: (item[0], item[1]))
    return urlunsplit((
        scheme,
        netloc,
        parsed.path or "/",
        urlencode(items, doseq=True),
        "",
    ))


def _group_severity(
    risks: tuple[RiskEvent, ...],
    group: Group,
) -> Severity:
    result = Severity.INFO
    for risk in risks:
        if group in risk.match.groups:
            result = _max_severity(result, risk.match.severity)
    return result


def _max_severity(left: Severity, right: Severity) -> Severity:
    return left if _severity_rank(left) >= _severity_rank(right) else right


def _severity_rank(value: Severity) -> int:
    return {
        Severity.INFO: 0,
        Severity.WARN: 1,
        Severity.HIGH: 2,
        Severity.CRITICAL: 3,
    }[value]


def _object(value: Any, fields: frozenset[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError("对象字段白名单无效")
    return value


def _boolean(value: Any) -> bool:
    if not isinstance(value, bool):
        raise ValueError("布尔字段无效")
    return value


def _date(value: Any) -> date:
    if not isinstance(value, str):
        raise ValueError("日期字段无效")
    return date.fromisoformat(value)


def _instant(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("时间字段无效")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("时间必须带时区")
    return parsed


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
