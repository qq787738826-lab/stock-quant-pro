package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.CriticIssueCode;
import com.stockquant.server.agent.research.AgentResearchModels.Evidence;
import com.stockquant.server.agent.research.AgentResearchModels.ModelUsage;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.math.BigDecimal;
import java.util.List;

/** Deterministic structured model used for offline E2E and replay. */
public final class DeterministicFakeModelAdapter implements ModelAdapter {
    public static final String MODEL = "DETERMINISTIC_FAKE_MODEL_V1";

    @Override
    public Descriptor descriptor() {
        return new Descriptor("STOCK_QUANT_FAKE", MODEL,
                AgentResearchModels.MODEL_PROTOCOL_VERSION, true);
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return switch (request.agentRole()) {
            case RESEARCH_COORDINATOR -> coordinator(request);
            case DATA_ANALYST -> response(request,
                    List.of(ToolCode.RESEARCH_DATASET), ClaimType.FACT,
                    "The accepted research dataset is eligible for bounded "
                            + "quantitative analysis.",
                    "Dataset eligibility was checked with deterministic "
                            + "evidence.");
            case MARKET_TECHNICAL -> response(request,
                    List.of(ToolCode.MARKET_TECHNICAL), ClaimType.INFERENCE,
                    "The technical classifications are derived from the "
                            + "recorded adjusted-price observations.",
                    "Technical interpretation remains bounded by the "
                            + "observed window.");
            case STRATEGY_RESEARCH -> response(request,
                    List.of(ToolCode.STRATEGY_COMPARE), ClaimType.FACT,
                    "The strategy comparison uses deterministic backtests "
                            + "with accounting and look-ahead guards.",
                    "Strategy metrics came from the strategy research API.");
            case RISK -> response(request,
                    List.of(ToolCode.RISK_METRICS), ClaimType.FACT,
                    "The risk classification reflects quantified drawdown, "
                            + "volatility, and concentration evidence.",
                    "Risk was assessed independently from return preference.");
            case PORTFOLIO -> portfolio(request);
            case CRITIC_REVIEW -> critic(request);
        };
    }

    private static ModelResponse coordinator(ModelRequest request) {
        if ("PLAN".equals(request.phase())) {
            return new ModelResponse(List.of(), List.of(),
                    "Independent data, technical, strategy, and risk work "
                            + "will be synthesized and challenged.",
                    List.of(), false, ModelUsage.zero());
        }
        boolean insufficient = insufficientOutOfSample(request);
        List<String> evidence = ids(request.evidence());
        ModelClaim claim = new ModelClaim(
                insufficient ? ClaimType.UNKNOWN : ClaimType.RECOMMENDATION,
                insufficient
                        ? "The available window is insufficient for an "
                        + "out-of-sample research preference."
                        : "The evidence supports a bounded research "
                        + "preference, not an executable trading instruction.",
                evidence, cap(request, insufficient ? "0.40" : "0.55"));
        return new ModelResponse(List.of(), List.of(claim),
                "The final report preserves evidence, disagreement, risk, "
                        + "and unresolved limitations.",
                List.of(), false, ModelUsage.zero());
    }

    private static ModelResponse portfolio(ModelRequest request) {
        boolean insufficient = insufficientOutOfSample(request);
        String statement = request.revision()
                ? insufficient
                ? "The revised synthesis marks the available window as "
                + "insufficient for out-of-sample preference."
                : "The research preference is revised to disclose unresolved "
                + "lineage limits and a reduced confidence cap."
                : "The leading deterministic experiment is a research "
                + "preference subject to the independent risk assessment.";
        return new ModelResponse(List.of(), List.of(new ModelClaim(
                insufficient ? ClaimType.UNKNOWN : ClaimType.RECOMMENDATION,
                statement, ids(request.evidence()),
                cap(request, insufficient ? "0.40"
                        : request.revision() ? "0.50" : "0.60"))),
                request.revision()
                        ? "The portfolio synthesis now carries the critic's "
                        + "lineage limitation."
                        : "The portfolio synthesis remains research-only.",
                List.of(), false, ModelUsage.zero());
    }

    private static ModelResponse critic(ModelRequest request) {
        boolean pitGap = request.evidence().stream().anyMatch(value ->
                value.sourceTool() == ToolCode.RESEARCH_DATASET
                        && value.statement().contains(
                        "provider PIT lineage is false"));
        List<CriticIssueCode> issues = pitGap
                ? List.of(CriticIssueCode.PIT_LINEAGE_LIMITATION)
                : List.of();
        String statement = pitGap
                ? "The portfolio conclusion must explicitly preserve the "
                + "unverified provider lineage limitation."
                : "No unsupported quantitative conclusion survived the "
                + "evidence checks.";
        return new ModelResponse(List.of(), List.of(new ModelClaim(
                ClaimType.INFERENCE, statement, ids(request.evidence()),
                cap(request, "0.50"))),
                pitGap
                        ? "A bounded portfolio revision is required."
                        : "The structured report is internally consistent.",
                issues, pitGap, ModelUsage.zero());
    }

    private static ModelResponse response(
            ModelRequest request,
            List<ToolCode> tools,
            ClaimType type,
            String statement,
            String summary
    ) {
        return new ModelResponse(tools, List.of(new ModelClaim(type, statement,
                ids(request.evidence()), cap(request, "0.65"))), summary,
                List.of(), false, ModelUsage.zero());
    }

    private static List<String> ids(List<Evidence> evidence) {
        return evidence.stream().map(Evidence::evidenceId).toList();
    }

    private static BigDecimal cap(ModelRequest request, String desired) {
        return request.confidenceCap().min(new BigDecimal(desired));
    }

    private static boolean insufficientOutOfSample(ModelRequest request) {
        return request.evidence().stream().anyMatch(value ->
                value.sourceTool() == ToolCode.STRATEGY_COMPARE
                        && value.statement().contains(
                        "out-of-sample evaluated=false"));
    }
}
