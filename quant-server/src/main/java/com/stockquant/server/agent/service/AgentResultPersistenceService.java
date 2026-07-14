package com.stockquant.server.agent.service;

import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.DecisionStatus;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import com.stockquant.server.agent.repository.AgentEvidenceRepository;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.repository.AgentVetoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

@Service
public class AgentResultPersistenceService {

    private final AgentRunRepository runRepository;
    private final AgentEvidenceRepository evidenceRepository;
    private final AgentVetoRepository vetoRepository;
    private final AgentDecisionRepository decisionRepository;
    private final AgentTaskRepository taskRepository;

    public AgentResultPersistenceService(
            AgentRunRepository runRepository,
            AgentEvidenceRepository evidenceRepository,
            AgentVetoRepository vetoRepository,
            AgentDecisionRepository decisionRepository,
            AgentTaskRepository taskRepository
    ) {
        this.runRepository = runRepository;
        this.evidenceRepository = evidenceRepository;
        this.vetoRepository = vetoRepository;
        this.decisionRepository = decisionRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public void persist(AgentTeamResponse response, Duration duration) {
        for (AgentOutput output : response.agentRuns()) {
            runRepository.updateResult(output, duration);
        }

        Map<String, Long> evidenceOwners = evidenceOwners(response.agentRuns());
        evidenceRepository.saveAll(response.taskId(), response.evidence(), evidenceOwners);
        Map<String, Long> vetoIds = vetoRepository.saveAll(response.vetoes());

        DecisionStatus decisionStatus = decisionStatus(response.agentRuns());
        decisionRepository.save(response.finalDecision(), decisionStatus.name(), vetoIds, duration);
        taskRepository.markFinished(response.taskId(), taskStatus(decisionStatus));
    }

    private static Map<String, Long> evidenceOwners(List<AgentOutput> outputs) {
        Map<String, java.util.Set<Long>> candidates = new HashMap<>();
        for (AgentOutput output : outputs) {
            for (Evidence evidence : output.evidence()) {
                candidates.computeIfAbsent(evidence.evidenceId(), ignored -> new HashSet<>()).add(output.runId());
            }
        }
        Map<String, Long> owners = new HashMap<>();
        candidates.forEach((evidenceId, runIds) -> {
            if (runIds.size() == 1) {
                owners.put(evidenceId, runIds.iterator().next());
            }
        });
        return Map.copyOf(owners);
    }

    private static DecisionStatus decisionStatus(List<AgentOutput> outputs) {
        if (outputs.stream().anyMatch(output -> output.status() == RunStatus.PARTIAL
                || output.status() == RunStatus.FAILED)) {
            return DecisionStatus.PARTIAL;
        }
        if (outputs.stream().anyMatch(output -> output.status() == RunStatus.INSUFFICIENT_DATA)) {
            return DecisionStatus.INSUFFICIENT_DATA;
        }
        return DecisionStatus.COMPLETED;
    }

    private static TaskStatus taskStatus(DecisionStatus status) {
        return switch (status) {
            case COMPLETED -> TaskStatus.COMPLETED;
            case PARTIAL, INSUFFICIENT_DATA -> TaskStatus.PARTIAL;
            case FAILED -> TaskStatus.FAILED;
        };
    }
}
