package com.stockquant.server.agent.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.agent.exception.AgentTeamException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class MarketDataPersistenceService {

    public static final String DEFAULT_SOURCE = "LOCAL_PERSISTENCE_UNSPECIFIED";

    private final JdbcTemplate jdbcTemplate;
    private final MarketDataObservationRepository observationRepository;
    private final BacktestCanonicalHashService canonicalHashService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MarketDataPersistenceService(
            JdbcTemplate jdbcTemplate,
            MarketDataObservationRepository observationRepository,
            BacktestCanonicalHashService canonicalHashService,
            ObjectMapper objectMapper,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.observationRepository = observationRepository;
        this.canonicalHashService = canonicalHashService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CaptureResult persistBars(String symbol, List<Bar> bars, String sourceCode) {
        return persistBars(
                symbol,
                bars,
                sourceCode,
                null,
                "MARKET_DATA_PERSISTENCE");
    }

    @Transactional
    public CaptureResult persistBars(
            String symbol,
            List<Bar> bars,
            String sourceCode,
            String sourceRevision,
            String captureType
    ) {
        if (bars == null || bars.isEmpty()) {
            return new CaptureResult(null, null, 0, 0);
        }
        validate(symbol, bars, sourceCode, sourceRevision, captureType);
        String normalizedSource = sourceCode == null || sourceCode.isBlank()
                ? DEFAULT_SOURCE : sourceCode.strip();
        Instant observedAt = BacktestCanonicalHashService.microsecondInstant(clock.instant());
        String suffix = UUID.randomUUID().toString();
        String batchVersion = "OBS_BATCH_V1-" + suffix;
        String datasetVersion = "LOCAL_DATASET_V1-" + suffix;
        String metadata = sourceMetadata(normalizedSource, sourceRevision);
        long batchId = observationRepository.insertBatch(
                batchVersion,
                normalizedSource,
                datasetVersion,
                captureType,
                observedAt,
                bars.size(),
                metadata);

        int appended = 0;
        for (Bar bar : bars) {
            String contentHash = canonicalHashService.hash(
                    contentPayload(normalizedSource, bar));
            String observationVersion = canonicalHashService.hash(
                    observationVersionPayload(
                            batchVersion,
                            datasetVersion,
                            normalizedSource,
                            sourceRevision,
                            observedAt,
                            contentHash));
            if (observationRepository.appendIfChanged(
                    batchId,
                    batchVersion,
                    datasetVersion,
                    normalizedSource,
                    sourceRevision,
                    bar,
                    observedAt,
                    contentHash,
                    observationVersion)) {
                appended++;
            }
        }

        ensureSecurity(symbol);
        persistCurrentProjection(symbol, bars);
        return new CaptureResult(batchVersion, datasetVersion, bars.size(), appended);
    }

    ObjectNode contentPayload(String sourceCode, Bar bar) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        value.put("sourceCode", sourceCode);
        value.put("symbol", bar.symbol());
        value.put("tradeDate", bar.tradeDate().toString());
        value.put("adjustType", BacktestContracts.ADJUST_TYPE);
        value.put("open", bar.open());
        value.put("high", bar.high());
        value.put("low", bar.low());
        value.put("close", bar.close());
        value.put("volume", bar.volume());
        putNullableDecimal(value, "amount", bar.amount());
        putNullableDecimal(value, "turnoverRate", bar.turnoverRate());
        return value;
    }

    private ObjectNode observationVersionPayload(
            String batchVersion,
            String datasetVersion,
            String sourceCode,
            String sourceRevision,
            Instant observedAt,
            String contentHash
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        value.put("batchVersion", batchVersion);
        value.put("datasetVersion", datasetVersion);
        value.put("sourceCode", sourceCode);
        if (sourceRevision == null) value.putNull("sourceRevision");
        else value.put("sourceRevision", sourceRevision);
        value.put("firstObservedAt", BacktestCanonicalHashService.formatInstant(observedAt));
        value.put("knownAt", BacktestCanonicalHashService.formatInstant(observedAt));
        value.put("canonicalContentHash", contentHash);
        return value;
    }

    private String sourceMetadata(String sourceCode, String sourceRevision) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("capturePath", "MarketDataPersistenceService");
        metadata.put("sourceCode", sourceCode);
        metadata.put("sourceRevisionProvided", sourceRevision != null);
        metadata.put(
                "revisionSemantics",
                sourceRevision == null ? "NOT_PROVIDED" : "PROVIDER_IDENTIFIER");
        metadata.put("datasetVersionSemantics", "LOCAL_IMMUTABLE_CAPTURE_BATCH");
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException error) {
            throw new AgentTeamException("无法序列化行情观察批次元数据", error);
        }
    }

    private void persistCurrentProjection(String symbol, List<Bar> bars) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO daily_bars(
                    symbol, trade_date, open, high, low, close,
                    volume, amount, turnover_rate, adjust_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QFQ')
                ON CONFLICT(symbol, trade_date, adjust_type) DO UPDATE SET
                    open=excluded.open,
                    high=excluded.high,
                    low=excluded.low,
                    close=excluded.close,
                    volume=excluded.volume,
                    amount=excluded.amount,
                    turnover_rate=excluded.turnover_rate
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                Bar bar = bars.get(index);
                statement.setString(1, symbol);
                statement.setDate(2, Date.valueOf(bar.tradeDate()));
                statement.setBigDecimal(3, bar.open());
                statement.setBigDecimal(4, bar.high());
                statement.setBigDecimal(5, bar.low());
                statement.setBigDecimal(6, bar.close());
                statement.setLong(7, bar.volume());
                statement.setBigDecimal(8, bar.amount());
                statement.setBigDecimal(9, bar.turnoverRate());
            }

            @Override
            public int getBatchSize() {
                return bars.size();
            }
        });
    }

    private void ensureSecurity(String symbol) {
        String exchange = symbol.startsWith("6") ? "SH" : "SZ";
        jdbcTemplate.update("""
                INSERT INTO securities(symbol, name, exchange, board, is_st, is_active)
                VALUES (?, ?, ?, 'MAIN', false, true)
                ON CONFLICT(symbol) DO NOTHING
                """, symbol, symbol, exchange);
    }

    private static void validate(
            String symbol,
            List<Bar> bars,
            String sourceCode,
            String sourceRevision,
            String captureType
    ) {
        if (symbol == null || !symbol.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("股票代码必须是6位数字");
        }
        String normalizedSource = sourceCode == null || sourceCode.isBlank()
                ? DEFAULT_SOURCE : sourceCode.strip();
        if (normalizedSource.length() > 128) {
            throw new IllegalArgumentException("行情来源代码长度不能超过128");
        }
        if (sourceRevision != null
                && (sourceRevision.isBlank() || sourceRevision.length() > 128)) {
            throw new IllegalArgumentException("来源revision为空或过长");
        }
        if (!List.of(
                "MARKET_DATA_PERSISTENCE",
                "BOOTSTRAP_CURRENT_STATE",
                "TEST_FIXTURE").contains(captureType)) {
            throw new IllegalArgumentException("不支持的观察捕获类型");
        }
        LocalDate previousDate = null;
        for (Bar bar : bars) {
            if (bar == null || !symbol.equals(bar.symbol()) || bar.tradeDate() == null
                    || bar.open() == null || bar.high() == null
                    || bar.low() == null || bar.close() == null
                    || bar.open().signum() <= 0 || bar.high().signum() <= 0
                    || bar.low().signum() <= 0 || bar.close().signum() <= 0
                    || bar.volume() < 0
                    || bar.amount() != null && bar.amount().signum() < 0
                    || bar.turnoverRate() != null && bar.turnoverRate().signum() < 0
                    || bar.high().compareTo(bar.open()) < 0
                    || bar.high().compareTo(bar.close()) < 0
                    || bar.high().compareTo(bar.low()) < 0
                    || bar.low().compareTo(bar.open()) > 0
                    || bar.low().compareTo(bar.close()) > 0
                    || (previousDate != null
                    && !bar.tradeDate().isAfter(previousDate))
                    || !fitsNumeric(bar.open(), 18, 4)
                    || !fitsNumeric(bar.high(), 18, 4)
                    || !fitsNumeric(bar.low(), 18, 4)
                    || !fitsNumeric(bar.close(), 18, 4)
                    || !fitsNullableNumeric(bar.amount(), 24, 4)
                    || !fitsNullableNumeric(bar.turnoverRate(), 10, 4)) {
                throw new IllegalArgumentException("行情K线字段或OHLC关系非法");
            }
            previousDate = bar.tradeDate();
        }
    }

    private static boolean fitsNullableNumeric(
            BigDecimal value,
            int precision,
            int scale
    ) {
        return value == null || fitsNumeric(value, precision, scale);
    }

    private static boolean fitsNumeric(
            BigDecimal value,
            int precision,
            int scale
    ) {
        if (value == null) return false;
        BigDecimal normalized = value.stripTrailingZeros();
        int fractionalDigits = Math.max(normalized.scale(), 0);
        int integerDigits = Math.max(
                normalized.precision() - normalized.scale(),
                0);
        return fractionalDigits <= scale
                && integerDigits <= precision - scale;
    }

    private static void putNullableDecimal(
            ObjectNode value,
            String field,
            java.math.BigDecimal decimal
    ) {
        if (decimal == null) value.putNull(field);
        else value.put(field, decimal);
    }

    public record CaptureResult(
            String batchVersion,
            String datasetVersion,
            int inputCount,
            int appendedObservationCount
    ) {
    }
}
