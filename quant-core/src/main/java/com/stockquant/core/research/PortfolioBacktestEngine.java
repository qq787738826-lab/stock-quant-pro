package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.AccountingSummary;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.BacktestResult;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.EquityPoint;
import com.stockquant.core.research.StrategyResearchModels.PerformanceMetrics;
import com.stockquant.core.research.StrategyResearchModels.PositionSnapshot;
import com.stockquant.core.research.StrategyResearchModels.RejectedOrder;
import com.stockquant.core.research.StrategyResearchModels.RejectionReason;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.Side;
import com.stockquant.core.research.StrategyResearchModels.StrategyContext;
import com.stockquant.core.research.StrategyResearchModels.TargetPortfolio;
import com.stockquant.core.research.StrategyResearchModels.TargetAction;
import com.stockquant.core.research.StrategyResearchModels.TradeFill;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic, single-process, long-only portfolio backtest engine.
 * Signals are generated after close and may only execute at a later session's
 * open. The legacy stage-2F single-security entry point remains untouched.
 */
public final class PortfolioBacktestEngine {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal EPSILON = new BigDecimal("0.000001");
    private static final int MONEY_SCALE = 8;
    private static final int RATIO_SCALE = 12;

    private final StrategyRegistry registry;

    public PortfolioBacktestEngine() {
        this(new StrategyRegistry());
    }

    public PortfolioBacktestEngine(StrategyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public BacktestResult run(BacktestRequest request) {
        Objects.requireNonNull(request, "request");
        Strategy strategy = registry.create(request.strategy());
        State state = new State(request.config());
        ResearchDataset dataset = request.dataset();
        Map<Security, Map<LocalDate, DailyBar>> barsByDate = indexBars(dataset);
        Map<Security, List<DailyBar>> history = new TreeMap<>();
        for (Security security : dataset.securities()) {
            history.put(security, new ArrayList<>());
        }

        TargetPortfolio pending = null;
        BigDecimal previousEquity = null;
        BigDecimal peak = request.config().initialCash();
        int openSessionIndex = 0;

        for (TradingSession session : dataset.sessions()) {
            LocalDate date = session.tradeDate();
            if (date.isAfter(request.executionEnd())) {
                break;
            }
            if (!session.anyOpen()) {
                continue;
            }
            Map<Security, DailyBar> today = barsForDate(
                    dataset.securities(), barsByDate, date);

            if (pending != null && !date.isBefore(request.executionStart())
                    && pending.signalDate().isBefore(date)
                    && pending.action() == TargetAction.REBALANCE) {
                executeTargets(state, request.config(), dataset, session,
                        today, pending);
            }

            for (Map.Entry<Security, DailyBar> entry : today.entrySet()) {
                history.get(entry.getKey()).add(entry.getValue());
                state.marks.put(entry.getKey(), entry.getValue().close());
            }

            if (!date.isBefore(request.executionStart())) {
                BigDecimal equity = portfolioEquity(state.cash,
                        state.positions, state.marks);
                peak = peak.max(equity);
                BigDecimal drawdown = ratio(peak.subtract(equity), peak);
                BigDecimal dailyReturn = previousEquity == null
                        ? ZERO : ratio(equity.subtract(previousEquity),
                        previousEquity);
                state.equityCurve.add(new EquityPoint(date,
                        money(state.cash), money(marketValue(
                        state.positions, state.marks)), money(equity),
                        normalized(dailyReturn), normalized(drawdown),
                        state.positions.size()));
                previousEquity = equity;
                if (!state.riskHalted && !state.positions.isEmpty()
                        && drawdown.compareTo(
                        request.config().maxPortfolioDrawdown()) >= 0) {
                    state.riskHalted = true;
                }
            }

            StrategyContext context = new StrategyContext(
                    date, StrategyResearchModels.closeInstant(date),
                    openSessionIndex, immutableHistory(history),
                    currentWeights(state), currentQuantities(state));
            if (state.riskHalted) {
                pending = new TargetPortfolio(date, Map.of(),
                        "MAX_PORTFOLIO_DRAWDOWN_HALT");
            } else {
                pending = strategy.generateTargets(context);
                if (!pending.signalDate().equals(date)) {
                    throw invalid("M2_STRATEGY_SIGNAL_DATE_INVALID");
                }
            }
            openSessionIndex++;
        }

        if (state.equityCurve.isEmpty()) {
            throw invalid("M2_BACKTEST_NO_TRADING_SESSION");
        }
        List<PositionSnapshot> positions = endingPositions(state);
        AccountingSummary accounting = accounting(state,
                request.config().initialCash(), positions);
        PerformanceMetrics metrics = metrics(request, state, accounting);
        String fingerprint = fingerprint(request, strategy, metrics,
                accounting, positions, state);
        return new BacktestResult(
                StrategyResearchModels.BACKTEST_ENGINE_VERSION,
                StrategyResearchModels.BACKTEST_ENGINE_PROFILE,
                strategy.definition(), dataset.datasetVersion(),
                request.executionStart(), request.executionEnd(),
                fingerprint, metrics, accounting, positions,
                state.equityCurve, state.trades, state.rejections,
                state.riskHalted, true, true);
    }

    private static void executeTargets(
            State state,
            BacktestConfig config,
            ResearchDataset dataset,
            TradingSession session,
            Map<Security, DailyBar> today,
            TargetPortfolio intent
    ) {
        Map<Security, BigDecimal> targets = normalizeTargets(
                intent.targetWeights(), config, dataset.securities());
        BigDecimal preTradeEquity = openEquity(state, today);
        Map<Security, Integer> startQuantity = currentQuantities(state);
        List<Security> universe = new ArrayList<>(dataset.securities());
        universe.sort(Comparator.naturalOrder());

        for (Security security : universe) {
            MutablePosition position = state.positions.get(security);
            int current = position == null ? 0 : position.quantity;
            BigDecimal weight = targets.getOrDefault(security, ZERO);
            PriceAccess access = priceAccess(session, today.get(security),
                    security);
            if (!access.available()) {
                if (current > 0 && weight.signum() == 0) {
                    reject(state, security, Side.SELL, intent.signalDate(),
                            session.tradeDate(), access.reason());
                }
                continue;
            }
            int desired = desiredQuantity(preTradeEquity, weight,
                    access.bar().open(), config.boardLotSize());
            if (current > desired) {
                int requested = current - desired;
                int sellable = config.tPlusOne()
                        ? startQuantity.getOrDefault(security, 0) : current;
                int quantity = Math.min(requested, sellable);
                if (quantity <= 0) {
                    reject(state, security, Side.SELL, intent.signalDate(),
                            session.tradeDate(),
                            RejectionReason.T_PLUS_ONE_RESTRICTED);
                    continue;
                }
                sell(state, config, security, position, quantity,
                        access.bar().open(), intent, session.tradeDate());
            }
        }

        preTradeEquity = openEquity(state, today);
        for (Security security : universe) {
            MutablePosition position = state.positions.get(security);
            int current = position == null ? 0 : position.quantity;
            BigDecimal weight = targets.getOrDefault(security, ZERO);
            PriceAccess access = priceAccess(session, today.get(security),
                    security);
            if (!access.available()) {
                if (weight.signum() > 0) {
                    reject(state, security, Side.BUY, intent.signalDate(),
                            session.tradeDate(), access.reason());
                }
                continue;
            }
            int desired = desiredQuantity(preTradeEquity, weight,
                    access.bar().open(), config.boardLotSize());
            if (desired <= current) {
                continue;
            }
            int quantity = desired - current;
            quantity = affordableQuantity(state.cash, quantity,
                    access.bar().open(), config);
            if (quantity < config.boardLotSize()) {
                reject(state, security, Side.BUY, intent.signalDate(),
                        session.tradeDate(), state.cash.signum() <= 0
                                ? RejectionReason.INSUFFICIENT_CASH
                                : RejectionReason.BELOW_BOARD_LOT);
                continue;
            }
            buy(state, config, security, quantity, access.bar().open(),
                    intent, session.tradeDate());
        }
        requireCashInvariant(state.cash);
    }

    private static Map<Security, BigDecimal> normalizeTargets(
            Map<Security, BigDecimal> requested,
            BacktestConfig config,
            List<Security> universe
    ) {
        Map<Security, BigDecimal> values = new TreeMap<>();
        for (Map.Entry<Security, BigDecimal> entry : requested.entrySet()) {
            if (!universe.contains(entry.getKey())) {
                throw invalid("M2_TARGET_UNKNOWN_SECURITY");
            }
            if (entry.getValue().signum() < 0) {
                throw invalid("M2_SHORT_TARGET_FORBIDDEN");
            }
            if (entry.getValue().signum() > 0) {
                values.put(entry.getKey(), entry.getValue().min(
                        config.maxSinglePositionWeight()));
            }
        }
        if (values.size() > config.maxPositions()) {
            List<Map.Entry<Security, BigDecimal>> ranked = new ArrayList<>(
                    values.entrySet());
            ranked.sort(Map.Entry.<Security, BigDecimal>comparingByValue()
                    .reversed().thenComparing(Map.Entry::getKey));
            values.clear();
            ranked.stream().limit(config.maxPositions()).forEach(entry ->
                    values.put(entry.getKey(), entry.getValue()));
        }
        BigDecimal total = values.values().stream().reduce(ZERO,
                BigDecimal::add);
        if (total.compareTo(config.maxGrossExposure()) > 0) {
            BigDecimal scale = config.maxGrossExposure().divide(total,
                    RATIO_SCALE, RoundingMode.DOWN);
            values.replaceAll((key, value) -> value.multiply(scale)
                    .setScale(RATIO_SCALE, RoundingMode.DOWN));
        }
        return Collections.unmodifiableMap(values);
    }

    private static void buy(
            State state,
            BacktestConfig config,
            Security security,
            int quantity,
            BigDecimal referencePrice,
            TargetPortfolio intent,
            LocalDate executionDate
    ) {
        BigDecimal executionPrice = slipped(referencePrice,
                config.slippageBps(), Side.BUY);
        BigDecimal gross = money(executionPrice.multiply(
                BigDecimal.valueOf(quantity)));
        BigDecimal commission = commission(gross, config);
        BigDecimal total = gross.add(commission);
        if (total.compareTo(state.cash) > 0) {
            throw invalid("M2_CASH_OVERDRAW_PREVENTED");
        }
        state.cash = money(state.cash.subtract(total));
        MutablePosition existing = state.positions.get(security);
        BigDecimal oldCost = existing == null ? ZERO
                : existing.averageCost.multiply(
                BigDecimal.valueOf(existing.quantity));
        int oldQuantity = existing == null ? 0 : existing.quantity;
        int newQuantity = oldQuantity + quantity;
        BigDecimal newCost = oldCost.add(gross).add(commission);
        BigDecimal averageCost = newCost.divide(
                BigDecimal.valueOf(newQuantity), RATIO_SCALE,
                RoundingMode.HALF_EVEN);
        state.positions.put(security, new MutablePosition(
                newQuantity, averageCost));
        state.grossBuys = money(state.grossBuys.add(gross));
        state.totalCommission = money(state.totalCommission.add(commission));
        BigDecimal slippage = money(executionPrice.subtract(referencePrice)
                .multiply(BigDecimal.valueOf(quantity)).abs());
        state.totalSlippage = money(state.totalSlippage.add(slippage));
        state.trades.add(new TradeFill(++state.eventSequence, security,
                Side.BUY, intent.signalDate(),
                StrategyResearchModels.closeInstant(intent.signalDate()),
                executionDate, StrategyResearchModels.openInstant(executionDate),
                normalized(referencePrice), executionPrice, quantity, gross,
                commission, ZERO, slippage, ZERO, state.cash, newQuantity,
                intent.reason()));
    }

    private static void sell(
            State state,
            BacktestConfig config,
            Security security,
            MutablePosition position,
            int quantity,
            BigDecimal referencePrice,
            TargetPortfolio intent,
            LocalDate executionDate
    ) {
        BigDecimal executionPrice = slipped(referencePrice,
                config.slippageBps(), Side.SELL);
        BigDecimal gross = money(executionPrice.multiply(
                BigDecimal.valueOf(quantity)));
        BigDecimal commission = commission(gross, config);
        BigDecimal stampDuty = money(gross.multiply(config.stampDutyRate()));
        BigDecimal costBasis = money(position.averageCost.multiply(
                BigDecimal.valueOf(quantity)));
        BigDecimal realized = money(gross.subtract(commission)
                .subtract(stampDuty).subtract(costBasis));
        state.cash = money(state.cash.add(gross)
                .subtract(commission).subtract(stampDuty));
        int remaining = position.quantity - quantity;
        if (remaining == 0) {
            state.positions.remove(security);
        } else {
            state.positions.put(security, new MutablePosition(
                    remaining, position.averageCost));
        }
        state.grossSells = money(state.grossSells.add(gross));
        state.totalCommission = money(state.totalCommission.add(commission));
        state.totalStampDuty = money(state.totalStampDuty.add(stampDuty));
        state.realizedPnl = money(state.realizedPnl.add(realized));
        BigDecimal slippage = money(referencePrice.subtract(executionPrice)
                .multiply(BigDecimal.valueOf(quantity)).abs());
        state.totalSlippage = money(state.totalSlippage.add(slippage));
        state.trades.add(new TradeFill(++state.eventSequence, security,
                Side.SELL, intent.signalDate(),
                StrategyResearchModels.closeInstant(intent.signalDate()),
                executionDate, StrategyResearchModels.openInstant(executionDate),
                normalized(referencePrice), executionPrice, quantity, gross,
                commission, stampDuty, slippage, realized, state.cash,
                remaining, intent.reason()));
    }

    private static void reject(
            State state,
            Security security,
            Side side,
            LocalDate signalDate,
            LocalDate executionDate,
            RejectionReason reason
    ) {
        state.rejections.add(new RejectedOrder(++state.eventSequence,
                security, side, signalDate, executionDate, reason));
    }

    private static PriceAccess priceAccess(
            TradingSession session,
            DailyBar bar,
            Security security
    ) {
        if (!session.isOpen(security.exchange())) {
            return new PriceAccess(null, RejectionReason.EXCHANGE_CLOSED);
        }
        if (bar == null) {
            return new PriceAccess(null, RejectionReason.NO_TRADABLE_PRICE);
        }
        if (!bar.tradable()) {
            return new PriceAccess(null, RejectionReason.SUSPENDED);
        }
        return new PriceAccess(bar, null);
    }

    private static int desiredQuantity(
            BigDecimal equity,
            BigDecimal weight,
            BigDecimal price,
            int lot
    ) {
        if (weight.signum() <= 0) {
            return 0;
        }
        BigDecimal targetValue = equity.multiply(weight);
        return targetValue.divide(price.multiply(BigDecimal.valueOf(lot)),
                0, RoundingMode.DOWN).intValueExact() * lot;
    }

    private static int affordableQuantity(
            BigDecimal cash,
            int requested,
            BigDecimal referencePrice,
            BacktestConfig config
    ) {
        int lot = config.boardLotSize();
        int quantity = requested / lot * lot;
        BigDecimal executionPrice = slipped(referencePrice,
                config.slippageBps(), Side.BUY);
        while (quantity >= lot) {
            BigDecimal gross = money(executionPrice.multiply(
                    BigDecimal.valueOf(quantity)));
            if (gross.add(commission(gross, config)).compareTo(cash) <= 0) {
                return quantity;
            }
            quantity -= lot;
        }
        return 0;
    }

    private static BigDecimal commission(
            BigDecimal gross,
            BacktestConfig config
    ) {
        return money(gross.multiply(config.commissionRate())
                .max(config.minimumCommission()));
    }

    private static BigDecimal slipped(
            BigDecimal referencePrice,
            int slippageBps,
            Side side
    ) {
        BigDecimal fraction = BigDecimal.valueOf(slippageBps)
                .divide(BPS, RATIO_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal multiplier = side == Side.BUY
                ? ONE.add(fraction) : ONE.subtract(fraction);
        return referencePrice.multiply(multiplier)
                .setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private static AccountingSummary accounting(
            State state,
            BigDecimal initialCash,
            List<PositionSnapshot> positions
    ) {
        BigDecimal marketValue = positions.stream()
                .map(PositionSnapshot::marketValue)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal unrealized = positions.stream()
                .map(PositionSnapshot::unrealizedPnl)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal endingEquity = money(state.cash.add(marketValue));
        BigDecimal expectedCash = money(initialCash.subtract(state.grossBuys)
                .add(state.grossSells).subtract(state.totalCommission)
                .subtract(state.totalStampDuty));
        BigDecimal rawCashDelta = money(state.cash.subtract(expectedCash));
        BigDecimal rawPnlDelta = money(endingEquity.subtract(initialCash)
                .subtract(state.realizedPnl).subtract(unrealized));
        BigDecimal cashDelta = rawCashDelta.abs().compareTo(EPSILON) <= 0
                ? money(ZERO) : rawCashDelta;
        BigDecimal pnlDelta = rawPnlDelta.abs().compareTo(EPSILON) <= 0
                ? money(ZERO) : rawPnlDelta;
        boolean passed = state.cash.signum() >= 0
                && rawCashDelta.abs().compareTo(EPSILON) <= 0
                && rawPnlDelta.abs().compareTo(EPSILON) <= 0
                && positions.stream().allMatch(value -> value.quantity() > 0);
        if (!passed) {
            throw invalid("M2_ACCOUNTING_INVARIANT_FAILED");
        }
        return new AccountingSummary(state.grossBuys, state.grossSells,
                state.totalCommission, state.totalStampDuty,
                state.totalSlippage, state.realizedPnl, money(unrealized),
                state.cash, money(marketValue), endingEquity,
                cashDelta, pnlDelta, true);
    }

    private static PerformanceMetrics metrics(
            BacktestRequest request,
            State state,
            AccountingSummary accounting
    ) {
        List<EquityPoint> curve = state.equityCurve;
        BigDecimal initial = request.config().initialCash();
        BigDecimal finalEquity = accounting.endingEquity();
        BigDecimal pnl = money(finalEquity.subtract(initial));
        BigDecimal totalReturn = ratio(pnl, initial);
        long calendarDays = Math.max(1, ChronoUnit.DAYS.between(
                curve.get(0).tradeDate(),
                curve.get(curve.size() - 1).tradeDate()));
        BigDecimal cagr = fromDouble(StrictMath.pow(
                finalEquity.divide(initial, 16, RoundingMode.HALF_EVEN)
                        .doubleValue(), 365.2425d / calendarDays) - 1d);
        List<Double> returns = curve.stream().skip(1)
                .map(value -> value.dailyReturn().doubleValue()).toList();
        double mean = returns.stream().mapToDouble(Double::doubleValue)
                .average().orElse(0d);
        double variance = 0d;
        if (returns.size() > 1) {
            for (double value : returns) {
                variance += (value - mean) * (value - mean);
            }
            variance /= returns.size() - 1d;
        }
        double annualVolatility = StrictMath.sqrt(variance)
                * StrictMath.sqrt(252d);
        double annualReturn = mean * 252d;
        double sharpe = annualVolatility == 0d ? 0d
                : (annualReturn
                - request.config().annualRiskFreeRate().doubleValue())
                / annualVolatility;
        BigDecimal maxDrawdown = curve.stream().map(EquityPoint::drawdown)
                .max(BigDecimal::compareTo).orElse(ZERO);
        List<TradeFill> exits = state.trades.stream()
                .filter(value -> value.side() == Side.SELL).toList();
        long wins = exits.stream().filter(value ->
                value.realizedPnl().signum() > 0).count();
        BigDecimal winRate = exits.isEmpty() ? ZERO
                : BigDecimal.valueOf(wins).divide(
                BigDecimal.valueOf(exits.size()), RATIO_SCALE,
                RoundingMode.HALF_EVEN);
        BigDecimal averageEquity = curve.stream().map(EquityPoint::equity)
                .reduce(ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(curve.size()), MONEY_SCALE,
                        RoundingMode.HALF_EVEN);
        BigDecimal turnover = ratio(state.grossBuys.add(state.grossSells),
                averageEquity);
        return new PerformanceMetrics(money(initial), finalEquity, pnl,
                normalized(totalReturn), normalized(cagr),
                normalized(fromDouble(annualReturn)),
                normalized(fromDouble(annualVolatility)),
                normalized(fromDouble(sharpe)), normalized(maxDrawdown),
                normalized(winRate), normalized(turnover), curve.size(),
                state.trades.size(), exits.size());
    }

    private static List<PositionSnapshot> endingPositions(State state) {
        BigDecimal market = marketValue(state.positions, state.marks);
        BigDecimal equity = state.cash.add(market);
        List<PositionSnapshot> result = new ArrayList<>();
        state.positions.forEach((security, position) -> {
            BigDecimal price = requiredMark(state.marks, security);
            BigDecimal value = money(price.multiply(
                    BigDecimal.valueOf(position.quantity)));
            BigDecimal cost = money(position.averageCost.multiply(
                    BigDecimal.valueOf(position.quantity)));
            result.add(new PositionSnapshot(security, position.quantity,
                    normalized(position.averageCost), normalized(price), value,
                    money(value.subtract(cost)), normalized(ratio(value, equity))));
        });
        result.sort(Comparator.comparing(PositionSnapshot::security));
        return List.copyOf(result);
    }

    private static Map<Security, BigDecimal> currentWeights(State state) {
        BigDecimal equity = portfolioEquity(state.cash, state.positions,
                state.marks);
        Map<Security, BigDecimal> result = new TreeMap<>();
        state.positions.forEach((security, position) -> {
            BigDecimal value = requiredMark(state.marks, security)
                    .multiply(BigDecimal.valueOf(position.quantity));
            result.put(security, normalized(ratio(value, equity)));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<Security, Integer> currentQuantities(State state) {
        Map<Security, Integer> result = new TreeMap<>();
        state.positions.forEach((key, value) ->
                result.put(key, value.quantity));
        return Collections.unmodifiableMap(result);
    }

    private static Map<Security, List<DailyBar>> immutableHistory(
            Map<Security, List<DailyBar>> history
    ) {
        Map<Security, List<DailyBar>> result = new TreeMap<>();
        history.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    private static Map<Security, Map<LocalDate, DailyBar>> indexBars(
            ResearchDataset dataset
    ) {
        Map<Security, Map<LocalDate, DailyBar>> result = new TreeMap<>();
        for (DailyBar bar : dataset.bars()) {
            Map<LocalDate, DailyBar> values = result.computeIfAbsent(
                    bar.security(), ignored -> new TreeMap<>());
            if (values.put(bar.tradeDate(), bar) != null) {
                throw invalid("M2_DAILY_BAR_DUPLICATE");
            }
        }
        return result;
    }

    private static Map<Security, DailyBar> barsForDate(
            List<Security> securities,
            Map<Security, Map<LocalDate, DailyBar>> index,
            LocalDate date
    ) {
        Map<Security, DailyBar> result = new TreeMap<>();
        for (Security security : securities) {
            DailyBar bar = index.getOrDefault(security, Map.of()).get(date);
            if (bar != null) {
                result.put(security, bar);
            }
        }
        return result;
    }

    private static BigDecimal openEquity(
            State state,
            Map<Security, DailyBar> today
    ) {
        BigDecimal value = ZERO;
        for (Map.Entry<Security, MutablePosition> entry
                : state.positions.entrySet()) {
            DailyBar bar = today.get(entry.getKey());
            BigDecimal price = bar == null
                    ? requiredMark(state.marks, entry.getKey()) : bar.open();
            value = value.add(price.multiply(
                    BigDecimal.valueOf(entry.getValue().quantity)));
        }
        return money(state.cash.add(value));
    }

    private static BigDecimal portfolioEquity(
            BigDecimal cash,
            Map<Security, MutablePosition> positions,
            Map<Security, BigDecimal> marks
    ) {
        return money(cash.add(marketValue(positions, marks)));
    }

    private static BigDecimal marketValue(
            Map<Security, MutablePosition> positions,
            Map<Security, BigDecimal> marks
    ) {
        BigDecimal value = ZERO;
        for (Map.Entry<Security, MutablePosition> entry
                : positions.entrySet()) {
            value = value.add(requiredMark(marks, entry.getKey()).multiply(
                    BigDecimal.valueOf(entry.getValue().quantity)));
        }
        return money(value);
    }

    private static BigDecimal requiredMark(
            Map<Security, BigDecimal> marks,
            Security security
    ) {
        BigDecimal value = marks.get(security);
        if (value == null || value.signum() <= 0) {
            throw invalid("M2_POSITION_MARK_MISSING");
        }
        return value;
    }

    private static String fingerprint(
            BacktestRequest request,
            Strategy strategy,
            PerformanceMetrics metrics,
            AccountingSummary accounting,
            List<PositionSnapshot> positions,
            State state
    ) {
        StringBuilder value = new StringBuilder(16_384);
        append(value, "engine", StrategyResearchModels.BACKTEST_ENGINE_VERSION);
        append(value, "profile", StrategyResearchModels.BACKTEST_ENGINE_PROFILE);
        append(value, "dataset", request.dataset().datasetVersion());
        append(value, "knowledgeMode", request.dataset().knowledgeMode().name());
        append(value, "start", request.executionStart().toString());
        append(value, "end", request.executionEnd().toString());
        append(value, "strategy", strategy.definition().strategyCode());
        strategy.definition().parameters().forEach((key, parameter) ->
                append(value, "parameter." + key, parameter));
        BacktestConfig config = request.config();
        append(value, "initialCash", decimal(config.initialCash()));
        append(value, "commissionRate", decimal(config.commissionRate()));
        append(value, "minimumCommission", decimal(config.minimumCommission()));
        append(value, "stampDutyRate", decimal(config.stampDutyRate()));
        append(value, "slippageBps", Integer.toString(config.slippageBps()));
        append(value, "boardLotSize", Integer.toString(config.boardLotSize()));
        append(value, "maxGross", decimal(config.maxGrossExposure()));
        append(value, "maxSingle", decimal(config.maxSinglePositionWeight()));
        append(value, "maxPositions", Integer.toString(config.maxPositions()));
        append(value, "maxDrawdown", decimal(config.maxPortfolioDrawdown()));
        append(value, "riskFree", decimal(config.annualRiskFreeRate()));
        append(value, "tPlusOne", Boolean.toString(config.tPlusOne()));
        request.dataset().sessions().forEach(session -> append(value,
                "session", session.tradeDate() + ":"
                        + String.join(",", session.openExchanges())));
        request.dataset().bars().forEach(bar -> append(value, "bar",
                bar.security().canonicalCode() + ":" + bar.tradeDate() + ":"
                        + decimal(bar.open()) + ":" + decimal(bar.high()) + ":"
                        + decimal(bar.low()) + ":" + decimal(bar.close()) + ":"
                        + bar.volume() + ":" + bar.tradable() + ":"
                        + bar.marketCloseAvailableAt() + ":"
                        + bar.sourceKnownAt()));
        append(value, "metrics", metrics.toString());
        append(value, "accounting", accounting.toString());
        positions.forEach(position -> append(value, "position",
                position.toString()));
        state.equityCurve.forEach(point -> append(value, "equity",
                point.toString()));
        state.trades.forEach(trade -> append(value, "fill", trade.toString()));
        state.rejections.forEach(rejection -> append(value, "rejection",
                rejection.toString()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("M2_SHA256_UNAVAILABLE", exception);
        }
    }

    private static void append(StringBuilder target, String key, String value) {
        target.append(key.length()).append(':').append(key).append('=')
                .append(value.length()).append(':').append(value).append('\n');
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) {
            return ZERO;
        }
        return numerator.divide(denominator, RATIO_SCALE,
                RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal normalized(BigDecimal value) {
        BigDecimal normalized = value.setScale(RATIO_SCALE,
                RoundingMode.HALF_EVEN).stripTrailingZeros();
        return normalized.signum() == 0 ? ZERO : normalized;
    }

    private static BigDecimal fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw invalid("M2_METRIC_NOT_FINITE");
        }
        return BigDecimal.valueOf(value).setScale(RATIO_SCALE,
                RoundingMode.HALF_EVEN);
    }

    private static void requireCashInvariant(BigDecimal cash) {
        if (cash.signum() < 0) {
            throw invalid("M2_NEGATIVE_CASH_INVARIANT");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private static final class MutablePosition {
        private final int quantity;
        private final BigDecimal averageCost;

        private MutablePosition(int quantity, BigDecimal averageCost) {
            if (quantity <= 0 || averageCost.signum() <= 0) {
                throw invalid("M2_POSITION_STATE_INVALID");
            }
            this.quantity = quantity;
            this.averageCost = averageCost;
        }
    }

    private record PriceAccess(DailyBar bar, RejectionReason reason) {
        private boolean available() {
            return bar != null;
        }
    }

    private static final class State {
        private BigDecimal cash;
        private final Map<Security, MutablePosition> positions = new TreeMap<>();
        private final Map<Security, BigDecimal> marks = new TreeMap<>();
        private final List<EquityPoint> equityCurve = new ArrayList<>();
        private final List<TradeFill> trades = new ArrayList<>();
        private final List<RejectedOrder> rejections = new ArrayList<>();
        private BigDecimal grossBuys = money(ZERO);
        private BigDecimal grossSells = money(ZERO);
        private BigDecimal totalCommission = money(ZERO);
        private BigDecimal totalStampDuty = money(ZERO);
        private BigDecimal totalSlippage = money(ZERO);
        private BigDecimal realizedPnl = money(ZERO);
        private long eventSequence;
        private boolean riskHalted;

        private State(BacktestConfig config) {
            this.cash = money(config.initialCash());
        }
    }
}
