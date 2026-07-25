package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.announcement.AgentSecurityEventsContextService;
import com.stockquant.server.agent.announcement.AnnouncementContracts;
import com.stockquant.server.agent.announcement.AnnouncementIngestionService;
import com.stockquant.server.agent.announcement.AnnouncementProviderClient;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureResult;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderError;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import com.stockquant.server.agent.announcement.AnnouncementRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentStage2GAnnouncementPostgresIntegrationTest {

    private static final String SCHEMA_PREFIX = "stage_2g_announcement_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final LocalDate REQUEST_DATE = LocalDate.of(2026, 7, 25);
    private static final LocalDate START_DATE = REQUEST_DATE.minusDays(179);
    private static final Instant T1 = REQUEST_DATE.atTime(10, 0)
            .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
    private static final Instant T2 = T1.plusSeconds(60);
    private static final Instant T3 = T2.plusSeconds(60);

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired AnnouncementIngestionService ingestion;
    @Autowired AnnouncementRepository repository;
    @Autowired AgentSecurityEventsContextService contextService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockBean AnnouncementProviderClient provider;
    @MockBean(name = "agentTemporalClock") Clock agentTemporalClock;

    private final AtomicReference<Instant> now = new AtomicReference<>(T1);
    private final AtomicReference<Function<ProviderRequest, ProviderResponse>>
            responseFactory = new AtomicReference<>();

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
    }

    @BeforeEach
    void setUpMocks() {
        when(agentTemporalClock.instant()).thenAnswer(ignored -> now.get());
        when(provider.fetch(any())).thenAnswer(invocation -> {
            Function<ProviderRequest, ProviderResponse> factory =
                    responseFactory.get();
            if (factory == null) {
                throw new IllegalStateException("provider fixture is not configured");
            }
            return factory.apply(invocation.getArgument(0));
        });
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
    void migratesV1ToV10AndPersistsEmptyPartialAndVersionedCaptures() {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success=TRUE ORDER BY installed_rank
                        """, String.class));

        String emptySymbol = "600701";
        now.set(T1);
        responseFactory.set(request -> complete(request, List.of()));
        CaptureResult empty = ingestion.capture(
                new CaptureRequest(emptySymbol, START_DATE, REQUEST_DATE));
        assertTrue(empty.complete());
        assertEquals(0, empty.recordCount());
        assertEquals(0, empty.appendedCount());
        ObjectNode emptyContext = contextService.create(
                emptySymbol, REQUEST_DATE, T1.plusSeconds(3600));
        assertTrue(emptyContext.path("available").asBoolean());
        assertEquals(0, emptyContext.path("eventCount").asInt());

        String partialSymbol = "600702";
        responseFactory.set(request -> partial(
                request,
                List.of(record(
                        partialSymbol,
                        "1212345601",
                        "问询函",
                        REQUEST_DATE.minusDays(1)))));
        CaptureResult partial = ingestion.capture(
                new CaptureRequest(partialSymbol, START_DATE, REQUEST_DATE));
        assertFalse(partial.complete());
        assertEquals(1, partial.recordCount());
        assertEquals(1, partial.appendedCount());
        ObjectNode partialContext = contextService.create(
                partialSymbol, REQUEST_DATE, T1.plusSeconds(3600));
        assertFalse(partialContext.path("available").asBoolean());
        assertEquals(
                AnnouncementContracts.NO_COMPLETE_CAPTURE,
                partialContext.path("reasonCode").asText());

        String symbol = "600703";
        ProviderRecord a = record(
                symbol,
                "1212345602",
                "重大诉讼公告",
                REQUEST_DATE.minusDays(2));
        ProviderRecord b = record(
                symbol,
                "1212345602",
                "重大诉讼进展暨立案调查公告",
                REQUEST_DATE.minusDays(2));
        responseFactory.set(request -> complete(request, List.of(a)));
        now.set(T1);
        CaptureResult first = ingestion.capture(
                new CaptureRequest(symbol, START_DATE, REQUEST_DATE));
        assertEquals(1, first.appendedCount());
        CaptureResult repeated = ingestion.capture(
                new CaptureRequest(symbol, START_DATE, REQUEST_DATE));
        assertEquals(0, repeated.appendedCount());

        responseFactory.set(request -> complete(request, List.of(b)));
        now.set(T2);
        assertEquals(1, ingestion.capture(
                new CaptureRequest(symbol, START_DATE, REQUEST_DATE))
                .appendedCount());
        responseFactory.set(request -> complete(request, List.of(a)));
        now.set(T3);
        assertEquals(1, ingestion.capture(
                new CaptureRequest(symbol, START_DATE, REQUEST_DATE))
                .appendedCount());

        assertEquals(3L, count("""
                SELECT count(*) FROM announcement_observations
                WHERE symbol='600703'
                """));
        assertEquals(
                "重大诉讼公告",
                repository.findAsOf(
                        symbol, START_DATE, REQUEST_DATE, T1).get(0).title());
        assertEquals(
                "重大诉讼进展暨立案调查公告",
                repository.findAsOf(
                        symbol, START_DATE, REQUEST_DATE, T2).get(0).title());
        assertEquals(
                "重大诉讼公告",
                repository.findAsOf(
                        symbol, START_DATE, REQUEST_DATE, T3).get(0).title());
        assertTrue(repository.findAsOf(
                symbol, START_DATE, REQUEST_DATE, T1.minusSeconds(1)).isEmpty(),
                "late historical announcements must be invisible before first observation");

        ObjectNode context = contextService.create(
                symbol, REQUEST_DATE, T3.plusSeconds(3600));
        assertTrue(context.path("available").asBoolean());
        assertEquals(1, context.path("eventCount").asInt());
        assertEquals(
                "重大诉讼公告",
                context.withArray("events").get(0).path("title").asText());
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void databaseHardGatesAndAppendOnlyTriggersRejectInvalidFacts() {
        String symbol = "600704";
        responseFactory.set(request -> complete(request, List.of(record(
                symbol,
                "1212345603",
                "警示函",
                REQUEST_DATE.minusDays(1)))));
        now.set(T1);
        ingestion.capture(new CaptureRequest(symbol, START_DATE, REQUEST_DATE));

        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO announcement_observations (
                    batch_id, batch_version, source_code, provider_contract_version,
                    source_announcement_id, source_identity_strength, symbol,
                    security_name, title, reported_publish_date,
                    reported_publish_time_precision, source_url,
                    normalized_source_url, source_url_hash,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    assurance_level, formal_eligible, pit_verified,
                    revision_relationship_guaranteed, raw_payload_json
                )
                SELECT batch_id, batch_version, source_code, provider_contract_version,
                       'CNINFO:9999990001', source_identity_strength, symbol,
                       security_name, title,
                       (first_observed_at AT TIME ZONE 'Asia/Shanghai')::date + 1,
                       reported_publish_time_precision, source_url,
                       normalized_source_url, source_url_hash,
                       first_observed_at, known_at, recorded_at,
                       canonical_content_hash, ?,
                       assurance_level, formal_eligible, pit_verified,
                       revision_relationship_guaranteed, raw_payload_json
                FROM announcement_observations WHERE symbol=? LIMIT 1
                """, "a".repeat(64), symbol));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO announcement_observations (
                    batch_id, batch_version, source_code, provider_contract_version,
                    source_announcement_id, source_identity_strength, symbol,
                    security_name, title, reported_publish_date,
                    reported_publish_time_precision, source_url,
                    normalized_source_url, source_url_hash,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    assurance_level, formal_eligible, pit_verified,
                    revision_relationship_guaranteed, raw_payload_json
                )
                SELECT batch_id, batch_version, source_code, provider_contract_version,
                       'CNINFO:9999990002', source_identity_strength, symbol,
                       security_name, title, reported_publish_date,
                       reported_publish_time_precision, source_url,
                       normalized_source_url, source_url_hash,
                       first_observed_at, known_at, recorded_at,
                       canonical_content_hash, ?,
                       assurance_level, TRUE, pit_verified,
                       revision_relationship_guaranteed, raw_payload_json
                FROM announcement_observations WHERE symbol=? LIMIT 1
                """, "b".repeat(64), symbol));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO announcement_observations (
                    batch_id, batch_version, source_code, provider_contract_version,
                    source_announcement_id, source_identity_strength, symbol,
                    security_name, title, reported_publish_date,
                    reported_publish_time_precision, source_url,
                    normalized_source_url, source_url_hash,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    assurance_level, formal_eligible, pit_verified,
                    revision_relationship_guaranteed, raw_payload_json
                )
                SELECT batch_id, batch_version, source_code, provider_contract_version,
                       'CNINFO:9999990003', source_identity_strength, symbol,
                       security_name, title, reported_publish_date,
                       reported_publish_time_precision, source_url,
                       normalized_source_url, source_url_hash,
                       first_observed_at, known_at, recorded_at,
                       'NOT_A_HASH', ?,
                       assurance_level, formal_eligible, pit_verified,
                       revision_relationship_guaranteed, raw_payload_json
                FROM announcement_observations WHERE symbol=? LIMIT 1
                """, "c".repeat(64), symbol));

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE announcement_observations SET title='changed' WHERE symbol=?",
                symbol));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM announcement_observations WHERE symbol=?", symbol));
        assertThrows(DataAccessException.class, () -> jdbc.execute(
                "TRUNCATE TABLE announcement_observations"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE announcement_capture_batches SET record_count=0"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM announcement_capture_batches"));
        assertThrows(DataAccessException.class, () -> jdbc.execute(
                "TRUNCATE TABLE announcement_capture_batches CASCADE"));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void concurrentIdenticalCaptureAppendsOnlyOneObservation() throws Exception {
        String symbol = "600705";
        responseFactory.set(request -> complete(request, List.of(record(
                symbol,
                "1212345604",
                "对外担保公告",
                REQUEST_DATE.minusDays(1)))));
        now.set(T1);
        CaptureRequest request = new CaptureRequest(
                symbol, START_DATE, REQUEST_DATE);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<CaptureResult>> calls = List.of(
                    () -> ingestion.capture(request),
                    () -> ingestion.capture(request));
            List<Future<CaptureResult>> futures = executor.invokeAll(calls);
            List<Integer> appended = new ArrayList<>();
            for (Future<CaptureResult> future : futures) {
                appended.add(future.get().appendedCount());
            }
            appended.sort(Integer::compareTo);
            assertEquals(List.of(0, 1), appended);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1L, count("""
                SELECT count(*) FROM announcement_observations
                WHERE symbol='600705'
                """));
        assertEquals(2L, count("""
                SELECT count(*) FROM announcement_capture_batches
                WHERE symbol='600705'
                """));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private ProviderResponse complete(
            ProviderRequest request,
            List<ProviderRecord> records
    ) {
        int chunks = chunks(request.startDate(), request.endDate());
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

    private ProviderResponse partial(
            ProviderRequest request,
            List<ProviderRecord> records
    ) {
        int chunks = chunks(request.startDate(), request.endDate());
        return new ProviderResponse(
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                AnnouncementContracts.AKSHARE_VERSION,
                request.symbol(),
                request.startDate(),
                request.endDate(),
                false,
                chunks,
                chunks - 1,
                records,
                List.of(new ProviderError(
                        "AKSHARE_PROVIDER_TEMPORARY_FAILURE",
                        request.endDate(),
                        request.endDate(),
                        3)));
    }

    private ProviderRecord record(
            String symbol,
            String id,
            String title,
            LocalDate reportedDate
    ) {
        String url = "https://static.cninfo.com.cn/finalpage/"
                + reportedDate + "/" + id + ".pdf";
        ObjectNode raw = mapper.createObjectNode();
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

    private static int chunks(LocalDate start, LocalDate end) {
        return (int) Math.ceil(((double) (end.toEpochDay() - start.toEpochDay()) + 1)
                / 30.0);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
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
                    "stock_quant_test must permit isolated stage_2g schema",
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
            while (tables.next()) {
                tableNames.add(tables.getString(1));
            }
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
                "^stage_2g_announcement_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe test schema: " + schema);
        }
    }

    private record PublicBaseline(
            Map<String, Long> rowCounts,
            List<String> migrationChecksums
    ) {
    }
}
