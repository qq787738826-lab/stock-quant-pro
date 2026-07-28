package com.stockquant.server.agent.service;

import com.stockquant.server.agent.chief.ChiefDecisionContracts;
import com.stockquant.server.agent.marketfacts.PitMarketFactsContracts;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentTypes.DecisionStatus;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentResultPersistenceServiceTest {

    @Test
    void treatsEveryDeterminedStage2IOutcomeAsCompleted() {
        for (FinalDecisionCode decision : List.of(
                FinalDecisionCode.REJECTED_BY_VETO,
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                FinalDecisionCode.RESEARCH_ONLY,
                FinalDecisionCode.WATCH,
                FinalDecisionCode.PASS_TO_MANUAL_REVIEW)) {
            assertEquals(
                    DecisionStatus.COMPLETED,
                    AgentResultPersistenceService.decisionStatus(response(
                            ChiefDecisionContracts.RULE_VERSION,
                            decision,
                            RunStatus.INSUFFICIENT_DATA,
                            RunStatus.PARTIAL)));
        }
    }

    @Test
    void keepsStage2IInsufficientDataNonCompleted() {
        assertEquals(
                DecisionStatus.INSUFFICIENT_DATA,
                AgentResultPersistenceService.decisionStatus(response(
                        ChiefDecisionContracts.RULE_VERSION,
                        FinalDecisionCode.INSUFFICIENT_DATA,
                        RunStatus.COMPLETED)));
    }

    @Test
    void givesPitV2TheSameFinalDecisionAwareTerminalMapping() {
        assertEquals(
                DecisionStatus.COMPLETED,
                AgentResultPersistenceService.decisionStatus(response(
                        PitMarketFactsContracts.RULE_VERSION,
                        FinalDecisionCode.WATCH,
                        RunStatus.PARTIAL)));
        assertEquals(
                DecisionStatus.INSUFFICIENT_DATA,
                AgentResultPersistenceService.decisionStatus(response(
                        PitMarketFactsContracts.RULE_VERSION,
                        FinalDecisionCode.INSUFFICIENT_DATA,
                        RunStatus.COMPLETED)));
    }

    @Test
    void keepsLegacyRunDerivedPersistenceSemantics() {
        assertEquals(
                DecisionStatus.PARTIAL,
                AgentResultPersistenceService.decisionStatus(response(
                        "1.4.0-stage-2h-position-risk-v1",
                        FinalDecisionCode.REJECTED_BY_VETO,
                        RunStatus.PARTIAL)));
        assertEquals(
                DecisionStatus.INSUFFICIENT_DATA,
                AgentResultPersistenceService.decisionStatus(response(
                        "1.4.0-stage-2g-announcement-risk-v1",
                        FinalDecisionCode.INSUFFICIENT_DATA,
                        RunStatus.INSUFFICIENT_DATA)));
        assertEquals(
                DecisionStatus.COMPLETED,
                AgentResultPersistenceService.decisionStatus(response(
                        "1.4.0-stage-2f-strategy-backtest-v1",
                        FinalDecisionCode.INSUFFICIENT_DATA,
                        RunStatus.COMPLETED)));
    }

    private static AgentTeamResponse response(
            String ruleVersion,
            FinalDecisionCode decision,
            RunStatus... runStatuses
    ) {
        List<AgentOutput> runs = java.util.Arrays.stream(runStatuses)
                .map(AgentResultPersistenceServiceTest::output)
                .toList();
        FinalDecision finalDecision = new FinalDecision(
                null,
                1L,
                decision,
                null,
                false,
                0,
                0,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                ruleVersion,
                null,
                null);
        return new AgentTeamResponse(
                null,
                1L,
                null,
                null,
                ruleVersion,
                null,
                runs,
                List.of(),
                List.of(),
                finalDecision,
                null);
    }

    private static AgentOutput output(RunStatus status) {
        return new AgentOutput(
                null,
                1L,
                1L,
                null,
                status,
                null,
                null,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);
    }
}
