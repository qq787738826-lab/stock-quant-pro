package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.RunCommand;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchModels.RuntimeQualification;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TushareReducedResearchRuntimeServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 27);
    private static final LocalDate END = LocalDate.of(2026, 7, 28);
    private static final String SCHEMA =
            "f1c_tushare_research_"
                    + "00000000000000000000000000000001";
    private static final List<String> V1_TO_V13 = List.of(
            "1", "2", "3", "4", "5", "6", "7",
            "8", "9", "10", "11", "12", "13");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T08:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void requiresExactFrozenAuthorizationBeforeProviderCall() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        TushareReducedResearchRuntimeService runtime =
                runtime(gateway, validGuard(), capture);

        assertThrows(
                NullPointerException.class,
                () -> runtime.run(null, command(END)));
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.run(
                        forgedAuthorization(), command(END)));
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.run(
                        forgedAuthorization(
                                TushareMarketFactProvider.PROVIDER_CODE,
                                4,
                                TushareReducedResearchRuntimeAuthorization
                                        .AutomaticRetryPolicy.ENABLED,
                                TushareReducedResearchRuntimeAuthorization
                                        .RuntimePermission.ALLOWED,
                                TushareReducedResearchRuntimeAuthorization
                                        .RuntimePermission.ALLOWED),
                        command(END)));

        assertEquals(0, gateway.calls());
        verify(capture, never())
                .captureAuthorizedLimitedPersonalFormal(
                        any(), any(), any());
    }

    @Test
    void rejectsUnsafeSchemaBeforeProviderAndCapture() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        TushareReducedResearchRuntimeService runtime = runtime(
                gateway,
                new TushareReducedResearchPersistenceGuard(
                        () -> new TushareReducedResearchPersistenceGuard
                                .SchemaState(
                                "public", "public", V1_TO_V13)),
                capture);

        TushareReducedResearchPersistenceGuard.GuardException error =
                assertThrows(
                        TushareReducedResearchPersistenceGuard
                                .GuardException.class,
                        () -> runtime.run(authorization(), command(END)));

        assertEquals(
                "TUSHARE_REDUCED_RUNTIME_PUBLIC_SCHEMA_FORBIDDEN",
                error.safeCode());
        assertEquals(0, gateway.calls());
        verify(capture, never())
                .captureAuthorizedLimitedPersonalFormal(
                        any(), any(), any());
    }

    @Test
    void rejectsSchemaTargetChangeBeforeCapture() {
        AtomicInteger inspections = new AtomicInteger();
        String changedSchema =
                "f1c_tushare_research_"
                        + "00000000000000000000000000000002";
        TushareReducedResearchPersistenceGuard guard =
                new TushareReducedResearchPersistenceGuard(() -> {
                    String schema = inspections.getAndIncrement() == 0
                            ? SCHEMA : changedSchema;
                    return new TushareReducedResearchPersistenceGuard
                            .SchemaState(schema, schema, V1_TO_V13);
                });
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);

        TushareReducedResearchPersistenceGuard.GuardException error =
                assertThrows(
                        TushareReducedResearchPersistenceGuard
                                .GuardException.class,
                        () -> runtime(gateway, guard, capture)
                                .run(authorization(), command(END)));

        assertEquals(
                "TUSHARE_REDUCED_RUNTIME_ISOLATED_SCHEMA_REQUIRED",
                error.safeCode());
        assertEquals(3, gateway.calls());
        verify(capture, never())
                .captureAuthorizedLimitedPersonalFormal(
                        any(), any(), any());
    }

    @Test
    void executesExactlyThreeEndpointsWithoutRetryAndReturnsQualifiedQfq() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                captureResult(6, 0);
        TushareReducedResearchRuntimeService runtime =
                runtime(gateway, validGuard(), capture);

        var result = runtime.run(authorization(), command(END));

        assertEquals(
                List.of("daily", "adj_factor", "trade_cal"),
                gateway.endpoints());
        assertEquals(3, gateway.calls());
        assertEquals(3, result.providerCallCount());
        assertEquals(0, result.retryCount());
        assertEquals(3, result.sessionConsumedRequests());
        assertEquals(RuntimeQualification.REDUCED_RESEARCH_FORMULA_ONLY,
                result.runtimeQualification());
        assertTrue(result.systemKnowledgeOnly());
        assertFalse(result.providerPitVerified());
        assertFalse(result.corporateActionLineageComplete());
        assertFalse(result.permanentSecurityIdentityVerified());
        assertFalse(result.formalEligible());
        assertFalse(result.fullQfqEligible());
        assertFalse(result.productionEligible());
        assertFalse(result.agentDecisionEligible());
        assertFalse(result.backtestExecutionEligible());
        assertFalse(result.investmentAdviceEligible());
        assertFalse(result.tradingEligible());
        assertEquals(new BigDecimal("5.0000"),
                result.qfqBars().get(0).open());
        assertEquals(new BigDecimal("6.0000"),
                result.qfqBars().get(0).high());
        assertEquals(new BigDecimal("4.5000"),
                result.qfqBars().get(0).low());
        assertEquals(new BigDecimal("5.5000"),
                result.qfqBars().get(0).close());
        assertEquals(new BigDecimal("20.0000"),
                result.qfqBars().get(1).open());
        assertTrue(result.reasonCodes().contains(
                "CORPORATE_ACTION_LINEAGE_INCOMPLETE"));
        verify(capture, times(1))
                .captureAuthorizedLimitedPersonalFormal(
                        any(), any(), any());
    }

    @Test
    void sameInputsProduceSameFormulaResultAndCaptureCanBeIdempotent() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        when(capture.captureAuthorizedLimitedPersonalFormal(
                any(), any(), any()))
                .thenReturn(result(6, 0))
                .thenReturn(result(0, 6));
        TushareReducedResearchRuntimeService runtime =
                runtime(gateway, validGuard(), capture);

        var first = runtime.run(authorization(), command(END));
        var second = runtime.run(authorization(), command(END));

        assertEquals(first.qfqBars(), second.qfqBars());
        assertEquals(6, first.captureResult().appendedCount());
        assertEquals(6, second.captureResult().idempotentCount());
        assertEquals(6, gateway.calls());
    }

    @Test
    void fourthSessionRequestIsRejectedBeforeGatewayWork() {
        ObjectMapper mapper = new ObjectMapper();
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1cIsolatedManual(
                        "600000", "SSE", START, END);
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("ts_code", "600000.SH");
        parameters.put("start_date", "20260727");
        parameters.put("end_date", "20260728");
        session.authorizeAndReserve("daily", parameters);
        session.authorizeAndReserve("daily", parameters);
        session.authorizeAndReserve("daily", parameters);

        GatewayException error = assertThrows(
                GatewayException.class,
                () -> session.authorizeAndReserve(
                        "daily", parameters));

        assertEquals(
                "TUSHARE_REQUEST_BUDGET_EXHAUSTED",
                error.safeCode());
        assertEquals(3, session.consumedBusinessRequests());
        assertFalse(session.automaticRetryAllowed());
    }

    @Test
    void incompleteResponseNeverPersistsPartialFacts() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.behavior = Behavior.FAIL_FACTOR;
        assertBlockedWithoutCapture(
                gateway,
                "TUSHARE_REDUCED_RUNTIME_PROVIDER_RESPONSE_INCOMPLETE",
                command(END));
    }

    @Test
    void missingCalendarNeverPersists() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.behavior = Behavior.MISSING_CALENDAR;
        assertBlockedWithoutCapture(
                gateway,
                "TUSHARE_REDUCED_RUNTIME_CALENDAR_INCOMPLETE",
                command(END));
    }

    @Test
    void missingFactorNeverPersists() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.behavior = Behavior.MISSING_FACTOR;
        assertBlockedWithoutCapture(
                gateway,
                "TUSHARE_REDUCED_RUNTIME_FACT_WINDOW_INCOMPLETE",
                command(END));
    }

    @Test
    void missingAnchorNeverPersists() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.behavior = Behavior.MISSING_ANCHOR_FACTOR;
        assertBlockedWithoutCapture(
                gateway,
                "TUSHARE_REDUCED_RUNTIME_ANCHOR_INVALID",
                command(END));
    }

    @Test
    void rawAfterAnchorIsRejectedWithoutPersistence() {
        SyntheticGateway gateway = new SyntheticGateway();
        assertBlockedWithoutCapture(
                gateway,
                "TUSHARE_QFQ_TRADE_DATE_AFTER_ANCHOR",
                command(START));
    }

    @Test
    void formulaUsesExplicitAnchorAndNeverReadsDividend() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                captureResult(6, 0);
        var result = runtime(
                gateway, validGuard(), capture)
                .run(authorization(), command(END));

        assertNotEquals(
                result.qfqBars().get(0).open(),
                result.qfqBars().get(1).open());
        assertFalse(gateway.endpoints().contains("dividend"));
        assertFalse(gateway.endpoints().contains("stock_basic"));
        assertEquals(Set.of(
                        "daily", "adj_factor", "trade_cal"),
                Set.copyOf(gateway.endpoints()));
    }

    private static void assertBlockedWithoutCapture(
            SyntheticGateway gateway,
            String expectedCode,
            RunCommand command
    ) {
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        TushareReducedResearchRuntimeService runtime =
                runtime(gateway, validGuard(), capture);

        TushareReducedResearchRuntimeService.RuntimeBlockedException
                error = assertThrows(
                TushareReducedResearchRuntimeService
                        .RuntimeBlockedException.class,
                () -> runtime.run(authorization(), command));

        assertEquals(expectedCode, error.safeCode());
        verify(capture, never())
                .captureAuthorizedLimitedPersonalFormal(
                        any(), any(), any());
    }

    private static TushareReducedResearchRuntimeService runtime(
            SyntheticGateway gateway,
            TushareReducedResearchPersistenceGuard guard,
            PitMarketFactCaptureService capture
    ) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken("synthetic-unit-test-token");
        TushareMarketFactProvider provider =
                new TushareMarketFactProvider(
                        new ObjectMapper(), properties, gateway);
        return new TushareReducedResearchRuntimeService(
                provider, guard, capture, CLOCK);
    }

    private static TushareReducedResearchPersistenceGuard validGuard() {
        return new TushareReducedResearchPersistenceGuard(
                () -> new TushareReducedResearchPersistenceGuard
                        .SchemaState(SCHEMA, SCHEMA, V1_TO_V13));
    }

    private static PitMarketFactCaptureService captureResult(
            int appended,
            int idempotent
    ) {
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        when(capture.captureAuthorizedLimitedPersonalFormal(
                any(), any(), any()))
                .thenReturn(result(appended, idempotent));
        return capture;
    }

    private static CaptureResult result(
            int appended,
            int idempotent
    ) {
        return new CaptureResult(
                1L,
                "synthetic-batch",
                1L,
                "synthetic-dataset",
                6,
                appended,
                idempotent,
                true);
    }

    private static RunCommand command(LocalDate anchor) {
        return new RunCommand(
                "600000",
                "SSE",
                START,
                END,
                anchor,
                Duration.ofSeconds(5));
    }

    private static TushareReducedResearchRuntimeAuthorization
    authorization() {
        return TushareReducedResearchRuntimeAuthorization
                .f1cIsolatedManual();
    }

    private static TushareReducedResearchRuntimeAuthorization
    forgedAuthorization() {
        return forgedAuthorization(
                "FORGED_PROVIDER",
                3,
                TushareReducedResearchRuntimeAuthorization
                        .AutomaticRetryPolicy.DISABLED,
                TushareReducedResearchRuntimeAuthorization
                        .RuntimePermission.FORBIDDEN,
                TushareReducedResearchRuntimeAuthorization
                        .RuntimePermission.FORBIDDEN);
    }

    private static TushareReducedResearchRuntimeAuthorization
    forgedAuthorization(
            String providerCode,
            int maximumProviderRequests,
            TushareReducedResearchRuntimeAuthorization
                    .AutomaticRetryPolicy retryPolicy,
            TushareReducedResearchRuntimeAuthorization
                    .RuntimePermission normalBusinessDatabase,
            TushareReducedResearchRuntimeAuthorization
                    .RuntimePermission agentDecision
    ) {
        var original = authorization();
        return new TushareReducedResearchRuntimeAuthorization(
                providerCode,
                original.adapterVersion(),
                original.implementationScope(),
                original.runtimeMode(),
                original.runNamespace(),
                original.usageQualification(),
                original.formalEligibility(),
                original.maximumSymbols(),
                original.maximumNaturalDays(),
                maximumProviderRequests,
                original.allowedFactTypes(),
                retryPolicy,
                original.isolatedSchemaRequirement(),
                normalBusinessDatabase,
                original.scheduler(),
                original.shadow(),
                agentDecision,
                original.backtestExecution(),
                original.investmentAdvice(),
                original.trading());
    }

    private enum Behavior {
        NORMAL,
        FAIL_FACTOR,
        MISSING_CALENDAR,
        MISSING_FACTOR,
        MISSING_ANCHOR_FACTOR
    }

    private static final class SyntheticGateway
            implements TushareApiGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.ArrayList<String> endpoints =
                new java.util.ArrayList<>();
        private Behavior behavior = Behavior.NORMAL;

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
            calls.incrementAndGet();
            endpoints.add(endpoint);
            if (behavior == Behavior.FAIL_FACTOR
                    && "adj_factor".equals(endpoint)) {
                throw new GatewayException(
                        ErrorKind.PERMISSION_DENIED,
                        "SYNTHETIC_FACTOR_PERMISSION_DENIED",
                        "synthetic factor rejection",
                        1,
                        0,
                        null);
            }
            List<List<JsonNode>> rows = switch (endpoint) {
                case "daily" -> dailyRows();
                case "adj_factor" -> factorRows();
                case "trade_cal" -> calendarRows();
                default -> throw new IllegalArgumentException(endpoint);
            };
            return new QueryResult(
                    new Table(fields, rows), 1, 0);
        }

        int calls() {
            return calls.get();
        }

        List<String> endpoints() {
            return List.copyOf(endpoints);
        }

        private List<List<JsonNode>> dailyRows() {
            return List.of(
                    List.of(
                            text("600000.SH"),
                            text("20260727"),
                            decimal("10"),
                            decimal("12"),
                            decimal("9"),
                            decimal("11"),
                            decimal("100"),
                            decimal("10")),
                    List.of(
                            text("600000.SH"),
                            text("20260728"),
                            decimal("20"),
                            decimal("22"),
                            decimal("19"),
                            decimal("21"),
                            decimal("200"),
                            decimal("20")));
        }

        private List<List<JsonNode>> factorRows() {
            if (behavior == Behavior.MISSING_FACTOR) {
                return List.of(List.of(
                        text("600000.SH"),
                        text("20260728"),
                        decimal("2")));
            }
            if (behavior == Behavior.MISSING_ANCHOR_FACTOR) {
                return List.of(List.of(
                        text("600000.SH"),
                        text("20260727"),
                        decimal("1")));
            }
            return List.of(
                    List.of(
                            text("600000.SH"),
                            text("20260727"),
                            decimal("1")),
                    List.of(
                            text("600000.SH"),
                            text("20260728"),
                            decimal("2")));
        }

        private List<List<JsonNode>> calendarRows() {
            if (behavior == Behavior.MISSING_CALENDAR) {
                return List.of(List.of(
                        text("SSE"),
                        text("20260727"),
                        DecimalNode.valueOf(BigDecimal.ONE),
                        text("20260724")));
            }
            return List.of(
                    List.of(
                            text("SSE"),
                            text("20260727"),
                            DecimalNode.valueOf(BigDecimal.ONE),
                            text("20260724")),
                    List.of(
                            text("SSE"),
                            text("20260728"),
                            DecimalNode.valueOf(BigDecimal.ONE),
                            text("20260727")));
        }

        private static JsonNode text(String value) {
            return TextNode.valueOf(value);
        }

        private static JsonNode decimal(String value) {
            return DecimalNode.valueOf(new BigDecimal(value));
        }
    }
}
