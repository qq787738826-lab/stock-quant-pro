package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.marketfacts.LimitedPersonalFormalCaptureAuthorization;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateAction;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateActionType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MockMarketFactProvider;
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
        assertThrows(
                IllegalArgumentException.class,
                () -> captureService.capture(response, OBSERVED_AT));
        var first = captureService.captureAuthorizedLimitedPersonalFormal(
                response, OBSERVED_AT, authorization());
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

        var repeated =
                captureService.captureAuthorizedLimitedPersonalFormal(
                provider.fetchForControlledAcceptance(
                        request, session()),
                OBSERVED_AT.plusSeconds(60),
                authorization());
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

        var result =
                captureService.captureAuthorizedLimitedPersonalFormal(
                        response,
                        OBSERVED_AT.plusSeconds(120),
                        authorization());
        assertFalse(result.complete());
        assertEquals(0, result.appendedCount());
        assertEquals(0, result.idempotentCount());
        assertEquals(0, count("""
                SELECT count(*) FROM pit_market_fact_observations
                WHERE batch_id=%d
                """.formatted(result.batchId())));
    }

    @Test
    void rejectsForgedFormalAuthorizationBeforeAnyPersistence() {
        MarketFactResponse original = provider(new SyntheticGateway())
                .fetchForControlledAcceptance(request(), session());
        int before = count("""
                SELECT count(*) FROM pit_market_fact_batches
                """);

        assertAuthorizedRejected(fakeProvider(original));

        ObjectNode missingUserAuthorization =
                original.capability().licensing().deepCopy();
        missingUserAuthorization.remove(
                "userPersonalUseImplementationAuthorization");
        assertAuthorizedRejected(withLicensing(
                original, missingUserAuthorization));

        assertAuthorizedRejected(withAdapterVersion(
                original, "FORGED_TUSHARE_ADAPTER"));

        ObjectNode formalEligible =
                original.capability().licensing().deepCopy();
        formalEligible.put("formalEligible", true);
        assertAuthorizedRejected(withLicensing(
                original, formalEligible));

        for (String invalidUsage :
                List.of("LICENSED_INTERNAL", "TEST_DEMO_ONLY")) {
            ObjectNode licensing =
                    original.capability().licensing().deepCopy();
            licensing.put("usageQualification", invalidUsage);
            assertAuthorizedRejected(withLicensing(
                    original, licensing));
        }

        assertAuthorizedRejected(withCorporateAction(original));
        assertAuthorizedRejected(withProviderVerifiedFact(original));

        assertEquals(before, count("""
                SELECT count(*) FROM pit_market_fact_batches
                """));
    }

    @Test
    void genericCaptureKeepsTestAndDemoFixturePaths() {
        captureMockFixture(
                RunNamespace.TEST, "600000", "SSE", OBSERVED_AT);
        captureMockFixture(
                RunNamespace.DEMO, "000001", "SZSE",
                OBSERVED_AT.plusSeconds(30));

        assertEquals(1, count("""
                SELECT count(*) FROM pit_market_fact_batches
                WHERE source_code='MOCK_PIT_MARKET_FACTS_V2'
                  AND run_namespace='TEST'
                  AND capture_mode='TEST_FIXTURE'
                """));
        assertEquals(1, count("""
                SELECT count(*) FROM pit_market_fact_batches
                WHERE source_code='MOCK_PIT_MARKET_FACTS_V2'
                  AND run_namespace='DEMO'
                  AND capture_mode='DEMO_FIXTURE'
                """));
    }

    private void captureMockFixture(
            RunNamespace namespace,
            String symbol,
            String exchange,
            Instant observedAt
    ) {
        MockMarketFactProvider provider = new MockMarketFactProvider(
                mapper, MockMarketFactProvider.Scenario.NORMAL);
        MarketFactRequest request = new MarketFactRequest(
                namespace,
                MockMarketFactProvider.PROVIDER_CODE,
                "MOCK:" + symbol + ":" + exchange,
                symbol,
                exchange,
                TRADE_DATE,
                TRADE_DATE,
                Set.of(
                        FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR,
                        FactType.TRADING_CALENDAR),
                Duration.ofSeconds(5));
        var result = captureService.capture(
                provider.fetch(request), observedAt);
        assertTrue(result.complete());
        assertTrue(result.appendedCount() > 0);
    }

    private void assertAuthorizedRejected(MarketFactResponse response) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> captureService
                        .captureAuthorizedLimitedPersonalFormal(
                                response,
                                OBSERVED_AT.plusSeconds(240),
                                authorization()));
        assertTrue(error.getMessage().contains(
                "TUSHARE_LIMITED_PERSONAL_FORMAL_AUTHORIZATION_INVALID"));
    }

    private MarketFactResponse fakeProvider(MarketFactResponse original) {
        ProviderCapability capability = copyCapability(
                original.capability(),
                "FORGED_PROVIDER",
                original.adapterVersion(),
                original.capability().supportedFactTypes(),
                original.capability().coverage(),
                original.capability().licensing());
        return copyResponse(
                original,
                "FORGED_PROVIDER",
                original.adapterVersion(),
                "FORGED_PROVIDER",
                capability,
                original.rawDailyBars(),
                original.corporateActions());
    }

    private MarketFactResponse withAdapterVersion(
            MarketFactResponse original,
            String adapterVersion
    ) {
        ProviderCapability capability = copyCapability(
                original.capability(),
                original.providerCode(),
                adapterVersion,
                original.capability().supportedFactTypes(),
                original.capability().coverage(),
                original.capability().licensing());
        return copyResponse(
                original,
                original.providerCode(),
                adapterVersion,
                original.sourceCode(),
                capability,
                original.rawDailyBars(),
                original.corporateActions());
    }

    private MarketFactResponse withLicensing(
            MarketFactResponse original,
            JsonNode licensing
    ) {
        ProviderCapability capability = copyCapability(
                original.capability(),
                original.providerCode(),
                original.adapterVersion(),
                original.capability().supportedFactTypes(),
                original.capability().coverage(),
                licensing);
        return copyResponse(
                original,
                original.providerCode(),
                original.adapterVersion(),
                original.sourceCode(),
                capability,
                original.rawDailyBars(),
                original.corporateActions());
    }

    private MarketFactResponse withCorporateAction(
            MarketFactResponse original
    ) {
        CorporateAction action = new CorporateAction(
                "TUSHARE:FORGED_ACTION:600000.SH",
                "FORGED_ACTION",
                "600000",
                CorporateActionType.CASH_DIVIDEND,
                TRADE_DATE,
                TRADE_DATE,
                mapper.createObjectNode(),
                systemKnowledgeVersion(),
                mapper.createObjectNode());
        return copyResponse(
                original,
                original.providerCode(),
                original.adapterVersion(),
                original.sourceCode(),
                original.capability(),
                original.rawDailyBars(),
                List.of(action));
    }

    private MarketFactResponse withProviderVerifiedFact(
            MarketFactResponse original
    ) {
        RawDailyBar value = original.rawDailyBars().get(0);
        ProviderVersion verified = new ProviderVersion(
                "FORGED_DATASET",
                "FORGED_REVISION",
                null,
                OBSERVED_AT.minusSeconds(60),
                null,
                RevisionQualification.PROVIDER_VERIFIED);
        RawDailyBar forged = new RawDailyBar(
                value.sourceIdentity(),
                value.symbol(),
                value.exchange(),
                value.tradeDate(),
                value.open(),
                value.high(),
                value.low(),
                value.close(),
                value.volume(),
                value.amount(),
                value.turnoverRate(),
                verified,
                value.rawFields());
        return copyResponse(
                original,
                original.providerCode(),
                original.adapterVersion(),
                original.sourceCode(),
                original.capability(),
                List.of(forged),
                original.corporateActions());
    }

    private static ProviderVersion systemKnowledgeVersion() {
        return new ProviderVersion(
                null, null, null, null, null,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
    }

    private static ProviderCapability copyCapability(
            ProviderCapability original,
            String providerCode,
            String adapterVersion,
            Set<FactType> supportedFactTypes,
            JsonNode coverage,
            JsonNode licensing
    ) {
        return new ProviderCapability(
                original.providerContractVersion(),
                providerCode,
                adapterVersion,
                supportedFactTypes,
                original.revisionIdAvailable(),
                original.snapshotIdAvailable(),
                original.providerPublishedAtAvailable(),
                original.providerUpdatedAtAvailable(),
                original.historicalVersionsQueryable(),
                original.localPersistenceAllowed(),
                original.historicalReplayAllowed(),
                original.backtestAllowed(),
                original.agentUseAllowed(),
                original.maximumSymbolsPerRequest(),
                original.maximumNaturalDaysPerRequest(),
                original.minimumRequestInterval(),
                original.fieldUnits(),
                original.decimalScales(),
                coverage,
                licensing,
                original.rateLimit());
    }

    private static MarketFactResponse copyResponse(
            MarketFactResponse original,
            String providerCode,
            String adapterVersion,
            String sourceCode,
            ProviderCapability capability,
            List<RawDailyBar> rawDailyBars,
            List<CorporateAction> corporateActions
    ) {
        return new MarketFactResponse(
                original.providerContractVersion(),
                providerCode,
                adapterVersion,
                original.runNamespace(),
                sourceCode,
                original.sourceInstrumentId(),
                original.requestedStart(),
                original.requestedEnd(),
                original.complete(),
                capability,
                rawDailyBars,
                original.adjustmentFactors(),
                original.tradingCalendar(),
                corporateActions,
                original.errors(),
                original.providerMetadata());
    }

    private static LimitedPersonalFormalCaptureAuthorization authorization() {
        return LimitedPersonalFormalCaptureAuthorization.tushareF1A();
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
