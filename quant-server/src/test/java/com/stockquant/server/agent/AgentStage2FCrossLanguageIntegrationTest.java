package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
class AgentStage2FCrossLanguageIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentResponseValidator validator = new AgentResponseValidator();

    @Test
    void javaContextPythonRulesAndJavaValidationCloseAllSafetyPaths()
            throws Exception {
        AgentTeamResponse passed = call(AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.PASS));
        AgentOutput strategy = run(passed, AgentCode.STRATEGY_BACKTEST);
        assertEquals(RunStatus.COMPLETED, strategy.status());
        assertEquals(GateStatus.PASS, strategy.gateStatus());
        assertEquals(40, strategy.confidence());
        assertEquals(List.of(
                        "STRATEGY_BACKTEST_SAMPLE_SUFFICIENT",
                        "STRATEGY_BACKTEST_TOTAL_RETURN_ASSESSED",
                        "STRATEGY_BACKTEST_MAX_DRAWDOWN_ASSESSED",
                        "STRATEGY_BACKTEST_WIN_LOSS_QUALITY_ASSESSED",
                        "STRATEGY_BACKTEST_SUBPERIOD_STABILITY_ASSESSED"),
                strategy.findings().stream().map(item -> item.code()).toList());
        assertEquals(1, strategy.evidence().size());
        assertFalse(strategy.veto());
        assertEquals(6, passed.agentRuns().size());
        assertEquals(AgentCode.PROFESSIONAL_AGENTS, passed.agentRuns().stream()
                .map(AgentOutput::agentCode).toList());
        assertTrue(passed.vetoes().isEmpty());
        assertFalse(passed.finalDecision().vetoed());
        assertEquals("INSUFFICIENT_DATA", passed.finalDecision().decision().name());

        AgentTeamResponse warned = call(AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.WARN));
        AgentOutput warnStrategy = run(warned, AgentCode.STRATEGY_BACKTEST);
        assertEquals(RunStatus.COMPLETED, warnStrategy.status());
        assertEquals(GateStatus.WARN, warnStrategy.gateStatus());
        assertEquals(40, warnStrategy.confidence());

        AgentTeamResponse blocked = call(AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.BLOCKED));
        AgentOutput blockedStrategy = run(blocked, AgentCode.STRATEGY_BACKTEST);
        assertEquals(RunStatus.INSUFFICIENT_DATA, blockedStrategy.status());
        assertEquals(GateStatus.BLOCKED, blockedStrategy.gateStatus());
        assertTrue(blockedStrategy.findings().isEmpty());
        assertTrue(blockedStrategy.evidence().isEmpty());
        assertTrue(blockedStrategy.errors().isEmpty());

        AgentTeamResponse unavailable = call(AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.UNAVAILABLE));
        AgentOutput unavailableStrategy = run(
                unavailable, AgentCode.STRATEGY_BACKTEST);
        assertEquals(RunStatus.INSUFFICIENT_DATA, unavailableStrategy.status());
        assertEquals(List.of("BACKTEST_KNOWLEDGE_TIME_UNVERIFIABLE"),
                unavailableStrategy.errors().stream().map(item -> item.code()).toList());

        AgentTeamResponse invalid = call(AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.INVALID_HASH));
        AgentOutput invalidStrategy = run(invalid, AgentCode.STRATEGY_BACKTEST);
        assertEquals(RunStatus.INSUFFICIENT_DATA, invalidStrategy.status());
        assertEquals(List.of("STRATEGY_BACKTEST_INPUT_INVALID"),
                invalidStrategy.errors().stream().map(item -> item.code()).toList());

        AgentTeamResponse predated = call(AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.PREDATED_BAR));
        AgentOutput predatedStrategy = run(
                predated, AgentCode.STRATEGY_BACKTEST);
        assertEquals(RunStatus.INSUFFICIENT_DATA, predatedStrategy.status());
        assertEquals(List.of("STRATEGY_BACKTEST_INPUT_INVALID"),
                predatedStrategy.errors().stream().map(item -> item.code()).toList());
        assertTrue(predatedStrategy.findings().isEmpty());
        assertTrue(predatedStrategy.evidence().isEmpty());
    }

    @Test
    void sameContextHashProducesSameBusinessResult() throws Exception {
        AgentTeamRequest request = AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.PASS);
        assertEquals(
                stableBusinessJson(call(request)),
                stableBusinessJson(call(request)));
    }

    @Test
    void javaRejectsTamperedScoreHashEvidenceAndPrematureFinalUpgrade()
            throws Exception {
        AgentTeamRequest request = AgentStage2FTestFixtures.request(
                AgentStage2FTestFixtures.Scenario.PASS);
        AgentTeamResponse source = call(request);

        ObjectNode score = mapper.valueToTree(source);
        strategyRun(score).put("score", 99);
        assertRejected(request, score);

        ObjectNode contextHash = mapper.valueToTree(source);
        ((ObjectNode) strategyRun(contextHash)
                .path("evidence").get(0)
                .path("fields")
                .path("backtestContext"))
                .put("backtestResultHash", "0".repeat(64));
        assertRejected(request, contextHash);

        ObjectNode evidence = mapper.valueToTree(source);
        ((ObjectNode) strategyRun(evidence).path("evidence").get(0))
                .put("contentHash", "0".repeat(64));
        assertRejected(request, evidence);

        ObjectNode advice = mapper.valueToTree(source);
        strategyRun(advice).put("summary", "建议买入");
        assertRejected(request, advice);

        ObjectNode upgraded = mapper.valueToTree(source);
        ((ObjectNode) upgraded.path("finalDecision")).put("decision", "WATCH");
        assertRejected(request, upgraded);
    }

    @Test
    void responseContainsNoInvestmentInstructionOrReturnPromise()
            throws Exception {
        String body = mapper.writeValueAsString(call(
                AgentStage2FTestFixtures.request(
                        AgentStage2FTestFixtures.Scenario.PASS)));
        for (String forbidden : Set.of(
                "买入", "卖出", "加仓", "减仓", "目标价", "收益承诺")) {
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
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        InputStream responseStream = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String body = responseStream == null
                ? "" : new String(
                responseStream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        assertEquals(200, status, body);
        AgentTeamResponse response = mapper.readValue(
                body, AgentTeamResponse.class);
        assertDoesNotThrow(() -> validator.validate(request, response));
        return response;
    }

    private void assertRejected(
            AgentTeamRequest request,
            JsonNode response
    ) throws Exception {
        AgentTeamResponse changed = mapper.treeToValue(
                response, AgentTeamResponse.class);
        assertThrows(
                AgentTeamException.class,
                () -> validator.validate(request, changed));
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

    private static AgentOutput run(
            AgentTeamResponse response,
            AgentCode code
    ) {
        return response.agentRuns().stream()
                .filter(item -> item.agentCode() == code)
                .findFirst()
                .orElseThrow();
    }

    private static ObjectNode strategyRun(ObjectNode response) {
        for (JsonNode item : response.withArray("agentRuns")) {
            if ("STRATEGY_BACKTEST".equals(item.path("agentCode").asText())) {
                return (ObjectNode) item;
            }
        }
        throw new AssertionError("缺少STRATEGY_BACKTEST运行");
    }
}
