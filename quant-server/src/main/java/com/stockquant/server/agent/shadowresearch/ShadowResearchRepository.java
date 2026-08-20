package com.stockquant.server.agent.shadowresearch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.FrozenSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperFill;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrder;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperOrderStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperPortfolio;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PaperPosition;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.PortfolioSnapshot;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.RunStatus;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRecommendation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowRun;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.ShadowOutcome;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.OutcomeObservation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.Side;
import com.stockquant.server.agent.shadowresearch.ShadowResearchModels.TriggerMode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** PostgreSQL persistence for immutable M4 shadow facts and paper accounting. */
@org.springframework.stereotype.Repository
public class ShadowResearchRepository {
    private static final String RUN_COLUMNS = """
            id, run_key, attempt, status, trigger_mode, trade_date,
            research_slot, research_as_of, signal_time,
            paper_execution_time, strategy_version, model_provider,
            model, prompt_version, agent_runtime_version,
            dataset_fingerprint, strategy_fingerprint,
            research_fingerprint, request_fingerprint, error_code,
            started_at, completed_at, created_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ShadowResearchRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<ShadowRun> frozenSlot(
            LocalDate tradeDate,
            String slot,
            String strategyVersion
    ) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM shadow_research_runs"
                        + " WHERE trade_date=? AND research_slot=?"
                        + " AND strategy_version=? AND status='FROZEN'",
                this::mapRun, tradeDate, slot, strategyVersion)
                .stream().findFirst();
    }

    public Optional<ShadowRun> slot(
            LocalDate tradeDate,
            String slot,
            String strategyVersion
    ) {
        return jdbc.query("SELECT " + RUN_COLUMNS
                        + " FROM shadow_research_runs WHERE trade_date=?"
                        + " AND research_slot=? AND strategy_version=?"
                        + " AND status IN ('QUEUED','RUNNING','FROZEN')"
                        + " ORDER BY id DESC LIMIT 1",
                this::mapRun, tradeDate, slot, strategyVersion)
                .stream().findFirst();
    }

    public Optional<ShadowRun> run(long runId) {
        return jdbc.query("SELECT " + RUN_COLUMNS
                        + " FROM shadow_research_runs WHERE id=?",
                this::mapRun, runId).stream().findFirst();
    }

    public Optional<ShadowRun> activeRun() {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM shadow_research_runs"
                        + " WHERE status IN ('QUEUED','RUNNING')"
                        + " ORDER BY id LIMIT 1",
                this::mapRun).stream().findFirst();
    }

    public boolean researchCalendarOpen(LocalDate date, Instant cutoff) {
        return researchCalendarState(date, cutoff) == CalendarState.OPEN;
    }

    public CalendarState researchCalendarState(
            LocalDate date,
            Instant cutoff
    ) {
        List<CalendarFact> values = jdbc.query("""
                SELECT c.exchange, c.is_open
                  FROM trading_calendar_facts_v1 c
                  JOIN pit_market_fact_observations o
                    ON o.id=c.observation_id
                 WHERE c.calendar_date=? AND c.exchange IN ('SSE','SZSE')
                   AND o.known_at<=?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pit_market_fact_observations newer
                         JOIN trading_calendar_facts_v1 nc
                           ON nc.observation_id=newer.id
                        WHERE newer.fact_type=o.fact_type
                          AND newer.source_code=o.source_code
                          AND newer.source_instrument_id=
                              o.source_instrument_id
                          AND nc.exchange=c.exchange
                          AND nc.calendar_date=c.calendar_date
                          AND newer.known_at<=?
                          AND (newer.known_at, newer.id)>(o.known_at, o.id)
                   )
                """, (row, number) -> new CalendarFact(
                        row.getString(1), row.getBoolean(2)), date,
                Timestamp.from(cutoff), Timestamp.from(cutoff));
        if (values.size() != 2
                || values.stream().map(CalendarFact::exchange).distinct()
                .count() != 2
                || !values.stream().map(CalendarFact::exchange).collect(
                java.util.stream.Collectors.toSet())
                .equals(java.util.Set.of("SSE", "SZSE"))
                || values.stream().map(CalendarFact::open).distinct()
                .count() != 1) {
            return CalendarState.UNKNOWN;
        }
        return values.get(0).open() ? CalendarState.OPEN
                : CalendarState.CLOSED;
    }

    public enum CalendarState { OPEN, CLOSED, UNKNOWN }

    private record CalendarFact(String exchange, boolean open) {
    }

    /** True only when every natural date up to a common next open is known. */
    public boolean nextCommonOpenKnown(LocalDate date, Instant cutoff) {
        LocalDate from = date.plusDays(1);
        LocalDate to = date.plusDays(30);
        List<DatedCalendarFact> values = jdbc.query("""
                SELECT c.calendar_date, c.exchange, c.is_open
                  FROM trading_calendar_facts_v1 c
                  JOIN pit_market_fact_observations o
                    ON o.id=c.observation_id
                 WHERE c.calendar_date BETWEEN ? AND ?
                   AND c.exchange IN ('SSE','SZSE')
                   AND o.source_code='TUSHARE_PRO'
                   AND o.source_instrument_id=CASE c.exchange
                       WHEN 'SSE' THEN 'TUSHARE:TRADE_CAL:SSE'
                       WHEN 'SZSE' THEN 'TUSHARE:TRADE_CAL:SZSE'
                   END
                   AND o.known_at<=?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pit_market_fact_observations newer
                         JOIN trading_calendar_facts_v1 nc
                           ON nc.observation_id=newer.id
                        WHERE newer.fact_type='TRADING_CALENDAR'
                          AND newer.source_code=o.source_code
                          AND newer.source_instrument_id=
                              o.source_instrument_id
                          AND nc.exchange=c.exchange
                          AND nc.calendar_date=c.calendar_date
                          AND newer.known_at<=?
                          AND (newer.known_at,newer.id)>(o.known_at,o.id)
                   )
                 ORDER BY c.calendar_date, c.exchange
                """, (row, number) -> new DatedCalendarFact(
                        row.getObject(1, LocalDate.class), row.getString(2),
                        row.getBoolean(3)), from, to,
                Timestamp.from(cutoff), Timestamp.from(cutoff));
        java.util.Map<LocalDate, java.util.Map<String, Boolean>> byDate =
                new java.util.LinkedHashMap<>();
        for (DatedCalendarFact value : values) {
            Boolean previous = byDate.computeIfAbsent(value.date(), ignored ->
                    new java.util.LinkedHashMap<>()).put(value.exchange(),
                    value.open());
            if (previous != null) {
                return false;
            }
        }
        for (LocalDate candidate = from; !candidate.isAfter(to);
                candidate = candidate.plusDays(1)) {
            java.util.Map<String, Boolean> exchanges = byDate.get(candidate);
            if (exchanges == null || !exchanges.keySet().equals(
                    java.util.Set.of("SSE", "SZSE"))) {
                return false;
            }
            if (Boolean.TRUE.equals(exchanges.get("SSE"))
                    && Boolean.TRUE.equals(exchanges.get("SZSE"))) {
                return true;
            }
        }
        return false;
    }

    private record DatedCalendarFact(
            LocalDate date,
            String exchange,
            boolean open
    ) {
    }

    public boolean claimScheduledDispatch(
            LocalDate tradeDate,
            String requestId,
            Instant researchAsOf
    ) {
        try {
            Integer id = jdbc.queryForObject("""
                    INSERT INTO shadow_scheduler_dispatches (
                        trade_date, research_slot, strategy_version,
                        scheduler_version, request_id, status,
                        research_as_of
                    ) VALUES (?, ?, ?, ?, ?, 'CLAIMED', ?)
                    RETURNING id
                    """, Integer.class, tradeDate,
                    ShadowResearchModels.RESEARCH_SLOT,
                    ShadowResearchModels.STRATEGY_VERSION,
                    ShadowResearchModels.SCHEDULER_VERSION, requestId,
                    Timestamp.from(researchAsOf));
            return id != null;
        } catch (DuplicateKeyException error) {
            return false;
        }
    }

    public void completeScheduledDispatch(
            String requestId,
            boolean submitted,
            String failureCode,
            Instant completedAt
    ) {
        if (submitted == (failureCode != null)
                || !submitted && (failureCode == null
                || !failureCode.matches("[A-Z][A-Z0-9_]{3,127}"))) {
            throw new IllegalArgumentException(
                    "M4_SCHEDULER_TERMINAL_RESULT_INVALID");
        }
        int changed = jdbc.update("""
                UPDATE shadow_scheduler_dispatches
                   SET status=?, failure_code=?, completed_at=?
                 WHERE request_id=? AND status='CLAIMED'
                """, submitted ? "SUBMITTED" : "FAILED", failureCode,
                Timestamp.from(completedAt), requestId);
        if (changed != 1) {
            throw new IllegalStateException(
                    "M4_SCHEDULER_TERMINAL_UPDATE_FAILED");
        }
    }

    public ShadowRun createRun(
            String runKey,
            TriggerMode triggerMode,
            LocalDate tradeDate,
            String slot,
            Instant researchAsOf,
            String strategyVersion,
            String modelProvider,
            String model,
            String promptVersion,
            String agentRuntimeVersion,
            String requestFingerprint
    ) {
        Integer attempt = jdbc.queryForObject("""
                SELECT COALESCE(max(attempt), 0) + 1
                FROM shadow_research_runs WHERE run_key=?
                """, Integer.class, runKey);
        try {
            return jdbc.queryForObject("""
                    INSERT INTO shadow_research_runs (
                        run_key, attempt, run_version, status, trigger_mode,
                        trade_date, research_slot, research_as_of,
                        strategy_version, model_provider, model,
                        prompt_version, agent_runtime_version,
                        request_fingerprint
                    ) VALUES (?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING
                    """ + RUN_COLUMNS, this::mapRun, runKey, attempt,
                    ShadowResearchModels.RUNTIME_VERSION, triggerMode.name(),
                    tradeDate, slot, Timestamp.from(researchAsOf),
                    strategyVersion, modelProvider, model, promptVersion,
                    agentRuntimeVersion, requestFingerprint);
        } catch (DuplicateKeyException error) {
            return slot(tradeDate, slot, strategyVersion)
                    .orElseThrow(() -> error);
        }
    }

    public int interruptStaleRuns(Instant cutoff, Instant completedAt) {
        return jdbc.update("""
                UPDATE shadow_research_runs
                   SET status='INTERRUPTED',
                       error_code='M4_STALE_RUN_RECOVERED',
                       started_at=COALESCE(started_at, created_at),
                       completed_at=?
                 WHERE status IN ('QUEUED','RUNNING') AND created_at<?
                """, Timestamp.from(completedAt), Timestamp.from(cutoff));
    }

    public ShadowRun start(long runId, Instant startedAt) {
        return jdbc.queryForObject("""
                UPDATE shadow_research_runs
                   SET status='RUNNING', started_at=?
                 WHERE id=? AND status='QUEUED'
                RETURNING
                """ + RUN_COLUMNS, this::mapRun,
                Timestamp.from(startedAt), runId);
    }

    public void freezeRun(
            long runId,
            Instant signalTime,
            Instant paperExecutionTime,
            String datasetFingerprint,
            String strategyFingerprint,
            String researchFingerprint,
            Instant completedAt
    ) {
        int changed = jdbc.update("""
                UPDATE shadow_research_runs
                   SET status='FROZEN', signal_time=?, paper_execution_time=?,
                       dataset_fingerprint=?, strategy_fingerprint=?,
                       research_fingerprint=?, completed_at=?
                 WHERE id=? AND status='RUNNING'
                """, Timestamp.from(signalTime),
                paperExecutionTime == null ? null
                        : Timestamp.from(paperExecutionTime),
                datasetFingerprint, strategyFingerprint,
                researchFingerprint, Timestamp.from(completedAt), runId);
        if (changed != 1) {
            throw new IllegalStateException("M4_RUN_FREEZE_FAILED");
        }
    }

    public void fail(long runId, RunStatus status, String code, Instant at) {
        if (status != RunStatus.FAILED && status != RunStatus.INTERRUPTED) {
            throw new IllegalArgumentException("M4_FAILURE_STATUS_INVALID");
        }
        jdbc.update("""
                UPDATE shadow_research_runs
                   SET status=?, error_code=?, completed_at=?,
                       started_at=COALESCE(started_at, ?)
                 WHERE id=? AND status IN ('QUEUED','RUNNING')
                """, status.name(), code, Timestamp.from(at),
                Timestamp.from(at), runId);
    }

    public FrozenSnapshot insertSnapshot(
            long runId,
            ResearchReport report,
            ShadowRecommendation recommendation,
            Instant frozenAt
    ) {
        String reportJson = ShadowResearchCanonical.json(report);
        String recommendationJson = ShadowResearchCanonical.json(
                recommendation);
        String evidenceJson = ShadowResearchCanonical.json(report.evidence());
        String findingsJson = ShadowResearchCanonical.json(report.agentRuns()
                .stream().flatMap(value -> value.findings().stream()).toList());
        String criticJson = ShadowResearchCanonical.json(report.criticReview());
        String limitationsJson = ShadowResearchCanonical.json(
                recommendation.limitations());
        String fingerprint = ShadowResearchCanonical.hash(List.of(
                runId, reportJson, recommendationJson, evidenceJson,
                findingsJson, criticJson, limitationsJson,
                frozenAt.toString()));
        return jdbc.queryForObject("""
                INSERT INTO shadow_research_snapshots (
                    run_id, snapshot_version, report_json,
                    recommendation_json, evidence_json,
                    agent_findings_json, critic_json, limitations_json,
                    snapshot_fingerprint, frozen_at
                ) VALUES (?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                          ?::jsonb, ?::jsonb, ?, ?)
                RETURNING id, run_id, snapshot_fingerprint, frozen_at,
                          report_json::text, recommendation_json::text
                """, (row, number) -> new FrozenSnapshot(
                        row.getLong("id"), row.getLong("run_id"),
                        row.getString("snapshot_fingerprint"),
                        instant(row, "frozen_at"),
                        read(row.getString("report_json"), ResearchReport.class),
                        read(row.getString("recommendation_json"),
                                ShadowRecommendation.class)),
                runId, ShadowResearchModels.SNAPSHOT_VERSION, reportJson,
                recommendationJson, evidenceJson, findingsJson, criticJson,
                limitationsJson, fingerprint, Timestamp.from(frozenAt));
    }

    public Optional<FrozenSnapshot> snapshot(long runId) {
        return jdbc.query("""
                SELECT id, run_id, snapshot_fingerprint, frozen_at,
                       report_json::text, recommendation_json::text
                  FROM shadow_research_snapshots WHERE run_id=?
                """, (row, number) -> new FrozenSnapshot(
                        row.getLong("id"), row.getLong("run_id"),
                        row.getString("snapshot_fingerprint"),
                        instant(row, "frozen_at"),
                        read(row.getString("report_json"), ResearchReport.class),
                        read(row.getString("recommendation_json"),
                                ShadowRecommendation.class)), runId)
                .stream().findFirst();
    }

    public PaperPortfolio lockPortfolio() {
        return mapPortfolio(jdbc.queryForMap("""
                SELECT * FROM shadow_paper_portfolios
                 WHERE portfolio_code=? FOR UPDATE
                """, ShadowResearchModels.PAPER_PORTFOLIO));
    }

    public PaperPortfolio portfolio() {
        return mapPortfolio(jdbc.queryForMap("""
                SELECT * FROM shadow_paper_portfolios
                 WHERE portfolio_code=?
                """, ShadowResearchModels.PAPER_PORTFOLIO));
    }

    public List<PaperPosition> positions(long portfolioId) {
        return jdbc.query("""
                SELECT symbol, exchange, quantity, available_quantity,
                       average_cost, last_price, last_buy_date
                  FROM shadow_paper_positions
                 WHERE portfolio_id=? ORDER BY exchange, symbol
                """, (row, number) -> new PaperPosition(
                        new Security(row.getString("symbol"),
                                row.getString("exchange")),
                        row.getInt("quantity"),
                        row.getInt("available_quantity"),
                        row.getBigDecimal("average_cost"),
                        row.getBigDecimal("last_price"),
                        row.getObject("last_buy_date", LocalDate.class)),
                portfolioId);
    }

    public PaperOrder insertOrder(
            long runId,
            long portfolioId,
            String orderKey,
            Side side,
            Security security,
            Instant signalTime,
            Instant earliestExecutionTime,
            BigDecimal targetWeight
    ) {
        return jdbc.queryForObject("""
                INSERT INTO shadow_paper_orders (
                    run_id, portfolio_id, order_key, side, symbol, exchange,
                    signal_time, earliest_execution_time, target_weight,
                    status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT (order_key) DO UPDATE SET order_key=EXCLUDED.order_key
                RETURNING id, run_id, portfolio_id, order_key, side, symbol,
                          exchange, signal_time, earliest_execution_time,
                          target_weight, status, rejection_reason
                """, this::mapOrder, runId, portfolioId, orderKey,
                side.name(), security.symbol(), security.exchange(),
                Timestamp.from(signalTime), Timestamp.from(earliestExecutionTime),
                targetWeight);
    }

    public List<PaperOrder> pendingOrders(Instant at) {
        return jdbc.query("""
                SELECT id, run_id, portfolio_id, order_key, side, symbol,
                       exchange, signal_time, earliest_execution_time,
                       target_weight, status, rejection_reason
                  FROM shadow_paper_orders
                 WHERE status='PENDING' AND earliest_execution_time<=?
                 ORDER BY id FOR UPDATE SKIP LOCKED
                """, this::mapOrder, Timestamp.from(at));
    }

    public void rejectOrder(long orderId, String reason) {
        jdbc.update("""
                UPDATE shadow_paper_orders
                   SET status='REJECTED', rejection_reason=?
                 WHERE id=? AND status='PENDING'
                """, reason, orderId);
    }

    public int releaseTPlusOne(LocalDate executionDate) {
        return jdbc.update("""
                UPDATE shadow_paper_positions
                   SET available_quantity=quantity,
                       updated_at=clock_timestamp()
                 WHERE last_buy_date<? AND available_quantity<quantity
                """, executionDate);
    }

    public void updatePortfolio(
            long portfolioId,
            BigDecimal cash,
            BigDecimal realizedPnl,
            BigDecimal totalFees,
            long expectedVersion
    ) {
        int changed = jdbc.update("""
                UPDATE shadow_paper_portfolios
                   SET cash=?, realized_pnl=?, total_fees=?,
                       state_version=state_version+1,
                       updated_at=clock_timestamp()
                 WHERE id=? AND state_version=?
                """, cash, realizedPnl, totalFees, portfolioId,
                expectedVersion);
        if (changed != 1) {
            throw new IllegalStateException("M4_PORTFOLIO_VERSION_CONFLICT");
        }
    }

    public void upsertPosition(
            long portfolioId,
            Security security,
            int quantity,
            int availableQuantity,
            BigDecimal averageCost,
            BigDecimal lastPrice,
            LocalDate lastBuyDate
    ) {
        if (quantity == 0) {
            jdbc.update("""
                    DELETE FROM shadow_paper_positions
                     WHERE portfolio_id=? AND symbol=? AND exchange=?
                    """, portfolioId, security.symbol(), security.exchange());
            return;
        }
        jdbc.update("""
                INSERT INTO shadow_paper_positions (
                    portfolio_id, symbol, exchange, quantity,
                    available_quantity, average_cost, last_price,
                    last_buy_date
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (portfolio_id, symbol, exchange) DO UPDATE SET
                    quantity=EXCLUDED.quantity,
                    available_quantity=EXCLUDED.available_quantity,
                    average_cost=EXCLUDED.average_cost,
                    last_price=EXCLUDED.last_price,
                    last_buy_date=EXCLUDED.last_buy_date,
                    updated_at=clock_timestamp()
                """, portfolioId, security.symbol(), security.exchange(),
                quantity, availableQuantity, averageCost, lastPrice,
                lastBuyDate);
    }

    public PaperFill fill(
            PaperOrder order,
            LocalDate executionDate,
            Instant executionTime,
            BigDecimal referencePrice,
            BigDecimal executionPrice,
            int quantity,
            BigDecimal gross,
            BigDecimal commission,
            BigDecimal stampDuty,
            BigDecimal slippage,
            BigDecimal realized,
            BigDecimal cashAfter,
            int positionAfter
    ) {
        String fingerprint = ShadowResearchCanonical.hash(List.of(
                order.orderKey(), executionDate, executionTime,
                referencePrice, executionPrice, quantity, gross,
                commission, stampDuty, slippage, realized,
                cashAfter, positionAfter));
        PaperFill fill = jdbc.queryForObject("""
                INSERT INTO shadow_paper_fills (
                    order_id, portfolio_id, run_id, execution_date,
                    execution_time, reference_price, execution_price,
                    quantity, gross_amount, commission, stamp_duty,
                    slippage_cost, realized_pnl, cash_after,
                    position_after, fill_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, order_id, run_id, execution_date,
                          execution_time, reference_price, execution_price,
                          quantity, gross_amount, commission, stamp_duty,
                          slippage_cost, realized_pnl, cash_after,
                          position_after, fill_fingerprint
                """, (row, number) -> new PaperFill(
                        row.getLong("id"), row.getLong("order_id"),
                        row.getLong("run_id"),
                        row.getObject("execution_date", LocalDate.class),
                        instant(row, "execution_time"), order.security(),
                        order.side(), row.getBigDecimal("reference_price"),
                        row.getBigDecimal("execution_price"),
                        row.getInt("quantity"),
                        row.getBigDecimal("gross_amount"),
                        row.getBigDecimal("commission"),
                        row.getBigDecimal("stamp_duty"),
                        row.getBigDecimal("slippage_cost"),
                        row.getBigDecimal("realized_pnl"),
                        row.getBigDecimal("cash_after"),
                        row.getInt("position_after"),
                        row.getString("fill_fingerprint")),
                order.id(), order.portfolioId(), order.runId(), executionDate,
                Timestamp.from(executionTime), referencePrice, executionPrice,
                quantity, gross, commission, stampDuty, slippage, realized,
                cashAfter, positionAfter, fingerprint);
        jdbc.update("""
                UPDATE shadow_paper_orders SET status='FILLED'
                 WHERE id=? AND status='PENDING'
                """, order.id());
        return fill;
    }

    public PortfolioSnapshot insertPortfolioSnapshot(
            long portfolioId,
            Long runId,
            LocalDate date,
            Instant at,
            BigDecimal cash,
            BigDecimal marketValue,
            BigDecimal equity,
            BigDecimal realized,
            BigDecimal unrealized,
            BigDecimal fees,
            BigDecimal totalReturn,
            int positionCount
    ) {
        String fingerprint = ShadowResearchCanonical.hash(List.of(
                portfolioId, runId == null ? "NONE" : runId,
                date, at, cash, marketValue, equity, realized,
                unrealized, fees, totalReturn, positionCount));
        List<PortfolioSnapshot> inserted = jdbc.query("""
                INSERT INTO shadow_portfolio_snapshots (
                    portfolio_id, run_id, snapshot_date, snapshot_time,
                    cash, market_value, total_equity, realized_pnl,
                    unrealized_pnl, total_fees, total_return,
                    position_count, snapshot_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (snapshot_fingerprint) DO NOTHING
                RETURNING id, portfolio_id, run_id, snapshot_date,
                          snapshot_time, cash, market_value, total_equity,
                          realized_pnl, unrealized_pnl, total_fees,
                          total_return, position_count, snapshot_fingerprint
                """, this::mapPortfolioSnapshot, portfolioId, runId, date,
                Timestamp.from(at), cash, marketValue, equity, realized,
                unrealized, fees, totalReturn, positionCount, fingerprint);
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return jdbc.queryForObject("""
                SELECT id, portfolio_id, run_id, snapshot_date,
                       snapshot_time, cash, market_value, total_equity,
                       realized_pnl, unrealized_pnl, total_fees,
                       total_return, position_count, snapshot_fingerprint
                  FROM shadow_portfolio_snapshots
                 WHERE snapshot_fingerprint=?
                """, this::mapPortfolioSnapshot, fingerprint);
    }

    public List<ShadowRun> runs(int limit) {
        return jdbc.query("SELECT " + RUN_COLUMNS
                        + " FROM shadow_research_runs ORDER BY id DESC LIMIT ?",
                this::mapRun, limit);
    }

    /** Frozen snapshots only; terminal M4 evidence is never rewritten by M5. */
    public List<FrozenSnapshot> frozenSnapshots(int limit) {
        return jdbc.query("""
                SELECT s.id, s.run_id, s.snapshot_fingerprint, s.frozen_at,
                       s.report_json::text, s.recommendation_json::text
                  FROM shadow_research_snapshots s
                  JOIN shadow_research_runs r ON r.id=s.run_id
                 WHERE r.status='FROZEN'
                 ORDER BY s.run_id DESC LIMIT ?
                """, (row, number) -> new FrozenSnapshot(
                row.getLong("id"), row.getLong("run_id"),
                row.getString("snapshot_fingerprint"),
                instant(row, "frozen_at"),
                read(row.getString("report_json"), ResearchReport.class),
                read(row.getString("recommendation_json"),
                        ShadowRecommendation.class)), limit);
    }

    public List<PortfolioSnapshot> portfolioSnapshots(int limit) {
        return jdbc.query("""
                SELECT id, portfolio_id, run_id, snapshot_date,
                       snapshot_time, cash, market_value, total_equity,
                       realized_pnl, unrealized_pnl, total_fees,
                       total_return, position_count, snapshot_fingerprint
                  FROM shadow_portfolio_snapshots
                 ORDER BY id DESC LIMIT ?
                """, this::mapPortfolioSnapshot, limit);
    }

    public List<PortfolioSnapshot> portfolioSnapshotsForRun(long runId) {
        return jdbc.query("""
                SELECT id, portfolio_id, run_id, snapshot_date,
                       snapshot_time, cash, market_value, total_equity,
                       realized_pnl, unrealized_pnl, total_fees,
                       total_return, position_count, snapshot_fingerprint
                  FROM shadow_portfolio_snapshots
                 WHERE run_id=? ORDER BY snapshot_time, id
                """, this::mapPortfolioSnapshot, runId);
    }

    public Optional<PortfolioSnapshot> latestPortfolioSnapshot() {
        return jdbc.query("""
                SELECT id, portfolio_id, run_id, snapshot_date,
                       snapshot_time, cash, market_value, total_equity,
                       realized_pnl, unrealized_pnl, total_fees,
                       total_return, position_count, snapshot_fingerprint
                  FROM shadow_portfolio_snapshots ORDER BY id DESC LIMIT 1
                """, this::mapPortfolioSnapshot).stream().findFirst();
    }

    public Optional<PortfolioSnapshot> portfolioSnapshot(long runId) {
        return jdbc.query("""
                SELECT id, portfolio_id, run_id, snapshot_date,
                       snapshot_time, cash, market_value, total_equity,
                       realized_pnl, unrealized_pnl, total_fees,
                       total_return, position_count, snapshot_fingerprint
                 FROM shadow_portfolio_snapshots
                 WHERE run_id=? ORDER BY snapshot_time DESC, id DESC LIMIT 1
                """, this::mapPortfolioSnapshot, runId).stream().findFirst();
    }

    public List<PaperOrder> orders(long runId) {
        return jdbc.query("""
                SELECT id, run_id, portfolio_id, order_key, side, symbol,
                       exchange, signal_time, earliest_execution_time,
                       target_weight, status, rejection_reason
                  FROM shadow_paper_orders WHERE run_id=? ORDER BY id
                """, this::mapOrder, runId);
    }

    public List<PaperFill> fills(long runId) {
        return jdbc.query("""
                SELECT f.id, f.order_id, f.run_id, f.execution_date,
                       f.execution_time, f.reference_price, f.execution_price,
                       f.quantity, f.gross_amount, f.commission, f.stamp_duty,
                       f.slippage_cost, f.realized_pnl, f.cash_after,
                       f.position_after, f.fill_fingerprint,
                       o.symbol, o.exchange, o.side
                  FROM shadow_paper_fills f
                  JOIN shadow_paper_orders o ON o.id=f.order_id
                 WHERE f.run_id=? ORDER BY f.id
                """, (row, number) -> new PaperFill(
                        row.getLong("id"), row.getLong("order_id"),
                        row.getLong("run_id"),
                        row.getObject("execution_date", LocalDate.class),
                        instant(row, "execution_time"),
                        new Security(row.getString("symbol"),
                                row.getString("exchange")),
                        Side.valueOf(row.getString("side")),
                        row.getBigDecimal("reference_price"),
                        row.getBigDecimal("execution_price"),
                        row.getInt("quantity"),
                        row.getBigDecimal("gross_amount"),
                        row.getBigDecimal("commission"),
                        row.getBigDecimal("stamp_duty"),
                        row.getBigDecimal("slippage_cost"),
                        row.getBigDecimal("realized_pnl"),
                        row.getBigDecimal("cash_after"),
                        row.getInt("position_after"),
                                row.getString("fill_fingerprint")), runId);
    }

    /** Read-only first Paper lifecycle originating from one frozen run. */
    public List<PaperFill> paperLifecycleFills(long entryRunId) {
        return jdbc.query("""
                WITH entry AS (
                    SELECT o.portfolio_id, o.symbol, o.exchange,
                           min(f.id) AS entry_fill_id
                      FROM shadow_paper_fills f
                      JOIN shadow_paper_orders o ON o.id=f.order_id
                     WHERE f.run_id=? AND o.side='BUY'
                     GROUP BY o.portfolio_id, o.symbol, o.exchange
                ), bounded AS (
                    SELECT entry.*,
                           (SELECT min(future.id)
                              FROM shadow_paper_fills future
                              JOIN shadow_paper_orders future_order
                                ON future_order.id=future.order_id
                             WHERE future_order.portfolio_id=
                                       entry.portfolio_id
                               AND future_order.symbol=entry.symbol
                               AND future_order.exchange=entry.exchange
                               AND future_order.side='BUY'
                               AND future.id>entry.entry_fill_id
                           ) AS next_entry_fill_id
                      FROM entry
                )
                SELECT f.id, f.order_id, f.run_id, f.execution_date,
                       f.execution_time, f.reference_price, f.execution_price,
                       f.quantity, f.gross_amount, f.commission, f.stamp_duty,
                       f.slippage_cost, f.realized_pnl, f.cash_after,
                       f.position_after, f.fill_fingerprint,
                       o.symbol, o.exchange, o.side
                  FROM bounded lifecycle
                  JOIN shadow_paper_orders o
                    ON o.portfolio_id=lifecycle.portfolio_id
                   AND o.symbol=lifecycle.symbol
                   AND o.exchange=lifecycle.exchange
                  JOIN shadow_paper_fills f ON f.order_id=o.id
                 WHERE f.id>=lifecycle.entry_fill_id
                   AND (lifecycle.next_entry_fill_id IS NULL
                        OR f.id<lifecycle.next_entry_fill_id)
                 ORDER BY f.execution_time, f.id
                """, (row, number) -> new PaperFill(
                row.getLong("id"), row.getLong("order_id"),
                row.getLong("run_id"),
                row.getObject("execution_date", LocalDate.class),
                instant(row, "execution_time"),
                new Security(row.getString("symbol"),
                        row.getString("exchange")),
                Side.valueOf(row.getString("side")),
                row.getBigDecimal("reference_price"),
                row.getBigDecimal("execution_price"),
                row.getInt("quantity"), row.getBigDecimal("gross_amount"),
                row.getBigDecimal("commission"),
                row.getBigDecimal("stamp_duty"),
                row.getBigDecimal("slippage_cost"),
                row.getBigDecimal("realized_pnl"),
                row.getBigDecimal("cash_after"),
                row.getInt("position_after"),
                row.getString("fill_fingerprint")), entryRunId);
    }

    public ShadowOutcome insertOutcome(
            long runId,
            String horizonCode,
            LocalDate evaluationDate,
            OutcomeObservation observation,
            Instant evaluatedAt
    ) {
        String json = ShadowResearchCanonical.json(observation);
        String fingerprint = ShadowResearchCanonical.hash(List.of(
                runId, horizonCode, evaluationDate, json, evaluatedAt));
        List<ShadowOutcome> inserted = jdbc.query("""
                INSERT INTO shadow_outcomes (
                    run_id, horizon_code, evaluation_date, outcome_json,
                    outcome_fingerprint, evaluated_at
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (run_id, horizon_code) DO NOTHING
                RETURNING id, run_id, horizon_code, evaluation_date,
                          outcome_json::text, outcome_fingerprint,
                          evaluated_at
                """, this::mapOutcome, runId, horizonCode, evaluationDate,
                json, fingerprint, Timestamp.from(evaluatedAt));
        ShadowOutcome value = inserted.isEmpty()
                ? jdbc.queryForObject("""
                    SELECT id, run_id, horizon_code, evaluation_date,
                           outcome_json::text, outcome_fingerprint,
                           evaluated_at
                      FROM shadow_outcomes
                     WHERE run_id=? AND horizon_code=?
                    """, this::mapOutcome, runId, horizonCode)
                : inserted.get(0);
        if (!value.evaluationDate().equals(evaluationDate)
                || !value.observation().equals(observation)) {
            throw new IllegalStateException("M4_OUTCOME_CONFLICT");
        }
        return value;
    }

    public List<ShadowOutcome> outcomes(long runId) {
        return jdbc.query("""
                SELECT id, run_id, horizon_code, evaluation_date,
                       outcome_json::text, outcome_fingerprint, evaluated_at
                  FROM shadow_outcomes WHERE run_id=?
                 ORDER BY CASE horizon_code WHEN 'D1' THEN 1
                          WHEN 'D5' THEN 5 ELSE 20 END
                """, this::mapOutcome, runId);
    }

    private PaperPortfolio mapPortfolio(java.util.Map<String, Object> row) {
        long id = ((Number) row.get("id")).longValue();
        return new PaperPortfolio(id, String.valueOf(row.get("portfolio_code")),
                decimal(row.get("initial_cash")), decimal(row.get("cash")),
                decimal(row.get("realized_pnl")),
                decimal(row.get("total_fees")),
                ((Number) row.get("state_version")).longValue(),
                positions(id));
    }

    private ShadowRun mapRun(ResultSet row, int ignored) throws SQLException {
        return new ShadowRun(row.getLong("id"), row.getString("run_key"),
                row.getInt("attempt"),
                RunStatus.valueOf(row.getString("status")),
                TriggerMode.valueOf(row.getString("trigger_mode")),
                row.getObject("trade_date", LocalDate.class),
                row.getString("research_slot"), instant(row, "research_as_of"),
                nullableInstant(row, "signal_time"),
                nullableInstant(row, "paper_execution_time"),
                row.getString("strategy_version"),
                row.getString("model_provider"), row.getString("model"),
                row.getString("prompt_version"),
                row.getString("agent_runtime_version"),
                row.getString("dataset_fingerprint"),
                row.getString("strategy_fingerprint"),
                row.getString("research_fingerprint"),
                row.getString("request_fingerprint"),
                row.getString("error_code"), nullableInstant(row, "started_at"),
                nullableInstant(row, "completed_at"), instant(row, "created_at"));
    }

    private PaperOrder mapOrder(ResultSet row, int ignored) throws SQLException {
        return new PaperOrder(row.getLong("id"), row.getLong("run_id"),
                row.getLong("portfolio_id"), row.getString("order_key"),
                Side.valueOf(row.getString("side")),
                new Security(row.getString("symbol"),
                        row.getString("exchange")),
                instant(row, "signal_time"),
                instant(row, "earliest_execution_time"),
                row.getBigDecimal("target_weight"),
                PaperOrderStatus.valueOf(row.getString("status")),
                row.getString("rejection_reason"));
    }

    private PortfolioSnapshot mapPortfolioSnapshot(ResultSet row, int ignored)
            throws SQLException {
        long rawRun = row.getLong("run_id");
        Long runId = row.wasNull() ? null : rawRun;
        return new PortfolioSnapshot(row.getLong("id"),
                row.getLong("portfolio_id"), runId,
                row.getObject("snapshot_date", LocalDate.class),
                instant(row, "snapshot_time"), row.getBigDecimal("cash"),
                row.getBigDecimal("market_value"),
                row.getBigDecimal("total_equity"),
                row.getBigDecimal("realized_pnl"),
                row.getBigDecimal("unrealized_pnl"),
                row.getBigDecimal("total_fees"),
                row.getBigDecimal("total_return"),
                row.getInt("position_count"),
                row.getString("snapshot_fingerprint"));
    }

    private ShadowOutcome mapOutcome(ResultSet row, int ignored)
            throws SQLException {
        return new ShadowOutcome(row.getLong("id"), row.getLong("run_id"),
                row.getString("horizon_code"),
                row.getObject("evaluation_date", LocalDate.class),
                read(row.getString("outcome_json"),
                        OutcomeObservation.class),
                row.getString("outcome_fingerprint"),
                instant(row, "evaluated_at"));
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("M4_SNAPSHOT_JSON_INVALID", error);
        }
    }

    private static Instant instant(ResultSet row, String name)
            throws SQLException {
        OffsetDateTime value = row.getObject(name, OffsetDateTime.class);
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String name)
            throws SQLException {
        OffsetDateTime value = row.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal : new BigDecimal(String.valueOf(value));
    }
}
