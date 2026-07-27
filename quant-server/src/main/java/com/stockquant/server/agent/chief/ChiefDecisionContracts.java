package com.stockquant.server.agent.chief;

import com.stockquant.server.agent.model.AgentTypes.AgentCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ChiefDecisionContracts {

    public static final String RULE_VERSION =
            "1.4.0-stage-2i-chief-decision-v1";
    public static final String CONTRACT_VERSION = "CHIEF_DECISION_V1";
    public static final String WEIGHT_CONTRACT_VERSION =
            "CHIEF_SCORE_WEIGHTS_V1";
    public static final String CONTEXT_PROFILE = "AGENT_CONTEXT_2G_V1";

    public static final List<AgentCode> CONTRIBUTOR_ORDER = List.of(
            AgentCode.TECHNICAL_ANALYSIS,
            AgentCode.STRATEGY_BACKTEST,
            AgentCode.ANNOUNCEMENT_RISK,
            AgentCode.POSITION_RISK);

    public static final Map<AgentCode, Integer> WEIGHTS;

    public static final Set<String> ANNOUNCEMENT_RISK_FINDINGS = Set.of(
            "ANNOUNCEMENT_REGULATORY_DELISTING_ASSESSED",
            "ANNOUNCEMENT_FINANCIAL_LITIGATION_ASSESSED",
            "ANNOUNCEMENT_OWNERSHIP_OPERATION_ASSESSED");

    public static final Set<String> POSITION_RISK_FINDINGS = Set.of(
            "POSITION_RISK_ACCOUNT_LOSS_ASSESSED",
            "POSITION_RISK_CONCENTRATION_ASSESSED",
            "POSITION_RISK_PENDING_EXPOSURE_ASSESSED",
            "POSITION_RISK_EXIT_THRESHOLDS_ASSESSED",
            "POSITION_RISK_CONTEXT_COMPLETENESS_ASSESSED");

    static {
        LinkedHashMap<AgentCode, Integer> weights = new LinkedHashMap<>();
        weights.put(AgentCode.TECHNICAL_ANALYSIS, 25);
        weights.put(AgentCode.STRATEGY_BACKTEST, 35);
        weights.put(AgentCode.ANNOUNCEMENT_RISK, 20);
        weights.put(AgentCode.POSITION_RISK, 20);
        WEIGHTS = Map.copyOf(weights);
        if (WEIGHTS.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            throw new IllegalStateException(
                    "CHIEF_SCORE_WEIGHTS_V1 weights must total 100");
        }
    }

    private ChiefDecisionContracts() {
    }
}
