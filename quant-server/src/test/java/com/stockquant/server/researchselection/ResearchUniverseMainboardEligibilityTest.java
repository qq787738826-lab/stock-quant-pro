package com.stockquant.server.researchselection;

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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchUniverseMainboardEligibilityTest {
    private static final List<LocalDate> SESSIONS = sessions();
    private static final Instant CUTOFF = Instant.parse(
            "2026-08-14T08:00:00Z");

    @Test
    void normalMemberIsEligibleAndProducesTwentyQfqBars() {
        var projection = project(member(false, LocalDate.of(2000, 1, 1)),
                normalRaw(new BigDecimal("20000000")), normalFactors());

        assertEquals(ResearchUniverseMainboard.EligibilityStatus.ELIGIBLE,
                projection.evaluation().status());
        assertTrue(projection.evaluation().exclusionReasons().isEmpty());
        assertEquals(20, projection.bars().size());
    }

    @Test
    void stNewListingSuspensionMissingFactsAndLowLiquidityAreExplicit() {
        assertReason(project(member(true, LocalDate.of(2000, 1, 1)),
                        normalRaw(new BigDecimal("20000000")),
                        normalFactors()),
                ResearchUniverseMainboard.ExclusionReason.ST_SECURITY);

        assertReason(project(member(false, SESSIONS.get(5)),
                        normalRaw(new BigDecimal("20000000")),
                        normalFactors()), ResearchUniverseMainboard
                        .ExclusionReason.LISTING_HISTORY_INSUFFICIENT);

        Map<LocalDate, RawDailyBarObservation> suspendedRaw = normalRaw(
                new BigDecimal("20000000"));
        Map<LocalDate, AdjustmentFactorObservation> suspendedFactors =
                normalFactors();
        suspendedRaw.remove(SESSIONS.get(19));
        suspendedFactors.remove(SESSIONS.get(19));
        var suspended = project(member(false, LocalDate.of(2000, 1, 1)),
                suspendedRaw, suspendedFactors);
        assertReason(suspended, ResearchUniverseMainboard.ExclusionReason
                .SUSPENDED_OR_NO_TRADE);
        assertReason(suspended, ResearchUniverseMainboard.ExclusionReason
                .DAILY_FACT_MISSING);
        assertReason(suspended, ResearchUniverseMainboard.ExclusionReason
                .ADJUSTMENT_FACTOR_MISSING);

        Map<LocalDate, RawDailyBarObservation> missingRaw = normalRaw(
                new BigDecimal("20000000"));
        Map<LocalDate, AdjustmentFactorObservation> missingFactors =
                normalFactors();
        missingRaw.remove(SESSIONS.get(10));
        missingFactors.remove(SESSIONS.get(10));
        var missing = project(member(false, LocalDate.of(2000, 1, 1)),
                missingRaw, missingFactors);
        assertReason(missing, ResearchUniverseMainboard.ExclusionReason
                .DAILY_FACT_MISSING);
        assertReason(missing, ResearchUniverseMainboard.ExclusionReason
                .ADJUSTMENT_FACTOR_MISSING);

        assertReason(project(member(false, LocalDate.of(2000, 1, 1)),
                        normalRaw(new BigDecimal("9999999")),
                        normalFactors()), ResearchUniverseMainboard
                        .ExclusionReason.EXTREMELY_LOW_LIQUIDITY);
    }

    @Test
    void futureKnowledgeAndPriceAnomalyFailClosed() {
        Map<LocalDate, RawDailyBarObservation> futureRaw = normalRaw(
                new BigDecimal("20000000"));
        LocalDate date = SESSIONS.get(19);
        futureRaw.put(date, raw(date, CUTOFF.plusSeconds(1),
                new BigDecimal("20000000"), false));
        assertReason(project(member(false, LocalDate.of(2000, 1, 1)),
                        futureRaw, normalFactors()), ResearchUniverseMainboard
                        .ExclusionReason.FUTURE_DATA_GUARD_FAILED);

        Map<LocalDate, RawDailyBarObservation> anomalous = normalRaw(
                new BigDecimal("20000000"));
        anomalous.put(date, raw(date, CUTOFF,
                new BigDecimal("20000000"), true));
        assertReason(project(member(false, LocalDate.of(2000, 1, 1)),
                        anomalous, normalFactors()), ResearchUniverseMainboard
                        .ExclusionReason.PRICE_OR_VOLUME_ANOMALY);
    }

    private static ResearchUniverseMainboardDatasetLoader.MemberProjection
    project(
            ResearchUniverseMainboard.Member member,
            Map<LocalDate, RawDailyBarObservation> raw,
            Map<LocalDate, AdjustmentFactorObservation> factors
    ) {
        return ResearchUniverseMainboardDatasetLoader.project(member,
                SESSIONS, raw, factors, CUTOFF);
    }

    private static void assertReason(
            ResearchUniverseMainboardDatasetLoader.MemberProjection value,
            ResearchUniverseMainboard.ExclusionReason reason
    ) {
        assertEquals(ResearchUniverseMainboard.EligibilityStatus.EXCLUDED,
                value.evaluation().status());
        assertTrue(value.evaluation().exclusionReasons().contains(reason));
        assertTrue(value.bars().isEmpty());
    }

    private static ResearchUniverseMainboard.Member member(
            boolean st,
            LocalDate listDate
    ) {
        return new ResearchUniverseMainboard.Member("600000.SH", "600000",
                "SSE", st ? "ST浦发" : "浦发银行", "银行", "主板", "L",
                listDate, null, CUTOFF,
                ResearchUniverseMainboard.SOURCE, "a".repeat(64), st);
    }

    private static Map<LocalDate, RawDailyBarObservation> normalRaw(
            BigDecimal amount
    ) {
        Map<LocalDate, RawDailyBarObservation> values = new LinkedHashMap<>();
        SESSIONS.forEach(date -> values.put(date,
                raw(date, CUTOFF, amount, false)));
        return values;
    }

    private static Map<LocalDate, AdjustmentFactorObservation>
    normalFactors() {
        Map<LocalDate, AdjustmentFactorObservation> values =
                new LinkedHashMap<>();
        SESSIONS.forEach(date -> values.put(date,
                new AdjustmentFactorObservation(envelope(
                        FactType.ADJUSTMENT_FACTOR, date, CUTOFF), "600000",
                        date, "QFQ_FACTOR", "DAILY_EXACT",
                        new BigDecimal("1.0000"))));
        return values;
    }

    private static RawDailyBarObservation raw(
            LocalDate date,
            Instant knownAt,
            BigDecimal amount,
            boolean anomalous
    ) {
        BigDecimal open = new BigDecimal("10.00");
        BigDecimal close = new BigDecimal("10.20");
        BigDecimal high = anomalous ? new BigDecimal("9.00")
                : new BigDecimal("10.50");
        BigDecimal low = new BigDecimal("9.80");
        return new RawDailyBarObservation(envelope(FactType.RAW_DAILY_BAR,
                date, knownAt), "600000", "SSE", date, open, high, low,
                close, field(new BigDecimal("1000000"),
                MarketFieldUnit.SHARES,
                MarketFieldSemantic.TRADED_VOLUME), field(amount,
                MarketFieldUnit.CNY, MarketFieldSemantic.TRADED_AMOUNT),
                field(BigDecimal.ZERO, MarketFieldUnit.RATIO,
                        MarketFieldSemantic.TURNOVER_RATE));
    }

    private static QualifiedMarketField field(
            BigDecimal value,
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new QualifiedMarketField(value,
                FieldQualification.PRESENT_VERIFIED, unit, semantic);
    }

    private static FactEnvelope envelope(
            FactType type,
            LocalDate date,
            Instant knownAt
    ) {
        return new FactEnvelope(1, 1, type, type.contractVersion(),
                type + "|600000|" + date, 1, null, "TUSHARE_PRO",
                "TEST", "TEST", null, null, null, null, knownAt, knownAt,
                knownAt, "b".repeat(64), "1",
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY,
                AssuranceLevel.SYSTEM_KNOWLEDGE_PIT,
                UsageQualification.RESEARCH_ONLY, false, true, true, true,
                true, null);
    }

    private static List<LocalDate> sessions() {
        java.util.ArrayList<LocalDate> values = new java.util.ArrayList<>();
        LocalDate date = LocalDate.of(2026, 7, 20);
        while (values.size() < 20) {
            if (date.getDayOfWeek().getValue() <= 5) values.add(date);
            date = date.plusDays(1);
        }
        return List.copyOf(values);
    }
}
