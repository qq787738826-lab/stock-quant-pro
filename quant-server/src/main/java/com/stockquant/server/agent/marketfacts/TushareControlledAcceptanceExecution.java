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

/** Immutable, redacted records used by the F1F-B trusted executor. */
public final class TushareControlledAcceptanceExecution {
    public static final String EXECUTOR_VERSION = "TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1";
    public static final String RULE_VERSION = "TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1";

    private TushareControlledAcceptanceExecution() {
    }

    public enum ExecutionSource {
        TEST,
        REAL_CONTROLLED_ACCEPTANCE
    }

    public enum ExecutionStatus {
        AUTHORIZED,
        RESERVED,
        RUNNING,
        SUCCEEDED_CANDIDATE,
        PASSED,
        FAILED_PRE_PROVIDER,
        FAILED_PROVIDER,
        FAILED_VALIDATION,
        FAILED_DATABASE_GUARD,
        FAILED_PERSISTENCE,
        FAILED_ROLLBACK,
        FAILED_QFQ,
        FAILED_OUTPUT_AUDIT,
        INTERRUPTED,
        STALE,
        INCOMPATIBLE_BASELINE;

        public boolean incomplete() {
            return this == AUTHORIZED || this == RESERVED || this == RUNNING
                    || this == SUCCEEDED_CANDIDATE;
        }
    }

    public enum EvidenceQualification {
        TEST_ONLY_CANDIDATE,
        REAL_CONTROLLED_ACCEPTANCE_PASSED,
        FAILED
    }

    public enum ProhibitedStageAttestation {
        VERIFIED_UNREACHABLE,
        NOT_ATTESTED
    }

    public record Reservation(
            String acceptanceId,
            String authorizationFingerprint,
            ExecutionSource executionSource,
            String providerCode,
            String sourceInstrumentId,
            LocalDate tradeDate,
            Set<ControlledEndpoint> endpoints,
            String codeBaselineCommit,
            String artifactSha256,
            String databaseIdentity,
            String databaseUser,
            String schemaName,
            int schemaVersion,
            Instant createdAt,
            Instant authorizationExpiresAt
    ) {
        public Reservation {
            acceptanceId = safeId(acceptanceId);
            authorizationFingerprint = sha256(authorizationFingerprint);
            executionSource = Objects.requireNonNull(executionSource, "executionSource");
            providerCode = safeText(providerCode);
            sourceInstrumentId = safeText(sourceInstrumentId);
            tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
            endpoints = Set.copyOf(Objects.requireNonNull(endpoints, "endpoints"));
            codeBaselineCommit = commit(codeBaselineCommit);
            artifactSha256 = sha256(artifactSha256);
            databaseIdentity = safeText(databaseIdentity);
            databaseUser = safeText(databaseUser);
            schemaName = safeText(schemaName);
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            authorizationExpiresAt = Objects.requireNonNull(
                    authorizationExpiresAt, "authorizationExpiresAt");
            if (!providerCode.equals(TushareMarketFactProvider.PROVIDER_CODE)
                    || endpoints.size() != 3
                    || !endpoints.equals(TushareControlledAcceptanceAuthorization.REQUIRED_ENDPOINTS)
                    || schemaVersion != 14
                    || !authorizationExpiresAt.isAfter(createdAt)) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RESERVATION_INVALID");
            }
        }
    }

    public record StoredExecution(
            Reservation reservation,
            ExecutionStatus status,
            Instant reservedAt,
            Instant startedAt,
            Instant finalizedAt,
            String failureStage,
            String safeFailureReason,
            Long captureBatchId,
            int providerCallCount,
            int retryCount,
            String evidenceSummaryJson,
            String evidenceDigest,
            long rowVersion
    ) {
        public StoredExecution {
            Objects.requireNonNull(reservation, "reservation");
            Objects.requireNonNull(status, "status");
            failureStage = nullableSafe(failureStage);
            safeFailureReason = nullableSafe(safeFailureReason);
            evidenceDigest = evidenceDigest == null ? null : sha256(evidenceDigest);
            if (providerCallCount < 0 || providerCallCount > 3 || retryCount != 0
                    || rowVersion < 0 || captureBatchId != null && captureBatchId <= 0) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_STORED_EXECUTION_INVALID");
            }
        }
    }

    public record Transition(
            String acceptanceId,
            ExecutionStatus from,
            ExecutionStatus to,
            Instant transitionAt,
            long rowVersion,
            String safeReasonCode
    ) {
        public Transition {
            acceptanceId = safeId(acceptanceId);
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(transitionAt, "transitionAt");
            safeReasonCode = nullableSafe(safeReasonCode);
        }
    }

    public record DatabaseReadbackEvidence(
            long batchId,
            List<Long> observationIds,
            Map<FactType, Integer> factCounts,
            Instant observedAt,
            Instant minimumFirstObservedAt,
            Instant maximumFirstObservedAt,
            Instant minimumKnownAt,
            Instant maximumKnownAt,
            int backendPid,
            String databaseIdentity,
            String databaseUser,
            String schemaName,
            boolean exactMicrosecondMatch
    ) {
        public DatabaseReadbackEvidence {
            observationIds = List.copyOf(Objects.requireNonNull(observationIds, "observationIds"));
            EnumMap<FactType, Integer> copy = new EnumMap<>(FactType.class);
            copy.putAll(Objects.requireNonNull(factCounts, "factCounts"));
            factCounts = Map.copyOf(copy);
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(minimumFirstObservedAt, "minimumFirstObservedAt");
            Objects.requireNonNull(maximumFirstObservedAt, "maximumFirstObservedAt");
            Objects.requireNonNull(minimumKnownAt, "minimumKnownAt");
            Objects.requireNonNull(maximumKnownAt, "maximumKnownAt");
            databaseIdentity = safeText(databaseIdentity);
            databaseUser = safeText(databaseUser);
            schemaName = safeText(schemaName);
            if (batchId <= 0 || observationIds.size() != 3
                    || observationIds.stream().anyMatch(id -> id == null || id <= 0)
                    || backendPid <= 0) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_READBACK_INVALID");
            }
        }
    }

    public record RedactedEvidence(
            String acceptanceId,
            ExecutionSource executionSource,
            String codeBaselineCommit,
            String artifactSha256,
            int providerCallCount,
            int retryCount,
            long captureBatchId,
            Map<ControlledEndpoint, Integer> endpointCallCounts,
            DatabaseReadbackEvidence databaseReadback,
            TushareControlledAcceptanceOutputAudit.AuditResult outputAudit,
            ProhibitedStageAttestation prohibitedStageAttestation,
            boolean formulaOnlyQfqValid,
            boolean formulaOnlyQfqPersisted,
            boolean providerPitVerified,
            boolean fullLineageVerified,
            boolean permanentSecurityIdentityVerified,
            Instant startedAt,
            Instant endedAt,
            String executorVersion,
            String qualificationRuleVersion
    ) {
        public RedactedEvidence {
            acceptanceId = safeId(acceptanceId);
            executionSource = Objects.requireNonNull(executionSource, "executionSource");
            codeBaselineCommit = commit(codeBaselineCommit);
            artifactSha256 = sha256(artifactSha256);
            EnumMap<ControlledEndpoint, Integer> counts = new EnumMap<>(ControlledEndpoint.class);
            counts.putAll(Objects.requireNonNull(endpointCallCounts, "endpointCallCounts"));
            endpointCallCounts = Map.copyOf(counts);
            Objects.requireNonNull(databaseReadback, "databaseReadback");
            Objects.requireNonNull(outputAudit, "outputAudit");
            Objects.requireNonNull(prohibitedStageAttestation, "prohibitedStageAttestation");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(endedAt, "endedAt");
            executorVersion = safeText(executorVersion);
            qualificationRuleVersion = safeText(qualificationRuleVersion);
        }
    }

    public static final class Decision {
        private final EvidenceQualification qualification;
        private final ExecutionStatus status;
        private final boolean reducedResearchOperationalReady;
        private final Set<String> blockers;

        private Decision(
                EvidenceQualification qualification,
                ExecutionStatus status,
                boolean reducedResearchOperationalReady,
                Set<String> blockers
        ) {
            this.qualification = Objects.requireNonNull(qualification, "qualification");
            this.status = Objects.requireNonNull(status, "status");
            this.reducedResearchOperationalReady = reducedResearchOperationalReady;
            this.blockers = Set.copyOf(Objects.requireNonNull(blockers, "blockers"));
            if (reducedResearchOperationalReady != (status == ExecutionStatus.PASSED)
                    || status == ExecutionStatus.PASSED && !this.blockers.isEmpty()) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_DECISION_INVALID");
            }
        }

        static Decision testCandidate(Set<String> blockers) {
            return new Decision(EvidenceQualification.TEST_ONLY_CANDIDATE,
                    ExecutionStatus.SUCCEEDED_CANDIDATE, false, blockers);
        }

        static Decision internalPassed() {
            return new Decision(EvidenceQualification.REAL_CONTROLLED_ACCEPTANCE_PASSED,
                    ExecutionStatus.PASSED, true, Set.of());
        }

        static Decision failed(ExecutionStatus status, Set<String> blockers) {
            if (status == ExecutionStatus.PASSED) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_DECISION_INVALID");
            }
            return new Decision(EvidenceQualification.FAILED, status, false, blockers);
        }

        public EvidenceQualification qualification() { return qualification; }
        public ExecutionStatus status() { return status; }
        public boolean reducedResearchOperationalReady() { return reducedResearchOperationalReady; }
        public Set<String> blockers() { return blockers; }
    }

    static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }

    static String safeId(String value) {
        if (value == null || !value.matches("[A-Z0-9_-]{8,64}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ID_INVALID");
        }
        return value;
    }

    static String safeText(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_TEXT_INVALID");
        }
        return value;
    }

    static String nullableSafe(String value) {
        return value == null ? null : safeText(value);
    }

    static String commit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_COMMIT_INVALID");
        }
        return value;
    }

    static String sha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_SHA256_INVALID");
        }
        return value;
    }
}
