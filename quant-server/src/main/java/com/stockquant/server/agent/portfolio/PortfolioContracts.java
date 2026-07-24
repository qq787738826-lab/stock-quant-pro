package com.stockquant.server.agent.portfolio;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public final class PortfolioContracts {

    public static final long ACCOUNT_ID = 1L;
    public static final String RULE_VERSION = "1.4.0-stage-2h-position-risk-v1";
    public static final String CONTEXT_PROFILE = "AGENT_CONTEXT_2H_V1";
    public static final String CONTEXT_SCHEMA_VERSION = "PORTFOLIO_CONTEXT_V1";
    public static final String PRODUCER = "AgentPortfolioContextService";
    public static final String PRODUCER_VERSION = "JAVA_PORTFOLIO_CONTEXT_V1";
    public static final String MARKET_TIMEZONE = "Asia/Shanghai";
    public static final ZoneId MARKET_ZONE = ZoneId.of(MARKET_TIMEZONE);
    public static final int MAX_PRICE_AGE_DAYS = 7;

    public static final String NOT_CURRENT_DATE = "PORTFOLIO_CONTEXT_NOT_CURRENT_DATE";
    public static final String ACCOUNT_INVALID = "PORTFOLIO_ACCOUNT_INVALID";
    public static final String SETTINGS_INVALID = "PORTFOLIO_SETTINGS_INVALID";
    public static final String POSITION_INVALID = "PORTFOLIO_POSITION_INVALID";
    public static final String PRICE_MISSING = "PORTFOLIO_PRICE_MISSING";
    public static final String PRICE_STALE = "PORTFOLIO_PRICE_STALE";
    public static final String ORDER_INVALID = "PORTFOLIO_ORDER_INVALID";
    public static final String INPUT_INVALID = "POSITION_RISK_INPUT_INVALID";

    public static final Set<String> UNAVAILABLE_REASON_CODES = Set.of(
            NOT_CURRENT_DATE,
            ACCOUNT_INVALID,
            SETTINGS_INVALID,
            POSITION_INVALID,
            PRICE_MISSING,
            PRICE_STALE,
            ORDER_INVALID
    );

    public static final List<String> FINDING_CODES = List.of(
            "POSITION_RISK_ACCOUNT_LOSS_ASSESSED",
            "POSITION_RISK_CONCENTRATION_ASSESSED",
            "POSITION_RISK_PENDING_EXPOSURE_ASSESSED",
            "POSITION_RISK_EXIT_THRESHOLDS_ASSESSED",
            "POSITION_RISK_CONTEXT_COMPLETENESS_ASSESSED"
    );

    public static final List<String> ACCOUNT_VETO_CODES = List.of(
            "POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
            "POSITION_RISK_DAILY_LOSS_LIMIT",
            "POSITION_RISK_MAX_POSITIONS_EXCEEDED"
    );

    private PortfolioContracts() {
    }
}
