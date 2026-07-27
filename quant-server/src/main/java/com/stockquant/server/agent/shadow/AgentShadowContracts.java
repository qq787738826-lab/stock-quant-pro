package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.chief.ChiefDecisionContracts;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;

import java.time.ZoneId;
import java.util.List;

public final class AgentShadowContracts {

    public static final String RUN_CONTROL_VERSION = "SHADOW_RUN_CONTROL_V1";
    public static final String SELECTION_VERSION = "SHADOW_SELECTION_V1";
    public static final String OUTCOME_SNAPSHOT_VERSION = "SHADOW_OUTCOME_SNAPSHOT_V1";
    public static final String REVIEW_VERSION = "SHADOW_REVIEW_V1";
    public static final String METRICS_VERSION = "SHADOW_METRICS_V1";
    public static final String RULE_VERSION = ChiefDecisionContracts.RULE_VERSION;
    public static final String FALLBACK_INSUFFICIENT_REASON =
            "SHADOW_INSUFFICIENT_WITHOUT_REASON_CODE";
    public static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    public static final int DEFAULT_MAX_SYMBOLS = 10;
    public static final int HARD_MAX_SYMBOLS = 20;
    public static final int ACCOUNT_ID = 1;
    public static final List<AgentCode> AGENT_ORDER = AgentCode.PROFESSIONAL_AGENTS;

    private AgentShadowContracts() {
    }
}
