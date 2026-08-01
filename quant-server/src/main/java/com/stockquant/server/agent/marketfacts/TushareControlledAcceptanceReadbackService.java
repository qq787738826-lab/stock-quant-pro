package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.Verification;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads the committed V13 rows back on the transaction-bound connection. */
public final class TushareControlledAcceptanceReadbackService {
    private final JdbcTemplate jdbc;
    private final TushareDedicatedResearchPersistenceGuard guard;

    public TushareControlledAcceptanceReadbackService(
            JdbcTemplate jdbc,
            TushareDedicatedResearchPersistenceGuard guard
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    DatabaseReadbackEvidence readAndVerify(
            long batchId,
            Instant expectedObservedAt,
            Instant executionStartedAt,
            Instant readbackAt,
            DatabaseExecutionIdentity writeIdentity
    ) {
        Verification readbackIdentity = guard.verifyTransactional();
        if (!writeIdentity.currentDatabase().equals(readbackIdentity.currentDatabase())
                || !writeIdentity.currentUser().equals(readbackIdentity.currentUser())
                || !writeIdentity.currentSchema().equals(readbackIdentity.currentSchema())
                || !writeIdentity.appliedMigrations().equals(readbackIdentity.appliedMigrations())
                || writeIdentity.backendPidBefore() != writeIdentity.backendPidAfter()
                || writeIdentity.backendPidAfter() != readbackIdentity.backendPid()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_READBACK_IDENTITY_CHANGED");
        }
        Instant expectedMicros = expectedObservedAt.truncatedTo(ChronoUnit.MICROS);
        List<BatchRow> batches = jdbc.query("""
                SELECT id, observed_at FROM pit_market_fact_batches WHERE id = ?
                """, (rs, row) -> new BatchRow(
                rs.getLong("id"), rs.getTimestamp("observed_at").toInstant()), batchId);
        if (batches.size() != 1) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_BATCH_READBACK_MISMATCH");
        }
        List<ObservationRow> observations = jdbc.query("""
                SELECT id, fact_type, first_observed_at, known_at
                  FROM pit_market_fact_observations
                 WHERE batch_id = ? ORDER BY id
                """, (rs, row) -> new ObservationRow(
                rs.getLong("id"), FactType.valueOf(rs.getString("fact_type")),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("known_at").toInstant()), batchId);
        EnumMap<FactType, Integer> counts = new EnumMap<>(FactType.class);
        List<Long> ids = new ArrayList<>();
        for (ObservationRow row : observations) {
            ids.add(row.id());
            counts.merge(row.factType(), 1, Integer::sum);
        }
        Map<FactType, Integer> required = Map.of(
                FactType.RAW_DAILY_BAR, 1,
                FactType.ADJUSTMENT_FACTOR, 1,
                FactType.TRADING_CALENDAR, 1);
        Instant minFirst = observations.stream().map(ObservationRow::firstObservedAt)
                .min(Instant::compareTo).orElse(Instant.EPOCH);
        Instant maxFirst = observations.stream().map(ObservationRow::firstObservedAt)
                .max(Instant::compareTo).orElse(Instant.EPOCH);
        Instant minKnown = observations.stream().map(ObservationRow::knownAt)
                .min(Instant::compareTo).orElse(Instant.EPOCH);
        Instant maxKnown = observations.stream().map(ObservationRow::knownAt)
                .max(Instant::compareTo).orElse(Instant.EPOCH);
        boolean exact = batches.get(0).observedAt().equals(expectedMicros)
                && minFirst.equals(expectedMicros) && maxFirst.equals(expectedMicros)
                && minKnown.equals(expectedMicros) && maxKnown.equals(expectedMicros);
        if (!counts.equals(required) || observations.size() != 3 || !exact
                || expectedMicros.isBefore(executionStartedAt)
                || expectedMicros.isAfter(readbackAt)) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_SYSTEM_KNOWLEDGE_READBACK_INVALID");
        }
        return new DatabaseReadbackEvidence(
                batchId, ids, counts, expectedMicros, minFirst, maxFirst,
                minKnown, maxKnown, readbackIdentity.backendPid(),
                readbackIdentity.currentDatabase(), readbackIdentity.currentUser(),
                readbackIdentity.currentSchema(), true);
    }

    private record BatchRow(long id, Instant observedAt) {
    }

    private record ObservationRow(
            long id,
            FactType factType,
            Instant firstObservedAt,
            Instant knownAt
    ) {
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
