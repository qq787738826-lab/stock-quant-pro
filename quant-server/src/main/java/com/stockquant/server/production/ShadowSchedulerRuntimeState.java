package com.stockquant.server.production;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public final class ShadowSchedulerRuntimeState {
    private final AtomicReference<Instant> lastChecked = new AtomicReference<>();
    private final AtomicReference<Instant> lastDispatch = new AtomicReference<>();
    private final AtomicReference<String> lastReason =
            new AtomicReference<>("AWAITING_SCHEDULE");

    public void checked(Instant at, String reason) {
        lastChecked.set(at);
        lastReason.set(reason);
    }

    public void dispatched(Instant at) {
        lastDispatch.set(at);
        lastReason.set("DISPATCHED");
    }

    public Snapshot snapshot() {
        return new Snapshot(lastChecked.get(), lastDispatch.get(),
                lastReason.get());
    }

    public record Snapshot(
            Instant lastCheckedAt,
            Instant lastDispatchedAt,
            String lastReason
    ) {
    }
}
