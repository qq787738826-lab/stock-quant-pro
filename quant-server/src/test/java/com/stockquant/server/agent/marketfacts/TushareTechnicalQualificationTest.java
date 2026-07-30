package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.AssessmentInput;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.CorporateActionType;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationDimension;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationStatus;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.ResearchRawPrice;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.TechnicalBlocker;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.DividendEvidence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

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
    void current2000PointEvidenceSelectsReducedResearchOnly() {
        var actual =
                TushareTechnicalQualification.current2000PointAssessment();

        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.fullTechnicalContractReady());
        assertTrue(actual.reducedResearchContractReady());
        assertTrue(actual.forwardSystemKnowledgePitBuildable());
        assertEquals(
                QualificationStatus.VERIFIED,
                actual.rawDailyQualification());
        assertEquals(
                QualificationStatus.VERIFIED,
                actual.adjustmentFactorQualification());
        assertEquals(
                QualificationStatus.VERIFIED,
                actual.calendarQualification());
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
        assertEquals(
                QualificationStatus.UNVERIFIED,
                actual.fullHistoryDailyExactQualification());
        assertEquals(
                QualificationStatus.NOT_SUPPORTED,
                actual.providerPitQualification());
        assertFalse(actual.corporateActionLineageComplete());
        assertFalse(actual.permanentSecurityIdentityVerified());
        assertFalse(actual.providerRevisionAvailable());
        assertFalse(actual.historicalVersionsQueryable());
        assertTrue(actual.evidenceIds().contains("TS-021"));
        assertTrue(actual.blockers().containsAll(Set.of(
                TechnicalBlocker.CORPORATE_ACTION_LINEAGE_INCOMPLETE,
                TechnicalBlocker.STABLE_ACTION_ID_UNAVAILABLE,
                TechnicalBlocker.FACTOR_ACTION_RELATION_UNVERIFIED,
                TechnicalBlocker.PROVIDER_REVISION_UNAVAILABLE,
                TechnicalBlocker.HISTORICAL_VERSIONS_NOT_QUERYABLE,
                TechnicalBlocker.PERMANENT_SECURITY_IDENTITY_UNVERIFIED,
                TechnicalBlocker.FULL_HISTORY_DAILY_EXACT_UNVERIFIED,
                TechnicalBlocker.PROVIDER_PIT_UNAVAILABLE)));
        assertEquals(
                QualificationStatus.PARTIAL,
                actual.corporateActionCoverage()
                        .get(CorporateActionType.CASH_DIVIDEND));
        assertEquals(
                QualificationStatus.PARTIAL,
                actual.corporateActionCoverage()
                        .get(CorporateActionType.STOCK_DIVIDEND));
        assertEquals(
                QualificationStatus.PARTIAL,
                actual.corporateActionCoverage()
                        .get(CorporateActionType.CAPITALIZATION));
        assertEquals(
                QualificationStatus.NOT_SUPPORTED,
                actual.corporateActionCoverage()
                        .get(CorporateActionType.RIGHTS_ISSUE));
    }

    @Test
    void onlyAllFullContractConditionsCanSelectFullRoute() {
        var actual = TushareTechnicalQualification.assess(
                fullInput(
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        true,
                        true,
                        fullActionCoverage(),
                        completeEvidence()));

        assertEquals(
                RouteDecision.FULL_F1_BUILDABLE,
                actual.routeDecision());
        assertTrue(actual.fullTechnicalContractReady());
        assertFalse(actual.reducedResearchContractReady());
        assertTrue(actual.corporateActionLineageComplete());
        assertTrue(actual.permanentSecurityIdentityVerified());
        assertTrue(actual.providerRevisionAvailable());
        assertTrue(actual.historicalVersionsQueryable());
        assertTrue(actual.blockers().isEmpty());
    }

    @Test
    void missingActionIdCanOnlySelectReducedRoute() {
        var actual = TushareTechnicalQualification.assess(
                fullInput(
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        false,
                        true,
                        fullActionCoverage(),
                        completeEvidence()));

        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.corporateActionLineageComplete());
        assertTrue(actual.blockers().contains(
                TechnicalBlocker.STABLE_ACTION_ID_UNAVAILABLE));
    }

    @Test
    void missingRevisionCanOnlySelectReducedRoute() {
        var actual = TushareTechnicalQualification.assess(
                fullInput(
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.NOT_SUPPORTED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        true,
                        true,
                        fullActionCoverage(),
                        completeEvidence()));

        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.providerRevisionAvailable());
        assertTrue(actual.blockers().contains(
                TechnicalBlocker.PROVIDER_REVISION_UNAVAILABLE));
    }

    @Test
    void missingPermanentIdentityCanOnlySelectReducedRoute() {
        var actual = TushareTechnicalQualification.assess(
                fullInput(
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.PARTIAL,
                        QualificationStatus.VERIFIED,
                        true,
                        true,
                        fullActionCoverage(),
                        completeEvidence()));

        assertEquals(
                RouteDecision.REDUCED_RESEARCH_ONLY,
                actual.routeDecision());
        assertFalse(actual.permanentSecurityIdentityVerified());
        assertTrue(actual.blockers().contains(
                TechnicalBlocker
                        .PERMANENT_SECURITY_IDENTITY_UNVERIFIED));
    }

    @Test
    void unavailableCoreFactRejectsProviderRoute() {
        for (int index = 0; index < 3; index++) {
            QualificationStatus raw = index == 0
                    ? QualificationStatus.UNAVAILABLE
                    : QualificationStatus.VERIFIED;
            QualificationStatus factor = index == 1
                    ? QualificationStatus.UNAVAILABLE
                    : QualificationStatus.VERIFIED;
            QualificationStatus calendar = index == 2
                    ? QualificationStatus.UNAVAILABLE
                    : QualificationStatus.VERIFIED;
            var actual = TushareTechnicalQualification.assess(
                    fullInput(
                            raw,
                            factor,
                            QualificationStatus.VERIFIED,
                            QualificationStatus.VERIFIED,
                            QualificationStatus.VERIFIED,
                            true,
                            true,
                            fullActionCoverage(),
                            completeEvidence(),
                            calendar));
            assertEquals(
                    RouteDecision.PROVIDER_ROUTE_REJECTED,
                    actual.routeDecision());
            assertTrue(actual.blockers().contains(
                    TechnicalBlocker.CORE_FACT_CONTRACT_INCOMPLETE));
        }
    }

    @Test
    void verifiedStateWithoutDimensionEvidenceIsDowngraded() {
        Map<QualificationDimension, Set<String>> evidence =
                new EnumMap<>(completeEvidence());
        evidence.remove(QualificationDimension.RAW_DAILY);

        var actual = TushareTechnicalQualification.assess(
                fullInput(
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        QualificationStatus.VERIFIED,
                        true,
                        true,
                        fullActionCoverage(),
                        evidence));

        assertEquals(
                QualificationStatus.UNVERIFIED,
                actual.rawDailyQualification());
        assertEquals(
                RouteDecision.PROVIDER_ROUTE_REJECTED,
                actual.routeDecision());
    }

    @Test
    void requestedEndDateAnchorIsDeterministicAndChangesResult() {
        var raw = java.util.List.of(
                new ResearchRawPrice(FIRST, new BigDecimal("10")),
                new ResearchRawPrice(SECOND, new BigDecimal("12")));
        Map<LocalDate, BigDecimal> factors = Map.of(
                FIRST, new BigDecimal("1"),
                SECOND, new BigDecimal("2"),
                LATER_ANCHOR, new BigDecimal("4"));

        var endDateAnchored =
                TushareTechnicalQualification.calculateReducedResearchQfq(
                        "TUSHARE_PRO", "TUSHARE_PRO",
                        raw, factors, SECOND);
        var repeated =
                TushareTechnicalQualification.calculateReducedResearchQfq(
                        "TUSHARE_PRO", "TUSHARE_PRO",
                        raw, factors, SECOND);
        var laterAnchored =
                TushareTechnicalQualification.calculateReducedResearchQfq(
                        "TUSHARE_PRO", "TUSHARE_PRO",
                        raw, factors, LATER_ANCHOR);

        assertEquals(endDateAnchored, repeated);
        assertEquals(
                java.util.List.of(
                        new BigDecimal("5.0000"),
                        new BigDecimal("12.0000")),
                endDateAnchored.stream()
                        .map(value -> value.qfqPrice())
                        .toList());
        assertEquals(
                java.util.List.of(
                        new BigDecimal("2.5000"),
                        new BigDecimal("6.0000")),
                laterAnchored.stream()
                        .map(value -> value.qfqPrice())
                        .toList());
    }

    @Test
    void missingAnchorOrDailyFactorIsSafelyBlocked() {
        var raw = java.util.List.of(
                new ResearchRawPrice(FIRST, new BigDecimal("10")),
                new ResearchRawPrice(SECOND, new BigDecimal("12")));

        IllegalArgumentException missingAnchor = assertThrows(
                IllegalArgumentException.class,
                () -> TushareTechnicalQualification
                        .calculateReducedResearchQfq(
                                "TUSHARE_PRO", "TUSHARE_PRO",
                                raw,
                                Map.of(
                                        FIRST, BigDecimal.ONE,
                                        SECOND, new BigDecimal("2")),
                                LATER_ANCHOR));
        assertEquals(
                "TUSHARE_QFQ_ANCHOR_FACTOR_UNAVAILABLE",
                missingAnchor.getMessage());

        IllegalArgumentException missingDaily = assertThrows(
                IllegalArgumentException.class,
                () -> TushareTechnicalQualification
                        .calculateReducedResearchQfq(
                                "TUSHARE_PRO", "TUSHARE_PRO",
                                raw,
                                Map.of(SECOND, new BigDecimal("2")),
                                SECOND));
        assertEquals(
                "TUSHARE_QFQ_DAILY_FACTOR_UNAVAILABLE",
                missingDaily.getMessage());
    }

    @Test
    void nonPositiveFactorAndCrossProviderInputsAreRejected() {
        var raw = java.util.List.of(
                new ResearchRawPrice(FIRST, new BigDecimal("10")));

        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> TushareTechnicalQualification
                        .calculateReducedResearchQfq(
                                "TUSHARE_PRO", "TUSHARE_PRO",
                                raw, Map.of(FIRST, BigDecimal.ZERO), FIRST));
        assertEquals(
                "TUSHARE_QFQ_FACTOR_INVALID",
                invalid.getMessage());

        IllegalArgumentException mixed = assertThrows(
                IllegalArgumentException.class,
                () -> TushareTechnicalQualification
                        .calculateReducedResearchQfq(
                                "TUSHARE_PRO", "OTHER_PROVIDER",
                                raw, Map.of(FIRST, BigDecimal.ONE), FIRST));
        assertEquals(
                "TUSHARE_QFQ_CROSS_PROVIDER_FORBIDDEN",
                mixed.getMessage());
    }

    @Test
    void qfqContractCannotConsumeDividendEvidence() {
        boolean consumesDividend = Arrays.stream(
                        TushareTechnicalQualification.class
                                .getDeclaredMethods())
                .filter(method -> method.getName()
                        .equals("calculateReducedResearchQfq"))
                .map(Method::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(type -> type == DividendEvidence.class);

        assertFalse(consumesDividend);
    }

    private static AssessmentInput fullInput(
            QualificationStatus raw,
            QualificationStatus factor,
            QualificationStatus revision,
            QualificationStatus identity,
            QualificationStatus fullHistoryDailyExact,
            boolean stableActionId,
            boolean factorActionRelationship,
            Map<CorporateActionType, QualificationStatus> actions,
            Map<QualificationDimension, Set<String>> evidence
    ) {
        return fullInput(
                raw, factor, revision, identity, fullHistoryDailyExact,
                stableActionId, factorActionRelationship,
                actions, evidence, QualificationStatus.VERIFIED);
    }

    private static AssessmentInput fullInput(
            QualificationStatus raw,
            QualificationStatus factor,
            QualificationStatus revision,
            QualificationStatus identity,
            QualificationStatus fullHistoryDailyExact,
            boolean stableActionId,
            boolean factorActionRelationship,
            Map<CorporateActionType, QualificationStatus> actions,
            Map<QualificationDimension, Set<String>> evidence,
            QualificationStatus calendar
    ) {
        return new AssessmentInput(
                raw,
                factor,
                calendar,
                QualificationStatus.VERIFIED,
                revision,
                QualificationStatus.VERIFIED,
                identity,
                QualificationStatus.VERIFIED,
                fullHistoryDailyExact,
                QualificationStatus.VERIFIED,
                actions,
                evidence,
                stableActionId,
                factorActionRelationship,
                true,
                true);
    }

    private static Map<CorporateActionType, QualificationStatus>
    fullActionCoverage() {
        Map<CorporateActionType, QualificationStatus> result =
                new EnumMap<>(CorporateActionType.class);
        EnumSet.allOf(CorporateActionType.class).forEach(
                type -> result.put(type, QualificationStatus.VERIFIED));
        return result;
    }

    private static Map<QualificationDimension, Set<String>>
    completeEvidence() {
        Map<QualificationDimension, Set<String>> result =
                new EnumMap<>(QualificationDimension.class);
        EnumSet.allOf(QualificationDimension.class).forEach(
                dimension -> result.put(
                        dimension,
                        Set.of("TEST-" + dimension.name())));
        return result;
    }
}
