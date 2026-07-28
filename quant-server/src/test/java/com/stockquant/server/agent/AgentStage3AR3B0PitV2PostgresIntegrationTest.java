package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateAction;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateActionType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MockMarketFactProvider;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels;
import com.stockquant.server.agent.marketfacts.PitMarketFactsContracts;
import com.stockquant.server.agent.marketfacts.QfqAsOfEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentStage3AR3B0PitV2PostgresIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 27);
    private static final Instant OBSERVED =
            Instant.parse("2026-07-27T08:00:00.000000Z");
    private static final Instant CUTOFF =
            Instant.parse("2026-07-27T15:59:59.999999Z");
    private static AgentPostgresTestEnvironment.IsolatedSchema isolated;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PitMarketFactCaptureService captureService;
    @Autowired QfqAsOfEngine qfqEngine;
    @Autowired PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        isolated = AgentPostgresTestEnvironment.registerIsolatedDataSource(
                registry, "pit_v2");
        registry.add("stockquant.agent-team.enabled", () -> false);
        registry.add("stockquant.agent-team.shadow.enabled", () -> false);
        registry.add("stockquant.agent-team.shadow.scheduler-enabled",
                () -> false);
        registry.add("stockquant.announcement.akshare.enabled", () -> false);
    }

    @AfterAll
    static void cleanup() {
        if (isolated != null) {
            isolated.close();
        }
    }

    @Test
    void migratesV1ThroughV13AndCreatesOnlyTheProviderNeutralDelta() {
        assertEquals(
                List.of("1", "2", "3", "4", "5", "6", "7",
                        "8", "9", "10", "11", "12", "13"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success ORDER BY installed_rank
                        """, String.class));
        assertEquals(0, count("""
                SELECT count(*) FROM flyway_schema_history
                WHERE NOT success
                """));
        assertEquals(-763324992, jdbc.queryForObject("""
                SELECT checksum FROM flyway_schema_history
                WHERE version='13' AND success
                """, Integer.class));
        assertEquals(6, count("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema=current_schema()
                  AND table_name IN (
                    'pit_market_fact_batches',
                    'pit_market_fact_observations',
                    'raw_daily_bar_facts_v2',
                    'adjustment_factor_facts_v1',
                    'trading_calendar_facts_v1',
                    'corporate_action_facts_v1')
                """));
        assertEquals(17, count("""
                SELECT count(*)
                FROM pg_trigger trigger_record
                JOIN pg_class relation
                  ON relation.oid=trigger_record.tgrelid
                JOIN pg_namespace namespace
                  ON namespace.oid=relation.relnamespace
                WHERE namespace.nspname=current_schema()
                  AND NOT trigger_record.tgisinternal
                  AND relation.relname IN (
                    'pit_market_fact_batches',
                    'pit_market_fact_observations',
                    'raw_daily_bar_facts_v2',
                    'adjustment_factor_facts_v1',
                    'trading_calendar_facts_v1',
                    'corporate_action_facts_v1')
                """));
    }

    @Test
    void preservesIdempotencyAtoBtoAAndAppendOnlyFacts() {
        MarketFactResponse original = response(
                "000001", "SZSE",
                MockMarketFactProvider.Scenario.NORMAL);
        var first = captureService.capture(original, OBSERVED);
        assertTrue(first.appendedCount() > 0);
        var repeated = captureService.capture(
                original, OBSERVED.plusSeconds(60));
        assertEquals(0, repeated.appendedCount());
        assertEquals(original.recordCount(), repeated.idempotentCount());
        BigDecimal originalClose = calculate(
                "000001", "SZSE",
                OBSERVED.plusSeconds(90))
                .bars().get(0).close();

        MarketFactResponse changed = changedFirstClose(original);
        var second = captureService.capture(
                changed, OBSERVED.plusSeconds(120));
        assertEquals(1, second.appendedCount());
        BigDecimal changedClose = calculate(
                "000001", "SZSE",
                OBSERVED.plusSeconds(150))
                .bars().get(0).close();
        assertNotEquals(originalClose, changedClose);
        var third = captureService.capture(
                original, OBSERVED.plusSeconds(180));
        assertEquals(1, third.appendedCount());
        assertEquals(originalClose, calculate(
                "000001", "SZSE",
                OBSERVED.plusSeconds(210))
                .bars().get(0).close());

        List<String> hashes = jdbc.queryForList("""
                SELECT canonical_content_hash
                FROM pit_market_fact_observations
                WHERE fact_type='RAW_DAILY_BAR'
                  AND natural_key=?
                ORDER BY chain_sequence
                """, String.class,
                "RAW_DAILY_BAR|000001|" + START);
        assertEquals(3, hashes.size());
        assertEquals(hashes.get(0), hashes.get(2));
        assertNotEquals(hashes.get(0), hashes.get(1));
        assertEquals(List.of(1, 2, 3), jdbc.queryForList("""
                SELECT chain_sequence
                FROM pit_market_fact_observations
                WHERE fact_type='RAW_DAILY_BAR'
                  AND natural_key=?
                ORDER BY chain_sequence
                """, Integer.class,
                "RAW_DAILY_BAR|000001|" + START));

        long batchId = first.batchId();
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE pit_market_fact_batches SET record_count=0 WHERE id=?",
                batchId));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "DELETE FROM pit_market_fact_batches WHERE id=?", batchId));
        assertThrows(DataAccessException.class, () -> jdbc.execute(
                "TRUNCATE raw_daily_bar_facts_v2"));

        var qfq = calculate("000001", "SZSE", CUTOFF);
        assertTrue(qfq.available(), qfq.reasonCode());
        assertEquals(END, qfq.anchorTradeDate());
        assertTrue(qfq.bars().size() >= 120);
        assertTrue(qfq.bars().stream().allMatch(
                bar -> bar.tradeDate().getDayOfWeek().getValue() <= 5));
    }

    @Test
    void enforcesCutoffDailyExactAndCorporateActionSafetyScenarios() {
        captureService.capture(response(
                "000002", "SZSE",
                MockMarketFactProvider.Scenario.FACTOR_MISSING), OBSERVED);
        assertUnavailable(
                calculate("000002", "SZSE", CUTOFF),
                PitMarketFactsContracts.FACTOR_UNAVAILABLE);

        captureService.capture(response(
                "000003", "SZSE",
                MockMarketFactProvider.Scenario.UNEXPLAINED_FACTOR_CHANGE),
                OBSERVED);
        assertUnavailable(
                calculate("000003", "SZSE", CUTOFF),
                PitMarketFactsContracts.CORPORATE_ACTION_LINEAGE_UNAVAILABLE);

        MarketFactResponse noCalendar = withoutCalendar(response(
                "000004", "SZSE",
                MockMarketFactProvider.Scenario.NORMAL));
        captureService.capture(noCalendar, OBSERVED);
        assertUnavailable(
                calculate("000004", "SZSE", CUTOFF),
                PitMarketFactsContracts.CALENDAR_UNAVAILABLE);

        MarketFactResponse complete = response(
                "000005", "SZSE",
                MockMarketFactProvider.Scenario.NORMAL);
        LocalDate missingDate =
                complete.adjustmentFactors().get(60)
                        .factorEffectiveTradeDate();
        MarketFactResponse firstCapture = withoutFactor(
                complete, missingDate);
        captureService.capture(firstCapture, OBSERVED);
        assertUnavailable(
                calculate(
                        "000005", "SZSE",
                        OBSERVED.plusSeconds(1)),
                PitMarketFactsContracts.FACTOR_UNAVAILABLE);
        captureService.capture(
                onlyFactor(complete, missingDate),
                OBSERVED.plusSeconds(60));
        assertUnavailable(
                calculate(
                        "000005", "SZSE",
                        OBSERVED.plusSeconds(30)),
                PitMarketFactsContracts.FACTOR_UNAVAILABLE);
        assertTrue(calculate(
                "000005", "SZSE", CUTOFF).available());

        assertUnavailable(
                calculate(
                        "000005", "SZSE",
                        OBSERVED.minusSeconds(1)),
                PitMarketFactsContracts.CALENDAR_UNAVAILABLE);
        assertUnavailable(
                qfqEngine.calculate(
                        "000005", "SZSE", "ANOTHER_PROVIDER",
                        "000005.SZSE", END, CUTOFF),
                PitMarketFactsContracts.CALENDAR_UNAVAILABLE);
    }

    @Test
    void replaysFactorRevisionsAcrossCutoffsOnlyWhenNewActionExplainsThem() {
        MarketFactResponse original = response(
                "600004", "SSE", MockMarketFactProvider.Scenario.NORMAL);
        captureService.capture(original, OBSERVED);
        var originalAsOf = calculate(
                "600004", "SSE", OBSERVED.plusSeconds(30));
        assertTrue(originalAsOf.available(), originalAsOf.reasonCode());

        MarketFactResponse explainedRevision =
                scaledFactors(original, true);
        captureService.capture(
                explainedRevision, OBSERVED.plusSeconds(600));
        var oldReplay = calculate(
                "600004", "SSE", OBSERVED.plusSeconds(300));
        var revisedAsOf = calculate(
                "600004", "SSE", OBSERVED.plusSeconds(700));
        assertTrue(oldReplay.available(), oldReplay.reasonCode());
        assertTrue(revisedAsOf.available(), revisedAsOf.reasonCode());
        assertEquals(
                originalAsOf.bars().get(0).close(),
                oldReplay.bars().get(0).close());
        assertEquals(
                originalAsOf.bars().get(0).close(),
                revisedAsOf.bars().get(0).close());
        assertNotEquals(
                oldReplay.bars().get(0).factorObservationVersion(),
                revisedAsOf.bars().get(0).factorObservationVersion());
        assertTrue(revisedAsOf.corporateActionLineage().stream()
                .anyMatch(action ->
                        "MOCK-ACTION-002".equals(action.sourceActionId())));

        MarketFactResponse unexplainedOriginal = response(
                "600005", "SSE", MockMarketFactProvider.Scenario.NORMAL);
        captureService.capture(unexplainedOriginal, OBSERVED);
        captureService.capture(
                scaledFactors(unexplainedOriginal, false),
                OBSERVED.plusSeconds(600));
        assertUnavailable(
                calculate("600005", "SSE", OBSERVED.plusSeconds(700)),
                PitMarketFactsContracts
                        .CORPORATE_ACTION_LINEAGE_UNAVAILABLE);
    }

    @Test
    void concurrentIdenticalCaptureCreatesOneLegalChainTail() throws Exception {
        MarketFactResponse response = response(
                "000006", "SZSE",
                MockMarketFactProvider.Scenario.NORMAL);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> captureService.capture(
                    response, OBSERVED.plusSeconds(300)));
            var second = executor.submit(() -> captureService.capture(
                    response, OBSERVED.plusSeconds(301)));
            int appended = first.get().appendedCount()
                    + second.get().appendedCount();
            assertEquals(response.recordCount(), appended);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(
                count("""
                        SELECT count(DISTINCT natural_key)
                        FROM pit_market_fact_observations
                        WHERE source_instrument_id='000006.SZSE'
                        """),
                count("""
                        SELECT count(*)
                        FROM pit_market_fact_observations
                        WHERE source_instrument_id='000006.SZSE'
                        """));
    }

    @Test
    void rejectsDuplicateProviderNaturalKeysBeforeWritingAnything() {
        MarketFactResponse original = response(
                "600001", "SSE",
                MockMarketFactProvider.Scenario.NORMAL);
        List<RawDailyBar> duplicate =
                new ArrayList<>(original.rawDailyBars());
        duplicate.add(original.rawDailyBars().get(0));
        MarketFactResponse invalid = copy(
                original,
                duplicate,
                original.adjustmentFactors(),
                original.tradingCalendar(),
                original.corporateActions());
        long before = count("""
                SELECT count(*) FROM pit_market_fact_batches
                WHERE source_instrument_id='600001.SSE'
                """);
        assertThrows(IllegalArgumentException.class, () ->
                captureService.capture(invalid, OBSERVED));
        assertEquals(before, count("""
                SELECT count(*) FROM pit_market_fact_batches
                WHERE source_instrument_id='600001.SSE'
                """));
    }

    @Test
    void keepsIncompleteResponsesAsBatchEvidenceWithoutReliableFacts() {
        MarketFactResponse partial = response(
                "600002", "SSE",
                MockMarketFactProvider.Scenario.PARTIAL);
        var incomplete = captureService.capture(partial, OBSERVED);
        assertFalse(incomplete.complete());
        assertEquals(partial.recordCount(), incomplete.receivedCount());
        assertEquals(0, incomplete.appendedCount());
        assertEquals(0, incomplete.idempotentCount());
        assertEquals(0, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE source_instrument_id='600002.SSE'
                """));
        assertEquals("MOCK_PARTIAL_RESPONSE",
                jdbc.queryForObject("""
                        SELECT provider_metadata_json
                               ->'errors'->0->>'code'
                        FROM pit_market_fact_batches
                        WHERE id=?
                        """, String.class, incomplete.batchId()));

        MarketFactResponse complete = response(
                "600002", "SSE",
                MockMarketFactProvider.Scenario.NORMAL);
        var completed = captureService.capture(
                complete, OBSERVED.plusSeconds(60));
        assertTrue(completed.complete());
        assertEquals(complete.recordCount(), completed.appendedCount());
    }

    @Test
    void rejectsPredatedDailyBarsAndInventedProviderRevisionAtJavaAndDatabase()
            throws Exception {
        MarketFactResponse response = response(
                "600003", "SSE",
                MockMarketFactProvider.Scenario.NORMAL);
        assertThrows(IllegalArgumentException.class, () ->
                captureService.capture(
                        response,
                        END.atTime(14, 59, 59)
                                .atZone(PitMarketFactsContracts.MARKET_ZONE)
                                .toInstant()));
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderVersion(
                        MockMarketFactProvider.FIXTURE_VERSION,
                        "LOCAL_HASH_IS_NOT_A_REVISION",
                        null,
                        null,
                        null,
                        RevisionQualification.SYSTEM_KNOWLEDGE_ONLY));

        long batchId = captureService.capture(response, OBSERVED).batchId();
        Instant beforeClose = END.atTime(14, 59, 59)
                .atZone(PitMarketFactsContracts.MARKET_ZONE)
                .toInstant();
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                WITH observation AS (
                    INSERT INTO pit_market_fact_observations(
                        batch_id, fact_type, fact_contract_version,
                        natural_key, chain_sequence,
                        predecessor_observation_id, source_code,
                        source_instrument_id, provider_dataset_version,
                        provider_revision, provider_snapshot_id,
                        provider_published_at, provider_updated_at,
                        first_observed_at, known_at, recorded_at,
                        canonical_content_hash, observation_version,
                        revision_qualification, assurance_level,
                        usage_qualification, formal_eligible,
                        local_persistence_allowed,
                        historical_replay_allowed, backtest_allowed,
                        agent_use_allowed, raw_payload_json
                    )
                    SELECT id, 'RAW_DAILY_BAR',
                           'RAW_DAILY_BAR_OBSERVATION_V2',
                           'RAW_DAILY_BAR|600003|2026-07-28',
                           1, NULL, source_code, source_instrument_id,
                           provider_dataset_version, NULL, NULL, NULL, NULL,
                           ?, ?, ?, repeat('a', 64), repeat('b', 64),
                           revision_qualification, assurance_level,
                           usage_qualification, formal_eligible,
                           local_persistence_allowed,
                           historical_replay_allowed, backtest_allowed,
                           agent_use_allowed, '{}'::jsonb
                    FROM pit_market_fact_batches WHERE id=?
                    RETURNING id
                )
                INSERT INTO raw_daily_bar_facts_v2(
                    observation_id, symbol, exchange, trade_date,
                    open, high, low, close, volume, amount, turnover_rate
                )
                SELECT id, '600003', 'SSE', DATE '2026-07-28',
                       10, 11, 9, 10, 100, 1000, 0.01
                FROM observation
                """, beforeClose, beforeClose, beforeClose, batchId));

        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO pit_market_fact_observations(
                    batch_id, fact_type, fact_contract_version,
                    natural_key, chain_sequence,
                    predecessor_observation_id, source_code,
                    source_instrument_id, provider_dataset_version,
                    provider_revision, provider_snapshot_id,
                    provider_published_at, provider_updated_at,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    revision_qualification, assurance_level,
                    usage_qualification, formal_eligible,
                    local_persistence_allowed,
                    historical_replay_allowed, backtest_allowed,
                    agent_use_allowed, raw_payload_json
                )
                SELECT id, 'RAW_DAILY_BAR',
                       'RAW_DAILY_BAR_OBSERVATION_V2',
                       'RAW_DAILY_BAR|600003|2026-07-29',
                       1, NULL, source_code, source_instrument_id,
                       provider_dataset_version, 'INVENTED_REVISION',
                       NULL, NULL, NULL, observed_at, observed_at,
                       recorded_at, repeat('c', 64), repeat('d', 64),
                       revision_qualification, assurance_level,
                       usage_qualification, formal_eligible,
                       local_persistence_allowed,
                       historical_replay_allowed, backtest_allowed,
                       agent_use_allowed, '{}'::jsonb
                FROM pit_market_fact_batches WHERE id=?
                """, batchId));
    }

    @Test
    void databaseHardGatesRejectInvalidOhlcAndNonPositiveFactorAtomically() {
        long batchId = captureService.capture(response(
                "600006", "SSE", MockMarketFactProvider.Scenario.NORMAL),
                OBSERVED).batchId();
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        assertThrows(DataAccessException.class, () ->
                transaction.executeWithoutResult(status -> {
                    long observationId = insertEnvelope(
                            batchId,
                            "RAW_DAILY_BAR",
                            PitMarketFactsContracts.RAW_DAILY_BAR_CONTRACT,
                            "RAW_DAILY_BAR|699998|" + START,
                            "1", "2");
                    jdbc.update("""
                            INSERT INTO raw_daily_bar_facts_v2(
                                observation_id, symbol, exchange, trade_date,
                                open, high, low, close, volume, amount,
                                turnover_rate
                            ) VALUES (?, '699998', 'SSE', ?, 10, 9, 8, 10,
                                      100, 1000, 0.01)
                            """, observationId, START);
                }));
        assertEquals(0, count("""
                SELECT count(*) FROM pit_market_fact_observations
                WHERE natural_key='RAW_DAILY_BAR|699998|%s'
                """.formatted(START)));

        assertThrows(DataAccessException.class, () ->
                transaction.executeWithoutResult(status -> {
                    long observationId = insertEnvelope(
                            batchId,
                            "ADJUSTMENT_FACTOR",
                            PitMarketFactsContracts.ADJUSTMENT_FACTOR_CONTRACT,
                            "ADJUSTMENT_FACTOR|699999|QFQ|" + START,
                            "3", "4");
                    jdbc.update("""
                            INSERT INTO adjustment_factor_facts_v1(
                                observation_id, symbol,
                                factor_effective_trade_date,
                                factor_type, coverage_mode, factor
                            ) VALUES (?, '699999', ?, 'QFQ', 'DAILY_EXACT', 0)
                            """, observationId, START);
                }));
        assertEquals(0, count("""
                SELECT count(*) FROM pit_market_fact_observations
                WHERE natural_key='ADJUSTMENT_FACTOR|699999|QFQ|%s'
                """.formatted(START)));
    }

    @Test
    void databaseRejectsCrossSourcePredecessorLineage() {
        MarketFactResponse original = response(
                "600007", "SSE", MockMarketFactProvider.Scenario.NORMAL);
        captureService.capture(original, OBSERVED);
        var otherCapture = captureService.capture(
                withSource(original, "OTHER_TEST_PROVIDER"),
                OBSERVED.plusSeconds(60));
        String naturalKey = "RAW_DAILY_BAR|600007|" + START;
        Long foreignPredecessor = jdbc.queryForObject("""
                SELECT id FROM pit_market_fact_observations
                WHERE source_code=? AND natural_key=?
                ORDER BY chain_sequence DESC LIMIT 1
                """, Long.class,
                MockMarketFactProvider.PROVIDER_CODE, naturalKey);
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO pit_market_fact_observations(
                    batch_id, fact_type, fact_contract_version, natural_key,
                    chain_sequence, predecessor_observation_id, source_code,
                    source_instrument_id, provider_dataset_version,
                    provider_revision, provider_snapshot_id,
                    provider_published_at, provider_updated_at,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    revision_qualification, assurance_level,
                    usage_qualification, formal_eligible,
                    local_persistence_allowed, historical_replay_allowed,
                    backtest_allowed, agent_use_allowed, raw_payload_json
                )
                SELECT id, 'RAW_DAILY_BAR',
                       'RAW_DAILY_BAR_OBSERVATION_V2', ?, 2, ?,
                       source_code, source_instrument_id,
                       provider_dataset_version, NULL, NULL, NULL, NULL,
                       ?, ?, ?, repeat('5', 64), repeat('6', 64),
                       revision_qualification, assurance_level,
                       usage_qualification, formal_eligible,
                       local_persistence_allowed, historical_replay_allowed,
                       backtest_allowed, agent_use_allowed, '{}'::jsonb
                FROM pit_market_fact_batches WHERE id=?
                """, naturalKey, foreignPredecessor,
                OBSERVED.plusSeconds(60), OBSERVED.plusSeconds(60),
                OBSERVED.plusSeconds(61), otherCapture.batchId()));
    }

    private MarketFactResponse response(
            String symbol,
            String exchange,
            MockMarketFactProvider.Scenario scenario
    ) {
        var provider = new MockMarketFactProvider(mapper, scenario);
        return provider.fetch(request(symbol, exchange));
    }

    private static MarketFactRequest request(
            String symbol,
            String exchange
    ) {
        return new MarketFactRequest(
                RunNamespace.TEST,
                MockMarketFactProvider.PROVIDER_CODE,
                symbol + "." + exchange,
                symbol,
                exchange,
                START,
                END,
                Set.of(FactType.values()),
                Duration.ofSeconds(5));
    }

    private MarketFactResponse changedFirstClose(
            MarketFactResponse original
    ) {
        List<RawDailyBar> values =
                new ArrayList<>(original.rawDailyBars());
        RawDailyBar first = values.get(0);
        values.set(0, new RawDailyBar(
                first.symbol(),
                first.exchange(),
                first.tradeDate(),
                first.open(),
                first.high(),
                first.low(),
                first.close().add(new BigDecimal("0.01")),
                first.volume(),
                first.amount(),
                first.turnoverRate(),
                first.version(),
                first.rawFields()));
        return copy(
                original,
                values,
                original.adjustmentFactors(),
                original.tradingCalendar(),
                original.corporateActions());
    }

    private MarketFactResponse scaledFactors(
            MarketFactResponse original,
            boolean addExplainingAction
    ) {
        List<AdjustmentFactor> factors = original.adjustmentFactors().stream()
                .map(value -> new AdjustmentFactor(
                        value.symbol(),
                        value.factorEffectiveTradeDate(),
                        value.factorType(),
                        value.coverageMode(),
                        value.factor().multiply(new BigDecimal("2")),
                        value.version(),
                        value.rawFields()))
                .toList();
        List<CorporateAction> actions =
                new ArrayList<>(original.corporateActions());
        if (addExplainingAction) {
            ObjectNode terms = mapper.createObjectNode();
            terms.put("fixtureExplanation", "NEW_STOCK_DIVIDEND");
            actions.add(new CorporateAction(
                    "MOCK-ACTION-002",
                    original.rawDailyBars().get(0).symbol(),
                    CorporateActionType.STOCK_DIVIDEND,
                    END.minusDays(1),
                    END,
                    terms,
                    original.adjustmentFactors().get(0).version(),
                    terms.deepCopy()));
        }
        return copy(
                original,
                original.rawDailyBars(),
                factors,
                original.tradingCalendar(),
                actions);
    }

    private static MarketFactResponse withSource(
            MarketFactResponse value,
            String sourceCode
    ) {
        return new MarketFactResponse(
                value.providerContractVersion(),
                value.providerCode(),
                value.adapterVersion(),
                value.runNamespace(),
                sourceCode,
                value.sourceInstrumentId(),
                value.requestedStart(),
                value.requestedEnd(),
                value.complete(),
                value.capability(),
                value.rawDailyBars(),
                value.adjustmentFactors(),
                value.tradingCalendar(),
                value.corporateActions(),
                value.errors(),
                value.providerMetadata());
    }

    private MarketFactResponse withoutCalendar(MarketFactResponse value) {
        return copy(
                value,
                value.rawDailyBars(),
                value.adjustmentFactors(),
                List.of(),
                value.corporateActions());
    }

    private MarketFactResponse withoutFactor(
            MarketFactResponse value,
            LocalDate date
    ) {
        return copy(
                value,
                value.rawDailyBars(),
                value.adjustmentFactors().stream()
                        .filter(factor -> !factor
                                .factorEffectiveTradeDate().equals(date))
                        .toList(),
                value.tradingCalendar(),
                value.corporateActions());
    }

    private MarketFactResponse onlyFactor(
            MarketFactResponse value,
            LocalDate date
    ) {
        return copy(
                value,
                List.of(),
                value.adjustmentFactors().stream()
                        .filter(factor -> factor
                                .factorEffectiveTradeDate().equals(date))
                        .toList(),
                List.of(),
                List.of());
    }

    private static MarketFactResponse copy(
            MarketFactResponse value,
            List<RawDailyBar> bars,
            List<MarketFactProviderModels.AdjustmentFactor> factors,
            List<MarketFactProviderModels.TradingCalendar> calendar,
            List<MarketFactProviderModels.CorporateAction> actions
    ) {
        return new MarketFactResponse(
                value.providerContractVersion(),
                value.providerCode(),
                value.adapterVersion(),
                value.runNamespace(),
                value.sourceCode(),
                value.sourceInstrumentId(),
                value.requestedStart(),
                value.requestedEnd(),
                true,
                value.capability(),
                bars,
                factors,
                calendar,
                actions,
                List.of(),
                value.providerMetadata());
    }

    private PitMarketFactModels.QfqAsOfResult calculate(
            String symbol,
            String exchange,
            Instant cutoff
    ) {
        return qfqEngine.calculate(
                symbol,
                exchange,
                MockMarketFactProvider.PROVIDER_CODE,
                symbol + "." + exchange,
                END,
                cutoff);
    }

    private long insertEnvelope(
            long batchId,
            String factType,
            String contractVersion,
            String naturalKey,
            String contentHashCharacter,
            String observationVersionCharacter
    ) {
        Long value = jdbc.queryForObject("""
                INSERT INTO pit_market_fact_observations(
                    batch_id, fact_type, fact_contract_version, natural_key,
                    chain_sequence, predecessor_observation_id, source_code,
                    source_instrument_id, provider_dataset_version,
                    provider_revision, provider_snapshot_id,
                    provider_published_at, provider_updated_at,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    revision_qualification, assurance_level,
                    usage_qualification, formal_eligible,
                    local_persistence_allowed, historical_replay_allowed,
                    backtest_allowed, agent_use_allowed, raw_payload_json
                )
                SELECT id, ?, ?, ?, 1, NULL, source_code,
                       source_instrument_id, provider_dataset_version,
                       NULL, NULL, NULL, NULL, ?, ?, ?,
                       repeat(?, 64), repeat(?, 64),
                       revision_qualification, assurance_level,
                       usage_qualification, formal_eligible,
                       local_persistence_allowed, historical_replay_allowed,
                       backtest_allowed, agent_use_allowed, '{}'::jsonb
                FROM pit_market_fact_batches
                WHERE id=?
                RETURNING id
                """, Long.class,
                factType, contractVersion, naturalKey,
                OBSERVED, OBSERVED, OBSERVED.plusSeconds(1),
                contentHashCharacter, observationVersionCharacter, batchId);
        return value == null ? -1L : value;
    }

    private static void assertUnavailable(
            PitMarketFactModels.QfqAsOfResult result,
            String reasonCode
    ) {
        assertFalse(result.available());
        assertEquals(reasonCode, result.reasonCode());
        assertTrue(result.bars().isEmpty());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
