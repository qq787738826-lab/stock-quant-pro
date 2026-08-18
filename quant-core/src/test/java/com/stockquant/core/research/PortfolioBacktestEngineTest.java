package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.BacktestResult;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.RejectionReason;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.Side;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioBacktestEngineTest {
    private final PortfolioBacktestEngine engine =
            new PortfolioBacktestEngine();

    @Test
    void replayIsByteStableAndSignalsAlwaysExecuteOnALaterSession() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(4, 260);
        BacktestRequest request = request(dataset,
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "5", "longWindow", "20",
                                "targetWeight", "0.2")),
                BacktestConfig.standard());

        BacktestResult first = engine.run(request);
        BacktestResult second = engine.run(request);

        assertEquals(first, second);
        assertEquals(first.deterministicFingerprint(),
                second.deterministicFingerprint());
        assertEquals(64, first.deterministicFingerprint().length());
        assertFalse(first.tradeLedger().isEmpty());
        assertTrue(first.tradeLedger().stream().allMatch(fill ->
                fill.executionDate().isAfter(fill.signalDate())
                        && fill.executionAt().isAfter(fill.signalAt())));
        assertTrue(first.accounting().invariantPassed());
        assertEquals(0, first.accounting().cashConservationDelta().signum());
        assertEquals(0, first.accounting().pnlReconciliationDelta().signum());
    }

    @Test
    void feesAndSlippageReduceOtherwiseIdenticalBuyAndHoldReturn() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(1, 180);
        StrategySpec strategy = StrategySpec.of(StrategyRegistry.BUY_AND_HOLD);
        BacktestConfig free = config(BigDecimal.ZERO, BigDecimal.ZERO, 0);
        BacktestConfig fees = config(new BigDecimal("0.0003"),
                new BigDecimal("0.0005"), 0);
        BacktestConfig slipped = config(new BigDecimal("0.0003"),
                new BigDecimal("0.0005"), 30);

        BacktestResult freeResult = engine.run(request(dataset, strategy, free));
        BacktestResult feeResult = engine.run(request(dataset, strategy, fees));
        BacktestResult slippedResult = engine.run(request(dataset, strategy,
                slipped));

        assertTrue(feeResult.metrics().finalEquity().compareTo(
                freeResult.metrics().finalEquity()) < 0);
        assertTrue(slippedResult.metrics().finalEquity().compareTo(
                feeResult.metrics().finalEquity()) < 0);
        assertTrue(feeResult.accounting().totalCommission().signum() > 0);
        assertTrue(slippedResult.accounting().totalSlippageCost().signum() > 0);
    }

    @Test
    void missingAndSuspendedOpenPricesAreRejectedWithoutChangingCash() {
        ResearchDataset original = StrategyResearchTestFixtures.dataset(1, 20);
        Security security = original.securities().get(0);
        var secondDate = original.sessions().get(1).tradeDate();
        List<DailyBar> missing = original.bars().stream()
                .filter(bar -> !bar.tradeDate().equals(secondDate)).toList();
        BacktestResult missingResult = engine.run(request(
                StrategyResearchTestFixtures.replaceBars(original, missing,
                        "_MISSING"),
                StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                BacktestConfig.standard()));
        assertTrue(missingResult.rejectedOrders().stream().anyMatch(value ->
                value.security().equals(security)
                        && value.reason() == RejectionReason.NO_TRADABLE_PRICE));
        assertTrue(missingResult.tradeLedger().stream().allMatch(value ->
                !value.executionDate().equals(secondDate)));

        List<DailyBar> suspended = new ArrayList<>();
        for (DailyBar bar : original.bars()) {
            suspended.add(bar.tradeDate().equals(secondDate)
                    ? new DailyBar(bar.security(), bar.tradeDate(), bar.open(),
                    bar.high(), bar.low(), bar.close(), bar.volume(), false,
                    bar.marketCloseAvailableAt(), bar.sourceKnownAt()) : bar);
        }
        BacktestResult suspendedResult = engine.run(request(
                StrategyResearchTestFixtures.replaceBars(original, suspended,
                        "_SUSPENDED"),
                StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                BacktestConfig.standard()));
        assertTrue(suspendedResult.rejectedOrders().stream().anyMatch(value ->
                value.reason() == RejectionReason.SUSPENDED));
    }

    @Test
    void portfolioLimitsBoardLotsAndCashConservationAlwaysHold() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(10, 220);
        BacktestConfig limited = new BacktestConfig(
                new BigDecimal("500000"), new BigDecimal("0.0003"),
                new BigDecimal("5"), new BigDecimal("0.0005"), 5, 100,
                new BigDecimal("0.75"), new BigDecimal("0.25"), 3,
                new BigDecimal("0.40"), new BigDecimal("0.02"), true);
        BacktestResult result = engine.run(request(dataset, new StrategySpec(
                StrategyRegistry.CROSS_SECTIONAL_MOMENTUM, Map.of(
                "lookback", "20", "topN", "8",
                "rebalanceEvery", "5",
                "targetGrossExposure", "1.0")), limited));

        assertTrue(result.endingPositions().size() <= 3);
        assertTrue(result.tradeLedger().stream().allMatch(value ->
                value.quantity() % 100 == 0));
        assertTrue(result.equityCurve().stream().allMatch(value ->
                value.cash().signum() >= 0 && value.equity().signum() > 0));
        assertTrue(result.accounting().invariantPassed());
    }

    @Test
    void sellFillsApplyStampDutyAndProduceReconciledRealizedPnl() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(2, 300);
        BacktestResult result = engine.run(request(dataset,
                new StrategySpec(StrategyRegistry.MEAN_REVERSION, Map.of(
                        "lookback", "8", "entryDeviation", "0.01",
                        "exitDeviation", "0", "targetWeight", "0.3")),
                BacktestConfig.standard()));

        assertTrue(result.tradeLedger().stream().anyMatch(value ->
                value.side() == Side.SELL));
        assertTrue(result.tradeLedger().stream()
                .filter(value -> value.side() == Side.SELL)
                .allMatch(value -> value.stampDuty().signum() > 0));
        assertTrue(result.accounting().invariantPassed());
    }

    @Test
    void buyAndHoldDoesNotRebalanceAndLedgerReplaysCashAndPositionsExactly() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(4, 180);
        BacktestConfig config = BacktestConfig.standard();
        BacktestResult result = engine.run(request(dataset,
                StrategySpec.of(StrategyRegistry.BUY_AND_HOLD), config));

        BigDecimal cash = config.initialCash().setScale(8);
        Map<Security, Integer> quantities = new java.util.TreeMap<>();
        for (var fill : result.tradeLedger()) {
            if (fill.side() == Side.BUY) {
                cash = cash.subtract(fill.grossAmount())
                        .subtract(fill.commission()).setScale(8);
                quantities.merge(fill.security(), fill.quantity(), Integer::sum);
            } else {
                cash = cash.add(fill.grossAmount())
                        .subtract(fill.commission())
                        .subtract(fill.stampDuty()).setScale(8);
                quantities.merge(fill.security(), -fill.quantity(), Integer::sum);
            }
            assertEquals(0, cash.compareTo(fill.cashAfter()));
            assertEquals(quantities.get(fill.security()).intValue(),
                    fill.positionAfter());
        }
        assertEquals(dataset.securities().size(), result.tradeLedger().size());
        assertTrue(result.tradeLedger().stream().allMatch(fill ->
                fill.side() == Side.BUY));
        assertEquals(0, cash.compareTo(result.accounting().endingCash()));
    }

    @Test
    void rejectsLookAheadAvailabilityAndChangingDataChangesFingerprint() {
        ResearchDataset dataset = StrategyResearchTestFixtures.dataset(2, 80);
        DailyBar first = dataset.bars().get(0);
        assertThrows(IllegalArgumentException.class, () -> new DailyBar(
                first.security(), first.tradeDate(), first.open(), first.high(),
                first.low(), first.close(), first.volume(), first.tradable(),
                first.marketCloseAvailableAt().plusSeconds(1),
                first.sourceKnownAt()));

        BacktestResult original = engine.run(request(dataset,
                StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                BacktestConfig.standard()));
        List<DailyBar> changed = new ArrayList<>(dataset.bars());
        DailyBar last = changed.get(changed.size() - 1);
        changed.set(changed.size() - 1, new DailyBar(last.security(),
                last.tradeDate(), last.open(), last.high().add(BigDecimal.ONE),
                last.low(), last.close().add(new BigDecimal("0.5")),
                last.volume(), last.tradable(), last.marketCloseAvailableAt(),
                last.sourceKnownAt()));
        ResearchDataset revised = StrategyResearchTestFixtures.replaceBars(
                dataset, changed, "_REVISED");
        BacktestResult revisedResult = engine.run(request(revised,
                StrategySpec.of(StrategyRegistry.BUY_AND_HOLD),
                BacktestConfig.standard()));
        assertNotEquals(original.deterministicFingerprint(),
                revisedResult.deterministicFingerprint());
    }

    @Test
    void rejectsWeekendOpenSessionsAndMalformedSecurityIdentities() {
        assertThrows(IllegalArgumentException.class, () ->
                new StrategyResearchModels.TradingSession(
                        LocalDate.of(2024, 1, 6), Set.of("SSE")));
        assertThrows(IllegalArgumentException.class, () ->
                new Security("60001", "SSE"));
        assertThrows(IllegalArgumentException.class, () ->
                new Security("600001", "BSE"));
        assertEquals("300001:SZSE",
                new Security("300001", "SZSE").canonicalCode());
        assertEquals("688001:SSE",
                new Security("688001", "SSE").canonicalCode());
    }

    @Test
    void trappedSuspendedPositionCannotCreateExcessGrossExposure() {
        ResearchDataset original = StrategyResearchTestFixtures.dataset(2, 120);
        BacktestConfig config = new BacktestConfig(
                new BigDecimal("500000"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 100, new BigDecimal("0.50"),
                new BigDecimal("0.50"), 2, BigDecimal.ONE,
                BigDecimal.ZERO, true);
        StrategySpec rotation = new StrategySpec(
                StrategyRegistry.CROSS_SECTIONAL_MOMENTUM, Map.of(
                "lookback", "5", "topN", "1", "rebalanceEvery", "1",
                "targetGrossExposure", "0.50"));
        BacktestResult baseline = engine.run(request(original, rotation, config));
        var switchSell = baseline.tradeLedger().stream()
                .filter(value -> value.side() == Side.SELL)
                .filter(value -> baseline.tradeLedger().stream().anyMatch(other ->
                        other.executionDate().equals(value.executionDate())
                                && other.side() == Side.BUY
                                && !other.security().equals(value.security())))
                .findFirst().orElseThrow();

        List<DailyBar> bars = new ArrayList<>();
        for (DailyBar bar : original.bars()) {
            bars.add(bar.security().equals(switchSell.security())
                    && bar.tradeDate().equals(switchSell.executionDate())
                    ? new DailyBar(bar.security(), bar.tradeDate(), bar.open(),
                    bar.high(), bar.low(), bar.close(), bar.volume(), false,
                    bar.marketCloseAvailableAt(), bar.sourceKnownAt()) : bar);
        }
        ResearchDataset suspended = StrategyResearchTestFixtures.replaceBars(
                original, bars, "_TRAPPED_POSITION");
        BacktestResult result = engine.run(request(suspended, rotation, config));

        assertTrue(result.rejectedOrders().stream().anyMatch(value ->
                value.executionDate().equals(switchSell.executionDate())
                        && value.security().equals(switchSell.security())
                        && value.reason() == RejectionReason.SUSPENDED));
        Map<Security, Integer> quantities = new java.util.TreeMap<>();
        BigDecimal cash = config.initialCash();
        for (var fill : result.tradeLedger()) {
            if (fill.executionDate().isAfter(switchSell.executionDate())) {
                break;
            }
            quantities.merge(fill.security(), fill.side() == Side.BUY
                    ? fill.quantity() : -fill.quantity(), Integer::sum);
            cash = fill.cashAfter();
        }
        BigDecimal openValue = BigDecimal.ZERO;
        for (var position : quantities.entrySet()) {
            if (position.getValue() <= 0) {
                continue;
            }
            BigDecimal open = suspended.bars().stream()
                    .filter(value -> value.security().equals(position.getKey())
                            && value.tradeDate().equals(
                            switchSell.executionDate()))
                    .findFirst().orElseThrow().open();
            openValue = openValue.add(open.multiply(
                    BigDecimal.valueOf(position.getValue())));
        }
        BigDecimal gross = openValue.divide(openValue.add(cash), 12,
                java.math.RoundingMode.HALF_EVEN);
        assertTrue(gross.compareTo(config.maxGrossExposure()) <= 0);
    }

    private static BacktestRequest request(
            ResearchDataset dataset,
            StrategySpec strategy,
            BacktestConfig config
    ) {
        return new BacktestRequest(dataset, strategy, config,
                dataset.firstSessionDate(), dataset.lastSessionDate());
    }

    private static BacktestConfig config(
            BigDecimal commission,
            BigDecimal stamp,
            int slippage
    ) {
        return new BacktestConfig(new BigDecimal("1000000"), commission,
                commission.signum() == 0 ? BigDecimal.ZERO
                        : new BigDecimal("5"), stamp, slippage, 100,
                new BigDecimal("0.95"), new BigDecimal("0.95"), 1,
                BigDecimal.ONE, new BigDecimal("0.02"), true);
    }
}
