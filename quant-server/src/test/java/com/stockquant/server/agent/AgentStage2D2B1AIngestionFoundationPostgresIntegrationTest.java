package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.ingestion.IngestionCanonicalHasher;
import com.stockquant.server.agent.ingestion.IngestionDataConflictException;
import com.stockquant.server.agent.ingestion.IngestionModels.AppendSecurityStatusRawCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AppendTradingCalendarRawCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RecordAttemptCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.SealRunCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.StartRunCommand;
import com.stockquant.server.agent.ingestion.MarketDataIngestionService;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;
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
import java.math.BigDecimal;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentStage2D2B1AIngestionFoundationPostgresIntegrationTest {

    private static final String SCHEMA_PREFIX = "stage_2d2b1a_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final String SECURITY_SOURCE = "TEST_STAGE_2D2B1A_SECURITY";
    private static final String CALENDAR_SOURCE = "TEST_STAGE_2D2B1A_CALENDAR";
    private static final String SOURCE_VERSION = "source-neutral-v1";
    private static final String CONNECTOR_VERSION = "test-connector-v1";
    private static final LocalDate RANGE_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate RANGE_END = LocalDate.of(2025, 12, 31);
    private static final Instant FETCHED_AT = Instant.parse("2025-01-01T03:00:00Z");
    private static final Instant PUBLISHED_AT = Instant.parse("2025-01-01T01:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final List<String> INGESTION_TABLES = List.of(
            "market_data_ingestion_runs",
            "security_status_raw_records", "security_status_ingestion_run_records",
            "security_status_processing_attempts",
            "trading_calendar_raw_records", "trading_calendar_ingestion_run_records",
            "trading_calendar_processing_attempts");

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired JdbcTemplate jdbc;
    @Autowired TemporalMarketFoundationService temporal;
    @Autowired MarketDataIngestionService ingestion;
    @Autowired IngestionCanonicalHasher hasher;
    @Autowired ObjectMapper objectMapper;
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
        requireSafeSchemaName();
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
    void migratesAndEnforcesSourceNeutralLifecycleWithoutPublicSideEffects() throws Exception {
        assertDedicatedDatabaseAndMigration();
        assertSchemaObjects();

        DatasetVersion securityDataset = registerDataset(
                DatasetType.SECURITY_STATUS, SECURITY_SOURCE, HASH_A);
        DatasetVersion calendarDataset = registerDataset(
                DatasetType.TRADING_CALENDAR, CALENDAR_SOURCE, HASH_B);

        StartRunCommand securityStart = startCommand(
                securityDataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.INGEST, "security-lifecycle");
        IngestionRun securityRun = ingestion.startRun(securityStart);
        assertEquals(securityRun.id(), ingestion.startRun(securityStart).id());
        assertEquals(1, securityRun.runAttemptNumber());
        assertEquals(null, securityRun.retryOfRunLogicalKey());
        assertEquals(RANGE_START, securityRun.requestedRangeStart());
        assertEquals(RANGE_END, securityRun.requestedRangeEnd());
        assertThrows(IngestionDataConflictException.class, () -> ingestion.startRun(
                startCommand(securityDataset, DatasetType.SECURITY_STATUS,
                        RunNamespace.FORMAL, OperationType.INGEST, "formal-must-fail")));
        String formalRootKey = hasher.rootRequestLogicalKey(
                securityRun.datasetLogicalKey(), DatasetType.SECURITY_STATUS,
                RunNamespace.FORMAL, OperationType.INGEST, "formal-direct",
                RANGE_START, RANGE_END);
        String formalRunKey = hasher.runLogicalKey(
                securityRun.datasetLogicalKey(), DatasetType.SECURITY_STATUS,
                RunNamespace.FORMAL, OperationType.INGEST, "formal-direct", formalRootKey, 1,
                RANGE_START, RANGE_END, null);
        expectDatabaseFailure("55000", "FORMAL ingestion is unavailable", """
                INSERT INTO market_data_ingestion_runs(
                    ingestion_run_logical_key, dataset_version_id, dataset_logical_key,
                    dataset_type, run_namespace, operation_type, request_key,
                    retry_of_run_logical_key, root_request_logical_key, run_attempt_number,
                    requested_range_start, requested_range_end, status,
                    started_at, created_at
                ) VALUES (?, ?, ?, 'SECURITY_STATUS', 'FORMAL', 'INGEST', ?, NULL, ?, 1,
                          ?, ?, 'RUNNING', ?, ?)
                """, formalRunKey, securityDataset.id(),
                securityRun.datasetLogicalKey(), "formal-direct", formalRootKey,
                RANGE_START, RANGE_END, FETCHED_AT, FETCHED_AT);

        JsonNode securityPayload = objectMapper.createObjectNode()
                .put("sourceRecord", "security-1").put("revision", "1");
        AppendSecurityStatusRawCommand securityRawCommand = securityRawCommand(
                securityRun.id(), SECURITY_SOURCE, "security-1", securityPayload,
                TemporalTrustLevel.OBSERVED, PUBLISHED_AT);
        RawRecord securityRaw = ingestion.appendSecurityStatusRaw(securityRawCommand);
        assertEquals("instrument-security-1", securityRaw.sourceInstrumentId());
        assertEquals(securityRaw.id(),
                ingestion.appendSecurityStatusRaw(securityRawCommand).id());
        assertEquals(1, count("security_status_raw_records"));
        assertEquals(1, count("security_status_ingestion_run_records"));
        assertRawPayloadHashRejected(securityRun, securityDataset);
        assertOrphanRawCommitRejected(
                DatasetType.SECURITY_STATUS, securityRun, securityDataset,
                SECURITY_SOURCE, "security-orphan");

        expectDatabaseFailure("23514", "verified publication time", directAttemptSql(
                        "security_status_processing_attempts"),
                securityRun.id(), securityRaw.id(), 1,
                attemptKey(securityRun, securityRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT.plusSeconds(1), "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null, "{}", hasher.jsonHash(
                        objectMapper.createObjectNode()));
        expectDatabaseFailure("23514", "assurance must equal requested/source/publication/stage",
                directAttemptSql(
                        "security_status_processing_attempts"),
                securityRun.id(), securityRaw.id(), 1,
                attemptKey(securityRun, securityRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1", "PIT_VERIFIED", null,
                "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        expectDatabaseFailure("23514", "assurance must equal requested/source/publication/stage",
                directAttemptSql("security_status_processing_attempts"),
                securityRun.id(), securityRaw.id(), 1,
                attemptKey(securityRun, securityRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "INFERRED_RESEARCH", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null, "{}",
                hasher.jsonHash(objectMapper.createObjectNode()));
        expectDatabaseFailure("23514", "assurance must equal requested/source/publication/stage",
                directAttemptSql("security_status_processing_attempts"),
                securityRun.id(), securityRaw.id(), 1,
                attemptKey(securityRun, securityRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "RECONSTRUCTED_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "PIT_VERIFIED", null, "{}",
                hasher.jsonHash(objectMapper.createObjectNode()));
        JsonNode forbiddenMetadata = objectMapper.createObjectNode().put("projectedEventId", 99);
        expectDatabaseFailure("23514", "result_metadata must be empty", directAttemptSql(
                        "security_status_processing_attempts"),
                securityRun.id(), securityRaw.id(), 1,
                attemptKey(securityRun, securityRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null,
                forbiddenMetadata.toString(), hasher.jsonHash(forbiddenMetadata));

        ProcessingAttempt securityAttempt = ingestion.recordSecurityStatusAttempt(
                completedAttempt(securityRun.id(), securityRaw.id(),
                        1, PublicationTimeVerification.VERIFIED));
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, securityAttempt.assuranceLevel());
        assertEquals(AssuranceLevel.PIT_VERIFIED,
                securityAttempt.requestedAssuranceLevel());
        assertEquals("PIT_VERIFIED", jdbc.queryForObject("""
                SELECT requested_assurance_level
                FROM security_status_processing_attempts
                WHERE id=?
                """, String.class, securityAttempt.id()));
        assertEquals(PUBLISHED_AT, securityAttempt.derivedKnownFrom());
        assertFalse(securityAttempt.completedAt().isBefore(securityRaw.systemFirstObservedAt()));
        ProcessingAttempt repeatedSecurityAttempt = ingestion.recordSecurityStatusAttempt(
                completedAttempt(securityRun.id(), securityRaw.id(),
                        1, PublicationTimeVerification.VERIFIED));
        assertEquals(securityAttempt.id(), repeatedSecurityAttempt.id());
        assertEquals(securityAttempt.completedAt(), repeatedSecurityAttempt.completedAt());
        assertThrows(IngestionDataConflictException.class, () ->
                ingestion.recordSecurityStatusAttempt(new RecordAttemptCommand(
                        securityRun.id(), securityRaw.id(), 1, AttemptStatus.COMPLETED,
                        "processor-v1", "SOURCE_NEUTRAL_RECORD_V1",
                        PublicationTimeVerification.VERIFIED,
                        AssuranceLevel.INFERRED_RESEARCH, null,
                        objectMapper.createObjectNode())));

        IngestionRun sealedSecurity = ingestion.sealRun(
                new SealRunCommand(securityRun.id(), RunStatus.COMPLETED, 1));
        assertEquals(RunStatus.COMPLETED, sealedSecurity.status());
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, sealedSecurity.assuranceLevel());
        assertCallerSearchPathCannotBypassSealedRun(sealedSecurity, securityRaw);
        assertEquals(sealedSecurity.manifestHash(), ingestion.sealRun(
                new SealRunCommand(securityRun.id(), RunStatus.COMPLETED, 1)).manifestHash());
        assertCanonicalDatabaseAgreement(
                securityDataset, securityRun, securityRaw, securityAttempt, sealedSecurity);
        assertThrows(IngestionDataConflictException.class,
                () -> ingestion.appendSecurityStatusRaw(securityRawCommand));

        IngestionRun retryRun = ingestion.startRun(retryCommand(securityDataset, sealedSecurity));
        assertEquals(2, retryRun.runAttemptNumber());
        assertEquals(sealedSecurity.logicalKey(), retryRun.retryOfRunLogicalKey());
        assertEquals(sealedSecurity.rootRequestLogicalKey(), retryRun.rootRequestLogicalKey());
        assertEquals(retryRun.id(), ingestion.startRun(
                retryCommand(securityDataset, sealedSecurity)).id());
        RawRecord retriedRaw = ingestion.appendSecurityStatusRaw(new AppendSecurityStatusRawCommand(
                retryRun.id(), securityRawCommand.source(), securityRawCommand.sourceVersion(),
                securityRawCommand.sourceRecordId(), securityRawCommand.sourceRevision(),
                securityRawCommand.sourceInstrumentId(),
                securityRawCommand.sourcePublishedAt(), securityRawCommand.sourceEffectiveDate(),
                securityRawCommand.sourceEffectiveAt(), securityRawCommand.sourceTrustLevel(),
                securityRawCommand.rawPayload(), securityRawCommand.payloadHash()));
        assertEquals(securityRaw.id(), retriedRaw.id());
        assertEquals(2, count("security_status_ingestion_run_records"));
        ingestion.recordSecurityStatusAttempt(completedAttempt(
                retryRun.id(), retriedRaw.id(), 1, PublicationTimeVerification.VERIFIED));
        IngestionRun sealedRetry = ingestion.sealRun(
                new SealRunCommand(retryRun.id(), RunStatus.COMPLETED, 1));
        assertNotEquals(sealedSecurity.manifestHash(), sealedRetry.manifestHash());
        IngestionRun secondRetry = ingestion.startRun(retryCommand(securityDataset, sealedRetry));
        assertEquals(3, secondRetry.runAttemptNumber());
        assertEquals(sealedRetry.logicalKey(), secondRetry.retryOfRunLogicalKey());
        assertEquals(sealedSecurity.rootRequestLogicalKey(), secondRetry.rootRequestLogicalKey());
        RawRecord secondRetryRaw = ingestion.appendSecurityStatusRaw(
                new AppendSecurityStatusRawCommand(
                        secondRetry.id(), securityRawCommand.source(),
                        securityRawCommand.sourceVersion(), securityRawCommand.sourceRecordId(),
                        securityRawCommand.sourceRevision(), securityRawCommand.sourceInstrumentId(),
                        securityRawCommand.sourcePublishedAt(),
                        securityRawCommand.sourceEffectiveDate(),
                        securityRawCommand.sourceEffectiveAt(), securityRawCommand.sourceTrustLevel(),
                        securityRawCommand.rawPayload(), securityRawCommand.payloadHash()));
        ingestion.recordSecurityStatusAttempt(completedAttempt(
                secondRetry.id(), secondRetryRaw.id(), 1,
                PublicationTimeVerification.VERIFIED));
        IngestionRun sealedSecondRetry = ingestion.sealRun(
                new SealRunCommand(secondRetry.id(), RunStatus.COMPLETED, 1));
        IngestionRun instrumentConflictRun = ingestion.startRun(
                retryCommand(securityDataset, sealedSecondRetry));
        assertThrows(IngestionDataConflictException.class, () ->
                ingestion.appendSecurityStatusRaw(new AppendSecurityStatusRawCommand(
                        instrumentConflictRun.id(), securityRawCommand.source(),
                        securityRawCommand.sourceVersion(), securityRawCommand.sourceRecordId(),
                        securityRawCommand.sourceRevision(), "different-instrument",
                        securityRawCommand.sourcePublishedAt(),
                        securityRawCommand.sourceEffectiveDate(),
                        securityRawCommand.sourceEffectiveAt(), securityRawCommand.sourceTrustLevel(),
                        securityRawCommand.rawPayload(), securityRawCommand.payloadHash())));
        ingestion.sealRun(new SealRunCommand(
                instrumentConflictRun.id(), RunStatus.FAILED, 0));

        IngestionRun unsealedParent = ingestion.startRun(startCommand(
                securityDataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.REBUILD, "unsealed-retry-parent"));
        assertThrows(IngestionDataConflictException.class,
                () -> ingestion.startRun(retryCommand(securityDataset, unsealedParent)));
        ingestion.sealRun(new SealRunCommand(unsealedParent.id(), RunStatus.FAILED, 0));
        assertThrows(IngestionDataConflictException.class, () -> ingestion.startRun(
                new StartRunCommand(
                        securityDataset.id(), DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                        OperationType.RETRY, sealedSecurity.requestKey(), RANGE_START, RANGE_END,
                        "run:v1:" + "f".repeat(64))));
        assertThrows(IngestionDataConflictException.class, () -> ingestion.startRun(
                new StartRunCommand(
                        calendarDataset.id(), DatasetType.TRADING_CALENDAR, RunNamespace.DEMO,
                        OperationType.RETRY, sealedSecurity.requestKey(), RANGE_START, RANGE_END,
                        sealedSecurity.logicalKey())));
        assertThrows(IngestionDataConflictException.class, () -> ingestion.startRun(
                new StartRunCommand(
                        securityDataset.id(), DatasetType.SECURITY_STATUS, RunNamespace.DEMO,
                        OperationType.RETRY, sealedSecurity.requestKey(), RANGE_START, RANGE_END,
                        sealedSecurity.logicalKey())));
        assertThrows(IngestionDataConflictException.class, () -> ingestion.startRun(
                new StartRunCommand(
                        securityDataset.id(), DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                        OperationType.RETRY, sealedSecurity.requestKey(), RANGE_START.plusDays(1),
                        RANGE_END, sealedSecurity.logicalKey())));

        IngestionRun calendarRun = ingestion.startRun(startCommand(
                calendarDataset, DatasetType.TRADING_CALENDAR, RunNamespace.DEMO,
                OperationType.BACKFILL, "calendar-lifecycle"));
        JsonNode calendarPayload = objectMapper.createObjectNode()
                .put("tradeDate", "2025-01-02").put("isOpen", true);
        AppendTradingCalendarRawCommand calendarRawCommand = calendarRawCommand(
                calendarRun.id(), CALENDAR_SOURCE, "calendar-20250102", calendarPayload,
                TemporalTrustLevel.BACKFILLED_INFERRED, null);
        RawRecord calendarRaw = ingestion.appendTradingCalendarRaw(calendarRawCommand);
        assertEquals("SSE", calendarRaw.exchange());
        assertEquals(LocalDate.of(2025, 1, 2), calendarRaw.tradeDate());
        assertCalendarRawIdentityGates(calendarRun, calendarDataset);
        assertOrphanRawCommitRejected(
                DatasetType.TRADING_CALENDAR, calendarRun, calendarDataset,
                CALENDAR_SOURCE, "calendar-orphan");
        ProcessingAttempt calendarAttempt = ingestion.recordTradingCalendarAttempt(
                completedAttempt(calendarRun.id(), calendarRaw.id(),
                        1, PublicationTimeVerification.NOT_PROVIDED));
        assertEquals(calendarRaw.systemFirstObservedAt(), calendarAttempt.derivedKnownFrom());
        assertEquals(AssuranceLevel.INFERRED_RESEARCH, calendarAttempt.assuranceLevel());
        IngestionRun sealedCalendar = ingestion.sealRun(
                new SealRunCommand(calendarRun.id(), RunStatus.COMPLETED, 1));
        assertEquals(AssuranceLevel.INFERRED_RESEARCH, sealedCalendar.assuranceLevel());

        IngestionRun calendarRetry = ingestion.startRun(
                retryCommand(calendarDataset, sealedCalendar));
        AppendTradingCalendarRawCommand retriedCalendarCommand =
                new AppendTradingCalendarRawCommand(
                        calendarRetry.id(), calendarRawCommand.source(),
                        calendarRawCommand.sourceVersion(), calendarRawCommand.sourceRecordId(),
                        calendarRawCommand.sourceRevision(), calendarRawCommand.exchange(),
                        calendarRawCommand.tradeDate(), calendarRawCommand.sourcePublishedAt(),
                        calendarRawCommand.sourceEffectiveDate(),
                        calendarRawCommand.sourceEffectiveAt(),
                        calendarRawCommand.sourceTrustLevel(), calendarRawCommand.rawPayload(),
                        calendarRawCommand.payloadHash());
        RawRecord retriedCalendarRaw = ingestion.appendTradingCalendarRaw(
                retriedCalendarCommand);
        assertEquals(calendarRaw.id(), retriedCalendarRaw.id());
        assertThrows(IngestionDataConflictException.class, () ->
                ingestion.appendTradingCalendarRaw(new AppendTradingCalendarRawCommand(
                        calendarRetry.id(), calendarRawCommand.source(),
                        calendarRawCommand.sourceVersion(), calendarRawCommand.sourceRecordId(),
                        calendarRawCommand.sourceRevision(), "SZSE",
                        calendarRawCommand.tradeDate(), calendarRawCommand.sourcePublishedAt(),
                        calendarRawCommand.sourceEffectiveDate(),
                        calendarRawCommand.sourceEffectiveAt(),
                        calendarRawCommand.sourceTrustLevel(), calendarRawCommand.rawPayload(),
                        calendarRawCommand.payloadHash())));
        assertThrows(IngestionDataConflictException.class, () ->
                ingestion.appendTradingCalendarRaw(new AppendTradingCalendarRawCommand(
                        calendarRetry.id(), calendarRawCommand.source(),
                        calendarRawCommand.sourceVersion(), calendarRawCommand.sourceRecordId(),
                        calendarRawCommand.sourceRevision(), calendarRawCommand.exchange(),
                        calendarRawCommand.tradeDate().plusDays(1),
                        calendarRawCommand.sourcePublishedAt(),
                        calendarRawCommand.sourceEffectiveDate(),
                        calendarRawCommand.sourceEffectiveAt(),
                        calendarRawCommand.sourceTrustLevel(), calendarRawCommand.rawPayload(),
                        calendarRawCommand.payloadHash())));
        ingestion.recordTradingCalendarAttempt(completedAttempt(
                calendarRetry.id(), retriedCalendarRaw.id(), 1,
                PublicationTimeVerification.NOT_PROVIDED));
        ingestion.sealRun(new SealRunCommand(calendarRetry.id(), RunStatus.COMPLETED, 1));

        DatasetVersion inferredDataset = registerDataset(
                DatasetType.SECURITY_STATUS, SECURITY_SOURCE + "_INFERRED",
                "d".repeat(64), TemporalTrustLevel.BACKFILLED_INFERRED);
        IngestionRun inferredRun = ingestion.startRun(startCommand(
                inferredDataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.BACKFILL, "dataset-trust-floor"));
        RawRecord inferredRaw = ingestion.appendSecurityStatusRaw(securityRawCommand(
                inferredRun.id(), SECURITY_SOURCE + "_INFERRED", "inferred-security",
                objectMapper.createObjectNode().put("sourceRecord", "inferred-security"),
                TemporalTrustLevel.OBSERVED, PUBLISHED_AT));
        expectDatabaseFailure("23514", "assurance must equal requested/source/publication/stage",
                directAttemptSql("security_status_processing_attempts"),
                inferredRun.id(), inferredRaw.id(), 1, attemptKey(inferredRun, inferredRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null,
                "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        ProcessingAttempt inferredAttempt = ingestion.recordSecurityStatusAttempt(
                completedAttempt(inferredRun.id(), inferredRaw.id(), 1,
                        PublicationTimeVerification.VERIFIED));
        assertEquals(AssuranceLevel.INFERRED_RESEARCH, inferredAttempt.assuranceLevel());
        assertEquals(AssuranceLevel.INFERRED_RESEARCH, ingestion.sealRun(
                new SealRunCommand(inferredRun.id(), RunStatus.COMPLETED, 1)).assuranceLevel());

        DatasetVersion verifiedBackfillDataset = registerDataset(
                DatasetType.SECURITY_STATUS, SECURITY_SOURCE + "_VERIFIED_BACKFILL",
                "e".repeat(64), TemporalTrustLevel.BACKFILLED_VERIFIED);
        IngestionRun verifiedBackfillRun = ingestion.startRun(startCommand(
                verifiedBackfillDataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.BACKFILL, "verified-backfill-trust-ceiling"));
        RawRecord verifiedBackfillRaw = ingestion.appendSecurityStatusRaw(securityRawCommand(
                verifiedBackfillRun.id(), SECURITY_SOURCE + "_VERIFIED_BACKFILL",
                "verified-backfill-security",
                objectMapper.createObjectNode().put("sourceRecord", "verified-backfill-security"),
                TemporalTrustLevel.OBSERVED, PUBLISHED_AT));
        expectDatabaseFailure("23514", "assurance must equal requested/source/publication/stage",
                directAttemptSql("security_status_processing_attempts"),
                verifiedBackfillRun.id(), verifiedBackfillRaw.id(), 1,
                attemptKey(verifiedBackfillRun, verifiedBackfillRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1", "PIT_VERIFIED", null,
                "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        ProcessingAttempt verifiedBackfillAttempt = ingestion.recordSecurityStatusAttempt(
                completedAttempt(verifiedBackfillRun.id(), verifiedBackfillRaw.id(), 1,
                        PublicationTimeVerification.VERIFIED));
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED,
                verifiedBackfillAttempt.assuranceLevel());
        ingestion.sealRun(new SealRunCommand(
                verifiedBackfillRun.id(), RunStatus.COMPLETED, 1));

        IngestionRun multiAttemptRun = ingestion.startRun(startCommand(
                securityDataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.REBUILD, "multiple-terminal-attempts"));
        RawRecord multiAttemptRaw = ingestion.appendSecurityStatusRaw(securityRawCommand(
                multiAttemptRun.id(), SECURITY_SOURCE, "security-multiple-attempts",
                objectMapper.createObjectNode().put("sourceRecord", "multiple-attempts"),
                TemporalTrustLevel.OBSERVED, PUBLISHED_AT));
        ProcessingAttempt unresolvedAttempt = ingestion.recordSecurityStatusAttempt(
                identityUnresolvedAttempt(multiAttemptRun.id(), multiAttemptRaw.id(), 1,
                        PublicationTimeVerification.VERIFIED));
        assertEquals(AttemptStatus.IDENTITY_UNRESOLVED, unresolvedAttempt.status());
        expectDatabaseFailure("23514", "next contiguous value",
                directAttemptSql("security_status_processing_attempts"),
                multiAttemptRun.id(), multiAttemptRaw.id(), 3,
                attemptKey(multiAttemptRun, multiAttemptRaw, 3),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null,
                "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        expectDatabaseFailure("23514", "result hash does not match canonical metadata",
                directAttemptSql("security_status_processing_attempts"),
                multiAttemptRun.id(), multiAttemptRaw.id(), 2,
                attemptKey(multiAttemptRun, multiAttemptRaw, 2),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null,
                "{}", "f".repeat(64));
        ProcessingAttempt acceptedAttempt = ingestion.recordSecurityStatusAttempt(
                completedAttempt(multiAttemptRun.id(), multiAttemptRaw.id(), 2,
                        PublicationTimeVerification.VERIFIED));
        assertEquals(2, acceptedAttempt.attemptNo());
        assertFalse(acceptedAttempt.completedAt().isBefore(unresolvedAttempt.completedAt()));
        expectDatabaseFailure("55000", "cannot continue after a completed attempt",
                directAttemptSql("security_status_processing_attempts"),
                multiAttemptRun.id(), multiAttemptRaw.id(), 3,
                attemptKey(multiAttemptRun, multiAttemptRaw, 3),
                "REJECTED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED",
                "LATE_RETRY", "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        expectDatabaseFailure("23514", "manifest hash does not match",
                """
                UPDATE market_data_ingestion_runs
                SET status='COMPLETED', manifest_hash=?, final_expected_count=1,
                    final_received_count=1, final_accepted_count=1,
                    final_rejected_count=0, assurance_level='RECONSTRUCTED_VERIFIED'
                WHERE id=?
                """, "f".repeat(64), multiAttemptRun.id());
        IngestionRun sealedMultiAttempt = ingestion.sealRun(
                new SealRunCommand(multiAttemptRun.id(), RunStatus.COMPLETED, 1));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_processing_attempts
                WHERE ingestion_run_id=? AND raw_record_id=?
                """, Integer.class, multiAttemptRun.id(), multiAttemptRaw.id()));
        assertEquals(1, sealedMultiAttempt.finalAcceptedCount());
        assertEquals(0, sealedMultiAttempt.finalRejectedCount());
        assertCanonicalDatabaseAgreement(
                securityDataset, multiAttemptRun, multiAttemptRaw,
                acceptedAttempt, sealedMultiAttempt);

        IngestionRun calendarTestRun = ingestion.startRun(startCommand(
                calendarDataset, DatasetType.TRADING_CALENDAR, RunNamespace.TEST,
                OperationType.REBUILD, "calendar-database-gates"));
        expectDatabaseFailure("23514", "dataset and namespace", """
                INSERT INTO trading_calendar_ingestion_run_records(
                    ingestion_run_id, raw_record_id, received_at
                ) VALUES (?, ?, ?)
                """, calendarTestRun.id(), calendarRaw.id(), FETCHED_AT);
        JsonNode wrongTypePayload = objectMapper.createObjectNode().put("wrongType", true);
        String wrongTypeRecordId = "calendar-in-security-table";
        expectDatabaseFailure("23514", "does not match its ingestion run",
                directRawSql("security_status_raw_records"),
                calendarTestRun.id(), calendarDataset.id(), hasher.rawRecordLogicalKey(
                        DatasetType.SECURITY_STATUS, RunNamespace.TEST, CALENDAR_SOURCE,
                        SOURCE_VERSION, wrongTypeRecordId, "1"),
                RunNamespace.TEST.name(), CALENDAR_SOURCE, SOURCE_VERSION,
                wrongTypeRecordId, "1", "instrument-wrong-type", null,
                LocalDate.of(2025, 1, 2), null,
                FETCHED_AT, FETCHED_AT, TemporalTrustLevel.OBSERVED.name(),
                wrongTypePayload.toString(), hasher.jsonHash(wrongTypePayload));
        ingestion.sealRun(new SealRunCommand(calendarTestRun.id(), RunStatus.FAILED, 0));

        IngestionRun unattachedRun = ingestion.startRun(startCommand(
                securityDataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.REBUILD, "unattached-attempt"));
        expectDatabaseFailure("23514", "requires an attached run raw record",
                directAttemptSql("security_status_processing_attempts"),
                unattachedRun.id(), securityRaw.id(), 1,
                attemptKey(unattachedRun, securityRaw, 1),
                "COMPLETED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED", null,
                "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        ingestion.sealRun(new SealRunCommand(unattachedRun.id(), RunStatus.FAILED, 0));

        assertFactImmutability(
                sealedSecurity, securityRaw, securityAttempt,
                sealedCalendar, calendarRaw, calendarAttempt);
        assertEquals(4, count("market_data_dataset_versions"));
        assertEquals(0, count("security_status_events"));
        assertEquals(0, count("security_status_history"));
        assertEquals(0, count("trading_calendar_revisions"));
    }

    @Test
    @Order(2)
    void serializesTwoRealBackendsToOneRunRawAttemptAndManifest() throws Exception {
        DatasetVersion dataset = registerDataset(
                DatasetType.SECURITY_STATUS, SECURITY_SOURCE + "_CONCURRENT", "c".repeat(64));
        StartRunCommand start = startCommand(
                dataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.INGEST, "concurrent-run");
        List<ConcurrentResult<IngestionRun>> runs = runConcurrent(() -> ingestion.startRun(start));
        assertDistinctBackends(runs);
        assertEquals(runs.get(0).value().id(), runs.get(1).value().id());
        IngestionRun run = runs.get(0).value();

        JsonNode payload = objectMapper.createObjectNode()
                .put("sourceRecord", "concurrent-security").put("revision", "1");
        AppendSecurityStatusRawCommand rawCommand = securityRawCommand(
                run.id(), SECURITY_SOURCE + "_CONCURRENT", "concurrent-security", payload,
                TemporalTrustLevel.OBSERVED, PUBLISHED_AT);
        List<ConcurrentResult<RawRecord>> raws = runConcurrent(
                () -> ingestion.appendSecurityStatusRaw(rawCommand));
        assertDistinctBackends(raws);
        assertEquals(raws.get(0).value().id(), raws.get(1).value().id());
        assertEquals(raws.get(0).value().systemFirstObservedAt(),
                raws.get(1).value().systemFirstObservedAt());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_raw_records
                WHERE source=? AND source_record_id=? AND source_revision='1'
                """, Integer.class, SECURITY_SOURCE + "_CONCURRENT", "concurrent-security"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_ingestion_run_records
                WHERE ingestion_run_id=? AND raw_record_id=?
                """, Integer.class, run.id(), raws.get(0).value().id()));
        Instant firstObservedAt = raws.get(0).value().systemFirstObservedAt();
        assertEquals(firstObservedAt,
                ingestion.appendSecurityStatusRaw(rawCommand).systemFirstObservedAt());

        RecordAttemptCommand attemptCommand = completedAttempt(
                run.id(), raws.get(0).value().id(), 1,
                PublicationTimeVerification.VERIFIED);
        List<ConcurrentResult<ProcessingAttempt>> attempts = runConcurrent(
                () -> ingestion.recordSecurityStatusAttempt(attemptCommand));
        assertDistinctBackends(attempts);
        assertEquals(attempts.get(0).value().id(), attempts.get(1).value().id());
        assertEquals(attempts.get(0).value().completedAt(),
                attempts.get(1).value().completedAt());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_processing_attempts
                WHERE ingestion_run_id=? AND raw_record_id=?
                """, Integer.class, run.id(), raws.get(0).value().id()));

        SealRunCommand seal = new SealRunCommand(run.id(), RunStatus.COMPLETED, 1);
        List<ConcurrentResult<IngestionRun>> sealed = runConcurrent(() -> ingestion.sealRun(seal));
        assertDistinctBackends(sealed);
        assertEquals(sealed.get(0).value(), sealed.get(1).value());
        assertEquals(sealed.get(0).value().id(), sealed.get(1).value().id());
        assertEquals(sealed.get(0).value().manifestHash(), sealed.get(1).value().manifestHash());
        assertTrue(sealed.get(0).value().sealed());
        assertEquals(1, sealed.get(0).value().finalReceivedCount());
        assertEquals(1, sealed.get(0).value().finalAcceptedCount());
        assertEquals(0, sealed.get(0).value().finalRejectedCount());

        StartRunCommand concurrentRetryCommand = retryCommand(dataset, sealed.get(0).value());
        List<ConcurrentResult<IngestionRun>> retryRuns = runConcurrent(
                () -> ingestion.startRun(concurrentRetryCommand));
        assertDistinctBackends(retryRuns);
        assertEquals(retryRuns.get(0).value(), retryRuns.get(1).value());
        assertEquals(2, retryRuns.get(0).value().runAttemptNumber());
        assertEquals(sealed.get(0).value().logicalKey(),
                retryRuns.get(0).value().retryOfRunLogicalKey());
        ingestion.sealRun(new SealRunCommand(
                retryRuns.get(0).value().id(), RunStatus.FAILED, 0));

        IngestionRun conflictRun = ingestion.startRun(startCommand(
                dataset, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.REBUILD, "concurrent-conflicting-revision"));
        JsonNode firstPayload = objectMapper.createObjectNode().put("winnerCandidate", "A");
        JsonNode secondPayload = objectMapper.createObjectNode().put("winnerCandidate", "B");
        AppendSecurityStatusRawCommand firstCommand = securityRawCommand(
                conflictRun.id(), SECURITY_SOURCE + "_CONCURRENT", "conflicting-security",
                firstPayload, TemporalTrustLevel.OBSERVED, PUBLISHED_AT);
        AppendSecurityStatusRawCommand secondCommand = securityRawCommand(
                conflictRun.id(), SECURITY_SOURCE + "_CONCURRENT", "conflicting-security",
                secondPayload, TemporalTrustLevel.OBSERVED, PUBLISHED_AT);
        List<ConcurrentOutcome<RawRecord>> conflictOutcomes = runConcurrentCapturing(
                () -> ingestion.appendSecurityStatusRaw(firstCommand),
                () -> ingestion.appendSecurityStatusRaw(secondCommand));
        assertNotEquals(conflictOutcomes.get(0).backendPid(), conflictOutcomes.get(1).backendPid());
        assertEquals(1, conflictOutcomes.stream().filter(outcome -> outcome.value() != null).count());
        assertEquals(1, conflictOutcomes.stream()
                .filter(outcome -> causedBy(
                        outcome.error(), IngestionDataConflictException.class)).count());
        RawRecord conflictWinner = conflictOutcomes.stream()
                .map(ConcurrentOutcome::value).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        AppendSecurityStatusRawCommand winningCommand = conflictWinner.payloadHash()
                .equals(firstCommand.payloadHash()) ? firstCommand : secondCommand;
        RawRecord repeatedWinner = ingestion.appendSecurityStatusRaw(winningCommand);
        assertEquals(conflictWinner.id(), repeatedWinner.id());
        assertEquals(conflictWinner.systemFirstObservedAt(), repeatedWinner.systemFirstObservedAt());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_raw_records
                WHERE source=? AND source_record_id=? AND source_revision='1'
                """, Integer.class, SECURITY_SOURCE + "_CONCURRENT", "conflicting-security"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM security_status_ingestion_run_records
                WHERE ingestion_run_id=? AND raw_record_id=?
                """, Integer.class, conflictRun.id(), conflictWinner.id()));
        ingestion.recordSecurityStatusAttempt(completedAttempt(
                conflictRun.id(), conflictWinner.id(), 1,
                PublicationTimeVerification.VERIFIED));
        ingestion.sealRun(new SealRunCommand(conflictRun.id(), RunStatus.COMPLETED, 1));
    }

    private DatasetVersion registerDataset(DatasetType type, String source, String payloadHash) {
        return registerDataset(type, source, payloadHash, TemporalTrustLevel.OBSERVED);
    }

    private DatasetVersion registerDataset(
            DatasetType type,
            String source,
            String payloadHash,
            TemporalTrustLevel trustLevel
    ) {
        RegisterDatasetVersionCommand command = new RegisterDatasetVersionCommand(
                type.name(), source, SOURCE_VERSION, CONNECTOR_VERSION,
                RANGE_START, RANGE_END, FETCHED_AT, payloadHash, trustLevel,
                objectMapper.createObjectNode().put("fixture", "stage-2d2b1a"));
        DatasetVersion value = temporal.registerDatasetVersion(command);
        assertEquals(value.id(), temporal.registerDatasetVersion(command).id());
        return value;
    }

    private StartRunCommand startCommand(
            DatasetVersion dataset,
            DatasetType type,
            RunNamespace namespace,
            OperationType operation,
            String requestKey
    ) {
        return new StartRunCommand(
                dataset.id(), type, namespace, operation, requestKey,
                RANGE_START, RANGE_END, null);
    }

    private StartRunCommand retryCommand(DatasetVersion dataset, IngestionRun parent) {
        return new StartRunCommand(
                dataset.id(), parent.datasetType(), parent.runNamespace(), OperationType.RETRY,
                parent.requestKey(), parent.requestedRangeStart(), parent.requestedRangeEnd(),
                parent.logicalKey());
    }

    private AppendSecurityStatusRawCommand securityRawCommand(
            long runId,
            String source,
            String recordId,
            JsonNode payload,
            TemporalTrustLevel trust,
            Instant publishedAt
    ) {
        return new AppendSecurityStatusRawCommand(
                runId, source, SOURCE_VERSION, recordId, "1", "instrument-" + recordId,
                publishedAt,
                LocalDate.of(2025, 1, 2), Instant.parse("2025-01-01T16:00:00Z"),
                trust, payload, hasher.jsonHash(payload));
    }

    private AppendTradingCalendarRawCommand calendarRawCommand(
            long runId,
            String source,
            String recordId,
            JsonNode payload,
            TemporalTrustLevel trust,
            Instant publishedAt
    ) {
        return new AppendTradingCalendarRawCommand(
                runId, source, SOURCE_VERSION, recordId, "1", "SSE",
                LocalDate.of(2025, 1, 2), publishedAt,
                LocalDate.of(2025, 1, 2), Instant.parse("2025-01-01T16:00:00Z"),
                trust, payload, hasher.jsonHash(payload));
    }

    private RecordAttemptCommand completedAttempt(
            long runId,
            long rawRecordId,
            int attemptNo,
            PublicationTimeVerification publicationVerification
    ) {
        return new RecordAttemptCommand(
                runId, rawRecordId, attemptNo, AttemptStatus.COMPLETED, "processor-v1",
                "SOURCE_NEUTRAL_RECORD_V1", publicationVerification,
                AssuranceLevel.PIT_VERIFIED, null,
                objectMapper.createObjectNode());
    }

    private RecordAttemptCommand rejectedAttempt(
            long runId,
            long rawRecordId,
            int attemptNo,
            PublicationTimeVerification publicationVerification,
            String errorCode
    ) {
        return new RecordAttemptCommand(
                runId, rawRecordId, attemptNo, AttemptStatus.REJECTED, "processor-v1",
                "SOURCE_NEUTRAL_RECORD_V1", publicationVerification,
                AssuranceLevel.PIT_VERIFIED, errorCode, objectMapper.createObjectNode());
    }

    private RecordAttemptCommand identityUnresolvedAttempt(
            long runId,
            long rawRecordId,
            int attemptNo,
            PublicationTimeVerification publicationVerification
    ) {
        return new RecordAttemptCommand(
                runId, rawRecordId, attemptNo, AttemptStatus.IDENTITY_UNRESOLVED,
                "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", publicationVerification,
                AssuranceLevel.PIT_VERIFIED, "IDENTITY_NOT_MAPPED",
                objectMapper.createObjectNode());
    }

    private String attemptKey(IngestionRun run, RawRecord raw, int attemptNo) {
        return hasher.attemptLogicalKey(
                run.logicalKey(), raw.logicalKey(), attemptNo,
                "processor-v1", "SOURCE_NEUTRAL_RECORD_V1");
    }

    private void assertOrphanRawCommitRejected(
            DatasetType type,
            IngestionRun run,
            DatasetVersion dataset,
            String source,
            String recordId
    ) {
        JsonNode payload = objectMapper.createObjectNode().put("orphan", true);
        String logicalKey = hasher.rawRecordLogicalKey(
                type, run.runNamespace(), source,
                SOURCE_VERSION, recordId, "1");
        String rawTable = type == DatasetType.SECURITY_STATUS
                ? "security_status_raw_records" : "trading_calendar_raw_records";
        List<Object> parameters = new ArrayList<>(List.of(
                run.id(), dataset.id(), logicalKey, run.runNamespace().name(), source,
                SOURCE_VERSION, recordId, "1"));
        if (type == DatasetType.SECURITY_STATUS) {
            parameters.add("instrument-" + recordId);
        } else {
            parameters.add("SSE");
            parameters.add(LocalDate.of(2025, 1, 2));
        }
        java.util.Collections.addAll(parameters, PUBLISHED_AT, LocalDate.of(2025, 1, 2),
                Instant.parse("2025-01-01T16:00:00Z"), FETCHED_AT, FETCHED_AT,
                TemporalTrustLevel.OBSERVED.name(), payload.toString(), hasher.jsonHash(payload));
        expectDatabaseCommitFailure(
                "23514", "first ingestion run association is required",
                directRawSql(rawTable),
                parameters.toArray());
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM %s
                WHERE source=? AND source_record_id=?
                """.formatted(rawTable), Integer.class, source, recordId));
    }

    private void assertRawPayloadHashRejected(IngestionRun run, DatasetVersion dataset) {
        JsonNode payload = objectMapper.createObjectNode().put("hashMismatch", true);
        String recordId = "security-hash-mismatch";
        expectDatabaseFailure(
                "23514", "raw payload hash does not match canonical payload",
                directRawSql("security_status_raw_records"),
                run.id(), dataset.id(), hasher.rawRecordLogicalKey(
                        DatasetType.SECURITY_STATUS, run.runNamespace(), SECURITY_SOURCE,
                        SOURCE_VERSION, recordId, "1"),
                run.runNamespace().name(), SECURITY_SOURCE, SOURCE_VERSION, recordId, "1",
                "instrument-" + recordId, PUBLISHED_AT, LocalDate.of(2025, 1, 2),
                Instant.parse("2025-01-01T16:00:00Z"), FETCHED_AT, FETCHED_AT,
                TemporalTrustLevel.OBSERVED.name(), payload.toString(), "f".repeat(64));
    }

    private void assertCalendarRawIdentityGates(IngestionRun run, DatasetVersion dataset) {
        JsonNode payload = objectMapper.createObjectNode().put("calendarIdentityGate", true);
        String recordId = "calendar-identity-gate";
        String logicalKey = hasher.rawRecordLogicalKey(
                DatasetType.TRADING_CALENDAR, run.runNamespace(), CALENDAR_SOURCE,
                SOURCE_VERSION, recordId, "1");
        Object[] common = new Object[]{run.id(), dataset.id(), logicalKey,
                run.runNamespace().name(), CALENDAR_SOURCE, SOURCE_VERSION, recordId, "1"};
        expectDatabaseFailure("23514", "trading calendar exchange is unsupported",
                directRawSql("trading_calendar_raw_records"),
                common[0], common[1], common[2], common[3], common[4], common[5], common[6],
                common[7], "BSE", LocalDate.of(2025, 1, 2), PUBLISHED_AT,
                LocalDate.of(2025, 1, 2), Instant.parse("2025-01-01T16:00:00Z"),
                FETCHED_AT, FETCHED_AT, TemporalTrustLevel.OBSERVED.name(),
                payload.toString(), hasher.jsonHash(payload));
        expectDatabaseFailure("23514", "trading calendar trade date is outside its dataset contract",
                directRawSql("trading_calendar_raw_records"),
                common[0], common[1], common[2], common[3], common[4], common[5], common[6],
                common[7], "SSE", RANGE_END.plusDays(1), PUBLISHED_AT,
                RANGE_END.plusDays(1), null, FETCHED_AT, FETCHED_AT,
                TemporalTrustLevel.OBSERVED.name(), payload.toString(), hasher.jsonHash(payload));
    }

    private void assertCanonicalDatabaseAgreement(
            DatasetVersion dataset,
            IngestionRun run,
            RawRecord raw,
            ProcessingAttempt attempt,
            IngestionRun sealed
    ) {
        String goldenDatasetKey =
                "dataset:v1:1bdc3835611398f211f40274f8714b7333d8b06b9febef303be5109dafcca32c";
        String goldenRootKey = "root-request:v1:bd6fc3b681f2653fc27f625bb84a1e589a30998f6fc9424f2450d6b2f8e1ed01";
        String goldenRunKey =
                "run:v1:0872277cc21e8c6193cc8445c476be7e7b540262e065e69e6bce62bb2d3609bc";
        String goldenRawKey =
                "raw:v1:1e8ee05be8508af23e7fb8f682ea562eaa9e70965c9de16d852c284929738391";
        assertEquals(goldenRootKey, jdbc.queryForObject(
                "SELECT compute_ingestion_root_request_logical_key(?, ?, ?, ?, ?, ?, ?)",
                String.class, goldenDatasetKey, "SECURITY_STATUS", "TEST", "INGEST",
                "request-1", RANGE_START, RANGE_END));
        assertEquals(goldenRunKey, jdbc.queryForObject(
                "SELECT compute_ingestion_run_logical_key(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                String.class, goldenDatasetKey, "SECURITY_STATUS", "TEST", "INGEST",
                "request-1", goldenRootKey, 1, RANGE_START, RANGE_END, null));
        assertEquals(goldenRawKey, jdbc.queryForObject(
                "SELECT compute_ingestion_raw_record_logical_key(?, ?, ?, ?, ?, ?)",
                String.class, "SECURITY_STATUS", "TEST", "SOURCE", "V1", "record", "1"));
        assertEquals(
                "attempt:v1:2583c785c63ac8edfe08a671d8f50ebb45584f957f74a7cc8eba4c72de0e8bb0",
                jdbc.queryForObject(
                        "SELECT compute_ingestion_attempt_logical_key(?, ?, ?, ?, ?)",
                        String.class, goldenRunKey, goldenRawKey, 1, "processor", "contract"));
        assertEquals(
                "3e0be30d9c55a4c203bff3bcdce0842e1cd7054bf46f9c24f476e51adf2cf34b",
                jdbc.queryForObject(
                        "SELECT compute_ingestion_json_hash('{}'::jsonb)", String.class));
        assertEquals(run.datasetLogicalKey(), jdbc.queryForObject(
                "SELECT compute_ingestion_dataset_logical_key(?)", String.class, dataset.id()));
        assertEquals(run.logicalKey(), jdbc.queryForObject(
                "SELECT compute_ingestion_run_logical_key(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                String.class,
                run.datasetLogicalKey(), run.datasetType().name(), run.runNamespace().name(),
                run.operationType().name(), run.requestKey(), run.rootRequestLogicalKey(),
                run.runAttemptNumber(), run.requestedRangeStart(), run.requestedRangeEnd(),
                run.retryOfRunLogicalKey()));
        assertEquals(raw.logicalKey(), jdbc.queryForObject(
                "SELECT compute_ingestion_raw_record_logical_key(?, ?, ?, ?, ?, ?)",
                String.class, raw.datasetType().name(), raw.recordNamespace().name(), raw.source(),
                raw.sourceVersion(), raw.sourceRecordId(), raw.sourceRevision()));
        assertEquals(raw.payloadHash(), jdbc.queryForObject(
                "SELECT compute_ingestion_json_hash(?::jsonb)", String.class,
                raw.rawPayload().toString()));
        JsonNode unicodeVector = objectMapper.createObjectNode()
                .put("\uE000", 1).put("\uD800\uDC00", 2);
        JsonNode numericVector = objectMapper.createObjectNode()
                .put("fraction", new BigDecimal("1.00"))
                .put("negativeZero", new BigDecimal("-0.0"))
                .put("large", new BigDecimal("100000000000000000000"));
        for (JsonNode vector : List.of(unicodeVector, numericVector,
                objectMapper.createArrayNode().add(2).add(1))) {
            assertEquals(hasher.jsonHash(vector), jdbc.queryForObject(
                    "SELECT compute_ingestion_json_hash(?::jsonb)", String.class,
                    vector.toString()));
        }
        assertEquals(attempt.logicalKey(), jdbc.queryForObject(
                "SELECT compute_ingestion_attempt_logical_key(?, ?, ?, ?, ?)", String.class,
                run.logicalKey(), raw.logicalKey(), attempt.attemptNo(),
                attempt.processorVersion(), attempt.contractVersion()));
        String databaseManifestHash = jdbc.queryForObject(
                "SELECT compute_ingestion_manifest_hash(?, ?, ?, ?, ?, ?, ?)", String.class,
                sealed.id(), sealed.status().name(), sealed.finalExpectedCount(),
                sealed.finalReceivedCount(), sealed.finalAcceptedCount(),
                sealed.finalRejectedCount(), sealed.assuranceLevel().name());
        assertEquals(sealed.manifestHash(), databaseManifestHash);
        if ("security-lifecycle".equals(run.requestKey())) {
            assertEquals("7622682aba4f70912444cebb5f169187158dd9f0c7a2c1fa7d1ce42c037cbdc8",
                    databaseManifestHash);
        }
    }

    private void assertFactImmutability(
            IngestionRun run,
            RawRecord raw,
            ProcessingAttempt attempt,
            IngestionRun calendarRun,
            RawRecord calendarRaw,
            ProcessingAttempt calendarAttempt
    ) {
        List<MutationProbe> probes = List.of(
                new MutationProbe("market_data_ingestion_runs", "id", run.id(),
                        "request_key=request_key", "immutable"),
                new MutationProbe("security_status_raw_records", "id", raw.id(),
                        "payload_hash=payload_hash", "append-only"),
                new MutationProbe("security_status_ingestion_run_records", "raw_record_id", raw.id(),
                        "received_at=received_at", "append-only"),
                new MutationProbe("security_status_processing_attempts", "id", attempt.id(),
                        "requested_assurance_level='INFERRED_RESEARCH'", "append-only"),
                new MutationProbe("market_data_ingestion_runs", "id", calendarRun.id(),
                        "request_key=request_key", "immutable"),
                new MutationProbe("trading_calendar_raw_records", "id", calendarRaw.id(),
                        "payload_hash=payload_hash", "append-only"),
                new MutationProbe("trading_calendar_ingestion_run_records", "raw_record_id",
                        calendarRaw.id(), "received_at=received_at", "append-only"),
                new MutationProbe("trading_calendar_processing_attempts", "id",
                        calendarAttempt.id(),
                        "requested_assurance_level='INFERRED_RESEARCH'", "append-only"));
        for (MutationProbe probe : probes) {
            expectDatabaseFailure("55000", probe.message(),
                    "UPDATE " + probe.table() + " SET " + probe.assignment()
                            + " WHERE " + probe.idColumn() + "=?", probe.id());
            expectDatabaseFailure("55000", probe.message(),
                    "DELETE FROM " + probe.table() + " WHERE " + probe.idColumn() + "=?",
                    probe.id());
        }
        expectDatabaseFailure("55000", "sealed ingestion run", """
                INSERT INTO security_status_ingestion_run_records(
                    ingestion_run_id, raw_record_id, received_at
                ) VALUES (?, ?, ?)
                """, run.id(), raw.id(), FETCHED_AT);
        expectDatabaseFailure("55000", "sealed ingestion run",
                directAttemptSql("security_status_processing_attempts"),
                run.id(), raw.id(), 2, attemptKey(run, raw, 2),
                "REJECTED", "processor-v1", "SOURCE_NEUTRAL_RECORD_V1", "VERIFIED",
                "PIT_VERIFIED", PUBLISHED_AT, "KNOWLEDGE_TIME_POLICY_V1",
                "RECONSTRUCTED_VERIFIED",
                "SEALED_RETRY", "{}", hasher.jsonHash(objectMapper.createObjectNode()));
        for (String table : INGESTION_TABLES) {
            expectDatabaseFailure("55000", "TRUNCATE",
                    "TRUNCATE TABLE " + table + " CASCADE");
        }
        for (String column : List.of(
                "retry_of_run_logical_key", "root_request_logical_key", "run_attempt_number",
                "requested_range_start", "requested_range_end")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema=? AND table_name='market_data_ingestion_runs'
                      AND column_name=?
                    """, Integer.class, TEST_SCHEMA, column));
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='security_status_raw_records'
                  AND column_name='source_instrument_id'
                """, Integer.class, TEST_SCHEMA));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=? AND table_name='trading_calendar_raw_records'
                  AND column_name IN ('exchange', 'trade_date')
                """, Integer.class, TEST_SCHEMA));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema=?
                  AND table_name IN (
                    'security_status_processing_attempts',
                    'trading_calendar_processing_attempts')
                  AND column_name='requested_assurance_level'
                  AND is_nullable='NO'
                """, Integer.class, TEST_SCHEMA));
    }

    private void assertDedicatedDatabaseAndMigration() {
        assertEquals("stock_quant_test", jdbc.queryForObject(
                "SELECT current_database()", String.class));
        assertEquals("stock_quant_test", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(TEST_SCHEMA, jdbc.queryForObject("SELECT current_schema()", String.class));
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7"), jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",
                (resultSet, row) -> resultSet.getString(1)));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success=FALSE", Integer.class));
    }

    private void assertSchemaObjects() {
        for (String table : INGESTION_TABLES) {
            assertNotNull(jdbc.queryForObject("SELECT to_regclass(?)", String.class,
                    TEST_SCHEMA + "." + table));
        }
        assertEquals(23, jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger_record
                JOIN pg_class table_record ON table_record.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname=? AND NOT trigger_record.tgisinternal
                  AND table_record.relname IN (
                    'market_data_ingestion_runs',
                    'security_status_raw_records', 'security_status_ingestion_run_records',
                    'security_status_processing_attempts',
                    'trading_calendar_raw_records', 'trading_calendar_ingestion_run_records',
                    'trading_calendar_processing_attempts')
                """, Integer.class, TEST_SCHEMA));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger trigger_record
                JOIN pg_class table_record ON table_record.oid=trigger_record.tgrelid
                JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                WHERE schema_record.nspname=? AND trigger_record.tgdeferrable
                  AND trigger_record.tginitdeferred
                  AND trigger_record.tgname IN (
                    'trg_security_status_raw_records_first_run_receipt',
                    'trg_trading_calendar_raw_records_first_run_receipt')
                """, Integer.class, TEST_SCHEMA));
        for (String table : INGESTION_TABLES) {
            assertTrue(jdbc.queryForObject("""
                    SELECT count(*) > 0 FROM pg_indexes
                    WHERE schemaname=? AND tablename=?
                    """, Boolean.class, TEST_SCHEMA, table));
        }
        for (String constraint : List.of(
                "fk_market_data_ingestion_runs_dataset",
                "fk_market_data_ingestion_runs_retry_parent",
                "uq_market_data_ingestion_runs_logical_key",
                "uq_market_data_ingestion_runs_root_attempt",
                "fk_security_status_raw_records_first_run",
                "fk_security_status_raw_records_dataset",
                "uq_security_status_raw_records_source_revision",
                "fk_trading_calendar_raw_records_first_run",
                "fk_trading_calendar_raw_records_dataset",
                "uq_trading_calendar_raw_records_source_revision",
                "fk_security_status_ingestion_run_records_run",
                "fk_security_status_ingestion_run_records_raw",
                "fk_trading_calendar_ingestion_run_records_run",
                "fk_trading_calendar_ingestion_run_records_raw",
                "fk_security_status_processing_attempts_run",
                "fk_security_status_processing_attempts_raw",
                "uq_security_status_processing_attempts_run_raw_no",
                "ck_security_status_processing_attempts_no",
                "fk_trading_calendar_processing_attempts_run",
                "fk_trading_calendar_processing_attempts_raw",
                "uq_trading_calendar_processing_attempts_run_raw_no",
                "ck_trading_calendar_processing_attempts_no")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint constraint_record
                    JOIN pg_class table_record ON table_record.oid=constraint_record.conrelid
                    JOIN pg_namespace schema_record ON schema_record.oid=table_record.relnamespace
                    WHERE schema_record.nspname=? AND constraint_record.conname=?
                    """, Integer.class, TEST_SCHEMA, constraint));
        }
        assertFalse(jdbc.queryForObject("""
                SELECT position('completed_at' IN pg_get_functiondef(function_record.oid)) > 0
                FROM pg_proc function_record
                JOIN pg_namespace schema_record ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname=?
                  AND function_record.proname='compute_ingestion_manifest_hash'
                """, Boolean.class, TEST_SCHEMA));
        for (String function : List.of(
                "compute_ingestion_dataset_logical_key",
                "compute_ingestion_root_request_logical_key",
                "compute_ingestion_run_logical_key",
                "compute_ingestion_raw_record_logical_key",
                "compute_ingestion_attempt_logical_key",
                "compute_ingestion_json_hash",
                "compute_ingestion_manifest_hash")) {
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM pg_proc function_record
                    JOIN pg_namespace schema_record
                      ON schema_record.oid=function_record.pronamespace
                    WHERE schema_record.nspname=? AND function_record.proname=?
                    """, Integer.class, TEST_SCHEMA, function));
        }
        assertEquals(19, jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc function_record
                JOIN pg_namespace schema_record
                  ON schema_record.oid=function_record.pronamespace
                WHERE schema_record.nspname=?
                  AND function_record.proname IN (
                    'ingestion_canonical_append',
                    'ingestion_canonical_sha256',
                    'ingestion_canonical_instant',
                    'ingestion_canonical_json_append',
                    'compute_ingestion_json_hash',
                    'compute_ingestion_dataset_logical_key',
                    'compute_ingestion_root_request_logical_key',
                    'compute_ingestion_run_logical_key',
                    'compute_ingestion_raw_record_logical_key',
                    'compute_ingestion_attempt_logical_key',
                    'compute_ingestion_manifest_hash',
                    'reject_ingestion_fact_mutation',
                    'reject_ingestion_fact_truncate',
                    'validate_ingestion_raw_insert',
                    'validate_ingestion_run_record_insert',
                    'ensure_first_ingestion_run_record',
                    'validate_ingestion_attempt_insert',
                    'validate_ingestion_run_insert',
                    'protect_ingestion_run_mutation')
                  AND ?=ANY(coalesce(function_record.proconfig, ARRAY[]::TEXT[]))
                """, Integer.class, TEST_SCHEMA,
                "search_path=pg_catalog, " + TEST_SCHEMA));
    }

    private void assertCallerSearchPathCannotBypassSealedRun(
            IngestionRun run,
            RawRecord raw
    ) {
        String sql = "INSERT INTO \"" + TEST_SCHEMA
                + "\".security_status_ingestion_run_records("
                + "ingestion_run_id, raw_record_id, received_at) VALUES (?, ?, ?)";
        try (Connection connection = controlConnection();
             Statement searchPath = connection.createStatement();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            searchPath.execute("SET search_path TO pg_catalog");
            bind(statement, run.id(), raw.id(), FETCHED_AT);
            SQLException topLevel = assertThrows(SQLException.class, statement::execute);
            assertDatabaseError(topLevel, "55000", "sealed ingestion run");
        } catch (SQLException error) {
            throw new AssertionError("could not verify V7 function search_path isolation", error);
        }
    }

    private <T> List<ConcurrentResult<T>> runConcurrent(ConcurrentOperation<T> operation)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<ConcurrentResult<T>>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                    return transaction.execute(status -> {
                        int backendPid = jdbc.queryForObject(
                                "SELECT pg_backend_pid()", Integer.class);
                        try {
                            barrier.await();
                            return new ConcurrentResult<>(backendPid, operation.execute());
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    });
                }));
            }
            List<ConcurrentResult<T>> result = new ArrayList<>();
            for (Future<ConcurrentResult<T>> future : futures) result.add(future.get());
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private <T> List<ConcurrentOutcome<T>> runConcurrentCapturing(
            ConcurrentOperation<T> first,
            ConcurrentOperation<T> second
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<ConcurrentOperation<T>> operations = List.of(first, second);
        try {
            List<Future<ConcurrentOutcome<T>>> futures = new ArrayList<>();
            for (ConcurrentOperation<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    int[] backendPid = {-1};
                    try {
                        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                        T value = transaction.execute(status -> {
                            backendPid[0] = jdbc.queryForObject(
                                    "SELECT pg_backend_pid()", Integer.class);
                            try {
                                barrier.await();
                                return operation.execute();
                            } catch (Exception error) {
                                throw new IllegalStateException(error);
                            }
                        });
                        return new ConcurrentOutcome<>(backendPid[0], value, null);
                    } catch (Exception error) {
                        return new ConcurrentOutcome<>(backendPid[0], null, error);
                    }
                }));
            }
            List<ConcurrentOutcome<T>> result = new ArrayList<>();
            for (Future<ConcurrentOutcome<T>> future : futures) result.add(future.get());
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static <T> void assertDistinctBackends(List<ConcurrentResult<T>> results) {
        assertEquals(2, results.size());
        assertNotEquals(results.get(0).backendPid(), results.get(1).backendPid());
    }

    private void expectDatabaseFailure(
            String expectedSqlState,
            String expectedMessageFragment,
            String sql,
            Object... parameters
    ) {
        try (Connection connection = isolatedConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            bind(statement, parameters);
            SQLException topLevel = assertThrows(SQLException.class, statement::execute);
            assertDatabaseError(topLevel, expectedSqlState, expectedMessageFragment);
        } catch (SQLException error) {
            throw new AssertionError("could not isolate expected database failure", error);
        }
    }

    private void expectDatabaseCommitFailure(
            String expectedSqlState,
            String expectedMessageFragment,
            String sql,
            Object... parameters
    ) {
        try (Connection connection = isolatedConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            bind(statement, parameters);
            statement.execute();
            SQLException topLevel = assertThrows(SQLException.class, connection::commit);
            assertDatabaseError(topLevel, expectedSqlState, expectedMessageFragment);
        } catch (SQLException error) {
            throw new AssertionError("could not isolate expected database commit failure", error);
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object parameter = parameters[index];
            if (parameter instanceof Instant instant) {
                statement.setObject(index + 1, instant.atOffset(ZoneOffset.UTC));
            } else {
                statement.setObject(index + 1, parameter);
            }
        }
    }

    private static void assertDatabaseError(
            SQLException topLevel,
            String expectedSqlState,
            String expectedMessageFragment
    ) {
        SQLException databaseError = topLevel;
        while (databaseError != null
                && !expectedSqlState.equals(databaseError.getSQLState())) {
            databaseError = databaseError.getNextException();
        }
        assertNotNull(databaseError, () -> "expected SQLSTATE " + expectedSqlState
                + " but top-level SQLSTATE was " + topLevel.getSQLState());
        assertNotNull(databaseError.getMessage());
        assertTrue(databaseError.getMessage().contains(expectedMessageFragment),
                databaseError.getMessage());
    }

    private static String directAttemptSql(String table) {
        return """
                INSERT INTO %s(
                    ingestion_run_id, raw_record_id, attempt_no, attempt_logical_key, status,
                    processor_version, contract_version, published_at_verification,
                    requested_assurance_level, derived_known_from,
                    knowledge_time_policy_version, assurance_level,
                    error_code, result_metadata, result_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.formatted(table);
    }

    private static String directRawSql(String table) {
        boolean security = "security_status_raw_records".equals(table);
        String typeColumns = security ? "source_instrument_id" : "exchange, trade_date";
        String typeValues = security ? "?" : "?, ?";
        return """
                INSERT INTO %s(
                    first_ingestion_run_id, dataset_version_id, raw_record_logical_key,
                    record_namespace, source, source_version, source_record_id, source_revision,
                    %s,
                    source_published_at, source_effective_date, source_effective_at,
                    system_first_observed_at, system_recorded_at, source_trust_level,
                    raw_payload, payload_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.formatted(table, typeColumns, typeValues);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static void createIsolatedSchema() {
        requireSafeSchemaName();
        try (Connection connection = controlConnection(); Statement statement = connection.createStatement()) {
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_database()"));
            assertEquals("stock_quant_test", scalarText(statement, "SELECT current_user"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
            publicBaseline = publicBaseline(statement);
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
            assertEquals(1, scalar(statement, "SELECT count(*) FROM information_schema.schemata "
                    + "WHERE schema_name='" + TEST_SCHEMA + "'"));
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2d2b1a_it_ schema creation", error);
        }
    }

    private static Connection controlConnection() throws SQLException {
        return DriverManager.getConnection(
                credentials.url(), credentials.username(), credentials.password());
    }

    private static Connection isolatedConnection() throws SQLException {
        Connection connection = controlConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + TEST_SCHEMA + "\"");
        }
        return connection;
    }

    private static PublicBaseline publicBaseline(Statement statement) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : strings(statement, """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                ORDER BY table_name
                """)) {
            result.put(table, scalar(statement,
                    "SELECT count(*) FROM public." + quoteIdentifier(table)));
        }
        List<String> schemaObjects = strings(statement, """
                SELECT kind || ':' || identity
                FROM (
                    SELECT 'relation' AS kind,
                           c.relkind::TEXT || ':' || c.relname AS identity
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'constraint', c.conname || ':' || c.contype::TEXT || ':'
                           || coalesce(pg_get_constraintdef(c.oid), '')
                    FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace
                    WHERE n.nspname='public'
                    UNION ALL
                    SELECT 'trigger', table_record.relname || ':' || trigger_record.tgname
                    FROM pg_trigger trigger_record
                    JOIN pg_class table_record ON table_record.oid=trigger_record.tgrelid
                    JOIN pg_namespace n ON n.oid=table_record.relnamespace
                    WHERE n.nspname='public' AND NOT trigger_record.tgisinternal
                    UNION ALL
                    SELECT 'function', p.proname || ':'
                           || pg_get_function_identity_arguments(p.oid) || ':'
                           || md5(pg_get_functiondef(p.oid))
                    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                    WHERE n.nspname='public'
                ) object_record
                ORDER BY kind, identity
                """);
        List<String> flywayHistory = strings(statement, """
                SELECT installed_rank || ':' || coalesce(version, '') || ':'
                       || coalesce(checksum::text, '') || ':' || success
                FROM public.flyway_schema_history
                ORDER BY installed_rank
                """);
        List<String> extensions = strings(statement, """
                SELECT extension_record.extname || ':' || extension_record.extversion || ':'
                       || namespace_record.nspname
                FROM pg_extension extension_record
                JOIN pg_namespace namespace_record
                  ON namespace_record.oid=extension_record.extnamespace
                ORDER BY extension_record.extname
                """);
        return new PublicBaseline(Map.copyOf(result), schemaObjects, flywayHistory, extensions);
    }

    private static List<String> strings(Statement statement, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return List.copyOf(result);
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
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

    private static void requireSafeSchemaName() {
        if (!TEST_SCHEMA.matches("^stage_2d2b1a_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe temporary schema name");
        }
    }

    @FunctionalInterface
    private interface ConcurrentOperation<T> {
        T execute() throws Exception;
    }

    private record ConcurrentResult<T>(int backendPid, T value) {}

    private record ConcurrentOutcome<T>(int backendPid, T value, Throwable error) {}

    private record MutationProbe(
            String table,
            String idColumn,
            long id,
            String assignment,
            String message
    ) {}

    private record PublicBaseline(
            Map<String, Long> tableRows,
            List<String> schemaObjects,
            List<String> flywayHistory,
            List<String> extensions
    ) {}
}
