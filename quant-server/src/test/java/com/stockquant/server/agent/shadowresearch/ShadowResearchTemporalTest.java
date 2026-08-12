package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowResearchTemporalTest {

    @Test
    void inMemoryReplayCannotExposeFactsKnownAfterCutoff() {
        var accepted = ShadowResearchTestFixtures.dataset();
        var source = new InMemoryShadowResearchDatasetSource(accepted);
        var securities = accepted.dataset().securities();
        var end = accepted.dataset().lastSessionDate();
        Instant cutoff = StrategyResearchModels.closeInstant(end)
                .plusSeconds(60);
        var task = new ResearchTask("M3TASK_SHADOW_101_20250910",
                "Verify immutable historical as-of research.", securities,
                end.minusDays(60), end, end, cutoff, securities.get(0),
                ShadowResearchTestFixtures.strategies(),
                new com.stockquant.server.agent.research.AgentResearchModels
                        .RuntimeLimits(2, 8, 16, Duration.ofMinutes(8)));

        var loaded = source.load(task);

        assertEquals(end, loaded.dataset().lastSessionDate());
        assertTrue(loaded.dataset().bars().stream().allMatch(value ->
                !value.tradeDate().isAfter(end)
                        && !value.sourceKnownAt().isAfter(cutoff)));
        assertFalse(loaded.dataset().bars().isEmpty());
    }

    @Test
    void replayRejectsExecutionAtSignalOrSameDate() {
        var loaded = ShadowResearchTestFixtures.dataset().dataset();
        var request = ShadowResearchRuntimePostgresTest.request();
        assertThrows(IllegalArgumentException.class, () ->
                new ShadowHistoricalReplayService.ReplayStep(request,
                        request.tradeDate(), request.researchAsOf(), loaded,
                        request.researchAsOf()));
    }

    @Test
    void requestRejectsResearchAsOfBeforeTheTargetMarketClose() {
        var accepted = ShadowResearchTestFixtures.dataset().dataset();
        LocalDate date = LocalDate.of(2025, 9, 10);
        assertThrows(IllegalArgumentException.class, () ->
                new ShadowResearchModels.ShadowRequest(
                        ShadowResearchModels.TriggerMode.MANUAL, date,
                        date.minusDays(30), StrategyResearchModels
                        .closeInstant(date).minusSeconds(1),
                        accepted.securities(), accepted.securities().get(0),
                        ShadowResearchTestFixtures.strategies(), null, 0,
                        "Reject pre-close research as-of."));
    }

    @Test
    void scheduleWindowIsBoundedAndWeekendNeverEligible() {
        var properties = new ShadowResearchScheduleProperties();
        properties.validate();
        assertTrue(ShadowResearchScheduler.eligibleWeekday(
                java.time.LocalDate.of(2025, 9, 10)));
        assertFalse(ShadowResearchScheduler.eligibleWeekday(
                java.time.LocalDate.of(2025, 9, 13)));
    }
}
