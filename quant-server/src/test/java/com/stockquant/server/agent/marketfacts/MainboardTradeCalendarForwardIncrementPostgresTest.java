package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardTradeCalendarForwardIncrementPostgresTest {
    private static final LocalDate INITIAL_MAX = LocalDate.of(2026, 8, 27);
    private static final LocalDate TARGET = LocalDate.of(2026, 9, 17);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-17T08:00:00Z"), ZoneOffset.UTC);
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = System.getenv("STOCK_QUANT_FORWARD_TEST_JDBC_URL");
        String user = System.getenv("STOCK_QUANT_FORWARD_TEST_DB_USER");
        String password = System.getenv(
                "STOCK_QUANT_FORWARD_TEST_DB_PASSWORD");
        if (url == null || user == null || password == null) return;
        dataSource = new DriverManagerDataSource(url, user, password);
    }

    @BeforeEach
    void resetDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .cleanDisabled(false).locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void forwardIncrementIsAppendOnlyAndRepeatedOrEarlierRangesAreNoOp() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = TushareDedicatedResearchRuntimeComponents
                .createE2eDryRun(dataSource, CLOCK, gateway)) {
            components.mainboardUniverseCaptureService().capture(null, true,
                    Set.of(), INITIAL_MAX.minusDays(89), INITIAL_MAX, true,
                    "0123456789abcdef0123456789abcdef01234567",
                    Duration.ofSeconds(5), 0);
            int oldRows = jdbc.queryForObject("""
                    SELECT count(*) FROM trading_calendar_facts_v1
                     WHERE calendar_date <= ?
                    """, Integer.class, INITIAL_MAX);
            String oldFingerprint = jdbc.queryForObject("""
                    SELECT md5(string_agg(o.id::text || ':' ||
                           o.canonical_content_hash, ',' ORDER BY o.id))
                      FROM pit_market_fact_observations o
                      JOIN trading_calendar_facts_v1 c
                        ON c.observation_id=o.id
                     WHERE c.calendar_date <= ?
                    """, String.class, INITIAL_MAX);
            var service = new MainboardTradeCalendarForwardIncrementService(
                    jdbc, new ObjectMapper().findAndRegisterModules(),
                    components.mainboardUniverseCaptureService(), CLOCK);
            var progress =
                    new MainboardTradeCalendarForwardIncrementService
                            .Progress();
            var outcome = service.execute(TARGET, 4, 2,
                    "0123456789abcdef0123456789abcdef01234567", progress);

            assertEquals("APPENDED", outcome.action());
            assertEquals(INITIAL_MAX.plusDays(1), outcome.startDate());
            assertEquals(TARGET, outcome.finalSseMaxCalDate());
            assertEquals(TARGET, outcome.finalSzseMaxCalDate());
            assertEquals(21, outcome.rangeCalendarDateCount());
            assertEquals(2, outcome.providerCalls());
            assertEquals(1,
                    outcome.calendarCallCountsByExchange().get("SSE"));
            assertEquals(1,
                    outcome.calendarCallCountsByExchange().get("SZSE"));
            assertEquals(42, outcome.appended());
            assertEquals(0, outcome.duplicateCount());
            assertTrue(outcome.continuousCoverage());
            assertTrue(outcome.knownAtValid());
            assertTrue(outcome.firstObservedAtValid());
            assertTrue(outcome.lineageValid());
            assertTrue(outcome.providerFieldsPreserved());
            assertTrue(outcome.existingFactsUnchanged());
            assertEquals(oldRows, jdbc.queryForObject("""
                    SELECT count(*) FROM trading_calendar_facts_v1
                     WHERE calendar_date <= ?
                    """, Integer.class, INITIAL_MAX));
            assertEquals(oldFingerprint, jdbc.queryForObject("""
                    SELECT md5(string_agg(o.id::text || ':' ||
                           o.canonical_content_hash, ',' ORDER BY o.id))
                      FROM pit_market_fact_observations o
                      JOIN trading_calendar_facts_v1 c
                        ON c.observation_id=o.id
                     WHERE c.calendar_date <= ?
                    """, String.class, INITIAL_MAX));

            var repeat = service.execute(TARGET, 4, 2,
                    "0123456789abcdef0123456789abcdef01234567",
                    new MainboardTradeCalendarForwardIncrementService
                            .Progress());
            var earlier = service.execute(TARGET.minusDays(7), 4, 2,
                    "0123456789abcdef0123456789abcdef01234567",
                    new MainboardTradeCalendarForwardIncrementService
                            .Progress());
            assertEquals("NO_OP", repeat.action());
            assertEquals(0, repeat.providerCalls());
            assertEquals("NO_OP", earlier.action());
            assertEquals(0, earlier.providerCalls());
            assertEquals(5, gateway.calls());
        }
    }

    @Test
    void oneSidedProviderFailureLeavesBothFormalCalendarMaximaUnchanged() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var seed = TushareDedicatedResearchRuntimeComponents
                .createE2eDryRun(dataSource, CLOCK)) {
            seed.mainboardUniverseCaptureService().capture(null, true,
                    Set.of(), INITIAL_MAX.minusDays(89), INITIAL_MAX, true,
                    "0123456789abcdef0123456789abcdef01234567",
                    Duration.ofSeconds(5), 0);
        }
        var snapshot = new ResearchUniverseMainboardRepository(jdbc)
                .latest().orElseThrow();
        assertEquals(INITIAL_MAX, max(jdbc, "SSE"));
        assertEquals(INITIAL_MAX, max(jdbc, "SZSE"));
        var failing = new TushareControlledAcceptanceE2eDryRunGateway(2);
        try (var components = TushareDedicatedResearchRuntimeComponents
                .createE2eDryRun(dataSource, CLOCK, failing)) {
            var failure = assertThrows(
                    TushareMainboardUniverseCaptureService.CaptureFailure.class,
                    () -> components.mainboardUniverseCaptureService()
                            .captureForwardCalendars(snapshot,
                                    INITIAL_MAX.plusDays(1), TARGET,
                                    "0123456789abcdef0123456789abcdef01234567",
                                    Duration.ofSeconds(5), 0));
            assertEquals("TUSHARE_SYNTHETIC_PROVIDER_FAILURE",
                    failure.getMessage());
            assertEquals(2, failure.providerCallCount());
            assertEquals(0, failure.appendedObservations());
        }
        assertEquals(INITIAL_MAX, max(jdbc, "SSE"));
        assertEquals(INITIAL_MAX, max(jdbc, "SZSE"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM trading_calendar_facts_v1
                 WHERE calendar_date > ?
                """, Integer.class, INITIAL_MAX));
    }

    private static LocalDate max(JdbcTemplate jdbc, String exchange) {
        return jdbc.queryForObject("""
                SELECT max(calendar_date) FROM trading_calendar_facts_v1
                 WHERE exchange=?
                """, LocalDate.class, exchange);
    }
}
