package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareDay001FactWindowFilteringTest {

    private static final LocalDate DATE = LocalDate.of(2025, 1, 3);
    private static final SecuritySelection SECURITY =
            new SecuritySelection("600000", "SSE");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exactTargetDayMapsOneFactAndUsesFrozenRequestParameters() {
        ShapeGateway gateway = new ShapeGateway(List.of(targetDaily()));

        MarketFactResponse response = fetch(gateway);

        assertValidTargetWindow(response);
        assertEquals(List.of("daily", "adj_factor", "trade_cal"),
                gateway.calls.stream().map(Call::endpoint).toList());
        assertEquals("600000.SH",
                gateway.calls.get(0).parameters().path("ts_code").asText());
        assertEquals("20250103",
                gateway.calls.get(0).parameters().path("start_date").asText());
        assertEquals("20250103",
                gateway.calls.get(0).parameters().path("end_date").asText());
        assertEquals("600000.SH",
                gateway.calls.get(1).parameters().path("ts_code").asText());
        assertEquals("SSE",
                gateway.calls.get(2).parameters().path("exchange").asText());
        assertEquals("20250103",
                gateway.calls.get(2).parameters().path("start_date").asText());
        assertEquals("20250103",
                gateway.calls.get(2).parameters().path("end_date").asText());
    }

    @Test
    void zeroDailyRowsCannotSatisfyTargetWindow() {
        MarketFactResponse response = fetch(new ShapeGateway(List.of()));

        assertTrue(response.complete());
        assertEquals(0, response.rawDailyBars().size());
        assertTargetWindowRejected(response);
    }

    @Test
    void multiDaySupersetIsFilteredBeforeExactWindowValidation() {
        ShapeGateway gateway = new ShapeGateway(List.of(
                daily("600000.SH", "20250102"),
                targetDaily(),
                daily("600000.SH", "20250106")));

        assertValidTargetWindow(fetch(gateway));
    }

    @Test
    void multiSecuritySupersetIsFilteredBeforeDtoMapping() {
        ShapeGateway gateway = new ShapeGateway(List.of(
                daily("000001.SZ", "20250103"),
                targetDaily(),
                daily("600001.SH", "20250103")));

        assertValidTargetWindow(fetch(gateway));
    }

    @Test
    void bareSymbolDoesNotMasqueradeAsProviderTsCode() {
        MarketFactResponse response = fetch(new ShapeGateway(List.of(
                daily("600000", "20250103"))));

        assertTrue(response.complete());
        assertEquals(0, response.rawDailyBars().size());
        assertTargetWindowRejected(response);
    }

    @Test
    void malformedProviderDateRemainsFailClosedWithEndpointReason() {
        ShapeGateway gateway = new ShapeGateway(List.of(
                daily("600000.SH", "2025-01-03")));

        MarketFactResponse response = fetch(gateway);

        assertFalse(response.complete());
        assertEquals(1, gateway.calls.size());
        assertEquals("TUSHARE_DAILY_RESPONSE_MAPPING_INVALID",
                response.errors().get(0).code());
        assertTrue(response.rawDailyBars().isEmpty());
    }

    @Test
    void targetRowWithExtraIdentityAndDateRowsStillMapsExactlyOnce() {
        ShapeGateway gateway = new ShapeGateway(List.of(
                daily("600000", "20250103"),
                daily("600000.SH", "20250102"),
                targetDaily(),
                daily("000001.SZ", "20250103")));

        assertValidTargetWindow(fetch(gateway));
    }

    @Test
    void missingTargetPairCannotPassEvenWhenSymbolAndDateExistSeparately() {
        MarketFactResponse response = fetch(new ShapeGateway(List.of(
                daily("600000.SH", "20250102"),
                daily("000001.SZ", "20250103"))));

        assertTrue(response.complete());
        assertEquals(0, response.rawDailyBars().size());
        assertTargetWindowRejected(response);
    }

    @Test
    void responseOrderDoesNotChangeFilteredTargetFact() {
        List<List<JsonNode>> rows = new ArrayList<>(List.of(
                daily("000001.SZ", "20250103"),
                targetDaily(),
                daily("600000.SH", "20250102")));
        MarketFactResponse first = fetch(new ShapeGateway(rows));
        Collections.reverse(rows);
        MarketFactResponse reversed = fetch(new ShapeGateway(rows));

        assertValidTargetWindow(first);
        assertValidTargetWindow(reversed);
        assertEquals(first.rawDailyBars(), reversed.rawDailyBars());
    }

    @Test
    void allThreeEndpointSupersetsFilterToOneExactFactEach() {
        ShapeGateway gateway = new ShapeGateway(
                List.of(daily("000001.SZ", "20250103"), targetDaily()),
                List.of(
                        factor("600000.SH", "20250102"),
                        factor("000001.SZ", "20250103"),
                        factor("600000.SH", "20250103")),
                List.of(
                        calendar("SZSE", "20250103"),
                        calendar("SSE", "20250102"),
                        calendar("SSE", "20250103")));

        MarketFactResponse response = fetch(gateway);

        assertValidTargetWindow(response);
        assertEquals(1, response.adjustmentFactors().size());
        assertEquals(1, response.tradingCalendar().size());
    }

    @Test
    void duplicateExactTargetRowsRemainFailClosed() {
        ShapeGateway gateway = new ShapeGateway(List.of(
                targetDaily(), targetDaily()));

        MarketFactResponse response = fetch(gateway);

        assertFalse(response.complete());
        assertEquals("TUSHARE_DAILY_RESPONSE_MAPPING_INVALID",
                response.errors().get(0).code());
        assertEquals(1, gateway.calls.size());
    }

    private MarketFactResponse fetch(ShapeGateway gateway) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken("synthetic-fact-window-token");
        TushareMarketFactProvider provider = new TushareMarketFactProvider(
                mapper, properties, gateway);
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1eDedicatedLocalManual(
                        List.of(SECURITY), DATE);
        return provider.fetchForDedicatedReducedResearch(
                new MarketFactRequest(
                        RunNamespace.FORMAL,
                        TushareMarketFactProvider.PROVIDER_CODE,
                        TushareMarketFactProvider.sourceInstrumentId(
                                SECURITY.symbol(), SECURITY.exchange()),
                        SECURITY.symbol(), SECURITY.exchange(), DATE, DATE,
                        Set.of(FactType.RAW_DAILY_BAR,
                                FactType.ADJUSTMENT_FACTOR,
                                FactType.TRADING_CALENDAR),
                        Duration.ofSeconds(5)),
                session);
    }

    private static void assertValidTargetWindow(MarketFactResponse response) {
        assertTrue(response.complete());
        assertEquals(1, response.rawDailyBars().size());
        assertEquals("600000", response.rawDailyBars().get(0).symbol());
        assertEquals(DATE, response.rawDailyBars().get(0).tradeDate());
        TushareDedicatedResearchFactValidator.validate(
                response, SECURITY, DATE);
    }

    private static void assertTargetWindowRejected(
            MarketFactResponse response
    ) {
        assertThrows(IllegalArgumentException.class, () ->
                TushareDedicatedResearchFactValidator.validate(
                        response, SECURITY, DATE));
    }

    private static List<JsonNode> targetDaily() {
        return daily("600000.SH", "20250103");
    }

    private static List<JsonNode> daily(String tsCode, String date) {
        return List.of(
                TextNode.valueOf(tsCode),
                TextNode.valueOf(date),
                DecimalNode.valueOf(new BigDecimal("10")),
                DecimalNode.valueOf(new BigDecimal("12")),
                DecimalNode.valueOf(new BigDecimal("9")),
                DecimalNode.valueOf(new BigDecimal("11")),
                DecimalNode.valueOf(new BigDecimal("100")),
                DecimalNode.valueOf(new BigDecimal("10")));
    }

    private static List<JsonNode> factor(String tsCode, String date) {
        return List.of(
                TextNode.valueOf(tsCode), TextNode.valueOf(date),
                DecimalNode.valueOf(new BigDecimal("2")));
    }

    private static List<JsonNode> calendar(String exchange, String date) {
        return List.of(
                TextNode.valueOf(exchange), TextNode.valueOf(date),
                DecimalNode.valueOf(BigDecimal.ONE),
                TextNode.valueOf("20250102"));
    }

    private static final class ShapeGateway implements TushareApiGateway {
        private final List<List<JsonNode>> dailyRows;
        private final List<List<JsonNode>> factorRows;
        private final List<List<JsonNode>> calendarRows;
        private final List<Call> calls = new ArrayList<>();

        private ShapeGateway(List<List<JsonNode>> dailyRows) {
            this(dailyRows,
                    List.of(factor("600000.SH", "20250103")),
                    List.of(calendar("SSE", "20250103")));
        }

        private ShapeGateway(
                List<List<JsonNode>> dailyRows,
                List<List<JsonNode>> factorRows,
                List<List<JsonNode>> calendarRows
        ) {
            this.dailyRows = List.copyOf(dailyRows);
            this.factorRows = List.copyOf(factorRows);
            this.calendarRows = List.copyOf(calendarRows);
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
            session.authorizeAndReserve(endpoint, parameters);
            calls.add(new Call(endpoint, parameters.deepCopy()));
            List<List<JsonNode>> rows = switch (endpoint) {
                case "daily" -> dailyRows;
                case "adj_factor" -> factorRows;
                case "trade_cal" -> calendarRows;
                default -> throw new IllegalArgumentException(endpoint);
            };
            return new QueryResult(new Table(fields, rows), 1, 0);
        }
    }

    private record Call(String endpoint, ObjectNode parameters) {
    }
}
