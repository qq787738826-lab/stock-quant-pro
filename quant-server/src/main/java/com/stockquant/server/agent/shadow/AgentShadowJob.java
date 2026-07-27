package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.config.AgentShadowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

@Component
@ConditionalOnProperty(
        prefix = "stockquant.agent-team.shadow",
        name = {"enabled", "scheduler-enabled"},
        havingValue = "true")
public class AgentShadowJob {

    private static final Logger log = LoggerFactory.getLogger(
            AgentShadowJob.class);

    private final AgentShadowBatchService batchService;
    private final AgentShadowRepository repository;
    private final AgentShadowProperties properties;
    private final Clock clock;

    public AgentShadowJob(
            AgentShadowBatchService batchService,
            AgentShadowRepository repository,
            AgentShadowProperties properties,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.batchService = batchService;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${stockquant.agent-team.shadow.cron:0 50 16 * * MON-FRI}",
            zone = "${stockquant.agent-team.shadow.zone:Asia/Shanghai}")
    public void runScheduled() {
        ZonedDateTime now = clock.instant().atZone(
                AgentShadowContracts.MARKET_ZONE);
        LocalDate date = now.toLocalDate();
        if (!weekday(date)) {
            batchService.recordScheduledSkip(
                    "SHADOW_SCHEDULER_NON_WORKDAY");
            return;
        }
        if (!withinSafeWindow(now.toLocalTime())) {
            batchService.recordScheduledSkip(
                    "SHADOW_SCHEDULER_OUTSIDE_SAFE_WINDOW");
            return;
        }
        var calendarOpen = repository.reliableCalendarOpen(
                date, clock.instant());
        if (calendarOpen.isPresent()
                && !calendarOpen.orElseThrow()) {
            batchService.recordScheduledSkip(
                    "SHADOW_RELIABLE_CALENDAR_CLOSED");
            return;
        }
        try {
            batchService.createScheduled();
        } catch (RuntimeException error) {
            log.error("scheduled shadow batch failed", error);
            batchService.recordScheduledSkip(
                    "SHADOW_SCHEDULER_CREATE_FAILED");
        }
    }

    boolean withinSafeWindow(LocalTime time) {
        return !time.isBefore(properties.getSafeWindowStart())
                && !time.isAfter(properties.getSafeWindowEnd());
    }

    static boolean weekday(LocalDate date) {
        DayOfWeek value = date.getDayOfWeek();
        return value != DayOfWeek.SATURDAY
                && value != DayOfWeek.SUNDAY;
    }
}
