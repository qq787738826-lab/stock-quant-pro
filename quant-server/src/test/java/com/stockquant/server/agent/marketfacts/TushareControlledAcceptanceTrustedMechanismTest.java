package com.stockquant.server.agent.marketfacts;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.core.read.ListAppender;
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
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.CapturedExecutionException;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.CapturedText;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.SensitiveMaterial;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

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
        assertEquals(value.authorizationFingerprint(), authorization().authorizationFingerprint());
    }

    @Test
    void truncatedAndForgedBuildInputsAreRejectedBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.verifiedTestProof("f68d8440", SHA));
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.verifiedTestProof(COMMIT, "x"));
    }

    @Test
    void controlledBuildProofIsBoundToJarManifestHashAndAdjacentSidecar(
            @TempDir Path temp
    ) throws Exception {
        Path jar = temp.resolve("quant-server-1.3.1.jar");
        writeJar(jar, COMMIT);
        Path sidecar = Path.of(jar + TushareControlledAcceptanceBuildProof.SIDECAR_SUFFIX);
        writeSidecar(sidecar, COMMIT, sha256(jar));

        VerifiedBuildProof proof = TushareControlledAcceptanceBuildProof
                .loadBoundTestArtifact(jar, sidecar);
        assertFalse(proof.governanceEligible());
        assertEquals(COMMIT, proof.gitCommit());
        assertFalse(proof.toString().contains(proof.actualArtifactSha256()));

        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.loadBoundTestArtifact(
                        jar, temp.resolve("arbitrary.properties")));
        Files.writeString(jar, "tamper", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.loadBoundTestArtifact(jar, sidecar));
    }

    @Test
    void manifestCommitMismatchRejectsOtherwiseMatchingSidecar(@TempDir Path temp)
            throws Exception {
        Path jar = temp.resolve("quant-server-1.3.1.jar");
        writeJar(jar, "b".repeat(40));
        Path sidecar = Path.of(jar + TushareControlledAcceptanceBuildProof.SIDECAR_SUFFIX);
        writeSidecar(sidecar, COMMIT, sha256(jar));
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.loadBoundTestArtifact(jar, sidecar));
    }

    @Test
    void taskBranchArtifactCannotSatisfyControlledBuildProof(@TempDir Path temp)
            throws Exception {
        String taskBranch = "codex/1.4.0-stage-f1f-b1";
        Path jar = temp.resolve("quant-server-1.3.1.jar");
        writeJar(jar, COMMIT, taskBranch);
        Path sidecar = Path.of(jar + TushareControlledAcceptanceBuildProof.SIDECAR_SUFFIX);
        writeSidecar(sidecar, COMMIT, sha256(jar), taskBranch);
        assertThrows(IllegalArgumentException.class, () ->
                TushareControlledAcceptanceBuildProof.loadBoundTestArtifact(jar, sidecar));
    }

    @Test
    void auditFindsExactPrefixSuffixEncodedHeadersQueryAndEnvironmentForms() {
        String secret = "fake-token-0123456789";
        AuditResult result = TushareControlledAcceptanceOutputAudit.audit(
                List.of(new CapturedText("STDOUT",
                        "Authorization: Bearer " + secret
                                + "?token=" + java.net.URLEncoder.encode(
                                secret, StandardCharsets.UTF_8)
                                + " TUSHARE_TOKEN=" + secret + " password=fake")),
                List.of(SensitiveMaterial.register(secret)), true);
        assertFalse(result.clean());
        assertTrue(result.hits().size() >= 8);
        assertFalse(result.toString().contains(secret));
    }

    @Test
    void logbackAppendersAreIsolatedAndRestoredWithoutLeakingSecret() throws Exception {
        String secret = "fake-token-output-audit";
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> outside =
                new ListAppender<>();
        outside.setContext(context);
        outside.start();
        root.addAppender(outside);
        try {
            var captured = TushareControlledAcceptanceOutputAudit.capture(
                    List.of(SensitiveMaterial.register(secret)),
                    () -> {
                        LoggerFactory.getLogger("controlled.audit.test")
                                .error("Bearer {}", secret);
                        return null;
                    });
            assertFalse(captured.auditResult().clean());
            assertTrue(outside.list.isEmpty(),
                    "the pre-existing appender must not receive controlled output");
            LoggerFactory.getLogger("controlled.audit.test").info("restored-safe-message");
            assertEquals(1, outside.list.size(), "the original appender must be restored");
        } finally {
            root.detachAppender(outside);
            outside.stop();
        }
    }

    @Test
    void independentAndAsyncAppendersAreIsolatedAndRestored() throws Exception {
        String secret = "fake-token-independent-async";
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger independent = context.getLogger("controlled.audit.independent");
        boolean originalAdditive = independent.isAdditive();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> independentOutside =
                new ListAppender<>();
        independentOutside.setContext(context);
        independentOutside.start();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> asyncOutside =
                new ListAppender<>();
        asyncOutside.setContext(context);
        asyncOutside.start();
        AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setContext(context);
        asyncAppender.addAppender(asyncOutside);
        asyncAppender.start();
        independent.setAdditive(false);
        independent.addAppender(independentOutside);
        root.addAppender(asyncAppender);
        try {
            var captured = TushareControlledAcceptanceOutputAudit.capture(
                    List.of(SensitiveMaterial.register(secret)),
                    () -> {
                        independent.error("token={}", secret);
                        LoggerFactory.getLogger("controlled.audit.async")
                                .error("Bearer {}", secret);
                        return null;
                    });
            assertFalse(captured.auditResult().clean());
            assertTrue(independentOutside.list.isEmpty(),
                    "a non-additive pre-existing appender must be isolated");
            assertTrue(asyncOutside.list.isEmpty(),
                    "a pre-existing async appender must be isolated");

            independent.info("restored-independent-safe");
            LoggerFactory.getLogger("controlled.audit.async")
                    .info("restored-async-safe");
            asyncAppender.stop();
            assertEquals(1, independentOutside.list.size());
            assertEquals(1, asyncOutside.list.size());
        } finally {
            root.detachAppender(asyncAppender);
            independent.detachAppender(independentOutside);
            independent.setAdditive(originalAdditive);
            asyncAppender.stop();
            independentOutside.stop();
            asyncOutside.stop();
        }
    }

    @Test
    void childThreadAndNestedSuppressedExceptionsAreAudited() {
        String secret = "fake-token-child-thread";
        CapturedExecutionException error = assertThrows(
                CapturedExecutionException.class,
                () -> TushareControlledAcceptanceOutputAudit.capture(
                        List.of(SensitiveMaterial.register(secret)),
                        () -> {
                            Thread child = new Thread(() ->
                                    LoggerFactory.getLogger("controlled.child")
                                            .warn("token={}", secret));
                            child.start();
                            child.join();
                            IllegalStateException cause =
                                    new IllegalStateException("suppressed-" + secret);
                            IllegalArgumentException outer =
                                    new IllegalArgumentException("outer", cause);
                            outer.addSuppressed(new IOException("nested-" + secret));
                            throw outer;
                        }));
        assertFalse(error.auditResult().clean());
        assertFalse(error.auditResult().hits().isEmpty());
        assertFalse(error.auditResult().toString().contains(secret));
    }

    @Test
    void sensitiveRegistrationRunsInsideTheOutputCaptureBoundary() throws Exception {
        String secret = "fake-token-registration-window";
        var captured = TushareControlledAcceptanceOutputAudit.captureAfterRegistration(
                () -> {
                    System.err.println("token=" + secret);
                    return List.of(SensitiveMaterial.register(secret));
                },
                () -> "completed");
        assertEquals("completed", captured.value());
        assertFalse(captured.auditResult().clean());
        assertFalse(captured.auditResult().toString().contains(secret));
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
                        proof()).testDecision().status());
        assertFalse(evaluator.evaluateCandidate(stored, candidateHistory(), evidence,
                proof()).testDecision().reducedResearchOperationalReady());
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
    void unknownEvidenceFieldsAndRecomputedUnrelatedEvidenceAreRejected() throws Exception {
        TushareControlledAcceptanceEvaluator evaluator = evaluator();
        RedactedEvidence evidence = evidence(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ProhibitedStageAttestation.VERIFIED_UNREACHABLE);
        var encoded = evaluator.encode(evidence);
        String withExtra = encoded.json().substring(0, encoded.json().length() - 1)
                + ",\"unexpected\":true}";
        assertThrows(IllegalStateException.class, () ->
                evaluator.decode(withExtra, sha256(withExtra)));

        RedactedEvidence unrelated = new RedactedEvidence(
                evidence.acceptanceId(), evidence.executionSource(),
                evidence.codeBaselineCommit(), evidence.artifactSha256(),
                evidence.providerCallCount(), evidence.retryCount(),
                99L, evidence.endpointCallCounts(),
                new DatabaseReadbackEvidence(99L, List.of(31L, 32L, 33L),
                        evidence.databaseReadback().factCounts(),
                        evidence.databaseReadback().observedAt(),
                        evidence.databaseReadback().minimumFirstObservedAt(),
                        evidence.databaseReadback().maximumFirstObservedAt(),
                        evidence.databaseReadback().minimumKnownAt(),
                        evidence.databaseReadback().maximumKnownAt(), 1234, 5678,
                        "stock_quant_research", "stock_quant_research",
                        "tushare_research", true, true),
                evidence.outputAudit(), evidence.prohibitedStageAttestation(),
                true, false, false, false, false,
                evidence.startedAt(), evidence.endedAt(), evidence.executorVersion(),
                evidence.qualificationRuleVersion());
        var unrelatedEncoded = evaluator.encode(unrelated);
        StoredExecution forged = new StoredExecution(
                stored(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                        ExecutionStatus.PASSED, evidence, evaluator).reservation(),
                ExecutionStatus.PASSED, START.plusSeconds(1), START.plusSeconds(2),
                START.plusSeconds(5), null, null, 11L, 3, 0,
                unrelatedEncoded.json(), unrelatedEncoded.digest(), 4);
        assertThrows(IllegalStateException.class, () -> evaluator.reloadAndRevalidate(
                forged, passedHistory(), proof()));
    }

    @Test
    void missingReorderedOrTimeReversedHistoryCannotProjectPassed() {
        TushareControlledAcceptanceEvaluator evaluator = evaluator();
        RedactedEvidence evidence = evidence(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ProhibitedStageAttestation.VERIFIED_UNREACHABLE);
        StoredExecution stored = stored(ExecutionSource.REAL_CONTROLLED_ACCEPTANCE,
                ExecutionStatus.PASSED, evidence, evaluator);
        assertThrows(IllegalStateException.class, () -> evaluator.reloadAndRevalidate(
                stored, passedHistory().subList(0, 4), proof()));
        List<Transition> reversedTime = new java.util.ArrayList<>(passedHistory());
        reversedTime.set(3, new Transition("F1FB1_TEST_001", ExecutionStatus.RUNNING,
                ExecutionStatus.SUCCEEDED_CANDIDATE, START.plusSeconds(1), 3, null));
        assertThrows(IllegalStateException.class, () -> evaluator.reloadAndRevalidate(
                stored, reversedTime, proof()));
    }

    @Test
    void stateShapeRejectsCandidateWithoutEvidenceAndNonMonotonicTimes() {
        Reservation reservation = reservation(ExecutionSource.TEST);
        assertThrows(IllegalArgumentException.class, () -> new StoredExecution(
                reservation, ExecutionStatus.SUCCEEDED_CANDIDATE,
                START.plusSeconds(1), START.plusSeconds(2), START.plusSeconds(4),
                null, null, 11L, 3, 0, null, null, 3));
        assertThrows(IllegalArgumentException.class, () -> new StoredExecution(
                reservation, ExecutionStatus.RUNNING,
                START.plusSeconds(2), START.plusSeconds(1), null,
                null, null, null, 0, 0, null, null, 2));
    }

    @Test
    void databaseReadbackRequiresExactTypedCountsAndDistinctObservationIds() {
        Map<FactType, Integer> exactCounts = Map.of(
                FactType.RAW_DAILY_BAR, 1,
                FactType.ADJUSTMENT_FACTOR, 1,
                FactType.TRADING_CALENDAR, 1);
        assertThrows(IllegalArgumentException.class, () ->
                new DatabaseReadbackEvidence(11L, List.of(21L, 21L, 23L),
                        exactCounts, START, START, START, START, START,
                        1234, 5678, "stock_quant_research",
                        "stock_quant_research", "tushare_research", true, true));
        assertThrows(IllegalArgumentException.class, () ->
                new DatabaseReadbackEvidence(11L, List.of(21L, 22L, 23L),
                        Map.of(FactType.RAW_DAILY_BAR, 3),
                        START, START, START, START, START,
                        1234, 5678, "stock_quant_research",
                        "stock_quant_research", "tushare_research", true, true));
    }

    @Test
    void boundaryAttestationIsDerivedFromExecutorType() {
        assertEquals(ProhibitedStageAttestation.VERIFIED_UNREACHABLE,
                TushareControlledAcceptanceBoundaryAttestor.attest(
                        TushareControlledAcceptanceExecutor.class));
        assertEquals(ProhibitedStageAttestation.NOT_ATTESTED,
                TushareControlledAcceptanceBoundaryAttestor.attest(
                        SpringReachableExecutor.class));
    }

    @Service
    private static final class SpringReachableExecutor {
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
        Instant observed = START.plusSeconds(3);
        DatabaseReadbackEvidence readback = new DatabaseReadbackEvidence(
                11, List.of(21L, 22L, 23L), Map.of(
                FactType.RAW_DAILY_BAR, 1,
                FactType.ADJUSTMENT_FACTOR, 1,
                FactType.TRADING_CALENDAR, 1), observed, observed, observed,
                observed, observed, 1234, 5678, "stock_quant_research",
                "stock_quant_research", "tushare_research", true, true);
        return new RedactedEvidence("F1FB1_TEST_001", source, COMMIT, SHA,
                3, 0, 11, Map.of(ControlledEndpoint.DAILY, 1,
                ControlledEndpoint.ADJ_FACTOR, 1, ControlledEndpoint.TRADE_CAL, 1),
                readback, new AuditResult(true, true, List.of()), stageAttestation,
                true, false, false, false, false, START, START.plusMillis(3500),
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
        Instant finalized = status == ExecutionStatus.PASSED
                ? START.plusSeconds(5) : START.plusSeconds(4);
        return new StoredExecution(reservation(source), status, START.plusSeconds(1),
                START.plusSeconds(2), finalized, null, null, 11L, 3, 0,
                encoded.json(), encoded.digest(), status == ExecutionStatus.PASSED ? 4 : 3);
    }

    private static Reservation reservation(ExecutionSource source) {
        return new Reservation("F1FB1_TEST_001", authorization().authorizationFingerprint(),
                source, TushareMarketFactProvider.PROVIDER_CODE, "600000.SH",
                LocalDate.of(2025, 1, 2), Set.of(ControlledEndpoint.DAILY,
                ControlledEndpoint.ADJ_FACTOR, ControlledEndpoint.TRADE_CAL),
                COMMIT, SHA, "stock_quant_research", "stock_quant_research",
                "tushare_research", 14, START, START.plusSeconds(60));
    }

    private static List<Transition> candidateHistory() {
        return List.of(transition(null, ExecutionStatus.AUTHORIZED, 0, START),
                transition(ExecutionStatus.AUTHORIZED, ExecutionStatus.RESERVED,
                        1, START.plusSeconds(1)),
                transition(ExecutionStatus.RESERVED, ExecutionStatus.RUNNING,
                        2, START.plusSeconds(2)),
                transition(ExecutionStatus.RUNNING, ExecutionStatus.SUCCEEDED_CANDIDATE,
                        3, START.plusSeconds(4)));
    }

    private static List<Transition> passedHistory() {
        var values = new java.util.ArrayList<>(candidateHistory());
        values.add(transition(ExecutionStatus.SUCCEEDED_CANDIDATE,
                ExecutionStatus.PASSED, 4, START.plusSeconds(5)));
        return values;
    }

    private static Transition transition(
            ExecutionStatus from,
            ExecutionStatus to,
            long version,
            Instant at
    ) {
        return new Transition("F1FB1_TEST_001", from, to, at, version, null);
    }

    private static void writeJar(Path jar, String commit) throws IOException {
        writeJar(jar, commit,
                TushareControlledAcceptanceBuildProof.REQUIRED_INTEGRATION_BRANCH);
    }

    private static void writeJar(Path jar, String commit, String branch)
            throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Stock-Quant-Git-Commit", commit);
        attributes.putValue("Stock-Quant-Git-Branch", branch);
        attributes.putValue("Stock-Quant-Tracked-Clean", "true");
        attributes.putValue("Stock-Quant-Untracked-Scope-Clean", "true");
        attributes.putValue("Stock-Quant-Build-Time", "2026-08-01T00:00:00Z");
        attributes.putValue("Stock-Quant-Java-Version", "17-test");
        attributes.putValue("Stock-Quant-Module-Version", "1.3.1");
        attributes.putValue("Stock-Quant-Executor-Version",
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION);
        attributes.putValue("Stock-Quant-Qualification-Rule-Version",
                TushareControlledAcceptanceExecution.RULE_VERSION);
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(jar), manifest)) {
            // Manifest-only test artifact.
        }
    }

    private static void writeSidecar(Path sidecar, String commit, String artifactHash)
            throws IOException {
        writeSidecar(sidecar, commit, artifactHash,
                TushareControlledAcceptanceBuildProof.REQUIRED_INTEGRATION_BRANCH);
    }

    private static void writeSidecar(
            Path sidecar,
            String commit,
            String artifactHash,
            String branch
    ) throws IOException {
        Files.writeString(sidecar, """
                git.commit=%s
                git.branch=%s
                git.trackedClean=true
                git.untrackedScopeClean=true
                artifact.sha256=%s
                build.time=2026-08-01T00:00:00Z
                java.version=17-test
                module.version=1.3.1
                executor.version=TUSHARE_CONTROLLED_ACCEPTANCE_EXECUTOR_V1
                qualification.rule.version=TUSHARE_CONTROLLED_ACCEPTANCE_RULE_V1
                """.formatted(commit, branch, artifactHash), StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
