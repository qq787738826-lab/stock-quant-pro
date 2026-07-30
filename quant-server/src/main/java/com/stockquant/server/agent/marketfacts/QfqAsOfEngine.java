package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CorporateActionObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactorPredecessor;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqAsOfResult;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqBar;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.QfqSourceIdentities;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
    private final PitMarketFactRepository repository;

    public QfqAsOfEngine(PitMarketFactRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public QfqAsOfResult calculate(
            String symbol,
            String exchange,
            String sourceCode,
            QfqSourceIdentities sourceIdentities,
            LocalDate requestTradeDate,
            Instant knowledgeCutoff
    ) {
        var effective = repository.findEffectiveTradeDate(
                sourceCode, sourceIdentities.calendarSourceIdentity(), exchange,
                requestTradeDate, knowledgeCutoff);
        if (effective.isEmpty()) {
            return unavailable(
                    PitMarketFactsContracts.CALENDAR_UNAVAILABLE,
                    "No cutoff-visible open calendar date exists",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (!usageAllowed(effective.orElseThrow().envelope())) {
            return unavailable(
                    PitMarketFactsContracts.USAGE_NOT_ALLOWED,
                    "Selected calendar observation is not qualified "
                            + "for PIT replay, backtest, and Agent use",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (!validEnvelope(
                effective.orElseThrow().envelope(),
                sourceCode,
                sourceIdentities.calendarSourceIdentity(),
                knowledgeCutoff)) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Calendar observation qualification is invalid",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        LocalDate effectiveDate = effective.orElseThrow().calendarDate();
        List<RawDailyBarObservation> raw = repository.findRawBarsAsOf(
                sourceCode, sourceIdentities.rawSourceIdentity(),
                symbol, exchange,
                effectiveDate,
                knowledgeCutoff, MAXIMUM_BARS);
        if (raw.isEmpty()
                || !raw.get(raw.size() - 1).tradeDate().equals(effectiveDate)) {
            return unavailable(
                    PitMarketFactsContracts.RAW_BAR_UNAVAILABLE,
                    "Raw window does not end on requestEffectiveTradeDate",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (raw.stream().anyMatch(
                value -> !usageAllowed(value.envelope()))) {
            return unavailable(
                    PitMarketFactsContracts.USAGE_NOT_ALLOWED,
                    "A selected raw observation is not qualified "
                            + "for PIT replay, backtest, and Agent use",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (raw.stream().anyMatch(value -> !validEnvelope(
                value.envelope(), sourceCode,
                sourceIdentities.rawSourceIdentity(), knowledgeCutoff))) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Raw observation qualification is invalid",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        LocalDate start = raw.get(0).tradeDate();
        List<TradingCalendarObservation> calendar =
                repository.findOpenCalendarAsOf(
                        sourceCode,
                        sourceIdentities.calendarSourceIdentity(),
                        exchange,
                        start, effectiveDate, knowledgeCutoff);
        Set<LocalDate> openDates = new HashSet<>();
        calendar.forEach(item -> openDates.add(item.calendarDate()));
        if (calendar.stream().anyMatch(
                value -> !usageAllowed(value.envelope()))) {
            return unavailable(
                    PitMarketFactsContracts.USAGE_NOT_ALLOWED,
                    "A selected calendar lineage observation is not qualified "
                            + "for PIT replay, backtest, and Agent use",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (calendar.stream().anyMatch(value -> !validEnvelope(
                value.envelope(), sourceCode,
                sourceIdentities.calendarSourceIdentity(),
                knowledgeCutoff))) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Calendar lineage qualification is invalid",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (raw.stream().anyMatch(item -> !openDates.contains(item.tradeDate()))) {
            return unavailable(
                    PitMarketFactsContracts.CALENDAR_UNAVAILABLE,
                    "A raw bar lacks an exact cutoff-visible open-calendar fact",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (calendar.size() != raw.size()
                || openDates.size() != raw.size()) {
            return unavailable(
                    PitMarketFactsContracts.RAW_BAR_UNAVAILABLE,
                    "The cutoff-visible raw window does not cover every "
                            + "open calendar date",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }

        List<AdjustmentFactorObservation> factors =
                repository.findFactorsAsOf(
                        sourceCode,
                        sourceIdentities.factorSourceIdentity(),
                        symbol,
                        start, effectiveDate, knowledgeCutoff);
        Map<LocalDate, AdjustmentFactorObservation> factorByDate = new HashMap<>();
        factors.forEach(value -> factorByDate.put(
                value.factorEffectiveTradeDate(), value));
        if (factors.stream().anyMatch(
                value -> !usageAllowed(value.envelope()))) {
            return unavailable(
                    PitMarketFactsContracts.USAGE_NOT_ALLOWED,
                    "A selected factor observation is not qualified "
                            + "for PIT replay, backtest, and Agent use",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (factors.stream().anyMatch(value -> !validEnvelope(
                value.envelope(), sourceCode,
                sourceIdentities.factorSourceIdentity(),
                knowledgeCutoff))) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Factor observation qualification is invalid",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (raw.stream().anyMatch(
                item -> !factorByDate.containsKey(item.tradeDate()))) {
            return unavailable(
                    PitMarketFactsContracts.FACTOR_UNAVAILABLE,
                    "DAILY_EXACT factor is absent at the cutoff",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        AdjustmentFactorObservation anchorFactor =
                factorByDate.get(effectiveDate);
        if (anchorFactor == null) {
            return unavailable(
                    PitMarketFactsContracts.FACTOR_UNAVAILABLE,
                    "Anchor date lacks an exact factor",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }

        List<CorporateActionObservation> actions = repository.findActionsAsOf(
                sourceCode,
                sourceIdentities.corporateActionSourceIdentity(),
                symbol,
                start, effectiveDate, knowledgeCutoff);
        if (actions.stream().anyMatch(
                value -> !usageAllowed(value.envelope()))) {
            return unavailable(
                    PitMarketFactsContracts.USAGE_NOT_ALLOWED,
                    "A selected corporate-action observation is not qualified "
                            + "for PIT replay, backtest, and Agent use",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        if (actions.stream().anyMatch(value -> !validEnvelope(
                value.envelope(), sourceCode,
                sourceIdentities.corporateActionSourceIdentity(),
                knowledgeCutoff))) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Corporate-action observation qualification is invalid",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
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
                    symbol, sourceCode, sourceIdentities,
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
                    explainsFactorRevision(
                            action, factor, predecessor,
                            sourceCode, sourceIdentities,
                            knowledgeCutoff))) {
                return unavailable(
                        PitMarketFactsContracts
                                .CORPORATE_ACTION_LINEAGE_UNAVAILABLE,
                        "Revised factor lacks cutoff-visible "
                                + "corporate-action lineage",
                        symbol, sourceCode, sourceIdentities,
                        requestTradeDate, knowledgeCutoff);
            }
        }
        AdjustmentFactorObservation previous = null;
        for (RawDailyBarObservation item : raw) {
            AdjustmentFactorObservation current = factorByDate.get(item.tradeDate());
            if (previous != null
                    && current.factor().compareTo(previous.factor()) != 0
                    && current.envelope().revisionQualification()
                    != RevisionQualification.PROVIDER_VERIFIED
                    && actions.stream().noneMatch(action ->
                    explainsFactorDateChange(
                            action, current, sourceCode,
                            sourceIdentities, knowledgeCutoff))) {
                return unavailable(
                        PitMarketFactsContracts.CORPORATE_ACTION_LINEAGE_UNAVAILABLE,
                        "Factor change lacks cutoff-visible corporate-action lineage",
                        symbol, sourceCode, sourceIdentities,
                        requestTradeDate, knowledgeCutoff);
            }
            previous = current;
        }

        List<QfqBar> qfq = new ArrayList<>(raw.size());
        for (RawDailyBarObservation value : raw) {
            AdjustmentFactorObservation factor = factorByDate.get(value.tradeDate());
            BigDecimal open = QfqPriceMath.calculate(
                    value.open(), factor.factor(), anchorFactor.factor());
            BigDecimal high = QfqPriceMath.calculate(
                    value.high(), factor.factor(), anchorFactor.factor());
            BigDecimal low = QfqPriceMath.calculate(
                    value.low(), factor.factor(), anchorFactor.factor());
            BigDecimal close = QfqPriceMath.calculate(
                    value.close(), factor.factor(), anchorFactor.factor());
            if (!validOhlc(open, high, low, close)) {
                return unavailable(
                        PitMarketFactsContracts.FACT_INVALID,
                        "Rounded QFQ OHLC relationship is invalid",
                        symbol, sourceCode, sourceIdentities,
                        requestTradeDate, knowledgeCutoff);
            }
            qfq.add(new QfqBar(
                    symbol, value.tradeDate(), open, high, low, close,
                    value.volume(), value.amount(), value.turnoverRate(),
                    value.envelope().id(),
                    value.envelope().sourceInstrumentId(),
                    value.envelope().observationVersion(),
                    value.envelope().canonicalContentHash(),
                    factor.envelope().id(),
                    factor.envelope().sourceInstrumentId(),
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
                        || !sourceCode.equals(batch.sourceCode()))) {
            return unavailable(
                    PitMarketFactsContracts.FACT_INVALID,
                    "Stable capture batch lineage is incomplete or mismatched",
                    symbol, sourceCode, sourceIdentities,
                    requestTradeDate, knowledgeCutoff);
        }
        return new QfqAsOfResult(
                true, null, null, sourceCode, sourceIdentities, symbol,
                requestTradeDate, effectiveDate, effectiveDate, knowledgeCutoff,
                PitMarketFactsContracts.FACTOR_TYPE,
                PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                PitMarketFactsContracts.QFQ_ENGINE_VERSION,
                qfq, raw, factors, calendar, actions, batchLineage);
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
            QfqSourceIdentities sourceIdentities,
            LocalDate requestTradeDate,
            Instant cutoff
    ) {
        return QfqAsOfResult.unavailable(
                code, reason, sourceCode, sourceIdentities,
                symbol, requestTradeDate, cutoff);
    }

    private static boolean explainsFactorRevision(
            CorporateActionObservation action,
            AdjustmentFactorObservation factor,
            FactorPredecessor predecessor,
            String sourceCode,
            QfqSourceIdentities identities,
            Instant cutoff
    ) {
        return actionMatches(
                action, factor, sourceCode, identities, cutoff)
                && predecessor.sourceCode().equals(sourceCode)
                && predecessor.sourceIdentity().equals(
                factor.envelope().sourceInstrumentId())
                && predecessor.symbol().equals(factor.symbol())
                && predecessor.factorEffectiveTradeDate().equals(
                factor.factorEffectiveTradeDate())
                && !action.envelope().knownAt().isAfter(
                factor.envelope().knownAt());
    }

    private static boolean explainsFactorDateChange(
            CorporateActionObservation action,
            AdjustmentFactorObservation factor,
            String sourceCode,
            QfqSourceIdentities identities,
            Instant cutoff
    ) {
        return actionMatches(
                action, factor, sourceCode, identities, cutoff)
                && !action.envelope().knownAt().isAfter(
                factor.envelope().knownAt());
    }

    private static boolean actionMatches(
            CorporateActionObservation action,
            AdjustmentFactorObservation factor,
            String sourceCode,
            QfqSourceIdentities identities,
            Instant cutoff
    ) {
        return action.symbol().equals(factor.symbol())
                && action.envelope().sourceCode().equals(sourceCode)
                && action.envelope().sourceInstrumentId().equals(
                identities.corporateActionSourceIdentity())
                && factor.envelope().sourceInstrumentId().equals(
                identities.factorSourceIdentity())
                && action.effectiveTradeDate().equals(
                factor.factorEffectiveTradeDate())
                && !action.envelope().knownAt().isAfter(cutoff);
    }

    private static boolean validEnvelope(
            PitMarketFactModels.FactEnvelope envelope,
            String sourceCode,
            String sourceIdentity,
            Instant cutoff
    ) {
        if (!sourceCode.equals(envelope.sourceCode())
                || !sourceIdentity.equals(envelope.sourceInstrumentId())
                || envelope.recordedAt().isBefore(
                envelope.firstObservedAt())
                || envelope.knownAt().isAfter(cutoff)) {
            return false;
        }
        if (envelope.revisionQualification()
                == RevisionQualification.PROVIDER_VERIFIED) {
            return envelope.assuranceLevel()
                    == AssuranceLevel.PROVIDER_PIT_VERIFIED
                    && envelope.providerRevision() != null
                    && envelope.providerPublishedAt() != null
                    && envelope.knownAt().equals(
                    envelope.providerPublishedAt())
                    && !envelope.providerPublishedAt().isAfter(
                    envelope.firstObservedAt())
                    && (envelope.providerUpdatedAt() == null
                    || !envelope.providerUpdatedAt().isBefore(
                    envelope.providerPublishedAt())
                    && !envelope.providerUpdatedAt().isAfter(
                    envelope.firstObservedAt()));
        }
        return envelope.assuranceLevel()
                == AssuranceLevel.SYSTEM_KNOWLEDGE_PIT
                && envelope.providerDatasetVersion() == null
                && envelope.providerRevision() == null
                && envelope.providerSnapshotId() == null
                && envelope.providerPublishedAt() == null
                && envelope.providerUpdatedAt() == null
                && envelope.knownAt().equals(
                envelope.firstObservedAt());
    }

    private static boolean usageAllowed(
            PitMarketFactModels.FactEnvelope envelope
    ) {
        return envelope.usageQualification() != null
                && envelope.localPersistenceAllowed()
                && envelope.historicalReplayAllowed()
                && envelope.backtestAllowed()
                && envelope.agentUseAllowed();
    }
}
