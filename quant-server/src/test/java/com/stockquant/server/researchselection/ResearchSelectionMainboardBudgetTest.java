package com.stockquant.server.researchselection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionMainboardBudgetTest {

    private static final Instant AS_OF = Instant.parse(
            "2026-08-18T02:00:00Z");
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 14);

    @Test
    void approvedAugustLimitAdmitsExactSixtySessionBackfillAndReserve() {
        List<LocalDate> dates = weekdaysEndingAt(ANCHOR, 60);
        var audit = new ResearchUniverseMainboardDatasetLoader.Audit(
                dates, dates, false, true, 25);

        var plan = ResearchSelectionProviderBudgetPlanner
                .assembleMainboardPlan(null, audit, ANCHOR, AS_OF, 25, 96,
                        ResearchSelectionProviderBudgetPlanner
                                .monthlyTushareLimit(
                                        java.time.YearMonth.of(2026, 8)))
                .backfill();

        assertEquals(1, plan.stockBasicRequests());
        assertEquals(60, plan.dailyRequests());
        assertEquals(60, plan.adjustmentFactorRequests());
        assertEquals(0, plan.tradeCalendarRequests());
        assertEquals(121, plan.totalRequests());
        assertEquals(24, plan.scheduledReserve());
        assertEquals(241, plan.ledgerUsed() + plan.totalRequests()
                + plan.scheduledReserve());
        assertEquals(250, plan.ledgerLimit());
        assertTrue(plan.executableWithinBudget());
        assertEquals(9, plan.ledgerLimit() - plan.ledgerUsed()
                - plan.totalRequests() - plan.scheduledReserve());
    }

    @Test
    void completeSnapshotConsumesNoCallsAndRetainsDailyReserve() {
        List<LocalDate> dates = weekdaysEndingAt(ANCHOR, 60);
        var snapshot = snapshot();
        var audit = new ResearchUniverseMainboardDatasetLoader.Audit(
                dates, List.of(), false, false, 3_000);

        var plan = ResearchSelectionProviderBudgetPlanner
                .assembleMainboardPlan(snapshot, audit, ANCHOR, AS_OF,
                        3_000, 96, 150).backfill();

        assertEquals(0, plan.totalRequests());
        assertEquals(24, plan.scheduledReserve());
        assertTrue(plan.executableWithinBudget());
    }

    @Test
    void reserveIncludesTodayBeforeSlotAndStartsTomorrowAfterSlot() {
        assertEquals(24, ResearchSelectionProviderBudgetPlanner
                .scheduledReserve(AS_OF));
        assertEquals(22, ResearchSelectionProviderBudgetPlanner
                .scheduledReserve(Instant.parse(
                        "2026-08-18T10:00:01Z")));
    }

    @Test
    void invalidLedgerCannotProduceARequestEnvelope() {
        var audit = new ResearchUniverseMainboardDatasetLoader.Audit(
                List.of(ANCHOR), List.of(ANCHOR), false, true, 0);
        assertThrows(IllegalArgumentException.class, () ->
                ResearchSelectionProviderBudgetPlanner.assembleMainboardPlan(
                        null, audit, ANCHOR, AS_OF, 0, 151, 150));
    }

    private static ResearchUniverseMainboard.SnapshotBundle snapshot() {
        var member = new ResearchUniverseMainboard.Member("600000.SH",
                "600000", "SSE", "浦发银行", "银行", "主板", "L",
                LocalDate.of(1999, 11, 10), null, AS_OF,
                ResearchUniverseMainboard.SOURCE, "a".repeat(64), false);
        var snapshot = new ResearchUniverseMainboard.Snapshot(1,
                "MAINBOARD_20260818_aaaaaaaaaaaa",
                ResearchUniverseMainboard.VERSION, 1, 1, 0, 0, AS_OF,
                AS_OF,
                LocalDate.of(2026, 8, 18), ResearchUniverseMainboard.SOURCE,
                "b".repeat(64), "c".repeat(64), "d".repeat(40));
        return new ResearchUniverseMainboard.SnapshotBundle(snapshot,
                List.of(member));
    }

    private static List<LocalDate> weekdaysEndingAt(
            LocalDate end, int count
    ) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate date = end;
        while (result.size() < count) {
            if (date.getDayOfWeek().getValue() <= 5) result.add(date);
            date = date.minusDays(1);
        }
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }
}
