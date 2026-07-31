package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AdjustmentFactor;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RawDailyBar;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.TradingCalendar;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService.F1eDedicatedCaptureResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.SymbolResearchResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchBatchResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.TushareDedicatedResearchQfqBar;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.AdmissionDecision;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.ImplementationReadiness;
import com.stockquant.server.agent.marketfacts.TushareReducedResearchAdmissionQualification.OperationalReadiness;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.QualificationStatus;
import com.stockquant.server.agent.marketfacts.TushareTechnicalQualification.RouteDecision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit manual F1E runtime for one dedicated local research database.
 *
 * <p>Provider calls are completed before the transactional capture entry is
 * invoked. No controller, scheduler, Agent, backtest or production database
 * entry delegates to this service.</p>
 */
@Service
public final class TushareDedicatedResearchBatchService {

    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);
    private static final Set<String> SUCCESS_REASON_CODES = Set.of(
            "TUSHARE_DEDICATED_LOCAL_RESEARCH_PATH",
            "SYSTEM_KNOWLEDGE_PIT_FORWARD_ONLY",
            "REDUCED_RESEARCH_FORMULA_ONLY",
            "PROVIDER_PIT_NOT_VERIFIED",
            "FULL_QFQ_NOT_ELIGIBLE",
            "OPERATIONAL_ACCEPTANCE_NOT_RUN");

    private final TushareMarketFactProvider provider;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final PitMarketFactCaptureService captureService;
    private final Clock clock;

    public TushareDedicatedResearchBatchService(
            TushareMarketFactProvider provider,
            TushareDedicatedResearchPersistenceGuard guard,
            PitMarketFactCaptureService captureService,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.captureService = Objects.requireNonNull(
                captureService, "captureService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TushareDedicatedResearchBatchResult run(
            TushareDedicatedResearchBatchAuthorization authorization,
            TushareDedicatedResearchBatchCommand command
    ) {
        Objects.requireNonNull(
                authorization, "authorization").validateFrozen();
        Objects.requireNonNull(command, "command");
        validateAdmission();
        validateRateLimitContract();

        TushareDedicatedResearchPersistenceGuard.Verification
                preProvider = guard.verifyBeforeProvider();
        TushareManualBoundedSession session =
                TushareManualBoundedSession.f1eDedicatedLocalManual(
                        command.securities(), command.tradeDate());
        long rateCountBefore = provider.f1cRateLimitContract()
                .totalRateLimitedCallCount();

        List<MarketFactResponse> responses = new ArrayList<>();
        List<ValidatedSymbol> validated = new ArrayList<>();
        for (SecuritySelection security : command.securities()) {
            MarketFactRequest request = new MarketFactRequest(
                    RunNamespace.FORMAL,
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.sourceInstrumentId(
                            security.symbol(), security.exchange()),
                    security.symbol(),
                    security.exchange(),
                    command.tradeDate(),
                    command.tradeDate(),
                    FACT_TYPES,
                    command.timeout());
            MarketFactResponse response =
                    provider.fetchForDedicatedReducedResearch(
                            request, session);
            ValidatedSymbol symbol = validateResponse(
                    response, security, command);
            responses.add(response);
            validated.add(symbol);
        }

        int expectedCalls = command.expectedProviderRequests();
        long rateCountAfter = provider.f1cRateLimitContract()
                .totalRateLimitedCallCount();
        if (session.consumedBusinessRequests() != expectedCalls
                || rateCountAfter - rateCountBefore != expectedCalls
                || validated.stream().mapToInt(
                ValidatedSymbol::providerCallCount).sum()
                != expectedCalls
                || validated.stream().mapToInt(
                ValidatedSymbol::retryCount).sum() != 0) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_CALL_CONTRACT_INVALID");
        }

        Instant observedAt =
                BacktestCanonicalHashService.microsecondInstant(
                        clock.instant());
        TushareDedicatedResearchCaptureContract captureContract =
                TushareDedicatedResearchCaptureContract.validated(
                        command, session, responses);
        F1eDedicatedCaptureResult capture =
                captureService.captureAuthorizedDedicatedResearchBatch(
                        captureContract,
                        observedAt,
                        authorization,
                        preProvider);
        if (capture.captureResults().size() != validated.size()) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_CAPTURE_RESULT_INVALID");
        }

        List<SymbolResearchResult> symbolResults = new ArrayList<>();
        for (int index = 0; index < validated.size(); index++) {
            ValidatedSymbol symbol = validated.get(index);
            symbolResults.add(new SymbolResearchResult(
                    symbol.response().sourceCode(),
                    symbol.response().sourceInstrumentId(),
                    command.tradeDate(),
                    symbol.providerCallCount(),
                    symbol.retryCount(),
                    1,
                    1,
                    1,
                    symbol.qfqBars(),
                    capture.captureResults().get(index)));
        }
        TushareDedicatedResearchPersistenceGuard.Verification before =
                capture.beforeVerification();
        TushareDedicatedResearchPersistenceGuard.Verification after =
                capture.afterVerification();
        DatabaseExecutionIdentity databaseIdentity =
                DatabaseExecutionIdentity.from(before, after);
        return TushareDedicatedResearchBatchResult.formulaOnly(
                command.tradeDate(),
                command.securities().stream()
                        .map(security ->
                                TushareMarketFactProvider
                                        .sourceInstrumentId(
                                                security.symbol(),
                                                security.exchange()))
                        .toList(),
                expectedCalls,
                session.consumedBusinessRequests(),
                symbolResults,
                databaseIdentity,
                SUCCESS_REASON_CODES,
                provider.technicalQualification().routeDecision());
    }

    private void validateAdmission() {
        TushareReducedResearchAdmissionQualification admission =
                TushareReducedResearchAdmissionQualification
                        .currentF1eAssessment();
        TushareTechnicalQualification technical =
                provider.technicalQualification();
        if (admission.admissionDecision()
                != AdmissionDecision.DEDICATED_LOCAL_RESEARCH_PATH
                || admission.implementationReadiness()
                != ImplementationReadiness.READY
                || admission.operationalReadiness()
                != OperationalReadiness.NOT_ACCEPTED
                || !admission
                .reducedResearchLocalRuntimeImplementationReady()
                || !admission
                .reducedResearchControlledAcceptanceReady()
                || admission.reducedResearchOperationalReady()
                || admission.reducedResearchProductionRuntimeReady()
                || admission.normalBusinessDatabaseRuntimeReady()
                || admission.schedulerRuntimeReady()
                || admission.agentDecisionRuntimeReady()
                || admission.backtestExecutionRuntimeReady()
                || admission.f2bRuntimeReady()
                || admission.f3RuntimeReady()
                || admission.fullF1EntryReady()
                || admission.fullTechnicalContractReady()
                || admission.formalEligible()
                || !provider.writtenPermissionQualification()
                .personalResearchPermissionComplete()
                || provider.f1EntryQualification().entryReadiness()
                != TushareF1EntryQualification.EntryReadiness
                .BLOCKED_TECHNICAL_EVIDENCE
                || technical.routeDecision()
                != RouteDecision.REDUCED_RESEARCH_ONLY
                || !technical.reducedResearchContractReady()
                || technical.fullTechnicalContractReady()
                || technical.qfqReducedResearchRuntimeQualification()
                != QualificationStatus.VERIFIED
                || technical.qfqFullLineageRuntimeQualification()
                != QualificationStatus.PARTIAL) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_ADMISSION_BLOCKED");
        }
    }

    private void validateRateLimitContract() {
        try {
            provider.f1cRateLimitContract().validateFrozenF1c();
        } catch (RuntimeException error) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_RATE_LIMIT_GATEWAY_REQUIRED");
        }
    }

    private static ValidatedSymbol validateResponse(
            MarketFactResponse response,
            SecuritySelection security,
            TushareDedicatedResearchBatchCommand command
    ) {
        if (response == null
                || !response.complete()
                || !response.errors().isEmpty()
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.sourceCode())
                || !TushareMarketFactProvider.sourceInstrumentId(
                        security.symbol(), security.exchange()).equals(
                response.sourceInstrumentId())
                || !command.tradeDate().equals(response.requestedStart())
                || !command.tradeDate().equals(response.requestedEnd())) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_PROVIDER_RESPONSE_INCOMPLETE");
        }
        int providerCalls = metadataInt(
                response.providerMetadata(), "providerCallCount");
        int retryCount = metadataInt(
                response.providerMetadata(), "rateLimitRetryCount");
        if (providerCalls != 3 || retryCount != 0
                || response.rawDailyBars().size() != 1
                || response.adjustmentFactors().size() != 1
                || response.tradingCalendar().size() != 1) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_PROVIDER_RESPONSE_INVALID");
        }
        RawDailyBar raw = response.rawDailyBars().get(0);
        AdjustmentFactor factor =
                response.adjustmentFactors().get(0);
        TradingCalendar calendar =
                response.tradingCalendar().get(0);
        if (!command.tradeDate().equals(raw.tradeDate())
                || !command.tradeDate().equals(
                factor.factorEffectiveTradeDate())
                || !command.tradeDate().equals(calendar.calendarDate())
                || !calendar.open()) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_FACT_WINDOW_INCOMPLETE");
        }
        requirePositive(factor.factor(),
                "TUSHARE_QFQ_FACTOR_INVALID");
        requirePositive(raw.open(),
                "TUSHARE_QFQ_RAW_PRICE_INVALID");
        requirePositive(raw.high(),
                "TUSHARE_QFQ_RAW_PRICE_INVALID");
        requirePositive(raw.low(),
                "TUSHARE_QFQ_RAW_PRICE_INVALID");
        requirePositive(raw.close(),
                "TUSHARE_QFQ_RAW_PRICE_INVALID");
        List<TushareDedicatedResearchQfqBar> qfqBars = List.of(
                new TushareDedicatedResearchQfqBar(
                        command.tradeDate(),
                        QfqPriceMath.calculate(
                                raw.open(),
                                factor.factor(),
                                factor.factor()),
                        QfqPriceMath.calculate(
                                raw.high(),
                                factor.factor(),
                                factor.factor()),
                        QfqPriceMath.calculate(
                                raw.low(),
                                factor.factor(),
                                factor.factor()),
                        QfqPriceMath.calculate(
                                raw.close(),
                                factor.factor(),
                                factor.factor())));
        return new ValidatedSymbol(
                response, providerCalls, retryCount, qfqBars);
    }

    private static int metadataInt(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw blocked(
                    "TUSHARE_DEDICATED_RESEARCH_PROVIDER_METADATA_INVALID");
        }
        return value.intValue();
    }

    private static void requirePositive(
            BigDecimal value,
            String safeCode
    ) {
        if (value == null || value.signum() <= 0) {
            throw blocked(safeCode);
        }
    }

    private static RuntimeBlockedException blocked(String safeCode) {
        return new RuntimeBlockedException(safeCode);
    }

    private record ValidatedSymbol(
            MarketFactResponse response,
            int providerCallCount,
            int retryCount,
            List<TushareDedicatedResearchQfqBar> qfqBars
    ) {
        private ValidatedSymbol {
            response = Objects.requireNonNull(response, "response");
            qfqBars = List.copyOf(qfqBars);
        }
    }

    public static final class RuntimeBlockedException
            extends RuntimeException {
        private final String safeCode;

        RuntimeBlockedException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }

        public String safeCode() {
            return safeCode;
        }
    }
}
