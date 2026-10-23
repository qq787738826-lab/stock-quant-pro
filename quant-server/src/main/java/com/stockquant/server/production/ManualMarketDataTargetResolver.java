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

/** Resolves a stable manual-sync request date and its known trade date. */
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

    public Resolution resolve(Instant submittedAt) {
        Objects.requireNonNull(submittedAt, "submittedAt");
        var now = submittedAt.atZone(zone);
        LocalDate today = now.toLocalDate();
        LocalDate coverageEnd = now.toLocalTime().isBefore(
                properties.getSafeWindowStart())
                ? today.minusDays(1) : today;
        LocalDate start = coverageEnd.minusDays(
                MAXIMUM_CALENDAR_LOOKBACK_DAYS - 1L);
        Map<LocalDate, CalendarDay> sse = calendar("SSE", start,
                coverageEnd, submittedAt);
        Map<LocalDate, CalendarDay> szse = calendar("SZSE", start,
                coverageEnd, submittedAt);
        LocalDate sseMaximum = maximum(sse);
        LocalDate szseMaximum = maximum(szse);
        if (!sseMaximum.equals(szseMaximum)) {
            throw unavailable(null);
        }
        latestCommonOpen(sse, szse, sseMaximum, start);
        if (sseMaximum.isBefore(coverageEnd)) {
            return Resolution.bootstrap(coverageEnd);
        }
        LocalDate effectiveTradeDate = latestCommonOpen(sse, szse,
                coverageEnd, start);
        return Resolution.known(coverageEnd, effectiveTradeDate);
    }

    private static LocalDate maximum(Map<LocalDate, CalendarDay> values) {
        return values.keySet().stream().max(LocalDate::compareTo)
                .orElseThrow(() -> unavailable(null));
    }

    private static LocalDate latestCommonOpen(
            Map<LocalDate, CalendarDay> sse,
            Map<LocalDate, CalendarDay> szse,
            LocalDate candidate,
            LocalDate start
    ) {
        LocalDate value = candidate;
        while (!value.isBefore(start)) {
            CalendarDay sseDay = sse.get(value);
            CalendarDay szseDay = szse.get(value);
            if (sseDay == null || szseDay == null) {
                throw unavailable(null);
            }
            if (sseDay.open() && szseDay.open()) {
                return value;
            }
            value = value.minusDays(1);
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

    /**
     * coverageEndDate is the stable request identity. When local calendars
     * already cover it, dispatchTargetDate remains the actual common open
     * trade date for backward compatibility. A stale but aligned calendar
     * instead dispatches the coverage end so the Broker's first stage can
     * append the missing calendar tail before resolving open dates.
     */
    public record Resolution(
            LocalDate coverageEndDate,
            LocalDate dispatchTargetDate,
            LocalDate effectiveTargetTradeDate,
            boolean calendarBootstrapRequired
    ) {
        public Resolution {
            Objects.requireNonNull(coverageEndDate, "coverageEndDate");
            Objects.requireNonNull(dispatchTargetDate,
                    "dispatchTargetDate");
            if (dispatchTargetDate.isAfter(coverageEndDate)
                    || (effectiveTargetTradeDate != null
                    && effectiveTargetTradeDate.isAfter(coverageEndDate))
                    || calendarBootstrapRequired
                    != (effectiveTargetTradeDate == null)
                    || (calendarBootstrapRequired
                    && !dispatchTargetDate.equals(coverageEndDate))
                    || (!calendarBootstrapRequired
                    && !dispatchTargetDate.equals(
                    effectiveTargetTradeDate))) {
                throw new IllegalArgumentException(
                        "MANUAL_MARKET_DATA_TARGET_RESOLUTION_INVALID");
            }
        }

        public static Resolution known(
                LocalDate coverageEndDate,
                LocalDate effectiveTargetTradeDate
        ) {
            return new Resolution(coverageEndDate,
                    effectiveTargetTradeDate, effectiveTargetTradeDate,
                    false);
        }

        public static Resolution bootstrap(LocalDate coverageEndDate) {
            return new Resolution(coverageEndDate, coverageEndDate, null,
                    true);
        }
    }
}
