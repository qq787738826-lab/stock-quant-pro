package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter.FailureDiagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Sanitized terminal evidence emitted by the fixed M4 runner. */
public final class TushareM4ShadowResearchResult {
    public static final String VERSION = "M4_SHADOW_RESEARCH_RESULT_V1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private TushareM4ShadowResearchResult() {
    }

    static Result success(
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            ShadowExecutionResult shadow,
            int providerCalls,
            int retryCount,
            boolean fake,
            boolean auditClean,
            FailureDiagnostics modelDiagnostics,
            int maintainedPaperFillCount
    ) {
        var report = shadow.snapshot().report();
        return new Result(VERSION, "SUCCEEDED", executionId, gitCommit,
                startedAt, completedAt, providerCalls, retryCount,
                shadow.modelProviderRequests(), shadow.inputTokens(),
                shadow.outputTokens(), shadow.reasoningTokens(),
                shadow.totalTokens(), shadow.conservativeCostCny(), "CNY",
                shadow.run().id(), shadow.run().runKey(),
                shadow.run().tradeDate(), shadow.run().researchAsOf(),
                shadow.run().signalTime(),
                shadow.run().paperExecutionTime(),
                shadow.snapshot().snapshotFingerprint(),
                shadow.run().datasetFingerprint(),
                shadow.run().strategyFingerprint(),
                shadow.run().researchFingerprint(),
                report.status().name(), report.modelCallCount(),
                report.toolCallCount(), report.agentRuns().stream()
                .map(value -> value.agentRole().name()).distinct().toList(),
                report.finalDecision().code().name(),
                report.finalDecision().confidence(),
                report.risk().overallLevel().name(),
                report.criticReview().issues().stream()
                        .map(Enum::name).toList(),
                report.evidence().size(), report.dataset().rangeStart(),
                report.dataset().rangeEnd(),
                report.dataset().datasetFingerprint(),
                report.dataset().typedFactReadback(),
                report.dataset().systemKnowledgeReadback(),
                report.dataset().formulaOnlyQfq(),
                report.dataset().noFutureDataLeakage(),
                shadow.orders().size(),
                shadow.fills().size() + maintainedPaperFillCount,
                shadow.portfolio().cash(),
                shadow.portfolioSnapshot().totalEquity(),
                shadow.portfolioSnapshot().totalReturn(), auditClean, fake,
                true, false, false, modelDiagnostics, null);
    }

    static Result failure(
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            int providerCalls,
            int retryCount,
            int modelProviderRequests,
            int inputTokens,
            int outputTokens,
            int reasoningTokens,
            int totalTokens,
            java.math.BigDecimal conservativeCostCny,
            FailureDiagnostics modelDiagnostics,
            String reason,
            boolean auditClean
    ) {
        return new Result(VERSION, "FAILED", executionId, gitCommit,
                startedAt, completedAt, providerCalls, retryCount,
                modelProviderRequests, inputTokens, outputTokens,
                reasoningTokens, totalTokens, conservativeCostCny, "CNY",
                null, null, null, null, null, null, null, null, null, null,
                null, 0, 0, List.of(), null, null, null, List.of(), 0,
                null, null, null, false, false, false, false, 0, 0, null,
                null, null, auditClean, false, true, false, false,
                modelDiagnostics, reason);
    }

    static Result skippedNonTradingDay(
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            java.time.LocalDate tradeDate,
            int providerCalls,
            int retryCount,
            boolean fake,
            boolean auditClean
    ) {
        return new Result(VERSION, "SKIPPED_NON_TRADING_DAY", executionId,
                gitCommit, startedAt, completedAt, providerCalls, retryCount,
                0, 0, 0, 0, 0, java.math.BigDecimal.ZERO, "CNY",
                null, null, tradeDate, null, null, null, null, null, null,
                null, null, 0, 0, List.of(), null, null, null, List.of(), 0,
                null, null, null, false, false, false, false, 0, 0, null,
                null, null, auditClean, fake, true, false, false, null, null);
    }

    public record Result(
            String schemaVersion,
            String status,
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            int tushareProviderCallCount,
            int retryCount,
            int modelProviderRequestCount,
            int inputTokens,
            int outputTokens,
            int reasoningTokens,
            int totalTokens,
            java.math.BigDecimal conservativeCostCny,
            String costCurrency,
            Long shadowRunId,
            String shadowRunKey,
            java.time.LocalDate tradeDate,
            Instant researchAsOf,
            Instant signalTime,
            Instant paperExecutionTime,
            String snapshotFingerprint,
            String datasetFingerprint,
            String strategyFingerprint,
            String researchFingerprint,
            String researchStatus,
            int modelCallCount,
            int toolCallCount,
            List<String> agentRoles,
            String decisionCode,
            java.math.BigDecimal confidence,
            String riskLevel,
            List<String> criticIssues,
            int evidenceCount,
            java.time.LocalDate datasetRangeStart,
            java.time.LocalDate datasetRangeEnd,
            String reportDatasetFingerprint,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean formulaOnlyQfq,
            boolean noFutureDataLeakage,
            int paperOrderCount,
            int paperFillCount,
            java.math.BigDecimal paperCash,
            java.math.BigDecimal paperEquity,
            java.math.BigDecimal paperTotalReturn,
            boolean outputAuditClean,
            boolean deterministicFake,
            boolean researchOnly,
            boolean brokerConnected,
            boolean realTradingStarted,
            FailureDiagnostics modelDiagnostics,
            String failureReason
    ) {
        public Result {
            agentRoles = List.copyOf(agentRoles);
            criticIssues = List.copyOf(criticIssues);
        }
    }

    static final class ResultFile {
        private final Path path;

        private ResultFile(Path path) {
            this.path = path;
        }

        static ResultFile reserve(Path path, Result initial) {
            try {
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.getParent() == null
                        || normalized.toString().contains(".ai")) {
                    throw new IllegalArgumentException(
                            "M4_RESULT_PATH_INVALID");
                }
                Files.createDirectories(normalized.getParent());
                Files.writeString(normalized,
                        MAPPER.writeValueAsString(initial) + "\n",
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ResultFile(normalized);
            } catch (IOException error) {
                throw new IllegalStateException("M4_RESULT_RESERVE_FAILED");
            }
        }

        void write(Result result) {
            try {
                Files.writeString(path,
                        MAPPER.writeValueAsString(result) + "\n",
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException error) {
                throw new IllegalStateException("M4_RESULT_WRITE_FAILED");
            }
        }
    }
}
