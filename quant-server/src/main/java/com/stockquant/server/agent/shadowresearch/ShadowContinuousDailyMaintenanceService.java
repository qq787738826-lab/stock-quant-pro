package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.RunStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Applies yesterday's frozen paper intent and appends now-known outcomes
 * before today's research is frozen.  It never changes historical research.
 */
public final class ShadowContinuousDailyMaintenanceService {
    private final ShadowResearchRepository repository;
    private final ShadowPaperPortfolioService paper;
    private final ShadowOutcomeService outcomes;

    public ShadowContinuousDailyMaintenanceService(
            ShadowResearchRepository repository,
            ShadowPaperPortfolioService paper,
            ShadowOutcomeService outcomes
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.paper = Objects.requireNonNull(paper, "paper");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    public MaintenanceResult maintain(
            LocalDate tradeDate,
            ResearchDataset observedDataset,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(observedDataset, "observedDataset");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (!observedDataset.lastSessionDate().equals(tradeDate)
                || StrategyResearchModels.closeInstant(tradeDate)
                .isAfter(evaluatedAt)
                || observedDataset.bars().stream().anyMatch(value ->
                value.sourceKnownAt().isAfter(evaluatedAt))) {
            throw new IllegalArgumentException(
                    "M4_DAILY_MAINTENANCE_TEMPORAL_BOUNDARY_INVALID");
        }
        var execution = paper.executeDue(tradeDate,
                StrategyResearchModels.openInstant(tradeDate),
                observedDataset, null);
        Set<String> datasetSecurities = observedDataset.securities().stream()
                .map(value -> value.canonicalCode())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int availableOutcomes = 0;
        for (var run : repository.runs(250)) {
            if (run.status() != RunStatus.FROZEN
                    || !run.tradeDate().isBefore(tradeDate)
                    || run.tradeDate().isBefore(
                    observedDataset.firstSessionDate())) {
                continue;
            }
            var snapshot = repository.snapshot(run.id()).orElseThrow(() ->
                    new IllegalStateException("M4_OUTCOME_SNAPSHOT_MISSING"));
            if (!datasetSecurities.containsAll(snapshot.recommendation()
                    .rankedSecurities())) {
                continue;
            }
            availableOutcomes += outcomes.evaluateAvailable(run.id(),
                    observedDataset, evaluatedAt).size();
        }
        return new MaintenanceResult(execution, availableOutcomes, true);
    }

    public record MaintenanceResult(
            ShadowPaperPortfolioService.Execution paperExecution,
            int availableOutcomeCount,
            boolean historicalResearchUnchanged
    ) {
        public MaintenanceResult {
            Objects.requireNonNull(paperExecution, "paperExecution");
            if (availableOutcomeCount < 0 || !historicalResearchUnchanged) {
                throw new IllegalArgumentException(
                        "M4_DAILY_MAINTENANCE_RESULT_INVALID");
            }
        }
    }
}
