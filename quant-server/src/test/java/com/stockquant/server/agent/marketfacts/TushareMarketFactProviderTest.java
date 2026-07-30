package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderErrorType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.DividendEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareMarketFactProviderTest {

    private static final String TEST_TOKEN = "provider-unit-test-token";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void capabilityFreezesPartialEvidenceAndProcessOnlyQuotas() {
        TushareMarketFactProvider provider =
                provider(new FixtureGateway(mapper));
        var capability = provider.capability();
        assertEquals(
                Set.of(
                        FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR,
                        FactType.TRADING_CALENDAR),
                capability.supportedFactTypes());
        assertTrue(capability.localPersistenceAllowed());
        assertTrue(capability.historicalReplayAllowed());
        assertTrue(capability.backtestAllowed());
        assertTrue(capability.agentUseAllowed());
        assertFalse(capability.revisionIdAvailable());
        assertFalse(capability.historicalVersionsQueryable());
        assertEquals("PARTIAL",
                capability.coverage()
                        .path("stableSecurityIdentity").asText());
        assertEquals("PARTIAL_NOT_V13_ELIGIBLE",
                capability.coverage()
                        .path("dividendEvidence").asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenQuantDataSourceUsePermission")
                        .asText());
        assertEquals("UNVERIFIED",
                capability.licensing()
                        .path("writtenPersonalLocalStoragePermission")
                        .asText());
        assertEquals("UNVERIFIED",
                capability.licensing()
                        .path("writtenPersonalBacktestPermission")
                        .asText());
        assertEquals("UNVERIFIED",
                capability.licensing()
                        .path("writtenPersonalAgentAnalysisPermission")
                        .asText());
        assertEquals("CONFIRMED",
                capability.licensing()
                        .path("userPersonalUseImplementationAuthorization")
                        .asText());
        assertEquals("APPROVED_BY_USER",
                capability.licensing()
                        .path("limitedPersonalUseImplementation")
                        .asText());
        assertEquals(200,
                capability.rateLimit()
                        .path("officialPerMinute").asInt());
        assertEquals(180,
                capability.rateLimit()
                        .path("applicationSafePerMinute").asInt());
        assertEquals(100_000,
                capability.rateLimit()
                        .path("officialDailyPerApi").asInt());
        assertEquals(90_000,
                capability.rateLimit()
                        .path("applicationDailySafePerApi").asInt());
        assertTrue(capability.rateLimit()
                .path("processWide").asBoolean());
        assertTrue(capability.rateLimit()
                .path("sharedAcrossEndpoints").asBoolean());
        assertTrue(capability.rateLimit()
                .path("sharedAcrossCallersInProcess").asBoolean());
        assertFalse(capability.rateLimit()
                .path("tokenLevelGlobalAcrossProcesses").asBoolean(true));
        assertFalse(capability.rateLimit()
                .path("distributedRateLimitCoordinated").asBoolean(true));
        assertTrue(capability.rateLimit()
                .path("dailyQuotaProcessWideOnly").asBoolean());
        assertFalse(capability.rateLimit()
                .path("distributedDailyQuotaCoordinated")
                .asBoolean(true));
        assertFalse(capability.rateLimit().has("tokenLevelGlobal"));
        assertFalse(capability.toString().contains(TEST_TOKEN));
    }

    @Test
    void ordinaryFetchIsUnavailableAndDisabledModeStopsControlledPath() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        TushareMarketFactProvider enabled = provider(gateway);
        assertThrows(IllegalStateException.class,
                () -> enabled.fetch(request(
                        "600000", "SSE",
                        Set.of(FactType.RAW_DAILY_BAR))));
        assertTrue(gateway.calls.isEmpty());

        TushareMarketFactProperties disabled =
                new TushareMarketFactProperties();
        disabled.setToken(TEST_TOKEN);
        TushareMarketFactProvider provider =
                new TushareMarketFactProvider(
                        mapper, disabled, gateway);
        assertThrows(IllegalStateException.class,
                () -> provider.fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session()));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void mapsRawFactorAndCalendarWithoutInventingPitMetadata() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request(
                                "600000",
                                "SSE",
                                Set.of(
                                        FactType.RAW_DAILY_BAR,
                                        FactType.ADJUSTMENT_FACTOR,
                                        FactType.TRADING_CALENDAR)),
                        session());

        assertTrue(response.complete());
        assertEquals(List.of(
                        "daily", "adj_factor", "trade_cal"),
                gateway.calls.stream().map(Call::endpoint).toList());
        assertTrue(gateway.calls.stream().allMatch(call ->
                call.mode() == QueryMode.CONTROLLED_NO_RETRY));
        assertEquals(2, response.rawDailyBars().size());
        assertEquals(2, response.adjustmentFactors().size());
        assertEquals(2, response.tradingCalendar().size());
        assertTrue(response.corporateActions().isEmpty());
        assertEquals(3,
                response.providerMetadata()
                        .path("providerCallCount").asInt());
        assertEquals("MANUAL_BOUNDED",
                response.providerMetadata()
                        .path("tushareMode").asText());

        var firstBar = response.rawDailyBars().get(0);
        assertEquals(LocalDate.of(2025, 1, 6),
                firstBar.tradeDate());
        assertEquals(new BigDecimal("100050"),
                firstBar.volume().value());
        assertEquals(new BigDecimal("123456"),
                firstBar.amount().value());
        assertEquals(FieldQualification.MISSING,
                firstBar.turnoverRate().qualification());
        assertEquals(
                TushareMarketFactProvider.rawSourceIdentity(
                        "600000", "SSE"),
                firstBar.sourceIdentity());
        assertEquals(
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY,
                firstBar.version().revisionQualification());
        assertEquals(
                TushareMarketFactProvider.factorSourceIdentity(
                        "600000", "SSE"),
                response.adjustmentFactors().get(0).sourceIdentity());
        assertEquals(
                TushareMarketFactProvider.calendarSourceIdentity("SSE"),
                response.tradingCalendar().get(0).sourceIdentity());
        assertFalse(response.toString().contains(TEST_TOKEN));
    }

    @Test
    void mapsStockBasicAsPartialOrdinaryIdentity() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        var response = provider(gateway)
                .fetchInstrumentIdentityForControlledAcceptance(
                        "600000",
                        "SSE",
                        Duration.ofSeconds(5),
                        session());
        assertEquals("stock_basic", response.endpoint());
        assertEquals(1, response.values().size());
        var identity = response.values().get(0);
        assertEquals("600000.SH",
                identity.providerInstrumentId());
        assertEquals("600000", identity.symbol());
        assertEquals("SSE", identity.exchange());
        assertEquals("浦发银行", identity.name());
        assertEquals("主板", identity.market());
        assertEquals("L", identity.listStatus());
        assertEquals(LocalDate.of(1999, 11, 10),
                identity.listDate());
        assertNull(identity.delistDate());
        assertFalse(response.v13CorporateActionEligible());
    }

    @Test
    void mapsDividendOnlyAsPartialEvidenceWithoutStableActionClaims() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        var response = provider(gateway)
                .fetchDividendEvidenceForControlledAcceptance(
                        "600000",
                        "SSE",
                        Duration.ofSeconds(5),
                        session());
        assertEquals("dividend", response.endpoint());
        assertEquals(1, response.values().size());
        DividendEvidence value = response.values().get(0);
        assertEquals("600000.SH", value.tsCode());
        assertEquals(LocalDate.of(2024, 12, 31),
                value.endDate());
        assertEquals("实施", value.processStatus());
        assertEquals(new BigDecimal("0.1"),
                value.cashDividend());
        assertFalse(response.v13CorporateActionEligible());
        assertTrue(List.of(DividendEvidence.class
                        .getRecordComponents()).stream()
                .noneMatch(component -> Set.of(
                                "stableActionId", "rightsIssue", "split",
                                "reverseSplit", "corrected", "withdrawn",
                                "revision")
                        .contains(component.getName())));
        assertTrue(new MarketFactProviderModels.MarketFactResponse(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.ADAPTER_VERSION,
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        "600000", "SSE"),
                LocalDate.of(2025, 1, 6),
                LocalDate.of(2025, 1, 7),
                true,
                provider(gateway).capability(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                mapper.createObjectNode())
                .corporateActions().isEmpty());
    }

    @Test
    void preservesMissingAndExplicitZeroProviderFields() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.dailyRows = List.of(List.of(
                text("600000.SH"),
                text("20250106"),
                decimal("10"),
                decimal("10"),
                decimal("10"),
                decimal("10"),
                mapper.nullNode(),
                decimal("0")));
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session());
        var bar = response.rawDailyBars().get(0);
        assertEquals(FieldQualification.MISSING,
                bar.volume().qualification());
        assertEquals(FieldQualification.PRESENT_VERIFIED,
                bar.amount().qualification());
        assertEquals(BigDecimal.ZERO, bar.amount().value());
    }

    @Test
    void unsupportedScopeStopsBeforeGateway() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        TushareMarketFactProvider provider = provider(gateway);
        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchForControlledAcceptance(
                        request("688001", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session()));
        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.CORPORATE_ACTION)),
                        session()));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void partialFailureIsTypedAndNeverMasqueradesAsComplete() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.failureEndpoint = "adj_factor";
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request(
                                "600000",
                                "SSE",
                                Set.of(
                                        FactType.RAW_DAILY_BAR,
                                        FactType.ADJUSTMENT_FACTOR,
                                        FactType.TRADING_CALENDAR)),
                        session());
        assertFalse(response.complete());
        assertEquals(2, response.rawDailyBars().size());
        assertTrue(response.adjustmentFactors().isEmpty());
        assertTrue(response.tradingCalendar().isEmpty());
        assertEquals(ProviderErrorType.PERMISSION_DENIED,
                response.errors().get(0).type());
        assertEquals("TUSHARE_PERMISSION_DENIED",
                response.errors().get(0).code());
    }

    @Test
    void malformedRowsFailAtomicallyAtProviderBoundary() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.dailyRows = List.of(List.of(
                text("600000.SH"),
                text("20250106"),
                text("not-a-decimal"),
                decimal("11"),
                decimal("9"),
                decimal("10"),
                decimal("100"),
                decimal("100")));
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session());
        assertFalse(response.complete());
        assertTrue(response.rawDailyBars().isEmpty());
        assertEquals(ProviderErrorType.STRUCTURE_CHANGED,
                response.errors().get(0).type());
    }

    private TushareMarketFactProvider provider(
            TushareApiGateway gateway
    ) {
        return new TushareMarketFactProvider(
                mapper, properties(), gateway);
    }

    private TushareMarketFactProperties properties() {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken(TEST_TOKEN);
        return properties;
    }

    private static TushareManualBoundedSession session() {
        return TushareManualBoundedSession.f1aAcceptance(0);
    }

    private MarketFactRequest request(
            String symbol,
            String exchange,
            Set<FactType> factTypes
    ) {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        symbol, exchange),
                symbol,
                exchange,
                LocalDate.of(2025, 1, 6),
                LocalDate.of(2025, 1, 7),
                factTypes,
                Duration.ofSeconds(5));
    }

    private JsonNode text(String value) {
        return mapper.getNodeFactory().textNode(value);
    }

    private JsonNode decimal(String value) {
        return mapper.getNodeFactory().numberNode(
                new BigDecimal(value));
    }

    private static final class FixtureGateway
            implements TushareApiGateway {
        private final ObjectMapper mapper;
        private final List<Call> calls = new ArrayList<>();
        private List<List<JsonNode>> dailyRows;
        private String failureEndpoint;

        private FixtureGateway(ObjectMapper mapper) {
            this.mapper = mapper;
            this.dailyRows = List.of(
                    List.of(
                            textNode("600000.SH"),
                            textNode("20250107"),
                            decimalNode("10.1"),
                            decimalNode("10.3"),
                            decimalNode("10.0"),
                            decimalNode("10.2"),
                            decimalNode("1100.5"),
                            decimalNode("130.5")),
                    List.of(
                            textNode("600000.SH"),
                            textNode("20250106"),
                            decimalNode("10.0"),
                            decimalNode("10.2"),
                            decimalNode("9.9"),
                            decimalNode("10.1"),
                            decimalNode("1000.5"),
                            decimalNode("123.456")));
        }

        @Override
        public QueryResult query(
                String endpoint,
                ObjectNode parameters,
                List<String> fields,
                Duration timeout,
                QueryMode mode,
                TushareManualBoundedSession session
        ) {
            calls.add(new Call(endpoint, mode));
            if (endpoint.equals(failureEndpoint)) {
                throw new GatewayException(
                        ErrorKind.PERMISSION_DENIED,
                        "TUSHARE_PERMISSION_DENIED",
                        "permission denied",
                        1,
                        0,
                        null);
            }
            return new QueryResult(
                    switch (endpoint) {
                        case "daily" -> new Table(fields, dailyRows);
                        case "adj_factor" -> new Table(
                                fields,
                                List.of(
                                        List.of(
                                                textNode("600000.SH"),
                                                textNode("20250107"),
                                                decimalNode("1.2")),
                                        List.of(
                                                textNode("600000.SH"),
                                                textNode("20250106"),
                                                decimalNode("1.2"))));
                        case "trade_cal" -> new Table(
                                fields,
                                List.of(
                                        List.of(
                                                textNode("SSE"),
                                                textNode("20250107"),
                                                integerNode(1),
                                                textNode("20250106")),
                                        List.of(
                                                textNode("SSE"),
                                                textNode("20250106"),
                                                integerNode(1),
                                                textNode("20250103"))));
                        case "stock_basic" -> new Table(
                                fields,
                                List.of(List.of(
                                        textNode("600000.SH"),
                                        textNode("600000"),
                                        textNode("浦发银行"),
                                        textNode("主板"),
                                        textNode("SSE"),
                                        textNode("L"),
                                        textNode("19991110"),
                                        mapper.nullNode())));
                        case "dividend" -> new Table(
                                fields,
                                List.of(List.of(
                                        textNode("600000.SH"),
                                        textNode("20241231"),
                                        textNode("20250301"),
                                        textNode("实施"),
                                        decimalNode("0"),
                                        decimalNode("0"),
                                        decimalNode("0"),
                                        decimalNode("0.1"),
                                        decimalNode("0.09"),
                                        textNode("20250601"),
                                        textNode("20250602"),
                                        textNode("20250603"),
                                        mapper.nullNode(),
                                        textNode("20250520"))));
                        default -> throw new IllegalArgumentException(
                                endpoint);
                    },
                    1,
                    0);
        }

        private static JsonNode textNode(String value) {
            return com.fasterxml.jackson.databind.node.TextNode
                    .valueOf(value);
        }

        private static JsonNode decimalNode(String value) {
            return com.fasterxml.jackson.databind.node.DecimalNode
                    .valueOf(new BigDecimal(value));
        }

        private static JsonNode integerNode(int value) {
            return com.fasterxml.jackson.databind.node.IntNode
                    .valueOf(value);
        }
    }

    private record Call(String endpoint, QueryMode mode) {
    }
}
