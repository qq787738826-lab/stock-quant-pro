package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_PYTHON_BASE_URL",
        matches = ".+")
class AgentStage2ICrossLanguageIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentResponseValidator validator =
            new AgentResponseValidator();

    @Test
    void passToManualReviewClosesAcrossRealHttp() throws Exception {
        AgentTeamResponse response = call(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario.NO_EVENT));
        assertEquals(
                FinalDecisionCode.PASS_TO_MANUAL_REVIEW,
                response.finalDecision().decision());
        assertEquals(GateStatus.PASS, response.finalDecision().gateStatus());
        assertEquals(100, response.finalDecision().score());
        assertEquals(67, response.finalDecision().confidence());
        assertEquals(6, response.agentRuns().size());
        assertFalse(response.finalDecision().vetoed());
    }

    @Test
    void warningRiskProducesWatchAcrossRealHttp() throws Exception {
        AgentTeamResponse response = call(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario.WARN_EVENT));
        assertEquals(
                FinalDecisionCode.WATCH,
                response.finalDecision().decision());
        assertEquals(GateStatus.WARN, response.finalDecision().gateStatus());
        assertTrue(response.finalDecision().score() >= 70);
    }

    @Test
    void highRiskProducesResearchOnlyAcrossRealHttp() throws Exception {
        AgentTeamResponse response = call(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario.MULTI_RISK));
        assertEquals(
                FinalDecisionCode.RESEARCH_ONLY,
                response.finalDecision().decision());
        assertEquals(GateStatus.WARN, response.finalDecision().gateStatus());
    }

    @Test
    void unavailableContributorProducesInsufficientAcrossRealHttp()
            throws Exception {
        for (AgentStage2ITestFixtures.Scenario scenario : List.of(
                AgentStage2ITestFixtures.Scenario.ANNOUNCEMENT_UNAVAILABLE,
                AgentStage2ITestFixtures.Scenario.POSITION_UNAVAILABLE)) {
            AgentTeamResponse response = call(
                    AgentStage2ITestFixtures.request(scenario));
            assertEquals(
                    FinalDecisionCode.INSUFFICIENT_DATA,
                    response.finalDecision().decision(),
                    scenario.name());
            assertEquals(0, response.finalDecision().score());
            assertEquals(0, response.finalDecision().confidence());
        }
    }

    @Test
    void positionVetoPrecedesAllOtherStatesAcrossRealHttp()
            throws Exception {
        AgentTeamResponse response = call(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario.POSITION_VETO));
        assertEquals(
                FinalDecisionCode.REJECTED_BY_VETO,
                response.finalDecision().decision());
        assertTrue(response.finalDecision().vetoed());
        assertEquals(
                response.vetoes().stream().map(item -> item.vetoId()).toList(),
                response.finalDecision().vetoIds());
    }

    @Test
    void dataQualityBlockAndVetoPriorityCloseAcrossRealHttp()
            throws Exception {
        AgentTeamResponse blocked = call(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario
                                .DATA_QUALITY_BLOCKED));
        assertEquals(
                FinalDecisionCode.BLOCKED_BY_DATA_QUALITY,
                blocked.finalDecision().decision());

        AgentTeamResponse vetoed = call(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario
                                .DATA_QUALITY_BLOCKED_WITH_VETO));
        assertEquals(
                FinalDecisionCode.REJECTED_BY_VETO,
                vetoed.finalDecision().decision());
    }

    @Test
    void javaRejectsTamperedCompositeScore() throws Exception {
        assertTamperRejected(response -> response.with("finalDecision")
                .put("score", 99));
    }

    @Test
    void javaRejectsTamperedCompositeConfidence() throws Exception {
        assertTamperRejected(response -> response.with("finalDecision")
                .put("confidence", 80));
    }

    @Test
    void javaRejectsTamperedSummary() throws Exception {
        assertTamperRejected(response -> response.with("finalDecision")
                .put("summary", "tampered deterministic summary"));
    }

    @Test
    void javaRejectsWrongSourceRunOrder() throws Exception {
        assertTamperRejected(response -> {
            ArrayNode values = response.with("finalDecision")
                    .withArray("sourceRunIds");
            JsonNode first = values.get(0);
            JsonNode second = values.get(1);
            values.set(0, second);
            values.set(1, first);
        });
    }

    @Test
    void javaRejectsWrongFinalFindingOrder() throws Exception {
        assertTamperRejected(response -> {
            ArrayNode values = response.with("finalDecision")
                    .withArray("findings");
            JsonNode first = values.get(0);
            JsonNode second = values.get(1);
            values.set(0, second);
            values.set(1, first);
        });
    }

    @Test
    void javaRejectsForgedNonPositionVeto() throws Exception {
        assertTamperRejected(response -> {
            ObjectNode technical = run(
                    response, AgentCode.TECHNICAL_ANALYSIS);
            technical.put("veto", true);
            technical.put("decision", "REJECT");
            String evidenceId = technical.withArray("evidence")
                    .get(0).path("evidenceId").asText();
            ObjectNode veto = response.withArray("vetoes").addObject();
            veto.put("vetoId", "forged-technical-veto");
            veto.put("taskId", response.path("taskId").asLong());
            veto.put("runId", technical.path("runId").asLong());
            veto.put("agentCode", "TECHNICAL_ANALYSIS");
            veto.put("vetoCode", "FORGED_TECHNICAL_VETO");
            veto.put("reason", "forged");
            veto.putArray("evidenceIds").add(evidenceId);
            veto.put("createdAt", response.path("generatedAt").asText());
            ObjectNode decision = response.with("finalDecision");
            decision.put("decision", "REJECTED_BY_VETO");
            decision.put("gateStatus", "BLOCKED");
            decision.put("vetoed", true);
            decision.put("score", 0);
            decision.putArray("vetoIds").add("forged-technical-veto");
        });
    }

    private AgentTeamResponse call(AgentTeamRequest request)
            throws Exception {
        byte[] payload = mapper.writeValueAsBytes(request);
        String baseUrl = AgentPythonSmokeEnvironment.validate(
                System.getenv("STOCK_QUANT_PYTHON_BASE_URL"));
        HttpURLConnection connection = (HttpURLConnection) URI
                .create(baseUrl + "/agents/team/analyze")
                .toURL()
                .openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(20_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty(
                "Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        String body = stream == null
                ? ""
                : new String(
                stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        assertEquals(200, status, body);
        AgentTeamResponse response = mapper.readValue(
                body, AgentTeamResponse.class);
        assertDoesNotThrow(() -> validator.validate(request, response));
        return response;
    }

    private void assertTamperRejected(Tamper tamper) throws Exception {
        AgentTeamRequest request = AgentStage2ITestFixtures.request(
                AgentStage2ITestFixtures.Scenario.NO_EVENT);
        ObjectNode response = mapper.valueToTree(call(request));
        tamper.apply(response);
        AgentTeamResponse changed = mapper.treeToValue(
                response, AgentTeamResponse.class);
        assertThrows(
                RuntimeException.class,
                () -> validator.validate(request, changed));
    }

    private static ObjectNode run(
            ObjectNode response,
            AgentCode code
    ) {
        for (JsonNode value : response.withArray("agentRuns")) {
            if (code.name().equals(
                    value.path("agentCode").asText())) {
                return (ObjectNode) value;
            }
        }
        throw new AssertionError("missing run " + code);
    }

    @FunctionalInterface
    private interface Tamper {
        void apply(ObjectNode response);
    }
}
