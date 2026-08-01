package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.OperationalReadiness;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;

import java.math.BigDecimal;
import java.time.Instant;
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

    public static final class DatabaseExecutionIdentity {
        private final String currentDatabase;
        private final String currentUser;
        private final String jdbcUrl;
        private final String databasePurpose;
        private final String currentSchema;
        private final String searchPath;
        private final List<String> appliedMigrations;
        private final int backendPidBefore;
        private final int backendPidAfter;

        private DatabaseExecutionIdentity(
                TushareDedicatedResearchPersistenceGuard.Verification before,
                TushareDedicatedResearchPersistenceGuard.Verification after
        ) {
            TushareDedicatedResearchPersistenceGuard
                    .validateVerificationTarget(before, true);
            TushareDedicatedResearchPersistenceGuard
                    .validateVerificationTarget(after, true);
            if (!sameTarget(before, after)
                    || before.backendPid() != after.backendPid()) {
                throw invalid();
            }
            this.currentDatabase = before.currentDatabase();
            this.currentUser = before.currentUser();
            this.jdbcUrl = before.jdbcUrl();
            this.databasePurpose = before.databasePurpose();
            this.currentSchema = before.currentSchema();
            this.searchPath = before.searchPath();
            this.appliedMigrations = List.copyOf(
                    before.appliedMigrations());
            this.backendPidBefore = before.backendPid();
            this.backendPidAfter = after.backendPid();
        }

        public static DatabaseExecutionIdentity from(
                TushareDedicatedResearchPersistenceGuard.Verification before,
                TushareDedicatedResearchPersistenceGuard.Verification after
        ) {
            return new DatabaseExecutionIdentity(
                    Objects.requireNonNull(before, "before"),
                    Objects.requireNonNull(after, "after"));
        }

        private static boolean sameTarget(
                TushareDedicatedResearchPersistenceGuard.Verification before,
                TushareDedicatedResearchPersistenceGuard.Verification after
        ) {
            return Objects.equals(
                    before.currentDatabase(), after.currentDatabase())
                    && Objects.equals(
                    before.currentUser(), after.currentUser())
                    && Objects.equals(
                    before.jdbcUrl(), after.jdbcUrl())
                    && Objects.equals(
                    before.databasePurpose(), after.databasePurpose())
                    && Objects.equals(
                    before.currentSchema(), after.currentSchema())
                    && Objects.equals(
                    before.searchPath(), after.searchPath())
                    && Objects.equals(
                    before.appliedMigrations(),
                    after.appliedMigrations())
                    && before.databaseIdentityQualification()
                    == after.databaseIdentityQualification()
                    && before.schemaQualification()
                    == after.schemaQualification();
        }

        public String currentDatabase() {
            return currentDatabase;
        }

        public String currentUser() {
            return currentUser;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String databasePurpose() {
            return databasePurpose;
        }

        public String currentSchema() {
            return currentSchema;
        }

        public String searchPath() {
            return searchPath;
        }

        public List<String> appliedMigrations() {
            return appliedMigrations;
        }

        public int backendPidBefore() {
            return backendPidBefore;
        }

        public int backendPidAfter() {
            return backendPidAfter;
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException(
                    "TUSHARE_DEDICATED_RESEARCH_DATABASE_RESULT_INVALID");
        }
    }

    public record TushareDedicatedResearchBatchResult(
            RuntimeEligibility runtimeEligibility,
            OperationalReadiness operationalReadiness,
            Instant observedAt,
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
                Instant observedAt,
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
                    observedAt,
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
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
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
