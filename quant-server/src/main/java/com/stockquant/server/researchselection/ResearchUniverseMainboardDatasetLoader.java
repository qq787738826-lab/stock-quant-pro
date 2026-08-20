package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.QualifiedMarketField;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.agent.marketfacts.TushareMarketFactProvider;
import com.stockquant.server.researchselection.ResearchSelectionModels.DataCoverage;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.EligibilityStatus;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.ExclusionReason;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.MemberEvaluation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchTradePlanService.PriceBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Batch, no-N+1 current-as-of projection for all main-board members. */
public final class ResearchUniverseMainboardDatasetLoader {
    private static final int MAXIMUM_SESSIONS = 250;
    private static final int CALENDAR_LOOKBACK_DAYS = 500;
    private static final Duration SNAPSHOT_REFRESH_INTERVAL =
            Duration.ofDays(7);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int COMPLETE_ANCHOR_LOOKBACK = 31;
    private final PitMarketFactRepository facts;

    public ResearchUniverseMainboardDatasetLoader(
            PitMarketFactRepository facts
    ) {
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    public LoadedMainboard load(
            SnapshotBundle snapshot,
            LocalDate anchor,
            Instant cutoff
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(cutoff, "cutoff");
        List<LocalDate> openDates = commonOpenDates(anchor, cutoff);
        if (openDates.size() < ResearchUniverseMainboard
                .BASIC_MINIMUM_SESSIONS) {
            throw invalid("MAINBOARD_TRADE_CALENDAR_INCOMPLETE");
        }
        List<LocalDate> selected = openDates.subList(Math.max(0,
                openDates.size() - MAXIMUM_SESSIONS), openDates.size());
        LocalDate rangeStart = selected.get(0);
        LocalDate rangeEnd = selected.get(selected.size() - 1);
        List<RawDailyBarObservation> raw = facts
                .findRawBarsForSnapshotAsOf(snapshot.snapshot().databaseId(),
                        rangeStart, rangeEnd, cutoff);
        List<AdjustmentFactorObservation> factors = facts
                .findFactorsForSnapshotAsOf(snapshot.snapshot().databaseId(),
                        rangeStart, rangeEnd, cutoff);
        Map<String, Map<LocalDate, RawDailyBarObservation>> rawBySecurity =
                groupRaw(raw);
        Map<String, Map<LocalDate, AdjustmentFactorObservation>>
                factorBySecurity = groupFactors(factors);
        List<MemberEvaluation> evaluations = new ArrayList<>();
        List<DailyBar> bars = new ArrayList<>();
        Map<Security, List<PriceBar>> tradePlanPrices = new LinkedHashMap<>();
        int totalMissingDaily = 0;
        int totalMissingFactors = 0;
        for (Member member : snapshot.members()) {
            Map<LocalDate, RawDailyBarObservation> memberRaw =
                    rawBySecurity.getOrDefault(member.security().canonicalCode(),
                            Map.of());
            Map<LocalDate, AdjustmentFactorObservation> memberFactors =
                    factorBySecurity.getOrDefault(member.symbol(), Map.of());
            MemberProjection projection = project(member, selected, memberRaw,
                    memberFactors, cutoff);
            evaluations.add(projection.evaluation());
            bars.addAll(projection.bars());
            if (!projection.priceBars().isEmpty()) {
                tradePlanPrices.put(member.security(),
                        projection.priceBars());
            }
            totalMissingDaily += projection.evaluation().missingDaily();
            totalMissingFactors += projection.evaluation()
                    .missingAdjustmentFactors();
        }
        long eligible = evaluations.stream().filter(value ->
                value.status() == EligibilityStatus.ELIGIBLE).count();
        if (eligible < 3 || bars.isEmpty()) {
            throw invalid("MAINBOARD_ELIGIBLE_UNIVERSE_TOO_SMALL");
        }
        List<TradingSession> sessions = selected.stream().map(date ->
                new TradingSession(date, Set.of("SSE", "SZSE"))).toList();
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT,
                "MAINBOARD_RESEARCH_" + lineageFingerprint(bars),
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, cutoff,
                sessions, bars);
        DataCoverage coverage = new DataCoverage(
                selected.get(Math.max(0, selected.size() - 60)), rangeEnd,
                60, Math.min(60, selected.size()),
                snapshot.snapshot().memberCount(), Math.toIntExact(eligible),
                totalMissingDaily, totalMissingFactors, true, true, true,
                true, bars.stream().noneMatch(value ->
                value.sourceKnownAt().isAfter(cutoff)));
        return new LoadedMainboard(snapshot, dataset, coverage, evaluations,
                selected, tradePlanPrices);
    }

    public Audit audit(
            SnapshotBundle snapshot,
            LocalDate anchor,
            Instant cutoff,
            int requiredSessions
    ) {
        List<LocalDate> openDates = commonOpenDates(anchor, cutoff);
        if (openDates.size() < requiredSessions) {
            return new Audit(openDates, openDates, true,
                    snapshot == null, 0);
        }
        List<LocalDate> required = openDates.subList(
                openDates.size() - requiredSessions, openDates.size());
        if (snapshot == null) {
            return new Audit(required, required, false, true, 0);
        }
        LocalDate start = required.get(0);
        LocalDate end = required.get(required.size() - 1);
        List<RawDailyBarObservation> raw = facts
                .findRawBarsForSnapshotAsOf(snapshot.snapshot().databaseId(),
                        start, end, cutoff);
        List<AdjustmentFactorObservation> factors = facts
                .findFactorsForSnapshotAsOf(snapshot.snapshot().databaseId(),
                        start, end, cutoff);
        Map<LocalDate, Set<String>> rawByDate = identitiesByRawDate(raw);
        Map<LocalDate, Set<String>> factorByDate = identitiesByFactorDate(
                factors, snapshot.members());
        List<LocalDate> missing = new ArrayList<>();
        for (LocalDate date : required) {
            Set<String> daily = rawByDate.getOrDefault(date, Set.of());
            Set<String> adjusted = factorByDate.getOrDefault(date, Set.of());
            long expected = activeMembers(snapshot.members(), date);
            if (!daily.equals(adjusted) || expected == 0
                    || daily.size() * 100L < expected
                    * TushareMarketFactProvider
                    .MAINBOARD_MINIMUM_COVERAGE_PERCENT) {
                missing.add(date);
            }
        }
        boolean refreshSnapshot = snapshot.snapshot().lastVerifiedAt().plus(
                SNAPSHOT_REFRESH_INTERVAL).isBefore(cutoff);
        return new Audit(required, missing, false, refreshSnapshot,
                Math.toIntExact(raw.stream().map(value ->
                        value.symbol() + '|' + value.exchange()).distinct()
                        .count()));
    }

    /**
     * Resolves a closed common trading date without admitting intraday daily
     * data. Scheduled-after-close runs prefer the newest closed session;
     * on-demand runs reuse the newest already complete 60-session window.
     */
    public LocalDate resolveAnchor(
            SnapshotBundle snapshot,
            Instant asOf,
            boolean preferNewestClosed
    ) {
        Objects.requireNonNull(asOf, "asOf");
        LocalDate localDate = asOf.atZone(SHANGHAI).toLocalDate();
        LocalDate latestClosed = asOf.isBefore(
                StrategyResearchModels.closeInstant(localDate))
                ? localDate.minusDays(1) : localDate;
        List<LocalDate> openDates = commonOpenDates(latestClosed, asOf);
        if (openDates.isEmpty()) {
            throw invalid("MAINBOARD_COMMON_OPEN_SESSION_MISSING");
        }
        LocalDate newest = openDates.get(openDates.size() - 1);
        if (preferNewestClosed || snapshot == null) return newest;

        int firstCandidate = Math.max(
                ResearchUniverseMainboard.STABILITY_MINIMUM_SESSIONS - 1,
                openDates.size() - COMPLETE_ANCHOR_LOOKBACK);
        if (firstCandidate >= openDates.size()) return newest;
        int queryStartIndex = Math.max(0, firstCandidate
                - ResearchUniverseMainboard.STABILITY_MINIMUM_SESSIONS + 1);
        List<RawDailyBarObservation> raw = facts
                .findRawBarsForSnapshotAsOf(snapshot.snapshot().databaseId(),
                        openDates.get(queryStartIndex), newest, asOf);
        List<AdjustmentFactorObservation> factors = facts
                .findFactorsForSnapshotAsOf(snapshot.snapshot().databaseId(),
                        openDates.get(queryStartIndex), newest, asOf);
        Map<LocalDate, Set<String>> rawByDate = identitiesByRawDate(raw);
        Map<LocalDate, Set<String>> factorByDate = identitiesByFactorDate(
                factors, snapshot.members());
        Set<LocalDate> complete = new LinkedHashSet<>();
        for (LocalDate date : openDates.subList(queryStartIndex,
                openDates.size())) {
            Set<String> daily = rawByDate.getOrDefault(date, Set.of());
            Set<String> adjusted = factorByDate.getOrDefault(date, Set.of());
            long expected = activeMembers(snapshot.members(), date);
            if (expected > 0 && daily.equals(adjusted)
                    && daily.size() * 100L >= expected
                    * TushareMarketFactProvider
                    .MAINBOARD_MINIMUM_COVERAGE_PERCENT) {
                complete.add(date);
            }
        }
        for (int index = openDates.size() - 1;
                index >= firstCandidate; index--) {
            int start = index - ResearchUniverseMainboard
                    .STABILITY_MINIMUM_SESSIONS + 1;
            if (start >= 0 && complete.containsAll(openDates.subList(start,
                    index + 1))) {
                return openDates.get(index);
            }
        }
        return bestPartialBackfillAnchor(openDates, complete,
                firstCandidate, newest);
    }

    /**
     * Keeps an interrupted initial backfill on the 60-session window with the
     * most already-complete dates. A wall-clock day change must not discard
     * the oldest completed date and silently add a new Provider obligation.
     */
    static LocalDate bestPartialBackfillAnchor(
            List<LocalDate> openDates,
            Set<LocalDate> complete,
            int firstCandidate,
            LocalDate newest
    ) {
        int bestCount = 0;
        LocalDate best = newest;
        for (int index = firstCandidate; index < openDates.size(); index++) {
            int start = index - ResearchUniverseMainboard
                    .STABILITY_MINIMUM_SESSIONS + 1;
            if (start < 0) continue;
            int count = 0;
            for (LocalDate date : openDates.subList(start, index + 1)) {
                if (complete.contains(date)) count++;
            }
            if (count > bestCount || count == bestCount
                    && openDates.get(index).isAfter(best)) {
                bestCount = count;
                best = openDates.get(index);
            }
        }
        return bestCount == 0 ? newest : best;
    }

    public List<LocalDate> commonOpenDatesThrough(
            LocalDate anchor,
            Instant cutoff
    ) {
        return List.copyOf(commonOpenDates(anchor, cutoff));
    }

    static MemberProjection project(
            Member member,
            List<LocalDate> sessions,
            Map<LocalDate, RawDailyBarObservation> raw,
            Map<LocalDate, AdjustmentFactorObservation> factors,
            Instant cutoff
    ) {
        Set<ExclusionReason> reasons = new LinkedHashSet<>();
        if (member.stSecurity()) reasons.add(ExclusionReason.ST_SECURITY);
        int basicStart = Math.max(0, sessions.size()
                - ResearchUniverseMainboard.BASIC_MINIMUM_SESSIONS);
        List<LocalDate> required = sessions.subList(basicStart,
                sessions.size());
        if (member.listDate().isAfter(required.get(0))) {
            reasons.add(ExclusionReason.LISTING_HISTORY_INSUFFICIENT);
        }
        int missingDaily = Math.toIntExact(required.stream()
                .filter(date -> !raw.containsKey(date)).count());
        int missingFactors = Math.toIntExact(required.stream()
                .filter(date -> !factors.containsKey(date)).count());
        if (missingDaily > 0) reasons.add(ExclusionReason.DAILY_FACT_MISSING);
        if (missingFactors > 0) {
            reasons.add(ExclusionReason.ADJUSTMENT_FACTOR_MISSING);
        }
        LocalDate anchor = sessions.get(sessions.size() - 1);
        if (!raw.containsKey(anchor)) {
            reasons.add(ExclusionReason.SUSPENDED_OR_NO_TRADE);
        }
        int available = trailingAvailable(sessions, raw, factors);
        if (available < ResearchUniverseMainboard.BASIC_MINIMUM_SESSIONS) {
            reasons.add(ExclusionReason.TWENTY_SESSION_HISTORY_INSUFFICIENT);
        }
        List<RawDailyBarObservation> recent = required.stream()
                .map(raw::get).filter(Objects::nonNull).toList();
        boolean future = recent.stream().anyMatch(value ->
                value.envelope().knownAt().isAfter(cutoff))
                || required.stream().map(factors::get)
                .filter(Objects::nonNull).anyMatch(value ->
                        value.envelope().knownAt().isAfter(cutoff));
        if (future) reasons.add(ExclusionReason.FUTURE_DATA_GUARD_FAILED);
        boolean anomaly = recent.stream().anyMatch(value ->
                value.open().signum() <= 0 || value.high().signum() <= 0
                        || value.low().signum() <= 0
                        || value.close().signum() <= 0
                        || value.high().compareTo(value.low()) < 0
                        || value.high().compareTo(value.open()) < 0
                        || value.high().compareTo(value.close()) < 0
                        || value.low().compareTo(value.open()) > 0
                        || value.low().compareTo(value.close()) > 0
                        || field(value.volume()).signum() < 0);
        if (anomaly) reasons.add(ExclusionReason.PRICE_OR_VOLUME_ANOMALY);
        BigDecimal averageAmount = average(recent.stream().map(value ->
                field(value.amount())).toList());
        if (recent.size() >= ResearchUniverseMainboard.BASIC_MINIMUM_SESSIONS
                && averageAmount.compareTo(ResearchUniverseMainboard
                .MINIMUM_AVERAGE_TRADED_AMOUNT) < 0) {
            reasons.add(ExclusionReason.EXTREMELY_LOW_LIQUIDITY);
        }
        List<DailyBar> bars = reasons.isEmpty()
                ? qfq(member.security(), sessions, raw, factors, available)
                : List.of();
        List<PriceBar> priceBars = reasons.isEmpty()
                ? tradePlanPrices(sessions, raw, factors, available)
                : List.of();
        if (reasons.isEmpty() && bars.size()
                < ResearchUniverseMainboard.BASIC_MINIMUM_SESSIONS) {
            reasons.add(ExclusionReason.DATA_QUALITY_FAILED);
            bars = List.of();
            priceBars = List.of();
        }
        EligibilityStatus status = reasons.isEmpty()
                ? EligibilityStatus.ELIGIBLE : EligibilityStatus.EXCLUDED;
        return new MemberProjection(new MemberEvaluation(member, status,
                List.copyOf(reasons), available, missingDaily,
                missingFactors, averageAmount, null, null, null, null, null,
                null, false, false), bars, priceBars);
    }

    private static List<PriceBar> tradePlanPrices(
            List<LocalDate> sessions,
            Map<LocalDate, RawDailyBarObservation> raw,
            Map<LocalDate, AdjustmentFactorObservation> factors,
            int available
    ) {
        int count = Math.min(15, available);
        int start = sessions.size() - count;
        List<PriceBar> result = new ArrayList<>();
        for (LocalDate date : sessions.subList(start, sessions.size())) {
            RawDailyBarObservation daily = raw.get(date);
            AdjustmentFactorObservation factor = factors.get(date);
            if (daily == null || factor == null) return List.of();
            result.add(new PriceBar(date, daily.open(), daily.high(),
                    daily.low(), daily.close(), factor.factor(),
                    daily.envelope().canonicalContentHash(),
                    factor.envelope().canonicalContentHash()));
        }
        return List.copyOf(result);
    }

    private static List<DailyBar> qfq(
            Security security,
            List<LocalDate> sessions,
            Map<LocalDate, RawDailyBarObservation> raw,
            Map<LocalDate, AdjustmentFactorObservation> factors,
            int available
    ) {
        if (available <= 0) return List.of();
        int start = sessions.size() - available;
        LocalDate anchor = sessions.get(sessions.size() - 1);
        BigDecimal anchorFactor = factors.get(anchor).factor();
        if (anchorFactor == null || anchorFactor.signum() <= 0) {
            return List.of();
        }
        List<DailyBar> result = new ArrayList<>();
        for (LocalDate date : sessions.subList(start, sessions.size())) {
            RawDailyBarObservation source = raw.get(date);
            AdjustmentFactorObservation factor = factors.get(date);
            if (source == null || factor == null
                    || factor.factor().signum() <= 0) break;
            BigDecimal ratio = factor.factor().divide(anchorFactor, 18,
                    RoundingMode.HALF_EVEN);
            result.add(new DailyBar(security, date,
                    source.open().multiply(ratio),
                    source.high().multiply(ratio),
                    source.low().multiply(ratio),
                    source.close().multiply(ratio),
                    field(source.volume()).min(BigDecimal.valueOf(
                            Long.MAX_VALUE)).longValue(), true,
                    StrategyResearchModels.closeInstant(date),
                    later(source.envelope().knownAt(),
                            factor.envelope().knownAt())));
        }
        return List.copyOf(result);
    }

    private List<LocalDate> commonOpenDates(
            LocalDate anchor,
            Instant cutoff
    ) {
        LocalDate start = anchor.minusDays(CALENDAR_LOOKBACK_DAYS);
        Set<LocalDate> common = null;
        for (String exchange : List.of("SSE", "SZSE")) {
            Set<LocalDate> open = facts.findCalendarAsOf(
                            TushareMarketFactProvider.PROVIDER_CODE,
                            TushareMarketFactProvider
                                    .calendarSourceIdentity(exchange),
                            exchange, start, anchor, cutoff).stream()
                    .filter(TradingCalendarObservation::open)
                    .map(TradingCalendarObservation::calendarDate)
                    .collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new));
            if (common == null) common = open;
            else common.retainAll(open);
        }
        return common == null ? List.of() : common.stream().sorted().toList();
    }

    private static Map<String, Map<LocalDate, RawDailyBarObservation>>
    groupRaw(List<RawDailyBarObservation> values) {
        Map<String, Map<LocalDate, RawDailyBarObservation>> result =
                new LinkedHashMap<>();
        for (RawDailyBarObservation value : values) {
            String key = new Security(value.symbol(), value.exchange())
                    .canonicalCode();
            RawDailyBarObservation previous = result.computeIfAbsent(key,
                    ignored -> new LinkedHashMap<>()).put(value.tradeDate(),
                    value);
            if (previous != null) throw invalid(
                    "MAINBOARD_DAILY_DUPLICATE");
        }
        return result;
    }

    private static Map<String, Map<LocalDate, AdjustmentFactorObservation>>
    groupFactors(List<AdjustmentFactorObservation> values) {
        Map<String, Map<LocalDate, AdjustmentFactorObservation>> result =
                new LinkedHashMap<>();
        for (AdjustmentFactorObservation value : values) {
            AdjustmentFactorObservation previous = result.computeIfAbsent(
                    value.symbol(), ignored -> new LinkedHashMap<>()).put(
                    value.factorEffectiveTradeDate(), value);
            if (previous != null) throw invalid(
                    "MAINBOARD_FACTOR_DUPLICATE");
        }
        return result;
    }

    private static Map<LocalDate, Set<String>> identitiesByRawDate(
            List<RawDailyBarObservation> values
    ) {
        Map<LocalDate, Set<String>> result = new LinkedHashMap<>();
        values.forEach(value -> result.computeIfAbsent(value.tradeDate(),
                ignored -> new LinkedHashSet<>()).add(new Security(
                value.symbol(), value.exchange()).canonicalCode()));
        return result;
    }

    private static Map<LocalDate, Set<String>> identitiesByFactorDate(
            List<AdjustmentFactorObservation> values,
            List<Member> members
    ) {
        Map<String, String> exchanges = members.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Member::symbol,
                        Member::exchange, (left, right) -> left));
        Map<LocalDate, Set<String>> result = new LinkedHashMap<>();
        values.forEach(value -> result.computeIfAbsent(
                value.factorEffectiveTradeDate(), ignored ->
                new LinkedHashSet<>()).add(new Security(value.symbol(),
                exchanges.get(value.symbol())).canonicalCode()));
        return result;
    }

    private static long activeMembers(List<Member> members, LocalDate date) {
        return members.stream().filter(value ->
                !value.listDate().isAfter(date)
                        && (value.delistDate() == null
                        || !value.delistDate().isBefore(date))).count();
    }

    private static int trailingAvailable(
            List<LocalDate> sessions,
            Map<LocalDate, RawDailyBarObservation> raw,
            Map<LocalDate, AdjustmentFactorObservation> factors
    ) {
        int count = 0;
        for (int index = sessions.size() - 1; index >= 0; index--) {
            LocalDate date = sessions.get(index);
            if (!raw.containsKey(date) || !factors.containsKey(date)) break;
            count++;
        }
        return count;
    }

    private static BigDecimal field(QualifiedMarketField value) {
        return value == null || value.value() == null
                ? BigDecimal.ZERO : value.value();
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8,
                        RoundingMode.HALF_EVEN);
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static String lineageFingerprint(List<DailyBar> bars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (DailyBar value : bars) {
                digest.update((value.security().canonicalCode() + '|'
                        + value.tradeDate() + '|' + value.sourceKnownAt()
                        + '\n').getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "MAINBOARD_SHA256_UNAVAILABLE", error);
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    public record LoadedMainboard(
            SnapshotBundle snapshot,
            ResearchDataset dataset,
            DataCoverage coverage,
            List<MemberEvaluation> evaluations,
            List<LocalDate> sessions,
            Map<Security, List<PriceBar>> tradePlanPrices
    ) {
        public LoadedMainboard {
            evaluations = List.copyOf(evaluations);
            sessions = List.copyOf(sessions);
            Map<Security, List<PriceBar>> copied = new LinkedHashMap<>();
            tradePlanPrices.forEach((security, values) -> copied.put(
                    security, List.copyOf(values)));
            tradePlanPrices = java.util.Collections.unmodifiableMap(copied);
        }
    }

    public record Audit(
            List<LocalDate> requiredTradeDates,
            List<LocalDate> missingTradeDates,
            boolean calendarIncomplete,
            boolean refreshStockBasic,
            int existingSecurityCount
    ) {
        public Audit {
            requiredTradeDates = List.copyOf(requiredTradeDates);
            missingTradeDates = List.copyOf(missingTradeDates);
        }
    }

    record MemberProjection(
            MemberEvaluation evaluation,
            List<DailyBar> bars,
            List<PriceBar> priceBars
    ) {
    }
}
