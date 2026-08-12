package com.stockquant.server.agent.marketfacts;

import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.KnowledgeMode;
import com.stockquant.core.research.StrategyResearchModels.ResearchDataset;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.FormulaOnlyQfqBar;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchDataModels.SecurityDataset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only bridge from the accepted M1 dataset into the M2 engine contract. */
public final class TushareM2StrategyResearchDatasetAdapter {
    private final TushareM1ResearchDatasetService m1DatasetService;

    public TushareM2StrategyResearchDatasetAdapter(
            TushareM1ResearchDatasetService m1DatasetService
    ) {
        this.m1DatasetService = Objects.requireNonNull(
                m1DatasetService, "m1DatasetService");
    }

    public AdaptedDataset load(
            TushareM1ResearchWindowCommand command,
            Instant knowledgeCutoff
    ) {
        TushareM1ResearchDataModels.ResearchDataset m1 =
                m1DatasetService.loadAndVerify(command, knowledgeCutoff);
        return adapt(m1);
    }

    public static AdaptedDataset adapt(
            TushareM1ResearchDataModels.ResearchDataset m1
    ) {
        Objects.requireNonNull(m1, "m1");
        if (!m1.m2Readable() || !m1.typedFactReadbackPassed()
                || !m1.systemKnowledgeReadbackPassed()
                || !m1.formulaOnlyQfq() || m1.fullQfqLineageClaimed()
                || !m1.dataQualityPassed()
                || !m1.noFutureDataLeakage()) {
            throw invalid("M2_M1_DATASET_NOT_ELIGIBLE");
        }
        Map<LocalDate, Set<String>> exchangesByDate = new LinkedHashMap<>();
        for (LocalDate date = m1.rangeStart(); !date.isAfter(m1.rangeEnd());
                date = date.plusDays(1)) {
            exchangesByDate.put(date, new LinkedHashSet<>());
        }
        List<DailyBar> bars = new ArrayList<>();
        StringBuilder lineage = new StringBuilder();
        List<SecurityDataset> orderedSecurities = m1.securities().stream()
                .sorted(Comparator.comparing(SecurityDataset::exchange)
                        .thenComparing(SecurityDataset::symbol))
                .toList();
        for (SecurityDataset security : orderedSecurities) {
            Security identity = new Security(security.symbol(),
                    security.exchange());
            for (FormulaOnlyQfqBar value : security.qfqBars().stream()
                    .sorted(Comparator.comparing(
                            FormulaOnlyQfqBar::tradeDate)).toList()) {
                exchangesByDate.get(value.tradeDate()).add(
                        security.exchange());
                bars.add(new DailyBar(identity, value.tradeDate(),
                        value.open(), value.high(), value.low(), value.close(),
                        0L, true,
                        StrategyResearchModels.closeInstant(value.tradeDate()),
                        security.lastKnownAt()));
                lineage.append(identity.canonicalCode()).append(':')
                        .append(value.tradeDate()).append(':')
                        .append(value.rawObservationId()).append(':')
                        .append(value.factorObservationId()).append('\n');
            }
        }
        List<TradingSession> sessions = exchangesByDate.entrySet().stream()
                .map(value -> new TradingSession(value.getKey(), value.getValue()))
                .toList();
        String datasetVersion = "M1_TO_M2_" + sha256(lineage.toString());
        ResearchDataset dataset = new ResearchDataset(
                StrategyResearchModels.DATASET_CONTRACT, datasetVersion,
                KnowledgeMode.SYSTEM_KNOWLEDGE_RESEARCH,
                m1.knowledgeCutoff(), sessions, bars);
        return new AdaptedDataset(dataset, m1.contractVersion(),
                m1.totalRawDailyCount(), m1.totalAdjustmentFactorCount(),
                m1.totalCalendarCount(), m1.totalQfqBarCount(),
                m1.typedFactReadbackPassed(),
                m1.systemKnowledgeReadbackPassed(),
                m1.dataQualityPassed(), m1.noFutureDataLeakage(),
                !m1.fullQfqLineageClaimed());
    }

    public record AdaptedDataset(
            ResearchDataset dataset,
            String sourceContractVersion,
            int rawDailyCount,
            int adjustmentFactorCount,
            int calendarCount,
            int qfqBarCount,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean dataQuality,
            boolean noFutureDataLeakage,
            boolean formulaOnlyLineageLimitationDisclosed
    ) {
        public AdaptedDataset {
            Objects.requireNonNull(dataset, "dataset");
            Objects.requireNonNull(sourceContractVersion,
                    "sourceContractVersion");
            if (!"M1_RESEARCH_DATASET_V1".equals(sourceContractVersion)
                    || rawDailyCount <= 0
                    || adjustmentFactorCount != rawDailyCount
                    || qfqBarCount != rawDailyCount
                    || calendarCount < rawDailyCount
                    || !typedFactReadback || !systemKnowledgeReadback
                    || !dataQuality || !noFutureDataLeakage
                    || !formulaOnlyLineageLimitationDisclosed) {
                throw invalid("M2_ADAPTED_DATASET_INVALID");
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("M2_SHA256_UNAVAILABLE", exception);
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
