package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;
import com.stockquant.server.agent.research.AgentResearchModels.ClaimType;
import com.stockquant.server.agent.research.AgentResearchModels.CriticIssueCode;
import com.stockquant.server.agent.research.AgentResearchModels.Evidence;
import com.stockquant.server.agent.research.AgentResearchModels.ModelUsage;
import com.stockquant.server.agent.research.AgentResearchModels.ToolCode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Provider-neutral, structured-only model boundary for M3 agents. */
public interface ModelAdapter extends AutoCloseable {
    Descriptor descriptor();

    ModelResponse complete(ModelRequest request);

    @Override
    default void close() {
        // Most adapters do not own resources.
    }

    record Descriptor(
            String provider,
            String model,
            String adapterVersion,
            boolean deterministic
    ) {
        public Descriptor {
            provider = AgentResearchModels.required(provider, "provider");
            model = AgentResearchModels.required(model, "model");
            adapterVersion = AgentResearchModels.required(adapterVersion,
                    "adapterVersion");
        }
    }

    record ModelRequest(
            String callId,
            AgentRole agentRole,
            String phase,
            String promptVersion,
            String systemPrompt,
            String untrustedObjective,
            List<ToolCode> allowedTools,
            List<Evidence> evidence,
            List<String> priorFindingSummaries,
            boolean revision,
            BigDecimal confidenceCap,
            String inputFingerprint
    ) {
        public ModelRequest {
            callId = AgentResearchModels.required(callId, "callId");
            Objects.requireNonNull(agentRole, "agentRole");
            phase = AgentResearchModels.required(phase, "phase");
            promptVersion = AgentResearchModels.required(promptVersion,
                    "promptVersion");
            systemPrompt = AgentResearchModels.required(systemPrompt,
                    "systemPrompt");
            untrustedObjective = AgentResearchModels.required(
                    untrustedObjective, "untrustedObjective");
            allowedTools = List.copyOf(allowedTools);
            evidence = List.copyOf(evidence);
            priorFindingSummaries = List.copyOf(priorFindingSummaries);
            Objects.requireNonNull(confidenceCap, "confidenceCap");
            AgentResearchModels.requireHash(inputFingerprint,
                    "M3_MODEL_REQUEST_FINGERPRINT_INVALID");
            if (!callId.matches("MC_[0-9]{2}_[A-Z_]+")
                    || confidenceCap.signum() < 0
                    || confidenceCap.compareTo(BigDecimal.ONE) > 0) {
                throw AgentResearchModels.invalid("M3_MODEL_REQUEST_INVALID");
            }
        }
    }

    record ModelClaim(
            ClaimType claimType,
            String statement,
            List<String> evidenceIds,
            BigDecimal confidence
    ) {
        public ModelClaim {
            Objects.requireNonNull(claimType, "claimType");
            statement = AgentResearchModels.required(statement, "statement");
            evidenceIds = List.copyOf(evidenceIds);
            Objects.requireNonNull(confidence, "confidence");
            if (confidence.signum() < 0
                    || confidence.compareTo(BigDecimal.ONE) > 0) {
                throw AgentResearchModels.invalid("M3_MODEL_CLAIM_INVALID");
            }
        }
    }

    record ModelResponse(
            List<ToolCode> requestedTools,
            List<ModelClaim> claims,
            String summary,
            List<CriticIssueCode> issueCodes,
            boolean reworkRequested,
            ModelUsage usage
    ) {
        public ModelResponse {
            requestedTools = List.copyOf(requestedTools);
            claims = List.copyOf(claims);
            summary = AgentResearchModels.required(summary, "summary");
            issueCodes = List.copyOf(issueCodes);
            Objects.requireNonNull(usage, "usage");
            if (claims.size() > 8 || requestedTools.size() > 8
                    || issueCodes.size() > 12) {
                throw AgentResearchModels.invalid("M3_MODEL_RESPONSE_OVERSIZED");
            }
        }
    }
}
