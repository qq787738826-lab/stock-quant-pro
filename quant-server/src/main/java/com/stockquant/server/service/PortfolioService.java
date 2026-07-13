package com.stockquant.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PortfolioService {

    private static final long ACCOUNT_ID = 1L;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final JdbcTemplate jdbc;

    public PortfolioService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OrderRequest(
            String symbol,
            String side,
            Integer quantity,
            BigDecimal price,
            Long tradePlanId
    ) {}

    public record PlanOrderRequest(Integer quantity, BigDecimal price) {}

    @Transactional
    public Map<String, Object> summary() {
        settleTPlusOne();
        refreshPositionPrices(false);
        Map<String, Object> account = accountRow();
        List<Map<String, Object>> positions = positionRows();
        BigDecimal marketValue = sum(positions, "market_value");
        BigDecimal unrealizedPnl = sum(positions, "unrealized_pnl");
        BigDecimal cash = decimal(account.get("cash"));
        BigDecimal frozenCash = decimal(account.get("frozen_cash"));
        BigDecimal totalAsset = cash.add(marketValue);
        BigDecimal initialCapital = decimal(account.get("initial_capital"));
        BigDecimal totalReturn = initialCapital.signum() == 0
                ? BigDecimal.ZERO
                : totalAsset.subtract(initialCapital)
                .divide(initialCapital, 8, RoundingMode.HALF_UP);

        saveSnapshot(cash, frozenCash, marketValue, totalAsset,
                decimal(account.get("realized_pnl")), unrealizedPnl, totalReturn);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", ACCOUNT_ID);
        result.put("accountName", account.get("name"));
        result.put("initialCapital", money(initialCapital));
        result.put("cash", money(cash));
        result.put("frozenCash", money(frozenCash));
        result.put("availableCash", money(cash.subtract(frozenCash)));
        result.put("marketValue", money(marketValue));
        result.put("totalAsset", money(totalAsset));
        result.put("realizedPnl", money(decimal(account.get("realized_pnl"))));
        result.put("unrealizedPnl", money(unrealizedPnl));
        result.put("totalFees", money(decimal(account.get("total_fees"))));
        result.put("totalReturn", totalReturn);
        result.put("positionCount", positions.size());
        result.put("maxPositions", settingInt("portfolio.max_positions", 5));
        result.put("maxPositionWeight", settingDecimal("portfolio.max_position_weight", "0.20"));
        result.put("positions", positions);
        return result;
    }

    public List<Map<String, Object>> orders() {
        return jdbc.queryForList("""
                select o.*, coalesce(o.name, s.name, o.symbol) as display_name,
                       p.status as plan_status
                from manual_orders o
                left join securities s on s.symbol=o.symbol
                left join trade_plans p on p.id=o.trade_plan_id
                order by o.created_at desc, o.id desc
                limit 200
                """);
    }

    public List<Map<String, Object>> trades() {
        return jdbc.queryForList("""
                select * from simulated_trades
                order by trade_time desc, id desc
                limit 300
                """);
    }

    public List<Map<String, Object>> plans(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return jdbc.queryForList("""
                    select p.*, s.latest_price, s.latest_trade_date
                    from trade_plans p
                    left join securities s on s.symbol=p.symbol
                    order by p.created_at desc, p.id desc
                    limit 200
                    """);
        }
        return jdbc.queryForList("""
                select p.*, s.latest_price, s.latest_trade_date
                from trade_plans p
                left join securities s on s.symbol=p.symbol
                where p.status=?
                order by p.created_at desc, p.id desc
                limit 200
                """, status.toUpperCase(Locale.ROOT));
    }

    @Transactional
    public long createPlanFromScan(long scanTaskId, String symbol) {
        validateSymbol(symbol);
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id from trade_plans
                where symbol=? and status in ('ACTIVE','ORDER_CREATED','OPEN')
                order by id desc limit 1
                """, symbol);
        if (!existing.isEmpty()) {
            return longValue(existing.get(0).get("id"));
        }

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.symbol, r.name, r.trade_date, r.score,
                       r.buy_low, r.buy_high, r.stop_loss, r.target1, r.target2,
                       r.suggested_weight, r.summary, r.eligible
                from market_scan_results r
                join market_scan_tasks t on t.id=r.task_id
                where r.task_id=? and r.symbol=? and t.status='COMPLETED'
                """, scanTaskId, symbol);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("扫描结果不存在或任务尚未完成");
        }
        Map<String, Object> row = rows.get(0);
        if (!bool(row.get("eligible"))) {
            throw new IllegalArgumentException("该股票未通过严格候选过滤，不能直接加入交易计划");
        }

        Long id = jdbc.queryForObject("""
                insert into trade_plans(
                    symbol, name, plan_date, score, buy_low, buy_high,
                    stop_loss, target1, target2, suggested_weight,
                    valid_days, status, rationale, source_task_id,
                    source_result_id, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 5, 'ACTIVE', ?, ?, ?, now(), now())
                returning id
                """, Long.class,
                symbol,
                row.get("name"),
                row.get("trade_date"),
                row.get("score"),
                row.get("buy_low"),
                row.get("buy_high"),
                row.get("stop_loss"),
                row.get("target1"),
                row.get("target2"),
                row.get("suggested_weight"),
                row.get("summary"),
                scanTaskId,
                row.get("id")
        );
        if (id == null) {
            throw new IllegalStateException("创建交易计划失败");
        }
        return id;
    }

    @Transactional
    public long createOrder(OrderRequest request) {
        return createOrderInternal(request.symbol(), request.side(), request.quantity(), request.price(), request.tradePlanId());
    }

    @Transactional
    public long createOrderFromPlan(long planId, PlanOrderRequest request) {
        Map<String, Object> plan = singleRow("select * from trade_plans where id=?", planId, "交易计划不存在");
        String status = string(plan.get("status"));
        if (!List.of("ACTIVE", "ORDER_CREATED").contains(status)) {
            throw new IllegalArgumentException("只有有效交易计划可以生成买入委托");
        }
        String symbol = string(plan.get("symbol"));
        BigDecimal price = request != null && request.price() != null
                ? request.price()
                : firstPositive(decimal(plan.get("buy_high")), latestPrice(symbol));
        Integer quantity = request == null ? null : request.quantity();
        if (quantity == null || quantity <= 0) {
            BigDecimal weight = firstPositive(decimal(plan.get("suggested_weight")), settingDecimal("portfolio.max_position_weight", "0.20"));
            BigDecimal totalAsset = currentTotalAsset();
            quantity = totalAsset.multiply(weight)
                    .divide(price, 0, RoundingMode.DOWN)
                    .intValue() / 100 * 100;
            if (quantity < 100) {
                throw new IllegalArgumentException("账户资金不足以按计划生成100股委托");
            }
        }
        long orderId = createOrderInternal(symbol, "BUY", quantity, price, planId);
        jdbc.update("update trade_plans set status='ORDER_CREATED', quantity_hint=?, updated_at=now() where id=?",
                quantity, planId);
        return orderId;
    }

    @Transactional
    public void cancelPlan(long planId) {
        List<Long> pendingOrders = jdbc.queryForList("""
                select id from manual_orders
                where trade_plan_id=? and status='PENDING_CONFIRM'
                order by id
                """, Long.class, planId);
        for (Long orderId : pendingOrders) {
            cancelOrder(orderId);
        }
        int changed = jdbc.update("""
                update trade_plans set status='CANCELLED', updated_at=now()
                where id=? and status in ('ACTIVE','ORDER_CREATED')
                """, planId);
        if (changed == 0) {
            throw new IllegalArgumentException("交易计划不存在或当前状态不能取消");
        }
    }

    @Transactional
    public Map<String, Object> confirm(long orderId) {
        Map<String, Object> order = singleRow("select * from manual_orders where id=? for update", orderId, "委托不存在");
        if (!"PENDING_CONFIRM".equals(string(order.get("status")))) {
            throw new IllegalArgumentException("只有待确认委托可以模拟成交");
        }

        String side = string(order.get("side"));
        if ("BUY".equals(side)) {
            executeBuy(order);
        } else if ("SELL".equals(side)) {
            executeSell(order);
        } else {
            throw new IllegalArgumentException("不支持的委托方向：" + side);
        }
        snapshot();
        return singleRow("select * from manual_orders where id=?", orderId, "委托不存在");
    }

    @Transactional
    public void cancelOrder(long orderId) {
        Map<String, Object> order = singleRow("select * from manual_orders where id=? for update", orderId, "委托不存在");
        if (!"PENDING_CONFIRM".equals(string(order.get("status")))) {
            throw new IllegalArgumentException("只有待确认委托可以撤销");
        }
        if ("BUY".equals(string(order.get("side")))) {
            jdbc.update("""
                    update portfolio_accounts
                    set frozen_cash=greatest(0, frozen_cash-?), updated_at=now()
                    where id=?
                    """, decimal(order.get("frozen_amount")), ACCOUNT_ID);
        } else {
            jdbc.update("""
                    update positions
                    set available_quantity=least(quantity, available_quantity+?), updated_at=now()
                    where account_id=? and symbol=?
                    """, intValue(order.get("frozen_quantity")), ACCOUNT_ID, order.get("symbol"));
        }
        jdbc.update("""
                update manual_orders
                set status='CANCELLED', cancelled_at=now(), frozen_amount=0, frozen_quantity=0
                where id=?
                """, orderId);
        Long planId = nullableLong(order.get("trade_plan_id"));
        if (planId != null) {
            jdbc.update("""
                    update trade_plans set status='ACTIVE', updated_at=now()
                    where id=? and status='ORDER_CREATED'
                    """, planId);
        }
    }

    @Transactional
    public Map<String, Object> refreshAndCheckRisk() {
        settleTPlusOne();
        int refreshed = refreshPositionPrices(true);
        List<Map<String, Object>> events = checkRiskEvents();
        snapshot();
        return Map.of("refreshedPositions", refreshed, "riskEvents", events);
    }

    public List<Map<String, Object>> riskEvents(boolean resolved) {
        return jdbc.queryForList("""
                select * from risk_events
                where account_id=? and resolved=?
                order by event_time desc, id desc
                limit 200
                """, ACCOUNT_ID, resolved);
    }

    @Transactional
    public void resolveRiskEvent(long id) {
        int changed = jdbc.update("""
                update risk_events set resolved=true, resolved_at=now()
                where id=? and account_id=?
                """, id, ACCOUNT_ID);
        if (changed == 0) {
            throw new IllegalArgumentException("风险事件不存在");
        }
    }

    public List<Map<String, Object>> equityCurve(int days) {
        int safeDays = Math.max(7, Math.min(days, 1000));
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList("""
                select snapshot_date, cash, frozen_cash, market_value,
                       total_asset, realized_pnl, unrealized_pnl, total_return
                from account_equity_snapshots
                where account_id=?
                order by snapshot_date desc
                limit ?
                """, ACCOUNT_ID, safeDays));
        Collections.reverse(rows);
        return rows;
    }

    @Transactional
    public Map<String, Object> snapshot() {
        settleTPlusOne();
        refreshPositionPrices(false);
        Map<String, Object> account = accountRow();
        List<Map<String, Object>> positions = positionRows();
        BigDecimal marketValue = sum(positions, "market_value");
        BigDecimal unrealizedPnl = sum(positions, "unrealized_pnl");
        BigDecimal cash = decimal(account.get("cash"));
        BigDecimal frozenCash = decimal(account.get("frozen_cash"));
        BigDecimal totalAsset = cash.add(marketValue);
        BigDecimal initialCapital = decimal(account.get("initial_capital"));
        BigDecimal totalReturn = initialCapital.signum() == 0
                ? BigDecimal.ZERO
                : totalAsset.subtract(initialCapital).divide(initialCapital, 8, RoundingMode.HALF_UP);
        saveSnapshot(cash, frozenCash, marketValue, totalAsset,
                decimal(account.get("realized_pnl")), unrealizedPnl, totalReturn);
        return Map.of("snapshotDate", LocalDate.now(), "totalAsset", money(totalAsset));
    }

    private long createOrderInternal(String rawSymbol, String rawSide, Integer quantity, BigDecimal price, Long planId) {
        String symbol = validateSymbol(rawSymbol);
        String side = rawSide == null ? "" : rawSide.toUpperCase(Locale.ROOT);
        if (!List.of("BUY", "SELL").contains(side)) {
            throw new IllegalArgumentException("委托方向必须是BUY或SELL");
        }
        if (quantity == null || quantity <= 0 || quantity % 100 != 0) {
            throw new IllegalArgumentException("A股委托数量必须是100股的整数倍");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("委托价格必须大于0");
        }
        price = price.setScale(4, RoundingMode.HALF_UP);

        String name = securityName(symbol);
        BigDecimal gross = money(price.multiply(BigDecimal.valueOf(quantity)));
        Fee fee = fee(side, gross);
        BigDecimal frozenAmount = ZERO;
        int frozenQuantity = 0;

        if ("BUY".equals(side)) {
            validateBuyRisk(symbol, gross, fee.total());
            frozenAmount = money(gross.add(fee.total()));
            jdbc.update("""
                    update portfolio_accounts
                    set frozen_cash=frozen_cash+?, updated_at=now()
                    where id=?
                    """, frozenAmount, ACCOUNT_ID);
        } else {
            Map<String, Object> position = singleRow("""
                    select * from positions where account_id=? and symbol=? for update
                    """, ACCOUNT_ID, symbol, "没有可卖出的持仓");
            int available = intValue(position.get("available_quantity"));
            if (available < quantity) {
                throw new IllegalArgumentException("可卖数量不足，当前可卖：" + available);
            }
            frozenQuantity = quantity;
            jdbc.update("""
                    update positions
                    set available_quantity=available_quantity-?, updated_at=now()
                    where account_id=? and symbol=?
                    """, quantity, ACCOUNT_ID, symbol);
        }

        String clientNo = "SIM-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbc.queryForObject("""
                insert into manual_orders(
                    account_id, symbol, name, side, quantity, limit_price,
                    trade_plan_id, client_order_no, status, gross_amount,
                    commission, stamp_duty, transfer_fee, net_amount,
                    frozen_amount, frozen_quantity, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_CONFIRM', ?, ?, ?, ?, ?, ?, ?, now())
                returning id
                """, Long.class,
                ACCOUNT_ID, symbol, name, side, quantity, price,
                planId, clientNo, gross,
                fee.commission(), fee.stampDuty(), fee.transferFee(),
                "BUY".equals(side) ? gross.add(fee.total()) : gross.subtract(fee.total()),
                frozenAmount, frozenQuantity
        );
        if (id == null) {
            throw new IllegalStateException("创建模拟委托失败");
        }
        return id;
    }

    private void validateBuyRisk(String symbol, BigDecimal gross, BigDecimal fees) {
        Map<String, Object> account = accountRowForUpdate();
        BigDecimal cash = decimal(account.get("cash"));
        BigDecimal frozenCash = decimal(account.get("frozen_cash"));
        BigDecimal required = gross.add(fees);
        if (cash.subtract(frozenCash).compareTo(required) < 0) {
            throw new IllegalArgumentException("可用资金不足，需要：" + money(required));
        }

        Integer pendingSame = jdbc.queryForObject("""
                select count(*) from manual_orders
                where account_id=? and symbol=? and side='BUY' and status='PENDING_CONFIRM'
                """, Integer.class, ACCOUNT_ID, symbol);
        if (pendingSame != null && pendingSame > 0) {
            throw new IllegalArgumentException("该股票已有待确认买入委托");
        }

        Integer positionExists = jdbc.queryForObject("""
                select count(*) from positions where account_id=? and symbol=?
                """, Integer.class, ACCOUNT_ID, symbol);
        if (positionExists == null || positionExists == 0) {
            Integer distinctCount = jdbc.queryForObject("""
                    select count(*) from (
                      select symbol from positions where account_id=?
                      union
                      select symbol from manual_orders
                      where account_id=? and side='BUY' and status='PENDING_CONFIRM'
                    ) x
                    """, Integer.class, ACCOUNT_ID, ACCOUNT_ID);
            int maxPositions = settingInt("portfolio.max_positions", 5);
            if (distinctCount != null && distinctCount >= maxPositions) {
                throw new IllegalArgumentException("最多同时持有或等待买入" + maxPositions + "只股票");
            }
        }

        BigDecimal totalAsset = currentTotalAsset();
        BigDecimal maxWeight = settingDecimal("portfolio.max_position_weight", "0.20");
        BigDecimal maxValue = totalAsset.multiply(maxWeight);
        BigDecimal existingValue = nullableDecimal(jdbc.queryForObject("""
                select coalesce(sum(market_value),0) from positions
                where account_id=? and symbol=?
                """, BigDecimal.class, ACCOUNT_ID, symbol));
        BigDecimal pendingValue = nullableDecimal(jdbc.queryForObject("""
                select coalesce(sum(gross_amount),0) from manual_orders
                where account_id=? and symbol=? and side='BUY' and status='PENDING_CONFIRM'
                """, BigDecimal.class, ACCOUNT_ID, symbol));
        if (existingValue.add(pendingValue).add(gross).compareTo(maxValue) > 0) {
            throw new IllegalArgumentException("单股仓位超过总资产" + maxWeight.multiply(HUNDRED).stripTrailingZeros().toPlainString() + "%限制");
        }
    }

    private void executeBuy(Map<String, Object> order) {
        long orderId = longValue(order.get("id"));
        String symbol = string(order.get("symbol"));
        int quantity = intValue(order.get("quantity"));
        BigDecimal price = decimal(order.get("limit_price"));
        BigDecimal gross = money(price.multiply(BigDecimal.valueOf(quantity)));
        Fee fee = fee("BUY", gross);
        BigDecimal net = money(gross.add(fee.total()));
        BigDecimal reserved = decimal(order.get("frozen_amount"));

        Map<String, Object> account = accountRowForUpdate();
        if (decimal(account.get("cash")).compareTo(net) < 0) {
            throw new IllegalArgumentException("确认时账户现金不足，请撤销委托后重新创建");
        }

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select * from positions where account_id=? and symbol=? for update
                """, ACCOUNT_ID, symbol);
        Long planId = nullableLong(order.get("trade_plan_id"));
        BigDecimal stopLoss = null;
        BigDecimal target = null;
        if (planId != null) {
            List<Map<String, Object>> plans = jdbc.queryForList("select * from trade_plans where id=?", planId);
            if (!plans.isEmpty()) {
                stopLoss = decimalOrNull(plans.get(0).get("stop_loss"));
                target = decimalOrNull(plans.get(0).get("target1"));
            }
        }
        BigDecimal trailing = settingDecimal("portfolio.default_trailing_stop_pct", "0.04");

        if (existing.isEmpty()) {
            BigDecimal averageCost = net.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
            jdbc.update("""
                    insert into positions(
                        account_id, symbol, quantity, available_quantity,
                        average_cost, last_price, stop_loss, target_price,
                        trailing_stop_pct, highest_price, source_plan_id,
                        opened_at, last_buy_date, updated_at
                    ) values (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, now(), current_date, now())
                    """, ACCOUNT_ID, symbol, quantity, averageCost, price,
                    stopLoss, target, trailing, price, planId);
        } else {
            Map<String, Object> position = existing.get(0);
            int oldQuantity = intValue(position.get("quantity"));
            BigDecimal oldCost = decimal(position.get("average_cost"));
            BigDecimal newCost = oldCost.multiply(BigDecimal.valueOf(oldQuantity)).add(net)
                    .divide(BigDecimal.valueOf(oldQuantity + quantity), 4, RoundingMode.HALF_UP);
            jdbc.update("""
                    update positions set
                        quantity=quantity+?, average_cost=?, last_price=?,
                        stop_loss=coalesce(?, stop_loss),
                        target_price=coalesce(?, target_price),
                        trailing_stop_pct=?,
                        highest_price=greatest(coalesce(highest_price,0), ?),
                        source_plan_id=coalesce(?, source_plan_id),
                        last_buy_date=current_date, updated_at=now()
                    where account_id=? and symbol=?
                    """, quantity, newCost, price, stopLoss, target, trailing,
                    price, planId, ACCOUNT_ID, symbol);
        }

        jdbc.update("""
                update portfolio_accounts
                set cash=cash-?, frozen_cash=greatest(0, frozen_cash-?),
                    total_fees=total_fees+?, updated_at=now()
                where id=?
                """, net, reserved, fee.total(), ACCOUNT_ID);

        completeOrder(order, planId, price, quantity, gross, fee, net, BigDecimal.ZERO);
        if (planId != null) {
            jdbc.update("update trade_plans set status='OPEN', updated_at=now() where id=?", planId);
        }
    }

    private void executeSell(Map<String, Object> order) {
        long orderId = longValue(order.get("id"));
        String symbol = string(order.get("symbol"));
        int quantity = intValue(order.get("quantity"));
        BigDecimal price = decimal(order.get("limit_price"));
        Map<String, Object> position = singleRow("""
                select * from positions where account_id=? and symbol=? for update
                """, ACCOUNT_ID, symbol, "卖出确认失败：持仓不存在");
        int held = intValue(position.get("quantity"));
        if (held < quantity) {
            throw new IllegalArgumentException("持仓数量不足，请撤销委托后重新创建");
        }

        BigDecimal gross = money(price.multiply(BigDecimal.valueOf(quantity)));
        Fee fee = fee("SELL", gross);
        BigDecimal net = money(gross.subtract(fee.total()));
        BigDecimal cost = decimal(position.get("average_cost")).multiply(BigDecimal.valueOf(quantity));
        BigDecimal realized = money(net.subtract(cost));
        int remaining = held - quantity;
        Long sourcePlanId = nullableLong(position.get("source_plan_id"));

        if (remaining == 0) {
            jdbc.update("delete from positions where account_id=? and symbol=?", ACCOUNT_ID, symbol);
        } else {
            jdbc.update("""
                    update positions
                    set quantity=?, available_quantity=least(available_quantity, ?),
                        last_price=?, updated_at=now()
                    where account_id=? and symbol=?
                    """, remaining, remaining, price, ACCOUNT_ID, symbol);
        }

        jdbc.update("""
                update portfolio_accounts
                set cash=cash+?, realized_pnl=realized_pnl+?,
                    total_fees=total_fees+?, updated_at=now()
                where id=?
                """, net, realized, fee.total(), ACCOUNT_ID);

        completeOrder(order, sourcePlanId, price, quantity, gross, fee, net, realized);
        if (sourcePlanId != null && remaining == 0) {
            jdbc.update("update trade_plans set status='CLOSED', updated_at=now() where id=?", sourcePlanId);
        }
    }

    private void completeOrder(
            Map<String, Object> order,
            Long effectivePlanId,
            BigDecimal price,
            int quantity,
            BigDecimal gross,
            Fee fee,
            BigDecimal net,
            BigDecimal realized
    ) {
        long orderId = longValue(order.get("id"));
        jdbc.update("""
                update manual_orders set
                    status='FILLED', confirmed_at=now(), executed_at=now(),
                    trade_plan_id=coalesce(trade_plan_id, ?),
                    filled_quantity=?, filled_price=?, gross_amount=?,
                    commission=?, stamp_duty=?, transfer_fee=?, net_amount=?,
                    frozen_amount=0, frozen_quantity=0
                where id=?
                """, effectivePlanId, quantity, price, gross, fee.commission(), fee.stampDuty(),
                fee.transferFee(), net, orderId);

        jdbc.update("""
                insert into simulated_trades(
                    order_id, account_id, trade_plan_id, symbol, name,
                    side, quantity, price, gross_amount, commission,
                    stamp_duty, transfer_fee, net_amount, realized_pnl,
                    trade_date, trade_time
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_date, now())
                on conflict(order_id) do nothing
                """, orderId, ACCOUNT_ID, effectivePlanId,
                order.get("symbol"), order.get("name"), order.get("side"),
                quantity, price, gross, fee.commission(), fee.stampDuty(),
                fee.transferFee(), net, realized);
    }

    private int settleTPlusOne() {
        LocalDate marketDate = jdbc.queryForObject(
                "select coalesce(max(trade_date), current_date) from daily_bars",
                LocalDate.class
        );
        if (marketDate == null) {
            marketDate = LocalDate.now();
        }
        return jdbc.update("""
                update positions p
                set available_quantity=greatest(
                        0,
                        p.quantity-coalesce((
                          select sum(o.frozen_quantity)
                          from manual_orders o
                          where o.account_id=p.account_id
                            and o.symbol=p.symbol
                            and o.side='SELL'
                            and o.status='PENDING_CONFIRM'
                        ),0)
                    ),
                    updated_at=now()
                where p.account_id=?
                  and p.last_buy_date is not null
                  and p.last_buy_date<?
                """, ACCOUNT_ID, marketDate);
    }

    private int refreshPositionPrices(boolean requirePrice) {
        List<Map<String, Object>> positions = jdbc.queryForList(
                "select symbol from positions where account_id=?", ACCOUNT_ID);
        int refreshed = 0;
        for (Map<String, Object> row : positions) {
            String symbol = string(row.get("symbol"));
            List<Map<String, Object>> bars = jdbc.queryForList("""
                    select close, trade_date from daily_bars
                    where symbol=? and adjust_type='QFQ'
                    order by trade_date desc limit 1
                    """, symbol);
            if (bars.isEmpty()) {
                if (requirePrice) {
                    upsertRiskEvent(symbol, "DATA_MISSING", "MEDIUM",
                            "持仓缺少最新本地K线，请先更新行情", null, null, LocalDate.now());
                }
                continue;
            }
            BigDecimal price = decimal(bars.get(0).get("close"));
            jdbc.update("""
                    update positions set last_price=?,
                        highest_price=greatest(coalesce(highest_price,0), ?),
                        updated_at=now()
                    where account_id=? and symbol=?
                    """, price, price, ACCOUNT_ID, symbol);
            refreshed++;
        }
        return refreshed;
    }

    private List<Map<String, Object>> checkRiskEvents() {
        List<Map<String, Object>> positions = positionRows();
        LocalDate tradeDate = jdbc.queryForObject(
                "select coalesce(max(trade_date), current_date) from daily_bars",
                LocalDate.class
        );
        if (tradeDate == null) tradeDate = LocalDate.now();

        for (Map<String, Object> position : positions) {
            String symbol = string(position.get("symbol"));
            BigDecimal price = decimal(position.get("last_price"));
            BigDecimal stopLoss = decimalOrNull(position.get("stop_loss"));
            BigDecimal target = decimalOrNull(position.get("target_price"));
            BigDecimal highest = firstPositive(decimal(position.get("highest_price")), price);
            BigDecimal trailingPct = firstPositive(decimal(position.get("trailing_stop_pct")),
                    settingDecimal("portfolio.default_trailing_stop_pct", "0.04"));
            BigDecimal trailingPrice = highest.multiply(BigDecimal.ONE.subtract(trailingPct))
                    .setScale(4, RoundingMode.HALF_UP);

            if (stopLoss != null && price.compareTo(stopLoss) <= 0) {
                upsertRiskEvent(symbol, "STOP_LOSS", "HIGH",
                        "当前价格已触发止损线", price, stopLoss, tradeDate);
            }
            if (target != null && price.compareTo(target) >= 0) {
                upsertRiskEvent(symbol, "TAKE_PROFIT", "MEDIUM",
                        "当前价格达到第一目标位，可考虑分批止盈", price, target, tradeDate);
            }
            BigDecimal averageCost = decimal(position.get("average_cost"));
            if (highest.compareTo(averageCost) > 0 && price.compareTo(trailingPrice) <= 0) {
                upsertRiskEvent(symbol, "TRAILING_STOP", "HIGH",
                        "价格从持仓最高价回落并触发移动止盈线", price, trailingPrice, tradeDate);
            }
        }
        return riskEvents(false);
    }

    private void upsertRiskEvent(
            String symbol,
            String type,
            String level,
            String message,
            BigDecimal currentPrice,
            BigDecimal triggerPrice,
            LocalDate tradeDate
    ) {
        String key = ACCOUNT_ID + ":" + symbol + ":" + type + ":" + tradeDate;
        jdbc.update("""
                insert into risk_events(
                    account_id, event_key, event_time, level, event_type,
                    symbol, message, current_price, trigger_price, resolved
                ) values (?, ?, now(), ?, ?, ?, ?, ?, ?, false)
                on conflict(event_key) do update set
                    event_time=excluded.event_time,
                    level=excluded.level,
                    message=excluded.message,
                    current_price=excluded.current_price,
                    trigger_price=excluded.trigger_price,
                    resolved=risk_events.resolved,
                    resolved_at=risk_events.resolved_at
                """, ACCOUNT_ID, key, level, type, symbol, message, currentPrice, triggerPrice);
    }

    private void saveSnapshot(
            BigDecimal cash,
            BigDecimal frozenCash,
            BigDecimal marketValue,
            BigDecimal totalAsset,
            BigDecimal realizedPnl,
            BigDecimal unrealizedPnl,
            BigDecimal totalReturn
    ) {
        jdbc.update("""
                insert into account_equity_snapshots(
                    account_id, snapshot_date, cash, frozen_cash,
                    market_value, total_asset, realized_pnl,
                    unrealized_pnl, total_return, created_at, updated_at
                ) values (?, current_date, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict(account_id, snapshot_date) do update set
                    cash=excluded.cash,
                    frozen_cash=excluded.frozen_cash,
                    market_value=excluded.market_value,
                    total_asset=excluded.total_asset,
                    realized_pnl=excluded.realized_pnl,
                    unrealized_pnl=excluded.unrealized_pnl,
                    total_return=excluded.total_return,
                    updated_at=now()
                """, ACCOUNT_ID, cash, frozenCash, marketValue, totalAsset,
                realizedPnl, unrealizedPnl, totalReturn);
    }

    private Map<String, Object> accountRow() {
        return singleRow("select * from portfolio_accounts where id=?", ACCOUNT_ID, "模拟账户不存在");
    }

    private Map<String, Object> accountRowForUpdate() {
        return singleRow("select * from portfolio_accounts where id=? for update", ACCOUNT_ID, "模拟账户不存在");
    }

    private List<Map<String, Object>> positionRows() {
        return jdbc.queryForList("""
                select p.*, coalesce(s.name, p.symbol) as name,
                       case when p.highest_price is null then null
                            else round(p.highest_price * (1-p.trailing_stop_pct), 4)
                       end as trailing_stop_price,
                       case when p.average_cost=0 then 0
                            else round((p.last_price-p.average_cost)/p.average_cost, 8)
                       end as pnl_rate,
                       tp.status as plan_status
                from positions p
                left join securities s on s.symbol=p.symbol
                left join trade_plans tp on tp.id=p.source_plan_id
                where p.account_id=?
                order by p.market_value desc, p.symbol
                """, ACCOUNT_ID);
    }

    private BigDecimal currentTotalAsset() {
        Map<String, Object> account = accountRow();
        BigDecimal market = nullableDecimal(jdbc.queryForObject(
                "select coalesce(sum(market_value),0) from positions where account_id=?",
                BigDecimal.class, ACCOUNT_ID));
        return decimal(account.get("cash")).add(market);
    }

    private BigDecimal latestPrice(String symbol) {
        List<BigDecimal> rows = jdbc.queryForList("""
                select close from daily_bars
                where symbol=? and adjust_type='QFQ'
                order by trade_date desc limit 1
                """, BigDecimal.class, symbol);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        List<BigDecimal> security = jdbc.queryForList(
                "select latest_price from securities where symbol=? and latest_price is not null",
                BigDecimal.class, symbol);
        if (!security.isEmpty()) {
            return security.get(0);
        }
        throw new IllegalArgumentException("没有可用最新价格，请先更新行情");
    }

    private String securityName(String symbol) {
        List<String> names = jdbc.queryForList(
                "select name from securities where symbol=?", String.class, symbol);
        return names.isEmpty() ? symbol : names.get(0);
    }

    private Fee fee(String side, BigDecimal gross) {
        BigDecimal commissionRate = settingDecimal("portfolio.commission_rate", "0.0003");
        BigDecimal minimumCommission = settingDecimal("portfolio.minimum_commission", "5");
        BigDecimal transferRate = settingDecimal("portfolio.transfer_fee_rate", "0.00001");
        BigDecimal stampRate = "SELL".equals(side)
                ? settingDecimal("portfolio.stamp_duty_rate", "0.0005")
                : BigDecimal.ZERO;
        BigDecimal commission = gross.multiply(commissionRate).max(minimumCommission)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal stamp = gross.multiply(stampRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal transfer = gross.multiply(transferRate).setScale(2, RoundingMode.HALF_UP);
        return new Fee(commission, stamp, transfer);
    }

    private int settingInt(String key, int fallback) {
        List<String> rows = jdbc.queryForList(
                "select setting_value from app_settings where setting_key=?", String.class, key);
        return rows.isEmpty() ? fallback : Integer.parseInt(rows.get(0));
    }

    private BigDecimal settingDecimal(String key, String fallback) {
        List<String> rows = jdbc.queryForList(
                "select setting_value from app_settings where setting_key=?", String.class, key);
        return new BigDecimal(rows.isEmpty() ? fallback : rows.get(0));
    }

    private Map<String, Object> singleRow(String sql, Object arg, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arg);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.get(0);
    }

    private Map<String, Object> singleRow(String sql, Object arg1, Object arg2, String message) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, arg1, arg2);
        if (rows.isEmpty()) throw new IllegalArgumentException(message);
        return rows.get(0);
    }

    private String validateSymbol(String symbol) {
        if (symbol == null || !symbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("股票代码必须是6位数字");
        }
        return symbol;
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(row -> decimal(row.get(key))).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || "".equals(value)) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal decimalOrNull(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal nullableDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : new BigDecimal(String.valueOf(value)).longValue();
    }

    private static long longValue(Object value) {
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    private static int intValue(Object value) {
        return value == null ? 0 : new BigDecimal(String.valueOf(value)).intValue();
    }

    private static boolean bool(Object value) {
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal second) {
        return first != null && first.signum() > 0 ? first : second;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record Fee(BigDecimal commission, BigDecimal stampDuty, BigDecimal transferFee) {
        BigDecimal total() {
            return commission.add(stampDuty).add(transferFee);
        }
    }
}
