package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.announcement.AnnouncementContracts;
import com.stockquant.server.agent.announcement.AnnouncementIngestionService;
import com.stockquant.server.agent.announcement.AnnouncementProviderClient;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.backtest.MarketDataPersistenceService;
import com.stockquant.server.agent.chief.ChiefDecisionContracts;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MockMarketFactProvider;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService;
import com.stockquant.server.agent.marketfacts.PitMarketFactsContracts;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import com.stockquant.server.agent.service.AgentTaskService;
import com.stockquant.server.agent.shadow.AgentShadowBatchService;
import com.stockquant.server.agent.shadow.AgentShadowJob;
import com.stockquant.server.agent.shadow.AgentShadowMetricsService;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.MetricsFilter;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage3AR3B0PostgresPythonShadowIntegrationTest {

    private static final String SYMBOL = "600701";
    private static final String INVALID_SYMBOL = "600702";
    private static final String COMPANION = "600711";
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 27);
    private static final LocalDate FACT_START = TRADE_DATE.minusDays(365);
    private static final Instant CAPTURE_TIME = TRADE_DATE.atTime(15, 30)
            .atZone(PitMarketFactsContracts.MARKET_ZONE).toInstant();
    private static final Instant QUERY_TIME =
            Instant.parse("2026-07-27T15:59:59.999999Z");
    private static final String MARKET_SOURCE =
            "TEST_FIXTURE_STAGE_3AR3B0";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper PROXY_MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final HttpServer PROXY = startProxy();
    private static final Map<Long, AtomicInteger> CALLS =
            new ConcurrentHashMap<>();
    private static final AtomicReference<Instant> NOW =
            new AtomicReference<>(CAPTURE_TIME);
    private static AgentPostgresTestEnvironment.IsolatedSchema isolated;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PitMarketFactCaptureService factCapture;
    @Autowired MarketDataPersistenceService marketDataPersistence;
    @Autowired AnnouncementIngestionService announcementIngestion;
    @Autowired AgentTaskService taskService;
    @Autowired AgentContextSnapshotService contextService;
    @Autowired AgentShadowBatchService shadowBatchService;
    @Autowired AgentShadowMetricsService shadowMetricsService;
    @Autowired AgentShadowRepository shadowRepository;
    @Autowired ApplicationContext applicationContext;
    @MockBean AnnouncementProviderClient announcementProvider;
    @MockBean(name = "agentTemporalClock") Clock clock;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        isolated = AgentPostgresTestEnvironment.registerIsolatedDataSource(
                registry, "pit_v2_team");
        registry.add("stockquant.agent-team.base-url", () ->
                "http://127.0.0.1:" + PROXY.getAddress().getPort());
        registry.add("stockquant.announcement.akshare.enabled", () -> true);
        registry.add("stockquant.announcement.akshare.base-url",
                () -> "http://127.0.0.1:1");
        registry.add("stockquant.agent-team.shadow.enabled", () -> true);
        registry.add("stockquant.agent-team.shadow.scheduler-enabled",
                () -> false);
        registry.add("stockquant.agent-team.shadow.rule-version",
                () -> PitMarketFactsContracts.RULE_VERSION);
        registry.add(
                "stockquant.agent-team.shadow.test-demo-pit-v2-enabled",
                () -> true);
        registry.add(
                "stockquant.market-facts.v2.test-demo-enabled",
                () -> true);
        registry.add("stockquant.agent-team.shadow.max-concurrency", () -> 1);
        registry.add("stockquant.agent-team.shadow.poll-interval",
                () -> "100ms");
        registry.add("stockquant.agent-team.shadow.item-timeout",
                () -> "45s");
    }

    @BeforeEach
    void freezeClockAndProvider() {
        when(clock.instant()).thenAnswer(ignored -> NOW.get());
        when(clock.getZone()).thenReturn(
                PitMarketFactsContracts.MARKET_ZONE);
        when(announcementProvider.fetch(any())).thenAnswer(invocation ->
                emptyAnnouncementCapture(invocation.getArgument(0)));
    }

    @AfterAll
    static void stopProxyAndCleanSchema() {
        PROXY.stop(0);
        if (isolated != null) {
            isolated.close();
        }
    }

    @Test
    void closesProviderNeutralFactsThroughRealPythonPersistenceAndShadow() {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7",
                        "8", "9", "10", "11", "12", "13"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success ORDER BY installed_rank
                        """, String.class));
        assertTrue(applicationContext
                .getBeansOfType(AgentShadowJob.class).isEmpty());

        prepareCurrentProjection(SYMBOL, INVALID_SYMBOL, COMPANION);
        captureFacts(SYMBOL);
        captureFacts(INVALID_SYMBOL);
        captureAnnouncements(SYMBOL);
        captureAnnouncements(INVALID_SYMBOL);
        configureCompleteNoRiskAccount();
        NOW.set(QUERY_TIME);

        JsonNode v2Context = contextService.create(
                SYMBOL, TRADE_DATE, PitMarketFactsContracts.RULE_VERSION)
                .value().path("backtestContext");
        assertTrue(v2Context.path("available").asBoolean(),
                v2Context.path("reasonCode").asText());
        assertEquals("BACKTEST_CONTEXT_V2",
                v2Context.path("schemaVersion").asText());
        assertEquals("DAILY_EXACT",
                v2Context.path("qfqContract")
                        .path("factorCoverageMode").asText());
        assertFalse(v2Context.path("qfqContract")
                .path("forwardFillAllowed").asBoolean(true));
        assertTrue(v2Context.path("testDemoOnly").asBoolean());
        String v2Hash = contextService.create(
                SYMBOL, TRADE_DATE,
                PitMarketFactsContracts.RULE_VERSION).contextHash();
        String oldHash = contextService.create(
                SYMBOL, TRADE_DATE,
                ChiefDecisionContracts.RULE_VERSION).contextHash();
        assertNotEquals(oldHash, v2Hash,
                "new profile must have an isolated cache/context hash");

        Map<String, List<String>> protectedBefore = protectedRows();
        long factCountBefore = count(
                "SELECT count(*) FROM pit_market_fact_observations");

        var batch = shadowBatchService.createManual(
                TRADE_DATE,
                SelectionMode.EXPLICIT,
                List.of(SYMBOL),
                1,
                "stage-3ar3b0-offline-integration");
        var terminal = awaitBatch(batch.id());
        assertEquals(BatchStatus.COMPLETED, terminal.status());
        assertEquals(1, terminal.selectedCount());
        assertEquals(1, terminal.launchedCount());
        assertEquals(1, terminal.terminalCount());
        assertEquals(0, terminal.failedCount());

        var items = shadowBatchService.items(batch.id());
        assertEquals(1, items.size());
        var item = items.get(0);
        assertEquals(SYMBOL, item.symbol());
        assertEquals(
                OutcomeClass.DETERMINED,
                item.outcomeClass(),
                item.primaryReasonCode() + " "
                        + item.reasonCodes());
        assertNotNull(item.agentTaskId());
        assertTrue(item.taskNewlyCreated());
        assertFalse(item.cacheHit());
        assertEquals(6, count("""
                SELECT count(*) FROM agent_runs
                WHERE task_id=%d
                """.formatted(item.agentTaskId())));
        assertEquals(PitMarketFactsContracts.RULE_VERSION,
                jdbc.queryForObject("""
                        SELECT rule_version FROM agent_tasks WHERE id=?
                        """, String.class, item.agentTaskId()));
        assertEquals("COMPLETED", jdbc.queryForObject("""
                SELECT status FROM agent_tasks WHERE id=?
                """, String.class, item.agentTaskId()));
        String decision = jdbc.queryForObject("""
                SELECT decision FROM agent_decisions WHERE task_id=?
                """, String.class, item.agentTaskId());
        assertTrue(Set.of(
                "RESEARCH_ONLY", "WATCH",
                "PASS_TO_MANUAL_REVIEW").contains(decision), decision);
        assertEquals(1, calls(item.agentTaskId()));

        CreatedTask cached = taskService.create(
                request(SYMBOL), "stage-3ar3b0-cache");
        assertFalse(cached.newlyCreated());
        assertEquals(item.agentTaskId(), cached.task().id());
        assertEquals(1, calls(item.agentTaskId()));

        var metrics = shadowMetricsService.metrics(new MetricsFilter(
                null, null, PitMarketFactsContracts.RULE_VERSION,
                batch.id(), SYMBOL));
        assertEquals(1, metrics.batchCount());
        assertEquals(1, metrics.itemCount());
        assertEquals(1L, metrics.outcomeDistribution()
                .getOrDefault("DETERMINED", 0L));

        CreatedTask invalid = taskService.create(
                request(INVALID_SYMBOL), "stage-3ar3b0-invalid-response");
        assertTrue(invalid.newlyCreated());
        await(() -> "FAILED".equals(taskStatus(invalid.task().id())),
                "tampered V2 task did not fail atomically");
        assertEquals(1, calls(invalid.task().id()));
        assertEquals(0, countForTask(
                "agent_evidence", invalid.task().id()));
        assertEquals(0, countForTask(
                "agent_vetoes", invalid.task().id()));
        assertEquals(0, countForTask(
                "agent_decisions", invalid.task().id()));
        for (Map<String, Object> run : jdbc.queryForList("""
                SELECT status, output_json, score, confidence, error_message
                FROM agent_runs WHERE task_id=? ORDER BY id
                """, invalid.task().id())) {
            assertEquals("FAILED", run.get("status"));
            assertNull(run.get("output_json"));
            assertNull(run.get("score"));
            assertNull(run.get("confidence"));
            assertNotNull(run.get("error_message"));
        }

        assertEquals(protectedBefore, protectedRows());
        assertEquals(factCountBefore, count(
                "SELECT count(*) FROM pit_market_fact_observations"));
        assertEquals(0, count(
                "SELECT count(*) FROM agent_shadow_reviews"));
    }

    private void prepareCurrentProjection(String... symbols) {
        NOW.set(CAPTURE_TIME);
        for (String symbol : symbols) {
            var result = marketDataPersistence.persistBars(
                    symbol,
                    bars(symbol, TRADE_DATE, 500),
                    MARKET_SOURCE,
                    "TEST_PROVIDER_REVISION_V1",
                    "TEST_FIXTURE");
            assertEquals(500, result.appendedObservationCount());
            jdbc.update("""
                    UPDATE securities
                    SET name=?, exchange='SSE', board='MAIN', industry='TEST',
                        list_date=DATE '2000-01-01', is_st=false,
                        is_active=true, data_source=?,
                        updated_at=CURRENT_TIMESTAMP
                    WHERE symbol=?
                    """, "PIT V2 " + symbol, MARKET_SOURCE, symbol);
        }
    }

    private void captureFacts(String symbol) {
        MockMarketFactProvider provider = new MockMarketFactProvider(
                mapper, MockMarketFactProvider.Scenario.NORMAL);
        var result = factCapture.fetchAndCapture(
                provider,
                new MarketFactRequest(
                        RunNamespace.TEST,
                        MockMarketFactProvider.PROVIDER_CODE,
                        symbol + ".SSE",
                        symbol,
                        "SSE",
                        FACT_START,
                        TRADE_DATE,
                        Set.of(FactType.values()),
                        Duration.ofSeconds(5)));
        assertTrue(result.complete());
        assertTrue(result.appendedCount() >= 120);
        assertEquals(1, provider.fetchCount());
    }

    private void captureAnnouncements(String symbol) {
        var result = announcementIngestion.capture(new CaptureRequest(
                symbol, TRADE_DATE.minusDays(179), TRADE_DATE));
        assertTrue(result.complete());
        assertEquals(0, result.recordCount());
        assertEquals(0, result.appendedCount());
    }

    private static ProviderResponse emptyAnnouncementCapture(
            ProviderRequest request
    ) {
        int chunks = (int) Math.ceil(
                (request.endDate().toEpochDay()
                        - request.startDate().toEpochDay() + 1) / 30.0);
        return new ProviderResponse(
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                AnnouncementContracts.AKSHARE_VERSION,
                request.symbol(),
                request.startDate(),
                request.endDate(),
                true,
                chunks,
                chunks,
                List.of(),
                List.of());
    }

    private void configureCompleteNoRiskAccount() {
        jdbc.update("DELETE FROM simulated_trades");
        jdbc.update("DELETE FROM manual_orders");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM account_equity_snapshots");
        jdbc.update("DELETE FROM risk_events");
        jdbc.update("""
                UPDATE portfolio_accounts
                SET name='Stage 3A-R3B-0 simulated account',
                    initial_capital=100000.00,
                    cash=100000.00,
                    frozen_cash=0.00,
                    realized_pnl=0.00,
                    total_fees=0.00,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=1
                """);
        jdbc.update("""
                UPDATE app_settings SET setting_value='5'
                WHERE setting_key='portfolio.max_positions'
                """);
        jdbc.update("""
                UPDATE app_settings SET setting_value='0.20'
                WHERE setting_key='portfolio.max_position_weight'
                """);
        insertEquitySnapshot(TRADE_DATE.minusDays(2));
        insertEquitySnapshot(TRADE_DATE.minusDays(1));
    }

    private void insertEquitySnapshot(LocalDate date) {
        jdbc.update("""
                INSERT INTO account_equity_snapshots(
                    account_id, snapshot_date, cash, frozen_cash,
                    market_value, total_asset, realized_pnl, unrealized_pnl,
                    total_return, created_at, updated_at
                ) VALUES (
                    1, ?, 100000.00, 0.00, 0.00, 100000.00,
                    0.00, 0.00, 0.00000000,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """, date);
    }

    private static CreateAgentTaskRequest request(String symbol) {
        return new CreateAgentTaskRequest(
                symbol,
                TRADE_DATE,
                ExecutionMode.LOCAL_RULES,
                PitMarketFactsContracts.RULE_VERSION,
                false,
                TriggerType.MANUAL);
    }

    private String taskStatus(long taskId) {
        List<String> values = jdbc.query(
                "SELECT status FROM agent_tasks WHERE id=?",
                (row, index) -> row.getString(1),
                taskId);
        return values.isEmpty() ? null : values.get(0);
    }

    private com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch
    awaitBatch(long batchId) {
        AtomicReference<com.stockquant.server.agent.shadow.AgentShadowModels
                .ShadowBatch> value = new AtomicReference<>();
        await(() -> {
            var current = shadowRepository.findBatch(batchId).orElseThrow();
            value.set(current);
            return current.status().terminal();
        }, "V2 mock shadow batch did not become terminal");
        return value.get();
    }

    private Map<String, List<String>> protectedRows() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String table : List.of(
                "portfolio_accounts",
                "positions",
                "manual_orders",
                "simulated_trades",
                "account_equity_snapshots",
                "risk_events",
                "daily_bars",
                "market_data_observation_batches",
                "daily_bar_observations",
                "announcement_capture_batches",
                "announcement_observations",
                "pit_market_fact_batches",
                "pit_market_fact_observations",
                "raw_daily_bar_facts_v2",
                "adjustment_factor_facts_v1",
                "trading_calendar_facts_v1",
                "corporate_action_facts_v1")) {
            result.put(table, jdbc.query(
                    "SELECT to_jsonb(fact)::text FROM " + table
                            + " fact ORDER BY to_jsonb(fact)::text",
                    (row, index) -> row.getString(1)));
        }
        return Map.copyOf(result);
    }

    private long countForTask(String table, long taskId) {
        if (!Set.of(
                "agent_evidence",
                "agent_vetoes",
                "agent_decisions").contains(table)) {
            throw new IllegalArgumentException("unsupported task table");
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE task_id=?",
                Long.class, taskId);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private static List<Bar> bars(
            String symbol,
            LocalDate end,
            int count
    ) {
        List<LocalDate> dates = tradingDates(end, count);
        List<Bar> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("80.00")
                    .add(new BigDecimal(index % 40)
                            .multiply(new BigDecimal("0.10")));
            result.add(new Bar(
                    symbol,
                    dates.get(index),
                    close,
                    close.add(BigDecimal.ONE),
                    close.subtract(BigDecimal.ONE),
                    close,
                    100_000L + index,
                    new BigDecimal("10000000.0000"),
                    new BigDecimal("0.01000000")));
        }
        return List.copyOf(result);
    }

    private static List<LocalDate> tradingDates(
            LocalDate end,
            int count
    ) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate current = end;
        while (result.size() < count) {
            if (current.getDayOfWeek().getValue() <= 5) {
                result.add(current);
            }
            current = current.minusDays(1);
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private static int calls(long taskId) {
        AtomicInteger value = CALLS.get(taskId);
        return value == null ? 0 : value.get();
    }

    private static void await(
            BooleanSupplier condition,
            String message
    ) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
        throw new AssertionError(message);
    }

    private static HttpServer startProxy() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), 0),
                    0);
            server.createContext(
                    "/agents/team/analyze",
                    AgentStage3AR3B0PostgresPythonShadowIntegrationTest::proxy);
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
                        strategy.put("score",
                                strategy.path("score").asInt() == 100
                                        ? 99
                                        : strategy.path("score").asInt() + 1);
                    }
                }
            }
            byte[] output = PROXY_MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
        } catch (Exception error) {
            byte[] output = "proxy failure"
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
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty(
                "Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(request.length);
        connection.setDoOutput(true);
        connection.getOutputStream().write(request);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        byte[] body = stream == null
                ? new byte[0] : stream.readAllBytes();
        connection.disconnect();
        if (status != 200) {
            throw new IOException("Python returned HTTP " + status);
        }
        return body;
    }
}
