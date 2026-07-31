package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, exact authorization boundary for the only F1A FORMAL capture path.
 *
 * <p>This record projects the typed personal-research written-permission
 * qualification without widening the runtime boundary. It binds the limited
 * implementation to one provider, one adapter, SYSTEM_KNOWLEDGE_ONLY facts
 * and the three research fact types approved for isolated validation.
 * Complete personal-use permission does not make the response FORMAL-eligible
 * or make the full F1 technical contract ready.</p>
 */
public record LimitedPersonalFormalCaptureAuthorization(
        String providerCode,
        String adapterVersion,
        String implementationScope,
        RunNamespace runNamespace,
        UsageQualification usageQualification,
        boolean formalEligible,
        PermissionEvidence writtenQuantDataSourceUsePermission,
        PermissionEvidence writtenPersonalLocalStoragePermission,
        PermissionEvidence writtenPersonalBacktestPermission,
        PermissionEvidence writtenPersonalAgentAnalysisPermission,
        PermissionEvidence writtenAutomatedApiUpdatePermission,
        PermissionEvidence writtenTechnicalAuditMetadataRetentionPermission,
        PermissionEvidence postExpiryDataRetentionPermission,
        PermissionEvidence personal2000PointAccountScopePermission,
        UserAuthorization userPersonalUseImplementationAuthorization,
        LimitedImplementation limitedPersonalUseImplementation,
        AuthorizationBasis authorizationBasis,
        boolean personalResearchPermissionComplete,
        boolean fullF1EntryReady,
        boolean providerWrittenPermissionComplete,
        RedistributionPermission rawDataRedistributionPermission,
        CommercialDataServicePermission commercialDataServicePermission,
        TokenSharingPermission tokenSharingPermission,
        RevisionQualification revisionQualification,
        Set<FactType> supportedFactTypes
) {

    private static final Set<FactType> F1A_FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    public LimitedPersonalFormalCaptureAuthorization {
        providerCode = requiredText(providerCode, "providerCode");
        adapterVersion = requiredText(adapterVersion, "adapterVersion");
        implementationScope = requiredText(
                implementationScope, "implementationScope");
        runNamespace = Objects.requireNonNull(
                runNamespace, "runNamespace");
        usageQualification = Objects.requireNonNull(
                usageQualification, "usageQualification");
        writtenQuantDataSourceUsePermission = Objects.requireNonNull(
                writtenQuantDataSourceUsePermission,
                "writtenQuantDataSourceUsePermission");
        writtenPersonalLocalStoragePermission = Objects.requireNonNull(
                writtenPersonalLocalStoragePermission,
                "writtenPersonalLocalStoragePermission");
        writtenPersonalBacktestPermission = Objects.requireNonNull(
                writtenPersonalBacktestPermission,
                "writtenPersonalBacktestPermission");
        writtenPersonalAgentAnalysisPermission = Objects.requireNonNull(
                writtenPersonalAgentAnalysisPermission,
                "writtenPersonalAgentAnalysisPermission");
        writtenAutomatedApiUpdatePermission = Objects.requireNonNull(
                writtenAutomatedApiUpdatePermission,
                "writtenAutomatedApiUpdatePermission");
        writtenTechnicalAuditMetadataRetentionPermission =
                Objects.requireNonNull(
                        writtenTechnicalAuditMetadataRetentionPermission,
                        "writtenTechnicalAuditMetadataRetentionPermission");
        postExpiryDataRetentionPermission = Objects.requireNonNull(
                postExpiryDataRetentionPermission,
                "postExpiryDataRetentionPermission");
        personal2000PointAccountScopePermission = Objects.requireNonNull(
                personal2000PointAccountScopePermission,
                "personal2000PointAccountScopePermission");
        userPersonalUseImplementationAuthorization = Objects.requireNonNull(
                userPersonalUseImplementationAuthorization,
                "userPersonalUseImplementationAuthorization");
        limitedPersonalUseImplementation = Objects.requireNonNull(
                limitedPersonalUseImplementation,
                "limitedPersonalUseImplementation");
        authorizationBasis = Objects.requireNonNull(
                authorizationBasis, "authorizationBasis");
        rawDataRedistributionPermission = Objects.requireNonNull(
                rawDataRedistributionPermission,
                "rawDataRedistributionPermission");
        commercialDataServicePermission = Objects.requireNonNull(
                commercialDataServicePermission,
                "commercialDataServicePermission");
        tokenSharingPermission = Objects.requireNonNull(
                tokenSharingPermission,
                "tokenSharingPermission");
        revisionQualification = Objects.requireNonNull(
                revisionQualification, "revisionQualification");
        supportedFactTypes = Set.copyOf(Objects.requireNonNull(
                supportedFactTypes, "supportedFactTypes"));
    }

    public static LimitedPersonalFormalCaptureAuthorization tushareF1A() {
        return new LimitedPersonalFormalCaptureAuthorization(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.ADAPTER_VERSION,
                TushareMarketFactProvider.IMPLEMENTATION_SCOPE,
                RunNamespace.FORMAL,
                UsageQualification.RESEARCH_ONLY,
                false,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                PermissionEvidence.VERIFIED,
                UserAuthorization.CONFIRMED,
                LimitedImplementation.APPROVED_BY_USER,
                AuthorizationBasis.USER_APPROVED_LIMITED_PERSONAL_USE,
                true,
                false,
                true,
                RedistributionPermission.NOT_GRANTED,
                CommercialDataServicePermission.NOT_GRANTED,
                TokenSharingPermission.NOT_GRANTED,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY,
                F1A_FACT_TYPES);
    }

    void validateResponse(MarketFactResponse response) {
        Objects.requireNonNull(response, "response");
        LimitedPersonalFormalCaptureAuthorization expected = tushareF1A();
        if (!expected.equals(this)) {
            throw invalidAuthorization();
        }
        LimitedPersonalFormalCaptureAuthorization actual =
                fromResponse(response);
        if (!equals(actual)
                || !providerCode.equals(response.sourceCode())
                || !adapterVersion.equals(
                response.capability().adapterVersion())
                || !implementationScope.equals(text(
                response.providerMetadata(), "implementationScope"))) {
            throw invalidAuthorization();
        }
        Set<FactType> responseFactTypes = actualFactTypes(response);
        if (!supportedFactTypes.containsAll(responseFactTypes)
                || !response.corporateActions().isEmpty()) {
            throw invalidAuthorization();
        }
    }

    static LimitedPersonalFormalCaptureAuthorization fromResponse(
            MarketFactResponse response
    ) {
        Objects.requireNonNull(response, "response");
        JsonNode licensing = response.capability().licensing();
        JsonNode coverage = response.capability().coverage();
        return new LimitedPersonalFormalCaptureAuthorization(
                response.providerCode(),
                response.adapterVersion(),
                text(coverage, "implementationScope"),
                response.runNamespace(),
                enumValue(
                        licensing,
                        "usageQualification",
                        UsageQualification.class),
                bool(licensing, "formalEligible"),
                enumValue(
                        licensing,
                        "writtenQuantDataSourceUsePermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "writtenPersonalLocalStoragePermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "writtenPersonalBacktestPermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "writtenPersonalAgentAnalysisPermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "writtenAutomatedApiUpdatePermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "writtenTechnicalAuditMetadataRetentionPermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "postExpiryDataRetentionPermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "personal2000PointAccountScopePermission",
                        PermissionEvidence.class),
                enumValue(
                        licensing,
                        "userPersonalUseImplementationAuthorization",
                        UserAuthorization.class),
                enumValue(
                        licensing,
                        "limitedPersonalUseImplementation",
                        LimitedImplementation.class),
                enumValue(
                        licensing,
                        "authorizationBasis",
                        AuthorizationBasis.class),
                bool(licensing, "personalResearchPermissionComplete"),
                bool(licensing, "fullF1EntryReady"),
                bool(licensing, "providerWrittenPermissionComplete"),
                enumValue(
                        licensing,
                        "rawDataRedistributionPermission",
                        RedistributionPermission.class),
                enumValue(
                        licensing,
                        "commercialDataServicePermission",
                        CommercialDataServicePermission.class),
                enumValue(
                        licensing,
                        "tokenSharingPermission",
                        TokenSharingPermission.class),
                uniformRevisionQualification(response),
                response.capability().supportedFactTypes());
    }

    private static RevisionQualification uniformRevisionQualification(
            MarketFactResponse response
    ) {
        List<ProviderVersion> versions = new ArrayList<>();
        response.rawDailyBars().forEach(value ->
                versions.add(value.version()));
        response.adjustmentFactors().forEach(value ->
                versions.add(value.version()));
        response.tradingCalendar().forEach(value ->
                versions.add(value.version()));
        response.corporateActions().forEach(value ->
                versions.add(value.version()));
        if (versions.isEmpty()) {
            return RevisionQualification.SYSTEM_KNOWLEDGE_ONLY;
        }
        RevisionQualification result =
                versions.get(0).revisionQualification();
        if (versions.stream().anyMatch(version ->
                version.revisionQualification() != result)) {
            throw invalidAuthorization();
        }
        return result;
    }

    private static Set<FactType> actualFactTypes(
            MarketFactResponse response
    ) {
        var result = java.util.EnumSet.noneOf(FactType.class);
        if (!response.rawDailyBars().isEmpty()) {
            result.add(FactType.RAW_DAILY_BAR);
        }
        if (!response.adjustmentFactors().isEmpty()) {
            result.add(FactType.ADJUSTMENT_FACTOR);
        }
        if (!response.tradingCalendar().isEmpty()) {
            result.add(FactType.TRADING_CALENDAR);
        }
        if (!response.corporateActions().isEmpty()) {
            result.add(FactType.CORPORATE_ACTION);
        }
        return Set.copyOf(result);
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()
                || value.asText().isBlank()) {
            throw invalidAuthorization();
        }
        return value.asText();
    }

    private static boolean bool(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalidAuthorization();
        }
        return value.booleanValue();
    }

    private static <T extends Enum<T>> T enumValue(
            JsonNode object,
            String field,
            Class<T> type
    ) {
        try {
            return Enum.valueOf(type, text(object, field));
        } catch (IllegalArgumentException error) {
            throw invalidAuthorization();
        }
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid limited personal FORMAL " + field);
        }
        return value;
    }

    private static IllegalArgumentException invalidAuthorization() {
        return new IllegalArgumentException(
                "TUSHARE_LIMITED_PERSONAL_FORMAL_AUTHORIZATION_INVALID");
    }

    public enum PermissionEvidence {
        VERIFIED,
        UNVERIFIED
    }

    public enum UserAuthorization {
        CONFIRMED
    }

    public enum LimitedImplementation {
        APPROVED_BY_USER
    }

    public enum AuthorizationBasis {
        USER_APPROVED_LIMITED_PERSONAL_USE
    }

    public enum RedistributionPermission {
        NOT_GRANTED
    }

    public enum CommercialDataServicePermission {
        NOT_GRANTED
    }

    public enum TokenSharingPermission {
        NOT_GRANTED
    }
}
