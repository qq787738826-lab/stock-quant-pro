package com.stockquant.server.researchselection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldSemantic;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldUnit;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.QualifiedMarketField;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactEnvelope;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactRepository;
import com.stockquant.server.researchselection.ResearchSelectionModels.HistoricalAvailability;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Snapshot;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchUniverseMainboardStreamingDatasetTest {
    private static final LocalDate ANCHOR = LocalDate.of(2026, 8, 27);
    private static final Instant CUTOFF = Instant.parse(
            "2026-09-02T11:00:00Z");

    @Test
    void streamedBatchesAreNumericallyIdenticalToLegacyProjection() {
        List<LocalDate> sessions = sessions(250);
        List<Member> members = members(8);
        var repository = new SyntheticStreamingRepository(members, sessions);
        var loader = new ResearchUniverseMainboardDatasetLoader(repository);
        SnapshotBundle snapshot = snapshot(members);

        var loaded = loader.load(snapshot, ANCHOR, CUTOFF);
        List<ResearchUniverseMainboardDatasetLoader.MemberProjection>
                legacy = members.stream().map(member ->
                ResearchUniverseMainboardDatasetLoader.project(member,
                        sessions, repository.raw(member),
                        repository.factors(member), CUTOFF)).toList();
        List<DailyBar> expectedBars = legacy.stream()
                .flatMap(value -> value.bars().stream())
                .sorted(Comparator.comparing(DailyBar::security)
                        .thenComparing(DailyBar::tradeDate)).toList();
        List<TradingSession> tradingSessions = sessions.stream().map(date ->
                new TradingSession(date, Set.of("SSE", "SZSE"))).toList();
        ResearchDataset legacyDataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT, "LEGACY_FIXTURE",
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH, CUTOFF,
                tradingSessions, expectedBars);

        assertEquals(expectedBars, loaded.dataset().bars());
        assertEquals(legacy.stream().map(value -> value.evaluation()).toList(),
                loaded.evaluations());
        assertEquals(legacy.stream().collect(LinkedHashMap::new,
                        (values, projection) -> {
                            if (!projection.priceBars().isEmpty()) {
                                values.put(projection.evaluation().member()
                                                .security(),
                                        projection.priceBars());
                            }
                        }, Map::putAll),
                loaded.tradePlanPrices());

        Map<Security, Member> metadata = members.stream().collect(
                java.util.stream.Collectors.toMap(Member::security,
                        value -> value));
        var ranking = new ResearchSelectionRankingService();
        assertEquals(ranking.rankExplained(legacyDataset, metadata),
                ranking.rankExplained(loaded.dataset(), metadata));
        assertEquals(List.of(HistoricalAvailability.AVAILABLE,
                        HistoricalAvailability.AVAILABLE,
                        HistoricalAvailability.AVAILABLE,
                        HistoricalAvailability.AVAILABLE),
                ResearchSelectionHistoricalDatasetLoader.coverage(sessions)
                        .stream().map(value -> value.status()).toList());
        assertTrue(loaded.dataset().bars().stream().allMatch(value ->
                !value.sourceKnownAt().isAfter(CUTOFF)
                        && !value.sourceKnownAt().isBefore(
                        value.marketCloseAvailableAt())));
        assertEquals(1, repository.rawBatchCalls);
        assertEquals(1, repository.factorBatchCalls);
        assertEquals(8, repository.maximumBatchSize);
    }

    @Test
    void fullMainboard250SessionDatasetEntersQuantitativeScanUnderTwoGiB() {
        Assumptions.assumeTrue(Boolean.getBoolean(
                "stockquant.streaming.memory.probe"));
        long maximumHeap = Runtime.getRuntime().maxMemory();
        assertTrue(maximumHeap <= 2_300L * 1024 * 1024,
                "probe must run with -Xmx2048m");
        List<LocalDate> sessions = sessions(250);
        List<Member> members = members(3_193);
        var repository = new SyntheticStreamingRepository(members, sessions);
        var loader = new ResearchUniverseMainboardDatasetLoader(repository);
        SnapshotBundle snapshot = snapshot(members);

        System.gc();
        resetPeakHeap();
        long gcBefore = garbageCollectionMillis();
        long started = System.nanoTime();
        var loaded = loader.load(snapshot, ANCHOR, CUTOFF);
        long preparingMillis = elapsedMillis(started);
        long preparingPeakHeap = peakHeapBytes();

        assertEquals(250, loaded.sessions().size());
        assertEquals(3_193, loaded.dataset().securities().size());
        assertEquals(3_193 * 250, loaded.dataset().bars().size());
        assertEquals(3_193, loaded.evaluations().stream().filter(value ->
                value.status() == ResearchUniverseMainboard.EligibilityStatus
                        .ELIGIBLE).count());
        assertEquals(50, repository.rawBatchCalls);
        assertEquals(50, repository.factorBatchCalls);
        assertTrue(repository.maximumBatchSize
                <= ResearchUniverseMainboardDatasetLoader.MEMBER_BATCH_SIZE);

        resetPeakHeap();
        long scanStarted = System.nanoTime();
        Map<Security, Member> metadata = members.stream().collect(
                java.util.stream.Collectors.toMap(Member::security,
                        value -> value));
        var ranking = new ResearchSelectionRankingService()
                .rankExplained(loaded.dataset(), metadata);
        long scanMillis = elapsedMillis(scanStarted);
        long scanPeakHeap = peakHeapBytes();
        long gcMillis = Math.max(0, garbageCollectionMillis() - gcBefore);
        long totalMillis = preparingMillis + scanMillis;

        assertEquals(3_193, ranking.scores().size());
        assertFalse(ranking.explanations().isEmpty());
        assertTrue(gcMillis < Math.max(1, totalMillis * 9 / 10),
                "GC time indicates heap thrashing");
        System.out.println("STREAMING_DATASET_UNIVERSE=3193");
        System.out.println("STREAMING_DATASET_SESSIONS=250");
        System.out.println("STREAMING_TEST_HEAP_LIMIT_BYTES=" + maximumHeap);
        System.out.println("STREAMING_PREPARING_DATA_MILLIS="
                + preparingMillis);
        System.out.println("STREAMING_PREPARING_PEAK_HEAP_BYTES="
                + preparingPeakHeap);
        System.out.println("STREAMING_QUANTITATIVE_SCAN_MILLIS="
                + scanMillis);
        System.out.println("STREAMING_SCAN_PEAK_HEAP_BYTES=" + scanPeakHeap);
        System.out.println("STREAMING_GC_MILLIS=" + gcMillis);
        System.out.println("STREAMING_QUANTITATIVE_SCAN=PASS");
    }

    private static SnapshotBundle snapshot(List<Member> members) {
        return new SnapshotBundle(new Snapshot(1,
                "MAINBOARD_STREAMING_FIXTURE", ResearchUniverseMainboard.VERSION,
                members.size(), Math.min(1_699, members.size()),
                Math.max(0, members.size() - 1_699), 0, CUTOFF, CUTOFF,
                ANCHOR, ResearchUniverseMainboard.SOURCE, "a".repeat(64),
                "b".repeat(64), "c".repeat(40)), members);
    }

    private static List<Member> members(int count) {
        List<Member> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            boolean sse = index < Math.min(1_699, count);
            int exchangeIndex = sse ? index : index - 1_699;
            String symbol = sse ? String.format("%06d", 600_000 + index)
                    : String.format("%06d", 1 + exchangeIndex);
            String exchange = sse ? "SSE" : "SZSE";
            result.add(new Member(symbol + (sse ? ".SH" : ".SZ"),
                    symbol, exchange, "样本" + index, "行业" + index % 12,
                    "主板", "L", LocalDate.of(2000, 1, 1), null, CUTOFF,
                    ResearchUniverseMainboard.SOURCE, "d".repeat(64), false));
        }
        return List.copyOf(result);
    }

    private static List<LocalDate> sessions(int count) {
        List<LocalDate> descending = new ArrayList<>(count);
        LocalDate date = ANCHOR;
        while (descending.size() < count) {
            if (date.getDayOfWeek().getValue() <= 5) {
                descending.add(date);
            }
            date = date.minusDays(1);
        }
        java.util.Collections.reverse(descending);
        return List.copyOf(descending);
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private static void resetPeakHeap() {
        ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(value -> value.getType() == MemoryType.HEAP)
                .forEach(MemoryPoolMXBean::resetPeakUsage);
    }

    private static long peakHeapBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(value -> value.getType() == MemoryType.HEAP)
                .mapToLong(value -> value.getPeakUsage().getUsed()).sum();
    }

    private static long garbageCollectionMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(value -> Math.max(0,
                        value.getCollectionTime())).sum();
    }

    private static final class SyntheticStreamingRepository
            extends PitMarketFactRepository {
        private static final BigDecimal VOLUME_VALUE = new BigDecimal(
                "1000000");
        private static final BigDecimal AMOUNT_VALUE = new BigDecimal(
                "20000000");
        private static final QualifiedMarketField VOLUME = field(VOLUME_VALUE,
                MarketFieldUnit.SHARES,
                MarketFieldSemantic.TRADED_VOLUME);
        private static final QualifiedMarketField AMOUNT = field(AMOUNT_VALUE,
                MarketFieldUnit.CNY, MarketFieldSemantic.TRADED_AMOUNT);
        private static final QualifiedMarketField TURNOVER = field(
                new BigDecimal("0.01"), MarketFieldUnit.RATIO,
                MarketFieldSemantic.TURNOVER_RATE);
        private final Map<String, Member> members;
        private final Map<String, Integer> memberIndexes;
        private final List<LocalDate> sessions;
        private int rawBatchCalls;
        private int factorBatchCalls;
        private int maximumBatchSize;

        private SyntheticStreamingRepository(
                List<Member> members,
                List<LocalDate> sessions
        ) {
            super(new JdbcTemplate(), new ObjectMapper());
            Map<String, Member> byCode = new LinkedHashMap<>();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (int index = 0; index < members.size(); index++) {
                byCode.put(members.get(index).tsCode(), members.get(index));
                indexes.put(members.get(index).tsCode(), index);
            }
            this.members = Map.copyOf(byCode);
            this.memberIndexes = Map.copyOf(indexes);
            this.sessions = sessions;
        }

        @Override
        public List<TradingCalendarObservation> findCalendarAsOf(
                String sourceCode,
                String sourceInstrumentId,
                String exchange,
                LocalDate from,
                LocalDate to,
                Instant cutoff
        ) {
            return sessions.stream().filter(date -> !date.isBefore(from)
                    && !date.isAfter(to)).map(date ->
                    new TradingCalendarObservation(envelope(
                            FactType.TRADING_CALENDAR, date), exchange, date,
                            true, "OPEN")).toList();
        }

        @Override
        public void streamRawBarsForSnapshotMembersAsOf(
                long snapshotDatabaseId,
                List<String> memberTsCodes,
                LocalDate from,
                LocalDate to,
                Instant cutoff,
                int fetchSize,
                Consumer<RawDailyBarObservation> consumer
        ) {
            rawBatchCalls++;
            maximumBatchSize = Math.max(maximumBatchSize,
                    memberTsCodes.size());
            for (String tsCode : memberTsCodes) {
                Member member = members.get(tsCode);
                int memberIndex = memberIndexes.get(tsCode);
                for (int day = 0; day < sessions.size(); day++) {
                    LocalDate date = sessions.get(day);
                    if (!date.isBefore(from) && !date.isAfter(to)) {
                        consumer.accept(raw(member, memberIndex, day, date));
                    }
                }
            }
        }

        @Override
        public void streamFactorsForSnapshotMembersAsOf(
                long snapshotDatabaseId,
                List<String> memberTsCodes,
                LocalDate from,
                LocalDate to,
                Instant cutoff,
                int fetchSize,
                Consumer<AdjustmentFactorObservation> consumer
        ) {
            factorBatchCalls++;
            maximumBatchSize = Math.max(maximumBatchSize,
                    memberTsCodes.size());
            for (String tsCode : memberTsCodes) {
                Member member = members.get(tsCode);
                for (int day = 0; day < sessions.size(); day++) {
                    LocalDate date = sessions.get(day);
                    if (!date.isBefore(from) && !date.isAfter(to)) {
                        consumer.accept(factor(member, day, date));
                    }
                }
            }
        }

        private Map<LocalDate, RawDailyBarObservation> raw(Member member) {
            Map<LocalDate, RawDailyBarObservation> result =
                    new LinkedHashMap<>();
            int memberIndex = memberIndexes.get(member.tsCode());
            for (int day = 0; day < sessions.size(); day++) {
                LocalDate date = sessions.get(day);
                result.put(date, raw(member, memberIndex, day, date));
            }
            return result;
        }

        private Map<LocalDate, AdjustmentFactorObservation> factors(
                Member member
        ) {
            Map<LocalDate, AdjustmentFactorObservation> result =
                    new LinkedHashMap<>();
            for (int day = 0; day < sessions.size(); day++) {
                LocalDate date = sessions.get(day);
                result.put(date, factor(member, day, date));
            }
            return result;
        }

        private static RawDailyBarObservation raw(
                Member member,
                int memberIndex,
                int day,
                LocalDate date
        ) {
            BigDecimal close = BigDecimal.valueOf(
                    1_000L + memberIndex * 2L + day, 2);
            return new RawDailyBarObservation(envelope(
                    FactType.RAW_DAILY_BAR, date), member.symbol(),
                    member.exchange(), date,
                    close.subtract(new BigDecimal("0.02")),
                    close.add(new BigDecimal("0.05")),
                    close.subtract(new BigDecimal("0.05")), close,
                    VOLUME, AMOUNT, TURNOVER);
        }

        private static AdjustmentFactorObservation factor(
                Member member,
                int day,
                LocalDate date
        ) {
            return new AdjustmentFactorObservation(envelope(
                    FactType.ADJUSTMENT_FACTOR, date), member.symbol(), date,
                    "QFQ_FACTOR", "DAILY_EXACT",
                    BigDecimal.ONE.add(BigDecimal.valueOf(day, 4)));
        }

        private static QualifiedMarketField field(
                BigDecimal value,
                MarketFieldUnit unit,
                MarketFieldSemantic semantic
        ) {
            return new QualifiedMarketField(value,
                    FieldQualification.PRESENT_VERIFIED, unit, semantic);
        }

        private static FactEnvelope envelope(FactType type, LocalDate date) {
            return new FactEnvelope(1, 1, type, type.contractVersion(),
                    type + "|FIXTURE|" + date, 1, null, "TUSHARE_PRO",
                    "FIXTURE", "FIXTURE", null, null, null, null, CUTOFF,
                    CUTOFF, CUTOFF, "e".repeat(64), "1",
                    RevisionQualification.SYSTEM_KNOWLEDGE_ONLY,
                    AssuranceLevel.SYSTEM_KNOWLEDGE_PIT,
                    UsageQualification.RESEARCH_ONLY, false, true, true,
                    true, true, null);
        }
    }
}
