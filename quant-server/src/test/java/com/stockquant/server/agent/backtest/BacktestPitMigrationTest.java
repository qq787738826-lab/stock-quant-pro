package com.stockquant.server.agent.backtest;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestPitMigrationTest {

    private static final String MIGRATION =
            "db/migration/V9__backtest_pit_daily_bar_observations.sql";

    @Test
    void createsOnlyApprovedAppendOnlyPitObservationModel() throws Exception {
        String sql;
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "V9 migration must exist");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        assertEquals(2, normalized.split("create table ", -1).length - 1);
        for (String table : List.of(
                "market_data_observation_batches",
                "daily_bar_observations")) {
            assertTrue(normalized.contains("create table " + table));
            assertTrue(normalized.contains("before update or delete on " + table));
            assertTrue(normalized.contains("before truncate on " + table));
        }
        for (String required : List.of(
                "observation_version",
                "canonical_content_hash",
                "first_observed_at",
                "known_at",
                "source_revision",
                "dataset_version",
                "batch_version",
                "idx_daily_bar_observations_as_of",
                "known_at desc",
                "adjust_type = 'qfq'")) {
            assertTrue(normalized.contains(required), required);
        }
        for (String forbidden : List.of(
                "drop table",
                "delete from",
                "update daily_bars",
                "alter table daily_bars",
                "alter table securities",
                "insert into daily_bars",
                "flyway_schema_history")) {
            assertFalse(normalized.contains(forbidden), forbidden);
        }
    }
}
