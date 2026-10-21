package com.stockquant.server.production;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Snapshot;
import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Status;

/** Thin manual entry point over the existing formal maintenance dispatch. */
@Service
@ConditionalOnProperty(
        prefix = "stockquant.market-data-maintenance.scheduler",
        name = "enabled", havingValue = "true")
public final class ManualMarketDataMaintenanceService {
    private static final String WINDOW_INVALID =
            "AUTOMATIC_MARKET_DATA_MAINTENANCE_WINDOW_INVALID";

    private final AutomaticMarketDataMaintenanceDispatchGateway dispatcher;
    private final AutomaticMarketDataMaintenanceStatusQuery statusQuery;
    private final AutomaticMarketDataMaintenanceProperties properties;
    private final Clock clock;
    private final AutomaticMarketDataMaintenanceRuntimeState runtimeState;

    public ManualMarketDataMaintenanceService(
            AutomaticMarketDataMaintenanceDispatchGateway dispatcher,
            AutomaticMarketDataMaintenanceStatusQuery statusQuery,
            AutomaticMarketDataMaintenanceProperties properties,
            @Qualifier("agentTemporalClock") Clock clock,
            AutomaticMarketDataMaintenanceRuntimeState runtimeState
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.statusQuery = Objects.requireNonNull(statusQuery, "statusQuery");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeState = Objects.requireNonNull(runtimeState,
                "runtimeState");
        properties.validate();
    }

    public Snapshot syncLatest() {
        var submittedAt = clock.instant();
        var now = submittedAt.atZone(ZoneId.of(properties.getZone()));
        LocalDate tradeDate = now.toLocalDate();
        if (!AutomaticDailyMarketDataMaintenanceScheduler
                .eligibleWeekday(tradeDate)
                || !withinWindow(now.toLocalTime())) {
            runtimeState.checked(submittedAt, tradeDate, null,
                    WINDOW_INVALID);
            return Snapshot.notRun(tradeDate, "NONE", WINDOW_INVALID);
        }

        Snapshot existing = statusQuery.query(tradeDate);
        if (existing.status() != Status.NOT_RUN) {
            runtimeState.checked(submittedAt, tradeDate,
                    existing.requestId(), existing.reason());
            return existing;
        }

        try {
            var dispatched = dispatcher.dispatch(tradeDate, submittedAt);
            runtimeState.checked(clock.instant(), tradeDate,
                    dispatched.requestId(), dispatched.reason());
            Snapshot observed = statusQuery.query(tradeDate);
            if (observed.status() != Status.NOT_RUN) {
                return observed;
            }
            Status fallback = dispatched.accepted() ? Status.SUBMITTED
                    : fallbackStatus(dispatched.reason());
            return new Snapshot(fallback, dispatched.requestId(), tradeDate,
                    dispatched.reason(), false, null, null, null, null,
                    null);
        } catch (RuntimeException error) {
            String reason = safeCode(error);
            runtimeState.checked(clock.instant(), tradeDate, null, reason);
            return new Snapshot(Status.FAILED, "NONE", tradeDate, reason,
                    false, null, null, null, null, null);
        }
    }

    public Snapshot status() {
        var now = clock.instant().atZone(ZoneId.of(properties.getZone()));
        Snapshot persisted = statusQuery.query(now.toLocalDate());
        if (persisted.status() != Status.NOT_RUN) {
            return persisted;
        }
        var runtime = runtimeState.snapshot();
        if (!now.toLocalDate().equals(runtime.tradeDate())) {
            return persisted;
        }
        if (WINDOW_INVALID.equals(runtime.lastReason())) {
            return Snapshot.notRun(now.toLocalDate(), "NONE",
                    WINDOW_INVALID);
        }
        if (!"NONE".equals(runtime.requestId())) {
            return new Snapshot(Status.SUBMITTED, runtime.requestId(),
                    now.toLocalDate(), runtime.lastReason(), false,
                    null, null, null, null, null);
        }
        if (!"AWAITING_SCHEDULE".equals(runtime.lastReason())) {
            return new Snapshot(Status.FAILED, "NONE", now.toLocalDate(),
                    runtime.lastReason(), false, null, null, null, null,
                    null);
        }
        return persisted;
    }

    private boolean withinWindow(LocalTime time) {
        return !time.isBefore(properties.getSafeWindowStart())
                && !time.isAfter(properties.getSafeWindowEnd());
    }

    private static Status fallbackStatus(String reason) {
        return "AUTOMATIC_MARKET_DATA_MAINTENANCE_ALREADY_SUBMITTED"
                .equals(reason) ? Status.IN_PROGRESS : Status.FAILED;
    }

    private static String safeCode(Throwable error) {
        String message = error.getMessage();
        return message != null
                && message.matches("[A-Z][A-Z0-9_]{3,127}")
                ? message
                : "AUTOMATIC_MARKET_DATA_MAINTENANCE_DISPATCH_FAILED";
    }
}
