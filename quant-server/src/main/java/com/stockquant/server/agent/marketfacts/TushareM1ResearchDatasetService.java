package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactEnvelope;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.FormulaOnlyQfqBar;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.ResearchDataset;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.SecurityDataset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
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
 * Cutoff-aware, typed M1 projection over the append-only V13 research facts.
 *
 * <p>This is a research-only dataset contract. It preserves local
 * SYSTEM_KNOWLEDGE semantics and formula-only QFQ; it does not upgrade the
 * facts to provider PIT or claim complete corporate-action lineage.</p>
 */
public final class TushareM1ResearchDatasetService {
    private final PitMarketFactRepository repository;
    private final JdbcTemplate jdbc;

    public TushareM1ResearchDatasetService(
            PitMarketFactRepository repository,
            JdbcTemplate jdbc
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public ResearchDataset loadAndVerify(
            TushareM1ResearchWindowCommand command,
            Instant knowledgeCutoff
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw invalid("TUSHARE_M1_POST_COMMIT_READBACK_REQUIRED");
        }
        List<SecurityDataset> datasets = new ArrayList<>();
        for (SecuritySelection security : command.securities()) {
            datasets.add(loadSecurity(command, security, knowledgeCutoff));
        }
        int raw = datasets.stream().mapToInt(
                SecurityDataset::rawDailyCount).sum();
        int factors = datasets.stream().mapToInt(
                SecurityDataset::adjustmentFactorCount).sum();
        int calendars = datasets.stream().mapToInt(
                SecurityDataset::calendarCount).sum();
        int qfq = datasets.stream().mapToInt(
                value -> value.qfqBars().size()).sum();
        return new ResearchDataset(
                "M1_RESEARCH_DATASET_V1", knowledgeCutoff,
                command.rangeStart(), command.rangeEnd(),
                command.anchorTradeDate(), datasets,
                raw, factors, calendars, qfq,
                true, false, true, true, true, true, true);
    }

    public boolean verifyCurrentBatchReferences(
            List<CaptureResult> captureResults
    ) {
        List<CaptureResult> results = List.copyOf(Objects.requireNonNull(
                captureResults, "captureResults"));
        if (results.isEmpty()) {
            return false;
        }
        for (CaptureResult result : results) {
            if (result == null || !result.complete()
                    || !verifyBatchReferences(result)) {
                return false;
            }
        }
        return true;
    }

    private SecurityDataset loadSecurity(
            TushareM1ResearchWindowCommand command,
            SecuritySelection security,
            Instant cutoff
    ) {
        String rawIdentity = TushareMarketFactProvider.rawSourceIdentity(
                security.symbol(), security.exchange());
        String factorIdentity = TushareMarketFactProvider.factorSourceIdentity(
                security.symbol(), security.exchange());
        String calendarIdentity = TushareMarketFactProvider
                .calendarSourceIdentity(security.exchange());
        List<RawDailyBarObservation> raw = repository.findRawBarsWindowAsOf(
                TushareMarketFactProvider.PROVIDER_CODE, rawIdentity,
                security.symbol(), security.exchange(), command.rangeStart(),
                command.rangeEnd(), cutoff);
        List<AdjustmentFactorObservation> factors = repository.findFactorsAsOf(
                TushareMarketFactProvider.PROVIDER_CODE, factorIdentity,
                security.symbol(), command.rangeStart(), command.rangeEnd(),
                cutoff);
        List<TradingCalendarObservation> calendar = repository.findCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE, calendarIdentity,
                security.exchange(), command.rangeStart(), command.rangeEnd(),
                cutoff);

        Map<LocalDate, RawDailyBarObservation> rawByDate = uniqueRaw(
                raw, security, rawIdentity, cutoff);
        Map<LocalDate, AdjustmentFactorObservation> factorByDate = uniqueFactors(
                factors, security, factorIdentity, cutoff);
        Map<LocalDate, TradingCalendarObservation> calendarByDate =
                uniqueCalendar(calendar, security, calendarIdentity, cutoff);
        Set<LocalDate> expectedNaturalDates = naturalDates(
                command.rangeStart(), command.rangeEnd());
        if (!calendarByDate.keySet().equals(expectedNaturalDates)) {
            throw invalid("TUSHARE_M1_DATASET_CALENDAR_INCOMPLETE");
        }
        Set<LocalDate> openDates = new LinkedHashSet<>();
        for (TradingCalendarObservation value : calendar) {
            if (!Objects.equals(value.sessionCode(),
                    value.open() ? "REGULAR" : "CLOSED")) {
                throw invalid("TUSHARE_M1_DATASET_CALENDAR_SESSION_INVALID");
            }
            if (value.open()) {
                openDates.add(value.calendarDate());
            }
        }
        if (openDates.isEmpty()
                || !rawByDate.keySet().equals(openDates)
                || !factorByDate.keySet().equals(openDates)) {
            throw invalid("TUSHARE_M1_DATASET_FACT_WINDOW_INCOMPLETE");
        }
        LocalDate anchor = openDates.stream().max(LocalDate::compareTo)
                .orElseThrow();
        if (!anchor.equals(command.anchorTradeDate())) {
            throw invalid("TUSHARE_M1_DATASET_ANCHOR_INVALID");
        }
        BigDecimal anchorFactor = factorByDate.get(anchor).factor();
        if (anchorFactor == null || anchorFactor.signum() <= 0) {
            throw invalid("TUSHARE_M1_DATASET_ANCHOR_FACTOR_INVALID");
        }
        List<FormulaOnlyQfqBar> qfq = raw.stream()
                .sorted(Comparator.comparing(RawDailyBarObservation::tradeDate))
                .map(value -> {
                    AdjustmentFactorObservation factor = factorByDate.get(
                            value.tradeDate());
                    return new FormulaOnlyQfqBar(
                            value.tradeDate(),
                            QfqPriceMath.calculate(value.open(), factor.factor(),
                                    anchorFactor),
                            QfqPriceMath.calculate(value.high(), factor.factor(),
                                    anchorFactor),
                            QfqPriceMath.calculate(value.low(), factor.factor(),
                                    anchorFactor),
                            QfqPriceMath.calculate(value.close(), factor.factor(),
                                    anchorFactor),
                            value.envelope().id(), factor.envelope().id());
                }).toList();
        List<FactEnvelope> envelopes = new ArrayList<>();
        raw.forEach(value -> envelopes.add(value.envelope()));
        factors.forEach(value -> envelopes.add(value.envelope()));
        calendar.forEach(value -> envelopes.add(value.envelope()));
        Instant firstKnown = envelopes.stream().map(FactEnvelope::knownAt)
                .min(Instant::compareTo).orElseThrow();
        Instant lastKnown = envelopes.stream().map(FactEnvelope::knownAt)
                .max(Instant::compareTo).orElseThrow();
        boolean noFuture = envelopes.stream().allMatch(value ->
                !value.knownAt().isAfter(cutoff)
                        && !value.firstObservedAt().isAfter(value.knownAt()))
                && raw.stream().allMatch(value ->
                !value.tradeDate().isAfter(anchor))
                && factors.stream().allMatch(value ->
                !value.factorEffectiveTradeDate().isAfter(anchor));
        if (!noFuture) {
            throw invalid("TUSHARE_M1_DATASET_FUTURE_DATA_LEAKAGE");
        }
        return new SecurityDataset(
                security.symbol(), security.exchange(), rawIdentity,
                command.rangeStart(), command.rangeEnd(), anchor,
                raw.size(), factors.size(), calendar.size(), openDates.size(),
                calendar.size() - openDates.size(), qfq,
                firstKnown, lastKnown, true, true, true, true);
    }

    private static Map<LocalDate, RawDailyBarObservation> uniqueRaw(
            List<RawDailyBarObservation> values,
            SecuritySelection security,
            String identity,
            Instant cutoff
    ) {
        Map<LocalDate, RawDailyBarObservation> result = new LinkedHashMap<>();
        for (RawDailyBarObservation value : values) {
            if (!security.symbol().equals(value.symbol())
                    || !security.exchange().equals(value.exchange())
                    || !validEnvelope(value.envelope(), identity, cutoff)
                    || !positive(value.open()) || !positive(value.high())
                    || !positive(value.low()) || !positive(value.close())
                    || value.high().compareTo(value.open()) < 0
                    || value.high().compareTo(value.low()) < 0
                    || value.high().compareTo(value.close()) < 0
                    || value.low().compareTo(value.open()) > 0
                    || value.low().compareTo(value.high()) > 0
                    || value.low().compareTo(value.close()) > 0
                    || result.put(value.tradeDate(), value) != null) {
                throw invalid("TUSHARE_M1_DATASET_RAW_DAILY_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<LocalDate, AdjustmentFactorObservation> uniqueFactors(
            List<AdjustmentFactorObservation> values,
            SecuritySelection security,
            String identity,
            Instant cutoff
    ) {
        Map<LocalDate, AdjustmentFactorObservation> result =
                new LinkedHashMap<>();
        for (AdjustmentFactorObservation value : values) {
            if (!security.symbol().equals(value.symbol())
                    || !PitMarketFactsContracts.FACTOR_TYPE.equals(
                    value.factorType())
                    || !PitMarketFactsContracts.FACTOR_COVERAGE_MODE.equals(
                    value.coverageMode())
                    || !positive(value.factor())
                    || !validEnvelope(value.envelope(), identity, cutoff)
                    || result.put(value.factorEffectiveTradeDate(), value)
                    != null) {
                throw invalid("TUSHARE_M1_DATASET_FACTOR_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    private static Map<LocalDate, TradingCalendarObservation> uniqueCalendar(
            List<TradingCalendarObservation> values,
            SecuritySelection security,
            String identity,
            Instant cutoff
    ) {
        Map<LocalDate, TradingCalendarObservation> result =
                new LinkedHashMap<>();
        for (TradingCalendarObservation value : values) {
            if (!security.exchange().equals(value.exchange())
                    || !validEnvelope(value.envelope(), identity, cutoff)
                    || result.put(value.calendarDate(), value) != null) {
                throw invalid("TUSHARE_M1_DATASET_CALENDAR_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    private boolean verifyBatchReferences(CaptureResult result) {
        Boolean verified = jdbc.queryForObject("""
                SELECT b.response_complete
                       AND b.record_count = ?
                       AND count(refs.reference) = b.record_count
                       AND bool_and(EXISTS (
                           SELECT 1
                             FROM pit_market_fact_observations o
                            WHERE o.fact_type = refs.reference->>'factType'
                              AND o.source_code = b.source_code
                              AND o.source_instrument_id =
                                  refs.reference->>'sourceIdentity'
                              AND o.natural_key =
                                  refs.reference->>'naturalKey'
                              AND o.canonical_content_hash =
                                  refs.reference->>'canonicalContentHash'
                       ))
                  FROM pit_market_fact_batches b
                  CROSS JOIN LATERAL jsonb_array_elements(
                      b.provider_metadata_json->'factReferences')
                      AS refs(reference)
                 WHERE b.id = ?
                 GROUP BY b.id, b.response_complete, b.record_count,
                          b.source_code
                """, Boolean.class, result.receivedCount(), result.batchId());
        return Boolean.TRUE.equals(verified);
    }

    private static boolean validEnvelope(
            FactEnvelope envelope,
            String sourceIdentity,
            Instant cutoff
    ) {
        return envelope != null && envelope.id() > 0 && envelope.batchId() > 0
                && TushareMarketFactProvider.PROVIDER_CODE.equals(
                envelope.sourceCode())
                && sourceIdentity.equals(envelope.sourceInstrumentId())
                && envelope.revisionQualification()
                == RevisionQualification.SYSTEM_KNOWLEDGE_ONLY
                && envelope.assuranceLevel()
                == AssuranceLevel.SYSTEM_KNOWLEDGE_PIT
                && envelope.usageQualification()
                == UsageQualification.RESEARCH_ONLY
                && !envelope.formalEligible()
                && envelope.localPersistenceAllowed()
                && envelope.historicalReplayAllowed()
                && envelope.backtestAllowed()
                && envelope.agentUseAllowed()
                && envelope.firstObservedAt() != null
                && envelope.knownAt() != null
                && !envelope.firstObservedAt().isAfter(envelope.knownAt())
                && !envelope.knownAt().isAfter(cutoff);
    }

    private static Set<LocalDate> naturalDates(LocalDate from, LocalDate to) {
        Set<LocalDate> result = new LinkedHashSet<>();
        for (LocalDate value = from; !value.isAfter(to);
                value = value.plusDays(1)) {
            result.add(value);
        }
        return Set.copyOf(result);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
