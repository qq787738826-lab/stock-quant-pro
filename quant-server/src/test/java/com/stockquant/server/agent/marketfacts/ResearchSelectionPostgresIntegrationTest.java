package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.researchselection.ResearchSelectionModels;
import com.stockquant.server.researchselection.ResearchSelectionAnchorResolver;
import com.stockquant.server.researchselection.ResearchSelectionProviderBudgetPlanner;
import com.stockquant.server.researchselection.ResearchSelectionHistoricalDatasetLoader;
import com.stockquant.server.researchselection.ResearchSelectionRepository;
import com.stockquant.server.researchselection.ResearchUniverseV1;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionPostgresIntegrationTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 13);
    private static final Instant AS_OF = Instant.from(ANCHOR.atTime(16, 0)
            .atZone(PitMarketFactsContracts.MARKET_ZONE));
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = System.getenv("STOCK_QUANT_SELECTION_TEST_JDBC_URL");
        String user = System.getenv("STOCK_QUANT_SELECTION_TEST_DB_USER");
        String password = System.getenv(
                "STOCK_QUANT_SELECTION_TEST_DB_PASSWORD");
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
    void v17UniverseCaptureIncrementAndIdempotentTailAreComplete() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var facts = new PitMarketFactRepository(jdbc, mapper);
        var loader = new TushareResearchUniverseDatasetLoader(facts);
        var request = ResearchSelectionModels.SelectionRequest.immediate();

        assertEquals(52, ResearchSelectionProviderBudgetPlanner
                .requiredProviderRequests(loader, request, AS_OF));

        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        int observationCount;
        try (var components = components(gateway)) {
            var initial = components.researchUniverseCaptureService().capture(
                    ResearchUniverseV1.securities(),
                    LocalDate.of(2026, 5, 1), ANCHOR.minusDays(1),
                    Duration.ofSeconds(5));
            assertEquals(52, initial.providerCallCount());
            assertEquals(0, initial.retryCount());
            assertTrue(initial.appendedObservations() > 3_000);
            assertEquals(0, initial.idempotentChainTailHits());
            assertEquals(ANCHOR.minusDays(1),
                    ResearchSelectionAnchorResolver.resolve(loader,
                            request.auxiliaryWindow(), AS_OF));
            assertEquals(0, ResearchSelectionProviderBudgetPlanner
                    .requiredProviderRequests(loader, request, AS_OF));
            assertTrue(facts.findCalendarAsOf(
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.calendarSourceIdentity("SSE"),
                    "SSE", ANCHOR.plusDays(1), ANCHOR.plusDays(30), AS_OF)
                    .stream().anyMatch(value -> value.open()
                            && value.calendarDate().isAfter(ANCHOR)));

            var increment = components.researchUniverseCaptureService()
                    .captureDailyIncrement(ResearchUniverseV1.securities(),
                            ANCHOR, Duration.ofSeconds(5));
            assertEquals(2, increment.providerCallCount());
            assertEquals(50, increment.appendedObservations());
            assertEquals(0, increment.idempotentChainTailHits());
            observationCount = count(jdbc,
                    "pit_market_fact_observations");
        }
        var repeatGateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = components(repeatGateway,
                AS_OF.plusSeconds(1))) {
            var repeat = components.researchUniverseCaptureService()
                    .captureDailyIncrement(ResearchUniverseV1.securities(),
                            ANCHOR, Duration.ofSeconds(5));
            assertEquals(2, repeat.providerCallCount());
            assertEquals(0, repeat.appendedObservations());
            assertEquals(50, repeat.idempotentChainTailHits());
            assertEquals(observationCount, count(jdbc,
                    "pit_market_fact_observations"));
            assertEquals(2, repeatGateway.calls());
        }
        assertEquals(54, gateway.calls());

        var loaded = loader.load(ResearchUniverseV1.securities(), 60,
                ANCHOR, AS_OF);
        assertEquals(25, loaded.dataset().securities().size());
        assertEquals(60, loaded.dataset().sessions().size());
        assertEquals(1_500, loaded.dataset().bars().size());
        assertTrue(loaded.coverage().typedFactReadback());
        assertTrue(loaded.coverage().systemKnowledgeReadback());
        assertTrue(loaded.coverage().formulaOnlyQfq());
        assertTrue(loaded.coverage().noFutureDataLeakage());
        var historical = new ResearchSelectionHistoricalDatasetLoader()
                .expand(loader, loaded, ANCHOR, AS_OF);
        assertTrue(historical.loaded().dataset().sessions().size() >= 60);
        assertTrue(historical.loaded().dataset().sessions().size() < 120);
        assertEquals(ResearchSelectionModels.HistoricalAvailability.AVAILABLE,
                historical.windowCoverage().get(1).status());
        assertEquals(ResearchSelectionModels.HistoricalAvailability
                        .INSUFFICIENT_HISTORY,
                historical.windowCoverage().get(2).status());
        assertEquals(0, ResearchSelectionProviderBudgetPlanner
                .requiredProviderRequests(loader, request, AS_OF));
        Instant nextSessionAfterClose = Instant.from(
                ANCHOR.plusDays(1).atTime(16, 0)
                        .atZone(PitMarketFactsContracts.MARKET_ZONE));
        assertEquals(ANCHOR, ResearchSelectionAnchorResolver.resolve(loader,
                request.auxiliaryWindow(), nextSessionAfterClose));
        assertEquals(0, ResearchSelectionProviderBudgetPlanner
                .requiredProviderRequests(loader, request,
                        nextSessionAfterClose));
        var shadow = new ShadowResearchRepository(jdbc, mapper);
        assertTrue(new ResearchSelectionRepository(jdbc, mapper)
                .liveShadowSampleCounts().isEmpty());
        assertEquals(ShadowResearchRepository.CalendarState.OPEN,
                shadow.researchCalendarState(ANCHOR, AS_OF));
        assertEquals(17, jdbc.queryForObject("""
                SELECT max(version::integer) FROM flyway_schema_history
                 WHERE success
                """, Integer.class));
    }

    @Test
    void twentySevenResponseCaptureRollsBackAsOneTransaction() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE FUNCTION reject_universe_bar()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.symbol='600019' THEN
                        RAISE EXCEPTION 'synthetic universe rollback';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_reject_universe_bar
                BEFORE INSERT ON raw_daily_bar_facts_v2
                FOR EACH ROW EXECUTE FUNCTION reject_universe_bar()
                """);
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        try (var components = components(gateway)) {
            var failure = assertThrows(
                    TushareResearchUniverseCaptureService.CaptureFailure.class,
                    () -> components.researchUniverseCaptureService().capture(
                            ResearchUniverseV1.securities(),
                            LocalDate.of(2026, 5, 1), ANCHOR,
                            Duration.ofSeconds(5)));
            assertEquals(52, failure.providerCallCount());
            assertEquals(52, gateway.calls());
        }
        assertEquals(0, count(jdbc, "pit_market_fact_batches"));
        assertEquals(0, count(jdbc, "pit_market_fact_observations"));
    }

    @Test
    void selectionStateMachineAdvancesAsOfAndFreezesTerminalHistory() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = new ResearchSelectionRepository(jdbc,
                new ObjectMapper().findAndRegisterModules());
        var request = ResearchSelectionModels.SelectionRequest.immediate();
        var created = repository.create(
                "SELECT_20260813T100000Z_A1B2C3D4E5F6", request, AS_OF,
                "a".repeat(40));

        assertEquals(ResearchSelectionModels.Status.QUEUED,
                created.status());
        String brokerRequest =
                "SQHB_20260813T100000Z_A1B2C3D4E5F6";
        repository.bindBrokerRequest(created.runId(), brokerRequest);
        assertEquals(brokerRequest, repository.queuedBrokerRuns(10).get(0)
                .brokerRequestId());
        assertThrows(IllegalStateException.class, () ->
                repository.bindBrokerRequest(created.runId(),
                        "SQHB_20260813T100001Z_B1C2D3E4F5A6"));
        assertThrows(IllegalStateException.class, () -> repository.create(
                "SELECT_20260813T100001Z_B1C2D3E4F5A6", request, AS_OF,
                "a".repeat(40)));
        var scheduledRequest = new ResearchSelectionModels.SelectionRequest(
                ResearchSelectionModels.TriggerMode.SCHEDULED_SHADOW,
                20, 60, 10, 5, true);
        var scheduled = repository.create(
                "SELECT_20260813T100001Z_D1E2F3A4B5C6", scheduledRequest,
                AS_OF, "a".repeat(40));
        assertEquals(ResearchSelectionModels.Status.QUEUED,
                scheduled.status());
        repository.fail(scheduled.runId(),
                ResearchSelectionModels.Status.QUEUED, "SCHEDULER",
                "M4_SCHEDULER_TEST_TERMINAL", AS_OF);
        Instant advanced = AS_OF.plusSeconds(30);
        assertEquals(advanced, repository.advanceResearchAsOf(
                created.runId(), AS_OF, advanced).researchAsOf());
        repository.transition(created.runId(),
                ResearchSelectionModels.Status.QUEUED,
                ResearchSelectionModels.Status.PREPARING_DATA);
        repository.fail(created.runId(),
                ResearchSelectionModels.Status.PREPARING_DATA,
                "DATA", "RESEARCH_UNIVERSE_DATA_INCOMPLETE", advanced);
        assertEquals(0, repository.queuedBrokerRuns(10).size());
        assertEquals(ResearchSelectionModels.Status.FAILED,
                repository.summary(created.runId()).orElseThrow().status());
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                UPDATE research_selection_runs SET failure_reason='OTHER'
                 WHERE id=?
                """, created.runId()));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "DELETE FROM research_selection_runs WHERE id=?",
                created.runId()));

        var second = repository.create(
                "SELECT_20260813T100002Z_C1D2E3F4A5B6", request, advanced,
                "a".repeat(40));
        assertEquals(ResearchSelectionModels.Status.QUEUED, second.status());
        assertEquals(3, count(jdbc, "research_selection_runs"));
    }

    private static TushareDedicatedResearchRuntimeComponents components(
            TushareControlledAcceptanceE2eDryRunGateway gateway
    ) {
        return components(gateway, AS_OF);
    }

    private static TushareDedicatedResearchRuntimeComponents components(
            TushareControlledAcceptanceE2eDryRunGateway gateway,
            Instant clock
    ) {
        return TushareDedicatedResearchRuntimeComponents.createE2eDryRun(
                dataSource, Clock.fixed(clock, ZoneOffset.UTC), gateway);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
