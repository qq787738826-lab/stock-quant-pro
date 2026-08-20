package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.server.agent.research.AgentPromptCatalog;
import com.stockquant.server.agent.research.AgentResearchDatasetSource.LoadedDataset;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import com.stockquant.server.agent.research.AgentResearchModels.RuntimeLimits;
import com.stockquant.server.agent.research.AgentResearchRuntime;
import com.stockquant.server.agent.research.AgentResearchToolGateway;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.FrozenSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.RunStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRequest;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/** M1 -> M2 -> seven-agent M3 -> immutable M4 shadow coordinator. */
public final class ShadowResearchRuntime {
    private final ShadowResearchRepository repository;
    private final ShadowResearchDatasetSource datasetSource;
    private final ShadowPaperPortfolioService paper;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UnaryOperator<ShadowRecommendation> recommendationPolicy;

    public ShadowResearchRuntime(
            ShadowResearchRepository repository,
            ShadowResearchDatasetSource datasetSource,
            ShadowPaperPortfolioService paper,
            TransactionTemplate transaction,
            Clock clock
    ) {
        this(repository, datasetSource, paper, transaction, clock,
                UnaryOperator.identity());
    }

    public ShadowResearchRuntime(
            ShadowResearchRepository repository,
            ShadowResearchDatasetSource datasetSource,
            ShadowPaperPortfolioService paper,
            TransactionTemplate transaction,
            Clock clock,
            UnaryOperator<ShadowRecommendation> recommendationPolicy
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.datasetSource = Objects.requireNonNull(datasetSource,
                "datasetSource");
        this.paper = Objects.requireNonNull(paper, "paper");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recommendationPolicy = Objects.requireNonNull(
                recommendationPolicy, "recommendationPolicy");
    }

    public ShadowExecutionResult run(
            ShadowRequest request,
            ModelAdapter model
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(model, "model");
        try {
            Instant asOf = ShadowResearchCanonical.micros(
                    request.researchAsOf());
            String slot = request.researchSlot();
            String runKey = ShadowResearchCanonical.runKey(
                    request.tradeDate(), slot,
                    request.strategyVersion());
            String requestFingerprint = ShadowResearchCanonical.hash(Map.of(
                    "request", request, "slot", slot,
                    "runtime", ShadowResearchModels.RUNTIME_VERSION));
            var descriptor = model.descriptor();
            ShadowRun created = repository.createRun(runKey,
                    request.triggerMode(), request.tradeDate(), slot, asOf,
                    request.strategyVersion(),
                    descriptor.provider(), descriptor.model(),
                    promptVersion(),
                    com.stockquant.server.agent.research.AgentResearchModels
                            .RUNTIME_VERSION,
                    requestFingerprint);
            if (created.status() == RunStatus.FROZEN) {
                return existing(created, request.tushareProviderRequests());
            }
            if (created.status() != RunStatus.QUEUED) {
                throw new IllegalStateException(
                        "M4_SHADOW_SLOT_ALREADY_ACTIVE");
            }
            ShadowRun running = repository.start(created.id(),
                    clock.instant());
            try {
                ResearchTask task = task(request, running, asOf);
                LoadedDataset loaded = datasetSource.load(task);
                validateDataset(loaded, request, asOf);
                ResearchDataset dataset = loaded.dataset();
                Clock researchClock = request.triggerMode()
                        == ShadowResearchModels.TriggerMode.HISTORICAL_REPLAY
                        ? Clock.fixed(asOf, ZoneOffset.UTC) : clock;
                // Bind every deterministic tool call to the exact dataset
                // that passed the pre-model temporal and identity checks.
                AgentResearchToolGateway gateway =
                        new AgentResearchToolGateway(ignored -> loaded,
                                new DefaultStrategyResearchApi(),
                                BacktestConfig.standard(), researchClock);
                AgentResearchRuntime runtime = new AgentResearchRuntime(
                        gateway, model, new AgentPromptCatalog(),
                        researchClock);
                ResearchReport report = runtime.run(task);
                validateReport(report);
                Instant marketClose = com.stockquant.core.research
                        .StrategyResearchModels.closeInstant(
                                request.tradeDate());
                if (marketClose.isAfter(asOf)) {
                    throw new IllegalStateException(
                            "M4_RESEARCH_BEFORE_MARKET_CLOSE_FORBIDDEN");
                }
                // Research cannot be frozen before its as-of cut-off. A
                // fixed replay clock may equal it; live execution must not.
                if (clock.instant().isBefore(asOf)) {
                    throw new IllegalStateException(
                            "M4_RUNTIME_BEFORE_RESEARCH_AS_OF");
                }
                ShadowRecommendation recommendation =
                        ShadowRecommendation.from(report);
                recommendation = Objects.requireNonNull(
                        recommendationPolicy.apply(recommendation),
                        "recommendation");
                if (request.nextPaperExecutionTime() == null) {
                    recommendation = recommendation.withoutPaperExecution(
                            "NEXT_OPEN_SESSION_NOT_YET_KNOWN_AS_OF");
                }
                ShadowRecommendation frozenRecommendation = recommendation;
                Instant completedAt = ShadowResearchCanonical.micros(
                        clock.instant());
                // A replay is evaluated now, but its signal belongs to the
                // historical knowledge cut-off. Wall-clock completion would
                // blur the strict as-of boundary being proved.
                Instant signalTime = request.triggerMode()
                        == ShadowResearchModels.TriggerMode.HISTORICAL_REPLAY
                        ? asOf : completedAt.isBefore(asOf)
                        ? asOf : completedAt;
                if (request.nextPaperExecutionTime() != null
                        && !request.nextPaperExecutionTime().isAfter(
                        signalTime)) {
                    throw new IllegalStateException(
                            "M4_NEXT_EXECUTION_NOT_AFTER_SIGNAL");
                }
                Persisted persisted = Objects.requireNonNull(
                        transaction.execute(status -> {
                            FrozenSnapshot value = repository.insertSnapshot(
                                    running.id(), report,
                                    frozenRecommendation, completedAt);
                            repository.freezeRun(running.id(), signalTime,
                                    request.nextPaperExecutionTime(),
                                    report.dataset().datasetFingerprint(),
                                    report.strategyExperiments().fingerprint(),
                                    report.researchFingerprint(), completedAt);
                            ShadowRun frozen = repository.run(running.id())
                                    .orElseThrow();
                            List<PaperOrder> orders = paper.createOrders(
                                    frozen, frozenRecommendation,
                                    request.nextPaperExecutionTime(),
                                    dataset);
                            var portfolio = repository.lockPortfolio();
                            PortfolioSnapshot portfolioSnapshot =
                                    paper.snapshot(portfolio, frozen.id(),
                                            request.tradeDate(), completedAt,
                                            latestMarks(dataset));
                            return new Persisted(value, frozen, orders,
                                    portfolioSnapshot);
                        }), "snapshot");
                var usage = report.totalModelUsage();
                return new ShadowExecutionResult(persisted.run(),
                        persisted.snapshot(), persisted.orders(), List.of(),
                        repository.portfolio(),
                        persisted.portfolioSnapshot(),
                        report.modelCallCount(), descriptor.deterministic()
                        ? 0 : report.modelCallCount(),
                        request.tushareProviderRequests(),
                        usage.inputTokens(), usage.outputTokens(),
                        usage.reasoningTokens(), usage.totalTokens(),
                        usage.estimatedCost(), true, true, false);
            } catch (Throwable error) {
                repository.fail(running.id(),
                        error instanceof InterruptedException
                                ? RunStatus.INTERRUPTED : RunStatus.FAILED,
                        safeCode(error), clock.instant());
                throw error;
            }
        } finally {
            model.close();
        }
    }

    public int recoverStale(Duration age) {
        if (age == null || age.isNegative() || age.isZero()
                || age.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("M4_RECOVERY_AGE_INVALID");
        }
        Instant now = clock.instant();
        return repository.interruptStaleRuns(now.minus(age), now);
    }

    private ShadowExecutionResult existing(ShadowRun run,
                                           int tushareRequests) {
        var snapshot = repository.snapshot(run.id()).orElseThrow(() ->
                new IllegalStateException("M4_FROZEN_SNAPSHOT_MISSING"));
        var report = snapshot.report();
        var portfolio = repository.portfolio();
        var portfolioSnapshot = repository.portfolioSnapshot(run.id())
                .orElseThrow(() -> new IllegalStateException(
                        "M4_PORTFOLIO_SNAPSHOT_MISSING"));
        var usage = report.totalModelUsage();
        return new ShadowExecutionResult(run, snapshot,
                repository.orders(run.id()), repository.fills(run.id()),
                portfolio, portfolioSnapshot, report.modelCallCount(),
                0, tushareRequests,
                usage.inputTokens(), usage.outputTokens(),
                usage.reasoningTokens(), usage.totalTokens(),
                usage.estimatedCost(), true, true, true);
    }

    private static ResearchTask task(
            ShadowRequest request,
            ShadowRun run,
            Instant asOf
    ) {
        String taskId = "M3TASK_SHADOW_" + run.id() + "_"
                + request.tradeDate().toString().replace("-", "");
        return new ResearchTask(taskId, request.objective(),
                request.securities(), request.rangeStart(),
                request.tradeDate(), request.tradeDate(), asOf,
                request.benchmark(), request.strategies(),
                new RuntimeLimits(2, 8, 16,
                        Duration.ofMinutes(8)));
    }

    private static void validateDataset(
            LoadedDataset loaded,
            ShadowRequest request,
            Instant asOf
    ) {
        ResearchDataset dataset = loaded.dataset();
        if (!Set.copyOf(dataset.securities()).equals(
                Set.copyOf(request.securities()))) {
            throw new IllegalStateException(
                    "M4_DATASET_SECURITY_SCOPE_MISMATCH");
        }
        if (!dataset.knowledgeCutoff().equals(asOf)) {
            throw new IllegalStateException(
                    "M4_DATASET_KNOWLEDGE_CUTOFF_MISMATCH");
        }
        if (dataset.bars().stream().anyMatch(value ->
                value.tradeDate().isAfter(request.tradeDate()))) {
            throw new IllegalStateException(
                    "M4_DATASET_FUTURE_TRADE_DATE_FORBIDDEN");
        }
        if (dataset.bars().stream().anyMatch(value ->
                value.sourceKnownAt().isAfter(asOf))) {
            throw new IllegalStateException(
                    "M4_DATASET_FUTURE_KNOWLEDGE_FORBIDDEN");
        }
        if (!dataset.lastSessionDate().equals(request.tradeDate())) {
            throw new IllegalStateException(
                    "M4_DATASET_TARGET_SESSION_MISSING");
        }
        Set<?> targetSecurities = dataset.bars().stream()
                .filter(value -> value.tradeDate().equals(
                        request.tradeDate()))
                .map(value -> value.security())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!targetSecurities.equals(Set.copyOf(request.securities()))) {
            throw new IllegalStateException(
                    "M4_DATASET_TARGET_FACTS_INCOMPLETE");
        }
        if (!loaded.typedFactReadback()) {
            throw new IllegalStateException(
                    "M4_DATASET_TYPED_FACT_READBACK_REQUIRED");
        }
        if (!loaded.systemKnowledgeReadback()) {
            throw new IllegalStateException(
                    "M4_DATASET_SYSTEM_KNOWLEDGE_REQUIRED");
        }
        if (!loaded.dataQuality()) {
            throw new IllegalStateException(
                    "M4_DATASET_QUALITY_REQUIRED");
        }
        if (!loaded.noFutureDataLeakage()) {
            throw new IllegalStateException(
                    "M4_DATASET_NO_FUTURE_GUARD_REQUIRED");
        }
        if (!loaded.formulaOnlyQfq()) {
            throw new IllegalStateException(
                    "M4_DATASET_FORMULA_QFQ_REQUIRED");
        }
    }

    private static void validateReport(ResearchReport report) {
        if (!report.dataset().noFutureDataLeakage()) {
            throw new IllegalStateException(
                    "M4_REPORT_NO_FUTURE_GUARD_REQUIRED");
        }
        if (!report.dataset().typedFactReadback()) {
            throw new IllegalStateException(
                    "M4_REPORT_TYPED_FACT_READBACK_REQUIRED");
        }
        if (!report.dataset().systemKnowledgeReadback()) {
            throw new IllegalStateException(
                    "M4_REPORT_SYSTEM_KNOWLEDGE_REQUIRED");
        }
        if (!report.researchOnly() || report.providerCalled()
                || report.shadowStarted() || report.tradingStarted()) {
            throw new IllegalStateException(
                    "M4_REPORT_RESEARCH_ONLY_BOUNDARY_REQUIRED");
        }
        if (report.agentRuns().stream().map(value -> value.agentRole())
                .distinct().count() != 7) {
            throw new IllegalStateException(
                    "M4_REPORT_SEVEN_AGENT_TEAM_REQUIRED");
        }
        if (report.toolCallCount() != 4) {
            throw new IllegalStateException(
                    "M4_REPORT_TOOL_GATEWAY_COUNT_INVALID");
        }
        if (report.strategyExperiments().experiments().stream()
                .anyMatch(value -> !value.accountingInvariant()
                        || !value.lookAheadGuard())) {
            throw new IllegalStateException(
                    "M4_REPORT_STRATEGY_GUARD_REQUIRED");
        }
    }

    private static Map<com.stockquant.core.research.StrategyResearchModels.Security,
            BigDecimal> latestMarks(ResearchDataset dataset) {
        java.util.LinkedHashMap<com.stockquant.core.research
                .StrategyResearchModels.Security, BigDecimal> values =
                new java.util.LinkedHashMap<>();
        dataset.bars().forEach(value -> values.put(value.security(),
                value.close()));
        return values;
    }

    private static String promptVersion() {
        return "M3_PROMPT_CATALOG_V3";
    }

    private static String safeCode(Throwable error) {
        String value = error.getMessage();
        if (value != null && value.matches("[A-Z0-9_:.-]{4,128}")) {
            return value;
        }
        return "M4_SHADOW_RUNTIME_FAILED";
    }

    private record Persisted(
            FrozenSnapshot snapshot,
            ShadowRun run,
            List<PaperOrder> orders,
            PortfolioSnapshot portfolioSnapshot
    ) {
    }
}
