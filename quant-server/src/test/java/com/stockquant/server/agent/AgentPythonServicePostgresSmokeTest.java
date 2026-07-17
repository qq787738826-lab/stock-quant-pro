package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentPythonServicePostgresSmokeTest {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(15);
    private static final String RULE_VERSION = "1.4.0-stage-2b-dq-v1";
    private static final String DATA_SOURCE = "TEST_FIXTURE_STAGE_2B";
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    private static final Set<String> AGENT_CODES = Set.of(
            "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
            "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"
    );

    @Autowired
    private AgentTaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentContextHashService hashService;

    @DynamicPropertySource
    static void safeTestProperties(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
        AgentPythonSmokeEnvironment.registerBaseUrl(registry);
    }

    @Test
    void completesJavaPostgresRealPythonAgentTeamLoop() {
        assertDatabaseIdentityAndFlyway();
        Map<String, Integer> agentBaseline = agentCounts();
        int securitiesBaseline = tableCount("securities");
        int dailyBarsBaseline = tableCount("daily_bars");
        String symbol = uniqueSymbol();
        long taskId = 0;
        try {
            assertEquals(0, symbolCount("securities", symbol));
            assertEquals(0, symbolCount("daily_bars", symbol));
            insertFixture(symbol);
            assertEquals(securitiesBaseline + 1, tableCount("securities"));
            assertEquals(dailyBarsBaseline + 61, tableCount("daily_bars"));

            CreatedTask created = taskService.create(new CreateAgentTaskRequest(
                    symbol,
                    TRADE_DATE,
                    ExecutionMode.LOCAL_RULES,
                    RULE_VERSION,
                    true,
                    TriggerType.MANUAL
            ), "agent-python-smoke-test");
            taskId = created.task().id();
            long createdTaskId = taskId;

            await(() -> "PARTIAL".equals(taskStatus(createdTaskId)),
                    "真实Python智能体任务未进入PARTIAL终态");
            assertEquals(1, count("agent_tasks", taskId));

            List<Map<String, Object>> runs = jdbc.queryForList("""
                    SELECT id, agent_code, status, gate_status, decision, veto,
                           output_json::text AS output_json
                    FROM agent_runs WHERE task_id = ? ORDER BY id
                    """, taskId);
            assertEquals(6, runs.size());
            assertEquals(AGENT_CODES, values(runs, "agent_code"));
            assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));
            assertEquals(Set.of("COMPLETED", "INSUFFICIENT_DATA"), values(runs, "status"));
            assertEquals(6, new HashSet<>(runIds(runs)).size());

            for (Map<String, Object> run : runs) {
                String code = (String) run.get("agent_code");
                assertEquals(false, run.get("veto"));
                if ("DATA_QUALITY".equals(code)) {
                    assertEquals("COMPLETED", run.get("status"));
                    assertEquals("WARN", run.get("gate_status"));
                    assertEquals("WARN", run.get("decision"));
                } else {
                    assertEquals("INSUFFICIENT_DATA", run.get("status"));
                    assertEquals("NOT_APPLICABLE", run.get("gate_status"));
                    assertEquals("NOT_APPLICABLE", run.get("decision"));
                }
                JsonNode output = readJson((String) run.get("output_json"));
                assertTrue(output.isObject());
                assertEquals(taskId, output.path("taskId").asLong());
                assertEquals(((Number) run.get("id")).longValue(), output.path("runId").asLong());
                assertEquals(code, output.path("agentCode").asText());
                assertEquals(created.task().contextHash(), output.path("contextHash").asText());
                assertEquals(RULE_VERSION, output.path("ruleVersion").asText());
                assertEquals("LOCAL_RULES", output.path("executionMode").asText());
                assertNotNull(Instant.parse(output.path("generatedAt").asText()));
                if ("DATA_QUALITY".equals(code)) {
                    assertEquals(50, output.path("score").asInt());
                    assertEquals(100, output.path("confidence").asInt());
                    assertEquals(1, output.path("evidence").size());
                    assertFalse(output.path("veto").asBoolean());
                    assertTrue(output.path("findings").size() >= 1);
                    for (JsonNode finding : output.path("findings")) {
                        assertEquals(List.of("dq-context-" + created.task().contextHash()),
                                objectMapper.convertValue(finding.path("evidenceIds"), List.class));
                    }
                } else {
                    assertFalse(output.path("summary").asText().contains("因数据质量门禁阻断"));
                }
            }

            assertEquals(1, count("agent_evidence", taskId));
            Map<String, Object> evidence = jdbc.queryForMap("""
                    SELECT evidence_key, category, source_type, source_name, source_ref,
                           symbol, trade_date, content_hash, payload_json::text AS payload_json
                    FROM agent_evidence WHERE task_id = ?
                    """, taskId);
            assertEquals("dq-context-" + created.task().contextHash(), evidence.get("evidence_key"));
            assertEquals("DATA_QUALITY", evidence.get("category"));
            assertEquals("JAVA_ENGINE", evidence.get("source_type"));
            assertEquals("AgentContextSnapshotService", evidence.get("source_name"));
            assertEquals("contextSnapshot", evidence.get("source_ref"));
            assertEquals(created.task().symbol(), evidence.get("symbol"));
            assertEquals(created.task().tradeDate(),
                    ((java.sql.Date) evidence.get("trade_date")).toLocalDate());
            assertEquals(created.task().contextHash(), evidence.get("content_hash"));
            JsonNode evidenceFields = readJson((String) evidence.get("payload_json"));
            assertEquals(4, evidenceFields.size());
            assertTrue(evidenceFields.has("security"));
            assertTrue(evidenceFields.has("marketData"));
            assertTrue(evidenceFields.has("technicalMetrics"));
            assertTrue(evidenceFields.has("dataQualityContext"));
            assertEquals(created.task().contextSnapshot().path("security"),
                    evidenceFields.path("security"));
            assertEquals(created.task().contextSnapshot().path("marketData"),
                    evidenceFields.path("marketData"));
            assertEquals(created.task().contextSnapshot().path("technicalMetrics"),
                    evidenceFields.path("technicalMetrics"));
            assertEquals(created.task().contextSnapshot().path("dataQualityContext"),
                    evidenceFields.path("dataQualityContext"));
            assertEquals(created.task().contextHash(), hashService.hash(created.task().contextSnapshot()));
            assertFalse(evidenceFields.toString().contains("\"gateStatus\""));
            assertFalse(evidenceFields.toString().contains("\"decision\""));
            assertFalse(evidenceFields.toString().contains("\"score\""));
            assertFalse(evidenceFields.toString().contains("\"findings\""));
            assertEquals(0, count("agent_vetoes", taskId));
            assertEquals(1, count("agent_decisions", taskId));
            Map<String, Object> decision = jdbc.queryForMap("""
                    SELECT decision, gate_status, vetoed, score, confidence, source_run_ids,
                           generated_at, decision_json::text AS decision_json
                    FROM agent_decisions WHERE task_id = ?
                    """, taskId);
            assertEquals("INSUFFICIENT_DATA", decision.get("decision"));
            assertEquals("WARN", decision.get("gate_status"));
            assertEquals(false, decision.get("vetoed"));
            assertEquals(0, ((Number) decision.get("score")).intValue());
            assertEquals(0, ((Number) decision.get("confidence")).intValue());
            assertNotNull(decision.get("generated_at"));
            List<Long> sourceRunIds = jdbc.queryForList(
                    "SELECT unnest(source_run_ids) FROM agent_decisions WHERE task_id = ?",
                    Long.class, taskId);
            assertEquals(6, sourceRunIds.size());
            assertEquals(new HashSet<>(runIds(runs)), new HashSet<>(sourceRunIds));
            JsonNode decisionJson = readJson((String) decision.get("decision_json"));
            assertEquals(taskId, decisionJson.path("taskId").asLong());
            assertEquals("2026-07-14", decisionJson.path("tradeDate").asText());
            assertEquals(RULE_VERSION, decisionJson.path("ruleVersion").asText());
            assertEquals("LOCAL_RULES", decisionJson.path("executionMode").asText());
            assertEquals(sourceRunIds.size(), decisionJson.path("sourceRunIds").size());
            assertEquals("INSUFFICIENT_DATA", decisionJson.path("decision").asText());
            assertEquals("WARN", decisionJson.path("gateStatus").asText());
            assertEquals(0, decisionJson.path("score").asInt());
            assertEquals(0, decisionJson.path("confidence").asInt());
            assertFalse(decisionJson.path("summary").asText().contains("数据质量门禁阻断"));
            assertNotNull(Instant.parse(decisionJson.path("generatedAt").asText()));
            assertEquals("PARTIAL", taskStatus(taskId));
            assertTrue(runs.stream().allMatch(run -> run.get("output_json") != null));
        } finally {
            if (taskId > 0) {
                jdbc.update("DELETE FROM agent_tasks WHERE id = ?", taskId);
                assertEquals(0, count("agent_tasks", taskId));
                assertEquals(0, count("agent_runs", taskId));
                assertEquals(0, count("agent_evidence", taskId));
                assertEquals(0, count("agent_vetoes", taskId));
                assertEquals(0, count("agent_decisions", taskId));
            }
            jdbc.update("DELETE FROM daily_bars WHERE symbol = ? AND adjust_type = 'QFQ'", symbol);
            jdbc.update("DELETE FROM securities WHERE symbol = ? AND data_source = ?", symbol, DATA_SOURCE);
            assertEquals(0, symbolCount("securities", symbol));
            assertEquals(0, symbolCount("daily_bars", symbol));
            assertEquals(agentBaseline, agentCounts());
            assertEquals(securitiesBaseline, tableCount("securities"));
            assertEquals(dailyBarsBaseline, tableCount("daily_bars"));
        }
    }

    private void insertFixture(String symbol) {
        jdbc.update("""
                INSERT INTO securities (
                    symbol, name, exchange, board, industry, list_date,
                    is_st, is_active, data_source, updated_at
                ) VALUES (?, ?, 'SSE', 'MAIN', ?, DATE '2000-01-01',
                          FALSE, TRUE, ?, TIMESTAMP '2026-07-14 13:00:00')
                """, symbol, "阶段2B闭环测试证券", "测试行业", DATA_SOURCE);
        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < 61; index++) {
            LocalDate date = TRADE_DATE.minusDays(60L - index);
            BigDecimal close = BigDecimal.valueOf(1000L + index, 2);
            batch.add(new Object[]{
                    symbol, date, close, close.add(BigDecimal.ONE),
                    close.subtract(new BigDecimal("0.50")), close.add(new BigDecimal("0.20")),
                    1000L + index, BigDecimal.valueOf(100000L + index),
                    new BigDecimal("1.2500"), "QFQ"
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO daily_bars (
                    symbol, trade_date, open, high, low, close, volume,
                    amount, turnover_rate, adjust_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private void assertDatabaseIdentityAndFlyway() {
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

    private int count(String table, long taskId) {
        Set<String> allowed = Set.of(
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions");
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, taskId);
        return value == null ? 0 : value;
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
        if (!Set.of(
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions",
                "securities", "daily_bars").contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
        Integer value = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private int symbolCount(String table, String symbol) {
        if (!Set.of("securities", "daily_bars").contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE symbol = ?", Integer.class, symbol);
        return value == null ? 0 : value;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new AssertionError("数据库中的Python响应JSON无法解析", error);
        }
    }

    private static Set<String> values(List<Map<String, Object>> rows, String column) {
        Set<String> result = new HashSet<>();
        rows.forEach(row -> result.add(String.valueOf(row.get(column))));
        return result;
    }

    private static List<Long> runIds(List<Map<String, Object>> runs) {
        return runs.stream().map(run -> ((Number) run.get("id")).longValue()).toList();
    }

    private static String uniqueSymbol() {
        return String.valueOf(830000 + Math.floorMod(System.nanoTime(), 60000));
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
}
