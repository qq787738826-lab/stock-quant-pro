package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Seeds the isolated V18 database used by the packaged composite Fake E2E. */
class AutomaticMarketDataMaintenancePostgresFixtureTest {
    private static final LocalDate BASELINE = LocalDate.of(2026, 8, 27);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-31T08:30:00Z"), ZoneOffset.UTC);
    private static DriverManagerDataSource dataSource;
    private static String gitCommit;

    @BeforeAll
    static void database() {
        String url = System.getenv(
                "STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_JDBC_URL");
        String user = System.getenv(
                "STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_USER");
        String password = System.getenv(
                "STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_DB_PASSWORD");
        gitCommit = System.getenv(
                "STOCK_QUANT_AUTOMATIC_MAINTENANCE_TEST_GIT_COMMIT");
        if (url == null || user == null || password == null
                || gitCommit == null) return;
        dataSource = new DriverManagerDataSource(url, user, password);
    }

    @Test
    void seedCompleteBaselineWithoutBusinessRuns() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        assertTrue(gitCommit.matches("[0-9a-f]{40}"));
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .cleanDisabled(false).locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertEquals(18, jdbc.queryForObject("""
                SELECT max(version::integer)
                  FROM tushare_research.flyway_schema_history WHERE success
                """, Integer.class));

        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = TushareDedicatedResearchRuntimeComponents
                .createE2eDryRun(dataSource, CLOCK, gateway)) {
            Set<LocalDate> stableWindow = stableWeekdayWindow(BASELINE, 60);
            components.mainboardUniverseCaptureService().capture(null, true,
                    stableWindow, BASELINE.minusDays(499), BASELINE, true,
                    gitCommit, Duration.ofMinutes(2), 0);
        }

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var outcome = transaction.execute(ignored ->
                new MainboardCatchupReadonlyAuditService(jdbc,
                        new ObjectMapper().findAndRegisterModules(), CLOCK)
                        .execute(0, 450));
        assertEquals(BASELINE, outcome.calendarMaxSse());
        assertEquals(BASELINE, outcome.calendarMaxSzse());
        assertEquals(BASELINE, outcome.latestCompleteTradeDate());
        assertEquals(0, outcome.missingTradeDates().size());
        assertEquals(0, outcome.partialTradeDates().size());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM research_selection_runs",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_research_runs", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_paper_orders", Integer.class));
    }

    private static Set<LocalDate> stableWeekdayWindow(
            LocalDate end,
            int count
    ) {
        LinkedHashSet<LocalDate> result = new LinkedHashSet<>();
        for (LocalDate date = end; result.size() < count;
                date = date.minusDays(1)) {
            switch (date.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> { }
                default -> result.add(date);
            }
        }
        return Set.copyOf(result);
    }
}
