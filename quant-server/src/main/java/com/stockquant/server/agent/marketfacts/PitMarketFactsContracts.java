package com.stockquant.server.agent.marketfacts;

import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/** Frozen identifiers and safety codes for provider-neutral PIT market facts V2. */
public final class PitMarketFactsContracts {

    public static final String RULE_VERSION =
            "1.4.0-stage-3ar3b0-agent-team-pit-v2";
    public static final String CONTEXT_PROFILE = "AGENT_CONTEXT_3AR3B0_V2";
    public static final String BACKTEST_CONTEXT_VERSION = "BACKTEST_CONTEXT_V2";
    public static final String BACKTEST_CANONICAL_VERSION = "BACKTEST_CANONICAL_V2";
    public static final String MARKET_FACTS_VERSION = "PIT_MARKET_FACTS_V2";
    public static final String MARKET_FACTS_CANONICAL_VERSION =
            "PIT_MARKET_FACTS_CANONICAL_V2";
    public static final String PROVIDER_CONTRACT_VERSION =
            "MARKET_FACT_PROVIDER_CONTRACT_V1";
    public static final String RAW_DAILY_BAR_CONTRACT =
            "RAW_DAILY_BAR_OBSERVATION_V2";
    public static final String ADJUSTMENT_FACTOR_CONTRACT =
            "ADJUSTMENT_FACTOR_OBSERVATION_V1";
    public static final String TRADING_CALENDAR_CONTRACT =
            "TRADING_CALENDAR_OBSERVATION_V1";
    public static final String CORPORATE_ACTION_CONTRACT =
            "CORPORATE_ACTION_OBSERVATION_V1";
    public static final String QFQ_ENGINE_VERSION = "QFQ_AS_OF_ENGINE_V1";
    public static final String FACTOR_COVERAGE_MODE = "DAILY_EXACT";
    public static final String FACTOR_TYPE = "QFQ";
    public static final String PRODUCER = "AgentBacktestContextV2Service";
    public static final String PRODUCER_VERSION = "JAVA_BACKTEST_CONTEXT_V2";
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");

    public static final String CALENDAR_UNAVAILABLE = "PIT_CALENDAR_UNAVAILABLE";
    public static final String RAW_BAR_UNAVAILABLE = "PIT_RAW_BAR_UNAVAILABLE";
    public static final String FACTOR_UNAVAILABLE = "PIT_FACTOR_UNAVAILABLE";
    public static final String CORPORATE_ACTION_LINEAGE_UNAVAILABLE =
            "PIT_CORPORATE_ACTION_LINEAGE_UNAVAILABLE";
    public static final String CROSS_PROVIDER_FORBIDDEN =
            "PIT_CROSS_PROVIDER_FORBIDDEN";
    public static final String FACT_INVALID = "PIT_MARKET_FACT_INVALID";
    public static final String FUTURE_REQUEST_DATE = "PIT_FUTURE_REQUEST_DATE";
    public static final String DECISION_TIME_NOT_REACHED =
            "PIT_DECISION_TIME_NOT_REACHED";
    public static final String TEST_DEMO_PROFILE_DISABLED =
            "PIT_TEST_DEMO_PROFILE_DISABLED";
    public static final String SAMPLE_INSUFFICIENT = "BACKTEST_SAMPLE_INSUFFICIENT";
    public static final String REPLAY_MISMATCH = "BACKTEST_REPLAY_MISMATCH";
    public static final String IFIND_GATE_NOT_PASSED =
            "IFIND_TRIAL_GATE_NOT_PASSED";

    public static final List<String> UNAVAILABLE_REASON_CODES = List.of(
            CALENDAR_UNAVAILABLE,
            RAW_BAR_UNAVAILABLE,
            FACTOR_UNAVAILABLE,
            CORPORATE_ACTION_LINEAGE_UNAVAILABLE,
            CROSS_PROVIDER_FORBIDDEN,
            FACT_INVALID,
            FUTURE_REQUEST_DATE,
            DECISION_TIME_NOT_REACHED,
            TEST_DEMO_PROFILE_DISABLED,
            SAMPLE_INSUFFICIENT,
            REPLAY_MISMATCH
    );

    public static final Set<String> FACT_CONTRACTS = Set.of(
            RAW_DAILY_BAR_CONTRACT,
            ADJUSTMENT_FACTOR_CONTRACT,
            TRADING_CALENDAR_CONTRACT,
            CORPORATE_ACTION_CONTRACT
    );

    private PitMarketFactsContracts() {
    }
}
