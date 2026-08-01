package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Decision;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.RedactedEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Reservation;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceEvaluator.EncodedEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveMaterial;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchBatchResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit F1F-B execution boundary. It is intentionally not a Spring bean,
 * controller, runner or scheduled task and has no downstream-stage dependency.
 */
public final class TushareControlledAcceptanceExecutor {
    private final TushareControlledAcceptanceExecutionRepository repository;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final TushareDedicatedResearchBatchService batchService;
    private final TushareControlledAcceptanceReadbackService readbackService;
    private final TushareControlledAcceptanceEvaluator evaluator;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public TushareControlledAcceptanceExecutor(
            TushareControlledAcceptanceExecutionRepository repository,
            TushareDedicatedResearchPersistenceGuard guard,
            TushareDedicatedResearchBatchService batchService,
            TushareControlledAcceptanceReadbackService readbackService,
            TushareControlledAcceptanceEvaluator evaluator,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.batchService = Objects.requireNonNull(batchService, "batchService");
        this.readbackService = Objects.requireNonNull(readbackService, "readbackService");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
        if (TushareControlledAcceptanceBoundaryAttestor.attest(getClass())
                != TushareControlledAcceptanceExecution.ProhibitedStageAttestation
                .VERIFIED_UNREACHABLE) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_BOUNDARY_NOT_ISOLATED");
        }
    }

    Decision executeOnce(
            TushareControlledAcceptanceAuthorization authorization,
            TushareDedicatedResearchBatchCommand command,
            VerifiedBuildProof buildProof,
            ExecutionSource executionSource,
            SensitiveMaterialSource sensitiveMaterialSource
    ) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(buildProof, "buildProof").validate();
        Objects.requireNonNull(executionSource, "executionSource");
        Objects.requireNonNull(sensitiveMaterialSource, "sensitiveMaterialSource");
        List<SensitiveMaterial> sensitiveMaterials = List.copyOf(
                Objects.requireNonNull(sensitiveMaterialSource.materials(),
                        "sensitiveMaterials"));
        if (executionSource == ExecutionSource.REAL_CONTROLLED_ACCEPTANCE
                && sensitiveMaterials.isEmpty()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_REQUIRED");
        }
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateExactScope(authorization, command, buildProof, executionSource, startedAt);
        TushareDedicatedResearchPersistenceGuard.Verification preProvider =
                guard.verifyBeforeProvider();
        authorization.validatePreflight(command, buildProof.gitCommit(), startedAt);
        Reservation reservation = new Reservation(
                authorization.acceptanceId(), authorization.authorizationFingerprint(),
                executionSource, authorization.providerCode(),
                authorization.security().providerInstrumentId(), authorization.tradeDate(),
                authorization.endpoints(), authorization.codeBaselineCommit(),
                authorization.artifactSha256(), authorization.databaseIdentity(),
                authorization.databaseUser(), authorization.schemaName(),
                authorization.schemaVersion(), startedAt, authorization.expiresAt());
        repository.reserve(reservation);
        try {
            authorization.validateAndConsume(command, buildProof.gitCommit(),
                    startedAt, preProvider);
            repository.markRunning(authorization.acceptanceId());
        } catch (RuntimeException error) {
            repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RESERVED,
                    ExecutionStatus.FAILED_PRE_PROVIDER, "PRE_PROVIDER",
                    safeReason(error), 0);
            throw error;
        }

        ExecutionPayload payload;
        TushareControlledAcceptanceOutputAudit.AuditResult audit;
        try {
            Captured<ExecutionPayload> captured =
                    TushareControlledAcceptanceOutputAudit.capture(
                            sensitiveMaterials,
                            () -> Objects.requireNonNull(transaction.execute(status ->
                                    runAndReadback(command, startedAt))));
            payload = captured.value();
            audit = captured.auditResult();
            if (!audit.clean()) {
                repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RUNNING,
                        ExecutionStatus.FAILED_OUTPUT_AUDIT, "OUTPUT_AUDIT",
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_OUTPUT_DETECTED", 3);
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_OUTPUT_DETECTED");
            }
        } catch (TushareControlledAcceptanceOutputAudit.CapturedExecutionException error) {
            ExecutionStatus failure = error.auditResult().clean()
                    ? ExecutionStatus.FAILED_PROVIDER
                    : ExecutionStatus.FAILED_OUTPUT_AUDIT;
            repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RUNNING,
                    failure, failure == ExecutionStatus.FAILED_OUTPUT_AUDIT
                            ? "OUTPUT_AUDIT" : "EXECUTION",
                    failure == ExecutionStatus.FAILED_OUTPUT_AUDIT
                            ? "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_OUTPUT_DETECTED"
                            : "TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED", 0);
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED");
        } catch (Exception error) {
            repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RUNNING,
                    ExecutionStatus.FAILED_VALIDATION, "EXECUTION",
                    safeReason(error), 0);
            throw error instanceof RuntimeException runtime ? runtime
                    : blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED");
        }

        Instant endedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!endedAt.isAfter(startedAt)) {
            endedAt = startedAt.plus(1, ChronoUnit.MICROS);
        }
        RedactedEvidence evidence = new RedactedEvidence(
                authorization.acceptanceId(), executionSource,
                buildProof.gitCommit(), buildProof.actualArtifactSha256(),
                payload.result().providerCallCount(), payload.result().retryCount(),
                payload.batchId(), endpointCounts(), payload.readback(), audit,
                TushareControlledAcceptanceBoundaryAttestor.attest(getClass()),
                payload.formulaOnlyQfqValid(), false, false, false, false,
                startedAt, endedAt,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION);
        EncodedEvidence encoded = evaluator.encode(evidence);
        TushareControlledAcceptanceExecution.StoredExecution candidate =
                repository.markCandidate(authorization.acceptanceId(),
                        payload.batchId(), 3, encoded.json(), encoded.digest());
        Decision decision = evaluator.evaluateCandidate(candidate,
                repository.history(authorization.acceptanceId()), evidence, buildProof);
        if (decision.status() == ExecutionStatus.PASSED) {
            TushareControlledAcceptanceExecution.StoredExecution passed =
                    repository.markPassed(authorization.acceptanceId());
            return evaluator.reloadAndRevalidate(passed,
                    repository.history(authorization.acceptanceId()), buildProof);
        }
        return decision;
    }

    private ExecutionPayload runAndReadback(
            TushareDedicatedResearchBatchCommand command,
            Instant startedAt
    ) {
        TushareDedicatedResearchBatchResult result = batchService.run(
                TushareDedicatedResearchBatchAuthorization.manualPersonalResearch(), command);
        if (result.symbolResults().size() != 1
                || result.providerCallCount() != 3 || result.retryCount() != 0
                || result.sessionConsumedRequests() != 3
                || result.symbolResults().get(0).qfqBars().size() != 1) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RESULT_INVALID");
        }
        long batchId = result.symbolResults().get(0).captureResult().batchId();
        DatabaseReadbackEvidence readback = readbackService.readAndVerify(
                batchId, result.observedAt(), startedAt,
                clock.instant().truncatedTo(ChronoUnit.MICROS), result.databaseIdentity());
        return new ExecutionPayload(result, batchId, readback, true);
    }

    private static void validateExactScope(
            TushareControlledAcceptanceAuthorization authorization,
            TushareDedicatedResearchBatchCommand command,
            VerifiedBuildProof proof,
            ExecutionSource source,
            Instant now
    ) {
        authorization.validateFrozen();
        if (!authorization.durableConsumptionRecorded()
                || authorization.schemaVersion() != 14
                || !authorization.codeBaselineCommit().equals(proof.gitCommit())
                || !authorization.artifactSha256().equals(proof.actualArtifactSha256())
                || command.securities().size() != 1
                || command.expectedProviderRequests() != 3
                || !command.tradeDate().equals(authorization.tradeDate())
                || !command.securities().get(0).equals(authorization.security())
                || !now.isBefore(authorization.expiresAt())
                || source == ExecutionSource.REAL_CONTROLLED_ACCEPTANCE
                && !proof.governanceEligible()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_SCOPE_INVALID");
        }
    }

    private static Map<TushareControlledAcceptanceAuthorization.ControlledEndpoint, Integer>
    endpointCounts() {
        EnumMap<TushareControlledAcceptanceAuthorization.ControlledEndpoint, Integer> counts =
                new EnumMap<>(TushareControlledAcceptanceAuthorization.ControlledEndpoint.class);
        TushareControlledAcceptanceAuthorization.REQUIRED_ENDPOINTS
                .forEach(endpoint -> counts.put(endpoint, 1));
        return Map.copyOf(counts);
    }

    private static String safeReason(Throwable error) {
        String message = error.getMessage();
        return message != null && message.matches("[A-Z0-9_:-]{1,128}")
                ? message : "TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED";
    }

    @FunctionalInterface
    interface SensitiveMaterialSource {
        List<SensitiveMaterial> materials();
    }

    private record ExecutionPayload(
            TushareDedicatedResearchBatchResult result,
            long batchId,
            DatabaseReadbackEvidence readback,
            boolean formulaOnlyQfqValid
    ) {
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
