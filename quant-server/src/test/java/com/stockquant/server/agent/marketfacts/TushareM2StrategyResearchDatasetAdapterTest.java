package com.stockquant.server.agent.marketfacts;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.FormulaOnlyQfqBar;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.ResearchDataset;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.SecurityDataset;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareM2StrategyResearchDatasetAdapterTest {
    private static final LocalDate START = LocalDate.of(2025, 1, 2);
    private static final LocalDate END = LocalDate.of(2025, 1, 5);
    private static final LocalDate ANCHOR = LocalDate.of(2025, 1, 3);
    private static final Instant KNOWN_AT =
            Instant.parse("2026-08-11T01:00:00Z");
    private static final Instant CUTOFF =
            Instant.parse("2026-08-11T02:00:00Z");

    @Test
    void adaptsAcceptedM1FactsIntoDeterministicReadOnlyM2Dataset() {
        ResearchDataset source = dataset(List.of(
                security("600000", "SSE", 101, "10"),
                security("000001", "SZSE", 201, "20")));

        var first = TushareM2StrategyResearchDatasetAdapter.adapt(source);
        var reversed = TushareM2StrategyResearchDatasetAdapter.adapt(
                dataset(List.of(source.securities().get(1),
                        source.securities().get(0))));

        assertEquals(StrategyResearchModels.DATASET_CONTRACT,
                first.dataset().contractVersion());
        assertEquals(first.dataset().datasetVersion(),
                reversed.dataset().datasetVersion());
        assertEquals(2, first.dataset().securities().size());
        assertEquals(4, first.dataset().sessions().size());
        assertEquals(4, first.dataset().bars().size());
        assertTrue(first.dataset().sessions().get(0).anyOpen());
        assertTrue(first.dataset().sessions().get(1).anyOpen());
        assertFalse(first.dataset().sessions().get(2).anyOpen());
        assertFalse(first.dataset().sessions().get(3).anyOpen());
        assertTrue(first.typedFactReadback());
        assertTrue(first.systemKnowledgeReadback());
        assertTrue(first.dataQuality());
        assertTrue(first.noFutureDataLeakage());
        assertTrue(first.formulaOnlyLineageLimitationDisclosed());
        assertEquals(4L, first.dataset().bars().stream()
                .filter(bar -> bar.sourceKnownAt().equals(KNOWN_AT))
                .count());
    }

    private static ResearchDataset dataset(List<SecurityDataset> securities) {
        return new ResearchDataset("M1_RESEARCH_DATASET_V1", CUTOFF,
                START, END, ANCHOR, securities, 4, 4, 8, 4,
                true, false, true, true, true, true, true);
    }

    private static SecurityDataset security(
            String symbol,
            String exchange,
            long firstObservation,
            String basePrice
    ) {
        BigDecimal base = new BigDecimal(basePrice);
        List<FormulaOnlyQfqBar> bars = List.of(
                bar(ANCHOR, base.add(BigDecimal.ONE),
                        firstObservation + 2, firstObservation + 3),
                bar(START, base, firstObservation, firstObservation + 1));
        return new SecurityDataset(symbol, exchange,
                symbol + ("SSE".equals(exchange) ? ".SH" : ".SZ"),
                START, END, ANCHOR, 2, 2, 4, 2, 2, bars,
                KNOWN_AT.minusSeconds(60), KNOWN_AT,
                true, true, true, true);
    }

    private static FormulaOnlyQfqBar bar(
            LocalDate date,
            BigDecimal close,
            long rawObservation,
            long factorObservation
    ) {
        return new FormulaOnlyQfqBar(date, close,
                close.add(new BigDecimal("0.2")),
                close.subtract(new BigDecimal("0.2")), close,
                rawObservation, factorObservation);
    }
}
