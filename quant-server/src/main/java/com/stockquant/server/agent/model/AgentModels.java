package com.stockquant.server.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.DecisionStatus;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class AgentModels {

    private AgentModels() {}

    public record AgentTask(
            long id,
            String symbol,
            LocalDate tradeDate,
            TaskStatus status,
            String contextSchemaVersion,
            JsonNode contextSnapshot,
            Instant contextGeneratedAt,
            String contextHash,
            String ruleVersion,
            ExecutionMode executionMode,
            TriggerType triggerType,
            String requestedBy,
            boolean forceRefresh,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record AgentRun(
            long id,
            long taskId,
            AgentCode agentCode,
            int attemptNo,
            RunStatus status,
            GateStatus gateStatus,
            RunDecision decision,
            Integer score,
            Integer confidence,
            boolean veto,
            String summary,
            JsonNode outputJson,
            Instant startedAt,
            Instant finishedAt,
            Long durationMs,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record Finding(
            String findingId,
            String code,
            Severity severity,
            String title,
            String detail,
            List<String> evidenceIds
    ) {}

    public record Evidence(
            String evidenceId,
            EvidenceCategory category,
            EvidenceSourceType sourceType,
            String sourceName,
            String sourceRef,
            String symbol,
            LocalDate tradeDate,
            Instant observedAt,
            Instant collectedAt,
            JsonNode fields,
            String contentHash
    ) {}

    public record AgentError(String code, String message) {}

    public record AgentOutput(
            String schemaVersion,
            long taskId,
            long runId,
            AgentCode agentCode,
            RunStatus status,
            GateStatus gateStatus,
            RunDecision decision,
            boolean veto,
            Integer score,
            Integer confidence,
            String summary,
            List<Finding> findings,
            List<Evidence> evidence,
            List<AgentError> errors,
            String contextHash,
            String ruleVersion,
            ExecutionMode executionMode,
            Instant generatedAt
    ) {}

    public record FormalVeto(
            String vetoId,
            long taskId,
            long runId,
            AgentCode agentCode,
            String vetoCode,
            String reason,
            List<String> evidenceIds,
            Instant createdAt
    ) {}

    public record FinalDecision(
            String schemaVersion,
            long taskId,
            FinalDecisionCode decision,
            GateStatus gateStatus,
            boolean vetoed,
            Integer score,
            Integer confidence,
            String summary,
            List<Finding> findings,
            List<Long> sourceRunIds,
            List<String> vetoIds,
            String contextHash,
            LocalDate tradeDate,
            String ruleVersion,
            ExecutionMode executionMode,
            Instant generatedAt
    ) {}

    public record AgentTeamResponse(
            String schemaVersion,
            long taskId,
            String contextHash,
            LocalDate tradeDate,
            String ruleVersion,
            ExecutionMode executionMode,
            List<AgentOutput> agentRuns,
            List<Evidence> evidence,
            List<FormalVeto> vetoes,
            FinalDecision finalDecision,
            Instant generatedAt
    ) {}

    public record RunIds(
            long dataQuality,
            long marketRegime,
            long technicalAnalysis,
            long strategyBacktest,
            long announcementRisk,
            long positionRisk
    ) {
        public static RunIds from(List<AgentRun> runs) {
            Map<AgentCode, Long> ids = runs.stream().collect(java.util.stream.Collectors.toMap(
                    AgentRun::agentCode,
                    AgentRun::id
            ));
            return new RunIds(
                    required(ids, AgentCode.DATA_QUALITY),
                    required(ids, AgentCode.MARKET_REGIME),
                    required(ids, AgentCode.TECHNICAL_ANALYSIS),
                    required(ids, AgentCode.STRATEGY_BACKTEST),
                    required(ids, AgentCode.ANNOUNCEMENT_RISK),
                    required(ids, AgentCode.POSITION_RISK)
            );
        }

        public Map<AgentCode, Long> byAgentCode() {
            return Map.of(
                    AgentCode.DATA_QUALITY, dataQuality,
                    AgentCode.MARKET_REGIME, marketRegime,
                    AgentCode.TECHNICAL_ANALYSIS, technicalAnalysis,
                    AgentCode.STRATEGY_BACKTEST, strategyBacktest,
                    AgentCode.ANNOUNCEMENT_RISK, announcementRisk,
                    AgentCode.POSITION_RISK, positionRisk
            );
        }

        private static long required(Map<AgentCode, Long> ids, AgentCode code) {
            Long id = ids.get(code);
            if (id == null) {
                throw new IllegalArgumentException("缺少运行ID：" + code);
            }
            return id;
        }
    }

    public record AgentTeamRequest(
            String schemaVersion,
            long taskId,
            RunIds runIds,
            String symbol,
            LocalDate tradeDate,
            String contextHash,
            String contextSchemaVersion,
            String ruleVersion,
            ExecutionMode executionMode,
            JsonNode contextSnapshot,
            Instant requestedAt
    ) {}

    public record ContextSnapshot(
            String schemaVersion,
            JsonNode value,
            Instant generatedAt,
            String contextHash
    ) {}

    public record CacheKey(
            String symbol,
            LocalDate tradeDate,
            String contextHash,
            String ruleVersion,
            ExecutionMode executionMode
    ) {}

    public record CreatedTask(AgentTask task, boolean newlyCreated) {}

    public record PageResult<T>(List<T> content, int page, int size, long total) {}

    public record PersistedDecision(AgentTask task, DecisionStatus status) {}
}
