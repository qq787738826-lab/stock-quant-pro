package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded two-exchange calendar refresh before an unknown scheduled date. */
final class TushareM4TradingCalendarAdmissionService {
    private final TushareMarketFactProvider provider;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final PitMarketFactCaptureService capture;
    private final TransactionTemplate transactions;
    private final Clock clock;

    TushareM4TradingCalendarAdmissionService(
            TushareMarketFactProvider provider,
            TushareDedicatedResearchPersistenceGuard guard,
            PitMarketFactCaptureService capture,
            TransactionTemplate transactions,
            Clock clock
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.transactions = Objects.requireNonNull(transactions,
                "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Admission refresh(
            LocalDate tradeDate,
            LocalDate horizonEnd,
            Duration timeout
    ) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        Objects.requireNonNull(horizonEnd, "horizonEnd");
        Objects.requireNonNull(timeout, "timeout");
        if (horizonEnd.isBefore(tradeDate)
                || horizonEnd.isAfter(tradeDate.plusDays(30))
                || timeout.isZero() || timeout.isNegative()
                || clock.instant().isBefore(tradeDate.atTime(15, 0)
                .atZone(PitMarketFactsContracts.MARKET_ZONE).toInstant())) {
            throw invalid("M4_CALENDAR_ADMISSION_SCOPE_INVALID");
        }
        var preProvider = guard.verifyBeforeProvider();
        List<SecuritySelection> securities = List.of(
                new SecuritySelection("600000", "SSE"),
                new SecuritySelection("000001", "SZSE"));
        var session = TushareManualBoundedSession.m4CalendarAdmission(
                securities, tradeDate, horizonEnd);
        List<MarketFactResponse> responses = new ArrayList<>();
        try {
            for (SecuritySelection security : securities) {
                MarketFactRequest request = new MarketFactRequest(
                        RunNamespace.FORMAL,
                        TushareMarketFactProvider.PROVIDER_CODE,
                        TushareMarketFactProvider.sourceInstrumentId(
                                security.symbol(), security.exchange()),
                        security.symbol(), security.exchange(), tradeDate,
                        horizonEnd, Set.of(FactType.TRADING_CALENDAR), timeout);
                MarketFactResponse response = provider
                        .fetchForM4CalendarAdmission(request, session);
                validate(response, security, tradeDate, horizonEnd);
                responses.add(response);
            }
        } catch (RuntimeException error) {
            throw new CalendarAdmissionFailure(error.getMessage(),
                    session.consumedBusinessRequests(), 0, error);
        }
        if (session.consumedBusinessRequests() != 2) {
            throw invalid("M4_CALENDAR_ADMISSION_CALL_CONTRACT_INVALID");
        }
        List<CaptureResult> results = Objects.requireNonNull(
                transactions.execute(status -> {
                    var before = guard.verifyTransactional();
                    guard.verifySameTarget(preProvider, before);
                    List<CaptureResult> captured = new ArrayList<>();
                    for (MarketFactResponse response : responses) {
                        captured.add(capture
                                .captureAuthorizedLimitedPersonalFormal(
                                        response, clock.instant(),
                                        LimitedPersonalFormalCaptureAuthorization
                                                .tushareF1A()));
                    }
                    var after = guard.verifyTransactional();
                    guard.verifySameTransactionalConnection(before, after);
                    return List.copyOf(captured);
                }), "M4 calendar admission capture result");
        boolean open = responses.stream().allMatch(response ->
                response.tradingCalendar().stream().filter(value ->
                                value.calendarDate().equals(tradeDate))
                        .findFirst().orElseThrow().open());
        int received = results.stream().mapToInt(
                CaptureResult::receivedCount).sum();
        int appended = results.stream().mapToInt(
                CaptureResult::appendedCount).sum();
        int idempotent = results.stream().mapToInt(
                CaptureResult::idempotentCount).sum();
        return new Admission(open, 2, 0, received, appended, idempotent);
    }

    private static void validate(
            MarketFactResponse response,
            SecuritySelection security,
            LocalDate start,
            LocalDate end
    ) {
        if (!response.complete() && !response.errors().isEmpty()) {
            String code = response.errors().get(0).code();
            throw invalid(code != null
                    && code.matches("[A-Z][A-Z0-9_]{3,127}")
                    ? code : "M4_CALENDAR_ADMISSION_PROVIDER_FAILED");
        }
        int expectedDays = Math.toIntExact(end.toEpochDay()
                - start.toEpochDay() + 1);
        if (!response.complete() || !response.errors().isEmpty()
                || !response.rawDailyBars().isEmpty()
                || !response.adjustmentFactors().isEmpty()
                || !response.corporateActions().isEmpty()
                || response.tradingCalendar().size() != expectedDays
                || response.tradingCalendar().stream().anyMatch(value ->
                !security.exchange().equals(value.exchange())
                        || value.calendarDate().isBefore(start)
                        || value.calendarDate().isAfter(end))
                || response.tradingCalendar().stream()
                .map(value -> value.calendarDate()).distinct().count()
                != expectedDays
                || response.providerMetadata().path("providerCallCount")
                .asInt(-1) != 1
                || response.providerMetadata().path("rateLimitRetryCount")
                .asInt(-1) != 0) {
            throw invalid("M4_CALENDAR_ADMISSION_RESPONSE_INVALID");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    static final class CalendarAdmissionFailure extends IllegalStateException {
        private final int providerCalls;
        private final int retryCount;

        private CalendarAdmissionFailure(
                String code,
                int providerCalls,
                int retryCount,
                Throwable cause
        ) {
            super(code != null && code.matches("[A-Z][A-Z0-9_]{3,127}")
                    ? code : "M4_CALENDAR_ADMISSION_FAILED", cause);
            this.providerCalls = providerCalls;
            this.retryCount = retryCount;
        }

        int providerCalls() {
            return providerCalls;
        }

        int retryCount() {
            return retryCount;
        }
    }

    record Admission(
            boolean open,
            int providerCalls,
            int retryCount,
            int receivedFacts,
            int appendedObservations,
            int idempotentChainTailHits
    ) {
    }
}
