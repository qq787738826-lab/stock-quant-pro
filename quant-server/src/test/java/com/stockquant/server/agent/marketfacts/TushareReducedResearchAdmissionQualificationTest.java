package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.AdmissionDecision;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.ImplementationReadiness;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.OperationalReadiness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
