package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.temporal.MarketDataDatasetVersionRepository;
import com.stockquant.server.agent.temporal.SecurityStatusEventRepository;
import com.stockquant.server.agent.temporal.SecurityStatusHistoryRepository;
import com.stockquant.server.agent.temporal.SecurityStatusStateHasher;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import com.stockquant.server.agent.temporal.TradingCalendarRevisionRepository;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardCatchupReadonlyAuditPostgresTest {
    private static final LocalDate CALENDAR_MAX = LocalDate.of(2026, 9, 21);
    private static final LocalDate LATEST_COMPLETE =
            LocalDate.of(2026, 8, 27);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-21T08:30:00Z"), ZoneOffset.UTC);
    private static final String COMMIT =
            "0123456789abcdef0123456789abcdef01234567";
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = System.getenv("STOCK_QUANT_CATCHUP_AUDIT_TEST_JDBC_URL");
        String user = System.getenv("STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_USER");
        String password = System.getenv(
                "STOCK_QUANT_CATCHUP_AUDIT_TEST_DB_PASSWORD");
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
        Integer version = new JdbcTemplate(dataSource).queryForObject("""
                SELECT max(version::integer)
                  FROM tushare_research.flyway_schema_history WHERE success
                """, Integer.class);
        assertEquals(18, version);
    }

    @Test
    void auditsCompletePartialAndMissingWithoutProviderCallsOrWrites() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = TushareDedicatedResearchRuntimeComponents
                .createE2eDryRun(dataSource, CLOCK, gateway)) {
            components.mainboardUniverseCaptureService().capture(null, true,
                    Set.of(), CALENDAR_MAX.minusDays(499), CALENDAR_MAX, true,
                    COMMIT, Duration.ofMinutes(2), 0);
            var snapshot = new ResearchUniverseMainboardRepository(jdbc)
                    .latest().orElseThrow();
            var loader = new ResearchUniverseMainboardDatasetLoader(
                    new PitMarketFactRepository(jdbc,
                            new ObjectMapper().findAndRegisterModules()));
            List<LocalDate> throughBaseline = loader.commonOpenDatesThrough(
                    LATEST_COMPLETE, CLOCK.instant());
            List<LocalDate> baseline = throughBaseline.subList(
                    throughBaseline.size() - 60, throughBaseline.size());
            components.mainboardUniverseCaptureService().capture(snapshot,
                    false, Set.of(baseline.get(0), baseline.get(1),
                            LATEST_COMPLETE), baseline.get(0),
                    LATEST_COMPLETE, false, COMMIT, Duration.ofMinutes(5), 0);
            components.mainboardUniverseCaptureService().capture(snapshot,
                    false, Set.of(LocalDate.of(2026, 8, 31)),
                    LocalDate.of(2026, 8, 31),
                    LocalDate.of(2026, 8, 31), false, COMMIT,
                    Duration.ofMinutes(2), 0);
            captureOneSidedFacts(snapshot, gateway);
        }

        long rowsBefore = factRows(jdbc);
        int callsBefore = gateway.calls();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var outcome = transaction.execute(status ->
                new MainboardCatchupReadonlyAuditService(jdbc,
                        new ObjectMapper().findAndRegisterModules(), CLOCK)
                        .execute(377, 450));

        assertNotNull(outcome);
        assertEquals(CALENDAR_MAX, outcome.calendarMaxSse());
        assertEquals(CALENDAR_MAX, outcome.calendarMaxSzse());
        assertEquals(CALENDAR_MAX,
                outcome.latestCommonCompletedOpenTradeDate());
        assertEquals(LATEST_COMPLETE, outcome.latestCompleteTradeDate());
        assertEquals(MainboardDailyFactIntegrity.Status.MISSING,
                status(outcome, LocalDate.of(2026, 8, 28)));
        assertEquals(MainboardDailyFactIntegrity.Status.COMPLETE,
                status(outcome, LocalDate.of(2026, 8, 31)));
        assertEquals(MainboardDailyFactIntegrity.Status.PARTIAL,
                status(outcome, LocalDate.of(2026, 9, 1)));
        assertEquals(MainboardDailyFactIntegrity.Status.PARTIAL,
                status(outcome, LocalDate.of(2026, 9, 2)));
        assertEquals(14, outcome.missingTradeDates().size());
        assertEquals(2, outcome.partialTradeDates().size());
        assertEquals(List.of(LocalDate.of(2026, 8, 31)),
                outcome.completeTradeDates());
        assertEquals(28, outcome.plannedBaseCalls());
        assertEquals(0, outcome.networkRecoveryBudget());
        assertEquals(405, outcome.projectedWorstCaseLedger());
        assertTrue(outcome.projectedWithinLimit());
        assertTrue(outcome.readOnlyTransaction());
        assertEquals(callsBefore, gateway.calls());
        assertEquals(rowsBefore, factRows(jdbc));
    }

    private static void captureOneSidedFacts(
            com.stockquant.server.researchselection.ResearchUniverseMainboard
                    .SnapshotBundle snapshot,
            TushareControlledAcceptanceE2eDryRunGateway gateway
    ) {
        ObjectMapper mapper = new ObjectMapper().registerModule(
                new JavaTimeModule());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(dataSource);
        var dedicatedGuard = new TushareDedicatedResearchPersistenceGuard(
                jdbc, TushareDedicatedResearchPersistenceGuard
                .DATABASE_PURPOSE);
        var reducedGuard = new TushareReducedResearchPersistenceGuard(jdbc,
                TushareReducedResearchPersistenceGuard.DATABASE_PURPOSE);
        var capture = new PitMarketFactCaptureService(mapper,
                new PitMarketFactsCanonicalService(mapper,
                        new BacktestCanonicalHashService(mapper)),
                new PitMarketFactRepository(jdbc, mapper),
                new TemporalMarketFoundationService(
                        new MarketDataDatasetVersionRepository(jdbc, mapper),
                        new SecurityStatusEventRepository(jdbc, mapper),
                        new SecurityStatusHistoryRepository(jdbc),
                        new TradingCalendarRevisionRepository(jdbc),
                        new SecurityStatusStateHasher(), CLOCK),
                reducedGuard, dedicatedGuard, CLOCK, transactions);
        var properties = new TushareMarketFactProperties();
        properties.setMode(TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setMaximumRateLimitRetries(0);
        properties.setToken("MAINBOARD_CATCHUP_AUDIT_E2E_TOKEN");
        try {
            var provider = new TushareMarketFactProvider(mapper, properties,
                    gateway);
            var members = snapshot.members().stream().map(value ->
                    new TushareReferenceDataModels.MainboardInstrument(
                            value.tsCode(), value.symbol(), value.exchange(),
                            value.name(), value.industry(), value.market(),
                            value.listStatus(), value.listDate(),
                            value.delistDate(), value.contentHash())).toList();
            LocalDate dailyOnly = LocalDate.of(2026, 9, 1);
            LocalDate factorOnly = LocalDate.of(2026, 9, 2);
            var session = TushareManualBoundedSession.mainboardUniverse(
                    Set.of(dailyOnly, factorOnly), dailyOnly, factorOnly,
                    false, false, 0);
            MarketFactResponse daily = provider.fetchMainboardMarketDate(
                    members, dailyOnly, Duration.ofMinutes(2), session);
            MarketFactResponse factor = provider.fetchMainboardMarketDate(
                    members, factorOnly, Duration.ofMinutes(2), session);
            TransactionTemplate transaction = new TransactionTemplate(
                    transactions);
            transaction.executeWithoutResult(status -> capture
                    .captureAuthorizedLimitedPersonalFormal(
                            oneSided(daily, true), CLOCK.instant(),
                            LimitedPersonalFormalCaptureAuthorization
                                    .tushareF1A()));
            transaction.executeWithoutResult(status -> capture
                    .captureAuthorizedLimitedPersonalFormal(
                            oneSided(factor, false), CLOCK.instant(),
                            LimitedPersonalFormalCaptureAuthorization
                                    .tushareF1A()));
        } finally {
            properties.clearToken();
        }
    }

    private static MarketFactResponse oneSided(
            MarketFactResponse value,
            boolean daily
    ) {
        return new MarketFactResponse(value.providerContractVersion(),
                value.providerCode(), value.adapterVersion(),
                value.runNamespace(), value.sourceCode(),
                value.sourceInstrumentId(), value.requestedStart(),
                value.requestedEnd(), value.complete(), value.capability(),
                daily ? value.rawDailyBars() : List.of(),
                daily ? List.of() : value.adjustmentFactors(),
                List.of(), List.of(), value.errors(),
                value.providerMetadata());
    }

    private static MainboardDailyFactIntegrity.Status status(
            MainboardCatchupReadonlyAuditService.Outcome outcome,
            LocalDate date
    ) {
        return outcome.dateAudits().stream()
                .filter(value -> value.tradeDate().equals(date))
                .findFirst().orElseThrow().status();
    }

    private static long factRows(JdbcTemplate jdbc) {
        Long value = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM pit_market_fact_batches)
                     + (SELECT count(*) FROM pit_market_fact_observations)
                     + (SELECT count(*) FROM trading_calendar_facts_v1)
                     + (SELECT count(*) FROM raw_daily_bar_facts_v2)
                     + (SELECT count(*) FROM adjustment_factor_facts_v1)
                     + (SELECT count(*) FROM research_universe_snapshots)
                     + (SELECT count(*) FROM research_universe_members)
                """, Long.class);
        return value == null ? 0L : value;
    }
}
