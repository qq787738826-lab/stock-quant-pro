package com.stockquant.server.service;

import com.stockquant.core.domain.Bar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    public record SecurityInfo(
            String symbol,
            String name,
            String exchange,
            String board,
            boolean st,
            boolean active,
            String dataSource
    ) {}

    public record AnalysisItem(
            String symbol,
            String mode,
            String dataSource,
            LocalDate tradeDate,
            BigDecimal score,
            String signalLevel,
            String riskLevel,
            String summary,
            List<String> bullish,
            List<String> bearish,
            Map<String, Object> metrics,
            List<Bar> bars
    ) {}

    public record BatchAnalysis(
            int requested,
            int success,
            int failed,
            List<AnalysisItem> items,
            Map<String, String> failures
    ) {}

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final JdbcTemplate jdbcTemplate;

    public MarketDataService(
            @Value("${quant.ai-service-url}") String baseUrl,
            JdbcTemplate jdbcTemplate
    ) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.jdbcTemplate = jdbcTemplate;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(180_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    public List<SecurityInfo> universe(boolean includeSt) {
        Map<String, Object> response = restTemplate.getForObject(
                baseUrl + "/market/universe?includeSt=" + includeSt,
                Map.class
        );
        if (response == null || !(response.get("items") instanceof List<?> rawItems)) {
            throw new IllegalStateException("行情服务没有返回股票列表");
        }

        List<SecurityInfo> result = new ArrayList<>();
        for (Object raw : rawItems) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            result.add(new SecurityInfo(
                    string(map.get("symbol")),
                    string(map.get("name")),
                    string(map.get("exchange")),
                    string(map.get("board")),
                    bool(map.get("isSt")),
                    boolDefault(map.get("isActive"), true),
                    string(map.get("dataSource"))
            ));
        }
        return result;
    }

    public List<Bar> history(String symbol, int days) {
        validateSymbol(symbol);
        int safeDays = Math.max(30, Math.min(days, 3000));

        List<Bar> cached = loadBars(symbol, safeDays);
        if (cached.size() >= safeDays
                && !cached.isEmpty()
                && !cached.get(cached.size() - 1).tradeDate().isBefore(LocalDate.now().minusDays(7))) {
            return cached;
        }

        List<Bar> fetched = fetchHistory(symbol, safeDays);
        persistBars(symbol, fetched);
        return fetched;
    }

    @SuppressWarnings("unchecked")
    public BatchAnalysis analyzeBatch(List<String> symbols, int days, boolean includeBars) {
        if (symbols == null || symbols.isEmpty()) {
            return new BatchAnalysis(0, 0, 0, List.of(), Map.of());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbols", symbols);
        body.put("days", Math.max(80, Math.min(days, 500)));
        body.put("includeBars", includeBars);
        body.put("maxWorkers", Math.min(6, Math.max(1, symbols.size())));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + "/market/analyze-batch",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        Map<String, Object> payload = response.getBody();
        if (payload == null) {
            throw new IllegalStateException("批量扫描接口返回空数据");
        }

        List<AnalysisItem> items = new ArrayList<>();
        Object rawItems = payload.get("items");
        if (rawItems instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> map) {
                    AnalysisItem item = mapAnalysis(map);
                    items.add(item);
                    if (!item.bars().isEmpty()) {
                        persistBars(item.symbol(), item.bars());
                    }
                }
            }
        }

        Map<String, String> failures = new LinkedHashMap<>();
        Object rawFailures = payload.get("failures");
        if (rawFailures instanceof Map<?, ?> map) {
            map.forEach((key, value) -> failures.put(string(key), string(value)));
        }

        return new BatchAnalysis(
                intValue(payload.get("requested"), symbols.size()),
                intValue(payload.get("success"), items.size()),
                intValue(payload.get("failed"), failures.size()),
                items,
                failures
        );
    }

    public void persistBars(String symbol, List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        ensureSecurity(symbol);
        jdbcTemplate.batchUpdate("""
                insert into daily_bars(
                    symbol, trade_date, open, high, low, close,
                    volume, amount, turnover_rate, adjust_type
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QFQ')
                on conflict(symbol, trade_date, adjust_type) do update set
                    open=excluded.open,
                    high=excluded.high,
                    low=excluded.low,
                    close=excluded.close,
                    volume=excluded.volume,
                    amount=excluded.amount,
                    turnover_rate=excluded.turnover_rate
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Bar bar = bars.get(i);
                ps.setString(1, symbol);
                ps.setDate(2, Date.valueOf(bar.tradeDate()));
                ps.setBigDecimal(3, bar.open());
                ps.setBigDecimal(4, bar.high());
                ps.setBigDecimal(5, bar.low());
                ps.setBigDecimal(6, bar.close());
                ps.setLong(7, bar.volume());
                ps.setBigDecimal(8, bar.amount());
                ps.setBigDecimal(9, bar.turnoverRate());
            }

            @Override
            public int getBatchSize() {
                return bars.size();
            }
        });
    }

    private List<Bar> fetchHistory(String symbol, int days) {
        ResponseEntity<List> response = restTemplate.getForEntity(
                baseUrl + "/market/history/" + symbol + "?days=" + days,
                List.class
        );
        List<?> rows = response.getBody();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("行情服务没有返回K线：" + symbol);
        }

        List<Bar> bars = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                bars.add(mapBar(map));
            }
        }
        if (bars.size() < 30) {
            throw new IllegalStateException("有效K线不足：" + symbol);
        }
        bars.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));
        return bars;
    }

    private List<Bar> loadBars(String symbol, int days) {
        List<Bar> desc = jdbcTemplate.query("""
                select symbol, trade_date, open, high, low, close,
                       volume, amount, turnover_rate
                from daily_bars
                where symbol=? and adjust_type='QFQ'
                order by trade_date desc
                limit ?
                """, (rs, rowNum) -> new Bar(
                rs.getString("symbol"),
                rs.getDate("trade_date").toLocalDate(),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getLong("volume"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("turnover_rate")
        ), symbol, days);
        Collections.reverse(desc);
        return desc;
    }

    private AnalysisItem mapAnalysis(Map<?, ?> map) {
        Map<String, Object> metrics = objectMap(map.get("metrics"));
        List<Bar> bars = new ArrayList<>();
        Object rawBars = map.get("bars");
        if (rawBars instanceof List<?> list) {
            for (Object raw : list) {
                if (raw instanceof Map<?, ?> barMap) {
                    bars.add(mapBar(barMap));
                }
            }
        }
        bars.sort((a, b) -> a.tradeDate().compareTo(b.tradeDate()));

        return new AnalysisItem(
                string(map.get("symbol")),
                string(map.get("mode")),
                string(map.get("dataSource")),
                LocalDate.parse(string(map.get("tradeDate"))),
                decimal(map.get("score")),
                string(map.get("signalLevel")),
                string(map.get("riskLevel")),
                string(map.get("summary")),
                stringList(map.get("bullish")),
                stringList(map.get("bearish")),
                metrics,
                bars
        );
    }

    private Bar mapBar(Map<?, ?> map) {
        return new Bar(
                string(map.get("symbol")),
                LocalDate.parse(string(map.get("tradeDate"))),
                decimal(map.get("open")),
                decimal(map.get("high")),
                decimal(map.get("low")),
                decimal(map.get("close")),
                longValue(map.get("volume")),
                decimal(map.get("amount")),
                decimal(map.get("turnoverRate"))
        );
    }

    private void ensureSecurity(String symbol) {
        String exchange = symbol.startsWith("6") ? "SH" : "SZ";
        jdbcTemplate.update("""
                insert into securities(symbol, name, exchange, board, is_st, is_active)
                values (?, ?, ?, 'MAIN', false, true)
                on conflict(symbol) do nothing
                """, symbol, symbol, exchange);
    }

    private void validateSymbol(String symbol) {
        if (symbol == null || !symbol.matches("\\d{6}")) {
            throw new IllegalArgumentException("股票代码必须是6位数字");
        }
    }

    private static Map<String, Object> objectMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(string(k), v));
        }
        return result;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(MarketDataService::string).toList();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || "".equals(value)) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    private static int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return new BigDecimal(String.valueOf(value)).intValue();
    }

    private static boolean bool(Object value) {
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static boolean boolDefault(Object value, boolean defaultValue) {
        return value == null ? defaultValue : bool(value);
    }
}
