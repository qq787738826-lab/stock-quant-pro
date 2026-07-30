package com.stockquant.server.agent.marketfacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Typed, evidence-backed qualification for the Tushare 2000-point route.
 *
 * <p>This model deliberately separates a reduced personal-research contract
 * from the complete four-fact F1 contract. Provider revision, complete
 * corporate-action lineage and permanent instrument identity must never be
 * inferred from ordinary API fields or from the local first-observation time.
 */
public record TushareTechnicalQualification(
        RouteDecision routeDecision,
        QualificationStatus rawDailyQualification,
        QualificationStatus adjustmentFactorQualification,
        QualificationStatus calendarQualification,
        QualificationStatus corporateActionQualification,
        QualificationStatus revisionQualification,
        QualificationStatus historicalVersionQualification,
        QualificationStatus securityIdentityQualification,
        QualificationStatus qfqQualification,
        QualificationStatus fullHistoryDailyExactQualification,
        QualificationStatus providerPitQualification,
        Map<CorporateActionType, QualificationStatus> corporateActionCoverage,
        Set<String> evidenceIds,
        Set<TechnicalBlocker> blockers,
        boolean fullTechnicalContractReady,
        boolean reducedResearchContractReady,
        boolean forwardSystemKnowledgePitBuildable,
        QfqCalculationMode qfqCalculationMode,
        QfqAnchorSemantics qfqAnchorSemantics,
        boolean corporateActionLineageComplete,
        boolean permanentSecurityIdentityVerified,
        boolean providerRevisionAvailable,
        boolean historicalVersionsQueryable
) {

    public static final String PROVIDER_CODE = "TUSHARE_PRO";
    private static final int DIVISION_SCALE = 16;
    private static final int PRICE_SCALE = 4;

    public TushareTechnicalQualification {
        routeDecision = Objects.requireNonNull(routeDecision, "routeDecision");
        rawDailyQualification = required(
                rawDailyQualification, "rawDailyQualification");
        adjustmentFactorQualification = required(
                adjustmentFactorQualification,
                "adjustmentFactorQualification");
        calendarQualification = required(
                calendarQualification, "calendarQualification");
        corporateActionQualification = required(
                corporateActionQualification,
                "corporateActionQualification");
        revisionQualification = required(
                revisionQualification, "revisionQualification");
        historicalVersionQualification = required(
                historicalVersionQualification,
                "historicalVersionQualification");
        securityIdentityQualification = required(
                securityIdentityQualification,
                "securityIdentityQualification");
        qfqQualification = required(qfqQualification, "qfqQualification");
        fullHistoryDailyExactQualification = required(
                fullHistoryDailyExactQualification,
                "fullHistoryDailyExactQualification");
        providerPitQualification = required(
                providerPitQualification, "providerPitQualification");
        corporateActionCoverage = Map.copyOf(
                Objects.requireNonNull(
                        corporateActionCoverage,
                        "corporateActionCoverage"));
        if (!corporateActionCoverage.keySet().equals(
                EnumSet.allOf(CorporateActionType.class))) {
            throw new IllegalArgumentException(
                    "All corporate-action types must be qualified");
        }
        evidenceIds = Set.copyOf(
                Objects.requireNonNull(evidenceIds, "evidenceIds"));
        if (evidenceIds.stream().anyMatch(value ->
                value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                    "evidenceIds must not contain blanks");
        }
        blockers = Set.copyOf(Objects.requireNonNull(blockers, "blockers"));
        qfqCalculationMode = Objects.requireNonNull(
                qfqCalculationMode, "qfqCalculationMode");
        qfqAnchorSemantics = Objects.requireNonNull(
                qfqAnchorSemantics, "qfqAnchorSemantics");
        if (fullTechnicalContractReady
                != (routeDecision == RouteDecision.FULL_F1_BUILDABLE)) {
            throw new IllegalArgumentException(
                    "fullTechnicalContractReady contradicts routeDecision");
        }
        if (reducedResearchContractReady
                != (routeDecision
                == RouteDecision.REDUCED_RESEARCH_ONLY)) {
            throw new IllegalArgumentException(
                    "reducedResearchContractReady contradicts routeDecision");
        }
    }

    /**
     * Current assessment based on official pages TS-003/004/005/006/007/008,
     * TS-009/018/019/020/021 and accepted bounded evidence TS-PB/TS-F1A.
     */
    public static TushareTechnicalQualification current2000PointAssessment() {
        Map<QualificationDimension, Set<String>> evidence =
                new EnumMap<>(QualificationDimension.class);
        evidence.put(QualificationDimension.RAW_DAILY, Set.of(
                "TS-004", "TS-PB-005", "TS-PB-006", "TS-F1A-001"));
        evidence.put(QualificationDimension.ADJUSTMENT_FACTOR, Set.of(
                "TS-005", "TS-PB-007", "TS-PB-008", "TS-F1A-001"));
        evidence.put(QualificationDimension.TRADING_CALENDAR, Set.of(
                "TS-006", "TS-PB-003", "TS-PB-004", "TS-F1A-001"));
        evidence.put(QualificationDimension.CORPORATE_ACTION, Set.of(
                "TS-007", "TS-PB-009", "TS-PB-010", "TS-F1A-002"));
        evidence.put(QualificationDimension.REVISION, Set.of(
                "TS-004", "TS-005", "TS-006", "TS-007", "TS-021"));
        evidence.put(QualificationDimension.HISTORICAL_VERSION, Set.of(
                "TS-021"));
        evidence.put(QualificationDimension.SECURITY_IDENTITY, Set.of(
                "TS-009", "TS-018", "TS-019", "TS-020",
                "TS-PB-001", "TS-PB-002", "TS-F1A-002"));
        evidence.put(QualificationDimension.QFQ, Set.of(
                "TS-005", "TS-008", "JAVA-QFQ-GOLDEN-V1"));
        evidence.put(QualificationDimension.FULL_HISTORY_DAILY_EXACT, Set.of(
                "TS-004", "TS-005", "TS-PB-005", "TS-PB-006",
                "TS-PB-007", "TS-PB-008"));
        evidence.put(QualificationDimension.PROVIDER_PIT, Set.of(
                "TS-004", "TS-005", "TS-006", "TS-007", "TS-021"));

        Map<CorporateActionType, QualificationStatus> actions =
                new EnumMap<>(CorporateActionType.class);
        actions.put(
                CorporateActionType.CASH_DIVIDEND,
                QualificationStatus.PARTIAL);
        actions.put(
                CorporateActionType.STOCK_DIVIDEND,
                QualificationStatus.PARTIAL);
        actions.put(
                CorporateActionType.CAPITALIZATION,
                QualificationStatus.PARTIAL);
        actions.put(
                CorporateActionType.RIGHTS_ISSUE,
                QualificationStatus.NOT_SUPPORTED);
        actions.put(
                CorporateActionType.SPLIT,
                QualificationStatus.NOT_SUPPORTED);
        actions.put(
                CorporateActionType.REVERSE_SPLIT,
                QualificationStatus.NOT_SUPPORTED);
        actions.put(
                CorporateActionType.CORRECTION,
                QualificationStatus.NOT_SUPPORTED);
        actions.put(
                CorporateActionType.WITHDRAWAL,
                QualificationStatus.NOT_SUPPORTED);

        return assess(new AssessmentInput(
                QualificationStatus.VERIFIED,
                QualificationStatus.VERIFIED,
                QualificationStatus.VERIFIED,
                QualificationStatus.PARTIAL,
                QualificationStatus.NOT_SUPPORTED,
                QualificationStatus.NOT_SUPPORTED,
                QualificationStatus.PARTIAL,
                QualificationStatus.VERIFIED,
                QualificationStatus.UNVERIFIED,
                QualificationStatus.NOT_SUPPORTED,
                actions,
                evidence,
                false,
                false,
                true,
                true));
    }

    public static TushareTechnicalQualification assess(
            AssessmentInput input
    ) {
        Objects.requireNonNull(input, "input");
        Map<QualificationDimension, Set<String>> evidence =
                normalizeEvidence(input.evidenceByDimension());

        QualificationStatus raw = evidenceBacked(
                input.rawDailyQualification(),
                QualificationDimension.RAW_DAILY, evidence);
        QualificationStatus factor = evidenceBacked(
                input.adjustmentFactorQualification(),
                QualificationDimension.ADJUSTMENT_FACTOR, evidence);
        QualificationStatus calendar = evidenceBacked(
                input.calendarQualification(),
                QualificationDimension.TRADING_CALENDAR, evidence);
        QualificationStatus action = evidenceBacked(
                input.corporateActionQualification(),
                QualificationDimension.CORPORATE_ACTION, evidence);
        QualificationStatus revision = evidenceBacked(
                input.revisionQualification(),
                QualificationDimension.REVISION, evidence);
        QualificationStatus versions = evidenceBacked(
                input.historicalVersionQualification(),
                QualificationDimension.HISTORICAL_VERSION, evidence);
        QualificationStatus identity = evidenceBacked(
                input.securityIdentityQualification(),
                QualificationDimension.SECURITY_IDENTITY, evidence);
        QualificationStatus qfq = evidenceBacked(
                input.qfqQualification(),
                QualificationDimension.QFQ, evidence);
        QualificationStatus dailyExact = evidenceBacked(
                input.fullHistoryDailyExactQualification(),
                QualificationDimension.FULL_HISTORY_DAILY_EXACT, evidence);
        QualificationStatus providerPit = evidenceBacked(
                input.providerPitQualification(),
                QualificationDimension.PROVIDER_PIT, evidence);

        Map<CorporateActionType, QualificationStatus> actionCoverage =
                normalizeActionCoverage(
                        input.corporateActionCoverage(), evidence);
        boolean actionLineageComplete =
                action == QualificationStatus.VERIFIED
                        && actionCoverage.values().stream().allMatch(
                        value -> value == QualificationStatus.VERIFIED)
                        && input.stableActionIdAvailable()
                        && input.factorActionRelationshipVerified();
        boolean permanentIdentity =
                identity == QualificationStatus.VERIFIED;
        boolean revisionAvailable =
                revision == QualificationStatus.VERIFIED;
        boolean versionsQueryable =
                versions == QualificationStatus.VERIFIED;

        boolean full = raw == QualificationStatus.VERIFIED
                && factor == QualificationStatus.VERIFIED
                && calendar == QualificationStatus.VERIFIED
                && actionLineageComplete
                && revisionAvailable
                && versionsQueryable
                && permanentIdentity
                && qfq == QualificationStatus.VERIFIED
                && dailyExact == QualificationStatus.VERIFIED
                && providerPit == QualificationStatus.VERIFIED
                && input.forwardSystemKnowledgePitBuildable()
                && input.safetyBoundaryImplementable();
        boolean reduced = !full
                && raw == QualificationStatus.VERIFIED
                && factor == QualificationStatus.VERIFIED
                && calendar == QualificationStatus.VERIFIED
                && qfq == QualificationStatus.VERIFIED
                && input.forwardSystemKnowledgePitBuildable()
                && input.safetyBoundaryImplementable();

        RouteDecision decision = full
                ? RouteDecision.FULL_F1_BUILDABLE
                : reduced
                ? RouteDecision.REDUCED_RESEARCH_ONLY
                : RouteDecision.PROVIDER_ROUTE_REJECTED;
        Set<TechnicalBlocker> blockers = blockers(
                raw, factor, calendar, actionLineageComplete,
                input.stableActionIdAvailable(),
                input.factorActionRelationshipVerified(),
                revisionAvailable, versionsQueryable, permanentIdentity,
                dailyExact, providerPit,
                input.forwardSystemKnowledgePitBuildable(),
                input.safetyBoundaryImplementable());
        Set<String> evidenceIds = new LinkedHashSet<>();
        evidence.values().forEach(evidenceIds::addAll);

        return new TushareTechnicalQualification(
                decision,
                raw,
                factor,
                calendar,
                action,
                revision,
                versions,
                identity,
                qfq,
                dailyExact,
                providerPit,
                actionCoverage,
                evidenceIds,
                blockers,
                full,
                reduced,
                input.forwardSystemKnowledgePitBuildable(),
                QfqCalculationMode.RAW_FACTOR_END_DATE_ANCHORED,
                QfqAnchorSemantics.REQUESTED_END_DATE_FACTOR,
                actionLineageComplete,
                permanentIdentity,
                revisionAvailable,
                versionsQueryable);
    }

    /**
     * Deterministic reduced-route calculation. Dividend evidence cannot enter
     * this method and must never be used to derive or repair a factor.
     */
    public static List<ResearchQfqPoint> calculateReducedResearchQfq(
            String rawProviderCode,
            String factorProviderCode,
            List<ResearchRawPrice> rawPrices,
            Map<LocalDate, BigDecimal> factors,
            LocalDate requestedEndDate
    ) {
        if (!PROVIDER_CODE.equals(rawProviderCode)
                || !PROVIDER_CODE.equals(factorProviderCode)
                || !rawProviderCode.equals(factorProviderCode)) {
            throw new IllegalArgumentException(
                    "TUSHARE_QFQ_CROSS_PROVIDER_FORBIDDEN");
        }
        Objects.requireNonNull(rawPrices, "rawPrices");
        Objects.requireNonNull(factors, "factors");
        Objects.requireNonNull(requestedEndDate, "requestedEndDate");
        BigDecimal anchor = factors.get(requestedEndDate);
        requirePositive(anchor, "TUSHARE_QFQ_ANCHOR_FACTOR_UNAVAILABLE");

        Set<LocalDate> dates = new LinkedHashSet<>();
        List<ResearchQfqPoint> result =
                new ArrayList<>(rawPrices.size());
        for (ResearchRawPrice raw : rawPrices) {
            Objects.requireNonNull(raw, "rawPrice");
            if (!dates.add(raw.tradeDate())) {
                throw new IllegalArgumentException(
                        "TUSHARE_QFQ_DUPLICATE_TRADE_DATE");
            }
            BigDecimal factor = factors.get(raw.tradeDate());
            requirePositive(
                    factor, "TUSHARE_QFQ_DAILY_FACTOR_UNAVAILABLE");
            result.add(new ResearchQfqPoint(
                    raw.tradeDate(),
                    price(raw.rawPrice(), factor, anchor)));
        }
        return List.copyOf(result);
    }

    private static BigDecimal price(
            BigDecimal raw,
            BigDecimal factor,
            BigDecimal anchor
    ) {
        if (raw == null || raw.signum() <= 0) {
            throw new IllegalArgumentException(
                    "TUSHARE_QFQ_RAW_PRICE_INVALID");
        }
        requirePositive(factor, "TUSHARE_QFQ_FACTOR_INVALID");
        requirePositive(anchor, "TUSHARE_QFQ_FACTOR_INVALID");
        return raw.multiply(factor)
                .divide(anchor, DIVISION_SCALE, RoundingMode.HALF_UP)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static void requirePositive(
            BigDecimal value,
            String absentCode
    ) {
        if (value == null) {
            throw new IllegalArgumentException(absentCode);
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "TUSHARE_QFQ_FACTOR_INVALID");
        }
    }

    private static Set<TechnicalBlocker> blockers(
            QualificationStatus raw,
            QualificationStatus factor,
            QualificationStatus calendar,
            boolean actionLineageComplete,
            boolean stableActionIdAvailable,
            boolean factorActionRelationshipVerified,
            boolean revisionAvailable,
            boolean versionsQueryable,
            boolean permanentIdentity,
            QualificationStatus dailyExact,
            QualificationStatus providerPit,
            boolean forwardPit,
            boolean safetyBoundary
    ) {
        Set<TechnicalBlocker> values =
                EnumSet.noneOf(TechnicalBlocker.class);
        if (raw != QualificationStatus.VERIFIED
                || factor != QualificationStatus.VERIFIED
                || calendar != QualificationStatus.VERIFIED) {
            values.add(TechnicalBlocker.CORE_FACT_CONTRACT_INCOMPLETE);
        }
        if (!actionLineageComplete) {
            values.add(
                    TechnicalBlocker.CORPORATE_ACTION_LINEAGE_INCOMPLETE);
        }
        if (!stableActionIdAvailable) {
            values.add(TechnicalBlocker.STABLE_ACTION_ID_UNAVAILABLE);
        }
        if (!factorActionRelationshipVerified) {
            values.add(
                    TechnicalBlocker.FACTOR_ACTION_RELATION_UNVERIFIED);
        }
        if (!revisionAvailable) {
            values.add(TechnicalBlocker.PROVIDER_REVISION_UNAVAILABLE);
        }
        if (!versionsQueryable) {
            values.add(
                    TechnicalBlocker.HISTORICAL_VERSIONS_NOT_QUERYABLE);
        }
        if (!permanentIdentity) {
            values.add(
                    TechnicalBlocker.PERMANENT_SECURITY_IDENTITY_UNVERIFIED);
        }
        if (dailyExact != QualificationStatus.VERIFIED) {
            values.add(
                    TechnicalBlocker.FULL_HISTORY_DAILY_EXACT_UNVERIFIED);
        }
        if (providerPit != QualificationStatus.VERIFIED) {
            values.add(TechnicalBlocker.PROVIDER_PIT_UNAVAILABLE);
        }
        if (!forwardPit) {
            values.add(
                    TechnicalBlocker.FORWARD_SYSTEM_KNOWLEDGE_PIT_UNAVAILABLE);
        }
        if (!safetyBoundary) {
            values.add(
                    TechnicalBlocker.SAFETY_BOUNDARY_NOT_IMPLEMENTABLE);
        }
        return Set.copyOf(values);
    }

    private static QualificationStatus evidenceBacked(
            QualificationStatus status,
            QualificationDimension dimension,
            Map<QualificationDimension, Set<String>> evidence
    ) {
        QualificationStatus value = required(status, dimension.name());
        if (value == QualificationStatus.VERIFIED
                && evidence.getOrDefault(dimension, Set.of()).isEmpty()) {
            return QualificationStatus.UNVERIFIED;
        }
        return value;
    }

    private static Map<CorporateActionType, QualificationStatus>
    normalizeActionCoverage(
            Map<CorporateActionType, QualificationStatus> source,
            Map<QualificationDimension, Set<String>> evidence
    ) {
        Objects.requireNonNull(source, "corporateActionCoverage");
        if (!source.keySet().equals(
                EnumSet.allOf(CorporateActionType.class))) {
            throw new IllegalArgumentException(
                    "All corporate-action types must be assessed");
        }
        Map<CorporateActionType, QualificationStatus> result =
                new EnumMap<>(CorporateActionType.class);
        source.forEach((type, status) -> {
            if (status == QualificationStatus.UNAVAILABLE) {
                throw new IllegalArgumentException(
                        "Corporate-action status must use VERIFIED, PARTIAL, "
                                + "NOT_SUPPORTED, or UNVERIFIED");
            }
            result.put(
                    type,
                    evidenceBacked(
                            status,
                            QualificationDimension.CORPORATE_ACTION,
                            evidence));
        });
        return Map.copyOf(result);
    }

    private static Map<QualificationDimension, Set<String>>
    normalizeEvidence(
            Map<QualificationDimension, Set<String>> source
    ) {
        Objects.requireNonNull(source, "evidenceByDimension");
        Map<QualificationDimension, Set<String>> result =
                new EnumMap<>(QualificationDimension.class);
        source.forEach((dimension, ids) -> {
            Objects.requireNonNull(dimension, "evidence dimension");
            Set<String> normalized = new LinkedHashSet<>();
            Objects.requireNonNull(ids, "evidence ids").forEach(id -> {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException(
                            "Evidence IDs must not be blank");
                }
                normalized.add(id);
            });
            result.put(dimension, Set.copyOf(normalized));
        });
        return Map.copyOf(result);
    }

    private static QualificationStatus required(
            QualificationStatus value,
            String name
    ) {
        return Objects.requireNonNull(value, name);
    }

    public record AssessmentInput(
            QualificationStatus rawDailyQualification,
            QualificationStatus adjustmentFactorQualification,
            QualificationStatus calendarQualification,
            QualificationStatus corporateActionQualification,
            QualificationStatus revisionQualification,
            QualificationStatus historicalVersionQualification,
            QualificationStatus securityIdentityQualification,
            QualificationStatus qfqQualification,
            QualificationStatus fullHistoryDailyExactQualification,
            QualificationStatus providerPitQualification,
            Map<CorporateActionType, QualificationStatus>
                    corporateActionCoverage,
            Map<QualificationDimension, Set<String>> evidenceByDimension,
            boolean stableActionIdAvailable,
            boolean factorActionRelationshipVerified,
            boolean forwardSystemKnowledgePitBuildable,
            boolean safetyBoundaryImplementable
    ) {
        public AssessmentInput {
            Objects.requireNonNull(
                    corporateActionCoverage,
                    "corporateActionCoverage");
            Objects.requireNonNull(
                    evidenceByDimension,
                    "evidenceByDimension");
        }
    }

    public record ResearchRawPrice(
            LocalDate tradeDate,
            BigDecimal rawPrice
    ) {
        public ResearchRawPrice {
            Objects.requireNonNull(tradeDate, "tradeDate");
            Objects.requireNonNull(rawPrice, "rawPrice");
        }
    }

    public record ResearchQfqPoint(
            LocalDate tradeDate,
            BigDecimal qfqPrice
    ) {
        public ResearchQfqPoint {
            Objects.requireNonNull(tradeDate, "tradeDate");
            Objects.requireNonNull(qfqPrice, "qfqPrice");
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

    public enum QualificationDimension {
        RAW_DAILY,
        ADJUSTMENT_FACTOR,
        TRADING_CALENDAR,
        CORPORATE_ACTION,
        REVISION,
        HISTORICAL_VERSION,
        SECURITY_IDENTITY,
        QFQ,
        FULL_HISTORY_DAILY_EXACT,
        PROVIDER_PIT
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
        SAFETY_BOUNDARY_NOT_IMPLEMENTABLE
    }

    public enum QfqCalculationMode {
        RAW_FACTOR_END_DATE_ANCHORED
    }

    public enum QfqAnchorSemantics {
        REQUESTED_END_DATE_FACTOR
    }
}
