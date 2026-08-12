package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.LifecycleDecision;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScorecardServiceTest {
    @Test
    void sevenRolesUseExplainableDistinctWeights() {
        var report = AgentEvaluationTestFixtures.report();
        var cards = new AgentScorecardService().scoreAll(
                "M5V_CHAMPION_BASELINE_V1", List.of(report, report, report));

        assertEquals(7, cards.size());
        assertTrue(cards.stream().allMatch(card ->
                card.metrics().stream().map(value -> value.weight())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .compareTo(BigDecimal.ONE) == 0));
        var data = cards.stream().filter(card -> card.role()
                == AgentRole.DATA_ANALYST).findFirst().orElseThrow();
        var critic = cards.stream().filter(card -> card.role()
                == AgentRole.CRITIC_REVIEW).findFirst().orElseThrow();
        assertNotEquals(data.metrics(), critic.metrics());
        assertEquals(LifecycleDecision.RETAIN, data.lifecycleDecision());
        assertTrue(data.weightedScore().compareTo(new BigDecimal("90")) >= 0);
    }

    @Test
    void smallSampleIsWatchInsteadOfAutomaticDemotion() {
        var card = new AgentScorecardService().score(
                "M5V_CHAMPION_BASELINE_V1", AgentRole.RISK,
                List.of(AgentEvaluationTestFixtures.report()));

        assertEquals(LifecycleDecision.WATCH, card.lifecycleDecision());
        assertTrue(card.rationale().contains("ABSTENTION_IS_NOT_A_FAILURE"));
        assertTrue(card.rationale().contains(
                "MINIMUM_REPORT_SAMPLE_NOT_MET"));
    }
}
