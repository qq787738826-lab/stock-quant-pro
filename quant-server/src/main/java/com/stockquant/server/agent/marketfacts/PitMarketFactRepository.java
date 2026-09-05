package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateAction;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateActionType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldSemantic;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldUnit;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.QualifiedMarketField;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.BatchLineage;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.BatchIdentity;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CorporateActionObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactEnvelope;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactorPredecessor;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Repository
public class PitMarketFactRepository {

    private static final String ENVELOPE_COLUMNS = """
            o.id, o.batch_id, o.fact_type, o.fact_contract_version,
            o.natural_key, o.chain_sequence, o.predecessor_observation_id,
            o.source_code, o.source_instrument_id,
            o.provider_dataset_version, o.provider_revision,
            o.provider_snapshot_id, o.provider_published_at,
            o.provider_updated_at, o.first_observed_at, o.known_at,
            o.recorded_at, o.canonical_content_hash, o.observation_version,
            o.revision_qualification, o.assurance_level,
            o.usage_qualification, o.formal_eligible,
            o.local_persistence_allowed, o.historical_replay_allowed,
            o.backtest_allowed, o.agent_use_allowed,
            o.raw_payload_json::text AS raw_payload_json
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PitMarketFactRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void lockChain(
            FactType type,
            String sourceCode,
            String sourceInstrumentId,
            String naturalKey
    ) {
        String key = type.name() + "|" + sourceCode + "|"
                + sourceInstrumentId + "|" + naturalKey;
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                prepared -> prepared.setString(1, key),
                resultSet -> {
                    if (!resultSet.next()) {
                        throw new IllegalStateException(
                                "could not acquire PIT fact chain lock");
                    }
                    return null;
                });
    }

    public Optional<FactEnvelope> findTail(
            FactType type,
            String sourceCode,
            String sourceInstrumentId,
            String naturalKey
    ) {
        return jdbcTemplate.query(
                "SELECT " + ENVELOPE_COLUMNS
                        + " FROM pit_market_fact_observations o"
                        + " WHERE o.fact_type=? AND o.source_code=?"
                        + " AND o.source_instrument_id=? AND o.natural_key=?"
                        + " ORDER BY o.chain_sequence DESC, o.id DESC LIMIT 1",
                this::mapEnvelope,
                type.name(), sourceCode, sourceInstrumentId, naturalKey
        ).stream().findFirst();
    }

    public long insertBatch(
            long datasetVersionId,
            BatchIdentity identity,
            String providerContractVersion,
            String captureMode,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Instant observedAt,
            Instant recordedAt,
            boolean complete,
            int recordCount,
            JsonNode factContracts,
            JsonNode capabilities,
            JsonNode metadata
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO pit_market_fact_batches(
                    batch_version, dataset_version_id, dataset_version,
                    provider_contract_version, market_facts_contract_version,
                    run_namespace, capture_mode, source_code,
                    source_instrument_id, provider_dataset_version,
                    revision_qualification, assurance_level,
                    usage_qualification, formal_eligible,
                    local_persistence_allowed, historical_replay_allowed,
                    backtest_allowed, agent_use_allowed,
                    range_start, range_end, observed_at, recorded_at,
                    response_complete, record_count, fact_contracts_json,
                    provider_capabilities_json, provider_metadata_json
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb
                ) RETURNING id
                """, Long.class,
                identity.batchVersion(), datasetVersionId, identity.datasetVersion(),
                providerContractVersion, PitMarketFactsContracts.MARKET_FACTS_VERSION,
                identity.runNamespace().name(), captureMode, identity.sourceCode(),
                identity.sourceInstrumentId(), identity.providerDatasetVersion(),
                identity.revisionQualification().name(),
                identity.assuranceLevel().name(),
                identity.usageQualification().name(),
                identity.formalEligible(), identity.localPersistenceAllowed(),
                identity.historicalReplayAllowed(), identity.backtestAllowed(),
                identity.agentUseAllowed(), rangeStart, rangeEnd,
                Timestamp.from(observedAt), Timestamp.from(recordedAt),
                complete, recordCount, json(factContracts), json(capabilities),
                json(metadata));
    }

    public FactEnvelope insertObservation(
            long batchId,
            BatchIdentity identity,
            FactType type,
            String naturalKey,
            int sequence,
            Long predecessorId,
            String sourceIdentity,
            ProviderVersion providerVersion,
            Instant firstObservedAt,
            Instant knownAt,
            Instant recordedAt,
            String contentHash,
            String observationVersion,
            JsonNode rawPayload
    ) {
        List<FactEnvelope> result = jdbcTemplate.query("""
                INSERT INTO pit_market_fact_observations(
                    batch_id, fact_type, fact_contract_version, natural_key,
                    chain_sequence, predecessor_observation_id, source_code,
                    source_instrument_id, provider_dataset_version,
                    provider_revision, provider_snapshot_id,
                    provider_published_at, provider_updated_at,
                    first_observed_at, known_at, recorded_at,
                    canonical_content_hash, observation_version,
                    revision_qualification, assurance_level,
                    usage_qualification, formal_eligible,
                    local_persistence_allowed, historical_replay_allowed,
                    backtest_allowed, agent_use_allowed, raw_payload_json
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?::jsonb
                ) RETURNING
                """ + ENVELOPE_COLUMNS.replace("o.", ""),
                this::mapEnvelope,
                batchId, type.name(), type.contractVersion(), naturalKey,
                sequence, predecessorId, identity.sourceCode(),
                sourceIdentity,
                providerVersion.providerDatasetVersion(),
                providerVersion.providerRevision(),
                providerVersion.providerSnapshotId(),
                timestamp(providerVersion.providerPublishedAt()),
                timestamp(providerVersion.providerUpdatedAt()),
                Timestamp.from(firstObservedAt), Timestamp.from(knownAt),
                Timestamp.from(recordedAt), contentHash, observationVersion,
                identity.revisionQualification().name(),
                identity.assuranceLevel().name(),
                identity.usageQualification().name(),
                identity.formalEligible(), identity.localPersistenceAllowed(),
                identity.historicalReplayAllowed(), identity.backtestAllowed(),
                identity.agentUseAllowed(), json(rawPayload));
        return result.get(0);
    }

    public void insertTyped(FactEnvelope envelope, Object fact) {
        if (fact instanceof RawDailyBar value) {
            jdbcTemplate.update("""
                    INSERT INTO raw_daily_bar_facts_v2(
                        observation_id, symbol, exchange, trade_date,
                        open, high, low, close,
                        volume, volume_qualification, volume_unit_code,
                        volume_semantic_code,
                        amount, amount_qualification, amount_unit_code,
                        amount_semantic_code,
                        turnover_rate, turnover_rate_qualification,
                        turnover_rate_unit_code, turnover_rate_semantic_code
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        ?, ?, ?, ?
                    )
                    """, envelope.id(), value.symbol(), value.exchange(),
                    value.tradeDate(), value.open(), value.high(), value.low(),
                    value.close(),
                    fieldValue(value.volume()),
                    value.volume().qualification().name(),
                    value.volume().unitCode().name(),
                    value.volume().semanticCode().name(),
                    fieldValue(value.amount()),
                    value.amount().qualification().name(),
                    value.amount().unitCode().name(),
                    value.amount().semanticCode().name(),
                    fieldValue(value.turnoverRate()),
                    value.turnoverRate().qualification().name(),
                    value.turnoverRate().unitCode().name(),
                    value.turnoverRate().semanticCode().name());
        } else if (fact instanceof AdjustmentFactor value) {
            jdbcTemplate.update("""
                    INSERT INTO adjustment_factor_facts_v1(
                        observation_id, symbol, factor_effective_trade_date,
                        factor_type, coverage_mode, factor
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, envelope.id(), value.symbol(),
                    value.factorEffectiveTradeDate(), value.factorType(),
                    value.coverageMode(), value.factor());
        } else if (fact instanceof TradingCalendar value) {
            jdbcTemplate.update("""
                    INSERT INTO trading_calendar_facts_v1(
                        observation_id, exchange, calendar_date,
                        is_open, session_code
                    ) VALUES (?, ?, ?, ?, ?)
                    """, envelope.id(), value.exchange(), value.calendarDate(),
                    value.open(), value.sessionCode());
        } else if (fact instanceof CorporateAction value) {
            jdbcTemplate.update("""
                    INSERT INTO corporate_action_facts_v1(
                        observation_id, source_action_id, symbol, action_type,
                        announcement_date, effective_trade_date, terms_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, envelope.id(), value.sourceActionId(), value.symbol(),
                    value.actionType().name(), value.announcementDate(),
                    value.effectiveTradeDate(), json(value.terms()));
        } else {
            throw new IllegalArgumentException("unsupported typed market fact");
        }
    }

    public Optional<TradingCalendarObservation> findEffectiveTradeDate(
            String sourceCode,
            String sourceInstrumentId,
            String exchange,
            LocalDate requestTradeDate,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, c.exchange, c.calendar_date, c.is_open,
                           c.session_code,
                           row_number() OVER (
                               PARTITION BY c.exchange, c.calendar_date
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN trading_calendar_facts_v1 c
                      ON c.observation_id=o.id
                    WHERE o.fact_type='TRADING_CALENDAR'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND c.exchange=?
                      AND c.calendar_date<=?
                      AND o.known_at<=?
            )
                SELECT * FROM visible
                WHERE selected_version=1 AND is_open
                ORDER BY calendar_date DESC LIMIT 1
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapCalendar,
                sourceCode, sourceInstrumentId, exchange, requestTradeDate,
                Timestamp.from(cutoff)).stream().findFirst();
    }

    public List<TradingCalendarObservation> findOpenCalendarAsOf(
            String sourceCode,
            String sourceInstrumentId,
            String exchange,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, c.exchange, c.calendar_date, c.is_open,
                           c.session_code,
                           row_number() OVER (
                               PARTITION BY c.exchange, c.calendar_date
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN trading_calendar_facts_v1 c
                      ON c.observation_id=o.id
                    WHERE o.fact_type='TRADING_CALENDAR'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND c.exchange=?
                      AND c.calendar_date BETWEEN ? AND ?
                      AND o.known_at<=?
            )
                SELECT * FROM visible
                WHERE selected_version=1 AND is_open
                ORDER BY calendar_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapCalendar,
                sourceCode, sourceInstrumentId, exchange, from, to,
                Timestamp.from(cutoff));
    }

    public List<TradingCalendarObservation> findCalendarAsOf(
            String sourceCode,
            String sourceInstrumentId,
            String exchange,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, c.exchange, c.calendar_date, c.is_open,
                           c.session_code,
                           row_number() OVER (
                               PARTITION BY c.exchange, c.calendar_date
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN trading_calendar_facts_v1 c
                      ON c.observation_id=o.id
                    WHERE o.fact_type='TRADING_CALENDAR'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND c.exchange=?
                      AND c.calendar_date BETWEEN ? AND ?
                      AND o.known_at<=?
            )
                SELECT * FROM visible
                WHERE selected_version=1
                ORDER BY calendar_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapCalendar,
                sourceCode, sourceInstrumentId, exchange, from, to,
                Timestamp.from(cutoff));
    }

    public List<RawDailyBarObservation> findRawBarsAsOf(
            String sourceCode,
            String sourceInstrumentId,
            String symbol,
            String exchange,
            LocalDate effectiveTradeDate,
            Instant cutoff,
            int limit
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, b.symbol, b.exchange, b.trade_date,
                           b.open, b.high, b.low, b.close, b.volume,
                           b.volume_qualification, b.volume_unit_code,
                           b.volume_semantic_code,
                           b.amount, b.amount_qualification,
                           b.amount_unit_code, b.amount_semantic_code,
                           b.turnover_rate, b.turnover_rate_qualification,
                           b.turnover_rate_unit_code,
                           b.turnover_rate_semantic_code,
                           row_number() OVER (
                               PARTITION BY b.symbol, b.exchange, b.trade_date
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN raw_daily_bar_facts_v2 b ON b.observation_id=o.id
                    WHERE o.fact_type='RAW_DAILY_BAR'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND b.symbol=?
                      AND b.exchange=?
                      AND b.trade_date<=?
                      AND o.known_at<=?
            )
                SELECT * FROM (
                    SELECT * FROM visible
                    WHERE selected_version=1
                    ORDER BY trade_date DESC
                    LIMIT ?
                ) selected
                ORDER BY trade_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapRaw,
                sourceCode, sourceInstrumentId, symbol, exchange,
                effectiveTradeDate,
                Timestamp.from(cutoff), limit);
    }

    public List<RawDailyBarObservation> findRawBarsWindowAsOf(
            String sourceCode,
            String sourceInstrumentId,
            String symbol,
            String exchange,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, b.symbol, b.exchange, b.trade_date,
                           b.open, b.high, b.low, b.close, b.volume,
                           b.volume_qualification, b.volume_unit_code,
                           b.volume_semantic_code,
                           b.amount, b.amount_qualification,
                           b.amount_unit_code, b.amount_semantic_code,
                           b.turnover_rate, b.turnover_rate_qualification,
                           b.turnover_rate_unit_code,
                           b.turnover_rate_semantic_code,
                           row_number() OVER (
                               PARTITION BY b.symbol, b.exchange, b.trade_date
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN raw_daily_bar_facts_v2 b ON b.observation_id=o.id
                    WHERE o.fact_type='RAW_DAILY_BAR'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND b.symbol=?
                      AND b.exchange=?
                      AND b.trade_date BETWEEN ? AND ?
                      AND o.known_at<=?
            )
                SELECT * FROM visible
                WHERE selected_version=1
                ORDER BY trade_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapRaw,
                sourceCode, sourceInstrumentId, symbol, exchange,
                from, to, Timestamp.from(cutoff));
    }

    /** One batch query for every member of an immutable main-board snapshot. */
    public List<RawDailyBarObservation> findRawBarsForSnapshotAsOf(
            long snapshotDatabaseId,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, b.symbol, b.exchange, b.trade_date,
                           b.open, b.high, b.low, b.close, b.volume,
                           b.volume_qualification, b.volume_unit_code,
                           b.volume_semantic_code,
                           b.amount, b.amount_qualification,
                           b.amount_unit_code, b.amount_semantic_code,
                           b.turnover_rate, b.turnover_rate_qualification,
                           b.turnover_rate_unit_code,
                           b.turnover_rate_semantic_code,
                           row_number() OVER (
                               PARTITION BY b.symbol, b.exchange, b.trade_date
                               ORDER BY
                                    CASE o.revision_qualification
                                      WHEN 'PROVIDER_VERIFIED' THEN 4
                                      WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                      WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                      WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                      ELSE 0
                                    END DESC,
                                    o.known_at DESC,
                                    o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                      FROM research_universe_members member
                      JOIN raw_daily_bar_facts_v2 b
                        ON b.symbol=member.symbol
                       AND b.exchange=member.exchange
                      JOIN pit_market_fact_observations o
                        ON o.id=b.observation_id
                       AND o.fact_type='RAW_DAILY_BAR'
                       AND o.source_code='TUSHARE_PRO'
                       AND o.source_instrument_id=
                           'TUSHARE:SECURITY:' || member.ts_code
                      JOIN pit_market_fact_batches capture
                        ON capture.id=o.batch_id AND capture.response_complete
                     WHERE member.snapshot_db_id=?
                       AND b.trade_date BETWEEN ? AND ?
                       AND o.known_at<=?
                )
                SELECT * FROM visible WHERE selected_version=1
                 ORDER BY exchange, symbol, trade_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapRaw, snapshotDatabaseId,
                from, to, Timestamp.from(cutoff));
    }

    /**
     * Streams one bounded member batch through a real PostgreSQL cursor.
     * The callback must consume each row synchronously and must not retain
     * the ResultSet. This is the production path for full-mainboard datasets;
     * the list-returning method remains for bounded completeness audits.
     */
    public void streamRawBarsForSnapshotMembersAsOf(
            long snapshotDatabaseId,
            List<String> memberTsCodes,
            LocalDate from,
            LocalDate to,
            Instant cutoff,
            int fetchSize,
            Consumer<RawDailyBarObservation> consumer
    ) {
        requireStreamArguments(snapshotDatabaseId, memberTsCodes, from, to,
                cutoff, fetchSize, consumer);
        String sql = """
                WITH visible AS (
                    SELECT %s, b.symbol, b.exchange, b.trade_date,
                           b.open, b.high, b.low, b.close, b.volume,
                           b.volume_qualification, b.volume_unit_code,
                           b.volume_semantic_code,
                           b.amount, b.amount_qualification,
                           b.amount_unit_code, b.amount_semantic_code,
                           b.turnover_rate, b.turnover_rate_qualification,
                           b.turnover_rate_unit_code,
                           b.turnover_rate_semantic_code,
                           row_number() OVER (
                               PARTITION BY b.symbol, b.exchange, b.trade_date
                               ORDER BY
                                    CASE o.revision_qualification
                                      WHEN 'PROVIDER_VERIFIED' THEN 4
                                      WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                      WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                      WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                      ELSE 0
                                    END DESC,
                                    o.known_at DESC,
                                    o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                      FROM research_universe_members member
                      JOIN raw_daily_bar_facts_v2 b
                        ON b.symbol=member.symbol
                       AND b.exchange=member.exchange
                      JOIN pit_market_fact_observations o
                        ON o.id=b.observation_id
                       AND o.fact_type='RAW_DAILY_BAR'
                       AND o.source_code='TUSHARE_PRO'
                       AND o.source_instrument_id=
                           'TUSHARE:SECURITY:' || member.ts_code
                      JOIN pit_market_fact_batches capture
                        ON capture.id=o.batch_id AND capture.response_complete
                     WHERE member.snapshot_db_id=?
                       AND member.ts_code IN (%s)
                       AND b.trade_date BETWEEN ? AND ?
                       AND o.known_at<=?
                )
                SELECT * FROM visible WHERE selected_version=1
                 ORDER BY exchange, symbol, trade_date
                """.formatted(ENVELOPE_COLUMNS,
                placeholders(memberTsCodes.size()));
        streamQuery(sql, statement -> {
            int parameter = 1;
            statement.setLong(parameter++, snapshotDatabaseId);
            for (String tsCode : memberTsCodes) {
                statement.setString(parameter++, tsCode);
            }
            statement.setObject(parameter++, from);
            statement.setObject(parameter++, to);
            statement.setTimestamp(parameter, Timestamp.from(cutoff));
        }, fetchSize, this::mapRaw, consumer);
    }

    public List<AdjustmentFactorObservation> findFactorsAsOf(
            String sourceCode,
            String sourceInstrumentId,
            String symbol,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, f.symbol, f.factor_effective_trade_date,
                           f.factor_type, f.coverage_mode, f.factor,
                           row_number() OVER (
                               PARTITION BY f.symbol, f.factor_type,
                                            f.factor_effective_trade_date
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN adjustment_factor_facts_v1 f
                      ON f.observation_id=o.id
                    WHERE o.fact_type='ADJUSTMENT_FACTOR'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND f.symbol=?
                      AND f.factor_type=?
                      AND f.coverage_mode=?
                      AND f.factor_effective_trade_date BETWEEN ? AND ?
                      AND o.known_at<=?
            )
                SELECT * FROM visible
                WHERE selected_version=1
                ORDER BY factor_effective_trade_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapFactor,
                sourceCode, sourceInstrumentId, symbol,
                PitMarketFactsContracts.FACTOR_TYPE,
                PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                from, to, Timestamp.from(cutoff));
    }

    /** One batch factor query for every member of one main-board snapshot. */
    public List<AdjustmentFactorObservation> findFactorsForSnapshotAsOf(
            long snapshotDatabaseId,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, f.symbol, f.factor_effective_trade_date,
                           f.factor_type, f.coverage_mode, f.factor,
                           row_number() OVER (
                               PARTITION BY f.symbol, f.factor_type,
                                            f.factor_effective_trade_date
                               ORDER BY
                                    CASE o.revision_qualification
                                      WHEN 'PROVIDER_VERIFIED' THEN 4
                                      WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                      WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                      WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                      ELSE 0
                                    END DESC,
                                    o.known_at DESC,
                                    o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                      FROM research_universe_members member
                      JOIN adjustment_factor_facts_v1 f
                        ON f.symbol=member.symbol
                      JOIN pit_market_fact_observations o
                        ON o.id=f.observation_id
                       AND o.fact_type='ADJUSTMENT_FACTOR'
                       AND o.source_code='TUSHARE_PRO'
                       AND o.source_instrument_id=
                           'TUSHARE:ADJ_FACTOR:' || member.ts_code
                      JOIN pit_market_fact_batches capture
                        ON capture.id=o.batch_id AND capture.response_complete
                     WHERE member.snapshot_db_id=?
                       AND f.factor_type=? AND f.coverage_mode=?
                       AND f.factor_effective_trade_date BETWEEN ? AND ?
                       AND o.known_at<=?
                )
                SELECT * FROM visible WHERE selected_version=1
                 ORDER BY symbol, factor_effective_trade_date
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapFactor, snapshotDatabaseId,
                PitMarketFactsContracts.FACTOR_TYPE,
                PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                from, to, Timestamp.from(cutoff));
    }

    /** Streams adjustment factors for the same bounded snapshot batch. */
    public void streamFactorsForSnapshotMembersAsOf(
            long snapshotDatabaseId,
            List<String> memberTsCodes,
            LocalDate from,
            LocalDate to,
            Instant cutoff,
            int fetchSize,
            Consumer<AdjustmentFactorObservation> consumer
    ) {
        requireStreamArguments(snapshotDatabaseId, memberTsCodes, from, to,
                cutoff, fetchSize, consumer);
        String sql = """
                WITH visible AS (
                    SELECT %s, f.symbol, f.factor_effective_trade_date,
                           f.factor_type, f.coverage_mode, f.factor,
                           row_number() OVER (
                               PARTITION BY f.symbol, f.factor_type,
                                            f.factor_effective_trade_date
                               ORDER BY
                                    CASE o.revision_qualification
                                      WHEN 'PROVIDER_VERIFIED' THEN 4
                                      WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                      WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                      WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                      ELSE 0
                                    END DESC,
                                    o.known_at DESC,
                                    o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                      FROM research_universe_members member
                      JOIN adjustment_factor_facts_v1 f
                        ON f.symbol=member.symbol
                      JOIN pit_market_fact_observations o
                        ON o.id=f.observation_id
                       AND o.fact_type='ADJUSTMENT_FACTOR'
                       AND o.source_code='TUSHARE_PRO'
                       AND o.source_instrument_id=
                           'TUSHARE:ADJ_FACTOR:' || member.ts_code
                      JOIN pit_market_fact_batches capture
                        ON capture.id=o.batch_id AND capture.response_complete
                     WHERE member.snapshot_db_id=?
                       AND member.ts_code IN (%s)
                       AND f.factor_type=? AND f.coverage_mode=?
                       AND f.factor_effective_trade_date BETWEEN ? AND ?
                       AND o.known_at<=?
                )
                SELECT * FROM visible WHERE selected_version=1
                 ORDER BY symbol, factor_effective_trade_date
                """.formatted(ENVELOPE_COLUMNS,
                placeholders(memberTsCodes.size()));
        streamQuery(sql, statement -> {
            int parameter = 1;
            statement.setLong(parameter++, snapshotDatabaseId);
            for (String tsCode : memberTsCodes) {
                statement.setString(parameter++, tsCode);
            }
            statement.setString(parameter++,
                    PitMarketFactsContracts.FACTOR_TYPE);
            statement.setString(parameter++,
                    PitMarketFactsContracts.FACTOR_COVERAGE_MODE);
            statement.setObject(parameter++, from);
            statement.setObject(parameter++, to);
            statement.setTimestamp(parameter, Timestamp.from(cutoff));
        }, fetchSize, this::mapFactor, consumer);
    }

    public List<CorporateActionObservation> findActionsAsOf(
            String sourceCode,
            String sourceInstrumentId,
            String symbol,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        String sql = """
                WITH visible AS (
                    SELECT %s, a.source_action_id, a.symbol, a.action_type,
                           a.announcement_date, a.effective_trade_date,
                           a.terms_json::text AS terms_json,
                           row_number() OVER (
                               PARTITION BY a.symbol, a.source_action_id
                               ORDER BY
                                        CASE o.revision_qualification
                                          WHEN 'PROVIDER_VERIFIED' THEN 4
                                          WHEN 'SYSTEM_KNOWLEDGE_ONLY' THEN 3
                                          WHEN 'PROVIDER_UNVERIFIED' THEN 2
                                          WHEN 'PROVIDER_UNAVAILABLE' THEN 1
                                          ELSE 0
                                        END DESC,
                                        o.known_at DESC,
                                        o.chain_sequence DESC, o.id DESC
                           ) AS selected_version
                    FROM pit_market_fact_observations o
                    JOIN pit_market_fact_batches capture
                      ON capture.id=o.batch_id
                     AND capture.response_complete
                    JOIN corporate_action_facts_v1 a
                      ON a.observation_id=o.id
                    WHERE o.fact_type='CORPORATE_ACTION'
                      AND o.source_code=?
                      AND o.source_instrument_id=?
                      AND a.symbol=?
                      AND a.effective_trade_date BETWEEN ? AND ?
                      AND o.known_at<=?
            )
                SELECT * FROM visible
                WHERE selected_version=1
                ORDER BY effective_trade_date, source_action_id
                """.formatted(ENVELOPE_COLUMNS);
        return jdbcTemplate.query(sql, this::mapAction,
                sourceCode, sourceInstrumentId, symbol, from, to,
                Timestamp.from(cutoff));
    }

    public List<BatchLineage> findBatchLineage(Collection<Long> batchIds) {
        List<Long> ids = batchIds.stream().distinct().sorted().toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(
                ",", Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("""
                SELECT batch_version, dataset_version,
                       provider_dataset_version, run_namespace,
                       source_code, source_instrument_id,
                       revision_qualification, assurance_level,
                       usage_qualification, observed_at, response_complete
                FROM pit_market_fact_batches
                WHERE id IN (%s)
                ORDER BY batch_version
                """.formatted(placeholders),
                (rs, row) -> new BatchLineage(
                        rs.getString("batch_version"),
                        rs.getString("dataset_version"),
                        rs.getString("provider_dataset_version"),
                        RunNamespace.valueOf(rs.getString("run_namespace")),
                        rs.getString("source_code"),
                        rs.getString("source_instrument_id"),
                        RevisionQualification.valueOf(
                                rs.getString("revision_qualification")),
                        AssuranceLevel.valueOf(
                                rs.getString("assurance_level")),
                        UsageQualification.valueOf(
                                rs.getString("usage_qualification")),
                        instant(rs.getObject("observed_at")),
                        rs.getBoolean("response_complete")),
                ids.toArray());
    }

    public List<FactorPredecessor> findFactorPredecessors(
            Collection<Long> observationIds
    ) {
        List<Long> ids = observationIds.stream().distinct().sorted().toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(
                ",", Collections.nCopies(ids.size(), "?"));
        return jdbcTemplate.query("""
                SELECT current_observation.id AS observation_id,
                       predecessor_observation.source_code,
                       predecessor_observation.source_instrument_id,
                       predecessor_factor.symbol,
                       predecessor_factor.factor_effective_trade_date,
                       predecessor_factor.factor AS predecessor_factor,
                       predecessor_observation.known_at AS predecessor_known_at,
                       predecessor_observation.revision_qualification
                FROM pit_market_fact_observations current_observation
                JOIN pit_market_fact_observations predecessor_observation
                  ON predecessor_observation.id =
                     current_observation.predecessor_observation_id
                JOIN adjustment_factor_facts_v1 predecessor_factor
                  ON predecessor_factor.observation_id =
                     predecessor_observation.id
                WHERE current_observation.id IN (%s)
                  AND current_observation.fact_type='ADJUSTMENT_FACTOR'
                  AND predecessor_observation.fact_type='ADJUSTMENT_FACTOR'
                ORDER BY current_observation.id
                """.formatted(placeholders),
                (rs, row) -> new FactorPredecessor(
                        rs.getLong("observation_id"),
                        rs.getString("source_code"),
                        rs.getString("source_instrument_id"),
                        rs.getString("symbol"),
                        rs.getObject(
                                "factor_effective_trade_date",
                                LocalDate.class),
                        rs.getBigDecimal("predecessor_factor"),
                        instant(rs.getObject("predecessor_known_at")),
                        RevisionQualification.valueOf(
                                rs.getString("revision_qualification"))),
                ids.toArray());
    }

    private FactEnvelope mapEnvelope(ResultSet rs, int row) throws SQLException {
        return new FactEnvelope(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                FactType.valueOf(rs.getString("fact_type")),
                rs.getString("fact_contract_version"),
                rs.getString("natural_key"),
                rs.getInt("chain_sequence"),
                nullableLong(rs, "predecessor_observation_id"),
                rs.getString("source_code"),
                rs.getString("source_instrument_id"),
                rs.getString("provider_dataset_version"),
                rs.getString("provider_revision"),
                rs.getString("provider_snapshot_id"),
                instant(rs.getObject("provider_published_at")),
                instant(rs.getObject("provider_updated_at")),
                instant(rs.getObject("first_observed_at")),
                instant(rs.getObject("known_at")),
                instant(rs.getObject("recorded_at")),
                rs.getString("canonical_content_hash"),
                rs.getString("observation_version"),
                RevisionQualification.valueOf(
                        rs.getString("revision_qualification")),
                AssuranceLevel.valueOf(rs.getString("assurance_level")),
                UsageQualification.valueOf(rs.getString("usage_qualification")),
                rs.getBoolean("formal_eligible"),
                rs.getBoolean("local_persistence_allowed"),
                rs.getBoolean("historical_replay_allowed"),
                rs.getBoolean("backtest_allowed"),
                rs.getBoolean("agent_use_allowed"),
                readJson(rs.getString("raw_payload_json")));
    }

    private <T> void streamQuery(
            String sql,
            StatementBinder binder,
            int fetchSize,
            RowMapper<T> mapper,
            Consumer<T> consumer
    ) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean localReadTransaction = connection.getAutoCommit();
            if (localReadTransaction) {
                connection.setAutoCommit(false);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchDirection(ResultSet.FETCH_FORWARD);
                statement.setFetchSize(fetchSize);
                binder.bind(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    int row = 0;
                    while (resultSet.next()) {
                        consumer.accept(mapper.mapRow(resultSet, row++));
                    }
                }
            } finally {
                if (localReadTransaction) {
                    try {
                        connection.rollback();
                    } finally {
                        connection.setAutoCommit(true);
                    }
                }
            }
            return null;
        });
    }

    private static void requireStreamArguments(
            long snapshotDatabaseId,
            List<String> memberTsCodes,
            LocalDate from,
            LocalDate to,
            Instant cutoff,
            int fetchSize,
            Consumer<?> consumer
    ) {
        Objects.requireNonNull(memberTsCodes, "memberTsCodes");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(cutoff, "cutoff");
        Objects.requireNonNull(consumer, "consumer");
        if (snapshotDatabaseId < 1 || memberTsCodes.isEmpty()
                || memberTsCodes.size() > 100 || from.isAfter(to)
                || fetchSize < 1 || fetchSize > 10_000
                || memberTsCodes.stream().anyMatch(value -> value == null
                || !value.matches("[0-9]{6}\\.(SH|SZ)"))
                || memberTsCodes.stream().distinct().count()
                != memberTsCodes.size()) {
            throw new IllegalArgumentException(
                    "PIT_SNAPSHOT_STREAM_ARGUMENTS_INVALID");
        }
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private RawDailyBarObservation mapRaw(ResultSet rs, int row) throws SQLException {
        return new RawDailyBarObservation(
                mapEnvelope(rs, row),
                rs.getString("symbol"),
                rs.getString("exchange"),
                rs.getObject("trade_date", LocalDate.class),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                qualifiedField(rs, "volume"),
                qualifiedField(rs, "amount"),
                qualifiedField(rs, "turnover_rate"));
    }

    private AdjustmentFactorObservation mapFactor(
            ResultSet rs,
            int row
    ) throws SQLException {
        return new AdjustmentFactorObservation(
                mapEnvelope(rs, row),
                rs.getString("symbol"),
                rs.getObject("factor_effective_trade_date", LocalDate.class),
                rs.getString("factor_type"),
                rs.getString("coverage_mode"),
                rs.getBigDecimal("factor"));
    }

    private TradingCalendarObservation mapCalendar(
            ResultSet rs,
            int row
    ) throws SQLException {
        return new TradingCalendarObservation(
                mapEnvelope(rs, row),
                rs.getString("exchange"),
                rs.getObject("calendar_date", LocalDate.class),
                rs.getBoolean("is_open"),
                rs.getString("session_code"));
    }

    private CorporateActionObservation mapAction(
            ResultSet rs,
            int row
    ) throws SQLException {
        return new CorporateActionObservation(
                mapEnvelope(rs, row),
                rs.getString("source_action_id"),
                rs.getString("symbol"),
                CorporateActionType.valueOf(rs.getString("action_type")),
                rs.getObject("announcement_date", LocalDate.class),
                rs.getObject("effective_trade_date", LocalDate.class),
                readJson(rs.getString("terms_json")));
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("market fact JSON is invalid", error);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("stored market fact JSON is invalid", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        return (Instant) value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static java.math.BigDecimal fieldValue(
            QualifiedMarketField value
    ) {
        return value.value();
    }

    private static QualifiedMarketField qualifiedField(
            ResultSet rs,
            String prefix
    ) throws SQLException {
        return new QualifiedMarketField(
                rs.getBigDecimal(prefix),
                FieldQualification.valueOf(
                        rs.getString(prefix + "_qualification")),
                MarketFieldUnit.valueOf(
                        rs.getString(prefix + "_unit_code")),
                MarketFieldSemantic.valueOf(
                        rs.getString(prefix + "_semantic_code")));
    }
}
