package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class StrategyResearchTestFixtures {
    private StrategyResearchTestFixtures() {
    }

    static ResearchDataset dataset(int securityCount, int sessionCount) {
        List<Security> securities = new ArrayList<>();
        for (int index = 0; index < securityCount; index++) {
            String symbol = index % 2 == 0
                    ? String.format("60%04d", index)
                    : String.format("00%04d", index);
            securities.add(new Security(symbol,
                    index % 2 == 0 ? "SSE" : "SZSE"));
        }
        List<TradingSession> sessions = sessions(sessionCount);
        Instant sourceKnownAt = StrategyResearchModels.closeInstant(
                sessions.get(sessions.size() - 1).tradeDate()).plusSeconds(60);
        List<DailyBar> bars = new ArrayList<>();
        for (int securityIndex = 0; securityIndex < securities.size();
                securityIndex++) {
            Security security = securities.get(securityIndex);
            for (int day = 0; day < sessions.size(); day++) {
                LocalDate date = sessions.get(day).tradeDate();
                BigDecimal trend = new BigDecimal("0.025")
                        .multiply(BigDecimal.valueOf(day));
                int cyclePosition = day % 30;
                BigDecimal cycle = new BigDecimal("0.11")
                        .multiply(BigDecimal.valueOf(
                                cyclePosition <= 15
                                        ? cyclePosition : 30 - cyclePosition));
                if ((securityIndex & 1) == 1) {
                    cycle = cycle.negate();
                }
                BigDecimal close = new BigDecimal("20")
                        .add(BigDecimal.valueOf(securityIndex * 4L))
                        .add(trend).add(cycle);
                BigDecimal open = close.add(BigDecimal.valueOf(
                        (day % 3 - 1) * 0.03d));
                BigDecimal high = open.max(close).add(new BigDecimal("0.30"));
                BigDecimal low = open.min(close).subtract(new BigDecimal("0.30"));
                bars.add(new DailyBar(security, date, open, high, low, close,
                        1_000_000L + day * 100L + securityIndex,
                        true, StrategyResearchModels.closeInstant(date),
                        sourceKnownAt));
            }
        }
        return new ResearchDataset(StrategyResearchModels.DATASET_CONTRACT,
                "FIXTURE_" + securityCount + "X" + sessionCount,
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH,
                sourceKnownAt.plusSeconds(1), sessions, bars);
    }

    static List<TradingSession> sessions(int count) {
        List<TradingSession> sessions = new ArrayList<>();
        LocalDate date = LocalDate.of(2024, 1, 2);
        while (sessions.size() < count) {
            if (date.getDayOfWeek().getValue() <= 5) {
                sessions.add(new TradingSession(date,
                        new LinkedHashSet<>(Set.of("SSE", "SZSE"))));
            }
            date = date.plusDays(1);
        }
        return List.copyOf(sessions);
    }

    static ResearchDataset replaceBars(
            ResearchDataset source,
            List<DailyBar> bars,
            String suffix
    ) {
        return new ResearchDataset(source.contractVersion(),
                source.datasetVersion() + suffix, source.knowledgeMode(),
                source.knowledgeCutoff(), source.sessions(), bars);
    }
}
