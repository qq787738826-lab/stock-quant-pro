from __future__ import annotations

import json
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

from app.agent_team.agents import AnnouncementRiskAgent
from app.agent_team.announcement_risk import (
    AnnouncementRiskRuleEngine,
    DELISTING_EXCLUSIONS,
    Group,
    OWNERSHIP_EXCLUSIONS,
    RULES,
    SOURCE_CODE,
    canonical_text,
    classify_title,
)
from app.agent_team.models import (
    AgentCode,
    AgentTeamRequest,
    GateStatus,
    RunIds,
    STAGE_2G_ANNOUNCEMENT_RISK_RULE_VERSION,
)


TRADE_DATE = date(2025, 6, 30)
QUERIED_AT = datetime(2025, 6, 30, 8, 0, tzinfo=timezone.utc)


def _event(
    title: str,
    reported_date: date = TRADE_DATE,
    identifier: str = "1212345678",
) -> dict:
    import hashlib

    source_url = (
        "https://static.cninfo.com.cn/finalpage/"
        f"{reported_date.isoformat()}/{identifier}.PDF"
    )
    value = {
        "sourceAnnouncementId": f"CNINFO:{identifier}",
        "sourceIdentityStrength": "CNINFO_ID",
        "symbol": "000001",
        "securityName": "平安银行",
        "title": title,
        "reportedPublishDate": reported_date.isoformat(),
        "reportedPublishTimePrecision": "DATE_ONLY",
        "sourceUrl": source_url,
        "normalizedSourceUrl": source_url.lower().replace(".pdf", ".PDF"),
        "sourceUrlHash": "",
        "firstObservedAt": QUERIED_AT.isoformat().replace("+00:00", "Z"),
        "knownAt": QUERIED_AT.isoformat().replace("+00:00", "Z"),
        "canonicalContentHash": "",
        "observationVersion": hashlib.sha256(
            f"{identifier}:{title}".encode()
        ).hexdigest(),
        "sourceCode": SOURCE_CODE,
        "providerContractVersion": "AKSHARE_CNINFO_PROVIDER_V1",
        "assuranceLevel": "RESEARCH",
        "formalEligible": False,
        "pitVerified": False,
        "revisionRelationshipGuaranteed": False,
    }
    value["normalizedSourceUrl"] = source_url
    value["sourceUrlHash"] = hashlib.sha256(source_url.encode()).hexdigest()
    value["canonicalContentHash"] = hashlib.sha256(
        canonical_text(value).encode()
    ).hexdigest()
    return value


def _request(events: list[dict], *, available: bool = True) -> AgentTeamRequest:
    context = {
        "available": available,
        "queriedAt": QUERIED_AT.isoformat().replace("+00:00", "Z"),
        "queryScope": {"symbol": "000001", "tradeDate": TRADE_DATE.isoformat()},
        "producer": "AgentSecurityEventsContextService",
        "producerVersion": "JAVA_SECURITY_EVENTS_CONTEXT_V1",
        "contextProfile": "AGENT_CONTEXT_2G_V1",
        "schemaVersion": "SECURITY_EVENTS_CONTEXT_V1",
        "symbol": "000001",
        "requestTradeDate": TRADE_DATE.isoformat(),
        "marketTimezone": "Asia/Shanghai",
        "knowledgeCutoff": QUERIED_AT.isoformat().replace("+00:00", "Z"),
        "lookbackStartDate": "2025-01-02",
        "lookbackDays": 180,
        "sourceCode": SOURCE_CODE,
        "providerContractVersion": "AKSHARE_CNINFO_PROVIDER_V1",
        "assuranceLevel": "RESEARCH",
        "formalEligible": False,
        "pitVerified": False,
        "revisionRelationshipGuaranteed": False,
        "reportedPublishTimePrecision": "DATE_ONLY",
        "completeCapture": available,
        "captureBatchVersion": "ANNOUNCEMENT_BATCH_V1:test" if available else None,
        "captureObservedAt": (
            QUERIED_AT.isoformat().replace("+00:00", "Z") if available else None
        ),
        "captureAgeHours": 0 if available else None,
        "eventCount": len(events),
        "events": events,
        "limitations": [
            "RESEARCH_SOURCE_ONLY",
            "DATE_ONLY_PUBLICATION_PRECISION",
            "NO_REVISION_RELATIONSHIP_GUARANTEE",
            "NO_HISTORICAL_COMPLETENESS_GUARANTEE",
            "NO_FORMAL_OR_PIT_QUALIFICATION",
            "NO_PDF_SEMANTIC_PARSING",
            "RESEARCH_ONLY",
        ],
    }
    if not available:
        context["reasonCode"] = "ANNOUNCEMENT_NO_COMPLETE_CAPTURE"
        context["reason"] = "test"
    placeholders = {key: {"available": False} for key in (
        "security", "marketData", "marketBreadth", "scanResult",
        "technicalMetrics", "backtestContext", "portfolioContext",
        "dataQualityContext",
    )}
    placeholders["securityEvents"] = context
    return AgentTeamRequest(
        schemaVersion="1.0",
        taskId=1,
        runIds=RunIds(
            dataQuality=11,
            marketRegime=12,
            technicalAnalysis=13,
            strategyBacktest=14,
            announcementRisk=15,
            positionRisk=16,
        ),
        symbol="000001",
        tradeDate=TRADE_DATE,
        contextHash="a" * 64,
        contextSchemaVersion="1.0",
        ruleVersion=STAGE_2G_ANNOUNCEMENT_RISK_RULE_VERSION,
        executionMode="LOCAL_RULES",
        contextSnapshot=placeholders,
        requestedAt=QUERIED_AT,
    )


class AnnouncementRiskAgentTest(unittest.TestCase):

    def setUp(self) -> None:
        self.engine = AnnouncementRiskRuleEngine()

    def test_every_frozen_keyword_has_expected_severity_and_group(self) -> None:
        for rule in RULES:
            with self.subTest(keyword=rule.keyword):
                match = classify_title(f"关于{rule.keyword}的公告")
                self.assertIn(rule.tag, match.tags)
                self.assertIn(rule.group, match.groups)
                self.assertGreaterEqual(
                    {"INFO": 0, "WARN": 1, "HIGH": 2, "CRITICAL": 3}[
                        match.severity.value
                    ],
                    {"INFO": 0, "WARN": 1, "HIGH": 2, "CRITICAL": 3}[
                        rule.severity.value
                    ],
                )

    def test_exclusions_do_not_hide_unrelated_regulatory_risk(self) -> None:
        for phrase in DELISTING_EXCLUSIONS:
            with self.subTest(phrase=phrase):
                match = classify_title(phrase)
                self.assertNotIn("DELISTING_TERMINATION", match.tags)
                self.assertNotIn("OTHER_RISK_WARNING", match.tags)
        for phrase in OWNERSHIP_EXCLUSIONS:
            with self.subTest(phrase=phrase):
                match = classify_title(phrase)
                self.assertNotIn("OWNERSHIP_ENFORCEMENT_HIGH", match.tags)
                self.assertNotIn("OWNERSHIP_EXPOSURE_WARN", match.tags)
        match = classify_title("撤销退市风险警示暨立案调查")
        self.assertEqual("CRITICAL", match.severity.value)
        self.assertIn("REGULATORY_ENFORCEMENT_CRITICAL", match.tags)

    def test_financial_correction_combination_is_high(self) -> None:
        match = classify_title("关于年报补充更正的公告")
        self.assertEqual("HIGH", match.severity.value)
        self.assertIn("FINANCIAL_REPORT_CORRECTION", match.tags)
        self.assertIn(Group.FINANCIAL_LITIGATION, match.groups)

    def test_valid_risks_score_once_per_announcement_and_sort_stably(self) -> None:
        events = [
            _event("立案调查暨重大诉讼", TRADE_DATE, "1212345678"),
            _event("问询函", date(2025, 6, 20), "1212345679"),
            _event("业绩预亏", date(2025, 5, 1), "1212345680"),
            _event("减持计划", date(2025, 2, 1), "1212345681"),
        ]
        result = self.engine.evaluate(_request(events), QUERIED_AT, GateStatus.PASS)
        self.assertEqual("COMPLETED", result.status.value)
        self.assertEqual("WARN", result.gate_status.value)
        # 40*1 + 10*.75 + 25*.50 + 10*.25 = 64 after per-event HALF_UP.
        self.assertEqual(36, result.score)
        self.assertEqual(40, result.confidence)
        self.assertEqual(5, len(result.findings))
        self.assertEqual(5, len(result.evidence))
        self.assertFalse(any(
            word in finding.detail
            for finding in result.findings
            for word in ("买入", "卖出", "清仓", "加仓")
        ))

    def test_score_clamps_at_zero_and_no_event_is_pass(self) -> None:
        risks = [
            _event("立案调查", TRADE_DATE, str(1212345600 + index))
            for index in range(3)
        ]
        result = self.engine.evaluate(_request(risks), QUERIED_AT, GateStatus.WARN)
        self.assertEqual(0, result.score)
        result = self.engine.evaluate(_request([]), QUERIED_AT, GateStatus.PASS)
        self.assertEqual(100, result.score)
        self.assertEqual("PASS", result.gate_status.value)
        self.assertEqual(5, len(result.findings))
        self.assertEqual(1, len(result.evidence))
        self.assertIn("未匹配冻结标题规则", result.summary)

    def test_data_quality_blocked_unavailable_and_invalid_are_safe(self) -> None:
        blocked = self.engine.evaluate(
            _request([_event("立案调查")]), QUERIED_AT, GateStatus.BLOCKED
        )
        self.assertEqual("INSUFFICIENT_DATA", blocked.status.value)
        self.assertFalse(blocked.findings)
        self.assertFalse(blocked.evidence)
        unavailable = self.engine.evaluate(
            _request([], available=False), QUERIED_AT, GateStatus.PASS
        )
        self.assertEqual(
            "ANNOUNCEMENT_NO_COMPLETE_CAPTURE",
            unavailable.errors[0].code,
        )
        request = _request([_event("立案调查")])
        request.contextSnapshot.securityEvents["events"][0][
            "canonicalContentHash"
        ] = "0" * 64
        invalid = self.engine.evaluate(request, QUERIED_AT, GateStatus.PASS)
        self.assertEqual("ANNOUNCEMENT_RISK_INPUT_INVALID", invalid.errors[0].code)

    def test_inconsistent_capture_age_and_out_of_window_event_are_invalid(
        self,
    ) -> None:
        inconsistent_age = _request([_event("问询函")])
        inconsistent_age.contextSnapshot.securityEvents["captureAgeHours"] = 1
        result = self.engine.evaluate(
            inconsistent_age,
            QUERIED_AT,
            GateStatus.PASS,
        )
        self.assertEqual("ANNOUNCEMENT_RISK_INPUT_INVALID", result.errors[0].code)

        outside_window = _request([
            _event("问询函", date(2025, 1, 1), "1212345699"),
        ])
        result = self.engine.evaluate(
            outside_window,
            QUERIED_AT,
            GateStatus.PASS,
        )
        self.assertEqual("ANNOUNCEMENT_RISK_INPUT_INVALID", result.errors[0].code)

        unavailable_with_event = _request(
            [_event("问询函")],
            available=False,
        )
        result = self.engine.evaluate(
            unavailable_with_event,
            QUERIED_AT,
            GateStatus.PASS,
        )
        self.assertEqual("ANNOUNCEMENT_RISK_INPUT_INVALID", result.errors[0].code)

    def test_agent_never_vetoes(self) -> None:
        output = AnnouncementRiskAgent().analyze(
            _request([_event("终止上市")]),
            QUERIED_AT,
            GateStatus.PASS,
        )
        self.assertEqual(AgentCode.ANNOUNCEMENT_RISK, output.agentCode)
        self.assertFalse(output.veto)

    def test_canonical_golden_vector(self) -> None:
        root = Path(__file__).resolve().parents[3]
        resources = root / "quant-server" / "src" / "test" / "resources" / "agent"
        value = json.loads(
            (resources / "announcement-canonical-v1-input.json").read_text(
                encoding="utf-8"
            )
        )
        expected_text = (
            resources / "announcement-canonical-v1-canonical.txt"
        ).read_text(encoding="utf-8").strip()
        expected_hash = (
            resources / "announcement-canonical-v1-sha256.txt"
        ).read_text(encoding="utf-8").strip()
        actual = canonical_text(value)
        self.assertEqual(expected_text, actual)
        import hashlib
        self.assertEqual(expected_hash, hashlib.sha256(actual.encode()).hexdigest())


if __name__ == "__main__":
    unittest.main()
