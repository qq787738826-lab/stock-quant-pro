package com.stockquant.server.agent.temporal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalMarketFoundationMigrationTest {

    private static final String MIGRATION = "db/migration/V6__temporal_market_foundation.sql";

    @Test
    void freezesOnlyTheApprovedTemporalFoundationSchema() throws IOException {
        String sql;
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "V6 temporal foundation migration must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        assertEquals(4, normalized.split("create table ", -1).length - 1,
                "V6 must create exactly the four approved temporal tables");

        for (String table : List.of(
                "market_data_dataset_versions",
                "security_status_events",
                "security_status_history",
                "trading_calendar_revisions"
        )) {
            assertTrue(normalized.contains("create table " + table), table);
        }
        assertTrue(normalized.contains("create extension if not exists btree_gist"));
        assertTrue(normalized.contains("ex_security_status_history_bitemporal_overlap"));
        assertTrue(normalized.contains("daterange(valid_from, valid_to, '[)')"));
        assertTrue(normalized.contains("tstzrange(known_from, known_to, '[)')"));
        assertTrue(normalized.contains("ex_trading_calendar_revisions_knowledge_overlap"));
        assertTrue(normalized.contains("where known_to is null"));
        assertTrue(normalized.contains("backfilled_inferred"));
        assertTrue(normalized.contains("jsonb not null default '{}'::jsonb"));
        assertTrue(normalized.contains("trg_security_status_events_append_only"));
        assertTrue(normalized.contains("before update on security_status_events"));

        for (String forbiddenTable : List.of(
                "security_universe_snapshot",
                "security_universe_snapshot_member",
                "daily_bar_revision",
                "corporate_action_revision",
                "adjustment_factor_snapshot",
                "market_regime_evaluation_case"
        )) {
            assertFalse(normalized.contains("create table " + forbiddenTable), forbiddenTable);
        }
        for (String destructive : List.of("truncate ", "drop table", "delete from", "alter table securities",
                "alter table daily_bars", "insert into securities", "insert into daily_bars")) {
            assertFalse(normalized.contains(destructive), destructive);
        }
        assertFalse(normalized.contains("references securities"));
        assertFalse(normalized.contains("timestamp without time zone"));
    }
}
