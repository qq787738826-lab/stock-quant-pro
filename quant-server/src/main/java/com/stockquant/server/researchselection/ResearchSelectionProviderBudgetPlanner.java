package com.stockquant.server.researchselection;

import com.stockquant.server.agent.evaluation.ExternalApiMonthlyBudget;
import com.stockquant.server.agent.marketfacts.TushareManualBoundedSession;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.BackfillPlan;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;

/** Computes an exact stock_basic/date-wide budget before Broker submit. */
public final class ResearchSelectionProviderBudgetPlanner {
    public static final int SCHEDULED_SAFETY_RESERVE = 4;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime SCHEDULED_SHADOW_SLOT =
            LocalTime.of(17, 20);

    private ResearchSelectionProviderBudgetPlanner() {
    }

    public static int monthlyTushareLimit(YearMonth month) {
        return ExternalApiMonthlyBudget.tushareRequestLimit(month);
    }

    public static int requiredProviderRequests(
            TushareResearchUniverseDatasetLoader loader,
            SelectionRequest request,
            Instant asOf
    ) {
        var anchor = ResearchSelectionAnchorResolver.resolve(loader,
                request.auxiliaryWindow(), asOf);
        try {
            loader.load(ResearchUniverseV1.securities(),
                    request.auxiliaryWindow(), anchor, asOf);
            return 0;
        } catch (TushareResearchUniverseDatasetLoader
                 .IncompleteUniverseException incomplete) {
            return incomplete.incrementalAnchorOnly() ? 2
                    : TushareManualBoundedSession
                    .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS;
        } catch (IllegalStateException missing) {
            if ("RESEARCH_UNIVERSE_CALENDAR_WINDOW_INCOMPLETE".equals(
                    missing.getMessage())) {
                return TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS;
            }
            throw missing;
        }
    }

    public static MainboardPlan mainboardPlan(
            ResearchUniverseMainboardDatasetLoader loader,
            SnapshotBundle snapshot,
            SelectionRequest request,
            Instant asOf,
            int existingSecurityCount,
            int ledgerUsed,
            int ledgerLimit
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(asOf, "asOf");
        if (existingSecurityCount < 0 || ledgerUsed < 0
                || ledgerLimit <= 0 || ledgerUsed > ledgerLimit) {
            throw new IllegalArgumentException(
                    "MAINBOARD_BUDGET_LEDGER_INVALID");
        }
        boolean scheduled = request.triggerMode()
                == ResearchSelectionModels.TriggerMode.SCHEDULED_SHADOW;
        LocalDate anchor = loader.resolveAnchor(snapshot, asOf, scheduled);
        var audit = loader.audit(snapshot, anchor, asOf,
                ResearchUniverseMainboard.STABILITY_MINIMUM_SESSIONS);
        return assembleMainboardPlan(snapshot, audit, anchor, asOf,
                existingSecurityCount, ledgerUsed, ledgerLimit);
    }

    static MainboardPlan assembleMainboardPlan(
            SnapshotBundle snapshot,
            ResearchUniverseMainboardDatasetLoader.Audit audit,
            LocalDate anchor,
            Instant asOf,
            int existingSecurityCount,
            int ledgerUsed,
            int ledgerLimit
    ) {
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(asOf, "asOf");
        if (existingSecurityCount < 0 || ledgerUsed < 0
                || ledgerLimit <= 0 || ledgerUsed > ledgerLimit) {
            throw new IllegalArgumentException(
                    "MAINBOARD_BUDGET_LEDGER_INVALID");
        }
        int stockBasic = audit.refreshStockBasic() ? 1 : 0;
        int daily = audit.calendarIncomplete() ? 0
                : audit.missingTradeDates().size();
        int factors = daily;
        int calendar = audit.calendarIncomplete() ? 2 : 0;
        int total = stockBasic + daily + factors + calendar;
        int reserve = scheduledReserve(asOf);
        LocalDate rangeStart = audit.requiredTradeDates().isEmpty()
                ? anchor : audit.requiredTradeDates().get(0);
        LocalDate rangeEnd = audit.requiredTradeDates().isEmpty()
                ? anchor : audit.requiredTradeDates().get(
                audit.requiredTradeDates().size() - 1);
        BackfillPlan plan = new BackfillPlan(
                ResearchUniverseMainboard.VERSION,
                snapshot == null ? null : snapshot.snapshot().snapshotId(),
                snapshot == null ? 0 : snapshot.snapshot().memberCount(),
                existingSecurityCount, anchor, rangeStart, rangeEnd,
                audit.requiredTradeDates(), audit.missingTradeDates(),
                stockBasic, daily, factors, calendar, total, ledgerUsed,
                ledgerLimit, reserve,
                ledgerUsed + total + reserve <= ledgerLimit);
        return new MainboardPlan(anchor, audit, plan);
    }

    static int scheduledReserve(Instant asOf) {
        var local = asOf.atZone(SHANGHAI);
        LocalDate localDate = local.toLocalDate();
        LocalDate date = local.toLocalTime().isAfter(SCHEDULED_SHADOW_SLOT)
                ? localDate.plusDays(1) : localDate;
        LocalDate end = YearMonth.from(localDate).atEndOfMonth();
        int sessions = 0;
        for (LocalDate value = date; !value.isAfter(end);
                value = value.plusDays(1)) {
            if (value.getDayOfWeek() != DayOfWeek.SATURDAY
                    && value.getDayOfWeek() != DayOfWeek.SUNDAY) {
                sessions++;
            }
        }
        return sessions * 2 + SCHEDULED_SAFETY_RESERVE;
    }

    public record MainboardPlan(
            LocalDate anchorTradeDate,
            ResearchUniverseMainboardDatasetLoader.Audit audit,
            BackfillPlan backfill
    ) {
    }

}
