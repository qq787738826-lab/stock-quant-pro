package com.stockquant.server.service;

import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.BacktestModels;
import com.stockquant.core.domain.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class ScanValidationService {

    private static final Logger log = LoggerFactory.getLogger(ScanValidationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final MarketDataService marketDataService;
    private final BacktestEngine backtestEngine;
    private final TaskExecutor executor;

    public ScanValidationService(
            JdbcTemplate jdbcTemplate,
            MarketDataService marketDataService,
            BacktestEngine backtestEngine,
            @Qualifier("scanBacktestExecutor") TaskExecutor executor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.marketDataService = marketDataService;
        this.backtestEngine = backtestEngine;
        this.executor = executor;
    }

    public synchronized long start(long scanTaskId, Integer requestedTopN, Integer requestedHoldingDays) {
        Integer scanExists = jdbcTemplate.queryForObject(
                "select count(*) from market_scan_tasks where id=? and status='COMPLETED'",
                Integer.class,
                scanTaskId
        );
        if (scanExists == null || scanExists == 0) {
            throw new IllegalArgumentException("只能验证已完成的扫描任务");
        }

        Integer running = jdbcTemplate.queryForObject("""
                select count(*) from scan_backtest_tasks
                where scan_task_id=? and status in ('QUEUED','RUNNING')
                """, Integer.class, scanTaskId);
        if (running != null && running > 0) {
            throw new IllegalArgumentException("该扫描任务已有批量回测正在运行");
        }

        int topN = requestedTopN == null ? 20 : Math.max(5, Math.min(requestedTopN, 50));
        int holdingDays = requestedHoldingDays == null
                ? 10
                : Math.max(2, Math.min(requestedHoldingDays, 30));

        Long taskId = jdbcTemplate.queryForObject("""
                insert into scan_backtest_tasks(
                    scan_task_id, status, top_n, max_holding_days,
                    message, created_at
                ) values (?, 'QUEUED', ?, ?, '等待执行', now())
                returning id
                """, Long.class, scanTaskId, topN, holdingDays);

        if (taskId == null) {
            throw new IllegalStateException("创建批量回测任务失败");
        }

        executor.execute(() -> run(taskId, scanTaskId, topN, holdingDays));
        return taskId;
    }

    public Map<String, Object> task(long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from scan_backtest_tasks where id=?",
                taskId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("批量回测任务不存在：" + taskId);
        }
        return rows.get(0);
    }

    public Map<String, Object> latestForScan(long scanTaskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select * from scan_backtest_tasks
                where scan_task_id=?
                order by id desc
                limit 1
                """, scanTaskId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> results(long taskId) {
        return jdbcTemplate.queryForList("""
                select id, backtest_task_id, scan_task_id, symbol, name,
                       scan_rank, scan_score, total_return, max_drawdown,
                       win_rate, profit_loss_ratio, trade_count,
                       status, error_message, created_at
                from scan_backtest_results
                where backtest_task_id=?
                order by scan_rank nulls last, symbol
                """, taskId);
    }

    private void run(long taskId, long scanTaskId, int topN, int holdingDays) {
        try {
            jdbcTemplate.update("""
                    update scan_backtest_tasks
                    set status='RUNNING', started_at=now(), message='正在读取候选股票'
                    where id=?
                    """, taskId);

            List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                    select symbol, name, rank_no, score
                    from market_scan_results
                    where task_id=? and eligible=true
                    order by rank_no
                    limit ?
                    """, scanTaskId, topN);

            if (candidates.isEmpty()) {
                candidates = jdbcTemplate.queryForList("""
                        select symbol, name, rank_no, score
                        from market_scan_results
                        where task_id=?
                        order by rank_no
                        limit ?
                        """, scanTaskId, topN);
            }

            jdbcTemplate.update("""
                    update scan_backtest_tasks
                    set total_symbols=?, message='开始批量历史回测'
                    where id=?
                    """, candidates.size(), taskId);

            int processed = 0;
            int success = 0;
            int failed = 0;
            int positive = 0;
            BigDecimal returnSum = BigDecimal.ZERO;
            BigDecimal winRateSum = BigDecimal.ZERO;
            BigDecimal drawdownSum = BigDecimal.ZERO;

            BacktestModels.Request request = new BacktestModels.Request(
                    BigDecimal.valueOf(100000),
                    holdingDays,
                    BigDecimal.valueOf(0.05),
                    BigDecimal.valueOf(0.08),
                    BigDecimal.valueOf(0.04),
                    BigDecimal.valueOf(0.0003),
                    BigDecimal.valueOf(0.0005)
            );

            for (Map<String, Object> candidate : candidates) {
                String symbol = String.valueOf(candidate.get("symbol"));
                String name = String.valueOf(candidate.get("name"));
                Integer rank = intValue(candidate.get("rank_no"));
                BigDecimal score = decimal(candidate.get("score"));

                try {
                    List<Bar> bars = marketDataService.localHistory(symbol, 180);
                    if (bars.size() < 30) {
                        throw new IllegalStateException("本地K线不足30条");
                    }

                    BacktestModels.Result result = backtestEngine.run(bars, request);
                    jdbcTemplate.update("""
                            insert into scan_backtest_results(
                                backtest_task_id, scan_task_id, symbol, name,
                                scan_rank, scan_score, total_return, max_drawdown,
                                win_rate, profit_loss_ratio, trade_count,
                                status, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', now())
                            on conflict(backtest_task_id, symbol) do update set
                                total_return=excluded.total_return,
                                max_drawdown=excluded.max_drawdown,
                                win_rate=excluded.win_rate,
                                profit_loss_ratio=excluded.profit_loss_ratio,
                                trade_count=excluded.trade_count,
                                status='SUCCESS',
                                error_message=null
                            """,
                            taskId,
                            scanTaskId,
                            symbol,
                            name,
                            rank,
                            score,
                            result.totalReturn(),
                            result.maxDrawdown(),
                            result.winRate(),
                            result.profitLossRatio(),
                            result.tradeCount()
                    );

                    returnSum = returnSum.add(result.totalReturn());
                    winRateSum = winRateSum.add(result.winRate());
                    drawdownSum = drawdownSum.add(result.maxDrawdown());
                    if (result.totalReturn().signum() > 0) {
                        positive++;
                    }
                    success++;
                } catch (Exception error) {
                    failed++;
                    jdbcTemplate.update("""
                            insert into scan_backtest_results(
                                backtest_task_id, scan_task_id, symbol, name,
                                scan_rank, scan_score, status, error_message, created_at
                            ) values (?, ?, ?, ?, ?, ?, 'FAILED', ?, now())
                            on conflict(backtest_task_id, symbol) do update set
                                status='FAILED',
                                error_message=excluded.error_message
                            """,
                            taskId,
                            scanTaskId,
                            symbol,
                            name,
                            rank,
                            score,
                            abbreviate(error.getMessage(), 900)
                    );
                    log.warn("候选股票回测失败 taskId={}, symbol={}", taskId, symbol, error);
                }

                processed++;
                jdbcTemplate.update("""
                        update scan_backtest_tasks
                        set processed_symbols=?, success_symbols=?, failed_symbols=?,
                            positive_strategy_count=?, message=?
                        where id=?
                        """,
                        processed,
                        success,
                        failed,
                        positive,
                        "已回测 " + processed + " / " + candidates.size(),
                        taskId
                );
            }

            BigDecimal divisor = success == 0 ? BigDecimal.ONE : BigDecimal.valueOf(success);
            jdbcTemplate.update("""
                    update scan_backtest_tasks
                    set status='COMPLETED',
                        avg_total_return=?,
                        avg_win_rate=?,
                        avg_max_drawdown=?,
                        positive_strategy_count=?,
                        message='批量历史回测完成',
                        finished_at=now()
                    where id=?
                    """,
                    returnSum.divide(divisor, 8, RoundingMode.HALF_UP),
                    winRateSum.divide(divisor, 8, RoundingMode.HALF_UP),
                    drawdownSum.divide(divisor, 8, RoundingMode.HALF_UP),
                    positive,
                    taskId
            );
        } catch (Exception error) {
            log.error("批量回测任务失败 taskId={}", taskId, error);
            jdbcTemplate.update("""
                    update scan_backtest_tasks
                    set status='FAILED', message=?, finished_at=now()
                    where id=?
                    """, abbreviate(error.getMessage(), 900), taskId);
        }
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        return new BigDecimal(String.valueOf(value)).intValue();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未知错误";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
