package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** One bounded, data-only expansion from the current complete window to 250 sessions. */
final class MainboardHistoryBackfillService {
    static final int TARGET_SESSIONS = 250;
    static final int MILESTONE_SESSIONS = 120;
    static final int MAXIMUM_NETWORK_RECOVERIES = 4;
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(90);

    private final JdbcTemplate jdbc;
    private final PitMarketFactRepository facts;
    private final ResearchUniverseMainboardRepository universes;
    private final TushareMainboardUniverseCaptureService capture;
    private final Clock clock;

    MainboardHistoryBackfillService(
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
            int expectedMissingSessions,
            int maximumProviderRequests,
            String gitCommit,
            Progress progress
    ) {
        Objects.requireNonNull(progress, "progress");
        Instant startedAt = clock.instant();
        Plan plan = plan(anchorTradeDate, expectedMissingSessions,
                maximumProviderRequests, gitCommit, startedAt);
        progress.plan = plan;
        Counts immutableBefore = businessCounts();
        List<LocalDate> newestFirst = plan.missingTradeDates().stream()
                .sorted(Comparator.reverseOrder()).toList();
        try {
            var evidence = capture.captureOrdered(plan.snapshot(), false,
                    newestFirst, plan.rangeStart(), plan.rangeEnd(), false,
                    gitCommit, PROVIDER_TIMEOUT,
                    MAXIMUM_NETWORK_RECOVERIES);
            progress.providerCalls = evidence.providerCallCount();
            progress.retryCount = evidence.retryCount();
            progress.endpointCallCounts = evidence.endpointCallCounts();
            progress.batchIds = evidence.batchIds();
            progress.appended = evidence.appendedObservations();
            progress.idempotent = evidence.idempotentChainTailHits();
            progress.completedTradeDates = evidence.completedTradeDates();
        } catch (TushareMainboardUniverseCaptureService.CaptureFailure failure) {
            progress.providerCalls = failure.providerCallCount();
            progress.retryCount = failure.retryCount();
            progress.endpointCallCounts = failure.endpointCallCounts();
            progress.batchIds = failure.batchIds();
            progress.appended = failure.appendedObservations();
            progress.idempotent = failure.idempotentChainTailHits();
            progress.completedTradeDates = failure.completedTradeDates();
            throw failure;
        }
        validateProviderAccounting(plan, progress);

        Instant completedAt = clock.instant();
        SnapshotBundle after = latestBoundSnapshot(plan.snapshot());
        Set<LocalDate> freshlyCaptured = Set.copyOf(
                plan.missingTradeDates());
        Audit finalAudit = audit(after, plan.targetTradeDates(), completedAt,
                freshlyCaptured, startedAt);
        if (!finalAudit.missingTradeDates().isEmpty()
                || finalAudit.completeSessions() != TARGET_SESSIONS
                || finalAudit.partialDates() != 0
                || finalAudit.duplicateCount() != 0) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_250_INCOMPLETE");
        }
        List<LocalDate> milestoneDates = plan.targetTradeDates().subList(
                TARGET_SESSIONS - MILESTONE_SESSIONS, TARGET_SESSIONS);
        Audit milestone = audit(after, milestoneDates, completedAt,
                freshlyCaptured, startedAt);
        if (milestone.completeSessions() != MILESTONE_SESSIONS
                || !milestone.missingTradeDates().isEmpty()
                || milestone.partialDates() != 0
                || milestone.duplicateCount() != 0) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_120_INCOMPLETE");
        }
        requireUnchanged(plan.snapshot(), immutableBefore);
        int dailyAdded = plan.missingTradeDates().stream().mapToInt(date ->
                inspect(after, date, completedAt, Set.of(date), startedAt)
                        .dailyCount()).sum();
        int factorAdded = plan.missingTradeDates().stream().mapToInt(date ->
                inspect(after, date, completedAt, Set.of(date), startedAt)
                        .factorCount()).sum();
        return new Outcome(after, plan, milestone, finalAudit,
                progress.providerCalls, progress.retryCount,
                progress.endpointCallCounts, progress.batchIds, dailyAdded,
                factorAdded, progress.appended, progress.idempotent,
                completedAt);
    }

    Plan plan(
            LocalDate anchorTradeDate,
            int expectedMissingSessions,
            int maximumProviderRequests,
            String gitCommit,
            Instant asOf
    ) {
        Objects.requireNonNull(anchorTradeDate, "anchorTradeDate");
        Objects.requireNonNull(asOf, "asOf");
        if (gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_GIT_BINDING_INVALID");
        }
        requireNoActiveBusinessRun();
        SnapshotBundle snapshot = universes.latest().orElseThrow(() ->
                invalid("MAINBOARD_HISTORY_BACKFILL_UNIVERSE_MISSING"));
        requireUniverse(snapshot);
        if (new ShadowResearchRepository(jdbc, new ObjectMapper()
                .findAndRegisterModules()).researchCalendarState(
                anchorTradeDate, asOf)
                != ShadowResearchRepository.CalendarState.OPEN) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_ANCHOR_NOT_OPEN");
        }
        ResearchUniverseMainboardDatasetLoader loader =
                new ResearchUniverseMainboardDatasetLoader(facts);
        LocalDate currentAnchor = loader.resolveAnchor(snapshot, asOf, false);
        if (!anchorTradeDate.equals(currentAnchor)) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_ANCHOR_MISMATCH");
        }
        List<LocalDate> openDates = loader.commonOpenDatesThrough(
                anchorTradeDate, asOf);
        if (openDates.size() < TARGET_SESSIONS
                || !openDates.get(openDates.size() - 1)
                .equals(anchorTradeDate)) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_CALENDAR_INCOMPLETE");
        }
        List<LocalDate> targetDates = List.copyOf(openDates.subList(
                openDates.size() - TARGET_SESSIONS, openDates.size()));
        Audit initial = audit(snapshot, targetDates, asOf, Set.of(), asOf);
        if (initial.partialDates() != 0 || initial.duplicateCount() != 0) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_PARTIAL_DATE_PRESENT");
        }
        List<LocalDate> missing = initial.missingTradeDates();
        int baseRequests = Math.multiplyExact(missing.size(), 2);
        int expectedMaximum = baseRequests + MAXIMUM_NETWORK_RECOVERIES;
        if (missing.size() != expectedMissingSessions
                || expectedMissingSessions <= 0
                || maximumProviderRequests != expectedMaximum
                || maximumProviderRequests
                > TushareManualBoundedSession
                .MAINBOARD_UNIVERSE_MAX_PROVIDER_REQUESTS) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_FIXED_SCOPE_INVALID");
        }
        return new Plan(snapshot, anchorTradeDate, targetDates.get(0),
                targetDates.get(targetDates.size() - 1), targetDates,
                missing, initial.completeSessions(), baseRequests,
                MAXIMUM_NETWORK_RECOVERIES, maximumProviderRequests,
                initial);
    }

    private Audit audit(
            SnapshotBundle snapshot,
            List<LocalDate> dates,
            Instant cutoff,
            Set<LocalDate> freshDates,
            Instant startedAt
    ) {
        List<LocalDate> missing = new ArrayList<>();
        int complete = 0;
        int partial = 0;
        int duplicates = 0;
        long dailyRows = 0;
        long factorRows = 0;
        boolean knownAt = true;
        for (LocalDate date : dates) {
            DateInspection value = inspect(snapshot, date, cutoff,
                    freshDates, startedAt);
            dailyRows += value.dailyCount();
            factorRows += value.factorCount();
            duplicates += value.duplicateCount();
            knownAt &= value.knownAtValid();
            if (value.partial()) partial++;
            if (value.complete()) complete++;
            else missing.add(date);
        }
        return new Audit(List.copyOf(dates), List.copyOf(missing), complete,
                partial, duplicates, dailyRows, factorRows, knownAt);
    }

    private DateInspection inspect(
            SnapshotBundle snapshot,
            LocalDate date,
            Instant cutoff,
            Set<LocalDate> freshDates,
            Instant startedAt
    ) {
        List<RawDailyBarObservation> raw = facts.findRawBarsForSnapshotAsOf(
                snapshot.snapshot().databaseId(), date, date, cutoff);
        List<AdjustmentFactorObservation> factors =
                facts.findFactorsForSnapshotAsOf(
                        snapshot.snapshot().databaseId(), date, date, cutoff);
        boolean partial = raw.isEmpty() != factors.isEmpty();
        if (raw.isEmpty() && factors.isEmpty()) {
            return new DateInspection(false, false, 0, 0, 0, true);
        }
        Map<String, RawDailyBarObservation> daily = unique(raw,
                value -> value.symbol() + '|' + value.exchange());
        Map<String, AdjustmentFactorObservation> adjusted = unique(factors,
                AdjustmentFactorObservation::symbol);
        int duplicates = raw.size() - daily.size()
                + factors.size() - adjusted.size();
        Map<String, Member> members = snapshot.members().stream().collect(
                Collectors.toUnmodifiableMap(Member::symbol, value -> value));
        Set<String> dailySymbols = daily.values().stream().map(
                RawDailyBarObservation::symbol).collect(Collectors.toSet());
        long active = snapshot.members().stream().filter(value ->
                !value.listDate().isAfter(date)
                        && (value.delistDate() == null
                        || !value.delistDate().isBefore(date))).count();
        boolean aligned = !partial && duplicates == 0
                && dailySymbols.equals(adjusted.keySet())
                && daily.values().stream().allMatch(value ->
                date.equals(value.tradeDate())
                        && members.containsKey(value.symbol())
                        && members.get(value.symbol()).exchange().equals(
                        value.exchange()))
                && adjusted.values().stream().allMatch(value ->
                date.equals(value.factorEffectiveTradeDate())
                        && members.containsKey(value.symbol()));
        boolean coverage = active > 0 && daily.size() * 100L >= active
                * TushareMarketFactProvider.MAINBOARD_MINIMUM_COVERAGE_PERCENT;
        boolean requireFresh = freshDates.contains(date);
        boolean knownAt = java.util.stream.Stream.concat(
                        raw.stream().map(RawDailyBarObservation::envelope),
                        factors.stream().map(
                                AdjustmentFactorObservation::envelope))
                .allMatch(envelope -> !envelope.knownAt().isAfter(cutoff)
                        && !envelope.firstObservedAt().isAfter(
                        envelope.knownAt())
                        && (!requireFresh || !envelope.knownAt().isBefore(
                        startedAt))
                        && envelope.historicalReplayAllowed()
                        && envelope.backtestAllowed()
                        && envelope.agentUseAllowed());
        return new DateInspection(aligned && coverage && knownAt, partial,
                daily.size(), adjusted.size(), duplicates, knownAt);
    }

    private static <T> Map<String, T> unique(
            List<T> values,
            Function<T, String> identity
    ) {
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) result.putIfAbsent(identity.apply(value), value);
        return Map.copyOf(result);
    }

    private void validateProviderAccounting(Plan plan, Progress progress) {
        int dailyCalls = progress.endpointCallCounts.getOrDefault("daily", 0);
        int factorCalls = progress.endpointCallCounts.getOrDefault(
                "adj_factor", 0);
        int stockBasicCalls = progress.endpointCallCounts.getOrDefault(
                "stock_basic", 0);
        int calendarCalls = progress.endpointCallCounts.getOrDefault(
                "trade_cal", 0);
        int base = plan.missingTradeDates().size();
        int recoveries = dailyCalls + factorCalls - base * 2;
        if (stockBasicCalls != 0 || calendarCalls != 0
                || dailyCalls < base || factorCalls < base
                || recoveries != progress.retryCount
                || progress.retryCount < 0
                || progress.retryCount > MAXIMUM_NETWORK_RECOVERIES
                || progress.providerCalls != dailyCalls + factorCalls
                || progress.providerCalls != plan.baseProviderRequests()
                + progress.retryCount
                || progress.providerCalls > plan.maximumProviderRequests()
                || !new LinkedHashSet<>(progress.completedTradeDates)
                .equals(new LinkedHashSet<>(plan.missingTradeDates()))) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_PROVIDER_BUDGET_MISMATCH");
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
            throw invalid("MAINBOARD_HISTORY_BACKFILL_BUSINESS_RUN_ACTIVE");
        }
    }

    private SnapshotBundle latestBoundSnapshot(SnapshotBundle expected) {
        SnapshotBundle actual = universes.latest().orElseThrow();
        if (actual.snapshot().databaseId()
                != expected.snapshot().databaseId()
                || !actual.snapshot().memberFingerprint().equals(
                expected.snapshot().memberFingerprint())) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_UNIVERSE_CHANGED");
        }
        return actual;
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
        SnapshotBundle after = latestBoundSnapshot(snapshot);
        if (!businessCounts().equals(before)
                || after.snapshot().memberCount()
                != snapshot.snapshot().memberCount()) {
            throw invalid("MAINBOARD_HISTORY_BACKFILL_SCOPE_MUTATION_DETECTED");
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
            throw invalid("MAINBOARD_HISTORY_BACKFILL_UNIVERSE_INVALID");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    record Plan(
            SnapshotBundle snapshot,
            LocalDate anchorTradeDate,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            List<LocalDate> targetTradeDates,
            List<LocalDate> missingTradeDates,
            int originalCompleteSessions,
            int baseProviderRequests,
            int networkRecoveryBudget,
            int maximumProviderRequests,
            Audit initialAudit
    ) {
        Plan {
            targetTradeDates = List.copyOf(targetTradeDates);
            missingTradeDates = List.copyOf(missingTradeDates);
        }
    }

    record Outcome(
            SnapshotBundle snapshot,
            Plan plan,
            Audit milestone120,
            Audit final250,
            int providerCalls,
            int retryCount,
            Map<String, Integer> endpointCallCounts,
            List<Long> batchIds,
            int dailyAdded,
            int factorAdded,
            int appended,
            int idempotent,
            Instant completedAt
    ) {
        Outcome {
            endpointCallCounts = Map.copyOf(endpointCallCounts);
            batchIds = List.copyOf(batchIds);
        }
    }

    record Audit(
            List<LocalDate> targetTradeDates,
            List<LocalDate> missingTradeDates,
            int completeSessions,
            int partialDates,
            int duplicateCount,
            long dailyRows,
            long factorRows,
            boolean knownAtValid
    ) {
        Audit {
            targetTradeDates = List.copyOf(targetTradeDates);
            missingTradeDates = List.copyOf(missingTradeDates);
        }
    }

    private record DateInspection(
            boolean complete,
            boolean partial,
            int dailyCount,
            int factorCount,
            int duplicateCount,
            boolean knownAtValid
    ) {
    }

    private record Counts(long selections, long shadows, long paperRows,
                          long evaluationRows) {
    }

    static final class Progress {
        Plan plan;
        int providerCalls;
        int retryCount;
        Map<String, Integer> endpointCallCounts = Map.of();
        List<Long> batchIds = List.of();
        List<LocalDate> completedTradeDates = List.of();
        int appended;
        int idempotent;
    }
}
