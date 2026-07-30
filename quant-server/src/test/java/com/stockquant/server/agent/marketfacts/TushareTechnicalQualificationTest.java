package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareReducedResearchQfqContract.ResearchRawPrice;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.DividendEvidence;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.AssessmentInput;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.CorporateActionType;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.EndpointRateLimitBlocker;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.EndpointRateLimitQualification;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QfqAnchorSemantics;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QfqCalculationMode;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QfqOperationalBlocker;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationStatus;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.TechnicalBlocker;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.TechnicalClaim;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareTechnicalQualificationTest {

    private static final LocalDate FIRST = LocalDate.of(2025, 1, 2);
    private static final LocalDate SECOND = LocalDate.of(2025, 1, 3);
    private static final LocalDate LATER_ANCHOR =
            LocalDate.of(2025, 1, 6);

    @Test
    void current2000PointAssessmentDefinesContractButNotRuntime() {
        var actual =
                TushareTechnicalQualification.current2000PointAssessment();

        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.fullTechnicalContractReady());
        assertTrue(actual.reducedResearchContractReady());
        assertFalse(actual.reducedResearchRuntimeReady());
        assertEquals(
                QualificationStatus.VERIFIED,
                actual.qfqFormulaQualification());
        assertEquals(
                QualificationStatus.PARTIAL,
                actual.qfqOperationalRuntimeQualification());
        assertEquals(
                Set.of(QfqOperationalBlocker
                        .EXISTING_QFQ_ENGINE_REQUIRES_CORPORATE_ACTION_LINEAGE),
                actual.qfqOperationalBlockers());
        assertEquals(
                EndpointRateLimitQualification
                        .PARTIAL_CONFLICT_IDENTIFIED,
                actual.endpointRateLimitQualification());
        assertFalse(actual.endpointSpecificRateLimitEnforced());
        assertTrue(actual.endpointRateLimitEvidenceIds().containsAll(
                Set.of("TS-003", "TS-004", "TS-009")));
        assertTrue(actual.endpointRateLimitBlockers().containsAll(Set.of(
                EndpointRateLimitBlocker
                        .GENERAL_AND_ENDPOINT_LIMITS_REQUIRE_CONSERVATIVE_MINIMUM,
                EndpointRateLimitBlocker
                        .ENDPOINT_SPECIFIC_LIMITER_NOT_IMPLEMENTED)));
        assertTrue(actual.forwardSystemKnowledgePitBuildable());
        assertEquals(
                QualificationStatus.PARTIAL,
                actual.corporateActionQualification());
        assertEquals(
                QualificationStatus.NOT_SUPPORTED,
                actual.revisionQualification());
        assertEquals(
                QualificationStatus.NOT_SUPPORTED,
                actual.historicalVersionQualification());
        assertEquals(
                QualificationStatus.PARTIAL,
                actual.securityIdentityQualification());
        assertFalse(actual.corporateActionLineageComplete());
        assertFalse(actual.providerRevisionAvailable());
        assertFalse(actual.historicalVersionsQueryable());
        assertTrue(actual.blockers().containsAll(Set.of(
                TechnicalBlocker.CORPORATE_ACTION_LINEAGE_INCOMPLETE,
                TechnicalBlocker.STABLE_ACTION_ID_UNAVAILABLE,
                TechnicalBlocker.FACTOR_ACTION_RELATION_UNVERIFIED,
                TechnicalBlocker.PROVIDER_REVISION_UNAVAILABLE,
                TechnicalBlocker.HISTORICAL_VERSIONS_NOT_QUERYABLE,
                TechnicalBlocker.PERMANENT_SECURITY_IDENTITY_UNVERIFIED,
                TechnicalBlocker.FULL_HISTORY_DAILY_EXACT_UNVERIFIED,
                TechnicalBlocker.PROVIDER_PIT_UNAVAILABLE,
                TechnicalBlocker.QFQ_OPERATIONAL_RUNTIME_INCOMPLETE,
                TechnicalBlocker.ENDPOINT_RATE_LIMIT_EVIDENCE_CONFLICT,
                TechnicalBlocker
                        .ENDPOINT_SPECIFIC_RATE_LIMIT_NOT_ENFORCED)));
    }

    @Test
    void onlyIndependentEvidenceForEveryFullConditionCanSelectFull() {
        var actual = TushareTechnicalQualification.assess(fullInput());

        assertEquals(
                RouteDecision.FULL_F1_BUILDABLE,
                actual.routeDecision());
        assertTrue(actual.fullTechnicalContractReady());
        assertFalse(actual.reducedResearchContractReady());
        assertFalse(actual.reducedResearchRuntimeReady());
        assertTrue(actual.corporateActionLineageComplete());
        assertTrue(actual.permanentSecurityIdentityVerified());
        assertTrue(actual.providerRevisionAvailable());
        assertTrue(actual.historicalVersionsQueryable());
        assertTrue(actual.blockers().isEmpty());
    }

    @Test
    void cashDividendEvidenceCannotPromoteOtherActions() {
        AssessmentInput full = fullInput();
        Map<CorporateActionType, TechnicalClaim> actions =
                new EnumMap<>(CorporateActionType.class);
        EnumSet.allOf(CorporateActionType.class).forEach(type ->
                actions.put(type, claim(
                        type == CorporateActionType.CASH_DIVIDEND
                                ? QualificationStatus.VERIFIED
                                : QualificationStatus.UNVERIFIED,
                        type == CorporateActionType.CASH_DIVIDEND
                                ? Set.of("ACTION-CASH")
                                : Set.of())));

        var actual = TushareTechnicalQualification.assess(
                withActions(full, actions));

        assertEquals(
                QualificationStatus.VERIFIED,
                actual.corporateActionCoverage()
                        .get(CorporateActionType.CASH_DIVIDEND));
        assertEquals(
                QualificationStatus.UNVERIFIED,
                actual.corporateActionCoverage()
                        .get(CorporateActionType.SPLIT));
        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.corporateActionLineageComplete());
    }

    @Test
    void verifiedActionWithoutEvidenceIsRejectedBeforeAssessment() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> claim(QualificationStatus.VERIFIED, Set.of()));
        assertEquals(
                "VERIFIED claim requires evidence",
                error.getMessage());
    }

    @Test
    void stableActionIdAndFactorRelationshipCannotBypassEvidence() {
        IllegalArgumentException stableActionId = assertThrows(
                IllegalArgumentException.class,
                () -> claim(QualificationStatus.VERIFIED, Set.of()));
        IllegalArgumentException factorRelationship = assertThrows(
                IllegalArgumentException.class,
                () -> new TechnicalClaim(
                        QualificationStatus.VERIFIED, Set.of()));
        assertEquals(
                "VERIFIED claim requires evidence",
                stableActionId.getMessage());
        assertEquals(
                "VERIFIED claim requires evidence",
                factorRelationship.getMessage());
        Set<String> recordComponents = Arrays.stream(
                        TushareTechnicalQualification.class
                                .getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertFalse(recordComponents.contains("stableActionIdAvailable"));
        assertFalse(recordComponents.contains(
                "factorActionRelationshipVerified"));
        assertFalse(recordComponents.contains(
                "forwardSystemKnowledgePitBuildable"));
        assertFalse(recordComponents.contains(
                "safetyBoundaryImplementable"));
    }

    @Test
    void oneGenericCorporateActionEvidenceCannotCoverAllActions() {
        AssessmentInput full = fullInput();
        Map<CorporateActionType, TechnicalClaim> actions =
                new EnumMap<>(CorporateActionType.class);
        EnumSet.allOf(CorporateActionType.class).forEach(type ->
                actions.put(type, verified("GENERIC-CORPORATE-ACTION")));

        var actual = TushareTechnicalQualification.assess(
                withActions(full, actions));

        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.corporateActionLineageComplete());
    }

    @Test
    void unavailableCoreFactRejectsProviderRoute() {
        AssessmentInput full = fullInput();
        for (int index = 0; index < 3; index++) {
            TechnicalClaim raw = index == 0
                    ? unavailable("RAW-UNAVAILABLE")
                    : full.rawDailyClaim();
            TechnicalClaim factor = index == 1
                    ? unavailable("FACTOR-UNAVAILABLE")
                    : full.adjustmentFactorClaim();
            TechnicalClaim calendar = index == 2
                    ? unavailable("CALENDAR-UNAVAILABLE")
                    : full.calendarClaim();

            var actual = TushareTechnicalQualification.assess(
                    withCore(full, raw, factor, calendar));

            assertEquals(
                    RouteDecision.PROVIDER_ROUTE_REJECTED,
                    actual.routeDecision());
            assertFalse(actual.reducedResearchContractReady());
            assertFalse(actual.reducedResearchRuntimeReady());
        }
    }

    @Test
    void directConstructorRejectsRouteAndReadinessContradictions() {
        TushareTechnicalQualification full =
                TushareTechnicalQualification.assess(fullInput());
        assertThrows(
                IllegalArgumentException.class,
                () -> copyQualification(
                        full,
                        RouteDecision.REDUCED_RESEARCH_ONLY,
                        full.rawDailyClaim(),
                        full.blockers(),
                        false,
                        true,
                        true));

        AssessmentInput rejectedInput = withCore(
                fullInput(),
                unavailable("RAW-UNAVAILABLE"),
                fullInput().adjustmentFactorClaim(),
                fullInput().calendarClaim());
        TushareTechnicalQualification rejected =
                TushareTechnicalQualification.assess(rejectedInput);
        assertThrows(
                IllegalArgumentException.class,
                () -> copyQualification(
                        rejected,
                        RouteDecision.PROVIDER_ROUTE_REJECTED,
                        rejected.rawDailyClaim(),
                        rejected.blockers(),
                        false,
                        false,
                        true));
    }

    @Test
    void directConstructorRejectsFullWithBlockers() {
        TushareTechnicalQualification full =
                TushareTechnicalQualification.assess(fullInput());
        assertThrows(
                IllegalArgumentException.class,
                () -> copyQualification(
                        full,
                        RouteDecision.FULL_F1_BUILDABLE,
                        full.rawDailyClaim(),
                        Set.of(TechnicalBlocker.PROVIDER_PIT_UNAVAILABLE),
                        true,
                        false,
                        false));
    }

    @Test
    void actionLineageAndRevisionFlagsAreDerivedFromClaims() {
        var current =
                TushareTechnicalQualification.current2000PointAssessment();

        assertFalse(current.corporateActionLineageComplete());
        assertEquals(
                QualificationStatus.NOT_SUPPORTED,
                current.providerRevisionClaim().status());
        assertFalse(current.providerRevisionAvailable());
        Set<String> componentNames = Arrays.stream(
                        TushareTechnicalQualification.class
                                .getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());
        assertFalse(componentNames.contains(
                "corporateActionLineageComplete"));
        assertFalse(componentNames.contains("providerRevisionAvailable"));
    }

    @Test
    void sharedQfqMathAppliesOfficialEndDateAnchorDeterministically() {
        List<ResearchRawPrice> raw = List.of(
                new ResearchRawPrice(FIRST, new BigDecimal("10")),
                new ResearchRawPrice(SECOND, new BigDecimal("12")));
        Map<LocalDate, BigDecimal> factors = Map.of(
                FIRST, BigDecimal.ONE,
                SECOND, new BigDecimal("2"),
                LATER_ANCHOR, new BigDecimal("4"));

        var endDateAnchored =
                TushareReducedResearchQfqContract.validateAndCalculate(
                        "TUSHARE_PRO", "TUSHARE_PRO",
                        raw, factors, SECOND);
        var repeated =
                TushareReducedResearchQfqContract.validateAndCalculate(
                        "TUSHARE_PRO", "TUSHARE_PRO",
                        raw, factors, SECOND);
        var laterAnchored =
                TushareReducedResearchQfqContract.validateAndCalculate(
                        "TUSHARE_PRO", "TUSHARE_PRO",
                        raw, factors, LATER_ANCHOR);

        assertEquals(endDateAnchored, repeated);
        assertEquals(
                List.of(
                        new BigDecimal("5.0000"),
                        new BigDecimal("12.0000")),
                endDateAnchored.stream()
                        .map(value -> value.qfqPrice())
                        .toList());
        assertEquals(
                List.of(
                        new BigDecimal("2.5000"),
                        new BigDecimal("6.0000")),
                laterAnchored.stream()
                        .map(value -> value.qfqPrice())
                        .toList());
        assertEquals(
                endDateAnchored.get(0).qfqPrice(),
                QfqPriceMath.calculate(
                        new BigDecimal("10"),
                        BigDecimal.ONE,
                        new BigDecimal("2")));
    }

    @Test
    void reducedQfqContractBlocksMissingAndInvalidFactors() {
        List<ResearchRawPrice> raw = List.of(
                new ResearchRawPrice(FIRST, new BigDecimal("10")),
                new ResearchRawPrice(SECOND, new BigDecimal("12")));

        assertEquals(
                "TUSHARE_QFQ_ANCHOR_FACTOR_UNAVAILABLE",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "TUSHARE_PRO",
                                        raw,
                                        Map.of(
                                                FIRST, BigDecimal.ONE,
                                                SECOND,
                                                new BigDecimal("2")),
                                        LATER_ANCHOR)));
        assertEquals(
                "TUSHARE_QFQ_DAILY_FACTOR_UNAVAILABLE",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "TUSHARE_PRO",
                                        raw,
                                        Map.of(
                                                SECOND,
                                                new BigDecimal("2")),
                                        SECOND)));
        assertEquals(
                "TUSHARE_QFQ_FACTOR_INVALID",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "TUSHARE_PRO",
                                        List.of(new ResearchRawPrice(
                                                FIRST,
                                                new BigDecimal("10"))),
                                        Map.of(FIRST, BigDecimal.ZERO),
                                        FIRST)));
    }

    @Test
    void reducedQfqContractRejectsRangeDuplicatesAndCrossProvider() {
        assertEquals(
                "TUSHARE_QFQ_RAW_SERIES_EMPTY",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "TUSHARE_PRO",
                                        List.of(), Map.of(), FIRST)));
        assertEquals(
                "TUSHARE_QFQ_TRADE_DATE_AFTER_ANCHOR",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "TUSHARE_PRO",
                                        List.of(new ResearchRawPrice(
                                                SECOND,
                                                new BigDecimal("10"))),
                                        Map.of(
                                                FIRST, BigDecimal.ONE,
                                                SECOND, BigDecimal.ONE),
                                        FIRST)));
        assertEquals(
                "TUSHARE_QFQ_DUPLICATE_TRADE_DATE",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "TUSHARE_PRO",
                                        List.of(
                                                new ResearchRawPrice(
                                                        FIRST,
                                                        BigDecimal.ONE),
                                                new ResearchRawPrice(
                                                        FIRST,
                                                        BigDecimal.TEN)),
                                        Map.of(FIRST, BigDecimal.ONE),
                                        FIRST)));
        assertEquals(
                "TUSHARE_QFQ_CROSS_PROVIDER_FORBIDDEN",
                failure(() ->
                        TushareReducedResearchQfqContract
                                .validateAndCalculate(
                                        "TUSHARE_PRO", "OTHER_PROVIDER",
                                        List.of(new ResearchRawPrice(
                                                FIRST, BigDecimal.ONE)),
                                        Map.of(FIRST, BigDecimal.ONE),
                                        FIRST)));
    }

    @Test
    void qfqContractCannotConsumeDividendEvidence() {
        boolean consumesDividend = Arrays.stream(
                        TushareReducedResearchQfqContract.class
                                .getDeclaredMethods())
                .filter(method -> method.getName()
                        .equals("validateAndCalculate"))
                .map(Method::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(type -> type == DividendEvidence.class);

        assertFalse(consumesDividend);
        assertTrue(Arrays.stream(
                        TushareTechnicalQualification.class
                                .getDeclaredMethods())
                .noneMatch(method -> method.getName()
                        .equals("calculateReducedResearchQfq")));
        assertTrue(Arrays.stream(QfqAsOfEngine.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().equals("price")));
    }

    private static String failure(Runnable runnable) {
        return assertThrows(
                IllegalArgumentException.class, runnable::run).getMessage();
    }

    private static AssessmentInput fullInput() {
        return new AssessmentInput(
                verified("RAW"),
                verified("FACTOR"),
                verified("CALENDAR"),
                independentFullActionClaims(),
                verified("STABLE-ACTION-ID"),
                verified("FACTOR-ACTION-RELATION"),
                verified("REVISION"),
                verified("HISTORICAL-VERSIONS"),
                verified("PERMANENT-IDENTITY"),
                verified("QFQ-FORMULA"),
                verified("QFQ-RUNTIME"),
                verified("FULL-HISTORY-DAILY-EXACT"),
                verified("PROVIDER-PIT"),
                verified("FORWARD-PIT"),
                verified("SAFETY"),
                verified("ENDPOINT-RATE-EVIDENCE"),
                verified("ENDPOINT-RATE-ENFORCEMENT"),
                EndpointRateLimitQualification.VERIFIED_CONSISTENT,
                QfqCalculationMode.RAW_FACTOR_END_DATE_ANCHORED,
                QfqAnchorSemantics.REQUESTED_END_DATE_FACTOR);
    }

    private static Map<CorporateActionType, TechnicalClaim>
    independentFullActionClaims() {
        Map<CorporateActionType, TechnicalClaim> result =
                new EnumMap<>(CorporateActionType.class);
        EnumSet.allOf(CorporateActionType.class).forEach(type ->
                result.put(type, verified("ACTION-" + type.name())));
        return result;
    }

    private static AssessmentInput withActions(
            AssessmentInput source,
            Map<CorporateActionType, TechnicalClaim> actions
    ) {
        return new AssessmentInput(
                source.rawDailyClaim(),
                source.adjustmentFactorClaim(),
                source.calendarClaim(),
                actions,
                source.stableActionIdClaim(),
                source.factorActionRelationshipClaim(),
                source.providerRevisionClaim(),
                source.historicalVersionsClaim(),
                source.permanentSecurityIdentityClaim(),
                source.qfqFormulaClaim(),
                source.qfqOperationalRuntimeClaim(),
                source.fullHistoryDailyExactClaim(),
                source.providerPitClaim(),
                source.forwardSystemKnowledgePitClaim(),
                source.safetyBoundaryClaim(),
                source.endpointRateLimitClaim(),
                source.endpointRateLimitEnforcementClaim(),
                source.endpointRateLimitQualification(),
                source.qfqCalculationMode(),
                source.qfqAnchorSemantics());
    }

    private static AssessmentInput withCore(
            AssessmentInput source,
            TechnicalClaim raw,
            TechnicalClaim factor,
            TechnicalClaim calendar
    ) {
        return new AssessmentInput(
                raw,
                factor,
                calendar,
                source.corporateActionClaims(),
                source.stableActionIdClaim(),
                source.factorActionRelationshipClaim(),
                source.providerRevisionClaim(),
                source.historicalVersionsClaim(),
                source.permanentSecurityIdentityClaim(),
                source.qfqFormulaClaim(),
                source.qfqOperationalRuntimeClaim(),
                source.fullHistoryDailyExactClaim(),
                source.providerPitClaim(),
                source.forwardSystemKnowledgePitClaim(),
                source.safetyBoundaryClaim(),
                source.endpointRateLimitClaim(),
                source.endpointRateLimitEnforcementClaim(),
                source.endpointRateLimitQualification(),
                source.qfqCalculationMode(),
                source.qfqAnchorSemantics());
    }

    private static TushareTechnicalQualification copyQualification(
            TushareTechnicalQualification source,
            RouteDecision routeDecision,
            TechnicalClaim rawClaim,
            Set<TechnicalBlocker> blockers,
            boolean fullReady,
            boolean reducedReady,
            boolean runtimeReady
    ) {
        return new TushareTechnicalQualification(
                routeDecision,
                rawClaim,
                source.adjustmentFactorClaim(),
                source.calendarClaim(),
                source.corporateActionClaims(),
                source.stableActionIdClaim(),
                source.factorActionRelationshipClaim(),
                source.providerRevisionClaim(),
                source.historicalVersionsClaim(),
                source.permanentSecurityIdentityClaim(),
                source.qfqFormulaClaim(),
                source.qfqOperationalRuntimeClaim(),
                source.fullHistoryDailyExactClaim(),
                source.providerPitClaim(),
                source.forwardSystemKnowledgePitClaim(),
                source.safetyBoundaryClaim(),
                source.endpointRateLimitClaim(),
                source.endpointRateLimitEnforcementClaim(),
                source.endpointRateLimitQualification(),
                blockers,
                source.qfqOperationalBlockers(),
                source.endpointRateLimitBlockers(),
                fullReady,
                reducedReady,
                runtimeReady,
                source.qfqCalculationMode(),
                source.qfqAnchorSemantics());
    }

    private static TechnicalClaim verified(String evidenceId) {
        return claim(QualificationStatus.VERIFIED, Set.of(evidenceId));
    }

    private static TechnicalClaim unavailable(String evidenceId) {
        return claim(QualificationStatus.UNAVAILABLE, Set.of(evidenceId));
    }

    private static TechnicalClaim claim(
            QualificationStatus status,
            Set<String> evidenceIds
    ) {
        return new TechnicalClaim(status, evidenceIds);
    }
}
