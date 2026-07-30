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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareMarketFactProviderTest {

    private static final String TEST_TOKEN = "provider-unit-test-token";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void capabilityFreezesLimitedPersonalUseAndGlobalRateLimit() {
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
        assertEquals("RESEARCH_ONLY",
                capability.licensing()
                        .path("usageQualification").asText());
        assertFalse(capability.licensing()
                .path("formalEligible").asBoolean(true));
        assertEquals("UNVERIFIED",
                capability.licensing()
                        .path("postExpiryDataRetentionPermission")
                        .asText());
        assertEquals("NOT_GRANTED",
                capability.licensing()
                        .path("rawDataRedistributionPermission")
                        .asText());
        assertEquals(200,
                capability.rateLimit()
                        .path("officialPerMinute").asInt());
        assertEquals(180,
                capability.rateLimit()
                        .path("applicationSafePerMinute").asInt());
        assertTrue(capability.rateLimit()
                .path("tokenLevelGlobal").asBoolean());
        assertFalse(capability.toString().contains(TEST_TOKEN));
    }

    @Test
    void mapsRawFactorAndExchangeCalendarWithoutInventingPitMetadata() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        TushareMarketFactProvider provider = provider(gateway);
        var response = provider.fetchForControlledAcceptance(
                request("600000", "SSE", Set.of(FactType.values())
                        .stream()
                        .filter(type -> type != FactType.CORPORATE_ACTION)
                        .collect(java.util.stream.Collectors.toSet())));

        assertTrue(response.complete());
        assertEquals(3, gateway.calls.size());
        assertTrue(gateway.calls.stream()
                .allMatch(call -> call.mode()
                        == QueryMode.CONTROLLED_NO_RETRY));
        assertEquals(List.of(
                        "daily", "adj_factor", "trade_cal"),
                gateway.calls.stream().map(Call::endpoint).toList());
        assertEquals(2, response.rawDailyBars().size());
        assertEquals(2, response.adjustmentFactors().size());
        assertEquals(2, response.tradingCalendar().size());
        assertTrue(response.corporateActions().isEmpty());
        assertEquals(3,
                response.providerMetadata()
                        .path("providerCallCount").asInt());
        assertEquals(0,
                response.providerMetadata()
                        .path("rateLimitRetryCount").asInt());

        var firstBar = response.rawDailyBars().get(0);
        assertEquals(LocalDate.of(2025, 1, 2), firstBar.tradeDate());
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
        assertFalse(firstBar.rawFields().toString().contains(TEST_TOKEN));
    }

    @Test
    void preservesMissingAndExplicitZeroProviderFields() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.dailyRows = List.of(List.of(
                text("600000.SH"),
                text("20250102"),
                decimal("10"),
                decimal("10"),
                decimal("10"),
                decimal("10"),
                mapper.nullNode(),
                decimal("0")));
        var response = provider(gateway).fetchForControlledAcceptance(
                request("600000", "SSE",
                        Set.of(FactType.RAW_DAILY_BAR)));
        var bar = response.rawDailyBars().get(0);
        assertEquals(FieldQualification.MISSING,
                bar.volume().qualification());
        assertEquals(FieldQualification.PRESENT_VERIFIED,
                bar.amount().qualification());
        assertEquals(BigDecimal.ZERO, bar.amount().value());
    }

    @Test
    void disabledProviderAndUnsupportedScopeStopBeforeGateway() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        TushareMarketFactProperties disabled = properties();
        disabled.setEnabled(false);
        TushareMarketFactProvider provider =
                new TushareMarketFactProvider(
                        mapper, disabled, gateway);
        assertThrows(IllegalStateException.class,
                () -> provider.fetch(request(
                        "600000", "SSE",
                        Set.of(FactType.RAW_DAILY_BAR))));
        assertTrue(gateway.calls.isEmpty());

        TushareMarketFactProvider enabled = provider(gateway);
        assertThrows(IllegalArgumentException.class,
                () -> enabled.fetch(request(
                        "688001", "SSE",
                        Set.of(FactType.RAW_DAILY_BAR))));
        assertThrows(IllegalArgumentException.class,
                () -> enabled.fetch(request(
                        "600000", "SSE",
                        Set.of(FactType.CORPORATE_ACTION))));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void partialFailureIsTypedAndNeverMasqueradesAsComplete() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.failureEndpoint = "adj_factor";
        var response = provider(gateway).fetch(
                request(
                        "600000", "SSE",
                        Set.of(
                                FactType.RAW_DAILY_BAR,
                                FactType.ADJUSTMENT_FACTOR,
                                FactType.TRADING_CALENDAR)));
        assertFalse(response.complete());
        assertEquals(2, response.rawDailyBars().size());
        assertTrue(response.adjustmentFactors().isEmpty());
        assertTrue(response.tradingCalendar().isEmpty());
        assertEquals(1, response.errors().size());
        assertEquals(ProviderErrorType.PERMISSION_DENIED,
                response.errors().get(0).type());
        assertEquals("TUSHARE_PERMISSION_DENIED",
                response.errors().get(0).code());
    }

    @Test
    void malformedRowsFailAtomicallyAtTheProviderBoundary() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.dailyRows = List.of(List.of(
                text("600000.SH"),
                text("20250102"),
                text("not-a-decimal"),
                decimal("11"),
                decimal("9"),
                decimal("10"),
                decimal("100"),
                decimal("100")));
        var response = provider(gateway).fetchForControlledAcceptance(
                request("600000", "SSE",
                        Set.of(FactType.RAW_DAILY_BAR)));
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
        properties.setEnabled(true);
        properties.setToken(TEST_TOKEN);
        return properties;
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
                LocalDate.of(2025, 1, 2),
                LocalDate.of(2025, 1, 3),
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
                            textNode("20250103"),
                            decimalNode("10.1"),
                            decimalNode("10.3"),
                            decimalNode("10.0"),
                            decimalNode("10.2"),
                            decimalNode("1100.5"),
                            decimalNode("130.5")),
                    List.of(
                            textNode("600000.SH"),
                            textNode("20250102"),
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
                QueryMode mode
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
                                                textNode("20250103"),
                                                decimalNode("1.2")),
                                        List.of(
                                                textNode("600000.SH"),
                                                textNode("20250102"),
                                                decimalNode("1.2"))));
                        case "trade_cal" -> new Table(
                                fields,
                                List.of(
                                        List.of(
                                                textNode("SSE"),
                                                textNode("20250103"),
                                                mapper.getNodeFactory()
                                                        .numberNode(1),
                                                textNode("20250102")),
                                        List.of(
                                                textNode("SSE"),
                                                textNode("20250102"),
                                                mapper.getNodeFactory()
                                                        .numberNode(1),
                                                textNode("20241231"))));
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
    }

    private record Call(String endpoint, QueryMode mode) {
    }
}
