package com.stockquant.server.agent.service;

import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskStartService {

    private final AgentTaskRepository taskRepository;
    private final AgentRunRepository runRepository;

    public AgentTaskStartService(AgentTaskRepository taskRepository, AgentRunRepository runRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
    }

    @Transactional
    public boolean claim(long taskId) {
        if (taskRepository.claimQueuedTask(taskId) == 0) {
            return false;
        }
        runRepository.markAllRunning(taskId);
        return true;
    }
}
