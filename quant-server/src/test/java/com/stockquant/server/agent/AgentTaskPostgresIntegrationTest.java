package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.repository.AgentEvidenceRepository;
import com.stockquant.server.agent.repository.AgentVetoRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
class AgentTaskPostgresIntegrationTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper STUB_MAPPER = new ObjectMapper();
    private static final List<Long> TASKS_TO_DELETE = new CopyOnWriteArrayList<>();
    private static final Map<Long, AtomicInteger> CALLS_BY_TASK = new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> REQUESTS_BY_TASK = new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> RESPONSES_BY_TASK = new ConcurrentHashMap<>();
    private static final Set<String> FAILING_SYMBOLS = ConcurrentHashMap.newKeySet();

    private static final JsonNode sharedResponseFixture = loadSharedResponseFixture();
    private static final HttpServer stubServer = startLocalStub();

    @Autowired
    private AgentTaskService taskService;

    @Autowired
    private AgentTaskWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentEvidenceRepository evidenceRepository;

    @Autowired
    private AgentVetoRepository vetoRepository;

    @DynamicPropertySource
    static void agentTeamBaseUrl(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
        registry.add("stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + stubServer.getAddress().getPort());
    }

    @AfterEach
    void cleanCreatedTasks() {
        for (Long taskId : new ArrayList<>(TASKS_TO_DELETE)) {
            jdbc.update("DELETE FROM agent_tasks WHERE id = ?", taskId);
            assertEquals(0, count("agent_tasks", taskId));
            assertEquals(0, count("agent_runs", taskId));
            assertEquals(0, count("agent_evidence", taskId));
            assertEquals(0, count("agent_vetoes", taskId));
            assertEquals(0, count("agent_decisions", taskId));
            TASKS_TO_DELETE.remove(taskId);
            CALLS_BY_TASK.remove(taskId);
            REQUESTS_BY_TASK.remove(taskId);
            RESPONSES_BY_TASK.remove(taskId);
        }
        FAILING_SYMBOLS.clear();
    }

    @AfterAll
    static void stopStub() {
        if (stubServer != null) {
            stubServer.stop(0);
        }
    }

    @Test
    void completesRealPostgresAfterCommitHttpAndPersistenceFlow() {
        assertDedicatedDatabaseAndFlywayV5();
        String symbol = uniqueSymbol(810000);
        CreatedTask created = createTask(symbol, "integration-success");
        long taskId = track(created.task().id());

        await(() -> Set.of("PARTIAL", "FAILED").contains(taskStatus(taskId)), "成功任务未进入终态");

        JsonNode request = REQUESTS_BY_TASK.get(taskId);
        JsonNode stubResponse = RESPONSES_BY_TASK.get(taskId);
        assertNotNull(request);
        assertNotNull(stubResponse);
        assertTrue(request.path("tradeDate").isTextual());
        assertEquals("2026-07-14", request.path("tradeDate").asText());
        assertTrue(stubResponse.path("tradeDate").isTextual());
        assertEquals(request.path("tradeDate").asText(), stubResponse.path("tradeDate").asText());
        assertEquals("PARTIAL", taskStatus(taskId), "有效测试桩响应必须形成PARTIAL任务");

        assertEquals(1, count("agent_tasks", taskId));
        assertEquals(6, count("agent_runs", taskId));
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT id, agent_code, status, gate_status, decision, score, confidence,
                       veto, summary, output_json::text AS output_json
                FROM agent_runs WHERE task_id = ? ORDER BY id
                """, taskId);
        assertProfessionalRuns(runs);
        assertEquals(Set.of("INSUFFICIENT_DATA"), values(runs, "status"));
        assertEquals("BLOCKED", runs.stream()
                .filter(run -> "DATA_QUALITY".equals(run.get("agent_code")))
                .findFirst().orElseThrow().get("gate_status"));
        assertTrue(runs.stream()
                .filter(run -> !"DATA_QUALITY".equals(run.get("agent_code")))
                .allMatch(run -> "NOT_APPLICABLE".equals(run.get("gate_status"))));
        assertEquals(1, calls(taskId));

        assertEquals(taskId, request.path("taskId").asLong());
        assertEquals(symbol, request.path("symbol").asText());
        assertEquals("2026-07-14", request.path("tradeDate").asText());
        assertEquals("integration-success", request.path("ruleVersion").asText());
        assertEquals("LOCAL_RULES", request.path("executionMode").asText());
        assertEquals(created.task().contextHash(), request.path("contextHash").asText());
        assertRunIdsMatchDatabase(request.path("runIds"), runs);
        assertEquals(created.task().contextGeneratedAt(), jdbc.queryForObject(
                "SELECT context_generated_at FROM agent_tasks WHERE id = ?",
                OffsetDateTime.class, taskId).toInstant());

        for (Map<String, Object> run : runs) {
            assertNotNull(run.get("output_json"));
            JsonNode output = readJson((String) run.get("output_json"));
            assertEquals(((Number) run.get("id")).longValue(), output.path("runId").asLong());
            assertEquals(taskId, output.path("taskId").asLong());
        }

        assertEquals(1, count("agent_decisions", taskId));
        Map<String, Object> decision = jdbc.queryForMap("""
                SELECT status, decision, gate_status, vetoed, source_run_ids,
                       decision_json::text AS decision_json
                FROM agent_decisions WHERE task_id = ?
                """, taskId);
        assertEquals("INSUFFICIENT_DATA", decision.get("status"));
        assertEquals("BLOCKED_BY_DATA_QUALITY", decision.get("decision"));
        assertEquals("BLOCKED", decision.get("gate_status"));
        assertEquals(false, decision.get("vetoed"));
        assertEquals("BLOCKED_BY_DATA_QUALITY",
                readJson((String) decision.get("decision_json")).path("decision").asText());
        assertEquals(Instant.parse("2026-07-14T05:02:00Z"), jdbc.queryForObject(
                "SELECT generated_at FROM agent_decisions WHERE task_id = ?",
                OffsetDateTime.class, taskId).toInstant());
        assertEquals(0, count("agent_evidence", taskId));
        assertEquals(0, count("agent_vetoes", taskId));

        worker.execute(taskId);
        assertEquals(1, calls(taskId), "重复启动不得再次调用HTTP服务");
        assertEquals(1, count("agent_decisions", taskId), "重复启动不得重复保存总控决策");
    }

    @Test
    void persistsSafeFailureInRequiresNewWithoutPartialResultsOrRetry() {
        assertDedicatedDatabaseAndFlywayV5();
        String symbol = uniqueSymbol(820000);
        FAILING_SYMBOLS.add(symbol);
        CreatedTask created = createTask(symbol, "integration-http-500");
        long taskId = track(created.task().id());

        await(() -> "FAILED".equals(taskStatus(taskId)), "失败任务未进入FAILED终态");

        assertEquals(1, calls(taskId));
        assertEquals(0, count("agent_decisions", taskId));
        assertEquals(0, count("agent_evidence", taskId));
        assertEquals(0, count("agent_vetoes", taskId));
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT status, score, confidence, summary, output_json::text AS output_json,
                       veto, error_message
                FROM agent_runs WHERE task_id = ? ORDER BY id
                """, taskId);
        assertEquals(6, runs.size());
        for (Map<String, Object> run : runs) {
            assertEquals("FAILED", run.get("status"));
            assertNull(run.get("score"));
            assertNull(run.get("confidence"));
            assertNull(run.get("summary"));
            assertNull(run.get("output_json"));
            assertEquals(false, run.get("veto"));
            assertEquals("智能体分析服务暂时不可用", run.get("error_message"));
        }
        assertEquals("智能体分析服务暂时不可用", jdbc.queryForObject(
                "SELECT error_message FROM agent_tasks WHERE id = ?", String.class, taskId));

        worker.execute(taskId);
        assertEquals(1, calls(taskId), "失败终态重复启动不得重试HTTP调用");
        assertEquals(0, count("agent_decisions", taskId));
    }

    @Test
    void writesAndReadsEvidenceAndVetoTimestamptzValues() {
        assertDedicatedDatabaseAndFlywayV5();
        Instant observedAt = OffsetDateTime.parse("2026-07-14T13:10:00+08:00").toInstant();
        Instant collectedAt = Instant.parse("2026-07-14T05:11:00Z");
        Instant vetoCreatedAt = OffsetDateTime.parse("2026-07-14T13:12:00+08:00").toInstant();
        long taskId = track(insertRepositoryParentTask());
        long runId = insertPositionRiskParentRun(taskId);

        Evidence evidence = new Evidence(
                "evidence-time-1",
                EvidenceCategory.QUERY_RESULT,
                EvidenceSourceType.JAVA_ENGINE,
                "agent-integration-test",
                "repository-time-check",
                "600001",
                LocalDate.of(2026, 7, 14),
                observedAt,
                collectedAt,
                STUB_MAPPER.createObjectNode().put("returnedCount", 1),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );
        evidenceRepository.saveAll(taskId, List.of(evidence), Map.of(evidence.evidenceId(), runId));

        FormalVeto veto = new FormalVeto(
                "veto-time-1",
                taskId,
                runId,
                AgentCode.POSITION_RISK,
                "POSITION_LIMIT",
                "专用Repository时间绑定验证",
                List.of(evidence.evidenceId()),
                vetoCreatedAt
        );
        vetoRepository.saveAll(List.of(veto));

        Evidence storedEvidence = evidenceRepository.findByTaskId(taskId).get(0);
        FormalVeto storedVeto = vetoRepository.findByTaskId(taskId).get(0);
        assertEquals(observedAt, storedEvidence.observedAt());
        assertEquals(collectedAt, storedEvidence.collectedAt());
        assertEquals(vetoCreatedAt, storedVeto.createdAt());
    }

    private CreatedTask createTask(String symbol, String ruleVersion) {
        return taskService.create(new CreateAgentTaskRequest(
                symbol,
                LocalDate.of(2026, 7, 14),
                ExecutionMode.LOCAL_RULES,
                ruleVersion,
                true,
                TriggerType.MANUAL
        ), "agent-integration-test");
    }

    private long track(long taskId) {
        TASKS_TO_DELETE.add(taskId);
        return taskId;
    }

    private long insertRepositoryParentTask() {
        return jdbc.queryForObject("""
                INSERT INTO agent_tasks (
                    symbol, trade_date, status, context_schema_version, context_snapshot_json,
                    context_generated_at, context_hash, rule_version, execution_mode,
                    trigger_type, force_refresh, cache_hit, created_at, updated_at
                ) VALUES ('600001', DATE '2026-07-14', 'RUNNING', '1.0', '{}'::jsonb,
                          ?, ?, 'repository-time-test', 'LOCAL_RULES', 'MANUAL',
                          TRUE, FALSE, now(), now())
                RETURNING id
                """, Long.class,
                Instant.parse("2026-07-14T05:00:00Z").atOffset(ZoneOffset.UTC),
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
    }

    private long insertPositionRiskParentRun(long taskId) {
        return jdbc.queryForObject("""
                INSERT INTO agent_runs (
                    task_id, agent_code, attempt_no, status, gate_status, decision,
                    score, confidence, veto, summary, output_json, started_at,
                    finished_at, duration_ms, created_at, updated_at
                ) VALUES (?, 'POSITION_RISK', 1, 'COMPLETED', 'WARN', 'REJECT',
                          0, 100, TRUE, 'Repository时间绑定父运行', '{}'::jsonb,
                          now(), now(), 0, now(), now())
                RETURNING id
                """, Long.class, taskId);
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

    private void assertProfessionalRuns(List<Map<String, Object>> runs) {
        Set<String> expected = Set.of(
                "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
                "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"
        );
        assertEquals(expected, values(runs, "agent_code"));
        assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));
        assertEquals(6, new HashSet<>(values(runs, "agent_code")).size());
    }

    private static Set<String> values(List<Map<String, Object>> rows, String column) {
        Set<String> values = new HashSet<>();
        rows.forEach(row -> values.add(String.valueOf(row.get(column))));
        return values;
    }

    private static void assertRunIdsMatchDatabase(JsonNode runIds, List<Map<String, Object>> runs) {
        Map<String, String> fields = Map.of(
                "DATA_QUALITY", "dataQuality",
                "MARKET_REGIME", "marketRegime",
                "TECHNICAL_ANALYSIS", "technicalAnalysis",
                "STRATEGY_BACKTEST", "strategyBacktest",
                "ANNOUNCEMENT_RISK", "announcementRisk",
                "POSITION_RISK", "positionRisk"
        );
        for (Map<String, Object> run : runs) {
            String code = (String) run.get("agent_code");
            assertEquals(((Number) run.get("id")).longValue(), runIds.path(fields.get(code)).asLong());
        }
    }

    private String taskStatus(long taskId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM agent_tasks WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getString(1),
                taskId
        );
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private int count(String table, long taskId) {
        Set<String> allowed = Set.of(
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions"
        );
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, taskId);
        return value == null ? 0 : value;
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

    private static String uniqueSymbol(int base) {
        return String.valueOf(base + Math.floorMod(System.nanoTime(), 90000));
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
        if (FAILING_SYMBOLS.contains(request.path("symbol").asText())) {
            byte[] body = "test stub failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }

        byte[] response = responseFor(request);
        RESPONSES_BY_TASK.put(taskId, STUB_MAPPER.readTree(response));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static JsonNode loadSharedResponseFixture() {
        try (InputStream input = AgentTaskPostgresIntegrationTest.class.getResourceAsStream(
                "/agent-team-contract/valid-agent-team-response.json")) {
            if (input == null) {
                throw new IllegalStateException("共享响应夹具不存在");
            }
            return STUB_MAPPER.readTree(input);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static HttpServer startLocalStub() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/agents/team/analyze", AgentTaskPostgresIntegrationTest::handleAnalyze);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static byte[] responseFor(JsonNode request) throws IOException {
        ObjectNode response = sharedResponseFixture.deepCopy();
        long taskId = request.path("taskId").asLong();
        response.put("taskId", taskId);
        response.put("contextHash", request.path("contextHash").asText());
        response.put("tradeDate", request.path("tradeDate").asText());
        response.put("ruleVersion", request.path("ruleVersion").asText());
        response.put("executionMode", request.path("executionMode").asText());

        JsonNode requestedRunIds = request.path("runIds");
        ArrayNode runs = (ArrayNode) response.path("agentRuns");
        Map<String, String> fields = Map.of(
                "DATA_QUALITY", "dataQuality",
                "MARKET_REGIME", "marketRegime",
                "TECHNICAL_ANALYSIS", "technicalAnalysis",
                "STRATEGY_BACKTEST", "strategyBacktest",
                "ANNOUNCEMENT_RISK", "announcementRisk",
                "POSITION_RISK", "positionRisk"
        );
        for (JsonNode value : runs) {
            ObjectNode run = (ObjectNode) value;
            run.put("taskId", taskId);
            run.put("runId", requestedRunIds.path(fields.get(run.path("agentCode").asText())).asLong());
            run.put("contextHash", request.path("contextHash").asText());
            run.put("ruleVersion", request.path("ruleVersion").asText());
            run.put("executionMode", request.path("executionMode").asText());
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

    private static JsonNode readJson(String value) {
        try {
            return STUB_MAPPER.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("数据库JSON无法解析", error);
        }
    }
}
