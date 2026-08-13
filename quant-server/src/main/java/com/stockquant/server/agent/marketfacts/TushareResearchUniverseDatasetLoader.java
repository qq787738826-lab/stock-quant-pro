package com.stockquant.server.agent.marketfacts;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.researchselection.ResearchSelectionModels.DataCoverage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Current-as-of M1 projection for a 20-30 security universe.  It preserves
 * knownAt and formula-only QFQ but does not pretend later observations were
 * known on historical dates.
 */
public final class TushareResearchUniverseDatasetLoader {
    private final PitMarketFactRepository facts;

    public TushareResearchUniverseDatasetLoader(PitMarketFactRepository facts) {
        this.facts = Objects.requireNonNull(facts, "facts");
    }

    public LoadedUniverse load(
            List<Security> securities,
            int openSessionWindow,
            LocalDate anchor,
            Instant cutoff
    ) {
        if (securities == null || securities.size() < 3
                || securities.size() > 30 || openSessionWindow < 20
                || openSessionWindow > 250 || anchor == null
                || cutoff == null) {
            throw invalid("RESEARCH_UNIVERSE_DATASET_REQUEST_INVALID");
        }
        long lookback = Math.min(
                TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_MAX_MARKET_FACT_NATURAL_DAYS - 1L,
                Math.max(45L, (openSessionWindow * 8L + 4L) / 5L));
        LocalDate searchStart = anchor.minusDays(lookback);
        Map<String, List<TradingCalendarObservation>> calendars =
                new LinkedHashMap<>();
        for (String exchange : securities.stream().map(Security::exchange)
                .distinct().sorted().toList()) {
            List<TradingCalendarObservation> values = facts.findCalendarAsOf(
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.calendarSourceIdentity(exchange),
                    exchange, searchStart, anchor, cutoff);
            calendars.put(exchange, values);
        }
        List<LocalDate> commonOpen = commonOpenDates(calendars, anchor);
        if (commonOpen.size() < openSessionWindow) {
            throw invalid("RESEARCH_UNIVERSE_CALENDAR_WINDOW_INCOMPLETE");
        }
        List<LocalDate> selected = commonOpen.subList(
                commonOpen.size() - openSessionWindow, commonOpen.size());
        LocalDate rangeStart = selected.get(0);
        LocalDate rangeEnd = selected.get(selected.size() - 1);
        Set<LocalDate> selectedSet = Set.copyOf(selected);
        List<DailyBar> bars = new ArrayList<>();
        int complete = 0;
        int missingDaily = 0;
        int missingFactors = 0;
        boolean incrementalAnchorOnly = true;
        StringBuilder lineage = new StringBuilder();
        for (Security security : securities.stream().sorted().toList()) {
            List<RawDailyBarObservation> raw = facts.findRawBarsWindowAsOf(
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.rawSourceIdentity(
                            security.symbol(), security.exchange()),
                    security.symbol(), security.exchange(), rangeStart,
                    rangeEnd, cutoff);
            List<AdjustmentFactorObservation> factors = facts.findFactorsAsOf(
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.factorSourceIdentity(
                            security.symbol(), security.exchange()),
                    security.symbol(), rangeStart, rangeEnd, cutoff);
            Map<LocalDate, RawDailyBarObservation> rawByDate = uniqueRaw(raw);
            Map<LocalDate, AdjustmentFactorObservation> factorByDate =
                    uniqueFactors(factors);
            Set<LocalDate> missingRawDates = difference(selectedSet,
                    rawByDate.keySet());
            Set<LocalDate> missingFactorDates = difference(selectedSet,
                    factorByDate.keySet());
            missingDaily += missingRawDates.size();
            missingFactors += missingFactorDates.size();
            incrementalAnchorOnly &= missingRawDates.equals(Set.of(rangeEnd))
                    && missingFactorDates.equals(Set.of(rangeEnd))
                    && selectedSet.containsAll(rawByDate.keySet())
                    && selectedSet.containsAll(factorByDate.keySet());
            if (!rawByDate.keySet().equals(selectedSet)
                    || !factorByDate.keySet().equals(selectedSet)) {
                continue;
            }
            BigDecimal anchorFactor = factorByDate.get(rangeEnd).factor();
            if (anchorFactor == null || anchorFactor.signum() <= 0) {
                continue;
            }
            boolean valid = true;
            List<DailyBar> securityBars = new ArrayList<>();
            for (LocalDate date : selected) {
                RawDailyBarObservation rawBar = rawByDate.get(date);
                AdjustmentFactorObservation factor = factorByDate.get(date);
                if (rawBar.envelope().knownAt().isAfter(cutoff)
                        || factor.envelope().knownAt().isAfter(cutoff)
                        || factor.factor().signum() <= 0) {
                    valid = false;
                    break;
                }
                BigDecimal ratio = factor.factor().divide(anchorFactor, 18,
                        RoundingMode.HALF_EVEN);
                securityBars.add(new DailyBar(security, date,
                        rawBar.open().multiply(ratio),
                        rawBar.high().multiply(ratio),
                        rawBar.low().multiply(ratio),
                        rawBar.close().multiply(ratio), volume(rawBar), true,
                        StrategyResearchModels.closeInstant(date),
                        later(rawBar.envelope().knownAt(),
                                factor.envelope().knownAt())));
                lineage.append(security.canonicalCode()).append('|')
                        .append(date).append('|')
                        .append(rawBar.envelope().id()).append('|')
                        .append(factor.envelope().id()).append('\n');
            }
            if (valid) {
                bars.addAll(securityBars);
                complete++;
            }
        }
        if (complete != securities.size()) {
            throw new IncompleteUniverseException(new DataCoverage(rangeStart,
                    rangeEnd, openSessionWindow, selected.size(),
                    securities.size(), complete, missingDaily,
                    missingFactors, true, true, true, true, true),
                    incrementalAnchorOnly);
        }
        List<TradingSession> sessions = selected.stream()
                .map(date -> new TradingSession(date,
                        new LinkedHashSet<>(calendars.keySet()))).toList();
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT,
                "RESEARCH_UNIVERSE_" + sha256(lineage.toString()),
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, cutoff,
                sessions, bars);
        DataCoverage coverage = new DataCoverage(rangeStart, rangeEnd,
                openSessionWindow, selected.size(), securities.size(),
                complete, 0, 0, true, true, true, true, true);
        return new LoadedUniverse(dataset, coverage);
    }

    /** Latest common open session already present in trusted calendar facts. */
    public LocalDate latestCommonOpenDate(
            List<Security> securities,
            LocalDate notAfter,
            Instant cutoff
    ) {
        if (securities == null || securities.isEmpty() || notAfter == null
                || cutoff == null) {
            throw invalid("RESEARCH_UNIVERSE_ANCHOR_REQUEST_INVALID");
        }
        Map<String, List<TradingCalendarObservation>> calendars =
                new LinkedHashMap<>();
        LocalDate start = notAfter.minusDays(45);
        for (String exchange : securities.stream().map(Security::exchange)
                .distinct().sorted().toList()) {
            calendars.put(exchange, facts.findCalendarAsOf(
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.calendarSourceIdentity(exchange),
                    exchange, start, notAfter, cutoff));
        }
        LocalDate latestClosed = cutoff.isBefore(
                StrategyResearchModels.closeInstant(notAfter))
                ? notAfter.minusDays(1) : notAfter;
        return commonOpenDates(calendars, latestClosed).stream().reduce(
                (left, right) -> right).orElseThrow(() -> invalid(
                "RESEARCH_UNIVERSE_COMMON_OPEN_SESSION_MISSING"));
    }

    private static List<LocalDate> commonOpenDates(
            Map<String, List<TradingCalendarObservation>> calendars,
            LocalDate anchor
    ) {
        Set<LocalDate> common = null;
        for (List<TradingCalendarObservation> values : calendars.values()) {
            Set<LocalDate> open = values.stream().filter(
                            TradingCalendarObservation::open)
                    .map(TradingCalendarObservation::calendarDate)
                    .filter(date -> !date.isAfter(anchor))
                    .collect(java.util.stream.Collectors.toCollection(
                            LinkedHashSet::new));
            common = common == null ? open : intersection(common, open);
        }
        if (common == null) {
            return List.of();
        }
        return common.stream().sorted().toList();
    }

    private static Set<LocalDate> intersection(
            Set<LocalDate> left,
            Set<LocalDate> right
    ) {
        Set<LocalDate> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private static Set<LocalDate> difference(
            Set<LocalDate> expected,
            Set<LocalDate> actual
    ) {
        Set<LocalDate> result = new LinkedHashSet<>(expected);
        result.removeAll(actual);
        return Set.copyOf(result);
    }

    private static Map<LocalDate, RawDailyBarObservation> uniqueRaw(
            List<RawDailyBarObservation> values
    ) {
        Map<LocalDate, RawDailyBarObservation> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(
                RawDailyBarObservation::tradeDate)).forEach(value -> {
            if (result.put(value.tradeDate(), value) != null) {
                throw invalid("RESEARCH_UNIVERSE_DAILY_DUPLICATE");
            }
        });
        return Map.copyOf(result);
    }

    private static Map<LocalDate, AdjustmentFactorObservation> uniqueFactors(
            List<AdjustmentFactorObservation> values
    ) {
        Map<LocalDate, AdjustmentFactorObservation> result =
                new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(
                        AdjustmentFactorObservation::factorEffectiveTradeDate))
                .forEach(value -> {
                    if (result.put(value.factorEffectiveTradeDate(), value)
                            != null) {
                        throw invalid("RESEARCH_UNIVERSE_FACTOR_DUPLICATE");
                    }
                });
        return Map.copyOf(result);
    }

    private static long volume(RawDailyBarObservation bar) {
        if (bar.volume() == null || bar.volume().value() == null) {
            return 0;
        }
        try {
            return bar.volume().value().longValueExact();
        } catch (ArithmeticException error) {
            return Long.MAX_VALUE;
        }
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "RESEARCH_UNIVERSE_SHA256_UNAVAILABLE", error);
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    public record LoadedUniverse(
            ResearchDataset dataset,
            DataCoverage coverage
    ) {
    }

    public static final class IncompleteUniverseException
            extends IllegalStateException {
        private final DataCoverage coverage;
        private final boolean incrementalAnchorOnly;

        IncompleteUniverseException(
                DataCoverage coverage,
                boolean incrementalAnchorOnly
        ) {
            super("RESEARCH_UNIVERSE_DATA_INCOMPLETE");
            this.coverage = coverage;
            this.incrementalAnchorOnly = incrementalAnchorOnly;
        }

        public DataCoverage coverage() {
            return coverage;
        }

        public boolean incrementalAnchorOnly() {
            return incrementalAnchorOnly;
        }
    }
}
