package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.AdmissionDecision;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.AdmissionBlocker;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.ImplementationReadiness;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.OperationalReadiness;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationStatus;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TushareReducedResearchAdmissionQualificationTest {

    @Test
    void freezesImplementationReadyButOperationalAndFullF1Blocked() {
        var qualification =
                TushareReducedResearchAdmissionQualification
                        .currentF1eAssessment();

        assertEquals(
                AdmissionDecision.DEDICATED_LOCAL_RESEARCH_PATH,
                qualification.admissionDecision());
        assertEquals(
                ImplementationReadiness.READY,
                qualification.implementationReadiness());
        assertEquals(
                OperationalReadiness.NOT_ACCEPTED,
                qualification.operationalReadiness());
        assertTrue(qualification.claims().values().stream()
                .allMatch(TushareReducedResearchAdmissionQualification
                        .AdmissionClaim::verified));
        assertFalse(qualification.evidenceIds().isEmpty());
        assertTrue(qualification
                .reducedResearchLocalRuntimeImplementationReady());
        assertTrue(qualification
                .reducedResearchControlledAcceptanceReady());
        assertFalse(qualification.reducedResearchOperationalReady());
        assertFalse(qualification
                .reducedResearchProductionRuntimeReady());
        assertFalse(qualification.normalBusinessDatabaseRuntimeReady());
        assertFalse(qualification.schedulerRuntimeReady());
        assertFalse(qualification.agentDecisionRuntimeReady());
        assertFalse(qualification.backtestExecutionRuntimeReady());
        assertFalse(qualification.f2bRuntimeReady());
        assertFalse(qualification.f3RuntimeReady());
        assertFalse(qualification.fullF1EntryReady());
        assertFalse(qualification.fullTechnicalContractReady());
        assertFalse(qualification.formalEligible());
    }

    @Test
    void incompleteWrittenPermissionReturnsBlockedInsteadOfThrowing() {
        TushareWrittenPermissionQualification written =
                mock(TushareWrittenPermissionQualification.class);
        when(written.personalResearchPermissionComplete())
                .thenReturn(false);

        assertBlocked(
                TushareReducedResearchAdmissionQualification.assess(
                        written,
                        TushareTechnicalQualification
                                .current2000PointAssessment(),
                        TushareReducedResearchAdmissionQualification
                                .ImplementationEvidence.currentF1e()));
    }

    @Test
    void nonReducedTechnicalRouteReturnsBlocked() {
        TushareTechnicalQualification technical =
                mock(TushareTechnicalQualification.class);
        when(technical.routeDecision()).thenReturn(
                RouteDecision.PROVIDER_ROUTE_REJECTED);

        assertBlocked(
                TushareReducedResearchAdmissionQualification.assess(
                        TushareWrittenPermissionQualification
                                .currentPersonal2000PointAssessment(),
                        technical,
                        TushareReducedResearchAdmissionQualification
                                .ImplementationEvidence.currentF1e()));
    }

    @Test
    void endpointRateLimitQualificationRegressionReturnsBlocked() {
        TushareTechnicalQualification technical =
                mock(TushareTechnicalQualification.class);
        when(technical.routeDecision()).thenReturn(
                RouteDecision.REDUCED_RESEARCH_ONLY);
        when(technical.reducedResearchContractReady())
                .thenReturn(true);
        when(technical.reducedResearchIsolatedManualRuntimeReady())
                .thenReturn(true);
        when(technical.qfqReducedResearchRuntimeQualification())
                .thenReturn(QualificationStatus.VERIFIED);
        when(technical.endpointSpecificRateLimitEnforced())
                .thenReturn(false);
        when(technical.conservativeEndpointMinimumPolicyEnforced())
                .thenReturn(true);

        assertBlocked(
                TushareReducedResearchAdmissionQualification.assess(
                        TushareWrittenPermissionQualification
                                .currentPersonal2000PointAssessment(),
                        technical,
                        TushareReducedResearchAdmissionQualification
                                .ImplementationEvidence.currentF1e()));
    }

    private static void assertBlocked(
            TushareReducedResearchAdmissionQualification qualification
    ) {
        assertEquals(
                AdmissionDecision.BLOCKED,
                qualification.admissionDecision());
        assertEquals(
                ImplementationReadiness.BLOCKED,
                qualification.implementationReadiness());
        assertEquals(
                OperationalReadiness.BLOCKED,
                qualification.operationalReadiness());
        assertEquals(
                Set.of(AdmissionBlocker
                        .ADMISSION_SOURCE_QUALIFICATION_INVALID),
                qualification.blockers());
        assertTrue(qualification.claims().values().stream()
                .allMatch(TushareReducedResearchAdmissionQualification
                        .AdmissionClaim::verified));
        assertFalse(qualification.fullF1EntryReady());
        assertFalse(qualification.fullTechnicalContractReady());
        assertFalse(qualification.formalEligible());
        assertFalse(qualification
                .reducedResearchLocalRuntimeImplementationReady());
        assertFalse(qualification
                .reducedResearchControlledAcceptanceReady());
        assertFalse(qualification.reducedResearchOperationalReady());
        assertFalse(qualification
                .reducedResearchProductionRuntimeReady());
        assertFalse(qualification.normalBusinessDatabaseRuntimeReady());
        assertFalse(qualification.schedulerRuntimeReady());
        assertFalse(qualification.agentDecisionRuntimeReady());
        assertFalse(qualification.backtestExecutionRuntimeReady());
        assertFalse(qualification.f2bRuntimeReady());
        assertFalse(qualification.f3RuntimeReady());
    }
}
