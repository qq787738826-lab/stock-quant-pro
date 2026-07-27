package com.stockquant.server.agent.shadow;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentShadowFoundationMigrationTest {

    private static final String MIGRATION =
            "db/migration/V11__agent_shadow_run_foundation.sql";

    @Test
    void freezesOnlyTheApprovedShadowFoundation() throws Exception {
        String sql;
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertNotNull(stream);
            sql = new String(
                    stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        assertEquals(3,
                normalized.split("create table ", -1).length - 1);
        for (String table : List.of(
                "agent_shadow_batches",
                "agent_shadow_items",
                "agent_shadow_reviews")) {
            assertTrue(normalized.contains(
                    "create table " + table), table);
            assertTrue(normalized.contains(
                    "before truncate on " + table), table);
        }
        for (String required : List.of(
                "'shadow'",
                "shadow_run_control_v1",
                "shadow_selection_v1",
                "shadow_outcome_snapshot_v1",
                "shadow_review_v1",
                "shadow_metrics_v1",
                "uq_agent_shadow_batches_one_active",
                "unique (batch_id, symbol)",
                "terminal agent_shadow_batches are immutable",
                "terminal agent_shadow_items are immutable",
                "append-only",
                "supersedes_review_id")) {
            assertTrue(normalized.contains(required), required);
        }
        for (String forbidden : List.of(
                "alter table daily_bars",
                "alter table positions",
                "update positions",
                "delete from agent_tasks",
                "truncate agent_tasks",
                "flyway_schema_history")) {
            assertFalse(normalized.contains(forbidden), forbidden);
        }
    }
}
