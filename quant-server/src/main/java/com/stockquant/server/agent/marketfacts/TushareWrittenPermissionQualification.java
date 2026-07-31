package com.stockquant.server.agent.marketfacts;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed written-permission qualification for the personal Tushare Pro
 * 2000-point account.
 *
 * <p>The current assessment is based on user-provided exact transcriptions.
 * It does not claim that Codex reviewed an original artifact or independently
 * authenticated the source.</p>
 */
public final class TushareWrittenPermissionQualification {

    public static final String QUANT_SOURCE_EVIDENCE_ID = "TS-WP-001";
    public static final String PERSONAL_USE_EVIDENCE_ID = "TS-WP-002";
    public static final String TRANSCRIPTION_RECEIVED_AT =
            "2026-07-31T11:07:00+08:00";

    private static final List<String> TS_WP_002_TRANSCRIPTION = List.of(
            "本地数据库保存：允许",
            "策略回测/历史回放：允许",
            "本地AI或智能体分析：允许",
            "程序自动调用/定时更新：允许",
            "字段结构、Hash、摘要和错误日志留存：允许",
            "可以一直保存到本地",
            "适用于个人Tushare Pro 2000积分账号");

    private final PermissionClaim quantDataSourceUsePermission;
    private final PermissionClaim personalLocalStoragePermission;
    private final PermissionClaim personalBacktestPermission;
    private final PermissionClaim personalAgentAnalysisPermission;
    private final PermissionClaim automatedApiUpdatePermission;
    private final PermissionClaim technicalAuditMetadataRetentionPermission;
    private final PermissionClaim postExpiryDataRetentionPermission;
    private final PermissionClaim personal2000PointAccountScopePermission;
    private final PermissionClaim redistributionPermission;
    private final PermissionClaim commercialDataServicePermission;
    private final PermissionClaim tokenSharingPermission;
    private final Set<String> evidenceIds;
    private final Map<String, EvidenceMetadata> evidenceProvenance;
    private final Set<PermissionBlocker> permissionBlockers;
    private final boolean personalResearchPermissionComplete;

    private TushareWrittenPermissionQualification(
            AssessmentInput input,
            Set<String> evidenceIds,
            Set<PermissionBlocker> permissionBlockers
    ) {
        this.quantDataSourceUsePermission =
                input.quantDataSourceUsePermission();
        this.personalLocalStoragePermission =
                input.personalLocalStoragePermission();
        this.personalBacktestPermission =
                input.personalBacktestPermission();
        this.personalAgentAnalysisPermission =
                input.personalAgentAnalysisPermission();
        this.automatedApiUpdatePermission =
                input.automatedApiUpdatePermission();
        this.technicalAuditMetadataRetentionPermission =
                input.technicalAuditMetadataRetentionPermission();
        this.postExpiryDataRetentionPermission =
                input.postExpiryDataRetentionPermission();
        this.personal2000PointAccountScopePermission =
                input.personal2000PointAccountScopePermission();
        this.redistributionPermission = input.redistributionPermission();
        this.commercialDataServicePermission =
                input.commercialDataServicePermission();
        this.tokenSharingPermission = input.tokenSharingPermission();
        this.evidenceIds = Set.copyOf(evidenceIds);
        this.evidenceProvenance = Map.copyOf(input.evidenceProvenance());
        this.permissionBlockers = Set.copyOf(permissionBlockers);
        this.personalResearchPermissionComplete =
                permissionBlockers.isEmpty();
    }

    public static TushareWrittenPermissionQualification
    currentPersonal2000PointAssessment() {
        PermissionClaim quant = PermissionClaim.verified(
                PermissionSubject.QUANT_DATA_SOURCE_USE,
                QUANT_SOURCE_EVIDENCE_ID);
        return assess(new AssessmentInput(
                quant,
                PermissionClaim.verified(
                        PermissionSubject.PERSONAL_LOCAL_STORAGE,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.verified(
                        PermissionSubject.PERSONAL_BACKTEST,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.verified(
                        PermissionSubject.PERSONAL_AGENT_ANALYSIS,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.verified(
                        PermissionSubject.AUTOMATED_API_UPDATE,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.verified(
                        PermissionSubject
                                .TECHNICAL_AUDIT_METADATA_RETENTION,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.verified(
                        PermissionSubject.POST_EXPIRY_DATA_RETENTION,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.verified(
                        PermissionSubject
                                .PERSONAL_2000_POINT_ACCOUNT_SCOPE,
                        PERSONAL_USE_EVIDENCE_ID),
                PermissionClaim.notGranted(
                        PermissionSubject.RAW_DATA_REDISTRIBUTION),
                PermissionClaim.notGranted(
                        PermissionSubject.COMMERCIAL_DATA_SERVICE),
                PermissionClaim.notGranted(
                        PermissionSubject.TOKEN_SHARING),
                Map.of(
                        QUANT_SOURCE_EVIDENCE_ID,
                        quantSourceEvidence(),
                        PERSONAL_USE_EVIDENCE_ID,
                        personalUseEvidence())));
    }

    public static TushareWrittenPermissionQualification assess(
            AssessmentInput input
    ) {
        Objects.requireNonNull(input, "input");
        validateRestrictions(input);
        Set<String> evidenceIds = collectEvidenceIds(input);
        validateEvidenceSupport(input, evidenceIds);
        Set<PermissionBlocker> blockers =
                EnumSet.noneOf(PermissionBlocker.class);
        addBlocker(
                blockers,
                input.quantDataSourceUsePermission(),
                PermissionBlocker.QUANT_DATA_SOURCE_USE_UNVERIFIED);
        addBlocker(
                blockers,
                input.personalLocalStoragePermission(),
                PermissionBlocker.PERSONAL_LOCAL_STORAGE_UNVERIFIED);
        addBlocker(
                blockers,
                input.personalBacktestPermission(),
                PermissionBlocker.PERSONAL_BACKTEST_UNVERIFIED);
        addBlocker(
                blockers,
                input.personalAgentAnalysisPermission(),
                PermissionBlocker.PERSONAL_AGENT_ANALYSIS_UNVERIFIED);
        addBlocker(
                blockers,
                input.automatedApiUpdatePermission(),
                PermissionBlocker.AUTOMATED_API_UPDATE_UNVERIFIED);
        addBlocker(
                blockers,
                input.technicalAuditMetadataRetentionPermission(),
                PermissionBlocker
                        .TECHNICAL_AUDIT_METADATA_RETENTION_UNVERIFIED);
        addBlocker(
                blockers,
                input.postExpiryDataRetentionPermission(),
                PermissionBlocker.POST_EXPIRY_DATA_RETENTION_UNVERIFIED);
        addBlocker(
                blockers,
                input.personal2000PointAccountScopePermission(),
                PermissionBlocker.PERSONAL_2000_POINT_SCOPE_UNVERIFIED);
        return new TushareWrittenPermissionQualification(
                input, evidenceIds, Set.copyOf(blockers));
    }

    private static void validateEvidenceSupport(
            AssessmentInput input,
            Set<String> evidenceIds
    ) {
        Map<String, EvidenceMetadata> metadataById =
                input.evidenceProvenance();
        if (!metadataById.keySet().equals(evidenceIds)) {
            throw new IllegalArgumentException(
                    "permission evidence provenance must match claim IDs");
        }
        Map<String, Set<PermissionSubject>> referencedSubjects =
                new java.util.LinkedHashMap<>();
        for (PermissionClaim claim : input.claims()) {
            if (!claim.verified()) {
                continue;
            }
            for (String evidenceId : claim.evidenceIds()) {
                EvidenceMetadata metadata = metadataById.get(evidenceId);
                if (metadata == null
                        || !evidenceId.equals(metadata.evidenceId())) {
                    throw new IllegalArgumentException(
                            "permission evidence metadata ID mismatch");
                }
                if (!metadata.supportsVerifiedPermission()) {
                    throw new IllegalArgumentException(
                            "permission evidence is not qualified for "
                                    + "VERIFIED: " + evidenceId);
                }
                if (!metadata.supportedPermissionSubjects()
                        .contains(claim.subject())) {
                    throw new IllegalArgumentException(
                            "permission evidence does not support claim "
                                    + "subject: " + claim.subject());
                }
                referencedSubjects.computeIfAbsent(
                        evidenceId,
                        ignored -> EnumSet.noneOf(PermissionSubject.class))
                        .add(claim.subject());
            }
        }
        for (Map.Entry<String, EvidenceMetadata> entry
                : metadataById.entrySet()) {
            Set<PermissionSubject> subjects =
                    referencedSubjects.get(entry.getKey());
            if (subjects == null
                    || !Set.copyOf(subjects).equals(
                    entry.getValue().supportedPermissionSubjects())) {
                throw new IllegalArgumentException(
                        "permission evidence subjects must exactly match "
                                + "referencing claims: " + entry.getKey());
            }
        }
    }

    private static EvidenceMetadata quantSourceEvidence() {
        return new EvidenceMetadata(
                QUANT_SOURCE_EVIDENCE_ID,
                "Tushare量化数据来源用途官方回复转录",
                EvidenceSource.TUSHARE_OFFICIAL_REPLY,
                EvidenceProvenance
                        .USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
                "2026-07-30_TIME_UNKNOWN",
                "UNKNOWN",
                true,
                false,
                false,
                false,
                Set.of(PermissionSubject.QUANT_DATA_SOURCE_USE),
                List.of(
                        "问：这个可以用来当量化数据来源吧",
                        "答：可以"));
    }

    private static EvidenceMetadata personalUseEvidence() {
        return new EvidenceMetadata(
                PERSONAL_USE_EVIDENCE_ID,
                "Tushare个人Pro 2000积分账号七项使用许可官方回复转录",
                EvidenceSource.TUSHARE_OFFICIAL_REPLY,
                EvidenceProvenance
                        .USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
                TRANSCRIPTION_RECEIVED_AT,
                "UNKNOWN",
                true,
                false,
                false,
                false,
                EnumSet.of(
                        PermissionSubject.PERSONAL_LOCAL_STORAGE,
                        PermissionSubject.PERSONAL_BACKTEST,
                        PermissionSubject.PERSONAL_AGENT_ANALYSIS,
                        PermissionSubject.AUTOMATED_API_UPDATE,
                        PermissionSubject
                                .TECHNICAL_AUDIT_METADATA_RETENTION,
                        PermissionSubject.POST_EXPIRY_DATA_RETENTION,
                        PermissionSubject
                                .PERSONAL_2000_POINT_ACCOUNT_SCOPE),
                TS_WP_002_TRANSCRIPTION);
    }

    private static void validateRestrictions(AssessmentInput input) {
        if (input.redistributionPermission().status()
                != PermissionStatus.NOT_GRANTED
                || input.commercialDataServicePermission().status()
                != PermissionStatus.NOT_GRANTED
                || input.tokenSharingPermission().status()
                != PermissionStatus.NOT_GRANTED) {
            throw new IllegalArgumentException(
                    "personal research restrictions must remain NOT_GRANTED");
        }
    }

    private static Set<String> collectEvidenceIds(AssessmentInput input) {
        Set<String> ids = new LinkedHashSet<>();
        input.claims().forEach(claim -> ids.addAll(claim.evidenceIds()));
        if (ids.stream().anyMatch(id ->
                !input.evidenceProvenance().containsKey(id))) {
            throw new IllegalArgumentException(
                    "verified permission lacks evidence provenance");
        }
        return Set.copyOf(ids);
    }

    private static void addBlocker(
            Set<PermissionBlocker> blockers,
            PermissionClaim claim,
            PermissionBlocker blocker
    ) {
        if (!claim.verified()) {
            blockers.add(blocker);
        }
    }

    public PermissionClaim quantDataSourceUsePermission() {
        return quantDataSourceUsePermission;
    }

    public PermissionClaim personalLocalStoragePermission() {
        return personalLocalStoragePermission;
    }

    public PermissionClaim personalBacktestPermission() {
        return personalBacktestPermission;
    }

    public PermissionClaim personalAgentAnalysisPermission() {
        return personalAgentAnalysisPermission;
    }

    public PermissionClaim automatedApiUpdatePermission() {
        return automatedApiUpdatePermission;
    }

    public PermissionClaim technicalAuditMetadataRetentionPermission() {
        return technicalAuditMetadataRetentionPermission;
    }

    public PermissionClaim postExpiryDataRetentionPermission() {
        return postExpiryDataRetentionPermission;
    }

    public PermissionClaim personal2000PointAccountScopePermission() {
        return personal2000PointAccountScopePermission;
    }

    public PermissionClaim redistributionPermission() {
        return redistributionPermission;
    }

    public PermissionClaim commercialDataServicePermission() {
        return commercialDataServicePermission;
    }

    public PermissionClaim tokenSharingPermission() {
        return tokenSharingPermission;
    }

    public Set<String> evidenceIds() {
        return evidenceIds;
    }

    public Map<String, EvidenceMetadata> evidenceProvenance() {
        return evidenceProvenance;
    }

    public Set<PermissionBlocker> permissionBlockers() {
        return permissionBlockers;
    }

    public boolean personalResearchPermissionComplete() {
        return personalResearchPermissionComplete;
    }

    @Override
    public String toString() {
        return "TushareWrittenPermissionQualification["
                + "personalResearchPermissionComplete="
                + personalResearchPermissionComplete
                + ", evidenceIds=" + evidenceIds
                + ", permissionBlockers=" + permissionBlockers
                + ", restrictedUses=NOT_GRANTED]";
    }

    public record PermissionClaim(
            PermissionSubject subject,
            PermissionStatus status,
            Set<String> evidenceIds
    ) {
        public PermissionClaim {
            subject = Objects.requireNonNull(subject, "subject");
            status = Objects.requireNonNull(status, "status");
            evidenceIds = Set.copyOf(Objects.requireNonNull(
                    evidenceIds, "evidenceIds"));
            if (evidenceIds.stream().anyMatch(
                    id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException(
                        "permission evidence IDs must not contain blanks");
            }
            if (status == PermissionStatus.VERIFIED
                    && evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "VERIFIED permission requires evidence");
            }
            if (status != PermissionStatus.VERIFIED
                    && !evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "non-VERIFIED permission must not claim evidence");
            }
        }

        public static PermissionClaim verified(
                PermissionSubject subject,
                String evidenceId
        ) {
            return new PermissionClaim(
                    Objects.requireNonNull(subject, "subject"),
                    PermissionStatus.VERIFIED,
                    Set.of(requiredText(evidenceId, "evidenceId")));
        }

        public static PermissionClaim unverified(
                PermissionSubject subject
        ) {
            return new PermissionClaim(
                    Objects.requireNonNull(subject, "subject"),
                    PermissionStatus.UNVERIFIED, Set.of());
        }

        public static PermissionClaim notGranted(
                PermissionSubject subject
        ) {
            return new PermissionClaim(
                    Objects.requireNonNull(subject, "subject"),
                    PermissionStatus.NOT_GRANTED, Set.of());
        }

        public boolean verified() {
            return status == PermissionStatus.VERIFIED
                    && !evidenceIds.isEmpty();
        }
    }

    public record EvidenceMetadata(
            String evidenceId,
            String evidenceName,
            EvidenceSource evidenceSource,
            EvidenceProvenance evidenceProvenance,
            String transcriptionReceivedAt,
            String officialReplyAt,
            boolean userAttestedOfficialSource,
            boolean originalArtifactStored,
            boolean screenshotReviewed,
            boolean independentSourceAuthenticityReviewed,
            Set<PermissionSubject> supportedPermissionSubjects,
            List<String> exactTranscription
    ) {
        public EvidenceMetadata {
            evidenceId = requiredText(evidenceId, "evidenceId");
            evidenceName = requiredText(evidenceName, "evidenceName");
            evidenceSource = Objects.requireNonNull(
                    evidenceSource, "evidenceSource");
            evidenceProvenance = Objects.requireNonNull(
                    evidenceProvenance, "evidenceProvenance");
            transcriptionReceivedAt = requiredText(
                    transcriptionReceivedAt, "transcriptionReceivedAt");
            officialReplyAt = requiredText(
                    officialReplyAt, "officialReplyAt");
            supportedPermissionSubjects = Set.copyOf(
                    Objects.requireNonNull(
                            supportedPermissionSubjects,
                            "supportedPermissionSubjects"));
            exactTranscription = List.copyOf(Objects.requireNonNull(
                    exactTranscription, "exactTranscription"));
            if (exactTranscription.isEmpty()
                    || exactTranscription.stream().anyMatch(
                    value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException(
                        "exact transcription must not be blank");
            }
            if (evidenceProvenance
                    == EvidenceProvenance
                    .USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION) {
                if (evidenceSource
                        != EvidenceSource.TUSHARE_OFFICIAL_REPLY
                        || !userAttestedOfficialSource) {
                    throw new IllegalArgumentException(
                            "official transcription requires user-attested "
                                    + "Tushare official reply source");
                }
                if (originalArtifactStored
                        || screenshotReviewed
                        || independentSourceAuthenticityReviewed) {
                    throw new IllegalArgumentException(
                            "transcription provenance cannot impersonate "
                                    + "artifact review");
                }
            }
            if (evidenceProvenance
                    == EvidenceProvenance.OFFICIAL_ARTIFACT_REVIEWED
                    && (evidenceSource
                    != EvidenceSource.TUSHARE_OFFICIAL_REPLY
                    || !userAttestedOfficialSource
                    || (!originalArtifactStored && !screenshotReviewed))) {
                throw new IllegalArgumentException(
                        "reviewed artifact provenance requires an official "
                                + "source and an actual review fact");
            }
        }

        public boolean supportsVerifiedPermission() {
            return switch (evidenceProvenance) {
                case USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION ->
                        evidenceSource
                                == EvidenceSource.TUSHARE_OFFICIAL_REPLY
                                && userAttestedOfficialSource
                                && !originalArtifactStored
                                && !screenshotReviewed
                                && !independentSourceAuthenticityReviewed
                                && !supportedPermissionSubjects.isEmpty()
                                && !exactTranscription.isEmpty();
                case OFFICIAL_ARTIFACT_REVIEWED ->
                        evidenceSource
                                == EvidenceSource.TUSHARE_OFFICIAL_REPLY
                                && userAttestedOfficialSource
                                && (originalArtifactStored
                                || screenshotReviewed)
                                && !supportedPermissionSubjects.isEmpty()
                                && !exactTranscription.isEmpty();
                case OFFICIAL_DOCUMENT, UNVERIFIED -> false;
            };
        }
    }

    public record AssessmentInput(
            PermissionClaim quantDataSourceUsePermission,
            PermissionClaim personalLocalStoragePermission,
            PermissionClaim personalBacktestPermission,
            PermissionClaim personalAgentAnalysisPermission,
            PermissionClaim automatedApiUpdatePermission,
            PermissionClaim technicalAuditMetadataRetentionPermission,
            PermissionClaim postExpiryDataRetentionPermission,
            PermissionClaim personal2000PointAccountScopePermission,
            PermissionClaim redistributionPermission,
            PermissionClaim commercialDataServicePermission,
            PermissionClaim tokenSharingPermission,
            Map<String, EvidenceMetadata> evidenceProvenance
    ) {
        public AssessmentInput {
            quantDataSourceUsePermission = required(
                    quantDataSourceUsePermission,
                    "quantDataSourceUsePermission");
            personalLocalStoragePermission = required(
                    personalLocalStoragePermission,
                    "personalLocalStoragePermission");
            personalBacktestPermission = required(
                    personalBacktestPermission,
                    "personalBacktestPermission");
            personalAgentAnalysisPermission = required(
                    personalAgentAnalysisPermission,
                    "personalAgentAnalysisPermission");
            automatedApiUpdatePermission = required(
                    automatedApiUpdatePermission,
                    "automatedApiUpdatePermission");
            technicalAuditMetadataRetentionPermission = required(
                    technicalAuditMetadataRetentionPermission,
                    "technicalAuditMetadataRetentionPermission");
            postExpiryDataRetentionPermission = required(
                    postExpiryDataRetentionPermission,
                    "postExpiryDataRetentionPermission");
            personal2000PointAccountScopePermission = required(
                    personal2000PointAccountScopePermission,
                    "personal2000PointAccountScopePermission");
            redistributionPermission = required(
                    redistributionPermission,
                    "redistributionPermission");
            commercialDataServicePermission = required(
                    commercialDataServicePermission,
                    "commercialDataServicePermission");
            tokenSharingPermission = required(
                    tokenSharingPermission,
                    "tokenSharingPermission");
            requireSubject(
                    quantDataSourceUsePermission,
                    PermissionSubject.QUANT_DATA_SOURCE_USE,
                    "quantDataSourceUsePermission");
            requireSubject(
                    personalLocalStoragePermission,
                    PermissionSubject.PERSONAL_LOCAL_STORAGE,
                    "personalLocalStoragePermission");
            requireSubject(
                    personalBacktestPermission,
                    PermissionSubject.PERSONAL_BACKTEST,
                    "personalBacktestPermission");
            requireSubject(
                    personalAgentAnalysisPermission,
                    PermissionSubject.PERSONAL_AGENT_ANALYSIS,
                    "personalAgentAnalysisPermission");
            requireSubject(
                    automatedApiUpdatePermission,
                    PermissionSubject.AUTOMATED_API_UPDATE,
                    "automatedApiUpdatePermission");
            requireSubject(
                    technicalAuditMetadataRetentionPermission,
                    PermissionSubject
                            .TECHNICAL_AUDIT_METADATA_RETENTION,
                    "technicalAuditMetadataRetentionPermission");
            requireSubject(
                    postExpiryDataRetentionPermission,
                    PermissionSubject.POST_EXPIRY_DATA_RETENTION,
                    "postExpiryDataRetentionPermission");
            requireSubject(
                    personal2000PointAccountScopePermission,
                    PermissionSubject.PERSONAL_2000_POINT_ACCOUNT_SCOPE,
                    "personal2000PointAccountScopePermission");
            requireSubject(
                    redistributionPermission,
                    PermissionSubject.RAW_DATA_REDISTRIBUTION,
                    "redistributionPermission");
            requireSubject(
                    commercialDataServicePermission,
                    PermissionSubject.COMMERCIAL_DATA_SERVICE,
                    "commercialDataServicePermission");
            requireSubject(
                    tokenSharingPermission,
                    PermissionSubject.TOKEN_SHARING,
                    "tokenSharingPermission");
            evidenceProvenance = Map.copyOf(Objects.requireNonNull(
                    evidenceProvenance, "evidenceProvenance"));
            evidenceProvenance.forEach((id, metadata) -> {
                if (!requiredText(id, "evidence provenance ID")
                        .equals(Objects.requireNonNull(
                                metadata, "evidence metadata")
                                .evidenceId())) {
                    throw new IllegalArgumentException(
                            "evidence provenance key mismatch");
                }
            });
        }

        List<PermissionClaim> claims() {
            return List.of(
                    quantDataSourceUsePermission,
                    personalLocalStoragePermission,
                    personalBacktestPermission,
                    personalAgentAnalysisPermission,
                    automatedApiUpdatePermission,
                    technicalAuditMetadataRetentionPermission,
                    postExpiryDataRetentionPermission,
                    personal2000PointAccountScopePermission,
                    redistributionPermission,
                    commercialDataServicePermission,
                    tokenSharingPermission);
        }
    }

    private static PermissionClaim required(
            PermissionClaim value,
            String name
    ) {
        return Objects.requireNonNull(value, name);
    }

    private static void requireSubject(
            PermissionClaim claim,
            PermissionSubject expected,
            String field
    ) {
        if (claim.subject() != expected) {
            throw new IllegalArgumentException(
                    field + " subject must be " + expected);
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum PermissionStatus {
        VERIFIED,
        UNVERIFIED,
        NOT_GRANTED
    }

    public enum PermissionSubject {
        QUANT_DATA_SOURCE_USE,
        PERSONAL_LOCAL_STORAGE,
        PERSONAL_BACKTEST,
        PERSONAL_AGENT_ANALYSIS,
        AUTOMATED_API_UPDATE,
        TECHNICAL_AUDIT_METADATA_RETENTION,
        POST_EXPIRY_DATA_RETENTION,
        PERSONAL_2000_POINT_ACCOUNT_SCOPE,
        RAW_DATA_REDISTRIBUTION,
        COMMERCIAL_DATA_SERVICE,
        TOKEN_SHARING
    }

    public enum EvidenceSource {
        TUSHARE_OFFICIAL_REPLY,
        UNVERIFIED_SOURCE
    }

    public enum EvidenceProvenance {
        OFFICIAL_ARTIFACT_REVIEWED,
        USER_PROVIDED_EXACT_OFFICIAL_TRANSCRIPTION,
        OFFICIAL_DOCUMENT,
        UNVERIFIED
    }

    public enum PermissionBlocker {
        QUANT_DATA_SOURCE_USE_UNVERIFIED,
        PERSONAL_LOCAL_STORAGE_UNVERIFIED,
        PERSONAL_BACKTEST_UNVERIFIED,
        PERSONAL_AGENT_ANALYSIS_UNVERIFIED,
        AUTOMATED_API_UPDATE_UNVERIFIED,
        TECHNICAL_AUDIT_METADATA_RETENTION_UNVERIFIED,
        POST_EXPIRY_DATA_RETENTION_UNVERIFIED,
        PERSONAL_2000_POINT_SCOPE_UNVERIFIED
    }
}
