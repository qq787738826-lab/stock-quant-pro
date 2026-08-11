package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareHttpApiGatewayTest {

    private static final String TEST_TOKEN = "unit-test-secret";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realGatewayPublishesFrozenInjectedRateLimitContract() {
        TushareEndpointRateLimitPolicy policy =
                TushareEndpointRateLimitPolicy.frozenF1cPolicy();
        TushareTokenRateLimiter limiter =
                new TushareTokenRateLimiter(policy);
        TushareHttpApiGateway gateway =
                new TushareHttpApiGateway(
                        mapper,
                        properties(),
                        limiter,
                        URI.create("https://api.tushare.pro"),
                        new FakeHttpExchange(),
                        duration -> {
                            // No request is made by this contract test.
                        });

        var contract = gateway.f1cRateLimitContract();
        contract.validateFrozenF1c();

        assertTrue(contract.endpointSpecificRateLimitEnforced());
        assertTrue(contract.conservativeMinimumPolicyEnforced());
        assertEquals(180, contract.globalSafeLimitPerMinute());
        assertEquals(45,
                contract.endpointSafeLimitsPerMinute()
                        .get("stock_basic"));
        assertEquals(90_000,
                contract.dailySafeLimitPerEndpoint());
        assertTrue(contract.unknownEndpointRejected());
        assertFalse(contract.distributedCoordination());
    }

    @Test
    void disabledIsTheDefaultAndStopsBeforeHttp() {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setToken(TEST_TOKEN);
        Fixture fixture = fixture(properties, 180, 90_000);
        fixture.exchange.respond(200, successEmpty());

        assertThrows(IllegalStateException.class, () ->
                fixture.gateway.query(
                        "daily",
                        datedSecurityParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session(0, false)));
        assertEquals(0, fixture.exchange.requests.size());
    }

    @Test
    void manualBoundedSendsOfficialEnvelopeWithoutLeakingToken()
            throws Exception {
        Fixture fixture = fixture(properties(), 180, 90_000);
        fixture.exchange.respond(200, """
                {
                  "code":0,
                  "msg":null,
                  "data":{
                    "fields":["ts_code","trade_date"],
                    "items":[["600000.SH","20250106"]]
                  }
                }
                """);

        var result = fixture.gateway.query(
                "daily",
                datedSecurityParameters(),
                List.of("ts_code", "trade_date"),
                Duration.ofSeconds(5),
                QueryMode.CONTROLLED_NO_RETRY,
                session(0, false));

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
    void sharedSessionBudgetRejectsEleventhBeforeHttp() {
        Fixture fixture = fixture(properties(), 180, 90_000);
        TushareManualBoundedSession session = session(0, false);
        for (int index = 0; index < 10; index++) {
            fixture.exchange.respond(200, successEmpty());
            fixture.gateway.query(
                    "stock_basic",
                    stockParameters(),
                    List.of("ts_code"),
                    Duration.ofSeconds(5),
                    QueryMode.CONTROLLED_NO_RETRY,
                    session);
        }
        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "stock_basic",
                        stockParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session));
        assertEquals("TUSHARE_REQUEST_BUDGET_EXHAUSTED",
                error.safeCode());
        assertEquals(10, session.consumedBusinessRequests());
        assertEquals(10, fixture.exchange.requests.size());
        assertEquals(10,
                fixture.limiter.snapshot().totalCallCount());
    }

    @Test
    void retryRequiresNormalModeAndExplicitSessionPermission() {
        Fixture fixture = fixture(properties(), 180, 90_000);
        fixture.exchange.respond(429, "");
        fixture.exchange.respond(429, "");
        fixture.exchange.respond(200, successEmpty());

        var result = fixture.gateway.query(
                "trade_cal",
                calendarParameters(),
                List.of("exchange"),
                Duration.ofSeconds(5),
                QueryMode.NORMAL,
                session(0, true));
        assertEquals(3, result.providerCallCount());
        assertEquals(2, result.rateLimitRetryCount());
        assertEquals(3, fixture.exchange.requests.size());
    }

    @Test
    void controlledModeNeverRetries() {
        Fixture fixture = fixture(properties(), 180, 90_000);
        fixture.exchange.respond(429, "");

        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "adj_factor",
                        datedSecurityParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session(0, true)));
        assertEquals(ErrorKind.RATE_LIMITED, error.kind());
        assertEquals(1, error.providerCallCount());
        assertEquals(0, error.rateLimitRetryCount());
        assertEquals(1, fixture.exchange.requests.size());
    }

    @Test
    void httpAuthenticationStatusesRemainDistinctFromProviderBodyCodes() {
        for (var expected : List.of(
                List.of("401", "TUSHARE_HTTP_UNAUTHORIZED_401"),
                List.of("403", "TUSHARE_HTTP_FORBIDDEN_403"))) {
            Fixture fixture = fixture(properties(), 180, 90_000);
            fixture.exchange.respond(
                    Integer.parseInt(expected.get(0)),
                    "text/plain; charset=utf-8",
                    "sanitized transport rejection");

            GatewayException error = assertThrows(
                    GatewayException.class,
                    () -> fixture.gateway.query(
                            "daily",
                            datedSecurityParameters(),
                            List.of("ts_code"),
                            Duration.ofSeconds(5),
                            QueryMode.CONTROLLED_NO_RETRY,
                            session(0, false)));

            assertEquals(expected.get(1), error.safeCode());
            assertEquals(ErrorKind.NETWORK_ERROR, error.kind());
            assertNotNull(error.diagnostic());
            assertEquals(Integer.parseInt(expected.get(0)),
                    error.diagnostic().httpStatus());
            assertEquals(null, error.diagnostic().providerCode());
            assertEquals("NOT_PARSED",
                    error.diagnostic().providerMessageCategory());
            assertEquals("daily", error.diagnostic().endpoint());
            assertEquals(List.of("end_date", "start_date", "ts_code"),
                    error.diagnostic().requestParameterNames());
            assertEquals("api.tushare.pro",
                    error.diagnostic().providerHost());
            assertEquals("/", error.diagnostic().providerPath());
            assertEquals("application/json",
                    error.diagnostic().requestContentType());
            assertEquals("text/plain; charset=utf-8",
                    error.diagnostic().responseContentType());
            assertFalse(error.diagnostic().responseJsonValid());
        }
    }

    @Test
    void code40101UsesSanitizedMessageCategoryWithoutBecomingHttp401() {
        for (var expected : List.of(
                List.of("invalid token", "INVALID_CREDENTIAL",
                        "TUSHARE_CREDENTIAL_REJECTED_40101", "API_ERROR"),
                List.of("permission denied", "PERMISSION_DENIED",
                        "TUSHARE_PERMISSION_DENIED_40101",
                        "PERMISSION_DENIED"),
                List.of("account suspended", "ACCOUNT_RESTRICTED",
                        "TUSHARE_ACCOUNT_RESTRICTED_40101",
                        "PERMISSION_DENIED"),
                List.of("provider rejected request", "OTHER_PROVIDER_ERROR",
                        "TUSHARE_API_ERROR_40101", "API_ERROR"))) {
            Fixture fixture = fixture(properties(), 180, 90_000);
            fixture.exchange.respond(200, """
                    {"code":40101,"msg":"%s","data":null}
                    """.formatted(expected.get(0)));

            GatewayException error = assertThrows(
                    GatewayException.class,
                    () -> fixture.gateway.query(
                            "daily",
                            datedSecurityParameters(),
                            List.of("ts_code"),
                            Duration.ofSeconds(5),
                            QueryMode.CONTROLLED_NO_RETRY,
                            session(0, false)));

            assertEquals(expected.get(2), error.safeCode());
            assertEquals(ErrorKind.valueOf(expected.get(3)), error.kind());
            assertEquals(200, error.diagnostic().httpStatus());
            assertEquals(40101, error.diagnostic().providerCode());
            assertEquals(expected.get(1),
                    error.diagnostic().providerMessageCategory());
            assertTrue(error.diagnostic().responseJsonValid());
        }
    }

    @Test
    void providerRateMessagesOverrideCode2002PermissionDefault() {
        for (String message : List.of(
                "每分钟最多访问200次",
                "接口频次限制",
                "Rate Limit reached")) {
            Fixture fixture = fixture(properties(), 180, 90_000);
            fixture.exchange.respond(200, """
                    {"code":2002,"msg":"%s","data":null}
                    """.formatted(message));
            GatewayException error = assertThrows(
                    GatewayException.class,
                    () -> fixture.gateway.query(
                            "daily",
                            datedSecurityParameters(),
                            List.of("ts_code"),
                            Duration.ofSeconds(5),
                            QueryMode.CONTROLLED_NO_RETRY,
                            session(0, false)));
            assertEquals(ErrorKind.RATE_LIMITED, error.kind());
            assertEquals("TUSHARE_API_RATE_LIMITED",
                    error.safeCode());
        }

        Fixture permission = fixture(properties(), 180, 90_000);
        permission.exchange.respond(200, """
                {
                  "code":2002,
                  "msg":"token=unit-test-secret username=example-user permission denied",
                  "data":null
                }
                """);
        GatewayException error = assertThrows(
                GatewayException.class,
                () -> permission.gateway.query(
                        "daily",
                        datedSecurityParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session(0, false)));
        assertEquals(ErrorKind.PERMISSION_DENIED, error.kind());
        assertEquals("TUSHARE_PERMISSION_DENIED", error.safeCode());
        assertFalse(error.getMessage().contains(TEST_TOKEN));
        assertFalse(error.getMessage().contains("example-user"));
    }

    @Test
    void invalidJsonIsStructureChangedRatherThanNetworkError() {
        Fixture fixture = fixture(properties(), 180, 90_000);
        fixture.exchange.respond(200, "text/html", "{not-json");
        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "daily",
                        datedSecurityParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session(0, false)));
        assertEquals(ErrorKind.STRUCTURE_CHANGED, error.kind());
        assertEquals("TUSHARE_RESPONSE_JSON_INVALID",
                error.safeCode());
        assertEquals(200, error.diagnostic().httpStatus());
        assertEquals("text/html",
                error.diagnostic().responseContentType());
        assertFalse(error.diagnostic().responseJsonValid());
    }

    @Test
    void perApiDailyBudgetHardStopsBeforeThirdHttp() {
        Fixture fixture = fixture(properties(), 180, 2);
        TushareManualBoundedSession session = session(0, false);
        fixture.exchange.respond(200, successEmpty());
        fixture.exchange.respond(200, successEmpty());
        for (int index = 0; index < 2; index++) {
            fixture.gateway.query(
                    "dividend",
                    stockParameters(),
                    List.of("ts_code"),
                    Duration.ofSeconds(5),
                    QueryMode.CONTROLLED_NO_RETRY,
                    session);
        }
        GatewayException error = assertThrows(
                GatewayException.class,
                () -> fixture.gateway.query(
                        "dividend",
                        stockParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session));
        assertEquals("TUSHARE_DAILY_API_BUDGET_EXHAUSTED",
                error.safeCode());
        assertEquals(2, fixture.exchange.requests.size());
    }

    @Test
    void rejectsChangedRowShape() {
        Fixture fixture = fixture(properties(), 180, 90_000);
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
                        datedSecurityParameters(),
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session(0, false)));
        assertEquals(ErrorKind.STRUCTURE_CHANGED, error.kind());
    }

    private Fixture fixture(
            TushareMarketFactProperties properties,
            int minuteLimit,
            int dailyLimit
    ) {
        AtomicLong now = new AtomicLong();
        TushareTokenRateLimiter limiter =
                new TushareTokenRateLimiter(
                        minuteLimit,
                        dailyLimit,
                        Duration.ofMinutes(1),
                        now::get,
                        () -> LocalDate.of(2026, 7, 30),
                        duration -> now.addAndGet(
                                duration.toNanos()));
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

    private TushareMarketFactProperties properties() {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken(TEST_TOKEN);
        return properties;
    }

    private ObjectNode stockParameters() {
        ObjectNode result = mapper.createObjectNode();
        result.put("ts_code", "600000.SH");
        return result;
    }

    private ObjectNode datedSecurityParameters() {
        ObjectNode result = stockParameters();
        result.put("start_date", "20250106");
        result.put("end_date", "20250107");
        return result;
    }

    private ObjectNode calendarParameters() {
        ObjectNode result = mapper.createObjectNode();
        result.put("exchange", "SSE");
        result.put("start_date", "20250106");
        result.put("end_date", "20250107");
        return result;
    }

    private static TushareManualBoundedSession session(
            int consumed,
            boolean retry
    ) {
        return new TushareManualBoundedSession(
                10,
                Set.of("600000.SH", "000001.SZ"),
                Set.of("SSE", "SZSE"),
                LocalDate.of(2025, 1, 6),
                LocalDate.of(2025, 1, 7),
                TushareManualBoundedSession.F1A_ALLOWED_ENDPOINTS,
                retry,
                consumed);
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

        private void respond(int status, String contentType, String body) {
            responses.addLast(
                    new TushareHttpApiGateway.HttpExchangeResult(
                            status, contentType, body));
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
