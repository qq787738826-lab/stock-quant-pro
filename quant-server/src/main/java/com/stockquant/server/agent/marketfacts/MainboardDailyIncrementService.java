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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Data-only, one-date main-board market-fact increment. */
final class MainboardDailyIncrementService {
    static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(90);
    private final JdbcTemplate jdbc;
    private final PitMarketFactRepository facts;
    private final ResearchUniverseMainboardRepository universes;
    private final TushareMainboardUniverseCaptureService capture;
    private final Clock clock;

    MainboardDailyIncrementService(
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

    Outcome execute(LocalDate tradeDate, String gitCommit, Progress progress) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_GIT_BINDING_INVALID");
        }
        Instant startedAt = clock.instant();
        SnapshotBundle before = universes.latest().orElseThrow(() ->
                invalid("MAINBOARD_DAILY_INCREMENT_UNIVERSE_MISSING"));
        requireUniverse(before);
        requireNoActiveBusinessRun();
        if (new ShadowResearchRepository(jdbc, new ObjectMapper()
                .findAndRegisterModules()).researchCalendarState(
                tradeDate, startedAt)
                != ShadowResearchRepository.CalendarState.OPEN) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_TRADE_DATE_NOT_OPEN");
        }

        Counts immutableBefore = businessCounts();
        DateFacts prior = dateFacts(before, tradeDate, startedAt);
        if (prior.daily().isEmpty() != prior.factors().isEmpty()) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_PARTIAL_DATE_PRESENT");
        }
        if (!prior.daily().isEmpty()) {
            Validation existing = validate(before, tradeDate, prior,
                    startedAt, false, startedAt);
            requireUnchanged(before, immutableBefore);
            LocalDate latest = new ResearchUniverseMainboardDatasetLoader(facts)
                    .resolveAnchor(before, clock.instant(), false);
            return new Outcome(before, 0, 0, List.of(), 0, 0, 0, 0,
                    prior.daily().size(), prior.factors().size(), existing,
                    latest);
        }

        try {
            var evidence = capture.capture(before, false, Set.of(tradeDate),
                    tradeDate, tradeDate, false, gitCommit,
                    PROVIDER_TIMEOUT, 0);
            progress.providerCalls = evidence.providerCallCount();
            progress.retryCount = evidence.retryCount();
            progress.batchIds = evidence.batchIds();
            progress.appended = evidence.appendedObservations();
            progress.idempotent = evidence.idempotentChainTailHits();
        } catch (TushareMainboardUniverseCaptureService.CaptureFailure failure) {
            progress.providerCalls = failure.providerCallCount();
            progress.retryCount = failure.retryCount();
            throw failure;
        }
        if (progress.providerCalls != 2 || progress.retryCount != 0
                || progress.batchIds.size() != 1) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_PROVIDER_BUDGET_MISMATCH");
        }

        Instant completedAt = clock.instant();
        SnapshotBundle after = universes.latest().orElseThrow();
        if (after.snapshot().databaseId() != before.snapshot().databaseId()
                || !after.snapshot().memberFingerprint().equals(
                before.snapshot().memberFingerprint())) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_UNIVERSE_CHANGED");
        }
        DateFacts current = dateFacts(after, tradeDate, completedAt);
        Validation validation = validate(after, tradeDate, current,
                startedAt, true, completedAt);
        requireUnchanged(before, immutableBefore);
        LocalDate latest = new ResearchUniverseMainboardDatasetLoader(facts)
                .resolveAnchor(after, completedAt, false);
        if (latest.isBefore(tradeDate)) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_LATEST_DATE_INVALID");
        }
        return new Outcome(after, progress.providerCalls, progress.retryCount,
                progress.batchIds, current.daily().size(),
                current.factors().size(), progress.appended,
                progress.idempotent, current.daily().size(),
                current.factors().size(), validation, latest);
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
            throw invalid("MAINBOARD_DAILY_INCREMENT_BUSINESS_RUN_ACTIVE");
        }
    }

    private DateFacts dateFacts(
            SnapshotBundle snapshot, LocalDate date, Instant cutoff
    ) {
        return new DateFacts(
                facts.findRawBarsForSnapshotAsOf(
                        snapshot.snapshot().databaseId(), date, date, cutoff),
                facts.findFactorsForSnapshotAsOf(
                        snapshot.snapshot().databaseId(), date, date, cutoff));
    }

    private Validation validate(
            SnapshotBundle snapshot,
            LocalDate date,
            DateFacts values,
            Instant startedAt,
            boolean requireFresh,
            Instant completedAt
    ) {
        Map<String, RawDailyBarObservation> daily = unique(values.daily(),
                value -> value.symbol() + '|' + value.exchange(),
                "MAINBOARD_DAILY_INCREMENT_DAILY_DUPLICATE");
        Map<String, Member> members = snapshot.members().stream().collect(
                Collectors.toUnmodifiableMap(Member::symbol, value -> value));
        Map<String, AdjustmentFactorObservation> factors = unique(
                values.factors(), AdjustmentFactorObservation::symbol,
                "MAINBOARD_DAILY_INCREMENT_FACTOR_DUPLICATE");
        Set<String> dailySymbols = daily.values().stream().map(
                RawDailyBarObservation::symbol).collect(Collectors.toSet());
        if (daily.isEmpty() || !dailySymbols.equals(factors.keySet())
                || daily.values().stream().anyMatch(value ->
                !date.equals(value.tradeDate())
                        || !members.containsKey(value.symbol())
                        || !members.get(value.symbol()).exchange().equals(
                        value.exchange()))
                || factors.values().stream().anyMatch(value ->
                !date.equals(value.factorEffectiveTradeDate())
                        || !members.containsKey(value.symbol()))) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_FACT_ALIGNMENT_INVALID");
        }
        long active = snapshot.members().stream().filter(value ->
                !value.listDate().isAfter(date)
                        && (value.delistDate() == null
                        || !value.delistDate().isBefore(date))).count();
        boolean coverage = active > 0 && daily.size() * 100L >= active
                * TushareMarketFactProvider.MAINBOARD_MINIMUM_COVERAGE_PERCENT;
        if (!coverage) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_COVERAGE_INCOMPLETE");
        }
        boolean knownAt = java.util.stream.Stream.concat(
                        values.daily().stream().map(RawDailyBarObservation::envelope),
                        values.factors().stream().map(
                                AdjustmentFactorObservation::envelope))
                .allMatch(envelope -> !envelope.knownAt().isAfter(completedAt)
                        && !envelope.firstObservedAt().isAfter(
                        envelope.knownAt())
                        && (!requireFresh || !envelope.knownAt().isBefore(
                        startedAt))
                        && envelope.historicalReplayAllowed()
                        && envelope.backtestAllowed()
                        && envelope.agentUseAllowed());
        if (!knownAt) {
            throw invalid("MAINBOARD_DAILY_INCREMENT_PIT_INVALID");
        }
        return new Validation(true, true, 0, active);
    }

    private static <T> Map<String, T> unique(
            List<T> values, Function<T, String> identity, String failure
    ) {
        Map<String, T> result = new java.util.LinkedHashMap<>();
        for (T value : values) {
            if (result.put(identity.apply(value), value) != null) {
                throw invalid(failure);
            }
        }
        return Map.copyOf(result);
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
            throw invalid("MAINBOARD_DAILY_INCREMENT_SCOPE_MUTATION_DETECTED");
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
            throw invalid("MAINBOARD_DAILY_INCREMENT_UNIVERSE_INVALID");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    record Outcome(
            SnapshotBundle snapshot,
            int providerCalls,
            int retryCount,
            List<Long> batchIds,
            int dailyAdded,
            int factorAdded,
            int appended,
            int idempotent,
            int dailyVisible,
            int factorVisible,
            Validation validation,
            LocalDate latestCompleteDate
    ) {
        Outcome {
            batchIds = List.copyOf(batchIds);
        }
    }

    record Validation(
            boolean coverageComplete,
            boolean knownAtValid,
            int duplicateCount,
            long activeMemberCount
    ) {
    }

    record DateFacts(
            List<RawDailyBarObservation> daily,
            List<AdjustmentFactorObservation> factors
    ) {
        DateFacts {
            daily = List.copyOf(daily);
            factors = List.copyOf(factors);
        }
    }

    private record Counts(long selections, long shadows, long paperRows,
                          long evaluationRows) {
    }

    static final class Progress {
        int providerCalls;
        int retryCount;
        List<Long> batchIds = List.of();
        int appended;
        int idempotent;
    }
}
