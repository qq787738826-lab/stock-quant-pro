package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage2ECrossLanguageIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentResponseValidator validator = new AgentResponseValidator();
    @Test
    void javaRequestPythonRulesAndJavaValidationCloseAllQualityPaths() throws Exception {
        AgentTeamResponse passed = call(AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.PASS));
        AgentOutput passTechnical = run(passed, AgentCode.TECHNICAL_ANALYSIS);
        assertEquals(RunStatus.COMPLETED, passTechnical.status());
        assertEquals(GateStatus.PASS, passTechnical.gateStatus());
        assertEquals(100, passTechnical.score());
        assertEquals(100, passTechnical.confidence());
        assertEquals(List.of(
                        "TECH_TREND_BULLISH_ALIGNED",
                        "TECH_RSI_POSITIVE_MOMENTUM",
                        "TECH_PRICE_NEAR_MA20",
                        "TECH_VOLATILITY_NORMAL",
                        "TECH_INDICATORS_BULLISH_CONFIRMED"),
                passTechnical.findings().stream().map(item -> item.code()).toList());
        assertEquals(2, passTechnical.evidence().size());
        assertFalse(passTechnical.veto());
        assertTrue(passed.vetoes().isEmpty());
        assertFalse(passed.finalDecision().vetoed());
        assertEquals(6, passed.agentRuns().size());
        assertEquals(AgentCode.PROFESSIONAL_AGENTS, passed.agentRuns().stream()
                .map(AgentOutput::agentCode).toList());

        AgentTeamResponse warned = call(AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.WARN));
        AgentOutput warnTechnical = run(warned, AgentCode.TECHNICAL_ANALYSIS);
        assertEquals(GateStatus.WARN, warnTechnical.gateStatus());
        assertEquals(50, warnTechnical.confidence());
        assertEquals(100, warnTechnical.score());

        AgentTeamResponse blocked = call(AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.BLOCKED));
        AgentOutput blockedTechnical = run(blocked, AgentCode.TECHNICAL_ANALYSIS);
        assertEquals(RunStatus.INSUFFICIENT_DATA, blockedTechnical.status());
        assertEquals(GateStatus.NOT_APPLICABLE, blockedTechnical.gateStatus());
        assertTrue(blockedTechnical.findings().isEmpty());
        assertTrue(blockedTechnical.evidence().isEmpty());
        assertTrue(blockedTechnical.errors().isEmpty());

        AgentTeamResponse invalidInput = call(AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.INVALID_TECHNICAL_INPUT));
        AgentOutput invalidTechnical = run(invalidInput, AgentCode.TECHNICAL_ANALYSIS);
        assertEquals(RunStatus.INSUFFICIENT_DATA, invalidTechnical.status());
        assertEquals(GateStatus.PASS, invalidTechnical.gateStatus());
        assertEquals(0, invalidTechnical.score());
        assertEquals(0, invalidTechnical.confidence());
        assertEquals(List.of("TECHNICAL_ANALYSIS_INPUT_INVALID"),
                invalidTechnical.errors().stream().map(item -> item.code()).toList());
    }

    @Test
    void sameContextHashProducesSameBusinessResult() throws Exception {
        AgentTeamRequest request = AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.PASS);
        JsonNode first = stableBusinessJson(call(request));
        JsonNode second = stableBusinessJson(call(request));
        assertEquals(first, second);
    }

    @Test
    void javaRejectsTamperedScoreFindingEvidenceAndPrematureFinalUpgrade() throws Exception {
        AgentTeamRequest request = AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.PASS);
        AgentTeamResponse source = call(request);

        ObjectNode score = mapper.valueToTree(source);
        technicalRun(score).put("score", 99);
        assertRejected(request, score);

        ObjectNode finding = mapper.valueToTree(source);
        ((ObjectNode) technicalRun(finding).path("findings").get(0))
                .put("detail", "tampered");
        assertRejected(request, finding);

        ObjectNode evidence = mapper.valueToTree(source);
        ObjectNode runEvidence = technicalEvidence(technicalRun(evidence), "ta-metrics-");
        ObjectNode topEvidence = technicalEvidence(evidence, "ta-metrics-");
        ((ObjectNode) runEvidence.path("fields").path("technicalMetrics"))
                .put("queriedAt", "forbidden-extra-field");
        ((ObjectNode) topEvidence.path("fields").path("technicalMetrics"))
                .put("queriedAt", "forbidden-extra-field");
        assertRejected(request, evidence);

        ObjectNode upgraded = mapper.valueToTree(source);
        ((ObjectNode) upgraded.path("finalDecision")).put("decision", "WATCH");
        assertRejected(request, upgraded);
    }

    @Test
    void responseContainsNoInvestmentInstructionOrReturnPromise() throws Exception {
        String body = mapper.writeValueAsString(call(AgentStage2ETestFixtures.request(
                AgentStage2ETestFixtures.Scenario.PASS)));
        for (String forbidden : Set.of("买入", "卖出", "加仓", "减仓", "目标价", "收益承诺")) {
            assertFalse(body.contains(forbidden), forbidden);
        }
    }

    private AgentTeamResponse call(AgentTeamRequest request) throws Exception {
        String baseUrl = AgentPythonSmokeEnvironment.validate(
                System.getenv("STOCK_QUANT_PYTHON_BASE_URL"));
        byte[] payload = mapper.writeValueAsBytes(request);
        HttpURLConnection connection = (HttpURLConnection) URI
                .create(baseUrl + "/agents/team/analyze").toURL().openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        InputStream responseStream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String body = responseStream == null
                ? "" : new String(responseStream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        assertEquals(200, status, body);
        AgentTeamResponse response = mapper.readValue(body, AgentTeamResponse.class);
        assertDoesNotThrow(() -> validator.validate(request, response));
        return response;
    }

    private void assertRejected(AgentTeamRequest request, JsonNode response) throws Exception {
        AgentTeamResponse changed = mapper.treeToValue(response, AgentTeamResponse.class);
        assertThrows(AgentTeamException.class, () -> validator.validate(request, changed));
    }

    private JsonNode stableBusinessJson(AgentTeamResponse response) {
        ObjectNode value = mapper.valueToTree(response);
        value.remove("generatedAt");
        ((ObjectNode) value.path("finalDecision")).remove("generatedAt");
        for (JsonNode run : value.withArray("agentRuns")) {
            ((ObjectNode) run).remove("generatedAt");
        }
        return value;
    }

    private static AgentOutput run(AgentTeamResponse response, AgentCode code) {
        return response.agentRuns().stream()
                .filter(item -> item.agentCode() == code)
                .findFirst().orElseThrow();
    }

    private static ObjectNode technicalRun(ObjectNode response) {
        for (JsonNode item : response.withArray("agentRuns")) {
            if ("TECHNICAL_ANALYSIS".equals(item.path("agentCode").asText())) {
                return (ObjectNode) item;
            }
        }
        throw new AssertionError("缺少TECHNICAL_ANALYSIS运行");
    }

    private static ObjectNode technicalEvidence(JsonNode container, String prefix) {
        ArrayNode items = (ArrayNode) container.path("evidence");
        for (JsonNode item : items) {
            if (item.path("evidenceId").asText().startsWith(prefix)) return (ObjectNode) item;
        }
        throw new AssertionError("缺少阶段2E-1技术证据：" + prefix);
    }
}
