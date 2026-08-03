package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceAuthorization.ControlledEndpoint;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Reservation;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "F1F_B1_POSTGRES_JDBC_URL", matches = ".+")
class TushareControlledAcceptancePostgresTest {
    private static final String COMMIT = "e3777602fadd65f3af0a2ba8ac6e886693d745d5";
    private static final String SHA = "a".repeat(64);
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void connect() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getenv("F1F_B1_POSTGRES_JDBC_URL"));
        source.setUser(System.getenv("F1F_B1_POSTGRES_USER"));
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void freshGovernedSchema() {
        resetToMainV13();
        migrateGovernance(dataSource);
    }

    @AfterAll
    static void clean() {
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA tushare_research CASCADE");
        }
    }

    @Test
    void defaultFlywayStopsAtV13AndGovernanceUsesIndependentHistory() {
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9",
                "10", "11", "12", "13"), mainVersions());
        assertEquals(List.of(
                new HistoryEntry("13", "BASELINE",
                        "explicit verified dedicated V1-V13 base "
                                + TushareControlledAcceptanceExecution.RULE_VERSION),
                new HistoryEntry("14", "SQL",
                        "V14__tushare_controlled_acceptance_execution.sql")),
                jdbc.query("""
                SELECT version, type, script
                  FROM tushare_research.flyway_controlled_acceptance_history
                 WHERE success ORDER BY installed_rank
                """, (row, ignored) -> new HistoryEntry(
                        row.getString("version"), row.getString("type"),
                        row.getString("script"))));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*)
                  FROM tushare_research.flyway_controlled_acceptance_history
                 WHERE NOT success
                """, Integer.class));
        assertThrows(RuntimeException.class, () -> Flyway.configure()
                .dataSource(dataSource)
                .schemas("tushare_research").defaultSchema("tushare_research")
                .locations("classpath:db/migration", "classpath:db/controlled-acceptance")
                .cleanDisabled(true)
                .load().migrate());
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9",
                "10", "11", "12", "13"), mainVersions());
        assertDoesNotThrow(() ->
                migrateGovernance(dataSource));
        assertEquals(List.of("13", "14"), jdbc.queryForList("""
                SELECT version
                  FROM tushare_research.flyway_controlled_acceptance_history
                 WHERE success ORDER BY installed_rank
                """, String.class));
    }

    @Test
    void wrongSchemaIsRejectedBeforeAnyGovernanceDdl() {
        PGSimpleDataSource publicSource = new PGSimpleDataSource();
        publicSource.setURL(System.getenv("F1F_B1_POSTGRES_JDBC_URL")
                .replace("currentSchema=tushare_research", "currentSchema=public"));
        publicSource.setUser(System.getenv("F1F_B1_POSTGRES_USER"));
        assertThrows(RuntimeException.class, () ->
                TushareControlledAcceptanceDatabaseGuard.migrateGovernance(
                        publicSource,
                        TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE,
                        authorization(), buildProof()));
        JdbcTemplate publicJdbc = new JdbcTemplate(publicSource);
        assertFalse(Boolean.TRUE.equals(publicJdbc.queryForObject("""
                SELECT to_regclass(
                  'public.flyway_controlled_acceptance_history') IS NOT NULL
                    OR to_regclass(
                  'public.tushare_controlled_acceptance_execution') IS NOT NULL
                """, Boolean.class)));
    }

    @Test
    void searchPathWithPublicFallbackIsRejectedBeforeGovernanceDdl() {
        resetToMainV13();
        PGSimpleDataSource unsafe = source(System.getenv("F1F_B1_POSTGRES_JDBC_URL")
                .replace("currentSchema=tushare_research",
                        "currentSchema=tushare_research,public"),
                System.getenv("F1F_B1_POSTGRES_USER"));

        assertThrows(RuntimeException.class, () -> migrateGovernance(unsafe));
        assertFalse(governanceHistoryExists(jdbc));
    }

    @Test
    void failedMainHistoryIsRejectedBeforeGovernanceDdl() {
        resetToMainV13();
        insertMainHistory("13.1", "FAILED_TEST", "SQL", "failed-test.sql", false);

        assertThrows(IllegalStateException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceHistoryExists(jdbc));
    }

    @Test
    void absentMissingAndFutureMainHistoryAreRejectedBeforeGovernanceDdl() {
        dropAndCreateSchema(jdbc);
        assertThrows(RuntimeException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceHistoryExists(jdbc));

        resetToMainV13();
        jdbc.update("DELETE FROM tushare_research.flyway_schema_history "
                + "WHERE version='13'");
        assertThrows(RuntimeException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceHistoryExists(jdbc));

        resetToMainV13();
        insertMainHistory("99", "FUTURE_TEST", "SQL", "future-test.sql", true);
        assertThrows(RuntimeException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceHistoryExists(jdbc));
    }

    @Test
    void partialAndUnknownGovernanceHistoryAreRejectedWithoutV14Ddl() {
        resetToMainV13();
        baselineGovernance();
        jdbc.update("DELETE FROM tushare_research.flyway_controlled_acceptance_history");
        assertThrows(RuntimeException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceObjectsExist(jdbc));

        resetToMainV13();
        baselineGovernance();
        insertGovernanceHistory("15", "UNKNOWN_TEST", "SQL", "V15__unknown.sql", true);
        assertThrows(RuntimeException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceObjectsExist(jdbc));

        resetToMainV13();
        baselineGovernance();
        jdbc.update("""
                UPDATE tushare_research.flyway_controlled_acceptance_history
                   SET type='SQL', script='forged-baseline.sql'
                 WHERE version='13'
                """);
        assertThrows(RuntimeException.class, () -> migrateGovernance(dataSource));
        assertFalse(governanceObjectsExist(jdbc));
    }

    @Test
    void wrongDatabaseAndWrongUserAreRejectedBeforeGovernanceDdl() {
        String baseUrl = System.getenv("F1F_B1_POSTGRES_JDBC_URL");
        PGSimpleDataSource wrongDatabase = source(
                baseUrl.replace("/stock_quant_research", "/postgres"),
                System.getenv("F1F_B1_POSTGRES_USER"));
        JdbcTemplate wrongDatabaseJdbc = new JdbcTemplate(wrongDatabase);
        dropAndCreateSchema(wrongDatabaseJdbc);
        migrateMain(wrongDatabase);
        try {
            assertThrows(RuntimeException.class, () -> migrateGovernance(wrongDatabase));
            assertFalse(governanceHistoryExists(wrongDatabaseJdbc));
        } finally {
            wrongDatabaseJdbc.execute("DROP SCHEMA tushare_research CASCADE");
        }

        resetToMainV13();
        jdbc.execute("DROP ROLE IF EXISTS stock_quant_wrong_user");
        jdbc.execute("CREATE ROLE stock_quant_wrong_user LOGIN");
        jdbc.execute("GRANT USAGE ON SCHEMA tushare_research TO stock_quant_wrong_user");
        jdbc.execute("GRANT SELECT ON tushare_research.flyway_schema_history "
                + "TO stock_quant_wrong_user");
        PGSimpleDataSource wrongUser = source(baseUrl, "stock_quant_wrong_user");
        try {
            assertThrows(RuntimeException.class, () -> migrateGovernance(wrongUser));
            assertFalse(governanceHistoryExists(jdbc));
        } finally {
            jdbc.execute("DROP OWNED BY stock_quant_wrong_user");
            jdbc.execute("DROP ROLE stock_quant_wrong_user");
        }
    }

    @Test
    void reservationIsCrossConnectionAtomicAndRunningRecoverySealsTheId()
            throws Exception {
        var first = repository();
        var second = repository();
        Reservation reservation = reservation("F1FB1_PG_0001", "b".repeat(64));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var results = executor.invokeAll(List.<Callable<Boolean>>of(
                    () -> reserve(first, reservation),
                    () -> reserve(second, reservation)));
            assertEquals(1, results.stream().filter(value -> {
                try {
                    return value.get();
                } catch (Exception error) {
                    return false;
                }
            }).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(ExecutionStatus.RESERVED,
                first.find("F1FB1_PG_0001").orElseThrow().status());
        first.markRunning("F1FB1_PG_0001");

        var recoverers = Executors.newFixedThreadPool(2);
        int recovered;
        try {
            var results = recoverers.invokeAll(List.of(
                    (Callable<Integer>) first::recoverIncompleteExecutions,
                    (Callable<Integer>) second::recoverIncompleteExecutions));
            recovered = results.get(0).get() + results.get(1).get();
        } finally {
            recoverers.shutdownNow();
        }
        assertEquals(1, recovered);
        assertEquals(ExecutionStatus.INTERRUPTED,
                first.find("F1FB1_PG_0001").orElseThrow().status());
        assertThrows(IllegalStateException.class, () -> first.reserve(reservation));
        assertThrows(IllegalStateException.class, () -> first.reserve(
                reservation("F1FB1_PG_0001", "c".repeat(64))));
        assertEquals(List.of(ExecutionStatus.AUTHORIZED,
                        ExecutionStatus.RESERVED, ExecutionStatus.RUNNING,
                        ExecutionStatus.INTERRUPTED),
                first.history("F1FB1_PG_0001").stream()
                        .map(TushareControlledAcceptanceExecution.Transition::to).toList());
    }

    @Test
    void databaseStateMachineRejectsSkippedStepsTerminalReuseAndEvidenceMutation() {
        var repository = repository();
        repository.reserve(reservation("F1FB1_PG_0002", "d".repeat(64)));
        assertThrows(Exception.class, () -> jdbc.update("""
                UPDATE tushare_controlled_acceptance_execution
                   SET status='PASSED', row_version=row_version+1,
                       finalized_at=clock_timestamp()
                 WHERE acceptance_id='F1FB1_PG_0002'
                """));
        repository.markRunning("F1FB1_PG_0002");
        repository.markCandidate("F1FB1_PG_0002", 42L, 3,
                "{\"redacted\":true}", "e".repeat(64));
        repository.markPassed("F1FB1_PG_0002");
        assertThrows(Exception.class, () -> jdbc.update("""
                UPDATE tushare_controlled_acceptance_execution
                   SET status='RESERVED', row_version=row_version+1
                 WHERE acceptance_id='F1FB1_PG_0002'
                """));
        assertThrows(Exception.class, () -> jdbc.update("""
                UPDATE tushare_controlled_acceptance_execution
                   SET evidence_digest=?, row_version=row_version+1
                 WHERE acceptance_id='F1FB1_PG_0002'
                """, "f".repeat(64)));
        var stored = repository.find("F1FB1_PG_0002").orElseThrow();
        assertEquals(ExecutionStatus.PASSED, stored.status());
        assertEquals(4, stored.rowVersion());
        var history = repository.history("F1FB1_PG_0002");
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L),
                history.stream().map(
                        TushareControlledAcceptanceExecution.Transition::rowVersion).toList());
        for (int index = 1; index < history.size(); index++) {
            assertFalse(history.get(index).transitionAt().isBefore(
                    history.get(index - 1).transitionAt()));
        }
    }

    @Test
    void postCommitReadbackRequiresCommittedTypedFactsAndExactTarget() {
        TushareDedicatedResearchPersistenceGuard baseGuard =
                new TushareDedicatedResearchPersistenceGuard(
                        jdbc, TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        CommittedFacts complete = transaction.execute(status ->
                insertFacts(baseGuard, LocalDate.of(2025, 1, 2), "6", true));
        assertNotNull(complete);
        TushareControlledAcceptanceReadbackService readback =
                new TushareControlledAcceptanceReadbackService(jdbc, baseGuard);
        Instant executionStarted = complete.observedAt().minusSeconds(1);
        Instant readbackAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var evidence = readback.readAndVerify(
                complete.batchId(), complete.observedAt(), executionStarted,
                readbackAt, complete.writeIdentity(), complete.sourceInstrumentId(),
                "600000", "SSE", complete.tradeDate());
        assertTrue(evidence.committedReadbackVerified());
        assertTrue(evidence.exactMicrosecondMatch());
        assertEquals(3, evidence.observationIds().size());
        assertEquals(complete.writeIdentity().backendPidAfter(),
                evidence.writeBackendPid());
        assertTrue(evidence.committedReadbackBackendPid() > 0);

        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            readback.readAndVerify(
                    complete.batchId(), complete.observedAt(), executionStarted,
                    readbackAt, complete.writeIdentity(), complete.sourceInstrumentId(),
                    "600000", "SSE", complete.tradeDate());
            return null;
        }));

        CommittedFacts envelopesOnly = transaction.execute(status ->
                insertFacts(baseGuard, LocalDate.of(2025, 1, 3), "7", false));
        assertNotNull(envelopesOnly);
        assertThrows(IllegalStateException.class, () -> readback.readAndVerify(
                envelopesOnly.batchId(), envelopesOnly.observedAt(), executionStarted,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                envelopesOnly.writeIdentity(), envelopesOnly.sourceInstrumentId(),
                "600000", "SSE", envelopesOnly.tradeDate()));
    }

    private static List<String> mainVersions() {
        return jdbc.queryForList("""
                SELECT version FROM tushare_research.flyway_schema_history
                 WHERE success ORDER BY installed_rank
                """, String.class);
    }

    private static void resetToMainV13() {
        dropAndCreateSchema(jdbc);
        migrateMain(dataSource);
    }

    private static void dropAndCreateSchema(JdbcTemplate target) {
        target.execute("DROP SCHEMA IF EXISTS tushare_research CASCADE");
        target.execute("CREATE SCHEMA tushare_research "
                + "AUTHORIZATION stock_quant_research");
    }

    private static void migrateMain(DataSource target) {
        Flyway.configure().dataSource(target)
                .schemas("tushare_research").defaultSchema("tushare_research")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load().migrate();
    }

    private static void baselineGovernance() {
        Flyway.configure().dataSource(dataSource)
                .schemas("tushare_research").defaultSchema("tushare_research")
                .table(TushareControlledAcceptanceDatabaseGuard.GOVERNANCE_HISTORY_TABLE)
                .locations(TushareControlledAcceptanceDatabaseGuard.GOVERNANCE_LOCATION)
                .baselineOnMigrate(false)
                .baselineVersion("13")
                .baselineDescription("explicit verified dedicated V1-V13 base "
                        + TushareControlledAcceptanceExecution.RULE_VERSION)
                .cleanDisabled(true)
                .load().baseline();
    }

    private static void migrateGovernance(DataSource target) {
        TushareControlledAcceptanceDatabaseGuard.migrateGovernance(
                target, TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE,
                authorization(), buildProof());
    }

    private static boolean governanceHistoryExists(JdbcTemplate target) {
        return Boolean.TRUE.equals(target.queryForObject("""
                SELECT to_regclass(
                  'tushare_research.flyway_controlled_acceptance_history') IS NOT NULL
                """, Boolean.class));
    }

    private static boolean governanceObjectsExist(JdbcTemplate target) {
        return Boolean.TRUE.equals(target.queryForObject("""
                SELECT to_regclass(
                  'tushare_research.tushare_controlled_acceptance_execution') IS NOT NULL
                    OR to_regclass(
                  'tushare_research.tushare_controlled_acceptance_transition') IS NOT NULL
                """, Boolean.class));
    }

    private static void insertMainHistory(
            String version,
            String description,
            String type,
            String script,
            boolean success
    ) {
        insertHistory("flyway_schema_history", version, description, type,
                script, success);
    }

    private static void insertGovernanceHistory(
            String version,
            String description,
            String type,
            String script,
            boolean success
    ) {
        insertHistory("flyway_controlled_acceptance_history", version,
                description, type, script, success);
    }

    private static void insertHistory(
            String table,
            String version,
            String description,
            String type,
            String script,
            boolean success
    ) {
        jdbc.update("""
                INSERT INTO tushare_research.%s(
                    installed_rank, version, description, type, script,
                    checksum, installed_by, execution_time, success)
                SELECT COALESCE(max(installed_rank), 0) + 1,
                       ?, ?, ?, ?, NULL, current_user, 0, ?
                  FROM tushare_research.%s
                """.formatted(table, table), version, description, type,
                script, success);
    }

    private static PGSimpleDataSource source(String url, String user) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(url);
        source.setUser(user);
        return source;
    }

    private static CommittedFacts insertFacts(
            TushareDedicatedResearchPersistenceGuard guard,
            LocalDate tradeDate,
            String hashSeed,
            boolean includeTypedRows
    ) {
        var before = guard.verifyTransactional();
        Instant observedAt = Instant.now().minusSeconds(1)
                .truncatedTo(ChronoUnit.MICROS);
        String sourceInstrumentId = TushareMarketFactProvider.sourceInstrumentId(
                "600000", "SSE");
        long datasetId = jdbc.queryForObject("""
                INSERT INTO market_data_dataset_versions(
                    dataset_type, source, source_version, connector_version,
                    range_start, range_end, fetched_at, payload_hash,
                    trust_level, metadata)
                VALUES ('PIT_MARKET_FACTS', 'TUSHARE_PRO',
                    'SYSTEM_KNOWLEDGE_ONLY', 'TUSHARE_MARKET_FACT_PROVIDER_V1',
                    ?, ?, ?, ?, 'OBSERVED', '{}'::jsonb)
                RETURNING id
                """, Long.class, tradeDate, tradeDate, Timestamp.from(observedAt),
                hashSeed.repeat(64));
        long batchId = jdbc.queryForObject("""
                INSERT INTO pit_market_fact_batches(
                    batch_version, dataset_version_id, dataset_version,
                    provider_contract_version, market_facts_contract_version,
                    run_namespace, capture_mode, source_code,
                    source_instrument_id, revision_qualification,
                    assurance_level, usage_qualification, formal_eligible,
                    local_persistence_allowed, historical_replay_allowed,
                    backtest_allowed, agent_use_allowed, range_start, range_end,
                    observed_at, response_complete, record_count,
                    fact_contracts_json, provider_capabilities_json,
                    provider_metadata_json)
                VALUES (?, ?, 'SYSTEM_KNOWLEDGE_ONLY',
                    'MARKET_FACT_PROVIDER_CONTRACT_V1', 'PIT_MARKET_FACTS_V2',
                    'FORMAL', 'PROVIDER_CAPTURE', 'TUSHARE_PRO', ?,
                    'SYSTEM_KNOWLEDGE_ONLY', 'SYSTEM_KNOWLEDGE_PIT',
                    'RESEARCH_ONLY', false, true, true, true, true,
                    ?, ?, ?, true, 3, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb)
                RETURNING id
                """, Long.class, (hashSeed + "8").repeat(32), datasetId,
                sourceInstrumentId, tradeDate, tradeDate, Timestamp.from(observedAt));
        long rawId = insertObservation(batchId, "RAW_DAILY_BAR",
                "RAW_DAILY_BAR_OBSERVATION_V2",
                "RAW_DAILY_BAR|600000|" + tradeDate, sourceInstrumentId,
                observedAt, (hashSeed + "a").repeat(32),
                (hashSeed + "b").repeat(32));
        long factorId = insertObservation(batchId, "ADJUSTMENT_FACTOR",
                "ADJUSTMENT_FACTOR_OBSERVATION_V1",
                "ADJUSTMENT_FACTOR|600000|QFQ|" + tradeDate,
                sourceInstrumentId, observedAt, (hashSeed + "c").repeat(32),
                (hashSeed + "d").repeat(32));
        long calendarId = insertObservation(batchId, "TRADING_CALENDAR",
                "TRADING_CALENDAR_OBSERVATION_V1",
                "TRADING_CALENDAR|SSE|" + tradeDate, sourceInstrumentId,
                observedAt, (hashSeed + "e").repeat(32),
                (hashSeed + "f").repeat(32));
        if (includeTypedRows) {
            jdbc.update("""
                    INSERT INTO raw_daily_bar_facts_v2(
                        observation_id, symbol, exchange, trade_date,
                        open, high, low, close, volume, volume_qualification,
                        volume_unit_code, volume_semantic_code, amount,
                        amount_qualification, amount_unit_code,
                        amount_semantic_code, turnover_rate,
                        turnover_rate_qualification, turnover_rate_unit_code,
                        turnover_rate_semantic_code)
                    VALUES (?, '600000', 'SSE', ?, 10, 11, 9, 10,
                        NULL, 'MISSING', 'SHARES', 'TRADED_VOLUME',
                        NULL, 'MISSING', 'CNY', 'TRADED_AMOUNT',
                        NULL, 'MISSING', 'RATIO', 'TURNOVER_RATE')
                    """, rawId, tradeDate);
            jdbc.update("""
                    INSERT INTO adjustment_factor_facts_v1(
                        observation_id, symbol, factor_effective_trade_date,
                        factor_type, coverage_mode, factor)
                    VALUES (?, '600000', ?, 'QFQ', 'DAILY_EXACT', 1)
                    """, factorId, tradeDate);
            jdbc.update("""
                    INSERT INTO trading_calendar_facts_v1(
                        observation_id, exchange, calendar_date,
                        is_open, session_code)
                    VALUES (?, 'SSE', ?, true, 'REGULAR')
                    """, calendarId, tradeDate);
        }
        var after = guard.verifyTransactional();
        return new CommittedFacts(batchId, observedAt, tradeDate,
                sourceInstrumentId, DatabaseExecutionIdentity.from(before, after));
    }

    private static long insertObservation(
            long batchId,
            String factType,
            String contractVersion,
            String naturalKey,
            String sourceInstrumentId,
            Instant observedAt,
            String contentHash,
            String observationVersion
    ) {
        return jdbc.queryForObject("""
                INSERT INTO pit_market_fact_observations(
                    batch_id, fact_type, fact_contract_version, natural_key,
                    chain_sequence, source_code, source_instrument_id,
                    first_observed_at, known_at, canonical_content_hash,
                    observation_version, revision_qualification,
                    assurance_level, usage_qualification, formal_eligible,
                    local_persistence_allowed, historical_replay_allowed,
                    backtest_allowed, agent_use_allowed, raw_payload_json)
                VALUES (?, ?, ?, ?, 1, 'TUSHARE_PRO', ?, ?, ?, ?, ?,
                    'SYSTEM_KNOWLEDGE_ONLY', 'SYSTEM_KNOWLEDGE_PIT',
                    'RESEARCH_ONLY', false, true, true, true, true, '{}'::jsonb)
                RETURNING id
                """, Long.class, batchId, factType, contractVersion, naturalKey,
                sourceInstrumentId, Timestamp.from(observedAt),
                Timestamp.from(observedAt), contentHash,
                observationVersion);
    }

    private record CommittedFacts(
            long batchId,
            Instant observedAt,
            LocalDate tradeDate,
            String sourceInstrumentId,
            DatabaseExecutionIdentity writeIdentity
    ) {
    }

    private record HistoryEntry(String version, String type, String script) {
    }

    private static boolean reserve(
            TushareControlledAcceptanceExecutionRepository repository,
            Reservation reservation
    ) {
        try {
            repository.reserve(reservation);
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }

    private static TushareControlledAcceptanceExecutionRepository repository() {
        return new TushareControlledAcceptanceExecutionRepository(
                jdbc, new ObjectMapper().findAndRegisterModules(),
                new DataSourceTransactionManager(dataSource),
                Clock.systemUTC());
    }

    private static Reservation reservation(String id, String fingerprint) {
        Instant created = Instant.now().minusSeconds(2);
        return new Reservation(id, fingerprint, ExecutionSource.TEST,
                TushareMarketFactProvider.PROVIDER_CODE, "600000.SH",
                LocalDate.of(2025, 1, 2), Set.of(ControlledEndpoint.DAILY,
                ControlledEndpoint.ADJ_FACTOR, ControlledEndpoint.TRADE_CAL),
                "f68d84403ebb82babe92a1cb0f78d845ed39547a", "a".repeat(64),
                "stock_quant_research", "stock_quant_research", "tushare_research",
                14, created, created.plusSeconds(120));
    }

    private static TushareControlledAcceptanceAuthorization authorization() {
        Instant issued = Instant.now().minusSeconds(30);
        return TushareControlledAcceptanceAuthorization.issueUserApprovedDurable(
                "F1FB2_PG_MIGRATION", COMMIT, SHA,
                new TushareDedicatedResearchBatchCommand.SecuritySelection(
                        "600000", "SSE"),
                LocalDate.of(2025, 1, 2), issued, issued.plusSeconds(120));
    }

    private static TushareControlledAcceptanceBuildProof.VerifiedBuildProof buildProof() {
        var proof = mock(
                TushareControlledAcceptanceBuildProof.VerifiedBuildProof.class);
        when(proof.gitCommit()).thenReturn(COMMIT);
        when(proof.actualArtifactSha256()).thenReturn(SHA);
        when(proof.governanceEligible()).thenReturn(true);
        return proof;
    }
}
