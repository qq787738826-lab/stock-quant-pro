package com.stockquant.server.agent.research;

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
import com.stockquant.server.agent.research.AgentResearchModels.RuntimeLimits;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentResearchTestFixtures {
    static final Instant CUTOFF = Instant.parse("2026-08-10T12:00:00Z");

    private AgentResearchTestFixtures() {
    }

    static LoadedDataset loadedDataset() {
        List<Security> securities = List.of(
                new Security("600000", "SSE"),
                new Security("600001", "SSE"),
                new Security("000001", "SZSE"),
                new Security("000002", "SZSE")).stream().sorted().toList();
        List<TradingSession> sessions = sessions(180);
        List<DailyBar> bars = new ArrayList<>();
        for (int securityIndex = 0; securityIndex < securities.size();
                securityIndex++) {
            Security security = securities.get(securityIndex);
            BigDecimal base = BigDecimal.valueOf(8L + securityIndex * 5L);
            for (int index = 0; index < sessions.size(); index++) {
                LocalDate date = sessions.get(index).tradeDate();
                long wave = (index % (13 + securityIndex)) - 6L;
                BigDecimal close = base
                        .add(BigDecimal.valueOf(index)
                                .multiply(new BigDecimal("0.025")))
                        .add(BigDecimal.valueOf(wave)
                                .multiply(new BigDecimal("0.018")))
                        .setScale(4);
                BigDecimal open = close.subtract(new BigDecimal("0.0200"));
                BigDecimal high = close.add(new BigDecimal("0.0800"));
                BigDecimal low = open.subtract(new BigDecimal("0.0600"));
                bars.add(new DailyBar(security, date, open, high, low, close,
                        1_000_000L + index * 1_000L, true,
                        StrategyResearchModels.closeInstant(date),
                        StrategyResearchModels.closeInstant(date)
                                .plusSeconds(3_600)));
            }
        }
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT,
                "M1_TO_M2_FIXTURE_180X4", KnowledgeMode
                .SYSTEM_KNOWLEDGE_RESEARCH, CUTOFF, sessions, bars);
        return new LoadedDataset(dataset, "M1_RESEARCH_DATASET_V1",
                bars.size(), bars.size(), sessions.size() * 2, bars.size(),
                true, true, true, true, true, false);
    }

    static ResearchTask task() {
        return task("Compare bounded research strategies with evidence.");
    }

    static ResearchTask task(String objective) {
        LoadedDataset loaded = loadedDataset();
        return new ResearchTask("M3TASK_FIXTURE_RESEARCH_001", objective,
                loaded.dataset().securities(),
                loaded.dataset().firstSessionDate(),
                loaded.dataset().lastSessionDate(),
                loaded.dataset().lastSessionDate(), CUTOFF,
                loaded.dataset().securities().get(0), List.of(
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
                        Map.of("lookback", "20", "topN", "2",
                                "rebalanceEvery", "5",
                                "targetGrossExposure", "0.80"))),
                new RuntimeLimits(2, 8, 12, Duration.ofSeconds(30)));
    }

    private static List<TradingSession> sessions(int count) {
        List<TradingSession> result = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        while (result.size() < count) {
            if (date.getDayOfWeek().getValue() <= 5) {
                result.add(new TradingSession(date, Set.of("SSE", "SZSE")));
            }
            date = date.plusDays(1);
        }
        return result;
    }
}
