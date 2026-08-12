package com.stockquant.server.agent.shadowresearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.server.agent.research.DeterministicFakeModelAdapter;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRequest;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.TriggerMode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShadowResearchRuntimePostgresTest {
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void database() {
        String url = System.getenv("STOCK_QUANT_M4_TEST_JDBC_URL");
        String user = System.getenv("STOCK_QUANT_M4_TEST_DB_USER");
        String password = System.getenv("STOCK_QUANT_M4_TEST_DB_PASSWORD");
        if (url == null || user == null || password == null) {
            return;
        }
        dataSource = new DriverManagerDataSource(url, user, password);
        Flyway.configure().dataSource(dataSource).cleanDisabled(false)
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void isolate() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        // This database is unique to the owning test process. Rebuild it
        // between cases instead of disabling production immutability guards
        // or relying on TRUNCATE, which those guards deliberately reject.
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .cleanDisabled(false).locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void freezesSevenAgentReportAndIsIdempotent() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // The test database is created for this test run and discarded by the
        // owning harness; production immutable facts are never cleaned.
        ShadowResearchDatasetSource source = ShadowResearchTestFixtures.source();
        var repository = new ShadowResearchRepository(jdbc,
                new ObjectMapper().findAndRegisterModules());
        var tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        Clock clock = Clock.fixed(Instant.parse("2025-09-11T08:31:00Z"),
                ZoneOffset.UTC);
        var paper = new ShadowPaperPortfolioService(repository, tx);
        var runtime = new ShadowResearchRuntime(repository, source, paper,
                tx, clock);
        var request = request();

        var first = runtime.run(request,
                new DeterministicFakeModelAdapter());
        var second = runtime.run(request,
                new DeterministicFakeModelAdapter());

        assertEquals("FROZEN", first.run().status().name());
        assertEquals(first.run().id(), second.run().id());
        assertTrue(second.idempotent());
        assertEquals(7, first.snapshot().report().agentRuns().stream()
                .map(value -> value.agentRole()).distinct().count());
        assertEquals(4, first.snapshot().report().toolCallCount());
        assertTrue(first.noFutureDataLeakage());
        assertTrue(first.outputAuditClean());
        assertFalse(first.snapshot().report().tradingStarted());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shadow_research_snapshots WHERE run_id=?",
                Integer.class, first.run().id()));
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE shadow_research_snapshots SET limitations_json='[]'::jsonb WHERE run_id=?",
                first.run().id()));
    }

    @Test
    void paperExecutionAtNextOpenIsExactlyOnceAndConservesAccounting() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        var paper = paper(repository);
        ShadowResearchModels.ShadowRun run = createFrozenRun(repository,
                LocalDate.of(2025, 9, 10), 2001);
        var security = ShadowResearchTestFixtures.dataset().dataset()
                .securities().get(0);
        var recommendation = new ShadowResearchModels.ShadowRecommendation(
                "RESEARCH_PREFERENCE", List.of("BUY_AND_HOLD"),
                List.of(security.canonicalCode()), "BUY_AND_HOLD", "LOW",
                new BigDecimal("0.55"), new BigDecimal("0.40"),
                List.of("EVIDENCE_1"), List.of(), true, true);
        Instant execution = StrategyResearchModels.openInstant(
                LocalDate.of(2025, 9, 11));
        List<ShadowResearchModels.PaperOrder> orders = paper.createOrders(
                run, recommendation, execution);

        var first = paper.executeDue(LocalDate.of(2025, 9, 11), execution,
                ShadowResearchTestFixtures.dataset().dataset(), null);
        var second = paper.executeDue(LocalDate.of(2025, 9, 11), execution,
                ShadowResearchTestFixtures.dataset().dataset(), null);

        assertEquals(1, orders.size());
        assertEquals(1, first.fills().size());
        assertEquals(0, second.fills().size());
        assertTrue(first.portfolio().cash().signum() >= 0);
        assertTrue(first.portfolio().totalFees().signum() > 0);
        assertEquals(run.id(), first.snapshot().runId());
        assertEquals(first.snapshot(), repository.portfolioSnapshot(
                run.id()).orElseThrow());
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_paper_fills WHERE run_id=?",
                Integer.class, run.id()));
    }

    @Test
    void dailyMaintenanceExecutesPriorIntentAndAppendsOutcomesOnly() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        var paper = paper(repository);
        var tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var runtime = new ShadowResearchRuntime(repository,
                ShadowResearchTestFixtures.source(), paper, tx,
                Clock.fixed(ShadowResearchTestFixtures.AS_OF,
                        ZoneOffset.UTC));
        var frozen = runtime.run(request(),
                new DeterministicFakeModelAdapter());
        ShadowResearchModels.ShadowRun run = frozen.run();
        var dataset = ShadowResearchTestFixtures.dataset().dataset();
        int dueIntentCount = frozen.orders().size();
        assertTrue(dueIntentCount > 0);
        var service = new ShadowContinuousDailyMaintenanceService(repository,
                paper, new ShadowOutcomeService(repository));

        LocalDate currentDate = dataset.lastSessionDate();
        var first = service.maintain(currentDate, dataset,
                ShadowResearchTestFixtures.AS_OF);
        var second = service.maintain(currentDate, dataset,
                ShadowResearchTestFixtures.AS_OF);

        assertEquals(dueIntentCount, first.paperExecution().fills().size());
        assertEquals(0, second.paperExecution().fills().size());
        assertEquals(3, repository.outcomes(run.id()).size());
        assertTrue(first.historicalResearchUnchanged());
        assertEquals("FROZEN", repository.run(run.id()).orElseThrow()
                .status().name());
        assertEquals(dueIntentCount, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_paper_fills WHERE run_id=?",
                Integer.class, run.id()));
    }

    @Test
    void staleRunRecoveryAllowsOneNewAttemptWithoutMutatingTerminalHistory() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        LocalDate date = LocalDate.of(2025, 9, 12);
        var created = repository.createRun(key(date, 3001),
                ShadowResearchModels.TriggerMode.MANUAL, date,
                ShadowResearchModels.RESEARCH_SLOT,
                Instant.parse("2025-09-12T08:30:00Z"),
                ShadowResearchModels.STRATEGY_VERSION, "TEST", "TEST",
                "PROMPT_V1", "AGENT_RUNTIME_V1", hash(3001));
        int recovered = repository.interruptStaleRuns(
                Instant.parse("2030-01-01T08:00:00Z"),
                Instant.parse("2030-01-01T09:00:00Z"));
        var retry = repository.createRun(key(date, 3001),
                ShadowResearchModels.TriggerMode.MANUAL, date,
                ShadowResearchModels.RESEARCH_SLOT,
                Instant.parse("2025-09-12T09:01:00Z"),
                ShadowResearchModels.STRATEGY_VERSION, "TEST", "TEST",
                "PROMPT_V1", "AGENT_RUNTIME_V1", hash(3002));

        assertEquals(1, recovered);
        assertEquals(ShadowResearchModels.RunStatus.INTERRUPTED,
                repository.run(created.id()).orElseThrow().status());
        assertEquals(2, retry.attempt());
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE shadow_research_runs SET error_code='CHANGED' WHERE id=?",
                created.id()));
    }

    @Test
    void schedulerDispatchClaimIsUniqueAndTerminalRowsAreImmutable() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        LocalDate date = LocalDate.of(2025, 9, 15);
        String requestId = "SQHB_20250915T093000Z_A1B2C3D4E5F6";
        assertTrue(repository.claimScheduledDispatch(date, requestId,
                Instant.parse("2025-09-15T09:30:00Z")));
        assertFalse(repository.claimScheduledDispatch(date,
                "SQHB_20250915T093001Z_B1C2D3E4F5A6",
                Instant.parse("2025-09-15T09:30:01Z")));
        repository.completeScheduledDispatch(requestId, true, null,
                Instant.parse("2025-09-15T09:30:02Z"));
        assertThrows(Exception.class, () -> jdbc.update(
                "DELETE FROM shadow_scheduler_dispatches WHERE request_id=?",
                requestId));
    }

    @Test
    void modelFailureLeavesNoSnapshotOrderFillOrActiveRun() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        var tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var runtime = new ShadowResearchRuntime(repository,
                ShadowResearchTestFixtures.source(), paper(repository), tx,
                Clock.fixed(ShadowResearchTestFixtures.AS_OF,
                        ZoneOffset.UTC));
        ModelAdapter failing = new ModelAdapter() {
            @Override
            public Descriptor descriptor() {
                return new Descriptor("FAKE_FAILURE", "FAIL_FAST",
                        "MODEL_ADAPTER_V1", true);
            }

            @Override
            public ModelResponse complete(ModelRequest request) {
                throw new IllegalStateException("M4_FAKE_MODEL_FAILED");
            }
        };

        assertThrows(IllegalStateException.class, () -> runtime.run(
                request(), failing));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_research_runs WHERE status='FAILED'",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_research_snapshots",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_paper_orders",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_paper_fills",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_research_runs WHERE status IN ('QUEUED','RUNNING')",
                Integer.class));
    }

    @Test
    void insufficientEvidenceFreezesAnEmptyRecommendationAndNoPaperOrder() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        var tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var runtime = new ShadowResearchRuntime(repository,
                ShadowResearchTestFixtures.source(), paper(repository), tx,
                Clock.fixed(ShadowResearchTestFixtures.AS_OF,
                        ZoneOffset.UTC));
        var loaded = ShadowResearchTestFixtures.dataset().dataset();
        LocalDate end = LocalDate.of(2025, 9, 10);
        Instant asOf = StrategyResearchModels.closeInstant(end)
                .plusSeconds(60);
        var request = new ShadowRequest(TriggerMode.HISTORICAL_REPLAY,
                end, end.minusDays(30), asOf, loaded.securities(),
                loaded.securities().get(0),
                ShadowResearchTestFixtures.strategies(),
                StrategyResearchModels.openInstant(
                        LocalDate.of(2025, 9, 11)), 0,
                "Insufficient windows must freeze as an empty decision.");

        var result = runtime.run(request,
                new DeterministicFakeModelAdapter());

        assertEquals("INSUFFICIENT_EVIDENCE",
                result.snapshot().recommendation().decisionCode());
        assertTrue(result.snapshot().recommendation()
                .rankedSecurities().isEmpty());
        assertEquals(0, result.snapshot().recommendation()
                .suggestedGrossExposure().signum());
        assertTrue(result.orders().isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM shadow_paper_orders",
                Integer.class));
    }

    @Test
    void outcomesAreAppendOnlyIdempotentAndRejectFutureKnowledge() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        var tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var runtime = new ShadowResearchRuntime(repository,
                ShadowResearchTestFixtures.source(), paper(repository), tx,
                Clock.fixed(ShadowResearchTestFixtures.AS_OF,
                        ZoneOffset.UTC));
        var frozen = runtime.run(request(),
                new DeterministicFakeModelAdapter());
        var service = new ShadowOutcomeService(repository);

        var first = service.evaluateAvailable(frozen.run().id(),
                ShadowResearchTestFixtures.dataset().dataset(),
                ShadowResearchTestFixtures.AS_OF);
        var second = service.evaluateAvailable(frozen.run().id(),
                ShadowResearchTestFixtures.dataset().dataset(),
                ShadowResearchTestFixtures.AS_OF);

        assertEquals(3, first.size());
        assertEquals(first, second);
        assertEquals(3, repository.outcomes(frozen.run().id()).size());
        assertThrows(Exception.class, () -> jdbc.update(
                "UPDATE shadow_outcomes SET evaluation_date=evaluation_date+1 WHERE run_id=?",
                frozen.run().id()));
        assertThrows(IllegalStateException.class, () ->
                service.evaluateAvailable(frozen.run().id(),
                        ShadowResearchTestFixtures.dataset().dataset(),
                        frozen.run().researchAsOf()));
    }

    @Test
    void fiveAndTwentyDayReplayRemainBoundedAndDeterministic() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource != null);
        replay(5, 4_000);
        replay(20, 5_000);
        replay(60, 6_000);
    }

    private static void replay(int count, int keySeed) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = repository(jdbc);
        var tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var paper = new ShadowPaperPortfolioService(repository, tx);
        var accepted = ShadowResearchTestFixtures.dataset();
        var source = new InMemoryShadowResearchDatasetSource(accepted);
        Instant now = ShadowResearchTestFixtures.AS_OF;
        var runtime = new ShadowResearchRuntime(repository, source, paper,
                tx, Clock.fixed(now, ZoneOffset.UTC));
        var replay = new ShadowHistoricalReplayService(runtime, paper,
                new ShadowOutcomeService(repository));
        List<StrategyResearchModels.TradingSession> sessions = accepted
                .dataset().sessions().stream().filter(value ->
                        !value.tradeDate().isBefore(LocalDate.of(2025, 8, 1)))
                .limit(count + 1L).toList();
        java.util.ArrayList<ShadowHistoricalReplayService.ReplayStep> steps =
                new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = sessions.get(i).tradeDate();
            LocalDate next = sessions.get(i + 1).tradeDate();
            Instant asOf = StrategyResearchModels.closeInstant(date)
                    .plusSeconds(60);
            var request = new ShadowRequest(
                    TriggerMode.HISTORICAL_REPLAY, date,
                    date.minusDays(60), asOf,
                    accepted.dataset().securities(),
                    accepted.dataset().securities().get(0),
                    ShadowResearchTestFixtures.strategies(),
                    StrategyResearchModels.openInstant(next), 0,
                    "Bounded historical shadow replay " + (keySeed + i));
            steps.add(new ShadowHistoricalReplayService.ReplayStep(request,
                    next, StrategyResearchModels.openInstant(next),
                    accepted.dataset(), ShadowResearchTestFixtures.AS_OF));
        }
        var result = replay.replay(steps,
                DeterministicFakeModelAdapter::new);
        assertEquals(count, result.researchRuns().size());
        assertTrue(result.noFutureDataLeakage());
        assertTrue(result.researchRuns().stream().allMatch(value ->
                value.snapshot().report().agentRuns().stream()
                        .map(run -> run.agentRole()).distinct().count() == 7));
        assertTrue(result.outcomeCount() >= count);
    }

    private static ShadowResearchRepository repository(JdbcTemplate jdbc) {
        return new ShadowResearchRepository(jdbc,
                new ObjectMapper().findAndRegisterModules());
    }

    private static ShadowPaperPortfolioService paper(
            ShadowResearchRepository repository
    ) {
        return new ShadowPaperPortfolioService(repository,
                new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource)));
    }

    private static ShadowResearchModels.ShadowRun createFrozenRun(
            ShadowResearchRepository repository,
            LocalDate date,
            int seed
    ) {
        Instant asOf = StrategyResearchModels.closeInstant(date)
                .plusSeconds(60);
        var run = repository.createRun(key(date, seed),
                TriggerMode.HISTORICAL_REPLAY, date, "HISTORICAL_REPLAY",
                asOf, ShadowResearchModels.STRATEGY_VERSION,
                "TEST", "TEST", "PROMPT_V1", "AGENT_RUNTIME_V1",
                hash(seed));
        repository.start(run.id(), asOf);
        repository.freezeRun(run.id(), asOf,
                StrategyResearchModels.openInstant(date.plusDays(1)),
                hash(seed + 10), hash(seed + 20), hash(seed + 30), asOf);
        return repository.run(run.id()).orElseThrow();
    }

    private static String key(LocalDate date, int seed) {
        return "SHADOW_" + date.toString().replace("-", "")
                + "_AFTER_CLOSE_" + String.format("%016x", seed);
    }

    private static String hash(int seed) {
        return String.format("%064x", seed);
    }

    static ShadowRequest request() {
        var loaded = ShadowResearchTestFixtures.dataset().dataset();
        var end = LocalDate.of(2025, 9, 10);
        Instant asOf = StrategyResearchModels.closeInstant(end)
                .plusSeconds(60);
        return new ShadowRequest(TriggerMode.HISTORICAL_REPLAY,
                end, loaded.firstSessionDate(),
                asOf, loaded.securities(),
                loaded.securities().get(0),
                ShadowResearchTestFixtures.strategies(),
                StrategyResearchModels.openInstant(
                        LocalDate.of(2025, 9, 11)), 0,
                "Run immutable, evidence-bound historical shadow research.");
    }
}
