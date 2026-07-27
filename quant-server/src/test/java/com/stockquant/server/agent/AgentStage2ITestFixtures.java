package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.chief.ChiefDecisionContracts;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.service.AgentContextHashService;

final class AgentStage2ITestFixtures {

    private static final AgentContextHashService HASHES =
            new AgentContextHashService(
                    new ObjectMapper().findAndRegisterModules());

    enum Scenario {
        NO_EVENT,
        WARN_EVENT,
        MULTI_RISK,
        ANNOUNCEMENT_UNAVAILABLE,
        POSITION_PARTIAL,
        POSITION_UNAVAILABLE,
        POSITION_VETO,
        DATA_QUALITY_BLOCKED,
        DATA_QUALITY_BLOCKED_WITH_VETO
    }

    private AgentStage2ITestFixtures() {
    }

    static AgentTeamRequest request(Scenario scenario) {
        AgentTeamRequest base = switch (scenario) {
            case NO_EVENT -> AgentStage2GTestFixtures.request(
                    AgentStage2GTestFixtures.Scenario.NO_EVENT);
            case WARN_EVENT -> AgentStage2GTestFixtures.request(
                    AgentStage2GTestFixtures.Scenario.WARN_RISK);
            case MULTI_RISK -> AgentStage2GTestFixtures.request(
                    AgentStage2GTestFixtures.Scenario.MULTI_RISK);
            case ANNOUNCEMENT_UNAVAILABLE ->
                    AgentStage2GTestFixtures.request(
                            AgentStage2GTestFixtures.Scenario.UNAVAILABLE);
            case POSITION_VETO -> AgentStage2GTestFixtures.request(
                    AgentStage2GTestFixtures.Scenario.POSITION_VETO);
            case DATA_QUALITY_BLOCKED_WITH_VETO ->
                    AgentStage2GTestFixtures.request(
                            AgentStage2GTestFixtures.Scenario
                                    .DATA_QUALITY_BLOCKED_WITH_VETO);
            case POSITION_PARTIAL -> stage2H(
                    AgentStage2HTestFixtures.Scenario.PARTIAL);
            case POSITION_UNAVAILABLE -> stage2H(
                    AgentStage2HTestFixtures.Scenario.UNAVAILABLE);
            case DATA_QUALITY_BLOCKED -> stage2F(
                    AgentStage2FTestFixtures.Scenario.BLOCKED);
        };
        return withRuleVersion(base);
    }

    private static AgentTeamRequest stage2H(
            AgentStage2HTestFixtures.Scenario scenario
    ) {
        AgentTeamRequest portfolio =
                AgentStage2HTestFixtures.request(scenario);
        AgentTeamRequest events = AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.NO_EVENT);
        ObjectNode snapshot =
                (ObjectNode) portfolio.contextSnapshot().deepCopy();
        snapshot.set(
                "securityEvents",
                events.contextSnapshot().path("securityEvents").deepCopy());
        String hash = HASHES.hash(snapshot);
        return new AgentTeamRequest(
                portfolio.schemaVersion(),
                portfolio.taskId(),
                portfolio.runIds(),
                portfolio.symbol(),
                portfolio.tradeDate(),
                hash,
                portfolio.contextSchemaVersion(),
                portfolio.ruleVersion(),
                portfolio.executionMode(),
                snapshot,
                portfolio.requestedAt());
    }

    private static AgentTeamRequest stage2F(
            AgentStage2FTestFixtures.Scenario scenario
    ) {
        AgentTeamRequest quality =
                AgentStage2FTestFixtures.request(scenario);
        AgentTeamRequest complete = AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.NO_EVENT);
        ObjectNode snapshot =
                (ObjectNode) quality.contextSnapshot().deepCopy();
        snapshot.set(
                "securityEvents",
                complete.contextSnapshot().path("securityEvents").deepCopy());
        snapshot.set(
                "portfolioContext",
                complete.contextSnapshot().path("portfolioContext").deepCopy());
        String hash = HASHES.hash(snapshot);
        return new AgentTeamRequest(
                quality.schemaVersion(),
                quality.taskId(),
                quality.runIds(),
                quality.symbol(),
                quality.tradeDate(),
                hash,
                quality.contextSchemaVersion(),
                quality.ruleVersion(),
                quality.executionMode(),
                snapshot,
                quality.requestedAt());
    }

    private static AgentTeamRequest withRuleVersion(AgentTeamRequest base) {
        return new AgentTeamRequest(
                base.schemaVersion(),
                base.taskId(),
                base.runIds(),
                base.symbol(),
                base.tradeDate(),
                base.contextHash(),
                base.contextSchemaVersion(),
                ChiefDecisionContracts.RULE_VERSION,
                base.executionMode(),
                base.contextSnapshot(),
                base.requestedAt());
    }
}
