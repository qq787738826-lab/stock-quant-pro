package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.stockquant.server.agent.marketfacts.TushareF1EntryQualification.EntryReadiness.BLOCKED_MULTIPLE;
import static com.stockquant.server.agent.marketfacts.TushareF1EntryQualification.EntryReadiness.BLOCKED_TECHNICAL_EVIDENCE;
import static com.stockquant.server.agent.marketfacts.TushareF1EntryQualification.GateStatus.BLOCKED;
import static com.stockquant.server.agent.marketfacts.TushareF1EntryQualification.GateStatus.PASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareF1EntryQualificationTest {

    @Test
    void currentAssessmentHasOnlyTechnicalEntryBlocker() {
        TushareTechnicalQualification technical =
                TushareTechnicalQualification
                        .current2000PointAssessment();
        TushareF1EntryQualification value =
                TushareF1EntryQualification.assess(
                        TushareWrittenPermissionQualification
                                .currentPersonal2000PointAssessment(),
                        technical);

        assertEquals(PASS, value.writtenPermissionGate());
        assertEquals(BLOCKED, value.technicalEvidenceGate());
        assertEquals(
                BLOCKED_TECHNICAL_EVIDENCE,
                value.entryReadiness());
        assertEquals(
                Set.of(TushareF1EntryQualification.EntryBlocker
                        .BLOCKED_TECHNICAL_EVIDENCE),
                value.activeBlockers());
        assertFalse(value.fullF1EntryReady());
        assertFalse(technical.fullTechnicalContractReady());
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .CORPORATE_ACTION_LINEAGE_INCOMPLETE));
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .PROVIDER_REVISION_UNAVAILABLE));
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .HISTORICAL_VERSIONS_NOT_QUERYABLE));
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .PERMANENT_SECURITY_IDENTITY_UNVERIFIED));
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .FULL_HISTORY_DAILY_EXACT_UNVERIFIED));
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .PROVIDER_PIT_UNAVAILABLE));
        assertTrue(technical.blockers().contains(
                TushareTechnicalQualification.TechnicalBlocker
                        .QFQ_OPERATIONAL_RUNTIME_INCOMPLETE));
    }

    @Test
    void incompletePermissionAndTechnicalEvidenceRemainMultiple() {
        TushareWrittenPermissionQualification complete =
                TushareWrittenPermissionQualification
                        .currentPersonal2000PointAssessment();
        Map<String, TushareWrittenPermissionQualification.EvidenceMetadata>
                evidence = new LinkedHashMap<>(
                complete.evidenceProvenance());
        var personalEvidence = evidence.get(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID);
        Set<TushareWrittenPermissionQualification.PermissionSubject>
                subjects = new LinkedHashSet<>(
                personalEvidence.supportedPermissionSubjects());
        subjects.remove(
                TushareWrittenPermissionQualification.PermissionSubject
                        .PERSONAL_LOCAL_STORAGE);
        evidence.put(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID,
                new TushareWrittenPermissionQualification.EvidenceMetadata(
                        personalEvidence.evidenceId(),
                        personalEvidence.evidenceName(),
                        personalEvidence.evidenceSource(),
                        personalEvidence.evidenceProvenance(),
                        personalEvidence.transcriptionReceivedAt(),
                        personalEvidence.officialReplyAt(),
                        personalEvidence.userAttestedOfficialSource(),
                        personalEvidence.originalArtifactStored(),
                        personalEvidence.screenshotReviewed(),
                        personalEvidence
                                .independentSourceAuthenticityReviewed(),
                        subjects,
                        personalEvidence.exactTranscription()));
        var incomplete =
                TushareWrittenPermissionQualification.assess(
                        new TushareWrittenPermissionQualification
                                .AssessmentInput(
                                complete.quantDataSourceUsePermission(),
                                TushareWrittenPermissionQualification
                                        .PermissionClaim.unverified(
                                        TushareWrittenPermissionQualification
                                                .PermissionSubject
                                                .PERSONAL_LOCAL_STORAGE),
                                complete.personalBacktestPermission(),
                                complete.personalAgentAnalysisPermission(),
                                complete.automatedApiUpdatePermission(),
                                complete
                                        .technicalAuditMetadataRetentionPermission(),
                                complete.postExpiryDataRetentionPermission(),
                                complete
                                        .personal2000PointAccountScopePermission(),
                                complete.redistributionPermission(),
                                complete.commercialDataServicePermission(),
                                complete.tokenSharingPermission(),
                                evidence));
        TushareF1EntryQualification value =
                TushareF1EntryQualification.assess(
                        incomplete,
                        TushareTechnicalQualification
                                .current2000PointAssessment());

        assertEquals(BLOCKED, value.writtenPermissionGate());
        assertEquals(BLOCKED, value.technicalEvidenceGate());
        assertEquals(BLOCKED_MULTIPLE, value.entryReadiness());
        assertEquals(
                Set.of(
                        TushareF1EntryQualification.EntryBlocker
                                .BLOCKED_WRITTEN_PERMISSION,
                        TushareF1EntryQualification.EntryBlocker
                                .BLOCKED_TECHNICAL_EVIDENCE),
                value.activeBlockers());
        assertFalse(value.fullF1EntryReady());
    }
}
