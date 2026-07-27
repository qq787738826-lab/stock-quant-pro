package com.stockquant.server.agent.shadow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.AgentRun;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.FinalDecisionCode;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentVetoRepository;
import com.stockquant.server.agent.shadow.AgentShadowModels.OutcomeClass;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentShadowOutcomeServiceTest {

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AgentRunRepository runRepository =
            mock(AgentRunRepository.class);
    private final AgentDecisionRepository decisionRepository =
            mock(AgentDecisionRepository.class);
    private final AgentVetoRepository vetoRepository =
            mock(AgentVetoRepository.class);
    private final AgentShadowRepository shadowRepository =
            mock(AgentShadowRepository.class);
    private final AgentShadowOutcomeService service =
            new AgentShadowOutcomeService(
                    runRepository,
                    decisionRepository,
                    vetoRepository,
                    shadowRepository,
                    mapper);

    @Test
    void extractsInsufficiencyCodesInFixedAgentOrder() {
        AgentTask task = task(TaskStatus.PARTIAL);
        List<AgentRun> runs = new ArrayList<>();
        for (AgentCode code : AgentCode.PROFESSIONAL_AGENTS) {
            String reason = switch (code) {
                case DATA_QUALITY -> "DATA_QUALITY_REASON";
                case STRATEGY_BACKTEST -> "BACKTEST_REASON";
                case POSITION_RISK -> "PORTFOLIO_REASON";
                default -> null;
            };
            runs.add(run(code, reason));
        }
        Collections.reverse(runs);
        when(runRepository.findByTaskId(task.id()))
                .thenReturn(runs);
        when(vetoRepository.findByTaskId(task.id()))
                .thenReturn(List.of());
        when(decisionRepository.findByTaskId(task.id()))
                .thenReturn(Optional.of(decision(
                        FinalDecisionCode.INSUFFICIENT_DATA)));

        var outcome = service.terminalOutcome(
                item(), task, Instant.EPOCH.plusSeconds(2));

        assertEquals(OutcomeClass.INSUFFICIENT,
                outcome.outcomeClass());
        assertEquals(List.of(
                        "DATA_QUALITY_REASON",
                        "BACKTEST_REASON",
                        "PORTFOLIO_REASON"),
                outcome.reasonCodes());
        assertEquals(
                AgentCode.PROFESSIONAL_AGENTS.stream()
                        .map(Enum::name).toList(),
                java.util.stream.StreamSupport.stream(
                                outcome.runSnapshot()
                                        .path("runs").spliterator(),
                                false)
                        .map(value -> value.path("agentCode").asText())
                        .toList());
        assertFalse(outcome.vetoed());
    }

    @Test
    void usesStableFallbackWhenNoStructuredReasonExists() {
        AgentTask task = task(TaskStatus.PARTIAL);
        when(runRepository.findByTaskId(task.id()))
                .thenReturn(AgentCode.PROFESSIONAL_AGENTS.stream()
                        .map(code -> run(code, null)).toList());
        when(vetoRepository.findByTaskId(task.id()))
                .thenReturn(List.of());
        when(decisionRepository.findByTaskId(task.id()))
                .thenReturn(Optional.of(decision(
                        FinalDecisionCode.INSUFFICIENT_DATA)));

        var outcome = service.terminalOutcome(
                item(), task, Instant.EPOCH.plusSeconds(2));

        assertEquals(
                List.of(AgentShadowContracts
                        .FALLBACK_INSUFFICIENT_REASON),
                outcome.reasonCodes());
    }

    private AgentTask task(TaskStatus status) {
        return new AgentTask(
                99,
                "600000",
                LocalDate.of(2026, 7, 27),
                status,
                "1.0",
                mapper.createObjectNode(),
                Instant.EPOCH,
                "a".repeat(64),
                AgentShadowContracts.RULE_VERSION,
                ExecutionMode.LOCAL_RULES,
                TriggerType.SHADOW,
                "shadow:1",
                false,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1));
    }

    private AgentRun run(AgentCode code, String reason) {
        ObjectNode output = mapper.createObjectNode();
        var errors = output.putArray("errors");
        if (reason != null) {
            errors.addObject()
                    .put("code", reason)
                    .put("message", reason);
        }
        return new AgentRun(
                code.ordinal() + 1,
                99,
                code,
                1,
                RunStatus.INSUFFICIENT_DATA,
                GateStatus.NOT_APPLICABLE,
                RunDecision.NOT_APPLICABLE,
                0,
                0,
                false,
                "insufficient",
                output,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1),
                1L,
                null,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(1));
    }

    private FinalDecision decision(FinalDecisionCode code) {
        return new FinalDecision(
                "1.0",
                99,
                code,
                GateStatus.NOT_APPLICABLE,
                false,
                0,
                0,
                "insufficient",
                List.of(),
                List.of(1L, 2L, 3L, 4L, 5L, 6L),
                List.of(),
                "a".repeat(64),
                LocalDate.of(2026, 7, 27),
                AgentShadowContracts.RULE_VERSION,
                ExecutionMode.LOCAL_RULES,
                Instant.EPOCH.plusSeconds(1));
    }

    private ShadowItem item() {
        return new ShadowItem(
                1,
                1,
                1,
                "600000",
                SelectionSource.EXPLICIT,
                "explicit",
                99L,
                true,
                false,
                TaskStatus.RUNNING,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                Instant.EPOCH,
                null,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
