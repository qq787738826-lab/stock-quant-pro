package com.stockquant.core.research;

import com.stockquant.core.research.PaperExecutionEngine.Position;
import com.stockquant.core.research.PaperExecutionEngine.Request;
import com.stockquant.core.research.PaperExecutionEngine.Side;
import com.stockquant.core.research.PaperExecutionEngine.State;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.Security;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperExecutionEngineTest {
    private static final Security SECURITY = new Security("600000", "SSE");
    private static final Instant SIGNAL = Instant.parse("2025-01-03T07:00:00Z");
    private static final Instant EXECUTION = Instant.parse("2025-01-06T01:30:00Z");

    @Test
    void buysNextSessionWithM2FeesSlippageAndCashConservation() {
        var result = new PaperExecutionEngine().execute(request(
                new State(new BigDecimal("1000000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, Map.of()), Side.BUY,
                new BigDecimal("0.40")));

        assertTrue(result.fill().isPresent());
        var fill = result.fill().orElseThrow();
        assertTrue(fill.executionPrice().compareTo(fill.referencePrice()) > 0);
        assertEquals(0, fill.stampDuty().compareTo(BigDecimal.ZERO));
        assertTrue(fill.commission().signum() > 0);
        assertEquals(fill.cashAfter(), result.state().cash());
        assertTrue(fill.quantity() % 100 == 0);
    }

    @Test
    void sellAppliesStampDutyAndTPlusOne() {
        Position position = new Position(1000, 1000,
                new BigDecimal("9.00"), new BigDecimal("10.00"),
                LocalDate.of(2025, 1, 2));
        var result = new PaperExecutionEngine().execute(request(
                new State(new BigDecimal("990000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, Map.of(SECURITY, position)),
                Side.SELL, BigDecimal.ZERO));

        assertTrue(result.fill().isPresent());
        assertTrue(result.fill().orElseThrow().stampDuty().signum() > 0);
        assertTrue(result.state().realizedPnl().signum() > 0);
    }

    @Test
    void sameDayExecutionAndUnavailableTPlusOneAreRejected() {
        Request valid = request(new State(new BigDecimal("1000000"),
                BigDecimal.ZERO, BigDecimal.ZERO, Map.of()), Side.BUY,
                new BigDecimal("0.20"));
        assertThrows(IllegalArgumentException.class, () ->
                new PaperExecutionEngine().execute(new Request(
                        valid.state(), valid.config(), valid.side(),
                        valid.security(), valid.targetWeight(),
                        valid.signalDate(), valid.signalTime(),
                        valid.signalDate(), valid.signalTime().plusSeconds(1),
                        valid.referencePrice(), valid.marks())));

        Position locked = new Position(1000, 0, new BigDecimal("9"),
                new BigDecimal("10"), LocalDate.of(2025, 1, 3));
        var result = new PaperExecutionEngine().execute(request(
                new State(new BigDecimal("990000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, Map.of(SECURITY, locked)),
                Side.SELL, BigDecimal.ZERO));
        assertEquals("T_PLUS_ONE_RESTRICTED", result.rejectionReason());
    }

    private static Request request(State state, Side side,
                                   BigDecimal targetWeight) {
        return new Request(state, BacktestConfig.standard(), side, SECURITY,
                targetWeight, LocalDate.of(2025, 1, 3), SIGNAL,
                LocalDate.of(2025, 1, 6), EXECUTION,
                new BigDecimal("10.00"), Map.of(SECURITY,
                new BigDecimal("10.00")));
    }
}
