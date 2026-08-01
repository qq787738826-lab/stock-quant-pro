package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionSource;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ExecutionStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Reservation;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.StoredExecution;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Transition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable, process-safe reservation and monotonic state transitions. */
final class TushareControlledAcceptanceExecutionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    TushareControlledAcceptanceExecutionRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requiresNew = new TransactionTemplate(Objects.requireNonNull(
                transactionManager, "transactionManager"));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    StoredExecution reserve(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        return Objects.requireNonNull(requiresNew.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO tushare_controlled_acceptance_execution (
                      acceptance_id, authorization_fingerprint, execution_source,
                      provider_code, source_instrument_id, trade_date, endpoints_json,
                      code_baseline_commit, artifact_sha256, database_identity,
                      database_user, schema_name, schema_version, created_at,
                      authorization_expires_at, status, executor_version,
                      qualification_rule_version
                    ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?,
                              'AUTHORIZED', ?, ?)
                    ON CONFLICT (acceptance_id) DO NOTHING
                    """,
                    reservation.acceptanceId(), reservation.authorizationFingerprint(),
                    reservation.executionSource().name(), reservation.providerCode(),
                    reservation.sourceInstrumentId(), reservation.tradeDate(),
                    endpointsJson(reservation), reservation.codeBaselineCommit(),
                    reservation.artifactSha256(), reservation.databaseIdentity(),
                    reservation.databaseUser(), reservation.schemaName(),
                    reservation.schemaVersion(), Timestamp.from(reservation.createdAt()),
                    Timestamp.from(reservation.authorizationExpiresAt()),
                    TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                    TushareControlledAcceptanceExecution.RULE_VERSION);
            if (inserted != 1) {
                throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_ID_ALREADY_RESERVED");
            }
            Instant now = clock.instant();
            transitionExact(reservation.acceptanceId(), ExecutionStatus.AUTHORIZED,
                    ExecutionStatus.RESERVED, now, null, null, 0, 0, null, null);
            return require(reservation.acceptanceId());
        }));
    }

    StoredExecution markRunning(String acceptanceId) {
        return transition(acceptanceId, ExecutionStatus.RESERVED,
                ExecutionStatus.RUNNING, clock.instant(), null,
                null, 0, 0, null, null);
    }

    StoredExecution markCandidate(
            String acceptanceId,
            long batchId,
            int providerCalls,
            String evidenceJson,
            String evidenceDigest
    ) {
        return transition(acceptanceId, ExecutionStatus.RUNNING,
                ExecutionStatus.SUCCEEDED_CANDIDATE, clock.instant(), null,
                null, providerCalls, 0, batchId, new Evidence(evidenceJson, evidenceDigest));
    }

    StoredExecution markPassed(String acceptanceId) {
        return transition(acceptanceId, ExecutionStatus.SUCCEEDED_CANDIDATE,
                ExecutionStatus.PASSED, clock.instant(), null,
                null, 3, 0, null, null);
    }

    StoredExecution markFailed(
            String acceptanceId,
            ExecutionStatus expected,
            ExecutionStatus failure,
            String failureStage,
            String safeReason,
            int providerCalls
    ) {
        if (!failure.name().startsWith("FAILED_") && failure != ExecutionStatus.INTERRUPTED
                && failure != ExecutionStatus.STALE
                && failure != ExecutionStatus.INCOMPATIBLE_BASELINE) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_FAILURE_STATUS_INVALID");
        }
        return transition(acceptanceId, expected, failure, clock.instant(),
                failureStage, safeReason, providerCalls, 0, null, null);
    }

    int recoverIncompleteExecutions() {
        List<StoredExecution> incomplete = jdbc.query("""
                SELECT * FROM tushare_controlled_acceptance_execution
                 WHERE status IN ('RESERVED','RUNNING','SUCCEEDED_CANDIDATE')
                 ORDER BY acceptance_id
                """, this::mapExecution);
        int recovered = 0;
        for (StoredExecution execution : incomplete) {
            String acceptanceId = execution.reservation().acceptanceId();
            StoredExecution current = execution;
            for (int attempt = 0; attempt < 4 && current.status().incomplete(); attempt++) {
                if (current.status() == ExecutionStatus.AUTHORIZED) {
                    break;
                }
                try {
                    markFailed(acceptanceId, current.status(),
                            ExecutionStatus.INTERRUPTED, "RECOVERY",
                            "TUSHARE_CONTROLLED_ACCEPTANCE_INTERRUPTED_RECOVERY",
                            current.providerCallCount());
                    recovered++;
                    break;
                } catch (IllegalStateException race) {
                    current = find(acceptanceId).orElseThrow(() -> race);
                    if (!current.status().incomplete()) {
                        break;
                    }
                }
            }
        }
        return recovered;
    }

    Optional<StoredExecution> find(String acceptanceId) {
        List<StoredExecution> values = jdbc.query("""
                SELECT * FROM tushare_controlled_acceptance_execution
                 WHERE acceptance_id = ?
                """, this::mapExecution,
                TushareControlledAcceptanceExecution.safeId(acceptanceId));
        return values.stream().findFirst();
    }

    List<Transition> history(String acceptanceId) {
        return jdbc.query("""
                SELECT acceptance_id, from_status, to_status, transition_at,
                       row_version, safe_reason_code
                  FROM tushare_controlled_acceptance_transition
                 WHERE acceptance_id = ? ORDER BY transition_id
                """, (rs, row) -> new Transition(
                rs.getString("acceptance_id"),
                rs.getString("from_status") == null ? null
                        : ExecutionStatus.valueOf(rs.getString("from_status")),
                ExecutionStatus.valueOf(rs.getString("to_status")),
                rs.getTimestamp("transition_at").toInstant(),
                rs.getLong("row_version"),
                rs.getString("safe_reason_code")),
                TushareControlledAcceptanceExecution.safeId(acceptanceId));
    }

    private StoredExecution transition(
            String acceptanceId,
            ExecutionStatus expected,
            ExecutionStatus next,
            Instant at,
            String failureStage,
            String safeReason,
            int providerCalls,
            int retryCount,
            Long batchId,
            Evidence evidence
    ) {
        return Objects.requireNonNull(requiresNew.execute(status -> {
            transitionExact(acceptanceId, expected, next, at, failureStage,
                    safeReason, providerCalls, retryCount, batchId, evidence);
            return require(acceptanceId);
        }));
    }

    private void transitionExact(
            String acceptanceId,
            ExecutionStatus expected,
            ExecutionStatus next,
            Instant at,
            String failureStage,
            String safeReason,
            int providerCalls,
            int retryCount,
            Long batchId,
            Evidence evidence
    ) {
        String json = evidence == null ? null : evidence.json();
        String digest = evidence == null ? null : evidence.digest();
        int changed = jdbc.update("""
                UPDATE tushare_controlled_acceptance_execution
                   SET status = ?,
                       reserved_at = CASE WHEN ? = 'RESERVED' THEN ? ELSE reserved_at END,
                       started_at = CASE WHEN ? = 'RUNNING' THEN ? ELSE started_at END,
                       finalized_at = CASE WHEN ? IN (
                           'SUCCEEDED_CANDIDATE','PASSED','FAILED_PRE_PROVIDER',
                           'FAILED_PROVIDER','FAILED_VALIDATION','FAILED_DATABASE_GUARD',
                           'FAILED_PERSISTENCE','FAILED_ROLLBACK','FAILED_QFQ',
                           'FAILED_OUTPUT_AUDIT','INTERRUPTED','STALE','INCOMPATIBLE_BASELINE'
                       ) THEN ? ELSE finalized_at END,
                       failure_stage = COALESCE(?, failure_stage),
                       safe_failure_reason = COALESCE(?, safe_failure_reason),
                       capture_batch_id = COALESCE(?, capture_batch_id),
                       provider_call_count = GREATEST(provider_call_count, ?),
                       retry_count = GREATEST(retry_count, ?),
                       evidence_summary_json = COALESCE(CAST(? AS jsonb), evidence_summary_json),
                       evidence_digest = COALESCE(?, evidence_digest),
                       row_version = row_version + 1
                 WHERE acceptance_id = ? AND status = ?
                """, next.name(), next.name(), Timestamp.from(at),
                next.name(), Timestamp.from(at), next.name(), Timestamp.from(at),
                failureStage, safeReason, batchId, providerCalls, retryCount,
                json, digest, TushareControlledAcceptanceExecution.safeId(acceptanceId),
                expected.name());
        if (changed != 1) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_TRANSITION_REJECTED");
        }
    }

    private StoredExecution require(String acceptanceId) {
        return find(acceptanceId).orElseThrow(() ->
                blocked("TUSHARE_CONTROLLED_ACCEPTANCE_RECORD_MISSING"));
    }

    private StoredExecution mapExecution(ResultSet rs, int row) throws SQLException {
        Reservation reservation = new Reservation(
                rs.getString("acceptance_id"),
                rs.getString("authorization_fingerprint"),
                ExecutionSource.valueOf(rs.getString("execution_source")),
                rs.getString("provider_code"),
                rs.getString("source_instrument_id"),
                rs.getObject("trade_date", java.time.LocalDate.class),
                parseEndpoints(rs.getString("endpoints_json")),
                rs.getString("code_baseline_commit"),
                rs.getString("artifact_sha256"),
                rs.getString("database_identity"),
                rs.getString("database_user"),
                rs.getString("schema_name"),
                rs.getInt("schema_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("authorization_expires_at").toInstant());
        return new StoredExecution(
                reservation, ExecutionStatus.valueOf(rs.getString("status")),
                instant(rs, "reserved_at"), instant(rs, "started_at"),
                instant(rs, "finalized_at"), rs.getString("failure_stage"),
                rs.getString("safe_failure_reason"),
                (Long) rs.getObject("capture_batch_id"),
                rs.getInt("provider_call_count"), rs.getInt("retry_count"),
                rs.getString("evidence_summary_json"), rs.getString("evidence_digest"),
                rs.getLong("row_version"));
    }

    private String endpointsJson(Reservation reservation) {
        List<String> names = reservation.endpoints().stream()
                .map(Enum::name).sorted().toList();
        try {
            return objectMapper.writeValueAsString(names);
        } catch (JsonProcessingException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_ENDPOINT_SERIALIZATION_FAILED");
        }
    }

    private java.util.Set<TushareControlledAcceptanceAuthorization.ControlledEndpoint>
    parseEndpoints(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<TushareControlledAcceptanceAuthorization.ControlledEndpoint> values = new ArrayList<>();
            node.forEach(value -> values.add(
                    TushareControlledAcceptanceAuthorization.ControlledEndpoint.valueOf(value.asText())));
            return java.util.Set.copyOf(values);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_ENDPOINT_DESERIALIZATION_FAILED");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Evidence(String json, String digest) {
        private Evidence {
            Objects.requireNonNull(json, "json");
            TushareControlledAcceptanceExecution.sha256(digest);
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
