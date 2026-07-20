package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.temporal.MarketExchange;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
import com.stockquant.server.agent.temporal.TemporalDataConflictException;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
class AgentStage2D2ATemporalMarketFoundationPostgresIntegrationTest {

    private static final String SOURCE = "TEST_FIXTURE_STAGE_2D2A";
    private static final String SOURCE_VERSION = "stage-2d2a-v1";
    private static final String CONNECTOR_VERSION = "test-connector-v1";
    private static final String SYMBOL = "699991";
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

    private final Set<Long> datasetIds = new LinkedHashSet<>();
    private Map<String, Long> baseline;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
    }

    @AfterEach
    void cleanFixturesPreciselyAndRestoreBaseline() {
        for (long datasetId : datasetIds) {
            jdbc.update("DELETE FROM trading_calendar_revisions WHERE dataset_version_id=?", datasetId);
            jdbc.update("DELETE FROM security_status_history WHERE dataset_version_id=?", datasetId);
            int deleted;
            do {
                deleted = jdbc.update("""
                        DELETE FROM security_status_events parent
                        WHERE parent.dataset_version_id=?
                          AND NOT EXISTS (
                              SELECT 1 FROM security_status_events child
                              WHERE child.supersedes_event_id=parent.id
                          )
                        """, datasetId);
            } while (deleted > 0);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM security_status_events WHERE dataset_version_id=?",
                    Integer.class, datasetId));
            assertEquals(1, jdbc.update(
                    "DELETE FROM market_data_dataset_versions WHERE id=?", datasetId));
        }
        if (baseline != null) {
            assertEquals(baseline, counts());
        }
    }

    @Test
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
        datasetIds.add(dataset.id());
        assertEquals(dataset.id(), temporal.registerDatasetVersion(datasetCommand).id());
        assertEquals(1, countBySource("market_data_dataset_versions"));

        SecurityStatusEvent firstEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "status-1", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                TemporalTrustLevel.OBSERVED, null, HASH_A, dataset.id()
        ));
        assertEquals(firstEvent.id(), temporal.appendSecurityStatusEvent(statusEvent(
                "status-1", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                TemporalTrustLevel.OBSERVED, null, HASH_A, dataset.id()
        )).id());
        assertThrows(TemporalDataConflictException.class,
                () -> temporal.appendSecurityStatusEvent(statusEvent(
                        "status-1", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                        RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                        TemporalTrustLevel.OBSERVED, null, HASH_B, dataset.id()
                )));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE security_status_events SET payload=payload WHERE id=?", firstEvent.id()));
        assertEquals(HASH_A, jdbc.queryForObject(
                "SELECT payload_hash FROM security_status_events WHERE id=?",
                String.class, firstEvent.id()));

        SecurityStatusEvent secondEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "status-2", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                LocalDate.of(2025, 6, 1), null, KNOWLEDGE_1,
                TemporalTrustLevel.OBSERVED, null, HASH_B, dataset.id()
        ));
        SecurityStatusVersion firstVersion = temporal.publishSecurityStatusVersion(statusVersion(
                firstEvent, RANGE_START, LocalDate.of(2025, 6, 1), KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, dataset.id()
        ));
        SecurityStatusVersion secondVersion = temporal.publishSecurityStatusVersion(statusVersion(
                secondEvent, LocalDate.of(2025, 6, 1), null, KNOWLEDGE_1,
                false, TemporalTrustLevel.OBSERVED, dataset.id()
        ));
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
                TemporalTrustLevel.BACKFILLED_INFERRED, secondEvent.id(), HASH_C, dataset.id()
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

        SecurityStatusEvent overlapEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "overlap", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                LocalDate.of(2025, 5, 1), LocalDate.of(2025, 7, 1), KNOWLEDGE_1,
                TemporalTrustLevel.OBSERVED, null, HASH_A, dataset.id()
        ));
        long historiesBeforeOverlap = tableCount("security_status_history");
        assertThrows(DataIntegrityViolationException.class,
                () -> temporal.publishSecurityStatusVersion(statusVersion(
                        overlapEvent, LocalDate.of(2025, 5, 1), LocalDate.of(2025, 7, 1),
                        KNOWLEDGE_1, false, TemporalTrustLevel.OBSERVED, dataset.id()
                )));
        assertEquals(historiesBeforeOverlap, tableCount("security_status_history"));

        SecurityStatusEvent atomicEvent = temporal.appendSecurityStatusEvent(statusEvent(
                "atomic", "1", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                RANGE_START, LocalDate.of(2025, 8, 1), KNOWLEDGE_3,
                TemporalTrustLevel.OBSERVED, firstEvent.id(), HASH_B, dataset.id()
        ));
        assertThrows(DataIntegrityViolationException.class,
                () -> temporal.correctSecurityStatusVersion(
                        new CorrectSecurityStatusVersionCommand(firstVersion.id(), statusVersion(
                                atomicEvent, RANGE_START, LocalDate.of(2025, 8, 1), KNOWLEDGE_3,
                                false, TemporalTrustLevel.OBSERVED, dataset.id()
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
        assertThrows(DataIntegrityViolationException.class,
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
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO trading_calendar_revisions(
                    dataset_version_id, exchange, trade_date, is_open, session_type,
                    session_open_at, session_close_at, known_from, known_to,
                    source, source_version, source_record_id, source_revision,
                    trust_level, payload_hash
                ) VALUES (?, 'SSE', DATE '2025-01-10', false, 'HOLIDAY', NULL, NULL,
                    ?::timestamptz, ?::timestamptz, ?, ?, 'invalid-range', '1',
                    'OBSERVED', ?)
                """, datasetId, KNOWLEDGE_2.toString(), KNOWLEDGE_1.toString(),
                SOURCE, SOURCE_VERSION, HASH_A));
        assertEquals(beforeInvalid, tableCount("trading_calendar_revisions"));
    }

    private void assertDedicatedDatabaseAndMigration() {
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version='6' AND success=TRUE
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history v5
                JOIN flyway_schema_history v6 ON v5.installed_rank < v6.installed_rank
                WHERE v5.version='5' AND v5.success=TRUE AND v6.version='6' AND v6.success=TRUE
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success=FALSE", Integer.class));
    }

    private void assertSchemaProtection() {
        for (String table : List.of(
                "market_data_dataset_versions", "security_status_events",
                "security_status_history", "trading_calendar_revisions"
        )) {
            assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)", String.class, table));
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
        return new RegisterDatasetVersionCommand(
                "TEMPORAL_MARKET_FOUNDATION", SOURCE, SOURCE_VERSION, CONNECTOR_VERSION,
                RANGE_START, RANGE_END, Instant.parse("2025-01-01T01:00:00Z"),
                HASH_A, TemporalTrustLevel.OBSERVED,
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
            TemporalTrustLevel trust,
            Long supersedes,
            String payloadHash,
            long datasetId
    ) {
        return new AppendSecurityStatusEventCommand(
                datasetId, SYMBOL, type, effectiveFrom, effectiveTo,
                knownAt.minusSeconds(60), knownAt, SOURCE, SOURCE_VERSION,
                recordId, revision, trust,
                objectMapper.createObjectNode().put("recordId", recordId).put("revision", revision),
                payloadHash, supersedes
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
                SYMBOL, MarketExchange.SSE, "MAIN", true, true, st,
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
                previous, next, knownFrom, null, SOURCE, SOURCE_VERSION,
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
                null, null, previous, next, knownFrom, null,
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
