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
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.Eligibility;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.RuntimeQualification;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.OperationalReadiness;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TushareDedicatedResearchBatchServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 30);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T04:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void executesOneTwoAndThreeSymbolBatchesWithExactBudgets() {
        for (int symbolCount = 1; symbolCount <= 3; symbolCount++) {
            SyntheticGateway gateway = new SyntheticGateway();
            PitMarketFactCaptureService capture = captureSuccess(
                    symbolCount);
            TushareDedicatedResearchBatchService service =
                    service(gateway, validGuard(), capture);

            var result = service.run(authorization(),
                    command(securities(symbolCount)));

            assertEquals(symbolCount * 3, gateway.calls());
            assertEquals(symbolCount * 3,
                    result.providerCallCount());
            assertEquals(symbolCount * 3,
                    result.sessionConsumedRequests());
            assertEquals(0, result.retryCount());
            assertEquals(symbolCount, result.symbolResults().size());
            assertEquals(symbolCount * 3, result.appendedCount());
            assertEquals(0, result.idempotentCount());
            assertEquals(
                    RuntimeQualification
                            .REDUCED_RESEARCH_FORMULA_ONLY,
                    result.runtimeEligibility()
                            .runtimeQualification());
            assertEquals(
                    OperationalReadiness.NOT_ACCEPTED,
                    result.operationalReadiness());
            assertEquals(
                    Eligibility.YES,
                    result.runtimeEligibility().systemKnowledgeOnly());
            assertEquals(
                    Eligibility.NO,
                    result.runtimeEligibility().productionEligible());
            assertEquals(
                    Eligibility.NO,
                    result.runtimeEligibility().agentDecisionEligible());
            assertEquals(
                    Eligibility.NO,
                    result.runtimeEligibility()
                            .backtestExecutionEligible());
            assertEquals(
                    Eligibility.NO,
                    result.runtimeEligibility().f2bEligible());
            assertEquals(
                    Eligibility.NO,
                    result.runtimeEligibility().f3Eligible());
            assertEquals(
                    symbols(securities(symbolCount)),
                    result.symbols());
        }
    }

    @Test
    void invalidAuthorizationAndDatabaseFailBeforeProvider() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        var service = service(gateway, validGuard(), capture);
        var original = authorization();
        var forged =
                new TushareDedicatedResearchBatchAuthorization(
                        "FORGED",
                        original.adapterVersion(),
                        original.accountScope(),
                        original.usageQualification(),
                        original.writtenPermissionCompleteness(),
                        original.runtimeMode(),
                        original.runNamespace(),
                        original.formalEligibility(),
                        original.maximumSymbols(),
                        original.maximumNaturalDays(),
                        original.maximumProviderRequests(),
                        original.allowedFactTypes(),
                        original.automaticRetryPolicy(),
                        original.normalBusinessDatabase(),
                        original.scheduler(),
                        original.shadow(),
                        original.agentDecision(),
                        original.backtestExecution(),
                        original.investmentAdvice(),
                        original.trading());

        assertThrows(IllegalArgumentException.class, () ->
                service.run(forged, command(securities(1))));
        assertEquals(0, gateway.calls());
        verify(capture, never())
                .captureAuthorizedDedicatedResearchBatch(
                        any(), any(), any(), any());

        var unsafe = service(
                gateway,
                new TushareDedicatedResearchPersistenceGuard(
                        () -> new TushareDedicatedResearchPersistenceGuard
                                .SchemaState(
                                "stock_quant",
                                "stock_quant_research",
                                "jdbc:postgresql://127.0.0.1:55433/"
                                        + "stock_quant",
                                "tushare_research",
                                "tushare_research",
                                TushareDedicatedResearchPersistenceGuard
                                        .REQUIRED_MIGRATIONS,
                                10_001,
                                false)),
                capture);
        assertThrows(
                TushareDedicatedResearchPersistenceGuard
                        .GuardException.class,
                () -> unsafe.run(
                        authorization(), command(securities(1))));
        assertEquals(0, gateway.calls());
    }

    @Test
    void providerFailureOnSecondSymbolCreatesNoCapture() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.failAtCall = 4;
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        var service = service(gateway, validGuard(), capture);

        var error = assertThrows(
                TushareDedicatedResearchBatchService
                        .RuntimeBlockedException.class,
                () -> service.run(
                        authorization(), command(securities(2))));

        assertEquals("SYNTHETIC_PROVIDER_FAILURE", error.safeCode());
        assertEquals(4, gateway.calls());
        verify(capture, never())
                .captureAuthorizedDedicatedResearchBatch(
                        any(), any(), any(), any());
    }

    @Test
    void incompleteFactWindowCreatesNoCapture() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.emptyFactor = true;
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);

        assertThrows(
                TushareDedicatedResearchBatchService
                        .RuntimeBlockedException.class,
                () -> service(gateway, validGuard(), capture).run(
                        authorization(), command(securities(1))));

        assertEquals(3, gateway.calls());
        verify(capture, never())
                .captureAuthorizedDedicatedResearchBatch(
                        any(), any(), any(), any());
    }

    @Test
    void closedCalendarUsesSharedFactGateAndCreatesNoCapture() {
        SyntheticGateway gateway = new SyntheticGateway();
        gateway.closedCalendar = true;
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);

        var error = assertThrows(
                TushareDedicatedResearchBatchService
                        .RuntimeBlockedException.class,
                () -> service(gateway, validGuard(), capture).run(
                        authorization(), command(securities(1))));

        assertEquals(
                "TUSHARE_DEDICATED_RESEARCH_FACT_WINDOW_INCOMPLETE",
                error.safeCode());
        assertEquals(3, gateway.calls());
        verify(capture, never())
                .captureAuthorizedDedicatedResearchBatch(
                        any(), any(), any(), any());
    }

    @Test
    void captureFailurePropagatesAfterExactProviderBudget() {
        SyntheticGateway gateway = new SyntheticGateway();
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        when(capture.captureAuthorizedDedicatedResearchBatch(
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException(
                        "synthetic capture failure"));

        assertThrows(
                IllegalStateException.class,
                () -> service(gateway, validGuard(), capture).run(
                        authorization(), command(securities(3))));

        assertEquals(9, gateway.calls());
    }

    @Test
    void oneSymbolSessionRejectsFourthAttemptBeforeGatewayAccounting() {
        SyntheticGateway gateway = new SyntheticGateway();
        List<SecuritySelection> values = securities(1);
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1eDedicatedLocalManual(
                        values, DATE);
        TushareMarketFactProvider provider = provider(gateway);
        var selection = values.get(0);
        var request = new MarketFactProviderModels.MarketFactRequest(
                MarketFactProviderModels.RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        selection.symbol(), selection.exchange()),
                selection.symbol(),
                selection.exchange(),
                DATE,
                DATE,
                Set.of(
                        MarketFactProviderModels.FactType.RAW_DAILY_BAR,
                        MarketFactProviderModels.FactType.ADJUSTMENT_FACTOR,
                        MarketFactProviderModels.FactType.TRADING_CALENDAR),
                Duration.ofSeconds(5));
        provider.fetchForDedicatedReducedResearch(request, session);
        int callsBefore = gateway.calls();
        ObjectNode parameters = new ObjectMapper().createObjectNode();
        parameters.put("ts_code", selection.providerInstrumentId());
        parameters.put("start_date", "20260730");
        parameters.put("end_date", "20260730");

        GatewayException error = assertThrows(
                GatewayException.class,
                () -> gateway.query(
                        "daily",
                        parameters,
                        List.of("ts_code"),
                        Duration.ofSeconds(5),
                        QueryMode.CONTROLLED_NO_RETRY,
                        session));

        assertEquals(
                "TUSHARE_REQUEST_BUDGET_EXHAUSTED",
                error.safeCode());
        assertEquals(callsBefore, gateway.calls());
        assertEquals(3, session.consumedBusinessRequests());
    }

    @Test
    void sameInputProducesDeterministicFormulaOnlyQfq() {
        SyntheticGateway gateway = new SyntheticGateway();
        var first = service(
                gateway, validGuard(), captureSuccess(1))
                .run(authorization(), command(securities(1)));
        SyntheticGateway repeatedGateway = new SyntheticGateway();
        var second = service(
                repeatedGateway, validGuard(), captureSuccess(1))
                .run(authorization(), command(securities(1)));

        assertEquals(
                first.symbolResults().get(0).qfqBars(),
                second.symbolResults().get(0).qfqBars());
        assertFalse(first.runtimeEligibility().formalEligible()
                == Eligibility.YES);
        assertFalse(first.runtimeEligibility().fullQfqEligible()
                == Eligibility.YES);
    }

    private static TushareDedicatedResearchBatchService service(
            TushareApiGateway gateway,
            TushareDedicatedResearchPersistenceGuard guard,
            PitMarketFactCaptureService capture
    ) {
        return new TushareDedicatedResearchBatchService(
                provider(gateway), guard, capture, CLOCK);
    }

    private static TushareMarketFactProvider provider(
            TushareApiGateway gateway
    ) {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken("synthetic-unit-test-token");
        return new TushareMarketFactProvider(
                new ObjectMapper(), properties, gateway);
    }

    private static TushareDedicatedResearchPersistenceGuard validGuard() {
        return new TushareDedicatedResearchPersistenceGuard(
                () -> validVerificationState(false));
    }

    private static TushareDedicatedResearchPersistenceGuard.SchemaState
    validVerificationState(boolean transactional) {
        return new TushareDedicatedResearchPersistenceGuard.SchemaState(
                "stock_quant_research",
                "stock_quant_research",
                "jdbc:postgresql://127.0.0.1:55433/"
                        + "stock_quant_research"
                        + "?currentSchema=tushare_research",
                "tushare_research",
                "tushare_research",
                TushareDedicatedResearchPersistenceGuard
                        .REQUIRED_MIGRATIONS,
                10_001,
                transactional);
    }

    private static PitMarketFactCaptureService captureSuccess(
            int symbols
    ) {
        PitMarketFactCaptureService capture =
                mock(PitMarketFactCaptureService.class);
        when(capture.captureAuthorizedDedicatedResearchBatch(
                any(), any(), any(), any())).thenReturn(
                dedicatedCapture(symbols));
        return capture;
    }

    private static PitMarketFactCaptureService.F1eDedicatedCaptureResult
    dedicatedCapture(int symbols) {
        var verification =
                new TushareDedicatedResearchPersistenceGuard
                        .Verification(
                        "stock_quant_research",
                        "stock_quant_research",
                        "jdbc:postgresql://127.0.0.1:55433/"
                                + "stock_quant_research"
                                + "?currentSchema=tushare_research",
                        "TUSHARE_DEDICATED_LOCAL_RESEARCH",
                        "tushare_research",
                        "tushare_research",
                        TushareDedicatedResearchPersistenceGuard
                                .REQUIRED_MIGRATIONS,
                        10_001,
                        true,
                        TushareDedicatedResearchPersistenceGuard
                                .DatabaseIdentityQualification.VERIFIED,
                        TushareDedicatedResearchPersistenceGuard
                                .SchemaQualification.VERIFIED);
        List<CaptureResult> results = new ArrayList<>();
        for (int index = 0; index < symbols; index++) {
            results.add(new CaptureResult(
                    index + 1L,
                    "batch-" + index,
                    index + 1L,
                    "dataset-" + index,
                    3,
                    3,
                    0,
                    true));
        }
        return new PitMarketFactCaptureService
                .F1eDedicatedCaptureResult(
                results, verification, verification);
    }

    private static TushareDedicatedResearchBatchAuthorization
    authorization() {
        return TushareDedicatedResearchBatchAuthorization
                .manualPersonalResearch();
    }

    private static TushareDedicatedResearchBatchCommand command(
            List<SecuritySelection> securities
    ) {
        return new TushareDedicatedResearchBatchCommand(
                DATE, securities, Duration.ofSeconds(5));
    }

    private static List<SecuritySelection> securities(int count) {
        List<SecuritySelection> all = List.of(
                new SecuritySelection("600000", "SSE"),
                new SecuritySelection("000001", "SZSE"),
                new SecuritySelection("600001", "SSE"));
        return all.subList(0, count);
    }

    private static List<String> symbols(
            List<SecuritySelection> values
    ) {
        return values.stream()
                .map(value -> TushareMarketFactProvider
                        .sourceInstrumentId(
                                value.symbol(), value.exchange()))
                .toList();
    }

    private static final class SyntheticGateway
            implements TushareApiGateway, F1cRateLimitedGateway {
        private final TushareTokenRateLimiter limiter =
                new TushareTokenRateLimiter(
                        TushareEndpointRateLimitPolicy
                                .frozenF1cPolicy());
        private int calls;
        private int failAtCall = -1;
        private boolean emptyFactor;
        private boolean closedCalendar;

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
            limiter.acquire(endpoint);
            calls++;
            if (calls == failAtCall) {
                throw new GatewayException(
                        ErrorKind.NETWORK_ERROR,
                        "SYNTHETIC_PROVIDER_FAILURE",
                        "synthetic failure",
                        1,
                        0,
                        null);
            }
            String date = parameters.path("start_date")
                    .asText("20260730");
            String tsCode = parameters.path("ts_code")
                    .asText("CALENDAR");
            String exchange = parameters.path("exchange")
                    .asText("SSE");
            List<List<JsonNode>> rows = switch (endpoint) {
                case "daily" -> List.of(List.of(
                        text(tsCode),
                        text(date),
                        decimal("10"),
                        decimal("12"),
                        decimal("9"),
                        decimal("11"),
                        decimal("100"),
                        decimal("10")));
                case "adj_factor" -> emptyFactor
                        ? List.of()
                        : List.of(List.of(
                        text(tsCode),
                        text(date),
                        decimal("2")));
                case "trade_cal" -> List.of(List.of(
                        text(exchange),
                        text(date),
                        decimal(closedCalendar ? "0" : "1"),
                        text(previousDate(date))));
                default -> throw new IllegalArgumentException(endpoint);
            };
            return new QueryResult(new Table(fields, rows), 1, 0);
        }

        @Override
        public F1cRateLimitedGatewayContract f1cRateLimitContract() {
            return F1cRateLimitedGatewayContract.from(
                    limiter.policy(), limiter);
        }

        int calls() {
            return calls;
        }

        private static String previousDate(String value) {
            return LocalDate.parse(
                            value, DateTimeFormatter.BASIC_ISO_DATE)
                    .minusDays(1)
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
        }

        private static JsonNode text(String value) {
            return TextNode.valueOf(value);
        }

        private static JsonNode decimal(String value) {
            return DecimalNode.valueOf(new BigDecimal(value));
        }
    }
}
