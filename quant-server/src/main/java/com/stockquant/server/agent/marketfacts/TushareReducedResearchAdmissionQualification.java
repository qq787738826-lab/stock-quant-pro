package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareF1EntryQualification.EntryBlocker;
import com.stockquant.server.agent.marketfacts.TushareF1EntryQualification.EntryReadiness;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationStatus;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evidence-backed admission decision for the F1E dedicated local research
 * implementation. This qualification deliberately separates implementation
 * readiness from operational acceptance and full F1 readiness.
 */
public record TushareReducedResearchAdmissionQualification(
        AdmissionDecision admissionDecision,
        ImplementationReadiness implementationReadiness,
        OperationalReadiness operationalReadiness,
        Map<AdmissionClaimType, AdmissionClaim> claims,
        Set<AdmissionBlocker> blockers,
        Set<String> evidenceIds,
        boolean fullF1EntryReady,
        boolean fullTechnicalContractReady,
        boolean formalEligible,
        boolean reducedResearchLocalRuntimeImplementationReady,
        boolean reducedResearchControlledAcceptanceReady,
        boolean reducedResearchOperationalReady,
        boolean reducedResearchProductionRuntimeReady,
        boolean normalBusinessDatabaseRuntimeReady,
        boolean schedulerRuntimeReady,
        boolean agentDecisionRuntimeReady,
        boolean backtestExecutionRuntimeReady,
        boolean f2bRuntimeReady,
        boolean f3RuntimeReady
) {

    private static final Set<AdmissionClaimType> REQUIRED_CLAIMS =
            Set.of(
                    AdmissionClaimType.WRITTEN_PERSONAL_RESEARCH_PERMISSION,
                    AdmissionClaimType.REDUCED_TECHNICAL_CONTRACT,
                    AdmissionClaimType.ENDPOINT_RATE_LIMIT_ENFORCEMENT,
                    AdmissionClaimType.DEDICATED_DATABASE_GUARD,
                    AdmissionClaimType.MANUAL_BATCH_BOUNDARY,
                    AdmissionClaimType.SYSTEM_KNOWLEDGE_PIT,
                    AdmissionClaimType.FORMULA_ONLY_QFQ,
                    AdmissionClaimType.FULL_F1_ISOLATION);

    public TushareReducedResearchAdmissionQualification {
        admissionDecision = Objects.requireNonNull(
                admissionDecision, "admissionDecision");
        implementationReadiness = Objects.requireNonNull(
                implementationReadiness, "implementationReadiness");
        operationalReadiness = Objects.requireNonNull(
                operationalReadiness, "operationalReadiness");
        claims = copyClaims(claims);
        blockers = Set.copyOf(Objects.requireNonNull(
                blockers, "blockers"));
        evidenceIds = Set.copyOf(Objects.requireNonNull(
                evidenceIds, "evidenceIds"));
        validateInvariants(
                admissionDecision,
                implementationReadiness,
                operationalReadiness,
                claims,
                blockers,
                evidenceIds,
                fullF1EntryReady,
                fullTechnicalContractReady,
                formalEligible,
                reducedResearchLocalRuntimeImplementationReady,
                reducedResearchControlledAcceptanceReady,
                reducedResearchOperationalReady,
                reducedResearchProductionRuntimeReady,
                normalBusinessDatabaseRuntimeReady,
                schedulerRuntimeReady,
                agentDecisionRuntimeReady,
                backtestExecutionRuntimeReady,
                f2bRuntimeReady,
                f3RuntimeReady);
    }

    public static TushareReducedResearchAdmissionQualification
    currentF1eAssessment() {
        TushareWrittenPermissionQualification written =
                TushareWrittenPermissionQualification
                        .currentPersonal2000PointAssessment();
        TushareTechnicalQualification technical =
                TushareTechnicalQualification
                        .current2000PointAssessment();
        TushareF1EntryQualification f1 =
                TushareF1EntryQualification.assess(written, technical);

        Map<AdmissionClaimType, AdmissionClaim> claims =
                new EnumMap<>(AdmissionClaimType.class);
        claims.put(
                AdmissionClaimType.WRITTEN_PERSONAL_RESEARCH_PERMISSION,
                AdmissionClaim.verified(
                        "TS-WP-001", "TS-WP-002"));
        claims.put(
                AdmissionClaimType.REDUCED_TECHNICAL_CONTRACT,
                AdmissionClaim.verified(
                        "TS-F1B-TECHNICAL-QUALIFICATION-001"));
        claims.put(
                AdmissionClaimType.ENDPOINT_RATE_LIMIT_ENFORCEMENT,
                AdmissionClaim.verified(
                        "TS-F1C-ENDPOINT-RATE-LIMIT-001"));
        claims.put(
                AdmissionClaimType.DEDICATED_DATABASE_GUARD,
                AdmissionClaim.verified(
                        "TS-F1E-DEDICATED-DATABASE-GUARD-001"));
        claims.put(
                AdmissionClaimType.MANUAL_BATCH_BOUNDARY,
                AdmissionClaim.verified(
                        "TS-F1E-MANUAL-BATCH-CONTRACT-001"));
        claims.put(
                AdmissionClaimType.SYSTEM_KNOWLEDGE_PIT,
                AdmissionClaim.verified(
                        "TS-F1C-SYSTEM-KNOWLEDGE-PIT-001"));
        claims.put(
                AdmissionClaimType.FORMULA_ONLY_QFQ,
                AdmissionClaim.verified(
                        "TS-F1C-REDUCED-QFQ-RUNTIME-001"));
        claims.put(
                AdmissionClaimType.FULL_F1_ISOLATION,
                AdmissionClaim.verified(
                        "TS-F1E-FULL-F1-ISOLATION-001"));

        boolean sourceReady =
                written.personalResearchPermissionComplete()
                        && technical.routeDecision()
                        == RouteDecision.REDUCED_RESEARCH_ONLY
                        && technical.reducedResearchContractReady()
                        && technical
                        .reducedResearchIsolatedManualRuntimeReady()
                        && technical
                        .qfqReducedResearchRuntimeQualification()
                        == QualificationStatus.VERIFIED
                        && technical.endpointSpecificRateLimitEnforced()
                        && technical
                        .conservativeEndpointMinimumPolicyEnforced()
                        && f1.entryReadiness()
                        == EntryReadiness.BLOCKED_TECHNICAL_EVIDENCE
                        && f1.activeBlockers().equals(
                        Set.of(EntryBlocker.BLOCKED_TECHNICAL_EVIDENCE));
        if (!sourceReady) {
            return blocked(claims);
        }
        return ready(claims);
    }

    private static TushareReducedResearchAdmissionQualification ready(
            Map<AdmissionClaimType, AdmissionClaim> claims
    ) {
        return new TushareReducedResearchAdmissionQualification(
                AdmissionDecision.DEDICATED_LOCAL_RESEARCH_PATH,
                ImplementationReadiness.READY,
                OperationalReadiness.NOT_ACCEPTED,
                claims,
                Set.of(AdmissionBlocker.CONTROLLED_ACCEPTANCE_NOT_RUN),
                collectEvidenceIds(claims),
                false,
                false,
                false,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private static TushareReducedResearchAdmissionQualification blocked(
            Map<AdmissionClaimType, AdmissionClaim> claims
    ) {
        return new TushareReducedResearchAdmissionQualification(
                AdmissionDecision.BLOCKED,
                ImplementationReadiness.BLOCKED,
                OperationalReadiness.BLOCKED,
                claims,
                Set.of(AdmissionBlocker.ADMISSION_SOURCE_QUALIFICATION_INVALID),
                collectEvidenceIds(claims),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false);
    }

    private static Map<AdmissionClaimType, AdmissionClaim> copyClaims(
            Map<AdmissionClaimType, AdmissionClaim> values
    ) {
        Objects.requireNonNull(values, "claims");
        if (!values.keySet().equals(REQUIRED_CLAIMS)) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_ADMISSION_CLAIMS_INVALID");
        }
        Map<AdmissionClaimType, AdmissionClaim> copy =
                new EnumMap<>(AdmissionClaimType.class);
        values.forEach((type, claim) -> {
            if (type == null || claim == null) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_ADMISSION_CLAIMS_INVALID");
            }
            copy.put(type, claim);
        });
        return Map.copyOf(copy);
    }

    private static Set<String> collectEvidenceIds(
            Map<AdmissionClaimType, AdmissionClaim> claims
    ) {
        Set<String> ids = new LinkedHashSet<>();
        claims.values().forEach(claim -> ids.addAll(claim.evidenceIds()));
        return Set.copyOf(ids);
    }

    private static void validateInvariants(
            AdmissionDecision decision,
            ImplementationReadiness implementation,
            OperationalReadiness operational,
            Map<AdmissionClaimType, AdmissionClaim> claims,
            Set<AdmissionBlocker> blockers,
            Set<String> evidenceIds,
            boolean fullF1,
            boolean fullTechnical,
            boolean formal,
            boolean implementationReady,
            boolean acceptanceReady,
            boolean operationalReady,
            boolean productionReady,
            boolean normalDatabaseReady,
            boolean schedulerReady,
            boolean agentReady,
            boolean backtestReady,
            boolean f2bReady,
            boolean f3Ready
    ) {
        boolean claimsVerified =
                claims.values().stream().allMatch(AdmissionClaim::verified);
        boolean readyDecision =
                decision == AdmissionDecision.DEDICATED_LOCAL_RESEARCH_PATH;
        if (readyDecision != claimsVerified
                || (implementation == ImplementationReadiness.READY)
                != readyDecision
                || implementationReady != readyDecision
                || acceptanceReady != readyDecision
                || operational != (readyDecision
                ? OperationalReadiness.NOT_ACCEPTED
                : OperationalReadiness.BLOCKED)
                || !evidenceIds.equals(collectEvidenceIds(claims))
                || blockers.isEmpty()
                || fullF1
                || fullTechnical
                || formal
                || operationalReady
                || productionReady
                || normalDatabaseReady
                || schedulerReady
                || agentReady
                || backtestReady
                || f2bReady
                || f3Ready) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_ADMISSION_INVALID");
        }
    }

    public record AdmissionClaim(
            QualificationStatus status,
            Set<String> evidenceIds
    ) {
        public AdmissionClaim {
            status = Objects.requireNonNull(status, "status");
            evidenceIds = Set.copyOf(Objects.requireNonNull(
                    evidenceIds, "evidenceIds"));
            if ((status == QualificationStatus.VERIFIED)
                    != !evidenceIds.isEmpty()
                    || evidenceIds.stream().anyMatch(
                    id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_ADMISSION_CLAIM_INVALID");
            }
        }

        public static AdmissionClaim verified(String... evidenceIds) {
            return new AdmissionClaim(
                    QualificationStatus.VERIFIED,
                    Set.of(evidenceIds));
        }

        public boolean verified() {
            return status == QualificationStatus.VERIFIED
                    && !evidenceIds.isEmpty();
        }
    }

    public enum AdmissionDecision {
        DEDICATED_LOCAL_RESEARCH_PATH,
        BLOCKED
    }

    public enum ImplementationReadiness {
        READY,
        BLOCKED
    }

    public enum OperationalReadiness {
        READY,
        NOT_ACCEPTED,
        BLOCKED
    }

    public enum AdmissionClaimType {
        WRITTEN_PERSONAL_RESEARCH_PERMISSION,
        REDUCED_TECHNICAL_CONTRACT,
        ENDPOINT_RATE_LIMIT_ENFORCEMENT,
        DEDICATED_DATABASE_GUARD,
        MANUAL_BATCH_BOUNDARY,
        SYSTEM_KNOWLEDGE_PIT,
        FORMULA_ONLY_QFQ,
        FULL_F1_ISOLATION
    }

    public enum AdmissionBlocker {
        CONTROLLED_ACCEPTANCE_NOT_RUN,
        ADMISSION_SOURCE_QUALIFICATION_INVALID
    }
}
