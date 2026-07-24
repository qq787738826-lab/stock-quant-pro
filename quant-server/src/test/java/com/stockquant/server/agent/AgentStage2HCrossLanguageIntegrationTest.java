package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
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

@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage2HCrossLanguageIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentResponseValidator validator = new AgentResponseValidator();

    @Test
    void noRiskSingleVetoMultiVetoAndPartialCloseAcrossLanguages()
            throws Exception {
        AgentTeamResponse passed = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.PASS));
        AgentOutput passRun = run(passed, AgentCode.POSITION_RISK);
        assertEquals(RunStatus.COMPLETED, passRun.status());
        assertEquals(GateStatus.PASS, passRun.gateStatus());
        assertEquals(100, passRun.score());
        assertEquals(100, passRun.confidence());
        assertFalse(passRun.veto());
        assertTrue(passed.vetoes().isEmpty());
        assertEquals(FinalDecisionCode.INSUFFICIENT_DATA,
                passed.finalDecision().decision());
        assertEquals(6, passed.agentRuns().size());

        AgentTeamResponse vetoed = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.SINGLE_VETO));
        assertEquals(List.of("POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT"),
                vetoed.vetoes().stream().map(item -> item.vetoCode()).toList());
        assertEquals(FinalDecisionCode.REJECTED_BY_VETO,
                vetoed.finalDecision().decision());
        assertTrue(vetoed.finalDecision().vetoed());
        assertEquals(0, run(vetoed, AgentCode.POSITION_RISK).score());

        AgentTeamResponse multiple = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.MULTI_VETO));
        assertEquals(List.of(
                        "POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
                        "POSITION_RISK_DAILY_LOSS_LIMIT"),
                multiple.vetoes().stream().map(item -> item.vetoCode()).toList());
        assertEquals(
                multiple.vetoes().stream().map(item -> item.vetoId()).toList(),
                multiple.finalDecision().vetoIds());

        AgentTeamResponse partial = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.PARTIAL));
        AgentOutput partialRun = run(partial, AgentCode.POSITION_RISK);
        assertEquals(RunStatus.PARTIAL, partialRun.status());
        assertEquals(GateStatus.WARN, partialRun.gateStatus());
        assertEquals(60, partialRun.confidence());
    }

    @Test
    void formalVetoPrecedesDataQualityBlock() throws Exception {
        AgentTeamResponse response = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.DATA_QUALITY_BLOCKED_WITH_VETO));
        assertEquals(GateStatus.BLOCKED,
                run(response, AgentCode.DATA_QUALITY).gateStatus());
        assertEquals(GateStatus.BLOCKED,
                run(response, AgentCode.POSITION_RISK).gateStatus());
        assertEquals(FinalDecisionCode.REJECTED_BY_VETO,
                response.finalDecision().decision());
        assertTrue(response.finalDecision().vetoed());
    }

    @Test
    void unavailableAndInvalidPortfolioContextsFailSafe() throws Exception {
        AgentTeamResponse unavailable = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.UNAVAILABLE));
        AgentOutput unavailableRun = run(unavailable, AgentCode.POSITION_RISK);
        assertEquals(RunStatus.INSUFFICIENT_DATA, unavailableRun.status());
        assertEquals(List.of("PORTFOLIO_PRICE_MISSING"),
                unavailableRun.errors().stream().map(item -> item.code()).toList());
        assertTrue(unavailable.vetoes().isEmpty());

        AgentTeamResponse invalid = call(AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.INVALID));
        AgentOutput invalidRun = run(invalid, AgentCode.POSITION_RISK);
        assertEquals(List.of("POSITION_RISK_INPUT_INVALID"),
                invalidRun.errors().stream().map(item -> item.code()).toList());
        assertTrue(invalidRun.evidence().isEmpty());
        assertTrue(invalid.vetoes().isEmpty());
    }

    @Test
    void javaRejectsTamperedVetoScoreEvidenceAndChiefDecision() throws Exception {
        AgentTeamRequest request = AgentStage2HTestFixtures.request(
                AgentStage2HTestFixtures.Scenario.SINGLE_VETO);
        AgentTeamResponse source = call(request);

        ObjectNode score = mapper.valueToTree(source);
        positionRun(score).put("score", 1);
        assertRejected(request, score);

        ObjectNode vetoCode = mapper.valueToTree(source);
        ((ObjectNode) vetoCode.withArray("vetoes").get(0))
                .put("vetoCode", "POSITION_RISK_DAILY_LOSS_LIMIT");
        assertRejected(request, vetoCode);

        ObjectNode evidence = mapper.valueToTree(source);
        ((ObjectNode) positionRun(evidence).withArray("evidence").get(0))
                .put("contentHash", "0".repeat(64));
        assertRejected(request, evidence);

        ObjectNode decision = mapper.valueToTree(source);
        ((ObjectNode) decision.path("finalDecision"))
                .put("decision", "BLOCKED_BY_DATA_QUALITY");
        assertRejected(request, decision);

        ObjectNode instruction = mapper.valueToTree(source);
        positionRun(instruction).put("summary", "立即卖出");
        assertRejected(request, instruction);
    }

    private AgentTeamResponse call(AgentTeamRequest request) throws Exception {
        String baseUrl = AgentPythonSmokeEnvironment.validate(
                System.getenv("STOCK_QUANT_PYTHON_BASE_URL"));
        byte[] payload = mapper.writeValueAsBytes(request);
        HttpURLConnection connection = (HttpURLConnection) URI
                .create(baseUrl + "/agents/team/analyze").toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(
                stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        assertEquals(200, status, body);
        AgentTeamResponse response = mapper.readValue(body, AgentTeamResponse.class);
        assertDoesNotThrow(() -> validator.validate(request, response));
        return response;
    }

    private void assertRejected(
            AgentTeamRequest request,
            JsonNode response
    ) throws Exception {
        AgentTeamResponse changed = mapper.treeToValue(
                response, AgentTeamResponse.class);
        assertThrows(RuntimeException.class,
                () -> validator.validate(request, changed));
    }

    private static AgentOutput run(
            AgentTeamResponse response,
            AgentCode code
    ) {
        return response.agentRuns().stream()
                .filter(item -> item.agentCode() == code)
                .findFirst().orElseThrow();
    }

    private static ObjectNode positionRun(ObjectNode response) {
        for (JsonNode item : response.withArray("agentRuns")) {
            if ("POSITION_RISK".equals(item.path("agentCode").asText())) {
                return (ObjectNode) item;
            }
        }
        throw new AssertionError("缺少POSITION_RISK运行");
    }
}
