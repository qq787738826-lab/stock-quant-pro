package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class MarketDataDatasetVersionRepository {

    private static final String COLUMNS = """
            id, dataset_type, source, source_version, connector_version,
            range_start, range_end, fetched_at, recorded_at, payload_hash,
            trust_level, metadata::text AS metadata
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MarketDataDatasetVersionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<DatasetVersion> findById(long id) {
        TemporalValidation.positiveId(id, "id");
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM market_data_dataset_versions WHERE id=?",
                this::map,
                id
        ).stream().findFirst();
    }

    public Optional<DatasetVersion> findByIdempotencyKey(RegisterDatasetVersionCommand command) {
        var key = TemporalIdempotencyKeys.dataset(command);
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM market_data_dataset_versions "
                        + "WHERE dataset_type=? AND source=? AND source_version=? "
                        + "AND connector_version=? AND range_start=? AND range_end=? "
                        + "AND payload_hash=?",
                this::map,
                key.datasetType(), key.source(), key.sourceVersion(),
                key.connectorVersion(), key.rangeStart(), key.rangeEnd(), key.payloadHash()
        ).stream().findFirst();
    }

    public Optional<DatasetVersion> insertIfAbsent(
            RegisterDatasetVersionCommand command,
            Instant recordedAt
    ) {
        TemporalValidation.required(command, "command");
        TemporalValidation.required(recordedAt, "recordedAt");
        List<DatasetVersion> rows = jdbcTemplate.query("""
                INSERT INTO market_data_dataset_versions(
                    dataset_type, source, source_version, connector_version,
                    range_start, range_end, fetched_at, recorded_at, payload_hash,
                    trust_level, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (dataset_type, source, source_version, connector_version,
                             range_start, range_end, payload_hash)
                DO NOTHING
                RETURNING
                """ + COLUMNS, this::map,
                command.datasetType(), command.source(), command.sourceVersion(),
                command.connectorVersion(), command.rangeStart(), command.rangeEnd(),
                TemporalJdbcSupport.timestamptz(command.fetchedAt()),
                TemporalJdbcSupport.timestamptz(recordedAt), command.payloadHash(),
                command.trustLevel().name(),
                TemporalJdbcSupport.writeJson(objectMapper, command.metadata()));
        return rows.stream().findFirst();
    }

    private DatasetVersion map(ResultSet resultSet, int rowNum) throws SQLException {
        return new DatasetVersion(
                resultSet.getLong("id"),
                resultSet.getString("dataset_type"),
                resultSet.getString("source"),
                resultSet.getString("source_version"),
                resultSet.getString("connector_version"),
                resultSet.getObject("range_start", LocalDate.class),
                resultSet.getObject("range_end", LocalDate.class),
                TemporalJdbcSupport.instant(resultSet.getObject("fetched_at")),
                TemporalJdbcSupport.instant(resultSet.getObject("recorded_at")),
                resultSet.getString("payload_hash"),
                TemporalTrustLevel.valueOf(resultSet.getString("trust_level")),
                TemporalJdbcSupport.readJson(objectMapper, resultSet.getString("metadata"))
        );
    }
}
