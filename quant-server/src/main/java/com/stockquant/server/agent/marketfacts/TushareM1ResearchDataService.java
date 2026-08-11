package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.PitMarketFactCaptureService.M1ResearchCaptureResult;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchModels.DatabaseExecutionIdentity;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.ResearchDataset;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.RunEvidence;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Manual, bounded M1 capture and post-commit typed dataset readback. */
public final class TushareM1ResearchDataService {
    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    private final TushareMarketFactProvider provider;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final PitMarketFactCaptureService captureService;
    private final TushareM1ResearchDatasetService datasetService;
    private final Clock clock;

    public TushareM1ResearchDataService(
            TushareMarketFactProvider provider,
            TushareDedicatedResearchPersistenceGuard guard,
            PitMarketFactCaptureService captureService,
            TushareM1ResearchDatasetService datasetService,
            Clock clock
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.captureService = Objects.requireNonNull(
                captureService, "captureService");
        this.datasetService = Objects.requireNonNull(
                datasetService, "datasetService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RunEvidence run(
            TushareDedicatedResearchBatchAuthorization authorization,
            TushareM1ResearchWindowCommand command
    ) {
        Objects.requireNonNull(authorization, "authorization")
                .validateM1Frozen();
        Objects.requireNonNull(command, "command");
        validateProviderContract();
        TushareDedicatedResearchPersistenceGuard.Verification preProvider =
                guard.verifyBeforeProvider();
        TushareManualBoundedSession session =
                TushareManualBoundedSession.m1ResearchDataManual(
                        command.securities(), command.rangeStart(),
                        command.rangeEnd());
        long attemptsBefore = provider.f1cRateLimitContract()
                .totalRateLimitedCallCount();
        List<MarketFactResponse> responses = new ArrayList<>();
        for (SecuritySelection security : command.securities()) {
            MarketFactRequest request = new MarketFactRequest(
                    RunNamespace.FORMAL,
                    TushareMarketFactProvider.PROVIDER_CODE,
                    TushareMarketFactProvider.sourceInstrumentId(
                            security.symbol(), security.exchange()),
                    security.symbol(), security.exchange(),
                    command.rangeStart(), command.rangeEnd(), FACT_TYPES,
                    command.timeout());
            MarketFactResponse response = provider.fetchForM1ResearchData(
                    request, session);
            if (!response.complete()) {
                throw blocked(safeProviderCode(response));
            }
            TushareM1ResearchWindowValidator.validate(
                    response, security, command);
            responses.add(response);
        }
        int expectedCalls = command.expectedProviderRequests();
        long attemptsAfter = provider.f1cRateLimitContract()
                .totalRateLimitedCallCount();
        if (session.consumedBusinessRequests() != expectedCalls
                || attemptsAfter - attemptsBefore != expectedCalls) {
            throw blocked("TUSHARE_M1_PROVIDER_CALL_CONTRACT_INVALID");
        }
        Instant observedAt = BacktestCanonicalHashService.microsecondInstant(
                clock.instant());
        TushareM1ResearchCaptureContract contract =
                TushareM1ResearchCaptureContract.validated(
                        command, session, responses);
        M1ResearchCaptureResult capture =
                captureService.captureAuthorizedM1ResearchBatch(
                        contract, observedAt, authorization, preProvider);
        List<CaptureResult> results = capture.captureResults();
        int received = results.stream().mapToInt(
                CaptureResult::receivedCount).sum();
        int appended = results.stream().mapToInt(
                CaptureResult::appendedCount).sum();
        int idempotent = results.stream().mapToInt(
                CaptureResult::idempotentCount).sum();
        if (command.mode()
                == TushareM1ResearchWindowCommand.Mode.IDEMPOTENCY_VERIFICATION
                && (appended != 0 || idempotent != received)
                || command.mode() == TushareM1ResearchWindowCommand.Mode.CAPTURE
                && appended == 0) {
            throw blocked("TUSHARE_M1_MODE_RESULT_MISMATCH");
        }
        Instant readbackAt = clock.instant();
        ResearchDataset dataset = datasetService.loadAndVerify(
                command, readbackAt);
        boolean references = datasetService.verifyCurrentBatchReferences(
                results);
        if (!references) {
            throw blocked("TUSHARE_M1_CURRENT_BATCH_REFERENCES_INVALID");
        }
        Map<String, Integer> endpointCalls = new LinkedHashMap<>();
        endpointCalls.put("daily", command.securities().size());
        endpointCalls.put("adj_factor", command.securities().size());
        endpointCalls.put("trade_cal", command.securities().size());
        DatabaseExecutionIdentity databaseIdentity =
                DatabaseExecutionIdentity.from(
                        capture.beforeVerification(),
                        capture.afterVerification());
        return new RunEvidence(
                observedAt, expectedCalls, 0, endpointCalls,
                results.stream().map(CaptureResult::batchId).toList(),
                received, appended, idempotent, true,
                databaseIdentity, dataset);
    }

    private void validateProviderContract() {
        try {
            provider.f1cRateLimitContract().validateFrozenF1c();
        } catch (RuntimeException error) {
            throw blocked("TUSHARE_M1_RATE_LIMIT_GATEWAY_REQUIRED");
        }
        TushareTechnicalQualification qualification =
                provider.technicalQualification();
        if (!provider.writtenPermissionQualification()
                .personalResearchPermissionComplete()
                || qualification.routeDecision()
                != TushareTechnicalQualification.RouteDecision
                .REDUCED_RESEARCH_ONLY
                || !qualification.reducedResearchContractReady()
                || qualification.fullTechnicalContractReady()
                || qualification.qfqReducedResearchRuntimeQualification()
                != TushareTechnicalQualification.QualificationStatus.VERIFIED
                || qualification.qfqFullLineageRuntimeQualification()
                != TushareTechnicalQualification.QualificationStatus.PARTIAL) {
            throw blocked("TUSHARE_M1_RESEARCH_DATA_ADMISSION_BLOCKED");
        }
    }

    private static String safeProviderCode(MarketFactResponse response) {
        if (response.errors().size() == 1) {
            String value = response.errors().get(0).code();
            if (value != null && value.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return value;
            }
        }
        return "TUSHARE_M1_PROVIDER_RESPONSE_INCOMPLETE";
    }

    private static TushareDedicatedResearchBatchService.RuntimeBlockedException
    blocked(String code) {
        return new TushareDedicatedResearchBatchService.RuntimeBlockedException(
                code);
    }
}
