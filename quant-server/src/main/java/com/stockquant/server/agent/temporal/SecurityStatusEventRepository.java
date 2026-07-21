package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.temporal.TemporalModels.AppendSecurityStatusEventCommand;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusEvent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class SecurityStatusEventRepository {

    private static final String COLUMNS = """
            id, dataset_version_id, symbol, event_type, effective_from, effective_to,
            published_at, known_at, recorded_at, source, source_version,
            source_record_id, source_revision, trust_level, payload::text AS payload,
            payload_hash, supersedes_event_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SecurityStatusEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<SecurityStatusEvent> findById(long id) {
        TemporalValidation.positiveId(id, "id");
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM security_status_events WHERE id=?",
                this::map,
                id
        ).stream().findFirst();
    }

    public Optional<SecurityStatusEvent> findByIdempotencyKey(
            AppendSecurityStatusEventCommand command
    ) {
        var key = TemporalIdempotencyKeys.securityEvent(command);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM security_status_events "
                        + "WHERE source=? AND source_version=? "
                        + "AND source_record_id=? AND source_revision=?",
                this::map,
                key.source(), key.sourceVersion(), key.sourceRecordId(), key.sourceRevision()
        ).stream().findFirst();
    }

    public Optional<SecurityStatusEvent> insertIfAbsent(
            AppendSecurityStatusEventCommand command,
            Instant recordedAt
    ) {
        TemporalValidation.required(command, "command");
        TemporalValidation.required(recordedAt, "recordedAt");
        List<SecurityStatusEvent> rows = jdbcTemplate.query("""
                INSERT INTO security_status_events(
                    dataset_version_id, symbol, event_type, effective_from, effective_to,
                    published_at, known_at, recorded_at, source, source_version,
                    source_record_id, source_revision, trust_level, payload, payload_hash,
                    supersedes_event_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (source, source_version, source_record_id, source_revision)
                DO NOTHING
                RETURNING
                """ + COLUMNS, this::map,
                command.datasetVersionId(), command.symbol(), command.eventType().name(),
                command.effectiveFrom(), command.effectiveTo(),
                TemporalJdbcSupport.timestamptz(command.publishedAt()),
                TemporalJdbcSupport.timestamptz(command.knownAt()),
                TemporalJdbcSupport.timestamptz(recordedAt),
                command.source(), command.sourceVersion(), command.sourceRecordId(),
                command.sourceRevision(), command.trustLevel().name(),
                TemporalJdbcSupport.writeJson(objectMapper, command.payload()),
                command.payloadHash(), command.supersedesEventId());
        return rows.stream().findFirst();
    }

    private SecurityStatusEvent map(ResultSet resultSet, int rowNum) throws SQLException {
        return new SecurityStatusEvent(
                resultSet.getLong("id"),
                resultSet.getLong("dataset_version_id"),
                resultSet.getString("symbol"),
                SecurityStatusEventType.valueOf(resultSet.getString("event_type")),
                resultSet.getObject("effective_from", LocalDate.class),
                resultSet.getObject("effective_to", LocalDate.class),
                TemporalJdbcSupport.instant(resultSet.getObject("published_at")),
                TemporalJdbcSupport.instant(resultSet.getObject("known_at")),
                TemporalJdbcSupport.instant(resultSet.getObject("recorded_at")),
                resultSet.getString("source"),
                resultSet.getString("source_version"),
                resultSet.getString("source_record_id"),
                resultSet.getString("source_revision"),
                TemporalTrustLevel.valueOf(resultSet.getString("trust_level")),
                TemporalJdbcSupport.readJson(objectMapper, resultSet.getString("payload")),
                resultSet.getString("payload_hash"),
                resultSet.getObject("supersedes_event_id", Long.class)
        );
    }
}
