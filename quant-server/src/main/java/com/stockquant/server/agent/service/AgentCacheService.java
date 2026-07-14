package com.stockquant.server.agent.service;

import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.CacheKey;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AgentCacheService {

    private final AgentTaskRepository taskRepository;

    public AgentCacheService(AgentTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public Optional<AgentTask> active(CacheKey key) {
        return taskRepository.findActive(key);
    }

    public Optional<AgentTask> completed(CacheKey key) {
        return taskRepository.findLatestCompleted(key);
    }
}
