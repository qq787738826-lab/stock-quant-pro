package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldSemantic;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldUnit;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateAction;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateActionType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.QualifiedMarketField;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MockMarketFactProvider;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels;
import com.stockquant.server.agent.marketfacts.PitMarketFactsContracts;
import com.stockquant.server.agent.marketfacts.QfqAsOfEngine;
import com.stockquant.server.agent.marketfacts.AgentBacktestContextV2Service;
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
    @Autowired AgentBacktestContextV2Service backtestContextV2Service;
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
        registry.add(
                "stockquant.market-facts.v2.test-demo-enabled",
                () -> true);
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
        assertEquals(1903740866, jdbc.queryForObject("""
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
                qfqEngine.calculate(
                        "000004", "SZSE",
                        MockMarketFactProvider.PROVIDER_CODE,
                        new PitMarketFactModels.QfqSourceIdentities(
                                MockMarketFactProvider.rawSourceIdentity(
                                        "000004", "SZSE"),
                                MockMarketFactProvider.factorSourceIdentity(
                                        "000004", "SZSE"),
                                "CALENDAR:UNAVAILABLE",
                                MockMarketFactProvider
                                        .corporateActionSourceIdentity(
                                                "000004", "SZSE")),
                        END, CUTOFF),
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
                PitMarketFactsContracts.RAW_BAR_UNAVAILABLE);
        assertUnavailable(
                qfqEngine.calculate(
                        "000005", "SZSE", "ANOTHER_PROVIDER",
                        MockMarketFactProvider.qfqSourceIdentities(
                                "000005", "SZSE"),
                        END, CUTOFF),
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
                originalAsOf.bars().get(0).close()
                        .divide(new BigDecimal("2"))
                        .setScale(4),
                revisedAsOf.bars().get(0).close());
        assertNotEquals(
                oldReplay.bars().get(oldReplay.bars().size() - 1)
                        .factorObservationVersion(),
                revisedAsOf.bars().get(revisedAsOf.bars().size() - 1)
                        .factorObservationVersion());
        assertTrue(revisedAsOf.corporateActionLineage().stream()
                .anyMatch(action ->
                        "MOCK-ACTION-002".equals(action.sourceActionId())));

        MarketFactResponse unexplainedOriginal = response(
                "600005", "SSE", MockMarketFactProvider.Scenario.NORMAL);
        captureService.capture(unexplainedOriginal, OBSERVED);
        captureService.capture(
                withUnrelatedAction(
                        scaledFactors(unexplainedOriginal, false),
                        END.minusDays(3)),
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
        long before = count("""
                SELECT count(*) FROM pit_market_fact_observations
                """);
        var executor = Executors.newFixedThreadPool(2);
        int appended;
        try {
            var first = executor.submit(() -> captureService.capture(
                    response, OBSERVED.plusSeconds(300)));
            var second = executor.submit(() -> captureService.capture(
                    response, OBSERVED.plusSeconds(301)));
            appended = first.get().appendedCount()
                    + second.get().appendedCount();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(
                count("""
                        SELECT count(*) FROM pit_market_fact_observations
                        """) - before,
                appended);
        assertEquals(
                count("""
                        SELECT count(DISTINCT natural_key)
                        FROM pit_market_fact_observations
                        WHERE source_instrument_id IN (
                          'SECURITY:000006.SZSE',
                          'FACTOR:QFQ:000006.SZSE',
                          'CORPORATE_ACTION:000006.SZSE'
                        )
                        """),
                count("""
                        SELECT count(*)
                        FROM pit_market_fact_observations
                        WHERE source_instrument_id IN (
                          'SECURITY:000006.SZSE',
                          'FACTOR:QFQ:000006.SZSE',
                          'CORPORATE_ACTION:000006.SZSE'
                        )
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
                WHERE batch_id=?
                """, incomplete.batchId()));
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
        long observationsBefore = count("""
                SELECT count(*) FROM pit_market_fact_observations
                """);
        var completed = captureService.capture(
                complete, OBSERVED.plusSeconds(60));
        assertTrue(completed.complete());
        assertEquals(
                count("""
                        SELECT count(*) FROM pit_market_fact_observations
                        """) - observationsBefore,
                completed.appendedCount());
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
                    open, high, low, close,
                    volume, volume_qualification, volume_unit_code,
                    volume_semantic_code,
                    amount, amount_qualification, amount_unit_code,
                    amount_semantic_code,
                    turnover_rate, turnover_rate_qualification,
                    turnover_rate_unit_code, turnover_rate_semantic_code
                )
                SELECT id, '600003', 'SSE', DATE '2026-07-28',
                       10, 11, 9, 10,
                       100, 'PRESENT_VERIFIED', 'SHARES',
                       'TRADED_VOLUME',
                       1000, 'PRESENT_VERIFIED', 'CNY',
                       'TRADED_AMOUNT',
                       0.01, 'PRESENT_VERIFIED', 'RATIO',
                       'TURNOVER_RATE'
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
                                open, high, low, close,
                                volume, volume_qualification,
                                volume_unit_code, volume_semantic_code,
                                amount, amount_qualification,
                                amount_unit_code, amount_semantic_code,
                                turnover_rate,
                                turnover_rate_qualification,
                                turnover_rate_unit_code,
                                turnover_rate_semantic_code
                            ) VALUES (
                                ?, '699998', 'SSE', ?, 10, 9, 8, 10,
                                100, 'PRESENT_VERIFIED', 'SHARES',
                                'TRADED_VOLUME',
                                1000, 'PRESENT_VERIFIED', 'CNY',
                                'TRADED_AMOUNT',
                                0.01, 'PRESENT_VERIFIED', 'RATIO',
                                'TURNOVER_RATE'
                            )
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

    @Test
    void appliesQualificationAwareKnowledgeTimeInJavaDatabaseAndAsOfQueries() {
        Instant providerPublishedAt =
                Instant.parse("2026-07-27T07:05:00.000000Z");
        Instant providerUpdatedAt =
                Instant.parse("2026-07-27T07:10:00.000000Z");
        Instant historicalCutoff =
                Instant.parse("2026-07-27T07:30:00.000000Z");

        MarketFactResponse system = response(
                "000008", "SZSE", MockMarketFactProvider.Scenario.NORMAL);
        captureService.capture(system, OBSERVED);
        assertUnavailable(
                calculate("000008", "SZSE", historicalCutoff),
                PitMarketFactsContracts.CALENDAR_UNAVAILABLE);

        MarketFactResponse verified = withProviderVersion(
                response(
                        "000009", "SZSE",
                        MockMarketFactProvider.Scenario.NORMAL),
                verifiedVersion(
                        "REVISION-1", "SNAPSHOT-1",
                        providerPublishedAt, providerUpdatedAt),
                verifiedCapability(
                        response(
                                "000009", "SZSE",
                                MockMarketFactProvider.Scenario.NORMAL)
                                .capability()));
        long verifiedBatch = captureService.capture(
                verified, OBSERVED).batchId();
        var historical = calculate(
                "000009", "SZSE", historicalCutoff);
        assertTrue(historical.available(), historical.reasonCode());
        assertEquals(0, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE batch_id=?
                  AND (
                    known_at IS DISTINCT FROM provider_published_at
                    OR provider_published_at >= first_observed_at
                    OR recorded_at < first_observed_at
                  )
                """, verifiedBatch));

        assertThrows(IllegalArgumentException.class, () ->
                captureService.capture(
                        withProviderVersion(
                                response(
                                        "000010", "SZSE",
                                        MockMarketFactProvider.Scenario.NORMAL),
                                verifiedVersion(
                                        "REVISION-LATE", "SNAPSHOT-LATE",
                                        OBSERVED.plusSeconds(1),
                                        OBSERVED.plusSeconds(2)),
                                verifiedCapability(
                                        response(
                                                "000010", "SZSE",
                                                MockMarketFactProvider.Scenario
                                                        .NORMAL)
                                                .capability())),
                        OBSERVED));

        long systemBatch = captureService.capture(
                response(
                        "000011", "SZSE",
                        MockMarketFactProvider.Scenario.NORMAL),
                OBSERVED.plusSeconds(60)).batchId();
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
                SELECT id, 'TRADING_CALENDAR',
                       'TRADING_CALENDAR_OBSERVATION_V1',
                       'TRADING_CALENDAR|SZSE|2026-07-28',
                       1, NULL, source_code, 'CALENDAR:SZSE',
                       NULL, NULL, NULL, NULL, NULL,
                       observed_at, observed_at + INTERVAL '1 second',
                       recorded_at + INTERVAL '2 seconds',
                       repeat('7', 64), repeat('8', 64),
                       revision_qualification, assurance_level,
                       usage_qualification, formal_eligible,
                       local_persistence_allowed,
                       historical_replay_allowed, backtest_allowed,
                       agent_use_allowed, '{}'::jsonb
                FROM pit_market_fact_batches WHERE id=?
                """, systemBatch));

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
                SELECT id, 'TRADING_CALENDAR',
                       'TRADING_CALENDAR_OBSERVATION_V1',
                       'TRADING_CALENDAR|SZSE|2026-07-28',
                       1, NULL, source_code, 'CALENDAR:SZSE',
                       provider_dataset_version, provider_revision,
                       provider_snapshot_id, provider_published_at,
                       provider_updated_at, first_observed_at,
                       provider_published_at + INTERVAL '1 second',
                       recorded_at, repeat('9', 64), repeat('a', 64),
                       revision_qualification, assurance_level,
                       usage_qualification, formal_eligible,
                       local_persistence_allowed,
                       historical_replay_allowed, backtest_allowed,
                       agent_use_allowed, '{}'::jsonb
                FROM pit_market_fact_batches WHERE id=?
                """, verifiedBatch));
    }

    @Test
    void usesFactSpecificSourceIdentitiesAndReusableExchangeCalendar() {
        String calendarCountSql = """
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='TRADING_CALENDAR'
                  AND source_instrument_id='CALENDAR:SZSE'
                  AND natural_key='TRADING_CALENDAR|SZSE|2026-07-27'
                """;
        captureService.capture(
                response(
                        "000012", "SZSE",
                        MockMarketFactProvider.Scenario.NORMAL),
                OBSERVED);
        long afterFirst = count(calendarCountSql);
        captureService.capture(
                response(
                        "000013", "SZSE",
                        MockMarketFactProvider.Scenario.NORMAL),
                OBSERVED.plusSeconds(60));
        assertTrue(afterFirst >= 1);
        assertEquals(afterFirst, count(calendarCountSql));
        assertTrue(calculate(
                "000012", "SZSE", CUTOFF).available());
        assertTrue(calculate(
                "000013", "SZSE", CUTOFF).available());
        assertUnavailable(
                qfqEngine.calculate(
                        "000012", "SZSE",
                        MockMarketFactProvider.PROVIDER_CODE,
                        new PitMarketFactModels.QfqSourceIdentities(
                                MockMarketFactProvider.rawSourceIdentity(
                                        "000012", "SZSE"),
                                MockMarketFactProvider.factorSourceIdentity(
                                        "000012", "SZSE"),
                                MockMarketFactProvider.calendarSourceIdentity(
                                        "SSE"),
                                MockMarketFactProvider
                                        .corporateActionSourceIdentity(
                                                "000012", "SZSE")),
                        END, CUTOFF),
                PitMarketFactsContracts.CALENDAR_UNAVAILABLE);
        assertUnavailable(
                qfqEngine.calculate(
                        "000012", "SZSE",
                        MockMarketFactProvider.PROVIDER_CODE,
                        MockMarketFactProvider.qfqSourceIdentities(
                                "000013", "SZSE"),
                        END, CUTOFF),
                PitMarketFactsContracts.RAW_BAR_UNAVAILABLE);
        var lineage = calculate("000012", "SZSE", CUTOFF);
        assertEquals(
                MockMarketFactProvider.rawSourceIdentity(
                        "000012", "SZSE"),
                lineage.sourceIdentities().rawSourceIdentity());
        assertEquals(
                MockMarketFactProvider.calendarSourceIdentity("SZSE"),
                lineage.calendarLineage().get(0).envelope()
                        .sourceInstrumentId());
    }

    @Test
    void appendsEverySemanticQualificationAndProviderMetadataChange() {
        String symbol = "600008";
        MarketFactResponse base = response(
                symbol, "SSE", MockMarketFactProvider.Scenario.NORMAL);
        captureService.capture(base, OBSERVED);
        ProviderCapability verified = verifiedCapability(base.capability());
        ProviderVersion revisionOne = verifiedVersion(
                "REVISION-1", "SNAPSHOT-1",
                Instant.parse("2026-07-27T07:05:00Z"),
                Instant.parse("2026-07-27T07:06:00Z"));
        captureService.capture(
                withProviderVersion(base, revisionOne, verified),
                OBSERVED.plusSeconds(60));
        ProviderVersion snapshotChanged = verifiedVersion(
                "REVISION-1", "SNAPSHOT-2",
                Instant.parse("2026-07-27T07:05:00Z"),
                Instant.parse("2026-07-27T07:06:00Z"));
        captureService.capture(
                withProviderVersion(base, snapshotChanged, verified),
                OBSERVED.plusSeconds(120));
        ProviderVersion publishedChanged = verifiedVersion(
                "REVISION-2", "SNAPSHOT-2",
                Instant.parse("2026-07-27T07:15:00Z"),
                Instant.parse("2026-07-27T07:16:00Z"));
        captureService.capture(
                withProviderVersion(base, publishedChanged, verified),
                OBSERVED.plusSeconds(180));
        Instant oldCutoff = Instant.parse("2026-07-27T07:10:00Z");
        Instant newCutoff = Instant.parse("2026-07-27T07:30:00Z");
        var oldResult = calculate(symbol, "SSE", oldCutoff);
        var newResult = calculate(symbol, "SSE", newCutoff);
        assertTrue(oldResult.available(), oldResult.reasonCode());
        assertTrue(newResult.available(), newResult.reasonCode());
        assertEquals(
                RevisionQualification.PROVIDER_VERIFIED,
                newResult.rawLineage().get(0).envelope()
                        .revisionQualification(),
                "a later lower qualification must not obscure verified facts");
        assertNotEquals(
                oldResult.rawLineage().get(0).envelope()
                        .canonicalContentHash(),
                newResult.rawLineage().get(0).envelope()
                        .canonicalContentHash());

        captureService.capture(
                withProviderVersion(
                        base, publishedChanged,
                        withAgentUseAllowed(verified, false)),
                OBSERVED.plusSeconds(240));
        captureService.capture(base, OBSERVED.plusSeconds(300));

        List<String> hashes = jdbc.queryForList("""
                SELECT canonical_content_hash
                FROM pit_market_fact_observations
                WHERE fact_type='RAW_DAILY_BAR'
                  AND source_code=?
                  AND source_instrument_id=?
                  AND natural_key=?
                ORDER BY chain_sequence
                """, String.class,
                MockMarketFactProvider.PROVIDER_CODE,
                MockMarketFactProvider.rawSourceIdentity(symbol, "SSE"),
                "RAW_DAILY_BAR|" + symbol + "|" + START);
        assertEquals(6, hashes.size());
        assertEquals(hashes.get(0), hashes.get(5),
                "qualification A→B→A must preserve three replayable versions");
        assertEquals(5, hashes.subList(0, 5).stream().distinct().count());

    }

    @Test
    void preservesOptionalFieldQualificationAndRejectsForgedZero() {
        MarketFactResponse original = response(
                "000014", "SZSE", MockMarketFactProvider.Scenario.NORMAL);
        List<RawDailyBar> bars = new ArrayList<>(original.rawDailyBars());
        RawDailyBar first = bars.get(0);
        bars.set(0, new RawDailyBar(
                first.sourceIdentity(), first.symbol(), first.exchange(),
                first.tradeDate(), first.open(), first.high(), first.low(),
                first.close(),
                missing(
                        MarketFieldUnit.SHARES,
                        MarketFieldSemantic.TRADED_VOLUME),
                missing(
                        MarketFieldUnit.CNY,
                        MarketFieldSemantic.TRADED_AMOUNT),
                missing(
                        MarketFieldUnit.RATIO,
                        MarketFieldSemantic.TURNOVER_RATE),
                first.version(), first.rawFields()));
        captureService.capture(
                copy(
                        original, bars, original.adjustmentFactors(),
                        original.tradingCalendar(),
                        original.corporateActions()),
                OBSERVED);
        var qfq = calculate("000014", "SZSE", CUTOFF);
        assertTrue(qfq.available(), qfq.reasonCode());
        assertEquals(FieldQualification.MISSING,
                qfq.bars().get(0).volume().qualification());
        assertEquals(FieldQualification.MISSING,
                qfq.bars().get(0).amount().qualification());
        assertEquals(FieldQualification.MISSING,
                qfq.bars().get(0).turnoverRate().qualification());
        assertEquals(0, count("""
                SELECT count(*)
                FROM raw_daily_bar_facts_v2
                WHERE symbol='000014'
                  AND trade_date=?
                  AND (
                    volume IS NOT NULL
                    OR amount IS NOT NULL
                    OR turnover_rate IS NOT NULL
                    OR volume_qualification <> 'MISSING'
                    OR amount_qualification <> 'MISSING'
                    OR turnover_rate_qualification <> 'MISSING'
                  )
                """, START));
        assertEquals(
                PitMarketFactsContracts.REQUIRED_MARKET_FIELD_UNAVAILABLE,
                backtestContextV2Service.create(
                                "000014", END, CUTOFF)
                        .path("reasonCode").asText());

        assertThrows(IllegalArgumentException.class, () ->
                new QualifiedMarketField(
                        BigDecimal.ZERO,
                        FieldQualification.MISSING,
                        MarketFieldUnit.SHARES,
                        MarketFieldSemantic.TRADED_VOLUME));

        RawDailyBar zero = new RawDailyBar(
                first.sourceIdentity(), first.symbol(), first.exchange(),
                first.tradeDate(), first.open(), first.high(), first.low(),
                first.close(),
                field(
                        BigDecimal.ZERO,
                        FieldQualification.PRESENT_VERIFIED,
                        MarketFieldUnit.SHARES,
                        MarketFieldSemantic.TRADED_VOLUME),
                first.amount(), first.turnoverRate(),
                first.version(), first.rawFields());
        assertEquals(BigDecimal.ZERO, zero.volume().value());

        MarketFactResponse zeroResponse = response(
                "000016", "SZSE", MockMarketFactProvider.Scenario.NORMAL);
        List<RawDailyBar> zeroBars =
                new ArrayList<>(zeroResponse.rawDailyBars());
        RawDailyBar zeroFirst = zeroBars.get(0);
        zeroBars.set(0, new RawDailyBar(
                zeroFirst.sourceIdentity(), zeroFirst.symbol(),
                zeroFirst.exchange(), zeroFirst.tradeDate(),
                zeroFirst.open(), zeroFirst.high(), zeroFirst.low(),
                zeroFirst.close(),
                field(
                        BigDecimal.ZERO,
                        FieldQualification.PRESENT_VERIFIED,
                        MarketFieldUnit.SHARES,
                        MarketFieldSemantic.TRADED_VOLUME),
                zeroFirst.amount(), zeroFirst.turnoverRate(),
                zeroFirst.version(), zeroFirst.rawFields()));
        captureService.capture(
                copy(
                        zeroResponse, zeroBars,
                        zeroResponse.adjustmentFactors(),
                        zeroResponse.tradingCalendar(),
                        zeroResponse.corporateActions()),
                OBSERVED.plusSeconds(120));
        assertEquals(1, count("""
                SELECT count(*)
                FROM raw_daily_bar_facts_v2 b
                JOIN pit_market_fact_observations o
                  ON o.id=b.observation_id
                WHERE b.symbol='000016'
                  AND b.trade_date=?
                  AND b.volume=0
                  AND b.volume_qualification='PRESENT_VERIFIED'
                """, START));

        long batchId = captureService.capture(
                response(
                        "000015", "SZSE",
                        MockMarketFactProvider.Scenario.NORMAL),
                OBSERVED.plusSeconds(60)).batchId();
        assertThrows(DataAccessException.class, () ->
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> {
                            long observationId = insertEnvelope(
                                    batchId,
                                    "RAW_DAILY_BAR",
                                    PitMarketFactsContracts
                                            .RAW_DAILY_BAR_CONTRACT,
                                    "RAW_DAILY_BAR|099999|" + START,
                                    "b", "c");
                            jdbc.update("""
                                    INSERT INTO raw_daily_bar_facts_v2(
                                        observation_id, symbol, exchange,
                                        trade_date, open, high, low, close,
                                        volume, volume_qualification,
                                        volume_unit_code,
                                        volume_semantic_code,
                                        amount, amount_qualification,
                                        amount_unit_code,
                                        amount_semantic_code,
                                        turnover_rate,
                                        turnover_rate_qualification,
                                        turnover_rate_unit_code,
                                        turnover_rate_semantic_code
                                    ) VALUES (
                                        ?, '099999', 'SZSE', ?,
                                        10, 11, 9, 10,
                                        0, 'MISSING', 'SHARES',
                                        'TRADED_VOLUME',
                                        NULL, 'MISSING', 'CNY',
                                        'TRADED_AMOUNT',
                                        NULL, 'MISSING', 'RATIO',
                                        'TURNOVER_RATE'
                                    )
                                    """, observationId, START);
                        }));
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
                first.sourceIdentity(),
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
                        value.sourceIdentity(),
                        value.symbol(),
                        value.factorEffectiveTradeDate(),
                        value.factorType(),
                        value.coverageMode(),
                        value.factorEffectiveTradeDate().equals(END)
                                ? value.factor().multiply(
                                new BigDecimal("2"))
                                : value.factor(),
                        value.version(),
                        value.rawFields()))
                .toList();
        List<CorporateAction> actions =
                new ArrayList<>(original.corporateActions());
        if (addExplainingAction) {
            ObjectNode terms = mapper.createObjectNode();
            terms.put("fixtureExplanation", "NEW_STOCK_DIVIDEND");
            actions.add(new CorporateAction(
                    MockMarketFactProvider.corporateActionSourceIdentity(
                            original.rawDailyBars().get(0).symbol(), "SSE"),
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

    private MarketFactResponse withProviderVersion(
            MarketFactResponse original,
            ProviderVersion version,
            ProviderCapability capability
    ) {
        List<RawDailyBar> bars = original.rawDailyBars().stream()
                .map(value -> new RawDailyBar(
                        value.sourceIdentity(), value.symbol(),
                        value.exchange(), value.tradeDate(),
                        value.open(), value.high(), value.low(), value.close(),
                        value.volume(), value.amount(), value.turnoverRate(),
                        version, value.rawFields()))
                .toList();
        List<AdjustmentFactor> factors =
                original.adjustmentFactors().stream()
                        .map(value -> new AdjustmentFactor(
                                value.sourceIdentity(), value.symbol(),
                                value.factorEffectiveTradeDate(),
                                value.factorType(), value.coverageMode(),
                                value.factor(), version, value.rawFields()))
                        .toList();
        var calendar = original.tradingCalendar().stream()
                .map(value ->
                        new MarketFactProviderModels.TradingCalendar(
                                value.sourceIdentity(), value.exchange(),
                                value.calendarDate(), value.open(),
                                value.sessionCode(), version,
                                value.rawFields()))
                .toList();
        List<CorporateAction> actions = original.corporateActions().stream()
                .map(value -> new CorporateAction(
                        value.sourceIdentity(), value.sourceActionId(),
                        value.symbol(), value.actionType(),
                        value.announcementDate(),
                        value.effectiveTradeDate(), value.terms(),
                        version, value.rawFields()))
                .toList();
        return new MarketFactResponse(
                original.providerContractVersion(),
                original.providerCode(),
                original.adapterVersion(),
                original.runNamespace(),
                original.sourceCode(),
                original.sourceInstrumentId(),
                original.requestedStart(),
                original.requestedEnd(),
                original.complete(),
                capability,
                bars,
                factors,
                calendar,
                actions,
                original.errors(),
                original.providerMetadata());
    }

    private MarketFactResponse withUnrelatedAction(
            MarketFactResponse value,
            LocalDate effectiveDate
    ) {
        List<CorporateAction> actions =
                new ArrayList<>(value.corporateActions());
        ObjectNode terms = mapper.createObjectNode();
        terms.put("fixtureExplanation", "UNRELATED_ACTION");
        actions.add(new CorporateAction(
                MockMarketFactProvider.corporateActionSourceIdentity(
                        value.rawDailyBars().get(0).symbol(), "SSE"),
                "MOCK-UNRELATED-ACTION",
                value.rawDailyBars().get(0).symbol(),
                CorporateActionType.OTHER,
                effectiveDate.minusDays(1),
                effectiveDate,
                terms,
                value.adjustmentFactors().get(0).version(),
                terms.deepCopy()));
        return copy(
                value,
                value.rawDailyBars(),
                value.adjustmentFactors(),
                value.tradingCalendar(),
                actions);
    }

    private static ProviderVersion verifiedVersion(
            String revision,
            String snapshot,
            Instant publishedAt,
            Instant updatedAt
    ) {
        return new ProviderVersion(
                "PROVIDER-DATASET-1",
                revision,
                snapshot,
                publishedAt,
                updatedAt,
                RevisionQualification.PROVIDER_VERIFIED);
    }

    private static ProviderCapability verifiedCapability(
            ProviderCapability value
    ) {
        return new ProviderCapability(
                value.providerContractVersion(),
                value.providerCode(),
                value.adapterVersion(),
                value.supportedFactTypes(),
                true,
                true,
                true,
                true,
                value.historicalVersionsQueryable(),
                value.localPersistenceAllowed(),
                value.historicalReplayAllowed(),
                value.backtestAllowed(),
                value.agentUseAllowed(),
                value.maximumSymbolsPerRequest(),
                value.maximumNaturalDaysPerRequest(),
                value.minimumRequestInterval(),
                value.fieldUnits(),
                value.decimalScales(),
                value.coverage(),
                value.licensing(),
                value.rateLimit());
    }

    private static ProviderCapability withAgentUseAllowed(
            ProviderCapability value,
            boolean agentUseAllowed
    ) {
        return new ProviderCapability(
                value.providerContractVersion(),
                value.providerCode(),
                value.adapterVersion(),
                value.supportedFactTypes(),
                value.revisionIdAvailable(),
                value.snapshotIdAvailable(),
                value.providerPublishedAtAvailable(),
                value.providerUpdatedAtAvailable(),
                value.historicalVersionsQueryable(),
                value.localPersistenceAllowed(),
                value.historicalReplayAllowed(),
                value.backtestAllowed(),
                agentUseAllowed,
                value.maximumSymbolsPerRequest(),
                value.maximumNaturalDaysPerRequest(),
                value.minimumRequestInterval(),
                value.fieldUnits(),
                value.decimalScales(),
                value.coverage(),
                value.licensing(),
                value.rateLimit());
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
                MockMarketFactProvider.qfqSourceIdentities(
                        symbol, exchange),
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

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private static QualifiedMarketField field(
            BigDecimal value,
            FieldQualification qualification,
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new QualifiedMarketField(
                value, qualification, unit, semantic);
    }

    private static QualifiedMarketField missing(
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new QualifiedMarketField(
                null, FieldQualification.MISSING, unit, semantic);
    }
}
