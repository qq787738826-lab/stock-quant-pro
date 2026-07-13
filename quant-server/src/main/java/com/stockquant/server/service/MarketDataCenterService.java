package com.stockquant.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.service.MarketDataService.AnalysisItem;
import com.stockquant.server.service.MarketDataService.BatchAnalysis;
import com.stockquant.server.service.MarketDataService.HistoryBatch;
import com.stockquant.server.service.MarketDataService.SecurityInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketDataCenterService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataCenterService.class);

    private final JdbcTemplate jdbcTemplate;
    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor scanExecutor;
    private final TaskExecutor dataUpdateExecutor;

    public MarketDataCenterService(
            JdbcTemplate jdbcTemplate,
            MarketDataService marketDataService,
            ObjectMapper objectMapper,
            @Qualifier("marketScanExecutor") TaskExecutor scanExecutor,
            @Qualifier("marketDataUpdateExecutor") TaskExecutor dataUpdateExecutor
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.marketDataService = marketDataService;
        this.objectMapper = objectMapper;
        this.scanExecutor = scanExecutor;
        this.dataUpdateExecutor = dataUpdateExecutor;
    }

    public Map<String, Object> syncUniverse() {
        List<SecurityInfo> rows = marketDataService.universe(false);
        int[][] affected = jdbcTemplate.batchUpdate("""
                insert into securities(
                    symbol, name, exchange, board, is_st, is_active,
                    data_source, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict(symbol) do update set
                    name=excluded.name,
                    exchange=excluded.exchange,
                    board=excluded.board,
                    is_st=excluded.is_st,
                    is_active=excluded.is_active,
                    data_source=excluded.data_source,
                    updated_at=now()
                """, rows, 500, (ps, item) -> {
            ps.setString(1, item.symbol());
            ps.setString(2, item.name());
            ps.setString(3, item.exchange());
            ps.setString(4, item.board());
            ps.setBoolean(5, item.st());
            ps.setBoolean(6, item.active());
            ps.setString(7, item.dataSource());
        });

        jdbcTemplate.update("""
                insert into market_sync_runs(sync_type, status, item_count, message, started_at, finished_at)
                values ('UNIVERSE', 'COMPLETED', ?, ?, now(), now())
                """, rows.size(), "沪深主板股票列表同步完成");

        return Map.of(
                "count", rows.size(),
                "affectedBatches", affected.length,
                "time", LocalDateTime.now()
        );
    }

    public Map<String, Object> syncHistory(String symbol, int days) {
        var bars = marketDataService.history(symbol, days);
        return Map.of(
                "symbol", symbol,
                "count", bars.size(),
                "firstDate", bars.get(0).tradeDate(),
                "lastDate", bars.get(bars.size() - 1).tradeDate()
        );
    }

    public Map<String, Object> overview() {
        Integer securities = jdbcTemplate.queryForObject(
                "select count(*) from securities where is_active=true and is_st=false and board='MAIN'",
                Integer.class
        );
        Long bars = jdbcTemplate.queryForObject("select count(*) from daily_bars", Long.class);
        LocalDate latestBarDate = jdbcTemplate.queryForObject(
                "select max(trade_date) from daily_bars",
                LocalDate.class
        );
        Integer cachedSymbols = jdbcTemplate.queryForObject(
                "select count(distinct symbol) from daily_bars",
                Integer.class
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("securities", securities == null ? 0 : securities);
        result.put("dailyBars", bars == null ? 0 : bars);
        result.put("cachedSymbols", cachedSymbols == null ? 0 : cachedSymbols);
        result.put("latestBarDate", latestBarDate);
        result.put("latestTask", latestTask());
        result.put("latestOfficialTask", latestOfficialTask());
        result.put("latestUpdateTask", latestUpdateTask());
        return result;
    }

    public Map<String, Object> searchSecurities(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(10, Math.min(size, 200));
        int offset = (safePage - 1) * safeSize;
        String normalized = keyword == null ? "" : keyword.trim();
        String like = "%" + normalized + "%";

        Long total = jdbcTemplate.queryForObject("""
                select count(*) from securities
                where is_active=true and board='MAIN'
                  and (?='' or symbol like ? or name like ?)
                """, Long.class, normalized, like, like);

        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                select s.symbol, s.name, s.exchange, s.board, s.industry,
                       s.is_st, s.is_active, s.data_source, s.updated_at,
                       b.trade_date as latest_trade_date,
                       b.close as latest_close
                from securities s
                left join lateral (
                    select trade_date, close
                    from daily_bars d
                    where d.symbol=s.symbol and d.adjust_type='QFQ'
                    order by trade_date desc
                    limit 1
                ) b on true
                where s.is_active=true and s.board='MAIN'
                  and (?='' or s.symbol like ? or s.name like ?)
                order by s.symbol
                limit ? offset ?
                """, normalized, like, like, safeSize, offset);

        return Map.of(
                "page", safePage,
                "size", safeSize,
                "total", total == null ? 0 : total,
                "items", items
        );
    }

    public synchronized long startScan(
            Integer requestedLimit,
            Integer requestedBatchSize,
            Integer requestedResultLimit
    ) {
        int scanLimit = requestedLimit == null ? 0 : Math.max(requestedLimit, 0);
        String scanType = scanLimit == 0 ? "FULL" : "TEST";
        return createAndStartScan(
                scanType,
                scanLimit,
                requestedBatchSize,
                requestedResultLimit,
                null,
                null
        );
    }

    public synchronized long retryFailures(long sourceTaskId, Integer requestedBatchSize) {
        if (hasRunningTask()) {
            throw new IllegalArgumentException("已有全市场扫描任务正在运行");
        }
        if (hasRunningUpdateTask()) {
            throw new IllegalArgumentException("行情增量更新正在运行，请等待完成后再重试");
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select f.symbol, coalesce(f.name, s.name, f.symbol) as name
                from market_scan_failures f
                left join securities s on s.symbol=f.symbol
                where f.task_id=? and f.resolved=false
                order by f.symbol
                """, sourceTaskId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("该任务没有可重试的失败股票");
        }

        int batchSize = requestedBatchSize == null ? 6 : Math.max(2, Math.min(requestedBatchSize, 20));
        Long taskId = jdbcTemplate.queryForObject("""
                insert into market_scan_tasks(
                    status, requested_limit, batch_size, result_limit,
                    scan_type, official, source_task_id, message, created_at
                ) values ('QUEUED', ?, ?, 200, 'RETRY', false, ?, '等待重试失败股票', now())
                returning id
                """, Long.class, rows.size(), batchSize, sourceTaskId);
        if (taskId == null) {
            throw new IllegalStateException("创建重试任务失败");
        }

        scanExecutor.execute(() -> runScan(taskId, "RETRY", rows.size(), batchSize, 200, rows, sourceTaskId));
        return taskId;
    }

    private long createAndStartScan(
            String scanType,
            int scanLimit,
            Integer requestedBatchSize,
            Integer requestedResultLimit,
            List<Map<String, Object>> explicitSecurities,
            Long sourceTaskId
    ) {
        if (hasRunningTask()) {
            throw new IllegalArgumentException("已有全市场扫描任务正在运行");
        }
        if (hasRunningUpdateTask()) {
            throw new IllegalArgumentException("行情增量更新正在运行，请等待完成后再扫描");
        }

        int batchSize = requestedBatchSize == null ? 12 : Math.max(2, Math.min(requestedBatchSize, 30));
        int resultLimit = requestedResultLimit == null ? 50 : Math.max(10, Math.min(requestedResultLimit, 200));

        Long taskId = jdbcTemplate.queryForObject("""
                insert into market_scan_tasks(
                    status, requested_limit, batch_size, result_limit,
                    scan_type, official, source_task_id,
                    message, created_at
                ) values ('QUEUED', ?, ?, ?, ?, false, ?, '等待执行', now())
                returning id
                """, Long.class, scanLimit, batchSize, resultLimit, scanType, sourceTaskId);
        if (taskId == null) {
            throw new IllegalStateException("创建扫描任务失败");
        }

        scanExecutor.execute(() -> runScan(
                taskId,
                scanType,
                scanLimit,
                batchSize,
                resultLimit,
                explicitSecurities,
                sourceTaskId
        ));
        return taskId;
    }

    public boolean hasRunningTask() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from market_scan_tasks where status in ('QUEUED','RUNNING')",
                Integer.class
        );
        return count != null && count > 0;
    }

    public Map<String, Object> task(long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from market_scan_tasks where id=?",
                taskId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("扫描任务不存在：" + taskId);
        }
        return rows.get(0);
    }

    public Map<String, Object> latestTask() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from market_scan_tasks order by id desc limit 1"
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public Map<String, Object> latestOfficialTask() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select * from market_scan_tasks
                where status='COMPLETED' and official=true
                order by id desc
                limit 1
                """);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> taskHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.queryForList("""
                select t.*,
                       (select count(*) from market_scan_failures f
                        where f.task_id=t.id and f.resolved=false) as unresolved_failures
                from market_scan_tasks t
                order by t.id desc
                limit ?
                """, safeLimit);
    }

    public List<Map<String, Object>> latestResults(int limit) {
        List<Long> taskIds = jdbcTemplate.queryForList("""
                select id from market_scan_tasks
                where status='COMPLETED' and official=true
                order by id desc
                limit 1
                """, Long.class);
        if (taskIds.isEmpty()) {
            taskIds = jdbcTemplate.queryForList("""
                    select id from market_scan_tasks
                    where status='COMPLETED'
                    order by id desc
                    limit 1
                    """, Long.class);
        }
        return taskIds.isEmpty() ? List.of() : taskResults(taskIds.get(0), limit, true);
    }

    public List<Map<String, Object>> taskResults(long taskId, int limit, boolean eligibleOnly) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        String eligibleSql = eligibleOnly ? " and eligible=true " : "";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select task_id, rank_no, symbol, name, trade_date, score,
                       signal_level, risk_level, eligible, filter_reasons,
                       latest_close, buy_low, buy_high, stop_loss, target1, target2,
                       suggested_weight, data_source, summary, metrics,
                       bullish, bearish, avg_amount_20, return_5_pct, return_20_pct,
                       rsi14, atr14_pct, volume_ratio20, breakout20
                from market_scan_results
                where task_id=?
                """ + eligibleSql + """
                order by eligible desc, rank_no asc
                limit ?
                """, taskId, safeLimit);

        return rows.stream()
                .map(this::normalizeScanResult)
                .toList();
    }

    private Map<String, Object> normalizeScanResult(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);

        List<?> filterReasons = jsonValue(source.get("filter_reasons"), List.of(), List.class);
        List<?> bullish = jsonValue(source.get("bullish"), List.of(), List.class);
        List<?> bearish = jsonValue(source.get("bearish"), List.of(), List.class);
        Map<?, ?> metrics = jsonValue(source.get("metrics"), Map.of(), Map.class);

        row.put("filter_reasons", filterReasons);
        row.put("bullish", bullish);
        row.put("bearish", bearish);
        row.put("metrics", metrics);

        putMetricWhenMissing(row, "return_5_pct", metrics, "return5Pct");
        putMetricWhenMissing(row, "return_20_pct", metrics, "return20Pct");
        putMetricWhenMissing(row, "rsi14", metrics, "rsi14");
        putMetricWhenMissing(row, "atr14_pct", metrics, "atr14Pct");
        putMetricWhenMissing(row, "volume_ratio20", metrics, "volumeRatio20");
        putMetricWhenMissing(row, "breakout20", metrics, "breakout20");

        return row;
    }

    private void putMetricWhenMissing(
            Map<String, Object> row,
            String resultField,
            Map<?, ?> metrics,
            String metricField
    ) {
        if (row.get(resultField) == null && metrics.containsKey(metricField)) {
            row.put(resultField, metrics.get(metricField));
        }
    }

    private <T> T jsonValue(Object value, T fallback, Class<T> targetType) {
        if (value == null) {
            return fallback;
        }
        if (targetType.isInstance(value)) {
            return targetType.cast(value);
        }

        try {
            return objectMapper.readValue(String.valueOf(value), targetType);
        } catch (Exception error) {
            log.debug("扫描结果JSON字段解析失败 value={}", value, error);
            return fallback;
        }
    }

    public List<Map<String, Object>> scanFailures(long taskId) {
        return jdbcTemplate.queryForList("""
                select id, task_id, symbol, name, error_message,
                       retry_count, resolved, created_at, updated_at
                from market_scan_failures
                where task_id=?
                order by resolved, symbol
                """, taskId);
    }

    public synchronized long startIncrementalUpdate(Integer requestedLimit, Integer requestedBatchSize) {
        if (hasRunningUpdateTask()) {
            throw new IllegalArgumentException("已有行情增量更新任务正在运行");
        }
        if (hasRunningTask()) {
            throw new IllegalArgumentException("全市场扫描正在运行，请等待完成后再更新行情");
        }
        int limit = requestedLimit == null ? 0 : Math.max(0, requestedLimit);
        int batchSize = requestedBatchSize == null ? 12 : Math.max(2, Math.min(requestedBatchSize, 30));

        Long taskId = jdbcTemplate.queryForObject("""
                insert into market_data_update_tasks(
                    status, requested_limit, batch_size, message, created_at
                ) values ('QUEUED', ?, ?, '等待执行', now())
                returning id
                """, Long.class, limit, batchSize);
        if (taskId == null) {
            throw new IllegalStateException("创建行情更新任务失败");
        }
        dataUpdateExecutor.execute(() -> runIncrementalUpdate(taskId, limit, batchSize));
        return taskId;
    }

    public boolean hasRunningUpdateTask() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from market_data_update_tasks where status in ('QUEUED','RUNNING')",
                Integer.class
        );
        return count != null && count > 0;
    }

    public Map<String, Object> updateTask(long taskId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from market_data_update_tasks where id=?",
                taskId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("行情更新任务不存在：" + taskId);
        }
        return rows.get(0);
    }

    public Map<String, Object> latestUpdateTask() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select * from market_data_update_tasks order by id desc limit 1"
        );
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    public List<Map<String, Object>> updateFailures(long taskId) {
        return jdbcTemplate.queryForList("""
                select id, task_id, symbol, name, error_message, created_at
                from market_data_update_failures
                where task_id=?
                order by symbol
                """, taskId);
    }

    private void runScan(
            long taskId,
            String scanType,
            int scanLimit,
            int batchSize,
            int resultLimit,
            List<Map<String, Object>> explicitSecurities,
            Long sourceTaskId
    ) {
        LocalDateTime startTime = LocalDateTime.now();
        try {
            ensureUniverse();
            jdbcTemplate.update("""
                    update market_scan_tasks
                    set status='RUNNING', started_at=now(), message='正在读取股票列表'
                    where id=?
                    """, taskId);

            List<Map<String, Object>> securities;
            if (explicitSecurities != null) {
                securities = explicitSecurities;
            } else {
                String sql = """
                        select symbol, name
                        from securities
                        where is_active=true and is_st=false and board='MAIN'
                        order by symbol
                        """ + (scanLimit > 0 ? " limit " + scanLimit : "");
                securities = jdbcTemplate.queryForList(sql);
            }

            jdbcTemplate.update(
                    "update market_scan_tasks set total_symbols=?, message='开始批量计算指标' where id=?",
                    securities.size(), taskId
            );

            int processed = 0;
            int success = 0;
            int failed = 0;

            for (int start = 0; start < securities.size(); start += batchSize) {
                int end = Math.min(start + batchSize, securities.size());
                List<Map<String, Object>> batch = securities.subList(start, end);

                List<String> symbols = batch.stream()
                        .map(row -> String.valueOf(row.get("symbol")))
                        .toList();
                Map<String, String> names = new LinkedHashMap<>();
                batch.forEach(row -> names.put(
                        String.valueOf(row.get("symbol")),
                        String.valueOf(row.get("name"))
                ));

                try {
                    BatchAnalysis analysis = marketDataService.analyzeBatch(symbols, 120, true);
                    for (AnalysisItem item : analysis.items()) {
                        persistScanResult(taskId, names.getOrDefault(item.symbol(), item.symbol()), item);
                        if (sourceTaskId != null) {
                            jdbcTemplate.update("""
                                    update market_scan_failures
                                    set resolved=true, updated_at=now(), retry_count=retry_count+1
                                    where task_id=? and symbol=?
                                    """, sourceTaskId, item.symbol());
                        }
                    }
                    analysis.failures().forEach((symbol, error) ->
                            persistScanFailure(
                                    taskId,
                                    symbol,
                                    names.getOrDefault(symbol, symbol),
                                    error
                            )
                    );
                    success += analysis.success();
                    failed += analysis.failed();
                } catch (Exception batchError) {
                    failed += batch.size();
                    batch.forEach(row -> persistScanFailure(
                            taskId,
                            String.valueOf(row.get("symbol")),
                            String.valueOf(row.get("name")),
                            abbreviate(batchError.getMessage(), 900)
                    ));
                    log.warn("扫描批次失败 taskId={}, symbols={}", taskId, symbols, batchError);
                }

                processed += batch.size();
                jdbcTemplate.update("""
                        update market_scan_tasks
                        set processed_symbols=?, success_symbols=?, failed_symbols=?,
                            message=?
                        where id=?
                        """,
                        processed, success, failed,
                        "已处理 " + processed + " / " + securities.size(),
                        taskId
                );
            }

            rankResults(taskId);
            if ("FULL".equals(scanType)) {
                refreshLatestSignalsAndPlans(taskId, resultLimit);
            }

            Long durationMs = Duration.between(startTime, LocalDateTime.now()).toMillis();
            jdbcTemplate.update("""
                    update market_scan_tasks
                    set status='COMPLETED',
                        official=?,
                        selected_count=(
                            select count(*)::int
                            from market_scan_results
                            where task_id=? and eligible=true
                        ),
                        trade_date=(
                            select max(trade_date)
                            from market_scan_results
                            where task_id=?
                        ),
                        duration_ms=?,
                        message='扫描完成',
                        finished_at=now()
                    where id=?
                    """,
                    "FULL".equals(scanType),
                    taskId,
                    taskId,
                    durationMs,
                    taskId
            );
        } catch (Exception e) {
            log.error("全市场扫描失败 taskId={}", taskId, e);
            jdbcTemplate.update("""
                    update market_scan_tasks
                    set status='FAILED', message=?, finished_at=now(),
                        duration_ms=?
                    where id=?
                    """,
                    abbreviate(e.getMessage(), 900),
                    Duration.between(startTime, LocalDateTime.now()).toMillis(),
                    taskId
            );
        }
    }

    private void runIncrementalUpdate(long taskId, int requestedLimit, int batchSize) {
        LocalDateTime startTime = LocalDateTime.now();
        try {
            ensureUniverse();
            jdbcTemplate.update("""
                    update market_data_update_tasks
                    set status='RUNNING', started_at=now(), message='正在读取股票列表'
                    where id=?
                    """, taskId);

            String sql = """
                    select symbol, name
                    from securities
                    where is_active=true and is_st=false and board='MAIN'
                    order by symbol
                    """ + (requestedLimit > 0 ? " limit " + requestedLimit : "");
            List<Map<String, Object>> securities = jdbcTemplate.queryForList(sql);

            jdbcTemplate.update("""
                    update market_data_update_tasks
                    set total_symbols=?, message='开始更新最近90日K线'
                    where id=?
                    """, securities.size(), taskId);

            int processed = 0;
            int success = 0;
            int failed = 0;
            long bars = 0;

            for (int start = 0; start < securities.size(); start += batchSize) {
                int end = Math.min(start + batchSize, securities.size());
                List<Map<String, Object>> batch = securities.subList(start, end);
                List<String> symbols = batch.stream()
                        .map(row -> String.valueOf(row.get("symbol")))
                        .toList();
                Map<String, String> names = new LinkedHashMap<>();
                batch.forEach(row -> names.put(
                        String.valueOf(row.get("symbol")),
                        String.valueOf(row.get("name"))
                ));

                try {
                    HistoryBatch history = marketDataService.syncHistoryBatch(symbols, 90);
                    success += history.success();
                    failed += history.failed();
                    bars += history.persistedBars();
                    history.failures().forEach((symbol, error) ->
                            persistUpdateFailure(
                                    taskId,
                                    symbol,
                                    names.getOrDefault(symbol, symbol),
                                    error
                            )
                    );
                } catch (Exception batchError) {
                    failed += batch.size();
                    batch.forEach(row -> persistUpdateFailure(
                            taskId,
                            String.valueOf(row.get("symbol")),
                            String.valueOf(row.get("name")),
                            abbreviate(batchError.getMessage(), 900)
                    ));
                    log.warn("行情更新批次失败 taskId={}, symbols={}", taskId, symbols, batchError);
                }

                processed += batch.size();
                jdbcTemplate.update("""
                        update market_data_update_tasks
                        set processed_symbols=?, success_symbols=?, failed_symbols=?,
                            inserted_bars=?, message=?
                        where id=?
                        """,
                        processed,
                        success,
                        failed,
                        bars,
                        "已更新 " + processed + " / " + securities.size(),
                        taskId
                );
            }

            LocalDate latest = jdbcTemplate.queryForObject(
                    "select max(trade_date) from daily_bars",
                    LocalDate.class
            );
            jdbcTemplate.update("""
                    update market_data_update_tasks
                    set status='COMPLETED', latest_trade_date=?,
                        duration_ms=?, message='增量更新完成', finished_at=now()
                    where id=?
                    """,
                    latest == null ? null : Date.valueOf(latest),
                    Duration.between(startTime, LocalDateTime.now()).toMillis(),
                    taskId
            );
        } catch (Exception e) {
            log.error("行情增量更新失败 taskId={}", taskId, e);
            jdbcTemplate.update("""
                    update market_data_update_tasks
                    set status='FAILED', message=?, duration_ms=?, finished_at=now()
                    where id=?
                    """,
                    abbreviate(e.getMessage(), 900),
                    Duration.between(startTime, LocalDateTime.now()).toMillis(),
                    taskId
            );
        }
    }

    private void ensureUniverse() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from securities where is_active=true and board='MAIN'",
                Integer.class
        );
        if (count == null || count == 0) {
            syncUniverse();
        }
    }

    private void persistScanResult(long taskId, String name, AnalysisItem item) {
        BigDecimal price = metric(item, "latestClose");
        BigDecimal buyLow = price.multiply(new BigDecimal("0.99")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal buyHigh = price.multiply(new BigDecimal("1.01")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal stopLoss = price.multiply(new BigDecimal("0.95")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal target1 = price.multiply(new BigDecimal("1.08")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal target2 = price.multiply(new BigDecimal("1.12")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal weight = suggestedWeight(item.score(), item.riskLevel());
        boolean eligible = boolMetric(item, "candidateEligible");
        List<String> filterReasons = stringListMetric(item, "filterReasons");

        jdbcTemplate.update("""
                insert into market_scan_results(
                    task_id, rank_no, symbol, name, trade_date, score,
                    signal_level, risk_level, eligible, filter_reasons,
                    latest_close, buy_low, buy_high, stop_loss, target1, target2,
                    suggested_weight, data_source, summary,
                    metrics, bullish, bearish,
                    avg_amount_20, return_5_pct, return_20_pct,
                    rsi14, atr14_pct, volume_ratio20, breakout20,
                    created_at
                ) values (?, 0, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb),
                          ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                          ?, ?, ?, ?, ?, ?, ?, now())
                on conflict(task_id, symbol) do update set
                    name=excluded.name,
                    trade_date=excluded.trade_date,
                    score=excluded.score,
                    signal_level=excluded.signal_level,
                    risk_level=excluded.risk_level,
                    eligible=excluded.eligible,
                    filter_reasons=excluded.filter_reasons,
                    latest_close=excluded.latest_close,
                    buy_low=excluded.buy_low,
                    buy_high=excluded.buy_high,
                    stop_loss=excluded.stop_loss,
                    target1=excluded.target1,
                    target2=excluded.target2,
                    suggested_weight=excluded.suggested_weight,
                    data_source=excluded.data_source,
                    summary=excluded.summary,
                    metrics=excluded.metrics,
                    bullish=excluded.bullish,
                    bearish=excluded.bearish,
                    avg_amount_20=excluded.avg_amount_20,
                    return_5_pct=excluded.return_5_pct,
                    return_20_pct=excluded.return_20_pct,
                    rsi14=excluded.rsi14,
                    atr14_pct=excluded.atr14_pct,
                    volume_ratio20=excluded.volume_ratio20,
                    breakout20=excluded.breakout20
                """,
                taskId,
                item.symbol(),
                name,
                Date.valueOf(item.tradeDate()),
                item.score(),
                item.signalLevel(),
                item.riskLevel(),
                eligible,
                json(filterReasons),
                price,
                buyLow,
                buyHigh,
                stopLoss,
                target1,
                target2,
                weight,
                item.dataSource(),
                item.summary(),
                json(item.metrics()),
                json(item.bullish()),
                json(item.bearish()),
                metric(item, "averageAmount20"),
                metric(item, "return5Pct"),
                metric(item, "return20Pct"),
                metric(item, "rsi14"),
                metric(item, "atr14Pct"),
                metric(item, "volumeRatio20"),
                boolMetric(item, "breakout20")
        );
    }

    private void persistScanFailure(long taskId, String symbol, String name, String error) {
        jdbcTemplate.update("""
                insert into market_scan_failures(
                    task_id, symbol, name, error_message, created_at, updated_at
                ) values (?, ?, ?, ?, now(), now())
                on conflict(task_id, symbol) do update set
                    name=excluded.name,
                    error_message=excluded.error_message,
                    updated_at=now()
                """, taskId, symbol, name, abbreviate(error, 1500));
    }

    private void persistUpdateFailure(long taskId, String symbol, String name, String error) {
        jdbcTemplate.update("""
                insert into market_data_update_failures(
                    task_id, symbol, name, error_message, created_at
                ) values (?, ?, ?, ?, now())
                on conflict(task_id, symbol) do update set
                    name=excluded.name,
                    error_message=excluded.error_message
                """, taskId, symbol, name, abbreviate(error, 1500));
    }

    private void rankResults(long taskId) {
        jdbcTemplate.update("""
                with ranked as (
                    select id, row_number() over(
                        order by eligible desc, score desc, trade_date desc, symbol
                    ) as rn
                    from market_scan_results
                    where task_id=?
                )
                update market_scan_results r
                set rank_no=ranked.rn::int
                from ranked
                where r.id=ranked.id
                """, taskId);
    }

    private void refreshLatestSignalsAndPlans(long taskId, int resultLimit) {
        jdbcTemplate.update(
                "delete from signals where strategy_code like 'FULL_MARKET_V%'"
        );
        jdbcTemplate.update("""
                insert into signals(
                    symbol, signal_date, action, score, reference_price,
                    reasons, risks, strategy_code, created_at
                )
                select symbol, trade_date,
                       case when score>=75 then 'BUY' else 'WATCH' end,
                       score, latest_close, bullish, bearish,
                       'FULL_MARKET_V122', now()
                from market_scan_results
                where task_id=? and eligible=true
                order by rank_no
                limit ?
                """, taskId, resultLimit);

        jdbcTemplate.update(
                "delete from trade_plans where plan_date=current_date and status='ACTIVE'"
        );
        jdbcTemplate.update("""
                insert into trade_plans(
                    symbol, plan_date, score, buy_low, buy_high,
                    stop_loss, target1, target2, suggested_weight,
                    valid_days, status, rationale
                )
                select symbol, trade_date, score, buy_low, buy_high,
                       stop_loss, target1, target2, suggested_weight,
                       2, 'ACTIVE', summary
                from market_scan_results
                where task_id=? and eligible=true
                order by rank_no
                limit ?
                """, taskId, resultLimit);
    }

    private BigDecimal metric(AnalysisItem item, String key) {
        Object value = item.metrics().get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }

    private boolean boolMetric(AnalysisItem item, String key) {
        Object value = item.metrics().get(key);
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private List<String> stringListMetric(AnalysisItem item, String key) {
        Object value = item.metrics().get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private BigDecimal suggestedWeight(BigDecimal score, String riskLevel) {
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            return new BigDecimal("0.05");
        }
        if (score.compareTo(new BigDecimal("80")) >= 0) {
            return new BigDecimal("0.20");
        }
        if (score.compareTo(new BigDecimal("70")) >= 0) {
            return new BigDecimal("0.15");
        }
        if (score.compareTo(new BigDecimal("60")) >= 0) {
            return new BigDecimal("0.10");
        }
        return new BigDecimal("0.05");
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON序列化失败", e);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "未知错误";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
