package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
public final class TushareControlledAcceptanceEvaluator {
    private final ObjectMapper canonicalMapper;

    public TushareControlledAcceptanceEvaluator(ObjectMapper objectMapper) {
        canonicalMapper = Objects.requireNonNull(objectMapper, "objectMapper").copy();
        canonicalMapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        canonicalMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
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

    Decision evaluateCandidate(
            StoredExecution execution,
            List<Transition> history,
            RedactedEvidence evidence,
            VerifiedBuildProof buildProof
    ) {
        requireBaseEvidence(execution, history, evidence, buildProof, false);
        if (execution.reservation().executionSource() == ExecutionSource.TEST) {
            return Decision.testCandidate(Set.of("REAL_CONTROLLED_ACCEPTANCE_NOT_RUN"));
        }
        requireOperationalEvidence(evidence, buildProof);
        return Decision.internalPassed();
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
        if (!history.stream().map(Transition::to).toList().equals(expected)
                || !execution.reservation().acceptanceId().equals(evidence.acceptanceId())
                || !execution.reservation().codeBaselineCommit().equals(evidence.codeBaselineCommit())
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
                || !evidence.formulaOnlyQfqValid()
                || evidence.formulaOnlyQfqPersisted()
                || evidence.providerPitVerified()
                || evidence.fullLineageVerified()
                || evidence.permanentSecurityIdentityVerified()
                || !evidence.endedAt().isAfter(evidence.startedAt())
                || !TushareControlledAcceptanceExecution.EXECUTOR_VERSION.equals(evidence.executorVersion())
                || !TushareControlledAcceptanceExecution.RULE_VERSION.equals(evidence.qualificationRuleVersion())) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_EVIDENCE_INVALID");
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

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
