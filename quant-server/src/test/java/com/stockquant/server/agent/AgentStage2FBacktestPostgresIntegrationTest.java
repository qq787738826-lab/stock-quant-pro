package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.backtest.AgentBacktestContextService;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.backtest.MarketDataObservationRepository;
import com.stockquant.server.agent.backtest.MarketDataPersistenceService;
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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentStage2FBacktestPostgresIntegrationTest {

    private static final String SCHEMA_PREFIX = "stage_2f_pit_it_";
    private static final String TEST_SCHEMA = SCHEMA_PREFIX
            + UUID.randomUUID().toString().replace("-", "");
    private static final String SYMBOL = "600971";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private static AgentPostgresTestEnvironment.Credentials credentials;
    private static PublicBaseline publicBaseline;
    private static boolean schemaCreated;

    @Autowired MarketDataPersistenceService persistenceService;
    @Autowired MarketDataObservationRepository observationRepository;
    @Autowired BacktestCanonicalHashService canonicalHashService;
    @Autowired AgentBacktestContextService contextService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

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
    void appliesV9AndPreservesPitVersionsIdempotencyAndAppendOnlyRules() {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success=TRUE ORDER BY installed_rank
                        """, String.class));
        long observationBaseline = count("daily_bar_observations");
        long currentBarBaseline = count("daily_bars");
        LocalDate requestDate = LocalDate.of(2025, 6, 30);
        Instant firstCaptureTime = BacktestContracts
                .earliestDailyBarKnownAt(requestDate);
        List<Bar> original = bars(SYMBOL, requestDate, 120, BigDecimal.ZERO);
        var first = persistenceAt(firstCaptureTime).persistBars(
                SYMBOL,
                original,
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_A",
                "TEST_FIXTURE");
        assertEquals(120, first.appendedObservationCount());
        assertEquals(observationBaseline + 120, count("daily_bar_observations"));
        assertEquals(currentBarBaseline + 120, count("daily_bars"));
        Instant firstKnownAt = jdbc.queryForObject(
                "SELECT min(known_at) FROM daily_bar_observations WHERE symbol=?",
                Instant.class,
                SYMBOL);

        var repeated = persistenceAt(firstCaptureTime).persistBars(
                SYMBOL,
                original,
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_A",
                "TEST_FIXTURE");
        assertEquals(0, repeated.appendedObservationCount());
        assertEquals(observationBaseline + 120, count("daily_bar_observations"));

        LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        List<Bar> revised = new ArrayList<>(original);
        Bar previous = revised.get(10);
        BigDecimal changed = previous.close().add(new BigDecimal("1.00"));
        revised.set(10, new Bar(
                SYMBOL,
                previous.tradeDate(),
                changed,
                changed.add(new BigDecimal("0.50")),
                changed.subtract(new BigDecimal("0.50")),
                changed,
                previous.volume(),
                previous.amount(),
                previous.turnoverRate()));
        var revision = persistenceAt(firstCaptureTime.plusSeconds(60)).persistBars(
                SYMBOL,
                revised,
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_B",
                "TEST_FIXTURE");
        assertEquals(120, revision.appendedObservationCount(),
                "a new source revision must remain visible in every selected bar lineage");
        assertEquals(observationBaseline + 240, count("daily_bar_observations"));
        var repeatedRevision = persistenceAt(firstCaptureTime.plusSeconds(60)).persistBars(
                SYMBOL,
                revised,
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_B",
                "TEST_FIXTURE");
        assertEquals(0, repeatedRevision.appendedObservationCount(),
                "the same content and source revision must remain idempotent");
        assertEquals(observationBaseline + 240, count("daily_bar_observations"));
        Instant secondKnownAt = jdbc.queryForObject(
                "SELECT max(known_at) FROM daily_bar_observations WHERE symbol=?",
                Instant.class,
                SYMBOL);
        assertTrue(secondKnownAt.isAfter(firstKnownAt));

        List<MarketDataObservationRepository.ObservedDailyBar> beforeRevision =
                observationRepository.findAsOf(
                        SYMBOL,
                        requestDate,
                        firstKnownAt,
                        500);
        List<MarketDataObservationRepository.ObservedDailyBar> afterRevision =
                observationRepository.findAsOf(
                        SYMBOL,
                        requestDate,
                        secondKnownAt,
                        500);
        assertEquals(
                0,
                beforeRevision.get(10).close().compareTo(previous.close()));
        assertEquals(
                0,
                afterRevision.get(10).close().compareTo(changed));
        assertEquals("REVISION_A", beforeRevision.get(10).sourceRevision());
        assertEquals("REVISION_B", afterRevision.get(10).sourceRevision());

        LockSupport.parkNanos(Duration.ofMillis(2).toNanos());
        var reverted = persistenceAt(firstCaptureTime.plusSeconds(120)).persistBars(
                SYMBOL,
                original,
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_C",
                "TEST_FIXTURE");
        assertEquals(120, reverted.appendedObservationCount(),
                "A-to-B-to-A must preserve the later A occurrence");
        assertEquals(observationBaseline + 360, count("daily_bar_observations"));
        assertEquals(
                0,
                jdbc.queryForObject("""
                        SELECT close FROM daily_bars
                        WHERE symbol=? AND trade_date=? AND adjust_type='QFQ'
                        """, BigDecimal.class, SYMBOL, previous.tradeDate())
                        .compareTo(previous.close()));

        JsonNode context = contextService.create(
                SYMBOL,
                requestDate,
                requestDate.plusDays(1)
                        .atStartOfDay(SHANGHAI)
                        .toInstant());
        assertTrue(context.path("available").asBoolean());
        assertEquals(120, context.path("barCount").asInt());
        assertEquals(
                "REVISION_C",
                context.path("bars").get(10).path("sourceRevision").asText());
        assertTrue(context.path("inputDataHash").asText().matches("^[0-9a-f]{64}$"));

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE daily_bar_observations SET volume=volume+1 WHERE symbol=?",
                SYMBOL));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM daily_bar_observations WHERE symbol=?",
                SYMBOL));
        assertThrows(DataAccessException.class, () -> jdbc.execute(
                "TRUNCATE TABLE daily_bar_observations"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE market_data_observation_batches SET record_count=0"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM market_data_observation_batches"));
        assertThrows(DataAccessException.class, () -> jdbc.execute(
                "TRUNCATE TABLE market_data_observation_batches CASCADE"));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void preCloseDailyBarUpdatesCurrentProjectionWithoutFalsePitBatch() {
        LocalDate tradeDate = LocalDate.of(2025, 6, 30);
        Instant preClose = tradeDate.atTime(14, 59, 59)
                .atZone(SHANGHAI)
                .toInstant();
        MarketDataPersistenceService preClosePersistence =
                persistenceAt(preClose);
        long batchBaseline = count("market_data_observation_batches");
        long observationBaseline = count("daily_bar_observations");

        String currentOnlySymbol = "600976";
        var currentOnly = preClosePersistence.persistBars(
                currentOnlySymbol,
                bars(currentOnlySymbol, tradeDate, 1, BigDecimal.ZERO),
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_PRE_CLOSE",
                "TEST_FIXTURE");
        assertNull(currentOnly.batchVersion());
        assertNull(currentOnly.datasetVersion());
        assertEquals(0, currentOnly.appendedObservationCount());
        assertEquals(batchBaseline, count("market_data_observation_batches"));
        assertEquals(observationBaseline, count("daily_bar_observations"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM daily_bars WHERE symbol=?
                """, Integer.class, currentOnlySymbol));

        String mixedSymbol = "600977";
        var mixed = preClosePersistence.persistBars(
                mixedSymbol,
                bars(mixedSymbol, tradeDate, 2, BigDecimal.ZERO),
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_PRE_CLOSE_MIXED",
                "TEST_FIXTURE");
        assertEquals(1, mixed.appendedObservationCount());
        assertEquals(batchBaseline + 1, count("market_data_observation_batches"));
        assertEquals(observationBaseline + 1, count("daily_bar_observations"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT record_count FROM market_data_observation_batches
                WHERE batch_version=?
                """, Integer.class, mixed.batchVersion()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM daily_bars WHERE symbol=?
                """, Integer.class, mixedSymbol));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void databaseRejectsPredatedFuturePreCloseAndWeekendDailyBars() {
        long batchBaseline = count("market_data_observation_batches");
        long observationBaseline = count("daily_bar_observations");

        LocalDate futureTradeDate = nextTradingDate(
                LocalDate.now(SHANGHAI).plusDays(1));
        Instant pastKnowledge = Instant.now().minusSeconds(60);
        assertThrows(DataAccessException.class, () -> insertObservationAtomically(
                "600978",
                futureTradeDate,
                pastKnowledge,
                pastKnowledge,
                pastKnowledge.plusSeconds(1),
                "1"));

        LocalDate historicalTradeDate = completedTradingDate();
        Instant preClose = historicalTradeDate.atTime(14, 59, 59)
                .atZone(SHANGHAI)
                .toInstant();
        Instant legalClose = BacktestContracts
                .earliestDailyBarKnownAt(historicalTradeDate);
        assertThrows(DataAccessException.class, () -> insertObservationAtomically(
                "600979",
                historicalTradeDate,
                preClose,
                legalClose,
                legalClose.plusSeconds(1),
                "2"));
        assertThrows(DataAccessException.class, () -> insertObservationAtomically(
                "600980",
                historicalTradeDate,
                preClose,
                preClose,
                legalClose.plusSeconds(1),
                "3"));

        LocalDate weekend = nextSaturday(historicalTradeDate);
        Instant weekendClose = weekend.atTime(15, 0)
                .atZone(SHANGHAI)
                .toInstant();
        assertThrows(DataAccessException.class, () -> insertObservationAtomically(
                "600981",
                weekend,
                weekendClose,
                weekendClose,
                weekendClose.plusSeconds(1),
                "4"));
        Bar weekdayTemplate = bars(
                "600982", historicalTradeDate, 1, BigDecimal.ZERO).get(0);
        Bar weekendBar = new Bar(
                weekdayTemplate.symbol(),
                weekend,
                weekdayTemplate.open(),
                weekdayTemplate.high(),
                weekdayTemplate.low(),
                weekdayTemplate.close(),
                weekdayTemplate.volume(),
                weekdayTemplate.amount(),
                weekdayTemplate.turnoverRate());
        assertThrows(IllegalArgumentException.class, () -> persistenceAt(
                weekendClose).persistBars(
                "600982",
                List.of(weekendBar),
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_WEEKEND",
                "TEST_FIXTURE"));

        assertEquals(batchBaseline, count("market_data_observation_batches"));
        assertEquals(observationBaseline, count("daily_bar_observations"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM daily_bars WHERE symbol='600982'
                """, Integer.class));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void failedPersistenceRollsBackBatchObservationAndCurrentProjection() {
        String symbol = "600972";
        long batchBaseline = count("market_data_observation_batches");
        long observationBaseline = count("daily_bar_observations");
        jdbc.execute("""
                CREATE FUNCTION reject_stage_2f_test_security_insert()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'stage 2F atomic rollback fixture'
                        USING ERRCODE = '55000';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_stage_2f_test_security_insert
                BEFORE INSERT ON securities
                FOR EACH ROW
                WHEN (NEW.symbol = '600972')
                EXECUTE FUNCTION reject_stage_2f_test_security_insert()
                """);
        try {
            assertThrows(DataAccessException.class, () -> persistenceService.persistBars(
                    symbol,
                    bars(symbol, completedTradingDate(), 2, BigDecimal.ZERO),
                    "TEST_FIXTURE_STAGE_2F",
                    "REVISION_ATOMIC_FAILURE",
                    "TEST_FIXTURE"));
        } finally {
            jdbc.execute("""
                    DROP TRIGGER reject_stage_2f_test_security_insert ON securities
                    """);
            jdbc.execute("DROP FUNCTION reject_stage_2f_test_security_insert()");
        }
        assertEquals(batchBaseline, count("market_data_observation_batches"));
        assertEquals(observationBaseline, count("daily_bar_observations"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM daily_bars WHERE symbol=?",
                Integer.class,
                symbol));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM securities WHERE symbol=?",
                Integer.class,
                symbol));

        Bar precise = new Bar(
                symbol,
                completedTradingDate(),
                new BigDecimal("20.00001"),
                new BigDecimal("21.00001"),
                new BigDecimal("19.00001"),
                new BigDecimal("20.00001"),
                1L,
                BigDecimal.ONE,
                BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> persistenceService.persistBars(
                symbol,
                List.of(precise),
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_PRECISION",
                "TEST_FIXTURE"));

        List<Bar> ordered = bars(
                symbol, completedTradingDate(), 2, BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> persistenceService.persistBars(
                symbol,
                List.of(ordered.get(1), ordered.get(0)),
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_UNORDERED",
                "TEST_FIXTURE"));
        assertThrows(IllegalArgumentException.class, () -> persistenceService.persistBars(
                symbol,
                List.of(ordered.get(0), ordered.get(0)),
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_DUPLICATE_DATE",
                "TEST_FIXTURE"));
        assertEquals(batchBaseline, count("market_data_observation_batches"));
        assertEquals(observationBaseline, count("daily_bar_observations"));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void lateHistoricalBarsRemainUnavailableBeforeTheirKnowledgeTime() {
        String symbol = "600973";
        LocalDate historicalRequestDate = previousOrSameTradingDate(
                LocalDate.now(SHANGHAI).minusDays(30));
        List<Bar> lateBars = bars(
                symbol,
                historicalRequestDate,
                120,
                BigDecimal.ZERO);
        var capture = persistenceService.persistBars(
                symbol,
                lateBars,
                "TEST_FIXTURE_STAGE_2F",
                "REVISION_LATE",
                "TEST_FIXTURE");
        assertEquals(120, capture.appendedObservationCount());

        JsonNode historicalContext = contextService.create(
                symbol,
                historicalRequestDate,
                Instant.now());
        assertFalse(historicalContext.path("available").asBoolean());
        assertEquals(
                BacktestContracts.KNOWLEDGE_TIME_UNVERIFIABLE,
                historicalContext.path("reasonCode").asText(),
                "stable content hashes cannot prove that late data was known at the old cutoff");
        assertTrue(observationRepository.findAsOf(
                symbol,
                historicalRequestDate,
                Instant.now(),
                500).size() >= 120);
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void sameKnownAtSelectsLatestPhysicalObservationDeterministically() {
        String symbol = "600974";
        LocalDate tradeDate = completedTradingDate();
        Instant knownAt = Instant.now().minusSeconds(5);
        insertObservation(
                symbol,
                tradeDate,
                knownAt,
                "SAME_TIME_BATCH_A",
                "SAME_TIME_DATASET_A",
                "REVISION_A",
                "1".repeat(64),
                "a".repeat(64),
                new BigDecimal("20.00"));
        insertObservation(
                symbol,
                tradeDate,
                knownAt,
                "SAME_TIME_BATCH_B",
                "SAME_TIME_DATASET_B",
                "REVISION_B",
                "2".repeat(64),
                "b".repeat(64),
                new BigDecimal("21.00"));

        List<MarketDataObservationRepository.ObservedDailyBar> selected =
                observationRepository.findAsOf(
                        symbol,
                        tradeDate,
                        knownAt.plusSeconds(1),
                        500);
        assertEquals(1, selected.size());
        assertEquals("REVISION_B", selected.get(0).sourceRevision());
        assertEquals(
                0,
                selected.get(0).close().compareTo(new BigDecimal("21.00")));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    @Test
    void concurrentIdenticalCapturesProduceOneObservationPerBar()
            throws Exception {
        String symbol = "600975";
        LocalDate requestDate = completedTradingDate();
        List<Bar> input = bars(symbol, requestDate, 120, BigDecimal.ZERO);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MarketDataPersistenceService.CaptureResult> first =
                    executor.submit(() -> persistenceService.persistBars(
                            symbol,
                            input,
                            "TEST_FIXTURE_STAGE_2F",
                            "REVISION_CONCURRENT",
                            "TEST_FIXTURE"));
            Future<MarketDataPersistenceService.CaptureResult> second =
                    executor.submit(() -> persistenceService.persistBars(
                            symbol,
                            input,
                            "TEST_FIXTURE_STAGE_2F",
                            "REVISION_CONCURRENT",
                            "TEST_FIXTURE"));
            assertEquals(
                    120,
                    first.get().appendedObservationCount()
                            + second.get().appendedObservationCount());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(120, jdbc.queryForObject("""
                SELECT count(*) FROM daily_bar_observations WHERE symbol=?
                """, Integer.class, symbol));
        assertEquals(120, jdbc.queryForObject("""
                SELECT count(*) FROM daily_bars WHERE symbol=?
                """, Integer.class, symbol));
        assertEquals(publicBaseline, currentPublicBaseline());
    }

    private void insertObservationAtomically(
            String symbol,
            LocalDate tradeDate,
            Instant firstObservedAt,
            Instant knownAt,
            Instant recordedAt,
            String hashDigit
    ) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String batchVersion = "PIT_TIME_GUARD_BATCH_" + suffix;
        String datasetVersion = "PIT_TIME_GUARD_DATASET_" + suffix;
        jdbc.update("""
                WITH inserted_batch AS (
                    INSERT INTO market_data_observation_batches(
                        batch_version, source_code, dataset_version, capture_type,
                        observed_at, recorded_at, record_count, source_metadata
                    ) VALUES (
                        ?, 'TEST_FIXTURE_STAGE_2F', ?, 'TEST_FIXTURE',
                        ?, ?, 1, '{}'::jsonb
                    )
                    RETURNING id
                )
                INSERT INTO daily_bar_observations(
                    observation_version, batch_id, symbol, trade_date,
                    adjust_type, open, high, low, close, volume, amount,
                    turnover_rate, source_code, source_revision,
                    dataset_version, first_observed_at, known_at, recorded_at,
                    canonical_content_hash
                )
                SELECT ?, inserted_batch.id, ?, ?, 'QFQ',
                       20, 21, 19, 20, 10000, 1000000, 0.5,
                       'TEST_FIXTURE_STAGE_2F', 'REVISION_TIME_GUARD',
                       ?, ?, ?, ?, ?
                FROM inserted_batch
                """,
                batchVersion,
                datasetVersion,
                Timestamp.from(firstObservedAt),
                Timestamp.from(recordedAt),
                hashDigit.repeat(64),
                symbol,
                tradeDate,
                datasetVersion,
                Timestamp.from(firstObservedAt),
                Timestamp.from(knownAt),
                Timestamp.from(recordedAt),
                hashDigit.repeat(64));
    }

    private void insertObservation(
            String symbol,
            LocalDate tradeDate,
            Instant knownAt,
            String batchVersion,
            String datasetVersion,
            String revision,
            String observationVersion,
            String contentHash,
            BigDecimal close
    ) {
        Long batchId = jdbc.queryForObject("""
                INSERT INTO market_data_observation_batches(
                    batch_version, source_code, dataset_version, capture_type,
                    observed_at, recorded_at, record_count, source_metadata
                ) VALUES (?, 'TEST_FIXTURE_STAGE_2F', ?, 'TEST_FIXTURE',
                          ?, ?, 1, '{}'::jsonb)
                RETURNING id
                """, Long.class,
                batchVersion,
                datasetVersion,
                Timestamp.from(knownAt),
                Timestamp.from(knownAt));
        jdbc.update("""
                INSERT INTO daily_bar_observations(
                    observation_version, batch_id, symbol, trade_date,
                    adjust_type, open, high, low, close, volume, amount,
                    turnover_rate, source_code, source_revision,
                    dataset_version, first_observed_at, known_at, recorded_at,
                    canonical_content_hash
                ) VALUES (
                    ?, ?, ?, ?, 'QFQ', ?, ?, ?, ?, 10000, 1000000, 0.5,
                    'TEST_FIXTURE_STAGE_2F', ?, ?, ?, ?, ?, ?
                )
                """,
                observationVersion,
                batchId,
                symbol,
                tradeDate,
                close,
                close.add(new BigDecimal("0.50")),
                close.subtract(new BigDecimal("0.50")),
                close,
                revision,
                datasetVersion,
                Timestamp.from(knownAt),
                Timestamp.from(knownAt),
                Timestamp.from(knownAt),
                contentHash);
    }

    private static List<Bar> bars(
            String symbol,
            LocalDate end,
            int count,
            BigDecimal shift
    ) {
        List<Bar> values = new ArrayList<>();
        List<LocalDate> tradeDates = tradingDates(end, count);
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("20")
                    .add(new BigDecimal("0.10").multiply(BigDecimal.valueOf(index)))
                    .add(shift);
            values.add(new Bar(
                    symbol,
                    tradeDates.get(index),
                    close,
                    close.add(new BigDecimal("0.50")),
                    close.subtract(new BigDecimal("0.50")),
                    close,
                    10_000L + index,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("0.5000")));
        }
        return List.copyOf(values);
    }

    private MarketDataPersistenceService persistenceAt(Instant captureTime) {
        return new MarketDataPersistenceService(
                jdbc,
                observationRepository,
                canonicalHashService,
                objectMapper,
                Clock.fixed(captureTime, SHANGHAI));
    }

    private static LocalDate completedTradingDate() {
        return previousOrSameTradingDate(LocalDate.now(SHANGHAI).minusDays(1));
    }

    private static LocalDate previousOrSameTradingDate(LocalDate date) {
        LocalDate candidate = date;
        while (!BacktestContracts.isSupportedDailyBarTradeDate(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private static LocalDate nextTradingDate(LocalDate date) {
        LocalDate candidate = date;
        while (!BacktestContracts.isSupportedDailyBarTradeDate(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static LocalDate nextSaturday(LocalDate date) {
        LocalDate candidate = date.plusDays(1);
        while (candidate.getDayOfWeek().getValue() != 6) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private static List<LocalDate> tradingDates(LocalDate end, int count) {
        List<LocalDate> values = new ArrayList<>();
        LocalDate candidate = previousOrSameTradingDate(end);
        while (values.size() < count) {
            if (BacktestContracts.isSupportedDailyBarTradeDate(candidate)) {
                values.add(candidate);
            }
            candidate = candidate.minusDays(1);
        }
        Collections.reverse(values);
        return List.copyOf(values);
    }

    private long count(String table) {
        if (!List.of(
                "market_data_observation_batches",
                "daily_bar_observations",
                "daily_bars").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM " + quoteIdentifier(table), Long.class);
        return value == null ? 0L : value;
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
            statement.execute("CREATE SCHEMA \"" + TEST_SCHEMA + "\"");
            schemaCreated = true;
        } catch (Exception error) {
            throw new IllegalStateException(
                    "stock_quant_test must permit isolated stage_2f_pit_it_ schema",
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
            throw new IllegalStateException("无法读取public基线", error);
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
                    SELECT 'function', p.proname || ':'
                           || pg_get_function_identity_arguments(p.oid)
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

    private static List<String> strings(Statement statement, String sql)
            throws SQLException {
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

    private static void requireSafeSchemaName(String schema) {
        if (!schema.matches("^stage_2f_pit_it_[0-9a-f]{32}$")) {
            throw new IllegalStateException("unsafe temporary schema name");
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
