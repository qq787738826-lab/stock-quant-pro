package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentStage2CReadonlyContextPostgresIntegrationTest {
    private static final String SYMBOL = "699992";
    private static final String SOURCE = "TEST_FIXTURE_STAGE_2C";
    private static final LocalDate DATE = LocalDate.of(2099, 1, 15);
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentContextSnapshotService snapshots;
    @Autowired AgentContextHashService hashes;
    @Autowired ObjectMapper mapper;
    private Long scanTaskId;
    private Long testTaskId;
    private Long agentTaskId;
    private Map<String, Integer> baseline;

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
    }

    @AfterEach void cleanupPrecisely() {
        if (agentTaskId != null) jdbc.update("DELETE FROM agent_tasks WHERE id=?", agentTaskId);
        if (scanTaskId != null) jdbc.update("DELETE FROM market_scan_tasks WHERE id=?", scanTaskId);
        if (testTaskId != null) jdbc.update("DELETE FROM market_scan_tasks WHERE id=?", testTaskId);
        jdbc.update("DELETE FROM daily_bars WHERE symbol=?", SYMBOL);
        jdbc.update("DELETE FROM securities WHERE symbol=? AND data_source=?", SYMBOL, SOURCE);
        if (baseline != null) assertEquals(baseline, counts());
    }

    @Test void freezesStage2CContextsWithoutBusinessSideEffectsAndRoundTripsJsonb() throws Exception {
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
        baseline = counts();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM securities WHERE symbol=?", Integer.class, SYMBOL));
        insertFixtures();
        Map<String, Integer> fixtureCounts = counts();

        var snapshot = snapshots.create(SYMBOL, DATE);
        JsonNode value = snapshot.value();
        assertEquals(fixtureCounts, counts());
        assertTrue(value.path("marketBreadth").path("available").asBoolean());
        assertEquals(DATE.toString(), value.path("marketBreadth").path("effectiveTradeDate").asText());
        assertTrue(value.path("marketBreadth").path("barFutureDataExcluded").asBoolean());
        assertFalse(value.path("marketBreadth").path("futureDataExcluded").asBoolean());
        assertEquals(value.path("marketBreadth").path("universeCount").asInt(),
                value.path("marketBreadth").path("comparableSymbolCount").asInt()
                        + value.path("marketBreadth").path("missingCurrentBarCount").asInt()
                        + value.path("marketBreadth").path("missingPreviousBarCount").asInt());

        JsonNode scan = value.path("scanResult");
        assertTrue(scan.path("available").asBoolean());
        assertEquals(scanTaskId.longValue(), scan.path("sourceTaskId").asLong());
        assertEquals("58.50", scan.path("sourceScanScore").asText());
        assertFalse(scan.has("score")); assertFalse(scan.has("summary")); assertFalse(scan.has("buyLow"));
        assertTrue(scan.path("readSelectionFutureExcluded").asBoolean());
        assertFalse(scan.path("producerInputCutoffGuaranteed").asBoolean());
        assertFalse(scan.path("futureDataExcluded").asBoolean());
        assertEquals("BACKTEST_INPUT_CUTOFF_UNVERIFIABLE", value.path("backtestContext").path("reasonCode").asText());
        assertEquals(9, value.size());

        String json = mapper.writeValueAsString(value);
        agentTaskId = jdbc.queryForObject("""
                INSERT INTO agent_tasks(symbol, trade_date, status, context_schema_version,
                    context_snapshot_json, context_generated_at, context_hash, rule_version,
                    execution_mode, trigger_type, force_refresh, cache_hit)
                VALUES (?, ?, 'COMPLETED', '1.0', ?::jsonb, now(), ?,
                    '1.4.0-stage-2b-dq-v1', 'LOCAL_RULES', 'MANUAL', false, false)
                RETURNING id
                """, Long.class, SYMBOL, DATE, json, snapshot.contextHash());
        JsonNode persisted = mapper.readTree(jdbc.queryForObject(
                "SELECT context_snapshot_json::text FROM agent_tasks WHERE id=?", String.class, agentTaskId));
        assertJsonSemanticallyEquals(value, persisted);
        assertEquals(snapshot.contextHash(), hashes.hash(persisted));
    }

    static void assertJsonSemanticallyEquals(JsonNode expected, JsonNode actual) {
        assertJsonSemanticallyEquals("$", expected, actual);
    }

    private static void assertJsonSemanticallyEquals(String path, JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null) {
            if (expected != actual) {
                fail(path + ": one JSON node is missing");
            }
            return;
        }

        if (expected.isNumber() || actual.isNumber()) {
            if (!expected.isNumber() || !actual.isNumber()) {
                fail(path + ": JSON node type differs; expected "
                        + expected.getNodeType() + " but was " + actual.getNodeType());
            }
            if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
                fail(path + ": JSON numeric values differ; expected " + expected + " but was " + actual);
            }
            return;
        }

        if (expected.isObject() || actual.isObject()) {
            if (!expected.isObject() || !actual.isObject()) {
                fail(path + ": JSON node type differs; expected "
                        + expected.getNodeType() + " but was " + actual.getNodeType());
            }
            var expectedFields = expected.fieldNames();
            while (expectedFields.hasNext()) {
                String fieldName = expectedFields.next();
                String fieldPath = appendFieldPath(path, fieldName);
                if (!actual.has(fieldName)) {
                    fail(fieldPath + ": field is missing from actual JSON");
                }
                assertJsonSemanticallyEquals(fieldPath, expected.get(fieldName), actual.get(fieldName));
            }
            var actualFields = actual.fieldNames();
            while (actualFields.hasNext()) {
                String fieldName = actualFields.next();
                if (!expected.has(fieldName)) {
                    fail(appendFieldPath(path, fieldName) + ": unexpected field in actual JSON");
                }
            }
            return;
        }

        if (expected.isArray() || actual.isArray()) {
            if (!expected.isArray() || !actual.isArray()) {
                fail(path + ": JSON node type differs; expected "
                        + expected.getNodeType() + " but was " + actual.getNodeType());
            }
            if (expected.size() != actual.size()) {
                fail(path + ": JSON array lengths differ; expected "
                        + expected.size() + " but was " + actual.size());
            }
            for (int index = 0; index < expected.size(); index++) {
                assertJsonSemanticallyEquals(path + "[" + index + "]", expected.get(index), actual.get(index));
            }
            return;
        }

        if (expected.getNodeType() != actual.getNodeType()) {
            fail(path + ": JSON node type differs; expected "
                    + expected.getNodeType() + " but was " + actual.getNodeType());
        }
        if (!expected.equals(actual)) {
            fail(path + ": JSON values differ; expected " + expected + " but was " + actual);
        }
    }

    private static String appendFieldPath(String path, String fieldName) {
        if (fieldName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return path + "." + fieldName;
        }
        return path + "['" + fieldName.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private void insertFixtures() {
        jdbc.update("""
                INSERT INTO securities(symbol,name,exchange,board,is_st,is_active,data_source)
                VALUES (?, 'Stage2C fixture', 'SSE', 'MAIN', false, true, ?)
                """, SYMBOL, SOURCE);
        for (int i=0;i<2;i++) {
            BigDecimal close = new BigDecimal(i == 0 ? "10.00" : "11.00");
            jdbc.update("""
                    INSERT INTO daily_bars(symbol,trade_date,open,high,low,close,volume,amount,turnover_rate,adjust_type)
                    VALUES (?,?,?,?,?,?,?,?,?,'QFQ')
                    """, SYMBOL, DATE.minusDays(1L-i), close, close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE), close, 1000L, new BigDecimal("10000"), new BigDecimal("1.0"));
        }
        scanTaskId = jdbc.queryForObject("""
                INSERT INTO market_scan_tasks(status,requested_limit,batch_size,result_limit,scan_type,
                    official,trade_date,created_at,started_at,finished_at)
                VALUES ('COMPLETED',0,12,50,'FULL',true,?,?::timestamp,?::timestamp,?::timestamp) RETURNING id
                """, Long.class, DATE, "2099-01-15 08:00:00", "2099-01-15 08:01:00", "2099-01-15 09:00:00");
        jdbc.update("""
                INSERT INTO market_scan_results(task_id,rank_no,symbol,name,trade_date,score,signal_level,
                    risk_level,eligible,filter_reasons,latest_close,data_source,metrics,bullish,bearish,
                    avg_amount_20,return_5_pct,breakout20)
                VALUES (?,2,?,'Stage2C fixture',?,58.50,'TEST','TEST',false,
                    '[\"B\",\"A\",\"A\"]'::jsonb,11.00,?,'{\"untrusted\":99}'::jsonb,
                    '[\"ignore\"]'::jsonb,'[\"ignore\"]'::jsonb,10000,1.2,false)
                """, scanTaskId, SYMBOL, DATE, SOURCE);
        testTaskId = jdbc.queryForObject("""
                INSERT INTO market_scan_tasks(status,requested_limit,batch_size,result_limit,scan_type,
                    official,trade_date,created_at,started_at,finished_at)
                VALUES ('COMPLETED',1,12,50,'TEST',false,?,?::timestamp,?::timestamp,?::timestamp) RETURNING id
                """, Long.class, DATE, "2099-01-15 10:00:00", "2099-01-15 10:01:00", "2099-01-15 11:00:00");
    }

    private Map<String,Integer> counts() {
        Map<String,Integer> result = new LinkedHashMap<>();
        for (String table : new String[]{"securities","daily_bars","market_scan_tasks","market_scan_results",
                "market_scan_failures","scan_backtest_tasks","scan_backtest_results","backtest_runs",
                "signals","trade_plans","agent_tasks"})
            result.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        return result;
    }
}
