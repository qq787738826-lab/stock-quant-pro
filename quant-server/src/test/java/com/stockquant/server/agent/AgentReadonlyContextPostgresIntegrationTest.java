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
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import com.stockquant.server.agent.service.AgentTaskService;
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
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentReadonlyContextPostgresIntegrationTest {

    private static final String SYMBOL = "699991";
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    private static final String DATA_SOURCE = "TEST_FIXTURE_STAGE_2A";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper STUB_MAPPER = new ObjectMapper();
    private static final JsonNode RESPONSE_FIXTURE = loadResponseFixture();
    private static final Map<Long, JsonNode> REQUESTS = new ConcurrentHashMap<>();
    private static final HttpServer STUB_SERVER = startStub();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentContextSnapshotService snapshotService;

    @Autowired
    private AgentContextHashService hashService;

    @Autowired
    private AgentTaskService taskService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long taskId;
    private boolean fixtureInserted;
    private Map<String, Integer> agentBaseline;
    private int securitiesBaseline;
    private int dailyBarsBaseline;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
        registry.add("stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + STUB_SERVER.getAddress().getPort());
    }

    @AfterEach
    void cleanOnlyThisTestData() {
        if (taskId != null) {
            jdbc.update("DELETE FROM agent_tasks WHERE id = ?", taskId);
            assertEquals(0, scopedCount("agent_tasks", taskId));
            assertEquals(0, scopedCount("agent_runs", taskId));
            assertEquals(0, scopedCount("agent_evidence", taskId));
            assertEquals(0, scopedCount("agent_vetoes", taskId));
            assertEquals(0, scopedCount("agent_decisions", taskId));
            REQUESTS.remove(taskId);
        }
        if (fixtureInserted) {
            jdbc.update("DELETE FROM daily_bars WHERE symbol = ?", SYMBOL);
            jdbc.update("DELETE FROM securities WHERE symbol = ? AND data_source = ?", SYMBOL, DATA_SOURCE);
        }
        if (agentBaseline != null) {
            assertEquals(agentBaseline, agentCounts());
            assertEquals(securitiesBaseline, tableCount("securities"));
            assertEquals(dailyBarsBaseline, tableCount("daily_bars"));
        }
    }

    @AfterAll
    static void stopStub() {
        STUB_SERVER.stop(0);
    }

    @Test
    void freezesReadonlyPostgresContextThroughRealTaskCreationFlow() {
        assertDedicatedDatabaseAndFlywayV5();
        agentBaseline = agentCounts();
        securitiesBaseline = tableCount("securities");
        dailyBarsBaseline = tableCount("daily_bars");
        assertEquals(0, tableCount("securities", SYMBOL));
        assertEquals(0, tableCount("daily_bars", SYMBOL));
        insertFixture();
        fixtureInserted = true;

        int securitiesAfterFixture = tableCount("securities");
        int barsAfterFixture = tableCount("daily_bars");
        JsonNode directSnapshot = snapshotService.create(SYMBOL, TRADE_DATE).value();
        assertEquals(securitiesAfterFixture, tableCount("securities"));
        assertEquals(barsAfterFixture, tableCount("daily_bars"));
        assertReadonlySections(directSnapshot);

        CreatedTask created = taskService.create(new CreateAgentTaskRequest(
                SYMBOL, TRADE_DATE, ExecutionMode.LOCAL_RULES,
                "stage-2a-readonly-context", true, TriggerType.MANUAL
        ), "stage-2a-integration-test");
        taskId = created.task().id();
        await(() -> REQUESTS.containsKey(taskId), "Python stub did not receive the task request");
        await(() -> Set.of("PARTIAL", "FAILED").contains(taskStatus()),
                "Agent task did not reach a terminal status");
        assertEquals("PARTIAL", taskStatus());

        JsonNode persisted = readJson(jdbc.queryForObject(
                "SELECT context_snapshot_json::text FROM agent_tasks WHERE id = ?", String.class, taskId));
        JsonNode request = REQUESTS.get(taskId);
        assertNotNull(request);
        assertEquals(persisted, request.path("contextSnapshot"));
        assertEquals(created.task().contextSnapshot(), persisted);
        String databaseHash = jdbc.queryForObject(
                "SELECT context_hash FROM agent_tasks WHERE id = ?", String.class, taskId);
        String recalculatedHash = hashService.hash(persisted);
        assertEquals(created.task().contextHash(), databaseHash);
        assertEquals(created.task().contextHash(), recalculatedHash);
        assertEquals(created.task().contextHash(), request.path("contextHash").asText());
        System.out.println("STAGE_2A_MEMORY_HASH=" + created.task().contextHash());
        System.out.println("STAGE_2A_DATABASE_HASH=" + databaseHash);
        System.out.println("STAGE_2A_JSONB_RECALCULATED_HASH=" + recalculatedHash);
        assertReadonlySections(persisted);

        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT id, agent_code FROM agent_runs WHERE task_id = ? ORDER BY id", taskId);
        assertEquals(6, runs.size());
        Set<String> agentCodes = new HashSet<>();
        runs.forEach(run -> agentCodes.add((String) run.get("agent_code")));
        assertEquals(Set.of(
                "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
                "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"), agentCodes);
        assertFalse(agentCodes.contains("CHIEF_DECISION"));
        assertEquals(securitiesAfterFixture, tableCount("securities"));
        assertEquals(barsAfterFixture, tableCount("daily_bars"));
    }

    private void assertReadonlySections(JsonNode snapshot) {
        assertTrue(snapshot.path("security").path("available").asBoolean());
        assertEquals(SYMBOL, snapshot.path("security").path("symbol").asText());
        assertEquals(DATA_SOURCE, snapshot.path("security").path("dataSource").asText());
        assertFalse(snapshot.path("security").path("qualityFacts")
                .path("pointInTimeGuaranteed").asBoolean());

        JsonNode marketData = snapshot.path("marketData");
        assertTrue(marketData.path("available").asBoolean());
        assertEquals("QFQ", marketData.path("adjustType").asText());
        assertEquals(61, marketData.path("actualBars").asInt());
        assertTrue(marketData.path("exactTradeDateMatch").asBoolean());
        assertEquals(TRADE_DATE.toString(), marketData.path("effectiveTradeDate").asText());
        assertEquals(TRADE_DATE.minusDays(60).toString(),
                marketData.path("bars").get(0).path("tradeDate").asText());
        assertEquals(TRADE_DATE.toString(), marketData.path("bars").get(60).path("tradeDate").asText());
        assertTrue(marketData.path("bars").get(0).path("amount").isNull());
        assertTrue(marketData.path("bars").get(0).path("turnoverRate").isNull());

        JsonNode technical = snapshot.path("technicalMetrics");
        assertTrue(technical.path("available").asBoolean());
        assertEquals(61, technical.path("requiredBars").asInt());
        assertEquals("JAVA_INDICATORS_V1", technical.path("formulaVersion").asText());
        assertTrue(technical.path("values").has("ma60"));
        assertFalse(technical.path("values").has("ema"));
        assertFalse(technical.path("values").has("macd"));
        assertFalse(technical.path("values").has("boll"));

        JsonNode quality = snapshot.path("dataQualityContext");
        assertTrue(quality.path("available").asBoolean());
        assertEquals(61, quality.path("facts").path("loadedBarCount").asInt());
        assertEquals(1, quality.path("facts").path("missingAmountCount").asInt());
        assertEquals(1, quality.path("facts").path("missingTurnoverRateCount").asInt());
        assertEquals(List.of("BFQ", "HFQ", "QFQ"), STUB_MAPPER.convertValue(
                quality.path("facts").path("adjustTypesObserved"), List.class));
        for (String forbidden : List.of("score", "gateStatus", "decision", "veto")) {
            assertFalse(quality.has(forbidden));
            assertFalse(quality.path("facts").has(forbidden));
        }
        for (String section : List.of(
                "marketBreadth", "scanResult", "backtestContext", "securityEvents", "portfolioContext")) {
            assertFalse(snapshot.path(section).path("available").asBoolean());
            assertEquals("该只读上下文尚未接入现有业务数据源", snapshot.path(section).path("reason").asText());
        }
    }

    private void insertFixture() {
        jdbc.update("""
                INSERT INTO securities (
                    symbol, name, exchange, board, industry, list_date,
                    is_st, is_active, data_source, updated_at
                ) VALUES (?, ?, 'SSE', 'MAIN', ?, DATE '2000-01-01',
                          FALSE, TRUE, ?, TIMESTAMP '2026-07-14 13:00:00')
                """, SYMBOL, "阶段2A测试证券", "测试行业", DATA_SOURCE);
        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < 61; index++) {
            LocalDate date = TRADE_DATE.minusDays(60L - index);
            BigDecimal close = BigDecimal.valueOf(1000L + index, 2);
            batch.add(new Object[]{
                    SYMBOL, date, close, close.add(BigDecimal.ONE),
                    close.subtract(new BigDecimal("0.50")), close.add(new BigDecimal("0.20")),
                    1000L + index, index == 0 ? null : BigDecimal.valueOf(100000L + index),
                    index == 0 ? null : new BigDecimal("1.2500"), "QFQ"
            });
        }
        batch.add(otherAdjustment(TRADE_DATE, "HFQ"));
        batch.add(otherAdjustment(TRADE_DATE, "BFQ"));
        batch.add(otherAdjustment(TRADE_DATE.plusDays(1), "QFQ"));
        jdbc.batchUpdate("""
                INSERT INTO daily_bars (
                    symbol, trade_date, open, high, low, close, volume,
                    amount, turnover_rate, adjust_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private Object[] otherAdjustment(LocalDate date, String adjustType) {
        return new Object[]{
                SYMBOL, date, new BigDecimal("10"), new BigDecimal("11"), new BigDecimal("9"),
                new BigDecimal("10.5"), 1000L, BigDecimal.TEN, BigDecimal.ONE, adjustType
        };
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

    private Map<String, Integer> agentCounts() {
        return Map.of(
                "agent_tasks", tableCount("agent_tasks"),
                "agent_runs", tableCount("agent_runs"),
                "agent_evidence", tableCount("agent_evidence"),
                "agent_vetoes", tableCount("agent_vetoes"),
                "agent_decisions", tableCount("agent_decisions")
        );
    }

    private int tableCount(String table) {
        assertAllowedTable(table);
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private int tableCount(String table, String symbol) {
        if (!Set.of("securities", "daily_bars").contains(table)) {
            throw new IllegalArgumentException("Table is not allowed");
        }
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE symbol = ?", Integer.class, symbol);
        return value == null ? 0 : value;
    }

    private int scopedCount(String table, long id) {
        assertAllowedTable(table);
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
        return value == null ? 0 : value;
    }

    private static void assertAllowedTable(String table) {
        if (!Set.of(
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions",
                "securities", "daily_bars").contains(table)) {
            throw new IllegalArgumentException("Table is not allowed");
        }
    }

    private String taskStatus() {
        List<String> statuses = jdbc.query(
                "SELECT status FROM agent_tasks WHERE id = ?",
                (resultSet, rowNum) -> resultSet.getString(1), taskId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError(message);
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/agents/team/analyze", AgentReadonlyContextPostgresIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        JsonNode request = STUB_MAPPER.readTree(exchange.getRequestBody());
        long requestTaskId = request.path("taskId").asLong();
        REQUESTS.put(requestTaskId, request);
        byte[] response = responseFor(request);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static byte[] responseFor(JsonNode request) throws IOException {
        ObjectNode response = RESPONSE_FIXTURE.deepCopy();
        long requestTaskId = request.path("taskId").asLong();
        response.put("taskId", requestTaskId);
        response.put("contextHash", request.path("contextHash").asText());
        response.put("tradeDate", request.path("tradeDate").asText());
        response.put("ruleVersion", request.path("ruleVersion").asText());
        response.put("executionMode", request.path("executionMode").asText());
        Map<String, String> runFields = Map.of(
                "DATA_QUALITY", "dataQuality", "MARKET_REGIME", "marketRegime",
                "TECHNICAL_ANALYSIS", "technicalAnalysis", "STRATEGY_BACKTEST", "strategyBacktest",
                "ANNOUNCEMENT_RISK", "announcementRisk", "POSITION_RISK", "positionRisk");
        ArrayNode runs = (ArrayNode) response.path("agentRuns");
        for (JsonNode value : runs) {
            ObjectNode run = (ObjectNode) value;
            run.put("taskId", requestTaskId);
            run.put("runId", request.path("runIds")
                    .path(runFields.get(run.path("agentCode").asText())).asLong());
            run.put("contextHash", request.path("contextHash").asText());
            run.put("ruleVersion", request.path("ruleVersion").asText());
            run.put("executionMode", request.path("executionMode").asText());
        }
        ObjectNode decision = (ObjectNode) response.path("finalDecision");
        decision.put("taskId", requestTaskId);
        decision.put("contextHash", request.path("contextHash").asText());
        decision.put("tradeDate", request.path("tradeDate").asText());
        decision.put("ruleVersion", request.path("ruleVersion").asText());
        decision.put("executionMode", request.path("executionMode").asText());
        ArrayNode sourceRunIds = STUB_MAPPER.createArrayNode();
        runs.forEach(run -> sourceRunIds.add(run.path("runId").asLong()));
        decision.set("sourceRunIds", sourceRunIds);
        return STUB_MAPPER.writeValueAsBytes(response);
    }

    private static JsonNode loadResponseFixture() {
        try (InputStream input = AgentReadonlyContextPostgresIntegrationTest.class.getResourceAsStream(
                "/agent-team-contract/valid-agent-team-response.json")) {
            if (input == null) {
                throw new IllegalStateException("Shared response fixture is missing");
            }
            return STUB_MAPPER.readTree(input);
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("Stored JSON could not be parsed", error);
        }
    }

}
