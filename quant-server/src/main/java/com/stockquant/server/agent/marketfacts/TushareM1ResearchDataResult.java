package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.ResearchDataset;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchWindowCommand.Mode;

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

/** Sanitized terminal evidence for one bounded M1 research-data run. */
record TushareM1ResearchDataResult(
        String stage,
        FinalStatus status,
        String runId,
        String executionSource,
        String gitCommit,
        String artifactSha256,
        List<SecuritySelection> securities,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        LocalDate anchorTradeDate,
        Mode mode,
        Map<String, Integer> endpointCallCounts,
        int providerCallCount,
        int retryCount,
        int stageProviderCallsBefore,
        int stageProviderCallsAfter,
        int cumulativeProviderCallsAfter,
        List<Long> captureBatchIds,
        int receivedFactCount,
        int newObservationCount,
        int idempotentChainTailCount,
        DatasetSummary researchDataset,
        AuditSummary outputAudit,
        String authorizationState,
        boolean providerAutostart,
        ProhibitedStages prohibitedStages,
        Instant startedAt,
        Instant completedAt,
        String safeFailureCode
) {
    static final String STAGE = "M1_RESEARCH_DATA_READY";

    TushareM1ResearchDataResult {
        if (!STAGE.equals(stage)) {
            throw invalid();
        }
        status = Objects.requireNonNull(status, "status");
        require(runId, "[A-Z0-9_-]{8,64}");
        if (!TushareM1ResearchDataAuthorization.EXECUTION_SOURCE.equals(
                executionSource)) {
            throw invalid();
        }
        require(gitCommit, "[0-9a-f]{40}");
        require(artifactSha256, "[0-9a-f]{64}");
        securities = List.copyOf(Objects.requireNonNull(
                securities, "securities"));
        Objects.requireNonNull(rangeStart, "rangeStart");
        Objects.requireNonNull(rangeEnd, "rangeEnd");
        Objects.requireNonNull(anchorTradeDate, "anchorTradeDate");
        Objects.requireNonNull(mode, "mode");
        endpointCallCounts = orderedCalls(endpointCallCounts);
        captureBatchIds = List.copyOf(Objects.requireNonNull(
                captureBatchIds, "captureBatchIds"));
        researchDataset = Objects.requireNonNull(
                researchDataset, "researchDataset");
        outputAudit = Objects.requireNonNull(outputAudit, "outputAudit");
        if (!"CONSUMED".equals(authorizationState)) {
            throw invalid();
        }
        prohibitedStages = Objects.requireNonNull(
                prohibitedStages, "prohibitedStages");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        safeFailureCode = safeFailureCode == null ? null
                : require(safeFailureCode, "[A-Z][A-Z0-9_]{7,127}");
        int securityCount = securities.size();
        int maximum = securityCount * 3;
        if (securities.isEmpty()
                || securities.size() > TushareManualBoundedSession.M1_MAX_SYMBOLS
                || providerCallCount < 0 || providerCallCount > maximum
                || retryCount != 0
                || stageProviderCallsBefore < 0
                || stageProviderCallsAfter
                != stageProviderCallsBefore + providerCallCount
                || stageProviderCallsAfter
                > TushareM1ResearchDataAuthorization.STAGE_PROVIDER_CALL_LIMIT
                || cumulativeProviderCallsAfter
                != TushareM1ResearchDataAuthorization
                .HISTORICAL_PROVIDER_CALL_BASELINE + stageProviderCallsAfter
                || receivedFactCount < 0 || newObservationCount < 0
                || idempotentChainTailCount < 0
                || newObservationCount + idempotentChainTailCount
                > receivedFactCount
                || providerAutostart
                || !prohibitedStages.noneStarted()
                || completedAt.isBefore(startedAt)) {
            throw invalid();
        }
        if (status == FinalStatus.SUCCEEDED) {
            boolean modeValid = mode == Mode.CAPTURE
                    ? newObservationCount > 0
                    : newObservationCount == 0
                    && idempotentChainTailCount == receivedFactCount;
            if (providerCallCount != maximum
                    || endpointCallCounts.values().stream().anyMatch(
                    count -> count != securityCount)
                    || captureBatchIds.size() != securityCount
                    || receivedFactCount <= 0
                    || newObservationCount + idempotentChainTailCount
                    != receivedFactCount
                    || !modeValid || !researchDataset.passed()
                    || !outputAudit.passed() || safeFailureCode != null) {
                throw invalid();
            }
        } else if (safeFailureCode == null) {
            throw invalid();
        }
    }

    static TushareM1ResearchDataResult placeholder(
            TushareM1ResearchDataAuthorization authorization,
            Instant startedAt
    ) {
        return failure(authorization, FinalStatus.INTERRUPTED,
                startedAt, startedAt, zeroCalls(), 0, 0,
                List.of(), 0, 0, 0, DatasetSummary.notRun(),
                AuditSummary.notRun(), "TUSHARE_M1_EXECUTION_NOT_COMPLETED");
    }

    static TushareM1ResearchDataResult success(
            TushareM1ResearchDataAuthorization authorization,
            Instant startedAt,
            Instant completedAt,
            TushareM1ResearchDataModels.RunEvidence evidence,
            AuditSummary audit
    ) {
        return create(authorization, FinalStatus.SUCCEEDED,
                startedAt, completedAt, evidence.endpointCallCounts(),
                evidence.providerCallCount(), evidence.retryCount(),
                evidence.captureBatchIds(), evidence.receivedFactCount(),
                evidence.appendedObservationCount(),
                evidence.idempotentChainTailCount(),
                DatasetSummary.from(evidence.dataset()), audit, null);
    }

    static TushareM1ResearchDataResult failure(
            TushareM1ResearchDataAuthorization authorization,
            FinalStatus status,
            Instant startedAt,
            Instant completedAt,
            Map<String, Integer> endpointCalls,
            int providerCalls,
            int retries,
            List<Long> batchIds,
            int received,
            int appended,
            int idempotent,
            DatasetSummary dataset,
            AuditSummary audit,
            String code
    ) {
        if (status == FinalStatus.SUCCEEDED) {
            throw invalid();
        }
        return create(authorization, status, startedAt, completedAt,
                endpointCalls, providerCalls, retries, batchIds, received,
                appended, idempotent, dataset, audit, code);
    }

    private static TushareM1ResearchDataResult create(
            TushareM1ResearchDataAuthorization authorization,
            FinalStatus status,
            Instant startedAt,
            Instant completedAt,
            Map<String, Integer> endpointCalls,
            int providerCalls,
            int retries,
            List<Long> batchIds,
            int received,
            int appended,
            int idempotent,
            DatasetSummary dataset,
            AuditSummary audit,
            String code
    ) {
        int stageAfter = authorization.stageProviderCallsBefore()
                + providerCalls;
        return new TushareM1ResearchDataResult(
                STAGE, status, authorization.runId(),
                TushareM1ResearchDataAuthorization.EXECUTION_SOURCE,
                authorization.gitCommit(), authorization.artifactSha256(),
                authorization.securities(), authorization.rangeStart(),
                authorization.rangeEnd(), authorization.anchorTradeDate(),
                authorization.mode(), endpointCalls, providerCalls, retries,
                authorization.stageProviderCallsBefore(), stageAfter,
                TushareM1ResearchDataAuthorization
                        .HISTORICAL_PROVIDER_CALL_BASELINE + stageAfter,
                batchIds, received, appended, idempotent, dataset, audit,
                "CONSUMED", false, ProhibitedStages.allOff(),
                startedAt, completedAt, code);
    }

    static Map<String, Integer> zeroCalls() {
        return calls(0, 0, 0);
    }

    static Map<String, Integer> calls(int daily, int factor, int calendar) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        result.put("daily", daily);
        result.put("adj_factor", factor);
        result.put("trade_cal", calendar);
        return Map.copyOf(result);
    }

    private static Map<String, Integer> orderedCalls(
            Map<String, Integer> values
    ) {
        Objects.requireNonNull(values, "endpointCallCounts");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String key : List.of("daily", "adj_factor", "trade_cal")) {
            Integer value = values.get(key);
            if (value == null || value < 0
                    || value > TushareManualBoundedSession.M1_MAX_SYMBOLS) {
                throw invalid();
            }
            result.put(key, value);
        }
        if (values.size() != result.size()) {
            throw invalid();
        }
        return Map.copyOf(result);
    }

    private static String require(String value, String pattern) {
        if (value == null || !value.matches(pattern)) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("TUSHARE_M1_RESULT_INVALID");
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

    record DatasetSummary(
            String contractVersion,
            int securityCount,
            int rawDailyCount,
            int adjustmentFactorCount,
            int calendarCount,
            int openDateCount,
            int closedDateCount,
            int qfqBarCount,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean formulaOnlyQfq,
            boolean fullQfqLineageClaimed,
            boolean dataQuality,
            boolean noFutureDataLeakage,
            boolean m2Readable
    ) {
        static DatasetSummary from(ResearchDataset dataset) {
            int open = dataset.securities().stream().mapToInt(
                    TushareM1ResearchDataModels.SecurityDataset::openDateCount)
                    .sum();
            int closed = dataset.securities().stream().mapToInt(
                    TushareM1ResearchDataModels.SecurityDataset::closedDateCount)
                    .sum();
            return new DatasetSummary(
                    dataset.contractVersion(), dataset.securities().size(),
                    dataset.totalRawDailyCount(),
                    dataset.totalAdjustmentFactorCount(),
                    dataset.totalCalendarCount(), open, closed,
                    dataset.totalQfqBarCount(),
                    dataset.typedFactReadbackPassed(),
                    dataset.systemKnowledgeReadbackPassed(),
                    dataset.formulaOnlyQfq(),
                    dataset.fullQfqLineageClaimed(),
                    dataset.dataQualityPassed(),
                    dataset.noFutureDataLeakage(), dataset.m2Readable());
        }

        static DatasetSummary notRun() {
            return new DatasetSummary("NOT_RUN", 0, 0, 0, 0, 0, 0, 0,
                    false, false, false, false, false, false, false);
        }

        boolean passed() {
            return "M1_RESEARCH_DATASET_V1".equals(contractVersion)
                    && securityCount > 0 && rawDailyCount > 0
                    && rawDailyCount == adjustmentFactorCount
                    && rawDailyCount == openDateCount
                    && calendarCount == openDateCount + closedDateCount
                    && qfqBarCount == rawDailyCount
                    && typedFactReadback && systemKnowledgeReadback
                    && formulaOnlyQfq && !fullQfqLineageClaimed
                    && dataQuality && noFutureDataLeakage && m2Readable;
        }
    }

    record AuditSummary(
            boolean captureComplete,
            boolean clean,
            int hitCount,
            List<String> hitCategories
    ) {
        AuditSummary {
            hitCategories = List.copyOf(Objects.requireNonNull(
                    hitCategories, "hitCategories"));
            if (hitCount < 0 || hitCount != hitCategories.size()
                    || clean != (captureComplete && hitCount == 0)) {
                throw invalid();
            }
        }

        static AuditSummary from(
                TushareControlledAcceptanceOutputAudit.AuditResult audit
        ) {
            return new AuditSummary(
                    audit.captureComplete(), audit.clean(), audit.hits().size(),
                    audit.hits().stream().map(value ->
                            value.category().name()).toList());
        }

        static AuditSummary notRun() {
            return new AuditSummary(false, false, 0, List.of());
        }

        boolean passed() {
            return captureComplete && clean && hitCount == 0;
        }
    }

    record ProhibitedStages(
            boolean f2b,
            boolean scheduler,
            boolean agent,
            boolean shadow,
            boolean backtest,
            boolean trading
    ) {
        static ProhibitedStages allOff() {
            return new ProhibitedStages(false, false, false,
                    false, false, false);
        }

        boolean noneStarted() {
            return !f2b && !scheduler && !agent && !shadow
                    && !backtest && !trading;
        }
    }

    static final class ResultFile {
        private static final ObjectMapper MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        private final Path path;

        private ResultFile(Path path) {
            this.path = path;
        }

        static ResultFile reserve(
                Path requested,
                TushareM1ResearchDataResult initial
        ) {
            Path path = Objects.requireNonNull(requested, "requested")
                    .toAbsolutePath().normalize();
            if (path.getParent() == null) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_RESULT_PATH_INVALID");
            }
            try {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            } catch (IOException error) {
                throw new IllegalStateException(
                        "TUSHARE_M1_RESULT_RESERVATION_FAILED", error);
            }
            ResultFile file = new ResultFile(path);
            file.write(initial);
            return file;
        }

        void write(TushareM1ResearchDataResult value) {
            Path temporary = path.resolveSibling(path.getFileName() + ".writing");
            try {
                String json = MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(value) + System.lineSeparator();
                Files.writeString(temporary, json, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, path,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException error) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw new IllegalStateException(
                        "TUSHARE_M1_RESULT_WRITE_FAILED", error);
            }
        }
    }
}
