package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyContext;
import com.stockquant.core.research.StrategyResearchModels.StrategyDefinition;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TargetPortfolio;
import com.stockquant.core.research.StrategyResearchModels.TargetAction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Fixed whitelist registry for the representative M2 strategy set. */
public final class StrategyRegistry {
    public static final String BUY_AND_HOLD = "BUY_AND_HOLD_V1";
    public static final String MOVING_AVERAGE_MOMENTUM =
            "MOVING_AVERAGE_MOMENTUM_V1";
    public static final String MEAN_REVERSION = "MEAN_REVERSION_V1";
    public static final String CROSS_SECTIONAL_MOMENTUM =
            "CROSS_SECTIONAL_MOMENTUM_V1";

    public Strategy create(StrategySpec spec) {
        Objects.requireNonNull(spec, "spec");
        return switch (spec.strategyCode()) {
            case BUY_AND_HOLD -> buyAndHold(spec.parameters());
            case MOVING_AVERAGE_MOMENTUM -> movingAverage(spec.parameters());
            case MEAN_REVERSION -> meanReversion(spec.parameters());
            case CROSS_SECTIONAL_MOMENTUM -> crossSectional(spec.parameters());
            default -> throw invalid("M2_STRATEGY_NOT_REGISTERED");
        };
    }

    public List<StrategyDefinition> catalog() {
        return List.of(
                create(StrategySpec.of(BUY_AND_HOLD)).definition(),
                create(StrategySpec.of(MOVING_AVERAGE_MOMENTUM)).definition(),
                create(StrategySpec.of(MEAN_REVERSION)).definition(),
                create(StrategySpec.of(CROSS_SECTIONAL_MOMENTUM)).definition());
    }

    private static Strategy buyAndHold(Map<String, String> input) {
        Parameters params = Parameters.of(input, Map.of(
                "symbol", "ALL",
                "targetWeight", "0.95"));
        String symbol = params.text("symbol");
        BigDecimal targetWeight = params.ratio("targetWeight", false);
        return new BaseStrategy(BUY_AND_HOLD, 1, params.values()) {
            @Override
            public TargetPortfolio generateTargets(StrategyContext context) {
                List<Security> selected = context.history().keySet().stream()
                        .filter(security -> "ALL".equals(symbol)
                                || security.canonicalCode().equals(symbol))
                        .filter(security -> latestIsToday(
                                context.history().get(security),
                                context.signalDate()))
                        .sorted().toList();
                if (selected.isEmpty()) {
                    return target(context, Map.of(), "NO_ELIGIBLE_SECURITY");
                }
                boolean hasPosition = selected.stream().anyMatch(value ->
                        context.currentPositions().getOrDefault(value, 0) > 0);
                if (hasPosition) {
                    return hold(context, "BUY_AND_HOLD_EXISTING_POSITION");
                }
                BigDecimal each = targetWeight.divide(
                        BigDecimal.valueOf(selected.size()), 12,
                        RoundingMode.DOWN);
                Map<Security, BigDecimal> weights = new TreeMap<>();
                selected.forEach(value -> weights.put(value, each));
                return target(context, weights, "BUY_AND_HOLD_INITIAL_ENTRY");
            }
        };
    }

    private static Strategy movingAverage(Map<String, String> input) {
        Parameters params = Parameters.of(input, Map.of(
                "shortWindow", "5",
                "longWindow", "20",
                "targetWeight", "0.30"));
        int shortWindow = params.integer("shortWindow", 1, 250);
        int longWindow = params.integer("longWindow", 2, 500);
        BigDecimal targetWeight = params.ratio("targetWeight", false);
        if (shortWindow >= longWindow) {
            throw invalid("M2_MOVING_AVERAGE_WINDOWS_INVALID");
        }
        return new BaseStrategy(MOVING_AVERAGE_MOMENTUM, longWindow,
                params.values()) {
            @Override
            public TargetPortfolio generateTargets(StrategyContext context) {
                Map<Security, BigDecimal> weights = new TreeMap<>();
                for (Map.Entry<Security, List<DailyBar>> entry
                        : context.history().entrySet()) {
                    List<DailyBar> bars = entry.getValue();
                    if (bars.size() < longWindow
                            || !latestIsToday(bars, context.signalDate())) {
                        continue;
                    }
                    BigDecimal shortAverage = averageClose(bars, shortWindow);
                    BigDecimal longAverage = averageClose(bars, longWindow);
                    BigDecimal close = bars.get(bars.size() - 1).close();
                    if (shortAverage.compareTo(longAverage) > 0
                            && close.compareTo(longAverage) > 0) {
                        weights.put(entry.getKey(), targetWeight);
                    }
                }
                return target(context, weights, "MOVING_AVERAGE_SIGNAL");
            }
        };
    }

    private static Strategy meanReversion(Map<String, String> input) {
        Parameters params = Parameters.of(input, Map.of(
                "lookback", "10",
                "entryDeviation", "0.03",
                "exitDeviation", "0.00",
                "targetWeight", "0.25"));
        int lookback = params.integer("lookback", 2, 500);
        BigDecimal entryDeviation = params.ratio("entryDeviation", true);
        BigDecimal exitDeviation = params.ratio("exitDeviation", true);
        BigDecimal targetWeight = params.ratio("targetWeight", false);
        return new BaseStrategy(MEAN_REVERSION, lookback, params.values()) {
            @Override
            public TargetPortfolio generateTargets(StrategyContext context) {
                Map<Security, BigDecimal> weights = new TreeMap<>();
                for (Map.Entry<Security, List<DailyBar>> entry
                        : context.history().entrySet()) {
                    List<DailyBar> bars = entry.getValue();
                    if (bars.size() < lookback
                            || !latestIsToday(bars, context.signalDate())) {
                        continue;
                    }
                    BigDecimal mean = averageClose(bars, lookback);
                    BigDecimal close = bars.get(bars.size() - 1).close();
                    boolean held = context.currentPositions().getOrDefault(
                            entry.getKey(), 0) > 0;
                    BigDecimal entryLevel = mean.multiply(
                            BigDecimal.ONE.subtract(entryDeviation));
                    BigDecimal exitLevel = mean.multiply(
                            BigDecimal.ONE.add(exitDeviation));
                    if (!held && close.compareTo(entryLevel) <= 0
                            || held && close.compareTo(exitLevel) < 0) {
                        weights.put(entry.getKey(), targetWeight);
                    }
                }
                return target(context, weights, "MEAN_REVERSION_SIGNAL");
            }
        };
    }

    private static Strategy crossSectional(Map<String, String> input) {
        Parameters params = Parameters.of(input, Map.of(
                "lookback", "20",
                "topN", "2",
                "rebalanceEvery", "5",
                "targetGrossExposure", "0.90"));
        int lookback = params.integer("lookback", 2, 500);
        int topN = params.integer("topN", 1, 100);
        int rebalanceEvery = params.integer("rebalanceEvery", 1, 250);
        BigDecimal gross = params.ratio("targetGrossExposure", false);
        return new BaseStrategy(CROSS_SECTIONAL_MOMENTUM, lookback + 1,
                params.values()) {
            @Override
            public TargetPortfolio generateTargets(StrategyContext context) {
                if (context.sessionIndex() % rebalanceEvery != 0
                        && !context.currentPositions().isEmpty()) {
                    return hold(context,
                            "CROSS_SECTIONAL_HOLD_UNTIL_REBALANCE");
                }
                List<RankedSecurity> ranked = new ArrayList<>();
                for (Map.Entry<Security, List<DailyBar>> entry
                        : context.history().entrySet()) {
                    List<DailyBar> bars = entry.getValue();
                    if (bars.size() < lookback + 1
                            || !latestIsToday(bars, context.signalDate())) {
                        continue;
                    }
                    BigDecimal first = bars.get(bars.size() - lookback - 1)
                            .close();
                    BigDecimal last = bars.get(bars.size() - 1).close();
                    BigDecimal momentum = last.divide(first, 12,
                            RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE);
                    ranked.add(new RankedSecurity(entry.getKey(), momentum));
                }
                ranked.sort(Comparator.comparing(RankedSecurity::momentum)
                        .reversed().thenComparing(RankedSecurity::security));
                int count = Math.min(topN, ranked.size());
                if (count == 0) {
                    return target(context, Map.of(),
                            "CROSS_SECTIONAL_NO_ELIGIBLE_SECURITY");
                }
                BigDecimal each = gross.divide(BigDecimal.valueOf(count),
                        12, RoundingMode.DOWN);
                Map<Security, BigDecimal> weights = new TreeMap<>();
                ranked.stream().limit(count).forEach(value ->
                        weights.put(value.security(), each));
                return target(context, weights,
                        "CROSS_SECTIONAL_MOMENTUM_RANK");
            }
        };
    }

    private abstract static class BaseStrategy implements Strategy {
        private final StrategyDefinition definition;

        private BaseStrategy(
                String code,
                int minimumHistory,
                Map<String, String> parameters
        ) {
            this.definition = new StrategyDefinition(
                    StrategyResearchModels.STRATEGY_ENGINE_VERSION,
                    code, code, minimumHistory, parameters);
        }

        @Override
        public final StrategyDefinition definition() {
            return definition;
        }

        protected final TargetPortfolio target(
                StrategyContext context,
                Map<Security, BigDecimal> weights,
                String reason
        ) {
            return new TargetPortfolio(context.signalDate(), weights, reason);
        }

        protected final TargetPortfolio hold(
                StrategyContext context,
                String reason
        ) {
            return new TargetPortfolio(context.signalDate(),
                    context.currentWeights(), TargetAction.HOLD, reason);
        }
    }

    private record RankedSecurity(Security security, BigDecimal momentum) {
    }

    private record Parameters(Map<String, String> values) {
        private static Parameters of(
                Map<String, String> supplied,
                Map<String, String> defaults
        ) {
            Map<String, String> values = new TreeMap<>(defaults);
            for (Map.Entry<String, String> entry : supplied.entrySet()) {
                if (!defaults.containsKey(entry.getKey())) {
                    throw invalid("M2_STRATEGY_PARAMETER_UNKNOWN");
                }
                values.put(entry.getKey(), entry.getValue());
            }
            return new Parameters(Map.copyOf(values));
        }

        private String text(String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw invalid("M2_STRATEGY_PARAMETER_INVALID");
            }
            return value;
        }

        private int integer(String key, int minimum, int maximum) {
            try {
                int value = Integer.parseInt(text(key));
                if (value < minimum || value > maximum) {
                    throw invalid("M2_STRATEGY_PARAMETER_RANGE_INVALID");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw invalid("M2_STRATEGY_PARAMETER_TYPE_INVALID");
            }
        }

        private BigDecimal ratio(String key, boolean allowZero) {
            try {
                BigDecimal value = new BigDecimal(text(key));
                if ((allowZero ? value.signum() < 0 : value.signum() <= 0)
                        || value.compareTo(BigDecimal.ONE) > 0) {
                    throw invalid("M2_STRATEGY_PARAMETER_RANGE_INVALID");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw invalid("M2_STRATEGY_PARAMETER_TYPE_INVALID");
            }
        }
    }

    private static boolean latestIsToday(
            List<DailyBar> bars,
            java.time.LocalDate signalDate
    ) {
        return !bars.isEmpty()
                && bars.get(bars.size() - 1).tradeDate().equals(signalDate);
    }

    private static BigDecimal averageClose(List<DailyBar> bars, int window) {
        return bars.subList(bars.size() - window, bars.size()).stream()
                .map(DailyBar::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), 12, RoundingMode.HALF_EVEN);
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
