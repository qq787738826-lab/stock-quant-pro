package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.research.OpenAiResponsesModelAdapter.FailureDiagnostics;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/** Secret-free terminal evidence emitted by the fixed V1.0.1 runner. */
public final class ResearchSelectionSanitizedResult {
    public static final String VERSION = "RESEARCH_SELECTION_RUNNER_RESULT_V1";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    private ResearchSelectionSanitizedResult() {
    }

    public static Result success(
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            SelectionResult selection,
            int appendedObservations,
            int idempotentChainTailHits,
            FailureDiagnostics diagnostics,
            boolean fake,
            boolean auditClean
    ) {
        var usage = selection.usage();
        return new Result(VERSION, "SUCCEEDED", executionId, gitCommit,
                startedAt, completedAt, selection.runId(),
                selection.publicRunId(), selection.status().name(),
                selection.anchorTradeDate(), selection.researchAsOf(),
                selection.dataCoverage().rangeStart(),
                selection.dataCoverage().rangeEnd(),
                selection.ranking().size(), selection.shortlist().size(),
                selection.candidates().size(), selection.emptyResult(),
                selection.decisionCode(), usage.tushareProviderRequests(),
                usage.retryCount(), usage.modelCalls(),
                usage.modelProviderRequests(), usage.inputTokens(),
                usage.outputTokens(), usage.reasoningTokens(),
                usage.totalTokens(), usage.conservativeCostCny(),
                appendedObservations, idempotentChainTailHits,
                selection.dataCoverage().typedFactReadback(),
                selection.dataCoverage().systemKnowledgeReadback(),
                selection.dataCoverage().formulaOnlyQfq(),
                selection.dataCoverage().noFutureDataLeakage(),
                selection.agentReport().toolCallCount(),
                selection.agentReport().agentRuns().stream()
                        .map(value -> value.agentRole().name()).distinct()
                        .toList(), selection.agentReport().criticReview()
                        .issues().stream().map(Enum::name).toList(),
                selection.timings().dataPreparationMillis(),
                selection.timings().quantitativeScanMillis(),
                selection.timings().strategyAnalysisMillis(),
                selection.timings().agentResearchMillis(),
                selection.timings().totalMillis(), fake, auditClean, true,
                false, diagnostics, null);
    }

    public static Result failure(
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            long runId,
            String publicRunId,
            int providerCalls,
            int retryCount,
            int modelProviderRequests,
            FailureDiagnostics diagnostics,
            String reason,
            boolean auditClean
    ) {
        int modelCalls = diagnostics == null ? 0
                : diagnostics.completedCallCount();
        int inputTokens = diagnostics == null ? 0
                : diagnostics.inputTokenCount();
        int outputTokens = diagnostics == null ? 0
                : diagnostics.outputTokenCount();
        int reasoningTokens = diagnostics == null ? 0
                : diagnostics.reasoningTokenCount();
        int totalTokens = diagnostics == null ? 0
                : diagnostics.totalTokenCount();
        BigDecimal cost = diagnostics == null ? BigDecimal.ZERO
                : diagnostics.accountedCost();
        return new Result(VERSION, "FAILED", executionId, gitCommit,
                startedAt, completedAt, runId, publicRunId, "FAILED", null,
                null, null, null, 0, 0, 0, true, null, providerCalls,
                retryCount, modelCalls, modelProviderRequests, inputTokens,
                outputTokens, reasoningTokens, totalTokens, cost, 0, 0,
                false, false, false, false, 0,
                List.of(), List.of(), 0, 0, 0, 0, 0, false, auditClean,
                true, false, diagnostics, reason);
    }

    public record Result(
            String schemaVersion,
            String status,
            String executionId,
            String gitCommit,
            Instant startedAt,
            Instant completedAt,
            long selectionRunId,
            String publicRunId,
            String selectionStatus,
            java.time.LocalDate anchorTradeDate,
            Instant researchAsOf,
            java.time.LocalDate dataRangeStart,
            java.time.LocalDate dataRangeEnd,
            int universeSize,
            int shortlistSize,
            int candidateCount,
            boolean emptyResult,
            String decisionCode,
            int tushareProviderCallCount,
            int retryCount,
            int modelCallCount,
            int modelProviderRequestCount,
            int inputTokens,
            int outputTokens,
            int reasoningTokens,
            int totalTokens,
            BigDecimal conservativeCostCny,
            int appendedObservations,
            int idempotentChainTailHits,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean formulaOnlyQfq,
            boolean noFutureDataLeakage,
            int toolCallCount,
            List<String> agentRoles,
            List<String> criticIssues,
            long dataPreparationMillis,
            long quantitativeScanMillis,
            long strategyAnalysisMillis,
            long agentResearchMillis,
            long totalMillis,
            boolean deterministicFake,
            boolean outputAuditClean,
            boolean researchOnly,
            boolean realTradingStarted,
            FailureDiagnostics modelDiagnostics,
            String failureReason
    ) {
        public Result {
            agentRoles = List.copyOf(agentRoles);
            criticIssues = List.copyOf(criticIssues);
        }
    }

    public static final class ResultFile {
        private final Path path;

        private ResultFile(Path path) {
            this.path = path;
        }

        public static ResultFile reserve(Path path, Result initial) {
            try {
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.getParent() == null
                        || normalized.toString().contains(".ai")) {
                    throw new IllegalArgumentException(
                            "RESEARCH_SELECTION_RESULT_PATH_INVALID");
                }
                Files.createDirectories(normalized.getParent());
                Files.writeString(normalized,
                        MAPPER.writeValueAsString(initial) + "\n",
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                return new ResultFile(normalized);
            } catch (IOException error) {
                throw new IllegalStateException(
                        "RESEARCH_SELECTION_RESULT_RESERVE_FAILED");
            }
        }

        public void write(Result result) {
            try {
                Files.writeString(path,
                        MAPPER.writeValueAsString(result) + "\n",
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } catch (IOException error) {
                throw new IllegalStateException(
                        "RESEARCH_SELECTION_RESULT_WRITE_FAILED");
            }
        }
    }
}
