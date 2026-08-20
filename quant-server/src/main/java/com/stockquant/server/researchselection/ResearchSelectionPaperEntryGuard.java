package com.stockquant.server.researchselection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategyContext;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProvider;
import com.stockquant.server.agent.shadowresearch.ShadowPaperPortfolioService.PaperEntryGuard;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperPortfolio;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.researchselection.ResearchSelectionModels.ResearchTradePlan;
import com.stockquant.server.researchselection.ResearchTradePlanService.PriceBar;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Applies a persisted V1 plan around the existing M2 Paper engine. */
public final class ResearchSelectionPaperEntryGuard
        implements PaperEntryGuard {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final PitMarketFactRepository facts;
    private final ShadowResearchRepository shadows;
    private final ResearchTradePlanService plans =
            new ResearchTradePlanService();
    private final BiFunction<ResearchTradePlan, LocalDate, String>
            currentFingerprint;

    public ResearchSelectionPaperEntryGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this(jdbc, mapper, clock,
                fingerprintResolver(jdbc, mapper, clock));
    }

    ResearchSelectionPaperEntryGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Function<ResearchTradePlan, String> currentFingerprint
    ) {
        this(jdbc, mapper, Clock.systemUTC(),
                (plan, date) -> currentFingerprint.apply(plan));
    }

    private ResearchSelectionPaperEntryGuard(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock,
            BiFunction<ResearchTradePlan, LocalDate, String>
                    currentFingerprint
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.facts = new PitMarketFactRepository(this.jdbc, this.mapper);
        this.shadows = new ShadowResearchRepository(this.jdbc, this.mapper);
        this.currentFingerprint = Objects.requireNonNull(currentFingerprint,
                "currentFingerprint");
    }

    @Override
    public Optional<String> rejectionReason(
            PaperOrder order,
            LocalDate executionDate,
            BigDecimal referenceOpen
    ) {
        List<String> rows = jdbc.queryForList("""
                SELECT plan.value::text
                  FROM research_selection_runs selection,
                       LATERAL jsonb_array_elements(
                         COALESCE(selection.result_json->'researchTradePlans',
                                  '[]'::jsonb)
                       ) plan(value)
                 WHERE selection.shadow_run_id=?
                   AND plan.value->'security'->>'symbol'=?
                   AND plan.value->'security'->>'exchange'=?
                 ORDER BY selection.id DESC
                 LIMIT 2
                """, String.class, order.runId(), order.security().symbol(),
                order.security().exchange());
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) {
            return Optional.of("RESEARCH_TRADE_PLAN_BINDING_AMBIGUOUS");
        }
        ResearchTradePlan plan;
        try {
            plan = mapper.readValue(rows.get(0), ResearchTradePlan.class);
        } catch (JsonProcessingException error) {
            return Optional.of("RESEARCH_TRADE_PLAN_STORED_RESULT_INVALID");
        }
        if (plan.plannedExecutionDate() == null
                || !plan.plannedExecutionDate().equals(executionDate)) {
            return Optional.of("RESEARCH_TRADE_PLAN_EXECUTION_DATE_MISMATCH");
        }
        String fingerprint;
        try {
            fingerprint = currentFingerprint.apply(plan, executionDate);
        } catch (IllegalStateException error) {
            return Optional.of(safeReason(error));
        }
        var admission = plans.admitEntry(plan, referenceOpen, true, true,
                fingerprint);
        return admission.admitted() ? Optional.empty()
                : Optional.of(admission.reason());
    }

    @Override
    public Map<Security, String> exitReasons(
            ShadowRun run,
            ResearchDataset asOfDataset,
            PaperPortfolio portfolio
    ) {
        Map<Security, String> result = new LinkedHashMap<>();
        for (var position : portfolio.positions()) {
            try {
                Optional<BoundPlan> bound = activePlan(portfolio.id(),
                        position.security());
                if (bound.isEmpty()) continue;
                ResearchTradePlan stored = bound.orElseThrow().plan();
                List<com.stockquant.server.agent.shadowresearch
                        .ShadowResearchModels.PaperFill> lifecycle =
                        shadows.paperLifecycleFills(
                                bound.orElseThrow().shadowRunId());
                ResearchTradePlan actual = plans.applyFills(stored,
                        lifecycle, tradingSessions(asOfDataset,
                                run.tradeDate()));
                if (actual.actualPaperEntryPrice() == null) {
                    result.put(position.security(),
                            "RESEARCH_TRADE_PLAN_ENTRY_FILL_MISSING");
                    continue;
                }
                int holding = holdingSessions(actual, asOfDataset,
                        run.tradeDate());
                RawDailyBarObservation raw = rawAt(position.security(),
                        run.tradeDate());
                boolean sourceValid = currentFingerprint.apply(stored,
                        run.tradeDate()).equals(stored.sourceFingerprint());
                boolean dataQuality = raw != null && asOfDataset.bars()
                        .stream().anyMatch(value -> value.security().equals(
                                        position.security())
                                && value.tradeDate().equals(run.tradeDate())
                                && value.tradable());
                boolean signalValid = strategySignalValid(actual,
                        asOfDataset, portfolio, run.tradeDate());
                BigDecimal high = raw == null ? BigDecimal.ZERO : raw.high();
                BigDecimal low = raw == null ? BigDecimal.ZERO : raw.low();
                var intent = plans.evaluateExit(actual, holding, high, low,
                        signalValid, sourceValid && dataQuality);
                if (intent.exit()) {
                    result.put(position.security(), intent.reason());
                }
            } catch (RuntimeException error) {
                result.put(position.security(), safeReason(error));
            }
        }
        return Map.copyOf(result);
    }

    private Optional<BoundPlan> activePlan(
            long portfolioId,
            Security security
    ) {
        List<Long> origins = jdbc.queryForList("""
                SELECT f.run_id
                  FROM shadow_paper_fills f
                  JOIN shadow_paper_orders o ON o.id=f.order_id
                 WHERE o.portfolio_id=? AND o.symbol=? AND o.exchange=?
                   AND o.side='BUY'
                 ORDER BY f.id DESC
                 LIMIT 1
                """, Long.class, portfolioId, security.symbol(),
                security.exchange());
        if (origins.isEmpty()) return Optional.empty();
        long shadowRunId = origins.get(0);
        List<String> rows = jdbc.queryForList("""
                SELECT plan.value::text
                  FROM research_selection_runs selection,
                       LATERAL jsonb_array_elements(
                         COALESCE(selection.result_json->'researchTradePlans',
                                  '[]'::jsonb)
                       ) plan(value)
                 WHERE selection.shadow_run_id=?
                   AND selection.status='COMPLETED'
                   AND plan.value->'security'->>'symbol'=?
                   AND plan.value->'security'->>'exchange'=?
                 ORDER BY selection.id DESC
                 LIMIT 2
                """, String.class, shadowRunId, security.symbol(),
                security.exchange());
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) {
            throw invalid("RESEARCH_TRADE_PLAN_BINDING_AMBIGUOUS");
        }
        try {
            return Optional.of(new BoundPlan(shadowRunId,
                    mapper.readValue(rows.get(0), ResearchTradePlan.class)));
        } catch (JsonProcessingException error) {
            throw invalid("RESEARCH_TRADE_PLAN_STORED_RESULT_INVALID");
        }
    }

    private RawDailyBarObservation rawAt(
            Security security,
            LocalDate date
    ) {
        List<RawDailyBarObservation> values = facts.findRawBarsAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.rawSourceIdentity(
                        security.symbol(), security.exchange()),
                security.symbol(), security.exchange(), date,
                clock.instant(), 1);
        return values.size() == 1
                && values.get(0).tradeDate().equals(date)
                ? values.get(0) : null;
    }

    private static List<LocalDate> tradingSessions(
            ResearchDataset dataset,
            LocalDate through
    ) {
        return dataset.sessions().stream().map(value -> value.tradeDate())
                .filter(value -> !value.isAfter(through)).toList();
    }

    private static int holdingSessions(
            ResearchTradePlan plan,
            ResearchDataset dataset,
            LocalDate through
    ) {
        if (plan.actualPaperEntryPrice() == null) return 0;
        LocalDate entry = dataset.sessions().stream().map(
                        value -> value.tradeDate())
                .filter(value -> !value.isAfter(through))
                .filter(value -> !value.isBefore(
                        plan.plannedExecutionDate()))
                .findFirst().orElse(plan.plannedExecutionDate());
        return Math.toIntExact(dataset.sessions().stream().map(
                        value -> value.tradeDate())
                .filter(value -> value.isAfter(entry)
                        && !value.isAfter(through)).count());
    }

    private static boolean strategySignalValid(
            ResearchTradePlan plan,
            ResearchDataset dataset,
            PaperPortfolio portfolio,
            LocalDate signalDate
    ) {
        Map<Security, List<DailyBar>> history = new LinkedHashMap<>();
        dataset.bars().stream().filter(value ->
                        !value.tradeDate().isAfter(signalDate))
                .forEach(value -> history.computeIfAbsent(value.security(),
                        ignored -> new java.util.ArrayList<>()).add(value));
        history.replaceAll((security, values) -> values.stream().sorted(
                java.util.Comparator.comparing(DailyBar::tradeDate)).toList());
        Map<Security, Integer> positions = new LinkedHashMap<>();
        portfolio.positions().forEach(value -> positions.put(value.security(),
                value.quantity()));
        Map<Security, BigDecimal> weights = new LinkedHashMap<>();
        if (!positions.isEmpty()) {
            BigDecimal equal = BigDecimal.ONE.divide(BigDecimal.valueOf(
                    positions.size()), 12, java.math.RoundingMode.DOWN);
            positions.keySet().forEach(value -> weights.put(value, equal));
        }
        int sessionIndex = -1;
        for (int index = 0; index < dataset.sessions().size(); index++) {
            if (dataset.sessions().get(index).tradeDate().equals(signalDate)) {
                sessionIndex = index;
                break;
            }
        }
        if (sessionIndex < 0) {
            throw invalid("RESEARCH_TRADE_PLAN_SIGNAL_SESSION_MISSING");
        }
        StrategyContext context = new StrategyContext(signalDate,
                StrategyResearchModels.closeInstant(signalDate), sessionIndex,
                history, weights, positions);
        var target = new StrategyRegistry().create(StrategySpec.of(
                plan.preferredStrategy())).generateTargets(context);
        return target.targetWeights().getOrDefault(plan.security(),
                BigDecimal.ZERO).signum() > 0;
    }

    private static BiFunction<ResearchTradePlan, LocalDate, String>
            fingerprintResolver(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        PitMarketFactRepository facts = new PitMarketFactRepository(
                Objects.requireNonNull(jdbc, "jdbc"),
                Objects.requireNonNull(mapper, "mapper"));
        Clock sourceClock = Objects.requireNonNull(clock, "clock");
        return (plan, executionDate) -> currentFingerprint(facts, sourceClock,
                plan, executionDate);
    }

    private static String currentFingerprint(
            PitMarketFactRepository facts,
            Clock clock,
            ResearchTradePlan plan,
            LocalDate executionDate
    ) {
        var security = plan.security();
        List<RawDailyBarObservation> raw = facts.findRawBarsAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.rawSourceIdentity(
                        security.symbol(), security.exchange()),
                security.symbol(), security.exchange(),
                plan.anchorTradeDate(), clock.instant(), 15);
        if (raw.size() != 15) {
            throw invalid("RESEARCH_TRADE_PLAN_SOURCE_WINDOW_INCOMPLETE");
        }
        List<AdjustmentFactorObservation> factors = facts.findFactorsAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.factorSourceIdentity(
                        security.symbol(), security.exchange()),
                security.symbol(), raw.get(0).tradeDate(),
                plan.anchorTradeDate(), clock.instant());
        Map<LocalDate, AdjustmentFactorObservation> byDate =
                new LinkedHashMap<>();
        for (AdjustmentFactorObservation factor : factors) {
            AdjustmentFactorObservation previous = byDate.put(
                    factor.factorEffectiveTradeDate(), factor);
            if (previous != null) {
                throw invalid("RESEARCH_TRADE_PLAN_SOURCE_DUPLICATE");
            }
        }
        List<PriceBar> prices = raw.stream().map(value -> {
            AdjustmentFactorObservation factor = byDate.get(
                    value.tradeDate());
            if (factor == null) {
                throw invalid(
                        "RESEARCH_TRADE_PLAN_SOURCE_WINDOW_INCOMPLETE");
            }
            return new PriceBar(value.tradeDate(), value.open(), value.high(),
                    value.low(), value.close(), factor.factor(),
                    value.envelope().canonicalContentHash(),
                    factor.envelope().canonicalContentHash());
        }).toList();
        AdjustmentFactorObservation anchorFactor = byDate.get(
                plan.anchorTradeDate());
        List<AdjustmentFactorObservation> executionFactors =
                facts.findFactorsAsOf(
                        TushareMarketFactProvider.PROVIDER_CODE,
                        TushareMarketFactProvider.factorSourceIdentity(
                                security.symbol(), security.exchange()),
                        security.symbol(), executionDate, executionDate,
                        clock.instant());
        if (anchorFactor == null || executionFactors.size() != 1) {
            throw invalid("RESEARCH_TRADE_PLAN_SOURCE_WINDOW_INCOMPLETE");
        }
        if (anchorFactor.factor().compareTo(
                executionFactors.get(0).factor()) != 0) {
            throw invalid("SOURCE_OR_ADJUSTMENT_FACTOR_CHANGED");
        }
        return ResearchTradePlanService.sourceFingerprint(security, prices);
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException(reason);
    }

    private static String safeReason(RuntimeException error) {
        String reason = error.getMessage();
        return reason != null && reason.matches("[A-Z][A-Z0-9_]{3,127}")
                ? reason : "RESEARCH_TRADE_PLAN_SOURCE_CHECK_FAILED";
    }

    private record BoundPlan(long shadowRunId, ResearchTradePlan plan) {
    }
}
