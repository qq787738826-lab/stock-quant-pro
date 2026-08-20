package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperFill;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrderStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowExecutionResult;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.Side;
import com.stockquant.server.researchselection.ResearchSelectionModels.Candidate;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalGrade;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalResearch;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalStability;
import com.stockquant.server.researchselection.ResearchSelectionModels.ResearchTradePlan;
import com.stockquant.server.researchselection.ResearchSelectionModels.ResearchTradePlanStatus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic research-only entry/exit plan; never submits an order. */
public final class ResearchTradePlanService {
    public static final String CALCULATION_VERSION =
            "RESEARCH_TRADE_PLAN_CALC_V1";
    private static final MathContext MC = new MathContext(18,
            RoundingMode.HALF_EVEN);
    private static final BigDecimal MINIMUM_ENTRY_BAND =
            new BigDecimal("0.005");
    private static final BigDecimal MAXIMUM_ENTRY_BAND =
            new BigDecimal("0.020");
    private static final BigDecimal MINIMUM_RISK = new BigDecimal("0.04");
    private static final BigDecimal MAXIMUM_RISK = new BigDecimal("0.10");
    private static final BigDecimal RISK_REWARD = new BigDecimal("2.0");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public List<ResearchTradePlan> create(
            List<Candidate> candidates,
            HistoricalResearch historical,
            Map<Security, List<PriceBar>> pricesBySecurity,
            ResearchDataset qfqDataset,
            ShadowExecutionResult shadow
    ) {
        Objects.requireNonNull(shadow, "shadow");
        return create(candidates, historical, pricesBySecurity, qfqDataset,
                shadow.run().tradeDate(), shadow.run().paperExecutionTime(),
                shadow.fills().stream().filter(value ->
                        value.runId() == shadow.run().id()).toList());
    }

    List<ResearchTradePlan> create(
            List<Candidate> candidates,
            HistoricalResearch historical,
            Map<Security, List<PriceBar>> pricesBySecurity,
            ResearchDataset qfqDataset,
            LocalDate anchorTradeDate,
            Instant paperExecutionTime,
            List<PaperFill> fills
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(historical, "historical");
        Objects.requireNonNull(pricesBySecurity, "pricesBySecurity");
        Objects.requireNonNull(qfqDataset, "qfqDataset");
        Objects.requireNonNull(anchorTradeDate, "anchorTradeDate");
        fills = fills == null ? List.of() : List.copyOf(fills);
        Map<Security, HistoricalStability> stability = historical.securities()
                .stream().collect(java.util.stream.Collectors
                        .toUnmodifiableMap(HistoricalStability::security,
                                value -> value));
        List<ResearchTradePlan> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            List<PriceBar> prices = pricesBySecurity.getOrDefault(
                    candidate.security(), List.of()).stream().sorted(
                    Comparator.comparing(PriceBar::tradeDate)).toList();
            String fingerprint = sourceFingerprint(candidate.security(),
                    prices);
            HistoricalStability history = stability.get(candidate.security());
            if (history == null || history.grade() == HistoricalGrade.C
                    || !"WATCH".equals(candidate.recommendation().name())
                    || candidate.preferredStrategy() == null
                    || "NONE".equals(candidate.preferredStrategy())) {
                result.add(observationOnly(candidate,
                        anchorTradeDate, fingerprint,
                        history == null ? "HISTORICAL_EVIDENCE_MISSING"
                                : "GRADE_OR_EVIDENCE_NOT_EXECUTABLE"));
                continue;
            }
            if (prices.size() < 15) {
                throw new IllegalStateException(
                        "RESEARCH_TRADE_PLAN_ATR_WINDOW_INCOMPLETE");
            }
            LocalDate anchor = prices.get(prices.size() - 1).tradeDate();
            if (!anchor.equals(anchorTradeDate)) {
                throw new IllegalStateException(
                        "RESEARCH_TRADE_PLAN_ANCHOR_MISMATCH");
            }
            BigDecimal rawClose = prices.get(prices.size() - 1).close();
            BigDecimal qfqClose = qfqDataset.barsBySecurity()
                    .getOrDefault(candidate.security(), List.of()).stream()
                    .filter(value -> value.tradeDate().equals(anchor))
                    .map(value -> value.close()).findFirst().orElseThrow(() ->
                            new IllegalStateException(
                                    "RESEARCH_TRADE_PLAN_QFQ_CLOSE_MISSING"));
            BigDecimal atr = atr14(prices);
            BigDecimal band = clamp(atr.multiply(new BigDecimal("0.5"), MC)
                    .divide(rawClose, MC), MINIMUM_ENTRY_BAND,
                    MAXIMUM_ENTRY_BAND);
            BigDecimal lower = price(rawClose.multiply(
                    BigDecimal.ONE.subtract(band), MC));
            BigDecimal upper = price(rawClose.multiply(
                    BigDecimal.ONE.add(band), MC));
            RiskPrices risk = risk(rawClose, atr);
            HoldingRule holding = holding(candidate.preferredStrategy());
            Instant execution = paperExecutionTime;
            ResearchTradePlanStatus status = execution == null
                    ? ResearchTradePlanStatus.SKIPPED
                    : ResearchTradePlanStatus.PLANNED;
            String reason = execution == null
                    ? "NEXT_LEGAL_OPEN_NOT_KNOWN_AS_OF" : null;
            ResearchTradePlan plan = new ResearchTradePlan(
                    ResearchSelectionModels.RESEARCH_TRADE_PLAN_VERSION,
                    candidate.security(), anchor, price(rawClose),
                    price(qfqClose), price(atr), ratio(band), lower, upper,
                    upper, execution == null ? null
                    : execution.atZone(SHANGHAI).toLocalDate(),
                    execution == null ? null
                            : execution.atZone(SHANGHAI).toLocalTime(),
                    risk.stopLoss(), risk.target(), risk.amount(),
                    RISK_REWARD, candidate.preferredStrategy(),
                    holding.expectedMinimum(), holding.expectedMaximum(),
                    holding.maximum(), holding.invalidationRule(),
                    exitConditions(holding), status, reason, null, null,
                    null, null, null, CALCULATION_VERSION, fingerprint);
            result.add(applyFills(plan, fills));
        }
        return List.copyOf(result);
    }

    public static BigDecimal atr14(List<PriceBar> input) {
        List<PriceBar> prices = input.stream().sorted(
                Comparator.comparing(PriceBar::tradeDate)).toList();
        if (prices.size() < 15) {
            throw new IllegalArgumentException(
                    "RESEARCH_TRADE_PLAN_ATR_WINDOW_INCOMPLETE");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int index = prices.size() - 14; index < prices.size(); index++) {
            PriceBar current = prices.get(index);
            BigDecimal previousClose = prices.get(index - 1).close();
            BigDecimal trueRange = current.high().subtract(current.low())
                    .max(current.high().subtract(previousClose).abs())
                    .max(current.low().subtract(previousClose).abs());
            total = total.add(trueRange, MC);
        }
        return total.divide(BigDecimal.valueOf(14), MC);
    }

    public EntryAdmission admitEntry(
            ResearchTradePlan plan,
            BigDecimal actualOpen,
            boolean tradable,
            boolean dataQualityPassed,
            String currentSourceFingerprint
    ) {
        Objects.requireNonNull(plan, "plan");
        if (plan.planStatus() != ResearchTradePlanStatus.PLANNED) {
            return new EntryAdmission(false, plan.planStatus(),
                    "PLAN_NOT_EXECUTABLE");
        }
        if (!plan.sourceFingerprint().equals(currentSourceFingerprint)) {
            return new EntryAdmission(false,
                    ResearchTradePlanStatus.INVALIDATED,
                    "SOURCE_OR_ADJUSTMENT_FACTOR_CHANGED");
        }
        if (!tradable) {
            return new EntryAdmission(false, ResearchTradePlanStatus.SKIPPED,
                    "SUSPENDED_OR_NO_TRADE");
        }
        if (!dataQualityPassed) {
            return new EntryAdmission(false, ResearchTradePlanStatus.SKIPPED,
                    "DATA_QUALITY_GATE_FAILED");
        }
        if (actualOpen == null
                || actualOpen.compareTo(plan.plannedEntryLower()) < 0
                || actualOpen.compareTo(
                plan.maximumAcceptableEntryPrice()) > 0) {
            return new EntryAdmission(false, ResearchTradePlanStatus.SKIPPED,
                    "OPEN_OUTSIDE_PLANNED_ENTRY_BAND");
        }
        return new EntryAdmission(true, ResearchTradePlanStatus.ENTERED,
                "ENTRY_ADMITTED_TO_EXISTING_PAPER_ENGINE");
    }

    public ResearchTradePlan applyFills(
            ResearchTradePlan plan,
            List<PaperFill> fills
    ) {
        return applyFills(plan, fills, List.of());
    }

    public ResearchTradePlan applyFills(
            ResearchTradePlan plan,
            List<PaperFill> fills,
            List<LocalDate> tradingSessions
    ) {
        List<PaperFill> relevant = fills.stream().filter(value ->
                value.runId() >= 0
                        && value.security().equals(plan.security())).sorted(
                Comparator.comparing(PaperFill::executionTime)).toList();
        PaperFill entry = relevant.stream().filter(value ->
                value.side() == Side.BUY).findFirst().orElse(null);
        PaperFill exit = relevant.stream().filter(value ->
                value.side() == Side.SELL).reduce((left, right) -> right)
                .orElse(null);
        if (entry == null) return plan;
        RiskPrices risk = risk(entry.executionPrice(), plan.atr14());
        BigDecimal fees = relevant.stream().map(value -> value.commission()
                        .add(value.stampDuty()).add(value.slippageCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal realizedPnl = relevant.stream().filter(value ->
                        value.side() == Side.SELL)
                .map(PaperFill::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Integer holding = exit == null || tradingSessions.isEmpty()
                ? null : Math.toIntExact(tradingSessions.stream().filter(
                date -> date.isAfter(entry.executionDate())
                        && !date.isAfter(exit.executionDate())).count());
        return copy(plan, exit == null ? ResearchTradePlanStatus.ENTERED
                        : ResearchTradePlanStatus.CLOSED,
                exit == null ? "PAPER_ENTRY_RECORDED"
                        : "PAPER_EXIT_RECORDED",
                risk, entry.executionPrice(),
                exit == null ? null : exit.executionPrice(), holding,
                money(fees), exit == null ? null : money(realizedPnl));
    }

    public ResearchTradePlan applyOrderStatus(
            ResearchTradePlan plan,
            List<PaperOrder> orders
    ) {
        PaperOrder entry = orders.stream().filter(value ->
                        value.side() == Side.BUY
                                && value.security().equals(plan.security()))
                .findFirst().orElse(null);
        if (entry == null || entry.status() != PaperOrderStatus.REJECTED) {
            return plan;
        }
        String reason = entry.rejectionReason() == null
                ? "PAPER_ENTRY_REJECTED" : entry.rejectionReason();
        ResearchTradePlanStatus status = reason.contains("SOURCE")
                || reason.contains("ADJUSTMENT_FACTOR")
                ? ResearchTradePlanStatus.INVALIDATED
                : ResearchTradePlanStatus.SKIPPED;
        return copy(plan, status, reason, null,
                plan.actualPaperEntryPrice(), plan.actualPaperExitPrice(),
                plan.actualHoldingSessions(), plan.actualFees(),
                plan.actualPnl());
    }

    public ExitIntent evaluateExit(
            ResearchTradePlan plan,
            int holdingSessions,
            BigDecimal sessionHigh,
            BigDecimal sessionLow,
            boolean strategySignalValid,
            boolean riskAndDataQualityPassed
    ) {
        if (plan.actualPaperEntryPrice() == null || holdingSessions <= 0) {
            return new ExitIntent(false, "T_PLUS_ONE_OR_ENTRY_NOT_READY");
        }
        if (!riskAndDataQualityPassed) {
            return new ExitIntent(true, "RISK_OR_DATA_QUALITY_GATE_FAILED");
        }
        if (sessionLow.compareTo(plan.stopLossPrice()) <= 0) {
            return new ExitIntent(true, "STOP_LOSS_TOUCHED");
        }
        if (sessionHigh.compareTo(plan.targetExitPrice()) >= 0) {
            return new ExitIntent(true, "TARGET_PRICE_TOUCHED");
        }
        if (!strategySignalValid) {
            return new ExitIntent(true, "STRATEGY_SIGNAL_INVALIDATED");
        }
        if (holdingSessions >= plan.maximumHoldingSessions()) {
            return new ExitIntent(true, "MAXIMUM_HOLDING_REACHED");
        }
        return new ExitIntent(false, "HOLD");
    }

    public ResearchTradePlan invalidateIfSourceChanged(
            ResearchTradePlan plan,
            String currentSourceFingerprint
    ) {
        if (plan.sourceFingerprint().equals(currentSourceFingerprint)) {
            return plan;
        }
        return copy(plan, ResearchTradePlanStatus.INVALIDATED,
                "SOURCE_OR_ADJUSTMENT_FACTOR_CHANGED", null,
                plan.actualPaperEntryPrice(), plan.actualPaperExitPrice(),
                plan.actualHoldingSessions(), plan.actualFees(),
                plan.actualPnl());
    }

    static String sourceFingerprint(
            Security security,
            List<PriceBar> prices
    ) {
        StringBuilder value = new StringBuilder(
                security.canonicalCode());
        prices.stream().sorted(Comparator.comparing(PriceBar::tradeDate))
                .forEach(bar -> value.append('|').append(bar.tradeDate())
                        .append('|').append(bar.open())
                        .append('|').append(bar.high())
                        .append('|').append(bar.low())
                        .append('|').append(bar.close())
                        .append('|').append(bar.adjustmentFactor())
                        .append('|').append(bar.rawContentHash())
                        .append('|').append(bar.factorContentHash()));
        return BacktestCanonicalHashService.sha256(value.toString());
    }

    private static ResearchTradePlan observationOnly(
            Candidate candidate,
            LocalDate anchor,
            String fingerprint,
            String reason
    ) {
        return new ResearchTradePlan(
                ResearchSelectionModels.RESEARCH_TRADE_PLAN_VERSION,
                candidate.security(), anchor, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, candidate.preferredStrategy(), null, null, null,
                "仅观察，不生成研究买卖计划", List.of(),
                ResearchTradePlanStatus.OBSERVATION_ONLY, reason, null, null,
                null, null, null, CALCULATION_VERSION, fingerprint);
    }

    private static RiskPrices risk(
            BigDecimal entryReference,
            BigDecimal atr
    ) {
        BigDecimal riskPercent = clamp(atr.multiply(
                        new BigDecimal("1.5"), MC)
                .divide(entryReference, MC), MINIMUM_RISK, MAXIMUM_RISK);
        BigDecimal amount = price(entryReference.multiply(riskPercent, MC));
        return new RiskPrices(amount,
                price(entryReference.subtract(amount)),
                price(entryReference.add(amount.multiply(RISK_REWARD, MC))));
    }

    private static HoldingRule holding(String strategy) {
        return switch (strategy) {
            case StrategyRegistry.MEAN_REVERSION -> new HoldingRule(5, 10,
                    10, "Z值回到0时提前退出");
            case StrategyRegistry.MOVING_AVERAGE_MOMENTUM ->
                    new HoldingRule(10, 20, 20,
                            "收盘跌破MA20时提前退出");
            case StrategyRegistry.CROSS_SECTIONAL_MOMENTUM ->
                    new HoldingRule(5, 20, 20,
                            "每5个交易日复核，跌出强势排名时退出");
            case StrategyRegistry.BUY_AND_HOLD -> new HoldingRule(20, 60,
                    60, "每20个交易日复核，风险门禁失效时退出");
            default -> throw new IllegalArgumentException(
                    "RESEARCH_TRADE_PLAN_STRATEGY_UNSUPPORTED");
        };
    }

    private static List<String> exitConditions(HoldingRule holding) {
        return List.of("触及止损价", "触及目标价", holding.invalidationRule(),
                "达到最长持有期限", "风险或数据质量门禁失效", "严格遵守T+1");
    }

    private static BigDecimal clamp(
            BigDecimal value,
            BigDecimal minimum,
            BigDecimal maximum
    ) {
        return value.max(minimum).min(maximum);
    }

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_EVEN);
    }

    private static ResearchTradePlan copy(
            ResearchTradePlan source,
            ResearchTradePlanStatus status,
            String reason,
            RiskPrices risk,
            BigDecimal actualEntry,
            BigDecimal actualExit,
            Integer holding,
            BigDecimal fees,
            BigDecimal pnl
    ) {
        return new ResearchTradePlan(source.version(), source.security(),
                source.anchorTradeDate(), source.rawReferenceClose(),
                source.qfqReferenceClose(), source.atr14(),
                source.entryBandPercent(), source.plannedEntryLower(),
                source.plannedEntryUpper(),
                source.maximumAcceptableEntryPrice(),
                source.plannedExecutionDate(), source.plannedExecutionTime(),
                risk == null ? source.stopLossPrice() : risk.stopLoss(),
                risk == null ? source.targetExitPrice() : risk.target(),
                risk == null ? source.riskAmountPerShare() : risk.amount(),
                source.riskRewardRatio(), source.preferredStrategy(),
                source.expectedHoldingMinSessions(),
                source.expectedHoldingMaxSessions(),
                source.maximumHoldingSessions(),
                source.strategyInvalidationRule(), source.exitConditions(),
                status, reason, actualEntry, actualExit, holding, fees, pnl,
                source.calculationVersion(), source.sourceFingerprint());
    }

    public record PriceBar(
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal adjustmentFactor,
            String rawContentHash,
            String factorContentHash
    ) {
        public PriceBar {
            Objects.requireNonNull(tradeDate, "tradeDate");
            List.of(open, high, low, close, adjustmentFactor).forEach(value -> {
                if (value == null || value.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "RESEARCH_TRADE_PLAN_PRICE_BAR_INVALID");
                }
            });
            if (high.compareTo(low) < 0 || high.compareTo(open) < 0
                    || high.compareTo(close) < 0
                    || low.compareTo(open) > 0
                    || low.compareTo(close) > 0
                    || rawContentHash == null
                    || factorContentHash == null) {
                throw new IllegalArgumentException(
                        "RESEARCH_TRADE_PLAN_PRICE_BAR_INVALID");
            }
        }
    }

    public record EntryAdmission(
            boolean admitted,
            ResearchTradePlanStatus status,
            String reason
    ) {
    }

    public record ExitIntent(boolean exit, String reason) {
    }

    private record HoldingRule(
            int expectedMinimum,
            int expectedMaximum,
            int maximum,
            String invalidationRule
    ) {
    }

    private record RiskPrices(
            BigDecimal amount,
            BigDecimal stopLoss,
            BigDecimal target
    ) {
    }
}
