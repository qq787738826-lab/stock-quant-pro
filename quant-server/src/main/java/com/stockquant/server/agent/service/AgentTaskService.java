package com.stockquant.server.agent.service;

import com.stockquant.server.agent.api.CreateAgentTaskRequest;
import com.stockquant.server.agent.exception.AgentTaskNotFoundException;
import com.stockquant.server.agent.model.AgentModels.AgentTask;
import com.stockquant.server.agent.model.AgentModels.CacheKey;
import com.stockquant.server.agent.model.AgentModels.ContextSnapshot;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentModels.PageResult;
import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import com.stockquant.server.agent.repository.AgentEvidenceRepository;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentTaskService {

    private final AgentTaskRepository taskRepository;
    private final AgentRunRepository runRepository;
    private final AgentEvidenceRepository evidenceRepository;
    private final AgentDecisionRepository decisionRepository;
    private final AgentCacheService cacheService;
    private final AgentContextSnapshotService contextSnapshotService;
    private final AgentTaskCreationTransaction creationTransaction;

    public AgentTaskService(
            AgentTaskRepository taskRepository,
            AgentRunRepository runRepository,
            AgentEvidenceRepository evidenceRepository,
            AgentDecisionRepository decisionRepository,
            AgentCacheService cacheService,
            AgentContextSnapshotService contextSnapshotService,
            AgentTaskCreationTransaction creationTransaction
    ) {
        this.taskRepository = taskRepository;
        this.runRepository = runRepository;
        this.evidenceRepository = evidenceRepository;
        this.decisionRepository = decisionRepository;
        this.cacheService = cacheService;
        this.contextSnapshotService = contextSnapshotService;
        this.creationTransaction = creationTransaction;
    }

    public CreatedTask create(CreateAgentTaskRequest request, String requestedBy) {
        validate(request);
        ContextSnapshot context = contextSnapshotService.create(request.symbol(), request.tradeDate());
        CacheKey key = new CacheKey(
                request.symbol(),
                request.tradeDate(),
                context.contextHash(),
                request.ruleVersion(),
                request.executionMode()
        );

        return cacheService.active(key)
                .map(task -> new CreatedTask(task, false))
                .orElseGet(() -> completedOrCreate(request, requestedBy, context, key));
    }

    private CreatedTask completedOrCreate(
            CreateAgentTaskRequest request,
            String requestedBy,
            ContextSnapshot context,
            CacheKey key
    ) {
        if (!request.forceRefresh()) {
            var completed = cacheService.completed(key);
            if (completed.isPresent()) {
                return new CreatedTask(completed.get(), false);
            }
        }
        try {
            return creationTransaction.create(request, context, requestedBy);
        } catch (DuplicateKeyException conflict) {
            return cacheService.active(key)
                    .map(task -> new CreatedTask(task, false))
                    .orElseThrow(() -> conflict);
        }
    }

    public AgentTask task(long taskId) {
        return taskRepository.findById(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
    }

    public List<?> runs(long taskId) {
        requireTask(taskId);
        return runRepository.findByTaskId(taskId);
    }

    public List<?> evidence(long taskId) {
        requireTask(taskId);
        return evidenceRepository.findByTaskId(taskId);
    }

    public Object decision(long taskId) {
        requireTask(taskId);
        return decisionRepository.findByTaskId(taskId).orElse(null);
    }

    public PageResult<AgentTask> history(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page不能小于0");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size必须在1到100之间");
        }
        return taskRepository.history(page, size);
    }

    private void requireTask(long taskId) {
        task(taskId);
    }

    private static void validate(CreateAgentTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("任务请求不能为空");
        }
        if (request.symbol() == null || !request.symbol().matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("symbol必须是6位数字");
        }
        if (request.tradeDate() == null) {
            throw new IllegalArgumentException("tradeDate不能为空");
        }
        if (request.ruleVersion() == null || request.ruleVersion().isBlank()) {
            throw new IllegalArgumentException("ruleVersion不能为空");
        }
        if (request.ruleVersion().length() > 64) {
            throw new IllegalArgumentException("ruleVersion长度不能超过64字符");
        }
        if (request.executionMode() != ExecutionMode.LOCAL_RULES) {
            throw new IllegalArgumentException("executionMode当前只允许LOCAL_RULES");
        }
        if (request.triggerType() == null) {
            throw new IllegalArgumentException("triggerType不能为空");
        }
    }
}
