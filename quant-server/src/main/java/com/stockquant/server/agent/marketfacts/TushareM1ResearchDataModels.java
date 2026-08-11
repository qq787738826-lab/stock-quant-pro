package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sanitized typed projections produced by the M1 research data layer. */
public final class TushareM1ResearchDataModels {
    private TushareM1ResearchDataModels() {
    }

    public record FormulaOnlyQfqBar(
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long rawObservationId,
            long factorObservationId
    ) {
        public FormulaOnlyQfqBar {
            Objects.requireNonNull(tradeDate, "tradeDate");
            requirePositive(open);
            requirePositive(high);
            requirePositive(low);
            requirePositive(close);
            if (rawObservationId <= 0 || factorObservationId <= 0) {
                throw invalid("TUSHARE_M1_QFQ_LINEAGE_INVALID");
            }
        }
    }

    public record SecurityDataset(
            String symbol,
            String exchange,
            String sourceInstrumentId,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            LocalDate anchorTradeDate,
            int rawDailyCount,
            int adjustmentFactorCount,
            int calendarCount,
            int openDateCount,
            int closedDateCount,
            List<FormulaOnlyQfqBar> qfqBars,
            Instant firstKnownAt,
            Instant lastKnownAt,
            boolean typedFactReadbackPassed,
            boolean systemKnowledgeReadbackPassed,
            boolean dataQualityPassed,
            boolean noFutureDataLeakage
    ) {
        public SecurityDataset {
            symbol = required(symbol);
            exchange = required(exchange);
            sourceInstrumentId = required(sourceInstrumentId);
            Objects.requireNonNull(rangeStart, "rangeStart");
            Objects.requireNonNull(rangeEnd, "rangeEnd");
            Objects.requireNonNull(anchorTradeDate, "anchorTradeDate");
            qfqBars = List.copyOf(Objects.requireNonNull(qfqBars, "qfqBars"));
            Objects.requireNonNull(firstKnownAt, "firstKnownAt");
            Objects.requireNonNull(lastKnownAt, "lastKnownAt");
            if (!symbol.matches("[0-9]{6}")
                    || !List.of("SSE", "SZSE").contains(exchange)
                    || rangeEnd.isBefore(rangeStart)
                    || anchorTradeDate.isBefore(rangeStart)
                    || anchorTradeDate.isAfter(rangeEnd)
                    || rawDailyCount <= 0
                    || adjustmentFactorCount != rawDailyCount
                    || openDateCount != rawDailyCount
                    || calendarCount != openDateCount + closedDateCount
                    || qfqBars.size() != rawDailyCount
                    || firstKnownAt.isAfter(lastKnownAt)
                    || !typedFactReadbackPassed
                    || !systemKnowledgeReadbackPassed
                    || !dataQualityPassed
                    || !noFutureDataLeakage) {
                throw invalid("TUSHARE_M1_SECURITY_DATASET_INVALID");
            }
        }
    }

    public record ResearchDataset(
            String contractVersion,
            Instant knowledgeCutoff,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            LocalDate anchorTradeDate,
            List<SecurityDataset> securities,
            int totalRawDailyCount,
            int totalAdjustmentFactorCount,
            int totalCalendarCount,
            int totalQfqBarCount,
            boolean formulaOnlyQfq,
            boolean fullQfqLineageClaimed,
            boolean typedFactReadbackPassed,
            boolean systemKnowledgeReadbackPassed,
            boolean dataQualityPassed,
            boolean noFutureDataLeakage,
            boolean m2Readable
    ) {
        public ResearchDataset {
            contractVersion = required(contractVersion);
            Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
            Objects.requireNonNull(rangeStart, "rangeStart");
            Objects.requireNonNull(rangeEnd, "rangeEnd");
            Objects.requireNonNull(anchorTradeDate, "anchorTradeDate");
            securities = List.copyOf(Objects.requireNonNull(
                    securities, "securities"));
            int raw = securities.stream().mapToInt(
                    SecurityDataset::rawDailyCount).sum();
            int factors = securities.stream().mapToInt(
                    SecurityDataset::adjustmentFactorCount).sum();
            int calendars = securities.stream().mapToInt(
                    SecurityDataset::calendarCount).sum();
            int qfq = securities.stream().mapToInt(
                    value -> value.qfqBars().size()).sum();
            if (!"M1_RESEARCH_DATASET_V1".equals(contractVersion)
                    || securities.isEmpty()
                    || totalRawDailyCount != raw
                    || totalAdjustmentFactorCount != factors
                    || totalCalendarCount != calendars
                    || totalQfqBarCount != qfq
                    || !formulaOnlyQfq || fullQfqLineageClaimed
                    || !typedFactReadbackPassed
                    || !systemKnowledgeReadbackPassed
                    || !dataQualityPassed
                    || !noFutureDataLeakage || !m2Readable) {
                throw invalid("TUSHARE_M1_RESEARCH_DATASET_INVALID");
            }
        }
    }

    public record RunEvidence(
            Instant observedAt,
            int providerCallCount,
            int retryCount,
            Map<String, Integer> endpointCallCounts,
            List<Long> captureBatchIds,
            int receivedFactCount,
            int appendedObservationCount,
            int idempotentChainTailCount,
            boolean currentBatchReferencesVerified,
            DatabaseExecutionIdentity databaseIdentity,
            ResearchDataset dataset
    ) {
        public RunEvidence {
            Objects.requireNonNull(observedAt, "observedAt");
            endpointCallCounts = Map.copyOf(Objects.requireNonNull(
                    endpointCallCounts, "endpointCallCounts"));
            captureBatchIds = List.copyOf(Objects.requireNonNull(
                    captureBatchIds, "captureBatchIds"));
            Objects.requireNonNull(databaseIdentity, "databaseIdentity");
            Objects.requireNonNull(dataset, "dataset");
            if (providerCallCount <= 0 || providerCallCount > 9
                    || retryCount != 0
                    || captureBatchIds.isEmpty()
                    || captureBatchIds.stream().anyMatch(id -> id == null || id <= 0)
                    || receivedFactCount <= 0
                    || appendedObservationCount < 0
                    || idempotentChainTailCount < 0
                    || appendedObservationCount + idempotentChainTailCount
                    != receivedFactCount
                    || !currentBatchReferencesVerified) {
                throw invalid("TUSHARE_M1_RUN_EVIDENCE_INVALID");
            }
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("TUSHARE_M1_TEXT_INVALID");
        }
        return value;
    }

    private static void requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw invalid("TUSHARE_M1_QFQ_VALUE_INVALID");
        }
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
