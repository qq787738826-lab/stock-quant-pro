package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestEntry;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestContractVersion;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class MarketDataIngestionRepository {

    private static final String RUN_COLUMNS = """
            id, ingestion_run_logical_key, dataset_version_id, dataset_logical_key,
            dataset_type, run_namespace, operation_type, request_key, status,
            retry_of_run_logical_key, root_request_logical_key, run_attempt_number,
            requested_range_start, requested_range_end,
            manifest_contract_version,
            started_at, sealed_at, finished_at, manifest_hash,
            final_expected_count, final_received_count, final_accepted_count,
            final_rejected_count, assurance_level, created_at
            """;

    private static final String RAW_COMMON_COLUMNS = """
            id, first_ingestion_run_id, dataset_version_id, raw_record_logical_key,
            record_namespace, source, source_version, source_record_id, source_revision,
            %s,
            source_published_at, source_effective_date, source_effective_at,
            system_first_observed_at, system_recorded_at, source_trust_level,
            raw_payload::text AS raw_payload, payload_hash
            """;

    private static final String ATTEMPT_COLUMNS = """
            id, ingestion_run_id, raw_record_id, attempt_no, attempt_logical_key, status,
            processor_version, contract_version, published_at_verification,
            requested_assurance_level, derived_known_from,
            knowledge_time_policy_version, assurance_level,
            error_code, result_metadata::text AS result_metadata, result_hash,
            completed_at, system_recorded_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MarketDataIngestionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<IngestionRun> findRun(long id) {
        return jdbc.query(
                "SELECT " + RUN_COLUMNS + " FROM market_data_ingestion_runs WHERE id=?",
                this::mapRun, id).stream().findFirst();
    }

    public Optional<IngestionRun> lockRun(long id) {
        return jdbc.query(
                "SELECT " + RUN_COLUMNS
                        + " FROM market_data_ingestion_runs WHERE id=? FOR UPDATE",
                this::mapRun, id).stream().findFirst();
    }

    public Optional<IngestionRun> findRunByLogicalKey(String logicalKey) {
        return jdbc.query(
                "SELECT " + RUN_COLUMNS + " FROM market_data_ingestion_runs "
                        + "WHERE ingestion_run_logical_key=?",
                this::mapRun, logicalKey)
                .stream().findFirst();
    }

    public Optional<IngestionRun> findRunByRootAttempt(String rootLogicalKey, int attemptNumber) {
        return jdbc.query(
                "SELECT " + RUN_COLUMNS + " FROM market_data_ingestion_runs "
                        + "WHERE root_request_logical_key=? AND run_attempt_number=?",
                this::mapRun, rootLogicalKey, attemptNumber)
                .stream().findFirst();
    }

    public Optional<IngestionRun> insertRun(
            String logicalKey,
            long datasetVersionId,
            String datasetLogicalKey,
            DatasetType datasetType,
            RunNamespace namespace,
            OperationType operationType,
            String requestKey,
            String retryOfRunLogicalKey,
            String rootRequestLogicalKey,
            int runAttemptNumber,
            LocalDate requestedRangeStart,
            LocalDate requestedRangeEnd,
            ManifestContractVersion manifestContractVersion,
            Instant now
    ) {
        return jdbc.query("""
                INSERT INTO market_data_ingestion_runs(
                    ingestion_run_logical_key, dataset_version_id, dataset_logical_key,
                    dataset_type, run_namespace, operation_type, request_key,
                    retry_of_run_logical_key, root_request_logical_key, run_attempt_number,
                    requested_range_start, requested_range_end, manifest_contract_version, status,
                    started_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING
                """ + RUN_COLUMNS, this::mapRun,
                logicalKey, datasetVersionId, datasetLogicalKey, datasetType.name(),
                namespace.name(), operationType.name(), requestKey, retryOfRunLogicalKey,
                rootRequestLogicalKey, runAttemptNumber, requestedRangeStart, requestedRangeEnd,
                manifestContractVersion.name(), timestamp(now), timestamp(now))
                .stream().findFirst();
    }

    public Optional<RawRecord> findRawRecord(DatasetType type, long id) {
        return jdbc.query(
                "SELECT " + rawColumns(type) + " FROM " + rawTable(type) + " WHERE id=?",
                (rs, row) -> mapRaw(type, rs), id).stream().findFirst();
    }

    public Optional<RawRecord> findRawRecordBySource(
            DatasetType type,
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision
    ) {
        return jdbc.query(
                "SELECT " + rawColumns(type) + " FROM " + rawTable(type)
                        + " WHERE record_namespace=? AND source=? AND source_version=? "
                        + "AND source_record_id=? AND source_revision=?",
                (rs, row) -> mapRaw(type, rs), namespace.name(), source, sourceVersion,
                sourceRecordId, sourceRevision).stream().findFirst();
    }

    Optional<RawRecord> insertRawRecord(
            DatasetType type,
            long firstRunId,
            long datasetVersionId,
            String logicalKey,
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            String sourceInstrumentId,
            String exchange,
            LocalDate tradeDate,
            Instant sourcePublishedAt,
            LocalDate sourceEffectiveDate,
            Instant sourceEffectiveAt,
            Instant now,
            TemporalTrustLevel trustLevel,
            JsonNode payload,
            String payloadHash
    ) {
        String typeColumns = type == DatasetType.SECURITY_STATUS
                ? "source_instrument_id" : "exchange, trade_date";
        String typeValues = type == DatasetType.SECURITY_STATUS ? "?" : "?, ?";
        String sql = """
                INSERT INTO %s(
                    first_ingestion_run_id, dataset_version_id, raw_record_logical_key,
                    record_namespace, source, source_version, source_record_id, source_revision,
                    %s,
                    source_published_at, source_effective_date, source_effective_at,
                    system_first_observed_at, system_recorded_at, source_trust_level,
                    raw_payload, payload_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (record_namespace, source, source_version,
                             source_record_id, source_revision)
                DO NOTHING
                RETURNING
                %s
                """.formatted(rawTable(type), typeColumns, typeValues, rawColumns(type));
        Object[] commonTail = new Object[]{timestamp(sourcePublishedAt), sourceEffectiveDate,
                timestamp(sourceEffectiveAt), timestamp(now), timestamp(now), trustLevel.name(),
                writeJson(payload), payloadHash};
        java.util.ArrayList<Object> parameters = new java.util.ArrayList<>();
        java.util.Collections.addAll(parameters, firstRunId, datasetVersionId, logicalKey,
                namespace.name(), source, sourceVersion, sourceRecordId, sourceRevision);
        if (type == DatasetType.SECURITY_STATUS) {
            parameters.add(sourceInstrumentId);
        } else {
            parameters.add(exchange);
            parameters.add(tradeDate);
        }
        java.util.Collections.addAll(parameters, commonTail);
        return jdbc.query(sql, (rs, row) -> mapRaw(type, rs), parameters.toArray())
                .stream().findFirst();
    }

    boolean attachRawRecordToRun(
            DatasetType type,
            long runId,
            long rawRecordId,
            Instant receivedAt
    ) {
        int inserted = jdbc.update("""
                INSERT INTO %s(ingestion_run_id, raw_record_id, received_at)
                VALUES (?, ?, ?)
                ON CONFLICT (ingestion_run_id, raw_record_id) DO NOTHING
                """.formatted(runRecordTable(type)), runId, rawRecordId, timestamp(receivedAt));
        if (inserted == 1) return true;
        return isRawRecordAttachedToRun(type, runId, rawRecordId);
    }

    public boolean isRawRecordAttachedToRun(DatasetType type, long runId, long rawRecordId) {
        Boolean attached = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM %s WHERE ingestion_run_id=? AND raw_record_id=?
                )
                """.formatted(runRecordTable(type)), Boolean.class, runId, rawRecordId);
        return Boolean.TRUE.equals(attached);
    }

    public int countRunRawRecords(DatasetType type, long runId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + runRecordTable(type) + " WHERE ingestion_run_id=?",
                Integer.class, runId);
        return count == null ? 0 : count;
    }

    public Optional<ProcessingAttempt> findAttempt(
            DatasetType type,
            long runId,
            long rawRecordId,
            int attemptNo
    ) {
        return jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS + " FROM " + attemptTable(type)
                        + " WHERE ingestion_run_id=? AND raw_record_id=? AND attempt_no=?",
                (rs, row) -> mapAttempt(type, rs), runId, rawRecordId, attemptNo)
                .stream().findFirst();
    }

    Optional<ProcessingAttempt> insertAttempt(
            DatasetType type,
            long runId,
            long rawRecordId,
            int attemptNo,
            String logicalKey,
            AttemptStatus status,
            String processorVersion,
            String contractVersion,
            PublicationTimeVerification publicationVerification,
            AssuranceLevel requestedAssurance,
            Instant derivedKnownFrom,
            AssuranceLevel assurance,
            String errorCode,
            JsonNode resultMetadata,
            String resultHash
    ) {
        return jdbc.query("""
                INSERT INTO %s(
                    ingestion_run_id, raw_record_id, attempt_no, attempt_logical_key, status,
                    processor_version, contract_version, published_at_verification,
                    requested_assurance_level, derived_known_from,
                    knowledge_time_policy_version, assurance_level,
                    error_code, result_metadata, result_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (ingestion_run_id, raw_record_id, attempt_no)
                DO NOTHING
                RETURNING
                %s
                """.formatted(attemptTable(type), ATTEMPT_COLUMNS),
                (rs, row) -> mapAttempt(type, rs),
                runId, rawRecordId, attemptNo, logicalKey, status.name(),
                processorVersion, contractVersion,
                publicationVerification.name(), requestedAssurance.name(),
                timestamp(derivedKnownFrom),
                KnowledgeTimePolicyV1.VERSION, assurance.name(), errorCode,
                writeJson(resultMetadata), resultHash)
                .stream().findFirst();
    }

    public List<ProcessingAttempt> findFinalAttempts(DatasetType type, long runId) {
        return jdbc.query("""
                SELECT %s
                FROM %s
                WHERE ingestion_run_id=?
                  AND attempt_no=(
                    SELECT max(latest.attempt_no)
                    FROM %s latest
                    WHERE latest.ingestion_run_id=%s.ingestion_run_id
                      AND latest.raw_record_id=%s.raw_record_id
                  )
                ORDER BY raw_record_id
                """.formatted(
                ATTEMPT_COLUMNS, attemptTable(type), attemptTable(type),
                attemptTable(type), attemptTable(type)),
                (rs, row) -> mapAttempt(type, rs), runId);
    }

    public List<ManifestEntry> findManifestEntries(DatasetType type, long runId) {
        return jdbc.query("""
                SELECT raw.raw_record_logical_key, raw.payload_hash AS raw_payload_hash,
                       raw.source_trust_level, %s, attempt.attempt_no,
                       attempt.attempt_logical_key, attempt.status,
                       attempt.processor_version, attempt.contract_version,
                       attempt.published_at_verification,
                       attempt.requested_assurance_level,
                       attempt.knowledge_time_policy_version, attempt.assurance_level,
                       attempt.derived_known_from, attempt.error_code,
                       attempt.result_hash
                FROM %s receipt
                JOIN %s attempt ON attempt.ingestion_run_id=receipt.ingestion_run_id
                    AND attempt.raw_record_id=receipt.raw_record_id
                JOIN %s raw ON raw.id=attempt.raw_record_id
                WHERE receipt.ingestion_run_id=?
                ORDER BY raw.raw_record_logical_key, attempt.attempt_logical_key
                """.formatted(rawManifestColumns(type), runRecordTable(type),
                attemptTable(type), rawTable(type)),
                (rs, row) -> new ManifestEntry(
                rs.getString("raw_record_logical_key"),
                rs.getString("raw_payload_hash"),
                TemporalTrustLevel.valueOf(rs.getString("source_trust_level")),
                rs.getString("source_instrument_id"),
                rs.getString("exchange"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getInt("attempt_no"),
                rs.getString("attempt_logical_key"),
                AttemptStatus.valueOf(rs.getString("status")),
                rs.getString("processor_version"),
                rs.getString("contract_version"),
                PublicationTimeVerification.valueOf(
                        rs.getString("published_at_verification")),
                AssuranceLevel.valueOf(rs.getString("requested_assurance_level")),
                rs.getString("knowledge_time_policy_version"),
                AssuranceLevel.valueOf(rs.getString("assurance_level")),
                instant(rs.getObject("derived_known_from")),
                rs.getString("error_code"),
                rs.getString("result_hash")
        ), runId);
    }

    public Optional<IngestionRun> sealRun(
            long runId,
            RunStatus status,
            String manifestHash,
            int expected,
            int received,
            int accepted,
            int rejected,
            AssuranceLevel assurance
    ) {
        return jdbc.query("""
                UPDATE market_data_ingestion_runs
                SET status=?, manifest_hash=?,
                    final_expected_count=?, final_received_count=?,
                    final_accepted_count=?, final_rejected_count=?, assurance_level=?
                WHERE id=? AND status='RUNNING' AND sealed_at IS NULL
                RETURNING
                """ + RUN_COLUMNS, this::mapRun,
                status.name(), manifestHash,
                expected, received, accepted, rejected, assurance.name(), runId)
                .stream().findFirst();
    }

    private IngestionRun mapRun(ResultSet rs, int row) throws SQLException {
        return new IngestionRun(
                rs.getLong("id"), rs.getString("ingestion_run_logical_key"),
                rs.getLong("dataset_version_id"), rs.getString("dataset_logical_key"),
                DatasetType.valueOf(rs.getString("dataset_type")),
                RunNamespace.valueOf(rs.getString("run_namespace")),
                OperationType.valueOf(rs.getString("operation_type")),
                rs.getString("request_key"), rs.getString("retry_of_run_logical_key"),
                rs.getString("root_request_logical_key"), rs.getInt("run_attempt_number"),
                rs.getObject("requested_range_start", LocalDate.class),
                rs.getObject("requested_range_end", LocalDate.class),
                ManifestContractVersion.valueOf(rs.getString("manifest_contract_version")),
                RunStatus.valueOf(rs.getString("status")),
                instant(rs.getObject("started_at")), instant(rs.getObject("sealed_at")),
                instant(rs.getObject("finished_at")), rs.getString("manifest_hash"),
                integer(rs, "final_expected_count"), integer(rs, "final_received_count"),
                integer(rs, "final_accepted_count"), integer(rs, "final_rejected_count"),
                rs.getString("assurance_level") == null ? null
                        : AssuranceLevel.valueOf(rs.getString("assurance_level")),
                instant(rs.getObject("created_at")));
    }

    private RawRecord mapRaw(DatasetType type, ResultSet rs) throws SQLException {
        return new RawRecord(
                rs.getLong("id"), type, rs.getLong("first_ingestion_run_id"),
                rs.getLong("dataset_version_id"), rs.getString("raw_record_logical_key"),
                RunNamespace.valueOf(rs.getString("record_namespace")), rs.getString("source"),
                rs.getString("source_version"), rs.getString("source_record_id"),
                rs.getString("source_revision"), rs.getString("source_instrument_id"),
                rs.getString("exchange"), rs.getObject("trade_date", LocalDate.class),
                instant(rs.getObject("source_published_at")),
                rs.getObject("source_effective_date", LocalDate.class),
                instant(rs.getObject("source_effective_at")),
                instant(rs.getObject("system_first_observed_at")),
                instant(rs.getObject("system_recorded_at")),
                TemporalTrustLevel.valueOf(rs.getString("source_trust_level")),
                readJson(rs.getString("raw_payload")), rs.getString("payload_hash"));
    }

    private ProcessingAttempt mapAttempt(DatasetType type, ResultSet rs) throws SQLException {
        return new ProcessingAttempt(
                rs.getLong("id"), type, rs.getLong("ingestion_run_id"),
                rs.getLong("raw_record_id"), rs.getInt("attempt_no"),
                rs.getString("attempt_logical_key"),
                AttemptStatus.valueOf(rs.getString("status")), rs.getString("processor_version"),
                rs.getString("contract_version"), PublicationTimeVerification.valueOf(
                        rs.getString("published_at_verification")),
                AssuranceLevel.valueOf(rs.getString("requested_assurance_level")),
                instant(rs.getObject("derived_known_from")),
                rs.getString("knowledge_time_policy_version"),
                AssuranceLevel.valueOf(rs.getString("assurance_level")),
                rs.getString("error_code"), readJson(rs.getString("result_metadata")),
                rs.getString("result_hash"), instant(rs.getObject("completed_at")),
                instant(rs.getObject("system_recorded_at")));
    }

    private String writeJson(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("ingestion JSON cannot be serialized", error);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored ingestion JSON is invalid", error);
        }
    }

    private static String rawTable(DatasetType type) {
        return switch (type) {
            case SECURITY_STATUS -> "security_status_raw_records";
            case TRADING_CALENDAR -> "trading_calendar_raw_records";
        };
    }

    private static String rawColumns(DatasetType type) {
        String typeColumns = type == DatasetType.SECURITY_STATUS
                ? "source_instrument_id, NULL::VARCHAR AS exchange, NULL::DATE AS trade_date"
                : "NULL::VARCHAR AS source_instrument_id, exchange, trade_date";
        return RAW_COMMON_COLUMNS.formatted(typeColumns);
    }

    private static String rawManifestColumns(DatasetType type) {
        return type == DatasetType.SECURITY_STATUS
                ? "raw.source_instrument_id, NULL::VARCHAR AS exchange, NULL::DATE AS trade_date"
                : "NULL::VARCHAR AS source_instrument_id, raw.exchange, raw.trade_date";
    }

    private static String attemptTable(DatasetType type) {
        return switch (type) {
            case SECURITY_STATUS -> "security_status_processing_attempts";
            case TRADING_CALENDAR -> "trading_calendar_processing_attempts";
        };
    }

    private static String runRecordTable(DatasetType type) {
        return switch (type) {
            case SECURITY_STATUS -> "security_status_ingestion_run_records";
            case TRADING_CALENDAR -> "trading_calendar_ingestion_run_records";
        };
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return (Instant) value;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Integer.class);
    }
}
