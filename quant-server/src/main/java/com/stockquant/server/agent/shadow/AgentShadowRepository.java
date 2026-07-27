package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.DriftResult;
import com.stockquant.server.agent.shadow.AgentShadowModels.MetricsFilter;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.ReviewLabel;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionEntry;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowReview;
import com.stockquant.server.agent.shadow.AgentShadowModels.TerminalOutcome;
import com.stockquant.server.agent.shadow.AgentShadowModels.TriggerMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class AgentShadowRepository {

    private static final String BATCH_COLUMNS = """
            id, contract_version, status, trigger_mode, trade_date,
            rule_version, selection_mode, selection_hash,
            configured_max_symbols, selected_count, launched_count,
            terminal_count, determined_count, insufficient_count,
            failed_count, veto_count, data_quality_blocked_count,
            cache_hit_count, cancellation_requested,
            configuration_json::text AS configuration_json,
            error_message, started_at, finished_at, created_by,
            created_at, updated_at
            """;

    private static final String ITEM_COLUMNS = """
            id, batch_id, selection_order, symbol, selection_source,
            selection_source_ref, agent_task_id, task_newly_created,
            cache_hit, task_status, final_decision, gate_status, score,
            confidence, vetoed, outcome_class, primary_reason_code,
            reason_codes_json::text AS reason_codes_json,
            run_snapshot_json::text AS run_snapshot_json,
            context_hash, duration_ms, previous_item_id, context_changed,
            decision_changed, score_delta, confidence_delta,
            changed_agents_json::text AS changed_agents_json,
            error_message, started_at, finished_at, created_at, updated_at
            """;

    private static final String REVIEW_COLUMNS = """
            id, batch_id, item_id, review_contract_version, label,
            note, reviewer, supersedes_review_id, created_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentShadowRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean hasActiveBatch() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM agent_shadow_batches
                WHERE status IN ('QUEUED', 'RUNNING')
                """, Integer.class);
        return count != null && count > 0;
    }

    public boolean hasRunningMarketWork() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT count(*) FROM market_scan_tasks
                     WHERE status IN ('QUEUED', 'RUNNING'))
                  + (SELECT count(*) FROM market_data_update_tasks
                     WHERE status IN ('QUEUED', 'RUNNING'))
                """, Integer.class);
        return count != null && count > 0;
    }

    public Optional<Boolean> reliableCalendarOpen(
            LocalDate tradeDate,
            Instant knowledgeCutoff
    ) {
        List<Boolean> values = jdbcTemplate.query("""
                SELECT is_open
                FROM trading_calendar_revisions
                WHERE exchange IN ('SSE', 'SZSE')
                  AND trade_date = ?
                  AND known_from <= ?
                  AND (known_to IS NULL OR ? < known_to)
                  AND trust_level IN ('OBSERVED', 'BACKFILLED_VERIFIED')
                ORDER BY exchange
                """, (resultSet, rowNum) -> resultSet.getBoolean("is_open"),
                tradeDate,
                OffsetDateTime.ofInstant(
                        knowledgeCutoff, java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(
                        knowledgeCutoff, java.time.ZoneOffset.UTC));
        if (values.size() != 2) {
            return Optional.empty();
        }
        return Optional.of(values.stream().anyMatch(Boolean::booleanValue));
    }

    public List<SelectionCandidate> currentPositionCandidates() {
        return jdbcTemplate.query("""
                SELECT symbol, market_value
                FROM positions
                WHERE account_id = 1
                ORDER BY market_value DESC, symbol ASC
                """, (resultSet, rowNum) -> new SelectionCandidate(
                resultSet.getString("symbol"),
                SelectionSource.CURRENT_POSITION,
                "positions:accountId=1:symbol="
                        + resultSet.getString("symbol")
                        + ":marketValue="
                        + decimal(resultSet.getBigDecimal("market_value"))
        ));
    }

    public Optional<Long> latestCompletedScanTaskId() {
        return jdbcTemplate.query("""
                SELECT id
                FROM market_scan_tasks
                WHERE status = 'COMPLETED'
                ORDER BY id DESC
                LIMIT 1
                """, (resultSet, rowNum) -> resultSet.getLong("id"))
                .stream().findFirst();
    }

    public List<SelectionCandidate> eligibleScanCandidates(long scanTaskId) {
        return jdbcTemplate.query("""
                SELECT symbol, rank_no
                FROM market_scan_results
                WHERE task_id = ? AND eligible = TRUE
                ORDER BY rank_no ASC, symbol ASC
                """, (resultSet, rowNum) -> new SelectionCandidate(
                resultSet.getString("symbol"),
                SelectionSource.LATEST_SCAN_CANDIDATE,
                "market_scan_tasks:" + scanTaskId
                        + ":rank:" + resultSet.getInt("rank_no")
        ), scanTaskId);
    }

    public ShadowBatch insertBatch(
            BatchStatus status,
            TriggerMode triggerMode,
            LocalDate tradeDate,
            SelectionMode selectionMode,
            String selectionHash,
            int configuredMaxSymbols,
            int selectedCount,
            JsonNode configuration,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            String createdBy
    ) {
        String sql = """
                INSERT INTO agent_shadow_batches (
                    contract_version, status, trigger_mode, trade_date,
                    rule_version, selection_mode, selection_hash,
                    configured_max_symbols, selected_count,
                    configuration_json, error_message, started_at,
                    finished_at, created_by, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                RETURNING
                """ + BATCH_COLUMNS;
        return jdbcTemplate.queryForObject(
                sql,
                this::mapBatch,
                AgentShadowContracts.RUN_CONTROL_VERSION,
                status.name(),
                triggerMode.name(),
                tradeDate,
                AgentShadowContracts.RULE_VERSION,
                selectionMode.name(),
                selectionHash,
                configuredMaxSymbols,
                selectedCount,
                writeJson(configuration),
                errorMessage,
                timestamp(startedAt),
                timestamp(finishedAt),
                createdBy
        );
    }

    public void insertItems(long batchId, List<SelectionEntry> entries) {
        for (SelectionEntry entry : entries) {
            jdbcTemplate.update("""
                    INSERT INTO agent_shadow_items (
                        batch_id, selection_order, symbol,
                        selection_source, selection_source_ref,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    batchId,
                    entry.selectionOrder(),
                    entry.symbol(),
                    entry.selectionSource().name(),
                    entry.selectionSourceRef());
        }
    }

    public Optional<ShadowBatch> findBatch(long batchId) {
        return jdbcTemplate.query(
                "SELECT " + BATCH_COLUMNS
                        + " FROM agent_shadow_batches WHERE id = ?",
                this::mapBatch,
                batchId).stream().findFirst();
    }

    public List<ShadowBatch> findBatches(int limit) {
        return jdbcTemplate.query(
                "SELECT " + BATCH_COLUMNS
                        + " FROM agent_shadow_batches"
                        + " ORDER BY id DESC LIMIT ?",
                this::mapBatch,
                limit);
    }

    public Optional<ShadowItem> findItem(long itemId) {
        return jdbcTemplate.query(
                "SELECT " + ITEM_COLUMNS
                        + " FROM agent_shadow_items WHERE id = ?",
                this::mapItem,
                itemId).stream().findFirst();
    }

    public List<ShadowItem> findItems(long batchId) {
        return jdbcTemplate.query(
                "SELECT " + ITEM_COLUMNS
                        + " FROM agent_shadow_items"
                        + " WHERE batch_id = ? ORDER BY selection_order",
                this::mapItem,
                batchId);
    }

    public List<ShadowItem> findUnstartedItems(long batchId) {
        return jdbcTemplate.query(
                "SELECT " + ITEM_COLUMNS
                        + " FROM agent_shadow_items"
                        + " WHERE batch_id = ? AND agent_task_id IS NULL"
                        + " AND outcome_class IS NULL"
                        + " ORDER BY selection_order",
                this::mapItem,
                batchId);
    }

    public void markBatchRunning(long batchId, Instant startedAt) {
        requireOne(jdbcTemplate.update("""
                UPDATE agent_shadow_batches
                SET status = 'RUNNING', started_at = ?, error_message = NULL
                WHERE id = ? AND status = 'QUEUED'
                """, timestamp(startedAt), batchId),
                "shadow batch could not enter RUNNING: " + batchId);
    }

    public void requestCancellation(long batchId) {
        requireOne(jdbcTemplate.update("""
                UPDATE agent_shadow_batches
                SET cancellation_requested = TRUE
                WHERE id = ? AND status IN ('QUEUED', 'RUNNING')
                """, batchId),
                "shadow batch is not cancellable: " + batchId);
    }

    public void attachTask(
            long itemId,
            long taskId,
            boolean newlyCreated,
            Instant startedAt
    ) {
        requireOne(jdbcTemplate.update("""
                UPDATE agent_shadow_items
                SET agent_task_id = ?, task_newly_created = ?,
                    cache_hit = ?, task_status = 'QUEUED',
                    started_at = ?
                WHERE id = ? AND agent_task_id IS NULL
                  AND outcome_class IS NULL
                """,
                taskId,
                newlyCreated,
                !newlyCreated,
                timestamp(startedAt),
                itemId),
                "shadow item task could not be attached: " + itemId);
    }

    public void finishItem(
            long itemId,
            TerminalOutcome outcome,
            DriftResult drift,
            Instant finishedAt
    ) {
        String primaryReason = outcome.reasonCodes().isEmpty()
                ? null : outcome.reasonCodes().get(0);
        requireOne(jdbcTemplate.update("""
                UPDATE agent_shadow_items
                SET task_status = ?, final_decision = ?, gate_status = ?,
                    score = ?, confidence = ?, vetoed = ?,
                    outcome_class = ?, primary_reason_code = ?,
                    reason_codes_json = ?::jsonb,
                    run_snapshot_json = ?::jsonb,
                    context_hash = ?, duration_ms = ?,
                    previous_item_id = ?, context_changed = ?,
                    decision_changed = ?, score_delta = ?,
                    confidence_delta = ?, changed_agents_json = ?::jsonb,
                    error_message = ?, finished_at = ?
                WHERE id = ? AND outcome_class IS NULL
                """,
                outcome.taskStatus().name(),
                enumName(outcome.finalDecision()),
                enumName(outcome.gateStatus()),
                outcome.score(),
                outcome.confidence(),
                outcome.outcomeClass() == OutcomeClass.FAILED
                        ? null : outcome.vetoed(),
                outcome.outcomeClass().name(),
                primaryReason,
                writeJson(objectMapper.valueToTree(outcome.reasonCodes())),
                writeNullableJson(outcome.runSnapshot()),
                outcome.contextHash(),
                outcome.durationMs(),
                drift.previousItemId(),
                drift.contextChanged(),
                drift.decisionChanged(),
                drift.scoreDelta(),
                drift.confidenceDelta(),
                writeNullableJson(drift.changedAgents()),
                outcome.errorMessage(),
                timestamp(finishedAt),
                itemId),
                "shadow item could not enter terminal state: " + itemId);
    }

    public int cancelUnstartedItems(long batchId, Instant finishedAt) {
        return jdbcTemplate.update("""
                UPDATE agent_shadow_items
                SET task_status = 'CANCELLED',
                    outcome_class = 'CANCELLED',
                    reason_codes_json = '[]'::jsonb,
                    duration_ms = 0,
                    error_message = 'SHADOW_BATCH_CANCELLED_BEFORE_LAUNCH',
                    finished_at = ?
                WHERE batch_id = ? AND agent_task_id IS NULL
                  AND outcome_class IS NULL
                """, timestamp(finishedAt), batchId);
    }

    public Optional<ShadowItem> findPreviousComparable(
            long currentItemId,
            String symbol,
            String ruleVersion
    ) {
        return jdbcTemplate.query(
                "SELECT " + prefixedItemColumns("i")
                        + " FROM agent_shadow_items i"
                        + " JOIN agent_shadow_batches b ON b.id = i.batch_id"
                        + " WHERE i.id <> ? AND i.symbol = ?"
                        + " AND b.rule_version = ?"
                        + " AND i.outcome_class IS NOT NULL"
                        + " AND i.outcome_class <> 'CANCELLED'"
                        + " ORDER BY i.finished_at DESC, i.id DESC LIMIT 1",
                this::mapItem,
                currentItemId,
                symbol,
                ruleVersion).stream().findFirst();
    }

    public BatchCounts counts(long batchId) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    count(*) FILTER (
                        WHERE agent_task_id IS NOT NULL
                    )::int AS launched_count,
                    count(*) FILTER (
                        WHERE outcome_class IS NOT NULL
                    )::int AS terminal_count,
                    count(*) FILTER (
                        WHERE outcome_class = 'DETERMINED'
                    )::int AS determined_count,
                    count(*) FILTER (
                        WHERE outcome_class = 'INSUFFICIENT'
                    )::int AS insufficient_count,
                    count(*) FILTER (
                        WHERE outcome_class = 'FAILED'
                    )::int AS failed_count,
                    count(*) FILTER (
                        WHERE vetoed = TRUE
                    )::int AS veto_count,
                    count(*) FILTER (
                        WHERE final_decision = 'BLOCKED_BY_DATA_QUALITY'
                    )::int AS data_quality_blocked_count,
                    count(*) FILTER (
                        WHERE cache_hit = TRUE
                    )::int AS cache_hit_count,
                    count(*) FILTER (
                        WHERE outcome_class = 'CANCELLED'
                    )::int AS cancelled_count
                FROM agent_shadow_items
                WHERE batch_id = ?
                """, (resultSet, rowNum) -> new BatchCounts(
                resultSet.getInt("launched_count"),
                resultSet.getInt("terminal_count"),
                resultSet.getInt("determined_count"),
                resultSet.getInt("insufficient_count"),
                resultSet.getInt("failed_count"),
                resultSet.getInt("veto_count"),
                resultSet.getInt("data_quality_blocked_count"),
                resultSet.getInt("cache_hit_count"),
                resultSet.getInt("cancelled_count")
        ), batchId);
    }

    public void finishBatch(
            long batchId,
            BatchStatus status,
            BatchCounts counts,
            String errorMessage,
            Instant finishedAt
    ) {
        requireOne(jdbcTemplate.update("""
                UPDATE agent_shadow_batches
                SET status = ?, launched_count = ?, terminal_count = ?,
                    determined_count = ?, insufficient_count = ?,
                    failed_count = ?, veto_count = ?,
                    data_quality_blocked_count = ?, cache_hit_count = ?,
                    error_message = ?, finished_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """,
                status.name(),
                counts.launchedCount(),
                counts.terminalCount(),
                counts.determinedCount(),
                counts.insufficientCount(),
                counts.failedCount(),
                counts.vetoCount(),
                counts.dataQualityBlockedCount(),
                counts.cacheHitCount(),
                errorMessage,
                timestamp(finishedAt),
                batchId),
                "shadow batch could not enter terminal state: " + batchId);
    }

    public void failQueuedBatch(
            long batchId,
            String errorMessage,
            Instant failedAt
    ) {
        requireOne(jdbcTemplate.update("""
                UPDATE agent_shadow_batches
                SET status = 'FAILED', started_at = ?,
                    finished_at = ?, error_message = ?
                WHERE id = ? AND status = 'QUEUED'
                """,
                timestamp(failedAt),
                timestamp(failedAt),
                errorMessage,
                batchId),
                "queued shadow batch could not fail: " + batchId);
    }

    public ShadowReview insertReview(
            long batchId,
            long itemId,
            ReviewLabel label,
            String note,
            String reviewer,
            Long supersedesReviewId
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO agent_shadow_reviews (
                    batch_id, item_id, review_contract_version, label,
                    note, reviewer, supersedes_review_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                RETURNING
                """ + REVIEW_COLUMNS,
                this::mapReview,
                batchId,
                itemId,
                AgentShadowContracts.REVIEW_VERSION,
                label.name(),
                note,
                reviewer,
                supersedesReviewId);
    }

    public List<ShadowReview> findReviews(long itemId) {
        return jdbcTemplate.query(
                "SELECT " + REVIEW_COLUMNS
                        + " FROM agent_shadow_reviews"
                        + " WHERE item_id = ? ORDER BY created_at, id",
                this::mapReview,
                itemId);
    }

    public List<ShadowItem> findMetricItems(MetricsFilter filter) {
        QueryParts query = metricWhere(filter);
        return jdbcTemplate.query(
                "SELECT " + prefixedItemColumns("i")
                        + " FROM agent_shadow_items i"
                        + " JOIN agent_shadow_batches b ON b.id = i.batch_id"
                        + query.where()
                        + " ORDER BY i.finished_at, i.id",
                this::mapItem,
                query.args().toArray());
    }

    public List<ShadowBatch> findMetricBatches(MetricsFilter filter) {
        QueryParts query = metricWhere(filter);
        return jdbcTemplate.query(
                "SELECT DISTINCT " + prefixedBatchColumns("b")
                        + " FROM agent_shadow_batches b"
                        + " LEFT JOIN agent_shadow_items i ON i.batch_id = b.id"
                        + query.where()
                        + " ORDER BY b.id",
                this::mapBatch,
                query.args().toArray());
    }

    public List<ShadowReview> findMetricReviews(MetricsFilter filter) {
        QueryParts query = metricWhere(filter);
        return jdbcTemplate.query(
                "SELECT " + prefixedReviewColumns("r")
                        + " FROM agent_shadow_reviews r"
                        + " JOIN agent_shadow_items i ON i.id = r.item_id"
                        + " JOIN agent_shadow_batches b ON b.id = i.batch_id"
                        + query.where()
                        + " ORDER BY r.id",
                this::mapReview,
                query.args().toArray());
    }

    private QueryParts metricWhere(MetricsFilter filter) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (filter.fromDate() != null) {
            clauses.add("b.trade_date >= ?");
            args.add(filter.fromDate());
        }
        if (filter.toDate() != null) {
            clauses.add("b.trade_date <= ?");
            args.add(filter.toDate());
        }
        if (filter.ruleVersion() != null
                && !filter.ruleVersion().isBlank()) {
            clauses.add("b.rule_version = ?");
            args.add(filter.ruleVersion());
        }
        if (filter.batchId() != null) {
            clauses.add("b.id = ?");
            args.add(filter.batchId());
        }
        if (filter.symbol() != null && !filter.symbol().isBlank()) {
            clauses.add("i.symbol = ?");
            args.add(filter.symbol());
        }
        String where = clauses.isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", clauses);
        return new QueryParts(where, List.copyOf(args));
    }

    private ShadowBatch mapBatch(ResultSet resultSet, int rowNum)
            throws SQLException {
        return new ShadowBatch(
                resultSet.getLong("id"),
                resultSet.getString("contract_version"),
                BatchStatus.valueOf(resultSet.getString("status")),
                TriggerMode.valueOf(resultSet.getString("trigger_mode")),
                resultSet.getObject("trade_date", LocalDate.class),
                resultSet.getString("rule_version"),
                SelectionMode.valueOf(resultSet.getString("selection_mode")),
                resultSet.getString("selection_hash"),
                resultSet.getInt("configured_max_symbols"),
                resultSet.getInt("selected_count"),
                resultSet.getInt("launched_count"),
                resultSet.getInt("terminal_count"),
                resultSet.getInt("determined_count"),
                resultSet.getInt("insufficient_count"),
                resultSet.getInt("failed_count"),
                resultSet.getInt("veto_count"),
                resultSet.getInt("data_quality_blocked_count"),
                resultSet.getInt("cache_hit_count"),
                resultSet.getBoolean("cancellation_requested"),
                readJson(resultSet.getString("configuration_json")),
                resultSet.getString("error_message"),
                instant(resultSet.getObject("started_at")),
                instant(resultSet.getObject("finished_at")),
                resultSet.getString("created_by"),
                instant(resultSet.getObject("created_at")),
                instant(resultSet.getObject("updated_at"))
        );
    }

    private ShadowItem mapItem(ResultSet resultSet, int rowNum)
            throws SQLException {
        return new ShadowItem(
                resultSet.getLong("id"),
                resultSet.getLong("batch_id"),
                resultSet.getInt("selection_order"),
                resultSet.getString("symbol"),
                SelectionSource.valueOf(
                        resultSet.getString("selection_source")),
                resultSet.getString("selection_source_ref"),
                resultSet.getObject("agent_task_id", Long.class),
                resultSet.getBoolean("task_newly_created"),
                resultSet.getBoolean("cache_hit"),
                enumValue(TaskStatus.class,
                        resultSet.getString("task_status")),
                enumValue(FinalDecisionCode.class,
                        resultSet.getString("final_decision")),
                enumValue(GateStatus.class,
                        resultSet.getString("gate_status")),
                resultSet.getObject("score", Integer.class),
                resultSet.getObject("confidence", Integer.class),
                resultSet.getObject("vetoed", Boolean.class),
                enumValue(OutcomeClass.class,
                        resultSet.getString("outcome_class")),
                resultSet.getString("primary_reason_code"),
                readNullableJson(
                        resultSet.getString("reason_codes_json")),
                readNullableJson(
                        resultSet.getString("run_snapshot_json")),
                resultSet.getString("context_hash"),
                resultSet.getObject("duration_ms", Long.class),
                resultSet.getObject("previous_item_id", Long.class),
                resultSet.getObject("context_changed", Boolean.class),
                resultSet.getObject("decision_changed", Boolean.class),
                resultSet.getObject("score_delta", Integer.class),
                resultSet.getObject("confidence_delta", Integer.class),
                readNullableJson(
                        resultSet.getString("changed_agents_json")),
                resultSet.getString("error_message"),
                instant(resultSet.getObject("started_at")),
                instant(resultSet.getObject("finished_at")),
                instant(resultSet.getObject("created_at")),
                instant(resultSet.getObject("updated_at"))
        );
    }

    private ShadowReview mapReview(ResultSet resultSet, int rowNum)
            throws SQLException {
        return new ShadowReview(
                resultSet.getLong("id"),
                resultSet.getLong("batch_id"),
                resultSet.getLong("item_id"),
                resultSet.getString("review_contract_version"),
                ReviewLabel.valueOf(resultSet.getString("label")),
                resultSet.getString("note"),
                resultSet.getString("reviewer"),
                resultSet.getObject("supersedes_review_id", Long.class),
                instant(resultSet.getObject("created_at"))
        );
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "shadow JSON could not be read", error);
        }
    }

    private JsonNode readNullableJson(String value) {
        return value == null ? null : readJson(value);
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "shadow JSON could not be written", error);
        }
    }

    private String writeNullableJson(JsonNode value) {
        return value == null ? null : writeJson(value);
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value
    ) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Object timestamp(Instant value) {
        return value == null
                ? null
                : OffsetDateTime.ofInstant(
                        value, java.time.ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new IllegalStateException(
                "unsupported shadow timestamp: " + value.getClass());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "null" : value.toPlainString();
    }

    private static void requireOne(int updated, String message) {
        if (updated != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static String prefixedBatchColumns(String alias) {
        return prefixColumns(BATCH_COLUMNS, alias);
    }

    private static String prefixedItemColumns(String alias) {
        return prefixColumns(ITEM_COLUMNS, alias);
    }

    private static String prefixedReviewColumns(String alias) {
        return prefixColumns(REVIEW_COLUMNS, alias);
    }

    private static String prefixColumns(String columns, String alias) {
        return java.util.Arrays.stream(
                        columns.replace('\n', ' ').split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(raw -> {
                    int asIndex = raw.toUpperCase().indexOf(" AS ");
                    if (asIndex >= 0) {
                        String expression = raw.substring(0, asIndex);
                        String named = raw.substring(asIndex);
                        return alias + "." + expression + named;
                    }
                    return alias + "." + raw;
                })
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public record SelectionCandidate(
            String symbol,
            SelectionSource source,
            String sourceRef
    ) {
    }

    public record BatchCounts(
            int launchedCount,
            int terminalCount,
            int determinedCount,
            int insufficientCount,
            int failedCount,
            int vetoCount,
            int dataQualityBlockedCount,
            int cacheHitCount,
            int cancelledCount
    ) {
    }

    private record QueryParts(String where, List<Object> args) {
    }
}
