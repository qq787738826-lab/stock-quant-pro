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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        assertEquals(LocalDate.of(2026, 10, 16),
                resolver.resolve(at(monday, 10, 0)));
    }

    @Test
    void tradingDayAt1530UsesPreviousCommonOpenDate() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertEquals(LocalDate.of(2026, 10, 20),
                resolver.resolve(at(today, 15, 30)));
    }

    @Test
    void tradingDayAtSafeWindowStartUsesToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertEquals(today, resolver.resolve(at(today, 16, 25)));
    }

    @Test
    void tradingDayAfterSafeWindowStartUsesToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertEquals(today, resolver.resolve(at(today, 16, 30)));
    }

    @Test
    void tradingDayAfterAutomaticWindowStillUsesToday() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        var resolver = openResolver(LocalDate.of(2026, 10, 20), today);

        assertEquals(today, resolver.resolve(at(today, 20, 0)));
    }

    @Test
    void saturdayUsesMostRecentCommonOpenDate() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        var resolver = commonCalendar(friday,
                LocalDate.of(2026, 10, 24),
                ManualMarketDataTargetResolverTest::weekday);

        assertEquals(friday, resolver.resolve(at(
                LocalDate.of(2026, 10, 24), 18, 0)));
    }

    @Test
    void sundayUsesMostRecentCommonOpenDate() {
        LocalDate friday = LocalDate.of(2026, 10, 23);
        var resolver = commonCalendar(friday,
                LocalDate.of(2026, 10, 25),
                ManualMarketDataTargetResolverTest::weekday);

        assertEquals(friday, resolver.resolve(at(
                LocalDate.of(2026, 10, 25), 18, 0)));
    }

    @Test
    void statutoryHolidayUsesLastCommonOpenDate() {
        LocalDate previous = LocalDate.of(2026, 9, 30);
        LocalDate holiday = LocalDate.of(2026, 10, 7);
        Predicate<LocalDate> open = date -> date.equals(previous);
        Map<LocalDate, Boolean> values = calendar(previous, holiday, open);
        var resolver = resolver(values, values);

        assertEquals(previous, resolver.resolve(at(holiday, 18, 0)));
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

        assertEquals(previous, resolver.resolve(at(today, 16, 30)));
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
