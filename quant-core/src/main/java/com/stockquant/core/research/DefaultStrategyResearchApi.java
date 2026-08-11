package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.BacktestResult;
import com.stockquant.core.research.StrategyResearchModels.BenchmarkResult;
import com.stockquant.core.research.StrategyResearchModels.ComparisonResult;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.ResearchResult;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyComparison;
import com.stockquant.core.research.StrategyResearchModels.StrategyDefinition;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TemporalSplit;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.core.research.StrategyResearchModels.TrainTestResult;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardFold;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardPlan;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Default in-process implementation of STRATEGY_RESEARCH_API_V1. */
public final class DefaultStrategyResearchApi implements StrategyResearchApi {
    private final StrategyRegistry registry;
    private final PortfolioBacktestEngine engine;

    public DefaultStrategyResearchApi() {
        this(new StrategyRegistry());
    }

    public DefaultStrategyResearchApi(StrategyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.engine = new PortfolioBacktestEngine(registry);
    }

    @Override
    public List<StrategyDefinition> catalog() {
        return registry.catalog();
    }

    @Override
    public ResearchResult backtest(
            BacktestRequest request,
            Security benchmark
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(benchmark, "benchmark");
        if (!request.dataset().securities().contains(benchmark)) {
            throw invalid("M2_BENCHMARK_SECURITY_MISSING");
        }
        BacktestResult primary = engine.run(request);
        BacktestResult baseline = engine.run(new BacktestRequest(
                request.dataset(),
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD, Map.of(
                        "symbol", benchmark.canonicalCode(),
                        "targetWeight", "1.0")),
                benchmarkConfig(request.config()),
                request.executionStart(), request.executionEnd()));
        BigDecimal excess = primary.metrics().totalReturn().subtract(
                baseline.metrics().totalReturn());
        return new ResearchResult(StrategyResearchModels.API_VERSION,
                primary, new BenchmarkResult(benchmark, baseline, excess));
    }

    @Override
    public ComparisonResult compare(
            ResearchDataset dataset,
            List<StrategySpec> strategies,
            BacktestConfig config,
            LocalDate executionStart,
            LocalDate executionEnd,
            Security benchmark
    ) {
        Objects.requireNonNull(dataset, "dataset");
        List<StrategySpec> specs = List.copyOf(Objects.requireNonNull(
                strategies, "strategies"));
        if (specs.isEmpty()) {
            throw invalid("M2_COMPARISON_STRATEGIES_EMPTY");
        }
        List<StrategyComparison> results = new ArrayList<>();
        for (StrategySpec spec : specs) {
            ResearchResult result = backtest(new BacktestRequest(
                    dataset, spec, config, executionStart, executionEnd),
                    benchmark);
            BacktestResult backtest = result.strategyResult();
            results.add(new StrategyComparison(
                    backtest.strategy().strategyCode(),
                    backtest.strategy().strategyVersion(),
                    backtest.deterministicFingerprint(),
                    backtest.metrics().totalReturn(),
                    backtest.metrics().maxDrawdown(),
                    backtest.metrics().sharpeRatio(),
                    backtest.metrics().turnover(),
                    result.benchmark().excessReturn()));
        }
        return new ComparisonResult(StrategyResearchModels.API_VERSION,
                results);
    }

    @Override
    public TrainTestResult trainTest(
            ResearchDataset dataset,
            StrategySpec strategy,
            BacktestConfig config,
            TemporalSplit split,
            Security benchmark
    ) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(split, "split");
        requireDateInDataset(dataset, split.trainStart());
        requireDateInDataset(dataset, split.trainEnd());
        requireDateInDataset(dataset, split.testStart());
        requireDateInDataset(dataset, split.testEnd());
        ResearchResult train = backtest(new BacktestRequest(dataset, strategy,
                config, split.trainStart(), split.trainEnd()), benchmark);
        ResearchResult test = backtest(new BacktestRequest(dataset, strategy,
                config, split.testStart(), split.testEnd()), benchmark);
        if (!train.strategyResult().executionEnd().isBefore(
                test.strategyResult().executionStart())) {
            throw invalid("M2_TRAIN_TEST_DATA_CROSSING");
        }
        return new TrainTestResult(StrategyResearchModels.API_VERSION,
                split, train, test, true);
    }

    @Override
    public WalkForwardResult walkForward(
            ResearchDataset dataset,
            StrategySpec strategy,
            BacktestConfig config,
            WalkForwardPlan plan,
            Security benchmark
    ) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(plan, "plan");
        List<LocalDate> dates = dataset.sessions().stream()
                .filter(TradingSession::anyOpen)
                .map(TradingSession::tradeDate).toList();
        List<WalkForwardFold> folds = new ArrayList<>();
        int required = plan.trainSessions() + plan.testSessions();
        for (int start = 0; start + required <= dates.size()
                && folds.size() < plan.maximumFolds();
                start += plan.stepSessions()) {
            int trainEndIndex = start + plan.trainSessions() - 1;
            int testStartIndex = trainEndIndex + 1;
            int testEndIndex = testStartIndex + plan.testSessions() - 1;
            TemporalSplit split = new TemporalSplit(
                    dates.get(start), dates.get(trainEndIndex),
                    dates.get(testStartIndex), dates.get(testEndIndex));
            folds.add(new WalkForwardFold(folds.size() + 1, split,
                    trainTest(dataset, strategy, config, split, benchmark)));
        }
        return new WalkForwardResult(StrategyResearchModels.API_VERSION,
                plan, folds, true);
    }

    private static BacktestConfig benchmarkConfig(BacktestConfig source) {
        return new BacktestConfig(source.initialCash(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0,
                source.boardLotSize(), BigDecimal.ONE, BigDecimal.ONE, 1,
                BigDecimal.ONE, source.annualRiskFreeRate(), source.tPlusOne());
    }

    private static void requireDateInDataset(
            ResearchDataset dataset,
            LocalDate date
    ) {
        boolean found = dataset.sessions().stream().anyMatch(value ->
                value.tradeDate().equals(date) && value.anyOpen());
        if (!found) {
            throw invalid("M2_SPLIT_DATE_NOT_OPEN_SESSION");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
