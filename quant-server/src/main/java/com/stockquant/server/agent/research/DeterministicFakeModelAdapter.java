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
        if ("PLAN".equals(request.phase())
                || request.phase().endsWith("_TOOL_SELECTION")) {
            return toolSelection(request);
        }
        return switch (request.agentRole()) {
            case RESEARCH_COORDINATOR -> coordinator(request);
            case DATA_ANALYST -> response(request, ClaimType.FACT,
                    "已验收的研究数据集具备开展受限量化分析的资格。",
                    "已使用确定性证据检查数据集资格。");
            case MARKET_TECHNICAL -> response(request, ClaimType.INFERENCE,
                    "技术分类来自已记录的前复权价格观察。",
                    "技术解释严格限定在已观察的数据窗口内。");
            case STRATEGY_RESEARCH -> response(request, ClaimType.FACT,
                    "策略比较使用带有会计守恒和防未来数据门禁的确定性回测。",
                    "策略指标均来自确定性策略研究接口。");
            case RISK -> response(request, ClaimType.FACT,
                    "风险分类反映了量化回撤、波动率和集中度证据。",
                    "风险评估独立于收益偏好完成。");
            case PORTFOLIO -> portfolio(request);
            case CRITIC_REVIEW -> critic(request);
        };
    }

    private static ModelResponse toolSelection(ModelRequest request) {
        return new ModelResponse(request.allowedTools(), List.of(),
                "该角色仅选择了明确授权的确定性研究工具。",
                List.of(), false, ModelUsage.zero());
    }

    private static ModelResponse coordinator(ModelRequest request) {
        boolean insufficient = insufficientOutOfSample(request);
        List<String> evidence = ids(request.evidence());
        ModelClaim claim = new ModelClaim(
                insufficient ? ClaimType.UNKNOWN : ClaimType.RECOMMENDATION,
                insufficient
                        ? "当前数据窗口不足以形成样本外研究偏好。"
                        : "现有证据支持受限的研究偏好，但不构成可执行交易指令。",
                evidence, cap(request, insufficient ? "0.40" : "0.55"));
        return new ModelResponse(List.of(), List.of(claim),
                "最终报告保留了证据、分歧、风险和未解决限制。",
                List.of(), false, ModelUsage.zero());
    }

    private static ModelResponse portfolio(ModelRequest request) {
        boolean insufficient = insufficientOutOfSample(request);
        String statement = request.revision()
                ? insufficient
                ? "修订后的综合结论明确标记当前窗口不足以形成样本外偏好。"
                : "修订后的研究偏好披露了尚未解决的血缘限制，并降低置信度上限。"
                : "领先的确定性实验仅形成研究偏好，仍受独立风险评估约束。";
        return new ModelResponse(List.of(), List.of(new ModelClaim(
                insufficient ? ClaimType.UNKNOWN : ClaimType.RECOMMENDATION,
                statement, ids(request.evidence()),
                cap(request, insufficient ? "0.40"
                        : request.revision() ? "0.50" : "0.60"))),
                request.revision()
                        ? "组合综合结论已纳入批判审查提出的血缘限制。"
                        : "组合综合结论仍然仅限研究用途。",
                List.of(), false, ModelUsage.zero());
    }

    private static ModelResponse critic(ModelRequest request) {
        boolean pitGap = request.evidence().stream().anyMatch(value ->
                value.sourceTool() == ToolCode.RESEARCH_DATASET
                        && value.statement().contains(
                        "PROVIDER_PIT_VERIFIED=false"));
        List<CriticIssueCode> issues = pitGap
                ? List.of(CriticIssueCode.PIT_LINEAGE_LIMITATION)
                : List.of();
        String statement = pitGap
                ? "组合结论必须明确保留数据源时点血缘尚未验证这一限制。"
                : "证据检查后没有保留缺乏支持的量化结论。";
        return new ModelResponse(List.of(), List.of(new ModelClaim(
                ClaimType.INFERENCE, statement, ids(request.evidence()),
                cap(request, "0.50"))),
                pitGap
                        ? "需要进行一次受限的组合结论修订。"
                        : "结构化研究报告内部一致。",
                issues, pitGap, ModelUsage.zero());
    }

    private static ModelResponse response(
            ModelRequest request,
            ClaimType type,
            String statement,
            String summary
    ) {
        return new ModelResponse(List.of(), List.of(new ModelClaim(type,
                statement, ids(request.evidence()), cap(request, "0.65"))),
                summary, List.of(), false, ModelUsage.zero());
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
                        "OUT_OF_SAMPLE_EVALUATED=false"));
    }
}
