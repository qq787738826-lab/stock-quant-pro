package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.shadow.AgentShadowBatchService;
import com.stockquant.server.agent.shadow.AgentShadowContracts;
import com.stockquant.server.agent.shadow.AgentShadowJob;
import com.stockquant.server.agent.shadow.AgentShadowLifecycleService;
import com.stockquant.server.agent.shadow.AgentShadowMetricsService;
import com.stockquant.server.agent.shadow.AgentShadowModels;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.MetricsFilter;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.ReviewLabel;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import com.stockquant.server.agent.shadow.AgentShadowModels.TriggerMode;
import com.stockquant.server.agent.shadow.AgentShadowRepository;
import com.stockquant.server.agent.shadow.AgentShadowReviewService;
import com.stockquant.server.agent.shadow.AgentShadowSelectionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
class AgentStage3A1ShadowPostgresPythonIntegrationTest {

    private static final String SCHEMA_PREFIX = "stage_3a1_shadow_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate TRADE_DATE =
            LocalDate.of(2026, 7, 27);
    private static final Instant NOW = TRADE_DATE.atTime(17, 10)
            .atZone(AgentShadowContracts.MARKET_ZONE).toInstant();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final List<String> SYMBOLS = List.of(
            "600001", "600002", "600003");
    private static final List<String> PROTECTED_BUSINESS_TABLES = List.of(
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

    @Autowired AgentShadowRepository shadowRepository;
    @Autowired AgentShadowSelectionService selectionService;
    @Autowired AgentShadowBatchService batchService;
    @Autowired AgentShadowLifecycleService lifecycleService;
    @Autowired AgentShadowReviewService reviewService;
    @Autowired AgentShadowMetricsService metricsService;
    @Autowired AgentTaskRepository taskRepository;
    @Autowired AgentRunRepository runRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationContext applicationContext;
    @MockBean(name = "agentTemporalClock") Clock agentTemporalClock;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        credentials = AgentPostgresTestEnvironment.validate(
                System.getenv("STOCK_QUANT_TEST_DB_URL"),
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME"),
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD"));
        createIsolatedSchema();
        registry.add("spring.datasource.url",
                () -> schemaUrl(TEST_SCHEMA));
        registry.add("spring.datasource.username",
                credentials::username);
        registry.add("spring.datasource.password",
                credentials::password);
        registry.add("spring.flyway.default-schema",
                () -> TEST_SCHEMA);
        registry.add("spring.flyway.schemas", () -> TEST_SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> false);
        registry.add("stockquant.agent-team.base-url", () ->
                AgentPythonSmokeEnvironment.validate(
                        System.getenv("STOCK_QUANT_PYTHON_BASE_URL")));
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
                () -> 2);
    }

    @BeforeEach
    void freezeClock() {
        when(agentTemporalClock.instant()).thenReturn(NOW);
        when(agentTemporalClock.getZone()).thenReturn(
                ZoneId.of("Asia/Shanghai"));
    }

    @AfterAll
    static void dropSchemaAndVerifyPublic() throws Exception {
        if (!schemaCreated) {
            return;
        }
        requireSafeSchemaName(TEST_SCHEMA);
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "DROP SCHEMA \"" + TEST_SCHEMA + "\" CASCADE");
            schemaCreated = false;
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='%s'
                    """.formatted(TEST_SCHEMA)));
            assertEquals(publicBaseline, publicBaseline(statement));
        }
    }

    @Test
    void closesV11LifecycleRealTaskCacheDriftReviewAndReadOnlyGate() {
        assertV11AndDatabaseProtection();
        assertTrue(applicationContext
                .getBeansOfType(AgentShadowJob.class).isEmpty(),
                "scheduler bean must remain absent while disabled");

        Map<String, String> businessBefore = tableFingerprints();
        var first = batchService.createManual(
                TRADE_DATE,
                SelectionMode.EXPLICIT,
                List.of("600003", "600001", "600002", "600001"),
                10,
                "stage-3a1-integration");
        var firstTerminal = awaitBatch(first.id());

        assertEquals(BatchStatus.COMPLETED, firstTerminal.status());
        assertEquals(3, firstTerminal.selectedCount());
        assertEquals(3, firstTerminal.launchedCount());
        assertEquals(3, firstTerminal.terminalCount());
        assertEquals(0, firstTerminal.failedCount());
        assertEquals(0, firstTerminal.cacheHitCount());
        var firstItems = batchService.items(first.id());
        assertEquals(SYMBOLS,
                firstItems.stream().map(value -> value.symbol()).toList());
        for (var item : firstItems) {
            assertTrue(item.terminal());
            assertTrue(item.outcomeClass() == OutcomeClass.DETERMINED
                    || item.outcomeClass() == OutcomeClass.INSUFFICIENT);
            assertNotNull(item.agentTaskId());
            var task = taskRepository.findById(
                    item.agentTaskId()).orElseThrow();
            assertEquals(TriggerType.SHADOW, task.triggerType());
            assertEquals(
                    "shadow:" + first.id(), task.requestedBy());
            assertFalse(task.forceRefresh());
            assertEquals(AgentShadowContracts.RULE_VERSION,
                    task.ruleVersion());
            assertEquals(6,
                    runRepository.findByTaskId(task.id()).size());
            if (item.outcomeClass() == OutcomeClass.INSUFFICIENT) {
                assertNotNull(item.primaryReasonCode());
                assertTrue(item.reasonCodes().size() >= 1);
            }
        }
        assertEquals(businessBefore, tableFingerprints());

        var second = batchService.createManual(
                TRADE_DATE,
                SelectionMode.EXPLICIT,
                SYMBOLS,
                10,
                "stage-3a1-integration-repeat");
        var secondTerminal = awaitBatch(second.id());
        assertEquals(BatchStatus.COMPLETED, secondTerminal.status());
        assertEquals(3, secondTerminal.cacheHitCount());
        var secondItems = batchService.items(second.id());
        Map<String, Long> firstTasks = new LinkedHashMap<>();
        firstItems.forEach(item ->
                firstTasks.put(item.symbol(), item.agentTaskId()));
        for (var item : secondItems) {
            assertTrue(item.cacheHit());
            assertFalse(item.taskNewlyCreated());
            assertEquals(firstTasks.get(item.symbol()),
                    item.agentTaskId());
            assertNotNull(item.previousItemId());
            assertFalse(item.contextChanged());
            assertFalse(item.decisionChanged());
            assertEquals(0, item.scoreDelta());
            assertEquals(0, item.confidenceDelta());
            assertTrue(item.changedAgents().isArray());
            assertEquals(0, item.changedAgents().size());
        }

        var reviewed = secondItems.get(0);
        var review = reviewService.add(
                reviewed.id(),
                ReviewLabel.EXPECTED,
                "就绪度不足或确定结果符合冻结输入事实。",
                "stage-3a1-reviewer",
                null);
        var correction = reviewService.add(
                reviewed.id(),
                ReviewLabel.NEEDS_FOLLOW_UP,
                "追加复核，不改写原始Agent结论。",
                "stage-3a1-reviewer",
                review.id());
        assertEquals(review.id(), correction.supersedesReviewId());
        assertEquals(2, reviewService.reviews(reviewed.id()).size());

        var metrics = metricsService.metrics(new MetricsFilter(
                null,
                null,
                AgentShadowContracts.RULE_VERSION,
                second.id(),
                null));
        assertEquals(AgentShadowContracts.METRICS_VERSION,
                metrics.contractVersion());
        assertEquals(1, metrics.batchCount());
        assertEquals(3, metrics.itemCount());
        assertEquals(3, metrics.cacheHitCount());
        assertEquals(1.0, metrics.cacheHitRate());
        assertEquals(3, metrics.unreviewedItemCount() + 1);
        assertEquals(1,
                metrics.reviewLabelDistribution().get("EXPECTED"));
        assertEquals(1,
                metrics.reviewLabelDistribution()
                        .get("NEEDS_FOLLOW_UP"));
        assertEquals(3,
                metricsService.drift(new MetricsFilter(
                        null,
                        null,
                        AgentShadowContracts.RULE_VERSION,
                        second.id(),
                        null)).size());

        assertEquals(businessBefore, tableFingerprints());
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private void assertV11AndDatabaseProtection() {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6",
                        "7", "8", "9", "10", "11", "12"),
                jdbc.queryForList("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = TRUE
                        ORDER BY installed_rank
                        """, String.class));
        String triggerConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = 'ck_agent_tasks_trigger_type'
                  AND connamespace = current_schema()::regnamespace
                """, String.class);
        assertNotNull(triggerConstraint);
        for (String trigger : List.of(
                "MANUAL", "SCAN_CANDIDATE", "SCHEDULED",
                "RETRY", "SHADOW")) {
            assertTrue(triggerConstraint.contains(trigger));
        }
        assertEquals(3, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name IN (
                    'agent_shadow_batches',
                    'agent_shadow_items',
                    'agent_shadow_reviews'
                  )
                """, Integer.class));

        var selection = selectionService.select(
                SelectionMode.EXPLICIT,
                List.of("699999"),
                1,
                TRADE_DATE);
        var batch = shadowRepository.insertBatch(
                BatchStatus.QUEUED,
                TriggerMode.MANUAL,
                TRADE_DATE,
                SelectionMode.EXPLICIT,
                selection.selectionHash(),
                1,
                1,
                objectMapper.createObjectNode(),
                null,
                null,
                null,
                "database-protection-test");
        shadowRepository.insertItems(batch.id(), selection.entries());
        var item = shadowRepository.findItems(batch.id()).get(0);

        assertThrows(DataAccessException.class, () ->
                shadowRepository.insertBatch(
                        BatchStatus.QUEUED,
                        TriggerMode.MANUAL,
                        TRADE_DATE,
                        SelectionMode.EXPLICIT,
                        "f".repeat(64),
                        1,
                        0,
                        objectMapper.createObjectNode(),
                        null,
                        null,
                        null,
                        "overlap-test"));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("""
                        INSERT INTO agent_shadow_reviews (
                            batch_id, item_id, review_contract_version,
                            label, note, reviewer
                        ) VALUES (?, ?, ?, 'EXPECTED', 'too early', 'test')
                        """,
                        batch.id(),
                        item.id(),
                        AgentShadowContracts.REVIEW_VERSION));

        batchService.cancel(batch.id());
        lifecycleService.start(batch.id());
        lifecycleService.cancelUnstarted(batch.id());
        var cancelled = lifecycleService.finish(batch.id(), null);
        assertEquals(BatchStatus.CANCELLED, cancelled.status());
        var cancelledItem =
                shadowRepository.findItems(batch.id()).get(0);
        assertEquals(OutcomeClass.CANCELLED,
                cancelledItem.outcomeClass());
        assertEquals(TaskStatus.CANCELLED,
                cancelledItem.taskStatus());

        var review = reviewService.add(
                cancelledItem.id(),
                ReviewLabel.EXPECTED,
                "取消语义符合预期。",
                "database-reviewer",
                null);
        var correction = reviewService.add(
                cancelledItem.id(),
                ReviewLabel.UNEXPECTED,
                "追加更正记录。",
                "database-reviewer",
                review.id());
        assertEquals(review.id(), correction.supersedesReviewId());

        assertThrows(DataAccessException.class, () ->
                jdbc.update("""
                        UPDATE agent_shadow_batches
                        SET error_message = 'mutated'
                        WHERE id = ?
                        """, batch.id()));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("""
                        UPDATE agent_shadow_items
                        SET error_message = 'mutated'
                        WHERE id = ?
                        """, cancelledItem.id()));
        assertThrows(DataAccessException.class, () ->
                shadowRepository.insertItems(
                        batch.id(),
                        List.of(new AgentShadowModels.SelectionEntry(
                                2,
                                "699998",
                                SelectionSource.EXPLICIT,
                                "explicit:symbol=699998"))));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("""
                        UPDATE agent_shadow_reviews
                        SET note = 'mutated'
                        WHERE id = ?
                        """, review.id()));
        assertThrows(DataAccessException.class, () ->
                jdbc.update(
                        "DELETE FROM agent_shadow_reviews WHERE id = ?",
                        review.id()));
        assertThrows(DataAccessException.class, () ->
                jdbc.execute("TRUNCATE agent_shadow_reviews"));
    }

    private AgentShadowModels.ShadowBatch awaitBatch(long batchId) {
        waitUntil(() -> batchService.batch(batchId).status().terminal());
        return batchService.batch(batchId);
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }
        throw new AssertionError("shadow batch did not become terminal");
    }

    private Map<String, String> tableFingerprints() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String table : PROTECTED_BUSINESS_TABLES) {
            values.put(table, jdbc.queryForObject(
                    """
                    SELECT md5(COALESCE(
                        string_agg(row_value, E'\\n' ORDER BY row_value),
                        ''
                    ))
                    FROM (
                        SELECT to_jsonb(t)::text AS row_value
                        FROM %s t
                    ) facts
                    """.formatted(quoteIdentifier(table)),
                    String.class));
        }
        return Map.copyOf(values);
    }

    private static void createIsolatedSchema() {
        requireSafeSchemaName(TEST_SCHEMA);
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test", scalarText(
                    statement, "SELECT current_database()"));
            assertEquals("stock_quant_test", scalarText(
                    statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, """
                    SELECT count(*) FROM information_schema.schemata
                    WHERE schema_name='%s'
                    """.formatted(TEST_SCHEMA)));
            publicBaseline = publicBaseline(statement);
            statement.execute(
                    "CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated 3A-1 schema",
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
        return credentials.url() + separator
                + "currentSchema=" + schema;
    }

    private static PublicBaseline currentPublicBaseline() {
        try (Connection connection = controlConnection();
             Statement statement = connection.createStatement()) {
            return publicBaseline(statement);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "could not read public schema baseline", error);
        }
    }

    private static PublicBaseline publicBaseline(Statement statement)
            throws Exception {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema='public'
                  AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            rows.put(table, scalar(statement,
                    "SELECT count(*) FROM public."
                            + quoteAnyIdentifier(table)));
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
                ) facts
                ORDER BY kind, identity
                """);
        List<String> history = strings(statement, """
                SELECT installed_rank || ':'
                       || coalesce(version,'') || ':'
                       || coalesce(checksum::text,'') || ':' || success
                FROM public.flyway_schema_history
                ORDER BY installed_rank
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
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return List.copyOf(values);
    }

    private static long scalar(
            Statement statement,
            String sql
    ) throws SQLException {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new IllegalStateException(
                        "scalar query returned no row");
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
                throw new IllegalStateException(
                        "scalar query returned no row");
            }
            return row.getString(1);
        }
    }

    private static String quoteIdentifier(String value) {
        if (!PROTECTED_BUSINESS_TABLES.contains(value)) {
            throw new IllegalArgumentException(
                    "unexpected table for fingerprint");
        }
        return quoteAnyIdentifier(value);
    }

    private static String quoteAnyIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void requireSafeSchemaName(String schema) {
        if (!schema.matches(
                "^stage_3a1_shadow_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException(
                    "unsafe temporary schema name");
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
