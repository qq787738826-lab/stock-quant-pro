package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.researchselection.ResearchSelectionModels;
import com.stockquant.server.researchselection.ResearchSelectionAnchorResolver;
import com.stockquant.server.researchselection.ResearchSelectionProviderBudgetPlanner;
import com.stockquant.server.researchselection.ResearchSelectionHistoricalDatasetLoader;
import com.stockquant.server.researchselection.ResearchSelectionRepository;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(18, jdbc.queryForObject("""
                SELECT max(version::integer) FROM flyway_schema_history
                 WHERE success
                """, Integer.class));
    }

    @Test
    void v18MainboardSnapshotIsImmutableAndDateWideCaptureIsComplete() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        Set<LocalDate> dates = Set.of(ANCHOR.minusDays(1), ANCHOR);
        TushareMainboardUniverseCaptureService.CaptureEvidence captured;
        try (var components = components(gateway)) {
            captured = components.mainboardUniverseCaptureService().capture(
                    null, true, dates, ANCHOR.minusDays(1), ANCHOR, true,
                    "a".repeat(40), Duration.ofSeconds(5));
        }

        var snapshot = captured.snapshot();
        assertEquals(7, captured.providerCallCount());
        assertEquals(0, captured.retryCount());
        assertEquals(4, captured.batchIds().size());
        assertEquals(3_000, snapshot.snapshot().memberCount());
        assertEquals(1_500, snapshot.snapshot().sseCount());
        assertEquals(1_500, snapshot.snapshot().szseCount());
        assertEquals(1, snapshot.snapshot().stCount());
        assertEquals(3_000, snapshot.members().stream().map(
                ResearchUniverseMainboard.Member::tsCode).distinct().count());
        assertEquals(1, count(jdbc,
                "research_universe_snapshot_observations"));
        assertTrue(captured.appendedObservations() >= 12_000);
        assertEquals(7, gateway.calls());

        var repository = new ResearchUniverseMainboardRepository(jdbc);
        var loader = new ResearchUniverseMainboardDatasetLoader(
                new PitMarketFactRepository(jdbc, mapper));
        var audit = loader.audit(snapshot, ANCHOR, AS_OF, 2);
        assertTrue(audit.missingTradeDates().isEmpty());
        assertEquals(3_000, audit.existingSecurityCount());

        var unchangedMembers = snapshot.members().stream().map(value ->
                new ResearchUniverseMainboard.Member(value.tsCode(),
                        value.symbol(), value.exchange(), value.name(),
                        value.industry(), value.market(), value.listStatus(),
                        value.listDate(), value.delistDate(),
                        AS_OF.plusSeconds(1), value.source(),
                        value.contentHash(), value.stSecurity())).toList();
        var unchanged = repository.saveIfChanged(unchangedMembers,
                AS_OF.plusSeconds(1), ANCHOR,
                snapshot.snapshot().sourceFingerprint(), "b".repeat(40));
        assertEquals(snapshot.snapshot().databaseId(),
                unchanged.snapshot().databaseId());
        assertEquals(AS_OF.plusSeconds(1),
                unchanged.snapshot().lastVerifiedAt());
        assertEquals(1, count(jdbc, "research_universe_snapshots"));
        assertEquals(2, count(jdbc,
                "research_universe_snapshot_observations"));
        assertFalse(loader.audit(unchanged, ANCHOR,
                AS_OF.plusSeconds(2), 2).refreshStockBasic());
        assertTrue(loader.audit(unchanged, ANCHOR,
                AS_OF.plus(Duration.ofDays(8)), 2).refreshStockBasic());

        var changedMembers = new ArrayList<>(snapshot.members());
        var first = changedMembers.get(0);
        changedMembers.set(0, new ResearchUniverseMainboard.Member(
                first.tsCode(), first.symbol(), first.exchange(),
                first.name() + "新", first.industry(), first.market(),
                first.listStatus(), first.listDate(), first.delistDate(),
                AS_OF.plusSeconds(2), first.source(), "c".repeat(64),
                first.stSecurity()));
        for (int index = 1; index < changedMembers.size(); index++) {
            var value = changedMembers.get(index);
            changedMembers.set(index, new ResearchUniverseMainboard.Member(
                    value.tsCode(), value.symbol(), value.exchange(),
                    value.name(), value.industry(), value.market(),
                    value.listStatus(), value.listDate(), value.delistDate(),
                    AS_OF.plusSeconds(2), value.source(), value.contentHash(),
                    value.stSecurity()));
        }
        var changed = repository.saveIfChanged(changedMembers,
                AS_OF.plusSeconds(2), ANCHOR, "d".repeat(64),
                "b".repeat(40));
        assertNotEquals(snapshot.snapshot().databaseId(),
                changed.snapshot().databaseId());
        assertEquals(2, count(jdbc, "research_universe_snapshots"));
        assertEquals(3, count(jdbc,
                "research_universe_snapshot_observations"));
        assertEquals(first.name(), repository.find(
                snapshot.snapshot().databaseId()).orElseThrow().members()
                .get(0).name());

        var revertedMembers = snapshot.members().stream().map(value ->
                new ResearchUniverseMainboard.Member(value.tsCode(),
                        value.symbol(), value.exchange(), value.name(),
                        value.industry(), value.market(), value.listStatus(),
                        value.listDate(), value.delistDate(),
                        AS_OF.plusSeconds(3), value.source(),
                        value.contentHash(), value.stSecurity())).toList();
        var reverted = repository.saveIfChanged(revertedMembers,
                AS_OF.plusSeconds(3), ANCHOR,
                snapshot.snapshot().sourceFingerprint(), "e".repeat(40));
        assertNotEquals(snapshot.snapshot().databaseId(),
                reverted.snapshot().databaseId());
        assertNotEquals(changed.snapshot().databaseId(),
                reverted.snapshot().databaseId());
        assertEquals(snapshot.snapshot().memberFingerprint(),
                reverted.snapshot().memberFingerprint());
        assertEquals(reverted.snapshot().databaseId(), repository.latest()
                .orElseThrow().snapshot().databaseId());
        assertEquals(3, count(jdbc, "research_universe_snapshots"));
        assertEquals(4, count(jdbc,
                "research_universe_snapshot_observations"));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                UPDATE research_universe_members SET name='禁止改写'
                 WHERE snapshot_db_id=? AND ts_code=?
                """, snapshot.snapshot().databaseId(), first.tsCode()));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                UPDATE research_universe_snapshot_observations
                   SET observed_at=clock_timestamp()
                 WHERE snapshot_db_id=?
                """, snapshot.snapshot().databaseId()));
    }

    @Test
    void snapshotFactsUseBoundedForwardCursorAndPreserveRowOrder() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Set<LocalDate> dates = Set.of(ANCHOR.minusDays(1), ANCHOR);
        ResearchUniverseMainboard.SnapshotBundle snapshot;
        try (var components = components(
                new TushareControlledAcceptanceE2eDryRunGateway())) {
            snapshot = components.mainboardUniverseCaptureService().capture(
                    null, true, dates, ANCHOR.minusDays(1), ANCHOR, true,
                    "a".repeat(40), Duration.ofSeconds(5)).snapshot();
        }
        var facts = new PitMarketFactRepository(jdbc, mapper);
        List<String> batch = snapshot.members().subList(0, 64).stream()
                .map(ResearchUniverseMainboard.Member::tsCode).toList();
        List<String> dailyOrder = new ArrayList<>();
        List<String> factorOrder = new ArrayList<>();

        facts.streamRawBarsForSnapshotMembersAsOf(
                snapshot.snapshot().databaseId(), batch,
                ANCHOR.minusDays(1), ANCHOR, AS_OF, 17,
                value -> dailyOrder.add(value.exchange() + '|'
                        + value.symbol() + '|' + value.tradeDate()));
        facts.streamFactorsForSnapshotMembersAsOf(
                snapshot.snapshot().databaseId(), batch,
                ANCHOR.minusDays(1), ANCHOR, AS_OF, 19,
                value -> factorOrder.add(value.symbol() + '|'
                        + value.factorEffectiveTradeDate()));

        assertEquals(128, dailyOrder.size());
        assertEquals(128, factorOrder.size());
        assertEquals(dailyOrder.stream().sorted().toList(), dailyOrder);
        assertEquals(factorOrder.stream().sorted().toList(), factorOrder);
        assertEquals(128, dailyOrder.stream().distinct().count());
        assertEquals(128, factorOrder.stream().distinct().count());
        assertThrows(IllegalArgumentException.class, () ->
                facts.streamRawBarsForSnapshotMembersAsOf(
                        snapshot.snapshot().databaseId(),
                        snapshot.members().subList(0, 101).stream().map(
                                ResearchUniverseMainboard.Member::tsCode)
                                .toList(), ANCHOR.minusDays(1), ANCHOR,
                        AS_OF, 17, ignored -> {
                        }));
    }

    @Test
    void boundedNetworkRecoveryPersistsOnlyCompleteDatesAndRestartResumesGap() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 8, 10),
                LocalDate.of(2026, 8, 11),
                LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 13));
        ResearchUniverseMainboard.SnapshotBundle snapshot;
        try (var initial = components(
                new TushareControlledAcceptanceE2eDryRunGateway())) {
            snapshot = initial.mainboardUniverseCaptureService().capture(
                    null, true, Set.of(dates.get(0)), dates.get(0),
                    dates.get(3), true, "a".repeat(40),
                    Duration.ofSeconds(5)).snapshot();
        }

        var intermittent = new IntermittentNetworkGateway(
                new TushareControlledAcceptanceE2eDryRunGateway());
        try (var interrupted = components(intermittent)) {
            var failure = assertThrows(
                    TushareMainboardUniverseCaptureService.CaptureFailure.class,
                    () -> interrupted.mainboardUniverseCaptureService()
                            .capture(snapshot, false,
                                    Set.copyOf(dates.subList(1, 4)),
                                    dates.get(0), dates.get(3), false,
                                    "b".repeat(40), Duration.ofSeconds(5),
                                    4));
            assertEquals("TUSHARE_NETWORK_ERROR", failure.getMessage());
            assertEquals(9, failure.providerCallCount());
            assertEquals(4, failure.retryCount());
            assertEquals(9, intermittent.attempts);
        }

        var loader = new ResearchUniverseMainboardDatasetLoader(
                new PitMarketFactRepository(jdbc, mapper));
        var gap = loader.audit(snapshot, dates.get(3), AS_OF, 4);
        assertEquals(List.of(dates.get(3)), gap.missingTradeDates());
        int observationsBeforeResume = count(jdbc,
                "pit_market_fact_observations");

        try (var resumed = components(
                new TushareControlledAcceptanceE2eDryRunGateway())) {
            var evidence = resumed.mainboardUniverseCaptureService().capture(
                    snapshot, false, Set.copyOf(gap.missingTradeDates()),
                    dates.get(0), dates.get(3), false, "c".repeat(40),
                    Duration.ofSeconds(5), 4);
            assertEquals(2, evidence.providerCallCount());
            assertEquals(0, evidence.retryCount());
            assertEquals(1, evidence.batchIds().size());
        }
        assertTrue(count(jdbc, "pit_market_fact_observations")
                > observationsBeforeResume);
        assertTrue(loader.audit(snapshot, dates.get(3), AS_OF, 4)
                .missingTradeDates().isEmpty());
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

    @Test
    void dataOnlyMainboardIncrementWritesOneCompleteDateAndThenNoOps() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var setupGateway = new TushareControlledAcceptanceE2eDryRunGateway();
        ResearchUniverseMainboard.SnapshotBundle snapshot;
        try (var setup = components(setupGateway)) {
            snapshot = setup.mainboardUniverseCaptureService().capture(
                    null, true, Set.of(), ANCHOR, ANCHOR, true,
                    "a".repeat(40), Duration.ofSeconds(5)).snapshot();
        }
        assertEquals(3, setupGateway.calls());
        int selectionBefore = count(jdbc, "research_selection_runs");
        int shadowBefore = count(jdbc, "shadow_research_runs");
        int paperBefore = count(jdbc, "shadow_paper_orders");
        int evaluationBefore = count(jdbc, "agent_evaluation_reports");

        var incrementGateway = new TushareControlledAcceptanceE2eDryRunGateway();
        MainboardDailyIncrementService.Outcome first;
        try (var components = components(incrementGateway)) {
            var progress = new MainboardDailyIncrementService.Progress();
            first = new MainboardDailyIncrementService(jdbc, mapper,
                    components.mainboardUniverseCaptureService(),
                    Clock.fixed(AS_OF, ZoneOffset.UTC))
                    .execute(ANCHOR, "b".repeat(40), progress);
        }
        assertEquals(2, first.providerCalls());
        assertEquals(0, first.retryCount());
        assertEquals(1, first.batchIds().size());
        assertEquals(3_000, first.dailyAdded());
        assertEquals(3_000, first.factorAdded());
        assertEquals(6_000, first.appended());
        assertEquals(ANCHOR, first.latestCompleteDate());
        assertTrue(first.validation().coverageComplete());
        assertTrue(first.validation().knownAtValid());
        assertEquals(2, incrementGateway.calls());

        var repeatGateway = new TushareControlledAcceptanceE2eDryRunGateway();
        MainboardDailyIncrementService.Outcome repeat;
        try (var components = components(repeatGateway,
                AS_OF.plusSeconds(1))) {
            repeat = new MainboardDailyIncrementService(jdbc, mapper,
                    components.mainboardUniverseCaptureService(),
                    Clock.fixed(AS_OF.plusSeconds(1), ZoneOffset.UTC))
                    .execute(ANCHOR, "c".repeat(40),
                            new MainboardDailyIncrementService.Progress());
        }
        assertEquals(0, repeat.providerCalls());
        assertEquals(0, repeat.dailyAdded());
        assertEquals(0, repeat.factorAdded());
        assertEquals(0, repeatGateway.calls());
        assertEquals(snapshot.snapshot().databaseId(),
                repeat.snapshot().snapshot().databaseId());
        assertEquals(selectionBefore, count(jdbc, "research_selection_runs"));
        assertEquals(shadowBefore, count(jdbc, "shadow_research_runs"));
        assertEquals(paperBefore, count(jdbc, "shadow_paper_orders"));
        assertEquals(evaluationBefore,
                count(jdbc, "agent_evaluation_reports"));
    }

    @Test
    void dataOnlyMainboardIncrementRollsBackDailyWhenFactorPersistenceFails() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (var setup = components(
                new TushareControlledAcceptanceE2eDryRunGateway())) {
            setup.mainboardUniverseCaptureService().capture(null, true,
                    Set.of(), ANCHOR, ANCHOR, true, "a".repeat(40),
                    Duration.ofSeconds(5));
        }
        int batchesBefore = count(jdbc, "pit_market_fact_batches");
        jdbc.execute("""
                CREATE FUNCTION reject_increment_factor()
                RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.symbol='600019' THEN
                        RAISE EXCEPTION 'synthetic increment rollback';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_reject_increment_factor
                BEFORE INSERT ON adjustment_factor_facts_v1
                FOR EACH ROW EXECUTE FUNCTION reject_increment_factor()
                """);

        var gateway = new TushareControlledAcceptanceE2eDryRunGateway();
        var progress = new MainboardDailyIncrementService.Progress();
        try (var components = components(gateway)) {
            var service = new MainboardDailyIncrementService(jdbc, mapper,
                    components.mainboardUniverseCaptureService(),
                    Clock.fixed(AS_OF, ZoneOffset.UTC));
            assertThrows(TushareMainboardUniverseCaptureService
                    .CaptureFailure.class, () -> service.execute(ANCHOR,
                    "b".repeat(40), progress));
        }
        assertEquals(2, progress.providerCalls);
        assertEquals(0, progress.retryCount);
        assertEquals(2, gateway.calls());
        assertEquals(batchesBefore, count(jdbc, "pit_market_fact_batches"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM raw_daily_bar_facts_v2
                 WHERE trade_date=?
                """, Integer.class, ANCHOR));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM adjustment_factor_facts_v1
                 WHERE factor_effective_trade_date=?
                """, Integer.class, ANCHOR));
        assertEquals(0, count(jdbc, "research_selection_runs"));
        assertEquals(0, count(jdbc, "shadow_research_runs"));
    }

    private static TushareDedicatedResearchRuntimeComponents components(
            TushareApiGateway gateway
    ) {
        return components(gateway, AS_OF);
    }

    private static TushareDedicatedResearchRuntimeComponents components(
            TushareApiGateway gateway,
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

    /** Simulates a transport that loses the first response per endpoint/date. */
    private static final class IntermittentNetworkGateway
            implements TushareApiGateway {
        private final TushareApiGateway delegate;
        private final Set<String> failedOnce = new HashSet<>();
        private int attempts;

        private IntermittentNetworkGateway(TushareApiGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public QueryResult query(
                String endpoint,
                com.fasterxml.jackson.databind.node.ObjectNode parameters,
                List<String> fields,
                Duration timeout,
                QueryMode mode,
                TushareManualBoundedSession session
        ) {
            String key = endpoint + '|' + parameters.path("trade_date")
                    .asText("NO_DATE");
            if (!failedOnce.add(key)) {
                QueryResult result = delegate.query(endpoint, parameters,
                        fields, timeout, mode, session);
                attempts += result.providerCallCount();
                return result;
            }
            session.authorizeAndReserve(endpoint, parameters);
            attempts++;
            if (!session.reserveNetworkRecovery()) {
                throw new GatewayException(ErrorKind.NETWORK_ERROR,
                        "TUSHARE_NETWORK_ERROR",
                        "synthetic no-response network failure",
                        1, 0, null);
            }
            QueryResult recovered = delegate.query(endpoint, parameters,
                    fields, timeout, mode, session);
            attempts += recovered.providerCallCount();
            return new QueryResult(recovered.table(),
                    recovered.providerCallCount() + 1,
                    recovered.rateLimitRetryCount() + 1);
        }
    }
}
