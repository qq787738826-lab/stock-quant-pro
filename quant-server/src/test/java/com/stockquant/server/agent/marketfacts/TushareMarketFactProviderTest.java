package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderErrorType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.ErrorKind;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.GatewayException;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryMode;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.QueryResult;
import com.stockquant.server.agent.marketfacts.TushareApiGateway.Table;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.DividendEvidence;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.MainboardInstrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareMarketFactProviderTest {

    private static final String TEST_TOKEN = "provider-unit-test-token";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void capabilityFreezesPartialEvidenceAndProcessOnlyQuotas() {
        TushareMarketFactProvider provider =
                provider(new FixtureGateway(mapper));
        var capability = provider.capability();
        assertEquals(
                Set.of(
                        FactType.RAW_DAILY_BAR,
                        FactType.ADJUSTMENT_FACTOR,
                        FactType.TRADING_CALENDAR),
                capability.supportedFactTypes());
        assertTrue(capability.localPersistenceAllowed());
        assertTrue(capability.historicalReplayAllowed());
        assertTrue(capability.backtestAllowed());
        assertTrue(capability.agentUseAllowed());
        assertFalse(capability.revisionIdAvailable());
        assertFalse(capability.historicalVersionsQueryable());
        assertEquals("PARTIAL",
                capability.coverage()
                        .path("stableSecurityIdentity").asText());
        assertEquals("PARTIAL_NOT_V13_ELIGIBLE",
                capability.coverage()
                        .path("dividendEvidence").asText());
        assertEquals("REDUCED_RESEARCH_ONLY",
                capability.coverage()
                        .path("technicalRouteDecision").asText());
        assertFalse(capability.coverage()
                .path("fullTechnicalContractReady").asBoolean(true));
        assertTrue(capability.coverage()
                .path("reducedResearchContractReady").asBoolean());
        assertEquals("CONTRACT_DEFINED_ISOLATED_MANUAL_READY",
                capability.coverage()
                        .path("tushareReducedResearchContract")
                        .asText());
        assertFalse(capability.coverage()
                .path("reducedResearchRuntimeReady").asBoolean(true));
        assertTrue(capability.coverage()
                .path("reducedResearchIsolatedManualRuntimeReady")
                .asBoolean());
        assertFalse(capability.coverage()
                .path("reducedResearchProductionRuntimeReady")
                .asBoolean(true));
        assertFalse(capability.coverage()
                .path("normalBusinessDatabaseRuntimeReady")
                .asBoolean(true));
        assertEquals("DEDICATED_LOCAL_RESEARCH_PATH",
                capability.coverage()
                        .path("reducedResearchRouteDecision").asText());
        assertTrue(capability.coverage()
                .path("reducedResearchLocalRuntimeImplementationReady")
                .asBoolean());
        assertTrue(capability.coverage()
                .path("reducedResearchControlledAcceptanceReady")
                .asBoolean());
        assertFalse(capability.coverage()
                .path("reducedResearchOperationalReady")
                .asBoolean(true));
        assertTrue(capability.coverage()
                .path("dedicatedLocalResearchDatabaseRequired")
                .asBoolean());
        assertFalse(capability.coverage()
                .path("schedulerRuntimeReady").asBoolean(true));
        assertFalse(capability.coverage()
                .path("agentDecisionRuntimeReady").asBoolean(true));
        assertFalse(capability.coverage()
                .path("backtestExecutionRuntimeReady").asBoolean(true));
        assertFalse(capability.coverage()
                .path("f2bRuntimeReady").asBoolean(true));
        assertFalse(capability.coverage()
                .path("f3RuntimeReady").asBoolean(true));
        assertFalse(capability.coverage()
                .path("normalBusinessDatabaseRuntimeReady")
                .asBoolean(true));
        assertFalse(capability.coverage()
                .path("schedulerRuntimeReady").asBoolean(true));
        assertEquals("RAW_FACTOR_END_DATE_ANCHORED",
                capability.coverage()
                        .path("qfqCalculationMode").asText());
        assertEquals("REQUESTED_END_DATE_FACTOR",
                capability.coverage()
                        .path("qfqAnchorSemantics").asText());
        assertEquals("VERIFIED",
                capability.coverage()
                        .path("qfqFormulaQualification").asText());
        assertEquals("PARTIAL",
                capability.coverage()
                        .path("qfqOperationalRuntimeQualification")
                        .asText());
        assertEquals("VERIFIED",
                capability.coverage()
                        .path("qfqReducedResearchRuntimeQualification")
                        .asText());
        assertEquals("PARTIAL",
                capability.coverage()
                        .path("qfqFullLineageRuntimeQualification")
                        .asText());
        assertEquals(
                "EXISTING_QFQ_ENGINE_REQUIRES_CORPORATE_ACTION_LINEAGE",
                capability.coverage()
                        .path("qfqOperationalBlockers").path(0)
                        .asText());
        assertFalse(capability.coverage()
                .path("corporateActionLineageComplete")
                .asBoolean(true));
        assertFalse(capability.coverage()
                .path("permanentSecurityIdentityVerified")
                .asBoolean(true));
        assertFalse(capability.coverage()
                .path("providerRevisionAvailable").asBoolean(true));
        assertFalse(capability.coverage()
                .path("historicalVersionsQueryable")
                .asBoolean(true));
        assertEquals("UNVERIFIED",
                capability.coverage()
                        .path("fullHistoryDailyExactQualification")
                        .asText());
        assertEquals("NOT_SUPPORTED",
                capability.coverage()
                        .path("providerPitQualification").asText());
        assertTrue(capability.coverage()
                .path("forwardSystemKnowledgePitBuildable")
                .asBoolean());
        assertEquals("PARTIAL_CONFLICT_IDENTIFIED",
                capability.coverage()
                        .path("endpointRateLimitQualification")
                        .asText());
        assertEquals("PARTIAL_CONFLICT_IDENTIFIED",
                capability.coverage()
                        .path("officialEndpointRateLimits")
                        .asText());
        assertTrue(capability.coverage()
                .path("endpointSpecificRateLimitEnforced")
                .asBoolean());
        assertTrue(capability.coverage()
                .path("conservativeEndpointMinimumPolicyEnforced")
                .asBoolean());
        assertTrue(capability.coverage()
                .path("isolatedSchemaGuardVerified").asBoolean());
        assertTrue(capability.coverage()
                .path("endpointRateLimitEvidenceIds").isArray());
        assertTrue(capability.coverage()
                .path("endpointRateLimitBlockers").isArray());
        assertEquals("ISSUER_IDENTITY_EVIDENCE",
                capability.coverage()
                        .path("stockCompanyIdentityUse").asText());
        assertEquals("SECURITY_NAME_HISTORY_EVIDENCE",
                capability.coverage()
                        .path("namechangeUse").asText());
        assertEquals(
                "HISTORICAL_SECURITY_LIST_PERMISSION_INSUFFICIENT",
                capability.coverage()
                        .path("historicalSecurityList").asText());
        assertEquals("PARTIAL",
                capability.coverage()
                        .path("corporateActionCoverage")
                        .path("CASH_DIVIDEND").asText());
        assertEquals("NOT_SUPPORTED",
                capability.coverage()
                        .path("corporateActionCoverage")
                        .path("WITHDRAWAL").asText());
        assertTrue(capability.coverage()
                .path("technicalBlockers").isArray());
        assertTrue(capability.coverage()
                .path("technicalEvidenceIds").isArray());
        assertEquals(
                provider.technicalQualification().routeDecision().name(),
                capability.coverage()
                        .path("technicalRouteDecision").asText());
        assertEquals(
                provider.technicalQualification()
                        .reducedResearchContractReady(),
                capability.coverage()
                        .path("reducedResearchContractReady")
                        .asBoolean());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenQuantDataSourceUsePermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenPersonalLocalStoragePermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenPersonalBacktestPermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenPersonalAgentAnalysisPermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenAutomatedApiUpdatePermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("writtenTechnicalAuditMetadataRetentionPermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("postExpiryDataRetentionPermission")
                        .asText());
        assertEquals("VERIFIED",
                capability.licensing()
                        .path("personal2000PointAccountScopePermission")
                        .asText());
        assertEquals("CONFIRMED",
                capability.licensing()
                        .path("userPersonalUseImplementationAuthorization")
                        .asText());
        assertEquals("APPROVED_BY_USER",
                capability.licensing()
                        .path("limitedPersonalUseImplementation")
                        .asText());
        assertFalse(capability.licensing()
                .path("formalEligible").asBoolean(true));
        assertFalse(capability.licensing()
                .path("fullF1EntryReady").asBoolean(true));
        assertEquals("USER_APPROVED_LIMITED_PERSONAL_USE",
                capability.licensing()
                        .path("authorizationBasis").asText());
        assertTrue(capability.licensing()
                .path("personalResearchPermissionComplete")
                .asBoolean());
        assertTrue(capability.licensing()
                .path("providerWrittenPermissionComplete")
                .asBoolean());
        assertEquals("PASS",
                capability.licensing()
                        .path("writtenPermissionGate").asText());
        assertEquals("BLOCKED",
                capability.licensing()
                        .path("technicalEvidenceGate").asText());
        assertEquals("BLOCKED_TECHNICAL_EVIDENCE",
                capability.licensing()
                        .path("f1EntryReadiness").asText());
        assertEquals(1, capability.licensing()
                .path("activeF1Blockers").size());
        assertEquals("BLOCKED_TECHNICAL_EVIDENCE",
                capability.licensing()
                        .path("activeF1Blockers").path(0).asText());
        assertEquals("NOT_GRANTED",
                capability.licensing()
                        .path("rawDataRedistributionPermission").asText());
        assertEquals("NOT_GRANTED",
                capability.licensing()
                        .path("commercialDataServicePermission").asText());
        assertEquals("NOT_GRANTED",
                capability.licensing()
                        .path("tokenSharingPermission").asText());
        JsonNode wp001 = capability.licensing()
                .path("writtenPermissionEvidenceProvenance")
                .path("TS-WP-001");
        assertEquals(
                List.of("QUANT_DATA_SOURCE_USE"),
                java.util.stream.StreamSupport.stream(
                                wp001.path("supportedPermissionSubjects")
                                        .spliterator(),
                                false)
                        .map(JsonNode::asText)
                        .toList());
        JsonNode wp002 = capability.licensing()
                .path("writtenPermissionEvidenceProvenance")
                .path("TS-WP-002");
        assertEquals("USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION",
                wp002.path("provenance").asText());
        assertEquals("2026-07-31T11:07:00+08:00",
                wp002.path("transcriptionReceivedAt").asText());
        assertEquals("UNKNOWN", wp002.path("officialReplyAt").asText());
        assertTrue(wp002.path("userAttestedOfficialSource").asBoolean());
        assertFalse(wp002.path("originalArtifactStored").asBoolean(true));
        assertFalse(wp002.path("screenshotReviewed").asBoolean(true));
        assertFalse(wp002.path("independentSourceAuthenticityReviewed")
                .asBoolean(true));
        assertEquals(
                List.of(
                        "PERSONAL_LOCAL_STORAGE",
                        "PERSONAL_BACKTEST",
                        "PERSONAL_AGENT_ANALYSIS",
                        "AUTOMATED_API_UPDATE",
                        "TECHNICAL_AUDIT_METADATA_RETENTION",
                        "POST_EXPIRY_DATA_RETENTION",
                        "PERSONAL_2000_POINT_ACCOUNT_SCOPE"),
                java.util.stream.StreamSupport.stream(
                                wp002.path("supportedPermissionSubjects")
                                        .spliterator(),
                                false)
                        .map(JsonNode::asText)
                        .toList());
        assertFalse(capability.toString().contains(TEST_TOKEN));
        assertEquals(200,
                capability.rateLimit()
                        .path("officialPerMinute").asInt());
        assertEquals(180,
                capability.rateLimit()
                        .path("applicationSafePerMinute").asInt());
        assertEquals(100_000,
                capability.rateLimit()
                        .path("officialDailyPerApi").asInt());
        assertEquals(90_000,
                capability.rateLimit()
                        .path("applicationDailySafePerApi").asInt());
        assertTrue(capability.rateLimit()
                .path("processWide").asBoolean());
        assertTrue(capability.rateLimit()
                .path("sharedAcrossEndpoints").asBoolean());
        assertTrue(capability.rateLimit()
                .path("sharedAcrossCallersInProcess").asBoolean());
        assertFalse(capability.rateLimit()
                .path("tokenLevelGlobalAcrossProcesses").asBoolean(true));
        assertFalse(capability.rateLimit()
                .path("distributedRateLimitCoordinated").asBoolean(true));
        assertTrue(capability.rateLimit()
                .path("dailyQuotaProcessWideOnly").asBoolean());
        assertFalse(capability.rateLimit()
                .path("distributedDailyQuotaCoordinated")
                .asBoolean(true));
        assertEquals("PARTIAL_CONFLICT_IDENTIFIED",
                capability.rateLimit()
                        .path("officialEndpointRateLimits").asText());
        assertTrue(capability.rateLimit()
                .path("endpointSpecificRateLimitEnforced")
                .asBoolean());
        assertTrue(capability.rateLimit()
                .path("conservativeEndpointMinimumPolicyEnforced")
                .asBoolean());
        assertEquals("MOST_CONSERVATIVE_MINIMUM",
                capability.rateLimit()
                        .path("applicableLimitSelection").asText());
        assertEquals(50,
                capability.rateLimit()
                        .path("officialPerMinuteByEndpoint")
                        .path("stock_basic").asInt());
        assertEquals(500,
                capability.rateLimit()
                        .path("officialPerMinuteByEndpoint")
                        .path("daily").asInt());
        assertEquals(45,
                capability.rateLimit()
                        .path("applicationSafePerMinuteByEndpoint")
                        .path("stock_basic").asInt());
        assertEquals(180,
                capability.rateLimit()
                        .path("applicationSafePerMinuteByEndpoint")
                        .path("daily").asInt());
        assertFalse(capability.rateLimit()
                .path("distributedCoordination").asBoolean(true));
        assertFalse(capability.rateLimit().has("tokenLevelGlobal"));
        assertFalse(capability.toString().contains(TEST_TOKEN));
    }

    @Test
    void ordinaryFetchIsUnavailableAndDisabledModeStopsControlledPath() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        TushareMarketFactProvider enabled = provider(gateway);
        assertThrows(IllegalStateException.class,
                () -> enabled.fetch(request(
                        "600000", "SSE",
                        Set.of(FactType.RAW_DAILY_BAR))));
        assertTrue(gateway.calls.isEmpty());

        TushareMarketFactProperties disabled =
                new TushareMarketFactProperties();
        disabled.setToken(TEST_TOKEN);
        TushareMarketFactProvider provider =
                new TushareMarketFactProvider(
                        mapper, disabled, gateway);
        assertThrows(IllegalStateException.class,
                () -> provider.fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session()));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void mapsRawFactorAndCalendarWithoutInventingPitMetadata() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request(
                                "600000",
                                "SSE",
                                Set.of(
                                        FactType.RAW_DAILY_BAR,
                                        FactType.ADJUSTMENT_FACTOR,
                                        FactType.TRADING_CALENDAR)),
                        session());

        assertEquals(
                LimitedPersonalFormalCaptureAuthorization.tushareF1A(),
                LimitedPersonalFormalCaptureAuthorization.fromResponse(
                        response));
        assertTrue(response.complete());
        assertEquals(List.of(
                        "daily", "adj_factor", "trade_cal"),
                gateway.calls.stream().map(Call::endpoint).toList());
        assertTrue(gateway.calls.stream().allMatch(call ->
                call.mode() == QueryMode.CONTROLLED_NO_RETRY));
        assertEquals(2, response.rawDailyBars().size());
        assertEquals(2, response.adjustmentFactors().size());
        assertEquals(2, response.tradingCalendar().size());
        assertTrue(response.corporateActions().isEmpty());
        assertEquals(3,
                response.providerMetadata()
                        .path("providerCallCount").asInt());
        assertEquals("MANUAL_BOUNDED",
                response.providerMetadata()
                        .path("tushareMode").asText());

        var firstBar = response.rawDailyBars().get(0);
        assertEquals(LocalDate.of(2025, 1, 6),
                firstBar.tradeDate());
        assertEquals(new BigDecimal("100050"),
                firstBar.volume().value());
        assertEquals(new BigDecimal("123456"),
                firstBar.amount().value());
        assertEquals(FieldQualification.MISSING,
                firstBar.turnoverRate().qualification());
        assertEquals(
                TushareMarketFactProvider.rawSourceIdentity(
                        "600000", "SSE"),
                firstBar.sourceIdentity());
        assertEquals(
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY,
                firstBar.version().revisionQualification());
        assertEquals(
                TushareMarketFactProvider.factorSourceIdentity(
                        "600000", "SSE"),
                response.adjustmentFactors().get(0).sourceIdentity());
        assertEquals(
                TushareMarketFactProvider.calendarSourceIdentity("SSE"),
                response.tradingCalendar().get(0).sourceIdentity());
        assertFalse(response.toString().contains(TEST_TOKEN));
    }

    @Test
    void mapsStockBasicAsPartialOrdinaryIdentity() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        var response = provider(gateway)
                .fetchInstrumentIdentityForControlledAcceptance(
                        "600000",
                        "SSE",
                        Duration.ofSeconds(5),
                        session());
        assertEquals("stock_basic", response.endpoint());
        assertEquals(1, response.values().size());
        var identity = response.values().get(0);
        assertEquals("600000.SH",
                identity.providerInstrumentId());
        assertEquals("600000", identity.symbol());
        assertEquals("SSE", identity.exchange());
        assertEquals("浦发银行", identity.name());
        assertEquals("主板", identity.market());
        assertEquals("L", identity.listStatus());
        assertEquals(LocalDate.of(1999, 11, 10),
                identity.listDate());
        assertNull(identity.delistDate());
        assertFalse(response.v13CorporateActionEligible());
    }

    @Test
    void mapsDividendOnlyAsPartialEvidenceWithoutStableActionClaims() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        var response = provider(gateway)
                .fetchDividendEvidenceForControlledAcceptance(
                        "600000",
                        "SSE",
                        Duration.ofSeconds(5),
                        session());
        assertEquals("dividend", response.endpoint());
        assertEquals(1, response.values().size());
        DividendEvidence value = response.values().get(0);
        assertEquals("600000.SH", value.tsCode());
        assertEquals(LocalDate.of(2024, 12, 31),
                value.endDate());
        assertEquals("实施", value.processStatus());
        assertEquals(new BigDecimal("0.1"),
                value.cashDividend());
        assertFalse(response.v13CorporateActionEligible());
        assertTrue(List.of(DividendEvidence.class
                        .getRecordComponents()).stream()
                .noneMatch(component -> Set.of(
                                "stableActionId", "rightsIssue", "split",
                                "reverseSplit", "corrected", "withdrawn",
                                "revision")
                        .contains(component.getName())));
        assertTrue(new MarketFactProviderModels.MarketFactResponse(
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.ADAPTER_VERSION,
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        "600000", "SSE"),
                LocalDate.of(2025, 1, 6),
                LocalDate.of(2025, 1, 7),
                true,
                provider(gateway).capability(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                mapper.createObjectNode())
                .corporateActions().isEmpty());
    }

    @Test
    void referenceRowLimitsRejectWithoutTruncatingOrCreatingDtos() {
        FixtureGateway stockGateway = new FixtureGateway(mapper);
        stockGateway.stockBasicRowCount =
                TushareMarketFactProvider.STOCK_BASIC_MAX_ROWS + 1;
        GatewayException stockError = assertThrows(
                GatewayException.class,
                () -> provider(stockGateway)
                        .fetchInstrumentIdentityForControlledAcceptance(
                                "600000",
                                "SSE",
                                Duration.ofSeconds(5),
                                session()));
        assertEquals(ErrorKind.STRUCTURE_CHANGED, stockError.kind());
        assertEquals("TUSHARE_REFERENCE_ROW_LIMIT_EXCEEDED",
                stockError.safeCode());

        FixtureGateway dividendGateway = new FixtureGateway(mapper);
        dividendGateway.dividendRowCount =
                TushareMarketFactProvider.DIVIDEND_EVIDENCE_MAX_ROWS + 1;
        GatewayException dividendError = assertThrows(
                GatewayException.class,
                () -> provider(dividendGateway)
                        .fetchDividendEvidenceForControlledAcceptance(
                                "600000",
                                "SSE",
                                Duration.ofSeconds(5),
                                session()));
        assertEquals(ErrorKind.STRUCTURE_CHANGED, dividendError.kind());
        assertEquals("TUSHARE_REFERENCE_ROW_LIMIT_EXCEEDED",
                dividendError.safeCode());
    }

    @Test
    void preservesMissingAndExplicitZeroProviderFields() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.dailyRows = List.of(List.of(
                text("600000.SH"),
                text("20250106"),
                decimal("10"),
                decimal("10"),
                decimal("10"),
                decimal("10"),
                mapper.nullNode(),
                decimal("0")));
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session());
        var bar = response.rawDailyBars().get(0);
        assertEquals(FieldQualification.MISSING,
                bar.volume().qualification());
        assertEquals(FieldQualification.PRESENT_VERIFIED,
                bar.amount().qualification());
        assertEquals(BigDecimal.ZERO, bar.amount().value());
    }

    @Test
    void unsupportedScopeStopsBeforeGateway() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        TushareMarketFactProvider provider = provider(gateway);
        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchForControlledAcceptance(
                        request("688001", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session()));
        assertThrows(IllegalArgumentException.class,
                () -> provider.fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.CORPORATE_ACTION)),
                        session()));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test
    void partialFailureIsTypedAndNeverMasqueradesAsComplete() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.failureEndpoint = "adj_factor";
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request(
                                "600000",
                                "SSE",
                                Set.of(
                                        FactType.RAW_DAILY_BAR,
                                        FactType.ADJUSTMENT_FACTOR,
                                        FactType.TRADING_CALENDAR)),
                        session());
        assertFalse(response.complete());
        assertEquals(2, response.rawDailyBars().size());
        assertTrue(response.adjustmentFactors().isEmpty());
        assertTrue(response.tradingCalendar().isEmpty());
        assertEquals(ProviderErrorType.PERMISSION_DENIED,
                response.errors().get(0).type());
        assertEquals("TUSHARE_PERMISSION_DENIED",
                response.errors().get(0).code());
    }

    @Test
    void malformedRowsFailAtomicallyAtProviderBoundary() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.dailyRows = List.of(List.of(
                text("600000.SH"),
                text("20250106"),
                text("not-a-decimal"),
                decimal("11"),
                decimal("9"),
                decimal("10"),
                decimal("100"),
                decimal("100")));
        var response = provider(gateway)
                .fetchForControlledAcceptance(
                        request("600000", "SSE",
                                Set.of(FactType.RAW_DAILY_BAR)),
                        session());
        assertFalse(response.complete());
        assertTrue(response.rawDailyBars().isEmpty());
        assertEquals(ProviderErrorType.STRUCTURE_CHANGED,
                response.errors().get(0).type());
    }

    @Test
    void mainboardSnapshotUsesProviderFieldsInsteadOfCodePrefixes() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.mainboardRows = completeMainboardRows();
        var session = TushareManualBoundedSession.mainboardUniverse(Set.of(
                LocalDate.of(2026, 8, 12)), LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 12), true, false);

        var response = provider(gateway).fetchMainboardUniverseSnapshot(
                Duration.ofSeconds(5), session);

        assertEquals(1_000, response.values().size());
        assertTrue(response.values().stream().map(
                MainboardInstrument::tsCode).toList().containsAll(
                List.of("300001.SZ", "600001.SH")));
        assertTrue(response.complete());
        assertEquals("主板", gateway.calls.get(0).parameters()
                .path("market").asText());
        assertEquals("L", gateway.calls.get(0).parameters()
                .path("list_status").asText());
    }

    @Test
    void mainboardSnapshotRejectsProviderClassificationMismatch() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.mainboardRows = List.of(mainboardRow("600001.SH", "600001",
                "错误分类", "制造", "创业板", "SSE"));
        var session = TushareManualBoundedSession.mainboardUniverse(Set.of(
                LocalDate.of(2026, 8, 12)), LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 12), true, false);

        GatewayException failure = assertThrows(GatewayException.class, () ->
                provider(gateway).fetchMainboardUniverseSnapshot(
                        Duration.ofSeconds(5), session));

        assertEquals("MAINBOARD_STOCK_BASIC_ROW_INVALID",
                failure.safeCode());
    }

    @Test
    void mainboardSnapshotFailsClosedWhenMembershipCannotBeComplete() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        gateway.mainboardRows = completeMainboardRows().subList(0, 999);
        var session = TushareManualBoundedSession.mainboardUniverse(Set.of(
                LocalDate.of(2026, 8, 12)), LocalDate.of(2026, 8, 12),
                LocalDate.of(2026, 8, 12), true, false);

        GatewayException failure = assertThrows(GatewayException.class, () ->
                provider(gateway).fetchMainboardUniverseSnapshot(
                        Duration.ofSeconds(5), session));

        assertEquals("MAINBOARD_STOCK_BASIC_COVERAGE_INCOMPLETE",
                failure.safeCode());
    }

    @Test
    void mainboardMarketDateUsesTwoCallsAndFiltersToSnapshot() {
        FixtureGateway gateway = new FixtureGateway(mapper);
        LocalDate date = LocalDate.of(2026, 8, 12);
        gateway.mainboardDailyRows = List.of(
                dailyRow("600001.SH", date), dailyRow("000001.SZ", date),
                dailyRow("300001.SZ", date));
        gateway.mainboardFactorRows = List.of(
                factorRow("600001.SH", date), factorRow("000001.SZ", date),
                factorRow("300001.SZ", date));
        List<MainboardInstrument> members = List.of(
                mainboard("600001.SH", "600001", "SSE"),
                mainboard("000001.SZ", "000001", "SZSE"));
        var session = TushareManualBoundedSession.mainboardUniverse(
                Set.of(date), date, date, false, false);

        var response = provider(gateway).fetchMainboardMarketDate(members,
                date, Duration.ofSeconds(5), session);

        assertTrue(response.complete());
        assertEquals(2, response.providerMetadata()
                .path("providerCallCount").asInt());
        assertEquals(2, response.rawDailyBars().size());
        assertEquals(2, response.adjustmentFactors().size());
        assertEquals(List.of("daily", "adj_factor"), gateway.calls.stream()
                .map(Call::endpoint).toList());
        assertTrue(gateway.calls.stream().allMatch(call ->
                call.parameters().size() == 1
                        && call.parameters().path("trade_date").asText()
                        .equals("20260812")));
    }

    @Test
    void mainboardMarketDateFailsClosedOnTruncationOrMismatchedFacts() {
        FixtureGateway truncated = new FixtureGateway(mapper);
        LocalDate date = LocalDate.of(2026, 8, 12);
        truncated.mainboardDailyRows = java.util.Collections.nCopies(
                TushareMarketFactProvider.MAINBOARD_MARKET_MAX_ROWS,
                dailyRow("600001.SH", date));
        truncated.mainboardFactorRows = List.of(factorRow("600001.SH", date));
        List<MainboardInstrument> members = List.of(
                mainboard("600001.SH", "600001", "SSE"));
        var truncatedSession = TushareManualBoundedSession.mainboardUniverse(
                Set.of(date), date, date, false, false);
        GatewayException rowLimit = assertThrows(GatewayException.class,
                () -> provider(truncated).fetchMainboardMarketDate(members,
                        date, Duration.ofSeconds(5), truncatedSession));
        assertEquals("MAINBOARD_PROVIDER_RESPONSE_TRUNCATED",
                rowLimit.safeCode());

        FixtureGateway mismatch = new FixtureGateway(mapper);
        mismatch.mainboardDailyRows = List.of(dailyRow("600001.SH", date));
        mismatch.mainboardFactorRows = List.of(factorRow("000001.SZ", date));
        var mismatchSession = TushareManualBoundedSession.mainboardUniverse(
                Set.of(date), date, date, false, false);
        GatewayException coverage = assertThrows(GatewayException.class,
                () -> provider(mismatch).fetchMainboardMarketDate(members,
                        date, Duration.ofSeconds(5), mismatchSession));
        assertEquals("MAINBOARD_MARKET_DATE_COVERAGE_INCOMPLETE",
                coverage.safeCode());

        FixtureGateway preListing = new FixtureGateway(mapper);
        preListing.mainboardDailyRows = List.of(
                dailyRow("600001.SH", date));
        preListing.mainboardFactorRows = List.of(
                factorRow("600001.SH", date));
        List<MainboardInstrument> futureMember = List.of(
                new MainboardInstrument("600001.SH", "600001", "SSE",
                        "未来上市样本", "制造", "主板", "L",
                        date.plusDays(1), null, "b".repeat(64)));
        var preListingSession = TushareManualBoundedSession
                .mainboardUniverse(Set.of(date), date, date, false, false);
        GatewayException identityDate = assertThrows(GatewayException.class,
                () -> provider(preListing).fetchMainboardMarketDate(
                        futureMember, date, Duration.ofSeconds(5),
                        preListingSession));
        assertEquals("MAINBOARD_DAILY_RESPONSE_INVALID",
                identityDate.safeCode());
    }

    private static MainboardInstrument mainboard(
            String tsCode, String symbol, String exchange
    ) {
        return new MainboardInstrument(tsCode, symbol, exchange, symbol,
                "制造", "主板", "L", LocalDate.of(2000, 1, 1), null,
                "a".repeat(64));
    }

    private static List<JsonNode> mainboardRow(
            String tsCode, String symbol, String name, String industry,
            String market, String exchange
    ) {
        return List.of(FixtureGateway.textNode(tsCode),
                FixtureGateway.textNode(symbol),
                FixtureGateway.textNode(name),
                FixtureGateway.textNode(industry),
                FixtureGateway.textNode(market),
                FixtureGateway.textNode(exchange),
                FixtureGateway.textNode("L"),
                FixtureGateway.textNode("20000101"),
                com.fasterxml.jackson.databind.node.NullNode.getInstance());
    }

    private static List<List<JsonNode>> completeMainboardRows() {
        List<List<JsonNode>> values = new ArrayList<>();
        values.add(mainboardRow("300001.SZ", "300001", "主板前缀反例",
                "制造", "主板", "SZSE"));
        values.add(mainboardRow("600001.SH", "600001", "沪市样本",
                "银行", "主板", "SSE"));
        for (int index = 0; index < 998; index++) {
            boolean sse = index % 2 == 0;
            int number = (sse ? 601_000 : 1_000) + index / 2;
            String symbol = String.format("%06d", number);
            String tsCode = symbol + (sse ? ".SH" : ".SZ");
            values.add(mainboardRow(tsCode, symbol, "主板样本" + index,
                    sse ? "工业" : "消费", "主板",
                    sse ? "SSE" : "SZSE"));
        }
        return List.copyOf(values);
    }

    private static List<JsonNode> dailyRow(String tsCode, LocalDate date) {
        return List.of(FixtureGateway.textNode(tsCode),
                FixtureGateway.textNode(date.format(
                        java.time.format.DateTimeFormatter.BASIC_ISO_DATE)),
                FixtureGateway.decimalNode("10.0"),
                FixtureGateway.decimalNode("10.5"),
                FixtureGateway.decimalNode("9.8"),
                FixtureGateway.decimalNode("10.2"),
                FixtureGateway.decimalNode("100000"),
                FixtureGateway.decimalNode("200000"));
    }

    private static List<JsonNode> factorRow(String tsCode, LocalDate date) {
        return List.of(FixtureGateway.textNode(tsCode),
                FixtureGateway.textNode(date.format(
                        java.time.format.DateTimeFormatter.BASIC_ISO_DATE)),
                FixtureGateway.decimalNode("1.0"));
    }

    private TushareMarketFactProvider provider(
            TushareApiGateway gateway
    ) {
        return new TushareMarketFactProvider(
                mapper, properties(), gateway);
    }

    private TushareMarketFactProperties properties() {
        TushareMarketFactProperties properties =
                new TushareMarketFactProperties();
        properties.setMode(
                TushareMarketFactProperties.Mode.MANUAL_BOUNDED);
        properties.setToken(TEST_TOKEN);
        return properties;
    }

    private static TushareManualBoundedSession session() {
        return TushareManualBoundedSession.f1aAcceptance(0);
    }

    private MarketFactRequest request(
            String symbol,
            String exchange,
            Set<FactType> factTypes
    ) {
        return new MarketFactRequest(
                RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        symbol, exchange),
                symbol,
                exchange,
                LocalDate.of(2025, 1, 6),
                LocalDate.of(2025, 1, 7),
                factTypes,
                Duration.ofSeconds(5));
    }

    private JsonNode text(String value) {
        return mapper.getNodeFactory().textNode(value);
    }

    private JsonNode decimal(String value) {
        return mapper.getNodeFactory().numberNode(
                new BigDecimal(value));
    }

    private static final class FixtureGateway
            implements TushareApiGateway {
        private final ObjectMapper mapper;
        private final List<Call> calls = new ArrayList<>();
        private List<List<JsonNode>> dailyRows;
        private String failureEndpoint;
        private int stockBasicRowCount = 1;
        private int dividendRowCount = 1;
        private List<List<JsonNode>> mainboardRows;
        private List<List<JsonNode>> mainboardDailyRows;
        private List<List<JsonNode>> mainboardFactorRows;

        private FixtureGateway(ObjectMapper mapper) {
            this.mapper = mapper;
            this.dailyRows = List.of(
                    List.of(
                            textNode("600000.SH"),
                            textNode("20250107"),
                            decimalNode("10.1"),
                            decimalNode("10.3"),
                            decimalNode("10.0"),
                            decimalNode("10.2"),
                            decimalNode("1100.5"),
                            decimalNode("130.5")),
                    List.of(
                            textNode("600000.SH"),
                            textNode("20250106"),
                            decimalNode("10.0"),
                            decimalNode("10.2"),
                            decimalNode("9.9"),
                            decimalNode("10.1"),
                            decimalNode("1000.5"),
                            decimalNode("123.456")));
        }

        @Override
        public QueryResult query(
                String endpoint,
                ObjectNode parameters,
                List<String> fields,
                Duration timeout,
                QueryMode mode,
                TushareManualBoundedSession session
        ) {
            calls.add(new Call(endpoint, mode, parameters.deepCopy()));
            if (endpoint.equals(failureEndpoint)) {
                throw new GatewayException(
                        ErrorKind.PERMISSION_DENIED,
                        "TUSHARE_PERMISSION_DENIED",
                        "permission denied",
                        1,
                        0,
                        null);
            }
            return new QueryResult(
                    switch (endpoint) {
                        case "daily" -> new Table(fields,
                                mainboardDailyRows == null ? dailyRows
                                        : mainboardDailyRows);
                        case "adj_factor" -> new Table(
                                fields,
                                mainboardFactorRows == null ? List.of(
                                        List.of(
                                                textNode("600000.SH"),
                                                textNode("20250107"),
                                                decimalNode("1.2")),
                                        List.of(
                                                textNode("600000.SH"),
                                                textNode("20250106"),
                                                decimalNode("1.2")))
                                        : mainboardFactorRows);
                        case "trade_cal" -> new Table(
                                fields,
                                List.of(
                                        List.of(
                                                textNode("SSE"),
                                                textNode("20250107"),
                                                integerNode(1),
                                                textNode("20250106")),
                                        List.of(
                                                textNode("SSE"),
                                                textNode("20250106"),
                                                integerNode(1),
                                                textNode("20250103"))));
                        case "stock_basic" -> new Table(fields,
                                mainboardRows != null ? mainboardRows
                                : repeated(
                                        stockBasicRowCount,
                                        List.of(
                                        textNode("600000.SH"),
                                        textNode("600000"),
                                        textNode("浦发银行"),
                                        textNode("主板"),
                                        textNode("SSE"),
                                        textNode("L"),
                                        textNode("19991110"),
                                        mapper.nullNode())));
                        case "dividend" -> new Table(
                                fields,
                                repeated(
                                        dividendRowCount,
                                        List.of(
                                        textNode("600000.SH"),
                                        textNode("20241231"),
                                        textNode("20250301"),
                                        textNode("实施"),
                                        decimalNode("0"),
                                        decimalNode("0"),
                                        decimalNode("0"),
                                        decimalNode("0.1"),
                                        decimalNode("0.09"),
                                        textNode("20250601"),
                                        textNode("20250602"),
                                        textNode("20250603"),
                                        mapper.nullNode(),
                                        textNode("20250520"))));
                        default -> throw new IllegalArgumentException(
                                endpoint);
                    },
                    1,
                    0);
        }

        private static List<List<JsonNode>> repeated(
                int count,
                List<JsonNode> row
        ) {
            return java.util.Collections.nCopies(count, row);
        }

        private static JsonNode textNode(String value) {
            return com.fasterxml.jackson.databind.node.TextNode
                    .valueOf(value);
        }

        private static JsonNode decimalNode(String value) {
            return com.fasterxml.jackson.databind.node.DecimalNode
                    .valueOf(new BigDecimal(value));
        }

        private static JsonNode integerNode(int value) {
            return com.fasterxml.jackson.databind.node.IntNode
                    .valueOf(value);
        }
    }

    private record Call(
            String endpoint,
            QueryMode mode,
            ObjectNode parameters
    ) {
    }
}
