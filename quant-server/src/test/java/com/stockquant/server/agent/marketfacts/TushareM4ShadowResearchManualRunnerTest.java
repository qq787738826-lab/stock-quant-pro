package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareM4ShadowResearchManualRunnerTest {
    @Test
    void nextExecutionDateCanOnlyBeResolvedInternally() {
        var parsed = TushareM4ShadowResearchManualRunner.Arguments.parse(
                arguments("INTERNAL_CALENDAR"));

        assertEquals(TushareM4ShadowResearchManualRunner.CalendarAdmission
                .UNKNOWN, parsed.calendarAdmission());
        assertEquals("2025-02-09",
                parsed.calendarHorizonEnd().toString());
        assertThrows(IllegalStateException.class, () ->
                TushareM4ShadowResearchManualRunner.Arguments.parse(
                        arguments("NONE")));
        assertThrows(IllegalStateException.class, () ->
                TushareM4ShadowResearchManualRunner.Arguments.parse(
                        arguments("2025-01-13")));
    }

    private static String[] arguments(String nextDate) {
        return new String[]{
                "--result-file=target/m4-test.json",
                "--execution-id=M4SHADOW_20260812T010203Z_A1B2C3D4E5F6",
                "--database-port=55432", "--execution-mode=FAKE",
                "--securities=600000:SSE,000001:SZSE",
                "--range-start=2025-01-02", "--trade-date=2025-01-10",
                "--next-trade-date=" + nextDate,
                "--calendar-admission=UNKNOWN",
                "--calendar-horizon-end=2025-02-09",
                "--capture-mode=CAPTURE", "--trigger-mode=SCHEDULED",
                "--maximum-cost-cny=5.00"
        };
    }
}
