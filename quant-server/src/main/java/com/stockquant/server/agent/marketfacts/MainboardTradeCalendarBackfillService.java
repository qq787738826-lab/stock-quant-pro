package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Data-only range capture that makes the production 250-session calendar resolvable. */
final class MainboardTradeCalendarBackfillService {
    static final int TARGET_SESSIONS = 250;
    static final int MINIMUM_COMMON_OPEN_SESSIONS = 260;
    /** Inclusive range is exactly the existing 500-natural-day limit. */
    static final int CALENDAR_LOOKBACK_DAYS = 499;
    static final int MAXIMUM_PROVIDER_REQUESTS = 4;
    static final int MAXIMUM_NETWORK_RECOVERIES = 2;
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(90);

    private final JdbcTemplate jdbc;
    private final PitMarketFactRepository facts;
    private final ResearchUniverseMainboardRepository universes;
    private final TushareMainboardUniverseCaptureService capture;
    private final Clock clock;

    MainboardTradeCalendarBackfillService(
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
            LocalDate anchorTradeDate,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            int maximumProviderRequests,
            int networkRecoveryBudget,
            String gitCommit,
            Progress progress
    ) {
        validateArguments(anchorTradeDate, rangeStart, rangeEnd,
                maximumProviderRequests, networkRecoveryBudget, gitCommit,
                progress);
        Instant startedAt = clock.instant();
        SnapshotBundle beforeSnapshot = universes.latest().orElseThrow(() ->
                invalid("MAINBOARD_TRADE_CAL_BACKFILL_UNIVERSE_MISSING"));
        requireUniverse(beforeSnapshot);
        requireNoActiveBusinessRun();
        Counts immutableBefore = businessCounts();
        CalendarAudit before = audit(rangeStart, rangeEnd, startedAt,
                startedAt, Set.of(), Set.of());
        if (before.commonOpenDates().isEmpty()
                || !before.latestCommonOpenDate().equals(anchorTradeDate)) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_ANCHOR_INVALID");
        }

        if (before.commonOpenDates().size()
                >= MINIMUM_COMMON_OPEN_SESSIONS) {
            List<LocalDate> target = target250(before.commonOpenDates());
            requireUnchanged(beforeSnapshot, immutableBefore);
            return new Outcome(beforeSnapshot, rangeStart, rangeEnd, before,
                    before, target, 0, 0, Map.of(), List.of(), 0, 0,
                    startedAt);
        }

        Set<LocalDate> priorSseDates = before.sseCalendarDates();
        Set<LocalDate> priorSzseDates = before.szseCalendarDates();
        try {
            var evidence = capture.capture(beforeSnapshot, false, Set.of(),
                    rangeStart, rangeEnd, true, gitCommit, PROVIDER_TIMEOUT,
                    networkRecoveryBudget);
            progress.providerCalls = evidence.providerCallCount();
            progress.retryCount = evidence.retryCount();
            progress.endpointCallCounts = evidence.endpointCallCounts();
            progress.calendarCallCountsByExchange =
                    evidence.calendarCallCountsByExchange();
            progress.batchIds = evidence.batchIds();
            progress.appended = evidence.appendedObservations();
            progress.idempotent = evidence.idempotentChainTailHits();
        } catch (TushareMainboardUniverseCaptureService.CaptureFailure failure) {
            progress.providerCalls = failure.providerCallCount();
            progress.retryCount = failure.retryCount();
            progress.endpointCallCounts = failure.endpointCallCounts();
            progress.calendarCallCountsByExchange =
                    failure.calendarCallCountsByExchange();
            progress.batchIds = failure.batchIds();
            progress.appended = failure.appendedObservations();
            progress.idempotent = failure.idempotentChainTailHits();
            throw failure;
        }
        validateProviderAccounting(progress, maximumProviderRequests,
                networkRecoveryBudget);

        Instant completedAt = clock.instant();
        SnapshotBundle afterSnapshot = universes.latest().orElseThrow();
        CalendarAudit after = audit(rangeStart, rangeEnd, completedAt,
                startedAt, priorSseDates, priorSzseDates);
        if (after.duplicateCount() != 0
                || after.sseCalendarDates().size() == 0
                || after.szseCalendarDates().size() == 0
                || after.commonOpenDates().size()
                < MINIMUM_COMMON_OPEN_SESSIONS
                || !after.latestCommonOpenDate().equals(anchorTradeDate)
                || !after.knownAtValid()
                || !after.firstObservedAtValid()
                || !after.lineageValid()) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_COVERAGE_INVALID");
        }
        List<LocalDate> productionCommon =
                new ResearchUniverseMainboardDatasetLoader(facts)
                        .commonOpenDatesThrough(anchorTradeDate, completedAt)
                        .stream()
                        .filter(date -> !date.isBefore(rangeStart))
                        .toList();
        if (!productionCommon.equals(after.commonOpenDates())) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_PRODUCTION_RULE_MISMATCH");
        }
        List<LocalDate> target = target250(productionCommon);
        requireUnchanged(beforeSnapshot, immutableBefore);
        if (afterSnapshot.snapshot().databaseId()
                != beforeSnapshot.snapshot().databaseId()
                || !afterSnapshot.snapshot().memberFingerprint().equals(
                beforeSnapshot.snapshot().memberFingerprint())) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_UNIVERSE_CHANGED");
        }
        return new Outcome(afterSnapshot, rangeStart, rangeEnd, before, after,
                target, progress.providerCalls, progress.retryCount,
                progress.calendarCallCountsByExchange, progress.batchIds,
                progress.appended, progress.idempotent, completedAt);
    }

    private CalendarAudit audit(
            LocalDate from,
            LocalDate to,
            Instant cutoff,
            Instant startedAt,
            Set<LocalDate> priorSseDates,
            Set<LocalDate> priorSzseDates
    ) {
        List<TradingCalendarObservation> sse = facts.findCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity("SSE"),
                "SSE", from, to, cutoff);
        List<TradingCalendarObservation> szse = facts.findCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity("SZSE"),
                "SZSE", from, to, cutoff);
        int duplicates = duplicateCount(sse) + duplicateCount(szse);
        Set<LocalDate> sseDates = calendarDates(sse);
        Set<LocalDate> szseDates = calendarDates(szse);
        Set<LocalDate> sseOpen = openDates(sse);
        Set<LocalDate> szseOpen = openDates(szse);
        List<LocalDate> common = commonOpenDates(sseOpen, szseOpen);
        boolean knownAt = java.util.stream.Stream.concat(sse.stream(),
                        szse.stream()).allMatch(value ->
                        !value.envelope().knownAt().isAfter(cutoff)
                                && !value.envelope().firstObservedAt().isAfter(
                                value.envelope().knownAt())
                                && !value.envelope().recordedAt().isBefore(
                                value.envelope().firstObservedAt()));
        boolean firstObserved = java.util.stream.Stream.concat(sse.stream(),
                        szse.stream()).filter(value ->
                        !("SSE".equals(value.exchange())
                                ? priorSseDates : priorSzseDates).contains(
                                value.calendarDate())).allMatch(value ->
                        !value.envelope().firstObservedAt().isBefore(startedAt)
                                && !value.envelope().firstObservedAt().isAfter(
                                cutoff));
        boolean lineage = java.util.stream.Stream.concat(sse.stream(),
                        szse.stream()).allMatch(value ->
                        value.envelope().factType()
                                == FactType.TRADING_CALENDAR
                                && TushareMarketFactProvider.PROVIDER_CODE
                                .equals(value.envelope().sourceCode())
                                && TushareMarketFactProvider
                                .calendarSourceIdentity(value.exchange())
                                .equals(value.envelope().sourceInstrumentId())
                                && ("TRADING_CALENDAR|" + value.exchange()
                                + "|" + value.calendarDate()).equals(
                                value.envelope().naturalKey())
                                && value.envelope().historicalReplayAllowed()
                                && value.envelope().backtestAllowed()
                                && value.envelope().agentUseAllowed());
        return new CalendarAudit(sseDates, szseDates, sseOpen, szseOpen,
                common, duplicates, knownAt, firstObserved, lineage);
    }

    static List<LocalDate> commonOpenDates(
            Set<LocalDate> sseOpen,
            Set<LocalDate> szseOpen
    ) {
        LinkedHashSet<LocalDate> common = new LinkedHashSet<>(sseOpen);
        common.retainAll(szseOpen);
        return common.stream().sorted().toList();
    }

    static List<LocalDate> target250(List<LocalDate> commonOpenDates) {
        if (commonOpenDates.size() < TARGET_SESSIONS) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_TARGET_INCOMPLETE");
        }
        return List.copyOf(commonOpenDates.subList(
                commonOpenDates.size() - TARGET_SESSIONS,
                commonOpenDates.size()));
    }

    private static int duplicateCount(List<TradingCalendarObservation> values) {
        return values.size() - calendarDates(values).size();
    }

    private static Set<LocalDate> calendarDates(
            List<TradingCalendarObservation> values
    ) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (TradingCalendarObservation value : values) {
            if (!result.add(value.calendarDate())) {
                continue;
            }
        }
        return Set.copyOf(result);
    }

    private static Set<LocalDate> openDates(
            List<TradingCalendarObservation> values
    ) {
        Set<LocalDate> result = new LinkedHashSet<>();
        values.stream().filter(TradingCalendarObservation::open)
                .map(TradingCalendarObservation::calendarDate)
                .forEach(result::add);
        return Set.copyOf(result);
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
                .anyMatch(value -> value < 1 || value > 2)
                || progress.providerCalls != 2 + progress.retryCount
                || progress.providerCalls != tradeCalendarCalls
                || progress.providerCalls != exchangeCalls
                || progress.providerCalls > maximumProviderRequests
                || progress.retryCount < 0
                || progress.retryCount > networkRecoveryBudget) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_PROVIDER_BUDGET_MISMATCH");
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
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_BUSINESS_RUN_ACTIVE");
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
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_SCOPE_MUTATION_DETECTED");
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
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_UNIVERSE_INVALID");
        }
    }

    private static void validateArguments(
            LocalDate anchor,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            int maximumProviderRequests,
            int networkRecoveryBudget,
            String gitCommit,
            Progress progress
    ) {
        if (anchor == null || rangeStart == null || rangeEnd == null
                || !rangeEnd.equals(anchor)
                || !rangeStart.equals(anchor.minusDays(
                CALENDAR_LOOKBACK_DAYS))
                || maximumProviderRequests != MAXIMUM_PROVIDER_REQUESTS
                || networkRecoveryBudget != MAXIMUM_NETWORK_RECOVERIES
                || gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")
                || progress == null) {
            throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_ARGUMENTS_INVALID");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    record Outcome(
            SnapshotBundle snapshot,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            CalendarAudit before,
            CalendarAudit after,
            List<LocalDate> target250TradeDates,
            int providerCalls,
            int retryCount,
            Map<String, Integer> calendarCallCountsByExchange,
            List<Long> batchIds,
            int appended,
            int idempotent,
            Instant completedAt
    ) {
        Outcome {
            target250TradeDates = List.copyOf(target250TradeDates);
            calendarCallCountsByExchange = Map.copyOf(
                    calendarCallCountsByExchange);
            batchIds = List.copyOf(batchIds);
        }
    }

    record CalendarAudit(
            Set<LocalDate> sseCalendarDates,
            Set<LocalDate> szseCalendarDates,
            Set<LocalDate> sseOpenDates,
            Set<LocalDate> szseOpenDates,
            List<LocalDate> commonOpenDates,
            int duplicateCount,
            boolean knownAtValid,
            boolean firstObservedAtValid,
            boolean lineageValid
    ) {
        CalendarAudit {
            sseCalendarDates = Set.copyOf(sseCalendarDates);
            szseCalendarDates = Set.copyOf(szseCalendarDates);
            sseOpenDates = Set.copyOf(sseOpenDates);
            szseOpenDates = Set.copyOf(szseOpenDates);
            commonOpenDates = List.copyOf(commonOpenDates);
        }

        LocalDate latestCommonOpenDate() {
            if (commonOpenDates.isEmpty()) {
                throw invalid("MAINBOARD_TRADE_CAL_BACKFILL_COMMON_OPEN_EMPTY");
            }
            return commonOpenDates.get(commonOpenDates.size() - 1);
        }
    }

    private record Counts(long selections, long shadows, long paperRows,
                          long evaluationRows) {
    }

    static final class Progress {
        int providerCalls;
        int retryCount;
        Map<String, Integer> endpointCallCounts = Map.of();
        Map<String, Integer> calendarCallCountsByExchange = Map.of();
        List<Long> batchIds = List.of();
        int appended;
        int idempotent;
    }
}
