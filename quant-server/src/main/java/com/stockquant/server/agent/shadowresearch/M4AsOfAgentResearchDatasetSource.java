package com.stockquant.server.agent.shadowresearch;

import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareM1AsOfDatasetLoader;
import com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchDatasetAdapter;
import com.stockquant.server.agent.research.AgentResearchDatasetSource;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;

import java.util.Objects;

/**
 * M4 bridge that resolves every M1 fact at the task's immutable knowledge
 * cutoff before adapting it to the deterministic M2/M3 dataset contract.
 */
public final class M4AsOfAgentResearchDatasetSource
        implements ShadowResearchDatasetSource {
    private final TushareM1AsOfDatasetLoader loader;
    private volatile LoadedDataset lastLoaded;

    public M4AsOfAgentResearchDatasetSource(
            TushareM1AsOfDatasetLoader loader
    ) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public LoadedDataset load(ResearchTask task) {
        Objects.requireNonNull(task, "task");
        var m1 = loader.load(task.securities().stream().map(value ->
                        new SecuritySelection(value.symbol(), value.exchange()))
                .toList(), task.rangeStart(), task.rangeEnd(),
                task.knowledgeCutoff());
        var adapted = TushareM2StrategyResearchDatasetAdapter.adapt(m1);
        if (!adapted.dataset().securities().equals(task.securities())
                || adapted.dataset().firstSessionDate().isBefore(
                task.rangeStart())
                || adapted.dataset().lastSessionDate().isAfter(task.rangeEnd())
                || !adapted.dataset().knowledgeCutoff().equals(
                task.knowledgeCutoff())) {
            throw new IllegalStateException(
                    "M4_AS_OF_DATASET_SCOPE_MISMATCH");
        }
        LoadedDataset result = new LoadedDataset(adapted.dataset(),
                adapted.sourceContractVersion(), adapted.rawDailyCount(),
                adapted.adjustmentFactorCount(), adapted.calendarCount(),
                adapted.qfqBarCount(), adapted.typedFactReadback(),
                adapted.systemKnowledgeReadback(), adapted.dataQuality(),
                adapted.noFutureDataLeakage(),
                adapted.formulaOnlyLineageLimitationDisclosed(), false);
        lastLoaded = result;
        return result;
    }

    public LoadedDataset requireLastLoaded() {
        LoadedDataset value = lastLoaded;
        if (value == null) {
            throw new IllegalStateException("M4_AS_OF_DATASET_NOT_LOADED");
        }
        return value;
    }
}
