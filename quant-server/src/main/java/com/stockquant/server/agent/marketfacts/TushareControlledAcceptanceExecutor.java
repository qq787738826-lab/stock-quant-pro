package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Decision;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.RedactedEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Reservation;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceEvaluator.EncodedEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceEvaluator.CandidateAssessment;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.Captured;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveMaterial;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchBatchResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit F1F-B execution boundary. It is intentionally not a Spring bean,
 * controller, runner or scheduled task and has no downstream-stage dependency.
 */
public final class TushareControlledAcceptanceExecutor {
    private final TushareControlledAcceptanceExecutionRepository repository;
    private final TushareControlledAcceptanceDatabaseGuard guard;
    private final TushareDedicatedResearchBatchService batchService;
    private final TushareControlledAcceptanceReadbackService readbackService;
    private final TushareControlledAcceptanceEvaluator evaluator;
    private final Clock clock;

    public TushareControlledAcceptanceExecutor(
            TushareControlledAcceptanceExecutionRepository repository,
            TushareControlledAcceptanceDatabaseGuard guard,
            TushareDedicatedResearchBatchService batchService,
            TushareControlledAcceptanceReadbackService readbackService,
            TushareControlledAcceptanceEvaluator evaluator,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.batchService = Objects.requireNonNull(batchService, "batchService");
        this.readbackService = Objects.requireNonNull(readbackService, "readbackService");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
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
        Instant startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateExactScope(authorization, command, buildProof, executionSource, startedAt);
        TushareControlledAcceptanceDatabaseGuard.ControlledVerification preProvider =
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
            authorization.validateAndConsumeDurable(command, buildProof.gitCommit(),
                    startedAt, preProvider);
            repository.markRunning(authorization.acceptanceId());
        } catch (RuntimeException error) {
            repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RESERVED,
                    ExecutionStatus.FAILED_PRE_PROVIDER, "PRE_PROVIDER",
                    safeReason(error), 0);
            throw error;
        }

        AtomicLong providerAttemptsBefore = new AtomicLong(-1L);
        ExecutionPayload payload;
        TushareControlledAcceptanceOutputAudit.AuditResult audit;
        try {
            Captured<ExecutionPayload> captured =
                    TushareControlledAcceptanceOutputAudit.captureAfterRegistration(
                            () -> List.copyOf(Objects.requireNonNull(
                                    sensitiveMaterialSource.materials(),
                                    "sensitiveMaterials")),
                            () -> {
                                long before = batchService.totalProviderAttemptCount();
                                providerAttemptsBefore.set(before);
                                return runAndReadback(command, startedAt, before);
                            });
            payload = captured.value();
            audit = captured.auditResult();
            if (!audit.clean()) {
                repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RUNNING,
                        ExecutionStatus.FAILED_OUTPUT_AUDIT, "OUTPUT_AUDIT",
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_OUTPUT_DETECTED",
                        providerAttemptsSince(providerAttemptsBefore.get()));
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_OUTPUT_DETECTED");
            }
        } catch (TushareControlledAcceptanceOutputAudit.CapturedExecutionException error) {
            FailureClassification failure = error.auditResult().clean()
                    ? classifyCleanFailure(error.getCause(),
                    providerAttemptsBefore.get())
                    : new FailureClassification(
                    ExecutionStatus.FAILED_OUTPUT_AUDIT, "OUTPUT_AUDIT",
                    "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_OUTPUT_DETECTED");
            repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RUNNING,
                    failure.status(), failure.stage(), failure.reasonCode(),
                    providerAttemptsSince(providerAttemptsBefore.get()));
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTION_FAILED");
        } catch (Exception error) {
            repository.markFailed(authorization.acceptanceId(), ExecutionStatus.RUNNING,
                    ExecutionStatus.FAILED_VALIDATION, "EXECUTION",
                    safeReason(error), providerAttemptsSince(providerAttemptsBefore.get()));
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
        CandidateAssessment candidateAssessment = evaluator.evaluateCandidate(candidate,
                repository.history(authorization.acceptanceId()), evidence, buildProof);
        if (candidateAssessment.persistedPassAuthorized()) {
            TushareControlledAcceptanceExecution.StoredExecution passed =
                    repository.markPassed(authorization.acceptanceId());
            return evaluator.reloadAndRevalidate(passed,
                    repository.history(authorization.acceptanceId()), buildProof);
        }
        return candidateAssessment.testDecision();
    }

    private ExecutionPayload runAndReadback(
            TushareDedicatedResearchBatchCommand command,
            Instant startedAt,
            long providerAttemptsBefore
    ) {
        TushareDedicatedResearchBatchResult result = batchService.run(
                TushareDedicatedResearchBatchAuthorization.manualPersonalResearch(), command);
        if (result.symbolResults().size() != 1
                || result.providerCallCount() != 3 || result.retryCount() != 0
                || result.sessionConsumedRequests() != 3
                || result.symbolResults().get(0).qfqBars().size() != 1) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RESULT_INVALID");
        }
        if (providerAttemptsSince(providerAttemptsBefore) != 3) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_PROVIDER_ATTEMPT_COUNT_INVALID");
        }
        long batchId = result.symbolResults().get(0).captureResult().batchId();
        var symbolResult = result.symbolResults().get(0);
        var security = command.securities().get(0);
        DatabaseReadbackEvidence readback = readbackService.readAndVerify(
                batchId, result.observedAt(), startedAt,
                clock.instant().truncatedTo(ChronoUnit.MICROS), result.databaseIdentity(),
                symbolResult.sourceInstrumentId(), security.symbol(),
                security.exchange(), command.tradeDate());
        guard.verifyBeforeProvider();
        return new ExecutionPayload(result, batchId, readback, true);
    }

    private int providerAttemptsSince(long before) {
        if (before < 0) {
            return 0;
        }
        long difference = batchService.totalProviderAttemptCount() - before;
        if (difference < 0 || difference > 3) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_PROVIDER_ATTEMPT_COUNT_INVALID");
        }
        return Math.toIntExact(difference);
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

    static FailureClassification classifyCleanFailure(
            Throwable error,
            long providerAttemptsBefore
    ) {
        String reason = safeReason(error);
        if (providerAttemptsBefore < 0
                || reason.startsWith(
                "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_")) {
            return new FailureClassification(
                    ExecutionStatus.FAILED_OUTPUT_AUDIT, "OUTPUT_AUDIT", reason);
        }
        if (hasCause(error, TushareApiGateway.GatewayException.class)
                || reason.startsWith("TUSHARE_TIMEOUT")
                || reason.startsWith("TUSHARE_NETWORK_")
                || reason.startsWith("TUSHARE_API_")
                || reason.startsWith("TUSHARE_PERMISSION_")
                || reason.startsWith("TUSHARE_RATE_LIMIT")) {
            return new FailureClassification(
                    ExecutionStatus.FAILED_PROVIDER, "PROVIDER", reason);
        }
        if (reason.startsWith("TUSHARE_QFQ_")) {
            return new FailureClassification(
                    ExecutionStatus.FAILED_QFQ, "QFQ", reason);
        }
        if (reason.startsWith("TUSHARE_DEDICATED_RESEARCH_CAPTURE_")
                || reason.startsWith("TUSHARE_REDUCED_RUNTIME_CAPTURE_")) {
            return new FailureClassification(
                    ExecutionStatus.FAILED_PERSISTENCE, "PERSISTENCE", reason);
        }
        if (reason.startsWith("TUSHARE_DEDICATED_RESEARCH_SCHEMA_")
                || reason.startsWith("TUSHARE_DEDICATED_RESEARCH_SEARCH_PATH_")
                || reason.startsWith("TUSHARE_DEDICATED_RESEARCH_DATABASE_")
                || reason.startsWith("TUSHARE_DEDICATED_RESEARCH_TRANSACTION_")
                || reason.startsWith("TUSHARE_CONTROLLED_ACCEPTANCE_GOVERNANCE_")
                || reason.startsWith("TUSHARE_CONTROLLED_ACCEPTANCE_DATABASE_")
                || reason.startsWith("TUSHARE_CONTROLLED_ACCEPTANCE_UNTRACKED_SCHEMA_")
                || reason.startsWith("TUSHARE_CONTROLLED_ACCEPTANCE_READBACK_IDENTITY_")
                || reason.startsWith("TUSHARE_CONTROLLED_ACCEPTANCE_POST_COMMIT_")) {
            return new FailureClassification(
                    ExecutionStatus.FAILED_DATABASE_GUARD,
                    "DATABASE_GUARD", reason);
        }
        return new FailureClassification(
                ExecutionStatus.FAILED_VALIDATION, "VALIDATION", reason);
    }

    private static boolean hasCause(Throwable error, Class<?> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    record FailureClassification(
            ExecutionStatus status,
            String stage,
            String reasonCode
    ) {
        FailureClassification {
            Objects.requireNonNull(status, "status");
            stage = TushareControlledAcceptanceExecution.safeText(stage);
            reasonCode = TushareControlledAcceptanceExecution.safeText(reasonCode);
            if (!status.name().startsWith("FAILED_")) {
                throw new IllegalArgumentException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_FAILURE_STATUS_INVALID");
            }
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
