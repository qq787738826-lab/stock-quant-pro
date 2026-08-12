package com.stockquant.server.agent.shadowresearch;

import com.stockquant.core.research.PaperExecutionEngine;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperFill;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperPortfolio;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperPosition;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.Side;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic, transaction-scoped paper accounting; no broker boundary. */
public final class ShadowPaperPortfolioService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);
    private final ShadowResearchRepository repository;
    private final TransactionTemplate transaction;
    private final PaperExecutionEngine engine;
    private final BacktestConfig config;

    public ShadowPaperPortfolioService(
            ShadowResearchRepository repository,
            TransactionTemplate transaction
    ) {
        this(repository, transaction, new PaperExecutionEngine(),
                BacktestConfig.standard());
    }

    ShadowPaperPortfolioService(
            ShadowResearchRepository repository,
            TransactionTemplate transaction,
            PaperExecutionEngine engine,
            BacktestConfig config
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Freezes paper intents with the research snapshot. If the next open
     * session is not present in the as-of calendar, the legal result is an
     * empty portfolio decision rather than an invented future session.
     */
    public List<PaperOrder> createOrders(
            ShadowRun run,
            ShadowRecommendation recommendation,
            Instant nextExecution
    ) {
        if (recommendation.suggestedGrossExposure().signum() == 0
                || nextExecution == null) {
            return List.of();
        }
        PaperPortfolio portfolio = repository.lockPortfolio();
        List<Security> candidates = recommendation.rankedSecurities()
                .stream().map(ShadowPaperPortfolioService::security)
                .limit(Math.min(config.maxPositions(), 3)).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        BigDecimal each = recommendation.suggestedGrossExposure()
                .divide(BigDecimal.valueOf(candidates.size()), 12,
                        RoundingMode.DOWN)
                .min(config.maxSinglePositionWeight());
        Map<Security, BigDecimal> targets = new LinkedHashMap<>();
        candidates.forEach(value -> targets.put(value, each));
        BigDecimal equity = portfolio.cash();
        for (PaperPosition position : portfolio.positions()) {
            equity = equity.add(position.lastPrice().multiply(
                    BigDecimal.valueOf(position.quantity())));
        }
        List<PaperOrder> orders = new ArrayList<>();
        java.util.LinkedHashSet<Security> identities =
                new java.util.LinkedHashSet<>(targets.keySet());
        portfolio.positions().forEach(value -> identities.add(
                value.security()));
        for (Security security : identities) {
            BigDecimal target = targets.getOrDefault(security,
                    BigDecimal.ZERO);
            PaperPosition position = portfolio.positions().stream()
                    .filter(value -> value.security().equals(security))
                    .findFirst().orElse(null);
            BigDecimal currentWeight = position == null ? BigDecimal.ZERO
                    : position.lastPrice().multiply(BigDecimal.valueOf(
                    position.quantity())).divide(equity, 12,
                    RoundingMode.HALF_EVEN);
            Side side = currentWeight.compareTo(target) > 0
                    ? Side.SELL : Side.BUY;
            if (currentWeight.subtract(target).abs().compareTo(
                    new BigDecimal("0.00000001")) <= 0) {
                continue;
            }
            String orderKey = "PAPER_" + run.id() + "_BUY_"
                    + security.symbol() + "_" + security.exchange();
            orderKey = orderKey.replace("_BUY_", "_" + side.name() + "_");
            orders.add(repository.insertOrder(run.id(), portfolio.id(),
                    orderKey, side, security, run.signalTime(),
                    nextExecution, target));
        }
        return List.copyOf(orders);
    }

    /** Executes all due intents exactly once against a known next-session open. */
    public Execution executeDue(
            LocalDate executionDate,
            Instant executionTime,
            ResearchDataset asOfDataset,
            Long snapshotRunId
    ) {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.releaseTPlusOne(executionDate);
            PaperPortfolio portfolio = repository.lockPortfolio();
            Map<Security, BigDecimal> marks = marksAtExecution(asOfDataset,
                    executionDate);
            List<PaperFill> fills = new ArrayList<>();
            List<PaperOrder> due = repository.pendingOrders(executionTime);
            java.util.LinkedHashSet<Long> sourceRuns =
                    new java.util.LinkedHashSet<>();
            due.forEach(order -> sourceRuns.add(order.runId()));
            for (PaperOrder order : due) {
                BigDecimal reference = openingPrice(asOfDataset,
                        order.security(), executionDate);
                if (reference == null) {
                    repository.rejectOrder(order.id(),
                            "NO_LEGAL_NEXT_SESSION_PRICE");
                    continue;
                }
                PaperExecutionEngine.State before = state(portfolio);
                PaperExecutionEngine.Result result = engine.execute(
                        new PaperExecutionEngine.Request(before, config,
                                order.side() == Side.BUY
                                        ? PaperExecutionEngine.Side.BUY
                                        : PaperExecutionEngine.Side.SELL,
                                order.security(), order.targetWeight(),
                                runTradeDate(order.runId()),
                                order.signalTime(), executionDate,
                                executionTime, reference, marks));
                if (result.fill().isEmpty()) {
                    repository.rejectOrder(order.id(),
                            result.rejectionReason());
                    continue;
                }
                var value = result.fill().orElseThrow();
                PaperExecutionEngine.Position position = result.state()
                        .positions().get(order.security());
                repository.updatePortfolio(portfolio.id(),
                        result.state().cash(), result.state().realizedPnl(),
                        result.state().totalFees(), portfolio.stateVersion());
                repository.upsertPosition(portfolio.id(), order.security(),
                        position == null ? 0 : position.quantity(),
                        position == null ? 0 : position.availableQuantity(),
                        position == null ? BigDecimal.ONE
                                : position.averageCost(),
                        position == null ? reference : position.lastPrice(),
                        position == null ? null : position.lastBuyDate());
                fills.add(repository.fill(order, executionDate, executionTime,
                        value.referencePrice(), value.executionPrice(),
                        value.quantity(), value.grossAmount(),
                        value.commission(), value.stampDuty(),
                        value.slippageCost(), value.realizedPnl(),
                        value.cashAfter(), value.positionAfter()));
                portfolio = repository.lockPortfolio();
            }
            PaperPortfolio after = repository.lockPortfolio();
            PortfolioSnapshot snapshot;
            if (snapshotRunId != null) {
                snapshot = snapshot(after, snapshotRunId, executionDate,
                        executionTime, marks);
            } else if (!sourceRuns.isEmpty()) {
                snapshot = null;
                for (Long sourceRun : sourceRuns) {
                    snapshot = snapshot(after, sourceRun, executionDate,
                            executionTime, marks);
                }
            } else {
                snapshot = snapshot(after, null, executionDate,
                        executionTime, marks);
            }
            return new Execution(List.copyOf(fills), after, snapshot);
        }), "paperExecution");
    }

    public PortfolioSnapshot snapshot(
            PaperPortfolio portfolio,
            Long runId,
            LocalDate date,
            Instant at,
            Map<Security, BigDecimal> marks
    ) {
        BigDecimal market = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (PaperPosition position : portfolio.positions()) {
            BigDecimal mark = marks.getOrDefault(position.security(),
                    position.lastPrice());
            market = market.add(mark.multiply(BigDecimal.valueOf(
                    position.quantity())));
            cost = cost.add(position.averageCost().multiply(
                    BigDecimal.valueOf(position.quantity())));
        }
        BigDecimal equity = money(portfolio.cash().add(market));
        BigDecimal unrealized = money(market.subtract(cost));
        BigDecimal totalReturn = equity.subtract(portfolio.initialCash())
                .divide(portfolio.initialCash(), 12, RoundingMode.HALF_EVEN);
        return repository.insertPortfolioSnapshot(portfolio.id(), runId,
                date, at, money(portfolio.cash()), money(market), equity,
                money(portfolio.realizedPnl()), unrealized,
                money(portfolio.totalFees()), totalReturn,
                portfolio.positions().size());
    }

    private PaperExecutionEngine.State state(PaperPortfolio portfolio) {
        Map<Security, PaperExecutionEngine.Position> positions =
                new LinkedHashMap<>();
        for (PaperPosition value : portfolio.positions()) {
            positions.put(value.security(), new PaperExecutionEngine.Position(
                    value.quantity(), value.availableQuantity(),
                    value.averageCost(), value.lastPrice(),
                    value.lastBuyDate()));
        }
        return new PaperExecutionEngine.State(portfolio.cash(),
                portfolio.realizedPnl(), portfolio.totalFees(), positions);
    }

    private LocalDate runTradeDate(long runId) {
        return repository.run(runId).orElseThrow(() ->
                new IllegalStateException("M4_ORDER_RUN_MISSING"))
                .tradeDate();
    }

    private static Map<Security, BigDecimal> marksAtExecution(
            ResearchDataset dataset,
            LocalDate date
    ) {
        Map<Security, BigDecimal> values = new LinkedHashMap<>();
        dataset.bars().stream().filter(value ->
                value.tradeDate().isBefore(date)).forEach(value ->
                values.put(value.security(), value.close()));
        dataset.bars().stream().filter(value ->
                value.tradeDate().equals(date) && value.tradable())
                .forEach(value -> values.put(value.security(), value.open()));
        return values;
    }

    private static BigDecimal openingPrice(
            ResearchDataset dataset,
            Security security,
            LocalDate date
    ) {
        return dataset.bars().stream().filter(value ->
                        value.security().equals(security)
                                && value.tradeDate().equals(date)
                                && value.tradable())
                .map(DailyBar::open).findFirst().orElse(null);
    }

    private static Security security(String canonical) {
        String[] values = canonical.split(":", -1);
        if (values.length != 2) {
            throw new IllegalStateException("M4_SECURITY_RANKING_INVALID");
        }
        return new Security(values[0], values[1]);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_EVEN);
    }

    public record Execution(
            List<PaperFill> fills,
            PaperPortfolio portfolio,
            PortfolioSnapshot snapshot
    ) {
    }
}
