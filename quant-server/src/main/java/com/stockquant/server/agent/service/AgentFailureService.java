package com.stockquant.server.agent.service;

import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentFailureService {

    private static final int MAX_ERROR_LENGTH = 900;

    private final AgentTaskRepository taskRepository;
    private final AgentRunRepository runRepository;

    public AgentFailureService(AgentTaskRepository taskRepository, AgentRunRepository runRepository) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long taskId, String errorMessage, long durationMs) {
        String safeMessage = sanitize(errorMessage);
        runRepository.markUnfinishedFailed(taskId, safeMessage, Math.max(durationMs, 0));
        taskRepository.markFailed(taskId, safeMessage);
    }

    private static String sanitize(String message) {
        String value = message == null || message.isBlank() ? "智能体团队执行失败" : message;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
