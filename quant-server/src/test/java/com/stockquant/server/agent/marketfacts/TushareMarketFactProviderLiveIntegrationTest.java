package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One-shot F1A repair probe. It intentionally excludes the six already
 * executed daily/factor/calendar calls and permits exactly four new calls.
 */
@EnabledIfEnvironmentVariable(
        named = "TUSHARE_F1A_REPAIR_LIVE_ENABLED", matches = "true")
@EnabledIfEnvironmentVariable(
        named = "TUSHARE_TOKEN", matches = ".+")
class TushareMarketFactProviderLiveIntegrationTest {

    @Test
    void verifiesStockBasicAndDividendWithExactlyFourNoRetryCalls() {
        String token = System.getenv("TUSHARE_TOKEN");
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
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
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1aAcceptance(6);
        List<Instrument> instruments = List.of(
                new Instrument("600000", "SSE"),
                new Instrument("000001", "SZSE"));

        for (Instrument instrument : instruments) {
            var result =
                    provider.fetchInstrumentIdentityForControlledAcceptance(
                            instrument.symbol(),
                            instrument.exchange(),
                            Duration.ofSeconds(20),
                            session);
            assertEquals(1, result.providerCallCount());
            assertEquals(0, result.rateLimitRetryCount());
            assertEquals(1, result.values().size());
            assertEquals(Set.of(
                            "ts_code", "symbol", "name", "market",
                            "exchange", "list_status", "list_date",
                            "delist_date"),
                    Set.copyOf(result.responseFields()));
            assertFalse(result.toString().contains(token));
            printSafeResult(
                    "stock_basic",
                    instrument.tsCode(),
                    result.values().size(),
                    result.responseFields());
        }

        for (Instrument instrument : instruments) {
            var result =
                    provider.fetchDividendEvidenceForControlledAcceptance(
                            instrument.symbol(),
                            instrument.exchange(),
                            Duration.ofSeconds(20),
                            session);
            assertEquals(1, result.providerCallCount());
            assertEquals(0, result.rateLimitRetryCount());
            assertFalse(result.values().isEmpty());
            assertEquals(Set.of(
                            "ts_code", "end_date", "ann_date",
                            "div_proc", "stk_div", "stk_bo_rate",
                            "stk_co_rate", "cash_div", "cash_div_tax",
                            "record_date", "ex_date", "pay_date",
                            "div_listdate", "imp_ann_date"),
                    Set.copyOf(result.responseFields()));
            assertFalse(result.v13CorporateActionEligible());
            assertFalse(result.toString().contains(token));
            printSafeResult(
                    "dividend",
                    instrument.tsCode(),
                    result.values().size(),
                    result.responseFields());
        }

        assertEquals(10, session.consumedBusinessRequests());
        var snapshot = limiter.snapshot();
        assertEquals(4, snapshot.totalCallCount());
        assertEquals(
                java.util.Map.of(
                        "stock_basic", 2L,
                        "dividend", 2L),
                snapshot.endpointCallCounts());
        assertTrue(snapshot.dailyEndpointCallCounts()
                .equals(snapshot.endpointCallCounts()));
        System.out.println("F1A_REAL_CALL_COUNT=10");
        System.out.println(
                "TUSHARE_TOTAL_REAL_BUSINESS_CALL_COUNT=20");
    }

    private static void printSafeResult(
            String endpoint,
            String tsCode,
            int rowCount,
            List<String> fields
    ) {
        System.out.println(
                "endpoint=" + endpoint
                        + " target=" + tsCode
                        + " status=PASS"
                        + " rowCount=" + rowCount
                        + " fields=" + String.join(",", fields));
    }

    private record Instrument(String symbol, String exchange) {
        private String tsCode() {
            return symbol + ("SSE".equals(exchange) ? ".SH" : ".SZ");
        }
    }
}
