package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.ComparisonResult;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.ResearchResult;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TemporalSplit;
import com.stockquant.core.research.StrategyResearchModels.TrainTestResult;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardPlan;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyResearchApiTest {
    private final StrategyResearchApi api = new DefaultStrategyResearchApi();

    @Test
    void benchmarkUsesSameWindowAndUnifiedResultsAreComparable() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(5, 300);
        var benchmark = dataset.securities().get(0);
        ComparisonResult comparison = api.compare(dataset, List.of(
                        StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                        new StrategySpec(
                                StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                                Map.of("shortWindow", "5", "longWindow", "20",
                                        "targetWeight", "0.18")),
                        new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                                Map.of("lookback", "10",
                                        "entryDeviation", "0.02",
                                        "exitDeviation", "0",
                                        "targetWeight", "0.18")),
                        new StrategySpec(
                                StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                                Map.of("lookback", "20", "topN", "2",
                                        "rebalanceEvery", "5",
                                        "targetGrossExposure", "0.8"))),
                BacktestConfig.standard(), dataset.firstSessionDate(),
                dataset.lastSessionDate(), benchmark);

        assertEquals(4, comparison.strategies().size());
        assertTrue(comparison.strategies().stream().allMatch(value ->
                value.fingerprint().length() == 64));
        ResearchResult result = api.backtest(new BacktestRequest(dataset,
                StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                BacktestConfig.standard(), dataset.firstSessionDate(),
                dataset.lastSessionDate()), benchmark);
        assertEquals(result.strategyResult().executionStart(),
                result.benchmark().result().executionStart());
        assertEquals(result.strategyResult().executionEnd(),
                result.benchmark().result().executionEnd());
    }

    @Test
    void trainTestIsStrictlyDisjointAndWalkForwardProducesOosFolds() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(4, 360);
        List<StrategyResearchModels.TradingSession> sessions = dataset.sessions()
                .stream().filter(StrategyResearchModels.TradingSession::anyOpen)
                .toList();
        TemporalSplit split = new TemporalSplit(
                sessions.get(0).tradeDate(), sessions.get(199).tradeDate(),
                sessions.get(200).tradeDate(), sessions.get(299).tradeDate());
        StrategySpec strategy = new StrategySpec(
                StrategyRegistry.MOVING_AVERAGE_MOMENTUM, Map.of(
                "shortWindow", "5", "longWindow", "20",
                "targetWeight", "0.2"));
        TrainTestResult result = api.trainTest(dataset, strategy,
                BacktestConfig.standard(), split, dataset.securities().get(0));

        assertTrue(result.strictlyIsolated());
        assertTrue(result.train().strategyResult().executionEnd().isBefore(
                result.test().strategyResult().executionStart()));

        WalkForwardResult walkForward = api.walkForward(dataset, strategy,
                BacktestConfig.standard(), new WalkForwardPlan(120, 40, 40, 5),
                dataset.securities().get(0));
        assertEquals(5, walkForward.folds().size());
        assertTrue(walkForward.outOfSampleOnly());
        assertTrue(walkForward.folds().stream().allMatch(fold ->
                fold.split().trainEnd().isBefore(fold.split().testStart())));
    }

    @Test
    void trainAndTestFingerprintsExcludeFactsBeyondTheirTimeCutoff() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(4, 360);
        List<StrategyResearchModels.TradingSession> sessions = dataset.sessions();
        TemporalSplit split = new TemporalSplit(
                sessions.get(0).tradeDate(), sessions.get(199).tradeDate(),
                sessions.get(200).tradeDate(), sessions.get(299).tradeDate());
        StrategySpec strategy = new StrategySpec(
                StrategyRegistry.MOVING_AVERAGE_MOMENTUM, Map.of(
                "shortWindow", "5", "longWindow", "20",
                "targetWeight", "0.2"));
        TrainTestResult original = api.trainTest(dataset, strategy,
                BacktestConfig.standard(), split, dataset.securities().get(0));

        List<StrategyResearchModels.DailyBar> changed = new ArrayList<>(
                dataset.bars());
        int futureIndex = changed.size() - 1;
        var future = changed.get(futureIndex);
        changed.set(futureIndex, new StrategyResearchModels.DailyBar(
                future.security(), future.tradeDate(), future.open(),
                future.high().add(java.math.BigDecimal.ONE), future.low(),
                future.close().add(new java.math.BigDecimal("0.5")),
                future.volume(), future.tradable(),
                future.marketCloseAvailableAt(), future.sourceKnownAt()));
        ResearchDataset futureRevised = StrategyResearchTestFixtures.replaceBars(
                dataset, changed, "_AFTER_TEST");
        TrainTestResult revised = api.trainTest(futureRevised, strategy,
                BacktestConfig.standard(), split,
                futureRevised.securities().get(0));

        assertEquals(original.train().strategyResult()
                        .deterministicFingerprint(),
                revised.train().strategyResult().deterministicFingerprint());
        assertEquals(original.test().strategyResult()
                        .deterministicFingerprint(),
                revised.test().strategyResult().deterministicFingerprint());
    }

    @Test
    void overlappingSplitAndUnknownBenchmarkFailClosed() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(2, 80);
        assertThrows(IllegalArgumentException.class, () -> new TemporalSplit(
                dataset.sessions().get(0).tradeDate(),
                dataset.sessions().get(20).tradeDate(),
                dataset.sessions().get(20).tradeDate(),
                dataset.sessions().get(40).tradeDate()));
        assertThrows(IllegalArgumentException.class, () -> api.backtest(
                new BacktestRequest(dataset,
                        StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                        BacktestConfig.standard(), dataset.firstSessionDate(),
                        dataset.lastSessionDate()),
                new StrategyResearchModels.Security("600999", "SSE")));
    }
}
