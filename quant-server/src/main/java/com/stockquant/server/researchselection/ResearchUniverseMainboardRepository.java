package com.stockquant.server.researchselection;

import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.MemberEvaluation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.MemberPage;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Snapshot;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL persistence for immutable V1.0.9 universe snapshots. */
public final class ResearchUniverseMainboardRepository {
    private static final DateTimeFormatter SNAPSHOT_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSSSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String SNAPSHOT_SELECT = """
            SELECT snapshot.*,
                   COALESCE((
                       SELECT max(observation.observed_at)
                         FROM research_universe_snapshot_observations
                                observation
                        WHERE observation.snapshot_db_id=snapshot.id
                   ), snapshot.observed_at) AS last_verified_at
              FROM research_universe_snapshots snapshot
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ResearchUniverseMainboardRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException(
                    "MAINBOARD_UNIVERSE_DATASOURCE_REQUIRED");
        }
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public Optional<SnapshotBundle> latest() {
        return jdbc.query(SNAPSHOT_SELECT + """
                 WHERE snapshot.universe_version=
                       'RESEARCH_UNIVERSE_MAINBOARD_V1'
                 ORDER BY last_verified_at DESC, snapshot.id DESC LIMIT 1
                """, SNAPSHOT_MAPPER).stream().findFirst().map(snapshot ->
                new SnapshotBundle(snapshot, members(snapshot.databaseId())));
    }

    public Optional<SnapshotBundle> find(long databaseId) {
        return jdbc.query(SNAPSHOT_SELECT + " WHERE snapshot.id=?",
                SNAPSHOT_MAPPER, databaseId).stream().findFirst()
                .map(snapshot -> new SnapshotBundle(snapshot,
                        members(snapshot.databaseId())));
    }

    public SnapshotBundle saveIfChanged(
            List<Member> input,
            Instant observedAt,
            LocalDate effectiveDate,
            String sourceFingerprint,
            String gitCommit
    ) {
        List<Member> members = input.stream().sorted(Comparator.comparing(
                Member::tsCode)).toList();
        validateMembers(members, observedAt, sourceFingerprint, gitCommit);
        String memberFingerprint = sha256(members.stream().map(value ->
                value.tsCode() + '|' + value.contentHash() + '\n')
                .reduce("", String::concat));
        return Objects.requireNonNull(transactions.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(hashtext(" +
                    "'RESEARCH_UNIVERSE_MAINBOARD_V1'))");
            Optional<Snapshot> latest = jdbc.query(SNAPSHOT_SELECT + """
                     WHERE snapshot.universe_version=
                           'RESEARCH_UNIVERSE_MAINBOARD_V1'
                     ORDER BY last_verified_at DESC, snapshot.id DESC
                     LIMIT 1
                    """, SNAPSHOT_MAPPER).stream().findFirst();
            if (latest.isPresent() && latest.get().memberFingerprint().equals(
                    memberFingerprint)) {
                recordObservation(latest.get().databaseId(), observedAt,
                        sourceFingerprint, gitCommit);
                return find(latest.get().databaseId()).orElseThrow();
            }
            int sse = Math.toIntExact(members.stream().filter(value ->
                    "SSE".equals(value.exchange())).count());
            int szse = members.size() - sse;
            int st = Math.toIntExact(members.stream().filter(
                    Member::stSecurity).count());
            String snapshotId = "MAINBOARD_"
                    + SNAPSHOT_TIME.format(observedAt) + '_'
                    + memberFingerprint.substring(0, 12);
            Long id = jdbc.queryForObject("""
                    INSERT INTO research_universe_snapshots(
                        snapshot_id, universe_version, member_count,
                        sse_count, szse_count, st_count, observed_at,
                        effective_date, source, source_fingerprint,
                        member_fingerprint, git_commit
                    ) VALUES (?, 'RESEARCH_UNIVERSE_MAINBOARD_V1', ?, ?, ?,
                              ?, ?, ?, 'TUSHARE_STOCK_BASIC', ?, ?, ?)
                    RETURNING id
                    """, Long.class, snapshotId, members.size(), sse, szse,
                    st, Timestamp.from(observedAt), effectiveDate,
                    sourceFingerprint, memberFingerprint, gitCommit);
            long databaseId = Objects.requireNonNull(id);
            jdbc.batchUpdate("""
                    INSERT INTO research_universe_members(
                        snapshot_db_id, ts_code, symbol, exchange, name,
                        industry, market, list_status, list_date, delist_date,
                        snapshot_observed_at, source, content_hash, st_security
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, members, 500, (statement, value) -> {
                statement.setLong(1, databaseId);
                statement.setString(2, value.tsCode());
                statement.setString(3, value.symbol());
                statement.setString(4, value.exchange());
                statement.setString(5, value.name());
                statement.setString(6, value.industry());
                statement.setString(7, value.market());
                statement.setString(8, value.listStatus());
                statement.setObject(9, value.listDate());
                statement.setObject(10, value.delistDate());
                statement.setTimestamp(11,
                        Timestamp.from(value.snapshotObservedAt()));
                statement.setString(12, value.source());
                statement.setString(13, value.contentHash());
                statement.setBoolean(14, value.stSecurity());
            });
            recordObservation(databaseId, observedAt, sourceFingerprint,
                    gitCommit);
            return find(databaseId).orElseThrow();
        }), "mainboard universe transaction");
    }

    public void bindRun(long runId, long snapshotDatabaseId) {
        int updated = jdbc.update("""
                UPDATE research_selection_runs
                   SET universe_snapshot_db_id=?
                 WHERE id=?
                   AND universe_version='RESEARCH_UNIVERSE_MAINBOARD_V1'
                   AND status='QUEUED'
                   AND universe_snapshot_db_id IS NULL
                """, snapshotDatabaseId, runId);
        if (updated == 0) {
            Long existing = jdbc.queryForObject("""
                    SELECT universe_snapshot_db_id
                      FROM research_selection_runs WHERE id=?
                    """, Long.class, runId);
            if (!Objects.equals(existing, snapshotDatabaseId)) {
                throw new IllegalStateException(
                        "MAINBOARD_UNIVERSE_RUN_BINDING_CONFLICT");
            }
        }
    }

    public Optional<Long> boundSnapshot(long runId) {
        return jdbc.query("""
                SELECT universe_snapshot_db_id FROM research_selection_runs
                 WHERE id=? AND universe_snapshot_db_id IS NOT NULL
                """, (row, ignored) -> row.getLong(1), runId)
                .stream().findFirst();
    }

    public void insertRunMembers(
            long runId,
            long snapshotDatabaseId,
            List<MemberEvaluation> values
    ) {
        jdbc.batchUpdate("""
                INSERT INTO research_selection_member_results(
                    run_id, snapshot_db_id, ts_code, symbol, exchange, name,
                    industry, eligibility_status, exclusion_reasons,
                    available_sessions, missing_daily, missing_adj_factor,
                    average_traded_amount, basic_rank, basic_score,
                    historical_rank, stability_score, historical_grade,
                    strategy_rank, agent_selected, final_candidate
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?)
                """, values, 500, (statement, value) -> {
            statement.setLong(1, runId);
            statement.setLong(2, snapshotDatabaseId);
            Member member = value.member();
            statement.setString(3, member.tsCode());
            statement.setString(4, member.symbol());
            statement.setString(5, member.exchange());
            statement.setString(6, member.name());
            statement.setString(7, member.industry());
            statement.setString(8, value.status().name());
            Array reasons = statement.getConnection().createArrayOf("text",
                    value.exclusionReasons().stream().map(Enum::name)
                            .toArray(String[]::new));
            statement.setArray(9, reasons);
            statement.setInt(10, value.availableSessions());
            statement.setInt(11, value.missingDaily());
            statement.setInt(12, value.missingAdjustmentFactors());
            statement.setBigDecimal(13, value.averageTradedAmount());
            setInteger(statement, 14, value.basicRank());
            statement.setBigDecimal(15, value.basicScore());
            setInteger(statement, 16, value.historicalRank());
            statement.setBigDecimal(17, value.stabilityScore());
            statement.setString(18, value.historicalGrade());
            setInteger(statement, 19, value.strategyRank());
            statement.setBoolean(20, value.agentSelected());
            statement.setBoolean(21, value.finalCandidate());
        });
    }

    public MemberPage memberPage(
            long runId,
            int page,
            int size,
            String eligibility
    ) {
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(10, Math.min(200, size));
        boolean filtered = eligibility != null && !eligibility.isBlank();
        if (filtered && !List.of("ELIGIBLE", "EXCLUDED").contains(
                eligibility)) {
            throw new IllegalArgumentException(
                    "MAINBOARD_UNIVERSE_PAGE_FILTER_INVALID");
        }
        String where = filtered
                ? " WHERE run_id=? AND eligibility_status=?"
                : " WHERE run_id=?";
        Object[] countArgs = filtered
                ? new Object[]{runId, eligibility}
                : new Object[]{runId};
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM research_selection_member_results"
                        + where, Long.class, countArgs);
        List<Object> args = new ArrayList<>();
        java.util.Collections.addAll(args, countArgs);
        args.add(boundedSize);
        args.add(boundedPage * boundedSize);
        List<MemberEvaluation> members = jdbc.query("""
                SELECT r.*, m.market, m.list_status, m.list_date,
                       m.delist_date, m.snapshot_observed_at, m.source,
                       m.content_hash, m.st_security
                  FROM research_selection_member_results r
                  JOIN research_universe_members m
                    ON m.snapshot_db_id=r.snapshot_db_id
                   AND m.ts_code=r.ts_code
                """ + where + """
                 ORDER BY r.basic_rank NULLS LAST, r.ts_code
                 LIMIT ? OFFSET ?
                """, MEMBER_EVALUATION_MAPPER, args.toArray());
        return new MemberPage(runId, boundedPage, boundedSize,
                total == null ? 0 : total, members);
    }

    public int existingMarketFactSecurityCount() {
        Integer value = jdbc.queryForObject("""
                SELECT count(DISTINCT (b.symbol, b.exchange))
                  FROM raw_daily_bar_facts_v2 b
                  JOIN pit_market_fact_observations o
                    ON o.id=b.observation_id
                 WHERE o.source_code='TUSHARE_PRO'
                """, Integer.class);
        return value == null ? 0 : value;
    }

    private List<Member> members(long databaseId) {
        return jdbc.query("""
                SELECT * FROM research_universe_members
                 WHERE snapshot_db_id=? ORDER BY exchange, symbol
                """, MEMBER_MAPPER, databaseId);
    }

    private static void validateMembers(
            List<Member> members,
            Instant observedAt,
            String sourceFingerprint,
            String gitCommit
    ) {
        if (members.size() < ResearchUniverseMainboard
                .MINIMUM_PLAUSIBLE_MEMBER_COUNT || observedAt == null
                || sourceFingerprint == null
                || !sourceFingerprint.matches("[0-9a-f]{64}")
                || gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")
                || members.stream().map(Member::tsCode).distinct().count()
                != members.size()
                || members.stream().anyMatch(value ->
                !"主板".equals(value.market())
                        || !"L".equals(value.listStatus())
                        || !ResearchUniverseMainboard.SOURCE.equals(
                        value.source())
                        || !observedAt.equals(value.snapshotObservedAt())
                        || !value.contentHash().matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "MAINBOARD_UNIVERSE_MEMBERS_INVALID");
        }
    }

    private void recordObservation(
            long snapshotDatabaseId,
            Instant observedAt,
            String sourceFingerprint,
            String gitCommit
    ) {
        jdbc.update("""
                INSERT INTO research_universe_snapshot_observations(
                    snapshot_db_id, observed_at, source_fingerprint,
                    git_commit
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (snapshot_db_id, observed_at,
                             source_fingerprint) DO NOTHING
                """, snapshotDatabaseId, Timestamp.from(observedAt),
                sourceFingerprint, gitCommit);
    }

    private static void setInteger(
            java.sql.PreparedStatement statement,
            int index,
            Integer value
    ) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.INTEGER);
        else statement.setInt(index, value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "MAINBOARD_UNIVERSE_SHA256_UNAVAILABLE", error);
        }
    }

    private static final RowMapper<Snapshot> SNAPSHOT_MAPPER =
            (row, ignored) -> new Snapshot(row.getLong("id"),
                    row.getString("snapshot_id"),
                    row.getString("universe_version"),
                    row.getInt("member_count"), row.getInt("sse_count"),
                    row.getInt("szse_count"), row.getInt("st_count"),
                    row.getTimestamp("observed_at").toInstant(),
                    row.getTimestamp("last_verified_at").toInstant(),
                    row.getObject("effective_date", LocalDate.class),
                    row.getString("source"),
                    row.getString("source_fingerprint"),
                    row.getString("member_fingerprint"),
                    row.getString("git_commit"));

    private static final RowMapper<Member> MEMBER_MAPPER =
            (row, ignored) -> mapMember(row);

    private static final RowMapper<MemberEvaluation>
            MEMBER_EVALUATION_MAPPER = (row, ignored) ->
            new MemberEvaluation(mapMember(row),
                    ResearchUniverseMainboard.EligibilityStatus.valueOf(
                            row.getString("eligibility_status")),
                    stringArray(row, "exclusion_reasons").stream().map(
                            ResearchUniverseMainboard.ExclusionReason::valueOf)
                            .toList(),
                    row.getInt("available_sessions"),
                    row.getInt("missing_daily"),
                    row.getInt("missing_adj_factor"),
                    row.getBigDecimal("average_traded_amount"),
                    integer(row, "basic_rank"),
                    row.getBigDecimal("basic_score"),
                    integer(row, "historical_rank"),
                    row.getBigDecimal("stability_score"),
                    row.getString("historical_grade"),
                    integer(row, "strategy_rank"),
                    row.getBoolean("agent_selected"),
                    row.getBoolean("final_candidate"));

    private static Member mapMember(ResultSet row) throws SQLException {
        return new Member(row.getString("ts_code"),
                row.getString("symbol"), row.getString("exchange"),
                row.getString("name"), row.getString("industry"),
                row.getString("market"), row.getString("list_status"),
                row.getObject("list_date", LocalDate.class),
                row.getObject("delist_date", LocalDate.class),
                row.getTimestamp("snapshot_observed_at").toInstant(),
                row.getString("source"), row.getString("content_hash"),
                row.getBoolean("st_security"));
    }

    private static Integer integer(ResultSet row, String name)
            throws SQLException {
        int value = row.getInt(name);
        return row.wasNull() ? null : value;
    }

    private static List<String> stringArray(ResultSet row, String name)
            throws SQLException {
        Array array = row.getArray(name);
        if (array == null) return List.of();
        return List.of((String[]) array.getArray());
    }
}
