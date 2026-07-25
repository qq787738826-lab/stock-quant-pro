package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AnnouncementRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnnouncementRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void lockCaptureScope(String symbol) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtext(?))",
                resultSet -> {
                    // Consuming the single row holds the transaction-scoped lock.
                },
                AnnouncementContracts.SOURCE_CODE + ":" + symbol);
    }

    public Map<String, String> latestHashes(String symbol) {
        List<Map.Entry<String, String>> values = jdbcTemplate.query("""
                SELECT DISTINCT ON (source_announcement_id)
                       source_announcement_id, canonical_content_hash
                FROM announcement_observations
                WHERE source_code = ?
                  AND provider_contract_version = ?
                  AND symbol = ?
                ORDER BY source_announcement_id,
                         known_at DESC, recorded_at DESC, id DESC,
                         observation_version DESC
                """, (resultSet, rowNum) -> Map.entry(
                resultSet.getString("source_announcement_id"),
                resultSet.getString("canonical_content_hash")
        ), AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                symbol);
        Map<String, String> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(value.getKey(), value.getValue()));
        return Map.copyOf(result);
    }

    public long insertBatch(BatchInsert value) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO announcement_capture_batches (
                    batch_version, source_code, provider_contract_version,
                    symbol, requested_start_date, requested_end_date,
                    observed_at, complete, chunk_count, successful_chunk_count,
                    record_count, appended_count, provider_metadata_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                RETURNING id
                """, Long.class,
                value.batchVersion(),
                AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                value.symbol(),
                value.startDate(),
                value.endDate(),
                Timestamp.from(value.observedAt()),
                value.complete(),
                value.chunkCount(),
                value.successfulChunkCount(),
                value.recordCount(),
                value.appendedCount(),
                jsonb(value.providerMetadata()),
                Timestamp.from(value.recordedAt()));
        if (id == null) {
            throw new IllegalStateException("公告批次插入未返回ID");
        }
        return id;
    }

    public void insertObservation(
            long batchId,
            String batchVersion,
            AnnouncementFact value,
            Instant recordedAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO announcement_observations (
                    batch_id, batch_version, source_code, provider_contract_version,
                    source_announcement_id, source_identity_strength, symbol,
                    security_name, title, reported_publish_date,
                    reported_publish_time_precision, source_url,
                    normalized_source_url, source_url_hash,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    assurance_level, formal_eligible, pit_verified,
                    revision_relationship_guaranteed, raw_payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?::jsonb)
                """,
                batchId,
                batchVersion,
                AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                value.sourceAnnouncementId(),
                value.sourceIdentityStrength(),
                value.symbol(),
                value.securityName(),
                value.title(),
                value.reportedPublishDate(),
                AnnouncementContracts.PUBLISH_TIME_PRECISION,
                value.sourceUrl(),
                value.normalizedSourceUrl(),
                value.sourceUrlHash(),
                Timestamp.from(value.firstObservedAt()),
                Timestamp.from(value.firstObservedAt()),
                Timestamp.from(recordedAt),
                value.canonicalContentHash(),
                value.observationVersion(),
                AnnouncementContracts.ASSURANCE_LEVEL,
                false,
                false,
                false,
                jsonb(value.rawPayload()));
    }

    public List<CaptureBatchRecord> findBatches(String symbol, Instant cutoff) {
        return jdbcTemplate.query("""
                SELECT id, batch_version, source_code, provider_contract_version,
                       symbol, requested_start_date, requested_end_date,
                       observed_at, complete, chunk_count, successful_chunk_count,
                       record_count, appended_count
                FROM announcement_capture_batches
                WHERE symbol = ?
                  AND observed_at <= ?
                ORDER BY observed_at DESC, id DESC, batch_version DESC
                """, (resultSet, rowNum) -> new CaptureBatchRecord(
                resultSet.getLong("id"),
                resultSet.getString("batch_version"),
                resultSet.getString("source_code"),
                resultSet.getString("provider_contract_version"),
                resultSet.getString("symbol"),
                resultSet.getObject("requested_start_date", LocalDate.class),
                resultSet.getObject("requested_end_date", LocalDate.class),
                instant(resultSet.getTimestamp("observed_at")),
                resultSet.getBoolean("complete"),
                resultSet.getInt("chunk_count"),
                resultSet.getInt("successful_chunk_count"),
                resultSet.getInt("record_count"),
                resultSet.getInt("appended_count")
        ), symbol, Timestamp.from(cutoff));
    }

    public List<ObservationRecord> findAsOf(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            Instant cutoff
    ) {
        return jdbcTemplate.query("""
                SELECT source_announcement_id, source_identity_strength, symbol,
                       security_name, title, reported_publish_date, source_url,
                       normalized_source_url, source_url_hash, first_observed_at,
                       known_at, canonical_content_hash, observation_version,
                       batch_version, source_code, provider_contract_version, assurance_level,
                       formal_eligible, pit_verified,
                       revision_relationship_guaranteed,
                       reported_publish_time_precision, raw_payload_json
                FROM (
                    SELECT DISTINCT ON (source_announcement_id)
                           source_announcement_id, source_identity_strength, symbol,
                           security_name, title, reported_publish_date, source_url,
                           normalized_source_url, source_url_hash, first_observed_at,
                           known_at, canonical_content_hash, observation_version,
                           batch_version, source_code, provider_contract_version, assurance_level,
                           formal_eligible, pit_verified,
                           revision_relationship_guaranteed,
                           reported_publish_time_precision, raw_payload_json,
                           recorded_at, id
                    FROM announcement_observations
                    WHERE source_code = ?
                      AND provider_contract_version = ?
                      AND symbol = ?
                      AND reported_publish_date BETWEEN ? AND ?
                      AND known_at <= ?
                    ORDER BY source_announcement_id,
                             known_at DESC, recorded_at DESC, id DESC,
                             observation_version DESC
                ) latest
                ORDER BY reported_publish_date DESC, known_at DESC,
                         source_announcement_id, observation_version
                """, (resultSet, rowNum) -> new ObservationRecord(
                resultSet.getString("source_announcement_id"),
                resultSet.getString("source_identity_strength"),
                resultSet.getString("symbol"),
                resultSet.getString("security_name"),
                resultSet.getString("title"),
                resultSet.getObject("reported_publish_date", LocalDate.class),
                resultSet.getString("source_url"),
                resultSet.getString("normalized_source_url"),
                resultSet.getString("source_url_hash"),
                instant(resultSet.getTimestamp("first_observed_at")),
                instant(resultSet.getTimestamp("known_at")),
                resultSet.getString("canonical_content_hash"),
                resultSet.getString("observation_version"),
                resultSet.getString("batch_version"),
                resultSet.getString("source_code"),
                resultSet.getString("provider_contract_version"),
                resultSet.getString("assurance_level"),
                resultSet.getBoolean("formal_eligible"),
                resultSet.getBoolean("pit_verified"),
                resultSet.getBoolean("revision_relationship_guaranteed"),
                resultSet.getString("reported_publish_time_precision"),
                parseJson(resultSet.getString("raw_payload_json"))
        ), AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                symbol,
                startDate,
                endDate,
                Timestamp.from(cutoff));
    }

    private String jsonb(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("公告JSON必须是对象");
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("公告JSON序列化失败", error);
        }
    }

    private JsonNode parseJson(String value) {
        try {
            JsonNode result = objectMapper.readTree(value);
            if (result == null || !result.isObject()) {
                throw new IllegalStateException("数据库公告JSON不是对象");
            }
            return result;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库公告JSON解析失败", error);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record BatchInsert(
            String batchVersion,
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            Instant observedAt,
            boolean complete,
            int chunkCount,
            int successfulChunkCount,
            int recordCount,
            int appendedCount,
            JsonNode providerMetadata,
            Instant recordedAt
    ) {
    }

    public record CaptureBatchRecord(
            long id,
            String batchVersion,
            String sourceCode,
            String providerContractVersion,
            String symbol,
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            Instant observedAt,
            boolean complete,
            int chunkCount,
            int successfulChunkCount,
            int recordCount,
            int appendedCount
    ) {
    }

    public record ObservationRecord(
            String sourceAnnouncementId,
            String sourceIdentityStrength,
            String symbol,
            String securityName,
            String title,
            LocalDate reportedPublishDate,
            String sourceUrl,
            String normalizedSourceUrl,
            String sourceUrlHash,
            Instant firstObservedAt,
            Instant knownAt,
            String canonicalContentHash,
            String observationVersion,
            String batchVersion,
            String sourceCode,
            String providerContractVersion,
            String assuranceLevel,
            boolean formalEligible,
            boolean pitVerified,
            boolean revisionRelationshipGuaranteed,
            String reportedPublishTimePrecision,
            JsonNode rawPayload
    ) {
    }
}
