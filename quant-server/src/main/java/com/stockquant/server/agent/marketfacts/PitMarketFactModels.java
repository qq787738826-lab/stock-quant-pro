package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.CorporateActionType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Database-independent immutable projections used by capture and as-of replay. */
public final class PitMarketFactModels {

    private PitMarketFactModels() {
    }

    public record FactEnvelope(
            long id,
            long batchId,
            FactType factType,
            String factContractVersion,
            String naturalKey,
            int chainSequence,
            Long predecessorObservationId,
            String sourceCode,
            String sourceInstrumentId,
            String providerDatasetVersion,
            String providerRevision,
            String providerSnapshotId,
            Instant providerPublishedAt,
            Instant providerUpdatedAt,
            Instant firstObservedAt,
            Instant knownAt,
            Instant recordedAt,
            String canonicalContentHash,
            String observationVersion,
            RevisionQualification revisionQualification,
            AssuranceLevel assuranceLevel,
            UsageQualification usageQualification,
            boolean formalEligible,
            boolean localPersistenceAllowed,
            boolean historicalReplayAllowed,
            boolean backtestAllowed,
            boolean agentUseAllowed,
            JsonNode rawPayload
    ) {
    }

    public record RawDailyBarObservation(
            FactEnvelope envelope,
            String symbol,
            String exchange,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal turnoverRate
    ) {
    }

    public record AdjustmentFactorObservation(
            FactEnvelope envelope,
            String symbol,
            LocalDate factorEffectiveTradeDate,
            String factorType,
            String coverageMode,
            BigDecimal factor
    ) {
    }

    public record FactorPredecessor(
            long observationId,
            BigDecimal factor,
            Instant knownAt
    ) {
    }

    public record TradingCalendarObservation(
            FactEnvelope envelope,
            String exchange,
            LocalDate calendarDate,
            boolean open,
            String sessionCode
    ) {
    }

    public record CorporateActionObservation(
            FactEnvelope envelope,
            String sourceActionId,
            String symbol,
            CorporateActionType actionType,
            LocalDate announcementDate,
            LocalDate effectiveTradeDate,
            JsonNode terms
    ) {
    }

    public record BatchLineage(
            String batchVersion,
            String datasetVersion,
            String providerDatasetVersion,
            RunNamespace runNamespace,
            String sourceCode,
            String sourceInstrumentId,
            RevisionQualification revisionQualification,
            AssuranceLevel assuranceLevel,
            UsageQualification usageQualification,
            Instant observedAt,
            boolean responseComplete
    ) {
    }

    public record CaptureResult(
            long batchId,
            String batchVersion,
            long datasetVersionId,
            String datasetVersion,
            int receivedCount,
            int appendedCount,
            int idempotentCount,
            boolean complete
    ) {
    }

    public record QfqBar(
            String symbol,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            long rawObservationId,
            String rawObservationVersion,
            String rawContentHash,
            long factorObservationId,
            String factorObservationVersion,
            String factorContentHash
    ) {
    }

    public record QfqAsOfResult(
            boolean available,
            String reasonCode,
            String reason,
            String sourceCode,
            String sourceInstrumentId,
            String symbol,
            LocalDate requestTradeDate,
            LocalDate requestEffectiveTradeDate,
            LocalDate anchorTradeDate,
            Instant knowledgeCutoff,
            String factorType,
            String coverageMode,
            String engineVersion,
            List<QfqBar> bars,
            List<TradingCalendarObservation> calendarLineage,
            List<CorporateActionObservation> corporateActionLineage,
            List<BatchLineage> batchLineage
    ) {
        public QfqAsOfResult {
            bars = List.copyOf(bars);
            calendarLineage = List.copyOf(calendarLineage);
            corporateActionLineage = List.copyOf(corporateActionLineage);
            batchLineage = List.copyOf(batchLineage);
        }

        public static QfqAsOfResult unavailable(
                String reasonCode,
                String reason,
                String sourceCode,
                String sourceInstrumentId,
                String symbol,
                LocalDate requestTradeDate,
                Instant knowledgeCutoff
        ) {
            return new QfqAsOfResult(
                    false, reasonCode, reason, sourceCode, sourceInstrumentId,
                    symbol, requestTradeDate, null, null, knowledgeCutoff,
                    PitMarketFactsContracts.FACTOR_TYPE,
                    PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                    PitMarketFactsContracts.QFQ_ENGINE_VERSION,
                    List.of(), List.of(), List.of(), List.of());
        }
    }

    public record BatchIdentity(
            String batchVersion,
            String datasetVersion,
            String providerDatasetVersion,
            RunNamespace runNamespace,
            String sourceCode,
            String sourceInstrumentId,
            RevisionQualification revisionQualification,
            AssuranceLevel assuranceLevel,
            UsageQualification usageQualification,
            boolean formalEligible,
            boolean localPersistenceAllowed,
            boolean historicalReplayAllowed,
            boolean backtestAllowed,
            boolean agentUseAllowed
    ) {
    }
}
