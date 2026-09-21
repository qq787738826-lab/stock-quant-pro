package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Append-only, calendar-only extension after the current formal maximum. */
final class MainboardTradeCalendarForwardIncrementService {
    static final int MAXIMUM_RANGE_DAYS = 500;
    static final int MAXIMUM_PROVIDER_REQUESTS = 4;
    static final int MAXIMUM_NETWORK_RECOVERIES = 2;
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(90);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 0);

    private final JdbcTemplate jdbc;
    private final PitMarketFactRepository facts;
    private final ResearchUniverseMainboardRepository universes;
    private final TushareMainboardUniverseCaptureService capture;
    private final Clock clock;

    MainboardTradeCalendarForwardIncrementService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            TushareMainboardUniverseCaptureService capture,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.facts = new PitMarketFactRepository(jdbc,
                Objects.requireNonNull(mapper, "mapper"));
        this.universes = new ResearchUniverseMainboardRepository(jdbc);
        this.capture = Objects.requireNonNull(capture, "capture");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Outcome execute(
            LocalDate targetEndDate,
            int maximumProviderRequests,
            int networkRecoveryBudget,
            String gitCommit,
            Progress progress
    ) {
        validateArguments(targetEndDate, maximumProviderRequests,
                networkRecoveryBudget, gitCommit, progress);
        Instant startedAt = clock.instant();
        ZonedDateTime marketNow = startedAt.atZone(MARKET_ZONE);
        if (targetEndDate.isAfter(marketNow.toLocalDate())) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_TARGET_FUTURE");
        }
        SnapshotBundle beforeSnapshot = universes.latest().orElseThrow(() ->
                invalid("MAINBOARD_TRADE_CAL_FORWARD_UNIVERSE_MISSING"));
        requireUniverse(beforeSnapshot);
        requireNoActiveBusinessRun();
        Counts immutableBefore = businessCounts();

        TradingCalendarObservation currentSse = latest("SSE", startedAt);
        TradingCalendarObservation currentSzse = latest("SZSE", startedAt);
        requireCurrentMaximum(currentSse, "SSE", startedAt);
        requireCurrentMaximum(currentSzse, "SZSE", startedAt);
        LocalDate currentSseMax = currentSse.calendarDate();
        LocalDate currentSzseMax = currentSzse.calendarDate();
        if (!currentSseMax.equals(currentSzseMax)) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_CURRENT_MAX_MISMATCH");
        }
        LocalDate currentMax = currentSseMax;
        LocalDate startDate = currentMax.plusDays(1);
        LocalDate completedBoundary = completedBoundary(marketNow);
        CalendarAudit before = auditCompleted(currentMax, completedBoundary,
                startedAt);
        if (targetEndDate.compareTo(currentMax) <= 0) {
            requireUnchanged(beforeSnapshot, immutableBefore);
            return new Outcome("NO_OP", beforeSnapshot, currentSseMax,
                    currentSzseMax, startDate, targetEndDate, currentSseMax,
                    currentSzseMax, before.latestCommonCompletedOpenDate(),
                    0, 0, Map.of(), List.of(), 0, 0, 0, true, true,
                    true, true, true, true, startedAt);
        }
        long rangeDays = ChronoUnit.DAYS.between(startDate,
                targetEndDate) + 1;
        if (rangeDays < 1 || rangeDays > MAXIMUM_RANGE_DAYS) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_RANGE_INVALID");
        }

        MaxIdentity sseIdentity = MaxIdentity.from(currentSse);
        MaxIdentity szseIdentity = MaxIdentity.from(currentSzse);
        try {
            var evidence = capture.captureForwardCalendars(beforeSnapshot,
                    startDate, targetEndDate, gitCommit, PROVIDER_TIMEOUT,
                    networkRecoveryBudget);
            progress.accept(evidence);
        } catch (TushareMainboardUniverseCaptureService.CaptureFailure failure) {
            progress.accept(failure);
            throw failure;
        }
        validateProviderAccounting(progress, maximumProviderRequests,
                networkRecoveryBudget);

        Instant completedAt = clock.instant();
        SnapshotBundle afterSnapshot = universes.latest().orElseThrow();
        List<TradingCalendarObservation> sse = range("SSE", startDate,
                targetEndDate, completedAt);
        List<TradingCalendarObservation> szse = range("SZSE", startDate,
                targetEndDate, completedAt);
        boolean coverage = exactNaturalDateCoverage(sse, startDate,
                targetEndDate) && exactNaturalDateCoverage(szse, startDate,
                targetEndDate);
        int duplicateCount = physicalDuplicateCount(startDate,
                targetEndDate);
        boolean knownAt = all(sse, szse).stream().allMatch(value ->
                !value.envelope().knownAt().isAfter(completedAt)
                        && !value.envelope().knownAt().isBefore(startedAt));
        boolean firstObservedAt = all(sse, szse).stream().allMatch(value ->
                !value.envelope().firstObservedAt().isBefore(startedAt)
                        && !value.envelope().firstObservedAt().isAfter(
                        value.envelope().knownAt()));
        boolean lineage = all(sse, szse).stream().allMatch(
                MainboardTradeCalendarForwardIncrementService::validLineage);
        boolean providerFields = all(sse, szse).stream().allMatch(
                MainboardTradeCalendarForwardIncrementService
                        ::validProviderFields);
        TradingCalendarObservation finalSse = latest("SSE", completedAt);
        TradingCalendarObservation finalSzse = latest("SZSE", completedAt);
        boolean existingUnchanged = sseIdentity.equals(MaxIdentity.from(
                findOnDate("SSE", currentMax, completedAt)))
                && szseIdentity.equals(MaxIdentity.from(findOnDate("SZSE",
                currentMax, completedAt)));
        int expectedAppended = Math.toIntExact(rangeDays * 2L);
        if (!coverage || duplicateCount != 0
                || !finalSse.calendarDate().equals(targetEndDate)
                || !finalSzse.calendarDate().equals(targetEndDate)
                || !knownAt || !firstObservedAt || !lineage
                || !providerFields || !existingUnchanged
                || progress.appended != expectedAppended
                || progress.idempotent != 0) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_COVERAGE_INVALID");
        }
        CalendarAudit after = auditCompleted(targetEndDate,
                completedBoundary, completedAt);
        requireUnchanged(beforeSnapshot, immutableBefore);
        if (afterSnapshot.snapshot().databaseId()
                != beforeSnapshot.snapshot().databaseId()
                || !afterSnapshot.snapshot().memberFingerprint().equals(
                beforeSnapshot.snapshot().memberFingerprint())) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_UNIVERSE_CHANGED");
        }
        return new Outcome("APPENDED", afterSnapshot, currentSseMax,
                currentSzseMax, startDate, targetEndDate,
                finalSse.calendarDate(), finalSzse.calendarDate(),
                after.latestCommonCompletedOpenDate(),
                Math.toIntExact(rangeDays), duplicateCount,
                progress.calendarCallCountsByExchange, progress.batchIds,
                progress.appended, progress.idempotent, progress.providerCalls,
                coverage, knownAt, firstObservedAt, lineage, providerFields,
                existingUnchanged, completedAt);
    }

    private TradingCalendarObservation latest(String exchange,
                                               Instant cutoff) {
        return facts.findLatestCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity(exchange),
                exchange, cutoff).orElseThrow(() -> invalid(
                "MAINBOARD_TRADE_CAL_FORWARD_CURRENT_MAX_MISSING"));
    }

    private TradingCalendarObservation findOnDate(String exchange,
                                                   LocalDate date,
                                                   Instant cutoff) {
        List<TradingCalendarObservation> values = range(exchange, date, date,
                cutoff);
        if (values.size() != 1) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_EXISTING_FACT_CHANGED");
        }
        return values.get(0);
    }

    private List<TradingCalendarObservation> range(String exchange,
                                                   LocalDate start,
                                                   LocalDate end,
                                                   Instant cutoff) {
        return facts.findCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity(exchange),
                exchange, start, end, cutoff);
    }

    private CalendarAudit auditCompleted(LocalDate calendarMax,
                                         LocalDate completedBoundary,
                                         Instant cutoff) {
        LocalDate effective = calendarMax.isBefore(completedBoundary)
                ? calendarMax : completedBoundary;
        LocalDate start = effective.minusDays(MAXIMUM_RANGE_DAYS - 1L);
        Set<LocalDate> sse = openDates(range("SSE", start, effective,
                cutoff));
        Set<LocalDate> szse = openDates(range("SZSE", start, effective,
                cutoff));
        LinkedHashSet<LocalDate> common = new LinkedHashSet<>(sse);
        common.retainAll(szse);
        List<LocalDate> ordered = common.stream().sorted().toList();
        if (ordered.isEmpty()) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_COMMON_OPEN_EMPTY");
        }
        return new CalendarAudit(ordered,
                ordered.get(ordered.size() - 1));
    }

    private int physicalDuplicateCount(LocalDate start, LocalDate end) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(sum(duplicate_count), 0)::integer
                  FROM (
                    SELECT count(*) - 1 AS duplicate_count
                      FROM pit_market_fact_observations o
                      JOIN pit_market_fact_batches b ON b.id=o.batch_id
                       AND b.response_complete
                      JOIN trading_calendar_facts_v1 c
                        ON c.observation_id=o.id
                     WHERE o.fact_type='TRADING_CALENDAR'
                       AND o.source_code=?
                       AND o.source_instrument_id IN (?, ?)
                       AND c.calendar_date BETWEEN ? AND ?
                     GROUP BY o.source_instrument_id, c.exchange,
                              c.calendar_date
                    HAVING count(*) > 1
                  ) duplicates
                """, Integer.class, TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity("SSE"),
                TushareMarketFactProvider.calendarSourceIdentity("SZSE"),
                start, end);
        return Objects.requireNonNullElse(value, 0);
    }

    private static boolean exactNaturalDateCoverage(
            List<TradingCalendarObservation> values,
            LocalDate start,
            LocalDate end
    ) {
        long expected = ChronoUnit.DAYS.between(start, end) + 1;
        if (values.size() != expected) return false;
        LocalDate cursor = start;
        for (TradingCalendarObservation value : values) {
            if (!cursor.equals(value.calendarDate())) return false;
            cursor = cursor.plusDays(1);
        }
        return cursor.equals(end.plusDays(1));
    }

    private static boolean validLineage(TradingCalendarObservation value) {
        return value.envelope().factType() == FactType.TRADING_CALENDAR
                && TushareMarketFactProvider.PROVIDER_CODE.equals(
                value.envelope().sourceCode())
                && TushareMarketFactProvider.calendarSourceIdentity(
                value.exchange()).equals(
                value.envelope().sourceInstrumentId())
                && ("TRADING_CALENDAR|" + value.exchange() + "|"
                + value.calendarDate()).equals(value.envelope().naturalKey())
                && value.envelope().historicalReplayAllowed()
                && value.envelope().backtestAllowed()
                && value.envelope().agentUseAllowed();
    }

    private static boolean validProviderFields(
            TradingCalendarObservation value
    ) {
        JsonNode row = value.envelope().rawPayload().path("providerRow");
        String expectedDate = value.calendarDate().format(
                DateTimeFormatter.BASIC_ISO_DATE);
        return "trade_cal".equals(
                value.envelope().rawPayload().path("endpoint").asText())
                && expectedDate.equals(row.path("cal_date").asText())
                && row.has("is_open")
                && row.path("is_open").asInt(-1)
                == (value.open() ? 1 : 0)
                && row.has("pretrade_date")
                && !row.path("pretrade_date").isNull()
                && !row.path("pretrade_date").asText().isBlank();
    }

    private static List<TradingCalendarObservation> all(
            List<TradingCalendarObservation> first,
            List<TradingCalendarObservation> second
    ) {
        List<TradingCalendarObservation> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static Set<LocalDate> openDates(
            List<TradingCalendarObservation> values
    ) {
        LinkedHashSet<LocalDate> result = new LinkedHashSet<>();
        values.stream().filter(TradingCalendarObservation::open)
                .map(TradingCalendarObservation::calendarDate)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    private static LocalDate completedBoundary(ZonedDateTime marketNow) {
        return marketNow.toLocalTime().isBefore(MARKET_CLOSE)
                ? marketNow.toLocalDate().minusDays(1)
                : marketNow.toLocalDate();
    }

    private static void requireCurrentMaximum(
            TradingCalendarObservation value,
            String exchange,
            Instant cutoff
    ) {
        if (!exchange.equals(value.exchange())
                || value.envelope().knownAt().isAfter(cutoff)
                || value.envelope().firstObservedAt().isAfter(
                value.envelope().knownAt())
                || !validLineage(value) || !validProviderFields(value)) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_CURRENT_MAX_INVALID");
        }
    }

    private static void validateProviderAccounting(
            Progress progress,
            int maximumProviderRequests,
            int networkRecoveryBudget
    ) {
        int tradeCalendarCalls = progress.endpointCallCounts.getOrDefault(
                "trade_cal", 0);
        int exchangeCalls = progress.calendarCallCountsByExchange.values()
                .stream().mapToInt(Integer::intValue).sum();
        if (!progress.endpointCallCounts.keySet().equals(Set.of("trade_cal"))
                || !progress.calendarCallCountsByExchange.keySet().equals(
                Set.of("SSE", "SZSE"))
                || progress.calendarCallCountsByExchange.values().stream()
                .anyMatch(value -> value < 1 || value > 3)
                || progress.providerCalls != 2 + progress.retryCount
                || progress.providerCalls != tradeCalendarCalls
                || progress.providerCalls != exchangeCalls
                || progress.providerCalls > maximumProviderRequests
                || progress.retryCount < 0
                || progress.retryCount > networkRecoveryBudget) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_BUDGET_MISMATCH");
        }
    }

    private void requireNoActiveBusinessRun() {
        Integer selection = jdbc.queryForObject("""
                SELECT count(*) FROM research_selection_runs
                 WHERE status NOT IN ('COMPLETED','FAILED')
                """, Integer.class);
        Integer shadow = jdbc.queryForObject("""
                SELECT count(*) FROM shadow_research_runs
                 WHERE status IN ('QUEUED','RUNNING')
                """, Integer.class);
        if (!Objects.equals(selection, 0) || !Objects.equals(shadow, 0)) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_BUSINESS_RUN_ACTIVE");
        }
    }

    private Counts businessCounts() {
        return new Counts(count("research_selection_runs"),
                count("shadow_research_runs"),
                count("shadow_paper_portfolios")
                        + count("shadow_paper_positions")
                        + count("shadow_paper_orders")
                        + count("shadow_paper_fills"),
                count("agent_evaluation_versions")
                        + count("agent_evaluation_reports")
                        + count("agent_evaluation_decisions"));
    }

    private void requireUnchanged(SnapshotBundle snapshot, Counts before) {
        SnapshotBundle after = universes.latest().orElseThrow();
        if (after.snapshot().databaseId() != snapshot.snapshot().databaseId()
                || !after.snapshot().memberFingerprint().equals(
                snapshot.snapshot().memberFingerprint())
                || !businessCounts().equals(before)) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_SCOPE_MUTATION");
        }
    }

    private long count(String table) {
        Long value = jdbc.queryForObject("SELECT count(*) FROM " + table,
                Long.class);
        return Objects.requireNonNullElse(value, 0L);
    }

    private static void requireUniverse(SnapshotBundle snapshot) {
        if (!ResearchUniverseMainboard.VERSION.equals(
                snapshot.snapshot().universeVersion())
                || snapshot.snapshot().memberCount()
                < ResearchUniverseMainboard.MINIMUM_PLAUSIBLE_MEMBER_COUNT
                || snapshot.members().size()
                != snapshot.snapshot().memberCount()) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_UNIVERSE_INVALID");
        }
    }

    private static void validateArguments(
            LocalDate targetEndDate,
            int maximumProviderRequests,
            int networkRecoveryBudget,
            String gitCommit,
            Progress progress
    ) {
        if (targetEndDate == null
                || maximumProviderRequests != MAXIMUM_PROVIDER_REQUESTS
                || networkRecoveryBudget != MAXIMUM_NETWORK_RECOVERIES
                || gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")
                || progress == null) {
            throw invalid("MAINBOARD_TRADE_CAL_FORWARD_ARGUMENTS_INVALID");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    record Outcome(
            String action,
            SnapshotBundle snapshot,
            LocalDate currentSseMaxCalDate,
            LocalDate currentSzseMaxCalDate,
            LocalDate startDate,
            LocalDate targetEndDate,
            LocalDate finalSseMaxCalDate,
            LocalDate finalSzseMaxCalDate,
            LocalDate latestCommonCompletedOpenTradeDate,
            int rangeCalendarDateCount,
            int duplicateCount,
            Map<String, Integer> calendarCallCountsByExchange,
            List<Long> batchIds,
            int appended,
            int idempotent,
            int providerCalls,
            boolean continuousCoverage,
            boolean knownAtValid,
            boolean firstObservedAtValid,
            boolean lineageValid,
            boolean providerFieldsPreserved,
            boolean existingFactsUnchanged,
            Instant completedAt
    ) {
        Outcome {
            calendarCallCountsByExchange = Map.copyOf(
                    calendarCallCountsByExchange);
            batchIds = List.copyOf(batchIds);
        }
    }

    record CalendarAudit(List<LocalDate> commonOpenDates,
                         LocalDate latestCommonCompletedOpenDate) {
        CalendarAudit {
            commonOpenDates = List.copyOf(commonOpenDates);
        }
    }

    private record Counts(long selections, long shadows, long paperRows,
                          long evaluationRows) {
    }

    private record MaxIdentity(long observationId, String contentHash,
                               Instant firstObservedAt, Instant knownAt) {
        static MaxIdentity from(TradingCalendarObservation value) {
            return new MaxIdentity(value.envelope().id(),
                    value.envelope().canonicalContentHash(),
                    value.envelope().firstObservedAt(),
                    value.envelope().knownAt());
        }
    }

    static final class Progress {
        int providerCalls;
        int retryCount;
        Map<String, Integer> endpointCallCounts = Map.of();
        Map<String, Integer> calendarCallCountsByExchange = Map.of();
        List<Long> batchIds = List.of();
        int appended;
        int idempotent;

        void accept(TushareMainboardUniverseCaptureService.CaptureEvidence e) {
            providerCalls = e.providerCallCount();
            retryCount = e.retryCount();
            endpointCallCounts = e.endpointCallCounts();
            calendarCallCountsByExchange =
                    e.calendarCallCountsByExchange();
            batchIds = e.batchIds();
            appended = e.appendedObservations();
            idempotent = e.idempotentChainTailHits();
        }

        void accept(TushareMainboardUniverseCaptureService.CaptureFailure e) {
            providerCalls = e.providerCallCount();
            retryCount = e.retryCount();
            endpointCallCounts = e.endpointCallCounts();
            calendarCallCountsByExchange =
                    e.calendarCallCountsByExchange();
            batchIds = e.batchIds();
            appended = e.appendedObservations();
            idempotent = e.idempotentChainTailHits();
        }
    }
}
