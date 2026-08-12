package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.FormulaOnlyQfqBar;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.ResearchDataset;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.SecurityDataset;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * Strict cutoff loader for M4 replay/live-shadow reads. Unlike the M1 capture
 * readback, it permits a partial tail when facts after the cutoff are not yet
 * known and proves every returned typed fact was known at or before the cutoff.
 */
public final class TushareM1AsOfDatasetLoader {
    private static final String PROVIDER =
            TushareMarketFactProvider.PROVIDER_CODE;

    private final PitMarketFactRepository repository;

    public TushareM1AsOfDatasetLoader(PitMarketFactRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public ResearchDataset load(
            List<SecuritySelection> securities,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            Instant knowledgeCutoff
    ) {
        if (securities == null || securities.isEmpty()
                || securities.size() > 20
                || requestedStart == null || requestedEnd == null
                || requestedEnd.isBefore(requestedStart)
                || knowledgeCutoff == null) {
            throw invalid("M4_AS_OF_REQUEST_INVALID");
        }
        List<SecurityDataset> loaded = new ArrayList<>();
        LocalDate commonEnd = null;
        for (SecuritySelection security : securities.stream()
                .sorted(Comparator.comparing(SecuritySelection::exchange)
                        .thenComparing(SecuritySelection::symbol)).toList()) {
            SecurityDataset dataset = loadSecurity(security, requestedStart,
                    requestedEnd, knowledgeCutoff);
            commonEnd = commonEnd == null ? dataset.rangeEnd()
                    : commonEnd.isBefore(dataset.rangeEnd())
                    ? commonEnd : dataset.rangeEnd();
            loaded.add(dataset);
        }
        LocalDate end = Objects.requireNonNull(commonEnd, "commonEnd");
        List<SecurityDataset> aligned = loaded.stream()
                .map(value -> value.rangeEnd().equals(end) ? value
                        : loadSecurity(new SecuritySelection(value.symbol(),
                        value.exchange()), requestedStart, end,
                        knowledgeCutoff)).toList();
        int raw = aligned.stream().mapToInt(SecurityDataset::rawDailyCount).sum();
        int factors = aligned.stream().mapToInt(
                SecurityDataset::adjustmentFactorCount).sum();
        int calendars = aligned.stream().mapToInt(
                SecurityDataset::calendarCount).sum();
        int qfq = aligned.stream().mapToInt(
                value -> value.qfqBars().size()).sum();
        return new ResearchDataset("M1_RESEARCH_DATASET_V1", knowledgeCutoff,
                requestedStart, end, end, aligned, raw, factors, calendars,
                qfq, true, false, true, true, true, true, true);
    }

    private SecurityDataset loadSecurity(
            SecuritySelection security,
            LocalDate start,
            LocalDate end,
            Instant cutoff
    ) {
        String rawIdentity = TushareMarketFactProvider.rawSourceIdentity(
                security.symbol(), security.exchange());
        String factorIdentity = TushareMarketFactProvider.factorSourceIdentity(
                security.symbol(), security.exchange());
        String calendarIdentity = TushareMarketFactProvider
                .calendarSourceIdentity(security.exchange());
        List<RawDailyBarObservation> raw = repository.findRawBarsWindowAsOf(
                PROVIDER, rawIdentity, security.symbol(), security.exchange(),
                start, end, cutoff);
        List<AdjustmentFactorObservation> factors = repository.findFactorsAsOf(
                PROVIDER, factorIdentity, security.symbol(), start, end, cutoff);
        List<TradingCalendarObservation> calendar = repository.findCalendarAsOf(
                PROVIDER, calendarIdentity, security.exchange(), start, end,
                cutoff);
        if (raw.isEmpty() || factors.isEmpty() || calendar.isEmpty()) {
            throw invalid("M4_AS_OF_DATASET_INSUFFICIENT");
        }
        requireCutoff(raw.stream().map(value -> value.envelope().knownAt())
                .toList(), cutoff);
        requireCutoff(factors.stream().map(value -> value.envelope().knownAt())
                .toList(), cutoff);
        requireCutoff(calendar.stream().map(value -> value.envelope().knownAt())
                .toList(), cutoff);
        LocalDate effectiveEnd = raw.stream().map(
                RawDailyBarObservation::tradeDate).max(LocalDate::compareTo)
                .orElseThrow();
        if (effectiveEnd.isAfter(end)) {
            throw invalid("M4_AS_OF_FUTURE_DATE_LEAKAGE");
        }
        Map<LocalDate, RawDailyBarObservation> rawByDate = uniqueRaw(raw)
                .entrySet().stream()
                .filter(value -> !value.getKey().isAfter(effectiveEnd))
                .collect(LinkedHashMap::new,
                        (target, value) -> target.put(value.getKey(),
                                value.getValue()), Map::putAll);
        Map<LocalDate, AdjustmentFactorObservation> factorByDate =
                uniqueFactors(factors).entrySet().stream()
                        .filter(value -> !value.getKey().isAfter(effectiveEnd))
                        .collect(LinkedHashMap::new,
                                (target, value) -> target.put(value.getKey(),
                                        value.getValue()), Map::putAll);
        Map<LocalDate, TradingCalendarObservation> calendarByDate =
                uniqueCalendar(calendar).entrySet().stream()
                        .filter(value -> !value.getKey().isAfter(effectiveEnd))
                        .collect(LinkedHashMap::new,
                                (target, value) -> target.put(value.getKey(),
                                        value.getValue()), Map::putAll);
        Set<LocalDate> expectedDates = new LinkedHashSet<>();
        for (LocalDate date = start; !date.isAfter(effectiveEnd);
                date = date.plusDays(1)) {
            expectedDates.add(date);
        }
        if (!calendarByDate.keySet().equals(expectedDates)) {
            throw invalid("M4_AS_OF_CALENDAR_INCOMPLETE");
        }
        Set<LocalDate> openDates = new LinkedHashSet<>();
        calendarByDate.values().stream().filter(
                TradingCalendarObservation::open).forEach(value ->
                openDates.add(value.calendarDate()));
        if (!rawByDate.keySet().equals(openDates)
                || !factorByDate.keySet().equals(openDates)) {
            throw invalid("M4_AS_OF_FACT_WINDOW_INCOMPLETE");
        }
        BigDecimal anchor = factorByDate.get(effectiveEnd).factor();
        List<FormulaOnlyQfqBar> qfq = new ArrayList<>();
        for (LocalDate date : openDates) {
            RawDailyBarObservation bar = rawByDate.get(date);
            AdjustmentFactorObservation factor = factorByDate.get(date);
            BigDecimal ratio = factor.factor().divide(anchor, 18,
                    RoundingMode.HALF_EVEN);
            qfq.add(new FormulaOnlyQfqBar(date,
                    bar.open().multiply(ratio), bar.high().multiply(ratio),
                    bar.low().multiply(ratio), bar.close().multiply(ratio),
                    bar.envelope().id(), factor.envelope().id()));
        }
        Instant firstKnown = allKnown(raw, factors, calendar).stream()
                .min(Instant::compareTo).orElseThrow();
        Instant lastKnown = allKnown(raw, factors, calendar).stream()
                .max(Instant::compareTo).orElseThrow();
        return new SecurityDataset(security.symbol(), security.exchange(),
                TushareMarketFactProvider.sourceInstrumentId(
                        security.symbol(), security.exchange()),
                start, effectiveEnd, effectiveEnd, raw.size(), factors.size(),
                calendarByDate.size(), openDates.size(),
                calendarByDate.size() - openDates.size(), qfq,
                firstKnown, lastKnown, true, true, true, true);
    }

    private static Map<LocalDate, RawDailyBarObservation> uniqueRaw(
            List<RawDailyBarObservation> values
    ) {
        Map<LocalDate, RawDailyBarObservation> result = new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(
                RawDailyBarObservation::tradeDate)).forEach(value -> {
            if (result.put(value.tradeDate(), value) != null) {
                throw invalid("M4_AS_OF_DUPLICATE_DAILY");
            }
        });
        return result;
    }

    private static Map<LocalDate, AdjustmentFactorObservation> uniqueFactors(
            List<AdjustmentFactorObservation> values
    ) {
        Map<LocalDate, AdjustmentFactorObservation> result =
                new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(
                AdjustmentFactorObservation::factorEffectiveTradeDate))
                .forEach(value -> {
                    if (result.put(value.factorEffectiveTradeDate(),
                            value) != null) {
                        throw invalid("M4_AS_OF_DUPLICATE_FACTOR");
                    }
                });
        return result;
    }

    private static Map<LocalDate, TradingCalendarObservation> uniqueCalendar(
            List<TradingCalendarObservation> values
    ) {
        Map<LocalDate, TradingCalendarObservation> result =
                new LinkedHashMap<>();
        values.stream().sorted(Comparator.comparing(
                TradingCalendarObservation::calendarDate)).forEach(value -> {
            if (result.put(value.calendarDate(), value) != null) {
                throw invalid("M4_AS_OF_DUPLICATE_CALENDAR");
            }
        });
        return result;
    }

    private static List<Instant> allKnown(
            List<RawDailyBarObservation> raw,
            List<AdjustmentFactorObservation> factors,
            List<TradingCalendarObservation> calendar
    ) {
        List<Instant> values = new ArrayList<>();
        raw.forEach(value -> values.add(value.envelope().knownAt()));
        factors.forEach(value -> values.add(value.envelope().knownAt()));
        calendar.forEach(value -> values.add(value.envelope().knownAt()));
        return values;
    }

    private static void requireCutoff(List<Instant> values, Instant cutoff) {
        if (values.stream().anyMatch(value -> value.isAfter(cutoff))) {
            throw invalid("M4_AS_OF_KNOWLEDGE_LEAKAGE");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
