package com.stockquant.server.agent.announcement;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public final class AnnouncementContracts {

    public static final String SOURCE_CODE = "AKSHARE_CNINFO_RESEARCH_V1";
    public static final String PROVIDER_CONTRACT_VERSION = "AKSHARE_CNINFO_PROVIDER_V1";
    public static final String AKSHARE_VERSION = "1.18.64";
    public static final String ASSURANCE_LEVEL = "RESEARCH";
    public static final String PUBLISH_TIME_PRECISION = "DATE_ONLY";
    public static final String CANONICAL_CONTRACT_VERSION = "ANNOUNCEMENT_CANONICAL_V1";
    public static final String RULE_VERSION = "1.4.0-stage-2g-announcement-risk-v1";
    public static final String CONTEXT_PROFILE = "AGENT_CONTEXT_2G_V1";
    public static final String CONTEXT_SCHEMA_VERSION = "SECURITY_EVENTS_CONTEXT_V1";
    public static final String PRODUCER = "AgentSecurityEventsContextService";
    public static final String PRODUCER_VERSION = "JAVA_SECURITY_EVENTS_CONTEXT_V1";
    public static final String MARKET_TIMEZONE = "Asia/Shanghai";
    public static final ZoneId MARKET_ZONE = ZoneId.of(MARKET_TIMEZONE);
    public static final int LOOKBACK_DAYS = 180;
    public static final int MAX_CAPTURE_AGE_HOURS = 24;

    public static final String NO_COMPLETE_CAPTURE = "ANNOUNCEMENT_NO_COMPLETE_CAPTURE";
    public static final String CAPTURE_STALE = "ANNOUNCEMENT_CAPTURE_STALE";
    public static final String CAPTURE_RANGE_INCOMPLETE =
            "ANNOUNCEMENT_CAPTURE_RANGE_INCOMPLETE";
    public static final String SOURCE_UNVERIFIABLE =
            "ANNOUNCEMENT_SOURCE_UNVERIFIABLE";
    public static final String CONTEXT_INVALID = "ANNOUNCEMENT_CONTEXT_INVALID";
    public static final String FUTURE_REQUEST_DATE =
            "ANNOUNCEMENT_FUTURE_REQUEST_DATE";
    public static final String INPUT_INVALID = "ANNOUNCEMENT_RISK_INPUT_INVALID";

    public static final Set<String> UNAVAILABLE_REASON_CODES = Set.of(
            NO_COMPLETE_CAPTURE,
            CAPTURE_STALE,
            CAPTURE_RANGE_INCOMPLETE,
            SOURCE_UNVERIFIABLE,
            CONTEXT_INVALID,
            FUTURE_REQUEST_DATE
    );

    public static final List<String> LIMITATIONS = List.of(
            "RESEARCH_SOURCE_ONLY",
            "DATE_ONLY_PUBLICATION_PRECISION",
            "NO_REVISION_RELATIONSHIP_GUARANTEE",
            "NO_HISTORICAL_COMPLETENESS_GUARANTEE",
            "NO_FORMAL_OR_PIT_QUALIFICATION",
            "NO_PDF_SEMANTIC_PARSING",
            "RESEARCH_ONLY"
    );

    public static final List<String> FINDING_CODES = List.of(
            "ANNOUNCEMENT_SOURCE_COVERAGE_ASSESSED",
            "ANNOUNCEMENT_REGULATORY_DELISTING_ASSESSED",
            "ANNOUNCEMENT_FINANCIAL_LITIGATION_ASSESSED",
            "ANNOUNCEMENT_OWNERSHIP_OPERATION_ASSESSED",
            "ANNOUNCEMENT_RESEARCH_LIMITATIONS_ASSESSED"
    );

    private AnnouncementContracts() {
    }
}
