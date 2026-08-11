package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;

import java.util.Objects;
import java.util.Set;

/** Exact authorization for the F1E dedicated local manual batch. */
public record TushareDedicatedResearchBatchAuthorization(
        String providerCode,
        String adapterVersion,
        AccountScope accountScope,
        UsageQualification usageQualification,
        WrittenPermissionCompleteness writtenPermissionCompleteness,
        RuntimeMode runtimeMode,
        RunNamespace runNamespace,
        FormalEligibility formalEligibility,
        int maximumSymbols,
        int maximumNaturalDays,
        int maximumProviderRequests,
        Set<FactType> allowedFactTypes,
        AutomaticRetryPolicy automaticRetryPolicy,
        RuntimePermission normalBusinessDatabase,
        RuntimePermission scheduler,
        RuntimePermission shadow,
        RuntimePermission agentDecision,
        RuntimePermission backtestExecution,
        RuntimePermission investmentAdvice,
        RuntimePermission trading
) {

    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    public TushareDedicatedResearchBatchAuthorization {
        providerCode = requiredText(providerCode, "providerCode");
        adapterVersion = requiredText(adapterVersion, "adapterVersion");
        accountScope = Objects.requireNonNull(
                accountScope, "accountScope");
        usageQualification = Objects.requireNonNull(
                usageQualification, "usageQualification");
        writtenPermissionCompleteness = Objects.requireNonNull(
                writtenPermissionCompleteness,
                "writtenPermissionCompleteness");
        runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
        runNamespace = Objects.requireNonNull(
                runNamespace, "runNamespace");
        formalEligibility = Objects.requireNonNull(
                formalEligibility, "formalEligibility");
        allowedFactTypes = Set.copyOf(Objects.requireNonNull(
                allowedFactTypes, "allowedFactTypes"));
        automaticRetryPolicy = Objects.requireNonNull(
                automaticRetryPolicy, "automaticRetryPolicy");
        normalBusinessDatabase = Objects.requireNonNull(
                normalBusinessDatabase, "normalBusinessDatabase");
        scheduler = Objects.requireNonNull(scheduler, "scheduler");
        shadow = Objects.requireNonNull(shadow, "shadow");
        agentDecision = Objects.requireNonNull(
                agentDecision, "agentDecision");
        backtestExecution = Objects.requireNonNull(
                backtestExecution, "backtestExecution");
        investmentAdvice = Objects.requireNonNull(
                investmentAdvice, "investmentAdvice");
        trading = Objects.requireNonNull(trading, "trading");
        if (maximumSymbols <= 0
                || maximumNaturalDays <= 0
                || maximumProviderRequests <= 0) {
            throw invalid();
        }
    }

    public static TushareDedicatedResearchBatchAuthorization
    manualPersonalResearch() {
        return new TushareDedicatedResearchBatchAuthorization(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.ADAPTER_VERSION,
                AccountScope.PERSONAL_2000_POINT,
                UsageQualification.RESEARCH_ONLY,
                WrittenPermissionCompleteness.COMPLETE,
                RuntimeMode.DEDICATED_LOCAL_MANUAL,
                RunNamespace.FORMAL,
                FormalEligibility.NOT_ELIGIBLE,
                3,
                1,
                9,
                FACT_TYPES,
                AutomaticRetryPolicy.DISABLED,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN);
    }

    public static TushareDedicatedResearchBatchAuthorization
    m1ResearchData() {
        return new TushareDedicatedResearchBatchAuthorization(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.ADAPTER_VERSION,
                AccountScope.PERSONAL_2000_POINT,
                UsageQualification.RESEARCH_ONLY,
                WrittenPermissionCompleteness.COMPLETE,
                RuntimeMode.M1_RESEARCH_DATA_MANUAL,
                RunNamespace.FORMAL,
                FormalEligibility.NOT_ELIGIBLE,
                TushareManualBoundedSession.M1_MAX_SYMBOLS,
                TushareManualBoundedSession.M1_MAX_NATURAL_DAYS,
                TushareManualBoundedSession
                        .M1_MAX_PROVIDER_BUSINESS_REQUESTS,
                FACT_TYPES,
                AutomaticRetryPolicy.DISABLED,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN);
    }

    public void validateFrozen() {
        if (!manualPersonalResearch().equals(this)) {
            throw invalid();
        }
    }

    public void validateM1Frozen() {
        if (!m1ResearchData().equals(this)) {
            throw invalid();
        }
    }

    public boolean automaticRetryAllowed() {
        return automaticRetryPolicy == AutomaticRetryPolicy.ENABLED;
    }

    public boolean formalEligible() {
        return formalEligibility == FormalEligibility.ELIGIBLE;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_DEDICATED_RESEARCH_AUTHORIZATION_INVALID");
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid dedicated research authorization " + field);
        }
        return value;
    }

    public enum AccountScope {
        PERSONAL_2000_POINT
    }

    public enum WrittenPermissionCompleteness {
        COMPLETE,
        INCOMPLETE
    }

    public enum RuntimeMode {
        DEDICATED_LOCAL_MANUAL,
        M1_RESEARCH_DATA_MANUAL
    }

    public enum FormalEligibility {
        NOT_ELIGIBLE,
        ELIGIBLE
    }

    public enum AutomaticRetryPolicy {
        DISABLED,
        ENABLED
    }

    public enum RuntimePermission {
        FORBIDDEN,
        ALLOWED
    }
}
