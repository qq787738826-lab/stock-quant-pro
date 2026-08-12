package com.stockquant.server.agent.shadowresearch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
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

    public ShadowResearchScheduler(
            ShadowResearchRepository repository,
            ShadowResearchDispatchGateway dispatcher,
            ShadowResearchScheduleProperties properties,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        properties.validate();
    }

    @Scheduled(cron = "${stockquant.shadow-research.scheduler.cron:0 20 17 * * MON-FRI}",
            zone = "${stockquant.shadow-research.scheduler.zone:Asia/Shanghai}")
    public void dispatchAfterClose() {
        var now = clock.instant().atZone(ZoneId.of(properties.getZone()));
        LocalDate date = now.toLocalDate();
        if (!eligibleWeekday(date) || !withinWindow(now.toLocalTime())
                || repository.frozenSlot(date,
                ShadowResearchModels.RESEARCH_SLOT,
                ShadowResearchModels.STRATEGY_VERSION).isPresent()
                || repository.activeRun().isPresent()) {
            return;
        }
        if (!repository.researchCalendarOpen(date, clock.instant())) {
            return;
        }
        dispatcher.dispatch(date, clock.instant());
    }

    boolean withinWindow(LocalTime time) {
        return !time.isBefore(properties.getSafeWindowStart())
                && !time.isAfter(properties.getSafeWindowEnd());
    }

    static boolean eligibleWeekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY
                && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
