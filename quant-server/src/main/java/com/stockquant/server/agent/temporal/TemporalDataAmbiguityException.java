package com.stockquant.server.agent.temporal;

public class TemporalDataAmbiguityException extends IllegalStateException {
    public TemporalDataAmbiguityException(String message) {
        super(message);
    }
}
