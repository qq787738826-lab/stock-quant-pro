package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/** Selects the newest closed, data-complete research anchor. */
public final class ResearchSelectionAnchorResolver {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_COMPLETE_ANCHOR_CANDIDATES = 31;

    private ResearchSelectionAnchorResolver() {
    }

    public static LocalDate resolve(
            TushareResearchUniverseDatasetLoader loader,
            int openSessionWindow,
            Instant asOf
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(asOf, "asOf");
        return resolve(Clock.fixed(asOf, java.time.ZoneOffset.UTC),
                notAfter -> latestOpen(loader, notAfter, asOf),
                candidate -> complete(loader, openSessionWindow, candidate,
                        asOf));
    }

    static LocalDate resolve(
            Clock clock,
            Function<LocalDate, LocalDate> latestOpen,
            Predicate<LocalDate> complete
    ) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(latestOpen, "latestOpen");
        Objects.requireNonNull(complete, "complete");
        Instant asOf = clock.instant();
        LocalDate localDate = asOf.atZone(SHANGHAI).toLocalDate();
        LocalDate latestClosed = asOf.isBefore(
                StrategyResearchModels.closeInstant(localDate))
                ? localDate.minusDays(1) : localDate;
        LocalDate newest = Objects.requireNonNull(
                latestOpen.apply(latestClosed), "newest");
        LocalDate candidate = newest;
        for (int checked = 0;
                checked < MAX_COMPLETE_ANCHOR_CANDIDATES; checked++) {
            if (complete.test(candidate)) {
                return candidate;
            }
            LocalDate previous = Objects.requireNonNull(
                    latestOpen.apply(candidate.minusDays(1)), "previous");
            if (!previous.isBefore(candidate)) {
                throw invalid("RESEARCH_SELECTION_ANCHOR_ORDER_INVALID");
            }
            candidate = previous;
        }
        // Preserve the existing bounded capture path when no complete local
        // window exists yet. The planner will authorize only 2 or 52 calls.
        return newest;
    }

    private static LocalDate latestOpen(
            TushareResearchUniverseDatasetLoader loader,
            LocalDate notAfter,
            Instant asOf
    ) {
        try {
            return loader.latestCommonOpenDate(
                    ResearchUniverseV1.securities(), notAfter, asOf);
        } catch (IllegalStateException missing) {
            if (!"RESEARCH_UNIVERSE_COMMON_OPEN_SESSION_MISSING".equals(
                    missing.getMessage())) {
                throw missing;
            }
            LocalDate fallback = notAfter;
            while (fallback.getDayOfWeek() == DayOfWeek.SATURDAY
                    || fallback.getDayOfWeek() == DayOfWeek.SUNDAY) {
                fallback = fallback.minusDays(1);
            }
            return fallback;
        }
    }

    private static boolean complete(
            TushareResearchUniverseDatasetLoader loader,
            int openSessionWindow,
            LocalDate anchor,
            Instant asOf
    ) {
        try {
            loader.load(ResearchUniverseV1.securities(), openSessionWindow,
                    anchor, asOf);
            return true;
        } catch (TushareResearchUniverseDatasetLoader
                 .IncompleteUniverseException incomplete) {
            return false;
        } catch (IllegalStateException incomplete) {
            if ("RESEARCH_UNIVERSE_CALENDAR_WINDOW_INCOMPLETE".equals(
                    incomplete.getMessage())) {
                return false;
            }
            throw incomplete;
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
