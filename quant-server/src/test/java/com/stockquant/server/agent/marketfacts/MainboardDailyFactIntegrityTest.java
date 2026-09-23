package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.AdjustmentFactorObservation;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactEnvelope;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.RawDailyBarObservation;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Member;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.Snapshot;
import com.stockquant.server.researchselection.ResearchUniverseMainboard.SnapshotBundle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainboardDailyFactIntegrityTest {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 28);
    private static final Instant CUTOFF = Instant.parse(
            "2026-09-21T08:30:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SnapshotBundle SNAPSHOT = snapshot();

    @Test
    void classifiesCompleteMissingAndOneSidedPartial() {
        List<RawDailyBarObservation> daily = daily(950);
        List<AdjustmentFactorObservation> factors = factors(950);

        var complete = evaluate(daily, factors);
        assertEquals(MainboardDailyFactIntegrity.Status.COMPLETE,
                complete.status());
        assertTrue(complete.complete());
        assertEquals(950, complete.dailySecurityCount());
        assertEquals(950, complete.factorSecurityCount());

        var missing = evaluate(List.of(), List.of());
        assertEquals(MainboardDailyFactIntegrity.Status.MISSING,
                missing.status());
        assertTrue(missing.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_DAILY_FACT_MISSING"));
        assertTrue(missing.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_ADJ_FACTOR_MISSING"));

        var dailyOnly = evaluate(daily, List.of());
        assertEquals(MainboardDailyFactIntegrity.Status.PARTIAL,
                dailyOnly.status());
        assertTrue(dailyOnly.dailySideComplete());
        assertFalse(dailyOnly.factorSideComplete());

        var factorOnly = evaluate(List.of(), factors);
        assertEquals(MainboardDailyFactIntegrity.Status.PARTIAL,
                factorOnly.status());
        assertFalse(factorOnly.dailySideComplete());
        assertTrue(factorOnly.factorSideComplete());
    }

    @Test
    void duplicateCanNeverBeCompleteAndUsesDailyIncrementReasonCode() {
        List<RawDailyBarObservation> duplicate = new ArrayList<>(daily(950));
        duplicate.add(raw(0, 10_000));
        var value = evaluate(duplicate, factors(950));

        assertFalse(value.complete());
        assertEquals(1, value.duplicateCount());
        assertTrue(value.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_DAILY_DUPLICATE"));
    }

    @Test
    void factorDuplicateCanNeverBeComplete() {
        List<AdjustmentFactorObservation> duplicate = new ArrayList<>(
                factors(950));
        duplicate.add(factor(0, 40_000, DATE));

        var value = evaluate(daily(950), duplicate);

        assertFalse(value.complete());
        assertEquals(1, value.factorDuplicateCount());
        assertTrue(value.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_FACTOR_DUPLICATE"));
    }

    @Test
    void incompleteCoverageAndWrongTradeDatesAreNeverComplete() {
        var incomplete = evaluate(daily(949), factors(949));
        assertEquals(MainboardDailyFactIntegrity.Status.MISSING,
                incomplete.status());
        assertTrue(incomplete.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_COVERAGE_INCOMPLETE"));

        List<AdjustmentFactorObservation> mismatched = new ArrayList<>(
                factors(950));
        mismatched.set(949, factor(950, 70_000, DATE));
        var mismatchedSets = evaluate(daily(950), mismatched);
        assertEquals(MainboardDailyFactIntegrity.Status.MISSING,
                mismatchedSets.status());
        assertFalse(mismatchedSets.securitySetsEqual());
        assertTrue(mismatchedSets.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_FACT_ALIGNMENT_INVALID"));

        List<RawDailyBarObservation> wrongDaily = new ArrayList<>(daily(950));
        wrongDaily.set(0, raw(0, 50_000, DATE.minusDays(1)));
        List<AdjustmentFactorObservation> wrongFactors = new ArrayList<>(
                factors(950));
        wrongFactors.set(0, factor(0, 60_000, DATE.minusDays(1)));
        var wrongDate = evaluate(wrongDaily, wrongFactors);

        assertEquals(MainboardDailyFactIntegrity.Status.MISSING,
                wrongDate.status());
        assertFalse(wrongDate.dailyAligned());
        assertFalse(wrongDate.factorAligned());
        assertTrue(wrongDate.reasonCodes().contains(
                "MAINBOARD_DAILY_INCREMENT_FACT_ALIGNMENT_INVALID"));
    }

    @Test
    void dailyIncrementStillDelegatesToTheSameIntegrityContract()
            throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "MainboardDailyIncrementService.java"));
        assertTrue(source.contains("MainboardDailyFactIntegrity.evaluate"));
        assertTrue(source.contains("requireDailyIncrementComplete()"));
    }

    private static MainboardDailyFactIntegrity.Evaluation evaluate(
            List<RawDailyBarObservation> daily,
            List<AdjustmentFactorObservation> factors
    ) {
        return MainboardDailyFactIntegrity.evaluate(SNAPSHOT, DATE, daily,
                factors, CUTOFF.minusSeconds(60), false, CUTOFF);
    }

    private static SnapshotBundle snapshot() {
        List<Member> members = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            String exchange = index < 500 ? "SSE" : "SZSE";
            String symbol = index < 500
                    ? String.format("%06d", 600000 + index)
                    : String.format("%06d", index - 499);
            members.add(new Member(symbol + (exchange.equals("SSE")
                    ? ".SH" : ".SZ"), symbol, exchange, "N" + index,
                    "I", "MAIN", "L", LocalDate.of(2000, 1, 1), null,
                    CUTOFF.minusSeconds(120), "TEST", "HASH" + index,
                    false));
        }
        Snapshot snapshot = new Snapshot(1, "SNAPSHOT",
                "RESEARCH_UNIVERSE_MAINBOARD_V1", 1_000, 500, 500, 0,
                CUTOFF.minusSeconds(120), CUTOFF.minusSeconds(120), DATE,
                "TEST", "SOURCE", "MEMBERS",
                "0123456789abcdef0123456789abcdef01234567");
        return new SnapshotBundle(snapshot, members);
    }

    private static List<RawDailyBarObservation> daily(int count) {
        List<RawDailyBarObservation> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(raw(index, index + 1L));
        }
        return List.copyOf(result);
    }

    private static List<AdjustmentFactorObservation> factors(int count) {
        List<AdjustmentFactorObservation> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(factor(index, index + 20_000L, DATE));
        }
        return List.copyOf(result);
    }

    private static AdjustmentFactorObservation factor(
            int memberIndex,
            long id,
            LocalDate date
    ) {
        Member member = SNAPSHOT.members().get(memberIndex);
        return new AdjustmentFactorObservation(
                envelope(id, FactType.ADJUSTMENT_FACTOR,
                        "ADJUSTMENT_FACTOR|" + member.symbol()),
                member.symbol(), date, PitMarketFactsContracts.FACTOR_TYPE,
                PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                BigDecimal.ONE);
    }

    private static RawDailyBarObservation raw(int memberIndex, long id) {
        return raw(memberIndex, id, DATE);
    }

    private static RawDailyBarObservation raw(
            int memberIndex,
            long id,
            LocalDate date
    ) {
        Member member = SNAPSHOT.members().get(memberIndex);
        return new RawDailyBarObservation(
                envelope(id, FactType.RAW_DAILY_BAR,
                        "RAW_DAILY_BAR|" + member.symbol()),
                member.symbol(), member.exchange(), date, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                null, null, null);
    }

    private static FactEnvelope envelope(
            long id,
            FactType type,
            String naturalKey
    ) {
        return new FactEnvelope(id, 1, type, type.contractVersion(),
                naturalKey, 1, null, "TUSHARE_PRO", "TEST", "DATASET",
                null, null, null, null, CUTOFF.minusSeconds(30),
                CUTOFF.minusSeconds(30), CUTOFF.minusSeconds(30),
                "HASH" + id, "OBS" + id,
                RevisionQualification.PROVIDER_VERIFIED,
                AssuranceLevel.PROVIDER_PIT_VERIFIED,
                UsageQualification.RESEARCH_ONLY, true, true, true, true,
                true, MAPPER.createObjectNode());
    }
}
