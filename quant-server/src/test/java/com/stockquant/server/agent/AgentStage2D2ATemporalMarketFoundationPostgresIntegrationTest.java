package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.temporal.MarketExchange;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract.SecurityStatusState;
import com.stockquant.server.agent.temporal.TemporalDataConflictException;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import com.stockquant.server.agent.temporal.TemporalModels;
import com.stockquant.server.agent.temporal.TemporalModels.AppendSecurityStatusEventCommand;
import com.stockquant.server.agent.temporal.TemporalModels.AppendTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.CorrectSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.CorrectTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusEvent;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusVersion;
import com.stockquant.server.agent.temporal.TemporalModels.TradingCalendarRevision;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;
import com.stockquant.server.agent.temporal.TradingSessionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@Transactional
@Rollback
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentStage2D2ATemporalMarketFoundationPostgresIntegrationTest {

    private static final String SCHEMA_PREFIX = "stage_2d2a_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static Map<String, Long> publicBaseline;

    private static final String SOURCE = "TEST_FIXTURE_STAGE_2D2A";
    private static final String SOURCE_VERSION = "stage-2d2a-v1";
    private static final String CONNECTOR_VERSION = "test-connector-v1";
    private static final String SYMBOL = "699991";
    private static final String CONCURRENT_SYMBOL = "699992";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final LocalDate RANGE_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate RANGE_END = LocalDate.of(2025, 12, 31);
    private static final Instant KNOWLEDGE_1 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant KNOWLEDGE_2 = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant KNOWLEDGE_3 = Instant.parse("2025-03-01T00:00:00Z");
    private static final List<String> BASELINE_TABLES = List.of(
            "market_data_dataset_versions", "security_status_events",
            "security_status_history", "trading_calendar_revisions",
            "securities", "daily_bars", "agent_tasks", "agent_runs",
            "agent_evidence", "agent_vetoes", "agent_decisions"
    );

    @Autowired JdbcTemplate jdbc;
    @Autowired TemporalMarketFoundationService temporal;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    private Map<String, Long> baseline;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        credentials = AgentPostgresTestEnvironment.validate(
                System.getenv("STOCK_QUANT_TEST_DB_URL"),
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME"),
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD"));
        createIsolatedSchema();
        String schemaUrl = credentials.url() + "?currentSchema=" + TEST_SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", credentials::username);
        registry.add("spring.datasource.password", credentials::password);
        registry.add("spring.flyway.default-schema", () -> TEST_SCHEMA);
        registry.add("spring.flyway.schemas", () -> TEST_SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> false);
    }

    @AfterAll
    static void dropIsolatedSchemaAndVerifyPublicUnchanged() throws Exception {
        requireSafeSchemaName();
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + TEST_SCHEMA + "\" CASCADE");
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            assertEquals(publicBaseline, publicCounts(statement));
        }
    }

    private static void createIsolatedSchema() {
        requireSafeSchemaName();
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_database()"));
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            publicBaseline = publicCounts(statement);
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            assertEquals(1, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2d2a_it_ schema creation", error);
        }
    }

    private static Connection controlConnection() throws SQLException {
        return DriverManager.getConnection(
                credentials.url(), credentials.username(), credentials.password());
    }

    private static void requireSafeSchemaName() {
        if (!TEST_SCHEMA.matches("^stage_2d2a_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe temporary schema name");
        }
    }

    private static Map<String, Long> publicCounts(Statement statement) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : List.of("securities", "daily_bars", "agent_tasks", "agent_runs",
                "agent_evidence", "agent_vetoes", "agent_decisions")) {
            result.put(table, scalar(statement, "SELECT count(*) FROM public." + table));
        }
        result.put("flyway_schema_history", scalar(statement,
                "SELECT count(*) FROM public.flyway_schema_history"));
        return result;
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("scalar query returned no row");
            return result.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new IllegalStateException("scalar query returned no row");
            return result.getString(1);
        }
    }

    @AfterTransaction
    void verifyRollbackRestoredBaseline() {
        if (baseline != null) {
            assertEquals(baseline, counts());
        }
    }

    @Test
    @Order(1)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void establishesIdempotentBitemporalFactsAndAsOfCalendarWithoutLegacySideEffects() {
        assertDedicatedDatabaseAndMigration();
        assertSchemaProtection();
        baseline = counts();
        assertFixtureNamespaceAvailable();
        assertEquals(0, countBySource("market_data_dataset_versions"));
        assertEquals(0, countBySource("security_status_events"));
        assertEquals(0, countBySource("security_status_history"));
        assertEquals(0, countBySource("trading_calendar_revisions"));

        var datasetCommand = datasetCommand();
        var dataset = temporal.registerDatasetVersion(datasetCommand);
        assertEquals(dataset.id(), temporal.registerDatasetVersion(datasetCommand).id());
        assertEquals(1, countBySource("market_data_dataset_versions"));

        var malformedPayload = SecurityStatusEventPayloadContract.payload(
                new SecurityStatusState(MarketExchange.SSE, "MAIN", true, true, false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformedPayload.get("resultingState"))
                .remove("isSt");
        assertThrows(TemporalDataConflictException.class,
                () -> temporal.appendSecurityStatusEvent(new AppendSecurityStatusEventCommand(
                        dataset.id(), SYMBOL, SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                        RANGE_START, null, KNOWLEDGE_1.minusSeconds(60), KNOWLEDGE_1,
                        SOURCE, SOURCE_VERSION, "malformed", "1", TemporalTrustLevel.OBSERVED,
                        malformedPayload, HASH_A, null)));
        assertEquals(0, countBySource("security_status_events"));

        SecurityStatusEvent firstEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "status-1", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, null, dataset.id()
        ));
        assertEquals(firstEvent.id(), temporal.appendSecurityStatusEvent(statusEvent(
                "status-1", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, null, dataset.id()
        )).id());
        assertThrows(TemporalDataConflictException.class,
                () -> temporal.appendSecurityStatusEvent(statusEvent(
                        "status-1", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                        RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                        true, TemporalTrustLevel.OBSERVED, null, dataset.id()
                )));
        expectIndependentDatabaseFailure("55000", "security_status_events",
                "UPDATE security_status_events SET payload=payload WHERE id=?", firstEvent.id());
        expectIndependentDatabaseFailure("55000", "security_status_events",
                "DELETE FROM security_status_events WHERE id=?", firstEvent.id());
        expectIndependentDatabaseFailure("0A000", "security_status_events",
                "TRUNCATE security_status_events");
        expectIndependentDatabaseFailure("55000", "market_data_dataset_versions",
                "UPDATE market_data_dataset_versions SET metadata=metadata WHERE id=?", dataset.id());
        expectIndependentDatabaseFailure("55000", "market_data_dataset_versions",
                "DELETE FROM market_data_dataset_versions WHERE id=?", dataset.id());
        expectIndependentDatabaseFailure("0A000", "market_data_dataset_versions",
                "TRUNCATE market_data_dataset_versions");
        assertEquals(firstEvent.payloadHash(), jdbc.queryForObject(
                "SELECT payload_hash FROM security_status_events WHERE id=?",
                String.class, firstEvent.id()));

        SecurityStatusEvent secondEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "status-2", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                LocalDate.of(2025, 6, 1), null, KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, null, dataset.id()
        ));
        SecurityStatusVersion firstVersion = temporal.publishSecurityStatusVersion(statusVersion(
                firstEvent, RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, dataset.id()
        ));
        SecurityStatusVersion secondVersion = temporal.publishSecurityStatusVersion(statusVersion(
                secondEvent, LocalDate.of(2025, 6, 1), null, KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, dataset.id()
        ));
        assertThrows(TemporalDataConflictException.class,
                () -> temporal.publishSecurityStatusVersion(statusVersion(
                        secondEvent, LocalDate.of(2025, 6, 1), null, KNOWLEDGE_1,
                        true, TemporalTrustLevel.OBSERVED, dataset.id())));
        assertEquals(firstVersion.id(), temporal.publishSecurityStatusVersion(statusVersion(
                firstEvent, RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, dataset.id()
        )).id());
        assertNotEquals(firstVersion.statusHash(), secondVersion.statusHash());
        assertEquals(firstVersion.id(), temporal.findSecurityStatusAsOf(
                SYMBOL, LocalDate.of(2025, 5, 31), KNOWLEDGE_1.plusSeconds(1)
        ).orElseThrow().version().id());
        assertEquals(secondVersion.id(), temporal.findSecurityStatusAsOf(
                SYMBOL, LocalDate.of(2025, 6, 1), KNOWLEDGE_1.plusSeconds(1)
        ).orElseThrow().version().id());

        SecurityStatusEvent correctedEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "status-2", "2", SecurityStatusEventType.ST_CHANGE,
                LocalDate.of(2025, 6, 1), null, KNOWLEDGE_2,
                true, TemporalTrustLevel.BACKFILLED_INFERRED, secondEvent.id(), dataset.id()
        ));
        SecurityStatusVersion corrected = temporal.correctSecurityStatusVersion(
                new CorrectSecurityStatusVersionCommand(secondVersion.id(), statusVersion(
                        correctedEvent, LocalDate.of(2025, 6, 1), null, KNOWLEDGE_2,
                        true, TemporalTrustLevel.BACKFILLED_INFERRED, dataset.id()
                ))
        );
        assertEquals(corrected.id(), temporal.correctSecurityStatusVersion(
                new CorrectSecurityStatusVersionCommand(secondVersion.id(), statusVersion(
                        correctedEvent, LocalDate.of(2025, 6, 1), null, KNOWLEDGE_2,
                        true, TemporalTrustLevel.BACKFILLED_INFERRED, dataset.id()
                ))
        ).id());
        assertEquals(secondVersion.id(), temporal.findSecurityStatusAsOf(
                SYMBOL, LocalDate.of(2025, 7, 1), KNOWLEDGE_2.minusSeconds(1)
        ).orElseThrow().version().id());
        var correctedAsOf = temporal.findSecurityStatusAsOf(
                SYMBOL, LocalDate.of(2025, 7, 1), KNOWLEDGE_2
        ).orElseThrow();
        assertEquals(corrected.id(), correctedAsOf.version().id());
        assertTrue(correctedAsOf.version().st());
        assertFalse(correctedAsOf.trustAssessment().pointInTimeCandidate());
        expectIndependentDatabaseFailure("55000", "security_status_history",
                "UPDATE security_status_history SET board='OTHER' WHERE id=?", corrected.id());
        expectIndependentDatabaseFailure("55000", "security_status_history",
                "UPDATE security_status_history SET known_to=NULL WHERE id=?", secondVersion.id());
        expectIndependentDatabaseFailure("55000", "security_status_history",
                "UPDATE security_status_history SET known_to=? WHERE id=?",
                java.time.OffsetDateTime.ofInstant(KNOWLEDGE_3, ZoneOffset.UTC), secondVersion.id());
        expectIndependentDatabaseFailure("55000", "security_status_history",
                "DELETE FROM security_status_history WHERE id=?", corrected.id());
        expectIndependentDatabaseFailure("55000", "security_status_history",
                "TRUNCATE security_status_history");

        SecurityStatusEvent overlapEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "overlap", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 7, 1), KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, null, dataset.id()
        ));
        long historiesBeforeOverlap = tableCount("security_status_history");
        assertThrows(DataAccessException.class,
                () -> temporal.publishSecurityStatusVersion(statusVersion(
                        overlapEvent, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 7, 1),
                        KNOWLEDGE_1, false, TemporalTrustLevel.OBSERVED, dataset.id()
                )));
        assertEquals(historiesBeforeOverlap, tableCount("security_status_history"));

        SecurityStatusEvent atomicEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "atomic", "1", SecurityStatusEventType.ST_CHANGE,
                RANGE_START, LocalDate.of(2025, 8, 1), KNOWLEDGE_3,
                true, TemporalTrustLevel.OBSERVED, firstEvent.id(), dataset.id()
        ));
        assertThrows(DataAccessException.class,
                () -> temporal.correctSecurityStatusVersion(
                        new CorrectSecurityStatusVersionCommand(firstVersion.id(), statusVersion(
                                atomicEvent, RANGE_START, LocalDate.of(2025, 8, 1), KNOWLEDGE_3,
                                true, TemporalTrustLevel.OBSERVED, dataset.id()
                        ))
                ));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_history WHERE id=? AND known_to IS NOT NULL",
                Integer.class, firstVersion.id()));

        exerciseTradingCalendar(dataset.id());
        assertEquals(baseline.get("securities"), tableCount("securities"));
        assertEquals(baseline.get("daily_bars"), tableCount("daily_bars"));
        for (String table : List.of(
                "agent_tasks", "agent_runs", "agent_evidence", "agent_vetoes", "agent_decisions"
        )) {
            assertEquals(baseline.get(table), tableCount(table));
        }
    }

    @Test
    @Order(2)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesRealConnectionIdempotencyAndConcurrentCorrection() throws Exception {
        RegisterDatasetVersionCommand datasetCommand = datasetCommand(HASH_B);
        List<ConcurrentResult<TemporalModels.DatasetVersion>> datasets = runConcurrent(
                () -> temporal.registerDatasetVersion(datasetCommand));
        assertDistinctConnections(datasets);
        assertEquals(datasets.get(0).value().id(), datasets.get(1).value().id());
        long datasetId = datasets.get(0).value().id();
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM market_data_dataset_versions WHERE id=?",
                Integer.class, datasetId));

        AppendSecurityStatusEventCommand initialCommand = statusEvent(CONCURRENT_SYMBOL,
                "concurrent-status", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                RANGE_START, null, KNOWLEDGE_1, false,
                TemporalTrustLevel.OBSERVED, null, datasetId);
        List<ConcurrentResult<SecurityStatusEvent>> events = runConcurrent(
                () -> temporal.appendSecurityStatusEvent(initialCommand));
        assertDistinctConnections(events);
        assertEquals(events.get(0).value().id(), events.get(1).value().id());
        SecurityStatusEvent initialEvent = events.get(0).value();
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_events
                WHERE source=? AND source_version=? AND source_record_id=? AND source_revision=?
                """, Integer.class, SOURCE, SOURCE_VERSION, "concurrent-status", "1"));

        SecurityStatusVersion original = temporal.publishSecurityStatusVersion(statusVersion(
                initialEvent, RANGE_START, null, KNOWLEDGE_1, false,
                TemporalTrustLevel.OBSERVED, datasetId));
        SecurityStatusEvent correctionEvent = temporal.appendSecurityStatusEvent(statusEvent(
                CONCURRENT_SYMBOL,
                "concurrent-status", "2", SecurityStatusEventType.ST_CHANGE,
                RANGE_START, null, KNOWLEDGE_2, true,
                TemporalTrustLevel.OBSERVED, initialEvent.id(), datasetId));
        CorrectSecurityStatusVersionCommand correction = new CorrectSecurityStatusVersionCommand(
                original.id(), statusVersion(correctionEvent, RANGE_START, null, KNOWLEDGE_2,
                true, TemporalTrustLevel.OBSERVED, datasetId));
        List<ConcurrentResult<SecurityStatusVersion>> corrections = runConcurrent(
                () -> temporal.correctSecurityStatusVersion(correction));
        assertDistinctConnections(corrections);
        assertEquals(corrections.get(0).value().id(), corrections.get(1).value().id());
        assertEquals(KNOWLEDGE_2, jdbc.queryForObject(
                "SELECT known_to FROM security_status_history WHERE id=?",
                java.time.OffsetDateTime.class, original.id()).toInstant());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_history
                WHERE source_event_id=? AND known_from=? AND known_to IS NULL
                """, Integer.class, correctionEvent.id(),
                java.time.OffsetDateTime.ofInstant(KNOWLEDGE_2, ZoneOffset.UTC)));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_history WHERE symbol=?",
                Integer.class, CONCURRENT_SYMBOL));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*)
                FROM security_status_history left_version
                JOIN security_status_history right_version
                  ON left_version.id < right_version.id
                 AND left_version.symbol=right_version.symbol
                 AND daterange(left_version.valid_from, left_version.valid_to, '[)')
                     && daterange(right_version.valid_from, right_version.valid_to, '[)')
                 AND tstzrange(left_version.known_from, left_version.known_to, '[)')
                     && tstzrange(right_version.known_from, right_version.known_to, '[)')
                WHERE left_version.symbol=?
                """, Integer.class, CONCURRENT_SYMBOL));
    }

    private <T> List<ConcurrentResult<T>> runConcurrent(ConcurrentOperation<T> operation)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<ConcurrentResult<T>>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    return transaction.execute(status -> {
                        int backendPid = jdbc.queryForObject(
                                "SELECT pg_backend_pid()", Integer.class);
                        try {
                            barrier.await();
                            return new ConcurrentResult<>(backendPid, operation.run());
                        } catch (Exception error) {
                            throw new IllegalStateException("concurrent temporal operation failed", error);
                        }
                    });
                }));
            }
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertDistinctConnections(List<? extends ConcurrentResult<?>> results) {
        assertEquals(2, results.size());
        assertNotEquals(results.get(0).backendPid(), results.get(1).backendPid());
    }

    @FunctionalInterface
    private interface ConcurrentOperation<T> {
        T run();
    }

    private record ConcurrentResult<T>(int backendPid, T value) {}

    private void exerciseTradingCalendar(long datasetId) {
        LocalDate firstDate = LocalDate.of(2025, 1, 6);
        LocalDate correctedDate = LocalDate.of(2025, 1, 7);
        LocalDate thirdDate = LocalDate.of(2025, 1, 8);
        LocalDate holidayDate = LocalDate.of(2025, 1, 9);

        TradingCalendarRevision first = temporal.appendTradingCalendarRevision(openCalendar(
                datasetId, MarketExchange.SSE, firstDate, "sse-1", "1",
                null, correctedDate, KNOWLEDGE_1, HASH_A
        ));
        assertEquals(first.id(), temporal.appendTradingCalendarRevision(openCalendar(
                datasetId, MarketExchange.SSE, firstDate, "sse-1", "1",
                null, correctedDate, KNOWLEDGE_1, HASH_A
        )).id());
        TradingCalendarRevision current = temporal.appendTradingCalendarRevision(openCalendar(
                datasetId, MarketExchange.SSE, correctedDate, "sse-2", "1",
                firstDate, thirdDate, KNOWLEDGE_1, HASH_B
        ));
        temporal.appendTradingCalendarRevision(openCalendar(
                datasetId, MarketExchange.SSE, thirdDate, "sse-3", "1",
                correctedDate, null, KNOWLEDGE_1, HASH_C
        ));
        TradingCalendarRevision szse = temporal.appendTradingCalendarRevision(openCalendar(
                datasetId, MarketExchange.SZSE, correctedDate, "szse-2", "1",
                firstDate, thirdDate, KNOWLEDGE_1, HASH_A
        ));
        temporal.appendTradingCalendarRevision(closedCalendar(
                datasetId, MarketExchange.SSE, holidayDate, "sse-4", "1",
                TradingSessionType.HOLIDAY, thirdDate, null, KNOWLEDGE_1,
                TemporalTrustLevel.BACKFILLED_INFERRED, HASH_B
        ));

        long calendarsBeforeOverlap = tableCount("trading_calendar_revisions");
        assertThrows(DataAccessException.class,
                () -> temporal.appendTradingCalendarRevision(openCalendar(
                        datasetId, MarketExchange.SSE, correctedDate, "sse-overlap", "1",
                        firstDate, thirdDate, KNOWLEDGE_1.plusSeconds(1), HASH_A
                )));
        assertEquals(calendarsBeforeOverlap, tableCount("trading_calendar_revisions"));

        TradingCalendarRevision closure = temporal.correctTradingCalendarRevision(
                new CorrectTradingCalendarRevisionCommand(current.id(), closedCalendar(
                        datasetId, MarketExchange.SSE, correctedDate, "sse-2", "2",
                        TradingSessionType.TEMPORARY_CLOSURE, firstDate, thirdDate,
                        KNOWLEDGE_2, TemporalTrustLevel.OBSERVED, HASH_C
                ))
        );
        assertEquals(closure.id(), temporal.correctTradingCalendarRevision(
                new CorrectTradingCalendarRevisionCommand(current.id(), closedCalendar(
                        datasetId, MarketExchange.SSE, correctedDate, "sse-2", "2",
                        TradingSessionType.TEMPORARY_CLOSURE, firstDate, thirdDate,
                        KNOWLEDGE_2, TemporalTrustLevel.OBSERVED, HASH_C
                ))
        ).id());
        expectIndependentDatabaseFailure("55000", "trading_calendar_revisions",
                "UPDATE trading_calendar_revisions SET source='OTHER' WHERE id=?", closure.id());
        expectIndependentDatabaseFailure("55000", "trading_calendar_revisions",
                "UPDATE trading_calendar_revisions SET known_to=NULL WHERE id=?", current.id());
        expectIndependentDatabaseFailure("55000", "trading_calendar_revisions",
                "UPDATE trading_calendar_revisions SET known_to=? WHERE id=?",
                java.time.OffsetDateTime.ofInstant(KNOWLEDGE_3, ZoneOffset.UTC), current.id());
        expectIndependentDatabaseFailure("55000", "trading_calendar_revisions",
                "DELETE FROM trading_calendar_revisions WHERE id=?", closure.id());
        expectIndependentDatabaseFailure("55000", "trading_calendar_revisions",
                "TRUNCATE trading_calendar_revisions");

        assertTrue(temporal.findTradingCalendarAsOf(
                MarketExchange.SSE, correctedDate, KNOWLEDGE_2.minusSeconds(1)
        ).orElseThrow().revision().open());
        assertFalse(temporal.findTradingCalendarAsOf(
                MarketExchange.SSE, correctedDate, KNOWLEDGE_2
        ).orElseThrow().revision().open());
        assertEquals(szse.id(), temporal.findTradingCalendarAsOf(
                MarketExchange.SZSE, correctedDate, KNOWLEDGE_2
        ).orElseThrow().revision().id());
        assertFalse(temporal.findTradingCalendarAsOf(
                MarketExchange.SSE, holidayDate, KNOWLEDGE_2
        ).orElseThrow().trustAssessment().pointInTimeCandidate());

        assertEquals(correctedDate, temporal.findPreviousOpenDateAsOf(
                MarketExchange.SSE, thirdDate, KNOWLEDGE_2.minusSeconds(1)
        ).orElseThrow());
        assertEquals(firstDate, temporal.findPreviousOpenDateAsOf(
                MarketExchange.SSE, thirdDate, KNOWLEDGE_2
        ).orElseThrow());
        assertEquals(correctedDate, temporal.findNextOpenDateAsOf(
                MarketExchange.SSE, firstDate, KNOWLEDGE_2.minusSeconds(1)
        ).orElseThrow());
        assertEquals(thirdDate, temporal.findNextOpenDateAsOf(
                MarketExchange.SSE, firstDate, KNOWLEDGE_2
        ).orElseThrow());

        long beforeInvalid = tableCount("trading_calendar_revisions");
        expectIndependentDatabaseFailure("23514", "ck_trading_calendar_revisions_known_range", """
                INSERT INTO trading_calendar_revisions(
                    dataset_version_id, exchange, trade_date, is_open, session_type,
                    session_open_at, session_close_at, known_from, known_to,
                    source, source_version, source_record_id, source_revision,
                    trust_level, payload_hash
                ) VALUES (?, 'SSE', DATE '2025-01-10', false, 'HOLIDAY', NULL, NULL,
                    ?::timestamptz, ?::timestamptz, ?, ?, 'invalid-range', '1',
                    'OBSERVED', ?)
                """, datasetId, KNOWLEDGE_2.toString(), KNOWLEDGE_1.toString(),
                SOURCE, SOURCE_VERSION, HASH_A);
        assertEquals(beforeInvalid, tableCount("trading_calendar_revisions"));
    }

    private void expectIndependentDatabaseFailure(
            String expectedSqlState, String expectedMessageFragment,
            String sql, Object... parameters) {
        int springBackendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        try (Connection connection = controlConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO \"" + TEST_SCHEMA + "\"");
            }
            int independentBackendPid;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
                assertTrue(result.next());
                independentBackendPid = result.getInt(1);
            }
            assertNotEquals(springBackendPid, independentBackendPid);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                SQLException topLevelError = assertThrows(SQLException.class, statement::execute);
                assertNotNull(topLevelError.getSQLState(), "top-level SQLSTATE must be present");
                SQLException databaseError = topLevelError;
                while (databaseError != null
                        && !expectedSqlState.equals(databaseError.getSQLState())) {
                    databaseError = databaseError.getNextException();
                }
                assertNotNull(databaseError,
                        () -> "expected SQLSTATE " + expectedSqlState
                                + " but top-level SQLSTATE was " + topLevelError.getSQLState());
                String databaseMessage = databaseError.getMessage();
                assertNotNull(databaseMessage);
                assertTrue(databaseMessage.contains(expectedMessageFragment),
                        () -> "SQLSTATE " + expectedSqlState
                                + " did not identify " + expectedMessageFragment);
            }
        } catch (SQLException error) {
            throw new AssertionError("could not isolate expected database failure", error);
        }
    }

    private void assertDedicatedDatabaseAndMigration() {
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(TEST_SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(6, jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version IN ('1','2','3','4','5','6') AND success=TRUE
                """, Integer.class));
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), jdbc.query(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE version IN ('1','2','3','4','5','6') AND success=TRUE "
                        + "ORDER BY installed_rank",
                (resultSet, rowNum) -> resultSet.getString(1)));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success=FALSE", Integer.class));
    }

    private void assertSchemaProtection() {
        for (String table : List.of(
                "market_data_dataset_versions", "security_status_events",
                "security_status_history", "trading_calendar_revisions"
        )) {
            assertNotNull(jdbc.queryForObject(
                    "SELECT to_regclass(?)", String.class, TEST_SCHEMA + "." + table));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname='btree_gist'", Integer.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint constraint_record
                JOIN pg_class table_record ON table_record.oid=constraint_record.conrelid
                JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname=current_schema()
                  AND ((table_record.relname='security_status_history'
                        AND constraint_record.conname='ex_security_status_history_bitemporal_overlap')
                    OR (table_record.relname='trading_calendar_revisions'
                        AND constraint_record.conname='ex_trading_calendar_revisions_knowledge_overlap'))
                  AND constraint_record.contype='x'
                """, Integer.class));
        assertEquals(8, jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger_record
                JOIN pg_class table_record ON table_record.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname=current_schema()
                  AND NOT trigger_record.tgisinternal
                  AND trigger_record.tgname IN (
                    'trg_market_dataset_versions_immutable_rows',
                    'trg_market_dataset_versions_no_truncate',
                    'trg_security_status_events_immutable_rows',
                    'trg_security_status_events_no_truncate',
                    'trg_security_status_history_guard_rows',
                    'trg_security_status_history_no_truncate',
                    'trg_trading_calendar_revisions_guard_rows',
                    'trg_trading_calendar_revisions_no_truncate')
                """, Integer.class));
        for (String trigger : List.of(
                "trg_market_dataset_versions_immutable_rows",
                "trg_security_status_events_immutable_rows",
                "trg_security_status_history_guard_rows",
                "trg_trading_calendar_revisions_guard_rows")) {
            String definition = jdbc.queryForObject("""
                    SELECT pg_get_triggerdef(trigger_record.oid)
                    FROM pg_trigger trigger_record
                    JOIN pg_class table_record ON table_record.oid=trigger_record.tgrelid
                    JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                    WHERE schema_record.nspname=? AND trigger_record.tgname=?
                    """, String.class, TEST_SCHEMA, trigger);
            assertTrue(definition.contains("UPDATE OR DELETE")
                    || definition.contains("DELETE OR UPDATE"), definition);
        }
        for (String trigger : List.of(
                "trg_market_dataset_versions_no_truncate",
                "trg_security_status_events_no_truncate",
                "trg_security_status_history_no_truncate",
                "trg_trading_calendar_revisions_no_truncate")) {
            assertTrue(jdbc.queryForObject("""
                    SELECT pg_get_triggerdef(trigger_record.oid)
                    FROM pg_trigger trigger_record
                    JOIN pg_class table_record ON table_record.oid=trigger_record.tgrelid
                    JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                    WHERE schema_record.nspname=? AND trigger_record.tgname=?
                    """, String.class, TEST_SCHEMA, trigger).contains("TRUNCATE"));
        }
        List<String> requiredConstraints = List.of(
                "fk_security_status_events_dataset", "fk_security_status_events_supersedes",
                "fk_security_status_history_event", "fk_security_status_history_dataset",
                "fk_trading_calendar_revisions_dataset",
                "uq_market_dataset_source_payload", "uq_security_status_events_source_record",
                "uq_security_status_history_event_projection",
                "uq_trading_calendar_revisions_source_record",
                "ck_market_dataset_payload_hash", "ck_security_status_events_payload_hash",
                "ck_security_status_history_status_hash",
                "ck_security_status_history_valid_range",
                "ck_security_status_history_known_range",
                "ck_trading_calendar_revisions_session",
                "ck_trading_calendar_revisions_known_range");
        String placeholders = String.join(",", java.util.Collections.nCopies(
                requiredConstraints.size(), "?"));
        List<Object> constraintParameters = new java.util.ArrayList<>();
        constraintParameters.add(TEST_SCHEMA);
        constraintParameters.addAll(requiredConstraints);
        assertEquals(requiredConstraints.size(), jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint constraint_record "
                        + "JOIN pg_class table_record ON table_record.oid=constraint_record.conrelid "
                        + "JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace "
                        + "WHERE schema_record.nspname=? AND constraint_record.conname IN ("
                        + placeholders + ")",
                Integer.class, constraintParameters.toArray()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname=current_schema()
                  AND ((tablename='security_status_history'
                        AND indexname='idx_security_status_history_current_knowledge')
                    OR (tablename='trading_calendar_revisions'
                        AND indexname='uq_trading_calendar_revisions_current_knowledge'))
                """, Integer.class));
    }

    private void assertFixtureNamespaceAvailable() {
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_events WHERE symbol=?",
                Integer.class, SYMBOL));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_history WHERE symbol=?",
                Integer.class, SYMBOL));
        for (LocalDate tradeDate : List.of(
                LocalDate.of(2025, 1, 6), LocalDate.of(2025, 1, 7),
                LocalDate.of(2025, 1, 8), LocalDate.of(2025, 1, 9),
                LocalDate.of(2025, 1, 10))) {
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM trading_calendar_revisions
                    WHERE exchange IN ('SSE', 'SZSE') AND trade_date=?
                    """, Integer.class, tradeDate));
        }
    }

    private RegisterDatasetVersionCommand datasetCommand() {
        return datasetCommand(HASH_A);
    }

    private RegisterDatasetVersionCommand datasetCommand(String payloadHash) {
        return new RegisterDatasetVersionCommand(
                "TEMPORAL_MARKET_FOUNDATION", SOURCE, SOURCE_VERSION, CONNECTOR_VERSION,
                RANGE_START, RANGE_END, Instant.parse("2025-01-01T01:00:00Z"),
                payloadHash, TemporalTrustLevel.OBSERVED,
                objectMapper.createObjectNode().put("fixture", "stage-2d2a")
        );
    }

    private AppendSecurityStatusEventCommand statusEvent(
            String recordId,
            String revision,
            SecurityStatusEventType type,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant knownAt,
            boolean resultingSt,
            TemporalTrustLevel trust,
            Long supersedes,
            long datasetId
    ) {
        return statusEvent(SYMBOL, recordId, revision, type, effectiveFrom, effectiveTo,
                knownAt, resultingSt, trust, supersedes, datasetId);
    }

    private AppendSecurityStatusEventCommand statusEvent(
            String symbol,
            String recordId,
            String revision,
            SecurityStatusEventType type,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant knownAt,
            boolean resultingSt,
            TemporalTrustLevel trust,
            Long supersedes,
            long datasetId
    ) {
        var payload = SecurityStatusEventPayloadContract.payload(
                new SecurityStatusState(MarketExchange.SSE, "MAIN", true, true, resultingSt));
        return new AppendSecurityStatusEventCommand(
                datasetId, symbol, type, effectiveFrom, effectiveTo,
                knownAt.minusSeconds(60), knownAt, SOURCE, SOURCE_VERSION,
                recordId, revision, trust,
                payload, SecurityStatusEventPayloadContract.hash(payload), supersedes
        );
    }

    private PublishSecurityStatusVersionCommand statusVersion(
            SecurityStatusEvent event,
            LocalDate validFrom,
            LocalDate validTo,
            Instant knownFrom,
            boolean st,
            TemporalTrustLevel trust,
            long datasetId
    ) {
        return new PublishSecurityStatusVersionCommand(
                event.symbol(), MarketExchange.SSE, "MAIN", true, true, st,
                validFrom, validTo, knownFrom, null, event.id(), datasetId,
                SOURCE, SOURCE_VERSION, trust
        );
    }

    private AppendTradingCalendarRevisionCommand openCalendar(
            long datasetId,
            MarketExchange exchange,
            LocalDate tradeDate,
            String recordId,
            String revision,
            LocalDate previous,
            LocalDate next,
            Instant knownFrom,
            String payloadHash
    ) {
        return new AppendTradingCalendarRevisionCommand(
                datasetId, exchange, tradeDate, true, TradingSessionType.REGULAR,
                tradeDate.atTime(1, 30).toInstant(ZoneOffset.UTC),
                tradeDate.atTime(7, 0).toInstant(ZoneOffset.UTC),
                knownFrom, null, SOURCE, SOURCE_VERSION,
                recordId, revision, TemporalTrustLevel.OBSERVED, payloadHash
        );
    }

    private AppendTradingCalendarRevisionCommand closedCalendar(
            long datasetId,
            MarketExchange exchange,
            LocalDate tradeDate,
            String recordId,
            String revision,
            TradingSessionType sessionType,
            LocalDate previous,
            LocalDate next,
            Instant knownFrom,
            TemporalTrustLevel trust,
            String payloadHash
    ) {
        return new AppendTradingCalendarRevisionCommand(
                datasetId, exchange, tradeDate, false, sessionType,
                null, null, knownFrom, null,
                SOURCE, SOURCE_VERSION, recordId, revision, trust, payloadHash
        );
    }

    private Map<String, Long> counts() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : BASELINE_TABLES) {
            result.put(table, tableCount(table));
        }
        return result;
    }

    private long tableCount(String table) {
        if (!BASELINE_TABLES.contains(table)) {
            throw new IllegalArgumentException("table is outside the stage 2D-2A count allowlist");
        }
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    private int countBySource(String table) {
        if (!Set.of(
                "market_data_dataset_versions", "security_status_events",
                "security_status_history", "trading_calendar_revisions"
        ).contains(table)) {
            throw new IllegalArgumentException("table is outside the temporal source allowlist");
        }
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE source=?",
                Integer.class, SOURCE);
        return value == null ? 0 : value;
    }
}
