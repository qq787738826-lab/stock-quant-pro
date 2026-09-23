package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import com.stockquant.server.researchselection.ResearchUniverseMainboardDatasetLoader;
import com.stockquant.server.researchselection.ResearchUniverseMainboardRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Fixed, provider-free audit of formal calendar and daily fact coverage. */
final class MainboardCatchupReadonlyAuditService {
    static final int NETWORK_RECOVERY_BUDGET = 0;
    private static final int MAXIMUM_CALENDAR_LOOKBACK_DAYS = 500;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 0);

    private final JdbcTemplate jdbc;
    private final PitMarketFactRepository facts;
    private final ResearchUniverseMainboardRepository universes;
    private final Clock clock;

    MainboardCatchupReadonlyAuditService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.facts = new PitMarketFactRepository(jdbc,
                Objects.requireNonNull(mapper, "mapper"));
        this.universes = new ResearchUniverseMainboardRepository(jdbc);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Outcome execute(int currentLedger, int ledgerLimit) {
        if (currentLedger < 0 || ledgerLimit < 1
                || currentLedger > ledgerLimit) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_LEDGER_INVALID");
        }
        String transactionReadOnly = jdbc.queryForObject(
                "SHOW transaction_read_only", String.class);
        if (!"on".equalsIgnoreCase(transactionReadOnly)) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_READ_ONLY_REQUIRED");
        }
        Instant cutoff = clock.instant();
        SnapshotBundle snapshot = universes.latest().orElseThrow(() ->
                invalid("MAINBOARD_CATCHUP_AUDIT_UNIVERSE_MISSING"));
        requireUniverse(snapshot);

        TradingCalendarObservation sseMax = latestCalendar("SSE", cutoff);
        TradingCalendarObservation szseMax = latestCalendar("SZSE", cutoff);
        requireCalendar(sseMax, "SSE", cutoff);
        requireCalendar(szseMax, "SZSE", cutoff);
        LocalDate completedBoundary = cutoff.atZone(MARKET_ZONE).toLocalTime()
                .isBefore(MARKET_CLOSE)
                ? cutoff.atZone(MARKET_ZONE).toLocalDate().minusDays(1)
                : cutoff.atZone(MARKET_ZONE).toLocalDate();
        LocalDate effectiveCalendarEnd = min(completedBoundary,
                min(sseMax.calendarDate(), szseMax.calendarDate()));
        LocalDate calendarStart = effectiveCalendarEnd.minusDays(
                MAXIMUM_CALENDAR_LOOKBACK_DAYS - 1L);
        List<TradingCalendarObservation> sseCalendar = calendar("SSE",
                calendarStart, effectiveCalendarEnd, cutoff);
        List<TradingCalendarObservation> szseCalendar = calendar("SZSE",
                calendarStart, effectiveCalendarEnd, cutoff);
        requireContinuousCalendar(sseCalendar, calendarStart,
                effectiveCalendarEnd, "SSE", cutoff);
        requireContinuousCalendar(szseCalendar, calendarStart,
                effectiveCalendarEnd, "SZSE", cutoff);
        List<LocalDate> allCommonOpen = commonOpen(sseCalendar,
                szseCalendar);
        if (allCommonOpen.isEmpty()) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_COMMON_OPEN_EMPTY");
        }
        LocalDate target = allCommonOpen.get(allCommonOpen.size() - 1);

        ResearchUniverseMainboardDatasetLoader loader =
                new ResearchUniverseMainboardDatasetLoader(facts);
        LocalDate latestComplete = loader.resolveAnchor(snapshot, cutoff,
                false);
        if (latestComplete.isAfter(target)) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_COMPLETE_DATE_INVALID");
        }
        MainboardDailyFactIntegrity.Evaluation anchor = integrity(snapshot,
                latestComplete, cutoff, cutoff);
        if (!anchor.complete()) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_COMPLETE_DATE_INVALID");
        }

        List<LocalDate> commonOpenDates = allCommonOpen.stream()
                .filter(date -> date.isAfter(latestComplete))
                .toList();
        List<RawDailyBarObservation> daily = commonOpenDates.isEmpty()
                ? List.of() : facts.findRawBarsForSnapshotAsOf(
                snapshot.snapshot().databaseId(), commonOpenDates.get(0),
                target, cutoff);
        List<AdjustmentFactorObservation> factors = commonOpenDates.isEmpty()
                ? List.of() : facts.findFactorsForSnapshotAsOf(
                snapshot.snapshot().databaseId(), commonOpenDates.get(0),
                target, cutoff);
        Map<LocalDate, List<RawDailyBarObservation>> dailyByDate = daily
                .stream().collect(Collectors.groupingBy(
                        RawDailyBarObservation::tradeDate,
                        LinkedHashMap::new, Collectors.toList()));
        Map<LocalDate, List<AdjustmentFactorObservation>> factorByDate =
                factors.stream().collect(Collectors.groupingBy(
                        AdjustmentFactorObservation
                                ::factorEffectiveTradeDate,
                        LinkedHashMap::new, Collectors.toList()));

        List<DateAudit> dates = new ArrayList<>();
        List<LocalDate> missing = new ArrayList<>();
        List<LocalDate> partial = new ArrayList<>();
        List<LocalDate> complete = new ArrayList<>();
        for (LocalDate date : commonOpenDates) {
            var evaluation = MainboardDailyFactIntegrity.evaluate(snapshot,
                    date, dailyByDate.getOrDefault(date, List.of()),
                    factorByDate.getOrDefault(date, List.of()), cutoff,
                    false, cutoff);
            DateAudit audit = DateAudit.from(date, evaluation);
            dates.add(audit);
            switch (evaluation.status()) {
                case COMPLETE -> complete.add(date);
                case PARTIAL -> partial.add(date);
                case MISSING -> missing.add(date);
            }
        }
        int plannedBaseCalls = Math.multiplyExact(missing.size(), 2);
        int projected = Math.addExact(currentLedger, Math.addExact(
                plannedBaseCalls, NETWORK_RECOVERY_BUDGET));
        return new Outcome(snapshot, sseMax.calendarDate(),
                szseMax.calendarDate(), target, latestComplete,
                commonOpenDates, dates, missing, partial, complete,
                anchor.dailySecurityCount(), currentLedger, ledgerLimit,
                plannedBaseCalls, NETWORK_RECOVERY_BUDGET, projected,
                projected <= ledgerLimit, true, cutoff);
    }

    private MainboardDailyFactIntegrity.Evaluation integrity(
            SnapshotBundle snapshot,
            LocalDate date,
            Instant startedAt,
            Instant completedAt
    ) {
        return MainboardDailyFactIntegrity.evaluate(snapshot, date,
                facts.findRawBarsForSnapshotAsOf(
                        snapshot.snapshot().databaseId(), date, date,
                        completedAt),
                facts.findFactorsForSnapshotAsOf(
                        snapshot.snapshot().databaseId(), date, date,
                        completedAt), startedAt, false, completedAt);
    }

    private TradingCalendarObservation latestCalendar(
            String exchange,
            Instant cutoff
    ) {
        return facts.findLatestCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity(exchange),
                exchange, cutoff).orElseThrow(() -> invalid(
                "MAINBOARD_CATCHUP_AUDIT_CALENDAR_MISSING"));
    }

    private List<TradingCalendarObservation> calendar(
            String exchange,
            LocalDate start,
            LocalDate end,
            Instant cutoff
    ) {
        return facts.findCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity(exchange),
                exchange, start, end, cutoff);
    }

    private static void requireContinuousCalendar(
            List<TradingCalendarObservation> values,
            LocalDate start,
            LocalDate end,
            String exchange,
            Instant cutoff
    ) {
        long expected = ChronoUnit.DAYS.between(start, end) + 1L;
        if (values.size() != expected) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_CALENDAR_INCOMPLETE");
        }
        LocalDate cursor = start;
        for (TradingCalendarObservation value : values) {
            if (!cursor.equals(value.calendarDate())) {
                throw invalid(
                        "MAINBOARD_CATCHUP_AUDIT_CALENDAR_INCOMPLETE");
            }
            requireCalendar(value, exchange, cutoff);
            cursor = cursor.plusDays(1);
        }
    }

    private static List<LocalDate> commonOpen(
            List<TradingCalendarObservation> sse,
            List<TradingCalendarObservation> szse
    ) {
        Set<LocalDate> sseOpen = sse.stream()
                .filter(TradingCalendarObservation::open)
                .map(TradingCalendarObservation::calendarDate)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<LocalDate> szseOpen = szse.stream()
                .filter(TradingCalendarObservation::open)
                .map(TradingCalendarObservation::calendarDate)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        sseOpen.retainAll(szseOpen);
        return sseOpen.stream().sorted().toList();
    }

    private static void requireCalendar(
            TradingCalendarObservation value,
            String exchange,
            Instant cutoff
    ) {
        JsonNode row = value.envelope().rawPayload().path("providerRow");
        String date = value.calendarDate().format(
                DateTimeFormatter.BASIC_ISO_DATE);
        if (!exchange.equals(value.exchange())
                || value.envelope().factType() != FactType.TRADING_CALENDAR
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                value.envelope().sourceCode())
                || !TushareMarketFactProvider.calendarSourceIdentity(
                exchange).equals(value.envelope().sourceInstrumentId())
                || value.envelope().knownAt().isAfter(cutoff)
                || value.envelope().firstObservedAt().isAfter(
                value.envelope().knownAt())
                || !value.envelope().historicalReplayAllowed()
                || !value.envelope().backtestAllowed()
                || !value.envelope().agentUseAllowed()
                || !"trade_cal".equals(value.envelope().rawPayload()
                .path("endpoint").asText())
                || !date.equals(row.path("cal_date").asText())
                || row.path("is_open").asInt(-1)
                != (value.open() ? 1 : 0)
                || !row.has("pretrade_date")
                || row.path("pretrade_date").asText().isBlank()) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_CALENDAR_INVALID");
        }
    }

    private static void requireUniverse(SnapshotBundle snapshot) {
        if (!ResearchUniverseMainboard.VERSION.equals(
                snapshot.snapshot().universeVersion())
                || snapshot.snapshot().memberCount()
                < ResearchUniverseMainboard.MINIMUM_PLAUSIBLE_MEMBER_COUNT
                || snapshot.members().size()
                != snapshot.snapshot().memberCount()) {
            throw invalid("MAINBOARD_CATCHUP_AUDIT_UNIVERSE_INVALID");
        }
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }

    record DateAudit(
            LocalDate tradeDate,
            MainboardDailyFactIntegrity.Status status,
            List<String> reasonCodes,
            int dailyRowCount,
            int adjustmentFactorRowCount,
            int dailySecurityCount,
            int adjustmentFactorSecurityCount,
            long activeSecurityCount,
            int duplicateCount,
            boolean securitySetsEqual,
            boolean dailyComplete,
            boolean adjustmentFactorComplete,
            boolean knownAtValid
    ) {
        DateAudit {
            reasonCodes = List.copyOf(reasonCodes);
        }

        static DateAudit from(
                LocalDate date,
                MainboardDailyFactIntegrity.Evaluation value
        ) {
            return new DateAudit(date, value.status(), value.reasonCodes(),
                    value.dailyRowCount(), value.factorRowCount(),
                    value.dailySecurityCount(), value.factorSecurityCount(),
                    value.activeMemberCount(), value.duplicateCount(),
                    value.securitySetsEqual(), value.dailySideComplete(),
                    value.factorSideComplete(), value.knownAtValid());
        }
    }

    record Outcome(
            SnapshotBundle snapshot,
            LocalDate calendarMaxSse,
            LocalDate calendarMaxSzse,
            LocalDate latestCommonCompletedOpenTradeDate,
            LocalDate latestCompleteTradeDate,
            List<LocalDate> commonOpenTradeDates,
            List<DateAudit> dateAudits,
            List<LocalDate> missingTradeDates,
            List<LocalDate> partialTradeDates,
            List<LocalDate> completeTradeDates,
            int factSecurityCount,
            int currentLedger,
            int ledgerLimit,
            int plannedBaseCalls,
            int networkRecoveryBudget,
            int projectedWorstCaseLedger,
            boolean projectedWithinLimit,
            boolean readOnlyTransaction,
            Instant auditedAt
    ) {
        Outcome {
            commonOpenTradeDates = List.copyOf(commonOpenTradeDates);
            dateAudits = List.copyOf(dateAudits);
            missingTradeDates = List.copyOf(missingTradeDates);
            partialTradeDates = List.copyOf(partialTradeDates);
            completeTradeDates = List.copyOf(completeTradeDates);
        }
    }
}
