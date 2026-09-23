package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactEnvelope;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Shared completeness contract used by both increment and read-only audit. */
final class MainboardDailyFactIntegrity {
    private MainboardDailyFactIntegrity() {
    }

    static Evaluation evaluate(
            SnapshotBundle snapshot,
            LocalDate date,
            List<RawDailyBarObservation> dailyValues,
            List<AdjustmentFactorObservation> factorValues,
            Instant startedAt,
            boolean requireFresh,
            Instant completedAt
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dailyValues, "dailyValues");
        Objects.requireNonNull(factorValues, "factorValues");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");

        Unique<RawDailyBarObservation> daily = unique(dailyValues,
                value -> value.symbol() + '|' + value.exchange());
        Unique<AdjustmentFactorObservation> factors = unique(factorValues,
                AdjustmentFactorObservation::symbol);
        Map<String, Member> members = snapshot.members().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Member::symbol,
                        Function.identity()));
        Set<String> dailySymbols = daily.values().values().stream()
                .map(RawDailyBarObservation::symbol)
                .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new));
        Set<String> factorSymbols = new LinkedHashSet<>(
                factors.values().keySet());
        boolean dailyAligned = daily.values().values().stream().allMatch(
                value -> date.equals(value.tradeDate())
                        && members.containsKey(value.symbol())
                        && members.get(value.symbol()).exchange().equals(
                        value.exchange()));
        boolean factorAligned = factors.values().values().stream().allMatch(
                value -> date.equals(value.factorEffectiveTradeDate())
                        && members.containsKey(value.symbol()));
        boolean setsEqual = dailySymbols.equals(factorSymbols);
        long active = snapshot.members().stream().filter(value ->
                !value.listDate().isAfter(date)
                        && (value.delistDate() == null
                        || !value.delistDate().isBefore(date))).count();
        boolean dailyCoverage = active > 0
                && daily.values().size() * 100L >= active
                * TushareMarketFactProvider.MAINBOARD_MINIMUM_COVERAGE_PERCENT;
        boolean factorCoverage = active > 0
                && factors.values().size() * 100L >= active
                * TushareMarketFactProvider.MAINBOARD_MINIMUM_COVERAGE_PERCENT;
        boolean dailyPit = dailyValues.stream().map(
                RawDailyBarObservation::envelope).allMatch(envelope ->
                validPit(envelope, startedAt, requireFresh, completedAt));
        boolean factorPit = factorValues.stream().map(
                AdjustmentFactorObservation::envelope).allMatch(envelope ->
                validPit(envelope, startedAt, requireFresh, completedAt));

        boolean dailySideComplete = !daily.values().isEmpty()
                && daily.duplicateCount() == 0 && dailyAligned
                && dailyCoverage && dailyPit;
        boolean factorSideComplete = !factors.values().isEmpty()
                && factors.duplicateCount() == 0 && factorAligned
                && factorCoverage && factorPit;
        boolean complete = dailySideComplete && factorSideComplete
                && setsEqual;
        Status status = complete ? Status.COMPLETE
                : dailySideComplete ^ factorSideComplete
                ? Status.PARTIAL : Status.MISSING;

        List<String> reasons = new ArrayList<>();
        if (daily.values().isEmpty()) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_DAILY_FACT_MISSING");
        }
        if (factors.values().isEmpty()) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_ADJ_FACTOR_MISSING");
        }
        if (daily.duplicateCount() > 0) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_DAILY_DUPLICATE");
        }
        if (factors.duplicateCount() > 0) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_FACTOR_DUPLICATE");
        }
        boolean alignment = !daily.values().isEmpty()
                && !factors.values().isEmpty() && dailyAligned
                && factorAligned && setsEqual;
        if (!alignment && !daily.values().isEmpty()
                && !factors.values().isEmpty()) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_FACT_ALIGNMENT_INVALID");
        }
        if ((!daily.values().isEmpty() && !dailyCoverage)
                || (!factors.values().isEmpty() && !factorCoverage)) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_COVERAGE_INCOMPLETE");
        }
        if ((!daily.values().isEmpty() && !dailyPit)
                || (!factors.values().isEmpty() && !factorPit)) {
            reasons.add("MAINBOARD_DAILY_INCREMENT_PIT_INVALID");
        }
        return new Evaluation(status, List.copyOf(reasons),
                dailyValues.size(), factorValues.size(),
                daily.values().size(), factors.values().size(),
                daily.duplicateCount(), factors.duplicateCount(), active,
                dailyAligned, factorAligned, setsEqual, dailyCoverage,
                factorCoverage, dailyPit, factorPit, dailySideComplete,
                factorSideComplete);
    }

    private static boolean validPit(
            FactEnvelope envelope,
            Instant startedAt,
            boolean requireFresh,
            Instant completedAt
    ) {
        return envelope != null && envelope.knownAt() != null
                && envelope.firstObservedAt() != null
                && !envelope.knownAt().isAfter(completedAt)
                && !envelope.firstObservedAt().isAfter(envelope.knownAt())
                && (!requireFresh || !envelope.knownAt().isBefore(startedAt))
                && envelope.historicalReplayAllowed()
                && envelope.backtestAllowed()
                && envelope.agentUseAllowed();
    }

    private static <T> Unique<T> unique(
            List<T> values,
            Function<T, String> identity
    ) {
        Map<String, T> result = new LinkedHashMap<>();
        int duplicates = 0;
        for (T value : values) {
            if (result.put(identity.apply(value), value) != null) {
                duplicates++;
            }
        }
        return new Unique<>(Map.copyOf(result), duplicates);
    }

    enum Status { COMPLETE, PARTIAL, MISSING }

    record Evaluation(
            Status status,
            List<String> reasonCodes,
            int dailyRowCount,
            int factorRowCount,
            int dailySecurityCount,
            int factorSecurityCount,
            int dailyDuplicateCount,
            int factorDuplicateCount,
            long activeMemberCount,
            boolean dailyAligned,
            boolean factorAligned,
            boolean securitySetsEqual,
            boolean dailyCoverageComplete,
            boolean factorCoverageComplete,
            boolean dailyKnownAtValid,
            boolean factorKnownAtValid,
            boolean dailySideComplete,
            boolean factorSideComplete
    ) {
        Evaluation {
            reasonCodes = List.copyOf(reasonCodes);
        }

        int duplicateCount() {
            return dailyDuplicateCount + factorDuplicateCount;
        }

        boolean complete() {
            return status == Status.COMPLETE;
        }

        boolean coverageComplete() {
            return dailyCoverageComplete && factorCoverageComplete
                    && securitySetsEqual;
        }

        boolean knownAtValid() {
            return dailyKnownAtValid && factorKnownAtValid;
        }

        void requireDailyIncrementComplete() {
            if (dailyDuplicateCount > 0) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_DAILY_DUPLICATE");
            }
            if (factorDuplicateCount > 0) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_FACTOR_DUPLICATE");
            }
            if (dailySecurityCount == 0 || factorSecurityCount == 0
                    || !dailyAligned || !factorAligned
                    || !securitySetsEqual) {
                throw invalid(
                        "MAINBOARD_DAILY_INCREMENT_FACT_ALIGNMENT_INVALID");
            }
            // Once sets are aligned this is exactly the prior daily-count
            // coverage gate; factor coverage necessarily has the same count.
            if (!dailyCoverageComplete) {
                throw invalid(
                        "MAINBOARD_DAILY_INCREMENT_COVERAGE_INCOMPLETE");
            }
            if (!knownAtValid()) {
                throw invalid("MAINBOARD_DAILY_INCREMENT_PIT_INVALID");
            }
        }
    }

    private record Unique<T>(Map<String, T> values, int duplicateCount) {
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
