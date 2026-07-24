package com.stockquant.core.backtest;

import com.stockquant.core.domain.BacktestModels;
import com.stockquant.core.domain.Bar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestEngineTest {

    private final BacktestEngine engine = new BacktestEngine();
    private final BacktestModels.Request request = new BacktestModels.Request(
            new BigDecimal("100000"),
            10,
            new BigDecimal("0.05"),
            new BigDecimal("0.08"),
            new BigDecimal("0.04"),
            new BigDecimal("0.0003"),
            new BigDecimal("0.0005"));

    @Test
    void isDeterministicUsesNextOpenHundredShareLotsAndFrozenExitPriority() {
        List<Bar> bars = risingBars(80);
        Bar entry = bars.get(21);
        bars.set(21, new Bar(
                entry.symbol(),
                entry.tradeDate(),
                new BigDecimal("100"),
                new BigDecimal("109"),
                new BigDecimal("95"),
                new BigDecimal("101"),
                entry.volume(),
                entry.amount(),
                entry.turnoverRate()));

        BacktestModels.Result first = engine.run(bars, request);
        BacktestModels.Result second = engine.run(List.copyOf(bars), request);
        assertEquals(first, second);
        assertFalse(first.trades().isEmpty());
        BacktestModels.Trade trade = first.trades().get(0);
        assertEquals(bars.get(21).tradeDate(), trade.entryDate());
        assertEquals("STOP_LOSS", trade.exitReason(),
                "same-bar stop loss must precede take profit and trailing stop");
        assertEquals(new BigDecimal("95.00"), trade.exitPrice());
        assertEquals(0, trade.quantity() % 100);
        assertTrue(trade.quantity() >= 100);
        assertTrue(first.trades().stream()
                .allMatch(value -> !value.exitDate().isAfter(bars.get(79).tradeDate())));
    }

    @Test
    void freezesTakeProfitTrailingStopMaxHoldAndFeeArithmetic() {
        List<Bar> takeProfitBars = risingBars(40);
        Bar takeProfitEntry = takeProfitBars.get(21);
        takeProfitBars.set(21, new Bar(
                takeProfitEntry.symbol(),
                takeProfitEntry.tradeDate(),
                new BigDecimal("100"),
                new BigDecimal("108"),
                new BigDecimal("96"),
                new BigDecimal("102"),
                takeProfitEntry.volume(),
                takeProfitEntry.amount(),
                takeProfitEntry.turnoverRate()));
        BacktestModels.Trade takeProfit = engine
                .run(takeProfitBars, request)
                .trades()
                .get(0);
        assertEquals("TAKE_PROFIT", takeProfit.exitReason());
        assertEquals(0, takeProfit.exitPrice().compareTo(new BigDecimal("108")));
        assertEquals(200, takeProfit.quantity());
        assertEquals(
                0,
                takeProfit.pnl().compareTo(new BigDecimal("1576.7200")),
                "commission applies to buy and sell amounts and stamp duty to sell");

        List<Bar> trailingBars = risingBars(40);
        Bar trailingEntry = trailingBars.get(21);
        trailingBars.set(21, new Bar(
                trailingEntry.symbol(),
                trailingEntry.tradeDate(),
                new BigDecimal("100"),
                new BigDecimal("106"),
                new BigDecimal("96"),
                new BigDecimal("101.76"),
                trailingEntry.volume(),
                trailingEntry.amount(),
                trailingEntry.turnoverRate()));
        BacktestModels.Trade trailing = engine
                .run(trailingBars, request)
                .trades()
                .get(0);
        assertEquals("TRAILING_STOP", trailing.exitReason());
        assertEquals(
                0,
                trailing.exitPrice().compareTo(new BigDecimal("101.76")));

        List<Bar> maxHoldBars = risingBars(40);
        BacktestModels.Trade maxHold = engine
                .run(maxHoldBars, request)
                .trades()
                .get(0);
        assertEquals("MAX_HOLD", maxHold.exitReason());
        assertEquals(
                maxHoldBars.get(31).tradeDate(),
                maxHold.exitDate());
    }

    @Test
    void rejectsInvalidBarsOrderAndParameters() {
        List<Bar> bars = risingBars(40);
        Collections.swap(bars, 5, 6);
        assertThrows(IllegalArgumentException.class, () -> engine.run(bars, request));

        BacktestModels.Request invalid = new BacktestModels.Request(
                BigDecimal.ZERO,
                0,
                new BigDecimal("1.00"),
                new BigDecimal("0.08"),
                new BigDecimal("0.04"),
                new BigDecimal("0.0003"),
                new BigDecimal("0.0005"));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.run(risingBars(40), invalid));
        assertThrows(
                IllegalArgumentException.class,
                () -> engine.run(risingBars(29), request));
    }

    private static List<Bar> risingBars(int count) {
        List<Bar> bars = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("80")
                    .add(new BigDecimal("0.50").multiply(BigDecimal.valueOf(index)));
            bars.add(new Bar(
                    "600001",
                    start.plusDays(index),
                    close,
                    close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE),
                    close,
                    10_000L + index,
                    new BigDecimal("1000000"),
                    new BigDecimal("0.50")));
        }
        return bars;
    }
}
