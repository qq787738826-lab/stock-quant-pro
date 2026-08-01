package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.AcceptanceStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.FailureStage;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.SymbolResearchResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchBatchResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchQfqBar;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.DatabaseIdentityQualification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.SchemaQualification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.Verification;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TushareControlledAcceptanceServiceTest {

    private static final String BASELINE =
            TushareControlledAcceptanceQualification.PREPARATION_BASELINE;
    private static final String OTHER_BASELINE =
            "1111111111111111111111111111111111111111";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 30);
    private static final Instant NOW =
            Instant.parse("2026-08-01T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void successfulOfflineExecutionCreatesCandidateAndNotPassed() {
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);
        when(batch.run(any(), any())).thenReturn(successResult());
        var service = service(batch, validGuard(), BASELINE);
        var authorization = authorization(BASELINE);

        var result = service.executePreparedAcceptance(
                authorization, command());

        assertEquals(AcceptanceStatus.CANDIDATE, result.status());
        assertFalse(result.reducedResearchOperationalReady());
        assertTrue(authorization.consumed());
        assertEquals(3,
                result.executionEvidence().totalProviderCallCount());
        assertEquals(0, result.executionEvidence().retryCount());
        assertFalse(result.executionEvidence().tokenOutputDetected());
        assertFalse(result.executionEvidence()
                .normalBusinessDatabaseUsed());
        assertFalse(result.executionEvidence().publicSchemaUsed());
        assertTrue(result.executionEvidence()
                .startedProhibitedStages().isEmpty());
    }

    @Test
    void authorizationIsOneShotAndSecondUseDoesNotDelegate() {
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);
        when(batch.run(any(), any())).thenReturn(successResult());
        var service = service(batch, validGuard(), BASELINE);
        var authorization = authorization(BASELINE);

        assertEquals(AcceptanceStatus.CANDIDATE,
                service.executePreparedAcceptance(
                        authorization, command()).status());
        var repeated = service.executePreparedAcceptance(
                authorization, command());

        assertEquals(AcceptanceStatus.FAILED, repeated.status());
        assertEquals(
                FailureStage.AUTHORIZATION_VALIDATION,
                repeated.failureEvidence().stage());
        verify(batch, times(1)).run(any(), any());
    }

    @Test
    void baselineMismatchFailsBeforeDatabaseAndProvider() {
        AtomicInteger inspections = new AtomicInteger();
        TushareDedicatedResearchPersistenceGuard guard =
                new TushareDedicatedResearchPersistenceGuard(() -> {
                    inspections.incrementAndGet();
                    return validState();
                });
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);

        var result = service(batch, guard, OTHER_BASELINE)
                .executePreparedAcceptance(
                        authorization(BASELINE), command());

        assertEquals(AcceptanceStatus.FAILED, result.status());
        assertEquals(0, inspections.get());
        verify(batch, times(0)).run(any(), any());
    }

    @Test
    void expiredAuthorizationFailsBeforeDatabaseAndProvider() {
        AtomicInteger inspections = new AtomicInteger();
        TushareDedicatedResearchPersistenceGuard guard =
                new TushareDedicatedResearchPersistenceGuard(() -> {
                    inspections.incrementAndGet();
                    return validState();
                });
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);
        var expired = TushareControlledAcceptanceAuthorization
                .issueUserApprovedOneShot(
                        "F1F_ACCEPTANCE_EXPIRED",
                        BASELINE,
                        security(),
                        DATE,
                        NOW.minusSeconds(120),
                        NOW.minusSeconds(60));

        var result = service(batch, guard, BASELINE)
                .executePreparedAcceptance(expired, command());

        assertEquals(AcceptanceStatus.FAILED, result.status());
        assertEquals(
                FailureStage.AUTHORIZATION_VALIDATION,
                result.failureEvidence().stage());
        assertEquals(0, inspections.get());
        verify(batch, times(0)).run(any(), any());
    }

    @Test
    void commandScopeMismatchFailsBeforeDatabaseAndProvider() {
        AtomicInteger inspections = new AtomicInteger();
        TushareDedicatedResearchPersistenceGuard guard =
                new TushareDedicatedResearchPersistenceGuard(() -> {
                    inspections.incrementAndGet();
                    return validState();
                });
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);
        var mismatchedCommand = new TushareDedicatedResearchBatchCommand(
                DATE,
                List.of(new SecuritySelection("000001", "SZSE")),
                Duration.ofSeconds(5));

        var result = service(batch, guard, BASELINE)
                .executePreparedAcceptance(
                        authorization(BASELINE), mismatchedCommand);

        assertEquals(AcceptanceStatus.FAILED, result.status());
        assertEquals(
                FailureStage.AUTHORIZATION_VALIDATION,
                result.failureEvidence().stage());
        assertEquals(0, inspections.get());
        verify(batch, times(0)).run(any(), any());
    }

    @Test
    void databaseFailureHappensBeforeDelegatedRuntime() {
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);
        TushareDedicatedResearchPersistenceGuard guard =
                new TushareDedicatedResearchPersistenceGuard(() ->
                        new TushareDedicatedResearchPersistenceGuard
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
                                false));

        var result = service(batch, guard, BASELINE)
                .executePreparedAcceptance(
                        authorization(BASELINE), command());

        assertEquals(AcceptanceStatus.FAILED, result.status());
        assertEquals(
                FailureStage.DATABASE_IDENTITY,
                result.failureEvidence().stage());
        verify(batch, times(0)).run(any(), any());
    }

    @Test
    void providerFailureProducesOnlyRedactedFailureEvidence() {
        TushareDedicatedResearchBatchService batch =
                mock(TushareDedicatedResearchBatchService.class);
        when(batch.run(any(), any())).thenThrow(new GatewayException(
                ErrorKind.NETWORK_ERROR,
                "TUSHARE_NETWORK_ERROR",
                "synthetic safe failure",
                1,
                0,
                null));

        var result = service(batch, validGuard(), BASELINE)
                .executePreparedAcceptance(
                        authorization(BASELINE), command());

        assertEquals(AcceptanceStatus.FAILED, result.status());
        assertEquals(
                FailureStage.PROVIDER_CALL,
                result.failureEvidence().stage());
        assertEquals(
                "TUSHARE_NETWORK_ERROR",
                result.failureEvidence().safeReasonCode());
        assertFalse(result.toString().toLowerCase().contains("token"));
        assertFalse(result.toString().toLowerCase().contains("password"));
    }

    @Test
    void authorizationTextContainsNoCredentialMaterial() {
        String value = authorization(BASELINE).toString().toLowerCase();

        assertFalse(value.contains("token"));
        assertFalse(value.contains("password"));
        assertFalse(value.contains("jdbc:"));
    }

    private static TushareControlledAcceptanceService service(
            TushareDedicatedResearchBatchService batch,
            TushareDedicatedResearchPersistenceGuard guard,
            String baseline
    ) {
        return new TushareControlledAcceptanceService(
                batch, guard, CLOCK, baseline);
    }

    private static TushareControlledAcceptanceAuthorization authorization(
            String baseline
    ) {
        return TushareControlledAcceptanceAuthorization
                .issueUserApprovedOneShot(
                        "F1F_ACCEPTANCE_001",
                        baseline,
                        security(),
                        DATE,
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(600));
    }

    private static TushareDedicatedResearchBatchCommand command() {
        return new TushareDedicatedResearchBatchCommand(
                DATE, List.of(security()), Duration.ofSeconds(5));
    }

    private static SecuritySelection security() {
        return new SecuritySelection("600000", "SSE");
    }

    private static TushareDedicatedResearchPersistenceGuard validGuard() {
        return new TushareDedicatedResearchPersistenceGuard(
                TushareControlledAcceptanceServiceTest::validState);
    }

    private static TushareDedicatedResearchPersistenceGuard.SchemaState
    validState() {
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
                false);
    }

    private static TushareDedicatedResearchBatchResult successResult() {
        Verification before = verification();
        Verification after = verification();
        CaptureResult capture = new CaptureResult(
                101L,
                "batch-version",
                201L,
                "dataset-version",
                3,
                3,
                0,
                true);
        SymbolResearchResult symbol = new SymbolResearchResult(
                TushareMarketFactProvider.PROVIDER_CODE,
                security().providerInstrumentId(),
                DATE,
                3,
                0,
                1,
                1,
                1,
                List.of(new TushareDedicatedResearchQfqBar(
                        DATE,
                        BigDecimal.TEN,
                        BigDecimal.valueOf(12),
                        BigDecimal.valueOf(9),
                        BigDecimal.valueOf(11))),
                capture);
        return TushareDedicatedResearchBatchResult.formulaOnly(
                NOW,
                DATE,
                List.of(security().providerInstrumentId()),
                3,
                3,
                List.of(symbol),
                DatabaseExecutionIdentity.from(before, after),
                Set.of("TUSHARE_DEDICATED_LOCAL_RESEARCH_PATH"),
                RouteDecision.REDUCED_RESEARCH_ONLY);
    }

    private static Verification verification() {
        return new Verification(
                "stock_quant_research",
                "stock_quant_research",
                "jdbc:postgresql://127.0.0.1:55433/"
                        + "stock_quant_research"
                        + "?currentSchema=tushare_research",
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE,
                "tushare_research",
                "tushare_research",
                TushareDedicatedResearchPersistenceGuard
                        .REQUIRED_MIGRATIONS,
                10_001,
                true,
                DatabaseIdentityQualification.VERIFIED,
                SchemaQualification.VERIFIED);
    }
}
