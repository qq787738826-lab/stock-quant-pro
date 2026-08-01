package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceAuthorization.ControlledEndpoint;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.AcceptanceStatus;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.AtomicCommitResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.ExecutionEvidence;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.FormulaOnlyQfqSummary;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.ProhibitedStage;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceQualification.SystemKnowledgeEvidence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TushareControlledAcceptanceQualificationTest {

    private static final String BASELINE =
            TushareControlledAcceptanceQualification.PREPARATION_BASELINE;
    private static final String OTHER_BASELINE =
            "1111111111111111111111111111111111111111";
    private static final Instant STARTED =
            Instant.parse("2026-08-01T02:00:00Z");
    private static final Instant ENDED =
            Instant.parse("2026-08-01T02:01:00Z");
    private static final Instant EXPIRES =
            Instant.parse("2026-08-01T03:00:00Z");

    @Test
    void defaultStateIsNotRunAndOperationalReadyFalse() {
        var qualification =
                TushareControlledAcceptanceQualification.notRun();

        assertEquals(AcceptanceStatus.NOT_RUN, qualification.status());
        assertFalse(qualification.reducedResearchOperationalReady());
        assertTrue(qualification.evidenceIds().isEmpty());
        assertFalse(TushareReducedResearchAdmissionQualification
                .currentF1eAssessment()
                .reducedResearchOperationalReady());
    }

    @Test
    void passedQualificationCannotBeConstructedByPublicApi() {
        assertTrue(java.util.Arrays.stream(
                        TushareControlledAcceptanceQualification.class
                                .getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(
                        constructor.getModifiers())));
        assertTrue(java.util.Arrays.stream(
                        TushareControlledAcceptanceQualification.class
                                .getMethods())
                .noneMatch(method -> method.getName().toLowerCase()
                        .contains("pass")));
    }

    @Test
    void incompatibleBaselineAndExpiredEvidenceFailClosed() {
        assertStatus(
                AcceptanceStatus.INCOMPATIBLE_BASELINE,
                qualify(evidence(builder -> {
                }), OTHER_BASELINE, STARTED));
        assertStatus(
                AcceptanceStatus.STALE,
                TushareControlledAcceptanceQualification
                        .preparedCandidate(
                                evidence(builder -> {
                                }),
                                ENDED,
                                BASELINE,
                                ENDED));
    }

    @Test
    void providerCallBudgetAndEndpointCountsMustBeExact() {
        assertFailed(evidence(builder -> builder.totalCalls = 2));
        assertFailed(evidence(builder -> builder.totalCalls = 4));
        assertFailed(evidence(builder -> builder.endpointCalls.put(
                ControlledEndpoint.DAILY, 2)));
        assertFailed(evidence(builder -> builder.retryCount = 1));
        assertFailed(evidence(builder -> builder.endpoints = Set.of(
                ControlledEndpoint.DAILY,
                ControlledEndpoint.ADJ_FACTOR)));
    }

    @Test
    void symbolDateAndOpenDayScopeMustBeExact() {
        assertFailed(evidence(builder -> builder.symbolCount = 2));
        assertFailed(evidence(builder -> builder.tradeDateCount = 2));
        assertFailed(evidence(builder -> builder.tradingDayOpen = false));
        assertFailed(evidence(builder -> builder.sessionCode = "CLOSED"));
    }

    @Test
    void dedicatedDatabaseIdentityAndV13MustRemainStable() {
        assertFailed(evidence(builder ->
                builder.databaseIdentity = "stock_quant"));
        assertFailed(evidence(builder -> builder.publicSchemaUsed = true));
        assertFailed(evidence(builder -> builder.schemaVersion = 12));
        assertFailed(evidence(builder ->
                builder.databaseIdentityStable = false));
    }

    @Test
    void factFailureAndRollbackFailureCannotPass() {
        assertFailed(evidence(builder -> builder.factCounts.put(
                FactType.ADJUSTMENT_FACTOR, 0)));
        assertFailed(evidence(builder -> builder.atomicCommitResult =
                AtomicCommitResult.ROLLED_BACK_CLEANLY));
        assertFailed(evidence(builder -> builder.atomicCommitResult =
                AtomicCommitResult.ROLLBACK_FAILED));
    }

    @Test
    void systemKnowledgeAndFormulaOnlyBoundariesAreRequired() {
        assertFailed(evidence(builder ->
                builder.systemKnowledgeEvidence =
                        new SystemKnowledgeEvidence(
                                STARTED, false, true, true)));
        assertFailed(evidence(builder -> builder.qfqSummary =
                new FormulaOnlyQfqSummary(
                        1, true, true, false, false)));
        assertFailed(evidence(builder -> builder.qfqSummary =
                new FormulaOnlyQfqSummary(
                        1, true, false, true, true)));
        assertFailed(evidence(builder -> builder.providerPitVerified = true));
        assertFailed(evidence(builder -> builder.fullLineageVerified = true));
        assertFailed(evidence(builder ->
                builder.permanentSecurityIdentityVerified = true));
    }

    @Test
    void sensitiveOutputAndProhibitedStagesFailClosed() {
        assertFailed(evidence(builder -> builder.tokenOutputDetected = true));
        assertFailed(evidence(builder -> builder.startedProhibitedStages =
                Set.of(ProhibitedStage.SCHEDULER)));
        assertFailed(evidence(builder -> builder.startedProhibitedStages =
                Set.of(ProhibitedStage.AGENT, ProhibitedStage.BACKTEST,
                        ProhibitedStage.SHADOW, ProhibitedStage.TRADING)));
    }

    @Test
    void completeOfflineExecutionProducesCandidateOnly() {
        var candidate = qualify(evidence(builder -> {
        }), BASELINE, ENDED);

        assertStatus(AcceptanceStatus.CANDIDATE, candidate);
        assertFalse(candidate.reducedResearchOperationalReady());
        var admission = TushareReducedResearchAdmissionQualification.assess(
                TushareWrittenPermissionQualification
                        .currentPersonal2000PointAssessment(),
                TushareTechnicalQualification
                        .current2000PointAssessment(),
                TushareReducedResearchAdmissionQualification
                        .ImplementationEvidence.currentF1e(),
                candidate);
        assertFalse(admission.reducedResearchOperationalReady());
        assertFalse(admission.fullF1EntryReady());
        assertFalse(admission.fullTechnicalContractReady());
        assertFalse(admission.formalEligible());
    }

    @Test
    void completeF1TechnicalBlockerSetRemainsUnchanged() {
        var technical =
                TushareTechnicalQualification.current2000PointAssessment();
        var entry = TushareF1EntryQualification.assess(
                TushareWrittenPermissionQualification
                        .currentPersonal2000PointAssessment(),
                technical);

        assertEquals(10, technical.blockers().size());
        assertEquals(
                TushareF1EntryQualification.EntryReadiness
                        .BLOCKED_TECHNICAL_EVIDENCE,
                entry.entryReadiness());
        assertFalse(entry.fullF1EntryReady());
    }

    private static TushareControlledAcceptanceQualification qualify(
            ExecutionEvidence evidence,
            String activeBaseline,
            Instant assessedAt
    ) {
        return TushareControlledAcceptanceQualification
                .preparedCandidate(
                        evidence, EXPIRES, activeBaseline, assessedAt);
    }

    private static void assertFailed(ExecutionEvidence evidence) {
        assertStatus(
                AcceptanceStatus.FAILED,
                qualify(evidence, BASELINE, ENDED));
    }

    private static void assertStatus(
            AcceptanceStatus expected,
            TushareControlledAcceptanceQualification qualification
    ) {
        assertEquals(expected, qualification.status());
        assertFalse(qualification.reducedResearchOperationalReady());
        assertFalse(qualification.blockers().isEmpty());
    }

    private static ExecutionEvidence evidence(Consumer<Builder> mutation) {
        Builder builder = new Builder();
        mutation.accept(builder);
        return builder.build();
    }

    private static final class Builder {
        private String databaseIdentity = "stock_quant_research";
        private int schemaVersion = 13;
        private int symbolCount = 1;
        private int tradeDateCount = 1;
        private Set<ControlledEndpoint> endpoints =
                TushareControlledAcceptanceAuthorization.REQUIRED_ENDPOINTS;
        private final Map<ControlledEndpoint, Integer> endpointCalls =
                endpointCounts();
        private int totalCalls = 3;
        private int retryCount;
        private final Map<FactType, Integer> factCounts = factCounts();
        private AtomicCommitResult atomicCommitResult =
                AtomicCommitResult.COMMITTED_ATOMICALLY;
        private boolean databaseIdentityStable = true;
        private boolean tradingDayOpen = true;
        private String sessionCode = "REGULAR";
        private SystemKnowledgeEvidence systemKnowledgeEvidence =
                new SystemKnowledgeEvidence(STARTED, true, true, true);
        private FormulaOnlyQfqSummary qfqSummary =
                new FormulaOnlyQfqSummary(
                        1, true, false, false, false);
        private boolean tokenOutputDetected;
        private boolean normalBusinessDatabaseUsed;
        private boolean publicSchemaUsed;
        private Set<ProhibitedStage> startedProhibitedStages = Set.of();
        private boolean providerPitVerified;
        private boolean fullLineageVerified;
        private boolean permanentSecurityIdentityVerified;

        private ExecutionEvidence build() {
            return new ExecutionEvidence(
                    "F1F_ACCEPTANCE_EVIDENCE",
                    "F1F_ACCEPTANCE_001",
                    BASELINE,
                    TushareMarketFactProvider.PROVIDER_CODE,
                    databaseIdentity,
                    "stock_quant_research",
                    "tushare_research",
                    schemaVersion,
                    symbolCount,
                    tradeDateCount,
                    "600000.SH",
                    LocalDate.of(2026, 7, 30),
                    endpoints,
                    endpointCalls,
                    totalCalls,
                    retryCount,
                    STARTED,
                    ENDED,
                    List.of(101L),
                    factCounts,
                    atomicCommitResult,
                    databaseIdentityStable,
                    tradingDayOpen,
                    sessionCode,
                    systemKnowledgeEvidence,
                    qfqSummary,
                    tokenOutputDetected,
                    normalBusinessDatabaseUsed,
                    publicSchemaUsed,
                    startedProhibitedStages,
                    providerPitVerified,
                    fullLineageVerified,
                    permanentSecurityIdentityVerified,
                    "CONTROLLED_ACCEPTANCE_EXECUTION_SUMMARY");
        }

        private static Map<ControlledEndpoint, Integer> endpointCounts() {
            Map<ControlledEndpoint, Integer> values =
                    new EnumMap<>(ControlledEndpoint.class);
            TushareControlledAcceptanceAuthorization.REQUIRED_ENDPOINTS
                    .forEach(endpoint -> values.put(endpoint, 1));
            return values;
        }

        private static Map<FactType, Integer> factCounts() {
            Map<FactType, Integer> values = new EnumMap<>(FactType.class);
            values.put(FactType.RAW_DAILY_BAR, 1);
            values.put(FactType.ADJUSTMENT_FACTOR, 1);
            values.put(FactType.TRADING_CALENDAR, 1);
            return values;
        }
    }
}
