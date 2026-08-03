package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.DatabaseReadbackEvidence;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchPersistenceGuard.Verification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads committed V13 rows after the capture transaction has returned. */
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
            DatabaseExecutionIdentity writeIdentity,
            String expectedSourceInstrumentId,
            String expectedSymbol,
            String expectedExchange,
            LocalDate expectedTradeDate
    ) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_POST_COMMIT_READBACK_REQUIRED");
        }
        Verification readbackIdentity = guard.verifyBeforeProvider();
        if (!writeIdentity.currentDatabase().equals(readbackIdentity.currentDatabase())
                || !writeIdentity.currentUser().equals(readbackIdentity.currentUser())
                || !writeIdentity.currentSchema().equals(readbackIdentity.currentSchema())
                || !writeIdentity.appliedMigrations().equals(readbackIdentity.appliedMigrations())
                || writeIdentity.backendPidBefore() != writeIdentity.backendPidAfter()) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_READBACK_IDENTITY_CHANGED");
        }
        Instant expectedMicros = expectedObservedAt.truncatedTo(ChronoUnit.MICROS);
        List<BatchRow> batches = jdbc.query("""
                SELECT id, source_code, source_instrument_id, range_start,
                       range_end, observed_at, run_namespace, capture_mode,
                       revision_qualification, assurance_level,
                       usage_qualification, formal_eligible,
                       local_persistence_allowed, historical_replay_allowed,
                       backtest_allowed, agent_use_allowed, response_complete,
                       record_count
                  FROM pit_market_fact_batches WHERE id = ?
                """, (rs, row) -> new BatchRow(
                rs.getLong("id"), rs.getString("source_code"),
                rs.getString("source_instrument_id"),
                rs.getDate("range_start").toLocalDate(),
                rs.getDate("range_end").toLocalDate(),
                rs.getTimestamp("observed_at").toInstant(),
                rs.getString("run_namespace"), rs.getString("capture_mode"),
                rs.getString("revision_qualification"),
                rs.getString("assurance_level"),
                rs.getString("usage_qualification"),
                rs.getBoolean("formal_eligible"),
                rs.getBoolean("local_persistence_allowed"),
                rs.getBoolean("historical_replay_allowed"),
                rs.getBoolean("backtest_allowed"),
                rs.getBoolean("agent_use_allowed"),
                rs.getBoolean("response_complete"),
                rs.getInt("record_count")), batchId);
        if (batches.size() != 1 || !batches.get(0).isExpected(
                expectedSourceInstrumentId, expectedTradeDate, expectedMicros)) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_BATCH_READBACK_MISMATCH");
        }
        List<ObservationRow> observations = jdbc.query("""
                SELECT observation.id, observation.fact_type,
                       observation.source_code,
                       observation.source_instrument_id,
                       observation.first_observed_at, observation.known_at,
                       raw.observation_id AS raw_id, raw.symbol AS raw_symbol,
                       raw.exchange AS raw_exchange,
                       raw.trade_date AS raw_date,
                       factor.observation_id AS factor_id,
                       factor.symbol AS factor_symbol,
                       factor.factor_effective_trade_date AS factor_date,
                       calendar.observation_id AS calendar_id,
                       calendar.exchange AS calendar_exchange,
                       calendar.calendar_date AS calendar_date,
                       calendar.is_open AS calendar_open,
                       calendar.session_code AS calendar_session
                  FROM pit_market_fact_observations observation
                  LEFT JOIN raw_daily_bar_facts_v2 raw
                    ON raw.observation_id=observation.id
                  LEFT JOIN adjustment_factor_facts_v1 factor
                    ON factor.observation_id=observation.id
                  LEFT JOIN trading_calendar_facts_v1 calendar
                    ON calendar.observation_id=observation.id
                 WHERE observation.batch_id = ? ORDER BY observation.id
                """, (rs, row) -> new ObservationRow(
                rs.getLong("id"), FactType.valueOf(rs.getString("fact_type")),
                rs.getString("source_code"),
                rs.getString("source_instrument_id"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("known_at").toInstant(),
                rs.getObject("raw_id", Long.class), rs.getString("raw_symbol"),
                rs.getString("raw_exchange"), localDate(rs.getDate("raw_date")),
                rs.getObject("factor_id", Long.class),
                rs.getString("factor_symbol"),
                localDate(rs.getDate("factor_date")),
                rs.getObject("calendar_id", Long.class),
                rs.getString("calendar_exchange"),
                localDate(rs.getDate("calendar_date")),
                rs.getObject("calendar_open", Boolean.class),
                rs.getString("calendar_session")), batchId);
        EnumMap<FactType, Integer> counts = new EnumMap<>(FactType.class);
        List<Long> ids = new ArrayList<>();
        for (ObservationRow row : observations) {
            if (!row.isExpected(expectedSymbol, expectedExchange,
                    expectedTradeDate)) {
                throw blocked(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_TYPED_FACT_READBACK_INVALID");
            }
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
        boolean exact = minFirst.equals(expectedMicros) && maxFirst.equals(expectedMicros)
                && minKnown.equals(expectedMicros) && maxKnown.equals(expectedMicros);
        if (!counts.equals(required) || observations.size() != 3 || !exact
                || expectedMicros.isBefore(executionStartedAt)
                || expectedMicros.isAfter(readbackAt)) {
            throw blocked("TUSHARE_CONTROLLED_ACCEPTANCE_SYSTEM_KNOWLEDGE_READBACK_INVALID");
        }
        return new DatabaseReadbackEvidence(
                batchId, ids, counts, expectedMicros, minFirst, maxFirst,
                minKnown, maxKnown, writeIdentity.backendPidAfter(),
                readbackIdentity.backendPid(),
                readbackIdentity.currentDatabase(), readbackIdentity.currentUser(),
                readbackIdentity.currentSchema(), true, true);
    }

    private static LocalDate localDate(java.sql.Date value) {
        return value == null ? null : value.toLocalDate();
    }

    private record BatchRow(
            long id,
            String sourceCode,
            String sourceInstrumentId,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Instant observedAt,
            String runNamespace,
            String captureMode,
            String revisionQualification,
            String assuranceLevel,
            String usageQualification,
            boolean formalEligible,
            boolean localPersistenceAllowed,
            boolean historicalReplayAllowed,
            boolean backtestAllowed,
            boolean agentUseAllowed,
            boolean responseComplete,
            int recordCount
    ) {
        boolean isExpected(
                String expectedSourceInstrumentId,
                LocalDate expectedTradeDate,
                Instant expectedObservedAt
        ) {
            return id > 0
                    && TushareMarketFactProvider.PROVIDER_CODE.equals(sourceCode)
                    && Objects.equals(sourceInstrumentId, expectedSourceInstrumentId)
                    && Objects.equals(rangeStart, expectedTradeDate)
                    && Objects.equals(rangeEnd, expectedTradeDate)
                    && Objects.equals(observedAt, expectedObservedAt)
                    && "FORMAL".equals(runNamespace)
                    && "PROVIDER_CAPTURE".equals(captureMode)
                    && "SYSTEM_KNOWLEDGE_ONLY".equals(revisionQualification)
                    && "SYSTEM_KNOWLEDGE_PIT".equals(assuranceLevel)
                    && "RESEARCH_ONLY".equals(usageQualification)
                    && !formalEligible
                    && localPersistenceAllowed
                    && historicalReplayAllowed
                    && backtestAllowed
                    && agentUseAllowed
                    && responseComplete
                    && recordCount == 3;
        }
    }

    private record ObservationRow(
            long id,
            FactType factType,
            String sourceCode,
            String sourceInstrumentId,
            Instant firstObservedAt,
            Instant knownAt,
            Long rawId,
            String rawSymbol,
            String rawExchange,
            LocalDate rawDate,
            Long factorId,
            String factorSymbol,
            LocalDate factorDate,
            Long calendarId,
            String calendarExchange,
            LocalDate calendarDate,
            Boolean calendarOpen,
            String calendarSession
    ) {
        boolean isExpected(
                String expectedSymbol,
                String expectedExchange,
                LocalDate expectedTradeDate
        ) {
            String expectedTypedSourceIdentity = switch (factType) {
                case RAW_DAILY_BAR -> TushareMarketFactProvider.rawSourceIdentity(
                        expectedSymbol, expectedExchange);
                case ADJUSTMENT_FACTOR ->
                        TushareMarketFactProvider.factorSourceIdentity(
                                expectedSymbol, expectedExchange);
                case TRADING_CALENDAR ->
                        TushareMarketFactProvider.calendarSourceIdentity(
                                expectedExchange);
                case CORPORATE_ACTION -> null;
            };
            if (id <= 0
                    || !TushareMarketFactProvider.PROVIDER_CODE.equals(sourceCode)
                    || !Objects.equals(sourceInstrumentId,
                            expectedTypedSourceIdentity)) {
                return false;
            }
            return switch (factType) {
                case RAW_DAILY_BAR -> rawId != null && factorId == null
                        && calendarId == null
                        && Objects.equals(rawSymbol, expectedSymbol)
                        && Objects.equals(rawExchange, expectedExchange)
                        && Objects.equals(rawDate, expectedTradeDate);
                case ADJUSTMENT_FACTOR -> factorId != null && rawId == null
                        && calendarId == null
                        && Objects.equals(factorSymbol, expectedSymbol)
                        && Objects.equals(factorDate, expectedTradeDate);
                case TRADING_CALENDAR -> calendarId != null && rawId == null
                        && factorId == null
                        && Objects.equals(calendarExchange, expectedExchange)
                        && Objects.equals(calendarDate, expectedTradeDate)
                        && Boolean.TRUE.equals(calendarOpen)
                        && "REGULAR".equals(calendarSession);
                case CORPORATE_ACTION -> false;
            };
        }
    }

    private static IllegalStateException blocked(String code) {
        return new IllegalStateException(code);
    }
}
