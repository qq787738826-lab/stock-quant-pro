package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperFill;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrderStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.Side;
import com.stockquant.server.researchselection.ResearchSelectionModels.Candidate;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalGrade;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalResearch;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalStability;
import com.stockquant.server.researchselection.ResearchSelectionModels.RecommendationStatus;
import com.stockquant.server.researchselection.ResearchSelectionModels.ResearchTradePlanStatus;
import com.stockquant.server.researchselection.ResearchTradePlanService.PriceBar;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchSelectionTradePlanTest {

    @Test
    void atrEntryRiskAndFourStrategyHoldingRulesAreDeterministic() {
        Fixture fixture = fixture(HistoricalGrade.B);
        var plans = new ResearchTradePlanService().create(
                fixture.candidates(), fixture.history(), fixture.prices(),
                fixture.dataset(), fixture.anchor(), fixture.execution(),
                List.of());

        assertEquals(4, plans.size());
        assertTrue(plans.stream().allMatch(value ->
                value.planStatus() == ResearchTradePlanStatus.PLANNED
                        && value.atr14().compareTo(
                        new BigDecimal("0.2000")) == 0
                        && value.entryBandPercent().compareTo(
                        new BigDecimal("0.00877193")) == 0
                        && value.riskRewardRatio().compareTo(
                        new BigDecimal("2.0")) == 0
                        && value.plannedExecutionDate().equals(
                        fixture.execution().atZone(java.time.ZoneId.of(
                                "Asia/Shanghai")).toLocalDate())));
        Map<String, List<Integer>> holding = new LinkedHashMap<>();
        plans.forEach(value -> holding.put(value.preferredStrategy(),
                List.of(value.expectedHoldingMinSessions(),
                        value.expectedHoldingMaxSessions(),
                        value.maximumHoldingSessions())));
        assertEquals(List.of(5, 10, 10), holding.get(
                StrategyRegistry.MEAN_REVERSION));
        assertEquals(List.of(10, 20, 20), holding.get(
                StrategyRegistry.MOVING_AVERAGE_MOMENTUM));
        assertEquals(List.of(5, 20, 20), holding.get(
                StrategyRegistry.CROSS_SECTIONAL_MOMENTUM));
        assertEquals(List.of(20, 60, 60), holding.get(
                StrategyRegistry.BUY_AND_HOLD));
        var first = plans.get(0);
        assertEquals(first.plannedEntryUpper(),
                first.maximumAcceptableEntryPrice());
        assertEquals(new BigDecimal("10.9440"), first.stopLossPrice());
        assertEquals(new BigDecimal("12.3120"), first.targetExitPrice());
    }

    @Test
    void entryAdmissionRebaseTPlusOneAndSourceChangeFailClosed() {
        Fixture fixture = fixture(HistoricalGrade.B);
        var service = new ResearchTradePlanService();
        var plan = service.create(fixture.candidates().subList(0, 1),
                fixture.historyForFirst(), Map.of(fixture.securities().get(0),
                        fixture.prices().get(fixture.securities().get(0))),
                singleDataset(fixture, fixture.securities().get(0)),
                fixture.anchor(), fixture.execution(), List.of()).get(0);

        var outside = service.admitEntry(plan,
                plan.maximumAcceptableEntryPrice().add(BigDecimal.ONE), true,
                true, plan.sourceFingerprint());
        assertFalse(outside.admitted());
        assertEquals(ResearchTradePlanStatus.SKIPPED, outside.status());
        assertEquals("OPEN_OUTSIDE_PLANNED_ENTRY_BAND", outside.reason());
        assertTrue(service.admitEntry(plan, plan.rawReferenceClose(), true,
                true, plan.sourceFingerprint()).admitted());

        BigDecimal actual = new BigDecimal("11.4500");
        PaperFill fill = new PaperFill(1, 1, 9,
                fixture.execution().atZone(java.time.ZoneId.of(
                        "Asia/Shanghai")).toLocalDate(),
                fixture.execution(), fixture.securities().get(0), Side.BUY,
                actual, actual, 100, new BigDecimal("1145"),
                new BigDecimal("5"), BigDecimal.ZERO,
                new BigDecimal("1"), BigDecimal.ZERO,
                new BigDecimal("998849"), 100, "f".repeat(64));
        var entered = service.applyFills(plan, List.of(fill));
        assertEquals(ResearchTradePlanStatus.ENTERED,
                entered.planStatus());
        assertEquals(actual, entered.actualPaperEntryPrice());
        assertEquals(new BigDecimal("10.9920"), entered.stopLossPrice());
        assertEquals(new BigDecimal("12.3660"), entered.targetExitPrice());
        assertEquals(new BigDecimal("6.00000000"), entered.actualFees());
        assertFalse(service.evaluateExit(entered, 0,
                new BigDecimal("20"), new BigDecimal("1"), false,
                false).exit());
        assertEquals("STOP_LOSS_TOUCHED", service.evaluateExit(entered, 1,
                new BigDecimal("12"), entered.stopLossPrice(), true,
                true).reason());
        assertEquals("TARGET_PRICE_TOUCHED", service.evaluateExit(entered, 1,
                entered.targetExitPrice(), new BigDecimal("11"), true,
                true).reason());
        assertEquals("STRATEGY_SIGNAL_INVALIDATED", service.evaluateExit(
                entered, 1, new BigDecimal("12"), new BigDecimal("11"),
                false, true).reason());
        assertEquals("MAXIMUM_HOLDING_REACHED", service.evaluateExit(entered,
                entered.maximumHoldingSessions(), new BigDecimal("12"),
                new BigDecimal("11"), true, true).reason());
        assertEquals("RISK_OR_DATA_QUALITY_GATE_FAILED", service.evaluateExit(
                entered, 1, new BigDecimal("12"), new BigDecimal("11"),
                true, false).reason());
        List<PriceBar> revised = new ArrayList<>(fixture.prices().get(
                fixture.securities().get(0)));
        PriceBar original = revised.get(revised.size() - 1);
        revised.set(revised.size() - 1, new PriceBar(original.tradeDate(),
                original.open(), original.high(), original.low(),
                original.close(), new BigDecimal("1.1000"),
                original.rawContentHash(), "e".repeat(64)));
        String revisedFingerprint = ResearchTradePlanService
                .sourceFingerprint(fixture.securities().get(0), revised);
        var invalidated = service.invalidateIfSourceChanged(entered,
                revisedFingerprint);
        assertEquals(ResearchTradePlanStatus.INVALIDATED,
                invalidated.planStatus());
    }

    @Test
    void cGradeAndLegacyResultNeverInventExecutablePlanFields()
            throws Exception {
        Fixture fixture = fixture(HistoricalGrade.C);
        var plan = new ResearchTradePlanService().create(
                fixture.candidates().subList(0, 1),
                fixture.historyForFirst(),
                Map.of(fixture.securities().get(0), fixture.prices().get(
                        fixture.securities().get(0))),
                singleDataset(fixture, fixture.securities().get(0)),
                fixture.anchor(), fixture.execution(), List.of()).get(0);
        assertEquals(ResearchTradePlanStatus.OBSERVATION_ONLY,
                plan.planStatus());
        assertNull(plan.plannedEntryLower());
        assertNull(plan.stopLossPrice());

        String legacy = """
                {"contractVersion":"RESEARCH_SELECTION_V1","runId":1,
                 "publicRunId":"SELECT_20260820T000000Z_ABCDEF123456",
                 "status":"COMPLETED","triggerMode":"ON_DEMAND",
                 "researchAsOf":"2026-08-20T08:00:00Z",
                 "anchorTradeDate":"2026-08-19","ranking":[],
                 "shortlist":[],"candidates":[],"emptyResult":true,
                 "decisionCode":"INSUFFICIENT_EVIDENCE",
                 "paperEnabled":true,"realTradingEnabled":false,
                 "historicalLiveShadow":false,
                 "startedAt":"2026-08-20T08:00:00Z",
                 "completedAt":"2026-08-20T08:00:01Z"}
                """;
        var parsed = new ObjectMapper().findAndRegisterModules().readValue(
                legacy, ResearchSelectionModels.SelectionResult.class);
        assertTrue(parsed.selectionExplanations().isEmpty());
        assertTrue(parsed.researchTradePlans().isEmpty());
    }

    @Test
    void persistedSelectionPlanGuardsExistingPaperEntryWithoutChangingLegacy()
            throws Exception {
        Fixture fixture = fixture(HistoricalGrade.B);
        var plan = new ResearchTradePlanService().create(
                fixture.candidates().subList(0, 1),
                fixture.historyForFirst(),
                Map.of(fixture.securities().get(0), fixture.prices().get(
                        fixture.securities().get(0))),
                singleDataset(fixture, fixture.securities().get(0)),
                fixture.anchor(), fixture.execution(), List.of()).get(0);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PaperOrder order = new PaperOrder(1, 9, 1, "ORDER_1", Side.BUY,
                fixture.securities().get(0),
                StrategyResearchModels.closeInstant(fixture.anchor()),
                fixture.execution(), BigDecimal.ONE,
                PaperOrderStatus.PENDING, null);
        var guard = new ResearchSelectionPaperEntryGuard(
                new StubJdbcTemplate(List.of(
                        mapper.writeValueAsString(plan))), mapper,
                ignored -> plan.sourceFingerprint());

        assertEquals("OPEN_OUTSIDE_PLANNED_ENTRY_BAND",
                guard.rejectionReason(order, plan.plannedExecutionDate(),
                        plan.maximumAcceptableEntryPrice().add(
                                BigDecimal.ONE)).orElseThrow());
        assertTrue(guard.rejectionReason(order, plan.plannedExecutionDate(),
                plan.rawReferenceClose()).isEmpty());
        assertTrue(new ResearchSelectionPaperEntryGuard(
                new StubJdbcTemplate(List.of()), mapper,
                ignored -> plan.sourceFingerprint()).rejectionReason(
                order, plan.plannedExecutionDate(),
                plan.rawReferenceClose()).isEmpty());
        assertEquals("SOURCE_OR_ADJUSTMENT_FACTOR_CHANGED",
                new ResearchSelectionPaperEntryGuard(
                        new StubJdbcTemplate(List.of(
                                mapper.writeValueAsString(plan))), mapper,
                        ignored -> "f".repeat(64)).rejectionReason(order,
                        plan.plannedExecutionDate(),
                        plan.rawReferenceClose()).orElseThrow());

        PaperOrder rejected = new PaperOrder(order.id(), order.runId(),
                order.portfolioId(), order.orderKey(), order.side(),
                order.security(), order.signalTime(),
                order.earliestExecutionTime(), order.targetWeight(),
                PaperOrderStatus.REJECTED,
                "SOURCE_OR_ADJUSTMENT_FACTOR_CHANGED");
        var visible = new ResearchTradePlanService().applyOrderStatus(plan,
                List.of(rejected));
        assertEquals(ResearchTradePlanStatus.INVALIDATED,
                visible.planStatus());
        assertEquals("SOURCE_OR_ADJUSTMENT_FACTOR_CHANGED",
                visible.statusReason());
    }

    private static Fixture fixture(HistoricalGrade grade) {
        List<LocalDate> dates = openDates(LocalDate.of(2026, 8, 3), 15);
        LocalDate anchor = dates.get(dates.size() - 1);
        List<Security> securities = List.of(new Security("600000", "SSE"),
                new Security("600519", "SSE"),
                new Security("000001", "SZSE"),
                new Security("000002", "SZSE"));
        List<String> strategies = List.of(StrategyRegistry.MEAN_REVERSION,
                StrategyRegistry.MOVING_AVERAGE_MOMENTUM,
                StrategyRegistry.CROSS_SECTIONAL_MOMENTUM,
                StrategyRegistry.BUY_AND_HOLD);
        List<Candidate> candidates = new ArrayList<>();
        List<HistoricalStability> histories = new ArrayList<>();
        Map<Security, List<PriceBar>> prices = new LinkedHashMap<>();
        List<DailyBar> qfq = new ArrayList<>();
        for (int securityIndex = 0; securityIndex < securities.size();
                securityIndex++) {
            Security security = securities.get(securityIndex);
            candidates.add(candidate(security, strategies.get(securityIndex),
                    securityIndex + 1));
            histories.add(history(security, grade));
            List<PriceBar> source = new ArrayList<>();
            for (int index = 0; index < dates.size(); index++) {
                BigDecimal close = new BigDecimal("10.0000").add(
                        new BigDecimal("0.1000").multiply(
                                BigDecimal.valueOf(index)));
                LocalDate date = dates.get(index);
                source.add(new PriceBar(date, close,
                        close.add(new BigDecimal("0.1000")),
                        close.subtract(new BigDecimal("0.1000")), close,
                        BigDecimal.ONE, "a".repeat(64), "b".repeat(64)));
                Instant known = StrategyResearchModels.closeInstant(date)
                        .plusSeconds(60);
                qfq.add(new DailyBar(security, date, close,
                        close.add(new BigDecimal("0.1000")),
                        close.subtract(new BigDecimal("0.1000")), close,
                        1_000_000, true,
                        StrategyResearchModels.closeInstant(date), known));
            }
            prices.put(security, List.copyOf(source));
        }
        Instant cutoff = StrategyResearchModels.closeInstant(anchor)
                .plusSeconds(120);
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT,
                "TRADE_PLAN_TEST_DATASET", KnowledgeMode
                .SYSTEM_KNOWLEDGE_RESEARCH, cutoff,
                dates.stream().map(value -> new TradingSession(value,
                        Set.of("SSE", "SZSE"))).toList(), qfq);
        HistoricalResearch historical = new HistoricalResearch(
                ResearchSelectionModels.HISTORICAL_STABILITY_VERSION,
                "POST_HOC_RESEARCH", "PIT_PARTIAL", 60,
                dates.get(0), anchor, List.of(), List.of(), histories,
                Map.of("A", grade == HistoricalGrade.A ? 4 : 0,
                        "B", grade == HistoricalGrade.B ? 4 : 0,
                        "C", grade == HistoricalGrade.C ? 4 : 0),
                true, true, true, true, "c".repeat(64));
        Instant execution = StrategyResearchModels.openInstant(
                nextWeekday(anchor));
        return new Fixture(securities, candidates, historical, prices,
                dataset, anchor, execution);
    }

    private static Candidate candidate(
            Security security,
            String strategy,
            int rank
    ) {
        return new Candidate(rank, security, security.symbol(), "TEST",
                new BigDecimal("75.0000"), RecommendationStatus.WATCH,
                "MODERATE", new BigDecimal("0.65"), List.of("支持"),
                List.of(), strategy, List.of(), new BigDecimal("0.10"),
                "UPTREND", List.of());
    }

    private static HistoricalStability history(
            Security security,
            HistoricalGrade grade
    ) {
        return new HistoricalStability(security, 60,
                new BigDecimal("70.0000"), grade,
                new BigDecimal("60"), new BigDecimal("70"),
                new BigDecimal("65"), new BigDecimal("80"),
                new BigDecimal("75"), new BigDecimal("0.60"),
                new BigDecimal("0.70"), "CURRENT_20",
                new BigDecimal("0.10"), "ROLLING_20_1",
                new BigDecimal("-0.03"),
                new ResearchSelectionModels.WalkForwardSummary(true, null,
                        20, 10, 10, 3, 12, new BigDecimal("0.02"),
                        new BigDecimal("-0.01"), new BigDecimal("0.60"),
                        new BigDecimal("0.10"), 10, true, true),
                List.of(), 0, List.of(), List.of(), true);
    }

    private static ResearchDataset singleDataset(
            Fixture fixture,
            Security security
    ) {
        return new ResearchDataset(fixture.dataset().contractVersion(),
                fixture.dataset().datasetVersion() + "_SINGLE",
                fixture.dataset().knowledgeMode(),
                fixture.dataset().knowledgeCutoff(),
                fixture.dataset().sessions(), fixture.dataset().bars().stream()
                .filter(value -> value.security().equals(security)).toList());
    }

    private static List<LocalDate> openDates(LocalDate start, int count) {
        List<LocalDate> values = new ArrayList<>();
        LocalDate value = start;
        while (values.size() < count) {
            if (value.getDayOfWeek().getValue() <= 5) values.add(value);
            value = value.plusDays(1);
        }
        return List.copyOf(values);
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate value = date.plusDays(1);
        while (value.getDayOfWeek().getValue() > 5) value = value.plusDays(1);
        return value;
    }

    private record Fixture(
            List<Security> securities,
            List<Candidate> candidates,
            HistoricalResearch history,
            Map<Security, List<PriceBar>> prices,
            ResearchDataset dataset,
            LocalDate anchor,
            Instant execution
    ) {
        private HistoricalResearch historyForFirst() {
            HistoricalStability first = history.securities().get(0);
            return new HistoricalResearch(history.version(),
                    history.researchLabel(), history.pitQualification(),
                    history.availableSessions(), history.rangeStart(),
                    history.rangeEnd(), history.windowCoverage(),
                    history.missingTradeDates(), List.of(first),
                    Map.of("A", first.grade() == HistoricalGrade.A ? 1 : 0,
                            "B", first.grade() == HistoricalGrade.B ? 1 : 0,
                            "C", first.grade() == HistoricalGrade.C ? 1 : 0),
                    true, true, true, true, history.datasetFingerprint());
        }
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final List<String> rows;

        private StubJdbcTemplate(List<String> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(
                String sql,
                Class<T> elementType,
                Object... args
        ) {
            return (List<T>) rows;
        }
    }
}
