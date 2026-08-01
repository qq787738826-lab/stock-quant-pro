package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabaseGuard.ControlledVerification;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveMaterial;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.DatabaseIdentityQualification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.SchemaQualification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.Verification;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TushareControlledAcceptanceExecutorTest {
    private static final String COMMIT = "f68d84403ebb82babe92a1cb0f78d845ed39547a";
    private static final String SHA = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-01T01:00:00Z");

    @Test
    void committedRunningPrecedesProviderAndFailurePersistsAttemptCount() {
        TushareControlledAcceptanceExecutionRepository repository =
                mock(TushareControlledAcceptanceExecutionRepository.class);
        TushareControlledAcceptanceDatabaseGuard guard =
                mock(TushareControlledAcceptanceDatabaseGuard.class);
        TushareDedicatedResearchBatchService batchService =
                mock(TushareDedicatedResearchBatchService.class);
        TushareControlledAcceptanceReadbackService readback =
                mock(TushareControlledAcceptanceReadbackService.class);
        TushareControlledAcceptanceEvaluator evaluator =
                mock(TushareControlledAcceptanceEvaluator.class);
        when(guard.verifyBeforeProvider()).thenReturn(verification());
        when(batchService.totalProviderAttemptCount()).thenReturn(0L, 1L);
        when(batchService.run(any(), any())).thenThrow(
                new IllegalStateException("TUSHARE_TIMEOUT"));

        TushareControlledAcceptanceExecutor executor =
                new TushareControlledAcceptanceExecutor(
                        repository, guard, batchService, readback, evaluator,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        SecuritySelection security = new SecuritySelection("600000", "SSE");
        TushareControlledAcceptanceAuthorization authorization =
                TushareControlledAcceptanceAuthorization.issueUserApprovedDurable(
                        "F1FB1_EXEC_001", COMMIT, SHA, security,
                        LocalDate.of(2025, 1, 2), NOW.minusSeconds(1),
                        NOW.plusSeconds(60));
        TushareDedicatedResearchBatchCommand command =
                new TushareDedicatedResearchBatchCommand(
                        LocalDate.of(2025, 1, 2), List.of(security),
                        Duration.ofSeconds(10));

        assertThrows(IllegalStateException.class, () -> executor.executeOnce(
                authorization, command,
                TushareControlledAcceptanceBuildProof.verifiedTestProof(COMMIT, SHA),
                ExecutionSource.TEST,
                () -> List.of(SensitiveMaterial.register("test-secret-value"))));

        InOrder order = inOrder(repository, batchService);
        order.verify(repository).reserve(any());
        order.verify(repository).markRunning("F1FB1_EXEC_001");
        order.verify(batchService).totalProviderAttemptCount();
        order.verify(batchService).run(any(), eq(command));
        order.verify(batchService).totalProviderAttemptCount();
        order.verify(repository).markFailed(
                eq("F1FB1_EXEC_001"), eq(ExecutionStatus.RUNNING),
                eq(ExecutionStatus.FAILED_PROVIDER), eq("PROVIDER"),
                eq("TUSHARE_TIMEOUT"), eq(1));
        verifyNoInteractions(readback);
    }

    @Test
    void classifiesCleanFailuresByTheirActualExecutionBoundary() {
        assertFailure("TUSHARE_TIMEOUT", 0,
                ExecutionStatus.FAILED_PROVIDER, "PROVIDER");
        assertFailure("TUSHARE_QFQ_FACTOR_INVALID", 0,
                ExecutionStatus.FAILED_QFQ, "QFQ");
        assertFailure("TUSHARE_DEDICATED_RESEARCH_CAPTURE_RESULT_INVALID", 0,
                ExecutionStatus.FAILED_PERSISTENCE, "PERSISTENCE");
        assertFailure("TUSHARE_CONTROLLED_ACCEPTANCE_READBACK_IDENTITY_CHANGED", 0,
                ExecutionStatus.FAILED_DATABASE_GUARD, "DATABASE_GUARD");
        assertFailure("TUSHARE_CONTROLLED_ACCEPTANCE_TYPED_FACT_READBACK_INVALID", 0,
                ExecutionStatus.FAILED_VALIDATION, "VALIDATION");
        assertFailure("TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_REQUIRED", -1,
                ExecutionStatus.FAILED_OUTPUT_AUDIT, "OUTPUT_AUDIT");
    }

    private static void assertFailure(
            String reason,
            long attemptsBefore,
            ExecutionStatus expectedStatus,
            String expectedStage
    ) {
        var failure = TushareControlledAcceptanceExecutor.classifyCleanFailure(
                new IllegalStateException(reason), attemptsBefore);
        assertEquals(expectedStatus, failure.status());
        assertEquals(expectedStage, failure.stage());
        assertEquals(reason, failure.reasonCode());
    }

    private static ControlledVerification verification() {
        Verification base = new Verification(
                "stock_quant_research", "stock_quant_research",
                "jdbc:postgresql://127.0.0.1:54321/stock_quant_research",
                TushareDedicatedResearchPersistenceGuard.DATABASE_PURPOSE,
                "tushare_research", "tushare_research",
                TushareDedicatedResearchPersistenceGuard.REQUIRED_MIGRATIONS,
                1234, false, DatabaseIdentityQualification.VERIFIED,
                SchemaQualification.VERIFIED);
        return new ControlledVerification(
                base, List.of("13", "14"),
                TushareControlledAcceptanceDatabaseGuard.GOVERNANCE_HISTORY_TABLE,
                14);
    }
}
