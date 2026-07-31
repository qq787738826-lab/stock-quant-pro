package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.F1eSyntheticTushareGateway;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchAuthorization;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchService;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProperties;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(
        named = F1eDedicatedPostgresTestEnvironment.URL_VARIABLE,
        matches = ".+")
@EnabledIfEnvironmentVariable(
        named = F1eDedicatedPostgresTestEnvironment.USER_VARIABLE,
        matches = ".+")
@EnabledIfEnvironmentVariable(
        named = F1eDedicatedPostgresTestEnvironment.PASSWORD_VARIABLE,
        matches = ".+")
class AgentStage3AR3BF1ETushareDedicatedResearchPostgresIntegrationTest {

    private static F1eDedicatedPostgresTestEnvironment environment;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PitMarketFactCaptureService captureService;
    @Autowired TushareDedicatedResearchPersistenceGuard guard;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        environment =
                F1eDedicatedPostgresTestEnvironment.register(registry);
        registry.add("stockquant.agent-team.enabled", () -> false);
        registry.add("stockquant.agent-team.shadow.enabled", () -> false);
        registry.add(
                "stockquant.agent-team.shadow.scheduler-enabled",
                () -> false);
        registry.add(
                "stockquant.announcement.akshare.enabled",
                () -> false);
        registry.add(
                "stockquant.market-facts.tushare.mode",
                () -> "DISABLED");
        registry.add(
                "stockquant.market-facts.tushare."
                        + "f1e-dedicated-database-purpose",
                () -> TushareDedicatedResearchPersistenceGuard
                        .DATABASE_PURPOSE);
    }

    @AfterAll
    static void cleanup() {
        if (environment != null) {
            environment.close();
        }
    }

    @Test
    @Order(1)
    void dedicatedDatabaseHasExactIdentitySearchPathAndV1ToV13() {
        assertEquals("stock_quant_research",
                jdbc.queryForObject(
                        "SELECT current_database()", String.class));
        assertEquals("stock_quant_research",
                jdbc.queryForObject(
                        "SELECT current_user", String.class));
        assertEquals("tushare_research",
                jdbc.queryForObject(
                        "SELECT current_schema()", String.class));
        assertEquals("tushare_research",
                jdbc.queryForObject(
                        "SELECT current_setting('search_path')",
                        String.class));
        assertEquals(
                TushareDedicatedResearchPersistenceGuard
                        .REQUIRED_MIGRATIONS,
                jdbc.queryForList("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank
                        """, String.class));
        assertEquals(
                environment.baseline(),
                environment.currentPublicFingerprint());
    }

    @Test
    @Order(2)
    void oneTwoAndThreeSymbolBatchesUseExactBudgetsAndSinglePid() {
        int observationsBefore = count(
                "SELECT count(*) FROM pit_market_fact_observations");
        for (int symbols = 1; symbols <= 3; symbols++) {
            F1eSyntheticTushareGateway gateway =
                    new F1eSyntheticTushareGateway();
            LocalDate date = LocalDate.of(2026, 7, 19 + symbols);
            var result = runtime(
                    gateway,
                    Instant.parse(
                            "2026-07-31T04:0" + symbols + ":00Z"))
                    .run(
                            authorization(),
                            command(date, selections(symbols)));

            assertEquals(symbols * 3, gateway.calls());
            assertEquals(symbols * 3,
                    result.providerCallCount());
            assertEquals(symbols * 3,
                    result.sessionConsumedRequests());
            assertEquals(0, result.retryCount());
            assertEquals(
                    result.databaseIdentity().backendPidBefore(),
                    result.databaseIdentity().backendPidAfter());
            assertEquals("stock_quant_research",
                    result.databaseIdentity().currentDatabase());
            assertEquals("tushare_research",
                    result.databaseIdentity().currentSchema());
            assertFalse(result.runtimeEligibility()
                    .productionEligible()
                    == com.stockquant.server.agent.marketfacts
                    .TushareDedicatedResearchBatchModels
                    .Eligibility.YES);
        }
        assertTrue(count(
                "SELECT count(*) FROM pit_market_fact_observations")
                > observationsBefore);
    }

    @Test
    @Order(3)
    void providerFailureOnSecondSymbolProducesZeroWrites() {
        int batchesBefore = count(
                "SELECT count(*) FROM pit_market_fact_batches");
        int observationsBefore = count(
                "SELECT count(*) FROM pit_market_fact_observations");
        F1eSyntheticTushareGateway gateway =
                new F1eSyntheticTushareGateway().failAtCall(4);

        assertThrows(
                TushareDedicatedResearchBatchService
                        .RuntimeBlockedException.class,
                () -> runtime(
                        gateway,
                        Instant.parse("2026-07-31T04:10:00Z"))
                        .run(
                                authorization(),
                                command(
                                        LocalDate.of(2026, 7, 23),
                                        selections(2))));

        assertEquals(4, gateway.calls());
        assertEquals(batchesBefore, count(
                "SELECT count(*) FROM pit_market_fact_batches"));
        assertEquals(observationsBefore, count(
                "SELECT count(*) FROM pit_market_fact_observations"));
    }

    @Test
    @Order(4)
    void thirdCaptureFailureRollsBackWholeBatch() {
        int batchesBefore = count(
                "SELECT count(*) FROM pit_market_fact_batches");
        int observationsBefore = count(
                "SELECT count(*) FROM pit_market_fact_observations");
        jdbc.execute("""
                CREATE FUNCTION f1e_fail_third_capture()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.source_instrument_id LIKE '%600001.SH' THEN
                        RAISE EXCEPTION 'synthetic third capture failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_f1e_fail_third_capture
                BEFORE INSERT ON pit_market_fact_observations
                FOR EACH ROW
                EXECUTE FUNCTION f1e_fail_third_capture()
                """);
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> runtime(
                            new F1eSyntheticTushareGateway(),
                            Instant.parse("2026-07-31T04:11:00Z"))
                            .run(
                                    authorization(),
                                    command(
                                            LocalDate.of(2026, 7, 24),
                                            selections(3))));
        } finally {
            jdbc.execute("""
                    DROP TRIGGER IF EXISTS
                    trg_f1e_fail_third_capture
                    ON pit_market_fact_observations
                    """);
            jdbc.execute("""
                    DROP FUNCTION IF EXISTS
                    f1e_fail_third_capture()
                    """);
        }
        assertEquals(batchesBefore, count(
                "SELECT count(*) FROM pit_market_fact_batches"));
        assertEquals(observationsBefore, count(
                "SELECT count(*) FROM pit_market_fact_observations"));
    }

    @Test
    @Order(5)
    void searchPathChangeBeforeCommitRollsBackWholeBatch() {
        int batchesBefore = count(
                "SELECT count(*) FROM pit_market_fact_batches");
        int observationsBefore = count(
                "SELECT count(*) FROM pit_market_fact_observations");
        jdbc.execute("""
                CREATE FUNCTION f1e_change_search_path()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM set_config('search_path', 'public', true);
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_f1e_change_search_path
                AFTER INSERT ON trading_calendar_facts_v1
                FOR EACH ROW
                EXECUTE FUNCTION f1e_change_search_path()
                """);
        try {
            var error = assertThrows(
                    TushareDedicatedResearchPersistenceGuard
                            .GuardException.class,
                    () -> runtime(
                            new F1eSyntheticTushareGateway(),
                            Instant.parse("2026-07-31T04:12:00Z"))
                            .run(
                                    authorization(),
                                    command(
                                            LocalDate.of(2026, 7, 27),
                                            selections(1))));
            assertEquals(
                    "TUSHARE_DEDICATED_RESEARCH_PUBLIC_SCHEMA_FORBIDDEN",
                    error.safeCode());
        } finally {
            jdbc.execute("""
                    DROP TRIGGER IF EXISTS
                    trg_f1e_change_search_path
                    ON trading_calendar_facts_v1
                    """);
            jdbc.execute("""
                    DROP FUNCTION IF EXISTS
                    f1e_change_search_path()
                    """);
        }
        assertEquals(batchesBefore, count(
                "SELECT count(*) FROM pit_market_fact_batches"));
        assertEquals(observationsBefore, count(
                "SELECT count(*) FROM pit_market_fact_observations"));
    }

    @Test
    @Order(6)
    void forwardCaptureIsIdempotentThenAppendsContentChange() {
        LocalDate date = LocalDate.of(2026, 7, 28);
        List<SecuritySelection> selection = selections(1);
        var first = runtime(
                new F1eSyntheticTushareGateway(),
                Instant.parse("2026-07-31T04:13:00Z"))
                .run(authorization(), command(date, selection));
        var repeated = runtime(
                new F1eSyntheticTushareGateway(),
                Instant.parse("2026-07-31T04:14:00Z"))
                .run(authorization(), command(date, selection));
        var changed = runtime(
                new F1eSyntheticTushareGateway()
                        .changedCloseDelta(2),
                Instant.parse("2026-07-31T04:15:00Z"))
                .run(authorization(), command(date, selection));

        assertEquals(3, first.appendedCount());
        assertEquals(0, first.idempotentCount());
        assertEquals(0, repeated.appendedCount());
        assertEquals(3, repeated.idempotentCount());
        assertEquals(1, changed.appendedCount());
        assertEquals(2, changed.idempotentCount());
        assertEquals(2, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='RAW_DAILY_BAR'
                  AND natural_key LIKE '%2026-07-28%'
                """));
        assertEquals(2, count("""
                SELECT max(chain_sequence)
                FROM pit_market_fact_observations
                WHERE fact_type='RAW_DAILY_BAR'
                  AND natural_key LIKE '%2026-07-28%'
                """));
        assertEquals(0, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='CORPORATE_ACTION'
                """));
        assertEquals(
                environment.baseline(),
                environment.currentPublicFingerprint());
    }

    private TushareDedicatedResearchBatchService runtime(
            F1eSyntheticTushareGateway gateway,
            Instant observedAt
    ) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken("synthetic-f1e-integration-token");
        TushareMarketFactProvider provider =
                new TushareMarketFactProvider(
                        mapper, properties, gateway);
        return new TushareDedicatedResearchBatchService(
                provider,
                guard,
                captureService,
                Clock.fixed(observedAt, ZoneOffset.UTC));
    }

    private static TushareDedicatedResearchBatchAuthorization
    authorization() {
        return TushareDedicatedResearchBatchAuthorization
                .manualPersonalResearch();
    }

    private static TushareDedicatedResearchBatchCommand command(
            LocalDate date,
            List<SecuritySelection> securities
    ) {
        return new TushareDedicatedResearchBatchCommand(
                date, securities, Duration.ofSeconds(5));
    }

    private static List<SecuritySelection> selections(int count) {
        return List.of(
                new SecuritySelection("600000", "SSE"),
                new SecuritySelection("000001", "SZSE"),
                new SecuritySelection("600001", "SSE"))
                .subList(0, count);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
