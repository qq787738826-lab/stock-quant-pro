package com.stockquant.server.agent.marketfacts;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        var evidence = value.evidenceProvenance().get(
                TushareWrittenPermissionQualification
                        .PERSONAL_USE_EVIDENCE_ID);

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
    void unverifiedCorePermissionKeepsCompletionFalse() {
        TushareWrittenPermissionQualification current =
                current();
        TushareWrittenPermissionQualification value =
                TushareWrittenPermissionQualification.assess(
                        inputWithLocalStorage(
                                current,
                                TushareWrittenPermissionQualification
                                        .PermissionClaim.unverified()));

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
                                        "UNKNOWN-EVIDENCE"))));

        var verifiedRestriction =
                TushareWrittenPermissionQualification.PermissionClaim
                        .verified(
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
                List.of("test"));
    }

    private static TushareWrittenPermissionQualification.EvidenceMetadata
    trustedExactMetadata(String evidenceId) {
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
                List.of("test"));
    }
}
