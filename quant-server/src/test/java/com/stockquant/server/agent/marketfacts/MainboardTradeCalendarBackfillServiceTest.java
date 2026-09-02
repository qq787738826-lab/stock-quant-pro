package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardTradeCalendarBackfillServiceTest {

    @Test
    void intersectionUsesOnlyCommonOfficialOpenSessions() {
        List<LocalDate> weekdays = weekdaysEndingAt(
                LocalDate.of(2026, 8, 27), 270);
        Set<LocalDate> sse = new LinkedHashSet<>(weekdays);
        Set<LocalDate> szse = new LinkedHashSet<>(weekdays);
        LocalDate exchangeSpecificClosure = weekdays.get(8);
        szse.remove(exchangeSpecificClosure);

        List<LocalDate> common =
                MainboardTradeCalendarBackfillService.commonOpenDates(
                        sse, szse);
        List<LocalDate> target =
                MainboardTradeCalendarBackfillService.target250(common);

        assertEquals(269, common.size());
        assertFalse(common.contains(exchangeSpecificClosure));
        assertEquals(250, target.size());
        assertEquals(LocalDate.of(2026, 8, 27), target.get(249));
        assertTrue(target.stream().allMatch(date ->
                date.getDayOfWeek() != DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != DayOfWeek.SUNDAY));
    }

    @Test
    void oneExchangeCannotManufactureACompleteTargetWindow() {
        List<LocalDate> sseDates = weekdaysEndingAt(
                LocalDate.of(2026, 8, 27), 270);
        List<LocalDate> szseDates = sseDates.subList(21, 270);
        List<LocalDate> common =
                MainboardTradeCalendarBackfillService.commonOpenDates(
                        Set.copyOf(sseDates), Set.copyOf(szseDates));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> MainboardTradeCalendarBackfillService.target250(common));

        assertEquals(249, common.size());
        assertEquals("MAINBOARD_TRADE_CAL_BACKFILL_TARGET_INCOMPLETE",
                failure.getMessage());
    }

    @Test
    void fakeSeedWindowContainsExactlySixtyOpenWeekdays() {
        LocalDate anchor = LocalDate.of(2026, 8, 27);
        LocalDate start = TushareMainboardTradeCalendarBackfillManualRunner
                .lastWeekdayWindowStart(anchor, 60);

        assertEquals(60, weekdaysBetween(start, anchor));
        assertEquals(500, ChronoUnit.DAYS.between(
                anchor.minusDays(MainboardTradeCalendarBackfillService
                        .CALENDAR_LOOKBACK_DAYS), anchor) + 1);
    }

    private static List<LocalDate> weekdaysEndingAt(
            LocalDate end,
            int count
    ) {
        LinkedHashSet<LocalDate> values = new LinkedHashSet<>();
        LocalDate date = end;
        while (values.size() < count) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                values.add(date);
            }
            date = date.minusDays(1);
        }
        return values.stream().sorted().toList();
    }

    private static int weekdaysBetween(LocalDate start, LocalDate end) {
        int result = 0;
        for (LocalDate date = start; !date.isAfter(end);
             date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                result++;
            }
        }
        return result;
    }
}
