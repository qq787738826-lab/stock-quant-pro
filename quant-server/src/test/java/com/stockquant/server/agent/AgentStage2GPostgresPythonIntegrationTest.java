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
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.backtest.MarketDataPersistenceService;
import com.stockquant.server.agent.chief.ChiefDecisionContracts;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.CacheKey;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentModels.RunIds;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.portfolio.PortfolioContracts;
import com.stockquant.server.agent.service.AgentCacheService;
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentResultPersistenceService;
import com.stockquant.server.agent.service.AgentTaskService;
import com.stockquant.server.agent.validation.AgentResponseValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage2GPostgresPythonIntegrationTest {

    private static final String NO_EVENT_SYMBOL = "600801";
    private static final String RISK_SYMBOL = "600802";
    private static final String VETO_SYMBOL = "600803";
    private static final String BLOCKED_SYMBOL = "600804";
    private static final String INVALID_SYMBOL = "600805";
    private static final String COMPANION_SYMBOL = "600811";
    private static final String CHIEF_PASS_SYMBOL = "600821";
    private static final String CHIEF_WATCH_SYMBOL = "600822";
    private static final String CHIEF_RESEARCH_SYMBOL = "600823";
    private static final String CHIEF_VETO_SYMBOL = "600824";
    private static final String CHIEF_BLOCKED_SYMBOL = "600825";
    private static final String CHIEF_INSUFFICIENT_SYMBOL = "600826";
    private static final String CHIEF_INVALID_SYMBOL = "600827";
    private static final String CHIEF_COMPANION_SYMBOL = "600831";
    private static final String SOURCE = "TEST_FIXTURE_STAGE_2G";
    private static final LocalDate ANALYSIS_DATE = LocalDate.of(2026, 7, 25);
    private static final LocalDate CHIEF_ANALYSIS_DATE =
            LocalDate.of(2026, 7, 24);
    private static final LocalDate CAPTURE_START = ANALYSIS_DATE.minusDays(179);
    private static final LocalDate CHIEF_CAPTURE_START =
            CHIEF_ANALYSIS_DATE.minusDays(179);
    private static final Instant CAPTURE_INSTANT = ANALYSIS_DATE.atTime(11, 0)
            .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
    private static final Instant QUERY_INSTANT = ANALYSIS_DATE.atTime(12, 0)
            .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
    private static final Instant CHIEF_CAPTURE_INSTANT =
            CHIEF_ANALYSIS_DATE.atTime(11, 0)
                    .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
    private static final Instant CHIEF_QUERY_INSTANT =
            CHIEF_ANALYSIS_DATE.atTime(12, 0)
                    .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final String SCHEMA_PREFIX = "stage_2g_team_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final ObjectMapper PROXY_MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final HttpServer PROXY = startProxy();
    private static final Map<Long, AtomicInteger> CALLS =
            new ConcurrentHashMap<>();
    private static final Map<Long, JsonNode> FORWARDED_RESPONSES =
            new ConcurrentHashMap<>();
    private static final List<String> BUSINESS_TABLES = List.of(
            "portfolio_accounts",
            "positions",
            "manual_orders",
            "simulated_trades",
            "account_equity_snapshots",
            "risk_events");
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
    @Autowired AnnouncementIngestionService ingestion;
    @Autowired MarketDataPersistenceService marketDataPersistence;
    @Autowired AgentContextHashService contextHashes;
    @Autowired AgentCacheService cacheService;
    @Autowired AgentResponseValidator responseValidator;
    @Autowired AgentResultPersistenceService resultPersistence;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AnnouncementProviderClient provider;
    @MockBean(name = "agentTemporalClock") Clock agentTemporalClock;

    private final AtomicReference<Instant> now =
            new AtomicReference<>(QUERY_INSTANT);
    private final Map<String, List<ProviderRecord>> providerRecords =
            new ConcurrentHashMap<>();

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
        registry.add("stockquant.announcement.akshare.enabled", () -> true);
        registry.add(
                "stockquant.announcement.akshare.base-url",
                () -> "http://127.0.0.1:1");
        registry.add(
                "stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + PROXY.getAddress().getPort());
    }

    @BeforeEach
    void configureMocks() {
        when(agentTemporalClock.instant()).thenAnswer(ignored -> now.get());
        when(provider.fetch(any())).thenAnswer(invocation ->
                complete(invocation.getArgument(0)));
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
                    WHERE schema_name='%s'
                    """.formatted(TEST_SCHEMA)));
            assertEquals(publicBaseline, publicBaseline(statement));
        }
    }

    @Test
    void closesProviderContextPythonPersistencePriorityAndAtomicFailure()
            throws Exception {
        assertMigrations();
        prepareMarketData(
                NO_EVENT_SYMBOL,
                RISK_SYMBOL,
                VETO_SYMBOL,
                INVALID_SYMBOL,
                COMPANION_SYMBOL);
        capture(NO_EVENT_SYMBOL, List.of());
        capture(RISK_SYMBOL, List.of(
                record(
                        RISK_SYMBOL,
                        "1212800001",
                        "减持计划公告",
                        ANALYSIS_DATE.minusDays(10)),
                record(
                        RISK_SYMBOL,
                        "1212800002",
                        "立案调查暨重大诉讼公告",
                        ANALYSIS_DATE)));
        capture(VETO_SYMBOL, List.of(record(
                VETO_SYMBOL,
                "1212800003",
                "问询函",
                ANALYSIS_DATE.minusDays(1))));
        capture(BLOCKED_SYMBOL, List.of());
        capture(INVALID_SYMBOL, List.of(record(
                INVALID_SYMBOL,
                "1212800004",
                "对外担保公告",
                ANALYSIS_DATE.minusDays(2))));

        configureNoRiskAccount();
        CreatedTask noEvent = execute(NO_EVENT_SYMBOL, "stage-2g-no-event");
        assertNoEventPersistence(noEvent);

        configureNoRiskAccount();
        CreatedTask risks = execute(RISK_SYMBOL, "stage-2g-multi-risk");
        assertRiskPersistence(risks);

        configurePositionVeto(VETO_SYMBOL);
        CreatedTask veto = execute(VETO_SYMBOL, "stage-2g-position-veto");
        assertPositionVetoPriority(veto);

        configureNoRiskAccount();
        CreatedTask blocked = execute(BLOCKED_SYMBOL, "stage-2g-dq-blocked");
        assertDataQualityBlocked(blocked);

        configureNoRiskAccount();
        assertTamperedResponseFailsAtomically();
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void closesStage2ICompositePersistencePriorityCacheAndAtomicFailure()
            throws Exception {
        assertMigrations();
        prepareChiefMarketData(
                CHIEF_PASS_SYMBOL,
                CHIEF_WATCH_SYMBOL,
                CHIEF_RESEARCH_SYMBOL,
                CHIEF_VETO_SYMBOL,
                CHIEF_INSUFFICIENT_SYMBOL,
                CHIEF_INVALID_SYMBOL,
                CHIEF_COMPANION_SYMBOL);
        captureChief(CHIEF_PASS_SYMBOL, List.of());
        captureChief(CHIEF_WATCH_SYMBOL, List.of(record(
                CHIEF_WATCH_SYMBOL,
                "1212820001",
                "\u51cf\u6301\u8ba1\u5212\u516c\u544a",
                CHIEF_ANALYSIS_DATE)));
        captureChief(CHIEF_RESEARCH_SYMBOL, List.of(record(
                CHIEF_RESEARCH_SYMBOL,
                "1212820002",
                "\u91cd\u5927\u8bc9\u8bbc\u516c\u544a",
                CHIEF_ANALYSIS_DATE)));
        captureChief(CHIEF_VETO_SYMBOL, List.of());
        captureChief(CHIEF_BLOCKED_SYMBOL, List.of());
        captureChief(CHIEF_INVALID_SYMBOL, List.of());

        assertFixedChiefOutcomesPersistThroughRealPythonAndPostgres();

        configureCompleteNoRiskAccount();
        CreatedTask pass = executeChief(
                CHIEF_PASS_SYMBOL, "stage-2i-pass", true);
        assertChiefDecision(
                pass,
                "INSUFFICIENT_DATA",
                "NOT_APPLICABLE",
                0,
                0,
                "PARTIAL");

        configureCompleteNoRiskAccount();
        CreatedTask watch = executeChief(
                CHIEF_WATCH_SYMBOL, "stage-2i-watch", true);
        assertChiefDecision(
                watch,
                "INSUFFICIENT_DATA",
                "NOT_APPLICABLE",
                0,
                0,
                "PARTIAL");

        configureCompleteNoRiskAccount();
        CreatedTask research = executeChief(
                CHIEF_RESEARCH_SYMBOL, "stage-2i-research", true);
        assertChiefDecision(
                research,
                "INSUFFICIENT_DATA",
                "NOT_APPLICABLE",
                0,
                0,
                "PARTIAL");

        configureCompleteNoRiskAccount();
        CreatedTask insufficient = executeChief(
                CHIEF_INSUFFICIENT_SYMBOL,
                "stage-2i-insufficient",
                false);
        assertChiefDecision(
                insufficient,
                "INSUFFICIENT_DATA",
                "NOT_APPLICABLE",
                0,
                0,
                "PARTIAL");
        assertChiefNotInCompletedCache(insufficient);

        configureChiefPositionVeto(CHIEF_VETO_SYMBOL);
        CreatedTask veto = executeChief(
                CHIEF_VETO_SYMBOL, "stage-2i-veto", true);
        assertChiefDecision(
                veto,
                "REJECTED_BY_VETO",
                "BLOCKED",
                0,
                100,
                "COMPLETED");
        assertTrue(((Boolean) decision(veto.task().id()).get("vetoed")));
        assertTrue(runs(veto.task().id()).stream().anyMatch(
                row -> "INSUFFICIENT_DATA".equals(row.get("status"))));
        assertChiefCompletedCacheHit(veto, CHIEF_VETO_SYMBOL);

        configureCompleteNoRiskAccount();
        CreatedTask blocked = executeChief(
                CHIEF_BLOCKED_SYMBOL, "stage-2i-dq-blocked", true);
        assertChiefDecision(
                blocked,
                "BLOCKED_BY_DATA_QUALITY",
                "BLOCKED",
                0,
                100,
                "COMPLETED");
        assertTrue(runs(blocked.task().id()).stream().anyMatch(
                row -> "INSUFFICIENT_DATA".equals(row.get("status"))));
        assertChiefCompletedCacheHit(blocked, CHIEF_BLOCKED_SYMBOL);

        configureCompleteNoRiskAccount();
        assertChiefTamperedResponseFailsAtomically();
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private void capture(String symbol, List<ProviderRecord> records) {
        providerRecords.put(symbol, List.copyOf(records));
        now.set(CAPTURE_INSTANT);
        var result = ingestion.capture(
                new CaptureRequest(symbol, CAPTURE_START, ANALYSIS_DATE));
        assertTrue(result.complete());
        assertEquals(records.size(), result.recordCount());
        assertEquals(records.size(), result.appendedCount());
        now.set(QUERY_INSTANT);
    }

    private void captureChief(
            String symbol,
            List<ProviderRecord> records
    ) {
        providerRecords.put(symbol, List.copyOf(records));
        now.set(CHIEF_CAPTURE_INSTANT);
        var result = ingestion.capture(new CaptureRequest(
                symbol,
                CHIEF_CAPTURE_START,
                CHIEF_ANALYSIS_DATE));
        assertTrue(result.complete());
        assertEquals(records.size(), result.recordCount());
        assertEquals(records.size(), result.appendedCount());
        now.set(CHIEF_QUERY_INSTANT);
    }

    private CreatedTask execute(String symbol, String requestedBy) {
        Map<String, List<String>> businessBefore = businessTableRows();
        long observationCount = count(
                "SELECT count(*) FROM announcement_observations");
        CreatedTask created = taskService.create(
                request(symbol),
                requestedBy);
        assertTrue(created.newlyCreated());
        long taskId = created.task().id();
        await(
                () -> Set.of("PARTIAL", "COMPLETED", "FAILED")
                        .contains(taskStatus(taskId)),
                "stage 2G task did not reach a terminal state");
        assertFalse("FAILED".equals(taskStatus(taskId)),
                () -> "forwarded response=" + FORWARDED_RESPONSES.get(taskId));
        assertEquals(1, calls(taskId));
        assertEquals(businessBefore, businessTableRows());
        assertEquals(observationCount, count(
                "SELECT count(*) FROM announcement_observations"));
        assertTrue(created.task().contextSnapshot()
                .path("securityEvents").path("available").asBoolean());
        assertEquals(
                AnnouncementContracts.CONTEXT_PROFILE,
                created.task().contextSnapshot()
                        .path("securityEvents").path("contextProfile").asText());
        assertPersistedContext(created);
        return created;
    }

    private CreatedTask executeChief(
            String symbol,
            String requestedBy,
            boolean expectedSecurityEventsAvailable
    ) {
        Map<String, List<String>> businessBefore = businessTableRows();
        long observationCount = count(
                "SELECT count(*) FROM announcement_observations");
        CreatedTask created = taskService.create(
                chiefRequest(symbol),
                requestedBy);
        assertTrue(created.newlyCreated());
        long taskId = created.task().id();
        await(
                () -> Set.of("PARTIAL", "COMPLETED", "FAILED")
                        .contains(taskStatus(taskId)),
                "stage 2I task did not reach a terminal state");
        assertFalse("FAILED".equals(taskStatus(taskId)),
                () -> "forwarded response=" + FORWARDED_RESPONSES.get(taskId));
        assertEquals(1, calls(taskId));
        assertEquals(businessBefore, businessTableRows());
        assertEquals(observationCount, count(
                "SELECT count(*) FROM announcement_observations"));
        assertEquals(
                expectedSecurityEventsAvailable,
                created.task().contextSnapshot()
                        .path("securityEvents").path("available").asBoolean());
        assertPersistedContext(created);
        return created;
    }

    private void assertFixedChiefOutcomesPersistThroughRealPythonAndPostgres()
            throws Exception {
        Map<String, List<String>> businessBefore = businessTableRows();
        for (var expected : List.of(
                Map.entry(
                        AgentStage2ITestFixtures.Scenario.NO_EVENT,
                        "PASS_TO_MANUAL_REVIEW"),
                Map.entry(
                        AgentStage2ITestFixtures.Scenario.WARN_EVENT,
                        "WATCH"),
                Map.entry(
                        AgentStage2ITestFixtures.Scenario.MULTI_RISK,
                        "RESEARCH_ONLY"))) {
            AgentTeamRequest request = insertFixedChiefTask(
                    AgentStage2ITestFixtures.request(expected.getKey()));
            AgentTeamResponse response = objectMapper.readValue(
                    forwardToPython(objectMapper.writeValueAsBytes(request)),
                    AgentTeamResponse.class);
            responseValidator.validate(request, response);
            resultPersistence.persist(response, Duration.ofMillis(7));
            assertFixedChiefPersistence(
                    request,
                    response,
                    expected.getValue());
        }
        AgentTeamRequest partialRequest = insertFixedChiefTask(
                AgentStage2ITestFixtures.request(
                        AgentStage2ITestFixtures.Scenario.POSITION_PARTIAL));
        AgentTeamResponse partialResponse = objectMapper.readValue(
                forwardToPython(objectMapper.writeValueAsBytes(partialRequest)),
                AgentTeamResponse.class);
        responseValidator.validate(partialRequest, partialResponse);
        resultPersistence.persist(partialResponse, Duration.ofMillis(7));
        assertFixedChiefPersistence(
                partialRequest,
                partialResponse,
                "RESEARCH_ONLY");
        assertEquals(
                "PARTIAL",
                run(runs(partialRequest.taskId()), "POSITION_RISK")
                        .get("status"));
        assertEquals(businessBefore, businessTableRows());
    }

    private AgentTeamRequest insertFixedChiefTask(AgentTeamRequest fixture) {
        long taskId = jdbc.queryForObject("""
                INSERT INTO agent_tasks(
                    symbol, trade_date, status, context_schema_version,
                    context_snapshot_json, context_generated_at, context_hash,
                    rule_version, execution_mode, trigger_type, requested_by,
                    force_refresh, cache_hit, started_at, created_at, updated_at
                ) VALUES (
                    ?, ?, 'RUNNING', ?, ?::jsonb, ?, ?, ?,
                    'LOCAL_RULES', 'MANUAL', 'stage-2i-fixed-persistence',
                    FALSE, FALSE, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING id
                """,
                Long.class,
                fixture.symbol(),
                fixture.tradeDate(),
                fixture.contextSchemaVersion(),
                writeJson(fixture.contextSnapshot()),
                OffsetDateTime.ofInstant(
                        fixture.requestedAt(),
                        ZoneOffset.UTC),
                fixture.contextHash(),
                ChiefDecisionContracts.RULE_VERSION);
        List<Long> runIds = new ArrayList<>();
        for (AgentCode code : AgentCode.PROFESSIONAL_AGENTS) {
            runIds.add(jdbc.queryForObject("""
                    INSERT INTO agent_runs(
                        task_id, agent_code, attempt_no, status,
                        gate_status, decision, veto,
                        started_at, created_at, updated_at
                    ) VALUES (
                        ?, ?, 1, 'RUNNING', 'NOT_APPLICABLE',
                        'NOT_APPLICABLE', FALSE,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    RETURNING id
                    """, Long.class, taskId, code.name()));
        }
        RunIds ids = new RunIds(
                runIds.get(0),
                runIds.get(1),
                runIds.get(2),
                runIds.get(3),
                runIds.get(4),
                runIds.get(5));
        return new AgentTeamRequest(
                fixture.schemaVersion(),
                taskId,
                ids,
                fixture.symbol(),
                fixture.tradeDate(),
                fixture.contextHash(),
                fixture.contextSchemaVersion(),
                ChiefDecisionContracts.RULE_VERSION,
                fixture.executionMode(),
                fixture.contextSnapshot(),
                fixture.requestedAt());
    }

    private void assertFixedChiefPersistence(
            AgentTeamRequest request,
            AgentTeamResponse response,
            String expectedDecision
    ) throws Exception {
        long taskId = request.taskId();
        assertEquals("COMPLETED", taskStatus(taskId));
        assertSixRuns(runs(taskId));
        Map<String, Object> persisted = decision(taskId);
        assertEquals("COMPLETED", persisted.get("status"));
        assertEquals(expectedDecision, persisted.get("decision"));
        assertEquals(
                response.finalDecision().score(),
                number(persisted, "score"));
        assertEquals(
                response.finalDecision().confidence(),
                number(persisted, "confidence"));
        AgentStage2CReadonlyContextPostgresIntegrationTest
                .assertJsonSemanticallyEquals(
                        objectMapper.valueToTree(response.finalDecision()),
                        readJson(String.valueOf(persisted.get("decision_json"))));
        assertEquals(
                response.finalDecision().sourceRunIds(),
                sqlLongs((Array) persisted.get("source_run_ids")));
        assertEquals(
                response.evidence().size(),
                countForTask("agent_evidence", taskId));
        assertEquals(0, countForTask("agent_vetoes", taskId));
        assertEquals(1, countForTask("agent_decisions", taskId));
        var cached = cacheService.completed(new CacheKey(
                request.symbol(),
                request.tradeDate(),
                request.contextHash(),
                ChiefDecisionContracts.RULE_VERSION,
                ExecutionMode.LOCAL_RULES));
        assertTrue(cached.isPresent());
        assertEquals(taskId, cached.orElseThrow().id());
    }

    private void assertChiefDecision(
            CreatedTask created,
            String expectedDecision,
            String expectedGate,
            int expectedScore,
            int expectedConfidence,
            String expectedTaskStatus
    ) throws Exception {
        long taskId = created.task().id();
        List<Map<String, Object>> persistedRuns = runs(taskId);
        assertEquals(
                expectedTaskStatus,
                taskStatus(taskId),
                () -> "runs=" + persistedRuns
                        + ", decision=" + decision(taskId));
        assertSixRuns(persistedRuns);
        assertFalse(persistedRuns.stream().anyMatch(
                row -> "CHIEF_DECISION".equals(row.get("agent_code"))));

        Map<String, Object> persistedDecision = decision(taskId);
        assertEquals(expectedDecision, persistedDecision.get("decision"));
        assertEquals(expectedGate, persistedDecision.get("gate_status"));
        assertEquals(expectedScore, number(persistedDecision, "score"));
        assertEquals(
                expectedConfidence,
                number(persistedDecision, "confidence"));
        assertEquals(
                !"INSUFFICIENT_DATA".equals(expectedDecision)
                        ? "COMPLETED"
                        : "INSUFFICIENT_DATA",
                persistedDecision.get("status"));

        JsonNode forwarded = FORWARDED_RESPONSES.get(taskId);
        assertNotNull(forwarded);
        assertEquals(6, forwarded.path("agentRuns").size());
        AgentStage2CReadonlyContextPostgresIntegrationTest
                .assertJsonSemanticallyEquals(
                        forwarded.path("finalDecision"),
                        readJson(String.valueOf(
                                persistedDecision.get("decision_json"))));
        AgentStage2CReadonlyContextPostgresIntegrationTest
                .assertJsonSemanticallyEquals(
                        forwarded.path("finalDecision").path("findings"),
                        readJson(String.valueOf(
                                persistedDecision.get("findings_json"))));

        List<Long> runIds = jdbc.queryForList(
                        "SELECT id FROM agent_runs WHERE task_id=? ORDER BY id",
                        Long.class,
                        taskId);
        assertEquals(
                runIds,
                sqlLongs((Array) persistedDecision.get("source_run_ids")));
        List<Long> vetoIds = sqlLongs(
                (Array) persistedDecision.get("veto_ids"));
        if ("REJECTED_BY_VETO".equals(expectedDecision)) {
            assertFalse(vetoIds.isEmpty());
            assertEquals(
                    jdbc.queryForList(
                            "SELECT id FROM agent_vetoes "
                                    + "WHERE task_id=? ORDER BY id",
                            Long.class,
                            taskId),
                    vetoIds);
        } else {
            assertEquals(List.of(), vetoIds);
        }
        assertEquals(
                forwarded.path("evidence").size(),
                countForTask("agent_evidence", taskId));
        assertEquals(
                ChiefDecisionContracts.RULE_VERSION,
                jdbc.queryForObject(
                        "SELECT rule_version FROM agent_decisions WHERE task_id=?",
                        String.class,
                        taskId));
    }

    private void assertChiefCompletedCacheHit(
            CreatedTask completed,
            String symbol
    ) {
        long taskCount = count("SELECT count(*) FROM agent_tasks");
        int pythonCalls = calls(completed.task().id());
        CreatedTask cached = taskService.create(
                chiefRequest(symbol),
                "stage-2i-completed-cache");
        assertFalse(cached.newlyCreated());
        assertEquals(completed.task().id(), cached.task().id());
        assertEquals(pythonCalls, calls(completed.task().id()));
        assertEquals(taskCount, count("SELECT count(*) FROM agent_tasks"));
    }

    private void assertChiefNotInCompletedCache(CreatedTask insufficient) {
        assertTrue(cacheService.completed(new CacheKey(
                insufficient.task().symbol(),
                insufficient.task().tradeDate(),
                insufficient.task().contextHash(),
                insufficient.task().ruleVersion(),
                insufficient.task().executionMode())).isEmpty());
    }

    private void assertChiefTamperedResponseFailsAtomically() {
        Map<String, List<String>> businessBefore = businessTableRows();
        long observationCount = count(
                "SELECT count(*) FROM announcement_observations");
        CreatedTask created = taskService.create(
                chiefRequest(CHIEF_INVALID_SYMBOL),
                "stage-2i-invalid-response");
        long taskId = created.task().id();
        await(
                () -> "FAILED".equals(taskStatus(taskId)),
                "tampered stage 2I response did not reach FAILED");
        assertEquals(1, calls(taskId));
        assertNotNull(FORWARDED_RESPONSES.get(taskId));
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
        assertEquals(businessBefore, businessTableRows());
        assertEquals(observationCount, count(
                "SELECT count(*) FROM announcement_observations"));
    }

    private void assertNoEventPersistence(CreatedTask created) {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        Map<String, Object> announcement = run(runs, "ANNOUNCEMENT_RISK");
        assertEquals("COMPLETED", announcement.get("status"));
        assertEquals("PASS", announcement.get("gate_status"));
        assertEquals("PASS", announcement.get("decision"));
        assertEquals(100, number(announcement, "score"));
        assertEquals(40, number(announcement, "confidence"));
        assertEquals(false, announcement.get("veto"));
        assertEquals(1, evidenceCount(taskId, "QUERY_RESULT"));
        assertEquals(0, evidenceCount(taskId, "SECURITY_EVENT"));
        assertEquals("INSUFFICIENT_DATA", decision(taskId).get("decision"));
        assertEquals(0, countForTask("agent_vetoes", taskId));
    }

    private void assertRiskPersistence(CreatedTask created) {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        Map<String, Object> announcement = run(runs, "ANNOUNCEMENT_RISK");
        assertEquals("COMPLETED", announcement.get("status"));
        assertEquals("WARN", announcement.get("gate_status"));
        assertEquals("WARN", announcement.get("decision"));
        assertEquals(52, number(announcement, "score"));
        assertEquals(40, number(announcement, "confidence"));
        assertEquals(false, announcement.get("veto"));
        assertEquals(2, evidenceCount(taskId, "SECURITY_EVENT"));
        List<Map<String, Object>> eventEvidence = jdbc.queryForList("""
                SELECT source_ref, content_hash, payload_json::text AS payload
                FROM agent_evidence
                WHERE task_id=? AND category='SECURITY_EVENT'
                ORDER BY id
                """, taskId);
        assertEquals(2, eventEvidence.size());
        for (Map<String, Object> evidence : eventEvidence) {
            assertTrue(String.valueOf(evidence.get("source_ref"))
                    .startsWith("contextSnapshot.securityEvents.events."));
            assertTrue(String.valueOf(evidence.get("content_hash"))
                    .matches("^[0-9a-f]{64}$"));
            assertTrue(readJson(String.valueOf(evidence.get("payload")))
                    .path("event").isObject());
        }
        assertEquals("INSUFFICIENT_DATA", decision(taskId).get("decision"));
    }

    private void assertPositionVetoPriority(CreatedTask created) {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        assertEquals(false, run(runs, "ANNOUNCEMENT_RISK").get("veto"));
        Map<String, Object> position = run(runs, "POSITION_RISK");
        assertEquals(true, position.get("veto"));
        assertEquals("BLOCKED", position.get("gate_status"));
        List<Map<String, Object>> vetoes = jdbc.queryForList("""
                SELECT agent_code, veto_code
                FROM agent_vetoes WHERE task_id=? ORDER BY id
                """, taskId);
        assertEquals(1, vetoes.size());
        assertEquals("POSITION_RISK", vetoes.get(0).get("agent_code"));
        assertEquals(
                "POSITION_RISK_POSITION_WEIGHT_LIMIT_" + VETO_SYMBOL,
                vetoes.get(0).get("veto_code"));
        Map<String, Object> decision = decision(taskId);
        assertEquals("REJECTED_BY_VETO", decision.get("decision"));
        assertEquals("BLOCKED", decision.get("gate_status"));
        assertEquals(true, decision.get("vetoed"));
    }

    private void assertDataQualityBlocked(CreatedTask created) {
        long taskId = created.task().id();
        List<Map<String, Object>> runs = runs(taskId);
        assertSixRuns(runs);
        assertEquals(
                "BLOCKED",
                run(runs, "DATA_QUALITY").get("gate_status"));
        Map<String, Object> announcement = run(runs, "ANNOUNCEMENT_RISK");
        assertEquals("INSUFFICIENT_DATA", announcement.get("status"));
        assertEquals("NOT_APPLICABLE", announcement.get("gate_status"));
        assertEquals(0, number(announcement, "score"));
        assertEquals(0, number(announcement, "confidence"));
        assertEquals("BLOCKED_BY_DATA_QUALITY", decision(taskId).get("decision"));
    }

    private void assertTamperedResponseFailsAtomically() {
        Map<String, List<String>> businessBefore = businessTableRows();
        long observationCount = count(
                "SELECT count(*) FROM announcement_observations");
        CreatedTask created = taskService.create(
                request(INVALID_SYMBOL),
                "stage-2g-invalid-response");
        long taskId = created.task().id();
        await(
                () -> "FAILED".equals(taskStatus(taskId)),
                "tampered stage 2G response did not reach FAILED");
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
        assertEquals(businessBefore, businessTableRows());
        assertEquals(observationCount, count(
                "SELECT count(*) FROM announcement_observations"));
    }

    private void assertPersistedContext(CreatedTask created) {
        String persistedJson = jdbc.queryForObject(
                "SELECT context_snapshot_json::text FROM agent_tasks WHERE id=?",
                String.class,
                created.task().id());
        JsonNode persisted = readJson(persistedJson);
        AgentStage2CReadonlyContextPostgresIntegrationTest
                .assertJsonSemanticallyEquals(
                        created.task().contextSnapshot(),
                        persisted);
        assertEquals(created.task().contextHash(), contextHashes.hash(persisted));
        assertEquals(created.task().contextHash(), jdbc.queryForObject(
                "SELECT context_hash FROM agent_tasks WHERE id=?",
                String.class,
                created.task().id()));
    }

    private void prepareMarketData(String... symbols) {
        LocalDate effectiveTradeDate = latestSupportedTradeDate(
                ANALYSIS_DATE.minusDays(1));
        now.set(effectiveTradeDate.atTime(15, 0)
                .atZone(BacktestContracts.MARKET_ZONE).toInstant());
        for (String symbol : symbols) {
            var result = marketDataPersistence.persistBars(
                    symbol,
                    bars(symbol, effectiveTradeDate, 120),
                    SOURCE,
                    "REVISION_1",
                    "TEST_FIXTURE");
            assertEquals(120, result.appendedObservationCount());
            jdbc.update("""
                    UPDATE securities
                    SET name=?, exchange='SSE', board='MAIN', industry='TEST',
                        list_date=DATE '2000-01-01', is_st=false, is_active=true,
                        data_source=?, updated_at=CURRENT_TIMESTAMP
                    WHERE symbol=?
                    """, "Stage2G " + symbol, SOURCE, symbol);
        }
        now.set(QUERY_INSTANT);
    }

    private void prepareChiefMarketData(String... symbols) {
        LocalDate effectiveTradeDate = latestSupportedTradeDate(
                CHIEF_ANALYSIS_DATE);
        now.set(effectiveTradeDate.atTime(15, 0)
                .atZone(BacktestContracts.MARKET_ZONE).toInstant());
        for (String symbol : symbols) {
            var result = marketDataPersistence.persistBars(
                    symbol,
                    bars(symbol, effectiveTradeDate, 500),
                    SOURCE,
                    "REVISION_1",
                    "TEST_FIXTURE");
            assertEquals(500, result.appendedObservationCount());
            jdbc.update("""
                    UPDATE securities
                    SET name=?, exchange='SSE', board='MAIN', industry='TEST',
                        list_date=DATE '2000-01-01', is_st=false, is_active=true,
                        data_source=?, updated_at=CURRENT_TIMESTAMP
                    WHERE symbol=?
                    """, "Stage2I " + symbol, SOURCE, symbol);
        }
        now.set(CHIEF_QUERY_INSTANT);
    }

    private void configureNoRiskAccount() {
        resetBusinessTables();
        updateAccount("100000.00", "0.00");
        updateLimits(5, "0.20");
    }

    private void configureCompleteNoRiskAccount() {
        configureNoRiskAccount();
        insertEquitySnapshot(
                CHIEF_ANALYSIS_DATE.minusDays(2), "100000.00");
        insertEquitySnapshot(
                CHIEF_ANALYSIS_DATE.minusDays(1), "100000.00");
    }

    private void configurePositionVeto(String symbol) {
        resetBusinessTables();
        updateAccount("100000.00", "0.00");
        updateLimits(5, "0.20");
        jdbc.update("""
                INSERT INTO positions(
                    account_id, symbol, quantity, available_quantity,
                    average_cost, last_price, stop_loss, target_price,
                    trailing_stop_pct, highest_price, source_plan_id,
                    opened_at, last_buy_date, updated_at
                ) VALUES (
                    1, ?, 1000, 1000, 80.0000, 100.0000, NULL, NULL,
                    0.0400, 100.0000, NULL,
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP
                )
                """, symbol, ANALYSIS_DATE.minusDays(10));
        insertEquitySnapshot(ANALYSIS_DATE.minusDays(2), "200000.00");
        insertEquitySnapshot(ANALYSIS_DATE.minusDays(1), "200000.00");
    }

    private void configureChiefPositionVeto(String symbol) {
        resetBusinessTables();
        updateAccount("100000.00", "0.00");
        updateLimits(5, "0.20");
        jdbc.update("""
                INSERT INTO positions(
                    account_id, symbol, quantity, available_quantity,
                    average_cost, last_price, stop_loss, target_price,
                    trailing_stop_pct, highest_price, source_plan_id,
                    opened_at, last_buy_date, updated_at
                ) VALUES (
                    1, ?, 1000, 1000, 80.0000, 138.0000, NULL, NULL,
                    0.0400, 138.0000, NULL,
                    CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP
                )
                """, symbol, CHIEF_ANALYSIS_DATE.minusDays(10));
        insertEquitySnapshot(
                CHIEF_ANALYSIS_DATE.minusDays(2), "200000.00");
        insertEquitySnapshot(
                CHIEF_ANALYSIS_DATE.minusDays(1), "200000.00");
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
                SET name='Stage2G simulated account',
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

    private void insertEquitySnapshot(LocalDate date, String totalAsset) {
        BigDecimal total = new BigDecimal(totalAsset);
        BigDecimal totalReturn = total.subtract(new BigDecimal("100000.00"))
                .divide(new BigDecimal("100000.00"), 8,
                        java.math.RoundingMode.HALF_UP);
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

    private ProviderResponse complete(ProviderRequest request) {
        List<ProviderRecord> records = providerRecords.getOrDefault(
                request.symbol(), List.of());
        int chunks = (int) Math.ceil(
                ((double) (request.endDate().toEpochDay()
                        - request.startDate().toEpochDay()) + 1) / 30.0);
        return new ProviderResponse(
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                AnnouncementContracts.AKSHARE_VERSION,
                request.symbol(),
                request.startDate(),
                request.endDate(),
                true,
                chunks,
                chunks,
                records,
                List.of());
    }

    private ProviderRecord record(
            String symbol,
            String id,
            String title,
            LocalDate reportedDate
    ) {
        String url = "https://static.cninfo.com.cn/finalpage/"
                + reportedDate + "/" + id + ".pdf";
        ObjectNode raw = objectMapper.createObjectNode();
        raw.put("代码", symbol);
        raw.put("简称", "测试证券");
        raw.put("公告标题", title);
        raw.put("公告时间", reportedDate.toString());
        raw.put("公告链接", url);
        return new ProviderRecord(
                symbol,
                "测试证券",
                title,
                reportedDate,
                url,
                raw);
    }

    private static CreateAgentTaskRequest request(String symbol) {
        return new CreateAgentTaskRequest(
                symbol,
                ANALYSIS_DATE,
                ExecutionMode.LOCAL_RULES,
                AnnouncementContracts.RULE_VERSION,
                false,
                TriggerType.MANUAL);
    }

    private static CreateAgentTaskRequest chiefRequest(String symbol) {
        return new CreateAgentTaskRequest(
                symbol,
                CHIEF_ANALYSIS_DATE,
                ExecutionMode.LOCAL_RULES,
                ChiefDecisionContracts.RULE_VERSION,
                false,
                TriggerType.MANUAL);
    }

    private List<Map<String, Object>> runs(long taskId) {
        return jdbc.queryForList("""
                SELECT id, agent_code, status, gate_status, decision, score,
                       confidence, veto
                FROM agent_runs WHERE task_id=? ORDER BY id
                """, taskId);
    }

    private static Map<String, Object> run(
            List<Map<String, Object>> runs,
            String code
    ) {
        return runs.stream()
                .filter(value -> code.equals(value.get("agent_code")))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSixRuns(List<Map<String, Object>> runs) {
        assertEquals(6, runs.size());
        assertEquals(
                AGENT_CODES,
                runs.stream()
                        .map(value -> String.valueOf(value.get("agent_code")))
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private int evidenceCount(long taskId, String category) {
        Integer value = jdbc.queryForObject("""
                SELECT count(*) FROM agent_evidence
                WHERE task_id=? AND category=?
                """, Integer.class, taskId, category);
        return value == null ? 0 : value;
    }

    private Map<String, Object> decision(long taskId) {
        return jdbc.queryForMap("""
                SELECT status, decision, gate_status, vetoed, score,
                       confidence, findings_json::text AS findings_json,
                       source_run_ids, veto_ids,
                       decision_json::text AS decision_json
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
                "agent_evidence",
                "agent_vetoes",
                "agent_decisions").contains(table)) {
            throw new IllegalArgumentException("unsupported task table");
        }
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE task_id=?",
                Integer.class,
                taskId);
        return value == null ? 0 : value;
    }

    private static int number(Map<String, Object> value, String field) {
        return ((Number) value.get(field)).intValue();
    }

    private static List<Long> sqlLongs(Array value) throws SQLException {
        if (value == null) return List.of();
        Object raw = value.getArray();
        if (raw instanceof Long[] longs) return List.of(longs);
        if (raw instanceof Object[] values) {
            List<Long> result = new ArrayList<>(values.length);
            for (Object item : values) {
                result.add(((Number) item).longValue());
            }
            return List.copyOf(result);
        }
        throw new SQLException("unsupported SQL array representation");
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (IOException error) {
            throw new AssertionError("persisted JSON cannot be parsed", error);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException error) {
            throw new AssertionError("fixture JSON cannot be serialized", error);
        }
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

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private void assertMigrations() {
        assertEquals(
                "stock_quant_test",
                jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(
                TEST_SCHEMA,
                jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success=TRUE ORDER BY installed_rank
                        """, String.class));
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
        List<LocalDate> result = new ArrayList<>();
        LocalDate candidate = end;
        while (result.size() < count) {
            if (BacktestContracts.isSupportedDailyBarTradeDate(candidate)) {
                result.add(candidate);
            }
            candidate = candidate.minusDays(1);
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private static int calls(long taskId) {
        AtomicInteger result = CALLS.get(taskId);
        return result == null ? 0 : result.get();
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
                    AgentStage2GPostgresPythonIntegrationTest::proxy);
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
                    if ("ANNOUNCEMENT_RISK".equals(
                            run.path("agentCode").asText())) {
                        ((ObjectNode) run).put(
                                "score",
                                run.path("score").asInt() == 100
                                        ? 99 : run.path("score").asInt() + 1);
                    }
                }
            } else if (CHIEF_INVALID_SYMBOL.equals(
                    request.path("symbol").asText())) {
                ObjectNode decision = response.with("finalDecision");
                decision.put(
                        "score",
                        decision.path("score").asInt() == 100
                                ? 99
                                : decision.path("score").asInt() + 1);
            }
            FORWARDED_RESPONSES.put(taskId, response.deepCopy());
            byte[] output = PROXY_MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, output.length);
            exchange.getResponseBody().write(output);
        } catch (Exception error) {
            byte[] output = ("proxy failure: "
                    + error.getClass().getSimpleName())
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
                    WHERE schema_name='%s'
                    """.formatted(TEST_SCHEMA)));
            publicBaseline = publicBaseline(statement);
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2g_team_it schema",
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

    private static PublicBaseline currentPublicBaseline() {
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            return publicBaseline(statement);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static PublicBaseline publicBaseline(Statement statement)
            throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        List<String> tableNames = new ArrayList<>();
        try (ResultSet tables = statement.executeQuery("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            while (tables.next()) tableNames.add(tables.getString(1));
        }
        for (String table : tableNames) {
            rows.put(
                    table,
                    scalar(
                            statement,
                            "SELECT count(*) FROM public.\"" + table + "\""));
        }
        List<String> migrations = new ArrayList<>();
        if (rows.containsKey("flyway_schema_history")) {
            try (ResultSet result = statement.executeQuery("""
                    SELECT version || ':' || coalesce(checksum::text, 'NULL')
                    FROM public.flyway_schema_history
                    ORDER BY installed_rank
                    """)) {
                while (result.next()) migrations.add(result.getString(1));
            }
        }
        return new PublicBaseline(Map.copyOf(rows), List.copyOf(migrations));
    }

    private static long scalar(Statement statement, String sql)
            throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("query returned no row");
            return result.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql)
            throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("query returned no row");
            return result.getString(1);
        }
    }

    private static void requireSafeSchemaName(String schema) {
        if (schema == null || !schema.matches(
                "^stage_2g_team_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe test schema: " + schema);
        }
    }

    private record PublicBaseline(
            Map<String, Long> rowCounts,
            List<String> migrationChecksums
    ) {
    }
}
