package com.stockquant.server.agent.exception;

public class AgentTeamException extends RuntimeException {
    public AgentTeamException(String message) {
        super(message);
    }

    public AgentTeamException(String message, Throwable cause) {
        super(message, cause);
    }
}
