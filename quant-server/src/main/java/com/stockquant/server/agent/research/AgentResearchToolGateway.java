package com.stockquant.server.agent.research;

import com.stockquant.core.research.StrategyResearchApi;
import com.stockquant.core.research.StrategyResearchModels.BacktestConfig;
import com.stockquant.core.research.StrategyResearchModels.BacktestRequest;
import com.stockquant.core.research.StrategyResearchModels.DailyBar;
import com.stockquant.core.research.StrategyResearchModels.ResearchResult;
import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;
import com.stockquant.core.research.StrategyResearchModels.TemporalSplit;
import com.stockquant.core.research.StrategyResearchModels.TradingSession;
import com.stockquant.core.research.StrategyResearchModels.WalkForwardPlan;
import com.stockquant.server.agent.research.AgentResearchDatasetSource.LoadedDataset;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.DatasetEvidence;
import com.stockquant.server.agent.research.AgentResearchModels.Evidence;
import com.stockquant.server.agent.research.AgentResearchModels.PortfolioAssessment;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import com.stockquant.server.agent.research.AgentResearchModels.RiskAssessment;
import com.stockquant.server.agent.research.AgentResearchModels.RiskLevel;
import com.stockquant.server.agent.research.AgentResearchModels.StrategyExperiment;
import com.stockquant.server.agent.research.AgentResearchModels.StrategyExperimentSet;
import com.stockquant.server.agent.research.AgentResearchModels.StrategyRisk;
import com.stockquant.server.agent.research.AgentResearchModels.TechnicalSnapshot;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCall;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fixed deterministic tool gateway. Model output cannot supply market facts or
 * backtest metrics through this boundary.
 */
public final class AgentResearchToolGateway {
    private static final BigDecimal SQRT_TRADING_DAYS = new BigDecimal(
            "15.8745078663875");

    private final AgentResearchDatasetSource datasetSource;
    private final StrategyResearchApi researchApi;
    private final BacktestConfig backtestConfig;
    private final Clock clock;

    public AgentResearchToolGateway(
            AgentResearchDatasetSource datasetSource,
            StrategyResearchApi researchApi,
            BacktestConfig backtestConfig,
            Clock clock
    ) {
        this.datasetSource = Objects.requireNonNull(datasetSource,
                "datasetSource");
        this.researchApi = Objects.requireNonNull(researchApi, "researchApi");
        this.backtestConfig = Objects.requireNonNull(backtestConfig,
                "backtestConfig");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Session open(ResearchTask task) {
        return new Session(Objects.requireNonNull(task, "task"));
    }

    public final class Session {
        private final ResearchTask task;
        private final Set<ToolCode> completed = EnumSet.noneOf(ToolCode.class);
        private final List<ToolCall> calls = new ArrayList<>();
        private final List<Evidence> evidence = new ArrayList<>();

        private Session(ResearchTask task) {
            this.task = task;
        }

        public DatasetToolResult inspectDataset() {
            begin(ToolCode.RESEARCH_DATASET);
            LoadedDataset loaded = datasetSource.load(task);
            String fingerprint = AgentResearchCanonical.sha256(Map.of(
                    "dataset", loaded.dataset(),
                    "sourceContract", loaded.sourceContractVersion(),
                    "rawDailyCount", loaded.rawDailyCount(),
                    "adjustmentFactorCount", loaded.adjustmentFactorCount(),
                    "calendarCount", loaded.calendarCount(),
                    "qfqBarCount", loaded.qfqBarCount()));
            int openSessions = Math.toIntExact(loaded.dataset().sessions()
                    .stream().filter(value -> value.anyOpen()).count());
            DatasetEvidence result = new DatasetEvidence(
                    loaded.sourceContractVersion(),
                    loaded.dataset().datasetVersion(), fingerprint,
                    loaded.dataset().knowledgeMode().name(),
                    loaded.dataset().knowledgeCutoff(), task.rangeStart(),
                    task.rangeEnd(), loaded.dataset().securities().size(),
                    openSessions, loaded.rawDailyCount(),
                    loaded.adjustmentFactorCount(), loaded.calendarCount(),
                    loaded.qfqBarCount(), loaded.typedFactReadback(),
                    loaded.systemKnowledgeReadback(), loaded.dataQuality(),
                    loaded.noFutureDataLeakage(), loaded.formulaOnlyQfq(),
                    loaded.providerPitVerified());
            Evidence item = evidence(ToolCode.RESEARCH_DATASET, fingerprint,
                    "The accepted M1 dataset passed typed-fact, "
                            + "SYSTEM_KNOWLEDGE, data-quality, formula-only "
                            + "QFQ, and no-future-data checks for "
                            + result.securityCount() + " securities and "
                            + result.openSessionCount() + " open sessions; "
                            + "provider PIT lineage is "
                            + result.providerPitVerified() + ".");
            complete(ToolCode.RESEARCH_DATASET, AgentRole.DATA_ANALYST,
                    task, result);
            return new DatasetToolResult(loaded, result, List.of(item));
        }

        public TechnicalToolResult analyzeTechnical(LoadedDataset loaded) {
            beginAfter(ToolCode.MARKET_TECHNICAL,
                    ToolCode.RESEARCH_DATASET);
            List<TechnicalSnapshot> snapshots = loaded.dataset()
                    .barsBySecurity().entrySet().stream()
                    .map(value -> technical(value.getKey(), value.getValue()))
                    .toList();
            List<Evidence> items = snapshots.stream().map(value -> evidence(
                    ToolCode.MARKET_TECHNICAL, value.fingerprint(),
                    "For " + value.security().canonicalCode()
                            + ", deterministic QFQ analysis used "
                            + value.observationCount() + " observations; "
                            + "window return=" + value.windowReturn()
                            + ", short momentum=" + value.shortMomentum()
                            + ", annualized volatility="
                            + value.annualizedVolatility() + ", trend="
                            + value.trend() + ".")).toList();
            String fingerprint = AgentResearchCanonical.sha256(snapshots);
            complete(ToolCode.MARKET_TECHNICAL,
                    AgentRole.MARKET_TECHNICAL, loaded.dataset(), snapshots);
            return new TechnicalToolResult(snapshots, items, fingerprint);
        }

        public StrategyToolResult compareStrategies(LoadedDataset loaded) {
            beginAfter(ToolCode.STRATEGY_COMPARE,
                    ToolCode.RESEARCH_DATASET);
            List<StrategyExperiment> experiments = new ArrayList<>();
            for (StrategySpec strategy : task.strategies()) {
                ResearchResult result = researchApi.backtest(
                        new BacktestRequest(loaded.dataset(), strategy,
                                backtestConfig, task.rangeStart(),
                                task.rangeEnd()), task.benchmark());
                var backtest = result.strategyResult();
                BigDecimal maximumWeight = backtest.endingPositions().stream()
                        .map(value -> value.portfolioWeight().abs())
                        .max(Comparator.naturalOrder())
                        .orElse(BigDecimal.ZERO);
                OutOfSampleResult outOfSample = outOfSample(
                        loaded, strategy);
                experiments.add(new StrategyExperiment(
                        backtest.strategy().strategyCode(),
                        backtest.strategy().strategyVersion(),
                        backtest.strategy().parameters(),
                        backtest.deterministicFingerprint(),
                        backtest.metrics().finalEquity(),
                        backtest.metrics().pnl(),
                        backtest.metrics().totalReturn(),
                        backtest.metrics().cagr(),
                        backtest.metrics().annualizedReturn(),
                        backtest.metrics().annualizedVolatility(),
                        backtest.metrics().sharpeRatio(),
                        backtest.metrics().winRate(),
                        backtest.metrics().turnover(),
                        backtest.metrics().maxDrawdown(),
                        result.benchmark().excessReturn(),
                        backtest.metrics().fillCount(),
                        backtest.endingPositions().size(), maximumWeight,
                        backtest.accounting().invariantPassed(),
                        backtest.lookAheadGuardPassed(),
                        outOfSample.evaluated(), outOfSample.trainReturn(),
                        outOfSample.testReturn(),
                        outOfSample.strictIsolation(),
                        outOfSample.walkForwardFolds(),
                        outOfSample.walkForwardOutOfSampleOnly(),
                        outOfSample.overfittingFlag()));
            }
            List<String> ranking = experiments.stream()
                    .sorted(Comparator.comparing(
                                    StrategyExperiment::sharpeRatio).reversed()
                            .thenComparing(StrategyExperiment::totalReturn,
                                    Comparator.reverseOrder())
                            .thenComparing(StrategyExperiment::maxDrawdown,
                                    Comparator.reverseOrder())
                            .thenComparing(StrategyExperiment::strategyCode))
                    .map(StrategyExperiment::strategyCode).toList();
            String fingerprint = AgentResearchCanonical.sha256(Map.of(
                    "experiments", experiments, "ranking", ranking));
            StrategyExperimentSet result = new StrategyExperimentSet(
                    experiments, ranking, fingerprint);
            List<Evidence> items = experiments.stream().map(value -> evidence(
                    ToolCode.STRATEGY_COMPARE, value.backtestFingerprint(),
                    "Deterministic M2 backtest for " + value.strategyCode()
                            + " produced total return=" + value.totalReturn()
                            + ", Sharpe=" + value.sharpeRatio()
                            + ", maximum drawdown=" + value.maxDrawdown()
                            + ", turnover=" + value.turnover()
                            + ", excess return=" + value.excessReturn()
                            + "; accounting and look-ahead guards passed="
                            + value.accountingInvariant() + "/"
                            + value.lookAheadGuard()
                            + ", out-of-sample evaluated="
                            + value.outOfSampleEvaluated()
                            + ", train return=" + value.trainReturn()
                            + ", test return=" + value.testReturn()
                            + ", walk-forward folds="
                            + value.walkForwardFolds()
                            + ", overfitting flag="
                            + value.overfittingFlag() + ".")).toList();
            complete(ToolCode.STRATEGY_COMPARE,
                    AgentRole.STRATEGY_RESEARCH, loaded.dataset(), result);
            return new StrategyToolResult(result, items);
        }

        public RiskToolResult assessRisk(StrategyExperimentSet experiments) {
            beginAfter(ToolCode.RISK_METRICS, ToolCode.STRATEGY_COMPARE);
            List<StrategyRisk> strategies = experiments.experiments().stream()
                    .map(Session::risk).toList();
            RiskLevel overall = strategies.stream().map(StrategyRisk::level)
                    .max(Comparator.comparingInt(Session::riskOrder))
                    .orElse(RiskLevel.UNKNOWN);
            boolean concentration = strategies.stream().allMatch(value ->
                    value.maximumPositionWeight().compareTo(
                            new BigDecimal("0.50")) <= 0);
            String fingerprint = AgentResearchCanonical.sha256(Map.of(
                    "strategies", strategies,
                    "concentrationControlled", concentration));
            RiskAssessment result = new RiskAssessment(overall, strategies,
                    true, true, concentration, fingerprint);
            List<Evidence> items = strategies.stream().map(value -> evidence(
                    ToolCode.RISK_METRICS, fingerprint,
                    "Risk policy classified " + value.strategyCode()
                            + " as " + value.level() + " with maximum "
                            + "drawdown=" + value.maxDrawdown()
                            + ", annualized volatility="
                            + value.annualizedVolatility()
                            + ", maximum position weight="
                            + value.maximumPositionWeight()
                            + ", high-return/high-drawdown flag="
                            + value.highReturnHighDrawdown() + ".")).toList();
            complete(ToolCode.RISK_METRICS, AgentRole.RISK,
                    experiments, result);
            return new RiskToolResult(result, items);
        }

        public PortfolioAssessment portfolio(
                StrategyExperimentSet experiments,
                RiskAssessment risk,
                DatasetEvidence dataset,
                boolean includeKnownLimitations
        ) {
            String preferred = experiments.ranking().get(0);
            RiskLevel preferredRisk = risk.strategies().stream()
                    .filter(value -> value.strategyCode().equals(preferred))
                    .findFirst().orElseThrow(() -> AgentResearchModels.invalid(
                            "M3_PREFERRED_STRATEGY_RISK_MISSING")).level();
            BigDecimal exposure = switch (preferredRisk) {
                case LOW -> new BigDecimal("0.75");
                case MODERATE -> new BigDecimal("0.50");
                case HIGH, UNKNOWN -> new BigDecimal("0.25");
            };
            BigDecimal confidence = switch (preferredRisk) {
                case LOW -> new BigDecimal("0.70");
                case MODERATE -> new BigDecimal("0.60");
                case HIGH, UNKNOWN -> new BigDecimal("0.45");
            };
            List<String> limitations = new ArrayList<>();
            if (!risk.concentrationControlled()) {
                limitations.add("CONCENTRATION_LIMIT");
            }
            if (includeKnownLimitations) {
                if (dataset.formulaOnlyQfq()) {
                    limitations.add("FORMULA_ONLY_QFQ_LINEAGE");
                }
                if (!dataset.providerPitVerified()) {
                    limitations.add("PROVIDER_PIT_NOT_VERIFIED");
                    confidence = confidence.min(new BigDecimal("0.55"));
                }
                if (experiments.experiments().stream().anyMatch(value ->
                        !value.outOfSampleEvaluated())) {
                    limitations.add("OUT_OF_SAMPLE_WINDOW_INSUFFICIENT");
                    confidence = confidence.min(new BigDecimal("0.40"));
                }
                if (experiments.experiments().stream().anyMatch(
                        StrategyExperiment::overfittingFlag)) {
                    limitations.add("OVERFITTING_RISK_DETECTED");
                    confidence = confidence.min(new BigDecimal("0.35"));
                }
                if (experiments.experiments().stream().anyMatch(value ->
                        value.totalReturn().compareTo(new BigDecimal("0.20"))
                                > 0
                                && value.maxDrawdown().abs().compareTo(
                                new BigDecimal("0.20")) >= 0)) {
                    limitations.add("HIGH_RETURN_HIGH_DRAWDOWN");
                    confidence = confidence.min(new BigDecimal("0.35"));
                }
            }
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("ranking", experiments.ranking());
            canonical.put("preferred", preferred);
            canonical.put("risk", preferredRisk);
            canonical.put("exposure", exposure);
            canonical.put("confidence", confidence);
            canonical.put("limitations", limitations);
            return new PortfolioAssessment(experiments.ranking(), preferred,
                    preferredRisk, exposure, confidence, limitations,
                    AgentResearchCanonical.sha256(canonical));
        }

        public List<ToolCall> calls() {
            return List.copyOf(calls);
        }

        public List<Evidence> evidence() {
            return List.copyOf(evidence);
        }

        private void begin(ToolCode tool) {
            if (completed.contains(tool)
                    || calls.size() >= task.limits().maxToolCalls()) {
                throw AgentResearchModels.invalid(
                        "M3_TOOL_BUDGET_OR_DUPLICATE");
            }
        }

        private void beginAfter(ToolCode tool, ToolCode dependency) {
            begin(tool);
            if (!completed.contains(dependency)) {
                throw AgentResearchModels.invalid("M3_TOOL_ORDER_INVALID");
            }
        }

        private void complete(
                ToolCode tool,
                AgentRole role,
                Object request,
                Object result
        ) {
            completed.add(tool);
            calls.add(new ToolCall("TC_" + String.format("%02d",
                    calls.size() + 1) + "_" + tool.name(), tool, role,
                    AgentResearchCanonical.sha256(request),
                    AgentResearchCanonical.sha256(result), "SUCCEEDED"));
        }

        private Evidence evidence(
                ToolCode tool,
                String sourceFingerprint,
                String statement
        ) {
            String suffix = AgentResearchCanonical.sha256Text(
                    tool + "|" + sourceFingerprint + "|" + statement)
                    .substring(0, 12);
            Evidence item = new Evidence("EV_" + tool.name() + "_" + suffix,
                    tool, sourceFingerprint, clock.instant(), statement);
            evidence.add(item);
            return item;
        }

        private TechnicalSnapshot technical(
                Security security,
                List<DailyBar> bars
        ) {
            if (bars.size() < 2) {
                throw AgentResearchModels.invalid(
                        "M3_TECHNICAL_HISTORY_INSUFFICIENT");
            }
            BigDecimal first = bars.get(0).close();
            BigDecimal last = bars.get(bars.size() - 1).close();
            BigDecimal windowReturn = ratio(last, first).subtract(
                    BigDecimal.ONE);
            int shortWindow = Math.min(5, bars.size() - 1);
            BigDecimal shortFirst = bars.get(bars.size() - shortWindow - 1)
                    .close();
            BigDecimal momentum = ratio(last, shortFirst).subtract(
                    BigDecimal.ONE);
            List<BigDecimal> returns = new ArrayList<>();
            for (int index = 1; index < bars.size(); index++) {
                returns.add(ratio(bars.get(index).close(),
                        bars.get(index - 1).close()).subtract(BigDecimal.ONE));
            }
            BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO,
                    BigDecimal::add).divide(BigDecimal.valueOf(returns.size()),
                    16, RoundingMode.HALF_EVEN);
            BigDecimal variance = returns.stream().map(value ->
                            value.subtract(mean).pow(2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(returns.size()), 16,
                            RoundingMode.HALF_EVEN);
            BigDecimal volatility = new BigDecimal(Math.sqrt(
                    variance.doubleValue())).multiply(SQRT_TRADING_DAYS)
                    .setScale(12, RoundingMode.HALF_EVEN);
            String trend;
            BigDecimal threshold = new BigDecimal("0.005");
            if (momentum.compareTo(threshold) > 0
                    && windowReturn.signum() > 0) {
                trend = "UP";
            } else if (momentum.compareTo(threshold.negate()) < 0
                    && windowReturn.signum() < 0) {
                trend = "DOWN";
            } else if (momentum.abs().compareTo(new BigDecimal("0.001"))
                    <= 0 && windowReturn.abs().compareTo(
                    new BigDecimal("0.001")) <= 0) {
                trend = "FLAT";
            } else {
                trend = "MIXED";
            }
            Map<String, Object> canonical = Map.of(
                    "security", security,
                    "observationCount", bars.size(),
                    "windowReturn", windowReturn,
                    "shortMomentum", momentum,
                    "annualizedVolatility", volatility,
                    "trend", trend);
            return new TechnicalSnapshot(security, bars.size(), windowReturn,
                    momentum, volatility, trend,
                    AgentResearchCanonical.sha256(canonical));
        }

        private static StrategyRisk risk(StrategyExperiment experiment) {
            BigDecimal drawdown = experiment.maxDrawdown().abs();
            RiskLevel level;
            if (drawdown.compareTo(new BigDecimal("0.20")) >= 0
                    || experiment.annualizedVolatility().compareTo(
                    new BigDecimal("0.40")) >= 0
                    || experiment.maximumPositionWeight().compareTo(
                    new BigDecimal("0.50")) > 0) {
                level = RiskLevel.HIGH;
            } else if (drawdown.compareTo(new BigDecimal("0.10")) >= 0
                    || experiment.annualizedVolatility().compareTo(
                    new BigDecimal("0.25")) >= 0
                    || experiment.maximumPositionWeight().compareTo(
                    new BigDecimal("0.35")) > 0) {
                level = RiskLevel.MODERATE;
            } else {
                level = RiskLevel.LOW;
            }
            boolean highReturnHighDrawdown = experiment.totalReturn()
                    .compareTo(new BigDecimal("0.20")) > 0
                    && drawdown.compareTo(new BigDecimal("0.20")) >= 0;
            List<String> reasons = new ArrayList<>();
            reasons.add("QUANTIFIED_DRAWDOWN");
            reasons.add("QUANTIFIED_VOLATILITY");
            reasons.add("QUANTIFIED_CONCENTRATION");
            if (highReturnHighDrawdown) {
                reasons.add("HIGH_RETURN_HIGH_DRAWDOWN");
            }
            return new StrategyRisk(experiment.strategyCode(), level,
                    experiment.maxDrawdown(),
                    experiment.annualizedVolatility(),
                    experiment.maximumPositionWeight(),
                    highReturnHighDrawdown, reasons);
        }

        private OutOfSampleResult outOfSample(
                LoadedDataset loaded,
                StrategySpec strategy
        ) {
            List<TradingSession> open = loaded.dataset().sessions().stream()
                    .filter(TradingSession::anyOpen).toList();
            if (open.size() < 80) {
                return OutOfSampleResult.notEvaluated();
            }
            int splitIndex = Math.max(40, open.size() * 2 / 3);
            if (open.size() - splitIndex < 20) {
                splitIndex = open.size() - 20;
            }
            TemporalSplit split = new TemporalSplit(
                    open.get(0).tradeDate(),
                    open.get(splitIndex - 1).tradeDate(),
                    open.get(splitIndex).tradeDate(),
                    open.get(open.size() - 1).tradeDate());
            var trainTest = researchApi.trainTest(loaded.dataset(), strategy,
                    backtestConfig, split, task.benchmark());
            int walkTrain = Math.min(80, open.size() - 20);
            var walkForward = researchApi.walkForward(loaded.dataset(),
                    strategy, backtestConfig,
                    new WalkForwardPlan(walkTrain, 20, 20, 3),
                    task.benchmark());
            BigDecimal trainReturn = trainTest.train().strategyResult()
                    .metrics().totalReturn();
            BigDecimal testReturn = trainTest.test().strategyResult()
                    .metrics().totalReturn();
            BigDecimal trainSharpe = trainTest.train().strategyResult()
                    .metrics().sharpeRatio();
            BigDecimal testSharpe = trainTest.test().strategyResult()
                    .metrics().sharpeRatio();
            boolean overfit = trainReturn.compareTo(
                    new BigDecimal("0.05")) > 0 && testReturn.signum() < 0
                    || trainReturn.subtract(testReturn).compareTo(
                    new BigDecimal("0.25")) > 0
                    || trainSharpe.compareTo(new BigDecimal("2.0")) > 0
                    && testSharpe.signum() < 0;
            return new OutOfSampleResult(true, trainReturn, testReturn,
                    trainTest.strictlyIsolated(), walkForward.folds().size(),
                    walkForward.outOfSampleOnly(), overfit);
        }

        private static int riskOrder(RiskLevel value) {
            return switch (value) {
                case LOW -> 0;
                case MODERATE -> 1;
                case HIGH -> 2;
                case UNKNOWN -> 3;
            };
        }
    }

    public record DatasetToolResult(
            LoadedDataset loaded,
            DatasetEvidence evidence,
            List<Evidence> citations
    ) {
    }

    public record TechnicalToolResult(
            List<TechnicalSnapshot> snapshots,
            List<Evidence> citations,
            String fingerprint
    ) {
    }

    public record StrategyToolResult(
            StrategyExperimentSet experiments,
            List<Evidence> citations
    ) {
    }

    public record RiskToolResult(
            RiskAssessment risk,
            List<Evidence> citations
    ) {
    }

    private record OutOfSampleResult(
            boolean evaluated,
            BigDecimal trainReturn,
            BigDecimal testReturn,
            boolean strictIsolation,
            int walkForwardFolds,
            boolean walkForwardOutOfSampleOnly,
            boolean overfittingFlag
    ) {
        private static OutOfSampleResult notEvaluated() {
            return new OutOfSampleResult(false, BigDecimal.ZERO,
                    BigDecimal.ZERO, false, 0, false, false);
        }
    }

    private static BigDecimal ratio(BigDecimal numerator,
                                    BigDecimal denominator) {
        return numerator.divide(denominator, 16, RoundingMode.HALF_EVEN);
    }
}
