package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializedSecurityEvent;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.NormalizationOutcome;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityEventManifestEntry;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityStatusEventLineage;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityStatusNormalizationResult;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityIdentity;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SourceSecurityIdentityMapping;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
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
public class SecurityStatusEventMaterializationRepository {

    private static final String EVENT_COLUMNS = """
            id, dataset_version_id, symbol, event_type, effective_from, effective_to,
            published_at, known_at, recorded_at, source, source_version,
            source_record_id, source_revision, trust_level, payload::text AS payload,
            payload_hash, supersedes_event_id, event_logical_key, event_contract_version,
            record_namespace, assurance_level, security_logical_key
            """;
    private static final String RESULT_COLUMNS = """
            id, processing_attempt_id, attempt_logical_key, outcome,
            event_id, event_logical_key, security_logical_key,
            predecessor_event_logical_key, normalizer_version, transition_rule_version,
            assurance_level, result_hash, error_code, recorded_at
            """;
    private static final String LINEAGE_COLUMNS = """
            id, event_id, event_logical_key, dataset_version_id, dataset_logical_key,
            raw_record_id, raw_record_logical_key, ingestion_run_id,
            ingestion_run_logical_key, processing_attempt_id, attempt_logical_key,
            security_identity_id, security_logical_key, mapping_id, mapping_logical_key,
            predecessor_event_id, predecessor_event_logical_key, record_namespace,
            event_contract_version, normalizer_version, transition_rule_version,
            assurance_level, lineage_hash, recorded_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SecurityStatusEventMaterializationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void lockSecurityIdentity(String securityLogicalKey) {
        jdbc.query("""
                SELECT pg_advisory_xact_lock(
                    hashtextextended(current_schema() || chr(31) || ?, 0))
                """, result -> {
            if (!result.next()) {
                throw new IllegalStateException("security advisory lock returned no row");
            }
            return null;
        }, securityLogicalKey);
    }

    public Optional<MaterializedSecurityEvent> findEventByRawRevision(
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision
    ) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM security_status_events "
                        + "WHERE record_namespace=? AND source=? AND source_version=? "
                        + "AND source_record_id=? AND source_revision=? "
                        + "AND event_logical_key IS NOT NULL",
                this::mapEvent, namespace.name(), source, sourceVersion,
                sourceRecordId, sourceRevision).stream().findFirst();
    }

    public boolean unresolvedLegacyEventExistsByRawRevision(
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision
    ) {
        Boolean value = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM security_status_events
                    WHERE record_namespace=? AND source=? AND source_version=?
                      AND source_record_id=? AND source_revision=?
                      AND event_logical_key IS NULL
                )
                """, Boolean.class, namespace.name(), source, sourceVersion,
                sourceRecordId, sourceRevision);
        return Boolean.TRUE.equals(value);
    }

    public Optional<MaterializedSecurityEvent> findEventByLogicalKey(String logicalKey) {
        return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM security_status_events "
                        + "WHERE event_logical_key=?",
                this::mapEvent, logicalKey).stream().findFirst();
    }

    public Optional<MaterializedSecurityEvent> findChainHead(
            RunNamespace namespace,
            String securityLogicalKey,
            String eventContractVersion
    ) {
        List<MaterializedSecurityEvent> values = jdbc.query("""
                SELECT %s
                FROM security_status_events event
                WHERE event.record_namespace=?
                  AND event.security_logical_key=?
                  AND event.event_contract_version=?
                  AND event.event_logical_key IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM security_status_events successor
                    WHERE successor.supersedes_event_id=event.id
                      AND successor.event_logical_key IS NOT NULL
                  )
                ORDER BY event.effective_from DESC, event.known_at DESC, event.id DESC
                LIMIT 2
                """.formatted(EVENT_COLUMNS), this::mapEvent,
                namespace.name(), securityLogicalKey, eventContractVersion);
        if (values.size() > 1) {
            throw new IngestionDataConflictException("security event chain has multiple heads");
        }
        return values.stream().findFirst();
    }

    Optional<MaterializedSecurityEvent> insertEvent(
            long datasetVersionId,
            String symbol,
            SecurityStatusEventType eventType,
            LocalDate effectiveFrom,
            Instant publishedAt,
            Instant knownAt,
            Instant recordedAt,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            TemporalTrustLevel trustLevel,
            JsonNode payload,
            String payloadHash,
            Long predecessorEventId,
            String eventLogicalKey,
            String eventContractVersion,
            RunNamespace namespace,
            AssuranceLevel assurance,
            String securityLogicalKey
    ) {
        return jdbc.query("""
                INSERT INTO security_status_events(
                    dataset_version_id, symbol, event_type, effective_from, effective_to,
                    published_at, known_at, recorded_at, source, source_version,
                    source_record_id, source_revision, trust_level, payload, payload_hash,
                    supersedes_event_id, event_logical_key, event_contract_version,
                    record_namespace, assurance_level, security_logical_key
                ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_logical_key) DO NOTHING
                RETURNING
                """ + EVENT_COLUMNS, this::mapEvent,
                datasetVersionId, symbol, eventType.name(), effectiveFrom,
                timestamp(publishedAt), timestamp(knownAt), timestamp(recordedAt), source,
                sourceVersion, sourceRecordId, sourceRevision, trustLevel.name(),
                writeJson(payload), payloadHash, predecessorEventId, eventLogicalKey,
                eventContractVersion, namespace.name(), assurance.name(), securityLogicalKey)
                .stream().findFirst();
    }

    public Optional<SecurityStatusEventLineage> findLineageByEvent(long eventId) {
        return jdbc.query("SELECT " + LINEAGE_COLUMNS
                        + " FROM security_status_event_lineage WHERE event_id=?",
                this::mapLineage, eventId).stream().findFirst();
    }

    Optional<SecurityStatusEventLineage> insertLineage(
            MaterializedSecurityEvent event,
            long datasetVersionId,
            String datasetLogicalKey,
            long rawRecordId,
            String rawRecordLogicalKey,
            long runId,
            String runLogicalKey,
            long attemptId,
            String attemptLogicalKey,
            SecurityIdentity identity,
            SourceSecurityIdentityMapping mapping,
            MaterializedSecurityEvent predecessor,
            String normalizerVersion,
            String transitionRuleVersion,
            AssuranceLevel assurance,
            String lineageHash
    ) {
        return jdbc.query("""
                INSERT INTO security_status_event_lineage(
                    event_id, event_logical_key, dataset_version_id, dataset_logical_key,
                    raw_record_id, raw_record_logical_key, ingestion_run_id,
                    ingestion_run_logical_key, processing_attempt_id, attempt_logical_key,
                    security_identity_id, security_logical_key, mapping_id, mapping_logical_key,
                    predecessor_event_id, predecessor_event_logical_key, record_namespace,
                    event_contract_version, normalizer_version, transition_rule_version,
                    assurance_level, lineage_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                RETURNING
                """ + LINEAGE_COLUMNS, this::mapLineage,
                event.id(), event.eventLogicalKey(), datasetVersionId, datasetLogicalKey,
                rawRecordId, rawRecordLogicalKey, runId, runLogicalKey, attemptId,
                attemptLogicalKey, identity.id(), identity.securityLogicalKey(), mapping.id(),
                mapping.mappingLogicalKey(), predecessor == null ? null : predecessor.id(),
                predecessor == null ? null : predecessor.eventLogicalKey(),
                event.recordNamespace().name(), event.eventContractVersion(), normalizerVersion,
                transitionRuleVersion, assurance.name(), lineageHash).stream().findFirst();
    }

    public Optional<SecurityStatusNormalizationResult> findNormalizationResult(long attemptId) {
        return jdbc.query("SELECT " + RESULT_COLUMNS
                        + " FROM security_status_normalization_results "
                        + "WHERE processing_attempt_id=?",
                this::mapResult, attemptId).stream().findFirst();
    }

    Optional<SecurityStatusNormalizationResult> insertNormalizationResult(
            long attemptId,
            String attemptLogicalKey,
            NormalizationOutcome outcome,
            MaterializedSecurityEvent event,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            String normalizerVersion,
            String transitionRuleVersion,
            AssuranceLevel assurance,
            String resultHash,
            String errorCode
    ) {
        return jdbc.query("""
                INSERT INTO security_status_normalization_results(
                    processing_attempt_id, attempt_logical_key, outcome,
                    event_id, event_logical_key, security_logical_key,
                    predecessor_event_logical_key, normalizer_version,
                    transition_rule_version, assurance_level, result_hash, error_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (processing_attempt_id) DO NOTHING
                RETURNING
                """ + RESULT_COLUMNS, this::mapResult,
                attemptId, attemptLogicalKey, outcome.name(), event == null ? null : event.id(),
                event == null ? null : event.eventLogicalKey(), securityLogicalKey,
                predecessorEventLogicalKey, normalizerVersion, transitionRuleVersion,
                assurance.name(), resultHash, errorCode).stream().findFirst();
    }

    public List<SecurityEventManifestEntry> findManifestV2Entries(long runId) {
        return jdbc.query("""
                SELECT raw.raw_record_logical_key,
                       raw.payload_hash AS raw_payload_hash,
                       raw.source_trust_level,
                       raw.source_instrument_id,
                       attempt.attempt_no,
                       attempt.attempt_logical_key,
                       attempt.status AS attempt_status,
                       attempt.processor_version,
                       attempt.contract_version,
                       attempt.published_at_verification,
                       attempt.requested_assurance_level,
                       attempt.knowledge_time_policy_version,
                       attempt.assurance_level AS attempt_assurance_level,
                       attempt.derived_known_from,
                       attempt.error_code AS attempt_error_code,
                       attempt.result_hash AS attempt_result_hash,
                       result.outcome,
                       result.event_logical_key,
                       event.event_type,
                       event.payload_hash AS event_payload_hash,
                       event.assurance_level AS event_assurance_level,
                       result.security_logical_key,
                       result.predecessor_event_logical_key,
                       run.run_namespace AS record_namespace,
                       result.normalizer_version,
                       result.transition_rule_version,
                       result.assurance_level AS result_assurance_level,
                       result.result_hash AS normalization_result_hash,
                       lineage.lineage_hash
                FROM security_status_ingestion_run_records receipt
                JOIN market_data_ingestion_runs run ON run.id=receipt.ingestion_run_id
                JOIN security_status_raw_records raw ON raw.id=receipt.raw_record_id
                JOIN security_status_processing_attempts attempt
                  ON attempt.ingestion_run_id=receipt.ingestion_run_id
                 AND attempt.raw_record_id=receipt.raw_record_id
                JOIN security_status_normalization_results result
                  ON result.processing_attempt_id=attempt.id
                LEFT JOIN security_status_events event ON event.id=result.event_id
                LEFT JOIN security_status_event_lineage lineage ON lineage.event_id=event.id
                WHERE receipt.ingestion_run_id=?
                ORDER BY raw.raw_record_logical_key, attempt.attempt_no,
                         attempt.attempt_logical_key
                """, this::mapManifestEntry, runId);
    }

    public List<SecurityStatusNormalizationResult> findFinalResults(long runId) {
        return jdbc.query("""
                SELECT %s
                FROM security_status_processing_attempts attempt
                JOIN security_status_normalization_results result
                  ON result.processing_attempt_id=attempt.id
                WHERE attempt.ingestion_run_id=?
                  AND attempt.attempt_no=(
                    SELECT max(latest.attempt_no)
                    FROM security_status_processing_attempts latest
                    WHERE latest.ingestion_run_id=attempt.ingestion_run_id
                      AND latest.raw_record_id=attempt.raw_record_id
                  )
                ORDER BY attempt.raw_record_id
                """.formatted(prefixResultColumns("result")), this::mapResult, runId);
    }

    private MaterializedSecurityEvent mapEvent(ResultSet result, int row) throws SQLException {
        return new MaterializedSecurityEvent(
                result.getLong("id"), result.getLong("dataset_version_id"),
                result.getString("symbol"),
                SecurityStatusEventType.valueOf(result.getString("event_type")),
                result.getObject("effective_from", LocalDate.class),
                result.getObject("effective_to", LocalDate.class),
                instant(result.getObject("published_at")), instant(result.getObject("known_at")),
                instant(result.getObject("recorded_at")), result.getString("source"),
                result.getString("source_version"), result.getString("source_record_id"),
                result.getString("source_revision"),
                TemporalTrustLevel.valueOf(result.getString("trust_level")),
                readJson(result.getString("payload")), result.getString("payload_hash"),
                result.getObject("supersedes_event_id", Long.class),
                result.getString("event_logical_key"),
                result.getString("event_contract_version"),
                RunNamespace.valueOf(result.getString("record_namespace")),
                AssuranceLevel.valueOf(result.getString("assurance_level")),
                result.getString("security_logical_key"));
    }

    private SecurityStatusEventLineage mapLineage(ResultSet result, int row) throws SQLException {
        return new SecurityStatusEventLineage(
                result.getLong("id"), result.getLong("event_id"),
                result.getString("event_logical_key"), result.getLong("dataset_version_id"),
                result.getString("dataset_logical_key"), result.getLong("raw_record_id"),
                result.getString("raw_record_logical_key"), result.getLong("ingestion_run_id"),
                result.getString("ingestion_run_logical_key"),
                result.getLong("processing_attempt_id"),
                result.getString("attempt_logical_key"),
                result.getLong("security_identity_id"),
                result.getString("security_logical_key"), result.getLong("mapping_id"),
                result.getString("mapping_logical_key"),
                result.getObject("predecessor_event_id", Long.class),
                result.getString("predecessor_event_logical_key"),
                RunNamespace.valueOf(result.getString("record_namespace")),
                result.getString("event_contract_version"),
                result.getString("normalizer_version"),
                result.getString("transition_rule_version"),
                AssuranceLevel.valueOf(result.getString("assurance_level")),
                result.getString("lineage_hash"), instant(result.getObject("recorded_at")));
    }

    private SecurityStatusNormalizationResult mapResult(ResultSet result, int row)
            throws SQLException {
        return new SecurityStatusNormalizationResult(
                result.getLong("id"), result.getLong("processing_attempt_id"),
                result.getString("attempt_logical_key"),
                NormalizationOutcome.valueOf(result.getString("outcome")),
                result.getObject("event_id", Long.class), result.getString("event_logical_key"),
                result.getString("security_logical_key"),
                result.getString("predecessor_event_logical_key"),
                result.getString("normalizer_version"),
                result.getString("transition_rule_version"),
                AssuranceLevel.valueOf(result.getString("assurance_level")),
                result.getString("result_hash"), result.getString("error_code"),
                instant(result.getObject("recorded_at")));
    }

    private SecurityEventManifestEntry mapManifestEntry(ResultSet result, int row)
            throws SQLException {
        String eventType = result.getString("event_type");
        String eventAssurance = result.getString("event_assurance_level");
        return new SecurityEventManifestEntry(
                result.getString("raw_record_logical_key"),
                result.getString("raw_payload_hash"),
                TemporalTrustLevel.valueOf(result.getString("source_trust_level")),
                result.getString("source_instrument_id"), result.getInt("attempt_no"),
                result.getString("attempt_logical_key"),
                AttemptStatus.valueOf(result.getString("attempt_status")),
                result.getString("processor_version"), result.getString("contract_version"),
                PublicationTimeVerification.valueOf(
                        result.getString("published_at_verification")),
                AssuranceLevel.valueOf(result.getString("requested_assurance_level")),
                result.getString("knowledge_time_policy_version"),
                AssuranceLevel.valueOf(result.getString("attempt_assurance_level")),
                instant(result.getObject("derived_known_from")),
                result.getString("attempt_error_code"),
                result.getString("attempt_result_hash"),
                NormalizationOutcome.valueOf(result.getString("outcome")),
                result.getString("event_logical_key"),
                eventType == null ? null : SecurityStatusEventType.valueOf(eventType),
                result.getString("event_payload_hash"),
                eventAssurance == null ? null : AssuranceLevel.valueOf(eventAssurance),
                result.getString("security_logical_key"),
                result.getString("predecessor_event_logical_key"),
                RunNamespace.valueOf(result.getString("record_namespace")),
                result.getString("normalizer_version"),
                result.getString("transition_rule_version"),
                AssuranceLevel.valueOf(result.getString("result_assurance_level")),
                result.getString("normalization_result_hash"),
                result.getString("lineage_hash"));
    }

    private static String prefixResultColumns(String alias) {
        return RESULT_COLUMNS.lines()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> java.util.Arrays.stream(value.split(","))
                        .map(String::strip)
                        .filter(item -> !item.isEmpty())
                        .map(item -> alias + "." + item)
                        .reduce((left, right) -> left + ", " + right).orElse(""))
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + ", " + right).orElseThrow();
    }

    private String writeJson(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("event JSON cannot be serialized", error);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored event JSON is invalid", error);
        }
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
}
