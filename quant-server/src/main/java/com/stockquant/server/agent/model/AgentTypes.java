package com.stockquant.server.agent.model;

import java.util.List;

public final class AgentTypes {

    private AgentTypes() {}

    public enum AgentCode {
        DATA_QUALITY,
        MARKET_REGIME,
        TECHNICAL_ANALYSIS,
        STRATEGY_BACKTEST,
        ANNOUNCEMENT_RISK,
        POSITION_RISK;

        public static final List<AgentCode> PROFESSIONAL_AGENTS = List.of(values());
    }

    public enum TaskStatus { QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

    public enum RunStatus {
        QUEUED, RUNNING, COMPLETED, PARTIAL, INSUFFICIENT_DATA, FAILED, SKIPPED
    }

    public enum DecisionStatus { COMPLETED, PARTIAL, INSUFFICIENT_DATA, FAILED }

    public enum GateStatus { PASS, WARN, BLOCKED, NOT_APPLICABLE }

    public enum RunDecision { PASS, WARN, REJECT, NOT_APPLICABLE }

    public enum FinalDecisionCode {
        REJECTED_BY_VETO,
        BLOCKED_BY_DATA_QUALITY,
        INSUFFICIENT_DATA,
        RESEARCH_ONLY,
        WATCH,
        PASS_TO_MANUAL_REVIEW
    }

    public enum ExecutionMode { LOCAL_RULES }

    public enum TriggerType { MANUAL, SCAN_CANDIDATE, SCHEDULED, RETRY }

    public enum Severity { INFO, WARN, HIGH, CRITICAL }

    public enum EvidenceCategory {
        MARKET_DATA,
        MARKET_BREADTH,
        TECHNICAL_INDICATOR,
        SCAN_RESULT,
        BACKTEST_RESULT,
        SECURITY_EVENT,
        PORTFOLIO_STATE,
        DATA_QUALITY,
        QUERY_RESULT
    }

    public enum EvidenceSourceType {
        DATABASE,
        LOCAL_CACHE,
        CONFIGURED_PROVIDER,
        JAVA_ENGINE,
        PYTHON_RULE_ENGINE
    }
}
