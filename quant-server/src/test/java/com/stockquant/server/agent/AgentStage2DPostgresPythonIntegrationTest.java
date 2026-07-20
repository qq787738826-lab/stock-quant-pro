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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
class AgentStage2DPostgresPythonIntegrationTest {

    private static final String RULE_VERSION = "1.4.0-stage-2d-market-regime-v1";
    private static final String DATA_SOURCE = "TEST_FIXTURE_STAGE_2D1";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> AGENT_CODES = Set.of(
            "DATA_QUALITY", "MARKET_REGIME", "TECHNICAL_ANALYSIS",
            "STRATEGY_BACKTEST", "ANNOUNCEMENT_RISK", "POSITION_RISK"
    );
    private static final Set<String> MARKET_BREADTH_EVIDENCE_FIELDS = Set.of(
            "available", "reasonCode", "sourceType", "sourceTables", "sourceStatus",
            "producer", "producerVersion", "versionAvailable", "requestedTradeDate",
            "effectiveTradeDate", "previousEffectiveTradeDate", "exactTradeDateMatch",
            "pointInTimeGuaranteed", "barFutureDataExcluded", "universePointInTimeGuaranteed",
            "futureDataExcluded", "timestampTimezoneSemantics", "adjustType", "selectionRule",
            "universeCount", "coveredSymbolCount", "comparableSymbolCount", "advancingCount",
            "decliningCount", "unchangedCount", "missingCurrentBarCount",
            "missingPreviousBarCount", "coverageRatio", "limitations"
    );

    @Autowired AgentTaskService taskService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentContextHashService hashService;

    @DynamicPropertySource
    static void safeTestProperties(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
        AgentPythonSmokeEnvironment.registerBaseUrl(registry);
    }

    @Test
    void persistsRestrictedCurrentUniverseBreadthWithoutPartialSideEffects() {
        assertDedicatedDatabase();
        Map<String, Integer> baseline = counts();
        List<BarKey> insertedBars = new ArrayList<>();
        List<String> insertedSecurities = new ArrayList<>();
        long taskId = 0L;
        LocalDate tradeDate = Instant.now().atZone(SHANGHAI).toLocalDate();
        try {
            List<String> symbols = unusedSymbols(2);
            String primary = symbols.get(0);
            insertSecurity(primary, "Stage2D1 primary");
            insertSecurity(symbols.get(1), "Stage2D1 secondary");
            insertedSecurities.addAll(symbols);

            for (int offset = 60; offset >= 0; offset--) {
                LocalDate date = tradeDate.minusDays(offset);
                BigDecimal close = new BigDecimal("10.00")
                        .add(new BigDecimal(60 - offset).movePointLeft(2));
                insertBarIfMissing(primary, date, close, insertedBars);
            }
            insertMissingUniverseBars(tradeDate.minusDays(1), new BigDecimal("10.00"), insertedBars);
            insertMissingUniverseBars(tradeDate, new BigDecimal("11.00"), insertedBars);

            CreatedTask created = taskService.create(new CreateAgentTaskRequest(
                    primary, tradeDate, ExecutionMode.LOCAL_RULES, RULE_VERSION,
                    true, TriggerType.MANUAL
            ), "stage-2d1-real-loop-test");
            taskId = created.task().id();
            long createdTaskId = taskId;

            assertEquals(tradeDate, created.task().createdAt().atZone(SHANGHAI).toLocalDate());
            JsonNode frozen = created.task().contextSnapshot();
            JsonNode breadth = frozen.path("marketBreadth");
            assertTrue(breadth.path("available").asBoolean());
            assertEquals(tradeDate.toString(), breadth.path("effectiveTradeDate").asText());
            assertEquals(breadth.path("universeCount").asInt(), breadth.path("comparableSymbolCount").asInt());
            assertTrue(breadth.path("comparableSymbolCount").asInt() >= 2);
            assertEquals(0, BigDecimal.ONE.compareTo(breadth.path("coverageRatio").decimalValue()));

            await(() -> "PARTIAL".equals(taskStatus(createdTaskId)),
                    "阶段2D-1真实Python任务未进入PARTIAL终态");

            List<Map<String, Object>> runs = jdbc.queryForList("""
                    SELECT id, agent_code, status, gate_status, decision, veto,
                           output_json::text AS output_json
                    FROM agent_runs WHERE task_id=? ORDER BY id
                    """, taskId);
            assertEquals(6, runs.size());
            assertEquals(AGENT_CODES, values(runs, "agent_code"));
            assertFalse(values(runs, "agent_code").contains("CHIEF_DECISION"));

            Map<String, Object> marketRegime = runs.stream()
                    .filter(row -> "MARKET_REGIME".equals(row.get("agent_code")))
                    .findFirst().orElseThrow();
            assertEquals("COMPLETED", marketRegime.get("status"));
            assertEquals("WARN", marketRegime.get("decision"));
            assertEquals(false, marketRegime.get("veto"));
            JsonNode marketOutput = readJson((String) marketRegime.get("output_json"));
            assertEquals(0, marketOutput.path("confidence").asInt());
            assertEquals(2, marketOutput.path("findings").size());
            assertEquals("MARKET_BREADTH_POINT_IN_TIME_UNVERIFIED",
                    marketOutput.path("findings").get(0).path("code").asText());
            assertTrue(Set.of("MARKET_BREADTH_POSITIVE", "MARKET_BREADTH_MIXED",
                            "MARKET_BREADTH_NEGATIVE")
                    .contains(marketOutput.path("findings").get(1).path("code").asText()));
            assertEquals(1, marketOutput.path("evidence").size());

            BigDecimal net = new BigDecimal(breadth.path("advancingCount").asText())
                    .subtract(new BigDecimal(breadth.path("decliningCount").asText()))
                    .divide(new BigDecimal(breadth.path("comparableSymbolCount").asText()),
                            8, RoundingMode.HALF_UP);
            int expectedScore = net.add(BigDecimal.ONE).multiply(new BigDecimal("50"))
                    .setScale(0, RoundingMode.HALF_UP).intValueExact();
            assertEquals(expectedScore, marketOutput.path("score").asInt());

            List<Map<String, Object>> evidenceRows = jdbc.queryForList("""
                    SELECT evidence_key, category, source_type, source_name, source_ref,
                           content_hash, payload_json::text AS payload_json
                    FROM agent_evidence WHERE task_id=? ORDER BY id
                    """, taskId);
            assertEquals(2, evidenceRows.size());
            assertEquals("DATA_QUALITY", evidenceRows.get(0).get("category"));
            assertEquals("MARKET_BREADTH", evidenceRows.get(1).get("category"));
            assertEquals("mr-breadth-" + created.task().contextHash(),
                    evidenceRows.get(1).get("evidence_key"));
            assertEquals("JAVA_ENGINE", evidenceRows.get(1).get("source_type"));
            assertEquals("AgentMarketBreadthContextService", evidenceRows.get(1).get("source_name"));
            assertEquals("contextSnapshot.marketBreadth", evidenceRows.get(1).get("source_ref"));
            assertEquals(created.task().contextHash(), evidenceRows.get(1).get("content_hash"));
            JsonNode marketFields = readJson((String) evidenceRows.get(1).get("payload_json"));
            assertEquals(Set.of("marketBreadth"), fieldNames(marketFields));
            assertEquals(MARKET_BREADTH_EVIDENCE_FIELDS, fieldNames(marketFields.path("marketBreadth")));
            String serializedFields = marketFields.toString();
            for (String forbidden : List.of("scanResult", "marketData", "technicalMetrics",
                    "gateStatus", "decision", "score", "confidence", "finding")) {
                assertFalse(serializedFields.contains("\"" + forbidden + "\""));
            }

            Map<String, Object> decision = jdbc.queryForMap("""
                    SELECT decision, gate_status, vetoed, score, confidence, source_run_ids,
                           decision_json::text AS decision_json
                    FROM agent_decisions WHERE task_id=?
                    """, taskId);
            assertEquals("INSUFFICIENT_DATA", decision.get("decision"));
            assertEquals(false, decision.get("vetoed"));
            assertEquals(0, ((Number) decision.get("score")).intValue());
            assertEquals(0, ((Number) decision.get("confidence")).intValue());
            List<Long> runIds = runIds(runs);
            List<Long> sourceRunIds = jdbc.queryForList(
                    "SELECT unnest(source_run_ids) FROM agent_decisions WHERE task_id=?",
                    Long.class, taskId);
            assertEquals(runIds, sourceRunIds);
            assertEquals(0, countForTask("agent_vetoes", taskId));

            String persistedJson = jdbc.queryForObject(
                    "SELECT context_snapshot_json::text FROM agent_tasks WHERE id=?",
                    String.class, taskId);
            JsonNode persisted = readJson(persistedJson);
            AgentStage2CReadonlyContextPostgresIntegrationTest.assertJsonSemanticallyEquals(frozen, persisted);
            assertEquals(created.task().contextHash(), hashService.hash(persisted));
            assertEquals(created.task().contextHash(), jdbc.queryForObject(
                    "SELECT context_hash FROM agent_tasks WHERE id=?", String.class, taskId));
        } finally {
            if (taskId > 0) {
                jdbc.update("DELETE FROM agent_tasks WHERE id=?", taskId);
                for (String table : List.of("agent_tasks", "agent_runs", "agent_evidence",
                        "agent_vetoes", "agent_decisions")) {
                    assertEquals(0, countForTask(table, taskId));
                }
            }
            if (!insertedBars.isEmpty()) {
                jdbc.batchUpdate("DELETE FROM daily_bars WHERE symbol=? AND trade_date=? AND adjust_type='QFQ'",
                        insertedBars.stream().map(key -> new Object[]{key.symbol(), key.tradeDate()}).toList());
            }
            for (String symbol : insertedSecurities) {
                jdbc.update("DELETE FROM securities WHERE symbol=? AND data_source=?", symbol, DATA_SOURCE);
            }
            assertEquals(baseline, counts());
        }
    }

    private void assertDedicatedDatabase() {
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
    }

    private List<String> unusedSymbols(int count) {
        List<String> result = new ArrayList<>();
        for (int value = 690000; value <= 699999 && result.size() < count; value++) {
            String symbol = String.valueOf(value);
            Integer existing = jdbc.queryForObject(
                    "SELECT count(*) FROM securities WHERE symbol=?", Integer.class, symbol);
            if (existing != null && existing == 0) result.add(symbol);
        }
        if (result.size() != count) throw new AssertionError("没有足够的阶段2D-1测试证券代码");
        return result;
    }

    private void insertSecurity(String symbol, String name) {
        jdbc.update("""
                INSERT INTO securities(symbol,name,exchange,board,industry,list_date,
                    is_st,is_active,data_source,updated_at)
                VALUES (?,?,'SSE','MAIN','TEST',DATE '2000-01-01',false,true,?,CURRENT_TIMESTAMP)
                """, symbol, name, DATA_SOURCE);
    }

    private void insertBarIfMissing(String symbol, LocalDate date, BigDecimal close,
                                    List<BarKey> insertedBars) {
        Integer existing = jdbc.queryForObject("""
                SELECT count(*) FROM daily_bars
                WHERE symbol=? AND trade_date=? AND adjust_type='QFQ'
                """, Integer.class, symbol, date);
        if (existing != null && existing > 0) return;
        jdbc.update("""
                INSERT INTO daily_bars(symbol,trade_date,open,high,low,close,volume,
                    amount,turnover_rate,adjust_type)
                VALUES (?,?,?,?,?,?,?,?,?,'QFQ')
                """, symbol, date, close, close.add(BigDecimal.ONE),
                close.subtract(new BigDecimal("0.50")), close,
                1000L, new BigDecimal("10000.00"), new BigDecimal("1.0000"));
        insertedBars.add(new BarKey(symbol, date));
    }

    private void insertMissingUniverseBars(LocalDate date, BigDecimal close,
                                           List<BarKey> insertedBars) {
        List<String> inserted = jdbc.query("""
                INSERT INTO daily_bars(symbol,trade_date,open,high,low,close,volume,
                    amount,turnover_rate,adjust_type)
                SELECT s.symbol, ?, ?, ?, ?, ?, 1000, 10000.00, 1.0000, 'QFQ'
                FROM securities s
                WHERE s.board='MAIN' AND s.is_active=true AND s.is_st=false
                  AND NOT EXISTS (
                    SELECT 1 FROM daily_bars b WHERE b.symbol=s.symbol
                      AND b.trade_date=? AND b.adjust_type='QFQ'
                  )
                RETURNING symbol
                """, (rs, rowNum) -> rs.getString(1), date, close,
                close.add(BigDecimal.ONE), close.subtract(new BigDecimal("0.50")), close, date);
        inserted.forEach(symbol -> insertedBars.add(new BarKey(symbol, date)));
    }

    private String taskStatus(long taskId) {
        List<String> values = jdbc.query(
                "SELECT status FROM agent_tasks WHERE id=?",
                (rs, rowNum) -> rs.getString(1), taskId);
        return values.isEmpty() ? null : values.get(0);
    }

    private int countForTask(String table, long taskId) {
        if (!Set.of("agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes",
                "agent_decisions").contains(table)) {
            throw new IllegalArgumentException("不允许查询该表");
        }
        String column = "agent_tasks".equals(table) ? "id" : "task_id";
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, taskId);
        return value == null ? 0 : value;
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String table : List.of("agent_tasks", "agent_runs", "agent_evidence",
                "agent_vetoes", "agent_decisions", "securities", "daily_bars")) {
            result.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        }
        return result;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception error) {
            throw new AssertionError("阶段2D-1持久化JSON无法解析", error);
        }
    }

    private static Set<String> values(List<Map<String, Object>> rows, String column) {
        Set<String> result = new HashSet<>();
        rows.forEach(row -> result.add(String.valueOf(row.get(column))));
        return result;
    }

    private static List<Long> runIds(List<Map<String, Object>> runs) {
        return runs.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void await(BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError(failureMessage);
    }

    private record BarKey(String symbol, LocalDate tradeDate) {}
}
