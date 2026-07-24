package com.stockquant.server.agent.backtest;

import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.BacktestModels;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

public final class BacktestContracts {

    public static final String RULE_VERSION = "1.4.0-stage-2f-strategy-backtest-v1";
    public static final String CONTEXT_PROFILE = "AGENT_CONTEXT_2F_V1";
    public static final String CONTEXT_SCHEMA_VERSION = "BACKTEST_CONTEXT_V1";
    public static final String PRODUCER = "AgentBacktestContextService";
    public static final String PRODUCER_VERSION = "JAVA_BACKTEST_CONTEXT_V1";
    public static final String PIT_MODEL_VERSION = "PIT_DAILY_BAR_OBSERVATION_V1";
    public static final String CANONICAL_CONTRACT_VERSION = "BACKTEST_CANONICAL_V1";
    public static final String STRATEGY_CODE = BacktestEngine.STRATEGY_CODE;
    public static final String STRATEGY_VERSION = BacktestEngine.STRATEGY_CODE;
    public static final String ENGINE_VERSION = BacktestEngine.ENGINE_VERSION;
    public static final String PARAMETER_SCHEMA_VERSION = "BACKTEST_PARAMS_V1";
    public static final String ADJUST_TYPE = "QFQ";
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime DAILY_BAR_EARLIEST_KNOWN_TIME =
            LocalTime.of(15, 0);
    public static final int MAXIMUM_BARS = 500;
    public static final int MINIMUM_CONTEXT_BARS = 120;
    public static final String SPLIT_ALGORITHM =
            "CHRONOLOGICAL_THIRDS_REMAINDER_TO_EARLY_THEN_MIDDLE_V1";

    public static final String NO_TRUSTED_PIT_DAILY_BARS =
            "BACKTEST_NO_TRUSTED_PIT_DAILY_BARS";
    public static final String KNOWLEDGE_TIME_UNVERIFIABLE =
            "BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE";
    public static final String SOURCE_REVISION_UNVERIFIABLE =
            "BACKTEST_SOURCE_REVISION_UNVERIFIABLE";
    public static final String CUTOFF_POLLUTION_UNRESOLVED =
            "BACKTEST_CUTOFF_POLLUTION_UNRESOLVED";
    public static final String SAMPLE_INSUFFICIENT = "BACKTEST_SAMPLE_INSUFFICIENT";
    public static final String DAILY_BAR_INVALID = "BACKTEST_DAILY_BAR_INVALID";
    public static final String STRATEGY_VERSION_UNVERIFIABLE =
            "BACKTEST_STRATEGY_VERSION_UNVERIFIABLE";
    public static final String PARAMS_INVALID = "BACKTEST_PARAMS_INVALID";
    public static final String HASH_MISMATCH = "BACKTEST_HASH_MISMATCH";
    public static final String REPLAY_MISMATCH = "BACKTEST_REPLAY_MISMATCH";
    public static final String FUTURE_REQUEST_DATE = "BACKTEST_FUTURE_REQUEST_DATE";
    public static final String DECISION_TIME_NOT_REACHED =
            "BACKTEST_DECISION_TIME_NOT_REACHED";
    public static final String STRATEGY_INPUT_INVALID =
            "STRATEGY_BACKTEST_INPUT_INVALID";
    public static final String STRATEGY_SAMPLE_INSUFFICIENT =
            "STRATEGY_BACKTEST_SAMPLE_INSUFFICIENT";

    public static final List<String> UNAVAILABLE_REASON_CODES = List.of(
            NO_TRUSTED_PIT_DAILY_BARS,
            KNOWLEDGE_TIME_UNVERIFIABLE,
            SOURCE_REVISION_UNVERIFIABLE,
            CUTOFF_POLLUTION_UNRESOLVED,
            SAMPLE_INSUFFICIENT,
            DAILY_BAR_INVALID,
            STRATEGY_VERSION_UNVERIFIABLE,
            PARAMS_INVALID,
            HASH_MISMATCH,
            REPLAY_MISMATCH,
            FUTURE_REQUEST_DATE,
            DECISION_TIME_NOT_REACHED
    );

    public static final Set<String> STRATEGY_ERROR_CODES = Set.of(
            STRATEGY_INPUT_INVALID,
            STRATEGY_SAMPLE_INSUFFICIENT
    );

    private static final BacktestModels.Request PARAMETERS = new BacktestModels.Request(
            new BigDecimal("100000"),
            10,
            new BigDecimal("0.05"),
            new BigDecimal("0.08"),
            new BigDecimal("0.04"),
            new BigDecimal("0.0003"),
            new BigDecimal("0.0005")
    );

    private BacktestContracts() {
    }

    public static BacktestModels.Request parameters() {
        return PARAMETERS;
    }

    public static boolean isSupportedDailyBarTradeDate(LocalDate tradeDate) {
        return tradeDate != null && tradeDate.getDayOfWeek().getValue() <= 5;
    }

    public static Instant earliestDailyBarKnownAt(LocalDate tradeDate) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        return tradeDate.atTime(DAILY_BAR_EARLIEST_KNOWN_TIME)
                .atZone(MARKET_ZONE)
                .toInstant();
    }

    public static boolean validDailyBarKnowledgeTimes(
            LocalDate tradeDate,
            Instant firstObservedAt,
            Instant knownAt,
            Instant recordedAt
    ) {
        if (!isSupportedDailyBarTradeDate(tradeDate)
                || firstObservedAt == null
                || knownAt == null
                || recordedAt == null) {
            return false;
        }
        Instant earliestKnownAt = earliestDailyBarKnownAt(tradeDate);
        return !firstObservedAt.isBefore(earliestKnownAt)
                && !knownAt.isBefore(earliestKnownAt)
                && !firstObservedAt.isAfter(knownAt)
                && !knownAt.isAfter(recordedAt);
    }
}
