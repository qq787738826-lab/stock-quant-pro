package com.stockquant.server.agent.research;

import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchWindowCommand;
import com.stockquant.server.agent.marketfacts.TushareM2StrategyResearchDatasetAdapter;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;

import java.time.Duration;
import java.util.Objects;

/** Production read-only bridge from M1_RESEARCH_DATASET_V1 into M3. */
public final class M1AgentResearchDatasetSource
        implements AgentResearchDatasetSource {
    private final TushareM2StrategyResearchDatasetAdapter adapter;

    public M1AgentResearchDatasetSource(
            TushareM2StrategyResearchDatasetAdapter adapter
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public LoadedDataset load(ResearchTask task) {
        Objects.requireNonNull(task, "task");
        Duration timeout = task.limits().timeout().compareTo(
                Duration.ofMinutes(2)) > 0
                ? Duration.ofMinutes(2) : task.limits().timeout();
        TushareM1ResearchWindowCommand command =
                new TushareM1ResearchWindowCommand(
                        task.securities().stream().map(value ->
                                new SecuritySelection(value.symbol(),
                                        value.exchange())).toList(),
                        task.rangeStart(), task.rangeEnd(),
                        task.anchorTradeDate(),
                        TushareM1ResearchWindowCommand.Mode
                                .IDEMPOTENCY_VERIFICATION,
                        timeout);
        TushareM2StrategyResearchDatasetAdapter.AdaptedDataset adapted =
                adapter.load(command, task.knowledgeCutoff());
        if (!adapted.dataset().securities().equals(task.securities())
                || !adapted.dataset().firstSessionDate().equals(
                task.rangeStart())
                || !adapted.dataset().lastSessionDate().equals(
                task.rangeEnd())
                || !adapted.dataset().knowledgeCutoff().equals(
                task.knowledgeCutoff())) {
            throw AgentResearchModels.invalid(
                    "M3_M1_DATASET_SCOPE_MISMATCH");
        }
        return new LoadedDataset(adapted.dataset(),
                adapted.sourceContractVersion(), adapted.rawDailyCount(),
                adapted.adjustmentFactorCount(), adapted.calendarCount(),
                adapted.qfqBarCount(), adapted.typedFactReadback(),
                adapted.systemKnowledgeReadback(), adapted.dataQuality(),
                adapted.noFutureDataLeakage(),
                adapted.formulaOnlyLineageLimitationDisclosed(), false);
    }
}
