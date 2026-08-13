package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.Evidence;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fail-closed validation for every structured model response. */
final class AgentModelResponseValidator {
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z_])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z_])");
    private static final Pattern FORBIDDEN_ACTION = Pattern.compile(
            "(?i)(place\\s+an?\\s+order|execute\\s+(?:a\\s+)?trade|"
                    + "submit\\s+(?:a\\s+)?trade|真实下单|自动交易|执行交易)");
    private static final Map<AgentRole, Set<ClaimType>> CLAIM_TYPES =
            claimTypes();
    private static final Set<String> DOWNGRADE_TO_UNKNOWN = Set.of(
            "M3_MODEL_CLAIM_TYPE_REJECTED",
            "M3_MODEL_CLAIM_LENGTH_REJECTED",
            "M3_MODEL_CLAIM_CONTROL_REJECTED",
            "M3_MODEL_CLAIM_TRADING_ACTION_REJECTED",
            "M3_MODEL_CLAIM_CONFIDENCE_REJECTED",
            "M3_MODEL_EVIDENCE_REFERENCE_REJECTED",
            "M3_UNSUPPORTED_MODEL_CLAIM",
            "M3_UNKNOWN_CONFIDENCE_REJECTED",
            "M3_UNSUPPORTED_NUMERIC_CLAIM");

    private AgentModelResponseValidator() {
    }

    static ModelAdapter.ModelResponse validate(
            ModelAdapter.ModelRequest request,
            ModelAdapter.ModelResponse response
    ) {
        boolean toolSelection = "PLAN".equals(request.phase())
                || request.phase().endsWith("_TOOL_SELECTION");
        if (toolSelection && (response.requestedTools().isEmpty()
                || !response.claims().isEmpty())) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_TOOL_SELECTION_REJECTED");
        }
        if (!toolSelection && response.claims().isEmpty()
                && request.agentRole() != AgentRole.RESEARCH_COORDINATOR) {
            throw AgentResearchModels.invalid("M3_MODEL_CLAIMS_REQUIRED");
        }
        Set<ToolCode> allowed = EnumSet.noneOf(ToolCode.class);
        allowed.addAll(request.allowedTools());
        Set<ToolCode> requested = EnumSet.noneOf(ToolCode.class);
        for (ToolCode tool : response.requestedTools()) {
            if (!allowed.contains(tool) || !requested.add(tool)) {
                throw AgentResearchModels.invalid(
                        "M3_MODEL_TOOL_REQUEST_REJECTED");
            }
        }
        if (request.agentRole() != AgentRole.CRITIC_REVIEW
                && (!response.issueCodes().isEmpty()
                || response.reworkRequested())) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_CRITIC_AUTHORITY_REJECTED");
        }
        Map<String, Evidence> evidence = new HashMap<>();
        for (Evidence item : request.evidence()) {
            if (evidence.put(item.evidenceId(), item) != null) {
                throw AgentResearchModels.invalid(
                        "M3_MODEL_EVIDENCE_DUPLICATE");
            }
        }
        List<ModelAdapter.ModelClaim> validatedClaims = new ArrayList<>();
        for (ModelAdapter.ModelClaim claim : response.claims()) {
            try {
                validateClaim(request, claim, evidence);
                validatedClaims.add(claim);
            } catch (IllegalArgumentException failure) {
                if (!DOWNGRADE_TO_UNKNOWN.contains(failure.getMessage())) {
                    throw failure;
                }
                ModelAdapter.ModelClaim unknown = new ModelAdapter.ModelClaim(
                        ClaimType.UNKNOWN,
                        rejectedClaimStatement(failure.getMessage()),
                        List.of(), claim.confidence()
                        .min(new BigDecimal("0.50"))
                        .min(request.confidenceCap()));
                validateClaim(request, unknown, evidence);
                validatedClaims.add(unknown);
            }
        }
        if (response.summary().length() > 800
                || containsControl(response.summary())
                || FORBIDDEN_ACTION.matcher(response.summary()).find()) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_SUMMARY_REJECTED");
        }
        String summary = NUMBER.matcher(response.summary()).find()
                ? "Structured role analysis completed under deterministic "
                + "evidence constraints."
                : response.summary();
        return new ModelAdapter.ModelResponse(response.requestedTools(),
                validatedClaims, summary, response.issueCodes(),
                response.reworkRequested(), response.usage());
    }

    static Set<ClaimType> allowedClaimTypes(AgentRole role) {
        return CLAIM_TYPES.get(role);
    }

    private static String rejectedClaimStatement(String reason) {
        return switch (reason) {
            case "M3_MODEL_EVIDENCE_REFERENCE_REJECTED" ->
                    "A model claim was rejected because its evidence "
                            + "reference was not present in deterministic "
                            + "tool output.";
            case "M3_UNSUPPORTED_MODEL_CLAIM" ->
                    "A model claim was rejected because deterministic "
                            + "supporting evidence was insufficient.";
            case "M3_UNKNOWN_CONFIDENCE_REJECTED" ->
                    "A model uncertainty claim was rejected because its "
                            + "confidence exceeded the uncertainty limit.";
            default -> "A model-supplied numeric statement was rejected "
                    + "because cited deterministic evidence did not directly "
                    + "support it.";
        };
    }

    private static void validateClaim(
            ModelAdapter.ModelRequest request,
            ModelAdapter.ModelClaim claim,
            Map<String, Evidence> knownEvidence
    ) {
        if (!CLAIM_TYPES.get(request.agentRole()).contains(claim.claimType())) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_CLAIM_TYPE_REJECTED");
        }
        if (claim.statement().length() > 600) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_CLAIM_LENGTH_REJECTED");
        }
        if (containsControl(claim.statement())) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_CLAIM_CONTROL_REJECTED");
        }
        if (FORBIDDEN_ACTION.matcher(claim.statement()).find()) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_CLAIM_TRADING_ACTION_REJECTED");
        }
        if (claim.confidence().compareTo(request.confidenceCap()) > 0) {
            throw AgentResearchModels.invalid(
                    "M3_MODEL_CLAIM_CONFIDENCE_REJECTED");
        }
        Set<String> citations = new HashSet<>();
        StringBuilder citedText = new StringBuilder();
        for (String id : claim.evidenceIds()) {
            Evidence item = knownEvidence.get(id);
            if (item == null || !citations.add(id)) {
                throw AgentResearchModels.invalid(
                        "M3_MODEL_EVIDENCE_REFERENCE_REJECTED");
            }
            citedText.append(item.statement()).append(' ');
        }
        if (claim.claimType() == ClaimType.UNKNOWN) {
            if (claim.confidence().compareTo(new BigDecimal("0.50")) > 0) {
                throw AgentResearchModels.invalid(
                        "M3_UNKNOWN_CONFIDENCE_REJECTED");
            }
        } else if (citations.isEmpty()) {
            throw AgentResearchModels.invalid(
                    "M3_UNSUPPORTED_MODEL_CLAIM");
        }
        Matcher numeric = NUMBER.matcher(claim.statement());
        while (numeric.find()) {
            if (citedText.indexOf(numeric.group()) < 0) {
                throw AgentResearchModels.invalid(
                        "M3_UNSUPPORTED_NUMERIC_CLAIM");
            }
        }
    }

    private static boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(
                character) && !Character.isWhitespace(character));
    }

    private static Map<AgentRole, Set<ClaimType>> claimTypes() {
        EnumMap<AgentRole, Set<ClaimType>> result = new EnumMap<>(
                AgentRole.class);
        result.put(AgentRole.RESEARCH_COORDINATOR, EnumSet.of(
                ClaimType.INFERENCE, ClaimType.HYPOTHESIS,
                ClaimType.RECOMMENDATION, ClaimType.UNKNOWN));
        result.put(AgentRole.DATA_ANALYST, EnumSet.of(ClaimType.FACT,
                ClaimType.UNKNOWN));
        result.put(AgentRole.MARKET_TECHNICAL, EnumSet.of(ClaimType.FACT,
                ClaimType.INFERENCE, ClaimType.UNKNOWN));
        result.put(AgentRole.STRATEGY_RESEARCH, EnumSet.of(ClaimType.FACT,
                ClaimType.INFERENCE, ClaimType.HYPOTHESIS,
                ClaimType.UNKNOWN));
        result.put(AgentRole.RISK, EnumSet.of(ClaimType.FACT,
                ClaimType.INFERENCE, ClaimType.UNKNOWN));
        result.put(AgentRole.PORTFOLIO, EnumSet.of(ClaimType.INFERENCE,
                ClaimType.RECOMMENDATION, ClaimType.UNKNOWN));
        result.put(AgentRole.CRITIC_REVIEW, EnumSet.of(ClaimType.INFERENCE,
                ClaimType.UNKNOWN));
        return Map.copyOf(result);
    }
}
