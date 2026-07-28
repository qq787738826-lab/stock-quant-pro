package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CorporateActionObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactorPredecessor;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqAsOfResult;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqBar;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Java-authoritative DAILY_EXACT QFQ computation over cutoff-visible facts. */
@Service
public class QfqAsOfEngine {

    public static final int MAXIMUM_BARS = 500;
    private static final int DIVISION_SCALE = 16;
    private static final int PRICE_SCALE = 4;

    private final PitMarketFactRepository repository;

    public QfqAsOfEngine(PitMarketFactRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public QfqAsOfResult calculate(
            String symbol,
            String exchange,
            String sourceCode,
            String sourceInstrumentId,
            LocalDate requestTradeDate,
            Instant knowledgeCutoff
    ) {
        var effective = repository.findEffectiveTradeDate(
                sourceCode, sourceInstrumentId, exchange,
                requestTradeDate, knowledgeCutoff);
        if (effective.isEmpty()) {
            return unavailable(
                    PitMarketFactsContracts.CALENDAR_UNAVAILABLE,
                    "No cutoff-visible open calendar date exists",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }
        LocalDate effectiveDate = effective.orElseThrow().calendarDate();
        List<RawDailyBarObservation> raw = repository.findRawBarsAsOf(
                sourceCode, sourceInstrumentId, symbol, exchange,
                effectiveDate,
                knowledgeCutoff, MAXIMUM_BARS);
        if (raw.isEmpty()
                || !raw.get(raw.size() - 1).tradeDate().equals(effectiveDate)) {
            return unavailable(
                    PitMarketFactsContracts.RAW_BAR_UNAVAILABLE,
                    "Raw window does not end on requestEffectiveTradeDate",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }
        LocalDate start = raw.get(0).tradeDate();
        List<TradingCalendarObservation> calendar =
                repository.findOpenCalendarAsOf(
                        sourceCode, sourceInstrumentId, exchange,
                        start, effectiveDate, knowledgeCutoff);
        Set<LocalDate> openDates = new HashSet<>();
        calendar.forEach(item -> openDates.add(item.calendarDate()));
        if (raw.stream().anyMatch(item -> !openDates.contains(item.tradeDate()))) {
            return unavailable(
                    PitMarketFactsContracts.CALENDAR_UNAVAILABLE,
                    "A raw bar lacks an exact cutoff-visible open-calendar fact",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }
        if (calendar.size() != raw.size()
                || openDates.size() != raw.size()) {
            return unavailable(
                    PitMarketFactsContracts.RAW_BAR_UNAVAILABLE,
                    "The cutoff-visible raw window does not cover every "
                            + "open calendar date",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }

        List<AdjustmentFactorObservation> factors =
                repository.findFactorsAsOf(
                        sourceCode, sourceInstrumentId, symbol,
                        start, effectiveDate, knowledgeCutoff);
        Map<LocalDate, AdjustmentFactorObservation> factorByDate = new HashMap<>();
        factors.forEach(value -> factorByDate.put(
                value.factorEffectiveTradeDate(), value));
        if (raw.stream().anyMatch(
                item -> !factorByDate.containsKey(item.tradeDate()))) {
            return unavailable(
                    PitMarketFactsContracts.FACTOR_UNAVAILABLE,
                    "DAILY_EXACT factor is absent at the cutoff",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }
        AdjustmentFactorObservation anchorFactor =
                factorByDate.get(effectiveDate);
        if (anchorFactor == null) {
            return unavailable(
                    PitMarketFactsContracts.FACTOR_UNAVAILABLE,
                    "Anchor date lacks an exact factor",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }

        List<CorporateActionObservation> actions = repository.findActionsAsOf(
                sourceCode, sourceInstrumentId, symbol,
                start, effectiveDate, knowledgeCutoff);
        List<Long> revisedFactorIds = factors.stream()
                .filter(value -> value.envelope().chainSequence() > 1)
                .map(value -> value.envelope().id())
                .toList();
        Map<Long, FactorPredecessor> factorPredecessors = new HashMap<>();
        repository.findFactorPredecessors(revisedFactorIds).forEach(value ->
                factorPredecessors.put(value.observationId(), value));
        if (factorPredecessors.size() != revisedFactorIds.size()) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Revised factor predecessor lineage is incomplete",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }
        for (AdjustmentFactorObservation factor : factors) {
            if (factor.envelope().chainSequence() <= 1
                    || factor.envelope().revisionQualification()
                    == RevisionQualification.PROVIDER_VERIFIED) {
                continue;
            }
            FactorPredecessor predecessor =
                    factorPredecessors.get(factor.envelope().id());
            if (predecessor != null
                    && factor.factor().compareTo(predecessor.factor()) != 0
                    && actions.stream().noneMatch(action ->
                    action.envelope().knownAt().isAfter(
                            predecessor.knownAt())
                            && !action.envelope().knownAt().isAfter(
                            factor.envelope().knownAt()))) {
                return unavailable(
                        PitMarketFactsContracts
                                .CORPORATE_ACTION_LINEAGE_UNAVAILABLE,
                        "Revised factor lacks cutoff-visible "
                                + "corporate-action lineage",
                        symbol, sourceCode, sourceInstrumentId,
                        requestTradeDate, knowledgeCutoff);
            }
        }
        Set<LocalDate> explainedDates = new HashSet<>();
        actions.forEach(value -> explainedDates.add(value.effectiveTradeDate()));
        AdjustmentFactorObservation previous = null;
        for (RawDailyBarObservation item : raw) {
            AdjustmentFactorObservation current = factorByDate.get(item.tradeDate());
            if (previous != null
                    && current.factor().compareTo(previous.factor()) != 0
                    && current.envelope().revisionQualification()
                    != RevisionQualification.PROVIDER_VERIFIED
                    && !explainedDates.contains(item.tradeDate())) {
                return unavailable(
                        PitMarketFactsContracts.CORPORATE_ACTION_LINEAGE_UNAVAILABLE,
                        "Factor change lacks cutoff-visible corporate-action lineage",
                        symbol, sourceCode, sourceInstrumentId,
                        requestTradeDate, knowledgeCutoff);
            }
            previous = current;
        }

        List<QfqBar> qfq = new ArrayList<>(raw.size());
        for (RawDailyBarObservation value : raw) {
            AdjustmentFactorObservation factor = factorByDate.get(value.tradeDate());
            BigDecimal open = price(
                    value.open(), factor.factor(), anchorFactor.factor());
            BigDecimal high = price(
                    value.high(), factor.factor(), anchorFactor.factor());
            BigDecimal low = price(
                    value.low(), factor.factor(), anchorFactor.factor());
            BigDecimal close = price(
                    value.close(), factor.factor(), anchorFactor.factor());
            if (!validOhlc(open, high, low, close)) {
                return unavailable(
                        PitMarketFactsContracts.FACT_INVALID,
                        "Rounded QFQ OHLC relationship is invalid",
                        symbol, sourceCode, sourceInstrumentId,
                        requestTradeDate, knowledgeCutoff);
            }
            qfq.add(new QfqBar(
                    symbol, value.tradeDate(), open, high, low, close,
                    value.volume(), value.amount(), value.turnoverRate(),
                    value.envelope().id(),
                    value.envelope().observationVersion(),
                    value.envelope().canonicalContentHash(),
                    factor.envelope().id(),
                    factor.envelope().observationVersion(),
                    factor.envelope().canonicalContentHash()));
        }
        List<Long> batchIds = Stream.of(
                        raw.stream().map(item -> item.envelope().batchId()),
                        raw.stream().map(item -> factorByDate
                                .get(item.tradeDate()).envelope().batchId()),
                        calendar.stream().map(item -> item.envelope().batchId()),
                        actions.stream().map(item -> item.envelope().batchId()))
                .flatMap(value -> value)
                .distinct()
                .toList();
        var batchLineage = repository.findBatchLineage(batchIds);
        if (batchLineage.size() != batchIds.size()
                || batchLineage.stream().anyMatch(
                batch -> !batch.responseComplete()
                        || !sourceCode.equals(batch.sourceCode())
                        || !sourceInstrumentId.equals(
                        batch.sourceInstrumentId()))) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Stable capture batch lineage is incomplete or mismatched",
                    symbol, sourceCode, sourceInstrumentId,
                    requestTradeDate, knowledgeCutoff);
        }
        return new QfqAsOfResult(
                true, null, null, sourceCode, sourceInstrumentId, symbol,
                requestTradeDate, effectiveDate, effectiveDate, knowledgeCutoff,
                PitMarketFactsContracts.FACTOR_TYPE,
                PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                PitMarketFactsContracts.QFQ_ENGINE_VERSION,
                qfq, calendar, actions, batchLineage);
    }

    private static BigDecimal price(
            BigDecimal raw,
            BigDecimal factor,
            BigDecimal anchorFactor
    ) {
        return raw.multiply(factor)
                .divide(anchorFactor, DIVISION_SCALE, RoundingMode.HALF_UP)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean validOhlc(
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {
        return open.signum() > 0 && high.signum() > 0
                && low.signum() > 0 && close.signum() > 0
                && high.compareTo(open) >= 0
                && high.compareTo(low) >= 0
                && high.compareTo(close) >= 0
                && low.compareTo(open) <= 0
                && low.compareTo(high) <= 0
                && low.compareTo(close) <= 0;
    }

    private static QfqAsOfResult unavailable(
            String code,
            String reason,
            String symbol,
            String sourceCode,
            String sourceInstrumentId,
            LocalDate requestTradeDate,
            Instant cutoff
    ) {
        return QfqAsOfResult.unavailable(
                code, reason, sourceCode, sourceInstrumentId,
                symbol, requestTradeDate, cutoff);
    }
}
