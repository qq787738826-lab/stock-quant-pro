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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
class AgentEvidenceVetoPostgresIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    private static final String EVIDENCE_SYMBOL = "600731";
    private static final String VETO_SYMBOL = "600732";
    private static final ObjectMapper STUB_MAPPER = new ObjectMapper();
    private static final List<Long> TASKS_TO_DELETE = new CopyOnWriteArrayList<>();
    private static final Map<Long, AtomicInteger> CALLS_BY_TASK = new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> REQUESTS_BY_TASK = new ConcurrentHashMap<>();
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
        }
    }

    @AfterAll
    static void stopStub() {
        STUB_SERVER.stop(0);
    }

    @Test
    void persistsSharedEvidenceWithoutFormalVetoThroughTheProductionFlow() throws SQLException {
        assertDedicatedDatabaseAndFlywayV5();
        CreateAgentTaskRequest request = request(EVIDENCE_SYMBOL, "stage-1d-3b-evidence");
        CreatedTask created = createAndTrack(request);
        long taskId = created.task().id();

        await(() -> "COMPLETED".equals(taskStatus(taskId)), "证据场景未进入COMPLETED终态");

        JsonNode sent = REQUESTS_BY_TASK.get(taskId);
        assertNotNull(sent);
        assertEquals(1, calls(taskId));
        assertRequestUsesDatabaseIdentity(taskId, sent);
        assertTaskScopedCounts(taskId, 1, 6, 3, 0, 1);

        List<Map<String, Object>> runs = runs(taskId);
        assertProfessionalRuns(runs);
        assertEquals(Set.of("COMPLETED"), values(runs, "status"));

        List<Map<String, Object>> evidence = jdbc.queryForList("""
                SELECT evidence_key, run_id, category, source_type, source_name, source_ref,
                       symbol, trade_date, observed_at, collected_at, content_hash,
                       payload_json::text AS payload_json
                FROM agent_evidence WHERE task_id = ? ORDER BY evidence_key
                """, taskId);
        assertEquals(Set.of("e-market", "e-technical", "e-portfolio"), values(evidence, "evidence_key"));
        assertEquals(3, evidence.size());
        assertEvidence(evidence, "valid-agent-team-evidence-response.json", "e-market");
        assertEvidence(evidence, "valid-agent-team-evidence-response.json", "e-technical");
        assertEvidence(evidence, "valid-agent-team-evidence-response.json", "e-portfolio");
        assertTrue(evidence.stream().allMatch(row -> row.get("run_id") == null),
                "冻结夹具的单智能体evidence子集为空，当前归属算法应保存为任务级证据");
        Set<String> evidenceKeys = values(evidence, "evidence_key");
        assertRunOutputs(taskId, runs, evidenceKeys);
        assertFindingReferences(runs, "DATA_QUALITY", Set.of("e-market"));
        assertFindingReferences(runs, "MARKET_REGIME", Set.of("e-market"));
        assertFindingReferences(runs, "TECHNICAL_ANALYSIS", Set.of("e-technical"));
        assertFindingReferences(runs, "POSITION_RISK", Set.of("e-portfolio"));

        Map<String, Object> decision = decision(taskId);
        assertEquals("COMPLETED", decision.get("status"));
        assertEquals("WATCH", decision.get("decision"));
        assertEquals("WARN", decision.get("gate_status"));
        assertEquals(false, decision.get("vetoed"));
        assertEquals(List.of(), sqlLongs((Array) decision.get("veto_ids")));
        assertSourceRunIds(decision, runs);
        JsonNode decisionJson = readJson((String) decision.get("decision_json"));
        assertEquals("WATCH", decisionJson.path("decision").asText());
        assertTrue(decisionJson.path("vetoIds").isEmpty());
        assertEquals(Instant.parse("2026-07-14T05:02:00Z"),
                sqlInstant(decision.get("generated_at")));
        assertEquals("COMPLETED", taskStatus(taskId));

        assertCompletedCacheHitIsIdempotent(request, created, taskId, 3, 0);
    }

    @Test
    void persistsPositionRiskFormalVetoAndMapsItsLogicalIdToPhysicalIds() throws SQLException {
        assertDedicatedDatabaseAndFlywayV5();
        CreateAgentTaskRequest request = request(VETO_SYMBOL, "stage-1d-3b-veto");
        CreatedTask created = createAndTrack(request);
        long taskId = created.task().id();

        await(() -> "COMPLETED".equals(taskStatus(taskId)), "正式否决场景未进入COMPLETED终态");

        JsonNode sent = REQUESTS_BY_TASK.get(taskId);
        assertNotNull(sent);
        assertEquals(1, calls(taskId));
        assertRequestUsesDatabaseIdentity(taskId, sent);
        assertTaskScopedCounts(taskId, 1, 6, 1, 1, 1);

        List<Map<String, Object>> runs = runs(taskId);
        assertProfessionalRuns(runs);
        assertEquals(Set.of("COMPLETED"), values(runs, "status"));
        Map<String, Object> positionRisk = runs.stream()
                .filter(run -> "POSITION_RISK".equals(run.get("agent_code")))
                .findFirst().orElseThrow();
        long positionRiskRunId = ((Number) positionRisk.get("id")).longValue();
        assertEquals("REJECT", positionRisk.get("decision"));
        assertEquals(true, positionRisk.get("veto"));
        assertTrue(runs.stream().filter(run -> run != positionRisk)
                .allMatch(run -> Boolean.FALSE.equals(run.get("veto"))));

        List<Map<String, Object>> evidence = jdbc.queryForList("""
                SELECT evidence_key, run_id, category, source_type, source_name, source_ref,
                       symbol, trade_date, observed_at, collected_at, content_hash,
                       payload_json::text AS payload_json
                FROM agent_evidence WHERE task_id = ?
                """, taskId);
        assertEquals(1, evidence.size());
        Map<String, Object> storedEvidence = evidence.get(0);
        assertEquals("e-risk", storedEvidence.get("evidence_key"));
        assertNull(storedEvidence.get("run_id"));
        assertEvidence(evidence, "valid-agent-team-veto-response.json", "e-risk");
        Set<String> evidenceKeys = values(evidence, "evidence_key");
        assertRunOutputs(taskId, runs, evidenceKeys);
        assertFindingReferences(runs, "ANNOUNCEMENT_RISK", Set.of("e-risk"));
        assertFindingReferences(runs, "POSITION_RISK", Set.of("e-risk"));

        Map<String, Object> veto = jdbc.queryForMap("""
                SELECT id, task_id, run_id, agent_code, veto_code, reason,
                       evidence_ids, created_at
                FROM agent_vetoes WHERE task_id = ?
                """, taskId);
        JsonNode fixtureVeto = FIXTURES.get("valid-agent-team-veto-response.json")
                .withArray("vetoes").get(0);
        long physicalVetoId = ((Number) veto.get("id")).longValue();
        assertTrue(physicalVetoId > 0);
        assertEquals(taskId, ((Number) veto.get("task_id")).longValue());
        assertEquals(positionRiskRunId, ((Number) veto.get("run_id")).longValue());
        assertEquals(fixtureVeto.path("agentCode").asText(), veto.get("agent_code"));
        assertEquals(fixtureVeto.path("vetoCode").asText(), veto.get("veto_code"));
        assertEquals(fixtureVeto.path("reason").asText(), veto.get("reason"));
        assertEquals(jsonStrings(fixtureVeto.path("evidenceIds")),
                sqlStrings((Array) veto.get("evidence_ids")));
        assertEquals(OffsetDateTime.parse(fixtureVeto.path("createdAt").asText()).toInstant(),
                sqlInstant(veto.get("created_at")));

        Map<String, Object> decision = decision(taskId);
        assertEquals("COMPLETED", decision.get("status"));
        assertEquals("REJECTED_BY_VETO", decision.get("decision"));
        assertEquals("BLOCKED", decision.get("gate_status"));
        assertEquals(true, decision.get("vetoed"));
        assertEquals(List.of(physicalVetoId), sqlLongs((Array) decision.get("veto_ids")));
        assertSourceRunIds(decision, runs);
        JsonNode decisionJson = readJson((String) decision.get("decision_json"));
        assertEquals("veto-risk-1", decisionJson.path("vetoIds").get(0).asText());
        assertFalse(String.valueOf(physicalVetoId).equals("veto-risk-1"));
        assertEquals(Instant.parse("2026-07-14T05:02:00Z"),
                sqlInstant(decision.get("generated_at")));
        assertEquals("COMPLETED", taskStatus(taskId));

        assertCompletedCacheHitIsIdempotent(request, created, taskId, 1, 1);
    }

    private CreatedTask createAndTrack(CreateAgentTaskRequest request) {
        CreatedTask created = taskService.create(request, "stage-1d-3b-integration-test");
        TASKS_TO_DELETE.add(created.task().id());
        assertTrue(created.newlyCreated());
        return created;
    }

    private void assertCompletedCacheHitIsIdempotent(
            CreateAgentTaskRequest request,
            CreatedTask created,
            long taskId,
            int expectedEvidence,
            int expectedVetoes
    ) {
        CreatedTask cached = taskService.create(request, "stage-1d-3b-integration-test-repeat");
        long returnedTaskId = cached.task().id();
        if (!TASKS_TO_DELETE.contains(returnedTaskId)) {
            TASKS_TO_DELETE.add(returnedTaskId);
        }
        assertFalse(cached.newlyCreated());
        assertEquals(created.task().id(), returnedTaskId);
        worker.execute(taskId);
        assertEquals(1, calls(taskId));
        assertTaskScopedCounts(taskId, 1, 6, expectedEvidence, expectedVetoes, 1);
    }

    private static CreateAgentTaskRequest request(String symbol, String ruleVersion) {
        return new CreateAgentTaskRequest(
                symbol,
                TRADE_DATE,
                ExecutionMode.LOCAL_RULES,
                ruleVersion,
                false,
                TriggerType.MANUAL
        );
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

    private void assertRequestUsesDatabaseIdentity(long taskId, JsonNode request) {
        assertEquals(taskId, request.path("taskId").asLong());
        assertEquals(TRADE_DATE.toString(), request.path("tradeDate").asText());
        assertEquals("LOCAL_RULES", request.path("executionMode").asText());
        assertEquals(jdbc.queryForObject(
                "SELECT context_hash FROM agent_tasks WHERE id = ?", String.class, taskId),
                request.path("contextHash").asText());
        Map<String, String> fields = runIdFields();
        for (Map<String, Object> run : runs(taskId)) {
            assertEquals(((Number) run.get("id")).longValue(),
                    request.path("runIds").path(fields.get(run.get("agent_code"))).asLong());
        }
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

    private void assertEvidence(List<Map<String, Object>> evidence, String fixtureName, String key) {
        JsonNode expected = fixtureEvidence(fixtureName, key);
        Map<String, Object> row = evidence.stream()
                .filter(item -> key.equals(item.get("evidence_key")))
                .findFirst().orElseThrow();
        assertEquals(expected.path("evidenceId").asText(), row.get("evidence_key"));
        assertEquals(expected.path("category").asText(), row.get("category"));
        assertEquals(expected.path("sourceType").asText(), row.get("source_type"));
        assertEquals(expected.path("sourceName").asText(), row.get("source_name"));
        assertEquals(expected.path("sourceRef").asText(), row.get("source_ref"));
        assertEquals(expected.path("symbol").asText(), row.get("symbol"));
        assertEquals(LocalDate.parse(expected.path("tradeDate").asText()),
                ((java.sql.Date) row.get("trade_date")).toLocalDate());
        assertEquals(OffsetDateTime.parse(expected.path("observedAt").asText()).toInstant(),
                sqlInstant(row.get("observed_at")));
        assertEquals(OffsetDateTime.parse(expected.path("collectedAt").asText()).toInstant(),
                sqlInstant(row.get("collected_at")));
        assertEquals(expected.path("contentHash").asText(), row.get("content_hash"));
        JsonNode payload = readJson((String) row.get("payload_json"));
        assertTrue(payload.isObject());
        assertEquals(expected.path("fields"), payload);
    }

    private void assertRunOutputs(long taskId, List<Map<String, Object>> runs, Set<String> evidenceKeys) {
        for (Map<String, Object> run : runs) {
            JsonNode output = readJson((String) run.get("output_json"));
            assertTrue(output.isObject());
            assertEquals(taskId, output.path("taskId").asLong());
            assertEquals(((Number) run.get("id")).longValue(), output.path("runId").asLong());
            assertEquals(run.get("agent_code"), output.path("agentCode").asText());
            assertTrue(output.path("findings").isArray());
            assertTrue(output.path("evidence").isArray());
            for (JsonNode finding : output.path("findings")) {
                List<String> references = jsonStrings(finding.path("evidenceIds"));
                assertEquals(references.size(), new HashSet<>(references).size());
                assertTrue(evidenceKeys.containsAll(references));
            }
            assertTrue(output.path("evidence").isEmpty(),
                    "当前冻结夹具的单智能体evidence子集必须保持为空");
            for (JsonNode item : output.path("evidence")) {
                assertTrue(evidenceKeys.contains(item.path("evidenceId").asText()));
            }
        }
    }

    private void assertFindingReferences(
            List<Map<String, Object>> runs,
            String agentCode,
            Set<String> expectedReferences
    ) {
        Map<String, Object> run = runs.stream()
                .filter(item -> agentCode.equals(item.get("agent_code")))
                .findFirst().orElseThrow();
        JsonNode output = readJson((String) run.get("output_json"));
        Set<String> actual = new HashSet<>();
        for (JsonNode finding : output.path("findings")) {
            actual.addAll(jsonStrings(finding.path("evidenceIds")));
        }
        assertEquals(expectedReferences, actual);
    }

    private static void assertSourceRunIds(
            Map<String, Object> decision,
            List<Map<String, Object>> runs
    ) throws SQLException {
        List<Long> sourceRunIds = sqlLongs((Array) decision.get("source_run_ids"));
        assertEquals(6, sourceRunIds.size());
        Set<Long> uniqueSourceRunIds = new HashSet<>(sourceRunIds);
        assertEquals(6, uniqueSourceRunIds.size());
        assertEquals(runIds(runs), uniqueSourceRunIds);
    }

    private static JsonNode fixtureEvidence(String fixtureName, String evidenceId) {
        for (JsonNode evidence : FIXTURES.get(fixtureName).path("evidence")) {
            if (evidenceId.equals(evidence.path("evidenceId").asText())) {
                return evidence;
            }
        }
        throw new AssertionError("共享夹具缺少证据：" + evidenceId);
    }

    private static void assertProfessionalRuns(List<Map<String, Object>> runs) {
        Set<String> expected = Set.of(
                "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
                "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"
        );
        assertEquals(6, runs.size());
        assertEquals(expected, values(runs, "agent_code"));
        assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));
    }

    private List<Map<String, Object>> runs(long taskId) {
        return jdbc.queryForList("""
                SELECT id, agent_code, status, gate_status, decision, veto,
                       output_json::text AS output_json
                FROM agent_runs WHERE task_id = ? ORDER BY id
                """, taskId);
    }

    private Map<String, Object> decision(long taskId) {
        return jdbc.queryForMap("""
                SELECT status, decision, gate_status, vetoed, source_run_ids, veto_ids,
                       decision_json::text AS decision_json, generated_at
                FROM agent_decisions WHERE task_id = ?
                """, taskId);
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

    private static Set<Long> runIds(List<Map<String, Object>> runs) {
        Set<Long> values = new HashSet<>();
        runs.forEach(run -> values.add(((Number) run.get("id")).longValue()));
        return values;
    }

    private static List<Long> sqlLongs(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        List<Long> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add(((Number) value).longValue());
        }
        return List.copyOf(result);
    }

    private static List<String> sqlStrings(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static List<String> jsonStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static Instant sqlInstant(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new AssertionError("不支持的数据库时间类型：" + value.getClass().getName());
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
        CALLS_BY_TASK.computeIfAbsent(taskId, ignored -> new AtomicInteger()).incrementAndGet();
        REQUESTS_BY_TASK.put(taskId, request);

        if (!"POST".equals(exchange.getRequestMethod())
                || !"/agents/team/analyze".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String fixtureName = switch (request.path("symbol").asText()) {
            case EVIDENCE_SYMBOL -> "valid-agent-team-evidence-response.json";
            case VETO_SYMBOL -> "valid-agent-team-veto-response.json";
            default -> null;
        };
        if (fixtureName == null) {
            byte[] body = "unknown test symbol".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }

        byte[] response = responseFor(request, FIXTURES.get(fixtureName));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static byte[] responseFor(JsonNode request, JsonNode fixture) throws IOException {
        ObjectNode response = fixture.deepCopy();
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
            veto.put("createdAt", "2026-07-14T13:02:00+08:00");
        }

        if (EVIDENCE_SYMBOL.equals(request.path("symbol").asText())) {
            ((ObjectNode) response.withArray("evidence").get(0))
                    .put("observedAt", "2026-07-14T13:00:00+08:00");
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
        return STUB_MAPPER.writeValueAsBytes(response);
    }

    private static Map<String, JsonNode> loadFixtures() {
        Map<String, JsonNode> fixtures = new HashMap<>();
        for (String name : List.of(
                "valid-agent-team-evidence-response.json",
                "valid-agent-team-veto-response.json")) {
            try (InputStream input = AgentEvidenceVetoPostgresIntegrationTest.class
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
                    AgentEvidenceVetoPostgresIntegrationTest::handleAnalyze);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
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

    private static JsonNode readJson(String value) {
        try {
            return STUB_MAPPER.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("数据库JSON无法解析", error);
        }
    }
}
