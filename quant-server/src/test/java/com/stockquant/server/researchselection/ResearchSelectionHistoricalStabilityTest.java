package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.marketfacts.TushareResearchUniverseDatasetLoader.LoadedUniverse;
import com.stockquant.server.researchselection.ResearchSelectionHistoricalDatasetLoader.HistoricalDataset;
import com.stockquant.server.researchselection.ResearchSelectionModels.DataCoverage;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalAvailability;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalGrade;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalWindowCoverage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionHistoricalStabilityTest {

    @Test
    void sixtySessionHistoryProducesStrictNonBlockingStabilityEvidence() {
        ResearchDataset dataset = dataset(60);
        var ranking = new ResearchSelectionRankingService().rank(dataset);
        var result = new ResearchSelectionHistoricalStabilityService().analyze(
                historical(dataset), ranking, Map.of());

        assertEquals("HISTORICAL_STABILITY_SCORE_V1", result.version());
        assertEquals("POST_HOC_RESEARCH", result.researchLabel());
        assertEquals("PIT_PARTIAL", result.pitQualification());
        assertEquals(60, result.availableSessions());
        assertEquals(List.of(HistoricalAvailability.AVAILABLE,
                        HistoricalAvailability.AVAILABLE,
                        HistoricalAvailability.INSUFFICIENT_HISTORY,
                        HistoricalAvailability.INSUFFICIENT_HISTORY),
                result.windowCoverage().stream().map(
                        HistoricalWindowCoverage::status).toList());
        assertEquals(25, result.securities().size());
        assertEquals(25, result.gradeDistribution().values().stream()
                .mapToInt(Integer::intValue).sum());
        assertEquals(0, result.gradeDistribution().get("A"));
        assertTrue(result.securities().stream().allMatch(value ->
                value.availableSessions() == 60
                        && value.score().compareTo(BigDecimal.ZERO) >= 0
                        && value.score().compareTo(new BigDecimal("100")) <= 0
                        && value.grade() != HistoricalGrade.A
                        && value.walkForward().available()
                        && value.walkForward().strictlyIsolated()
                        && value.walkForward().noFutureDataLeakage()
                        && value.windows().stream().anyMatch(window ->
                        "CURRENT_20".equals(window.windowCode()))
                        && value.windows().stream().anyMatch(window ->
                        "CURRENT_60".equals(window.windowCode()))
                        && value.windows().stream().anyMatch(window ->
                        window.windowCode().startsWith("ROLLING_20_"))));
        assertTrue(result.securities().stream().allMatch(value -> {
            BigDecimal weighted = value.dataCompletenessComponent()
                    .multiply(new BigDecimal("0.20"))
                    .add(value.multiWindowConsistencyComponent()
                            .multiply(new BigDecimal("0.20")))
                    .add(value.outOfSampleComponent()
                            .multiply(new BigDecimal("0.25")))
                    .add(value.riskComponent()
                            .multiply(new BigDecimal("0.20")))
                    .add(value.costAndSampleComponent()
                            .multiply(new BigDecimal("0.15")))
                    .setScale(4, RoundingMode.HALF_EVEN);
            return weighted.subtract(value.score()).abs().compareTo(
                    new BigDecimal("0.0002")) <= 0
                    && value.bestWindowReturn().compareTo(
                    value.worstWindowReturn()) >= 0;
        }));
        assertTrue(result.dataQualityPassed());
        assertTrue(result.knownAtQualified());
        assertTrue(result.noFutureDataLeakage());
        assertTrue(result.securities().stream().allMatch(value ->
                value.limitations().contains("120日历史覆盖不足")
                        && value.limitations().contains("250日历史覆盖不足")
                        && value.limitations().contains(
                        "Live Shadow样本不足")));
    }

    @Test
    void historicalTargetsNeverExpandTheCurrentProviderWindow() {
        var request = new ResearchSelectionController.StartRequest(250)
                .toSelection();

        assertEquals(250, request.primaryWindow());
        assertEquals(60, request.auxiliaryWindow());
        assertEquals(10, request.shortlistSize());
        assertEquals(5, request.finalLimit());
    }

    @Test
    void agentObjectiveCarriesOneCompactDeterministicSummary() {
        ResearchDataset dataset = dataset(60);
        var ranking = new ResearchSelectionRankingService().rank(dataset);
        var history = new ResearchSelectionHistoricalStabilityService()
                .analyze(historical(dataset), ranking, Map.of());

        String objective = ResearchSelectionDeepResearchService.objective(
                history, ranking.subList(0, 10));

        assertTrue(objective.contains("HISTORICAL_STABILITY_SCORE_V1"));
        assertTrue(objective.contains("POST_HOC_RESEARCH"));
        assertTrue(objective.contains("PIT_PARTIAL"));
        assertTrue(objective.contains("Top10["));
        assertTrue(objective.contains("不得冒充Live Shadow"));
        assertTrue(objective.length() <= 500);
        assertFalse(objective.contains("Token"));
    }

    private static HistoricalDataset historical(ResearchDataset dataset) {
        int available = dataset.sessions().size();
        var coverage = new DataCoverage(dataset.firstSessionDate(),
                dataset.lastSessionDate(), available, available, 25, 25,
                0, 0, true, true, true, true, true);
        List<HistoricalWindowCoverage> windows = List.of(
                coverage(dataset, 20), coverage(dataset, 60),
                coverage(dataset, 120), coverage(dataset, 250));
        return new HistoricalDataset(new LoadedUniverse(dataset, coverage),
                windows);
    }

    private static HistoricalWindowCoverage coverage(
            ResearchDataset dataset,
            int requested
    ) {
        int available = dataset.sessions().size();
        boolean complete = available >= requested;
        LocalDate start = complete
                ? dataset.sessions().get(available - requested).tradeDate()
                : dataset.firstSessionDate();
        return new HistoricalWindowCoverage(requested,
                complete ? HistoricalAvailability.AVAILABLE
                        : HistoricalAvailability.INSUFFICIENT_HISTORY,
                Math.min(available, requested), start,
                dataset.lastSessionDate(), Math.max(0, requested - available),
                complete ? null : "INSUFFICIENT_HISTORY");
    }

    private static ResearchDataset dataset(int sessions) {
        List<LocalDate> dates = openDates(LocalDate.of(2026, 4, 1),
                sessions);
        List<TradingSession> calendar = dates.stream().map(date ->
                new TradingSession(date, Set.of("SSE", "SZSE"))).toList();
        List<DailyBar> bars = new ArrayList<>();
        List<Security> securities = ResearchUniverseV1.securities();
        for (int securityIndex = 0; securityIndex < securities.size();
                securityIndex++) {
            Security security = securities.get(securityIndex);
            for (int day = 0; day < dates.size(); day++) {
                BigDecimal close = BigDecimal.valueOf(20L + securityIndex)
                        .add(BigDecimal.valueOf((long) (securityIndex + 1)
                                * day, 2))
                        .add(day % 5 == 0 ? new BigDecimal("-0.08")
                                : new BigDecimal("0.03"));
                LocalDate date = dates.get(day);
                Instant marketClose = StrategyResearchModels.closeInstant(
                        date);
                bars.add(new DailyBar(security, date, close,
                        close.add(new BigDecimal("0.50")),
                        close.subtract(new BigDecimal("0.50")), close,
                        1_000_000L + securityIndex * 10_000L, true,
                        marketClose, marketClose.plusSeconds(60)));
            }
        }
        Instant cutoff = StrategyResearchModels.closeInstant(
                dates.get(dates.size() - 1)).plusSeconds(120);
        return new ResearchDataset(StrategyResearchModels.DATASET_CONTRACT,
                "RESEARCH_SELECTION_HISTORY_TEST_DATASET",
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, cutoff, calendar,
                bars);
    }

    private static List<LocalDate> openDates(LocalDate start, int count) {
        LinkedHashSet<LocalDate> result = new LinkedHashSet<>();
        LocalDate value = start;
        while (result.size() < count) {
            if (value.getDayOfWeek().getValue() <= 5) result.add(value);
            value = value.plusDays(1);
        }
        return List.copyOf(result);
    }
}
