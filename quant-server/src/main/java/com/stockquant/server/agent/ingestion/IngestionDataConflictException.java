package com.stockquant.server.agent.ingestion;

public class IngestionDataConflictException extends RuntimeException {
    public IngestionDataConflictException(String message) {
        super(message);
    }
}
