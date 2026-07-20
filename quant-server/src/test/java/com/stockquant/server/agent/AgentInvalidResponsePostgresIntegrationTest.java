package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.service.AgentTaskService;
import com.stockquant.server.agent.service.AgentTaskWorker;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentInvalidResponsePostgresIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    private static final String LEAK_MARKER = "STAGE_1D3C_RESPONSE_BODY_MUST_NOT_BE_PERSISTED";
    private static final ObjectMapper STUB_MAPPER = new ObjectMapper();
    private static final List<Long> TASKS_TO_DELETE = new CopyOnWriteArrayList<>();
    private static final Map<Long, AtomicInteger> CALLS_BY_TASK = new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> REQUESTS_BY_TASK = new ConcurrentHashMap<>();
    private static final Map<Long, byte[]> RESPONSES_BY_TASK = new ConcurrentHashMap<>();
    private static final Map<String, Scenario> SCENARIOS_BY_SYMBOL = scenariosBySymbol();
    private static final Map<String, JsonNode> FIXTURES = loadFixtures();
    private static final HttpServer STUB_SERVER = startLocalStub();

    @Autowired
    private AgentTaskService taskService;

    @Autowired
    private AgentTaskWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
        registry.add("stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + STUB_SERVER.getAddress().getPort());
    }

    @AfterEach
    void cleanCreatedTasks() {
        for (Long taskId : new ArrayList<>(TASKS_TO_DELETE)) {
            jdbc.update("DELETE FROM agent_tasks WHERE id = ?", taskId);
            assertTaskScopedCounts(taskId, 0, 0, 0, 0, 0);
            TASKS_TO_DELETE.remove(taskId);
            CALLS_BY_TASK.remove(taskId);
            REQUESTS_BY_TASK.remove(taskId);
            RESPONSES_BY_TASK.remove(taskId);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB_SERVER.stop(0);
    }

    static Stream<Scenario> invalidScenarios() {
        return Stream.of(Scenario.values());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScenarios")
    void rejectsInvalidResponseWithoutPartialPersistence(Scenario scenario) {
        assertDedicatedDatabaseAndFlywayV5();

        CreatedTask created = taskService.create(new CreateAgentTaskRequest(
                scenario.symbol,
                TRADE_DATE,
                ExecutionMode.LOCAL_RULES,
                scenario.ruleVersion,
                true,
                TriggerType.MANUAL
        ), "stage-1d-3c-integration-test");
        long taskId = created.task().id();
        TASKS_TO_DELETE.add(taskId);
        assertTrue(created.newlyCreated());

        await(() -> "FAILED".equals(taskStatus(taskId)), "非法响应任务未进入FAILED终态");

        JsonNode actualRequest = REQUESTS_BY_TASK.get(taskId);
        byte[] actualResponse = RESPONSES_BY_TASK.get(taskId);
        assertNotNull(actualRequest);
        assertNotNull(actualResponse);
        assertEquals(taskId, actualRequest.path("taskId").asLong());
        assertEquals(1, calls(taskId));
        assertEquals(1, countForTask("agent_tasks", taskId));
        assertEquals(6, countForTask("agent_runs", taskId));
        assertEquals(0, countForTask("agent_evidence", taskId));
        assertEquals(0, countForTask("agent_vetoes", taskId));
        assertEquals(0, countForTask("agent_decisions", taskId));

        if (scenario == Scenario.FINAL_DECISION_VETO_MISMATCH) {
            JsonNode lateFailureResponse = readJson(actualResponse);
            assertEquals(1, lateFailureResponse.path("evidence").size());
            assertEquals(1, lateFailureResponse.path("vetoes").size());
            assertFalse(lateFailureResponse.path("finalDecision").path("vetoed").asBoolean());
        } else if (scenario == Scenario.MALFORMED_JSON) {
            assertTrue(new String(actualResponse, StandardCharsets.UTF_8).contains(LEAK_MARKER));
        } else {
            assertTrue(readJson(actualResponse).toString().contains(LEAK_MARKER));
        }

        String expectedError = scenario == Scenario.MALFORMED_JSON
                ? "智能体分析服务暂时不可用"
                : "智能体响应校验失败";
        assertEquals(expectedError, jdbc.queryForObject(
                "SELECT error_message FROM agent_tasks WHERE id = ?", String.class, taskId));

        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT agent_code, status, score, confidence, veto, summary,
                       output_json::text AS output_json, error_message
                FROM agent_runs WHERE task_id = ? ORDER BY id
                """, taskId);
        assertFailedRuns(runs, expectedError);
        assertSafeErrors(taskId, runs, expectedError);

        worker.execute(taskId);
        assertEquals(1, calls(taskId), "FAILED任务重复执行不得再次调用HTTP");
        assertEquals(6, countForTask("agent_runs", taskId));
        assertEquals(0, countForTask("agent_evidence", taskId));
        assertEquals(0, countForTask("agent_vetoes", taskId));
        assertEquals(0, countForTask("agent_decisions", taskId));
        assertEquals("FAILED", taskStatus(taskId));
    }

    private void assertFailedRuns(List<Map<String, Object>> runs, String expectedError) {
        Set<String> expectedAgents = Set.of(
                "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
                "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"
        );
        assertEquals(6, runs.size());
        assertEquals(expectedAgents, values(runs, "agent_code"));
        assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));
        assertEquals(Set.of("FAILED"), values(runs, "status"));
        for (Map<String, Object> run : runs) {
            assertNull(run.get("score"));
            assertNull(run.get("confidence"));
            assertNull(run.get("summary"));
            assertNull(run.get("output_json"));
            assertEquals(false, run.get("veto"));
            assertEquals(expectedError, run.get("error_message"));
        }
    }

    private void assertSafeErrors(long taskId, List<Map<String, Object>> runs, String expectedError) {
        List<String> messages = new ArrayList<>();
        messages.add(jdbc.queryForObject(
                "SELECT error_message FROM agent_tasks WHERE id = ?", String.class, taskId));
        runs.forEach(run -> messages.add(String.valueOf(run.get("error_message"))));
        for (String message : messages) {
            assertNotNull(message);
            assertFalse(message.isBlank());
            assertEquals(expectedError, message);
            assertTrue(message.length() <= 900);
            assertFalse(message.contains(LEAK_MARKER));
            assertFalse(message.contains("contextSnapshot"));
            assertFalse(message.contains("schemaVersion"));
            assertFalse(message.contains("password"));
            assertFalse(message.contains("token"));
            assertFalse(message.contains("\nat "));
            assertFalse(message.contains("{"));
        }
    }

    private void assertDedicatedDatabaseAndFlywayV5() {
        Map<String, Object> identity = jdbc.queryForMap(
                "SELECT current_database() AS database_name, current_user AS user_name");
        assertEquals("stock_quant_test", identity.get("database_name"));
        assertEquals("stock_quant_test", identity.get("user_name"));
        assertEquals("5", jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                """, String.class));
    }

    private String taskStatus(long taskId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM agent_tasks WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getString(1), taskId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private int countForTask(String table, long taskId) {
        requireAllowedTable(table);
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, taskId);
        return count == null ? 0 : count;
    }

    private void assertTaskScopedCounts(
            long taskId,
            int tasks,
            int runs,
            int evidence,
            int vetoes,
            int decisions
    ) {
        assertEquals(tasks, countForTask("agent_tasks", taskId));
        assertEquals(runs, countForTask("agent_runs", taskId));
        assertEquals(evidence, countForTask("agent_evidence", taskId));
        assertEquals(vetoes, countForTask("agent_vetoes", taskId));
        assertEquals(decisions, countForTask("agent_decisions", taskId));
    }

    private static Set<String> allowedTables() {
        return Set.of("agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions");
    }

    private static void requireAllowedTable(String table) {
        if (!allowedTables().contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
    }

    private static Set<String> values(List<Map<String, Object>> rows, String column) {
        Set<String> values = new HashSet<>();
        rows.forEach(row -> values.add(String.valueOf(row.get(column))));
        return values;
    }

    private static void await(BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError(failureMessage);
    }

    private static int calls(long taskId) {
        AtomicInteger calls = CALLS_BY_TASK.get(taskId);
        return calls == null ? 0 : calls.get();
    }

    private static void handleAnalyze(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        JsonNode request = STUB_MAPPER.readTree(requestBytes);
        long taskId = request.path("taskId").asLong();
        int call = CALLS_BY_TASK.computeIfAbsent(taskId, ignored -> new AtomicInteger()).incrementAndGet();
        REQUESTS_BY_TASK.put(taskId, request);

        if (!"POST".equals(exchange.getRequestMethod())
                || !"/agents/team/analyze".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (call != 1) {
            exchange.sendResponseHeaders(409, -1);
            exchange.close();
            return;
        }

        Scenario scenario = SCENARIOS_BY_SYMBOL.get(request.path("symbol").asText());
        if (scenario == null) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }

        byte[] response = responseFor(request, scenario);
        RESPONSES_BY_TASK.put(taskId, response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static byte[] responseFor(JsonNode request, Scenario scenario) throws IOException {
        if (scenario == Scenario.MALFORMED_JSON) {
            return ("{\"schemaVersion\":\"1.0\",\"summary\":\"" + LEAK_MARKER + "\"")
                    .getBytes(StandardCharsets.UTF_8);
        }

        ObjectNode response = FIXTURES.get(scenario.fixtureName).deepCopy();
        applyJavaIdentity(response, request);
        ObjectNode finalDecision = (ObjectNode) response.path("finalDecision");
        finalDecision.put("summary", finalDecision.path("summary").asText() + " " + LEAK_MARKER);

        switch (scenario) {
            case DANGLING_FINDING_EVIDENCE -> ((ArrayNode) ((ObjectNode) response
                    .withArray("agentRuns").get(0)).withArray("findings").get(0)
                    .path("evidenceIds"))
                    .set(0, STUB_MAPPER.getNodeFactory().textNode("missing-evidence-id"));
            case NON_POSITION_FORMAL_VETO -> {
                ObjectNode veto = (ObjectNode) response.withArray("vetoes").get(0);
                veto.put("agentCode", "ANNOUNCEMENT_RISK");
                veto.put("runId", request.path("runIds").path("announcementRisk").asLong());
            }
            case MISSING_SOURCE_RUN -> finalDecision.withArray("sourceRunIds").remove(5);
            case FINAL_DECISION_VETO_MISMATCH -> finalDecision.put("vetoed", false);
            case SCORE_OUT_OF_RANGE -> ((ObjectNode) response.withArray("agentRuns").get(0))
                    .put("score", 101);
            case STAGE_2D_INVALID_MARKET_REGIME -> {
                // The shared Stage 2D fixture already contains the deliberately invalid
                // MARKET_REGIME confidence value. Keep it unchanged so this database test
                // exercises the same cross-language invalid response contract.
            }
            case MALFORMED_JSON -> throw new IllegalStateException("malformed场景已提前处理");
        }
        return STUB_MAPPER.writeValueAsBytes(response);
    }

    private static void applyJavaIdentity(ObjectNode response, JsonNode request) {
        long taskId = request.path("taskId").asLong();
        response.put("taskId", taskId);
        response.put("contextHash", request.path("contextHash").asText());
        response.put("tradeDate", request.path("tradeDate").asText());
        response.put("ruleVersion", request.path("ruleVersion").asText());
        response.put("executionMode", request.path("executionMode").asText());

        JsonNode requestedRunIds = request.path("runIds");
        ArrayNode runs = response.withArray("agentRuns");
        Map<String, String> fields = runIdFields();
        Map<String, Long> runIds = new HashMap<>();
        for (JsonNode value : runs) {
            ObjectNode run = (ObjectNode) value;
            String agentCode = run.path("agentCode").asText();
            long runId = requestedRunIds.path(fields.get(agentCode)).asLong();
            runIds.put(agentCode, runId);
            run.put("taskId", taskId);
            run.put("runId", runId);
            run.put("contextHash", request.path("contextHash").asText());
            run.put("ruleVersion", request.path("ruleVersion").asText());
            run.put("executionMode", request.path("executionMode").asText());
        }

        for (JsonNode value : response.withArray("vetoes")) {
            ObjectNode veto = (ObjectNode) value;
            veto.put("taskId", taskId);
            veto.put("runId", runIds.get("POSITION_RISK"));
        }

        ObjectNode decision = (ObjectNode) response.path("finalDecision");
        decision.put("taskId", taskId);
        decision.put("contextHash", request.path("contextHash").asText());
        decision.put("tradeDate", request.path("tradeDate").asText());
        decision.put("ruleVersion", request.path("ruleVersion").asText());
        decision.put("executionMode", request.path("executionMode").asText());
        ArrayNode sourceRunIds = STUB_MAPPER.createArrayNode();
        for (JsonNode run : runs) {
            sourceRunIds.add(run.path("runId").asLong());
        }
        decision.set("sourceRunIds", sourceRunIds);
    }

    private static Map<String, JsonNode> loadFixtures() {
        Map<String, JsonNode> fixtures = new HashMap<>();
        for (String name : Set.of(
                "valid-agent-team-evidence-response.json",
                "valid-agent-team-veto-response.json",
                "stage-2d-invalid-response.json")) {
            try (InputStream input = AgentInvalidResponsePostgresIntegrationTest.class
                    .getResourceAsStream("/agent-team-contract/" + name)) {
                if (input == null) {
                    throw new IllegalStateException("共享响应夹具不存在：" + name);
                }
                fixtures.put(name, STUB_MAPPER.readTree(input));
            } catch (IOException error) {
                throw new ExceptionInInitializerError(error);
            }
        }
        return Map.copyOf(fixtures);
    }

    private static HttpServer startLocalStub() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/agents/team/analyze",
                    AgentInvalidResponsePostgresIntegrationTest::handleAnalyze);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static Map<String, Scenario> scenariosBySymbol() {
        Map<String, Scenario> scenarios = new HashMap<>();
        for (Scenario scenario : Scenario.values()) {
            scenarios.put(scenario.symbol, scenario);
        }
        return Map.copyOf(scenarios);
    }

    private static Map<String, String> runIdFields() {
        return Map.of(
                "DATA_QUALITY", "dataQuality",
                "MARKET_REGIME", "marketRegime",
                "TECHNICAL_ANALYSIS", "technicalAnalysis",
                "STRATEGY_BACKTEST", "strategyBacktest",
                "ANNOUNCEMENT_RISK", "announcementRisk",
                "POSITION_RISK", "positionRisk"
        );
    }

    private static JsonNode readJson(byte[] value) {
        try {
            return STUB_MAPPER.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("测试响应JSON无法解析", error);
        }
    }

    enum Scenario {
        DANGLING_FINDING_EVIDENCE(
                "悬空finding证据引用", "600741", "invalid-dangling-evidence",
                "valid-agent-team-evidence-response.json"),
        NON_POSITION_FORMAL_VETO(
                "非POSITION_RISK正式否决", "600742", "invalid-non-position-veto",
                "valid-agent-team-veto-response.json"),
        MISSING_SOURCE_RUN(
                "finalDecision缺少sourceRunId", "600743", "invalid-missing-source-run",
                "valid-agent-team-evidence-response.json"),
        FINAL_DECISION_VETO_MISMATCH(
                "finalDecision vetoed与正式veto不一致", "600744", "invalid-vetoed-mismatch",
                "valid-agent-team-veto-response.json"),
        SCORE_OUT_OF_RANGE(
                "score越界", "600745", "invalid-score-range",
                "valid-agent-team-evidence-response.json"),
        STAGE_2D_INVALID_MARKET_REGIME(
                "阶段2D-1非法MARKET_REGIME响应原子失败", "600747",
                "1.4.0-stage-2d-market-regime-v1", "stage-2d-invalid-response.json"),
        MALFORMED_JSON(
                "无法反序列化JSON", "600746", "invalid-malformed-json", null);

        private final String displayName;
        private final String symbol;
        private final String ruleVersion;
        private final String fixtureName;

        Scenario(String displayName, String symbol, String ruleVersion, String fixtureName) {
            this.displayName = displayName;
            this.symbol = symbol;
            this.ruleVersion = ruleVersion;
            this.fixtureName = fixtureName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }
}
