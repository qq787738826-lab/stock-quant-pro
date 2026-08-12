package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.research.AgentResearchDatasetSource.LoadedDataset;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShadowResearchTestFixtures {
    static final Instant AS_OF = Instant.parse("2025-11-20T08:30:00Z");

    private ShadowResearchTestFixtures() {
    }

    static LoadedDataset dataset() {
        List<Security> securities = List.of(
                new Security("600000", "SSE"),
                new Security("000001", "SZSE")).stream().sorted().toList();
        List<TradingSession> sessions = new ArrayList<>();
        LocalDate date = LocalDate.of(2025, 1, 2);
        while (sessions.size() < 230) {
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
                LocalDate tradeDate = sessions.get(i).tradeDate();
                bars.add(new DailyBar(securities.get(s), tradeDate,
                        close.subtract(new BigDecimal("0.03")),
                        close.add(new BigDecimal("0.08")),
                        close.subtract(new BigDecimal("0.09")), close,
                        1_000_000, true,
                        StrategyResearchModels.closeInstant(tradeDate),
                        StrategyResearchModels.closeInstant(tradeDate)
                                .plusSeconds(60)));
            }
        }
        ResearchDataset value = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT,
                "M1_TO_M2_M4_FIXTURE_230X2",
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, AS_OF,
                sessions, bars);
        return new LoadedDataset(value, "M1_RESEARCH_DATASET_V1",
                bars.size(), bars.size(), sessions.size() * 2, bars.size(),
                true, true, true, true, true, false);
    }

    static ShadowResearchDatasetSource source() {
        return new ShadowResearchDatasetSource() {
            private LoadedDataset loaded;

            @Override
            public LoadedDataset load(ResearchTask task) {
                loaded = dataset();
                if (!loaded.dataset().securities().equals(task.securities())
                        || loaded.dataset().lastSessionDate().isBefore(
                        task.rangeEnd())) {
                    throw new IllegalStateException("M4_FIXTURE_SCOPE_MISMATCH");
                }
                var bounded = new InMemoryShadowResearchDatasetSource(loaded);
                this.loaded = bounded.load(task);
                return this.loaded;
            }

            @Override
            public LoadedDataset requireLastLoaded() {
                return loaded;
            }
        };
    }

    static List<StrategySpec> strategies() {
        return List.of(
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
    }
}
