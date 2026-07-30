package com.stockquant.server.agent.marketfacts;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, evidence-backed technical qualification for the Tushare
 * 2000-point route.
 *
 * <p>The route decision is derived from typed claims. No caller-supplied
 * boolean can promote a capability without evidence, and a reduced contract
 * being defined must not be confused with an operational runtime being ready.
 */
public record TushareTechnicalQualification(
        RouteDecision routeDecision,
        TechnicalClaim rawDailyClaim,
        TechnicalClaim adjustmentFactorClaim,
        TechnicalClaim calendarClaim,
        Map<CorporateActionType, TechnicalClaim> corporateActionClaims,
        TechnicalClaim stableActionIdClaim,
        TechnicalClaim factorActionRelationshipClaim,
        TechnicalClaim providerRevisionClaim,
        TechnicalClaim historicalVersionsClaim,
        TechnicalClaim permanentSecurityIdentityClaim,
        TechnicalClaim qfqFormulaClaim,
        TechnicalClaim qfqOperationalRuntimeClaim,
        TechnicalClaim fullHistoryDailyExactClaim,
        TechnicalClaim providerPitClaim,
        TechnicalClaim forwardSystemKnowledgePitClaim,
        TechnicalClaim safetyBoundaryClaim,
        TechnicalClaim endpointRateLimitClaim,
        TechnicalClaim endpointRateLimitEnforcementClaim,
        EndpointRateLimitQualification endpointRateLimitQualification,
        Set<TechnicalBlocker> blockers,
        Set<QfqOperationalBlocker> qfqOperationalBlockers,
        Set<EndpointRateLimitBlocker> endpointRateLimitBlockers,
        boolean fullTechnicalContractReady,
        boolean reducedResearchContractReady,
        boolean reducedResearchRuntimeReady,
        QfqCalculationMode qfqCalculationMode,
        QfqAnchorSemantics qfqAnchorSemantics
) {

    public static final int GENERAL_2000_POINT_RATE_LIMIT_PER_MINUTE = 200;
    public static final int GENERAL_2000_POINT_DAILY_LIMIT_PER_API = 100_000;
    public static final int STOCK_BASIC_OFFICIAL_RATE_LIMIT_PER_MINUTE = 50;
    public static final int DAILY_OFFICIAL_RATE_LIMIT_PER_MINUTE = 500;

    public TushareTechnicalQualification {
        routeDecision = Objects.requireNonNull(
                routeDecision, "routeDecision");
        rawDailyClaim = required(rawDailyClaim, "rawDailyClaim");
        adjustmentFactorClaim = required(
                adjustmentFactorClaim, "adjustmentFactorClaim");
        calendarClaim = required(calendarClaim, "calendarClaim");
        corporateActionClaims = copyActionClaims(corporateActionClaims);
        stableActionIdClaim = required(
                stableActionIdClaim, "stableActionIdClaim");
        factorActionRelationshipClaim = required(
                factorActionRelationshipClaim,
                "factorActionRelationshipClaim");
        providerRevisionClaim = required(
                providerRevisionClaim, "providerRevisionClaim");
        historicalVersionsClaim = required(
                historicalVersionsClaim, "historicalVersionsClaim");
        permanentSecurityIdentityClaim = required(
                permanentSecurityIdentityClaim,
                "permanentSecurityIdentityClaim");
        qfqFormulaClaim = required(qfqFormulaClaim, "qfqFormulaClaim");
        qfqOperationalRuntimeClaim = required(
                qfqOperationalRuntimeClaim,
                "qfqOperationalRuntimeClaim");
        fullHistoryDailyExactClaim = required(
                fullHistoryDailyExactClaim,
                "fullHistoryDailyExactClaim");
        providerPitClaim = required(
                providerPitClaim, "providerPitClaim");
        forwardSystemKnowledgePitClaim = required(
                forwardSystemKnowledgePitClaim,
                "forwardSystemKnowledgePitClaim");
        safetyBoundaryClaim = required(
                safetyBoundaryClaim, "safetyBoundaryClaim");
        endpointRateLimitClaim = required(
                endpointRateLimitClaim, "endpointRateLimitClaim");
        endpointRateLimitEnforcementClaim = required(
                endpointRateLimitEnforcementClaim,
                "endpointRateLimitEnforcementClaim");
        endpointRateLimitQualification = Objects.requireNonNull(
                endpointRateLimitQualification,
                "endpointRateLimitQualification");
        blockers = Set.copyOf(Objects.requireNonNull(
                blockers, "blockers"));
        qfqOperationalBlockers = Set.copyOf(Objects.requireNonNull(
                qfqOperationalBlockers, "qfqOperationalBlockers"));
        endpointRateLimitBlockers = Set.copyOf(Objects.requireNonNull(
                endpointRateLimitBlockers, "endpointRateLimitBlockers"));
        qfqCalculationMode = Objects.requireNonNull(
                qfqCalculationMode, "qfqCalculationMode");
        qfqAnchorSemantics = Objects.requireNonNull(
                qfqAnchorSemantics, "qfqAnchorSemantics");

        validateEndpointRateLimitState(
                endpointRateLimitQualification,
                endpointRateLimitClaim);

        Set<QfqOperationalBlocker> expectedQfqBlockers =
                expectedQfqOperationalBlockers(
                        qfqOperationalRuntimeClaim);
        if (!qfqOperationalBlockers.equals(expectedQfqBlockers)) {
            throw new IllegalArgumentException(
                    "qfqOperationalBlockers contradict runtime claim");
        }
        Set<EndpointRateLimitBlocker> expectedEndpointBlockers =
                expectedEndpointRateLimitBlockers(
                        endpointRateLimitQualification,
                        endpointRateLimitEnforcementClaim);
        if (!endpointRateLimitBlockers.equals(
                expectedEndpointBlockers)) {
            throw new IllegalArgumentException(
                    "endpointRateLimitBlockers contradict claims");
        }

        Set<TechnicalBlocker> expectedBlockers = expectedBlockers(
                rawDailyClaim,
                adjustmentFactorClaim,
                calendarClaim,
                corporateActionClaims,
                stableActionIdClaim,
                factorActionRelationshipClaim,
                providerRevisionClaim,
                historicalVersionsClaim,
                permanentSecurityIdentityClaim,
                fullHistoryDailyExactClaim,
                providerPitClaim,
                forwardSystemKnowledgePitClaim,
                safetyBoundaryClaim,
                qfqOperationalRuntimeClaim,
                endpointRateLimitQualification,
                endpointRateLimitEnforcementClaim);
        if (!blockers.equals(expectedBlockers)) {
            throw new IllegalArgumentException(
                    "blockers contradict evidence claims");
        }

        RouteDecision expectedRoute = expectedRoute(
                rawDailyClaim,
                adjustmentFactorClaim,
                calendarClaim,
                corporateActionClaims,
                stableActionIdClaim,
                factorActionRelationshipClaim,
                providerRevisionClaim,
                historicalVersionsClaim,
                permanentSecurityIdentityClaim,
                qfqFormulaClaim,
                qfqOperationalRuntimeClaim,
                fullHistoryDailyExactClaim,
                providerPitClaim,
                forwardSystemKnowledgePitClaim,
                safetyBoundaryClaim,
                endpointRateLimitQualification,
                endpointRateLimitEnforcementClaim,
                expectedBlockers);
        if (routeDecision != expectedRoute) {
            throw new IllegalArgumentException(
                    "routeDecision contradicts evidence claims");
        }
        boolean expectedFull =
                expectedRoute == RouteDecision.FULL_F1_BUILDABLE;
        boolean expectedReduced =
                expectedRoute == RouteDecision.REDUCED_RESEARCH_ONLY;
        boolean expectedRuntime = expectedReduced
                && qfqOperationalRuntimeClaim.verified()
                && endpointRateLimitQualification
                == EndpointRateLimitQualification.VERIFIED_CONSISTENT
                && endpointRateLimitEnforcementClaim.verified()
                && expectedQfqBlockers.isEmpty()
                && expectedEndpointBlockers.isEmpty();
        if (fullTechnicalContractReady != expectedFull) {
            throw new IllegalArgumentException(
                    "fullTechnicalContractReady contradicts claims");
        }
        if (reducedResearchContractReady != expectedReduced) {
            throw new IllegalArgumentException(
                    "reducedResearchContractReady contradicts claims");
        }
        if (reducedResearchRuntimeReady != expectedRuntime) {
            throw new IllegalArgumentException(
                    "reducedResearchRuntimeReady contradicts claims");
        }
        if (expectedFull && !blockers.isEmpty()) {
            throw new IllegalArgumentException(
                    "FULL_F1_BUILDABLE cannot contain blockers");
        }
    }

    /**
     * Current assessment based only on the accepted Tushare official evidence
     * and bounded technical results recorded by F1A/F1B.
     */
    public static TushareTechnicalQualification
    current2000PointAssessment() {
        Map<CorporateActionType, TechnicalClaim> actions =
                new EnumMap<>(CorporateActionType.class);
        actions.put(CorporateActionType.CASH_DIVIDEND, claim(
                QualificationStatus.PARTIAL,
                "TS-007", "TS-PB-009", "TS-PB-010", "TS-F1A-002"));
        actions.put(CorporateActionType.STOCK_DIVIDEND, claim(
                QualificationStatus.PARTIAL,
                "TS-007", "TS-PB-009", "TS-PB-010", "TS-F1A-002"));
        actions.put(CorporateActionType.CAPITALIZATION, claim(
                QualificationStatus.PARTIAL,
                "TS-007", "TS-PB-009", "TS-PB-010", "TS-F1A-002"));
        actions.put(CorporateActionType.RIGHTS_ISSUE, claim(
                QualificationStatus.NOT_SUPPORTED, "TS-007"));
        actions.put(CorporateActionType.SPLIT, claim(
                QualificationStatus.NOT_SUPPORTED, "TS-007"));
        actions.put(CorporateActionType.REVERSE_SPLIT, claim(
                QualificationStatus.NOT_SUPPORTED, "TS-007"));
        actions.put(CorporateActionType.CORRECTION, claim(
                QualificationStatus.NOT_SUPPORTED, "TS-007"));
        actions.put(CorporateActionType.WITHDRAWAL, claim(
                QualificationStatus.NOT_SUPPORTED, "TS-007"));

        return assess(new AssessmentInput(
                claim(QualificationStatus.VERIFIED,
                        "TS-004", "TS-PB-005", "TS-PB-006",
                        "TS-F1A-001"),
                claim(QualificationStatus.VERIFIED,
                        "TS-005", "TS-PB-007", "TS-PB-008",
                        "TS-F1A-001"),
                claim(QualificationStatus.VERIFIED,
                        "TS-006", "TS-PB-003", "TS-PB-004",
                        "TS-F1A-001"),
                actions,
                claim(QualificationStatus.NOT_SUPPORTED, "TS-007"),
                claim(QualificationStatus.UNVERIFIED,
                        "TS-005", "TS-007"),
                claim(QualificationStatus.NOT_SUPPORTED,
                        "TS-004", "TS-005", "TS-006", "TS-007",
                        "TS-021"),
                claim(QualificationStatus.NOT_SUPPORTED, "TS-021"),
                claim(QualificationStatus.PARTIAL,
                        "TS-009", "TS-018", "TS-019", "TS-020",
                        "TS-PB-001", "TS-PB-002", "TS-F1A-002"),
                claim(QualificationStatus.VERIFIED,
                        "TS-005", "TS-008", "JAVA-QFQ-GOLDEN-V1"),
                claim(QualificationStatus.PARTIAL,
                        "JAVA-QFQ-AS-OF-ENGINE-V1",
                        "JAVA-QFQ-GOLDEN-V1"),
                claim(QualificationStatus.UNVERIFIED,
                        "TS-004", "TS-005", "TS-PB-005", "TS-PB-006",
                        "TS-PB-007", "TS-PB-008"),
                claim(QualificationStatus.NOT_SUPPORTED,
                        "TS-004", "TS-005", "TS-006", "TS-007",
                        "TS-021"),
                claim(QualificationStatus.VERIFIED,
                        "TS-F1A-SYSTEM-KNOWLEDGE-CAPTURE"),
                claim(QualificationStatus.VERIFIED,
                        "TS-F1A-MANUAL-BOUNDED-SAFETY"),
                claim(QualificationStatus.PARTIAL,
                        "TS-003", "TS-004", "TS-009"),
                claim(QualificationStatus.PARTIAL,
                        "JAVA-F1A-PROCESS-RATE-LIMITER-V1"),
                EndpointRateLimitQualification
                        .PARTIAL_CONFLICT_IDENTIFIED,
                QfqCalculationMode.RAW_FACTOR_END_DATE_ANCHORED,
                QfqAnchorSemantics.REQUESTED_END_DATE_FACTOR));
    }

    public static TushareTechnicalQualification assess(
            AssessmentInput input
    ) {
        Objects.requireNonNull(input, "input");
        Set<QfqOperationalBlocker> qfqBlockers =
                expectedQfqOperationalBlockers(
                        input.qfqOperationalRuntimeClaim());
        Set<EndpointRateLimitBlocker> endpointBlockers =
                expectedEndpointRateLimitBlockers(
                        input.endpointRateLimitQualification(),
                        input.endpointRateLimitEnforcementClaim());
        Set<TechnicalBlocker> blockers = expectedBlockers(
                input.rawDailyClaim(),
                input.adjustmentFactorClaim(),
                input.calendarClaim(),
                input.corporateActionClaims(),
                input.stableActionIdClaim(),
                input.factorActionRelationshipClaim(),
                input.providerRevisionClaim(),
                input.historicalVersionsClaim(),
                input.permanentSecurityIdentityClaim(),
                input.fullHistoryDailyExactClaim(),
                input.providerPitClaim(),
                input.forwardSystemKnowledgePitClaim(),
                input.safetyBoundaryClaim(),
                input.qfqOperationalRuntimeClaim(),
                input.endpointRateLimitQualification(),
                input.endpointRateLimitEnforcementClaim());
        RouteDecision decision = expectedRoute(
                input.rawDailyClaim(),
                input.adjustmentFactorClaim(),
                input.calendarClaim(),
                input.corporateActionClaims(),
                input.stableActionIdClaim(),
                input.factorActionRelationshipClaim(),
                input.providerRevisionClaim(),
                input.historicalVersionsClaim(),
                input.permanentSecurityIdentityClaim(),
                input.qfqFormulaClaim(),
                input.qfqOperationalRuntimeClaim(),
                input.fullHistoryDailyExactClaim(),
                input.providerPitClaim(),
                input.forwardSystemKnowledgePitClaim(),
                input.safetyBoundaryClaim(),
                input.endpointRateLimitQualification(),
                input.endpointRateLimitEnforcementClaim(),
                blockers);
        boolean reduced =
                decision == RouteDecision.REDUCED_RESEARCH_ONLY;
        boolean runtimeReady = reduced
                && input.qfqOperationalRuntimeClaim().verified()
                && input.endpointRateLimitQualification()
                == EndpointRateLimitQualification.VERIFIED_CONSISTENT
                && input.endpointRateLimitEnforcementClaim().verified()
                && qfqBlockers.isEmpty()
                && endpointBlockers.isEmpty();
        return new TushareTechnicalQualification(
                decision,
                input.rawDailyClaim(),
                input.adjustmentFactorClaim(),
                input.calendarClaim(),
                input.corporateActionClaims(),
                input.stableActionIdClaim(),
                input.factorActionRelationshipClaim(),
                input.providerRevisionClaim(),
                input.historicalVersionsClaim(),
                input.permanentSecurityIdentityClaim(),
                input.qfqFormulaClaim(),
                input.qfqOperationalRuntimeClaim(),
                input.fullHistoryDailyExactClaim(),
                input.providerPitClaim(),
                input.forwardSystemKnowledgePitClaim(),
                input.safetyBoundaryClaim(),
                input.endpointRateLimitClaim(),
                input.endpointRateLimitEnforcementClaim(),
                input.endpointRateLimitQualification(),
                blockers,
                qfqBlockers,
                endpointBlockers,
                decision == RouteDecision.FULL_F1_BUILDABLE,
                reduced,
                runtimeReady,
                input.qfqCalculationMode(),
                input.qfqAnchorSemantics());
    }

    public QualificationStatus rawDailyQualification() {
        return rawDailyClaim.status();
    }

    public QualificationStatus adjustmentFactorQualification() {
        return adjustmentFactorClaim.status();
    }

    public QualificationStatus calendarQualification() {
        return calendarClaim.status();
    }

    public QualificationStatus corporateActionQualification() {
        Set<QualificationStatus> statuses = corporateActionClaims.values()
                .stream()
                .map(TechnicalClaim::status)
                .collect(java.util.stream.Collectors.toSet());
        if (statuses.size() == 1
                && statuses.contains(QualificationStatus.VERIFIED)) {
            return QualificationStatus.VERIFIED;
        }
        if (statuses.contains(QualificationStatus.VERIFIED)
                || statuses.contains(QualificationStatus.PARTIAL)) {
            return QualificationStatus.PARTIAL;
        }
        if (statuses.size() == 1
                && statuses.contains(QualificationStatus.NOT_SUPPORTED)) {
            return QualificationStatus.NOT_SUPPORTED;
        }
        if (statuses.contains(QualificationStatus.UNAVAILABLE)) {
            return QualificationStatus.UNAVAILABLE;
        }
        return QualificationStatus.UNVERIFIED;
    }

    public QualificationStatus revisionQualification() {
        return providerRevisionClaim.status();
    }

    public QualificationStatus historicalVersionQualification() {
        return historicalVersionsClaim.status();
    }

    public QualificationStatus securityIdentityQualification() {
        return permanentSecurityIdentityClaim.status();
    }

    public QualificationStatus qfqQualification() {
        return qfqFormulaClaim.status();
    }

    public QualificationStatus qfqFormulaQualification() {
        return qfqFormulaClaim.status();
    }

    public QualificationStatus qfqOperationalRuntimeQualification() {
        return qfqOperationalRuntimeClaim.status();
    }

    public QualificationStatus fullHistoryDailyExactQualification() {
        return fullHistoryDailyExactClaim.status();
    }

    public QualificationStatus providerPitQualification() {
        return providerPitClaim.status();
    }

    public Map<CorporateActionType, QualificationStatus>
    corporateActionCoverage() {
        Map<CorporateActionType, QualificationStatus> result =
                new EnumMap<>(CorporateActionType.class);
        corporateActionClaims.forEach(
                (type, claim) -> result.put(type, claim.status()));
        return Map.copyOf(result);
    }

    public Set<String> evidenceIds() {
        Set<String> result = new LinkedHashSet<>();
        allClaims().forEach(claim -> result.addAll(claim.evidenceIds()));
        return Set.copyOf(result);
    }

    public Set<String> endpointRateLimitEvidenceIds() {
        Set<String> result = new LinkedHashSet<>(
                endpointRateLimitClaim.evidenceIds());
        result.addAll(endpointRateLimitEnforcementClaim.evidenceIds());
        return Set.copyOf(result);
    }

    public boolean corporateActionLineageComplete() {
        return allActionsIndependentlyVerified(corporateActionClaims)
                && stableActionIdClaim.verified()
                && factorActionRelationshipClaim.verified();
    }

    public boolean permanentSecurityIdentityVerified() {
        return permanentSecurityIdentityClaim.verified();
    }

    public boolean providerRevisionAvailable() {
        return providerRevisionClaim.verified();
    }

    public boolean historicalVersionsQueryable() {
        return historicalVersionsClaim.verified();
    }

    public boolean forwardSystemKnowledgePitBuildable() {
        return forwardSystemKnowledgePitClaim.verified();
    }

    public boolean endpointSpecificRateLimitEnforced() {
        return endpointRateLimitEnforcementClaim.verified();
    }

    private Set<TechnicalClaim> allClaims() {
        Set<TechnicalClaim> result = new LinkedHashSet<>();
        result.add(rawDailyClaim);
        result.add(adjustmentFactorClaim);
        result.add(calendarClaim);
        result.addAll(corporateActionClaims.values());
        result.add(stableActionIdClaim);
        result.add(factorActionRelationshipClaim);
        result.add(providerRevisionClaim);
        result.add(historicalVersionsClaim);
        result.add(permanentSecurityIdentityClaim);
        result.add(qfqFormulaClaim);
        result.add(qfqOperationalRuntimeClaim);
        result.add(fullHistoryDailyExactClaim);
        result.add(providerPitClaim);
        result.add(forwardSystemKnowledgePitClaim);
        result.add(safetyBoundaryClaim);
        result.add(endpointRateLimitClaim);
        result.add(endpointRateLimitEnforcementClaim);
        return Set.copyOf(result);
    }

    private static RouteDecision expectedRoute(
            TechnicalClaim raw,
            TechnicalClaim factor,
            TechnicalClaim calendar,
            Map<CorporateActionType, TechnicalClaim> actions,
            TechnicalClaim stableActionId,
            TechnicalClaim factorActionRelationship,
            TechnicalClaim revision,
            TechnicalClaim versions,
            TechnicalClaim identity,
            TechnicalClaim qfqFormula,
            TechnicalClaim qfqRuntime,
            TechnicalClaim dailyExact,
            TechnicalClaim providerPit,
            TechnicalClaim forwardPit,
            TechnicalClaim safety,
            EndpointRateLimitQualification endpointRateLimitQualification,
            TechnicalClaim endpointRateLimitEnforcement,
            Set<TechnicalBlocker> blockers
    ) {
        boolean core = raw.verified()
                && factor.verified()
                && calendar.verified()
                && qfqFormula.verified()
                && forwardPit.verified()
                && safety.verified();
        boolean full = core
                && allActionsIndependentlyVerified(actions)
                && stableActionId.verified()
                && factorActionRelationship.verified()
                && revision.verified()
                && versions.verified()
                && identity.verified()
                && qfqRuntime.verified()
                && dailyExact.verified()
                && providerPit.verified()
                && endpointRateLimitQualification
                == EndpointRateLimitQualification.VERIFIED_CONSISTENT
                && endpointRateLimitEnforcement.verified()
                && blockers.isEmpty();
        if (full) {
            return RouteDecision.FULL_F1_BUILDABLE;
        }
        if (core) {
            return RouteDecision.REDUCED_RESEARCH_ONLY;
        }
        return RouteDecision.PROVIDER_ROUTE_REJECTED;
    }

    private static Set<TechnicalBlocker> expectedBlockers(
            TechnicalClaim raw,
            TechnicalClaim factor,
            TechnicalClaim calendar,
            Map<CorporateActionType, TechnicalClaim> actions,
            TechnicalClaim stableActionId,
            TechnicalClaim factorActionRelationship,
            TechnicalClaim revision,
            TechnicalClaim versions,
            TechnicalClaim identity,
            TechnicalClaim dailyExact,
            TechnicalClaim providerPit,
            TechnicalClaim forwardPit,
            TechnicalClaim safety,
            TechnicalClaim qfqRuntime,
            EndpointRateLimitQualification endpointRateLimitQualification,
            TechnicalClaim endpointRateLimitEnforcement
    ) {
        Set<TechnicalBlocker> values =
                EnumSet.noneOf(TechnicalBlocker.class);
        if (!raw.verified() || !factor.verified()
                || !calendar.verified()) {
            values.add(TechnicalBlocker.CORE_FACT_CONTRACT_INCOMPLETE);
        }
        if (!allActionsIndependentlyVerified(actions)
                || !stableActionId.verified()
                || !factorActionRelationship.verified()) {
            values.add(
                    TechnicalBlocker.CORPORATE_ACTION_LINEAGE_INCOMPLETE);
        }
        if (!stableActionId.verified()) {
            values.add(TechnicalBlocker.STABLE_ACTION_ID_UNAVAILABLE);
        }
        if (!factorActionRelationship.verified()) {
            values.add(
                    TechnicalBlocker.FACTOR_ACTION_RELATION_UNVERIFIED);
        }
        if (!revision.verified()) {
            values.add(TechnicalBlocker.PROVIDER_REVISION_UNAVAILABLE);
        }
        if (!versions.verified()) {
            values.add(
                    TechnicalBlocker.HISTORICAL_VERSIONS_NOT_QUERYABLE);
        }
        if (!identity.verified()) {
            values.add(
                    TechnicalBlocker.PERMANENT_SECURITY_IDENTITY_UNVERIFIED);
        }
        if (!dailyExact.verified()) {
            values.add(
                    TechnicalBlocker.FULL_HISTORY_DAILY_EXACT_UNVERIFIED);
        }
        if (!providerPit.verified()) {
            values.add(TechnicalBlocker.PROVIDER_PIT_UNAVAILABLE);
        }
        if (!forwardPit.verified()) {
            values.add(
                    TechnicalBlocker.FORWARD_SYSTEM_KNOWLEDGE_PIT_UNAVAILABLE);
        }
        if (!safety.verified()) {
            values.add(
                    TechnicalBlocker.SAFETY_BOUNDARY_NOT_IMPLEMENTABLE);
        }
        if (!qfqRuntime.verified()) {
            values.add(
                    TechnicalBlocker.QFQ_OPERATIONAL_RUNTIME_INCOMPLETE);
        }
        if (endpointRateLimitQualification
                != EndpointRateLimitQualification.VERIFIED_CONSISTENT) {
            values.add(
                    TechnicalBlocker.ENDPOINT_RATE_LIMIT_EVIDENCE_CONFLICT);
        }
        if (!endpointRateLimitEnforcement.verified()) {
            values.add(
                    TechnicalBlocker.ENDPOINT_SPECIFIC_RATE_LIMIT_NOT_ENFORCED);
        }
        return Set.copyOf(values);
    }

    private static Set<QfqOperationalBlocker>
    expectedQfqOperationalBlockers(TechnicalClaim runtimeClaim) {
        if (runtimeClaim.verified()) {
            return Set.of();
        }
        return Set.of(QfqOperationalBlocker
                .EXISTING_QFQ_ENGINE_REQUIRES_CORPORATE_ACTION_LINEAGE);
    }

    private static Set<EndpointRateLimitBlocker>
    expectedEndpointRateLimitBlockers(
            EndpointRateLimitQualification qualification,
            TechnicalClaim enforcementClaim
    ) {
        Set<EndpointRateLimitBlocker> values =
                EnumSet.noneOf(EndpointRateLimitBlocker.class);
        if (qualification
                == EndpointRateLimitQualification
                .PARTIAL_CONFLICT_IDENTIFIED) {
            values.add(EndpointRateLimitBlocker
                    .GENERAL_AND_ENDPOINT_LIMITS_REQUIRE_CONSERVATIVE_MINIMUM);
        } else if (qualification
                == EndpointRateLimitQualification.UNVERIFIED) {
            values.add(EndpointRateLimitBlocker
                    .OFFICIAL_ENDPOINT_LIMITS_UNVERIFIED);
        }
        if (!enforcementClaim.verified()) {
            values.add(EndpointRateLimitBlocker
                    .ENDPOINT_SPECIFIC_LIMITER_NOT_IMPLEMENTED);
        }
        return Set.copyOf(values);
    }

    private static boolean allActionsIndependentlyVerified(
            Map<CorporateActionType, TechnicalClaim> claims
    ) {
        if (!claims.keySet().equals(
                EnumSet.allOf(CorporateActionType.class))) {
            return false;
        }
        if (claims.values().stream().anyMatch(
                claim -> !claim.verified()
                        || claim.evidenceIds().isEmpty())) {
            return false;
        }
        for (Map.Entry<CorporateActionType, TechnicalClaim> entry
                : claims.entrySet()) {
            Set<String> otherEvidence = new LinkedHashSet<>();
            claims.forEach((type, claim) -> {
                if (type != entry.getKey()) {
                    otherEvidence.addAll(claim.evidenceIds());
                }
            });
            if (entry.getValue().evidenceIds().stream()
                    .allMatch(otherEvidence::contains)) {
                return false;
            }
        }
        return true;
    }

    private static void validateEndpointRateLimitState(
            EndpointRateLimitQualification qualification,
            TechnicalClaim claim
    ) {
        QualificationStatus expectedStatus = switch (qualification) {
            case VERIFIED_CONSISTENT -> QualificationStatus.VERIFIED;
            case PARTIAL_CONFLICT_IDENTIFIED ->
                    QualificationStatus.PARTIAL;
            case UNVERIFIED -> QualificationStatus.UNVERIFIED;
        };
        if (claim.status() != expectedStatus) {
            throw new IllegalArgumentException(
                    "endpointRateLimitQualification contradicts claim");
        }
    }

    private static Map<CorporateActionType, TechnicalClaim>
    copyActionClaims(
            Map<CorporateActionType, TechnicalClaim> source
    ) {
        Objects.requireNonNull(source, "corporateActionClaims");
        if (!source.keySet().equals(
                EnumSet.allOf(CorporateActionType.class))) {
            throw new IllegalArgumentException(
                    "All corporate-action types require a claim");
        }
        Map<CorporateActionType, TechnicalClaim> result =
                new EnumMap<>(CorporateActionType.class);
        source.forEach((type, claim) -> result.put(
                Objects.requireNonNull(type, "corporateActionType"),
                required(claim, "corporateActionClaim")));
        return Map.copyOf(result);
    }

    private static TechnicalClaim required(
            TechnicalClaim value,
            String name
    ) {
        return Objects.requireNonNull(value, name);
    }

    private static TechnicalClaim claim(
            QualificationStatus status,
            String... evidenceIds
    ) {
        return new TechnicalClaim(status, Set.of(evidenceIds));
    }

    public record TechnicalClaim(
            QualificationStatus status,
            Set<String> evidenceIds
    ) {
        public TechnicalClaim {
            status = Objects.requireNonNull(status, "status");
            evidenceIds = Set.copyOf(Objects.requireNonNull(
                    evidenceIds, "evidenceIds"));
            if (evidenceIds.stream().anyMatch(
                    id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException(
                        "evidenceIds must not contain blanks");
            }
            if ((status == QualificationStatus.VERIFIED
                    || status == QualificationStatus.PARTIAL)
                    && evidenceIds.isEmpty()) {
                throw new IllegalArgumentException(
                        status + " claim requires evidence");
            }
        }

        public boolean verified() {
            return status == QualificationStatus.VERIFIED
                    && !evidenceIds.isEmpty();
        }
    }

    public record AssessmentInput(
            TechnicalClaim rawDailyClaim,
            TechnicalClaim adjustmentFactorClaim,
            TechnicalClaim calendarClaim,
            Map<CorporateActionType, TechnicalClaim>
                    corporateActionClaims,
            TechnicalClaim stableActionIdClaim,
            TechnicalClaim factorActionRelationshipClaim,
            TechnicalClaim providerRevisionClaim,
            TechnicalClaim historicalVersionsClaim,
            TechnicalClaim permanentSecurityIdentityClaim,
            TechnicalClaim qfqFormulaClaim,
            TechnicalClaim qfqOperationalRuntimeClaim,
            TechnicalClaim fullHistoryDailyExactClaim,
            TechnicalClaim providerPitClaim,
            TechnicalClaim forwardSystemKnowledgePitClaim,
            TechnicalClaim safetyBoundaryClaim,
            TechnicalClaim endpointRateLimitClaim,
            TechnicalClaim endpointRateLimitEnforcementClaim,
            EndpointRateLimitQualification endpointRateLimitQualification,
            QfqCalculationMode qfqCalculationMode,
            QfqAnchorSemantics qfqAnchorSemantics
    ) {
        public AssessmentInput {
            rawDailyClaim = required(rawDailyClaim, "rawDailyClaim");
            adjustmentFactorClaim = required(
                    adjustmentFactorClaim, "adjustmentFactorClaim");
            calendarClaim = required(calendarClaim, "calendarClaim");
            corporateActionClaims = copyActionClaims(
                    corporateActionClaims);
            stableActionIdClaim = required(
                    stableActionIdClaim, "stableActionIdClaim");
            factorActionRelationshipClaim = required(
                    factorActionRelationshipClaim,
                    "factorActionRelationshipClaim");
            providerRevisionClaim = required(
                    providerRevisionClaim, "providerRevisionClaim");
            historicalVersionsClaim = required(
                    historicalVersionsClaim, "historicalVersionsClaim");
            permanentSecurityIdentityClaim = required(
                    permanentSecurityIdentityClaim,
                    "permanentSecurityIdentityClaim");
            qfqFormulaClaim = required(
                    qfqFormulaClaim, "qfqFormulaClaim");
            qfqOperationalRuntimeClaim = required(
                    qfqOperationalRuntimeClaim,
                    "qfqOperationalRuntimeClaim");
            fullHistoryDailyExactClaim = required(
                    fullHistoryDailyExactClaim,
                    "fullHistoryDailyExactClaim");
            providerPitClaim = required(
                    providerPitClaim, "providerPitClaim");
            forwardSystemKnowledgePitClaim = required(
                    forwardSystemKnowledgePitClaim,
                    "forwardSystemKnowledgePitClaim");
            safetyBoundaryClaim = required(
                    safetyBoundaryClaim, "safetyBoundaryClaim");
            endpointRateLimitClaim = required(
                    endpointRateLimitClaim, "endpointRateLimitClaim");
            endpointRateLimitEnforcementClaim = required(
                    endpointRateLimitEnforcementClaim,
                    "endpointRateLimitEnforcementClaim");
            endpointRateLimitQualification = Objects.requireNonNull(
                    endpointRateLimitQualification,
                    "endpointRateLimitQualification");
            qfqCalculationMode = Objects.requireNonNull(
                    qfqCalculationMode, "qfqCalculationMode");
            qfqAnchorSemantics = Objects.requireNonNull(
                    qfqAnchorSemantics, "qfqAnchorSemantics");
            validateEndpointRateLimitState(
                    endpointRateLimitQualification,
                    endpointRateLimitClaim);
        }
    }

    public enum RouteDecision {
        FULL_F1_BUILDABLE,
        REDUCED_RESEARCH_ONLY,
        PROVIDER_ROUTE_REJECTED
    }

    public enum QualificationStatus {
        VERIFIED,
        PARTIAL,
        UNAVAILABLE,
        UNVERIFIED,
        NOT_SUPPORTED
    }

    public enum CorporateActionType {
        CASH_DIVIDEND,
        STOCK_DIVIDEND,
        CAPITALIZATION,
        RIGHTS_ISSUE,
        SPLIT,
        REVERSE_SPLIT,
        CORRECTION,
        WITHDRAWAL
    }

    public enum TechnicalBlocker {
        CORE_FACT_CONTRACT_INCOMPLETE,
        CORPORATE_ACTION_LINEAGE_INCOMPLETE,
        STABLE_ACTION_ID_UNAVAILABLE,
        FACTOR_ACTION_RELATION_UNVERIFIED,
        PROVIDER_REVISION_UNAVAILABLE,
        HISTORICAL_VERSIONS_NOT_QUERYABLE,
        PERMANENT_SECURITY_IDENTITY_UNVERIFIED,
        FULL_HISTORY_DAILY_EXACT_UNVERIFIED,
        PROVIDER_PIT_UNAVAILABLE,
        FORWARD_SYSTEM_KNOWLEDGE_PIT_UNAVAILABLE,
        SAFETY_BOUNDARY_NOT_IMPLEMENTABLE,
        QFQ_OPERATIONAL_RUNTIME_INCOMPLETE,
        ENDPOINT_RATE_LIMIT_EVIDENCE_CONFLICT,
        ENDPOINT_SPECIFIC_RATE_LIMIT_NOT_ENFORCED
    }

    public enum QfqOperationalBlocker {
        EXISTING_QFQ_ENGINE_REQUIRES_CORPORATE_ACTION_LINEAGE
    }

    public enum EndpointRateLimitBlocker {
        GENERAL_AND_ENDPOINT_LIMITS_REQUIRE_CONSERVATIVE_MINIMUM,
        OFFICIAL_ENDPOINT_LIMITS_UNVERIFIED,
        ENDPOINT_SPECIFIC_LIMITER_NOT_IMPLEMENTED
    }

    public enum EndpointRateLimitQualification {
        VERIFIED_CONSISTENT,
        PARTIAL_CONFLICT_IDENTIFIED,
        UNVERIFIED
    }

    public enum QfqCalculationMode {
        RAW_FACTOR_END_DATE_ANCHORED
    }

    public enum QfqAnchorSemantics {
        REQUESTED_END_DATE_FACTOR
    }
}
