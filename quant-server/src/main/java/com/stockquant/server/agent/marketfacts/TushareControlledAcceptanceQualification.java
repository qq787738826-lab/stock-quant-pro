package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceAuthorization.ControlledEndpoint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed evidence and projection for the future F1F-B controlled run.
 *
 * <p>F1F-A can only produce NOT_RUN, CANDIDATE, FAILED, STALE or
 * INCOMPATIBLE_BASELINE. There is deliberately no public PASSED factory;
 * real-provider attestation remains a separately authorized F1F-B concern.</p>
 */
public final class TushareControlledAcceptanceQualification {

    public static final String PREPARATION_BASELINE =
            "0e2b607bc068910319134790360d71a18a6a9e02";
    private static final Set<FactType> REQUIRED_FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    private final AcceptanceStatus status;
    private final ExecutionEvidence executionEvidence;
    private final FailureEvidence failureEvidence;
    private final Set<AcceptanceBlocker> blockers;
    private final boolean reducedResearchOperationalReady;

    private TushareControlledAcceptanceQualification(
            AcceptanceStatus status,
            ExecutionEvidence executionEvidence,
            FailureEvidence failureEvidence,
            Set<AcceptanceBlocker> blockers,
            boolean reducedResearchOperationalReady
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.executionEvidence = executionEvidence;
        this.failureEvidence = failureEvidence;
        this.blockers = Set.copyOf(Objects.requireNonNull(
                blockers, "blockers"));
        this.reducedResearchOperationalReady =
                reducedResearchOperationalReady;
        validateInvariants();
    }

    public static TushareControlledAcceptanceQualification notRun() {
        return new TushareControlledAcceptanceQualification(
                AcceptanceStatus.NOT_RUN,
                null,
                null,
                Set.of(AcceptanceBlocker.CONTROLLED_ACCEPTANCE_NOT_RUN),
                false);
    }

    static TushareControlledAcceptanceQualification preparedCandidate(
            ExecutionEvidence evidence,
            Instant evidenceValidUntil,
            String activeCodeBaseline,
            Instant assessedAt
    ) {
        Objects.requireNonNull(evidence, "evidence");
        requireCommit(activeCodeBaseline);
        Objects.requireNonNull(evidenceValidUntil, "evidenceValidUntil");
        Objects.requireNonNull(assessedAt, "assessedAt");
        if (!evidence.codeBaselineCommit().equals(activeCodeBaseline)) {
            return new TushareControlledAcceptanceQualification(
                    AcceptanceStatus.INCOMPATIBLE_BASELINE,
                    evidence,
                    null,
                    Set.of(AcceptanceBlocker.INCOMPATIBLE_CODE_BASELINE),
                    false);
        }
        if (!assessedAt.isBefore(evidenceValidUntil)) {
            return new TushareControlledAcceptanceQualification(
                    AcceptanceStatus.STALE,
                    evidence,
                    null,
                    Set.of(AcceptanceBlocker.ACCEPTANCE_EVIDENCE_STALE),
                    false);
        }
        if (!meetsOperationalContract(evidence)) {
            return failed(
                    evidence.acceptanceId(),
                    evidence.codeBaselineCommit(),
                    FailureStage.QUALIFICATION_PROJECTION,
                    "TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_INVALID",
                    assessedAt);
        }
        return new TushareControlledAcceptanceQualification(
                AcceptanceStatus.CANDIDATE,
                evidence,
                null,
                Set.of(AcceptanceBlocker
                        .REAL_CONTROLLED_ACCEPTANCE_NOT_ATTESTED),
                false);
    }

    static TushareControlledAcceptanceQualification failed(
            String acceptanceId,
            String codeBaselineCommit,
            FailureStage stage,
            String safeReasonCode,
            Instant failedAt
    ) {
        return new TushareControlledAcceptanceQualification(
                AcceptanceStatus.FAILED,
                null,
                new FailureEvidence(
                        acceptanceId,
                        codeBaselineCommit,
                        stage,
                        safeReasonCode,
                        failedAt),
                Set.of(AcceptanceBlocker.CONTROLLED_ACCEPTANCE_FAILED),
                false);
    }

    private static boolean meetsOperationalContract(
            ExecutionEvidence evidence
    ) {
        Map<ControlledEndpoint, Integer> expectedEndpointCounts =
                new EnumMap<>(ControlledEndpoint.class);
        TushareControlledAcceptanceAuthorization.REQUIRED_ENDPOINTS
                .forEach(endpoint -> expectedEndpointCounts.put(endpoint, 1));
        Map<FactType, Integer> expectedFactCounts =
                new EnumMap<>(FactType.class);
        REQUIRED_FACT_TYPES.forEach(type -> expectedFactCounts.put(type, 1));
        return TushareMarketFactProvider.PROVIDER_CODE.equals(
                evidence.providerCode())
                && TushareDedicatedResearchPersistenceGuard
                .REQUIRED_DATABASE.equals(evidence.databaseIdentity())
                && TushareDedicatedResearchPersistenceGuard
                .REQUIRED_USER.equals(evidence.databaseUser())
                && TushareDedicatedResearchPersistenceGuard
                .REQUIRED_SCHEMA.equals(evidence.schemaName())
                && evidence.schemaVersion() == 13
                && evidence.symbolCount() == 1
                && evidence.tradeDateCount() == 1
                && evidence.endpoints().equals(
                TushareControlledAcceptanceAuthorization
                        .REQUIRED_ENDPOINTS)
                && evidence.endpointCallCounts().equals(
                Map.copyOf(expectedEndpointCounts))
                && evidence.totalProviderCallCount() == 3
                && evidence.retryCount() == 0
                && evidence.captureBatchIds().size() == 1
                && evidence.factCounts().equals(
                Map.copyOf(expectedFactCounts))
                && evidence.atomicCommitResult()
                == AtomicCommitResult.COMMITTED_ATOMICALLY
                && evidence.databaseIdentityStable()
                && evidence.tradingDayOpen()
                && "REGULAR".equals(evidence.sessionCode())
                && evidence.systemKnowledgeEvidence().valid()
                && evidence.qfqSummary().validFormulaOnly()
                && !evidence.tokenOutputDetected()
                && !evidence.normalBusinessDatabaseUsed()
                && !evidence.publicSchemaUsed()
                && evidence.startedProhibitedStages().isEmpty()
                && !evidence.providerPitVerified()
                && !evidence.fullLineageVerified()
                && !evidence.permanentSecurityIdentityVerified()
                && !evidence.endedAt().isBefore(evidence.startedAt())
                && !evidence.systemKnowledgeEvidence().observedAt()
                .isBefore(evidence.startedAt())
                && !evidence.systemKnowledgeEvidence().observedAt()
                .isAfter(evidence.endedAt());
    }

    private void validateInvariants() {
        boolean passed = status == AcceptanceStatus.PASSED;
        if (reducedResearchOperationalReady != passed
                || passed && (executionEvidence == null
                || !meetsOperationalContract(executionEvidence)
                || !blockers.isEmpty())
                || status == AcceptanceStatus.CANDIDATE
                && (executionEvidence == null
                || failureEvidence != null
                || blockers.isEmpty())
                || status == AcceptanceStatus.FAILED
                && (failureEvidence == null
                || executionEvidence != null)
                || status == AcceptanceStatus.NOT_RUN
                && (executionEvidence != null
                || failureEvidence != null)
                || status != AcceptanceStatus.PASSED
                && blockers.isEmpty()) {
            throw new IllegalArgumentException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_QUALIFICATION_INVALID");
        }
    }

    public AcceptanceStatus status() {
        return status;
    }

    public ExecutionEvidence executionEvidence() {
        return executionEvidence;
    }

    public FailureEvidence failureEvidence() {
        return failureEvidence;
    }

    public Set<AcceptanceBlocker> blockers() {
        return blockers;
    }

    public boolean reducedResearchOperationalReady() {
        return reducedResearchOperationalReady;
    }

    public Set<String> evidenceIds() {
        return executionEvidence == null
                ? Set.of() : Set.of(executionEvidence.evidenceId());
    }

    public record ExecutionEvidence(
            String evidenceId,
            String acceptanceId,
            String codeBaselineCommit,
            String providerCode,
            String databaseIdentity,
            String databaseUser,
            String schemaName,
            int schemaVersion,
            int symbolCount,
            int tradeDateCount,
            String sourceInstrumentId,
            LocalDate tradeDate,
            Set<ControlledEndpoint> endpoints,
            Map<ControlledEndpoint, Integer> endpointCallCounts,
            int totalProviderCallCount,
            int retryCount,
            Instant startedAt,
            Instant endedAt,
            List<Long> captureBatchIds,
            Map<FactType, Integer> factCounts,
            AtomicCommitResult atomicCommitResult,
            boolean databaseIdentityStable,
            boolean tradingDayOpen,
            String sessionCode,
            SystemKnowledgeEvidence systemKnowledgeEvidence,
            FormulaOnlyQfqSummary qfqSummary,
            boolean tokenOutputDetected,
            boolean normalBusinessDatabaseUsed,
            boolean publicSchemaUsed,
            Set<ProhibitedStage> startedProhibitedStages,
            boolean providerPitVerified,
            boolean fullLineageVerified,
            boolean permanentSecurityIdentityVerified,
            String redactedEvidenceSummary
    ) {
        public ExecutionEvidence {
            evidenceId = requireSafeId(evidenceId, "evidenceId");
            acceptanceId = requireSafeId(
                    acceptanceId, "acceptanceId");
            codeBaselineCommit = requireCommit(codeBaselineCommit);
            providerCode = requireSafeText(providerCode, "providerCode");
            databaseIdentity = requireSafeText(
                    databaseIdentity, "databaseIdentity");
            databaseUser = requireSafeText(databaseUser, "databaseUser");
            schemaName = requireSafeText(schemaName, "schemaName");
            sessionCode = requireSafeText(sessionCode, "sessionCode");
            sourceInstrumentId = requireSafeText(
                    sourceInstrumentId, "sourceInstrumentId");
            tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
            endpoints = Set.copyOf(Objects.requireNonNull(
                    endpoints, "endpoints"));
            endpointCallCounts = copyEndpointCounts(endpointCallCounts);
            startedAt = Objects.requireNonNull(startedAt, "startedAt");
            endedAt = Objects.requireNonNull(endedAt, "endedAt");
            captureBatchIds = List.copyOf(Objects.requireNonNull(
                    captureBatchIds, "captureBatchIds"));
            factCounts = copyFactCounts(factCounts);
            atomicCommitResult = Objects.requireNonNull(
                    atomicCommitResult, "atomicCommitResult");
            systemKnowledgeEvidence = Objects.requireNonNull(
                    systemKnowledgeEvidence, "systemKnowledgeEvidence");
            qfqSummary = Objects.requireNonNull(qfqSummary, "qfqSummary");
            startedProhibitedStages = Set.copyOf(Objects.requireNonNull(
                    startedProhibitedStages, "startedProhibitedStages"));
            redactedEvidenceSummary = requireSafeSummary(
                    redactedEvidenceSummary);
            if (schemaVersion <= 0
                    || symbolCount <= 0
                    || tradeDateCount <= 0
                    || totalProviderCallCount < 0
                    || retryCount < 0
                    || captureBatchIds.stream().anyMatch(
                    id -> id == null || id <= 0)) {
                throw invalidEvidence();
            }
        }
    }

    public record SystemKnowledgeEvidence(
            Instant observedAt,
            boolean firstObservedAtAssigned,
            boolean knownAtAssigned,
            boolean systemKnowledgeOnly
    ) {
        public SystemKnowledgeEvidence {
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
        }

        boolean valid() {
            return firstObservedAtAssigned
                    && knownAtAssigned
                    && systemKnowledgeOnly;
        }
    }

    public record FormulaOnlyQfqSummary(
            int qfqBarCount,
            boolean calculatedInMemory,
            boolean persisted,
            boolean fullQfqEligible,
            boolean corporateActionLineageComplete
    ) {
        boolean validFormulaOnly() {
            return qfqBarCount == 1
                    && calculatedInMemory
                    && !persisted
                    && !fullQfqEligible
                    && !corporateActionLineageComplete;
        }
    }

    public record FailureEvidence(
            String acceptanceId,
            String codeBaselineCommit,
            FailureStage stage,
            String safeReasonCode,
            Instant failedAt
    ) {
        public FailureEvidence {
            acceptanceId = requireSafeId(
                    acceptanceId, "acceptanceId");
            codeBaselineCommit = requireCommit(codeBaselineCommit);
            stage = Objects.requireNonNull(stage, "stage");
            safeReasonCode = requireSafeSummary(safeReasonCode);
            failedAt = Objects.requireNonNull(failedAt, "failedAt");
        }
    }

    public enum AcceptanceStatus {
        NOT_RUN,
        CANDIDATE,
        PASSED,
        FAILED,
        STALE,
        INCOMPATIBLE_BASELINE
    }

    public enum AcceptanceBlocker {
        CONTROLLED_ACCEPTANCE_NOT_RUN,
        REAL_CONTROLLED_ACCEPTANCE_NOT_ATTESTED,
        CONTROLLED_ACCEPTANCE_FAILED,
        ACCEPTANCE_EVIDENCE_STALE,
        INCOMPATIBLE_CODE_BASELINE
    }

    public enum FailureStage {
        AUTHORIZATION_VALIDATION,
        PROVIDER_PRECONDITION,
        PROVIDER_CALL,
        RESPONSE_VALIDATION,
        DATABASE_IDENTITY,
        SCHEMA_VERSION,
        PERSISTENCE_WRITE,
        TRANSACTION_ROLLBACK,
        QFQ_VALIDATION,
        PROHIBITED_STAGE,
        QUALIFICATION_PROJECTION
    }

    public enum AtomicCommitResult {
        COMMITTED_ATOMICALLY,
        ROLLED_BACK_CLEANLY,
        ROLLBACK_FAILED,
        NOT_OBSERVED
    }

    public enum ProhibitedStage {
        SCHEDULER,
        AGENT,
        BACKTEST,
        SHADOW,
        TRADING,
        DAY_002,
        F2B,
        F3,
        STAGE_3A_R3B_1,
        STAGE_3B
    }

    private static Map<ControlledEndpoint, Integer> copyEndpointCounts(
            Map<ControlledEndpoint, Integer> values
    ) {
        Objects.requireNonNull(values, "endpointCallCounts");
        Map<ControlledEndpoint, Integer> copy =
                new EnumMap<>(ControlledEndpoint.class);
        values.forEach((endpoint, count) -> {
            if (endpoint == null || count == null || count < 0) {
                throw invalidEvidence();
            }
            copy.put(endpoint, count);
        });
        return Map.copyOf(copy);
    }

    private static Map<FactType, Integer> copyFactCounts(
            Map<FactType, Integer> values
    ) {
        Objects.requireNonNull(values, "factCounts");
        Map<FactType, Integer> copy = new EnumMap<>(FactType.class);
        values.forEach((type, count) -> {
            if (type == null || count == null || count < 0) {
                throw invalidEvidence();
            }
            copy.put(type, count);
        });
        return Map.copyOf(copy);
    }

    private static String requireCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw invalidEvidence();
        }
        return value;
    }

    private static String requireSafeId(String value, String field) {
        if (value == null || !value.matches("[A-Z0-9_-]{8,96}")) {
            throw new IllegalArgumentException(
                    "invalid controlled acceptance " + field);
        }
        return value;
    }

    private static String requireSafeText(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException(
                    "invalid controlled acceptance " + field);
        }
        return value;
    }

    private static String requireSafeSummary(String value) {
        if (value == null || !value.matches("[A-Z0-9_:-]{1,256}")) {
            throw invalidEvidence();
        }
        return value;
    }

    private static IllegalArgumentException invalidEvidence() {
        return new IllegalArgumentException(
                "TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_INVALID");
    }
}
