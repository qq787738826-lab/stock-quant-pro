package com.stockquant.server.researchselection;

import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader.LoadedUniverse;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalAvailability;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalWindowCoverage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Finds the longest complete trailing dataset already present locally.
 * Missing 120/250-session history is evidence, never a Provider capture
 * instruction.
 */
public final class ResearchSelectionHistoricalDatasetLoader {
    private static final List<Integer> REPORTED_WINDOWS =
            List.of(20, 60, 120, 250);

    public HistoricalDataset expand(
            TushareResearchUniverseDatasetLoader loader,
            LoadedUniverse current,
            LocalDate anchor,
            Instant cutoff
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(current, "current");
        int minimum = current.dataset().sessions().size();
        if (minimum < 20 || minimum > 250) {
            throw new IllegalArgumentException(
                    "RESEARCH_SELECTION_HISTORY_BASE_INVALID");
        }
        LoadedUniverse longest = current;
        if (minimum < 250) {
            LoadedUniverse maximum = attempt(loader, 250, anchor, cutoff);
            if (maximum != null) {
                longest = maximum;
            } else {
                int low = minimum;
                int high = 249;
                while (low < high) {
                    int probe = (low + high + 1) / 2;
                    LoadedUniverse candidate = attempt(loader, probe,
                            anchor, cutoff);
                    if (candidate == null) {
                        high = probe - 1;
                    } else {
                        low = probe;
                        longest = candidate;
                    }
                }
            }
        }
        return new HistoricalDataset(longest,
                coverage(longest.dataset().sessions().stream()
                        .map(value -> value.tradeDate()).toList()));
    }

    private static LoadedUniverse attempt(
            TushareResearchUniverseDatasetLoader loader,
            int sessions,
            LocalDate anchor,
            Instant cutoff
    ) {
        try {
            return loader.load(ResearchUniverseV1.securities(), sessions,
                    anchor, cutoff);
        } catch (TushareResearchUniverseDatasetLoader
                 .IncompleteUniverseException incomplete) {
            return null;
        } catch (IllegalStateException failure) {
            if ("RESEARCH_UNIVERSE_CALENDAR_WINDOW_INCOMPLETE".equals(
                    failure.getMessage())) {
                return null;
            }
            throw failure;
        }
    }

    private static List<HistoricalWindowCoverage> coverage(
            List<LocalDate> sessions
    ) {
        int available = sessions.size();
        List<HistoricalWindowCoverage> result = new ArrayList<>();
        for (int requested : REPORTED_WINDOWS) {
            boolean complete = available >= requested;
            LocalDate start = complete
                    ? sessions.get(available - requested)
                    : sessions.get(0);
            result.add(new HistoricalWindowCoverage(requested,
                    complete ? HistoricalAvailability.AVAILABLE
                            : HistoricalAvailability.INSUFFICIENT_HISTORY,
                    Math.min(available, requested), start,
                    sessions.get(available - 1),
                    Math.max(0, requested - available),
                    complete ? null : "INSUFFICIENT_HISTORY"));
        }
        return List.copyOf(result);
    }

    public record HistoricalDataset(
            LoadedUniverse loaded,
            List<HistoricalWindowCoverage> windowCoverage
    ) {
        public HistoricalDataset {
            Objects.requireNonNull(loaded, "loaded");
            windowCoverage = List.copyOf(windowCoverage);
        }
    }
}
