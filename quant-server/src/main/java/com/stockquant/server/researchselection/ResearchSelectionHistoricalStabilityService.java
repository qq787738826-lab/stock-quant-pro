package com.stockquant.server.researchselection;

import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardPlan;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.researchselection.ResearchSelectionHistoricalDatasetLoader.HistoricalDataset;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalGrade;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalResearch;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalStability;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalWindowMetrics;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.WalkForwardSummary;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic, post-hoc historical stability projection for V1.0.8. */
public final class ResearchSelectionHistoricalStabilityService {
    private static final MathContext MC = new MathContext(18,
            RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal SQRT_252 = new BigDecimal(
            "15.874507866387544");
    private static final List<Integer> STANDARD_WINDOWS =
            List.of(20, 60, 120, 250);
    private final DefaultStrategyResearchApi research =
            new DefaultStrategyResearchApi();

    public HistoricalResearch analyze(
            HistoricalDataset historical,
            List<QuantitativeScore> currentRanking,
            Map<String, Integer> liveShadowSamples
    ) {
        Objects.requireNonNull(historical, "historical");
        ResearchDataset dataset = historical.loaded().dataset();
        Map<Security, BigDecimal> currentScores = currentRanking.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        QuantitativeScore::security,
                        QuantitativeScore::score));
        Map<String, Integer> liveSamples = liveShadowSamples == null
                ? Map.of() : Map.copyOf(liveShadowSamples);
        boolean noFuture = dataset.bars().stream().noneMatch(value ->
                value.tradeDate().isAfter(dataset.lastSessionDate())
                        || value.sourceKnownAt().isAfter(
                        dataset.knowledgeCutoff()));
        boolean knownAt = dataset.bars().stream().allMatch(value ->
                !value.sourceKnownAt().isAfter(dataset.knowledgeCutoff())
                        && !value.sourceKnownAt().isBefore(
                        value.marketCloseAvailableAt()));
        int available = dataset.sessions().size();
        boolean quality = dataset.securities().size()
                == ResearchUniverseV1.securities().size()
                && dataset.barsBySecurity().values().stream().allMatch(
                bars -> bars.size() == available && bars.stream().allMatch(
                        value -> value.tradable()
                                && value.close().signum() > 0));
        List<HistoricalStability> securities = dataset.securities().stream()
                .map(security -> stability(dataset, security,
                        currentScores.getOrDefault(security, BigDecimal.ZERO),
                        liveSamples.getOrDefault(
                                security.canonicalCode(), 0), noFuture))
                .sorted(Comparator.comparing(HistoricalStability::score)
                        .reversed().thenComparing(
                                HistoricalStability::security))
                .toList();
        Map<String, Integer> grades = new LinkedHashMap<>();
        for (HistoricalGrade grade : HistoricalGrade.values()) {
            grades.put(grade.name(), Math.toIntExact(securities.stream()
                    .filter(value -> value.grade() == grade).count()));
        }
        return new HistoricalResearch(
                ResearchSelectionModels.HISTORICAL_STABILITY_VERSION,
                "POST_HOC_RESEARCH", "PIT_PARTIAL", available,
                dataset.firstSessionDate(), dataset.lastSessionDate(),
                historical.windowCoverage(), List.of(), securities, grades,
                true, knownAt, quality, noFuture,
                BacktestCanonicalHashService.sha256(
                        dataset.datasetVersion()));
    }

    private HistoricalStability stability(
            ResearchDataset source,
            Security security,
            BigDecimal currentScore,
            int liveSamples,
            boolean noFuture
    ) {
        int available = source.sessions().size();
        List<HistoricalWindowMetrics> windows = new ArrayList<>();
        for (int size : STANDARD_WINDOWS) {
            if (available >= size) {
                windows.add(window(source, security,
                        available - size, available,
                        "CURRENT_" + size));
            }
        }
        int rollingStart = Math.max(0, available - 60);
        int rollingIndex = 1;
        for (int start = rollingStart; start + 20 <= available;
                start += 10) {
            windows.add(window(source, security, start, start + 20,
                    "ROLLING_20_" + rollingIndex++));
        }
        WalkForwardSummary walkForward = walkForward(source, security);
        HistoricalWindowMetrics best = windows.stream().max(
                Comparator.comparing(
                        HistoricalWindowMetrics::costAdjustedReturn))
                .orElseThrow();
        HistoricalWindowMetrics worst = windows.stream().min(
                Comparator.comparing(
                        HistoricalWindowMetrics::costAdjustedReturn))
                .orElseThrow();
        BigDecimal windowRatio = ratio(windows.stream().filter(value ->
                value.costAdjustedReturn().signum() > 0).count(),
                windows.size());
        int positiveStrategies = windows.stream().mapToInt(
                HistoricalWindowMetrics::positiveStrategyCount).sum();
        int strategyCount = windows.stream().mapToInt(
                HistoricalWindowMetrics::strategyCount).sum();
        BigDecimal strategyRatio = ratio(positiveStrategies, strategyCount);
        int trades = windows.stream().mapToInt(
                HistoricalWindowMetrics::tradeCount).sum()
                + walkForward.tradeCount();
        BigDecimal dataComponent = dataComponent(available);
        BigDecimal consistencyComponent = windowRatio.multiply(
                        new BigDecimal("60"), MC)
                .add(strategyRatio.multiply(new BigDecimal("40"), MC));
        BigDecimal outOfSampleComponent = outOfSampleComponent(walkForward);
        BigDecimal worstDrawdown = windows.stream().map(
                        HistoricalWindowMetrics::maxDrawdown)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal worstVolatility = windows.stream().map(
                        HistoricalWindowMetrics::annualizedVolatility)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal riskComponent = clamp(HUNDRED
                .subtract(worstDrawdown.multiply(
                        new BigDecimal("250"), MC))
                .subtract(worstVolatility.multiply(
                        new BigDecimal("100"), MC)));
        BigDecimal costSampleComponent = clamp(strategyRatio.multiply(
                        new BigDecimal("70"), MC)
                .add(BigDecimal.valueOf(Math.min(30.0, trades * 1.5))));
        BigDecimal score = dataComponent.multiply(new BigDecimal("0.20"), MC)
                .add(consistencyComponent.multiply(
                        new BigDecimal("0.20"), MC))
                .add(outOfSampleComponent.multiply(
                        new BigDecimal("0.25"), MC))
                .add(riskComponent.multiply(new BigDecimal("0.20"), MC))
                .add(costSampleComponent.multiply(
                        new BigDecimal("0.15"), MC));
        HistoricalGrade grade = grade(score, currentScore, available,
                walkForward, worstDrawdown, noFuture);
        List<String> evidence = List.of(
                "可用完整历史 " + available + " 个交易日",
                "多窗口成本后正收益比例 " + percent(windowRatio),
                "多策略成本后正收益比例 " + percent(strategyRatio),
                "样本外正收益窗口比例 "
                        + percent(walkForward.positiveFoldRatio()),
                "历史计算全部由确定性Java/M2引擎完成");
        List<String> limitations = new ArrayList<>();
        if (available < 120) limitations.add("120日历史覆盖不足");
        if (available < 250) limitations.add("250日历史覆盖不足");
        if (liveSamples < 5) limitations.add("Live Shadow样本不足");
        limitations.add("本结果属于事后历史研究，不是历史实时影子记录");
        limitations.add("Provider PIT资格仍为部分满足");
        if (!walkForward.available()) limitations.add("Walk-forward样本不足");
        else if (walkForward.averageOutOfSampleReturn().signum() < 0) {
            limitations.add("样本外平均表现为负");
        }
        if (worstDrawdown.compareTo(new BigDecimal("0.20")) > 0) {
            limitations.add("历史最差窗口回撤偏高");
        }
        return new HistoricalStability(security, available, scaled(score),
                grade, scaled(dataComponent), scaled(consistencyComponent),
                scaled(outOfSampleComponent), scaled(riskComponent),
                scaled(costSampleComponent), scaled(windowRatio),
                scaled(strategyRatio), best.windowCode(),
                best.costAdjustedReturn(), worst.windowCode(),
                worst.costAdjustedReturn(), walkForward, List.copyOf(windows),
                liveSamples, evidence, List.copyOf(limitations), noFuture);
    }

    private HistoricalWindowMetrics window(
            ResearchDataset source,
            Security security,
            int start,
            int end,
            String code
    ) {
        ResearchDataset dataset = slice(source, security, start, end, code);
        List<DailyBar> bars = dataset.barsBySecurity().get(security);
        PriceMetrics price = priceMetrics(bars);
        List<StrategyOutcome> outcomes = new ArrayList<>();
        for (StrategySpec strategy
                : ResearchSelectionStrategies.singleSecurityHistorical()) {
            var result = research.backtest(new BacktestRequest(dataset,
                    strategy, BacktestConfig.standard(),
                    dataset.firstSessionDate(), dataset.lastSessionDate()),
                    security).strategyResult();
            outcomes.add(new StrategyOutcome(strategy.strategyCode(),
                    result.metrics().totalReturn(),
                    result.metrics().turnover(), result.metrics().winRate(),
                    result.metrics().fillCount()));
        }
        StrategyOutcome best = outcomes.stream().max(Comparator.comparing(
                StrategyOutcome::totalReturn)).orElseThrow();
        StrategyOutcome worst = outcomes.stream().min(Comparator.comparing(
                StrategyOutcome::totalReturn)).orElseThrow();
        return new HistoricalWindowMetrics(code, dataset.sessions().size(),
                dataset.firstSessionDate(), dataset.lastSessionDate(),
                price.totalReturn(), average(outcomes.stream().map(
                        StrategyOutcome::totalReturn).toList()),
                price.maxDrawdown(), price.volatility(), price.sharpe(),
                average(outcomes.stream().map(
                        StrategyOutcome::turnover).toList()),
                average(outcomes.stream().map(
                        StrategyOutcome::winRate).toList()),
                outcomes.stream().mapToInt(StrategyOutcome::trades).sum(),
                best.strategy(), best.totalReturn(), worst.strategy(),
                worst.totalReturn(), Math.toIntExact(outcomes.stream()
                .filter(value -> value.totalReturn().signum() > 0).count()),
                outcomes.size());
    }

    private WalkForwardSummary walkForward(
            ResearchDataset source,
            Security security
    ) {
        if (source.sessions().size() < 30) {
            return unavailableWalkForward();
        }
        ResearchDataset dataset = slice(source, security, 0,
                source.sessions().size(), "WALK_FORWARD");
        var plan = new WalkForwardPlan(20, 10, 10, 5);
        List<com.stockquant.core.research.StrategyResearchModels
                .PerformanceMetrics> metrics = new ArrayList<>();
        int foldCount = 0;
        boolean isolated = true;
        boolean lookAhead = true;
        for (StrategySpec strategy
                : ResearchSelectionStrategies.singleSecurityHistorical()) {
            var result = research.walkForward(dataset, strategy,
                    BacktestConfig.standard(), plan, security);
            foldCount = Math.max(foldCount, result.folds().size());
            isolated &= result.outOfSampleOnly();
            for (var fold : result.folds()) {
                var test = fold.result().test().strategyResult();
                metrics.add(test.metrics());
                isolated &= fold.result().strictlyIsolated();
                lookAhead &= test.lookAheadGuardPassed();
            }
        }
        return new WalkForwardSummary(true, null, 20, 10, 10,
                foldCount, metrics.size(),
                average(metrics.stream().map(value ->
                        value.totalReturn()).toList()),
                metrics.stream().map(value -> value.totalReturn())
                        .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO),
                ratio(metrics.stream().filter(value ->
                        value.totalReturn().signum() > 0).count(),
                        metrics.size()),
                metrics.stream().map(value -> value.maxDrawdown())
                        .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO),
                metrics.stream().mapToInt(value -> value.fillCount()).sum(),
                isolated, lookAhead);
    }

    private static WalkForwardSummary unavailableWalkForward() {
        return new WalkForwardSummary(false, "INSUFFICIENT_HISTORY",
                20, 10, 10, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, true, true);
    }

    private static ResearchDataset slice(
            ResearchDataset source,
            Security security,
            int start,
            int end,
            String label
    ) {
        List<TradingSession> sessions = source.sessions().subList(start, end);
        Set<LocalDate> dates = sessions.stream().map(
                TradingSession::tradeDate).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        List<DailyBar> bars = source.bars().stream().filter(value ->
                value.security().equals(security)
                        && dates.contains(value.tradeDate())).toList();
        String version = "SELECTION_HISTORY_"
                + BacktestCanonicalHashService.sha256(
                source.datasetVersion() + '|' + security.canonicalCode()
                        + '|' + label + '|' + start + '|' + end);
        return new ResearchDataset(source.contractVersion(), version,
                source.knowledgeMode(), source.knowledgeCutoff(),
                sessions, bars);
    }

    private static PriceMetrics priceMetrics(List<DailyBar> bars) {
        List<BigDecimal> closes = bars.stream().map(DailyBar::close).toList();
        BigDecimal totalReturn = closes.get(closes.size() - 1)
                .divide(closes.get(0), MC).subtract(BigDecimal.ONE);
        List<BigDecimal> returns = new ArrayList<>();
        for (int index = 1; index < closes.size(); index++) {
            returns.add(closes.get(index).divide(closes.get(index - 1), MC)
                    .subtract(BigDecimal.ONE));
        }
        BigDecimal mean = average(returns);
        BigDecimal deviation = deviation(returns, mean);
        BigDecimal volatility = deviation.multiply(SQRT_252, MC);
        BigDecimal sharpe = deviation.signum() == 0 ? BigDecimal.ZERO
                : mean.divide(deviation, MC).multiply(SQRT_252, MC);
        BigDecimal peak = closes.get(0);
        BigDecimal drawdown = BigDecimal.ZERO;
        for (BigDecimal close : closes) {
            if (close.compareTo(peak) > 0) peak = close;
            BigDecimal value = peak.subtract(close).divide(peak, MC);
            if (value.compareTo(drawdown) > 0) drawdown = value;
        }
        return new PriceMetrics(scaledRatio(totalReturn),
                scaledRatio(drawdown), scaledRatio(volatility),
                scaledRatio(sharpe));
    }

    private static BigDecimal deviation(
            List<BigDecimal> values,
            BigDecimal mean
    ) {
        if (values.size() < 2) return BigDecimal.ZERO;
        BigDecimal variance = values.stream().map(value ->
                        value.subtract(mean).pow(2, MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size() - 1L), MC);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    private static HistoricalGrade grade(
            BigDecimal score,
            BigDecimal currentScore,
            int available,
            WalkForwardSummary walkForward,
            BigDecimal worstDrawdown,
            boolean noFuture
    ) {
        if (noFuture && available >= 120
                && score.compareTo(new BigDecimal("75")) >= 0
                && currentScore.compareTo(new BigDecimal("60")) >= 0
                && walkForward.available()
                && walkForward.positiveFoldRatio().compareTo(
                new BigDecimal("0.60")) >= 0
                && worstDrawdown.compareTo(new BigDecimal("0.20")) <= 0) {
            return HistoricalGrade.A;
        }
        if (noFuture && available >= 60
                && score.compareTo(new BigDecimal("45")) >= 0
                && currentScore.compareTo(new BigDecimal("35")) >= 0) {
            return HistoricalGrade.B;
        }
        return HistoricalGrade.C;
    }

    private static BigDecimal dataComponent(int available) {
        if (available >= 250) return HUNDRED;
        if (available >= 120) return new BigDecimal("80");
        if (available >= 60) return new BigDecimal("60");
        return new BigDecimal("35");
    }

    private static BigDecimal outOfSampleComponent(
            WalkForwardSummary value
    ) {
        if (!value.available()) return new BigDecimal("20");
        BigDecimal returnSignal = clamp(new BigDecimal("50").add(
                value.averageOutOfSampleReturn().multiply(
                        new BigDecimal("1000"), MC)));
        return value.positiveFoldRatio().multiply(
                        new BigDecimal("70"), MC)
                .add(returnSignal.multiply(new BigDecimal("0.30"), MC));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), MC)
                .setScale(8, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(numerator).divide(
                BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(HUNDRED);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal scaledRatio(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_EVEN);
    }

    private static String percent(BigDecimal value) {
        return value.multiply(HUNDRED).setScale(1,
                RoundingMode.HALF_EVEN) + "%";
    }

    private record StrategyOutcome(
            String strategy,
            BigDecimal totalReturn,
            BigDecimal turnover,
            BigDecimal winRate,
            int trades
    ) {
    }

    private record PriceMetrics(
            BigDecimal totalReturn,
            BigDecimal maxDrawdown,
            BigDecimal volatility,
            BigDecimal sharpe
    ) {
    }
}
