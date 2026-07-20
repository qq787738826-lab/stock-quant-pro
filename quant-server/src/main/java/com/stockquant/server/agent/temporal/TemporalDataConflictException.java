package com.stockquant.server.agent.temporal;

public class TemporalDataConflictException extends IllegalStateException {
    public TemporalDataConflictException(String message) {
        super(message);
    }
}
