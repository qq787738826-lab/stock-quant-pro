package com.stockquant.server.agent.ingestion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionFoundationMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V7__market_data_ingestion_foundation.sql");

    @Test
    void migrationContainsOnlySourceNeutralIngestionFoundation() throws IOException {
        String sql = Files.readString(MIGRATION).toLowerCase(Locale.ROOT);
        assertEquals(7, count(sql, "create table "));
        for (String table : new String[]{
                "market_data_ingestion_runs",
                "security_status_raw_records", "security_status_processing_attempts",
                "trading_calendar_raw_records", "trading_calendar_processing_attempts",
                "security_status_ingestion_run_records",
                "trading_calendar_ingestion_run_records"}) {
            assertTrue(sql.contains("create table " + table));
        }
        assertTrue(sql.contains("formal ingestion is unavailable before an approved source adapter"));
        assertTrue(sql.contains("pit_verified is unavailable before an approved source adapter"));
        assertTrue(sql.contains("before truncate"));
        assertTrue(sql.contains("knowledge_time_policy_v1"));
        assertTrue(sql.contains("processing attempt requires an attached run raw record"));
        assertTrue(sql.contains("raw record first ingestion run association is required"));
        assertTrue(sql.contains("compute_ingestion_manifest_hash"));
        assertTrue(sql.contains("compute_ingestion_json_hash"));
        assertTrue(sql.contains("attempt_no"));
        assertTrue(sql.contains("retry_of_run_logical_key"));
        assertTrue(sql.contains("root_request_logical_key"));
        assertTrue(sql.contains("run_attempt_number"));
        assertTrue(sql.contains("requested_range_start"));
        assertTrue(sql.contains("requested_range_end"));
        assertTrue(sql.contains("source_instrument_id"));
        assertTrue(sql.contains("exchange in ('sse', 'szse')"));
        assertTrue(sql.contains("trade_date"));
        assertTrue(sql.contains("identity_unresolved"));
        assertTrue(sql.contains("result_metadata = '{}'::jsonb"));
        assertTrue(sql.contains("greatest(clock_timestamp(), raw_first_observed_at)"));
        String manifestFunction = sql.substring(
                sql.indexOf("create or replace function compute_ingestion_manifest_hash"),
                sql.indexOf("create or replace function reject_ingestion_fact_mutation"));
        assertFalse(manifestFunction.contains("completed_at"));
        assertTrue(sql.contains("set search_path to pg_catalog"));
        assertFalse(sql.contains("create extension pgcrypto"));
        assertFalse(sql.contains("market_universe_snapshot"));
        assertFalse(sql.contains("security_status_event_correction_targets"));
        assertFalse(sql.contains("alter table security_status_history"));
        assertFalse(sql.contains("alter table trading_calendar_revisions"));
    }

    private static int count(String value, String needle) {
        int total = 0;
        for (int index = value.indexOf(needle); index >= 0;
             index = value.indexOf(needle, index + needle.length())) {
            total++;
        }
        return total;
    }
}
