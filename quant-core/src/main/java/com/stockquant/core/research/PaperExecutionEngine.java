package com.stockquant.core.research;

import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.Security;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic one-step paper execution using the same A-share accounting
 * rules as {@link PortfolioBacktestEngine}: next-session execution, board lot,
 * long-only, T+1, commission, stamp duty and directional slippage.
 */
public final class PaperExecutionEngine {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final int MONEY_SCALE = 8;
    private static final int RATIO_SCALE = 12;

    public Result execute(Request request) {
        Objects.requireNonNull(request, "request");
        requireTemporalBoundary(request);
        State before = request.state();
        Position existing = before.positions().get(request.security());
        int current = existing == null ? 0 : existing.quantity();
        BigDecimal equity = equity(before, request.marks());
        BigDecimal slipped = slipped(request.referencePrice(),
                request.config().slippageBps(), request.side());
        int desired = desiredQuantity(equity, request.targetWeight(), slipped,
                request.config().boardLotSize());
        if (request.side() == Side.BUY && desired <= current
                || request.side() == Side.SELL && desired >= current) {
            return Result.rejected(before, "TARGET_ALREADY_SATISFIED");
        }
        return request.side() == Side.BUY
                ? buy(request, before, existing, current, desired, slipped)
                : sell(request, before, existing, current, desired, slipped);
    }

    private static Result buy(
            Request request,
            State before,
            Position existing,
            int current,
            int desired,
            BigDecimal executionPrice
    ) {
        if (existing == null
                && before.positions().size() >= request.config().maxPositions()) {
            return Result.rejected(before, "POSITION_LIMIT");
        }
        int quantity = desired - current;
        quantity = affordable(before.cash(), quantity, executionPrice,
                request.config());
        if (quantity < request.config().boardLotSize()) {
            return Result.rejected(before, "INSUFFICIENT_CASH_OR_BOARD_LOT");
        }
        BigDecimal gross = money(executionPrice.multiply(
                BigDecimal.valueOf(quantity)));
        BigDecimal commission = commission(gross, request.config());
        BigDecimal total = gross.add(commission);
        if (total.compareTo(before.cash()) > 0) {
            throw invalid("M2_PAPER_CASH_OVERDRAW_PREVENTED");
        }
        int newQuantity = current + quantity;
        BigDecimal oldCost = existing == null ? ZERO
                : existing.averageCost().multiply(BigDecimal.valueOf(current));
        BigDecimal averageCost = oldCost.add(gross).add(commission)
                .divide(BigDecimal.valueOf(newQuantity), RATIO_SCALE,
                        RoundingMode.HALF_EVEN);
        Map<Security, Position> positions = new TreeMap<>(before.positions());
        positions.put(request.security(), new Position(newQuantity,
                existing == null ? 0 : existing.availableQuantity(),
                averageCost, executionPrice, request.executionDate()));
        BigDecimal cash = money(before.cash().subtract(total));
        BigDecimal slippage = money(executionPrice
                .subtract(request.referencePrice()).abs()
                .multiply(BigDecimal.valueOf(quantity)));
        State after = new State(cash, before.realizedPnl(),
                money(before.totalFees().add(commission)), positions);
        Fill fill = new Fill(Side.BUY, request.security(),
                request.signalDate(), request.signalTime(),
                request.executionDate(), request.executionTime(),
                request.referencePrice(), executionPrice, quantity, gross,
                commission, ZERO, slippage, ZERO, cash, newQuantity);
        requireConservation(before, after, fill);
        return Result.filled(after, fill);
    }

    private static Result sell(
            Request request,
            State before,
            Position existing,
            int current,
            int desired,
            BigDecimal executionPrice
    ) {
        if (existing == null || current <= 0) {
            return Result.rejected(before, "POSITION_MISSING");
        }
        int requested = current - desired;
        int quantity = Math.min(requested,
                request.config().tPlusOne()
                        ? existing.availableQuantity() : current);
        quantity = quantity / request.config().boardLotSize()
                * request.config().boardLotSize();
        if (quantity <= 0) {
            return Result.rejected(before, "T_PLUS_ONE_RESTRICTED");
        }
        BigDecimal gross = money(executionPrice.multiply(
                BigDecimal.valueOf(quantity)));
        BigDecimal commission = commission(gross, request.config());
        BigDecimal stamp = money(gross.multiply(
                request.config().stampDutyRate()));
        BigDecimal realized = money(gross.subtract(commission).subtract(stamp)
                .subtract(existing.averageCost().multiply(
                        BigDecimal.valueOf(quantity))));
        BigDecimal cash = money(before.cash().add(gross)
                .subtract(commission).subtract(stamp));
        int remaining = current - quantity;
        Map<Security, Position> positions = new TreeMap<>(before.positions());
        if (remaining == 0) {
            positions.remove(request.security());
        } else {
            positions.put(request.security(), new Position(remaining,
                    Math.min(remaining,
                            existing.availableQuantity() - quantity),
                    existing.averageCost(), executionPrice,
                    existing.lastBuyDate()));
        }
        BigDecimal slippage = money(request.referencePrice()
                .subtract(executionPrice).abs()
                .multiply(BigDecimal.valueOf(quantity)));
        State after = new State(cash,
                money(before.realizedPnl().add(realized)),
                money(before.totalFees().add(commission).add(stamp)),
                positions);
        Fill fill = new Fill(Side.SELL, request.security(),
                request.signalDate(), request.signalTime(),
                request.executionDate(), request.executionTime(),
                request.referencePrice(), executionPrice, quantity, gross,
                commission, stamp, slippage, realized, cash, remaining);
        requireConservation(before, after, fill);
        return Result.filled(after, fill);
    }

    private static void requireTemporalBoundary(Request request) {
        if (!request.executionDate().isAfter(request.signalDate())
                || !request.executionTime().isAfter(request.signalTime())
                || !request.executionTime().equals(
                StrategyResearchModels.openInstant(request.executionDate()))
                || request.targetWeight().signum() < 0
                || request.targetWeight().compareTo(
                request.config().maxSinglePositionWeight()) > 0) {
            throw invalid("M2_PAPER_TEMPORAL_OR_WEIGHT_BOUNDARY_INVALID");
        }
    }

    private static BigDecimal equity(State state,
                                     Map<Security, BigDecimal> marks) {
        BigDecimal market = ZERO;
        for (Map.Entry<Security, Position> entry : state.positions().entrySet()) {
            BigDecimal mark = marks.get(entry.getKey());
            if (mark == null || mark.signum() <= 0) {
                throw invalid("M2_PAPER_MARK_MISSING");
            }
            market = market.add(mark.multiply(
                    BigDecimal.valueOf(entry.getValue().quantity())));
        }
        return money(state.cash().add(market));
    }

    private static int desiredQuantity(BigDecimal equity, BigDecimal weight,
                                       BigDecimal price, int lot) {
        if (weight.signum() == 0) {
            return 0;
        }
        return equity.multiply(weight).divide(price.multiply(
                        BigDecimal.valueOf(lot)), 0, RoundingMode.DOWN)
                .intValueExact() * lot;
    }

    private static int affordable(BigDecimal cash, int requested,
                                  BigDecimal price, BacktestConfig config) {
        int lot = config.boardLotSize();
        int quantity = requested / lot * lot;
        while (quantity >= lot) {
            BigDecimal gross = money(price.multiply(
                    BigDecimal.valueOf(quantity)));
            if (gross.add(commission(gross, config)).compareTo(cash) <= 0) {
                return quantity;
            }
            quantity -= lot;
        }
        return 0;
    }

    private static BigDecimal commission(BigDecimal gross,
                                         BacktestConfig config) {
        return money(gross.multiply(config.commissionRate())
                .max(config.minimumCommission()));
    }

    private static BigDecimal slipped(BigDecimal price, int bps, Side side) {
        BigDecimal fraction = BigDecimal.valueOf(bps).divide(BPS,
                RATIO_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal multiplier = side == Side.BUY
                ? ONE.add(fraction) : ONE.subtract(fraction);
        return price.multiply(multiplier).setScale(MONEY_SCALE,
                RoundingMode.HALF_EVEN);
    }

    private static void requireConservation(State before, State after,
                                            Fill fill) {
        BigDecimal expected = fill.side() == Side.BUY
                ? before.cash().subtract(fill.grossAmount())
                .subtract(fill.commission())
                : before.cash().add(fill.grossAmount())
                .subtract(fill.commission()).subtract(fill.stampDuty());
        if (money(expected).compareTo(after.cash()) != 0
                || after.cash().signum() < 0) {
            throw invalid("M2_PAPER_ACCOUNTING_INVARIANT_FAILED");
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
    }

    public enum Side { BUY, SELL }

    public record Position(
            int quantity,
            int availableQuantity,
            BigDecimal averageCost,
            BigDecimal lastPrice,
            LocalDate lastBuyDate
    ) {
        public Position {
            if (quantity <= 0 || availableQuantity < 0
                    || availableQuantity > quantity
                    || averageCost == null || averageCost.signum() <= 0
                    || lastPrice == null || lastPrice.signum() <= 0) {
                throw invalid("M2_PAPER_POSITION_INVALID");
            }
        }
    }

    public record State(
            BigDecimal cash,
            BigDecimal realizedPnl,
            BigDecimal totalFees,
            Map<Security, Position> positions
    ) {
        public State {
            if (cash == null || cash.signum() < 0 || realizedPnl == null
                    || totalFees == null || totalFees.signum() < 0) {
                throw invalid("M2_PAPER_STATE_INVALID");
            }
            positions = Collections.unmodifiableMap(new TreeMap<>(positions));
        }
    }

    public record Request(
            State state,
            BacktestConfig config,
            Side side,
            Security security,
            BigDecimal targetWeight,
            LocalDate signalDate,
            Instant signalTime,
            LocalDate executionDate,
            Instant executionTime,
            BigDecimal referencePrice,
            Map<Security, BigDecimal> marks
    ) {
        public Request {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(targetWeight, "targetWeight");
            Objects.requireNonNull(signalDate, "signalDate");
            Objects.requireNonNull(signalTime, "signalTime");
            Objects.requireNonNull(executionDate, "executionDate");
            Objects.requireNonNull(executionTime, "executionTime");
            Objects.requireNonNull(referencePrice, "referencePrice");
            marks = Collections.unmodifiableMap(new TreeMap<>(marks));
            if (referencePrice.signum() <= 0) {
                throw invalid("M2_PAPER_REFERENCE_PRICE_INVALID");
            }
        }
    }

    public record Fill(
            Side side,
            Security security,
            LocalDate signalDate,
            Instant signalTime,
            LocalDate executionDate,
            Instant executionTime,
            BigDecimal referencePrice,
            BigDecimal executionPrice,
            int quantity,
            BigDecimal grossAmount,
            BigDecimal commission,
            BigDecimal stampDuty,
            BigDecimal slippageCost,
            BigDecimal realizedPnl,
            BigDecimal cashAfter,
            int positionAfter
    ) {
    }

    public record Result(
            State state,
            Optional<Fill> fill,
            String rejectionReason
    ) {
        static Result filled(State state, Fill fill) {
            return new Result(state, Optional.of(fill), null);
        }

        static Result rejected(State state, String reason) {
            return new Result(state, Optional.empty(), reason);
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
