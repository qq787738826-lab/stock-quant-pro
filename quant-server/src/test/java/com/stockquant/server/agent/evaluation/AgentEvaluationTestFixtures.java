package com.stockquant.server.agent.evaluation;

import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.research.AgentPromptCatalog;
import com.stockquant.server.agent.research.AgentResearchDatasetSource;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import com.stockquant.server.agent.research.AgentResearchModels.RuntimeLimits;
import com.stockquant.server.agent.research.AgentResearchRuntime;
import com.stockquant.server.agent.research.AgentResearchToolGateway;
import com.stockquant.server.agent.research.DeterministicFakeModelAdapter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentEvaluationTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-12T08:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private AgentEvaluationTestFixtures() {
    }

    static ResearchReport report() {
        return report(new AgentPromptCatalog());
    }

    static ResearchReport report(AgentPromptCatalog prompts) {
        return report(prompts,
                "Evaluate a bounded evidence-first research team.");
    }

    static ResearchReport report(
            AgentPromptCatalog prompts,
            String objective
    ) {
        Loaded loaded = loaded(objective);
        return report(prompts, loaded.value, loaded.task);
    }

    static ResearchReport report(
            AgentPromptCatalog prompts,
            AgentResearchDatasetSource.LoadedDataset loaded,
            ResearchTask task
    ) {
        AgentResearchDatasetSource source = ignored -> loaded;
        Clock clock = Clock.fixed(task.knowledgeCutoff(), ZoneOffset.UTC);
        var gateway = new AgentResearchToolGateway(source,
                new DefaultStrategyResearchApi(), BacktestConfig.standard(),
                clock);
        try (var runtime = new AgentResearchRuntime(gateway,
                new DeterministicFakeModelAdapter(),
                prompts, clock)) {
            return runtime.run(task);
        }
    }

    private static Loaded loaded(String objective) {
        List<Security> securities = List.of(new Security("600000", "SSE"),
                new Security("000001", "SZSE")).stream().sorted().toList();
        List<TradingSession> sessions = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        while (sessions.size() < 180) {
            if (date.getDayOfWeek().getValue() <= 5) {
                sessions.add(new TradingSession(date,
                        Set.of("SSE", "SZSE")));
            }
            date = date.plusDays(1);
        }
        List<DailyBar> bars = new ArrayList<>();
        for (int s = 0; s < securities.size(); s++) {
            for (int i = 0; i < sessions.size(); i++) {
                BigDecimal close = BigDecimal.valueOf(10 + s * 4)
                        .add(BigDecimal.valueOf(i)
                                .multiply(new BigDecimal("0.02")))
                        .add(BigDecimal.valueOf((i % 11) - 5)
                                .multiply(new BigDecimal("0.01")))
                        .setScale(4);
                LocalDate day = sessions.get(i).tradeDate();
                bars.add(new DailyBar(securities.get(s), day,
                        close.subtract(new BigDecimal("0.03")),
                        close.add(new BigDecimal("0.08")),
                        close.subtract(new BigDecimal("0.09")), close,
                        1_000_000, true,
                        StrategyResearchModels.closeInstant(day),
                        StrategyResearchModels.closeInstant(day).plusSeconds(60)));
            }
        }
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT, "M5_FIXTURE_180X2",
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, NOW, sessions, bars);
        var source = new AgentResearchDatasetSource.LoadedDataset(dataset,
                "M1_RESEARCH_DATASET_V1", bars.size(), bars.size(),
                sessions.size() * 2, bars.size(), true, true, true, true,
                true, false);
        List<StrategySpec> strategies = List.of(
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        Map.of("symbol", "ALL", "targetWeight", "0.80")),
                new StrategySpec(StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                        Map.of("shortWindow", "5", "longWindow", "20",
                                "targetWeight", "0.25")),
                new StrategySpec(StrategyRegistry.MEAN_REVERSION,
                        Map.of("lookback", "10", "entryDeviation", "0.02",
                                "exitDeviation", "0.00",
                                "targetWeight", "0.25")),
                new StrategySpec(StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                        Map.of("lookback", "20", "topN", "1",
                                "rebalanceEvery", "5",
                                "targetGrossExposure", "0.60")));
        ResearchTask task = new ResearchTask("M3TASK_M5_EVALUATION_001",
                objective,
                securities, sessions.get(0).tradeDate(),
                sessions.get(sessions.size() - 1).tradeDate(),
                sessions.get(sessions.size() - 1).tradeDate(), NOW,
                securities.get(0), strategies,
                new RuntimeLimits(2, 8, 16, Duration.ofSeconds(30)));
        return new Loaded(source, task);
    }

    private record Loaded(AgentResearchDatasetSource.LoadedDataset value,
                          ResearchTask task) {
    }
}
