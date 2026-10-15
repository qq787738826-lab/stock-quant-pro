package com.stockquant.server.production;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory operational projection; Broker files remain authoritative. */
@Component
public final class AutomaticMarketDataMaintenanceRuntimeState {
    private final AtomicReference<Snapshot> state = new AtomicReference<>(
            new Snapshot(null, null, "NONE", "AWAITING_SCHEDULE"));

    public void checked(
            Instant at,
            LocalDate tradeDate,
            String requestId,
            String reason
    ) {
        state.set(new Snapshot(at, tradeDate,
                requestId == null ? "NONE" : requestId, reason));
    }

    public Snapshot snapshot() {
        return state.get();
    }

    public record Snapshot(
            Instant lastCheckedAt,
            LocalDate tradeDate,
            String requestId,
            String lastReason
    ) {
    }
}
