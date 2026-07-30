package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit six-call adapter acceptance. It stores no provider response and
 * never retries.
 */
@EnabledIfEnvironmentVariable(
        named = "TUSHARE_F1A_LIVE_ENABLED", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "TUSHARE_TOKEN", matches = ".+")
class TushareMarketFactProviderLiveIntegrationTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 6);
    private static final LocalDate END = LocalDate.of(2025, 1, 7);

    @Test
    void mapsTwoExchangesWithExactlySixNoRetryCalls() {
        String token = System.getenv("TUSHARE_TOKEN");
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setEnabled(true);
        properties.setToken(token);
        properties.setReadTimeout(Duration.ofSeconds(20));
        ObjectMapper mapper = new ObjectMapper();
        TushareTokenRateLimiter limiter =
                new TushareTokenRateLimiter(properties);
        TushareMarketFactProvider provider =
                new TushareMarketFactProvider(
                        mapper,
                        properties,
                        new TushareHttpApiGateway(
                                mapper, properties, limiter));

        for (Instrument instrument : List.of(
                new Instrument("600000", "SSE"),
                new Instrument("000001", "SZSE"))) {
            var response = provider.fetchForControlledAcceptance(
                    request(instrument));
            assertTrue(response.complete());
            assertFalse(response.rawDailyBars().isEmpty());
            assertFalse(response.adjustmentFactors().isEmpty());
            assertFalse(response.tradingCalendar().isEmpty());
            assertTrue(response.corporateActions().isEmpty());
            assertTrue(response.rawDailyBars().stream()
                    .allMatch(bar ->
                            !bar.tradeDate().isBefore(START)
                                    && !bar.tradeDate().isAfter(END)));
            assertTrue(response.adjustmentFactors().stream()
                    .allMatch(factor ->
                            !factor.factorEffectiveTradeDate()
                                    .isBefore(START)
                                    && !factor.factorEffectiveTradeDate()
                                    .isAfter(END)));
            assertEquals(3,
                    response.providerMetadata()
                            .path("providerCallCount").asInt());
            assertEquals(0,
                    response.providerMetadata()
                            .path("rateLimitRetryCount").asInt());
            assertFalse(response.toString().contains(token));
        }

        var snapshot = limiter.snapshot();
        assertEquals(6, snapshot.totalCallCount());
        assertEquals(
                java.util.Map.of(
                        "daily", 2L,
                        "adj_factor", 2L,
                        "trade_cal", 2L),
                snapshot.endpointCallCounts());
    }

    private static MarketFactRequest request(Instrument instrument) {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        instrument.symbol(), instrument.exchange()),
                instrument.symbol(),
                instrument.exchange(),
                START,
                END,
                Set.of(
                        FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR,
                        FactType.TRADING_CALENDAR),
                Duration.ofSeconds(20));
    }

    private record Instrument(String symbol, String exchange) {
    }
}
