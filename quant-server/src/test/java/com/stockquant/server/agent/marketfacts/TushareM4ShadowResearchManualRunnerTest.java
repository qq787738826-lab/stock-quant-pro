package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareM4ShadowResearchManualRunnerTest {
    @Test
    void nextExecutionDateCanOnlyBeResolvedInternally() {
        var parsed = TushareM4ShadowResearchManualRunner.Arguments.parse(
                arguments("INTERNAL_CALENDAR", "SCHEDULED"));

        assertEquals(TushareM4ShadowResearchManualRunner.CalendarAdmission
                .UNKNOWN, parsed.calendarAdmission());
        assertEquals("2025-02-09",
                parsed.calendarHorizonEnd().toString());
        assertThrows(IllegalStateException.class, () ->
                TushareM4ShadowResearchManualRunner.Arguments.parse(
                        arguments("NONE", "SCHEDULED")));
        assertThrows(IllegalStateException.class, () ->
                TushareM4ShadowResearchManualRunner.Arguments.parse(
                        arguments("2025-01-13", "SCHEDULED")));
    }

    @Test
    void historicalReplayUsesHistoricalCloseWhileScheduledUsesWallClock() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T13:00:00Z"),
                ZoneOffset.UTC);
        var replay = TushareM4ShadowResearchManualRunner.Arguments.parse(
                arguments("INTERNAL_CALENDAR", "HISTORICAL_REPLAY"));
        var scheduled = TushareM4ShadowResearchManualRunner.Arguments.parse(
                arguments("INTERNAL_CALENDAR", "SCHEDULED"));

        assertEquals(com.stockquant.core.research.StrategyResearchModels
                        .closeInstant(replay.tradeDate()),
                TushareM4ShadowResearchManualRunner.researchAsOf(
                        replay, clock));
        assertEquals(clock.instant(),
                TushareM4ShadowResearchManualRunner.researchAsOf(
                        scheduled, clock));
        assertEquals(com.stockquant.core.research.StrategyResearchModels
                        .closeInstant(replay.tradeDate()),
                TushareM4ShadowResearchManualRunner.factClock(replay, clock)
                        .instant());
        assertEquals(clock, TushareM4ShadowResearchManualRunner.factClock(
                scheduled, clock));
    }

    private static String[] arguments(String nextDate, String triggerMode) {
        return new String[]{
                "--result-file=target/m4-test.json",
                "--execution-id=M4SHADOW_20260812T010203Z_A1B2C3D4E5F6",
                "--database-port=55432", "--execution-mode=FAKE",
                "--securities=600000:SSE,000001:SZSE",
                "--range-start=2025-01-02", "--trade-date=2025-01-10",
                "--next-trade-date=" + nextDate,
                "--calendar-admission=UNKNOWN",
                "--calendar-horizon-end=2025-02-09",
                "--capture-mode=CAPTURE", "--trigger-mode=" + triggerMode,
                "--maximum-cost-cny=5.00"
        };
    }
}
