package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchDay001Authorization.Day001Mode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sanitized file result for one reduced-research Day 001 execution. */
record TushareReducedResearchDay001Result(
        String stage,
        FinalStatus status,
        String runId,
        String executionSource,
        String gitCommit,
        String artifactSha256,
        String provider,
        Day001Mode day001Mode,
        String symbol,
        String exchange,
        LocalDate tradeDate,
        Map<String, Integer> endpointCallCounts,
        int providerCallCount,
        int retryCount,
        Long captureBatchId,
        int newObservationCount,
        int existingChainTailCount,
        CheckResult typedFactReadback,
        CheckResult systemKnowledgeReadback,
        QfqSummary formulaOnlyQfq,
        OutputAuditSummary outputAudit,
        DatabaseTarget databaseTarget,
        ProhibitedStageAttestation prohibitedStages,
        boolean passedAcceptanceStatusProduced,
        boolean operationalReadinessModified,
        Instant startedAt,
        Instant completedAt,
        String safeFailureCode
) {
    static final String STAGE =
            "3A-R3B-RR-DAY001:REDUCED_RESEARCH_MANUAL_CAPTURE";

    TushareReducedResearchDay001Result {
        if (!STAGE.equals(stage)) {
            throw invalid();
        }
        status = Objects.requireNonNull(status, "status");
        runId = safeRunId(runId);
        if (!TushareReducedResearchDay001Authorization.EXECUTION_SOURCE.equals(
                executionSource)) {
            throw invalid();
        }
        gitCommit = safeCommit(gitCommit);
        artifactSha256 = safeSha256(artifactSha256);
        if (!TushareReducedResearchDay001Authorization.PROVIDER.equals(provider)) {
            throw invalid();
        }
        day001Mode = Objects.requireNonNull(day001Mode, "day001Mode");
        if (symbol == null || !symbol.matches("[0-9]{6}")
                || exchange == null || !exchange.matches("SSE|SZSE")) {
            throw invalid();
        }
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        LinkedHashMap<String, Integer> calls = new LinkedHashMap<>();
        calls.put("daily", requiredCount(endpointCallCounts, "daily"));
        calls.put("adj_factor", requiredCount(endpointCallCounts, "adj_factor"));
        calls.put("trade_cal", requiredCount(endpointCallCounts, "trade_cal"));
        if (endpointCallCounts.size() != calls.size()) {
            throw invalid();
        }
        endpointCallCounts = Map.copyOf(calls);
        typedFactReadback = Objects.requireNonNull(
                typedFactReadback, "typedFactReadback");
        systemKnowledgeReadback = Objects.requireNonNull(
                systemKnowledgeReadback, "systemKnowledgeReadback");
        formulaOnlyQfq = Objects.requireNonNull(formulaOnlyQfq, "formulaOnlyQfq");
        outputAudit = Objects.requireNonNull(outputAudit, "outputAudit");
        databaseTarget = Objects.requireNonNull(databaseTarget, "databaseTarget");
        prohibitedStages = Objects.requireNonNull(
                prohibitedStages, "prohibitedStages");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        safeFailureCode = safeFailureCode == null ? null
                : safeFailureCode(safeFailureCode);
        if (providerCallCount < 0 || providerCallCount > 3
                || retryCount < 0 || newObservationCount < 0
                || existingChainTailCount < 0
                || newObservationCount + existingChainTailCount > 3
                || captureBatchId != null && captureBatchId <= 0
                || completedAt.isBefore(startedAt)
                || passedAcceptanceStatusProduced
                || operationalReadinessModified
                || !prohibitedStages.allNotStarted()) {
            throw invalid();
        }
        if (status == FinalStatus.SUCCEEDED) {
            boolean newCapture = day001Mode == Day001Mode.NEW_CAPTURE
                    && newObservationCount == 3 && existingChainTailCount == 0;
            boolean idempotent = day001Mode == Day001Mode.IDEMPOTENCY_VERIFICATION
                    && newObservationCount == 0 && existingChainTailCount == 3;
            if (providerCallCount != 3 || retryCount != 0
                    || endpointCallCounts.values().stream().anyMatch(value -> value != 1)
                    || captureBatchId == null || !(newCapture || idempotent)
                    || typedFactReadback != CheckResult.PASSED
                    || systemKnowledgeReadback != CheckResult.PASSED
                    || !formulaOnlyQfq.successful()
                    || !outputAudit.successful()
                    || safeFailureCode != null) {
                throw invalid();
            }
        } else if (safeFailureCode == null) {
            throw invalid();
        }
    }

    static TushareReducedResearchDay001Result interruptedPlaceholder(
            TushareReducedResearchDay001Authorization authorization,
            Instant startedAt
    ) {
        return failure(authorization, FinalStatus.INTERRUPTED, startedAt,
                startedAt, zeroCalls(), 0, 0, null, 0, 0,
                CheckResult.NOT_RUN, CheckResult.NOT_RUN,
                QfqSummary.notRun(), OutputAuditSummary.notRun(),
                "TUSHARE_REDUCED_RESEARCH_EXECUTION_NOT_COMPLETED");
    }

    static TushareReducedResearchDay001Result failure(
            TushareReducedResearchDay001Authorization authorization,
            FinalStatus status,
            Instant startedAt,
            Instant completedAt,
            Map<String, Integer> endpointCalls,
            int providerCalls,
            int retries,
            Long batchId,
            int appended,
            int idempotent,
            CheckResult typedReadback,
            CheckResult systemKnowledgeReadback,
            QfqSummary qfq,
            OutputAuditSummary audit,
            String safeFailureCode
    ) {
        if (status == FinalStatus.SUCCEEDED) {
            throw invalid();
        }
        return create(authorization, status, startedAt, completedAt,
                endpointCalls, providerCalls, retries, batchId,
                appended, idempotent, typedReadback,
                systemKnowledgeReadback, qfq, audit, safeFailureCode);
    }

    static TushareReducedResearchDay001Result success(
            TushareReducedResearchDay001Authorization authorization,
            Instant startedAt,
            Instant completedAt,
            long batchId,
            int appended,
            int idempotent,
            QfqSummary qfq,
            OutputAuditSummary audit
    ) {
        return create(authorization, FinalStatus.SUCCEEDED,
                startedAt, completedAt, oneCallEach(), 3, 0, batchId,
                appended, idempotent, CheckResult.PASSED,
                CheckResult.PASSED, qfq, audit, null);
    }

    private static TushareReducedResearchDay001Result create(
            TushareReducedResearchDay001Authorization authorization,
            FinalStatus status,
            Instant startedAt,
            Instant completedAt,
            Map<String, Integer> endpointCalls,
            int providerCalls,
            int retries,
            Long batchId,
            int appended,
            int idempotent,
            CheckResult typedReadback,
            CheckResult systemKnowledgeReadback,
            QfqSummary qfq,
            OutputAuditSummary audit,
            String safeFailureCode
    ) {
        Objects.requireNonNull(authorization, "authorization");
        return new TushareReducedResearchDay001Result(
                STAGE, status, authorization.runId(),
                TushareReducedResearchDay001Authorization.EXECUTION_SOURCE,
                authorization.gitCommit(), authorization.artifactSha256(),
                TushareReducedResearchDay001Authorization.PROVIDER,
                authorization.day001Mode(), authorization.security().symbol(),
                authorization.security().exchange(), authorization.tradeDate(),
                endpointCalls, providerCalls, retries, batchId,
                appended, idempotent, typedReadback, systemKnowledgeReadback,
                qfq, audit,
                new DatabaseTarget(
                        TushareReducedResearchDay001Authorization.DATABASE_HOST,
                        authorization.databasePort(),
                        TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE,
                        TushareDedicatedResearchPersistenceGuard.REQUIRED_USER,
                        TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA),
                ProhibitedStageAttestation.noneStarted(), false, false,
                startedAt, completedAt, safeFailureCode);
    }

    static Map<String, Integer> zeroCalls() {
        return calls(0, 0, 0);
    }

    static Map<String, Integer> oneCallEach() {
        return calls(1, 1, 1);
    }

    private static Map<String, Integer> calls(
            int daily,
            int factor,
            int calendar
    ) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        result.put("daily", daily);
        result.put("adj_factor", factor);
        result.put("trade_cal", calendar);
        return result;
    }

    private static int requiredCount(Map<String, Integer> counts, String key) {
        Integer value = Objects.requireNonNull(counts, "endpointCallCounts").get(key);
        if (value == null || value < 0 || value > 1) {
            throw invalid();
        }
        return value;
    }

    private static String safeRunId(String value) {
        if (value == null || !value.matches("[A-Z0-9_-]{8,64}")) {
            throw invalid();
        }
        return value;
    }

    private static String safeCommit(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw invalid();
        }
        return value;
    }

    private static String safeSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        return value;
    }

    private static String safeFailureCode(String value) {
        if (!value.matches("[A-Z][A-Z0-9_]{7,127}")) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_REDUCED_RESEARCH_RESULT_INVALID");
    }

    enum FinalStatus {
        SUCCEEDED,
        FAILED_PRE_PROVIDER,
        FAILED_PROVIDER,
        FAILED_VALIDATION,
        FAILED_PERSISTENCE,
        FAILED_OUTPUT_AUDIT,
        INTERRUPTED
    }

    enum CheckResult {
        PASSED,
        FAILED,
        NOT_RUN
    }

    record QfqSummary(
            CheckResult result,
            int barCount,
            boolean formulaOnly,
            boolean persisted,
            boolean fullLineageClaimed
    ) {
        QfqSummary {
            result = Objects.requireNonNull(result, "result");
            if (barCount < 0 || persisted || fullLineageClaimed
                    || result == CheckResult.PASSED
                    && (barCount != 1 || !formulaOnly)
                    || result != CheckResult.PASSED && barCount != 0) {
                throw invalid();
            }
        }

        static QfqSummary passed() {
            return new QfqSummary(CheckResult.PASSED, 1, true, false, false);
        }

        static QfqSummary notRun() {
            return new QfqSummary(CheckResult.NOT_RUN, 0, false, false, false);
        }

        boolean successful() {
            return result == CheckResult.PASSED && barCount == 1
                    && formulaOnly && !persisted && !fullLineageClaimed;
        }
    }

    record OutputAuditSummary(
            boolean captureComplete,
            boolean clean,
            int hitCount,
            List<String> hitCategories
    ) {
        OutputAuditSummary {
            hitCategories = List.copyOf(Objects.requireNonNull(
                    hitCategories, "hitCategories"));
            if (hitCount < 0 || hitCount != hitCategories.size()
                    || clean != (captureComplete && hitCount == 0)
                    || hitCategories.stream().anyMatch(category ->
                    category == null || !category.matches("[A-Z_]{3,64}"))) {
                throw invalid();
            }
        }

        static OutputAuditSummary from(
                TushareControlledAcceptanceOutputAudit.AuditResult audit
        ) {
            Objects.requireNonNull(audit, "audit");
            return new OutputAuditSummary(
                    audit.captureComplete(), audit.clean(), audit.hits().size(),
                    audit.hits().stream()
                            .map(hit -> hit.category().name())
                            .toList());
        }

        static OutputAuditSummary notRun() {
            return new OutputAuditSummary(false, false, 0, List.of());
        }

        boolean successful() {
            return captureComplete && clean && hitCount == 0;
        }
    }

    record DatabaseTarget(
            String host,
            int port,
            String database,
            String user,
            String schema
    ) {
        DatabaseTarget {
            if (!TushareReducedResearchDay001Authorization.DATABASE_HOST.equals(host)
                    || port <= 0 || port > 65_535
                    || !TushareDedicatedResearchPersistenceGuard.REQUIRED_DATABASE
                    .equals(database)
                    || !TushareDedicatedResearchPersistenceGuard.REQUIRED_USER
                    .equals(user)
                    || !TushareDedicatedResearchPersistenceGuard.REQUIRED_SCHEMA
                    .equals(schema)) {
                throw invalid();
            }
        }
    }

    record ProhibitedStageAttestation(
            boolean controlledAcceptanceStateMachineStarted,
            boolean f2bStarted,
            boolean f3Started,
            boolean schedulerStarted,
            boolean agentStarted,
            boolean shadowStarted,
            boolean backtestStarted,
            boolean tradingStarted
    ) {
        static ProhibitedStageAttestation noneStarted() {
            return new ProhibitedStageAttestation(
                    false, false, false, false,
                    false, false, false, false);
        }

        boolean allNotStarted() {
            return !controlledAcceptanceStateMachineStarted
                    && !f2bStarted && !f3Started && !schedulerStarted
                    && !agentStarted && !shadowStarted && !backtestStarted
                    && !tradingStarted;
        }
    }

    /** Reserved result path; an existing file is never overwritten by a run. */
    static final class ResultFile {
        private static final ObjectMapper MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        private final Path path;

        private ResultFile(Path path) {
            this.path = path;
        }

        static ResultFile reserve(
                Path requestedPath,
                TushareReducedResearchDay001Result initial
        ) {
            Objects.requireNonNull(requestedPath, "requestedPath");
            Path path = requestedPath.toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent == null) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_RESULT_PATH_INVALID");
            }
            try {
                Files.createDirectories(parent);
                Files.createFile(path);
            } catch (IOException error) {
                throw new IllegalStateException(
                        "TUSHARE_REDUCED_RESEARCH_RESULT_RESERVATION_FAILED", error);
            }
            ResultFile resultFile = new ResultFile(path);
            resultFile.write(initial);
            return resultFile;
        }

        void write(TushareReducedResearchDay001Result result) {
            Objects.requireNonNull(result, "result");
            Path temporary = path.resolveSibling(
                    path.getFileName() + ".writing");
            try {
                String json = MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result) + System.lineSeparator();
                Files.writeString(temporary, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, path,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
                throw new IllegalStateException(
                        "TUSHARE_REDUCED_RESEARCH_RESULT_WRITE_FAILED", error);
            }
        }

        Path path() {
            return path;
        }
    }
}
