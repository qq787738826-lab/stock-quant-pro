package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed command and result records for the isolated F1C runtime. */
public final class TushareReducedResearchModels {

    private TushareReducedResearchModels() {
    }

    public record RunCommand(
            String symbol,
            String exchange,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            LocalDate anchorTradeDate,
            Duration timeout
    ) {
        public RunCommand {
            if (symbol == null || !symbol.matches("[0-9]{6}")
                    || !Set.of("SSE", "SZSE").contains(exchange)
                    || requestedStart == null
                    || requestedEnd == null
                    || anchorTradeDate == null
                    || requestedEnd.isBefore(requestedStart)
                    || anchorTradeDate.isBefore(requestedStart)
                    || anchorTradeDate.isAfter(requestedEnd)
                    || java.time.temporal.ChronoUnit.DAYS.between(
                    requestedStart, requestedEnd) + 1 > 2
                    || timeout == null
                    || timeout.isZero()
                    || timeout.isNegative()) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RUNTIME_COMMAND_INVALID");
            }
        }
    }

    public record TushareReducedResearchQfqBar(
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {
        public TushareReducedResearchQfqBar {
            Objects.requireNonNull(tradeDate, "tradeDate");
            requirePositive(open, "open");
            requirePositive(high, "high");
            requirePositive(low, "low");
            requirePositive(close, "close");
        }
    }

    public record TushareReducedResearchRunResult(
            RuntimeQualification runtimeQualification,
            boolean systemKnowledgeOnly,
            boolean providerPitVerified,
            boolean corporateActionLineageComplete,
            boolean permanentSecurityIdentityVerified,
            boolean formalEligible,
            boolean fullQfqEligible,
            boolean productionEligible,
            boolean agentDecisionEligible,
            boolean backtestExecutionEligible,
            boolean investmentAdviceEligible,
            boolean tradingEligible,
            int providerCallCount,
            int retryCount,
            int sessionConsumedRequests,
            String sourceCode,
            String sourceInstrumentId,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            LocalDate anchorTradeDate,
            int rawCount,
            int factorCount,
            int calendarCount,
            List<TushareReducedResearchQfqBar> qfqBars,
            CaptureResult captureResult,
            Set<String> reasonCodes,
            RouteDecision technicalRouteDecision
    ) {
        public static TushareReducedResearchRunResult formulaOnly(
                int providerCallCount,
                int retryCount,
                int sessionConsumedRequests,
                String sourceCode,
                String sourceInstrumentId,
                LocalDate requestedStart,
                LocalDate requestedEnd,
                LocalDate anchorTradeDate,
                int rawCount,
                int factorCount,
                int calendarCount,
                List<TushareReducedResearchQfqBar> qfqBars,
                CaptureResult captureResult,
                Set<String> reasonCodes,
                RouteDecision technicalRouteDecision
        ) {
            return new TushareReducedResearchRunResult(
                    RuntimeQualification.REDUCED_RESEARCH_FORMULA_ONLY,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    providerCallCount,
                    retryCount,
                    sessionConsumedRequests,
                    sourceCode,
                    sourceInstrumentId,
                    requestedStart,
                    requestedEnd,
                    anchorTradeDate,
                    rawCount,
                    factorCount,
                    calendarCount,
                    qfqBars,
                    captureResult,
                    reasonCodes,
                    technicalRouteDecision);
        }

        public TushareReducedResearchRunResult {
            runtimeQualification = Objects.requireNonNull(
                    runtimeQualification, "runtimeQualification");
            sourceCode = requiredText(sourceCode, "sourceCode");
            sourceInstrumentId = requiredText(
                    sourceInstrumentId, "sourceInstrumentId");
            requestedStart = Objects.requireNonNull(
                    requestedStart, "requestedStart");
            requestedEnd = Objects.requireNonNull(
                    requestedEnd, "requestedEnd");
            anchorTradeDate = Objects.requireNonNull(
                    anchorTradeDate, "anchorTradeDate");
            qfqBars = List.copyOf(Objects.requireNonNull(
                    qfqBars, "qfqBars"));
            captureResult = Objects.requireNonNull(
                    captureResult, "captureResult");
            reasonCodes = Set.copyOf(Objects.requireNonNull(
                    reasonCodes, "reasonCodes"));
            technicalRouteDecision = Objects.requireNonNull(
                    technicalRouteDecision, "technicalRouteDecision");
            if (runtimeQualification
                    != RuntimeQualification
                    .REDUCED_RESEARCH_FORMULA_ONLY
                    || !systemKnowledgeOnly
                    || providerPitVerified
                    || corporateActionLineageComplete
                    || permanentSecurityIdentityVerified
                    || formalEligible
                    || fullQfqEligible
                    || productionEligible
                    || agentDecisionEligible
                    || backtestExecutionEligible
                    || investmentAdviceEligible
                    || tradingEligible
                    || providerCallCount != 3
                    || retryCount != 0
                    || sessionConsumedRequests != 3
                    || rawCount <= 0
                    || factorCount != rawCount
                    || calendarCount < rawCount
                    || qfqBars.size() != rawCount
                    || !captureResult.complete()
                    || captureResult.receivedCount()
                    != rawCount + factorCount + calendarCount
                    || captureResult.appendedCount()
                    + captureResult.idempotentCount()
                    != captureResult.receivedCount()
                    || reasonCodes.isEmpty()
                    || technicalRouteDecision
                    != RouteDecision.REDUCED_RESEARCH_ONLY) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RUNTIME_RESULT_INVALID");
            }
        }
    }

    public enum RuntimeQualification {
        REDUCED_RESEARCH_FORMULA_ONLY
    }

    private static void requirePositive(
            BigDecimal value,
            String field
    ) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "invalid reduced QFQ " + field);
        }
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid reduced runtime " + field);
        }
        return value;
    }
}
