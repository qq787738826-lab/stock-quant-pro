package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.FrozenSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.OutcomeObservation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowOutcome;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.RunStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.TriggerMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowOutcomeEvaluationServiceTest {
    private static final Instant SIGNAL = Instant.parse(
            "2026-01-02T08:00:00Z");

    @Test
    void evaluatesRankingDirectionRiskAndPreservesAbstention() {
        ShadowRun run = run();
        FrozenSnapshot snapshot = snapshot(run.id(), "LOW", "0.90");
        OutcomeObservation observed = new OutcomeObservation(
                "SHADOW_OUTCOME_V1", "RESEARCH_PREFERENCE",
                List.of("600000:SSE", "000001:SZSE"), Map.of(
                "600000:SSE", new BigDecimal("-0.06"),
                "000001:SZSE", new BigDecimal("0.02")),
                new BigDecimal("-0.02"), false, true, true);

        var result = new ShadowOutcomeEvaluationService().evaluate(run,
                snapshot, List.of(outcome(run.id(), observed,
                        SIGNAL.plusSeconds(86_400))), null).get(0);

        assertEquals(BigDecimal.ZERO.setScale(8), result.rankingAccuracy());
        assertTrue(result.riskUnderestimated());
        assertEquals(List.of("DIRECTION_MISS", "RANKING_MISS",
                "RISK_UNDERESTIMATED"), result.errorTypes());
        assertTrue(result.directionScorable());
    }

    @Test
    void outcomeKnownAtSignalIsRejected() {
        ShadowRun run = run();
        FrozenSnapshot snapshot = snapshot(run.id(), "MODERATE", "0.50");
        OutcomeObservation observed = new OutcomeObservation(
                "SHADOW_OUTCOME_V1", "INSUFFICIENT_EVIDENCE", List.of(),
                Map.of(), BigDecimal.ZERO, true, true, true);

        assertThrows(IllegalArgumentException.class, () ->
                new ShadowOutcomeEvaluationService().evaluate(run, snapshot,
                        List.of(outcome(run.id(), observed, SIGNAL)), null));
    }

    @Test
    void unknownRiskIsReportedWithoutInventingAThreshold() {
        ShadowRun run = run();
        FrozenSnapshot snapshot = snapshot(run.id(), "UNKNOWN", "0.40");
        OutcomeObservation observed = new OutcomeObservation(
                "SHADOW_OUTCOME_V1", "RESEARCH_PREFERENCE",
                List.of("600000:SSE"), Map.of(
                "600000:SSE", new BigDecimal("0.01")),
                new BigDecimal("0.01"), false, true, true);

        var result = new ShadowOutcomeEvaluationService().evaluate(run,
                snapshot, List.of(outcome(run.id(), observed,
                        SIGNAL.plusSeconds(86_400))), null).get(0);

        assertEquals(List.of("RISK_UNKNOWN"), result.errorTypes());
        assertTrue(!result.riskUnderestimated());
    }

    @Test
    void nonDirectionalDecisionDoesNotManufactureAHitOrMiss() {
        ShadowRun run = run();
        FrozenSnapshot snapshot = snapshot(run.id(), "MODERATE", "0.70",
                "WATCH");
        OutcomeObservation observed = new OutcomeObservation(
                "SHADOW_OUTCOME_V1", "WATCH", List.of("600000:SSE"),
                Map.of("600000:SSE", new BigDecimal("-0.02")),
                new BigDecimal("-0.02"), false, true, true);

        var result = new ShadowOutcomeEvaluationService().evaluate(run,
                snapshot, List.of(outcome(run.id(), observed,
                        SIGNAL.plusSeconds(86_400))), null).get(0);

        assertTrue(!result.directionScorable());
        assertTrue(!result.directionHit());
        assertTrue(!result.errorTypes().contains("DIRECTION_MISS"));
    }

    @Test
    void paperReturnUsesOnlySnapshotKnownByOutcomeTime() {
        ShadowRun run = run();
        FrozenSnapshot snapshot = snapshot(run.id(), "MODERATE", "0.70");
        OutcomeObservation observed = new OutcomeObservation(
                "SHADOW_OUTCOME_V1", "RESEARCH_PREFERENCE",
                List.of("600000:SSE"),
                Map.of("600000:SSE", new BigDecimal("0.01")),
                new BigDecimal("0.01"), false, true, true);
        Instant outcomeAt = SIGNAL.plusSeconds(86_400);
        List<PortfolioSnapshot> portfolio = List.of(
                portfolio(run.id(), outcomeAt.minusSeconds(1), "0.02"),
                portfolio(run.id(), outcomeAt.plusSeconds(1), "0.99"));

        var result = new ShadowOutcomeEvaluationService().evaluate(run,
                snapshot, List.of(outcome(run.id(), observed, outcomeAt)),
                portfolio).get(0);

        assertEquals(new BigDecimal("0.02"), result.paperReturn());
    }

    private static ShadowRun run() {
        return new ShadowRun(1, "SHADOW_20260102_AFTER_CLOSE_1234567890abcdef",
                1, RunStatus.FROZEN, TriggerMode.SCHEDULED,
                LocalDate.of(2026, 1, 2), "AFTER_CLOSE", SIGNAL, SIGNAL,
                null, "M4_SHADOW_STRATEGY_V1", "STOCK_QUANT_FAKE",
                "DETERMINISTIC_FAKE_MODEL_V1", "M3_PROMPT_CATALOG_V2",
                "AGENT_RUNTIME_V1", "a".repeat(64), "b".repeat(64),
                "c".repeat(64), "d".repeat(64), null, SIGNAL, SIGNAL,
                SIGNAL);
    }

    private static FrozenSnapshot snapshot(long runId, String risk,
            String confidence) {
        return snapshot(runId, risk, confidence, "RESEARCH_PREFERENCE");
    }

    private static FrozenSnapshot snapshot(long runId, String risk,
            String confidence, String decisionCode) {
        var recommendation = new ShadowRecommendation(
                decisionCode, List.of("BUY_AND_HOLD"),
                List.of("600000:SSE", "000001:SZSE"), "BUY_AND_HOLD",
                risk, new BigDecimal(confidence), new BigDecimal("0.5"),
                List.of("EV_TEST"), List.of(), true, true);
        return new FrozenSnapshot(1, runId, "e".repeat(64), SIGNAL,
                AgentEvaluationTestFixtures.report(), recommendation);
    }

    private static ShadowOutcome outcome(long runId,
            OutcomeObservation observation, Instant evaluatedAt) {
        return new ShadowOutcome(1, runId, "D1",
                LocalDate.of(2026, 1, 5), observation, "f".repeat(64),
                evaluatedAt);
    }

    private static PortfolioSnapshot portfolio(
            long runId,
            Instant at,
            String totalReturn
    ) {
        return new PortfolioSnapshot(1, 1, runId,
                LocalDate.of(2026, 1, 5), at, new BigDecimal("100"),
                BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(totalReturn), 0, "a".repeat(64));
    }
}
