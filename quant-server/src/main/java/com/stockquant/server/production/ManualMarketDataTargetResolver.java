package com.stockquant.server.production;

import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves the newest locally known common SSE/SZSE manual-sync date. */
@Component
@ConditionalOnProperty(
        prefix = "stockquant.market-data-maintenance.scheduler",
        name = "enabled", havingValue = "true")
public final class ManualMarketDataTargetResolver {
    public static final String TARGET_UNAVAILABLE =
            "MANUAL_MARKET_DATA_TARGET_TRADE_DATE_UNAVAILABLE";
    private static final int MAXIMUM_CALENDAR_LOOKBACK_DAYS = 500;

    private final CalendarLookup calendars;
    private final AutomaticMarketDataMaintenanceProperties properties;
    private final ZoneId zone;

    @Autowired
    public ManualMarketDataTargetResolver(
            PitMarketFactRepository facts,
            AutomaticMarketDataMaintenanceProperties properties
    ) {
        this((exchange, from, to, cutoff) -> facts.findCalendarAsOf(
                        TushareMarketFactProvider.PROVIDER_CODE,
                        TushareMarketFactProvider.calendarSourceIdentity(
                                exchange),
                        exchange, from, to, cutoff).stream()
                .map(value -> new CalendarDay(value.exchange(),
                        value.calendarDate(), value.open()))
                .toList(), properties);
    }

    ManualMarketDataTargetResolver(
            CalendarLookup calendars,
            AutomaticMarketDataMaintenanceProperties properties
    ) {
        this.calendars = Objects.requireNonNull(calendars, "calendars");
        this.properties = Objects.requireNonNull(properties, "properties");
        properties.validate();
        this.zone = ZoneId.of(properties.getZone());
    }

    public LocalDate resolve(Instant submittedAt) {
        Objects.requireNonNull(submittedAt, "submittedAt");
        var now = submittedAt.atZone(zone);
        LocalDate today = now.toLocalDate();
        LocalDate start = today.minusDays(
                MAXIMUM_CALENDAR_LOOKBACK_DAYS - 1L);
        Map<LocalDate, CalendarDay> sse = calendar("SSE", start, today,
                submittedAt);
        Map<LocalDate, CalendarDay> szse = calendar("SZSE", start, today,
                submittedAt);
        LocalDate candidate = now.toLocalTime().isBefore(
                properties.getSafeWindowStart())
                ? today.minusDays(1) : today;
        while (!candidate.isBefore(start)) {
            CalendarDay sseDay = sse.get(candidate);
            CalendarDay szseDay = szse.get(candidate);
            if (sseDay == null || szseDay == null) {
                throw unavailable(null);
            }
            if (sseDay.open() && szseDay.open()) {
                return candidate;
            }
            candidate = candidate.minusDays(1);
        }
        throw unavailable(null);
    }

    private Map<LocalDate, CalendarDay> calendar(
            String exchange,
            LocalDate start,
            LocalDate end,
            Instant cutoff
    ) {
        List<CalendarDay> values;
        try {
            values = calendars.find(exchange, start, end, cutoff);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
        if (values == null) {
            throw unavailable(null);
        }
        Map<LocalDate, CalendarDay> result = new LinkedHashMap<>();
        for (CalendarDay value : values) {
            if (value == null || !exchange.equals(value.exchange())
                    || value.date().isBefore(start)
                    || value.date().isAfter(end)
                    || result.put(value.date(), value) != null) {
                throw unavailable(null);
            }
        }
        return Map.copyOf(result);
    }

    private static IllegalStateException unavailable(Throwable cause) {
        return cause == null ? new IllegalStateException(TARGET_UNAVAILABLE)
                : new IllegalStateException(TARGET_UNAVAILABLE, cause);
    }

    @FunctionalInterface
    interface CalendarLookup {
        List<CalendarDay> find(
                String exchange,
                LocalDate from,
                LocalDate to,
                Instant cutoff
        );
    }

    record CalendarDay(String exchange, LocalDate date, boolean open) {
        CalendarDay {
            Objects.requireNonNull(exchange, "exchange");
            Objects.requireNonNull(date, "date");
        }
    }
}
