package com.stockquant.server.agent.marketfacts;

import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded fixed-universe initial/repair capture for RESEARCH_UNIVERSE_V1. */
public final class TushareResearchUniverseCaptureService {
    private final TushareMarketFactProvider provider;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final PitMarketFactCaptureService capture;
    private final Clock clock;

    public TushareResearchUniverseCaptureService(
            TushareMarketFactProvider provider,
            TushareDedicatedResearchPersistenceGuard guard,
            PitMarketFactCaptureService capture,
            Clock clock
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CaptureEvidence capture(
            List<Security> securities,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            Duration timeout
    ) {
        List<SecuritySelection> selections = securities.stream()
                .sorted().map(value -> new SecuritySelection(value.symbol(),
                        value.exchange())).toList();
        if (selections.size()
                != TushareManualBoundedSession.RESEARCH_UNIVERSE_MAX_SYMBOLS
                || timeout == null || timeout.isZero()
                || timeout.isNegative()) {
            throw invalid("RESEARCH_UNIVERSE_CAPTURE_REQUEST_INVALID");
        }
        var preProvider = guard.verifyBeforeProvider();
        LocalDate calendarEnd = rangeEnd.plusDays(
                TushareManualBoundedSession
                        .RESEARCH_UNIVERSE_CALENDAR_FORWARD_DAYS);
        var session = TushareManualBoundedSession.researchUniverse(
                selections, rangeStart, rangeEnd, calendarEnd);
        try {
            List<MarketFactResponse> responses = new ArrayList<>();
            for (SecuritySelection security : selections) {
                MarketFactResponse response = provider
                        .fetchForResearchUniverseSecurity(request(security,
                                rangeStart, rangeEnd, Set.of(
                                        FactType.RAW_DAILY_BAR,
                                        FactType.ADJUSTMENT_FACTOR), timeout),
                                session);
                validateSecurity(response, security, rangeStart, rangeEnd);
                responses.add(response);
            }
            selections.stream().map(SecuritySelection::exchange).distinct()
                    .sorted().forEach(exchange -> {
                        SecuritySelection representative = selections.stream()
                                .filter(value -> value.exchange()
                                        .equals(exchange))
                                .findFirst().orElseThrow();
                        MarketFactResponse response = provider
                                .fetchForResearchUniverseCalendar(request(
                                        representative, rangeStart,
                                        calendarEnd,
                                        Set.of(FactType.TRADING_CALENDAR),
                                        timeout), session);
                        validateCalendar(response, exchange, rangeStart,
                                calendarEnd);
                        responses.add(response);
                    });
            if (session.consumedBusinessRequests()
                    != TushareManualBoundedSession
                    .RESEARCH_UNIVERSE_MAX_PROVIDER_REQUESTS) {
                throw invalid("RESEARCH_UNIVERSE_PROVIDER_BUDGET_MISMATCH");
            }
            Instant observedAt = clock.instant();
            List<CaptureResult> results = capture
                    .captureAuthorizedResearchUniverse(responses, observedAt,
                            preProvider);
            int received = results.stream().mapToInt(
                    CaptureResult::receivedCount).sum();
            int appended = results.stream().mapToInt(
                    CaptureResult::appendedCount).sum();
            int idempotent = results.stream().mapToInt(
                    CaptureResult::idempotentCount).sum();
            return new CaptureEvidence(session.consumedBusinessRequests(), 0,
                    results.stream().map(CaptureResult::batchId).toList(),
                    received, appended, idempotent, observedAt);
        } catch (RuntimeException failure) {
            throw captureFailure(failure,
                    session.consumedBusinessRequests());
        }
    }

    /** Incremental open-day refresh: two market-wide requests, no retry. */
    public CaptureEvidence captureDailyIncrement(
            List<Security> securities,
            LocalDate tradeDate,
            Duration timeout
    ) {
        List<SecuritySelection> selections = securities.stream().sorted()
                .map(value -> new SecuritySelection(value.symbol(),
                        value.exchange())).toList();
        if (selections.size()
                != TushareManualBoundedSession.RESEARCH_UNIVERSE_MAX_SYMBOLS
                || timeout == null || timeout.isZero()
                || timeout.isNegative()) {
            throw invalid("RESEARCH_UNIVERSE_INCREMENT_REQUEST_INVALID");
        }
        var preProvider = guard.verifyBeforeProvider();
        var session = TushareManualBoundedSession
                .researchUniverseDailyIncrement(selections, tradeDate);
        try {
            List<MarketFactRequest> requests = selections.stream()
                    .map(security -> request(security, tradeDate, tradeDate,
                            Set.of(FactType.RAW_DAILY_BAR,
                                    FactType.ADJUSTMENT_FACTOR), timeout))
                    .toList();
            List<MarketFactResponse> responses = provider
                    .fetchResearchUniverseDateBulk(requests, session);
            if (responses.stream().anyMatch(response -> !response.complete())
                    || session.consumedBusinessRequests() != 2) {
                throw invalid(
                        "RESEARCH_UNIVERSE_INCREMENT_RESPONSE_INVALID");
            }
            Instant observedAt = clock.instant();
            List<CaptureResult> results = capture
                    .captureAuthorizedResearchUniverse(responses, observedAt,
                            preProvider);
            return new CaptureEvidence(2, 0,
                    results.stream().map(CaptureResult::batchId).toList(),
                    results.stream().mapToInt(
                            CaptureResult::receivedCount).sum(),
                    results.stream().mapToInt(
                            CaptureResult::appendedCount).sum(),
                    results.stream().mapToInt(
                            CaptureResult::idempotentCount).sum(), observedAt);
        } catch (RuntimeException failure) {
            throw captureFailure(failure,
                    session.consumedBusinessRequests());
        }
    }

    private static MarketFactRequest request(
            SecuritySelection security,
            LocalDate start,
            LocalDate end,
            Set<FactType> facts,
            Duration timeout
    ) {
        return new MarketFactRequest(RunNamespace.FORMAL,
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.sourceInstrumentId(
                        security.symbol(), security.exchange()),
                security.symbol(), security.exchange(), start, end, facts,
                timeout);
    }

    private static void validateSecurity(
            MarketFactResponse response,
            SecuritySelection security,
            LocalDate start,
            LocalDate end
    ) {
        long openDays = response.rawDailyBars().stream()
                .map(MarketFactProviderModels.RawDailyBar::tradeDate)
                .distinct().count();
        if (!response.complete() || !response.errors().isEmpty()
                || response.providerMetadata().path("providerCallCount")
                .asInt(-1) != 2
                || response.providerMetadata().path("rateLimitRetryCount")
                .asInt(-1) != 0
                || response.rawDailyBars().stream().anyMatch(value ->
                !value.symbol().equals(security.symbol())
                        || !value.exchange().equals(security.exchange())
                        || value.tradeDate().isBefore(start)
                        || value.tradeDate().isAfter(end))
                || response.adjustmentFactors().stream().anyMatch(value ->
                !value.symbol().equals(security.symbol())
                        || value.factorEffectiveTradeDate().isBefore(start)
                        || value.factorEffectiveTradeDate().isAfter(end))
                || response.adjustmentFactors().stream().map(
                MarketFactProviderModels.AdjustmentFactor
                        ::factorEffectiveTradeDate).distinct().count()
                != openDays || openDays < 20
                || !response.tradingCalendar().isEmpty()) {
            throw invalid("RESEARCH_UNIVERSE_SECURITY_RESPONSE_INVALID");
        }
    }

    private static void validateCalendar(
            MarketFactResponse response,
            String exchange,
            LocalDate start,
            LocalDate end
    ) {
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        if (!response.complete() || !response.errors().isEmpty()
                || response.providerMetadata().path("providerCallCount")
                .asInt(-1) != 1
                || response.providerMetadata().path("rateLimitRetryCount")
                .asInt(-1) != 0
                || response.tradingCalendar().size() != days
                || response.tradingCalendar().stream().anyMatch(value ->
                !value.exchange().equals(exchange)
                        || value.calendarDate().isBefore(start)
                        || value.calendarDate().isAfter(end))
                || !response.rawDailyBars().isEmpty()
                || !response.adjustmentFactors().isEmpty()) {
            throw invalid("RESEARCH_UNIVERSE_CALENDAR_RESPONSE_INVALID");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    private static CaptureFailure captureFailure(
            RuntimeException failure,
            int providerCallCount
    ) {
        if (failure instanceof CaptureFailure captured) {
            return captured;
        }
        String message = failure.getMessage();
        String reason = message != null
                && message.matches("[A-Z][A-Z0-9_]{3,127}")
                ? message : "RESEARCH_UNIVERSE_CAPTURE_FAILED";
        return new CaptureFailure(reason, providerCallCount, failure);
    }

    /** Carries already-consumed external calls without exposing response data. */
    public static final class CaptureFailure extends IllegalStateException {
        private final int providerCallCount;

        private CaptureFailure(
                String reason,
                int providerCallCount,
                RuntimeException cause
        ) {
            super(reason, cause);
            this.providerCallCount = providerCallCount;
        }

        public int providerCallCount() {
            return providerCallCount;
        }
    }

    public record CaptureEvidence(
            int providerCallCount,
            int retryCount,
            List<Long> batchIds,
            int receivedObservations,
            int appendedObservations,
            int idempotentChainTailHits,
            Instant observedAt
    ) {
        public CaptureEvidence {
            batchIds = List.copyOf(batchIds);
        }
    }
}
