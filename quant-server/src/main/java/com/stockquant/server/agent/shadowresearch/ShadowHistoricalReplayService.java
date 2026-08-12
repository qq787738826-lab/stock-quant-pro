package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.shadowresearch.ShadowPaperPortfolioService.Execution;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Bounded historical replay; each date uses only its supplied as-of view. */
public final class ShadowHistoricalReplayService {
    private final ShadowResearchRuntime runtime;
    private final ShadowPaperPortfolioService paper;
    private final ShadowOutcomeService outcomes;

    public ShadowHistoricalReplayService(
            ShadowResearchRuntime runtime,
            ShadowPaperPortfolioService paper,
            ShadowOutcomeService outcomes
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.paper = Objects.requireNonNull(paper, "paper");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    public ReplayResult replay(
            List<ReplayStep> steps,
            Supplier<ModelAdapter> models
    ) {
        if (steps == null || steps.isEmpty() || steps.size() > 250) {
            throw new IllegalArgumentException("M4_REPLAY_SCOPE_INVALID");
        }
        Objects.requireNonNull(models, "models");
        List<ShadowExecutionResult> research = new ArrayList<>();
        List<Execution> executions = new ArrayList<>();
        List<List<ShadowResearchModels.ShadowOutcome>> observed =
                new ArrayList<>();
        LocalDate previous = null;
        for (ReplayStep step : steps) {
            if (step.request().triggerMode()
                    != ShadowResearchModels.TriggerMode.HISTORICAL_REPLAY
                    || previous != null
                    && !step.request().tradeDate().isAfter(previous)) {
                throw new IllegalArgumentException(
                        "M4_REPLAY_ORDER_INVALID");
            }
            ShadowExecutionResult result = runtime.run(step.request(),
                    models.get());
            research.add(result);
            executions.add(paper.executeDue(step.executionDate(),
                    step.executionTime(), step.executionDataset(),
                    result.run().id()));
            observed.add(outcomes.evaluateAvailable(result.run().id(),
                    step.executionDataset(), step.outcomeEvaluationTime()));
            previous = step.request().tradeDate();
        }
        int fills = executions.stream().mapToInt(value ->
                value.fills().size()).sum();
        return new ReplayResult(ShadowResearchModels.REPLAY_VERSION,
                List.copyOf(research), List.copyOf(executions), fills,
                observed.stream().mapToInt(List::size).sum(),
                research.stream().allMatch(
                        ShadowExecutionResult::noFutureDataLeakage), true);
    }

    public record ReplayStep(
            ShadowRequest request,
            LocalDate executionDate,
            Instant executionTime,
            ResearchDataset executionDataset,
            Instant outcomeEvaluationTime
    ) {
        public ReplayStep {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(executionDate, "executionDate");
            Objects.requireNonNull(executionTime, "executionTime");
            Objects.requireNonNull(executionDataset, "executionDataset");
            Objects.requireNonNull(outcomeEvaluationTime,
                    "outcomeEvaluationTime");
            if (!executionDate.isAfter(request.tradeDate())
                    || !executionTime.isAfter(request.researchAsOf())
                    || executionDataset.lastSessionDate()
                    .isBefore(executionDate)
                    || outcomeEvaluationTime.isBefore(executionTime)) {
                throw new IllegalArgumentException(
                        "M4_REPLAY_EXECUTION_BOUNDARY_INVALID");
            }
        }
    }

    public record ReplayResult(
            String version,
            List<ShadowExecutionResult> researchRuns,
            List<Execution> paperExecutions,
            int fillCount,
            int outcomeCount,
            boolean noFutureDataLeakage,
            boolean researchOnly
    ) {
        public ReplayResult {
            researchRuns = List.copyOf(researchRuns);
            paperExecutions = List.copyOf(paperExecutions);
            if (!ShadowResearchModels.REPLAY_VERSION.equals(version)
                    || researchRuns.isEmpty()
                    || paperExecutions.size() != researchRuns.size()
                    || fillCount < 0 || outcomeCount < 0
                    || !noFutureDataLeakage
                    || !researchOnly) {
                throw new IllegalArgumentException(
                        "M4_REPLAY_RESULT_INVALID");
            }
        }
    }
}
