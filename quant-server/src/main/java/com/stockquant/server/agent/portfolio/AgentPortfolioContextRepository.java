package com.stockquant.server.agent.portfolio;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AgentPortfolioContextRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentPortfolioContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AccountRecord> findAccount(long accountId) {
        return jdbcTemplate.query("""
                SELECT id, name, initial_capital, cash, frozen_cash,
                       realized_pnl, total_fees
                FROM portfolio_accounts
                WHERE id = ?
                """, (resultSet, rowNum) -> new AccountRecord(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getBigDecimal("initial_capital"),
                resultSet.getBigDecimal("cash"),
                resultSet.getBigDecimal("frozen_cash"),
                resultSet.getBigDecimal("realized_pnl"),
                resultSet.getBigDecimal("total_fees")
        ), accountId).stream().findFirst();
    }

    public List<PositionRecord> findPositions(long accountId, LocalDate requestTradeDate) {
        return jdbcTemplate.query("""
                SELECT p.symbol, p.quantity, p.available_quantity, p.average_cost,
                       p.last_price, p.market_value, p.unrealized_pnl,
                       p.stop_loss, p.target_price, p.trailing_stop_pct,
                       p.highest_price, p.source_plan_id, p.last_buy_date,
                       mark.close AS mark_price, mark.trade_date AS mark_trade_date
                FROM positions p
                LEFT JOIN LATERAL (
                    SELECT d.close, d.trade_date
                    FROM daily_bars d
                    WHERE d.symbol = p.symbol
                      AND d.adjust_type = 'QFQ'
                      AND d.trade_date <= ?
                    ORDER BY d.trade_date DESC
                    LIMIT 1
                ) mark ON TRUE
                WHERE p.account_id = ?
                ORDER BY p.symbol
                """, (resultSet, rowNum) -> new PositionRecord(
                resultSet.getString("symbol"),
                resultSet.getInt("quantity"),
                resultSet.getInt("available_quantity"),
                resultSet.getBigDecimal("average_cost"),
                resultSet.getBigDecimal("last_price"),
                resultSet.getBigDecimal("market_value"),
                resultSet.getBigDecimal("unrealized_pnl"),
                resultSet.getBigDecimal("stop_loss"),
                resultSet.getBigDecimal("target_price"),
                resultSet.getBigDecimal("trailing_stop_pct"),
                resultSet.getBigDecimal("highest_price"),
                resultSet.getObject("source_plan_id", Long.class),
                resultSet.getObject("last_buy_date", LocalDate.class),
                resultSet.getBigDecimal("mark_price"),
                resultSet.getObject("mark_trade_date", LocalDate.class)
        ), requestTradeDate, accountId);
    }

    public List<PendingOrderRecord> findPendingOrders(long accountId) {
        return jdbcTemplate.query("""
                SELECT id, symbol, side, quantity, limit_price, gross_amount,
                       frozen_amount, frozen_quantity, trade_plan_id
                FROM manual_orders
                WHERE account_id = ?
                  AND status = 'PENDING_CONFIRM'
                ORDER BY id
                """, (resultSet, rowNum) -> new PendingOrderRecord(
                resultSet.getLong("id"),
                resultSet.getString("symbol"),
                resultSet.getString("side"),
                resultSet.getInt("quantity"),
                resultSet.getBigDecimal("limit_price"),
                resultSet.getBigDecimal("gross_amount"),
                resultSet.getBigDecimal("frozen_amount"),
                resultSet.getInt("frozen_quantity"),
                resultSet.getObject("trade_plan_id", Long.class)
        ), accountId);
    }

    public List<EquitySnapshotRecord> findEquityHistoryBefore(
            long accountId,
            LocalDate requestTradeDate
    ) {
        return jdbcTemplate.query("""
                SELECT snapshot_date, total_asset
                FROM account_equity_snapshots
                WHERE account_id = ?
                  AND snapshot_date < ?
                ORDER BY snapshot_date DESC
                """, (resultSet, rowNum) -> new EquitySnapshotRecord(
                resultSet.getObject("snapshot_date", LocalDate.class),
                resultSet.getBigDecimal("total_asset")
        ), accountId, requestTradeDate);
    }

    public Map<String, String> findRiskSettings() {
        List<Map.Entry<String, String>> values = jdbcTemplate.query("""
                SELECT setting_key, setting_value
                FROM app_settings
                WHERE setting_key IN (
                    'portfolio.max_positions',
                    'portfolio.max_position_weight'
                )
                ORDER BY setting_key
                """, (resultSet, rowNum) -> Map.entry(
                resultSet.getString("setting_key"),
                resultSet.getString("setting_value")
        ));
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(result);
    }

    public record AccountRecord(
            long id,
            String name,
            BigDecimal initialCapital,
            BigDecimal cash,
            BigDecimal frozenCash,
            BigDecimal realizedPnl,
            BigDecimal totalFees
    ) {
    }

    public record PositionRecord(
            String symbol,
            int quantity,
            int availableQuantity,
            BigDecimal averageCost,
            BigDecimal databaseLastPrice,
            BigDecimal databaseMarketValue,
            BigDecimal databaseUnrealizedPnl,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            BigDecimal trailingStopPct,
            BigDecimal highestPrice,
            Long sourcePlanId,
            LocalDate lastBuyDate,
            BigDecimal markPrice,
            LocalDate markTradeDate
    ) {
    }

    public record PendingOrderRecord(
            long orderId,
            String symbol,
            String side,
            int quantity,
            BigDecimal limitPrice,
            BigDecimal grossAmount,
            BigDecimal frozenAmount,
            int frozenQuantity,
            Long tradePlanId
    ) {
    }

    public record EquitySnapshotRecord(LocalDate snapshotDate, BigDecimal totalAsset) {
    }
}
