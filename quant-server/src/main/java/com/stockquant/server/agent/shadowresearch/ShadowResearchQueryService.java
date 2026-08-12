package com.stockquant.server.agent.shadowresearch;

import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.FrozenSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperFill;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperPortfolio;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowOutcome;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Read-only UI projection; it exposes no execution or mutable account API. */
@Service
public final class ShadowResearchQueryService {
    private final ShadowResearchRepository repository;

    public ShadowResearchQueryService(ShadowResearchRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public Overview overview(int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return new Overview(ShadowResearchModels.UI_VERSION,
                ShadowResearchModels.RUNTIME_VERSION,
                ShadowResearchModels.SCHEDULER_VERSION,
                repository.runs(bounded), repository.portfolio(),
                repository.latestPortfolioSnapshot().orElse(null),
                true, false, false);
    }

    public RunDetail run(long id) {
        ShadowRun run = repository.run(id).orElseThrow(() ->
                new IllegalArgumentException("M4_SHADOW_RUN_NOT_FOUND"));
        FrozenSnapshot snapshot = repository.snapshot(id).orElse(null);
        return new RunDetail(run, snapshot, repository.orders(id),
                repository.fills(id), repository.outcomes(id));
    }

    public record Overview(
            String uiVersion,
            String runtimeVersion,
            String schedulerVersion,
            List<ShadowRun> runs,
            PaperPortfolio portfolio,
            PortfolioSnapshot latestPortfolioSnapshot,
            boolean researchOnly,
            boolean brokerConnected,
            boolean realTradingEnabled
    ) {
        public Overview {
            runs = List.copyOf(runs);
        }
    }

    public record RunDetail(
            ShadowRun run,
            FrozenSnapshot snapshot,
            List<PaperOrder> orders,
            List<PaperFill> fills,
            List<ShadowOutcome> outcomes
    ) {
        public RunDetail {
            orders = List.copyOf(orders);
            fills = List.copyOf(fills);
            outcomes = List.copyOf(outcomes);
        }
    }
}
