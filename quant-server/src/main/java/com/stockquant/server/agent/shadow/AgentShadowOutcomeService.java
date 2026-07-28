package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentRun;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentVetoRepository;
import com.stockquant.server.agent.shadow.AgentShadowModels.DriftResult;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.TerminalOutcome;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AgentShadowOutcomeService {

    private final AgentRunRepository runRepository;
    private final AgentDecisionRepository decisionRepository;
    private final AgentVetoRepository vetoRepository;
    private final AgentShadowRepository shadowRepository;
    private final ObjectMapper objectMapper;

    public AgentShadowOutcomeService(
            AgentRunRepository runRepository,
            AgentDecisionRepository decisionRepository,
            AgentVetoRepository vetoRepository,
            AgentShadowRepository shadowRepository,
            ObjectMapper objectMapper
    ) {
        this.runRepository = runRepository;
        this.decisionRepository = decisionRepository;
        this.vetoRepository = vetoRepository;
        this.shadowRepository = shadowRepository;
        this.objectMapper = objectMapper;
    }

    public TerminalOutcome terminalOutcome(
            ShadowItem item,
            AgentTask task,
            Instant observedAt
    ) {
        if (!terminal(task.status())) {
            throw new IllegalArgumentException(
                    "agent task is not terminal: " + task.id());
        }
        List<AgentRun> runs = orderedRuns(
                runRepository.findByTaskId(task.id()));
        List<FormalVeto> vetoes = vetoRepository.findByTaskId(task.id());
        FinalDecision decision = decisionRepository.findByTaskId(task.id())
                .orElse(null);
        long durationMs = duration(item.startedAt(), observedAt);
        if (task.status() == TaskStatus.FAILED
                || task.status() == TaskStatus.CANCELLED) {
            return new TerminalOutcome(
                    task.status(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    OutcomeClass.FAILED,
                    List.of(),
                    snapshot(task, runs, vetoes, null),
                    task.contextHash(),
                    durationMs,
                    safeError(task.errorMessage(),
                            "AGENT_TASK_" + task.status().name()));
        }
        if (decision == null) {
            return new TerminalOutcome(
                    task.status(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    OutcomeClass.FAILED,
                    List.of(),
                    snapshot(task, runs, vetoes, null),
                    task.contextHash(),
                    durationMs,
                    "SHADOW_TERMINAL_TASK_MISSING_DECISION");
        }
        List<String> reasonCodes =
                decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                        ? reasonCodes(runs)
                        : List.of();
        OutcomeClass outcomeClass =
                decision.decision() == FinalDecisionCode.INSUFFICIENT_DATA
                        ? OutcomeClass.INSUFFICIENT
                        : OutcomeClass.DETERMINED;
        return new TerminalOutcome(
                task.status(),
                decision.decision(),
                decision.gateStatus(),
                decision.score(),
                decision.confidence(),
                decision.vetoed(),
                outcomeClass,
                reasonCodes,
                snapshot(task, runs, vetoes, decision),
                task.contextHash(),
                durationMs,
                null);
    }

    public TerminalOutcome launchFailure(
            ShadowItem item,
            String errorMessage,
            Instant observedAt
    ) {
        return new TerminalOutcome(
                TaskStatus.FAILED,
                null,
                null,
                null,
                null,
                false,
                OutcomeClass.FAILED,
                List.of(),
                null,
                null,
                duration(item.startedAt(), observedAt),
                safeError(errorMessage, "SHADOW_ITEM_CREATION_FAILED"));
    }

    public TerminalOutcome timeout(
            ShadowItem item,
            AgentTask task,
            Instant observedAt
    ) {
        return new TerminalOutcome(
                task.status(),
                null,
                null,
                null,
                null,
                false,
                OutcomeClass.FAILED,
                List.of(),
                null,
                task.contextHash(),
                duration(item.startedAt(), observedAt),
                "SHADOW_ITEM_TIMEOUT_TASK_CONTINUES");
    }

    public DriftResult drift(
            ShadowItem current,
            TerminalOutcome outcome
    ) {
        var previous = shadowRepository.findPreviousComparable(
                current.id(),
                current.symbol(),
                shadowRepository.findBatch(current.batchId())
                        .orElseThrow(() -> new IllegalStateException(
                                "shadow batch disappeared: "
                                        + current.batchId()))
                        .ruleVersion());
        if (previous.isEmpty()) {
            return new DriftResult(
                    null, null, null, null, null, null);
        }
        ShadowItem prior = previous.orElseThrow();
        ArrayNode changed = objectMapper.createArrayNode();
        Map<String, JsonNode> priorRuns = runsByCode(
                prior.runSnapshot());
        Map<String, JsonNode> currentRuns = runsByCode(
                outcome.runSnapshot());
        for (AgentCode code : AgentShadowContracts.AGENT_ORDER) {
            List<String> fields = changedFields(
                    priorRuns.get(code.name()),
                    currentRuns.get(code.name()));
            if (!fields.isEmpty()) {
                ObjectNode node = changed.addObject();
                node.put("agentCode", code.name());
                ArrayNode values = node.putArray("fields");
                fields.forEach(values::add);
            }
        }
        return new DriftResult(
                prior.id(),
                !Objects.equals(
                        prior.contextHash(), outcome.contextHash()),
                !Objects.equals(
                        prior.finalDecision(), outcome.finalDecision()),
                delta(outcome.score(), prior.score()),
                delta(outcome.confidence(), prior.confidence()),
                changed);
    }

    private ObjectNode snapshot(
            AgentTask task,
            List<AgentRun> runs,
            List<FormalVeto> vetoes,
            FinalDecision decision
    ) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("contractVersion",
                AgentShadowContracts.OUTCOME_SNAPSHOT_VERSION);
        root.put("taskId", task.id());
        root.put("taskStatus", task.status().name());
        root.put("ruleVersion", task.ruleVersion());
        root.put("contextHash", task.contextHash());
        if (decision == null) {
            root.putNull("finalDecision");
        } else {
            root.set("finalDecision",
                    objectMapper.valueToTree(decision));
        }
        List<String> vetoIds = vetoes.stream()
                .map(FormalVeto::vetoId)
                .sorted()
                .toList();
        ArrayNode vetoValues = root.putArray("vetoIds");
        vetoIds.forEach(vetoValues::add);
        ArrayNode runValues = root.putArray("runs");
        for (AgentRun run : runs) {
            ObjectNode value = runValues.addObject();
            value.put("agentCode", run.agentCode().name());
            value.put("runId", run.id());
            value.put("status", run.status().name());
            value.put("gateStatus", run.gateStatus().name());
            value.put("decision", run.decision().name());
            putNullable(value, "score", run.score());
            putNullable(value, "confidence", run.confidence());
            value.put("veto", run.veto());
            ArrayNode errors = value.putArray("errors");
            structuredErrors(run).forEach(errors::add);
            if (run.agentCode() == AgentCode.POSITION_RISK) {
                ArrayNode formalVetoIds =
                        value.putArray("formalVetoIds");
                vetoIds.forEach(formalVetoIds::add);
            }
        }
        return root;
    }

    private List<String> reasonCodes(List<AgentRun> runs) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (AgentRun run : runs) {
            for (JsonNode error : structuredErrors(run)) {
                String code = error.path("code").asText("").trim();
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
        }
        if (codes.isEmpty()) {
            codes.add(
                    AgentShadowContracts.FALLBACK_INSUFFICIENT_REASON);
        }
        return List.copyOf(codes);
    }

    private static List<AgentRun> orderedRuns(List<AgentRun> runs) {
        Map<AgentCode, AgentRun> byCode = new LinkedHashMap<>();
        runs.forEach(run -> byCode.put(run.agentCode(), run));
        if (byCode.size() != AgentShadowContracts.AGENT_ORDER.size()) {
            throw new IllegalStateException(
                    "shadow outcome requires exactly six agent runs");
        }
        return AgentShadowContracts.AGENT_ORDER.stream()
                .map(code -> {
                    AgentRun run = byCode.get(code);
                    if (run == null) {
                        throw new IllegalStateException(
                                "missing shadow run: " + code);
                    }
                    return run;
                })
                .toList();
    }

    private static List<JsonNode> structuredErrors(AgentRun run) {
        JsonNode errors = run.outputJson() == null
                ? null : run.outputJson().path("errors");
        if (errors == null || !errors.isArray()) {
            return List.of();
        }
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode error : errors) {
            if (error.isObject()
                    && !error.path("code").asText("").isBlank()) {
                result.add(error.deepCopy());
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, JsonNode> runsByCode(JsonNode snapshot) {
        if (snapshot == null
                || !snapshot.path("runs").isArray()) {
            return Map.of();
        }
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode run : snapshot.path("runs")) {
            String code = run.path("agentCode").asText("");
            if (!code.isBlank()) {
                result.put(code, run);
            }
        }
        return Map.copyOf(result);
    }

    private static List<String> changedFields(
            JsonNode previous,
            JsonNode current
    ) {
        List<String> fields = List.of(
                "status",
                "gateStatus",
                "decision",
                "score",
                "confidence",
                "veto",
                "errors",
                "formalVetoIds");
        if (previous == null || current == null) {
            return fields;
        }
        return fields.stream()
                .filter(field -> !Objects.equals(
                        previous.get(field), current.get(field)))
                .toList();
    }

    private static Integer delta(Integer current, Integer previous) {
        return current == null || previous == null
                ? null : current - previous;
    }

    private static long duration(Instant startedAt, Instant finishedAt) {
        return startedAt == null
                ? 0
                : Math.max(0,
                Duration.between(startedAt, finishedAt).toMillis());
    }

    private static boolean terminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.PARTIAL
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    private static String safeError(
            String message,
            String fallback
    ) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500
                ? normalized
                : normalized.substring(0, 500);
    }

    private static void putNullable(
            ObjectNode node,
            String field,
            Integer value
    ) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
