package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService;
import com.stockquant.server.agent.marketfacts.TushareApiGateway;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProperties;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProvider;
import com.stockquant.server.agent.marketfacts.TushareManualBoundedSession;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
class AgentStage3AR3BF1ATusharePostgresIntegrationTest {

    private static final LocalDate TRADE_DATE =
            LocalDate.of(2026, 7, 27);
    private static final Instant OBSERVED_AT =
            Instant.parse("2026-07-27T08:30:00Z");
    private static AgentPostgresTestEnvironment.IsolatedSchema isolated;

    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PitMarketFactCaptureService captureService;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        bootstrapEphemeralPublicV12();
        isolated = AgentPostgresTestEnvironment.registerIsolatedDataSource(
                registry, "tushare_f1a");
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
    void capturesLimitedFormalFactsAsResearchOnlySystemKnowledge() {
        SyntheticGateway gateway = new SyntheticGateway();
        TushareMarketFactProvider provider = provider(gateway);
        MarketFactRequest request = request();

        var response = provider.fetchForControlledAcceptance(
                request, session());
        assertTrue(response.complete());
        assertEquals(3, response.recordCount());
        var first = captureService.capture(response, OBSERVED_AT);
        assertEquals(3, first.appendedCount());
        assertEquals(0, first.idempotentCount());

        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7",
                        "8", "9", "10", "11", "12", "13"),
                jdbc.queryForList("""
                        SELECT version FROM flyway_schema_history
                        WHERE success ORDER BY installed_rank
                        """, String.class));
        assertEquals(1, count("""
                SELECT count(*) FROM pit_market_fact_batches
                WHERE id=%d
                  AND run_namespace='FORMAL'
                  AND capture_mode='PROVIDER_CAPTURE'
                  AND source_code='TUSHARE_PRO'
                  AND revision_qualification='SYSTEM_KNOWLEDGE_ONLY'
                  AND assurance_level='SYSTEM_KNOWLEDGE_PIT'
                  AND usage_qualification='RESEARCH_ONLY'
                  AND NOT formal_eligible
                  AND local_persistence_allowed
                  AND historical_replay_allowed
                  AND backtest_allowed
                  AND agent_use_allowed
                  AND response_complete
                """.formatted(first.batchId())));
        assertEquals(3, count("""
                SELECT count(*) FROM pit_market_fact_observations
                WHERE batch_id=%d
                  AND first_observed_at=known_at
                  AND provider_dataset_version IS NULL
                  AND provider_revision IS NULL
                  AND provider_snapshot_id IS NULL
                  AND provider_published_at IS NULL
                  AND provider_updated_at IS NULL
                """.formatted(first.batchId())));
        assertEquals(
                List.of(
                        "TUSHARE:ADJ_FACTOR:600000.SH",
                        "TUSHARE:SECURITY:600000.SH",
                        "TUSHARE:TRADE_CAL:SSE"),
                jdbc.queryForList("""
                        SELECT source_instrument_id
                        FROM pit_market_fact_observations
                        WHERE batch_id=?
                        ORDER BY source_instrument_id
                        """, String.class, first.batchId()));

        var repeated = captureService.capture(
                provider.fetchForControlledAcceptance(
                        request, session()),
                OBSERVED_AT.plusSeconds(60));
        assertEquals(0, repeated.appendedCount());
        assertEquals(3, repeated.idempotentCount());
        assertEquals(3, count("""
                SELECT count(*) FROM pit_market_fact_observations
                WHERE source_code='TUSHARE_PRO'
                """));
    }

    @Test
    void partialProviderResponseCreatesNoFactObservations() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.failFactor = true;
        TushareMarketFactProvider provider = provider(gateway);
        var response = provider.fetchForControlledAcceptance(
                request(), session());
        assertFalse(response.complete());
        assertEquals(1, response.recordCount());
        assertEquals(1, response.errors().size());

        var result = captureService.capture(
                response, OBSERVED_AT.plusSeconds(120));
        assertFalse(result.complete());
        assertEquals(0, result.appendedCount());
        assertEquals(0, result.idempotentCount());
        assertEquals(0, count("""
                SELECT count(*) FROM pit_market_fact_observations
                WHERE batch_id=%d
                """.formatted(result.batchId())));
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

    private static MarketFactRequest request() {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        "600000", "SSE"),
                "600000",
                "SSE",
                TRADE_DATE,
                TRADE_DATE,
                Set.of(
                        FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR,
                        FactType.TRADING_CALENDAR),
                Duration.ofSeconds(5));
    }

    private static TushareManualBoundedSession session() {
        return new TushareManualBoundedSession(
                10,
                Set.of("600000.SH"),
                Set.of("SSE"),
                TRADE_DATE,
                TRADE_DATE,
                Set.of("daily", "adj_factor", "trade_cal"),
                false,
                0);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static final class SyntheticGateway
            implements TushareApiGateway {
        private boolean failFactor;

        @Override
        public QueryResult query(
                String endpoint,
                ObjectNode parameters,
                List<String> fields,
                Duration timeout,
                QueryMode mode,
                TushareManualBoundedSession session
        ) {
            if (failFactor && "adj_factor".equals(endpoint)) {
                throw new GatewayException(
                        ErrorKind.PERMISSION_DENIED,
                        "TUSHARE_PERMISSION_DENIED",
                        "synthetic permission failure",
                        1,
                        0,
                        null);
            }
            List<List<JsonNode>> rows = switch (endpoint) {
                case "daily" -> List.of(List.of(
                        text("600000.SH"),
                        text("20260727"),
                        decimal("10.10"),
                        decimal("10.30"),
                        decimal("10.00"),
                        decimal("10.20"),
                        decimal("1000"),
                        decimal("100")));
                case "adj_factor" -> List.of(List.of(
                        text("600000.SH"),
                        text("20260727"),
                        decimal("1.20")));
                case "trade_cal" -> List.of(List.of(
                        text("SSE"),
                        text("20260727"),
                        DecimalNode.valueOf(BigDecimal.ONE),
                        text("20260724")));
                default -> throw new IllegalArgumentException(endpoint);
            };
            return new QueryResult(new Table(fields, rows), 1, 0);
        }

        private static JsonNode text(String value) {
            return TextNode.valueOf(value);
        }

        private static JsonNode decimal(String value) {
            return DecimalNode.valueOf(new BigDecimal(value));
        }
    }
}
