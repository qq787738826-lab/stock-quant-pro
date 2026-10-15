package com.stockquant.server.production;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One deterministic after-close submission; execution remains Broker-only. */
@Component
@ConditionalOnProperty(
        prefix = "stockquant.market-data-maintenance.scheduler",
        name = "enabled", havingValue = "true")
public final class AutomaticDailyMarketDataMaintenanceScheduler {
    private final AutomaticMarketDataMaintenanceDispatchGateway dispatcher;
    private final AutomaticMarketDataMaintenanceProperties properties;
    private final Clock clock;
    private final AutomaticMarketDataMaintenanceRuntimeState runtimeState;
    private final AtomicBoolean dispatching = new AtomicBoolean();

    public AutomaticDailyMarketDataMaintenanceScheduler(
            AutomaticMarketDataMaintenanceDispatchGateway dispatcher,
            AutomaticMarketDataMaintenanceProperties properties,
            @Qualifier("agentTemporalClock") Clock clock,
            AutomaticMarketDataMaintenanceRuntimeState runtimeState
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeState = Objects.requireNonNull(runtimeState,
                "runtimeState");
        properties.validate();
    }

    @Scheduled(cron = "${stockquant.market-data-maintenance.scheduler.cron:0 30 16 * * MON-FRI}",
            zone = "${stockquant.market-data-maintenance.scheduler.zone:Asia/Shanghai}")
    public void dispatchAfterClose() {
        var instant = clock.instant();
        var now = instant.atZone(ZoneId.of(properties.getZone()));
        LocalDate date = now.toLocalDate();
        if (!eligibleWeekday(date)) {
            runtimeState.checked(instant, date, null,
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_WEEKEND_SKIP");
            return;
        }
        if (!withinWindow(now.toLocalTime())) {
            runtimeState.checked(instant, date, null,
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_OUTSIDE_WINDOW");
            return;
        }
        if (!dispatching.compareAndSet(false, true)) {
            runtimeState.checked(instant, date, null,
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_DISPATCH_IN_PROGRESS");
            return;
        }
        try {
            var result = dispatcher.dispatch(date, instant);
            runtimeState.checked(clock.instant(), date, result.requestId(),
                    result.reason());
        } catch (RuntimeException error) {
            runtimeState.checked(clock.instant(), date, null,
                    safeCode(error));
        } finally {
            dispatching.set(false);
        }
    }

    boolean withinWindow(LocalTime time) {
        return !time.isBefore(properties.getSafeWindowStart())
                && !time.isAfter(properties.getSafeWindowEnd());
    }

    static boolean eligibleWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private static String safeCode(Throwable error) {
        String message = error.getMessage();
        return message != null && message.matches(
                "[A-Z][A-Z0-9_]{3,127}")
                ? message
                : "AUTOMATIC_MARKET_DATA_MAINTENANCE_DISPATCH_FAILED";
    }
}
