package com.stockquant.server.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import com.stockquant.server.agent.service.AgentContextHashService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCrossLanguageContractTest {

    private ObjectMapper mapper;
    private AgentTeamRequest request;
    private AgentTeamResponse response;

    @BeforeEach
    void setUp() throws IOException {
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        request = read("valid-agent-team-request.json", AgentTeamRequest.class);
        response = read("valid-agent-team-response.json", AgentTeamResponse.class);
    }

    @Test
    void javaOutboundRequestUsesPythonCompatibleCamelCaseIsoDateTimesAndRoundTrips() throws Exception {
        JsonNode serialized = mapper.readTree(mapper.writeValueAsBytes(request));

        assertTrue(serialized.has("taskId"));
        assertFalse(serialized.has("task_id"));
        assertTrue(serialized.path("runIds").isObject());
        assertTrue(serialized.path("runIds").has("dataQuality"));
        assertTrue(serialized.path("runIds").has("marketRegime"));
        assertTrue(serialized.path("runIds").has("technicalAnalysis"));
        assertTrue(serialized.path("runIds").has("strategyBacktest"));
        assertTrue(serialized.path("runIds").has("announcementRisk"));
        assertTrue(serialized.path("runIds").has("positionRisk"));
        assertFalse(serialized.has("run_ids"));
        assertFalse(serialized.path("runIds").has("data_quality"));

        assertTrue(serialized.path("tradeDate").isTextual());
        assertEquals("2026-07-14", serialized.path("tradeDate").textValue());
        assertTrue(serialized.path("requestedAt").isTextual());
        String requestedAt = serialized.path("requestedAt").textValue();
        assertTrue(requestedAt.endsWith("Z"));
        assertEquals(Instant.parse("2026-07-14T05:01:00Z"), Instant.parse(requestedAt));
        assertTrue(serialized.path("contextSnapshot").isObject());

        AgentTeamRequest roundTrip = mapper.treeToValue(serialized, AgentTeamRequest.class);
        assertEquals(request.taskId(), roundTrip.taskId());
        assertEquals(request.runIds(), roundTrip.runIds());
        assertEquals(request.tradeDate(), roundTrip.tradeDate());
        assertEquals(request.requestedAt(), roundTrip.requestedAt());
        assertEquals(request.contextHash(), roundTrip.contextHash());
    }

    @Test
    void sharedRequestDeserializesWithExactJavaIdentifiersAndCamelCase() throws Exception {
        assertEquals("1.0", request.schemaVersion());
        assertEquals(77, request.taskId());
        assertEquals("600000", request.symbol());
        assertEquals(LocalDate.of(2026, 7, 14), request.tradeDate());
        assertEquals("a".repeat(64), request.contextHash());
        assertEquals("local-rules-1", request.ruleVersion());
        assertEquals(ExecutionMode.LOCAL_RULES, request.executionMode());
        List<Long> ids = List.copyOf(request.runIds().byAgentCode().values());
        assertEquals(6, ids.size());
        assertEquals(6, new HashSet<>(ids).size());
        assertEquals(101, request.runIds().dataQuality());
        assertEquals(102, request.runIds().marketRegime());
        assertEquals(103, request.runIds().technicalAnalysis());
        assertEquals(104, request.runIds().strategyBacktest());
        assertEquals(105, request.runIds().announcementRisk());
        assertEquals(106, request.runIds().positionRisk());

        JsonNode serialized = mapper.readTree(mapper.writeValueAsBytes(request));
        assertTrue(serialized.has("taskId"));
        assertTrue(serialized.path("runIds").has("dataQuality"));
        assertFalse(serialized.has("task_id"));
    }

    @Test
    void sharedResponseDeserializesAndPassesJavaSemanticValidator() {
        assertEquals(6, response.agentRuns().size());
        assertEquals(AgentCode.PROFESSIONAL_AGENTS,
                response.agentRuns().stream().map(run -> run.agentCode()).toList());
        assertEquals(6, response.agentRuns().stream().map(run -> run.agentCode()).distinct().count());
        assertEquals(request.runIds().byAgentCode().values().stream().collect(java.util.stream.Collectors.toSet()),
                new HashSet<>(response.finalDecision().sourceRunIds()));
        new AgentResponseValidator().validate(request, response);
    }

    @Test
    void stage2BSharedFixturesPassJavaValidatorAndCoverFrozenMappings() throws Exception {
        AgentTeamRequest stage2BRequest = read("stage-2b-valid-request.json", AgentTeamRequest.class);
        List<Stage2BExpectation> scenarios = List.of(
                new Stage2BExpectation(
                        "stage-2b-invalid-context-response.json",
                        RunStatus.INSUFFICIENT_DATA, GateStatus.BLOCKED, RunDecision.REJECT,
                        0, 0, FinalDecisionCode.BLOCKED_BY_DATA_QUALITY),
                new Stage2BExpectation(
                        "stage-2b-blocked-response.json",
                        RunStatus.COMPLETED, GateStatus.BLOCKED, RunDecision.REJECT,
                        0, 100, FinalDecisionCode.BLOCKED_BY_DATA_QUALITY),
                new Stage2BExpectation(
                        "stage-2b-warn-response.json",
                        RunStatus.COMPLETED, GateStatus.WARN, RunDecision.WARN,
                        50, 100, FinalDecisionCode.INSUFFICIENT_DATA),
                new Stage2BExpectation(
                        "stage-2b-pass-response.json",
                        RunStatus.COMPLETED, GateStatus.PASS, RunDecision.PASS,
                        100, 100, FinalDecisionCode.INSUFFICIENT_DATA)
        );

        AgentResponseValidator validator = new AgentResponseValidator();
        for (Stage2BExpectation scenario : scenarios) {
            AgentTeamResponse stage2BResponse = read(scenario.name(), AgentTeamResponse.class);
            validator.validate(stage2BRequest, stage2BResponse);
            var dataQuality = stage2BResponse.agentRuns().get(0);
            assertEquals(scenario.status(), dataQuality.status(), scenario.name());
            assertEquals(scenario.gate(), dataQuality.gateStatus(), scenario.name());
            assertEquals(scenario.decision(), dataQuality.decision(), scenario.name());
            assertEquals(scenario.score(), dataQuality.score(), scenario.name());
            assertEquals(scenario.confidence(), dataQuality.confidence(), scenario.name());
            assertFalse(dataQuality.veto(), scenario.name());
            assertEquals(scenario.finalDecision(), stage2BResponse.finalDecision().decision(), scenario.name());
            if (dataQuality.status() == RunStatus.COMPLETED) {
                assertEquals(stage2BResponse.evidence(), dataQuality.evidence(), scenario.name());
            }
        }
    }

    @Test
    void stage2CSharedRequestUsesProductionHashAndFrozenResearchContracts() throws Exception {
        AgentTeamRequest stage2C = read("stage-2c-valid-request.json", AgentTeamRequest.class);
        String recalculated = new AgentContextHashService(mapper).hash(stage2C.contextSnapshot());
        assertEquals(stage2C.contextHash(), recalculated, "STAGE_2C_PRODUCTION_HASH=" + recalculated);
        assertEquals("1.0", stage2C.schemaVersion());
        assertEquals("1.0", stage2C.contextSchemaVersion());
        assertEquals("1.4.0-stage-2b-dq-v1", stage2C.ruleVersion());
        assertEquals(6, new HashSet<>(stage2C.runIds().byAgentCode().values()).size());
        JsonNode snapshot = stage2C.contextSnapshot();
        assertEquals(9, snapshot.size());
        JsonNode breadth = snapshot.path("marketBreadth");
        assertTrue(breadth.path("available").asBoolean());
        assertFalse(breadth.path("pointInTimeGuaranteed").asBoolean());
        assertTrue(breadth.path("barFutureDataExcluded").asBoolean());
        assertFalse(breadth.path("universePointInTimeGuaranteed").asBoolean());
        assertFalse(breadth.path("futureDataExcluded").asBoolean());
        assertEquals(0, new BigDecimal("0.60000000").compareTo(breadth.path("coverageRatio").decimalValue()));
        try (InputStream stream = resource("stage-2c-valid-request.json")) {
            assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"coverageRatio\": 0.60000000"));
        }
        JsonNode scan = snapshot.path("scanResult");
        assertTrue(scan.path("symbolParticipationKnown").asBoolean());
        assertTrue(scan.path("symbolResultAvailable").asBoolean());
        assertTrue(scan.has("sourceScanScore"));
        for (String forbidden : List.of("score", "bullish", "bearish", "summary",
                "buyLow", "buyHigh", "stopLoss", "target1", "target2", "suggestedWeight")) {
            assertFalse(scan.has(forbidden));
        }
        assertEquals("BACKTEST_INPUT_CUTOFF_UNVERIFIABLE",
                snapshot.path("backtestContext").path("reasonCode").asText());
        AgentTeamRequest stage2B = read("stage-2b-valid-request.json", AgentTeamRequest.class);
        for (String section : List.of("security", "marketData", "technicalMetrics", "dataQualityContext")) {
            assertEquals(stage2B.contextSnapshot().path(section), snapshot.path(section));
        }
    }

    @Test
    void zAndOffsetDateTimesDeserializeToTheSameInstant() {
        Instant expected = Instant.parse("2026-07-14T05:02:00Z");
        assertEquals(Instant.parse("2026-07-14T05:01:00Z"), request.requestedAt());
        assertEquals(expected, response.generatedAt());
        response.agentRuns().forEach(run -> assertEquals(expected, run.generatedAt()));
        assertEquals(expected, response.finalDecision().generatedAt());
    }

    @Test
    void invalidEnumAndTimezoneLessTimestampAreRejected() throws Exception {
        JsonNode invalidEnum = responseNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalidEnum).put("executionMode", "PAID_MODEL");
        assertThrows(JsonProcessingException.class,
                () -> mapper.treeToValue(invalidEnum, AgentTeamResponse.class));

        JsonNode noZone = responseNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) noZone).put("generatedAt", "2026-07-14T05:02:00");
        assertThrows(JsonProcessingException.class,
                () -> mapper.treeToValue(noZone, AgentTeamResponse.class));
    }

    @Test
    void mismatchedRunIdMappingIsRejectedByJavaValidator() throws Exception {
        JsonNode invalid = responseNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("agentRuns").get(0)).put("runId", 999);
        AgentTeamResponse mismatched = mapper.treeToValue(invalid, AgentTeamResponse.class);
        assertThrows(AgentTeamException.class,
                () -> new AgentResponseValidator().validate(request, mismatched));
    }

    private JsonNode responseNode() throws IOException {
        try (InputStream stream = resource("valid-agent-team-response.json")) {
            return mapper.readTree(stream);
        }
    }

    private <T> T read(String name, Class<T> type) throws IOException {
        try (InputStream stream = resource(name)) {
            return mapper.readValue(stream, type);
        }
    }

    private InputStream resource(String name) {
        InputStream stream = getClass().getResourceAsStream("/agent-team-contract/" + name);
        if (stream == null) {
            throw new IllegalStateException("共享契约夹具不存在：" + name);
        }
        return stream;
    }

    private record Stage2BExpectation(
            String name,
            RunStatus status,
            GateStatus gate,
            RunDecision decision,
            int score,
            int confidence,
            FinalDecisionCode finalDecision
    ) {}
}
