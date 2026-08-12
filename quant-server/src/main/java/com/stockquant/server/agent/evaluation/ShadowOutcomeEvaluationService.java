package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ShadowOutcomeEvaluation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.FrozenSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowOutcome;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Joins a frozen prediction to append-only, strictly later M4 outcomes. */
public final class ShadowOutcomeEvaluationService {
    public List<ShadowOutcomeEvaluation> evaluate(
            ShadowRun run,
            FrozenSnapshot snapshot,
            List<ShadowOutcome> outcomes,
            List<PortfolioSnapshot> portfolioSnapshots
    ) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(snapshot, "snapshot");
        if (run.signalTime() == null || snapshot.runId() != run.id()) {
            throw AgentEvaluationModels.invalid("M5_FROZEN_PREDICTION_INVALID");
        }
        List<PortfolioSnapshot> orderedPortfolio = portfolioSnapshots == null
                ? List.of() : portfolioSnapshots.stream()
                .filter(value -> value.runId() != null
                        && value.runId() == run.id())
                .sorted(java.util.Comparator.comparing(
                        PortfolioSnapshot::snapshotTime)).toList();
        return outcomes.stream().map(outcome -> {
            if (outcome.runId() != run.id()
                    || !outcome.evaluatedAt().isAfter(run.signalTime())
                    || !outcome.observation().noFutureDataLeakage()
                    || !outcome.observation().researchOnly()) {
                throw AgentEvaluationModels.invalid(
                        "M5_OUTCOME_TEMPORAL_BOUNDARY_INVALID");
            }
            boolean abstention = outcome.observation().emptyRecommendation();
            BigDecimal rankedReturn = outcome.observation()
                    .equalWeightReturn();
            Direction direction = direction(snapshot.recommendation()
                    .decisionCode());
            boolean directionScorable = !abstention
                    && direction != Direction.UNKNOWN;
            boolean directionHit = directionScorable
                    && (direction == Direction.POSITIVE
                    ? rankedReturn.signum() > 0
                    : rankedReturn.signum() <= 0);
            BigDecimal rankingAccuracy = rankingAccuracy(
                    outcome.observation().rankedSecurities(),
                    outcome.observation().securityReturns());
            BigDecimal worstConstituentReturn = outcome.observation()
                    .securityReturns().values().stream()
                    .min(BigDecimal::compareTo).orElse(rankedReturn);
            boolean riskUnderestimated = !abstention
                    && riskUnderestimated(snapshot.recommendation().riskLevel(),
                    worstConstituentReturn);
            BigDecimal paperReturn = orderedPortfolio.stream()
                    .filter(value -> !value.snapshotTime().isAfter(
                            outcome.evaluatedAt()))
                    .reduce((left, right) -> right)
                    .map(PortfolioSnapshot::totalReturn)
                    .orElse(BigDecimal.ZERO);
            List<String> errors = new ArrayList<>();
            if (directionScorable && !directionHit) {
                errors.add("DIRECTION_MISS");
            }
            if (!abstention && outcome.observation().rankedSecurities()
                    .size() > 1 && rankingAccuracy.compareTo(
                    new BigDecimal("0.5")) < 0) {
                errors.add("RANKING_MISS");
            }
            if (riskUnderestimated) {
                errors.add("RISK_UNDERESTIMATED");
            }
            if (!abstention && "UNKNOWN".equals(
                    snapshot.recommendation().riskLevel())) {
                errors.add("RISK_UNKNOWN");
            }
            return new ShadowOutcomeEvaluation(run.id(),
                    outcome.horizonCode(), run.signalTime(),
                    outcome.evaluatedAt(), outcome.evaluationDate(),
                    snapshot.recommendation().decisionCode(),
                    snapshot.recommendation().confidence(), rankedReturn,
                    rankingAccuracy, directionHit, directionScorable,
                    abstention,
                    snapshot.recommendation().riskLevel(), riskUnderestimated,
                    paperReturn, errors, true);
        }).toList();
    }

    private static Direction direction(String decisionCode) {
        String normalized = decisionCode.toUpperCase(
                java.util.Locale.ROOT);
        if (normalized.matches(".*(BUY|LONG|OVERWEIGHT|BULLISH|PREFERENCE).*")) {
            return Direction.POSITIVE;
        }
        if (normalized.matches(".*(SELL|EXIT|UNDERWEIGHT|BEARISH|AVOID).*")) {
            return Direction.NEGATIVE;
        }
        return Direction.UNKNOWN;
    }

    private enum Direction { POSITIVE, NEGATIVE, UNKNOWN }

    private static BigDecimal rankingAccuracy(
            List<String> ranked,
            Map<String, BigDecimal> returns
    ) {
        if (ranked.size() < 2) {
            return ranked.isEmpty() ? BigDecimal.ZERO : BigDecimal.ONE;
        }
        int pairs = 0;
        int correct = 0;
        for (int left = 0; left < ranked.size(); left++) {
            for (int right = left + 1; right < ranked.size(); right++) {
                pairs++;
                if (returns.get(ranked.get(left)).compareTo(
                        returns.get(ranked.get(right))) >= 0) {
                    correct++;
                }
            }
        }
        return BigDecimal.valueOf(correct).divide(BigDecimal.valueOf(pairs),
                8, RoundingMode.HALF_UP);
    }

    private static boolean riskUnderestimated(
            String riskLevel,
            BigDecimal observedReturn
    ) {
        BigDecimal loss = observedReturn.min(BigDecimal.ZERO).abs();
        return switch (riskLevel) {
            case "LOW" -> loss.compareTo(new BigDecimal("0.03")) > 0;
            case "MODERATE" -> loss.compareTo(new BigDecimal("0.08")) > 0;
            case "HIGH", "UNKNOWN" -> false;
            default -> throw AgentEvaluationModels.invalid(
                    "M5_RISK_LEVEL_INVALID");
        };
    }
}
