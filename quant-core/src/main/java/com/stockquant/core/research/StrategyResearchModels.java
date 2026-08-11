package com.stockquant.core.research;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable contracts shared by STRATEGY_ENGINE_V1 and BACKTEST_ENGINE_V1. */
public final class StrategyResearchModels {
    public static final String DATASET_CONTRACT = "STRATEGY_RESEARCH_DATASET_V1";
    public static final String STRATEGY_ENGINE_VERSION = "STRATEGY_ENGINE_V1";
    public static final String BACKTEST_ENGINE_VERSION = "BACKTEST_ENGINE_V1";
    public static final String BACKTEST_ENGINE_PROFILE =
            "M2_LONG_ONLY_PORTFOLIO_RESEARCH_V1";
    public static final String API_VERSION = "STRATEGY_RESEARCH_API_V1";
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 0);

    private StrategyResearchModels() {
    }

    public enum KnowledgeMode {
        SYSTEM_KNOWLEDGE_RESEARCH,
        PROVIDER_PIT_VERIFIED
    }

    public enum Side {
        BUY,
        SELL
    }

    public enum TargetAction {
        REBALANCE,
        HOLD
    }

    public enum RejectionReason {
        EXCHANGE_CLOSED,
        NO_TRADABLE_PRICE,
        SUSPENDED,
        INSUFFICIENT_CASH,
        BELOW_BOARD_LOT,
        T_PLUS_ONE_RESTRICTED,
        POSITION_LIMIT,
        INVALID_TARGET
    }

    public record Security(String symbol, String exchange)
            implements Comparable<Security> {
        public Security {
            symbol = required(symbol, "symbol");
            exchange = required(exchange, "exchange");
            boolean mainBoardIdentity = "SSE".equals(exchange)
                    && symbol.matches("60[0135][0-9]{3}")
                    || "SZSE".equals(exchange)
                    && symbol.matches("00[0123][0-9]{3}");
            if (!mainBoardIdentity) {
                throw invalid("M2_SECURITY_INVALID");
            }
        }

        @Override
        public int compareTo(Security other) {
            int byExchange = exchange.compareTo(other.exchange);
            return byExchange != 0 ? byExchange : symbol.compareTo(other.symbol);
        }

        public String canonicalCode() {
            return symbol + ":" + exchange;
        }
    }

    public record TradingSession(LocalDate tradeDate, Set<String> openExchanges) {
        public TradingSession {
            Objects.requireNonNull(tradeDate, "tradeDate");
            TreeSet<String> normalized = new TreeSet<>(Objects.requireNonNull(
                    openExchanges, "openExchanges"));
            if (normalized.stream().anyMatch(value ->
                    !Set.of("SSE", "SZSE").contains(value))) {
                throw invalid("M2_TRADING_SESSION_EXCHANGE_INVALID");
            }
            if (!normalized.isEmpty()
                    && tradeDate.getDayOfWeek().getValue() > 5) {
                throw invalid("M2_TRADING_SESSION_WEEKEND_OPEN");
            }
            openExchanges = Collections.unmodifiableSet(normalized);
        }

        public boolean isOpen(String exchange) {
            return openExchanges.contains(exchange);
        }

        public boolean anyOpen() {
            return !openExchanges.isEmpty();
        }
    }

    public record DailyBar(
            Security security,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume,
            boolean tradable,
            Instant marketCloseAvailableAt,
            Instant sourceKnownAt
    ) {
        public DailyBar {
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(tradeDate, "tradeDate");
            requirePositive(open, "open");
            requirePositive(high, "high");
            requirePositive(low, "low");
            requirePositive(close, "close");
            Objects.requireNonNull(marketCloseAvailableAt,
                    "marketCloseAvailableAt");
            Objects.requireNonNull(sourceKnownAt, "sourceKnownAt");
            if (volume < 0
                    || high.compareTo(open) < 0
                    || high.compareTo(low) < 0
                    || high.compareTo(close) < 0
                    || low.compareTo(open) > 0
                    || low.compareTo(high) > 0
                    || low.compareTo(close) > 0
                    || !marketCloseAvailableAt.equals(closeInstant(tradeDate))
                    || sourceKnownAt.isBefore(marketCloseAvailableAt)) {
                throw invalid("M2_DAILY_BAR_INVALID");
            }
        }
    }

    public record ResearchDataset(
            String contractVersion,
            String datasetVersion,
            KnowledgeMode knowledgeMode,
            Instant knowledgeCutoff,
            List<TradingSession> sessions,
            List<DailyBar> bars
    ) {
        public ResearchDataset {
            contractVersion = required(contractVersion, "contractVersion");
            datasetVersion = required(datasetVersion, "datasetVersion");
            Objects.requireNonNull(knowledgeMode, "knowledgeMode");
            Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
            sessions = normalizeSessions(sessions);
            bars = normalizeBars(bars);
            if (!DATASET_CONTRACT.equals(contractVersion)
                    || sessions.isEmpty() || bars.isEmpty()) {
                throw invalid("M2_RESEARCH_DATASET_INVALID");
            }
            Map<LocalDate, TradingSession> sessionByDate = new LinkedHashMap<>();
            for (TradingSession session : sessions) {
                if (sessionByDate.put(session.tradeDate(), session) != null) {
                    throw invalid("M2_TRADING_SESSION_DUPLICATE");
                }
            }
            SecurityDate previous = null;
            for (DailyBar bar : bars) {
                TradingSession session = sessionByDate.get(bar.tradeDate());
                SecurityDate current = new SecurityDate(
                        bar.security(), bar.tradeDate());
                if (session == null || !session.isOpen(bar.security().exchange())
                        || bar.sourceKnownAt().isAfter(knowledgeCutoff)
                        || knowledgeMode == KnowledgeMode.PROVIDER_PIT_VERIFIED
                        && bar.sourceKnownAt().isAfter(
                        bar.marketCloseAvailableAt())
                        || current.equals(previous)) {
                    throw invalid("M2_RESEARCH_DATASET_TEMPORAL_INVALID");
                }
                previous = current;
            }
        }

        public List<Security> securities() {
            return bars.stream().map(DailyBar::security).distinct().sorted().toList();
        }

        public Map<Security, List<DailyBar>> barsBySecurity() {
            Map<Security, List<DailyBar>> grouped = new TreeMap<>();
            for (DailyBar bar : bars) {
                grouped.computeIfAbsent(bar.security(), ignored ->
                        new ArrayList<>()).add(bar);
            }
            Map<Security, List<DailyBar>> result = new LinkedHashMap<>();
            grouped.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Collections.unmodifiableMap(result);
        }

        public LocalDate firstSessionDate() {
            return sessions.get(0).tradeDate();
        }

        public LocalDate lastSessionDate() {
            return sessions.get(sessions.size() - 1).tradeDate();
        }
    }

    public record StrategySpec(String strategyCode, Map<String, String> parameters) {
        public StrategySpec {
            strategyCode = required(strategyCode, "strategyCode");
            TreeMap<String, String> normalized = new TreeMap<>();
            Objects.requireNonNull(parameters, "parameters").forEach((key, value) ->
                    normalized.put(required(key, "parameterName"),
                            required(value, "parameterValue")));
            parameters = Collections.unmodifiableSortedMap(normalized);
        }

        public static StrategySpec of(String strategyCode) {
            return new StrategySpec(strategyCode, Map.of());
        }
    }

    public record StrategyDefinition(
            String engineVersion,
            String strategyCode,
            String strategyVersion,
            int minimumHistory,
            Map<String, String> parameters
    ) {
        public StrategyDefinition {
            engineVersion = required(engineVersion, "engineVersion");
            strategyCode = required(strategyCode, "strategyCode");
            strategyVersion = required(strategyVersion, "strategyVersion");
            if (!STRATEGY_ENGINE_VERSION.equals(engineVersion)
                    || minimumHistory < 1) {
                throw invalid("M2_STRATEGY_DEFINITION_INVALID");
            }
            parameters = Collections.unmodifiableSortedMap(
                    new TreeMap<>(Objects.requireNonNull(parameters,
                            "parameters")));
        }
    }

    public record StrategyContext(
            LocalDate signalDate,
            Instant signalAt,
            int sessionIndex,
            Map<Security, List<DailyBar>> history,
            Map<Security, BigDecimal> currentWeights,
            Map<Security, Integer> currentPositions
    ) {
        public StrategyContext {
            Objects.requireNonNull(signalDate, "signalDate");
            Objects.requireNonNull(signalAt, "signalAt");
            if (!signalAt.equals(closeInstant(signalDate)) || sessionIndex < 0) {
                throw invalid("M2_STRATEGY_CONTEXT_TIME_INVALID");
            }
            history = immutableHistory(history, signalDate, signalAt);
            currentWeights = immutableDecimalMap(currentWeights);
            currentPositions = immutableIntegerMap(currentPositions);
        }
    }

    public record TargetPortfolio(
            LocalDate signalDate,
            Map<Security, BigDecimal> targetWeights,
            TargetAction action,
            String reason
    ) {
        public TargetPortfolio(
                LocalDate signalDate,
                Map<Security, BigDecimal> targetWeights,
                String reason
        ) {
            this(signalDate, targetWeights, TargetAction.REBALANCE, reason);
        }

        public TargetPortfolio {
            Objects.requireNonNull(signalDate, "signalDate");
            targetWeights = immutableDecimalMap(targetWeights);
            Objects.requireNonNull(action, "action");
            reason = required(reason, "reason");
            if (targetWeights.values().stream().anyMatch(value ->
                    value.signum() < 0)) {
                throw invalid("M2_SHORT_TARGET_FORBIDDEN");
            }
        }
    }

    public record BacktestConfig(
            BigDecimal initialCash,
            BigDecimal commissionRate,
            BigDecimal minimumCommission,
            BigDecimal stampDutyRate,
            int slippageBps,
            int boardLotSize,
            BigDecimal maxGrossExposure,
            BigDecimal maxSinglePositionWeight,
            int maxPositions,
            BigDecimal maxPortfolioDrawdown,
            BigDecimal annualRiskFreeRate,
            boolean tPlusOne
    ) {
        public BacktestConfig {
            requirePositive(initialCash, "initialCash");
            requireRatio(commissionRate, "commissionRate", true);
            requireNonNegative(minimumCommission, "minimumCommission");
            requireRatio(stampDutyRate, "stampDutyRate", true);
            requireRatio(maxGrossExposure, "maxGrossExposure", false);
            requireRatio(maxSinglePositionWeight,
                    "maxSinglePositionWeight", false);
            requireRatio(maxPortfolioDrawdown,
                    "maxPortfolioDrawdown", false);
            requireRatio(annualRiskFreeRate, "annualRiskFreeRate", true);
            if (slippageBps < 0 || slippageBps > 10_000
                    || boardLotSize <= 0 || maxPositions <= 0
                    || maxSinglePositionWeight.compareTo(maxGrossExposure) > 0) {
                throw invalid("M2_BACKTEST_CONFIG_INVALID");
            }
        }

        public static BacktestConfig standard() {
            return new BacktestConfig(
                    new BigDecimal("1000000"),
                    new BigDecimal("0.0003"),
                    new BigDecimal("5"),
                    new BigDecimal("0.0005"),
                    5,
                    100,
                    new BigDecimal("0.95"),
                    new BigDecimal("0.40"),
                    5,
                    new BigDecimal("0.30"),
                    new BigDecimal("0.02"),
                    true);
        }
    }

    public record BacktestRequest(
            ResearchDataset dataset,
            StrategySpec strategy,
            BacktestConfig config,
            LocalDate executionStart,
            LocalDate executionEnd
    ) {
        public BacktestRequest {
            Objects.requireNonNull(dataset, "dataset");
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(executionStart, "executionStart");
            Objects.requireNonNull(executionEnd, "executionEnd");
            if (executionEnd.isBefore(executionStart)
                    || executionStart.isBefore(dataset.firstSessionDate())
                    || executionEnd.isAfter(dataset.lastSessionDate())) {
                throw invalid("M2_BACKTEST_WINDOW_INVALID");
            }
        }
    }

    public record PositionSnapshot(
            Security security,
            int quantity,
            BigDecimal averageCost,
            BigDecimal marketPrice,
            BigDecimal marketValue,
            BigDecimal unrealizedPnl,
            BigDecimal portfolioWeight
    ) {
    }

    public record EquityPoint(
            LocalDate tradeDate,
            BigDecimal cash,
            BigDecimal marketValue,
            BigDecimal equity,
            BigDecimal dailyReturn,
            BigDecimal drawdown,
            int positionCount
    ) {
    }

    public record TradeFill(
            long sequence,
            Security security,
            Side side,
            LocalDate signalDate,
            Instant signalAt,
            LocalDate executionDate,
            Instant executionAt,
            BigDecimal referencePrice,
            BigDecimal executionPrice,
            int quantity,
            BigDecimal grossAmount,
            BigDecimal commission,
            BigDecimal stampDuty,
            BigDecimal slippageCost,
            BigDecimal realizedPnl,
            BigDecimal cashAfter,
            int positionAfter,
            String reason
    ) {
    }

    public record RejectedOrder(
            long sequence,
            Security security,
            Side side,
            LocalDate signalDate,
            LocalDate executionDate,
            RejectionReason reason
    ) {
    }

    public record PerformanceMetrics(
            BigDecimal initialEquity,
            BigDecimal finalEquity,
            BigDecimal pnl,
            BigDecimal totalReturn,
            BigDecimal cagr,
            BigDecimal annualizedReturn,
            BigDecimal annualizedVolatility,
            BigDecimal sharpeRatio,
            BigDecimal maxDrawdown,
            BigDecimal winRate,
            BigDecimal turnover,
            int tradingSessions,
            int fillCount,
            int roundTripCount
    ) {
    }

    public record AccountingSummary(
            BigDecimal grossBuys,
            BigDecimal grossSells,
            BigDecimal totalCommission,
            BigDecimal totalStampDuty,
            BigDecimal totalSlippageCost,
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal endingCash,
            BigDecimal endingMarketValue,
            BigDecimal endingEquity,
            BigDecimal cashConservationDelta,
            BigDecimal pnlReconciliationDelta,
            boolean invariantPassed
    ) {
    }

    public record BacktestResult(
            String engineVersion,
            String engineProfile,
            StrategyDefinition strategy,
            String datasetVersion,
            LocalDate executionStart,
            LocalDate executionEnd,
            String deterministicFingerprint,
            PerformanceMetrics metrics,
            AccountingSummary accounting,
            List<PositionSnapshot> endingPositions,
            List<EquityPoint> equityCurve,
            List<TradeFill> tradeLedger,
            List<RejectedOrder> rejectedOrders,
            boolean riskHalted,
            boolean lookAheadGuardPassed,
            boolean deterministic
    ) {
        public BacktestResult {
            engineVersion = required(engineVersion, "engineVersion");
            engineProfile = required(engineProfile, "engineProfile");
            Objects.requireNonNull(strategy, "strategy");
            datasetVersion = required(datasetVersion, "datasetVersion");
            Objects.requireNonNull(executionStart, "executionStart");
            Objects.requireNonNull(executionEnd, "executionEnd");
            deterministicFingerprint = required(deterministicFingerprint,
                    "deterministicFingerprint");
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(accounting, "accounting");
            endingPositions = List.copyOf(endingPositions);
            equityCurve = List.copyOf(equityCurve);
            tradeLedger = List.copyOf(tradeLedger);
            rejectedOrders = List.copyOf(rejectedOrders);
            if (!BACKTEST_ENGINE_VERSION.equals(engineVersion)
                    || !BACKTEST_ENGINE_PROFILE.equals(engineProfile)
                    || !accounting.invariantPassed()
                    || !lookAheadGuardPassed || !deterministic) {
                throw invalid("M2_BACKTEST_RESULT_INVALID");
            }
        }
    }

    public record BenchmarkResult(
            Security security,
            BacktestResult result,
            BigDecimal excessReturn
    ) {
        public BenchmarkResult {
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(excessReturn, "excessReturn");
        }
    }

    public record ResearchResult(
            String apiVersion,
            BacktestResult strategyResult,
            BenchmarkResult benchmark
    ) {
        public ResearchResult {
            if (!API_VERSION.equals(apiVersion)) {
                throw invalid("M2_RESEARCH_API_VERSION_INVALID");
            }
            Objects.requireNonNull(strategyResult, "strategyResult");
            Objects.requireNonNull(benchmark, "benchmark");
        }
    }

    public record StrategyComparison(
            String strategyCode,
            String strategyVersion,
            String fingerprint,
            BigDecimal totalReturn,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            BigDecimal turnover,
            BigDecimal excessReturn
    ) {
    }

    public record ComparisonResult(
            String apiVersion,
            List<StrategyComparison> strategies
    ) {
        public ComparisonResult {
            if (!API_VERSION.equals(apiVersion)) {
                throw invalid("M2_COMPARISON_VERSION_INVALID");
            }
            strategies = List.copyOf(strategies);
            if (strategies.isEmpty()) {
                throw invalid("M2_COMPARISON_EMPTY");
            }
        }
    }

    public record TemporalSplit(
            LocalDate trainStart,
            LocalDate trainEnd,
            LocalDate testStart,
            LocalDate testEnd
    ) {
        public TemporalSplit {
            Objects.requireNonNull(trainStart, "trainStart");
            Objects.requireNonNull(trainEnd, "trainEnd");
            Objects.requireNonNull(testStart, "testStart");
            Objects.requireNonNull(testEnd, "testEnd");
            if (trainEnd.isBefore(trainStart) || !trainEnd.isBefore(testStart)
                    || testEnd.isBefore(testStart)) {
                throw invalid("M2_TEMPORAL_SPLIT_OVERLAP");
            }
        }
    }

    public record TrainTestResult(
            String apiVersion,
            TemporalSplit split,
            ResearchResult train,
            ResearchResult test,
            boolean strictlyIsolated
    ) {
        public TrainTestResult {
            if (!API_VERSION.equals(apiVersion) || !strictlyIsolated) {
                throw invalid("M2_TRAIN_TEST_INVALID");
            }
            Objects.requireNonNull(split, "split");
            Objects.requireNonNull(train, "train");
            Objects.requireNonNull(test, "test");
        }
    }

    public record WalkForwardPlan(
            int trainSessions,
            int testSessions,
            int stepSessions,
            int maximumFolds
    ) {
        public WalkForwardPlan {
            if (trainSessions < 2 || testSessions < 1 || stepSessions < 1
                    || maximumFolds < 1) {
                throw invalid("M2_WALK_FORWARD_PLAN_INVALID");
            }
        }
    }

    public record WalkForwardFold(
            int fold,
            TemporalSplit split,
            TrainTestResult result
    ) {
    }

    public record WalkForwardResult(
            String apiVersion,
            WalkForwardPlan plan,
            List<WalkForwardFold> folds,
            boolean outOfSampleOnly
    ) {
        public WalkForwardResult {
            if (!API_VERSION.equals(apiVersion) || !outOfSampleOnly) {
                throw invalid("M2_WALK_FORWARD_RESULT_INVALID");
            }
            Objects.requireNonNull(plan, "plan");
            folds = List.copyOf(folds);
            if (folds.isEmpty()) {
                throw invalid("M2_WALK_FORWARD_EMPTY");
            }
        }
    }

    public static Instant closeInstant(LocalDate date) {
        return Objects.requireNonNull(date, "date")
                .atTime(MARKET_CLOSE).atZone(MARKET_ZONE).toInstant();
    }

    public static Instant openInstant(LocalDate date) {
        return Objects.requireNonNull(date, "date")
                .atTime(9, 30).atZone(MARKET_ZONE).toInstant();
    }

    private static List<TradingSession> normalizeSessions(
            List<TradingSession> input
    ) {
        List<TradingSession> result = new ArrayList<>(
                Objects.requireNonNull(input, "sessions"));
        result.sort(Comparator.comparing(TradingSession::tradeDate));
        return List.copyOf(result);
    }

    private static List<DailyBar> normalizeBars(List<DailyBar> input) {
        List<DailyBar> result = new ArrayList<>(
                Objects.requireNonNull(input, "bars"));
        result.sort(Comparator.comparing(DailyBar::security)
                .thenComparing(DailyBar::tradeDate));
        return List.copyOf(result);
    }

    private static Map<Security, List<DailyBar>> immutableHistory(
            Map<Security, List<DailyBar>> input,
            LocalDate signalDate,
            Instant signalAt
    ) {
        Map<Security, List<DailyBar>> result = new LinkedHashMap<>();
        new TreeMap<>(Objects.requireNonNull(input, "history"))
                .forEach((security, values) -> {
                    List<DailyBar> copy = List.copyOf(values);
                    LocalDate previous = null;
                    for (DailyBar bar : copy) {
                        if (!security.equals(bar.security())
                                || bar.tradeDate().isAfter(signalDate)
                                || bar.marketCloseAvailableAt().isAfter(signalAt)
                                || previous != null
                                && !bar.tradeDate().isAfter(previous)) {
                            throw invalid("M2_LOOK_AHEAD_DATA_REJECTED");
                        }
                        previous = bar.tradeDate();
                    }
                    result.put(security, copy);
                });
        return Collections.unmodifiableMap(result);
    }

    private static Map<Security, BigDecimal> immutableDecimalMap(
            Map<Security, BigDecimal> input
    ) {
        SortedMap<Security, BigDecimal> result = new TreeMap<>();
        Objects.requireNonNull(input, "decimalMap").forEach((key, value) -> {
            Objects.requireNonNull(key, "security");
            Objects.requireNonNull(value, "value");
            result.put(key, value.stripTrailingZeros());
        });
        return Collections.unmodifiableSortedMap(result);
    }

    private static Map<Security, Integer> immutableIntegerMap(
            Map<Security, Integer> input
    ) {
        SortedMap<Security, Integer> result = new TreeMap<>();
        Objects.requireNonNull(input, "integerMap").forEach((key, value) -> {
            Objects.requireNonNull(key, "security");
            Objects.requireNonNull(value, "value");
            if (value < 0) {
                throw invalid("M2_POSITION_QUANTITY_INVALID");
            }
            result.put(key, value);
        });
        return Collections.unmodifiableSortedMap(result);
    }

    private record SecurityDate(Security security, LocalDate date)
            implements Comparable<SecurityDate> {
        @Override
        public int compareTo(SecurityDate other) {
            int bySecurity = security.compareTo(other.security);
            return bySecurity != 0 ? bySecurity : date.compareTo(other.date);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid("M2_REQUIRED_TEXT_INVALID:" + name);
        }
        return value;
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw invalid("M2_POSITIVE_VALUE_REQUIRED:" + name);
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) {
            throw invalid("M2_NON_NEGATIVE_VALUE_REQUIRED:" + name);
        }
    }

    private static void requireRatio(
            BigDecimal value,
            String name,
            boolean allowZero
    ) {
        if (value == null || (allowZero ? value.signum() < 0
                : value.signum() <= 0) || value.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("M2_RATIO_INVALID:" + name);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
