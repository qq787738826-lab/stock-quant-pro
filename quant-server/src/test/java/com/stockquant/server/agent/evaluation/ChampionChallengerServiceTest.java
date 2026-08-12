package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ComparisonDecision;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.EvaluationStatus;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.VersionEvaluation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChampionChallengerServiceTest {
    @Test
    void noRealShadowSampleCanNeverPromoteChallenger() {
        VersionEvaluation champion = evaluation("M5V_CHAMPION_BASELINE_V1",
                "90", 0, true);
        VersionEvaluation challenger = evaluation("M5V_CHALLENGER_GUARD_V1",
                "96", 0, true);

        var result = new ChampionChallengerService().compare(champion,
                challenger);

        assertEquals(ComparisonDecision.WATCH_CHALLENGER, result.decision());
        assertFalse(result.promotionAllowed());
    }

    @Test
    void safetyRegressionRejectsEvenHighScoringChallenger() {
        VersionEvaluation champion = evaluation("M5V_CHAMPION_BASELINE_V1",
                "90", 20, true);
        VersionEvaluation challenger = evaluation("M5V_CHALLENGER_GUARD_V1",
                "99", 20, false);

        assertEquals(ComparisonDecision.REJECT_CHALLENGER,
                new ChampionChallengerService().compare(champion,
                        challenger).decision());
    }

    @Test
    void eligibleChallengerPromotesOnlyAfterEveryGatePasses() {
        VersionEvaluation champion = evaluation("M5V_CHAMPION_BASELINE_V1",
                "90", 20, true);
        VersionEvaluation challenger = evaluation("M5V_CHALLENGER_GUARD_V1",
                "96", 20, true);

        var result = new ChampionChallengerService().compare(champion,
                challenger);

        assertEquals(ComparisonDecision.PROMOTE_CHALLENGER,
                result.decision());
        assertTrue(result.promotionAllowed());
        assertTrue(result.minimumEvidencePassed());
        assertTrue(result.overfitGuardPassed());
    }

    private static VersionEvaluation evaluation(String key, String score,
            int samples, boolean safe) {
        var report = AgentEvaluationTestFixtures.report();
        var cards = new AgentScorecardService().scoreAll(key,
                List.of(report, report, report));
        var outcomes = new java.util.ArrayList<AgentEvaluationModels
                .ShadowOutcomeEvaluation>();
        for (int i = 0; i < samples; i++) {
            outcomes.add(new AgentEvaluationModels.ShadowOutcomeEvaluation(
                    i + 1, "D1", AgentEvaluationTestFixtures.NOW,
                    AgentEvaluationTestFixtures.NOW.plusSeconds(86_400),
                    java.time.LocalDate.of(2026, 8, 13),
                    "RESEARCH_PREFERENCE", new BigDecimal("0.9"),
                    new BigDecimal("0.01"), BigDecimal.ONE, true, true,
                    false, "MODERATE", false, BigDecimal.ZERO, List.of(),
                    true));
        }
        var calibration = new ConfidenceCalibrationService().evaluate(outcomes);
        return new VersionEvaluation(key, cards, calibration, outcomes,
                15, 15, 60, safe, safe, safe, 13, 1000,
                BigDecimal.ONE, "CNY", Duration.ofSeconds(10),
                new BigDecimal(score), safe ? calibration.status()
                : EvaluationStatus.FAIL, List.of(),
                AgentEvaluationCanonical.hash(key + score + samples + safe));
    }
}
