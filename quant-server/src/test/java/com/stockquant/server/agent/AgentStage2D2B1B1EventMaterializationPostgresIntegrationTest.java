package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.ingestion.IngestionCanonicalHasher;
import com.stockquant.server.agent.ingestion.IngestionDataConflictException;
import com.stockquant.server.agent.ingestion.IngestionModels.AppendSecurityStatusRawCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestContractVersion;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.SealRunCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.StartRunCommand;
import com.stockquant.server.agent.ingestion.MarketDataIngestionService;
import com.stockquant.server.agent.ingestion.SecurityEventCanonicalHasher;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializationResult;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializeSecurityStatusCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.NormalizationOutcome;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.RegisterSecurityIdentityCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.RegisterSourceSecurityIdentityMappingCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityIdentity;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SourceSecurityIdentityMapping;
import com.stockquant.server.agent.ingestion.SecurityIdentityService;
import com.stockquant.server.agent.ingestion.SecurityStatusEventMaterializationService;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentStage2D2B1B1EventMaterializationPostgresIntegrationTest {

    private static final String SCHEMA_PREFIX = "stage_2d2b1b1_it_";
    private static final String LEGACY_PREFIX = "stage_2d2b1b1_legacy_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final String SOURCE = "TEST_STAGE_2D2B1B1_SECURITY";
    private static final String SOURCE_VERSION = "raw-test-v1";
    private static final String CONNECTOR_VERSION = "fixture-connector-v1";
    private static final LocalDate RANGE_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate RANGE_END = LocalDate.of(2025, 12, 31);
    private static final Instant FETCHED_AT = Instant.parse("2025-01-01T03:00:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2025-01-01T01:00:00Z");

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TemporalMarketFoundationService temporal;
    @Autowired MarketDataIngestionService ingestion;
    @Autowired SecurityIdentityService identities;
    @Autowired SecurityStatusEventMaterializationService materialization;
    @Autowired IngestionCanonicalHasher ingestionHasher;
    @Autowired SecurityEventCanonicalHasher eventHasher;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        credentials = AgentPostgresTestEnvironment.validate(
                System.getenv("STOCK_QUANT_TEST_DB_URL"),
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME"),
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD"));
        createIsolatedSchema();
        String separator = credentials.url().contains("?") ? "&" : "?";
        String schemaUrl = credentials.url() + separator + "currentSchema=" + TEST_SCHEMA;
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", credentials::username);
        registry.add("spring.datasource.password", credentials::password);
        registry.add("spring.flyway.default-schema", () -> TEST_SCHEMA);
        registry.add("spring.flyway.schemas", () -> TEST_SCHEMA);
        registry.add("spring.flyway.create-schemas", () -> false);
    }

    @AfterAll
    static void dropIsolatedSchemaAndVerifyPublicUnchanged() throws Exception {
        if (!schemaCreated) return;
        requireSafeSchemaName(TEST_SCHEMA, SCHEMA_PREFIX);
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + TEST_SCHEMA + "\" CASCADE");
            schemaCreated = false;
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            assertEquals(publicBaseline, publicBaseline(statement));
        }
    }

    @Test
    @Order(1)
    void migratesLegacyAndEnforcesAtomicMaterializationAndManifestV2() throws Exception {
        assertDedicatedDatabaseAndMigration();
        assertV8SchemaObjects();
        assertCanonicalGoldenVectors();
        assertLegacyMigrationIsConservative();

        DatasetVersion dataset = registerDataset("primary", TemporalTrustLevel.OBSERVED);
        IngestionRun v1Run = ingestion.startRun(startCommand(dataset, "v1-compatible"));
        assertEquals(ManifestContractVersion.INGESTION_MANIFEST_V1,
                v1Run.manifestContractVersion());
        IngestionRun run = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "event-chain"));
        assertEquals(ManifestContractVersion.INGESTION_MANIFEST_V2_SECURITY_EVENT,
                run.manifestContractVersion());
        assertThrows(IngestionDataConflictException.class, () ->
                ingestion.recordSecurityStatusAttempt(new com.stockquant.server.agent.ingestion
                        .IngestionModels.RecordAttemptCommand(
                        run.id(), 1, 1,
                        com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus.REJECTED,
                        "processor", SecurityEventMaterializationModels.RAW_CONTRACT_VERSION,
                        PublicationTimeVerification.VERIFIED,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED, "MUST_FAIL",
                        objectMapper.createObjectNode())));
        assertThrows(IngestionDataConflictException.class, () ->
                ingestion.startSecurityEventMaterializationRun(new StartRunCommand(
                        dataset.id(), DatasetType.SECURITY_STATUS, RunNamespace.FORMAL,
                        OperationType.INGEST, "formal", RANGE_START, RANGE_END, null)));

        SecurityIdentity identity = identities.registerIdentity(new RegisterSecurityIdentityCommand(
                RunNamespace.TEST, "TEST_AUTHORITY", "stable-instrument-1",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        assertEquals(identity.id(), identities.registerIdentity(new RegisterSecurityIdentityCommand(
                RunNamespace.TEST, "TEST_AUTHORITY", "stable-instrument-1",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED)).id());
        SourceSecurityIdentityMapping mapping = identities.registerMapping(
                new RegisterSourceSecurityIdentityMappingCommand(
                        RunNamespace.TEST, SOURCE, SOURCE_VERSION, "instrument-1",
                        identity.securityLogicalKey(),
                        SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED));
        assertNotNull(mapping.mappingLogicalKey());
        SecurityIdentity conflictingIdentity = identities.registerIdentity(
                new RegisterSecurityIdentityCommand(
                        RunNamespace.TEST, "TEST_AUTHORITY", "stable-instrument-conflict",
                        SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED));
        assertThrows(IngestionDataConflictException.class, () -> identities.registerMapping(
                new RegisterSourceSecurityIdentityMappingCommand(
                        RunNamespace.TEST, SOURCE, SOURCE_VERSION, "instrument-1",
                        conflictingIdentity.securityLogicalKey(),
                        SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED)));

        RawRecord initial = appendRaw(run, "event-1", "1", "instrument-1",
                LocalDate.of(2025, 1, 2), rawPayload("600000", "SSE", "MAIN", true, true, false));
        RawRecord noChange = appendRaw(run, "event-2", "1", "instrument-1",
                LocalDate.of(2025, 1, 3), rawPayload("600000", "SSE", "MAIN", true, true, false));
        RawRecord stChange = appendRaw(run, "event-3", "1", "instrument-1",
                LocalDate.of(2025, 1, 4), rawPayload("600000", "SSE", "MAIN", true, true, true));

        MaterializationResult first = materialization.materialize(command(run, initial, 1));
        MaterializationResult unchanged = materialization.materialize(command(run, noChange, 1));
        MaterializationResult changed = materialization.materialize(command(run, stChange, 1));
        assertEquals(NormalizationOutcome.EVENT_MATERIALIZED,
                first.normalizationResult().outcome());
        assertEquals(NormalizationOutcome.NO_STATE_CHANGE,
                unchanged.normalizationResult().outcome());
        assertNull(unchanged.event());
        assertEquals(NormalizationOutcome.EVENT_MATERIALIZED,
                changed.normalizationResult().outcome());
        assertEquals("ST_CHANGE", changed.event().eventType().name());
        assertEquals(first.event().id(), materialization.materialize(command(run, initial, 1))
                .event().id());

        IngestionRun sealed = materialization.sealRun(
                new SealRunCommand(run.id(), RunStatus.COMPLETED, 3));
        assertEquals(3, sealed.finalReceivedCount());
        assertEquals(3, sealed.finalAcceptedCount());
        assertEquals(0, sealed.finalRejectedCount());
        assertEquals(sealed.manifestHash(), jdbc.queryForObject("""
                SELECT compute_security_event_manifest_v2_hash(
                    ?, status, final_expected_count, final_received_count,
                    final_accepted_count, final_rejected_count, assurance_level)
                FROM market_data_ingestion_runs WHERE id=?
                """, String.class, run.id(), run.id()));
        assertEquals("be34961409564a83dd31196302f0f2e8336f477b7957112c4e598f54ad76628c", sealed.manifestHash());
        assertEquals(2, count("security_status_events"));
        assertEquals(2, count("security_status_event_lineage"));
        assertEquals(3, count("security_status_normalization_results"));

        IngestionRun reuseRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "event-reuse"));
        RawRecord reusedRaw = appendRaw(reuseRun, "event-1", "1", "instrument-1",
                LocalDate.of(2025, 1, 2), rawPayload("600000", "SSE", "MAIN", true, true, false));
        assertEquals(initial.id(), reusedRaw.id());
        MaterializationResult reused = materialization.materialize(command(reuseRun, reusedRaw, 1));
        assertEquals(NormalizationOutcome.EVENT_REUSED, reused.normalizationResult().outcome());
        assertEquals(first.event().id(), reused.event().id());
        materialization.sealRun(new SealRunCommand(reuseRun.id(), RunStatus.COMPLETED, 1));
        assertEquals(2, count("security_status_events"));
        assertEquals(2, count("security_status_event_lineage"));

        IngestionRun unresolvedRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "identity-unresolved"));
        RawRecord unresolvedRaw = appendRaw(unresolvedRun, "event-unresolved", "1",
                "missing-instrument", LocalDate.of(2025, 2, 1),
                rawPayload("600099", "SSE", "MAIN", true, true, false));
        MaterializationResult unresolved = materialization.materialize(
                command(unresolvedRun, unresolvedRaw, 1));
        assertEquals(NormalizationOutcome.IDENTITY_UNRESOLVED,
                unresolved.normalizationResult().outcome());
        assertNull(unresolved.event());
        SecurityIdentity recoveredIdentity = identities.registerIdentity(
                new RegisterSecurityIdentityCommand(
                        RunNamespace.TEST, "TEST_AUTHORITY", "stable-instrument-recovered",
                        SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.TEST, SOURCE, SOURCE_VERSION, "missing-instrument",
                recoveredIdentity.securityLogicalKey(),
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        MaterializationResult recovered = materialization.materialize(
                command(unresolvedRun, unresolvedRaw, 2));
        assertEquals(NormalizationOutcome.EVENT_MATERIALIZED,
                recovered.normalizationResult().outcome());
        IngestionRun recoveredSealed = materialization.sealRun(
                new SealRunCommand(unresolvedRun.id(), RunStatus.COMPLETED, 1));
        assertEquals(1, recoveredSealed.finalAcceptedCount());
        assertEquals(0, recoveredSealed.finalRejectedCount());
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_processing_attempts
                WHERE ingestion_run_id=?
                """, Integer.class, unresolvedRun.id()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_normalization_results result
                JOIN security_status_processing_attempts attempt
                  ON attempt.id=result.processing_attempt_id
                WHERE attempt.ingestion_run_id=?
                """, Integer.class, unresolvedRun.id()));

        IngestionRun unsupportedVersionRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "unsupported-materialization-version"));
        RawRecord unsupportedVersionRaw = appendRaw(
                unsupportedVersionRun, "unsupported-materialization-version", "1",
                "instrument-1", LocalDate.of(2025, 5, 1),
                rawPayload("600000", "SSE", "MAIN", true, true, true));
        MaterializationResult unsupportedVersion = materialization.materialize(
                new MaterializeSecurityStatusCommand(
                        unsupportedVersionRun.id(), unsupportedVersionRaw.id(), 1,
                        PublicationTimeVerification.VERIFIED,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED, "processor-v1",
                        SecurityEventMaterializationModels.RAW_CONTRACT_VERSION,
                        "SECURITY_STATUS_NORMALIZER_UNKNOWN",
                        SecurityEventMaterializationModels.TRANSITION_RULE_VERSION));
        assertEquals(NormalizationOutcome.UNSUPPORTED_CONTRACT,
                unsupportedVersion.normalizationResult().outcome());
        assertEquals("UNSUPPORTED_MATERIALIZATION_CONTRACT",
                unsupportedVersion.normalizationResult().errorCode());
        assertNull(unsupportedVersion.event());
        materialization.sealRun(new SealRunCommand(
                unsupportedVersionRun.id(), RunStatus.FAILED, 1));

        assertAppendOnly("security_identity_registry", identity.id(),
                "identity_authority=identity_authority", "0A000", "security_identity_registry");
        assertAppendOnly("source_security_identity_mappings", mapping.id(),
                "source=source", "0A000", "source_security_identity_mappings");
        assertAppendOnly("security_status_event_lineage", first.lineage().id(),
                "normalizer_version=normalizer_version", "55000", "append-only");
        assertAppendOnly("security_status_normalization_results",
                first.normalizationResult().id(), "normalizer_version=normalizer_version",
                "55000", "append-only");
        assertDirectAttemptRequiresResult(dataset);
        assertDirectResolvedEventRequiresLineage(dataset);
        assertDirectEventChainGuards(dataset, first.event(), identity, recovered.event());
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    @Order(2)
    void serializesTwoRealBackendsToOneEventAndOneLineage() throws Exception {
        DatasetVersion dataset = registerDataset("concurrent", TemporalTrustLevel.OBSERVED);
        SecurityIdentity identity = identities.registerIdentity(new RegisterSecurityIdentityCommand(
                RunNamespace.DEMO, "DEMO_AUTHORITY", "stable-concurrent",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                AssuranceLevel.INFERRED_RESEARCH));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.DEMO, SOURCE, SOURCE_VERSION, "instrument-concurrent",
                identity.securityLogicalKey(), SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.INFERRED_RESEARCH));
        IngestionRun firstRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, RunNamespace.DEMO, "concurrent-first"));
        IngestionRun secondRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, RunNamespace.DEMO, "concurrent-second"));
        JsonNode payload = rawPayload("000001", "SZSE", "MAIN", true, true, false);
        RawRecord firstRaw = appendRaw(firstRun, "concurrent-event", "1",
                "instrument-concurrent", LocalDate.of(2025, 3, 1), payload);
        RawRecord secondRaw = appendRaw(secondRun, "concurrent-event", "1",
                "instrument-concurrent", LocalDate.of(2025, 3, 1), payload);
        assertEquals(firstRaw.id(), secondRaw.id());

        List<ConcurrentResult<MaterializationResult>> results = runConcurrent(List.of(
                () -> materialization.materialize(command(firstRun, firstRaw, 1)),
                () -> materialization.materialize(command(secondRun, secondRaw, 1))));
        assertEquals(2, results.size());
        assertNotEquals(results.get(0).backendPid(), results.get(1).backendPid());
        assertEquals(results.get(0).value().event().id(), results.get(1).value().event().id());
        assertEquals(1, results.stream().filter(value -> value.value().normalizationResult().outcome()
                == NormalizationOutcome.EVENT_MATERIALIZED).count());
        assertEquals(1, results.stream().filter(value -> value.value().normalizationResult().outcome()
                == NormalizationOutcome.EVENT_REUSED).count());
        long eventId = results.get(0).value().event().id();
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_events WHERE id=?", Integer.class, eventId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_event_lineage WHERE event_id=?",
                Integer.class, eventId));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_normalization_results
                WHERE event_id=?
                """, Integer.class, eventId));
        materialization.sealRun(new SealRunCommand(firstRun.id(), RunStatus.COMPLETED, 1));
        materialization.sealRun(new SealRunCommand(secondRun.id(), RunStatus.COMPLETED, 1));
        assertEquals(AssuranceLevel.INFERRED_RESEARCH,
                results.get(0).value().event().assuranceLevel());
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    @Order(3)
    void serializesCompetingRootRevisionsAndReturnsOneExplicitLoser() throws Exception {
        DatasetVersion dataset = registerDataset("competing-root", TemporalTrustLevel.OBSERVED);
        SecurityIdentity identity = identities.registerIdentity(new RegisterSecurityIdentityCommand(
                RunNamespace.TEST, "TEST_AUTHORITY", "stable-competing-root",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.TEST, SOURCE, SOURCE_VERSION, "instrument-competing-root",
                identity.securityLogicalKey(), SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        IngestionRun firstRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "competing-root-first"));
        IngestionRun secondRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "competing-root-second"));
        JsonNode payload = rawPayload("600088", "SSE", "MAIN", true, true, false);
        RawRecord firstRaw = appendRaw(firstRun, "competing-root-a", "1",
                "instrument-competing-root", LocalDate.of(2025, 9, 1), payload);
        RawRecord secondRaw = appendRaw(secondRun, "competing-root-b", "1",
                "instrument-competing-root", LocalDate.of(2025, 9, 1), payload);

        List<ConcurrentResult<MaterializationResult>> results = runConcurrent(List.of(
                () -> materialization.materialize(command(firstRun, firstRaw, 1)),
                () -> materialization.materialize(command(secondRun, secondRaw, 1))));
        assertNotEquals(results.get(0).backendPid(), results.get(1).backendPid());
        assertEquals(1, results.stream().filter(value -> value.value().normalizationResult().outcome()
                == NormalizationOutcome.EVENT_MATERIALIZED).count());
        assertEquals(1, results.stream().filter(value -> value.value().normalizationResult().outcome()
                == NormalizationOutcome.UNSUPPORTED_CONTRACT).count());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_events
                WHERE security_logical_key=? AND event_contract_version='SECURITY_STATUS_EVENT_V1'
                """, Integer.class, identity.securityLogicalKey()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_event_lineage lineage
                JOIN security_status_events event ON event.id=lineage.event_id
                WHERE event.security_logical_key=?
                """, Integer.class, identity.securityLogicalKey()));

        for (ConcurrentResult<MaterializationResult> result : results) {
            RunStatus status = result.value().normalizationResult().outcome()
                    == NormalizationOutcome.EVENT_MATERIALIZED
                    ? RunStatus.COMPLETED : RunStatus.FAILED;
            long runId = result.value().attempt().ingestionRunId();
            materialization.sealRun(new SealRunCommand(runId, status, 1));
        }
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private void assertDedicatedDatabaseAndMigration() {
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(TEST_SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8"), jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",
                (row, index) -> row.getString(1)));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success=FALSE", Integer.class));
    }

    private void assertV8SchemaObjects() {
        for (String table : List.of("security_identity_registry",
                "source_security_identity_mappings", "security_status_normalization_results",
                "security_status_event_lineage")) {
            assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)", String.class,
                    TEST_SCHEMA + "." + table));
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='market_data_ingestion_runs'
                  AND column_name='manifest_contract_version' AND is_nullable='NO'
                """, Integer.class, TEST_SCHEMA));
        assertEquals(5, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='security_status_events'
                  AND column_name IN ('event_logical_key','event_contract_version',
                                      'record_namespace','assurance_level','security_logical_key')
                """, Integer.class, TEST_SCHEMA));
        for (String trigger : List.of(
                "trg_security_identity_registry_immutable",
                "trg_source_security_identity_mappings_immutable",
                "trg_security_status_event_lineage_immutable",
                "trg_security_status_normalization_results_immutable",
                "trg_security_status_processing_attempts_v2_result",
                "trg_security_status_events_v8_lineage")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM pg_trigger t
                    JOIN pg_class c ON c.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname=? AND t.tgname=?
                    """, Integer.class, TEST_SCHEMA, trigger));
        }
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger t
                JOIN pg_class c ON c.oid=t.tgrelid
                JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND t.tgdeferrable AND t.tginitdeferred
                  AND t.tgname IN ('trg_security_status_processing_attempts_v2_result',
                                   'trg_security_status_events_v8_lineage')
                """, Integer.class, TEST_SCHEMA));
        for (String constraint : List.of(
                "uq_security_status_events_source_record",
                "uq_security_status_events_event_logical_key",
                "uq_security_identity_registry_logical_key",
                "uq_source_security_identity_mappings_business_key",
                "uq_security_status_normalization_results_attempt",
                "uq_security_status_event_lineage_event",
                "uq_security_status_event_lineage_attempt")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint p
                    JOIN pg_namespace n ON n.oid=p.connamespace
                    WHERE n.nspname=? AND p.conname=?
                    """, Integer.class, TEST_SCHEMA, constraint));
        }
        for (String index : List.of(
                "uq_security_status_events_full_root",
                "uq_security_status_events_superseded_once")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM pg_indexes
                    WHERE schemaname=? AND indexname=?
                    """, Integer.class, TEST_SCHEMA, index));
        }
        String sourceRevisionConstraint = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(constraint_record.oid)
                FROM pg_constraint constraint_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=constraint_record.connamespace
                WHERE schema_record.nspname=?
                  AND constraint_record.conname='uq_security_status_events_source_record'
                """, String.class, TEST_SCHEMA);
        assertNotNull(sourceRevisionConstraint);
        assertTrue(sourceRevisionConstraint.contains(
                "record_namespace, source, source_version, source_record_id, source_revision"));
    }

    private void assertCanonicalGoldenVectors() {
        assertEquals(
                "security:v1:429d639f9e141c29e34fa2b2718ee7db53d86e1878e3f1979f9c346f9e58ffb4",
                jdbc.queryForObject("""
                        SELECT compute_security_identity_logical_key(
                            'TEST','TEST_AUTHORITY','instrument-1','SECURITY_IDENTITY_V1')
                        """, String.class));
        assertEquals(
                "mapping:v1:ea96def660a1369c4d14f10ebe0009c0180dfd2481ff8538f575c40b9a1cc765",
                jdbc.queryForObject("""
                        SELECT compute_source_security_mapping_logical_key(
                            'TEST','SOURCE','source-v1','instrument-1',
                            'SOURCE_SECURITY_IDENTITY_MAPPING_V1')
                        """, String.class));
        String eventKey =
                "event:v1:621749331ab96f46b63d025ae3244526d55ce62f45aa4a95e1598f990c27a7d2";
        assertEquals(eventKey, jdbc.queryForObject("""
                SELECT compute_security_event_logical_key(
                    ?, 'SECURITY_STATUS_EVENT_V1', 'FULL_STATUS_SNAPSHOT')
                """, String.class, "raw:v1:" + "a".repeat(64)));
        assertEquals(
                "4c9260a4ff827d886dfbe3ba5a5aa13d689b7ba46df84e31f26fc0075e8a25a4",
                jdbc.queryForObject("""
                        SELECT compute_security_normalization_result_hash(
                            ?, 'EVENT_MATERIALIZED', ?, ?, NULL,
                            'SECURITY_STATUS_NORMALIZER_V1',
                            'SECURITY_STATUS_TRANSITION_V1',
                            'RECONSTRUCTED_VERIFIED', NULL)
                        """, String.class, "attempt:v1:" + "b".repeat(64), eventKey,
                        "security:v1:429d639f9e141c29e34fa2b2718ee7db53d86e1878e3f1979f9c346f9e58ffb4"));
        assertEquals(
                "f721031cdf601739e25cb290cca72be11e66b88559a8693676293af54fdcd87a",
                jdbc.queryForObject("""
                        SELECT compute_security_event_lineage_hash(
                            ?, ?, ?, ?, ?, ?, ?, NULL, 'TEST',
                            'SECURITY_STATUS_EVENT_V1', 'SECURITY_STATUS_NORMALIZER_V1',
                            'SECURITY_STATUS_TRANSITION_V1', ?, 'RECONSTRUCTED_VERIFIED')
                        """, String.class, eventKey, "dataset:v1:" + "c".repeat(64),
                        "raw:v1:" + "a".repeat(64), "run:v1:" + "d".repeat(64),
                        "attempt:v1:" + "b".repeat(64),
                        "mapping:v1:ea96def660a1369c4d14f10ebe0009c0180dfd2481ff8538f575c40b9a1cc765",
                        "security:v1:429d639f9e141c29e34fa2b2718ee7db53d86e1878e3f1979f9c346f9e58ffb4",
                        "e".repeat(64)));
    }

    private void assertLegacyMigrationIsConservative() throws Exception {
        String schema = LEGACY_PREFIX + UUID.randomUUID().toString().replace("-", "");
        requireSafeSchemaName(schema, LEGACY_PREFIX);
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        try {
            Flyway.configure().dataSource(schemaUrl(schema), credentials.username(), credentials.password())
                    .defaultSchema(schema).schemas(schema).createSchemas(false)
                    .target(MigrationVersion.fromVersion("7")).load().migrate();
            try (Connection connection = schemaConnection(schema)) {
                connection.setAutoCommit(false);
                long datasetId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO market_data_dataset_versions(
                            dataset_type, source, source_version, connector_version,
                            range_start, range_end, fetched_at, recorded_at,
                            payload_hash, trust_level, metadata)
                        VALUES ('SECURITY_STATUS','LEGACY_SOURCE','V1','LEGACY_CONNECTOR',
                                DATE '2025-01-01',DATE '2025-12-31',?,?,?,
                                'BACKFILLED_INFERRED','{}'::jsonb) RETURNING id
                        """)) {
                    bind(statement, FETCHED_AT, FETCHED_AT, "9".repeat(64));
                    try (ResultSet rows = statement.executeQuery()) {
                        assertTrue(rows.next());
                        datasetId = rows.getLong(1);
                    }
                }
                JsonNode payload = SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusEventPayloadContract.SecurityStatusState(
                                com.stockquant.server.agent.temporal.MarketExchange.SSE,
                                "MAIN", true, true, false));
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO security_status_events(
                            dataset_version_id,symbol,event_type,effective_from,published_at,
                            known_at,recorded_at,source,source_version,source_record_id,
                            source_revision,trust_level,payload,payload_hash)
                        VALUES (?,'600010','FULL_STATUS_SNAPSHOT',DATE '2025-01-02',?,?,?,
                                'LEGACY_SOURCE','V1','legacy-record','1','BACKFILLED_INFERRED',
                                ?::jsonb,?)
                        """)) {
                    bind(statement, datasetId, PUBLISHED_AT, FETCHED_AT, FETCHED_AT,
                            payload.toString(), SecurityStatusEventPayloadContract.hash(payload));
                    statement.executeUpdate();
                }
                connection.commit();
            }
            Flyway.configure().dataSource(schemaUrl(schema), credentials.username(), credentials.password())
                    .defaultSchema(schema).schemas(schema).createSchemas(false).load().migrate();
            try (Connection connection = schemaConnection(schema); Statement statement = connection.createStatement();
                 ResultSet row = statement.executeQuery("""
                         SELECT record_namespace, assurance_level, event_logical_key,
                                event_contract_version, security_logical_key
                         FROM security_status_events WHERE source='LEGACY_SOURCE'
                         """)) {
                assertTrue(row.next());
                assertEquals("DEMO", row.getString(1));
                assertEquals("INFERRED_RESEARCH", row.getString(2));
                assertNull(row.getString(3));
                assertNull(row.getString(4));
                assertNull(row.getString(5));
            }
        } finally {
            try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
                assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                        + "WHERE schema_name='" + schema + "'"));
            }
        }
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private void assertAppendOnly(
            String table,
            long id,
            String assignment,
            String truncateSqlState,
            String truncateMessage
    ) {
        expectFailure("55000", "append-only", "UPDATE " + table + " SET " + assignment
                + " WHERE id=?", id);
        expectFailure("55000", "append-only", "DELETE FROM " + table + " WHERE id=?", id);
        expectFailure(truncateSqlState, truncateMessage, "TRUNCATE TABLE " + table);
    }

    private void assertDirectAttemptRequiresResult(DatasetVersion dataset) throws Exception {
        IngestionRun run = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "direct-attempt"));
        RawRecord raw = appendRaw(run, "direct-attempt", "1", "instrument-1",
                LocalDate.of(2025, 4, 1), rawPayload("600000", "SSE", "MAIN", true, true, true));
        String attemptKey = ingestionHasher.attemptLogicalKey(run.logicalKey(), raw.logicalKey(), 1,
                "processor-v1", SecurityEventMaterializationModels.RAW_CONTRACT_VERSION);
        expectCommitFailure("23514", "requires one normalization result", """
                INSERT INTO security_status_processing_attempts(
                    ingestion_run_id,raw_record_id,attempt_no,attempt_logical_key,status,
                    processor_version,contract_version,published_at_verification,
                    requested_assurance_level,derived_known_from,knowledge_time_policy_version,
                    assurance_level,error_code,result_metadata,result_hash)
                VALUES (?,?,1,?,'COMPLETED','processor-v1','SECURITY_STATUS_RAW_TEST_V1',
                        'VERIFIED','RECONSTRUCTED_VERIFIED',?,'KNOWLEDGE_TIME_POLICY_V1',
                        'RECONSTRUCTED_VERIFIED',NULL,'{}'::jsonb,?)
                """, run.id(), raw.id(), attemptKey, raw.sourcePublishedAt(),
                ingestionHasher.jsonHash(objectMapper.createObjectNode()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_processing_attempts
                WHERE ingestion_run_id=? AND raw_record_id=?
                """, Integer.class, run.id(), raw.id()));
    }

    private void assertDirectResolvedEventRequiresLineage(DatasetVersion dataset) throws Exception {
        SecurityIdentity identity = identities.registerIdentity(new RegisterSecurityIdentityCommand(
                RunNamespace.TEST, "TEST_AUTHORITY", "stable-direct-event",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.TEST, SOURCE, SOURCE_VERSION, "instrument-direct-event",
                identity.securityLogicalKey(),
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        IngestionRun run = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "direct-event"));
        RawRecord raw = appendRaw(run, "direct-event", "1", "instrument-direct-event",
                LocalDate.of(2025, 5, 1), rawPayload("600000", "SSE", "MAIN", true, true, false));
        JsonNode eventPayload = SecurityStatusEventPayloadContract.payload(
                new SecurityStatusEventPayloadContract.SecurityStatusState(
                        com.stockquant.server.agent.temporal.MarketExchange.SSE,
                        "MAIN", true, true, false));
        String eventKey = eventHasher.eventLogicalKey(raw.logicalKey(),
                SecurityStatusEventPayloadContract.VERSION,
                com.stockquant.server.agent.temporal.SecurityStatusEventType.FULL_STATUS_SNAPSHOT);
        expectCommitFailure("23514", "requires one authoritative lineage", """
                INSERT INTO security_status_events(
                    dataset_version_id,symbol,event_type,effective_from,effective_to,
                    published_at,known_at,recorded_at,source,source_version,source_record_id,
                    source_revision,trust_level,payload,payload_hash,supersedes_event_id,
                    event_logical_key,event_contract_version,record_namespace,assurance_level,
                    security_logical_key)
                VALUES (?,'600000','FULL_STATUS_SNAPSHOT',?,NULL,?,?,?,?,?,?,?,?,?::jsonb,?,NULL,
                        ?,'SECURITY_STATUS_EVENT_V1','TEST','RECONSTRUCTED_VERIFIED',?)
                """, dataset.id(), raw.sourceEffectiveDate(), raw.sourcePublishedAt(),
                raw.sourcePublishedAt(), raw.sourcePublishedAt(), raw.source(), raw.sourceVersion(),
                raw.sourceRecordId(), raw.sourceRevision(), TemporalTrustLevel.OBSERVED.name(),
                eventPayload.toString(), SecurityStatusEventPayloadContract.hash(eventPayload),
                eventKey, identity.securityLogicalKey());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM security_status_events WHERE event_logical_key=?",
                Integer.class, eventKey));
    }

    private void assertDirectEventChainGuards(
            DatasetVersion dataset,
            com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels
                    .MaterializedSecurityEvent root,
            SecurityIdentity rootIdentity,
            com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels
                    .MaterializedSecurityEvent availablePredecessor
    ) throws Exception {
        IngestionRun duplicateRootRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "direct-duplicate-root"));
        RawRecord duplicateRootRaw = appendRaw(
                duplicateRootRun, "direct-duplicate-root", "1", "instrument-1",
                LocalDate.of(2025, 6, 1),
                rawPayload("600000", "SSE", "MAIN", true, true, false));
        expectResolvedEventFailure(
                "23505", "uq_security_status_events_full_root", dataset, duplicateRootRaw,
                rootIdentity, com.stockquant.server.agent.temporal.SecurityStatusEventType
                        .FULL_STATUS_SNAPSHOT,
                null, SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusEventPayloadContract.SecurityStatusState(
                                com.stockquant.server.agent.temporal.MarketExchange.SSE,
                                "MAIN", true, true, false)),
                SecurityStatusEventPayloadContract.VERSION);

        IngestionRun duplicateSuccessorRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "direct-duplicate-successor"));
        RawRecord duplicateSuccessorRaw = appendRaw(
                duplicateSuccessorRun, "direct-duplicate-successor", "1", "instrument-1",
                LocalDate.of(2025, 7, 1),
                rawPayload("600000", "SSE", "OTHER_MAIN", true, true, false));
        expectResolvedEventFailure(
                "23505", "uq_security_status_events_superseded_once", dataset,
                duplicateSuccessorRaw, rootIdentity,
                com.stockquant.server.agent.temporal.SecurityStatusEventType.BOARD_CHANGE,
                root, SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusEventPayloadContract.SecurityStatusState(
                                com.stockquant.server.agent.temporal.MarketExchange.SSE,
                                "OTHER_MAIN", true, true, false)),
                SecurityStatusEventPayloadContract.VERSION);

        SecurityIdentity otherIdentity = identities.registerIdentity(
                new RegisterSecurityIdentityCommand(
                        RunNamespace.TEST, "TEST_AUTHORITY", "stable-cross-identity",
                        SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.TEST, SOURCE, SOURCE_VERSION, "instrument-cross-identity",
                otherIdentity.securityLogicalKey(),
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED));
        IngestionRun crossIdentityRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, "direct-cross-identity"));
        RawRecord crossIdentityRaw = appendRaw(
                crossIdentityRun, "direct-cross-identity", "1", "instrument-cross-identity",
                LocalDate.of(2025, 8, 1),
                rawPayload("600000", "SSE", "MAIN", true, true, true));
        expectResolvedEventFailure(
                "23514", "predecessor chain is invalid", dataset, crossIdentityRaw,
                otherIdentity, com.stockquant.server.agent.temporal.SecurityStatusEventType.ST_CHANGE,
                availablePredecessor, SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusEventPayloadContract.SecurityStatusState(
                                com.stockquant.server.agent.temporal.MarketExchange.SSE,
                                "MAIN", true, true, true)),
                SecurityStatusEventPayloadContract.VERSION);

        SecurityIdentity demoIdentity = identities.registerIdentity(
                new RegisterSecurityIdentityCommand(
                        RunNamespace.DEMO, "DEMO_AUTHORITY", "stable-cross-namespace",
                        SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                        AssuranceLevel.INFERRED_RESEARCH));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.DEMO, SOURCE, SOURCE_VERSION, "instrument-cross-namespace",
                demoIdentity.securityLogicalKey(),
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.INFERRED_RESEARCH));
        IngestionRun crossNamespaceRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, RunNamespace.DEMO, "direct-cross-namespace"));
        RawRecord crossNamespaceRaw = appendRaw(
                crossNamespaceRun, "direct-cross-namespace", "1", "instrument-cross-namespace",
                LocalDate.of(2025, 8, 2),
                rawPayload("600000", "SSE", "MAIN", true, true, true));
        expectResolvedEventFailure(
                "23514", "predecessor chain is invalid", dataset, crossNamespaceRaw,
                demoIdentity, com.stockquant.server.agent.temporal.SecurityStatusEventType.ST_CHANGE,
                availablePredecessor, SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusEventPayloadContract.SecurityStatusState(
                                com.stockquant.server.agent.temporal.MarketExchange.SSE,
                                "MAIN", true, true, true)),
                SecurityStatusEventPayloadContract.VERSION);

        expectResolvedEventFailure(
                "23514", "complete TEST/DEMO V1 logical identity", dataset,
                duplicateRootRaw, rootIdentity,
                com.stockquant.server.agent.temporal.SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                null, SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusEventPayloadContract.SecurityStatusState(
                                com.stockquant.server.agent.temporal.MarketExchange.SSE,
                                "MAIN", true, true, false)),
                "SECURITY_STATUS_EVENT_UNKNOWN");

        JsonNode legacyPayload = SecurityStatusEventPayloadContract.payload(
                new SecurityStatusEventPayloadContract.SecurityStatusState(
                        com.stockquant.server.agent.temporal.MarketExchange.SZSE,
                        "MAIN", true, true, false));
        jdbc.queryForObject("""
                INSERT INTO security_status_events(
                    dataset_version_id,symbol,event_type,effective_from,published_at,
                    known_at,recorded_at,source,source_version,source_record_id,
                    source_revision,trust_level,payload,payload_hash,record_namespace,
                    assurance_level)
                VALUES (?,'000099','FULL_STATUS_SNAPSHOT',DATE '2025-01-02',?,?,?,?,?,
                        'legacy-blocked','1','BACKFILLED_INFERRED',?::jsonb,?,
                        'DEMO','INFERRED_RESEARCH')
                RETURNING id
                """, Long.class, dataset.id(), PUBLISHED_AT.atOffset(ZoneOffset.UTC),
                PUBLISHED_AT.atOffset(ZoneOffset.UTC), PUBLISHED_AT.atOffset(ZoneOffset.UTC),
                SOURCE, SOURCE_VERSION, legacyPayload.toString(),
                SecurityStatusEventPayloadContract.hash(legacyPayload));
        SecurityIdentity legacyIdentity = identities.registerIdentity(
                new RegisterSecurityIdentityCommand(
                        RunNamespace.DEMO, "DEMO_AUTHORITY", "stable-legacy-blocked",
                        SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION,
                        AssuranceLevel.INFERRED_RESEARCH));
        identities.registerMapping(new RegisterSourceSecurityIdentityMappingCommand(
                RunNamespace.DEMO, SOURCE, SOURCE_VERSION, "instrument-legacy-blocked",
                legacyIdentity.securityLogicalKey(),
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION,
                AssuranceLevel.INFERRED_RESEARCH));
        IngestionRun legacyRun = ingestion.startSecurityEventMaterializationRun(
                startCommand(dataset, RunNamespace.DEMO, "legacy-blocked"));
        RawRecord legacyRaw = appendRaw(
                legacyRun, "legacy-blocked", "1", "instrument-legacy-blocked",
                LocalDate.of(2025, 1, 2),
                rawPayload("000099", "SZSE", "MAIN", true, true, false));
        MaterializationResult legacyConflict = materialization.materialize(
                command(legacyRun, legacyRaw, 1));
        assertEquals(NormalizationOutcome.CONFLICT,
                legacyConflict.normalizationResult().outcome());
        assertEquals("LEGACY_EVENT_IDENTITY_UNRESOLVED",
                legacyConflict.normalizationResult().errorCode());
        assertNull(legacyConflict.event());
    }

    private void expectResolvedEventFailure(
            String sqlState,
            String message,
            DatasetVersion dataset,
            RawRecord raw,
            SecurityIdentity identity,
            com.stockquant.server.agent.temporal.SecurityStatusEventType eventType,
            com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels
                    .MaterializedSecurityEvent predecessor,
            JsonNode eventPayload,
            String eventContractVersion
    ) {
        String eventKey = eventHasher.eventLogicalKey(
                raw.logicalKey(), eventContractVersion, eventType);
        expectFailure(sqlState, message, """
                INSERT INTO security_status_events(
                    dataset_version_id,symbol,event_type,effective_from,effective_to,
                    published_at,known_at,recorded_at,source,source_version,source_record_id,
                    source_revision,trust_level,payload,payload_hash,supersedes_event_id,
                    event_logical_key,event_contract_version,record_namespace,assurance_level,
                    security_logical_key)
                VALUES (?,?,?, ?,NULL,?,?,?,?,?,?,?,?,?::jsonb,?,?, ?,?,?,?,?)
                """, dataset.id(), raw.rawPayload().path("symbol").asText(),
                eventType.name(), raw.sourceEffectiveDate(), raw.sourcePublishedAt(),
                raw.sourcePublishedAt(), raw.sourcePublishedAt(), raw.source(),
                raw.sourceVersion(), raw.sourceRecordId(), raw.sourceRevision(),
                TemporalTrustLevel.OBSERVED.name(), eventPayload.toString(),
                SecurityStatusEventPayloadContract.hash(eventPayload),
                predecessor == null ? null : predecessor.id(), eventKey, eventContractVersion,
                raw.recordNamespace().name(),
                raw.recordNamespace() == RunNamespace.TEST
                        ? AssuranceLevel.RECONSTRUCTED_VERIFIED.name()
                        : AssuranceLevel.INFERRED_RESEARCH.name(),
                identity.securityLogicalKey());
    }

    private DatasetVersion registerDataset(String suffix, TemporalTrustLevel trust) {
        String payloadHash = ingestionHasher.jsonHash(
                objectMapper.createObjectNode().put("dataset", suffix));
        return temporal.registerDatasetVersion(new RegisterDatasetVersionCommand(
                DatasetType.SECURITY_STATUS.name(), SOURCE, SOURCE_VERSION, CONNECTOR_VERSION,
                RANGE_START, RANGE_END, FETCHED_AT, payloadHash, trust,
                objectMapper.createObjectNode().put("fixture", suffix)));
    }

    private StartRunCommand startCommand(DatasetVersion dataset, String requestKey) {
        return startCommand(dataset, RunNamespace.TEST, requestKey);
    }

    private StartRunCommand startCommand(
            DatasetVersion dataset,
            RunNamespace namespace,
            String requestKey
    ) {
        return new StartRunCommand(dataset.id(), DatasetType.SECURITY_STATUS, namespace,
                OperationType.INGEST, requestKey, RANGE_START, RANGE_END, null);
    }

    private RawRecord appendRaw(
            IngestionRun run,
            String recordId,
            String revision,
            String instrumentId,
            LocalDate effectiveDate,
            JsonNode payload
    ) {
        Instant effectiveAt = effectiveDate.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai"))
                .toInstant();
        return ingestion.appendSecurityStatusRaw(new AppendSecurityStatusRawCommand(
                run.id(), SOURCE, SOURCE_VERSION, recordId, revision, instrumentId,
                PUBLISHED_AT, effectiveDate, effectiveAt, TemporalTrustLevel.OBSERVED,
                payload, ingestionHasher.jsonHash(payload)));
    }

    private MaterializeSecurityStatusCommand command(IngestionRun run, RawRecord raw, int no) {
        return new MaterializeSecurityStatusCommand(run.id(), raw.id(), no,
                PublicationTimeVerification.VERIFIED, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                "processor-v1", SecurityEventMaterializationModels.RAW_CONTRACT_VERSION,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION);
    }

    private JsonNode rawPayload(
            String symbol,
            String exchange,
            String board,
            boolean listed,
            boolean active,
            boolean st
    ) throws Exception {
        return objectMapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":"%s",
                 "state":{"exchange":"%s","board":"%s","listed":%s,
                          "active":%s,"isSt":%s}}
                """.formatted(symbol, exchange, board, listed, active, st));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private List<ConcurrentResult<MaterializationResult>> runConcurrent(
            List<ConcurrentOperation<MaterializationResult>> operations
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<ConcurrentResult<MaterializationResult>>> futures = new ArrayList<>();
            for (ConcurrentOperation<MaterializationResult> operation : operations) {
                futures.add(executor.submit(() -> {
                    TransactionTemplate tx = new TransactionTemplate(transactionManager);
                    return tx.execute(status -> {
                        int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                        try {
                            barrier.await();
                            return new ConcurrentResult<>(pid, operation.execute());
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
                }));
            }
            List<ConcurrentResult<MaterializationResult>> values = new ArrayList<>();
            for (Future<ConcurrentResult<MaterializationResult>> future : futures) {
                values.add(future.get(30, TimeUnit.SECONDS));
            }
            return List.copyOf(values);
        } finally {
            executor.shutdownNow();
        }
    }

    private void expectFailure(
            String sqlState,
            String message,
            String sql,
            Object... parameters
    ) {
        try (Connection connection = isolatedConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            bind(statement, parameters);
            SQLException error = assertThrows(SQLException.class, statement::execute);
            assertDatabaseError(error, sqlState, message);
        } catch (SQLException error) {
            throw new AssertionError("could not execute isolated rejection probe", error);
        }
    }

    private void expectCommitFailure(
            String sqlState,
            String message,
            String sql,
            Object... parameters
    ) {
        try (Connection connection = isolatedConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            bind(statement, parameters);
            statement.execute();
            SQLException error = assertThrows(SQLException.class, connection::commit);
            assertDatabaseError(error, sqlState, message);
        } catch (SQLException error) {
            throw new AssertionError("could not execute isolated commit rejection probe", error);
        }
    }

    private static void assertDatabaseError(SQLException top, String state, String message) {
        SQLException current = top;
        while (current != null && !state.equals(current.getSQLState())) {
            current = current.getNextException();
        }
        assertNotNull(current, () -> "expected SQLSTATE " + state
                + " but top-level SQLSTATE was " + top.getSQLState());
        assertNotNull(current.getMessage());
        assertTrue(current.getMessage().contains(message), current.getMessage());
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            if (value instanceof Instant instant) {
                statement.setObject(index + 1, instant.atOffset(ZoneOffset.UTC));
            } else {
                statement.setObject(index + 1, value);
            }
        }
    }

    private static void createIsolatedSchema() {
        requireSafeSchemaName(TEST_SCHEMA, SCHEMA_PREFIX);
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_database()"));
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            publicBaseline = publicBaseline(statement);
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2d2b1b1_it_ schema creation", error);
        }
    }

    private static Connection controlConnection() throws SQLException {
        return DriverManager.getConnection(
                credentials.url(), credentials.username(), credentials.password());
    }

    private static Connection isolatedConnection() throws SQLException {
        return schemaConnection(TEST_SCHEMA);
    }

    private static Connection schemaConnection(String schema) throws SQLException {
        Connection connection = controlConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
        return connection;
    }

    private static String schemaUrl(String schema) {
        String separator = credentials.url().contains("?") ? "&" : "?";
        return credentials.url() + separator + "currentSchema=" + schema;
    }

    private static PublicBaseline currentPublicBaseline() throws Exception {
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            return publicBaseline(statement);
        }
    }

    private static PublicBaseline publicBaseline(Statement statement) throws Exception {
        Map<String, Long> rows = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            rows.put(table, scalar(statement,
                    "SELECT count(*) FROM public." + quoteIdentifier(table)));
        }
        List<String> objects = strings(statement, """
                SELECT kind || ':' || identity FROM (
                    SELECT 'relation' kind, c.relkind::text || ':' || c.relname identity
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'constraint', c.conname || ':' || c.contype::text || ':'
                           || coalesce(pg_get_constraintdef(c.oid),'')
                    FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'trigger', r.relname || ':' || t.tgname
                    FROM pg_trigger t JOIN pg_class r ON r.oid=t.tgrelid
                    JOIN pg_namespace n ON n.oid=r.relnamespace
                    WHERE n.nspname='public' AND NOT t.tgisinternal
                    UNION ALL
                    SELECT 'function', p.proname || ':' || pg_get_function_identity_arguments(p.oid)
                           || ':' || md5(pg_get_functiondef(p.oid))
                    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
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
                FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace
                ORDER BY e.extname
                """);
        return new PublicBaseline(Map.copyOf(rows), objects, history, extensions);
    }

    private static List<String> strings(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return List.copyOf(values);
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) throw new IllegalStateException("scalar query returned no row");
            return row.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql) throws Exception {
        try (ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) throw new IllegalStateException("scalar query returned no row");
            return row.getString(1);
        }
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static void requireSafeSchemaName(String schema, String prefix) {
        if (!schema.matches("^" + prefix + "[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe temporary schema name");
        }
    }

    @FunctionalInterface
    private interface ConcurrentOperation<T> {
        T execute() throws Exception;
    }

    private record ConcurrentResult<T>(int backendPid, T value) {}

    private record PublicBaseline(
            Map<String, Long> tableRows,
            List<String> schemaObjects,
            List<String> flywayHistory,
            List<String> extensions
    ) {}
}
