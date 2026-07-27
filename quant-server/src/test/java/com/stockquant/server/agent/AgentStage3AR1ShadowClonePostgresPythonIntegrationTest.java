package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.shadow.AgentShadowBatchService;
import com.stockquant.server.agent.shadow.AgentShadowContracts;
import com.stockquant.server.agent.shadow.AgentShadowJob;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = QuantServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage3AR1ShadowClonePostgresPythonIntegrationTest {

    private static final String PREFIX = "stage_3ar1_shadow_clone_";
    private static final String TEST_SCHEMA = PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate TRADE_DATE =
            LocalDate.of(2026, 7, 27);
    private static final Instant NOW = TRADE_DATE.atTime(17, 10)
            .atZone(AgentShadowContracts.MARKET_ZONE).toInstant();
    private static final String SYMBOL = "600001";
    private static final Duration WAIT_TIMEOUT =
            Duration.ofSeconds(60);
    private static final List<String> PROTECTED_TABLES = List.of(
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
            "announcement_observations");

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AgentShadowBatchService batchService;
    @Autowired AgentRunRepository runRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext applicationContext;
    @MockBean(name = "agentTemporalClock") Clock agentTemporalClock;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        credentials = AgentPostgresTestEnvironment.validate(
                System.getenv("STOCK_QUANT_TEST_DB_URL"),
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME"),
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD"));
        createAndMigrateAppliedV6Clone();
        registry.add("spring.datasource.url",
                () -> schemaUrl(TEST_SCHEMA));
        registry.add("spring.datasource.username",
                credentials::username);
        registry.add("spring.datasource.password",
                credentials::password);
        registry.add("spring.flyway.default-schema",
                () -> TEST_SCHEMA);
        registry.add("spring.flyway.schemas",
                () -> TEST_SCHEMA);
        registry.add("spring.flyway.create-schemas",
                () -> false);
        registry.add("stockquant.agent-team.base-url", () ->
                AgentPythonSmokeEnvironment.validate(
                        System.getenv(
                                "STOCK_QUANT_PYTHON_BASE_URL")));
        registry.add("stockquant.agent-team.shadow.enabled",
                () -> true);
        registry.add(
                "stockquant.agent-team.shadow.scheduler-enabled",
                () -> false);
        registry.add(
                "stockquant.agent-team.shadow.poll-interval",
                () -> "100ms");
        registry.add(
                "stockquant.agent-team.shadow.item-timeout",
                () -> "45s");
        registry.add(
                "stockquant.agent-team.shadow.max-concurrency",
                () -> 1);
        registry.add("quant.jobs.daily-scan-cron", () -> "-");
        registry.add("quant.jobs.portfolio-risk-cron", () -> "-");
        registry.add("stockquant.announcement.akshare.enabled",
                () -> false);
    }

    @BeforeEach
    void freezeClock() {
        when(agentTemporalClock.instant()).thenReturn(NOW);
        when(agentTemporalClock.getZone()).thenReturn(
                ZoneId.of("Asia/Shanghai"));
    }

    @AfterAll
    static void dropCloneAndVerifyPublic() throws Exception {
        if (!schemaCreated) {
            return;
        }
        requireSafeSchema(TEST_SCHEMA);
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA "
                    + quote(TEST_SCHEMA) + " CASCADE");
            schemaCreated = false;
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='%s'
                    """.formatted(TEST_SCHEMA)));
        }
        assertEquals(publicBaseline, readPublicBaseline());
    }

    @Test
    void startsAppliedV6CloneAndRunsOneControlledShadowItem()
            throws Exception {
        assertTrue(applicationContext
                .getBeansOfType(AgentShadowJob.class).isEmpty(),
                "shadow scheduler must remain disabled");
        mockMvc.perform(get("/api/agent-team/shadow/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.schedulerEnabled")
                        .value(false));
        assertEquals(3, jdbc.queryForObject("""
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema=current_schema()
                  AND table_name IN (
                    'agent_shadow_batches',
                    'agent_shadow_items',
                    'agent_shadow_reviews')
                """, Integer.class));

        Map<String, String> before = protectedFingerprints();
        String response = mockMvc.perform(
                        post("/api/agent-team/shadow/batches")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "tradeDate":"2026-07-27",
                                          "selectionMode":"EXPLICIT",
                                          "explicitSymbols":["600001"],
                                          "maxSymbols":1,
                                          "createdBy":"3ar1-clone-control"
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.selectedCount").value(1))
                .andReturn().getResponse().getContentAsString();
        long batchId = objectMapper.readTree(response)
                .path("data").path("id").asLong();
        assertTrue(batchId > 0);

        var terminal = awaitTerminal(batchId);
        assertTrue(terminal.status() == BatchStatus.COMPLETED
                || terminal.status() == BatchStatus.PARTIAL);
        assertEquals(1, terminal.selectedCount());
        assertEquals(1, terminal.launchedCount());
        assertEquals(1, terminal.terminalCount());
        assertEquals(0, terminal.failedCount());
        var items = batchService.items(batchId);
        assertEquals(1, items.size());
        var item = items.get(0);
        assertEquals(SYMBOL, item.symbol());
        assertNotNull(item.agentTaskId());
        assertTrue(item.outcomeClass() == OutcomeClass.DETERMINED
                || item.outcomeClass() == OutcomeClass.INSUFFICIENT);
        assertEquals(6,
                runRepository.findByTaskId(item.agentTaskId()).size());
        assertEquals(before, protectedFingerprints());
    }

    private ShadowBatch awaitTerminal(long batchId) {
        long deadline = System.nanoTime()
                + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var batch = batchService.batch(batchId);
            if (batch.status().terminal()) {
                return batch;
            }
            LockSupport.parkNanos(
                    Duration.ofMillis(100).toNanos());
        }
        throw new AssertionError(
                "shadow clone batch did not become terminal");
    }

    private Map<String, String> protectedFingerprints() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : PROTECTED_TABLES) {
            result.put(table, jdbc.queryForObject("""
                    SELECT count(*) || ':' || md5(coalesce(
                        string_agg(row_value, E'\\n' ORDER BY row_value),
                        ''))
                    FROM (
                        SELECT to_jsonb(t)::text row_value
                        FROM %s t
                    ) facts
                    """.formatted(quote(table)), String.class));
        }
        return Map.copyOf(result);
    }

    private static void createAndMigrateAppliedV6Clone() {
        requireSafeSchema(TEST_SCHEMA);
        try {
            publicBaseline = readPublicBaseline();
        } catch (Exception error) {
            throw new IllegalStateException(
                    "could not read the public PostgreSQL "
                            + "test baseline", error);
        }
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test",
                    scalarText(statement, "SELECT current_database()"));
            assertEquals("stock_quant_test",
                    scalarText(statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='%s'
                    """.formatted(TEST_SCHEMA)));
            statement.execute("CREATE SCHEMA "
                    + quote(TEST_SCHEMA));
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "could not create 3A-R1 shadow clone", error);
        }

        Flyway targetV6 = flyway("6");
        targetV6.migrate();
        var validation = targetV6.validateWithResult();
        if (!validation.validationSuccessful) {
            throw new IllegalStateException(
                    validation.getAllErrorMessages());
        }
        flyway(null).migrate();
    }

    private static Flyway flyway(String target) {
        requireSafeSchema(TEST_SCHEMA);
        var configuration = Flyway.configure()
                .dataSource(schemaUrl(TEST_SCHEMA),
                        credentials.username(),
                        credentials.password())
                .locations("classpath:db/migration")
                .defaultSchema(TEST_SCHEMA)
                .schemas(TEST_SCHEMA)
                .createSchemas(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection controlConnection()
            throws SQLException {
        return DriverManager.getConnection(
                credentials.url(), credentials.username(),
                credentials.password());
    }

    private static String schemaUrl(String schema) {
        requireSafeSchema(schema);
        String separator = credentials.url().contains("?")
                ? "&" : "?";
        return credentials.url() + separator
                + "currentSchema=" + schema;
    }

    private static PublicBaseline readPublicBaseline()
            throws SQLException {
        try (Connection connection = controlConnection()) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                assertEquals("public",
                        scalarText(statement, "SELECT current_schema()"));
                return publicBaseline(statement);
            }
        }
    }

    private static PublicBaseline publicBaseline(
            Statement statement
    ) throws SQLException {
        Map<String, String> tables = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema='public'
                  AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            tables.put(table, scalarText(statement, """
                    SELECT count(*) || ':' || md5(coalesce(
                        string_agg(row_value, E'\\n' ORDER BY row_value),
                        ''))
                    FROM (
                        SELECT to_jsonb(t)::text row_value
                        FROM public.%s t
                    ) facts
                    """.formatted(quote(table))));
        }
        return new PublicBaseline(
                Map.copyOf(tables),
                strings(statement, """
                        SELECT installed_rank || ':'
                               || coalesce(version, '') || ':'
                               || coalesce(checksum::text, '') || ':'
                               || success
                        FROM public.flyway_schema_history
                        ORDER BY installed_rank
                        """));
    }

    private static List<String> strings(
            Statement statement,
            String sql
    ) throws SQLException {
        var values = new java.util.ArrayList<String>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private static int scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    private static String scalarText(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private static void requireSafeSchema(String schema) {
        if (!schema.matches(
                "^stage_3ar1_shadow_clone_[0-9a-f]{32}$")) {
            throw new IllegalStateException(
                    "unsafe 3A-R1 shadow clone schema");
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private record PublicBaseline(
            Map<String, String> tableRows,
            List<String> flywayHistory
    ) {
    }
}
