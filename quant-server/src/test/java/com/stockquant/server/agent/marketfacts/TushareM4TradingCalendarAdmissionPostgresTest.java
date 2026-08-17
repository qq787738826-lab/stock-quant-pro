package com.stockquant.server.agent.marketfacts;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM4TradingCalendarAdmissionPostgresTest {
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = System.getenv("STOCK_QUANT_M5_TEST_JDBC_URL");
        String user = System.getenv("STOCK_QUANT_M5_TEST_DB_USER");
        String password = System.getenv("STOCK_QUANT_M5_TEST_DB_PASSWORD");
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
    void unknownOpenDateUsesExactlyTwoCalendarCallsAndPersistsBothExchanges() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = components(date, gateway)) {
            var result = components.m4CalendarAdmissionService().refresh(
                    date, date.plusDays(30), Duration.ofSeconds(5));

            assertTrue(result.open());
            assertEquals(2, result.providerCalls());
            assertEquals(0, result.retryCount());
            assertEquals(62, result.receivedFacts());
            assertEquals(62, result.appendedObservations());
            assertEquals(0, result.idempotentChainTailHits());
            assertEquals(2, gateway.calls());
            assertEquals(2, components.totalProviderAttemptCount());
        }
        assertCalendarOnlyPersistence(62, 2);
        var resolver = new TushareM4NextOpenSessionResolver(
                new PitMarketFactRepository(new JdbcTemplate(dataSource),
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .findAndRegisterModules()));
        assertEquals(LocalDate.of(2026, 8, 13), resolver.resolve(date,
                date.plusDays(30), Instant.from(date.atTime(16, 0)
                        .atZone(PitMarketFactsContracts.MARKET_ZONE)))
                .orElseThrow());
        Instant august13Intraday = Instant.from(LocalDate.of(2026, 8, 13)
                .atTime(10, 15)
                .atZone(PitMarketFactsContracts.MARKET_ZONE));
        assertEquals(LocalDate.of(2026, 8, 14),
                resolver.resolveAfterResearchAsOf(date, date.plusDays(30),
                        august13Intraday).orElseThrow());
        assertTrue(com.stockquant.core.research.StrategyResearchModels
                .openInstant(LocalDate.of(2026, 8, 14))
                .isAfter(august13Intraday));
        Instant august13PreOpen = Instant.from(LocalDate.of(2026, 8, 13)
                .atTime(8, 0)
                .atZone(PitMarketFactsContracts.MARKET_ZONE));
        assertEquals(LocalDate.of(2026, 8, 13),
                resolver.resolveAfterResearchAsOf(date, date.plusDays(30),
                        august13PreOpen).orElseThrow());
    }

    @Test
    void unknownClosedDateProducesCalendarEvidenceWithoutShadowFacts() {
        LocalDate date = LocalDate.of(2026, 8, 15);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = components(date, gateway)) {
            var result = components.m4CalendarAdmissionService().refresh(
                    date, date.plusDays(30), Duration.ofSeconds(5));

            assertFalse(result.open());
            assertEquals(2, result.providerCalls());
            assertEquals(0, result.retryCount());
            assertEquals(2, gateway.calls());
        }
        assertCalendarOnlyPersistence(62, 2);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertTrue(new com.stockquant.server.agent.shadowresearch
                .ShadowResearchRepository(jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()).nextCommonOpenKnown(date,
                Instant.from(date.atTime(16, 0)
                        .atZone(PitMarketFactsContracts.MARKET_ZONE))));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_research_runs", Integer.class));
    }

    @Test
    void secondExchangePersistenceFailureRollsBackBothCalendarResponses() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE FUNCTION reject_m4_szse_calendar()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.exchange='SZSE' THEN
                        RAISE EXCEPTION 'synthetic calendar rollback';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_reject_m4_szse_calendar
                BEFORE INSERT ON trading_calendar_facts_v1
                FOR EACH ROW EXECUTE FUNCTION reject_m4_szse_calendar()
                """);
        LocalDate date = LocalDate.of(2026, 8, 12);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = components(date, gateway)) {
            assertThrows(RuntimeException.class, () -> components
                    .m4CalendarAdmissionService().refresh(date,
                            date.plusDays(30), Duration.ofSeconds(5)));
            assertEquals(2, gateway.calls());
        }
        assertCalendarOnlyPersistence(0, 0);
    }

    @Test
    void secondCalendarProviderFailureRetainsConsumedCallTelemetry() {
        LocalDate date = LocalDate.of(2026, 8, 12);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway(2);
        try (var components = components(date, gateway)) {
            var failure = assertThrows(
                    TushareM4TradingCalendarAdmissionService
                            .CalendarAdmissionFailure.class,
                    () -> components.m4CalendarAdmissionService().refresh(
                            date, date.plusDays(30), Duration.ofSeconds(5)));

            assertEquals("TUSHARE_SYNTHETIC_PROVIDER_FAILURE",
                    failure.getMessage());
            assertEquals(2, failure.providerCalls());
            assertEquals(0, failure.retryCount());
            assertEquals(2, gateway.calls());
            assertEquals(2, components.totalProviderAttemptCount());
        }
        assertCalendarOnlyPersistence(0, 0);
    }

    private static TushareDedicatedResearchRuntimeComponents components(
            LocalDate date,
            TushareControlledAcceptanceE2eDryRunGateway gateway
    ) {
        Clock clock = Clock.fixed(Instant.from(date.atTime(16, 0)
                .atZone(PitMarketFactsContracts.MARKET_ZONE)), ZoneOffset.UTC);
        return TushareDedicatedResearchRuntimeComponents.createE2eDryRun(
                dataSource, clock, gateway);
    }

    private static void assertCalendarOnlyPersistence(
            int observations,
            int batches
    ) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(observations, jdbc.queryForObject("""
                SELECT count(*) FROM pit_market_fact_observations
                 WHERE fact_type='TRADING_CALENDAR'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM pit_market_fact_observations
                 WHERE fact_type IN ('RAW_DAILY_BAR','ADJUSTMENT_FACTOR')
                """, Integer.class));
        assertEquals(batches, jdbc.queryForObject(
                "SELECT count(*) FROM pit_market_fact_batches",
                Integer.class));
    }
}
