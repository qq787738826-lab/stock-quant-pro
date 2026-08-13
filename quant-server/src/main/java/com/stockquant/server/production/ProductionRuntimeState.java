package com.stockquant.server.production;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable startup identity shared by the M6 health and backup surfaces. */
public final class ProductionRuntimeState {
    public static final String VERSION = "RESEARCH_PRODUCTION_V1";

    private static final AtomicReference<Snapshot> CURRENT =
            new AtomicReference<>();

    private ProductionRuntimeState() {
    }

    public static void install(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!CURRENT.compareAndSet(null, snapshot)) {
            throw new IllegalStateException("M6_RUNTIME_ALREADY_INSTALLED");
        }
    }

    public static Snapshot require() {
        Snapshot value = CURRENT.get();
        if (value == null) {
            throw new IllegalStateException("M6_RUNTIME_NOT_INSTALLED");
        }
        return value;
    }

    public static void clear() {
        CURRENT.set(null);
    }

    public record Snapshot(
            String gitCommit,
            String artifactSha256,
            Instant startedAt,
            int databasePort,
            int schemaVersion,
            boolean migrationApplied,
            boolean outputAuditInstalled
    ) {
        public Snapshot {
            if (gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")
                    || artifactSha256 == null
                    || !artifactSha256.matches("[0-9a-f]{64}")
                    || startedAt == null || databasePort != 38_432
                    || schemaVersion != 16 || !outputAuditInstalled) {
                throw new IllegalArgumentException("M6_RUNTIME_STATE_INVALID");
            }
        }
    }
}
