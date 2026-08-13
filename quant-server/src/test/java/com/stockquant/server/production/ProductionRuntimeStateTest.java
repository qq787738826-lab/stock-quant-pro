package com.stockquant.server.production;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionRuntimeStateTest {
    @AfterEach
    void clear() {
        ProductionRuntimeState.clear();
    }

    @Test
    void acceptsOnlyBoundV16LocalProductionState() {
        var value = new ProductionRuntimeState.Snapshot("a".repeat(40),
                "b".repeat(64), Instant.parse("2026-08-13T00:00:00Z"),
                38_432, 16, false, true);
        ProductionRuntimeState.install(value);

        assertEquals(value, ProductionRuntimeState.require());
        assertFalse(value.migrationApplied());
        assertThrows(IllegalStateException.class,
                () -> ProductionRuntimeState.install(value));
    }

    @Test
    void rejectsWrongPortOrVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionRuntimeState.Snapshot("a".repeat(40),
                        "b".repeat(64), Instant.now(), 5432, 16,
                        false, true));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductionRuntimeState.Snapshot("a".repeat(40),
                        "b".repeat(64), Instant.now(), 38_432, 15,
                        false, true));
    }
}
