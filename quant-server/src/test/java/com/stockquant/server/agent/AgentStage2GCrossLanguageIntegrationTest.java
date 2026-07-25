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
class AgentStage2GCrossLanguageIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AgentResponseValidator validator = new AgentResponseValidator();

    @Test
    void zeroEventAndMultiRiskResponsesCloseAcrossLanguages() throws Exception {
        AgentTeamResponse noEvent = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.NO_EVENT));
        AgentOutput noEventRun = run(noEvent, AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(RunStatus.COMPLETED, noEventRun.status());
        assertEquals(GateStatus.PASS, noEventRun.gateStatus());
        assertEquals(100, noEventRun.score());
        assertEquals(40, noEventRun.confidence());
        assertEquals(5, noEventRun.findings().size());
        assertEquals(1, noEventRun.evidence().size());
        assertFalse(noEventRun.veto());
        assertEquals(FinalDecisionCode.INSUFFICIENT_DATA,
                noEvent.finalDecision().decision());
        assertEquals(
                "六个专业运行已完成，但2I综合决策规则尚未实现。",
                noEvent.finalDecision().summary());
        assertEquals(6, noEvent.agentRuns().size());

        AgentTeamResponse risks = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.MULTI_RISK));
        AgentOutput riskRun = run(risks, AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(RunStatus.COMPLETED, riskRun.status());
        assertEquals(GateStatus.WARN, riskRun.gateStatus());
        assertEquals(52, riskRun.score());
        assertEquals(3, riskRun.evidence().size());
        assertTrue(riskRun.findings().stream()
                .flatMap(value -> value.evidenceIds().stream())
                .anyMatch(value -> value.startsWith("announcement-risk-event-")));
        assertTrue(risks.vetoes().isEmpty());
    }

    @Test
    void unavailableInvalidAndDataQualityBlockedFailSafe() throws Exception {
        AgentTeamResponse unavailable = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.UNAVAILABLE));
        AgentOutput unavailableRun = run(unavailable, AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(RunStatus.INSUFFICIENT_DATA, unavailableRun.status());
        assertEquals(List.of("ANNOUNCEMENT_NO_COMPLETE_CAPTURE"),
                unavailableRun.errors().stream().map(item -> item.code()).toList());
        assertTrue(unavailableRun.evidence().isEmpty());

        AgentTeamResponse invalid = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.INVALID_HASH));
        AgentOutput invalidRun = run(invalid, AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(List.of("ANNOUNCEMENT_RISK_INPUT_INVALID"),
                invalidRun.errors().stream().map(item -> item.code()).toList());
        assertTrue(invalidRun.findings().isEmpty());

        AgentTeamResponse invalidSource = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.INVALID_SOURCE_URL));
        AgentOutput invalidSourceRun = run(
                invalidSource,
                AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(List.of("ANNOUNCEMENT_RISK_INPUT_INVALID"),
                invalidSourceRun.errors().stream()
                        .map(item -> item.code()).toList());
        assertTrue(invalidSourceRun.findings().isEmpty());

        AgentTeamResponse blocked = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.DATA_QUALITY_BLOCKED_WITH_VETO));
        assertEquals(GateStatus.BLOCKED,
                run(blocked, AgentCode.DATA_QUALITY).gateStatus());
        AgentOutput blockedAnnouncement = run(blocked, AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(RunStatus.INSUFFICIENT_DATA, blockedAnnouncement.status());
        assertTrue(blockedAnnouncement.errors().isEmpty());
        assertEquals(FinalDecisionCode.REJECTED_BY_VETO,
                blocked.finalDecision().decision());
        assertTrue(blocked.finalDecision().vetoed());
    }

    @Test
    void positionVetoStillHasPriorityAndAnnouncementNeverVetoes() throws Exception {
        AgentTeamResponse response = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.POSITION_VETO));
        assertEquals(FinalDecisionCode.REJECTED_BY_VETO,
                response.finalDecision().decision());
        assertTrue(response.finalDecision().vetoed());
        assertFalse(run(response, AgentCode.ANNOUNCEMENT_RISK).veto());
        assertTrue(response.vetoes().stream()
                .allMatch(item -> item.agentCode() == AgentCode.POSITION_RISK));
    }

    @Test
    void mixedSafeAndRiskPhrasesRemainConsistentAcrossLanguages()
            throws Exception {
        AgentTeamResponse response = call(AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.MIXED_EXCLUSION_RISKS));
        AgentOutput run = run(response, AgentCode.ANNOUNCEMENT_RISK);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(GateStatus.WARN, run.gateStatus());
        assertEquals(40, run.score());
        assertEquals(5, run.findings().size());
        assertEquals(4, run.evidence().size());
        assertEquals(
                List.of(
                        "CNINFO:1212345681",
                        "CNINFO:1212345682",
                        "CNINFO:1212345683"),
                run.evidence().subList(1, 4).stream()
                        .map(item -> item.fields().get("event")
                                .get("sourceAnnouncementId").asText())
                        .toList());
        assertFalse(run.veto());
    }

    @Test
    void javaRejectsTamperedScoreEventEvidenceVetoAndChiefDecision()
            throws Exception {
        AgentTeamRequest request = AgentStage2GTestFixtures.request(
                AgentStage2GTestFixtures.Scenario.MULTI_RISK);
        AgentTeamResponse source = call(request);

        ObjectNode score = mapper.valueToTree(source);
        announcementRun(score).put("score", 99);
        assertRejected(request, score);

        ObjectNode evidence = mapper.valueToTree(source);
        ((ObjectNode) announcementRun(evidence).withArray("evidence").get(1))
                .put("contentHash", "0".repeat(64));
        assertRejected(request, evidence);

        ObjectNode veto = mapper.valueToTree(source);
        announcementRun(veto).put("veto", true);
        assertRejected(request, veto);

        ObjectNode decision = mapper.valueToTree(source);
        ((ObjectNode) decision.path("finalDecision"))
                .put("decision", "PASS_TO_MANUAL_REVIEW");
        assertRejected(request, decision);

        ObjectNode instruction = mapper.valueToTree(source);
        announcementRun(instruction).put("summary", "立即卖出");
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

    private static ObjectNode announcementRun(ObjectNode response) {
        for (JsonNode item : response.withArray("agentRuns")) {
            if ("ANNOUNCEMENT_RISK".equals(item.path("agentCode").asText())) {
                return (ObjectNode) item;
            }
        }
        throw new AssertionError("缺少ANNOUNCEMENT_RISK运行");
    }
}
