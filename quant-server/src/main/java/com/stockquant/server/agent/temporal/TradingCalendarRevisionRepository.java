package com.stockquant.server.agent.temporal;

import com.stockquant.server.agent.temporal.TemporalModels.AppendTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.TradingCalendarRevision;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class TradingCalendarRevisionRepository {

    private static final String COLUMNS = """
            id, dataset_version_id, exchange, trade_date, is_open, session_type,
            session_open_at, session_close_at,
            known_from, known_to, source, source_version, source_record_id,
            source_revision, trust_level, payload_hash, recorded_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public TradingCalendarRevisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TradingCalendarRevision> findById(long id) {
        TemporalValidation.positiveId(id, "id");
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM trading_calendar_revisions WHERE id=?",
                this::map,
                id
        ).stream().findFirst();
    }

    public Optional<TradingCalendarRevision> findByIdForUpdate(long id) {
        TemporalValidation.positiveId(id, "id");
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM trading_calendar_revisions WHERE id=? FOR UPDATE",
                this::map,
                id
        ).stream().findFirst();
    }

    public Optional<TradingCalendarRevision> findByIdempotencyKey(
            AppendTradingCalendarRevisionCommand command
    ) {
        var key = TemporalIdempotencyKeys.tradingCalendar(command);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM trading_calendar_revisions "
                        + "WHERE source=? AND source_version=? AND source_record_id=? "
                        + "AND source_revision=? AND exchange=? AND trade_date=?",
                this::map,
                key.source(), key.sourceVersion(), key.sourceRecordId(),
                key.sourceRevision(), key.exchange().name(), key.tradeDate()
        ).stream().findFirst();
    }

    public Optional<TradingCalendarRevision> insertIfAbsent(
            AppendTradingCalendarRevisionCommand command,
            Instant recordedAt
    ) {
        TemporalValidation.required(command, "command");
        TemporalValidation.required(recordedAt, "recordedAt");
        List<TradingCalendarRevision> rows = jdbcTemplate.query("""
                INSERT INTO trading_calendar_revisions(
                    dataset_version_id, exchange, trade_date, is_open, session_type,
                    session_open_at, session_close_at,
                    known_from, known_to, source, source_version, source_record_id,
                    source_revision, trust_level, payload_hash, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, source_version, source_record_id, source_revision,
                             exchange, trade_date)
                DO NOTHING
                RETURNING
                """ + COLUMNS, this::map,
                command.datasetVersionId(), command.exchange().name(), command.tradeDate(),
                command.open(), command.sessionType().name(),
                TemporalJdbcSupport.timestamptz(command.sessionOpenAt()),
                TemporalJdbcSupport.timestamptz(command.sessionCloseAt()),
                TemporalJdbcSupport.timestamptz(command.knownFrom()),
                TemporalJdbcSupport.timestamptz(command.knownTo()),
                command.source(), command.sourceVersion(), command.sourceRecordId(),
                command.sourceRevision(), command.trustLevel().name(), command.payloadHash(),
                TemporalJdbcSupport.timestamptz(recordedAt));
        return rows.stream().findFirst();
    }

    public int closeKnowledgeInterval(long id, Instant knownTo) {
        TemporalValidation.positiveId(id, "id");
        Instant normalized = TemporalValidation.instant(knownTo, "knownTo");
        return jdbcTemplate.update("""
                UPDATE trading_calendar_revisions
                SET known_to=?
                WHERE id=? AND known_to IS NULL AND known_from<?
                """, TemporalJdbcSupport.timestamptz(normalized), id,
                TemporalJdbcSupport.timestamptz(normalized));
    }

    /** recorded_at is intentionally excluded from this knowledge-time query. */
    public List<TradingCalendarRevision> findAsOfCandidates(
            MarketExchange exchange,
            LocalDate tradeDate,
            Instant knowledgeCutoff
    ) {
        TemporalValidation.required(exchange, "exchange");
        TemporalValidation.required(tradeDate, "tradeDate");
        Instant cutoff = TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff");
        return jdbcTemplate.query("""
                SELECT
                """ + COLUMNS + """
                FROM trading_calendar_revisions
                WHERE exchange=? AND trade_date=?
                  AND known_from<=?
                  AND (known_to IS NULL OR ?<known_to)
                ORDER BY known_from DESC, id DESC
                LIMIT 2
                """, this::map,
                exchange.name(), tradeDate,
                TemporalJdbcSupport.timestamptz(cutoff),
                TemporalJdbcSupport.timestamptz(cutoff));
    }

    public Optional<LocalDate> findPreviousOpenDateAsOf(
            MarketExchange exchange,
            LocalDate beforeDate,
            Instant knowledgeCutoff
    ) {
        return findAdjacentOpenDate(exchange, beforeDate, knowledgeCutoff, true);
    }

    public Optional<LocalDate> findNextOpenDateAsOf(
            MarketExchange exchange,
            LocalDate afterDate,
            Instant knowledgeCutoff
    ) {
        return findAdjacentOpenDate(exchange, afterDate, knowledgeCutoff, false);
    }

    private Optional<LocalDate> findAdjacentOpenDate(
            MarketExchange exchange,
            LocalDate anchorDate,
            Instant knowledgeCutoff,
            boolean previous
    ) {
        TemporalValidation.required(exchange, "exchange");
        TemporalValidation.required(anchorDate, "anchorDate");
        Instant cutoff = TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff");
        String operator = previous ? "<" : ">";
        String direction = previous ? "DESC" : "ASC";
        List<LocalDate> rows = jdbcTemplate.query(
                "SELECT trade_date FROM trading_calendar_revisions "
                        + "WHERE exchange=? AND trade_date" + operator + "? AND is_open=true "
                        + "AND known_from<=? AND (known_to IS NULL OR ?<known_to) "
                        + "ORDER BY trade_date " + direction + " LIMIT 1",
                (resultSet, rowNum) -> resultSet.getObject("trade_date", LocalDate.class),
                exchange.name(), anchorDate,
                TemporalJdbcSupport.timestamptz(cutoff),
                TemporalJdbcSupport.timestamptz(cutoff));
        return rows.stream().findFirst();
    }

    private TradingCalendarRevision map(ResultSet resultSet, int rowNum) throws SQLException {
        return new TradingCalendarRevision(
                resultSet.getLong("id"),
                resultSet.getLong("dataset_version_id"),
                MarketExchange.valueOf(resultSet.getString("exchange")),
                resultSet.getObject("trade_date", LocalDate.class),
                resultSet.getBoolean("is_open"),
                TradingSessionType.valueOf(resultSet.getString("session_type")),
                TemporalJdbcSupport.instant(resultSet.getObject("session_open_at")),
                TemporalJdbcSupport.instant(resultSet.getObject("session_close_at")),
                TemporalJdbcSupport.instant(resultSet.getObject("known_from")),
                TemporalJdbcSupport.instant(resultSet.getObject("known_to")),
                resultSet.getString("source"),
                resultSet.getString("source_version"),
                resultSet.getString("source_record_id"),
                resultSet.getString("source_revision"),
                TemporalTrustLevel.valueOf(resultSet.getString("trust_level")),
                resultSet.getString("payload_hash"),
                TemporalJdbcSupport.instant(resultSet.getObject("recorded_at"))
        );
    }
}
