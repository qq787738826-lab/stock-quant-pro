package com.stockquant.server.agent.exception;

public class AgentTaskNotFoundException extends RuntimeException {
    public AgentTaskNotFoundException(long taskId) {
        super("智能体任务不存在：" + taskId);
    }
}
