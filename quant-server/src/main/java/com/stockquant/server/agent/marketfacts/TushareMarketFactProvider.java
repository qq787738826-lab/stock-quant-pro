package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldSemantic;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldUnit;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderCapability;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderError;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderErrorType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.QualifiedMarketField;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.DividendEvidence;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.InstrumentIdentity;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.MainboardInstrument;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.MainboardReferenceResponse;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.ReferenceDataResponse;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Limited personal-research adapter for the verified Tushare 2000-point
 * endpoints. It emits SYSTEM_KNOWLEDGE_ONLY facts and does not claim provider
 * revisions, permanent instrument identity, or a complete corporate-action
 * lineage.
 */
@Component
public final class TushareMarketFactProvider implements MarketFactProvider {

    public static final String PROVIDER_CODE = "TUSHARE_PRO";
    public static final String ADAPTER_VERSION =
            "TUSHARE_MARKET_FACT_PROVIDER_V1";
    public static final String IMPLEMENTATION_SCOPE =
            "LIMITED_PERSONAL_RESEARCH_USE";
    public static final int STOCK_BASIC_MAX_ROWS = 1;
    public static final int MAINBOARD_MARKET_MAX_ROWS = 6_000;
    public static final int MAINBOARD_MINIMUM_COVERAGE_PERCENT = 95;
    public static final int DIVIDEND_EVIDENCE_MAX_ROWS = 1_000;
    private static final int MAXIMUM_NATURAL_DAYS =
            TushareManualBoundedSession.M1_MAX_NATURAL_DAYS;
    private static final DateTimeFormatter PROVIDER_DATE =
            DateTimeFormatter.BASIC_ISO_DATE;
    private static final Set<FactType> SUPPORTED_FACT_TYPES =
            Set.copyOf(EnumSet.of(
                    FactType.RAW_DAILY_BAR,
                    FactType.ADJUSTMENT_FACTOR,
                    FactType.TRADING_CALENDAR));
    private static final List<String> DAILY_FIELDS = List.of(
            "ts_code", "trade_date", "open", "high", "low", "close",
            "vol", "amount");
    private static final List<String> FACTOR_FIELDS = List.of(
            "ts_code", "trade_date", "adj_factor");
    private static final List<String> CALENDAR_FIELDS = List.of(
            "exchange", "cal_date", "is_open", "pretrade_date");
    private static final List<String> STOCK_BASIC_FIELDS = List.of(
            "ts_code", "symbol", "name", "market", "exchange",
            "list_status", "list_date", "delist_date");
    private static final List<String> MAINBOARD_STOCK_BASIC_FIELDS = List.of(
            "ts_code", "symbol", "name", "industry", "market",
            "exchange", "list_status", "list_date", "delist_date");
    private static final List<String> DIVIDEND_FIELDS = List.of(
            "ts_code", "end_date", "ann_date", "div_proc", "stk_div",
            "stk_bo_rate", "stk_co_rate", "cash_div", "cash_div_tax",
            "record_date", "ex_date", "pay_date", "div_listdate",
            "imp_ann_date");
    private static final ProviderVersion SYSTEM_KNOWLEDGE_VERSION =
            new ProviderVersion(
                    null, null, null, null, null,
                    RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
    private static final TushareTechnicalQualification
            TECHNICAL_QUALIFICATION =
            TushareTechnicalQualification.current2000PointAssessment();
    private static final TushareWrittenPermissionQualification
            WRITTEN_PERMISSION_QUALIFICATION =
            TushareWrittenPermissionQualification
                    .currentPersonal2000PointAssessment();
    private static final TushareF1EntryQualification
            F1_ENTRY_QUALIFICATION =
            TushareF1EntryQualification.assess(
                    WRITTEN_PERMISSION_QUALIFICATION,
                    TECHNICAL_QUALIFICATION);

    private final ObjectMapper objectMapper;
    private final TushareMarketFactProperties properties;
    private final TushareApiGateway gateway;

    public TushareMarketFactProvider(
            ObjectMapper objectMapper,
            TushareMarketFactProperties properties,
            TushareApiGateway gateway
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.gateway = gateway;
    }

    @Override
    public ProviderCapability capability() {
        TushareTechnicalQualification qualification =
                technicalQualification();
        TushareReducedResearchAdmissionQualification admission =
                TushareReducedResearchAdmissionQualification
                        .currentF1eAssessment();
        ObjectNode coverage = objectMapper.createObjectNode();
        coverage.put("implementationScope", IMPLEMENTATION_SCOPE);
        coverage.put("rawDaily", "MINIMUM_SAMPLE_VERIFIED");
        coverage.put("adjustmentFactor", "MINIMUM_DAILY_EXACT_VERIFIED");
        coverage.put("tradingCalendar", "SSE_SZSE_MINIMUM_SAMPLE_VERIFIED");
        coverage.put("stockBasicIdentity", "PARTIAL");
        coverage.put("dividendEvidence", "PARTIAL_NOT_V13_ELIGIBLE");
        coverage.put("corporateAction", "INCOMPLETE_TECHNICAL_BLOCKER");
        coverage.put("stableSecurityIdentity", "PARTIAL");
        coverage.put("v13Lineage", "PARTIAL");
        coverage.put("pitQualification", "PIT_PARTIAL");
        coverage.put(
                "fullTechnicalContractReady",
                qualification.fullTechnicalContractReady());
        coverage.put(
                "reducedResearchContractReady",
                qualification.reducedResearchContractReady());
        coverage.put(
                "tushareReducedResearchContract",
                qualification.reducedResearchContractReady()
                        ? qualification
                        .reducedResearchIsolatedManualRuntimeReady()
                        ? "CONTRACT_DEFINED_ISOLATED_MANUAL_READY"
                        : "CONTRACT_DEFINED_RUNTIME_NOT_READY"
                        : "BLOCKED");
        coverage.put(
                "technicalRouteDecision",
                qualification.routeDecision().name());
        coverage.put(
                "reducedResearchRuntimeReady",
                qualification.reducedResearchRuntimeReady());
        coverage.put(
                "reducedResearchIsolatedManualRuntimeReady",
                qualification
                        .reducedResearchIsolatedManualRuntimeReady());
        coverage.put(
                "reducedResearchProductionRuntimeReady",
                qualification.reducedResearchProductionRuntimeReady());
        coverage.put(
                "normalBusinessDatabaseRuntimeReady",
                qualification.normalBusinessDatabaseRuntimeReady());
        coverage.put(
                "reducedResearchRouteDecision",
                admission.admissionDecision().name());
        coverage.put(
                "reducedResearchLocalRuntimeImplementationReady",
                admission
                        .reducedResearchLocalRuntimeImplementationReady());
        coverage.put(
                "reducedResearchControlledAcceptanceReady",
                admission.reducedResearchControlledAcceptanceReady());
        coverage.put(
                "reducedResearchOperationalReady",
                admission.reducedResearchOperationalReady());
        coverage.put(
                "dedicatedLocalResearchDatabaseRequired",
                true);
        coverage.put(
                "schedulerRuntimeReady",
                qualification.schedulerRuntimeReady());
        coverage.put("agentDecisionRuntimeReady", false);
        coverage.put("backtestExecutionRuntimeReady", false);
        coverage.put("f2bRuntimeReady", false);
        coverage.put("f3RuntimeReady", false);
        coverage.put(
                "qfqCalculationMode",
                qualification.qfqCalculationMode().name());
        coverage.put(
                "qfqAnchorSemantics",
                qualification.qfqAnchorSemantics().name());
        coverage.put(
                "qfqFormulaQualification",
                qualification.qfqFormulaQualification().name());
        coverage.put(
                "qfqOperationalRuntimeQualification",
                qualification.qfqOperationalRuntimeQualification().name());
        coverage.put(
                "qfqReducedResearchRuntimeQualification",
                qualification
                        .qfqReducedResearchRuntimeQualification().name());
        coverage.put(
                "qfqFullLineageRuntimeQualification",
                qualification
                        .qfqFullLineageRuntimeQualification().name());
        coverage.put(
                "corporateActionLineageComplete",
                qualification.corporateActionLineageComplete());
        coverage.put(
                "permanentSecurityIdentityVerified",
                qualification.permanentSecurityIdentityVerified());
        coverage.put(
                "providerRevisionAvailable",
                qualification.providerRevisionAvailable());
        coverage.put(
                "historicalVersionsQueryable",
                qualification.historicalVersionsQueryable());
        coverage.put(
                "fullHistoryDailyExactQualification",
                qualification.fullHistoryDailyExactQualification().name());
        coverage.put(
                "providerPitQualification",
                qualification.providerPitQualification().name());
        coverage.put(
                "forwardSystemKnowledgePitBuildable",
                qualification.forwardSystemKnowledgePitBuildable());
        coverage.put(
                "endpointRateLimitQualification",
                qualification.endpointRateLimitQualification().name());
        coverage.put(
                "officialEndpointRateLimits",
                qualification.endpointRateLimitQualification().name());
        coverage.put(
                "endpointSpecificRateLimitEnforced",
                qualification.endpointSpecificRateLimitEnforced());
        coverage.put(
                "conservativeEndpointMinimumPolicyEnforced",
                qualification
                        .conservativeEndpointMinimumPolicyEnforced());
        coverage.put(
                "isolatedSchemaGuardVerified",
                qualification.isolatedSchemaGuardVerified());
        coverage.put(
                "stockCompanyIdentityUse",
                "ISSUER_IDENTITY_EVIDENCE");
        coverage.put(
                "namechangeUse",
                "SECURITY_NAME_HISTORY_EVIDENCE");
        coverage.put(
                "historicalSecurityList",
                "HISTORICAL_SECURITY_LIST_PERMISSION_INSUFFICIENT");
        ObjectNode corporateActionCoverage =
                coverage.putObject("corporateActionCoverage");
        qualification.corporateActionCoverage().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> corporateActionCoverage.put(
                        entry.getKey().name(),
                        entry.getValue().name()));
        ArrayNode technicalBlockers =
                coverage.putArray("technicalBlockers");
        qualification.blockers().stream()
                .sorted()
                .forEach(value -> technicalBlockers.add(value.name()));
        ArrayNode qfqOperationalBlockers =
                coverage.putArray("qfqOperationalBlockers");
        qualification.qfqOperationalBlockers().stream()
                .sorted()
                .forEach(value -> qfqOperationalBlockers.add(
                        value.name()));
        ArrayNode endpointRateLimitBlockers =
                coverage.putArray("endpointRateLimitBlockers");
        qualification.endpointRateLimitBlockers().stream()
                .sorted()
                .forEach(value -> endpointRateLimitBlockers.add(
                        value.name()));
        ArrayNode endpointRateLimitEvidenceIds =
                coverage.putArray("endpointRateLimitEvidenceIds");
        qualification.endpointRateLimitEvidenceIds().stream()
                .sorted()
                .forEach(endpointRateLimitEvidenceIds::add);
        ArrayNode technicalEvidenceIds =
                coverage.putArray("technicalEvidenceIds");
        qualification.evidenceIds().stream()
                .sorted()
                .forEach(technicalEvidenceIds::add);

        ObjectNode licensing = objectMapper.createObjectNode();
        TushareWrittenPermissionQualification writtenPermission =
                writtenPermissionQualification();
        TushareF1EntryQualification f1Entry =
                f1EntryQualification();
        licensing.put("usageQualification", "RESEARCH_ONLY");
        licensing.put("formalEligible", false);
        licensing.put("personalUseOnly", true);
        licensing.put(
                "writtenQuantDataSourceUsePermission",
                writtenPermission.quantDataSourceUsePermission()
                        .status().name());
        licensing.put(
                "writtenPersonalLocalStoragePermission",
                writtenPermission.personalLocalStoragePermission()
                        .status().name());
        licensing.put(
                "writtenPersonalBacktestPermission",
                writtenPermission.personalBacktestPermission()
                        .status().name());
        licensing.put(
                "writtenPersonalAgentAnalysisPermission",
                writtenPermission.personalAgentAnalysisPermission()
                        .status().name());
        licensing.put(
                "writtenAutomatedApiUpdatePermission",
                writtenPermission.automatedApiUpdatePermission()
                        .status().name());
        licensing.put(
                "writtenTechnicalAuditMetadataRetentionPermission",
                writtenPermission
                        .technicalAuditMetadataRetentionPermission()
                        .status().name());
        licensing.put(
                "postExpiryDataRetentionPermission",
                writtenPermission.postExpiryDataRetentionPermission()
                        .status().name());
        licensing.put(
                "personal2000PointAccountScopePermission",
                writtenPermission
                        .personal2000PointAccountScopePermission()
                        .status().name());
        licensing.put(
                "userPersonalUseImplementationAuthorization",
                "CONFIRMED");
        licensing.put(
                "limitedPersonalUseImplementation",
                "APPROVED_BY_USER");
        licensing.put(
                "fullF1EntryReady",
                f1Entry.fullF1EntryReady());
        licensing.put(
                "authorizationBasis",
                "USER_APPROVED_LIMITED_PERSONAL_USE");
        licensing.put(
                "personalResearchPermissionComplete",
                writtenPermission.personalResearchPermissionComplete());
        licensing.put(
                "providerWrittenPermissionComplete",
                writtenPermission.personalResearchPermissionComplete());
        licensing.put(
                "writtenPermissionGate",
                f1Entry.writtenPermissionGate().name());
        licensing.put(
                "technicalEvidenceGate",
                f1Entry.technicalEvidenceGate().name());
        licensing.put(
                "f1EntryReadiness",
                f1Entry.entryReadiness().name());
        licensing.put(
                "rawDataRedistributionPermission",
                writtenPermission.redistributionPermission()
                        .status().name());
        licensing.put(
                "commercialDataServicePermission",
                writtenPermission.commercialDataServicePermission()
                        .status().name());
        licensing.put(
                "tokenSharingPermission",
                writtenPermission.tokenSharingPermission()
                        .status().name());
        ArrayNode activeF1Blockers =
                licensing.putArray("activeF1Blockers");
        f1Entry.activeBlockers().stream()
                .sorted()
                .forEach(value -> activeF1Blockers.add(value.name()));
        ArrayNode permissionEvidenceIds =
                licensing.putArray("writtenPermissionEvidenceIds");
        writtenPermission.evidenceIds().stream()
                .sorted()
                .forEach(permissionEvidenceIds::add);
        ArrayNode permissionBlockers =
                licensing.putArray("writtenPermissionBlockers");
        writtenPermission.permissionBlockers().stream()
                .sorted()
                .forEach(value -> permissionBlockers.add(value.name()));
        ObjectNode permissionEvidenceProvenance =
                licensing.putObject("writtenPermissionEvidenceProvenance");
        writtenPermission.evidenceProvenance().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ObjectNode evidence =
                            permissionEvidenceProvenance.putObject(
                                    entry.getKey());
                    var metadata = entry.getValue();
                    evidence.put(
                            "provenance",
                            metadata.evidenceProvenance().name());
                    evidence.put(
                            "transcriptionReceivedAt",
                            metadata.transcriptionReceivedAt().toString());
                    evidence.put(
                            "officialReplyAt",
                            metadata.officialReplyAt());
                    evidence.put(
                            "userAttestedOfficialSource",
                            metadata.userAttestedOfficialSource());
                    evidence.put(
                            "originalArtifactStored",
                            metadata.originalArtifactStored());
                    evidence.put(
                            "screenshotReviewed",
                            metadata.screenshotReviewed());
                    evidence.put(
                            "independentSourceAuthenticityReviewed",
                            metadata.independentSourceAuthenticityReviewed());
                    ArrayNode supportedSubjects = evidence.putArray(
                            "supportedPermissionSubjects");
                    metadata.supportedPermissionSubjects().stream()
                            .sorted()
                            .forEach(value ->
                                    supportedSubjects.add(value.name()));
                });

        ObjectNode rateLimit = objectMapper.createObjectNode();
        rateLimit.put(
                "officialPerMinute",
                properties.getOfficialRateLimitPerMinute());
        rateLimit.put(
                "applicationSafePerMinute",
                properties.getApplicationSafeLimitPerMinute());
        rateLimit.put(
                "officialDailyPerApi",
                properties.getOfficialDailyLimitPerApi());
        rateLimit.put(
                "applicationDailySafePerApi",
                properties.getApplicationDailySafeLimitPerApi());
        rateLimit.put("processWide", true);
        rateLimit.put("sharedAcrossEndpoints", true);
        rateLimit.put("sharedAcrossCallersInProcess", true);
        rateLimit.put("tokenLevelGlobalAcrossProcesses", false);
        rateLimit.put("distributedRateLimitCoordinated", false);
        rateLimit.put("dailyQuotaProcessWideOnly", true);
        rateLimit.put("distributedDailyQuotaCoordinated", false);
        rateLimit.put(
                "normalMaximumRateLimitRetries",
                properties.getMaximumRateLimitRetries());
        rateLimit.put("controlledProbeMaximumRetries", 0);
        rateLimit.put(
                "manualBoundedMaximumBusinessRequests",
                TushareManualBoundedSession.MAX_PROVIDER_BUSINESS_REQUESTS);
        rateLimit.put(
                "officialEndpointRateLimits",
                qualification.endpointRateLimitQualification().name());
        rateLimit.put(
                "endpointSpecificRateLimitEnforced",
                qualification.endpointSpecificRateLimitEnforced());
        rateLimit.put(
                "conservativeEndpointMinimumPolicyEnforced",
                qualification
                        .conservativeEndpointMinimumPolicyEnforced());
        rateLimit.put(
                "applicableLimitSelection",
                "MOST_CONSERVATIVE_MINIMUM");
        ObjectNode endpointLimits =
                rateLimit.putObject("officialPerMinuteByEndpoint");
        endpointLimits.put(
                "stock_basic",
                TushareTechnicalQualification
                        .STOCK_BASIC_OFFICIAL_RATE_LIMIT_PER_MINUTE);
        endpointLimits.put(
                "daily",
                TushareTechnicalQualification
                        .DAILY_OFFICIAL_RATE_LIMIT_PER_MINUTE);
        endpointLimits.put(
                "adj_factor",
                TushareTechnicalQualification
                        .GENERAL_2000_POINT_RATE_LIMIT_PER_MINUTE);
        endpointLimits.put(
                "trade_cal",
                TushareTechnicalQualification
                        .GENERAL_2000_POINT_RATE_LIMIT_PER_MINUTE);
        endpointLimits.put(
                "dividend",
                TushareTechnicalQualification
                        .GENERAL_2000_POINT_RATE_LIMIT_PER_MINUTE);
        ObjectNode applicationEndpointLimits =
                rateLimit.putObject("applicationSafePerMinuteByEndpoint");
        TushareEndpointRateLimitPolicy.frozenF1cPolicy()
                .endpointSafeLimitsPerMinute()
                .forEach(applicationEndpointLimits::put);
        rateLimit.put("distributedCoordination", false);

        return new ProviderCapability(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                PROVIDER_CODE,
                ADAPTER_VERSION,
                SUPPORTED_FACT_TYPES,
                false,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                true,
                1,
                MAXIMUM_NATURAL_DAYS,
                Duration.ofMillis(334),
                Map.of(
                        "price", "CNY_PER_SHARE",
                        "volume", "SHARES_CONVERTED_FROM_HANDS",
                        "amount", "CNY_CONVERTED_FROM_THOUSAND_CNY",
                        "turnoverRate", "NOT_EXPOSED",
                        "factor", "DIMENSIONLESS"
                ),
                Map.of(
                        "price", 8,
                        "volume", 8,
                        "amount", 8,
                        "turnoverRate", 12,
                        "factor", 18),
                coverage,
                licensing,
                rateLimit);
    }

    public TushareTechnicalQualification technicalQualification() {
        return TECHNICAL_QUALIFICATION;
    }

    public TushareWrittenPermissionQualification
    writtenPermissionQualification() {
        return WRITTEN_PERMISSION_QUALIFICATION;
    }

    public TushareF1EntryQualification f1EntryQualification() {
        return F1_ENTRY_QUALIFICATION;
    }

    public F1cRateLimitedGateway.F1cRateLimitedGatewayContract
    f1cRateLimitContract() {
        if (!(gateway instanceof F1cRateLimitedGateway rateLimitedGateway)) {
            throw new IllegalStateException(
                    "TUSHARE_REDUCED_RUNTIME_RATE_LIMIT_GATEWAY_REQUIRED");
        }
        return rateLimitedGateway.f1cRateLimitContract();
    }

    @Override
    public MarketFactResponse fetch(MarketFactRequest request) {
        throw new IllegalStateException(
                "TUSHARE_MANUAL_BOUNDED_SESSION_REQUIRED");
    }

    /** Explicit acceptance path: exactly one attempt per requested endpoint. */
    public MarketFactResponse fetchForControlledAcceptance(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        return fetch(
                request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /**
     * Explicit F1C path. It accepts only the three-request isolated-manual
     * session and never enables retries or the two reference endpoints.
     */
    public MarketFactResponse fetchForIsolatedReducedResearch(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null
                || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .F1C_ISOLATED_MANUAL
                || session.maximumBusinessRequests()
                != TushareManualBoundedSession
                .F1C_MAX_PROVIDER_BUSINESS_REQUESTS
                || !session.allowedEndpoints().equals(
                TushareManualBoundedSession.F1C_ALLOWED_ENDPOINTS)
                || session.automaticRetryAllowed()) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RUNTIME_SESSION_INVALID");
        }
        if (request == null
                || !request.factTypes().equals(SUPPORTED_FACT_TYPES)) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RUNTIME_FACT_SCOPE_INVALID");
        }
        return fetch(
                request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /**
     * Explicit F1E path. A single shared session covers one to three
     * securities for exactly one natural day, with three calls per security
     * and no retry.
     */
    public MarketFactResponse fetchForDedicatedReducedResearch(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null
                || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .F1E_DEDICATED_LOCAL_MANUAL
                || session.maximumBusinessRequests()
                != session.allowedSymbols().size() * 3
                || session.maximumBusinessRequests()
                > TushareManualBoundedSession
                .F1E_MAX_PROVIDER_BUSINESS_REQUESTS
                || !session.allowedEndpoints().equals(
                TushareManualBoundedSession.F1E_ALLOWED_ENDPOINTS)
                || session.automaticRetryAllowed()
                || !session.allowedStart().equals(session.allowedEnd())) {
            throw new IllegalArgumentException(
                    "TUSHARE_DEDICATED_RESEARCH_SESSION_INVALID");
        }
        if (request == null
                || !request.factTypes().equals(SUPPORTED_FACT_TYPES)
                || !request.rangeStart().equals(request.rangeEnd())) {
            throw new IllegalArgumentException(
                    "TUSHARE_DEDICATED_RESEARCH_FACT_SCOPE_INVALID");
        }
        return fetch(
                request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /**
     * Explicit M1 manual window path. It keeps the same three accepted
     * endpoints and zero-retry transport while allowing a bounded historical
     * window for one to three main-board securities.
     */
    public MarketFactResponse fetchForM1ResearchData(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null
                || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .M1_RESEARCH_DATA_MANUAL
                || session.maximumBusinessRequests()
                != session.allowedSymbols().size() * 3
                || session.maximumBusinessRequests()
                > TushareManualBoundedSession
                .M1_MAX_PROVIDER_BUSINESS_REQUESTS
                || !session.allowedEndpoints().equals(
                TushareManualBoundedSession.F1E_ALLOWED_ENDPOINTS)
                || session.automaticRetryAllowed()) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_SESSION_INVALID");
        }
        if (request == null
                || !request.factTypes().equals(SUPPORTED_FACT_TYPES)
                || !request.rangeStart().equals(session.allowedStart())
                || !request.rangeEnd().equals(session.allowedEnd())) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_FACT_SCOPE_INVALID");
        }
        return fetch(request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /**
     * Fetches one fixed Universe security with two bounded window requests.
     * This avoids the provider's market-wide row limit while keeping the
     * endpoint/date/security allow-list explicit and zero-retry.
     */
    MarketFactResponse fetchForResearchUniverseSecurity(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .RESEARCH_UNIVERSE_V1
                || session.maximumBusinessRequests()
                != TushareManualBoundedSession
                .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS
                || !session.allowedEndpoints().equals(
                TushareManualBoundedSession.F1E_ALLOWED_ENDPOINTS)
                || session.automaticRetryAllowed()) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_SESSION_INVALID");
        }
        if (request == null
                || !request.rangeStart().equals(session.allowedStart())
                || !request.rangeEnd().equals(session.allowedEnd().minusDays(
                TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_CALENDAR_FORWARD_DAYS))
                || !request.factTypes().equals(Set.of(
                FactType.RAW_DAILY_BAR,
                FactType.ADJUSTMENT_FACTOR))) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_SECURITY_SCOPE_INVALID");
        }
        return fetch(request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /** V1.0.1 calendar query, exactly once for each represented exchange. */
    MarketFactResponse fetchForResearchUniverseCalendar(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .RESEARCH_UNIVERSE_V1
                || session.maximumBusinessRequests()
                != TushareManualBoundedSession
                .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS
                || session.automaticRetryAllowed()) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_SESSION_INVALID");
        }
        if (request == null || !request.factTypes().equals(
                Set.of(FactType.TRADING_CALENDAR))
                || !request.rangeStart().equals(session.allowedStart())
                || !request.rangeEnd().equals(session.allowedEnd())) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_CALENDAR_SCOPE_INVALID");
        }
        return fetch(request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /**
     * A single trade_date request can return a legal market-wide superset.
     * Mapping strictly retains the fixed allow-listed Universe identities.
     */
    List<MarketFactResponse> fetchResearchUniverseDateBulk(
            List<MarketFactRequest> requests,
            TushareManualBoundedSession session
    ) {
        List<MarketFactRequest> scope = List.copyOf(requests);
        if (session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .RESEARCH_UNIVERSE_DAILY_INCREMENT
                || scope.size()
                != TushareManualBoundedSession.RESEARCH_UNIVERSE_MAX_SYMBOLS
                || scope.stream().map(MarketFactRequest::rangeStart)
                .distinct().count() != 1
                || scope.stream().anyMatch(request ->
                !request.rangeStart().equals(request.rangeEnd())
                        || !request.factTypes().equals(Set.of(
                        FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR)))) {
            throw new IllegalArgumentException(
                    "RESEARCH_UNIVERSE_BULK_SCOPE_INVALID");
        }
        LocalDate date = scope.get(0).rangeStart();
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("trade_date", providerDate(date));
        QueryResult daily = gateway.query("daily", parameters,
                DAILY_FIELDS, scope.get(0).timeout(),
                QueryMode.CONTROLLED_NO_RETRY, session);
        QueryResult factors = gateway.query("adj_factor", parameters,
                FACTOR_FIELDS, scope.get(0).timeout(),
                QueryMode.CONTROLLED_NO_RETRY, session);
        List<MarketFactResponse> results = new ArrayList<>();
        for (MarketFactRequest request : scope) {
            List<RawDailyBar> bars = mapDaily(request, daily.table());
            List<AdjustmentFactor> mappedFactors = mapFactors(request,
                    factors.table());
            boolean complete = bars.size() == 1
                    && mappedFactors.size() == 1;
            List<ProviderError> errors = complete ? List.of()
                    : List.of(new ProviderError(
                    ProviderErrorType.UNAVAILABLE,
                    "RESEARCH_UNIVERSE_BULK_TARGET_MISSING",
                    "Fixed universe target row is missing", false, null));
            results.add(response(request, complete, bars, mappedFactors,
                    List.of(), errors, 0, 0,
                    QueryMode.CONTROLLED_NO_RETRY, session));
        }
        return List.copyOf(results);
    }

    /**
     * Fetches one complete market date with exactly two upstream calls and
     * retains only identities from the immutable stock_basic snapshot.
     */
    MarketFactResponse fetchMainboardMarketDate(
            List<MainboardInstrument> members,
            LocalDate tradeDate,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        List<MainboardInstrument> scope = List.copyOf(members);
        if (scope.isEmpty() || tradeDate == null || timeout == null
                || timeout.isZero() || timeout.isNegative()
                || session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .MAINBOARD_UNIVERSE_V1
                || !session.allowedEndpoints().containsAll(
                Set.of("daily", "adj_factor"))) {
            throw new IllegalArgumentException(
                    "MAINBOARD_MARKET_DATE_SCOPE_INVALID");
        }
        Map<String, MainboardInstrument> allowed = scope.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        MainboardInstrument::tsCode, value -> value));
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("trade_date", providerDate(tradeDate));
        QueryResult daily = gateway.query("daily", parameters, DAILY_FIELDS,
                timeout, QueryMode.CONTROLLED_NETWORK_RECOVERY, session);
        QueryResult factors = gateway.query("adj_factor", parameters,
                FACTOR_FIELDS, timeout,
                QueryMode.CONTROLLED_NETWORK_RECOVERY,
                session);
        rejectMarketWideTruncation("daily", daily);
        rejectMarketWideTruncation("adj_factor", factors);
        List<RawDailyBar> bars = mapMainboardDaily(
                daily.table(), allowed, tradeDate);
        List<AdjustmentFactor> mappedFactors = mapMainboardFactors(
                factors.table(), allowed, tradeDate);
        Set<String> barCodes = bars.stream().map(value ->
                tsCode(value.symbol(), value.exchange())).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> requiredFactorIdentities = bars.stream().map(value ->
                factorSourceIdentity(value.symbol(), value.exchange()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> availableFactorIdentities = mappedFactors.stream()
                .map(AdjustmentFactor::sourceIdentity)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        long expected = scope.stream().filter(value ->
                !value.listDate().isAfter(tradeDate)
                        && (value.delistDate() == null
                        || !value.delistDate().isBefore(tradeDate))).count();
        if (!availableFactorIdentities.containsAll(requiredFactorIdentities)
                || expected == 0
                || barCodes.size() * 100L
                < expected * MAINBOARD_MINIMUM_COVERAGE_PERCENT) {
            throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                    "MAINBOARD_MARKET_DATE_COVERAGE_INCOMPLETE",
                    "Market-wide daily and adjustment-factor coverage is incomplete",
                    daily.providerCallCount() + factors.providerCallCount(),
                    daily.rateLimitRetryCount()
                            + factors.rateLimitRetryCount(), null);
        }
        // adj_factor may legally contain a row for a suspended member for
        // which daily has no traded bar. Persist only the exact traded-date
        // intersection, while still requiring a factor for every daily bar.
        List<AdjustmentFactor> alignedFactors = mappedFactors.stream()
                .filter(value -> requiredFactorIdentities.contains(
                        value.sourceIdentity()))
                .toList();
        MainboardInstrument representative = scope.get(0);
        MarketFactRequest request = new MarketFactRequest(
                RunNamespace.FORMAL, PROVIDER_CODE,
                "MAINBOARD_MARKET_WIDE|" + tradeDate,
                representative.symbol(), representative.exchange(),
                tradeDate, tradeDate, Set.of(FactType.RAW_DAILY_BAR,
                FactType.ADJUSTMENT_FACTOR), timeout);
        return response(request, true, bars, alignedFactors, List.of(),
                List.of(), daily.providerCallCount()
                        + factors.providerCallCount(),
                daily.rateLimitRetryCount()
                        + factors.rateLimitRetryCount(),
                QueryMode.CONTROLLED_NETWORK_RECOVERY, session);
    }

    MarketFactResponse fetchMainboardCalendar(
            MainboardInstrument representative,
            String exchange,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        if (representative == null || !Set.of("SSE", "SZSE").contains(
                exchange) || rangeStart == null || rangeEnd == null
                || timeout == null || timeout.isZero() || timeout.isNegative()
                || session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .MAINBOARD_UNIVERSE_V1
                || !session.allowedEndpoints().contains("trade_cal")) {
            throw new IllegalArgumentException(
                    "MAINBOARD_CALENDAR_SCOPE_INVALID");
        }
        MarketFactRequest request = new MarketFactRequest(RunNamespace.FORMAL,
                PROVIDER_CODE, calendarSourceIdentity(exchange),
                representative.symbol(), exchange, rangeStart, rangeEnd,
                Set.of(FactType.TRADING_CALENDAR), timeout);
        QueryResult result = queryCalendar(request,
                QueryMode.CONTROLLED_NETWORK_RECOVERY, session);
        List<TradingCalendar> values = mapCalendar(request, result.table());
        long expectedDays = ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1;
        if (values.size() != expectedDays) {
            throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                    "MAINBOARD_CALENDAR_RESPONSE_INCOMPLETE",
                    "trade_cal response does not cover every requested date",
                    result.providerCallCount(),
                    result.rateLimitRetryCount(), null);
        }
        return response(request, true, List.of(), List.of(), values,
                List.of(), result.providerCallCount(),
                result.rateLimitRetryCount(),
                QueryMode.CONTROLLED_NETWORK_RECOVERY,
                session);
    }

    /** M4-only exchange calendar refresh; no price or factor endpoint. */
    MarketFactResponse fetchForM4CalendarAdmission(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .M4_CALENDAR_ADMISSION
                || session.maximumBusinessRequests() != 2
                || !session.allowedEndpoints().equals(Set.of("trade_cal"))
                || session.automaticRetryAllowed()) {
            throw new IllegalArgumentException(
                    "M4_CALENDAR_ADMISSION_SESSION_INVALID");
        }
        if (request == null
                || !request.factTypes().equals(
                Set.of(FactType.TRADING_CALENDAR))
                || !request.rangeStart().equals(session.allowedStart())
                || !request.rangeEnd().equals(session.allowedEnd())) {
            throw new IllegalArgumentException(
                    "M4_CALENDAR_ADMISSION_SCOPE_INVALID");
        }
        return fetch(request, QueryMode.CONTROLLED_NO_RETRY, session);
    }

    /**
     * Performs exactly one daily request for an already user-approved M1
     * credential verification. No database component is involved.
     */
    M1TokenVerification verifyM1Token(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (session == null
                || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .M1_TOKEN_VERIFICATION
                || session.maximumBusinessRequests() != 1
                || !session.allowedEndpoints().equals(Set.of("daily"))
                || session.automaticRetryAllowed()) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_SESSION_INVALID");
        }
        if (request == null
                || !request.factTypes().equals(Set.of(FactType.RAW_DAILY_BAR))
                || !request.rangeStart().equals(request.rangeEnd())
                || !request.rangeStart().equals(session.allowedStart())
                || !request.rangeEnd().equals(session.allowedEnd())) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_SCOPE_INVALID");
        }
        validateRequest(request, session);
        properties.requireManualBoundedToken();
        QueryResult result = queryDaily(
                request, QueryMode.CONTROLLED_NO_RETRY, session);
        List<RawDailyBar> bars = mapDaily(request, result.table());
        boolean targetPresent = bars.stream().anyMatch(bar ->
                bar.symbol().equals(request.symbol())
                        && bar.exchange().equals(request.exchange())
                        && bar.tradeDate().equals(request.rangeStart()));
        return new M1TokenVerification(
                result.providerCallCount(), result.rateLimitRetryCount(),
                result.table().fields().size(), result.table().rows().size(),
                bars.size(), targetPresent);
    }

    record M1TokenVerification(
            int providerCallCount,
            int retryCount,
            int fieldCount,
            int rowCount,
            int mappedRowCount,
            boolean targetRowPresent
    ) {
        M1TokenVerification {
            if (providerCallCount != 1 || retryCount != 0
                    || fieldCount < 1 || rowCount < 0 || mappedRowCount < 0
                    || mappedRowCount > rowCount) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_TOKEN_VERIFICATION_RESULT_INVALID");
            }
        }
    }

    private MarketFactResponse fetch(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        validateRequest(request, session);
        properties.requireManualBoundedToken();
        if (session == null) {
            throw new IllegalArgumentException(
                    "Tushare MANUAL_BOUNDED session is required");
        }
        List<RawDailyBar> rawDailyBars = new ArrayList<>();
        List<AdjustmentFactor> adjustmentFactors = new ArrayList<>();
        List<TradingCalendar> tradingCalendar = new ArrayList<>();
        List<ProviderError> errors = new ArrayList<>();
        int providerCalls = 0;
        int retryCount = 0;

        for (FactType type : request.factTypes().stream().sorted().toList()) {
            try {
                QueryResult result = switch (type) {
                    case RAW_DAILY_BAR ->
                            queryDaily(request, mode, session);
                    case ADJUSTMENT_FACTOR ->
                            queryFactor(request, mode, session);
                    case TRADING_CALENDAR ->
                            queryCalendar(request, mode, session);
                    case CORPORATE_ACTION -> throw new IllegalArgumentException(
                            "Tushare corporate actions remain outside F1A");
                };
                providerCalls += result.providerCallCount();
                retryCount += result.rateLimitRetryCount();
                switch (type) {
                    case RAW_DAILY_BAR ->
                            rawDailyBars.addAll(mapDaily(request, result.table()));
                    case ADJUSTMENT_FACTOR ->
                            adjustmentFactors.addAll(
                                    mapFactors(request, result.table()));
                    case TRADING_CALENDAR ->
                            tradingCalendar.addAll(
                                    mapCalendar(request, result.table()));
                    case CORPORATE_ACTION -> {
                        // Rejected before any network call.
                    }
                }
            } catch (GatewayException error) {
                providerCalls += error.providerCallCount();
                retryCount += error.rateLimitRetryCount();
                errors.add(providerError(error));
                break;
            } catch (RuntimeException error) {
                errors.add(new ProviderError(
                        ProviderErrorType.STRUCTURE_CHANGED,
                        mappingFailureCode(type),
                        "Tushare response could not be mapped safely",
                        false,
                        null));
                break;
            }
        }
        rawDailyBars.sort(Comparator.comparing(RawDailyBar::tradeDate));
        adjustmentFactors.sort(Comparator.comparing(
                AdjustmentFactor::factorEffectiveTradeDate));
        tradingCalendar.sort(Comparator.comparing(
                TradingCalendar::calendarDate));
        return response(
                request,
                errors.isEmpty(),
                rawDailyBars,
                adjustmentFactors,
                tradingCalendar,
                errors,
                providerCalls,
                retryCount,
                mode,
                session);
    }

    private QueryResult queryDaily(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode parameters = baseSecurityParameters(request);
        return gateway.query(
                "daily", parameters, DAILY_FIELDS, request.timeout(), mode,
                session);
    }

    private QueryResult queryFactor(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode parameters = baseSecurityParameters(request);
        return gateway.query(
                "adj_factor", parameters, FACTOR_FIELDS,
                request.timeout(), mode, session);
    }

    private QueryResult queryCalendar(
            MarketFactRequest request,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("exchange", request.exchange());
        parameters.put("start_date", providerDate(request.rangeStart()));
        parameters.put("end_date", providerDate(request.rangeEnd()));
        return gateway.query(
                "trade_cal", parameters, CALENDAR_FIELDS,
                request.timeout(), mode, session);
    }

    /**
     * Reads ordinary provider identity fields. The result is explicitly
     * PARTIAL and does not assert a permanent security identity.
     */
    public ReferenceDataResponse<InstrumentIdentity>
    fetchInstrumentIdentityForControlledAcceptance(
            String symbol,
            String exchange,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        validateReferenceRequest(symbol, exchange, timeout, session);
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("ts_code", tsCode(symbol, exchange));
        QueryResult result = gateway.query(
                "stock_basic",
                parameters,
                STOCK_BASIC_FIELDS,
                timeout,
                QueryMode.CONTROLLED_NO_RETRY,
                session);
        validateReferenceRowLimit(
                "stock_basic", result, STOCK_BASIC_MAX_ROWS);
        List<InstrumentIdentity> identities =
                mapInstrumentIdentities(symbol, exchange, result.table());
        return new ReferenceDataResponse<>(
                "stock_basic",
                result.table().fields(),
                identities,
                result.providerCallCount(),
                result.rateLimitRetryCount(),
                false);
    }

    /** One official current-listed main-board snapshot; no code-prefix filter. */
    MainboardReferenceResponse fetchMainboardUniverseSnapshot(
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        properties.requireManualBoundedToken();
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || session == null || session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .MAINBOARD_UNIVERSE_V1
                || !session.allowedEndpoints().contains("stock_basic")) {
            throw new IllegalArgumentException(
                    "MAINBOARD_STOCK_BASIC_SCOPE_INVALID");
        }
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("market", "主板");
        parameters.put("list_status", "L");
        QueryResult result = gateway.query("stock_basic", parameters,
                MAINBOARD_STOCK_BASIC_FIELDS, timeout,
                QueryMode.CONTROLLED_NETWORK_RECOVERY, session);
        rejectMarketWideTruncation("stock_basic", result);
        List<MainboardInstrument> instruments = mapMainboardInstruments(
                result.table());
        long sse = instruments.stream().filter(value ->
                "SSE".equals(value.exchange())).count();
        long szse = instruments.size() - sse;
        if (instruments.size() < ResearchUniverseMainboard
                .MINIMUM_PLAUSIBLE_MEMBER_COUNT || sse == 0 || szse == 0) {
            throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                    "MAINBOARD_STOCK_BASIC_COVERAGE_INCOMPLETE",
                    "stock_basic result cannot prove complete SSE/SZSE main-board membership",
                    result.providerCallCount(),
                    result.rateLimitRetryCount(), null);
        }
        String sourceFingerprint = sha256(MAINBOARD_STOCK_BASIC_FIELDS
                + "|" + instruments.stream().map(value ->
                value.tsCode() + '|' + value.contentHash() + '\n')
                .reduce("", String::concat));
        return new MainboardReferenceResponse("stock_basic",
                result.table().fields(), instruments,
                result.providerCallCount(), result.rateLimitRetryCount(),
                sourceFingerprint, true);
    }

    /**
     * Reads partial dividend evidence without creating a V13 corporate action.
     */
    public ReferenceDataResponse<DividendEvidence>
    fetchDividendEvidenceForControlledAcceptance(
            String symbol,
            String exchange,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        validateReferenceRequest(symbol, exchange, timeout, session);
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("ts_code", tsCode(symbol, exchange));
        QueryResult result = gateway.query(
                "dividend",
                parameters,
                DIVIDEND_FIELDS,
                timeout,
                QueryMode.CONTROLLED_NO_RETRY,
                session);
        validateReferenceRowLimit(
                "dividend", result, DIVIDEND_EVIDENCE_MAX_ROWS);
        List<DividendEvidence> evidence =
                mapDividendEvidence(symbol, exchange, result.table());
        return new ReferenceDataResponse<>(
                "dividend",
                result.table().fields(),
                evidence,
                result.providerCallCount(),
                result.rateLimitRetryCount(),
                false);
    }

    private static void validateReferenceRowLimit(
            String endpoint,
            QueryResult result,
            int maximumRows
    ) {
        if (result.table().rows().size() <= maximumRows) {
            return;
        }
        throw new GatewayException(
                ErrorKind.STRUCTURE_CHANGED,
                "TUSHARE_REFERENCE_ROW_LIMIT_EXCEEDED",
                "Tushare " + endpoint
                        + " response exceeds the bounded row limit",
                result.providerCallCount(),
                result.rateLimitRetryCount(),
                null);
    }

    private List<InstrumentIdentity> mapInstrumentIdentities(
            String symbol,
            String exchange,
            Table table
    ) {
        List<RowView> rows = rows(table, STOCK_BASIC_FIELDS);
        if (rows.size() > 1) {
            throw new IllegalArgumentException(
                    "duplicate Tushare stock_basic identity");
        }
        List<InstrumentIdentity> values = new ArrayList<>();
        for (RowView row : rows) {
            String expectedTsCode = tsCode(symbol, exchange);
            if (!expectedTsCode.equals(row.text("ts_code"))
                    || !symbol.equals(row.text("symbol"))
                    || !exchange.equals(row.text("exchange"))) {
                throw new IllegalArgumentException(
                        "Tushare stock_basic identity mismatch");
            }
            values.add(new InstrumentIdentity(
                    expectedTsCode,
                    symbol,
                    exchange,
                    row.text("name"),
                    row.text("market"),
                    row.text("list_status"),
                    row.nullableDate("list_date"),
                    row.nullableDate("delist_date")));
        }
        return List.copyOf(values);
    }

    private List<MainboardInstrument> mapMainboardInstruments(Table table) {
        List<RowView> rows = rows(table, MAINBOARD_STOCK_BASIC_FIELDS);
        Map<String, MainboardInstrument> values = new LinkedHashMap<>();
        for (RowView row : rows) {
            String symbol = row.text("symbol");
            String exchange = row.text("exchange");
            String tsCode = row.text("ts_code");
            String market = row.text("market");
            String listStatus = row.text("list_status");
            LocalDate listDate = row.date("list_date");
            LocalDate delistDate = row.nullableDate("delist_date");
            String name = row.text("name");
            String industry = row.nullableText("industry");
            if (!Set.of("SSE", "SZSE").contains(exchange)
                    || !"主板".equals(market) || !"L".equals(listStatus)
                    || !tsCode.equals(tsCode(symbol, exchange))
                    || delistDate != null) {
                throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                        "MAINBOARD_STOCK_BASIC_ROW_INVALID",
                        "stock_basic returned an identity outside the requested current main-board scope",
                        1, 0, null);
            }
            String normalizedIndustry = industry == null
                    || industry.isBlank() ? "未分类" : industry;
            String contentHash = sha256(String.join("|", tsCode, symbol,
                    exchange, name, normalizedIndustry, market, listStatus,
                    listDate.toString(), ""));
            MainboardInstrument instrument = new MainboardInstrument(tsCode,
                    symbol, exchange, name, normalizedIndustry, market,
                    listStatus, listDate, null, contentHash);
            if (values.put(tsCode, instrument) != null) {
                throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                        "MAINBOARD_STOCK_BASIC_DUPLICATE",
                        "stock_basic returned duplicate main-board identity",
                        1, 0, null);
            }
        }
        if (values.isEmpty()) {
            throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                    "MAINBOARD_STOCK_BASIC_EMPTY",
                    "stock_basic returned no current main-board identities",
                    1, 0, null);
        }
        return values.values().stream().sorted(Comparator.comparing(
                MainboardInstrument::tsCode)).toList();
    }

    private List<RawDailyBar> mapMainboardDaily(
            Table table,
            Map<String, MainboardInstrument> allowed,
            LocalDate expectedDate
    ) {
        List<RawDailyBar> values = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (RowView row : rows(table, DAILY_FIELDS)) {
            MainboardInstrument member = allowed.get(row.text("ts_code"));
            if (member == null) continue;
            LocalDate tradeDate = row.date("trade_date");
            if (!expectedDate.equals(tradeDate)
                    || tradeDate.isBefore(member.listDate())
                    || member.delistDate() != null
                    && tradeDate.isAfter(member.delistDate())
                    || !identities.add(member.tsCode())) {
                throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                        "MAINBOARD_DAILY_RESPONSE_INVALID",
                        "Market-wide daily response has duplicate or wrong-date rows",
                        1, 0, null);
            }
            BigDecimal providerVolume = row.nullableDecimal("vol");
            BigDecimal providerAmount = row.nullableDecimal("amount");
            values.add(new RawDailyBar(
                    rawSourceIdentity(member.symbol(), member.exchange()),
                    member.symbol(), member.exchange(), tradeDate,
                    row.decimal("open"), row.decimal("high"),
                    row.decimal("low"), row.decimal("close"),
                    qualified(providerVolume == null ? null
                                    : providerVolume.movePointRight(2),
                            MarketFieldUnit.SHARES,
                            MarketFieldSemantic.TRADED_VOLUME),
                    qualified(providerAmount == null ? null
                                    : providerAmount.movePointRight(3),
                            MarketFieldUnit.CNY,
                            MarketFieldSemantic.TRADED_AMOUNT),
                    qualified(null, MarketFieldUnit.RATIO,
                            MarketFieldSemantic.TURNOVER_RATE),
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload("daily", row, Map.of(
                            "vol", "HANDS_TO_SHARES_X100",
                            "amount", "THOUSAND_CNY_TO_CNY_X1000"))));
        }
        return List.copyOf(values);
    }

    private List<AdjustmentFactor> mapMainboardFactors(
            Table table,
            Map<String, MainboardInstrument> allowed,
            LocalDate expectedDate
    ) {
        List<AdjustmentFactor> values = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (RowView row : rows(table, FACTOR_FIELDS)) {
            MainboardInstrument member = allowed.get(row.text("ts_code"));
            if (member == null) continue;
            LocalDate tradeDate = row.date("trade_date");
            if (!expectedDate.equals(tradeDate)
                    || tradeDate.isBefore(member.listDate())
                    || member.delistDate() != null
                    && tradeDate.isAfter(member.delistDate())
                    || !identities.add(member.tsCode())) {
                throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                        "MAINBOARD_ADJ_FACTOR_RESPONSE_INVALID",
                        "Market-wide adjustment-factor response has duplicate or wrong-date rows",
                        1, 0, null);
            }
            values.add(new AdjustmentFactor(
                    factorSourceIdentity(member.symbol(), member.exchange()),
                    member.symbol(), tradeDate,
                    PitMarketFactsContracts.FACTOR_TYPE,
                    PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                    row.decimal("adj_factor"), SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload("adj_factor", row, Map.of())));
        }
        return List.copyOf(values);
    }

    private static void rejectMarketWideTruncation(
            String endpoint,
            QueryResult result
    ) {
        int rows = result.table().rows().size();
        if (rows <= 0 || rows >= MAINBOARD_MARKET_MAX_ROWS) {
            throw new GatewayException(ErrorKind.STRUCTURE_CHANGED,
                    "MAINBOARD_PROVIDER_RESPONSE_TRUNCATED",
                    "Tushare " + endpoint
                            + " response is empty or reached the fail-closed row limit",
                    result.providerCallCount(),
                    result.rateLimitRetryCount(), null);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "MAINBOARD_SHA256_UNAVAILABLE", error);
        }
    }

    private List<DividendEvidence> mapDividendEvidence(
            String symbol,
            String exchange,
            Table table
    ) {
        List<RowView> rows = rows(table, DIVIDEND_FIELDS);
        List<DividendEvidence> values = new ArrayList<>();
        String expectedTsCode = tsCode(symbol, exchange);
        for (RowView row : rows) {
            if (!expectedTsCode.equals(row.text("ts_code"))) {
                throw new IllegalArgumentException(
                        "Tushare dividend identity mismatch");
            }
            values.add(new DividendEvidence(
                    expectedTsCode,
                    row.nullableDate("end_date"),
                    row.nullableDate("ann_date"),
                    row.nullableDate("imp_ann_date"),
                    row.nullableText("div_proc"),
                    row.nullableDecimal("stk_div"),
                    row.nullableDecimal("stk_bo_rate"),
                    row.nullableDecimal("stk_co_rate"),
                    row.nullableDecimal("cash_div"),
                    row.nullableDecimal("cash_div_tax"),
                    row.nullableDate("record_date"),
                    row.nullableDate("ex_date"),
                    row.nullableDate("pay_date"),
                    row.nullableDate("div_listdate")));
        }
        return List.copyOf(values);
    }

    private void validateReferenceRequest(
            String symbol,
            String exchange,
            Duration timeout,
            TushareManualBoundedSession session
    ) {
        properties.requireManualBoundedToken();
        if (!isMainBoard(symbol, exchange)) {
            throw new IllegalArgumentException(
                    "Tushare F1A is restricted to main-board securities");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "invalid Tushare reference timeout");
        }
        if (session == null) {
            throw new IllegalArgumentException(
                    "Tushare MANUAL_BOUNDED session is required");
        }
    }

    private ObjectNode baseSecurityParameters(MarketFactRequest request) {
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("ts_code", tsCode(
                request.symbol(), request.exchange()));
        parameters.put("start_date", providerDate(request.rangeStart()));
        parameters.put("end_date", providerDate(request.rangeEnd()));
        return parameters;
    }

    private List<RawDailyBar> mapDaily(
            MarketFactRequest request,
            Table table
    ) {
        List<RowView> rows = rows(table, DAILY_FIELDS);
        List<RawDailyBar> values = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        String expectedTsCode = tsCode(
                request.symbol(), request.exchange());
        for (RowView row : rows) {
            if (!expectedTsCode.equals(row.text("ts_code"))) {
                continue;
            }
            LocalDate tradeDate = row.date("trade_date");
            if (!insideRequestedRange(request, tradeDate)) {
                continue;
            }
            if (!dates.add(tradeDate)) {
                throw new IllegalArgumentException(
                        "duplicate Tushare daily row");
            }
            BigDecimal providerVolume = row.nullableDecimal("vol");
            BigDecimal providerAmount = row.nullableDecimal("amount");
            values.add(new RawDailyBar(
                    rawSourceIdentity(request.symbol(), request.exchange()),
                    request.symbol(),
                    request.exchange(),
                    tradeDate,
                    row.decimal("open"),
                    row.decimal("high"),
                    row.decimal("low"),
                    row.decimal("close"),
                    qualified(
                            providerVolume == null ? null
                                    : providerVolume.movePointRight(2),
                            MarketFieldUnit.SHARES,
                            MarketFieldSemantic.TRADED_VOLUME),
                    qualified(
                            providerAmount == null ? null
                                    : providerAmount.movePointRight(3),
                            MarketFieldUnit.CNY,
                            MarketFieldSemantic.TRADED_AMOUNT),
                    qualified(
                            null,
                            MarketFieldUnit.RATIO,
                            MarketFieldSemantic.TURNOVER_RATE),
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload(
                            "daily", row, Map.of(
                                    "vol", "HANDS_TO_SHARES_X100",
                                    "amount",
                                    "THOUSAND_CNY_TO_CNY_X1000"))));
        }
        return List.copyOf(values);
    }

    private List<AdjustmentFactor> mapFactors(
            MarketFactRequest request,
            Table table
    ) {
        List<RowView> rows = rows(table, FACTOR_FIELDS);
        List<AdjustmentFactor> values = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        String expectedTsCode = tsCode(
                request.symbol(), request.exchange());
        for (RowView row : rows) {
            if (!expectedTsCode.equals(row.text("ts_code"))) {
                continue;
            }
            LocalDate tradeDate = row.date("trade_date");
            if (!insideRequestedRange(request, tradeDate)) {
                continue;
            }
            if (!dates.add(tradeDate)) {
                throw new IllegalArgumentException(
                        "duplicate Tushare factor row");
            }
            values.add(new AdjustmentFactor(
                    factorSourceIdentity(
                            request.symbol(), request.exchange()),
                    request.symbol(),
                    tradeDate,
                    PitMarketFactsContracts.FACTOR_TYPE,
                    PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                    row.decimal("adj_factor"),
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload("adj_factor", row, Map.of())));
        }
        return List.copyOf(values);
    }

    private List<TradingCalendar> mapCalendar(
            MarketFactRequest request,
            Table table
    ) {
        List<RowView> rows = rows(table, CALENDAR_FIELDS);
        List<TradingCalendar> values = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (RowView row : rows) {
            if (!request.exchange().equals(row.text("exchange"))) {
                continue;
            }
            LocalDate calendarDate = row.date("cal_date");
            if (!insideRequestedRange(request, calendarDate)) {
                continue;
            }
            if (!dates.add(calendarDate)) {
                throw new IllegalArgumentException(
                        "duplicate Tushare calendar row");
            }
            int openValue = row.integer("is_open");
            if (openValue != 0 && openValue != 1) {
                throw new IllegalArgumentException(
                        "invalid Tushare calendar open state");
            }
            boolean open = openValue == 1;
            values.add(new TradingCalendar(
                    calendarSourceIdentity(request.exchange()),
                    request.exchange(),
                    calendarDate,
                    open,
                    open ? "REGULAR" : "CLOSED",
                    SYSTEM_KNOWLEDGE_VERSION,
                    rawPayload("trade_cal", row, Map.of())));
        }
        return List.copyOf(values);
    }

    private List<RowView> rows(
            Table table,
            List<String> requiredFields
    ) {
        if (!table.fields().containsAll(requiredFields)) {
            throw new IllegalArgumentException(
                    "Tushare response fields are incomplete");
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < table.fields().size(); index++) {
            indexes.put(table.fields().get(index), index);
        }
        return table.rows().stream()
                .map(row -> new RowView(indexes, row))
                .toList();
    }

    private ObjectNode rawPayload(
            String endpoint,
            RowView row,
            Map<String, String> conversions
    ) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("provider", PROVIDER_CODE);
        result.put("endpoint", endpoint);
        result.put("providerVersionQualification",
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY.name());
        ObjectNode values = result.putObject("providerRow");
        row.indexes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> values.set(
                        entry.getKey(),
                        row.values.get(entry.getValue()).deepCopy()));
        ObjectNode units = result.putObject("unitConversions");
        conversions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> units.put(
                        entry.getKey(), entry.getValue()));
        return result;
    }

    private MarketFactResponse response(
            MarketFactRequest request,
            boolean complete,
            List<RawDailyBar> bars,
            List<AdjustmentFactor> factors,
            List<TradingCalendar> calendar,
            List<ProviderError> errors,
            int providerCalls,
            int retryCount,
            QueryMode mode,
            TushareManualBoundedSession session
    ) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("implementationScope", IMPLEMENTATION_SCOPE);
        metadata.put("providerCallCount", providerCalls);
        metadata.put("rateLimitRetryCount",
                mode == QueryMode.CONTROLLED_NETWORK_RECOVERY
                        ? 0 : retryCount);
        metadata.put("networkRecoveryCount",
                mode == QueryMode.CONTROLLED_NETWORK_RECOVERY
                        ? retryCount : 0);
        metadata.put("queryMode", mode.name());
        metadata.put("tushareMode",
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED.name());
        metadata.put("sessionMaximumBusinessRequests",
                session.maximumBusinessRequests());
        metadata.put("sessionConsumedBusinessRequests",
                session.consumedBusinessRequests());
        metadata.put("sessionProfile",
                session.sessionProfile().name());
        metadata.put("automaticRetryAllowed",
                session.automaticRetryAllowed());
        metadata.put("systemKnowledgeOnly", true);
        metadata.put("formalEligible", false);
        metadata.put("corporateActionLineageComplete", false);
        ArrayNode requested = metadata.putArray("requestedFactTypes");
        request.factTypes().stream().sorted()
                .forEach(type -> requested.add(type.name()));
        return new MarketFactResponse(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                PROVIDER_CODE,
                ADAPTER_VERSION,
                request.runNamespace(),
                request.sourceCode(),
                request.sourceInstrumentId(),
                request.rangeStart(),
                request.rangeEnd(),
                complete,
                capability(),
                bars,
                factors,
                calendar,
                List.of(),
                errors,
                metadata);
    }

    private static ProviderError providerError(GatewayException error) {
        ProviderErrorType type = switch (error.kind()) {
            case PERMISSION_DENIED -> ProviderErrorType.PERMISSION_DENIED;
            case RATE_LIMITED -> ProviderErrorType.RATE_LIMITED;
            case TIMEOUT -> ProviderErrorType.TIMEOUT;
            case STRUCTURE_CHANGED ->
                    ProviderErrorType.STRUCTURE_CHANGED;
            case NETWORK_ERROR, API_ERROR ->
                    ProviderErrorType.UNAVAILABLE;
        };
        return new ProviderError(
                type,
                error.safeCode(),
                error.getMessage(),
                false,
                null);
    }

    private static String mappingFailureCode(FactType type) {
        return switch (type) {
            case RAW_DAILY_BAR ->
                    "TUSHARE_DAILY_RESPONSE_MAPPING_INVALID";
            case ADJUSTMENT_FACTOR ->
                    "TUSHARE_ADJ_FACTOR_RESPONSE_MAPPING_INVALID";
            case TRADING_CALENDAR ->
                    "TUSHARE_TRADE_CAL_RESPONSE_MAPPING_INVALID";
            case CORPORATE_ACTION ->
                    "TUSHARE_CORPORATE_ACTION_RESPONSE_MAPPING_INVALID";
        };
    }

    private void validateRequest(MarketFactRequest request) {
        validateRequest(request, null);
    }

    private void validateRequest(
            MarketFactRequest request,
            TushareManualBoundedSession session
    ) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Tushare request is required");
        }
        if (request.runNamespace() != RunNamespace.FORMAL) {
            throw new IllegalArgumentException(
                    "Tushare requires FORMAL namespace");
        }
        if (!PROVIDER_CODE.equals(request.sourceCode())) {
            throw new IllegalArgumentException(
                    "Tushare sourceCode mismatch");
        }
        if (!SUPPORTED_FACT_TYPES.containsAll(request.factTypes())) {
            throw new IllegalArgumentException(
                    "Tushare requested fact type is outside F1A");
        }
        if (!sourceInstrumentId(
                request.symbol(), request.exchange())
                .equals(request.sourceInstrumentId())) {
            throw new IllegalArgumentException(
                    "Tushare source instrument identity mismatch");
        }
        if (!isMainBoard(request.symbol(), request.exchange())) {
            throw new IllegalArgumentException(
                    "Tushare F1A is restricted to Shanghai/Shenzhen main board");
        }
        long naturalDays = ChronoUnit.DAYS.between(
                request.rangeStart(), request.rangeEnd()) + 1;
        long maximumDays = session != null && session.sessionProfile()
                == TushareManualBoundedSession.SessionProfile
                .RESEARCH_UNIVERSE_V1
                ? TushareManualBoundedSession
                .RESEARCH_UNIVERSE_MAX_NATURAL_DAYS
                : MAXIMUM_NATURAL_DAYS;
        if (naturalDays > maximumDays) {
            throw new IllegalArgumentException(
                    "Tushare request range exceeds F1A limit");
        }
    }

    private static boolean insideRequestedRange(
            MarketFactRequest request,
            LocalDate date
    ) {
        return !date.isBefore(request.rangeStart())
                && !date.isAfter(request.rangeEnd());
    }

    public static String sourceInstrumentId(
            String symbol,
            String exchange
    ) {
        return "TUSHARE:SECURITY:" + tsCode(symbol, exchange);
    }

    public static String rawSourceIdentity(
            String symbol,
            String exchange
    ) {
        return sourceInstrumentId(symbol, exchange);
    }

    public static String factorSourceIdentity(
            String symbol,
            String exchange
    ) {
        return "TUSHARE:ADJ_FACTOR:"
                + tsCode(symbol, exchange);
    }

    public static String calendarSourceIdentity(String exchange) {
        if (!"SSE".equals(exchange) && !"SZSE".equals(exchange)) {
            throw new IllegalArgumentException(
                    "invalid Tushare exchange");
        }
        return "TUSHARE:TRADE_CAL:" + exchange;
    }

    private static String tsCode(String symbol, String exchange) {
        if (symbol == null || !symbol.matches("[0-9]{6}")) {
            throw new IllegalArgumentException(
                    "invalid Tushare symbol");
        }
        return switch (exchange) {
            case "SSE" -> symbol + ".SH";
            case "SZSE" -> symbol + ".SZ";
            default -> throw new IllegalArgumentException(
                    "invalid Tushare exchange");
        };
    }

    private static boolean isMainBoard(
            String symbol,
            String exchange
    ) {
        return switch (exchange) {
            case "SSE" -> symbol.matches("60[0135][0-9]{3}");
            case "SZSE" -> symbol.matches("00[0123][0-9]{3}");
            default -> false;
        };
    }

    private static String providerDate(LocalDate date) {
        return PROVIDER_DATE.format(date);
    }

    private static QualifiedMarketField qualified(
            BigDecimal value,
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new QualifiedMarketField(
                value,
                value == null
                        ? FieldQualification.MISSING
                        : FieldQualification.PRESENT_VERIFIED,
                unit,
                semantic);
    }

    private static final class RowView {
        private final Map<String, Integer> indexes;
        private final List<JsonNode> values;

        private RowView(
                Map<String, Integer> indexes,
                List<JsonNode> values
        ) {
            this.indexes = Map.copyOf(indexes);
            this.values = List.copyOf(values);
        }

        private JsonNode value(String field) {
            Integer index = indexes.get(field);
            if (index == null || index >= values.size()) {
                throw new IllegalArgumentException(
                        "missing Tushare field " + field);
            }
            return values.get(index);
        }

        private String text(String field) {
            String value = nullableText(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "invalid Tushare text field " + field);
            }
            return value;
        }

        private String nullableText(String field) {
            JsonNode value = value(field);
            if (value.isNull()
                    || value.isTextual() && value.asText().isBlank()) {
                return null;
            }
            if (!value.isTextual()) {
                throw new IllegalArgumentException(
                        "invalid Tushare text field " + field);
            }
            return value.asText();
        }

        private BigDecimal decimal(String field) {
            BigDecimal value = nullableDecimal(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing Tushare decimal field " + field);
            }
            return value;
        }

        private BigDecimal nullableDecimal(String field) {
            JsonNode value = value(field);
            if (value.isNull()) return null;
            if (value.isNumber()) return value.decimalValue();
            if (value.isTextual() && value.asText().isBlank()) return null;
            if (value.isTextual()
                    && value.asText().matches(
                    "-?[0-9]+(?:\\.[0-9]+)?")) {
                return new BigDecimal(value.asText());
            }
            throw new IllegalArgumentException(
                    "invalid Tushare decimal field " + field);
        }

        private int integer(String field) {
            JsonNode value = value(field);
            if (!value.canConvertToInt()) {
                throw new IllegalArgumentException(
                        "invalid Tushare integer field " + field);
            }
            return value.intValue();
        }

        private LocalDate date(String field) {
            LocalDate value = nullableDate(field);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing Tushare date field " + field);
            }
            return value;
        }

        private LocalDate nullableDate(String field) {
            String value = nullableText(field);
            if (value == null) {
                return null;
            }
            try {
                return LocalDate.parse(value, PROVIDER_DATE);
            } catch (DateTimeParseException error) {
                throw new IllegalArgumentException(
                        "invalid Tushare date field " + field, error);
            }
        }
    }
}
