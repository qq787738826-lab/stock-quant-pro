package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.F1cSyntheticTushareGateway;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService;
import com.stockquant.server.agent.marketfacts.TushareApiGateway;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProperties;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProvider;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.RunCommand;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.RuntimeQualification;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchPersistenceGuard;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchRuntimeAuthorization;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchRuntimeService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(
        named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentStage3AR3BF1CTushareReducedRuntimePostgresIntegrationTest {

    private static final LocalDate START =
            LocalDate.of(2026, 7, 27);
    private static final LocalDate END =
            LocalDate.of(2026, 7, 28);
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-30T08:00:00Z");
    private static AgentPostgresTestEnvironment.IsolatedSchema isolated;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PitMarketFactCaptureService captureService;
    @Autowired TushareReducedResearchPersistenceGuard persistenceGuard;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        bootstrapEphemeralPublicV12();
        isolated = AgentPostgresTestEnvironment
                .registerF1cIsolatedDataSource(registry);
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
    }

    private static void bootstrapEphemeralPublicV12() {
        if (!"true".equalsIgnoreCase(System.getenv(
                "STOCK_QUANT_TEST_EPHEMERAL_BOOTSTRAP"))) {
            return;
        }
        String url = System.getenv("STOCK_QUANT_TEST_DB_URL");
        if (!AgentPostgresTestEnvironment.EPHEMERAL_LOCAL_URL
                .equals(url)) {
            throw new IllegalStateException(
                    "ephemeral bootstrap is restricted to the fixed "
                            + "local PostgreSQL test port");
        }
        String username =
                System.getenv("STOCK_QUANT_TEST_DB_USERNAME");
        String password =
                System.getenv("STOCK_QUANT_TEST_DB_PASSWORD");
        AgentPostgresTestEnvironment.validate(
                url, username, password);
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .target("12")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @AfterAll
    static void cleanup() {
        if (isolated != null) {
            isolated.close();
        }
    }

    @Test
    void runsFormulaOnlyResearchAndCapturesOnlyThreeFactTypes() {
        F1cSyntheticTushareGateway gateway =
                new F1cSyntheticTushareGateway();
        TushareMarketFactProvider provider = provider(gateway);
        TushareReducedResearchRuntimeService runtime =
                new TushareReducedResearchRuntimeService(
                        provider,
                        persistenceGuard,
                        captureService,
                        Clock.fixed(OBSERVED_AT, ZoneOffset.UTC));

        var first = runtime.run(
                TushareReducedResearchRuntimeAuthorization
                        .f1cIsolatedManual(),
                command());
        TushareReducedResearchRuntimeService repeatedRuntime =
                new TushareReducedResearchRuntimeService(
                        provider,
                        persistenceGuard,
                        captureService,
                        Clock.fixed(
                                OBSERVED_AT.plusSeconds(60),
                                ZoneOffset.UTC));
        var second = repeatedRuntime.run(
                TushareReducedResearchRuntimeAuthorization
                        .f1cIsolatedManual(),
                command());

        assertTrue(isolated.schema().matches(
                "^f1c_tushare_research_[0-9a-f]{32}$"));
        assertEquals(List.of(
                        "1", "2", "3", "4", "5", "6", "7",
                        "8", "9", "10", "11", "12", "13"),
                jdbc.queryForList("""
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                        ORDER BY installed_rank
                        """, String.class));
        assertEquals(isolated.schema(),
                jdbc.queryForObject(
                        "SELECT current_schema()", String.class));
        assertFalse(jdbc.queryForObject(
                "SELECT current_schema()='public'", Boolean.class));

        assertEquals(RuntimeQualification.REDUCED_RESEARCH_FORMULA_ONLY,
                first.runtimeQualification());
        assertEquals(3, first.providerCallCount());
        assertEquals(0, first.retryCount());
        assertEquals(6, first.captureResult().appendedCount());
        assertEquals(0, first.captureResult().idempotentCount());
        assertEquals(0, second.captureResult().appendedCount());
        assertEquals(6, second.captureResult().idempotentCount());
        assertEquals(first.qfqBars(), second.qfqBars());
        assertFalse(first.corporateActionLineageComplete());
        assertFalse(first.fullQfqEligible());
        assertFalse(first.productionEligible());
        assertFalse(first.agentDecisionEligible());
        assertFalse(first.backtestExecutionEligible());
        assertFalse(first.tradingEligible());

        assertEquals(2, count("""
                SELECT count(*)
                FROM pit_market_fact_batches
                WHERE source_code='TUSHARE_PRO'
                  AND run_namespace='FORMAL'
                  AND usage_qualification='RESEARCH_ONLY'
                  AND NOT formal_eligible
                  AND revision_qualification='SYSTEM_KNOWLEDGE_ONLY'
                  AND assurance_level='SYSTEM_KNOWLEDGE_PIT'
                  AND response_complete
                """));
        assertEquals(6, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                """));
        assertEquals(2, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='RAW_DAILY_BAR'
                """));
        assertEquals(2, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='ADJUSTMENT_FACTOR'
                """));
        assertEquals(2, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='TRADING_CALENDAR'
                """));
        assertEquals(0, count("""
                SELECT count(*)
                FROM pit_market_fact_observations
                WHERE fact_type='CORPORATE_ACTION'
                """));
        assertEquals(
                List.of(
                        "daily", "adj_factor", "trade_cal",
                        "daily", "adj_factor", "trade_cal"),
                gateway.endpoints());
        assertEquals(6, gateway.calls());
    }

    private TushareMarketFactProvider provider(
            TushareApiGateway gateway
    ) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken("synthetic-integration-token");
        return new TushareMarketFactProvider(
                mapper, properties, gateway);
    }

    private static RunCommand command() {
        return new RunCommand(
                "600000",
                "SSE",
                START,
                END,
                END,
                Duration.ofSeconds(5));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

}
