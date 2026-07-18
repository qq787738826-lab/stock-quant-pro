package com.stockquant.server.agent.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentResearchContextReadRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentResearchContextReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BreadthRecord marketBreadth(LocalDate requestedTradeDate) {
        return jdbcTemplate.queryForObject("""
                WITH universe AS (
                    SELECT symbol FROM securities
                    WHERE board='MAIN' AND is_active=true AND is_st=false
                ), effective_date AS (
                    SELECT MAX(b.trade_date) AS trade_date
                    FROM daily_bars b JOIN universe u ON u.symbol=b.symbol
                    WHERE b.adjust_type='QFQ' AND b.trade_date<=?
                ), previous_date AS (
                    SELECT MAX(b.trade_date) AS trade_date
                    FROM daily_bars b JOIN universe u ON u.symbol=b.symbol
                    CROSS JOIN effective_date e
                    WHERE b.adjust_type='QFQ' AND b.trade_date<e.trade_date
                ), current_bars AS (
                    SELECT b.symbol, b.close FROM daily_bars b CROSS JOIN effective_date e
                    WHERE b.adjust_type='QFQ' AND b.trade_date=e.trade_date
                ), previous_bars AS (
                    SELECT b.symbol, b.close FROM daily_bars b CROSS JOIN previous_date p
                    WHERE b.adjust_type='QFQ' AND b.trade_date=p.trade_date
                )
                SELECT e.trade_date AS effective_trade_date,
                       p.trade_date AS previous_effective_trade_date,
                       COUNT(u.symbol)::int AS universe_count,
                       COUNT(c.symbol)::int AS covered_symbol_count,
                       COUNT(*) FILTER (WHERE u.symbol IS NOT NULL AND c.symbol IS NOT NULL AND previous.symbol IS NOT NULL)::int AS comparable_symbol_count,
                       COUNT(*) FILTER (WHERE u.symbol IS NOT NULL AND c.close > previous.close)::int AS advancing_count,
                       COUNT(*) FILTER (WHERE u.symbol IS NOT NULL AND c.close < previous.close)::int AS declining_count,
                       COUNT(*) FILTER (WHERE u.symbol IS NOT NULL AND c.close = previous.close)::int AS unchanged_count,
                       COUNT(*) FILTER (WHERE u.symbol IS NOT NULL AND c.symbol IS NULL)::int AS missing_current_bar_count,
                       COUNT(*) FILTER (WHERE u.symbol IS NOT NULL AND c.symbol IS NOT NULL AND previous.symbol IS NULL)::int AS missing_previous_bar_count
                FROM effective_date e CROSS JOIN previous_date p
                LEFT JOIN universe u ON true
                LEFT JOIN current_bars c ON c.symbol=u.symbol
                LEFT JOIN previous_bars previous ON previous.symbol=u.symbol
                GROUP BY e.trade_date, p.trade_date
                """, (rs, rowNum) -> new BreadthRecord(
                rs.getObject("effective_trade_date", LocalDate.class),
                rs.getObject("previous_effective_trade_date", LocalDate.class),
                rs.getInt("universe_count"), rs.getInt("covered_symbol_count"),
                rs.getInt("comparable_symbol_count"), rs.getInt("advancing_count"),
                rs.getInt("declining_count"), rs.getInt("unchanged_count"),
                rs.getInt("missing_current_bar_count"), rs.getInt("missing_previous_bar_count")
        ), requestedTradeDate);
    }

    public Optional<ScanTaskRecord> latestOfficialScan(LocalDate requestedTradeDate) {
        List<ScanTaskRecord> rows = jdbcTemplate.query("""
                SELECT id, status, scan_type, official, source_task_id, trade_date,
                       created_at, finished_at
                FROM market_scan_tasks
                WHERE status='COMPLETED' AND scan_type='FULL' AND official=true
                  AND trade_date<=? AND finished_at IS NOT NULL AND finished_at<?
                ORDER BY trade_date DESC, finished_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> new ScanTaskRecord(
                rs.getLong("id"), rs.getString("status"), rs.getString("scan_type"),
                rs.getBoolean("official"), rs.getObject("source_task_id", Long.class),
                rs.getObject("trade_date", LocalDate.class),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("finished_at", LocalDateTime.class)
        ), requestedTradeDate, requestedTradeDate.plusDays(1).atStartOfDay());
        return rows.stream().findFirst();
    }

    public Optional<ScanResultRecord> scanResult(long taskId, String symbol) {
        List<ScanResultRecord> rows = jdbcTemplate.query("""
                SELECT id, task_id, symbol, trade_date, rank_no, eligible, filter_reasons::text,
                       score, latest_close, data_source, created_at,
                       avg_amount_20, return_5_pct, return_20_pct, rsi14,
                       atr14_pct, volume_ratio20, breakout20
                FROM market_scan_results WHERE task_id=? AND symbol=?
                """, (rs, rowNum) -> new ScanResultRecord(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("symbol"),
                rs.getObject("trade_date", LocalDate.class), rs.getInt("rank_no"),
                rs.getBoolean("eligible"), rs.getString("filter_reasons"),
                rs.getBigDecimal("score"), rs.getBigDecimal("latest_close"),
                rs.getString("data_source"), rs.getObject("created_at", LocalDateTime.class),
                rs.getBigDecimal("avg_amount_20"), rs.getBigDecimal("return_5_pct"),
                rs.getBigDecimal("return_20_pct"), rs.getBigDecimal("rsi14"),
                rs.getBigDecimal("atr14_pct"), rs.getBigDecimal("volume_ratio20"),
                rs.getObject("breakout20", Boolean.class)
        ), taskId, symbol);
        return rows.stream().findFirst();
    }

    public Optional<ScanFailureRecord> scanFailure(long taskId, String symbol) {
        List<ScanFailureRecord> rows = jdbcTemplate.query("""
                SELECT id, task_id, symbol, retry_count, resolved, created_at, updated_at
                FROM market_scan_failures WHERE task_id=? AND symbol=? ORDER BY id ASC LIMIT 1
                """, (rs, rowNum) -> new ScanFailureRecord(
                rs.getLong("id"), rs.getLong("task_id"), rs.getString("symbol"),
                rs.getInt("retry_count"), rs.getBoolean("resolved"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
        ), taskId, symbol);
        return rows.stream().findFirst();
    }

    public record BreadthRecord(LocalDate effectiveTradeDate, LocalDate previousEffectiveTradeDate,
                                int universeCount, int coveredSymbolCount, int comparableSymbolCount,
                                int advancingCount, int decliningCount, int unchangedCount,
                                int missingCurrentBarCount, int missingPreviousBarCount) {}

    public record ScanTaskRecord(long id, String status, String scanType, boolean official,
                                 Long sourceTaskId, LocalDate tradeDate,
                                 LocalDateTime createdAt, LocalDateTime finishedAt) {}

    public record ScanResultRecord(long id, long taskId, String symbol, LocalDate tradeDate,
                                   int rank, boolean eligible, String filterReasonsJson,
                                   BigDecimal sourceScanScore, BigDecimal latestClose,
                                   String dataSource, LocalDateTime createdAt,
                                   BigDecimal avgAmount20, BigDecimal return5Pct,
                                   BigDecimal return20Pct, BigDecimal rsi14,
                                   BigDecimal atr14Pct, BigDecimal volumeRatio20,
                                   Boolean breakout20) {}

    public record ScanFailureRecord(long id, long taskId, String symbol, int retryCount,
                                    boolean resolved, LocalDateTime createdAt,
                                    LocalDateTime updatedAt) {}
}
