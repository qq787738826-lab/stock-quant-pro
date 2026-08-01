package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceAuthorization.ControlledEndpoint;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.AtomicCommitResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.AuthorizationConsumptionQualification;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.CodeBaselineQualification;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.ExecutionEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.FailureStage;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.FormulaOnlyQfqSummary;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.SystemKnowledgeEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.SensitiveOutputQualification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.Eligibility;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.RuntimeQualification;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchBatchResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.GuardException;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.Verification;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, non-controller F1F controlled-acceptance preparation entry.
 *
 * <p>A successful invocation only creates a CANDIDATE. F1F-A cannot create a
 * PASSED evidence record or open operational, scheduler, Agent, backtest,
 * Shadow or trading capabilities.</p>
 */
@Service
public final class TushareControlledAcceptanceService {

    private final TushareDedicatedResearchBatchService batchService;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final Clock clock;
    private final String activeCodeBaseline;

    public TushareControlledAcceptanceService(
            TushareDedicatedResearchBatchService batchService,
            TushareDedicatedResearchPersistenceGuard guard,
            @Qualifier("agentTemporalClock") Clock clock,
            @Value("${stockquant.market-facts.tushare.controlled-acceptance-code-baseline:UNSET}")
            String activeCodeBaseline
    ) {
        this.batchService = Objects.requireNonNull(
                batchService, "batchService");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeCodeBaseline = Objects.requireNonNull(
                activeCodeBaseline, "activeCodeBaseline");
    }

    public TushareControlledAcceptanceQualification
    executePreparedAcceptance(
            TushareControlledAcceptanceAuthorization authorization,
            TushareDedicatedResearchBatchCommand command
    ) {
        Objects.requireNonNull(authorization, "authorization");
        Instant startedAt = clock.instant();
        try {
            authorization.validatePreflight(
                    command, activeCodeBaseline, startedAt);
            Verification preProvider = guard.verifyBeforeProvider();
            authorization.validateAndConsume(
                    command,
                    activeCodeBaseline,
                    startedAt,
                    preProvider);
            TushareDedicatedResearchBatchResult result = batchService.run(
                    TushareDedicatedResearchBatchAuthorization
                            .manualPersonalResearch(),
                    command);
            Instant endedAt = clock.instant();
            ExecutionEvidence evidence = executionEvidence(
                    authorization,
                    result,
                    startedAt,
                    endedAt,
                    preProvider);
            return TushareControlledAcceptanceQualification
                    .preparedCandidate(
                            evidence,
                            authorization.expiresAt(),
                            activeCodeBaseline,
                            endedAt);
        } catch (RuntimeException error) {
            return TushareControlledAcceptanceQualification.failed(
                    authorization.acceptanceId(),
                    authorization.codeBaselineCommit(),
                    failureStage(error),
                    safeReasonCode(error),
                    clock.instant());
        }
    }

    private static ExecutionEvidence executionEvidence(
            TushareControlledAcceptanceAuthorization authorization,
            TushareDedicatedResearchBatchResult result,
            Instant startedAt,
            Instant endedAt,
            Verification preProvider
    ) {
        Objects.requireNonNull(result, "result");
        validateResult(authorization, result, preProvider);
        Map<ControlledEndpoint, Integer> endpointCalls =
                new EnumMap<>(ControlledEndpoint.class);
        authorization.endpoints().forEach(
                endpoint -> endpointCalls.put(endpoint, 1));
        Map<FactType, Integer> factCounts = new EnumMap<>(FactType.class);
        factCounts.put(FactType.RAW_DAILY_BAR, 1);
        factCounts.put(FactType.ADJUSTMENT_FACTOR, 1);
        factCounts.put(FactType.TRADING_CALENDAR, 1);
        List<Long> batchIds = result.symbolResults().stream()
                .map(value -> value.captureResult().batchId())
                .toList();
        int qfqBars = result.symbolResults().stream()
                .mapToInt(value -> value.qfqBars().size())
                .sum();
        return new ExecutionEvidence(
                authorization.acceptanceId() + "_EVIDENCE",
                authorization.acceptanceId(),
                authorization.codeBaselineCommit(),
                CodeBaselineQualification.CONFIG_DECLARED_EXACT_MATCH,
                authorization.providerCode(),
                result.databaseIdentity().currentDatabase(),
                result.databaseIdentity().currentUser(),
                result.databaseIdentity().currentSchema(),
                result.databaseIdentity().appliedMigrations().size(),
                result.symbols().size(),
                1,
                result.symbols().get(0),
                result.tradeDate(),
                authorization.endpoints(),
                endpointCalls,
                result.providerCallCount(),
                result.retryCount(),
                AuthorizationConsumptionQualification
                        .OBJECT_INSTANCE_CAS_ONLY,
                startedAt,
                endedAt,
                batchIds,
                factCounts,
                AtomicCommitResult.COMMITTED_ATOMICALLY,
                true,
                true,
                "REGULAR",
                new SystemKnowledgeEvidence(
                        result.observedAt(), true, true, true, false),
                new FormulaOnlyQfqSummary(
                        qfqBars, true, false, false, false),
                SensitiveOutputQualification.NOT_ATTESTED,
                false,
                false,
                Set.of(),
                false,
                false,
                false,
                "CONTROLLED_ACCEPTANCE_EXECUTION_SUMMARY");
    }

    private static void validateResult(
            TushareControlledAcceptanceAuthorization authorization,
            TushareDedicatedResearchBatchResult result,
            Verification preProvider
    ) {
        String sourceInstrumentId =
                authorization.security().providerInstrumentId();
        if (result.symbols().size() != 1
                || !result.symbols().get(0).equals(sourceInstrumentId)
                || !result.tradeDate().equals(authorization.tradeDate())
                || result.providerCallCount() != 3
                || result.retryCount() != 0
                || result.sessionConsumedRequests() != 3
                || result.symbolResults().size() != 1
                || result.appendedCount() + result.idempotentCount() != 3
                || result.runtimeEligibility().runtimeQualification()
                != RuntimeQualification.REDUCED_RESEARCH_FORMULA_ONLY
                || result.runtimeEligibility().systemKnowledgeOnly()
                != Eligibility.YES
                || result.runtimeEligibility().providerPitVerified()
                != Eligibility.NO
                || result.runtimeEligibility()
                .corporateActionLineageComplete() != Eligibility.NO
                || result.runtimeEligibility()
                .permanentSecurityIdentityVerified() != Eligibility.NO
                || result.runtimeEligibility().fullQfqEligible()
                != Eligibility.NO
                || result.runtimeEligibility().productionEligible()
                != Eligibility.NO
                || result.runtimeEligibility()
                .normalBusinessDatabaseEligible() != Eligibility.NO
                || result.runtimeEligibility().schedulerEligible()
                != Eligibility.NO
                || result.runtimeEligibility().agentDecisionEligible()
                != Eligibility.NO
                || result.runtimeEligibility()
                .backtestExecutionEligible() != Eligibility.NO
                || result.runtimeEligibility().f2bEligible()
                != Eligibility.NO
                || result.runtimeEligibility().f3Eligible()
                != Eligibility.NO
                || !preProvider.currentDatabase().equals(
                result.databaseIdentity().currentDatabase())
                || !preProvider.currentUser().equals(
                result.databaseIdentity().currentUser())
                || !preProvider.currentSchema().equals(
                result.databaseIdentity().currentSchema())
                || !preProvider.appliedMigrations().equals(
                result.databaseIdentity().appliedMigrations())) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_RESULT_INVALID");
        }
    }

    private static FailureStage failureStage(RuntimeException error) {
        if (error instanceof GuardException guardError) {
            return guardError.safeCode().contains("SCHEMA_VERSION")
                    ? FailureStage.SCHEMA_VERSION
                    : FailureStage.DATABASE_IDENTITY;
        }
        if (error instanceof GatewayException) {
            return FailureStage.PROVIDER_CALL;
        }
        if (error instanceof TushareDedicatedResearchBatchService
                .RuntimeBlockedException blocked) {
            String code = blocked.safeCode();
            if (code.contains("FACT_WINDOW")) {
                return FailureStage.RESPONSE_VALIDATION;
            }
            if (code.contains("RATE_LIMIT") || code.contains("CALL_")) {
                return FailureStage.PROVIDER_CALL;
            }
            if (code.contains("ADMISSION")) {
                return FailureStage.PROHIBITED_STAGE;
            }
            if (code.contains("QFQ")) {
                return FailureStage.QFQ_VALIDATION;
            }
            return FailureStage.PERSISTENCE_WRITE;
        }
        if (error instanceof IllegalArgumentException) {
            return FailureStage.AUTHORIZATION_VALIDATION;
        }
        return FailureStage.PERSISTENCE_WRITE;
    }

    private static String safeReasonCode(RuntimeException error) {
        String code;
        if (error instanceof GuardException guardError) {
            code = guardError.safeCode();
        } else if (error instanceof GatewayException gatewayError) {
            code = gatewayError.safeCode();
        } else if (error instanceof TushareDedicatedResearchBatchService
                .RuntimeBlockedException blocked) {
            code = blocked.safeCode();
        } else if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().matches("[A-Z0-9_:-]{1,256}")) {
            code = error.getMessage();
        } else {
            code = "TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED";
        }
        return code == null || !code.matches("[A-Z0-9_:-]{1,256}")
                ? "TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED" : code;
    }
}
