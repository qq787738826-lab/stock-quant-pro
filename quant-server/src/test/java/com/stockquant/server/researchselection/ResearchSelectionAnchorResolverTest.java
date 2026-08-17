package com.stockquant.server.researchselection;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResearchSelectionAnchorResolverTest {
    private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 14);
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);

    @Test
    void preOpenUsesMostRecentCompletedSession() {
        assertEquals(FRIDAY, resolve("2026-08-17T00:00:00Z",
                tradingDays(LocalDate.of(2026, 6, 1), MONDAY),
                Set.of(FRIDAY)));
    }

    @Test
    void intradayUsesMostRecentCompletedSession() {
        assertEquals(FRIDAY, resolve("2026-08-17T02:15:00Z",
                tradingDays(LocalDate.of(2026, 6, 1), MONDAY),
                Set.of(FRIDAY)));
    }

    @Test
    void afterCloseFallsBackWhileCurrentDailyFactsAreIncomplete() {
        assertEquals(FRIDAY, resolve("2026-08-17T08:00:00Z",
                tradingDays(LocalDate.of(2026, 6, 1), MONDAY),
                Set.of(FRIDAY)));
    }

    @Test
    void afterCloseUsesCurrentSessionOnlyWhenItsWindowIsComplete() {
        assertEquals(MONDAY, resolve("2026-08-17T08:00:00Z",
                tradingDays(LocalDate.of(2026, 6, 1), MONDAY),
                Set.of(FRIDAY, MONDAY)));
    }

    @Test
    void weekendUsesFridayAndIsDeterministicAcrossRepeatedClicks() {
        Set<LocalDate> open = tradingDays(LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 8, 16));
        LocalDate first = resolve("2026-08-16T02:00:00Z", open,
                Set.of(FRIDAY));
        LocalDate second = resolve("2026-08-16T02:00:00Z", open,
                Set.of(FRIDAY));
        assertEquals(FRIDAY, first);
        assertEquals(first, second);
    }

    @Test
    void exchangeHolidayUsesLastCommonOpenSession() {
        Set<LocalDate> open = tradingDays(LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 9));
        for (LocalDate date = LocalDate.of(2026, 10, 1);
                !date.isAfter(LocalDate.of(2026, 10, 8));
                date = date.plusDays(1)) {
            open.remove(date);
        }
        LocalDate september30 = LocalDate.of(2026, 9, 30);
        assertEquals(september30, resolve("2026-10-01T02:00:00Z",
                open, Set.of(september30)));
    }

    @Test
    void noCompleteLocalWindowPreservesNewestClosedAnchorForBoundedCapture() {
        assertEquals(MONDAY, resolve("2026-08-17T08:00:00Z",
                tradingDays(LocalDate.of(2026, 6, 1), MONDAY), Set.of()));
    }

    private static LocalDate resolve(
            String instant,
            Set<LocalDate> open,
            Set<LocalDate> complete
    ) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return ResearchSelectionAnchorResolver.resolve(clock,
                notAfter -> open.stream().filter(date ->
                                !date.isAfter(notAfter))
                        .max(LocalDate::compareTo).orElseThrow(),
                complete::contains);
    }

    private static Set<LocalDate> tradingDays(
            LocalDate start,
            LocalDate end
    ) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (LocalDate date = start; !date.isAfter(end);
                date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() <= 5) {
                result.add(date);
            }
        }
        return result;
    }
}
