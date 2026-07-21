package com.stockquant.server.agent.temporal;

import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusVersion;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class SecurityStatusHistoryRepository {

    private static final String COLUMNS = """
            id, symbol, exchange, board, listed, active, is_st,
            valid_from, valid_to, known_from, known_to, source_event_id,
            dataset_version_id, source, source_version, trust_level,
            status_hash, recorded_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public SecurityStatusHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SecurityStatusVersion> findById(long id) {
        TemporalValidation.positiveId(id, "id");
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM security_status_history WHERE id=?",
                this::map,
                id
        ).stream().findFirst();
    }

    public Optional<SecurityStatusVersion> findByIdForUpdate(long id) {
        TemporalValidation.positiveId(id, "id");
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM security_status_history WHERE id=? FOR UPDATE",
                this::map,
                id
        ).stream().findFirst();
    }

    public Optional<SecurityStatusVersion> findByIdempotencyKey(
            PublishSecurityStatusVersionCommand command
    ) {
        var key = TemporalIdempotencyKeys.securityStatus(command);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM security_status_history "
                        + "WHERE source_event_id=? AND valid_from=? AND known_from=?",
                this::map,
                key.sourceEventId(), key.validFrom(),
                TemporalJdbcSupport.timestamptz(key.knownFrom())
        ).stream().findFirst();
    }

    public Optional<SecurityStatusVersion> insertIfAbsent(
            PublishSecurityStatusVersionCommand command,
            String statusHash,
            Instant recordedAt
    ) {
        TemporalValidation.required(command, "command");
        TemporalValidation.sha256(statusHash, "statusHash");
        TemporalValidation.required(recordedAt, "recordedAt");
        List<SecurityStatusVersion> rows = jdbcTemplate.query("""
                INSERT INTO security_status_history(
                    symbol, exchange, board, listed, active, is_st,
                    valid_from, valid_to, known_from, known_to, source_event_id,
                    dataset_version_id, source, source_version, trust_level,
                    status_hash, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_event_id, valid_from, known_from)
                DO NOTHING
                RETURNING
                """ + COLUMNS, this::map,
                command.symbol(), command.exchange().name(), command.board(), command.listed(),
                command.active(), command.st(), command.validFrom(), command.validTo(),
                TemporalJdbcSupport.timestamptz(command.knownFrom()),
                TemporalJdbcSupport.timestamptz(command.knownTo()), command.sourceEventId(),
                command.datasetVersionId(), command.source(), command.sourceVersion(),
                command.trustLevel().name(), statusHash,
                TemporalJdbcSupport.timestamptz(recordedAt));
        return rows.stream().findFirst();
    }

    public int closeKnowledgeInterval(long id, Instant knownTo) {
        TemporalValidation.positiveId(id, "id");
        Instant normalized = TemporalValidation.instant(knownTo, "knownTo");
        return jdbcTemplate.update("""
                UPDATE security_status_history
                SET known_to=?
                WHERE id=? AND known_to IS NULL AND known_from<?
                """, TemporalJdbcSupport.timestamptz(normalized), id,
                TemporalJdbcSupport.timestamptz(normalized));
    }

    /**
     * Returns at most two candidates so callers can reject ambiguous bitemporal data.
     * recorded_at is intentionally not part of the as-of predicate.
     */
    public List<SecurityStatusVersion> findAsOfCandidates(
            String symbol,
            LocalDate validDate,
            Instant knowledgeCutoff
    ) {
        String normalizedSymbol = TemporalValidation.symbol(symbol);
        TemporalValidation.required(validDate, "validDate");
        Instant cutoff = TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff");
        return jdbcTemplate.query("""
                SELECT
                """ + COLUMNS + """
                FROM security_status_history
                WHERE symbol=?
                  AND valid_from<=?
                  AND (valid_to IS NULL OR ?<valid_to)
                  AND known_from<=?
                  AND (known_to IS NULL OR ?<known_to)
                ORDER BY known_from DESC, id DESC
                LIMIT 2
                """, this::map,
                normalizedSymbol, validDate, validDate,
                TemporalJdbcSupport.timestamptz(cutoff),
                TemporalJdbcSupport.timestamptz(cutoff));
    }

    private SecurityStatusVersion map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SecurityStatusVersion(
                resultSet.getLong("id"),
                resultSet.getString("symbol"),
                MarketExchange.valueOf(resultSet.getString("exchange")),
                resultSet.getString("board"),
                resultSet.getBoolean("listed"),
                resultSet.getBoolean("active"),
                resultSet.getBoolean("is_st"),
                resultSet.getObject("valid_from", LocalDate.class),
                resultSet.getObject("valid_to", LocalDate.class),
                TemporalJdbcSupport.instant(resultSet.getObject("known_from")),
                TemporalJdbcSupport.instant(resultSet.getObject("known_to")),
                resultSet.getLong("source_event_id"),
                resultSet.getLong("dataset_version_id"),
                resultSet.getString("source"),
                resultSet.getString("source_version"),
                TemporalTrustLevel.valueOf(resultSet.getString("trust_level")),
                resultSet.getString("status_hash"),
                TemporalJdbcSupport.instant(resultSet.getObject("recorded_at"))
        );
    }
}
