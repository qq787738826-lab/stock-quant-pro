package com.stockquant.server.agent.research;

import com.stockquant.core.research.StrategyResearchModels.Security;
import com.stockquant.core.research.StrategyResearchModels.StrategySpec;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Stable structured contracts for the bounded M3 research team. */
public final class AgentResearchModels {
    public static final String RUNTIME_VERSION = "AGENT_RUNTIME_V1";
    public static final String TEAM_VERSION = "AGENT_RESEARCH_TEAM_V1";
    public static final String TOOL_GATEWAY_VERSION = "AGENT_TOOL_GATEWAY_V1";
    public static final String EVAL_VERSION = "AGENT_EVAL_V1";
    public static final String REPORT_VERSION = "RESEARCH_REPORT_V1";
    public static final String MODEL_PROTOCOL_VERSION =
            "AGENT_MODEL_PROTOCOL_V1";
    public static final String REAL_MODEL = "gpt-5-mini-2025-08-07";

    private AgentResearchModels() {
    }

    public enum AgentRole {
        RESEARCH_COORDINATOR,
        DATA_ANALYST,
        MARKET_TECHNICAL,
        STRATEGY_RESEARCH,
        RISK,
        PORTFOLIO,
        CRITIC_REVIEW
    }

    public enum ClaimType {
        FACT,
        INFERENCE,
        HYPOTHESIS,
        RECOMMENDATION,
        UNKNOWN
    }

    public enum ToolCode {
        RESEARCH_DATASET,
        MARKET_TECHNICAL,
        STRATEGY_COMPARE,
        RISK_METRICS
    }

    public enum AgentRunStatus {
        COMPLETED,
        REVISED,
        INSUFFICIENT_EVIDENCE,
        REJECTED
    }

    public enum ResearchStatus {
        SUCCEEDED,
        INSUFFICIENT_EVIDENCE,
        FAILED_VALIDATION
    }

    public enum RiskLevel {
        LOW,
        MODERATE,
        HIGH,
        UNKNOWN
    }

    public enum DecisionCode {
        RESEARCH_PREFERENCE,
        NO_PREFERENCE,
        INSUFFICIENT_EVIDENCE
    }

    public enum CriticIssueCode {
        DATA_QUALITY_GAP,
        FUTURE_DATA_RISK,
        METRIC_MISMATCH,
        UNSUPPORTED_CLAIM,
        OVERFITTING_RISK,
        DRAWDOWN_UNDERSTATED,
        AGENT_CONFLICT,
        OVERCONFIDENCE,
        PIT_LINEAGE_LIMITATION,
        PROMPT_INJECTION_ATTEMPT
    }

    public record RuntimeLimits(
            int maxRounds,
            int maxToolCalls,
            int maxModelCalls,
            Duration timeout
    ) {
        public RuntimeLimits {
            Objects.requireNonNull(timeout, "timeout");
            if (maxRounds < 1 || maxRounds > 2
                    || maxToolCalls < 4 || maxToolCalls > 16
                    || maxModelCalls < 7 || maxModelCalls > 20
                    || timeout.compareTo(Duration.ofSeconds(5)) < 0
                    || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw invalid("M3_RUNTIME_LIMITS_INVALID");
            }
        }

        public static RuntimeLimits standard() {
            return new RuntimeLimits(2, 8, 16, Duration.ofSeconds(30));
        }
    }

    public record ResearchTask(
            String taskId,
            String objective,
            List<Security> securities,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            LocalDate anchorTradeDate,
            Instant knowledgeCutoff,
            Security benchmark,
            List<StrategySpec> strategies,
            RuntimeLimits limits
    ) {
        public ResearchTask {
            taskId = required(taskId, "taskId");
            objective = required(objective, "objective");
            Objects.requireNonNull(rangeStart, "rangeStart");
            Objects.requireNonNull(rangeEnd, "rangeEnd");
            Objects.requireNonNull(anchorTradeDate, "anchorTradeDate");
            Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
            Objects.requireNonNull(benchmark, "benchmark");
            Objects.requireNonNull(limits, "limits");
            if (!taskId.matches("M3TASK_[A-Z0-9_]{6,80}")
                    || objective.length() > 500
                    || objective.chars().anyMatch(value -> value == 0
                    || Character.isISOControl(value)
                    && !Character.isWhitespace(value))
                    || rangeEnd.isBefore(rangeStart)
                    || anchorTradeDate.isBefore(rangeStart)
                    || anchorTradeDate.isAfter(rangeEnd)) {
                throw invalid("M3_RESEARCH_TASK_INVALID");
            }
            securities = distinctSorted(securities, "securities");
            strategies = normalizeStrategies(strategies);
            if (securities.isEmpty() || securities.size() > 20
                    || !securities.contains(benchmark)
                    || strategies.size() < 2 || strategies.size() > 8) {
                throw invalid("M3_RESEARCH_TASK_SCOPE_INVALID");
            }
        }
    }

    public record DatasetEvidence(
            String contractVersion,
            String datasetVersion,
            String datasetFingerprint,
            String knowledgeMode,
            Instant knowledgeCutoff,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            int securityCount,
            int openSessionCount,
            int dailyBarCount,
            int adjustmentFactorCount,
            int calendarCount,
            int qfqBarCount,
            boolean typedFactReadback,
            boolean systemKnowledgeReadback,
            boolean dataQualityPassed,
            boolean noFutureDataLeakage,
            boolean formulaOnlyQfq,
            boolean providerPitVerified
    ) {
        public DatasetEvidence {
            contractVersion = required(contractVersion, "contractVersion");
            datasetVersion = required(datasetVersion, "datasetVersion");
            requireHash(datasetFingerprint, "M3_DATASET_FINGERPRINT_INVALID");
            knowledgeMode = required(knowledgeMode, "knowledgeMode");
            Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
            Objects.requireNonNull(rangeStart, "rangeStart");
            Objects.requireNonNull(rangeEnd, "rangeEnd");
            if (securityCount < 1 || openSessionCount < 2
                    || dailyBarCount < securityCount
                    || adjustmentFactorCount != dailyBarCount
                    || qfqBarCount != dailyBarCount
                    || calendarCount < openSessionCount
                    || !typedFactReadback || !systemKnowledgeReadback
                    || !dataQualityPassed || !noFutureDataLeakage) {
                throw invalid("M3_DATASET_EVIDENCE_INVALID");
            }
        }
    }

    public record TechnicalSnapshot(
            Security security,
            int observationCount,
            BigDecimal windowReturn,
            BigDecimal shortMomentum,
            BigDecimal annualizedVolatility,
            String trend,
            String fingerprint
    ) {
        public TechnicalSnapshot {
            Objects.requireNonNull(security, "security");
            Objects.requireNonNull(windowReturn, "windowReturn");
            Objects.requireNonNull(shortMomentum, "shortMomentum");
            Objects.requireNonNull(annualizedVolatility,
                    "annualizedVolatility");
            trend = required(trend, "trend");
            requireHash(fingerprint, "M3_TECHNICAL_FINGERPRINT_INVALID");
            if (observationCount < 2
                    || !Set.of("UP", "DOWN", "MIXED", "FLAT")
                    .contains(trend)) {
                throw invalid("M3_TECHNICAL_SNAPSHOT_INVALID");
            }
        }
    }

    public record StrategyExperiment(
            String strategyCode,
            String strategyVersion,
            Map<String, String> parameters,
            String backtestFingerprint,
            BigDecimal finalEquity,
            BigDecimal pnl,
            BigDecimal totalReturn,
            BigDecimal cagr,
            BigDecimal annualizedReturn,
            BigDecimal annualizedVolatility,
            BigDecimal sharpeRatio,
            BigDecimal winRate,
            BigDecimal turnover,
            BigDecimal maxDrawdown,
            BigDecimal excessReturn,
            int fillCount,
            int endingPositionCount,
            BigDecimal maximumPositionWeight,
            boolean accountingInvariant,
            boolean lookAheadGuard,
            boolean outOfSampleEvaluated,
            BigDecimal trainReturn,
            BigDecimal testReturn,
            boolean strictTrainTestIsolation,
            int walkForwardFolds,
            boolean walkForwardOutOfSampleOnly,
            boolean overfittingFlag
    ) {
        public StrategyExperiment {
            strategyCode = required(strategyCode, "strategyCode");
            strategyVersion = required(strategyVersion, "strategyVersion");
            parameters = Collections.unmodifiableSortedMap(new TreeMap<>(
                    Objects.requireNonNull(parameters, "parameters")));
            requireHash(backtestFingerprint,
                    "M3_BACKTEST_FINGERPRINT_INVALID");
            List.of(finalEquity, pnl, totalReturn, cagr, annualizedReturn,
                    annualizedVolatility, sharpeRatio, winRate, turnover,
                    maxDrawdown, excessReturn, maximumPositionWeight)
                    .forEach(value -> Objects.requireNonNull(value,
                            "experimentMetric"));
            Objects.requireNonNull(trainReturn, "trainReturn");
            Objects.requireNonNull(testReturn, "testReturn");
            if (fillCount < 0 || endingPositionCount < 0
                    || !accountingInvariant || !lookAheadGuard
                    || walkForwardFolds < 0
                    || outOfSampleEvaluated
                    && (!strictTrainTestIsolation || walkForwardFolds < 1
                    || !walkForwardOutOfSampleOnly)
                    || !outOfSampleEvaluated
                    && (strictTrainTestIsolation || walkForwardFolds != 0
                    || walkForwardOutOfSampleOnly || overfittingFlag)) {
                throw invalid("M3_STRATEGY_EXPERIMENT_INVALID");
            }
        }
    }

    public record StrategyExperimentSet(
            List<StrategyExperiment> experiments,
            List<String> ranking,
            String fingerprint
    ) {
        public StrategyExperimentSet {
            experiments = List.copyOf(Objects.requireNonNull(
                    experiments, "experiments"));
            ranking = List.copyOf(Objects.requireNonNull(ranking, "ranking"));
            requireHash(fingerprint, "M3_EXPERIMENT_SET_FINGERPRINT_INVALID");
            Set<String> codes = new LinkedHashSet<>();
            experiments.forEach(value -> codes.add(value.strategyCode()));
            if (experiments.size() < 2 || codes.size() != experiments.size()
                    || ranking.size() != experiments.size()
                    || !codes.equals(new LinkedHashSet<>(ranking))) {
                throw invalid("M3_STRATEGY_EXPERIMENT_SET_INVALID");
            }
        }
    }

    public record StrategyRisk(
            String strategyCode,
            RiskLevel level,
            BigDecimal maxDrawdown,
            BigDecimal annualizedVolatility,
            BigDecimal maximumPositionWeight,
            boolean highReturnHighDrawdown,
            List<String> reasonCodes
    ) {
        public StrategyRisk {
            strategyCode = required(strategyCode, "strategyCode");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(maxDrawdown, "maxDrawdown");
            Objects.requireNonNull(annualizedVolatility,
                    "annualizedVolatility");
            Objects.requireNonNull(maximumPositionWeight,
                    "maximumPositionWeight");
            reasonCodes = immutableText(reasonCodes, "reasonCodes");
        }
    }

    public record RiskAssessment(
            RiskLevel overallLevel,
            List<StrategyRisk> strategies,
            boolean accountingPassed,
            boolean lookAheadPassed,
            boolean concentrationControlled,
            String fingerprint
    ) {
        public RiskAssessment {
            Objects.requireNonNull(overallLevel, "overallLevel");
            strategies = List.copyOf(Objects.requireNonNull(
                    strategies, "strategies"));
            requireHash(fingerprint, "M3_RISK_FINGERPRINT_INVALID");
            if (strategies.isEmpty() || !accountingPassed
                    || !lookAheadPassed) {
                throw invalid("M3_RISK_ASSESSMENT_INVALID");
            }
        }
    }

    public record PortfolioAssessment(
            List<String> rankedStrategies,
            String preferredStrategy,
            RiskLevel preferredRisk,
            BigDecimal suggestedGrossExposure,
            BigDecimal confidenceCap,
            List<String> limitations,
            String fingerprint
    ) {
        public PortfolioAssessment {
            rankedStrategies = immutableText(rankedStrategies,
                    "rankedStrategies");
            preferredStrategy = required(preferredStrategy,
                    "preferredStrategy");
            Objects.requireNonNull(preferredRisk, "preferredRisk");
            Objects.requireNonNull(suggestedGrossExposure,
                    "suggestedGrossExposure");
            Objects.requireNonNull(confidenceCap, "confidenceCap");
            limitations = immutableText(limitations, "limitations");
            requireHash(fingerprint, "M3_PORTFOLIO_FINGERPRINT_INVALID");
            if (rankedStrategies.isEmpty()
                    || !rankedStrategies.contains(preferredStrategy)
                    || suggestedGrossExposure.signum() < 0
                    || suggestedGrossExposure.compareTo(BigDecimal.ONE) > 0
                    || confidenceCap.signum() < 0
                    || confidenceCap.compareTo(BigDecimal.ONE) > 0) {
                throw invalid("M3_PORTFOLIO_ASSESSMENT_INVALID");
            }
        }
    }

    public record Evidence(
            String evidenceId,
            ToolCode sourceTool,
            String sourceFingerprint,
            Instant observedAt,
            String statement
    ) {
        public Evidence {
            evidenceId = required(evidenceId, "evidenceId");
            Objects.requireNonNull(sourceTool, "sourceTool");
            requireHash(sourceFingerprint,
                    "M3_EVIDENCE_SOURCE_FINGERPRINT_INVALID");
            Objects.requireNonNull(observedAt, "observedAt");
            statement = required(statement, "statement");
            if (!evidenceId.matches("EV_[A-Z_]+_[0-9a-f]{12}")) {
                throw invalid("M3_EVIDENCE_ID_INVALID");
            }
        }
    }

    public record AgentFinding(
            String findingId,
            AgentRole agentRole,
            ClaimType claimType,
            String statement,
            List<String> evidenceIds,
            BigDecimal confidence
    ) {
        public AgentFinding {
            findingId = required(findingId, "findingId");
            Objects.requireNonNull(agentRole, "agentRole");
            Objects.requireNonNull(claimType, "claimType");
            statement = required(statement, "statement");
            evidenceIds = immutableText(evidenceIds, "evidenceIds");
            Objects.requireNonNull(confidence, "confidence");
            if (!findingId.matches("F_[A-Z_]+_[0-9]{2}")
                    || confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0
                    || claimType != ClaimType.UNKNOWN && evidenceIds.isEmpty()) {
                throw invalid("M3_AGENT_FINDING_INVALID");
            }
        }
    }

    public record ModelUsage(
            int inputTokens,
            int outputTokens,
            int reasoningTokens,
            int totalTokens,
            BigDecimal estimatedCost,
            String costCurrency
    ) {
        public ModelUsage(
                int inputTokens,
                int outputTokens,
                BigDecimal estimatedCost,
                String costCurrency
        ) {
            this(inputTokens, outputTokens, 0,
                    Math.addExact(inputTokens, outputTokens), estimatedCost,
                    costCurrency);
        }

        public ModelUsage {
            Objects.requireNonNull(estimatedCost, "estimatedCost");
            costCurrency = required(costCurrency, "costCurrency");
            if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0
                    || reasoningTokens > outputTokens
                    || totalTokens < 0
                    || (long) inputTokens + outputTokens != totalTokens
                    || estimatedCost.signum() < 0
                    || !costCurrency.matches("NONE|USD|CNY")
                    || ("NONE".equals(costCurrency)
                    && estimatedCost.signum() != 0)) {
                throw invalid("M3_MODEL_USAGE_INVALID");
            }
        }

        public static ModelUsage zero() {
            return new ModelUsage(0, 0, 0, 0, BigDecimal.ZERO, "NONE");
        }
    }

    public record AgentRun(
            String runId,
            AgentRole agentRole,
            String phase,
            AgentRunStatus status,
            String promptVersion,
            String modelProvider,
            String model,
            String inputFingerprint,
            List<ToolCode> requestedTools,
            List<AgentFinding> findings,
            List<CriticIssueCode> issueCodes,
            boolean reworkRequested,
            boolean revised,
            ModelUsage usage
    ) {
        public AgentRun {
            runId = required(runId, "runId");
            Objects.requireNonNull(agentRole, "agentRole");
            phase = required(phase, "phase");
            Objects.requireNonNull(status, "status");
            promptVersion = required(promptVersion, "promptVersion");
            modelProvider = required(modelProvider, "modelProvider");
            model = required(model, "model");
            requireHash(inputFingerprint, "M3_MODEL_INPUT_FINGERPRINT_INVALID");
            requestedTools = List.copyOf(requestedTools);
            findings = List.copyOf(findings);
            issueCodes = List.copyOf(issueCodes);
            Objects.requireNonNull(usage, "usage");
            if (!runId.matches("AR_[0-9]{2}_[A-Z_]+")) {
                throw invalid("M3_AGENT_RUN_ID_INVALID");
            }
        }
    }

    public record ToolCall(
            String callId,
            ToolCode toolCode,
            AgentRole requestedBy,
            String requestFingerprint,
            String resultFingerprint,
            String status
    ) {
        public ToolCall {
            callId = required(callId, "callId");
            Objects.requireNonNull(toolCode, "toolCode");
            Objects.requireNonNull(requestedBy, "requestedBy");
            requireHash(requestFingerprint,
                    "M3_TOOL_REQUEST_FINGERPRINT_INVALID");
            requireHash(resultFingerprint,
                    "M3_TOOL_RESULT_FINGERPRINT_INVALID");
            status = required(status, "status");
            if (!callId.matches("TC_[0-9]{2}_[A-Z_]+")
                    || !"SUCCEEDED".equals(status)) {
                throw invalid("M3_TOOL_CALL_INVALID");
            }
        }
    }

    public record CriticReview(
            List<CriticIssueCode> issues,
            List<String> challengedFindingIds,
            boolean reworkRequested,
            boolean correctionApplied,
            int reworkRounds
    ) {
        public CriticReview {
            issues = List.copyOf(issues);
            challengedFindingIds = immutableText(challengedFindingIds,
                    "challengedFindingIds");
            if (reworkRounds < 0 || reworkRounds > 1
                    || correctionApplied && !reworkRequested) {
                throw invalid("M3_CRITIC_REVIEW_INVALID");
            }
        }
    }

    public record AgentDecision(
            DecisionCode code,
            String preferredStrategy,
            RiskLevel riskLevel,
            BigDecimal confidence,
            List<String> supportingEvidenceIds,
            List<String> unknowns,
            boolean researchOnly
    ) {
        public AgentDecision {
            Objects.requireNonNull(code, "code");
            preferredStrategy = preferredStrategy == null
                    ? "NONE" : preferredStrategy;
            Objects.requireNonNull(riskLevel, "riskLevel");
            Objects.requireNonNull(confidence, "confidence");
            supportingEvidenceIds = immutableText(supportingEvidenceIds,
                    "supportingEvidenceIds");
            unknowns = immutableText(unknowns, "unknowns");
            if (confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0
                    || !researchOnly
                    || code == DecisionCode.RESEARCH_PREFERENCE
                    && ("NONE".equals(preferredStrategy)
                    || supportingEvidenceIds.isEmpty())) {
                throw invalid("M3_AGENT_DECISION_INVALID");
            }
        }
    }

    public record ResearchReport(
            String reportVersion,
            String runtimeVersion,
            String teamVersion,
            String toolGatewayVersion,
            ResearchStatus status,
            ResearchTask task,
            DatasetEvidence dataset,
            List<TechnicalSnapshot> technical,
            StrategyExperimentSet strategyExperiments,
            RiskAssessment risk,
            PortfolioAssessment portfolio,
            List<Evidence> evidence,
            List<ToolCall> toolCalls,
            List<AgentRun> agentRuns,
            CriticReview criticReview,
            AgentDecision finalDecision,
            String researchFingerprint,
            Instant startedAt,
            Instant completedAt,
            int rounds,
            int toolCallCount,
            int modelCallCount,
            ModelUsage totalModelUsage,
            boolean deterministic,
            boolean researchOnly,
            boolean providerCalled,
            boolean shadowStarted,
            boolean tradingStarted
    ) {
        public ResearchReport {
            if (!REPORT_VERSION.equals(reportVersion)
                    || !RUNTIME_VERSION.equals(runtimeVersion)
                    || !TEAM_VERSION.equals(teamVersion)
                    || !TOOL_GATEWAY_VERSION.equals(toolGatewayVersion)) {
                throw invalid("M3_REPORT_VERSION_INVALID");
            }
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(task, "task");
            Objects.requireNonNull(dataset, "dataset");
            technical = List.copyOf(technical);
            Objects.requireNonNull(strategyExperiments,
                    "strategyExperiments");
            Objects.requireNonNull(risk, "risk");
            Objects.requireNonNull(portfolio, "portfolio");
            evidence = List.copyOf(evidence);
            toolCalls = List.copyOf(toolCalls);
            agentRuns = List.copyOf(agentRuns);
            Objects.requireNonNull(criticReview, "criticReview");
            Objects.requireNonNull(finalDecision, "finalDecision");
            requireHash(researchFingerprint,
                    "M3_RESEARCH_FINGERPRINT_INVALID");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(totalModelUsage, "totalModelUsage");
            if (completedAt.isBefore(startedAt)
                    || rounds < 1 || rounds > task.limits().maxRounds()
                    || toolCallCount != toolCalls.size()
                    || modelCallCount != agentRuns.size()
                    || toolCallCount > task.limits().maxToolCalls()
                    || modelCallCount > task.limits().maxModelCalls()
                    || !researchOnly || providerCalled
                    || shadowStarted || tradingStarted) {
                throw invalid("M3_RESEARCH_REPORT_INVALID");
            }
        }
    }

    private static List<Security> distinctSorted(
            List<Security> values,
            String name
    ) {
        List<Security> copy = new ArrayList<>(Objects.requireNonNull(values,
                name));
        copy.sort(Comparator.naturalOrder());
        if (copy.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(copy).size() != copy.size()) {
            throw invalid("M3_SECURITY_SCOPE_INVALID");
        }
        return List.copyOf(copy);
    }

    private static List<StrategySpec> normalizeStrategies(
            List<StrategySpec> values
    ) {
        List<StrategySpec> copy = new ArrayList<>(Objects.requireNonNull(values,
                "strategies"));
        copy.sort(Comparator.comparing(StrategySpec::strategyCode));
        Set<String> codes = new LinkedHashSet<>();
        if (copy.stream().anyMatch(Objects::isNull)
                || copy.stream().anyMatch(value ->
                !codes.add(value.strategyCode()))) {
            throw invalid("M3_STRATEGY_SCOPE_INVALID");
        }
        return List.copyOf(copy);
    }

    private static List<String> immutableText(
            List<String> values,
            String name
    ) {
        List<String> result = new ArrayList<>();
        for (String value : Objects.requireNonNull(values, name)) {
            result.add(required(value, name));
        }
        return List.copyOf(result);
    }

    static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid("M3_REQUIRED_TEXT_INVALID:" + name);
        }
        return value;
    }

    static void requireHash(String value, String code) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid(code);
        }
    }

    static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}
