package com.stockquant.server.agent;

import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementContracts;
import com.stockquant.server.agent.announcement.AnnouncementIngestionService;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureResult;
import com.stockquant.server.agent.announcement.AnnouncementRepository;
import com.stockquant.server.agent.announcement.AnnouncementRepository.ObservationRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_PYTHON_BASE_URL", matches = ".+")
class AgentStage2GAkshareLiveGateTest {

    private static final String SCHEMA_PREFIX = "stage_2g_akshare_live_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final String SYMBOL = "000001";
    private static final LocalDate START_DATE = LocalDate.of(2023, 6, 19);
    private static final LocalDate END_DATE = LocalDate.of(2023, 12, 20);

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired AnnouncementIngestionService ingestion;
    @Autowired AnnouncementRepository repository;
    @Autowired AnnouncementCanonicalService canonical;
    @Autowired JdbcTemplate jdbc;

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
                () -> AgentPythonSmokeEnvironment.validate(
                        System.getenv("STOCK_QUANT_PYTHON_BASE_URL")));
        registry.add("stockquant.announcement.akshare.read-timeout", () -> "25m");
    }

    @AfterAll
    static void dropSchemaAndVerifyPublic() throws Exception {
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
    void liveAkshareCapturePersistsAuditableResearchFacts() {
        CaptureResult result = ingestion.capture(
                new CaptureRequest(SYMBOL, START_DATE, END_DATE));

        assertTrue(result.complete());
        assertEquals(7, result.chunkCount());
        assertEquals(7, result.successfulChunkCount());
        assertTrue(result.recordCount() >= 1);
        assertEquals(result.recordCount(), result.appendedCount());
        assertEquals(1L, count("announcement_capture_batches"));
        assertEquals(result.appendedCount(), count("announcement_observations"));

        List<ObservationRecord> values = repository.findAsOf(
                SYMBOL, START_DATE, END_DATE, Instant.now());
        assertEquals(result.appendedCount(), values.size());
        for (ObservationRecord value : values) {
            assertEquals(AnnouncementContracts.SOURCE_CODE, value.sourceCode());
            assertEquals(
                    AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                    value.providerContractVersion());
            assertEquals(AnnouncementContracts.ASSURANCE_LEVEL, value.assuranceLevel());
            assertEquals(
                    AnnouncementContracts.PUBLISH_TIME_PRECISION,
                    value.reportedPublishTimePrecision());
            assertFalse(value.formalEligible());
            assertFalse(value.pitVerified());
            assertFalse(value.revisionRelationshipGuaranteed());
            assertEquals(value.firstObservedAt(), value.knownAt());
            assertFalse(value.reportedPublishDate().isBefore(START_DATE));
            assertFalse(value.reportedPublishDate().isAfter(END_DATE));
            assertTrue(value.canonicalContentHash().matches("^[0-9a-f]{64}$"));
            assertTrue(value.observationVersion().matches("^[0-9a-f]{64}$"));
            assertTrue(value.sourceAnnouncementId().matches(
                    "^(CNINFO:[A-Za-z0-9._-]+"
                            + "|CNINFO_URL_SHA256:[0-9a-f]{64})$"));
            var identity = canonical.sourceIdentity(value.sourceUrl());
            assertEquals(value.sourceAnnouncementId(), identity.sourceAnnouncementId());
            assertEquals(value.sourceIdentityStrength(), identity.strength());
            assertEquals(value.normalizedSourceUrl(), identity.normalizedUrl());
            assertTrue(canonical.hashMatches(new AnnouncementFact(
                    value.symbol(),
                    value.securityName(),
                    value.title(),
                    value.reportedPublishDate(),
                    value.sourceUrl(),
                    value.normalizedSourceUrl(),
                    value.sourceUrlHash(),
                    value.sourceAnnouncementId(),
                    value.sourceIdentityStrength(),
                    value.firstObservedAt(),
                    value.canonicalContentHash(),
                    value.observationVersion(),
                    value.rawPayload())));
        }

        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM announcement_observations
                WHERE symbol <> '000001'
                   OR source_code <> 'AKSHARE_CNINFO_RESEARCH_V1'
                   OR provider_contract_version <> 'AKSHARE_CNINFO_PROVIDER_V1'
                   OR assurance_level <> 'RESEARCH'
                   OR formal_eligible
                   OR pit_verified
                   OR revision_relationship_guaranteed
                   OR reported_publish_time_precision <> 'DATE_ONLY'
                   OR first_observed_at <> known_at
                   OR known_at > recorded_at
                   OR jsonb_typeof(raw_payload_json) <> 'object'
                """, Long.class));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE announcement_observations SET title='changed'"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM announcement_observations"));
        assertThrows(DataAccessException.class, () -> jdbc.execute(
                "TRUNCATE TABLE announcement_observations"));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
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
                    "stock_quant_test must permit isolated AKShare live schema",
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
            rows.put(table, scalar(statement,
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
                "^stage_2g_akshare_live_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe test schema: " + schema);
        }
    }

    private record PublicBaseline(
            Map<String, Long> rowCounts,
            List<String> migrationChecksums
    ) {
    }
}
