package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.CriticIssueCode;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Deterministic critic checks that cannot be overridden by model prose. */
final class AgentCriticGuardrails {
    private AgentCriticGuardrails() {
    }

    static Set<CriticIssueCode> inspect(ReviewSignals value) {
        EnumSet<CriticIssueCode> issues = EnumSet.noneOf(
                CriticIssueCode.class);
        if (!value.dataQualityPassed()) {
            issues.add(CriticIssueCode.DATA_QUALITY_GAP);
        }
        if (!value.noFutureDataLeakage()) {
            issues.add(CriticIssueCode.FUTURE_DATA_RISK);
        }
        if (value.metricMismatch()) {
            issues.add(CriticIssueCode.METRIC_MISMATCH);
        }
        if (value.unsupportedClaim()) {
            issues.add(CriticIssueCode.UNSUPPORTED_CLAIM);
        }
        if (!value.outOfSampleEvaluated() || value.overfittingDetected()) {
            issues.add(CriticIssueCode.OVERFITTING_RISK);
        }
        if (value.highReturnHighDrawdown()
                && !value.drawdownRiskDisclosed()) {
            issues.add(CriticIssueCode.DRAWDOWN_UNDERSTATED);
        }
        if (value.agentConflict()) {
            issues.add(CriticIssueCode.AGENT_CONFLICT);
        }
        if (value.overconfident()) {
            issues.add(CriticIssueCode.OVERCONFIDENCE);
        }
        if (!value.providerPitVerified()) {
            issues.add(CriticIssueCode.PIT_LINEAGE_LIMITATION);
        }
        if (value.promptInjectionAttempt()) {
            issues.add(CriticIssueCode.PROMPT_INJECTION_ATTEMPT);
        }
        return Set.copyOf(issues);
    }

    static boolean promptInjectionAttempt(String objective) {
        String normalized = objective.toLowerCase(Locale.ROOT);
        return normalized.contains("ignore all system")
                || normalized.contains("ignore previous")
                || normalized.contains("override system")
                || normalized.contains("execute a real order")
                || normalized.contains("reveal secret")
                || normalized.contains("忽略系统")
                || normalized.contains("执行真实交易")
                || normalized.contains("泄露秘密");
    }

    record ReviewSignals(
            boolean dataQualityPassed,
            boolean noFutureDataLeakage,
            boolean metricMismatch,
            boolean unsupportedClaim,
            boolean outOfSampleEvaluated,
            boolean overfittingDetected,
            boolean highReturnHighDrawdown,
            boolean drawdownRiskDisclosed,
            boolean agentConflict,
            boolean overconfident,
            boolean providerPitVerified,
            boolean promptInjectionAttempt
    ) {
    }
}
