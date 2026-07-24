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
import com.stockquant.server.agent.portfolio.PortfolioContracts;
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
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
class AgentStage2HPostgresPythonIntegrationTest {

    private static final String NO_RISK_SYMBOL = "600201";
    private static final String NO_RISK_COMPANION = "600211";
    private static final String SINGLE_VETO_SYMBOL = "600202";
    private static final String SINGLE_VETO_COMPANION = "600212";
    private static final String MULTI_VETO_SYMBOL = "600203";
    private static final String MULTI_VETO_PENDING_SYMBOL = "600204";
    private static final String MULTI_VETO_COMPANION = "600213";
    private static final String INVALID_SYMBOL = "600205";
    private static final String INVALID_COMPANION = "600215";
    private static final String SOURCE = "TEST_FIXTURE_STAGE_2H";
    private static final String SCHEMA_PREFIX = "stage_2h_team_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
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
    private static final List<String> BUSINESS_TABLES = List.of(
            "portfolio_accounts",
            "positions",
            "manual_orders",
            "simulated_trades",
            "account_equity_snapshots",
            "risk_events");

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
    void persistsNoRiskSingleVetoAndMultiVetoWithoutBusinessSideEffects()
            throws Exception {
        assertIsolatedDatabaseAndMigrations();
        LocalDate analysisDate = LocalDate.now(PortfolioContracts.MARKET_ZONE);

        prepareMarketData(
                analysisDate,
                NO_RISK_SYMBOL,
                NO_RISK_COMPANION,
                SINGLE_VETO_SYMBOL,
                SINGLE_VETO_COMPANION,
                MULTI_VETO_SYMBOL,
                MULTI_VETO_PENDING_SYMBOL,
                MULTI_VETO_COMPANION);

        configureNoRiskScenario(NO_RISK_SYMBOL, analysisDate);
        CreatedTask noRisk = executeAndAssertBusinessTablesUnchanged(
                NO_RISK_SYMBOL, analysisDate, "stage-2h-no-risk");
        assertNoRiskPersistence(noRisk);

        configureSingleVetoScenario(SINGLE_VETO_SYMBOL, analysisDate);
        CreatedTask singleVeto = executeAndAssertBusinessTablesUnchanged(
                SINGLE_VETO_SYMBOL, analysisDate, "stage-2h-single-veto");
        assertSingleVetoPersistence(singleVeto);

        configureMultiVetoScenario(
                MULTI_VETO_SYMBOL, MULTI_VETO_PENDING_SYMBOL, analysisDate);
        CreatedTask multiVeto = executeAndAssertBusinessTablesUnchanged(
                MULTI_VETO_SYMBOL, analysisDate, "stage-2h-multi-veto");
        assertMultiVetoPersistence(multiVeto);

        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void rejectsTamperedPositionRiskResponseAtomicallyAndKeepsBusinessTablesReadOnly()
            throws Exception {
        assertIsolatedDatabaseAndMigrations();
        LocalDate analysisDate = LocalDate.now(PortfolioContracts.MARKET_ZONE);
        prepareMarketData(analysisDate, INVALID_SYMBOL, INVALID_COMPANION);
        configureNoRiskScenario(INVALID_SYMBOL, analysisDate);
        Map<String, List<String>> before = businessTableRows();

        CreatedTask created = taskService.create(
                request(INVALID_SYMBOL, analysisDate),
                "stage-2h-invalid-response-atomic");
        long taskId = created.task().id();
        assertTrue(created.newlyCreated());
        await(
                () -> "FAILED".equals(taskStatus(taskId)),
                "tampered stage 2H response did not reach FAILED");

        assertEquals(1, calls(taskId));
        JsonNode forwarded = FORWARDED_RESPONSES.get(taskId);
        assertNotNull(forwarded);
        assertEquals(6, forwarded.path("agentRuns").size());
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
        assertEquals(before, businessTableRows());
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private CreatedTask executeAndAssertBusinessTablesUnchanged(
            String symbol,
            LocalDate analysisDate,
            String requestedBy
    ) {
        Map<String, List<String>> before = businessTableRows();
        CreatedTask created = taskService.create(
                request(symbol, analysisDate),
                requestedBy);
        assertTrue(created.newlyCreated());
        long taskId = created.task().id();
        await(
                () -> Set.of("PARTIAL", "FAILED").contains(taskStatus(taskId)),
                "stage 2H task did not reach a terminal state");
        assertEquals(
                "PARTIAL",
                taskStatus(taskId),
                () -> "portfolioContext="
                        + created.task().contextSnapshot().path("portfolioContext")
                        + ", forwardedPositionRun="
                        + forwardedPositionRun(taskId));
        assertEquals(1, calls(taskId));
        assertEquals(before, businessTableRows());
        assertTrue(created.task().contextSnapshot()
                .path("portfolioContext").path("available").asBoolean());
        assertEquals(
                PortfolioContracts.CONTEXT_PROFILE,
                created.task().contextSnapshot()
                        .path("portfolioContext").path("contextProfile").asText());
        return created;
    }

    private static JsonNode forwardedPositionRun(long taskId) {
        JsonNode response = FORWARDED_RESPONSES.get(taskId);
        if (response == null) return PROXY_MAPPER.nullNode();
        for (JsonNode run : response.path("agentRuns")) {
            if ("POSITION_RISK".equals(run.path("agentCode").asText())) {
                return run;
            }
        }
        return PROXY_MAPPER.nullNode();
    }

    private void assertNoRiskPersistence(CreatedTask created) throws Exception {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        Map<String, Object> position = run(runs, "POSITION_RISK");
        assertEquals("COMPLETED", position.get("status"));
        assertEquals("PASS", position.get("gate_status"));
        assertEquals("PASS", position.get("decision"));
        assertEquals(false, position.get("veto"));
        assertEquals(100, ((Number) position.get("score")).intValue());
        assertEquals(100, ((Number) position.get("confidence")).intValue());
        JsonNode positionOutput = readJson((String) position.get("output_json"));
        assertEquals(5, positionOutput.path("findings").size());
        assertEquals(1, positionOutput.path("evidence").size());

        Map<String, Object> decision = decision(taskId);
        assertEquals("INSUFFICIENT_DATA", decision.get("decision"));
        assertEquals(false, decision.get("vetoed"));
        assertEquals(List.of(), sqlLongs((Array) decision.get("veto_ids")));
        assertEquals(0, countForTask("agent_vetoes", taskId));
        assertPersistedContextAndPortfolioEvidence(created, position);
    }

    private void assertSingleVetoPersistence(CreatedTask created) throws Exception {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        Map<String, Object> position = run(runs, "POSITION_RISK");
        assertEquals("COMPLETED", position.get("status"));
        assertEquals("BLOCKED", position.get("gate_status"));
        assertEquals("REJECT", position.get("decision"));
        assertEquals(true, position.get("veto"));
        assertEquals(0, ((Number) position.get("score")).intValue());

        List<Map<String, Object>> vetoes = vetoes(taskId);
        assertEquals(1, vetoes.size());
        assertEquals(
                "POSITION_RISK_POSITION_WEIGHT_LIMIT_" + SINGLE_VETO_SYMBOL,
                vetoes.get(0).get("veto_code"));
        assertVetoAndDecisionMapping(created, position, vetoes);
        assertPersistedContextAndPortfolioEvidence(created, position);
    }

    private void assertMultiVetoPersistence(CreatedTask created) throws Exception {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        Map<String, Object> position = run(runs, "POSITION_RISK");
        assertEquals("COMPLETED", position.get("status"));
        assertEquals("BLOCKED", position.get("gate_status"));
        assertEquals("REJECT", position.get("decision"));
        assertEquals(true, position.get("veto"));
        assertEquals(0, ((Number) position.get("score")).intValue());

        List<Map<String, Object>> vetoes = vetoes(taskId);
        assertEquals(List.of(
                        "POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
                        "POSITION_RISK_DAILY_LOSS_LIMIT",
                        "POSITION_RISK_MAX_POSITIONS_EXCEEDED",
                        "POSITION_RISK_POSITION_WEIGHT_LIMIT_" + MULTI_VETO_SYMBOL,
                        "POSITION_RISK_PROJECTED_WEIGHT_LIMIT_"
                                + MULTI_VETO_PENDING_SYMBOL,
                        "POSITION_RISK_STOP_LOSS_TRIGGERED_" + MULTI_VETO_SYMBOL,
                        "POSITION_RISK_TRAILING_STOP_TRIGGERED_"
                                + MULTI_VETO_SYMBOL),
                vetoes.stream().map(row -> String.valueOf(row.get("veto_code"))).toList());
        assertVetoAndDecisionMapping(created, position, vetoes);
        assertPersistedContextAndPortfolioEvidence(created, position);
    }

    private void assertVetoAndDecisionMapping(
            CreatedTask created,
            Map<String, Object> positionRun,
            List<Map<String, Object>> vetoRows
    ) throws Exception {
        long taskId = created.task().id();
        long positionRunId = ((Number) positionRun.get("id")).longValue();
        List<Long> physicalIds = new ArrayList<>();
        for (Map<String, Object> veto : vetoRows) {
            long physicalId = ((Number) veto.get("id")).longValue();
            physicalIds.add(physicalId);
            assertTrue(physicalId > 0);
            assertEquals(taskId, ((Number) veto.get("task_id")).longValue());
            assertEquals(positionRunId, ((Number) veto.get("run_id")).longValue());
            assertEquals("POSITION_RISK", veto.get("agent_code"));
            assertFalse(String.valueOf(veto.get("reason")).isBlank());
            assertEquals(1, sqlStrings((Array) veto.get("evidence_ids")).size());
        }

        Map<String, Object> decision = decision(taskId);
        assertEquals("REJECTED_BY_VETO", decision.get("decision"));
        assertEquals("BLOCKED", decision.get("gate_status"));
        assertEquals(true, decision.get("vetoed"));
        assertEquals(physicalIds, sqlLongs((Array) decision.get("veto_ids")));
        JsonNode decisionJson = readJson((String) decision.get("decision_json"));
        List<String> logicalIds = jsonStrings(decisionJson.path("vetoIds"));
        assertEquals(vetoRows.size(), logicalIds.size());
        assertEquals(logicalIds.size(), new HashSet<>(logicalIds).size());
        for (int index = 0; index < logicalIds.size(); index++) {
            assertNotEquals(String.valueOf(physicalIds.get(index)), logicalIds.get(index));
        }
    }

    private void assertPersistedContextAndPortfolioEvidence(
            CreatedTask created,
            Map<String, Object> positionRun
    ) {
        long taskId = created.task().id();
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
        assertEquals(created.task().contextHash(), jdbc.queryForObject(
                "SELECT context_hash FROM agent_tasks WHERE id=?",
                String.class,
                taskId));

        Map<String, Object> evidence = jdbc.queryForMap("""
                SELECT evidence_key, run_id, category, source_type, source_name,
                       source_ref, content_hash, payload_json::text AS payload_json
                FROM agent_evidence
                WHERE task_id=? AND category='PORTFOLIO_STATE'
                """, taskId);
        assertEquals(
                ((Number) positionRun.get("id")).longValue(),
                ((Number) evidence.get("run_id")).longValue());
        assertEquals("JAVA_ENGINE", evidence.get("source_type"));
        assertEquals(PortfolioContracts.PRODUCER, evidence.get("source_name"));
        assertEquals("contextSnapshot.portfolioContext", evidence.get("source_ref"));
        assertEquals(created.task().contextHash(), evidence.get("content_hash"));
        JsonNode payload = readJson((String) evidence.get("payload_json"));
        AgentStage2CReadonlyContextPostgresIntegrationTest
                .assertJsonSemanticallyEquals(
                        created.task().contextSnapshot().path("portfolioContext"),
                        payload.path("portfolioContext"));
    }

    private void prepareMarketData(LocalDate analysisDate, String... symbols) {
        LocalDate effectiveTradeDate = latestSupportedTradeDate(
                analysisDate.minusDays(1));
        when(agentTemporalClock.instant()).thenReturn(
                effectiveTradeDate.atTime(15, 0)
                        .atZone(BacktestContracts.MARKET_ZONE)
                        .toInstant());
        for (String symbol : symbols) {
            var captured = persistenceService.persistBars(
                    symbol,
                    bars(symbol, effectiveTradeDate, 120),
                    SOURCE,
                    "REVISION_1",
                    "TEST_FIXTURE");
            assertEquals(120, captured.appendedObservationCount());
            jdbc.update("""
                    UPDATE securities
                    SET name=?, exchange='SSE', board='MAIN', industry='TEST',
                        list_date=DATE '2000-01-01', is_st=false, is_active=true,
                        data_source=?, updated_at=CURRENT_TIMESTAMP
                    WHERE symbol=?
                    """, "Stage2H " + symbol, SOURCE, symbol);
        }
        when(agentTemporalClock.instant()).thenReturn(
                analysisDate.atTime(LocalTime.MAX)
                        .atZone(PortfolioContracts.MARKET_ZONE)
                        .toInstant());
    }

    private void configureNoRiskScenario(String symbol, LocalDate analysisDate) {
        resetBusinessTables();
        updateAccount("100000.00", "0.00");
        updateLimits(5, "0.20");
        insertPosition(
                symbol, 100, 100, "80.0000", "100.0000",
                null, null, "0.0400", "100.0000", analysisDate.minusDays(10));
        insertEquitySnapshot(analysisDate.minusDays(2), "110000.00");
        insertEquitySnapshot(analysisDate.minusDays(1), "110000.00");
    }

    private void configureSingleVetoScenario(
            String symbol,
            LocalDate analysisDate
    ) {
        resetBusinessTables();
        updateAccount("100000.00", "0.00");
        updateLimits(5, "0.20");
        insertPosition(
                symbol, 1000, 1000, "80.0000", "100.0000",
                null, null, "0.0400", "100.0000", analysisDate.minusDays(10));
        insertEquitySnapshot(analysisDate.minusDays(2), "200000.00");
        insertEquitySnapshot(analysisDate.minusDays(1), "200000.00");
    }

    private void configureMultiVetoScenario(
            String symbol,
            String pendingSymbol,
            LocalDate analysisDate
    ) {
        resetBusinessTables();
        updateAccount("70000.00", "60005.00");
        updateLimits(1, "0.20");
        insertPosition(
                symbol, 1000, 1000, "80.0000", "100.0000",
                "105.0000", null, "0.0400", "120.0000",
                analysisDate.minusDays(10));
        jdbc.update("""
                INSERT INTO manual_orders(
                    account_id, symbol, side, quantity, limit_price, status,
                    gross_amount, frozen_amount, frozen_quantity, created_at
                ) VALUES (1, ?, 'BUY', 600, 100.0000, 'PENDING_CONFIRM',
                          60000.00, 60005.00, 0, CURRENT_TIMESTAMP)
                """, pendingSymbol);
        insertEquitySnapshot(analysisDate.minusDays(2), "200000.00");
        insertEquitySnapshot(analysisDate.minusDays(1), "180000.00");
    }

    private void resetBusinessTables() {
        jdbc.update("DELETE FROM simulated_trades");
        jdbc.update("DELETE FROM manual_orders");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM account_equity_snapshots");
        jdbc.update("DELETE FROM risk_events");
    }

    private void updateAccount(String cash, String frozenCash) {
        jdbc.update("""
                UPDATE portfolio_accounts
                SET name='Stage2H simulated account',
                    initial_capital=100000.00,
                    cash=?::numeric,
                    frozen_cash=?::numeric,
                    realized_pnl=0.00,
                    total_fees=0.00,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=1
                """, cash, frozenCash);
    }

    private void updateLimits(int maxPositions, String maxPositionWeight) {
        jdbc.update("""
                UPDATE app_settings
                SET setting_value=?, updated_at=CURRENT_TIMESTAMP
                WHERE setting_key='portfolio.max_positions'
                """, String.valueOf(maxPositions));
        jdbc.update("""
                UPDATE app_settings
                SET setting_value=?, updated_at=CURRENT_TIMESTAMP
                WHERE setting_key='portfolio.max_position_weight'
                """, maxPositionWeight);
    }

    private void insertPosition(
            String symbol,
            int quantity,
            int availableQuantity,
            String averageCost,
            String lastPrice,
            String stopLoss,
            String targetPrice,
            String trailingStopPct,
            String highestPrice,
            LocalDate lastBuyDate
    ) {
        jdbc.update("""
                INSERT INTO positions(
                    account_id, symbol, quantity, available_quantity,
                    average_cost, last_price, stop_loss, target_price,
                    trailing_stop_pct, highest_price, source_plan_id,
                    opened_at, last_buy_date, updated_at
                ) VALUES (
                    1, ?, ?, ?, ?::numeric, ?::numeric, ?::numeric, ?::numeric,
                    ?::numeric, ?::numeric, NULL,
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP
                )
                """,
                symbol,
                quantity,
                availableQuantity,
                averageCost,
                lastPrice,
                stopLoss,
                targetPrice,
                trailingStopPct,
                highestPrice,
                lastBuyDate);
    }

    private void insertEquitySnapshot(LocalDate date, String totalAsset) {
        BigDecimal total = new BigDecimal(totalAsset);
        BigDecimal totalReturn = total.subtract(new BigDecimal("100000.00"))
                .divide(new BigDecimal("100000.00"), 8, java.math.RoundingMode.HALF_UP);
        jdbc.update("""
                INSERT INTO account_equity_snapshots(
                    account_id, snapshot_date, cash, frozen_cash,
                    market_value, total_asset, realized_pnl, unrealized_pnl,
                    total_return, created_at, updated_at
                ) VALUES (
                    1, ?, ?::numeric, 0.00, 0.00, ?::numeric,
                    0.00, 0.00, ?::numeric, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """, date, totalAsset, totalAsset, totalReturn);
    }

    private Map<String, List<String>> businessTableRows() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("portfolio_accounts", jsonRows(
                "SELECT to_jsonb(t)::text FROM portfolio_accounts t ORDER BY id"));
        result.put("positions", jsonRows(
                "SELECT to_jsonb(t)::text FROM positions t ORDER BY id"));
        result.put("manual_orders", jsonRows(
                "SELECT to_jsonb(t)::text FROM manual_orders t ORDER BY id"));
        result.put("simulated_trades", jsonRows(
                "SELECT to_jsonb(t)::text FROM simulated_trades t ORDER BY id"));
        result.put("account_equity_snapshots", jsonRows("""
                SELECT to_jsonb(t)::text FROM account_equity_snapshots t
                ORDER BY account_id, snapshot_date
                """));
        result.put("risk_events", jsonRows(
                "SELECT to_jsonb(t)::text FROM risk_events t ORDER BY id"));
        assertEquals(BUSINESS_TABLES, new ArrayList<>(result.keySet()));
        return Map.copyOf(result);
    }

    private List<String> jsonRows(String sql) {
        return jdbc.query(sql, (row, index) -> row.getString(1));
    }

    private List<Map<String, Object>> runs(long taskId) {
        return jdbc.queryForList("""
                SELECT id, agent_code, status, gate_status, decision, score,
                       confidence, veto, output_json::text AS output_json
                FROM agent_runs WHERE task_id=? ORDER BY id
                """, taskId);
    }

    private static Map<String, Object> run(
            List<Map<String, Object>> runs,
            String agentCode
    ) {
        return runs.stream()
                .filter(row -> agentCode.equals(row.get("agent_code")))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSixRuns(List<Map<String, Object>> runs) {
        assertEquals(6, runs.size());
        assertEquals(
                AGENT_CODES,
                runs.stream()
                        .map(row -> String.valueOf(row.get("agent_code")))
                        .collect(java.util.stream.Collectors.toSet()));
        assertFalse(runs.stream()
                .anyMatch(row -> "CHIEF_DECISION".equals(row.get("agent_code"))));
    }

    private List<Map<String, Object>> vetoes(long taskId) {
        return jdbc.queryForList("""
                SELECT id, task_id, run_id, agent_code, veto_code,
                       reason, evidence_ids, created_at
                FROM agent_vetoes WHERE task_id=? ORDER BY id
                """, taskId);
    }

    private Map<String, Object> decision(long taskId) {
        return jdbc.queryForMap("""
                SELECT status, decision, gate_status, vetoed, source_run_ids,
                       veto_ids, decision_json::text AS decision_json
                FROM agent_decisions WHERE task_id=?
                """, taskId);
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

    private static CreateAgentTaskRequest request(
            String symbol,
            LocalDate tradeDate
    ) {
        return new CreateAgentTaskRequest(
                symbol,
                tradeDate,
                ExecutionMode.LOCAL_RULES,
                PortfolioContracts.RULE_VERSION,
                false,
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

    private static List<Bar> bars(
            String symbol,
            LocalDate end,
            int count
    ) {
        List<LocalDate> dates = tradingDates(end, count);
        List<Bar> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("88.10")
                    .add(new BigDecimal("0.10")
                            .multiply(BigDecimal.valueOf(index)));
            result.add(new Bar(
                    symbol,
                    dates.get(index),
                    close,
                    close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE),
                    close,
                    10_000L + index,
                    new BigDecimal("1000000.0000"),
                    new BigDecimal("0.5000")));
        }
        return List.copyOf(result);
    }

    private static LocalDate latestSupportedTradeDate(LocalDate requested) {
        LocalDate candidate = requested;
        while (!BacktestContracts.isSupportedDailyBarTradeDate(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private static List<LocalDate> tradingDates(LocalDate end, int count) {
        List<LocalDate> values = new ArrayList<>();
        LocalDate candidate = end;
        while (values.size() < count) {
            if (BacktestContracts.isSupportedDailyBarTradeDate(candidate)) {
                values.add(candidate);
            }
            candidate = candidate.minusDays(1);
        }
        Collections.reverse(values);
        return List.copyOf(values);
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

    private static List<Long> sqlLongs(Array values) throws SQLException {
        if (values == null) return List.of();
        Object raw = values.getArray();
        List<Long> result = new ArrayList<>();
        if (raw instanceof Object[] items) {
            for (Object item : items) {
                result.add(((Number) item).longValue());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> sqlStrings(Array values) throws SQLException {
        if (values == null) return List.of();
        Object raw = values.getArray();
        List<String> result = new ArrayList<>();
        if (raw instanceof Object[] items) {
            for (Object item : items) result.add(String.valueOf(item));
        }
        return List.copyOf(result);
    }

    private static List<String> jsonStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        values.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }

    private static HttpServer startProxy() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), 0),
                    0);
            server.createContext(
                    "/agents/team/analyze",
                    AgentStage2HPostgresPythonIntegrationTest::proxy);
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
                    if ("POSITION_RISK".equals(run.path("agentCode").asText())) {
                        ObjectNode position = (ObjectNode) run;
                        position.put(
                                "score",
                                position.path("score").asInt() == 100
                                        ? 99
                                        : position.path("score").asInt() + 1);
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
            byte[] output = ("proxy failure: " + error.getClass().getSimpleName())
                    .getBytes(StandardCharsets.UTF_8);
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
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
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
                    "stock_quant_test must permit isolated stage_2h_team_it_ schema",
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
        if (!schema.matches("^stage_2h_team_it_[0-9a-f]{32}$")) {
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
