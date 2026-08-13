package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, explainable cross-sectional ranking for Universe V1. */
public final class ResearchSelectionRankingService {
    private static final MathContext MC = new MathContext(18,
            RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SQRT_252 = new BigDecimal(
            "15.874507866387544");

    public List<QuantitativeScore> rank(ResearchDataset dataset) {
        if (dataset == null || dataset.securities().size() < 3) {
            throw new IllegalArgumentException(
                    "RESEARCH_SELECTION_RANKING_DATASET_INVALID");
        }
        List<RawScore> raw = new ArrayList<>();
        dataset.barsBySecurity().forEach((security, bars) ->
                raw.add(metrics(security, bars)));
        Map<Security, BigDecimal> r5 = percentile(raw,
                value -> value.fiveDayReturn, false);
        Map<Security, BigDecimal> r20 = percentile(raw,
                value -> value.twentyDayReturn, false);
        Map<Security, BigDecimal> r60 = percentile(raw,
                value -> value.sixtyDayReturn, false);
        Map<Security, BigDecimal> sharpe = percentile(raw,
                value -> value.sharpe, false);
        Map<Security, BigDecimal> drawdown = percentile(raw,
                value -> value.maxDrawdown, true);
        Map<Security, BigDecimal> volatility = percentile(raw,
                value -> value.annualizedVolatility, true);

        List<Scored> scored = raw.stream().map(value -> {
            BigDecimal score = weighted(r5.get(value.security), "0.10")
                    .add(weighted(r20.get(value.security), "0.25"))
                    .add(weighted(r60.get(value.security), "0.20"))
                    .add(weighted(trendScore(value.trend), "0.15"))
                    .add(weighted(sharpe.get(value.security), "0.10"))
                    .add(weighted(drawdown.get(value.security), "0.10"))
                    .add(weighted(volatility.get(value.security), "0.05"))
                    .add(weighted(qualityScore(value), "0.05"));
            return new Scored(value, score.setScale(4,
                    RoundingMode.HALF_EVEN));
        }).sorted(Comparator.comparing(Scored::score).reversed()
                .thenComparing(value -> value.raw().security())).toList();

        List<QuantitativeScore> result = new ArrayList<>();
        for (int index = 0; index < scored.size(); index++) {
            Scored value = scored.get(index);
            RawScore metric = value.raw();
            ResearchUniverseV1.Constituent constituent =
                    ResearchUniverseV1.require(metric.security);
            result.add(new QuantitativeScore(index + 1, metric.security,
                    constituent.name(), constituent.industry(), value.score,
                    metric.fiveDayReturn, metric.twentyDayReturn,
                    metric.sixtyDayReturn, metric.annualizedVolatility,
                    metric.maxDrawdown, metric.sharpe,
                    metric.meanReversionZ, metric.trend,
                    metric.observationCount, explanations(metric),
                    metric.dataQualityPassed));
        }
        return List.copyOf(result);
    }

    private static RawScore metrics(Security security, List<DailyBar> input) {
        List<DailyBar> bars = input.stream().sorted(
                Comparator.comparing(DailyBar::tradeDate)).toList();
        if (bars.size() < 20) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_MINIMUM_WINDOW_INCOMPLETE");
        }
        List<BigDecimal> closes = bars.stream().map(DailyBar::close).toList();
        BigDecimal r5 = trailingReturn(closes, 5);
        BigDecimal r20 = trailingReturn(closes, 20);
        BigDecimal r60 = trailingReturn(closes, Math.min(60,
                closes.size() - 1));
        List<BigDecimal> returns = returns(closes);
        BigDecimal mean = mean(returns);
        BigDecimal standardDeviation = standardDeviation(returns, mean);
        BigDecimal annualized = standardDeviation.multiply(SQRT_252, MC);
        BigDecimal sharpe = standardDeviation.signum() == 0
                ? BigDecimal.ZERO : mean.divide(standardDeviation, MC)
                .multiply(SQRT_252, MC);
        BigDecimal maxDrawdown = maxDrawdown(closes);
        BigDecimal ma20 = mean(closes.subList(closes.size() - 20,
                closes.size()));
        int longWindow = Math.min(60, closes.size());
        BigDecimal ma60 = mean(closes.subList(closes.size() - longWindow,
                closes.size()));
        BigDecimal last = closes.get(closes.size() - 1);
        String trend = last.compareTo(ma20) > 0 && ma20.compareTo(ma60) > 0
                ? "UPTREND" : last.compareTo(ma20) < 0
                && ma20.compareTo(ma60) < 0 ? "DOWNTREND" : "NEUTRAL";
        BigDecimal recentStd = standardDeviation(
                closes.subList(closes.size() - 20, closes.size()), ma20);
        BigDecimal z = recentStd.signum() == 0 ? BigDecimal.ZERO
                : last.subtract(ma20).divide(recentStd, MC);
        boolean quality = bars.stream().allMatch(value -> value.tradable()
                && value.close().signum() > 0);
        return new RawScore(security, bars.size(), r5, r20, r60,
                annualized, maxDrawdown, sharpe, z, trend, quality);
    }

    private static BigDecimal trailingReturn(
            List<BigDecimal> closes,
            int periods
    ) {
        int bounded = Math.max(1, Math.min(periods, closes.size() - 1));
        BigDecimal start = closes.get(closes.size() - 1 - bounded);
        return closes.get(closes.size() - 1).divide(start, MC)
                .subtract(BigDecimal.ONE).setScale(8, RoundingMode.HALF_EVEN);
    }

    private static List<BigDecimal> returns(List<BigDecimal> closes) {
        List<BigDecimal> values = new ArrayList<>();
        for (int index = 1; index < closes.size(); index++) {
            values.add(closes.get(index).divide(closes.get(index - 1), MC)
                    .subtract(BigDecimal.ONE));
        }
        return values;
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), MC);
    }

    private static BigDecimal standardDeviation(
            List<BigDecimal> values,
            BigDecimal mean
    ) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal variance = values.stream()
                .map(value -> value.subtract(mean).pow(2, MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size() - 1L), MC);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()))
                .setScale(12, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal maxDrawdown(List<BigDecimal> closes) {
        BigDecimal peak = closes.get(0);
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal close : closes) {
            if (close.compareTo(peak) > 0) {
                peak = close;
            }
            BigDecimal drawdown = peak.subtract(close).divide(peak, MC);
            if (drawdown.compareTo(worst) > 0) {
                worst = drawdown;
            }
        }
        return worst.setScale(8, RoundingMode.HALF_EVEN);
    }

    private static Map<Security, BigDecimal> percentile(
            List<RawScore> values,
            java.util.function.Function<RawScore, BigDecimal> metric,
            boolean lowerIsBetter
    ) {
        List<RawScore> ordered = values.stream().sorted((left, right) -> {
            int comparison = metric.apply(left).compareTo(metric.apply(right));
            if (lowerIsBetter) {
                comparison = -comparison;
            }
            return comparison != 0 ? comparison
                    : left.security.compareTo(right.security);
        }).toList();
        Map<Security, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal denominator = BigDecimal.valueOf(
                Math.max(1, ordered.size() - 1L));
        for (int index = 0; index < ordered.size(); index++) {
            result.put(ordered.get(index).security,
                    BigDecimal.valueOf(index).divide(denominator, MC)
                            .multiply(HUNDRED));
        }
        return Map.copyOf(result);
    }

    private static BigDecimal weighted(BigDecimal score, String weight) {
        return score.multiply(new BigDecimal(weight), MC);
    }

    private static BigDecimal trendScore(String trend) {
        return switch (trend) {
            case "UPTREND" -> HUNDRED;
            case "NEUTRAL" -> new BigDecimal("50");
            default -> BigDecimal.ZERO;
        };
    }

    private static BigDecimal qualityScore(RawScore score) {
        return score.dataQualityPassed ? HUNDRED : BigDecimal.ZERO;
    }

    private static List<String> explanations(RawScore value) {
        List<String> result = new ArrayList<>();
        result.add("20日收益 " + percent(value.twentyDayReturn));
        result.add("60日收益 " + percent(value.sixtyDayReturn));
        result.add("趋势状态 " + value.trend);
        result.add("最大回撤 " + percent(value.maxDrawdown));
        result.add("年化波动 " + percent(value.annualizedVolatility));
        return List.copyOf(result);
    }

    private static String percent(BigDecimal value) {
        return value.multiply(HUNDRED).setScale(2,
                RoundingMode.HALF_EVEN) + "%";
    }

    private record RawScore(
            Security security,
            int observationCount,
            BigDecimal fiveDayReturn,
            BigDecimal twentyDayReturn,
            BigDecimal sixtyDayReturn,
            BigDecimal annualizedVolatility,
            BigDecimal maxDrawdown,
            BigDecimal sharpe,
            BigDecimal meanReversionZ,
            String trend,
            boolean dataQualityPassed
    ) {
    }

    private record Scored(RawScore raw, BigDecimal score) {
    }
}
