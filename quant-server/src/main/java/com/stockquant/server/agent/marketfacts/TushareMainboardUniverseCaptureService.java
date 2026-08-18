package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.TushareReferenceDataModels.MainboardInstrument;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Exact-budget stock_basic plus trade-date bulk capture for V1.0.9. */
public final class TushareMainboardUniverseCaptureService {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private final TushareMarketFactProvider provider;
    private final TushareDedicatedResearchPersistenceGuard guard;
    private final PitMarketFactCaptureService capture;
    private final ResearchUniverseMainboardRepository universes;
    private final Clock clock;

    public TushareMainboardUniverseCaptureService(
            TushareMarketFactProvider provider,
            TushareDedicatedResearchPersistenceGuard guard,
            PitMarketFactCaptureService capture,
            ResearchUniverseMainboardRepository universes,
            Clock clock
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.universes = Objects.requireNonNull(universes, "universes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CaptureEvidence capture(
            SnapshotBundle current,
            boolean refreshStockBasic,
            Set<LocalDate> missingTradeDates,
            LocalDate calendarStart,
            LocalDate calendarEnd,
            boolean refreshCalendar,
            String gitCommit,
            Duration timeout
    ) {
        if (missingTradeDates == null || calendarStart == null
                || calendarEnd == null || gitCommit == null
                || !gitCommit.matches("[0-9a-f]{40}") || timeout == null
                || timeout.isZero() || timeout.isNegative()
                || current == null && !refreshStockBasic) {
            throw invalid("MAINBOARD_CAPTURE_REQUEST_INVALID");
        }
        var preProvider = guard.verifyBeforeProvider();
        var session = TushareManualBoundedSession.mainboardUniverse(
                missingTradeDates, calendarStart, calendarEnd,
                refreshStockBasic, refreshCalendar);
        int appended = 0;
        int idempotent = 0;
        List<Long> batches = new ArrayList<>();
        SnapshotBundle snapshot = current;
        try {
            if (refreshStockBasic) {
                var response = provider.fetchMainboardUniverseSnapshot(
                        timeout, session);
                if (current != null && response.values().size() * 100L
                        < current.snapshot().memberCount()
                        * TushareMarketFactProvider
                        .MAINBOARD_MINIMUM_COVERAGE_PERCENT) {
                    throw invalid(
                            "MAINBOARD_STOCK_BASIC_CONTINUITY_INVALID");
                }
                Instant observedAt = clock.instant();
                List<Member> members = response.values().stream().map(value ->
                        member(value, observedAt)).toList();
                snapshot = universes.saveIfChanged(members, observedAt,
                        LocalDate.ofInstant(observedAt, MARKET_ZONE),
                        response.sourceFingerprint(), gitCommit);
            }
            if (snapshot == null) {
                throw invalid("MAINBOARD_UNIVERSE_SNAPSHOT_MISSING");
            }
            List<MainboardInstrument> providerMembers = snapshot.members()
                    .stream().map(TushareMainboardUniverseCaptureService
                            ::providerMember).toList();
            for (LocalDate date : missingTradeDates.stream().sorted().toList()) {
                var response = provider.fetchMainboardMarketDate(
                        providerMembers, date, timeout, session);
                CaptureResult result = capture
                        .captureAuthorizedMainboardResponse(response,
                                clock.instant(), preProvider);
                batches.add(result.batchId());
                appended += result.appendedCount();
                idempotent += result.idempotentCount();
            }
            if (refreshCalendar) {
                for (String exchange : List.of("SSE", "SZSE")) {
                    MainboardInstrument representative = providerMembers
                            .stream().filter(value -> value.exchange()
                                    .equals(exchange)).findFirst()
                            .orElseThrow(() -> invalid(
                                    "MAINBOARD_EXCHANGE_MEMBER_MISSING"));
                    var response = provider.fetchMainboardCalendar(
                            representative, exchange, calendarStart,
                            calendarEnd, timeout, session);
                    CaptureResult result = capture
                            .captureAuthorizedMainboardResponse(response,
                                    clock.instant(), preProvider);
                    batches.add(result.batchId());
                    appended += result.appendedCount();
                    idempotent += result.idempotentCount();
                }
            }
            if (session.consumedBusinessRequests()
                    != session.maximumBusinessRequests()) {
                throw invalid("MAINBOARD_PROVIDER_BUDGET_MISMATCH");
            }
            return new CaptureEvidence(snapshot,
                    session.consumedBusinessRequests(), 0, batches,
                    appended, idempotent, clock.instant());
        } catch (RuntimeException failure) {
            throw new CaptureFailure(safeCode(failure),
                    session.consumedBusinessRequests(), failure);
        }
    }

    private static Member member(
            MainboardInstrument value,
            Instant observedAt
    ) {
        String normalized = value.name().replace(" ", "")
                .toUpperCase(Locale.ROOT);
        boolean st = normalized.startsWith("ST")
                || normalized.startsWith("*ST")
                || normalized.startsWith("S*ST");
        return new Member(value.tsCode(), value.symbol(), value.exchange(),
                value.name(), value.industry(), value.market(),
                value.listStatus(), value.listDate(), value.delistDate(),
                observedAt, ResearchUniverseMainboard.SOURCE,
                value.contentHash(), st);
    }

    private static MainboardInstrument providerMember(Member value) {
        return new MainboardInstrument(value.tsCode(), value.symbol(),
                value.exchange(), value.name(), value.industry(),
                value.market(), value.listStatus(), value.listDate(),
                value.delistDate(), value.contentHash());
    }

    static String safeCode(Throwable error) {
        for (Throwable current = error; current != null;
             current = current.getCause()) {
            if (current instanceof TushareApiGateway.GatewayException gateway
                    && validCode(gateway.safeCode())) {
                return gateway.safeCode();
            }
            String message = current.getMessage();
            if (validCode(message)) {
                return message;
            }
        }
        return "MAINBOARD_CAPTURE_FAILED";
    }

    private static boolean validCode(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{3,127}");
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    public record CaptureEvidence(
            SnapshotBundle snapshot,
            int providerCallCount,
            int retryCount,
            List<Long> batchIds,
            int appendedObservations,
            int idempotentChainTailHits,
            Instant completedAt
    ) {
        public CaptureEvidence {
            batchIds = List.copyOf(batchIds);
        }
    }

    public static final class CaptureFailure extends IllegalStateException {
        private final int providerCallCount;

        private CaptureFailure(String reason, int providerCallCount,
                               RuntimeException cause) {
            super(reason, cause);
            this.providerCallCount = providerCallCount;
        }

        public int providerCallCount() {
            return providerCallCount;
        }
    }
}
