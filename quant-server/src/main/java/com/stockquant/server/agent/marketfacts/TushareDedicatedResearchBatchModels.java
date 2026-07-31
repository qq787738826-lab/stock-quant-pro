package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.OperationalReadiness;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed result models for the F1E dedicated research batch. */
public final class TushareDedicatedResearchBatchModels {

    private TushareDedicatedResearchBatchModels() {
    }

    public record RuntimeEligibility(
            RuntimeQualification runtimeQualification,
            Eligibility systemKnowledgeOnly,
            Eligibility providerPitVerified,
            Eligibility corporateActionLineageComplete,
            Eligibility permanentSecurityIdentityVerified,
            Eligibility formalEligible,
            Eligibility fullQfqEligible,
            Eligibility productionEligible,
            Eligibility normalBusinessDatabaseEligible,
            Eligibility schedulerEligible,
            Eligibility agentDecisionEligible,
            Eligibility backtestExecutionEligible,
            Eligibility f2bEligible,
            Eligibility f3Eligible,
            Eligibility investmentAdviceEligible,
            Eligibility tradingEligible
    ) {
        public RuntimeEligibility {
            runtimeQualification = Objects.requireNonNull(
                    runtimeQualification, "runtimeQualification");
            systemKnowledgeOnly = Objects.requireNonNull(
                    systemKnowledgeOnly, "systemKnowledgeOnly");
            providerPitVerified = Objects.requireNonNull(
                    providerPitVerified, "providerPitVerified");
            corporateActionLineageComplete = Objects.requireNonNull(
                    corporateActionLineageComplete,
                    "corporateActionLineageComplete");
            permanentSecurityIdentityVerified = Objects.requireNonNull(
                    permanentSecurityIdentityVerified,
                    "permanentSecurityIdentityVerified");
            formalEligible = Objects.requireNonNull(
                    formalEligible, "formalEligible");
            fullQfqEligible = Objects.requireNonNull(
                    fullQfqEligible, "fullQfqEligible");
            productionEligible = Objects.requireNonNull(
                    productionEligible, "productionEligible");
            normalBusinessDatabaseEligible = Objects.requireNonNull(
                    normalBusinessDatabaseEligible,
                    "normalBusinessDatabaseEligible");
            schedulerEligible = Objects.requireNonNull(
                    schedulerEligible, "schedulerEligible");
            agentDecisionEligible = Objects.requireNonNull(
                    agentDecisionEligible, "agentDecisionEligible");
            backtestExecutionEligible = Objects.requireNonNull(
                    backtestExecutionEligible,
                    "backtestExecutionEligible");
            f2bEligible = Objects.requireNonNull(
                    f2bEligible, "f2bEligible");
            f3Eligible = Objects.requireNonNull(
                    f3Eligible, "f3Eligible");
            investmentAdviceEligible = Objects.requireNonNull(
                    investmentAdviceEligible,
                    "investmentAdviceEligible");
            tradingEligible = Objects.requireNonNull(
                    tradingEligible, "tradingEligible");
            if (runtimeQualification
                    != RuntimeQualification
                    .REDUCED_RESEARCH_FORMULA_ONLY
                    || systemKnowledgeOnly != Eligibility.YES
                    || java.util.stream.Stream.of(
                    providerPitVerified,
                    corporateActionLineageComplete,
                    permanentSecurityIdentityVerified,
                    formalEligible,
                    fullQfqEligible,
                    productionEligible,
                    normalBusinessDatabaseEligible,
                    schedulerEligible,
                    agentDecisionEligible,
                    backtestExecutionEligible,
                    f2bEligible,
                    f3Eligible,
                    investmentAdviceEligible,
                    tradingEligible).anyMatch(
                    value -> value != Eligibility.NO)) {
                throw new IllegalArgumentException(
                        "TUSHARE_DEDICATED_RESEARCH_ELIGIBILITY_INVALID");
            }
        }

        public static RuntimeEligibility formulaOnly() {
            return new RuntimeEligibility(
                    RuntimeQualification
                            .REDUCED_RESEARCH_FORMULA_ONLY,
                    Eligibility.YES,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO,
                    Eligibility.NO);
        }
    }

    public record TushareDedicatedResearchQfqBar(
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close
    ) {
        public TushareDedicatedResearchQfqBar {
            Objects.requireNonNull(tradeDate, "tradeDate");
            requirePositive(open);
            requirePositive(high);
            requirePositive(low);
            requirePositive(close);
        }
    }

    public record SymbolResearchResult(
            String sourceCode,
            String sourceInstrumentId,
            LocalDate tradeDate,
            int providerCallCount,
            int retryCount,
            int rawCount,
            int factorCount,
            int calendarCount,
            List<TushareDedicatedResearchQfqBar> qfqBars,
            CaptureResult captureResult
    ) {
        public SymbolResearchResult {
            sourceCode = requiredText(sourceCode, "sourceCode");
            sourceInstrumentId = requiredText(
                    sourceInstrumentId, "sourceInstrumentId");
            tradeDate = Objects.requireNonNull(
                    tradeDate, "tradeDate");
            qfqBars = List.copyOf(Objects.requireNonNull(
                    qfqBars, "qfqBars"));
            captureResult = Objects.requireNonNull(
                    captureResult, "captureResult");
            if (!TushareMarketFactProvider.PROVIDER_CODE.equals(
                    sourceCode)
                    || providerCallCount != 3
                    || retryCount != 0
                    || rawCount != 1
                    || factorCount != 1
                    || calendarCount != 1
                    || qfqBars.size() != 1
                    || !tradeDate.equals(qfqBars.get(0).tradeDate())
                    || !captureResult.complete()
                    || captureResult.receivedCount() != 3
                    || captureResult.appendedCount()
                    + captureResult.idempotentCount() != 3) {
                throw new IllegalArgumentException(
                        "TUSHARE_DEDICATED_RESEARCH_SYMBOL_RESULT_INVALID");
            }
        }
    }

    public record DatabaseExecutionIdentity(
            String currentDatabase,
            String currentUser,
            String jdbcUrl,
            String databasePurpose,
            String currentSchema,
            String searchPath,
            int backendPidBefore,
            int backendPidAfter
    ) {
        public DatabaseExecutionIdentity {
            currentDatabase = requiredText(
                    currentDatabase, "currentDatabase");
            currentUser = requiredText(currentUser, "currentUser");
            jdbcUrl = requiredText(jdbcUrl, "jdbcUrl");
            databasePurpose = requiredText(
                    databasePurpose, "databasePurpose");
            currentSchema = requiredText(
                    currentSchema, "currentSchema");
            searchPath = requiredText(searchPath, "searchPath");
            if (!TushareDedicatedResearchPersistenceGuard
                    .REQUIRED_DATABASE.equals(currentDatabase)
                    || !TushareDedicatedResearchPersistenceGuard
                    .REQUIRED_USER.equals(currentUser)
                    || !TushareDedicatedResearchPersistenceGuard
                    .DATABASE_PURPOSE.equals(databasePurpose)
                    || !TushareDedicatedResearchPersistenceGuard
                    .REQUIRED_SCHEMA.equals(currentSchema)
                    || backendPidBefore <= 0
                    || backendPidBefore != backendPidAfter) {
                throw new IllegalArgumentException(
                        "TUSHARE_DEDICATED_RESEARCH_DATABASE_RESULT_INVALID");
            }
        }
    }

    public record TushareDedicatedResearchBatchResult(
            RuntimeEligibility runtimeEligibility,
            OperationalReadiness operationalReadiness,
            LocalDate tradeDate,
            List<String> symbols,
            int providerCallCount,
            int retryCount,
            int sessionConsumedRequests,
            List<SymbolResearchResult> symbolResults,
            int appendedCount,
            int idempotentCount,
            DatabaseExecutionIdentity databaseIdentity,
            Set<String> reasonCodes,
            RouteDecision technicalRouteDecision
    ) {
        public static TushareDedicatedResearchBatchResult formulaOnly(
                LocalDate tradeDate,
                List<String> symbols,
                int providerCallCount,
                int sessionConsumedRequests,
                List<SymbolResearchResult> symbolResults,
                DatabaseExecutionIdentity databaseIdentity,
                Set<String> reasonCodes,
                RouteDecision technicalRouteDecision
        ) {
            int appended = symbolResults.stream()
                    .map(SymbolResearchResult::captureResult)
                    .mapToInt(CaptureResult::appendedCount)
                    .sum();
            int idempotent = symbolResults.stream()
                    .map(SymbolResearchResult::captureResult)
                    .mapToInt(CaptureResult::idempotentCount)
                    .sum();
            return new TushareDedicatedResearchBatchResult(
                    RuntimeEligibility.formulaOnly(),
                    OperationalReadiness.NOT_ACCEPTED,
                    tradeDate,
                    symbols,
                    providerCallCount,
                    0,
                    sessionConsumedRequests,
                    symbolResults,
                    appended,
                    idempotent,
                    databaseIdentity,
                    reasonCodes,
                    technicalRouteDecision);
        }

        public TushareDedicatedResearchBatchResult {
            runtimeEligibility = Objects.requireNonNull(
                    runtimeEligibility, "runtimeEligibility");
            operationalReadiness = Objects.requireNonNull(
                    operationalReadiness, "operationalReadiness");
            tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
            symbols = List.copyOf(Objects.requireNonNull(
                    symbols, "symbols"));
            symbolResults = List.copyOf(Objects.requireNonNull(
                    symbolResults, "symbolResults"));
            databaseIdentity = Objects.requireNonNull(
                    databaseIdentity, "databaseIdentity");
            reasonCodes = Set.copyOf(Objects.requireNonNull(
                    reasonCodes, "reasonCodes"));
            technicalRouteDecision = Objects.requireNonNull(
                    technicalRouteDecision, "technicalRouteDecision");
            int expectedCalls = symbols.size() * 3;
            if (operationalReadiness != OperationalReadiness.NOT_ACCEPTED
                    || symbols.isEmpty()
                    || symbols.size() > 3
                    || symbolResults.size() != symbols.size()
                    || providerCallCount != expectedCalls
                    || retryCount != 0
                    || sessionConsumedRequests != expectedCalls
                    || appendedCount < 0
                    || idempotentCount < 0
                    || appendedCount + idempotentCount
                    != symbols.size() * 3
                    || reasonCodes.isEmpty()
                    || technicalRouteDecision
                    != RouteDecision.REDUCED_RESEARCH_ONLY) {
                throw new IllegalArgumentException(
                        "TUSHARE_DEDICATED_RESEARCH_BATCH_RESULT_INVALID");
            }
            for (int index = 0; index < symbols.size(); index++) {
                SymbolResearchResult result = symbolResults.get(index);
                if (!symbols.get(index).equals(
                        result.sourceInstrumentId())
                        || !tradeDate.equals(result.tradeDate())) {
                    throw new IllegalArgumentException(
                            "TUSHARE_DEDICATED_RESEARCH_BATCH_RESULT_INVALID");
                }
            }
        }
    }

    public enum RuntimeQualification {
        REDUCED_RESEARCH_FORMULA_ONLY
    }

    public enum Eligibility {
        YES,
        NO
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid dedicated research " + field);
        }
        return value;
    }

    private static void requirePositive(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "TUSHARE_DEDICATED_RESEARCH_QFQ_VALUE_INVALID");
        }
    }
}
