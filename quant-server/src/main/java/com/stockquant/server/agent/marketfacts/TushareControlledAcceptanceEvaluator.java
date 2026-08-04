package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Decision;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.EvidenceQualification;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ProhibitedStageAttestation;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.RedactedEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.StoredExecution;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Transition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Package-internal authority for CANDIDATE/PASSED calculation and reload. */
final class TushareControlledAcceptanceEvaluator {
    private final ObjectMapper canonicalMapper;

    TushareControlledAcceptanceEvaluator(ObjectMapper objectMapper) {
        canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        canonicalMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        canonicalMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    EncodedEvidence encode(RedactedEvidence evidence) {
        try {
            String json = canonicalMapper.writeValueAsString(evidence);
            return new EncodedEvidence(json, sha256(json));
        } catch (JsonProcessingException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_SERIALIZATION_FAILED");
        }
    }

    RedactedEvidence decode(String json, String digest) {
        if (json == null || !sha256(json).equals(digest)) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_DIGEST_INVALID");
        }
        try {
            return canonicalMapper.readValue(json, RedactedEvidence.class);
        } catch (JsonProcessingException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_DESERIALIZATION_FAILED");
        }
    }

    CandidateAssessment evaluateCandidate(
            StoredExecution execution,
            List<Transition> history,
            RedactedEvidence evidence,
            VerifiedBuildProof buildProof
    ) {
        requireBaseEvidence(execution, history, evidence, buildProof, false);
        if (execution.reservation().executionSource() == ExecutionSource.TEST) {
            return CandidateAssessment.testOnly(Decision.testCandidate(
                    Set.of("REAL_CONTROLLED_ACCEPTANCE_NOT_RUN")));
        }
        requireOperationalEvidence(evidence, buildProof);
        return CandidateAssessment.authorizedForPersistedPass();
    }

    Decision reloadAndRevalidate(
            StoredExecution execution,
            List<Transition> history,
            VerifiedBuildProof buildProof
    ) {
        RedactedEvidence evidence = decode(
                execution.evidenceSummaryJson(), execution.evidenceDigest());
        boolean persistedPassed = execution.status() == ExecutionStatus.PASSED;
        requireBaseEvidence(execution, history, evidence, buildProof, persistedPassed);
        if (!persistedPassed) {
            return Decision.failed(execution.status(),
                    Set.of("REAL_CONTROLLED_ACCEPTANCE_NOT_PASSED"));
        }
        requireOperationalEvidence(evidence, buildProof);
        return Decision.internalPassed();
    }

    private void requireBaseEvidence(
            StoredExecution execution,
            List<Transition> history,
            RedactedEvidence evidence,
            VerifiedBuildProof buildProof,
            boolean requirePassedHistory
    ) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(evidence, "evidence");
        buildProof.validate();
        List<ExecutionStatus> expected = requirePassedHistory
                ? List.of(ExecutionStatus.AUTHORIZED, ExecutionStatus.RESERVED,
                ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED_CANDIDATE,
                ExecutionStatus.PASSED)
                : List.of(ExecutionStatus.AUTHORIZED, ExecutionStatus.RESERVED,
                ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED_CANDIDATE);
        requireExactHistory(execution, history, expected);
        if (execution.status() != expected.get(expected.size() - 1)
                || !execution.reservation().acceptanceId().equals(evidence.acceptanceId())
                || execution.reservation().executionSource() != evidence.executionSource()
                || !execution.reservation().codeBaselineCommit().equals(evidence.codeBaselineCommit())
                || !execution.reservation().artifactSha256().equals(evidence.artifactSha256())
                || !buildProof.gitCommit().equals(evidence.codeBaselineCommit())
                || !buildProof.actualArtifactSha256().equals(evidence.artifactSha256())
                || execution.providerCallCount() != 3
                || execution.retryCount() != 0
                || evidence.providerCallCount() != 3
                || evidence.retryCount() != 0
                || !evidence.endpointCallCounts().equals(Map.of(
                TushareControlledAcceptanceAuthorization.ControlledEndpoint.DAILY, 1,
                TushareControlledAcceptanceAuthorization.ControlledEndpoint.ADJ_FACTOR, 1,
                TushareControlledAcceptanceAuthorization.ControlledEndpoint.TRADE_CAL, 1))
                || !evidence.databaseReadback().exactMicrosecondMatch()
                || !evidence.databaseReadback().committedReadbackVerified()
                || !evidence.databaseReadback()
                .currentBatchFactReferencesVerified()
                || evidence.captureBatchId() != evidence.databaseReadback().batchId()
                || !Objects.equals(execution.captureBatchId(), evidence.captureBatchId())
                || !execution.reservation().databaseIdentity().equals(
                evidence.databaseReadback().databaseIdentity())
                || !execution.reservation().databaseUser().equals(
                evidence.databaseReadback().databaseUser())
                || !execution.reservation().schemaName().equals(
                evidence.databaseReadback().schemaName())
                || !evidence.formulaOnlyQfqValid()
                || evidence.formulaOnlyQfqPersisted()
                || evidence.providerPitVerified()
                || evidence.fullLineageVerified()
                || evidence.permanentSecurityIdentityVerified()
                || !evidence.startedAt().equals(execution.reservation().createdAt())
                || !evidence.endedAt().isAfter(evidence.startedAt())
                || evidence.databaseReadback().observedAt().isBefore(evidence.startedAt())
                || evidence.databaseReadback().observedAt().isAfter(evidence.endedAt())
                || execution.finalizedAt().isBefore(evidence.endedAt())
                || !TushareControlledAcceptanceExecution.EXECUTOR_VERSION.equals(evidence.executorVersion())
                || !TushareControlledAcceptanceExecution.RULE_VERSION.equals(evidence.qualificationRuleVersion())) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_INVALID");
        }
    }

    private static void requireExactHistory(
            StoredExecution execution,
            List<Transition> history,
            List<ExecutionStatus> expected
    ) {
        Objects.requireNonNull(history, "history");
        if (history.size() != expected.size()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_HISTORY_INVALID");
        }
        for (int index = 0; index < history.size(); index++) {
            Transition transition = history.get(index);
            ExecutionStatus expectedFrom = index == 0 ? null : expected.get(index - 1);
            if (!execution.reservation().acceptanceId().equals(transition.acceptanceId())
                    || transition.from() != expectedFrom
                    || transition.to() != expected.get(index)
                    || transition.rowVersion() != index
                    || index > 0 && transition.transitionAt().isBefore(
                    history.get(index - 1).transitionAt())) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_HISTORY_INVALID");
            }
        }
        if (!history.get(0).transitionAt().equals(execution.reservation().createdAt())
                || !history.get(1).transitionAt().equals(execution.reservedAt())
                || !history.get(2).transitionAt().equals(execution.startedAt())
                || !history.get(history.size() - 1).transitionAt().equals(
                execution.finalizedAt())
                || execution.rowVersion() != history.size() - 1) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_HISTORY_INVALID");
        }
    }

    private void requireOperationalEvidence(
            RedactedEvidence evidence,
            VerifiedBuildProof buildProof
    ) {
        if (evidence.executionSource() != ExecutionSource.REAL_CONTROLLED_ACCEPTANCE
                || !buildProof.governanceEligible()
                || !evidence.outputAudit().captureComplete()
                || !evidence.outputAudit().clean()
                || evidence.prohibitedStageAttestation()
                != ProhibitedStageAttestation.VERIFIED_UNREACHABLE) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_OPERATIONAL_EVIDENCE_INVALID");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record EncodedEvidence(String json, String digest) {
    }

    record CandidateAssessment(boolean persistedPassAuthorized, Decision testDecision) {
        CandidateAssessment {
            if (persistedPassAuthorized == (testDecision != null)) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_CANDIDATE_ASSESSMENT_INVALID");
            }
        }

        static CandidateAssessment authorizedForPersistedPass() {
            return new CandidateAssessment(true, null);
        }

        static CandidateAssessment testOnly(Decision decision) {
            return new CandidateAssessment(false, Objects.requireNonNull(decision, "decision"));
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
