package com.stockquant.server.production;

import com.stockquant.server.production.ManualMarketDataTargetResolver.Resolution;
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
        Resolution target;
        try {
            target = targetResolver.resolve(submittedAt);
        } catch (RuntimeException error) {
            Snapshot recovered = unresolvedStatus(submittedAt);
            if (recovered.status() != Status.NOT_RUN) {
                return recovered;
            }
            runtimeState.checked(submittedAt, recovered.tradeDate(), null,
                    recovered.reason());
            return recovered;
        }

        LocalDate dispatchTarget = target.dispatchTargetDate();
        Snapshot existing = queryExisting(target);
        if (existing.status() != Status.NOT_RUN) {
            runtimeState.checked(submittedAt, existing.tradeDate(),
                    existing.requestId(), existing.reason());
            return existing;
        }

        try {
            var dispatched = dispatcher.dispatchManual(dispatchTarget,
                    submittedAt);
            runtimeState.checked(clock.instant(), dispatchTarget,
                    dispatched.requestId(), dispatched.reason());
            Snapshot observed = queryExisting(target);
            if (observed.status() != Status.NOT_RUN) {
                return observed;
            }
            Status fallback = dispatched.accepted() ? Status.SUBMITTED
                    : fallbackStatus(dispatched.reason());
            return new Snapshot(fallback, dispatched.requestId(),
                    dispatchTarget, target.effectiveTargetTradeDate(),
                    dispatched.reason(), false, null, null, null, null,
                    null);
        } catch (RuntimeException error) {
            String reason = safeCode(error);
            runtimeState.checked(clock.instant(), dispatchTarget, null,
                    reason);
            return new Snapshot(Status.FAILED, "NONE", dispatchTarget,
                    target.effectiveTargetTradeDate(), reason, false, null,
                    null, null, null, null);
        }
    }

    public Snapshot status() {
        Instant checkedAt = clock.instant();
        Resolution target;
        try {
            target = targetResolver.resolve(checkedAt);
        } catch (RuntimeException error) {
            return unresolvedStatus(checkedAt);
        }
        Snapshot persisted = queryExisting(target);
        if (persisted.status() != Status.NOT_RUN) {
            return persisted;
        }
        var runtime = runtimeState.snapshot();
        if (!target.coverageEndDate().equals(runtime.tradeDate())
                && !target.dispatchTargetDate().equals(
                runtime.tradeDate())) {
            return persisted;
        }
        if (!"NONE".equals(runtime.requestId())) {
            return new Snapshot(Status.SUBMITTED, runtime.requestId(),
                    runtime.tradeDate(), target.effectiveTargetTradeDate(),
                    runtime.lastReason(), false,
                    null, null, null, null, null);
        }
        if (!"AWAITING_SCHEDULE".equals(runtime.lastReason())) {
            return new Snapshot(Status.FAILED, "NONE",
                    runtime.tradeDate(), target.effectiveTargetTradeDate(),
                    runtime.lastReason(), false, null, null, null, null,
                    null);
        }
        return persisted;
    }

    private Snapshot queryExisting(Resolution target) {
        Snapshot coverage = withResolution(statusQuery.query(
                target.coverageEndDate()), target);
        if (coverage.status() != Status.NOT_RUN
                || target.coverageEndDate().equals(
                target.dispatchTargetDate())) {
            return coverage;
        }
        return withResolution(statusQuery.query(
                target.dispatchTargetDate()), target);
    }

    private static Snapshot withResolution(
            Snapshot value,
            Resolution target
    ) {
        LocalDate effective = value.latestCommonCompletedOpenTradeDate()
                != null ? value.latestCommonCompletedOpenTradeDate()
                : target.effectiveTargetTradeDate();
        return value.withEffectiveTargetTradeDate(effective);
    }

    private Snapshot unresolvedStatus(Instant checkedAt) {
        LocalDate coverageEnd = coverageEnd(checkedAt);
        Snapshot persisted;
        try {
            persisted = statusQuery.query(coverageEnd);
        } catch (RuntimeException error) {
            return unavailable(coverageEnd);
        }
        if (persisted.status() != Status.NOT_RUN) {
            return persisted.withEffectiveTargetTradeDate(
                    persisted.latestCommonCompletedOpenTradeDate());
        }
        var runtime = runtimeState.snapshot();
        if (!coverageEnd.equals(runtime.tradeDate())) {
            return unavailable(coverageEnd);
        }
        if (!"NONE".equals(runtime.requestId())) {
            return new Snapshot(Status.SUBMITTED, runtime.requestId(),
                    coverageEnd, null, runtime.lastReason(), false,
                    null, null, null, null, null);
        }
        if (!"AWAITING_SCHEDULE".equals(runtime.lastReason())
                && !ManualMarketDataTargetResolver.TARGET_UNAVAILABLE
                .equals(runtime.lastReason())) {
            return new Snapshot(Status.FAILED, "NONE", coverageEnd, null,
                    runtime.lastReason(), false, null, null, null, null,
                    null);
        }
        return unavailable(coverageEnd);
    }

    private LocalDate coverageEnd(Instant instant) {
        var now = instant.atZone(ZoneId.of(properties.getZone()));
        return now.toLocalTime().isBefore(properties.getSafeWindowStart())
                ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    private static Snapshot unavailable(LocalDate coverageEnd) {
        return Snapshot.notRun(coverageEnd, null, "NONE",
                ManualMarketDataTargetResolver.TARGET_UNAVAILABLE);
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
