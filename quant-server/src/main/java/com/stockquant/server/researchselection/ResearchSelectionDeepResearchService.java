package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.research.AgentResearchDatasetSource.LoadedDataset;
import com.stockquant.server.agent.research.ModelAdapter;
import com.stockquant.server.agent.shadowresearch.InMemoryShadowResearchDatasetSource;
import com.stockquant.server.agent.shadowresearch.ShadowPaperPortfolioService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRequest;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.TriggerMode;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRuntime;
import com.stockquant.server.researchselection.ResearchSelectionModels.QuantitativeScore;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Reuses the accepted M3/M4 runtime for a deterministic Top10 subset. */
public final class ResearchSelectionDeepResearchService
        implements ResearchSelectionEngine.DeepResearch {
    private final ShadowResearchRepository repository;
    private final ShadowPaperPortfolioService paper;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final NextPaperExecutionResolver nextExecution;

    public ResearchSelectionDeepResearchService(
            ShadowResearchRepository repository,
            ShadowPaperPortfolioService paper,
            TransactionTemplate transaction,
            Clock clock,
            NextPaperExecutionResolver nextExecution
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.paper = Objects.requireNonNull(paper, "paper");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nextExecution = Objects.requireNonNull(nextExecution,
                "nextExecution");
    }

    @Override
    public ShadowExecutionResult run(
            ResearchDataset topDataset,
            List<QuantitativeScore> shortlist,
            List<StrategySpec> strategies,
            SelectionRequest selectionRequest,
            String publicRunId,
            LocalDate anchor,
            Instant asOf,
            int providerCalls,
            ModelAdapter model
    ) {
        int bars = topDataset.bars().size();
        LoadedDataset accepted = new LoadedDataset(topDataset,
                "M1_RESEARCH_DATASET_V1", bars, bars,
                topDataset.sessions().size()
                        * topDataset.securities().size(), bars,
                true, true, true, true, true, false);
        var source = new InMemoryShadowResearchDatasetSource(accepted);
        var runtime = new ShadowResearchRuntime(repository, source, paper,
                transaction, clock, recommendation -> constrainRecommendation(
                        recommendation, shortlist,
                        selectionRequest.finalLimit()));
        boolean scheduled = selectionRequest.triggerMode()
                == ResearchSelectionModels.TriggerMode.SCHEDULED_SHADOW;
        TriggerMode trigger = scheduled ? TriggerMode.SCHEDULED
                : TriggerMode.ON_DEMAND_SELECTION;
        String slot = scheduled ? ShadowResearchModels.RESEARCH_SLOT
                : "ON_DEMAND_" + publicRunId.substring(
                publicRunId.length() - 12);
        Instant paperExecution = selectionRequest.paperEnabled()
                ? nextExecution.resolve(anchor, asOf) : null;
        var request = new ShadowRequest(trigger,
                anchor, topDataset.firstSessionDate(), asOf,
                topDataset.securities(), shortlist.get(0).security(),
                strategies, paperExecution, providerCalls,
                "Current-as-of evidence-bound stock selection over "
                        + ResearchUniverseV1.VERSION
                        + "; paper research only, no real trading.",
                slot,
                ShadowResearchModels.SELECTION_STRATEGY_VERSION);
        return runtime.run(request, model);
    }

    static ShadowRecommendation constrainRecommendation(
            ShadowRecommendation recommendation,
            List<QuantitativeScore> shortlist,
            int finalLimit
    ) {
        Map<String, QuantitativeScore> scores = shortlist.stream().collect(
                Collectors.toUnmodifiableMap(value ->
                        value.security().canonicalCode(), value -> value));
        Set<String> eligible = shortlist.stream().filter(value ->
                        value.dataQualityPassed()
                                && value.score().compareTo(
                                new java.math.BigDecimal("55.0000")) >= 0)
                .map(value -> value.security().canonicalCode())
                .collect(Collectors.toUnmodifiableSet());
        List<String> ranked = recommendation.rankedSecurities().stream()
                .filter(scores::containsKey).filter(eligible::contains)
                .distinct().limit(finalLimit).toList();
        List<String> limitations = new java.util.ArrayList<>(
                recommendation.limitations());
        if (ranked.isEmpty() && !limitations.contains(
                "NO_SECURITY_PASSED_SELECTION_THRESHOLD")) {
            limitations.add("NO_SECURITY_PASSED_SELECTION_THRESHOLD");
        }
        return new ShadowRecommendation(ranked.isEmpty()
                ? "INSUFFICIENT_EVIDENCE" : recommendation.decisionCode(),
                recommendation.rankedStrategies(), ranked,
                ranked.isEmpty() ? "NONE" : recommendation.preferredStrategy(),
                recommendation.riskLevel(), ranked.isEmpty()
                ? java.math.BigDecimal.ZERO : recommendation.confidence(),
                ranked.isEmpty() ? java.math.BigDecimal.ZERO
                        : recommendation.suggestedGrossExposure(),
                ranked.isEmpty() ? List.of()
                        : recommendation.supportingEvidenceIds(),
                List.copyOf(limitations), true, true);
    }

    @FunctionalInterface
    public interface NextPaperExecutionResolver {
        Instant resolve(LocalDate signalDate, Instant knowledgeCutoff);
    }
}
