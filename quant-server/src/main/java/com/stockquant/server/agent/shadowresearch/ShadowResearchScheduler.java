package com.stockquant.server.agent.shadowresearch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.stockquant.server.production.ShadowSchedulerRuntimeState;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/** One bounded after-close dispatch per eligible day; never executes a model. */
@Component
@ConditionalOnProperty(prefix = "stockquant.shadow-research.scheduler",
        name = "enabled", havingValue = "true")
public final class ShadowResearchScheduler {
    private final ShadowResearchRepository repository;
    private final ShadowResearchDispatchGateway dispatcher;
    private final ShadowResearchScheduleProperties properties;
    private final Clock clock;
    private final ShadowSchedulerRuntimeState runtimeState;

    public ShadowResearchScheduler(
            ShadowResearchRepository repository,
            ShadowResearchDispatchGateway dispatcher,
            ShadowResearchScheduleProperties properties,
            @Qualifier("agentTemporalClock") Clock clock,
            ShadowSchedulerRuntimeState runtimeState
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runtimeState = Objects.requireNonNull(runtimeState,
                "runtimeState");
        properties.validate();
    }

    @Scheduled(cron = "${stockquant.shadow-research.scheduler.cron:0 20 17 * * MON-FRI}",
            zone = "${stockquant.shadow-research.scheduler.zone:Asia/Shanghai}")
    public void dispatchAfterClose() {
        var now = clock.instant().atZone(ZoneId.of(properties.getZone()));
        LocalDate date = now.toLocalDate();
        if (!eligibleWeekday(date)) {
            runtimeState.checked(clock.instant(), "NON_TRADING_WEEKEND");
            return;
        }
        if (!withinWindow(now.toLocalTime())) {
            runtimeState.checked(clock.instant(), "OUTSIDE_SAFE_WINDOW");
            return;
        }
        if (repository.frozenSlot(date,
                ShadowResearchModels.RESEARCH_SLOT,
                ShadowResearchModels.SELECTION_STRATEGY_VERSION).isPresent()) {
            runtimeState.checked(clock.instant(), "SLOT_ALREADY_FROZEN");
            return;
        }
        repository.interruptStaleRuns(clock.instant().minus(
                Duration.ofHours(2)), clock.instant());
        var calendar = repository.researchCalendarState(date,
                clock.instant());
        if (calendar == ShadowResearchRepository.CalendarState.CLOSED) {
            runtimeState.checked(clock.instant(), "MARKET_CLOSED");
            return;
        }
        if (calendar == ShadowResearchRepository.CalendarState.OPEN
                && !repository.nextCommonOpenKnown(date, clock.instant())) {
            calendar = ShadowResearchRepository.CalendarState.UNKNOWN;
        }
        ShadowResearchDispatchGateway.DispatchResult result;
        try {
            result = dispatcher.dispatch(date, clock.instant(), calendar);
        } catch (RuntimeException error) {
            runtimeState.checked(clock.instant(), safeCode(error));
            throw error;
        }
        if (result.accepted()) {
            runtimeState.dispatched(clock.instant());
        } else {
            runtimeState.checked(clock.instant(), result.reason());
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
                ? message : "M4_SCHEDULER_DISPATCH_FAILED";
    }
}
