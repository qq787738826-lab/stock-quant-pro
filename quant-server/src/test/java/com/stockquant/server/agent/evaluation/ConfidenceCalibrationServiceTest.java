package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.EvaluationStatus;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ShadowOutcomeEvaluation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceCalibrationServiceTest {
    @Test
    void abstentionIsCountedButNeverTreatedAsWrongRecommendation() {
        var values = new ArrayList<ShadowOutcomeEvaluation>();
        values.add(outcome(1, "0.0", false, true));
        values.add(outcome(2, "0.8", true, false));
        var result = new ConfidenceCalibrationService().evaluate(values);

        assertEquals(1, result.eligibleSampleCount());
        assertEquals(1, result.abstentionCount());
        assertEquals(EvaluationStatus.INSUFFICIENT_SAMPLE, result.status());
        assertTrue(result.brierScore().compareTo(new BigDecimal("0.05")) < 0);
    }

    @Test
    void twentyOutcomesUnlockCalibrationWithoutInflatingConfidence() {
        List<ShadowOutcomeEvaluation> values = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            values.add(outcome(i + 1, i < 14 ? "0.70" : "0.55",
                    i < 14, false));
        }
        var result = new ConfidenceCalibrationService().evaluate(values);

        assertEquals(EvaluationStatus.PASS, result.status());
        assertEquals(20, result.eligibleSampleCount());
        assertTrue(result.expectedCalibrationError()
                .compareTo(new BigDecimal("0.30")) < 0);
    }

    @Test
    void overconfidentWrongPredictionsFailCalibration() {
        List<ShadowOutcomeEvaluation> values = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            values.add(outcome(i + 1, "0.95", false, false));
        }

        var result = new ConfidenceCalibrationService().evaluate(values);

        assertEquals(EvaluationStatus.FAIL, result.status());
        assertTrue(result.brierScore().compareTo(new BigDecimal("0.25")) > 0);
        assertTrue(result.expectedCalibrationError()
                .compareTo(new BigDecimal("0.20")) > 0);
    }

    @Test
    void nonDirectionalRecommendationsRemainUnscored() {
        var result = new ConfidenceCalibrationService().evaluate(List.of(
                new ShadowOutcomeEvaluation(1, "D1",
                        Instant.parse("2026-01-01T08:00:00Z"),
                        Instant.parse("2026-01-02T08:00:00Z"),
                        LocalDate.of(2026, 1, 2), "WATCH",
                        new BigDecimal("0.90"), new BigDecimal("0.05"),
                        BigDecimal.ONE, false, false, false, "MODERATE",
                        false, BigDecimal.ZERO, List.of(), true)));

        assertEquals(0, result.eligibleSampleCount());
        assertEquals(0, result.abstentionCount());
        assertEquals(EvaluationStatus.INSUFFICIENT_SAMPLE, result.status());
    }

    private static ShadowOutcomeEvaluation outcome(long id,
            String confidence, boolean hit, boolean abstention) {
        return new ShadowOutcomeEvaluation(id, "D1",
                Instant.parse("2026-01-01T08:00:00Z"),
                Instant.parse("2026-01-02T08:00:00Z"),
                LocalDate.of(2026, 1, 2), abstention
                ? "INSUFFICIENT_EVIDENCE" : "RESEARCH_PREFERENCE",
                new BigDecimal(confidence),
                hit ? new BigDecimal("0.01") : new BigDecimal("-0.01"),
                BigDecimal.ONE, hit, !abstention, abstention, "MODERATE", false,
                BigDecimal.ZERO, hit ? List.of()
                : List.of("DIRECTION_MISS"), true);
    }
}
