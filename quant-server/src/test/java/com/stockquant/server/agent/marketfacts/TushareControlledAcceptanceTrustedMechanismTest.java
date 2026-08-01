package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceAuthorization.ControlledEndpoint;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ProhibitedStageAttestation;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.RedactedEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Reservation;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.StoredExecution;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Transition;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.AuditResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.CapturedText;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveMaterial;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TushareControlledAcceptanceTrustedMechanismTest {
    private static final String COMMIT = "f68d84403ebb82babe92a1cb0f78d845ed39547a";
    private static final String SHA = "a".repeat(64);
    private static final Instant START = Instant.parse("2026-08-01T01:00:00Z");

    @Test
    void durableAuthorizationFreezesArtifactAndScopeWithoutExposingDigest() {
        TushareControlledAcceptanceAuthorization value = authorization();
        assertTrue(value.durableConsumptionRecorded());
        assertEquals(14, value.schemaVersion());
        assertEquals(SHA, value.artifactSha256());
        assertFalse(value.toString().contains(SHA));
        assertDoesNotThrow(value::validateFrozen);
    }

    @Test
    void truncatedAndForgedBuildInputsAreRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.verifiedTestProof("f68d8440", SHA));
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.verifiedTestProof(COMMIT, "x"));
    }

    @Test
    void auditFindsExactPrefixSuffixEncodedAndHeadersWithoutPersistingText() {
        String secret = "fake-token-0123456789";
        AuditResult result = TushareControlledAcceptanceOutputAudit.audit(
                List.of(new CapturedText("STDOUT",
                        "Authorization: Bearer " + secret + " password=fake")),
                List.of(SensitiveMaterial.register(secret)), true);
        assertFalse(result.clean());
        assertTrue(result.hits().size() >= 5);
        assertFalse(result.toString().contains(secret));
    }

    @Test
    void incompleteCaptureCannotAttestCleanOutput() {
        assertThrows(IllegalArgumentException.class, () ->
                new AuditResult(false, true, List.of()));
    }

    @Test
    void testEvidenceCanOnlyBecomeCandidate() {
        TushareControlledAcceptanceEvaluator evaluator = evaluator();
        RedactedEvidence evidence = evidence(ExecutionSource.TEST,
                ProhibitedStageAttestation.VERIFIED_UNREACHABLE);
        StoredExecution stored = stored(ExecutionSource.TEST,
                ExecutionStatus.SUCCEEDED_CANDIDATE, evidence, evaluator);
        assertEquals(ExecutionStatus.SUCCEEDED_CANDIDATE,
                evaluator.evaluateCandidate(stored, candidateHistory(), evidence,
                        proof()).status());
        assertFalse(evaluator.evaluateCandidate(stored, candidateHistory(), evidence,
                proof()).reducedResearchOperationalReady());
    }

    @Test
    void testBuildProofCannotPassRealControlledAcceptance() {
        TushareControlledAcceptanceEvaluator evaluator = evaluator();
        RedactedEvidence evidence = evidence(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ProhibitedStageAttestation.VERIFIED_UNREACHABLE);
        StoredExecution stored = stored(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ExecutionStatus.SUCCEEDED_CANDIDATE, evidence, evaluator);
        assertThrows(IllegalStateException.class, () -> evaluator.evaluateCandidate(
                stored, candidateHistory(), evidence, proof()));
    }

    @Test
    void tamperedEvidenceDigestAndPassedColumnCannotBypassReload() {
        TushareControlledAcceptanceEvaluator evaluator = evaluator();
        RedactedEvidence evidence = evidence(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ProhibitedStageAttestation.VERIFIED_UNREACHABLE);
        StoredExecution stored = stored(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ExecutionStatus.PASSED, evidence, evaluator);
        StoredExecution tampered = new StoredExecution(stored.reservation(),
                ExecutionStatus.PASSED, stored.reservedAt(), stored.startedAt(),
                stored.finalizedAt(), null, null, stored.captureBatchId(), 3, 0,
                stored.evidenceSummaryJson() + " ", stored.evidenceDigest(), 4);
        assertThrows(IllegalStateException.class, () -> evaluator.reloadAndRevalidate(
                tampered, passedHistory(), proof()));
    }

    @Test
    void boundaryAttestationIsDerivedFromExecutorType() {
        assertEquals(ProhibitedStageAttestation.VERIFIED_UNREACHABLE,
                TushareControlledAcceptanceBoundaryAttestor.attest(
                        TushareControlledAcceptanceExecutor.class));
    }

    private static TushareControlledAcceptanceAuthorization authorization() {
        return TushareControlledAcceptanceAuthorization.issueUserApprovedDurable(
                "F1FB1_TEST_001", COMMIT, SHA,
                new SecuritySelection("600000", "SSE"),
                LocalDate.of(2025, 1, 2), START.minusSeconds(1), START.plusSeconds(60));
    }

    private static TushareControlledAcceptanceEvaluator evaluator() {
        return new TushareControlledAcceptanceEvaluator(
                new ObjectMapper().findAndRegisterModules());
    }

    private static VerifiedBuildProof proof() {
        return TushareControlledAcceptanceBuildProof.verifiedTestProof(COMMIT, SHA);
    }

    private static RedactedEvidence evidence(
            ExecutionSource source,
            ProhibitedStageAttestation stageAttestation
    ) {
        DatabaseReadbackEvidence readback = new DatabaseReadbackEvidence(
                11, List.of(21L, 22L, 23L), Map.of(
                FactType.RAW_DAILY_BAR, 1,
                FactType.ADJUSTMENT_FACTOR, 1,
                FactType.TRADING_CALENDAR, 1), START, START, START,
                START, START, 1234, "stock_quant_research",
                "stock_quant_research", "tushare_research", true);
        return new RedactedEvidence("F1FB1_TEST_001", source, COMMIT, SHA,
                3, 0, 11, Map.of(ControlledEndpoint.DAILY, 1,
                ControlledEndpoint.ADJ_FACTOR, 1, ControlledEndpoint.TRADE_CAL, 1),
                readback, new AuditResult(true, true, List.of()), stageAttestation,
                true, false, false, false, false, START, START.plusSeconds(1),
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION);
    }

    private static StoredExecution stored(
            ExecutionSource source,
            ExecutionStatus status,
            RedactedEvidence evidence,
            TushareControlledAcceptanceEvaluator evaluator
    ) {
        var encoded = evaluator.encode(evidence);
        Reservation reservation = new Reservation("F1FB1_TEST_001",
                authorization().authorizationFingerprint(), source,
                TushareMarketFactProvider.PROVIDER_CODE, "600000.SH",
                LocalDate.of(2025, 1, 2), Set.of(ControlledEndpoint.DAILY,
                ControlledEndpoint.ADJ_FACTOR, ControlledEndpoint.TRADE_CAL),
                COMMIT, SHA, "stock_quant_research", "stock_quant_research",
                "tushare_research", 14, START.minusSeconds(2), START.plusSeconds(60));
        return new StoredExecution(reservation, status, START.minusSeconds(1),
                START, START.plusSeconds(2), null, null, 11L, 3, 0,
                encoded.json(), encoded.digest(), status == ExecutionStatus.PASSED ? 4 : 3);
    }

    private static List<Transition> candidateHistory() {
        return List.of(transition(null, ExecutionStatus.AUTHORIZED, 0),
                transition(ExecutionStatus.AUTHORIZED, ExecutionStatus.RESERVED, 1),
                transition(ExecutionStatus.RESERVED, ExecutionStatus.RUNNING, 2),
                transition(ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED_CANDIDATE, 3));
    }

    private static List<Transition> passedHistory() {
        var values = new java.util.ArrayList<>(candidateHistory());
        values.add(transition(ExecutionStatus.SUCCEEDED_CANDIDATE,
                ExecutionStatus.PASSED, 4));
        return values;
    }

    private static Transition transition(
            ExecutionStatus from,
            ExecutionStatus to,
            long version
    ) {
        return new Transition("F1FB1_TEST_001", from, to,
                START.plusSeconds(version), version, null);
    }
}
