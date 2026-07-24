package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage2EPostgresPythonIntegrationTest {

    private static final String RULE_VERSION = "1.4.0-stage-2e-technical-analysis-v1";
    private static final String DATA_SOURCE = "TEST_FIXTURE_STAGE_2E1";
    private static final String SUCCESS_SYMBOL = "600981";
    private static final String SUCCESS_COMPANION = "600983";
    private static final String INVALID_SYMBOL = "600982";
    private static final String INVALID_COMPANION = "600984";
    private static final String SCHEMA_PREFIX = "stage_2e1_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper PROXY_MAPPER = new ObjectMapper();
    private static final HttpServer PROXY = startProxy();
    private static final Map<Long, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> FORWARDED_RESPONSES = new ConcurrentHashMap<>();
    private static final Set<String> AGENT_CODES = Set.of(
            "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
            "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"
    );
    private static final Map<String, Integer> SCORE_IMPACTS = Map.ofEntries(
            Map.entry("TECH_TREND_BULLISH_ALIGNED", 20),
            Map.entry("TECH_TREND_MIXED", 0),
            Map.entry("TECH_TREND_BEARISH_ALIGNED", -20),
            Map.entry("TECH_RSI_OVERBOUGHT_RISK", -10),
            Map.entry("TECH_RSI_POSITIVE_MOMENTUM", 15),
            Map.entry("TECH_RSI_NEUTRAL", 0),
            Map.entry("TECH_RSI_NEGATIVE_MOMENTUM", -15),
            Map.entry("TECH_RSI_OVERSOLD_RISK", -10),
            Map.entry("TECH_PRICE_ABOVE_MA20_EXTENDED", -10),
            Map.entry("TECH_PRICE_NEAR_MA20", 0),
            Map.entry("TECH_PRICE_BELOW_MA20_EXTENDED", -10),
            Map.entry("TECH_VOLATILITY_ELEVATED", -10),
            Map.entry("TECH_VOLATILITY_NORMAL", 0),
            Map.entry("TECH_INDICATORS_BULLISH_CONFIRMED", 15),
            Map.entry("TECH_INDICATORS_BEARISH_CONFIRMED", -15),
            Map.entry("TECH_INDICATORS_CONFLICT_OR_UNCONFIRMED", 0)
    );

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired AgentTaskService taskService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentContextHashService hashService;

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
        registry.add("stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + PROXY.getAddress().getPort());
    }

    @AfterAll
    static void stopProxyDropSchemaAndVerifyPublic() throws Exception {
        PROXY.stop(0);
        if (!schemaCreated) return;
        requireSafeSchemaName(TEST_SCHEMA);
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + TEST_SCHEMA + "\" CASCADE");
            schemaCreated = false;
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            assertEquals(publicBaseline, publicBaseline(statement));
        }
    }

    @Test
    void persistsSixRunTechnicalAnalysisThroughRealPostgresAndPython() throws Exception {
        assertIsolatedDatabaseAndMigrations();
        Map<String, Long> isolatedBaseline = isolatedCounts();
        LocalDate tradeDate = Instant.now().atZone(SHANGHAI).toLocalDate();
        long taskId = 0L;
        try {
            insertTechnicalScenario(SUCCESS_SYMBOL, SUCCESS_COMPANION, tradeDate);
            CreatedTask created = taskService.create(new CreateAgentTaskRequest(
                    SUCCESS_SYMBOL,
                    tradeDate,
                    ExecutionMode.LOCAL_RULES,
                    RULE_VERSION,
                    true,
                    TriggerType.MANUAL), "stage-2e1-real-postgres-python");
            taskId = created.task().id();
            long createdTaskId = taskId;
            assertTrue(created.newlyCreated());

            await(() -> "PARTIAL".equals(taskStatus(createdTaskId)),
                    "阶段2E-1真实PostgreSQL/Python任务未进入PARTIAL终态");
            assertEquals(1, calls(taskId));

            List<Map<String, Object>> runs = jdbc.queryForList("""
                    SELECT id, agent_code, status, gate_status, decision, score, confidence,
                           veto, output_json::text AS output_json
                    FROM agent_runs WHERE task_id=? ORDER BY id
                    """, taskId);
            assertEquals(6, runs.size());
            assertEquals(AGENT_CODES, values(runs, "agent_code"));
            assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));

            Map<String, Object> technical = runs.stream()
                    .filter(row -> "TECHNICAL_ANALYSIS".equals(row.get("agent_code")))
                    .findFirst().orElseThrow();
            Map<String, Object> quality = runs.stream()
                    .filter(row -> "DATA_QUALITY".equals(row.get("agent_code")))
                    .findFirst().orElseThrow();
            assertEquals("COMPLETED", technical.get("status"));
            assertEquals(quality.get("gate_status"), technical.get("gate_status"));
            assertEquals("WARN", technical.get("decision"));
            assertEquals(false, technical.get("veto"));
            JsonNode technicalOutput = readJson((String) technical.get("output_json"));
            assertEquals(RULE_VERSION, technicalOutput.path("ruleVersion").asText());
            assertEquals(created.task().contextHash(), technicalOutput.path("contextHash").asText());
            assertEquals(5, technicalOutput.path("findings").size());
            assertEquals(2, technicalOutput.path("evidence").size());
            assertEquals(0, technicalOutput.path("errors").size());
            List<String> reasonCodes = new ArrayList<>();
            technicalOutput.path("findings").forEach(item -> reasonCodes.add(item.path("code").asText()));
            assertEquals(5, new HashSet<>(reasonCodes).size());
            int expectedScore = Math.max(0, Math.min(100,
                    50 + reasonCodes.stream().mapToInt(SCORE_IMPACTS::get).sum()));
            assertEquals(expectedScore, ((Number) technical.get("score")).intValue());
            int expectedConfidence = "PASS".equals(quality.get("gate_status")) ? 100 : 50;
            assertEquals(expectedConfidence, ((Number) technical.get("confidence")).intValue());

            List<Map<String, Object>> evidence = jdbc.queryForList("""
                    SELECT evidence_key, category, source_type, source_name, source_ref,
                           content_hash, payload_json::text AS payload_json
                    FROM agent_evidence WHERE task_id=? ORDER BY id
                    """, taskId);
            assertEquals(4, evidence.size());
            assertEquals(List.of(
                            "DATA_QUALITY", "MARKET_BREADTH", "TECHNICAL_INDICATOR", "MARKET_DATA"),
                    evidence.stream().map(row -> String.valueOf(row.get("category"))).toList());
            assertEquals("ta-metrics-" + created.task().contextHash(),
                    evidence.get(2).get("evidence_key"));
            assertEquals("AgentTechnicalMetricsService", evidence.get(2).get("source_name"));
            assertEquals("contextSnapshot.technicalMetrics", evidence.get(2).get("source_ref"));
            assertEquals("ta-market-" + created.task().contextHash(),
                    evidence.get(3).get("evidence_key"));
            assertEquals("contextSnapshot.marketData", evidence.get(3).get("source_ref"));
            assertEquals("JAVA_ENGINE", evidence.get(2).get("source_type"));
            assertEquals("JAVA_ENGINE", evidence.get(3).get("source_type"));
            assertEquals(created.task().contextHash(), evidence.get(2).get("content_hash"));
            assertEquals(created.task().contextHash(), evidence.get(3).get("content_hash"));

            JsonNode metricsProjection = readJson((String) evidence.get(2).get("payload_json"));
            JsonNode marketProjection = readJson((String) evidence.get(3).get("payload_json"));
            assertEquals(Set.of("technicalMetrics"), fieldNames(metricsProjection));
            assertEquals(Set.of("marketData"), fieldNames(marketProjection));
            assertEquals(Set.of(
                            "available", "formulaVersion", "adjustType", "requestedTradeDate",
                            "effectiveTradeDate", "requiredBars", "actualBars", "windows", "values"),
                    fieldNames(metricsProjection.path("technicalMetrics")));
            assertEquals(Set.of(
                            "available", "adjustType", "requestedTradeDate", "effectiveTradeDate",
                            "exactTradeDateMatch", "actualBars", "latestBar"),
                    fieldNames(marketProjection.path("marketData")));

            Map<String, Object> decision = jdbc.queryForMap("""
                    SELECT decision, gate_status, vetoed, score, confidence
                    FROM agent_decisions WHERE task_id=?
                    """, taskId);
            assertEquals("INSUFFICIENT_DATA", decision.get("decision"));
            assertEquals(false, decision.get("vetoed"));
            assertEquals(0, ((Number) decision.get("score")).intValue());
            assertEquals(0, ((Number) decision.get("confidence")).intValue());
            assertEquals(0, countForTask("agent_vetoes", taskId));
            assertEquals(runs.stream().map(row -> ((Number) row.get("id")).longValue()).toList(),
                    jdbc.queryForList(
                            "SELECT unnest(source_run_ids) FROM agent_decisions WHERE task_id=?",
                            Long.class,
                            taskId));

            JsonNode frozen = created.task().contextSnapshot();
            String persistedJson = jdbc.queryForObject(
                    "SELECT context_snapshot_json::text FROM agent_tasks WHERE id=?",
                    String.class,
                    taskId);
            JsonNode persisted = readJson(persistedJson);
            AgentStage2CReadonlyContextPostgresIntegrationTest.assertJsonSemanticallyEquals(
                    frozen,
                    persisted);
            assertEquals(created.task().contextHash(), hashService.hash(persisted));
            assertEquals("JAVA_INDICATORS_V1",
                    frozen.path("technicalMetrics").path("formulaVersion").asText());
            assertEquals(61, frozen.path("marketData").path("bars").size());
            for (JsonNode bar : frozen.path("marketData").path("bars")) {
                assertFalse(LocalDate.parse(bar.path("tradeDate").asText()).isAfter(tradeDate));
            }
            assertNoInvestmentInstruction(technicalOutput.toString());
            assertEquals(publicBaseline, currentPublicBaseline());
        } finally {
            cleanScenario(taskId, SUCCESS_SYMBOL, SUCCESS_COMPANION);
            assertEquals(isolatedBaseline, isolatedCounts());
            assertEquals(publicBaseline, currentPublicBaseline());
        }
    }

    @Test
    void rejectsTamperedStage2EResponseWithoutPartialResultPersistence() throws Exception {
        assertIsolatedDatabaseAndMigrations();
        Map<String, Long> isolatedBaseline = isolatedCounts();
        LocalDate tradeDate = Instant.now().atZone(SHANGHAI).toLocalDate();
        long taskId = 0L;
        try {
            insertTechnicalScenario(INVALID_SYMBOL, INVALID_COMPANION, tradeDate);
            CreatedTask created = taskService.create(new CreateAgentTaskRequest(
                    INVALID_SYMBOL,
                    tradeDate,
                    ExecutionMode.LOCAL_RULES,
                    RULE_VERSION,
                    true,
                    TriggerType.MANUAL), "stage-2e1-invalid-response-atomic");
            taskId = created.task().id();
            long createdTaskId = taskId;
            await(() -> "FAILED".equals(taskStatus(createdTaskId)),
                    "阶段2E-1篡改响应任务未进入FAILED终态");

            assertEquals(1, calls(taskId));
            JsonNode forwarded = FORWARDED_RESPONSES.get(taskId);
            assertNotNull(forwarded);
            assertEquals(4, forwarded.path("evidence").size());
            assertEquals(6, forwarded.path("agentRuns").size());
            assertEquals(1, countForTask("agent_tasks", taskId));
            assertEquals(6, countForTask("agent_runs", taskId));
            assertEquals(0, countForTask("agent_evidence", taskId));
            assertEquals(0, countForTask("agent_vetoes", taskId));
            assertEquals(0, countForTask("agent_decisions", taskId));

            List<Map<String, Object>> runs = jdbc.queryForList("""
                    SELECT status, score, confidence, veto, summary,
                           output_json::text AS output_json, error_message
                    FROM agent_runs WHERE task_id=? ORDER BY id
                    """, taskId);
            assertEquals(6, runs.size());
            for (Map<String, Object> run : runs) {
                assertEquals("FAILED", run.get("status"));
                assertNull(run.get("score"));
                assertNull(run.get("confidence"));
                assertEquals(false, run.get("veto"));
                assertNull(run.get("summary"));
                assertNull(run.get("output_json"));
                assertEquals("智能体响应校验失败", run.get("error_message"));
            }
            assertEquals("智能体响应校验失败", jdbc.queryForObject(
                    "SELECT error_message FROM agent_tasks WHERE id=?",
                    String.class,
                    taskId));
            assertEquals(publicBaseline, currentPublicBaseline());
        } finally {
            cleanScenario(taskId, INVALID_SYMBOL, INVALID_COMPANION);
            assertEquals(isolatedBaseline, isolatedCounts());
            assertEquals(publicBaseline, currentPublicBaseline());
        }
    }

    private void insertTechnicalScenario(String symbol, String companion, LocalDate tradeDate) {
        insertSecurity(symbol, "Stage2E primary");
        insertSecurity(companion, "Stage2E companion");
        for (int offset = 60; offset >= 0; offset--) {
            LocalDate date = tradeDate.minusDays(offset);
            BigDecimalValue close = new BigDecimalValue(10_000L + (60L - offset) * 100L);
            insertBar(symbol, date, close);
        }
        insertBar(companion, tradeDate.minusDays(1), new BigDecimalValue(10_000L));
        insertBar(companion, tradeDate, new BigDecimalValue(11_000L));
    }

    private void insertSecurity(String symbol, String name) {
        jdbc.update("""
                INSERT INTO securities(symbol,name,exchange,board,industry,list_date,
                    is_st,is_active,data_source,updated_at)
                VALUES (?,?,'SSE','MAIN','TEST',DATE '2000-01-01',false,true,?,CURRENT_TIMESTAMP)
                """, symbol, name, DATA_SOURCE);
    }

    private void insertBar(String symbol, LocalDate tradeDate, BigDecimalValue scaledClose) {
        java.math.BigDecimal close = scaledClose.decimal();
        jdbc.update("""
                INSERT INTO daily_bars(symbol,trade_date,open,high,low,close,volume,
                    amount,turnover_rate,adjust_type)
                VALUES (?,?,?,?,?,?,?,?,?,'QFQ')
                """,
                symbol,
                tradeDate,
                close,
                close.add(new java.math.BigDecimal("0.50")),
                close.subtract(new java.math.BigDecimal("0.50")),
                close,
                1000L,
                new java.math.BigDecimal("100000.00"),
                new java.math.BigDecimal("0.5000"));
    }

    private void cleanScenario(long taskId, String symbol, String companion) {
        if (taskId > 0) {
            jdbc.update("DELETE FROM agent_tasks WHERE id=?", taskId);
            for (String table : List.of(
                    "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions")) {
                assertEquals(0, countForTask(table, taskId));
            }
            CALLS.remove(taskId);
            FORWARDED_RESPONSES.remove(taskId);
        }
        jdbc.update("DELETE FROM daily_bars WHERE symbol IN (?,?)", symbol, companion);
        jdbc.update("DELETE FROM securities WHERE symbol IN (?,?) AND data_source=?",
                symbol,
                companion,
                DATA_SOURCE);
    }

    private void assertIsolatedDatabaseAndMigrations() {
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(TEST_SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), jdbc.queryForList("""
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
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions")
                .contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class,
                taskId);
        return value == null ? 0 : value;
    }

    private Map<String, Long> isolatedCounts() {
        Map<String, Long> result = new LinkedHashMap<>();
        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=current_schema() AND table_type='BASE TABLE'
                ORDER BY table_name
                """, String.class);
        for (String table : tables) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM " + quoteIdentifier(table),
                    Long.class);
            result.put(table, count == null ? 0L : count);
        }
        return Map.copyOf(result);
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("阶段2E-1持久化JSON无法解析", error);
        }
    }

    private static Set<String> values(List<Map<String, Object>> rows, String column) {
        Set<String> result = new HashSet<>();
        rows.forEach(row -> result.add(String.valueOf(row.get(column))));
        return result;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static void assertNoInvestmentInstruction(String value) {
        for (String forbidden : Set.of("买入", "卖出", "加仓", "减仓", "目标价", "收益承诺")) {
            assertFalse(value.contains(forbidden), forbidden);
        }
    }

    private static int calls(long taskId) {
        AtomicInteger value = CALLS.get(taskId);
        return value == null ? 0 : value.get();
    }

    private static void await(BooleanSupplier condition, String failureMessage) {
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
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
                    0);
            server.createContext("/agents/team/analyze", AgentStage2EPostgresPythonIntegrationTest::proxy);
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
        CALLS.computeIfAbsent(taskId, ignored -> new AtomicInteger()).incrementAndGet();
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        try {
            byte[] responseBytes = forwardToPython(requestBytes);
            ObjectNode response = (ObjectNode) PROXY_MAPPER.readTree(responseBytes);
            if (INVALID_SYMBOL.equals(request.path("symbol").asText())) {
                for (JsonNode run : response.withArray("agentRuns")) {
                    if ("TECHNICAL_ANALYSIS".equals(run.path("agentCode").asText())) {
                        ObjectNode technical = (ObjectNode) run;
                        int score = technical.path("score").asInt();
                        technical.put("score", score == 100 ? 99 : score + 1);
                    }
                }
            }
            FORWARDED_RESPONSES.put(taskId, response.deepCopy());
            byte[] output = PROXY_MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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
                .create(baseUrl + "/agents/team/analyze").toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(request.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(request);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        byte[] body = stream == null ? new byte[0] : stream.readAllBytes();
        connection.disconnect();
        if (status != 200) {
            throw new IOException("Python returned HTTP " + status);
        }
        return body;
    }

    private static void createIsolatedSchema() {
        requireSafeSchemaName(TEST_SCHEMA);
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_database()"));
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            publicBaseline = publicBaseline(statement);
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2e1_it_ schema creation",
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
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            return publicBaseline(statement);
        }
    }

    private static PublicBaseline publicBaseline(Statement statement) throws Exception {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            rows.put(table, scalar(statement,
                    "SELECT count(*) FROM public." + quoteIdentifier(table)));
        }
        List<String> objects = strings(statement, """
                SELECT kind || ':' || identity FROM (
                    SELECT 'relation' kind, c.relkind::text || ':' || c.relname identity
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'constraint', c.conname || ':' || c.contype::text || ':'
                           || coalesce(pg_get_constraintdef(c.oid),'')
                    FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'trigger', r.relname || ':' || t.tgname
                    FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=r.relnamespace
                    WHERE n.nspname='public' AND NOT t.tgisinternal
                    UNION ALL
                    SELECT 'function', p.proname || ':' || pg_get_function_identity_arguments(p.oid)
                           || ':' || md5(pg_get_functiondef(p.oid))
                    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
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
                FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
                ORDER BY e.extname
                """);
        return new PublicBaseline(Map.copyOf(rows), objects, history, extensions);
    }

    private static List<String> strings(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return List.copyOf(values);
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) throw new IllegalStateException("scalar query returned no row");
            return row.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql) throws Exception {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) throw new IllegalStateException("scalar query returned no row");
            return row.getString(1);
        }
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void requireSafeSchemaName(String schema) {
        if (!schema.matches("^stage_2e1_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe temporary schema name");
        }
    }

    private record BigDecimalValue(long scaledByThousand) {
        private java.math.BigDecimal decimal() {
            return java.math.BigDecimal.valueOf(scaledByThousand, 3);
        }
    }

    private record PublicBaseline(
            Map<String, Long> tableRows,
            List<String> schemaObjects,
            List<String> flywayHistory,
            List<String> extensions
    ) {}
}
