package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.backtest.MarketDataPersistenceService;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentTaskService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage2FPostgresPythonIntegrationTest {

    private static final String SUCCESS_SYMBOL = "600991";
    private static final String SUCCESS_COMPANION = "600993";
    private static final String INVALID_SYMBOL = "600992";
    private static final String INVALID_COMPANION = "600994";
    private static final String SOURCE = "TEST_FIXTURE_STAGE_2F";
    private static final String SCHEMA_PREFIX = "stage_2f_team_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper PROXY_MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final HttpServer PROXY = startProxy();
    private static final Map<Long, AtomicInteger> CALLS =
            new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> FORWARDED_RESPONSES =
            new ConcurrentHashMap<>();
    private static final Set<String> AGENT_CODES = Set.of(
            "DATA_QUALITY",
            "MARKET_REGIME",
            "TECHNICAL_ANALYSIS",
            "STRATEGY_BACKTEST",
            "ANNOUNCEMENT_RISK",
            "POSITION_RISK");

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired AgentTaskService taskService;
    @Autowired MarketDataPersistenceService persistenceService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentContextHashService contextHashes;
    @MockBean(name = "agentTemporalClock") Clock agentTemporalClock;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        credentials = AgentPostgresTestEnvironment.validate(
                System.getenv("STOCK_QUANT_TEST_DB_URL"),
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME"),
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD"));
        createIsolatedSchema();
        registry.add("spring.datasource.url", () -> schemaUrl(TEST_SCHEMA));
        registry.add("spring.datasource.username", credentials::username);
        registry.add("spring.datasource.password", credentials::password);
        registry.add("spring.flyway.default-schema", () -> TEST_SCHEMA);
        registry.add("spring.flyway.schemas", () -> TEST_SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> false);
        registry.add(
                "stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + PROXY.getAddress().getPort());
    }

    @AfterAll
    static void stopProxyDropSchemaAndVerifyPublic() throws Exception {
        PROXY.stop(0);
        if (!schemaCreated) return;
        requireSafeSchemaName(TEST_SCHEMA);
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + TEST_SCHEMA + "\" CASCADE");
            schemaCreated = false;
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='""" + TEST_SCHEMA + "'"));
            assertEquals(publicBaseline, publicBaseline(statement));
        }
    }

    @Test
    void persistsReliableStrategyBacktestThroughRealPostgresAndPython()
            throws Exception {
        assertIsolatedDatabaseAndMigrations();
        LocalDate tradeDate = LocalDate.now(BacktestContracts.MARKET_ZONE)
                .minusDays(1);
        freezeCaptureTime(tradeDate);
        insertScenario(SUCCESS_SYMBOL, SUCCESS_COMPANION, tradeDate);
        freezeDecisionTimeReached(tradeDate);

        CreatedTask created = taskService.create(
                request(SUCCESS_SYMBOL, tradeDate),
                "stage-2f-real-postgres-python");
        long taskId = created.task().id();
        assertTrue(created.newlyCreated());
        await(
                () -> "PARTIAL".equals(taskStatus(taskId)),
                "stage 2F real PostgreSQL/Python task did not reach PARTIAL");
        assertEquals(1, calls(taskId));

        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT id, agent_code, status, gate_status, decision, score,
                       confidence, veto, output_json::text AS output_json
                FROM agent_runs WHERE task_id=? ORDER BY id
                """, taskId);
        assertEquals(6, runs.size());
        assertEquals(AGENT_CODES, values(runs, "agent_code"));
        assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));

        Map<String, Object> strategy = runs.stream()
                .filter(row -> "STRATEGY_BACKTEST".equals(row.get("agent_code")))
                .findFirst()
                .orElseThrow();
        assertEquals("COMPLETED", strategy.get("status"));
        assertEquals("WARN", strategy.get("decision"));
        assertEquals(false, strategy.get("veto"));
        assertEquals(40, ((Number) strategy.get("confidence")).intValue());
        JsonNode strategyOutput = readJson((String) strategy.get("output_json"));
        assertEquals(5, strategyOutput.path("findings").size());
        assertEquals(1, strategyOutput.path("evidence").size());
        assertEquals(0, strategyOutput.path("errors").size());
        assertEquals(
                BacktestContracts.RULE_VERSION,
                strategyOutput.path("ruleVersion").asText());
        assertEquals(
                created.task().contextHash(),
                strategyOutput.path("contextHash").asText());

        List<Map<String, Object>> evidence = jdbc.queryForList("""
                SELECT evidence_key, category, source_type, source_name,
                       source_ref, content_hash, payload_json::text AS payload_json
                FROM agent_evidence WHERE task_id=? ORDER BY id
                """, taskId);
        assertEquals(5, evidence.size());
        assertEquals(
                List.of(
                        "DATA_QUALITY",
                        "MARKET_BREADTH",
                        "TECHNICAL_INDICATOR",
                        "MARKET_DATA",
                        "BACKTEST_RESULT"),
                evidence.stream()
                        .map(row -> String.valueOf(row.get("category")))
                        .toList());

        JsonNode context = created.task()
                .contextSnapshot()
                .path("backtestContext");
        assertTrue(context.path("available").asBoolean());
        assertEquals(
                BacktestContracts.CONTEXT_PROFILE,
                context.path("contextProfile").asText());
        assertEquals(
                BacktestContracts.CONTEXT_SCHEMA_VERSION,
                context.path("schemaVersion").asText());
        assertEquals(120, context.path("barCount").asInt());
        assertEquals(120, context.path("bars").size());
        assertEquals(3, context.path("subperiods").size());
        assertEquals(
                context.path("backtestResultHash").asText(),
                evidence.get(4).get("content_hash"));
        assertTrue(context.path("inputDataHash").asText().matches("[0-9a-f]{64}"));
        assertTrue(context.path("strategyDefinitionHash")
                .asText().matches("[0-9a-f]{64}"));
        assertTrue(context.path("backtestResultHash")
                .asText().matches("[0-9a-f]{64}"));

        String persistedJson = jdbc.queryForObject(
                "SELECT context_snapshot_json::text FROM agent_tasks WHERE id=?",
                String.class,
                taskId);
        JsonNode persisted = readJson(persistedJson);
        AgentStage2CReadonlyContextPostgresIntegrationTest
                .assertJsonSemanticallyEquals(
                        created.task().contextSnapshot(),
                        persisted);
        assertEquals(created.task().contextHash(), contextHashes.hash(persisted));
        assertEquals("INSUFFICIENT_DATA", jdbc.queryForObject(
                "SELECT decision FROM agent_decisions WHERE task_id=?",
                String.class,
                taskId));
        assertEquals(0, countForTask("agent_vetoes", taskId));
        assertNoInvestmentInstruction(strategyOutput.toString());
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void rejectsTamperedStage2FResponseWithoutPartialResultPersistence()
            throws Exception {
        assertIsolatedDatabaseAndMigrations();
        LocalDate tradeDate = LocalDate.now(BacktestContracts.MARKET_ZONE)
                .minusDays(1);
        freezeCaptureTime(tradeDate);
        insertScenario(INVALID_SYMBOL, INVALID_COMPANION, tradeDate);
        freezeDecisionTimeReached(tradeDate);

        CreatedTask created = taskService.create(
                request(INVALID_SYMBOL, tradeDate),
                "stage-2f-invalid-response-atomic");
        long taskId = created.task().id();
        await(
                () -> "FAILED".equals(taskStatus(taskId)),
                "tampered stage 2F response did not reach FAILED");

        assertEquals(1, calls(taskId));
        JsonNode forwarded = FORWARDED_RESPONSES.get(taskId);
        assertNotNull(forwarded);
        assertEquals(6, forwarded.path("agentRuns").size());
        assertEquals(5, forwarded.path("evidence").size());
        assertEquals(1, countForTask("agent_tasks", taskId));
        assertEquals(6, countForTask("agent_runs", taskId));
        assertEquals(0, countForTask("agent_evidence", taskId));
        assertEquals(0, countForTask("agent_vetoes", taskId));
        assertEquals(0, countForTask("agent_decisions", taskId));
        for (Map<String, Object> run : jdbc.queryForList("""
                SELECT status, score, confidence, veto, summary,
                       output_json::text AS output_json, error_message
                FROM agent_runs WHERE task_id=? ORDER BY id
                """, taskId)) {
            assertEquals("FAILED", run.get("status"));
            assertNull(run.get("score"));
            assertNull(run.get("confidence"));
            assertEquals(false, run.get("veto"));
            assertNull(run.get("summary"));
            assertNull(run.get("output_json"));
            assertNotNull(run.get("error_message"));
        }
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private void insertScenario(
            String symbol,
            String companion,
            LocalDate tradeDate
    ) {
        var capture = persistenceService.persistBars(
                symbol,
                bars(symbol, tradeDate, 120),
                SOURCE,
                "REVISION_1",
                "TEST_FIXTURE");
        assertEquals(120, capture.appendedObservationCount());
        var companionCapture = persistenceService.persistBars(
                companion,
                bars(companion, tradeDate, 2),
                SOURCE,
                "REVISION_1",
                "TEST_FIXTURE");
        assertEquals(2, companionCapture.appendedObservationCount());
        jdbc.update("""
                UPDATE securities
                SET name=?, exchange='SSE', board='MAIN', industry='TEST',
                    list_date=DATE '2000-01-01', is_st=false, is_active=true,
                    data_source=?, updated_at=CURRENT_TIMESTAMP
                WHERE symbol=?
                """, "Stage2F " + symbol, SOURCE, symbol);
        jdbc.update("""
                UPDATE securities
                SET name=?, exchange='SSE', board='MAIN', industry='TEST',
                    list_date=DATE '2000-01-01', is_st=false, is_active=true,
                    data_source=?, updated_at=CURRENT_TIMESTAMP
                WHERE symbol=?
                """, "Stage2F " + companion, SOURCE, companion);
    }

    private void freezeCaptureTime(LocalDate tradeDate) {
        when(agentTemporalClock.instant()).thenReturn(
                tradeDate.atTime(12, 0)
                        .atZone(BacktestContracts.MARKET_ZONE)
                        .toInstant());
    }

    private void freezeDecisionTimeReached(LocalDate tradeDate) {
        when(agentTemporalClock.instant()).thenReturn(
                tradeDate.plusDays(1)
                        .atStartOfDay(BacktestContracts.MARKET_ZONE)
                        .toInstant());
    }

    private static List<Bar> bars(
            String symbol,
            LocalDate tradeDate,
            int count
    ) {
        List<Bar> values = new ArrayList<>();
        LocalDate start = tradeDate.minusDays(count - 1L);
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("20.00")
                    .add(new BigDecimal("0.10")
                            .multiply(BigDecimal.valueOf(index)));
            values.add(new Bar(
                    symbol,
                    start.plusDays(index),
                    close,
                    close.add(new BigDecimal("0.50")),
                    close.subtract(new BigDecimal("0.50")),
                    close,
                    10_000L + index,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("0.5000")));
        }
        return List.copyOf(values);
    }

    private static CreateAgentTaskRequest request(
            String symbol,
            LocalDate tradeDate
    ) {
        return new CreateAgentTaskRequest(
                symbol,
                tradeDate,
                ExecutionMode.LOCAL_RULES,
                BacktestContracts.RULE_VERSION,
                true,
                TriggerType.MANUAL);
    }

    private void assertIsolatedDatabaseAndMigrations() {
        assertEquals(
                "stock_quant_test",
                jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(
                "stock_quant_test",
                jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(
                TEST_SCHEMA,
                jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success=TRUE ORDER BY installed_rank
                        """, String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success=FALSE",
                Integer.class));
    }

    private String taskStatus(long taskId) {
        List<String> values = jdbc.query(
                "SELECT status FROM agent_tasks WHERE id=?",
                (row, index) -> row.getString(1),
                taskId);
        return values.isEmpty() ? null : values.get(0);
    }

    private int countForTask(String table, long taskId) {
        if (!Set.of(
                "agent_tasks",
                "agent_runs",
                "agent_evidence",
                "agent_vetoes",
                "agent_decisions").contains(table)) {
            throw new IllegalArgumentException("unsupported task table");
        }
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class,
                taskId);
        return value == null ? 0 : value;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("persisted JSON cannot be parsed", error);
        }
    }

    private static Set<String> values(
            List<Map<String, Object>> rows,
            String column
    ) {
        Set<String> result = new HashSet<>();
        rows.forEach(row -> result.add(String.valueOf(row.get(column))));
        return result;
    }

    private static void assertNoInvestmentInstruction(String value) {
        for (String forbidden : Set.of(
                "买入", "卖出", "加仓", "减仓", "目标价", "收益承诺")) {
            assertFalse(value.contains(forbidden), forbidden);
        }
    }

    private static int calls(long taskId) {
        AtomicInteger value = CALLS.get(taskId);
        return value == null ? 0 : value.get();
    }

    private static void await(
            BooleanSupplier condition,
            String failureMessage
    ) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError(failureMessage);
    }

    private static HttpServer startProxy() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), 0),
                    0);
            server.createContext(
                    "/agents/team/analyze",
                    AgentStage2FPostgresPythonIntegrationTest::proxy);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void proxy(HttpExchange exchange) throws IOException {
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        JsonNode request = PROXY_MAPPER.readTree(requestBytes);
        long taskId = request.path("taskId").asLong();
        CALLS.computeIfAbsent(
                taskId, ignored -> new AtomicInteger()).incrementAndGet();
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try {
            ObjectNode response = (ObjectNode) PROXY_MAPPER.readTree(
                    forwardToPython(requestBytes));
            if (INVALID_SYMBOL.equals(request.path("symbol").asText())) {
                for (JsonNode run : response.withArray("agentRuns")) {
                    if ("STRATEGY_BACKTEST".equals(
                            run.path("agentCode").asText())) {
                        ObjectNode strategy = (ObjectNode) run;
                        strategy.put(
                                "score",
                                strategy.path("score").asInt() == 100
                                        ? 99
                                        : strategy.path("score").asInt() + 1);
                    }
                }
            }
            FORWARDED_RESPONSES.put(taskId, response.deepCopy());
            byte[] output = PROXY_MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
        } catch (Exception error) {
            byte[] output = "proxy failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, output.length);
            exchange.getResponseBody().write(output);
        } finally {
            exchange.close();
        }
    }

    private static byte[] forwardToPython(byte[] request) throws Exception {
        String baseUrl = AgentPythonSmokeEnvironment.validate(
                System.getenv("STOCK_QUANT_PYTHON_BASE_URL"));
        HttpURLConnection connection = (HttpURLConnection) URI
                .create(baseUrl + "/agents/team/analyze")
                .toURL()
                .openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(request.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(request);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
        connection.disconnect();
        if (status != 200) {
            throw new IOException("Python returned HTTP " + status);
        }
        return body;
    }

    private static void createIsolatedSchema() {
        requireSafeSchemaName(TEST_SCHEMA);
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(
                    "stock_quant_test",
                    scalarText(statement, "SELECT current_database()"));
            assertEquals(
                    "stock_quant_test",
                    scalarText(statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='""" + TEST_SCHEMA + "'"));
            publicBaseline = publicBaseline(statement);
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2f_team_it_ schema",
                    error);
        }
    }

    private static Connection controlConnection() throws SQLException {
        return DriverManager.getConnection(
                credentials.url(),
                credentials.username(),
                credentials.password());
    }

    private static String schemaUrl(String schema) {
        String separator = credentials.url().contains("?") ? "&" : "?";
        return credentials.url() + separator + "currentSchema=" + schema;
    }

    private static PublicBaseline currentPublicBaseline() throws Exception {
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            return publicBaseline(statement);
        }
    }

    private static PublicBaseline publicBaseline(Statement statement)
            throws Exception {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            rows.put(
                    table,
                    scalar(
                            statement,
                            "SELECT count(*) FROM public."
                                    + quoteIdentifier(table)));
        }
        List<String> objects = strings(statement, """
                SELECT kind || ':' || identity FROM (
                    SELECT 'relation' kind,
                           c.relkind::text || ':' || c.relname identity
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'constraint',
                           c.conname || ':' || c.contype::text || ':'
                           || coalesce(pg_get_constraintdef(c.oid),'')
                    FROM pg_constraint c
                    JOIN pg_namespace n ON n.oid=c.connamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'trigger', r.relname || ':' || t.tgname
                    FROM pg_trigger t
                    JOIN pg_class r ON r.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=r.relnamespace
                    WHERE n.nspname='public' AND NOT t.tgisinternal
                    UNION ALL
                    SELECT 'function',
                           p.proname || ':'
                           || pg_get_function_identity_arguments(p.oid)
                           || ':' || md5(pg_get_functiondef(p.oid))
                    FROM pg_proc p
                    JOIN pg_namespace n ON n.oid=p.pronamespace
                    WHERE n.nspname='public'
                ) facts ORDER BY kind, identity
                """);
        List<String> history = strings(statement, """
                SELECT installed_rank || ':' || coalesce(version,'') || ':'
                       || coalesce(checksum::text,'') || ':' || success
                FROM public.flyway_schema_history ORDER BY installed_rank
                """);
        List<String> extensions = strings(statement, """
                SELECT e.extname || ':' || e.extversion || ':' || n.nspname
                FROM pg_extension e
                JOIN pg_namespace n ON n.oid=e.extnamespace
                ORDER BY e.extname
                """);
        return new PublicBaseline(
                Map.copyOf(rows), objects, history, extensions);
    }

    private static List<String> strings(
            Statement statement,
            String sql
    ) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return List.copyOf(values);
    }

    private static long scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new IllegalStateException("scalar query returned no row");
            }
            return row.getLong(1);
        }
    }

    private static String scalarText(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new IllegalStateException("scalar query returned no row");
            }
            return row.getString(1);
        }
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void requireSafeSchemaName(String schema) {
        if (!schema.matches("^stage_2f_team_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe temporary schema name");
        }
    }

    private record PublicBaseline(
            Map<String, Long> tableRows,
            List<String> schemaObjects,
            List<String> flywayHistory,
            List<String> extensions
    ) {
    }
}
