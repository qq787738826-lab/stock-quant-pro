package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM1TokenVerificationTest {
    private static final LocalDate TRADE_DATE = LocalDate.of(2025, 1, 3);

    @Test
    void authorizationFreezesOneCallAndRemainingStageBudget() {
        var authorization = TushareM1TokenVerificationAuthorization.from(
                authorization("2"));
        authorization.validateAt(Clock.fixed(
                Instant.parse("2026-08-11T03:01:00Z"), ZoneOffset.UTC));

        assertEquals("600000.SH",
                authorization.security().providerInstrumentId());
        assertEquals(36, authorization.cumulativeProviderCallsBefore());
        assertEquals(2, authorization.stageProviderCallsBefore());
    }

    @Test
    void authorizationRejectsUnknownSecretMismatchAndBudgetOverflow() {
        Properties unknown = authorization("2");
        unknown.setProperty("provider.token", "forbidden");
        assertThrows(IllegalArgumentException.class, () ->
                TushareM1TokenVerificationAuthorization.from(unknown));

        Properties wrongEndpoint = authorization("2");
        wrongEndpoint.setProperty("endpoint", "adj_factor");
        assertThrows(IllegalArgumentException.class, () ->
                TushareM1TokenVerificationAuthorization.from(wrongEndpoint));
        assertThrows(IllegalArgumentException.class, () ->
                TushareM1TokenVerificationAuthorization.from(
                        authorization("30")));
    }

    @Test
    void verifierMakesExactlyOneDailyRequestWithCanonicalParameters() {
        VerificationGateway gateway = new VerificationGateway(true);
        TushareManualBoundedSession session =
                TushareManualBoundedSession.m1TokenVerification(
                        "600000", "SSE", TRADE_DATE);

        var result = provider(gateway).verifyM1Token(request(), session);

        assertEquals(1, result.providerCallCount());
        assertEquals(0, result.retryCount());
        assertEquals(1, result.mappedRowCount());
        assertTrue(result.targetRowPresent());
        assertEquals(1, gateway.calls);
        assertEquals("daily", gateway.endpoint);
        assertEquals(Set.of("ts_code", "start_date", "end_date"),
                gateway.parameterNames);
        assertEquals("600000.SH", gateway.tsCode);
        assertEquals("20250103", gateway.startDate);
        assertEquals("20250103", gateway.endDate);

        assertThrows(TushareApiGateway.GatewayException.class, () ->
                provider(gateway).verifyM1Token(request(), session));
        assertEquals(1, gateway.calls);
    }

    @Test
    void verifierDoesNotAcceptAResponseWithoutTheTargetIdentity() {
        var result = provider(new VerificationGateway(false)).verifyM1Token(
                request(), TushareManualBoundedSession.m1TokenVerification(
                        "600000", "SSE", TRADE_DATE));

        assertEquals(0, result.mappedRowCount());
        assertFalse(result.targetRowPresent());
    }

    @Test
    void providerOnlyAuditRequiresExactlyTheTushareToken() throws Exception {
        char[] token = "unit-audit-material".toCharArray();
        var captured = TushareControlledAcceptanceOutputAudit
                .captureProviderOnlyProcess(registry -> {
                    registry.register(SensitiveKind.TUSHARE_TOKEN, token);
                    return 1;
                });
        assertEquals(1, captured.value());
        assertTrue(captured.auditResult().clean());
        assertThrows(TushareControlledAcceptanceOutputAudit
                .CapturedExecutionException.class, () ->
                TushareControlledAcceptanceOutputAudit
                        .captureProviderOnlyProcess(registry -> 1));
    }

    @Test
    void sanitizedResultModelIsJacksonSerializableInPackagedRuntime()
            throws Exception {
        var result = new TushareM1TokenVerificationRunner.VerificationResult(
                "M1_TUSHARE_TOKEN_VERIFICATION_RESULT_V1",
                "M1TOKEN_20260811T030000Z_ABCDEF123456", "RUNNING",
                "a".repeat(40), "b".repeat(64), "daily", "600000",
                "SSE", TRADE_DATE, 0, 0, 2, 2, 36, 0, 0, 0, false,
                null, null, "NOT_RUN", false,
                new TushareM1TokenVerificationRunner.Audit(
                        false, false, 0),
                null, Instant.parse("2026-08-11T03:00:00Z"),
                Instant.parse("2026-08-11T03:00:00Z"), "CONSUMED",
                false, Map.of("databaseConnected", false));

        String json = new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(result);
        assertTrue(json.contains("\"verificationId\":"
                + "\"M1TOKEN_20260811T030000Z_ABCDEF123456\""));
        assertTrue(json.contains("\"databaseConnected\":false"));
    }

    private static TushareMarketFactProvider provider(
            TushareApiGateway gateway
    ) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setMaximumRateLimitRetries(0);
        properties.setToken("M1_TOKEN_VERIFICATION_UNIT_VALUE");
        return new TushareMarketFactProvider(
                new ObjectMapper(), properties, gateway);
    }

    private static MarketFactRequest request() {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        "600000", "SSE"),
                "600000", "SSE", TRADE_DATE, TRADE_DATE,
                Set.of(FactType.RAW_DAILY_BAR), Duration.ofSeconds(5));
    }

    private static Properties authorization(String callsBefore) {
        Properties values = new Properties();
        values.setProperty("authorization.status", "USER_APPROVED");
        values.setProperty("authorization.version",
                "M1_TUSHARE_TOKEN_VERIFICATION_V1");
        values.setProperty("verification.id",
                "M1TOKEN_20260811T030000Z_ABCDEF123456");
        values.setProperty("git.commit", "a".repeat(40));
        values.setProperty("artifact.sha256", "b".repeat(64));
        values.setProperty("build.proof.path",
                "D:/repo/quant-server/target/m1.jar.f1f-b2-proof.properties");
        values.setProperty("provider", "TUSHARE");
        values.setProperty("endpoint", "daily");
        values.setProperty("security.symbol", "600000");
        values.setProperty("security.exchange", "SSE");
        values.setProperty("trade.date", "2025-01-03");
        values.setProperty("endpoint.daily.requests", "1");
        values.setProperty("maximum.provider.requests", "1");
        values.setProperty("retry.budget", "0");
        values.setProperty("redirects", "NEVER");
        values.setProperty("provider.historical.baseline", "34");
        values.setProperty("provider.stage.limit", "30");
        values.setProperty("provider.cumulative.limit", "64");
        values.setProperty("provider.stage.calls.before", callsBefore);
        values.setProperty("issued.at", "2026-08-11T03:00:00Z");
        values.setProperty("expires.at", "2026-08-11T03:30:00Z");
        values.setProperty("purpose",
                "M1_RESEARCH_DATA_READY_TOKEN_VERIFICATION");
        values.setProperty("execution.source",
                "M1_TUSHARE_TOKEN_VERIFICATION_MANUAL");
        values.setProperty("user.approval.reference",
                "USER_APPROVED_M1_TOKEN_VERIFICATION");
        return values;
    }

    private static final class VerificationGateway
            implements TushareApiGateway {
        private final boolean includeTarget;
        private int calls;
        private String endpoint;
        private Set<String> parameterNames;
        private String tsCode;
        private String startDate;
        private String endDate;

        private VerificationGateway(boolean includeTarget) {
            this.includeTarget = includeTarget;
        }

        @Override
        public QueryResult query(
                String value,
                ObjectNode parameters,
                List<String> fields,
                Duration timeout,
                QueryMode mode,
                TushareManualBoundedSession session
        ) {
            session.authorizeAndReserve(value, parameters);
            calls++;
            endpoint = value;
            Set<String> names = new HashSet<>();
            parameters.fieldNames().forEachRemaining(names::add);
            parameterNames = Set.copyOf(names);
            tsCode = parameters.path("ts_code").asText();
            startDate = parameters.path("start_date").asText();
            endDate = parameters.path("end_date").asText();
            String responseCode = includeTarget ? "600000.SH" : "000001.SZ";
            List<JsonNode> row = List.of(
                    TextNode.valueOf(responseCode),
                    TextNode.valueOf("20250103"),
                    decimal("10.00"), decimal("10.80"), decimal("9.80"),
                    decimal("10.50"), decimal("1000"), decimal("10500"));
            return new QueryResult(new Table(fields, List.of(row)), 1, 0);
        }

        private static JsonNode decimal(String value) {
            return DecimalNode.valueOf(new BigDecimal(value));
        }
    }
}
