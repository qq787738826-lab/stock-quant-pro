package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TushareHttpApiGatewayTest {

    private static final String TEST_TOKEN = "unit-test-secret";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsTheOfficialEnvelopeAndParsesRowsWithoutLeakingToken()
            throws Exception {
        Fixture fixture = fixture(180, 2);
        fixture.exchange.respond(200, """
                {
                  "code":0,
                  "msg":null,
                  "data":{
                    "fields":["ts_code","trade_date"],
                    "items":[["600000.SH","20250102"]]
                  }
                }
                """);

        var result = fixture.gateway.query(
                "daily",
                parameters("ts_code", "600000.SH"),
                List.of("ts_code", "trade_date"),
                Duration.ofSeconds(5),
                QueryMode.CONTROLLED_NO_RETRY);

        assertEquals(1, result.providerCallCount());
        assertEquals(0, result.rateLimitRetryCount());
        assertEquals(List.of("ts_code", "trade_date"),
                result.table().fields());
        assertEquals(1, result.table().rows().size());
        assertFalse(result.toString().contains(TEST_TOKEN));
        assertEquals(1, fixture.exchange.requests.size());
        var sent = mapper.readTree(
                fixture.exchange.requests.get(0).body());
        assertEquals("daily", sent.path("api_name").asText());
        assertEquals(TEST_TOKEN, sent.path("token").asText());
        assertEquals("600000.SH",
                sent.path("params").path("ts_code").asText());
        assertEquals("ts_code,trade_date",
                sent.path("fields").asText());
        assertEquals(Duration.ofSeconds(5),
                fixture.exchange.requests.get(0).timeout());
    }

    @Test
    void requestTimeoutIsCappedByTheConfiguredReadTimeout() {
        Fixture fixture = fixture(180, 2);
        fixture.exchange.respond(200, successEmpty());
        fixture.gateway.query(
                "trade_cal",
                mapper.createObjectNode(),
                List.of("exchange"),
                Duration.ofMinutes(1),
                QueryMode.CONTROLLED_NO_RETRY);
        assertEquals(Duration.ofSeconds(30),
                fixture.exchange.requests.get(0).timeout());
    }

    @Test
    void normalRateLimitRetryIsFiniteAndDefaultsToTwo() {
        Fixture fixture = fixture(180, 2);
        fixture.exchange.respond(429, "");
        fixture.exchange.respond(429, "");
        fixture.exchange.respond(200, successEmpty());

        var result = fixture.gateway.query(
                "trade_cal",
                mapper.createObjectNode(),
                List.of("exchange"),
                Duration.ofSeconds(5),
                QueryMode.NORMAL);
        assertEquals(3, result.providerCallCount());
        assertEquals(2, result.rateLimitRetryCount());
        assertEquals(3,
                fixture.limiter.snapshot().totalCallCount());
        assertEquals(3, fixture.exchange.requests.size());
    }

    @Test
    void controlledModeNeverRetries() {
        Fixture fixture = fixture(180, 2);
        fixture.exchange.respond(429, "");

        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "adj_factor",
                        mapper.createObjectNode(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY));
        assertEquals(ErrorKind.RATE_LIMITED, error.kind());
        assertEquals(1, error.providerCallCount());
        assertEquals(0, error.rateLimitRetryCount());
        assertEquals(1, fixture.exchange.requests.size());
    }

    @Test
    void permissionAndProviderMessagesAreSafelyClassified() {
        Fixture fixture = fixture(180, 2);
        fixture.exchange.respond(200, """
                {
                  "code":2002,
                  "msg":"token=unit-test-secret username=example-user permission denied",
                  "data":null
                }
                """);

        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "daily",
                        mapper.createObjectNode(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY));
        assertEquals(ErrorKind.PERMISSION_DENIED, error.kind());
        assertEquals("TUSHARE_PERMISSION_DENIED", error.safeCode());
        assertFalse(error.getMessage().contains(TEST_TOKEN));
        assertFalse(error.getMessage().contains("example-user"));
    }

    @Test
    void rejectsChangedRowShape() {
        Fixture fixture = fixture(180, 2);
        fixture.exchange.respond(200, """
                {
                  "code":0,
                  "msg":null,
                  "data":{
                    "fields":["ts_code","trade_date"],
                    "items":[["600000.SH"]]
                  }
                }
                """);
        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "daily",
                        mapper.createObjectNode(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY));
        assertEquals(ErrorKind.STRUCTURE_CHANGED, error.kind());
    }

    private Fixture fixture(int safeLimit, int maximumRetries) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setEnabled(true);
        properties.setToken(TEST_TOKEN);
        properties.setApplicationSafeLimitPerMinute(safeLimit);
        properties.setMaximumRateLimitRetries(maximumRetries);
        TushareTokenRateLimiter limiter =
                new TushareTokenRateLimiter(properties);
        FakeHttpExchange exchange = new FakeHttpExchange();
        TushareHttpApiGateway gateway =
                new TushareHttpApiGateway(
                        mapper,
                        properties,
                        limiter,
                        URI.create("https://api.tushare.pro"),
                        exchange,
                        duration -> {
                            // Deterministic unit test: no wall-clock wait.
                        });
        return new Fixture(exchange, limiter, gateway);
    }

    private ObjectNode parameters(String field, String value) {
        ObjectNode result = mapper.createObjectNode();
        result.put(field, value);
        return result;
    }

    private static String successEmpty() {
        return """
                {
                  "code":0,
                  "msg":null,
                  "data":{"fields":[],"items":[]}
                }
                """;
    }

    private static final class FakeHttpExchange
            implements TushareHttpApiGateway.HttpExchangeStrategy {
        private final ArrayDeque<
                TushareHttpApiGateway.HttpExchangeResult> responses =
                new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private void respond(int status, String body) {
            responses.addLast(
                    new TushareHttpApiGateway.HttpExchangeResult(
                            status, body));
        }

        @Override
        public TushareHttpApiGateway.HttpExchangeResult post(
                URI uri,
                String body,
                Duration timeout
        ) {
            requests.add(new Request(uri, body, timeout));
            return responses.removeFirst();
        }
    }

    private record Request(
            URI uri,
            String body,
            Duration timeout
    ) {
    }

    private record Fixture(
            FakeHttpExchange exchange,
            TushareTokenRateLimiter limiter,
            TushareHttpApiGateway gateway
    ) {
    }
}
