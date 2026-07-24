package com.stockquant.server.agent.backtest;

import com.stockquant.core.domain.Bar;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Repository
public class MarketDataObservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public MarketDataObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertBatch(
            String batchVersion,
            String sourceCode,
            String datasetVersion,
            String captureType,
            Instant observedAt,
            int recordCount,
            String sourceMetadataJson
    ) {
        Long value = jdbcTemplate.queryForObject("""
                INSERT INTO market_data_observation_batches(
                    batch_version, source_code, dataset_version, capture_type,
                    observed_at, recorded_at, record_count, source_metadata
                ) VALUES (?, ?, ?, ?, ?, clock_timestamp(), ?, ?::jsonb)
                RETURNING id
                """, Long.class,
                batchVersion,
                sourceCode,
                datasetVersion,
                captureType,
                Timestamp.from(observedAt),
                recordCount,
                sourceMetadataJson);
        if (value == null) throw new IllegalStateException("观察批次未返回ID");
        return value;
    }

    public boolean appendIfChanged(
            long batchId,
            String batchVersion,
            String datasetVersion,
            String sourceCode,
            String sourceRevision,
            Bar bar,
            Instant observedAt,
            String contentHash,
            String observationVersion
    ) {
        String lockKey = String.join(
                "|",
                sourceCode,
                bar.symbol(),
                bar.tradeDate().toString(),
                BacktestContracts.ADJUST_TYPE);
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> null,
                lockKey);
        List<LatestObservation> latest = jdbcTemplate.query("""
                SELECT canonical_content_hash, source_revision
                FROM daily_bar_observations
                WHERE source_code=?
                  AND symbol=?
                  AND trade_date=?
                  AND adjust_type=?
                ORDER BY known_at DESC, recorded_at DESC, id DESC,
                         observation_version DESC
                LIMIT 1
                """, (row, index) -> new LatestObservation(
                        row.getString("canonical_content_hash"),
                        row.getString("source_revision")),
                sourceCode,
                bar.symbol(),
                bar.tradeDate(),
                BacktestContracts.ADJUST_TYPE);
        if (!latest.isEmpty()
                && latest.get(0).contentHash().equals(contentHash)
                && Objects.equals(latest.get(0).sourceRevision(), sourceRevision)) {
            return false;
        }
        int inserted = jdbcTemplate.update("""
                INSERT INTO daily_bar_observations(
                    observation_version, batch_id, symbol, trade_date, adjust_type,
                    open, high, low, close, volume, amount, turnover_rate,
                    source_code, source_revision, dataset_version,
                    first_observed_at, known_at, recorded_at, canonical_content_hash
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, clock_timestamp(), ?
                )
                """,
                observationVersion,
                batchId,
                bar.symbol(),
                bar.tradeDate(),
                BacktestContracts.ADJUST_TYPE,
                bar.open(),
                bar.high(),
                bar.low(),
                bar.close(),
                bar.volume(),
                bar.amount(),
                bar.turnoverRate(),
                sourceCode,
                sourceRevision,
                datasetVersion,
                Timestamp.from(observedAt),
                Timestamp.from(observedAt),
                contentHash);
        if (inserted != 1) {
            throw new IllegalStateException(
                    "追加PIT日线观察失败：" + batchVersion + "/" + bar.tradeDate());
        }
        return true;
    }

    public List<ObservedDailyBar> findAsOf(
            String symbol,
            LocalDate requestTradeDate,
            Instant knowledgeCutoff,
            int limit
    ) {
        List<ObservedDailyBar> values = jdbcTemplate.query("""
                WITH ranked AS (
                    SELECT observation.id,
                           observation.observation_version,
                           observation.symbol,
                           observation.trade_date,
                           observation.adjust_type,
                           observation.open,
                           observation.high,
                           observation.low,
                           observation.close,
                           observation.volume,
                           observation.amount,
                           observation.turnover_rate,
                           observation.source_code,
                           observation.source_revision,
                           observation.dataset_version,
                           observation.first_observed_at,
                           observation.known_at,
                           observation.recorded_at,
                           observation.canonical_content_hash,
                           batch.batch_version,
                           batch.capture_type,
                           batch.observed_at AS batch_observed_at,
                           batch.recorded_at AS batch_recorded_at,
                           batch.source_metadata,
                           row_number() OVER (
                               PARTITION BY observation.symbol,
                                            observation.trade_date,
                                            observation.adjust_type
                               ORDER BY observation.known_at DESC,
                                        observation.recorded_at DESC,
                                        observation.id DESC,
                                        observation.observation_version DESC
                           ) AS version_rank
                    FROM daily_bar_observations observation
                    JOIN market_data_observation_batches batch
                      ON batch.id=observation.batch_id
                    WHERE observation.symbol=?
                      AND observation.adjust_type='QFQ'
                      AND observation.trade_date<=?
                      AND observation.known_at<=?
                )
                SELECT *
                FROM ranked
                WHERE version_rank=1
                ORDER BY trade_date DESC
                LIMIT ?
                """, (row, index) -> new ObservedDailyBar(
                row.getLong("id"),
                row.getString("observation_version"),
                row.getString("symbol"),
                row.getObject("trade_date", LocalDate.class),
                row.getString("adjust_type"),
                row.getBigDecimal("open"),
                row.getBigDecimal("high"),
                row.getBigDecimal("low"),
                row.getBigDecimal("close"),
                row.getLong("volume"),
                row.getBigDecimal("amount"),
                row.getBigDecimal("turnover_rate"),
                row.getString("source_code"),
                row.getString("source_revision"),
                row.getString("dataset_version"),
                instant(row.getTimestamp("first_observed_at")),
                instant(row.getTimestamp("known_at")),
                instant(row.getTimestamp("recorded_at")),
                row.getString("canonical_content_hash"),
                row.getString("batch_version"),
                row.getString("capture_type"),
                instant(row.getTimestamp("batch_observed_at")),
                instant(row.getTimestamp("batch_recorded_at")),
                row.getString("source_metadata")
        ), symbol, requestTradeDate, Timestamp.from(knowledgeCutoff), limit);
        return values.stream()
                .sorted(Comparator.comparing(ObservedDailyBar::tradeDate))
                .toList();
    }

    public long countOnOrBefore(String symbol, LocalDate requestTradeDate) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM daily_bar_observations
                WHERE symbol=? AND adjust_type='QFQ' AND trade_date<=?
                """, Long.class, symbol, requestTradeDate);
        return count == null ? 0L : count;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record LatestObservation(
            String contentHash,
            String sourceRevision
    ) {
    }

    public record ObservedDailyBar(
            long physicalId,
            String observationVersion,
            String symbol,
            LocalDate tradeDate,
            String adjustType,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            String sourceCode,
            String sourceRevision,
            String datasetVersion,
            Instant firstObservedAt,
            Instant knownAt,
            Instant recordedAt,
            String canonicalContentHash,
            String batchVersion,
            String captureType,
            Instant batchObservedAt,
            Instant batchRecordedAt,
            String sourceMetadataJson
    ) {
        public Bar toBar() {
            return new Bar(
                    symbol,
                    tradeDate,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    amount,
                    turnoverRate);
        }
    }
}
