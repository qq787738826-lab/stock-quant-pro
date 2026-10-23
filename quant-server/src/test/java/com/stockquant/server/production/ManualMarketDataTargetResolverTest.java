package com.stockquant.server.production;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualMarketDataTargetResolverTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void mondayMorningUsesPreviousFriday() {
        LocalDate monday = LocalDate.of(2026, 10, 19);
        var resolver = resolver(
                calendar(LocalDate.of(2026, 10, 16), monday,
                        ManualMarketDataTargetResolverTest::weekday),
                calendar(LocalDate.of(2026, 10, 16), monday,
                        ManualMarketDataTargetResolverTest::weekday));

        assertKnown(resolver.resolve(at(monday, 10, 0)),
                LocalDate.of(2026, 10, 18),
                LocalDate.of(2026, 10, 16));
    }

    @Test
    void tradingDayAt1530UsesPreviousCommonOpenDate() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertKnown(resolver.resolve(at(today, 15, 30)),
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 20));
    }

    @Test
    void tradingDayAtSafeWindowStartUsesToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertKnown(resolver.resolve(at(today, 16, 25)), today, today);
    }

    @Test
    void tradingDayAfterSafeWindowStartUsesToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertKnown(resolver.resolve(at(today, 16, 30)), today, today);
    }

    @Test
    void tradingDayAfterAutomaticWindowStillUsesToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertKnown(resolver.resolve(at(today, 20, 0)), today, today);
    }

    @Test
    void saturdayUsesMostRecentCommonOpenDate() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        var resolver = commonCalendar(friday,
                LocalDate.of(2026, 10, 24),
                ManualMarketDataTargetResolverTest::weekday);

        assertKnown(resolver.resolve(at(
                        LocalDate.of(2026, 10, 24), 18, 0)),
                LocalDate.of(2026, 10, 24), friday);
    }

    @Test
    void sundayUsesMostRecentCommonOpenDate() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        var resolver = commonCalendar(friday,
                LocalDate.of(2026, 10, 25),
                ManualMarketDataTargetResolverTest::weekday);

        assertKnown(resolver.resolve(at(
                        LocalDate.of(2026, 10, 25), 18, 0)),
                LocalDate.of(2026, 10, 25), friday);
    }

    @Test
    void statutoryHolidayUsesLastCommonOpenDate() {
        LocalDate previous = LocalDate.of(2026, 9, 30);
        LocalDate holiday = LocalDate.of(2026, 10, 7);
        Predicate<LocalDate> open = date -> date.equals(previous);
        Map<LocalDate, Boolean> values = calendar(previous, holiday, open);
        var resolver = resolver(values, values);

        assertKnown(resolver.resolve(at(holiday, 18, 0)), holiday,
                previous);
    }

    @Test
    void exchangeMismatchUsesOnlyMostRecentCommonOpenDate() {
        LocalDate previous = LocalDate.of(2026, 10, 20);
        LocalDate today = LocalDate.of(2026, 10, 21);
        Map<LocalDate, Boolean> sse = calendar(previous, today,
                ignored -> true);
        Map<LocalDate, Boolean> szse = calendar(previous, today,
                date -> date.equals(previous));
        var resolver = resolver(sse, szse);

        assertKnown(resolver.resolve(at(today, 16, 30)), today, previous);
    }

    @Test
    void alignedStaleCalendarBootstrapsStableCoverageEnd() {
        LocalDate latestKnown = LocalDate.of(2026, 10, 20);
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = commonCalendar(latestKnown.minusDays(1), latestKnown,
                ignored -> true);

        ManualMarketDataTargetResolver.Resolution resolution =
                resolver.resolve(at(today, 16, 30));

        assertEquals(today, resolution.coverageEndDate());
        assertEquals(today, resolution.dispatchTargetDate());
        assertNull(resolution.effectiveTargetTradeDate());
        assertTrue(resolution.calendarBootstrapRequired());
    }

    @Test
    void beforeCutoffBootstrapsOnlyThroughPreviousNaturalDate() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        LocalDate latestKnown = LocalDate.of(2026, 10, 19);
        var resolver = commonCalendar(latestKnown.minusDays(1), latestKnown,
                ignored -> true);

        ManualMarketDataTargetResolver.Resolution resolution =
                resolver.resolve(at(today, 10, 0));

        assertEquals(LocalDate.of(2026, 10, 20),
                resolution.coverageEndDate());
        assertEquals(LocalDate.of(2026, 10, 20),
                resolution.dispatchTargetDate());
        assertNull(resolution.effectiveTargetTradeDate());
        assertTrue(resolution.calendarBootstrapRequired());
    }

    @Test
    void incompleteCalendarFailsClosed() {
        LocalDate previous = LocalDate.of(2026, 10, 20);
        LocalDate today = LocalDate.of(2026, 10, 21);
        Map<LocalDate, Boolean> sse = calendar(previous, today,
                ignored -> true);
        Map<LocalDate, Boolean> szse = calendar(previous, previous,
                ignored -> true);
        var resolver = resolver(sse, szse);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(at(today, 16, 30)));

        assertEquals(ManualMarketDataTargetResolver.TARGET_UNAVAILABLE,
                error.getMessage());
    }

    @Test
    void emptyCalendarCannotBootstrapForwardIncrement() {
        var resolver = resolver(Map.of(), Map.of());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(at(LocalDate.of(2026, 10, 21),
                        16, 30)));

        assertEquals(ManualMarketDataTargetResolver.TARGET_UNAVAILABLE,
                error.getMessage());
    }

    @Test
    void staleClosedTailWithoutKnownOpenDateFailsClosed() {
        LocalDate friday = LocalDate.of(2026, 10, 16);
        Map<LocalDate, Boolean> closed = calendar(friday,
                friday.plusDays(1), ignored -> false);
        var resolver = resolver(closed, closed);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(at(LocalDate.of(2026, 10, 21),
                        16, 30)));

        assertEquals(ManualMarketDataTargetResolver.TARGET_UNAVAILABLE,
                error.getMessage());
    }

    private static void assertKnown(
            ManualMarketDataTargetResolver.Resolution resolution,
            LocalDate coverageEnd,
            LocalDate effectiveTradeDate
    ) {
        assertEquals(coverageEnd, resolution.coverageEndDate());
        assertEquals(effectiveTradeDate, resolution.dispatchTargetDate());
        assertEquals(effectiveTradeDate,
                resolution.effectiveTargetTradeDate());
        assertFalse(resolution.calendarBootstrapRequired());
    }

    private static ManualMarketDataTargetResolver openResolver(
            LocalDate start,
            LocalDate end
    ) {
        Map<LocalDate, Boolean> values = calendar(start, end,
                ignored -> true);
        return resolver(values, values);
    }

    private static ManualMarketDataTargetResolver commonCalendar(
            LocalDate start,
            LocalDate end,
            Predicate<LocalDate> open
    ) {
        Map<LocalDate, Boolean> values = calendar(start, end, open);
        return resolver(values, values);
    }

    private static ManualMarketDataTargetResolver resolver(
            Map<LocalDate, Boolean> sse,
            Map<LocalDate, Boolean> szse
    ) {
        Map<String, Map<LocalDate, Boolean>> fixture = Map.of(
                "SSE", sse, "SZSE", szse);
        ManualMarketDataTargetResolver.CalendarLookup lookup =
                (exchange, from, to, cutoff) -> fixture.get(exchange)
                        .entrySet().stream()
                        .filter(entry -> !entry.getKey().isBefore(from)
                                && !entry.getKey().isAfter(to))
                        .map(entry -> new ManualMarketDataTargetResolver
                                .CalendarDay(exchange, entry.getKey(),
                                entry.getValue()))
                        .toList();
        return new ManualMarketDataTargetResolver(lookup,
                enabledProperties());
    }

    private static Map<LocalDate, Boolean> calendar(
            LocalDate start,
            LocalDate end,
            Predicate<LocalDate> open
    ) {
        Map<LocalDate, Boolean> result = new LinkedHashMap<>();
        start.datesUntil(end.plusDays(1))
                .forEach(date -> result.put(date, open.test(date)));
        return Map.copyOf(result);
    }

    private static boolean weekday(LocalDate date) {
        return date.getDayOfWeek().getValue() <= 5;
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SHANGHAI).toInstant();
    }

    private static AutomaticMarketDataMaintenanceProperties
    enabledProperties() {
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        return properties;
    }
}
