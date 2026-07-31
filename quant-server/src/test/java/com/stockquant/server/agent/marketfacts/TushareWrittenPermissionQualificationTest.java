package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.stockquant.server.agent.marketfacts.TushareWrittenPermissionQualification.EvidenceProvenance.USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION;
import static com.stockquant.server.agent.marketfacts.TushareWrittenPermissionQualification.PermissionStatus.NOT_GRANTED;
import static com.stockquant.server.agent.marketfacts.TushareWrittenPermissionQualification.PermissionStatus.VERIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareWrittenPermissionQualificationTest {

    @Test
    void currentAssessmentRecordsExactSevenItemTranscription() {
        TushareWrittenPermissionQualification value =
                current();
        var quantEvidence = value.evidenceProvenance().get(
                TushareWrittenPermissionQualification
                        .QUANT_SOURCE_EVIDENCE_ID);
        var evidence = value.evidenceProvenance().get(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID);

        assertEquals(
                Set.of(TushareWrittenPermissionQualification
                        .PermissionSubject.QUANT_DATA_SOURCE_USE),
                quantEvidence.supportedPermissionSubjects());
        assertEquals(
                personalUseSubjects(),
                evidence.supportedPermissionSubjects());
        assertEquals(
                "2026-07-31T11:07:00+08:00",
                evidence.transcriptionReceivedAt());
        assertEquals("UNKNOWN", evidence.officialReplyAt());
        assertEquals(
                USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
                evidence.evidenceProvenance());
        assertTrue(evidence.userAttestedOfficialSource());
        assertFalse(evidence.originalArtifactStored());
        assertFalse(evidence.screenshotReviewed());
        assertFalse(evidence.independentSourceAuthenticityReviewed());
        assertTrue(evidence.supportsVerifiedPermission());
        assertEquals(List.of(
                "本地数据库保存：允许",
                "策略回测/历史回放：允许",
                "本地AI或智能体分析：允许",
                "程序自动调用/定时更新：允许",
                "字段结构、Hash、摘要和错误日志留存：允许",
                "可以一直保存到本地",
                "适用于个人Tushare Pro 2000积分账号"
        ), evidence.exactTranscription());
    }

    @Test
    void everyVerifiedPermissionHasRegisteredEvidence() {
        TushareWrittenPermissionQualification value =
                current();
        List<TushareWrittenPermissionQualification.PermissionClaim>
                allowed = List.of(
                value.quantDataSourceUsePermission(),
                value.personalLocalStoragePermission(),
                value.personalBacktestPermission(),
                value.personalAgentAnalysisPermission(),
                value.automatedApiUpdatePermission(),
                value.technicalAuditMetadataRetentionPermission(),
                value.postExpiryDataRetentionPermission(),
                value.personal2000PointAccountScopePermission());

        allowed.forEach(claim -> {
            assertEquals(VERIFIED, claim.status());
            assertFalse(claim.evidenceIds().isEmpty());
            assertTrue(value.evidenceIds().containsAll(
                    claim.evidenceIds()));
        });
        assertEquals(
                2,
                value.evidenceProvenance().size());
        assertTrue(value.evidenceProvenance().values().stream()
                .allMatch(TushareWrittenPermissionQualification
                        .EvidenceMetadata::supportsVerifiedPermission));
        assertEquals(
                NOT_GRANTED,
                value.redistributionPermission().status());
        assertEquals(
                NOT_GRANTED,
                value.commercialDataServicePermission().status());
        assertEquals(
                NOT_GRANTED,
                value.tokenSharingPermission().status());
        assertTrue(value.permissionBlockers().isEmpty());
        assertTrue(value.personalResearchPermissionComplete());
    }

    @Test
    void transcriptionCannotImpersonateReviewedArtifact() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TushareWrittenPermissionQualification
                        .EvidenceMetadata(
                        "TS-WP-TEST",
                        "test",
                        TushareWrittenPermissionQualification
                                .EvidenceSource.TUSHARE_OFFICIAL_REPLY,
                        USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
                        "2026-07-31T11:07:00+08:00",
                        "UNKNOWN",
                        true,
                        false,
                        true,
                        false,
                        Set.of(TushareWrittenPermissionQualification
                                .PermissionSubject
                                .PERSONAL_LOCAL_STORAGE),
                        List.of("test")));
    }

    @Test
    void exactTranscriptionRequiresEveryFrozenProvenanceInvariant() {
        assertInvalidExactTranscription(
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                false,
                false,
                false,
                false,
                List.of("test"));
        assertInvalidExactTranscription(
                TushareWrittenPermissionQualification.EvidenceSource
                        .UNVERIFIED_SOURCE,
                true,
                false,
                false,
                false,
                List.of("test"));
        assertInvalidExactTranscription(
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                true,
                true,
                false,
                false,
                List.of("test"));
        assertInvalidExactTranscription(
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                true,
                false,
                true,
                false,
                List.of("test"));
        assertInvalidExactTranscription(
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                true,
                false,
                false,
                true,
                List.of("test"));
        assertInvalidExactTranscription(
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                true,
                false,
                false,
                false,
                List.of());
    }

    @Test
    void unverifiedProvenanceCannotSupportVerifiedPermission() {
        TushareWrittenPermissionQualification current = current();
        var metadata = metadata(
                TushareWrittenPermissionQualification
                        .EvidenceProvenance.UNVERIFIED,
                true,
                false,
                false,
                false);

        assertFalse(metadata.supportsVerifiedPermission());
        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        inputWithPersonalEvidence(current, metadata)));
    }

    @Test
    void reviewedArtifactRequiresAnActualReviewFact() {
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata(
                        TushareWrittenPermissionQualification
                                .EvidenceProvenance
                                .OFFICIAL_ARTIFACT_REVIEWED,
                        true,
                        false,
                        false,
                        false));
    }

    @Test
    void unsupportedOfficialDocumentCannotUpgradePermission() {
        TushareWrittenPermissionQualification current = current();
        var metadata = metadata(
                TushareWrittenPermissionQualification
                        .EvidenceProvenance.OFFICIAL_DOCUMENT,
                true,
                false,
                false,
                false);

        assertFalse(metadata.supportsVerifiedPermission());
        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        inputWithPersonalEvidence(current, metadata)));
    }

    @Test
    void unreferencedEvidenceMetadataIsRejected() {
        TushareWrittenPermissionQualification current = current();
        Map<String, TushareWrittenPermissionQualification.EvidenceMetadata>
                evidence = new LinkedHashMap<>(
                current.evidenceProvenance());
        evidence.put(
                "TS-WP-EXTRA",
                trustedExactMetadata("TS-WP-EXTRA"));

        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        inputWithEvidence(current, evidence)));
    }

    @Test
    void evidenceMapKeyMustMatchMetadataId() {
        TushareWrittenPermissionQualification current = current();
        Map<String, TushareWrittenPermissionQualification.EvidenceMetadata>
                evidence = new LinkedHashMap<>(
                current.evidenceProvenance());
        evidence.put(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID,
                trustedExactMetadata("TS-WP-MISMATCH"));

        assertThrows(
                IllegalArgumentException.class,
                () -> inputWithEvidence(current, evidence));
    }

    @Test
    void personalUseClaimsCannotBorrowQuantSourceEvidence() {
        TushareWrittenPermissionQualification current = current();
        for (var subject : personalUseSubjects()) {
            var wrongEvidence =
                    TushareWrittenPermissionQualification.PermissionClaim
                            .verified(
                                    subject,
                                    TushareWrittenPermissionQualification
                                            .QUANT_SOURCE_EVIDENCE_ID);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TushareWrittenPermissionQualification.assess(
                            inputReplacingClaim(
                                    current,
                                    subject,
                                    wrongEvidence,
                                    current.evidenceProvenance())));
        }
    }

    @Test
    void quantSourceClaimCannotBorrowPersonalUseEvidence() {
        TushareWrittenPermissionQualification current = current();
        Map<String, TushareWrittenPermissionQualification.EvidenceMetadata>
                evidence = new LinkedHashMap<>(
                current.evidenceProvenance());
        evidence.remove(
                TushareWrittenPermissionQualification
                        .QUANT_SOURCE_EVIDENCE_ID);
        var wrongEvidence =
                TushareWrittenPermissionQualification.PermissionClaim
                        .verified(
                                TushareWrittenPermissionQualification
                                        .PermissionSubject
                                        .QUANT_DATA_SOURCE_USE,
                                TushareWrittenPermissionQualification
                                        .PERSONAL_USE_EVIDENCE_ID);

        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        inputReplacingClaim(
                                current,
                                TushareWrittenPermissionQualification
                                        .PermissionSubject
                                        .QUANT_DATA_SOURCE_USE,
                                wrongEvidence,
                                evidence)));
    }

    @Test
    void assessmentFieldRejectsClaimWithWrongSubject() {
        TushareWrittenPermissionQualification current = current();
        var wrongSubject =
                TushareWrittenPermissionQualification.PermissionClaim
                        .verified(
                                TushareWrittenPermissionQualification
                                        .PermissionSubject.PERSONAL_BACKTEST,
                                TushareWrittenPermissionQualification
                                        .PERSONAL_USE_EVIDENCE_ID);

        assertThrows(
                IllegalArgumentException.class,
                () -> inputWithLocalStorage(current, wrongSubject));
    }

    @Test
    void emptyOrUnrelatedEvidenceSubjectScopeCannotVerifyClaims() {
        TushareWrittenPermissionQualification current = current();
        for (Set<TushareWrittenPermissionQualification.PermissionSubject>
                subjects : List.of(
                Set.<TushareWrittenPermissionQualification
                        .PermissionSubject>of(),
                Set.of(TushareWrittenPermissionQualification
                        .PermissionSubject.QUANT_DATA_SOURCE_USE))) {
            var metadata = trustedExactMetadata(
                    TushareWrittenPermissionQualification
                            .PERSONAL_USE_EVIDENCE_ID,
                    subjects);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TushareWrittenPermissionQualification.assess(
                            inputWithPersonalEvidence(
                                    current,
                                    metadata)));
        }
    }

    @Test
    void evidenceCannotAdvertiseUnreferencedPermissionSubject() {
        TushareWrittenPermissionQualification current = current();
        Set<TushareWrittenPermissionQualification.PermissionSubject>
                subjects = new java.util.LinkedHashSet<>(
                personalUseSubjects());
        subjects.add(TushareWrittenPermissionQualification
                .PermissionSubject.RAW_DATA_REDISTRIBUTION);
        var metadata = trustedExactMetadata(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID,
                subjects);

        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        inputWithPersonalEvidence(current, metadata)));
    }

    @Test
    void unverifiedCorePermissionKeepsCompletionFalse() {
        TushareWrittenPermissionQualification current =
                current();
        TushareWrittenPermissionQualification value =
                TushareWrittenPermissionQualification.assess(
                        inputWithUnverifiedLocalStorage(current));

        assertFalse(value.personalResearchPermissionComplete());
        assertEquals(
                TushareWrittenPermissionQualification.PermissionStatus
                        .UNVERIFIED,
                value.personalLocalStoragePermission().status());
        assertTrue(value.permissionBlockers().contains(
                TushareWrittenPermissionQualification.PermissionBlocker
                        .PERSONAL_LOCAL_STORAGE_UNVERIFIED));
    }

    @Test
    void unknownEvidenceAndRelaxedRestrictionsAreRejected() {
        TushareWrittenPermissionQualification current =
                current();
        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        inputWithLocalStorage(
                                current,
                                TushareWrittenPermissionQualification
                                        .PermissionClaim.verified(
                                        TushareWrittenPermissionQualification
                                                .PermissionSubject
                                                .PERSONAL_LOCAL_STORAGE,
                                        "UNKNOWN-EVIDENCE"))));

        var verifiedRestriction =
                TushareWrittenPermissionQualification.PermissionClaim
                        .verified(
                                TushareWrittenPermissionQualification
                                        .PermissionSubject
                                        .RAW_DATA_REDISTRIBUTION,
                                TushareWrittenPermissionQualification
                                        .PERSONAL_USE_EVIDENCE_ID);
        assertThrows(
                IllegalArgumentException.class,
                () -> TushareWrittenPermissionQualification.assess(
                        new TushareWrittenPermissionQualification
                                .AssessmentInput(
                                current.quantDataSourceUsePermission(),
                                current.personalLocalStoragePermission(),
                                current.personalBacktestPermission(),
                                current.personalAgentAnalysisPermission(),
                                current.automatedApiUpdatePermission(),
                                current
                                        .technicalAuditMetadataRetentionPermission(),
                                current.postExpiryDataRetentionPermission(),
                                current
                                        .personal2000PointAccountScopePermission(),
                                verifiedRestriction,
                                current.commercialDataServicePermission(),
                                current.tokenSharingPermission(),
                                current.evidenceProvenance())));
    }

    @Test
    void modelStringDoesNotContainTranscriptionOrCredentialValue() {
        String rendered = current().toString();
        assertFalse(rendered.contains("这个可以用来当量化数据来源"));
        assertFalse(rendered.contains("sensitive-token-value"));
    }

    private static TushareWrittenPermissionQualification current() {
        return TushareWrittenPermissionQualification
                .currentPersonal2000PointAssessment();
    }

    private static TushareWrittenPermissionQualification.AssessmentInput
    inputWithLocalStorage(
            TushareWrittenPermissionQualification current,
            TushareWrittenPermissionQualification.PermissionClaim
                    localStorage
    ) {
        return new TushareWrittenPermissionQualification.AssessmentInput(
                current.quantDataSourceUsePermission(),
                localStorage,
                current.personalBacktestPermission(),
                current.personalAgentAnalysisPermission(),
                current.automatedApiUpdatePermission(),
                current.technicalAuditMetadataRetentionPermission(),
                current.postExpiryDataRetentionPermission(),
                current.personal2000PointAccountScopePermission(),
                current.redistributionPermission(),
                current.commercialDataServicePermission(),
                current.tokenSharingPermission(),
                current.evidenceProvenance());
    }

    private static TushareWrittenPermissionQualification.AssessmentInput
    inputWithUnverifiedLocalStorage(
            TushareWrittenPermissionQualification current
    ) {
        Map<String, TushareWrittenPermissionQualification.EvidenceMetadata>
                evidence = new LinkedHashMap<>(
                current.evidenceProvenance());
        var personalEvidence = evidence.get(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID);
        Set<TushareWrittenPermissionQualification.PermissionSubject>
                supportedSubjects = new java.util.LinkedHashSet<>(
                personalEvidence.supportedPermissionSubjects());
        supportedSubjects.remove(
                TushareWrittenPermissionQualification.PermissionSubject
                        .PERSONAL_LOCAL_STORAGE);
        evidence.put(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID,
                copyWithSubjects(personalEvidence, supportedSubjects));
        return new TushareWrittenPermissionQualification.AssessmentInput(
                current.quantDataSourceUsePermission(),
                TushareWrittenPermissionQualification.PermissionClaim
                        .unverified(
                                TushareWrittenPermissionQualification
                                        .PermissionSubject
                                        .PERSONAL_LOCAL_STORAGE),
                current.personalBacktestPermission(),
                current.personalAgentAnalysisPermission(),
                current.automatedApiUpdatePermission(),
                current.technicalAuditMetadataRetentionPermission(),
                current.postExpiryDataRetentionPermission(),
                current.personal2000PointAccountScopePermission(),
                current.redistributionPermission(),
                current.commercialDataServicePermission(),
                current.tokenSharingPermission(),
                evidence);
    }

    private static TushareWrittenPermissionQualification.AssessmentInput
    inputWithPersonalEvidence(
            TushareWrittenPermissionQualification current,
            TushareWrittenPermissionQualification.EvidenceMetadata
                    personalEvidence
    ) {
        Map<String, TushareWrittenPermissionQualification.EvidenceMetadata>
                evidence = new LinkedHashMap<>(
                current.evidenceProvenance());
        evidence.put(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID,
                personalEvidence);
        return inputWithEvidence(current, evidence);
    }

    private static TushareWrittenPermissionQualification.AssessmentInput
    inputWithEvidence(
            TushareWrittenPermissionQualification current,
            Map<String,
                    TushareWrittenPermissionQualification.EvidenceMetadata>
                    evidence
    ) {
        return new TushareWrittenPermissionQualification.AssessmentInput(
                current.quantDataSourceUsePermission(),
                current.personalLocalStoragePermission(),
                current.personalBacktestPermission(),
                current.personalAgentAnalysisPermission(),
                current.automatedApiUpdatePermission(),
                current.technicalAuditMetadataRetentionPermission(),
                current.postExpiryDataRetentionPermission(),
                current.personal2000PointAccountScopePermission(),
                current.redistributionPermission(),
                current.commercialDataServicePermission(),
                current.tokenSharingPermission(),
                evidence);
    }

    private static TushareWrittenPermissionQualification.AssessmentInput
    inputReplacingClaim(
            TushareWrittenPermissionQualification current,
            TushareWrittenPermissionQualification.PermissionSubject field,
            TushareWrittenPermissionQualification.PermissionClaim replacement,
            Map<String,
                    TushareWrittenPermissionQualification.EvidenceMetadata>
                    evidence
    ) {
        return new TushareWrittenPermissionQualification.AssessmentInput(
                field == TushareWrittenPermissionQualification
                        .PermissionSubject.QUANT_DATA_SOURCE_USE
                        ? replacement
                        : current.quantDataSourceUsePermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject.PERSONAL_LOCAL_STORAGE
                        ? replacement
                        : current.personalLocalStoragePermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject.PERSONAL_BACKTEST
                        ? replacement
                        : current.personalBacktestPermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject.PERSONAL_AGENT_ANALYSIS
                        ? replacement
                        : current.personalAgentAnalysisPermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject.AUTOMATED_API_UPDATE
                        ? replacement
                        : current.automatedApiUpdatePermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject
                        .TECHNICAL_AUDIT_METADATA_RETENTION
                        ? replacement
                        : current
                                .technicalAuditMetadataRetentionPermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject.POST_EXPIRY_DATA_RETENTION
                        ? replacement
                        : current.postExpiryDataRetentionPermission(),
                field == TushareWrittenPermissionQualification
                        .PermissionSubject
                        .PERSONAL_2000_POINT_ACCOUNT_SCOPE
                        ? replacement
                        : current
                                .personal2000PointAccountScopePermission(),
                current.redistributionPermission(),
                current.commercialDataServicePermission(),
                current.tokenSharingPermission(),
                evidence);
    }

    private static void assertInvalidExactTranscription(
            TushareWrittenPermissionQualification.EvidenceSource source,
            boolean userAttested,
            boolean originalStored,
            boolean screenshotReviewed,
            boolean independentlyReviewed,
            List<String> transcription
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TushareWrittenPermissionQualification
                        .EvidenceMetadata(
                        "TS-WP-TEST",
                        "test",
                        source,
                        USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
                        "2026-07-31T11:07:00+08:00",
                        "UNKNOWN",
                        userAttested,
                        originalStored,
                        screenshotReviewed,
                        independentlyReviewed,
                        Set.of(TushareWrittenPermissionQualification
                                .PermissionSubject
                                .PERSONAL_LOCAL_STORAGE),
                        transcription));
    }

    private static TushareWrittenPermissionQualification.EvidenceMetadata
    metadata(
            TushareWrittenPermissionQualification.EvidenceProvenance
                    provenance,
            boolean userAttested,
            boolean originalStored,
            boolean screenshotReviewed,
            boolean independentlyReviewed
    ) {
        return new TushareWrittenPermissionQualification.EvidenceMetadata(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID,
                "test",
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                provenance,
                "2026-07-31T11:07:00+08:00",
                "UNKNOWN",
                userAttested,
                originalStored,
                screenshotReviewed,
                independentlyReviewed,
                personalUseSubjects(),
                List.of("test"));
    }

    private static TushareWrittenPermissionQualification.EvidenceMetadata
    trustedExactMetadata(String evidenceId) {
        return trustedExactMetadata(
                evidenceId,
                Set.of(TushareWrittenPermissionQualification
                        .PermissionSubject.QUANT_DATA_SOURCE_USE));
    }

    private static TushareWrittenPermissionQualification.EvidenceMetadata
    trustedExactMetadata(
            String evidenceId,
            Set<TushareWrittenPermissionQualification.PermissionSubject>
                    supportedSubjects
    ) {
        return new TushareWrittenPermissionQualification.EvidenceMetadata(
                evidenceId,
                "test",
                TushareWrittenPermissionQualification.EvidenceSource
                        .TUSHARE_OFFICIAL_REPLY,
                USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
                "2026-07-31T11:07:00+08:00",
                "UNKNOWN",
                true,
                false,
                false,
                false,
                supportedSubjects,
                List.of("test"));
    }

    private static TushareWrittenPermissionQualification.EvidenceMetadata
    copyWithSubjects(
            TushareWrittenPermissionQualification.EvidenceMetadata original,
            Set<TushareWrittenPermissionQualification.PermissionSubject>
                    supportedSubjects
    ) {
        return new TushareWrittenPermissionQualification.EvidenceMetadata(
                original.evidenceId(),
                original.evidenceName(),
                original.evidenceSource(),
                original.evidenceProvenance(),
                original.transcriptionReceivedAt(),
                original.officialReplyAt(),
                original.userAttestedOfficialSource(),
                original.originalArtifactStored(),
                original.screenshotReviewed(),
                original.independentSourceAuthenticityReviewed(),
                supportedSubjects,
                original.exactTranscription());
    }

    private static Set<
            TushareWrittenPermissionQualification.PermissionSubject>
    personalUseSubjects() {
        return Set.of(
                TushareWrittenPermissionQualification.PermissionSubject
                        .PERSONAL_LOCAL_STORAGE,
                TushareWrittenPermissionQualification.PermissionSubject
                        .PERSONAL_BACKTEST,
                TushareWrittenPermissionQualification.PermissionSubject
                        .PERSONAL_AGENT_ANALYSIS,
                TushareWrittenPermissionQualification.PermissionSubject
                        .AUTOMATED_API_UPDATE,
                TushareWrittenPermissionQualification.PermissionSubject
                        .TECHNICAL_AUDIT_METADATA_RETENTION,
                TushareWrittenPermissionQualification.PermissionSubject
                        .POST_EXPIRY_DATA_RETENTION,
                TushareWrittenPermissionQualification.PermissionSubject
                        .PERSONAL_2000_POINT_ACCOUNT_SCOPE);
    }
}
