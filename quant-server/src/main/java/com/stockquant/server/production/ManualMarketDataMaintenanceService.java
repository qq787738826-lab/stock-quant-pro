package com.stockquant.server.production;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
    private final AutomaticMarketDataMaintenanceDispatchGateway dispatcher;
    private final AutomaticMarketDataMaintenanceStatusQuery statusQuery;
    private final AutomaticMarketDataMaintenanceProperties properties;
    private final Clock clock;
    private final AutomaticMarketDataMaintenanceRuntimeState runtimeState;
    private final ManualMarketDataTargetResolver targetResolver;

    public ManualMarketDataMaintenanceService(
            AutomaticMarketDataMaintenanceDispatchGateway dispatcher,
            AutomaticMarketDataMaintenanceStatusQuery statusQuery,
            AutomaticMarketDataMaintenanceProperties properties,
            @Qualifier("agentTemporalClock") Clock clock,
            AutomaticMarketDataMaintenanceRuntimeState runtimeState,
            ManualMarketDataTargetResolver targetResolver
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.statusQuery = Objects.requireNonNull(statusQuery, "statusQuery");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeState = Objects.requireNonNull(runtimeState,
                "runtimeState");
        this.targetResolver = Objects.requireNonNull(targetResolver,
                "targetResolver");
        properties.validate();
    }

    public Snapshot syncLatest() {
        var submittedAt = clock.instant();
        LocalDate tradeDate;
        try {
            tradeDate = targetResolver.resolve(submittedAt);
        } catch (RuntimeException error) {
            String reason = ManualMarketDataTargetResolver
                    .TARGET_UNAVAILABLE;
            LocalDate referenceDate = localDate(submittedAt);
            runtimeState.checked(submittedAt, referenceDate, null, reason);
            return Snapshot.notRun(referenceDate, "NONE", reason);
        }

        Snapshot existing = statusQuery.query(tradeDate);
        if (existing.status() != Status.NOT_RUN) {
            runtimeState.checked(submittedAt, tradeDate,
                    existing.requestId(), existing.reason());
            return existing;
        }

        try {
            var dispatched = dispatcher.dispatchManual(tradeDate,
                    submittedAt);
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
        Instant checkedAt = clock.instant();
        LocalDate tradeDate;
        try {
            tradeDate = targetResolver.resolve(checkedAt);
        } catch (RuntimeException error) {
            return Snapshot.notRun(localDate(checkedAt), "NONE",
                    ManualMarketDataTargetResolver.TARGET_UNAVAILABLE);
        }
        Snapshot persisted = statusQuery.query(tradeDate);
        if (persisted.status() != Status.NOT_RUN) {
            return persisted;
        }
        var runtime = runtimeState.snapshot();
        if (!tradeDate.equals(runtime.tradeDate())) {
            return persisted;
        }
        if (!"NONE".equals(runtime.requestId())) {
            return new Snapshot(Status.SUBMITTED, runtime.requestId(),
                    tradeDate, runtime.lastReason(), false,
                    null, null, null, null, null);
        }
        if (!"AWAITING_SCHEDULE".equals(runtime.lastReason())) {
            return new Snapshot(Status.FAILED, "NONE", tradeDate,
                    runtime.lastReason(), false, null, null, null, null,
                    null);
        }
        return persisted;
    }

    private LocalDate localDate(Instant instant) {
        return instant.atZone(ZoneId.of(properties.getZone())).toLocalDate();
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
