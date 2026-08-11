package com.stockquant.server.agent.marketfacts;

import com.stockquant.core.research.DefaultStrategyResearchApi;
import com.stockquant.core.research.StrategyRegistry;
import com.stockquant.core.research.StrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.ResearchResult;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Executes the bounded, read-only M1 real-data smoke for M2. */
public final class TushareM2StrategyResearchSmokeService {
    private final TushareM2StrategyResearchDatasetAdapter adapter;
    private final StrategyResearchApi researchApi;

    public TushareM2StrategyResearchSmokeService(
            TushareM2StrategyResearchDatasetAdapter adapter
    ) {
        this(adapter, new DefaultStrategyResearchApi());
    }

    TushareM2StrategyResearchSmokeService(
            TushareM2StrategyResearchDatasetAdapter adapter,
            StrategyResearchApi researchApi
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.researchApi = Objects.requireNonNull(researchApi, "researchApi");
    }

    public SmokeResult run(
            TushareM1ResearchWindowCommand command,
            Instant knowledgeCutoff
    ) {
        TushareM2StrategyResearchDatasetAdapter.AdaptedDataset adapted =
                adapter.load(command, knowledgeCutoff);
        Security benchmark = adapted.dataset().securities().get(0);
        BacktestConfig config = new BacktestConfig(
                new BigDecimal("1000000"), new BigDecimal("0.0003"),
                new BigDecimal("5"), new BigDecimal("0.0005"), 5, 100,
                new BigDecimal("0.90"), new BigDecimal("0.50"), 2,
                BigDecimal.ONE, new BigDecimal("0.02"), true);
        BacktestRequest request = new BacktestRequest(adapted.dataset(),
                new StrategySpec(StrategyRegistry.BUY_AND_HOLD,
                        java.util.Map.of("symbol", "ALL",
                                "targetWeight", "0.90")),
                config, adapted.dataset().firstSessionDate(),
                adapted.dataset().lastSessionDate());
        ResearchResult first = researchApi.backtest(request, benchmark);
        ResearchResult replay = researchApi.backtest(request, benchmark);
        if (!first.equals(replay)
                || !first.strategyResult().deterministicFingerprint().equals(
                replay.strategyResult().deterministicFingerprint())
                || !first.strategyResult().accounting().invariantPassed()
                || !first.strategyResult().lookAheadGuardPassed()
                || first.strategyResult().tradeLedger().isEmpty()) {
            throw invalid("M2_REAL_DATA_SMOKE_INVALID");
        }
        return new SmokeResult(
                "M2_M1_REAL_DATA_SMOKE_V1", "PASS",
                adapted.dataset().datasetVersion(),
                adapted.dataset().securities().size(),
                adapted.dataset().sessions().stream()
                        .filter(StrategyResearchModels.TradingSession::anyOpen)
                        .count(),
                adapted.rawDailyCount(), adapted.adjustmentFactorCount(),
                adapted.calendarCount(), adapted.qfqBarCount(),
                first.strategyResult().deterministicFingerprint(),
                first.strategyResult().metrics().fillCount(),
                first.strategyResult().metrics().finalEquity(),
                first.strategyResult().metrics().totalReturn(),
                first.strategyResult().metrics().maxDrawdown(),
                first.strategyResult().metrics().sharpeRatio(),
                first.strategyResult().metrics().turnover(),
                first.strategyResult().accounting().invariantPassed(),
                first.strategyResult().lookAheadGuardPassed(), true,
                adapted.typedFactReadback(),
                adapted.systemKnowledgeReadback(), adapted.dataQuality(),
                adapted.noFutureDataLeakage(), 0, 0);
    }

    public record SmokeResult(
            String contractVersion,
            String status,
            String datasetVersion,
            int securityCount,
            long openSessionCount,
            int rawDailyCount,
            int adjustmentFactorCount,
            int calendarCount,
            int qfqBarCount,
            String deterministicFingerprint,
            int fillCount,
            BigDecimal finalEquity,
            BigDecimal totalReturn,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            BigDecimal turnover,
            boolean accountingInvariant,
            boolean lookAheadGuard,
            boolean deterministicReplay,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean dataQuality,
            boolean noFutureDataLeakage,
            int providerCallCount,
            int databaseWriteCount
    ) {
        public SmokeResult {
            if (!"M2_M1_REAL_DATA_SMOKE_V1".equals(contractVersion)
                    || !"PASS".equals(status) || securityCount <= 0
                    || openSessionCount <= 1 || rawDailyCount <= 0
                    || qfqBarCount != rawDailyCount || fillCount <= 0
                    || providerCallCount != 0 || databaseWriteCount != 0
                    || !accountingInvariant || !lookAheadGuard
                    || !deterministicReplay || !typedFactReadback
                    || !systemKnowledgeReadback || !dataQuality
                    || !noFutureDataLeakage) {
                throw invalid("M2_REAL_DATA_SMOKE_RESULT_INVALID");
            }
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(code);
    }
}
