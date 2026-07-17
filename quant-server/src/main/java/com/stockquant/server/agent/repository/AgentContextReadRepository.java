package com.stockquant.server.agent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentContextReadRepository {

    public static final int MAX_DAILY_BARS = 61;
    public static final String ADJUST_TYPE = "QFQ";

    private final JdbcTemplate jdbcTemplate;

    public AgentContextReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SecurityRecord> findSecurity(String symbol) {
        List<SecurityRecord> rows = jdbcTemplate.query("""
                SELECT symbol, name, exchange, board, industry, list_date,
                       is_st, is_active, data_source, updated_at
                FROM securities
                WHERE symbol = ?
                """, (resultSet, rowNum) -> new SecurityRecord(
                resultSet.getString("symbol"),
                resultSet.getString("name"),
                resultSet.getString("exchange"),
                resultSet.getString("board"),
                resultSet.getString("industry"),
                resultSet.getObject("list_date", LocalDate.class),
                resultSet.getBoolean("is_st"),
                resultSet.getBoolean("is_active"),
                resultSet.getString("data_source"),
                resultSet.getObject("updated_at", LocalDateTime.class)
        ), symbol);
        return rows.stream().findFirst();
    }

    public List<DailyBarRecord> findQfqDailyBars(String symbol, LocalDate tradeDate) {
        List<DailyBarRecord> rows = jdbcTemplate.query("""
                SELECT symbol, trade_date, open, high, low, close,
                       volume, amount, turnover_rate, adjust_type
                FROM daily_bars
                WHERE symbol = ?
                  AND adjust_type = 'QFQ'
                  AND trade_date <= ?
                ORDER BY trade_date DESC
                LIMIT 61
                """, (resultSet, rowNum) -> new DailyBarRecord(
                resultSet.getString("symbol"),
                resultSet.getObject("trade_date", LocalDate.class),
                resultSet.getBigDecimal("open"),
                resultSet.getBigDecimal("high"),
                resultSet.getBigDecimal("low"),
                resultSet.getBigDecimal("close"),
                resultSet.getLong("volume"),
                resultSet.getBigDecimal("amount"),
                resultSet.getBigDecimal("turnover_rate"),
                resultSet.getString("adjust_type")
        ), symbol, tradeDate);
        return rows.stream().sorted(Comparator.comparing(DailyBarRecord::tradeDate)).toList();
    }

    public List<String> findAdjustTypes(String symbol, LocalDate tradeDate) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT adjust_type
                FROM daily_bars
                WHERE symbol = ? AND trade_date <= ?
                ORDER BY adjust_type
                """, String.class, symbol, tradeDate);
    }

    public record SecurityRecord(
            String symbol,
            String name,
            String exchange,
            String board,
            String industry,
            LocalDate listDate,
            boolean isSt,
            boolean isActive,
            String dataSource,
            LocalDateTime updatedAt
    ) {
    }

    public record DailyBarRecord(
            String symbol,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            String adjustType
    ) {
    }
}
