package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.OutcomeObservation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.RunStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowOutcome;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Appends D1/D5/D20 observations without changing frozen research history. */
public final class ShadowOutcomeService {
    private static final Map<String, Integer> HORIZONS = Map.of(
            "D1", 1, "D5", 5, "D20", 20);
    private final ShadowResearchRepository repository;

    public ShadowOutcomeService(ShadowResearchRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<ShadowOutcome> evaluateAvailable(
            long runId,
            ResearchDataset observedDataset,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(observedDataset, "observedDataset");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        var run = repository.run(runId).orElseThrow(() ->
                invalid("M4_OUTCOME_RUN_MISSING"));
        if (run.status() != RunStatus.FROZEN) {
            throw invalid("M4_OUTCOME_RUN_NOT_FROZEN");
        }
        var recommendation = repository.snapshot(runId).orElseThrow(() ->
                invalid("M4_OUTCOME_SNAPSHOT_MISSING")).recommendation();
        if (observedDataset.bars().stream().anyMatch(value ->
                value.sourceKnownAt().isAfter(evaluatedAt))) {
            throw invalid("M4_OUTCOME_FUTURE_KNOWLEDGE_FORBIDDEN");
        }
        List<LocalDate> futureSessions = observedDataset.sessions().stream()
                .filter(value -> value.anyOpen()
                        && value.tradeDate().isAfter(run.tradeDate()))
                .map(value -> value.tradeDate()).toList();
        List<String> ranked = recommendation.rankedSecurities().stream()
                .distinct().toList();
        List<ShadowOutcome> outcomes = new ArrayList<>();
        for (String horizon : List.of("D1", "D5", "D20")) {
            int offset = HORIZONS.get(horizon);
            if (futureSessions.size() < offset) {
                continue;
            }
            LocalDate date = futureSessions.get(offset - 1);
            if (com.stockquant.core.research.StrategyResearchModels
                    .closeInstant(date).isAfter(evaluatedAt)) {
                continue;
            }
            Map<String, BigDecimal> returns = returns(observedDataset,
                    run.tradeDate(), date, ranked);
            BigDecimal average = returns.isEmpty() ? BigDecimal.ZERO
                    : returns.values().stream().reduce(BigDecimal.ZERO,
                    BigDecimal::add).divide(
                    BigDecimal.valueOf(returns.size()), 12,
                    RoundingMode.HALF_EVEN);
            OutcomeObservation observation = new OutcomeObservation(
                    ShadowResearchModels.OUTCOME_VERSION,
                    recommendation.decisionCode(), ranked, returns, average,
                    ranked.isEmpty(), true, true);
            outcomes.add(repository.insertOutcome(runId, horizon, date,
                    observation, evaluatedAt));
        }
        return List.copyOf(outcomes);
    }

    private static Map<String, BigDecimal> returns(
            ResearchDataset dataset,
            LocalDate signalDate,
            LocalDate evaluationDate,
            List<String> ranked
    ) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String canonical : ranked) {
            Security security = security(canonical);
            BigDecimal start = close(dataset, security, signalDate);
            BigDecimal end = close(dataset, security, evaluationDate);
            if (start == null || end == null) {
                throw invalid("M4_OUTCOME_FACT_MISSING");
            }
            values.put(canonical, end.divide(start, 16,
                    RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE)
                    .setScale(12, RoundingMode.HALF_EVEN));
        }
        return Map.copyOf(values);
    }

    private static BigDecimal close(
            ResearchDataset dataset,
            Security security,
            LocalDate date
    ) {
        return dataset.bars().stream().filter(value ->
                        value.security().equals(security)
                                && value.tradeDate().equals(date)
                                && value.tradable())
                .map(DailyBar::close).findFirst().orElse(null);
    }

    private static Security security(String canonical) {
        String[] values = canonical.split(":", -1);
        if (values.length != 2) {
            throw invalid("M4_OUTCOME_SECURITY_INVALID");
        }
        return new Security(values[0], values[1]);
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
