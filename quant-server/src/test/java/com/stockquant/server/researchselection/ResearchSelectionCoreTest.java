package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels
        .ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionCoreTest {

    @Test
    void universeIsFixedDiverseMainBoardAndVersioned() {
        var constituents = ResearchUniverseV1.constituents();

        assertEquals("RESEARCH_UNIVERSE_V1", ResearchUniverseV1.VERSION);
        assertEquals(25, constituents.size());
        assertEquals(25, constituents.stream().map(value ->
                value.security().canonicalCode()).distinct().count());
        assertEquals(20, constituents.stream().filter(value ->
                "SSE".equals(value.security().exchange())).count());
        assertEquals(5, constituents.stream().filter(value ->
                "SZSE".equals(value.security().exchange())).count());
        assertTrue(constituents.stream().map(
                ResearchUniverseV1.Constituent::industry).distinct().count()
                >= 12);
        assertTrue(constituents.stream().allMatch(value ->
                value.security().symbol().matches("60[0135][0-9]{3}")
                        && "SSE".equals(value.security().exchange())
                        || value.security().symbol().matches("00[0123][0-9]{3}")
                        && "SZSE".equals(value.security().exchange())));
        assertEquals("600000:SSE",
                ResearchUniverseV1.benchmark().canonicalCode());
        assertThrows(UnsupportedOperationException.class, () ->
                constituents.add(constituents.get(0)));
    }

    @Test
    void rankingIsDeterministicExplainableAndCrossSectional() {
        ResearchDataset dataset = dataset(60);
        var service = new ResearchSelectionRankingService();

        var first = service.rank(dataset);
        var second = service.rank(dataset);

        assertEquals(first, second);
        assertEquals(25, first.size());
        assertEquals(java.util.stream.IntStream.rangeClosed(1, 25).boxed()
                .toList(), first.stream().map(value -> value.rank()).toList());
        assertEquals(25, first.stream().map(value ->
                value.security().canonicalCode()).distinct().count());
        assertTrue(first.stream().allMatch(value ->
                value.observationCount() == 60
                        && value.dataQualityPassed()
                        && value.explanations().size() == 5
                        && value.score().compareTo(BigDecimal.ZERO) >= 0
                        && value.score().compareTo(new BigDecimal("100"))
                        <= 0));
        assertTrue(first.get(0).twentyDayReturn().compareTo(
                first.get(first.size() - 1).twentyDayReturn()) > 0);
        assertFalse(first.get(0).name().isBlank());
        assertFalse(first.get(0).industry().isBlank());
    }

    @Test
    void rankingRejectsIncompleteWindowAndRequestBoundsAreClosed() {
        var service = new ResearchSelectionRankingService();
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.rank(dataset(19)));
        assertEquals("RESEARCH_SELECTION_MINIMUM_WINDOW_INCOMPLETE",
                failure.getMessage());

        var immediate = ResearchSelectionModels.SelectionRequest.immediate();
        assertEquals(20, immediate.primaryWindow());
        assertEquals(60, immediate.auxiliaryWindow());
        assertEquals(10, immediate.shortlistSize());
        assertEquals(5, immediate.finalLimit());
        assertTrue(immediate.paperEnabled());
        assertEquals("1.50",
                PowerShellResearchSelectionDispatchGateway.MAXIMUM_COST_CNY);
        assertThrows(IllegalArgumentException.class, () ->
                new ResearchSelectionModels.SelectionRequest(
                        ResearchSelectionModels.TriggerMode.ON_DEMAND,
                        30, 60, 10, 5, true));
        assertThrows(IllegalArgumentException.class, () ->
                new ResearchSelectionModels.SelectionRequest(
                        ResearchSelectionModels.TriggerMode.ON_DEMAND,
                        20, 60, 11, 5, true));
    }

    @Test
    void agentRankingAndQuantitativeGateBoundFinalPaperCandidates() {
        List<ResearchSelectionModels.QuantitativeScore> ranking =
                new ResearchSelectionRankingService().rank(dataset(60));
        var eligible = ranking.stream().filter(value ->
                        value.score().compareTo(new BigDecimal("55.0000"))
                                >= 0)
                .limit(4).toList();
        var below = ranking.stream().filter(value ->
                        value.score().compareTo(new BigDecimal("55.0000"))
                                < 0)
                .findFirst().orElseThrow();
        List<String> agentOrder = List.of(
                below.security().canonicalCode(),
                eligible.get(2).security().canonicalCode(),
                eligible.get(0).security().canonicalCode(),
                eligible.get(1).security().canonicalCode(),
                eligible.get(3).security().canonicalCode());
        var recommendation = new ShadowRecommendation(
                "RESEARCH_PREFERENCE", List.of("MEAN_REVERSION"),
                agentOrder, "MEAN_REVERSION", "MODERATE",
                new BigDecimal("0.68"), new BigDecimal("0.60"),
                List.of("EV_TEST_000000000001"), List.of(), true, true);

        var constrained = ResearchSelectionDeepResearchService
                .constrainRecommendation(recommendation, ranking, 3);

        assertEquals(List.of(eligible.get(2).security().canonicalCode(),
                        eligible.get(0).security().canonicalCode(),
                        eligible.get(1).security().canonicalCode()),
                constrained.rankedSecurities());
        assertEquals(new BigDecimal("0.60"),
                constrained.suggestedGrossExposure());

        var empty = ResearchSelectionDeepResearchService
                .constrainRecommendation(new ShadowRecommendation(
                        "RESEARCH_PREFERENCE", List.of("MEAN_REVERSION"),
                        List.of(below.security().canonicalCode()),
                        "MEAN_REVERSION", "MODERATE",
                        new BigDecimal("0.68"), new BigDecimal("0.60"),
                        List.of("EV_TEST_000000000001"), List.of(), true,
                        true), ranking, 5);
        assertTrue(empty.rankedSecurities().isEmpty());
        assertEquals("INSUFFICIENT_EVIDENCE", empty.decisionCode());
        assertEquals(BigDecimal.ZERO, empty.confidence());
        assertEquals(BigDecimal.ZERO, empty.suggestedGrossExposure());
        assertTrue(empty.limitations().contains(
                "NO_SECURITY_PASSED_SELECTION_THRESHOLD"));
    }

    @Test
    void intradayElapsedPaperOpenIsTheExactM4RequestViolation() {
        LocalDate anchor = LocalDate.of(2026, 8, 14);
        Instant researchAsOf = Instant.parse("2026-08-17T02:22:19Z");
        Instant elapsedOpen = StrategyResearchModels.openInstant(
                LocalDate.of(2026, 8, 17));
        List<Security> securities = ResearchUniverseV1.securities().stream()
                .limit(10).toList();
        List<StrategySpec> strategies = List.of(
                StrategySpec.of("BUY_AND_HOLD_V1"),
                StrategySpec.of("MEAN_REVERSION_V1"));

        IllegalArgumentException legacy = assertThrows(
                IllegalArgumentException.class, () -> new ShadowResearchModels
                        .ShadowRequest(
                        ShadowResearchModels.TriggerMode.ON_DEMAND_SELECTION,
                        anchor, LocalDate.of(2026, 5, 1), researchAsOf,
                        securities, securities.get(0), strategies,
                        elapsedOpen, 0, "current-as-of research",
                        "ON_DEMAND_A1B2C3D4E5F6",
                        ShadowResearchModels.SELECTION_STRATEGY_VERSION));
        assertEquals("M4_SHADOW_REQUEST_INVALID", legacy.getMessage());
        IllegalStateException precise = assertThrows(
                IllegalStateException.class, () ->
                        ResearchSelectionDeepResearchService
                                .validatePaperExecution(elapsedOpen,
                                        researchAsOf));
        assertEquals("RESEARCH_SELECTION_PAPER_EXECUTION_NOT_AFTER_AS_OF",
                precise.getMessage());

        Instant nextDayOpen = StrategyResearchModels.openInstant(
                LocalDate.of(2026, 8, 18));
        assertEquals(nextDayOpen, ResearchSelectionDeepResearchService
                .validatePaperExecution(nextDayOpen, researchAsOf));
        new ShadowResearchModels.ShadowRequest(
                ShadowResearchModels.TriggerMode.ON_DEMAND_SELECTION,
                anchor, LocalDate.of(2026, 5, 1), researchAsOf, securities,
                securities.get(0), strategies, nextDayOpen, 0,
                "current-as-of research", "ON_DEMAND_A1B2C3D4E5F6",
                ShadowResearchModels.SELECTION_STRATEGY_VERSION);
        assertEquals("DATA", ResearchSelectionFailureCategory.from(
                "RESEARCH_SELECTION_PAPER_EXECUTION_NOT_AFTER_AS_OF"));
    }

    @Test
    void mainboardSelectionBudgetDoesNotRelaxOrdinaryShadowBudget() {
        LocalDate anchor = LocalDate.of(2026, 8, 14);
        Instant researchAsOf = Instant.parse("2026-08-17T08:00:00Z");
        List<Security> securities = ResearchUniverseV1.securities().stream()
                .limit(10).toList();
        List<StrategySpec> strategies = List.of(
                StrategySpec.of("BUY_AND_HOLD_V1"),
                StrategySpec.of("MEAN_REVERSION_V1"));

        new ShadowResearchModels.ShadowRequest(
                ShadowResearchModels.TriggerMode.ON_DEMAND_SELECTION,
                anchor, LocalDate.of(2026, 5, 1), researchAsOf, securities,
                securities.get(0), strategies, null, 123,
                "full mainboard current-as-of research",
                "ON_DEMAND_A1B2C3D4E5F6",
                ShadowResearchModels.SELECTION_STRATEGY_VERSION);

        IllegalArgumentException ordinary = assertThrows(
                IllegalArgumentException.class, () ->
                        new ShadowResearchModels.ShadowRequest(
                                ShadowResearchModels.TriggerMode.SCHEDULED,
                                anchor, LocalDate.of(2026, 5, 1),
                                researchAsOf, securities, securities.get(0),
                                strategies, null, 53, "ordinary shadow",
                                ShadowResearchModels.RESEARCH_SLOT,
                                ShadowResearchModels.STRATEGY_VERSION));
        assertEquals("M4_SHADOW_REQUEST_INVALID", ordinary.getMessage());
    }

    @Test
    void mainboardSubsetFingerprintIsIndependentOfCollectionOrder() {
        List<Security> securities = ResearchUniverseV1.securities().stream()
                .limit(3).toList();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 14));
        List<Security> reversedSecurities = new ArrayList<>(securities);
        java.util.Collections.reverse(reversedSecurities);
        List<LocalDate> reversedDates = new ArrayList<>(dates);
        java.util.Collections.reverse(reversedDates);

        assertEquals(ResearchSelectionEngine.subsetDatasetVersion(
                        "SOURCE_V1", securities, dates, "TOP200_HISTORY"),
                ResearchSelectionEngine.subsetDatasetVersion(
                        "SOURCE_V1", reversedSecurities, reversedDates,
                        "TOP200_HISTORY"));
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
                        .add(day % 2 == 0 ? new BigDecimal("0.03")
                                : new BigDecimal("-0.02"));
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
                "RESEARCH_SELECTION_TEST_DATASET",
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, cutoff, calendar,
                bars);
    }

    private static List<LocalDate> openDates(LocalDate start, int count) {
        LinkedHashSet<LocalDate> result = new LinkedHashSet<>();
        LocalDate value = start;
        while (result.size() < count) {
            if (value.getDayOfWeek().getValue() <= 5) {
                result.add(value);
            }
            value = value.plusDays(1);
        }
        return List.copyOf(result);
    }
}
