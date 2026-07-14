package com.stockquant.server.agent.exception;

public class AgentTeamClientException extends AgentTeamException {

    public enum Kind {
        DISABLED,
        SERVICE_UNAVAILABLE
    }

    private final Kind kind;

    public AgentTeamClientException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public AgentTeamClientException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
