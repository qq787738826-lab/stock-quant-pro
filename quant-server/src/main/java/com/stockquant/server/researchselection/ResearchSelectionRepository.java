package com.stockquant.server.researchselection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.researchselection.ResearchSelectionModels.RunSummary;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionRequest;
import com.stockquant.server.researchselection.ResearchSelectionModels.SelectionResult;
import com.stockquant.server.researchselection.ResearchSelectionModels.Status;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL append-only state and result projection for V1.0.1. */
public final class ResearchSelectionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final BacktestCanonicalHashService canonical;
    private final ResearchUniverseMainboardRepository universes;
    private final TransactionTemplate transactions;

    public ResearchSelectionRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonical = new BacktestCanonicalHashService(mapper);
        this.universes = new ResearchUniverseMainboardRepository(jdbc);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(
                        jdbc.getDataSource(), "research selection dataSource")));
    }

    public RunSummary create(
            String publicRunId,
            SelectionRequest request,
            Instant asOf,
            String gitCommit
    ) {
        String fingerprint = hash(new Identity(publicRunId, request, asOf,
                gitCommit));
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO research_selection_runs (
                        public_run_id, contract_version, status, trigger_mode,
                        requested_at, research_as_of, primary_window,
                        auxiliary_window,
                        shortlist_limit, final_limit, paper_enabled,
                        universe_version, ranking_version, git_commit,
                        request_fingerprint
                    ) VALUES (?, 'RESEARCH_SELECTION_V1', 'QUEUED', ?, ?, ?,
                              ?, ?, ?, ?, ?, 'RESEARCH_UNIVERSE_MAINBOARD_V1',
                              'RESEARCH_SELECTION_RANKING_V1', ?, ?)
                    RETURNING id
                    """, Long.class, publicRunId, request.triggerMode().name(),
                    Timestamp.from(asOf), Timestamp.from(asOf),
                    request.primaryWindow(),
                    request.auxiliaryWindow(), request.shortlistSize(),
                    request.finalLimit(), request.paperEnabled(), gitCommit,
                    fingerprint);
            return summary(Objects.requireNonNull(id)).orElseThrow();
        } catch (DuplicateKeyException error) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_ALREADY_RUNNING", error);
        }
    }

    public RunSummary transition(long id, Status expected, Status next) {
        if (expected.terminal() || next == Status.QUEUED) {
            throw new IllegalArgumentException(
                    "RESEARCH_SELECTION_TRANSITION_INVALID");
        }
        int updated = jdbc.update("""
                UPDATE research_selection_runs
                   SET status=?, started_at=COALESCE(started_at, clock_timestamp())
                 WHERE id=? AND status=?
                """, next.name(), id, expected.name());
        if (updated != 1) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_STATE_CONFLICT");
        }
        return summary(id).orElseThrow();
    }

    /** Binds the secret-free Broker request once so pre-run rejection can be reconciled. */
    public void bindBrokerRequest(long id, String requestId) {
        if (requestId == null || !requestId.matches(
                "SQHB_[0-9]{8}T[0-9]{6}Z_[A-F0-9]{12}")) {
            throw new IllegalArgumentException(
                    "RESEARCH_SELECTION_BROKER_REQUEST_INVALID");
        }
        int updated = jdbc.update("""
                UPDATE research_selection_runs
                   SET broker_request_id=?
                 WHERE id=? AND broker_request_id IS NULL
                   AND status NOT IN ('COMPLETED','FAILED')
                """, requestId, id);
        if (updated == 0) {
            Status status = config(id).status();
            if (!status.terminal()) {
                throw new IllegalStateException(
                        "RESEARCH_SELECTION_BROKER_BINDING_CONFLICT");
            }
        }
    }

    public List<BrokerBoundRun> queuedBrokerRuns(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return jdbc.query("""
                SELECT id, broker_request_id
                  FROM research_selection_runs
                 WHERE status='QUEUED' AND broker_request_id IS NOT NULL
                 ORDER BY id LIMIT ?
                """, (row, ignored) -> new BrokerBoundRun(
                row.getLong("id"), row.getString("broker_request_id")),
                bounded);
    }

    public void complete(long id, SelectionResult result) {
        writeTerminal(id, Status.CRITIC_REVIEW, Status.COMPLETED, result,
                result.anchorTradeDate(), result.shadowRunId(), null, null);
    }

    public void complete(
            long id,
            SelectionResult result,
            long snapshotDatabaseId,
            List<ResearchUniverseMainboard.MemberEvaluation> evaluations
    ) {
        Objects.requireNonNull(evaluations, "evaluations");
        Objects.requireNonNull(transactions.execute(status -> {
            universes.insertRunMembers(id, snapshotDatabaseId, evaluations);
            writeTerminal(id, Status.CRITIC_REVIEW, Status.COMPLETED, result,
                    result.anchorTradeDate(), result.shadowRunId(), null, null);
            return Boolean.TRUE;
        }), "research selection completion transaction");
    }

    public void fail(
            long id,
            Status current,
            SelectionResult result,
            String category,
            String reason
    ) {
        writeTerminal(id, current, Status.FAILED, result,
                result.anchorTradeDate(), null, category, reason);
    }

    /** Terminalizes a run when dispatch or execution fails before a result. */
    public void fail(
            long id,
            Status current,
            String category,
            String reason,
            Instant completedAt
    ) {
        RunConfig config = config(id);
        SelectionResult result = new SelectionResult(
                ResearchSelectionModels.VERSION, id, config.publicRunId(),
                Status.FAILED, config.triggerMode(), config.researchAsOf(),
                null, null, null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), true,
                "FAILED", null, null, config.paperEnabled(), false, false,
                new ResearchSelectionModels.Timings(0, 0, 0, 0, 0),
                new ResearchSelectionModels.Usage(0, 0, 0, 0, 0, 0, 0,
                        0, java.math.BigDecimal.ZERO), null, category, reason,
                config.researchAsOf(), completedAt);
        fail(id, current, result, category, reason);
    }

    private void writeTerminal(
            long id,
            Status expected,
            Status terminal,
            SelectionResult result,
            LocalDate anchor,
            Long shadowRunId,
            String category,
            String reason
    ) {
        try {
            var tree = mapper.valueToTree(result);
            String json = mapper.writeValueAsString(tree);
            String fingerprint = canonical.hash(tree);
            int updated = jdbc.update("""
                    UPDATE research_selection_runs
                       SET status=?, anchor_trade_date=?, result_json=?::jsonb,
                           result_fingerprint=?, shadow_run_id=?,
                           failure_category=?, failure_reason=?,
                           completed_at=clock_timestamp()
                     WHERE id=? AND status=?
                    """, terminal.name(), anchor, json, fingerprint,
                    shadowRunId, category, reason, id, expected.name());
            if (updated != 1) {
                throw new IllegalStateException(
                        "RESEARCH_SELECTION_STATE_CONFLICT");
            }
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_RESULT_SERIALIZATION_FAILED", error);
        }
    }

    public Optional<RunSummary> summary(long id) {
        return jdbc.query("""
                SELECT id, public_run_id, status, trigger_mode,
                       research_as_of, anchor_trade_date, primary_window,
                       shortlist_limit, result_json, failure_category,
                       failure_reason, created_at, completed_at
                  FROM research_selection_runs WHERE id=?
                """, this::mapSummary, id).stream().findFirst();
    }

    public Optional<SelectionResult> result(long id) {
        return jdbc.query("""
                SELECT result_json::text FROM research_selection_runs
                 WHERE id=? AND result_json IS NOT NULL
                """, (row, ignored) -> parse(row.getString(1)), id).stream()
                .findFirst();
    }

    public Optional<SelectionResult> latestResult() {
        return jdbc.query("""
                SELECT result_json::text FROM research_selection_runs
                 WHERE result_json IS NOT NULL
                 ORDER BY id DESC LIMIT 1
                """, (row, ignored) -> parse(row.getString(1))).stream()
                .findFirst();
    }

    public RunConfig config(long id) {
        return jdbc.query("""
                SELECT public_run_id, trigger_mode, research_as_of,
                       primary_window, auxiliary_window, shortlist_limit,
                       final_limit, paper_enabled, git_commit, status,
                       universe_snapshot_db_id, universe_version
                  FROM research_selection_runs WHERE id=?
                """, (row, ignored) -> new RunConfig(id,
                row.getString("public_run_id"),
                ResearchSelectionModels.TriggerMode.valueOf(
                        row.getString("trigger_mode")),
                row.getTimestamp("research_as_of").toInstant(),
                row.getInt("primary_window"),
                row.getInt("auxiliary_window"),
                row.getInt("shortlist_limit"), row.getInt("final_limit"),
                row.getBoolean("paper_enabled"),
                row.getString("git_commit"),
                Status.valueOf(row.getString("status")),
                (Long) row.getObject("universe_snapshot_db_id"),
                row.getString("universe_version")), id).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "RESEARCH_SELECTION_RUN_MISSING"));
    }

    /**
     * Binds the actual current-as-of cutoff after provider preparation. This
     * field is intentionally mutable only while QUEUED and only forward in
     * time; later historical dates still retain their original knownAt.
     */
    public RunConfig advanceResearchAsOf(
            long id,
            Instant expected,
            Instant advanced
    ) {
        if (advanced == null || expected == null || advanced.isBefore(expected)) {
            throw new IllegalArgumentException(
                    "RESEARCH_SELECTION_AS_OF_INVALID");
        }
        int updated = jdbc.update("""
                UPDATE research_selection_runs
                   SET research_as_of=?
                 WHERE id=? AND status='QUEUED' AND research_as_of=?
                """, Timestamp.from(advanced), id, Timestamp.from(expected));
        if (updated != 1) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_AS_OF_CONFLICT");
        }
        return config(id);
    }

    public List<RunSummary> history(int limit) {
        int bounded = Math.max(1, Math.min(100, limit));
        return jdbc.query("""
                SELECT id, public_run_id, status, trigger_mode,
                       research_as_of, anchor_trade_date, primary_window,
                       shortlist_limit, result_json, failure_category,
                       failure_reason, created_at, completed_at
                  FROM research_selection_runs
                 ORDER BY id DESC LIMIT ?
                """, this::mapSummary, bounded);
    }

    public int monthlyTushareUsage(YearMonth month) {
        Objects.requireNonNull(month, "month");
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(sum(request_count), 0)
                  FROM external_api_monthly_usage_ledger
                 WHERE calendar_month=? AND provider='TUSHARE'
                """, Integer.class, month.toString());
        return value == null ? 0 : value;
    }

    /** Read-only count of genuine scheduled frozen samples per security. */
    public Map<String, Integer> liveShadowSampleCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ranked.security_code, count(DISTINCT run.id) AS samples
                  FROM shadow_research_runs run
                  JOIN shadow_research_snapshots snapshot
                    ON snapshot.run_id=run.id
                 CROSS JOIN LATERAL jsonb_array_elements_text(
                    COALESCE(snapshot.recommendation_json
                        ->'rankedSecurities', '[]'::jsonb)
                 ) AS ranked(security_code)
                 WHERE run.status='FROZEN'
                   AND run.trigger_mode='SCHEDULED'
                 GROUP BY ranked.security_code
                 ORDER BY ranked.security_code
                """, (row, ignored) -> Map.entry(
                row.getString("security_code"), row.getInt("samples")))
                .forEach(entry -> result.put(entry.getKey(),
                        entry.getValue()));
        return Map.copyOf(result);
    }

    private RunSummary mapSummary(ResultSet row, int ignored)
            throws SQLException {
        String json = row.getString("result_json");
        int candidateCount = 0;
        int universeSize = 0;
        int shortlistSize = 0;
        String decisionCode = "PENDING";
        if (json != null) {
            SelectionResult result = parse(json);
            candidateCount = result.candidates().size();
            universeSize = result.lineage() == null ? universeSize
                    : result.lineage().universeMemberCount() > 0
                    ? result.lineage().universeMemberCount()
                    : result.lineage().universeSecurities().size();
            shortlistSize = result.shortlist().size();
            decisionCode = result.decisionCode();
        }
        Timestamp completed = row.getTimestamp("completed_at");
        LocalDate anchor = row.getObject("anchor_trade_date",
                LocalDate.class);
        return new RunSummary(row.getLong("id"),
                row.getString("public_run_id"),
                Status.valueOf(row.getString("status")),
                ResearchSelectionModels.TriggerMode.valueOf(
                        row.getString("trigger_mode")),
                row.getTimestamp("research_as_of").toInstant(), anchor,
                universeSize, shortlistSize, candidateCount, decisionCode,
                row.getString("failure_category"),
                row.getString("failure_reason"),
                row.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private SelectionResult parse(String value) {
        try {
            return mapper.readValue(value, SelectionResult.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "RESEARCH_SELECTION_STORED_RESULT_INVALID", error);
        }
    }

    private String hash(Object value) {
        return canonical.hash(mapper.valueToTree(value));
    }

    private record Identity(
            String publicRunId,
            SelectionRequest request,
            Instant researchAsOf,
            String gitCommit
    ) {
    }

    public record RunConfig(
            long runId,
            String publicRunId,
            ResearchSelectionModels.TriggerMode triggerMode,
            Instant researchAsOf,
            int primaryWindow,
            int auxiliaryWindow,
            int shortlistSize,
            int finalLimit,
            boolean paperEnabled,
            String gitCommit,
            Status status,
            Long universeSnapshotDatabaseId,
            String universeVersion
    ) {
        public SelectionRequest request() {
            return new SelectionRequest(triggerMode, primaryWindow,
                    auxiliaryWindow, shortlistSize, finalLimit,
                    paperEnabled);
        }
    }

    public record BrokerBoundRun(long runId, String brokerRequestId) {
    }
}
