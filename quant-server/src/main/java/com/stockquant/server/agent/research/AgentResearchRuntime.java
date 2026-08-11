package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.AgentDecision;
import com.stockquant.server.agent.research.AgentResearchModels.AgentFinding;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRun;
import com.stockquant.server.agent.research.AgentResearchModels.AgentRunStatus;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.CriticIssueCode;
import com.stockquant.server.agent.research.AgentResearchModels.CriticReview;
import com.stockquant.server.agent.research.AgentResearchModels.DecisionCode;
import com.stockquant.server.agent.research.AgentResearchModels.Evidence;
import com.stockquant.server.agent.research.AgentResearchModels.ModelUsage;
import com.stockquant.server.agent.research.AgentResearchModels.PortfolioAssessment;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchReport;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchStatus;
import com.stockquant.server.agent.research.AgentResearchModels.ResearchTask;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded, evidence-first orchestration for the seven M3 research agents. */
public final class AgentResearchRuntime implements AutoCloseable {
    private static final Set<AgentRole> INDEPENDENT_WORKSTREAMS = Set.of(
            AgentRole.DATA_ANALYST,
            AgentRole.MARKET_TECHNICAL,
            AgentRole.STRATEGY_RESEARCH,
            AgentRole.RISK);

    private final AgentResearchToolGateway tools;
    private final ModelAdapter model;
    private final AgentPromptCatalog prompts;
    private final Clock clock;

    public AgentResearchRuntime(
            AgentResearchToolGateway tools,
            ModelAdapter model,
            AgentPromptCatalog prompts,
            Clock clock
    ) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.model = Objects.requireNonNull(model, "model");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ResearchReport run(ResearchTask task) {
        Objects.requireNonNull(task, "task");
        Instant startedAt = clock.instant();
        Deadline deadline = new Deadline(task.limits().timeout().toNanos());
        RunState runs = new RunState(task, deadline);
        AgentResearchToolGateway.Session session = tools.open(task);

        AgentRun plan = runs.invoke(AgentRole.RESEARCH_COORDINATOR, "PLAN",
                List.of(ToolCode.RESEARCH_DATASET,
                        ToolCode.MARKET_TECHNICAL,
                        ToolCode.STRATEGY_COMPARE,
                        ToolCode.RISK_METRICS),
                List.of(), false, BigDecimal.ONE);
        requireResearchPlan(plan);

        AgentRun dataSelection = runs.invoke(AgentRole.DATA_ANALYST,
                "DATA_TOOL_SELECTION",
                List.of(ToolCode.RESEARCH_DATASET), List.of(), false,
                new BigDecimal("0.90"));
        requireToolSelection(dataSelection, ToolCode.RESEARCH_DATASET);
        var dataset = session.inspectDataset();
        deadline.check();
        runs.invoke(AgentRole.DATA_ANALYST, "DATA_QUALITY",
                List.of(), dataset.citations(), false,
                new BigDecimal("0.90"));

        AgentRun technicalSelection = runs.invoke(
                AgentRole.MARKET_TECHNICAL, "TECHNICAL_TOOL_SELECTION",
                List.of(ToolCode.MARKET_TECHNICAL), dataset.citations(),
                false, new BigDecimal("0.75"));
        requireToolSelection(technicalSelection,
                ToolCode.MARKET_TECHNICAL);
        var technical = session.analyzeTechnical(dataset.loaded());
        deadline.check();
        runs.invoke(AgentRole.MARKET_TECHNICAL, "TECHNICAL_ANALYSIS",
                List.of(), technical.citations(), false,
                new BigDecimal("0.75"));

        AgentRun strategySelection = runs.invoke(
                AgentRole.STRATEGY_RESEARCH, "STRATEGY_TOOL_SELECTION",
                List.of(ToolCode.STRATEGY_COMPARE), dataset.citations(),
                false, new BigDecimal("0.75"));
        requireToolSelection(strategySelection, ToolCode.STRATEGY_COMPARE);
        var strategies = session.compareStrategies(dataset.loaded());
        deadline.check();
        runs.invoke(AgentRole.STRATEGY_RESEARCH, "STRATEGY_EXPERIMENTS",
                List.of(), strategies.citations(), false,
                new BigDecimal("0.75"));

        AgentRun riskSelection = runs.invoke(AgentRole.RISK,
                "RISK_TOOL_SELECTION", List.of(ToolCode.RISK_METRICS),
                strategies.citations(), false, new BigDecimal("0.75"));
        requireToolSelection(riskSelection, ToolCode.RISK_METRICS);
        var risk = session.assessRisk(strategies.experiments());
        deadline.check();
        runs.invoke(AgentRole.RISK, "RISK_ASSESSMENT",
                List.of(), risk.citations(), false,
                new BigDecimal("0.75"));

        List<Evidence> synthesisEvidence = new ArrayList<>();
        synthesisEvidence.addAll(dataset.citations());
        synthesisEvidence.addAll(strategies.citations());
        synthesisEvidence.addAll(risk.citations());
        PortfolioAssessment portfolio = session.portfolio(
                strategies.experiments(), risk.risk(), dataset.evidence(),
                false);
        AgentRun portfolioRun = runs.invoke(AgentRole.PORTFOLIO,
                "PORTFOLIO_SYNTHESIS", List.of(), synthesisEvidence, false,
                portfolio.confidenceCap());

        List<Evidence> criticEvidence = new ArrayList<>(session.evidence());
        AgentRun criticRun = runs.invoke(AgentRole.CRITIC_REVIEW,
                "CRITIC_CHALLENGE", List.of(), criticEvidence, false,
                new BigDecimal("0.70"));
        Set<CriticIssueCode> criticIssues = new LinkedHashSet<>(
                criticRun.issueCodes());
        boolean allOutOfSample = strategies.experiments().experiments()
                .stream().allMatch(value -> value.outOfSampleEvaluated());
        boolean overfitting = strategies.experiments().experiments().stream()
                .anyMatch(value -> value.overfittingFlag());
        boolean highReturnHighDrawdown = risk.risk().strategies().stream()
                .anyMatch(value -> value.highReturnHighDrawdown());
        criticIssues.addAll(AgentCriticGuardrails.inspect(
                new AgentCriticGuardrails.ReviewSignals(
                        dataset.evidence().dataQualityPassed(),
                        dataset.evidence().noFutureDataLeakage(), false,
                        false, allOutOfSample, overfitting,
                        highReturnHighDrawdown,
                        portfolio.limitations().contains(
                                "HIGH_RETURN_HIGH_DRAWDOWN"),
                        false,
                        portfolio.confidenceCap().compareTo(
                                new BigDecimal("0.75")) > 0,
                        dataset.evidence().providerPitVerified(),
                        AgentCriticGuardrails.promptInjectionAttempt(
                                task.objective()))));
        boolean rework = criticRun.reworkRequested()
                || !criticIssues.isEmpty();
        int rounds = 1;
        boolean corrected = false;
        if (rework) {
            if (task.limits().maxRounds() < 2) {
                throw AgentResearchModels.invalid(
                        "M3_CRITIC_REWORK_BUDGET_EXHAUSTED");
            }
            rounds = 2;
            portfolio = session.portfolio(strategies.experiments(),
                    risk.risk(), dataset.evidence(), true);
            runs.invoke(AgentRole.PORTFOLIO, "PORTFOLIO_REVISION",
                    List.of(), synthesisEvidence, true,
                    portfolio.confidenceCap());
            corrected = portfolio.limitations().contains(
                    "PROVIDER_PIT_NOT_VERIFIED");
        }
        deadline.check();

        runs.invoke(AgentRole.RESEARCH_COORDINATOR, "FINAL_SYNTHESIS",
                List.of(), session.evidence(), false,
                portfolio.confidenceCap());
        List<String> challenged = rework
                ? portfolioRun.findings().stream()
                .map(AgentFinding::findingId).toList()
                : List.of();
        CriticReview critic = new CriticReview(List.copyOf(criticIssues),
                challenged, rework, corrected, rework ? 1 : 0);
        List<String> supporting = new ArrayList<>();
        String preferredStrategy = portfolio.preferredStrategy();
        supporting.addAll(dataset.citations().stream().map(
                Evidence::evidenceId).toList());
        supporting.addAll(strategies.citations().stream().filter(value ->
                value.statement().contains(preferredStrategy))
                .map(Evidence::evidenceId).toList());
        supporting.addAll(risk.citations().stream().filter(value ->
                value.statement().contains(preferredStrategy))
                .map(Evidence::evidenceId).toList());
        List<String> unknowns = new ArrayList<>(portfolio.limitations());
        boolean sufficient = strategies.experiments().experiments().stream()
                .allMatch(value -> value.outOfSampleEvaluated()
                        && !value.overfittingFlag());
        AgentDecision decision = new AgentDecision(
                sufficient ? DecisionCode.RESEARCH_PREFERENCE
                        : DecisionCode.INSUFFICIENT_EVIDENCE,
                sufficient ? portfolio.preferredStrategy() : "NONE",
                portfolio.preferredRisk(),
                sufficient ? portfolio.confidenceCap() : BigDecimal.ZERO,
                sufficient ? supporting : List.of(), unknowns, true);
        ModelUsage totalUsage = runs.totalUsage();
        Instant completedAt = clock.instant();
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("task", task);
        canonical.put("dataset", dataset.evidence());
        canonical.put("technical", technical.snapshots());
        canonical.put("strategies", strategies.experiments());
        canonical.put("risk", risk.risk());
        canonical.put("portfolio", portfolio);
        canonical.put("evidence", session.evidence());
        canonical.put("toolCalls", session.calls());
        canonical.put("agentRuns", runs.runs());
        canonical.put("critic", critic);
        canonical.put("decision", decision);
        String fingerprint = AgentResearchCanonical.sha256(canonical);
        return new ResearchReport(AgentResearchModels.REPORT_VERSION,
                AgentResearchModels.RUNTIME_VERSION,
                AgentResearchModels.TEAM_VERSION,
                AgentResearchModels.TOOL_GATEWAY_VERSION,
                sufficient ? ResearchStatus.SUCCEEDED
                        : ResearchStatus.INSUFFICIENT_EVIDENCE,
                task, dataset.evidence(),
                technical.snapshots(), strategies.experiments(), risk.risk(),
                portfolio, session.evidence(), session.calls(), runs.runs(),
                critic, decision, fingerprint, startedAt, completedAt, rounds,
                session.calls().size(), runs.runs().size(), totalUsage,
                model.descriptor().deterministic(), true, false, false, false);
    }

    @Override
    public void close() {
        model.close();
    }

    private static void requireResearchPlan(AgentRun run) {
        Set<ToolCode> required = Set.of(ToolCode.values());
        if (!run.findings().isEmpty()
                || !Set.copyOf(run.requestedTools()).equals(required)) {
            throw AgentResearchModels.invalid("M3_RESEARCH_PLAN_INVALID");
        }
    }

    private static void requireToolSelection(
            AgentRun run,
            ToolCode required
    ) {
        if (!run.findings().isEmpty()
                || !run.requestedTools().equals(List.of(required))) {
            throw AgentResearchModels.invalid(
                    "M3_AGENT_TOOL_SELECTION_INVALID");
        }
    }

    private final class RunState {
        private final ResearchTask task;
        private final Deadline deadline;
        private final List<AgentRun> runs = new ArrayList<>();
        private final List<String> summaries = new ArrayList<>();
        private final Map<AgentRole, Integer> findingCounters =
                new EnumMap<>(AgentRole.class);

        private RunState(ResearchTask task, Deadline deadline) {
            this.task = task;
            this.deadline = deadline;
        }

        private AgentRun invoke(
                AgentRole role,
                String phase,
                List<ToolCode> allowedTools,
                List<Evidence> evidence,
                boolean revision,
                BigDecimal confidenceCap
        ) {
            if (runs.size() >= task.limits().maxModelCalls()) {
                throw AgentResearchModels.invalid(
                        "M3_MODEL_CALL_BUDGET_EXHAUSTED");
            }
            deadline.check();
            AgentPromptCatalog.PromptDefinition prompt = prompts.prompt(role);
            int sequence = runs.size() + 1;
            String callId = "MC_" + String.format("%02d", sequence) + "_"
                    + role.name();
            Map<String, Object> canonical = new java.util.LinkedHashMap<>();
            canonical.put("callId", callId);
            canonical.put("role", role);
            canonical.put("phase", phase);
            canonical.put("promptVersion", prompt.version());
            canonical.put("promptFingerprint", prompt.fingerprint());
            canonical.put("objective", task.objective());
            canonical.put("allowedTools", allowedTools);
            canonical.put("evidence", evidence);
            List<String> priorSummaries = INDEPENDENT_WORKSTREAMS
                    .contains(role) ? List.of() : List.copyOf(summaries);
            canonical.put("priorSummaries", priorSummaries);
            canonical.put("revision", revision);
            canonical.put("confidenceCap", confidenceCap);
            String fingerprint = AgentResearchCanonical.sha256(canonical);
            ModelAdapter.ModelRequest request = new ModelAdapter.ModelRequest(
                    callId, role, phase, prompt.version(), prompt.text(),
                    task.objective(), allowedTools, evidence,
                    priorSummaries, revision, confidenceCap,
                    fingerprint);
            ModelAdapter.ModelResponse response =
                    AgentModelResponseValidator.validate(request,
                            model.complete(request));
            List<AgentFinding> findings = new ArrayList<>();
            for (ModelAdapter.ModelClaim claim : response.claims()) {
                int number = findingCounters.merge(role, 1, Integer::sum);
                findings.add(new AgentFinding("F_" + role.name() + "_"
                        + String.format("%02d", number), role,
                        claim.claimType(), claim.statement(),
                        claim.evidenceIds(), claim.confidence()));
            }
            AgentRunStatus status = revision ? AgentRunStatus.REVISED
                    : !findings.isEmpty() && findings.stream().allMatch(value ->
                    value.claimType() == ClaimType.UNKNOWN)
                    ? AgentRunStatus.INSUFFICIENT_EVIDENCE
                    : AgentRunStatus.COMPLETED;
            AgentRun run = new AgentRun("AR_"
                    + String.format("%02d", sequence) + "_" + role.name(),
                    role, phase, status, prompt.version(),
                    model.descriptor().provider(), model.descriptor().model(),
                    fingerprint, response.requestedTools(), findings,
                    response.issueCodes(), response.reworkRequested(),
                    revision, response.usage());
            runs.add(run);
            if (!phase.endsWith("_TOOL_SELECTION")) {
                summaries.add(response.summary());
            }
            deadline.check();
            return run;
        }

        private List<AgentRun> runs() {
            return List.copyOf(runs);
        }

        private ModelUsage totalUsage() {
            int input = runs.stream().mapToInt(value ->
                    value.usage().inputTokens()).sum();
            int output = runs.stream().mapToInt(value ->
                    value.usage().outputTokens()).sum();
            int reasoning = runs.stream().mapToInt(value ->
                    value.usage().reasoningTokens()).sum();
            int total = runs.stream().mapToInt(value ->
                    value.usage().totalTokens()).sum();
            BigDecimal cost = runs.stream().map(value ->
                            value.usage().estimatedCost())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<String> currencies = runs.stream().map(value ->
                    value.usage().costCurrency()).collect(
                    java.util.stream.Collectors.toUnmodifiableSet());
            if (currencies.size() != 1) {
                throw AgentResearchModels.invalid(
                        "M3_MODEL_USAGE_CURRENCY_MISMATCH");
            }
            return new ModelUsage(input, output, reasoning, total, cost,
                    currencies.iterator().next());
        }
    }

    private static final class Deadline {
        private final long deadline;

        private Deadline(long timeoutNanos) {
            long now = System.nanoTime();
            this.deadline = timeoutNanos >= Long.MAX_VALUE - now
                    ? Long.MAX_VALUE : now + timeoutNanos;
        }

        private void check() {
            if (System.nanoTime() - deadline >= 0) {
                throw AgentResearchModels.invalid("M3_RUNTIME_TIMEOUT");
            }
        }
    }
}
