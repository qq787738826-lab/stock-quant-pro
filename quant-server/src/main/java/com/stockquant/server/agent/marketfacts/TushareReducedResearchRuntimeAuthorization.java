package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;

import java.util.Objects;
import java.util.Set;

/**
 * Typed authorization for the F1C random-schema manual runtime only.
 *
 * <p>Only {@link #f1cIsolatedManual()} is an accepted authorization. Public
 * construction exists so boundary tests can prove that widened or forged
 * authorizations are rejected before any provider call.</p>
 */
public record TushareReducedResearchRuntimeAuthorization(
        String providerCode,
        String adapterVersion,
        ImplementationScope implementationScope,
        RuntimeMode runtimeMode,
        RunNamespace runNamespace,
        UsageQualification usageQualification,
        FormalEligibility formalEligibility,
        int maximumSymbols,
        int maximumNaturalDays,
        int maximumProviderRequests,
        Set<FactType> allowedFactTypes,
        AutomaticRetryPolicy automaticRetryPolicy,
        IsolatedSchemaRequirement isolatedSchemaRequirement,
        RuntimePermission normalBusinessDatabase,
        RuntimePermission scheduler,
        RuntimePermission shadow,
        RuntimePermission agentDecision,
        RuntimePermission backtestExecution,
        RuntimePermission investmentAdvice,
        RuntimePermission trading
) {

    private static final Set<FactType> F1C_FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    public TushareReducedResearchRuntimeAuthorization {
        if (providerCode == null || providerCode.isBlank()
                || adapterVersion == null || adapterVersion.isBlank()
                || maximumSymbols <= 0
                || maximumNaturalDays <= 0
                || maximumProviderRequests <= 0) {
            throw invalidAuthorization();
        }
        implementationScope = Objects.requireNonNull(
                implementationScope, "implementationScope");
        runtimeMode = Objects.requireNonNull(runtimeMode, "runtimeMode");
        runNamespace = Objects.requireNonNull(
                runNamespace, "runNamespace");
        usageQualification = Objects.requireNonNull(
                usageQualification, "usageQualification");
        formalEligibility = Objects.requireNonNull(
                formalEligibility, "formalEligibility");
        allowedFactTypes = Set.copyOf(Objects.requireNonNull(
                allowedFactTypes, "allowedFactTypes"));
        automaticRetryPolicy = Objects.requireNonNull(
                automaticRetryPolicy, "automaticRetryPolicy");
        isolatedSchemaRequirement = Objects.requireNonNull(
                isolatedSchemaRequirement,
                "isolatedSchemaRequirement");
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
    }

    public static TushareReducedResearchRuntimeAuthorization
    f1cIsolatedManual() {
        return new TushareReducedResearchRuntimeAuthorization(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.ADAPTER_VERSION,
                ImplementationScope.LIMITED_PERSONAL_RESEARCH_USE,
                RuntimeMode.ISOLATED_MANUAL,
                RunNamespace.FORMAL,
                UsageQualification.RESEARCH_ONLY,
                FormalEligibility.NOT_ELIGIBLE,
                1,
                2,
                3,
                F1C_FACT_TYPES,
                AutomaticRetryPolicy.DISABLED,
                IsolatedSchemaRequirement
                        .RANDOM_F1C_ISOLATED_SCHEMA_REQUIRED,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN,
                RuntimePermission.FORBIDDEN);
    }

    public void validateFrozen() {
        if (!f1cIsolatedManual().equals(this)) {
            throw invalidAuthorization();
        }
    }

    public boolean formalEligible() {
        return formalEligibility == FormalEligibility.ELIGIBLE;
    }

    public boolean automaticRetryAllowed() {
        return automaticRetryPolicy == AutomaticRetryPolicy.ENABLED;
    }

    public boolean isolatedSchemaRequired() {
        return isolatedSchemaRequirement
                == IsolatedSchemaRequirement
                .RANDOM_F1C_ISOLATED_SCHEMA_REQUIRED;
    }

    public boolean normalBusinessDatabaseAllowed() {
        return normalBusinessDatabase == RuntimePermission.ALLOWED;
    }

    public boolean schedulerAllowed() {
        return scheduler == RuntimePermission.ALLOWED;
    }

    public boolean shadowAllowed() {
        return shadow == RuntimePermission.ALLOWED;
    }

    public boolean agentDecisionAllowed() {
        return agentDecision == RuntimePermission.ALLOWED;
    }

    public boolean backtestExecutionAllowed() {
        return backtestExecution == RuntimePermission.ALLOWED;
    }

    public boolean investmentAdviceAllowed() {
        return investmentAdvice == RuntimePermission.ALLOWED;
    }

    public boolean tradingAllowed() {
        return trading == RuntimePermission.ALLOWED;
    }

    private static IllegalArgumentException invalidAuthorization() {
        return new IllegalArgumentException(
                "TUSHARE_REDUCED_RUNTIME_AUTHORIZATION_INVALID");
    }

    public enum ImplementationScope {
        LIMITED_PERSONAL_RESEARCH_USE
    }

    public enum RuntimeMode {
        ISOLATED_MANUAL
    }

    public enum FormalEligibility {
        NOT_ELIGIBLE,
        ELIGIBLE
    }

    public enum AutomaticRetryPolicy {
        DISABLED,
        ENABLED
    }

    public enum IsolatedSchemaRequirement {
        RANDOM_F1C_ISOLATED_SCHEMA_REQUIRED,
        NOT_REQUIRED
    }

    public enum RuntimePermission {
        FORBIDDEN,
        ALLOWED
    }
}
